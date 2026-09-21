/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.neoforge;

import com.deds.meshelium.MesheliumLog;

/**
 * What Meshelium does about NeoForge's early loading screen, on
 * Minecraft 26.3: nothing, and here is why.
 *
 * <p>On 26.2 the early screen created the game's GLFW window in OpenGL
 * mode and NeoForge's patched {@code Minecraft} handed that window to the
 * game ({@code EarlyLoadingScreenController.takeOverGlfwWindow}), which
 * Vulkan cannot draw to: GLFW error 65540, neoforged/NeoForge#3230, and
 * the 26.2 copy of this class turns the screen off and dismisses it live.
 *
 * <p>NeoForge 26.3 (FML 12.0.0) does not load the early loading screen at
 * all. {@code ImmediateWindowHandler.load} in FML 11.0.16 read
 * {@code earlyWindowControl} and loaded the provider when it was true; in
 * 12.0.0 the same method, after its headless check, unconditionally nulls
 * the provider and logs "ImmediateWindowProvider not loading because
 * splash screen is disabled" (bytecode, ip 39-48; the config value is never
 * read). The dev run with {@code earlyWindowControl = true} printed exactly
 * that line and booted straight into a Vulkan SDL window. The game's window
 * is created by the graphics backend itself
 * ({@code Window.createWindow(GpuBackend, ...)}), and the NeoForge 26.3 game
 * patches contain no hand-over: the patched {@code Window} is vanilla's and
 * the patched {@code Minecraft} never names the early screen controller.
 *
 * <p>So there is nothing to rescue on this version. When NeoForge's SDL-era
 * early screen (PR #3493, "ELS renderer abstraction", unmerged, targeting
 * 26.3.x) lands, it is designed to draw after the game has created its own
 * window, which is the opposite of the 26.2 arrangement; whether it needs
 * anything from Meshelium is a question for that build, not this one.
 */
final class EarlyWindowPolicy {

    private EarlyWindowPolicy() {
    }

    static void apply() {
        MesheliumLog.LOGGER.info(
                "NeoForge early loading screen: nothing to do on Minecraft 26.3. This NeoForge's "
                        + "loader (FML 12) does not load the early loading screen at all - it ignores "
                        + "earlyWindowControl and logs that the splash screen is disabled - and the "
                        + "game creates its own window through the graphics backend, so the 26.2 "
                        + "Vulkan start-up failure (NeoForge issue #3230) cannot occur here.");
    }
}
