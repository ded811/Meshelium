/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.farfield.extract.ExtractDispatch;

import net.minecraft.client.multiplayer.ClientChunkCache;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Far-field save design E6 (pre16 M3): the SILENT-DISCARD rings
 * (FARFIELD-VANILLA-SEAM.md section 1.3). Two paths make chunks
 * unreachable WITHOUT ever calling {@code ClientLevel.unload}:
 *
 * <ul>
 * <li>{@code updateViewRadius(int)} allocates a fresh Storage and copies
 * only chunks the new range still holds — out-of-range chunks are simply
 * not copied, no callback (dossier bytecode walk, ip 18-149);</li>
 * <li>{@code updateViewCenter(int, int)} writes only
 * {@code Storage.viewCenterX/Z} (javap-confirmed: the whole body is two
 * putfields, ip 0-16) — chunks now out of range fail isValidChunk and
 * become invisible. The old caveat here ("until a later
 * {@code Storage.replace} evicts them, which routes through unload")
 * is only true for slots the new send disc REFILLS:
 * {@code ClientChunkCache.drop} returns at ip 12-18 when
 * {@code !storage.inRange}, so the forget storm no-ops for slid-out
 * chunks, and a move farther than the send pad leaves whole residue
 * rows of the old window unreplaced forever — no unload, no seam. That
 * is exactly how gametest (b)'s first real run lost an edit
 * (dirty record, no job, nothing coming).</li>
 * </ul>
 *
 * <p><b>The radius hook ACTS now, and this javadoc's old premise is
 * rewritten because it was FALSE.</b> Through pre15 it read "a
 * client-side render-distance change never reaches here" - true on a
 * remote server, jar-refuted on the INTEGRATED one. javap, 26.2 merged
 * jar, this authoring session: {@code IntegratedServer.tickServer} reads
 * {@code max(2, options.renderDistance)} every tick (ip 112-135),
 * compares it to {@code PlayerList.getViewDistance} (ip 137-146) and on
 * any change calls {@code PlayerList.setViewDistance} (ip 181), whose
 * body constructs {@code ClientboundSetChunkCacheRadiusPacket} and
 * {@code broadcastAll}s it (ip 6-14) - so in singleplayer the client's
 * own slider drives this hook, every time. That made
 * this hook's silent-abandon path the REAL repro-(a) loss mechanism (the
 * pre16 audit): the abandoned annulus of an rd shrink reached NO seam at
 * all. {@link ExtractDispatch#onViewRadiusShrink} now walks the OLD
 * window at HEAD - the old Storage is still installed, so the walk goes
 * through the PUBLIC {@code getChunk} and the dossier's UNRESOLVED
 * Storage-accessor row stays unresolved, deliberately - and applies the
 * drop transition to every leaving column: saved columns close in one
 * map probe, dirty ones are captured (reference grabs, never extraction,
 * so a once-per-rd-change storm costs O(1) per column). BOTH hooks capture
 * now: {@link ExtractDispatch#onViewCenterMove} walks the slid-out
 * annulus at ITS HEAD too (the one moment those chunks are still
 * reachable), one strip of probes per ordinary chunk crossing.</p>
 */
@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheViewMixin {

    @Unique
    private static boolean meshelium$viewRadiusHookBroken;

    @Unique
    private static boolean meshelium$viewCenterHookBroken;

    // javap, 26.2 merged jar (verified in this authoring session):
    //   public void updateViewRadius(int);
    @Inject(
            method = "updateViewRadius(I)V",
            at = @At("HEAD")
    )
    private void meshelium$beforeUpdateViewRadius(int newRadius, CallbackInfo ci) {
        if (!ExtractDispatch.armed() || meshelium$viewRadiusHookBroken) {
            return;
        }
        try {
            ExtractDispatch.onViewRadiusShrink(newRadius);
        } catch (Throwable t) {
            meshelium$viewRadiusHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium far-field view-radius bookkeeping failed; "
                            + "disabling the hook for this session", t);
        }
    }

    // javap, 26.2 merged jar (verified in this authoring session):
    //   public void updateViewCenter(int, int);
    @Inject(
            method = "updateViewCenter(II)V",
            at = @At("HEAD")
    )
    private void meshelium$beforeUpdateViewCenter(int newCenterX, int newCenterZ, CallbackInfo ci) {
        if (!ExtractDispatch.armed() || meshelium$viewCenterHookBroken) {
            return;
        }
        try {
            ExtractDispatch.onViewCenterMove(newCenterX, newCenterZ);
        } catch (Throwable t) {
            meshelium$viewCenterHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium far-field view-center bookkeeping failed; "
                            + "disabling the hook for this session", t);
        }
    }
}
