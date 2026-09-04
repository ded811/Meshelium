/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.gametest.client;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumBenchRecorder;
import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.farfield.FarField;
import com.deds.meshelium.farfield.FarFieldConfig;
import com.deds.meshelium.farfield.extract.ExtractDispatch;
import com.deds.meshelium.terrain.host.TerrainResidency;
import com.deds.meshelium.vk.TerrainDrawer;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.FramerateLimitTracker;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

/**
 * The FAR-ARMED frame-time bench: Phase 1 of
 * {@code docs/FARFIELD-PERF-BRIEF.md}, and the instrument every other
 * phase of that plan is gated on.
 *
 * <h2>Why it exists</h2>
 * <p>The owner's report is a frame-rate report - <i>"saving the chunks to
 * lod causes HUGE fps drops. this drops 200 to 60 while its going on"</i> -
 * and this project could not measure it. {@code -Pmeshelium.bench=<scene>}
 * arms the near renderer and NOT the far field (the harness gap recorded
 * under "AFTER THE REBUILD: the open ledger" in
 * {@code docs/FARFIELD-WAVES.md}), and its camera is pinned, so it never
 * loads a chunk and never saves one. Every fps number this project has
 * published therefore describes a session in which the far field did
 * nothing. There is no before-number for the defect the whole pre21
 * performance plan exists to remove, and a fix measured against no
 * baseline is a fix nobody can defend.</p>
 *
 * <h2>What it measures, and in what unit</h2>
 * <p>Per-FRAME times, from the same render-thread hook the near-field
 * benchmark uses ({@code MesheliumBenchRecorder}, one sample per
 * {@code LevelRenderer.render} entry - a preallocated {@code long[]}, no
 * per-frame allocation). Per-TICK sampling, which is what the far
 * field's other diagnostics do, is 20 Hz and cannot see a frame at all,
 * let alone a p99.</p>
 *
 * <p>Frame TIME in absolute milliseconds is the primary number
 * everywhere, and the fraction is DERIVED from it and labelled with the
 * frame it was divided by. The brief's target is a fraction - <b>the far
 * field may cost 10% of a frame while saving, capped at 2 ms</b> - but a
 * fraction is only meaningful against a stated frame, and the first
 * shakedown proved how badly it can mislead: at the harness's own 2,731
 * fps the same 0.75 ms of far-field work reads as 205% of a frame, while
 * on the owner's 200 fps machine it is 15%. Hence the pin
 * ({@link #FPS_CAP}) and hence two frame series - the wall clock the
 * player feels, and vanilla's own CPU span, which is what the budget
 * rule itself reads.</p>
 *
 * <h2>The three scenarios</h2>
 * <ol>
 *   <li><b>baseline-still</b> - stand on terrain the far field has
 *       already saved, with the save queue drained. The floor: whatever
 *       this costs, the far field is not the reason.</li>
 *   <li><b>travel</b> - fly a straight corridor over ground that has
 *       never been loaded this session, so vanilla streams chunks
 *       continuously and the far field saves continuously. The owner's
 *       "constant saves".</li>
 *   <li><b>teleport</b> - jump to fresh ground and stand there, three
 *       times, to three places that share no chunk. Recorded as THREE
 *       records per jump: the jump itself (the brief predicts one
 *       ~25-30 ms frame at the view-centre move and a ~6 ms frame at the
 *       next pump), the stand while vanilla is still streaming, and the
 *       idle stand after streaming stops - which is where the idle boost
 *       arms and where the brief puts the owner's 200 to 60.</li>
 * </ol>
 *
 * <h2>How the ground is guaranteed fresh</h2>
 * <p>Three LANES, {@value #LANE_SPACING_BLOCKS} blocks apart along X -
 * one per scenario - and hops within the teleport lane at least one
 * whole loaded window plus a margin apart. The client keeps chunks out
 * to {@code renderDistance + 3}, so two poses share no chunk once they
 * are {@code 2 * (rd + 3) * 16} blocks apart: 608 at rd 16. Both
 * separations are ASSERTED at run time against the run's actual render
 * distance rather than asserted in prose ({@link #assertGeometryFresh}),
 * because the whole travel and teleport story is worthless over ground
 * the session has already paid for.</p>
 *
 * <h2>The A/B, and why it is two runs</h2>
 * <p>{@code -Dmeshelium.test.farbench.far=false} runs the identical
 * scenarios with the far field DISARMED. The far field's cost is the
 * delta between two runs of the same seed, back to back - the standing
 * rule of this project's benchmarking is that only interleaved
 * same-session pairs are trustworthy and an archived number is context,
 * never a baseline.</p>
 *
 * <h2>Vacuity defence</h2>
 * <p>This harness has gone green having measured nothing before. So:
 * every scenario must record at least {@value #MIN_FRAMES} frames (a
 * jump spike, which is deliberately short, must record at least
 * {@value #MIN_SPIKE_FRAMES}); the travel leg must have crossed real
 * chunk columns, measured from the camera's own position, and - when the
 * far field is armed - must have walked columns while it did; and a
 * far-ARMED run whose far field never extracted a column, or whose
 * per-frame distributions came back empty, fails outright.
 *
 * <p>The frame floor is three cases, and only one of them fails on a
 * slow client. Zero frames FAILS - the hook never fired. A short record
 * that the run can attribute to something outside this renderer is
 * REPORTED, marked unusable and continues. A short record with no
 * external explanation FAILS, because that is a real frame-rate finding.
 * The verdict keys on {@link #externalWaitShare}, measured from the two
 * frame series; the integrated server's tick DRIFT is reported and
 * deliberately never gated on, because it turned out to say nothing
 * about load under this harness (see {@link ServerLag}). None of those is a performance assertion - this is an
 * instrument, not a gate. The one optional gate is
 * {@code -Dmeshelium.test.farbench.maxP99Ms=<n>}, default off.</p>
 *
 * <h2>Invocation</h2>
 * <pre>
 * ./gradlew runClientGameTest -Pmeshelium.backend=vulkan \
 *     -Pmeshelium.terrain -Pmeshelium.farbench --offline
 * </pre>
 * <p>{@code -Pmeshelium.farbench} runs this class ALONE (it swaps the
 * gametest entrypoint list, the same mechanism {@code -Pmeshelium.bench}
 * uses), because a measurement should not be preceded by twenty minutes
 * of other tests warming the machine, and because the pair of runs an
 * A/B needs is then minutes rather than an hour. Add
 * {@code -Pmeshelium.farbench.suite} to run the ordinary suite with this
 * class appended instead.</p>
 */
public final class MesheliumFarFieldBenchTest implements FabricClientGameTest {

    /** {@code -Pmeshelium.farbench}. Without it this class does nothing. */
    private static final boolean ARMED = Boolean.getBoolean("meshelium.test.farbench");

    /**
     * The A/B switch. True (default) arms the far field around the whole
     * run; {@code -Dmeshelium.test.farbench.far=false} runs the identical
     * scenarios with it off, and the difference between the two runs is
     * the far field's price.
     */
    private static final boolean FAR = Boolean.parseBoolean(
            System.getProperty("meshelium.test.farbench.far", "true"));

    /**
     * Near render distance. The owner plays at 32; the harness default is
     * 16 because the client gametest JVM is small (2 GB in
     * gradle.properties) and a NORMAL noise world at rd 32 spends the run
     * in worldgen rather than in the scenarios. The cost being measured
     * is per-COLUMN and the column count scales as the square of the
     * radius, so rd 16 understates the absolute milliseconds and
     * preserves the shape; the fractions this report is written in are
     * the part that transfers.
     */
    private static final int RD = Integer.getInteger("meshelium.test.farbench.rd", 16);

    /**
     * Layer-1 (LOD) radius in chunks. 32 is what the visual diagnostic's
     * scenes use; the owner runs 120. The radius decides how much ground
     * the far field WANTS, not how much it can save in a session, so it
     * changes the backlog's depth rather than the per-frame price.
     */
    private static final int LOD = Integer.getInteger("meshelium.test.farbench.lod", 32);

    /**
     * Fixed by default so two arms of an A/B see the same ground.
     * {@code -Pmeshelium.seed=<n>} moves the whole run (it arrives as
     * {@code meshelium.bench.seed});
     * {@code -Dmeshelium.test.farbench.seed=<n>} moves this bench alone.
     */
    private static final String SEED = System.getProperty("meshelium.test.farbench.seed",
            System.getProperty("meshelium.bench.seed", "4242"));

    /** Which scenarios to run, comma separated. */
    private static final String SCENARIOS = System.getProperty(
            "meshelium.test.farbench.scenarios", "baseline-still,travel,teleport");

    /**
     * Optional gate, default OFF: fail the run when a SAVING scenario's
     * p99 frame time exceeds this many milliseconds. Deliberately not a
     * default: the whole point of Phase 1 is to learn what the numbers
     * are, and a gate that fires before anybody knows the distribution
     * only teaches the next person to raise it.
     */
    private static final double MAX_P99_MS = Double.parseDouble(
            System.getProperty("meshelium.test.farbench.maxP99Ms", "0"));

    /**
     * Blocks per TICK along the travel corridor - 1.0 is 20 m/s, about
     * what a creative flight covers and close enough to the ~21 m/s the
     * owner's own travel implies.
     *
     * <p>The corridor is flown by teleporting the camera once per tick
     * rather than by holding a movement key. Movement keys go through
     * physics and the flight toggle and vary run to run; a per-tick
     * absolute teleport is deterministic, is the same in both arms of
     * the A/B, and crosses a chunk boundary every 16 ticks exactly as a
     * flight does. Its one honest cost is one server command per tick,
     * paid identically by both arms.</p>
     */
    private static final double SPEED_BLOCKS_PER_TICK = Double.parseDouble(
            System.getProperty("meshelium.test.farbench.speed", "1.0"));

    /** Seconds of the standing-still floor. */
    private static final int STILL_SECONDS =
            Integer.getInteger("meshelium.test.farbench.stillSeconds", 5);
    /** Seconds of measured travel (after an unmeasured warm-up leg). */
    private static final int TRAVEL_SECONDS =
            Integer.getInteger("meshelium.test.farbench.travelSeconds", 20);
    /** Seconds recorded from the teleport itself: the spike window. */
    private static final int JUMP_SECONDS =
            Integer.getInteger("meshelium.test.farbench.jumpSeconds", 4);
    /** Seconds of standing after the jump, while vanilla is still streaming. */
    private static final int STAND_SECONDS =
            Integer.getInteger("meshelium.test.farbench.standSeconds", 15);
    /** Seconds of standing after streaming stops: the idle-boost window. */
    private static final int IDLE_SECONDS =
            Integer.getInteger("meshelium.test.farbench.idleSeconds", 6);
    /** How many teleports, each to fresh ground. */
    private static final int TELEPORTS =
            Integer.getInteger("meshelium.test.farbench.teleports", 3);

    /**
     * THE PINNED FRAME RATE, and the most consequential setting here.
     *
     * <p>The first shakedown ran with the limit at
     * {@code Options.UNLIMITED_FRAMERATE_CUTOFF} and the harness produced
     * 2,731 fps - a 0.366 ms frame. The owner's machine runs 200 fps, a
     * 5 ms frame. The brief's whole target is a FRACTION of a frame, so
     * measuring the fraction against a 0.366 ms frame answers a question
     * nobody asked: the same 0.75 ms of far-field work is 15% of the
     * owner's frame and 205% of the harness's. Every fraction the first
     * run printed was off by more than an order of magnitude.</p>
     *
     * <p>So the limit is PINNED to 200 by default, deliberately
     * emulating the owner's machine rather than reporting this one's
     * capability. Verified by bytecode rather than assumed:
     * {@code Minecraft.runTick} reads
     * {@code GameRenderState.framerateLimit} and calls
     * {@code FramerateLimiter.limitDisplayFPS} only when it is
     * {@code < 260} (javap, ip 752-767), and
     * {@code Options.UNLIMITED_FRAMERATE_CUTOFF} is exactly 260 - so 260
     * means unlimited and 200 genuinely paces the client.</p>
     *
     * <p><b>What a pin does to the numbers, stated once.</b> Under it the
     * wall-clock frame time is 5 ms on every frame the machine can keep
     * up with, so p50 stops being interesting and becomes a check that
     * the pin held; the signal moves entirely into the frames that
     * OVERRUN the pin, which is exactly the owner's complaint (5 ms
     * frames becoming 16). The absolute millisecond figures stay primary
     * everywhere, and the second frame series - vanilla's own CPU span,
     * which excludes the limiter sleep - is what the fractions are also
     * computed against, because that is the number the budget rule reads.
     * A pin also changes what the far field DOES (the idle boost reads
     * frame time), so the honest reading is a pinned run beside an
     * unlimited one: {@code -Dmeshelium.test.farbench.fpsCap=260}.</p>
     */
    private static final int FPS_CAP =
            Integer.getInteger("meshelium.test.farbench.fpsCap", 200);

    /**
     * Frames per second the capture buffer is sized for.
     *
     * <p>The first shakedown sized it per TICK and truncated
     * baseline-still at 6,500 frames because the harness reached 2,731
     * fps. Sizing from seconds and a frame rate is the unit that
     * actually bounds it; 3,000 is comfortably above the fastest window
     * that run produced, and a truncated record still says so in the
     * log.</p>
     */
    private static final int FPS_CEILING =
            Integer.getInteger("meshelium.test.farbench.fpsCeiling", 3000);

