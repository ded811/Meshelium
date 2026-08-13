/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Wave 4 acceptance: mesh-shader terrain vs vanilla, same world, same
 * camera, two PNGs. Active ONLY on the `-Pmeshelium.backend=vulkan
 * -Pmeshelium.terrain=true` harness run; on every other run it returns
 * immediately (the boot smoke + residency tests already prove dormancy).
 */
package com.deds.meshelium.gametest.client;

import com.deds.meshelium.terrain.host.TerrainResidency;
import com.deds.meshelium.vk.MesheliumGpuTimers;
import com.deds.meshelium.vk.TerrainDrawer;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CloudStatus;

/**
 * The parity protocol (docs/VANILLA-FRAME-PATH.md wave-4 notes):
 *
 * <ol>
 *   <li>Determinism first — clouds OFF (client option), fixed noon, clear
 *       weather, day/weather cycles and mob spawning and random ticks all
 *       frozen via server commands, stray entities killed. Both shots share
 *       whatever HUD is up (identical pixels cancel out in the compare).</li>
 *   <li>Wait for chunks to render, the drawer to record frames, and the
 *       build pipeline to quiesce (encode counter stable + staging backlog
 *       empty) so section fade-in and uploads cannot differ between the
 *       shots.</li>
 *   <li>Shot 40 with the mesh-shader path ON.</li>
 *   <li>Flip the {@code meshelium.terrainDraw} system property OFF on the
 *       client thread — the kill-switch mixin re-reads it every renderGroup
 *       call, so vanilla's own OPAQUE draws resume within a frame (vanilla
 *       kept building + uploading its buffers the whole time: the wave-4
 *       kill switch only cancels DRAWS) — wait a few ticks, shot 41 from
 *       the untouched camera.</li>
 *   <li>Assert the drawer actually froze after the flip (no cancelled
 *       groups, no drawn frames), then restore the property.</li>
 * </ol>
 *
 * The coordinator pixel-compares 40 vs 41 offline (perceptual threshold).
 * Known benign diff sources, both localized: animated atlas sprites (water/
 * lava advance a few frames between the shots) and any entity animation the
 * kill commands missed. Structural diffs — missing sections, wrong facing
 * buckets, lighting/fog mismatches — are the failures this pair exists to
 * catch. Since wave 5, shot 40 is taken with GPU task-stage culling live,
 * so the same pair is also the culling-correctness detector: a culling bug
 * shows up as missing sections in shot 40.
 *
 * <p><b>Wave-5 additions (after the parity pair, camera free to move):</b>
 * (a) regionsDispatched ∈ (0, regionsLive]; (b) a 180° camera turn changes
 * the dispatched-region set (culling responds to the camera — the
 * residency test's tp-command walk pattern, turned into a rotation);
 * (c) the {@code meshelium.terrainDraw.cpuCull} escape hatch still renders
 * (its frame counter and section counter move) so the wave-4 fallback
 * stays honest.</p>
 *
 * <p><b>Wave-6 additions (occlusion is the default task path, so shots
 * 40/41 already exercise it — these add the culling-specific evidence):</b>
 * (d) hidden-occluder scene: a large stone wall is {@code fill}ed in front
 * of a repositioned camera; after settling, the GPU-counted sections drawn
 * with occlusion ON must be strictly below the bfsOnly count (occlusion
 * removes work the BFS flood keeps), the two modes screenshot as
 * {@code 50_meshelium_occlusion_on} / {@code 51_meshelium_occlusion_off_bfs}
 * for the coordinator's pixel-compare (identical modulo animated sprites —
 * culling may remove only hidden work), and the occlusion frame counter
 * provably freezes while bfsOnly is up (the fallback is a true wave-5
 * revert) then resumes; (e) camera-cut: a 180° teleport must produce
 * phase-B draws within 2 stats frames of the dispatch-set change (the
 * latency hider), with no error latch anywhere.</p>
 *
 * <p><b>Wave-7 additions (the TRANSLUCENT kill switch + resorts):</b>
 * (f) a translucency scene — sealed water tank, stained-glass stack,
 * framed nether portal — built in a pinned north view; shots
 * {@code 60_meshelium_translucent} / {@code 61_vanilla_translucent_reference}
 * via the same property-flip protocol, with the translucent frame/section/
 * cancel counters proven live before shot 60 and frozen for shot 61 (the
 * coordinator pixel-compares at the SAME threshold as 40/41 — blending
 * turns order bugs into color shifts, so no extra tolerance); (g) resorts:
 * small camera moves force vanilla resorts of the nearby tank (bytecode
 * trigger cited on the method), {@code resortsApplied} must advance in a
 * window where {@code encodedSections} is flat (resorts never re-encode),
 * {@code resortBytes} must move (the permuted prefixes reach the GPU),
 * and a 17-block strafe across the section-grid POV threshold advances
 * the counter again.</p>
 */
public final class MesheliumTerrainDrawTest implements FabricClientGameTest {

    private static final int DRAW_TIMEOUT_TICKS = 1200;

    // ---- wave-10 rd-leg budgets ----
    /** Server-follow round trip (option → tickServer → radius packet). */
    private static final int RD_TIMEOUT_TICKS = 1200;
    /**
     * Residency-growth budget at the extended distance: worldgen-bound
     * (the leg only needs the &gt;700-region crossing, not full
     * generation, but a NORMAL world generating thousands of chunks paces
     * everything — the wave-9 bench's worldgen-contention lesson).
     */
    private static final long RD_GROWTH_BUDGET_NANOS = 300L * 1_000_000_000L; // 5 min
    /**
     * The documented ceiling of regions an rd-32 grid can touch
     * (RegionStore wave-3b derivation: ~10×7×10 = 700); crossing it is
     * the "resident well beyond the rd-32 ceiling" proof.
     */
    private static final int RD32_REGION_CEILING = 700;

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean vulkanRun = "vulkan".equalsIgnoreCase(
                System.getProperty("meshelium.test.expectBackend", "opengl"));
        boolean terrainRun = Boolean.getBoolean("meshelium.terrainDraw");
        if (!vulkanRun || !terrainRun) {
            return; // only the -Pmeshelium.terrain=true Vulkan run exercises wave 4
        }

        // ARM OCCLUSION for the wave-5 and wave-6 legs below. The shipped
        // default is AUTO (measurements on MesheliumConfig.occlusionMode),
        // and this harness world runs at a low render distance where AUTO
        // correctly decides OFF. These legs exist to prove the machinery
        // works when it IS on, so they arm it explicitly through the
        // property rather than lean on a default they do not own. Dropped
        // again after the wave-6 legs, below.
        context.runOnClient(client ->
                System.setProperty(TerrainDrawer.PROPERTY_BFS_ONLY, "false"));

        context.runOnClient(client -> client.options.cloudStatus().set(CloudStatus.OFF));

        // Wave-13 arrangement: the buffers-from-option proof needs exactly
        // "option <= 32, ceiling > 32, standard sizes pinned" — the
        // harness's own boot option (rd 5) IS that arrangement. Do NOT
        // raise the option here: waitForChunksRender has a fixed timeout
        // and every extra chunk of render distance is real NORMAL-world
        // generation inside it (the wave-9 bench lesson; a set(12) here
        // timed this test out at 625 columns). The set+save production
        // write path is exercised by the rd-48 leg below.
        context.runOnClient(client -> {
            int option = client.options.renderDistance().get();
            if (option > 32) {
                throw new AssertionError("arrangement broken: harness boot option "
                        + option + " is not <= 32");
            }
        });

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            freezeWorld(singleplayer);
            singleplayer.getClientLevel().waitForChunksRender();

            // The drawer must be live: frames recorded, sections drawn, no error.
            context.waitFor(client ->
                    TerrainDrawer.framesDrawn() > 0 && TerrainDrawer.lastDrawnSections() > 0,
                    DRAW_TIMEOUT_TICKS);
            assertNoErrors();

            // ---- wave-13: buffers pin from the OPTION, not the ceiling;
            // the option range re-applies LIVE from the config setter ----
            assertScalingPinnedFromOption(context);
            assertLiveRangeApplication(context);
            if (TerrainDrawer.cancelledGroups() == 0) {
                throw new AssertionError("drawer recorded frames but never cancelled "
                        + "vanilla's OPAQUE renderGroup - the kill switch is not firing");
            }

            quiesce(context);
            context.takeScreenshot(TestScreenshotOptions.of("40_meshelium_terrain_meshshader"));
            assertNoErrors();
            if (TerrainDrawer.lastDrawnSections() <= 0) {
                throw new AssertionError("no sections drawn at the evidence screenshot");
            }

