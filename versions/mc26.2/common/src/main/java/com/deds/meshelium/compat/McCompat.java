/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.compat;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;

import java.util.EnumMap;
import java.util.List;

/**
 * The Minecraft 26.2 answers to the handful of questions whose answer
 * differs between game versions and cannot be a rename. This is the
 * 26.2 overlay copy, written in 26.2's own class names; the shared tree
 * is written for 26.3 and its copy of this class sits beside it there.
 * Same signatures, chosen at build time ({@code versions/README.md}).
 */
public final class McCompat {

    private McCompat() {
    }

    /**
     * A pNext struct descriptor. 26.2's record is sType + size; the struct
     * class 26.3 wants is accepted and ignored so the shared caller can
     * pass it on both versions.
     */
    public static VulkanPNextStruct pnextStruct(Class<?> structClass, int sType, int structSize) {
        return new VulkanPNextStruct(sType, structSize);
    }

    /** Where vanilla draws the opaque terrain group this frame. */
    public static RenderTarget opaqueTarget() {
        return ChunkSectionLayerGroup.OPAQUE.outputTarget();
    }

    /**
     * Where vanilla draws the translucent terrain group: the main target,
     * or the separate one when "improved transparency" is on - exactly what
     * {@code outputTarget()} hands back either way.
     */
    public static RenderTarget translucentTarget() {
        return ChunkSectionLayerGroup.TRANSLUCENT.outputTarget();
    }

    /** The block atlas view, vanilla's own lookup. */
    public static GpuTextureView blockAtlasView() {
        return Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
    }

    /**
     * The empty {@code ChunkSectionsToRender} the skipVanillaPrep candidate
     * hands back: the REAL atlas view with empty per-layer draw maps, 0
     * indices and an empty UBO-slice array - the same shape vanilla itself
     * builds when {@code sectionRenderDispatcher == null}, and one a stray
     * {@code renderGroup} call renders as zero draws. The consumer census
     * behind that claim is in {@code LevelRendererMixin}.
     */
    public static ChunkSectionsToRender emptySectionsToRender(GpuTextureView atlasView) {
        EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>>
                drawGroups = new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            drawGroups.put(layer, new Int2ObjectOpenHashMap<>());
        }
        return new ChunkSectionsToRender(atlasView, drawGroups, 0, new GpuBufferSlice[0]);
    }

    /**
     * Does this quad take its brightness from its own face? 26.2's
     * {@code shade} boolean says so directly; false is the flat "up"
     * brightness. The face is what 26.3 needs and is ignored here.
     */
    public static boolean quadShaded(BakedQuad.MaterialInfo info, Direction quadFace) {
        return info.shade();
    }
}
