/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium;

import com.deds.meshelium.gui.MesheliumPopupScreen;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;

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
 * the loading overlay is gone and {@code RenderSystem.tryGetDevice()}
 * returns a device — at that point the backend is final for the whole
 * session (backends swap only at boot).</p>
 *
 * <p><b>The decision deliberately does NOT wait for the title screen, and
 * used to.</b> The title screen was only ever a proxy for "boot has
 * finished and the device exists", and it is a proxy that fails: a player
 * who launches with quick-play, or uses a launcher's resume-last-world,
 * never sees a title screen, so the gate never decided and Meshelium
 * silently did nothing all session on hardware that fully supports it.
 * Reproduced on 26.2 with {@code --quickPlaySingleplayer}: the device was
 * created, mesh shaders were requested, and no "Backend gate:" line was
 * ever logged. The real precondition is the device, so that is what is
 * tested now.
 *
 * <p>The POPUP still waits for a title screen, because it replaces the
 * screen it is shown over and doing that to someone mid-world would be
 * indefensible. If one is owed when the decision lands, it is held until
 * a title screen next appears.</p>
 *
 * <p>The Vulkan-vs-GL call is made from public API only:
 * {@code RenderSystem.getDevice().getDeviceInfo().backendName()}, which the
 * 26.2 jar hardcodes to {@code "Vulkan"}/{@code "OpenGL"} per backend. The
 * mesh-shader half comes from {@link MesheliumVulkanState}, written by the
 * device-creation mixin — and is only trusted when the active backend
 * really is Vulkan, which also covers the corner where a Vulkan attempt got
 * as far as our mixin and then failed, falling back to GL.</p>
 */
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
        MesheliumPlatform.onEndClientTick(MesheliumGate::onEndTick);
    }

    /**
     * A popup is owed but there was no title screen to put it over. See
     * the quick-play note on {@link #init()}.
     */
    private static boolean popupPending;

    private static void onEndTick(Minecraft minecraft) {
        if (state != State.UNKNOWN) {
            // Decided already. The POPUP may still be owed - see
            // popupPending - and it waits for a title screen even though
            // the decision did not.
            if (popupPending && minecraft.gui.screen() instanceof TitleScreen) {
                popupPending = false;
                showPopupIfNeeded(minecraft, state);
            }
            return;
        }
        if (minecraft.gui.overlay() != null) {
            return; // still loading; the device may not exist yet
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

        MesheliumLog.LOGGER.info("Backend gate: {} (backend={}, device='{}', driver={})",
                decided, info.backendName(), info.name(), info.driverInfo());

        // Wave-10: the extended-render-distance range was widened (config-
        // gated) BEFORE options.txt loaded; now that the gate is decided,
        // re-validate immediately — a GL/no-mesh-shader session narrows
        // back to vanilla's range and clamps any value above 32 with the
        // notice, before the player can leave the title screen (the
        // clamp-back invariant, trigger 1).
        MesheliumExtendedRd.evaluateNow(minecraft);

        // The popup REPLACES the current screen, so it may only appear over
        // a title screen. On a normal boot that is the screen we are on and
        // it shows now; when the player skipped straight into a world it
        // waits until they come back out.
        if (minecraft.gui.screen() instanceof TitleScreen) {
            showPopupIfNeeded(minecraft, decided);
        } else {
            popupPending = true;
        }
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
                // Only accuse the hardware when we actually looked at it.
                // meshShadersRequested() is written by the device-creation
                // mixin at the END of its probe, and that mixin catches
                // Throwable and lets vanilla carry on, so "our probe threw"
                // and "this GPU has no VK_EXT_mesh_shader" both arrive here
                // as false. Telling the second story for the first is a
                // double insult: it blames a card that may be perfectly
                // capable, and it advises a driver update that cannot
                // possibly help. vulkanDeviceCreationSeen() is the
                // discriminator that already existed for this and had no
                // callers.
                if (!MesheliumVulkanState.vulkanDeviceCreationSeen()) {
                    MesheliumLog.LOGGER.error(
                            "Meshelium is off, and this one is Meshelium's fault rather than your "
                                    + "hardware's: the Vulkan backend is running but our "
                                    + "mesh-shader probe never finished, so we never learned what "
                                    + "this device supports. Look for a 'mesh-shader probe failed' "
                                    + "error earlier in this log and report it. No popup is shown "
                                    + "for this, because the honest message would be an apology "
                                    + "rather than advice.");
                    return;
                }
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
