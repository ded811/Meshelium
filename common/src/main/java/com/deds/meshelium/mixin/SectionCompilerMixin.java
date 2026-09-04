/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumGate;
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
 *
 * <h2>Two arms, because the loaders disagree about this method</h2>
 * <p>NeoForge adds a fifth parameter to {@code compile} so its
 * {@code AddSectionGeometryEvent} can contribute geometry, turns the
 * four-argument form into a {@code @Deprecated} shim that delegates, and
 * repoints its only caller — {@code RenderSection$CompileTask} — at the new
 * overload. Verified against NeoForge 26.2.0.75's own source patches.
 *
 * <p>A tap pinned to the four-argument form therefore APPLIES CLEANLY on
 * NeoForge, satisfies {@code defaultRequire = 1}, logs nothing, and never
 * fires. Nothing is decoded, nothing is parked, the arena stays empty, and
 * the drawer hands every frame back to vanilla: a normal-looking world at
 * vanilla frame rate. That is this renderer's worst failure mode and it is
 * exactly what {@code SectionBuildTap}'s disconnected-tap detector exists
 * to catch.
 *
 * <p>So both forms are tapped, each with {@code require = 0}, and the arms
 * are mutually exclusive in practice: Fabric has only the four-argument
 * method, NeoForge routes everything through the five. The one case where
 * both could fire for a single build is a legacy caller on NeoForge
 * reaching the shim, which then delegates — and the guard in
 * {@code onCompileReturn} drops the second arm by results identity, so a
 * section is never decoded twice.
 *
 * <p>The five-argument arm takes a raw {@code List} deliberately. Its real
 * element type is a NeoForge class that cannot be named from shared code,
 * generics are erased in the descriptor Mixin matches on, and the parameter
 * is unused here.</p>
 */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {

    @Unique
    private static boolean meshelium$tapBroken;

    /** Fabric's only form, and NeoForge's deprecated shim. */
    @Inject(
            method = "compile(Lnet/minecraft/core/SectionPos;"
                    + "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;"
                    + "Lcom/mojang/blaze3d/vertex/VertexSorting;"
                    + "Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)"
                    + "Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
            at = @At("RETURN"),
            require = 0
    )
    private void meshelium$afterCompile(SectionPos sectionPos, RenderSectionRegion region,
            VertexSorting vertexSorting, SectionBufferBuilderPack pack,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {
        meshelium$tap(sectionPos, cir.getReturnValue());
    }

    /**
     * NeoForge's real form, the one its own caller uses. Absent on Fabric,
     * hence {@code require = 0}; if BOTH arms are ever absent the tap is
     * dead, and the detector in {@link SectionBuildTap} reports it by name
     * rather than letting the world quietly render at vanilla speed.
     */
    @Inject(
            method = "compile(Lnet/minecraft/core/SectionPos;"
                    + "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;"
                    + "Lcom/mojang/blaze3d/vertex/VertexSorting;"
                    + "Lnet/minecraft/client/renderer/SectionBufferBuilderPack;"
                    + "Ljava/util/List;)"
                    + "Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
            at = @At("RETURN"),
            require = 0
    )
    private void meshelium$afterCompileWithAddedGeometry(SectionPos sectionPos,
            RenderSectionRegion region, VertexSorting vertexSorting,
            SectionBufferBuilderPack pack, java.util.List<?> additionalRenderers,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {
        meshelium$tap(sectionPos, cir.getReturnValue());
    }

    @Unique
    private static void meshelium$tap(SectionPos sectionPos, SectionCompiler.Results results) {
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
            SectionBuildTap.onCompileReturn(sectionPos, results);
        } catch (Throwable t) {
            meshelium$tapBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium section tap failed outside its own guard; disabling for this session", t);
        }
    }
}
