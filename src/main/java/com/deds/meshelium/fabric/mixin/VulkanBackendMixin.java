/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.vk.MeshShaderDeviceSupport;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.lwjgl.vulkan.VkDevice;

import java.util.Collection;
import java.util.Set;

/**
 * Wave-1 mixin, seam doc shopping-list row 1: hook the single choke point
 * where vanilla enables Vulkan device extensions —
 * {@code private static VkDevice createDevice(Collection<String>,
 * VulkanPhysicalDevice, Set<VulkanFeature>)} — and append
 * {@code VK_EXT_mesh_shader} + the meshShader/taskShader features iff the
 * physical device supports them (never unconditionally: an unsupported
 * required extension would kill a Vulkan boot vanilla could complete).
 *
 * <p>Injection strategy: {@code @Inject} at HEAD, mutating the two argument
 * collections in place. This is safe because the method's one and only call
 * site (the public {@code createDevice} overload, bytecode-verified against
 * the 26.2 jar) passes fresh mutable copies — {@code new
 * HashSet<>(REQUIRED_DEVICE_EXTENSIONS)} and {@code new
 * ObjectOpenHashSet<>(REQUIRED_DEVICE_FEATURES)} — the same copies vanilla
 * itself mutates to add its optional {@code VK_EXT_multi_draw} and
 * checkpoint extensions just before the call. The static required-sets are
 * never touched.</p>
 *
 * <p>Why the OpenGL path is structurally inert: this mixin targets
 * {@code VulkanBackend} only, so it is applied when that class loads — and
 * on a GL boot the class never loads ({@code PreferredGraphicsApi.DEFAULT}
 * and {@code OPENGL} both resolve to {@code GlBackend}, seam doc Q1). No
 * code here, nor in {@link MeshShaderDeviceSupport}, is reachable from any
 * GL-path class.</p>
 *
 * <p>The catch-everything is deliberate: wave 1's one inviolable rule is
 * that Meshelium must never break a boot that vanilla could finish. If our
 * probe fails, vanilla proceeds exactly as if Meshelium were absent.</p>
 */
@Mixin(VulkanBackend.class)
abstract class VulkanBackendMixin {

    @Inject(
            method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("HEAD")
    )
    private static void meshelium$requestMeshShaders(Collection<String> extensions,
            VulkanPhysicalDevice physicalDevice, Set<VulkanFeature> features,
            CallbackInfoReturnable<VkDevice> cir) {
        try {
            MeshShaderDeviceSupport.onCreateDevice(extensions, physicalDevice, features);
        } catch (Throwable t) {
            MesheliumClient.LOGGER.error(
                    "Meshelium's mesh-shader probe failed; leaving vanilla device creation untouched", t);
        }
    }
}
