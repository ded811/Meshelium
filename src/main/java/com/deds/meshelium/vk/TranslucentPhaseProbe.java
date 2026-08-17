/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Splits the translucent recording span into its phases, so the next
 * optimisation is aimed rather than guessed.
 *
 * <h2>Why</h2>
 * <p>{@code mesheliumTranslucent} is 0.571 ms of a 1.806 ms frame at render
 * distance 64: roughly a third of the frame, and 6.6 times the opaque
 * recording span for a layer that is a small fraction of the geometry. It
 * is also the one stage that does not move at all when occlusion culling is
 * switched off (0.571 against 0.564), so it is not about how much is
 * visible.</p>
 *
 * <p>It is already the product of one optimisation: the multi-workgroup
 * change took it from 1.94 ms to 0.63. What remains is unexplained, and
 * there are at least three candidates inside the method: a pre-pass that
 * scans every resident section looking for retained ones, a main loop that
 * allocates an AABB per visible section for the frustum test, and the
 * upload plus pass recording at the end. Guessing between them is exactly
 * how this project has wasted time before, so this measures instead.</p>
 *
 * <p>Property-gated and off by default, like the greedy probe. Timing three
 * spans per frame is cheap but not free, and nothing here should be on in a
 * player's game.</p>
 */
public final class TranslucentPhaseProbe {

    private static final AtomicLong TARGET_VIEWS = new AtomicLong();
    private static final AtomicLong PROLOGUE_REST = new AtomicLong();
    private static final LongAdder FRAMES = new LongAdder();
    private static final AtomicLong RETAINED_SCAN = new AtomicLong();
    private static final AtomicLong VISIBLE_LOOP = new AtomicLong();
    private static final AtomicLong RECORD = new AtomicLong();
    private static final LongAdder SECTIONS_SCANNED = new LongAdder();
    private static final LongAdder SECTIONS_DRAWN = new LongAdder();
    private static final LongAdder DRAWS = new LongAdder();

    private static final long REPORT_EVERY = 600L;

    private TranslucentPhaseProbe() {
    }

    /** {@code -Dmeshelium.probe.translucent=true}. */
    public static final boolean ARMED =
            Boolean.getBoolean("meshelium.probe.translucent");

    /** Fetching the render target and its colour, depth, atlas, lightmap views. */
    public static void targetViews(long nanos) {
        TARGET_VIEWS.addAndGet(nanos);
    }

    /** The rest of the prologue: snapshot, frustum, capacity, property read. */
    public static void prologueRest(long nanos) {
        PROLOGUE_REST.addAndGet(nanos);
    }

    public static void retainedScan(long nanos, int scanned) {
        RETAINED_SCAN.addAndGet(nanos);
        SECTIONS_SCANNED.add(scanned);
    }

    public static void visibleLoop(long nanos, int sectionsDrawn, int draws) {
        VISIBLE_LOOP.addAndGet(nanos);
        SECTIONS_DRAWN.add(sectionsDrawn);
        DRAWS.add(draws);
    }

    /** The transient upload plus the render-pass recording at the tail. */
    public static void record(long nanos) {
        RECORD.addAndGet(nanos);
        FRAMES.increment();
        if (FRAMES.sum() % REPORT_EVERY == 0) {
            com.deds.meshelium.fabric.MesheliumClient.LOGGER.info(report());
            // WINDOWED, not cumulative, and the difference is not cosmetic.
            // A cumulative average over every frame since world load is
            // dominated by the hundreds of thousands of cheap frames spent
            // waiting for worldgen to settle, while the figure it gets
            // compared against - the bench's stage median - comes from a
            // 600-frame window at full load. Averaging the two populations
            // together made the phases look like a third of the stage they
            // are inside, which is not a finding, it is a units error.
            reset();
        }
    }

    private static void reset() {
        TARGET_VIEWS.set(0);
        PROLOGUE_REST.set(0);
        RETAINED_SCAN.set(0);
        VISIBLE_LOOP.set(0);
        RECORD.set(0);
        SECTIONS_SCANNED.reset();
        SECTIONS_DRAWN.reset();
        DRAWS.reset();
        FRAMES.reset();
    }

    public static String report() {
        long frames = Math.max(1L, FRAMES.sum());
        double scan = RETAINED_SCAN.get() / 1.0e6 / frames;
        double loop = VISIBLE_LOOP.get() / 1.0e6 / frames;
        double rec = RECORD.get() / 1.0e6 / frames;
        double views = TARGET_VIEWS.get() / 1.0e6 / frames;
        double rest = PROLOGUE_REST.get() / 1.0e6 / frames;
        return String.format(
                "meshelium translucent phases over %d frames: target+views %.3f ms, "
                        + "prologue rest %.3f ms, retained scan %.3f ms (%d sections/frame), "
                        + "visible loop %.3f ms (%d drawn, %d draws/frame), "
                        + "upload+record %.3f ms. Total %.3f ms",
                frames, views, rest, scan, SECTIONS_SCANNED.sum() / frames,
                loop, SECTIONS_DRAWN.sum() / frames, DRAWS.sum() / frames,
                rec, views + rest + scan + loop + rec);
    }
}
