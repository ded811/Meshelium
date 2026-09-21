/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.vk.MeshShaderFeatureSets;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft 26.3: reads back which of Meshelium's feature sets vanilla
 * decided to enable, at the head of the private two-argument
 * {@code createDevice(FeatureSet, VulkanPhysicalDevice)}, where the
 * composite of every required and every supported optional set is
 * complete and the device is about to be created from it. The sets
 * themselves are offered by {@code VulkanFeatureSetsMixin}.
 *
 * <p>26.2's version of this mixin sat at the head of the three-argument
 * {@code createDevice(Collection, VulkanPhysicalDevice, Set)} and added
 * the extensions and features by hand; the shared consequence of both is
 * {@code MeshShaderDeviceSupport.onDeviceFeaturesDecided}.
 *
 * <p>The one inviolable rule, as before: Meshelium must never break a
 * boot vanilla could finish. If the read-back fails, vanilla proceeds
 * exactly as if Meshelium were absent, and the gate stays off for want
 * of a recorded device.
 */
@Mixin(VulkanBackend.class)
abstract class VulkanBackendMixin {

    @Inject(
            method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;"
                    + "Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)"
                    + "Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("HEAD")
    )
    private static void meshelium$recordDeviceFeatures(FeatureSet enabled,
            VulkanPhysicalDevice physicalDevice, CallbackInfoReturnable<VkDevice> cir) {
        try {
            MeshShaderFeatureSets.onCreateDevice(enabled, physicalDevice);
        } catch (Throwable t) {
            MesheliumLog.LOGGER.error(
                    "Meshelium's mesh-shader probe failed; leaving vanilla device creation untouched", t);
        }
    }
}
