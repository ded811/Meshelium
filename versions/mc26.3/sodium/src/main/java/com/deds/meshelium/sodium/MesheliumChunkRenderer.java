/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium;

import com.deds.meshelium.vk.MainPassSuspension;
import com.deds.meshelium.vk.SodiumTerrainDrawer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.oit.OitStage;

/**
 * Sodium 0.9.2+mc26.3's {@code ChunkRenderer}, implemented by
 * {@link MesheliumChunkRendererBase}. This file is the signatures plus the
 * one thing 26.3 adds, {@link MainPassSuspension}; every decision is in
 * the base.
 *
 * <p>Against 26.2's interface, {@code render} gained the {@code RenderPass}
 * Sodium is recording into (after the sorting flag) and a trailing
 * {@code OitStage}, non-null only for the order-independent translucent
 * passes, and {@code prepare} is new: Sodium's own renderer builds its
 * per-frame batches there before any pass is rendered. It is forwarded
 * unconditionally, because the translucent passes and any opaque pass
 * Meshelium hands back are rendered by the delegate, which expects to have
 * prepared.
 *
 * <p>The render pass Sodium hands over is vanilla's main pass, open (on
 * 26.3 the terrain is drawn inside the same pass as the entities). The
 * mesh-shader draw needs passes of its own, so it runs inside a
 * {@link MainPassSuspension}; when that declines, the pass is handed to
 * Sodium whole, exactly as when the drawer itself declines.
 */
public final class MesheliumChunkRenderer extends MesheliumChunkRendererBase implements ChunkRenderer {

    public MesheliumChunkRenderer(ChunkRenderer delegate) {
        super(delegate);
    }

    public MesheliumChunkRenderer(ChunkRenderer delegate, RenderSectionManager manager) {
        super(delegate, manager);
    }

    @Override
    public void prepare(ChunkRenderListIterable renderLists, CameraTransform camera,
            boolean useTranslucencySorting) {
        this.delegate.prepare(renderLists, camera, useTranslucencySorting);
    }

    @Override
    public void render(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists,
            TerrainRenderPass pass, CameraTransform camera, FogParameters fog,
            boolean useTranslucencySorting, RenderPass renderPass, GpuSampler atlasSampler,
            GpuBufferSlice globalsUbo, GpuBuffer sectionTimeInfo, OitStage oitStage) {
        // An OIT stage is a translucent pass by definition, and translucent
        // passes stay with Sodium. wantsDraw is the cheap half of draw's
        // own early-outs, so a pass that would be declined anyway never
        // pays for a suspension.
        if (oitStage == null && wantsDraw(pass)) {
            try (MainPassSuspension suspended = MainPassSuspension.suspend(renderPass)) {
                if (suspended != null && draw(matrices, renderLists, pass, camera, fog, atlasSampler)) {
                    return;
                }
            }
        }
        if (pass != null && !pass.isTranslucent()) {
            SodiumTerrainDrawer.reportRung("2"); // an opaque pass handed back whole
        }
        this.delegate.render(matrices, renderLists, pass, camera, fog, useTranslucencySorting,
                renderPass, atlasSampler, globalsUbo, sectionTimeInfo, oitStage);
    }
}
