/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium;

import java.util.Arrays;

/**
 * Wave-9 CPU frame-time capture for the benchmark harness. Pure JDK — no
 * Vulkan/LWJGL imports, so it is safe on every backend and never breaks
 * the wave-1 "no Vulkan classes on the GL path" discipline.
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

    /** True iff {@code -Dmeshelium.bench=<scene>} was passed (JIT-erasable). */
    public static final boolean ARMED = System.getProperty(PROPERTY) != null;

    private static long[] frameNanos = new long[0];
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
        if (!capturing) {
            lastFrameNanoTime = 0;
            return;
        }
        long now = System.nanoTime();
        if (lastFrameNanoTime != 0) {
            int i = filled;
            if (i < frameNanos.length) {
                frameNanos[i] = now - lastFrameNanoTime;
                filled = i + 1; // volatile publish AFTER the element write
                if (i + 1 == frameNanos.length) {
                    capturing = false;
                }
            }
        }
        lastFrameNanoTime = now;
    }

    /** Arm capture of the next {@code frames} frame deltas (client thread). */
    public static void arm(int frames) {
        frameNanos = new long[frames];
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
}