            // ---- the vanilla twin: flip the live-read property OFF ----
            context.runOnClient(client -> System.setProperty(TerrainDrawer.PROPERTY, "false"));
            context.waitTicks(3); // >= 2 frames: the mixin re-reads per renderGroup call
            long framesAtFlip = TerrainDrawer.framesDrawn();
            long cancelsAtFlip = TerrainDrawer.cancelledGroups();
            context.takeScreenshot(TestScreenshotOptions.of("41_vanilla_terrain_reference"));
            context.waitTicks(3);
            if (TerrainDrawer.framesDrawn() != framesAtFlip
                    || TerrainDrawer.cancelledGroups() != cancelsAtFlip) {
                throw new AssertionError("drawer kept running after the property flip - "
                        + "shot 41 is not a clean vanilla reference");
            }
            assertNoErrors();

            context.runOnClient(client -> System.setProperty(TerrainDrawer.PROPERTY, "true"));

            // ---- wave-9 assertion: GPU timers live on real hardware ----
            assertGpuTimersLive(context);

            // ---- wave-5 assertions (parity pair is done; camera may move) ----
            assertTaskCullingLive(context);
            assertCullingRespondsToCamera(context, singleplayer);
            assertCpuCullHatchRenders(context);

            // ---- wave-6 assertions ----
            assertHiddenWallOcclusion(context, singleplayer);
            assertCameraCutPhaseB(context, singleplayer);

            // Occlusion arming ends here: everything after this point runs
            // at the shipped 1.0.0 default, which is occlusion OFF.
            context.runOnClient(client ->
                    System.clearProperty(TerrainDrawer.PROPERTY_BFS_ONLY));

            // ---- wave-7 assertions ----
            assertTranslucentParity(context, singleplayer);
            assertResortsApplyWithoutReencode(context, singleplayer);

            // ---- wave-11: retained terrain (the infinite-horizon leg;
            // deterministic on SP because a render-distance DROP is a
            // controlled mass-release: extract sees the changed effective
            // rd -> allChanged -> releaseAllBuffers -> every slot reset,
            // all bytecode-cited in the wave-11 docs note) ----
            assertRetainedHorizon(context, singleplayer);
            // assertRetainedHorizon forces occlusion back ON for its own
            // shots (its finallys set the property rather than clearing
            // it, because clearing now means OFF). Drop it here so every
            // later leg, and every test after this class, sees the
            // shipped 1.0.0 default.
            context.runOnClient(client ->
                    System.clearProperty(TerrainDrawer.PROPERTY_BFS_ONLY));

