/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.gametest.client;

import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.Config;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.config.structure.OptionGroup;
import net.caffeinemc.mods.sodium.client.config.structure.Page;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlElement;
import net.caffeinemc.mods.sodium.client.gui.widgets.OptionListWidget;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The stand-down suite's window onto Sodium's config model and screen.
 *
 * <p>Fabric's {@code clickScreenButton} and this package's widget walk
 * ({@code collectWidgets}, {@code findButton}) see vanilla
 * {@code AbstractWidget}s and {@code LayoutElement}s. Sodium's widgets are
 * neither, so on Sodium's screen those helpers collect NOTHING, and a
 * presence check written with them passes or fails vacuously. Everything
 * here goes through the seams Sodium makes public instead: the built
 * {@code Config} (the model every control reads), the option list's
 * control elements (the real widgets, with scroll-aware geometry), and
 * the screen's own mouse routing.
 *
 * <p>Kept in its own class so the Sodium types are named in one file that
 * only the stand-down test loads; the ordinary suite never resolves them.
 */
final class SodiumPageProbe {

    static final String MESHELIUM_ENTRY = "meshelium";
    static final String SODIUM_ENTRY = "sodium";

    private SodiumPageProbe() {
    }

    static Config config() {
        Config config = ConfigManager.CONFIG;
        if (config == null) {
            throw new AssertionError("Sodium's config model is not built; registerConfigsLate has not run");
        }
        return config;
    }

    /**
     * Meshelium's entry, with Sodium's own entry checked first as the
     * control: a model with no Sodium entry is a walk that saw nothing.
     */
    static ModOptions mesheliumEntry() {
        List<ModOptions> mods = config().getModOptions();
        if (mods.isEmpty() || !SODIUM_ENTRY.equals(mods.get(0).configId())) {
            throw new AssertionError("control failed: Sodium's own entry is not first in its config model ("
                    + mods.size() + " entries)");
        }
        for (ModOptions mod : mods) {
            if (MESHELIUM_ENTRY.equals(mod.configId())) {
                return mod;
            }
        }
        throw new AssertionError("no '" + MESHELIUM_ENTRY + "' entry in Sodium's config model; "
                + "MesheliumSodiumConfigEntry did not register, or registered no page");
    }

    static List<String> pageNames(ModOptions mod) {
        List<String> names = new ArrayList<>();
        for (Page page : mod.pages()) {
            names.add(page.name().getString());
        }
        return names;
    }

    /** Group names in order; an unnamed group reads as an empty string. */
    static List<String> groupNames(ModOptions mod) {
        List<String> names = new ArrayList<>();
        for (Page page : mod.pages()) {
            for (OptionGroup group : page.groups()) {
                names.add(group.name() == null ? "" : group.name().getString());
            }
        }
        return names;
    }

    /** Every option's translated name, in the order Sodium lists them. */
    static List<String> optionNames(ModOptions mod) {
        List<String> names = new ArrayList<>();
        for (Page page : mod.pages()) {
            for (OptionGroup group : page.groups()) {
                for (Option option : group.options()) {
                    names.add(option.getName().getString());
                }
            }
        }
        return names;
    }

    static boolean isSodiumScreen(Screen screen) {
        return screen instanceof VideoSettingsScreen;
    }

    static void jumpToMesheliumPage(Screen screen) {
        ((VideoSettingsScreen) screen).jumpToPage(mesheliumEntry().pages().get(0));
    }

    static Option option(Identifier id) {
        Option option = config().getOption(id);
        if (option == null) {
            throw new AssertionError("Sodium's config model has no option " + id);
        }
        return option;
    }

    static boolean isEnabled(Identifier id) {
        return option(id).isEnabled();
    }

    static boolean hasChanged(Identifier id) {
        return option(id).hasChanged();
    }

    static Object pending(Identifier id) {
        return ((StatefulOption<?>) option(id)).getValidatedValue();
    }

    static Object applied(Identifier id) {
        return ((StatefulOption<?>) option(id)).getAppliedValue();
    }

    /** Stages a value the way a control does: nothing reaches a binding until {@link #applyAll()}. */
    @SuppressWarnings("unchecked")
    static void modify(Identifier id, Object value) {
        ((StatefulOption<Object>) option(id)).modifyValue(value);
    }

    /** Byte-for-byte the Apply button's action. */
    static void applyAll() {
        config().applyAllOptions();
    }

    /**
     * Screen-space centre of the option's control row, or null when the row
     * is outside the option list's visible band (the list scrolls; a click
     * there would land on whatever is drawn instead).
     */
    static int[] visibleControlCentre(Screen screen, Identifier id) {
        OptionListWidget list = null;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof OptionListWidget found) {
                list = found;
            }
        }
        if (list == null) {
            throw new AssertionError("no option list among Sodium's screen children");
        }
        Option target = option(id);
        for (ControlElement element : list.getControls()) {
            if (element.getOption() == target) {
                if (element.getY() < list.getY() || element.getLimitY() > list.getLimitY()) {
                    return null;
                }
                return new int[] {element.getCenterX(), element.getCenterY()};
            }
        }
        throw new AssertionError("option " + id + " has no control element in Sodium's list");
    }

    /**
     * A left click at a screen-space point, routed by the screen exactly as
     * the mouse handler would. The left button is 0 on 26.2 (GLFW) and 1 on
     * 26.3 (SDL); the constant is each version's own.
     */
    static boolean click(Screen screen, int x, int y) {
        return screen.mouseClicked(
                new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
    }
}
