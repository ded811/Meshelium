/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.MesheliumExtendedRd;

import net.minecraft.server.level.DistanceManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Wave-10 server cap #2 of 2 (recon: docs/EXTENDED-RENDER-DISTANCE.md Q2).
 * {@code DistanceManager.<init>} builds
 * {@code new PlayerTicketTracker(this, 32)} (bipush 32 at ctor ip 35 —
 * the only int-32 literal in the ctor; the other tracker takes 8, the
 * dispatcher 10, all javap-read). That 32 becomes
 * {@code FixedPlayerDistanceChunkTracker.maxDistance}: the tracker's
 * per-chunk player-distance levels default to {@code maxDistance + 2} and
 * never propagate past it, so chunks farther than ~34 from every player
 * can never receive the PLAYER_LOADING ticket that makes them load —
 * {@code ChunkMap}'s clamp (cap #1) governs SENDING, this one governs
 * LOADING, and both must widen for chunks to exist out to an extended
 * horizon.
 *
 * <p>Same live gate as cap #1. Construction-time semantics: the tracker
 * range is fixed per world load from the CONFIG CEILING — wave 13 keeps
 * it ceiling-derived on purpose: with the new default ceiling of 96 the
 * tracker covers the whole cap up front, which is what makes a mid-world
 * VANILLA-SLIDER raise fully live (loading follows without a rejoin; the
 * wave-10 dead-end was exactly a tracker pinned at 32 that no slider
 * move could cross). Known cost, now paid by DEFAULT on every
 * gate-open+terrain-enabled singleplayer world: the tracker maintains
 * its distance field out to {@code cap + 2} chunks around each player
 * regardless of the CURRENT option value — O(cap²) map entries per
 * player, ~38k longs at cap 96 vs vanilla's ~1.2k (a few MiB per player,
 * integrated server = normally one player). Lowering the options-screen
 * ceiling reduces it at the next world load; documented as a wave-13
 * risk row in docs/EXTENDED-RENDER-DISTANCE.md.</p>
 */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {

    @ModifyConstant(method = "<init>",
            constant = @Constant(intValue = 32))
    private int meshelium$widenPlayerTicketTrackerRange(int vanillaRange) {
        return Math.max(vanillaRange, MesheliumExtendedRd.serverViewDistanceCap());
    }
}
