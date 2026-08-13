/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.fabric.mixin.RenderPassAccessor;
import com.deds.meshelium.fabric.mixin.VulkanRenderPassAccessor;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Wave 2's deliverable: the first mesh-shader draws on screen, recorded
 * right after vanilla's OPAQUE terrain pass through Meshelium's OWN
 * RenderPass over the same color+depth attachments — the middle path of
 * docs/VANILLA-FRAME-PATH.md Q3.3's option table. Vanilla supplies
 * begin/end rendering, viewport/scissor, GENERAL layouts, transient
 * command-buffer lifetime and the after-pass full barrier; Meshelium records
 * exactly {@code vkCmdBindPipeline} (+ one push-descriptor write for the
 * world variant) + {@code vkCmdDrawMeshTasksEXT(1,1,1)} per triangle.
 *
 * <p><b>Double gate:</b> the draw runs only when the wave-1 gate says
 * {@code VULKAN_MESH_SHADERS} (checked by the mixin BEFORE this class
 * loads) AND the {@code meshelium.helloMeshlet} system property is set — the
 * jar ships to the owner's instance, and a permanent magenta triangle in
 * normal play would read as a bug. With the property off this class is
 * never loaded at all.</p>
 *
 * <p><b>Failure containment:</b> any throwable — shaderc rejection,
 * pipeline-creation failure, a driver surprise — flips {@code broken},
 * records {@link #lastError()} for the harness, logs ONE error, and the
 * hook goes silent for the session. The frame loop must never die for a
 * hello triangle.</p>
 *
 * <p><b>Two triangles per frame:</b> the magenta NDC triangle (the wave's
 * acceptance evidence — zero descriptors, hardcoded clip-space) and the
 * yellow world-space triangle: a one-block triangle world-anchored a few
 * blocks in front of wherever the camera first looked, driven by a
 * 64-byte MVP composed on the CPU from {@code CameraRenderState}'s public
 * matrices and uploaded through vanilla's {@code transientMemory()} each
 * frame (Q6 step B — per-submit lifetime, no persistent buffers, no
 * cleanup owed).</p>
 */
public final class HelloMeshletRenderer {

    /** The harness/dev opt-in; without it this class is never even loaded. */
    private static final boolean ENABLED = Boolean.getBoolean("meshelium.helloMeshlet");

    /** std140 mat4. */
    private static final int MVP_BYTES = 64;
    /**
     * Transient-UBO alignment: 256 is the Vulkan spec's maximum allowed
     * {@code minUniformBufferOffsetAlignment}, so it satisfies every
     * device without querying limits. (Param position verified from
     * {@code TransientMemory}'s default-method bytecode: the 3-arg
     * {@code uploadGpu(data, alignment, usage)} forwards to the 5-arg form
     * as {@code (data, alignment, usage, data.remaining(), 1)}.)
     */
    private static final long UBO_ALIGNMENT = 256;

    private static volatile String lastError;
    private static volatile int framesDrawn;
    private static boolean broken;                 // render thread only
    private static HelloMeshletPipeline pipeline;  // lazy, device lifetime
    private static boolean anchorLatched;
    private static double anchorX;
    private static double anchorY;
    private static double anchorZ;

    private HelloMeshletRenderer() {
    }

    /**
     * Harness probe: {@code null} = healthy. Non-null means the once-only
     * ERROR fired and carries its message; the client gametest fails on it.
     */
    public static String lastError() {
        return lastError;
    }

    /** Harness probe: how many frames actually recorded the hello draws. */
    public static int frameCount() {
        return framesDrawn;
    }

    /**
     * Wave-8 destroy sweep: drop the lazily built device-lifetime
     * pipelines at device close (via {@link MesheliumDeviceTeardown} — after
     * vanilla's encoder destroy, so the queue is idle and the VkDevice
     * still valid). No-op when the hello property never armed a build.
     */
    public static void destroyDeviceObjects(org.lwjgl.vulkan.VkDevice device) {
        HelloMeshletPipeline p = pipeline;
        if (p != null) {
            pipeline = null;
            p.destroy(device);
        }
    }

    /**
     * Called by {@code LevelRendererMixin} right after the OPAQUE
     * {@code renderGroup} call returns — encoder between passes, depth
     * holding exactly terrain (frame-path doc Q2.6a).
     */
    public static void afterOpaqueTerrain(LevelRenderState levelRenderState) {
        if (!ENABLED || broken) {
            return;
        }
        if (MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
            return; // defence in depth; the mixin already checked
        }
        try {
            draw(levelRenderState);
            framesDrawn++;
        } catch (Throwable t) {
            broken = true;
            lastError = t.toString();
            MesheliumClient.LOGGER.error(
                    "Hello-meshlet draw failed; disabled for this session (first and only report)", t);
        }
    }

    private static void draw(LevelRenderState levelRenderState) {
        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView colorView = mainTarget.getColorTextureView();
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return; // target not allocated yet; try again next frame
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        // Upload the world triangle's MVP BEFORE opening the pass — the
        // transient allocator may record its own transfer commands, and
        // only one pass may be open on the encoder at a time (Q1.3).
        GpuBufferSlice mvpSlice = levelRenderState != null
                ? uploadMvp(encoder, levelRenderState.cameraRenderState)
                : null;

        // Own pass, same attachments, no clears → loadOp LOAD on both
        // (Q1.3); vanilla's pass ctor sets viewport+scissor; close() ends
        // rendering and emits the full memory barrier (Q3.3).
        try (RenderPass pass = encoder.createRenderPass(() -> "meshelium hello meshlet",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            VulkanRenderPass backendPass = (VulkanRenderPass) ((RenderPassAccessor) pass).meshelium$backend();
            VulkanRenderPassAccessor vkPass = (VulkanRenderPassAccessor) backendPass;
            VkCommandBuffer commandBuffer = vkPass.meshelium$commandBuffer();

            HelloMeshletPipeline p = pipelineFor(vkPass.meshelium$device(), colorView, depthView);

            // Raw binds inside a pass object vanilla only begins/ends are
            // safe: vanilla records no draws here and its lazy descriptor
            // tracking never flushes without a vanilla draw call (Q3.2).
            VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, p.ndcPipeline());
            EXTMeshShader.vkCmdDrawMeshTasksEXT(commandBuffer, 1, 1, 1);

            if (mvpSlice != null) {
                VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, p.worldPipeline());
                pushMvpDescriptor(commandBuffer, p.worldPipelineLayout(), mvpSlice);
                EXTMeshShader.vkCmdDrawMeshTasksEXT(commandBuffer, 1, 1, 1);
            }
        }
    }

    /**
     * Lazy pipeline build at the first draw — the first moment the REAL
     * attachment formats exist to be read ({@code view.texture()
     * .getFormat()} → public {@code VulkanConst.toVk}, Q1.5/Q6 step 3).
     */
    private static HelloMeshletPipeline pipelineFor(VulkanDevice device,
            GpuTextureView colorView, GpuTextureView depthView) {
        HelloMeshletPipeline p = pipeline;
        if (p == null) {
            int vkColorFormat = VulkanConst.toVk(colorView.texture().getFormat());
            int vkDepthFormat = VulkanConst.toVk(depthView.texture().getFormat());
            p = HelloMeshletPipeline.create(device.vkDevice(), vkColorFormat, vkDepthFormat);
            pipeline = p;
            MesheliumClient.LOGGER.info(
                    "Hello-meshlet pipelines created (color format {} [{}], depth format {} [{}])",
                    colorView.texture().getFormat(), vkColorFormat,
                    depthView.texture().getFormat(), vkDepthFormat);
        }
        return p;
    }

    /**
     * Composes {@code MVP = projection * viewRotation * translate(anchor -
     * cameraPos)} from {@code CameraRenderState}'s public CPU matrices
     * (frame-path doc Q2.5 table) and uploads the 64 bytes into vanilla's
     * per-submit transient memory. The anchor is latched on the first hello
     * frame, 4 blocks along the camera's view direction, and stays FIXED in
     * world space from then on — moving the camera afterwards proves the
     * transform is world-anchored, not screen-glued.
     */
    private static GpuBufferSlice uploadMvp(CommandEncoder encoder, CameraRenderState camera) {
        if (camera == null || !camera.initialized) {
            return null; // NDC-only this frame
        }
        if (!anchorLatched) {
            Matrix4f view = camera.viewRotationMatrix;
            // Camera forward in world space = -(row 2 of the world→view
            // rotation); JOML mCR = column C, row R, so row 2 reads
            // (m02, m12, m22). Orthonormal rotation ⇒ transpose = inverse.
            double forwardX = -view.m02();
            double forwardY = -view.m12();
            double forwardZ = -view.m22();
            anchorX = camera.pos.x + forwardX * 4.0;
            anchorY = camera.pos.y + forwardY * 4.0 - 0.5; // base slightly below eye height
            anchorZ = camera.pos.z + forwardZ * 4.0;
            anchorLatched = true;
            MesheliumClient.LOGGER.info(
                    "Hello-meshlet world anchor latched at ({}, {}, {})", anchorX, anchorY, anchorZ);
        }

        Matrix4f mvp = new Matrix4f(camera.projectionMatrix)
                .mul(camera.viewRotationMatrix)
                .translate(
                        (float) (anchorX - camera.pos.x),
                        (float) (anchorY - camera.pos.y),
                        (float) (anchorZ - camera.pos.z));

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bytes = stack.malloc(MVP_BYTES);
            mvp.get(bytes); // writes 64 bytes at position 0, position unchanged
            // uploadGpu copies synchronously into mapped transient memory
            // (VulkanTransientMemory.upload writes through a MappedView and
            // closes it before returning), so the stack buffer is safe.
            return encoder.transientMemory().uploadGpu(bytes, UBO_ALIGNMENT, GpuBuffer.USAGE_UNIFORM);
        }
    }

    private static void pushMvpDescriptor(VkCommandBuffer commandBuffer, long pipelineLayout,
            GpuBufferSlice slice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufferInfo.get(0)
                    .buffer(((VulkanGpuBuffer) slice.buffer()).vkBuffer())
                    .offset(slice.offset())
                    .range(MVP_BYTES);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0)
                    .sType$Default()
                    .dstBinding(0)
                    .descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(bufferInfo);
            // Push descriptors, like every vanilla draw (Q3.2) — no pools,
            // no sets, nothing to free.
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(commandBuffer,
                    VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, write);
        }
    }
}
