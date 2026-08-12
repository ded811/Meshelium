/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.MesheliumCpuStages;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wave-12 CPU stage brackets (instrumentation ONLY — no behaviour change,
 * ever; every handler is {@code if (!ARMED) return} where ARMED is a
 * {@code static final} resolved at class load, JIT-dead on normal runs and
 * on the GL path — {@code MesheliumCpuStages} is pure JDK, so touching it on
 * OpenGL breaks no wave-1 dormancy rule):
 *
 * <ul>
 *   <li><b>{@code extract} HEAD/RETURN</b> — the render-thread extraction
 *       stage ({@code Minecraft.renderFrame} ip 441 →
 *       {@code GameRenderer.extract} ip 103 → here, javap-verified):
 *       dirty-section scan over {@code visibleSections} + per-dirty-section
 *       {@code RenderRegionCache} snapshots + entity/block-entity/particle
 *       extraction. HEAD is also the frame boundary — the previous frame's
 *       stage row commits here.</li>
 *   <li><b>{@code applyFrustum} HEAD/RETURN</b> — the {@code visibleSections}
 *       REBUILD ({@code clearVisibleSections} +
 *       {@code SectionOcclusionGraph.addSectionsInFrustum} octree walk;
 *       jar-wide census: this private method is addSectionsInFrustum's ONLY
 *       caller). It is CONDITIONAL — extract ip 256–295 runs it only when
 *       {@code consumeFrustumUpdate()} or the camera rotation crossed a 2°
 *       bucket (floor(rot/2) fields prevCamRotX/Y) — so the run-count
 *       series is measurement gold: it separates "the list rebuild costs X"
 *       from "the list rebuild happens on Y% of frames" (bench's static
 *       camera: ~0%; real mouse-look: most frames). Nested inside the
 *       extract bracket; the recorder documents never summing the two.</li>
 * </ul>
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {

    @Unique
    private static long meshelium$extractT0;

    @Unique
    private static long meshelium$applyT0;

    @Inject(
            method = "extract(Lnet/minecraft/client/DeltaTracker;"
                    + "Lnet/minecraft/client/Camera;F)V",
            at = @At("HEAD")
    )
    private void meshelium$extractHead(DeltaTracker deltaTracker, Camera camera, float partialTick,
            CallbackInfo ci) {
        if (!MesheliumCpuStages.ARMED) {
            return;
        }
        MesheliumCpuStages.beginFrame();
        meshelium$extractT0 = System.nanoTime();
    }

    @Inject(
            method = "extract(Lnet/minecraft/client/DeltaTracker;"
                    + "Lnet/minecraft/client/Camera;F)V",
            at = @At("RETURN")
    )
    private void meshelium$extractReturn(DeltaTracker deltaTracker, Camera camera, float partialTick,
            CallbackInfo ci) {
        if (!MesheliumCpuStages.ARMED) {
            return;
        }
        MesheliumCpuStages.record(MesheliumCpuStages.STAGE_EXTRACT,
                System.nanoTime() - meshelium$extractT0);
    }

    @Inject(
            method = "applyFrustum(Lnet/minecraft/client/renderer/culling/Frustum;)V",
            at = @At("HEAD")
    )
    private void meshelium$applyFrustumHead(Frustum frustum, CallbackInfo ci) {
        if (!MesheliumCpuStages.ARMED) {
            return;
        }
        meshelium$applyT0 = System.nanoTime();
    }

    @Inject(
            method = "applyFrustum(Lnet/minecraft/client/renderer/culling/Frustum;)V",
            at = @At("RETURN")
    )
    private void meshelium$applyFrustumReturn(Frustum frustum, CallbackInfo ci) {
        if (!MesheliumCpuStages.ARMED) {
            return;
        }
        MesheliumCpuStages.record(MesheliumCpuStages.STAGE_APPLY_FRUSTUM,
                System.nanoTime() - meshelium$applyT0);
        MesheliumCpuStages.noteApplyFrustumRun();
    }
}
