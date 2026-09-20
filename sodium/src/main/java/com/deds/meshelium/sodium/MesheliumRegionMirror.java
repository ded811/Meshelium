/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.vk.SodiumFrameRing;
import com.deds.meshelium.vk.SodiumGpuVisibilityLayout;
import com.deds.meshelium.vk.SodiumMirrorGpu;
import com.deds.meshelium.vk.SodiumTerrainDrawer;
import com.deds.meshelium.vk.TerrainDrawer;
import com.deds.meshelium.sodium.mixin.ChunkRenderListAccessor;

import com.mojang.blaze3d.buffers.GpuBuffer;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;

/**
 * The CPU half of the GPU record mirror on the Sodium host, and the
 * per-frame O(listed regions) loop that replaces the per-section
 * enumeration (D-023, GPU-VISIBILITY-DESIGN.md sections 3-4).
 *
 * <h2>What it keeps</h2>
 * <p>Per Meshelium region id ({@code mid}, handed out by
 * {@link MesheliumSodiumRegionIds} per {@code RenderRegion} identity): the
 * region it names, a shadow of the 64-byte row the GPU holds (buffer key,
 * occupancy popcount, the packed min/max occupied slot) and the mutation
 * epoch the row was committed at. Per frame: a dirty list fed by the five
 * stage-1 hooks through {@link #onMutation}, a dead list fed by the delete
 * hook through {@link #onDelete}, and the arrays the loop fills.
 *
 * <h2>Commit (contract section 3.5)</h2>
 * <p>At the SOLID call of a rung-0 attempt, before any pass: dead mids
 * become zero-row fills (I5), then every dirty region has its two
 * 12,288-byte pass blocks copied {@code getDataPointer(0)} to a CPU
 * scratch, its occupancy and row computed from that scratch, and both
 * plus the row copied into the staging ring (the pointer is used only
 * inside this call: I2; the staging mapping is write-only, which is why
 * the scratch exists: never read a sequential-write mapping). A full ring
 * declines the frame whole and keeps the rest dirty: a stale
 * {@code baseVertex} after a defragmentation reads WRONG geometry, the
 * one failure worse than a hole (I1).
 *
 * <h2>The loop (contract section 4.1)</h2>
 * <p>Walks Sodium's {@code ChunkRenderListIterable} argument, the exact
 * set the list path draws, so VisMode 0 is parity by construction
 * (contract section 0.3 on why not {@code getLastVisibleFrame}). Per
 * listed region: identity and key cross-checks against the shadow (a
 * mismatch marks the region dirty and declines the frame: a stale key
 * means a stale record set), the 256-bit BFS mask drained from the public
 * byte iterator, and a stable counting sort by buffer key into the
 * geometry-buffer groups the draw records one indirect command each for.
 * Cost is O(listed regions) plus the byte drain, ~5k bytes a frame at rd64.
 *
 * <h2>Sleep and wake</h2>
 * <p>Hooks feed the dirty list only while the GPU draw is wanted; with the
 * lever off the list would pin every region ever touched. Waking after a
 * sleep re-marks every loaded region dirty, so the mirror never draws from
 * records it was not told about. The delete hook always runs: an id must
 * be released whatever the lever says.
 *
 * <h2>Clean room</h2>
 * <p>Sodium is PolyForm Shield. This file calls Sodium's public methods and
 * copies bytes out of a heap Sodium exposes by address; it contains no
 * Sodium code.
 */
public final class MesheliumRegionMirror {

    /** What one frame's loop hands the drawer; cleared after the CUTOUT call (I4). */
    static final class FrameOutput {
        GpuBuffer[] groupBuffers = new GpuBuffer[16];
        int[] groupKeys = new int[16];
        int[] groupStart = new int[17];
        int[] groupCounts = new int[16];
        int groupCount;
        int listedRegions;
        /** Sections in the listed masks this frame (sum of popcounts): what the GPU will be asked to consider. */
        int listedSections;
        long signature;
        boolean faceAll;
        long loopNanos;

        void ensureGroups(int n) {
            if (n > groupBuffers.length) {
                int m = Math.max(n, groupBuffers.length * 2);
                groupBuffers = Arrays.copyOf(groupBuffers, m);
                groupKeys = Arrays.copyOf(groupKeys, m);
                groupStart = Arrays.copyOf(groupStart, m + 1);
                groupCounts = Arrays.copyOf(groupCounts, m);
            }
        }

        /** Drop the buffer references so a deleted region's buffer is not pinned. */
        void clear() {
            Arrays.fill(groupBuffers, 0, Math.min(groupCount, groupBuffers.length), null);
            groupCount = 0;
            listedRegions = 0;
            listedSections = 0;
        }
    }

    private static final int PASS_BLOCK_BYTES = SodiumGpuVisibilityLayout.PASS_BLOCK_BYTES;
    private static final int RECORD_BYTES = SodiumGpuVisibilityLayout.RECORD_BYTES;
    private static final int ROW_BYTES = SodiumGpuVisibilityLayout.ROW_BYTES;
    private static final int LIST_ENTRY_BYTES = SodiumGpuVisibilityLayout.LIST_ENTRY_BYTES;
    private static final int INDIRECT_BYTES = SodiumGpuVisibilityLayout.INDIRECT_BYTES;

    /** Byte offset of vertexCount[0] inside a record (contract section 1.1). */
    private static final int RECORD_COUNTS = 20;

    /** Runs per record: the seven facings. */
    private static final int RECORD_RUNS = 7;

    /** The live mirror the hooks route to; a region always belongs to the live manager. */
    private static volatile MesheliumRegionMirror current;

    private static volatile long mutationsTotal;

    private static volatile long deletesTotal;

    private final RenderSectionManager manager;
    private final MesheliumSodiumRegionIds ids;
    private final MesheliumSodiumBufferKeys keys = new MesheliumSodiumBufferKeys();
    private final ArrayList<RenderRegion> dirty = new ArrayList<>();
    private int[] dead = new int[256];
    private int deadCount;