    /**
     * Seconds allowed for the far field to finish saving spawn before
     * the standing-still FLOOR is measured.
     *
     * <p>Two minutes was not enough on the first shakedown - the wait
     * timed out and baseline-still was measured over a far field that was
     * still working, which is why its record showed 40 walks and four
     * idle boosts in a five-second window that was supposed to have
     * none.</p>
     */
    private static final int QUIET_SECONDS =
            Integer.getInteger("meshelium.test.farbench.quietSeconds", 240);

    /**
     * How often a measured window asks the server how far behind it is,
     * in ticks. Five is 250 ms - fine enough to attribute frames to a
     * saturation episode, coarse enough that the probe itself is not a
     * measurement artefact.
     */
    private static final int SERVER_SAMPLE_TICKS = 5;

    /**
     * Milliseconds past its own tick deadline before the integrated
     * server counts as BEHIND for this bench's purposes.
     *
     * <p>Deliberately far below vanilla's own alarm. The "Can't keep up!"
     * warning fires at {@code OVERLOADED_THRESHOLD_NANOS + 20 * nanosPerTick}
     * (javap: the constant is {@code 20 * NANOSECONDS_PER_SECOND / 20},
     * i.e. one second, so the warning is a TWO second lag) - and a server
     * only 100 ms behind is already pacing the client at one frame per
     * tick, which is the thing this bench must not mistake for its own
     * cost.</p>
     */
    private static final int SERVER_BEHIND_MS =
            Integer.getInteger("meshelium.test.farbench.serverBehindMs", 100);

    /**
     * Smoothed milliseconds per server tick past which the server counts
     * as BUSY. 50 ms is the whole tick budget, so 25 is half of it.
     *
     * <p>This replaces a lag threshold that did not work. See
     * {@link #waitForServerCaughtUp} and {@link ServerLag} for the
     * measurement that retired it.</p>
     */
    private static final int SERVER_BUSY_TICK_MS =
            Integer.getInteger("meshelium.test.farbench.serverBusyTickMs", 25);

    /**
     * Share of a window's wall time spent neither on the render thread
     * nor inside the framerate pin, past which the record is UNUSABLE.
     *
     * <p>The measure is {@code wall - max(cpu, pinPeriod)} summed over
     * the window, so the limiter's own sleep - which is the whole point
     * of a pinned rate - is not counted as a stall. What is left is the
     * frame time that went neither into our work nor into the pin, and
     * on the Phase 3 run's {@code teleport0-stand} that was 90% of a
     * 49.96 ms frame while the render thread used 1.5 ms of it.</p>
     */
    private static final double UNUSABLE_WAIT_SHARE = Double.parseDouble(
            System.getProperty("meshelium.test.farbench.unusableWaitShare", "0.5"));

    /**
     * Generate the teleport destinations BEFORE the far field is armed,
     * so the measured teleports land on ground the server already has.
     * See {@link #prewarmTeleportDestinations} for what this costs in
     * fidelity - it is not free and it is not hidden.
     */
    private static final boolean PREWARM = Boolean.parseBoolean(
            System.getProperty("meshelium.test.farbench.prewarm", "true"));

    /** Seconds to wait for the server to catch up before a window opens. */
    private static final int SERVER_CATCHUP_SECONDS =
            Integer.getInteger("meshelium.test.farbench.serverCatchupSeconds", 30);

    /**
     * Ticks between the corridor's teleports, and blocks per teleport, so
     * the speed is unchanged and the number of SERVER ROUND TRIPS is
     * divided by this.
     *
     * <p>The reason is the harness, and it is measured rather than
     * guessed. The client gametest framework runs the client, the server
     * and the test thread in lockstep through a four-phase
     * {@code java.util.concurrent.Phaser} (javap on the framework's
     * {@code ThreadingImpl}: {@code PHASE_TICK}, {@code PHASE_SERVER_TASKS},
     * {@code PHASE_CLIENT_TASKS}, {@code PHASE_TEST}, one semaphore per
     * side), and {@code TestServerContext.runCommand} is
     * {@code runOnServer} verbatim, which is
     * {@code ThreadingImpl.runTaskOnOtherThread}: it RELEASES the server
     * semaphore and then ACQUIRES the test one, i.e. the test thread
     * blocks until the server side has run the task. A teleport every
     * tick therefore puts a synchronous cross-thread round trip into
     * every cycle of that lockstep, and when the server side is busy the
     * cycle - which is also the client's frame - waits for it.
     *
     * <p>Four ticks and four blocks is still 20 m/s and still crosses a
     * chunk boundary every sixteen blocks; it just asks the server four
     * times less often. {@link #measure} now TIMES the per-tick action
     * from the test thread and every record carries the result
     * ({@code harnessPerTickMeanMs}), so the next run measures this
     * rather than arguing about it.</p>
     */
    private static final int TRAVEL_STEP_TICKS =
            Integer.getInteger("meshelium.test.farbench.travelStepTicks", 4);

    /** Unmeasured warm-up before every measured window, in ticks. */
    private static final int WARMUP_TICKS = 60;
    /** Unmeasured warm-up travel, in ticks, before the measured corridor. */
    private static final int TRAVEL_WARMUP_TICKS = 100;

    /** Every long record must hold at least this many frames. */
    private static final int MIN_FRAMES = 200;
    /** A jump spike is deliberately short; this is its own floor. */
    private static final int MIN_SPIKE_FRAMES = 40;
    /**
     * Chunk columns the travel corridor must actually cross, measured
     * from the player's own position rather than from a counter.
     *
     * <p>This is the vacuity check that cannot be wrong about its own
     * signal: if the camera did not move, nothing streamed, whatever any
     * counter says. 400 blocks of corridor is 25 crossings, so 16 is a
     * floor with room for a shortened run.</p>
     */
    private static final int MIN_TRAVEL_CHUNKS = Integer.getInteger(
            "meshelium.test.farbench.minTravelChunks", 16);

    /**
     * Columns the far field must have walked over the travel leg, when
     * it is armed.
     *
     * <p>Replaces the first shakedown's {@code encodedSections} floor,
     * which read a flat ZERO through a leg that had demonstrably streamed
     * and saved - 1,964 columns walked, 1,726 slice overruns, 75 frames
     * over 16.7 ms. The gate was failing the run on a signal that does
     * not move here, not on the scenario. Walked columns is both a
     * counter this bench has SEEN move and the literal meaning of "saving
     * happened", so it is the one the gate stands on; the near-field
     * counters are still printed, before and after, so the next run
     * diagnoses them instead of only tripping over them.</p>
     */
    private static final int MIN_TRAVEL_EXTRACTS = Integer.getInteger(
            "meshelium.test.farbench.minTravelExtracts", 200);

    /** Lane separation along X, one lane per scenario. */
    private static final int LANE_SPACING_BLOCKS = 8000;
    /** Camera height: above the terrain in this seed, below the build limit. */
    private static final double TRAVEL_Y = 200.0;
    /** Facing +Z, the direction of travel (the visual test's SOUTH). */
    private static final String YAW = "0";
    /** A shallow down-pitch: terrain across the frame, not sky. */
    private static final String PITCH = "5";

    private static final String WORLD_NAME =
            System.getProperty("meshelium.test.farbench.world", "meshelium-farbench");

    private static final int RD_TIMEOUT_TICKS = 1200;
    private static final int DRAW_TIMEOUT_TICKS = 1200;

    /** Every log line this class emits starts with this. */
    private static final String TAG = "FARBENCH";

    /** The brief's target, carried into the report so it can be checked. */
    private static final double TARGET_FRAME_FRACTION = 0.10;
    /** The brief's hard cap on the far field's share of a frame. */
    private static final double TARGET_HARD_CAP_MS = 2.0;

    // ------------------------------------------------------------------
    // One measured window
    // ------------------------------------------------------------------

    /**
     * One measured window and everything true about it.
     *
     * @param name     the scenario name, as logged and as keyed in the JSON
     * @param spike    a deliberately short window (a teleport jump), held
     *                 to {@link #MIN_SPIKE_FRAMES} rather than
     *                 {@link #MIN_FRAMES} and excluded from the optional
     *                 p99 gate, because its value is its MAXIMUM
     * @param saving   the far field is expected to be saving through this
     *                 window (so the optional gate applies to it)
     * @param frames   per-frame wall-clock nanosecond deltas, in order
     * @param cpuFrames vanilla's own CPU frame spans at the same
     *                 instants, which EXCLUDE the present and the
     *                 framerate-limiter sleep - the denominator the far
     *                 field's budget rule itself reads, and the only one
     *                 that still means something under a pinned rate
     * @param wallNanos wall clock the window spanned
     * @param harness  what the test harness itself cost this window, and
     *                 what the game's framerate limiter says it was doing
     * @param lag      how far the integrated server fell behind its own
     *                 tick deadline while this window was measured - the
     *                 difference between "the frame was slow" and "the
     *                 frame was slow BECAUSE OF US"
     * @param totals   counter DELTAS across the window
     * @param gauges   gauge values AT THE END of the window (a gauge
     *                 differenced is a lie; the visual test learned this
     *                 one the hard way)
     * @param extract  per-column walk cost distribution over the window
     * @param slice    per-frame far-field game-thread spend
     * @param overrun  per-frame spend BEYOND the slice's own ceiling
     * @param budget   the ceiling the budget rule actually chose
     */
    private record Window(String name, boolean spike, boolean saving,
            long[] frames, long[] cpuFrames, long wallNanos, ServerLag lag,
            Harness harness,
            Map<String, Long> totals, Map<String, Long> gauges,
            ExtractDispatch.PerfStats extract, ExtractDispatch.PerfStats slice,
            ExtractDispatch.PerfStats overrun, ExtractDispatch.PerfStats budget) {
    }

    /** Seconds spent generating the teleport destinations; into the report. */
    private static int prewarmSeconds;

    /**
     * What the framerate limit actually resolved to. STATIC because the
     * per-frame wait attribution needs it: waiting up to the pin period
     * is the pin doing its job, and only the wait BEYOND it is a stall.
     */
    private static int framerateLimitEffective;

    /**
     * How far the integrated server fell behind its own tick deadline
     * across one measured window.
     *
     * <p><b>REPORTED, NEVER GATED ON - and the measurement that decided
     * that is worth keeping.</b> The phase-3 final run put
     * {@code behindMaxNanos} at 603 ms on nine records that were
     * perfectly healthy (5.00 ms frames, 190-197 fps, zero stutter), at
     * 1,402 ms on a baseline-still record with no stutter at all, and at
     * <b>2 ms</b> on the single record that actually stalled at 20 fps.
     * The metric is inverted with respect to the thing it was gating.
     * The reason is that this is accumulated SCHEDULE DRIFT, not load:
     * once the server has fallen behind its tick schedule it advances
     * {@code nextTickTimeNanos} by one tick period per tick and never
     * pays the debt back, so the difference from now stays wherever the
     * last busy episode left it - here, a 367 s prewarm. The LOAD signal
     * is {@code smoothedTickMaxMicros} ({@code getCurrentSmoothedTickTime},
     * against a 50 ms tick budget), which read 0.56-17 ms on that run,
     * i.e. correctly said the server was fine. The verdict now keys on
     * {@link #externalWaitShare} instead.</p>
     *
     * <p>The quantity is still vanilla's own, taken from the same field
     * its "Can't keep up!" warning uses: {@code MinecraftServer.runServer}
     * computes {@code Util.getNanos() - this.nextTickTimeNanos} (javap,
     * ip 99-107) and warns when that exceeds one second plus twenty
     * ticks. {@code getNextTickTime()} is a public bare field read
     * (javap: {@code aload_0 / getfield nextTickTimeNanos:J / lreturn}),
     * so this bench reads the identical number without scraping a log
     * line - and reads it from the CLIENT thread, so a saturated server
     * cannot block the probe that exists to detect saturation. That read
     * races the server thread's write by construction; the value is a
     * monotonically advancing deadline and this is a diagnostic, so the
     * race can only ever cost one sample's precision.</p>
     *
     * @param framesWhileBehind frames rendered during the sampling
     *                          intervals that ENDED with the server
     *                          behind - attribution at 250 ms
     *                          granularity, not per frame
     */
    private record ServerLag(long behindMaxNanos, long smoothedTickMaxMicros,
            int samples, int samplesBehind, int framesWhileBehind) {

        long behindMaxMillis() {
            return behindMaxNanos / 1_000_000L;
        }
    }

    /**
     * What the HARNESS cost this window, and what the game's own
     * framerate limiter says it was doing.
     *
     * <p>Both exist because of one reading. On the phase-3 final run the
     * travel leg sat at exactly 20.0 fps - one frame per server tick -
     * with the render thread using 1.36 ms of each 50 ms frame and the
     * server's own ticks measured at 6.97 ms. Nothing in the game was
     * slow. The two candidates left were the game's limiter and the test
     * harness, and neither was instrumented; now both are.</p>
     *
     * @param throttleReason      {@code FramerateLimitTracker.getThrottleReason()}.
     *                            NONE with a limit of 200 means the pin is
     *                            not the game's: the tracker's own
     *                            constants are 60 out-of-level, 30 short
     *                            AFK, 10 long AFK and 10 iconified
     *                            (javap), and NONE of them is 20
     * @param perTickNanosTotal   test-thread time inside the window's
     *                            per-tick action - which for the corridor
     *                            is a blocking round trip to the server
     */
    private record Harness(long perTickNanosTotal, long perTickNanosMax,
            int perTickCalls, String throttleReason, int throttleLimit,
            int throttledSamples, int heavilyThrottledSamples) {

        double perTickMeanMs() {
            return perTickCalls > 0 ? perTickNanosTotal / (double) perTickCalls / 1e6 : 0;
        }
    }

