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
 * The NeoForge twin of {@link ConfigLoaderFabricMixin}: registers
 * Meshelium's Sodium config entry point (the render-distance overlay,
 * D-019) through Sodium's own registration call, on the loader whose
 * config loader is {@code ConfigLoaderForge}.
 *
 * <p>Written 2026-09-14, the day the owner asked whether the adapter
 * could be built into the NeoForge jar. Sodium's NeoForge jar has
 * {@code net.caffeinemc.mods.sodium.neoforge.config.ConfigLoaderForge}
 * with the same-shaped {@code public static void collectConfigEntryPoints()}
 * (javap of the nested mod jar; the Fabric class is absent there and
 * this one is absent from the Fabric jar), and its
 * {@code EntrypointMixin} calls it from {@code Minecraft.<init>} and then
 * {@code ConfigManager.registerConfigsEarly()}, so a TAIL injection here
 * lands after Sodium's own entry and before both registration passes -
 * the same ordering and the same mod-id attribution as on Fabric, with
 * no manifest key and nothing constructed by reflection.
 *
 * <p>Why a mixin and not the routes NeoForge also offers - a
 * {@code modproperties} key, Sodium's {@code @ConfigEntryPointForge}
 * annotation, or a direct call from the {@code @Mod} constructor: the
 * first two put a Sodium-visible registration outside the Sodium-gated
 * mixin set and go through {@code Class.forName} from Sodium's module;
 * the third would put a Sodium type into the {@code neoforge/}
 * entrypoint. This stays inside the one set that only applies with
 * Sodium present, exactly as the Fabric mixin does. The direct call is
 * the documented fallback if this mixin ever misbehaves
 * ({@code registerConfigEntryPoint} is public static and the
 * {@code @Mod} constructor runs before {@code Minecraft.<init>}).
 *
 * <p>Both config-loader mixins are listed in the one JSON;
 * {@link MesheliumSodiumMixinPlugin#shouldApplyMixin} admits each only
 * where its own target class is a visible resource, which Mixin asks
 * before it resolves the target, so the absent twin costs no log line
 * on either loader (D-026).
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.neoforge.config.ConfigLoaderForge")
abstract class ConfigLoaderForgeMixin {

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
