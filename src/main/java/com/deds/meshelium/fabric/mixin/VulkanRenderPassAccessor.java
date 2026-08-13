/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Wave-2 accessor, frame-path doc shopping-list row 2:
 * {@code VulkanRenderPass} holds {@code private final VkCommandBuffer
 * commandBuffer} — the encoder's live shared buffer between
 * {@code createRenderPass} and {@code submitRenderPass} (Q3.1). Reading it
 * from a pass MESHELIUM opened gives raw command access with nothing to save
 * or restore: vanilla records no draws into our pass, and its own state
 * tracking ({@code pipeline}, {@code anyDescriptorDirty}) never observes
 * our raw binds because the pass object is used for begin/end only.
 *
 * <p>{@code device} rides along ({@code private final VulkanDevice device},
 * javap-verified) so the lazy pipeline build can reach
 * {@code VulkanDevice.vkDevice()} without a second seam — the recon's
 * row-3 alternative ("capture VulkanDevice at the device seam") kept for
 * waves 4+; wave 2 needs the device only where it already has the pass.</p>
 *
 * <p>This mixin targets a {@code com.mojang.blaze3d.vulkan} class, so it is
 * only ever APPLIED when the Vulkan backend loads that class — structurally
 * inert on the OpenGL path, same argument as the wave-1 backend mixin.</p>
 */
@Mixin(VulkanRenderPass.class)
public interface VulkanRenderPassAccessor {

    @Accessor("commandBuffer")
    VkCommandBuffer meshelium$commandBuffer();

    @Accessor("device")
    VulkanDevice meshelium$device();
}
