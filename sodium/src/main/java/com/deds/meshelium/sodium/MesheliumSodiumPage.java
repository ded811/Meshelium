/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumExtendedRd;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.gui.MesheliumOptionsScreen;
import com.deds.meshelium.vk.SodiumTerrainDrawer;

import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ExternalButtonOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/**
 * Meshelium's page inside Sodium's video settings.
 *
 * <p>With Sodium installed, "Video Settings..." opens Sodium's screen, so
 * until 1.6.2 Meshelium's settings hung off a "Meshelium..." row at the
 * bottom of vanilla's Options screen ({@code OptionsScreenMixin}, deleted
 * with this class's arrival). Owner directive 2026-09-20: "can we please
 * do that when sodiums installed instead of the seperate menu button on
 * the menu area ... feel free to rearange it to fit the sodium graphics
 * menu better." Sodium's config API is built for exactly this: a mod
 * registers pages of options under its own header in the list on the
 * left, and Sodium draws the controls, keeps the pending values, and runs
 * Apply and Undo. This class registers that page on the {@code Meshelium}
 * entry {@link MesheliumSodiumConfigEntry} already owns for the
 * render-distance overlay; without a page that entry was invisible, since
 * Sodium's list skips a mod with no pages.
 *
 * <h2>Which rows</h2>
 * Exactly the rows the vanilla Meshelium screens build under Sodium, no
 * more: Sodium builds the chunks, so every setting that only Meshelium's
 * own chunk builder reads (the master switch, occlusion and its crossover,
 * greedy meshing, the plant cull, the leaf tiers, the two memory rows) was
 * taken off the Sodium shape on 2026-09-16 rather than shown greyed out,
 * and {@code MesheliumSodiumStandDownTest} asserts their absence. The page
 * mirrors that decision: a row that cannot act is furniture. What is left
 * is the everyday group (Distance Cap, GPU Visibility, Distance Fog, Fog
 * Ends At) and the advanced group (the sub-pixel cull, debug logging, the
 * backend popup), plus a button onto Meshelium's own screen for the things
 * a list of options cannot express: the live status line, the installed
 * Sodium version, the gate banner with [Enable Vulkan], and the two-click
 * Reset that also resets the rows this page does not show.
 *
 * <h2>Sodium's apply model, and where each side effect went</h2>
 * Sodium stages a change in the control and reaches the binding only on
 * Apply (or when validation rewrites a stored value at game load and at
 * screen open, with no player behind it). So a binding here writes its
 * {@link MesheliumConfig} field, the ONE storage handler saves the file
 * once per Apply after every changed binding has written, and GPU
 * Visibility's push into the live drawer is an apply hook, which Sodium
 * runs after the flush and only when that row changed. The drawer class is
 * a Vulkan import, so its call sits behind
 * {@link MesheliumGate#sodiumAdapterArmed()} in its own method and the
 * class is never loaded on an OpenGL session, same discipline as the
 * vanilla screen's handler. Everything else is read per frame by its
 * resolver.
 *
 * <p>The cap is the one binding with a side effect, and it has to be
 * synchronous. Sodium applies bindings in its option order, and the
 * render-distance option Meshelium overlays is re-inserted LAST by that
 * overlay ({@code Config.exchangeOption} removes and re-puts it), so the
 * cap's binding runs before Sodium hands the new render distance to
 * vanilla's {@code OptionInstance.set}. That setter rejects a value outside
 * the option's CURRENT range and stores the initial 12 instead, and the
 * only thing that widens the range is {@link MesheliumExtendedRd#onConfigChanged}.
 * A player who raises the cap and the distance in one Apply, the obvious
 * flow, would get 12 if the widening waited for an apply hook (hooks run
 * after every binding). So the cap's setter widens the range itself; the
 * call is what the vanilla screen's slider makes, and a no-op when the
 * ceiling has not moved. The overlay's own binding clamps the distance to
 * the current range as well, so even a reordering by a future Sodium
 * cannot reach 12 ({@link MesheliumSodiumConfigEntry}).
 *
 * <h2>Enabled state after the gate decides</h2>
 * This runs at {@code Minecraft.onGameLoadFinished}, before the gate has
 * decided anything, so nothing gate-dependent may be captured as a plain
 * boolean. The rows that only act while the adapter draws use an enabled
 * PROVIDER declared on {@link ConfigState#UPDATE_ON_REBUILD}: Sodium
 * re-evaluates it every time its screen is built, which is every open, and
 * by the first open the gate decided long ago. Fog Ends At depends on the
 * fog mode's PENDING value, so it follows the cycle button before Apply.
 * A disabled row is drawn Sodium's way: the name struck through and the
 * control hidden (an unset hidden-when-disabled flag means hidden), with
 * the tooltip saying why; that is what Sodium's own dependent rows do, so
 * the page reads like the rest of the screen.
 *
 * <h2>Ranges</h2>
 * Every stored value the config can legitimately hold must pass the
 * validator, or Sodium rewrites it at screen open (D-019). The ranges are
 * therefore step 1, because the vanilla screen's boxes accept any integer,
 * and they are {@link MesheliumSodiumRange}s, which bring a value outside
 * the range to the edge rather than to the default. The cap slider stops
 * at {@link MesheliumConfig#SLIDER_MAX_RENDER_DISTANCE}: under Sodium the
 * cap does one thing, widen Sodium's render-distance slider, and the
 * overlay never widens it past that, so a stored cap above it did nothing
 * and now reads as the ceiling: clamped in memory when Sodium builds its
 * model at game load and at every screen open, and on disk at the next
 * Apply, since Sodium flushes storage handlers only from Apply. The
 * vanilla screen's custom box stops at the same number under Sodium.
 */
