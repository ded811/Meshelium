/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.vk.MesheliumProjectionCapture;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;

import net.minecraft.client.renderer.ProjectionMatrixBuffer;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NEXT (c), 2026-09-15: capture the matrix vanilla uploads into the level
 * projection buffer — the matrix the standalone occlusion rasters BIND.
 *
 * <p>Instrumentation only: the hook copies the argument and the returned
 * slice and returns. It changes nothing vanilla does, and with the half-res
 * lever off nothing ever reads what it records.</p>
 *
 * <p>Why the capture is needed rather than reading
 * {@code CameraRenderState.projectionMatrix}: those are DIFFERENT matrices
 * on ordinary frames. See {@link MesheliumProjectionCapture} for the
 * {@code GameRenderer.renderLevel} bytecode trail (bob, hurt, portal and
 * nausea all multiply into the copy that gets uploaded).</p>
 *
 * <p>javap -p, 26.2:
 * {@code public GpuBufferSlice getBuffer(org.joml.Matrix4f)}. The sibling
 * overload {@code getBuffer(Projection)} funnels into the same private
 * {@code writeBuffer(Matrix4f)} but is not hooked: the level path calls the
 * {@code Matrix4f} overload directly (renderLevel ip 291-303), and the
 * capture is only ever trusted when its returned slice IS the bound one.</p>
 */
@Mixin(ProjectionMatrixBuffer.class)
abstract class ProjectionMatrixBufferMixin {

    @Inject(method = "getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",
            at = @At("RETURN"))
    private void meshelium$captureProjection(Matrix4f matrix,
            CallbackInfoReturnable<GpuBufferSlice> cir) {
        MesheliumProjectionCapture.record(matrix, cir.getReturnValue());
    }
}
