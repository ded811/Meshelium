/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium;

import net.minecraft.client.Minecraft;

import java.util.Arrays;

/**
 * Wave-9 CPU frame-time capture for the benchmark harness. No
 * Vulkan/LWJGL imports, so it is safe on every backend and never breaks
 * the wave-1 "no Vulkan classes on the GL path" discipline. (It names
 * {@code Minecraft} for the second series below - a backend-neutral
 * class that is loaded in every session by the mixin whose hook calls
 * this one, so nothing new reaches the GL path.)
 *
 * <h2>What it measures</h2>
 * Deltas of {@code System.nanoTime()} between consecutive
 * {@code LevelRenderer.render} entries (the wave-4 frame-state mixin's
 * HEAD, which fires once per rendered in-world frame) — i.e. WHOLE client
 * frame times, backend included, Meshelium or vanilla alike. That is the
 * meshelium-vs-vanilla comparison series; the drawer's CPU draw-path micros
 * and {@code MesheliumGpuTimers}' GPU pass times remain separate figures
 * (never summed with this or each other).
 *
 * <p>SECOND SERIES (pre21, the far-armed bench): vanilla's own
 * {@code Minecraft.getFrameTimeNs()} sampled at the same instants - the
 * frame's CPU span, which EXCLUDES the present and the framerate-limiter
 * sleep. Needed because that bench PINS the framerate limit to emulate
 * the owner's machine, and under a pin the wall-clock delta is the pin on
 * every frame the machine keeps up with. The two series are never summed
 * and never averaged together; see {@link #snapshotCpu()}.</p>
 *
 * <h2>Cost discipline</h2>
 * {@link #ARMED} is a {@code static final} resolved from the
 * {@code meshelium.bench} property at class load: on every non-bench run the
 * mixin's call site is {@code if (false)} after JIT — zero steady-state
 * cost, and this class only ever loads on runs where the mixin is applied
 * anyway.
 *
 * <h2>Threading</h2>
 * {@link #onRenderFrame} runs on the render thread; the benchmark arms and
 * polls from the client gametest via {@code runOnClient}/{@code waitFor}
 * (same thread) and reads the finished array from its own thread AFTER
 * observing the volatile {@link #filled} count — the volatile write in
 * {@code onRenderFrame} publishes the array contents (single-writer
 * piggyback, the drawer counters' existing pattern).
 */
public final class MesheliumBenchRecorder {

    /** Scene-name property; presence = this run is a benchmark run. */
    public static final String PROPERTY = "meshelium.bench";

    /**
     * The FAR-ARMED bench's arm ({@code -Pmeshelium.farbench}).
     *
     * <p>{@code MesheliumFarFieldBenchTest} needs exactly this clock -
     * whole client frames, one sample per rendered frame, preallocated -
     * and could not use it, because {@link #ARMED} keyed on the scene
     * property and {@code -Pmeshelium.bench} SWAPS the gametest
     * entrypoint list to the near-field benchmark alone. The far-armed
     * bench therefore arms the same recorder through its own property
     * rather than growing a second copy of a frame clock, which is how
     * two frame-time series that disagree get published.</p>
     */
    public static final String FARBENCH_PROPERTY = "meshelium.test.farbench";

    /**
     * True iff {@code -Dmeshelium.bench=<scene>} or
     * {@code -Dmeshelium.test.farbench=true} was passed (JIT-erasable
     * either way: both are resolved once, at class load).
     */
    public static final boolean ARMED = System.getProperty(PROPERTY) != null
            || Boolean.getBoolean(FARBENCH_PROPERTY);

    private static long[] frameNanos = new long[0];
    /**
     * The SECOND series: vanilla's own {@code Minecraft.getFrameTimeNs()}
     * at the same instants, i.e. the frame's CPU span with the present
     * and the framerate-limiter sleep EXCLUDED (javap, 26.2: the field is
     * assigned once per frame in {@code renderFrame} at ip 625-631,
     * before the buffer swap).
     *
     * <p>Two series because the far-armed bench pins the framerate limit
     * to emulate the owner's 200 fps machine, and under a pin the
     * wall-clock delta is 5 ms by construction on every frame the client
     * can keep up with - true, and useless as a denominator. The CPU span
     * is what the far field's budget rule itself reads, so "the far field
     * took 10% of the frame" can be evaluated against the same number the
     * rule uses. Kept as raw samples, never summed with the other series.</p>
     *
     * <p>One frame LAGGED, deliberately unfixed: the hook is at
     * {@code LevelRenderer.render} HEAD and the field is written at the
     * end of the enclosing {@code renderFrame}, so element {@code i}
     * holds the CPU span of the frame that the delta at {@code i} also
     * spans. The two series describe the same frame, which is the
     * alignment that matters.</p>
     */
    private static long[] frameCpuNanos = new long[0];
    /**
     * Per-frame run counts of the Sodium list path: runs pushed and runs
     * the CPU frustum test rejected, summed over the frame's opaque passes
     * ({@code SodiumTerrainDrawer.reportFrustum}). Same length and order
     * as {@link #frameNanos}; zeros on every other host and in every mode
     * that does not run the CPU test.
     */
    private static int[] frameRuns = new int[0];
    private static int[] frameRunsRejected = new int[0];
    private static int pendingRuns;
    private static int pendingRunsRejected;