public final class MesheliumSodiumPage {

    static final String NAMESPACE = "meshelium";

    public static final Identifier DISTANCE_CAP = id("general.distance_cap");
    public static final Identifier GPU_VISIBILITY = id("general.gpu_visibility");
    public static final Identifier FOG_MODE = id("general.fog_mode");
    public static final Identifier FOG_END = id("general.fog_end");
    public static final Identifier SUB_PIXEL_CULL = id("advanced.sub_pixel_cull");
    public static final Identifier SODIUM_OCCLUSION = id("advanced.sodium_occlusion");
    public static final Identifier RECORD_REPAIR = id("advanced.record_repair");
    public static final Identifier DEBUG_STATS = id("advanced.debug_stats");
    public static final Identifier BACKEND_POPUP = id("advanced.backend_popup");
    public static final Identifier FULL_SCREEN = id("advanced.full_screen");

    /** White shape on transparency; Sodium tints it with the theme colour. */
    static final Identifier ICON = Identifier.fromNamespaceAndPath(NAMESPACE, "textures/gui/config-icon.png");

    /** The blue of the mod icon, as ARGB; Sodium derives the lighter and darker shades itself. */
    static final int THEME_BASE = 0xFF3D7CFF;

    /** One handler for the whole page: Sodium calls it once per Apply, after every changed binding wrote. */
    static final StorageEventHandler STORAGE = () -> MesheliumConfig.get().save();

    /** A fresh instance is the schema's defaults, the same source {@code resetToDefaults} copies from. */
    private static final MesheliumConfig DEFAULTS = new MesheliumConfig();

    private MesheliumSodiumPage() {
    }

    static void addTo(ConfigBuilder builder, ModOptionsBuilder mod) {
        mod.setIcon(ICON);
        mod.setColorTheme(builder.createColorTheme().setBaseThemeRGB(THEME_BASE));

        OptionGroupBuilder everyday = builder.createOptionGroup();
        everyday.addOption(distanceCap(builder));
        everyday.addOption(gpuVisibility(builder));
        everyday.addOption(fogMode(builder));
        everyday.addOption(fogEnd(builder));

        OptionGroupBuilder advanced = builder.createOptionGroup()
                .setName(Component.translatable("meshelium.options.sodium_group.advanced"));
        advanced.addOption(subPixelCull(builder));
        // The troubleshooting rows: the big switch first, then the repair.
        // The two one-frame holds were rows during the flash hunt and are
        // launch options only now (meshelium.sodium.stampHold, on, and
        // meshelium.sodium.regionHold, off): nobody playing needs either.
        advanced.addOption(sodiumOcclusion(builder));
        advanced.addOption(recordRepair(builder));
        advanced.addOption(debugStats(builder));
        advanced.addOption(backendPopup(builder));

        // The door to Meshelium's own screen, last and on its own, so it
        // reads as the page's footer rather than as an advanced setting.
        OptionGroupBuilder more = builder.createOptionGroup();
        more.addOption(fullScreen(builder));

        OptionPageBuilder page = builder.createOptionPage()
                .setName(Component.translatable("meshelium.options.sodium_page"));
        page.addOptionGroup(everyday);
        page.addOptionGroup(advanced);
        page.addOptionGroup(more);
        mod.addPage(page);
    }

