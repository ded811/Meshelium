/*
 * Meshelium — LGPL-3.0-only.
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

    private static final String SEED = "4242";
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

    @Override
    public void runTest(ClientGameTestContext context) {
        String scene = MesheliumBenchRecorder.sceneName();
        if (scene == null) {
            return; // not a bench run (belt and braces — build.gradle
                    // already keeps this class out of normal entrypoints)
        }
        if (!"vulkan".equalsIgnoreCase(System.getProperty("meshelium.test.expectBackend", ""))) {
            throw new AssertionError("benchmark requires -Pmeshelium.backend=vulkan");
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
            server.runCommand("time set noon");
            server.runCommand("gamerule doDaylightCycle false");
            server.runCommand("weather clear");
            server.runCommand("gamerule doWeatherCycle false");
            server.runCommand("gamerule doMobSpawning false");
            server.runCommand("gamerule randomTickSpeed 0");
            server.runCommand("gamemode spectator @p");
            server.runCommand(scene.startsWith("ground-") ? GROUND_CAMERA_TP : CAMERA_TP);
            server.runCommand("kill @e[type=!minecraft:player]");
            settleWorldgen(context);

            // Meshelium must be live before anything is measured.
            context.waitFor(client -> TerrainDrawer.framesDrawn() > 0
                    && TerrainDrawer.lastDrawnSections() > 0, READY_TIMEOUT_TICKS);
            assertNoErrors();
            quiesce(context);
            context.takeScreenshot(TestScreenshotOptions.of("90_bench_" + scene));

            // ---- leg 1: Meshelium (CPU frame series + GPU pass series +
            // wave-12 CPU stage rows — armed by default on bench runs) ----
            int total = WARMUP_FRAMES + MEASURED_FRAMES;
            context.runOnClient(client -> {
                MesheliumGpuTimers.armCapture(total);
                MesheliumBenchRecorder.arm(total);
                MesheliumCpuStages.armCapture(total);
            });
            context.waitFor(client -> MesheliumBenchRecorder.filled() >= total,
                    CAPTURE_TIMEOUT_TICKS);
            // Let the lagged GPU readback drain what it can, then stop.
            context.waitTicks(10);
            context.runOnClient(client -> {
                MesheliumGpuTimers.disarmCapture();
                MesheliumCpuStages.disarmCapture();
            });
            long[] mesheliumCpu = tail(MesheliumBenchRecorder.snapshot(), MEASURED_FRAMES);
            long[] gpuRows = MesheliumGpuTimers.captureSnapshot();
            int gpuRowCount = gpuRows.length / MesheliumGpuTimers.PASSES;
            Map<String, Object> mesheliumStages = cpuStagesReport();
            Map<String, Object> mesheliumCounters = counters();
            assertNoErrors();
            // Wave-12: a skipVanillaPrep leg is only a valid A/B when the
            // prediction never missed (each miss = one vanilla frame drawn
            // from an empty prep — a frame the comparison must not contain).
            if (MesheliumConfig.skipVanillaPrepEnabled() && TerrainDrawer.prepSkipHoleFrames() > 0) {
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
            context.runOnClient(client -> System.setProperty(
                    com.deds.meshelium.MesheliumExtendedRd.PROPERTY_BENCH_NO_CLAMP, "true"));
            context.runOnClient(client -> System.setProperty(TerrainDrawer.PROPERTY, "false"));
            context.waitTicks(5);
            int framesAtFlip = TerrainDrawer.framesDrawn();
            context.waitTicks(5);
            if (TerrainDrawer.framesDrawn() != framesAtFlip) {
                throw new AssertionError("drawer kept recording after the flip - the vanilla "
                        + "leg would not be a clean baseline");
            }
            context.runOnClient(client -> {
                MesheliumBenchRecorder.arm(total);
                MesheliumCpuStages.armCapture(total);
            });
            context.waitFor(client -> MesheliumBenchRecorder.filled() >= total,
                    CAPTURE_TIMEOUT_TICKS);
            context.runOnClient(client -> MesheliumCpuStages.disarmCapture());
            long[] vanillaCpu = tail(MesheliumBenchRecorder.snapshot(), MEASURED_FRAMES);
            Map<String, Object> vanillaStages = cpuStagesReport();
            context.runOnClient(client -> System.setProperty(TerrainDrawer.PROPERTY, "true"));
            context.runOnClient(client -> System.clearProperty(
                    com.deds.meshelium.MesheliumExtendedRd.PROPERTY_BENCH_NO_CLAMP));
            // Record what the vanilla leg actually held, so a reader can
            // tell a real baseline from a clamped one without trusting the
            // harness: the JSON carries the number, not a promise.
            context.runOnClient(client ->
                    vanillaRenderDistance[0] = client.options.getEffectiveRenderDistance());
            context.waitFor(client -> TerrainDrawer.framesDrawn() > framesAtFlip,
                    READY_TIMEOUT_TICKS);
            assertNoErrors();

            // ---- report ----
            writeReport(scene, renderDistance, vanillaRenderDistance[0], mesheliumCpu,
                    vanillaCpu, gpuRows, gpuRowCount, mesheliumCounters, mesheliumStages,
                    vanillaStages);
        }
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    private static void writeReport(String scene, int renderDistance,
            int vanillaRenderDistance, long[] mesheliumCpu,
            long[] vanillaCpu, long[] gpuRows, int gpuRowCount,
            Map<String, Object> mesheliumCounters, Map<String, Object> mesheliumStages,
            Map<String, Object> vanillaStages) {
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
        root.put("camera", (scene.startsWith("ground-") ? GROUND_CAMERA_TP : CAMERA_TP)
                + " (spectator)");
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
        root.put("warmupFrames", WARMUP_FRAMES);
        root.put("measuredFrames", MEASURED_FRAMES);

        Map<String, Object> meshelium = new LinkedHashMap<>();
        meshelium.put("cpuFrameNanos", mesheliumCpu);
        meshelium.put("cpuSummaryMs", summarizeMs(mesheliumCpu));
        meshelium.put("counters", mesheliumCounters);
        meshelium.put("gpu", gpuReport(gpuRows, gpuRowCount));
        meshelium.put("cpuStages", mesheliumStages);
        root.put("meshelium", meshelium);

        Map<String, Object> vanilla = new LinkedHashMap<>();
        vanilla.put("cpuFrameNanos", vanillaCpu);
        vanilla.put("cpuSummaryMs", summarizeMs(vanillaCpu));
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
        return s;
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
}
