/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.MesheliumLog;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;

/**
 * The terrain kill switch: Meshelium draws a terrain group and vanilla's
 * draw of the same group is cancelled, or Meshelium declines and vanilla
 * draws it. The bodies of the {@code ChunkSectionsToRender} seams.
 *
 * <p>The seams themselves live in {@code ChunkSectionsToRenderMixin}, one
 * copy per Minecraft version, because {@code renderGroup}'s signature is
 * not the same on 26.2 and 26.3 and a mixin handler must name the whole
 * parameter list. Everything that is NOT the signature is here, once. The
 * mixins are a dozen lines each and this is where to look for behaviour.
 *
 * <p>Every entry point is triple-gated in the order that keeps the Vulkan
 * classes off the OpenGL path: the latch, the backend gate (the class
 * {@code TerrainDrawer} names LWJGL and must never load on OpenGL), then
 * the live terrain-rendering setting. A handler that throws latches the
 * switch off for the session and vanilla resumes, the same discipline as
 * every other seam.
 */
public final class TerrainKillSwitch {

    private static boolean drawHookBroken;
    private static boolean helloHookBroken;

    private TerrainKillSwitch() {
    }

    /**
     * {@code renderGroup} HEAD. Returns true when Meshelium drew the group
     * and the caller must cancel vanilla's draw; false hands the group to
     * vanilla untouched.
     *
     * @param atlasView the block atlas view the group is textured with. 26.3
     *                  passes it into {@code renderGroup}; the 26.2 seam
     *                  reads it off the sections record.
     */
    public static boolean beforeRenderGroup(ChunkSectionsToRender sections, ChunkSectionLayerGroup group,
            GpuSampler sampler, GpuTextureView atlasView) {
        if (drawHookBroken
                || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS
                || !MesheliumConfig.terrainRenderingEnabled()) {
            return false;
        }
        try {
            // The captured sampler is the exact chunkLayerSampler vanilla
            // would have bound as Sampler0 - reused for pixel parity.
            if (group == ChunkSectionLayerGroup.OPAQUE) {
                if (TerrainDrawer.drawOpaque(sections, sampler, atlasView)) {
                    // CANCELLING SKIPS THE SEAM'S OWN TAIL. Mixin returns
                    // from the method at this point, so the probe the TAIL
                    // handler fires never runs on the very path it exists
                    // to observe - the one where Meshelium drew the opaque
                    // terrain. Fire it here instead; the two paths are
                    // exclusive by construction, so it runs exactly once.
                    helloProbe();
                    return true;
                }
            } else if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
                return TerrainDrawer.drawTranslucent(sections, sampler, atlasView);
            }
        } catch (Throwable t) {
            drawHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium terrain kill switch failed outside the drawer's own guard; "
                            + "disabling for this session (vanilla terrain resumes)", t);
        }
        return false;
    }

    /**
     * {@code renderGroup} TAIL: reached only when the kill switch did NOT
     * cancel, so vanilla just drew the group. For the opaque group that is
     * the point after vanilla's opaque terrain, where the hello-meshlet
     * probe belongs.
     */
    public static void afterRenderGroup(ChunkSectionLayerGroup group) {
        if (group == ChunkSectionLayerGroup.OPAQUE) {
            helloProbe();
        }
    }

    /**
     * The cheap half of every gate here, for a caller that must decide
     * whether to do something costly BEFORE handing a group over (26.3
     * suspends vanilla's main pass first): the latch, the backend gate and
     * the live setting, nothing that loads a Vulkan class.
     */
    public static boolean armed() {
        return !drawHookBroken
                && MesheliumGate.state() == MesheliumGate.State.VULKAN_MESH_SHADERS
                && MesheliumConfig.terrainRenderingEnabled();
    }

    /**
     * Would Meshelium draw the translucent group this frame? Exactly the
     * drawer's own precondition - it owns translucent only on frames whose
     * opaque it drew - evaluated without drawing, so a caller can skip the
     * cost of handing the group over on frames where the answer is no.
     */
    public static boolean translucentWanted() {
        return armed() && TerrainDrawer.opaqueOwnedThisFrame();
    }

    /**
     * The 26.3 improved-transparency seam reporting a translucent draw, so
     * the harness's version-neutral witness
     * ({@code TerrainDrawer.improvedTransparencyFrames()}) moves on 26.3 the
     * way the separate-target count moves on 26.2. Gate-guarded like every
     * other entry so the drawer is never loaded on a path that has none.
     */
    public static void noteTranslucentUnderImprovedTransparency() {
        if (!drawHookBroken && MesheliumGate.state() == MesheliumGate.State.VULKAN_MESH_SHADERS) {
            TerrainDrawer.noteTranslucentUnderImprovedTransparency();
        }
    }

    /**
     * Did Meshelium draw the translucent terrain group this frame? Used by
     * the 26.3 order-independent path to skip vanilla's own translucent
     * terrain passes once ours is in the main target; false whenever the
     * drawer is not even loaded (OpenGL, Sodium host), so the answer never
     * loads a Vulkan class to give it.
     */
    public static boolean translucentOwnedThisFrame() {
        if (drawHookBroken || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
            return false;
        }
        return TerrainDrawer.translucentOwnedThisFrame();
    }

    /**
     * The hello-meshlet probe, gated on its own latch so a failure in the
     * probe cannot take the kill switch down with it. {@code afterOpaqueTerrain}
     * throwing {@code NoClassDefFoundError} on the OpenGL path is exactly
     * why the gate check precedes it: a try/catch cannot recover that
     * error class, which is why it is a guard and not a handler.
     */
    private static void helloProbe() {
        if (helloHookBroken
                || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
            return;
        }
        try {
            HelloMeshletRenderer.afterOpaqueTerrain(null);
        } catch (Throwable t) {
            helloHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium hello-meshlet hook failed outside the renderer's own guard; "
                            + "disabling the hook for this session", t);
        }
    }
}
