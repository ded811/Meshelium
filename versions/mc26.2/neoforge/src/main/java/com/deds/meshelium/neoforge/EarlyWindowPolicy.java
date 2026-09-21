/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.neoforge;

import com.deds.meshelium.MesheliumLog;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What Meshelium does about NeoForge's early loading screen, on
 * Minecraft 26.2 (the 26.2 overlay).
 *
 * <p>NeoForge's early loading screen creates the game window in OpenGL
 * mode and hands it to the game; Vulkan cannot create a surface on it and
 * the game dies during start-up with GLFW error 65540
 * (neoforged/NeoForge#3230, fix unmerged). Two layers of rescue: this
 * class turns {@code earlyWindowControl} off in {@code config/fml.toml}
 * for every later launch when Vulkan is the chosen backend, and
 * {@code WindowEarlyDisplayMixin} + {@link EarlyWindowBypass} dismiss the
 * screen live so THIS launch survives too. On 26.3 none of this exists:
 * see that version's copy of this class.
 */
final class EarlyWindowPolicy {

    private EarlyWindowPolicy() {
    }

    static void apply() {
        boolean earlyWindow;
        try {
            earlyWindow = FMLConfig.getBoolConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL);
        } catch (Throwable t) {
            return;
        }
        if (!earlyWindow) {
            return;
        }

        if (vulkanIsSelected()) {
            boolean fixed = false;
            try {
                FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL, false);
                fixed = true;
            } catch (Throwable t) {
                MesheliumLog.LOGGER.warn("Meshelium could not turn NeoForge's early loading "
                        + "screen off automatically; do it by hand.", t);
            }
            if (fixed) {
                MesheliumLog.LOGGER.info(
                        "You are on the Vulkan backend, and NeoForge's early loading screen "
                                + "cannot coexist with it: that screen makes the game window an "
                                + "OpenGL one, and Vulkan cannot draw to it, so the game fails to "
                                + "start with GLFW error 65540 (NeoForge issue #3230, whose fix is "
                                + "not merged yet). Meshelium has set earlyWindowControl = false in "
                                + "config/fml.toml so this cannot bite you again, and it will also "
                                + "close that screen during start-up to rescue THIS launch. If a "
                                + "GLFW error appears anyway, the rescue did not apply on this "
                                + "NeoForge build - just start the game again and it will work.");
                return;
            }
        }

        MesheliumLog.LOGGER.warn(
                "NeoForge's early loading screen is ON, and it cannot coexist with Minecraft's "
                        + "Vulkan backend - which is the only backend Meshelium runs on. The early "
                        + "screen makes the window an OpenGL one, so Vulkan cannot create a surface "
                        + "on it, and the game dies during start-up with \"GLFW error 65540 ... "
                        + "requires the window to have the client API set to GLFW_NO_API\". The "
                        + "dialog blames your drivers; it is not your drivers. Set "
                        + "earlyWindowControl = false in config/fml.toml and start again. Known "
                        + "NeoForge issue neoforged/NeoForge#3230. If you are staying on OpenGL, "
                        + "ignore this: Meshelium is switched off there anyway.");
    }

    /**
     * Is Vulkan the backend the player chose? Read straight from
     * {@code options.txt}: this runs during mod construction, before the
     * {@code Options} object exists, and the choice only takes effect at
     * boot anyway.
     */
    private static boolean vulkanIsSelected() {
        try {
            Path options = FMLPaths.GAMEDIR.get().resolve("options.txt");
            if (!Files.isRegularFile(options)) {
                return false;
            }
            for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("preferredGraphicsBackend:")) {
                    continue;
                }
                String value = trimmed.substring("preferredGraphicsBackend:".length())
                        .replace("\"", "").trim();
                return "vulkan".equalsIgnoreCase(value);
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
