/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;

import java.nio.ByteBuffer;

/**
 * Minecraft 26.3 only: what a {@code VulkanRenderPass} remembers about the
 * draw state vanilla set on it, exposed so {@link MainPassSuspension} can
 * replay it onto the pass that resumes vanilla's rendering. Implemented on
 * {@code VulkanRenderPass} by {@code VulkanRenderPassStateMixin}.
 */
public interface MesheliumPassState {

    /** The pipeline vanilla last bound on this pass, or null. */
    VulkanRenderPipeline meshelium$pipeline();

    /** The uniform objects vanilla set, by slot; nulls where none was set. */
    Object[] meshelium$uniformsSnapshot();

    /**
     * A copy of the bytes vanilla last pushed as constants on this pass,
     * position 0 to limit, or null if it never pushed any.
     */
    ByteBuffer meshelium$lastPushConstants();
}
