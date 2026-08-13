/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Wave 8 acceptance: lifecycle torture + the config graduation + the
 * coverage guard, on the real GPU. Active ONLY on the
 * `-Pmeshelium.backend=vulkan -Pmeshelium.terrain=true` harness run; on every
 * other run it returns immediately.
 */
package com.deds.meshelium.gametest.client;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.gui.MesheliumOptionsScreen;
import com.deds.meshelium.terrain.host.TerrainResidency;
import com.deds.meshelium.vk.TerrainDrawer;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import net.minecraft.client.gui.screens.TitleScreen;

/**
 * The wave-8 torture harness, six legs since wave 14:
 *
 * <ol>
 *   <li><b>Options screen smoke</b> (title screen): open
 *       {@link MesheliumOptionsScreen}, screenshot
 *       {@code 70_meshelium_options_screen}, close via [Done], back at the
 *       title — the screen exists, lays out, and does not crash.</li>
 *   <li><b>Config graduation, live</b> (in-world): clear the
 *       {@code meshelium.terrainDraw} property — drawing must CONTINUE,
 *       because {@code config.enableTerrainRendering} (default TRUE) now
 *       rules; flip the config field false — the drawer freezes within a
 *       few frames (restart-not-required, the toggle's exact semantics);
 *       true again — it revives. Same protocol for
 *       {@code occlusionMode} against the occlusion/bfsOnly frame
 *       counters, plus AUTO's crossover driven from the live effective
 *       render distance in BOTH directions (a mode stuck on is as wrong as
 *       one stuck off, and only the pair proves the comparison runs).
 *       Property restored afterwards.</li>
 *   <li><b>Resource reload</b> (the F3+T equivalent, in-world):
 *       {@code Minecraft.reloadResourcePacks()} (javap-verified name,
 *       returns CompletableFuture&lt;Void&gt;), wait the overlay out, then
 *       the drawer must resume recording frames with sections and ZERO new
 *       error latches — reload clears vanilla's pipeline cache
 *       ({@code ShaderManager} → {@code clearPipelineCache}, jar caller
 *       census), which must not touch Meshelium's own pipelines.</li>
 *   <li><b>Three fast world hops</b>: each new world must observe the
 *       PREVIOUS renderer's dispose (the note-11 lifecycle:
 *       {@code LevelRenderer.close()} runs at the NEXT level's spin-up —
 *       {@link TerrainResidency#lastDisposeSnapshot()} is the dispose log
 *       line's programmatic twin) and then draw again from a fresh
 *       residency, no error latches.</li>
 *   <li><b>Arena growth (wave 14)</b>: {@code meshelium.tune.arenaInitialMiB=1}
 *       forces a tiny INITIAL arena under the NORMAL device-derived
 *       ceiling in a throwaway world — the same world size that forces
 *       drops in leg 6 must instead GROW: {@code arenaGrowths} > 0,
 *       capacity climbs past the initial, ZERO drops, guard stays clean,
 *       the drawer keeps drawing (screenshot
 *       {@code 81_meshelium_arena_growth}), and the outgrown backings
 *       retire fence-safely ({@code MesheliumTerrainGpu.arenaBuffersRetired}
 *       catches up with the growth count; the retire path's explicit
 *       FREE_FRAME_LAG assert would latch an error if violated —
 *       {@code assertNoErrors} is its witness).</li>
 *   <li><b>Coverage guard</b>: {@code meshelium.test.arenaMiB=1} forces a
 *       tiny initial AND (since wave 14) a tiny CEILING, so growth is
 *       exhausted from the first failure and droppedArenaFull fires in a
 *       throwaway world — the guard must go passive (kill switch stops
 *       cancelling: frame/cancel counters FREEZE while vanilla draws
 *       everything — no holes possible, screenshot
 *       {@code 80_meshelium_coverage_guard_vanilla_draws}), WARN exactly once
 *       ({@code coverageTrips} +1, stable), the trip cause must NAME the
 *       arena budget ({@code TerrainResidency.guardTrip()}, wave 14), no
 *       growth may have fired (initial == ceiling), with {@code lastError}
 *       still null (passive is not an error); then a normal world with the
 *       property cleared re-enables drawing (clean counters re-arm the
 *       switch).</li>
 * </ol>
 *
 * <p><b>Window resize is SKIPPED, honestly:</b> the fabric client-gametest
 * API has no resize/setWindowSize surface (ClientGameTestContext javap'd
 * 5.1.1: waitFor/screenshots/input/worldBuilder only) — resize coverage
 * stays UNVERIFIED beyond the wave-2 static argument (viewport/scissor
 * dynamic, formats resolution-independent, per-frame UBOs transient).
 * Device-loss cannot be forced on healthy hardware; its handling
 * (catch → latch → passive at every drawer/pump boundary) ships
 * code-reviewed but UNVERIFIED at runtime.</p>
 *
 * <p><b>Clean close</b> is the run itself: the harness client shuts down
 * after the tests, which drives the wave-8 device-close sweep
 * ({@code VulkanDeviceMixin} → pipelines destroyed after vanilla's
 * waitIdle); the coordinator reads the "device-lifetime objects destroyed"
 * log line and the absence of validation-layer complaints as the
 * evidence.</p>
 */