    private RenderRegion[] regionOf;
    private GpuBuffer[] bufferOf;
    private int[] shadowKey;
    private int[] shadowPop;
    private int[] shadowMin;
    private int[] shadowMax;
    private boolean[] shadowLive;
    private int[] committedEpoch;

    private boolean tracking;
    private long serial;
    private long deadRowsTotal;
    private long midMissingTotal;
    private long keyMismatchTotal;

    // per-frame arrays, grow-only
    private RenderRegion[] listRegion = new RenderRegion[1024];
    private int[] listMid = new int[1024];
    private int[] listGroup = new int[1024];
    private int[] listMask = new int[1024 * 8];

    /** Sodium's geometry bitmap is four longs; this is that shape, named. */
    private static final int MAP_WORDS = 4;

    /** {@code verify} mode's second opinion: one region's drained bits. */
    private final int[] scratchMask = new int[8];

    /** buildFrame calls on this mirror; {@code ab} mode alternates on its parity. */
    private long buildFrames;

    /** Decided once per buildFrame, so a frame is wholly one implementation. */
    private boolean frameUsesMap;

    /** Decided with it: {@code verify} also drains and compares. */
    private boolean frameVerifies;
    private int listCount;
    private int[] groupCursor = new int[16];
    /** Per group, the running task-workgroup total of the entries written so far (one-draw search key). */
    private int[] groupWgCursor = new int[16];

    private final int[] occScratch = new int[8];
    /** Freed and zeroed by {@link #retire()}; not final so a second delete() cannot double-free it. */
    private long scratch = MemoryUtil.nmemAllocChecked(PASS_BLOCK_BYTES);
    private final Matrix4f mvp = new Matrix4f();
    private final float[] planes = new float[24];

    // the audit lever
    private final boolean audit = SodiumTerrainDrawer.mirrorAuditEnabled();
    private long auditBase;
    private int auditCapacity;
    private long auditFrames;
    private long auditMismatches;
    private long gpuMismatches;
    private int auditPendingMid = -1;
    private int auditPendingPass;
    private int auditPendingEpoch;
    private final Random auditRandom = new Random(1L);

    MesheliumRegionMirror(RenderSectionManager manager) {
        this.manager = manager;
        int capacity = SodiumTerrainDrawer.initialCapacity();
        this.ids = new MesheliumSodiumRegionIds(capacity);
        allocateShadow(capacity);
        current = this;
    }

    private void allocateShadow(int capacity) {
        regionOf = new RenderRegion[capacity];
        bufferOf = new GpuBuffer[capacity];
        shadowKey = new int[capacity];
        shadowPop = new int[capacity];
        shadowMin = new int[capacity];
        shadowMax = new int[capacity];
        shadowLive = new boolean[capacity];
        committedEpoch = new int[capacity];
        if (audit) {
            auditBase = MemoryUtil.nmemCallocChecked(capacity, 2L * PASS_BLOCK_BYTES);
            auditCapacity = capacity;
        }
    }

    // ------------------------------------------------------------------
    // The hooks (render thread; inside setupTerrain, before the first render())
    // ------------------------------------------------------------------

    /** H1-H4: a region's records changed (or will, before this frame's first render()). */
    public static void onMutation(RenderRegion region) {
        mutationsTotal++;
        MesheliumRegionMirror m = current;
        if (m == null || !m.tracking) {
            return;
        }
        m.markDirty(region);
    }

    /**
     * H5, before the epoch bump and the cache null: the heap dies when the
     * target body runs. Always active, whatever the lever: an id must be
     * released, and the holder's mid must read -1 afterwards.
     */
    public static void onDelete(RenderRegion region) {
        deletesTotal++;
        if (!(region instanceof MesheliumRegionCacheHolder holder)) {
            return;
        }
        int mid = holder.meshelium$mid();
        MesheliumRegionMirror m = current;
        if (mid >= 0 && m != null) {
            m.releaseMid(mid, region);
        }
        holder.meshelium$setMid(-1);
        holder.meshelium$setDirtyQueued(false);
    }

    public static long mutationsTotal() {
        return mutationsTotal;
    }

    public static long deletesTotal() {
        return deletesTotal;
    }

    /**
     * The 256 geometry bits for one listed region, into {@link #listMask}
     * at {@code base}.
     *
     * <h2>Two implementations of one set</h2>
     * <p>The DRAIN is what shipped until 2026-09-17: allocate Sodium's
     * byte iterator and fold its bytes into a bitmap. The MAP reads the
     * bitmap Sodium already built on the way in, through
     * {@link ChunkRenderListAccessor}. They are the same 256 bits by
     * construction — {@code ChunkRenderList.add} sets the map bit and
     * appends the byte inside one branch — so this is a substitution, not
     * an approximation, and {@code meshelium.sodium.listMap=verify} exists
     * to keep proving that on a real world rather than from the bytecode
     * alone.
     *
     * <h2>Why the choice is made once per frame</h2>
     * <p>{@code ab} mode alternates by frame so the two can be timed
     * against each other in ONE session, which is the only comparison this
     * project trusts (the harness repeats to 0.1% within a session and has
     * drifted 62% between them). Alternating per REGION instead would put
     * both implementations inside every frame's loop timer and measure
     * nothing.
     *
     * <h2>Every failure falls back to the drain</h2>
     * <p>The accessor is called only when the mixin plugin verified the
     * field (an {@code @Accessor} against a renamed field leaves this
     * interface unimplemented and would throw {@code AbstractMethodError}
     * mid frame), and the map's popcount is checked against Sodium's own
     * {@code getSectionsWithGeometryCount()} every time it is read. Any
     * disagreement, any throw, any unexpected shape disarms the map for
     * the session and the drain draws the frame — a slower frame, never a
     * wrong set.
     */
    private void fillGeometryMask(ChunkRenderList list, int base) {
        if (this.frameUsesMap && fillFromMap(list, base)) {
            if (this.frameVerifies) {
                drainInto(scratchMask, 0, list);
                int diff = 0;
                for (int w = 0; w < 8; w++) {
                    if (listMask[base + w] != scratchMask[w]) {
                        diff++;
                    }
                }
                SodiumTerrainDrawer.reportListMapCompare(diff);
                if (diff != 0) {
                    // The drained set is the one that shipped, so it wins
                    // the frame; the map is out of the session.
                    System.arraycopy(scratchMask, 0, listMask, base, 8);
                    disarmMap("the map and the drain disagreed in " + diff + " of 8 words");
                }
            }
            return;
        }
        drainInto(listMask, base, list);
    }

