/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Wave 9: the benchmark harness. Runs INSTEAD of the other gametests —
 * build.gradle swaps the fabric-client-gametest entrypoint list to exactly
 * this class when `-Pmeshelium.bench=<scene>` is passed (and this class
 * additionally refuses to run without the property, belt and braces).
 */
package com.deds.meshelium.gametest.client;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumBenchRecorder;
import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumPlatform;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.MesheliumCpuStages;
import com.deds.meshelium.MesheliumVulkanState;
import com.deds.meshelium.terrain.host.TerrainResidency;
import com.deds.meshelium.vk.MesheliumGpuTimers;
import com.deds.meshelium.sodium.MesheliumSodiumHooks;
import com.deds.meshelium.vk.SodiumTerrainDrawer;
import com.deds.meshelium.vk.TerrainDrawer;
import com.deds.meshelium.vk.TerrainOcclusion;

import com.google.gson.GsonBuilder;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.presets.WorldPresets;


import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Map.entry;

/**
 * The wave-9 measurement protocol, per docs/PERFORMANCE.md:
 *
 * <ol>
 *   <li><b>Scene:</b> a real {@code minecraft:normal} (noise) overworld at
 *       the fixed seed {@value #SEED} (the sibling repo's worldgen-proof
 *       pattern — the gametest default world is a superflat and would
 *       benchmark nothing), render distance from the scene name
 *       ({@code plains-rd16} → 16, {@code plains-rd32} → 32; same seed,
 *       same camera — the rd sweep isolates draw volume).</li>
 *   <li><b>Determinism:</b> clouds OFF, noon, cycles/spawning/random ticks
 *       frozen, entities killed, SPECTATOR camera (no gravity — the pinned
 *       pose survives the whole run) teleported to the fixed pose
 *       {@value #CAMERA_TP}; then the parity protocol's quiesce (encode
 *       counter flat + staging backlog empty).</li>
 *   <li><b>Measure Meshelium:</b> {@value #WARMUP_FRAMES} warmup +
 *       {@value #MEASURED_FRAMES} measured CPU frame times (whole-frame
 *       deltas at {@code LevelRenderer.render} HEAD via
 *       {@link MesheliumBenchRecorder}) + per-pass GPU times
 *       ({@link MesheliumGpuTimers} capture, frame−3 lagged readback).</li>
 *   <li><b>Measure vanilla:</b> flip {@code meshelium.terrainDraw} OFF live
 *       (the shots-40/41 protocol — same session, same world, same
 *       camera), verify the drawer froze, capture the same CPU series.
 *       GPU pass times honestly do not exist for vanilla frames (Meshelium
 *       records no passes) and are reported only for the Meshelium leg.</li>
 *   <li><b>Report:</b> ALL raw series + mean/median/p95/p99 summaries to
 *       {@code build/run/clientGameTest/meshelium-bench-&lt;scene&gt;.json}
 *       (the game dir) for the coordinator, plus INFO summary lines.
 *       CPU frame times and GPU pass times stay separate everywhere —
 *       they are different clocks over different spans and are never
 *       summed.</li>
 * </ol>
 */
public final class MesheliumBenchmarkTest implements FabricClientGameTest {

    /**
     * Fixed by default so every measurement compares like with like.
     * Overridable ONLY for gallery captures, where the point is variety
     * rather than comparability: {@code -Dmeshelium.bench.seed=...} and
     * {@code -Dmeshelium.bench.time=...}. A run that overrides either one
     * records the fact in its JSON, so a stray screenshot run can never be
     * mistaken later for a measurement.
     */
    private static final String SEED = System.getProperty("meshelium.bench.seed", "4242");
    /** Time of day command argument: noon by default, sunset for a nicer shot. */
    private static final String TIME_OF_DAY = System.getProperty("meshelium.bench.time", "noon");
    /**
     * Degrees of yaw per TICK to sweep the camera through while measuring,
     * or 0 for the pinned camera every scene used before 2026-08-12.
     *
     * <p>Why this exists (owner, from real play): standing still at render
     * distance 32 they saw 720 fps, and spinning the view dropped it to
     * 550, about a quarter gone. Every camera in this harness was PINNED,
     * so every number this project has published describes a player who
     * never moves the mouse. Vanilla rebuilds its visible-section list
     * whenever the view crosses a two degree bucket, and that work lands
     * on both legs, so a static bench does not just flatter Meshelium, it
     * hides a real cost from both sides of the comparison.
     *
     * <p>1.8 degrees per tick is a full circle in ten seconds at 20 ticks
     * per second, which crosses a bucket boundary roughly every tick: a
     * deliberately unkind pan rather than a gentle drift.</p>
     */
    /**
     * Measure the HOST RENDERER ONLY and skip everything Meshelium.
     *
     * <p>Exists to get an honest OpenGL baseline. Every "plain Minecraft"
     * number this project has published came from flipping
     * {@code meshelium.terrainDraw} off inside a process already running
     * Minecraft's VULKAN backend, so it is vanilla-on-Vulkan. Minecraft
     * 26.2 boots OpenGL by default, which means that figure is not the
     * "before" almost any reader actually has. Backends are chosen at boot
     * and cannot be switched in-process, so the only way to measure OpenGL
     * is a separate run with {@code -Pmeshelium.backend=opengl}, and on
     * that backend Meshelium is completely dormant.</p>
     *
     * <p>Safe because the bench clock is deliberately hooked BEFORE the
     * gate checks in {@code LevelRendererMixin} and is documented
     * GL-path-safe. This flag additionally skips every {@code
     * TerrainDrawer} touch, so no Vulkan-only class is loaded on the GL
     * path just to report a counter that would read zero.</p>
     */
    private static final boolean VANILLA_ONLY =
            Boolean.getBoolean("meshelium.bench.vanillaOnly");

    /**
     * True when SODIUM is the host renderer and Meshelium is drawing its
     * terrain with mesh shaders — the third leg this bench learned about
     * on 2026-09-04.
     *
     * <h2>Why it needs its own flag</h2>
     * <p>The A/B this file has always run flips
     * {@code meshelium.terrainDraw} to turn Meshelium's own renderer off
     * and measure vanilla underneath it. Under Sodium that property
     * controls a path which is dormant anyway: Sodium owns the chunk
     * build and the vanilla draw, and Meshelium's arena, residency and
     * task-cull machinery never run. Flipping it would have measured the
     * same thing twice and reported it as an A/B.
     *
     * <p>The equivalent switch under Sodium is
     * {@link SodiumTerrainDrawer#setEnabled}, which makes Meshelium
     * decline the pass so Sodium's own renderer draws it. Same shape, same
     * guarantees, different lever.
     *
     * <h2>Why this matters more than a second run would</h2>
     * <p>Before this existed, "Sodium alone" and "Meshelium on Sodium"
     * could only be separate sessions. This harness repeats to 0.1% inside
     * a session and has drifted 62% BETWEEN sessions, so two renderers
     * measured in two sessions is not a comparison — it is two numbers.
     * Now both legs share a world, a camera, a driver state and a JIT
     * profile, seconds apart.
     *
     * <p>Resolved lazily rather than at class init: the mod list is
     * readable early, but whether Meshelium's adapter actually installed
     * itself is only knowable once a world has drawn a frame.</p>
     */
    private static boolean sodiumHost;

    private static final double SPIN_DEGREES_PER_TICK =
            Double.parseDouble(System.getProperty("meshelium.bench.spin", "0"));
    private static final String WORLD_NAME = "meshelium-bench";
    /** Fixed scenic pose: high air camera, 25° down — terrain to horizon. */
    /**
     * The original bench pose: 50 to 60 blocks above the surface, pitched
     * 25 degrees DOWN. Scenic, and close to the worst case for occlusion
     * culling, because from up there almost nothing hides behind anything.
     */
    private static final String CAMERA_TP = "tp @p 0.5 130.0 0.5 45 25";
    /**
     * The pose people actually play in: just above the ground, looking
     * ALONG the terrain rather than down onto it, so hills and trees
     * occlude each other the way they do in a real session. Added
     * 2026-08-12 after the owner measured occlusion culling WINNING
     * heavily in their own world while it lost in every scene this
     * harness had, which turned out to be a property of the camera and
     * not of the feature. Scenes named {@code ground-rdN} use it.
     */
    private static final String GROUND_CAMERA_TP = "tp @p 0.5 74.0 0.5 45 2";

    /**
     * Enclosed scenes: the camera is UNDERGROUND, in a small carved chamber
     * with solid stone in every direction.
     *
     * <p>Every other scene in this harness looks at open terrain, and that
     * is the one shape where occlusion culling has the least to do - almost
     * nothing is hidden, so the test costs what it saves. The owner
     * measured occlusion winning heavily in their own world while it lost
     * in every scene here, and the ground-rdN cameras were the first half
     * of the answer. This is the other half and the extreme case: indoors
     * and underground, where nearly the whole loaded world is behind
     * something and a renderer that can prove it should draw almost none of
     * it.</p>
     *
     * <p>y=30 is solid stone in this seed's plains, well below the surface
     * and above the deepslate transition. The chamber is carved after
     * worldgen settles, so the surrounding rock is real generated terrain
     * rather than a box floating in air.</p>
     */
    private static final String CAVE_CAMERA_TP = "tp @p 0.5 30.0 0.5 45 0";
    /** Carves the chamber around whatever CAVE_CAMERA_TP just pinned. */
    private static final String CAVE_CARVE =
            "execute as @p at @s run fill ~-4 ~-3 ~-4 ~4 ~3 ~4 minecraft:air";

    /**
     * Ocean scenes: the camera hangs over open deep ocean at y=105 with
     * the scenic yaw and pitch of {@link #CAMERA_TP} - sea level is 63, so
     * that is about forty blocks up, the plains framing ratio applied to
     * water.
     *
     * <p>Unlike every other pose this cannot be a compile-time constant:
     * where the nearest deep ocean is belongs to the seed, and hard-coding
     * a spot found once for 4242 would silently bench the wrong scene for
     * every seed override. It is resolved once at scene setup from the
     * server's own biome source (see {@link #resolveOceanCamera}) and the
     * resolved coordinates go into the report's knob block, so every run
     * documents where it measured.</p>
     */
    private static volatile String oceanCameraTp;
    /** The resolved deep-ocean column, for the knob block. */
    private static volatile int oceanCameraX;
    private static volatile int oceanCameraZ;

    /**
     * Forest scenes: the camera hangs over tree canopy at y=105 with the
     * scenic yaw and pitch of {@link #CAMERA_TP} - trees read fine from
     * that height for a census. Resolved at scene setup exactly like the
     * ocean pose and for the same reason: where the nearest forest is
     * belongs to the seed (see {@link #resolveForestCamera}), and the
     * resolved coordinates go into the report's knob block.
     */
    private static volatile String forestCameraTp;

    /** The eye-level twin of {@link #forestCameraTp}; see forest-eye-* scenes. */
    private static volatile String forestEyeCameraTp;

    /** Ground under leaves near the forest column; see forest-canopy-* scenes. */
    private static volatile String forestCanopyCameraTp;
    /** The resolved forest column, for the knob block. */
    private static volatile int forestCameraX;
    private static volatile int forestCameraZ;
    /**
     * Frames discarded before a leg starts counting.
     *
     * <p>Was 120, which was a guess, and it was costing real accuracy: with
     * the SAME renderer in both legs the second leg measured 5-9% faster
     * than the first, and Meshelium is always the first leg. Measured
     * 2026-09-05 on a static camera, same renderer both legs:
     *
     * <pre>
     *   120 warm-up frames   leg2/leg1 = 0.951
     *  2000 warm-up frames   leg2/leg1 = 0.993
     * </pre>
     *
     * <p>So it was JIT warm-up all along, and two thousand frames removes
     * it. That costs about two seconds a leg at a thousand frames a second
     * and buys a harness whose legs are actually comparable — which is
     * worth far more than the two seconds, because the alternative was
     * correcting for a bias whose size had to be measured every time.
     *
     * <p>Leg 3 stays. It no longer has much to correct, but it is now a
     * cheap check that the run behaved: legs 1 and 3 should agree, and
     * when they do not, something changed mid-run and the run should be
     * discarded rather than averaged.
     */
    private static final int WARMUP_FRAMES =
            Math.max(1, Integer.getInteger("meshelium.bench.warmup", 2000));
    private static final int MEASURED_FRAMES = 600;
    private static final int READY_TIMEOUT_TICKS = 1200;
    private static final int CAPTURE_TIMEOUT_TICKS = 3600;

    /**
     * Leg 1's geometry-bitmap A/B, snapshotted at the end of its capture
     * window rather than read live in writeReport: by then legs 2 and 3
     * have run and the accumulators have moved on.
     */
    private static int listMapDrainFrames;

    private static int listMapMapFrames;

    private static double listMapDrainMicros;

    private static double listMapMapMicros;

    private static double listMapDrainRegionsMean;

    private static double listMapMapRegionsMean;

    private static long listMapCompares;

    private static long listMapMismatches;