    // ---- everyday --------------------------------------------------------

    private static IntegerOptionBuilder distanceCap(ConfigBuilder builder) {
        IntegerOptionBuilder cap = builder.createIntegerOption(DISTANCE_CAP);
        cap.setName(Component.translatable("meshelium.options.max_rd"));
        cap.setTooltip(Component.translatable("meshelium.options.tooltip.max_rd.sodium"));
        cap.setStorageHandler(STORAGE);
        cap.setValidator(new MesheliumSodiumRange(MesheliumConfig.MIN_MAX_RENDER_DISTANCE,
                MesheliumConfig.SLIDER_MAX_RENDER_DISTANCE, 1));
        cap.setValueFormatter(MesheliumSodiumPage::formatCap);
        cap.setDefaultValue(DEFAULTS.maxRenderDistance);
        cap.setBinding(MesheliumSodiumPage::saveCap,
                () -> Math.clamp(MesheliumConfig.get().maxRenderDistance,
                        MesheliumConfig.MIN_MAX_RENDER_DISTANCE, MesheliumConfig.MAX_MAX_RENDER_DISTANCE));
        cap.setEnabled(!capOverridden());
        return cap;
    }

    private static BooleanOptionBuilder gpuVisibility(ConfigBuilder builder) {
        BooleanOptionBuilder gpu = builder.createBooleanOption(GPU_VISIBILITY);
        gpu.setName(Component.translatable("meshelium.options.sodium_gpu"));
        gpu.setTooltip(value -> heldAware("meshelium.options.tooltip.sodium_gpu"));
        gpu.setStorageHandler(STORAGE);
        gpu.setDefaultValue(DEFAULTS.sodiumGpuVisibility);
        gpu.setBinding(value -> MesheliumConfig.get().sodiumGpuVisibility = value,
                () -> MesheliumConfig.get().sodiumGpuVisibility);
        gpu.setEnabledProvider(state -> MesheliumGate.sodiumAdapterArmed()
                && System.getProperty("meshelium.sodium.gpuDraw") == null, ConfigState.UPDATE_ON_REBUILD);
        gpu.setApplyHook(state -> applyGpuVisibility());
        return gpu;
    }

    private static EnumOptionBuilder<MesheliumConfig.FogMode> fogMode(ConfigBuilder builder) {
        EnumOptionBuilder<MesheliumConfig.FogMode> fog =
                builder.createEnumOption(FOG_MODE, MesheliumConfig.FogMode.class);
        fog.setName(Component.translatable("meshelium.options.fog"));
        fog.setTooltip(Component.translatable("meshelium.options.tooltip.fog"));
        fog.setStorageHandler(STORAGE);
        fog.setElementNameProvider(MesheliumSodiumPage::fogModeName);
        fog.setDefaultValue(DEFAULTS.fogMode);
        fog.setBinding(value -> MesheliumConfig.get().fogMode = value, () -> MesheliumConfig.get().fogMode);
        fog.setEnabled(!fogOverridden());
        return fog;
    }

    private static IntegerOptionBuilder fogEnd(ConfigBuilder builder) {
        IntegerOptionBuilder end = builder.createIntegerOption(FOG_END);
        end.setName(Component.translatable("meshelium.options.fog_end"));
        end.setTooltip(Component.translatable("meshelium.options.tooltip.fog_end"));
        end.setStorageHandler(STORAGE);
        end.setValidator(new MesheliumSodiumRange(MesheliumConfig.MIN_FOG_END_PERCENT,
                MesheliumConfig.MAX_FOG_END_PERCENT, 1));
        end.setValueFormatter(value -> Component.translatable("meshelium.options.fog_end.percent", value));
        end.setDefaultValue(DEFAULTS.fogEndPercent);
        end.setBinding(value -> MesheliumConfig.get().fogEndPercent = value,
                () -> Math.clamp(MesheliumConfig.get().fogEndPercent,
                        MesheliumConfig.MIN_FOG_END_PERCENT, MesheliumConfig.MAX_FOG_END_PERCENT));
        // Live only while the fog follows the view, read from the mode's
        // PENDING value so the row follows the cycle button before Apply.
        end.setEnabledProvider(state -> !fogOverridden()
                && state.readEnumOption(FOG_MODE, MesheliumConfig.FogMode.class) == MesheliumConfig.FogMode.SCALED,
                FOG_MODE);
        return end;
    }

