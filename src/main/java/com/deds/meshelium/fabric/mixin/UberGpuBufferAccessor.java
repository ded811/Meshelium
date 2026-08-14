/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import com.mojang.blaze3d.vertex.UberGpuBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * READ-ONLY census of vanilla's committed terrain memory.
 *
 * <p>Vanilla's terrain does not live in one buffer. Each
 * {@code UberGpuBuffer} is a growable {@code List<Pair<TlsfAllocator,
 * UberGpuBufferHeap>>} of FIXED-SIZE heaps (128 MiB vertex, 32 MiB index,
 * javap on {@code SectionRenderDispatcher.lambda$new$0}), and that list is
 * where the gigabytes are. {@code nodes.size() * heapSize} per buffer per
 * layer IS vanilla's committed terrain VRAM, fragmentation included.</p>
 *
 * <p>This exists because the question "is vanilla's duplicate copy really
 * where the memory went" was answered three different ways by derivation
 * and never once by measurement. A heap size times a list length is not a
 * model; it is the number.</p>
 */
@Mixin(UberGpuBuffer.class)
public interface UberGpuBufferAccessor {

    @Accessor("nodes")
    List<?> meshelium$nodes();

    @Accessor("heapSize")
    int meshelium$heapSize();
}
