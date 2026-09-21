/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Optional;

/**
 * The Vulkan render pass behind the {@code RenderPass} a command encoder
 * hands out, and - on 26.3 - the wrapper state
 * {@code com.deds.meshelium.vk.MainPassSuspension} needs to end vanilla's
 * main pass around Meshelium's own passes and reopen it.
 *
 * <p>Minecraft 26.3: {@code RenderPass} is an interface and the object
 * behind it is {@code com.mojang.renderpearl.frontend.FrontendRenderPass},
 * whose {@code backend} field IS the {@code VulkanRenderPass}. 26.2's
 * {@code RenderPass} was a class with the same field, and the 26.2 overlay
 * of this accessor has only the getter.
 *
 * <p>Every field named here was read off the 26.3 bytecode
 * ({@code docs/unreleased/MC26.3-RECON.md}): {@code backend} (final, hence
 * {@code @Mutable} on the setter), {@code isClosed}, {@code
 * pushedDebugGroups}, {@code colorAttachments}, {@code hasDepthAttachment},
 * {@code renderArea}.
 */
@Mixin(FrontendRenderPass.class)
public interface RenderPassAccessor {

    @Accessor("backend")
    RenderPassBackend meshelium$backend();

    @Accessor("backend")
    @Mutable
    void meshelium$setBackend(RenderPassBackend backend);

    @Accessor("isClosed")
    void meshelium$setClosed(boolean closed);

    @Accessor("pushedDebugGroups")
    int meshelium$pushedDebugGroups();

    @Accessor("colorAttachments")
    List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> meshelium$colorAttachments();

    @Accessor("hasDepthAttachment")
    boolean meshelium$hasDepthAttachment();

    @Accessor("renderArea")
    RenderPass.RenderArea meshelium$renderArea();
}
