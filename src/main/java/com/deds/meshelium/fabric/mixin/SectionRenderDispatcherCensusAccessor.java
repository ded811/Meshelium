/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.VanillaTerrainCensus;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * The per-layer terrain buffer map, for the read-only census. The value
 * type is package-private, so this is typed as a wildcard map and the
 * values are reached through {@link SectionUberBuffersAccessor}.
 */
@Mixin(SectionRenderDispatcher.class)
public interface SectionRenderDispatcherCensusAccessor
        extends VanillaTerrainCensus.SectionRenderDispatcherAccessorBridge {

    @Override
    @Accessor("chunkUberBuffers")
    Map<?, ?> meshelium$chunkUberBuffers();
}
