/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.vk.MesheliumPassState;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minecraft 26.3 only: remembers the draw state vanilla set on a Vulkan
 * render pass, for {@code MainPassSuspension} to replay after it reopens
 * vanilla's main pass ({@link MesheliumPassState}).
 *
 * <p>{@code pipeline} and {@code uniforms} are vanilla's own caches
 * (bytecode: {@code setPipeline} stores the pipeline, marks every
 * descriptor dirty and sizes {@code uniforms} to the pipeline's slots;
 * {@code setUniform(int, Object)} fills a slot; {@code pushDescriptors}
 * writes them all before a draw). Push constants have no cache in vanilla -
 * {@code pushConstants(ByteBuffer)} forwards the bytes to Vulkan and forgets
 * them - so this mixin keeps a copy of the last buffer pushed. Vanilla
 * resets its "constants pushed" expectation only on {@code setPipeline},
 * so a renderer may set a pipeline, push once and draw several times; the
 * copy is what lets the resumed pass carry on from the same point.
 */
@Mixin(VulkanRenderPass.class)
abstract class VulkanRenderPassStateMixin implements MesheliumPassState {

    @Shadow
    protected VulkanRenderPipeline pipeline;

    @Shadow
    @Final
    protected ReferenceList<Object> uniforms;

    @Unique
    private ByteBuffer meshelium$lastConstants;

    @Inject(method = "pushConstants(Ljava/nio/ByteBuffer;)V", at = @At("HEAD"))
    private void meshelium$rememberConstants(ByteBuffer constants, CallbackInfo ci) {
        int n = constants.remaining();
        ByteBuffer copy = this.meshelium$lastConstants;
        if (copy == null || copy.capacity() < n) {
            copy = ByteBuffer.allocateDirect(Math.max(n, 256)).order(ByteOrder.nativeOrder());
            this.meshelium$lastConstants = copy;
        }
        copy.clear();
        copy.put(constants.duplicate());
        copy.flip();
    }

    @Override
    public VulkanRenderPipeline meshelium$pipeline() {
        return this.pipeline;
    }

    @Override
    public Object[] meshelium$uniformsSnapshot() {
        return this.uniforms.toArray();
    }

    @Override
    public ByteBuffer meshelium$lastPushConstants() {
        ByteBuffer copy = this.meshelium$lastConstants;
        return copy == null || copy.limit() == 0 ? null : copy;
    }
}