    /**
     * Sodium's own bitmap, widened from four longs into eight ints.
     *
     * @return false when the map could not be trusted; the caller drains
     */
    private boolean fillFromMap(ChunkRenderList list, int base) {
        long[] map;
        try {
            map = ((ChunkRenderListAccessor) (Object) list).meshelium$sectionsWithGeometryMap();
        } catch (Throwable t) {
            disarmMap("reading the geometry map threw " + t);
            return false;
        }
        if (map == null || map.length != MAP_WORDS) {
            disarmMap("the geometry map is " + (map == null ? "null" : map.length + " longs")
                    + ", not " + MAP_WORDS);
            return false;
        }
        int bits = 0;
        for (int k = 0; k < MAP_WORDS; k++) {
            long word = map[k];
            bits += Long.bitCount(word);
            listMask[base + (k << 1)] = (int) word;
            listMask[base + (k << 1) + 1] = (int) (word >>> 32);
        }
        // The cross-check that makes this safe to trust frame after frame:
        // two independent counters Sodium maintains in the same branch. If
        // they ever disagree the map is not the set we think it is.
        int counted = list.getSectionsWithGeometryCount();
        if (bits != counted) {
            disarmMap("the map holds " + bits + " bits and the list reports " + counted
                    + " sections with geometry");
            return false;
        }
        return true;
    }

    /** The shipped implementation: Sodium's byte iterator, folded into a bitmap. */
    private static void drainInto(int[] target, int base, ChunkRenderList list) {
        Arrays.fill(target, base, base + 8, 0);
        ByteIterator sections = list.sectionsWithGeometryIterator(false);
        if (sections != null) { // null, not empty, when the count is 0
            while (sections.hasNext()) {
                int b = sections.nextByteAsInt() & 0xFF;
                target[base + (b >>> 5)] |= 1 << (b & 31);
            }
        }
    }

    /**
     * Stop reading Sodium's bitmap for the rest of the session.
     *
     * <p>Latched, and said once out loud, for the reason
     * {@code MesheliumSodiumHooks.disarm} gives: a session that quietly
     * fell back looks exactly like one that never needed to.
     */
    private void disarmMap(String reason) {
        this.frameUsesMap = false;
        SodiumTerrainDrawer.disarmListMap(reason);
    }

    private void markDirty(RenderRegion region) {
        if (region instanceof MesheliumRegionCacheHolder holder && !holder.meshelium$dirtyQueued()) {
            holder.meshelium$setDirtyQueued(true);
            dirty.add(region);
        }
    }

    private void releaseMid(int mid, RenderRegion region) {
        if (mid >= regionOf.length || regionOf[mid] != region) {
            return; // not ours (a region of a manager this mirror never mirrored)
        }
        regionOf[mid] = null;
        if (bufferOf[mid] != null) {
            keys.release(bufferOf[mid]);
            bufferOf[mid] = null;
        }
        shadowLive[mid] = false;
        shadowKey[mid] = 0;
        // Not released here: the id goes back into the lag from the dead
        // drain in commit(), right after its zero fill is queued, so a mid
        // can never be free before that fill is in a recorded CB (second
        // stage-2/3 review, 2026-09-07).
        if (deadCount == dead.length) {
            dead = Arrays.copyOf(dead, dead.length * 2);
        }
        dead[deadCount++] = mid;
    }

    // ------------------------------------------------------------------
    // Frame protocol (the renderer's SOLID call)
    // ------------------------------------------------------------------

    /** The GPU draw is wanted this frame: start (or resume) feeding the dirty list. */
    void ensureTracking() {
        if (tracking) {
            return;
        }
        tracking = true;
        // Everything mutated while asleep is unknown: re-mirror the world.
        Collection<RenderRegion> loaded = manager.regions.getLoadedRegions();
        for (RenderRegion region : loaded) {
            markDirty(region);
        }
    }

    /** The GPU draw is not wanted: stop feeding the dirty list (the next wake re-marks). */
    void sleep() {
        tracking = false;
    }

    boolean tracking() {
        return tracking;
    }

    /** Once per rung-0 attempt, first: the serial the release lag and the staging ring count on. */
    void beginFrame(long frameSerial) {
        this.serial = frameSerial;
        ids.beginFrame(frameSerial);
    }

    /**
     * The mirror capacity the GPU side actually has; grows the shadow
     * arrays and the id space to match (after creation and after a growth).
     */
    void syncCapacity(int capacity) {
        if (capacity <= regionOf.length) {
            // The arrays may stay larger; the id space may not (a mid past
            // the mirror's capacity would address bytes past its buffer).
            ids.setCapacity(capacity);
            return;
        }
        regionOf = Arrays.copyOf(regionOf, capacity);
        bufferOf = Arrays.copyOf(bufferOf, capacity);
        shadowKey = Arrays.copyOf(shadowKey, capacity);
        shadowPop = Arrays.copyOf(shadowPop, capacity);
        shadowMin = Arrays.copyOf(shadowMin, capacity);
        shadowMax = Arrays.copyOf(shadowMax, capacity);
        shadowLive = Arrays.copyOf(shadowLive, capacity);
        committedEpoch = Arrays.copyOf(committedEpoch, capacity);
        if (audit) {
            long grown = MemoryUtil.nmemCallocChecked(capacity, 2L * PASS_BLOCK_BYTES);
            MemoryUtil.memCopy(auditBase, grown, (long) auditCapacity * 2L * PASS_BLOCK_BYTES);
            MemoryUtil.nmemFree(auditBase);
            auditBase = grown;
            auditCapacity = capacity;
        }
        ids.setCapacity(capacity);
    }

