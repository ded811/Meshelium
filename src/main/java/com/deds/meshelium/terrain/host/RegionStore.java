/*
 * Meshelium — LGPL-3.0-only.
 *
 * Derived from Nvidium by MCRcortex (LGPL-3.0) — the CPU side of
 * RegionManager, rebuilt on Meshelium's wave-3a record writers:
 *   misc/reference/nvidium/src/main/java/me/cortex/nvidium/managers/RegionManager.java
 *   (posKey :227, swap-remove + ref-id patch :151-198, metadata scan :95-126,
 *    tombstone :66-79, whole-region batching note :352-353)
 * Differences, each deliberate and documented inline: range-granular dirty
 * upload instead of whole-8KB blocks; reclaimed ids re-zeroed via fill ops
 * (Nvidium's full-block uploads made that implicit); slot stealing for the
 * transient two-meshes-one-section window vanilla's promotion lag creates.
 */
package com.deds.meshelium.terrain.host;

import com.deds.meshelium.terrain.EncodedSectionMesh;
import com.deds.meshelium.terrain.IdProvider;
import com.deds.meshelium.terrain.RegionRecord;
import com.deds.meshelium.terrain.SectionRecord;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Sections → 8×4×8 regions with compact ids ({@link IdProvider}), a CPU
 * mirror of every region's 256 × 32-byte section records plus its 16-byte
 * region record, and dirty tracking so only changed byte ranges re-upload.
 *
 * <p><b>Threading:</b> every method runs under {@link TerrainResidency}'s
 * store lock. Mutations arrive from build workers (via the free hook, under
 * vanilla's {@code copyLock} with Meshelium's lock innermost) and from the
 * render-thread pump; {@link #commitDirty} additionally touches the GPU
 * host and is render-thread-only by the pump's construction.</p>
 *
 * <p><b>lastIdx invariants</b> (RegionRecord Javadoc, carried 3a risk):
 * compacted ids stay ≤ lastIdx because slots are assigned densely
 * 0..count−1 and lastIdx is the highest occupied POSITION index (≥ count−1
 * whenever count &gt; 0); trailing slots stay zeroed because the mirror is
 * born zeroed, swap-remove zeroes the vacated tail slot, and reclaimed
 * region ids get their whole GPU block zero-filled before reuse.</p>
 */
final class RegionStore {

    /**
     * Region-id budget → GPU buffer sizes (16 B and 8192 B per id; the
     * section buffer is sized by its {@code regionId*8192} addressing —
     * the 3a Q13 fix, {@link SectionRecord#sectionBufferBytes}).
     * Wave ≤9's literal 2048 ids (= 32 KiB region records + 16 MiB
     * section records) derived from: at render distance 32 the vanilla
     * grid spans ~65×65 chunks × 24 sections ⇒ ≤ ~10×7×10 = 700
     * concurrently-touched regions, and vanilla's slot-eviction retention
     * (recon Q4.3) cannot exceed the grid, so 2048 was ~3× headroom.
     * <b>Wave 10 (pin source reworked in wave 13):</b> the budget now
     * comes from {@code MesheliumScaling.current()} — still exactly 2048
     * while the OPTION at world standup is ≤ 32 (wave 13 pins from the
     * option, not the config ceiling), scaled by the
     * same grid derivation above it (formula on MesheliumScaling). The
     * scaling snapshot is pinned at world standup, strictly before this
     * store's first capacity check, and this store is recreated at every
     * dispatcher dispose — so one world sees one consistent budget.
     * Overflow still drops the section with a counter — and since wave 8
     * any drop trips the coverage guard (vanilla draws everything for the
     * rest of the world; no holes — and since wave 10 the clamp-back
     * monitor also restores the render distance to 32 with a notice).
     */
    static int maxRegions() {
        return com.deds.meshelium.MesheliumScaling.current().maxRegions();
    }

    private final IdProvider ids = new IdProvider();
    private final Long2ObjectOpenHashMap<Region> byKey = new Long2ObjectOpenHashMap<>();
    private final LinkedHashSet<Region> dirtyRegions = new LinkedHashSet<>();
    /** Freed region ids whose GPU block/record still needs tombstoning. */
    private final IntOpenHashSet tombstonePending = new IntOpenHashSet();

    /** Scratch for the 16-byte region record rebuild. */
    private final ByteBuffer recordScratch =
            ByteBuffer.allocate(RegionRecord.META_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    private final boolean[] occupancyScratch = new boolean[256];

    static final class Region {
        final int id;
        final long key;
        final int rx, ry, rz;
        int count;
        /** position key (RegionManager.java:227) → compacted slot, -1 empty. */
        final short[] pos2id = new short[256];
        /** compacted slot → position key, -1 empty. */
        final short[] id2pos = new short[256];
        /** position key → owning resident entry (slot-steal bookkeeping). */
        final Object[] ownerByPos = new Object[256];
        /** 256 × 32-byte section-record mirror, always little-endian. */
        final ByteBuffer mirror =
                ByteBuffer.allocate(SectionRecord.BYTES_PER_REGION).order(ByteOrder.LITTLE_ENDIAN);
        /**
         * Wave-11: 256-bit retained-section mask, indexed by POSITION key
         * (the same bit layout as the drawer's per-region visibility
         * masks: word {@code posKey >>> 5}, bit {@code posKey & 31}). A
         * set bit means the section at that position is a RETAINED copy —
         * vanilla released its mesh, Meshelium kept the arena copy — so the
         * BFS-mask visibility path must treat it as visible (vanilla's
         * {@code visibleSections} can never list it). Maintained by
         * {@code TerrainResidency} through {@link #markRetained}/
         * {@link #clearRetained}; {@link #remove} and {@link #addOrReplace}
         * clear the written position's bit unconditionally (a freshly
         * uploaded LIVE section is not retained; a removed slot cannot
         * stay marked).
         */
        final int[] retainedMask = new int[8];
        int dirtyMinSlot = Integer.MAX_VALUE;
        int dirtyMaxSlot = -1;

        Region(int id, long key, int rx, int ry, int rz) {
            this.id = id;
            this.key = key;
            this.rx = rx;
            this.ry = ry;
            this.rz = rz;
            java.util.Arrays.fill(pos2id, (short) -1);
            java.util.Arrays.fill(id2pos, (short) -1);
        }

        void markSlotDirty(int slot) {
            dirtyMinSlot = Math.min(dirtyMinSlot, slot);
            dirtyMaxSlot = Math.max(dirtyMaxSlot, slot);
        }
    }

    /** What {@link #addOrReplace} hands back. */
    record Assignment(Region region, int posKey, int slot, Object previousOwner) {}

    static long regionKey(int sx, int sy, int sz) {
        // Arithmetic shifts floor-divide negative section coords correctly
        // (RegionManager.java:217 uses the same >>3,>>2,>>3).
        long rx = sx >> 3, ry = sy >> 2, rz = sz >> 3;
        return ((rx & 0x1FFFFF) << 42) | ((ry & 0x1FFFFF) << 21) | (rz & 0x1FFFFF);
    }

    static int posKey(int sx, int sy, int sz) {
        // RegionManager.java:227: (y&3)<<6 | (z&7)<<3 | (x&7).
        return ((sy & 3) << 6) | ((sz & 7) << 3) | (sx & 7);
    }

    /**
     * Pre-flight for {@link #addOrReplace}: would this section's region fit
     * the id budget? The provide/release round-trip is side-effect-free
     * (release re-adds the id to the free set / tail-compacts it away).
     */
    boolean hasCapacityFor(int sx, int sy, int sz) {
        if (byKey.containsKey(regionKey(sx, sy, sz))) {
            return true;
        }
        int id = ids.provide();
        ids.release(id);
        return id < maxRegions();
    }

    /**
     * Assign a section's freshly uploaded mesh to its region slot and write
     * the 32-byte record into the CPU mirror. If the position already has a
     * slot (the transient window where vanilla's promotion of the OLD mesh
     * lags Meshelium's upload of the NEW one), the slot is reused in place —
     * last writer wins, and the previous owner is returned so the caller
     * can mark it slotless (its later free then skips region removal).
     *
     * @return the assignment, or {@code null} when the region-id budget is
     *         exhausted (caller drops the section and counts it)
     */
    Assignment addOrReplace(int sx, int sy, int sz, EncodedSectionMesh mesh,
            int terrainAddress, Object owner) {
        long key = regionKey(sx, sy, sz);
        Region region = byKey.get(key);
        if (region == null) {
            int id = ids.provide();
            if (id >= maxRegions()) {
                ids.release(id);
                return null;
            }
            region = new Region(id, key, sx >> 3, sy >> 2, sz >> 3);
            byKey.put(key, region);
            // A reclaimed id may still be tombstone-pending: keep the
            // pending zero-fill (it precedes this region's record copies in
            // the same command buffer, fills → barrier → copies), which is
            // exactly what re-establishes the trailing-slots-zeroed
            // invariant on the reused GPU block.
        }
        int posKey = posKey(sx, sy, sz);
        int slot = region.pos2id[posKey];
        Object previousOwner = null;
        if (slot < 0) {
            slot = region.count++;
            region.pos2id[posKey] = (short) slot;
            region.id2pos[slot] = (short) posKey;
        } else {
            previousOwner = region.ownerByPos[posKey];
        }
        region.ownerByPos[posKey] = owner;
        // Wave-11: a fresh LIVE upload at this position supersedes any
        // retained copy; the caller frees the previous owner's arena range
        // — the mask bit clears here so the two can never disagree.
        region.retainedMask[posKey >>> 5] &= ~(1 << (posKey & 31));

        ByteBuffer mirror = region.mirror.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        mirror.position(slot * SectionRecord.SECTION_SIZE);
        SectionRecord.write(mirror, sx, sy, sz, mesh, false, slot, terrainAddress);

        region.markSlotDirty(slot);
        dirtyRegions.add(region);
        return new Assignment(region, posKey, slot, previousOwner);
    }

    /**
     * Remove a section (swap-remove compaction, RegionManager.java:151-198):
     * the tail slot's 32-byte record moves into the hole with its
     * self-reference bits 18-25 rewritten, the vacated tail slot is zeroed,
     * and an emptied region releases its id and queues GPU tombstoning.
     */
    void remove(long regionKey, int posKey, Object owner) {
        Region region = byKey.get(regionKey);
        if (region == null || region.ownerByPos[posKey] != owner) {
            return; // slot was stolen by a newer mesh, or region already gone
        }
        int slot = region.pos2id[posKey];
        if (slot < 0) {
            return;
        }
        int last = region.count - 1;
        ByteBuffer mirror = region.mirror;
        if (slot != last) {
            // Move the tail record into the hole…
            for (int i = 0; i < SectionRecord.SECTION_SIZE; i += 4) {
                mirror.putInt(slot * SectionRecord.SECTION_SIZE + i,
                        mirror.getInt(last * SectionRecord.SECTION_SIZE + i));
            }
            // …and rewrite its compacted self-reference (header.y bits
            // 18-25 — RegionManager.java:191-195's patch, done in-mirror).
            int headerYOffset = slot * SectionRecord.SECTION_SIZE + 4;
            int py = mirror.getInt(headerYOffset);
            py = (py & ~(0xFF << 18)) | (slot << 18);
            mirror.putInt(headerYOffset, py);

            int movedPos = region.id2pos[last];
            region.pos2id[movedPos] = (short) slot;
            region.id2pos[slot] = (short) movedPos;
        }
        // Zero the vacated tail slot — the trailing-slots-zeroed invariant.
        for (int i = 0; i < SectionRecord.SECTION_SIZE; i += 4) {
            mirror.putInt(last * SectionRecord.SECTION_SIZE + i, 0);
        }
        region.pos2id[posKey] = -1;
        region.id2pos[last] = -1;
        region.ownerByPos[posKey] = null;
        region.retainedMask[posKey >>> 5] &= ~(1 << (posKey & 31)); // wave-11
        region.count--;
        region.markSlotDirty(slot);
        region.markSlotDirty(last);

        if (region.count == 0) {
            byKey.remove(regionKey);
            dirtyRegions.remove(region);
            ids.release(region.id);
            tombstonePending.add(region.id);
        } else {
            dirtyRegions.add(region);
        }
    }

    /**
     * Flush GPU-visible state: tombstone fills first (they always succeed
     * and are recorded before the copies, separated by a barrier in the
     * host's command buffer), then per dirty region the changed slot range
     * plus the rebuilt 16-byte record. Regions whose staging didn't fit
     * stay dirty and retry next pump.
     */
    void commitDirty(TerrainGpuHost gpu) {
        if (!tombstonePending.isEmpty()) {
            var it = tombstonePending.iterator();
            while (it.hasNext()) {
                int id = it.nextInt();
                gpu.fillSectionBlockZero(
                        (long) id * SectionRecord.BYTES_PER_REGION, SectionRecord.BYTES_PER_REGION);
                gpu.fillRegionTombstone((long) id * RegionRecord.META_SIZE);
            }
            tombstonePending.clear();
        }

        if (dirtyRegions.isEmpty()) {
            return;
        }
        Iterator<Region> it = dirtyRegions.iterator();
        List<Region> requeue = new ArrayList<>(0);
        while (it.hasNext()) {
            Region region = it.next();
            it.remove();
            int min = region.dirtyMinSlot;
            int max = region.dirtyMaxSlot;
            if (max < 0) {
                continue;
            }
            ByteBuffer slice = region.mirror.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            slice.position(min * SectionRecord.SECTION_SIZE)
                    .limit((max + 1) * SectionRecord.SECTION_SIZE);
            long sectionOffset = (long) region.id * SectionRecord.BYTES_PER_REGION
                    + (long) min * SectionRecord.SECTION_SIZE;
            if (!gpu.stageSectionRecords(slice, sectionOffset)) {
                requeue.add(region); // staging full — keep whole range dirty
                continue;
            }

            recordScratch.clear();
            for (int p = 0; p < 256; p++) {
                occupancyScratch[p] = region.pos2id[p] >= 0;
            }
            RegionRecord.fromOccupancy(recordScratch, occupancyScratch,
                    region.rx, region.ry, region.rz, 0);
            recordScratch.flip();
            if (!gpu.stageRegionRecord(recordScratch, (long) region.id * RegionRecord.META_SIZE)) {
                requeue.add(region); // slots restage next pump too — harmless dup
                continue;
            }
            region.dirtyMinSlot = Integer.MAX_VALUE;
            region.dirtyMaxSlot = -1;
        }
        dirtyRegions.addAll(requeue);
    }

    /**
     * Wave-5 additive: flatten every live region for the drawer's
     * per-region dispatch — {@code [id, rx, ry, rz, compactedCount,
     * occMinPacked, occMaxPacked]} per region
     * ({@code TerrainResidency.DrawSnapshot.REGION_STRIDE} ints).
     * {@code count} is the number of DENSE compacted slots (0..count−1) —
     * the CPU-side truth the per-region task dispatch is sized from
     * (tighter than Nvidium's GPU-side {@code lastIdx+1}: the GPU record
     * only knows the highest occupied POSITION index, but slots are
     * assigned densely so ceil(count/N) workgroups cover every live slot,
     * and trailing slots stay zeroed for the task shader's emptiness
     * check either way). Iteration order is the map's — stable between
     * mutations, which is all the epoch-cached snapshot needs.
     *
     * <p><b>Wave-6 additive:</b> {@code occMin/occMax} are the region's
     * occupancy AABB in section-local units ({@code x | y<<8 | z<<16},
     * x,z 0..7, y 0..3) — the min/max occupied POSITIONS, exactly what
     * Nvidium's region record carries for its region-raster box
     * (RegionManager.java:95-126's metadata scan, done here CPU-side so
     * the occlusion raster never needs the GPU region record). The box is
     * a superset of every resident section in the region by construction;
     * scanned per snapshot rebuild (epoch-cached), not per frame.</p>
     */
    int[] snapshotRegions() {
        int[] out = new int[byKey.size() * 7];
        int o = 0;
        for (Region r : byKey.values()) {
            int minX = 8, minY = 4, minZ = 8;
            int maxX = -1, maxY = -1, maxZ = -1;
            for (int p = 0; p < 256; p++) {
                if (r.pos2id[p] < 0) {
                    continue;
                }
                int x = p & 7, y = (p >> 6) & 3, z = (p >> 3) & 7;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
            if (maxX < 0) { // count==0 regions never linger, but stay safe
                minX = minY = minZ = 0;
                maxX = maxY = maxZ = 0;
            }
            out[o] = r.id;
            out[o + 1] = r.rx;
            out[o + 2] = r.ry;
            out[o + 3] = r.rz;
            out[o + 4] = r.count;
            out[o + 5] = minX | (minY << 8) | (minZ << 16);
            out[o + 6] = maxX | (maxY << 8) | (maxZ << 16);
            o += 7;
        }
        return out;
    }

    /**
     * Wave-7: the GLOBAL section index ({@code regionId*256 + compactedSlot}
     * — the stamp-buffer/section-record index) of the section at
     * {@code posKey}, or −1 when the region is gone, the slot is empty, or
     * {@code owner} no longer owns it (slot stolen by a newer mesh). Reads
     * only; called per resident during the epoch-cached snapshot rebuild.
     */
    int globalSectionIndex(long regionKey, int posKey, Object owner) {
        Region region = byKey.get(regionKey);
        if (region == null || region.ownerByPos[posKey] != owner) {
            return -1;
        }
        int slot = region.pos2id[posKey];
        return slot < 0 ? -1 : (region.id << 8) | slot;
    }

    /**
     * Wave-11: mark the section at {@code posKey} RETAINED (its vanilla
     * mesh was released; Meshelium keeps the copy). Owner-checked so a race
     * with a slot steal can never mark a LIVE section — if {@code owner}
     * no longer owns the slot the call is a no-op (the caller then frees
     * the copy instead of retaining, the supersede path).
     *
     * @return true when the bit was set (the owner still holds the slot)
     */
    boolean markRetained(long regionKey, int posKey, Object owner) {
        Region region = byKey.get(regionKey);
        if (region == null || region.ownerByPos[posKey] != owner || region.pos2id[posKey] < 0) {
            return false;
        }
        region.retainedMask[posKey >>> 5] |= 1 << (posKey & 31);
        return true;
    }

    /**
     * Wave-11 snapshot twin of {@link #snapshotRegions}: 8 mask ints per
     * live region, SAME iteration order (both calls happen back to back
     * under {@code TerrainResidency}'s lock with no mutation between, and
     * fastutil map iteration order is stable while unmutated) — so index
     * {@code r} here is region {@code r} of the region snapshot, which is
     * exactly how the drawer's per-frame mask build consumes it.
     */
    int[] snapshotRetainedMasks() {
        int[] out = new int[byKey.size() * 8];
        int o = 0;
        for (Region r : byKey.values()) {
            System.arraycopy(r.retainedMask, 0, out, o, 8);
            o += 8;
        }
        return out;
    }

    int regionCount() {
        return byKey.size();
    }

    int dirtyRegionCount() {
        return dirtyRegions.size() + tombstonePending.size();
    }
}