    /**
     * {@code -Dmeshelium.bench.jitter=true}: measurement-only. Every
     * rendered frame nudges the player's yaw by {@link #JITTER_DEGREES}
     * on alternate frames, so the camera is never "still" for a host that
     * keys its culling on that (Sodium reads its render list from a
     * different tree on a frame whose rotation changed; recon 2026-09-13)
     * while the picture stays the same to a thirtieth of a pixel at 1080p
     * (one pixel is about 0.036 degrees at a 70-degree FOV). Separates
     * "the view is moving" from "the view points somewhere else", which a
     * spin cannot.
     */
    public static final boolean JITTER = Boolean.getBoolean("meshelium.bench.jitter");
    static final float JITTER_DEGREES = 0.001f;
    private static float jitterBaseYaw = Float.NaN;
    private static long jitterFrames;
    private static volatile int filled;
    private static volatile boolean capturing;
    private static long lastFrameNanoTime;

    private MesheliumBenchRecorder() {}

    /** The scene name (null off bench runs). */
    public static String sceneName() {
        return System.getProperty(PROPERTY);
    }

    /**
     * Render-thread hook (frame-state mixin HEAD): one call per rendered
     * in-world frame. The first frame after arming only seeds the clock —
     * a capture of N deltas spans N+1 frames.
     */
    public static void onRenderFrame() {
        if (JITTER) {
            jitter();
        }
        // The passes of the frame that just ended accumulated into these;
        // the delta recorded below spans that same frame.
        int runs = pendingRuns;
        int rejected = pendingRunsRejected;
        pendingRuns = 0;
        pendingRunsRejected = 0;
        if (!capturing) {
            lastFrameNanoTime = 0;
            return;
        }
        long now = System.nanoTime();
        if (lastFrameNanoTime != 0) {
            int i = filled;
            if (i < frameNanos.length) {
                frameNanos[i] = now - lastFrameNanoTime;
                frameCpuNanos[i] = cpuFrameNanos();
                frameRuns[i] = runs;
                frameRunsRejected[i] = rejected;
                filled = i + 1; // volatile publish AFTER the element writes
                if (i + 1 == frameNanos.length) {
                    capturing = false;
                }
            }
        }
        lastFrameNanoTime = now;
    }

    /**
     * Vanilla's own CPU frame span, or 0 before the first measured frame.
     * One static getter and one field read, on the render thread, only
     * while capturing.
     */
    private static long cpuFrameNanos() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? 0L : minecraft.getFrameTimeNs();
    }

    /**
     * The yaw nudge (see {@link #JITTER}). The base is re-read whenever the
     * player's yaw is more than a nudge away from it, so the bench's own
     * yaw resets between legs are honoured; both the current and the
     * previous-tick yaw are set so the interpolated camera follows exactly.
     */
    private static void jitter() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        float yaw = minecraft.player.getYRot();
        if (Float.isNaN(jitterBaseYaw) || Math.abs(yaw - jitterBaseYaw) > 2.0f * JITTER_DEGREES) {
            jitterBaseYaw = yaw;
        }
        jitterFrames++;
        float nudged = jitterBaseYaw + ((jitterFrames & 1L) == 0L ? 0.0f : JITTER_DEGREES);
        minecraft.player.setYRot(nudged);
        minecraft.player.yRotO = nudged;
    }

    /** Arm capture of the next {@code frames} frame deltas (client thread). */
    public static void arm(int frames) {
        frameNanos = new long[frames];
        frameCpuNanos = new long[frames];
        frameRuns = new int[frames];
        frameRunsRejected = new int[frames];
        filled = 0;
        lastFrameNanoTime = 0;
        capturing = true;
    }

    public static void disarm() {
        capturing = false;
    }

    /** Frame deltas captured so far. */
    public static int filled() {
        return filled;
    }

    /** Copy of the captured deltas (nanoseconds), length {@link #filled()}. */
    public static long[] snapshot() {
        return Arrays.copyOf(frameNanos, filled);
    }

    /**
     * Copy of the matching CPU frame spans (nanoseconds), same length and
     * same order as {@link #snapshot()}. See {@link #frameCpuNanos} for
     * why there are two series and why they are never summed.
     */
    public static long[] snapshotCpu() {
        return Arrays.copyOf(frameCpuNanos, filled);
    }

    /**
     * Render thread, once per opaque pass of the Sodium list path:
     * accumulates into the frame in progress (see {@link #frameRuns}).
     */
    public static void addRuns(int runs, int rejected) {
        pendingRuns += runs;
        pendingRunsRejected += rejected;
    }

    /** Runs pushed per captured frame, same length and order as {@link #snapshot()}. */
    public static long[] snapshotRuns() {
        return widen(frameRuns, filled);
    }

    /** Runs the CPU frustum test rejected per captured frame; see {@link #snapshotRuns()}. */
    public static long[] snapshotRunsRejected() {
        return widen(frameRunsRejected, filled);
    }

    private static long[] widen(int[] values, int n) {
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = values[i];
        }
        return out;
    }
}
