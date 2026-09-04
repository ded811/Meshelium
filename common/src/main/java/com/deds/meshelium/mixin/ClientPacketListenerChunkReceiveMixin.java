/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.farfield.extract.ExtractDispatch;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The RECEIVE-side extraction seam (FARFIELD-VANILLA-SEAM.md section 1.4
 * and its recommended-seams item 4), added in pre2 for defect B1 —
 * "a lot of gaps and missing chunks" (docs/FARFIELD-WAVES.md, OWNER
 * PLAYTEST OF pre1).
 *
 * <h2>Why this seam and not more unload-side machinery</h2>
 * Three vanilla paths make a chunk unreachable with NO unload callback
 * (dossier section 1.3): {@code updateViewRadius} copies only the
 * survivors into a fresh Storage, {@code updateViewCenter} just slides
 * the valid window, and a dimension swap or disconnect discards the whole
 * {@code ClientChunkCache}. Each of those needed its own rescue hook, and
 * two of them needed an accessor into the private
 * {@code ClientChunkCache$Storage} that the dossier left UNRESOLVED. They
 * share one upstream event: the chunk ARRIVED first. Extracting on
 * arrival makes all three moot at once, with public API only.
 *
 * <h2>The injection point, from the bytecode</h2>
 * javap of the 26.2 merged jar in this authoring session:
 * <pre>
 *   private void enableChunkLight(net.minecraft.world.level.chunk.LevelChunk, int, int);
 * </pre>
 * chosen over {@code updateLevelChunk} TAIL because it is the moment
 * where chunk AND light are both applied. Its body walks every section
 * calling {@code LevelLightEngine.updateSectionStatus} (ip 27-70), then
 * {@code ClientLevel.setSectionRangeDirty} (ip 76-106), then a single
 * {@code return} at ip 109 — one exit, so TAIL is unambiguous.
 *
 * <p><b>It fires exactly once per chunk delivery.</b> The whole class has
 * exactly ONE invocation of it, at ip 33 of
 * {@code lambda$handleLevelChunkWithLight$0}, the runnable
 * {@code handleLevelChunkWithLight} queues through
 * {@code ClientLevel.queueLightUpdate}. Stand-alone
 * {@code ClientboundLightUpdatePacket}s reach {@code applyLightData} and
 * never this method, so border light corrections for later neighbours
 * cannot re-trigger extraction. The lambda also re-fetches the chunk from
 * the cache and null-checks it before the call (ip 18-25), so the
 * argument is guaranteed live and current.</p>
 *
 * <p>Thread: the client main thread by construction — the enclosing
 * packet handler passed {@code ensureRunningOnSameThread}, and the queued
 * light task is drained by {@code ClientLevel.update()} from
 * {@code Minecraft.runTick} (dossier sections 1.1 and 1.4).</p>
 *
 * <h2>Guard discipline</h2>
 * First line is the armed fast path (one static volatile read). The
 * broken latch mirrors the other lifecycle hooks: a throwing extraction
 * disables itself for the session and never breaks vanilla's chunk
 * delivery — losing far-field shells must never lose the near field.
 * {@link ExtractDispatch#onChunkReceived} carries its own frame budget,
 * so a burst of arrivals cannot spend an unbounded slice of the tick
 * here.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerChunkReceiveMixin {

    @Unique
    private static boolean meshelium$receiveHookBroken;

    // javap, 26.2 merged jar (verified in this authoring session):
    //   private void enableChunkLight(net.minecraft.world.level.chunk.LevelChunk, int, int);
    @Inject(
            method = "enableChunkLight(Lnet/minecraft/world/level/chunk/LevelChunk;II)V",
            at = @At("TAIL")
    )
    private void meshelium$afterChunkLight(LevelChunk chunk, int chunkX, int chunkZ,
            CallbackInfo ci) {
        if (!ExtractDispatch.armed() || meshelium$receiveHookBroken) {
            return;
        }
        try {
            ExtractDispatch.onChunkReceived(chunk);
        } catch (Throwable t) {
            meshelium$receiveHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium far-field extraction failed at the chunk-receive seam; "
                            + "disabling the hook for this session (chunks load normally, "
                            + "the far field falls back to extracting at unload)", t);
        }
    }
}
