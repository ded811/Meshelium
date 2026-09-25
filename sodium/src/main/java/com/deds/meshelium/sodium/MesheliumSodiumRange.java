/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium;

import java.util.function.Supplier;

import net.caffeinemc.mods.sodium.api.config.option.SteppedValidator;

/**
 * A slider range that brings an out-of-range value to the nearest edge
 * instead of replacing it with the option's default.
 *
 * <p>Sodium validates every stored value against its option's range when
 * its screen opens and when a dependency moves the range, and the
 * interface default for {@code getValidatedValue} answers an invalid value
 * with the fallback the option passes in, which is the option's DEFAULT.
 * That is how a render distance of 64 once became 12 (D-019). With this
 * validator a render distance above the ceiling becomes the ceiling, and a
 * Distance Cap above the page's slider becomes the slider's top, which is
 * what the value meant anyway. Same shape as Sodium's own range record from
 * the slider's point of view: {@code min}, {@code max} and {@code step} are
 * what the control lays itself out from.
 *
 * <p>A valid value is returned as the SAME object it came in as: Sodium
 * compares the loaded and validated values by identity to decide whether
 * to write anything back, and boxing a fresh {@code Integer} above 127
 * would read as a change every time the screen opened.
 */
record MesheliumSodiumRange(int min, int max, int step) implements SteppedValidator {

    MesheliumSodiumRange {
        if (step <= 0 || max < min) {
            throw new IllegalArgumentException("bad range " + min + ".." + max + " step " + step);
        }
    }

    @Override
    public Integer getValidatedValue(Integer value, Supplier<Integer> fallback) {
        if (value == null) {
            return fallback.get();
        }
        if (isValueValid(value)) {
            return value;
        }
        int clamped = Math.clamp(value, this.min, this.max);
        int snapped = this.min + Math.round((clamped - this.min) / (float) this.step) * this.step;
        return Math.min(snapped, this.max);
    }
}
