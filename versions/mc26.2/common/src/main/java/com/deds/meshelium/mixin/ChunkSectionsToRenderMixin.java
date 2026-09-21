/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.vk.TerrainKillSwitch;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The terrain kill switch's seams on Minecraft 26.2 (the 26.2 overlay).
 * The behaviour is in {@link TerrainKillSwitch}; this file is the two
 * signatures.
 *
 * <p>26.2's {@code renderGroup} takes the group and the sampler; the atlas
 * view is a component of the sections record ({@code textureView()}), which
 * is why it is read here and handed on - 26.3 passes it as a parameter and
 * dropped the component. Both groups go through this one method on 26.2,
 * translucent included, whatever "Improved Transparency" is set to: the
 * separate translucent target of that mode is what {@code outputTarget()}
 * hands the drawer.
 */
@Mixin(ChunkSectionsToRender.class)
abstract class ChunkSectionsToRenderMixin {

    @Inject(
            method = "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
                    + "Lcom/mojang/blaze3d/textures/GpuSampler;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void meshelium$replaceTerrainGroups(ChunkSectionLayerGroup group, GpuSampler sampler,
            CallbackInfo ci) {
        ChunkSectionsToRender sections = (ChunkSectionsToRender) (Object) this;
        if (TerrainKillSwitch.beforeRenderGroup(sections, group, sampler, sections.textureView())) {
            ci.cancel();
        }
    }

    @Inject(
            method = "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
                    + "Lcom/mojang/blaze3d/textures/GpuSampler;)V",
            at = @At("TAIL")
    )
    private void meshelium$afterOpaqueTerrain(ChunkSectionLayerGroup group, GpuSampler sampler,
            CallbackInfo ci) {
        TerrainKillSwitch.afterRenderGroup(group);
    }
}
