/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumPlatform;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;

import net.minecraft.resources.Identifier;

/**
 * Widens Sodium's render-distance slider to Meshelium's ceiling.
 *
 * <h2>The problem this solves (recon 2026-09-06, javap-verified)</h2>
 * <p>Sodium 0.9.2 replaces vanilla's Video Settings screen with its own,
 * and its render-distance option is a second front-end onto vanilla's
 * {@code Options.renderDistance()}: it reads and writes the vanilla
 * {@code OptionInstance}, but its slider RANGE is three literal operands
 * in {@code SodiumConfigBuilder.buildGeneralPage} — {@code setRange(2, 32,
 * 1)} — not a query of the vanilla range Meshelium widens. So under Sodium
 * there was no control that could reach past 32.
 *
 * <p>Worse than a low ceiling: Sodium validates the stored value against
 * its own range when its {@code Config} is built (at
 * {@code Minecraft.onGameLoadFinished}) and every time its video screen
 * opens ({@code StatefulOption.resetFromBinding}), and on failure writes
 * its DEFAULT — 12 — back through the binding into the vanilla option.
 * A Meshelium player at 64 with Sodium installed was silently reset to 12.
 *
 * <h2>Why an overlay through Sodium's config API, not a mixin</h2>
 * <p>Sodium ships a first-class mechanism for exactly this:
 * {@code ModOptionsBuilder.registerOptionOverlay} merges a builder onto
 * an existing option, and every field the overlay leaves unset — name,
 * tooltip, formatter, default, binding, storage handler, impact, flags —
 * is inherited from Sodium's option ({@code OptionBuilderImpl.getFirstNotNull}).
 * So the whole change is one range provider. It is also safe without
 * Sodium: since beta.9 this class is registered by the loader's
 * config-loader mixin ({@code ConfigLoaderFabricMixin} on Fabric,
 * {@code ConfigLoaderForgeMixin} on NeoForge since 2026-09-14, both in
 * the Sodium-gated mixin set) through Sodium's own
 * {@code ConfigManager.registerConfigEntryPoint}, not by a manifest
 * entrypoint key - the key made at least one launcher list the jar by
 * file name with no version - so nothing here loads without Sodium and
 * there is no manifest entry to misparse.
 *
 * <p>The version string comes from {@code MesheliumPlatform.modVersion}
 * rather than a loader API (2026-09-14): this file used to be one of the
 * three lines in the adapter that named Fabric, and it was those three
 * lines - not anything in Sodium, whose surface here is byte-identical
 * on both loaders - that kept the adapter out of the NeoForge jar. The
 * timing is safe on both: {@code registerConfigLate} runs at
 * {@code Minecraft.onGameLoadFinished}, long after each loader's
 * entrypoint has installed the platform services.
 *
 * <p>A range PROVIDER rather than a fixed range, because Meshelium's
 * ceiling is live-editable and Sodium builds its {@code Config} once per
 * session; the provider is re-evaluated when Sodium applies its screen
 * ({@link ConfigState#UPDATE_ON_APPLY}).
 *
 * <h2>The half that lives on our side</h2>
 * <p>The slider alone would move to 96 and render 32: the integrated
 * server's view-distance cap ({@code MesheliumExtendedRd.serverViewDistanceCap})
 * only widened under the {@code VULKAN_MESH_SHADERS} gate state, and
 * {@code Options.getEffectiveRenderDistance()} takes the minimum with the
 * server's distance. That cap now widens under {@code SODIUM_PRESENT} too,
 * in the same change, for the same reason the option range already did.
 */
public final class MesheliumSodiumConfigEntry implements ConfigEntryPoint {

    /** Sodium's own identifier for the option, from its config builder. */
    static final Identifier RENDER_DISTANCE = Identifier.parse("sodium:general.render_distance");

    /**
     * Overlay priority. Sodium throws at boot if two mods overlay the same
     * option at the same priority, and Bobby is a plausible future collider
     * on this exact identifier; a non-default value keeps us off the
     * likeliest tie. There is no API to query for an existing overlay.
     */
    static final int OVERLAY_PRIORITY = 100;

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        ModOptionsBuilder mod = builder.registerOwnModOptions();
        mod.setName("Meshelium").setVersion(version());
        IntegerOptionBuilder overlay = builder.createIntegerOption(RENDER_DISTANCE);
        overlay.setRangeProvider(MesheliumSodiumConfigEntry::range, ConfigState.UPDATE_ON_APPLY);
        mod.registerOptionOverlay(RENDER_DISTANCE, overlay, OVERLAY_PRIORITY);
        MesheliumLog.LOGGER.info(
                "Meshelium overlays Sodium's render-distance slider: range 2..{} (Sodium's own is "
                        + "2..32). The value is vanilla's option either way; only the range "
                        + "differs, and it follows Meshelium's render-distance ceiling.",
                sliderMax());
    }

    /**
     * The same rule as the vanilla option's range in
     * {@code MesheliumExtendedRd.onEndTick}: the widened range exists when
     * the configured ceiling is above vanilla's 32 and the master switch is
     * on; otherwise Sodium's own 2..32, so the two front-ends never disagree
     * about what is valid (a disagreement is what reset players to 12).
     * Capped at the slider ceiling; above it stays behind Meshelium's own
     * custom-entry friction.
     */
    static Range range(ConfigState state) {
        return new Range(2, sliderMax(), 1);
    }

    static int sliderMax() {
        int configured = MesheliumConfig.maxRenderDistanceConfigured();
        boolean extendedWanted = configured > 32 && MesheliumConfig.terrainRenderingEnabled();
        return extendedWanted ? Math.min(configured, MesheliumConfig.SLIDER_MAX_RENDER_DISTANCE) : 32;
    }

    private static String version() {
        return MesheliumPlatform.modVersion("meshelium").orElse("unknown");
    }
}