    private static final Map<String, Integer> SCENES = Map.ofEntries(
            // The release curve (owner directive 2026-08-11: "make sure we
            // focus on the fps improvements ... use different render
            // distances too. and give real numbers"). rd 8 and 24 exist so
            // the published table shows the SHAPE of the win, not just its
            // peak: the advantage grows with scene weight, and a reader on
            // a modest machine cares about the low end.
            entry("plains-rd8", 8),
            // 12 is VANILLA'S OWN DEFAULT render distance, so it is the
            // single most important cell on the published curve: it is
            // what a player who never touches the slider actually gets.
            entry("plains-rd12", 12),
            entry("plains-rd16", 16),
            entry("plains-rd24", 24),
            entry("plains-rd32", 32),
            // Wave-10 extended scenes: REQUIRE -Pmeshelium.rd=<value> too
            // (widens the option range at boot; the bench sets the option
            // BEFORE world creation, so the login ClientInformation carries
            // it - the mid-world save() lesson does not bite here, and
            // save() is called anyway for symmetry below).
            entry("plains-rd48", 48),
            entry("plains-rd64", 64),
            // The distance the owner actually plays at, and the reason this
            // entry exists: every published figure and every optimisation
            // decision so far was taken at 64 or below, while the sessions
            // that produced the bug reports were at 120. A frame at 120 has
            // roughly 3.5 times the section count of one at 64, so its
            // composition is not a scaled copy and cannot be extrapolated.
            entry("plains-rd120", 120),
            // Ground level variants, same seed and same spot, looking
            // along the terrain instead of down at it.
            entry("ground-rd8", 8),
            entry("ground-rd32", 32),
            entry("ground-rd64", 64),
            // Enclosed/underground, the occlusion-culling extreme. See
            // CAVE_CAMERA_TP for why these exist.
            entry("cave-rd32", 32),
            entry("cave-rd64", 64),
            // Open water, the translucent extreme: every surface section
            // contributes a 16x16 water sheet in the one layer the merge
            // refuses to touch. Exists to carry the flat-water census and
            // to put a real number on an ocean translucent pass, which the
            // plains rivers cannot stand in for. See oceanCameraTp for why
            // the pose is resolved at runtime rather than hard-coded.
            entry("ocean-rd32", 32),
            entry("ocean-rd64", 64),
            // Forest canopy, the cutout extreme: leaves are the dominant
            // Fancy-vs-Fast difference, and under Fancy every
            // leaf-against-leaf boundary emits both interior faces.
            // Exists to carry the cutout interior-pair census that prices
            // the "fast graphics past a distance" slider. Like the ocean
            // pose, the spot belongs to the seed and is resolved at
            // runtime (see forestCameraTp).
            entry("forest-rd32", 32),
            entry("forest-rd64", 64),
            // 2026-09-06: the owner expects the Sodium path's margin to grow
            // with distance (Sodium's per-frame list work scales with
            // sections; ours scales on the GPU), so the forest scene gets
            // the two distances above 64 the harness can reach. Both need
            // -Pmeshelium.rd=<value>; 120 is MAX_MAX_RENDER_DISTANCE.
            entry("forest-rd96", 96),
            entry("forest-rd120", 120),
            // Stage 0 of GPU-VISIBILITY-DESIGN.md: the same forest from EYE
            // level (ground under the canopy, pitch 0). The aerial forest
            // camera is a null control for occlusion — it sees over
            // everything — and the design's prize, if any, is here.
            entry("forest-eye-rd64", 64),
            entry("forest-eye-rd96", 96),
            // Under the canopy: the eye pose turned out to be a lake shore
            // (0k); this one scans the forest column's neighbourhood for dry
            // ground with at least four blocks of leaves overhead. Occlusion's
            // one fair test (0m).
            entry("forest-canopy-rd64", 64),
            entry("forest-canopy-rd96", 96));

    /**
     * Waits for a capture to fill, sweeping the camera if the spin knob is
     * armed. Yaw is advanced on the CLIENT, once per tick, exactly the
     * granularity at which vanilla re-buckets its visibility, and pitch is
     * left alone so the scene framing stays comparable with the static
     * runs. With spin at 0 this is the old passive wait.
     */
    /**
     * Sweep the camera through several full turns and let the world catch
     * up, BEFORE measuring anything.
     *
     * <p>Why (owner, 2026-08-12, and they were right): "those fps dips
     * might just be chunks loading for the first time, the chunks you
     * werent looking at". Vanilla only builds the sections its visibility
     * graph reaches, and from a pinned camera that is a wedge, not a
     * circle. The first measured spin therefore walks into thousands of
     * never-built sections and measures a BUILD STORM, not the cost of
     * turning your head. Without this warm-up the rd-64 spin looked like a
     * catastrophic 413 to 107 fps collapse, which would have been a
     * completely wrong conclusion published on the strength of one run.
     */
    private static void prewarmAllDirections(ClientGameTestContext context) {
        if (!Boolean.getBoolean("meshelium.bench.prespin")) {
            return;
        }
        for (int turn = 0; turn < 3; turn++) {
            for (int step = 0; step < 40; step++) {
                context.runOnClient(client -> {
                    if (client.player != null) {
                        client.player.setYRot(client.player.getYRot() + 9.0f);
                        client.player.yRotO = client.player.getYRot();
                    }
                });
                context.waitTicks(2);
            }
        }
        settleWorldgen(context);
        captureWorldShape(context);
        quiesce(context);
    }

    private static void awaitCapture(ClientGameTestContext context, int total) {
        if (SPIN_DEGREES_PER_TICK == 0) {
            context.waitFor(client -> MesheliumBenchRecorder.filled() >= total,
                    CAPTURE_TIMEOUT_TICKS);
            return;
        }
        // The yaw is a function of FRAMES CAPTURED, not of ticks elapsed.
        //
        // It used to advance by a fixed amount per tick, and that quietly
        // made the legs incomparable. Ticks are wall-clock (20 a second)
        // while a capture counts frames, so a leg that runs faster
        // finishes in fewer ticks and sweeps a SHORTER ARC than a slower
        // one. Two legs then measured different amounts of different
        // scenery and the difference was reported as a renderer
        // comparison. Measured 2026-09-05: with the same renderer in both
        // legs, spin on, the second leg came out 32% faster; with the spin
        // off, 9.3%.
        //
        // Driving the angle from the frame count makes every leg sweep the
        // identical arc over the identical frames, whatever frame rate it
        // runs at. The degrees-per-tick knob keeps its name and its
        // meaning at 20 fps so old invocations still mean something.
        final float startYaw = currentYaw(context);
        final double perFrame = SPIN_DEGREES_PER_TICK / 20.0;
        for (int tick = 0; tick < CAPTURE_TIMEOUT_TICKS; tick++) {
            int filled = MesheliumBenchRecorder.filled();
            if (filled >= total) {
                return;
            }
            final float yaw = (float) (startYaw + perFrame * filled);
            context.runOnClient(client -> {
                if (client.player != null) {
                    client.player.setYRot(yaw);
                    client.player.yRotO = yaw;
                }
            });
            context.waitTicks(1);
        }
        throw new AssertionError("capture never filled while spinning: "
                + MesheliumBenchRecorder.filled() + " of " + total + " frames");
    }

    private static String cameraFor(String scene) {
        if (scene.startsWith("ground-")) {
            return GROUND_CAMERA_TP;
        }
        if (scene.startsWith("cave-")) {
            return CAVE_CAMERA_TP;
        }
        if (scene.startsWith("ocean-")) {
            if (oceanCameraTp == null) {
                throw new AssertionError("ocean camera requested before the "
                        + "deep-ocean lookup ran");
            }
            return oceanCameraTp;
        }
        if (scene.startsWith("forest-canopy-")) {
            if (forestCanopyCameraTp == null) {
                throw new AssertionError("canopy forest camera requested before the "
                        + "forest lookup ran");
            }
            return forestCanopyCameraTp;
        }
        if (scene.startsWith("forest-eye-")) {
            if (forestEyeCameraTp == null) {
                throw new AssertionError("eye-level forest camera requested before the "
                        + "forest lookup ran");
            }
            return forestEyeCameraTp;
        }
        if (scene.startsWith("forest-")) {
            if (forestCameraTp == null) {
                throw new AssertionError("forest camera requested before the "
                        + "forest lookup ran");
            }
            return forestCameraTp;
        }
        return CAMERA_TP;
    }

    /**
     * Finds the nearest deep ocean to world spawn and pins the ocean pose
     * over it. The query goes straight to the overworld's biome source
     * with vanilla's own {@code /locate biome} parameters (radius 6400,
     * steps 32 horizontal / 64 vertical), so the answer is a function of
     * the seed and nothing else - no chunks need generating to compute it.
     * No deep ocean in range is a hard failure: benching some other biome
     * under an "ocean-" label would be worse than no number at all.
     */
    private static void resolveOceanCamera(TestServerContext server) {
        BlockPos found = server.computeOnServer(mc -> {
            var level = mc.overworld();
            var hit = level.findClosestBiome3d(biome -> biome.is(Biomes.DEEP_OCEAN),
                    level.getRespawnData().pos(), 6400, 32, 64);
            return hit == null ? null : hit.getFirst();
        });
        if (found == null) {
            throw new AssertionError("no deep_ocean within 6400 blocks of spawn for seed "
                    + SEED + " - refusing to bench the wrong scene");
        }
        oceanCameraX = found.getX();
        oceanCameraZ = found.getZ();
        oceanCameraTp = "tp @p " + (oceanCameraX + 0.5) + " 105.0 "
                + (oceanCameraZ + 0.5) + " 45 25";
    }

