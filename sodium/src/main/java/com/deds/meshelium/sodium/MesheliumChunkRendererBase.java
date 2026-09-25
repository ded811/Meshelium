/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium;

import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumVulkanState;
import com.deds.meshelium.sodium.mixin.RenderSectionManagerInvoker;
import com.deds.meshelium.vk.SodiumFrameRing;
import com.deds.meshelium.vk.SodiumGpuVisibilityLayout;
import com.deds.meshelium.vk.SodiumMirrorGpu;
import com.deds.meshelium.vk.SodiumTerrainDrawer;

import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;

/**
 * Meshelium's seat in Sodium's terrain pipeline: mesh shaders drawing
 * Sodium's geometry.
 *
 * <h2>What it does</h2>
 * <p>For the opaque passes it draws Sodium's terrain with Meshelium's mesh
 * shaders out of Sodium's own region buffers: no decode, no re-encode, no
 * upload, and no second copy of the world in video memory. The format
 * difference is absorbed by a different vertex fetch in the mesh shader;
 * see {@link SodiumTerrainDrawer}.</p>
 *
 * <p>Translucent geometry still goes to Sodium's renderer. It needs
 * back-to-front ordering and per-vertex alpha, and neither is in this
 * version's scope.</p>
 *
 * <h2>Three rungs (D-023, GPU-VISIBILITY-CONTRACT.md section 7)</h2>
 * <p>Decided at the SOLID call, remembered for CUTOUT: frames are owned
 * whole.</p>
 * <ul>
 *   <li><b>Rung 0</b> ({@code -Dmeshelium.sodium.gpuDraw=true}, default
 *       off): the GPU record mirror. Sodium's per-section records are
 *       mirrored on the GPU and patched on the five mutation hooks; the
 *       CPU walks the listed REGIONS only, and the task stage selects runs
 *       per section from the mirror. 0b draws Sodium's BFS set as a mask
 *       (pixel parity with rung 1 by construction); 0a
 *       ({@code -Dmeshelium.sodium.occlusion=true}) adds the box rasters
 *       and temporal history. Any per-frame reason it cannot own the frame
 *       (growth, a full staging ring, the ring guard, an invariant breach,
 *       a forced test decline) declines the whole frame to rung 1; a throw
 *       latches it off for the session.</li>
 *   <li><b>Rung 1</b>: the stage-1 list path ({@code drawOpaque},
 *       unchanged), with its own declines and {@code broken} latch. It is
 *       the parity control and still ships.</li>
 *   <li><b>Rung 2</b>: Sodium's own renderer, the delegate.</li>
 * </ul>
 *
 * <h2>Why the delegate is kept</h2>
 * <p>Because Meshelium consumes nothing. It reads Sodium's buffers and
 * writes none of Sodium's state, so at any moment handing the frame back
 * to Sodium's renderer is complete rather than partial. A renderer that
 * cannot hand the frame back turns any of its own failures into an
 * invisible world, which is the exact bug this whole effort started
 * from.</p>
 *
 * <h2>The seam</h2>
 * <p>{@code ChunkRenderer} is a three-method interface, and Sodium builds
 * its implementation into a {@code private final} field of
 * {@code RenderSectionManager} with a getter and no setter, so
 * substitution happens at construction; see
 * {@code RenderSectionManagerMixin}. The implementation, not the caller,
 * opens the render pass: {@code render} is called outside one, so
 * Meshelium is free to open its own over the same attachments.</p>
 *
 * <h2>Clean room</h2>
 * <p>Sodium is PolyForm Shield. This file implements Sodium's published
 * interface and calls Sodium's own objects; it contains no Sodium code and
 * none may ever be copied into it. Implementing an interface in order to
 * interoperate is not derivation.</p>
 */