    /**
     * The capacity the mirror must grow to before this frame's commit can
     * succeed, or 0: live ids plus those inside the release lag plus the
     * dirty regions that have no id yet, against the id space (x1.5,
     * rounded up to {@code CAPACITY_ROUND}).
     */
    int growthNeeded() {
        int fresh = 0;
        for (int i = 0, n = dirty.size(); i < n; i++) {
            if (dirty.get(i) instanceof MesheliumRegionCacheHolder holder
                    && holder.meshelium$dirtyQueued() && holder.meshelium$mid() < 0) {
                fresh++;
            }
        }
        int need = ids.occupied() + fresh;
        int capacity = ids.capacity();
        if (need <= capacity) {
            return 0;
        }
        int target = Math.max(capacity + capacity / 2, need);
        int round = SodiumGpuVisibilityLayout.CAPACITY_ROUND;
        return (target + round - 1) / round * round;
    }

    /**
     * Commit every dirty region (contract section 3.5 step 3): dead rows
     * first, then per region the two staged pass blocks and the row. A
     * full staging ring or an exhausted id space sets {@code declined[0]}
     * and leaves the rest dirty; whatever was queued is still executed by
     * the caller's {@code endCommit} (never read before the next full
     * commit, so a partial update is harmless and keeps the ring honest).
     *
     * @return regions committed
     */
    int commit(SodiumMirrorGpu gpu, boolean[] declined) {
        if (!gpu.beginCommit(serial)) {
            declined[0] = true;
            return 0;
        }
        for (int i = 0; i < deadCount; i++) {
            gpu.zeroRow(dead[i]);
            ids.release(dead[i], serial); // into the lag only now, with the fill queued
        }
        deadRowsTotal += deadCount;
        deadCount = 0;

        int committed = 0;
        int n = dirty.size();
        int i = 0;
        for (; i < n; i++) {
            RenderRegion region = dirty.get(i);
            if (!(region instanceof MesheliumRegionCacheHolder holder) || !holder.meshelium$dirtyQueued()) {
                continue; // deleted since it was queued
            }
            int mid = holder.meshelium$mid();
            if (mid >= 0 && (mid >= regionOf.length || regionOf[mid] != region)) {
                mid = -1; // an id from a mirror this region no longer belongs to
            }
            if (mid < 0) {
                mid = ids.acquire();
                if (mid < 0) {
                    declined[0] = true;
                    break;
                }
                holder.meshelium$setMid(mid);
                regionOf[mid] = region;
            }
            if (!commitRegion(gpu, region, holder, mid)) {
                declined[0] = true;
                break;
            }
            holder.meshelium$setDirtyQueued(false);
            committed++;
        }
        if (i >= n) {
            dirty.clear();
        } else {
            dirty.subList(0, i).clear();
        }
        return committed;
    }

    /** One region's two pass blocks and its row. False = the staging ring is full. */
    private boolean commitRegion(SodiumMirrorGpu gpu, RenderRegion region,
            MesheliumRegionCacheHolder holder, int mid) {
        RenderRegion.DeviceResources resources = region.getResources();
        GpuBuffer geometry = resources == null ? null : resources.getGeometryBuffer();
        boolean live = geometry != null;
        int[] occ = occScratch;
        Arrays.fill(occ, 0);
        for (int pass = 0; pass < SodiumGpuVisibilityLayout.PASS_COUNT; pass++) {
            SectionRenderDataStorage storage = region.getStorage(passOf(pass));
            long heap = storage == null ? 0L : storage.getDataPointer(0);
            long copy = audit ? auditBlock(mid, pass) : scratch;
            if (heap == 0L) {
                gpu.zeroPassBlock(mid, pass);
                if (audit) {
                    MemoryUtil.memSet(copy, 0, PASS_BLOCK_BYTES);
                }
                continue;
            }
            // Heap -> scratch (read once), occupancy from the scratch,
            // scratch -> staging (write-only memory).
            MemoryUtil.memCopy(heap, copy, PASS_BLOCK_BYTES);
            occupancy(copy, occ);
            long staged = gpu.stageBlock(PASS_BLOCK_BYTES);
            if (staged == 0L) {
                return false;
            }
            MemoryUtil.memCopy(copy, staged, PASS_BLOCK_BYTES);
            gpu.copyPassBlock(mid, pass, staged);
        }

        int pop = 0;
        int minX = 8, minY = 4, minZ = 8, maxX = -1, maxY = -1, maxZ = -1;
        for (int w = 0; w < 8; w++) {
            int word = occ[w];
            while (word != 0) {
                int bit = Integer.numberOfTrailingZeros(word);
                word &= word - 1;
                int slot = (w << 5) | bit;
                int x = (slot >>> 5) & 7;
                int y = slot & 3;
                int z = (slot >>> 2) & 7;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
                pop++;
            }
        }
        int occMin = pop == 0 ? 0 : (minX | (minY << 8) | (minZ << 16));
        int occMax = pop == 0 ? 0 : (maxX | (maxY << 8) | (maxZ << 16));
        int key = live ? keys.keyOf(geometry) : 0;

        long row = gpu.stageBlock(ROW_BYTES);
        if (row == 0L) {
            return false;
        }
        MemoryUtil.memPutInt(row + SodiumGpuVisibilityLayout.ROW_CHUNK_X, region.getChunkX());
        MemoryUtil.memPutInt(row + SodiumGpuVisibilityLayout.ROW_CHUNK_Y, region.getChunkY());
        MemoryUtil.memPutInt(row + SodiumGpuVisibilityLayout.ROW_CHUNK_Z, region.getChunkZ());
        MemoryUtil.memPutInt(row + SodiumGpuVisibilityLayout.ROW_FLAGS,
                live ? SodiumGpuVisibilityLayout.ROW_FLAG_LIVE : 0);
        for (int w = 0; w < 8; w++) {
            MemoryUtil.memPutInt(row + SodiumGpuVisibilityLayout.ROW_OCC_MASK + 4L * w, occ[w]);
        }
        MemoryUtil.memPutInt(row + SodiumGpuVisibilityLayout.ROW_BUFFER_KEY, key);
        MemoryUtil.memPutInt(row + SodiumGpuVisibilityLayout.ROW_POPCOUNT, pop);
        MemoryUtil.memPutInt(row + SodiumGpuVisibilityLayout.ROW_OCC_MIN, occMin);
        MemoryUtil.memPutInt(row + SodiumGpuVisibilityLayout.ROW_OCC_MAX, occMax);
        gpu.copyRow(mid, row);

        if (bufferOf[mid] != geometry) {
            if (bufferOf[mid] != null) {
                keys.release(bufferOf[mid]);
            }
            if (geometry != null) {
                keys.retain(geometry);
            }
            bufferOf[mid] = geometry;
        }
        shadowKey[mid] = key;
        shadowPop[mid] = pop;
        shadowMin[mid] = occMin;
        shadowMax[mid] = occMax;
        shadowLive[mid] = live;
        committedEpoch[mid] = holder.meshelium$epoch();
        return true;
    }

