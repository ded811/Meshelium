/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Wave 3b acceptance: the vanilla section tap + GPU residency, proven on
 * a real client. Vulkan run: sections/quads/arena counters go live after
 * chunks render, the staging backlog drains, the camera walk grows the
 * cumulative counters, and the world-close teardown shows every vanilla
 * mesh free reached Meshelium (the dispose-time snapshot's resident count
 * is the leak test that matters). GL run: every counter stays zero — the
 * dormancy proof for mixins that fire on both backends.
 */
package com.deds.meshelium.gametest.client;

import com.deds.meshelium.terrain.host.TerrainResidency;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

/**
 * Runs on both harness paths, after the boot smoke + data-layer tests
 * (same {@code meshelium.test.expectBackend} pinning — the test never lets
 * the gate grade its own homework).
 */
public final class MesheliumTerrainResidencyTest implements FabricClientGameTest {

    /** Ticks to wait for first residency (covers slow first chunk builds). */
    private static final int RESIDENCY_TIMEOUT_TICKS = 1200;
    /** Deliverable: the staging backlog must drain to 0 within N ticks. */
    private static final int BACKLOG_DRAIN_TICKS = 200;

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean vulkanRun = "vulkan".equalsIgnoreCase(
                System.getProperty("meshelium.test.expectBackend", "opengl"));

