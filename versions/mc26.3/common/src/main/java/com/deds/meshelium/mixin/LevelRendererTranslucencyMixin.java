/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.compat.McCompat;
import com.deds.meshelium.vk.MainPassSuspension;
import com.deds.meshelium.vk.TerrainKillSwitch;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 26.3 only: where Meshelium draws translucent terrain when
 * "Improved Transparency" is on.
 *
 * <p>Classic transparency is unchanged from 26.2: after the solid pass,
 * still inside the main render pass, vanilla calls
 * {@code renderGroup(TRANSLUCENT, ...)} and the kill switch takes it there.
 * Improved transparency is the new order-independent path
 * ({@code LevelRenderer.executeOit}): the main pass is closed, and terrain
 * translucency is drawn through three {@code renderOit} passes on separate
 * depth-bounds, transmittance and accumulate attachments that a resolve
 * pass later composites over the main target. {@code renderGroup} is never
 * called for the translucent group, so without this seam Meshelium would
 * draw the opaque terrain and leave the translucent terrain to vanilla -
 * which, with Duplicate Terrain Memory freed, has no meshes to draw it
 * from.
 *
 * <p>So the translucent group is drawn HERE, at the tail of
 * {@code executeSolid}: the solid terrain and the solid entities are
 * already in the main target, the main pass is still open (hence the
 * {@link MainPassSuspension} around the draw), and the three OIT terrain
 * passes are then cancelled for the frame by
 * {@code ChunkSectionsToRenderMixin}. What differs from vanilla's OIT
 * result: translucent terrain is composited under the OIT layer rather
 * than sorted with it, so a translucent entity behind a window pane is
 * drawn over the pane rather than through it. Classic transparency has
 * the mirror image of that artefact (the pane over the entity), and this
 * is the same trade every classic renderer makes.
 *
 * <p>{@code executeSolid} is private and has one overload
 * ({@code (ChunkSectionsToRender, FeatureRenderDispatcher$PreparedFrame,
 * RenderPass)V}, javap 26.3); TAIL is the point after
 * {@code frame.executeSolid(pass)}, ip 63.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererTranslucencyMixin {

    /** The exact sampler vanilla bound as Sampler0 for the solid group. */
    @Shadow
    private GpuSampler chunkLayerSampler;

    @Inject(
            method = "executeSolid(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
                    + "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
                    + "Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
            at = @At("TAIL")
    )
    private void meshelium$translucentBeforeOit(ChunkSectionsToRender sections,
            FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass, CallbackInfo ci) {
        if (!Minecraft.getInstance().gameRenderer.useImprovedTransparency()) {
            return; // classic: renderGroup(TRANSLUCENT) follows and the kill switch takes it
        }
        if (!TerrainKillSwitch.translucentWanted()) {
            return;
        }
        try (MainPassSuspension suspended = MainPassSuspension.suspend(pass)) {
            if (suspended == null) {
                return;
            }
            if (TerrainKillSwitch.beforeRenderGroup(sections, ChunkSectionLayerGroup.TRANSLUCENT,
                    this.chunkLayerSampler, McCompat.blockAtlasView())) {
                TerrainKillSwitch.noteTranslucentUnderImprovedTransparency();
            }
        }
    }
}
