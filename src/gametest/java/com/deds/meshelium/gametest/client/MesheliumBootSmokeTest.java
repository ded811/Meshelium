/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.gametest.client;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.gui.MesheliumOptionsScreen;
import com.deds.meshelium.gui.MesheliumPopupScreen;
import com.deds.meshelium.vk.HelloMeshletRenderer;
import com.deds.meshelium.vk.TerrainDrawer;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/**
 * Layer 3 from wave 0 — the standing lesson from the sibling pack repo,
 * where OpenBlocks stood its harness up three playtest rounds too late and
 * every render bug until then reached the owner first. For a renderer mod
 * this is not optional polish; a real client boot on a real GPU is the only
 * layer that can see anything this mod does.
 *
 * <p>Wave 1 grows the boot smoke into the backend-gate test. The same test
 * class serves both harness paths; the coordinator runs it twice:</p>
 *
 * <pre>
 *   ./gradlew runClientGameTest "-Pmeshelium.backend=opengl"   (GL path)
 *   ./gradlew runClientGameTest "-Pmeshelium.backend=vulkan"   (Vulkan path)
 *   ./gradlew runClientGameTest "-Pmeshelium.backend=vulkan" "-Pmeshelium.hello=true"
 *                                       (wave 2: + hello-meshlet evidence)
 * </pre>
 *
 * <p>The -P property makes build.gradle pass vanilla's {@code
 * --graphicsBackend} launch argument AND the {@code
 * meshelium.test.expectBackend} system property, so the expected path is
 * pinned from outside — the test never trusts the gate to grade its own
 * homework. With no -P at all, 26.2's DEFAULT is OpenGL-first (seam doc
 * Q1), so the expectation defaults to the GL path. The gametest framework
 * wipes {@code build/run/clientGameTest} before each run (loom's
 * deleteGameTestRunDir), so Meshelium's popup flags start fresh every time
 * and the popup is deterministic.</p>
 *
 * <p>GL path: waits for the popup to replace the title screen, screenshots
 * it, presses [Enable Vulkan], asserts the vanilla option was really
 * written, screenshots the restart hand-off, dismisses with [Later] (the
 * framework requires every test to end on the title screen). Vulkan path:
 * waits for the gate to decide, asserts Vulkan + mesh shaders and NO popup,
 * screenshots the clean title. Both paths then run the wave-0 world boot
 * and screenshot spawn.</p>
 */
