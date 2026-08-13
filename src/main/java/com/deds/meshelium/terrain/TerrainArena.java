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
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

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

    private final SegmentedManager segments = new SegmentedManager();
    // Wave-14: no longer final — grow() swaps the backing and raises the
    // capacity; addresses/offsets are untouched (the new backing carries a
    // GPU copy of the old bytes at identical offsets, so every live quad
    // address stays valid across a growth).
    private long backingHandle;
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
        if (memoryBytes <= 0 || vertexStride <= 0) {
            throw new IllegalArgumentException("memoryBytes and vertexStride must be positive");
        }
        this.memoryBytes = memoryBytes;
        this.vertexStride = vertexStride;
        this.backingHandle = backing.allocate(memoryBytes);
        // BufferArena.java:28 — fallback path caps the allocator at the
        // fixed buffer's quad capacity.
        this.quadLimit = memoryBytes / (4L * vertexStride);
        this.segments.setLimit(this.quadLimit);
        // BufferArena.java:30-31 — reserve quad index 0.
        int reserved = allocQuads(1);
        if (reserved != 0) {
            throw new IllegalStateException("reserved quad 0 landed at " + reserved);
        }
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
        int addr = (int) segments.alloc(quadCount);
        if (addr == ALLOC_FAILED) {
            return addr; // fix 2: totalQuads NOT inflated on failure
        }
        liveQuads += quadCount;
        liveAllocations++;
        return addr;
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
        long size = segments.getSize(addr); // throws if addr is not live
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
            segments.free(pendingFreeList.getInt(i));
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
    public void grow(long newMemoryBytes, long newBackingHandle) {
        if (newMemoryBytes <= this.memoryBytes) {
            throw new IllegalArgumentException("arena grow " + this.memoryBytes + " -> "
                    + newMemoryBytes + " bytes is not a growth");
        }
        this.memoryBytes = newMemoryBytes;
        this.backingHandle = newBackingHandle;
        this.quadLimit = newMemoryBytes / (4L * vertexStride);
        this.segments.setLimit(this.quadLimit);
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
        return segments.getSize(addr) == quadCount;
    }

    /** Byte offset of a quad address (BufferArena.java:41). */
    public long byteOffset(int addr) {
        return Integer.toUnsignedLong(addr) * 4L * vertexStride;
    }

    /** Byte size of the live allocation at {@code addr} (BufferArena.java:55). */
    public long byteSize(int addr) {
        return segments.getSize(addr) * 4L * vertexStride;
    }

    // ------------------------------------------------------------------
    // Stats (BufferArena.java:62-85 fallback-path behaviour, plus the leak
    // counters the tests pin)
    // ------------------------------------------------------------------

    /** Opaque handle from {@link ArenaBacking#allocate(long)}. */
    public long backingHandle() {
        return backingHandle;
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
        return segments.getSize();
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
