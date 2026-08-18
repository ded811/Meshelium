/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium;

import com.deds.meshelium.fabric.MesheliumClient;

import java.util.Arrays;

/**
 * Wave-12 CPU stage attribution. The rd-64 measurement (PERFORMANCE.md) is
 * the mandate: 7.24 ms frame, 3.0 ms GPU — the rest is CPU, and this class
 * says WHERE, per named render-thread stage, so the coordinator optimizes
 * measured hot spots instead of guessed ones. Pure JDK + slf4j — no
 * Vulkan/LWJGL imports, safe on every backend (the
 * {@link MesheliumBenchRecorder} discipline).
 *
 * <h2>The stages (all render thread; sources bytecode-cited in
 * docs/VANILLA-FRAME-PATH.md wave-12 notes)</h2>
 * <pre>
 * frame order:   extract → [render HEAD] → prepareChunkRenders →
 *                frame-graph execute (mesheliumOpaque, mesheliumTranslucent) →
 *                compileSections/upload → residencyPump → occlusionGraphUpdate
 *
 * 0 extract          LevelExtractor.extract(DeltaTracker, Camera, float) —
 *                    dirty-section scan over visibleSections + RenderRegionCache
 *                    snapshots + entity/block-entity/particle extraction.
 *                    INCLUDES stage 1 when it fires — never sum 0 and 1.
 * 1 applyFrustum     LevelExtractor.applyFrustum(Frustum) — clearVisibleSections
 *                    + SectionOcclusionGraph.addSectionsInFrustum (the octree
 *                    walk that REBUILDS visibleSections). CONDITIONAL: fires
 *                    only when consumeFrustumUpdate() or the camera rotation
 *                    crossed a 2° bucket (extract ip 256–295) — the per-frame
 *                    run count series is the still-vs-play discriminator.
 * 2 occlusionUpdate  SectionOcclusionGraph.update(CameraRenderState, int,
 *                    ChunkLoadingRenderState) at LevelRenderer.render ip 713 —
 *                    the render-thread BFS share (partial updates + chunk
 *                    load/empty set folding; FULL updates go async to
 *                    Util.backgroundExecutor and only their schedule cost
 *                    lands here — reported as such, never as the whole BFS).
 * 3 prepareChunks    LevelRenderer.prepareChunkRenders(Matrix4fc) — the
 *                    per-frame ChunkSectionsToRender build: visibleSections ×
 *                    layers draw-list construction + one ChunkSectionInfo UBO
 *                    write per visible section (the wave-12 skip candidate).
 * 4 mesheliumOpaque    TerrainDrawer.drawOpaque CPU recording span (the wave-5
 *                    breadcrumb's clock, now a per-frame series).
 * 5 mesheliumTransl    TerrainDrawer.drawTranslucent CPU recording span.
 * 6 residencyPump    MesheliumTerrainPump.afterVanillaTerrainUpload (staging→
 *                    arena copies, record uploads, retention sweeps).
 * </pre>
 * Stage values are CPU wall-nanos between {@code System.nanoTime()} pairs on
 * the render thread. They are NOT additive to a whole frame (stage 1 nests
 * inside 0; sleeping/vsync/GPU-wait time lives between stages) and are never
 * summed with the GPU pass times — same honesty rule as everywhere else.
 *
 * <h2>Gate + overhead argument</h2>
 * {@link #ARMED} is a {@code static final} resolved at class load:
 * {@code meshelium.cpustages} when present, else armed iff this is a bench run
 * ({@link MesheliumBenchRecorder#ARMED} — the bench gets stages for free). On
 * every normal run all call sites are {@code if (false)} after JIT — zero
 * steady-state cost, the {@code MesheliumBenchRecorder} pattern. When armed:
 * ≤ 7 nanoTime pairs + plain array stores per frame, ZERO allocation per
 * frame (the current-row array is static; capture arrays are allocated once
 * at {@link #armCapture}; the rolling 5 s means use fixed accumulators).
 * Always-on was considered and rejected: the two extra mixins on
 * backend-neutral vanilla classes must be provably inert on the GL path,
 * and "if (static final false)" is the only shape we can argue to be free
 * without measuring it first — measuring-first is this wave's whole point.
 *
 * <h2>Threading</h2>
 * All record calls run on the render thread. The bench thread arms via
 * {@code runOnClient} and reads rows only after observing the volatile
 * {@link #captureFilled} (single-writer piggyback publish — the
 * {@code MesheliumGpuTimers} capture pattern).
 */
public final class MesheliumCpuStages {

    /** Arm property; absent ⇒ armed only on bench runs. */
    public static final String PROPERTY = "meshelium.cpustages";

    /** True iff stage attribution records anything this session. */
    public static final boolean ARMED = resolveArmed();