    /** OR the occupancy bits of one pass block into {@code occ}: slot s is set iff any of its seven counts is non-zero. */
    private static void occupancy(long block, int[] occ) {
        for (int slot = 0; slot < SodiumGpuVisibilityLayout.SLOTS_PER_REGION; slot++) {
            long counts = block + (long) slot * RECORD_BYTES + RECORD_COUNTS;
            int any = 0;
            for (int i = 0; i < RECORD_RUNS; i++) {
                any |= MemoryUtil.memGetInt(counts + 4L * i);
            }
            if (any != 0) {
                occ[slot >>> 5] |= 1 << (slot & 31);
            }
        }
    }

    private static TerrainRenderPass passOf(int pass) {
        return pass == SodiumGpuVisibilityLayout.PASS_SOLID
                ? DefaultTerrainRenderPasses.SOLID : DefaultTerrainRenderPasses.CUTOUT;
    }

    private long auditBlock(int mid, int pass) {
        return auditBase + ((long) mid * 2L + pass) * PASS_BLOCK_BYTES;
    }

    // ------------------------------------------------------------------
    // The per-frame loop
    // ------------------------------------------------------------------

    /**
     * Fill the ring slot and the group tables for this frame (contract
     * section 4.1). Returns the listed region count, or -1 on an invariant
     * breach (the frame declines; the offending region is marked dirty).
     *
     * @param frustumRegions walk {@code getLoadedRegions()} with a frustum
     *        test instead of the iterable (the graphRegions=false lever,
     *        occlusion only); the BFS mask is then all ones, never read
     */
    int buildFrame(ChunkRenderListIterable lists, CameraTransform camera, Matrix4fc modelView,
            Matrix4fc projection, boolean frustumRegions, SodiumFrameRing ring, FrameOutput out) {
        long t0 = System.nanoTime();
        // Which geometry-bitmap implementation this WHOLE frame uses, so
        // the loop timer below measures one of them and not a mixture.
        // Off unless the field was verified at class-transform time: the
        // accessor is unimplemented when the field moved.
        long parity = this.buildFrames++;
        String listMapMode = SodiumTerrainDrawer.listMapMode();
        boolean mapUsable = MesheliumSodiumHooks.geometryMapFound()
                && !SodiumTerrainDrawer.listMapDisarmed();
        this.frameVerifies = mapUsable && SodiumTerrainDrawer.LIST_MAP_VERIFY.equals(listMapMode);
        this.frameUsesMap = mapUsable && switch (listMapMode) {
            case SodiumTerrainDrawer.LIST_MAP_ON, SodiumTerrainDrawer.LIST_MAP_VERIFY -> true;
            case SodiumTerrainDrawer.LIST_MAP_AB -> (parity & 1L) == 1L;
            default -> false;
        };
        // The same rule as the list path: Sodium's own switch, and any
        // failure to read it draws every facing.
        boolean faceCull = MesheliumSodiumDrawList.FACE_CULL;
        try {
            faceCull = faceCull && SodiumClientMod.options().performance.useBlockFaceCulling;
        } catch (Throwable ignored) {
            faceCull = false;
        }
        out.faceAll = !faceCull;
        listCount = 0;
        int capacity = ring.capacity();

        if (!frustumRegions) {
            Iterator<ChunkRenderList> it = lists.iterator(false);
            while (it.hasNext()) {
                ChunkRenderList list = it.next();
                RenderRegion region = list.getRegion();
                if (region == null) {
                    continue;
                }
                RenderRegion.DeviceResources resources = region.getResources();
                if (resources == null) {
                    continue;
                }
                GpuBuffer geometry = resources.getGeometryBuffer();
                if (geometry == null) {
                    continue;
                }
                int mid = resolve(region, geometry);
                if (mid < 0) {
                    return -1;
                }
                if (listCount >= capacity) {
                    // Cannot happen (every listed region holds a mid below
                    // the capacity); treated as the invariant breach it is.
                    midMissingTotal++;
                    return -1;
                }
                int e = append(region, mid);
                fillGeometryMask(list, e * 8);
            }
        } else {
            computePlanes(modelView, projection);
            for (RenderRegion region : manager.regions.getLoadedRegions()) {
                RenderRegion.DeviceResources resources = region.getResources();
                if (resources == null) {
                    continue;
                }
                GpuBuffer geometry = resources.getGeometryBuffer();
                if (geometry == null) {
                    continue;
                }
                float lx = (float) (region.getOriginX() - camera.x);
                float ly = (float) (region.getOriginY() - camera.y);
                float lz = (float) (region.getOriginZ() - camera.z);
                if (!boxInFrustum(lx, ly, lz, lx + 128.0f, ly + 64.0f, lz + 128.0f)) {
                    continue;
                }
                int mid = resolve(region, geometry);
                if (mid < 0) {
                    return -1;
                }
                if (listCount >= capacity) {
                    midMissingTotal++;
                    return -1;
                }
                int e = append(region, mid);
                Arrays.fill(listMask, e * 8, e * 8 + 8, -1);
            }
        }

        // Stable counting sort by buffer key: groups are interned by a
        // linear scan (single digits of distinct buffers at any render
        // distance, D-018), entries keep list order inside a group.
        out.groupCount = 0;
        for (int e = 0; e < listCount; e++) {
            int mid = listMid[e];
            int key = shadowKey[mid];
            int g = -1;
            for (int k = 0; k < out.groupCount; k++) {
                if (out.groupKeys[k] == key) {
                    g = k;
                    break;
                }
            }
            if (g < 0) {
                out.ensureGroups(out.groupCount + 1);
                g = out.groupCount++;
                out.groupKeys[g] = key;
                out.groupBuffers[g] = bufferOf[mid];
                out.groupCounts[g] = 0;
            }
            out.groupCounts[g]++;
            listGroup[e] = g;
        }
        if (out.groupCount > SodiumGpuVisibilityLayout.MAX_GROUP_COMMANDS) {
            // More distinct geometry buffers than the ring's group-command
            // area holds (D-018 says single digits): decline the frame.
            midMissingTotal++;
            return -1;
        }
        if (groupCursor.length < out.groupCount) {
            groupCursor = new int[Math.max(out.groupCount, groupCursor.length * 2)];
            groupWgCursor = new int[groupCursor.length];
        }
        out.groupStart[0] = 0;
        for (int g = 0; g < out.groupCount; g++) {
            out.groupStart[g + 1] = out.groupStart[g] + out.groupCounts[g];
            groupCursor[g] = out.groupStart[g];
            groupWgCursor[g] = 0;
        }

        // The ring slot: list entries and indirect commands in sorted
        // order, raw stores into write-only mapped memory. The task
        // workgroup width is the SAME knob the pipeline compiled
        // MESHELIUM_TASK_WG_SIZE from (-Dmeshelium.tune.taskWorkgroupSections,
        // 1..128): lane k of workgroup w is rank w*width + k, so a count
        // derived from any other width would leave ranks undispatched
        // below it (holes) or idle lanes above it (waste).
        int taskWg = Math.max(1, TerrainDrawer.taskWorkgroupSections());
        long listAddress = ring.listAddress();
        long indirectAddress = ring.indirectAddress();
        long sig = 0x9E3779B97F4A7C15L;
        int listedSections = 0;
        for (int e = 0; e < listCount; e++) {
            int mid = listMid[e];
            RenderRegion region = listRegion[e];
            int i = groupCursor[listGroup[e]]++;
            long a = listAddress + (long) i * LIST_ENTRY_BYTES;
            // The region origin minus the camera in double, cast once; the
            // task/mesh stages add 16*l in float, and rung 1's walkRegion /
            // replayEntry compute their section origins the same way
            // (second stage-2/3 review, 2026-09-07), so the two paths agree
            // in vertex bits, not only in sets.
            MemoryUtil.memPutFloat(a + SodiumGpuVisibilityLayout.LIST_ORIGIN,
                    (float) (region.getOriginX() - camera.x));
            MemoryUtil.memPutFloat(a + SodiumGpuVisibilityLayout.LIST_ORIGIN + 4L,
                    (float) (region.getOriginY() - camera.y));
            MemoryUtil.memPutFloat(a + SodiumGpuVisibilityLayout.LIST_ORIGIN + 8L,
                    (float) (region.getOriginZ() - camera.z));
            int pop = shadowPop[mid];
            int wg = (pop + taskWg - 1) / taskWg; // ceil(popcount / width) task workgroups
            // The one-draw search key: this group's cumulative workgroup
            // count through this entry, in list (= slot) order. Written
            // whether or not the lever is on; the raster shaders never
            // read the fourth origin component.
            int g = listGroup[e];
            groupWgCursor[g] += wg;
            MemoryUtil.memPutInt(a + SodiumGpuVisibilityLayout.LIST_GROUP_END, groupWgCursor[g]);
            MemoryUtil.memPutInt(a + SodiumGpuVisibilityLayout.LIST_META_MID, mid);
            MemoryUtil.memPutInt(a + SodiumGpuVisibilityLayout.LIST_META_POPCOUNT, pop);
            MemoryUtil.memPutInt(a + SodiumGpuVisibilityLayout.LIST_META_OCC_MIN, shadowMin[mid]);
            MemoryUtil.memPutInt(a + SodiumGpuVisibilityLayout.LIST_META_OCC_MAX, shadowMax[mid]);
            int base = e * 8;
            for (int w = 0; w < 8; w++) {
                MemoryUtil.memPutInt(a + SodiumGpuVisibilityLayout.LIST_BFS_MASK + 4L * w, listMask[base + w]);
            }
            long ia = indirectAddress + (long) i * INDIRECT_BYTES;
            MemoryUtil.memPutInt(ia, wg);
            MemoryUtil.memPutInt(ia + 4L, 1);
            MemoryUtil.memPutInt(ia + 8L, 1);
            sig = (sig ^ mid) * 0x100000001B3L;
            listedSections += pop;
            sig = (sig ^ pop) * 0x100000001B3L;
        }
        // One command per group (SodiumTerrainDrawer.PROPERTY_ONE_DRAW):
        // every task workgroup of the group in one dispatch.
        long groupAddress = ring.indirectGroupAddress();
        for (int g = 0; g < out.groupCount; g++) {
            long ga = groupAddress + (long) g * INDIRECT_BYTES;
            MemoryUtil.memPutInt(ga, groupWgCursor[g]);
            MemoryUtil.memPutInt(ga + 4L, 1);
            MemoryUtil.memPutInt(ga + 8L, 1);
        }
        Arrays.fill(listRegion, 0, listCount, null);
        out.listedRegions = listCount;
        out.listedSections = listedSections;
        out.signature = sig;
        out.loopNanos = System.nanoTime() - t0;
        // The interleaved pair: alternate frames, one accumulator each,
        // one session. The difference between the two means is what the
        // drain costs, measured against itself rather than against an
        // archived number.
        SodiumTerrainDrawer.reportListMapFrame(this.frameUsesMap, out.loopNanos, listCount);
        return listCount;
    }

