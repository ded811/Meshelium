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

import com.deds.meshelium.MesheliumBenchRecorder;
import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumCpuStages;
import com.deds.meshelium.MesheliumVulkanState;
import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.terrain.host.TerrainResidency;
import com.deds.meshelium.vk.MesheliumGpuTimers;
import com.deds.meshelium.vk.TerrainDrawer;

import com.google.gson.GsonBuilder;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private static final int WARMUP_FRAMES = 120;
    private static final int MEASURED_FRAMES = 600;
    private static final int READY_TIMEOUT_TICKS = 1200;
    private static final int CAPTURE_TIMEOUT_TICKS = 3600;

    private static final Map<String, Integer> SCENES = Map.of(
            // The release curve (owner directive 2026-08-11: "make sure we
            // focus on the fps improvements ... use different render
            // distances too. and give real numbers"). rd 8 and 24 exist so
            // the published table shows the SHAPE of the win, not just its
            // peak: the advantage grows with scene weight, and a reader on
            // a modest machine cares about the low end.
            "plains-rd8", 8,
            // 12 is VANILLA'S OWN DEFAULT render distance, so it is the
            // single most important cell on the published curve: it is
            // what a player who never touches the slider actually gets.
            "plains-rd12", 12,
            "plains-rd16", 16,
            "plains-rd24", 24,
            "plains-rd32", 32,
            // Wave-10 extended scenes: REQUIRE -Pmeshelium.rd=<value> too
            // (widens the option range at boot; the bench sets the option
            // BEFORE world creation, so the login ClientInformation carries
            // it - the mid-world save() lesson does not bite here, and
            // save() is called anyway for symmetry below).
            "plains-rd48", 48,
            "plains-rd64", 64,
            // Ground level variants, same seed and same spot, looking
            // along the terrain instead of down at it.
            "ground-rd8", 8,
            "ground-rd32", 32,
            "ground-rd64", 64);

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
        quiesce(context);
    }

    private static void awaitCapture(ClientGameTestContext context, int total) {
        if (SPIN_DEGREES_PER_TICK == 0) {
            context.waitFor(client -> MesheliumBenchRecorder.filled() >= total,
                    CAPTURE_TIMEOUT_TICKS);
            return;
        }
        for (int tick = 0; tick < CAPTURE_TIMEOUT_TICKS; tick++) {
            if (MesheliumBenchRecorder.filled() >= total) {
                return;
            }
            context.runOnClient(client -> {
                if (client.player != null) {
                    client.player.setYRot((float) (client.player.getYRot() + SPIN_DEGREES_PER_TICK));
                    client.player.yRotO = client.player.getYRot();
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
        return CAMERA_TP;
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

            // Deterministic freezes (the parity protocol's set) + the
            // pinned spectator camera.
            server.runCommand("time set " + TIME_OF_DAY);
            server.runCommand("gamerule doDaylightCycle false");
            server.runCommand("weather clear");
            server.runCommand("gamerule doWeatherCycle false");
            server.runCommand("gamerule doMobSpawning false");
            server.runCommand("gamerule randomTickSpeed 0");
            server.runCommand("gamemode spectator @p");
            server.runCommand(cameraFor(scene));
            server.runCommand("kill @e[type=!minecraft:player]");
            settleWorldgen(context);

            // Meshelium must be live before anything is measured. Skipped
            // in vanilla-only mode, where it never will be: on the GL
            // backend the gate keeps the drawer dormant by design, and this
            // wait would simply time out.
            if (!VANILLA_ONLY) {
                context.waitFor(client -> TerrainDrawer.framesDrawn() > 0
                        && TerrainDrawer.lastDrawnSections() > 0, READY_TIMEOUT_TICKS);
                assertNoErrors();
            }
            quiesce(context);
            // Build what a pinned camera never looks at, so a spinning
            // measurement measures spinning (see prewarmAllDirections).
            prewarmAllDirections(context);
            context.takeScreenshot(TestScreenshotOptions.of("90_bench_" + scene));

            // ---- leg 1: Meshelium (CPU frame series + GPU pass series +
            // wave-12 CPU stage rows — armed by default on bench runs) ----
            int total = WARMUP_FRAMES + MEASURED_FRAMES;
            context.runOnClient(client -> {
                MesheliumGpuTimers.armCapture(total);
                MesheliumBenchRecorder.arm(total);
                MesheliumCpuStages.armCapture(total);
            });
            awaitCapture(context, total);
            // Let the lagged GPU readback drain what it can, then stop.
            context.waitTicks(10);
            context.runOnClient(client -> {
                MesheliumGpuTimers.disarmCapture();
                MesheliumCpuStages.disarmCapture();
            });
            long[] mesheliumCpu = tail(MesheliumBenchRecorder.snapshot(), MEASURED_FRAMES);
            long[] gpuRows = MesheliumGpuTimers.captureSnapshot();
            int gpuRowCount = gpuRows.length / MesheliumGpuTimers.PASSES;
            // Every counter below reaches into TerrainDrawer, which is a
            // Vulkan-only class. In vanilla-only mode it is dormant (GL) and
            // every figure would read zero, so skip rather than load it.
            Map<String, Object> mesheliumStages = VANILLA_ONLY ? Map.of() : cpuStagesReport();
            Map<String, Object> mesheliumCounters = VANILLA_ONLY ? Map.of() : counters();
            if (!VANILLA_ONLY) {
                assertNoErrors();
            }
            // Wave-12: a skipVanillaPrep leg is only a valid A/B when the
            // prediction never missed (each miss = one vanilla frame drawn
            // from an empty prep — a frame the comparison must not contain).
            if (!VANILLA_ONLY && MesheliumConfig.skipVanillaPrepEnabled()
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
            if (!VANILLA_ONLY) {
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
            } else {
                framesAtFlip = 0;
            }
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
            if (!VANILLA_ONLY) {
                context.runOnClient(client -> System.setProperty(TerrainDrawer.PROPERTY, "true"));
                context.runOnClient(client -> System.clearProperty(
                        com.deds.meshelium.MesheliumExtendedRd.PROPERTY_BENCH_NO_CLAMP));
            }
            // Record what the vanilla leg actually held, so a reader can
            // tell a real baseline from a clamped one without trusting the
            // harness: the JSON carries the number, not a promise.
            context.runOnClient(client ->
                    vanillaRenderDistance[0] = client.options.getEffectiveRenderDistance());
            if (!VANILLA_ONLY) {
                context.waitFor(client -> TerrainDrawer.framesDrawn() > framesAtFlip,
                        READY_TIMEOUT_TICKS);
                assertNoErrors();
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
            // framebuffer is queried straight from GLFW so a clamped or
            // refused window is visible rather than silently equal.
            int[] fb = new int[6];
            context.runOnClient(client -> {
                fb[0] = client.getWindow().getWidth();
                fb[1] = client.getWindow().getHeight();
                fb[2] = client.getWindow().getScreenWidth();
                fb[3] = client.getWindow().getScreenHeight();
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer w = stack.mallocInt(1);
                    IntBuffer h = stack.mallocInt(1);
                    GLFW.glfwGetFramebufferSize(client.getWindow().handle(), w, h);
                    fb[4] = w.get(0);
                    fb[5] = h.get(0);
                }
            });

            // ---- report ----
            writeReport(scene, renderDistance, vanillaRenderDistance[0], mesheliumCpu,
                    vanillaCpu, gpuRows, gpuRowCount, mesheliumCounters, mesheliumStages,
                    vanillaStages, fb);
        }
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    private static void writeReport(String scene, int renderDistance,
            int vanillaRenderDistance, long[] mesheliumCpu,
            long[] vanillaCpu, long[] gpuRows, int gpuRowCount,
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
        root.put("seed", SEED);
        root.put("timeOfDay", TIME_OF_DAY);
        root.put("spinDegreesPerTick", SPIN_DEGREES_PER_TICK);
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
        knobs.put("translucentMultiWG",
                System.getProperty(TerrainDrawer.PROPERTY_TRANSLUCENT_MULTI_WG, "false"));
        // Wave-12 knobs + arm state — the JSON must say which candidates
        // were live so no leg can be misfiled during the sweep.
        knobs.put("skipVanillaPrep", MesheliumConfig.skipVanillaPrepEnabled());
        knobs.put("cachedCull", MesheliumConfig.cachedCullEnabled());
        knobs.put("cpuStagesArmed", MesheliumCpuStages.ARMED);
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
        comparison.put("note", "whole-frame CPU times, same world/camera/session; "
                + "GPU pass times exist only for the Meshelium leg and are never "
                + "summed with CPU times");
        root.put("comparison", comparison);

        String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        Path out = FabricLoader.getInstance().getGameDir()
                .resolve("meshelium-bench-" + scene + ".json");
        try {
            Files.writeString(out, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not write the bench report to " + out, e);
        }
        MesheliumClient.LOGGER.info("meshelium bench [" + scene + "]: meshelium mean "
                + round3(mesheliumMean) + " ms vs vanilla mean " + round3(vanillaMean)
                + " ms over " + MEASURED_FRAMES + " frames (ratio vanilla/meshelium "
                + comparison.get("vanillaOverMeshelium") + "); raw series + GPU pass times in "
                + out.getFileName());
    }

    private static Map<String, Object> gpuReport(long[] rows, int rowCount) {
        Map<String, Object> gpu = new LinkedHashMap<>();
        gpu.put("framesCaptured", rowCount);
        gpu.put("note", "per-pass GPU nanos between vanilla pass-end barriers, "
                + "frame-3 lagged readback; -1 = pass absent that frame; "
                + "warmup rows included (first ~" + WARMUP_FRAMES + ")");
        String[] names = {"opaqueA", "regionRaster", "sectionRaster", "phaseB", "translucent"};
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
        return out;
    }

    private static Map<String, Object> counters() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("framesDrawn", TerrainDrawer.framesDrawn());
        c.put("occlusionFrames", TerrainDrawer.occlusionFrames());
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
        return c;
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
    private static void settleWorldgen(ClientGameTestContext context) {
        if (VANILLA_ONLY) {
            settleWorldgenVanilla(context);
            return;
        }
        for (int i = 0; i < 240; i++) { // 8 min budget: rd64 noise gen is ~16k chunks
            long before = TerrainResidency.counters().uploadedSections();
            context.waitTicks(40);
            TerrainResidency.Counters c = TerrainResidency.counters();
            if (c.uploadedSections() == before && c.sectionsResident() > 0
                    && c.stagingBacklogEntries() == 0) {
                return;
            }
        }
        throw new AssertionError("bench world never settled (worldgen still "
                + "streaming after ~3 minutes): " + TerrainResidency.counters());
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
}
