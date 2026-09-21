/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium;

import com.deds.meshelium.vk.SodiumTerrainDrawer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;

/**
 * Sodium 0.9.2+mc26.2's {@code ChunkRenderer}, implemented by
 * {@link MesheliumChunkRendererBase}. The 26.2 overlay: this file is the
 * signatures; every decision is in the base. (26.3's interface adds a
 * {@code RenderPass}, an {@code OitStage} and a {@code prepare} method;
 * that copy lives under versions/mc26.3.)
 */
public final class MesheliumChunkRenderer extends MesheliumChunkRendererBase implements ChunkRenderer {

    public MesheliumChunkRenderer(ChunkRenderer delegate) {
        super(delegate);
    }

    public MesheliumChunkRenderer(ChunkRenderer delegate, RenderSectionManager manager) {
        super(delegate, manager);
    }

    @Override
    public void render(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists,
            TerrainRenderPass pass, CameraTransform camera, FogParameters fog,
            boolean useTranslucencySorting, GpuSampler atlasSampler, GpuBufferSlice globalsUbo,
            GpuBuffer sectionTimeInfo) {
        if (draw(matrices, renderLists, pass, camera, fog, atlasSampler)) {
            return;
        }
        if (pass != null && !pass.isTranslucent()) {
            SodiumTerrainDrawer.reportRung("2"); // an opaque pass handed back whole
        }
        this.delegate.render(matrices, renderLists, pass, camera, fog, useTranslucencySorting,
                atlasSampler, globalsUbo, sectionTimeInfo);
    }
}