public abstract class MesheliumChunkRendererBase {
    // The class that IMPLEMENTS Sodium's ChunkRenderer is MesheliumChunkRenderer,
    // one per Minecraft version under versions/mc<v>/sodium/: Sodium 0.9.2's
    // render(...) grew a RenderPass and an OitStage between 26.2 and 26.3 and
    // gained prepare(...), and an @Override cannot name two signatures. That
    // subclass is a constructor, render(...) calling draw(...) then the
    // delegate, and prepare(...) forwarded; every decision is in this file.


    /**
     * Sodium's own renderer. It draws every pass Meshelium declines, which
     * today includes all translucent geometry, and it is the landing place
     * for any failure.
     */
    protected final ChunkRenderer delegate;

    /** The manager this renderer sits in; null only through the legacy constructor. */
    private final RenderSectionManager manager;

    private final MesheliumSodiumDrawList drawList = new MesheliumSodiumDrawList();

    /** The CPU half of the record mirror; null without a manager (rung 0 never arms). */
    private final MesheliumRegionMirror mirror;

    /** The GPU half, created lazily on the first rung-0 attempt. */
    private SodiumTerrainDrawer.InstanceState gpu;

    private final MesheliumRegionMirror.FrameOutput frame = new MesheliumRegionMirror.FrameOutput();

    /** Sodium's frame counter at the last rung-0 attempt: one attempt per Sodium frame. */
    private int lastFrameSeen = Integer.MIN_VALUE;

    /** Rung-0 attempts: the serial the release lag and the staging ring count on. */
    private long attempts;

    /** Set by an owned SOLID call, consumed by the CUTOUT call. */
    private boolean frameOwned;

    /** Invariant declines in a row; three latch the GPU draw off. */
    private int invariantRun;

    private final float[] sceneTail = new float[4];

    private final int[] cameraBlock = new int[3];

    /**
     * Resolved once. The adapter needs a Vulkan device that was created
     * with mesh-shader support, which is decided long before this class
     * exists and never changes within a session.
     */
    private Boolean armed;

    /** The legacy seat: no manager, so rung 0 never arms and rungs 1-2 draw as in stage 1. */
    protected MesheliumChunkRendererBase(ChunkRenderer delegate) {
        this(delegate, null);
    }

    protected MesheliumChunkRendererBase(ChunkRenderer delegate, RenderSectionManager manager) {
        if (delegate == null) {
            throw new IllegalArgumentException(
                    "Meshelium's chunk renderer needs Sodium's to delegate to; without it there "
                            + "is no way to hand a frame back and a failure would draw nothing");
        }
        this.delegate = delegate;
        this.manager = manager;
        this.mirror = manager == null ? null : new MesheliumRegionMirror(manager);
    }

    /** Sodium's renderer, for the paths Meshelium does not take. */
    public ChunkRenderer delegate() {
        return this.delegate;
    }

