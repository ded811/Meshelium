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

/**
 * The Minecraft 26.3 / Fabric API 0.161 answers to the harness calls
 * whose shape differs between versions. One class per version under
 * {@code versions/mc<v>/fabric/src/gametest}; the test classes are shared
 * and call these.
 *
 * <p>Fabric's client gametest API moved from module 5.1.1 (Fabric API
 * 0.155.2+26.2) to 6.0.7 (0.161.0+26.3): {@code TestClientLevelContext}
 * is gone, a singleplayer context exposes {@code getConnection()} and the
 * chunk waits live on {@code TestServerConnection} itself, which is no
 * longer {@code AutoCloseable} (the dedicated-server variant,
 * {@code TestDedicatedServerConnection}, still is - the tests use
 * {@code var} in that try-with-resources so both versions compile).
 */
final class HarnessCompat {

    private HarnessCompat() {
    }

    /** Waits until every chunk in view is rendered, the framework's own wait. */
    static void waitForChunksRender(TestSingleplayerContext world) {
        world.getConnection().waitForChunksRender();
    }

    /** Waits until every chunk in view has arrived from the (integrated) server. */
    static void waitForChunksDownload(TestSingleplayerContext world) {
        world.getConnection().waitForChunksDownload();
    }

    /** The dedicated-server twin of {@link #waitForChunksRender(TestSingleplayerContext)}. */
    static void waitForChunksRender(TestServerConnection connection) {
        connection.waitForChunksRender();
    }

    /**
     * Vanilla's options screen. 26.3 dropped the {@code inWorld} flag (and
     * the gamemaster-permission reaction that read it); 26.2 needs it.
     */
    static OptionsScreen optionsScreen(Screen parent, Options options, boolean inWorld) {
        return new OptionsScreen(parent, options);
    }

    /**
     * The window's REAL framebuffer size, asked of the windowing layer
     * rather than read from the cached fields, so a clamped or refused
     * window shows as a difference. 26.3's window is SDL and answers
     * through {@code queryFramebufferSize()}; 26.2's was GLFW.
     */
    static int[] queryFramebufferSize(Window window) {
        Window.FramebufferSize size = window.queryFramebufferSize();
        return new int[] {size.width(), size.height()};
    }
}
