package com.deds.meshelium.neoforge;

import com.deds.meshelium.MesheliumLog;

import net.neoforged.fml.loading.EarlyLoadingScreenController;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;

/**
 * Dismantles NeoForge's early loading screen at the one instant it can be
 * done safely, so a Vulkan player's very first launch does not crash.
 *
 * <h2>The crash</h2>
 * <p>NeoForge's early loading screen owns a GLFW window with an OpenGL
 * context, and hands that same window to Minecraft. Vulkan then asks GLFW
 * for a surface on it and is refused — <em>"Vulkan: Window surface
 * creation requires the window to have the client API set to
 * GLFW_NO_API"</em>, GLFW error 65540 — and the game dies during startup
 * behind a dialog that blames the player's graphics drivers. It is
 * NeoForge issue #3230; the fix for it, PR #3259, reworks the whole early
 * screen onto Blaze3D and is still unmerged, so every Vulkan player on
 * NeoForge 26.2 hits this today.
 *
 * <p>Meshelium runs on Vulkan and nothing else, so every one of its
 * NeoForge users walks into it.
 *
 * <h2>Why here, and not somewhere more polite</h2>
 * <p>Two earlier places were tried and are dead ends, recorded here so
 * nobody spends the afternoon again:
 *
 * <ul>
 *   <li><b>A {@code GraphicsBootstrapper} service.</b> It runs at exactly
 *       the right moment — before NeoForge reads {@code
 *       earlyWindowControl} — but {@code ServiceLoaderUtil.loadEarlyServices}
 *       calls {@code ILaunchContext.addLocated} on the jar every service
 *       came from, and mod discovery then skips located jars. A mod that
 *       ships that service claims itself as a service and is never loaded
 *       as a mod. Measured: the bootstrap ran, the early window was
 *       skipped, and Meshelium vanished from the mod list entirely.</li>
 *   <li><b>The mod constructor.</b> It runs before the game window is
 *       created, but long after the early window exists, and on a
 *       mod-loading worker thread where GLFW calls are not allowed.</li>
 * </ul>
 *
 * <p>What is left is the call site itself. {@code Window.createGlfwWindow}
 * asks {@code EarlyLoadingScreenController.current()} whether to adopt an
 * existing window or make its own, and it asks on the main thread,
 * immediately after the backend has set its window hints. Returning null
 * there sends it down the branch it already has for players who never had
 * an early screen. That is what {@link WindowEarlyDisplayMixin} does, and
 * this class does the dismantling behind it.
 *
 * <h2>The order is the whole design</h2>
 * <p>Three things have to be true before the game can make its own
 * window, and they were learned one crash at a time:
 *
 * <ol>
 *   <li><b>The screen must stop being ticked.</b> NeoForge's client mod
 *       loader captured a direct reference to the screen's tick during
 *       startup and never consults the provider field, so clearing that
 *       field does not stop it. A tick that repaints has to draw, and
 *       under Vulkan the main thread has no OpenGL binding at all, so it
 *       dies in native code and takes the JVM with it — no crash report,
 *       nothing a player could act on. Setting the screen's own
 *       {@code closed} flag is what makes the tick harmless, and it is
 *       done first because it is reversible and stops nothing else.</li>
 *   <li><b>The provider must be cleared</b>, so everything that does go
 *       through the field sees the same state NeoForge sets when {@code
 *       earlyWindowControl} is off — a configuration it supports and every
 *       consumer already null-checks.</li>
 *   <li><b>The handover may happen only once.</b> {@code
 *       takeOverGlfwWindow} clears the window's size callback and closes
 *       it, so a second call dies on a null. If this method were to take
 *       the window and then report failure, the game's own call a few
 *       instructions later would be that second call. So the handover is
 *       the last reversible step, and after it this method never reports
 *       failure — it finishes the job.</li>
 * </ol>
 *
 * <p>NeoForge's own {@code close()} is deliberately not called. It frees
 * the screen's OpenGL objects through a binding the main thread does not
 * have under Vulkan — the first attempt here did call it and died on
 * {@code No GLCapabilities instance set for the current thread} — and
 * destroying the window frees those objects anyway.
 *
 * <p><b>But skipping it is not free, and an earlier version of this
 * comment claimed it was.</b> Stock NeoForge does reach {@code close()}:
 * {@code ClientModLoader.finish()}, called from {@code Minecraft.<init>}
 * after the window loop, closes the screen if it is a {@code
 * DisplayWindow}. Setting {@code closed} first makes that call return at
 * its first instruction, so the two things {@code close()} does besides
 * OpenGL — shutting down the {@code fml-loadingscreen} scheduler and
 * releasing the renderer — never happen. The thread is a daemon and will
 * not hold the JVM open, and what is stranded is small (an idle executor,
 * and single-digit kilobytes of off-heap vertex buffer that only the
 * OpenGL-bound path could have freed). Small is not nothing, and stock
 * reclaims it, so {@link #shutdownScheduler} does the half that can be
 * done without a GL binding. What it cannot reach is documented rather
 * than described as absent.
 *
 * <p>If any of it fails before the handover, the game is left exactly as
 * it was: the player still gets the crash on this launch, and the config
 * that {@link MesheliumNeoForge} has already rewritten still makes the
 * next one work. Degrading to the old behaviour is an acceptable outcome.
 * Leaving the game half-dismantled is not.
 */