    public static final int STAGE_EXTRACT = 0;
    public static final int STAGE_APPLY_FRUSTUM = 1;
    public static final int STAGE_OCCLUSION_UPDATE = 2;
    public static final int STAGE_PREPARE_CHUNKS = 3;
    public static final int STAGE_MESHELIUM_OPAQUE = 4;
    public static final int STAGE_MESHELIUM_TRANSLUCENT = 5;
    public static final int STAGE_RESIDENCY_PUMP = 6;
    // The attribution-gap wave (2026-08-18): the four windows the first
    // seven stages left dark. compileUpload = vanilla's compileSections
    // through uploadTerrainBuffersToGpu (inline compiles + staging drains
    // live here); encoderSubmit = VulkanCommandEncoder.submit(), whose tail
    // is the 2-submits-in-flight timeline-semaphore wait where a GPU-paced
    // CPU parks; levelRender = the whole LevelRenderer.render span;
    // renderFrame = the whole Minecraft.renderFrame span (frame delta minus
    // this = tick + input; renderFrame minus levelRender minus submit minus
    // extract ~= GUI + acquire + blit + present).
    public static final int STAGE_COMPILE_UPLOAD = 7;
    public static final int STAGE_ENCODER_SUBMIT = 8;
    public static final int STAGE_LEVEL_RENDER = 9;
    public static final int STAGE_RENDER_FRAME = 10;
    public static final int STAGES = 11;

    /** Stage names, index-aligned — the bench JSON + debug line labels. */
    public static final String[] NAMES = {
            "extract", "applyFrustum", "occlusionGraphUpdate", "prepareChunkRenders",
            "mesheliumOpaque", "mesheliumTranslucent", "residencyPump",
            "compileUpload", "encoderSubmit", "levelRender", "renderFrame"};

    /**
     * Executed section-compile tap entries (build threads; includes empty
     * results, excludes resorts structurally — they never reach the tap).
     * Cumulative; the per-frame capture stores deltas. Pure JDK, so the
     * terrain tap calling in keeps this class GL-path-safe.
     */
    private static final java.util.concurrent.atomic.LongAdder TAP_COMPILES =
            new java.util.concurrent.atomic.LongAdder();

    /** Build-thread hook: one executed compile tap (any result). */
    public static void noteTapCompile() {
        TAP_COMPILES.increment();
    }

    /** Cumulative executed tap compiles (bench counters block). */
    public static long tapCompiles() {
        return TAP_COMPILES.sum();
    }

    // Render thread only: the open frame's row (−1 = stage absent).
    private static final long[] current = new long[STAGES];
    private static int currentApplyRuns;
    private static int currentVisibleSections = -1;
    private static boolean frameOpen;
    private static long lastTapCompilesSample;

    // Rolling 5 s means (render thread only).
    private static final long[] accum = new long[STAGES];
    private static final int[] accumCount = new int[STAGES];
    private static int accumFrames;
    private static int accumApplyRuns;
    private static long lastLogNanos;

    // Bench capture: rows × STAGES nanos, plus per-frame ints.
    private static long[] captureRows = new long[0];
    private static int[] captureApplyRuns = new int[0];
    private static int[] captureVisibleSections = new int[0];
    private static int[] captureSectionCompiles = new int[0];
    private static volatile int captureFilled;
    private static volatile boolean capturing;

    static {
        Arrays.fill(current, -1);
    }

    private MesheliumCpuStages() {}

    private static boolean resolveArmed() {
        String p = System.getProperty(PROPERTY);
        if (p != null) {
            return Boolean.parseBoolean(p);
        }
        return MesheliumBenchRecorder.ARMED;
    }

    // ------------------------------------------------------------------
    // Render-thread hooks
    // ------------------------------------------------------------------

    /**
     * Frame boundary — called at {@code LevelRenderer.render} HEAD, the
     * SAME hook the bench recorder stamps its frame deltas from, so stage
     * rows tile frame deltas exactly (the 2026-08-18 alignment fix: the
     * old extract-HEAD boundary put every row 1-2 indices after its frame
     * delta and manufactured an "unattributed frame"). extract runs before
     * render, so its bracket lands deterministically in the PRIOR row.
     * Commits the PREVIOUS frame's row.
     */
    public static void beginFrame() {
        if (frameOpen) {
            commit();
        }
        Arrays.fill(current, -1);
        currentApplyRuns = 0;
        currentVisibleSections = -1;
        frameOpen = true;
    }

    /** Accumulate {@code nanos} into the open frame's {@code stage}. */
    public static void record(int stage, long nanos) {
        if (!frameOpen) {
            return; // no frame open (menus, world teardown) — drop honestly
        }
        long cur = current[stage];
        current[stage] = cur < 0 ? nanos : cur + nanos;
    }

