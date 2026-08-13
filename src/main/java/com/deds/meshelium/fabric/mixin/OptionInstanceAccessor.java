/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import com.mojang.serialization.Codec;

import net.minecraft.client.OptionInstance;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Wave-10 accessor pair for {@code OptionInstance}'s two private final
 * fields that pin the render-distance option to vanilla's 2..32
 * (javap-verified shapes, 26.2 jar):
 *
 * <ul>
 *   <li>{@code private final OptionInstance$ValueSet values} — the
 *       {@code IntRange(2, 32, false)} record instance. Validation
 *       ({@code IntRange.validateValue}) and the video-settings slider
 *       ({@code IntRangeBase.toSliderValue/fromSliderValue}) both dispatch
 *       through the record's accessors, so swapping the instance widens
 *       everything the UI touches.</li>
 *   <li>{@code private final Codec codec} — captured from
 *       {@code values.codec()} at OptionInstance construction
 *       ({@code Codec.intRange(min, max+1)} — {@code IntRange.codec()}
 *       reads the FIELDS, not the accessors), and used by
 *       {@code Options.load()/save()}. Swapping {@code values} alone would
 *       leave persistence rejecting values above 32 at the next boot,
 *       which is why the setter pair exists.</li>
 * </ul>
 *
 * Only {@code MesheliumExtendedRd} calls these, only on the client thread,
 * and only for the {@code renderDistance()} instance.
 */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor {

    @Accessor("values")
    OptionInstance.ValueSet<?> meshelium$values();

    @Mutable
    @Accessor("values")
    void meshelium$setValues(OptionInstance.ValueSet<?> values);

    @Accessor("codec")
    Codec<?> meshelium$codec();

    @Mutable
    @Accessor("codec")
    void meshelium$setCodec(Codec<?> codec);
}