    /**
     * @param useTranslucencySorting Sodium's own flag, and not, as the name
     *        of this parameter has been read before, block-face culling.
     *        It selects the region's sorted index buffer over the shared
     *        quad index buffer, and it is only ever true on a translucent
     *        pass, which Meshelium delegates.
     * @param globalsUbo the packed {@code u_Globals} uniform slice, into
     *        which Sodium has already folded this pass's matrices and fog
     * @param sectionTimeInfo Sodium's per-section fade timestamps
     */
    /**
     * @return true when Meshelium has recorded this pass and Sodium must
     *         not draw it again
     */
    protected final boolean draw(ChunkRenderMatrices matrices,
            ChunkRenderListIterable renderLists, TerrainRenderPass pass, CameraTransform camera,
            FogParameters fog, GpuSampler atlasSampler) {
        // Checked before any enumeration, because building a draw list is
        // the CPU work the bench's Sodium-alone leg exists to exclude.
        if (pass.isTranslucent() || !armed()) {
            return false;
        }
        if (!SodiumTerrainDrawer.enabled() || SodiumTerrainDrawer.broken()) {
            // Rung 1 latched, or the bench's Sodium-alone leg: rung 0 will
            // not attempt again this session (broken) or for now (disabled),
            // so a tracking mirror must stop feeding its dirty and dead
            // lists, which nothing would drain. sleep() is idempotent
            // (second stage-2/3 review, 2026-09-07).
            if (this.mirror != null) {
                this.mirror.sleep();
            }
            return false;
        }
        if (matrices == null || camera == null || renderLists == null) {
            return false;
        }
        RenderTarget target = pass.getTarget();
        GpuTextureView atlasView = pass.getAtlas();
        if (target == null || atlasView == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        GpuTextureView lightmapView = minecraft.gameRenderer.lightmap();

        boolean solid = pass == DefaultTerrainRenderPasses.SOLID;
        boolean cutout = pass == DefaultTerrainRenderPasses.CUTOUT;
        if (solid) {
            this.frameOwned = false;
            if (rung0Wanted()) {
                this.frameOwned = attemptRung0(matrices, renderLists, camera, fog, atlasSampler,
                        target, atlasView, lightmapView);
                if (this.frameOwned) {
                    return true;
                }
            } else if (this.mirror != null) {
                this.mirror.sleep();
            }
        } else if (cutout && this.frameOwned) {
            boolean ok;
            try {
                ok = SodiumTerrainDrawer.drawCutoutOwned(this.gpu,
                        target.getColorTextureView(), target.getDepthTextureView(),
                        atlasView, atlasSampler, lightmapView, solidNoDiscard());
            } catch (GpuDeviceLossException t) {
                throw t;
            } catch (Throwable t) {
                SodiumTerrainDrawer.latchGpuDraw(t);
                ok = false;
            } finally {
                this.frame.clear();
                this.frameOwned = false;
            }
            if (ok) {
                return true;
            }
            // Latched inside: this CUTOUT pass falls to rung 1.
        }
        return rung1(matrices, renderLists, pass, camera, atlasSampler, target, atlasView,
                lightmapView, solid ? 0 : (cutout ? 1 : -1));
    }

    // ------------------------------------------------------------------
    // Rung 0
    // ------------------------------------------------------------------

    /**
     * Has the record-stride probe run this session? One shot, on the first
     * frame rung 0 is wanted.
     */
    private static boolean strideChecked;

    /**
     * Ask SODIUM what its per-section record stride is, once, and stand the
     * MIRROR down if it disagrees with ours.
     *
     * <h2>Why a stride and not a version</h2>
     * <p>The version row on the settings screen and the startup WARN tell a
     * PLAYER that their Sodium is not the one this build was made for.
     * Neither can tell the RENDERER anything, because "a different version"
     * is not the same question as "is the one number this mirror is built
     * on still 48". This asks the number.
     *
     * <p>{@code SectionRenderDataUnsafe.heapPointer(long, int)} is public
     * static and its whole body is {@code lload_0; iload_2; i2l; ldc2_w
     * 48l; lmul; ladd; lreturn} (javap of
     * sodium-fabric-0.9.2-beta.1+mc26.2.jar), so {@code heapPointer(0L, 1)}
     * returns the live stride with no reflection, no version string and no
     * Sodium code copied anywhere.
     *
     * <h2>Why it stands the MIRROR down and not the adapter</h2>
     * <p>{@code SodiumTerrainDrawer.broken()} takes the WHOLE adapter out
     * of service, and the settings header then reads "a renderer error
     * occurred; vanilla is drawing", which under Sodium is the wrong
     * sentence twice over. {@code latchGpuDraw(String)} is the narrower
     * latch that exists for exactly this: one error line, {@code rung =
     * "1"}, {@code broken()} still false, and the stage-1 list path draws
     * every opaque pass from the next frame. Meshelium keeps drawing with
     * mesh shaders and the header keeps reading "Working with Sodium".
     *
     * <h2>Why it is NOT in MesheliumSodiumHooks</h2>
     * <p>That class is defined by naming nothing but {@code java.lang} (its
     * own heading, "Why it is a separate class with no imports", and the
     * paragraph under it), because {@code MesheliumSodiumMixinPlugin}
     * imports it and calls it from inside class transformation, before
     * Minecraft's classes are safe to touch. Putting a Sodium type and a
     * Blaze3D-naming type into its constant pool would undo the one
     * property it has. This class already names both, and the plugin does
     * not import it.
     */
    private static void checkRecordStride() {
        if (strideChecked) {
            return;
        }
        strideChecked = true;
        try {
            long stride = SectionRenderDataUnsafe.heapPointer(0L, 1);
            if (stride != SodiumGpuVisibilityLayout.RECORD_BYTES) {
                SodiumTerrainDrawer.latchGpuDraw("Sodium's per-section record stride is "
                        + stride + " bytes, not the " + SodiumGpuVisibilityLayout.RECORD_BYTES
                        + " this mirror is built on");
            }
        } catch (Throwable t) {
            // The probe itself failing is not a reason to draw a wrong
            // world, and not a reason to crash either: stand the mirror
            // down and let the list path draw.
            SodiumTerrainDrawer.latchGpuDraw(t);
        }
    }

    /**
     * Every precondition rung 0 needs that costs nothing to check: the
     * lever, the latch, a manager to mirror, a mesh+task device, all five
     * hooks found by the plugin and nothing having stood them down.
     */
    private boolean rung0Wanted() {
        checkRecordStride();
        return SodiumTerrainDrawer.gpuDrawEnabled()
                && !SodiumTerrainDrawer.gpuDrawBroken()
                && this.manager != null
                && this.mirror != null
                && MesheliumVulkanState.caps() != null
                && MesheliumSodiumHooks.hooksComplete()
                && !MesheliumSodiumHooks.disarmed();
    }

    /**
     * The SOLID call's rung-0 attempt, in the contract's order (section 3.5):
     * forced decline, device, ring guard, id lag, growth, commit, the commit
     * CB, the ring slot, the loop, the draw. Every decline reports its
     * reason and returns false, and the list path draws the frame whole;
     * a throw anywhere latches rung 0 off for the session.
     */
    private boolean attemptRung0(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists,
            CameraTransform camera, FogParameters fog, GpuSampler atlasSampler,
            RenderTarget target, GpuTextureView atlasView, GpuTextureView lightmapView) {
        int sodiumFrame = this.manager.getFrame();
        if (sodiumFrame == this.lastFrameSeen) {
            // A second SOLID call inside one Sodium frame (a panorama, a
            // second level render): the mirror is already committed for
            // this frame and the ring slot may still be read; rung 1.
            return false;
        }
        this.lastFrameSeen = sodiumFrame;
        if (this.gpu == null) {
            this.gpu = SodiumTerrainDrawer.newInstanceState();
        }
        SodiumTerrainDrawer.reportAttempt();
        long serial = ++this.attempts;
        try {
            this.mirror.ensureTracking();
            if (SodiumTerrainDrawer.takeRemirrorRequest()) {
                // The card refused a region three frames ago: its copy of
                // some row is not what was written for it, and nothing else
                // would ever mark that row dirty again. Before the commit,
                // so this frame is the one that repairs it.
                this.mirror.remirrorAll();
            }
            String forced = SodiumTerrainDrawer.takeForcedDecline();
            if (forced != null) {
                return decline("forced");
            }
            if (!SodiumTerrainDrawer.ensureResources(this.gpu)) {
                return decline("deviceNotUp");
            }
            SodiumMirrorGpu mirrorGpu = this.gpu.mirror();
            this.mirror.syncCapacity(mirrorGpu.capacity());
            if (SodiumFrameRing.guardTripped()) {
                return decline("ringGuard");
            }
            boolean occlusion = SodiumTerrainDrawer.occlusionEnabled()
                    && !SodiumTerrainDrawer.occlusionBroken()
                    && SodiumTerrainDrawer.prepareOcclusion(this.gpu);

            // 1. lagged ids become free; 2. growth (the frame declines, the
            // dirty set waits for next frame); 3-4. the commit and its CB.
            this.mirror.beginFrame(serial);
            int growTo = this.mirror.growthNeeded();
            if (growTo > 0) {
                if (SodiumTerrainDrawer.growInstance(this.gpu, growTo)) {
                    this.mirror.syncCapacity(growTo);
                }
                return decline("growth");
            }
            long commitStart = System.nanoTime();
            boolean[] declined = new boolean[1];
            int committed = this.mirror.commit(mirrorGpu, declined);
            mirrorGpu.endCommit();
            SodiumTerrainDrawer.reportCommit(committed, mirrorGpu.lastCommitBytes(),
                    System.nanoTime() - commitStart, this.mirror.deadRows(),
                    this.mirror.midsLive(), this.mirror.midsCapacity(),
                    this.mirror.midsReleased(), this.mirror.bufferKeys());
            if (declined[0]) {
                return decline(this.mirror.growthNeeded() > 0 ? "growth" : "staging");
            }
            if (SodiumTerrainDrawer.mirrorAuditEnabled()) {
                // This instance's own stats-frame index, not the static
                // probe's (which follows whichever instance drew last).
                this.mirror.audit(mirrorGpu, this.gpu.statsFrames());
            }

            // 5. the per-frame loop into this frame's ring slot.
            SodiumFrameRing ring = this.gpu.ring();
            ring.advance();
            boolean frustumRegions = occlusion && !SodiumTerrainDrawer.graphRegions();
            int listed = this.mirror.buildFrame(renderLists, camera, matrices.modelView(),
                    matrices.projection(), frustumRegions, ring, this.frame);
            SodiumTerrainDrawer.reportInvariant(this.mirror.midMissing(), this.mirror.keyMismatch());
            if (listed < 0) {
                this.frame.clear();
                if (++this.invariantRun >= 3) {
                    SodiumTerrainDrawer.latchGpuDraw("a mid/key invariant breach repeated "
                            + this.invariantRun + " frames running (midMissing="
                            + this.mirror.midMissing() + ", keyMismatch="
                            + this.mirror.keyMismatch() + ")");
                }
                return decline("invariant");
            }
            this.invariantRun = 0;

            // The distance gate's D and the facing formula's exact inputs.
            String gateMode = SodiumTerrainDrawer.distanceGateMode();
            float searchDistance = searchDistance(fog, gateMode);
            boolean distanceGate = !"off".equals(gateMode);
            this.sceneTail[0] = camera.fracX;
            this.sceneTail[1] = camera.fracY;
            this.sceneTail[2] = camera.fracZ;
            this.sceneTail[3] = searchDistance;
            this.cameraBlock[0] = camera.intX;
            this.cameraBlock[1] = camera.intY;
            this.cameraBlock[2] = camera.intZ;
            SodiumTerrainDrawer.reportFrameLoop(listed, this.frame.listedSections, this.frame.groupCount,
                    this.frame.loopNanos, this.frame.faceAll, searchDistance, this.searchDistanceSource);
            SodiumTerrainDrawer.reportDroppedRegions(this.frame.droppedMirrored,
                    this.frame.oneFrameGaps, listed);
            SodiumTerrainDrawer.reportUnreachable(this.frame.unreachableSections,
                    this.frame.unreachableRegions, this.frame.listedSections);

            boolean owned = SodiumTerrainDrawer.drawSolidOwned(this.gpu,
                    target.getColorTextureView(), target.getDepthTextureView(),
                    atlasView, atlasSampler, lightmapView,
                    matrices.modelView(), matrices.projection(),
                    camera.x, camera.y, camera.z, this.sceneTail, this.cameraBlock,
                    this.frame.groupBuffers, this.frame.groupKeys, this.frame.groupStart,
                    this.frame.groupCount, listed, this.frame.signature,
                    this.frame.faceAll, distanceGate, occlusion, solidNoDiscard());
            if (!owned) {
                this.frame.clear();
                return decline("deviceNotUp");
            }
            return true;
        } catch (GpuDeviceLossException t) {
            // A lost device is nobody's bug to recover from here.
            throw t;
        } catch (Throwable t) {
            this.frame.clear();
            SodiumTerrainDrawer.latchGpuDraw(t);
            return false;
        }
    }

    private boolean decline(String reason) {
        SodiumTerrainDrawer.reportDecline(reason);
        return false;
    }

    private String searchDistanceSource = "none";

    /**
     * D for the distance gate (contract section 6.3): Sodium's own private
     * {@code getSearchDistance(fog)} through the invoker when the plugin
     * found it and the lever says {@code fog}; else the effective render
     * distance in blocks (wider, never narrower).
     */
    private float searchDistance(FogParameters fog, String gateMode) {
        if ("fog".equals(gateMode) && fog != null && MesheliumSodiumHooks.searchDistanceFound()
                && this.manager instanceof RenderSectionManagerInvoker invoker) {
            try {
                float d = invoker.meshelium$getSearchDistance(fog);
                this.searchDistanceSource = "invoker";
                return d;
            } catch (Throwable ignored) {
                // Fall through to the widening.
            }
        }
        this.searchDistanceSource = "fallback";
        Minecraft client = Minecraft.getInstance();
        int rd = client != null && client.options != null ? client.options.getEffectiveRenderDistance() : 32;
        return rd * 16.0f;
    }

    /**
     * SOLID declares no fragment discard and its materials never need one;
     * CUTOUT does. The drawer keeps a pipeline per answer, but the
     * discard-free one is opt-in: measured slower at 1440p on the dev card
     * ({@link SodiumTerrainDrawer#PROPERTY_SOLID_NO_DISCARD}).
     */
    private static boolean solidNoDiscard() {
        return Boolean.getBoolean(SodiumTerrainDrawer.PROPERTY_SOLID_NO_DISCARD);
    }

    // ------------------------------------------------------------------
    // Rung 1: the stage-1 list path, unchanged in behaviour
    // ------------------------------------------------------------------

    /**
     * Built and consumed inside this call. Sodium's render lists and the
     * geometry records behind them are per-frame state that its arenas
     * rewrite as they defragment, so nothing derived from them may outlive
     * the pass.
     *
     * @param passIndex 0 = SOLID, 1 = CUTOUT, -1 = another opaque pass
     */
    private boolean rung1(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists,
            TerrainRenderPass pass, CameraTransform camera, GpuSampler atlasSampler,
            RenderTarget target, GpuTextureView atlasView, GpuTextureView lightmapView,
            int passIndex) {
        try {
            long buildStart = System.nanoTime();
            try {
                this.drawList.build(renderLists, pass, camera, matrices.modelView(),
                        matrices.projection());
            } catch (Throwable t) {
                // The enumeration reads Sodium's internals through its
                // public getters and is the first thing a Sodium update
                // breaks; it runs inside the same fallback drawOpaque's
                // own failures have.
                SodiumTerrainDrawer.failEnumeration(t);
                return false;
            }
            SodiumTerrainDrawer.reportQuads(this.drawList.quadsKept(), this.drawList.quadsCulled(),
                    System.nanoTime() - buildStart);
            SodiumTerrainDrawer.reportSectionsEmitted(passIndex, this.drawList.sectionsEmitted(),
                    this.drawList.quadsKept());
            // D-021: whether the per-region run cache ran this pass and
            // how much of the enumeration it replaced, for the bench and
            // the stand-down suite's parity leg.
            SodiumTerrainDrawer.reportRunCache(this.drawList.cacheArmed(),
                    this.drawList.regionsHit(), this.drawList.regionsRebuilt());
            // The frustum lever: runs pushed, and what the CPU test saw.
            SodiumTerrainDrawer.reportFrustum(this.drawList.runsPushed(),
                    this.drawList.runsFrustumRejected(), this.drawList.quadsFrustumRejected());
            boolean noDiscard = !pass.supportsFragmentDiscard() && solidNoDiscard();
            boolean drawn = SodiumTerrainDrawer.drawOpaque(
                    target.getColorTextureView(), target.getDepthTextureView(),
                    atlasView, atlasSampler, lightmapView,
                    matrices.modelView(), matrices.projection(),
                    this.drawList.buffers(), this.drawList.bufferCount(),
                    this.drawList.drawCount(), this.drawList.meta(), this.drawList.origins(),
                    this.drawList.regionCount(), noDiscard);
            if (drawn) {
                SodiumTerrainDrawer.reportRung("1");
            }
            return drawn;
        } finally {
            // A region can be deleted between frames, and its buffer with
            // it. Holding the reference here would keep a dead object
            // reachable for no reason.
            this.drawList.clearBufferRefs();
        }
    }

    /**
     * The cheap half of {@link #draw}'s early-outs, for the 26.3 subclass to
     * test before it suspends vanilla's main pass: a translucent pass, an
     * unarmed adapter or a drawer that is off or latched would be declined
     * by draw() before any GPU work, so they must not pay for a suspension
     * either. Anything draw() declines AFTER these still resumes the pass
     * and hands it to Sodium, at the cost of one end/begin pair.
     */
    protected final boolean wantsDraw(TerrainRenderPass pass) {
        return pass != null && !pass.isTranslucent() && armed()
                && SodiumTerrainDrawer.enabled() && !SodiumTerrainDrawer.broken();
    }

    protected final boolean armed() {
        Boolean a = this.armed;
        if (a == null) {
            // Keyed on the gate's answer rather than on the raw
            // meshShadersRequested() flag: that flag is stamped before a
            // VULKAN-selected boot can still fail inside createDevice and
            // fall back to OpenGL, and the gate reads the device that
            // actually exists (owner report 2026-09-08 (beta.8)). The gate
            // decides on the first end-tick after the loading overlay
            // clears, long before any level draws a pass; should a pass
            // ever arrive first, latching "not armed" for the session off
            // an undecided gate would be the wrong permanent answer, so an
            // undecided gate is not latched at all.
            if (MesheliumGate.state() == MesheliumGate.State.UNKNOWN) {
                return false;
            }
            a = MesheliumGate.sodiumAdapterArmed()
                    && MesheliumVulkanState.caps() != null;
            this.armed = a;
            if (a) {
                MesheliumLog.LOGGER.info(
                        "Meshelium is drawing Sodium's opaque terrain with mesh shaders. Sodium "
                                + "keeps the chunk building and the translucent pass; the "
                                + "geometry is read out of Sodium's own region buffers with no "
                                + "copy and no re-encode.");
            } else {
                MesheliumLog.LOGGER.warn(
                        "Meshelium is in Sodium's chunk-renderer slot but has no mesh-shader "
                                + "device to draw with, so every pass is forwarded to Sodium "
                                + "unchanged. This is the OpenGL backend, or a GPU without "
                                + "VK_EXT_mesh_shader.");
            }
        }
        return a;
    }

    public void rotate() {
        this.delegate.rotate();
    }

    /**
     * Sodium calls this from {@code RenderSectionManager.destroy()} AFTER
     * every region's {@code delete()} (javap: {@code regions.delete()} then
     * {@code chunkRenderer.delete()}), so every id has been released and
     * every row queued dead before the instance retires behind vanilla's
     * deferred-destroy rotation.
     */
    public void delete() {
        this.frame.clear();
        this.frameOwned = false;
        if (this.mirror != null) {
            this.mirror.retire();
        }
        if (this.gpu != null) {
            SodiumTerrainDrawer.retire(this.gpu);
            this.gpu = null;
        }
        this.delegate.delete();
    }
}
