/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import com.mojang.blaze3d.vertex.UberGpuBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The two buffers behind one terrain layer.
 *
 * <p>Targeted by NAME rather than by class literal because
 * {@code SectionRenderDispatcher$SectionUberBuffers} is a package-private
 * record and cannot be referenced from here (javap: {@code final class ...
 * $SectionUberBuffers extends java.lang.Record} with private final
 * {@code vertexBuffer} / {@code indexBuffer}).</p>
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$SectionUberBuffers")
public interface SectionUberBuffersAccessor {

    @Accessor("vertexBuffer")
    UberGpuBuffer<?> meshelium$vertexBuffer();

    @Accessor("indexBuffer")
    UberGpuBuffer<?> meshelium$indexBuffer();
}
