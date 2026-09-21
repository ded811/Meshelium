/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * NEXT (c), 2026-09-15: read the backend encoder's open render pass, so the
 * half-resolution occlusion target can PROVE it is being created outside
 * one.
 *
 * <h2>Why this accessor exists at all (the fact plan revision 2 got wrong)</h2>
 * There is NO Java-level guard against creating a texture inside an open
 * render pass. By bytecode,
 * {@code VulkanCommandEncoder.commandBuffer()} is
 * {@code getfield currentCommandBuffer; ifnull; areturn} — it hands back the
 * live shared command buffer whenever one exists, and its
 * {@code IllegalStateException("Cannot start command buffer while inside
 * RenderPass")} branch is reached ONLY when there is no current buffer.
 * {@code textureInitCommandBuffer()} just calls it. So a
 * {@code createTexture} inside an open pass silently records the
 * {@code VulkanGpuTexture} constructor's UNDEFINED-to-GENERAL
 * {@code VkImageMemoryBarrier} inside the {@code vkCmdBeginRenderingKHR}
 * instance: VUID-vkCmdPipelineBarrier-oldLayout-01181 and
 * VUID-vkCmdPipelineBarrier-None-07889, which only the validation layer
 * reports and whose driver behaviour is UNVERIFIED.
 *
 * <p>Nothing on the vanilla side refuses a nested pass either: neither the
 * facade's {@code createRenderPass} nor the backend's tests
 * {@code currentRenderPass} before opening one, and
 * {@code VulkanCommandEncoder.execute}'s throw belongs to the PREVIOUS
 * frame's transfer buffer. This accessor is therefore the sole structural
 * guard, and {@code OcclusionHalfResTarget.ensure} reads it as its first
 * statement, before any allocation.</p>
 *
 * <h2>Why a second mixin on a class we already mix into</h2>
 * {@link VulkanCommandEncoderMixin} (registered beside this one) is an
 * abstract CLASS mixin carrying {@code @Inject} hooks on {@code submit()}.
 * An {@code @Accessor} must live in an INTERFACE mixin, so the two coexist
 * by necessity, not by accident — a reviewer meeting the second file should
 * read this paragraph and not a duplicate.
 *
 * <p>The field is {@code private VulkanRenderPass currentRenderPass}
 * (javap -p, 26.2), assigned in {@code createRenderPass(RenderPassDescriptor)}
 * and nulled in {@code submitRenderPass()}, so it is non-null exactly while
 * a rendering instance is open on the shared buffer. A
 * {@code com.mojang.blaze3d.vulkan} target, so this mixin is only ever
 * applied when the Vulkan backend loads the class — structurally inert on
 * the OpenGL path.</p>
 */
@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {

    @Accessor("currentRenderPass")
    VulkanRenderPass meshelium$currentRenderPass();
}
