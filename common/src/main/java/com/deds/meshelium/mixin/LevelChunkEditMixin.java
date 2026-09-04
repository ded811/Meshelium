/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.farfield.extract.ExtractDispatch;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import net.minecraft.network.FriendlyByteBuf;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Far-field save design E4 (M3): the block-change event - the hook that
 * made the 30-second staleness epoch unnecessary
 * (docs/FARFIELD-SAVE-DESIGN.md section 2, event E4).
 *
 * <h2>The injection point, from the bytecode</h2>
 * javap of the 26.2 merged jar in this authoring session:
 * <pre>
 *   public net.minecraft.world.level.block.state.BlockState
 *       setBlockState(net.minecraft.core.BlockPos,
 *                     net.minecraft.world.level.block.state.BlockState, int);
 * </pre>
 * The method has four exits, and only the LAST is a real change: null
 * returns at ip 37-38 (air into an all-air section), ip 84-85 (the
 * section CAS found {@code old == new} - vanilla's own old-equals-new
 * filter, which is why this hook needs none), and ip 512-513 (the
 * post-removal block mismatch); the success path runs
 * {@code markUnsaved} at ip 685 and returns the old state at ip 690.
 * {@code @At("TAIL")} binds to the last RETURN opcode only, so this
 * handler runs exactly once per REAL block change and never for a no-op
 * set.
 *
 * <h2>Both server routes land here - the hook replaces the poll</h2>
 * javap, same session: {@code ClientPacketListener.handleBlockUpdate}
 * calls {@code ClientLevel.setServerVerifiedBlockState} (ip 26), whose
 * miss arm is {@code Level.setBlock} (ip 19); {@code
 * handleChunkBlocksUpdate} is {@code ClientboundSectionBlocksUpdatePacket
 * .runUpdates} over a BiConsumer whose body ({@code
 * lambda$handleChunkBlocksUpdate$0}, ip 8) is that same
 * {@code setServerVerifiedBlockState}; and {@code Level.setBlock} is
 * {@code getChunkAt(...).setBlockState(...)} (ip 28-44). The player's
 * own predicted edit takes {@code ClientLevel.setBlock}'s predicting arm
 * to the identical place. So every route a client block change can take
 * funnels through this one TAIL.
 *
 * <h2>Cost</h2>
 * One static volatile read ({@code armed()}) and one
 * {@code isClientSide} test on every real block change; the integrated
 * SERVER's chunks (same class, different Level) exit on the second test.
 * The dispatch behind it is O(1) - a map get and field writes, with the
 * coalesce window absorbing farm/fill churn; see
 * {@link ExtractDispatch#onBlockChanged}.
 *
 * <p>Guard discipline: broken latch like every lifecycle hook - a
 * throwing dispatch disables itself for the session and never breaks
 * vanilla's block set.</p>
 */
@Mixin(LevelChunk.class)
abstract class LevelChunkEditMixin {

    @Unique
    private static boolean meshelium$editHookBroken;

    // javap, 26.2 merged jar (verified in this authoring session):
    //   public BlockState setBlockState(BlockPos, BlockState, int);
    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;I)"
                    + "Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("TAIL")
    )
    private void meshelium$afterSetBlockState(BlockPos pos, BlockState state,
            int flags, CallbackInfoReturnable<BlockState> cir) {
        if (!ExtractDispatch.armed() || meshelium$editHookBroken) {
            return;
        }
        LevelChunk self = (LevelChunk) (Object) this;
        try {
            if (!self.getLevel().isClientSide()) {
                return; // the integrated server's copy: not our event
            }
            ExtractDispatch.onBlockChanged(self);
        } catch (Throwable t) {
            meshelium$editHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium far-field block-change bookkeeping failed; "
                            + "disabling the hook for this session (blocks change "
                            + "normally, horizon edits fall back to the drop seam)", t);
        }
    }

    /**
     * The BIOME arm of E4. {@code LevelChunk.replaceBiomes} rewrites
     * every section's biome container and does NOT {@code markUnsaved}
     * (javap this session: its whole body is a
     * {@code LevelChunkSection.readBiomes} loop, ip 0-35), so a
     * server-side biome repaint ({@code ClientboundChunksBiomesPacket} -
     * the route Colour Blending's save-time biome reads care about)
     * would otherwise change stored content with NO event and NO
     * backstop: the census's isUnsaved() read cannot see it either.
     * Same event, same dispatch, same cost argument as the block arm -
     * one per repainted chunk, not per biome cell.
     */
    // javap, 26.2 merged jar (verified in this authoring session):
    //   public void replaceBiomes(net.minecraft.network.FriendlyByteBuf);
    @Inject(
            method = "replaceBiomes(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At("TAIL")
    )
    private void meshelium$afterReplaceBiomes(FriendlyByteBuf buffer,
            CallbackInfo ci) {
        if (!ExtractDispatch.armed() || meshelium$editHookBroken) {
            return;
        }
        LevelChunk self = (LevelChunk) (Object) this;
        try {
            if (!self.getLevel().isClientSide()) {
                return;
            }
            ExtractDispatch.onBlockChanged(self);
        } catch (Throwable t) {
            meshelium$editHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium far-field biome-change bookkeeping failed; "
                            + "disabling the hook for this session", t);
        }
    }
}