    /**
     * The two mixin-free cross-checks per listed region (contract section
     * 3.4): the holder's mid maps back to this region, and the live buffer
     * is the one the row was committed with (same key, same epoch). A
     * failure marks the region dirty and declines the frame.
     */
    private int resolve(RenderRegion region, GpuBuffer geometry) {
        if (!(region instanceof MesheliumRegionCacheHolder holder)) {
            midMissingTotal++;
            return -1;
        }
        int mid = holder.meshelium$mid();
        if (mid < 0 || mid >= regionOf.length || regionOf[mid] != region) {
            midMissingTotal++;
            markDirty(region);
            return -1;
        }
        if (!shadowLive[mid] || keys.peekKey(geometry) != shadowKey[mid]
                || holder.meshelium$epoch() != committedEpoch[mid]) {
            keyMismatchTotal++;
            markDirty(region);
            return -1;
        }
        return mid;
    }

    private int append(RenderRegion region, int mid) {
        int e = listCount;
        if (e == listMid.length) {
            int n = listMid.length * 2;
            listRegion = Arrays.copyOf(listRegion, n);
            listMid = Arrays.copyOf(listMid, n);
            listGroup = Arrays.copyOf(listGroup, n);
            listMask = Arrays.copyOf(listMask, n * 8);
        }
        listRegion[e] = region;
        listMid[e] = mid;
        listCount = e + 1;
        return e;
    }

