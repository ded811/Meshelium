/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.vk.HelloMeshletRenderer;
import com.deds.meshelium.vk.TerrainDrawer;

import com.mojang.blaze3d.textures.GpuSampler;

import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The kill switch, frame-path doc Q2.6(b) / wave-2 shopping-list row 4:
 * {@code @Inject(HEAD, cancellable)} on
 * {@code ChunkSectionsToRender.renderGroup} — since wave 7 for BOTH groups,
 * each only when the triple gate holds — wave-1 gate says
 * VULKAN_MESH_SHADERS, AND terrain rendering is effectively on (since wave
 * 8: the {@code meshelium.terrainDraw} property when present — unchanged
 * harness semantics, re-read every call so tests can flip it live — else
 * {@code MesheliumConfig.enableTerrainRendering}, default TRUE; the
 * {@link MesheliumConfig} matrix), AND the drawer actually recorded (or
 * deliberately owns) the replacement pass. Because vanilla's RenderPass is
 * created INSIDE renderGroup, one cancel skips pass creation, uniform binds
 * and every draw of the group in one cut, and touches nothing else.
 *
 * <p>Wave 7: the TRANSLUCENT group is cancelled for
 * {@link TerrainDrawer#drawTranslucent} at the exact same frame point
 * vanilla's translucent draws held (after features/depth-copies — the
 * mixin IS that point). Translucent only ever owns a frame whose OPAQUE
 * group Meshelium also owned (the drawer couples them), so a mixed frame is
 * always vanilla-opaque + vanilla-translucent or Meshelium + Meshelium — the
 * blend pass always tests against depth its own opaque pass wrote.</p>
 *
 * <p><b>Rendering-off proof:</b> with terrain rendering effectively off
 * (property "false", or config false with no property) the handler returns
 * before {@link TerrainDrawer} is referenced — the class is never even
 * loaded, no state changes, renderGroup proceeds untouched. Same discipline
 * on the OpenGL path via the gate check, which runs FIRST (this mixin
 * targets a vanilla class that loads on both backends; {@code MesheliumConfig}
 * is pure loader/GSON code, wave-1-safe to touch anywhere).</p>
 *
 * <p><b>Failure containment:</b> the drawer returning false (early frames
 * without camera state, or the drawer's own error latch) leaves vanilla
 * uncancelled — terrain keeps rendering from vanilla's still-live
 * dual-pipeline buffers. The catch here covers only failures the drawer
 * cannot see (its own class-load), logged once then silenced.</p>
 */
@Mixin(ChunkSectionsToRender.class)
abstract class ChunkSectionsToRenderMixin {

    @Unique
    private static boolean meshelium$drawHookBroken;

    @Unique
    private static boolean meshelium$helloHookBroken;

    @Inject(
            method = "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
                    + "Lcom/mojang/blaze3d/textures/GpuSampler;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void meshelium$replaceTerrainGroups(ChunkSectionLayerGroup group, GpuSampler sampler,
            CallbackInfo ci) {
        if (meshelium$drawHookBroken
                || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS
                || !MesheliumConfig.terrainRenderingEnabled()) {
            return;
        }
        try {
            // The captured sampler is the exact chunkLayerSampler vanilla
            // would have bound as Sampler0 — reused for pixel parity.
            if (group == ChunkSectionLayerGroup.OPAQUE) {
                if (TerrainDrawer.drawOpaque((ChunkSectionsToRender) (Object) this, sampler)) {
                    ci.cancel();
                    // CANCELLING SKIPS OUR OWN TAIL. Mixin returns from the
                    // method at this point, so the probe below never runs on
                    // the very path it exists to observe - the one where
                    // Meshelium drew the opaque terrain. Fire it here
                    // instead. This is the whole reason the probe is not
                    // simply a TAIL hook, and it is what the recon's
                    // one-line fallback sketch missed: it was written
                    // before the kill switch cancelled anything.
                    meshelium$helloProbe();
                }
            } else if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
                if (TerrainDrawer.drawTranslucent((ChunkSectionsToRender) (Object) this, sampler)) {
                    ci.cancel();
                }
            }
        } catch (Throwable t) {
            meshelium$drawHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium terrain kill switch failed outside the drawer's own guard; "
                            + "disabling for this session (vanilla terrain resumes)", t);
        }
    }

    /**
     * The wave-2 hello-meshlet probe, at the recon's own documented
     * fallback anchor. It used to hang off {@code
     * LevelRenderer.lambda$addMainPass$0}, injected after this very
     * method's first call; it lives inside the callee now, filtered to
     * the OPAQUE group, which is the same recording position reached by a
     * stable route.
     *
     * <p><b>Why it moved.</b> A synthetic lambda descriptor is the most
     * fragile anchor available: generated, unnamed in source, and renamed
     * by any change to the enclosing method's shape. NeoForge 26.2 adds a
     * {@code Matrix4fc} parameter to {@code addMainPass} so its own render
     * event can carry the matrix, which renames the lambda and deletes the
     * descriptor we had pinned - a hard startup crash under {@code
     * defaultRequire = 1}, not a degraded frame. {@code
     * ChunkSectionsToRender} carries no NeoForge patch at all, so this
     * anchor holds on both loaders.</p>
     *
     * <p><b>What it costs.</b> TAIL here hands us no {@code
     * LevelRenderState}, so the probe draws its NDC triangle without the
     * world-space UBO the old site provided for free. The recon called
     * that out when it wrote this fallback down. It is a development toy
     * gated behind {@code -Dmeshelium.helloMeshlet} and off in every
     * shipped configuration, so the reduced capability costs nothing a
     * player can see.</p>
     *
     * <p>The gate ordering is wave 1's class-loading rule, not a
     * correctness one: {@link MesheliumGate} is checked BEFORE {@code
     * HelloMeshletRenderer} is named, so no class importing LWJGL Vulkan
     * loads on the OpenGL path. A try/catch cannot recover that, which is
     * why it is a guard and not a handler.</p>
     */
    @Inject(
            method = "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
                    + "Lcom/mojang/blaze3d/textures/GpuSampler;)V",
            at = @At("TAIL")
    )
    private void meshelium$afterOpaqueTerrain(ChunkSectionLayerGroup group, GpuSampler sampler,
            CallbackInfo ci) {
        if (group != ChunkSectionLayerGroup.OPAQUE) {
            return;
        }
        // Reached only when the kill switch did NOT cancel - Meshelium is
        // not drawing opaque terrain this frame, so vanilla just did, and
        // TAIL is the point after it. The cancelled path fires the probe
        // from the HEAD handler instead; the two are exclusive by
        // construction, so the probe runs exactly once either way.
        meshelium$helloProbe();
    }

    /**
     * The probe call itself, shared by the two mutually exclusive paths
     * above. Gate ordering is wave 1's class-loading rule and not a
     * correctness one: {@link MesheliumGate} is checked BEFORE
     * {@code HelloMeshletRenderer} is named, so no class importing LWJGL
     * Vulkan loads on the OpenGL path. A try/catch cannot recover that,
     * which is why it is a guard and not a handler.
     */
    @Unique
    private static void meshelium$helloProbe() {
        if (meshelium$helloHookBroken
                || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
            return;
        }
        try {
            HelloMeshletRenderer.afterOpaqueTerrain(null);
        } catch (Throwable t) {
            meshelium$helloHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium hello-meshlet hook failed outside the renderer's own guard; "
                            + "disabling the hook for this session", t);
        }
    }
}
