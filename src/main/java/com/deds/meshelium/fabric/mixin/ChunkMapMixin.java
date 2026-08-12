/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.MesheliumExtendedRd;

import net.minecraft.server.level.ChunkMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Wave-10 server cap #1 of 2 (recon: docs/EXTENDED-RENDER-DISTANCE.md Q2).
 * {@code ChunkMap.setServerViewDistance} is where the integrated server's
 * follow-the-client-option chain ({@code IntegratedServer.tickServer} →
 * {@code PlayerList.setViewDistance} → {@code ServerChunkCache
 * .setViewDistance}, none of which clamp upward — all bytecode-verified)
 * finally hits a literal: {@code Mth.clamp(i, 2, 32)} (bipush 32, the only
 * int-32 constant in the method). Without widening it, a client option of
 * 64 still tracks/sends chunks only to 32 — and because
 * {@code Options.getEffectiveRenderDistance()} is
 * {@code min(option, serverRenderDistance)} on singleplayer, the CLIENT
 * would silently render at 32 too.
 *
 * <p>Gated live through {@link MesheliumExtendedRd#serverViewDistanceCap()}:
 * vanilla-exact 32 unless the gate is VULKAN_MESH_SHADERS with terrain
 * rendering enabled and a configured ceiling above 32. This mod is
 * environment {@code client}, so the mixin only ever applies inside the
 * client JVM's integrated server — dedicated servers never load it.
 * Runs on the server thread; the cap reads only volatile/immutable
 * state.</p>
 *
 * <p>Note the per-player SENDING radius has a second input this mixin
 * does not touch: {@code getPlayerViewDistance} =
 * {@code clamp(ServerPlayer.requestedViewDistance, 2, serverViewDistance)}.
 * The requested value only updates when the client re-sends
 * {@code ServerboundClientInformationPacket} ({@code Options.save()} →
 * {@code broadcastOptions()} — the options screen does this on close), so
 * a raised option that is never saved widens LOADING but not SENDING.
 * Bytecode + the 157-chunk postmortem: docs/EXTENDED-RENDER-DISTANCE.md
 * §2b.</p>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @ModifyConstant(method = "setServerViewDistance",
            constant = @Constant(intValue = 32))
    private int meshelium$widenServerViewDistanceClamp(int vanillaCap) {
        return Math.max(vanillaCap, MesheliumExtendedRd.serverViewDistanceCap());
    }
}
