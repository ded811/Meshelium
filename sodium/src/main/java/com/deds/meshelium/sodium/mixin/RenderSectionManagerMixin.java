/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium.mixin;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.sodium.MesheliumChunkRenderer;

import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts Meshelium's renderer in Sodium's chunk-renderer slot.
 *
 * <h2>How, and why this way</h2>
 * <p>Sodium builds its renderer as {@code new DefaultChunkRenderer(...)}
 * straight into a {@code private final} field with a getter and no setter,
 * so there is nowhere to register a replacement. Substitution has to
 * happen at construction.
 *
 * <p>The obvious approach — redirecting the {@code NEW} or the constructor
 * call — does not type-check: those expressions are
 * {@code DefaultChunkRenderer}, and Meshelium's renderer implements the
 * {@code ChunkRenderer} interface rather than extending Sodium's class.
 * Redirecting the field write instead would work but pins the mixin to one
 * exact bytecode instruction.
 *
 * <p>So: inject at the constructor's tail and wrap whatever ended up in
 * the field. It depends only on the field existing and on the constructor
 * finishing, which is the least this can be coupled to. There is exactly
 * one constructor, so {@code <init>} is unambiguous.
 *
 * <h2>What it does</h2>
 * <p>{@link MesheliumChunkRenderer} draws the opaque passes with
 * Meshelium's mesh shaders, reading the geometry out of Sodium's own
 * region buffers, and forwards everything else — translucent geometry,
 * and every pass at all on a device without mesh shaders — to the
 * renderer it wrapped.
 *
 * <p>{@code -Dmeshelium.sodium.adapter=false} stops this mixin applying at
 * all, which puts Sodium's renderer back in its own slot with nothing
 * wrapped. See {@link MesheliumSodiumMixinPlugin}.
 *
 * <h2>Clean room</h2>
 * <p>Sodium is PolyForm Shield. This mixin names Sodium's class, field and
 * interface — the minimum required to interoperate — and contains no
 * Sodium code.
 */
@Mixin(RenderSectionManager.class)
public abstract class RenderSectionManagerMixin {

    @Shadow
    @Final
    @Mutable
    private ChunkRenderer chunkRenderer;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void meshelium$installChunkRenderer(CallbackInfo ci) {
        ChunkRenderer sodiums = this.chunkRenderer;
        if (sodiums == null) {
            // Nothing to delegate to means nothing to fall back to, and a
            // renderer that cannot hand the frame back turns any failure
            // into an invisible world. Leave Sodium alone.
            MesheliumLog.LOGGER.warn(
                    "Meshelium found no chunk renderer to wrap after Sodium's RenderSectionManager "
                            + "finished constructing. Leaving Sodium's pipeline untouched.");
            return;
        }
        if (sodiums instanceof MesheliumChunkRenderer) {
            return; // already ours; nothing sensible to do twice
        }
        // The manager rides along for stage 2 (D-023): the GPU record
        // mirror needs its frame counter, its region manager and, for the
        // distance gate, its private search distance through the invoker.
        this.chunkRenderer = new MesheliumChunkRenderer(sodiums, (RenderSectionManager) (Object) this);
        MesheliumLog.LOGGER.info(
                "Meshelium has taken Sodium's chunk-renderer slot, wrapping {}. Opaque terrain "
                        + "will be drawn with mesh shaders out of Sodium's own buffers; "
                        + "translucent geometry stays with Sodium.",
                sodiums.getClass().getName());
    }
}
