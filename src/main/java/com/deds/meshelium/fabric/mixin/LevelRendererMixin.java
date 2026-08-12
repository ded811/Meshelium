package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.MesheliumBenchRecorder;
import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumCpuStages;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.vk.HelloMeshletRenderer;
import com.deds.meshelium.vk.MesheliumTerrainPump;
import com.deds.meshelium.vk.TerrainDrawer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.util.profiling.ProfilerFiller;

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
 * Wave-2 mixin, frame-path doc shopping-list row 1: inject into the main
 * pass body — the synthetic private instance method
 * {@code LevelRenderer.lambda$addMainPass$0} (descriptor javap-verified
 * against the 26.2 jar, identical to the recon's) — immediately AFTER the
 * first {@code ChunkSectionsToRender.renderGroup} call. At that point the
 * OPAQUE terrain pass has been recorded AND closed (renderGroup owns its
 * pass, Q2.4), the encoder is between passes, and the depth buffer holds
 * exactly solid+cutout terrain — the recon's chosen seam (Q2.6a/Q6 step 1).
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
 * <p>Ordering of the guards: the {@link MesheliumGate} check runs FIRST so
 * that on the OpenGL path (and on Vulkan-without-mesh-shaders)
 * {@link HelloMeshletRenderer} — a class that imports LWJGL Vulkan — is
 * never even class-loaded, preserving wave 1's "no Vulkan classes on the
 * GL path" discipline. The renderer itself re-checks its system-property
 * gate and does all real-work error handling; the catch here only covers
 * failures the renderer cannot see (its own class-load failing), logged
 * once and then permanently silenced — a broken triangle must never crash
 * the frame loop.</p>
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {

    @Unique
    private static boolean meshelium$helloHookBroken;

    @Unique
    private static boolean meshelium$pumpHookBroken;

    @Unique
    private static boolean meshelium$frameStateHookBroken;

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
        if (meshelium$frameStateHookBroken
                || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS
                || !MesheliumConfig.terrainRenderingEnabled()) {
            return;
        }
        try {
            TerrainDrawer.beginFrame(cameraRenderState);
        } catch (Throwable t) {
            meshelium$frameStateHookBroken = true;
            MesheliumClient.LOGGER.error(
                    "Meshelium frame-state capture failed; terrain drawing will stay off "
                            + "(drawOpaque never cancels without camera state)", t);
        }
    }

    @Inject(
            method = "lambda$addMainPass$0(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                    + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
                    + "Lnet/minecraft/util/profiling/ProfilerFiller;"
                    + "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
                    + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                    + "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
                    + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                    + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                    + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                    + "Lcom/mojang/blaze3d/resource/ResourceHandle;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup("
                            + "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
                            + "Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER
            )
    )
    private void meshelium$afterOpaqueTerrain(GpuBufferSlice fogBuffer, LevelRenderState levelRenderState,
            ProfilerFiller profiler, ChunkSectionsToRender chunkSectionsToRender,
            ResourceHandle<?> mainTargetHandle, FeatureRenderDispatcher.PreparedFrame preparedFrame,
            ResourceHandle<?> translucentTargetHandle, ResourceHandle<?> itemEntityTargetHandle,
            ResourceHandle<?> particlesTargetHandle, ResourceHandle<?> weatherTargetHandle,
            CallbackInfo ci) {
        if (meshelium$helloHookBroken || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
            return;
        }
        try {
            HelloMeshletRenderer.afterOpaqueTerrain(levelRenderState);
        } catch (Throwable t) {
            meshelium$helloHookBroken = true;
            MesheliumClient.LOGGER.error(
                    "Meshelium hello-meshlet hook failed outside the renderer's own guard; "
                            + "disabling the hook for this session", t);
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
            MesheliumClient.LOGGER.error(
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
            MesheliumClient.LOGGER.error(
                    "Meshelium terrain pump hook failed outside the pump's own guard; "
                            + "disabling the hook for this session", t);
        }
    }
}
