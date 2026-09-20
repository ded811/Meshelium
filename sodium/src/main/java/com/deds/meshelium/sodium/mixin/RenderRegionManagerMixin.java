/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium.mixin;

import com.deds.meshelium.sodium.MesheliumRegionCacheHolder;
import com.deds.meshelium.sodium.MesheliumRegionMirror;
import com.deds.meshelium.sodium.MesheliumSodiumHooks;
import com.deds.meshelium.vk.SodiumTerrainDrawer;

import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * H1 of the five record-mutation hooks (D-021): a region is about to
 * receive build results.
 *
 * <h2>Why this method</h2>
 * <p>The private per-region {@code uploadResults} overload is the sole
 * caller of {@code SectionRenderDataStorage.setVertexData} — the only
 * writer of a fresh geometry record — and of {@code removeVertexData},
 * which clears the record of a section rebuilt to empty. It also creates
 * the region's device resources and, when the arena's upload replaced the
 * buffer, calls {@code onGeometryBufferChange} (H3) itself. One hook here
 * therefore covers every way an upload can change what the cache holds.
 *
 * <h2>Why HEAD and not TAIL</h2>
 * <p>The method has an early {@code return} — nothing pending to upload
 * after the removals — that is reached AFTER the records of sections
 * rebuilt to empty have been cleared. Mixin's TAIL is the LAST return
 * only, so a TAIL hook would miss that path and the cache would keep runs
 * whose base vertex now points at freed arena bytes: wrong geometry, the
 * failure worse than a hole. HEAD fires unconditionally, and since the
 * epoch is only read at {@code render()}, after the whole extract phase,
 * bumping before the write is as good as bumping after it.
 *
 * <p>It also fires for regions whose results are sort-only (translucent
 * index data, opaque records untouched). Stage 1 accepts that
 * over-invalidation; {@code regionsRebuilt} in the bench will say whether
 * it matters.
 *
 * <h2>Why {@code require = 0}</h2>
 * <p>See {@link RenderRegionMixin}: a renamed target must cost a slower
 * frame, not a crash, and the plugin's {@code preApply} check is what
 * decides whether the cache arms.
 *
 * <h2>Clean room</h2>
 * <p>Sodium is PolyForm Shield. This mixin names one of Sodium's private
 * methods by descriptor and contains no Sodium code.
 */
@Mixin(RenderRegionManager.class)
public abstract class RenderRegionManagerMixin {

    @Inject(method = MesheliumSodiumHooks.UPLOAD_TARGET, at = @At("HEAD"),
            require = 0, expect = 0)
    private void meshelium$onUploadResults(RenderRegion region, Collection<?> results,
            UniformBufferManager uniforms, CallbackInfo ci) {
        if (region instanceof MesheliumRegionCacheHolder holder) {
            holder.meshelium$bumpEpoch();
        }
        // Stage 2 (D-023): the same site dirties the GPU record mirror.
        MesheliumRegionMirror.onMutation(region);
        SodiumTerrainDrawer.onHookUpload();
    }
}
