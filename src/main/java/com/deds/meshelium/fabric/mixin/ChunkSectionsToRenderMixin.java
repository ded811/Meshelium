/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.fabric.MesheliumClient;
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
                }
            } else if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
                if (TerrainDrawer.drawTranslucent((ChunkSectionsToRender) (Object) this, sampler)) {
                    ci.cancel();
                }
            }
        } catch (Throwable t) {
            meshelium$drawHookBroken = true;
            MesheliumClient.LOGGER.error(
                    "Meshelium terrain kill switch failed outside the drawer's own guard; "
                            + "disabling for this session (vanilla terrain resumes)", t);
        }
    }
}
