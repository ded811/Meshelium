/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.MesheliumCpuStages;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wave-12 CPU stage bracket around {@code SectionOcclusionGraph.update
 * (CameraRenderState, int, ChunkLoadingRenderState)} — the render-thread
 * share of vanilla's BFS bookkeeping, called once per frame at
 * {@code LevelRenderer.render} ip 713, AFTER the frame graph executed
 * (javap-verified; the wave-5 timing note — this frame's update feeds NEXT
 * frame's visibleSections). Its body (bytecode): fold the frame's chunk
 * load/unload + empty-section deltas ({@code updateLoadedChunks}/
 * {@code updateEmptySections}), {@code invalidateIfNeeded}, then either
 * schedule a FULL graph rebuild asynchronously
 * ({@code CompletableFuture.runAsync(..., Util.backgroundExecutor())} —
 * only the SCHEDULING cost lands in this bracket, honestly reported as
 * such) or run the partial BFS propagation ({@code runPartialUpdate} →
 * {@code runUpdates}) right here on the render thread — the part that
 * scales with section churn and render distance.
 *
 * <p>Instrumentation only; {@code if (!ARMED)} JIT-dead on normal runs;
 * pure-JDK recorder, GL-path safe (wave-1 dormancy untouched).</p>
 */
@Mixin(SectionOcclusionGraph.class)
abstract class SectionOcclusionGraphMixin {

    @Unique
    private static long meshelium$updateT0;

    @Inject(
            method = "update(Lnet/minecraft/client/renderer/state/level/CameraRenderState;I"
                    + "Lnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V",
            at = @At("HEAD")
    )
    private void meshelium$updateHead(CameraRenderState cameraRenderState, int fov,
            ChunkLoadingRenderState chunkLoadingRenderState, CallbackInfo ci) {
        if (!MesheliumCpuStages.ARMED) {
            return;
        }
        meshelium$updateT0 = System.nanoTime();
    }

    @Inject(
            method = "update(Lnet/minecraft/client/renderer/state/level/CameraRenderState;I"
                    + "Lnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V",
            at = @At("RETURN")
    )
    private void meshelium$updateReturn(CameraRenderState cameraRenderState, int fov,
            ChunkLoadingRenderState chunkLoadingRenderState, CallbackInfo ci) {
        if (!MesheliumCpuStages.ARMED) {
            return;
        }
        MesheliumCpuStages.record(MesheliumCpuStages.STAGE_OCCLUSION_UPDATE,
                System.nanoTime() - meshelium$updateT0);
    }
}