public final class EarlyWindowBypass {

    private EarlyWindowBypass() {
    }

    /**
     * Takes the early loading screen apart and destroys its window.
     *
     * <p>Must be called on the main thread, from inside window creation,
     * and at most once per launch.
     *
     * @return true when the early screen is gone and the caller should
     *         create a fresh window; false when nothing was changed and
     *         the caller must carry on exactly as NeoForge intended
     */
    public static boolean dismiss(EarlyLoadingScreenController early) {
        // Resolve every reflective handle before touching anything, so a
        // NeoForge this code does not recognise costs one log line and no
        // behaviour at all.
        Field providerField;
        Field closedField;
        Object previousProvider;
        boolean previousClosed;
        try {
            providerField = Class.forName("net.neoforged.fml.loading.ImmediateWindowHandler")
                    .getDeclaredField("provider");
            providerField.setAccessible(true);
            closedField = early.getClass().getDeclaredField("closed");
            closedField.setAccessible(true);
            if (closedField.getType() != boolean.class) {
                throw new NoSuchFieldException(
                        "closed is a " + closedField.getType() + ", not a boolean");
            }
            previousProvider = providerField.get(null);
            previousClosed = closedField.getBoolean(early);
        } catch (Throwable t) {
            report(t);
            return false;
        }

        // Both writes are reversible, and neither stops the screen from
        // working if we have to back out.
        try {
            closedField.setBoolean(early, true);
            providerField.set(null, null);
        } catch (Throwable t) {
            restore(providerField, previousProvider, closedField, early, previousClosed);
            report(t);
            return false;
        }

        long window;
        try {
            // Stops the screen's render thread and hands back its window.
            // The last point at which backing out is still possible.
            window = early.takeOverGlfwWindow();
        } catch (Throwable t) {
            restore(providerField, previousProvider, closedField, early, previousClosed);
            report(t);
            return false;
        }

        // Committed. The screen is inert and its window is ours.
        if (window != 0L && window != -1L) {
            GLFW.glfwMakeContextCurrent(0L);
            GLFW.glfwDestroyWindow(window);
        }
        shutdownScheduler(early);
        MesheliumLog.LOGGER.info(
                "Meshelium closed NeoForge's early loading screen so Minecraft can open a Vulkan "
                        + "window. NeoForge hands that screen's OpenGL window straight to the game, "
                        + "and Vulkan cannot draw to one, which is why NeoForge and Vulkan fail to "
                        + "start together (NeoForge issue #3230). The game window opens a moment "
                        + "later than usual and there is no loading screen before it; nothing else "
                        + "changes.");
        return true;
    }

    /**
     * Stops the early screen's {@code fml-loadingscreen} executor, which
     * is the part of NeoForge's {@code close()} that needs no OpenGL
     * binding and would otherwise never run — see the class note.
     *
     * <p>Best effort by design, and deliberately AFTER the commit point:
     * the bypass has already succeeded by the time this is called, and an
     * executor left running is a far smaller problem than the start-up
     * crash this class exists to prevent. So a failure here is logged at
     * debug and changes nothing. The thread is a daemon, so the worst case
     * is one idle thread for the session, not a game that will not close.
     */
    private static void shutdownScheduler(EarlyLoadingScreenController early) {
        try {
            Field scheduler = early.getClass().getDeclaredField("renderScheduler");
            scheduler.setAccessible(true);
            Object value = scheduler.get(early);
            if (value instanceof ExecutorService executor) {
                // shutdownNow, not shutdown: the repeating render task was
                // already stopped by the handover, and anything still
                // queued would only try to draw to a window that no longer
                // exists.
                executor.shutdownNow();
            }
        } catch (Throwable t) {
            MesheliumLog.LOGGER.debug(
                    "Meshelium could not stop NeoForge's early loading-screen scheduler. Harmless: "
                            + "it is an idle daemon thread for the rest of the session.", t);
        }
    }

    private static void report(Throwable t) {
        MesheliumLog.LOGGER.warn(
                "Meshelium could not detach NeoForge's early loading screen, so this launch will "
                        + "fail with GLFW error 65540 (NeoForge issue #3230). earlyWindowControl "
                        + "has already been turned off in config/fml.toml, so starting the game "
                        + "again will work.", t);
    }

    private static void restore(Field providerField, Object previousProvider,
            Field closedField, Object early, boolean previousClosed) {
        try {
            providerField.set(null, previousProvider);
            closedField.setBoolean(early, previousClosed);
        } catch (Throwable t) {
            MesheliumLog.LOGGER.error(
                    "Meshelium could not put NeoForge's early loading screen back after a failed "
                            + "detach. The game will most likely fail to start; please report this.",
                    t);
        }
    }
}
