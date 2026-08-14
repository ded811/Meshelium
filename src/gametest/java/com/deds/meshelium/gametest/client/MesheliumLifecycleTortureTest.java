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
import com.deds.meshelium.VanillaTerrainCensus;
import com.deds.meshelium.terrain.host.TerrainResidency;
import com.deds.meshelium.terrain.host.VanillaUploadSeam;
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

        assertSeamHandover();
        optionsScreenSmoke(context);

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            waitForDrawing(context, "initial world");
            assertConfigGraduationLive(context);
            assertResourceReloadSurvives(context);
        }

        assertRendererSwap(context);
        assertRenderDistanceRestored(context);
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
        // A BEFORE shot, because counters cannot see this failure. A reload
        // destroys and recreates the block atlas, and a renderer holding a
        // stale texture view would carry on drawing frames perfectly
        // happily - the counters below would all pass - while sampling a
        // dead or reused texture. The only witness is the picture.
        //
        // Structurally this should be safe and the shots are here to prove
        // it rather than to assume it: the atlas view is fetched per frame
        // (LevelRendererMixin:265, vanilla's own TextureManager lookup) and
        // every descriptor is a PUSH descriptor written per draw
        // (vkCmdPushDescriptorSetKHR, TerrainDrawer:1730,1769), so there is
        // no descriptor set and no cached handle anywhere to go stale.
        // That is an argument, not evidence.
        context.takeScreenshot(TestScreenshotOptions.of("82_meshelium_before_resource_reload"));

        context.runOnClient(client -> client.reloadResourcePacks());
        // The reload overlay may or may not be caught mid-flight; what
        // matters is that it is GONE and the drawer then records real
        // frames again with no fresh latch.
        context.waitFor(client -> client.gui.overlay() == null, TIMEOUT);
        long frames = TerrainDrawer.framesDrawn();
        context.waitFor(client -> TerrainDrawer.framesDrawn() > frames + 20
                && TerrainDrawer.lastDrawnSections() > 0, TIMEOUT);
        assertNoErrors();

        // Same camera, same world, same settings, after the atlas was torn
        // down and rebuilt. The coordinator diffs 82 against 83; anything
        // beyond animated-sprite noise means the terrain path is sampling
        // something it should have re-fetched.
        context.takeScreenshot(TestScreenshotOptions.of("83_meshelium_after_resource_reload"));
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
            //
            // NOT INSTANTLY, though, and the difference is the ownership
            // rule. If the upload seam suppressed anything this world then
            // vanilla's buffers are EMPTY, so handing it the frame the
            // instant the guard trips would show a blank world rather than a
            // holey one. Meshelium keeps drawing until a rebuild has put
            // vanilla back. Waiting for that is a stronger assertion than
            // the freeze alone: it proves the handover actually completes,
            // which is precisely what was broken when the ownership rule
            // turned out to live in a method with no callers.
            if (VanillaUploadSeam.suppressedThisWorld()) {
                try {
                    context.waitFor(client -> VanillaUploadSeam.vanillaHasGeometry(), TIMEOUT);
                } catch (Throwable t) {
                    throw new AssertionError("the coverage guard tripped with the upload seam "
                            + "armed and vanilla never came back, so nothing would be drawing "
                            + "(armed=" + VanillaUploadSeam.armed()
                            + ", demoteReason=" + VanillaUploadSeam.demoteReason() + ")", t);
                }
            }
            long frames = TerrainDrawer.framesDrawn();
            long cancels = TerrainDrawer.cancelledGroups();
            long transCancels = TerrainDrawer.cancelledTranslucentGroups();
            context.waitTicks(10);
            if (TerrainDrawer.framesDrawn() != frames
                    || TerrainDrawer.cancelledGroups() != cancels
                    || TerrainDrawer.cancelledTranslucentGroups() != transCancels) {
                throw new AssertionError("drawer kept owning groups after the coverage guard "
                        + "went passive and vanilla was whole again - holes were possible");
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
    // Leg 10 - WITHDRAWN: freeing the arena on world exit
    // ------------------------------------------------------------------
    //
    // A leg was written here to prove that quitting to the menu hands the
    // terrain arena straight back, rather than parking it until the next
    // world. The observation behind it is correct: vanilla's dispose only
    // fires from LevelExtractor.extract(), which does not run without a
    // level, so the teardown genuinely waits for the next world load.
    //
    // The change it was written for is wrong, and the existing retention leg
    // said so on the first run: "vanilla keeps its meshes at the title
    // screen but Meshelium's store went to zero - frees are firing from a
    // path vanilla does not free on". Vanilla holds its terrain at the menu
    // deliberately, so rejoining the world you just left is instant, and
    // Meshelium's residency mirrors vanilla's. Free early and vanilla still
    // believes its meshes are uploaded while ours are gone, so a rejoin
    // finds no reason to rebuild and the world comes back empty.
    //
    // Recorded rather than deleted because the idea is an obvious one to
    // have twice.

    // ------------------------------------------------------------------
    // Leg 11 - switching Meshelium back on gives the distance back
    // ------------------------------------------------------------------

    /**
     * Turning Meshelium off pulls the render distance to vanilla's 32, and
     * turning it back on must put the player's distance back.
     *
     * <p>Asserts the OPTION, which is the thing that was actually broken and
     * the thing a screenshot cannot settle. The first version of this feature
     * logged and toasted "put the render distance back to 120" and the owner
     * saw 32 on the slider, which reads as the restore silently failing. The
     * value was fine; Video Settings had been handed back as a cached screen
     * whose slider widget was built while the range still ended at 32. Two
     * bug reports, one cause, and only a real assertion on the number tells
     * them apart.</p>
     *
     * <p>40 rather than 120 on purpose: it is past vanilla's ceiling, so it
     * exercises the widened range and the clamp, without asking the harness
     * to generate a 120-chunk world.</p>
     */
    private static void assertRenderDistanceRestored(ClientGameTestContext context) {
        final int raised = 40;
        final int rdBefore = context.computeOnClient(client -> client.options.renderDistance().get());
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            context.runOnClient(client -> {
                // The property outranks the config, and the harness always
                // sets it, so the master switch is unreachable until it goes.
                System.clearProperty("meshelium.terrainDraw");
                MesheliumConfig.get().enableTerrainRendering = true;
                client.options.renderDistance().set(raised);
                client.options.save();
            });
            int start = context.computeOnClient(client -> client.options.renderDistance().get());
            if (start != raised) {
                throw new AssertionError("could not raise the render distance to " + raised
                        + " past vanilla's ceiling, so this leg proves nothing (got " + start
                        + "); the option range is not widened");
            }

            context.runOnClient(client -> MesheliumConfig.get().enableTerrainRendering = false);
            try {
                context.waitFor(client -> client.options.renderDistance().get() <= 32, TIMEOUT);
            } catch (Throwable t) {
                throw new AssertionError("Meshelium was switched off and the extended render "
                        + "distance was not clamped back to vanilla's maximum", t);
            }

            context.runOnClient(client -> MesheliumConfig.get().enableTerrainRendering = true);
            try {
                context.waitFor(client -> client.options.renderDistance().get() == raised, TIMEOUT);
            } catch (Throwable t) {
                throw new AssertionError("Meshelium came back on and the render distance stayed at "
                        + context.computeOnClient(c -> c.options.renderDistance().get())
                        + " instead of the " + raised + " it was clamped from", t);
            }
        } finally {
            context.runOnClient(client -> {
                MesheliumConfig.get().enableTerrainRendering = true;
                System.setProperty("meshelium.terrainDraw", "true");
                // Put the distance back too. Leaving it at 40 made the NEXT
                // leg's waitForChunksRender time out, which reads as that leg
                // failing; a test that raises the render distance has to hand
                // it back or it breaks whatever runs after it.
                client.options.renderDistance().set(rdBefore);
                client.options.save();
            });
        }
    }

    // ------------------------------------------------------------------
    // Leg 9 - the mid-world renderer swap, with suppression armed
    // ------------------------------------------------------------------

    /**
     * Turn Meshelium off and on again in a live world with vanilla's uploads
     * suppressed, and require that SOMEBODY is drawing terrain at the end of
     * each half.
     *
     * <p>This is the test the owner had to be the first to run. The mod
     * shipped with the master switch reading the config directly at
     * ChunkSectionsToRenderMixin and LevelRendererMixin, while the ownership
     * rule and its demote() sat in TerrainDrawer.enabled() with ZERO callers
     * anywhere in the repository. So flipping the switch off stopped
     * Meshelium drawing that same frame, never told the seam, and left
     * vanilla's uploads cancelled forever. Nobody drew, and not for a few
     * frames: permanently. The world was see-through.</p>
     *
     * <p>The assertion that catches it is the census. Screenshots would not
     * have: the seam was cancelling uploads, so vanilla's own draw calls ran
     * against empty buffers and the frame was structurally valid and utterly
     * empty. {@code VanillaTerrainCensus.committedBytes()} asks the only
     * question that matters after a handover to vanilla, which is whether
     * vanilla actually has anything to draw.</p>
     */
    private static void assertRendererSwap(ClientGameTestContext context) {
        boolean suppressWas = MesheliumConfig.get().suppressVanillaUploads;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.runOnClient(client -> {
                MesheliumConfig.get().suppressVanillaUploads = true;
                VanillaUploadSeam.onSettingChanged();
            });
            singleplayer.getClientLevel().waitForChunksRender();
            waitForDrawing(context, "swap leg, suppression armed");

            // --- Meshelium -> vanilla -------------------------------------
            // The property has to go first. terrainRenderingEnabled() is
            // `meshelium.terrainDraw` ?? config, the harness always sets that
            // property, and a set property WINS - so writing the config field
            // alone would leave the draw hook happily enabled and this test
            // would pass without ever switching anything off.
            context.runOnClient(client -> {
                System.clearProperty("meshelium.terrainDraw");
                MesheliumConfig.get().enableTerrainRendering = false;
            });
            try {
                // Vanilla must end up holding real terrain buffers. -1 means
                // the census could not read the dispatcher, which is not a
                // pass.
                context.waitFor(client -> VanillaTerrainCensus.committedBytes() > 0, TIMEOUT);
            } catch (Throwable t) {
                throw new AssertionError("Meshelium was switched off with the upload seam armed "
                        + "and vanilla never got its terrain back (census="
                        + VanillaTerrainCensus.committedBytes()
                        + ", suppressedThisWorld=" + VanillaUploadSeam.suppressedThisWorld()
                        + ", armed=" + VanillaUploadSeam.armed()
                        + ", vanillaHasGeometry=" + VanillaUploadSeam.vanillaHasGeometry()
                        + ", demoteReason=" + VanillaUploadSeam.demoteReason()
                        + ") - this is the see-through world", t);
            }
            assertNoErrors();

            // And the other half of "dump one, then load the other": with
            // Meshelium off, its arena must go back to the driver rather
            // than sit there holding a copy of a world it is not drawing.
            // Without this the two renderers are resident at once, which on
            // an 8 GB card is the difference between working and not.
            try {
                context.waitFor(client ->
                        TerrainResidency.counters().arenaCapacityBytes() == 0, TIMEOUT);
            } catch (Throwable t) {
                throw new AssertionError("Meshelium was switched off but kept its "
                        + (TerrainResidency.counters().arenaCapacityBytes() >> 20)
                        + " MiB arena (sectionsResident="
                        + TerrainResidency.counters().sectionsResident()
                        + ", pendingFrees=" + TerrainResidency.counters().pendingFreeRanges()
                        + ", stagingBacklog="
                        + TerrainResidency.counters().stagingBacklogEntries()
                        + ") - both renderers are resident at once", t);
            }

            // --- vanilla -> Meshelium -------------------------------------
            context.runOnClient(client -> MesheliumConfig.get().enableTerrainRendering = true);
            singleplayer.getClientLevel().waitForChunksRender();
            waitForDrawing(context, "swap leg, Meshelium switched back on");
        } finally {
            context.runOnClient(client -> {
                MesheliumConfig.get().enableTerrainRendering = true;
                MesheliumConfig.get().suppressVanillaUploads = suppressWas;
                System.setProperty("meshelium.terrainDraw", "true");
            });
        }
    }

    // ------------------------------------------------------------------
    // Leg 8 - the upload seam handover, the empty-ground regression
    // ------------------------------------------------------------------

    /**
     * The seam must never hand the frame back to vanilla before vanilla has
     * actually rebuilt.
     *
     * <p>Pure static state, so no world and no GPU: this drives the machine
     * by hand and puts it back. It exists because the first version shipped
     * with a hole that a GPU test could not have found. The completion
     * signal is {@code LevelRenderer.hasRenderedAllSections()}, which is
     * only {@code isQueueEmpty()}, and a settled world has an empty queue
     * ALREADY - so "complete" read true from the first frame after
     * demotion, the handover fired before the rebuild was issued, and the
     * owner's ground went blank. Every assertion below is that bug seen
     * from a different angle.</p>
     */
    private static void assertSeamHandover() {
        VanillaUploadSeam.resetForWorld();
        try {
            if (!VanillaUploadSeam.vanillaHasGeometry()) {
                throw new AssertionError("a world where nothing was suppressed must trust vanilla");
            }

            VanillaUploadSeam.noteSuppressed();
            if (VanillaUploadSeam.vanillaHasGeometry()) {
                throw new AssertionError(
                        "a suppressed section must mark vanilla's copy incomplete");
            }

            VanillaUploadSeam.demote("lifecycle torture");

            // The regression itself: settled world, empty queue, "complete"
            // true every frame, rebuild not even issued yet.
            for (int i = 0; i < 200; i++) {
                VanillaUploadSeam.noteRebuildProgress(true);
            }
            if (VanillaUploadSeam.vanillaHasGeometry()) {
                throw new AssertionError("handed the frame to vanilla before the rebuild was "
                        + "issued - this is the blank-ground bug");
            }

            if (!VanillaUploadSeam.consumeRebuildRequest()) {
                throw new AssertionError("demote must ask for a rebuild, or vanilla never returns");
            }

            // Issued, and the queue has not been seen to fill. A short calm
            // must NOT be read as finished: that is the blank-ground bug one
            // step later.
            for (int i = 0; i < 30; i++) {
                VanillaUploadSeam.noteRebuildProgress(true);
            }
            if (VanillaUploadSeam.vanillaHasGeometry()) {
                throw new AssertionError("handed over after a short calm on a queue that was "
                        + "never seen busy - an empty queue only means finished after it "
                        + "meant working");
            }

            // A LONG calm does hand over, and must. The busy frame is
            // sampled at 20 Hz and a small world rebuilds inside one tick,
            // so requiring it absolutely deadlocks the handover and
            // Meshelium owns a holey frame forever.
            for (int i = 0; i < 200; i++) {
                VanillaUploadSeam.noteRebuildProgress(true);
            }
            if (!VanillaUploadSeam.vanillaHasGeometry()) {
                throw new AssertionError("never handed back although the rebuild was issued and "
                        + "the queue stayed calm - a rebuild too fast to catch would strand "
                        + "Meshelium as owner for the rest of the world");
            }

            // And the fast path still works: seen busy, then done.
            VanillaUploadSeam.resetForWorld();
            VanillaUploadSeam.noteSuppressed();
            VanillaUploadSeam.demote("lifecycle torture, fast path");
            VanillaUploadSeam.consumeRebuildRequest();
            VanillaUploadSeam.noteRebuildProgress(false);
            for (int i = 0; i < 25; i++) {
                VanillaUploadSeam.noteRebuildProgress(true);
            }
            if (!VanillaUploadSeam.vanillaHasGeometry()) {
                throw new AssertionError("a rebuild seen to run and then finish did not hand "
                        + "back within the short floor");
            }
        } finally {
            VanillaUploadSeam.resetForWorld();
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
