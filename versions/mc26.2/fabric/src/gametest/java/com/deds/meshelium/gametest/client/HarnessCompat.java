/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.gametest.client;

import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

/**
 * The Minecraft 26.2 / Fabric API 0.155 answers to the harness calls
 * whose shape differs between versions (the 26.2 overlay). One class per
 * version under {@code versions/mc<v>/fabric/src/gametest}; the test
 * classes are shared and call these.
 */
final class HarnessCompat {

    private HarnessCompat() {
    }

    /** Waits until every chunk in view is rendered, the framework's own wait. */
    static void waitForChunksRender(TestSingleplayerContext world) {
        world.getClientLevel().waitForChunksRender();
    }

    /** Waits until every chunk in view has arrived from the (integrated) server. */
    static void waitForChunksDownload(TestSingleplayerContext world) {
        world.getClientLevel().waitForChunksDownload();
    }

    /** The dedicated-server twin of {@link #waitForChunksRender(TestSingleplayerContext)}. */
    static void waitForChunksRender(TestServerConnection connection) {
        connection.getClientLevel().waitForChunksRender();
    }

    /** Vanilla's options screen; 26.2's constructor takes the in-world flag. */
    static OptionsScreen optionsScreen(Screen parent, Options options, boolean inWorld) {
        return new OptionsScreen(parent, options, inWorld);
    }

    /**
     * The window's REAL framebuffer size, queried straight from GLFW so a
     * clamped or refused window shows as a difference rather than reading
     * back the cached fields.
     */
    static int[] queryFramebufferSize(Window window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window.handle(), w, h);
            return new int[] {w.get(0), h.get(0)};
        }
    }
}
