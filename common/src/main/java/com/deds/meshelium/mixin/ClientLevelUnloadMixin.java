/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.farfield.extract.ExtractDispatch;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Far-field wave W2, extraction seam 1 of the dossier's recommended set
 * (FARFIELD-VANILLA-SEAM.md): {@code ClientLevel.unload(LevelChunk)}
 * @HEAD is the single choke point for BOTH ways a live chunk dies with a
 * callback — {@code Storage.drop} (forget packet) and
 * {@code Storage.replace} (a stale chunk overwritten in its slot), each
 * javap-confirmed to invoke {@code ClientLevel.unload}. At HEAD the
 * section data is fully readable: unload's own first act is
 * {@code clearAllBlockEntities} (bytecode ip 0-1) and it never touches
 * section block data (dossier section 1.2), so the extractor walks a
 * complete chunk one call before its block entities disappear.
 *
 * <p>Runs on the client main thread by construction — every chunk
 * lifecycle handler passes {@code ensureRunningOnSameThread} against the
 * game thread's PacketProcessor (dossier section 1.1).</p>
 *
 * <p>Guard discipline: first line is the armed fast path (one static
 * volatile read — the far field's master switch is live, so it cannot be
 * a static final like the bench ARMED, but the cost shape is the same).
 * The broken latch mirrors the other lifecycle hooks: a throwing
 * extraction disables itself for the session and never breaks vanilla's
 * unload — losing far-field shells must never lose the near field.</p>
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelUnloadMixin {

    @Unique
    private static boolean meshelium$unloadHookBroken;

    // javap, 26.2 merged jar (verified in this authoring session):
    //   public void unload(net.minecraft.world.level.chunk.LevelChunk);
    @Inject(
            method = "unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            at = @At("HEAD")
    )
    private void meshelium$beforeUnload(LevelChunk chunk, CallbackInfo ci) {
        if (!ExtractDispatch.armed() || meshelium$unloadHookBroken) {
            return;
        }
        try {
            ExtractDispatch.onChunkDropping(chunk);
        } catch (Throwable t) {
            meshelium$unloadHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium far-field extraction failed at the unload seam; "
                            + "disabling the hook for this session (chunks unload normally, "
                            + "the far field just stops growing)", t);
        }
    }
}