    // ---- advanced --------------------------------------------------------

    private static IntegerOptionBuilder subPixelCull(ConfigBuilder builder) {
        IntegerOptionBuilder cull = builder.createIntegerOption(SUB_PIXEL_CULL);
        cull.setName(Component.translatable("meshelium.options.detail_cull"));
        cull.setTooltip(value -> heldAware("meshelium.options.tooltip.detail_cull"));
        cull.setStorageHandler(STORAGE);
        cull.setValidator(new MesheliumSodiumRange(MesheliumConfig.MIN_DETAIL_CULL_CHUNKS,
                MesheliumConfig.MAX_DETAIL_CULL_CHUNKS, 1));
        cull.setValueFormatter(MesheliumSodiumPage::formatCullChunks);
        cull.setDefaultValue(DEFAULTS.subPixelCullChunks);
        cull.setBinding(value -> MesheliumConfig.get().subPixelCullChunks = value,
                () -> Math.clamp(MesheliumConfig.get().subPixelCullChunks,
                        MesheliumConfig.MIN_DETAIL_CULL_CHUNKS, MesheliumConfig.MAX_DETAIL_CULL_CHUNKS));
        cull.setEnabledProvider(state -> MesheliumGate.sodiumAdapterArmed(), ConfigState.UPDATE_ON_REBUILD);
        return cull;
    }

    private static BooleanOptionBuilder sodiumOcclusion(ConfigBuilder builder) {
        BooleanOptionBuilder occ = builder.createBooleanOption(SODIUM_OCCLUSION);
        occ.setName(Component.translatable("meshelium.options.sodium_occlusion"));
        occ.setTooltip(value -> heldAware("meshelium.options.tooltip.sodium_occlusion"));
        occ.setStorageHandler(STORAGE);
        occ.setDefaultValue(DEFAULTS.sodiumOcclusion);
        occ.setBinding(value -> MesheliumConfig.get().sodiumOcclusion = value,
                () -> MesheliumConfig.get().sodiumOcclusion);
        occ.setEnabledProvider(state -> MesheliumGate.sodiumAdapterArmed()
                && System.getProperty("meshelium.sodium.occlusion") == null,
                ConfigState.UPDATE_ON_REBUILD);
        // The same refresh the GPU Visibility row uses: the occlusion
        // lever's effective value is derived from that row's, so it is the
        // one call that re-reads both.
        occ.setApplyHook(state -> applyGpuVisibility());
        return occ;
    }

    private static BooleanOptionBuilder recordRepair(ConfigBuilder builder) {
        BooleanOptionBuilder repair = builder.createBooleanOption(RECORD_REPAIR);
        repair.setName(Component.translatable("meshelium.options.sodium_record_repair"));
        repair.setTooltip(value -> heldAware("meshelium.options.tooltip.sodium_record_repair"));
        repair.setStorageHandler(STORAGE);
        repair.setDefaultValue(DEFAULTS.sodiumRemirrorOnRefusal);
        repair.setBinding(value -> MesheliumConfig.get().sodiumRemirrorOnRefusal = value,
                () -> MesheliumConfig.get().sodiumRemirrorOnRefusal);
        repair.setEnabledProvider(state -> MesheliumGate.sodiumAdapterArmed()
                && System.getProperty("meshelium.sodium.remirrorOnRefusal") == null,
                ConfigState.UPDATE_ON_REBUILD);
        return repair;
    }

    private static BooleanOptionBuilder debugStats(ConfigBuilder builder) {
        BooleanOptionBuilder stats = builder.createBooleanOption(DEBUG_STATS);
        stats.setName(Component.translatable("meshelium.options.debug_stats"));
        stats.setTooltip(value -> heldAware("meshelium.options.tooltip.debug_stats"));
        stats.setStorageHandler(STORAGE);
        stats.setDefaultValue(DEFAULTS.debugStats);
        stats.setBinding(value -> MesheliumConfig.get().debugStats = value,
                () -> MesheliumConfig.get().debugStats);
        stats.setEnabledProvider(state -> MesheliumGate.sodiumAdapterArmed()
                && System.getProperty("meshelium.debugStats") == null, ConfigState.UPDATE_ON_REBUILD);
        return stats;
    }

