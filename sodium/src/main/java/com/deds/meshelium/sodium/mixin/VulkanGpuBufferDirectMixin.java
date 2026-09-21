/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;

import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gives every vertex buffer the storage-buffer usage bit, so that Sodium's
 * region geometry is LEGAL to bind the way Meshelium binds it.
 *
 * <h2>The defect this fixes</h2>
 * <p>Meshelium reads Sodium's terrain in place by binding Sodium's region
 * {@code GpuBuffer} as a {@code VK_DESCRIPTOR_TYPE_STORAGE_BUFFER}. Sodium
 * creates that buffer through vanilla's device with
 * {@code GpuBuffer.USAGE_VERTEX}, and vanilla's
 * {@code VulkanConst.bufferUsageToVk} maps that to
 * {@code VK_BUFFER_USAGE_VERTEX_BUFFER_BIT} and nothing else — Blaze3D has
 * no storage usage at all (javap-verified: the mapping's whole output set
 * is TRANSFER_SRC/DST, VERTEX, INDEX, UNIFORM, UNIFORM_TEXEL, INDIRECT).
 * Writing a storage descriptor for a buffer created without
 * {@code VK_BUFFER_USAGE_STORAGE_BUFFER_BIT} violates
 * VUID-VkWriteDescriptorSet-descriptorType-00331.
 *
 * <p>It worked anyway, on this desk, on AMD. That is the pattern this
 * project has been bitten by before: AMD tolerating an invalid module or
 * descriptor, validation staying quiet, and the first evidence arriving as
 * a bug report from an NVIDIA or Intel user. Undefined behaviour that
 * happens to render is still undefined.
 *
 * <h2>Why here, and why this narrowly</h2>
 * <p>The buffer is Sodium's, so its creation cannot be changed from
 * Meshelium's side — except at the one place vanilla turns usage flags
 * into Vulkan usage flags, which is a single {@code invokestatic} inside
 * {@code VulkanGpuBuffer.Direct}'s constructor. Redirecting it and OR-ing
 * the storage bit in for VERTEX-usage buffers makes every vertex buffer
 * on the device storage-capable. Adding a usage bit changes nothing about
 * how a buffer is allocated or used otherwise; it only widens what it may
 * be bound as.
 *
 * <p>It is scoped to vertex buffers because those are the only ones
 * Meshelium binds this way, and it lives in the Sodium mixin set because
 * that set only applies with Sodium present — the standalone path's arena
 * is Meshelium's own VMA allocation and already carries the bit.
 *
 * <p>The target is a vanilla class, not a Sodium one; the plugin gates the
 * whole set on Sodium being loaded, which is the right scope for a change
 * whose only beneficiary is the Sodium path.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer$Direct")
abstract class VulkanGpuBufferDirectMixin {

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanConst;bufferUsageToVk(I)I"))
    private static int meshelium$storageCapableVertexBuffers(int usage) {
        int vk = VulkanConst.bufferUsageToVk(usage);
        if ((usage & GpuBuffer.USAGE_VERTEX) != 0) {
            vk |= VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        }
        return vk;
    }
}
