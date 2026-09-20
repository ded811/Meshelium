/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium.mixin;

import com.deds.meshelium.sodium.MesheliumSodiumConfigEntry;

import net.caffeinemc.mods.sodium.client.config.ConfigManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers Meshelium's Sodium config entry point (the render-distance
 * overlay, D-019) through Sodium's own registration call instead of the
 * {@code sodium:config_api_user} manifest key.
 *
 * <p>Why not the manifest key: beta.6 through beta.8 declared it in
 * {@code fabric.mod.json}, and the owner's mod list then showed the jar
 * by file name with no version — a launcher-side metadata parser that
 * does not accept a namespaced entrypoint key. Fabric Loader itself is
 * fine with it, and so is every launcher whose parser was checked, but
 * the jar has to read correctly everywhere the owner looks, and the
 * manifest is the one file every tool parses. So the manifest goes back
 * to exactly its 1.6.0 shape.
 *
 * <p>Sodium's Fabric loader ({@code ConfigLoaderFabric.collectConfigEntryPoints},
 * javap-verified {@code public static void}) scans the manifest key and
 * then registers its own entry through the public static
 * {@code ConfigManager.registerConfigEntryPoint(Supplier, String)}; this
 * injection at its tail registers ours the same way, so the ordering and
 * the mod-id attribution are identical to a manifest registration. The
 * mixin lives in the Sodium set, which only applies with Sodium present,
 * so nothing here loads otherwise.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.fabric.config.ConfigLoaderFabric")
abstract class ConfigLoaderFabricMixin {

    // require/expect 0 for the reason every other Sodium-facing
    // injection in this config carries them (2026-09-18 release
    // audit): an injection point that matches nothing throws
    // InjectionError, which extends Error, and "required": false
    // does NOT soften that - so a renamed collectConfigEntryPoints
    // in a future Sodium would be a crash naming Meshelium rather
    // than a lost feature. Degraded, this loses the Meshelium row
    // inside Sodium's own settings screen and lets that screen
    // reset a widened render distance (D-019); both are worth far
    // less than a launch.
    @Inject(method = "collectConfigEntryPoints()V", at = @At("TAIL"), remap = false,
            require = 0, expect = 0)
    private static void meshelium$registerConfigEntry(CallbackInfo ci) {
        ConfigManager.registerConfigEntryPoint(MesheliumSodiumConfigEntry::new, "meshelium");
    }
}