    private static BooleanOptionBuilder backendPopup(ConfigBuilder builder) {
        BooleanOptionBuilder popup = builder.createBooleanOption(BACKEND_POPUP);
        popup.setName(Component.translatable("meshelium.options.popup"));
        popup.setTooltip(Component.translatable("meshelium.options.tooltip.popup")
                .append(" ").append(Component.translatable("meshelium.options.applies.restart")));
        popup.setStorageHandler(STORAGE);
        popup.setDefaultValue(DEFAULTS.showVulkanPrompt);
        // Same three writes as the vanilla Advanced screen: turning the
        // prompt back on re-arms the once-per-install notices with it.
        popup.setBinding(value -> {
            MesheliumConfig config = MesheliumConfig.get();
            config.showVulkanPrompt = value;
            config.noMeshShaderNoticeShown = !value;
            config.vulkanFailedNoticeShown = !value;
        }, () -> MesheliumConfig.get().showVulkanPrompt);
        return popup;
    }

    private static ExternalButtonOptionBuilder fullScreen(ConfigBuilder builder) {
        ExternalButtonOptionBuilder open = builder.createExternalButtonOption(FULL_SCREEN);
        open.setName(Component.translatable("meshelium.options.open"));
        open.setTooltip(Component.translatable("meshelium.options.tooltip.sodium_full"));
        open.setScreenConsumer(MesheliumSodiumPage::openFullScreen);
        return open;
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * The cap's binding: the field, then vanilla's range, in that order and
     * before Sodium's render-distance binding runs (class javadoc). The
     * same call the vanilla screen's slider makes.
     */
    private static void saveCap(Integer value) {
        MesheliumConfig.get().maxRenderDistance = value;
        MesheliumExtendedRd.onConfigChanged(Minecraft.getInstance());
    }

    /**
     * The cap the render-distance overlay should follow: the page's
     * pending value, or the {@code -D} override when one pins it (the row
     * is disabled then, so the pending value is whatever the file held).
     */
    static int effectiveCap(ConfigState state) {
        return capOverridden() ? MesheliumConfig.maxRenderDistanceConfigured() : state.readIntOption(DISTANCE_CAP);
    }

    static boolean capOverridden() {
        return System.getProperty("meshelium.maxRenderDistance") != null;
    }

    private static boolean fogOverridden() {
        return System.getProperty("meshelium.fogMode") != null;
    }

    private static Component formatCap(int value) {
        return value == MesheliumConfig.MIN_MAX_RENDER_DISTANCE
                ? Component.translatable("meshelium.options.max_rd.vanilla")
                : Component.translatable("meshelium.options.cull.chunks", value);
    }

    private static Component formatCullChunks(int value) {
        return value == MesheliumConfig.MIN_DETAIL_CULL_CHUNKS
                ? Component.translatable("meshelium.options.cull.off")
                : Component.translatable("meshelium.options.cull.chunks", value);
    }

    private static Component fogModeName(MesheliumConfig.FogMode mode) {
        return switch (mode) {
            case VANILLA -> Component.translatable("meshelium.options.fog.vanilla");
            case SCALED -> Component.translatable("meshelium.options.fog.scaled");
            case OFF -> Component.translatable("meshelium.options.fog.off");
        };
    }

    /**
     * The row's tooltip, with the held sentence appended while the adapter
     * is not drawing. Evaluated when the tooltip is shown, not at
     * registration, so it tracks the gate.
     */
    private static Component heldAware(String key) {
        MutableComponent text = Component.translatable(key);
        if (!MesheliumGate.sodiumAdapterArmed()) {
            text.append(" ").append(Component.translatable("meshelium.options.applies.sodium_gpu_held"));
        }
        return text;
    }

    /**
     * Own method so the Vulkan-importing drawer class is resolved only when
     * this executes, which the guard limits to a session where it already
     * draws.
     */
    private static void applyGpuVisibility() {
        if (MesheliumGate.sodiumAdapterArmed()) {
            SodiumTerrainDrawer.applyConfiguredGpuVisibility();
        }
    }

    /**
     * Meshelium's own screen, with Sodium's screen behind it. The parent is
     * a {@link MesheliumSodiumReturnScreen} rather than Sodium's screen
     * itself, so that Done reloads Sodium's pending values from the file
     * before Sodium's screen comes back; otherwise a Reset or a typed cap on
     * the Meshelium screen would leave this page showing the old numbers.
     */
    private static void openFullScreen(Screen sodiumScreen) {
        Minecraft.getInstance().gui.setScreen(
                new MesheliumOptionsScreen(new MesheliumSodiumReturnScreen(sodiumScreen)));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }
}