public final class MesheliumLifecycleTortureTest implements FabricClientGameTest {

    private static final int TIMEOUT = 1200;

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean vulkanRun = "vulkan".equalsIgnoreCase(
                System.getProperty("meshelium.test.expectBackend", "opengl"));
        boolean terrainRun = Boolean.getBoolean("meshelium.terrainDraw");
        if (!vulkanRun || !terrainRun) {
            return;
        }

        optionsScreenSmoke(context);

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            waitForDrawing(context, "initial world");
            assertConfigGraduationLive(context);
            assertResourceReloadSurvives(context);
        }

        assertWorldHops(context);
        assertArenaGrowth(context);
        assertCoverageGuard(context);
    }

    // ------------------------------------------------------------------
    // Leg 5 — wave-14 arena growth (tiny initial, normal ceiling)
    // ------------------------------------------------------------------

    private static void assertArenaGrowth(ClientGameTestContext context) {
        long growthsBefore = TerrainResidency.counters().arenaGrowths();
        long retiredBefore = com.deds.meshelium.vk.MesheliumTerrainGpu.arenaBuffersRetired();
        context.runOnClient(client -> System.setProperty("meshelium.tune.arenaInitialMiB", "1"));
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            // The same world content that overflows a FIXED 1 MiB arena in
            // the guard leg must grow right through it here.
            context.waitFor(client ->
                    TerrainResidency.counters().arenaGrowths() > growthsBefore, TIMEOUT);
            TerrainResidency.Counters c = TerrainResidency.counters();
            if (c.arenaCapacityBytes() <= (1L << 20)) {
                throw new AssertionError("arenaGrowths moved but capacity is still "
                        + c.arenaCapacityBytes() + " bytes - grow() did not raise the limit");
            }
            if (TerrainResidency.dropsThisWorld() != 0) {
                throw new AssertionError("sections were dropped although growth is available "
                        + "(a drop beat growth to the guard): " + c);
            }
            if (TerrainDrawer.coveragePassive()) {
                throw new AssertionError("coverage guard went passive in the growth leg - "
                        + "growth must keep the guard clean");
            }
            if (TerrainResidency.guardTrip() != null) {
                throw new AssertionError("guard trip cause set without a drop: "
                        + TerrainResidency.guardTrip());
            }
            waitForDrawing(context, "arena growth leg");
            // Fence-safety: every outgrown backing is destroyed once its
            // FREE_FRAME_LAG matures (the retire loop re-asserts the lag
            // and would latch an error on violation — assertNoErrors below
            // is that assert's witness). Retirements trail the newest
            // growth by up to the lag, so the bar is catch-up to all but
            // the last possibly-still-in-lag buffer.
            long growthsNow = TerrainResidency.counters().arenaGrowths();
            context.waitFor(client ->
                    com.deds.meshelium.vk.MesheliumTerrainGpu.arenaBuffersRetired() - retiredBefore
                            >= growthsNow - growthsBefore - 1, TIMEOUT);
            context.takeScreenshot(TestScreenshotOptions.of("81_meshelium_arena_growth"));
            assertNoErrors();
        } finally {
            context.runOnClient(client ->
                    System.clearProperty("meshelium.tune.arenaInitialMiB"));
        }
    }

    // ------------------------------------------------------------------
    // Leg 1 — options screen smoke
    // ------------------------------------------------------------------

    private static void optionsScreenSmoke(ClientGameTestContext context) {
        context.runOnClient(client ->
                client.gui.setScreen(new MesheliumOptionsScreen(client.gui.screen())));
        context.waitTicks(2);
        context.runOnClient(client -> {
            if (!(client.gui.screen() instanceof MesheliumOptionsScreen)) {
                throw new AssertionError("options screen did not open; got " + client.gui.screen());
            }
        });
        context.takeScreenshot(TestScreenshotOptions.of("70_meshelium_options_screen"));

        // Wave-15: the custom cap box, end-to-end at the healthy-Vulkan
        // title (this run's gate is open; skipped when a -D override
        // locks the row). Out-of-range input must be unacceptable; an
        // in-range >96 value must land in the config AND the live option
        // range the same tick, and the slider label must refresh.
        // 1.1: the custom box is INLINE on this screen, not a sub-screen,
        // so the leg drives it in place. Same three properties: refuse
        // out-of-range, refuse non-numeric, and land an in-range >96 value
        // in the config AND the live option range the same tick with the
        // slider label following. The commit is on Enter/blur rather than
        // per keystroke, so the leg commits explicitly.
        if (System.getProperty("meshelium.maxRenderDistance") == null) {
            int[] originalCap = new int[1];
            context.runOnClient(client -> {
                var screen = (com.deds.meshelium.gui.MesheliumOptionsScreen) client.gui.screen();
                originalCap[0] = MesheliumConfig.get().maxRenderDistance;

                screen.testSetCapBoxText("121");
                screen.testCommitCapBox();
                if (MesheliumConfig.get().maxRenderDistance == 121) {
                    throw new AssertionError("the inline cap box accepted 121, above the 120 "
                            + "hard max (the signed-byte wire cliff)");
                }
                if (!screen.testCapBoxText().equals(Integer.toString(originalCap[0]))) {
                    throw new AssertionError("the inline cap box kept a rejected value instead "
                            + "of reverting: '" + screen.testCapBoxText() + "'");
                }

                screen.testSetCapBoxText("meshelium");
                // The invalid state must still be READABLE. The first
                // inline-box build passed every value assertion here while
                // rendering the text fully transparent, because it set an
                // RGB colour where EditBox wants ARGB. Alpha 0 is never a
                // legitimate state for text meant to be read.
                if (screen.testCapBoxTextAlpha() == 0) {
                    throw new AssertionError("the inline cap box's text is fully transparent "
                            + "while showing invalid input; it needs an ARGB colour with alpha "
                            + "set, not a bare RGB one");
                }
                screen.testCommitCapBox();
                if (!screen.testCapBoxText().equals(Integer.toString(originalCap[0]))) {
                    throw new AssertionError("the inline cap box kept non-numeric input: '"
                            + screen.testCapBoxText() + "'");
                }
                if (screen.testCapBoxTextAlpha() == 0) {
                    throw new AssertionError("the inline cap box's text is fully transparent "
                            + "after reverting to a valid value");
                }

                screen.testSetCapBoxText("112");
                screen.testCommitCapBox();
            });
            context.waitTicks(1);
            context.runOnClient(client -> {
                var screen = (com.deds.meshelium.gui.MesheliumOptionsScreen) client.gui.screen();
                if (MesheliumConfig.get().maxRenderDistance != 112) {
                    throw new AssertionError("the inline cap box's 112 did not reach the "
                            + "config: " + MesheliumConfig.get().maxRenderDistance);
                }
                if (client.options.renderDistance().values().validateValue(112).isEmpty()) {
                    throw new AssertionError("custom cap 112 did not widen the live option "
                            + "range the same tick");
                }
                if (!screen.capSliderText().contains("112")) {
                    throw new AssertionError("cap slider label did not refresh after the "
                            + "inline box write: '" + screen.capSliderText() + "'");
                }
                screen.testSetCap(originalCap[0]);
            });
        }

        context.clickScreenButton("gui.done");
        context.waitForScreen(TitleScreen.class);
    }

    // ------------------------------------------------------------------
    // Leg 2 — config graduation, live semantics
    // ------------------------------------------------------------------

    private static void assertConfigGraduationLive(ClientGameTestContext context) {
        // Property cleared → config (default TRUE) rules → drawing continues.
        context.runOnClient(client -> System.clearProperty("meshelium.terrainDraw"));
        waitForDrawing(context, "property cleared, config default TRUE");
        try {
            // Config OFF, no property → the kill switch stops within frames.
            context.runOnClient(client -> MesheliumConfig.get().enableTerrainRendering = false);
            context.waitTicks(3); // >= 2 frames: the mixin re-reads per renderGroup call
            long frames = TerrainDrawer.framesDrawn();
            long cancels = TerrainDrawer.cancelledGroups();
            context.waitTicks(5);
            if (TerrainDrawer.framesDrawn() != frames || TerrainDrawer.cancelledGroups() != cancels) {
                throw new AssertionError("drawer kept running after enableTerrainRendering=false "
                        + "with no property set - the config toggle is not live");
            }
            // Config ON again → revives (restart-not-required, proven).
            context.runOnClient(client -> MesheliumConfig.get().enableTerrainRendering = true);
            waitForDrawing(context, "config toggled back on");

            // Occlusion toggle: OFF → the wave-5 BFS path draws (bfsOnly
            // frames advance, occlusion frames freeze); ON → occlusion
            // resumes. Mirrors the wave-6 property-flip protocol.
            context.runOnClient(client ->
                    MesheliumConfig.get().occlusionMode = MesheliumConfig.OcclusionMode.OFF);
            long bfsBefore = TerrainDrawer.bfsOnlyFrames();
            context.waitFor(client -> TerrainDrawer.bfsOnlyFrames() > bfsBefore, TIMEOUT);
            long occAtFlip = TerrainDrawer.occlusionFrames();
            context.waitTicks(10);
            if (TerrainDrawer.occlusionFrames() != occAtFlip) {
                throw new AssertionError("occlusion frames advanced with occlusionMode=OFF "
                        + "- the config setting is not live");
            }
            context.runOnClient(client ->
                    MesheliumConfig.get().occlusionMode = MesheliumConfig.OcclusionMode.ON);
            context.waitFor(client -> TerrainDrawer.occlusionFrames() > occAtFlip, TIMEOUT);
            assertNoErrors();

            // AUTO decides against the EFFECTIVE render distance, so drive
            // it from the live value rather than assuming the harness's.
            // Both directions, because a mode that is stuck on is as wrong
            // as one that is stuck off and only the pair proves the
            // comparison is really being evaluated.
            int[] rd = new int[1];
            context.runOnClient(client -> rd[0] = client.options.getEffectiveRenderDistance());

            // Crossover ABOVE the current distance: Auto must NOT arm.
            context.runOnClient(client -> {
                MesheliumConfig config = MesheliumConfig.get();
                config.occlusionAutoMinRenderDistance = rd[0] + 1;
                config.occlusionMode = MesheliumConfig.OcclusionMode.AUTO;
            });
            long bfsBeforeAuto = TerrainDrawer.bfsOnlyFrames();
            context.waitFor(client -> TerrainDrawer.bfsOnlyFrames() > bfsBeforeAuto, TIMEOUT);
            long occAtAuto = TerrainDrawer.occlusionFrames();
            context.waitTicks(10);
            if (TerrainDrawer.occlusionFrames() != occAtAuto) {
                throw new AssertionError("Auto armed occlusion at render distance " + rd[0]
                        + " with a crossover of " + (rd[0] + 1) + "; it must only arm at or "
                        + "ABOVE the crossover");
            }

            // Crossover AT the current distance: Auto must arm (>=, not >).
            context.runOnClient(client ->
                    MesheliumConfig.get().occlusionAutoMinRenderDistance = rd[0]);
            context.waitFor(client -> TerrainDrawer.occlusionFrames() > occAtAuto, TIMEOUT);
            assertNoErrors();
        } finally {
            // Restore the harness property + config defaults for whatever
            // test runs next in this client session.
            context.runOnClient(client -> {
                MesheliumConfig config = MesheliumConfig.get();
                config.enableTerrainRendering = true;
                // Explicit ON, not AUTO: the harness world runs at a low
                // render distance where AUTO would correctly decide OFF,
                // and a later test trusting the default would then measure
                // the BFS path while believing it measured occlusion.
                config.occlusionMode = MesheliumConfig.OcclusionMode.ON;
                config.occlusionAutoMinRenderDistance =
                        MesheliumConfig.DEFAULT_OCCLUSION_AUTO_RD;
                System.setProperty("meshelium.terrainDraw", "true");
            });
        }
    }

    // ------------------------------------------------------------------
    // Leg 3 — resource reload (F3+T equivalent)
    // ------------------------------------------------------------------

    private static void assertResourceReloadSurvives(ClientGameTestContext context) {
        context.runOnClient(client -> client.reloadResourcePacks());
        // The reload overlay may or may not be caught mid-flight; what
        // matters is that it is GONE and the drawer then records real
        // frames again with no fresh latch.
        context.waitFor(client -> client.gui.overlay() == null, TIMEOUT);
        long frames = TerrainDrawer.framesDrawn();
        context.waitFor(client -> TerrainDrawer.framesDrawn() > frames + 20
                && TerrainDrawer.lastDrawnSections() > 0, TIMEOUT);
        assertNoErrors();
    }

    // ------------------------------------------------------------------
    // Leg 4 — three fast world hops
    // ------------------------------------------------------------------

    private static void assertWorldHops(ClientGameTestContext context) {
        TerrainResidency.Counters previousDispose = TerrainResidency.lastDisposeSnapshot();
        for (int hop = 1; hop <= 3; hop++) {
            final TerrainResidency.Counters before = previousDispose;
            final int hopNumber = hop;
            try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
                // Note-11 lifecycle: the PREVIOUS renderer's close() (and so
                // the dispatcher dispose + Meshelium's teardown/baseline
                // reset) runs while THIS world spins up.
                try {
                    context.waitFor(client ->
                            TerrainResidency.lastDisposeSnapshot() != before, TIMEOUT);
                } catch (Throwable t) {
                    throw new AssertionError("hop " + hopNumber + ": no dispatcher dispose "
                            + "observed during world creation - the note-11 teardown did not run", t);
                }
                previousDispose = TerrainResidency.lastDisposeSnapshot();
                singleplayer.getClientLevel().waitForChunksRender();
                waitForDrawing(context, "world hop " + hopNumber);
            }
        }
    }

    // ------------------------------------------------------------------
    // Leg 6 — the coverage guard (wave-8 deliverable 3's harness half;
    // wave-14 trip condition: growth exhausted, budget named)
    // ------------------------------------------------------------------

    private static void assertCoverageGuard(ClientGameTestContext context) {
        long tripsBefore = TerrainDrawer.coverageTrips();
        long growthsBefore = TerrainResidency.counters().arenaGrowths();
        context.runOnClient(client -> System.setProperty("meshelium.test.arenaMiB", "1"));
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            // The 1 MiB arena (16384 quads) fills in the first pumps —
            // and since wave 14 the property also pins the CEILING to
            // 1 MiB, so growth is exhausted before it can start.
            context.waitFor(client -> TerrainResidency.dropsThisWorld() > 0, TIMEOUT);
            context.waitFor(client -> TerrainDrawer.coveragePassive(), TIMEOUT);
            if (TerrainDrawer.coverageTrips() != tripsBefore + 1) {
                throw new AssertionError("coverage guard tripped but the once-only WARN count is "
                        + TerrainDrawer.coverageTrips() + " (expected " + (tripsBefore + 1) + ")");
            }
            // Wave-14: the trip must NAME the arena budget, and no growth
            // may have fired (initial == ceiling by the test property).
            TerrainResidency.GuardTrip trip = TerrainResidency.guardTrip();
            if (trip == null || !"arena".equals(trip.kind())) {
                throw new AssertionError("guard tripped without naming the arena budget: "
                        + trip);
            }
            if (trip.limit() != 1 || trip.value() != 1) {
                throw new AssertionError("arena trip should report 1 MiB capacity at the "
                        + "1 MiB ceiling, got " + trip);
            }
            if (TerrainResidency.counters().arenaGrowths() != growthsBefore) {
                throw new AssertionError("the arena grew although initial == ceiling - "
                        + "meshelium.test.arenaMiB no longer pins the ceiling");
            }
            // Passive means the kill switch stops cancelling: vanilla draws
            // EVERY group (no holes possible), so the drawer's frame and
            // cancel counters freeze while the world keeps rendering.
            long frames = TerrainDrawer.framesDrawn();
            long cancels = TerrainDrawer.cancelledGroups();
            long transCancels = TerrainDrawer.cancelledTranslucentGroups();
            context.waitTicks(10);
            if (TerrainDrawer.framesDrawn() != frames
                    || TerrainDrawer.cancelledGroups() != cancels
                    || TerrainDrawer.cancelledTranslucentGroups() != transCancels) {
                throw new AssertionError("drawer kept owning groups while the coverage guard "
                        + "was passive - holes were possible");
            }
            if (TerrainDrawer.coverageTrips() != tripsBefore + 1) {
                throw new AssertionError("coverage guard WARNed more than once for one world");
            }
            context.takeScreenshot(
                    TestScreenshotOptions.of("80_meshelium_coverage_guard_vanilla_draws"));
            // Passive is a designed state, not a failure: no error latches.
            assertNoErrors();
        } finally {
            context.runOnClient(client -> System.clearProperty("meshelium.test.arenaMiB"));
        }

        // A normal world re-arms the switch: fresh baseline, clean
        // counters, drawing resumes.
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            waitForDrawing(context, "post-guard normal world");
            if (TerrainDrawer.coveragePassive()) {
                throw new AssertionError("coverage guard still passive in a clean world - "
                        + "the world-load re-arm is broken");
            }
        }
    }

    // ------------------------------------------------------------------
    // Shared plumbing
    // ------------------------------------------------------------------

    /** The drawer records fresh frames with real sections; no latches. */
    private static void waitForDrawing(ClientGameTestContext context, String where) {
        long frames = TerrainDrawer.framesDrawn();
        try {
            context.waitFor(client -> TerrainDrawer.framesDrawn() > frames
                    && TerrainDrawer.lastDrawnSections() > 0, TIMEOUT);
        } catch (Throwable t) {
            throw new AssertionError("drawer never resumed at: " + where
                    + " (framesDrawn stuck at " + TerrainDrawer.framesDrawn()
                    + ", lastError=" + TerrainDrawer.lastError()
                    + ", residencyError=" + TerrainResidency.lastError() + ")", t);
        }
        assertNoErrors();
    }

    private static void assertNoErrors() {
        String drawError = TerrainDrawer.lastError();
        if (drawError != null) {
            throw new AssertionError("terrain drawer reported an error: " + drawError);
        }
        String residencyError = TerrainResidency.lastError();
        if (residencyError != null) {
            throw new AssertionError("terrain residency reported an error: " + residencyError);
        }
        String occError = TerrainDrawer.occlusionError();
        if (occError != null) {
            throw new AssertionError("occlusion culling reported an error: " + occError);
        }
        if (TerrainDrawer.deviceLost()) {
            throw new AssertionError("device-loss latch set during the torture run");
        }
    }
}