    /**
     * Accumulates {@link ServerLag} across a window, and attributes the
     * window's frames to the intervals it sampled.
     */
    private static final class ServerLagSampler {
        private long behindMaxNanos;
        private long smoothedTickMaxMicros;
        private int samples;
        private int samplesBehind;
        private int framesWhileBehind;
        private int lastFrames;
        // Written on the CLIENT thread inside the probe below and read on
        // the test thread afterwards. Safe without volatile: the
        // framework's runOnClient releases a semaphore and re-acquires
        // one around the task (javap on ThreadingImpl.runTaskOnOtherThread),
        // which is the happens-before edge.
        private String throttleReason = "unsampled";
        private int throttleLimit;
        private int throttledSamples;
        private int heavilySamples;

        void sample(ClientGameTestContext context) {
            long[] probe = context.computeOnClient(client -> {
                FramerateLimitTracker tracker = client.getFramerateLimitTracker();
                if (tracker != null) {
                    FramerateLimitTracker.FramerateThrottleReason reason =
                            tracker.getThrottleReason();
                    throttleReason = reason == null ? "null" : reason.name();
                    throttleLimit = tracker.getFramerateLimit();
                    if (reason != null
                            && reason != FramerateLimitTracker
                                    .FramerateThrottleReason.NONE) {
                        throttledSamples++;
                    }
                    if (tracker.isHeavilyThrottled()) {
                        heavilySamples++;
                    }
                }
                MinecraftServer server = client.getSingleplayerServer();
                long behind = server == null ? 0L
                        : Math.max(0L, Util.getNanos() - server.getNextTickTime());
                long smoothedMicros = server == null ? 0L
                        : (long) (server.getCurrentSmoothedTickTime() * 1000.0f);
                return new long[] {MesheliumBenchRecorder.filled(), behind,
                        smoothedMicros};
            });
            int frames = (int) probe[0];
            int sinceLast = Math.max(0, frames - lastFrames);
            lastFrames = frames;
            samples++;
            behindMaxNanos = Math.max(behindMaxNanos, probe[1]);
            smoothedTickMaxMicros = Math.max(smoothedTickMaxMicros, probe[2]);
            if (probe[1] >= SERVER_BEHIND_MS * 1_000_000L) {
                samplesBehind++;
                framesWhileBehind += sinceLast;
            }
        }

        ServerLag result() {
            return new ServerLag(behindMaxNanos, smoothedTickMaxMicros, samples,
                    samplesBehind, framesWhileBehind);
        }

        Harness harness(long perTickNanosTotal, long perTickNanosMax, int perTickCalls) {
            return new Harness(perTickNanosTotal, perTickNanosMax, perTickCalls,
                    throttleReason, throttleLimit, throttledSamples, heavilySamples);
        }
    }

