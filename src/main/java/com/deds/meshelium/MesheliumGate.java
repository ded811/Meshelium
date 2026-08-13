/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium;

import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.gui.MesheliumPopupScreen;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * The wave-1 backend gate: decides, exactly once per session, which of the
 * three worlds Meshelium woke up in —
 *
 * <ol>
 * <li><b>OPENGL</b> — the GL backend is active. Meshelium stays completely
 *     dormant beyond the one-time popup (owner directive, SPEC "graceful
 *     OpenGL fallback"). This is the common case: 26.2's DEFAULT tries
 *     OpenGL first, so the popup is the mod's front door (seam doc Q1).</li>
 * <li><b>VULKAN_NO_MESH_SHADERS</b> — Vulkan is active but the device has
 *     no usable {@code VK_EXT_mesh_shader}. Dormant, one honest notice.</li>
 * <li><b>VULKAN_MESH_SHADERS</b> — Vulkan is active and the device-creation
 *     mixin successfully requested the extension + features. Later waves
 *     key off this state.</li>
 * </ol>
 *
 * <p><b>Why the decision waits for the title screen.</b> The client
 * entrypoint cannot decide: fabric-loader injects the entrypoint invocation
 * into {@code Minecraft.<init>} immediately before the
 * {@code Thread.currentThread()} call (bytecode offset 563 in the 26.2
 * constructor, EntrypointPatch's 1.19.4+ rule), while the GPU device is
 * created at offset ~1130 ({@code GpuBackend.createDevice}) — even
 * {@code Options} (offset 579) doesn't exist yet. So the entrypoint only
 * registers a tick hook; the decision runs on the first client tick where
 * the loading overlay is gone, the title screen is up, and
 * {@code RenderSystem.tryGetDevice()} returns a device — at that point the
 * backend is final for the whole session (backends swap only at boot).</p>
 *
 * <p>The Vulkan-vs-GL call is made from public API only:
 * {@code RenderSystem.getDevice().getDeviceInfo().backendName()}, which the
 * 26.2 jar hardcodes to {@code "Vulkan"}/{@code "OpenGL"} per backend. The
 * mesh-shader half comes from {@link MesheliumVulkanState}, written by the
 * device-creation mixin — and is only trusted when the active backend
 * really is Vulkan, which also covers the corner where a Vulkan attempt got
 * as far as our mixin and then failed, falling back to GL.</p>
 */
@Environment(EnvType.CLIENT)
public final class MesheliumGate {

    public enum State {
        /** Boot still in progress; not decided yet. */
        UNKNOWN,
        /** OpenGL backend active: Meshelium fully dormant. */
        OPENGL,
        /** Vulkan active, no usable mesh shaders: Meshelium fully dormant. */
        VULKAN_NO_MESH_SHADERS,
        /** Vulkan active with VK_EXT_mesh_shader enabled: Meshelium may run. */
        VULKAN_MESH_SHADERS
    }

    private static volatile State state = State.UNKNOWN;

    private MesheliumGate() {
    }

    public static State state() {
        return state;
    }

    /** Called once from the client entrypoint. */
    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(MesheliumGate::onEndTick);
    }

    private static void onEndTick(Minecraft minecraft) {
        if (state != State.UNKNOWN) {
            return;
        }
        if (minecraft.gui.overlay() != null) {
            return; // still loading; the device may exist but the title screen doesn't
        }
        if (!(minecraft.gui.screen() instanceof TitleScreen)) {
            return; // "shown once when the title screen first appears"
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return;
        }

        DeviceInfo info = device.getDeviceInfo();
        boolean vulkan = "Vulkan".equals(info.backendName());
        State decided;
        if (vulkan) {
            decided = MesheliumVulkanState.meshShadersRequested()
                    ? State.VULKAN_MESH_SHADERS
                    : State.VULKAN_NO_MESH_SHADERS;
        } else {
            decided = State.OPENGL;
        }
        state = decided;

        MesheliumClient.LOGGER.info("Backend gate: {} (backend={}, device='{}', driver={})",
                decided, info.backendName(), info.name(), info.driverInfo());

        // Wave-10: the extended-render-distance range was widened (config-
        // gated) BEFORE options.txt loaded; now that the gate is decided,
        // re-validate immediately — a GL/no-mesh-shader session narrows
        // back to vanilla's range and clamps any value above 32 with the
        // notice, before the player can leave the title screen (the
        // clamp-back invariant, trigger 1).
        MesheliumExtendedRd.evaluateNow(minecraft);

        showPopupIfNeeded(minecraft, decided);
    }

    private static void showPopupIfNeeded(Minecraft minecraft, State decided) {
        MesheliumConfig config = MesheliumConfig.get();
        Screen parent = minecraft.gui.screen();
        switch (decided) {
            case OPENGL -> {
                boolean vulkanWasRequested =
                        minecraft.options.preferredGraphicsBackend().get() == PreferredGraphicsApi.VULKAN;
                if (vulkanWasRequested) {
                    // Offering [Enable Vulkan] would be a broken promise —
                    // the option is already set and the boot fell back to GL.
                    if (!config.vulkanFailedNoticeShown) {
                        config.vulkanFailedNoticeShown = true;
                        config.save();
                        minecraft.gui.setScreen(new MesheliumPopupScreen(
                                MesheliumPopupScreen.Variant.VULKAN_FAILED, parent));
                    }
                } else if (config.showVulkanPrompt) {
                    minecraft.gui.setScreen(new MesheliumPopupScreen(
                            MesheliumPopupScreen.Variant.ENABLE_VULKAN, parent));
                }
            }
            case VULKAN_NO_MESH_SHADERS -> {
                if (!config.noMeshShaderNoticeShown) {
                    config.noMeshShaderNoticeShown = true;
                    config.save();
                    minecraft.gui.setScreen(new MesheliumPopupScreen(
                            MesheliumPopupScreen.Variant.NO_MESH_SHADERS, parent));
                }
            }
            default -> {
                // VULKAN_MESH_SHADERS: no popup — the caps INFO block was
                // already logged at device creation by the mixin helper.
            }
        }
    }
}