    /** Stage-1 fired this frame (its duration goes through {@link #record}). */
    public static void noteApplyFrustumRun() {
        if (frameOpen) {
            currentApplyRuns++;
        }
    }

    /** visibleSections.size() at prepareChunkRenders time (scale context). */
    public static void noteVisibleSections(int size) {
        if (frameOpen) {
            currentVisibleSections = size;
        }
    }

    private static void commit() {
        // Bench rows first (exact per-frame series).
        if (capturing) {
            int filled = captureFilled;
            if ((filled + 1) * STAGES <= captureRows.length) {
                System.arraycopy(current, 0, captureRows, filled * STAGES, STAGES);
                captureApplyRuns[filled] = currentApplyRuns;
                captureVisibleSections[filled] = currentVisibleSections;
                // Executed-compile delta since the last committed row: the
                // build-storm series (worker-side, so a delta not a bracket).
                long taps = TAP_COMPILES.sum();
                captureSectionCompiles[filled] = (int) (taps - lastTapCompilesSample);
                lastTapCompilesSample = taps;
                captureFilled = filled + 1; // volatile publish AFTER the copies
            } else {
                capturing = false;
            }
        }
        // Rolling means for the debug line.
        for (int s = 0; s < STAGES; s++) {
            if (current[s] >= 0) {
                accum[s] += current[s];
                accumCount[s]++;
            }
        }
        accumFrames++;
        accumApplyRuns += currentApplyRuns;
        maybeLog();
    }

    /**
     * The wave-12 debug line: per-stage MEANS over the last window, µs,
     * once per 5 s — INFO under debugStats, DEBUG otherwise (the drawer
     * breadcrumb's convention). "absent" = the stage never ran in the
     * window (e.g. Meshelium passive ⇒ stages 4-6 absent).
     */
    private static void maybeLog() {
        long now = System.nanoTime();
        if (lastLogNanos == 0) {
            lastLogNanos = now;
            return;
        }
        if (now - lastLogNanos < 5_000_000_000L) {
            return;
        }
        StringBuilder sb = new StringBuilder(224);
        sb.append("meshelium cpu stages (means us over ").append(accumFrames).append(" frames): ");
        for (int s = 0; s < STAGES; s++) {
            if (s > 0) {
                sb.append(' ');
            }
            sb.append(NAMES[s]).append('=');
            sb.append(accumCount[s] == 0 ? "absent" : Long.toString(accum[s] / accumCount[s] / 1_000));
        }
        sb.append(" applyFrustumRuns=").append(accumApplyRuns);
        sb.append(" (render-thread nanoTime brackets; applyFrustum NESTS inside extract; ")
                .append("never sum stages with each other or with GPU pass times)");
        String line = sb.toString();
        if (MesheliumConfig.debugStatsEnabled()) {
            MesheliumClient.LOGGER.info(line);
        } else {
            MesheliumClient.LOGGER.debug(line);
        }
        Arrays.fill(accum, 0);
        Arrays.fill(accumCount, 0);
        accumFrames = 0;
        accumApplyRuns = 0;
        lastLogNanos = now;
    }

    // ------------------------------------------------------------------
    // Bench capture (client thread arms, render thread fills)
    // ------------------------------------------------------------------

    /** Arm capture of the next {@code rows} committed frame rows. */
    public static void armCapture(int rows) {
        captureRows = new long[rows * STAGES];
        captureApplyRuns = new int[rows];
        captureVisibleSections = new int[rows];
        captureSectionCompiles = new int[rows];
        lastTapCompilesSample = TAP_COMPILES.sum();
        captureFilled = 0;
        capturing = true;
    }

    public static void disarmCapture() {
        capturing = false;
    }

    /** Committed rows so far (each row = {@link #STAGES} longs). */
    public static int captureFilled() {
        return captureFilled;
    }

    /** Copy of the filled rows, flat row-major (rows × STAGES), nanos, −1 absent. */
    public static long[] captureSnapshot() {
        return Arrays.copyOf(captureRows, captureFilled * STAGES);
    }

    /** Per-row applyFrustum run counts (0/1 in practice). */
    public static int[] captureApplyRunsSnapshot() {
        return Arrays.copyOf(captureApplyRuns, captureFilled);
    }

    /** Per-row visibleSections.size() at prepare time (−1 = not sampled). */
    public static int[] captureVisibleSectionsSnapshot() {
        return Arrays.copyOf(captureVisibleSections, captureFilled);
    }

    /** Per-row executed-compile deltas (build-thread taps between commits). */
    public static int[] captureSectionCompilesSnapshot() {
        return Arrays.copyOf(captureSectionCompiles, captureFilled);
    }
}