        // The boot-smoke test already opened and closed a world on this
        // client, so a dispose snapshot may exist BEFORE our world closes —
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            if (vulkanRun) {
                assertResidencyLive(context);
                assertBacklogDrains(context);
                long uploadedBefore = counters(context).uploadedSections();
                walkCamera(singleplayer);
                assertGrowth(context, uploadedBefore);
                quiesce(context);
                context.takeScreenshot(TestScreenshotOptions.of("30_meshelium_residency_world"));
            } else {
                assertGlDormant(context, "after world render");
            }
        }

        if (vulkanRun) {
            // Vanilla's REAL teardown model (bytecode-established after two
            // wrong guesses, section-build doc note 11): at world close,
            // NOTHING frees — LevelRenderer has no setLevel teardown;
            // releaseAllBuffers' only callers are invalidateCompiledGeometry
            // (F3+A/options) and resetLevelRenderData (renderer close()
            // only). The dispatcher and every compiled mesh SURVIVE at the
            // title screen, and free lazily as the next world's sections
            // reposition into the same slots. Meshelium keys on mesh identity,
            // so it inherits this retention policy by construction — the
            // leak test must therefore assert BOTH halves of it:
            assertRetentionAtTitle(context);
            assertFreesFlowInSecondWorld(context);
        } else {
            assertGlDormant(context, "after world close");
        }
    }

    // ------------------------------------------------------------------
    // Vulkan path
    // ------------------------------------------------------------------

    private static void assertResidencyLive(ClientGameTestContext context) {
        context.waitFor(client -> {
            TerrainResidency.Counters c = TerrainResidency.counters();
            return c.sectionsResident() > 0 && c.quadsResident() > 0 && c.arenaUsedBytes() > 0;
        }, RESIDENCY_TIMEOUT_TICKS);
        TerrainResidency.Counters c = counters(context);
        assertNoError();
        if (c.regionsLive() <= 0) {
            throw new AssertionError("sections resident but no live regions: " + c);
        }
        // Wave-14: the snapshot's arenaBytes is the INITIAL size and the
        // arena is elastic — capacity equals it until the first growth,
        // never falls below it.
        //
        // Multi-buffer: block 0 is capped at the block size, so the initial
        // capacity is min(initial, blockBytes). On real hardware the block
        // is 2 GiB and this changes nothing (256 MiB initial is far below
        // it); it only differs under -Dmeshelium.tune.arenaBlockMiB, the
        // knob that exists precisely to force the multi-block paths to run.
        long expectedInitial = Math.min(
                com.deds.meshelium.MesheliumScaling.arenaInitialBytes(),
                com.deds.meshelium.MesheliumScaling.arenaBlockBytes());
        if (c.arenaCapacityBytes() < expectedInitial) {
            throw new AssertionError("arena capacity " + c.arenaCapacityBytes()
                    + " below the initial " + expectedInitial + ": " + c);
        }
        if (c.arenaGrowths() == 0 && c.arenaCapacityBytes() != expectedInitial) {
            throw new AssertionError("arena capacity " + c.arenaCapacityBytes()
                    + " != the initial " + expectedInitial + " although no growth fired "
                    + "(wave 14: capacity is initial-until-grown; 256 MiB is the "
                    + "option<=32 standard initial): " + c);
        }
        if (c.decoderSkippedLayers() != 0) {
            throw new AssertionError(
                    "vanilla layers skipped by the BLOCK-format gate (format drift?): " + c);
        }
        if (c.droppedEncoding() != 0 || c.droppedOversize() != 0 || c.droppedArenaFull() != 0
                || c.droppedRegionBudget() != 0 || c.staleParks() != 0) {
            throw new AssertionError("drops/stale parks in a vanilla world: " + c);
        }
    }

    private static void assertBacklogDrains(ClientGameTestContext context) {
        context.waitFor(client -> TerrainResidency.counters().stagingBacklogEntries() == 0,
                BACKLOG_DRAIN_TICKS);
        assertNoError();
    }

    /**
     * Walk the singleplayer camera a few chunks: teleport the player 128
     * blocks (8 chunks) and wait for the new area to download + render —
     * fresh section builds must flow through the tap.
     */
    private static void walkCamera(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runCommand("execute as @p at @s run tp @s ~128 ~ ~");
        singleplayer.getClientLevel().waitForChunksDownload();
        singleplayer.getClientLevel().waitForChunksRender();
    }

    private static void assertGrowth(ClientGameTestContext context, long uploadedBefore) {
        context.waitFor(client ->
                TerrainResidency.counters().uploadedSections() > uploadedBefore,
                RESIDENCY_TIMEOUT_TICKS);
        TerrainResidency.Counters c = counters(context);
        if (c.sectionsResident() <= 0 || c.quadsResident() <= 0) {
            throw new AssertionError("counters did not stay live after the walk: " + c);
        }
        assertNoError();
    }

    /**
     * Let the build pipeline go quiet before closing the world, so the
     * dispose-time leak assertion isn't racing a mesh that vanilla itself
     * never individually frees (an in-flight compiled-but-never-promoted
     * mesh at teardown is released only by vanilla's wholesale buffer
     * close, which is not a per-mesh event).
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
        throw new AssertionError("build pipeline never went quiet before world close");
    }

    /**
     * Half one of the real teardown model: at the title screen after a
     * world close, vanilla has freed NOTHING (section-build doc note 11 —
     * no setLevel teardown exists; releaseAllBuffers is F3+A/shutdown
     * only), so Meshelium must still be holding its copies too. A drop to
     * zero here would actually be a bug: it would mean our store freed
     * copies whose vanilla twins still hold uber-buffer allocations.
     */
    private static void assertRetentionAtTitle(ClientGameTestContext context) {
        TerrainResidency.Counters now = counters(context);
        if (now.sectionsResident() <= 0 || now.quadsResident() <= 0) {
            throw new AssertionError("retention violated: vanilla keeps its "
                    + "meshes at the title screen but Meshelium's store went to "
                    + "zero - frees are firing from a path vanilla does not "
                    + "free on: " + now);
        }
        assertNoError();
    }

    /**
     * Half two — the leak test that matters, asserted where vanilla
     * ACTUALLY frees: opening the next world repositions the surviving
     * dispatcher's slots onto new sections, and every reposition/rebuild
     * releases the old mesh through {@code releaseSectionMesh} → the
     * row-3 hook → {@code freedSections++}. If that cumulative counter
     * does not move while a whole second world builds, the free path is
     * broken and the arena would grow monotonically across world loads —
     * the exact leak this test exists to catch.
     */
    private static void assertFreesFlowInSecondWorld(ClientGameTestContext context) {
        long freedBefore = TerrainResidency.counters().freedSections();
        try (TestSingleplayerContext second = context.worldBuilder().create()) {
            second.getClientLevel().waitForChunksRender();
            context.waitFor(client ->
                    TerrainResidency.counters().freedSections() > freedBefore,
                    RESIDENCY_TIMEOUT_TICKS);
            TerrainResidency.Counters now = counters(context);
            if (now.sectionsResident() <= 0) {
                throw new AssertionError(
                        "second world built but nothing resident: " + now);
            }
            assertNoError();
        }
        assertNoError();
    }

    // ------------------------------------------------------------------
    // GL path — the dormancy proof
    // ------------------------------------------------------------------

    private static void assertGlDormant(ClientGameTestContext context, String when) {
        context.waitTicks(40); // give any misfiring hook time to show up
        TerrainResidency.Counters c = counters(context);
        if (!c.isCompletelyIdle()) {
            throw new AssertionError("Meshelium residency touched something on the OpenGL path ("
                    + when + "): " + c);
        }
        if (TerrainResidency.lastDisposeSnapshot() != null) {
            throw new AssertionError("dispose hook fired on the OpenGL path (" + when + ")");
        }
        assertNoError();
    }

    // ------------------------------------------------------------------

    private static TerrainResidency.Counters counters(ClientGameTestContext context) {
        return context.computeOnClient(client -> TerrainResidency.counters());
    }

    private static void assertNoError() {
        String error = TerrainResidency.lastError();
        if (error != null) {
            throw new AssertionError("terrain residency reported an error: " + error);
        }
    }
}
