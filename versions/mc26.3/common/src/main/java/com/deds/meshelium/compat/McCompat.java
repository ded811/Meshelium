/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;

/**
 * The Minecraft 26.3 answers to the handful of questions whose answer
 * differs between game versions and cannot be a rename.
 *
 * <p>One class per version, same signatures, chosen at build time
 * ({@code versions/README.md}). The shared tree calls these and never
 * branches on a version string. Each method's doc says what the OTHER
 * version does, so a reader of either copy sees the whole difference.
 */
public final class McCompat {

    private McCompat() {
    }

    /**
     * A pNext struct descriptor. 26.3's record carries the LWJGL struct
     * class (it derives field offsets from it, {@code fieldOffset(String)});
     * 26.2's constructor took only the sType and the size.
     */
    public static VulkanPNextStruct pnextStruct(Class<?> structClass, int sType, int structSize) {
        return new VulkanPNextStruct(structClass, sType, structSize);
    }

    /**
     * Where vanilla draws the opaque terrain group this frame. 26.3 has no
     * per-group targets any more ({@code ChunkSectionLayerGroup.outputTarget}
     * is gone): the solid pass renders into the main target, the one
     * {@code LevelRenderer.addMainPass} imports as {@code targets.main}.
     */
    public static RenderTarget opaqueTarget() {
        return Minecraft.getInstance().gameRenderer.mainRenderTarget();
    }

    /**
     * Where a classic (non-OIT) translucent terrain group is drawn. 26.3
     * draws it into the main target from the same render pass as the solid
     * group; the separate "improved transparency" target of 26.2 became the
     * order-independent path, which does not go through {@code renderGroup}
     * at all (see {@code LevelRendererTranslucencyMixin}).
     */
    public static RenderTarget translucentTarget() {
        return Minecraft.getInstance().gameRenderer.mainRenderTarget();
    }

    /**
     * The block atlas view, vanilla's own lookup ({@code LevelRenderer
     * .executeSolid}, bytecode ip 15-28). 26.2's {@code ChunkSectionsToRender}
     * carried it as a record component; 26.3 passes it into
     * {@code renderGroup} and the drawer receives it from the seam.
     */
    public static GpuTextureView blockAtlasView() {
        return Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
    }

    /**
     * The empty {@code ChunkSectionsToRender} the skipVanillaPrep candidate
     * hands back in place of vanilla's per-frame build. {@code null} on
     * 26.3, which means "not on this version, let vanilla prepare": the
     * class became abstract with two shipped shapes ({@code DrawSeparate},
     * {@code DrawIndirect}) and a terrain-transform UBO argument whose
     * consumers have not been censused here the way 26.2's were. The
     * candidate is default-off and unmeasured on 26.3; the caller treats
     * null as a decline.
     */
    public static ChunkSectionsToRender emptySectionsToRender(GpuTextureView atlasView) {
        return null;
    }

    /**
     * Does this quad take its brightness from its own face? 26.2 kept a
     * {@code shade} boolean (false = the flat "up" brightness). 26.3
     * replaced it with {@code shadeDirectionOverride}: null = shade by the
     * quad's face; a direction = shade by THAT face
     * ({@code BlockModelLighter.getDirectionalBrightness}, bytecode-read).
     * An override equal to the quad's own face is the same thing as none;
     * {@code UP} is exactly 26.2's {@code shade=false}; any other override
     * has no boolean equivalent and is reported as face-shaded, the nearer
     * of the two.
     */
    public static boolean quadShaded(BakedQuad.MaterialInfo info, Direction quadFace) {
        Direction override = info.shadeDirectionOverride();
        return override == null || override == quadFace;
    }
}