    /**
     * Finds the nearest tree-heavy biome to world spawn and pins the
     * forest pose over it: {@link #resolveOceanCamera}'s protocol with a
     * canopy predicate. Any of forest, dark_forest or jungle will do -
     * the census wants leaf-against-leaf boundaries, not one species - so
     * a single query takes the nearest of the three. No match in range is
     * a hard failure for the same reason as the ocean's: benching plains
     * under a "forest-" label would be worse than no number at all.
     */
    private static void resolveForestCamera(TestServerContext server) {
        BlockPos found = server.computeOnServer(mc -> {
            var level = mc.overworld();
            var hit = level.findClosestBiome3d(biome -> biome.is(Biomes.FOREST)
                            || biome.is(Biomes.DARK_FOREST) || biome.is(Biomes.JUNGLE),
                    level.getRespawnData().pos(), 6400, 32, 64);
            return hit == null ? null : hit.getFirst();
        });
        if (found == null) {
            throw new AssertionError("no forest/dark_forest/jungle within 6400 blocks "
                    + "of spawn for seed " + SEED + " - refusing to bench the wrong scene");
        }
        forestCameraX = found.getX();
        forestCameraZ = found.getZ();
        forestCameraTp = "tp @p " + (forestCameraX + 0.5) + " 105.0 "
                + (forestCameraZ + 0.5) + " 45 25";
        // Ground under the canopy at the same column: the server generates
        // that one chunk synchronously if it has not yet (the settle that
        // follows the teleport generates the rest). Eye height 1.62 above
        // the motion-blocking surface without leaves, pitch 0.
        int surface = server.computeOnServer(mc -> mc.overworld().getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, forestCameraX, forestCameraZ));
        forestEyeCameraTp = "tp @p " + (forestCameraX + 0.5) + " " + (surface + 1.62) + " "
                + (forestCameraZ + 0.5) + " 45 0";
        // Under the canopy: walk outward in 4-block rings for a column whose
        // ground (motion-blocking without leaves) is above sea level and
        // whose leaf-inclusive height is at least 4 above it. Generates the
        // few chunks it touches on the server thread.
        int[] canopy = server.computeOnServer(mc -> {
            var level = mc.overworld();
            for (int r = 0; r <= 48; r += 4) {
                for (int dx = -r; dx <= r; dx += 4) {
                    for (int dz = -r; dz <= r; dz += 4) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        int x = forestCameraX + dx;
                        int z = forestCameraZ + dz;
                        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                        if (ground > 63 && top - ground >= 4) {
                            return new int[] {x, ground, z};
                        }
                    }
                }
            }
            return null;
        });
        if (canopy == null) {
            System.out.println("[Meshelium] bench: no canopy column within 48 blocks of the forest "
                    + "lookup; forest-canopy-* falls back to the eye (shore) pose");
            forestCanopyCameraTp = forestEyeCameraTp;
        } else {
            forestCanopyCameraTp = "tp @p " + (canopy[0] + 0.5) + " " + (canopy[1] + 1.62) + " "
                    + (canopy[2] + 0.5) + " 45 0";
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        String scene = MesheliumBenchRecorder.sceneName();
        if (scene == null) {
            return; // not a bench run (belt and braces — build.gradle
                    // already keeps this class out of normal entrypoints)
        }
        // Vanilla-only runs are the ONE case that legitimately wants
        // another backend: measuring what a player on Minecraft's default
        // OpenGL renderer actually gets, which cannot be done from a Vulkan
        // process because the backend is fixed at boot.
        if (!VANILLA_ONLY
                && !"vulkan".equalsIgnoreCase(
                        System.getProperty("meshelium.test.expectBackend", ""))) {
            throw new AssertionError("benchmark requires -Pmeshelium.backend=vulkan "
                    + "(or -Dmeshelium.bench.vanillaOnly=true for a host-renderer baseline)");
        }
        Integer renderDistance = SCENES.get(scene);
        if (renderDistance == null) {
            throw new AssertionError("unknown bench scene '" + scene + "' (known: "
                    + SCENES.keySet() + ")");
        }
        benchRenderDistance = renderDistance;

        // What the VANILLA leg actually held. Written into the report so a
        // reader can tell a real extended baseline from a clamped one.
        int[] vanillaRenderDistance = new int[] {-1};

        context.runOnClient(client -> {
            client.options.cloudStatus().set(CloudStatus.OFF);
            client.options.renderDistance().set(renderDistance);
            // Wave-10 lesson: a programmatic set() must save() to broadcast
            // ClientInformation - harmless pre-login, essential if the
            // server connection already exists.
            client.options.save();
            // THE 30FPS TRAP (first bench run, 2026-08-10): both legs
            // measured EXACTLY 33.4ms because vanilla's inactivity limiter
            // caps an unfocused window to 30fps after a minute — and the
            // harness window is never focused. MINIMIZED-only, vsync off,
            // framerate uncapped (>= UNLIMITED_FRAMERATE_CUTOFF), or the
            // benchmark measures the pacing cap instead of the renderer.
            client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
            client.options.enableVsync().set(false);
            client.options.framerateLimit().set(260);
            // -Dmeshelium.bench.flatLighting=true turns vanilla's Smooth
            // Lighting OFF, and it is the cheapest possible test of the
            // whole greedy-meshing thesis.
            //
            // The measurement says 64 percent of terrain cannot merge
            // because each quad's four corners disagree, which is vanilla
            // baking ambient occlusion per vertex. With smooth lighting off
            // vanilla takes prepareQuadFlat instead: one colour and one light
            // coord for the whole quad. That is exactly the uniform case the
            // merge needs, produced by vanilla itself, with no shader work,
            // no light volume on the GPU and no AO reproduction.
            //
            // So this leg answers "if lighting were not per-vertex, would the
            // merge actually pay" using real decoded geometry rather than a
            // model of it. If it lands near the 35.5 percent the struck-key
            // probe predicts, the thesis is confirmed and the shader work is
            // worth starting. If it does not, the prediction was wrong and
            // weeks have been saved.
            if (Boolean.getBoolean("meshelium.bench.flatLighting")) {
                client.options.ambientOcclusion().set(false);
                client.options.save();
            }
        });

        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(settings -> {
                    settings.setName(WORLD_NAME);
                    settings.setSeed(SEED);
                    settings.setAllowCommands(true);
                    settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    // A REAL noise world (the sibling worldgen-proof
                    // pattern): the preset holder comes from the creation
                    // context's own registry access.
                    settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(
                            settings.getSettings().worldgenLoadContext()
                                    .lookupOrThrow(Registries.WORLD_PRESET)
                                    .getOrThrow(WorldPresets.NORMAL)));
                })
                .create()) {

            var server = singleplayer.getServer();
            // NOT waitForChunksRender(): a NORMAL world generating at bench
            // render distance outruns the framework helper's fixed timeout
            // (first bench run: ~60s timeout vs still-streaming worldgen at
            // rd16, client at ~30fps under generation load). Settle on
            // Meshelium's own residency stability instead, with a
            // worldgen-sized budget.
            settleWorldgen(context);
            captureWorldShape(context);

            // Ocean and forest scenes resolve their pose before any command
            // references it; the settles around the tp below then absorb the
            // worldgen the far teleport triggers, exactly as they do for
            // every scene.
            if (scene.startsWith("ocean-")) {
                resolveOceanCamera(server);
            }
            if (scene.startsWith("forest-")) {
                resolveForestCamera(server);
            }

            // Deterministic freezes (the parity protocol's set) + the
            // pinned spectator camera.
            // 26.2 renamed every gamerule (GameRuleRegistryFix in the jar
            // carries the old-to-new table; the old camelCase names fail to
            // parse and the freeze silently never happened - benches ran
            // with daylight advancing and random ticks at 3 until this).
            // Harmless on plains pairs, fatal over oceans: kelp and
            // seagrass random-tick forever and worldgen never settles.
            server.runCommand("time set " + TIME_OF_DAY);
            server.runCommand("gamerule minecraft:advance_time false");
            server.runCommand("weather clear");
            server.runCommand("gamerule minecraft:advance_weather false");
            server.runCommand("gamerule minecraft:spawn_mobs false");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("gamemode spectator @p");
            server.runCommand(cameraFor(scene));
            if (scene.startsWith("cave-")) {
                // Carve AFTER the camera is pinned and worldgen has settled,
                // so the chamber is cut out of real generated stone rather
                // than being a box floating in ungenerated space.
                server.runCommand(CAVE_CARVE);
            }
            server.runCommand("kill @e[type=!minecraft:player]");
            settleWorldgen(context);
            captureWorldShape(context);

            // Meshelium must be live before anything is measured. Skipped
            // in vanilla-only mode, where it never will be: on the GL
            // backend the gate keeps the drawer dormant by design, and this
            // wait would simply time out.
            if (!VANILLA_ONLY) {
                // Which drawer to wait for depends on who the host is, and
                // waiting for the wrong one is not a slow failure but a
                // guaranteed timeout: under Sodium, TerrainDrawer never
                // records a frame in its life.
                // Already resolved by settleWorldgen, which runs first and
                // needs it too; re-read rather than assume ordering.
                sodiumHost = MesheliumPlatform.isModLoaded(MesheliumGate.SODIUM_MOD_ID);
                if (sodiumHost) {
                    context.waitFor(client -> SodiumTerrainDrawer.framesDrawn() > 0
                            && SodiumTerrainDrawer.totalSectionsDrawn() > 0,
                            READY_TIMEOUT_TICKS);
                    context.runOnClient(client -> {
                        if (SodiumTerrainDrawer.broken()) {
                            throw new AssertionError(
                                    "Meshelium's Sodium draw failed before the measurement "
                                            + "started, so leg 1 would be Sodium measuring "
                                            + "itself: " + SodiumTerrainDrawer.error());
                        }
                    });
                } else {
                    context.waitFor(client -> TerrainDrawer.framesDrawn() > 0
                            && TerrainDrawer.lastDrawnSections() > 0, READY_TIMEOUT_TICKS);
                    assertNoErrors();
                }
            }
            quiesce(context);
            // Build what a pinned camera never looks at, so a spinning
            // measurement measures spinning (see prewarmAllDirections).
            prewarmAllDirections(context);
            context.takeScreenshot(TestScreenshotOptions.of("90_bench_" + scene));

            // ---- leg 1: Meshelium (CPU frame series + GPU pass series +
            // wave-12 CPU stage rows — armed by default on bench runs) ----
            int total = WARMUP_FRAMES + MEASURED_FRAMES;
            resetToBenchYaw(context); // records the angle; legs 2 and 3 return to it
            context.runOnClient(client -> {
                MesheliumGpuTimers.armCapture(total);
                MesheliumBenchRecorder.arm(total);
                MesheliumCpuStages.armCapture(total);
                // The geometry-bitmap A/B counts only THIS window. From
                // game start it would be dominated by world generation,
                // where the loop is nearly free (see resetListMapCounters).
                SodiumTerrainDrawer.resetListMapCounters();
            });
            awaitCapture(context, total);
            // Read before the drain ticks below, so the window is leg 1's
            // and the numbers are the pose everything else on this row is.
            listMapDrainFrames = SodiumTerrainDrawer.listMapDrainFrames();
            listMapMapFrames = SodiumTerrainDrawer.listMapMapFrames();
            listMapDrainMicros = SodiumTerrainDrawer.listMapDrainMicros();
            listMapMapMicros = SodiumTerrainDrawer.listMapMapMicros();
            listMapDrainRegionsMean = SodiumTerrainDrawer.listMapDrainRegionsMean();
            listMapMapRegionsMean = SodiumTerrainDrawer.listMapMapRegionsMean();
            listMapCompares = SodiumTerrainDrawer.listMapCompares();
            listMapMismatches = SodiumTerrainDrawer.listMapMismatches();
            // Let the lagged GPU readback drain what it can, then stop.
            context.waitTicks(10);
            context.runOnClient(client -> {
                MesheliumGpuTimers.disarmCapture();
                MesheliumCpuStages.disarmCapture();
            });
            long[] mesheliumCpu = tail(MesheliumBenchRecorder.snapshot(), MEASURED_FRAMES);
            // The frustum lever's per-frame run series (Sodium host only;
            // zeros elsewhere). Stashed for writeReport rather than
            // threaded through its parameter list.
            mesheliumRunsPerFrame = tail(MesheliumBenchRecorder.snapshotRuns(), MEASURED_FRAMES);
            mesheliumRunsRejectedPerFrame =
                    tail(MesheliumBenchRecorder.snapshotRunsRejected(), MEASURED_FRAMES);
            long[] gpuRows = MesheliumGpuTimers.captureSnapshot();
            int gpuRowCount = gpuRows.length / MesheliumGpuTimers.PASSES;
            // Every counter below reaches into TerrainDrawer, which is a
            // Vulkan-only class. In vanilla-only mode it is dormant (GL) and
            // every figure would read zero, so skip rather than load it.
            Map<String, Object> mesheliumStages = VANILLA_ONLY ? Map.of() : cpuStagesReport();
            Map<String, Object> mesheliumCounters = VANILLA_ONLY
                    ? Map.of() : (sodiumHost ? sodiumCounters() : counters());
            if (!VANILLA_ONLY && !sodiumHost) {
                assertNoErrors();
            }
            // Wave-12: a skipVanillaPrep leg is only a valid A/B when the
            // prediction never missed (each miss = one vanilla frame drawn
            // from an empty prep — a frame the comparison must not contain).
            if (!VANILLA_ONLY && !sodiumHost && MesheliumConfig.skipVanillaPrepEnabled()
                    && TerrainDrawer.prepSkipHoleFrames() > 0) {
                throw new AssertionError("skipVanillaPrep hole frames > 0 ("
                        + TerrainDrawer.prepSkipHoleFrames()
                        + ") - the leg is invalid; see the drawer WARN for the first throw");
            }

            // ---- leg 2: vanilla baseline (the live property flip) ----
            // Suppress the clamp-back FIRST, so the vanilla leg holds the
            // extended distance instead of snapping to 32 (owner directive
            // 2026-08-11: "for vanilla please find some way to set it above
            // 32... it doesnt have to be pure vanilla just mesh
            // rendering"). Without this the extended rows have no baseline
            // at all and can only be quoted as absolute numbers. The
            // property is bench-only and is cleared in the finally below,
            // so no other leg and no shipped path ever sees it.
            // In vanilla-only mode there is nothing to flip off: the host
            // renderer already drew leg 1, so leg 2 is a second sample of
            // the same thing. Both are kept, and the report marks the run,
            // so a reader cannot mistake it for an A/B.
            final int framesAtFlip;
            final long sodiumFramesAtFlip;
            if (VANILLA_ONLY) {
                framesAtFlip = 0;
                sodiumFramesAtFlip = 0L;
            } else if (sodiumHost) {
                // Leg 2 under Sodium is SODIUM ALONE, reached by making
                // Meshelium decline the pass. The clamp suppression still
                // applies: the render distance must not snap back to 32
                // between legs or the two are drawing different worlds.
                context.runOnClient(client -> System.setProperty(
                        com.deds.meshelium.MesheliumExtendedRd.PROPERTY_BENCH_NO_CLAMP, "true"));
                context.runOnClient(client -> SodiumTerrainDrawer.setEnabled(false));
                context.waitTicks(5);
                long at = SodiumTerrainDrawer.framesDrawn();
                context.waitTicks(5);
                if (SodiumTerrainDrawer.framesDrawn() != at) {
                    throw new AssertionError(
                            "Meshelium kept drawing after the Sodium flip, so leg 2 would not be "
                                    + "a clean Sodium baseline (" + at + " -> "
                                    + SodiumTerrainDrawer.framesDrawn() + ")");
                }
                framesAtFlip = 0;
                sodiumFramesAtFlip = at;
            } else {
                context.runOnClient(client -> System.setProperty(
                        com.deds.meshelium.MesheliumExtendedRd.PROPERTY_BENCH_NO_CLAMP, "true"));
                context.runOnClient(client -> System.setProperty(TerrainDrawer.PROPERTY, "false"));
                context.waitTicks(5);
                framesAtFlip = TerrainDrawer.framesDrawn();
                context.waitTicks(5);
                if (TerrainDrawer.framesDrawn() != framesAtFlip) {
                    throw new AssertionError("drawer kept recording after the flip - the vanilla "
                            + "leg would not be a clean baseline");
                }
                // Vanilla has just been handed terrain it was not drawing
                // and has to rebuild it. Measuring that is measuring a
                // rebuild, not a renderer.
                settleAfterFlip(context, false);
                sodiumFramesAtFlip = 0L;
            }
            resetToBenchYaw(context);
            context.runOnClient(client -> {
                MesheliumBenchRecorder.arm(total);
                MesheliumCpuStages.armCapture(total);
            });
            awaitCapture(context, total);
            context.runOnClient(client -> MesheliumCpuStages.disarmCapture());
            long[] vanillaCpu = tail(MesheliumBenchRecorder.snapshot(), MEASURED_FRAMES);
            // The matching vanilla frame, same camera, same world, same
            // session, taken while the kill switch is still off. Paired
            // with shot 90 this is the honest before and after: identical
            // picture, different frame rate, and the numbers to label them
            // with are in this run's own JSON.
            context.takeScreenshot(TestScreenshotOptions.of("91_bench_" + scene + "_vanilla"));
            Map<String, Object> vanillaStages = VANILLA_ONLY ? Map.of() : cpuStagesReport();
            if (!VANILLA_ONLY && sodiumHost) {
                // Prove the baseline was real before restoring: if
                // Meshelium had somehow drawn during leg 2, that leg is
                // Meshelium-on-Sodium wearing Sodium's label, and reporting
                // it as a baseline would be worse than not measuring.
                if (SodiumTerrainDrawer.framesDrawn() != sodiumFramesAtFlip) {
                    throw new AssertionError(
                            "Meshelium drew during the Sodium-alone leg (" + sodiumFramesAtFlip
                                    + " -> " + SodiumTerrainDrawer.framesDrawn()
                                    + "), so that leg is not a baseline");
                }
                context.runOnClient(client -> SodiumTerrainDrawer.setEnabled(true));
            } else if (!VANILLA_ONLY) {
                context.runOnClient(client -> System.setProperty(TerrainDrawer.PROPERTY, "true"));
            }
            // The clamp suppression is deliberately NOT cleared here any
            // more. It used to be, and leg 3 once came back at 11.668 ms
            // against leg 1's 1.070 — twelve times slower, sustained over
            // 600 frames, which is not a hitch but a different world.
            // Clearing it lets the extended render distance re-evaluate
            // between legs, and a render-distance change rebuilds every
            // chunk; leg 3 was then measuring the rebuild. All three legs
            // must draw the same world or none of them compare.
            // Record what the vanilla leg actually held, so a reader can
            // tell a real baseline from a clamped one without trusting the
            // harness: the JSON carries the number, not a promise.
            context.runOnClient(client ->
                    vanillaRenderDistance[0] = client.options.getEffectiveRenderDistance());
            if (!VANILLA_ONLY && sodiumHost) {
                context.waitFor(client -> SodiumTerrainDrawer.framesDrawn() > sodiumFramesAtFlip,
                        READY_TIMEOUT_TICKS);
            } else if (!VANILLA_ONLY) {
                context.waitFor(client -> TerrainDrawer.framesDrawn() > framesAtFlip,
                        READY_TIMEOUT_TICKS);
                // And now the rebuild in the other direction: Meshelium's
                // residency has to refill before leg 3 is a measurement.
                settleAfterFlip(context, true);
                assertNoErrors();
            }

            // ---- leg 3: MESHELIUM AGAIN, and the reason it exists ----
            //
            // Measured 2026-09-05, and it invalidates a naive reading of
            // every two-leg number this harness has ever produced: with
            // the SAME renderer in both slots, leg 2 came out 32% faster
            // than leg 1 (0.809 ms against 1.185). Whatever runs second
            // looks better — JIT, clock ramp, first-touch page faults, all
            // of them plausible and none of them the renderer.
            //
            // Leg 1 is Meshelium, so Meshelium has been measured in the
            // DISADVANTAGED slot throughout. That makes the reported
            // advantage conservative, which is the safe direction to be
            // wrong in, but "conservative by an unknown amount" is not a
            // measurement.
            //
            // So: measure Meshelium a second time, after Sodium. Leg 1 and
            // leg 3 bracket the order effect, and the honest figure for
            // Meshelium is their mean against leg 2's Sodium in the middle.
            // A reader who wants the pessimistic number can still use leg 1
            // alone; both are in the report.
            long[] mesheliumLateCpu = new long[0];
            if (!VANILLA_ONLY) {
                quiesce(context);
                resetToBenchYaw(context);
                context.runOnClient(client -> {
                    MesheliumBenchRecorder.arm(total);
                });
                awaitCapture(context, total);
                mesheliumLateCpu = tail(MesheliumBenchRecorder.snapshot(), MEASURED_FRAMES);
                // Same renderer as leg 1 on the same world, so a large gap
                // is a broken leg rather than a slow one, and averaging a
                // broken leg into the headline is worse than not measuring.
                double earlyMs = meanMs(mesheliumCpu);
                double lateMs = meanMs(mesheliumLateCpu);
                if (earlyMs > 0 && (lateMs > earlyMs * 3.0 || lateMs * 3.0 < earlyMs)) {
                    throw new AssertionError("leg 3 (" + round3(lateMs) + " ms) is nowhere near "
                            + "leg 1 (" + round3(earlyMs) + " ms) with the same renderer on the "
                            + "same world - something changed between the legs and the order "
                            + "correction would be built on it");
                }
                int lateRd = clientRenderDistance(context);
                if (lateRd != vanillaRenderDistance[0]) {
                    throw new AssertionError("render distance moved between the legs ("
                            + vanillaRenderDistance[0] + " -> " + lateRd + "), so they did not "
                            + "draw the same world");
                }
                context.runOnClient(client -> System.clearProperty(
                        com.deds.meshelium.MesheliumExtendedRd.PROPERTY_BENCH_NO_CLAMP));
            }

            // The resolution the run ACTUALLY got. Read on the client
            // thread, like every other client read in this file.
            //
            // TWO sizes, because in a gametest they can differ and only one
            // of them is the one GPU cost scales with. Fabric's
            // fabric-client-gametest-api WindowMixin VIRTUALISES the
            // window: it cancels the GLFW resize and framebuffer-resize
            // callbacks, keeps the OS truth in its own realFramebufferWidth
            // /Height fields, and writes the harness-requested size into
            // vanilla's framebufferWidth/Height (verified by javap on
            // WindowMixin and by the mixin apply lines in debug.log).
            // Vanilla's fields are what the render target is sized from, so
            // getWidth/getHeight ARE the pixels being shaded and are the
            // number that belongs beside a per-pixel cost. The real OS
            // framebuffer is queried straight from the windowing layer so a clamped or
            // refused window is visible rather than silently equal.
            int[] fb = new int[6];
            context.runOnClient(client -> {
                fb[0] = client.getWindow().getWidth();
                fb[1] = client.getWindow().getHeight();
                fb[2] = client.getWindow().getScreenWidth();
                fb[3] = client.getWindow().getScreenHeight();
                int[] real = HarnessCompat.queryFramebufferSize(client.getWindow());
                fb[4] = real[0];
                fb[5] = real[1];
            });

            // ---- report ----
            writeReport(scene, renderDistance, vanillaRenderDistance[0], mesheliumCpu,
                    mesheliumLateCpu,
                    vanillaCpu, gpuRows, gpuRowCount, mesheliumCounters, mesheliumStages,
                    vanillaStages, fb);
        }
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    /** Leg 1's per-frame run series, for {@link #writeReport}; see MesheliumBenchRecorder.snapshotRuns. */
    private static long[] mesheliumRunsPerFrame = new long[0];
    private static long[] mesheliumRunsRejectedPerFrame = new long[0];

    private static void writeReport(String scene, int renderDistance,
            int vanillaRenderDistance, long[] mesheliumCpu,
            long[] mesheliumLateCpu, long[] vanillaCpu, long[] gpuRows, int gpuRowCount,
            Map<String, Object> mesheliumCounters, Map<String, Object> mesheliumStages,
            Map<String, Object> vanillaStages, int[] framebufferDims) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "meshelium-bench-1");
        root.put("scene", scene);
        root.put("renderDistance", renderDistance);
        // The distance the VANILLA leg actually ran at. Equal to
        // renderDistance means a true same-distance baseline; a smaller
        // number means the clamp-back fired and the legs are NOT
        // comparable as a ratio.
        root.put("vanillaRenderDistance", vanillaRenderDistance);
        root.put("effectiveRenderDistance", effectiveRenderDistanceAtSettle);
        root.put("clientLoadedChunks", loadedChunksAtSettle);
        root.put("seed", SEED);
        root.put("timeOfDay", TIME_OF_DAY);
        root.put("spinDegreesPerTick", SPIN_DEGREES_PER_TICK);
        // Every leg starts from the same yaw, so they sweep the same arc.
        // Without this the legs measured different scenery and two thirds
        // of the apparent order effect was the view, not the renderer.
        root.put("legsShareStartYaw", true);
        // The spin is driven by frames captured rather than ticks elapsed,
        // so every leg sweeps the same arc regardless of its frame rate.
        root.put("spinIsFrameDriven", true);
        root.put("prewarmedAllDirections", Boolean.getBoolean("meshelium.bench.prespin"));
        root.put("defaultScene", "4242".equals(SEED) && "noon".equals(TIME_OF_DAY)
                && SPIN_DEGREES_PER_TICK == 0);
        root.put("camera", cameraFor(scene) + " (spectator)");
        root.put("device", MesheliumVulkanState.deviceName());
        root.put("driver", MesheliumVulkanState.driverInfo());
        root.put("caps", String.valueOf(MesheliumVulkanState.caps()));
        root.put("timestampPeriodNs", MesheliumGpuTimers.timestampPeriodNs());
        Map<String, Object> knobs = new LinkedHashMap<>();
        knobs.put("meshWorkgroupQuads", TerrainDrawer.meshWorkgroupQuads());
        knobs.put("taskWorkgroupSections", TerrainDrawer.taskWorkgroupSections());
        knobs.put("frontToBack", System.getProperty(TerrainDrawer.PROPERTY_FRONT_TO_BACK, "true"));
        // The EFFECTIVE value, not the property. This line used to report
        // System.getProperty(..., "false"), which is wrong on every AMD
        // device: the property is normally absent and the default comes from
        // isMeasuredAmdDevice(). Every bench JSON taken on the dev card
        // therefore claimed multiWG was off while the renderer logged it as
        // active and ran it. The knob block exists precisely so a leg cannot
        // be misfiled, and this entry was misfiling all of them.
        knobs.put("translucentMultiWG", TerrainDrawer.translucentMultiWGFrames() > 0);
        // Wave-12 knobs + arm state — the JSON must say which candidates
        // were live so no leg can be misfiled during the sweep.
        knobs.put("skipVanillaPrep", MesheliumConfig.skipVanillaPrepEnabled());
        knobs.put("cachedCull", MesheliumConfig.cachedCullEnabled());
        knobs.put("cpuStagesArmed", MesheliumCpuStages.ARMED);
        // The varying layout and the lightmap stub are per-session pipeline
        // knobs too; a row filed as "unpacked" from a mistyped property was
        // indistinguishable from a packed run until these two lines.
        knobs.put("packedVaryings", TerrainDrawer.packedVaryings());
        knobs.put("lightStub", Boolean.getBoolean("meshelium.bench.lightStub"));
        // The frustum lever on the Sodium list path (2026-09-13).
        knobs.put("sodiumFrustum", System.getProperty("meshelium.sodium.frustum", "off"));
        knobs.put("sodiumFrustumCount", Boolean.getBoolean("meshelium.sodium.diag.frustumCount"));
        knobs.put("sodiumEmptyPasses", Integer.getInteger("meshelium.sodium.diag.emptyPasses", 0));
        knobs.put("jitter", MesheliumBenchRecorder.JITTER);
        knobs.put("sodiumMergePhaseA", SodiumTerrainDrawer.mergePhaseA());
        knobs.put("sodiumOccBoxesPerWG", SodiumTerrainDrawer.OCC_BOXES_PER_WG);
        knobs.put("sodiumTaskSkip", SodiumTerrainDrawer.diagTaskSkip());
        knobs.put("sodiumOneDrawPerGroup", SodiumTerrainDrawer.oneDrawPerGroup());
        // NEXT (c), the half-resolution occlusion depth. EFFECTIVE values,
        // by the BENCH:1029-1035 rule: reading the PROPERTY would file a
        // cell as "on" in which no half frame ever ran (a null-device
        // ensure, occlusion never armed at that pose, a mid-run latch after
        // the capture window, an arm skip on a non-canonical projection) -
        // the exact misfiling translucentMultiWG was fixed for.
        knobs.put("occlusionHalfRes",
                TerrainOcclusion.halfResFrames() > 0 && !TerrainOcclusion.halfResBroken());
        knobs.put("occlusionHalfResMode", halfResModeText());
        knobs.put("occlusionHalfResSize", TerrainOcclusion.halfResFrames() == 0 ? ""
                : TerrainOcclusion.halfResWidth() + "x" + TerrainOcclusion.halfResHeight());
        // Part B: "supported" is the extension's presence (probed on every
        // boot); "conservativeRaster" is present AND requested at device
        // creation, which is the only thing the pipelines could have baked.
        knobs.put("conservativeRasterSupported",
                MesheliumVulkanState.conservativeRasterizationSupported());
        knobs.put("conservativeRaster", TerrainOcclusion.conservativeRasterActive());
        if (scene.startsWith("ocean-")) {
            // The deep-ocean column the run actually measured over, not a
            // promise of one - the pose is seed-dependent (see oceanCameraTp).
            knobs.put("oceanCameraX", oceanCameraX);
            knobs.put("oceanCameraZ", oceanCameraZ);
        }
        if (scene.startsWith("forest-")) {
            // Same contract as the ocean's: the forest column the run
            // actually measured over (see forestCameraTp).
            knobs.put("forestCameraX", forestCameraX);
            knobs.put("forestCameraZ", forestCameraZ);
        }
        root.put("knobs", knobs);
        // WHAT THE BASELINE ACTUALLY IS. Every "plain Minecraft" figure this
        // project published before 1.1 was vanilla running on Minecraft's
        // VULKAN backend, because the bench produces its baseline by
        // switching Meshelium off inside an already-Vulkan process. 26.2
        // boots OpenGL by default, so that is not the "before" most readers
        // have. Backends are fixed at boot, so an OpenGL number needs its
        // own run; this field says which one a report is, in the report,
        // rather than leaving it to be inferred from the command line.
        root.put("hostBackend", VANILLA_ONLY ? "opengl-or-host" : "vulkan");
        root.put("vanillaOnly", VANILLA_ONLY);
        // Which experiment this report IS. Without it, a Sodium run and a
        // standalone run produce the same JSON shape with "meshelium" and
        // "vanilla" legs that mean entirely different things - and this
        // project has already published a page of numbers whose provenance
        // nobody could reconstruct.
        root.put("host", sodiumHost ? "sodium" : "vanilla");
        root.put("legMeaning", sodiumHost
                ? "meshelium = Meshelium mesh shaders drawing Sodium's geometry; "
                        + "vanilla = Sodium's own renderer, same session"
                : "meshelium = Meshelium's own renderer; vanilla = Minecraft's, same session");
        // THE RESOLUTION THE RUN ACTUALLY GOT, not the one that was asked
        // for. This project published a whole page of wrong numbers because
        // the harness window is 854x480 by default and nothing in the report
        // said so; -Pmeshelium.res then fixed the request side while leaving
        // the report still silent about the result, so a leg that ran at
        // another size would still have looked comparable.
        //
        // renderWidth/renderHeight are the size the RENDER TARGET is created
        // at, and they are the number that belongs beside any per-pixel
        // cost. osFramebuffer* is what GLFW says the real window is. In a
        // gametest these legitimately differ: Fabric's WindowMixin cancels
        // the resize callbacks and virtualises vanilla's framebuffer fields,
        // so the harness gets the render size it asked for whatever the
        // window manager did. Recording both means a discrepancy is visible
        // instead of being an unexplained anomaly in the timings, which is
        // exactly how the 2560x1440 leg first looked.
        Map<String, Object> render = new LinkedHashMap<>();
        render.put("renderWidth", framebufferDims[0]);
        render.put("renderHeight", framebufferDims[1]);
        render.put("megapixels",
                round3(framebufferDims[0] * (double) framebufferDims[1] / 1_000_000.0));
        render.put("guiWidth", framebufferDims[2]);
        render.put("guiHeight", framebufferDims[3]);
        render.put("osFramebufferWidth", framebufferDims[4]);
        render.put("osFramebufferHeight", framebufferDims[5]);
        render.put("osMatchesRender",
                framebufferDims[4] == framebufferDims[0] && framebufferDims[5] == framebufferDims[1]);
        root.put("framebuffer", render);
        root.put("warmupFrames", WARMUP_FRAMES);
        root.put("measuredFrames", MEASURED_FRAMES);

        Map<String, Object> meshelium = new LinkedHashMap<>();
        meshelium.put("cpuFrameNanos", mesheliumCpu);
        meshelium.put("cpuSummaryMs", summarizeMs(mesheliumCpu));
        meshelium.put("judderMs", judderMs(mesheliumCpu));
        meshelium.put("counters", mesheliumCounters);
        // Sodium list path: runs pushed per frame (both opaque passes) and,
        // when the CPU frustum test ran, how many of them were outside the
        // frustum. A spin's inflation shows here as a series, not a
        // last-pass snapshot.
        meshelium.put("runsPerFrame", mesheliumRunsPerFrame);
        meshelium.put("runsFrustumRejectedPerFrame", mesheliumRunsRejectedPerFrame);
        meshelium.put("gpu", gpuReport(gpuRows, gpuRowCount));
        meshelium.put("cpuStages", mesheliumStages);
        root.put("meshelium", meshelium);

        Map<String, Object> vanilla = new LinkedHashMap<>();
        vanilla.put("cpuFrameNanos", vanillaCpu);
        vanilla.put("cpuSummaryMs", summarizeMs(vanillaCpu));
        vanilla.put("judderMs", judderMs(vanillaCpu));
        // The vanilla leg gets stage attribution too: extract/applyFrustum/
        // occlusionGraphUpdate/prepareChunkRenders are VANILLA costs and the
        // baseline's own breakdown is half the wave-12 story (Meshelium-only
        // stages read absent there; the pump still runs — honest, it does).
        vanilla.put("cpuStages", vanillaStages);
        root.put("vanilla", vanilla);

        double mesheliumMean = meanMs(mesheliumCpu);
        double vanillaMean = meanMs(vanillaCpu);
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("mesheliumMeanMs", round3(mesheliumMean));
        comparison.put("vanillaMeanMs", round3(vanillaMean));
        comparison.put("vanillaOverMeshelium",
                mesheliumMean > 0 ? round3(vanillaMean / mesheliumMean) : 0.0);
        // Leg 3: the same renderer as leg 1, measured AFTER leg 2.
        //
        // With the same renderer in both slots this harness produced a 32%
        // gap purely from running order, so leg 1 alone is a pessimistic
        // reading and leg 3 alone is an optimistic one. The honest figure
        // is orderCorrected: leg 2 against the mean of legs 1 and 3, which
        // puts the baseline in the middle of the drift instead of at one
        // end of it.
        if (mesheliumLateCpu.length > 0) {
            double lateMean = meanMs(mesheliumLateCpu);
            double bracket = (mesheliumMean + lateMean) / 2.0;
            comparison.put("mesheliumLateMeanMs", round3(lateMean));
            comparison.put("orderEffect",
                    lateMean > 0 ? round3(mesheliumMean / lateMean) : 0.0);
            comparison.put("mesheliumBracketMeanMs", round3(bracket));
            comparison.put("vanillaOverMesheliumOrderCorrected",
                    bracket > 0 ? round3(vanillaMean / bracket) : 0.0);
            // Legs 1 and 3 are the SAME renderer on the same world. Since
            // the warm-up fix they agree to well under 1% on the Sodium
            // path, so a real disagreement means the run is not a
            // measurement of anything and must not be quoted.
            //
            // It is a FLAG rather than a throw because it fires
            // systematically on the standalone path, where flipping
            // meshelium.terrainDraw off hands the terrain to vanilla and
            // tears down Meshelium's own residency; leg 3 then measures
            // the re-arm rather than the renderer. Measured 2026-09-05:
            // legs1v3 of 0.753 and 1.252 on standalone, against 0.997 and
            // 1.003 on Sodium in the same hour. Throwing there would just
            // make the standalone bench unusable without explaining why.
            double agree = lateMean > 0 ? mesheliumMean / lateMean : 0.0;
            boolean legsAgree = agree > 0.90 && agree < 1.10;
            comparison.put("legsAgree", legsAgree);
            if (!legsAgree) {
                comparison.put("legsAgreeWarning", "legs 1 and 3 are the same renderer and "
                        + "disagree by more than 10% (" + round3(agree) + "), so this run is "
                        + "not a valid comparison - see MEASUREMENTS.md section 0e");
            }
        }
        comparison.put("note", "whole-frame CPU times, same world/camera/session. "
                + "Legs run in order meshelium, vanilla, meshelium: this harness "
                + "measures whatever runs LATER as faster (32% with one renderer in "
                + "both slots), so prefer vanillaOverMesheliumOrderCorrected. "
                + "GPU pass times exist only for the first Meshelium leg and are "
                + "never summed with CPU times");
        root.put("comparison", comparison);

        String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        Path out = FabricLoader.getInstance().getGameDir()
                .resolve("meshelium-bench-" + scene + ".json");
        try {
            Files.writeString(out, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not write the bench report to " + out, e);
        }
        MesheliumLog.LOGGER.info("meshelium bench [" + scene + "]: meshelium mean "
                + round3(mesheliumMean) + " ms vs vanilla mean " + round3(vanillaMean)
                + " ms over " + MEASURED_FRAMES + " frames (ratio vanilla/meshelium "
                + comparison.get("vanillaOverMeshelium") + "); raw series + GPU pass times in "
                + out.getFileName());
    }

    /**
     * NEXT (c): which depth arm the half-res frames of this run used, or ""
     * when none ran. A cell that CHANGED arm mid-run is not a row and says
     * so - the two arms have different re-admission profiles and a mixed
     * run averages them into a number that describes neither.
     *
     * <p>The bias arm carries its caveat in the table cell itself, because
     * a reader meeting "bias" in 0t months from now must not have to go
     * looking for why it is not a shippable result.</p>
     */
    private static String halfResModeText() {
        // Read once: the render thread is still moving these.
        long frames = TerrainOcclusion.halfResFrames();
        if (frames == 0L) {
            return "";
        }
        long flat = TerrainOcclusion.halfResFlatFrames();
        if (flat == frames) {
            return "flat";
        }
        if (flat == 0L) {
            return "bias (comparator; sound only where z+o<=1)";
        }
        return "MIXED - VOID";
    }

    private static Map<String, Object> gpuReport(long[] rows, int rowCount) {
        Map<String, Object> gpu = new LinkedHashMap<>();
        gpu.put("framesCaptured", rowCount);
        gpu.put("note", "per-pass GPU nanos between vanilla pass-end barriers, "
                + "frame-3 lagged readback; -1 = pass absent that frame; "
                + "warmup rows included (first ~" + WARMUP_FRAMES + ")");
        // NEXT (c): "downsample" is index PASS_DOWNSAMPLE and the loop
        // below is bounded by PASSES, so a missing seventh literal would
        // be an ArrayIndexOutOfBounds, not a quietly absent column.
        String[] names = {"opaqueA", "regionRaster", "sectionRaster", "phaseB", "translucent",
                "pass1a", "downsample"};
        for (int p = 0; p < MesheliumGpuTimers.PASSES; p++) {
            long[] series = new long[rowCount];
            for (int r = 0; r < rowCount; r++) {
                series[r] = rows[r * MesheliumGpuTimers.PASSES + p];
            }
            gpu.put(names[p], series);
            gpu.put(names[p] + "SummaryMs", summarizeMs(present(series)));
        }
        return gpu;
    }

    /**
     * Wave-12: the per-stage CPU series of the leg that just captured —
     * same summary shape as the GPU rows. Rows are tail-trimmed to
     * {@link #MEASURED_FRAMES} (stage rows commit one extract late, so a
     * leg can land total±1 rows; the tail discards warmup either way).
     */
    private static Map<String, Object> cpuStagesReport() {
        int rows = MesheliumCpuStages.captureFilled();
        long[] flat = MesheliumCpuStages.captureSnapshot();
        int[] applyRuns = MesheliumCpuStages.captureApplyRunsSnapshot();
        int[] visibleSections = MesheliumCpuStages.captureVisibleSectionsSnapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("framesCaptured", rows);
        out.put("note", "render-thread nanoTime brackets per stage, nanos; -1 = stage absent "
                + "that frame; applyFrustum NESTS inside extract (never sum the two); stages "
                + "are not additive to the whole frame and are never summed with GPU pass times");
        for (int s = 0; s < MesheliumCpuStages.STAGES; s++) {
            long[] series = new long[rows];
            for (int r = 0; r < rows; r++) {
                series[r] = flat[r * MesheliumCpuStages.STAGES + s];
            }
            long[] measured = tail(series, MEASURED_FRAMES);
            out.put(MesheliumCpuStages.NAMES[s], measured);
            out.put(MesheliumCpuStages.NAMES[s] + "SummaryMs", summarizeMs(present(measured)));
        }
        int applyTotal = 0;
        for (int v : applyRuns) {
            applyTotal += v;
        }
        out.put("applyFrustumRuns", applyRuns);
        out.put("applyFrustumRunsTotal", applyTotal);
        out.put("visibleSections", visibleSections);
        // 2026-08-18: executed-compile deltas per committed row (build-thread
        // taps; empties included, resorts structurally excluded) - the
        // build-storm series the rebuild-scheduling kill test reads.
        out.put("sectionCompiles", MesheliumCpuStages.captureSectionCompilesSnapshot());
        return out;
    }

    /**
     * Leg-1 counters for a Sodium-hosted run.
     *
     * <p>Deliberately small. Almost every counter {@link #counters()}
     * reports describes machinery the Sodium path does not use — the
     * arena, the residency store, the occlusion stamps, the region
     * dispatch — and reporting them as zeros would read as "these are
     * broken" rather than "these are not involved".
     *
     * <p>{@code runsPerPass} is the one to watch across runs. It is
     * how much geometry Meshelium is being handed, and if a future change
     * to the enumeration or to per-facing culling moves it, the frame time
     * moved for a reason that has nothing to do with the renderer. It was
     * keyed {@code sectionsPerPass} until 2026-09-08 (beta.8); the value is
     * unchanged, only the name, because on rung 1 it counts draws
     * (contiguous runs), not sections.
     */
    private static Map<String, Object> sodiumCounters() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("framesDrawn", SodiumTerrainDrawer.framesDrawn());
        c.put("totalSectionsDrawn", SodiumTerrainDrawer.totalSectionsDrawn());
        c.put("runsPerPass", SodiumTerrainDrawer.lastSectionsDrawn());
        c.put("regionsPerPass", SodiumTerrainDrawer.lastRegionsDrawn());
        // The two numbers the k-split experiment lives on: how many draw
        // commands were actually recorded, and the multiplier that made it
        // so. Without both in the report, a k=4 run and a k=1 run are
        // indistinguishable after the fact.
        c.put("drawCommandsPerPass", SodiumTerrainDrawer.lastDrawCommands());
        c.put("kSplit", SodiumTerrainDrawer.kSplit());
        // Batched runs vs commands is the whole claim of that change: the
        // same geometry, the same run count, far fewer commands. Reporting
        // only the command count could not tell a successful batch apart
        // from a draw loop that stopped drawing.
        c.put("batched", SodiumTerrainDrawer.batched());
        c.put("shape", SodiumTerrainDrawer.shapeName());
        c.put("runsPerPass", SodiumTerrainDrawer.lastRunsWritten());
        long kept = SodiumTerrainDrawer.lastQuadsKept();
        long culled = SodiumTerrainDrawer.lastQuadsCulled();
        c.put("quadsKept", kept);
        c.put("quadsCulled", culled);
        c.put("quadsCulledPct", kept + culled == 0 ? 0.0
                : Math.round(1000.0 * culled / (kept + culled)) / 10.0);
        c.put("tableMicros", SodiumTerrainDrawer.lastTableNanos() / 1000.0);
        c.put("recordMicros", SodiumTerrainDrawer.lastRecordNanos() / 1000.0);
        c.put("buildMicros", SodiumTerrainDrawer.meanBuildNanos() / 1000.0);
        c.put("buildMicrosLast", SodiumTerrainDrawer.lastBuildNanos() / 1000.0);
        c.put("noDiscardPasses", SodiumTerrainDrawer.noDiscardPasses());
        c.put("discardPasses", SodiumTerrainDrawer.discardPasses());
        // D-021's per-region run cache: how much of the enumeration it
        // replaced (hits against rebuilds, per pass and cumulative) and
        // whether each of the five hooks that invalidate it has callers.
        // Both halves belong in the report: a cache that never rebuilds in
        // a live world has a broken invalidation, not a perfect hit rate,
        // and buildMicros alone cannot tell the two apart.
        c.put("runCacheArmed", SodiumTerrainDrawer.runCacheArmed());
        c.put("regionsHitPerPass", SodiumTerrainDrawer.lastRegionsHit());
        c.put("regionsRebuiltPerPass", SodiumTerrainDrawer.lastRegionsRebuilt());
        c.put("regionsHitTotal", SodiumTerrainDrawer.regionsHitTotal());
        c.put("regionsRebuiltTotal", SodiumTerrainDrawer.regionsRebuiltTotal());
        // The frustum lever (2026-09-13): runs pushed before any dropping,
        // and what the CPU test saw of them; per pass and cumulative.
        c.put("frustumMode", SodiumTerrainDrawer.frustumModeName());
        c.put("runsPushedPerPass", SodiumTerrainDrawer.lastRunsPushed());
        c.put("runsFrustumRejectedPerPass", SodiumTerrainDrawer.lastRunsFrustumRejected());
        c.put("quadsFrustumRejectedPerPass", SodiumTerrainDrawer.lastQuadsFrustumRejected());
        c.put("runsPushedTotal", SodiumTerrainDrawer.runsPushedTotal());
        c.put("runsFrustumRejectedTotal", SodiumTerrainDrawer.runsFrustumRejectedTotal());
        c.put("hookUpload", SodiumTerrainDrawer.hookUpload());
        c.put("hookRemoveSection", SodiumTerrainDrawer.hookRemoveSection());
        c.put("hookBufferChange", SodiumTerrainDrawer.hookBufferChange());
        c.put("hookSegmentChange", SodiumTerrainDrawer.hookSegmentChange());
        c.put("hookDelete", SodiumTerrainDrawer.hookDelete());
        c.put("broken", SodiumTerrainDrawer.broken());
        c.put("error", String.valueOf(SodiumTerrainDrawer.error()));
        // D-023 stages 2-3: the GPU-visibility rungs (GPU-VISIBILITY-CONTRACT.md
        // 7.2, 10). `rung` says which path drew the last frame - "0a" mirror +
        // rasters, "0b" mirror with the BFS mask, "1" the list path, "2"
        // Sodium - because a run measured on the wrong rung is the same kind
        // of misfiled number as the multiWG knob above; the two `broken`
        // latches and their errors say why a rung was refused; the declines
        // (by reason) say how many frames fell to rung 1 mid-run, which a
        // mean frame time would silently average in.
        c.put("rung", SodiumTerrainDrawer.rung());
        c.put("gpuDrawEnabled", SodiumTerrainDrawer.gpuDrawEnabled());
        c.put("occlusionEnabled", SodiumTerrainDrawer.occlusionEnabled());
        c.put("gpuDrawBroken", SodiumTerrainDrawer.gpuDrawBroken());
        c.put("gpuDrawError", String.valueOf(SodiumTerrainDrawer.gpuDrawError()));
        c.put("occlusionBroken", SodiumTerrainDrawer.occlusionBroken());
        c.put("occlusionError", String.valueOf(SodiumTerrainDrawer.occlusionError()));
        c.put("framesOwned", SodiumTerrainDrawer.framesOwned());
        c.put("framesAttempted", SodiumTerrainDrawer.framesAttempted());
        c.put("frameDeclines", new LinkedHashMap<>(SodiumTerrainDrawer.frameDeclines()));
        c.put("frameDeclinesTotal", SodiumTerrainDrawer.frameDeclinesTotal());
        // The mirror: what each frame paid to keep the GPU's records equal
        // to Sodium's (I1), and the id/key bookkeeping behind I4/I5. A
        // steady world commits ~0; a build storm shows here before it shows
        // in the frame.
        c.put("commitRegionsPerFrame", SodiumTerrainDrawer.commitRegionsPerFrame());
        c.put("commitRegionsTotal", SodiumTerrainDrawer.commitRegionsTotal());
        c.put("commitBytesPerFrame", SodiumTerrainDrawer.commitBytesPerFrame());
        c.put("commitMicros", SodiumTerrainDrawer.commitNanos() / 1000.0);
        c.put("deadRows", SodiumTerrainDrawer.deadRows());
        c.put("midsLive", SodiumTerrainDrawer.midsLive());
        c.put("midsCapacity", SodiumTerrainDrawer.midsCapacity());
        c.put("midsReleased", SodiumTerrainDrawer.midsReleased());
        c.put("growths", SodiumTerrainDrawer.growths());
        c.put("bufferKeys", SodiumTerrainDrawer.bufferKeys());
        // The O(regions) loop: what it listed, how it grouped, how many
        // indirect commands that became (I3: 2 x groups x phases), and its
        // cost - the number stage 2 exists to make independent of the
        // section count.
        c.put("listedRegions", SodiumTerrainDrawer.listedRegions());
        c.put("bufferGroups", SodiumTerrainDrawer.bufferGroups());
        c.put("drawCommandsPerFrame", SodiumTerrainDrawer.drawCommandsPerFrame());
        c.put("loopMicros", SodiumTerrainDrawer.loopNanos() / 1000.0);
        // The geometry-bitmap A/B (2026-09-17). loopMicros above is ONE
        // frame's sample - reportFrameLoop overwrites it - so it cannot
        // answer "what does the drain cost". These are means over every
        // frame each implementation drew, interleaved inside this session,
        // plus the region counts that make them per-region comparable and
        // the verify tally that says whether the two sets ever differed.
        c.put("listMapMode", String.valueOf(SodiumTerrainDrawer.listMapMode()));
        c.put("listMapDrainFrames", listMapDrainFrames);
        c.put("listMapMapFrames", listMapMapFrames);
        c.put("listMapDrainMicros", listMapDrainMicros);
        c.put("listMapMapMicros", listMapMapMicros);
        c.put("listMapDrainRegionsMean", listMapDrainRegionsMean);
        c.put("listMapMapRegionsMean", listMapMapRegionsMean);
        c.put("listMapCompares", listMapCompares);
        c.put("listMapMismatches", listMapMismatches);
        c.put("listMapDisarmReason", String.valueOf(SodiumTerrainDrawer.listMapDisarmReason()));
        c.put("listMapFound", MesheliumSodiumHooks.geometryMapFound());
        // GPU-counted survivors from the lagged stats readback: VisMode 0
        // (parity with sectionsEmitted on rung 1), phase A, phase B, and
        // quads emitted. Sections drawn per pass falling toward the
        // standalone's 82-of-2,842 at the canopy pose is D-023's stage-3
        // gate; these are the numbers that gate reads.
        c.put("gpuSectionsMask", SodiumTerrainDrawer.gpuSectionsMask());
        c.put("gpuSectionsA", SodiumTerrainDrawer.gpuSectionsA());
        c.put("gpuSectionsB", SodiumTerrainDrawer.gpuSectionsB());
        c.put("gpuQuads", SodiumTerrainDrawer.gpuQuads());
        c.put("statsFramesRead", SodiumTerrainDrawer.statsFramesRead());
        c.put("sectionsEmitted", SodiumTerrainDrawer.sectionsEmitted());
        c.put("midMissing", SodiumTerrainDrawer.midMissing());
        c.put("keyMismatch", SodiumTerrainDrawer.keyMismatch());
        c.put("mirrorAuditArmed", SodiumTerrainDrawer.mirrorAuditArmed());
        c.put("mirrorAuditMismatches", SodiumTerrainDrawer.mirrorAuditMismatches());
        c.put("mirrorGpuReadbackMismatches", SodiumTerrainDrawer.mirrorGpuReadbackMismatches());
        // The two gates and their levers. `searchDistanceSource` must read
        // "invoker" for the fog gate to be Sodium's own number; "fallback"
        // means the plugin did not find getSearchDistance and the gate
        // widened to the render distance (draw more, never fewer).
        c.put("searchDistanceSource", String.valueOf(SodiumTerrainDrawer.searchDistanceSource()));
        c.put("searchDistanceBlocks", SodiumTerrainDrawer.searchDistanceBlocks());
        c.put("distanceGateMode", String.valueOf(SodiumTerrainDrawer.distanceGateMode()));
        c.put("graphRegions", SodiumTerrainDrawer.graphRegions());
        c.put("faceAll", SodiumTerrainDrawer.faceAll());
        c.put("phaseBCpuSkipArmed", SodiumTerrainDrawer.phaseBCpuSkipArmed());
        c.put("phaseBCpuSkips", SodiumTerrainDrawer.phaseBCpuSkips());
        c.put("instancesLive", SodiumTerrainDrawer.instancesLive());
        c.put("instancesRetired", SodiumTerrainDrawer.instancesRetired());
        c.put("occlusionRecreates", SodiumTerrainDrawer.occlusionRecreates());
        // NEXT (c): the half-resolution occlusion depth. occlusionFrames
        // sits beside them so the reader can compute the fraction
        // halfResFrames / occlusionFrames, which the G verdict rule needs
        // in [0.98, 1.02] in an on-cell and exactly 0 in an off-cell - any
        // other value VOIDS the row (a lever that never fired is not an
        // "on" row; one that fired in an "off" row is a property leak).
        // Slack BOTH ways because a frame that latches between the arm and
        // occlusionFrames++ counts on one side only.
        c.put("halfResFrames", TerrainOcclusion.halfResFrames());
        c.put("halfResRasterFrames", TerrainOcclusion.halfResRasterFrames());
        c.put("halfResFlatFrames", TerrainOcclusion.halfResFlatFrames());
        c.put("halfResAllocations", TerrainOcclusion.halfResAllocations());
        c.put("halfResAllocationFailures", TerrainOcclusion.halfResAllocationFailures());
        // A non-zero skip count is what a NON-CANONICAL PROJECTION looks
        // like in a bench cell: view bobbing, a hurt animation, a portal or
        // nausea hand the rasters a matrix whose k and NearR would be wrong.
        c.put("halfResArmSkips", TerrainOcclusion.halfResArmSkips());
        c.put("halfResArmProjectionMismatches",
                TerrainOcclusion.halfResArmProjectionMismatches());
        c.put("halfResBroken", TerrainOcclusion.halfResBroken());
        c.put("halfResError", String.valueOf(TerrainOcclusion.halfResError()));
        c.put("occlusionFrames", SodiumTerrainDrawer.occlusionFrames());
        // On rung 0 the GPU timer brackets a FRAME (both opaque passes);
        // on rung 1 a PASS. A gpu.opaqueA row is not comparable across
        // that boundary, and this is the field that says which it was.
        c.put("gpuTimerUnit", String.valueOf(SodiumTerrainDrawer.gpuTimerUnit()));
        c.put("ringGuardTrips", SodiumTerrainDrawer.ringGuardTrips());
        return c;
    }

    private static Map<String, Object> counters() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("framesDrawn", TerrainDrawer.framesDrawn());
        c.put("occlusionFrames", TerrainDrawer.occlusionFrames());
        // NEXT (c): the half-resolution occlusion depth. occlusionFrames
        // sits beside them so the reader can compute the fraction
        // halfResFrames / occlusionFrames, which the G verdict rule needs
        // in [0.98, 1.02] in an on-cell and exactly 0 in an off-cell - any
        // other value VOIDS the row (a lever that never fired is not an
        // "on" row; one that fired in an "off" row is a property leak).
        // Slack BOTH ways because a frame that latches between the arm and
        // occlusionFrames++ counts on one side only.
        c.put("halfResFrames", TerrainOcclusion.halfResFrames());
        c.put("halfResRasterFrames", TerrainOcclusion.halfResRasterFrames());
        c.put("halfResFlatFrames", TerrainOcclusion.halfResFlatFrames());
        c.put("halfResAllocations", TerrainOcclusion.halfResAllocations());
        c.put("halfResAllocationFailures", TerrainOcclusion.halfResAllocationFailures());
        // A non-zero skip count is what a NON-CANONICAL PROJECTION looks
        // like in a bench cell: view bobbing, a hurt animation, a portal or
        // nausea hand the rasters a matrix whose k and NearR would be wrong.
        c.put("halfResArmSkips", TerrainOcclusion.halfResArmSkips());
        c.put("halfResArmProjectionMismatches",
                TerrainOcclusion.halfResArmProjectionMismatches());
        c.put("halfResBroken", TerrainOcclusion.halfResBroken());
        c.put("halfResError", String.valueOf(TerrainOcclusion.halfResError()));
        c.put("bfsOnlyFrames", TerrainDrawer.bfsOnlyFrames());
        c.put("regionsDispatched", TerrainDrawer.regionsDispatched());
        c.put("gpuSectionsDrawn", TerrainDrawer.gpuSectionsDrawn());
        // The two halves of that total, separately, because the sum alone
        // cannot tell a GROWN VISIBLE SET apart from a DROPPED STAMP. Phase
        // A draws what was visible last frame; phase B draws what became
        // visible THIS frame. In a converged static scene phase B is ~0 and
        // the total is essentially all phase A. If a stamp is ever lost, the
        // section falls out of phase A, reappears in phase B, and lets
        // things behind it pass depth — so the TOTAL goes UP while terrain
        // flickers. A rising total is therefore ambiguous; a rising phase B
        // in a static scene is not.
        c.put("gpuPhaseASections", TerrainDrawer.gpuPhaseASections());
        c.put("gpuPhaseBSections", TerrainDrawer.gpuPhaseBSections());
        // Those two are a SNAPSHOT of the last stats frame. This is the
        // whole-window version and the one that actually settles the
        // question: how many stats frames have gone by since phase B last
        // drew anything at all. A dropped stamp puts its section into phase
        // B the very next frame, so a large quiet gap means no stamp was
        // lost anywhere in the measured window, not merely in the last frame.
        long lastRead = TerrainDrawer.lastReadStatsFrame();
        long lastPhaseB = TerrainDrawer.lastPhaseBStatsFrame();
        c.put("statsFrames", TerrainDrawer.statsFrames());
        c.put("lastReadStatsFrame", lastRead);
        c.put("lastPhaseBStatsFrame", lastPhaseB);
        c.put("phaseBQuietStatsFrames", lastPhaseB < 0 ? lastRead + 1 : lastRead - lastPhaseB);
        // The 1.4.0 CPU skip's engagement count. A static-only feature
        // must publish its rate next to any win it claims: a bench pair
        // without this column is the 854x480 mistake with a new axis.
        c.put("phaseBCpuSkipFrames", TerrainDrawer.phaseBCpuSkipFrames());
        c.put("tapCompiles", MesheliumCpuStages.tapCompiles());
        // The incremental snapshot's health: publishes must dwarf
        // fullRebuilds on any steady leg, or the delta log is not doing
        // its job and the frame is quietly paying the old full walk.
        c.put("snapshotPublishes", TerrainResidency.snapshotPublishes());
        c.put("snapshotFullRebuilds", TerrainResidency.snapshotFullRebuilds());
        c.put("snapshotLogRecords", TerrainResidency.snapshotLogRecords());
        c.put("translucentFrames", TerrainDrawer.translucentFrames());
        c.put("gpuTimerFramesRead", MesheliumGpuTimers.framesRead());
        c.put("gpuTimerNotReady", MesheliumGpuTimers.framesNotReadyCount());
        c.put("gpuTimerAnomalous", MesheliumGpuTimers.framesAnomalousCount());
        c.put("gpuTimerFailure", String.valueOf(MesheliumGpuTimers.failure()));
        // Wave-12 candidate health: hole frames must be 0 on skip legs
        // (asserted in the run); hit/miss rates tell the coordinator what
        // cachedCull actually did (bench static camera ⇒ hits ≈ frames).
        c.put("prepSkippedFrames", TerrainDrawer.prepSkippedFrames());
        c.put("prepSkipHoleFrames", TerrainDrawer.prepSkipHoleFrames());
        c.put("cachedCullHitFrames", TerrainDrawer.cachedCullHitFrames());
        c.put("cachedCullMissFrames", TerrainDrawer.cachedCullMissFrames());
        c.put("residency", TerrainResidency.counters().toString());
        farFieldCounters(c);
        return c;
    }

    /**
     * W4: the far field's own accounting, exported beside the residency
     * counters because the residency record does NOT carry it. A far
     * section is absent from {@code sectionsResident} and
     * {@code retainedSections} while its quads ARE inside
     * {@code arenaUsedBytes} and its region ids inside
     * {@code regionsLive} (the Counters record's javadoc has the full
     * split), so a bench that published arena bytes without these
     * columns would attribute far memory to the near field and quietly
     * misreport bytes per section on every far-armed run.
     *
     * <p><b>The FarFieldResidency block is gated on purpose.</b> Naming
     * that class loads it, and "the walker class is never loaded in a
     * session that never enabled the far field" is the wave's zero-cost
     * invariant, asserted by MesheliumFarFieldTest. A counter export is
     * not a good enough reason to break it, so with the far field off
     * this emits one honest marker instead. The store and extractor
     * counters below need no such gate: their classes are loaded by the
     * chunk-lifecycle mixins on every run regardless.</p>
     */
    private static void farFieldCounters(Map<String, Object> c) {
        // Store side (FarField): loaded on every run by the level-swap
        // mixin, so these are always safe and always meaningful.
        c.put("farStoreWrites", com.deds.meshelium.farfield.FarField.farStoreWrites.sum());
        c.put("farStoreReads", com.deds.meshelium.farfield.FarField.farStoreReads.sum());
        c.put("farStoreErrors", com.deds.meshelium.farfield.FarField.farStoreErrors.sum());
        c.put("farStoreBytesWritten",
                com.deds.meshelium.farfield.FarField.farStoreBytesWritten.sum());
        c.put("farStoreDroppedWrites",
                com.deds.meshelium.farfield.FarField.farStoreDroppedWrites.sum());
        // Extractor side (ExtractDispatch): same, refreshArmed() runs on
        // every setLevel whether or not the master switch is on.
        c.put("farExtracts",
                com.deds.meshelium.farfield.extract.ExtractDispatch.farExtracts.sum());
        c.put("farExtractCells",
                com.deds.meshelium.farfield.extract.ExtractDispatch.farExtractCells.sum());
        // pre16 M2-M4: the save state machine's ledger (behind is the
        // repairable GONE_BEHIND gauge, lost is permanent and monotone -
        // zero is the contract) beside the walker's seam-gap series.
        // farLateShells against farExtracts is THE diagnostic for the
        // near/far pop-in (a ratio near 1.0 while travelling means the
        // scan is writing columns off before their shell is written);
        // farSupersededByVanilla is how we know the inner edge is
        // handing over to real terrain rather than dropping it.
        // farSaveBehind is a game-thread gauge; this export is a
        // diagnostic on the same racy-read posture as the residency
        // gauges above it, never an assertion input.
        c.put("farSaveBehind",
                com.deds.meshelium.farfield.extract.ExtractDispatch
                        .farSaveBehind());
        c.put("farSaveLeftUnsaved",
                com.deds.meshelium.farfield.extract.ExtractDispatch
                        .farSaveLeftUnsaved.sum());
        c.put("farSaveLost",
                com.deds.meshelium.farfield.extract.ExtractDispatch
                        .farSaveLost.sum());
        c.put("farSaveEditsCoalesced", com.deds.meshelium.farfield.extract
                .ExtractDispatch.farSaveEditsCoalesced.sum());
        c.put("farLateShells",
                com.deds.meshelium.farfield.FarFieldResidency.farLateShells.sum());
        c.put("farSupersededByVanilla",
                com.deds.meshelium.farfield.FarFieldResidency
                        .farSupersededByVanilla.sum());
        boolean everArmed = com.deds.meshelium.farfield.FarFieldConfig.enabled()
                || !com.deds.meshelium.farfield.FarField.isCompletelyIdle();
        if (!everArmed) {
            c.put("farResidency", "off (walker class never loaded this session)");
            return;
        }
        c.put("farSectionsResident", com.deds.meshelium.farfield.FarFieldResidency
                .farSectionsResident.sum());
        c.put("farShellRequests", com.deds.meshelium.farfield.FarFieldResidency
                .farShellRequests.sum());
        c.put("farMeshedSections", com.deds.meshelium.farfield.FarFieldResidency
                .farMeshedSections.sum());
        c.put("farAdmissions", com.deds.meshelium.farfield.FarFieldResidency
                .farAdmissions.sum());
        c.put("farReleases", com.deds.meshelium.farfield.FarFieldResidency
                .farReleases.sum());
        c.put("farPromoteBudgetStalls", com.deds.meshelium.farfield.FarFieldResidency
                .farPromoteBudgetStalls.sum());
        c.put("farMeshSkippedCells", com.deds.meshelium.farfield.FarFieldResidency
                .farMeshSkippedCells.sum());
        c.put("farMeshErrors", com.deds.meshelium.farfield.FarFieldResidency
                .farMeshErrors.sum());
        c.put("farWaterOpaqueQuads", com.deds.meshelium.farfield.FarFieldResidency
                .farWaterOpaqueQuads.sum());
        c.put("farBroken", com.deds.meshelium.farfield.FarFieldResidency.isBroken());
    }

    // ------------------------------------------------------------------
    // Series math (nanos in, milliseconds out)
    // ------------------------------------------------------------------

    private static Map<String, Object> summarizeMs(long[] nanos) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (nanos.length == 0) {
            s.put("samples", 0);
            return s;
        }
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        s.put("samples", sorted.length);
        s.put("meanMs", round3(meanMs(sorted)));
        s.put("medianMs", round3(percentileMs(sorted, 50)));
        s.put("p95Ms", round3(percentileMs(sorted, 95)));
        s.put("p99Ms", round3(percentileMs(sorted, 99)));
        // ---- smoothness, not throughput ----
        //
        // Owner directive 2026-08-12: "fps is king here, of course without
        // stuttering". Everything above is central tendency and none of it
        // can see a hitch. Stutter lives in the tail and, more precisely, in
        // how far consecutive frames JUMP: a steady 60 fps looks better than
        // 100 fps alternating with 40, and the mean cannot tell those apart.
        //
        // These are emitted for every leg so a smoothness regression shows up
        // in the report by default instead of only when somebody thinks to go
        // looking. Learned the hard way: a change that read as a small
        // median LOSS was independently producing 63 ms frame-to-frame jumps
        // at ground-rd64, and nothing in the summary said so.
        double median = percentileMs(sorted, 50);
        s.put("maxMs", round3(sorted[sorted.length - 1] / 1e6));
        // p99 as a multiple of typical. 1.0 is perfectly even; much above 2
        // is a visible hitch even when the average looks healthy.
        s.put("p99OverMedian", median > 0 ? round3(percentileMs(sorted, 99) / median) : 0.0);
        // Frames worse than twice typical: the literal hitch count.
        int hitches = 0;
        for (long n : sorted) {
            if (n / 1e6 > 2.0 * median) {
                hitches++;
            }
        }
        s.put("hitchFrames", hitches);
        return s;
    }

    /**
     * Frame-to-frame judder, in the ORIGINAL sample order.
     *
     * <p>Deliberately separate from {@link #summarizeMs}, which sorts and so
     * destroys adjacency. The largest single jump between consecutive frames
     * is what a player perceives as a stutter; the mean absolute delta is the
     * background shimmer.</p>
     */
    private static Map<String, Object> judderMs(long[] nanos) {
        Map<String, Object> j = new LinkedHashMap<>();
        if (nanos.length < 2) {
            j.put("samples", 0);
            return j;
        }
        double worst = 0;
        double sum = 0;
        for (int i = 1; i < nanos.length; i++) {
            double d = Math.abs(nanos[i] - nanos[i - 1]) / 1e6;
            sum += d;
            if (d > worst) {
                worst = d;
            }
        }
        j.put("samples", nanos.length - 1);
        j.put("maxDeltaMs", round3(worst));
        j.put("meanDeltaMs", round3(sum / (nanos.length - 1)));
        return j;
    }

    /**
     * The yaw every measured leg starts from.
     *
     * <h2>Why the legs have to share a starting angle</h2>
     * <p>The camera spins throughout a bench run, and a 720-frame capture
     * at a thousand frames a second is about 28 degrees of arc. Left alone,
     * leg 1 measures one slice of the forest, leg 2 the next slice and
     * leg 3 the one after — three different views, reported as one
     * comparison.
     *
     * <p>Measured 2026-09-05, and it is not a rounding error. With the
     * SAME renderer in both slots the second leg came out 32% faster with
     * the spin on, and only 9.3% faster with it off. So roughly two thirds
     * of what looked like a warm-up effect was the camera simply pointing
     * somewhere cheaper.
     *
     * <p>Resetting the yaw before each capture makes every leg sweep the
     * SAME arc of the same world. What remains is the real ~9% order
     * effect, which is what the third leg is for.
     */
    private static volatile float benchStartYaw = Float.NaN;

    /** Point the camera back at the arc leg 1 measured. */
    private static void resetToBenchYaw(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (client.player == null) {
                return;
            }
            if (Float.isNaN(benchStartYaw)) {
                benchStartYaw = client.player.getYRot();
            } else {
                client.player.setYRot(benchStartYaw);
                client.player.yRotO = benchStartYaw;
            }
        });
    }

    /**
     * Wait for the terrain to be rebuilt after a renderer flip.
     *
     * <h2>Why a flip needs this at all</h2>
     * <p>On the SODIUM path it does not: making Meshelium decline a pass
     * tears nothing down, because it owns none of Sodium's state.
     *
     * <p>On the STANDALONE path the flip is a state transition. Turning
     * {@code meshelium.terrainDraw} off stands Meshelium's upload seam
     * down and asks vanilla to rebuild the terrain it had been feeding
     * Meshelium; turning it back on asks for the rebuild in the other
     * direction. Both take far longer than the warm-up frames, so without
     * this wait leg 2 measures vanilla rebuilding and leg 3 measures
     * Meshelium's residency refilling. Measured 2026-09-05: legs 1 and 3,
     * the same renderer on the same world, came out 25% and 33% apart.
     *
     * <p>The signal has to come from whichever renderer is now live —
     * Meshelium's residency counters read zero forever when it is dormant,
     * and vanilla's visible-section list reads zero forever when it is
     * not.
     *
     * @param mesheliumLive true when Meshelium is the one that has to
     *        finish rebuilding, false when vanilla is
     */
    private static void settleAfterFlip(ClientGameTestContext context, boolean mesheliumLive) {
        for (int i = 0; i < FLIP_SETTLE_ITERATIONS; i++) {
            long before = flipSettleSignal(context, mesheliumLive);
            context.waitTicks(40);
            long after = flipSettleSignal(context, mesheliumLive);
            if (after > 0 && after == before) {
                // Stable twice running. Give the renderer a moment to draw
                // what just landed before anything is timed.
                context.waitTicks(40);
                return;
            }
        }
        throw new AssertionError("terrain never settled after the renderer flip ("
                + (mesheliumLive ? "meshelium" : "vanilla") + " side); the legs would be "
                + "measuring a rebuild rather than a renderer");
    }

    /** How many 2s windows a post-flip rebuild gets before we give up. */
    private static final int FLIP_SETTLE_ITERATIONS = 90;

    private static long flipSettleSignal(ClientGameTestContext context, boolean mesheliumLive) {
        if (mesheliumLive) {
            TerrainResidency.Counters c = TerrainResidency.counters();
            // Uploads having stopped is the settle; sections resident is
            // the proof it settled on something rather than on nothing.
            return c.stagingBacklogEntries() == 0 && c.sectionsResident() > 0
                    ? c.uploadedSections() : -1L;
        }
        long[] n = new long[1];
        context.runOnClient(client -> n[0] = client.levelRenderer.visibleSections().size());
        return n[0];
    }

    private static float currentYaw(ClientGameTestContext context) {
        float[] y = new float[1];
        context.runOnClient(client -> y[0] = client.player == null ? 0.0f : client.player.getYRot());
        return y[0];
    }

    private static int clientRenderDistance(ClientGameTestContext context) {
        int[] rd = new int[1];
        context.runOnClient(client -> rd[0] = client.options.getEffectiveRenderDistance());
        return rd[0];
    }

    private static double meanMs(long[] nanos) {
        if (nanos.length == 0) {
            return 0;
        }
        double sum = 0;
        for (long n : nanos) {
            sum += n;
        }
        return sum / nanos.length / 1e6;
    }

    /** Nearest-rank percentile over an ALREADY SORTED array. */
    private static double percentileMs(long[] sorted, int pct) {
        int rank = Math.max(1, (int) Math.ceil(pct / 100.0 * sorted.length));
        return sorted[rank - 1] / 1e6;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static long[] tail(long[] series, int n) {
        if (series.length <= n) {
            return series;
        }
        return Arrays.copyOfRange(series, series.length - n, series.length);
    }

    /** Drop the −1 "pass absent" markers from a GPU series. */
    private static long[] present(long[] series) {
        return Arrays.stream(series).filter(v -> v >= 0).toArray();
    }

    // ------------------------------------------------------------------
    // Shared protocol helpers (the draw test's exact quiesce)
    // ------------------------------------------------------------------

    private static void quiesce(ClientGameTestContext context) {
        if (VANILLA_ONLY) {
            // Meshelium's build pipeline is what this waits on, and it is
            // dormant. settleWorldgenVanilla already waited for vanilla's
            // own chunk streaming to stop, which is the equivalent bar.
            context.waitTicks(40);
            return;
        }
        for (int i = 0; i < 30; i++) {
            long before = TerrainResidency.counters().encodedSections();
            context.waitTicks(20);
            long after = TerrainResidency.counters().encodedSections();
            if (before == after && TerrainResidency.counters().stagingBacklogEntries() == 0) {
                return;
            }
        }
        throw new AssertionError("build pipeline never went quiet before the benchmark");
    }

    private static void assertNoErrors() {
        if (TerrainDrawer.lastError() != null) {
            throw new AssertionError("terrain drawer error: " + TerrainDrawer.lastError());
        }
        if (TerrainResidency.lastError() != null) {
            throw new AssertionError("residency error: " + TerrainResidency.lastError());
        }
        if (TerrainDrawer.occlusionError() != null) {
            throw new AssertionError("occlusion error (bench would measure the fallback, "
                    + "not the product): " + TerrainDrawer.occlusionError());
        }
    }

    /**
     * Waits until the bench world's section stream goes quiet: Meshelium's
     * uploadedSections counter (Vulkan path only, which the benchmark
     * already enforces) unchanged across a 2-second window, with sections
     * actually resident. Budget ~3 minutes — noise-world generation at
     * bench render distances is minutes, not the seconds the framework's
     * waitForChunksRender allows.
     */
    /**
     * Settle iterations, scaled by the AREA the scene has to generate.
     *
     * <p>This was a flat 240 (an eight-minute budget, whatever its error
     * message claimed) tuned when the deepest scene was render distance 64.
     * Worldgen cost grows with the square of the distance: rd 64 is about
     * 16k columns and rd 120 is about 58k, three and a half times as many,
     * so a budget that fits one cannot fit the other and rd 120 failed on
     * the timeout rather than on anything real.</p>
     */
    private static int settleIterations() {
        int rd = Math.max(1, benchRenderDistance);
        double area = (rd / 64.0) * (rd / 64.0);
        return (int) Math.max(240, Math.ceil(240 * area));
    }

    /** The scene's render distance, for sizing the settle budget. */
    private static volatile int benchRenderDistance = 64;

    /**
     * The world the legs actually drew. The option is what was ASKED; the
     * effective distance is min(option, server), and the server's own
     * clamp lives in ChunkMap, widened by Meshelium only under its gate.
     * Every Sodium-path bench before D-019 asked for 64 with the server
     * clamped to 32, and nothing in the report could show it. These two
     * fields are what makes that visible.
     */
    private static int effectiveRenderDistanceAtSettle = -1;

    private static int loadedChunksAtSettle = -1;

    private static void captureWorldShape(ClientGameTestContext context) {
        context.runOnClient(client -> {
            effectiveRenderDistanceAtSettle = client.options.getEffectiveRenderDistance();
            loadedChunksAtSettle = client.level == null
                    ? -1 : client.level.getChunkSource().getLoadedChunksCount();
        });
    }

    private static void settleWorldgen(ClientGameTestContext context) {
        if (VANILLA_ONLY) {
            settleWorldgenVanilla(context);
            return;
        }
        // Sodium as the host is the same problem as VANILLA_ONLY and cost
        // eight minutes to rediscover: Meshelium's residency counters below
        // are fed by ITS chunk tap, and under Sodium that tap never fires
        // because Sodium owns the chunk build. The loop then compares 0 to
        // 0 for its whole budget and blames worldgen, with a counter dump
        // that is entirely zeros — which is the tell.
        //
        // The mod-list check is cheap and safe anywhere, so resolve the
        // host flag HERE rather than at the readiness wait: this is the
        // first place that needs it.
        if (MesheliumPlatform.isModLoaded(MesheliumGate.SODIUM_MOD_ID)) {
            sodiumHost = true;
            settleWorldgenForeignRenderer(context);
            return;
        }
        int budget = settleIterations();
        for (int i = 0; i < budget; i++) {
            long before = TerrainResidency.counters().uploadedSections();
            context.waitTicks(40);
            TerrainResidency.Counters c = TerrainResidency.counters();
            if (c.uploadedSections() == before && c.sectionsResident() > 0
                    && c.stagingBacklogEntries() == 0) {
                return;
            }
        }
        throw new AssertionError("bench world never settled: worldgen still streaming after "
                + (budget * 2) + "s at render distance " + benchRenderDistance + ". "
                + TerrainResidency.counters());
    }

    /**
     * The same settle, on a signal that exists when Meshelium does not.
     *
     * <p>The residency counters above are Meshelium's own and read zero
     * forever on a dormant backend, so the normal settle can only time out.
     * Vanilla's visible-section list is the equivalent observable: it grows
     * while chunks stream in and stops when the world is built.</p>
     */
    private static void settleWorldgenVanilla(ClientGameTestContext context) {
        // Vanilla's visible-section list is the right signal when VANILLA
        // is the host renderer, and useless when it is not: a mod that
        // replaces the chunk renderer leaves that list empty forever, so
        // the loop below would spin its whole budget against zero and then
        // blame worldgen. Measured exactly that against Sodium 0.9.2 —
        // "vanilla visible sections still changing: 0" after 160 seconds
        // of a world that had in fact finished loading long before.
        //
        // So pick the signal from what is actually drawing. Loaded chunks
        // is renderer-independent — it is world state — and it is the only
        // honest readiness signal available for a foreign renderer without
        // mixing into it.
        if (MesheliumPlatform.isModLoaded(MesheliumGate.SODIUM_MOD_ID)) {
            settleWorldgenForeignRenderer(context);
            return;
        }
        int[] count = new int[1];
        for (int i = 0; i < 240; i++) {
            context.runOnClient(client ->
                    count[0] = client.levelRenderer.visibleSections().size());
            int before = count[0];
            context.waitTicks(40);
            context.runOnClient(client ->
                    count[0] = client.levelRenderer.visibleSections().size());
            if (count[0] == before && count[0] > 0) {
                return;
            }
        }
        throw new AssertionError("bench world never settled on the host renderer "
                + "(vanilla visible sections still changing): " + count[0]);
    }

    /**
     * Settle when neither Meshelium nor vanilla owns the renderer.
     *
     * <p>Waits for the client's loaded-chunk count to stop moving, which
     * says worldgen and streaming have finished. That is strictly weaker
     * than the other two signals: it proves the WORLD is ready, not that
     * the renderer has finished building it. Nothing better is observable
     * from outside a renderer this test does not hook.
     *
     * <p>The extra settle afterwards is the compensation, and it is
     * deliberately generous. A bench that starts measuring while chunks
     * are still being built measures the build, not the draw — and a
     * number produced that way is worse than no number, because it looks
     * like a result.
     */
    private static void settleWorldgenForeignRenderer(ClientGameTestContext context) {
        int[] loaded = new int[1];
        // A plateau is SETTLE_PLATEAU_SAMPLES consecutive 2-second samples
        // with the same loaded count, not one: at rd96 (31k chunks,
        // 2026-09-13) generation stalled for seconds and resumed, one
        // unchanged sample let the legs start on a half-built world, and
        // the harness's own leg-agreement rule threw three runs away.
        int plateau = 0;
        for (int i = 0; i < settleIterations(); i++) {
            context.runOnClient(client ->
                    loaded[0] = client.level == null
                            ? -1 : client.level.getChunkSource().getLoadedChunksCount());
            int before = loaded[0];
            context.waitTicks(40);
            context.runOnClient(client ->
                    loaded[0] = client.level == null
                            ? -1 : client.level.getChunkSource().getLoadedChunksCount());
            plateau = (loaded[0] == before && loaded[0] > 0) ? plateau + 1 : 0;
            if (plateau >= SETTLE_PLATEAU_SAMPLES) {
                // Chunks have stopped arriving. Give the renderer a long
                // run at building what arrived last before trusting a
                // frame time - and then PROVE the frame time has settled.
                // At rd96 (31k chunks, 2026-09-13) the loaded count
                // plateaued while Sodium's builder still had tens of
                // thousands of sections to mesh, and leg 1 measured that
                // build storm at 6.4 ms against leg 3's 1.9; the harness's
                // own sanity rule threw the run away. The observable that
                // exists on every host is the frame itself.
                context.waitTicks(200);
                settleFrameTime(context);
                return;
            }
        }
        throw new AssertionError("bench world never settled on a foreign renderer "
                + "(client loaded-chunk count still changing): " + loaded[0]);
    }

    /**
     * Wait until two consecutive windows of captured frame deltas agree
     * within {@link #SETTLE_FRAME_TOLERANCE}, within the settle budget.
     * A world still being built by any renderer shows as a falling frame
     * time; a settled one repeats to within a few percent (this harness
     * repeats to 0.1% within a session once it does).
     */
    private static void settleFrameTime(ClientGameTestContext context) {
        double previous = -1.0;
        for (int i = 0; i < settleIterations(); i++) {
            context.runOnClient(client -> MesheliumBenchRecorder.arm(SETTLE_FRAME_WINDOW));
            context.waitFor(client -> MesheliumBenchRecorder.filled() >= SETTLE_FRAME_WINDOW,
                    CAPTURE_TIMEOUT_TICKS);
            double mean = meanMs(MesheliumBenchRecorder.snapshot());
            context.runOnClient(client -> MesheliumBenchRecorder.disarm());
            if (previous > 0 && mean > 0
                    && Math.abs(mean - previous) <= SETTLE_FRAME_TOLERANCE * Math.max(mean, previous)) {
                System.out.println("[Meshelium] bench frame time settled: " + round3(previous)
                        + " -> " + round3(mean) + " ms over two windows of " + SETTLE_FRAME_WINDOW
                        + " frames (" + (i + 1) + " windows)");
                return;
            }
            previous = mean;
            context.waitTicks(20);
        }
        throw new AssertionError("bench frame time never settled: the last window read "
                + round3(previous) + " ms and the one before differed by more than "
                + (int) (SETTLE_FRAME_TOLERANCE * 100) + "%");
    }

    /** Frames per settle window: about a second at the frame rates measured here. */
    private static final int SETTLE_FRAME_WINDOW = 400;

    /** Consecutive unchanged 2-second loaded-chunk samples that count as a plateau (ten seconds). */
    private static final int SETTLE_PLATEAU_SAMPLES = 5;

    /** Two consecutive windows within this fraction of each other count as settled. */
    private static final double SETTLE_FRAME_TOLERANCE = 0.04;
}
