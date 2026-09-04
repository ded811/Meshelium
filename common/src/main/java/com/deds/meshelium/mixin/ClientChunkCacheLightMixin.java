/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.farfield.extract.ExtractDispatch;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Far-field save design E10 (pre19 S4): the LIGHT-CHANGE event - the
 * hook that fixes "a torch at a chunk edge does not light the
 * neighbouring LOD chunk".
 *
 * <h2>The bug this closes</h2>
 * E4 ({@link net.minecraft.world.level.chunk.LevelChunk#setBlockState})
 * marks the column CONTAINING the changed block dirty, and nothing
 * else. Block light propagates up to 15 blocks and therefore ACROSS
 * chunk planes, so a torch within reach of an edge changes the stored
 * light of the NEIGHBOUR column too - and that column stayed clean, so
 * its stored plane kept the pre-torch light until something unrelated
 * dirtied it. The invalidation UNIT was wrong: for lighting purposes a
 * block change dirties a REGION, not a column.
 *
 * <h2>Why we mirror vanilla instead of computing a radius</h2>
 * The client light engine already computes the exact affected set and
 * publishes it, and this callback IS that publication. javap of the
 * 26.2 merged jar, this authoring session:
 *
 * <ul>
 * <li>{@code LayerLightSectionStorage.setStoredLevel(long, int)} - the
 * one writer of a light value - calls
 * {@code SectionPos.aroundAndAtBlockPos(blockPos, sectionsAffectedByLightUpdates::add)}
 * at ip 70-85. {@code aroundAndAtBlockPos} (ip 0-46) takes the section
 * span of {@code pos +/- 1} on each axis, so a light value written
 * within one block of a chunk plane marks the NEIGHBOUR's section too -
 * and the propagation itself writes values inside the neighbour chunk
 * anyway, which marks it directly.</li>
 * <li>{@code LayerLightSectionStorage.markSectionAndNeighborsAsAffected(long)}
 * (ip 0-89, a 3x3x3 section loop) adds the conservative halo when a
 * section's light data is first CREATED
 * ({@code initializeSection}, ip 42-47).</li>
 * <li>{@code LayerLightSectionStorage.swapSectionMap()} drains that set
 * at ip 38-105 and calls
 * {@code LightChunkGetter.onLightUpdate(layer, SectionPos.of(l))} once
 * per affected section - deduped by the {@code LongSet}, so the cost is
 * per SECTION per light run and never per block change.</li>
 * <li>{@code LightEngine.runLightUpdates()} calls {@code swapSectionMap}
 * last (ip 76-80); {@code ClientLevel.update()} calls
 * {@code pollLightUpdates()} then {@code runLightUpdates()} (ip 13-36),
 * and {@code Minecraft.runTick} calls {@code ClientLevel.update()} at
 * ip 372-376 - BEFORE the extract/render phase that ends in our pump.
 * So every callback lands on the game thread, in the same frame as the
 * edit that caused it, before the frame's far-field pump.</li>
 * <li>{@code ClientChunkCache.onLightUpdate} is the client's
 * implementation and its whole body is
 * {@code Minecraft.getInstance().levelExtractor.setSectionDirty(x, y, z)}
 * (ip 0-21) - vanilla re-meshes exactly these sections. We invalidate
 * exactly the same set, one level of granularity coarser (the column).</li>
 * </ul>
 *
 * <p>The alternative - keying a conservative radius off the block's own
 * emission at E4 - was rejected with arithmetic: block light reaches 15
 * blocks, so "conservative" degenerates to the whole 3x3 for almost
 * every position in a chunk, while vanilla's answer is the set of
 * sections whose values ACTUALLY changed. A torch inside solid stone
 * marks one column; the same torch in open air marks what it really
 * lights. Cheapest in the common case, exact in the expensive one.</p>
 *
 * <h2>Cost</h2>
 * One static volatile read and one map get per affected section per
 * light run; the record's own dirty arithmetic early-outs every
 * repeat, so a column with 24 lit sections pays one filing and 47 long
 * compares. The chunk-arrival halo that
 * {@code markSectionAndNeighborsAsAffected} produces is suppressed
 * inside the dispatch (see {@code ExtractDispatch.onLightChanged}) -
 * creating a data layer is not a light change, and mirroring it would
 * have re-dirtied a 5x5 of settled columns around every arriving chunk.
 *
 * <p>Guard discipline: broken latch like every lifecycle hook - a
 * throwing dispatch disables itself for the session and never breaks
 * vanilla's light publication.</p>
 */
@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheLightMixin {

    @Unique
    private static boolean meshelium$lightHookBroken;

    // javap, 26.2 merged jar (verified in this authoring session):
    //   public void onLightUpdate(net.minecraft.world.level.LightLayer,
    //                             net.minecraft.core.SectionPos);
    @Inject(
            method = "onLightUpdate(Lnet/minecraft/world/level/LightLayer;"
                    + "Lnet/minecraft/core/SectionPos;)V",
            at = @At("HEAD")
    )
    private void meshelium$onLightUpdate(LightLayer layer, SectionPos pos,
            CallbackInfo ci) {
        if (!ExtractDispatch.armed() || meshelium$lightHookBroken) {
            return;
        }
        try {
            // SectionPos.x()/z() ARE the chunk coordinates (vanilla's own
            // onLightUpdate feeds them straight to
            // LevelExtractor.setSectionDirty, ip 6-18). The layer is not
            // needed: our record stores one packed light byte per cell,
            // so sky and block staleness are the same staleness.
            ExtractDispatch.onLightChanged(pos.x(), pos.z());
        } catch (Throwable t) {
            meshelium$lightHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium far-field light-change bookkeeping failed; "
                            + "disabling the hook for this session (lighting "
                            + "renders normally, horizon light edits fall back "
                            + "to the census repair)", t);
        }
    }
}
