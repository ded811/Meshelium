/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium;

import com.deds.meshelium.vk.SodiumTerrainDrawer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Count in-world frames, dump what the gate and the Sodium adapter say,
 * take one screenshot, stop the client. The NeoForge proof run's
 * instrument; inert unless {@code -Dmeshelium.dev.proofFrames} is set.
 *
 * <h2>Why it exists (2026-09-14)</h2>
 * <p>The owner asked whether the Sodium render adapter could be built
 * into the NeoForge jar, and the answer was yes - but NeoForge has no
 * client gametest harness, so "it draws" had to be provable from a plain
 * {@code :neoforge:runClient}. {@link MesheliumSmokeRun} already gives
 * such a run a clean shutdown; this adds what a gametest would have
 * asserted: a line saying what state the gate reached, a line with the
 * adapter's draw counters, and a PNG the coordinator opens. Everything
 * is logged so the run can be read like a gametest, including the ways
 * it can fail to be a proof at all.
 *
 * <h2>When it counts</h2>
 * <p>Not from the first frame with a level: {@code GameRenderer.render}
 * calls {@code renderLevel} whenever {@code Minecraft.level} is non-null,
 * so {@code LevelRenderer.render} - the hook that calls this - runs while
 * the level-loading screen is still up (it dropped at render frame 12
 * on Vulkan and 16 on OpenGL, 2026-09-14; Sodium's initial builds then
 * happen INSIDE the count, which is what the frame budget is for). The
 * counter starts on
 * the first frame where {@code gui.screen()} and {@code gui.overlay()}
 * are both null and the player exists, i.e. the first frame the player
 * could see the world, and only frames on which that predicate holds are
 * counted. A pause screen in the picture would void it, so a screen is
 * never tolerated; it is NAMED instead (see below), which is what makes
 * a run that never counted diagnosable.
 *
 * <h2>Self-diagnosis</h2>
 * <ul>
 *   <li>"level frames begin (frame 0)" on the first call - the hook is
 *       armed and a level exists.</li>
 *   <li>While the predicate fails, every {@value #NOT_COUNTING_EVERY}
 *       frames: "not counting yet ... screen=X overlay=Y player=Z", so a
 *       {@code PauseScreen} (focus lost with pauseOnLostFocus on), a
 *       {@code DisconnectedScreen} (quick-play refused the world name) or
 *       a stuck {@code LevelLoadingScreen} is named in the log rather
 *       than inferred from silence.</li>
 *   <li>"counting from render frame N" once, with the absolute index.</li>
 *   <li>At capture: the gate's state AND backend, the adapter's arm, then
 *       either the drawer's counters or the reason there are none; then
 *       the screenshot result with the file's absolute path, size and
 *       modification time in the log's own timestamp pattern, or the
 *       literal {@code absent}. A PNG is evidence only if that line names
 *       it with a size above zero and an mtime later than the log's first
 *       line.</li>
 * </ul>
 * <p>The smoke run stays armed as the watchdog: a run that ends by "smoke
 * run: N ticks reached" WITHOUT a "screenshot result" line is a fail,
 * and the "not counting yet" lines say why. (Vanilla's grab path returns
 * without calling its consumer when the framebuffer is incomplete, so
 * the watchdog is not optional.)
 *
 * <h2>Why {@code schedule}, why the consumer</h2>
 * <p>The grab is scheduled with {@code Minecraft.schedule}, not called
 * from the render hook: {@code runAllTasks} runs at the top of the next
 * {@code runTick}, BEFORE that tick's {@code renderFrame}, so the main
 * target still holds the completed, presented frame; a grab inside
 * {@code LevelRenderer.render} HEAD would read a target the renderer has
 * just cleared and write a black PNG. The stop is issued FROM the grab's
 * consumer because the grab is two-stage asynchronous (GPU readback, then
 * the IO pool writes the file), and stopping earlier races the write
 * against {@code Util.shutdownExecutors()} in {@code Minecraft.close()}.
 *
 * <h2>GL-path discipline</h2>
 * <p>Pure JDK plus {@code Minecraft}, {@code Screenshot} and
 * {@code Component}; no Blaze3D, LWJGL or {@code vk.*} import is resolved
 * by this class. The adapter's counters live on
 * {@code SodiumTerrainDrawer}, which imports {@code com.mojang.blaze3d.vulkan}
 * and {@code org.lwjgl.vulkan}, so they are read through the nested
 * {@link SodiumCounters} holder, which the JVM resolves only inside the
 * {@code VULKAN_MESH_SHADERS} branch of {@link #capture()}. On the OpenGL
 * run that branch is skipped and the log says so.
 */
public final class MesheliumProofRun {

    /** In-world frames to count before capturing; 0 (the default) leaves the hook inert. */
    public static final String FRAMES_PROPERTY = "meshelium.dev.proofFrames";

    /** File name under {@code <gameDir>/screenshots/}; used verbatim, no ".png" appended. */
    public static final String SHOT_PROPERTY = "meshelium.dev.proofShot";

    /** {@code false} leaves the client running after the capture. */
    public static final String QUIT_PROPERTY = "meshelium.dev.proofQuit";

    private static final int FRAMES = Integer.getInteger(FRAMES_PROPERTY, 0);

    /**
     * True iff a positive frame count was asked for. A {@code static
     * final} resolved at class load, so the mixin's call site is
     * {@code if (false)} after JIT on every ordinary run - the
     * {@code MesheliumBenchRecorder.ARMED} pattern.
     */
    public static final boolean ARMED = FRAMES > 0;

    private static final String SHOT = System.getProperty(SHOT_PROPERTY, "meshelium-proof.png");

    private static final boolean QUIT = !"false".equalsIgnoreCase(System.getProperty(QUIT_PROPERTY));

    /** How often the "not counting yet" line repeats while the predicate fails. */
    private static final int NOT_COUNTING_EVERY = 600;

    /** The File appender's timestamp pattern in ModDevGradle's log4j config. */
    private static final DateTimeFormatter LOG_TIME =
            DateTimeFormatter.ofPattern("ddMMMyyyy HH:mm:ss.SSS", Locale.ROOT);

    /** Absolute frame index since the hook first ran (render thread only). */
    private static long frame;

    /** Frames counted while the predicate held (render thread only). */
    private static long counted;

    /** The absolute index the counting started at, or -1. */
    private static long countingFrom = -1;

    private static boolean begun;

    private static boolean captureScheduled;

    private MesheliumProofRun() {
    }

    /** Render thread, once per {@code LevelRenderer.render}; only called when {@link #ARMED}. */
    public static void onRenderFrame() {
        if (captureScheduled) {
            return;
        }
        long index = frame++;
        if (!begun) {
            begun = true;
            MesheliumLog.LOGGER.info(
                    "Meshelium proof: level frames begin (frame 0); capture after {} in-world "
                            + "frames, screenshot '{}', quit={}", FRAMES, SHOT, QUIT);
        }
        Minecraft mc = Minecraft.getInstance();
        Object screen = mc.gui.screen();
        Object overlay = mc.gui.overlay();
        boolean playerPresent = mc.player != null;
        boolean visible = screen == null && overlay == null && playerPresent;
        if (!visible) {
            if (index % NOT_COUNTING_EVERY == 0) {
                MesheliumLog.LOGGER.info(
                        "Meshelium proof: not counting yet at frame {}: screen={} overlay={} "
                                + "player={} ({} counted so far)",
                        index, name(screen), name(overlay), playerPresent, counted);
            }
            return;
        }
        if (countingFrom < 0) {
            countingFrom = index;
            MesheliumLog.LOGGER.info(
                    "Meshelium proof: counting from render frame {} (screen=null, overlay=null, "
                            + "player present)", index);
        }
        if (++counted >= FRAMES) {
            captureScheduled = true;
            mc.schedule(MesheliumProofRun::capture);
        }
    }

    /** Client thread, at the top of the tick after the Nth counted frame. */
    private static void capture() {
        Minecraft mc = Minecraft.getInstance();
        MesheliumGate.State state = MesheliumGate.state();
        MesheliumGate.State backend = MesheliumGate.backend();
        MesheliumLog.LOGGER.info(
                "Meshelium proof: frame {} reached ({} in-world frames counted from frame {}); "
                        + "gate state={} backend={} sodiumAdapterArmed={} sodiumAdapterConfigured={}",
                frame, counted, countingFrom, state, backend,
                MesheliumGate.sodiumAdapterArmed(), MesheliumGate.sodiumAdapterConfigured());
        if (backend == MesheliumGate.State.VULKAN_MESH_SHADERS) {
            try {
                MesheliumLog.LOGGER.info(SodiumCounters.line());
            } catch (Throwable t) {
                MesheliumLog.LOGGER.warn(
                        "Meshelium proof: the SodiumTerrainDrawer counters could not be read", t);
            }
        } else {
            MesheliumLog.LOGGER.info(
                    "Meshelium proof: backend is {}, not VULKAN_MESH_SHADERS; no drawer counters",
                    backend);
        }
        Object screen = mc.gui.screen();
        Object overlay = mc.gui.overlay();
        if (screen != null || overlay != null) {
            MesheliumLog.LOGGER.warn(
                    "Meshelium proof: a screen or overlay is up at capture time (screen={} "
                            + "overlay={}); the PNG shows it, not the world, and is void",
                    name(screen), name(overlay));
        }
        Path target = shotPath(mc);
        try {
            // Belt and braces with the pre-run sweep in neoforge/build.gradle:
            // even a run whose sweep was skipped cannot leave the previous
            // PNG in place once this runs.
            Files.deleteIfExists(target);
        } catch (IOException e) {
            MesheliumLog.LOGGER.warn("Meshelium proof: could not delete the previous '{}'", target, e);
        }
        MesheliumLog.LOGGER.info(
                "Meshelium proof: taking screenshot '{}' after {} in-world frames; waiting for the "
                        + "result line", SHOT, counted);
        try {
            Screenshot.grab(mc.gameDirectory, SHOT, mc.gameRenderer.mainRenderTarget(), 1,
                    MesheliumProofRun::onSaved);
        } catch (Throwable t) {
            MesheliumLog.LOGGER.error("Meshelium proof: Screenshot.grab threw; no PNG", t);
            finish(mc);
        }
    }

    /** IO-pool thread, after the file was written or after the failure. */
    private static void onSaved(Component message) {
        Minecraft mc = Minecraft.getInstance();
        Path target = shotPath(mc);
        String file;
        try {
            if (Files.exists(target)) {
                FileTime mtime = Files.getLastModifiedTime(target);
                file = target.toAbsolutePath() + " size=" + Files.size(target)
                        + " mtime=" + LOG_TIME.format(mtime.toInstant().atZone(ZoneId.systemDefault()))
                        + " (" + mtime + ")";
            } else {
                file = "absent";
            }
        } catch (IOException e) {
            file = "unreadable: " + e;
        }
        MesheliumLog.LOGGER.info("Meshelium proof: screenshot result: {}; file {}",
                message.getString(), file);
        finish(mc);
    }

    private static void finish(Minecraft mc) {
        if (!QUIT) {
            MesheliumLog.LOGGER.info("Meshelium proof: -D{}=false, leaving the client running",
                    QUIT_PROPERTY);
            return;
        }
        MesheliumLog.LOGGER.warn(
                "Meshelium proof: stopping the client. Everything logged after this line is teardown.");
        // The quit button's own path, as MesheliumSmokeRun does; schedule()
        // because this may be the IO pool and must not tear the loop down
        // from a foreign thread.
        mc.schedule(mc::stop);
    }

    private static Path shotPath(Minecraft mc) {
        return new File(new File(mc.gameDirectory, "screenshots"), SHOT).toPath();
    }

    private static String name(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    /**
     * The one place this file names {@code SodiumTerrainDrawer}. A nested
     * holder so that the outer class's constant pool carries no reference
     * to it: the JVM resolves this class - and with it the drawer, whose
     * static initialiser is the OpenGL run's own subject - only when
     * {@link #line()} first executes, which {@link #capture()} allows only
     * under {@code VULKAN_MESH_SHADERS}.
     */
    static final class SodiumCounters {

        private SodiumCounters() {
        }

        static String line() {
            return "Meshelium proof: SodiumTerrainDrawer"
                    + " rung=" + SodiumTerrainDrawer.rung()
                    + " framesOwned=" + SodiumTerrainDrawer.framesOwned()
                    + " framesAttempted=" + SodiumTerrainDrawer.framesAttempted()
                    + " occlusionFrames=" + SodiumTerrainDrawer.occlusionFrames()
                    + " framesDrawn=" + SodiumTerrainDrawer.framesDrawn()
                    + " totalSectionsDrawn=" + SodiumTerrainDrawer.totalSectionsDrawn()
                    + " lastSectionsDrawnFrame=" + SodiumTerrainDrawer.lastSectionsDrawnFrame()
                    + " lastRegionsDrawn=" + SodiumTerrainDrawer.lastRegionsDrawn()
                    + " lastDrawCommands=" + SodiumTerrainDrawer.lastDrawCommands()
                    + " frameDeclinesTotal=" + SodiumTerrainDrawer.frameDeclinesTotal()
                    + " declines=" + SodiumTerrainDrawer.frameDeclines()
                    + " broken=" + SodiumTerrainDrawer.broken()
                    + " error=" + SodiumTerrainDrawer.error()
                    + " gpuDrawBroken=" + SodiumTerrainDrawer.gpuDrawBroken()
                    + " gpuDrawError=" + SodiumTerrainDrawer.gpuDrawError()
                    + " occlusionBroken=" + SodiumTerrainDrawer.occlusionBroken()
                    + " occlusionError=" + SodiumTerrainDrawer.occlusionError()
                    + " gpuDrawEnabled=" + SodiumTerrainDrawer.gpuDrawEnabled()
                    + " occlusionEnabled=" + SodiumTerrainDrawer.occlusionEnabled();
        }
    }
}
