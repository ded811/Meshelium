/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium.mixin;

import com.deds.meshelium.sodium.MesheliumRegionCacheHolder;
import com.deds.meshelium.sodium.MesheliumRegionMirror;
import com.deds.meshelium.sodium.MesheliumRegionRunCache;
import com.deds.meshelium.sodium.MesheliumSodiumHooks;
import com.deds.meshelium.vk.SodiumTerrainDrawer;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Four of the five record-mutation hooks (D-021), and the region's cache
 * field.
 *
 * <h2>Why these four methods</h2>
 * <p>A jar-wide javap of Sodium 0.9.2-beta.1 finds exactly five callers
 * of the writers of a section's geometry record (base vertex, facing
 * list, slice mask, seven vertex counts): the private per-region
 * {@code uploadResults} (hooked in {@code RenderRegionManagerMixin}) and
 * these four on {@code RenderRegion}. {@code removeSection} clears a
 * departing section's record; {@code onGeometryBufferChange} re-derives
 * every base vertex after the arena replaced its buffer;
 * {@code onGeometrySegmentChange} rewrites ONE base vertex after an
 * incremental defragmentation moved a segment inside the same buffer —
 * the only signal for that move, and the one a buffer-identity check
 * could never see; {@code delete} frees the record heap.
 *
 * <p>NOT hooked, deliberately: {@code clearAllCachedBatches} and its
 * relatives. Sodium calls them from {@code ChunkRenderList.prepareForRender}
 * whenever a region's visible set or the camera's section changed, i.e.
 * for every visible region on every turning frame, which would flood the
 * cache with the O(sections) work it exists to remove.
 *
 * <h2>Why HEAD</h2>
 * <p>The epoch is only ever consumed at {@code render()}, and every one of
 * these runs inside {@code setupTerrain}, before the frame's first opaque
 * pass. Bumping before the write or after it is therefore the same to the
 * reader; HEAD is chosen because it has no early-return trap (Mixin's
 * TAIL is the LAST return only) and because {@code delete} must drop the
 * cache before the heap it was built from is freed.
 *
 * <h2>Why {@code require = 0}</h2>
 * <p>A Sodium update that renames one of these must cost a slower frame,
 * not a crash. With the injection allowed to be absent, the plugin's
 * {@code preApply} check is what decides whether the cache may arm; see
 * {@link MesheliumSodiumHooks}. Each hook also counts, so the suite can
 * prove it has callers rather than assume it (memory: verify the
 * guarantee has callers).
 *
 * <h2>Stage 2 (D-023)</h2>
 * <p>The same four hooks now also feed the GPU record mirror through
 * {@code MesheliumRegionMirror.onMutation} (H2-H4) and {@code onDelete}
 * (H5, which must run before the heap it mirrors is freed), and the region
 * carries two more fields: Meshelium's own dense region id and the dirty
 * list's dedup flag.
 *
 * <h2>Clean room</h2>
 * <p>Sodium is PolyForm Shield. This mixin names four of Sodium's methods
 * and adds four fields; it contains no Sodium code.
 */
@Mixin(RenderRegion.class)
public abstract class RenderRegionMixin implements MesheliumRegionCacheHolder {

    @Unique
    private int meshelium$epoch;

    @Unique
    private MesheliumRegionRunCache meshelium$runCache;

    /** Stage 2 (D-023): Meshelium's dense region id in the GPU record mirror; -1 until mirrored. */
    @Unique
    private int meshelium$mid = -1;

    @Unique
    private boolean meshelium$dirtyQueued;

    @Override
    public int meshelium$mid() {
        return this.meshelium$mid;
    }

    @Override
    public void meshelium$setMid(int mid) {
        this.meshelium$mid = mid;
    }

    @Override
    public boolean meshelium$dirtyQueued() {
        return this.meshelium$dirtyQueued;
    }

    @Override
    public void meshelium$setDirtyQueued(boolean value) {
        this.meshelium$dirtyQueued = value;
    }

    @Override
    public int meshelium$epoch() {
        return this.meshelium$epoch;
    }

    @Override
    public void meshelium$bumpEpoch() {
        this.meshelium$epoch++;
    }

    @Override
    public MesheliumRegionRunCache meshelium$runCache() {
        return this.meshelium$runCache;
    }

    @Override
    public void meshelium$setRunCache(MesheliumRegionRunCache cache) {
        this.meshelium$runCache = cache;
    }

    /** H2: a section left the region and its record was cleared. */
    @Inject(method = MesheliumSodiumHooks.REMOVE_SECTION_TARGET, at = @At("HEAD"),
            require = 0, expect = 0)
    private void meshelium$onRemoveSection(RenderSection section, CallbackInfo ci) {
        this.meshelium$epoch++;
        MesheliumRegionMirror.onMutation((RenderRegion) (Object) this);
        SodiumTerrainDrawer.onHookRemoveSection();
    }

    /** H3: the geometry buffer was replaced; every base vertex moves. */
    @Inject(method = MesheliumSodiumHooks.BUFFER_CHANGE_TARGET, at = @At("HEAD"),
            require = 0, expect = 0)
    private void meshelium$onGeometryBufferChange(CallbackInfo ci) {
        this.meshelium$epoch++;
        MesheliumRegionMirror.onMutation((RenderRegion) (Object) this);
        SodiumTerrainDrawer.onHookBufferChange();
    }

    /**
     * H4: one segment moved inside the buffer. The int packs section and
     * pass; translucent segments bump the epoch too, which over-invalidates
     * harmlessly and keeps this free of Sodium's pass-index constants.
     */
    @Inject(method = MesheliumSodiumHooks.SEGMENT_CHANGE_TARGET, at = @At("HEAD"),
            require = 0, expect = 0)
    private void meshelium$onGeometrySegmentChange(int packedOwner, CallbackInfo ci) {
        this.meshelium$epoch++;
        MesheliumRegionMirror.onMutation((RenderRegion) (Object) this);
        SodiumTerrainDrawer.onHookSegmentChange();
    }

    /**
     * H5: the region and its record heap are going away. The mirror hears
     * it FIRST: the heap dies when the target body runs, and the mirror
     * must release the region's id and queue its row for zeroing before
     * anything else forgets the region (I2, I5).
     */
    @Inject(method = MesheliumSodiumHooks.DELETE_TARGET, at = @At("HEAD"),
            require = 0, expect = 0)
    private void meshelium$onDelete(CallbackInfo ci) {
        MesheliumRegionMirror.onDelete((RenderRegion) (Object) this);
        this.meshelium$epoch++;
        this.meshelium$runCache = null;
        SodiumTerrainDrawer.onHookDelete();
    }
}