            // ---- wave-10/15: the extended-render-distance leg (armed by
            // -Pmeshelium.rd=48 → meshelium.test.rd). Wave-15 semantics: this
            // world pinned STANDARD buffers (option was <=32 at standup),
            // so the mid-world raise to 48 is exactly the owner's
            // move-the-slider path — server follow is LIVE, the pump
            // GROWS the pinned budget in place (records grow-and-copy),
            // and NO rejoin hint fires (it is the failed-grow fallback). ----
            int testRd = Integer.getInteger("meshelium.test.rd", 0);
            if (testRd > 32) {
                assertExtendedRenderDistance(context, singleplayer, testRd);
            }
        }

        // ---- wave-13/15: a FRESH world at the still-raised option pins
        // extended from standup (no grow needed, no hint) — the rejoin
        // path stays correct even though wave 15 made it unnecessary. ----
        int testRd = Integer.getInteger("meshelium.test.rd", 0);
        if (testRd > 32) {
            assertRejoinAppliesFullBudget(context, testRd);
        }
    }

    // ------------------------------------------------------------------
    // Wave-13 assertions
    // ------------------------------------------------------------------

    /**
     * Buffers pin from the OPTION, not the config ceiling: this world
     * stood up at option 12 while the ceiling is &gt;32 (the wave-13
     * default 96, or the harness's 48), so the pinned snapshot must be
     * the STANDARD wave-≤9 sizes — the exact "player at rd 12 under a 96
     * ceiling pays nothing" guarantee. Also pins the ladder headroom
     * (unconditional +64, boot-time).
     */
    private static void assertScalingPinnedFromOption(ClientGameTestContext context) {
        context.runOnClient(client -> {
            com.deds.meshelium.MesheliumScaling.Snapshot pinned =
                    com.deds.meshelium.MesheliumScaling.pinned();
            if (pinned == null) {
                throw new AssertionError("no scaling snapshot pinned although the drawer "
                        + "is live - pinForWorld did not run at world standup");
            }
            int ceiling = com.deds.meshelium.MesheliumConfig.maxRenderDistanceConfigured();
            if (ceiling <= 32) {
                throw new AssertionError("test arrangement broken: ceiling " + ceiling
                        + " - the pin-from-option proof needs a >32 ceiling "
                        + "(wave-13 default is 96)");
            }
            if (pinned.extended()
                    || pinned.maxRegions() != com.deds.meshelium.MesheliumScaling.STANDARD_MAX_REGIONS
                    || pinned.dispatchCapacity()
                            != com.deds.meshelium.MesheliumScaling.STANDARD_DISPATCH_CAPACITY
                    || pinned.arenaBytes()
                            != com.deds.meshelium.MesheliumScaling.STANDARD_ARENA_BYTES) {
                throw new AssertionError("world at a <=32 option under ceiling " + ceiling
                        + " must pin the STANDARD sizes, got " + pinned
                        + " - buffers are still pinning from the ceiling (the wave-10 "
                        + "dead-end)");
            }
            int rungs = com.deds.meshelium.MesheliumExtendedRd.chunkTaskLadderRungs();
            if (rungs < 99) {
                throw new AssertionError("chunk-task ladder has " + rungs
                        + " rungs - the unconditional +64 widening regressed");
            }
            // Wave-14: the arena is elastic — the pin carries the INITIAL
            // size, and the growth ceiling must be device-derived here
            // (no meshelium.test.arenaMiB / tune override on this run).
            long heap = com.deds.meshelium.MesheliumVulkanState.deviceLocalHeapBytes();
            if (heap <= 0) {
                throw new AssertionError("no device-local heap recorded although a Vulkan "
                        + "world is drawing - the wave-14 memory probe regressed");
            }
            long ceilingBytes = com.deds.meshelium.MesheliumScaling.arenaCeilingBytes();
            if (ceilingBytes < com.deds.meshelium.MesheliumScaling.ARENA_CEILING_FLOOR_BYTES) {
                throw new AssertionError("arena ceiling " + ceilingBytes
                        + " below the 256 MiB floor (heap " + heap + ")");
            }
        });
    }

    /**
     * The live range application (the owner's "overriding the render
     * distance does nothing" fix, range half): the config setter path
     * ({@code onConfigChanged} — what the options-screen cap row calls)
     * must re-apply the vanilla option's ValueSet the same tick, both
     * directions, under the gate. Skipped when the harness property
     * overrides the config (rd runs): {@code maxRenderDistanceConfigured}
     * reads the property first, so config writes would be masked.
     */
    private static void assertLiveRangeApplication(ClientGameTestContext context) {
        if (System.getProperty("meshelium.maxRenderDistance") != null) {
            return;
        }
        context.runOnClient(client -> {
            var config = com.deds.meshelium.MesheliumConfig.get();
            int original = config.maxRenderDistance;
            try {
                // The wave-13 default ceiling (96) must already be live.
                if (!com.deds.meshelium.MesheliumExtendedRd.rangeWidened()) {
                    throw new AssertionError("default ceiling 96 did not widen the option "
                            + "range under VULKAN_MESH_SHADERS + terrain enabled");
                }
                if (client.options.renderDistance().values().validateValue(96).isEmpty()) {
                    throw new AssertionError("range widened but 96 is not a legal value");
                }
                if (com.deds.meshelium.MesheliumExtendedRd.serverViewDistanceCap() < 96) {
                    throw new AssertionError("server view-distance cap "
                            + com.deds.meshelium.MesheliumExtendedRd.serverViewDistanceCap()
                            + " does not follow the live ceiling 96");
                }
                // LOWER to 40: the ValueSet must narrow the same tick.
                config.maxRenderDistance = 40;
                com.deds.meshelium.MesheliumExtendedRd.onConfigChanged(client);
                if (client.options.renderDistance().values().validateValue(48).isPresent()) {
                    throw new AssertionError("ceiling lowered to 40 but 48 is still legal - "
                            + "the config setter is not applying the range live");
                }
                if (client.options.renderDistance().values().validateValue(40).isEmpty()) {
                    throw new AssertionError("ceiling 40 but 40 is not legal");
                }
                // FLOOR at 32: full vanilla restore, cap vanilla-exact.
                config.maxRenderDistance = 32;
                com.deds.meshelium.MesheliumExtendedRd.onConfigChanged(client);
                if (com.deds.meshelium.MesheliumExtendedRd.rangeWidened()
                        || client.options.renderDistance().values().validateValue(33).isPresent()) {
                    throw new AssertionError("ceiling 32 must restore the exact vanilla range");
                }
                if (com.deds.meshelium.MesheliumExtendedRd.serverViewDistanceCap() != 32) {
                    throw new AssertionError("ceiling 32 but the server cap is "
                            + com.deds.meshelium.MesheliumExtendedRd.serverViewDistanceCap()
                            + " (must be vanilla-exact 32)");
                }
                // RAISE back: widened again the same tick.
                config.maxRenderDistance = original;
                com.deds.meshelium.MesheliumExtendedRd.onConfigChanged(client);
                if (!com.deds.meshelium.MesheliumExtendedRd.rangeWidened()) {
                    throw new AssertionError("restoring the ceiling did not re-widen live");
                }
                // Wave-15: custom caps ABOVE the 96 slider stop, up to the
                // wire-bounded hard max 120 (signed-byte ClientInformation).
                config.maxRenderDistance = 112;
                com.deds.meshelium.MesheliumExtendedRd.onConfigChanged(client);
                if (client.options.renderDistance().values().validateValue(112).isEmpty()
                        || client.options.renderDistance().values().validateValue(120).isPresent()) {
                    throw new AssertionError("custom cap 112 did not become the live range "
                            + "(112 must be legal, 120 must not)");
                }
                // The wave-13 codec lesson at custom scale: the swapped
                // persistence codec must ACCEPT 112, or a saved custom
                // value would be silently reset at the next boot.
                var codec = ((com.deds.meshelium.fabric.mixin.OptionInstanceAccessor)
                        (Object) client.options.renderDistance()).meshelium$codec();
                @SuppressWarnings("unchecked")
                var intCodec = (com.mojang.serialization.Codec<Integer>) codec;
                var parsed = intCodec.parse(com.mojang.serialization.JsonOps.INSTANCE,
                        new com.google.gson.JsonPrimitive(112));
                if (parsed.result().isEmpty() || parsed.result().get() != 112) {
                    throw new AssertionError("the swapped persistence codec rejected 112: "
                            + parsed + " - a saved custom cap would not survive boot");
                }
                if (com.deds.meshelium.MesheliumExtendedRd.serverViewDistanceCap() != 112) {
                    throw new AssertionError("server view-distance cap did not follow the "
                            + "custom 112 ceiling: "
                            + com.deds.meshelium.MesheliumExtendedRd.serverViewDistanceCap());
                }
                // The hard max holds: a hand-edited 130 clamps to 120
                // (128+ would overflow the signed-byte view-distance wire).
                config.maxRenderDistance = 130;
                if (com.deds.meshelium.MesheliumConfig.maxRenderDistanceConfigured() != 120) {
                    throw new AssertionError("config 130 must clamp to the 120 hard max, got "
                            + com.deds.meshelium.MesheliumConfig.maxRenderDistanceConfigured());
                }
            } finally {
                config.maxRenderDistance = original;
                com.deds.meshelium.MesheliumExtendedRd.onConfigChanged(client);
            }
        });
    }

    /**
     * The wave-13 rejoin half: world 1 ended with the option still at the
     * extended value, so a FRESH world must pin the extended snapshot
     * ({@code min(ceiling, next-8 above option)}), stand the SSBO frame
     * lists up, draw, and NOT fire the rejoin hint (the budget now covers
     * the option). Deliberately no {@code waitForChunksRender} — a full
     * rd-48 send is minutes of streaming; the pin and the drawer's
     * liveness are the assertions, and the screenshot shows whatever
     * horizon exists after a short settle.
     */
    private static void assertRejoinAppliesFullBudget(ClientGameTestContext context, int rd) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            freezeWorld(singleplayer);
            context.waitFor(client ->
                    TerrainDrawer.framesDrawn() > 0 && TerrainDrawer.lastDrawnSections() > 0,
                    DRAW_TIMEOUT_TICKS);
            context.runOnClient(client -> {
                int option = client.options.renderDistance().get();
                if (option != rd) {
                    throw new AssertionError("world 2 opened with option " + option
                            + ", expected the leg's " + rd + " to have persisted");
                }
                com.deds.meshelium.MesheliumScaling.Snapshot pinned =
                        com.deds.meshelium.MesheliumScaling.pinned();
                int expected = Math.min(
                        com.deds.meshelium.MesheliumConfig.maxRenderDistanceConfigured(),
                        (rd / 8 + 1) * 8);
                if (pinned == null || !pinned.extended() || pinned.maxRd() != expected) {
                    throw new AssertionError("rejoin at option " + rd + " must pin extended "
                            + "maxRd=" + expected + ", got " + pinned);
                }
            });
            long hintsBefore = com.deds.meshelium.MesheliumExtendedRd.rejoinHints();
            context.waitTicks(60);
            if (com.deds.meshelium.MesheliumExtendedRd.rejoinHints() != hintsBefore) {
                throw new AssertionError("rejoin hint fired in a world whose pinned budget "
                        + "covers the option - the once-per-world keying is wrong");
            }
            assertNoErrors();
            context.takeScreenshot(TestScreenshotOptions.of("96_meshelium_rd48_rejoined"));
            assertNoErrors();
        }
    }

    // ------------------------------------------------------------------
    // Wave-11 assertion
    // ------------------------------------------------------------------

    /** Retention leg rd pair: both vanilla-legal, so this runs EVERY Vulkan+terrain run. */
    private static final int RETAIN_RD_HIGH = 16;
    private static final int RETAIN_RD_LOW = 8;

    /**
     * The wave-11 retained-terrain leg (owner directive, SPEC row 11:
     * toggle + settable time limit + limit-off). Deterministic on SP:
     * <ol>
     *   <li>rd {@value #RETAIN_RD_HIGH} the way the UI does it (set +
     *       {@code save()} — the wave-10 §2b broadcast lesson), wait for
     *       chunks beyond the future rd-{@value #RETAIN_RD_LOW} horizon,
     *       quiesce;</li>
     *   <li>DROP to rd {@value #RETAIN_RD_LOW}: vanilla swaps the ViewArea
     *       and releases every mesh through {@code reset()} —
     *       {@code retainedSections} must rise (the ring survives) and
     *       {@code retainedSuperseded} must follow (in-range rebuilds
     *       steal their slots back — the supersede path proven live);</li>
     *   <li>the deterministic beyond-live-residency proof: under bfsOnly
     *       (live-read property) {@code lastRetainedMaskSections} &gt; 0 —
     *       every counted section is drawn purely because it is retained
     *       (vanilla's visibleSections cannot list it), i.e. the drawn set
     *       strictly exceeds what rd-{@value #RETAIN_RD_LOW} residency
     *       alone could give — then back to occlusion mode; screenshot
     *       {@code A0_meshelium_retained_horizon} on the DEFAULT path;</li>
     *   <li>toggle OFF at runtime ({@code meshelium.retainTerrain=false},
     *       property overrides config, re-read per pump): every retained
     *       section evicts through the fence epochs
     *       ({@code evictedByDisable}), the bfs probe reads 0, screenshot
     *       {@code A1_meshelium_retention_off} from the same camera — the
     *       A0/A1 pair is EXPECTED to differ (the horizon vanishes);</li>
     *   <li>toggle back ON, re-expand, re-drop with
     *       {@code meshelium.retainSeconds=5}: retained evicts by AGE within
     *       the sweep budget ({@code evictedByAge} moves, set drains);</li>
     *   <li>limit OFF (clear the seconds property; config default 0 = NO
     *       LIMIT, the owner's "turn that limit off"): a fresh retained
     *       set survives a 15 s wait, three times the limit that just
     *       evicted everything, with {@code evictedByAge} frozen;</li>
     *   <li>cleanup: drain via the toggle, restore the original rd, and
     *       disarm the feature again.</li>
     * </ol>
     *
     * <p><b>The leg ARMS the feature itself (2026-08-11).</b>
     * {@code MesheliumConfig.retainTerrain} now defaults FALSE: retention
     * was retired from the options screen when the owner decided to pair
     * with Bobby, because vanilla's fog is fully opaque exactly where the
     * chunk grid ends. The wave-11 machinery is untouched, so every
     * assertion below is unchanged and still meaningful; it just cannot
     * lean on the default any more. This method therefore sets
     * {@code -Dmeshelium.retainTerrain=true} around the whole leg (the
     * property outranks the config field and is re-read per pump), the
     * step-5 toggle-off restores it to {@code true} instead of clearing
     * it, and the cleanup clears it so every later leg runs at the
     * shipped default.</p>
     *
     * <p>The standard suite's parity shots stay valid: retention is now
     * OFF for them by default AND invisible until something is RELEASED,
     * shots 40/41 and 60/61 are taken before this leg with a pinned
     * camera, and this leg restores a retention-free world before the
     * wave-10 leg runs.</p>
     */
    private static void assertRetainedHorizon(ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        int[] rdBefore = new int[1];
        context.runOnClient(client -> rdBefore[0] = client.options.renderDistance().get());
        final int lowCeiling = trackingViewChunkCount(RETAIN_RD_LOW);
        // (0) ARM the retired feature for this leg only (see the javadoc):
        // the shipped default is OFF since retention left the options
        // screen, so nothing below would retain anything without this.
        context.runOnClient(client -> System.setProperty("meshelium.retainTerrain", "true"));
        try {
            // (1) expand + broadcast + settle.
            setRenderDistanceLikeTheUi(context, RETAIN_RD_HIGH);
            waitForLoadedChunksAbove(context, lowCeiling);
            quiesce(context);

            long orphanedBefore = TerrainResidency.counters().orphanedSections();
            long supersededBefore = TerrainResidency.counters().retainedSuperseded();

            // (2) the controlled mass-release.
            setRenderDistanceLikeTheUi(context, RETAIN_RD_LOW);
            context.waitFor(client -> {
                TerrainResidency.Counters c = TerrainResidency.counters();
                return c.retainedSections() > 0 && c.orphanedSections() > orphanedBefore;
            }, DRAW_TIMEOUT_TICKS);
            context.waitFor(client ->
                    TerrainResidency.counters().retainedSuperseded() > supersededBefore,
                    DRAW_TIMEOUT_TICKS);
            quiesce(context);
            TerrainResidency.Counters afterDrop = TerrainResidency.counters();
            if (afterDrop.retainedSections() <= 0) {
                throw new AssertionError("ring between rd " + RETAIN_RD_LOW + " and "
                        + RETAIN_RD_HIGH + " was not retained after the drop: " + afterDrop);
            }
            if (TerrainResidency.dropsThisWorld() != 0 || TerrainDrawer.coveragePassive()) {
                throw new AssertionError("retention tripped the coverage guard - the wave's "
                        + "central safety rule is broken: " + TerrainResidency.counters());
            }

            // (3) beyond-live-residency proof on the attributable path.
            long bfsBefore = TerrainDrawer.bfsOnlyFrames();
            context.runOnClient(client ->
                    System.setProperty(TerrainDrawer.PROPERTY_BFS_ONLY, "true"));
            try {
                context.waitFor(client -> TerrainDrawer.bfsOnlyFrames() > bfsBefore
                        && TerrainDrawer.lastRetainedMaskSections() > 0, DRAW_TIMEOUT_TICKS);
            } catch (Throwable t) {
                throw new AssertionError("no retained sections entered the visibility masks "
                        + "under bfsOnly (retained=" + TerrainResidency.counters().retainedSections()
                        + ") - the drawn set does not exceed live rd-" + RETAIN_RD_LOW
                        + " residency, retention is not rendering", t);
            } finally {
                context.runOnClient(client ->
                        System.setProperty(TerrainDrawer.PROPERTY_BFS_ONLY, "false"));
            }
            long occResume = TerrainDrawer.occlusionFrames();
            context.waitFor(client -> TerrainDrawer.occlusionFrames() > occResume,
                    DRAW_TIMEOUT_TICKS);

            // (4) the horizon shots, default (occlusion) path, fixed camera.
            singleplayer.getServer().runCommand(
                    "execute as @p at @s run tp @s ~ ~16 ~ -90 20");
            context.waitTicks(20);
            context.takeScreenshot(TestScreenshotOptions.of("A0_meshelium_retained_horizon"));
            assertNoErrors();

            // (5) toggle OFF at runtime -> evicted, horizon shrinks.
            long evictOffBefore = TerrainResidency.counters().evictedByDisable();
            context.runOnClient(client ->
                    System.setProperty("meshelium.retainTerrain", "false"));
            try {
                context.waitFor(client -> {
                    TerrainResidency.Counters c = TerrainResidency.counters();
                    return c.retainedSections() == 0 && c.evictedByDisable() > evictOffBefore;
                }, DRAW_TIMEOUT_TICKS);
                context.waitTicks(10); // a few frames of the shrunk set
                context.takeScreenshot(TestScreenshotOptions.of("A1_meshelium_retention_off"));
                long bfsBefore2 = TerrainDrawer.bfsOnlyFrames();
                context.runOnClient(client ->
                        System.setProperty(TerrainDrawer.PROPERTY_BFS_ONLY, "true"));
                try {
                    context.waitFor(client -> TerrainDrawer.bfsOnlyFrames() > bfsBefore2,
                            DRAW_TIMEOUT_TICKS);
                    if (TerrainDrawer.lastRetainedMaskSections() != 0) {
                        throw new AssertionError("retention off but retained mask bits still "
                                + "reach the drawn set");
                    }
                } finally {
                    context.runOnClient(client ->
                            System.setProperty(TerrainDrawer.PROPERTY_BFS_ONLY, "false"));
                }
            } finally {
                // Back to ARMED, not to the default: the default is OFF
                // now, and steps 5b to 7 need retention running.
                context.runOnClient(client ->
                        System.setProperty("meshelium.retainTerrain", "true"));
            }
            assertNoErrors();

            // (5b) toggle back ON + re-expand: the world rebuilds live.
            setRenderDistanceLikeTheUi(context, RETAIN_RD_HIGH);
            waitForLoadedChunksAbove(context, lowCeiling);
            quiesce(context);

            // (6) time-limit leg at test scale: 5 seconds.
            context.runOnClient(client -> System.setProperty("meshelium.retainSeconds", "5"));
            try {
                long ageBefore = TerrainResidency.counters().evictedByAge();
                setRenderDistanceLikeTheUi(context, RETAIN_RD_LOW);
                context.waitFor(client -> TerrainResidency.counters().retainedSections() > 0,
                        DRAW_TIMEOUT_TICKS);
                context.waitFor(client -> {
                    TerrainResidency.Counters c = TerrainResidency.counters();
                    return c.retainedSections() == 0 && c.evictedByAge() > ageBefore;
                }, DRAW_TIMEOUT_TICKS);
            } catch (Throwable t) {
                throw new AssertionError("retained terrain did not evict by age under "
                        + "meshelium.retainSeconds=5: " + TerrainResidency.counters(), t);
            } finally {
                context.runOnClient(client -> System.clearProperty("meshelium.retainSeconds"));
            }

            // (7) limit OFF (config default 0 = NO LIMIT): survives 3x the
            // limit that just evicted everything.
            setRenderDistanceLikeTheUi(context, RETAIN_RD_HIGH);
            waitForLoadedChunksAbove(context, lowCeiling);
            quiesce(context);
            long ageFrozen = TerrainResidency.counters().evictedByAge();
            setRenderDistanceLikeTheUi(context, RETAIN_RD_LOW);
            context.waitFor(client -> TerrainResidency.counters().retainedSections() > 0,
                    DRAW_TIMEOUT_TICKS);
            context.waitTicks(300); // 15 s >> the 5 s limit above
            TerrainResidency.Counters unlimited = TerrainResidency.counters();
            if (unlimited.retainedSections() <= 0 || unlimited.evictedByAge() != ageFrozen) {
                throw new AssertionError("limit 0 must mean NO age eviction (the owner's "
                        + "'turn that limit off') but retained=" + unlimited.retainedSections()
                        + " evictedByAge " + ageFrozen + " -> " + unlimited.evictedByAge());
            }
            assertNoErrors();
        } finally {
            // Cleanup: drain retention deterministically, restore the rd.
            // Deliberately non-throwing (a cleanup timeout must never mask
            // the leg's real failure): best-effort waits, then plain
            // set+save — the production broadcast path.
            context.runOnClient(client -> System.setProperty("meshelium.retainTerrain", "false"));
            try {
                context.waitFor(client ->
                        TerrainResidency.counters().retainedSections() == 0, DRAW_TIMEOUT_TICKS);
            } catch (Throwable ignored) {
                // reported by the primary assertion if it mattered
            } finally {
                // Clearing DISARMS: the config default is FALSE now, so
                // every later leg runs at the shipped setting.
                context.runOnClient(client -> System.clearProperty("meshelium.retainTerrain"));
            }
            context.runOnClient(client -> {
                client.options.renderDistance().set(rdBefore[0]);
                client.options.save();
            });
            context.waitTicks(20);
        }
    }

    /**
     * The production path for a render-distance change: set + save().
     * {@code save()}'s last action is {@code broadcastOptions()} — without
     * it the server keeps SENDING at the old radius forever (the wave-10
     * 157-chunk postmortem, docs/EXTENDED-RENDER-DISTANCE.md §2b). Then
     * wait for the SP follow chain ({@code getEffectiveRenderDistance} =
     * min(option, server radius)) so the ViewArea swap has happened before
     * the caller reads counters.
     */
    private static void setRenderDistanceLikeTheUi(ClientGameTestContext context, int rd) {
        context.runOnClient(client -> {
            client.options.renderDistance().set(rd);
            client.options.save();
        });
        context.waitFor(client -> client.options.getEffectiveRenderDistance() == rd,
                RD_TIMEOUT_TICKS);
    }

    /** Wait until the client holds more chunks than {@code bar} (sendable-count proof). */
    private static void waitForLoadedChunksAbove(ClientGameTestContext context, int bar) {
        context.waitFor(client -> client.level != null
                && client.level.getChunkSource().getLoadedChunksCount() > bar, RD_TIMEOUT_TICKS);
    }

    // ------------------------------------------------------------------
    // Wave-10 assertion
    // ------------------------------------------------------------------

    /**
     * The rd-48 leg (docs/EXTENDED-RENDER-DISTANCE.md carries the design):
     * <ol>
     *   <li>the vanilla option ACCEPTS the extended value under the gate
     *       (range widened; set sticks);</li>
     *   <li>the client BROADCASTS the new value the way the real UI does
     *       ({@code Options.save()} → {@code broadcastOptions()} →
     *       {@code ServerboundClientInformationPacket}) and the server
     *       registers it ({@code ServerPlayer.requestedViewDistance}).
     *       This step is load-bearing: {@code OptionInstance.set()} is
     *       client-local, and the server's per-player SENDING radius is
     *       {@code clamp(requestedViewDistance, 2, serverViewDistance)}
     *       ({@code ChunkMap.getPlayerViewDistance}, bytecode). The first
     *       rd-48 run skipped it and froze at EXACTLY 157 client chunks —
     *       the chunk count of a vd-5 {@code ChunkTrackingView} (the
     *       harness boots with rd 5) while everything else reported 48;</li>
     *   <li>the integrated server follows — {@code
     *       getEffectiveRenderDistance()} reaches the value, which on
     *       singleplayer requires the whole server chain (tickServer
     *       follow → PlayerList → widened ChunkMap clamp → radius packet
     *       back to the client);</li>
     *   <li>chunks actually cross the OLD horizon: the client's loaded
     *       chunk count exceeds the vd-32 sendable ceiling (3,725 — the
     *       exact {@code isWithinDistance} count at vd 32, computed by
     *       {@link #trackingViewChunkCount}), impossible without BOTH
     *       widened server clamps AND the broadcast; and live regions
     *       outgrow the pre-leg horizon (superflat calibration below), so
     *       Meshelium provably ingests the new terrain (needs the widened
     *       PlayerTicketTracker, or loading stalls at ~34 chunks);</li>
     *   <li>the drawer stays live with no drops and no error latches — or
     *       the coverage guard trips HONESTLY, in which case the wave-10
     *       clamp-back must have restored the option to 32 (the invariant:
     *       vanilla never renders above 32);</li>
     *   <li>screenshot {@code 95_meshelium_rd48} for the coordinator.</li>
     * </ol>
     * Worldgen at rd 48 is the long pole (~9.4k chunks if it ran to
     * completion) — the leg's bars arrive ring-by-ring long before full
     * generation, but it still gets its own generous timeout.
     */
    private static void assertExtendedRenderDistance(ClientGameTestContext context,
            TestSingleplayerContext singleplayer, int rd) {
        long rejoinHintsBefore = com.deds.meshelium.MesheliumExtendedRd.rejoinHints();
        // Captured BEFORE the raise in step (1): the per-tick monitor can
        // request the live grow on the first tick after set(), and the
        // grow lands while steps (2)/(3) sit in their server round-trip
        // waits (coordinator run 2026-08-10: a capture placed after step
        // (3) read the post-grow count and asserted "flat" against it).
        long recordGrowthsBefore = com.deds.meshelium.vk.MesheliumTerrainGpu.recordGrowths();
        // (1) the option accepts the extended value under the gate.
        context.runOnClient(client -> {
            if (!com.deds.meshelium.MesheliumExtendedRd.rangeWidened()) {
                throw new AssertionError("extended render distance armed (meshelium.test.rd="
                        + rd + ") but the option range is not widened under "
                        + "VULKAN_MESH_SHADERS + terrain enabled");
            }
            client.options.renderDistance().set(rd);
            int got = client.options.renderDistance().get();
            if (got != rd) {
                throw new AssertionError("renderDistance.set(" + rd + ") did not stick under "
                        + "the gate (got " + got + ")");
            }
        });

        // (2) broadcast the new REQUESTED view distance to the integrated
        // server the way the real UI does. OptionInstance.set() only
        // stores the value client-side; the server's per-player sending
        // radius reads ServerPlayer.requestedViewDistance, which updates
        // exclusively via ServerboundClientInformationPacket — emitted by
        // Options.save() -> broadcastOptions() (OptionsSubScreen.removed()
        // calls save(), so closing the video-settings screen is the
        // production path; all bytecode-cited in
        // docs/EXTENDED-RENDER-DISTANCE.md §2b).
        context.runOnClient(client -> client.options.save());
        try {
            context.waitFor(client -> {
                var server = client.getSingleplayerServer();
                if (server == null) {
                    return false;
                }
                var players = server.getPlayerList().getPlayers();
                return !players.isEmpty() && players.get(0).requestedViewDistance() >= rd;
            }, RD_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("ServerPlayer.requestedViewDistance never reached " + rd
                    + " after Options.save() - the ClientInformation round trip "
                    + "(broadcastOptions -> ServerboundClientInformationPacket -> "
                    + "handleClientInformation -> updateOptions) is broken; the server "
                    + "would keep SENDING chunks at the old radius no matter how far "
                    + "it loads them", t);
        }

        // (3) the server half follows: effective RD = min(option, server
        // radius) on singleplayer, so reaching rd proves the packet chain.
        try {
            context.waitFor(client ->
                    client.options.getEffectiveRenderDistance() >= rd, RD_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("getEffectiveRenderDistance never reached " + rd
                    + " - the integrated server did not follow the extended option "
                    + "(ChunkMap clamp or the tickServer follow chain)", t);
        }

        // (3b) wave-15: this world pinned STANDARD buffers (option was
        // <= 32 at standup), so raising to rd mid-world must GROW the
        // pinned budget live — records grow-and-copy, snapshot swaps,
        // residency expands in place — with NO rejoin hint (the hint is
        // now strictly the failed-grow fallback). recordGrowthsBefore is
        // captured at the top of this method, before the raise.
        try {
            context.waitFor(client -> {
                com.deds.meshelium.MesheliumScaling.Snapshot pinned =
                        com.deds.meshelium.MesheliumScaling.pinned();
                return pinned != null && pinned.maxRd() >= Math.min(
                        com.deds.meshelium.MesheliumConfig.maxRenderDistanceConfigured(),
                        (rd / 8 + 1) * 8);
            }, DRAW_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("the mid-world raise to " + rd + " never grew the "
                    + "pinned budget (wave-15 live grow: monitor requests, pump grows, "
                    + "snapshot swaps) - pinned is still "
                    + com.deds.meshelium.MesheliumScaling.pinned() + ", pinnedGrows="
                    + TerrainResidency.pinnedGrows() + ", growFailed="
                    + TerrainResidency.pinnedGrowFailedThisWorld(), t);
        }
        context.runOnClient(client -> {
            com.deds.meshelium.MesheliumScaling.Snapshot pinned =
                    com.deds.meshelium.MesheliumScaling.pinned();
            if (pinned == null || !pinned.extended()) {
                throw new AssertionError("grown snapshot must be extended, got " + pinned);
            }
            if (TerrainResidency.pinnedGrows() == 0) {
                throw new AssertionError("snapshot grew but pinnedGrows is 0 - the grow "
                        + "did not go through the pump's consume path");
            }
            if (pinned.maxRegions() > com.deds.meshelium.MesheliumScaling.STANDARD_MAX_REGIONS
                    && com.deds.meshelium.vk.MesheliumTerrainGpu.recordGrowths()
                            <= recordGrowthsBefore) {
                throw new AssertionError("maxRegions grew past the standard budget but the "
                        + "GPU record buffers never grow-and-copied (recordGrowths flat)");
            }
            if (com.deds.meshelium.MesheliumExtendedRd.rejoinHints() != rejoinHintsBefore) {
                throw new AssertionError("the rejoin hint fired although the live grow "
                        + "succeeded - the hint must be the failed-grow fallback only");
            }
        });

        // (4) growth beyond the OLD horizon while the drawer stays live -
        // or an honest guard trip with clamp-back. Two bars, both required:
        //   (a) clientLoadedChunks > the vd-32 sendable ceiling (3,725) -
        //       vanilla sends a chunk iff max(0,|dx|-2)^2 + max(0,|dz|-2)^2
        //       < vd^2 (ChunkTrackingView.isWithinDistance, includeEdge
        //       slack 2, strict <), so crossing the vd-32 count is
        //       impossible without the widened clamps AND the broadcast.
        //       (The first run froze at 157 = the vd-5 count.)
        //   (b) live regions outgrow the pre-leg horizon. CALIBRATION
        //       (first rd-48 run): the original threshold was the
        //       noise-world rd-32 ceiling (700 regions), but THIS leg runs
        //       in the draw test's SUPERFLAT world, whose single populated
        //       y-layer caps around ~170 regions at rd 48 - unreachable by
        //       construction. The honest superflat criterion: clearly
        //       outgrow the pre-leg horizon (3x baseline, floor 40) -
        //       still impossible unless chunks beyond the old horizon
        //       load, upload and become regions.
        long clampsBefore = com.deds.meshelium.MesheliumExtendedRd.sessionClamps();
        int regionsBefore = TerrainResidency.counters().regionsLive();
        long sectionsBefore = TerrainResidency.counters().sectionsResident();
        int growthTarget = Math.max(40, regionsBefore * 3);
        final int vd32SendCeiling = trackingViewChunkCount(32);
        boolean grewPastCeiling = false;
        boolean chunksPastVd32 = false;
        long deadline = System.nanoTime() + RD_GROWTH_BUDGET_NANOS;
        while (System.nanoTime() < deadline) {
            context.waitTicks(40);
            if (TerrainDrawer.coveragePassive()) {
                break; // honest guard trip path, asserted below
            }
            if (!chunksPastVd32) {
                int[] loaded = new int[1];
                context.runOnClient(client -> loaded[0] = client.level != null
                        ? client.level.getChunkSource().getLoadedChunksCount() : 0);
                chunksPastVd32 = loaded[0] > vd32SendCeiling;
            }
            if (!grewPastCeiling) {
                grewPastCeiling = TerrainResidency.counters().regionsLive() > growthTarget;
            }
            if (grewPastCeiling && chunksPastVd32) {
                break;
            }
        }

        if (TerrainDrawer.coveragePassive()) {
            // The guard tripped (arena/budget) - allowed, but the wave-10
            // invariant must have fired: option restored, clamp counted.
            context.waitFor(client -> com.deds.meshelium.MesheliumExtendedRd.sessionClamps()
                    > clampsBefore, DRAW_TIMEOUT_TICKS);
            context.runOnClient(client -> {
                int got = client.options.renderDistance().get();
                if (got > 32) {
                    throw new AssertionError("coverage guard went passive at rd " + rd
                            + " but the option is still " + got
                            + " - the clamp-back invariant did not fire");
                }
            });
            logHonestGuardTrip(rd);
            return;
        }
        if (!grewPastCeiling || !chunksPastVd32) {
            TerrainResidency.Counters c = TerrainResidency.counters();
            int[] probe = new int[4];
            context.runOnClient(client -> {
                probe[0] = client.options.renderDistance().get();
                probe[1] = client.options.getEffectiveRenderDistance();
                probe[2] = client.level != null
                        ? client.level.getChunkSource().getLoadedChunksCount() : -1;
                var server = client.getSingleplayerServer();
                probe[3] = server != null && !server.getPlayerList().getPlayers().isEmpty()
                        ? server.getPlayerList().getPlayers().get(0).requestedViewDistance()
                        : -1;
            });
            throw new AssertionError("rd " + rd + " leg never crossed its growth bars - "
                    + "clientLoadedChunks=" + probe[2] + " vs vd-32 sendable ceiling "
                    + vd32SendCeiling + " (crossed=" + chunksPastVd32
                    + "; 157 here = the requested-view-distance broadcast regressed), "
                    + "regionsLive=" + c.regionsLive() + " vs target " + growthTarget
                    + " (baseline " + regionsBefore + ", crossed=" + grewPastCeiling + ") - "
                    + "sectionsResident=" + c.sectionsResident() + " (was " + sectionsBefore
                    + ") encoded=" + c.encodedSections()
                    + " | option=" + probe[0] + " effective=" + probe[1]
                    + " serverRequestedViewDistance=" + probe[3]);
        }

        // Let the chunk stream PLATEAU before demanding a quiet pipeline:
        // the bars cross around the halfway ring (~4k of ~7.9k sendable
        // chunks at vd 48 still in flight), and quiesce()'s 30 windows
        // cannot outwait an active stream. Reuses the leg's remaining
        // growth budget; if the deadline lands first, fall through - the
        // quiesce below still fails honestly if the pipeline never quiets.
        int lastLoaded = -1;
        while (System.nanoTime() < deadline) {
            int[] loaded = new int[1];
            context.runOnClient(client -> loaded[0] = client.level != null
                    ? client.level.getChunkSource().getLoadedChunksCount() : 0);
            if (loaded[0] == lastLoaded) {
                break; // no new chunks across a 2 s window - stream settled
            }
            lastLoaded = loaded[0];
            context.waitTicks(40);
        }

        // Drawer live at the extended distance, zero drops, no latches.
        int framesBefore = TerrainDrawer.framesDrawn();
        context.waitFor(client -> TerrainDrawer.framesDrawn() > framesBefore
                && TerrainDrawer.lastDrawnSections() > 0, DRAW_TIMEOUT_TICKS);
        if (TerrainResidency.dropsThisWorld() != 0) {
            throw new AssertionError("sections were dropped at rd " + rd + " without the "
                    + "coverage guard going passive - the guard/drop accounting is broken");
        }
        assertNoErrors();

        // Wave-15: after minutes of above-old-budget play the hint count
        // must STILL be untouched (the grow succeeded; a hint here would
        // mean the fallback fired alongside the fix) and the grow state
        // must be clean.
        long hintsNow = com.deds.meshelium.MesheliumExtendedRd.rejoinHints();
        if (hintsNow != rejoinHintsBefore) {
            throw new AssertionError("rejoin hint count " + rejoinHintsBefore + " -> "
                    + hintsNow + " - the hint must stay silent when the live grow succeeded");
        }
        if (TerrainResidency.pinnedGrowFailedThisWorld()) {
            throw new AssertionError("pinnedGrowFailed latched although the leg drew clean "
                    + "at the grown budget");
        }

        quiesce(context);
        context.takeScreenshot(TestScreenshotOptions.of("95_meshelium_rd48"));
        assertNoErrors();
    }

    /**
     * The exact chunk count of a vanilla {@code ChunkTrackingView.Positioned}
     * at the given view distance — what the server can ever SEND a player
     * requesting that distance. Bytecode ({@code ChunkTrackingView
     * .isWithinDistance}, reached from {@code Positioned.contains(x,z)}
     * with {@code includeEdge=true}): a chunk is in view iff
     * {@code max(0,|dx|-2)² + max(0,|dz|-2)² < vd²} (slack 2, strict).
     * vd 5 → 157 (the first rd-48 run's frozen client chunk count — the
     * fingerprint that found the missing broadcast), vd 32 → 3,725.
     */
    private static int trackingViewChunkCount(int viewDistance) {
        int count = 0;
        for (int dx = -viewDistance - 2; dx <= viewDistance + 2; dx++) {
            for (int dz = -viewDistance - 2; dz <= viewDistance + 2; dz++) {
                long ex = Math.max(0, Math.abs(dx) - 2);
                long ez = Math.max(0, Math.abs(dz) - 2);
                if (ex * ex + ez * ez < (long) viewDistance * viewDistance) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Once-only INFO so a guard-trip pass is visible in the run log. */
    private static void logHonestGuardTrip(int rd) {
        System.out.println("[meshelium harness] rd" + rd + " leg ended in an HONEST coverage-guard "
                + "trip: Meshelium passive, render distance clamped back to 32 (allowed outcome; "
                + "no screenshot 95 this run)");
    }

    // ------------------------------------------------------------------
    // Wave-9 assertion
    // ------------------------------------------------------------------

    /**
     * The wave-9 timestamp path must WORK on the real GPU in the standard
     * run, not only inside benches: at least one lagged readback with
     * phase A present, no timer failure latch, and a sane timestampPeriod.
     * (Timers are pixel-neutral and default-ON; a latched failure here
     * would silently strip the bench of its GPU columns, so the standard
     * run fails loudly instead.)
     */
    private static void assertGpuTimersLive(ClientGameTestContext context) {
        context.waitFor(client -> MesheliumGpuTimers.framesRead() > 0, DRAW_TIMEOUT_TICKS);
        if (MesheliumGpuTimers.failure() != null) {
            throw new AssertionError("GPU timers latched off: " + MesheliumGpuTimers.failure());
        }
        long[] passes = MesheliumGpuTimers.lastPassNanosSnapshot();
        if (passes[MesheliumGpuTimers.PASS_OPAQUE_A] < 0) {
            throw new AssertionError("GPU timers read frames but phase A is absent - "
                    + "the point mask/readback pairing is broken");
        }
        assertNoErrors();
    }

    // ------------------------------------------------------------------
    // Wave-5 assertions
    // ------------------------------------------------------------------

    /**
     * regionsDispatched > 0 and never exceeds the live region count. Since
     * wave 6 the default task path is occlusion, whose sectionsVisibleIn
     * comes from the lagged GPU readback — it joins the waitFor rather
     * than being asserted instantly, and the occlusion frame counter must
     * be moving (occlusion really is the live path).
     */
    private static void assertTaskCullingLive(ClientGameTestContext context) {
        context.waitFor(client -> TerrainDrawer.taskCullFrames() > 0
                && TerrainDrawer.regionsDispatched() > 0
                && TerrainDrawer.sectionsVisibleIn() > 0, DRAW_TIMEOUT_TICKS);
        int dispatched = TerrainDrawer.regionsDispatched();
        int live = TerrainResidency.counters().regionsLive();
        if (dispatched <= 0 || dispatched > live) {
            throw new AssertionError("regionsDispatched=" + dispatched
                    + " out of range (0, regionsLive=" + live + "]");
        }
        if (TerrainDrawer.occlusionFrames() <= 0) {
            throw new AssertionError("task culling live but occlusionFrames=0 - "
                    + "the wave-6 occlusion path is not the one drawing"
                    + (TerrainDrawer.occlusionError() != null
                            ? " (occlusion error: " + TerrainDrawer.occlusionError() + ")" : ""));
        }
        assertNoErrors();
    }

    /**
     * Turn the camera 180° (the residency test's tp-command walk pattern,
     * as a relative-yaw rotation) and require the dispatched-region SET to
     * change — GPU culling must respond to the camera, not just exist.
     */
    private static void assertCullingRespondsToCamera(ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        long sigBefore = TerrainDrawer.dispatchSignature();
        int countBefore = TerrainDrawer.regionsDispatched();
        singleplayer.getServer().runCommand("execute as @p at @s run tp @s ~ ~ ~ ~180 ~");
        try {
            context.waitFor(client ->
                    TerrainDrawer.dispatchSignature() != sigBefore
                            || TerrainDrawer.regionsDispatched() != countBefore,
                    DRAW_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("dispatched regions did not change after a 180-degree "
                    + "camera turn (signature " + sigBefore + ", count " + countBefore
                    + " both static) - culling is not responding to the camera", t);
        }
        if (TerrainDrawer.regionsDispatched() <= 0) {
            throw new AssertionError("no regions dispatched after the camera turn");
        }
        assertNoErrors();
    }

    /**
     * The wave-4 CPU-culled path must still render behind
     * {@code meshelium.terrainDraw.cpuCull} — counters move, no errors — so
     * the escape hatch stays an honest A/B fallback.
     */
    private static void assertCpuCullHatchRenders(ClientGameTestContext context) {
        long cpuFramesBefore = TerrainDrawer.cpuCullFrames();
        int framesBefore = TerrainDrawer.framesDrawn();
        context.runOnClient(client -> System.setProperty(TerrainDrawer.PROPERTY_CPU_CULL, "true"));
        try {
            context.waitFor(client -> TerrainDrawer.cpuCullFrames() > cpuFramesBefore
                    && TerrainDrawer.framesDrawn() > framesBefore
                    && TerrainDrawer.lastDrawnSections() > 0, DRAW_TIMEOUT_TICKS);
            assertNoErrors();
        } finally {
            context.runOnClient(client -> System.clearProperty(TerrainDrawer.PROPERTY_CPU_CULL));
        }
        // And the task path resumes once the hatch closes.
        long taskFramesAfter = TerrainDrawer.taskCullFrames();
        context.waitFor(client -> TerrainDrawer.taskCullFrames() > taskFramesAfter,
                DRAW_TIMEOUT_TICKS);
        assertNoErrors();
    }

    // ------------------------------------------------------------------
    // Wave-6 assertions
    // ------------------------------------------------------------------

    /**
     * The hidden-occluder scene (wave-6 deliverable 4b). A large flat
     * stone wall (65×41×3 blocks — under the 32768-block fill limit) goes
     * up 8 blocks in front of a camera pinned facing south (yaw 0). The
     * BFS flood still reaches the terrain BEHIND the wall around its
     * edges, so bfsOnly keeps drawing it; the box raster depth-fails it.
     * Metric: {@link TerrainDrawer#gpuSectionsDrawn()} — the GPU-counted
     * post-all-gates survivors, the SAME counter in both modes (the task
     * shader increments it under every VisMode), so the comparison is
     * apples-to-apples. Also proves the bfsOnly revert is total (the
     * occlusion frame counter freezes) and reversible (it resumes).
     */
    private static void assertHiddenWallOcclusion(ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        var server = singleplayer.getServer();
        // Pin the view, then raise the wall dead ahead (south, +Z).
        server.runCommand("execute as @p at @s run tp @s ~ ~ ~ 0 0");
        server.runCommand("execute as @p at @s run fill ~-32 ~-8 ~8 ~32 ~32 ~10 minecraft:stone");
        quiesce(context); // wall rebuilds uploaded, encode counter flat

        // Let stamps converge and the lagged readback land, then sample.
        long settled = TerrainDrawer.statsFrames();
        context.waitFor(client -> TerrainDrawer.lastReadStatsFrame() >= settled
                && TerrainDrawer.gpuSectionsDrawn() > 0, DRAW_TIMEOUT_TICKS);
        int occDrawn = TerrainDrawer.gpuSectionsDrawn();
        context.takeScreenshot(TestScreenshotOptions.of("50_meshelium_occlusion_on"));
        assertNoErrors();

        // Flip to the wave-5 BFS feed; prove the revert is total.
        long bfsFramesBefore = TerrainDrawer.bfsOnlyFrames();
        context.runOnClient(client ->
                System.setProperty(TerrainDrawer.PROPERTY_BFS_ONLY, "true"));
        long occFramesAtFlip;
        try {
            // Only snapshot the occlusion counter AFTER the flip provably
            // took effect (frames recorded between set and take-effect
            // must not read as "occlusion ran under bfsOnly").
            context.waitFor(client -> TerrainDrawer.bfsOnlyFrames() > bfsFramesBefore,
                    DRAW_TIMEOUT_TICKS);
            occFramesAtFlip = TerrainDrawer.occlusionFrames();
            long bfsSettled = TerrainDrawer.statsFrames();
            context.waitFor(client -> TerrainDrawer.lastReadStatsFrame() >= bfsSettled
                    && TerrainDrawer.gpuSectionsDrawn() > 0, DRAW_TIMEOUT_TICKS);
            if (TerrainDrawer.occlusionFrames() != occFramesAtFlip) {
                throw new AssertionError("occlusion frames advanced under bfsOnly - the "
                        + "fallback is not a clean wave-5 revert");
            }
            int bfsDrawn = TerrainDrawer.gpuSectionsDrawn();
            context.takeScreenshot(TestScreenshotOptions.of("51_meshelium_occlusion_off_bfs"));
            assertNoErrors();
            if (occDrawn >= bfsDrawn) {
                throw new AssertionError("occlusion did not cull behind the wall: sections drawn "
                        + "occlusion=" + occDrawn + " vs bfsOnly=" + bfsDrawn
                        + " (expected strictly fewer)");
            }
        } finally {
            context.runOnClient(client ->
                    System.setProperty(TerrainDrawer.PROPERTY_BFS_ONLY, "false"));
        }
        // And the occlusion path resumes once the property clears.
        context.waitFor(client -> TerrainDrawer.occlusionFrames() > occFramesAtFlip,
                DRAW_TIMEOUT_TICKS);
        assertNoErrors();
    }

    /**
     * The camera-cut latency assertion (wave-6 deliverable 4c): after a
     * 180° teleport, phase B must draw within 2 stats frames of the
     * dispatch-set change — the temporal two-phase design repaints a cut
     * in the SAME frame (phase A primes with the stale set, the raster
     * marks the new view, phase B draws it), so the window exists only to
     * absorb tick/frame skew. Uses the drawer's per-frame history rings
     * (readback is 3 frames stale, but frame INDICES are exact).
     */
    private static void assertCameraCutPhaseB(ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        long f0 = TerrainDrawer.statsFrames();
        singleplayer.getServer().runCommand("execute as @p at @s run tp @s ~ ~ ~ ~180 ~");
        context.waitFor(client -> TerrainDrawer.firstDispatchChangeAtOrAfter(f0) >= 0,
                DRAW_TIMEOUT_TICKS);
        long turn = TerrainDrawer.firstDispatchChangeAtOrAfter(f0);
        // Wait until the readbacks covering [turn, turn+2] have landed.
        context.waitFor(client -> TerrainDrawer.lastReadStatsFrame() >= turn + 2,
                DRAW_TIMEOUT_TICKS);
        boolean phaseBHit = false;
        for (long f = turn; f <= turn + 2; f++) {
            if (TerrainDrawer.gpuPhaseBAt(f) > 0) {
                phaseBHit = true;
                break;
            }
        }
        if (!phaseBHit) {
            throw new AssertionError("no phase-B draws within 2 frames of the camera cut "
                    + "(dispatch change at stats frame " + turn + ", phaseB counts: "
                    + TerrainDrawer.gpuPhaseBAt(turn) + "/" + TerrainDrawer.gpuPhaseBAt(turn + 1)
                    + "/" + TerrainDrawer.gpuPhaseBAt(turn + 2)
                    + ") - the latency hider is not working");
        }
        assertNoErrors();
    }

    // ------------------------------------------------------------------
    // Wave-7 assertions
    // ------------------------------------------------------------------

    /**
     * The translucency scene + parity pair (wave-7 deliverable 4). Camera
     * pinned facing north (the wave-6 wall sits south), then three
     * translucent families raised in view: a SEALED glass tank full of
     * water (sources boxed on all six faces — fluids never flow, so the
     * build pipeline settles and the quiesce is exact), a stained-glass
     * stack, and a framed nether portal. Shots 60/61 use the same
     * live-property-flip protocol as 40/41; the coordinator pixel-compares
     * (blending makes ORDER errors visible as color shifts, so this pair
     * holds translucency to the same threshold as opaque — only the
     * established dither/animated-sprite floor is acceptable).
     */
    private static void assertTranslucentParity(ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        var server = singleplayer.getServer();
        // Pin the view north (-Z), pitch slightly down at the scene.
        server.runCommand("execute as @p at @s run tp @s ~ ~ ~ 180 20");
        // Sealed water tank: a plain-glass shell (CUTOUT layer — drawn by
        // the opaque pass, so the pair also checks translucent-through-
        // cutout compositing) whose interior is filled wall-to-wall with
        // water sources — nothing can flow, the scene settles exactly.
        server.runCommand("execute as @p at @s run fill ~-8 ~-1 ~-26 ~8 ~3 ~-10 minecraft:glass hollow");
        server.runCommand("execute as @p at @s run fill ~-7 ~ ~-25 ~7 ~2 ~-11 minecraft:water");
        // Stained glass (TRANSLUCENT layer) stack, left of center.
        server.runCommand("execute as @p at @s run fill ~-4 ~4 ~-18 ~-1 ~9 ~-16 minecraft:magenta_stained_glass");
        // Framed, lit nether portal (TRANSLUCENT layer), right of center:
        // solid obsidian slab, interior carved into portal blocks — the
        // surrounding ring keeps the frame valid under neighbor updates.
        server.runCommand("execute as @p at @s run fill ~2 ~4 ~-17 ~5 ~9 ~-17 minecraft:obsidian");
        server.runCommand("execute as @p at @s run fill ~3 ~5 ~-17 ~4 ~8 ~-17 minecraft:nether_portal[axis=x]");
        quiesce(context);

        // The translucent kill switch must be live: frames owned, sections
        // recorded, the TRANSLUCENT renderGroup cancelled.
        context.waitFor(client -> TerrainDrawer.translucentFrames() > 0
                && TerrainDrawer.lastTranslucentSections() > 0
                && TerrainDrawer.lastTranslucentDraws() > 0
                && TerrainDrawer.cancelledTranslucentGroups() > 0, DRAW_TIMEOUT_TICKS);
        assertNoErrors();
        // Wave-9 ledger-17 experiment run: when the coordinator passes
        // -Pmeshelium.translucentMultiWG=true, shot 60 must provably be a
        // multi-WG frame — otherwise the 60/61 verdict would grade the
        // wrong code path.
        if (Boolean.getBoolean(TerrainDrawer.PROPERTY_TRANSLUCENT_MULTI_WG)) {
            context.waitFor(client -> TerrainDrawer.translucentMultiWGFrames() > 0,
                    DRAW_TIMEOUT_TICKS);
        }
        context.takeScreenshot(TestScreenshotOptions.of("60_meshelium_translucent"));

        // The vanilla twin — same flip protocol as shots 40/41.
        context.runOnClient(client -> System.setProperty(TerrainDrawer.PROPERTY, "false"));
        context.waitTicks(3);
        long transFramesAtFlip = TerrainDrawer.translucentFrames();
        long transCancelsAtFlip = TerrainDrawer.cancelledTranslucentGroups();
        context.takeScreenshot(TestScreenshotOptions.of("61_vanilla_translucent_reference"));
        context.waitTicks(3);
        if (TerrainDrawer.translucentFrames() != transFramesAtFlip
                || TerrainDrawer.cancelledTranslucentGroups() != transCancelsAtFlip) {
            throw new AssertionError("translucent drawer kept running after the property flip - "
                    + "shot 61 is not a clean vanilla reference");
        }
        assertNoErrors();
        context.runOnClient(client -> System.setProperty(TerrainDrawer.PROPERTY, "true"));
        context.waitFor(client -> TerrainDrawer.translucentFrames() > transFramesAtFlip,
                DRAW_TIMEOUT_TICKS);
        assertNoErrors();
    }

    /**
     * Wave-7 deliverable 3's harness half: resorts APPLY (counter moves)
     * and NEVER re-encode (encodedSections stays flat across a window in
     * which resortsApplied advanced).
     *
     * <p>The trigger, from bytecode (docs/VANILLA-FRAME-PATH.md wave-7
     * notes): {@code scheduleTranslucentSectionResort} forces a resort of
     * every {@code nearbyVisibleSections} member (sections within 32
     * blocks — the Octree visitor's isClose radius) whenever the camera
     * BLOCK POSITION changes at all, and {@code ResortTransparencyTask}
     * then really rebuilds the index when the camera's per-axis
     * section-relative sign ({@code TranslucencyPointOfView} =
     * {@code clamp(camSection − section, −1, 1)} per axis) differs OR is
     * axis-aligned (any 0 component). So the small-move loop below (±1
     * block, alternating) fires real resorts of the tank in front of the
     * camera; the 17-block strafe afterwards crosses the SECTION-GRID
     * threshold — the POV change — which is the second trigger family.</p>
     */
    private static void assertResortsApplyWithoutReencode(ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        var server = singleplayer.getServer();
        quiesce(context);

        // Small-move loop: find ONE window where resortsApplied advanced
        // while encodedSections did not — the no-re-encode proof. (A lone
        // 1-block move could land near a chunk border and stream a new
        // column in, moving encodedSections for reasons unrelated to
        // resorts; alternating ±1 keeps the camera put while transient
        // builds settle, so a clean window must appear.)
        boolean cleanWindow = false;
        long resortsAtStart = TerrainResidency.counters().resortsApplied();
        for (int i = 0; i < 20 && !cleanWindow; i++) {
            long encodedBefore = TerrainResidency.counters().encodedSections();
            long resortsBefore = TerrainResidency.counters().resortsApplied();
            server.runCommand("execute as @p at @s run tp @s ~" + (i % 2 == 0 ? 1 : -1) + " ~ ~");
            context.waitTicks(10);
            long encodedAfter = TerrainResidency.counters().encodedSections();
            long resortsAfter = TerrainResidency.counters().resortsApplied();
            cleanWindow = resortsAfter > resortsBefore && encodedAfter == encodedBefore;
        }
        if (!cleanWindow) {
            throw new AssertionError("no window with resortsApplied advancing while "
                    + "encodedSections stayed flat (applied "
                    + resortsAtStart + " -> " + TerrainResidency.counters().resortsApplied()
                    + ") - either resorts never reach the tap or they re-encode");
        }
        // The permuted prefixes must actually restage bytes to the GPU.
        context.waitFor(client -> TerrainResidency.counters().resortBytes() > 0,
                DRAW_TIMEOUT_TICKS);
        assertNoErrors();

        // The section-grid threshold: strafe a full section width; POV
        // flips for the scene sections and resorts fire again (chunk
        // streaming is allowed to move encodedSections here).
        long resortsBeforeStrafe = TerrainResidency.counters().resortsApplied();
        server.runCommand("execute as @p at @s run tp @s ~17 ~ ~");
        context.waitFor(client ->
                TerrainResidency.counters().resortsApplied() > resortsBeforeStrafe,
                DRAW_TIMEOUT_TICKS);
        assertNoErrors();
    }

    /** Deterministic scene: fixed light, no weather, no wandering mobs. */
    private static void freezeWorld(TestSingleplayerContext singleplayer) {
        var server = singleplayer.getServer();
        server.runCommand("time set noon");
        server.runCommand("gamerule doDaylightCycle false");
        server.runCommand("weather clear");
        server.runCommand("gamerule doWeatherCycle false");
        server.runCommand("gamerule doMobSpawning false");
        server.runCommand("gamerule randomTickSpeed 0");
        server.runCommand("kill @e[type=!minecraft:player]");
    }

    /**
     * Same quiesce as the residency test: the encode counter must go flat
     * and the staging backlog empty, so both screenshots see the identical,
     * fully-uploaded resident set (and every section is past its fade-in —
     * the drawer's one documented visual deviation).
     */
    private static void quiesce(ClientGameTestContext context) {
        for (int i = 0; i < 30; i++) {
            long before = TerrainResidency.counters().encodedSections();
            context.waitTicks(20);
            long after = TerrainResidency.counters().encodedSections();
            if (before == after && TerrainResidency.counters().stagingBacklogEntries() == 0) {
                return;
            }
        }
        throw new AssertionError("build pipeline never went quiet before the parity shots");
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
        // Wave-6: occlusion failures fall back to bfs (terrain keeps
        // drawing) but the harness must still fail loudly on real hardware.
        String occError = TerrainDrawer.occlusionError();
        if (occError != null) {
            throw new AssertionError("occlusion culling reported an error (drawing fell back "
                    + "to the BFS feed): " + occError);
        }
    }
}
