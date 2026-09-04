/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumBenchRecorder;
import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumCpuStages;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.terrain.host.TerrainResidency;
import com.deds.meshelium.vk.MesheliumTerrainPump;
import com.deds.meshelium.vk.TerrainDrawer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;

import org.joml.Matrix4fc;
import org.joml.Vector4f;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumMap;
import java.util.List;

/**
 * Frame-path hooks on {@link LevelRenderer}: the geometry-invalidation
 * notice and the per-frame state capture the terrain drawer needs.
 *
 * <p><b>The wave-2 hello-meshlet hook USED to live here</b>, injected into
 * the synthetic {@code LevelRenderer.lambda$addMainPass$0} after the first
 * {@code renderGroup} call. It moved to
 * {@code ChunkSectionsToRenderMixin} — the recon's own documented
 * fallback, quoted below — because a synthetic lambda descriptor is the
 * most fragile anchor in the codebase: it is generated, unnamed in source,
 * and it renames whenever anything above it in the method changes. That is
 * not hypothetical. NeoForge 26.2 adds a {@code Matrix4fc} parameter to
 * {@code addMainPass} for its own render event, the lambda's descriptor
 * changes with it, and the old one does not exist at all; with
 * {@code defaultRequire = 1} that is a hard startup crash rather than a
 * degraded frame. The replacement anchor is a real named method on a class
 * NeoForge does not patch, so it holds on both loaders.</p>
 *
 * <p>The 26.2 era is unobfuscated and loom applies no remap, so the
 * synthetic name in dev IS the shipped name; {@code defaultRequire = 1}
 * (mixin config) makes any future rename a loud apply-failure instead of a
 * silent no-draw. If that ever fires, the recon's documented fallback is
 * the injection below — same recording position, from inside the callee,
 * filtered to the OPAQUE group:</p>
 *
 * <pre>
 * // FALLBACK (docs/VANILLA-FRAME-PATH.md Q6 shopping list, row-1 note):
 * // target ChunkSectionsToRender instead of the lambda —
 * //   &#64;Mixin(ChunkSectionsToRender.class)
 * //   &#64;Inject(method = "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
 * //           + "Lcom/mojang/blaze3d/textures/GpuSampler;)V", at = &#64;At("TAIL"))
 * //   private void meshelium$afterRenderGroup(ChunkSectionLayerGroup group, GpuSampler sampler,
 * //           CallbackInfo ci) {
 * //       if (group == ChunkSectionLayerGroup.OPAQUE
 * //               && MesheliumGate.state() == MesheliumGate.State.VULKAN_MESH_SHADERS) {
 * //           HelloMeshletRenderer.afterOpaqueTerrain(null); // no LevelRenderState here:
 * //           // NDC triangle only — the world-space UBO needs the camera state the
 * //           // lambda target hands us for free.
 * //       }
 * //   }
 * </pre>
 *
 * <p>The guard ordering that paragraph described moved with the hook, and
 * the rule behind it still binds wherever a Vulkan-touching class is
 * called from a mixin: check {@link MesheliumGate} FIRST, so that on the
 * OpenGL path (and on Vulkan without mesh shaders) no class importing
 * LWJGL Vulkan is even loaded. That is wave 1's "no Vulkan classes on the
 * GL path" discipline, and it is a class-loading rule rather than a
 * correctness one, so it cannot be recovered by a try/catch further
 * in.</p>
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {

    @Unique
    private static boolean meshelium$pumpHookBroken;

    @Unique
    private static boolean meshelium$frameStateHookBroken;

    // 2026-08-18 attribution wave brackets (ARMED-gated, JIT-dead otherwise).
    @Unique
    private static long meshelium$levelRenderT0;

    @Unique
    private static long meshelium$compileT0;

    /**
     * SEAM step 3 (pre16): {@code invalidateCompiledGeometry} HEAD - the
     * reload storm's front door. This method runs
     * {@code viewArea.releaseAllBuffers()} at ip 138 (javap, 26.2 merged
     * jar: iterate the RotatingSectionStorage, {@code reset()} every
     * node) and swaps in the new ViewArea at ip 149/184, all on the
     * render thread; every one of those resets frees a mesh whose
     * coverage test must be judged against the render distance THIS
     * reload is applying (ip 173-176 reads it from the same Options for
     * the new ViewArea). The walker only notices the reload next pump
     * (ViewArea identity), so without this hook a shrink storm would
     * park against the old, larger disc. One static call; the residency
     * gates it on its own sticky {@code farEverArmed} arm, so a session
     * that never enabled the far field pays two field reads and no far
     * class-load - and {@code TerrainResidency} itself is loaded in
     * every session by the pump wiring, so naming it here breaks no
     * dormancy rule.
     */
    @Inject(
            method = "invalidateCompiledGeometry("
                    + "Lnet/minecraft/client/multiplayer/ClientLevel;"
                    + "Lnet/minecraft/client/Options;"
                    + "Lnet/minecraft/client/Camera;"
                    + "Lnet/minecraft/client/color/block/BlockColors;)V",
            at = @At("HEAD")
    )
    private void meshelium$beforeGeometryInvalidated(ClientLevel level, Options options,
            Camera camera, BlockColors blockColors, CallbackInfo ci) {
        // Gate-checked like RenderSectionMixin's hooks, and for the same
        // reason: on the OpenGL path TerrainResidency must never be the
        // class this mixin loads (wave-1 dormancy). On Vulkan it is
        // loaded in every session by the pump wiring.
        if (MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
            return;
        }
        TerrainResidency.onVanillaGeometryInvalidated(
                options.getEffectiveRenderDistance());
    }

    /**
     * Wave-4 frame-state capture: {@code LevelRenderer.render} HEAD hands
     * the drawer this frame's {@code CameraRenderState} (public matrices,
     * camera pos, cull frustum — frame-path Q2.5's table) before the frame
     * graph executes the main pass where the kill switch fires. Triple-
     * gated exactly like the kill switch: gate + live re-read of the
     * effective terrain-rendering setting (wave 8: property ?? config —
     * the {@code MesheliumConfig} matrix), so with rendering off (or on
     * OpenGL) {@code TerrainDrawer} is never class-loaded and nothing —
     * not even a field write — happens.
     */
    @Inject(
            method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
                    + "Lnet/minecraft/client/DeltaTracker;Z"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
                    + "Lorg/joml/Matrix4fc;"
                    + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                    + "Lorg/joml/Vector4f;Z)V",
            at = @At("HEAD")
    )
    private void meshelium$captureFrameState(GraphicsResourceAllocator graphicsResourceAllocator,
            DeltaTracker deltaTracker, boolean renderBlockOutline, CameraRenderState cameraRenderState,
            Matrix4fc modelView, GpuBufferSlice fogBuffer, Vector4f clearColor, boolean renderSky,
            CallbackInfo ci) {
        // Wave-9 bench clock: BEFORE the gate checks, so the benchmark's
        // vanilla-baseline half (meshelium.terrainDraw flipped OFF) still
        // captures frame times. ARMED is a static final resolved from the
        // meshelium.bench property — provably false (and JIT-dead) on every
        // normal run; the recorder is pure JDK, GL-path-safe.
        if (MesheliumBenchRecorder.ARMED) {
            MesheliumBenchRecorder.onRenderFrame();
        }
        // 2026-08-18 attribution wave: the stage-row frame boundary lives at
        // the SAME hook as the bench recorder's frame delta, so rows tile
        // deltas exactly; levelRender brackets this whole method.
        if (MesheliumCpuStages.ARMED) {
            MesheliumCpuStages.beginFrame();
            meshelium$levelRenderT0 = System.nanoTime();
        }
        if (meshelium$frameStateHookBroken
                || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS
                || !MesheliumConfig.terrainRenderingEnabled()) {
            return;
        }
        try {
            TerrainDrawer.beginFrame(cameraRenderState);
        } catch (Throwable t) {
            meshelium$frameStateHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium frame-state capture failed; terrain drawing will stay off "
                            + "(drawOpaque never cancels without camera state)", t);
        }
    }

    @Unique
    private static boolean meshelium$skipPrepBroken;

    @Unique
    private static long meshelium$prepareT0;

    /**
     * Wave-12: bracket + candidate seam on {@code prepareChunkRenders(Matrix4fc)}
     * — the per-frame {@code ChunkSectionsToRender} build (frame-path Q2.5:
     * one draw-list entry per layer per visible section, one
     * {@code DynamicUniforms$ChunkSectionInfo} UBO write per visible
     * section, all under {@code dispatcher.lock()}). Two jobs:
     *
     * <p><b>(1) CPU stage bracket</b> ({@code MesheliumCpuStages.ARMED} only,
     * JIT-dead otherwise) plus the {@code visibleSections.size()} sample —
     * the scale term the stage's cost tracks (quadratic in render
     * distance).</p>
     *
     * <p><b>(2) The {@code meshelium.tune.skipVanillaPrep} candidate
     * (DEFAULT OFF — property absent ⇒ this handler returns before touching
     * anything, byte-identical behaviour).</b> When Meshelium will own BOTH
     * terrain groups this frame (the exact kill-switch predicate, evaluated
     * predictively by {@code TerrainDrawer.wouldOwnFrame()} — gate ∧ config
     * ∧ no latch ∧ coverage guard clean ∧ camera/targets present), the
     * entire work product of this method is dead: the jar-wide consumer
     * census (wave-12 notes, FRAME-PATH) proves {@code ChunkSectionsToRender}
     * is only ever consumed by its own {@code renderGroup} (which the
     * wave-4/7 kill switch cancels) and by Meshelium's own
     * {@code textureView()} read — so this returns a minimal record
     * carrying the REAL atlas view (vanilla's exact lookup, bytecode ip
     * 80–89: {@code textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS)
     * .getTextureView()}) with empty per-layer draw maps, 0 indices and an
     * empty UBO-slice array — the same shape vanilla itself builds when
     * {@code sectionRenderDispatcher == null} (ip 110–114's jump), and one
     * a stray {@code renderGroup} call renders as zero draws (bytecode:
     * {@code maxIndicesRequired == 0} ⇒ null shared index buffer; empty
     * maps ⇒ empty draw-group loops).
     *
     * <p><b>The one-frame edge, stated honestly:</b> the prediction runs
     * BEFORE the frame graph; if the drawer then throws its FIRST error
     * mid-frame (latch), vanilla's renderGroup runs this one frame with the
     * empty record — one frame without terrain, after which the latch makes
     * {@code wouldOwnFrame()} false and vanilla is whole again. Mid-frame
     * property/config flips cannot split prediction from kill switch: both
     * run on the render thread inside one {@code LevelRenderer.render} call
     * and the harness/options flips land between frames on that same
     * thread. {@code TerrainDrawer} counts any occurrence
     * ({@code prepSkipHoleFrames}) and WARNs once — the bench protocol
     * requires that counter be ZERO for a valid A/B leg.</p>
     */
    @Inject(
            method = "prepareChunkRenders(Lorg/joml/Matrix4fc;)"
                    + "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void meshelium$beforePrepareChunkRenders(Matrix4fc viewRotationMatrix,
            CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        if (MesheliumCpuStages.ARMED) {
            meshelium$prepareT0 = System.nanoTime();
            MesheliumCpuStages.noteVisibleSections(
                    ((LevelRenderer) (Object) this).visibleSections().size());
        }
        // The candidate. Gate order matters: property first (absent = free),
        // then the wave-1 gate BEFORE any TerrainDrawer reference so the
        // LWJGL-importing drawer never class-loads on the GL path.
        if (!MesheliumConfig.skipVanillaPrepEnabled()
                || meshelium$skipPrepBroken
                || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS
                || !MesheliumConfig.terrainRenderingEnabled()) {
            return;
        }
        try {
            if (!TerrainDrawer.wouldOwnFrame()) {
                return;
            }
            GpuTextureView atlasView = Minecraft.getInstance().getTextureManager()
                    .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
            if (atlasView == null) {
                return; // drawer would refuse a null atlas — vanilla preps
            }
            EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>>
                    drawGroups = new EnumMap<>(ChunkSectionLayer.class);
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                drawGroups.put(layer, new Int2ObjectOpenHashMap<>());
            }
            TerrainDrawer.notePrepSkipped();
            if (MesheliumCpuStages.ARMED) {
                // The cancel path never reaches the RETURN bracket — close
                // the stage here so skip frames report their (tiny) cost.
                MesheliumCpuStages.record(MesheliumCpuStages.STAGE_PREPARE_CHUNKS,
                        System.nanoTime() - meshelium$prepareT0);
            }
            cir.setReturnValue(new ChunkSectionsToRender(
                    atlasView, drawGroups, 0, new GpuBufferSlice[0]));
        } catch (Throwable t) {
            meshelium$skipPrepBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium skipVanillaPrep failed; vanilla prepareChunkRenders resumes for "
                            + "this session (first and only report)", t);
        }
    }

    /** Wave-12 stage close for the uncancelled (vanilla-prep) path. */
    @Inject(
            method = "prepareChunkRenders(Lorg/joml/Matrix4fc;)"
                    + "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;",
            at = @At("RETURN")
    )
    private void meshelium$afterPrepareChunkRenders(Matrix4fc viewRotationMatrix,
            CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        if (!MesheliumCpuStages.ARMED) {
            return;
        }
        MesheliumCpuStages.record(MesheliumCpuStages.STAGE_PREPARE_CHUNKS,
                System.nanoTime() - meshelium$prepareT0);
    }

    /**
     * Wave-3b pump hook, section-build doc shopping-list row 6: inject
     * into {@code LevelRenderer.render} immediately AFTER
     * {@code sectionRenderDispatcher.uploadTerrainBuffersToGpu()} — inside
     * vanilla's {@code lock()}/{@code unlock()} window (bytecode ip
     * 647-658: lock → upload → THIS → unlock), on the render thread, with
     * no render pass open. Meshelium records its staging→arena copies on a
     * transient command buffer spliced into the same submission vanilla's
     * terrain copies just joined, so both sides share one fence timeline
     * (frame-path Q1.2 — the basis of the 3-frame free discipline).
     *
     * <p>{@code lock()} IS {@code copyLock} (bytecode), so every mesh free
     * in the game is serialized against this pump by vanilla itself.</p>
     */
    @Inject(
            method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
                    + "Lnet/minecraft/client/DeltaTracker;Z"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
                    + "Lorg/joml/Matrix4fc;"
                    + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                    + "Lorg/joml/Vector4f;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;"
                            + "uploadTerrainBuffersToGpu()V",
                    shift = At.Shift.AFTER
            )
    )
    private void meshelium$afterTerrainUpload(CallbackInfo ci) {
        // compileUpload closes here regardless of the gate: the window is
        // vanilla's own compileSections + uploadTerrainBuffersToGpu (inline
        // compiles and staging drains live in it), interesting on vanilla
        // legs too. The pump below stays gate-guarded as before.
        if (MesheliumCpuStages.ARMED && meshelium$compileT0 != 0) {
            MesheliumCpuStages.record(MesheliumCpuStages.STAGE_COMPILE_UPLOAD,
                    System.nanoTime() - meshelium$compileT0);
            meshelium$compileT0 = 0;
        }
        if (meshelium$pumpHookBroken || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
            return;
        }
        try {
            // Wave-12 stage (e): the residency pump's render-thread cost —
            // measured at the hook so standup + pump + retention sweeps all
            // land in one honest bracket. JIT-dead unless ARMED.
            if (MesheliumCpuStages.ARMED) {
                long t0 = System.nanoTime();
                MesheliumTerrainPump.afterVanillaTerrainUpload();
                MesheliumCpuStages.record(MesheliumCpuStages.STAGE_RESIDENCY_PUMP,
                        System.nanoTime() - t0);
            } else {
                MesheliumTerrainPump.afterVanillaTerrainUpload();
            }
        } catch (Throwable t) {
            meshelium$pumpHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium terrain pump hook failed outside the pump's own guard; "
                            + "disabling the hook for this session", t);
        }
    }

    /**
     * 2026-08-18 attribution wave: stamp the start of vanilla's
     * compileSections + uploadTerrainBuffersToGpu window (javap-verified:
     * {@code private void compileSections(CameraRenderState)}). The close
     * lives in {@link #meshelium$afterTerrainUpload} — together they light
     * the one untimed render-thread window where inline compiles and
     * staging drains live, the storm suspect of the frame-gap analysis.
     */
    @Inject(
            method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
                    + "Lnet/minecraft/client/DeltaTracker;Z"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
                    + "Lorg/joml/Matrix4fc;"
                    + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                    + "Lorg/joml/Vector4f;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;compileSections("
                            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
            )
    )
    private void meshelium$beforeCompileSections(CallbackInfo ci) {
        if (MesheliumCpuStages.ARMED) {
            meshelium$compileT0 = System.nanoTime();
        }
    }

    /** 2026-08-18 attribution wave: close the whole-render-span bracket. */
    @Inject(
            method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
                    + "Lnet/minecraft/client/DeltaTracker;Z"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
                    + "Lorg/joml/Matrix4fc;"
                    + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                    + "Lorg/joml/Vector4f;Z)V",
            at = @At("RETURN")
    )
    private void meshelium$afterRender(CallbackInfo ci) {
        if (MesheliumCpuStages.ARMED && meshelium$levelRenderT0 != 0) {
            MesheliumCpuStages.record(MesheliumCpuStages.STAGE_LEVEL_RENDER,
                    System.nanoTime() - meshelium$levelRenderT0);
            meshelium$levelRenderT0 = 0;
        }
    }
}
