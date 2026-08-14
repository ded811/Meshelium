/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Ported from Nvidium by MCRcortex (LGPL-3.0) — the NON-SPARSE fallback
 * path (the one every Linux Nvidium user runs, Nvidium.java:43-46):
 *   misc/reference/nvidium/src/main/java/me/cortex/nvidium/util/BufferArena.java
 * (Alphadium's BufferArena is byte-identical modulo package rename.)
 *
 * Design-time fixes over the original (study Q13 + §3 port notes), each
 * pinned by MesheliumTerrainDataTest:
 *   1. DEFERRED FREE: the original TODO at BufferArena.java:8-9 ("wait until
 *      the end of a frame to deallocate") is also a use-after-free hazard
 *      under Vulkan — the GPU may still read a range freed mid-frame. Freed
 *      ranges here enter a pending list and only re-enter the allocator when
 *      the render loop calls releasePending() after its frame fence.
 *   2. STATS LEAK: the original inflates totalQuads BEFORE checking for
 *      allocation failure (BufferArena.java:35-39), permanently skewing the
 *      eviction heuristic's inputs on every failed alloc. Fixed: counted
 *      only on success.
 *   3. Double-free throws immediately instead of corrupting the free list
 *      frames later.
 */
package com.deds.meshelium.terrain;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Quad-granularity suballocator over ONE fixed backing range — the CPU side
 * of the terrain geometry pool. Address unit = 1 quad = {@code 4 *
 * vertexStride} bytes (64 for the 16-byte compact vertex); byte offset =
 * {@code Integer.toUnsignedLong(addr) * 4 * vertexStride}
 * (BufferArena.java:41,50,55).
 *
 * <p>Quad address 0 is RESERVED at construction (BufferArena.java:30-31) —
 * it doubles as the section record's "empty" sentinel in header.w
 * (see {@link SectionRecord}).</p>
 *
 * <p><b>Free is two-phase:</b> {@link #free(int)} retires the address from
 * the caller's point of view (stats drop, double-free guarded) but the range
 * stays unavailable to {@link #allocQuads(int)} until
 * {@link #releasePending()} — which wave 3b's render loop calls once its
 * frame fence proves the GPU is done reading. Tests call it directly.</p>
 */
public final class TerrainArena {

    /** Failed-allocation sentinel ({@code == (int) SegmentedManager.SIZE_LIMIT}). */
    public static final int ALLOC_FAILED = (int) SegmentedManager.SIZE_LIMIT;

    /**
     * One suballocator per BLOCK. Block k owns absolute quad addresses
     * [k << blockShift, (k << blockShift) + physicalQuads[k]).
     *
     * <p>THE INVARIANT THAT MAKES THE SPLIT SAFE: a block's physical
     * capacity never exceeds its address stride, so a local address can
     * never reach into the next block's range, and therefore no single
     * allocation can straddle a block boundary. It falls out of
     * construction rather than being checked per allocation - each block's
     * suballocator is given a limit of its own physical quads and cannot
     * hand out anything past it - and it is asserted anyway at every commit
     * because the whole design rests on it.</p>
     */
    private final ArenaBacking backing;
    private final List<SegmentedManager> blockSegments = new ArrayList<>();
    /** Opaque backing handle per block, parallel to {@link #blockSegments}. */
    private final LongArrayList blockHandles = new LongArrayList();
    /** Physical quad capacity per block; always <= {@link #blockQuads}. */
    private final LongArrayList blockPhysicalQuads = new LongArrayList();

    /** Address stride of one block in quads; a power of two. */
    private final long blockQuads;
    private final int blockShift;
    private final long blockMask;
    /** Hard cap on committed blocks (descriptor-array length). */
    private final int maxBlocks;

    private long memoryBytes;
    private final int vertexStride;
    private long quadLimit;

    private long liveQuads;
    private int liveAllocations;

    private final IntArrayList pendingFreeList = new IntArrayList();
    private final IntOpenHashSet pendingFreeSet = new IntOpenHashSet();
    private long pendingQuads;

    /**
     * @param backing supplies the single backing range (called once, here)
     * @param memoryBytes fixed arena capacity in bytes
     * @param vertexStride bytes per vertex (16 for the compact format —
     *                     NvidiumCompactChunkVertex STRIDE)
     */
    public TerrainArena(ArenaBacking backing, long memoryBytes, int vertexStride) {
        this(backing, memoryBytes, vertexStride, memoryBytes, 1);
    }

    /**
     * @param blockBytes address stride of one block; MUST be a power of two
     *        so the shader decodes an address with a shift and a mask
     * @param maxBlocks descriptor-array length, the hard cap on blocks
     */
    public TerrainArena(ArenaBacking backing, long memoryBytes, int vertexStride,
            long blockBytes, int maxBlocks) {
        if (memoryBytes <= 0 || vertexStride <= 0) {
            throw new IllegalArgumentException("memoryBytes and vertexStride must be positive");
        }
        long quadBytes = 4L * vertexStride;
        if (blockBytes < quadBytes || Long.bitCount(blockBytes) != 1) {
            throw new IllegalArgumentException("blockBytes must be a power of two >= " + quadBytes
                    + ", got " + blockBytes);
        }
        if (maxBlocks < 1) {
            throw new IllegalArgumentException("maxBlocks must be >= 1, got " + maxBlocks);
        }
        this.backing = backing;
        this.vertexStride = vertexStride;
        this.maxBlocks = maxBlocks;
        this.blockQuads = blockBytes / quadBytes;
        this.blockShift = Long.numberOfTrailingZeros(this.blockQuads);
        this.blockMask = this.blockQuads - 1L;

        // Block 0 starts at whatever the caller asked for, which is normally
        // far below a full block: a player who never needs more must not pay
        // for a 2 GiB allocation. Its physical size is therefore smaller than
        // its address stride, which is exactly the invariant above.
        long firstBytes = Math.min(memoryBytes, blockBytes);
        commitBlock(backing.allocate(firstBytes), firstBytes);
        this.memoryBytes = firstBytes;
        this.quadLimit = firstBytes / quadBytes;
        // BufferArena.java:30-31 — reserve quad index 0. Block 0 local 0, so
        // the reserved address really is 0 and header.w == 0 stays a unique
        // tombstone: every other block's local 0 is (k << blockShift), which
        // is nonzero by construction.
        int reserved = allocQuads(1);
        if (reserved != 0) {
            throw new IllegalStateException("reserved quad 0 landed at " + reserved);
        }
    }

    /** Register a freshly allocated block and give it its suballocator. */
    private void commitBlock(long handle, long physicalBytes) {
        long quads = physicalBytes / (4L * vertexStride);
        if (quads > blockQuads) {
            // The no-straddle invariant. If a block were ever physically
            // larger than its address stride, local addresses would run into
            // the next block's range and an allocation could span two
            // buffers - which no single shader read can ever satisfy.
            throw new IllegalStateException("block physical quads " + quads
                    + " exceeds the block address stride " + blockQuads
                    + " - an allocation could straddle two buffers");
        }
        SegmentedManager manager = new SegmentedManager();
        manager.setLimit(quads);
        blockSegments.add(manager);
        blockHandles.add(handle);
        blockPhysicalQuads.add(quads);
    }

    /**
     * Allocate {@code quadCount} contiguous quads. Returns the quad-unit
     * address, or {@link #ALLOC_FAILED} when the arena is out of memory
     * (the caller deletes the section, SectionManager.java:77-85).
     */
    public int allocQuads(int quadCount) {
        if (quadCount <= 0) {
            throw new IllegalArgumentException("quadCount must be positive, got " + quadCount);
        }
        if (quadCount > blockQuads) {
            // Larger than a whole block can ever hold. Nothing to search for.
            return ALLOC_FAILED;
        }
        int blocks = blockSegments.size();
        // LOWEST BLOCK THAT FITS, always. This used to start from a cursor
        // parked on whichever block satisfied the last request, on the
        // reasonable-sounding grounds that sections arrive in bursts and the
        // previous block is the best guess.
        //
        // That cursor is why the arena could never give memory back. Blocks
        // return to the driver only when COMPLETELY empty, and a cursor that
        // roams keeps scattering fresh allocations across every block, so
        // after a render-distance drop or a teleport the survivors are
        // smeared over all of them and not one is empty. Packing low lets
        // the high blocks drain on their own, with no compaction and no
        // copying. It is also what MULTIBUFFER-VRAM-PLAN section 3.4 asked
        // for in the first place.
        //
        // Cost is up to N-1 extra red-black descents per allocation, each a
        // fast reject on a full block, against a staging copy in the same
        // loop iteration. Not measurable.
        for (int b = 0; b < blocks; b++) {
            long local = blockSegments.get(b).alloc(quadCount);
            if (local != SegmentedManager.SIZE_LIMIT) {
                liveQuads += quadCount;
                liveAllocations++;
                return (int) ((((long) b) << blockShift) | local);
            }
        }
        return ALLOC_FAILED; // fix 2: totalQuads NOT inflated on failure
    }

    /** Which block owns an absolute quad address. */
    public int blockOf(int addr) {
        return (int) (Integer.toUnsignedLong(addr) >>> blockShift);
    }

    /** Quad index of an address WITHIN its own block. */
    private long localOf(int addr) {
        return Integer.toUnsignedLong(addr) & blockMask;
    }

    private SegmentedManager segmentsOf(int addr) {
        int b = blockOf(addr);
        if (b < 0 || b >= blockSegments.size()) {
            throw new IllegalStateException("quad address " + Integer.toUnsignedString(addr)
                    + " names block " + b + ", which is not committed (" + blockSegments.size()
                    + " blocks) - this is the silent-corruption case, refusing to guess");
        }
        return blockSegments.get(b);
    }

    /**
     * Retire an allocation. The range joins the pending list; it becomes
     * reusable only after {@link #releasePending()}.
     *
     * @throws IllegalStateException on double-free or unknown address
     */
    public void free(int addr) {
        if (pendingFreeSet.contains(addr)) {
            throw new IllegalStateException("double free of quad address " + addr);
        }
        long size = segmentsOf(addr).getSize(addr & (int) blockMask); // throws if not live
        pendingFreeSet.add(addr);
        pendingFreeList.add(addr);
        pendingQuads += size;
        liveQuads -= size;
        liveAllocations--;
    }

    /**
     * Return every pending-freed range to the allocator (coalescing happens
     * here, inside SegmentedManager.free). Call ONLY when the GPU provably
     * no longer reads the ranges — wave 3b gates this on the frame fence.
     *
     * @return number of ranges released
     */
    public int releasePending() {
        int released = pendingFreeList.size();
        for (int i = 0; i < released; i++) {
            int addr = pendingFreeList.getInt(i);
            segmentsOf(addr).free((int) localOf(addr));
        }
        pendingFreeList.clear();
        pendingFreeSet.clear();
        pendingQuads = 0;
        return released;
    }

    /**
     * Wave-14: raise the arena's capacity onto a NEW backing range. The
     * caller (the render-thread pump, under the residency lock) has
     * already: created the new backing, scheduled the GPU copy of every
     * old byte to the same offsets in it, and taken custody of the OLD
     * backing for fence-gated destruction ({@code FREE_FRAME_LAG}). This
     * method only re-points the allocator: same addresses, same offsets,
     * bigger tail. Shrinking or same-size "growth" is a bug — throws.
     */
    public void grow(long newLastBlockBytes, long newBackingHandle) {
        int last = blockSegments.size() - 1;
        long oldBytes = blockPhysicalQuads.getLong(last) * 4L * vertexStride;
        if (newLastBlockBytes <= oldBytes) {
            throw new IllegalArgumentException("arena grow " + oldBytes + " -> "
                    + newLastBlockBytes + " bytes is not a growth");
        }
        long quads = newLastBlockBytes / (4L * vertexStride);
        if (quads > blockQuads) {
            throw new IllegalStateException("grow would take block " + last + " to " + quads
                    + " quads, past its " + blockQuads + " address stride");
        }
        blockHandles.set(last, newBackingHandle);
        blockPhysicalQuads.set(last, quads);
        blockSegments.get(last).setLimit(quads);
        this.memoryBytes += newLastBlockBytes - oldBytes;
        this.quadLimit = totalQuadCapacity();
    }

    /**
     * Add a whole new block. The cheap half of growth: nothing is copied and
     * every existing address keeps its meaning, because a new block occupies
     * a range of the address space nothing has used yet.
     *
     * @return false when the cap is reached or the device refused
     */
    public boolean appendBlock(long blockBytes) {
        if (blockSegments.size() >= maxBlocks) {
            return false;
        }
        long handle = backing.appendBlock(blockBytes);
        if (handle == 0L) {
            return false;
        }
        commitBlock(handle, blockBytes);
        this.memoryBytes += blockBytes;
        this.quadLimit = totalQuadCapacity();
        return true;
    }

    private long totalQuadCapacity() {
        long total = 0;
        for (int i = 0; i < blockPhysicalQuads.size(); i++) {
            total += blockPhysicalQuads.getLong(i);
        }
        return total;
    }

    /** Committed block count. */
    public int blockCount() {
        return blockSegments.size();
    }

    /** Physical capacity of the LAST block, the one {@link #grow} extends. */
    public long lastBlockBytes() {
        return blockPhysicalQuads.getLong(blockPhysicalQuads.size() - 1) * 4L * vertexStride;
    }

    /** Address stride of one block, in bytes. */
    public long blockBytes() {
        return blockQuads * 4L * vertexStride;
    }

    /** Backing handles, index == block. Never null, never empty. */
    public long[] blockHandles() {
        return blockHandles.toLongArray();
    }

    /**
     * True when the live allocation at {@code addr} is exactly
     * {@code quadCount} quads — the section-reupload reuse test
     * (BufferArena.java:87-89; SectionManager.java:66-71).
     */
    public boolean canReuse(int addr, int quadCount) {
        if (pendingFreeSet.contains(addr)) {
            throw new IllegalStateException("address " + addr + " was freed and is pending release");
        }
        return segmentsOf(addr).getSize((int) localOf(addr)) == quadCount;
    }

    /**
     * Byte offset of a quad address WITHIN ITS OWN BLOCK's buffer
     * (BufferArena.java:41, now block-relative).
     *
     * <p>Renamed from {@code byteOffset} deliberately. It used to be an
     * offset into the one arena buffer and is now meaningless without
     * {@link #blockOf(int)} to say which buffer it indexes; letting the old
     * name survive would let a caller keep compiling while writing terrain
     * into the wrong block, which is precisely the silent-corruption class
     * this whole change exists to remove. The rename turns every such
     * caller into a compile error.</p>
     */
    public long byteOffsetInBlock(int addr) {
        return localOf(addr) * 4L * vertexStride;
    }

    /** Byte size of the live allocation at {@code addr} (BufferArena.java:55). */
    public long byteSize(int addr) {
        return segmentsOf(addr).getSize((int) localOf(addr)) * 4L * vertexStride;
    }

    // ------------------------------------------------------------------
    // Stats (BufferArena.java:62-85 fallback-path behaviour, plus the leak
    // counters the tests pin)
    // ------------------------------------------------------------------

    /**
     * Block 0's handle. Kept for the callers that only need to know whether
     * an arena exists at all; anything that READS or WRITES terrain must use
     * {@link #blockHandles()} with {@link #blockOf(int)}.
     */
    public long backingHandle() {
        return blockHandles.isEmpty() ? 0L : blockHandles.getLong(0);
    }

    /** Fixed capacity in bytes (== getMemoryUsed() on the non-sparse path). */
    public long memoryBytes() {
        return memoryBytes;
    }

    public int vertexStride() {
        return vertexStride;
    }

    /** Max quads the arena can hold (includes reserved quad 0). */
    public long quadLimit() {
        return quadLimit;
    }

    /** Quads in live allocations (includes reserved quad 0; excludes pending). */
    public long liveQuads() {
        return liveQuads;
    }

    /** Live allocation count (includes the reserved-quad allocation). */
    public int liveAllocationCount() {
        return liveAllocations;
    }

    /** Ranges freed but not yet released. */
    public int pendingFreeCount() {
        return pendingFreeList.size();
    }

    /** Quads freed but not yet released. */
    public long pendingQuads() {
        return pendingQuads;
    }

    /**
     * High-water extent of the allocator in quads (live + pending + free
     * holes up to the tail). 1 after full churn — the leak invariant.
     */
    public long quadExtent() {
        long total = 0;
        for (SegmentedManager m : blockSegments) {
            total += m.getSize();
        }
        return total;
    }

    /**
     * Whether a block holds nothing at all, and could in principle be handed
     * back to the driver.
     *
     * <p>No new bookkeeping: {@code SegmentedManager.getSize()} is the
     * block's high-water extent, it shrinks when the freed range is the tail
     * and coalesces with both neighbours, so zero means genuinely nothing
     * live. It is also release-time by construction, because
     * {@link #free(int)} only parks an address and
     * {@link #releasePending()} is what reaches the allocator. A block that
     * reads empty is a block the GPU has provably stopped reading.</p>
     *
     * <p>Block 0 can never read empty: the constructor permanently holds
     * reserved quad 0, whose address must stay 0 because a section record
     * uses {@code header.w == 0} as its empty tombstone.</p>
     */
    public boolean blockIsEmpty(int block) {
        return blockSegments.get(block).getSize() == 0;
    }

    /**
     * How many blocks at the TOP of the arena are completely empty.
     *
     * <p>Only the top matters, because a quad address encodes its block in
     * the high bits: releasing a middle block would re-address every live
     * allocation above it. Stops before block 0, which is never empty.</p>
     *
     * <p>Diagnostic for now. Nothing can release a block yet, so this
     * measures whether building that path would recover anything before the
     * path is built.</p>
     */
    public int emptyTopBlocks() {
        int empty = 0;
        for (int b = blockSegments.size() - 1; b > 0 && blockIsEmpty(b); b--) {
            empty++;
        }
        return empty;
    }

    /** BufferArena.java:70-72 (used MB from live quads). */
    public int getUsedMB() {
        return (int) ((liveQuads * vertexStride * 4) / (1024 * 1024));
    }

    /**
     * BufferArena.java:82-85 — NOTE: on the non-sparse path this is really
     * OCCUPANCY (expected bytes / fixed capacity), not fragmentation; the
     * name is kept for parity with the original stats surface.
     */
    public float getFragmentation() {
        long expected = liveQuads * vertexStride * 4;
        return (float) ((double) expected / memoryBytes);
    }
}
