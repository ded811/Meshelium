/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.gametest.client;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.MesheliumPlatform;
import com.deds.meshelium.sodium.MesheliumRegionMirror;
import com.deds.meshelium.sodium.MesheliumRegionRunCache;
import com.deds.meshelium.sodium.MesheliumSodiumFacing;
import com.deds.meshelium.sodium.MesheliumSodiumHooks;
import com.deds.meshelium.vk.MesheliumGpuTimers;
import com.deds.meshelium.vk.SodiumGpuVisibilityLayout;
import com.deds.meshelium.vk.SodiumTerrainDrawer;
import com.deds.meshelium.vk.TerrainOcclusion;
import com.deds.meshelium.vk.VisibleSetSample;

import com.deds.meshelium.gui.MesheliumAdvancedScreen;
import com.deds.meshelium.gui.MesheliumOptionsScreen;
import com.deds.meshelium.gui.MesheliumPopupScreen;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * With Sodium installed, Meshelium stands aside and the world still draws.
 *
 * <h2>The bug this exists for</h2>
 * <p>Installing both mods produced a world with no terrain at all — only
 * entities and particles. Reported by a player, then reproduced here.
 *
 * <p>Two faults stacked. Meshelium and Sodium both {@code @Inject} at
 * {@code @At("HEAD")} on
 * {@code ChunkSectionsToRender.renderGroup(ChunkSectionLayerGroup, GpuSampler)}
 * and both call {@code ci.cancel()}. Mixin returns from a target as soon as
 * any HEAD callback cancels it, so only the first-applied injector ever ran
 * — a silent race decided by mod load order. And Sodium replaces vanilla's
 * chunk build path, which is where Meshelium's geometry comes from, so even
 * when Meshelium won the race it had no sections to draw. Entities and
 * particles survived because they never pass through that method.
 *
 * <h2>Why this is its own entrypoint</h2>
 * <p>The ordinary suite asserts that Meshelium renders. Under Sodium it
 * deliberately does not, so those tests SHOULD fail and running them here
 * would prove nothing. {@code -Pmeshelium.sodium} swaps the entrypoint list
 * to exactly this class, the same way the bench modes do.
 *
 * <h2>What a pass means</h2>
 * <p>Three things, and the third is the one that matters to a player:
 *
 * <ol>
 *   <li>Sodium is actually loaded — otherwise this test is vacuous and
 *       would pass on an ordinary run, which is the failure mode this
 *       project keeps getting caught by.</li>
 *   <li>The gate decided {@code SODIUM_PRESENT}, so every Meshelium hook
 *       is dormant. Each one tests {@code != VULKAN_MESH_SHADERS}, so a
 *       single state change stands the whole mod down.</li>
 *   <li>A world loads and renders. The screenshot is the evidence, because
 *       "terrain is missing" is a claim about pixels and no counter can
 *       stand in for it.</li>
 * </ol>
 */
public final class MesheliumSodiumStandDownTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            // Guard against a vacuous pass FIRST. Without Sodium the rest
            // of this test would sail through while proving nothing at
            // all, and a green suite that tested nothing is worse than a
            // red one.
            if (!MesheliumPlatform.isModLoaded(MesheliumGate.SODIUM_MOD_ID)) {
                throw new AssertionError(
                        "MesheliumSodiumStandDownTest ran without Sodium installed, so it would "
                                + "have proven nothing. Run it with -Pmeshelium.sodium, which puts "
                                + "Sodium on the run classpath and swaps the entrypoint list to "
                                + "this class alone.");
            }
        });

        context.waitFor(client -> MesheliumGate.state() != MesheliumGate.State.UNKNOWN);
        context.runOnClient(client -> {
            MesheliumGate.State state = MesheliumGate.state();
            if (state != MesheliumGate.State.SODIUM_PRESENT) {
                throw new AssertionError(
                        "Sodium is installed but the gate decided " + state + " instead of "
                                + "SODIUM_PRESENT. Meshelium is about to fight Sodium for the "
                                + "terrain draw, and the player will get a world with no terrain "
                                + "in it.");
            }
        });

        // The backend half of the decision is kept beside SODIUM_PRESENT.
        // On the OpenGL run the directive's popup is the whole story: the
        // adapter cannot draw without Vulkan, the popup is how the player
        // learns that, and every beta.4..8 jar skipped it under Sodium
        // (owner report 2026-09-08 (beta.8)). The 90-97 world legs need the
        // adapter's Vulkan device, so the GL run ends after the title-screen
        // legs. Wired by -Pmeshelium.sodium -Pmeshelium.backend=opengl.
        boolean[] openGl = new boolean[1];
        context.runOnClient(client ->
                openGl[0] = MesheliumGate.backend() == MesheliumGate.State.OPENGL);
        if (openGl[0]) {
            assertSodiumOpenGlPopup(context);
            assertMesheliumStillReachableUnderSodium(context);
            // ITEM (1) under Sodium: the popup reaches a player who never
            // saw a title screen, with the SODIUM wording. Shared with
            // MesheliumBootSmokeTest so the two runs cannot drift; its own
            // screenshot prefix because both write into one directory on
            // the same -Pmeshelium.backend=opengl -Pmeshelium.sodium
            // invocation.
            MesheliumBootSmokeTest.assertPopupArrivesWithNoTitleScreen(
                    context, "meshelium.popup.opengl.body.sodium", "92");
            return;
        }
        // No popup on the Vulkan path under Sodium either, exactly as on
        // the standalone path (MesheliumBootSmokeTest.assertVulkanPath).
        context.runOnClient(client -> {
            if (client.gui.screen() instanceof MesheliumPopupScreen popup) {
                throw new AssertionError(
                        "No popup may appear on the Vulkan path under Sodium, but got "
                                + popup.variant());
            }
            // Nor may one be OWED. Since 2026-09-16 an owed popup lands in
            // a world as well as at a title screen, so "none on screen
            // here" is no longer the whole guarantee: the world legs below
            // would get it instead.
            if (MesheliumGate.popupOwed()) {
                throw new AssertionError("a popup is owed on the Vulkan path under Sodium. "
                        + "Nothing arms it there, and it would now land in the world the 90-97 "
                        + "legs open next");
            }
        });

        assertMesheliumStillReachableUnderSodium(context);
        assertSodiumSliderAcceptsTheWidenedRange(context);

        // The 90-96 legs prove rung 1 (the list path and D-021's run cache).
        // With the GPU draw on (the default since D-025) rung 0 would own
        // every frame and none of their counters would move; the 97 legs
        // turn it back on.
        context.runOnClient(client -> SodiumTerrainDrawer.setGpuDrawEnabled(false));

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            HarnessCompat.waitForChunksRender(world);
            context.takeScreenshot(TestScreenshotOptions.of("90_meshelium_sodium_stand_down"));

            assertMesheliumDrawsSodiumsTerrain(context);
            assertStatusHeaderCountsSections(context);

            // The gate must not drift once a world is up. The decision is
            // taken once per session, but the hooks read it every frame,
            // and a mid-session change would mean terrain appearing or
            // vanishing under the player.
            context.runOnClient(client -> {
                if (MesheliumGate.state() != MesheliumGate.State.SODIUM_PRESENT) {
                    throw new AssertionError(
                            "The gate left SODIUM_PRESENT after the world loaded, and is now "
                                    + MesheliumGate.state());
                }
            });
        }

        assertCutoutGeometryDrawsInARealWorld(context);
    }

    /**
     * The same draw, in a world that actually exercises it.
     *
     * <p>A superflat world is grass and nothing else, and grass is SOLID.
     * That leaves both of the format conversion's quiet traps untested:</p>
     *
     * <ul>
     *   <li>The <b>material byte's bit order</b> differs between the two
     *       formats, and only the alpha-cutoff index can change a pixel.
     *       Getting it wrong gives leaves and glass the 0.1 cutoff instead
     *       of 0.5 — a fringe around every cutout sprite, not a crash.</li>
     *   <li>The <b>texture field's sign bit</b> would, if read as part of
     *       the coordinate, add exactly 1.0 to two of every quad's four
     *       UVs. On a flat plain of one repeating sprite that is far less
     *       visible than it is on a tree.</li>
     * </ul>
     *
     * <p>So: a real noise world, and screenshots to look at. Neither trap
     * has an assertion that could catch it — both produce a picture that is
     * wrong rather than absent — which is exactly why the pictures are the
     * deliverable here and the counters are only the guard against a
     * vacuous pass.</p>
     */
    private static void assertCutoutGeometryDrawsInARealWorld(ClientGameTestContext context) {
        if ("false".equalsIgnoreCase(System.getProperty("meshelium.sodium.adapter"))) {
            return;
        }
        long before = SodiumTerrainDrawer.totalSectionsDrawn();

        try (TestSingleplayerContext world = context.worldBuilder()
                .adjustSettings(settings -> {
                    settings.setName("meshelium-sodium-noise");
                    settings.setSeed("meshelium-sodium");
                    settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(
                            settings.getSettings().worldgenLoadContext()
                                    .lookupOrThrow(Registries.WORLD_PRESET)
                                    .getOrThrow(WorldPresets.NORMAL)));
                })
                .create()) {
            HarnessCompat.waitForChunksRender(world);
            // Worldgen keeps arriving after the first drawable frame, and a
            // shot taken while a tree is still building photographs a hole
            // that is not a bug.
            context.waitTicks(200);

            for (int step = 0; step < 4; step++) {
                final int shot = step;
                context.runOnClient(client -> {
                    if (client.player != null) {
                        client.player.setYRot(client.player.getYRot() + 90.0f);
                        client.player.yRotO = client.player.getYRot();
                        // Tip the view down a little: at spawn the horizon
                        // is mostly sky, and the geometry worth looking at
                        // is below it.
                        client.player.setXRot(15.0f);
                        client.player.xRotO = 15.0f;
                    }
                });
                context.waitTicks(60);
                context.takeScreenshot(TestScreenshotOptions.of(
                        String.format("93_meshelium_sodium_noise_%02d_yaw%03d", shot, shot * 90)));
            }

            context.runOnClient(client -> {
                if (SodiumTerrainDrawer.broken()) {
                    throw new AssertionError(
                            "Meshelium's draw failed in the noise world: "
                                    + SodiumTerrainDrawer.error());
                }
                long drawn = SodiumTerrainDrawer.totalSectionsDrawn() - before;
                if (drawn <= 0L) {
                    throw new AssertionError(
                            "a real generated world loaded but Meshelium drew none of it, so "
                                    + "those screenshots are Sodium's work");
                }
                // The dispatch shape is fixed for the session, so these
                // screenshots are evidence for ONE shape only. Say which,
                // or a reader cannot tell a screenshotted path from one
                // that was benchmarked blind - which is how a broken
                // group-map variant once got measured as the fastest.
                System.out.println("[Meshelium] Sodium stage 1, noise world: " + drawn
                        + " sections drawn by mesh shaders, dispatch shape "
                        + SodiumTerrainDrawer.shapeName() + ".");
            });

            assertRunCacheParityAndTransitions(context, world);
            // D-023 stages 2-3, gated on -Dmeshelium.sodium.gpuDraw=true: the
            // mirror, the GPU draw and the rasters, proven by transitions in
            // the same world the run cache was just proven in.
            assertGpuVisibilityLegs(context, world);
        }
    }

    /** Indices into {@link #sampleCounters}. */
    private static final int S_RUNS = 0;
    private static final int S_REGIONS = 1;
    private static final int S_RUNS_WRITTEN = 2;
    private static final int S_QUADS_KEPT = 3;
    private static final int S_QUADS_CULLED = 4;
    private static final int S_REGIONS_HIT = 5;
    private static final int S_REGIONS_REBUILT = 6;
    private static final int S_ARMED = 7;

    /**
     * D-021's per-region run cache: it draws exactly what the walk draws,
     * and every hook that invalidates it has callers.
     *
     * <h2>Why parity is asserted by counters</h2>
     * <p>The cache's key includes the region's listed byte SEQUENCE, so a
     * hit replays the walk's run table run for run, in the same order. At
     * one pose in a settled world the per-pass counters — runs, regions,
     * run-table entries, quads kept, quads culled — must therefore be
     * identical with the cache on and off, and that is a stronger claim
     * than a screenshot can make. The screenshots are taken anyway: a
     * counter cannot see a base vertex pointing at the wrong bytes, and
     * this project has watched a green suite photograph black holes.
     *
     * <h2>Why transitions</h2>
     * <p>A steady screenshot proves the cache replays; it proves nothing
     * about invalidation, which is the whole risk (memory: transitions,
     * not steady states; verify the guarantee has callers). So three
     * transitions, each asserting the hook counters it must move: a block
     * edit next to the player (the upload hook, and a region rebuild); a
     * teleport past the render distance and back (section removal, region
     * deletion); a render-distance change at runtime (every region
     * deleted, a new manager, the draw still going). The segment-move hook
     * needs arena fragmentation that a short leg cannot force on purpose
     * (memory: quiet means volume), so it is printed rather than asserted.
     *
     * <h2>Why the settle</h2>
     * <p>A live world never stops ticking, and a chunk built between the
     * two samples would fail the parity compare for a reason that has
     * nothing to do with the cache. Each attempt waits for the upload hook
     * to go quiet first, and the compare is retried a few times before it
     * is called a failure.
     */
    private static void assertRunCacheParityAndTransitions(ClientGameTestContext context,
            TestSingleplayerContext world) {
        if ("false".equalsIgnoreCase(System.getProperty(SodiumTerrainDrawer.PROPERTY_RUN_CACHE))) {
            System.out.println("[Meshelium] run-cache leg skipped: -D"
                    + SodiumTerrainDrawer.PROPERTY_RUN_CACHE + "=false");
            return;
        }
        if (Boolean.getBoolean("meshelium.sodium.diag.allSlots")) {
            // The diagnostic draws every slot of a listed region, which the
            // cache's geometry-set key cannot represent, so the draw list
            // runs the walk by design; asserting "armed" here would blame a
            // hook for a lever (second review of this leg).
            System.out.println("[Meshelium] run-cache leg skipped: "
                    + "-Dmeshelium.sodium.diag.allSlots=true forces the walk");
            return;
        }
        // The camera key's exactness, on a lattice, against Sodium's own
        // facing test. Pure CPU; it lives here because the sodium tree has
        // no unit-test source set and the method under test is Sodium's.
        String lattice = MesheliumRegionRunCache.cameraKeyLatticeFailure();
        if (lattice != null) {
            throw new AssertionError("run-cache camera key: " + lattice);
        }

        TestServerContext server = world.getServer();
        double[] pose = context.computeOnClient(client -> new double[] {
                client.player.getX(), client.player.getY(), client.player.getZ(),
                client.player.getYRot(), client.player.getXRot()});
        // Pin yaw and pitch through the same command every return uses, so
        // every "back at the pose" screenshot is the same pose.
        String home = teleportCommand(pose[0], pose[1], pose[2], pose[3], pose[4]);
        server.runCommand(home);
        context.waitTicks(5);

        // 1. Steady state: the cache on, then off, at one pose.
        long[] on = null;
        long[] off = null;
        boolean parity = false;
        for (int attempt = 0; attempt < 4 && !parity; attempt++) {
            waitForUploadsToSettle(context);
            on = sampleCounters(context);
            if (attempt == 0) {
                context.takeScreenshot(TestScreenshotOptions.of("96_00_steady_on"));
            }
            if (on[S_ARMED] != 1L) {
                throw new AssertionError("the run cache is not armed with this Sodium: hooks "
                        + "found=0x" + Integer.toHexString(MesheliumSodiumHooks.hooksFound())
                        + " missing=[" + MesheliumSodiumHooks.describeMissing() + "] disarmed="
                        + MesheliumSodiumHooks.disarmReason() + ". Either a hook target moved "
                        + "or the guard fired; both mean the pinned spec no longer matches.");
            }
            context.runOnClient(client -> SodiumTerrainDrawer.setRunCacheEnabled(false));
            context.waitTicks(10);
            off = sampleCounters(context);
            if (attempt == 0) {
                context.takeScreenshot(TestScreenshotOptions.of("96_01_steady_off"));
            }
            context.runOnClient(client -> SodiumTerrainDrawer.setRunCacheEnabled(true));
            context.waitTicks(10);
            parity = on[S_RUNS] == off[S_RUNS]
                    && on[S_REGIONS] == off[S_REGIONS]
                    && on[S_RUNS_WRITTEN] == off[S_RUNS_WRITTEN]
                    && on[S_QUADS_KEPT] == off[S_QUADS_KEPT]
                    && on[S_QUADS_CULLED] == off[S_QUADS_CULLED];
            if (!parity) {
                System.out.println("[Meshelium] run-cache parity attempt " + attempt
                        + " differed (on " + describe(on) + ", off " + describe(off)
                        + "); settling and retrying");
            }
        }
        if (!parity) {
            throw new AssertionError("the run cache and the walk disagree at the same pose: "
                    + "cache on " + describe(on) + ", cache off " + describe(off)
                    + ". With the byte-sequence key the two must be identical run for run; "
                    + "a difference is a stale entry or a key that is too coarse.");
        }
        if (on[S_REGIONS_HIT] < 1L) {
            throw new AssertionError("the cache was armed at a settled pose but replayed "
                    + on[S_REGIONS_HIT] + " regions (rebuilt " + on[S_REGIONS_REBUILT]
                    + "), so the key never matches and the cache is a slower walk");
        }
        if (off[S_ARMED] != 0L || off[S_REGIONS_HIT] != 0L) {
            throw new AssertionError("setRunCacheEnabled(false) did not force the walk: armed="
                    + off[S_ARMED] + " hits=" + off[S_REGIONS_HIT]);
        }
        System.out.println("[Meshelium] run-cache steady parity: on " + describe(on)
                + ", off " + describe(off));

        // 2. A block edit next to the player: the upload hook, and a rebuild.
        // The edit must provably change a block, and the counters must be
        // read from a quiet baseline: a /setblock onto the same state is a
        // vanilla no-op (no packet, no rebuild), and a straggling worldgen
        // upload inside the window would satisfy the deltas without the edit
        // ever reaching the cache (both found by the second review). So the
        // edit is done on the server thread with a state chosen to differ,
        // its return value asserted, after the uploads have gone quiet.
        waitForUploadsToSettle(context);
        long uploadsBefore = SodiumTerrainDrawer.hookUpload();
        long rebuiltBefore = SodiumTerrainDrawer.regionsRebuiltTotal();
        double yaw = Math.toRadians(pose[3]);
        int bx = (int) Math.floor(pose[0] - Math.sin(yaw) * 3.0);
        int by = (int) Math.floor(pose[1]);
        int bz = (int) Math.floor(pose[2] + Math.cos(yaw) * 3.0);
        boolean changed = server.computeOnServer(mc -> {
            ServerLevel level = mc.overworld();
            BlockPos pos = new BlockPos(bx, by, bz);
            BlockState current = level.getBlockState(pos);
            BlockState next = (current.is(Blocks.STONE) ? Blocks.GLOWSTONE : Blocks.STONE)
                    .defaultBlockState();
            return level.setBlock(pos, next, 3);
        });
        if (!changed) {
            throw new AssertionError("the block edit at " + bx + " " + by + " " + bz
                    + " did not change the world, so this leg cannot test the upload hook");
        }
        context.waitTicks(40);
        if (SodiumTerrainDrawer.hookUpload() <= uploadsBefore) {
            throw new AssertionError("a block was changed at " + bx + " " + by + " " + bz
                    + " next to the player and the upload hook did not fire (" + uploadsBefore
                    + " before and after). The section was rebuilt and uploaded by Sodium, "
                    + "so the hook has no callers - the cache would keep drawing the old "
                    + "geometry.");
        }
        if (SodiumTerrainDrawer.regionsRebuiltTotal() <= rebuiltBefore) {
            throw new AssertionError("the upload hook fired after a block edit but no region "
                    + "was rebuilt from the walk; the epoch did not reach the cache key");
        }
        context.takeScreenshot(TestScreenshotOptions.of("96_02_block_edit"));

        // 3. Past the render distance and back: sections removed, regions
        //    deleted, the cache dropped with them.
        long removedBefore = SodiumTerrainDrawer.hookRemoveSection();
        long deletedBefore = SodiumTerrainDrawer.hookDelete();
        int rd = context.computeOnClient(client -> client.options.getEffectiveRenderDistance());
        double farX = pose[0] + 16.0 * (2 * rd + 16);
        int farColumnX = (int) Math.floor(farX);
        int farColumnZ = (int) Math.floor(pose[2]);
        // Land on the surface: the server generates that one chunk
        // synchronously if it has not yet, and a fall from the sky in a
        // survival world would end the test a different way.
        int farSurface = server.computeOnServer(mc -> mc.overworld().getHeight(
                Heightmap.Types.MOTION_BLOCKING, farColumnX, farColumnZ));
        server.runCommand(teleportCommand(farX, farSurface + 1.0, pose[2], pose[3], pose[4]));
        context.waitTicks(100);
        if (SodiumTerrainDrawer.hookRemoveSection() <= removedBefore) {
            throw new AssertionError("the player moved " + (int) (farX - pose[0]) + " blocks at "
                    + "render distance " + rd + " and no section was removed from a region "
                    + "(removeSection hook " + removedBefore + " before and after). Either "
                    + "the hook has no callers or the client never unloaded the old chunks "
                    + "(memory: 26.2 slides the storage window without an unload event).");
        }
        if (SodiumTerrainDrawer.hookDelete() <= deletedBefore) {
            throw new AssertionError("sections were removed after the far teleport but no "
                    + "region was deleted (delete hook " + deletedBefore + " before and after); "
                    + "empty regions should die in RenderRegionManager.update");
        }
        server.runCommand(home);
        HarnessCompat.waitForChunksRender(world);
        context.waitTicks(40);
        context.takeScreenshot(TestScreenshotOptions.of("96_03_after_reload"));

        // 4. Render distance changed at runtime: Sodium builds a new
        //    manager, every old region is deleted, the draw carries on.
        int rdBefore = context.computeOnClient(client -> client.options.renderDistance().get());
        int rdOther = rdBefore >= 24 ? rdBefore - 8 : rdBefore + 8;
        int regionsBefore = SodiumTerrainDrawer.lastRegionsDrawn();
        long deletedBeforeRd = SodiumTerrainDrawer.hookDelete();
        long framesBefore = SodiumTerrainDrawer.framesDrawn();
        context.runOnClient(client -> {
            client.options.renderDistance().set(rdOther);
            client.options.save();
        });
        context.waitTicks(100);
        long deletedByRd = SodiumTerrainDrawer.hookDelete() - deletedBeforeRd;
        if (deletedByRd < regionsBefore) {
            throw new AssertionError("render distance " + rdBefore + " -> " + rdOther
                    + " deleted " + deletedByRd + " regions, fewer than the " + regionsBefore
                    + " drawn in the last pass before it; Sodium's reload destroys every "
                    + "region, so the delete hook is missing callers");
        }
        if (SodiumTerrainDrawer.framesDrawn() <= framesBefore) {
            throw new AssertionError("no opaque pass was drawn by Meshelium after the "
                    + "render-distance change; the new RenderSectionManager was not wrapped");
        }
        context.runOnClient(client -> {
            client.options.renderDistance().set(rdBefore);
            client.options.save();
        });
        HarnessCompat.waitForChunksRender(world);
        context.waitTicks(40);
        context.takeScreenshot(TestScreenshotOptions.of("96_04_rd_back"));

        // 5. The whole leg's hook census.
        if (SodiumTerrainDrawer.hookBufferChange() <= 0L) {
            // onGeometryBufferChange fires when a SHARED arena overflows and
            // an owner is moved to another arena, or when compaction moves
            // owners - not on a region's first upload. The render-distance
            // excursion above loaded a fresh RenderSectionManager with a new
            // 32 MiB shared arena into a normal-preset world at 13 chunks,
            // whose opaque geometry must overflow it (second review, verified
            // against the arena aggregator's sizing).
            throw new AssertionError("the buffer-change hook never fired, although the "
                    + "render-distance excursion loaded a fresh region manager whose shared "
                    + "arena the world's geometry must overflow; this hook has no callers");
        }
        if (SodiumTerrainDrawer.broken()) {
            throw new AssertionError("Meshelium's draw failed during the run-cache leg: "
                    + SodiumTerrainDrawer.error());
        }
        long[] last = sampleCounters(context);
        if (last[S_ARMED] != 1L) {
            throw new AssertionError("the run cache is no longer armed after the transitions: "
                    + "disarmed=" + MesheliumSodiumHooks.disarmReason());
        }
        System.out.println("[Meshelium] run-cache leg: last pass " + describe(last)
                + "; totals hit=" + SodiumTerrainDrawer.regionsHitTotal()
                + " rebuilt=" + SodiumTerrainDrawer.regionsRebuiltTotal()
                + "; hooks upload=" + SodiumTerrainDrawer.hookUpload()
                + " removeSection=" + SodiumTerrainDrawer.hookRemoveSection()
                + " bufferChange=" + SodiumTerrainDrawer.hookBufferChange()
                + " segmentChange=" + SodiumTerrainDrawer.hookSegmentChange()
                + " (not asserted: needs fragmentation)"
                + " delete=" + SodiumTerrainDrawer.hookDelete());
    }

    /** The per-pass counters, read on the client thread so they belong to one frame. */
    private static long[] sampleCounters(ClientGameTestContext context) {
        return context.computeOnClient(client -> new long[] {
                SodiumTerrainDrawer.lastSectionsDrawn(),
                SodiumTerrainDrawer.lastRegionsDrawn(),
                SodiumTerrainDrawer.lastRunsWritten(),
                SodiumTerrainDrawer.lastQuadsKept(),
                SodiumTerrainDrawer.lastQuadsCulled(),
                SodiumTerrainDrawer.lastRegionsHit(),
                SodiumTerrainDrawer.lastRegionsRebuilt(),
                SodiumTerrainDrawer.runCacheArmed() ? 1L : 0L});
    }

    private static String describe(long[] s) {
        return "runs=" + s[S_RUNS] + " regions=" + s[S_REGIONS] + " runsWritten="
                + s[S_RUNS_WRITTEN] + " quadsKept=" + s[S_QUADS_KEPT] + " quadsCulled="
                + s[S_QUADS_CULLED] + " hit=" + s[S_REGIONS_HIT] + " rebuilt="
                + s[S_REGIONS_REBUILT] + " armed=" + s[S_ARMED];
    }

    /**
     * Wait until the upload hook has been quiet for two seconds, or give
     * up after a minute and let the compare say what it finds. Uploads are
     * the only thing that changes a region's runs at a fixed camera.
     */
    private static void waitForUploadsToSettle(ClientGameTestContext context) {
        long last = -1L;
        int quiet = 0;
        int waited = 0;
        while (quiet < 40 && waited < 1200) {
            context.waitTicks(5);
            waited += 5;
            long now = SodiumTerrainDrawer.hookUpload();
            if (now == last) {
                quiet += 5;
            } else {
                quiet = 0;
                last = now;
            }
        }
    }

    private static String teleportCommand(double x, double y, double z, double yaw, double pitch) {
        return "tp @p " + fmt(x) + " " + fmt(y) + " " + fmt(z) + " " + fmt(yaw) + " " + fmt(pitch);
    }

    /** Command-safe decimal, root locale (a comma would split the argument). */
    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ------------------------------------------------------------------
    // D-023 stages 2-3: the GPU-visibility legs (97_*)
    // ------------------------------------------------------------------

    /**
     * Pitch every "at the pose" shot of the 97 legs is taken at: a little
     * below the horizon so terrain, not sky, fills the frame. The horizon
     * leg (97_15) is the one exception and says so.
     */
    private static final double GPU_PITCH = 10.0;

    /** Ticks a leg waits for a rung, a counter or a readback before it calls the run a failure. */
    private static final int GPU_TIMEOUT_TICKS = 1200;

    /**
     * Owned frames a stats readback is allowed to trail the frame it
     * describes: the ring's READBACK_LAG (= FREE_FRAME_LAG) plus one for
     * the fold on the client thread. Every "wait for the readback" below
     * adds this to the stats-frame index it wants to see.
     */
    private static final int READBACK_MARGIN = SodiumGpuVisibilityLayout.FREE_FRAME_LAG + 1;

    /** Indices into {@link #gpuSample}. */
    private static final int G_OWNED = 0;
    private static final int G_ATTEMPTED = 1;
    private static final int G_DECLINES = 2;
    private static final int G_MASK = 3;
    private static final int G_A = 4;
    private static final int G_B = 5;
    private static final int G_QUADS = 6;
    private static final int G_STATS_READ = 7;
    private static final int G_LISTED = 8;
    private static final int G_GROUPS = 9;
    private static final int G_DRAWS = 10;
    private static final int G_MIDS_LIVE = 11;
    private static final int G_MIDS_CAP = 12;

    /** What the 97 legs carry between themselves. */
    private static final class GpuLegs {
        double x;
        double y;
        double z;
        /** Snapped to a multiple of 90 (see {@link #pinPose}). */
        double yaw;
        /** Unit forward vector of the snapped yaw, block axes. */
        int fx;
        int fz;
        /** The teleport that restores the pose at {@link #GPU_PITCH}. */
        String home;
        /** The 97_00 sample, or null if that leg failed. */
        long[] steady;
        /** 97_10's A+B and listed regions, or -1 if that leg failed. */
        long ab10 = -1L;
        long listed10 = -1L;
        /** False for the rung-0b legs, true once 97_10 has turned occlusion on. */
        boolean occlusionPhase;
        /** Set once the GPU draw latches broken: nothing after that can run. */
        boolean aborted;
        final List<String> failures = new ArrayList<>();
    }

    /**
     * D-023 stages 2 and 3: the GPU record mirror and the box-raster
     * occlusion on the Sodium host, proven by transitions and screenshots.
     *
     * <h2>What is being proven</h2>
     * <p>The contract's six invariants (GPU-VISIBILITY-CONTRACT.md §8),
     * each as the thing that would be seen if it failed: a record the task
     * stage reads that differs from Sodium's heap draws WRONG geometry, not
     * missing geometry (I1); a stale id or key would draw a dead region's
     * records (I5) or read the wrong buffer (I4); a failure that does not
     * end in a complete frame is a black pass (I6). None of those is a
     * counter a bench would notice, all of them are pixels, and this
     * project has watched a green suite photograph black holes — so every
     * leg takes its named screenshot for the coordinator to open, and the
     * counters exist to stop a vacuous pass, exactly as the 96 leg does.
     *
     * <h2>Why VisMode 0 first, and why parity is by GPU stats</h2>
     * <p>Rung 0b draws the BFS mask Sodium's list path drew, so at one
     * pose its GPU-counted survivors and quads must equal the list path's
     * {@code sectionsEmitted} and {@code quadsKept}: the mirror, the id
     * and key plumbing, the facing formula in GLSL and the record walk are
     * all inside that one equality. Only once it holds is the occlusion
     * lever flipped (97_10), because a stage-3 result on a wrong stage-2
     * draw would measure nothing.
     *
     * <h2>Why every leg runs even after one fails</h2>
     * <p>A suite run here is minutes of a single JVM and leaves ~20
     * screenshots the coordinator reads by eye; stopping at the first
     * failed assertion would throw away the evidence of every later leg
     * for the price of one re-run. Each leg is therefore recorded rather
     * than thrown, restarts from the pinned pose with the levers where its
     * phase wants them, and the run fails at the end with every message.
     * The one thing that stops the run is the GPU draw latching broken:
     * from then on every leg would be photographing the list path.
     *
     * <h2>Gate</h2>
     * <p>Skipped, with a printed reason, unless
     * {@code -Dmeshelium.sodium.gpuDraw=true} reached the client JVM
     * ({@code -Pmeshelium.vmargs="-Dmeshelium.sodium.gpuDraw=true"}); the
     * legs flip occlusion themselves so one run proves both rungs and the
     * transition between them. {@code -Dmeshelium.sodium.mirrorAudit=true}
     * arms the byte audit the I1 assertions read; without it they pass
     * vacuously and say so.
     */
    private static void assertGpuVisibilityLegs(ClientGameTestContext context,
            TestSingleplayerContext world) {
        if (!com.deds.meshelium.MesheliumConfig.sodiumGpuVisibilityEnabled()) {
            // Default on since D-025; off only by -Dmeshelium.sodium.gpuDraw=false
            // or the config row, either of which is a deliberate list-path run.
            System.out.println("[Meshelium] GPU-visibility legs (97_*) skipped: the GPU draw is "
                    + "off (-D" + SodiumTerrainDrawer.PROPERTY_GPU_DRAW + "=false or the config "
                    + "row); add -D" + SodiumTerrainDrawer.PROPERTY_MIRROR_AUDIT
                    + "=true for the byte audit when it runs");
            return;
        }
        // The facing formula the task stage carries, checked against
        // Sodium's own method on a lattice before a single frame is
        // judged. Pure CPU, here for the same reason the run cache's
        // camera-key lattice is: the sodium tree has no unit-test source
        // set and the method under test is Sodium's.
        String lattice = MesheliumSodiumFacing.latticeFailure();
        if (lattice != null) {
            throw new AssertionError("GPU facing formula: " + lattice);
        }

        GpuLegs legs = new GpuLegs();
        pinPose(context, world, legs);

        runLeg(context, world, legs, "97_00/01", () -> legSteadyAndParity(context, legs));
        runLeg(context, world, legs, "97_02", () -> legBlockEdit(context, world, legs));
        runLeg(context, world, legs, "97_03", () -> legFarTeleport(context, world, legs));
        runLeg(context, world, legs, "97_04", () -> legRenderDistance(context, world, legs, false));
        runLeg(context, world, legs, "97_05", () -> legForcedDecline(context, legs, false));
        runLeg(context, world, legs, "97_06", () -> legEmptyThenGeometry(context, world, legs));
        runLeg(context, world, legs, "97_07", () -> legGrowth(context, world, legs));
        runLeg(context, world, legs, "97_10", () -> legOcclusionOn(context, legs));
        legs.occlusionPhase = true;
        runLeg(context, world, legs, "97_11", () -> legHiddenWall(context, world, legs));
        // NEXT (c): the half-res superset leg flips the lever ITSELF, so one
        // run proves off, on and the transition; the property decides only
        // what the OTHER legs run under.
        runLeg(context, world, legs, "97_18", () -> legHalfResSuperset(context, world, legs));
        runLeg(context, world, legs, "97_12", () -> legCameraCut(context, world, legs));
        runLeg(context, world, legs, "97_13", () -> legRenderDistance(context, world, legs, true));
        runLeg(context, world, legs, "97_14", () -> legReloadAll(context, world, legs));
        runLeg(context, world, legs, "97_15", () -> legHorizon(context, world, legs));
        runLeg(context, world, legs, "97_16", () -> legForcedDecline(context, legs, true));
        runLeg(context, world, legs, "97_17", () -> legFrustumRegions(context, legs));
        runLeg(context, world, legs, "97_20", () -> legGeometryMapParity(context));
        // NEXT (c1): the WALKING leg, and LAST is a hard requirement rather
        // than a preference. assertHalfResHealthy (97_18) and legOcclusionOn
        // (97_10) both assert halfResArmSkips() != 0 -> throw ABSOLUTELY, so
        // a leg that may deliberately drive skips up must run after both or
        // it turns 97_18 red for a reason that is not 97_18's. It also
        // walks 64 blocks off the pinned pose and rewrites a runway, and
        // nothing comes after it but gpuCensus, which gets no teleport - so
        // it restores the pose itself.
        runLeg(context, world, legs, "97_19", () -> legWalkingBob(context, world, legs));
        gpuCensus(context, legs);

        if (!legs.failures.isEmpty()) {
            StringBuilder all = new StringBuilder("GPU-visibility legs failed (")
                    .append(legs.failures.size()).append("):");
            for (String f : legs.failures) {
                all.append("\n - ").append(f);
            }
            throw new AssertionError(all.toString());
        }
    }

    /**
     * Run one leg from a known footing: the levers where this phase wants
     * them, the pinned pose, and nothing left over from a leg that failed
     * half way. A failed assertion is recorded and the next leg runs; the
     * GPU draw latching broken stops everything after it.
     */
    private static void runLeg(ClientGameTestContext context, TestSingleplayerContext world,
            GpuLegs legs, String name, Runnable body) {
        if (legs.aborted) {
            System.out.println("[Meshelium] " + name + " skipped: an earlier leg latched the GPU "
                    + "draw broken (" + SodiumTerrainDrawer.gpuDrawError() + ")");
            return;
        }
        boolean occlusion = legs.occlusionPhase;
        context.runOnClient(client -> {
            SodiumTerrainDrawer.setGpuDrawEnabled(true);
            SodiumTerrainDrawer.setOcclusionEnabled(occlusion);
        });
        world.getServer().runCommand(legs.home);
        context.waitTicks(5);
        try {
            body.run();
        } catch (AssertionError e) {
            legs.failures.add(name + ": " + e.getMessage());
            System.out.println("[Meshelium] " + name + " FAILED: " + e.getMessage());
            if (SodiumTerrainDrawer.gpuDrawBroken()) {
                legs.aborted = true;
            }
        }
    }

    /**
     * Pin the run's spawn pose with the yaw snapped to an axis. The
     * hidden-wall leg fills an axis-aligned slab square to the view; a
     * diagonal view would see past its edge, and "sections drawn must
     * fall" would be a claim about the corner of a wall.
     *
     * <h2>NEXT (c): the world is FROZEN and the pose is CHOSEN</h2>
     * The 97 world was never frozen (no {@code advance_time} anywhere in
     * this file) while 97_18 takes samples tens of seconds apart and
     * compares PNGs. The bench's own freeze list goes in here, and clouds
     * go OFF on the client thread because the freeze cannot stop them:
     * {@code LevelRenderer} hands {@code addCloudsPass} the
     * {@code LevelRenderState.gameTime} field - the world's tick counter,
     * not the day time {@code advance_time} freezes (whether 26.2's
     * {@code ServerLevel.tickTime} advances gameTime under the rule is
     * UNVERIFIED; the cloud pass's INPUT is verified) - and skips the pass
     * entirely at {@code CloudStatus.OFF}. This also sharpens the existing
     * 97_00/01 and 97_15 picture pairs.
     *
     * <h2>And the pose is TELEPORTED to a compliant one, not inspected</h2>
     * The half-res near force is applied to the inflated REGION box too,
     * whose inflation is {@code k * D_far} of a 128x64x128-block occupancy
     * AABB: a neighbour region one block across the boundary has
     * D_far ~ 193 blocks, so at the harness k it reaches ~1.83 blocks and a
     * camera 1.0 block from a region boundary would force-stamp a whole
     * neighbour region on half-res frames only - cost invisible to every
     * counter and charged to the very segment the lever exists to shrink.
     * Snapping x and z to the mid-section, mid-region value puts the eye 8
     * blocks from any multiple of 16 and 64 from any region boundary, which
     * is far more than the ~1.9 the bound asks for. Only y is left to the
     * ground, and 97_18 prints all three margins and FAILS with the numbers
     * if the ground put it somewhere non-compliant - a message about the
     * harness pose, not a verdict about the lever.
     */
    private static void pinPose(ClientGameTestContext context, TestSingleplayerContext world,
            GpuLegs legs) {
        TestServerContext server = world.getServer();
        // The bench's freeze list (26.2's namespaced gamerule names).
        server.runCommand("time set noon");
        server.runCommand("gamerule minecraft:advance_time false");
        server.runCommand("weather clear");
        server.runCommand("gamerule minecraft:advance_weather false");
        // An entity in frame is a pixel delta the tighter picture bar counts.
        server.runCommand("gamerule minecraft:spawn_mobs false");
        server.runCommand("gamerule minecraft:random_tick_speed 0");
        context.runOnClient(client -> client.options.cloudStatus().set(CloudStatus.OFF));

        double[] pose = context.computeOnClient(client -> new double[] {
                client.player.getX(), client.player.getY(), client.player.getZ(),
                client.player.getYRot()});
        // Mid-section AND far from a region boundary in x/z. 72.5, not the
        // 64.5 this first shipped with: 64.5 is 0.5 blocks from the multiple
        // of 16 at 64, which is the opposite of mid-section and left the
        // pose rule passing by 0.03 blocks. 72.5 is 8.5 from every multiple
        // of 16 and 55.5 from every multiple of 128.
        legs.x = 128.0 * Math.round((pose[0] - 64.0) / 128.0) + 72.5;
        legs.z = 128.0 * Math.round((pose[2] - 64.0) / 128.0) + 72.5;
        // The ground AT THE NEW COLUMN. The snap moves x and z by up to 64
        // blocks and the 97 legs run in the NOISE world, so the spawn
        // column's y is unrelated to the new one: keeping it teleported the
        // player into mid-air, and every leg that edits a block "next to the
        // player" or waits for a rebuild then failed for a reason that had
        // nothing to do with what it tests (2026-09-15, the first F.3 run:
        // 97_02, 97_03, 97_06, 97_11, 97_12 and 97_17 all red on both the
        // lever-off control and the lever-on run).
        legs.y = surfaceAt(server, (int) Math.floor(legs.x), (int) Math.floor(legs.z)) + 1.0;
        legs.yaw = Math.round(pose[3] / 90.0) * 90.0;
        double rad = Math.toRadians(legs.yaw);
        // Minecraft's yaw: 0 looks down +Z, 90 down -X.
        legs.fx = (int) Math.round(-Math.sin(rad));
        legs.fz = (int) Math.round(Math.cos(rad));
        legs.home = teleportCommand(legs.x, legs.y, legs.z, legs.yaw, GPU_PITCH);
        server.runCommand(legs.home);
        context.waitTicks(5);
        HarnessCompat.waitForChunksRender(world);
        context.waitTicks(20);
        System.out.println("[Meshelium] pinPose: world frozen (noon, no weather, no mobs, no "
                + "random ticks, clouds OFF) and the pose snapped to mid-section/mid-region "
                + "x=" + fmt(legs.x) + " z=" + fmt(legs.z) + " (y=" + fmt(legs.y)
                + " is the ground's, not ours)");
    }

    /**
     * 97_00 and 97_01: rung 0b steady, then the list path at the same
     * pose, and the two must agree.
     *
     * <p>The GPU figures are a lagged readback, so the sample waits for
     * the readback covering the frames it describes; the list-path
     * figures are the counters the 96 leg already trusts. Retried like the
     * 96 parity, because a chunk built between the two samples fails the
     * compare for a reason that has nothing to do with the draw.</p>
     */
    private static void legSteadyAndParity(ClientGameTestContext context, GpuLegs legs) {
        long owned0 = SodiumTerrainDrawer.framesOwned();
        waitForRung(context, "0b", owned0, "97_00");
        // NEXT (c): a GUARD, not evidence. Occlusion is OFF on rung 0b, so
        // armHalfRes never runs and the lever cannot be exercised here
        // whatever the property says - which is exactly why this leg must
        // never be read as proof the lever works. 97_10 and 97_18 own that.
        long halfAt00 = TerrainOcclusion.halfResFrames();

        long[] on = null;
        long emitted = -1L;
        long kept = -1L;
        long lastPassEmitted = -1L;
        long lastPassKept = -1L;
        boolean parity = false;
        for (int attempt = 0; attempt < 4 && !parity; attempt++) {
            waitForUploadsToSettle(context);
            long[] a = gpuSample(context);
            if (!pollTicks(context, GPU_TIMEOUT_TICKS,
                    () -> SodiumTerrainDrawer.framesOwned() >= a[G_OWNED] + 60
                            || SodiumTerrainDrawer.gpuDrawBroken())) {
                throw new AssertionError("97_00: fewer than 60 owned frames in " + GPU_TIMEOUT_TICKS
                        + " ticks (" + describeGpu(gpuSample(context)) + ", rung="
                        + SodiumTerrainDrawer.rung() + ", declines=" + declinesText() + ")");
            }
            assertNotBroken("97_00");
            long[] b = gpuSample(context);
            if (attempt == 0) {
                if (b[G_DECLINES] != a[G_DECLINES]) {
                    throw new AssertionError("97_00: " + (b[G_DECLINES] - a[G_DECLINES])
                            + " frames declined over " + (b[G_OWNED] - a[G_OWNED])
                            + " owned frames at a settled pose (" + declinesText()
                            + "); a steady world with an empty dirty set has nothing to "
                            + "decline for");
                }
                if (!"0b".equals(SodiumTerrainDrawer.rung())) {
                    throw new AssertionError("97_00: rung is " + SodiumTerrainDrawer.rung()
                            + " with the GPU draw on and occlusion off; expected 0b");
                }
                long buildNanos = SodiumTerrainDrawer.lastBuildNanos();
                if (buildNanos != 0L) {
                    throw new AssertionError("97_00: buildMicros is " + buildNanos / 1000.0
                            + " on an owned frame; rung 0 must not build the list, and must "
                            + "report a zero build time so that this can be seen "
                            + "(contract 9, 97_00)");
                }
                assertMirrorAuditClean("97_00");
            }
            on = gpuSampleAfterReadback(context, "97_00");
            if (attempt == 0) {
                context.takeScreenshot(TestScreenshotOptions.of("97_00_gpudraw_on"));
            }

            // The list path at the same pose.
            context.runOnClient(client -> SodiumTerrainDrawer.setGpuDrawEnabled(false));
            context.waitTicks(10);
            waitForRungOne(context, "97_01");
            // The GPU stats accumulate over both opaque passes of a frame,
            // so the list path's per-pass counters are compared as their
            // SOLID + CUTOUT sums, which the drawer keeps for this compare.
            long[] cpu = context.computeOnClient(client -> new long[] {
                    SodiumTerrainDrawer.sectionsEmittedFrame(),
                    SodiumTerrainDrawer.quadsKeptFrame(),
                    SodiumTerrainDrawer.sectionsEmitted(),
                    SodiumTerrainDrawer.lastQuadsKept()});
            emitted = cpu[0];
            kept = cpu[1];
            lastPassEmitted = cpu[2];
            lastPassKept = cpu[3];
            if (attempt == 0) {
                context.takeScreenshot(TestScreenshotOptions.of("97_01_list_off"));
            }
            long ownedAt = SodiumTerrainDrawer.framesOwned();
            context.runOnClient(client -> SodiumTerrainDrawer.setGpuDrawEnabled(true));
            waitForRung(context, "0b", ownedAt, "97_01");

            parity = on[G_MASK] == emitted && on[G_QUADS] == kept;
            if (!parity) {
                System.out.println("[Meshelium] 97_01 parity attempt " + attempt
                        + " differed (GPU sections=" + on[G_MASK] + " quads=" + on[G_QUADS]
                        + "; list sectionsEmitted=" + emitted + " quadsKept=" + kept
                        + "); settling and retrying");
            }
        }
        if (!parity) {
            throw new AssertionError("97_01: the GPU draw (VisMode 0) and the list path disagree "
                    + "at the same pose: GPU sections=" + on[G_MASK] + " quads=" + on[G_QUADS]
                    + " vs list SOLID+CUTOUT sectionsEmitted=" + emitted + " quadsKept=" + kept
                    + " (last pass alone " + lastPassEmitted + " / " + lastPassKept + "). "
                    + "VisMode 0 draws the BFS mask the list path drew, so the two must be "
                    + "identical: a difference is a stale record, a wrong facing mask or a "
                    + "run walk that skipped a cursor advance.");
        }
        if (on[G_MASK] <= 0L) {
            throw new AssertionError("97_00: the GPU counted 0 VisMode-0 survivors at a pose "
                    + "where the list path drew " + emitted + " sections; the stats are not "
                    + "being written or not being read back");
        }
        // NEXT (c): the guard. No occlusion frame ran, so no half-res frame
        // could have; a rise here would mean the arm ran somewhere it does
        // not belong.
        if (TerrainOcclusion.halfResFrames() != halfAt00) {
            throw new AssertionError("97_00: halfResFrames moved from " + halfAt00 + " to "
                    + TerrainOcclusion.halfResFrames() + " on rung 0b, where occlusion is OFF "
                    + "and armHalfRes cannot run at all");
        }
        if (MesheliumGpuTimers.live()) {
            long down = MesheliumGpuTimers.lastPassNanosSnapshot()[
                    MesheliumGpuTimers.PASS_DOWNSAMPLE];
            if (down != -1L) {
                throw new AssertionError("97_00: the downsample pass timed " + down
                        + " ns on rung 0b, where it is never recorded");
            }
        }
        legs.steady = on;
        System.out.println("[Meshelium] 97_00/01: rung 0b " + describeGpu(on)
                + "; list path SOLID+CUTOUT sectionsEmitted=" + emitted + " quadsKept=" + kept
                + " (last pass " + lastPassEmitted + " / " + lastPassKept + ")"
                + "; loopMicros=" + SodiumTerrainDrawer.loopNanos() / 1000.0
                + " commitMicros=" + SodiumTerrainDrawer.commitNanos() / 1000.0
                + " bufferKeys=" + SodiumTerrainDrawer.bufferKeys()
                + " gpuTimerUnit=" + SodiumTerrainDrawer.gpuTimerUnit());
    }

    /**
     * 97_02: a block edit next to the player reaches the mirror. The
     * upload hook fires (stage 1), the region is committed (stage 2), and
     * the shot shows the edit drawn from the mirrored record.
     */
    private static void legBlockEdit(ClientGameTestContext context, TestSingleplayerContext world,
            GpuLegs legs) {
        waitForUploadsToSettle(context);
        long uploadsBefore = SodiumTerrainDrawer.hookUpload();
        long commitsBefore = SodiumTerrainDrawer.commitRegionsTotal();
        // Three blocks ahead, one above the 96_02 edit; the same
        // STONE/GLOWSTONE toggle, so it provably changes the world.
        int bx = (int) Math.floor(legs.x) + legs.fx * 3;
        int by = (int) Math.floor(legs.y) + 1;
        int bz = (int) Math.floor(legs.z) + legs.fz * 3;
        boolean changed = world.getServer().computeOnServer(mc -> {
            ServerLevel level = mc.overworld();
            BlockPos pos = new BlockPos(bx, by, bz);
            BlockState current = level.getBlockState(pos);
            BlockState next = (current.is(Blocks.STONE) ? Blocks.GLOWSTONE : Blocks.STONE)
                    .defaultBlockState();
            return level.setBlock(pos, next, 3);
        });
        if (!changed) {
            throw new AssertionError("the block edit at " + bx + " " + by + " " + bz
                    + " did not change the world, so this leg cannot test the mirror commit");
        }
        long[] maxBytes = new long[1];
        pollTicks(context, 400, () -> {
            maxBytes[0] = Math.max(maxBytes[0], SodiumTerrainDrawer.commitBytesPerFrame());
            return SodiumTerrainDrawer.hookUpload() > uploadsBefore
                    && SodiumTerrainDrawer.commitRegionsTotal() > commitsBefore;
        });
        if (SodiumTerrainDrawer.hookUpload() <= uploadsBefore) {
            throw new AssertionError("a block was changed at " + bx + " " + by + " " + bz
                    + " next to the player and the upload hook did not fire (" + uploadsBefore
                    + " before and after); the hook has no callers, so the mirror would keep "
                    + "the old record");
        }
        long committed = SodiumTerrainDrawer.commitRegionsTotal() - commitsBefore;
        if (committed <= 0L) {
            throw new AssertionError("the upload hook fired after the block edit but no region "
                    + "was committed to the mirror (" + commitsBefore + " before and after); the "
                    + "dirty mark never reached commit, and the GPU would draw the old records "
                    + "(I1)");
        }
        // commitBytesPerFrame is a per-frame figure sampled once a tick
        // from this thread; at 200 fps the commit frame is easy to miss, so
        // the byte count is observed rather than asserted (one pass block
        // plus a row is 12,352 B; a cumulative counter would make it exact).
        System.out.println("[Meshelium] 97_02: block edit committed " + committed
                + " region(s); largest commitBytesPerFrame seen while waiting " + maxBytes[0]
                + (maxBytes[0] >= 12352L ? " (>= 12352: a pass block and its row)"
                        : " (the commit frame was not sampled; not a failure)"));
        waitForUploadsToSettle(context);
        assertNotBroken("97_02");
        context.takeScreenshot(TestScreenshotOptions.of("97_02_block_edit"));
    }

    /**
     * 97_03: past the render distance and back. Regions die (the delete
     * hook), their rows are zeroed and their ids released behind the lag
     * (I5), every returning region with geometry is mirrored again, and no
     * id names a region Sodium has dropped.
     *
     * <p>Why the mirror is held against Sodium's region map and not
     * against its own 97_00 count (the first version, which failed with 9
     * against 24): the wake sweep ({@code ensureTracking}) mirrors every
     * LOADED region, built or not, so 97_00 counted all 24 regions of the
     * rd-5 cube; the hooks mirror only the regions Sodium uploads into;
     * and at a fixed camera Sodium builds only the sections its graph walk
     * reaches (javap: the cull task's {@code TaskCollectingTree} collects
     * pending sections along {@code OcclusionCuller.findVisible}, bounded
     * by the fog distance and blocked by solid ground). Reload the same
     * chunks and the live count lands wherever the walk stops, and stays
     * there until the camera moves. That is not a leak (a leak raises the
     * count) and not a mirroring gap (a listed region without an id is
     * {@code midMissing}, asserted zero). The claims that do hold are the
     * census's two zeros.
     */
    private static void legFarTeleport(ClientGameTestContext context, TestSingleplayerContext world,
            GpuLegs legs) {
        TestServerContext server = world.getServer();
        long removedBefore = SodiumTerrainDrawer.hookRemoveSection();
        long deletedBefore = SodiumTerrainDrawer.hookDelete();
        long deadBefore = SodiumTerrainDrawer.deadRows();
        long releasedBefore = SodiumTerrainDrawer.midsReleased();
        int rd = context.computeOnClient(client -> client.options.getEffectiveRenderDistance());
        double farX = legs.x + 16.0 * (2 * rd + 16);
        int farSurface = surfaceAt(server, (int) Math.floor(farX), (int) Math.floor(legs.z));
        server.runCommand(teleportCommand(farX, farSurface + 1.0, legs.z, legs.yaw, GPU_PITCH));
        context.waitTicks(100);
        if (SodiumTerrainDrawer.hookRemoveSection() <= removedBefore) {
            throw new AssertionError("the player moved " + (int) (farX - legs.x) + " blocks at "
                    + "render distance " + rd + " and no section was removed from a region "
                    + "(removeSection hook " + removedBefore + " before and after)");
        }
        if (SodiumTerrainDrawer.hookDelete() <= deletedBefore) {
            throw new AssertionError("sections were removed after the far teleport but no "
                    + "region was deleted (delete hook " + deletedBefore + " before and after)");
        }
        // Rows die in the commit that follows the delete hook and ids come
        // free three owned frames later (I5): both need owned frames, which
        // the far side provides once its chunks arrive.
        pollTicks(context, 400, () -> SodiumTerrainDrawer.deadRows() > deadBefore
                && SodiumTerrainDrawer.midsReleased() > releasedBefore);
        if (SodiumTerrainDrawer.deadRows() <= deadBefore) {
            throw new AssertionError("regions were deleted after the far teleport but no mirror "
                    + "row was zeroed (deadRows " + deadBefore + " before and after); the delete "
                    + "hook did not reach the mirror, and a reused id could draw the dead "
                    + "region's records (I5)");
        }
        if (SodiumTerrainDrawer.midsReleased() <= releasedBefore) {
            throw new AssertionError("rows were zeroed after the far teleport but no id was "
                    + "released (midsReleased " + releasedBefore + " before and after); the id "
                    + "allocator leaks and the mirror will grow without bound");
        }
        if (SodiumTerrainDrawer.keyMismatch() != 0L || SodiumTerrainDrawer.midMissing() != 0L) {
            throw new AssertionError("97_03: keyMismatch=" + SodiumTerrainDrawer.keyMismatch()
                    + " midMissing=" + SodiumTerrainDrawer.midMissing() + " after the teleport; a "
                    + "listed region reached the loop with a stale buffer key or no id, which "
                    + "means the dirty tracking missed a mutation");
        }
        server.runCommand(legs.home);
        HarnessCompat.waitForChunksRender(world);
        context.waitTicks(40);
        waitForUploadsToSettle(context);
        // A returned region gets its id at the first owned frame after its
        // upload, and a dead id leaves the live count at the first owned
        // frame after its delete; the settle bounds both, and the poll
        // keeps one slow frame from reading as a gap. A leak does not heal
        // with time, so the poll cannot hide one.
        pollTicks(context, 200, () -> {
            int[] now = census(context);
            return now != null && now[MesheliumRegionMirror.CENSUS_UNMIRRORED] == 0
                    && now[MesheliumRegionMirror.CENSUS_MIRRORED] == SodiumTerrainDrawer.midsLive();
        });
        int[] c = census(context);
        if (c == null) {
            throw new AssertionError("97_03: no mirror is live back at the pose");
        }
        long live = SodiumTerrainDrawer.midsLive();
        String counts = "midsLive " + live + " (97_00 "
                + (legs.steady == null ? "n/a" : String.valueOf(legs.steady[G_MIDS_LIVE]))
                + "), regions deleted " + (SodiumTerrainDrawer.hookDelete() - deletedBefore)
                + ", rows zeroed " + (SodiumTerrainDrawer.deadRows() - deadBefore)
                + ", ids released " + (SodiumTerrainDrawer.midsReleased() - releasedBefore)
                + ", loaded " + c[MesheliumRegionMirror.CENSUS_LOADED]
                + ", listable " + c[MesheliumRegionMirror.CENSUS_LISTABLE]
                + ", unmirrored " + c[MesheliumRegionMirror.CENSUS_UNMIRRORED]
                + ", orphaned " + c[MesheliumRegionMirror.CENSUS_ORPHANED]
                + ", mirrored " + c[MesheliumRegionMirror.CENSUS_MIRRORED];
        if (c[MesheliumRegionMirror.CENSUS_ORPHANED] != 0) {
            throw new AssertionError("97_03: " + c[MesheliumRegionMirror.CENSUS_ORPHANED]
                    + " id(s) still name a region Sodium no longer holds (" + counts + "); the "
                    + "delete hook missed them and the id allocator leaks");
        }
        if (c[MesheliumRegionMirror.CENSUS_UNMIRRORED] != 0) {
            throw new AssertionError("97_03: " + c[MesheliumRegionMirror.CENSUS_UNMIRRORED]
                    + " returned region(s) with geometry have no id (" + counts + "); the upload "
                    + "hook is not reaching the mirror for regions created after the reload");
        }
        if (live != c[MesheliumRegionMirror.CENSUS_MIRRORED]) {
            throw new AssertionError("97_03: the id allocator counts " + live + " live ids but "
                    + "the shadow table names " + c[MesheliumRegionMirror.CENSUS_MIRRORED]
                    + " regions (" + counts + "); the two disagree after the dead list drained");
        }
        System.out.println("[Meshelium] 97_03: " + counts);
        assertNotBroken("97_03");
        context.takeScreenshot(TestScreenshotOptions.of("97_03_after_reload"));
    }

    /**
     * 97_04 (rung 0b) and 97_13 (rung 0a): the render distance changed at
     * runtime. Sodium builds a new manager, the old renderer instance
     * retires its mirror behind the lag, the new one starts empty at the
     * initial capacity, and the draw carries on — with its occlusion state
     * recreated when occlusion is on.
     */
    private static void legRenderDistance(ClientGameTestContext context,
            TestSingleplayerContext world, GpuLegs legs, boolean occlusion) {
        String leg = occlusion ? "97_13" : "97_04";
        String rung = occlusion ? "0a" : "0b";
        int rdBefore = renderDistance(context);
        int rdOther = occlusion
                ? (rdBefore >= 48 ? rdBefore - 16 : rdBefore + 16)
                : (rdBefore >= 24 ? rdBefore - 8 : rdBefore + 8);
        long retiredBefore = SodiumTerrainDrawer.instancesRetired();
        long recreatesBefore = SodiumTerrainDrawer.occlusionRecreates();
        // NEXT (c): the half-res target dies and is re-made WITH the
        // occlusion instance, so the two counters must move together.
        long halfAllocBefore = TerrainOcclusion.halfResAllocations();
        long ownedBefore = SodiumTerrainDrawer.framesOwned();
        setRenderDistance(context, rdOther);
        context.waitTicks(100);
        waitForRung(context, rung, ownedBefore, leg + " (rd " + rdBefore + " -> " + rdOther + ")");
        long ownedMid = SodiumTerrainDrawer.framesOwned();
        if (!pollTicks(context, 200, () -> SodiumTerrainDrawer.framesOwned() > ownedMid + 10)) {
            throw new AssertionError(leg + ": framesOwned stopped rising on the new instance ("
                    + describeGpu(gpuSample(context)) + ")");
        }
        if (!occlusion && SodiumTerrainDrawer.midsCapacity() < SodiumGpuVisibilityLayout.CAPACITY_INITIAL) {
            throw new AssertionError(leg + ": the new instance's mirror capacity is "
                    + SodiumTerrainDrawer.midsCapacity() + ", below CAPACITY_INITIAL "
                    + SodiumGpuVisibilityLayout.CAPACITY_INITIAL);
        }
        setRenderDistance(context, rdBefore);
        HarnessCompat.waitForChunksRender(world);
        context.waitTicks(40);
        long ownedBack = SodiumTerrainDrawer.framesOwned();
        waitForRung(context, rung, ownedBack, leg + " (rd back to " + rdBefore + ")");
        long retired = SodiumTerrainDrawer.instancesRetired() - retiredBefore;
        if (retired < 2L) {
            throw new AssertionError(leg + ": render distance " + rdBefore + " -> " + rdOther
                    + " -> " + rdBefore + " retired " + retired + " renderer instance(s), not 2; "
                    + "Sodium builds a new RenderSectionManager per change and each old "
                    + "MesheliumChunkRenderer must retire its mirror behind the lag (I4/I5)");
        }
        if (occlusion) {
            long recreates = SodiumTerrainDrawer.occlusionRecreates() - recreatesBefore;
            if (recreates < 2L) {
                throw new AssertionError("97_13: the occlusion state was recreated " + recreates
                        + " time(s) across two render-distance changes, not 2; a new instance "
                        + "must start with zero-filled stamps at its own capacity");
            }
            // NEXT (c): the target is the instance's, so its allocations
            // must move WITH the recreates - not by a constant 2, which a
            // mirror growth in the same leg (one more recreate) would fail.
            if (Boolean.getBoolean(TerrainOcclusion.PROPERTY_HALF_RES)) {
                long allocs = TerrainOcclusion.halfResAllocations() - halfAllocBefore;
                if (allocs != recreates) {
                    throw new AssertionError("97_13: the half-res target was (re)created "
                            + allocs + " time(s) across " + recreates + " occlusion recreate(s); "
                            + "the target lives on the occlusion instance and must die with it");
                }
                if (TerrainOcclusion.halfResBroken()
                        || TerrainOcclusion.halfResAllocationFailures() != 0L) {
                    throw new AssertionError("97_13: half-res broken="
                            + TerrainOcclusion.halfResError() + " allocationFailures="
                            + TerrainOcclusion.halfResAllocationFailures()
                            + " after the render-distance changes");
                }
                System.out.println("[Meshelium] 97_13: half-res allocations=" + allocs
                        + " == occlusionRecreates=" + recreates + " (both >= 2)");
            }
        }
        waitForUploadsToSettle(context);
        assertNotBroken(leg);
        context.takeScreenshot(TestScreenshotOptions.of(occlusion
                ? "97_13_rd_occlusion" : "97_04_rd_back"));
    }

    /**
     * 97_05 (rung 0b) and 97_16 (rung 0a): a forced decline costs exactly
     * one frame, which rung 1 draws whole (I6), and the next owned frame
     * resumes. Under occlusion the declined frame ran no raster, so the
     * stamps of the last owned frame are still current: the first owned
     * frame after the decline draws that set in phase A and phase B adds
     * nothing new (second stage-2/3 review, 2026-09-07).
     */
    private static void legForcedDecline(ClientGameTestContext context, GpuLegs legs,
            boolean occlusion) {
        String leg = occlusion ? "97_16" : "97_05";
        String rung = occlusion ? "0a" : "0b";
        waitForUploadsToSettle(context);
        long ownedBefore = SodiumTerrainDrawer.framesOwned();
        waitForRung(context, rung, ownedBefore, leg);
        long forcedBefore = declines("forced");
        // Captured BEFORE the decline is armed: the declined frame records
        // no stats CB, so the first owned frame after it is stats frame
        // statsAtDecline (a capture after the poll could land frames later).
        long statsAtDecline = SodiumTerrainDrawer.statsFrames();
        context.runOnClient(client -> SodiumTerrainDrawer.forceDeclineNextFrame("test"));
        if (!pollTicks(context, 200, () -> declines("forced") > forcedBefore)) {
            throw new AssertionError(leg + ": forceDeclineNextFrame did not decline a frame within "
                    + "200 ticks (forced=" + declines("forced") + ", rung="
                    + SodiumTerrainDrawer.rung() + ", declines=" + declinesText() + ")");
        }
        long forced = declines("forced") - forcedBefore;
        if (forced != 1L) {
            throw new AssertionError(leg + ": the one-shot decline fired " + forced
                    + " times; it must cost exactly one frame");
        }
        long ownedAtDecline = SodiumTerrainDrawer.framesOwned();
        if (!pollTicks(context, 200, () -> SodiumTerrainDrawer.framesOwned() > ownedAtDecline)) {
            throw new AssertionError(leg + ": no owned frame followed the forced decline within "
                    + "200 ticks; the decline latched instead of costing one frame (rung="
                    + SodiumTerrainDrawer.rung() + ", " + describeGpu(gpuSample(context)) + ")");
        }
        if (!pollTicks(context, 200, () -> rung.equals(SodiumTerrainDrawer.rung()))) {
            throw new AssertionError(leg + ": rung is " + SodiumTerrainDrawer.rung()
                    + " after the forced decline, not " + rung);
        }
        if (occlusion) {
            // The declined frame ran no raster, so the last owned frame's
            // stamps are still current: the recovery frame's phase A draws
            // that frame's set and phase B draws nothing new. The earlier
            // expectation here (phase A empty, phase B redraws the world)
            // was reversed by the second stage-2/3 review, 2026-09-07.
            waitForReadbackOf(context, statsAtDecline + 1, leg);
            int a0 = SodiumTerrainDrawer.gpuSectionsAAt(statsAtDecline);
            if (a0 <= 0) {
                throw new AssertionError("97_16: the first owned frame after a forced decline drew "
                        + a0 + " phase-A sections; the stamps of the last owned frame must carry "
                        + "across a decline");
            }
            waitForReadbackOf(context, statsAtDecline + 3, leg);
            long a = SodiumTerrainDrawer.gpuSectionsA();
            if (a <= 0L) {
                throw new AssertionError("97_16: phase A drew " + a + " sections two frames after "
                        + "the forced decline; the stamps written on the recovery frame did not "
                        + "feed the next frame's phase A");
            }
        }
        waitForUploadsToSettle(context);
        assertNotBroken(leg);
        context.takeScreenshot(TestScreenshotOptions.of(occlusion
                ? "97_16_decline_occ" : "97_05_forced_decline"));
    }

    /**
     * 97_06: the {@code getId() == -1} case (design §3.3). A chest in an
     * all-air section builds with a block entity and no mesh, so Sodium
     * never acquires an id for its region; a stone block beside it then
     * gives the region geometry. The mirror keys on its own id, so the
     * region must be committed and drawn all the same.
     */
    private static void legEmptyThenGeometry(ClientGameTestContext context,
            TestSingleplayerContext world, GpuLegs legs) {
        TestServerContext server = world.getServer();
        int px = (int) Math.floor(legs.x);
        int pz = (int) Math.floor(legs.z);
        int startY = ((int) Math.floor(legs.y) + 40) & ~15;
        int[] section = server.computeOnServer(mc -> {
            ServerLevel level = mc.overworld();
            int x0 = px & ~15;
            int z0 = pz & ~15;
            for (int y0 = startY; y0 + 16 <= level.getMaxY(); y0 += 16) {
                boolean air = true;
                for (int x = x0; x < x0 + 16 && air; x++) {
                    for (int y = y0; y < y0 + 16 && air; y++) {
                        for (int z = z0; z < z0 + 16; z++) {
                            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                                air = false;
                                break;
                            }
                        }
                    }
                }
                if (air) {
                    return new int[] {x0, y0, z0};
                }
            }
            return null;
        });
        if (section == null) {
            throw new AssertionError("97_06: no all-air section above the player between y="
                    + startY + " and the build limit; the block-entity-only case cannot be set up "
                    + "here");
        }
        BlockPos chest = new BlockPos(section[0] + 8, section[1] + 8, section[2] + 8);
        boolean placed = server.computeOnServer(mc ->
                mc.overworld().setBlock(chest, Blocks.CHEST.defaultBlockState(), 3));
        if (!placed) {
            throw new AssertionError("97_06: the chest at " + chest + " was not placed");
        }
        context.waitTicks(40);
        waitForUploadsToSettle(context);
        long uploadsBefore = SodiumTerrainDrawer.hookUpload();
        long commitsBefore = SodiumTerrainDrawer.commitRegionsTotal();
        long missingBefore = SodiumTerrainDrawer.midMissing();
        BlockPos stone = chest.east();
        boolean placedStone = server.computeOnServer(mc ->
                mc.overworld().setBlock(stone, Blocks.STONE.defaultBlockState(), 3));
        if (!placedStone) {
            throw new AssertionError("97_06: the stone at " + stone + " was not placed");
        }
        pollTicks(context, 400, () -> SodiumTerrainDrawer.hookUpload() > uploadsBefore
                && SodiumTerrainDrawer.commitRegionsTotal() > commitsBefore);
        if (SodiumTerrainDrawer.hookUpload() <= uploadsBefore) {
            throw new AssertionError("97_06: the stone beside the chest did not fire the upload "
                    + "hook; the section was not rebuilt");
        }
        if (SodiumTerrainDrawer.commitRegionsTotal() <= commitsBefore) {
            throw new AssertionError("97_06: the block-entity-only section gained geometry and "
                    + "its region was never committed to the mirror; the region's Sodium id may "
                    + "be -1 here (design 3.3) and the mirror must not depend on it");
        }
        if (SodiumTerrainDrawer.midMissing() != missingBefore) {
            throw new AssertionError("97_06: midMissing moved by "
                    + (SodiumTerrainDrawer.midMissing() - missingBefore) + " when a region first "
                    + "gained geometry; the dirty mark that assigns a mid did not precede the "
                    + "frame's list");
        }
        // Sodium's own id for the region is printed by nothing here: the
        // gametest source set does not compile against Sodium, and the
        // contract names no probe for it. What is asserted is the property
        // that matters - the mirror never needed it.
        double eyeY = legs.y + 1.62;
        double dx = stone.getX() + 0.5 - legs.x;
        double dy = stone.getY() + 0.5 - eyeY;
        double dz = stone.getZ() + 0.5 - legs.z;
        double yawTo = Math.toDegrees(Math.atan2(-dx, dz));
        double pitchTo = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        server.runCommand(teleportCommand(legs.x, legs.y, legs.z, yawTo, pitchTo));
        context.waitTicks(40);
        waitForUploadsToSettle(context);
        assertNotBroken("97_06");
        System.out.println("[Meshelium] 97_06: chest at " + chest + ", stone at " + stone
                + "; commits " + (SodiumTerrainDrawer.commitRegionsTotal() - commitsBefore)
                + ", midMissing=" + SodiumTerrainDrawer.midMissing());
        context.takeScreenshot(TestScreenshotOptions.of("97_06_empty_then_geometry"));
        server.runCommand(legs.home);
        context.waitTicks(10);
    }

    /**
     * 97_07: mirror growth. A fresh instance is pinned below the number of
     * regions the client already holds at the pose, so its first commit
     * cannot fit and it has to grow; every growth declines its frame (rung
     * 1 draws it, I6) and the owned frames resume on the larger buffer
     * with no hole in the picture.
     *
     * <p>Why "below the live count" and not 64: the first version pinned
     * 64 and raised the render distance to 13 on the premise that the
     * regions "arrive in the hundreds". They do not. Sodium builds only
     * the sections its graph walk reaches from a fixed camera (see 97_03),
     * and at rd 13 in this world it uploaded into fewer than 64 regions in
     * 65 seconds: no growth line in the log, and the leg died first in
     * {@code waitForChunksRender} at rd 13, Fabric's 60-second timeout.
     * This version proves its premise from a counter it has:
     * {@code midsLive} at the pose (24 at rd 5: every loaded region, the
     * wake sweep mirrors them all) is above the pinned capacity, and the
     * new instance's wake sweep re-marks that same set before a single
     * chunk has to arrive.
     *
     * <p>The render-distance change is still what builds the new instance
     * (the contract's recipe), but nothing waits for the wider world to
     * render: the growth needs none of it.
     */
    private static void legGrowth(ClientGameTestContext context, TestSingleplayerContext world,
            GpuLegs legs) {
        long growthsBefore = SodiumTerrainDrawer.growths();
        long growthDeclinesBefore = declines("growth");
        int rdBefore = renderDistance(context);
        int rdOther = rdBefore >= 24 ? rdBefore - 8 : rdBefore + 8;
        waitForUploadsToSettle(context);
        int liveNow = SodiumTerrainDrawer.midsLive();
        int floor = SodiumGpuVisibilityLayout.CAPACITY_TEST_MIN;
        // The largest multiple of the floor strictly below the live count:
        // the new instance's wake sweep re-marks at least this many
        // regions, so its first commit cannot fit.
        int capacity = (liveNow - 1) / floor * floor;
        if (capacity < floor) {
            throw new AssertionError("97_07: only " + liveNow + " ids are live at the pose, not "
                    + "above the smallest capacity a test may pin (" + floor + "); this world "
                    + "cannot make a fresh instance overflow, so growth cannot be proven here");
        }
        context.runOnClient(client -> SodiumTerrainDrawer.setMirrorCapacityForTest(capacity));
        try {
            // A new instance is what picks the capacity up, and the render
            // distance change builds one. No waitForChunksRender at the
            // other distance: the growth comes from the regions the client
            // already holds, and that wait is the 60-second timeout the
            // first run died in.
            long ownedBefore = SodiumTerrainDrawer.framesOwned();
            setRenderDistance(context, rdOther);
            context.waitTicks(100);
            pollTicks(context, GPU_TIMEOUT_TICKS,
                    () -> (SodiumTerrainDrawer.growths() > growthsBefore
                            && SodiumTerrainDrawer.framesOwned() > ownedBefore)
                            || SodiumTerrainDrawer.gpuDrawBroken());
            assertNotBroken("97_07");
            long growths = SodiumTerrainDrawer.growths() - growthsBefore;
            if (growths < 1L) {
                throw new AssertionError("97_07: a fresh instance pinned at " + capacity + " ids "
                        + "took over " + liveNow + " live regions (rd " + rdBefore + " -> "
                        + rdOther + ") and never grew (midsLive=" + SodiumTerrainDrawer.midsLive()
                        + " capacity=" + SodiumTerrainDrawer.midsCapacity() + "); either the "
                        + "test capacity was not applied to the next instance or growth is not "
                        + "happening");
            }
            if (declines("growth") <= growthDeclinesBefore) {
                throw new AssertionError("97_07: the mirror grew " + growths + " time(s) but no "
                        + "frame was declined for it (" + declinesText() + "); the growth frame "
                        + "must decline so the dirty commits land on the new buffer next frame");
            }
            long ownedAfter = SodiumTerrainDrawer.framesOwned();
            if (!pollTicks(context, 200, () -> SodiumTerrainDrawer.framesOwned() > ownedAfter + 10)) {
                throw new AssertionError("97_07: owned frames did not resume after growth ("
                        + describeGpu(gpuSample(context)) + ")");
            }
            waitForUploadsToSettle(context);
            assertNotBroken("97_07");
            System.out.println("[Meshelium] 97_07: pinned " + capacity + " below " + liveNow
                    + " live; growths=" + growths + " capacity now "
                    + SodiumTerrainDrawer.midsCapacity() + " midsLive="
                    + SodiumTerrainDrawer.midsLive() + " declines=" + declinesText());
            context.takeScreenshot(TestScreenshotOptions.of("97_07_growth"));
        } finally {
            context.runOnClient(client -> SodiumTerrainDrawer.setMirrorCapacityForTest(0));
            setRenderDistance(context, rdBefore);
            HarnessCompat.waitForChunksRender(world);
            context.waitTicks(40);
        }
    }

    /**
     * 97_10: occlusion on at the pose. Rung 0a, the occlusion frame count
     * rising, the raster passes visible to the GPU timers, and phase A
     * plus phase B drawing no more than VisMode 0 drew at the same pose a
     * moment earlier — the rasters remove sections, they never add them.
     */
    private static void legOcclusionOn(ClientGameTestContext context, GpuLegs legs) {
        waitForUploadsToSettle(context);
        long[] pre = gpuSampleAfterReadback(context, "97_10");
        long ownedBefore = SodiumTerrainDrawer.framesOwned();
        long occBefore = SodiumTerrainDrawer.occlusionFrames();
        // NEXT (c): the half-res counter BEFORE the first occlusion frame of
        // the session, so the boot-time assertion below is about the FIRST
        // frames and not about a later runtime flip.
        long halfBefore = TerrainOcclusion.halfResFrames();
        boolean halfProperty = Boolean.getBoolean(TerrainOcclusion.PROPERTY_HALF_RES);
        context.runOnClient(client -> SodiumTerrainDrawer.setOcclusionEnabled(true));
        waitForRung(context, "0a", ownedBefore, "97_10");
        if (!pollTicks(context, 200, () -> SodiumTerrainDrawer.occlusionFrames() > occBefore + 10)) {
            throw new AssertionError("97_10: occlusionFrames is not rising under rung 0a ("
                    + SodiumTerrainDrawer.occlusionFrames() + " from " + occBefore + ")");
        }
        waitForUploadsToSettle(context);
        long[] s = gpuSampleAfterReadback(context, "97_10");
        long ab = s[G_A] + s[G_B];
        if (ab <= 0L) {
            throw new AssertionError("97_10: occlusion on drew 0 sections (A+B) at a pose where "
                    + "VisMode 0 drew " + pre[G_MASK] + "; the stamps are not marking or the "
                    + "stats are not being read");
        }
        if (ab > pre[G_MASK]) {
            throw new AssertionError("97_10: occlusion drew " + ab + " sections (A " + s[G_A]
                    + " + B " + s[G_B] + ") against " + pre[G_MASK] + " on the BFS-parity mask at "
                    + "the same pose; with graphRegions on the region set is Sodium's and the "
                    + "rasters can only remove sections");
        }
        if (MesheliumGpuTimers.live()) {
            long[] t = MesheliumGpuTimers.lastPassNanosSnapshot();
            long region = t[MesheliumGpuTimers.PASS_REGION_RASTER];
            long sectionRaster = t[MesheliumGpuTimers.PASS_SECTION_RASTER];
            if (region <= 0L || sectionRaster <= 0L) {
                throw new AssertionError("97_10: the GPU timers see no raster passes (regionRaster="
                        + region + " ns, sectionRaster=" + sectionRaster + " ns) while rung 0a "
                        + "is drawing; the timer marks are not placed around the Sodium-path "
                        + "rasters (gpuTimerUnit=" + SodiumTerrainDrawer.gpuTimerUnit() + ")");
            }
            System.out.println("[Meshelium] 97_10: GPU timers regionRaster=" + region / 1000.0
                    + " us sectionRaster=" + sectionRaster / 1000.0 + " us opaqueA="
                    + t[MesheliumGpuTimers.PASS_OPAQUE_A] / 1000.0 + " us phaseB="
                    + t[MesheliumGpuTimers.PASS_PHASE_B] / 1000.0 + " us (unit "
                    + SodiumTerrainDrawer.gpuTimerUnit() + ")");
        } else {
            System.out.println("[Meshelium] 97_10: GPU timers not live ("
                    + MesheliumGpuTimers.failure() + "); raster pass times not asserted");
        }
        // ---- NEXT (c): the lever, when the property armed it ----
        if (halfProperty) {
            long halfRan = TerrainOcclusion.halfResFrames() - halfBefore;
            long occRan = SodiumTerrainDrawer.occlusionFrames() - occBefore;
            if (TerrainOcclusion.halfResError() != null) {
                throw new AssertionError("97_10: the half-res path latched on the session's "
                        + "first occlusion frames: " + TerrainOcclusion.halfResError());
            }
            // The pipelines are built INSIDE the first arm (each half ensure
            // begins with its full-res ensure), so every occlusion frame
            // from the first must already be half-res. One frame of slack
            // for the counter read. This inequality is sound only because
            // halfResFrames counts in armHalfRes: counting it in the record
            // methods made an empty-list frame at a leg start look like a
            // lever fault.
            if (halfRan < occRan - 1L) {
                throw new AssertionError("97_10: with -D" + TerrainOcclusion.PROPERTY_HALF_RES
                        + "=true only " + halfRan + " of the first " + occRan + " occlusion "
                        + "frames ran half-res; a boot straight into the lever must build the "
                        + "half pipelines at the FIRST arm, not on a later flip");
            }
            if (TerrainOcclusion.halfResRasterFrames() <= 0L) {
                throw new AssertionError("97_10: halfResFrames rose to "
                        + TerrainOcclusion.halfResFrames() + " but no section raster ever "
                        + "recorded at half resolution");
            }
            if (TerrainOcclusion.halfResArmSkips() != 0L
                    || TerrainOcclusion.halfResAllocationFailures() != 0L) {
                throw new AssertionError("97_10: halfResArmSkips="
                        + TerrainOcclusion.halfResArmSkips() + " allocationFailures="
                        + TerrainOcclusion.halfResAllocationFailures()
                        + "; a session whose arm skipped is red, never a quiet lever-off row");
            }
            if (MesheliumGpuTimers.live()) {
                long down = MesheliumGpuTimers.lastPassNanosSnapshot()[
                        MesheliumGpuTimers.PASS_DOWNSAMPLE];
                if (down <= 0L) {
                    throw new AssertionError("97_10: the GPU timers see no downsample pass ("
                            + down + " ns) while the lever is on; without it the timer assertion "
                            + "above would pass on the old full-size passes and the lever would "
                            + "never have been exercised");
                }
                System.out.println("[Meshelium] 97_10: half-res ON - downsample=" + down / 1000.0
                        + " us, halfFrames=" + halfRan + "/" + occRan + " size="
                        + TerrainOcclusion.halfResWidth() + "x"
                        + TerrainOcclusion.halfResHeight());
            }
        } else if (TerrainOcclusion.halfResFrames() != halfBefore) {
            throw new AssertionError("97_10: half-res frames ran (" + halfBefore + " -> "
                    + TerrainOcclusion.halfResFrames() + ") with -D"
                    + TerrainOcclusion.PROPERTY_HALF_RES + " absent; that is a property leak");
        }
        legs.ab10 = ab;
        legs.listed10 = s[G_LISTED];
        System.out.println("[Meshelium] 97_10: occlusion on " + describeGpu(s)
                + " (VisMode-0 mask at the same pose " + pre[G_MASK] + "; 97_00 "
                + (legs.steady == null ? "n/a" : String.valueOf(legs.steady[G_MASK])) + ")"
                + " searchDistance=" + SodiumTerrainDrawer.searchDistanceSource() + "/"
                + SodiumTerrainDrawer.searchDistanceBlocks() + " gate="
                + SodiumTerrainDrawer.distanceGateMode() + " faceAll="
                + SodiumTerrainDrawer.faceAll());
        context.takeScreenshot(TestScreenshotOptions.of("97_10_occlusion_on"));
    }

    /**
     * 97_11: a hidden wall. A 33x9x1 stone slab six blocks ahead at eye
     * level (memory: the superflat blind spot - walls, not floors) must cut
     * the sections drawn by at least a fifth; removing it must reveal what
     * it hid through phase B within five owned frames, with the counts
     * recovering.
     */
    private static void legHiddenWall(ClientGameTestContext context, TestSingleplayerContext world,
            GpuLegs legs) {
        TestServerContext server = world.getServer();
        waitForUploadsToSettle(context);
        long[] before = gpuSampleAfterReadback(context, "97_11");
        long abBefore = before[G_A] + before[G_B];
        int cx = (int) Math.floor(legs.x) + legs.fx * 6;
        int cz = (int) Math.floor(legs.z) + legs.fz * 6;
        int ey = (int) Math.floor(legs.y + 1.62);
        int x1 = legs.fx != 0 ? cx : cx - 16;
        int x2 = legs.fx != 0 ? cx : cx + 16;
        int z1 = legs.fz != 0 ? cz : cz - 16;
        int z2 = legs.fz != 0 ? cz : cz + 16;
        String box = x1 + " " + (ey - 4) + " " + z1 + " " + x2 + " " + (ey + 4) + " " + z2;
        server.runCommand("fill " + box + " minecraft:stone");
        context.waitTicks(40);
        waitForUploadsToSettle(context);
        long[] wall = gpuSampleAfterReadback(context, "97_11");
        long abWall = wall[G_A] + wall[G_B];
        if (abWall > abBefore * 4L / 5L) {
            throw new AssertionError("97_11: a 33x9 stone wall six blocks ahead cut the sections "
                    + "drawn from " + abBefore + " to " + abWall + " (A " + wall[G_A] + " + B "
                    + wall[G_B] + "), less than 20%; the box rasters are not rejecting what the "
                    + "wall hides");
        }
        assertNotBroken("97_11");
        context.takeScreenshot(TestScreenshotOptions.of("97_11_wall"));

        long uploadsBefore = SodiumTerrainDrawer.hookUpload();
        long statsAtRemoval = SodiumTerrainDrawer.statsFrames();
        server.runCommand("fill " + box + " minecraft:air");
        if (!pollTicks(context, 400, () -> SodiumTerrainDrawer.hookUpload() > uploadsBefore)) {
            throw new AssertionError("97_11: removing the wall never fired the upload hook");
        }
        long reveal = SodiumTerrainDrawer.statsFrames();
        waitForReadbackOf(context, reveal + 6, "97_11");
        StringBuilder ring = new StringBuilder();
        long hit = phaseBHitIn(statsAtRemoval - 1, reveal + 5, ring);
        if (hit < 0L) {
            throw new AssertionError("97_11: no phase-B draw within five owned frames of the "
                    + "wall's removal (" + ring + "); the sections the wall hid have no fresh "
                    + "stamp and only phase B can bring them back the frame their boxes pass");
        }
        waitForUploadsToSettle(context);
        long[] after = gpuSampleAfterReadback(context, "97_11");
        long abAfter = after[G_A] + after[G_B];
        if (abAfter < abBefore * 4L / 5L) {
            throw new AssertionError("97_11: the counts did not recover after the wall was removed: "
                    + abAfter + " drawn against " + abBefore + " before the wall");
        }
        assertNotBroken("97_11");
        System.out.println("[Meshelium] 97_11: A+B before " + abBefore + " (97_10 " + legs.ab10
                + "), with wall " + abWall + ", after " + abAfter + "; reveal phase B at stats "
                + "frame " + hit + " (" + ring + ")");
        context.takeScreenshot(TestScreenshotOptions.of("97_11_wall_removed"));
    }

    // ------------------------------------------------------------------
    // 97_18: the half-resolution occlusion depth (NEXT (c))
    // ------------------------------------------------------------------

    /**
     * Worst-case {@code D_far} of a NEIGHBOUR region's occupancy AABB, in
     * blocks: a Sodium region is 8x4x8 sections = 128x64x128 blocks, and
     * the farthest corner of the one just across a boundary is
     * {@code sqrt(129^2 + 64^2 + 128^2)}. This is what the region-level
     * near force scales with, and it is ~7x the section figure - which is
     * precisely the arithmetic plan revision 3's section-only pose rule
     * left out.
     */
    private static final double OCC_REGION_DFAR = 192.7;

    /** The same for a neighbour SECTION: {@code sqrt(17^2 + 16^2 + 16^2)}. */
    private static final double OCC_SECTION_DFAR = 28.3;

    /** The rasters' existing 0.1-block box inflation (both shaders). */
    private static final double OCC_BOX_INFLATE = 0.1;

    /**
     * The PRE-DECLARED re-admission budget at the wall pose, binding on the
     * FLAT arm alone. A breach does not fail the leg - the construction is
     * still sound - it fails that arm's DEFAULT-FLIP CANDIDACY, which is
     * the decision the number exists to inform. Declared here, before the
     * first run, because a detector widened where the cost first shows
     * absorbs the cost.
     */
    private static final double OCC_READMISSION_BUDGET = 0.15;

    /** Per-channel tolerance for the picture pair: the 0i control's own residual. */
    private static final int OCC_PICTURE_TOLERANCE = 1;

    /** Printed reference for the picture fraction; above it, "read the PNGs". */
    private static final double OCC_PICTURE_REFERENCE = 0.001;

    /**
     * 97_18: the half-resolution occlusion depth is a SUPERSET of full-res,
     * measured as a SET INCLUSION rather than a sum of counters.
     *
     * <h2>What is being proven, and why a sum could not prove it</h2>
     * Half-res re-admits a handful of edge sections BY CONSTRUCTION (the
     * min-of-window takes the farther neighbour, the coverage inflation
     * fattens every silhouette, and the FLAT arm tests a box at its nearest
     * corner). If it ALSO dropped one hidden section the two would cancel
     * and a counter bound would pass - over a hole that PERSISTS, because
     * phase A never redraws an unstamped section at a static pose. That is
     * the terrain-deleting class, and the only check that sees it is
     * {@code S_full subset of S_flat} on the real stamps.
     *
     * <h2>The arms</h2>
     * FLAT is THE arm: its depth provably never leaves [0, 1], so the
     * superset holds by construction. The slope-bias arm is measured beside
     * it as a COMPARATOR and printed, but it can never fail this gate as a
     * defect and can never flip a default - its superset needs the biased
     * depth to be a defined value, and Vulkan leaves z_f undefined outside
     * the depth range without depth clamping.
     *
     * <h2>Everything this leg refuses to let pass quietly</h2>
     * an empty or mis-keyed fold (each set is ANCHORED to its own frame's
     * gpuSectionsA+B); a mid-flight change of the stamp index mapping (six
     * counters must not move between two samples); a near force that
     * reaches further than the arithmetic says (the forcedNear pair must be
     * EQUAL to what the full-res variant would have forced); a vacuous
     * phase-B-silence check (the CPU skip must be provably disarmed); and a
     * vacuous pose (the bounds are empty when full-res culled nothing, so
     * the whole thing repeats with 97_11's wall standing).
     */
    private static void legHalfResSuperset(ClientGameTestContext context,
            TestSingleplayerContext world, GpuLegs legs) {
        boolean readback = Boolean.getBoolean(TerrainOcclusion.PROPERTY_STAMPS_READBACK);
        if (!readback) {
            System.out.println("[Meshelium] 97_18: visible-set capture off (-D"
                    + TerrainOcclusion.PROPERTY_STAMPS_READBACK + " absent); the SET INCLUSION "
                    + "gate is skipped and every counter, timer, forcedNear and picture check "
                    + "still runs");
        }
        // Part B (F.5) is a CROSS-RUN comparison at the same pinned pose,
        // never an in-run flip: the extension is requested at DEVICE
        // CREATION and the box pipelines bake the mode. All this leg can do
        // is say which side of that comparison the run is on, and refuse to
        // let a run be filed as the part-B leg when the extension is absent.
        boolean consWanted = Boolean.getBoolean(TerrainOcclusion.PROPERTY_CONSERVATIVE_RASTER);
        if (consWanted && !TerrainOcclusion.conservativeRasterActive()) {
            System.out.println("[Meshelium] 97_18: conservative raster: extension ABSENT, part-B "
                    + "leg VACUOUS - this run cannot be the part-B side of the comparison");
        } else {
            System.out.println("[Meshelium] 97_18: conservative raster "
                    + (TerrainOcclusion.conservativeRasterActive() ? "ON (part B)" : "off")
                    + ", extension "
                    + (com.deds.meshelium.MesheliumVulkanState.conservativeRasterizationSupported()
                            ? "present" : "absent"));
        }
        int armBefore = TerrainOcclusion.halfResArm();
        boolean enabledBefore = TerrainOcclusion.halfResEnabled();
        try {
            // ---- step 1: what VisMode 0 draws at this pose (the M of the
            // NOT-DEGENERATE bound). Only rung 0b populates it. ----
            long ownedBefore = SodiumTerrainDrawer.framesOwned();
            context.runOnClient(client -> SodiumTerrainDrawer.setOcclusionEnabled(false));
            waitForRung(context, "0b", ownedBefore, "97_18");
            waitForUploadsToSettle(context);
            long[] pre = gpuSampleAfterReadback(context, "97_18");
            long maskM = pre[G_MASK];
            long ownedBack = SodiumTerrainDrawer.framesOwned();
            context.runOnClient(client -> SodiumTerrainDrawer.setOcclusionEnabled(true));
            waitForRung(context, "0a", ownedBack, "97_18");

            boolean open = halfResPose(context, legs, "open", maskM, readback);

            // ---- the VACUITY GUARD: the bounds are empty when full-res
            // occlusion culled nothing at the pose, so repeat with 97_11's
            // wall standing. ----
            TestServerContext server = world.getServer();
            int cx = (int) Math.floor(legs.x) + legs.fx * 6;
            int cz = (int) Math.floor(legs.z) + legs.fz * 6;
            int ey = (int) Math.floor(legs.y + 1.62);
            int x1 = legs.fx != 0 ? cx : cx - 16;
            int x2 = legs.fx != 0 ? cx : cx + 16;
            int z1 = legs.fz != 0 ? cz : cz - 16;
            int z2 = legs.fz != 0 ? cz : cz + 16;
            String box = x1 + " " + (ey - 4) + " " + z1 + " " + x2 + " " + (ey + 4) + " " + z2;
            boolean wall;
            server.runCommand("fill " + box + " minecraft:stone");
            context.waitTicks(40);
            waitForUploadsToSettle(context);
            try {
                wall = halfResPose(context, legs, "wall", maskM, readback);
            } finally {
                server.runCommand("fill " + box + " minecraft:air");
                // The 97_11 reveal shape: wait for the upload, then for the
                // readback, so the next leg starts from a settled world.
                long uploads = SodiumTerrainDrawer.hookUpload();
                pollTicks(context, 400, () -> SodiumTerrainDrawer.hookUpload() > uploads);
                context.waitTicks(40);
                waitForUploadsToSettle(context);
            }
            if (!open && !wall) {
                throw new AssertionError("97_18: neither pose culled at least 8 sections with "
                        + "full-res occlusion (VisMode-0 mask " + maskM + "), so every bound in "
                        + "this leg is vacuous; the harness world is too empty to prove anything "
                        + "about the lever");
            }
        } finally {
            // The property's own values, whatever the leg was flipping.
            context.runOnClient(client -> {
                TerrainOcclusion.setHalfResEnabled(
                        Boolean.getBoolean(TerrainOcclusion.PROPERTY_HALF_RES));
                TerrainOcclusion.setHalfResArm(TerrainOcclusion.configuredArm());
            });
            System.out.println("[Meshelium] 97_18: restored halfRes="
                    + TerrainOcclusion.halfResEnabled() + " arm=" + TerrainOcclusion.halfResArm()
                    + " (was " + enabledBefore + "/" + armBefore + ")");
        }
    }

    /**
     * One pose of 97_18: off, FLAT, the bias comparator, the invariants and
     * the picture pair.
     *
     * @return true when the pose was NOT vacuous (full-res occlusion culled
     *         at least 8 sections against the VisMode-0 mask)
     */
    private static boolean halfResPose(ClientGameTestContext context, GpuLegs legs, String tag,
            long maskM, boolean readback) {
        // ---- step 2: the lever OFF, and the reference set ----
        context.runOnClient(client -> TerrainOcclusion.setHalfResEnabled(false));
        context.waitTicks(20);
        waitForUploadsToSettle(context);
        long halfAtOff = TerrainOcclusion.halfResFrames();
        long[] off = gpuSampleAfterReadback(context, "97_18 " + tag);
        VisibleSetSample sFull = readback ? sampleSet(context, "97_18 " + tag + " full") : null;
        Path shotFull = context.takeScreenshot(
                TestScreenshotOptions.of("97_18_" + tag + "_full"));
        if (TerrainOcclusion.halfResFrames() != halfAtOff) {
            throw new AssertionError("97_18 " + tag + ": halfResFrames moved from " + halfAtOff
                    + " to " + TerrainOcclusion.halfResFrames() + " with the lever OFF - the "
                    + "off sample is not a full-resolution sample");
        }
        if (MesheliumGpuTimers.live()) {
            long down = MesheliumGpuTimers.lastPassNanosSnapshot()[MesheliumGpuTimers.PASS_DOWNSAMPLE];
            if (down != -1L) {
                throw new AssertionError("97_18 " + tag + ": the downsample pass timed " + down
                        + " ns with the lever OFF; pass D must not be recorded at all");
            }
        }
        long[] mappingAtOff = mappingCounters();
        assertPoseRule(context, "97_18 " + tag);

        // ---- step 3: THE ARM. FLAT is what the proof is about and the
        // only arm a default flip can act on, so it runs first. ----
        long flatBefore = TerrainOcclusion.halfResFlatFrames();
        context.runOnClient(client -> {
            TerrainOcclusion.setHalfResArm(TerrainOcclusion.HALF_RES_ARM_FLAT);
            TerrainOcclusion.setHalfResEnabled(true);
        });
        if (!pollTicks(context, 200,
                () -> TerrainOcclusion.halfResFlatFrames() > flatBefore + 10)) {
            throw new AssertionError("97_18 " + tag + ": the FLAT arm never ran 10 frames after "
                    + "the lever was flipped on (halfResFrames=" + TerrainOcclusion.halfResFrames()
                    + ", flat=" + TerrainOcclusion.halfResFlatFrames() + ", broken="
                    + TerrainOcclusion.halfResError() + ")");
        }
        waitForUploadsToSettle(context);
        long[] onFlat = gpuSampleAfterReadback(context, "97_18 " + tag + " flat");
        VisibleSetSample sFlat = readback ? sampleSet(context, "97_18 " + tag + " flat") : null;
        Path shotFlat = context.takeScreenshot(
                TestScreenshotOptions.of("97_18_" + tag + "_flat"));
        assertHalfResHealthy(context, "97_18 " + tag + " flat");
        assertMappingUnchanged("97_18 " + tag + " flat", mappingAtOff);
        assertForcedNear("97_18 " + tag + " flat", sFlat);

        // ---- step 3b: the COMPARATOR. Measurement only. ----
        long biasBefore = TerrainOcclusion.halfResFrames();
        long flatAtBias = TerrainOcclusion.halfResFlatFrames();
        context.runOnClient(client ->
                TerrainOcclusion.setHalfResArm(TerrainOcclusion.HALF_RES_ARM_BIAS));
        if (!pollTicks(context, 200, () -> TerrainOcclusion.halfResFrames() > biasBefore + 10
                && TerrainOcclusion.halfResFlatFrames() == flatAtBias)) {
            throw new AssertionError("97_18 " + tag + ": the bias comparator never ran 10 frames "
                    + "(halfResFrames=" + TerrainOcclusion.halfResFrames() + " from " + biasBefore
                    + ", flat=" + TerrainOcclusion.halfResFlatFrames() + " expected "
                    + flatAtBias + ")");
        }
        waitForUploadsToSettle(context);
        long[] onBias = gpuSampleAfterReadback(context, "97_18 " + tag + " bias");
        VisibleSetSample sBias = readback ? sampleSet(context, "97_18 " + tag + " bias") : null;
        Path shotBias = context.takeScreenshot(
                TestScreenshotOptions.of("97_18_" + tag + "_bias"));
        assertHalfResHealthy(context, "97_18 " + tag + " bias");
        assertMappingUnchanged("97_18 " + tag + " bias", mappingAtOff);
        assertForcedNear("97_18 " + tag + " bias", sBias);
        System.out.println("[Meshelium] 97_18 " + tag + ": bias arm: MEASUREMENT ONLY - sound "
                + "only where z + o <= 1 (Vulkan: z_f undefined outside [z_min, z_max] without "
                + "depth clamping). It can never flip a default whatever it measures.");
        context.runOnClient(client ->
                TerrainOcclusion.setHalfResArm(TerrainOcclusion.HALF_RES_ARM_FLAT));

        // ---- step 4: the invariants ----
        long offAb = off[G_A] + off[G_B];
        long flatAb = onFlat[G_A] + onFlat[G_B];
        long biasAb = onBias[G_A] + onBias[G_B];

        if (readback) {
            assertSetInclusion("97_18 " + tag, sFull, sFlat, sBias);
        }
        assertStableAndPhaseBSilent(context, "97_18 " + tag);

        // SUPERSET (the sum). Necessary, never sufficient - which is why
        // the set inclusion above exists.
        if (flatAb < offAb) {
            throw new AssertionError("97_18 " + tag + ": FLAT half-res drew FEWER sections ("
                    + flatAb + ") than full-res (" + offAb + "); half-res is a superset by "
                    + "construction, so a shortfall is a section the rasters DROPPED");
        }
        // NOT DEGENERATE: a threshold, never widened. Re-admitting half of
        // what full-res culled is the signature of a depth buffer that is
        // not a downsample of the scene at all (a cleared or never-written
        // attachment reads 0.0 = far and every box passes). That bug costs
        // frame time and never a pixel; only a counter sees it.
        long culled = maskM - offAb;
        long readmitted = flatAb - offAb;
        long slack = Math.max(8L, culled / 2L);
        System.out.println("[Meshelium] 97_18 " + tag + ": mask=" + maskM + " offA+B=" + offAb
                + " flatA+B=" + flatAb + " biasA+B=" + biasAb + " culled=" + culled
                + " readmitted=" + readmitted + " slack=" + slack
                + " ratio=" + (culled > 0 ? (double) readmitted / culled : Double.NaN));
        if (flatAb > offAb + slack) {
            throw new AssertionError("97_18 " + tag + ": FLAT half-res re-admitted " + readmitted
                    + " of the " + culled + " sections full-res culled (bound " + slack
                    + "); at that rate the depth being tested is not a downsample of the scene");
        }

        // ---- step 5: the pictures. A DIAGNOSTIC, never the gate. A
        // section the raster drops contributes no pixel by definition, so
        // any superset renders the same pixels; the counter above is what
        // catches a difference, and the coordinator opens the PNGs. ----
        picturePair("97_18 " + tag + " full-vs-flat", shotFull, shotFlat);
        picturePair("97_18 " + tag + " full-vs-bias", shotFull, shotBias);

        // ---- the re-admission budget, on the sets when we have them ----
        if (readback && sFull != null && sFull.size() > 0) {
            double flatRatio = sFlat.minus(sFull).cardinality() / (double) sFull.size();
            double biasRatio = sBias.minus(sFull).cardinality() / (double) sFull.size();
            System.out.println("[Meshelium] 97_18 " + tag + ": re-admission |S_arm \\ S_full| / "
                    + "|S_full| flat=" + flatRatio + " bias=" + biasRatio + " (budget "
                    + OCC_READMISSION_BUDGET + ", binding on FLAT at the wall pose only)");
            if ("wall".equals(tag) && flatRatio > OCC_READMISSION_BUDGET) {
                System.out.println("[Meshelium] 97_18 " + tag + ": re-admission budget BREACHED: "
                        + "flat " + flatRatio + " > " + OCC_READMISSION_BUDGET + " at the wall "
                        + "pose. The construction is still SOUND - this fails the DEFAULT-FLIP "
                        + "CANDIDACY of the FLAT arm (section G: stays a lever) and belongs in "
                        + "0t. It is deliberately NOT an assertion.");
            }
        }
        return culled >= 8L;
    }

    /**
     * Two folds of the visible set at least five stats frames apart, which
     * must AGREE (the stability half of the proof), each ANCHORED to its
     * own frame's counters.
     *
     * <p>The anchor is what stops the whole gate passing vacuously: the
     * fold compares each stamp word to the frame stamp the raster wrote, so
     * a fold keyed to the wrong stamp, the wrong buffer of the A/B pair, a
     * wrong slot or a byte-offset mistake yields an EMPTY set - and the
     * empty set is a subset of everything.</p>
     */
    private static VisibleSetSample sampleSet(ClientGameTestContext context, String label) {
        waitForReadbackOf(context, SodiumTerrainDrawer.statsFrames(), label);
        VisibleSetSample first = context.computeOnClient(
                client -> SodiumTerrainDrawer.debugVisibleSet());
        if (first == null) {
            throw new AssertionError(label + ": the visible-set readback is armed (-D"
                    + TerrainOcclusion.PROPERTY_STAMPS_READBACK + "=true) but debugVisibleSet() "
                    + "returned null; nothing has been folded, so the superset gate would have "
                    + "no data at all");
        }
        long firstFrame = first.frame();
        if (!pollTicks(context, GPU_TIMEOUT_TICKS,
                () -> SodiumTerrainDrawer.lastReadStatsFrame() >= firstFrame + 5L)) {
            throw new AssertionError(label + ": the readback never advanced five stats frames "
                    + "past the first fold (" + firstFrame + "; lastRead="
                    + SodiumTerrainDrawer.lastReadStatsFrame() + ")");
        }
        VisibleSetSample second = context.computeOnClient(
                client -> SodiumTerrainDrawer.debugVisibleSet());
        if (second == null || second.frame() < firstFrame + 5L) {
            throw new AssertionError(label + ": the second fold is "
                    + (second == null ? "null" : "frame " + second.frame())
                    + ", not at least five frames past " + firstFrame);
        }
        if (!first.bits().equals(second.bits())) {
            BitSet diff = (BitSet) first.bits().clone();
            diff.xor(second.bits());
            StringBuilder first8 = new StringBuilder();
            int shown = 0;
            for (int i = diff.nextSetBit(0); i >= 0 && shown < 8; i = diff.nextSetBit(i + 1)) {
                first8.append(' ').append(i / 256).append(':').append(i % 256);
                shown++;
            }
            throw new AssertionError(label + ": the visible set is NOT STABLE at a static pose - "
                    + "frame " + firstFrame + " had " + first.size() + " stamped, frame "
                    + second.frame() + " had " + second.size() + "; symmetric difference "
                    + diff.cardinality() + ", first eight (mid:slot)" + first8
                    + ". A flickering set means a section loses its stamp and comes back, which "
                    + "is the one-frame hole this lever must not create");
        }
        anchorSet(label, second);
        System.out.println("[Meshelium] " + label + ": |S|=" + second.size() + " stable over "
                + "frames " + firstFrame + ".." + second.frame() + " (forcedNear "
                + second.forcedText() + ")");
        return second;
    }

    /** {@code |S| > 0} and within max(8, 10%) of the frame's own drawn count. */
    private static void anchorSet(String label, VisibleSetSample s) {
        int size = s.size();
        int a = SodiumTerrainDrawer.gpuSectionsAAt(s.frame());
        int b = SodiumTerrainDrawer.gpuPhaseBAt(s.frame());
        if (size <= 0) {
            throw new AssertionError(label + ": the folded visible set is EMPTY at frame "
                    + s.frame() + " (phase A " + a + " + B " + b + " were drawn). An empty set is "
                    + "a subset of everything, so the superset gate would pass vacuously; this is "
                    + "a fold keyed to the wrong stamp, buffer, slot or offset");
        }
        if (a < 0 || b < 0) {
            throw new AssertionError(label + ": the counter ring no longer holds frame "
                    + s.frame() + " (gpuSectionsAAt=" + a + ", gpuPhaseBAt=" + b + "), so the "
                    + "set of " + size + " cannot be anchored to anything");
        }
        // The two quantities are not the same KIND of thing, which the
        // first version of this anchor missed and the first green-ish run
        // caught (2026-09-16: |S| = 48 against a + b = 91, called "nowhere
        // near" when it was exactly right). |S| counts SECTION SLOTS
        // stamped this frame, once each. gpuSectionsAAt / gpuPhaseBAt count
        // task-stage SURVIVORS, and the task stage runs once over the SOLID
        // record table and again over the CUTOUT one, so a section holding
        // both solid and cutout geometry is counted TWICE. The sound
        // relation is therefore an interval, not an equality: every drawn
        // survivor belongs to a stamped section, and each stamped section
        // can supply at most two of them, while a stamped section whose
        // record is empty in both passes supplies none.
        long expect = (long) a + b;
        long ceiling = 2L * size + Math.max(8L, Math.round(0.1 * size));
        System.out.println("[Meshelium] " + label + " anchor: frame=" + s.frame() + " |S|=" + size
                + " gpuA+B=" + expect + " ceiling=" + ceiling + " (a stamped section draws in at "
                + "most both opaque passes; an empty record draws in neither)");
        if (expect > ceiling) {
            throw new AssertionError(label + ": frame " + s.frame() + " drew " + expect
                    + " task-stage survivors, more than the " + ceiling + " that " + size
                    + " stamped sections can account for at two passes each; the fold and the "
                    + "counters are not describing the same frame");
        }
    }

    /**
     * The six counters whose movement would change the stamp index mapping
     * between two samples. The sixth, the upload hook, is the one a
     * three-counter guard missed: a section re-upload moves a mid with
     * recreates, growths and instancesLive all unchanged.
     */
    private static long[] mappingCounters() {
        return new long[] {
                SodiumTerrainDrawer.occlusionRecreates(),
                SodiumTerrainDrawer.growths(),
                SodiumTerrainDrawer.instancesLive(),
                SodiumTerrainDrawer.hookUpload(),
                SodiumTerrainDrawer.midsReleased(),
                SodiumTerrainDrawer.commitRegionsTotal()};
    }

    private static void assertMappingUnchanged(String label, long[] before) {
        long[] now = mappingCounters();
        if (!Arrays.equals(before, now)) {
            throw new AssertionError(label + ": the stamp index mapping moved between the two "
                    + "samples - {recreates, growths, instancesLive, uploads, midsReleased, "
                    + "commitRegions} went " + Arrays.toString(before) + " -> "
                    + Arrays.toString(now) + ". The two sets index different worlds and the "
                    + "comparison is VOID");
        }
    }

    /**
     * The measured form of "the near force and the inflation contributed
     * nothing to the printed re-admission": the widened test must have
     * fired exactly as often as the un-widened, un-inflated test the
     * full-res variant fires, on BOTH rasters.
     */
    private static void assertForcedNear(String label, VisibleSetSample s) {
        if (s == null) {
            return; // no readback in this run: nothing was counted
        }
        System.out.println("[Meshelium] " + label + " forcedNear: " + s.forcedText()
                + " (wide/plain per raster; equality is what makes the printed re-admission "
                + "attributable to the depth arm alone)");
        if (!s.forcedAgrees()) {
            throw new AssertionError(label + ": the WIDENED near force fired where the full-res "
                    + "test would not (" + s.forcedText() + "); with inflateK="
                    + TerrainOcclusion.halfResInflateK() + " nearR="
                    + TerrainOcclusion.halfResNearR() + " either the pose is non-compliant or "
                    + "the widening reaches further than the arithmetic says - and either way "
                    + "the printed re-admission is no longer the depth arm's alone");
        }
    }

    /** The half-res path must be healthy, not quietly standing down. */
    private static void assertHalfResHealthy(ClientGameTestContext context, String label) {
        if (TerrainOcclusion.halfResBroken()) {
            throw new AssertionError(label + ": the half-res path LATCHED ("
                    + TerrainOcclusion.halfResError() + "); a latch is a red run, never a "
                    + "fallback");
        }
        if (TerrainOcclusion.halfResArmSkips() != 0L) {
            throw new AssertionError(label + ": the arm skipped "
                    + TerrainOcclusion.halfResArmSkips() + " frame(s) - a non-canonical or "
                    + "unusable projection. At a pinned gametest pose that is a finding, not a "
                    + "normal frame");
        }
        if (TerrainOcclusion.halfResAllocationFailures() != 0L) {
            throw new AssertionError(label + ": " + TerrainOcclusion.halfResAllocationFailures()
                    + " half-res attachment allocation(s) threw");
        }
        int[] window = context.computeOnClient(client -> new int[] {
                client.getWindow().getWidth(), client.getWindow().getHeight()});
        int wantW = (window[0] + 1) / 2;
        int wantH = (window[1] + 1) / 2;
        if (TerrainOcclusion.halfResWidth() != wantW
                || TerrainOcclusion.halfResHeight() != wantH) {
            throw new AssertionError(label + ": the half target is "
                    + TerrainOcclusion.halfResWidth() + "x" + TerrainOcclusion.halfResHeight()
                    + ", not the CEILING half of the " + window[0] + "x" + window[1]
                    + " render size (" + wantW + "x" + wantH + "). With a floor the last "
                    + "column/row would belong to no half pixel");
        }
        if (MesheliumGpuTimers.live()) {
            long[] t = MesheliumGpuTimers.lastPassNanosSnapshot();
            long down = t[MesheliumGpuTimers.PASS_DOWNSAMPLE];
            long region = t[MesheliumGpuTimers.PASS_REGION_RASTER];
            long section = t[MesheliumGpuTimers.PASS_SECTION_RASTER];
            if (down <= 0L || region <= 0L || section <= 0L) {
                throw new AssertionError(label + ": the GPU timers see downsample=" + down
                        + " regionRaster=" + region + " sectionRaster=" + section
                        + " ns while the lever is on; pass D is not being recorded or the "
                        + "region-raster segment lost its start point");
            }
            // PRINTED, not asserted: 854x480 is the wrong instrument for a
            // timing claim, and the bench matrix is where the figure lives.
            System.out.println("[Meshelium] " + label + " GPU (us): downsample=" + down / 1000.0
                    + " regionRaster=" + region / 1000.0 + " sectionRaster=" + section / 1000.0
                    + " opaqueA=" + t[MesheliumGpuTimers.PASS_OPAQUE_A] / 1000.0
                    + " phaseB=" + t[MesheliumGpuTimers.PASS_PHASE_B] / 1000.0);
        }
        System.out.println("[Meshelium] " + label + ": halfResFrames="
                + TerrainOcclusion.halfResFrames() + " rasterFrames="
                + TerrainOcclusion.halfResRasterFrames() + " flatFrames="
                + TerrainOcclusion.halfResFlatFrames() + " allocations="
                + TerrainOcclusion.halfResAllocations() + " size="
                + TerrainOcclusion.halfResWidth() + "x" + TerrainOcclusion.halfResHeight()
                + " inflateK=" + TerrainOcclusion.halfResInflateK()
                + " nearR=" + TerrainOcclusion.halfResNearR());
    }

    /** The hard gate: {@code S_full subset of S_flat}. */
    private static void assertSetInclusion(String label, VisibleSetSample full,
            VisibleSetSample flat, VisibleSetSample bias) {
        BitSet lostFlat = full.minus(flat);
        BitSet lostBias = full.minus(bias);
        System.out.println("[Meshelium] " + label + " sets: |S_full|=" + full.size()
                + " |S_flat|=" + flat.size() + " |S_flat \\ S_full|="
                + flat.minus(full).cardinality() + " |S_bias|=" + bias.size()
                + " |S_bias \\ S_full|=" + bias.minus(full).cardinality()
                + " |S_full \\ S_flat|=" + lostFlat.cardinality()
                + " |S_full \\ S_bias|=" + lostBias.cardinality()
                + " (frames full=" + full.frame() + " flat=" + flat.frame()
                + " bias=" + bias.frame() + ")");
        if (!lostBias.isEmpty()) {
            // Reported, never a FLAT defect and never a reason to promote or
            // demote anything: the bias arm is already ineligible. This is
            // the SIGNATURE of the undefined-depth case (a near-eye box whose
            // biased depth left [0, 1]).
            System.out.println("[Meshelium] " + label + ": the BIAS comparator dropped "
                    + lostBias.cardinality() + " section(s) full-res kept. That is the "
                    + "undefined-depth signature (Vulkan leaves z_f undefined outside "
                    + "[z_min, z_max] without depth clamping), reported as evidence and nothing "
                    + "more - that arm cannot ship whatever this number is");
        }
        if (!lostFlat.isEmpty()) {
            StringBuilder first8 = new StringBuilder();
            int shown = 0;
            for (int i = lostFlat.nextSetBit(0); i >= 0 && shown < 8;
                    i = lostFlat.nextSetBit(i + 1)) {
                first8.append(' ').append(i / 256).append(':').append(i % 256);
                shown++;
            }
            throw new AssertionError(label + ": the FLAT half-res set is NOT a superset of the "
                    + "full-res set - " + lostFlat.cardinality() + " section(s) were stamped at "
                    + "full resolution and not at half, first eight (mid:slot)" + first8
                    + ". A max in the downsample, a footprint off by one, a dropped row at an "
                    + "odd size, a wrong viewport, a clipped front face, the far-plane seed, or "
                    + "an unsound straddler: each is a section in this set, and each is a hole "
                    + "that PERSISTS at a static pose");
        }
    }

    /**
     * STABLE and PHASE B SILENT over the same ten-frame window, with the
     * CPU skip proved disarmed - because with the skip armed phase B
     * records nothing whatever the stamps do, and "phase B was silent"
     * would be a statement about the skip.
     */
    /**
     * The first stats frame from which phase A holds one value for ten
     * frames, searched inside the settle budget.
     *
     * <p>The caller has just flipped an arm, taken a screenshot and folded a
     * readback; the frames still in flight carry the tail of that, and a
     * scene converging is not a scene flickering. Real flicker cannot hold
     * phase A still for ten consecutive frames, so requiring such a window
     * is the stricter claim as well as the correct one - and it is a phase-A
     * property, so it is computed whether or not phase B is observable.
     */
    private static long convergedStart(long settle, String label) {
        StringBuilder scan = new StringBuilder();
        for (long f = settle; f <= settle + SILENCE_SETTLE; f++) {
            int a0 = SodiumTerrainDrawer.gpuSectionsAAt(f);
            if (a0 < 0) {
                scan.append(" f").append(f).append("=absent");
                continue;
            }
            boolean flat = true;
            for (long g = f + 1; flat && g <= f + 10; g++) {
                flat = SodiumTerrainDrawer.gpuSectionsAAt(g) == a0;
            }
            if (flat) {
                return f;
            }
            scan.append(" f").append(f).append('=').append(a0);
        }
        throw new AssertionError(label + ": phase A never held one value for ten frames in "
                + settle + ".." + (settle + SILENCE_SETTLE) + " (" + scan + "); a section stamped "
                + "on one frame and not the next shows here whatever phase B does");
    }

    /**
     * Frames the leg allows phase B to finish draining after its own arm
     * flip, screenshot and readback before the silence window opens. Ten
     * is far more than the one frame measured, and a scene that is really
     * flickering cannot produce ten silent frames after any of them.
     */
    private static final int SILENCE_SETTLE = 10;

    private static void assertStableAndPhaseBSilent(ClientGameTestContext context, String label) {
        // The window has to START at convergence, not at the instant the
        // check is called. The caller has just flipped the arm, taken a
        // screenshot and folded a readback, and the frames still in flight
        // legitimately carry the tail of that: a section whose stamp source
        // changed is redrawn by phase B exactly once. Measured 2026-09-16:
        // the first frame of the window had 9 phase-B draws and the next ten
        // had none, which is convergence completing, and the first version
        // of this check called it flicker. Real flicker never yields ten
        // consecutive silent frames, so requiring them is the stricter
        // claim as well as the correct one.
        long settle = SodiumTerrainDrawer.statsFrames();
        waitForReadbackOf(context, settle + SILENCE_SETTLE + 10, label);
        // The window start is a property of PHASE A, whether or not phase B
        // is checkable. The first version searched for it only on the branch
        // where the CPU skip is disarmed, so a run with the skip armed - the
        // default, and how the suite is normally invoked - fell back to the
        // raw settle frame and the STABLE loop below judged a scene that was
        // still converging (measured 2026-09-16: 97 -> 104 on the two frames
        // after the arm flip, twice, once on each of two different fixes).
        long start = convergedStart(settle, label);
        if (!SodiumTerrainDrawer.phaseBCpuSkipArmed()) {
            StringBuilder scan = new StringBuilder();
            long quiet = -1L;
            for (long f = settle; f <= settle + SILENCE_SETTLE; f++) {
                int b = SodiumTerrainDrawer.gpuPhaseBAt(f);
                scan.append(" f").append(f).append('=').append(b);
                if (b == 0) {
                    quiet = f;
                    break;
                }
            }
            if (quiet < 0L) {
                throw new AssertionError(label + ": phase B never fell silent in the "
                        + SILENCE_SETTLE + " frames after the pose settled (" + scan + "); a "
                        + "section that loses its stamp drops out of phase A and reappears in "
                        + "phase B the next frame, so a phase B that never quiets is exactly "
                        + "the flicker the lever must not cause");
            }
            StringBuilder ring = new StringBuilder();
            long hit = phaseBHitIn(start, start + 10, ring);
            if (hit >= 0L) {
                throw new AssertionError(label + ": phase B drew at stats frame " + hit
                        + " inside the converged window " + start + ".." + (start + 10) + " ("
                        + ring + "). A section that loses its stamp drops out of phase A and "
                        + "reappears in phase B the next frame, so a phase B that will not stay "
                        + "quiet is exactly the flicker the lever must not cause");
            }
        } else {
            System.out.println("[Meshelium] " + label + ": phase-B silence: SKIPPED, the CPU "
                    + "skip is armed (run with -Dmeshelium.sodium.phaseBCpuSkip=false for the "
                    + "non-vacuous check). SET INCLUSION, STABLE and the sums still ran.");
        }
        int firstA = -1;
        StringBuilder aRing = new StringBuilder();
        for (long f = start; f <= start + 10; f++) {
            int a = SodiumTerrainDrawer.gpuSectionsAAt(f);
            aRing.append(" f").append(f).append('=').append(a);
            if (a < 0) {
                throw new AssertionError(label + ": the counter ring does not hold stats frame "
                        + f + " (" + aRing + "); the STABLE check has no window to read");
            }
            if (firstA < 0) {
                firstA = a;
            } else if (a != firstA) {
                throw new AssertionError(label + ": phase A is not STABLE over ten frames at a "
                        + "static pose (" + aRing + "); a section stamped on one frame and not "
                        + "the next shows here whatever phase B does");
            }
        }
        System.out.println("[Meshelium] " + label + ": stable phase A = " + firstA
                + " over " + start + ".." + (start + 10) + " (settled at " + start + " from "
                + settle + "), phase B silent=" + !SodiumTerrainDrawer.phaseBCpuSkipArmed());
    }

    /**
     * The pose rule, REGION-aware, computed from the LIVE k and R rather
     * than from the plan's arithmetic. The eye must be far enough from
     * every region boundary (multiples of 128 in x/z, 64 in y) and every
     * section boundary (multiples of 16) that the widened near force cannot
     * reach across it.
     *
     * <p>This is the CHEAP half of the claim that the near force costs
     * nothing here. The hard half is {@link #assertForcedNear}, which
     * measures it.</p>
     */
    private static void assertPoseRule(ClientGameTestContext context, String label) {
        double[] eye = context.computeOnClient(client -> {
            net.minecraft.world.phys.Vec3 p = client.gameRenderer.mainCamera().position();
            return new double[] {p.x, p.y, p.z};
        });
        double k = TerrainOcclusion.halfResInflateK();
        double r = TerrainOcclusion.halfResNearR();
        if (k <= 0.0) {
            // Nothing has armed yet in this session: the numbers that bound
            // the force do not exist, so there is nothing to assert. The
            // FLAT pass below arms and this runs with real values.
            System.out.println("[Meshelium] " + label + " pose rule: not evaluated (the arm has "
                    + "not run yet, so inflateK/nearR are 0)");
            return;
        }
        double needRegion = r + k * OCC_REGION_DFAR + OCC_BOX_INFLATE;
        double needSection = r + k * OCC_SECTION_DFAR + OCC_BOX_INFLATE;
        double mSection = Math.min(Math.min(gridMargin(eye[0], 16.0), gridMargin(eye[1], 16.0)),
                gridMargin(eye[2], 16.0));
        double mRegion = Math.min(Math.min(gridMargin(eye[0], 128.0), gridMargin(eye[2], 128.0)),
                gridMargin(eye[1], 64.0));
        System.out.println("[Meshelium] " + label + " pose rule: eye=(" + fmt(eye[0]) + ", "
                + fmt(eye[1]) + ", " + fmt(eye[2]) + ") inflateK=" + k + " nearR=" + r
                + "; section margin " + fmt(mSection) + " >= " + fmt(needSection)
                + "? region margin " + fmt(mRegion) + " >= " + fmt(needRegion)
                + "? (D_far taken as " + OCC_SECTION_DFAR + " / " + OCC_REGION_DFAR
                + " blocks, the worst case for a NEIGHBOUR section and region)");
        if (mSection < needSection || mRegion < needRegion) {
            throw new AssertionError(label + ": the pinned pose is too close to a boundary for "
                    + "the near force to be free - section margin " + fmt(mSection) + " (need "
                    + fmt(needSection) + "), region margin " + fmt(mRegion) + " (need "
                    + fmt(needRegion) + "). x and z are snapped by pinPose, so this is the "
                    + "GROUND HEIGHT: the world put the eye near a boundary in y. That is a "
                    + "message about the harness pose, not a verdict about the lever");
        }
    }

    /** Distance from {@code v} to the nearest multiple of {@code period}, in blocks. */
    private static double gridMargin(double v, double period) {
        double m = v - period * Math.floor(v / period);
        return Math.min(m, period - m);
    }

    /**
     * The picture pair: PRINTED against a reference, never asserted. The
     * claim "identical picture" is structural - a section the raster drops
     * contributes no pixel by definition - so the counter checks above are
     * the gate and this is what tells the coordinator which PNGs to open.
     * The fraction is reported for the upper and lower halves of the frame
     * separately, so a residual that is all sky reads as sky.
     */
    private static void picturePair(String label, Path a, Path b) {
        double[] diff = pixelDifference(a, b, OCC_PICTURE_TOLERANCE);
        System.out.println("[Meshelium] " + label + " picture: " + percentOf(diff[0])
                + " of pixels differ by more than " + OCC_PICTURE_TOLERANCE + "/255 (upper half "
                + percentOf(diff[1]) + ", lower half " + percentOf(diff[2]) + "; reference "
                + percentOf(OCC_PICTURE_REFERENCE) + ") " + a.getFileName() + " vs "
                + b.getFileName()
                + (diff[0] > OCC_PICTURE_REFERENCE ? " -- above the reference: READ THE PNGs" : ""));
    }

    /**
     * {@code {whole, upperHalf, lowerHalf}} fractions of pixels differing
     * by more than {@code tolerance} on some channel.
     *
     * <p>Deliberately a local copy of
     * {@code MesheliumFarFieldVisualTest.pixelDifference} rather than a
     * call: that one is private, has no tolerance, and lives in a test this
     * change has no business editing. Twenty lines of duplication against
     * touching a third gametest file.</p>
     */
    private static double[] pixelDifference(Path a, Path b, int tolerance) {
        byte[] left;
        byte[] right;
        try {
            left = Files.readAllBytes(a);
            right = Files.readAllBytes(b);
        } catch (Exception unreadable) {
            throw new AssertionError("could not read back the screenshots just written to "
                    + a + " and " + b, unreadable);
        }
        if (Arrays.equals(left, right)) {
            return new double[] {0.0, 0.0, 0.0};
        }
        try (NativeImage first = NativeImage.read(left);
                NativeImage second = NativeImage.read(right)) {
            if (first.getWidth() != second.getWidth()
                    || first.getHeight() != second.getHeight()) {
                return new double[] {1.0, 1.0, 1.0}; // different sizes: nothing to compare
            }
            int[] one = first.getPixels();
            int[] two = second.getPixels();
            if (one.length != two.length || one.length == 0) {
                return new double[] {1.0, 1.0, 1.0};
            }
            int width = first.getWidth();
            int half = first.getHeight() / 2;
            int differing = 0;
            int upper = 0;
            int lower = 0;
            for (int i = 0; i < one.length; i++) {
                if (one[i] == two[i]) {
                    continue;
                }
                int p = one[i];
                int q = two[i];
                boolean over = false;
                for (int shift = 0; shift < 32; shift += 8) {
                    int d = ((p >>> shift) & 0xFF) - ((q >>> shift) & 0xFF);
                    if (Math.abs(d) > tolerance) {
                        over = true;
                        break;
                    }
                }
                if (!over) {
                    continue;
                }
                differing++;
                if (width > 0 && i / width < half) {
                    upper++;
                } else {
                    lower++;
                }
            }
            int halfPixels = Math.max(1, width * half);
            return new double[] {
                    differing / (double) one.length,
                    upper / (double) halfPixels,
                    lower / (double) Math.max(1, one.length - halfPixels)};
        } catch (Throwable decodeFailed) {
            System.out.println("[Meshelium] could not decode " + a.getFileName() + " / "
                    + b.getFileName() + " for a pixel diff; reporting them as different");
            return new double[] {1.0, 1.0, 1.0};
        }
    }

    private static String percentOf(double fraction) {
        return String.format(Locale.ROOT, "%.4f%%", fraction * 100.0);
    }

    /**
     * 97_12: a 180-degree camera cut, then a 2000-block teleport. Phase A
     * draws last frame's set with this frame's matrices, the rasters mark
     * the new view, phase B draws it the same frame; so phase B must land
     * within two owned frames of the cut. The far teleport's first frames
     * are photographed for the coordinator: whatever is drawn must be
     * complete.
     */
    private static void legCameraCut(ClientGameTestContext context, TestSingleplayerContext world,
            GpuLegs legs) {
        TestServerContext server = world.getServer();
        waitForUploadsToSettle(context);
        double yaw0 = context.computeOnClient(client -> (double) client.player.getYRot());
        long[] bracket = {SodiumTerrainDrawer.statsFrames(), -1L};
        server.runCommand("execute as @p at @s run tp @s ~ ~ ~ ~180 ~");
        // The cut lands on some frame between two polls; both polls' stats
        // frames bracket it, so the window below is "the cut, plus two".
        boolean turned = pollTicks(context, 200, () -> {
            double yaw = context.computeOnClient(client -> (double) client.player.getYRot());
            long f = SodiumTerrainDrawer.statsFrames();
            double d = Math.abs(((yaw - yaw0) % 360.0 + 540.0) % 360.0 - 180.0);
            if (d < 90.0) {
                bracket[1] = f;
                return true;
            }
            bracket[0] = f;
            return false;
        });
        if (!turned) {
            throw new AssertionError("97_12: the 180-degree teleport never reached the client");
        }
        waitForReadbackOf(context, bracket[1] + 2, "97_12");
        StringBuilder ring = new StringBuilder();
        long hit = phaseBHitIn(bracket[0], bracket[1] + 2, ring);
        if (hit < 0L) {
            throw new AssertionError("97_12: no phase-B draw within two owned frames of the camera "
                    + "cut (stats frames " + bracket[0] + ".." + (bracket[1] + 2) + ":" + ring
                    + "); the temporal two-phase draw is not repainting the cut");
        }
        System.out.println("[Meshelium] 97_12: cut bracketed by stats frames " + bracket[0]
                + ".." + bracket[1] + ", phase B at " + hit + " (" + ring + ")");

        double farX = legs.x + 2000.0;
        int farSurface = surfaceAt(server, (int) Math.floor(farX), (int) Math.floor(legs.z));
        server.runCommand(teleportCommand(farX, farSurface + 1.0, legs.z, legs.yaw, GPU_PITCH));
        if (!pollTicks(context, 200, () -> context.computeOnClient(
                client -> client.player.getX()) > legs.x + 1000.0)) {
            throw new AssertionError("97_12: the 2000-block teleport never reached the client");
        }
        long arrived = SodiumTerrainDrawer.framesOwned();
        context.takeScreenshot(TestScreenshotOptions.of("97_12_cut_f0"));
        pollTicks(context, 200, () -> SodiumTerrainDrawer.framesOwned() >= arrived + 3);
        context.takeScreenshot(TestScreenshotOptions.of("97_12_cut_f3"));
        assertNotBroken("97_12");
        server.runCommand(legs.home);
        HarnessCompat.waitForChunksRender(world);
        context.waitTicks(40);
        waitForUploadsToSettle(context);
    }

    /**
     * 97_14: F3+A. In 26.2 the key calls {@code Minecraft.levelExtractor
     * .allChanged()} (KeyboardHandler, keyDebugReloadChunk); Sodium reloads
     * its manager behind it, so the old renderer instance retires and the
     * new one has to start drawing owned frames again.
     */
    private static void legReloadAll(ClientGameTestContext context, TestSingleplayerContext world,
            GpuLegs legs) {
        long retiredBefore = SodiumTerrainDrawer.instancesRetired();
        context.runOnClient(client -> client.levelExtractor.allChanged());
        context.waitTicks(100);
        HarnessCompat.waitForChunksRender(world);
        long owned = SodiumTerrainDrawer.framesOwned();
        waitForRung(context, "0a", owned, "97_14");
        if (!pollTicks(context, 200, () -> SodiumTerrainDrawer.framesOwned() > owned + 10)) {
            throw new AssertionError("97_14: framesOwned did not resume after F3+A ("
                    + describeGpu(gpuSample(context)) + ")");
        }
        if (SodiumTerrainDrawer.instancesRetired() <= retiredBefore) {
            throw new AssertionError("97_14: F3+A retired no renderer instance (instancesRetired "
                    + retiredBefore + " before and after); either Sodium kept its manager or the "
                    + "new one was not wrapped");
        }
        waitForUploadsToSettle(context);
        assertNotBroken("97_14");
        context.takeScreenshot(TestScreenshotOptions.of("97_14_reload_all"));
    }

    /**
     * 97_15: the horizon pair. Pitch 0, occlusion on, then the list path
     * at the same pose; the coordinator diffs the two (sky and fog rows
     * excepted) to judge the fog/search-distance gate, which is the one
     * gate whose exact tree linkage no counter can settle (contract 6.3).
     * The second run with {@code -Dmeshelium.sodium.distanceGate=render}
     * is documented, not automated.
     */
    private static void legHorizon(ClientGameTestContext context, TestSingleplayerContext world,
            GpuLegs legs) {
        TestServerContext server = world.getServer();
        server.runCommand(teleportCommand(legs.x, legs.y, legs.z, legs.yaw, 0.0));
        context.waitTicks(40);
        waitForUploadsToSettle(context);
        long ownedBefore = SodiumTerrainDrawer.framesOwned();
        waitForRung(context, "0a", ownedBefore, "97_15");
        context.takeScreenshot(TestScreenshotOptions.of("97_15_horizon_occ"));
        String source = String.valueOf(SodiumTerrainDrawer.searchDistanceSource());
        System.out.println("[Meshelium] 97_15: searchDistanceSource=" + source + " D="
                + SodiumTerrainDrawer.searchDistanceBlocks() + " blocks, distanceGateMode="
                + SodiumTerrainDrawer.distanceGateMode() + "; the widened-gate control run is "
                + "-Dmeshelium.sodium.distanceGate=render (not automated)");
        context.runOnClient(client -> SodiumTerrainDrawer.setGpuDrawEnabled(false));
        waitForRungOne(context, "97_15");
        context.waitTicks(10);
        context.takeScreenshot(TestScreenshotOptions.of("97_15_horizon_list"));
        long ownedAt = SodiumTerrainDrawer.framesOwned();
        context.runOnClient(client -> SodiumTerrainDrawer.setGpuDrawEnabled(true));
        waitForRung(context, "0a", ownedAt, "97_15");
        server.runCommand(legs.home);
        context.waitTicks(10);
        if (!"invoker".equals(source)) {
            throw new AssertionError("97_15: searchDistanceSource is \"" + source + "\", not "
                    + "\"invoker\"; the @Invoker target RenderSectionManager.getSearchDistance"
                    + "(FogParameters) was not found and the gate widened to the render "
                    + "distance, so the horizon pair judges the wrong gate");
        }
    }

    /**
     * 97_17: {@code graphRegions=false}. The loop walks every loaded region
     * through the frustum instead of Sodium's list, so it must list at
     * least as many regions, must not crash, and draws more, never fewer.
     */
    private static void legFrustumRegions(ClientGameTestContext context, GpuLegs legs) {
        waitForUploadsToSettle(context);
        long[] graph = gpuSampleAfterReadback(context, "97_17");
        context.runOnClient(client -> SodiumTerrainDrawer.setGraphRegionsForTest(false));
        try {
            context.waitTicks(20);
            waitForUploadsToSettle(context);
            long[] frustum = gpuSampleAfterReadback(context, "97_17");
            if (SodiumTerrainDrawer.graphRegions()) {
                throw new AssertionError("97_17: setGraphRegionsForTest(false) left graphRegions on");
            }
            long floor = Math.max(graph[G_LISTED], legs.listed10);
            if (frustum[G_LISTED] < floor) {
                throw new AssertionError("97_17: with graphRegions off the loop listed "
                        + frustum[G_LISTED] + " regions, fewer than the " + floor
                        + " Sodium's list gave at the same pose; the frustum walk of "
                        + "getLoadedRegions() must be a superset");
            }
            assertNotBroken("97_17");
            System.out.println("[Meshelium] 97_17: graphRegions off " + describeGpu(frustum)
                    + " (graph on " + describeGpu(graph) + ")");
            context.takeScreenshot(TestScreenshotOptions.of("97_17_frustum_regions"));
        } finally {
            context.runOnClient(client -> SodiumTerrainDrawer.setGraphRegionsForTest(true));
            context.waitTicks(10);
        }
    }

    /**
     * 97_20: the per-region geometry bitmap read from Sodium equals the one
     * drained from its section iterator, on every listed region of every
     * frame in the window.
     *
     * <h2>What this is proving, and why reading the bytecode was not enough</h2>
     * <p>{@code ChunkRenderList.add(int)} sets the map bit and appends the
     * byte inside ONE branch, so the two cannot disagree — that is an
     * argument about Sodium's source shape, and it is exactly the kind of
     * argument this project has been wrong about before. This leg turns it
     * into a measurement on a real world: {@code verify} mode fills the
     * mask from the map, drains a second copy, and compares all 256 bits
     * per region. Thousands of comparisons with zero mismatches is the
     * evidence; the bytecode is the reason to expect it.
     *
     * <h2>Why it must also assert that comparisons HAPPENED</h2>
     * <p>Zero mismatches is what a leg that never ran also reports. The
     * count is asserted first, and the disarm reason is checked too: a
     * session that fell back on frame one would otherwise pass with
     * {@code compares == 0} looking like a clean sheet.
     */
    private static void legGeometryMapParity(ClientGameTestContext context) {
        if (!MesheliumSodiumHooks.geometryMapFound()) {
            // Not a failure: the plugin says Sodium moved the field, which
            // is the case this whole mechanism is built to survive. Say so
            // loudly enough that it cannot be mistaken for a pass.
            System.out.println("[Meshelium] 97_20 SKIPPED: the mixin plugin did not find "
                    + MesheliumSodiumHooks.CHUNK_RENDER_LIST + "."
                    + MesheliumSodiumHooks.GEOMETRY_MAP_TARGET + ", so the mirror is draining "
                    + "the section iterator and there is nothing to compare. A Sodium update "
                    + "moved the field.");
            return;
        }
        waitForUploadsToSettle(context);
        String before = SodiumTerrainDrawer.listMapMode();
        context.runOnClient(client ->
                SodiumTerrainDrawer.setListMapModeForTest(SodiumTerrainDrawer.LIST_MAP_VERIFY));
        try {
            context.waitTicks(40);
            waitForUploadsToSettle(context);
            long compares = SodiumTerrainDrawer.listMapCompares();
            long mismatches = SodiumTerrainDrawer.listMapMismatches();
            String disarm = SodiumTerrainDrawer.listMapDisarmReason();
            if (compares < MAP_PARITY_MIN_COMPARES) {
                throw new AssertionError("97_20: only " + compares + " region bitmaps were "
                        + "compared in 40 ticks (wanted at least " + MAP_PARITY_MIN_COMPARES
                        + "). Either rung 0 stopped owning frames or verify mode never armed, "
                        + "and a zero-mismatch verdict from a window that did not run is the "
                        + "failure this assertion exists to catch. disarm=" + disarm);
            }
            if (mismatches != 0L) {
                throw new AssertionError("97_20: " + mismatches + " of " + compares
                        + " listed regions had a geometry bitmap that differed between Sodium's "
                        + "own map and the drained section iterator. They are written in one "
                        + "branch of ChunkRenderList.add, so a difference means the field is not "
                        + "the set we think it is: the map must not be trusted. disarm=" + disarm);
            }
            if (disarm != null) {
                throw new AssertionError("97_20: the map was stood down mid-window: " + disarm);
            }
            assertNotBroken("97_20");
            System.out.println("[Meshelium] 97_20: geometry bitmap parity " + compares
                    + " regions compared, 0 mismatches");
        } finally {
            context.runOnClient(client -> SodiumTerrainDrawer.setListMapModeForTest(before));
            context.waitTicks(10);
        }
    }

    /**
     * Region comparisons 97_20 needs before it may have an opinion. Forty
     * ticks at this harness's frame rate is thousands of frames and tens
     * of regions each; 200 is a floor that a real window clears by orders
     * of magnitude and a window that never armed cannot clear at all.
     */
    private static final long MAP_PARITY_MIN_COMPARES = 200L;

    // ---- NEXT (c1), 2026-09-16: the walking leg's constants ----
    /**
     * Attempted arms a window must contain before it is allowed to have an
     * opinion. "Attempted" is {@code halfResFrames + halfResArmSkips},
     * never {@code halfResFrames} alone: pre-fix a walking window has
     * {@code halfResFrames} delta 0, and a rule phrased against it would be
     * dividing by zero to say something it can say cleanly this way.
     */
    private static final int WALK_MIN_ARMS = 120;

    /** Ticks a window may take to reach {@link #WALK_MIN_ARMS}. */
    private static final int WALK_WINDOW_TIMEOUT_TICKS = 400;

    /**
     * The minimum interpolated bob, held for the WHOLE walk window - half
     * the 0.1 clamp. Sampled every two ticks, not just at the ends: a tree
     * or a cliff stops the player mid-window and the bob decays 0.6x a
     * tick, taking the pre-fix refusal rate down with it.
     */
    private static final float WALK_MIN_BOB = 0.05f;

    /** Horizontal blocks the player must actually cover in the window. */
    private static final double WALK_MIN_MOVE = 3.0;

    /**
     * Armed frames the CONTROL window may have with a non-identity
     * recovered transform. It should be exactly 0 - at a pinned pose
     * {@code ||t||} is exactly {@code 0.0f} and the recovered L exactly I,
     * by the {@code mulPerspectiveAffine} algebra in
     * {@code TerrainOcclusion.halfResArmedTransformedFrames} - and the
     * floor of 2 is for the tail of the settle after {@code runLeg}'s own
     * teleport, not a tolerance on the instrument.
     */
    private static final int WALK_CONTROL_TRANSFORMED_MAX = 2;

    /**
     * The BOUND on vanilla's bob, as a session assertion rather than a
     * window witness: {@code bobView}'s translate is
     * {@code (sin(f pi) b 0.5, -|cos(f pi) b|, 0)} with {@code b} in
     * [0, 0.1], and the x/y extremes are 90 degrees out of phase, so the
     * magnitude is maximised at {@code (0, -0.1, 0)}. A breach is a finding
     * about vanilla, not about us, and this is the only place it would ever
     * surface.
     */
    private static final double WALK_TRANSLATION_BOUND = 0.1 + 1.0e-3;

    /**
     * 97_19: the WALKING leg. Every other 97 leg is a pinned camera, and
     * before c1 the half-res arm refused a walking player on essentially
     * every frame - the bob's vertical translate alone puts
     * {@code m11 * 0.1 = 0.142815} in front of a {@code 1.0e-5} gate at the
     * harness's 854x480 / fov 70, which is 14,281x, and there is no phase
     * of a saturated bob at which the old gate could arm. This leg walks
     * and asserts the refusal RATE, against a stationary control taken in
     * the same run at the same pose with the same lever state.
     *
     * <h2>Why it must flip the lever ITSELF</h2>
     * {@code TerrainOcclusion.armHalfRes} returns 0 at its first statement
     * when {@code !halfResEnabled}, BEFORE {@code arm()} is reached, and
     * that early return counts NOTHING. {@code halfResEnabled} defaults to
     * {@code Boolean.getBoolean(PROPERTY_HALF_RES)} and the canonical suite
     * invocation sets no such property, so without the flip below neither
     * {@code halfResFrames} nor {@code halfResArmSkips} would move,
     * {@code attempted_W} would be 0, and the verdict
     * {@code skips_W <= max(2, attempted_W/50)} would be satisfied by a leg
     * that never armed a single frame. Occlusion being on ({@code runLeg}'s
     * {@code setOcclusionEnabled(legs.occlusionPhase)}) is necessary and
     * nowhere near sufficient. The flip is byte-for-byte 97_18's, and so is
     * the restore.
     *
     * <h2>What it does NOT prove</h2>
     * Not coverage: the superset gate needs a STABLE visible set two folds
     * apart and a walking camera changes the set every frame by
     * construction, so coverage under motion stays 97_18's problem at a
     * static pose. Not a frame-time win either - that is the bench matrix.
     *
     * <p>And it deliberately runs neither {@code assertPoseRule} nor
     * {@code assertForcedNear} inside its walk window. The coverage
     * inflation k is LIVE here ({@code halfResInflateEnabled()} is
     * absent-means-on and this is a player's configuration), so the
     * region-box reach is the ~1.83 blocks {@code pinPose} was rewritten to
     * stay 8 and 64 blocks clear of - and a leg that walks 64 blocks leaves
     * that compliance immediately. The walking window force-stamps
     * neighbour regions for its whole duration, which is a cost invisible
     * to every counter, so its draw counts are NOT comparable with the
     * pinPose-compliant legs. A second walking window under
     * {@code -Dmeshelium.occlusion.diag.halfResInflate=false} would be; it
     * is out of scope for c1 and named here so the choice is visible.</p>
     */
    private static void legWalkingBob(ClientGameTestContext context,
            TestSingleplayerContext world, GpuLegs legs) {
        TestServerContext server = world.getServer();
        // ---- 0. remember everything this leg will move ----
        boolean bobOption = context.computeOnClient(
                client -> client.options.bobView().get());
        boolean enabledBefore = TerrainOcclusion.halfResEnabled();
        int armBefore = TerrainOcclusion.halfResArm();
        // N1, and it is the most dangerous vacuous pass available: one
        // options.txt edit away. With bobView false, GameRenderer.renderLevel
        // ip 106-119 skips bobView entirely and an undamaged player's drawn
        // matrix is canonical, so the leg would pass today for nothing.
        if (!bobOption) {
            throw new AssertionError("97_19 HARNESS: client.options.bobView() is FALSE, so "
                    + "GameRenderer.renderLevel never folds a walking bob into the projection "
                    + "and this leg would pass vacuously. fabric/run/options.txt line 53 must "
                    + "be bobView:true");
        }
        // N7, BEFORE any key is held: KeyboardHandler.keyPress only reaches
        // KeyMapping.set(key,true) on the minecraft.gui.screen() == null
        // path (ip 1044-1055); the screen-open branch at ip 538-560 does
        // set(key,false) and returns, so with a screen up the walk SILENTLY
        // does nothing. options.txt line 78 is pauseOnLostFocus:true, so
        // the desktop being used mid-run is a real way to get one.
        String screen = context.computeOnClient(client -> client.gui.screen() == null
                ? null : client.gui.screen().getClass().getName());
        if (screen != null) {
            throw new AssertionError("97_19 HARNESS: a screen is open before the walk ("
                    + screen + "); the key press would be swallowed and the leg would pass "
                    + "vacuously");
        }
        boolean brokenBefore = TerrainOcclusion.halfResBroken();
        if (brokenBefore) {
            throw new AssertionError("97_19 HARNESS: the half-res path had already latched ("
                    + TerrainOcclusion.halfResError() + ") before this leg ran");
        }

        double[] start = walkPlayerSample(context);
        try {
            // ---- 1. arm the lever (see the javadoc: without this the
            // whole verdict is vacuous) ----
            context.runOnClient(client -> {
                TerrainOcclusion.setHalfResArm(TerrainOcclusion.HALF_RES_ARM_FLAT);
                TerrainOcclusion.setHalfResEnabled(true);
            });
            // Let the bob decay to 0 first, so the control window really is
            // a null control and not the tail of runLeg's own teleport.
            pollTicks(context, 100, () -> walkBob(context) <= 0.0);
            context.waitTicks(10);
            // ---- 2. CONTROL WINDOW S: same pose, same lever, no key ----
            // The maxima are session-wide running maxima, so clear them
            // first: otherwise "the control's largest recovered
            // displacement" is really "the largest since the client
            // booted" and says nothing about this window.
            context.runOnClient(client -> TerrainOcclusion.resetHalfResMaxArmedForTest());
            long[] s0 = walkCounterSample(context);
            int sTicks = walkWaitForArms(context, s0);
            long[] s1 = walkCounterSample(context);
            float controlMaxT = TerrainOcclusion.halfResMaxArmedTranslation();
            float controlMaxO = TerrainOcclusion.halfResMaxArmedOrtho();
            System.out.println("[Meshelium] 97_19 CONTROL maxima: ||t||=" + controlMaxT
                    + " ortho=" + controlMaxO + " over " + (s1[0] - s0[0]) + " armed frames");
            context.runOnClient(client -> TerrainOcclusion.resetHalfResMaxArmedForTest());
            long framesS = s1[0] - s0[0];
            long skipsS = s1[1] - s0[1];
            long attemptedS = framesS + skipsS;
            long transformedS = s1[2] - s0[2];
            long mismatchS = s1[3] - s0[3];

            // ---- 3. the runway ----
            // The 97 world is NORMAL-preset terrain: a walk can meet a
            // tree (the player stops and the bob decays), water (bob 0), a
            // cliff (airborne, and possibly a hurt tilt) or lava. N2/N3/N5/
            // N6 catch every one of those as a HARNESS message, but a leg
            // that is red half the time is worth less than a deterministic
            // one. Precedent: 97_11 and 97_18 already fill and clear slabs
            // in this throwaway world, and this is the LAST leg.
            int px = (int) Math.floor(start[3]);
            int pz = (int) Math.floor(start[5]);
            int feet = (int) Math.floor(walkPlayerSample(context)[4]);
            int ax1 = legs.fx != 0 ? px + legs.fx * -2 : px - 2;
            int ax2 = legs.fx != 0 ? px + legs.fx * 64 : px + 2;
            int az1 = legs.fz != 0 ? pz + legs.fz * -2 : pz - 2;
            int az2 = legs.fz != 0 ? pz + legs.fz * 64 : pz + 2;
            server.runCommand("fill " + Math.min(ax1, ax2) + " " + (feet - 1) + " "
                    + Math.min(az1, az2) + " " + Math.max(ax1, ax2) + " " + (feet - 1) + " "
                    + Math.max(az1, az2) + " minecraft:stone");
            server.runCommand("fill " + Math.min(ax1, ax2) + " " + feet + " "
                    + Math.min(az1, az2) + " " + Math.max(ax1, ax2) + " " + (feet + 3) + " "
                    + Math.max(az1, az2) + " minecraft:air");
            waitForUploadsToSettle(context);

            // ---- 4. the walk ----
            // NEVER holdKeyFor: TestInputImpl's is holdKey/waitTicks/
            // releaseKey with no try/finally, and a key left held leaks
            // into gpuCensus and every test after this class (KEYS_DOWN is
            // a HashSet).
            context.getInput().holdKey(o -> o.keyUp);
            long[] w0;
            long[] w1;
            double[] walkOpen;
            double[] walkClose;
            double bobMin = Double.MAX_VALUE;
            double bobMax = 0.0;
            double hurtMax = 0.0;
            boolean allOnGround = true;
            int wTicks;
            try {
                // The ramp. bob_n = 0.1 * (1 - 0.6^n): 0.064 after two
                // ticks, 0.0922 after five, 0.0994 after ten.
                boolean ramped = pollTicks(context, 100, () -> walkBob(context) >= 0.09);
                walkOpen = walkPlayerSample(context);
                if (!ramped) {
                    throw new AssertionError("97_19 HARNESS: the bob never reached 0.09 in 100 "
                            + "ticks of held keyUp (bob=" + walkOpen[0] + " onGround="
                            + (walkOpen[1] != 0.0) + " hurtTime=" + walkOpen[2] + " moved="
                            + walkHorizontal(start, walkOpen) + " blocks). The player is not "
                            + "walking, so nothing below is a statement about the lever");
                }
                w0 = walkCounterSample(context);
                wTicks = 0;
                while (wTicks < WALK_WINDOW_TIMEOUT_TICKS) {
                    double[] st = walkPlayerSample(context);
                    bobMin = Math.min(bobMin, st[0]);
                    bobMax = Math.max(bobMax, st[0]);
                    hurtMax = Math.max(hurtMax, st[2]);
                    allOnGround &= st[1] != 0.0;
                    long[] now = walkCounterSample(context);
                    if ((now[0] - w0[0]) + (now[1] - w0[1]) >= WALK_MIN_ARMS) {
                        break;
                    }
                    context.waitTicks(2);
                    wTicks += 2;
                }
                w1 = walkCounterSample(context);
                walkClose = walkPlayerSample(context);
            } finally {
                context.getInput().releaseKey(o -> o.keyUp);
                // The release needs a tick: KeyMapping.isDown() is only
                // re-read in KeyboardInput.tick().
                context.waitTicks(2);
            }

            long framesW = w1[0] - w0[0];
            long skipsW = w1[1] - w0[1];
            long attemptedW = framesW + skipsW;
            long transformedW = w1[2] - w0[2];
            long mismatchW = w1[3] - w0[3];
            double moved = walkHorizontal(walkOpen, walkClose);

            // ---- 5. the print, always, red or green ----
            System.out.println("[Meshelium] 97_19 walking bob: control frames=" + framesS
                    + " skips=" + skipsS + " attempted=" + attemptedS + " transformed="
                    + transformedS + " mismatches=+" + mismatchS + " in " + sTicks + " ticks"
                    + " | walk frames=" + framesW + " skips=" + skipsW + " attempted="
                    + attemptedW + " transformed=" + transformedW + " mismatches=+" + mismatchW
                    + " in " + wTicks + " ticks"
                    + " | bob min/max=" + bobMin + "/" + bobMax + " moved=" + moved
                    + " blocks hurtMax=" + hurtMax + " onGround=" + allOnGround
                    + " | maxArmedTranslation=" + TerrainOcclusion.halfResMaxArmedTranslation()
                    + " maxArmedOrtho=" + TerrainOcclusion.halfResMaxArmedOrtho()
                    + " inflateK=" + TerrainOcclusion.halfResInflateK()
                    + " nearR=" + TerrainOcclusion.halfResNearR()
                    + " | skips by reason: " + TerrainOcclusion.halfResArmSkipReasons()
                    + " | NOT a coverage result (the superset gate needs a static pose) and "
                    + "NOT comparable with the pinPose-compliant legs (k is live and a walking "
                    + "eye force-stamps neighbour regions)");

            // ---- 6. non-vacuity, all BEFORE the verdict ----
            String screenAfter = context.computeOnClient(client -> client.gui.screen() == null
                    ? null : client.gui.screen().getClass().getName());
            if (screenAfter != null) {
                throw new AssertionError("97_19 HARNESS: a screen opened during the walk ("
                        + screenAfter + "); the key path died half way and the window is not "
                        + "a walk");
            }
            if (!(bobMin >= WALK_MIN_BOB)) {
                throw new AssertionError("97_19 HARNESS: the bob fell to " + bobMin
                        + " inside the walk window (floor " + WALK_MIN_BOB + "); the player "
                        + "stopped, swam, or left the ground, and the refusal rate falls with "
                        + "the bob");
            }
            if (!(moved >= WALK_MIN_MOVE)) {
                throw new AssertionError("97_19 HARNESS: the player moved " + moved
                        + " blocks horizontally in " + wTicks + " ticks (floor "
                        + WALK_MIN_MOVE + ")");
            }
            if (hurtMax != 0.0) {
                throw new AssertionError("97_19 HARNESS: hurtTime reached " + hurtMax
                        + " during the walk. bobHurt's damage tilt is up to 14 degrees - 22x "
                        + "the bob's 0.632 - so this window refuses (or admits) frames for a "
                        + "reason that is not the bob and nobody could tell the two defects "
                        + "apart");
            }
            if (!allOnGround) {
                throw new AssertionError("97_19 HARNESS: the player left the ground during the "
                        + "walk; AbstractClientPlayer.updateBob forces the bob target to 0.0F "
                        + "off the ground, while swimming and while dead");
            }
            if (TerrainOcclusion.halfResBroken()
                    || TerrainOcclusion.halfResAllocationFailures() != 0L) {
                throw new AssertionError("97_19: the half-res path latched ("
                        + TerrainOcclusion.halfResError() + ") or an attachment allocation "
                        + "threw (" + TerrainOcclusion.halfResAllocationFailures() + ")");
            }
            if (attemptedS < WALK_MIN_ARMS || attemptedW < WALK_MIN_ARMS) {
                throw new AssertionError("97_19 HARNESS: attempted arms control=" + attemptedS
                        + " walk=" + attemptedW + ", floor " + WALK_MIN_ARMS + ". If BOTH are "
                        + "0 the lever never armed at all: armHalfRes returns 0 before arm() "
                        + "when halfResEnabled is false, and that early return counts nothing");
            }
            if (skipsS > Math.max(2L, attemptedS / 50L)) {
                throw new AssertionError("97_19 HARNESS: the STATIONARY control already refused "
                        + skipsS + " of " + attemptedS + " attempted arms ("
                        + TerrainOcclusion.halfResArmSkipReasons() + "). The instrument is "
                        + "dirty before the walk begins, so the walk window can prove nothing "
                        + "and this is not about the bob");
            }
            // The renderer's OWN witness that the bob was in the drawn
            // matrix, window-scoped on both hosts. halfResArmProjectionMismatches
            // cannot do this job: it is standalone-only, and the +/-0.0f
            // algebra of mulPerspectiveAffine can make it increment on
            // every armed frame even at a pinned pose.
            if (transformedW <= 0L) {
                throw new AssertionError("97_19: the walk window armed " + framesW + " frames "
                        + "and NONE of them carried a non-identity recovered transform "
                        + "(transformed delta " + transformedW + "). Either the drawn matrix "
                        + "never had the bob in it - in which case the premise of this leg is "
                        + "wrong and the fix is aimed at nothing - or the lever refused every "
                        + "bobbed frame and only stationary ones armed");
            }
            if (transformedS > WALK_CONTROL_TRANSFORMED_MAX) {
                throw new AssertionError("97_19 HARNESS: the stationary control armed "
                        + transformedS + " frames with a non-identity recovered transform "
                        + "(allowed " + WALK_CONTROL_TRANSFORMED_MAX + "); at a pinned pose "
                        + "||t|| is EXACTLY 0.0f and L exactly I, so the control is not still");
            }
            if (!(TerrainOcclusion.halfResMaxArmedTranslation() <= WALK_TRANSLATION_BOUND)) {
                throw new AssertionError("97_19: the lever armed under a recovered apex "
                        + "displacement of " + TerrainOcclusion.halfResMaxArmedTranslation()
                        + " blocks, above vanilla's proven bob bound of "
                        + WALK_TRANSLATION_BOUND + ". That is a finding about vanilla's bob, "
                        + "not about the gate, and this is the only place it would surface");
            }

            // ---- 7. THE VERDICT ----
            long allowed = Math.max(2L, attemptedW / 50L);
            if (skipsW > allowed) {
                throw new AssertionError("97_19: a WALKING player had " + skipsW + " of "
                        + attemptedW + " attempted arms refused (allowed " + allowed
                        + ") while the stationary control at the same pose had " + skipsS
                        + " of " + attemptedS + ". Reasons: "
                        + TerrainOcclusion.halfResArmSkipReasons() + ". bob min/max " + bobMin
                        + "/" + bobMax + ", moved " + moved + " blocks. The half-res lever is "
                        + "off for the state a player is actually in");
            }
        } finally {
            server.runCommand(legs.home);
            context.waitTicks(2);
            context.runOnClient(client -> {
                TerrainOcclusion.setHalfResEnabled(
                        Boolean.getBoolean(TerrainOcclusion.PROPERTY_HALF_RES));
                TerrainOcclusion.setHalfResArm(TerrainOcclusion.configuredArm());
            });
            System.out.println("[Meshelium] 97_19: restored halfRes="
                    + TerrainOcclusion.halfResEnabled() + " arm=" + TerrainOcclusion.halfResArm()
                    + " (was " + enabledBefore + "/" + armBefore + ") and re-homed for the "
                    + "census, which gets no teleport of its own");
        }
    }

    /**
     * One sample of every half-res counter the walking legs read, taken
     * inside ONE {@code computeOnClient} so they belong to one frame - the
     * file's own idiom.
     *
     * @return {@code {frames, armSkips, transformedFrames,
     *         projectionMismatches, allocationFailures}}
     */
    private static long[] walkCounterSample(ClientGameTestContext context) {
        return context.computeOnClient(client -> new long[] {
                TerrainOcclusion.halfResFrames(),
                TerrainOcclusion.halfResArmSkips(),
                TerrainOcclusion.halfResArmedTransformedFrames(),
                TerrainOcclusion.halfResArmProjectionMismatches(),
                TerrainOcclusion.halfResAllocationFailures()});
    }

    /**
     * {@code {bob, onGround, hurtTime, x, y, z}}. The bob is the LIVE one:
     * {@code ClientAvatarState.getInterpolatedBob(F)} is
     * {@code Mth.lerp(delta, bobO, bob)}, so 1.0f returns {@code bob}
     * exactly. It lives on {@code ClientAvatarState}, not on the player -
     * there is no {@code walkDist}, {@code bob} or {@code isMovingOnGround}
     * on {@code Entity}, {@code LivingEntity} or {@code Player} in 26.2.
     */
    private static double[] walkPlayerSample(ClientGameTestContext context) {
        return context.computeOnClient(client -> new double[] {
                client.player.avatarState().getInterpolatedBob(1.0f),
                client.player.onGround() ? 1.0 : 0.0,
                client.player.hurtTime,
                client.player.getX(),
                client.player.getY(),
                client.player.getZ()});
    }

    private static double walkBob(ClientGameTestContext context) {
        return context.computeOnClient(
                client -> (double) client.player.avatarState().getInterpolatedBob(1.0f));
    }

    private static double walkHorizontal(double[] a, double[] b) {
        double dx = b[3] - a[3];
        double dz = b[5] - a[5];
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Wait until {@link #WALK_MIN_ARMS} arms have been ATTEMPTED since the
     * sample, or the timeout. Polls the counter, never a tick count, so the
     * window is correct at any frame rate.
     *
     * @return the ticks waited
     */
    private static int walkWaitForArms(ClientGameTestContext context, long[] from) {
        int waited = 0;
        while (waited < WALK_WINDOW_TIMEOUT_TICKS) {
            long[] now = walkCounterSample(context);
            if ((now[0] - from[0]) + (now[1] - from[1]) >= WALK_MIN_ARMS) {
                break;
            }
            context.waitTicks(2);
            waited += 2;
        }
        return waited;
    }

    /** The whole run's census, and the latches that must still be clear. */
    private static void gpuCensus(ClientGameTestContext context, GpuLegs legs) {
        long[] s = gpuSample(context);
        System.out.println("[Meshelium] GPU-visibility legs: " + describeGpu(s)
                + "; rung=" + SodiumTerrainDrawer.rung()
                + " declines=" + declinesText()
                + " commitRegionsTotal=" + SodiumTerrainDrawer.commitRegionsTotal()
                + " deadRows=" + SodiumTerrainDrawer.deadRows()
                + " midsReleased=" + SodiumTerrainDrawer.midsReleased()
                + " growths=" + SodiumTerrainDrawer.growths()
                + " bufferKeys=" + SodiumTerrainDrawer.bufferKeys()
                + " keyMismatch=" + SodiumTerrainDrawer.keyMismatch()
                + " midMissing=" + SodiumTerrainDrawer.midMissing()
                + " ringGuardTrips=" + SodiumTerrainDrawer.ringGuardTrips()
                + " instancesLive=" + SodiumTerrainDrawer.instancesLive()
                + " instancesRetired=" + SodiumTerrainDrawer.instancesRetired()
                + " occlusionRecreates=" + SodiumTerrainDrawer.occlusionRecreates()
                + " phaseBCpuSkipArmed=" + SodiumTerrainDrawer.phaseBCpuSkipArmed()
                + " phaseBCpuSkips=" + SodiumTerrainDrawer.phaseBCpuSkips()
                + " searchDistance=" + SodiumTerrainDrawer.searchDistanceSource() + "/"
                + SodiumTerrainDrawer.searchDistanceBlocks()
                + " gate=" + SodiumTerrainDrawer.distanceGateMode()
                + " graphRegions=" + SodiumTerrainDrawer.graphRegions()
                + " faceAll=" + SodiumTerrainDrawer.faceAll()
                + " gpuTimerUnit=" + SodiumTerrainDrawer.gpuTimerUnit()
                + " commitMicros=" + SodiumTerrainDrawer.commitNanos() / 1000.0
                + " loopMicros=" + SodiumTerrainDrawer.loopNanos() / 1000.0
                + " mirrorAudit=" + SodiumTerrainDrawer.mirrorAuditArmed() + "/"
                + SodiumTerrainDrawer.mirrorAuditMismatches() + "/"
                + SodiumTerrainDrawer.mirrorGpuReadbackMismatches()
                // NEXT (c): the half-resolution occlusion depth. The
                // projection-mismatch count is PRINTED, never asserted: on
                // the standalone it is the bobbing/portal count and only a
                // pose that never bobs makes it 0. (NEXT (c1): and it can
                // also read non-zero at a pose that never bobs, because
                // Matrix4f.equals is floatToIntBits and
                // mulPerspectiveAffine writes D.m33 = P.m23 * M.m32 =
                // -0.0f against P.m33 = +0.0f. The window-scoped witness is
                // halfResArmedTransformedFrames.)
                + " halfResFrames=" + TerrainOcclusion.halfResFrames()
                + " halfResRasterFrames=" + TerrainOcclusion.halfResRasterFrames()
                + " halfResFlatFrames=" + TerrainOcclusion.halfResFlatFrames()
                + " halfResAllocations=" + TerrainOcclusion.halfResAllocations()
                + " halfResAllocationFailures=" + TerrainOcclusion.halfResAllocationFailures()
                + " halfResArmSkips=" + TerrainOcclusion.halfResArmSkipReasons()
                + " halfResArmProjectionMismatches="
                + TerrainOcclusion.halfResArmProjectionMismatches()
                + " halfResArmedTransformedFrames="
                + TerrainOcclusion.halfResArmedTransformedFrames()
                + " halfResMaxArmedTranslation="
                + TerrainOcclusion.halfResMaxArmedTranslation()
                + " halfResMaxArmedOrtho=" + TerrainOcclusion.halfResMaxArmedOrtho()
                + " halfResSize=" + TerrainOcclusion.halfResWidth() + "x"
                + TerrainOcclusion.halfResHeight()
                + " halfResBroken=" + TerrainOcclusion.halfResError()
                + " conservativeRaster=" + TerrainOcclusion.conservativeRasterActive()
                + " downsample=" + (MesheliumGpuTimers.live()
                        ? MesheliumGpuTimers.lastPassNanosSnapshot()[
                                MesheliumGpuTimers.PASS_DOWNSAMPLE]
                        : -1L));
        try {
            assertNotBroken("97");
            assertMirrorAuditClean("97");
            // An allocation failure is always a defect. An arm REFUSAL is
            // not, and since c1 (2026-09-16) it means something narrower
            // than it used to. The arm derives its geometry from the
            // PRE-BOB projection and recovers the bob from the drawn one,
            // so an ordinary walking bob and a hurt tilt now ARM; what is
            // left to refuse is plumbing (no stash yet, a stale serial) or
            // a projection that is genuinely not a bobbed perspective
            // (portal, nausea). The earlier reading of this counter - "13
            // refusals, worst off-diagonal 0.305, a real rotation correctly
            // refused" - was taken BEFORE that change and described a gate
            // that refused the everyday case too; 0.305 is 35x the walking
            // bob's proven maximum of 0.00873, so those frames were the
            // narrow class, not the common one. The rate bound stays
            // because plumbing failures are what it catches; the breakdown
            // below names which. "It actually ran" is asserted by 97_10,
            // 97_18 and 97_19, not here.
            long skips = TerrainOcclusion.halfResArmSkips();
            long ran = TerrainOcclusion.halfResFrames();
            long allowed = Math.max(64L, ran / 20L);
            System.out.println("[Meshelium] half-res census: ran=" + ran + " armSkips=" + skips
                    + " (allowed " + allowed + ") allocationFailures="
                    + TerrainOcclusion.halfResAllocationFailures()
                    + " by reason: " + TerrainOcclusion.halfResArmSkipReasons()
                    + " transformedFrames="
                    + TerrainOcclusion.halfResArmedTransformedFrames());
            if (TerrainOcclusion.halfResAllocationFailures() != 0L || skips > allowed) {
                throw new AssertionError("half-res: allocationFailures="
                        + TerrainOcclusion.halfResAllocationFailures() + " (must be 0) armSkips="
                        + skips + " of " + ran + " armed frames (allowed " + allowed
                        + "); a refusal rate this high means the canonical-projection gate is "
                        + "rejecting ordinary frames, not just bobbed ones");
            }
        } catch (AssertionError e) {
            legs.failures.add("census: " + e.getMessage());
        }
        // Leave the levers as the config and properties set them for
        // whatever runs next (the default is both on since D-025).
        context.runOnClient(client -> SodiumTerrainDrawer.applyConfiguredGpuVisibility());
    }

    // ---- 97 helpers ----

    /** {@link MesheliumRegionMirror#censusForTest} on the client thread; null when no mirror is live. */
    private static int[] census(ClientGameTestContext context) {
        return context.computeOnClient(client -> MesheliumRegionMirror.censusForTest());
    }

    /** The per-frame and lagged-GPU counters, read on the client thread so they belong to one frame. */
    private static long[] gpuSample(ClientGameTestContext context) {
        return context.computeOnClient(client -> new long[] {
                SodiumTerrainDrawer.framesOwned(),
                SodiumTerrainDrawer.framesAttempted(),
                SodiumTerrainDrawer.frameDeclinesTotal(),
                SodiumTerrainDrawer.gpuSectionsMask(),
                SodiumTerrainDrawer.gpuSectionsA(),
                SodiumTerrainDrawer.gpuSectionsB(),
                SodiumTerrainDrawer.gpuQuads(),
                SodiumTerrainDrawer.statsFramesRead(),
                SodiumTerrainDrawer.listedRegions(),
                SodiumTerrainDrawer.bufferGroups(),
                SodiumTerrainDrawer.drawCommandsPerFrame(),
                SodiumTerrainDrawer.midsLive(),
                SodiumTerrainDrawer.midsCapacity()});
    }

    /** {@link #gpuSample} once the readback covering the next stats frame has landed. */
    private static long[] gpuSampleAfterReadback(ClientGameTestContext context, String leg) {
        waitForReadbackOf(context, SodiumTerrainDrawer.statsFrames(), leg);
        return gpuSample(context);
    }

    /**
     * Wait until the lagged readback of stats frame {@code statsFrame} of
     * the active instance has been folded in. Every owned frame records
     * one stats CB and folds the frame READBACK_LAG behind it, so the
     * instance's stats-frame count passing {@code statsFrame} plus the
     * lag is the proof; the same index space {@code gpuPhaseBAt} reads.
     */
    private static void waitForReadbackOf(ClientGameTestContext context, long statsFrame,
            String leg) {
        long want = statsFrame + READBACK_MARGIN + 1;
        boolean ok = pollTicks(context, GPU_TIMEOUT_TICKS,
                () -> SodiumTerrainDrawer.statsFrames() >= want
                        || SodiumTerrainDrawer.gpuDrawBroken()
                        || SodiumTerrainDrawer.occlusionBroken());
        assertNotBroken(leg);
        if (!ok) {
            throw new AssertionError(leg + ": the GPU stats readback never reached stats frame "
                    + statsFrame + " (statsFrames=" + SodiumTerrainDrawer.statsFrames()
                    + ", statsFramesRead=" + SodiumTerrainDrawer.statsFramesRead()
                    + ", framesOwned=" + SodiumTerrainDrawer.framesOwned() + ", rung="
                    + SodiumTerrainDrawer.rung() + "); either no owned frame is being drawn or "
                    + "the stats CB is not recorded every owned frame");
        }
    }

    private static String describeGpu(long[] s) {
        return "owned=" + s[G_OWNED] + " attempted=" + s[G_ATTEMPTED] + " declines=" + s[G_DECLINES]
                + " gpuMask=" + s[G_MASK] + " gpuA=" + s[G_A] + " gpuB=" + s[G_B]
                + " gpuQuads=" + s[G_QUADS] + " statsRead=" + s[G_STATS_READ]
                + " listed=" + s[G_LISTED] + " groups=" + s[G_GROUPS] + " draws=" + s[G_DRAWS]
                + " midsLive=" + s[G_MIDS_LIVE] + "/" + s[G_MIDS_CAP];
    }

    /** A decline reason's count (0 when the reason never fired). */
    private static long declines(String reason) {
        return SodiumTerrainDrawer.frameDeclines(reason);
    }

    private static String declinesText() {
        return String.valueOf(SodiumTerrainDrawer.frameDeclines());
    }

    /**
     * The phase-B ring over stats frames {@code [from, to]} of the active
     * instance (the index space of {@code statsFrames()}): the first frame
     * that drew anything, or -1; every value is appended to {@code text}
     * for the message.
     */
    private static long phaseBHitIn(long from, long to, StringBuilder text) {
        long hit = -1L;
        for (long f = Math.max(0L, from); f <= to; f++) {
            int b = SodiumTerrainDrawer.gpuPhaseBAt(f);
            text.append(" f").append(f).append('=').append(b);
            if (b > 0 && hit < 0L) {
                hit = f;
            }
        }
        return hit;
    }

    /** Poll a condition every two ticks for up to {@code maxTicks}; true if it held. */
    private static boolean pollTicks(ClientGameTestContext context, int maxTicks,
            BooleanSupplier done) {
        for (int waited = 0; waited < maxTicks; waited += 2) {
            if (done.getAsBoolean()) {
                return true;
            }
            context.waitTicks(2);
        }
        return done.getAsBoolean();
    }

    /** Wait for rung {@code want} to draw an owned frame past {@code ownedBefore}, or explain why not. */
    private static void waitForRung(ClientGameTestContext context, String want, long ownedBefore,
            String leg) {
        boolean ok = pollTicks(context, GPU_TIMEOUT_TICKS,
                () -> (want.equals(SodiumTerrainDrawer.rung())
                        && SodiumTerrainDrawer.framesOwned() > ownedBefore)
                        || SodiumTerrainDrawer.gpuDrawBroken()
                        || SodiumTerrainDrawer.occlusionBroken()
                        || SodiumTerrainDrawer.broken());
        assertNotBroken(leg);
        if (!ok) {
            throw new AssertionError(leg + ": rung " + want + " never drew an owned frame within "
                    + GPU_TIMEOUT_TICKS + " ticks (rung=" + SodiumTerrainDrawer.rung()
                    + ", framesOwned=" + SodiumTerrainDrawer.framesOwned() + " from " + ownedBefore
                    + ", framesAttempted=" + SodiumTerrainDrawer.framesAttempted()
                    + ", declines=" + declinesText() + ", gpuDrawEnabled="
                    + SodiumTerrainDrawer.gpuDrawEnabled() + ", occlusionEnabled="
                    + SodiumTerrainDrawer.occlusionEnabled() + ", hooks found=0x"
                    + Integer.toHexString(MesheliumSodiumHooks.hooksFound()) + " missing=["
                    + MesheliumSodiumHooks.describeMissing() + "] disarmed="
                    + MesheliumSodiumHooks.disarmReason() + ")");
        }
    }

    /** After {@code setGpuDrawEnabled(false)}: the next frame must be the list path's. */
    private static void waitForRungOne(ClientGameTestContext context, String leg) {
        boolean ok = pollTicks(context, 200, () -> "1".equals(SodiumTerrainDrawer.rung())
                || SodiumTerrainDrawer.broken());
        assertNotBroken(leg);
        if (!ok) {
            throw new AssertionError(leg + ": setGpuDrawEnabled(false) did not send the next frame "
                    + "to the list path: rung=" + SodiumTerrainDrawer.rung());
        }
    }

    private static void assertNotBroken(String leg) {
        if (SodiumTerrainDrawer.gpuDrawBroken()) {
            throw new AssertionError(leg + ": the GPU draw latched broken (rung 1 for the rest of "
                    + "the session): " + SodiumTerrainDrawer.gpuDrawError());
        }
        if (SodiumTerrainDrawer.occlusionBroken()) {
            throw new AssertionError(leg + ": occlusion latched broken (rung 0b for the rest of "
                    + "the session): " + SodiumTerrainDrawer.occlusionError());
        }
        if (SodiumTerrainDrawer.broken()) {
            throw new AssertionError(leg + ": the list path latched broken: "
                    + SodiumTerrainDrawer.error());
        }
    }

    /**
     * I1 as a number: with the audit lever armed, every live region's two
     * pass blocks are re-read from Sodium's heap each owned frame and
     * compared with the bytes last committed, and once a session one
     * region's GPU block is read back and compared too. Zero both, or the
     * GPU is drawing from records that are not Sodium's.
     */
    private static void assertMirrorAuditClean(String leg) {
        long cpu = SodiumTerrainDrawer.mirrorAuditMismatches();
        long gpu = SodiumTerrainDrawer.mirrorGpuReadbackMismatches();
        if (cpu != 0L || gpu != 0L) {
            throw new AssertionError(leg + ": the mirror audit found " + cpu + " CPU-shadow and "
                    + gpu + " GPU-readback mismatches; a record the task stage reads differs from "
                    + "Sodium's heap, which draws WRONG geometry after a defrag, not missing "
                    + "geometry (I1)");
        }
        if (!SodiumTerrainDrawer.mirrorAuditArmed()) {
            System.out.println("[Meshelium] " + leg + ": mirror audit not armed (-D"
                    + SodiumTerrainDrawer.PROPERTY_MIRROR_AUDIT + "=true); the zero-mismatch "
                    + "claim is vacuous this run");
        }
    }

    private static int renderDistance(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.options.renderDistance().get());
    }

    private static void setRenderDistance(ClientGameTestContext context, int rd) {
        context.runOnClient(client -> {
            client.options.renderDistance().set(rd);
            client.options.save();
        });
    }

    /** The surface height at a column, generating the chunk if it has to. */
    private static int surfaceAt(TestServerContext server, int x, int z) {
        return server.computeOnServer(mc -> mc.overworld().getHeight(
                Heightmap.Types.MOTION_BLOCKING, x, z));
    }

    /**
     * Stage 1 of the port: Meshelium's mesh shaders really did draw
     * Sodium's opaque terrain, and the world really does look like a
     * world.
     *
     * <h2>Why the counters come first and the pictures come second</h2>
     * <p>The counters exist to stop a vacuous pass. A screenshot of a
     * correct world proves nothing about WHO drew it — Sodium's own
     * renderer is right there and would produce the same picture — so
     * without {@code framesDrawn} rising, a green run here would only be
     * saying that Sodium works.
     *
     * <p>But the counters cannot replace the pictures either, and this
     * project has the scar to prove it: a four-minute run once exited
     * green with every counter healthy on a world full of black holes.
     * {@code framesDrawn} says a draw was recorded, not that anything
     * legible came out of it. Both, or neither is worth having.
     *
     * <h2>Why it spins</h2>
     * <p>A section is only in Sodium's visible list once it has been built
     * and passed the cull, and what is behind the camera at spawn is
     * neither. One screenshot forward would leave most of the port
     * untested; a full turn puts every direction through the same path and
     * the eight frames together are the actual evidence.
     */
    private static void assertMesheliumDrawsSodiumsTerrain(ClientGameTestContext context) {
        if ("false".equalsIgnoreCase(System.getProperty("meshelium.sodium.adapter"))) {
            context.runOnClient(client -> {
                if (SodiumTerrainDrawer.framesDrawn() != 0L) {
                    throw new AssertionError(
                            "the adapter was declined with -Dmeshelium.sodium.adapter=false, but "
                                    + "Meshelium drew " + SodiumTerrainDrawer.framesDrawn()
                                    + " passes anyway - the kill switch does not kill");
                }
            });
            return;
        }

        // waitForChunksRender returns once the world is drawable, which can
        // be a frame or two before the first opaque pass with anything in
        // it. Wait for the draw rather than asserting on the first tick.
        context.waitFor(client -> SodiumTerrainDrawer.framesDrawn() > 0L
                || SodiumTerrainDrawer.broken());

        context.runOnClient(client -> {
            if (SodiumTerrainDrawer.broken()) {
                throw new AssertionError(
                        "Meshelium's mesh-shader draw of Sodium's terrain failed and fell back to "
                                + "Sodium: " + SodiumTerrainDrawer.error());
            }
            // Cumulative, not per-pass. A superflat world has solid
            // geometry and no cutout geometry, and the cutout pass runs
            // second, so a per-pass counter reads zero at the end of a
            // perfectly healthy frame.
            if (SodiumTerrainDrawer.totalSectionsDrawn() <= 0L) {
                throw new AssertionError(
                        "Meshelium recorded " + SodiumTerrainDrawer.framesDrawn() + " opaque "
                                + "passes out of Sodium's lists but has drawn 0 sections in "
                                + "total. Either the enumeration is finding nothing or every "
                                + "section is reading a zero vertex count - the world on screen "
                                + "is Sodium's work, not Meshelium's.");
            }
            if (SodiumTerrainDrawer.lastRegionsDrawn() <= 0) {
                throw new AssertionError(
                        "sections were drawn but no region was counted, so the draw list's "
                                + "bookkeeping disagrees with itself");
            }
        });

        for (int step = 0; step < 8; step++) {
            final int shot = step;
            context.runOnClient(client -> {
                if (client.player != null) {
                    client.player.setYRot(client.player.getYRot() + 45.0f);
                    client.player.yRotO = client.player.getYRot();
                }
            });
            // Long enough for Sodium to build and admit what just came into
            // view; a shot taken mid-build photographs a hole that is not a
            // bug.
            context.waitTicks(40);
            context.takeScreenshot(TestScreenshotOptions.of(
                    String.format("92_meshelium_draws_sodium_%02d_yaw%03d", shot, shot * 45)));
        }

        context.runOnClient(client -> {
            if (SodiumTerrainDrawer.broken()) {
                throw new AssertionError(
                        "Meshelium's draw failed part way through the turn, so some of those "
                                + "screenshots are Sodium's: " + SodiumTerrainDrawer.error());
            }
            System.out.println("[Meshelium] Sodium stage 1: " + SodiumTerrainDrawer.framesDrawn()
                    + " opaque passes drawn by mesh shaders, "
                    + SodiumTerrainDrawer.totalSectionsDrawn() + " sections in total, "
                    + SodiumTerrainDrawer.lastSectionsDrawn() + " across "
                    + SodiumTerrainDrawer.lastRegionsDrawn() + " regions in the last one.");
        });
    }

    /**
     * Sodium's OWN video screen must accept the widened value.
     *
     * <p>The leg below proves the vanilla option's range is widened under
     * Sodium. It proves nothing about Sodium's screen, which replaces
     * vanilla's Video Settings (its mixin sits at the head of the
     * "Video Settings..." button's lambda in vanilla's Options screen),
     * has its own literal 2..32 slider range, and — the part that hurt —
     * validates the stored value against THAT range every time the screen
     * opens and writes its default of 12 back into the vanilla option on
     * failure. So this leg walks the player's route: vanilla Options
     * screen (the in-world one, as the pause menu builds it), click Video
     * Settings, land on Sodium's screen, and check the value is still what
     * it was. With Sodium's stock range this leg fails at 12; with
     * Meshelium's overlay on Sodium's option (D-019) it holds. The
     * screenshot shows the slider itself.
     */
    private static void assertSodiumSliderAcceptsTheWidenedRange(ClientGameTestContext context) {
        int[] previous = new int[1];
        context.runOnClient(client -> {
            previous[0] = client.options.renderDistance().get();
            client.options.renderDistance().set(64);
        });
        context.runOnClient(client -> client.gui.setScreen(
                HarnessCompat.optionsScreen(client.gui.screen(), client.options, true)));
        context.waitTicks(2);
        // The pause-menu shape (inWorld = true) of the "Meshelium..." row.
        // assertOptionsMenuButtonUnderSodium pins the title-screen shape;
        // inWorld only selects the header's second button and the grid path
        // is shared (OptionsScreen.init ip 122-385), so this one check pins
        // both constructor shapes for free, with no extra screenshot.
        context.runOnClient(client -> {
            if (findButton(client.gui.screen(), "meshelium.options.menu") == null) {
                throw new AssertionError("no 'Meshelium...' row on the in-world Options screen "
                        + "(the pause-menu shape, inWorld=true) with Sodium installed, although "
                        + "the title-screen shape has one; the two share the grid path, so "
                        + "OptionsScreenMixin is not what changed here");
            }
        });
        context.clickScreenButton("options.video"); // Sodium substitutes the screen this opens
        context.waitTicks(5);
        int[] got = new int[1];
        String[] screen = new String[1];
        context.runOnClient(client -> {
            got[0] = client.options.renderDistance().get();
            screen[0] = client.gui.screen() == null ? "null" : client.gui.screen().getClass().getName();
        });
        context.takeScreenshot(TestScreenshotOptions.of("95_sodium_video_settings_render_distance"));
        context.runOnClient(client -> client.gui.setScreen(null));
        context.waitTicks(2);
        context.runOnClient(client -> client.options.renderDistance().set(previous[0]));
        context.waitTicks(2);
        if (!screen[0].startsWith("net.caffeinemc")) {
            throw new AssertionError("Expected Sodium's video settings screen behind vanilla's Video "
                    + "Settings button, got " + screen[0] + "; whatever the value did, this leg "
                    + "proved nothing about Sodium's slider.");
        }
        if (got[0] != 64) {
            throw new AssertionError("Sodium's video screen changed render distance from 64 to "
                    + got[0] + ". Sodium validates the stored value against its own slider range "
                    + "and writes its default back on failure; Meshelium's range overlay on "
                    + "Sodium's option is not in effect.");
        }
    }

    /**
     * Meshelium's settings and its widened render-distance slider have to
     * survive Sodium being installed.
     *
     * <p>Both are things Sodium can plausibly take away without anyone
     * noticing, because Sodium replaces large parts of the options UI.
     * Meshelium reaches its own screen by adding a button to vanilla's
     * Video Settings ({@code VideoSettingsScreenMixin}); Sodium substitutes
     * that screen, so the button has nowhere to attach and, until the
     * "Meshelium..." row on vanilla's Options screen
     * ({@code OptionsScreenMixin}, owner directive 2026-09-13), the
     * {@code /meshelium} command silently became the only route in. That
     * row is the menu route now, and {@link #assertOptionsMenuButtonUnderSodium}
     * pins it on every Sodium form.
     *
     * <p>The render-distance range is the other one. Vanilla stops at 32
     * and Meshelium widens the option past it — a feature that has nothing
     * to do with who draws the terrain, so standing the renderer down is
     * no reason to take it away. It was gated on the renderer being live
     * until 2026-09-04, and the symptom was quiet: a bench asking for 64
     * held 12 while reporting 64.
     *
     * <p>Screenshots rather than assertions for the Video Settings layout
     * itself: whether a button is present there is a claim about pixels
     * once another mod is rearranging the screen. The Options-screen row is
     * different, because vanilla still owns that screen: its presence,
     * width, position and press-through are asserted, not left to pixels.
     */
    private static void assertMesheliumStillReachableUnderSodium(ClientGameTestContext context) {
        int[] max = new int[1];
        context.runOnClient(client -> {
            // The widened range is the assertion; vanilla's ceiling is 32,
            // so anything above it can only be Meshelium's doing.
            max[0] = client.options.renderDistance().values().validateValue(64).isPresent()
                    ? 64 : -1;
        });
        if (max[0] != 64) {
            throw new AssertionError(
                    "With Sodium installed, the render-distance option refused 64, so Meshelium's "
                            + "widened range is not being applied. Vanilla's own ceiling is 32 and "
                            + "Sodium is perfectly able to draw further, so this is Meshelium "
                            + "taking a working feature away from the player for no reason.");
        }

        context.runOnClient(client -> client.gui.setScreen(
                new VideoSettingsScreen(client.gui.screen(), client, client.options)));
        context.waitTicks(2);
        context.takeScreenshot(TestScreenshotOptions.of("91_meshelium_video_settings_under_sodium"));

        assertStatusHeaderTellsTheTruth(context);

        context.runOnClient(client -> client.gui.setScreen(null));
        context.waitTicks(2);

        assertOptionsMenuButtonUnderSodium(context);
    }

    /**
     * The "Meshelium..." row at the bottom of vanilla's Options screen,
     * the route the owner asked for when Sodium is on (2026-09-13:
     * "maybe just at the bottom of the options menu??"), added by
     * {@code OptionsScreenMixin} only while Sodium is installed.
     *
     * <p>Runs on every Sodium form (Vulkan, OpenGL, and the declined
     * adapter), starting from the TitleScreen the caller leaves behind.
     * The predicate is pinned from both sides: this leg proves the row is
     * there with Sodium, and {@code MesheliumBootSmokeTest} proves it is
     * absent without; either alone would pass with a mixin that never
     * applies or one that always applies.
     *
     * <p>What is asserted and why each piece is separate. The widget walk
     * sees vanilla's own grid first (a control: an absence below is then a
     * real absence, and the row displaced nothing). Presence, then
     * {@code active} and {@code visible}, then geometry: flush with the
     * grid's left and right edges (full width like the other rows), below
     * every grid button, and above Done (inside the content area, on
     * screen). Fabric's click helper walks {@code Screen.renderables} and
     * presses the first Button whose translated label matches, via
     * {@code onPress}, without checking active or visible (the 95 leg
     * already relies on this for the grid), so the press-through below
     * proves the handler fires and the screen changes, not that a player
     * could click the button; visibility is asserted separately rather
     * than inferred from the press. Exactly one match of the label, before
     * and after the round trip: Done on the Meshelium screen must return
     * the SAME Options instance (parent wiring right, no
     * VideoSettingsScreen rebuild branch fired), and 26.2's
     * {@code Screen.init(int,int)} only repositions an initialized screen,
     * so a second row after re-entry would mean {@code init()} re-ran.
     */
    private static void assertOptionsMenuButtonUnderSodium(ClientGameTestContext context) {
        String menuKey = "meshelium.options.menu";
        String tooltipKey = "meshelium.options.tooltip.menu";
        OptionsScreen[] menu = new OptionsScreen[1];
        context.runOnClient(client -> {
            // Key presence first. Both walkers below (findButton here and
            // fabric's clickScreenButton) compare TRANSLATED strings, and a
            // missing key degrades to the raw key on both sides, so a typo'd
            // or absent en_us.json entry would still match, still click,
            // and leave both legs green while the player reads a raw key.
            if (!Language.getInstance().has(menuKey) || !Language.getInstance().has(tooltipKey)) {
                throw new AssertionError("en_us.json is missing the Options-screen row's key ("
                        + menuKey + " or " + tooltipKey + "); the walkers compare translated "
                        + "strings and would match the raw key, so this has to be pinned "
                        + "before the button is looked for");
            }
            // The TitleScreen shape (inWorld = false, as TitleScreen builds
            // it); assertSodiumSliderAcceptsTheWidenedRange pins the
            // pause-menu shape.
            menu[0] = HarnessCompat.optionsScreen(client.gui.screen(), client.options, false);
            client.gui.setScreen(menu[0]);
        });
        context.waitForScreen(OptionsScreen.class);
        context.waitTicks(2);

        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            if (!(screen instanceof OptionsScreen)) {
                throw new AssertionError("expected vanilla's Options screen, got " + screen);
            }
            // Control: the walk sees vanilla's own grid (video: left column
            // row 1; sounds: right column row 0, per javap of OptionsScreen
            // .init's RowHelper order) and the footer, so an absence below
            // is a real absence and the row displaced none of them.
            Button video = findButton(screen, "options.video");
            Button sounds = findButton(screen, "options.sounds");
            Button done = findButton(screen, "gui.done");
            if (video == null || sounds == null || done == null) {
                throw new AssertionError("the widget walk did not see vanilla's own Options grid "
                        + "(video=" + video + ", sounds=" + sounds + ", done=" + done + "), so "
                        + "nothing about the Meshelium row could be judged; either the walk is "
                        + "wrong or the row displaced vanilla's buttons");
            }
            Button ours = findButton(screen, menuKey);
            if (ours == null) {
                throw new AssertionError("no 'Meshelium...' button on vanilla's Options screen "
                        + "with Sodium installed; Sodium replaces the Video Settings supplier, so "
                        + "this button is the only menu route in");
            }
            // Fabric's click helper presses through Button.onPress without
            // checking either flag, so the press-through further down would
            // pass on a button a player could not click. This is the only
            // interactability pin; do not drop it as redundant.
            if (!ours.active || !ours.visible) {
                throw new AssertionError("the 'Meshelium...' row exists but is not clickable "
                        + "(active=" + ours.active + ", visible=" + ours.visible + ")");
            }
            // Full width like the other rows: flush with the left column's
            // left edge and the right column's right edge. The wrapper
            // column has no slack (316 both rows), so these are exact.
            if (ours.getX() != video.getX() || ours.getRight() != sounds.getRight()) {
                throw new AssertionError("the 'Meshelium...' row is not flush with the grid: "
                        + "ours x=" + ours.getX() + " right=" + ours.getRight()
                        + ", grid x=" + video.getX() + " right=" + sounds.getRight());
            }
            // Below the grid: under the Video Settings row, and under every
            // other content button (the whole grid, whatever vanilla adds
            // to it later).
            if (ours.getY() < video.getBottom()) {
                throw new AssertionError("the 'Meshelium...' row (y=" + ours.getY()
                        + ") is not below the grid's Video Settings row (bottom=" + video.getBottom() + ")");
            }
            int gridBottom = Integer.MIN_VALUE;
            List<AbstractWidget> widgets = new ArrayList<>();
            collectWidgets(screen, widgets);
            for (AbstractWidget widget : widgets) {
                if (widget instanceof Button && widget != ours && widget != done) {
                    gridBottom = Math.max(gridBottom, widget.getBottom());
                }
            }
            if (ours.getY() < gridBottom) {
                throw new AssertionError("the 'Meshelium...' row (y=" + ours.getY()
                        + ") overlaps the grid (lowest grid button bottom=" + gridBottom + ")");
            }
            // Inside the content area: above Done, which the footer pins on
            // screen. At the harness's 240 px logical floor this is the
            // tightest case (ours bottom 207 against Done y 213).
            if (ours.getBottom() > done.getY()) {
                throw new AssertionError("the 'Meshelium...' row (bottom=" + ours.getBottom()
                        + ") runs into the footer (Done y=" + done.getY() + ")");
            }
            int matches = countButtons(screen, menuKey);
            if (matches != 1) {
                throw new AssertionError("expected exactly one 'Meshelium...' row on the Options "
                        + "screen, found " + matches);
            }
        });
        context.takeScreenshot(TestScreenshotOptions.of("91_meshelium_options_menu_under_sodium"));

        // Press-through: the handler fires and the Meshelium screen opens
        // with this Options screen as its parent. Ten ticks for the settled
        // header, as assertStatusHeaderTellsTheTruth does; the banner and
        // status wording are that leg's business, not re-asserted here.
        context.clickScreenButton(menuKey);
        context.waitForScreen(MesheliumOptionsScreen.class);
        context.waitTicks(10);
        context.takeScreenshot(TestScreenshotOptions.of(
                "94_meshelium_options_from_options_menu_under_sodium"));
        context.runOnClient(client -> {
            if (!(client.gui.screen() instanceof MesheliumOptionsScreen)) {
                throw new AssertionError("the 'Meshelium...' row did not open the Meshelium "
                        + "settings screen, got " + client.gui.screen());
            }
        });

        // Done returns to the SAME Options instance, and the row is still
        // there exactly once (re-entry repositions, it does not re-init).
        context.clickScreenButton("gui.done");
        context.waitForScreen(OptionsScreen.class);
        context.runOnClient(client -> {
            if (client.gui.screen() != menu[0]) {
                throw new AssertionError("Done on the Meshelium screen must return the SAME "
                        + "Options screen it was opened from, got " + client.gui.screen()
                        + "; either the parent wiring is wrong or onClose took the "
                        + "VideoSettingsScreen rebuild branch for a plain Options parent");
            }
            int matches = countButtons(client.gui.screen(), menuKey);
            if (matches != 1) {
                throw new AssertionError("after returning from the Meshelium screen the Options "
                        + "screen has " + matches + " 'Meshelium...' rows; init() re-ran on an "
                        + "initialized screen, which 26.2's Screen.init(int,int) guard forbids");
            }
        });
        context.clickScreenButton("gui.done");
        context.waitForScreen(TitleScreen.class);
    }

    /**
     * The status header must not say Meshelium is off while it is drawing
     * the world.
     *
     * <p>It did, for one release. The gate state under Sodium is
     * {@code SODIUM_PRESENT}, and that state is genuinely about the
     * VANILLA path — which really is down, because Sodium owns the
     * geometry. The screen read it as "Meshelium is not running" and said
     * so in red while the mesh-shader adapter was drawing every opaque
     * pass. Two true facts, one of them reported as the other.
     *
     * <p>The failure mode here is not a crash and not a wrong pixel; it is
     * a player being told the mod does nothing, uninstalling it, and being
     * right to. Worth an assertion.
     */
    private static void assertStatusHeaderTellsTheTruth(ClientGameTestContext context) {
        boolean declined = "false".equalsIgnoreCase(
                System.getProperty("meshelium.sodium.adapter"));
        // Off is right when the adapter is declined AND when the backend
        // cannot carry it (the OpenGL run); "Working with Sodium" is only
        // owed on a mesh-shader Vulkan device with the adapter armed.
        boolean[] expectOffHolder = new boolean[1];
        context.runOnClient(client -> expectOffHolder[0] = declined
                || MesheliumGate.backend() != MesheliumGate.State.VULKAN_MESH_SHADERS);
        boolean expectOff = expectOffHolder[0];
        context.runOnClient(client ->
                client.gui.setScreen(new MesheliumOptionsScreen(client.gui.screen())));
        context.waitForScreen(MesheliumOptionsScreen.class);
        // Ten ticks, not two, so the screenshot shows the settled header.
        // An earlier note here blamed header timing for a two-tick shot
        // that read "still checking the graphics backend": that text was
        // the init-built BANNER, which had no SODIUM_PRESENT case at all
        // and fell to the UNKNOWN key. The banner assertions below are the
        // guard for that (owner report 2026-09-08 (beta.8)).
        context.waitTicks(10);
        context.takeScreenshot(TestScreenshotOptions.of("94_meshelium_options_under_sodium"));
        context.runOnClient(client -> {
            MesheliumOptionsScreen screen = (MesheliumOptionsScreen) client.gui.screen();
            String status = screen.statusText();
            boolean saysOff = status.contains("NOT RENDERING");
            if (expectOff && !saysOff) {
                throw new AssertionError(
                        "the adapter is not drawing (declined, or backend "
                                + MesheliumGate.backend() + ") but the status header says '"
                                + status + "', which claims Meshelium is doing something it "
                                + "is not");
            }
            if (!expectOff && saysOff) {
                throw new AssertionError(
                        "Meshelium is drawing Sodium's terrain with mesh shaders, but the status "
                                + "header says '" + status + "'. A player reading that would "
                                + "conclude the mod does nothing under Sodium and uninstall it.");
            }
            System.out.println("[Meshelium] Sodium status header: " + status);

            // The lock banner: never the UNKNOWN wording once the gate has
            // decided, and under a working adapter it describes the
            // adapter. The rows: the cap is live (ExtendedRd widens the
            // range under SODIUM_PRESENT), the standalone rows are held.
            String unknown = Component.translatable("meshelium.options.gate.unknown").getString();
            String banner = screen.gateBannerText();
            if (banner.isEmpty()) {
                throw new AssertionError("no gate banner under Sodium. Meshelium's own "
                        + "chunk-builder rows are not BUILT under Sodium since 2026-09-16, and "
                        + "the banner is the only thing left that says which settings are "
                        + "missing and why");
            }
            // THE SUB-SHAPE GUARD. sodiumBannerKey can resolve to six
            // different keys - gate.sodium, .sodium_opengl, .sodium_declined,
            // .sodium_no_adapter, .no_mesh.sodium, .vulkan_failed.sodium -
            // and EVERY one of them has to carry the held-rows sentence,
            // because every one of them is shown over a screen with nine
            // rows missing. Whichever key this run resolved to, the sentence
            // must be in it; gate.unknown has no such sentence, so this also
            // catches the undecided banner sneaking back in.
            //
            // It used to demand more: a 2026-09-16 review asked the banner to
            // NAME every hidden row, which grew eight banners by ~290
            // characters each. The owner read the result on 2026-09-18 and
            // called it rambling - "most people arent technical just little
            // minecraft kids who need an explaination and a little text" - so
            // the enumeration is gone and the reassurance stays. What a
            // player needs from this sentence is that the rows are hidden on
            // purpose and will return, not an inventory of them.
            if (!banner.contains("come back if you remove Sodium")) {
                throw new AssertionError("the Sodium banner does not tell the player the held "
                        + "settings are coming back: '" + banner + "'. Without that sentence a "
                        + "screen with rows missing reads as a screen that lost them");
            }
            if (banner.equals(unknown) || banner.contains("checking")) {
                throw new AssertionError("gate is " + MesheliumGate.state() + " (backend "
                        + MesheliumGate.backend() + ") but the settings banner says: " + banner);
            }
            if (!expectOff && !banner.contains("Working with Sodium")) {
                throw new AssertionError("banner does not describe the adapter: '" + banner
                        + "'");
            }
            if (screen.capLocked()) {
                throw new AssertionError("Distance Cap is held under Sodium, but "
                        + "MesheliumExtendedRd widens the range under SODIUM_PRESENT; the row "
                        + "and the range disagree");
            }
            if (!screen.gateLocked()) {
                throw new AssertionError("gateLocked is false under Sodium. It is what keeps "
                        + "the rows Meshelium's own chunk builder owns off this screen, and "
                        + "what the held sentence on every remaining tooltip keys on");
            }

            // WHICH Sodium (item 3). Built whenever the loader reports a
            // version, which Fabric always does, so an empty line here is a
            // missing row rather than an honest silence.
            String versionLine = screen.sodiumVersionText();
            String installed = MesheliumPlatform.modVersion(MesheliumGate.SODIUM_MOD_ID)
                    .orElse(null);
            if (installed == null) {
                throw new AssertionError("the loader did not report Sodium's version, so the "
                        + "version row could not be built and this leg cannot judge it");
            }
            if (versionLine.isEmpty()) {
                throw new AssertionError("no Sodium version row on the settings screen. It is "
                        + "the first thing a bug report needs and the only place a player is "
                        + "told their Sodium is not the one this build was made for");
            }
            String expectedVersionLine = Component.translatable(
                    "meshelium.options.sodium.version", installed).getString();
            if (!versionLine.equals(expectedVersionLine)) {
                throw new AssertionError("the Sodium version row reads '" + versionLine
                        + "'. This run's Sodium is " + installed + " and the jar was built "
                        + "against " + com.deds.meshelium.MesheliumSodiumVersion.BUILT_AGAINST
                        + "; if those have drifted, bump gradle.properties and "
                        + "MesheliumSodiumVersion.BUILT_AGAINST together rather than relaxing "
                        + "this assertion");
            }

            // THE ROW CENSUS. Absent is the assertion, not inactive: a
            // greyed row that says "held while Sodium builds the terrain"
            // is a row the screen is spending space to apologise for.
            List<AbstractWidget> rows = new ArrayList<>();
            collectWidgets(screen, rows);
            requireAbsentUnderSodium(rows, Component.translatable(
                    "meshelium.options.terrain").getString(), "the master switch");
            requireAbsentUnderSodium(rows, Component.translatable(
                    "meshelium.options.occlusion").getString(), "Occlusion Culling");
            // These two labels put their placeholder in the MIDDLE, so the
            // house trick of formatting with an empty argument produces a
            // string with a doubled space that no real row contains - the
            // absence check would pass vacuously. The raw pattern up to the
            // first placeholder is the prefix every state of the row shares.
            requireAbsentUnderSodium(rows, prefixBeforePlaceholder(
                    "meshelium.options.occlusion_rd.label"), "the Auto crossover");
            requireAbsentUnderSodium(rows, prefixBeforePlaceholder(
                    "meshelium.options.status.memory"), "the terrain-memory readout");
            requirePresentUnderSodium(rows, Component.translatable(
                    "meshelium.options.max_rd.label", Component.literal("")).getString(),
                    "Distance Cap");
            requirePresentUnderSodium(rows, Component.translatable(
                    "meshelium.options.sodium_gpu").getString(), "GPU Visibility");
            requirePresentUnderSodium(rows, Component.translatable(
                    "meshelium.options.fog").getString(), "Distance Fog");
            requirePresentUnderSodium(rows, Component.translatable(
                    "meshelium.options.advanced").getString(), "Advanced...");
            requirePresentUnderSodium(rows, Component.translatable(
                    "meshelium.options.reset").getString(), "Reset Meshelium Settings");
            requirePresentUnderSodium(rows, Component.translatable("gui.done").getString(),
                    "Done");
        });

        // The two-click reset says, in shape B, that it also resets what is
        // NOT on screen: resetToDefaults() rewrites twenty fields, most of
        // them rows this screen no longer shows. Arming it is safe - the
        // Advanced click below disarms and relabels it, which is the same
        // guard the button's own handler uses when a player navigates away.
        context.clickScreenButton("meshelium.options.reset");
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            List<AbstractWidget> widgets = new ArrayList<>();
            collectWidgets(screen, widgets);
            String sodiumConfirm = Component.translatable(
                    "meshelium.options.reset.confirm.sodium").getString();
            AbstractWidget armed = findWidget(widgets, sodiumConfirm);
            if (armed == null) {
                throw new AssertionError("the armed reset button under Sodium does not say it "
                        + "resets the settings this screen is not showing. Note that the "
                        + "standalone wording is a PREFIX of the Sodium one, so a contains() "
                        + "search for the standalone text would have passed either way");
            }
            if (!armed.getMessage().getString().equals(sodiumConfirm)) {
                throw new AssertionError("armed reset label is '"
                        + armed.getMessage().getString() + "'");
            }
        });

        assertAdvancedScreenUnderSodium(context, !expectOff);
    }

    /**
     * The Advanced page under Sodium: its banner must be the Sodium
     * sentence for the sub-shape it is in, and Sub-Pixel Detail must be
     * live exactly when the adapter is drawing, because the Sodium drawer
     * reads it every frame (owner report 2026-09-08 (beta.8)).
     *
     * <p>Since 2026-09-16 the six rows only Meshelium's own chunk builder
     * reads are ABSENT rather than greyed - greedy meshing, the tiny-plant
     * cull, the two leaf tiers, idle trim and the duplicate-memory row -
     * and the census below asserts absence, not inactivity. A greyed row
     * with a tooltip explaining that it belongs to a chunk builder which is
     * standing aside is space spent apologising; the banner names all six
     * in one line instead, which is what the rows were providing.
     *
     * <p>Ends back on the live options screen it started from.
     */
    private static void assertAdvancedScreenUnderSodium(ClientGameTestContext context,
            boolean armed) {
        context.clickScreenButton("meshelium.options.advanced");
        context.waitForScreen(MesheliumAdvancedScreen.class);
        context.waitTicks(2);
        context.takeScreenshot(TestScreenshotOptions.of("95_meshelium_advanced_under_sodium"));
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            List<AbstractWidget> widgets = new ArrayList<>();
            collectWidgets(screen, widgets);
            // THREE keys, not two. The armed/off ternary STAYS and gains a
            // third arm: advanced.locked.sodium says only the settings that
            // still apply are shown, which is false in the two Sodium
            // shapes where subPixelLive and statsLive are both false and
            // two rows on screen are grey. advanced.locked, the standalone
            // wording, must appear in NEITHER Sodium shape - it says
            // Meshelium is not running, which is wrong when the adapter is
            // drawing and incomplete when it is not.
            String sodiumBanner = Component.translatable(
                    "meshelium.options.advanced.locked.sodium").getString();
            String sodiumOffBanner = Component.translatable(
                    "meshelium.options.advanced.locked.sodium_off").getString();
            String plainBanner = Component.translatable(
                    "meshelium.options.advanced.locked").getString();
            if (findWidget(widgets, armed ? sodiumBanner : sodiumOffBanner) == null) {
                throw new AssertionError("the Advanced screen under Sodium (adapter "
                        + (armed ? "armed" : "off") + ") does not show the expected banner");
            }
            if (findWidget(widgets, plainBanner) != null) {
                throw new AssertionError("the Advanced screen shows the STANDALONE locked "
                        + "banner under Sodium. It says Meshelium is not running, which is "
                        + "false while the adapter draws Sodium's chunks and silent about "
                        + "Sodium when it does not");
            }
            String shownBanner = armed ? sodiumBanner : sodiumOffBanner;
            if (!shownBanner.contains("come back if you remove Sodium")) {
                throw new AssertionError("the Advanced screen's Sodium banner does not name the "
                        + "settings it is holding: '" + shownBanner + "'");
            }
            AbstractWidget detail = findWidget(widgets, Component.translatable(
                    "meshelium.options.detail_cull.label", Component.literal("")).getString());
            if (detail == null) {
                throw new AssertionError("no Sub-Pixel Detail slider on the Advanced screen");
            }
            if (detail.active != armed) {
                throw new AssertionError("Sub-Pixel Detail slider active=" + detail.active
                        + " under Sodium with the adapter " + (armed
                                ? "armed; the Sodium drawer reads it every frame"
                                : "off; nothing reads it"));
            }
            // ABSENT, not merely inactive (2026-09-16). Every one of these
            // is read by Meshelium's OWN chunk builder, which does not run
            // while Sodium builds; the banner above names them.
            requireAbsentUnderSodium(widgets, Component.translatable(
                    "meshelium.options.plant_cull.label", Component.literal("")).getString(),
                    "Cull Tiny Plants Beyond");
            requireAbsentUnderSodium(widgets, Component.translatable(
                    "meshelium.options.greedy_meshing").getString(), "Greedy Meshing");
            requireAbsentUnderSodium(widgets, Component.translatable(
                    "meshelium.options.smart_leaves.label", Component.literal("")).getString(),
                    "Smart Leaves Beyond");
            requireAbsentUnderSodium(widgets, Component.translatable(
                    "meshelium.options.solid_leaves.label", Component.literal("")).getString(),
                    "Solid Leaves Beyond");
            requireAbsentUnderSodium(widgets, Component.translatable(
                    "meshelium.options.arena_trim").getString(), "Idle Memory Trim");
            requireAbsentUnderSodium(widgets, Component.translatable(
                    "meshelium.options.suppress_vanilla").getString(),
                    "Duplicate Terrain Memory");
            // And the two that stay, because the Sodium drawer reads them.
            requirePresentUnderSodium(widgets, Component.translatable(
                    "meshelium.options.debug_stats").getString(), "Debug Stat Logging");
            requirePresentUnderSodium(widgets, Component.translatable(
                    "meshelium.options.popup").getString(), "Backend Popup");

            // Done: found AND inside the window, the guard
            // MesheliumBootSmokeTest runs on the standalone Advanced screen.
            // Six rows fewer cannot overflow a scroll container, but the
            // footer is what keeps the way out reachable at large GUI
            // scales and it has never been asserted on this shape.
            AbstractWidget done = null;
            String doneLabel = Component.translatable("gui.done").getString();
            for (AbstractWidget widget : widgets) {
                if (widget instanceof Button && widget.getMessage().getString().equals(doneLabel)) {
                    done = widget;
                }
            }
            if (done == null) {
                throw new AssertionError("no Done button on the Advanced screen under Sodium");
            }
            if (done.getX() < 0 || done.getY() < 0
                    || done.getX() + done.getWidth() > screen.width
                    || done.getY() + done.getHeight() > screen.height) {
                throw new AssertionError("Done at (" + done.getX() + "," + done.getY()
                        + ") is outside the " + screen.width + "x" + screen.height
                        + " window - the footer no longer pins the way out on screen");
            }
        });
        context.clickScreenButton("gui.done");
        context.waitForScreen(MesheliumOptionsScreen.class);
    }

    /**
     * Sodium on OpenGL: the directive's popup, with the Sodium wording, and
     * the same offer on the settings screen.
     *
     * <p>Zero coverage until 2026-09-08 (beta.8): the gate returned from
     * its Sodium branch before the popup, so every beta.4..8 jar showed a
     * Sodium user on OpenGL nothing at all. Mirrors
     * {@code MesheliumBootSmokeTest.assertOpenGlPath}; the popup flags are
     * deterministic because loom wipes the run dir per run.
     */
    private static void assertSodiumOpenGlPopup(ClientGameTestContext context) {
        context.waitForScreen(MesheliumPopupScreen.class);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            if (!(screen instanceof MesheliumPopupScreen popup)
                    || popup.variant() != MesheliumPopupScreen.Variant.ENABLE_VULKAN) {
                throw new AssertionError(
                        "Sodium on OpenGL must still get the ENABLE_VULKAN popup, got " + screen);
            }
            String sodiumBody = Component.translatable(
                    "meshelium.popup.opengl.body.sodium").getString();
            if (!popup.bodyText().equals(sodiumBody)) {
                throw new AssertionError("the popup under Sodium must say Sodium is present "
                        + "and that Meshelium needs Vulkan to draw its terrain; it says: '"
                        + popup.bodyText() + "'");
            }
        });
        context.takeScreenshot(TestScreenshotOptions.of("90_meshelium_sodium_popup_opengl"));
        context.clickScreenButton("meshelium.popup.not_now");
        context.waitForScreen(TitleScreen.class);

        // The settings screen: the Sodium-on-OpenGL banner, the cap live,
        // and the [Enable Vulkan] button.
        context.runOnClient(client ->
                client.gui.setScreen(new MesheliumOptionsScreen(client.gui.screen())));
        context.waitForScreen(MesheliumOptionsScreen.class);
        context.waitTicks(2);
        context.runOnClient(client -> {
            MesheliumOptionsScreen screen = (MesheliumOptionsScreen) client.gui.screen();
            String banner = screen.gateBannerText();
            // A POSITIVE equals(), and the only one in this file. It
            // survives a reword of gate.sodium_opengl because `expected` is
            // rebuilt from the same key - but it is named here so the next
            // person rewording that key is not doing it blind. The other
            // banner assertions in this file (assertStatusHeaderTellsTheTruth)
            // all use contains(), and its one equals() is the NEGATIVE
            // against gate.unknown.
            String expected = Component.translatable(
                    "meshelium.options.gate.sodium_opengl").getString();
            if (!banner.equals(expected)) {
                throw new AssertionError("Sodium on OpenGL: the settings banner should read "
                        + "the Sodium wording, got '" + banner + "'");
            }
            if (screen.capLocked()) {
                throw new AssertionError("Distance Cap held under Sodium on OpenGL, but "
                        + "MesheliumExtendedRd widens the range under SODIUM_PRESENT");
            }
            List<AbstractWidget> widgets = new ArrayList<>();
            collectWidgets(screen, widgets);
            AbstractWidget enable = findWidget(widgets,
                    Component.translatable("meshelium.popup.enable_vulkan").getString());
            if (!(enable instanceof Button)) {
                throw new AssertionError("no [Enable Vulkan] button on the settings screen "
                        + "under Sodium on OpenGL");
            }
        });
        context.takeScreenshot(TestScreenshotOptions.of("90_meshelium_sodium_options_opengl"));

        context.clickScreenButton("meshelium.popup.enable_vulkan");
        context.runOnClient(client -> {
            if (client.options.preferredGraphicsBackend().get() != PreferredGraphicsApi.VULKAN) {
                throw new AssertionError(
                        "[Enable Vulkan] did not write preferredGraphicsBackend=VULKAN");
            }
            Screen screen = client.gui.screen();
            if (!(screen instanceof MesheliumPopupScreen popup)
                    || popup.variant() != MesheliumPopupScreen.Variant.RESTART_REQUIRED) {
                throw new AssertionError("Expected the RESTART_REQUIRED hand-off, got " + screen);
            }
            // The 504d1fd invariant: only [Don't Show This Again] spends
            // the prompt (MesheliumBootSmokeTest.assertOnlyTheOptOutSpendsThePrompt).
            if (!MesheliumConfig.get().showVulkanPrompt) {
                throw new AssertionError("[Enable Vulkan] spent showVulkanPrompt; a Vulkan "
                        + "boot that crashes back to OpenGL would never be told again");
            }
        });
        context.clickScreenButton("meshelium.popup.later");
        context.waitForScreen(MesheliumOptionsScreen.class);
        context.clickScreenButton("gui.done");
        context.waitForScreen(TitleScreen.class);
    }

    /**
     * In a world, the status line must show the real per-FRAME section
     * count, and hold steady with the camera still.
     *
     * <p>It printed the per-PASS figure: the drawer's
     * {@code lastSectionsDrawn()} is written by every opaque pass, so the
     * header alternated between the SOLID and the CUTOUT value and, on a
     * superflat world whose CUTOUT pass is empty, between a number and 0
     * (owner report 2026-09-08 (beta.8): "drawing _ chunk sections"). The
     * format chain itself was sound; this pins the quantity. The header
     * is refreshed and read in the same client call as the counter it is
     * built from, so the two must agree exactly; the twenty-tick sample
     * is the flicker guard.
     */
    private static void assertStatusHeaderCountsSections(ClientGameTestContext context) {
        if ("false".equalsIgnoreCase(System.getProperty("meshelium.sodium.adapter"))) {
            return;
        }
        context.runOnClient(client -> client.gui.setScreen(new MesheliumOptionsScreen(null)));
        context.waitForScreen(MesheliumOptionsScreen.class);
        context.waitTicks(10);
        context.takeScreenshot(TestScreenshotOptions.of("94_meshelium_options_under_sodium_world"));
        Pattern shape = Pattern.compile("drawing (\\d+) chunk sections");
        List<Integer> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            context.runOnClient(client -> {
                MesheliumOptionsScreen screen = (MesheliumOptionsScreen) client.gui.screen();
                screen.testRefreshStatus();
                String status = screen.statusText();
                int frame = SodiumTerrainDrawer.lastSectionsDrawnFrame();
                Matcher m = shape.matcher(status);
                if (!m.find()) {
                    throw new AssertionError("in a world the Sodium status line should read "
                            + "'drawing N chunk sections', got '" + status
                            + "' (the format or the argument count regressed)");
                }
                int shown = Integer.parseInt(m.group(1));
                if (shown <= 0) {
                    throw new AssertionError("the status line says Meshelium is drawing 0 chunk "
                            + "sections in a world it has drawn "
                            + SodiumTerrainDrawer.totalSectionsDrawn() + " sections of: '"
                            + status + "'");
                }
                if (shown != frame) {
                    throw new AssertionError("status line shows " + shown
                            + " sections but lastSectionsDrawnFrame() is " + frame
                            + " in the same call");
                }
                String rung = SodiumTerrainDrawer.rung();
                if (rung == null || !rung.startsWith("0")) {
                    int emitted = SodiumTerrainDrawer.sectionsEmittedFrame();
                    if (shown != emitted) {
                        throw new AssertionError("on the list path the status line must be "
                                + "the SOLID + CUTOUT sum (" + emitted + "), got " + shown);
                    }
                }
                if (samples.isEmpty()) {
                    System.out.println("[Meshelium] Sodium status header, in world: " + status
                            + " (per pass: " + SodiumTerrainDrawer.lastSectionsDrawn()
                            + " runs, rung " + rung + ")");
                }
                samples.add(shown);
            });
            context.waitTicks(1);
        }
        int min = Collections.min(samples);
        int max = Collections.max(samples);
        if (min <= 0 || max > min * 1.25) {
            throw new AssertionError("the status line's section count moved between " + min
                    + " and " + max + " over 20 ticks with the camera still. The per-pass "
                    + "figure alternates SOLID/CUTOUT like this; a per-frame figure holds: "
                    + samples);
        }
        context.runOnClient(client -> client.gui.setScreen(null));
        context.waitTicks(2);
    }

    /** Every AbstractWidget in the event-handler tree, scroll containers included. */
    private static void collectWidgets(ContainerEventHandler container, List<AbstractWidget> out) {
        for (GuiEventListener child : container.children()) {
            if (child instanceof AbstractWidget widget) {
                out.add(widget);
            }
            if (child instanceof ContainerEventHandler nested) {
                collectWidgets(nested, out);
            }
        }
    }

    /**
     * The part of a translation that comes before its first placeholder.
     *
     * <p>{@code Component.translatable(key, literal(""))} is the house way
     * to build a row label with the value stripped out, and it works only
     * while the placeholder is LAST: "Cull Tiny Plants Beyond: %s" becomes
     * "Cull Tiny Plants Beyond: ", which every state of that row contains.
     * With the placeholder in the middle - "Auto turns on at: %s chunks",
     * "Terrain memory: %s MB" - it produces a doubled space that no live
     * row ever contains, so an ABSENCE check written that way passes
     * whatever is on screen.
     */
    private static String prefixBeforePlaceholder(String key) {
        String pattern = Language.getInstance().getOrDefault(key);
        int placeholder = pattern.indexOf('%');
        if (placeholder <= 0) {
            throw new AssertionError(key + " has no placeholder, so this helper is the wrong "
                    + "tool for it: " + pattern);
        }
        return pattern.substring(0, placeholder);
    }

    /**
     * A row that must NOT be on a Sodium screen at all.
     *
     * <p>Absent rather than inactive is the whole of item (2). A greyed row
     * with a tooltip explaining that it belongs to a chunk builder which is
     * standing aside is space spent apologising; the banner names every one
     * of them in a line.
     */
    private static void requireAbsentUnderSodium(List<AbstractWidget> widgets, String label,
            String humanName) {
        AbstractWidget found = findRow(widgets, label);
        if (found != null) {
            throw new AssertionError(humanName + " is still on the screen under Sodium (label '"
                    + found.getMessage().getString() + "', active=" + found.active + "). It is "
                    + "read only by Meshelium's own chunk builder, which does not run while "
                    + "Sodium builds the chunks");
        }
    }

    /** A row that must survive the Sodium shape. */
    private static void requirePresentUnderSodium(List<AbstractWidget> widgets, String label,
            String humanName) {
        if (findRow(widgets, label) == null) {
            throw new AssertionError(humanName + " is missing from the Sodium screen. The rows "
                    + "removed for Sodium are the ones Meshelium's own chunk builder reads; "
                    + "this one is not, so removing it takes a working setting away");
        }
    }

    /** The first widget whose message contains the label, or null. */
    /**
     * Any widget whose label contains this text, prose included. Banner
     * checks want this one; ROW checks must not use it (see findRow).
     */
    private static AbstractWidget findWidget(List<AbstractWidget> widgets, String label) {
        for (AbstractWidget widget : widgets) {
            if (widget.getMessage().getString().contains(label)) {
                return widget;
            }
        }
        return null;
    }

    /**
     * A widget the player can OPERATE, whose label contains this text.
     *
     * <p>Rows and prose have to be told apart, because the screen's prose
     * quotes row labels verbatim: the Sodium banner names every setting it
     * is hiding, by the label the player saw, which is the point of that
     * sentence. Matching prose made a removed row look present
     * (2026-09-16, both Sodium forms), and in a PRESENCE check it would do
     * the worse thing and let a missing row pass because a sentence
     * mentioned it. The match stays substring because a row's label carries
     * its value ("Distance Cap: 96").
     */
    private static AbstractWidget findRow(List<AbstractWidget> widgets, String label) {
        for (AbstractWidget widget : widgets) {
            if (isProse(widget)) {
                continue;
            }
            if (widget.getMessage().getString().contains(label)) {
                return widget;
            }
        }
        return null;
    }

    /** True for the screen's non-interactive text: titles, status lines, banners. */
    private static boolean isProse(AbstractWidget widget) {
        return widget instanceof net.minecraft.client.gui.components.StringWidget
                || widget instanceof net.minecraft.client.gui.components.MultiLineTextWidget;
    }

    /**
     * The first Button on the screen whose translated label EQUALS the
     * key's translation, or null. A probe, never a press: fabric's
     * {@code clickScreenButton} presses on match, so it cannot be used to
     * ask whether a button exists. Same equality as fabric's walker, so
     * what this finds is what a click would hit.
     */
    private static Button findButton(Screen screen, String translationKey) {
        if (screen == null) {
            return null;
        }
        String label = Component.translatable(translationKey).getString();
        List<AbstractWidget> widgets = new ArrayList<>();
        collectWidgets(screen, widgets);
        for (AbstractWidget widget : widgets) {
            if (widget instanceof Button button && button.getMessage().getString().equals(label)) {
                return button;
            }
        }
        return null;
    }

    /** How many Buttons on the screen carry exactly the key's translation. */
    private static int countButtons(Screen screen, String translationKey) {
        if (screen == null) {
            return 0;
        }
        String label = Component.translatable(translationKey).getString();
        List<AbstractWidget> widgets = new ArrayList<>();
        collectWidgets(screen, widgets);
        int count = 0;
        for (AbstractWidget widget : widgets) {
            if (widget instanceof Button && widget.getMessage().getString().equals(label)) {
                count++;
            }
        }
        return count;
    }
}
