/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.MesheliumExtendedRd;

import net.minecraft.client.Options;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wave-10: widen the render-distance option BEFORE {@code options.txt}
 * loads. {@code Options.<init>(Minecraft, File)} (the only ctor,
 * javap-verified) builds every OptionInstance and then calls
 * {@code this.load()} as its very last action (bytecode ip 5050, one call
 * site) — and the option's persistence codec was captured at
 * OptionInstance construction as {@code Codec.intRange(2, 33)}, so a
 * previously saved 48 would be REJECTED by the load (vanilla resets the
 * field to its default 12) if the widening ran any later. Injecting at
 * the {@code load()} INVOKE is the exact point where all options exist
 * but none have been read from disk.
 *
 * <p>The range widening itself is config-gated inside
 * {@link MesheliumExtendedRd#widenAtConstruction} (ceiling &gt; 32 ∧
 * terrain enabled — since wave 13 the ceiling DEFAULTS to 96, so the
 * widening is the normal boot path; setting the cap to 32 restores the
 * vanilla-exact no-op). The same call also widens the chunk-task
 * priority ladder UNCONDITIONALLY (+64 rungs, wave 13 — even on GL and
 * even with the cap at 32; see the ladder javadoc for the mid-session
 * crash class this closes). The gate cannot have decided yet (device
 * doesn't exist during {@code Options.<init>} — MesheliumGate javadoc);
 * the title-screen decision tick re-validates and clamps back
 * (the wave-10 invariant).</p>
 */
@Mixin(Options.class)
public abstract class OptionsMixin {

    @Inject(method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Options;load()V"))
    private void meshelium$widenRenderDistanceBeforeLoad(CallbackInfo ci) {
        MesheliumExtendedRd.widenAtConstruction((Options) (Object) this);
    }
}