    /**
     * The six planes of projection * modelView, JOML's FrustumIntersection
     * formulas as {@code TerrainDrawer.uploadScene} writes them; camera-
     * relative because the model-view carries no translation.
     */
    private void computePlanes(Matrix4fc modelView, Matrix4fc projection) {
        Matrix4f m = mvp.set(projection).mul(modelView);
        float[] p = planes;
        plane(p, 0, m.m03() + m.m00(), m.m13() + m.m10(), m.m23() + m.m20(), m.m33() + m.m30());
        plane(p, 4, m.m03() - m.m00(), m.m13() - m.m10(), m.m23() - m.m20(), m.m33() - m.m30());
        plane(p, 8, m.m03() + m.m01(), m.m13() + m.m11(), m.m23() + m.m21(), m.m33() + m.m31());
        plane(p, 12, m.m03() - m.m01(), m.m13() - m.m11(), m.m23() - m.m21(), m.m33() - m.m31());
        plane(p, 16, m.m03() + m.m02(), m.m13() + m.m12(), m.m23() + m.m22(), m.m33() + m.m32());
        plane(p, 20, m.m03() - m.m02(), m.m13() - m.m12(), m.m23() - m.m22(), m.m33() - m.m32());
    }

    private static void plane(float[] p, int o, float a, float b, float c, float d) {
        p[o] = a;
        p[o + 1] = b;
        p[o + 2] = c;
        p[o + 3] = d;
    }

