/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium;

import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.Config;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;

import net.minecraft.client.gui.screens.Screen;

/**
 * The way back from Meshelium's own screen to Sodium's.
 *
 * <p>Sodium reads every option's binding when its screen is constructed
 * and not again when the same instance is shown a second time, so a value
 * changed on the Meshelium screen (a Reset, a cap typed into the box) would
 * come back to Sodium's page as the old pending number. The Meshelium
 * screen is final and hands a parent that is not vanilla's Video Settings
 * back unchanged, so the refresh lives in the parent: this screen never
 * draws a frame, it reloads Sodium's values from the file and shows
 * Sodium's screen in its place from inside {@code init()}. The client's
 * screen setter assigns its field before it initialises the new screen and
 * initialises the ARGUMENT it was given, not the field, so the nested
 * switch here leaves Sodium's screen as the current one, initialised once.
 *
 * <p>The reload is skipped when the page has changes the player has not
 * applied yet, because reloading would silently throw those away; the
 * page then shows what it showed before, and the next open reads the file.
 *
 * <p>Showing a cached Sodium screen again re-runs its {@code init()}, which
 * rebuilds its lists scrolled to the top (Sodium's own General page) unless
 * the screen was created with a page to focus, and this one was not. So
 * after the swap the screen is sent back to Meshelium's page, the one the
 * player left from.
 */
final class MesheliumSodiumReturnScreen extends Screen {

    private final Screen sodiumScreen;

    MesheliumSodiumReturnScreen(Screen sodiumScreen) {
        super(sodiumScreen.getTitle());
        this.sodiumScreen = sodiumScreen;
    }

    @Override
    protected void init() {
        Config config = ConfigManager.CONFIG;
        if (config != null && !config.anyOptionChanged()) {
            config.resetAllOptionsFromBindings();
        }
        this.minecraft.gui.setScreen(this.sodiumScreen);
        if (config != null && this.sodiumScreen instanceof VideoSettingsScreen video) {
            for (ModOptions mod : config.getModOptions()) {
                if (MesheliumSodiumPage.NAMESPACE.equals(mod.configId()) && !mod.pages().isEmpty()) {
                    video.jumpToPage(mod.pages().get(0));
                    break;
                }
            }
        }
    }
}