    private final List<Window> windows = new ArrayList<>();
    private final List<String> shots = new ArrayList<>();
    private Path screenshotDir;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!ARMED) {
            return; // not a farbench run; every other run pays nothing
        }
        // HARD failures rather than skips, the visual diagnostic's rule:
        // the property was passed on purpose, and a run that quietly
        // measured a dormant renderer would be worse than no run - the
        // coordinator would read a flat frame time and conclude the far
        // field is free.
        if (!"vulkan".equalsIgnoreCase(
                System.getProperty("meshelium.test.expectBackend", ""))) {
            throw new AssertionError("the far-armed bench needs "
                    + "-Pmeshelium.backend=vulkan; on the OpenGL backend Meshelium is "
                    + "dormant by design, the residency pump never runs, and the far "
                    + "field is never pumped at all");
        }
        if (!Boolean.getBoolean(TerrainDrawer.PROPERTY)) {
            throw new AssertionError("the far-armed bench needs -Pmeshelium.terrain; "
                    + "without it the drawer never arms and the frame times measured "
                    + "would be vanilla's, not this renderer's");
        }
        if (!MesheliumConfig.terrainRenderingConfigured()) {
            throw new AssertionError("enableTerrainRendering is FALSE in "
                    + "config/meshelium.json; the residency pump gates on it, so the far "
                    + "field can never be pumped and the far arm of the A/B would be "
                    + "identical to the off arm by construction");
        }
        if (!MesheliumBenchRecorder.ARMED) {
            throw new AssertionError("the frame recorder is not armed: "
                    + MesheliumBenchRecorder.FARBENCH_PROPERTY + " did not reach the "
                    + "client JVM, so there is no per-frame clock and this run could "
                    + "only report zero frames. Check the -Pmeshelium.farbench block in "
                    + "build.gradle");
        }
        if (FAR && !ExtractDispatch.PERF_STATS) {
            throw new AssertionError("meshelium.farfield.perfStats did not reach the "
                    + "client JVM, so the per-frame slice, overrun and per-column "
                    + "distributions would all come back empty - which is exactly the "
                    + "shape of a green run that measured nothing. Check the "
                    + "-Pmeshelium.farbench block in build.gradle");
        }
        assertGeometryFresh();

        List<String> selected = Arrays.stream(SCENARIOS.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).toList();
        for (String scenario : selected) {
            if (!scenario.equals("baseline-still") && !scenario.equals("travel")
                    && !scenario.equals("teleport")) {
                throw new AssertionError("unknown scenario '" + scenario + "' in "
                        + "meshelium.test.farbench.scenarios (known: baseline-still, "
                        + "travel, teleport)");
            }
        }

        // The cache MUST be empty before a run that measures saving. A
        // second run against a warm store reads shells instead of
        // extracting them, so "fresh terrain" stops being fresh for the
        // far field and the A/B silently compares a saving session with
        // a loading one. Cleared BEFORE the world exists and before the
        // master switch goes on, which is the one moment there is no IO
        // thread and no open region handle to fight.
        clearFarCache(context);

        int[] framerateLimit = new int[1];
        context.runOnClient(client -> {
            client.options.renderDistance().set(RD);
            client.options.fov().set(70);
            client.options.cloudStatus().set(CloudStatus.OFF);
            // THE 30FPS TRAP (the near-field bench, 2026-08-10): vanilla
            // caps an unfocused window to 30 fps after a minute and the
            // harness window is never focused, so without MINIMIZED the
            // bench measures the pacing cap instead of the renderer.
            // Vsync off for the same reason.
            //
            // The framerate limit is then pinned DELIBERATELY - see
            // FPS_CAP - to emulate the owner's 5 ms frame rather than to
            // report this machine's 0.366 ms one. Pass
            // -Dmeshelium.test.farbench.fpsCap=260 for an unlimited run;
            // 260 is vanilla's own unlimited cutoff.
            client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
            client.options.enableVsync().set(false);
            client.options.framerateLimit().set(FPS_CAP);
            client.options.save();
            framerateLimit[0] = client.options.framerateLimit().get();
        });
        framerateLimitEffective = framerateLimit[0];
        if (framerateLimit[0] != FPS_CAP) {
            log("the framerate limit did not take: asked for " + FPS_CAP
                    + ", the option answers " + framerateLimit[0]
                    + " - every fraction-of-frame figure below is against THAT rate");
        }

        log("begin far=" + FAR + " rd=" + RD + " lodRadiusChunks=" + LOD
                + " seed=" + SEED + " scenarios=" + selected
                + " speedBlocksPerTick=" + SPEED_BLOCKS_PER_TICK
                + " hopBlocks=" + hopBlocks() + " laneSpacing=" + LANE_SPACING_BLOCKS
                + " still=" + STILL_SECONDS + "s travel=" + TRAVEL_SECONDS + "s jump="
                + JUMP_SECONDS + "s stand=" + STAND_SECONDS + "s idle=" + IDLE_SECONDS
                + "s teleports=" + TELEPORTS
                + " framerateLimit=" + framerateLimit[0]
                + (framerateLimit[0] >= Options.UNLIMITED_FRAMERATE_CUTOFF
                        ? " (UNLIMITED - fractions of frame are against this "
                                + "machine's own frame, not the owner's)"
                        : " (PINNED, deliberate emulation of a "
                                + round2(1000.0 / framerateLimit[0])
                                + "ms frame; not this machine's capability)")
                + " perfStats=" + ExtractDispatch.PERF_STATS
                + " cacheFolder=" + FarField.cacheFolder());

        try {
            try (TestSingleplayerContext singleplayer = context.worldBuilder()
                    .adjustSettings(settings -> {
                        settings.setName(WORLD_NAME + (FAR ? "-faron" : "-faroff"));
                        settings.setSeed(SEED);
                        settings.setAllowCommands(true);
                        settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                        // A REAL noise world. The harness default is the
                        // FLAT preset, where a column has one surface, no
                        // ocean floor to prime and almost nothing to walk -
                        // i.e. exactly the terrain that would make the
                        // extraction cost look free.
                        settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(
                                settings.getSettings().worldgenLoadContext()
                                        .lookupOrThrow(Registries.WORLD_PRESET)
                                        .getOrThrow(WorldPresets.NORMAL)));
                    })
                    .create()) {

                TestServerContext server = singleplayer.getServer();
                settleLoadedChunks(context, "world load");
                freezeWorld(server);
                server.runCommand("gamemode spectator @p");
                context.waitFor(client ->
                        client.options.getEffectiveRenderDistance() == RD,
                        RD_TIMEOUT_TICKS);
                context.waitFor(client -> TerrainDrawer.framesDrawn() > 0
                        && TerrainDrawer.lastDrawnSections() > 0, DRAW_TIMEOUT_TICKS);
                assertNoErrors();

                // BEFORE the master switch: the teleport destinations
                // are generated now, with the far field off and its cache
                // already emptied, so a measured teleport lands on ground
                // the server has and the far field has not. See
                // prewarmTeleportDestinations for what that costs.
                if (selected.contains("teleport")) {
                    prewarmTeleportDestinations(context, server);
                }
                if (FAR) {
                    setFarRadius(context, LOD);
                    armFarField(context, true);
                }
                log("world ready: seed=" + SEED + " rd=" + RD + " far="
                        + FAR + " farRadiusChunks=" + (FAR ? LOD : 0)
                        + " cacheSpeed=" + FarFieldConfig.layerCacheSpeed(
                                FarFieldConfig.Layer.L1)
                        + " extractBudgetMillis=" + FarFieldConfig.extractBudgetMillis()
                        + " frameRateFloor=" + FarFieldConfig.extractFrameRateFloor());

                if (selected.contains("baseline-still")) {
                    runBaselineStill(context, server);
                }
                if (selected.contains("travel")) {
                    runTravel(context, server);
                }
                if (selected.contains("teleport")) {
                    runTeleports(context, server);
                }
            }
        } catch (RuntimeException | Error failure) {
            // Write what WAS measured before rethrowing. The first
            // shakedown died on the travel gate and took its whole JSON
            // with it, so two complete scenarios existed only as log
            // lines. The gates inside the report are skipped here: a
            // vacuity assertion must never replace the failure that
            // actually stopped the run.
            try {
                report(context, false);
            } catch (RuntimeException | Error secondary) {
                failure.addSuppressed(secondary);
            }
            throw failure;
        } finally {
            armFarField(context, false);
            context.runOnClient(client -> {
                MesheliumBenchRecorder.disarm();
                System.clearProperty("meshelium.farfield.l1RadiusChunks");
                System.clearProperty("meshelium.farfield.l1Enabled");
            });
        }

        report(context, true);
    }

    // ------------------------------------------------------------------
    // The scenarios
    // ------------------------------------------------------------------

    /**
     * The floor: already-saved ground, the save queue drained, nothing
     * moving. Whatever this costs is what the machine costs, and every
     * other scenario is read against it.
     *
     * <p>Run FIRST and at the spawn lane on purpose: spawn is the one
     * region the world load has already generated, so waiting for the
     * far field to finish saving it is seconds rather than minutes, and
     * the wait itself is the proof that "no saves" is true rather than
     * assumed - {@link #waitForSaveQuiet} returns only when the job
     * queues are empty and the store's write counter has stopped.</p>
     */
    private void runBaselineStill(ClientGameTestContext context, TestServerContext server) {
        double x = laneX(0);
        double z = 0.0;
        teleport(server, x, TRAVEL_Y, z);
        settleLoadedChunks(context, "baseline-still");
        if (FAR) {
            waitForSaveQuiet(context);
        }
        context.waitTicks(WARMUP_TICKS);
        Window w = measure(context, "baseline-still", false, false,
                STILL_SECONDS * 20, null);
        logWindow(w);
        shoot(context, "P0_farbench_baseline_still");
        assertNoErrors();
    }

    /**
     * The travel corridor: fresh ground the whole way, one teleport a
     * tick, vanilla streaming continuously and the far field saving
     * continuously. The owner's "when its doing constant saves".
     */
    private void runTravel(ClientGameTestContext context, TestServerContext server) {
        double x = laneX(1);
        teleport(server, x, TRAVEL_Y, 0.0);
        settleLoadedChunks(context, "travel start");
        // Warm up ALONG the corridor, not standing at its head: the
        // steady state being measured is the streaming one, and a
        // measurement that starts from a settled window spends its first
        // seconds measuring the ramp instead.
        flyCorridor(context, server, x, 0.0, TRAVEL_WARMUP_TICKS, 0);
        double warmedZ = SPEED_BLOCKS_PER_TICK * TRAVEL_WARMUP_TICKS;
        // One teleport every TRAVEL_STEP_TICKS ticks, moving the whole
        // step at once: same 20 m/s, a quarter of the blocking server
        // round trips. See TRAVEL_STEP_TICKS for the harness mechanism
        // that makes the count matter more than the distance.
        int ticks = TRAVEL_SECONDS * 20;
        // FOUR terrain signals, before and after, ALL FOUR printed. The
        // first shakedown gated on TerrainResidency.encodedSections alone
        // and read a flat zero through a leg that had plainly streamed and
        // saved, so it failed the run on the strength of a counter that
        // does not move here rather than on the scenario. A delta on its
        // own is undiagnosable; the endpoints are not.
        TerrainResidency.Counters nearBefore = TerrainResidency.counters();
        long encodedBefore = nearBefore.encodedSections();
        long uploadedBefore = nearBefore.uploadedSections();
        long extractsBefore = ExtractDispatch.farExtracts.sum();
        long writesBefore = FarField.farStoreWrites.sum();
        double zBefore = cameraZ(context);
        Window w = measure(context, "travel", false, true, ticks,
                tick -> {
                    if (tick % TRAVEL_STEP_TICKS == 0) {
                        teleport(server, x, TRAVEL_Y, warmedZ
                                + SPEED_BLOCKS_PER_TICK * (tick + TRAVEL_STEP_TICKS));
                    }
                });
        TerrainResidency.Counters nearAfter = TerrainResidency.counters();
        long encodedAfter = nearAfter.encodedSections();
        long uploadedAfter = nearAfter.uploadedSections();
        long extractsAfter = ExtractDispatch.farExtracts.sum();
        long writesAfter = FarField.farStoreWrites.sum();
        double zAfter = cameraZ(context);
        int chunksCrossed = chunksBetween(zBefore, zAfter);
        logWindow(w);
        log("travel blocks=" + round1(SPEED_BLOCKS_PER_TICK * ticks)
                + " chunksCrossed=" + chunksCrossed
                + " cameraZ=" + round1(zBefore) + "->" + round1(zAfter)
                + " encodedSections=" + encodedBefore + "->" + encodedAfter
                + " uploadedSections=" + uploadedBefore + "->" + uploadedAfter
                + " farExtracts=" + extractsBefore + "->" + extractsAfter
                + " farStoreWrites=" + writesBefore + "->" + writesAfter);
        shoot(context, "P1_farbench_travel");
        // THE GATE, on the two signals this bench can prove move: the
        // camera's own position (which cannot be wrong about whether the
        // corridor happened) and, when the far field is armed, the
        // columns it walked (which is the literal meaning of "saving
        // happened", and which the shakedown measured at 1,964).
        if (chunksCrossed < MIN_TRAVEL_CHUNKS) {
            throw new AssertionError("the travel leg crossed only " + chunksCrossed
                    + " chunk columns (floor " + MIN_TRAVEL_CHUNKS + "): the camera did "
                    + "not travel, so nothing streamed and this record describes a "
                    + "stationary player. cameraZ " + round1(zBefore) + " -> "
                    + round1(zAfter) + " over " + ticks + " ticks at "
                    + SPEED_BLOCKS_PER_TICK + " blocks/tick - if those are equal the "
                    + "teleport command is not landing");
        }
        if (FAR && extractsAfter - extractsBefore < MIN_TRAVEL_EXTRACTS) {
            throw new AssertionError("the travel leg walked only "
                    + (extractsAfter - extractsBefore) + " columns (floor "
                    + MIN_TRAVEL_EXTRACTS + ") although the camera crossed "
                    + chunksCrossed + " chunks. The far field is armed and is not "
                    + "saving what the corridor uncovered, so the frame times in this "
                    + "record are not the cost of saving");
        }
        assertNoErrors();
    }

    /**
     * The headline. Jump to ground that has never been loaded, then
     * stand - three times, three places, no shared chunk.
     *
     * <p>Three records per jump, because the brief makes three different
     * claims about them and one record could not separate them: the JUMP
     * window holds the synchronous whole-window capture at the
     * view-centre move (predicted ~25-30 ms in one frame) and the pump
     * behind it (predicted ~6 ms); the STAND window is the streaming
     * storm; and the IDLE window is taken only after vanilla's chunk
     * traffic has stopped, which is the precondition the idle boost's own
     * rule requires (a quiet second AND a backlog) and therefore the only
     * window in which the brief's 65-75 fps row can appear at all.</p>
     */
    private void runTeleports(ClientGameTestContext context, TestServerContext server) {
        double x = laneX(2);
        int hop = hopBlocks();
        for (int i = 0; i < TELEPORTS; i++) {
            double z = (double) hop * i;
            // Do not open a measurement window INTO a saturation
            // episode. Cheap when the server is already caught up, and
            // the difference between a record that measures a teleport
            // and one that measures the tail of the last one.
            waitForServerCaughtUp(context, "teleport" + i + " jump");
            // The jump is INSIDE the measured window: the frame that
            // pays for it is the one the brief predicts, and a record
            // armed after the teleport would miss precisely that frame.
            Window jump = measure(context, "teleport" + i + "-jump", true, true,
                    JUMP_SECONDS * 20,
                    tick -> {
                        if (tick == 0) {
                            teleport(server, x, TRAVEL_Y, z);
                        }
                    });
            logWindow(jump);
            Window stand = measure(context, "teleport" + i + "-stand", false, true,
                    STAND_SECONDS * 20, null);
            logWindow(stand);
            // Now wait for VANILLA to go quiet, so the idle window is
            // idle by the far field's own definition rather than by the
            // clock. Non-fatal: a window that never settles is still
            // worth a record, as long as the log says which it was.
            boolean settled = settleLoadedChunksSoft(context, "teleport" + i + " idle");
            boolean caughtUp = waitForServerCaughtUp(context, "teleport" + i + " idle");
            Window idle = measure(context, "teleport" + i + "-idle", false, true,
                    IDLE_SECONDS * 20, null);
            logWindow(idle);
            log("teleport" + i + " vanillaSettledBeforeIdle=" + settled
                    + " serverCaughtUpBeforeIdle=" + caughtUp
                    + " prewarmed=" + PREWARM
                    + " at " + (long) x + "," + (long) z);
            shoot(context, "P2_farbench_teleport" + i);
            assertNoErrors();
        }
    }

    // ------------------------------------------------------------------
    // Measurement
    // ------------------------------------------------------------------

    /**
     * Record one window: reset the far field's distributions, arm the
     * frame clock, run {@code ticks} ticks (calling {@code perTick}
     * first on every one of them), then read everything back.
     *
     * <p>The frame clock is {@code MesheliumBenchRecorder}, the same
     * render-thread hook the near-field benchmark stamps: two
     * preallocated {@code long[]}s written once per rendered frame with
     * no allocation - the wall-clock delta and vanilla's own CPU frame
     * span. The capacity is sized from the window's SECONDS and
     * {@link #FPS_CEILING}; past that the window truncates and says so
     * rather than silently reporting a short record.</p>
     */
    private Window measure(ClientGameTestContext context, String name, boolean spike,
            boolean saving, int ticks, IntConsumer perTick) {
        int capacity = (int) Math.min(400_000L,
                (long) Math.ceil(ticks / 20.0 * FPS_CEILING) + 500L);
        Map<String, Long> before = counterSnapshot(context);
        context.runOnClient(client -> {
            ExtractDispatch.resetPerfStats();
            MesheliumBenchRecorder.arm(capacity);
        });
        long t0 = System.nanoTime();
        // The window is now always driven in steps, because every step
        // boundary is where the server's tick lag is sampled and where
        // the frames since the last sample are attributed to it. A window
        // that cannot say whether the server was saturated cannot tell
        // its own cost from vanilla's, which is exactly the read that
        // took a Phase 3 run apart.
        ServerLagSampler sampler = new ServerLagSampler();
        sampler.sample(context);
        long perTickNanos = 0;
        long perTickMaxNanos = 0;
        int perTickCalls = 0;
        int tick = 0;
        while (tick < ticks) {
            if (perTick != null) {
                // TIMED, because on this harness the per-tick action is a
                // blocking round trip to the server thread and that round
                // trip is a candidate for any stall the window records.
                long actionStart = System.nanoTime();
                perTick.accept(tick);
                long actionNanos = System.nanoTime() - actionStart;
                perTickNanos += actionNanos;
                perTickMaxNanos = Math.max(perTickMaxNanos, actionNanos);
                perTickCalls++;
                context.waitTicks(1);
                tick++;
            } else {
                int step = Math.min(SERVER_SAMPLE_TICKS, ticks - tick);
                context.waitTicks(step);
                tick += step;
            }
            if (tick % SERVER_SAMPLE_TICKS == 0 || tick == ticks) {
                sampler.sample(context);
            }
        }
        long wall = System.nanoTime() - t0;
        long[] frames = context.computeOnClient(client -> {
            MesheliumBenchRecorder.disarm();
            return MesheliumBenchRecorder.snapshot();
        });
        // T2 Phase 3: this distribution's MEANING CHANGED. It was the
        // per-column WALK (measured here at p50 6.816 ms / p99 20.972 ms
        // on the travel leg before Phase 3); it is now the per-column
        // GAME-THREAD capture step, which is the only far-field work the
        // frame's thread still does. The report's field is renamed with
        // it so an old JSON and a new one cannot be compared by accident;
        // the walk's own cost is farExtractNanos / farExtracts.
        long[] cpuFrames = context.computeOnClient(
                client -> MesheliumBenchRecorder.snapshotCpu());
        ExtractDispatch.PerfStats extract = context.computeOnClient(
                client -> ExtractDispatch.captureCostStats());
        ExtractDispatch.PerfStats slice = context.computeOnClient(
                client -> ExtractDispatch.sliceSpendStats());
        ExtractDispatch.PerfStats overrun = context.computeOnClient(
                client -> ExtractDispatch.sliceOverrunStats());
        ExtractDispatch.PerfStats budget = context.computeOnClient(
                client -> ExtractDispatch.sliceBudgetStats());
        Map<String, Long> after = counterSnapshot(context);
        Window window = new Window(name, spike, saving, frames, cpuFrames, wall,
                sampler.result(),
                sampler.harness(perTickNanos, perTickMaxNanos, perTickCalls),
                delta(before, after), gaugeSnapshot(context),
                extract, slice, overrun, budget);
        windows.add(window);
        if (frames.length >= capacity) {
            log(name + " TRUNCATED at " + capacity + " frames - the window ran "
                    + "faster than " + FPS_CEILING + " fps and its tail is missing. "
                    + "Raise meshelium.test.farbench.fpsCeiling before quoting this "
                    + "record");
        }
        // THE FLOOR, and the distinction the Phase 3 run forced.
        //
        // A short record has two completely different causes and the old
        // floor could not tell them apart. Zero frames means the frame
        // hook never fired and nothing rendered - that is the thing the
        // floor was built to catch and it still FAILS. A short record
        // whose own frames show the render thread waiting on something
        // else, or whose sampler caught the integrated server seconds
        // behind its tick deadline, is a measurement of vanilla's
        // worldgen and not of this renderer: it is REPORTED, marked
        // unusable, and the run continues. A stall that reproduces with
        // the feature disarmed was never the feature's stall.
        //
        // What is deliberately NOT softened: a short record with no
        // external cause still fails, because "the client was slow and it
        // was us" is the finding the floor exists to surface.
        String unusable = unusableReason(window);
        if (frames.length == 0) {
            throw new AssertionError("scenario " + name + " recorded ZERO frames over "
                    + round1(wall / 1e9) + "s. The frame hook never fired: nothing was "
                    + "rendered in-world, so this is not a slow client, it is no client "
                    + "at all");
        }
        int floor = spike ? MIN_SPIKE_FRAMES : MIN_FRAMES;
        if (frames.length < floor && unusable == null) {
            throw new AssertionError("scenario " + name + " recorded only "
                    + frames.length + " frames (floor " + floor + ") at "
                    + round1(frames.length / Math.max(1.0, wall / 1e9)) + " fps, and "
                    + "NOTHING EXTERNAL EXPLAINS IT: the server stayed within "
                    + window.lag().behindMaxMillis() + " ms of its tick deadline and "
                    + round1(100.0 * externalWaitShare(window)) + "% of the frame time "
                    + "was outside our work and the pin. That is a real frame-rate "
                    + "finding, and a record this short cannot carry a p99, so this run "
                    + "will not pretend otherwise");
        }
        if (unusable != null) {
            log(name + " UNUSABLE: " + unusable + ". The record is kept and written to "
                    + "the report, and must not be quoted as a distribution");
        }
        return window;
    }

    /**
     * The share of a window's wall clock that went neither into our own
     * work nor into the framerate pin, 0 to 1.
     *
     * <p>Per frame the quantity is {@code wall - max(cpu, pinPeriod)}.
     * Subtracting the pin matters: under a 200 fps pin a healthy 1.5 ms
     * frame sleeps 3.5 ms by design, and counting that as a stall would
     * mark every pinned record unusable. What survives the subtraction is
     * frame time that is neither our work nor the pacing we asked
     * for - on the Phase 3 run's {@code teleport0-stand} that was 90% of
     * every frame, against a 1.5 ms CPU span.</p>
     */
    private static double externalWaitShare(Window w) {
        long[] frames = w.frames();
        long[] cpu = w.cpuFrames();
        if (frames.length == 0 || cpu.length != frames.length) {
            return 0;
        }
        long pinNanos = framerateLimitEffective > 0
                && framerateLimitEffective < Options.UNLIMITED_FRAMERATE_CUTOFF
                ? 1_000_000_000L / framerateLimitEffective : 0L;
        long total = 0;
        long waited = 0;
        for (int i = 0; i < frames.length; i++) {
            total += frames[i];
            waited += Math.max(0L, frames[i] - Math.max(cpu[i], pinNanos));
        }
        return total > 0 ? (double) waited / total : 0;
    }

    /** Frames whose wall time was overwhelmingly spent waiting elsewhere. */
    private static int framesMostlyWaiting(Window w) {
        long[] frames = w.frames();
        long[] cpu = w.cpuFrames();
        if (cpu.length != frames.length) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < frames.length; i++) {
            // Four times: the render thread did under a quarter of the
            // frame's wall time, so three quarters of it belonged to
            // something this bench does not control.
            if (frames[i] > 4L * cpu[i]) {
                count++;
            }
        }
        return count;
    }

    /**
     * Why this record must not be quoted as a distribution, or null when
     * it may be.
     *
     * <p>Two causes, both external and both measured rather than
     * guessed: the integrated server missing its own tick deadline (the
     * quantity vanilla's "Can't keep up!" prints), and the render thread
     * spending the frame waiting on neither us nor the pin. They are the
     * same event seen from the two ends, and either is enough - the
     * Phase 3 run had both, and the far-DISARMED attribution run
     * reproduced the pathology at a different teleport, which is what
     * settled that it was never ours.</p>
     */
    private static String unusableReason(Window w) {
        double share = externalWaitShare(w);
        if (share < UNUSABLE_WAIT_SHARE) {
            return null;
        }
        return round1(100.0 * share) + "% of this record's wall clock went neither into "
                + "our work nor into the framerate pin (" + framesMostlyWaiting(w)
                + " of " + w.frames().length + " frames spent over three quarters of "
                + "themselves waiting; CPU span p50 against wall p50 is the contrast), "
                + "so these frame times measure something outside this renderer. "
                + "Harness per-tick action: " + round2(w.harness().perTickMeanMs())
                + " ms mean, " + round2(w.harness().perTickNanosMax() / 1e6) + " ms max "
                + "over " + w.harness().perTickCalls() + " calls; throttle reason "
                + w.harness().throttleReason() + " at limit "
                + w.harness().throttleLimit() + "; server schedule drift "
                + w.lag().behindMaxMillis() + " ms, worst smoothed tick "
                + round2(w.lag().smoothedTickMaxMicros() / 1000.0) + " ms";
    }

    /**
     * Wait, bounded, for the integrated server's smoothed tick time to
     * fall below {@value #SERVER_BUSY_TICK_MS} ms of its 50 ms budget.
     *
     * <p>Cheap when the server is already idle, and the difference
     * between opening a measurement window into a worldgen episode and
     * opening it after one. Never fatal: a server that stays busy is a
     * finding the record itself will carry.</p>
     *
     * <p>It used to wait on the tick DEADLINE instead, and that cost
     * nine minutes of the phase-3 final run without ever succeeding,
     * because the drift it waited on is not something the server pays
     * back. Thirty seconds against the load signal is both correct and
     * an order of magnitude cheaper.</p>
     */
    private static boolean waitForServerCaughtUp(ClientGameTestContext context,
            String where) {
        double worst = 0;
        int calm = 0;
        for (int second = 1; second <= SERVER_CATCHUP_SECONDS; second++) {
            // LOAD, not drift. This waited on
            // "now - nextTickTimeNanos" until the phase-3 final run showed
            // that number pinned near 603 ms on every healthy record and
            // at 2 ms on the one record that actually stalled: it is
            // accumulated SCHEDULE DRIFT, which the server never pays back
            // once it has fallen behind, and it says nothing about whether
            // the server is busy now. The smoothed tick time does: 50 ms
            // is the budget, and this run never saw it above 17.
            double tickMillis = context.computeOnClient(client -> {
                MinecraftServer server = client.getSingleplayerServer();
                return server == null ? 0.0 : (double) server.getCurrentSmoothedTickTime();
            });
            worst = Math.max(worst, tickMillis);
            calm = tickMillis < SERVER_BUSY_TICK_MS ? calm + 1 : 0;
            if (calm >= 2) {
                if (second > 2) {
                    log("server settled before " + where + " after " + second
                            + "s (worst smoothed tick " + round2(worst) + " ms)");
                }
                return true;
            }
            context.waitTicks(20);
        }
        log("the server was still busy before " + where + " (worst smoothed tick "
                + round2(worst) + " ms against a 50 ms budget, over "
                + SERVER_CATCHUP_SECONDS + "s); the window opens anyway and its record "
                + "will carry the numbers");
        return false;
    }

    /**
     * Generate the teleport destinations BEFORE the far field is armed.
     *
     * <p><b>Why.</b> On the Phase 3 run the measured teleport landed on
     * virgin terrain and the integrated server went 4,124 ms behind
     * generating it; the client was pinned to one frame per server tick,
     * 49.96 ms, of which the render thread used 1.5 ms and the far field
     * 0.24. The record measured worldgen. The far-DISARMED attribution
     * run reproduced the same pathology at a different teleport, which
     * settles that it was never ours - but a record nobody can quote is
     * still a scenario nobody can measure.</p>
     *
     * <p><b>What this changes, stated plainly rather than substituted
     * quietly.</b> The destinations are visited here, with the far field
     * OFF and the far-field cache already emptied, so the server writes
     * them to its region files and the measured teleport LOADS them
     * instead of generating them. The far field's own scenario is
     * untouched: it is still armed for the first time over that ground,
     * the client still receives every one of the ~1,521 chunk packets,
     * and every one of them is still captured and stored. What is
     * removed is vanilla's generation cost - which is real, which a
     * player teleporting somewhere genuinely new does pay, and which
     * this instrument cannot attribute and must not price. <b>So the
     * teleport records understate what a first visit feels like, and
     * they are not a claim about it.</b>
     * {@code -Dmeshelium.test.farbench.prewarm=false} restores the harsh
     * variant for anyone who wants the combined figure.</p>
     *
     * <p>The TRAVEL leg is deliberately left virgin. Its load is spread
     * over four hundred blocks of continuous movement rather than
     * arriving as one window, it never saturated the server on any run,
     * and streaming over new ground is the owner's own case.</p>
     */
    private void prewarmTeleportDestinations(ClientGameTestContext context,
            TestServerContext server) {
        if (!PREWARM) {
            log("prewarm: DISABLED - the teleport destinations will be generated "
                    + "inside their own measurement windows, so those records may be "
                    + "marked unusable when the server saturates");
            return;
        }
        double x = laneX(2);
        int hop = hopBlocks();
        long started = System.nanoTime();
        for (int i = 0; i < TELEPORTS; i++) {
            teleport(server, x, TRAVEL_Y, (double) hop * i);
            settleLoadedChunksSoft(context, "prewarm teleport" + i);
            waitForServerCaughtUp(context, "prewarm teleport" + i);
        }
        prewarmSeconds = (int) ((System.nanoTime() - started) / 1_000_000_000L);
        log("prewarm: " + TELEPORTS + " teleport destinations generated in "
                + prewarmSeconds + "s with the far field OFF - the measured teleports "
                + "load them from the region files instead of generating them, so those "
                + "records exclude worldgen and understate a genuine first visit. THIS "
                + "IS MOST OF THE RUN'S LENGTH: pass "
                + "-Dmeshelium.test.farbench.prewarm=false for a quick pass, and read "
                + "the teleport records knowing they then include worldgen");
    }

    /**
     * The camera's Z on the client, which in spectator IS the player
     * entity. Read from vanilla rather than from the commands this class
     * issued, so "the corridor happened" is evidence and not bookkeeping.
     */
    private static double cameraZ(ClientGameTestContext context) {
        Double z = context.computeOnClient(client ->
                client.player == null ? null : client.player.getZ());
        if (z == null) {
            throw new AssertionError("there is no client player to read a camera "
                    + "position from, so the travel leg cannot be shown to have "
                    + "travelled");
        }
        return z;
    }

    /** Chunk columns between two Z coordinates. */
    private static int chunksBetween(double from, double to) {
        return (int) Math.abs(Math.floor(to / 16.0) - Math.floor(from / 16.0));
    }

    /** Fly the corridor for {@code ticks} ticks without recording. */
    private static void flyCorridor(ClientGameTestContext context, TestServerContext server,
            double x, double z0, int ticks, int startTick) {
        for (int tick = 0; tick < ticks; tick++) {
            teleport(server, x, TRAVEL_Y,
                    z0 + SPEED_BLOCKS_PER_TICK * (startTick + tick + 1));
            context.waitTicks(1);
        }
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    /** The one-line, greppable per-scenario summary. */
    private void logWindow(Window w) {
        Map<String, Object> s = summarize(w);
        log(w.name() + (Boolean.TRUE.equals(s.get("unusable")) ? " UNUSABLE" : "")
                + " frames=" + s.get("frames")
                + " mean=" + s.get("meanMs") + "ms"
                + " p50=" + s.get("p50Ms")
                + " p90=" + s.get("p90Ms")
                + " p95=" + s.get("p95Ms")
                + " p99=" + s.get("p99Ms")
                + " max=" + s.get("maxMs")
                + " cpu[p50=" + s.get("cpuP50Ms") + " p95=" + s.get("cpuP95Ms")
                + " p99=" + s.get("cpuP99Ms") + " max=" + s.get("cpuMaxMs") + "]"
                + " fps[mean=" + s.get("meanFps") + " p50=" + s.get("p50Fps")
                + " p99=" + s.get("p99Fps") + "]"
                + " stutter[2xp50=" + s.get("framesOverTwiceP50")
                + " >16.7=" + s.get("framesOver16_7Ms")
                + " >33=" + s.get("framesOver33Ms") + "]"
                + " judderMax=" + s.get("maxFrameToFrameDeltaMs") + "ms"
                + " far[msPerFrame=" + s.get("farMsPerFrame")
                + " frameShare=" + s.get("farFrameSharePercent") + "%"
                + " slice_p95=" + s.get("farSliceP95Ms") + "ms"
                + " slice_max=" + s.get("farSliceMaxMs") + "ms"
                + " slice_frames=" + s.get("farSliceFrames")
                + " overrun_p95=" + s.get("farOverrunP95Ms") + "ms"
                + " overrun_max=" + s.get("farOverrunMaxMs") + "ms"
                + " overruns=" + s.get("farOverrunSlices")
                + " budget_p95=" + s.get("farBudgetP95Ms") + "ms"
                // Phase 3 renamed these THREE deliberately. They used to
                // read walk_*, i.e. the per-column cost of the walk on
                // the game thread; the walk is the worker's now and this
                // distribution measures the game thread's CAPTURE step.
                // A log line that kept the old label would invite exactly
                // the comparison it must not support.
                + " capture_p50=" + s.get("farCaptureP50Ms") + "ms"
                + " capture_p99=" + s.get("farCaptureP99Ms") + "ms"
                + " captures=" + s.get("farCaptureSamples")
                + " workerWalkMeanMs=" + s.get("farWorkerWalkMeanMs")
                + " workerBacklog=" + w.gauges().getOrDefault("workerBacklogNow", 0L)
                + " idleBoosts=" + w.totals().getOrDefault("farExtractIdleBoosts", 0L)
                + " withinTarget=" + s.get("withinBriefTarget")
                + " vs " + s.get("briefAllowanceMs") + "ms of a "
                + s.get("briefDenominatorMs") + "ms frame]"
                + " whose[extWait=" + s.get("externalWaitSharePercent") + "%"
                + " wall-cpu_p50=" + s.get("wallMinusCpuP50Ms") + "ms"
                + " harnessPerTick=" + s.get("harnessPerTickMeanMs") + "ms"
                + "/" + s.get("harnessPerTickMaxMs") + "ms x"
                + s.get("harnessPerTickCalls")
                + " (" + s.get("harnessPerTickShareOfWallPercent") + "% of wall)"
                + " throttle=" + s.get("throttleReason")
                + "@" + s.get("throttleFramerateLimit")
                + " serverTickMax=" + s.get("serverSmoothedTickMaxMs") + "ms"
                + " serverDrift=" + s.get("serverBehindMaxMs") + "ms(reported-only)]"
                + " gauge[behind=" + w.gauges().getOrDefault("farSaveBehind", 0L)
                + " queued=" + w.gauges().getOrDefault("jobsQueued", 0L)
                + " pinned=" + w.gauges().getOrDefault("pinnedCount", 0L) + "]");
    }

    /**
     * Every number this instrument exists to produce, for one window.
     *
     * <p>Frame time first and fps beside it, then the smoothness terms
     * (the owner's bar is "no big stutters", and no percentile of a
     * sorted array can see a hitch - adjacency has to be measured in the
     * original order), then the far field's own cost in ABSOLUTE
     * milliseconds, and only then the fraction - derived from it, and
     * carrying the name of the frame it was divided by
     * ({@code briefDenominatorSource}). A fraction whose frame is
     * unstated is the mistake the first shakedown made: the same 0.75 ms
     * of far-field work reads as 205% of this machine's frame and 15% of
     * the owner's.</p>
     */
    private static Map<String, Object> summarize(Window w) {
        Map<String, Object> s = new LinkedHashMap<>();
        long[] frames = w.frames();
        s.put("frames", frames.length);
        s.put("wallSeconds", round3(w.wallNanos() / 1e9));
        if (frames.length == 0) {
            return s;
        }
        long[] sorted = frames.clone();
        Arrays.sort(sorted);
        double mean = 0;
        for (long n : frames) {
            mean += n;
        }
        mean = mean / frames.length / 1e6;
        double p50 = percentileMs(sorted, 50);
        s.put("meanMs", round3(mean));
        s.put("p50Ms", round3(p50));
        s.put("p90Ms", round3(percentileMs(sorted, 90)));
        s.put("p95Ms", round3(percentileMs(sorted, 95)));
        s.put("p99Ms", round3(percentileMs(sorted, 99)));
        s.put("maxMs", round3(sorted[sorted.length - 1] / 1e6));
        s.put("meanFps", round1(mean > 0 ? 1000.0 / mean : 0));
        s.put("p50Fps", round1(p50 > 0 ? 1000.0 / p50 : 0));
        s.put("p99Fps", round1(percentileMs(sorted, 99) > 0
                ? 1000.0 / percentileMs(sorted, 99) : 0));
        // Stutter, three ways, because they answer three questions: a
        // multiple of typical is the perceptual one, 16.7 ms is "did we
        // hold 60", and 33 ms is a frame a player sees as a hitch.
        int overTwice = 0;
        int over16 = 0;
        int over33 = 0;
        for (long n : frames) {
            double ms = n / 1e6;
            if (ms > 2.0 * p50) {
                overTwice++;
            }
            if (ms > 16.7) {
                over16++;
            }
            if (ms > 33.0) {
                over33++;
            }
        }
        s.put("framesOverTwiceP50", overTwice);
        s.put("framesOver16_7Ms", over16);
        s.put("framesOver33Ms", over33);
        double worstJump = 0;
        double sumJump = 0;
        for (int i = 1; i < frames.length; i++) {
            double d = Math.abs(frames[i] - frames[i - 1]) / 1e6;
            sumJump += d;
            worstJump = Math.max(worstJump, d);
        }
        s.put("maxFrameToFrameDeltaMs", round3(worstJump));
        s.put("meanFrameToFrameDeltaMs",
                round3(frames.length > 1 ? sumJump / (frames.length - 1) : 0));
        // ---- the far field's own cost, in both units ----
        long frameSum = 0;
        for (long n : frames) {
            frameSum += n;
        }
        double farMsPerFrame = w.slice().totalNanos() / (double) frames.length / 1e6;
        s.put("farMsPerFrame", round3(farMsPerFrame));
        s.put("farFrameSharePercent",
                round2(frameSum > 0 ? 100.0 * w.slice().totalNanos() / frameSum : 0));
        s.put("farDutyCyclePercent",
                round2(w.wallNanos() > 0
                        ? 100.0 * w.slice().totalNanos() / w.wallNanos() : 0));
        s.put("farSliceP50Ms", round3(w.slice().p50Nanos() / 1e6));
        s.put("farSliceP95Ms", round3(w.slice().p95Nanos() / 1e6));
        s.put("farSliceP99Ms", round3(w.slice().p99Nanos() / 1e6));
        s.put("farSliceMaxMs", round3(w.slice().maxNanos() / 1e6));
        s.put("farSliceFrames", w.slice().samples());
        s.put("farOverrunSlices", w.overrun().samples());
        s.put("farOverrunP50Ms", round3(w.overrun().p50Nanos() / 1e6));
        s.put("farOverrunP95Ms", round3(w.overrun().p95Nanos() / 1e6));
        s.put("farOverrunMaxMs", round3(w.overrun().maxNanos() / 1e6));
        s.put("farOverrunMeanMs", round3(w.overrun().samples() > 0
                ? w.overrun().totalNanos() / (double) w.overrun().samples() / 1e6 : 0));
        s.put("farBudgetP50Ms", round3(w.budget().p50Nanos() / 1e6));
        s.put("farBudgetP95Ms", round3(w.budget().p95Nanos() / 1e6));
        s.put("farBudgetMaxMs", round3(w.budget().maxNanos() / 1e6));
        // Phase 3: farCapture* is the GAME-THREAD per-column step (the
        // successor to farWalk*, renamed because the quantity changed),
        // and farWorkerWalkMeanMs is the per-column WORKER cost derived
        // from the counters - the walk itself, which did not get cheaper
        // and which now sets the fill rate. Reading them together is the
        // whole Phase 3 verdict: the first should collapse, the second
        // should not move much, and the fill rate should follow the
        // second.
        s.put("farCaptureSamples", w.extract().samples());
        s.put("farCaptureP50Ms", round3(w.extract().p50Nanos() / 1e6));
        s.put("farCaptureP95Ms", round3(w.extract().p95Nanos() / 1e6));
        s.put("farCaptureP99Ms", round3(w.extract().p99Nanos() / 1e6));
        s.put("farCaptureMaxMs", round3(w.extract().maxNanos() / 1e6));
        s.put("farCaptureMeanMs", round3(w.extract().samples() > 0
                ? w.extract().totalNanos() / (double) w.extract().samples() / 1e6 : 0));
        long walkNanos = w.totals().getOrDefault("farExtractNanos", 0L);
        long walkCount = w.totals().getOrDefault("farExtracts", 0L);
        s.put("farWorkerWalkMeanMs",
                round3(walkCount > 0 ? walkNanos / (double) walkCount / 1e6 : 0));
        s.put("farCaptureMinMs", round3(w.extract().minNanos() / 1e6));
        // ---- the SECOND frame series: vanilla's own CPU span ----
        //
        // Under a pinned framerate the wall-clock frame is 5 ms by
        // construction whenever the machine keeps up, so it is the right
        // number for "what does the player feel" and the wrong one for
        // "what share of the frame's WORK is the far field". The CPU span
        // excludes the present and the limiter sleep, and it is the exact
        // signal the budget rule feeds on (ExtractDispatch reads
        // Minecraft.getFrameTimeNs), so the brief's 10% is evaluated
        // against it whenever it is available.
        long[] cpu = w.cpuFrames();
        double cpuP50 = 0;
        if (cpu.length > 0) {
            long[] cpuSorted = cpu.clone();
            Arrays.sort(cpuSorted);
            cpuP50 = percentileMs(cpuSorted, 50);
            double cpuMean = 0;
            long cpuSum = 0;
            for (long n : cpu) {
                cpuMean += n;
                cpuSum += n;
            }
            s.put("cpuFrames", cpu.length);
            s.put("cpuMeanMs", round3(cpuMean / cpu.length / 1e6));
            s.put("cpuP50Ms", round3(cpuP50));
            s.put("cpuP95Ms", round3(percentileMs(cpuSorted, 95)));
            s.put("cpuP99Ms", round3(percentileMs(cpuSorted, 99)));
            s.put("cpuMaxMs", round3(cpuSorted[cpuSorted.length - 1] / 1e6));
            s.put("farCpuFrameSharePercent",
                    round2(cpuSum > 0 ? 100.0 * w.slice().totalNanos() / cpuSum : 0));
        }
        // ---- WHOSE time was it? ----
        //
        // The contrast that took a Phase 3 teleport record apart in one
        // read - a 49.96 ms wall frame against a 1.533 ms CPU span - now
        // lives in the JSON and not only in the log line, as three
        // derived numbers plus the server's own account of itself. A
        // record where these are large is a record measuring vanilla.
        s.put("externalWaitSharePercent", round2(100.0 * externalWaitShare(w)));
        s.put("framesMostlyWaiting", framesMostlyWaiting(w));
        if (cpu.length == frames.length && frames.length > 0) {
            long[] gaps = new long[frames.length];
            for (int i = 0; i < frames.length; i++) {
                gaps[i] = Math.max(0L, frames[i] - cpu[i]);
            }
            Arrays.sort(gaps);
            s.put("wallMinusCpuP50Ms", round3(percentileMs(gaps, 50)));
            s.put("wallMinusCpuP95Ms", round3(percentileMs(gaps, 95)));
            s.put("wallMinusCpuMaxMs", round3(gaps[gaps.length - 1] / 1e6));
        }
        // THE HARNESS'S OWN COST, and the game limiter's own account of
        // itself. Both were unmeasured when the travel leg sat at exactly
        // 20.0 fps with a 1.36 ms CPU span, which left the diagnosis to
        // guesswork; they are the two candidates that were left.
        s.put("harnessPerTickMeanMs", round3(w.harness().perTickMeanMs()));
        s.put("harnessPerTickMaxMs", round3(w.harness().perTickNanosMax() / 1e6));
        s.put("harnessPerTickCalls", w.harness().perTickCalls());
        s.put("harnessPerTickTotalMs", round3(w.harness().perTickNanosTotal() / 1e6));
        s.put("harnessPerTickShareOfWallPercent", round2(w.wallNanos() > 0
                ? 100.0 * w.harness().perTickNanosTotal() / w.wallNanos() : 0));
        s.put("throttleReason", w.harness().throttleReason());
        s.put("throttleFramerateLimit", w.harness().throttleLimit());
        s.put("throttledSamples", w.harness().throttledSamples());
        s.put("heavilyThrottledSamples", w.harness().heavilyThrottledSamples());
        // REPORTED, NEVER GATED ON - see serverBehindNote in the report.
        s.put("serverBehindMaxMs", w.lag().behindMaxMillis());
        s.put("serverBehindIsScheduleDrift", true);
        s.put("serverSmoothedTickMaxMs", round2(w.lag().smoothedTickMaxMicros() / 1000.0));
        s.put("serverSamples", w.lag().samples());
        s.put("serverSamplesBehind", w.lag().samplesBehind());
        s.put("framesWhileServerBehind", w.lag().framesWhileBehind());
        String unusable = unusableReason(w);
        s.put("unusable", unusable != null);
        s.put("unusableReason", unusable == null ? "" : unusable);
        // The brief's target, evaluated rather than quoted: 10% of the
        // frame, hard-capped at 2 ms. Reported, never asserted - Phase 1
        // is the instrument, and the gate is Phase 2's business. The
        // DENOMINATOR is named in the record, because a fraction whose
        // frame is unstated is the mistake the first shakedown made.
        double denominatorMs = cpuP50 > 0 ? cpuP50 : p50;
        double allowanceMs = Math.min(TARGET_HARD_CAP_MS,
                TARGET_FRAME_FRACTION * denominatorMs);
        s.put("briefDenominatorMs", round3(denominatorMs));
        s.put("briefDenominatorSource",
                cpuP50 > 0 ? "vanilla CPU frame span p50" : "wall-clock frame p50");
        s.put("briefAllowanceMs", round3(allowanceMs));
        s.put("withinBriefTarget", w.slice().p95Nanos() / 1e6 <= allowanceMs);
        return s;
    }

    /**
     * The log's closing lines and the machine-readable record.
     *
     * <p>The JSON goes beside the screenshots, in the directory the
     * harness itself chose - taken from the {@code Path} the first
     * {@code takeScreenshot} returned rather than reconstructed, so it
     * cannot drift from wherever the framework decides to put them.</p>
     */
    private void report(ClientGameTestContext context, boolean gatesEnabled) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "meshelium-farbench-1");
        root.put("far", FAR);
        root.put("renderDistance", RD);
        root.put("lodRadiusChunks", FAR ? LOD : 0);
        root.put("seed", SEED);
        root.put("defaultSeed", "4242".equals(SEED));
        root.put("framerateLimitRequested", FPS_CAP);
        root.put("framerateLimitEffective", framerateLimitEffective);
        root.put("framerateLimitIsUnlimited",
                framerateLimitEffective >= Options.UNLIMITED_FRAMERATE_CUTOFF);
        root.put("framerateLimitNote", framerateLimitEffective
                >= Options.UNLIMITED_FRAMERATE_CUTOFF
                ? "UNLIMITED (260 is vanilla's own unlimited cutoff): frame times are "
                        + "this machine's capability and every fraction of a frame is "
                        + "against a frame far shorter than a player's"
                : "PINNED to " + framerateLimitEffective + " fps, a deliberate "
                        + "emulation of the owner's ~5 ms frame. NOT this machine's "
                        + "capability - it reached 2731 fps unpinned. Under a pin the "
                        + "wall-clock p50 is the pin; the signal is in the overruns");
        root.put("fpsCeiling", FPS_CEILING);
        root.put("teleportDestinationsPrewarmed", PREWARM);
        root.put("prewarmSeconds", prewarmSeconds);
        root.put("prewarmNote", PREWARM
                ? "the teleport destinations were GENERATED before the far field was "
                        + "armed, so those records exclude vanilla worldgen and "
                        + "understate a genuine first visit; the far field still saw "
                        + "them for the first time"
                : "the teleport destinations were generated inside their own "
                        + "measurement windows, so those records include vanilla "
                        + "worldgen and may be marked unusable");
        root.put("serverDriftThresholdMs", SERVER_BEHIND_MS);
        root.put("serverBusyTickThresholdMs", SERVER_BUSY_TICK_MS);
        root.put("unusableWaitShare", UNUSABLE_WAIT_SHARE);
        root.put("travelStepTicks", TRAVEL_STEP_TICKS);
        root.put("serverBehindNote", "serverBehindMaxMs is SCHEDULE DRIFT (now minus "
                + "nextTickTimeNanos), NOT load, and under this harness it is not a "
                + "usable verdict: the phase-3 final run measured it at 603-1404 ms on "
                + "every healthy record and 2 ms on the one record that stalled. It is "
                + "reported and never gated on. serverSmoothedTickMaxMs is the load "
                + "signal (50 ms is the tick budget), and externalWaitSharePercent is "
                + "what the unusable verdict keys on");
        root.put("speedBlocksPerTick", SPEED_BLOCKS_PER_TICK);
        root.put("speedBlocksPerSecond", round1(SPEED_BLOCKS_PER_TICK * 20));
        root.put("hopBlocks", hopBlocks());
        root.put("laneSpacingBlocks", LANE_SPACING_BLOCKS);
        root.put("loadedWindowRadiusBlocks", loadedWindowRadiusBlocks());
        root.put("scenarios", SCENARIOS);
        root.put("extractBudgetMillis", FarFieldConfig.extractBudgetMillis());
        root.put("extractFrameRateFloor", FarFieldConfig.extractFrameRateFloor());
        root.put("adaptiveExtraction", FarFieldConfig.adaptiveExtraction());
        root.put("cacheSpeed", FarFieldConfig.layerCacheSpeed(FarFieldConfig.Layer.L1));
        root.put("perfStatsArmed", ExtractDispatch.PERF_STATS);
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("frameFraction", TARGET_FRAME_FRACTION);
        target.put("hardCapMs", TARGET_HARD_CAP_MS);
        target.put("source", "docs/FARFIELD-PERF-BRIEF.md section 3");
        root.put("briefTarget", target);
        root.put("note", "per-frame nanoTime deltas at the render-thread frame hook, "
                + "plus vanilla's own CPU frame span at the same instants (cpu*, which "
                + "excludes the present and the limiter sleep and is what the budget "
                + "rule reads); far* figures are the far field's own game-thread slice, "
                + "one slice per frame; far* percentiles are bucket upper bounds within "
                + "12.5% and clamped to the exact max, frame percentiles are exact; "
                + "absolute milliseconds are primary and every fraction names the frame "
                + "it was divided by (briefDenominatorSource)");

        List<Map<String, Object>> scenarioRows = new ArrayList<>();
        List<String> unusable = new ArrayList<>();
        long totalFrames = 0;
        for (Window w : windows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", w.name());
            row.put("spike", w.spike());
            row.put("saving", w.saving());
            row.putAll(summarize(w));
            if (unusableReason(w) != null) {
                unusable.add(w.name());
            }
            row.put("counterDeltas", w.totals());
            row.put("gaugesAtEnd", w.gauges());
            scenarioRows.add(row);
            totalFrames += w.frames().length;
        }
        root.put("measured", scenarioRows);
        root.put("totalFrames", totalFrames);
        root.put("unusableScenarios", unusable);
        if (FAR) {
            root.put("pinCaptureNanos", context.computeOnClient(
                    client -> ExtractDispatch.pinCaptureNanosSummary()));
        }

        String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        Path dir = screenshotDir != null ? screenshotDir
                : FabricLoader.getInstance().getGameDir();
        Path out = dir.resolve("meshelium-farbench-" + (FAR ? "faron" : "faroff")
                + "-rd" + RD + ".json");
        try {
            Files.createDirectories(dir);
            Files.writeString(out, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not write the far-bench report to " + out, e);
        }

        // The vacuity gate, and the reason this class can be trusted at
        // all: a far-ARMED run in which the far field never stored a
        // column measured the wrong thing, however green it looks.
        //
        // Phase 3 SPLIT the first of these into two, and the split is the
        // point rather than pedantry. Captures and walks are now on
        // different threads, so "captures happened" and "walks happened"
        // are separate facts and either can be zero while the other is
        // large: a capture path that runs while the worker is wedged
        // stores nothing at a beautiful frame rate, and a run whose
        // captures never start looks identical to one where the far field
        // is off. Both were a single assertion before because both were
        // the same call.
        if (FAR) {
            long captures = 0;
            long slices = 0;
            for (Window w : windows) {
                captures += w.extract().samples();
                slices += w.slice().samples();
            }
            long walks = 0;
            for (Window w : windows) {
                walks += w.totals().getOrDefault("farExtracts", 0L);
            }
            if (captures == 0) {
                throw new AssertionError("far=true and the far field CAPTURED ZERO "
                        + "columns across " + windows.size() + " scenarios. The bench "
                        + "measured a session in which the thing it exists to price "
                        + "never ran - check that the master switch took "
                        + "(ExtractDispatch.armed()) and that the corridor covered "
                        + "fresh ground. Report still written to " + out);
            }
            if (walks == 0) {
                throw new AssertionError("far=true and " + captures + " capture steps "
                        + "ran on the game thread but the worker WALKED ZERO columns "
                        + "(farExtracts flat). Since Phase 3 those are different "
                        + "threads, so this is the failure mode that looks perfect: "
                        + "the frame cost is right and nothing is being stored. Check "
                        + "the pin worker thread and farStoreErrors. Report in " + out);
            }
            if (slices == 0) {
                throw new AssertionError("far=true and not one frame recorded any "
                        + "far-field game-thread spend, although " + captures
                        + " capture steps ran. The per-slice instrument is not wired "
                        + "to the pump; every fraction-of-frame number in " + out
                        + " is a zero that means 'unmeasured', not 'free'");
            }
        }

        log("done far=" + FAR + " scenarios=" + windows.size()
                + " totalFrames=" + totalFrames + " unusable=" + unusable
                + " shots=" + shots + " report=" + out);
        if (!unusable.isEmpty()) {
            log("READ THIS BEFORE QUOTING THE REPORT: " + unusable.size() + " of "
                    + windows.size() + " records were measured through something this "
                    + "bench does not control - see each row's unusableReason. They are "
                    + "in the JSON so the episode itself can be studied, and they are "
                    + "not distributions of this renderer's cost");
        }

        if (gatesEnabled && MAX_P99_MS > 0) {
            for (Window w : windows) {
                // An unusable record cannot fail a performance gate: its
                // p99 is vanilla's worldgen, and gating on it would turn
                // the instrument into a random number generator.
                if (!w.saving() || w.spike() || unusableReason(w) != null) {
                    continue;
                }
                double p99 = (Double) summarize(w).get("p99Ms");
                if (p99 > MAX_P99_MS) {
                    throw new AssertionError("scenario " + w.name() + " p99 " + p99
                            + " ms exceeds the requested gate of " + MAX_P99_MS
                            + " ms (meshelium.test.farbench.maxP99Ms). Full record: "
                            + out);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Counters
    // ------------------------------------------------------------------

    /**
     * The perf-relevant counter surface, as TOTALS (deltaed per window).
     *
     * <p>Deliberately a subset. The visual diagnostic dumps all
     * twenty-four far counters because it is looking for a defect; this
     * one is looking for time, so it carries the counters that either
     * cost frame time or explain why the budget rule chose what it
     * chose.</p>
     *
     * <p>Read on the client thread: the LongAdders are safe from
     * anywhere, but reading them from the harness thread while the game
     * thread writes them would give a window's endpoints different
     * instants, and a delta between two smeared reads is not a
     * measurement.</p>
     */
    private Map<String, Long> counterSnapshot(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            Map<String, Long> c = new LinkedHashMap<>();
            // What extraction did, and what it cost.
            c.put("farExtracts", ExtractDispatch.farExtracts.sum());
            c.put("farExtractCells", ExtractDispatch.farExtractCells.sum());
            c.put("farExtractNanos", ExtractDispatch.farExtractNanos.sum());
            c.put("farExtractOnDemand", ExtractDispatch.farExtractOnDemand.sum());
            c.put("farExtractSweepExtracts",
                    ExtractDispatch.farExtractSweepExtracts.sum());
            c.put("farExtractIncomplete", ExtractDispatch.farExtractIncomplete.sum());
            c.put("farExtractUpgrades", ExtractDispatch.farExtractUpgrades.sum());
            c.put("farExtractStaleFiled", ExtractDispatch.farExtractStaleFiled.sum());
            // The budget rule's own account of itself: slices opened,
            // slices that ran out, and the idle escalations the brief
            // blames for the standing-still case.
            c.put("farExtractSlices", ExtractDispatch.farExtractSlices.sum());
            c.put("farExtractSlicesSpent", ExtractDispatch.farExtractSlicesSpent.sum());
            c.put("farExtractIdleBoosts", ExtractDispatch.farExtractIdleBoosts.sum());
            c.put("farExtractBudgetRefusals",
                    ExtractDispatch.farExtractBudgetRefusals.sum());
            // The leaving path: pins are the capture-and-walk pipeline
            // that already exists, and Phase 3 proposes to route LIVE
            // through it, so its volume here is the load that change
            // would add.
            c.put("farSavePins", ExtractDispatch.farSavePins.sum());
            // Phase 3: the fill path's own volume and price. farCaptures
            // is columns FROZEN and handed to the worker;
            // farCaptureNanos / farCaptures is the per-column GAME-THREAD
            // cost, which is the number this whole wave is about, and
            // farExtractNanos / farExtracts is the per-column WORKER cost,
            // which is the work that did NOT get cheaper. farCaptureWorkerFull
            // says the pipeline is worker-bound, which on one worker
            // thread it is expected to be.
            c.put("farCaptures", ExtractDispatch.farCaptures.sum());
            c.put("farCaptureNanos", ExtractDispatch.farCaptureNanos.sum());
            c.put("farCaptureWorkerFull",
                    ExtractDispatch.farCaptureWorkerFull.sum());
            c.put("farCaptureDeferrals",
                    ExtractDispatch.farCaptureDeferrals.sum());
            c.put("farSavePinReleases", ExtractDispatch.farSavePinReleases.sum());
            c.put("farSaveCaptureSkipped", ExtractDispatch.farSaveCaptureSkipped.sum());
            c.put("farSaveLightUncaptured",
                    ExtractDispatch.farSaveLightUncaptured.sum());
            c.put("farSaveEditsCoalesced", ExtractDispatch.farSaveEditsCoalesced.sum());
            c.put("farSaveDegradedLeft", ExtractDispatch.farSaveDegradedLeft.sum());
            c.put("farSaveLost", ExtractDispatch.farSaveLost.sum());
            c.put("farSaveLeftUnsaved", ExtractDispatch.farSaveLeftUnsaved.sum());
            // The store, so a frame-time number can be read against the
            // work it bought.
            c.put("farStoreWrites", FarField.farStoreWrites.sum());
            c.put("farStoreBytesWritten", FarField.farStoreBytesWritten.sum());
            c.put("farStoreDroppedWrites", FarField.farStoreDroppedWrites.sum());
            c.put("farSaveAckOk", FarField.farSaveAckOk.sum());
            c.put("farSaveAckFailed", FarField.farSaveAckFailed.sum());
            // The near field, which is the other half of every frame and
            // the only one of these that exists in the far-OFF arm.
            TerrainResidency.Counters near = TerrainResidency.counters();
            c.put("encodedSections", near.encodedSections());
            c.put("uploadedSections", near.uploadedSections());
            if (FAR) {
                c.putAll(FarWalkerProbe.totals());
            }
            return c;
        });
    }

    /**
     * The GAUGES, absolute and at the end of a window.
     *
     * <p>Separate from {@link #counterSnapshot} on purpose: a gauge goes
     * up and down, so its "delta" across a window is the difference of
     * two instants and says nothing about what happened between them.
     * {@code farSaveBehind} at the end of the travel leg is a real
     * number; {@code farSaveBehind} deltaed is not.</p>
     */
    private Map<String, Long> gaugeSnapshot(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            Map<String, Long> g = new LinkedHashMap<>();
            g.put("jobsQueued", (long) ExtractDispatch.jobsQueued());
            g.put("editsPending", (long) ExtractDispatch.editsPending());
            g.put("pinnedCount", (long) ExtractDispatch.pinnedCount());
            g.put("pendingCaptureCount", (long) ExtractDispatch.pendingCaptureCount());
            g.put("farSaveBehind", ExtractDispatch.farSaveBehind());
            g.put("budgetMicrosNow", ExtractDispatch.budgetMicrosNow());
            g.put("captureCostMicros", ExtractDispatch.captureCostMicros());
            g.put("workerBacklogNow", (long) ExtractDispatch.workerBacklogNow());
            g.put("liveCaptureCount", (long) ExtractDispatch.liveCaptureCount());
            g.put("loadedChunks", client.level == null ? -1L
                    : (long) client.level.getChunkSource().getLoadedChunksCount());
            g.put("fps", (long) client.getFps());
            g.put("frameTimeNs", client.getFrameTimeNs());
            if (FAR) {
                g.putAll(FarWalkerProbe.gauges());
            }
            return g;
        });
    }

    private static Map<String, Long> delta(Map<String, Long> before,
            Map<String, Long> after) {
        Map<String, Long> d = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : after.entrySet()) {
            d.put(entry.getKey(),
                    entry.getValue() - before.getOrDefault(entry.getKey(), 0L));
        }
        return d;
    }

    // ------------------------------------------------------------------
    // Scene control
    // ------------------------------------------------------------------

    private static void armFarField(ClientGameTestContext context, boolean armed) {
        context.runOnClient(client -> {
            if (armed) {
                System.setProperty("meshelium.farfield.enabled", "true");
            } else {
                System.clearProperty("meshelium.farfield.enabled");
            }
            ExtractDispatch.refreshArmed();
        });
        if (armed && !ExtractDispatch.armed()) {
            throw new AssertionError("the far field did not arm: ExtractDispatch.armed() "
                    + "is false after setting meshelium.farfield.enabled. Every 'far' "
                    + "number this run could print would be a zero that means "
                    + "'never ran'");
        }
    }

    /**
     * Pin the layer-1 radius, both halves of it - the radius property and
     * the layer switch it is gated behind, because
     * {@code layerRadiusChunks} applies the layer's own on/off AFTER the
     * override and a config with layer 1 disabled would answer 0. Then
     * verify, through the same resolver the walker calls, rather than
     * assume.
     */
    private static void setFarRadius(ClientGameTestContext context, int chunks) {
        context.runOnClient(client -> {
            System.setProperty("meshelium.farfield.l1Enabled", "true");
            System.setProperty("meshelium.farfield.l1RadiusChunks",
                    Integer.toString(chunks));
        });
        if (FarFieldConfig.l1RadiusChunks() != chunks) {
            throw new AssertionError("the far radius did not take: asked for " + chunks
                    + " chunks, FarFieldConfig.l1RadiusChunks() answers "
                    + FarFieldConfig.l1RadiusChunks());
        }
    }

    /**
     * Empty the far-field cache before anything is measured.
     *
     * <p>The second run of an A/B is the reason. The store is keyed by
     * world, and a world this bench has already flown is a world whose
     * shells are already on disk - so the far field READS instead of
     * extracting and the run reports that saving is free. Cleared here,
     * before the world exists and before the master switch goes on,
     * which is the one moment {@code FarField} holds no IO thread and no
     * open region handle (the clear then runs on its own short-lived
     * thread and does not arm anything).</p>
     */
    private static void clearFarCache(ClientGameTestContext context) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        FarField.requestCacheClear(done::set);
        try {
            context.waitFor(client -> done.get() != null, 600);
        } catch (Throwable t) {
            throw new AssertionError("the far-field cache clear never answered; a run "
                    + "over a warm store measures reading, not saving, so this bench "
                    + "refuses to continue. Folder: " + FarField.cacheFolder(), t);
        }
        if (!Boolean.TRUE.equals(done.get())) {
            throw new AssertionError("the far-field cache could not be emptied ("
                    + FarField.cacheFolder() + "). A warm store turns every save "
                    + "scenario into a read scenario and the A/B would compare two "
                    + "different experiments");
        }
        log("far cache cleared: " + FarField.cacheFolder());
    }

    /**
     * Wait until the far field has nothing left to save: both job queues
     * empty, no captures pending triage, and the store's write counter
     * standing still. This is what makes "baseline-still" the FLOOR
     * rather than just a quieter scenario.
     */
    private static void waitForSaveQuiet(ClientGameTestContext context) {
        long lastWrites = -1;
        int quiet = 0;
        for (int second = 1; second <= QUIET_SECONDS; second++) {
            context.waitTicks(20);
            long writes = FarField.farStoreWrites.sum();
            int queued = context.computeOnClient(client ->
                    ExtractDispatch.jobsQueued() + ExtractDispatch.editsPending()
                            + ExtractDispatch.pendingCaptureCount());
            // TWO consecutive quiet samples, not one: the sweep files in
            // bursts, so a single sample between two bursts reads empty
            // and the floor would be taken mid-drain.
            quiet = queued == 0 && writes == lastWrites ? quiet + 1 : 0;
            if (quiet >= 2) {
                log("save queue quiet after " + second + "s (writes=" + writes
                        + ", walks=" + ExtractDispatch.farExtracts.sum() + ")");
                return;
            }
            lastWrites = writes;
            // The DRAIN CURVE, every 30s. The first shakedown exhausted a
            // two-minute budget here and said only that it had; a rate is
            // what tells the next reader whether the budget is short or
            // the drain is stuck.
            if (second % 30 == 0) {
                log("save queue draining: " + second + "s elapsed, queued=" + queued
                        + " writes=" + writes + " walks="
                        + ExtractDispatch.farExtracts.sum());
            }
        }
        log("the save queue never went quiet in " + QUIET_SECONDS + "s; the "
                + "baseline-still record is therefore NOT a no-save floor and must be "
                + "read as a light-save scenario (its own jobsQueued and farStoreWrites "
                + "are in the record). Raise meshelium.test.farbench.quietSeconds if the "
                + "drain curve above was still moving");
    }

    /** Wait until the client's loaded-chunk count stops moving; fatal on timeout. */
    private static void settleLoadedChunks(ClientGameTestContext context, String where) {
        if (!settleLoadedChunksSoft(context, where)) {
            throw new AssertionError("the client's loaded-chunk count never settled at "
                    + where + " - the world is still streaming, so nothing measured "
                    + "from here would be the scenario it claims to be");
        }
    }

    /**
     * The same settle, returning false instead of throwing. Used before
     * the idle window, where "vanilla never went quiet" is a finding
     * about the machine rather than a broken test.
     */
    private static boolean settleLoadedChunksSoft(ClientGameTestContext context,
            String where) {
        int last = -1;
        for (int i = 0; i < 120; i++) {
            int now = context.computeOnClient(client -> client.level == null ? -1
                    : client.level.getChunkSource().getLoadedChunksCount());
            if (now > 0 && now == last) {
                return true;
            }
            last = now;
            context.waitTicks(20);
        }
        log("loaded chunks still moving at " + where + " (last " + last + ")");
        return false;
    }

    /**
     * The deterministic scene, with the 26.2 gamerule names (the old
     * camelCase names fail to parse and the freeze silently never
     * happens, which over an ocean means kelp random-ticks forever and
     * the world never settles).
     */
    private static void freezeWorld(TestServerContext server) {
        server.runCommand("time set noon");
        server.runCommand("gamerule minecraft:advance_time false");
        server.runCommand("weather clear");
        server.runCommand("gamerule minecraft:advance_weather false");
        server.runCommand("gamerule minecraft:spawn_mobs false");
        server.runCommand("gamerule minecraft:random_tick_speed 0");
        server.runCommand("kill @e[type=!minecraft:player]");
    }

    /**
     * {@code execute as @p at @s run tp @s ...} with an ABSOLUTE
     * position: a relative height climbs the camera on every step and
     * the two arms of an A/B stop being a pair.
     */
    private static void teleport(TestServerContext server, double x, double y, double z) {
        server.runCommand("execute as @p at @s run tp @s "
                + fmt(x) + " " + fmt(y) + " " + fmt(z) + " " + YAW + " " + PITCH);
    }

    /** Command-safe decimal, root locale (a comma would split the argument). */
    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private void shoot(ClientGameTestContext context, String name) {
        String full = name + (FAR ? "_faron" : "_faroff");
        Path path = context.takeScreenshot(TestScreenshotOptions.of(full));
        shots.add(full);
        if (screenshotDir == null && path != null) {
            screenshotDir = path.getParent();
        }
    }

    private static void assertNoErrors() {
        String drawError = TerrainDrawer.lastError();
        if (drawError != null) {
            throw new AssertionError("terrain drawer reported an error: " + drawError);
        }
        String residencyError = TerrainResidency.lastError();
        if (residencyError != null) {
            throw new AssertionError("terrain residency reported an error: "
                    + residencyError);
        }
        if (TerrainDrawer.coveragePassive()) {
            throw new AssertionError("the coverage guard went passive: every frame after "
                    + "that point is vanilla's, not this renderer's, and the measurement "
                    + "is of the wrong program");
        }
        if (FAR && FarWalkerProbe.broken()) {
            throw new AssertionError("the far-field walker latched BROKEN; the far ring "
                    + "stopped growing at that moment and every frame after it is a "
                    + "measurement of a disabled feature");
        }
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /** Lane {@code i}'s X, one lane per scenario. */
    private static double laneX(int lane) {
        return (double) LANE_SPACING_BLOCKS * lane;
    }

    /**
     * Half-width of the square the client keeps chunks in, in blocks.
     * The client holds {@code renderDistance + 3} (the extractor's own
     * window), so two poses share no chunk once they are twice this
     * apart.
     */
    private static int loadedWindowRadiusBlocks() {
        return (RD + 3) * 16;
    }

    /**
     * Teleport hop length. The brief says "~500 blocks"; 500 is not far
     * enough at rd 16, where two windows 500 apart still share 108
     * blocks of ground, and the whole point of the scenario is that the
     * destination is FRESH. So the hop is the larger of 500 and one
     * whole window plus a 64-block margin.
     */
    private static int hopBlocks() {
        int base = Integer.getInteger("meshelium.test.farbench.hop", 500);
        return Math.max(base, 2 * loadedWindowRadiusBlocks() + 64);
    }

    /**
     * Prove the layout is fresh at the render distance this run actually
     * uses, rather than at the one the constants were written for. A
     * bench whose "fresh terrain" is terrain it loaded ten seconds ago
     * measures cache hits and calls them saves.
     */
    private static void assertGeometryFresh() {
        int window = 2 * loadedWindowRadiusBlocks();
        if (LANE_SPACING_BLOCKS <= window) {
            throw new AssertionError("the scenario lanes are " + LANE_SPACING_BLOCKS
                    + " blocks apart but a loaded window is " + window + " blocks wide "
                    + "at rd " + RD + ": the scenarios would share ground and none of "
                    + "them would be over fresh terrain");
        }
        if (hopBlocks() <= window) {
            throw new AssertionError("the teleport hop is " + hopBlocks() + " blocks "
                    + "and a loaded window is " + window + " wide at rd " + RD);
        }
        double corridor = SPEED_BLOCKS_PER_TICK * (TRAVEL_SECONDS + 5) * 20;
        if (corridor >= LANE_SPACING_BLOCKS) {
            throw new AssertionError("the travel corridor is " + round1(corridor)
                    + " blocks long, which reaches out of its own lane");
        }
    }

    // ------------------------------------------------------------------
    // Arithmetic
    // ------------------------------------------------------------------

    /** Nearest-rank percentile over an ALREADY SORTED array, milliseconds. */
    private static double percentileMs(long[] sorted, int pct) {
        int rank = Math.max(1, (int) Math.ceil(pct / 100.0 * sorted.length));
        return sorted[rank - 1] / 1e6;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static void log(String line) {
        MesheliumLog.LOGGER.info("{} {}", TAG, line);
    }

    // ------------------------------------------------------------------
    // Far-walker access, deliberately in its own class
    // ------------------------------------------------------------------

    /**
     * Every {@code FarFieldResidency} reference in this file lives here.
     *
     * <p>Same reasoning as the two other far-field test classes:
     * {@code MesheliumFarFieldTest}'s zero-cost-off leg asserts that the
     * walker class has never been LOADED in a session that never enabled
     * the far field, and the JVM links a class by verifying all of its
     * methods. A nested class is a separate class file that is not loaded
     * until something touches it, so this class merely being constructed
     * - which happens on every run where it sits in the entrypoint list -
     * cannot load the walker. The {@link #FAR} guard at every call site
     * is what keeps that true in the far-OFF arm of the A/B as well.</p>
     */
    private static final class FarWalkerProbe {

        private FarWalkerProbe() {
        }

        static boolean broken() {
            return com.deds.meshelium.farfield.FarFieldResidency.isBroken();
        }

        /** Residency work that lands on the frame, as totals. */
        static Map<String, Long> totals() {
            Map<String, Long> c = new LinkedHashMap<>();
            c.put("farAdmissions",
                    com.deds.meshelium.farfield.FarFieldResidency.farAdmissions.sum());
            c.put("farReleases",
                    com.deds.meshelium.farfield.FarFieldResidency.farReleases.sum());
            c.put("farMeshedSections",
                    com.deds.meshelium.farfield.FarFieldResidency.farMeshedSections.sum());
            c.put("farShellRequests",
                    com.deds.meshelium.farfield.FarFieldResidency.farShellRequests.sum());
            c.put("farPromoteBudgetStalls",
                    com.deds.meshelium.farfield.FarFieldResidency
                            .farPromoteBudgetStalls.sum());
            c.put("farReloads",
                    com.deds.meshelium.farfield.FarFieldResidency.farReloads.sum());
            c.put("farStandDownPumps",
                    com.deds.meshelium.farfield.FarFieldResidency.farStandDownPumps.sum());
            return c;
        }

        /** The residency gauge, absolute (admit adds, release subtracts). */
        static Map<String, Long> gauges() {
            Map<String, Long> g = new LinkedHashMap<>();
            g.put("farSectionsResidentGauge",
                    com.deds.meshelium.farfield.FarFieldResidency
                            .farSectionsResident.sum());
            return g;
        }
    }
}