    /** The p-vertex test against the unnormalised planes (scale-invariant). */
    private boolean boxInFrustum(float lx, float ly, float lz, float hx, float hy, float hz) {
        float[] p = planes;
        for (int o = 0; o < 24; o += 4) {
            float a = p[o];
            float b = p[o + 1];
            float c = p[o + 2];
            float d = p[o + 3];
            float px = a > 0.0f ? hx : lx;
            float py = b > 0.0f ? hy : ly;
            float pz = c > 0.0f ? hz : lz;
            if (a * px + b * py + c * pz + d < 0.0f) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // The audit (diagnostic lever)
    // ------------------------------------------------------------------

    /**
     * Under {@code -Dmeshelium.sodium.mirrorAudit=true}, after the commit
     * of an owned frame: re-read every live, clean region's two blocks from
     * Sodium's heap and count bytes that differ from the copy the last
     * commit staged; fold in one lagged GPU readback and request the next
     * (a random live mid). Reports through the drawer.
     *
     * @return mismatching bytes found this frame (CPU compare)
     */
    long audit(SodiumMirrorGpu gpu, long statsFrames) {
        if (!audit) {
            return 0L;
        }
        auditFrames++;
        long mismatches = 0L;
        int live = 0;
        for (int mid = 0; mid < regionOf.length; mid++) {
            RenderRegion region = regionOf[mid];
            if (region == null || !(region instanceof MesheliumRegionCacheHolder holder)
                    || holder.meshelium$dirtyQueued()) {
                continue;
            }
            live++;
            for (int pass = 0; pass < SodiumGpuVisibilityLayout.PASS_COUNT; pass++) {
                SectionRenderDataStorage storage = region.getStorage(passOf(pass));
                long heap = storage == null ? 0L : storage.getDataPointer(0);
                mismatches += differingBytes(heap, auditBlock(mid, pass));
            }
        }
        auditMismatches += mismatches;

        long back = gpu.takeBlockReadback(statsFrames);
        if (back != 0L && auditPendingMid >= 0) {
            int mid = auditPendingMid;
            if (regionOf[mid] != null && committedEpoch[mid] == auditPendingEpoch) {
                gpuMismatches += differingBytes(back, auditBlock(mid, auditPendingPass));
            }
            auditPendingMid = -1;
        }
        if (auditPendingMid < 0 && live > 0 && gpu.blockReadbackMid() < 0) {
            int tries = 0;
            int mid;
            do {
                mid = auditRandom.nextInt(regionOf.length);
            } while (regionOf[mid] == null && ++tries < 64);
            if (regionOf[mid] != null) {
                auditPendingMid = mid;
                auditPendingPass = auditRandom.nextInt(SodiumGpuVisibilityLayout.PASS_COUNT);
                auditPendingEpoch = committedEpoch[mid];
                gpu.requestBlockReadback(mid, auditPendingPass);
            }
        }
        SodiumTerrainDrawer.reportAudit(auditFrames, auditMismatches, gpuMismatches);
        return mismatches;
    }

    /** Bytes of {@code a} (or zeros when {@code a == 0}) that differ from {@code b}, over one pass block. */
    private static long differingBytes(long a, long b) {
        long diff = 0L;
        for (long o = 0; o < PASS_BLOCK_BYTES; o += 8) {
            long x = a == 0L ? 0L : MemoryUtil.memGetLong(a + o);
            long y = MemoryUtil.memGetLong(b + o);
            if (x != y) {
                long v = x ^ y;
                for (int k = 0; k < 8; k++) {
                    if (((v >>> (8 * k)) & 0xFFL) != 0L) {
                        diff++;
                    }
                }
            }
        }
        return diff;
    }

    // ------------------------------------------------------------------
    // Counters and teardown
    // ------------------------------------------------------------------

    int midsLive() {
        return ids.live();
    }

    int midsCapacity() {
        return ids.capacity();
    }

    long midsReleased() {
        return ids.releasedTotal();
    }

    int bufferKeys() {
        return keys.count();
    }

    long deadRows() {
        return deadRowsTotal;
    }

    long midMissing() {
        return midMissingTotal;
    }

    long keyMismatch() {
        return keyMismatchTotal;
    }

    int dirtyCount() {
        return dirty.size();
    }

    /** Indices into {@link #censusForTest()}. */
    public static final int CENSUS_LOADED = 0, CENSUS_LISTABLE = 1, CENSUS_UNMIRRORED = 2,
            CENSUS_ORPHANED = 3, CENSUS_MIRRORED = 4;

    /**
     * TEST/diagnostic, render thread: the mirror held against Sodium's own
     * region map, as five counts. {@code CENSUS_LOADED}: regions Sodium
     * has. {@code CENSUS_LISTABLE}: of those, the ones the loop could put
     * in a frame (device resources with a geometry buffer).
     * {@code CENSUS_UNMIRRORED}: listable regions whose id does not map
     * back to them (each would decline a frame as {@code midMissing}: a
     * mirroring gap). {@code CENSUS_ORPHANED}: ids naming a region Sodium
     * no longer holds (a leaked id: the delete hook missed it).
     * {@code CENSUS_MIRRORED}: ids naming any region, which the allocator's
     * live count must equal once the dead list has drained. Null when no
     * mirror is live.
     *
     * <p>Why this and not "midsLive returns to its old value" (the first
     * 97_03): {@link #ensureTracking} mirrors every LOADED region, built or
     * not, while the hooks mirror only the regions Sodium uploads into,
     * and at a fixed camera Sodium builds only the sections its graph walk
     * reaches (the fog distance, and no way down through the ground).
     * Reload the same chunks and the live count lands wherever the walk
     * stops: 9 against 24 at the rd-5 pose. The claims that hold are the
     * two zeros.
     */
    public static int[] censusForTest() {
        MesheliumRegionMirror m = current;
        if (m == null) {
            return null;
        }
        Set<RenderRegion> loaded = Collections.newSetFromMap(new IdentityHashMap<>());
        int listable = 0;
        int unmirrored = 0;
        for (RenderRegion region : m.manager.regions.getLoadedRegions()) {
            loaded.add(region);
            RenderRegion.DeviceResources resources = region.getResources();
            if (resources == null || resources.getGeometryBuffer() == null) {
                continue;
            }
            listable++;
            int mid = region instanceof MesheliumRegionCacheHolder holder
                    ? holder.meshelium$mid() : -1;
            if (mid < 0 || mid >= m.regionOf.length || m.regionOf[mid] != region) {
                unmirrored++;
            }
        }
        int orphaned = 0;
        int mirrored = 0;
        for (RenderRegion region : m.regionOf) {
            if (region == null) {
                continue;
            }
            mirrored++;
            if (!loaded.contains(region)) {
                orphaned++;
            }
        }
        return new int[] {loaded.size(), listable, unmirrored, orphaned, mirrored};
    }

    /** The manager this mirror follows. */
    RenderSectionManager manager() {
        return manager;
    }

    /**
     * Called from the renderer's {@code delete()}: stop routing hooks here
     * and free the scratch.
     *
     * <p>Logs what it freed (2026-09-14), because the constructor's
     * {@code scratch} block is native memory allocated UNCONDITIONALLY -
     * on every {@code RenderSectionManager} construction with Sodium
     * present, OpenGL included, before the audit lever is even read - and
     * only this frees it. A Sodium session on any backend that ends
     * without this line has leaked {@code PASS_BLOCK_BYTES} per world, so
     * the NeoForge OpenGL proof run greps for it at teardown.
     */
    void retire() {
        if (current == this) {
            current = null;
        }
        tracking = false;
        dirty.clear();
        deadCount = 0;
        keys.clear();
        Arrays.fill(regionOf, null);
        Arrays.fill(bufferOf, null);
        long freed = scratch != 0L ? PASS_BLOCK_BYTES : 0L;
        MemoryUtil.nmemFree(scratch); // nmemFree(0) is free(NULL): a second retire is a no-op
        scratch = 0L;
        if (auditBase != 0L) {
            freed += (long) auditCapacity * 2L * PASS_BLOCK_BYTES;
            MemoryUtil.nmemFree(auditBase);
            auditBase = 0L;
        }
        if (freed > 0L) {
            MesheliumLog.LOGGER.info(
                    "Meshelium region mirror retired with the chunk renderer: {} B of native "
                            + "scratch freed (audit={}, {} mutations / {} deletes seen this session)",
                    freed, audit, mutationsTotal, deletesTotal);
        }
    }
}
