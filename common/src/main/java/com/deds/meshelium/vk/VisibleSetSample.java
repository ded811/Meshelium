/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import java.util.BitSet;

/**
 * NEXT (c), 2026-09-15: one folded visible set, published by the test-only
 * stamps readback ({@code meshelium.occlusion.diag.stampsReadback}).
 *
 * <p>This is what turns 97_18's superset leg from a SUM of counters into a
 * SET INCLUSION. A sum can hide the failure that matters: half-res
 * re-admits a handful of edge sections (by construction) while dropping one
 * hidden section, the two cancel, and the counter bound passes over a hole
 * that persists at a static pose. {@code S_full subset of S_flat} cannot.</p>
 *
 * <p>Bit i is set when the stamp word i equalled the frame stamp the raster
 * wrote that frame. The INDEX SPACE is the raster's own:
 * {@code mid * 256 + Sodium slot} on the Sodium host,
 * {@code regionId * 256 + compacted slot} standalone — so two samples are
 * comparable only while that mapping is unchanged, which the leg asserts by
 * counter (upload hook, mids released, commit total, recreates, growths,
 * instances live) rather than assuming.</p>
 *
 * <p>The four {@code forced*} counts are the plan's promoted diagnostic
 * (revision 4, objections 3/8): per raster, how often the WIDENED near
 * force fired against how often the UN-widened, UN-inflated test — the one
 * the full-res variant fires — would have. 97_18 asserts they are equal,
 * which is the measured form of "the near force and the inflation
 * contributed nothing to the printed re-admission". They are 0 when the
 * readback is off, and so is every other field of a null sample.
 *
 * @param frame the stats frame this fold describes
 * @param bits  the stamped slots of that frame
 * @param forcedRegionWide   region raster: widened force fired
 * @param forcedRegionPlain  region raster: the full-res test would have
 * @param forcedSectionWide  section raster: widened force fired
 * @param forcedSectionPlain section raster: the full-res test would have
 */
public record VisibleSetSample(long frame, BitSet bits,
        int forcedRegionWide, int forcedRegionPlain,
        int forcedSectionWide, int forcedSectionPlain) {

    /** Stamped slots in this fold; 0 means the fold found nothing. */
    public int size() {
        return bits.cardinality();
    }

    /** True when every slot of {@code other} is stamped here too. */
    public boolean contains(VisibleSetSample other) {
        BitSet missing = (BitSet) other.bits.clone();
        missing.andNot(bits);
        return missing.isEmpty();
    }

    /** The slots of {@code other} that are NOT stamped here (a fresh set). */
    public BitSet minus(VisibleSetSample other) {
        BitSet diff = (BitSet) bits.clone();
        diff.andNot(other.bits);
        return diff;
    }

    /** "region wide/plain, section wide/plain" for a leg's message. */
    public String forcedText() {
        return "region " + forcedRegionWide + "/" + forcedRegionPlain
                + " section " + forcedSectionWide + "/" + forcedSectionPlain;
    }

    /** True when neither raster's widened force fired where the full-res test would not. */
    public boolean forcedAgrees() {
        return forcedRegionWide == forcedRegionPlain
                && forcedSectionWide == forcedSectionPlain;
    }
}
