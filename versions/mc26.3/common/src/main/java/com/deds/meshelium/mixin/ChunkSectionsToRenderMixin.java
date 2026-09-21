/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.vk.MainPassSuspension;
import com.deds.meshelium.vk.TerrainKillSwitch;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The terrain kill switch's seams on Minecraft 26.3. The behaviour is in
 * {@link TerrainKillSwitch}; this file is the three signatures and the one
 * thing 26.3 adds around them, {@link MainPassSuspension}.
 *
 * <p>26.3's {@code renderGroup} takes the render pass and the atlas view
 * as parameters (26.2 read the atlas off the sections record and used the
 * encoder's current pass) plus a wireframe flag, the F3 debug view. A
 * wireframe request is left to vanilla: Meshelium's mesh pipelines have no
 * wireframe variant, and a debug view that silently showed solid terrain
 * would be worse than one Meshelium sits out.
 *
 * <p>The pass parameter is vanilla's main pass, OPEN: on 26.3 the terrain
 * groups are drawn inside the same pass as the entities, which Meshelium's
 * multi-pass draw cannot share. So the draw runs inside a
 * {@link MainPassSuspension}, which ends that pass first and reopens it
 * after; when it declines, vanilla draws the group.
 *
 * <p>The third seam is new with 26.3. With "Improved Transparency" on,
 * vanilla no longer calls {@code renderGroup} for the translucent group at
 * all: it draws translucent terrain through three order-independent
 * passes ({@code renderOit}, one per {@code OitStage}, each in its own
 * render pass on its own attachments). When Meshelium has already drawn
 * the translucent terrain into the main target - which
 * {@code LevelRendererTranslucencyMixin} arranges at the end of the solid
 * pass - those three passes would draw vanilla's copy of the same terrain
 * a second time (or, with Duplicate Terrain Memory freed, nothing at all
 * and the water would be missing). So they are cancelled for the frame.
 * Everything else the OIT resolve composites (entities, particles,
 * weather, clouds) is untouched.
 */
@Mixin(ChunkSectionsToRender.class)
abstract class ChunkSectionsToRenderMixin {

    @Inject(
            method = "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
                    + "Lcom/mojang/renderpearl/api/commands/RenderPass;"
                    + "Lcom/mojang/renderpearl/api/textures/GpuSampler;"
                    + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void meshelium$replaceTerrainGroups(ChunkSectionLayerGroup group, RenderPass pass,
            GpuSampler sampler, GpuTextureView atlasView, boolean wireframe, CallbackInfo ci) {
        if (wireframe || !TerrainKillSwitch.armed()) {
            return;
        }
        if (group == ChunkSectionLayerGroup.TRANSLUCENT && !TerrainKillSwitch.translucentWanted()) {
            return; // vanilla drew opaque this frame; it draws translucent too
        }
        try (MainPassSuspension suspended = MainPassSuspension.suspend(pass)) {
            if (suspended == null) {
                return;
            }
            if (TerrainKillSwitch.beforeRenderGroup((ChunkSectionsToRender) (Object) this, group,
                    sampler, atlasView)) {
                ci.cancel();
            }
        }
    }

    @Inject(
            method = "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
                    + "Lcom/mojang/renderpearl/api/commands/RenderPass;"
                    + "Lcom/mojang/renderpearl/api/textures/GpuSampler;"
                    + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V",
            at = @At("TAIL")
    )
    private void meshelium$afterOpaqueTerrain(ChunkSectionLayerGroup group, RenderPass pass,
            GpuSampler sampler, GpuTextureView atlasView, boolean wireframe, CallbackInfo ci) {
        TerrainKillSwitch.afterRenderGroup(group);
    }

    @Inject(
            method = "renderOit(Lcom/mojang/renderpearl/api/textures/GpuSampler;"
                    + "Lnet/minecraft/client/renderer/oit/OitStage;"
                    + "Lnet/minecraft/client/renderer/oit/OitRenderPassProvider$Parameters;"
                    + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;"
                    + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void meshelium$skipOitTerrainWhenDrawn(GpuSampler sampler, OitStage stage,
            OitRenderPassProvider.Parameters parameters, GpuTextureView atlasView,
            GpuTextureView lightmapView, CallbackInfo ci) {
        if (TerrainKillSwitch.translucentOwnedThisFrame()) {
            ci.cancel();
        }
    }
}
