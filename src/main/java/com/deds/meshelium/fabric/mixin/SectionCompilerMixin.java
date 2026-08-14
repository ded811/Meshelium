/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.terrain.host.SectionBuildTap;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wave-3b tap, section-build doc shopping-list row 1: {@code @Inject} at
 * RETURN of {@code SectionCompiler.compile} (public method, descriptor
 * javap-verified against the 26.2 jar) — the one moment the section's
 * complete geometry exists in ONE readable CPU container per layer
 * ({@code Results.renderedLayers}, public field), on the build thread,
 * before any cancellation or staging (Q3.1). Fires once per section BUILD;
 * resort-only tasks never call compile (Q4.2), so they can never re-encode.
 *
 * <p><b>This target loads on BOTH backends</b> — unlike wave 1's
 * VulkanBackend mixin, this body runs on OpenGL too. The gate check is the
 * FIRST statement and the only thing the GL path ever executes: one static
 * volatile read, zero allocation, no Meshelium class beyond the gate itself
 * touched (SectionBuildTap stays unloaded on GL).</p>
 */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {

    @Unique
    private static boolean meshelium$tapBroken;

    @Inject(
            method = "compile(Lnet/minecraft/core/SectionPos;"
                    + "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;"
                    + "Lcom/mojang/blaze3d/vertex/VertexSorting;"
                    + "Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)"
                    + "Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
            at = @At("RETURN")
    )
    private void meshelium$afterCompile(SectionPos sectionPos, RenderSectionRegion region,
            VertexSorting vertexSorting, SectionBufferBuilderPack pack,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {
        // terrainRenderingEnabled() is checked here, not just at the draw:
        // encoding into an arena nobody draws is what made the doubled VRAM
        // a steady state rather than a swap transient. Every master-switch
        // edge issues an allChanged(), which is what re-encodes the world
        // when this comes back.
        if (meshelium$tapBroken || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS
                || !MesheliumConfig.terrainRenderingConfigured()) {
            return;
        }
        try {
            SectionBuildTap.onCompileReturn(sectionPos, cir.getReturnValue());
        } catch (Throwable t) {
            meshelium$tapBroken = true;
            MesheliumClient.LOGGER.error(
                    "Meshelium section tap failed outside its own guard; disabling for this session", t);
        }
    }
}
