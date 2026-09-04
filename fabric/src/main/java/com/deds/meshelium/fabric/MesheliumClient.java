/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric;

import com.deds.meshelium.MesheliumPlatform;
import com.deds.meshelium.MesheliumSmokeRun;
import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.gui.MesheliumOptionsScreen;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

import net.minecraft.client.Minecraft;

/**
 * Meshelium — mesh-shader terrain rendering for every GPU that has mesh
 * shaders, built on vanilla 26.2's Vulkan backend ({@code
 * com.mojang.blaze3d.vulkan}) and {@code VK_EXT_mesh_shader}.
 *
 * <p>A cross-vendor reimplementation of Nvidium's renderer. Original
 * architecture by MCRcortex (LGPL-3.0) — all credit for the design to them.
 * Nvidium proved the approach on {@code GL_NV_mesh_shader}, which only
 * NVIDIA ever implemented; Khronos put the cross-vendor extension in Vulkan
 * instead, which is why this mod exists and why it is Vulkan-only.</p>
 *
 * <p>This entrypoint deliberately does almost nothing: fabric-loader runs
 * it inside {@code Minecraft.<init>} <em>before</em> Options and the GPU
 * device exist (bytecode-verified, see {@link MesheliumGate}), so all it may
 * safely do is load Meshelium's own config and register hooks — the gate's
 * tick hook (the decision runs on the first tick where the GPU device
 * exists, NOT at the title screen — waiting for one meant quick-play and
 * resume-last-world got no renderer at all) and, since wave 8,
 * the {@code /meshelium} client command that opens the options screen. The
 * command is the second route to the settings, after the button in
 * vanilla's own Video Settings screen; the
 * screen-open is {@code schedule}d rather than run inline because command
 * execution happens under the chat screen, whose own close would otherwise
 * stomp the freshly set screen next tick
 * ({@code BlockableEventLoop.schedule} always enqueues, javap-verified —
 * unlike {@code execute}, which runs inline on the owning thread).</p>
 */
public final class MesheliumClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // FIRST, before anything reads config or registers a tick: give
        // the loader-neutral code its loader. MesheliumConfig.get() on the
        // next line already needs the config directory, so this ordering
        // is load bearing rather than tidy.
        MesheliumPlatform.install(new FabricPlatformServices());
        MesheliumLog.LOGGER.info("Meshelium {} initializing (backend gate armed; the decision runs "
                + "on the first tick where the GPU device exists)", version());
        MesheliumConfig.get();
        MesheliumGate.init();
        // Wave-10: the extended-RD monitor registers AFTER the gate's tick
        // hook so on the decision tick it already sees the decided state
        // (fabric events run in registration order).
        com.deds.meshelium.MesheliumExtendedRd.init();
        registerOptionsCommand();
        // Test lever only; does nothing unless -Dmeshelium.smokeTicks says
        // otherwise. Shared with NeoForge so both loaders can be booted
        // the way a launcher boots them and shut down cleanly enough to
        // read the teardown accounting.
        MesheliumSmokeRun.installIfRequested();
    }

    /**
     * Wave-8: {@code /meshelium} → the options screen, next tick. Registered
     * unconditionally (the command itself is harmless on any backend — the
     * screen only edits config), through fabric-command-api-v2's client
     * command surface ({@code ClientCommandRegistrationCallback.EVENT} +
     * {@code ClientCommands.literal}, javap-verified against fabric-api
     * 0.155.2's fabric-command-api-v2 3.1.0).
     */
    private static void registerOptionsCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("meshelium").executes(context -> {
                    MesheliumOptionsScreen.openFromCommand();
                    return 1;
                })));
    }

    private static String version() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("meshelium")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }
}
