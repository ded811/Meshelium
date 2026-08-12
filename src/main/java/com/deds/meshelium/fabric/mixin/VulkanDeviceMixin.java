/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.vk.MesheliumDeviceTeardown;

import com.mojang.blaze3d.vulkan.VulkanDevice;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wave-8 destroy sweep: the "whatever close hook exists" is
 * {@code VulkanDevice.close()} itself — {@code Minecraft.close()} reaches
 * it through {@code RenderSystem.shutdownRenderer()} (ip 128,
 * javap-verified). Injection point: AFTER the
 * {@code VulkanCommandEncoder.destroy()} call (close bytecode ip 13),
 * whose body runs the final submit, {@code graphicsQueue.waitIdle()} and
 * both destroy-queue drains — so when the handler runs, no Meshelium
 * submission can still be executing, every deferred per-world destroy has
 * already happened, and the {@code VkDevice} is still valid
 * ({@code vkDestroyDevice} is ip 32 of the same method). The ordering
 * argument lives on {@link MesheliumDeviceTeardown}.
 *
 * <p>OpenGL path: structurally inert — this mixin targets a class that
 * never loads there (the {@code VulkanBackendMixin} precedent). Failure
 * containment: a throwing teardown must never turn a clean shutdown into
 * a crash; log once and let vanilla finish closing (leaked handles die
 * with the device three bytecodes later).</p>
 */
@Mixin(VulkanDevice.class)
abstract class VulkanDeviceMixin {

    @Inject(
            method = "close()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;destroy()V",
                    shift = At.Shift.AFTER
            )
    )
    private void meshelium$destroyDeviceObjects(CallbackInfo ci) {
        try {
            MesheliumDeviceTeardown.onDeviceClose((VulkanDevice) (Object) this);
        } catch (Throwable t) {
            MesheliumClient.LOGGER.error(
                    "Meshelium device teardown failed; continuing vanilla shutdown "
                            + "(handles die with the device)", t);
        }
    }
}