public final class MesheliumBootSmokeTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        String expected = System.getProperty("meshelium.test.expectBackend", "opengl");
        boolean vulkanRun = "vulkan".equalsIgnoreCase(expected);
        if (vulkanRun) {
            assertVulkanPath(context);
        } else {
            assertOpenGlPath(context);
        }

        // Wave-13/15: the chunk-task priority ladder is widened
        // UNCONDITIONALLY at boot by (hard max - 32) rungs, on BOTH
        // backends — a cap-sized tracker produces ticket levels up to
        // cap+2, so the ladder must hold >= cap+3 rungs no matter what
        // the config said at boot (the mid-session-raise-then-rejoin
        // AIOOBE class; the rung count follows the wave-15 hard max).
        context.runOnClient(client -> {
            int rungs = com.deds.meshelium.MesheliumExtendedRd.chunkTaskLadderRungs();
            int needed = com.deds.meshelium.MesheliumConfig.MAX_MAX_RENDER_DISTANCE + 3;
            if (rungs < needed) {
                throw new AssertionError("chunk-task priority ladder has " + rungs
                        + " rungs - the unconditional widening did not follow the hard max "
                        + "(a " + com.deds.meshelium.MesheliumConfig.MAX_MAX_RENDER_DISTANCE
                        + "-cap tracker needs >= " + needed + ")");
            }
        });

        // Wave-15: lang lint (owner directive 9) — no em/en dash may ship
        // in any player-facing string; tooltips carry detail, dashes don't.
        assertLangHasNoLongDashes();
        assertShippedDefaults();

        // Wave-15: large retention-limit values round-trip through the
        // resolver (pure CPU, both backends), and the live-grow machinery
        // is provably dormant with no world up.
        //
        // 2026-08-11: the limit's slider and custom box left the options
        // screen with the rest of the retention UI (Bobby owns that job
        // now), so these two values are no longer things a player can
        // type. The assertion is kept AS IS and unweakened because the
        // field is still the developer surface documented on
        // MesheliumConfig.retainTerrainMinutes: hand-edited config values
        // well past the old 24 h lattice must still reach
        // retainLimitMillis() intact. It needs no arming: the resolver is
        // pure arithmetic over the field and never consults
        // retainTerrain.
        context.runOnClient(client -> {
            if (System.getProperty("meshelium.retainSeconds") == null) {
                var config = com.deds.meshelium.MesheliumConfig.get();
                int original = config.retainTerrainMinutes;
                try {
                    config.retainTerrainMinutes = 300; // 5 h, off the old lattice
                    if (com.deds.meshelium.MesheliumConfig.retainLimitMillis() != 300L * 60_000L) {
                        throw new AssertionError("retainLimitMillis lost the custom 300-minute "
                                + "value: " + com.deds.meshelium.MesheliumConfig.retainLimitMillis());
                    }
                    config.retainTerrainMinutes = 4320; // 3 days, well past 24 h
                    if (com.deds.meshelium.MesheliumConfig.retainLimitMillis() != 4320L * 60_000L) {
                        throw new AssertionError("retainLimitMillis lost the custom 4320-minute "
                                + "value: " + com.deds.meshelium.MesheliumConfig.retainLimitMillis());
                    }
                } finally {
                    config.retainTerrainMinutes = original;
                }
            }
            if (com.deds.meshelium.terrain.host.TerrainResidency.pinnedGrows() != 0
                    || com.deds.meshelium.terrain.host.TerrainResidency.pinnedGrowFailedThisWorld()) {
                throw new AssertionError("pinned-grow state moved before any world existed");
            }
        });

        // Wave-13: the "Meshelium Settings..." button in vanilla's Video
        // Settings screen, on BOTH backends — on GL the screen must open
        // with the renderer rows locked and the status header saying WHY
        // (the silent-refusal / silent-dormancy classes from the owner
        // playtests).
        assertVideoSettingsButton(context, !vulkanRun);
        // Wave 2: armed by build.gradle's -Pmeshelium.hello=true →
        // -Dmeshelium.helloMeshlet=true (same double gate the renderer uses).
        boolean hello = vulkanRun && Boolean.getBoolean("meshelium.helloMeshlet");

        try (TestSingleplayerContext singleplayer =
                context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            context.takeScreenshot(
                    TestScreenshotOptions.of("00_meshelium_boot_spawn"));
            if (hello) {
                assertHelloMeshlet(context);
            }
        }
    }

    /**
     * Wave-2 acceptance: with the world up and chunks rendered, the hello
     * draws must have recorded frames with NO error, and the screenshot the
     * coordinator reads must show the magenta NDC triangle (plus the yellow
     * world-anchored one where the camera allows). {@code lastError()} is
     * the once-only ERROR log's programmatic twin — null means the log
     * never fired; a non-null value carries the failure text straight into
     * the assertion message.
     */
    private static void assertHelloMeshlet(ClientGameTestContext context) {
        // A few extra frames so the lazy pipeline build + first draws have
        // definitely happened (they run inside the frames the wait ticks
        // render) before the evidence screenshot.
        context.waitTicks(5);
        context.runOnClient(client -> {
            String error = HelloMeshletRenderer.lastError();
            if (error != null) {
                throw new AssertionError("Hello-meshlet renderer reported an error: " + error);
            }
            if (HelloMeshletRenderer.frameCount() == 0) {
                throw new AssertionError("Hello-meshlet renderer never recorded a frame "
                        + "(gate open, property set, world rendered - the injection did not fire)");
            }
        });
        context.takeScreenshot(TestScreenshotOptions.of("20_meshelium_hello_meshlet"));
        // Screenshot taken AFTER the health probes: a PNG with no triangle
        // and a green test would be the worst outcome. The coordinator
        // reading the triangle in the PNG is the wave's acceptance.
        context.runOnClient(client -> {
            String error = HelloMeshletRenderer.lastError();
            if (error != null) {
                throw new AssertionError("Hello-meshlet renderer failed during the evidence "
                        + "screenshot: " + error);
            }
        });
    }

    /**
     * Wave-13: the Video Settings integration, both backends. Opens the
     * REAL vanilla screen (public ctor {@code (Screen, Minecraft,
     * Options)}, javap-verified), screenshots the Meshelium row (HEAD of
     * the OptionsList — visible without scrolling), clicks through to
     * {@link MesheliumOptionsScreen}, asserts the gate-lock probe and the
     * status header (locked backends MUST say "NOT RENDERING", a healthy
     * Vulkan title says "READY"), and walks Done → Done back to the
     * title.
     *
     * <p>Note the GL run reaches this AFTER the popup leg pressed
     * [Enable Vulkan], so {@code preferredGraphicsBackend} is already
     * VULKAN — the options screen then shows the vulkan-failed detail
     * and (correctly, the broken-promise rule) NO [Enable Vulkan]
     * button; that button's presence on a fresh GL config is
     * code-reviewed only, mirrored verbatim from the wave-1 popup.</p>
     */
    private static void assertVideoSettingsButton(ClientGameTestContext context,
            boolean expectLocked) {
        VideoSettingsScreen[] videoSettings = new VideoSettingsScreen[1];
        net.minecraft.client.gui.components.AbstractWidget[] sliderBefore =
                new net.minecraft.client.gui.components.AbstractWidget[1];
        context.runOnClient(client -> client.gui.setScreen(
                new VideoSettingsScreen(client.gui.screen(), client, client.options)));
        context.waitTicks(2);
        context.runOnClient(client -> {
            if (!(client.gui.screen() instanceof VideoSettingsScreen vs)) {
                throw new AssertionError("Video Settings did not open; got "
                        + client.gui.screen());
            }
            videoSettings[0] = vs;
            sliderBefore[0] = optionsListOf(vs).findOption(client.options.renderDistance());
        });
        context.takeScreenshot(TestScreenshotOptions.of("B0_meshelium_video_settings_button"));

        pressOptionsListButton(context, "meshelium.options.open");
        context.waitForScreen(MesheliumOptionsScreen.class);
        context.runOnClient(client -> {
            MesheliumOptionsScreen screen = (MesheliumOptionsScreen) client.gui.screen();
            if (screen.gateLocked() != expectLocked) {
                throw new AssertionError("MesheliumOptionsScreen.gateLocked()="
                        + screen.gateLocked() + " but this run expects " + expectLocked
                        + " (gate=" + MesheliumGate.state() + ")");
            }
        });
        context.waitTicks(2); // let tick() refresh the status header once
        context.runOnClient(client -> {
            MesheliumOptionsScreen screen = (MesheliumOptionsScreen) client.gui.screen();
            String status = screen.statusText();
            if (expectLocked && !status.contains("NOT RENDERING")) {
                throw new AssertionError("locked backend but the status header says '"
                        + status + "' - the silent-dormancy class is back");
            }
            if (!expectLocked && !status.contains("READY")) {
                throw new AssertionError("healthy Vulkan title screen but the status header "
                        + "says '" + status + "' (expected READY - no world is open here)");
            }
        });
        context.takeScreenshot(TestScreenshotOptions.of("B1_meshelium_options_from_video_settings"));

        // Wave-15 (Vulkan only — the cap row is gate-locked on GL, and a
        // -D override also locks it): the back-out regression walk.
        if (!expectLocked && System.getProperty("meshelium.maxRenderDistance") == null) {
            assertCapChangeRefreshesParentSlider(context, videoSettings[0], sliderBefore[0]);
        }

        context.clickScreenButton("gui.done"); // Meshelium screen -> back to Video Settings
        context.runOnClient(client -> {
            if (!(client.gui.screen() instanceof VideoSettingsScreen)) {
                throw new AssertionError("Done on the Meshelium screen must return to Video "
                        + "Settings (the parent), got " + client.gui.screen());
            }
        });
        context.clickScreenButton("gui.done"); // Video Settings footer -> title
        context.waitForScreen(TitleScreen.class);
    }

    /**
     * Wave-15 lang lint (owner directive 9): the shipped en_us.json must
     * contain no U+2014 (em dash) or U+2013 (en dash) anywhere — the
     * sentences were restructured with commas, colons and periods, and
     * this pin keeps future strings honest. Runs on both backends (pure
     * classpath read).
     */
    /**
     * The defaults a player actually gets. Added at 1.0.0 after the
     * occlusion default was found to be WRONG in a way no test could
     * catch: it was measured only in the harness's 854x480 window, where
     * rasterisation work is nearly free, so the passes looked like a win
     * while costing real players up to half their frame rate at 1080p and
     * above. A default is a shipped decision and deserves an assertion.
     */
    private static void assertShippedDefaults() {
        if (System.getProperty(TerrainDrawer.PROPERTY_BFS_ONLY) != null) {
            return; // a run that overrides it cannot judge the default
        }
        // 1.1: the default is AUTO, not a boolean. The assertion that
        // matters is no longer "off" but "not unconditionally on", plus the
        // crossover being where the measurements put it. A plain ON default
        // is what 1.0.0 shipped and it made render distance 32, the common
        // case, measurably slower.
        MesheliumConfig config = MesheliumConfig.get();
        if (config.occlusionMode != MesheliumConfig.OcclusionMode.AUTO) {
            throw new AssertionError("occlusion culling no longer defaults to AUTO (it is "
                    + config.occlusionMode + "). There is no global right answer: measured "
                    + "same-session at 1920x1080, occlusion is 11 to 15 percent SLOWER than "
                    + "the BFS feed at render distance 32 and 19 to 31 percent FASTER at 64. "
                    + "A plain ON default is 1.0.0's mistake and a plain OFF default hides a "
                    + "large win from the players this mod exists for. The numbers are on "
                    + "MesheliumConfig.occlusionMode");
        }
        // Auto must not be armed below the measured crossover. Guards the
        // specific regression of somebody 'tuning' the default down to make
        // a benchmark look better at short distances.
        if (config.occlusionAutoMinRenderDistance < MesheliumConfig.DEFAULT_OCCLUSION_AUTO_RD) {
            throw new AssertionError("the Auto occlusion crossover ships at "
                    + config.occlusionAutoMinRenderDistance + ", below the measured "
                    + MesheliumConfig.DEFAULT_OCCLUSION_AUTO_RD + ". Every scene at render "
                    + "distance 8, 16, 24 and 32 measured SLOWER with occlusion on; 48 and 64 "
                    + "measured faster. Lowering the shipped default needs new measurements, "
                    + "not a hunch. Players can still lower it themselves");
        }
        if (!MesheliumConfig.terrainRenderingEnabled()) {
            throw new AssertionError("terrain rendering defaults to OFF, so the mod ships "
                    + "doing nothing");
        }
    }

    private static void assertLangHasNoLongDashes() {
        String lang;
        try (java.io.InputStream in = MesheliumBootSmokeTest.class
                .getResourceAsStream("/assets/meshelium/lang/en_us.json")) {
            if (in == null) {
                throw new AssertionError("assets/meshelium/lang/en_us.json not on the classpath");
            }
            lang = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read en_us.json", e);
        }
        for (int i = 0; i < lang.length(); i++) {
            char c = lang.charAt(i);
            if (c == '—' || c == '–') {
                int from = Math.max(0, i - 50);
                throw new AssertionError("en_us.json contains an em/en dash near: ..."
                        + lang.substring(from, Math.min(lang.length(), i + 10)) + "...");
            }
        }
    }

    /**
     * Wave-15 regression walk for the owner-hit back-out bug (directive
     * 7): change the cap INSIDE the Meshelium screen, press Done, and the
     * Video Settings screen's render-distance slider must show the new
     * bounds WITHOUT leaving the options tree. Mechanism pinned on the
     * real client (2026-08-10 instrumented run): {@code rebuildWidgets()}
     * on the CACHED parent duplicates its accumulating
     * {@code HeaderAndFooterLayout} contents and the stale first
     * OptionsList shadows the fresh one, so the fix is vanilla's own
     * idiom instead - Done constructs a FRESH {@code VideoSettingsScreen}
     * carrying the original {@code lastScreen} (evidence on
     * {@code OptionsSubScreenAccessor}). This walk asserts (a) Done lands
     * on a Video Settings screen that is NOT the stale instance, (b) its
     * renderDistance widget is a fresh instance, and (c) the option range
     * narrowed to the new cap. Runs on the healthy-Vulkan title only (the
     * rows are gate-locked on GL). Entered while the Meshelium screen is
     * open over the given parent; leaves the walk back on the Meshelium
     * screen with the cap restored.
     */
    private static void assertCapChangeRefreshesParentSlider(ClientGameTestContext context,
            VideoSettingsScreen parentInstance,
            net.minecraft.client.gui.components.AbstractWidget sliderBefore) {
        int[] originalCap = new int[1];
        context.runOnClient(client -> {
            originalCap[0] = com.deds.meshelium.MesheliumConfig.get().maxRenderDistance;
            MesheliumOptionsScreen screen = (MesheliumOptionsScreen) client.gui.screen();
            screen.testSetCap(64);
        });
        context.clickScreenButton("gui.done"); // Meshelium -> fresh Video Settings
        context.runOnClient(client -> {
            if (!(client.gui.screen() instanceof VideoSettingsScreen)) {
                throw new AssertionError("Done after a cap change must land on a Video "
                        + "Settings screen, got " + client.gui.screen());
            }
            VideoSettingsScreen landed = (VideoSettingsScreen) client.gui.screen();
            if (landed == parentInstance) {
                throw new AssertionError("Done after a cap change returned the STALE Video "
                        + "Settings instance - the fresh-instance back-out fix regressed "
                        + "(its slider still holds the old ValueSet)");
            }
            net.minecraft.client.gui.components.AbstractWidget sliderAfter =
                    optionsListOf(landed).findOption(client.options.renderDistance());
            if (sliderAfter == null) {
                throw new AssertionError("renderDistance widget not found on the fresh "
                        + "Video Settings screen");
            }
            if (sliderAfter == sliderBefore) {
                throw new AssertionError("the fresh Video Settings screen reused the stale "
                        + "renderDistance widget - impossible unless construction changed");
            }
            if (client.options.renderDistance().values().validateValue(64).isEmpty()
                    || client.options.renderDistance().values().validateValue(72).isPresent()) {
                throw new AssertionError("cap 64 did not narrow the option range live");
            }
        });
        // Restore through the same UI path so the walk ends states-clean.
        pressOptionsListButton(context, "meshelium.options.open");
        context.waitForScreen(MesheliumOptionsScreen.class);
        context.runOnClient(client -> {
            ((MesheliumOptionsScreen) client.gui.screen()).testSetCap(originalCap[0]);
        });
    }

    /** The screen's OptionsList (Video Settings holds exactly one). */
    private static OptionsList optionsListOf(Screen screen) {
        for (GuiEventListener child : screen.children()) {
            if (child instanceof OptionsList list) {
                return list;
            }
        }
        throw new AssertionError("no OptionsList on " + screen);
    }

    /**
     * fabric's {@code clickScreenButton} walks {@code renderables} and
     * descends only via {@code LayoutElement.visitWidgets} — and NO class
     * in the {@code AbstractSelectionList} hierarchy overrides
     * {@code visitWidgets} (javap census, 26.2 jar), so a button INSIDE
     * an OptionsList entry is unreachable for it. This helper walks the
     * real event-handler tree instead — {@code Screen.children()} →
     * OptionsList → entries ({@code ContainerEventHandler}) → widgets —
     * and presses the match exactly the way fabric does
     * ({@code Button.onPress(new MouseButtonInfo(-1, 0))}, its
     * pressMatchingButton bytecode).
     */
    private static void pressOptionsListButton(ClientGameTestContext context,
            String translationKey) {
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            String label = Component.translatable(translationKey).getString();
            if (screen != null) {
                for (GuiEventListener child : screen.children()) {
                    if (!(child instanceof OptionsList list)) {
                        continue;
                    }
                    for (GuiEventListener entry : list.children()) {
                        if (!(entry instanceof ContainerEventHandler container)) {
                            continue;
                        }
                        for (GuiEventListener widget : container.children()) {
                            if (widget instanceof Button button
                                    && button.getMessage().getString().equals(label)) {
                                button.onPress(new MouseButtonInfo(-1, 0));
                                return;
                            }
                        }
                    }
                }
            }
            throw new AssertionError("No button '" + translationKey
                    + "' inside any OptionsList of " + screen
                    + " - the VideoSettingsScreenMixin row is missing");
        });
    }

    /**
     * State (a): the popup is the mod's front door — assert it opens, and
     * that the [Enable Vulkan] button really writes the vanilla option
     * (the owner directive's core requirement).
     */
    private static void assertOpenGlPath(ClientGameTestContext context) {
        context.waitForScreen(MesheliumPopupScreen.class);
        context.runOnClient(client -> {
            if (MesheliumGate.state() != MesheliumGate.State.OPENGL) {
                throw new AssertionError("Expected gate state OPENGL on the GL run, got "
                        + MesheliumGate.state());
            }
            Screen screen = client.gui.screen();
            if (!(screen instanceof MesheliumPopupScreen popup)
                    || popup.variant() != MesheliumPopupScreen.Variant.ENABLE_VULKAN) {
                throw new AssertionError("Expected the ENABLE_VULKAN popup at title, got " + screen);
            }
            // Wave-14 dormancy half of the memory probe: it lives inside
            // the Vulkan device-creation hook, so a GL session must never
            // have run it (0 = "no probe" for every consumer).
            if (com.deds.meshelium.MesheliumVulkanState.deviceLocalHeapBytes() != 0) {
                throw new AssertionError("device-local heap probe ran on the OpenGL path: "
                        + com.deds.meshelium.MesheliumVulkanState.deviceLocalHeapBytes() + " bytes");
            }
        });
        context.takeScreenshot(TestScreenshotOptions.of("10_meshelium_popup_opengl"));

        // Wave-10 clamp-back invariant, GL half (armed by -Pmeshelium.rd):
        // the gate has decided OPENGL by now (the popup is up), so the
        // option range must be vanilla again — the boot-time widening
        // (config property > 32) must have been revoked on the decision
        // tick, and a >32 value must not stick. This is the "no GL user
        // ever ends up above rd 32 on the vanilla renderer" assertion.
        int testRd = Integer.getInteger("meshelium.test.rd", 0);
        if (testRd > 32) {
            context.runOnClient(client -> {
                if (com.deds.meshelium.MesheliumExtendedRd.rangeWidened()) {
                    throw new AssertionError("render-distance range still widened on the GL "
                            + "path after the gate decision - the clamp-back invariant is broken");
                }
                if (client.options.renderDistance().values()
                        .validateValue(testRd).isPresent()) {
                    throw new AssertionError("vanilla renderDistance option accepted " + testRd
                            + " on the GL path - the range was not restored to vanilla");
                }
                int before = client.options.renderDistance().get();
                client.options.renderDistance().set(testRd);
                int after = client.options.renderDistance().get();
                if (after > 32) {
                    throw new AssertionError("renderDistance.set(" + testRd + ") stuck at "
                            + after + " on the GL path (was " + before + ") - a GL user could "
                            + "reach a vanilla-renderer slideshow");
                }
                // set() with an out-of-range value resets to the option's
                // initial value (OptionInstance.set bytecode: validateValue
                // .orElseGet(initialValue)) - restore the original so the
                // rest of the smoke run sees the untouched default.
                client.options.renderDistance().set(before);
            });
        }

        context.clickScreenButton("meshelium.popup.enable_vulkan");
        context.runOnClient(client -> {
            if (client.options.preferredGraphicsBackend().get() != PreferredGraphicsApi.VULKAN) {
                throw new AssertionError("[Enable Vulkan] did not write preferredGraphicsBackend=VULKAN");
            }
            Screen screen = client.gui.screen();
            if (!(screen instanceof MesheliumPopupScreen popup)
                    || popup.variant() != MesheliumPopupScreen.Variant.RESTART_REQUIRED) {
                throw new AssertionError("Expected the RESTART_REQUIRED hand-off, got " + screen);
            }
        });
        context.takeScreenshot(TestScreenshotOptions.of("11_meshelium_popup_restart"));

        context.clickScreenButton("meshelium.popup.later");
        context.waitForScreen(TitleScreen.class);
        context.takeScreenshot(TestScreenshotOptions.of("12_meshelium_popup_dismissed"));
    }

    /**
     * State (c) on the dev rig (RX 9070 XT): no popup, gate reports
     * Vulkan + mesh shaders (the caps INFO block lands in the log at device
     * creation — the coordinator reads it as the wave's acceptance
     * evidence). A machine whose Vulkan can't do mesh shaders fails this
     * loudly rather than passing vacuously.
     */
    private static void assertVulkanPath(ClientGameTestContext context) {
        context.waitFor(client -> MesheliumGate.state() != MesheliumGate.State.UNKNOWN);
        context.runOnClient(client -> {
            if (MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
                throw new AssertionError("Expected VULKAN_MESH_SHADERS on the Vulkan run, got "
                        + MesheliumGate.state());
            }
            Screen screen = client.gui.screen();
            if (screen instanceof MesheliumPopupScreen) {
                throw new AssertionError("No popup may appear on the Vulkan path, but got "
                        + ((MesheliumPopupScreen) screen).variant());
            }
            // Wave-14 memory probe: a created Vulkan device must have
            // recorded its largest DEVICE_LOCAL heap, and the derived
            // arena ceiling must respect the floor (the caps log line is
            // the coordinator's evidence twin of this assert).
            long heap = com.deds.meshelium.MesheliumVulkanState.deviceLocalHeapBytes();
            if (heap <= 0) {
                throw new AssertionError("no device-local heap recorded on the Vulkan path - "
                        + "the wave-14 memory probe did not run at device creation");
            }
            long ceiling = com.deds.meshelium.MesheliumScaling.arenaCeilingBytes();
            long range = com.deds.meshelium.MesheliumVulkanState.maxStorageBufferRangeBytes();
            // THE INVARIANT IS PER BLOCK, NOT PER TOTAL, and this assertion
            // has now been wrong in both directions, which is worth keeping
            // as a record. It first demanded the ceiling be at least the
            // 256 MiB floor, which forced an unreadable arena on a device
            // with a small maxStorageBufferRange. Corrected to "ceiling must
            // not exceed maxStorageBufferRange", it was right for exactly as
            // long as the arena was one buffer - and then the split made it
            // fire on a perfectly healthy 8151 MiB ceiling across 4 blocks.
            //
            // What actually has to hold is that no single BINDING exceeds
            // what a shader can read. Each block is separately bound, so the
            // total may exceed it and must, or the split bought nothing.
            long blockBytes = com.deds.meshelium.MesheliumScaling.arenaBlockBytes();
            int blocks = com.deds.meshelium.MesheliumScaling.arenaBlockCount();
            if (range > 0 && blockBytes > range) {
                throw new AssertionError("arena BLOCK " + blockBytes
                        + " exceeds maxStorageBufferRange " + range
                        + " - reads past the limit return zero, which is the"
                        + " empty-section tombstone (the wave-14 failure)");
            }
            if (blocks > 0 && ceiling > blockBytes * (long) blocks) {
                throw new AssertionError("arena ceiling " + ceiling
                        + " exceeds what " + blocks + " blocks of " + blockBytes
                        + " can hold - addresses past the last block name a"
                        + " buffer that does not exist");
            }
            long expectedFloor = range > 0
                    ? Math.min(com.deds.meshelium.MesheliumScaling.ARENA_CEILING_FLOOR_BYTES, range)
                    : com.deds.meshelium.MesheliumScaling.ARENA_CEILING_FLOOR_BYTES;
            if (ceiling < expectedFloor) {
                throw new AssertionError("arena ceiling " + ceiling + " below the floor "
                        + expectedFloor + " (heap " + heap + ", range " + range + ")");
            }
            assertAddressableArithmetic();
        });
        context.takeScreenshot(TestScreenshotOptions.of("10_meshelium_title_vulkan_no_popup"));
    }

    /**
     * The clamp arithmetic, against limits no GPU on this desk reports.
     *
     * <p>This exists because the bug it catches was invisible on the only
     * hardware available: the dev card reports maxStorageBufferRange of
     * 4095 MiB, and the failure needs a limit below the 256 MiB ceiling
     * floor. The original code applied the floor AFTER the clamp
     * ({@code Math.max(FLOOR, Math.min(bytes, limit))}), so a device at
     * Vulkan's required minimum of 128 MiB was handed a 256 MiB ceiling and
     * an arena twice as large as its shaders could read - terrain uploaded,
     * counted resident, and read back as zero, which is the empty-section
     * tombstone. That is the wave-14 invisible-terrain failure, rebuilt.
     *
     * <p>Pure arithmetic, so it runs anywhere the harness runs, and it is
     * the only check here that does not depend on which card is installed.
     */
    private static void assertAddressableArithmetic() {
        long mib = 1L << 20;
        // {requested, limit, expected}
        long[][] cases = {
                // Never probed: pass the request through untouched.
                {4096 * mib, 0, 4096 * mib},
                // The dev card. Nothing to clamp, floor irrelevant.
                {2048 * mib, 4095 * mib, 2048 * mib},
                // Request above the limit: clamped down to it.
                {8192 * mib, 4095 * mib, 4095 * mib},
                // THE REGRESSION. Vulkan's required minimum is 128 MiB,
                // which is below the 256 MiB floor. The answer must be the
                // limit, never the floor.
                {4096 * mib, 128 * mib, 128 * mib},
                {64 * mib, 128 * mib, 128 * mib},
                // Floor still applies where the device can afford it.
                {16 * mib, 4095 * mib, 256 * mib},
                // Sub-MiB limit must not round down to a zero-byte arena.
                {4096 * mib, 1000, 1000},
        };
        for (long[] c : cases) {
            long got = com.deds.meshelium.MesheliumScaling.addressableFor(c[0], c[1]);
            if (got != c[2]) {
                throw new AssertionError("addressableFor(" + c[0] + ", limit " + c[1]
                        + ") = " + got + ", expected " + c[2]);
            }
            if (c[1] > 0 && got > c[1]) {
                throw new AssertionError("addressableFor returned " + got
                        + " above the device limit " + c[1] + " - unreadable arena");
            }
        }

        // The override path: clamped, never floored. The two 192/352 MiB
        // cases are the torture knob the guard legs actually run at - if
        // the floor ever leaks into this path they silently become 256 MiB
        // runs and stop testing what they claim to.
        long[][] overrides = {
                {192 * mib, 4095 * mib, 192 * mib},
                {352 * mib, 4095 * mib, 352 * mib},
                {8192 * mib, 4095 * mib, 4095 * mib},
                {64 * mib, 128 * mib, 64 * mib},
                {4096 * mib, 0, 4096 * mib},
        };
        for (long[] c : overrides) {
            long got = com.deds.meshelium.MesheliumScaling.clampToAddressableFor(c[0], c[1]);
            if (got != c[2]) {
                throw new AssertionError("clampToAddressableFor(" + c[0] + ", limit " + c[1]
                        + ") = " + got + ", expected " + c[2]);
            }
        }
    }
}
