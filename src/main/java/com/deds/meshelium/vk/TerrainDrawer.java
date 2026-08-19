/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumCpuStages;
import com.deds.meshelium.MesheliumVulkanState;
import com.deds.meshelium.fabric.MesheliumClient;
import com.mojang.blaze3d.GpuDeviceLossException;
import com.deds.meshelium.fabric.mixin.FrustumAccessor;
import com.deds.meshelium.fabric.mixin.RenderPassAccessor;
import com.deds.meshelium.fabric.mixin.VulkanRenderPassAccessor;
import com.deds.meshelium.terrain.QuadFacing;
import com.deds.meshelium.terrain.host.SectionBuildTap;
import com.deds.meshelium.terrain.host.TerrainResidency;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Waves 4-7: terrain drawn by mesh shaders from the wave-3 GPU data,
 * recorded in Meshelium's OWN RenderPasses over the SAME attachments
 * vanilla's cancelled {@code renderGroup} calls would have used. Wave 5
 * moved per-section culling onto the GPU's task stage and turned the draw
 * loop into per-region dispatches; wave 6 made GPU-raster occlusion the
 * default visibility source — Nvidium's temporal two-phase scheme
 * ({@link #drawOcclusionCulled}; representation + pass/barrier story in
 * {@link TerrainOcclusion}) — with the wave-5 BFS mask feed kept verbatim
 * as the correctness fallback. Wave 7 extends the kill switch to the
 * TRANSLUCENT group ({@link #drawTranslucent}): host-sorter-first —
 * vanilla's own sort is the ordering authority at both granularities
 * (order sources cited on the method), drawn through the blend pipeline
 * variant, occlusion-gated per section on occlusion frames.
 *
 * <h2>Wave-11 — how RETAINED sections stay visible</h2>
 * Retained entries (vanilla released the mesh; residency kept the copy)
 * ride the same snapshot/region records, so the three opaque paths need
 * almost nothing: the OCCLUSION path (default) and the cpuCull hatch draw
 * them exactly like live sections (records/frustum/stamps carry all the
 * per-section truth); only the BFS-MASK path has a structural gap —
 * vanilla's {@code visibleSections} can never list a released section —
 * closed by OR-ing {@code DrawSnapshot.retainedMasks} into the per-region
 * masks (fail OPEN for retained bits; the cheap choice — no record or
 * shader change). The translucent pass gains a farthest-first pre-pass
 * for retained prefixes (they keep their LAST sort — stale-sort accepted,
 * Nvidium's behaviour) with drawn-marks so rebuild churn can never blend
 * a section twice. Retention is invisible until something is released:
 * in a world where nothing ever unloaded the retained set is empty and
 * every path is byte-identical to wave 10 — the standing parity shots
 * (40/41, 60/61) prove exactly that with the toggle at its default.
 *
 * <h2>Gating</h2>
 * Unchanged from wave 4: {@code MesheliumGate.state()==VULKAN_MESH_SHADERS}
 * AND the {@code meshelium.terrainDraw} property re-read EVERY call; property
 * off ⇒ the mixins return before this class loads. Escape hatches, both
 * re-read every call: {@code meshelium.terrainDraw.cpuCull} forces the
 * wave-4 CPU-culled per-section path (same pixels, no task stage);
 * {@code meshelium.terrainDraw.bfsOnly} reverts to the wave-5 behaviour
 * ENTIRELY (single-phase draw, vanilla-BFS masks, zero occlusion work) —
 * also engaged automatically for the session if occlusion ever fails
 * ({@link #occlusionError()} latches, {@link #lastError()} stays null,
 * terrain keeps drawing).
 *
 * <h2>Wave-5 frame (task path — since wave 6 the {@code bfsOnly}
 * fallback; the default occlusion frame is documented on
 * {@link #drawOcclusionCulled})</h2>
 * <ol>
 *   <li><b>Visibility feed:</b> read vanilla's own {@code visibleSections}
 *       (the SectionOcclusionGraph BFS output — the very list vanilla's
 *       {@code prepareChunkRenders} built this frame's draws from, same
 *       render thread, same {@code LevelRenderer.render} call, mutated only
 *       by {@code sectionOcclusionGraph.update} AFTER rendering) and write
 *       a 256-bit per-region section mask. This kills the wave-4 gap:
 *       sections vanilla no longer lists (BFS-occluded caves, sections
 *       retained after their chunk unloaded) stop being drawn. Wave 6's
 *       GPU-rasterised occlusion replaced this feed as the culling source;
 *       the feed stays as the correctness fallback.</li>
 *   <li><b>CPU region cull:</b> frustum over whole 128×64×128 regions
 *       (a few hundred at most) via {@code cam.cullFrustum.isVisible};
 *       regions whose mask is all-zero are skipped outright.</li>
 *   <li><b>Uploads:</b> the 192-byte scene UBO (view matrix, atlas size,
 *       the six Gribb-Hartmann planes of the RENDER ProjMat*ModelViewMat —
 *       vanilla's exact extraction/test machinery per JOML
 *       FrustumIntersection, bytecode-verified; vanilla's own cull frustum
 *       is deliberately looser, see the wave-5 notes — and the camera
 *       section coords) plus the 16 KiB visibility-mask UBO, both out of
 *       vanilla's transient memory before the pass opens.</li>
 *   <li><b>Dispatch:</b> per surviving region one {@code vkCmdPushConstants}
 *       {region origin rel camera, region record index, mask slot} +
 *       {@code vkCmdDrawMeshTasksEXT(ceil(count/32),1,1)}. terrain.task
 *       culls per section (frustum planes, visibility bit, facing buckets)
 *       and launches mesh workgroups through the payload; terrain.mesh is
 *       wave 4's shader reading its base/count from the payload.</li>
 * </ol>
 *
 * <h2>Parity argument (why culling cannot remove a visible section)</h2>
 * Every cut is one of: (a) the render clip volume's own planes against a
 * 0.5-block-inflated section box — a box fully outside one render plane
 * has ALL its geometry outside that plane, hence zero pixels (this is
 * strictly independent of vanilla's looser cull frustum); (b) vanilla's
 * own visibleSections list (bfsOnly mode) — vanilla draws EXACTLY that
 * list, so masking to it removes only what vanilla itself would not draw;
 * (c) the wave-4 facing-bucket gates (back-facing quads only); (d) wave-6
 * occlusion — a section is skipped this frame ONLY when its inflated,
 * geometry-superset box produced no depth-passing fragment against the
 * phase-A depth AND it wasn't visible last frame. Phase-A depth is a
 * SUBSET of the final frame's opaque depth (real terrain drawn this
 * frame), so a box failing everywhere means every geometry pixel loses
 * the final depth test too — zero pixels removed; the full guard
 * enumeration lives in the wave-6 notes (VANILLA-FRAME-PATH.md). The
 * overflow paths fail OPEN (mask-UBO overflow ⇒ sentinel ⇒ every section
 * passes; occlusion-list overflow ⇒ the region draws maskless in phase
 * A). If this argument were wrong the A/B pairs (shots 40/41, 50/51)
 * would show it as missing sections in the Meshelium shot.
 *
 * <h2>Failure containment</h2>
 * Wave-2 pattern, unchanged: any throwable latches {@code broken} +
 * {@link #lastError()}, logs ONCE, and every later call returns false — the
 * mixin then leaves vanilla's renderGroup uncancelled.
 */
public final class TerrainDrawer {

    /** System property; re-read EVERY call so tests can flip it live. */
    public static final String PROPERTY = "meshelium.terrainDraw";

    /**
     * Escape hatch, re-read every call: forces the wave-4 CPU-culled
     * per-section direct path (no task stage). Documented in the SPEC
     * wave-5 row; the harness asserts it still renders.
     */
    public static final String PROPERTY_CPU_CULL = "meshelium.terrainDraw.cpuCull";

    /**
     * Wave-6 correctness fallback, re-read every call: reverts to the
     * wave-5 behaviour ENTIRELY — single-phase draw, vanilla-BFS mask
     * feed, no occlusion passes, no stamp buffers touched. Also engaged
     * automatically (with {@link #occlusionError} latched) if the
     * occlusion resources ever fail. Precedence: cpuCull &gt; bfsOnly &gt;
     * occlusion.
     */
    public static final String PROPERTY_BFS_ONLY = "meshelium.terrainDraw.bfsOnly";

    // terrain.task VisMode values (push constant; see the shader header).
    static final int MODE_MASK = 0;
    static final int MODE_PHASE_A = 1;
    static final int MODE_PHASE_B = 2;

    /**
     * Default quads per mesh workgroup: 32 quads = 128 vertices / 64
     * primitives — inside every VK_EXT_mesh_shader minimum
     * (maxMeshWorkGroupInvocations ≥ 128, maxMeshOutput* ≥ 256) and one
     * wavefront on RDNA in wave32 mode. Verified against the REAL device's
     * caps at pipeline creation (the wave-1 probe values), logged there;
     * wave 9 retunes via the MESHELIUM_WG_SIZE macro.
     */
    static final int WORKGROUP_QUADS = 32;

    /**
     * Sections per task workgroup (one invocation each): 32 ⇒ a full
     * 256-section region dispatches as ceil(count/32) ≤ 8 workgroups in ONE
     * {@code vkCmdDrawMeshTasksEXT}. Chosen inside the spec-guaranteed
     * {@code maxTaskWorkGroupInvocations ≥ 128} (the 9070 XT reports 1024
     * and prefers 1024 — a 256-invocation whole-region workgroup is legal
     * THERE but not portable, and its payload would break the spec-minimum
     * {@code maxTaskPayloadSize = 16384}: 256 × 80 B > 16 KiB; 32 × 80 B =
     * 2.5 KiB fits everywhere). Host-injected as MESHELIUM_TASK_WG_SIZE —
     * the wave-9 per-vendor knob, same pattern as MESHELIUM_WG_SIZE.
     */
    static final int TASK_WG_SECTIONS = 32;

    /**
     * STANDARD visibility-mask UBO capacity in regions per frame. 512 ×
     * 32 B = 16384 B = the spec-minimum {@code maxUniformBufferRange}, so
     * the declared GLSL array is bindable on every conformant device.
     * Typical rd-32 frustum-visible region counts are a few hundred
     * (RegionStore sizing note); overflow regions dispatch with the
     * no-mask sentinel — culling degrades, parity never (the task stage
     * fails open). <b>Wave 10:</b> extended-render-distance worlds grow
     * the per-frame capacity to the pinned
     * {@code MesheliumScaling.dispatchCapacity()} (= the whole region
     * budget — overflow becomes structurally unreachable) and the masks
     * move to {@link MesheliumFrameLists}' host-visible STORAGE ring with
     * the SSBO pipeline variant; this constant remains the standard-mode
     * value and the fallback when the ring is unavailable.
     */
    static final int MAX_MASK_REGIONS = 512;

    /** Bytes of the visibility-mask UBO: 8 uints (256 bits) per region. */
    static final int VIS_UBO_BYTES = MAX_MASK_REGIONS * 32;

    /** MaskSlot sentinel: no mask uploaded, task stage treats all visible. */
    static final int NO_MASK_SLOT = 0xFFFFFFFF;

    /**
     * Bytes of the scene UBO (layout in {@link #uploadScene}): the wave-5
     * 192 plus one vec4 of distance-gated-cull thresholds. terrain.task and
     * terrain.mesh declare all 208; terrain.frag and the two occlusion
     * rasters still declare the 192-byte prefix, which stays legal because
     * the bound slice always covers the largest declared block.
     */
    static final int SCENE_BYTES = 208;

    /**
     * The "off" value {@link #uploadScene} writes for a distance-gated cull
     * whose slider sits at 0: larger than any reachable camera distance
     * squared (the render-distance ceiling keeps sections within ~2000
     * blocks, under 4e6 blocks squared), so both shader gates compare every
     * real section under it and behave exactly like the ungated code.
     * Deliberately not Float.MAX_VALUE: 3.4e38 is exactly representable
     * with headroom, so a stray doubling in a shader cannot overflow it to
     * infinity and flip a comparison.
     */
    static final float CULL_OFF_DIST2 = 3.4e38f;

    /**
     * Wave-7: default quads per TRANSLUCENT draw. One workgroup emits the
     * whole draw (threads loop — the safe shape while VK_EXT_mesh_shader's
     * inter-workgroup primitive-order guarantee stays UNVERIFIED, arch §6),
     * so the ceiling is the mesh-output caps: 64 quads = 256 vertices /
     * 128 primitives — exactly the spec-minimum maxMeshOutputVertices and
     * half the spec-minimum maxMeshOutputPrimitives. Clamped further by
     * the REAL device's caps at pipeline creation; the cost of the shape
     * is draw count (a 256-quad ocean-surface section = 4 draws instead
     * of 1 multi-WG dispatch) — measured never, chosen for correctness;
     * the multi-WG upgrade is parked with the wave-9 perf items.
     */
    static final int TRANS_QUADS_DEFAULT = 64;

    /** Translucent GateIndex sentinel: no occlusion gate — fail OPEN. */
    static final int NO_GATE = 0xFFFFFFFF;

    // ------------------------------------------------------------------
    // Wave-9 tuning knobs. Every knob DEFAULTS to the wave-4/5/7-verified
    // value and changes only via system property, so the coordinator can
    // A/B each one in isolation (one harness run per value — the workgroup
    // knobs are baked into cached pipelines at first creation, so they are
    // per-session by design; the ordering/multiWG toggles are re-read per
    // frame). None of them can change pixels by argument (each argument on
    // its resolver/consumer); the 40/41 and 60/61 A/B pairs remain the
    // empirical backstop on every sweep run.
    // ------------------------------------------------------------------

    /**
     * Quads per mesh workgroup ({@code MESHELIUM_WG_SIZE}), default
     * {@value #WORKGROUP_QUADS}. Sweepable to 64 on this hardware
     * (probe: maxMeshOutputVertices/Primitives = 256, preferred
     * invocations = 256). Values are clamped to
     * [1, {@value #MESH_WG_MAX}]: with the shaders' one-quad-per-thread
     * emission, 64 quads = 256 output vertices = the spec cap — a
     * 256-INVOCATION shape (the device's preferred size) would need
     * multi-quad-per-thread or vertex-parallel emission, a shader rewrite
     * that is NOT implemented; the sweep therefore stops at 64 and says so
     * (docs/PERFORMANCE.md). Pixel-safe: the workgroup size only re-tiles
     * which invocation emits which quad — the emitted set and every
     * per-vertex value are unchanged (same {@code meshelium_emitQuad} math,
     * counts derived from the same push constants/payload).
     */
    public static final String PROPERTY_MESH_WG = "meshelium.tune.meshWorkgroupQuads";

    /**
     * Sections per task workgroup ({@code MESHELIUM_TASK_WG_SIZE}), default
     * {@value #TASK_WG_SECTIONS}; sweep 64/128. Clamped to
     * [1, {@value #TASK_WG_MAX}]: 128 × {@code PAYLOAD_BYTES_PER_SECTION}
     * (80) = 10240 B — the largest power-of-two shape under the
     * spec-minimum {@code maxTaskPayloadSize} 16384 (256 would need
     * 20480 B and is rejected even on devices whose invocation cap allows
     * it — the payload budget, not the thread count, is the binding
     * constraint; the pipeline-creation assert checks BOTH against the
     * real device). Pixel-safe: the task stage culls per SECTION with
     * per-section inputs only — re-tiling sections across workgroups
     * changes no verdict; the prefix sum and the mesh side's
     * groupEnd search are size-parameterized by the same macro.
     */
    public static final String PROPERTY_TASK_WG = "meshelium.tune.taskWorkgroupSections";

    /**
     * Opaque front-to-back section ordering (wave-4 note 7's deferred perf
     * item), DEFAULT ON, re-read every frame: sorts the per-region CPU
     * dispatch list by camera distance before recording — nearer terrain
     * draws first, so farther fragments fail the early depth test instead
     * of shading (the classic early-z win; Nvidium sorts its region list
     * the same way).
     *
     * <p><b>Why this cannot change pixels:</b> the opaque pipelines are
     * depth-tested (GEQUAL) depth-WRITE-ON blend-OFF, so for every sample
     * the surviving fragment is the one with the maximum reversed-Z depth
     * — a property of the fragment SET, not of draw order; reordering
     * draws permutes the set's enumeration only. The single exception is
     * two fragments at EXACTLY equal depth (GEQUAL ties go to the
     * later-drawn primitive) — that requires two distinct coplanar
     * overlapping opaque quads at the same sample, a class vanilla's
     * section meshes do not produce (interior faces are culled at build;
     * neighbouring sections' boundary faces are removed, not duplicated),
     * and the same tie class the wave-6 verification addendum already
     * bounded to isolated silhouette-edge pixels when phase A/B reordered
     * draws. The toggle exists so the coordinator can A/B exactly this
     * argument (shots 40/41 with it OFF vs ON).</p>
     */
    public static final String PROPERTY_FRONT_TO_BACK = "meshelium.tune.frontToBack";

    /**
     * Translucent slices dispatched as ONE multi-workgroup draw per
     * section instead of one single-workgroup draw per ≤cap-quad slice.
     * DEFAULT ON since 1.3.0, every vendor (~1.3 ms/frame at rd 64):
     * VK_EXT_mesh_shader guarantees the ordering this relies on for
     * directly-launched workgroups — spec text and the proposal's
     * "sequential order based on their flattened workgroup index" are
     * quoted at the resolver that reads this property. FALSE forces the
     * split-draw path in the unlikely event a driver fails to honour its
     * own specification; shots 60/61 stay the parity backstop.
     */
    public static final String PROPERTY_TRANSLUCENT_MULTI_WG = "meshelium.translucentMultiWG";

    /**
     * The phase-B CPU skip (DEFAULT ON since 2026-08-17, re-read every
     * frame; {@code -Dmeshelium.occlusion.phaseBCpuSkip=false} forces the
     * old always-record path): when the occlusion inputs are provably
     * unchanged since a read-back verdict that showed zero phase-B draws,
     * the pass-4 recording is elided on the CPU. Decision logic,
     * induction argument and hazard story live on
     * {@code phaseBCpuSkipDecide}. Measured the night it was built:
     * frame p50 -11.0% at rd 64 / 1440p static (96% of frames skipped),
     * -7.9% mean at rd 32 / 1080p with occlusion forced, tail clean both
     * times, and still 96-97% engaged under tick-quantised spin
     * (docs/OCCLUSION-FILLRATE-DESIGN.md attempt 3, PERFORMANCE.md).
     */
    public static final String PROPERTY_PHASE_B_CPU_SKIP = "meshelium.occlusion.phaseBCpuSkip";

    // ------------------------------------------------------------------
    // Wave-12 CPU candidates. Property constants live on MesheliumConfig
    // (their first reader is a mixin on a backend-neutral vanilla class —
    // a constant HERE would class-load this LWJGL-importing drawer on the
    // GL path). Both default OFF; the coordinator A/Bs them on the bench
    // and only measured winners become defaults (a coordinator commit).
    //
    // skipVanillaPrep (MesheliumConfig.PROPERTY_SKIP_VANILLA_PREP): the
    //   LevelRendererMixin seam returns a minimal ChunkSectionsToRender on
    //   frames wouldOwnFrame() predicts Meshelium owns; the census argument
    //   and the one-frame failure edge live on that mixin + the wave-12
    //   FRAME-PATH notes. This class contributes the prediction
    //   (wouldOwnFrame), the skip marker, and the hole counter that keeps
    //   the prediction honest.
    //
    // cachedCull (MesheliumConfig.PROPERTY_CACHED_CULL): EXACT memoization
    //   of the occlusion path's per-frame dispatch-list build (region
    //   frustum cull + occlusion-list bytes + front-to-back sort). Reuse
    //   requires EVERY input bit-identical: snapshot epoch, camera
    //   position bits, the cull frustum's camera bits AND its private
    //   matrix's 16 raw float bits (FrustumAccessor), capacity/extLists,
    //   frontToBack. Identical inputs ⇒ the rebuild would produce
    //   identical outputs (the build is a pure function of them), so a hit
    //   records bitwise-identical commands — pixel-neutral by
    //   construction, and ANY delta is a miss.
    //
    //   HONESTY NOTE (the wave-12 brief's question, answered from this
    //   file's access patterns): the key only matches on frames whose
    //   camera state is BIT-identical — 100% of the bench's pinned
    //   spectator camera, but in real play only the stationary slice
    //   (standing still, chat/inventory open; any mouse-look or movement
    //   delta misses). The bench A/B therefore measures the UPPER BOUND
    //   of the win (the region-loop + sort share of the frame), not a
    //   typical-play win; cachedCullHit/MissFrames measured in a real
    //   play session are the evidence any default flip must cite. The
    //   brief's other variant — incremental mask building — was REJECTED
    //   after reading the paths: the per-region masks exist only on the
    //   bfsOnly FALLBACK (the default occlusion path builds no masks at
    //   all, see the wave-12 notes' elision finding), so optimizing them
    //   cannot help real play on the shipped path.
    // ------------------------------------------------------------------

    // skipVanillaPrep state (render thread; volatile probes for the bench).
    private static long prepSkippedSerial = -1;
    private static volatile long prepSkippedFrames;
    private static volatile long prepSkipHoleFrames;
    private static boolean prepSkipHoleWarned;

    // cachedCull state (render thread only; occlusion path exclusively —
    // the bfs/cpu paths invalidate on entry because they share the scratch
    // arrays this cache reuses).
    private static boolean ccValid;
    private static long ccEpoch = Long.MIN_VALUE;
    private static long ccPosX, ccPosY, ccPosZ;          // raw double bits
    private static long ccFrusX, ccFrusY, ccFrusZ;       // raw double bits
    private static final float[] ccMatrix = new float[16];
    private static final float[] ccMatrixScratch = new float[16];
    private static int ccDispatched;
    private static long ccSig;
    private static long ccOverflowThisFrame;
    private static int ccRegionCount;
    private static int ccCapacity;
    private static boolean ccExtLists;
    private static boolean ccFrontToBack;
    /** Ext-mode occlusion-list shadow (ring slots rotate; the cache's bytes
     *  live here and are memcpy'd into each frame's slot). Null standard. */
    private static ByteBuffer ccShadow;
    private static volatile long cachedCullHits;
    private static volatile long cachedCullMisses;

    /** Mesh-knob ceiling: 64 quads = the 256-output-vertex spec cap / 4. */
    static final int MESH_WG_MAX = 64;

    /**
     * Per-vertex output locations both terrain mesh shaders emit: locations
     * 0 to 5 in {@code terrain.mesh} plus {@code gl_Position}, which the
     * output-size formula counts like any other vec4. MUST move with the
     * shader's out declarations - it exists because the spec sizes a mesh
     * workgroup's output as verts x locations x 16 B against
     * {@code maxMeshOutputMemorySize}, whose guaranteed minimum is 32768 B
     * and which the dev RDNA4 card reports at EXACTLY that floor. The
     * greedy-merge varyings briefly grew this to 9, putting the 64-quad
     * translucent workgroup at 36864 B - over the limit on the very card
     * that ran every test, undefined behavior the driver happened to
     * tolerate. The varyings are now packed back to 7 (sprite rect as one
     * vec4, tile counts re-derived from materialBits per fragment), and
     * the budget is queried, clamped and asserted so the NEXT growth is a
     * loud failure instead of a silent one.
     */
    static final int MESH_OUTPUT_LOCATIONS = 7;

    /** verts/quad x locations x 16 B: what one quad costs the output budget. */
    static final int MESH_OUTPUT_BYTES_PER_QUAD = 4 * MESH_OUTPUT_LOCATIONS * 16;

    /**
     * Largest quads-per-workgroup the device's mesh-output memory admits,
     * or {@code Integer.MAX_VALUE} before caps exist. At the spec floor
     * this is 56; the dev RDNA4 admits far more, so the clamp is a no-op
     * everywhere the mod has ever actually run.
     */
    private static int maxQuadsByOutputMemory() {
        MesheliumVulkanState.MeshShaderCaps caps = MesheliumVulkanState.caps();
        if (caps == null || caps.maxMeshOutputMemorySize() <= 0) {
            return Integer.MAX_VALUE; // no information is never no capacity
        }
        return Math.max(1, caps.maxMeshOutputMemorySize() / MESH_OUTPUT_BYTES_PER_QUAD);
    }

    /** Task-knob ceiling: 128 × 80 B payload = 10240 ≤ spec-min 16384. */
    static final int TASK_WG_MAX = 128;

    // Resolved once per session (render thread; first pipeline creation
    // and every dispatch computation read the same resolved value).
    private static int resolvedMeshWgQuads = -1;
    private static int resolvedTaskWgSections = -1;

    /** Effective {@code MESHELIUM_WG_SIZE} (see {@link #PROPERTY_MESH_WG}). */
    public static int meshWorkgroupQuads() {
        int v = resolvedMeshWgQuads;
        if (v < 0) {
            v = resolveKnob(PROPERTY_MESH_WG, WORKGROUP_QUADS, MESH_WG_MAX);
            // Output-memory clamp (see MESH_OUTPUT_LOCATIONS): the default 32
            // fits every conformant device, but the knob reaches 64, which a
            // spec-floor device cannot hold at 9 locations per vertex. Only
            // CACHE once caps exist, so an early call cannot freeze an
            // unclamped value that a later pipeline build would disagree with.
            int memoryCap = maxQuadsByOutputMemory();
            int clamped = Math.min(v, memoryCap);
            if (clamped != v) {
                MesheliumClient.LOGGER.warn(
                        "Meshelium mesh workgroup clamped {} -> {} quads: this device's "
                                + "maxMeshOutputMemorySize admits {} at {} output locations",
                        v, clamped, memoryCap, MESH_OUTPUT_LOCATIONS);
            }
            if (MesheliumVulkanState.caps() == null) {
                return clamped;
            }
            resolvedMeshWgQuads = clamped;
            v = clamped;
        }
        return v;
    }

    /** Effective {@code MESHELIUM_TASK_WG_SIZE} (see {@link #PROPERTY_TASK_WG}). */
    public static int taskWorkgroupSections() {
        int v = resolvedTaskWgSections;
        if (v < 0) {
            v = resolveKnob(PROPERTY_TASK_WG, TASK_WG_SECTIONS, TASK_WG_MAX);
            resolvedTaskWgSections = v;
        }
        return v;
    }

    /**
     * Parse a workgroup knob: absent/garbage → the verified default;
     * out-of-range values CLAMP with a WARN (a bad sweep value must never
     * kill the renderer — the pipeline-creation caps assert remains the
     * hard failure for genuinely impossible shapes on the real device).
     */
    private static int resolveKnob(String property, int def, int max) {
        String raw = System.getProperty(property);
        if (raw == null) {
            return def;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            MesheliumClient.LOGGER.warn("Meshelium: unparseable {}='{}' — using the default {}",
                    property, raw, def);
            return def;
        }
        int clamped = Math.max(1, Math.min(max, parsed));
        if (clamped != parsed) {
            MesheliumClient.LOGGER.warn(
                    "Meshelium: {}={} outside [1, {}] — clamped to {} (the ceiling argument is on "
                            + "the property's javadoc; 256-invocation mesh shapes need "
                            + "multi-quad-per-thread emission, which is not implemented)",
                    property, parsed, max, clamped);
        }
        if (clamped != def) {
            MesheliumClient.LOGGER.info("Meshelium wave-9 knob: {}={} (default {})",
                    property, clamped, def);
        }
        return clamped;
    }

    /** {@code meshelium.tune.frontToBack} ?? true — re-read every frame. */
    private static boolean frontToBackEnabled() {
        String p = System.getProperty(PROPERTY_FRONT_TO_BACK);
        return p == null || Boolean.parseBoolean(p);
    }

    // Front-to-back sort scratch (render thread only, grown on demand).
    private static long[] sortKeys = new long[256];
    private static int[] sortMetaScratch = new int[3 * 256];
    private static float[] sortOriginScratch = new float[3 * 256];

    /**
     * Sort the frame's dispatch list (regionMeta/regionOrigins triplets)
     * by ascending camera distance to the region CENTER — the
     * {@link #PROPERTY_FRONT_TO_BACK} early-z ordering. Every per-region
     * datum (mask slot, occlusion list slot, task-group count) travels
     * inside the triplets, so consumers see a permutation, nothing else;
     * the visibility/occlusion upload buffers are indexed by the SLOT
     * values riding in the triplets, not by list position — order-proof
     * by construction. Distance keys are squared floats; non-negative
     * float bits sort correctly as integers.
     */
    private static void sortRegionsFrontToBack(int dispatched) {
        if (dispatched <= 1 || !frontToBackEnabled()) {
            return;
        }
        if (sortKeys.length < dispatched) {
            int n = Math.max(dispatched, sortKeys.length * 2);
            sortKeys = new long[n];
            sortMetaScratch = new int[3 * n];
            sortOriginScratch = new float[3 * n];
        }
        for (int r = 0; r < dispatched; r++) {
            float dx = regionOrigins[r * 3] + 64.0f;      // region center is
            float dy = regionOrigins[r * 3 + 1] + 32.0f;  // origin + half of
            float dz = regionOrigins[r * 3 + 2] + 64.0f;  // 128 × 64 × 128
            float d2 = dx * dx + dy * dy + dz * dz;
            sortKeys[r] = ((long) Float.floatToIntBits(d2) << 32) | r;
        }
        Arrays.sort(sortKeys, 0, dispatched);
        for (int i = 0; i < dispatched; i++) {
            int src = (int) sortKeys[i];
            sortMetaScratch[i * 3] = regionMeta[src * 3];
            sortMetaScratch[i * 3 + 1] = regionMeta[src * 3 + 1];
            sortMetaScratch[i * 3 + 2] = regionMeta[src * 3 + 2];
            sortOriginScratch[i * 3] = regionOrigins[src * 3];
            sortOriginScratch[i * 3 + 1] = regionOrigins[src * 3 + 1];
            sortOriginScratch[i * 3 + 2] = regionOrigins[src * 3 + 2];
        }
        System.arraycopy(sortMetaScratch, 0, regionMeta, 0, dispatched * 3);
        System.arraycopy(sortOriginScratch, 0, regionOrigins, 0, dispatched * 3);
    }

    // ------------------------------------------------------------------
    // Wave-12 cachedCull internals (occlusion path only)
    // ------------------------------------------------------------------

    /** Lazily (re)sized ext-mode shadow for the occlusion-list bytes. */
    private static ByteBuffer ccShadow(long bytes) {
        ByteBuffer b = ccShadow;
        if (b == null || b.capacity() < bytes) {
            b = ByteBuffer.allocateDirect((int) bytes).order(ByteOrder.LITTLE_ENDIAN);
            ccShadow = b;
        }
        return b;
    }

    /**
     * TRUE iff every input of the dispatch-list build is BIT-identical to
     * the cached miss frame's: snapshot epoch (covers regionData content),
     * camera position raw bits, the cull frustum's camera raw bits AND its
     * matrix's 16 raw float bits ({@link FrustumAccessor} — isVisible is a
     * pure function of exactly {matrix, camX/Y/Z} and the tested box),
     * capacity/extLists shape and the frontToBack toggle. Raw-bits compares
     * make NaN a miss (safe direction) and rounding-scale drift a miss —
     * the cache can only skip recomputing an IDENTICAL build, never serve
     * a stale one.
     */
    private static boolean cachedCullFresh(CameraRenderState cam, Frustum frustum,
            boolean extLists, int occCapacity, int regionCount) {
        if (!ccValid || ccEpoch != cachedEpoch || ccExtLists != extLists
                || ccCapacity != occCapacity || ccRegionCount != regionCount
                || ccFrontToBack != frontToBackEnabled()) {
            return false;
        }
        if (Double.doubleToRawLongBits(cam.pos.x) != ccPosX
                || Double.doubleToRawLongBits(cam.pos.y) != ccPosY
                || Double.doubleToRawLongBits(cam.pos.z) != ccPosZ
                || Double.doubleToRawLongBits(frustum.getCamX()) != ccFrusX
                || Double.doubleToRawLongBits(frustum.getCamY()) != ccFrusY
                || Double.doubleToRawLongBits(frustum.getCamZ()) != ccFrusZ) {
            return false;
        }
        ccReadMatrix(((FrustumAccessor) frustum).meshelium$matrix(), ccMatrixScratch);
        for (int i = 0; i < 16; i++) {
            if (Float.floatToRawIntBits(ccMatrixScratch[i]) != Float.floatToRawIntBits(ccMatrix[i])) {
                return false;
            }
        }
        return true;
    }

    /** Pin the key of the build that just ran (miss path). */
    private static void ccStoreKey(CameraRenderState cam, Frustum frustum,
            boolean extLists, int occCapacity, int regionCount) {
        ccEpoch = cachedEpoch;
        ccPosX = Double.doubleToRawLongBits(cam.pos.x);
        ccPosY = Double.doubleToRawLongBits(cam.pos.y);
        ccPosZ = Double.doubleToRawLongBits(cam.pos.z);
        ccFrusX = Double.doubleToRawLongBits(frustum.getCamX());
        ccFrusY = Double.doubleToRawLongBits(frustum.getCamY());
        ccFrusZ = Double.doubleToRawLongBits(frustum.getCamZ());
        ccReadMatrix(((FrustumAccessor) frustum).meshelium$matrix(), ccMatrix);
        ccExtLists = extLists;
        ccCapacity = occCapacity;
        ccRegionCount = regionCount;
        ccFrontToBack = frontToBackEnabled();
        // Valid immediately: the only consumer-visible state still to be
        // produced this frame is the in-place front-to-back sort, which
        // runs unconditionally before any use and leaves the arrays in
        // exactly the state a later hit reuses.
        ccValid = true;
    }

    /** Copy the 16 components (fixed order; comparison-only). */
    private static void ccReadMatrix(Matrix4f m, float[] out) {
        out[0] = m.m00();
        out[1] = m.m01();
        out[2] = m.m02();
        out[3] = m.m03();
        out[4] = m.m10();
        out[5] = m.m11();
        out[6] = m.m12();
        out[7] = m.m13();
        out[8] = m.m20();
        out[9] = m.m21();
        out[10] = m.m22();
        out[11] = m.m23();
        out[12] = m.m30();
        out[13] = m.m31();
        out[14] = m.m32();
        out[15] = m.m33();
    }

    private static volatile String lastError;
    private static volatile int framesDrawn;
    private static volatile int lastDrawnSections;
    private static volatile long totalDrawnSections;
    private static volatile long cancelledGroups;
    // Wave-5 counters.
    private static volatile int regionsDispatched;
    private static volatile int sectionsVisibleIn;
    private static volatile long dispatchSignature;
    private static volatile long taskCullFrames;
    private static volatile long cpuCullFrames;
    private static volatile long maskOverflowRegions;

    // Wave-11 counters (volatile; written on the render thread).
    /**
     * BFS-mask path only: retained sections whose mask bit THIS frame came
     * from the retained mask and not from vanilla's visibleSections — the
     * exact count of drawn-beyond-what-live-residency-could-give, the
     * harness's deterministic retention probe (the occlusion path draws
     * retained sections through the stamp machinery without attribution,
     * so the harness flips bfsOnly for this assert).
     */
    private static volatile int lastRetainedMaskSections;
    /** Cumulative {@link #lastRetainedMaskSections} over bfs frames. */
    private static volatile long retainedMaskSectionsTotal;
    /** Retained translucent sections drawn by the wave-11 pre-pass, last frame. */
    private static volatile int lastRetainedTranslucentSections;

    // Wave-7 counters/probes (volatile; written on the render thread).
    private static volatile long translucentFrames;
    private static volatile int lastTranslucentSections;
    private static volatile int lastTranslucentDraws;
    private static volatile long cancelledTranslucentGroups;
    /** Wave-16: owned translucent frames that drew into the SEPARATE target. */
    private static volatile long translucentSeparateTargetFrames;
    private static volatile long translucentGatedSections;
    // Wave-9: frames whose translucent pass used the multi-WG experiment.
    private static volatile long translucentMultiWGFrames;
    private static boolean multiWGLogged; // render thread only

    // Wave-8 counters/probes (volatile; written on the render thread).
    /** True while the coverage guard holds the drawer passive (see below). */
    private static volatile boolean coveragePassive;
    /** Cumulative coverage-guard trips (== WARN lines; one per bad world). */
    private static volatile long coverageTrips;
    /** True once a GpuDeviceLossException latched the drawer. */
    private static volatile boolean deviceLost;
    private static boolean coverageWarned; // render thread only

    // Wave-6 counters/probes (volatile; written on the render thread).
    private static volatile String occlusionError;
    private static volatile long occlusionFrames;
    private static volatile long bfsOnlyFrames;
    private static volatile long statsFrames;
    private static volatile int gpuSectionsDrawn;
    private static volatile int gpuPhaseASections;
    private static volatile int gpuPhaseBSections;
    private static volatile long lastPhaseBStatsFrame = -1;
    private static volatile long lastDispatchChangeStatsFrame = -1;
    private static volatile long occOverflowRegions;
    private static volatile long lastReadStatsFrame = -1;

    // ---- phase-B CPU skip state (render thread; volatiles are test probes) ----
    /**
     * Stats frame of the most recent occlusion-input change seen by the
     * skip's own trackers (camera/frustum/scene-matrix/extent key, epoch,
     * commit backlog, occlusion-frame gap). {@code lastDispatchChangeStatsFrame}
     * is folded in at decision time, so C = max of the two; armed iff
     * {@code lastReadStatsFrame >= C + 2 && lastPhaseBStatsFrame <= C}.
     */
    private static volatile long pbSkipInputChangeStatsFrame;
    private static volatile long phaseBCpuSkipFrames;
    private static boolean pbSkipKeyValid;
    private static long pbSkipPosX, pbSkipPosY, pbSkipPosZ;
    private static long pbSkipFrusX, pbSkipFrusY, pbSkipFrusZ;
    private static final float[] pbSkipFrusMatrix = new float[16];
    private static final float[] pbSkipViewRot = new float[16];
    private static final float[] pbSkipProj = new float[16];
    private static final float[] pbSkipScratch = new float[16];
    private static int pbSkipExtentW = -1, pbSkipExtentH = -1;
    private static long pbSkipEpoch = Long.MIN_VALUE;
    private static long pbSkipLastOccSerial = Long.MIN_VALUE;

    /**
     * Per-stats-frame history rings for the camera-cut assertion (client
     * thread writes AND reads — gametest predicates run on the client
     * thread): [statsFrame % N] = {frame id, phase-B count} / the frames
     * at which the dispatched-region signature changed in occlusion mode.
     */
    private static final int HISTORY = 128;
    private static final long[] phaseBFrames = new long[HISTORY];
    private static final int[] phaseBCounts = new int[HISTORY];
    private static final long[] changeFrames = new long[64];
    private static int changeCursor;
    static {
        Arrays.fill(phaseBFrames, -1);
        Arrays.fill(changeFrames, -1);
    }

    // Render thread only from here down.
    private static boolean broken;
    private static TerrainDrawPipeline taskPipeline;
    /** Wave-10: the MESHELIUM_LISTS_SSBO=1 task variant (extended worlds). */
    private static TerrainDrawPipeline taskPipelineExt;
    private static TerrainDrawPipeline cpuPipeline;
    private static TerrainDrawPipeline translucentPipeline;
    /**
     * Wave-10 per-world SSBO rings for the per-frame mask/occlusion lists
     * (extended worlds only; standard worlds keep the wave-5/6 transient
     * UBO paths byte-identical). Created lazily on the first extended
     * frame, destroyed with the dispatcher.
     */
    private static MesheliumFrameLists frameLists;
    private static boolean frameListsFailed; // once-only creation failure latch
    private static CameraRenderState camera;
    private static long cachedEpoch = Long.MIN_VALUE;
    private static TerrainResidency.DrawSnapshot snapshot;
    // Wave-7 frame coupling: translucent owns a frame ONLY when opaque
    // owned the same frame (mixed vanilla-opaque + meshelium-translucent
    // frames would blend against a depth Meshelium didn't write).
    private static long frameSerial;
    private static long opaqueOwnedSerial = -1;
    // Wave-7 occlusion carry (opaque pass → translucent pass, same frame):
    // gate only when the occlusion rasters actually ran this frame.
    private static long occGateSerial = -1;
    private static long occCurStampsHandle;
    private static int occGateStamp32;
    /** Region ids whose boxes rastered this frame (list slot >= 0). */
    private static final it.unimi.dsi.fastutil.ints.IntOpenHashSet occRasteredRegions =
            new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
    /** Section coords → snapshot slot, translucent-prefix slot owners only. */
    private static final Long2IntOpenHashMap translucentSlotByPos = new Long2IntOpenHashMap();
    static {
        translucentSlotByPos.defaultReturnValue(-1);
    }
    /** Per-frame translucent draw scratch: {ox,oy,oz} / {firstQuad,count,gate}. */
    private static float[] transOrigins = new float[3 * 512];
    private static int[] transMeta = new int[3 * 512];
    // Wave-11 retained-translucent pre-pass scratch (render thread only).
    /** Distance-sorted keys {d² bits << 32 | snapshot slot}. */
    private static long[] retTransKeys = new long[64];
    /** Snapshot slots already drawn by the pre-pass (visible loop skips). */
    private static boolean[] transDrawnMark = new boolean[512];
    /** The marked indices, for O(marked) clearing after the frame. */
    private static final it.unimi.dsi.fastutil.ints.IntArrayList transDrawnList =
            new it.unimi.dsi.fastutil.ints.IntArrayList();
    // Wave-6 occlusion state (render thread only).
    private static TerrainOcclusion occlusion;
    private static boolean occlusionBroken;
    /**
     * Monotonic occlusion frame stamp. Starts above the zero-fill value
     * and NEVER resets (not even across worlds — monotonicity is what
     * makes stale stamps self-invalidating; see TerrainOcclusion).
     */
    private static long occFrameStamp = 16;
    private static long prevOcclusionSignature;
    /** The 16 KiB occlusion region-list staging area, reused every frame. */
    private static final ByteBuffer occUpload =
            ByteBuffer.allocateDirect(TerrainOcclusion.OCC_LIST_BYTES).order(ByteOrder.LITTLE_ENDIAN);

    // Per-frame scratch, render thread only, grown on demand.
    private static float[] runOrigins = new float[3 * 256];
    private static int[] runQuads = new int[2 * 256];
    /** Per dispatched region: {regionId, maskSlot, taskGroups}. */
    private static int[] regionMeta = new int[3 * 256];
    private static float[] regionOrigins = new float[3 * 256];
    /** Live-region key → snapshot index; rebuilt on snapshot epoch change. */
    private static final Long2IntOpenHashMap regionSlotByKey = new Long2IntOpenHashMap();
    static {
        regionSlotByKey.defaultReturnValue(-1);
    }
    /** 8 mask words per live region (snapshot order), rebuilt every frame. */
    private static int[] regionMasks = new int[8 * 256];
    /** The 16 KiB visibility-mask UBO staging area, reused every frame. */
    private static final ByteBuffer visUpload =
            ByteBuffer.allocateDirect(VIS_UBO_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    /** Scratch for ProjMat*ModelViewMat (plane extraction). */
    private static final Matrix4f mvpScratch = new Matrix4f();

    // Perf breadcrumb (CPU-side draw-path time; GPU timing is wave 9's).
    private static long perfNanosAccum;
    private static long perfNanosMax;
    private static int perfFrames;
    private static long perfTaskGroupsAccum;
    private static long lastPerfLogNanos;

    private TerrainDrawer() {}

    // ------------------------------------------------------------------
    // Probes (harness)
    // ------------------------------------------------------------------

    /** Null = healthy; non-null = the once-only ERROR's message. */
    public static String lastError() {
        return lastError;
    }

    /** Frames in which at least one terrain draw was recorded. */
    public static int framesDrawn() {
        return framesDrawn;
    }

    /**
     * Task path: vanilla-visible sections inside the dispatched regions
     * (mask popcount; overflow regions count all their resident sections).
     * cpuCull path: wave-4 meaning — sections that passed the CPU frustum.
     */
    public static int lastDrawnSections() {
        return lastDrawnSections;
    }

    /** Cumulative {@link #lastDrawnSections()} across all drawn frames. */
    public static long totalDrawnSections() {
        return totalDrawnSections;
    }

    /** Times the OPAQUE renderGroup was cancelled in favour of our pass. */
    public static long cancelledGroups() {
        return cancelledGroups;
    }

    /** Regions dispatched (post CPU frustum + mask skip) last drawn frame. */
    public static int regionsDispatched() {
        return regionsDispatched;
    }

    /** Mask-popcount total of the last drawn frame (the feed statistic). */
    public static int sectionsVisibleIn() {
        return sectionsVisibleIn;
    }

    /**
     * Order-independent signature of the last frame's dispatched region-id
     * set — the harness asserts it CHANGES when the camera turns 180°.
     */
    public static long dispatchSignature() {
        return dispatchSignature;
    }

    /** Frames drawn through the wave-5 task-culling path. */
    public static long taskCullFrames() {
        return taskCullFrames;
    }

    /** Frames drawn through the wave-4 cpuCull escape hatch. */
    public static long cpuCullFrames() {
        return cpuCullFrames;
    }

    /** Cumulative regions dispatched with the no-mask sentinel (overflow). */
    public static long maskOverflowRegions() {
        return maskOverflowRegions;
    }

    // ------------------------------------------------------------------
    // Wave-11 probes
    // ------------------------------------------------------------------

    /**
     * BFS-mask path, last drawn frame: sections visible ONLY because they
     * are retained (their bit came from the retained mask, not from
     * vanilla's visibleSections). This is the deterministic "the drawn set
     * exceeds what live residency alone could give" probe — every counted
     * section is one vanilla no longer has. 0 on occlusion frames (the
     * stamp path draws retained sections without attribution).
     */
    public static int lastRetainedMaskSections() {
        return lastRetainedMaskSections;
    }

    /** Cumulative {@link #lastRetainedMaskSections()} across bfs frames. */
    public static long retainedMaskSectionsTotal() {
        return retainedMaskSectionsTotal;
    }

    /** Retained translucent sections drawn by the pre-pass, last owned frame. */
    public static int lastRetainedTranslucentSections() {
        return lastRetainedTranslucentSections;
    }

    // ------------------------------------------------------------------
    // Wave-7 probes
    // ------------------------------------------------------------------

    /** Frames in which Meshelium owned the TRANSLUCENT group. */
    public static long translucentFrames() {
        return translucentFrames;
    }

    /**
     * Owned translucent frames whose output target was NOT the main one,
     * i.e. frames that really ran the improved-transparency path. The
     * harness cannot determine this from outside: the frame graph's
     * LevelTargetBundle is cleared at end of frame, so
     * LevelRenderer.translucentTarget() reads null between frames whatever
     * the setting is.
     */
    public static long translucentSeparateTargetFrames() {
        return translucentSeparateTargetFrames;
    }

    /** Translucent sections recorded last owned translucent frame. */
    public static int lastTranslucentSections() {
        return lastTranslucentSections;
    }

    /** Translucent draws (≤ transQuadCapacity-quad slices) last frame. */
    public static int lastTranslucentDraws() {
        return lastTranslucentDraws;
    }

    /** Times the TRANSLUCENT renderGroup was cancelled for our pass. */
    public static long cancelledTranslucentGroups() {
        return cancelledTranslucentGroups;
    }

    /** Cumulative sections recorded WITH the occlusion stamp gate armed. */
    public static long translucentGatedSections() {
        return translucentGatedSections;
    }

    /**
     * Wave-9: frames whose translucent pass ran the
     * {@link #PROPERTY_TRANSLUCENT_MULTI_WG} experiment (multi-workgroup
     * slices) — the harness proves the experiment was actually live
     * during its parity shots.
     */
    public static long translucentMultiWGFrames() {
        return translucentMultiWGFrames;
    }

    // ------------------------------------------------------------------
    // Wave-6 probes
    // ------------------------------------------------------------------

    /**
     * Null = healthy. Occlusion failures latch HERE (and fall back to the
     * BFS feed), never into {@link #lastError()} — the drawer keeps
     * drawing; the harness asserts both are null.
     */
    public static String occlusionError() {
        return occlusionError;
    }

    /** Frames drawn through the wave-6 two-phase occlusion path. */
    public static long occlusionFrames() {
        return occlusionFrames;
    }

    /** Task frames drawn through the wave-5 BFS-mask fallback. */
    public static long bfsOnlyFrames() {
        return bfsOnlyFrames;
    }

    /** Frames that recorded a GPU stats copy (the readback timeline). */
    public static long statsFrames() {
        return statsFrames;
    }

    /**
     * GPU-counted sections drawn (task-stage survivors, all VisModes),
     * {@link TerrainOcclusion#READBACK_LAG} stats-frames stale. The
     * hidden-cave assertion's metric — comparable across occlusion and
     * bfsOnly because BOTH count post-all-gates survivors on the GPU.
     * 0 until the first readback lands (or when stats are unavailable).
     */
    public static int gpuSectionsDrawn() {
        return gpuSectionsDrawn;
    }

    /** GPU-counted phase-A sections of the last read-back stats frame. */
    public static int gpuPhaseASections() {
        return gpuPhaseASections;
    }

    /** GPU-counted phase-B sections of the last read-back stats frame. */
    public static int gpuPhaseBSections() {
        return gpuPhaseBSections;
    }

    /**
     * Stats-frame index of the most recent read-back frame whose phase B
     * drew anything (-1 = never) — the camera-cut latency probe.
     */
    public static long lastPhaseBStatsFrame() {
        return lastPhaseBStatsFrame;
    }

    /**
     * Stats-frame index at which the dispatched-region signature last
     * changed while in occlusion mode (-1 = never) — "when did the camera
     * cut land", for the phase-B-within-2-frames assertion.
     */
    public static long lastDispatchChangeStatsFrame() {
        return lastDispatchChangeStatsFrame;
    }

    /** Cumulative regions dispatched past the occlusion list cap (fail-open). */
    public static long occOverflowRegions() {
        return occOverflowRegions;
    }

    /** Highest stats frame whose readback has been folded in (-1 = none). */
    public static long lastReadStatsFrame() {
        return lastReadStatsFrame;
    }

    /** Frames whose phase-B recording the CPU skip elided (cumulative). */
    public static long phaseBCpuSkipFrames() {
        return phaseBCpuSkipFrames;
    }

    /**
     * Stats frame of the most recent phase-B-skip input change — the C
     * of the skip predicate, dispatch-signature tracker folded in. Test
     * probe (client thread).
     */
    public static long phaseBSkipInputChangeStatsFrame() {
        return Math.max(pbSkipInputChangeStatsFrame, lastDispatchChangeStatsFrame);
    }

    /**
     * Phase-B section count of stats frame {@code f}, or -1 when that
     * frame's readback hasn't landed / has been overwritten (ring of
     * {@value #HISTORY}). Client thread.
     */
    public static int gpuPhaseBAt(long f) {
        if (f < 0) {
            return -1;
        }
        int i = (int) (f % HISTORY);
        return phaseBFrames[i] == f ? phaseBCounts[i] : -1;
    }

    /**
     * Earliest occlusion-mode dispatch-signature change at or after stats
     * frame {@code f} still in the ring, or -1. Client thread.
     */
    public static long firstDispatchChangeAtOrAfter(long f) {
        long best = -1;
        for (long c : changeFrames) {
            if (c >= f && (best < 0 || c < best)) {
                best = c;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Wave-8 probes
    // ------------------------------------------------------------------

    /**
     * True while the coverage guard holds Meshelium passive: at least one
     * section was dropped in the CURRENT world (arena-full / oversize /
     * region-budget / encode failure), so a Meshelium-owned frame would be
     * missing terrain vanilla has. Passive = the kill switch stops
     * cancelling, vanilla draws everything, no hole is possible. Clears
     * only via a world load whose counters stay clean.
     */
    public static boolean coveragePassive() {
        return coveragePassive;
    }

    /** Cumulative guard trips (== WARN lines fired; one per tripped world). */
    public static long coverageTrips() {
        return coverageTrips;
    }

    /** True once a {@code GpuDeviceLossException} latched the drawer. */
    public static boolean deviceLost() {
        return deviceLost;
    }

    /**
     * Latch the session error and tell the player once.
     *
     * <p>Four throw sites shared one assignment; the chat line belongs with
     * the LATCH, not with the throw, so a storm of failures is one message.
     * Until now this state was invisible outside the log and a line on the
     * options screen that only exists while that screen is open, which is
     * the least likely place to be looking when the terrain stops.</p>
     */
    private static void latchError(Throwable t) {
        boolean first = lastError == null;
        lastError = t.toString();
        if (first) {
            com.deds.meshelium.MesheliumNotify.chat("meshelium.chat.error.renderer");
        }
    }

    // NOTE: there is deliberately no enabled() here any more.
    //
    // One used to exist, documented as "the live gate the mixins consult",
    // and it carried the whole ownership rule: return true even when the
    // config says off, for as long as the upload seam had suppressed vanilla
    // and vanilla had not been rebuilt, so Meshelium kept the frame instead of
    // handing over an empty world. It also called demote() on the way past.
    //
    // NOTHING CALLED IT. Not one caller in the entire repository. Both real
    // consumers of the master switch - the draw-cancel hook at
    // ChunkSectionsToRenderMixin and the frame-state capture at
    // LevelRendererMixin - read MesheliumConfig.terrainRenderingEnabled()
    // straight out of the config and never referenced this class. So the
    // ownership rule never executed once, the seam was never demoted by the
    // kill switch, and turning Meshelium off with suppression on produced a
    // permanently see-through world.
    //
    // It is not restored here, because the owner chose the other resolution:
    // the swap is SEQUENCED (dump one, then load the other) rather than
    // overlapped, so that the two copies are never resident at the same time
    // and an 8 GB card is not asked to hold both. Meshelium stopping the
    // instant the switch flips is therefore correct. What was missing is the
    // demotion, and that now runs from the client tick in
    // MesheliumExtendedRd.driveVanillaUploadSeamRecovery, which executes
    // unconditionally and also catches the harness flipping the property.

    // ------------------------------------------------------------------
    // Wave-12 probes + the skipVanillaPrep prediction
    // ------------------------------------------------------------------

    /** Frames whose vanilla prepareChunkRenders was skipped (knob ON). */
    public static long prepSkippedFrames() {
        return prepSkippedFrames;
    }

    /**
     * Frames where the skip prediction was WRONG: prep was skipped but the
     * drawer did not own a group, so vanilla drew that one frame from the
     * empty record (a missing-terrain frame). Deterministically 0 —
     * {@link #wouldOwnFrame} mirrors every non-throwing refusal path — so
     * any nonzero value means a drawer THROW latched mid-frame (the
     * documented one-frame edge). The bench protocol requires 0 for a
     * valid skipVanillaPrep leg.
     */
    public static long prepSkipHoleFrames() {
        return prepSkipHoleFrames;
    }

    /** cachedCull frames served from the memo (occlusion path only). */
    public static long cachedCullHitFrames() {
        return cachedCullHits;
    }

    /** cachedCull frames that rebuilt (any input bit changed). */
    public static long cachedCullMissFrames() {
        return cachedCullMisses;
    }

    /**
     * Wave-12: the skipVanillaPrep prediction — TRUE iff, with the gate and
     * config already checked by the caller (the LevelRendererMixin seam),
     * every DETERMINISTIC refusal path of {@link #drawOpaque} /
     * {@link #drawTranslucent} is clear this frame: no error/device-loss
     * latch, coverage guard clean ({@code dropsThisWorld() == 0} — read
     * WITHOUT the guard's WARN side effects; drawOpaque remains the one
     * place that trips it), camera state captured and usable, and the
     * OPAQUE target + lightmap views present (the TRANSLUCENT target
     * null-falls-back to main per frame-path Q2.1, so it cannot
     * independently refuse). Everything the drawer could still do
     * differently after this returns true is a THROW — which latches and
     * costs exactly the one documented frame, counted by
     * {@link #prepSkipHoleFrames}. Mid-frame flips of the property/config
     * cannot split this prediction from the kill switch: both run on the
     * render thread inside one {@code LevelRenderer.render} call, and
     * harness/options toggles land between frames on that same thread.
     */
    public static boolean wouldOwnFrame() {
        if (broken || deviceLost) {
            return false;
        }
        if (TerrainResidency.dropsThisWorld() != 0) {
            return false; // coverage guard would block (prediction only)
        }
        CameraRenderState cam = camera;
        if (cam == null || !cam.initialized || cam.cullFrustum == null) {
            return false;
        }
        RenderTarget target = ChunkSectionLayerGroup.OPAQUE.outputTarget();
        if (target == null || target.getColorTextureView() == null
                || target.getDepthTextureView() == null) {
            return false;
        }
        return Minecraft.getInstance().gameRenderer.lightmap() != null;
    }

    /** The mixin skipped vanilla prep for THIS frame (prediction was true). */
    public static void notePrepSkipped() {
        prepSkippedSerial = frameSerial;
        prepSkippedFrames++;
    }

    private static void countPrepSkipHole() {
        prepSkipHoleFrames++;
        if (!prepSkipHoleWarned) {
            prepSkipHoleWarned = true;
            MesheliumClient.LOGGER.warn(
                    "Meshelium skipVanillaPrep predicted a Meshelium-owned frame but the drawer did "
                            + "not own it — vanilla drew this ONE frame from the skipped (empty) "
                            + "prep. This is the documented first-throw edge; counted in "
                            + "prepSkipHoleFrames (bench legs require it to stay 0; once-only report)");
        }
    }

    /** Fold the skip-prediction outcome into the hole counter (opaque side). */
    private static boolean notePrepOutcome(boolean owned) {
        if (!owned && prepSkippedSerial == frameSerial) {
            countPrepSkipHole();
        }
        return owned;
    }

    /**
     * Wave-8 deliverable 3 — the coverage guard (the VRAM-pressure answer).
     * A dropped section is one vanilla HAS but Meshelium's arena does not;
     * once Meshelium owns the draw that difference is a HOLE in the world.
     * Rule: any drop in the current world ⇒ the kill switch stops
     * cancelling for the REST of that world (vanilla draws everything,
     * Meshelium's residency keeps mirroring so the next world can be judged
     * fresh), one WARN with the counts, once per tripped world.
     *
     * <p><b>No mid-frame flip-flop:</b> this is read exactly once per
     * frame, at {@code drawOpaque} HEAD; the translucent pass never
     * re-reads it — it keys on {@code opaqueOwnedSerial}, so a frame is
     * always wholly Meshelium's or wholly vanilla's. Drops land either in
     * the pump (render thread, BEFORE the frame graph runs renderGroup) or
     * on build workers ({@code droppedEncoding}); a worker-side drop
     * between the two groups of frame N flips only frame N+1's decision,
     * and the dropped section was compiled mid-frame-N, so vanilla had not
     * promoted its mesh for frame N either — no hole in frame N. Within a
     * world the input is monotonic ({@link TerrainResidency#dropsThisWorld}),
     * so passive can never flap back to active mid-world.</p>
     */
    private static boolean coverageGuardBlocks() {
        if (TerrainResidency.dropsThisWorld() == 0) {
            coveragePassive = false;
            return false;
        }
        coveragePassive = true;
        reportCoverageTripOnce();
        // THE OWNERSHIP RULE. The guard exists to avoid showing a holey
        // world, and it does that by handing the frame to vanilla - which
        // is only an improvement while vanilla's copy is WHOLE. Once the
        // upload seam has suppressed anything this world, vanilla's picture
        // is not holey, it is EMPTY, and handing over turns a bad frame
        // into a black one. So while a rebuild is putting vanilla back,
        // Meshelium keeps drawing whatever it has.
        if (com.deds.meshelium.terrain.host.VanillaUploadSeam.suppressedThisWorld()
                && !com.deds.meshelium.terrain.host.VanillaUploadSeam.vanillaHasGeometry()) {
            com.deds.meshelium.terrain.host.VanillaUploadSeam.demote("coverage guard tripped");
            return false; // keep drawing; holes beat nothing
        }
        return true;
    }

    /**
     * Say once, at the moment of the trip, that sections were dropped.
     *
     * <p>This used to sit after the ownership rule's early return, so while
     * the upload seam had suppressed anything the guard tripped SILENTLY:
     * no warning, no counter, nothing on the options screen, until a rebuild
     * had finished putting vanilla back tens of frames later. With duplicate
     * freeing now on by default that is the common case, not the corner one,
     * and it hid the single most useful diagnostic the renderer has behind
     * the setting most likely to be involved.</p>
     *
     * <p>The trip is a fact about the residency counters at this instant.
     * Who ends up drawing the frame is a separate decision, taken below, and
     * it should not decide whether the player is told.</p>
     */
    private static void reportCoverageTripOnce() {
        if (!coverageWarned) {
            coverageWarned = true;
            com.deds.meshelium.MesheliumNotify.chat("meshelium.chat.error.coverage");
            coverageTrips++;
            TerrainResidency.Counters c = TerrainResidency.counters();
            MesheliumClient.LOGGER.warn(
                    "Meshelium coverage guard: sections were dropped this world — {} — "
                            + "(arenaFull={}, oversize={}, regionBudget={}, encoding={} — lifetime "
                            + "counts) [latched error: {}] "
                            + "so a Meshelium frame would have holes; vanilla draws everything "
                            + "until a world load with clean counters (once-only report)",
                    TerrainResidency.guardTripDescription(),
                    c.droppedArenaFull(), c.droppedOversize(),
                    c.droppedRegionBudget(), c.droppedEncoding(),
                    // The exception text, INLINE. It was always latched and
                    // always reachable, but only from a separate line the
                    // owner's log kept truncating before - so twice now a
                    // report has arrived naming a cause with no way to tell
                    // which throw produced it. A diagnosis that needs a
                    // second lookup is a diagnosis nobody gets.
                    TerrainResidency.lastError() == null
                            ? "none" : TerrainResidency.lastError());
        }
    }

    /**
     * Dispatcher teardown (wired from {@link MesheliumTerrainPump}): drop
     * the per-world occlusion resources and the cached snapshot era. The
     * frame stamp deliberately keeps counting — monotonicity is the
     * staleness guard (TerrainOcclusion javadoc); the stats timeline
     * restarts with the fresh (zero-filled) ring.
     */
    public static void onDispatcherDispose() {
        if (occlusion != null) {
            occlusion.destroy();
            occlusion = null;
        }
        // Wave-10: the extended frame-list rings are per-world (their
        // capacity is the world's pinned scaling); the failure latch
        // re-arms so the next world retries creation.
        if (frameLists != null) {
            frameLists.destroy();
            frameLists = null;
        }
        frameListsFailed = false;
        cachedEpoch = Long.MIN_VALUE;
        snapshot = null;
        // Wave-12: the cachedCull memo references the dropped snapshot's
        // epoch/arrays; the ext-mode shadow is sized per world.
        ccValid = false;
        ccShadow = null;
        // Wave-8 coverage guard: TerrainResidency reset its drop baseline
        // in the same dispose flow — re-arm the once-only WARN so a tripped
        // NEXT world reports again (coveragePassive itself re-evaluates at
        // the next drawOpaque from the fresh baseline).
        coverageWarned = false;
        // Wave-7: the occlusion carry references queued-for-destroy buffers
        // and the translucent map references the dropped snapshot.
        occGateSerial = -1;
        occCurStampsHandle = 0L;
        occRasteredRegions.clear();
        translucentSlotByPos.clear();
        opaqueOwnedSerial = -1;
        statsFrames = 0;
        gpuSectionsDrawn = 0;
        gpuPhaseASections = 0;
        gpuPhaseBSections = 0;
        lastPhaseBStatsFrame = -1;
        lastDispatchChangeStatsFrame = -1;
        lastReadStatsFrame = -1;
        prevOcclusionSignature = 0;
        pbSkipKeyValid = false;
        pbSkipInputChangeStatsFrame = 0;
        pbSkipEpoch = Long.MIN_VALUE;
        pbSkipLastOccSerial = Long.MIN_VALUE;
        Arrays.fill(phaseBFrames, -1);
        Arrays.fill(changeFrames, -1);
        changeCursor = 0;
    }

    /**
     * Wave-15: the pinned scaling snapshot is about to GROW mid-world
     * (live render-distance raise — called by
     * {@code MesheliumTerrainGpu.growRecords} from the pump, render thread,
     * BEFORE the snapshot swap). Drop every drawer resource whose SIZE
     * derives from the old snapshot so the existing lazy per-world create
     * paths rebuild them at the new sizes before this frame's first draw:
     * the occlusion stamp buffers are indexed {@code regionId*256+slot}
     * and a new-budget id against the old-sized buffers would be an OOB
     * shader write; the frame-list rings carry the pinned
     * dispatchCapacity. Both destroy paths are the fence-safe
     * deferred-destroy ones the dispatcher dispose already uses; the
     * fresh zero-filled stamps cost one standup-identical frame (phase A
     * empty, phase B repaints the same frame — the world-standup
     * behaviour, correct pixels). The cached draw snapshot and the
     * wave-12 memo drop with them (they reference the old record-buffer
     * handle era); coverage-guard state, error latches and the frame
     * stamp are deliberately NOT touched.
     */
    public static void onPinnedRegrow() {
        if (occlusion != null) {
            occlusion.destroy();
            occlusion = null;
        }
        if (frameLists != null) {
            frameLists.destroy();
            frameLists = null;
        }
        frameListsFailed = false; // retry creation at the new capacity
        cachedEpoch = Long.MIN_VALUE;
        snapshot = null;
        ccValid = false;
        ccShadow = null;
        occGateSerial = -1;
        occCurStampsHandle = 0L;
        occRasteredRegions.clear();
        translucentSlotByPos.clear();
        // The stats timeline belongs to the occlusion instance's readback
        // ring — the fresh (zero-filled) ring restarts it, exactly like
        // the dispose path (coverage-guard state and error latches are
        // deliberately NOT touched; they are per-world, not per-ring).
        opaqueOwnedSerial = -1;
        statsFrames = 0;
        gpuSectionsDrawn = 0;
        gpuPhaseASections = 0;
        gpuPhaseBSections = 0;
        lastPhaseBStatsFrame = -1;
        lastDispatchChangeStatsFrame = -1;
        lastReadStatsFrame = -1;
        prevOcclusionSignature = 0;
        pbSkipKeyValid = false;
        pbSkipInputChangeStatsFrame = 0;
        pbSkipEpoch = Long.MIN_VALUE;
        pbSkipLastOccSerial = Long.MIN_VALUE;
        Arrays.fill(phaseBFrames, -1);
        Arrays.fill(changeFrames, -1);
        changeCursor = 0;
    }

    /**
     * Wave-8 destroy sweep: the drawer's device-lifetime objects — the
     * three cached {@link TerrainDrawPipeline}s and {@link TerrainOcclusion}'s
     * static box-raster pipelines/layouts — destroyed at device close
     * (called by {@link MesheliumDeviceTeardown} from the
     * {@code VulkanDevice.close()} hook, AFTER vanilla's
     * {@code VulkanCommandEncoder.destroy()} ran its {@code waitIdle}, so
     * every submission that could reference them has provably completed and
     * the VkDevice is still valid — {@code vkDestroyDevice} comes later in
     * the same close, bytecode-cited on the mixin). Nulls the caches so a
     * hypothetical future device would lazily rebuild rather than reuse
     * dead handles. Also sweeps a still-live per-world occlusion instance
     * defensively (normally the dispatcher dispose already dropped it —
     * {@code Minecraft.close()} runs {@code levelRenderer.close()} at
     * ip 63, long before the device close at ip 128).
     */
    public static void destroyDeviceObjects(org.lwjgl.vulkan.VkDevice device) {
        if (taskPipeline != null) {
            taskPipeline.destroy(device);
            taskPipeline = null;
        }
        if (taskPipelineExt != null) {
            taskPipelineExt.destroy(device);
            taskPipelineExt = null;
        }
        if (cpuPipeline != null) {
            cpuPipeline.destroy(device);
            cpuPipeline = null;
        }
        if (translucentPipeline != null) {
            translucentPipeline.destroy(device);
            translucentPipeline = null;
        }
        // Wave-9: the timestamp query pool is device-lifetime like the
        // pipelines (direct destroy — the deferred queue is drained here).
        MesheliumGpuTimers.destroyDeviceObjects();
        if (occlusion != null) {
            MesheliumClient.LOGGER.warn(
                    "Meshelium occlusion buffers still live at device close (the dispatcher "
                            + "dispose never ran?); destroying directly");
            occlusion.destroyNow();
            occlusion = null;
        }
        if (frameLists != null) {
            MesheliumClient.LOGGER.warn(
                    "Meshelium extended frame lists still live at device close (the dispatcher "
                            + "dispose never ran?); destroying directly");
            frameLists.destroyNow();
            frameLists = null;
        }
        TerrainOcclusion.destroyPipelines(device);
    }

    // ------------------------------------------------------------------
    // Frame hooks (mixins; render thread)
    // ------------------------------------------------------------------

    /** {@code LevelRenderer.render} HEAD: stash this frame's camera state. */
    public static void beginFrame(CameraRenderState cameraRenderState) {
        camera = cameraRenderState;
        frameSerial++;
        // Smart/Solid Leaves Beyond: publish the camera section where the
        // build workers and the residency walker can read it — the CPU twin of
        // the CameraChunk ivec4 uploadScene writes per frame from the same
        // blockPos >> 4. The volatile lives on the HOST side
        // (SectionBuildTap) because neither the tap nor TerrainResidency
        // may reference this LWJGL-importing class; the drawer already
        // reaches into that package freely.
        if (cameraRenderState != null && cameraRenderState.initialized
                && cameraRenderState.blockPos != null) {
            SectionBuildTap.publishCameraSection(
                    cameraRenderState.blockPos.getX() >> 4,
                    cameraRenderState.blockPos.getZ() >> 4);
        }
    }

    /**
     * The kill switch's body. Returns true iff Meshelium recorded (or
     * deliberately owns) the opaque terrain this frame — ONLY then does the
     * mixin cancel vanilla's renderGroup. Any internal failure returns
     * false and vanilla draws normally.
     */
    public static boolean drawOpaque(ChunkSectionsToRender sections, GpuSampler atlasSampler) {
        if (broken) {
            return notePrepOutcome(false);
        }
        // Wave-8 coverage guard — the frame's single read (see the method
        // javadoc for the no-mid-frame-flip argument). Passive frames set
        // opaqueOwnedSerial to "not ours" so translucent follows vanilla.
        if (coverageGuardBlocks()) {
            opaqueOwnedSerial = -1;
            return notePrepOutcome(false);
        }
        try {
            long t0 = System.nanoTime();
            boolean owned = drawOpaqueInner(sections, atlasSampler);
            // Wave-7 coupling marker: translucent may own THIS frame only
            // when opaque did (they share depth-authorship semantics).
            opaqueOwnedSerial = owned ? frameSerial : -1;
            if (owned) {
                cancelledGroups++;
                long dt = System.nanoTime() - t0;
                // Wave-12 stage (d1): the opaque recording span as a
                // per-frame series (same clock recordPerf averages).
                if (MesheliumCpuStages.ARMED) {
                    MesheliumCpuStages.record(MesheliumCpuStages.STAGE_MESHELIUM_OPAQUE, dt);
                }
                recordPerf(dt);
            }
            return notePrepOutcome(owned);
        } catch (GpuDeviceLossException t) {
            // Wave-8: the device is gone, not Meshelium's logic — go passive
            // without pretending it is a Meshelium bug. Vanilla's very next
            // GPU call will hit the same loss and crash exactly as an
            // unmodded client would (no client-side handler exists for it,
            // jar-verified) — never harder because of Meshelium.
            deviceLost = true;
            broken = true;
            latchError(t);
            MesheliumClient.LOGGER.error(
                    "GPU device lost during Meshelium's terrain pass; Meshelium goes passive "
                            + "(vanilla will report the loss on its own next call)", t);
            return notePrepOutcome(false);
        } catch (Throwable t) {
            broken = true;
            latchError(t);
            MesheliumClient.LOGGER.error(
                    "Meshelium terrain draw failed; vanilla terrain resumes for this session "
                            + "(first and only report)", t);
            return notePrepOutcome(false);
        }
    }

    private static boolean drawOpaqueInner(ChunkSectionsToRender sections, GpuSampler atlasSampler) {
        CameraRenderState cam = camera;
        if (cam == null || !cam.initialized || cam.cullFrustum == null) {
            return false; // no frame state yet — vanilla draws this frame
        }
        RenderTarget target = ChunkSectionLayerGroup.OPAQUE.outputTarget();
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        GpuTextureView atlasView = sections.textureView();
        GpuTextureView lightmapView = Minecraft.getInstance().gameRenderer.lightmap();
        if (colorView == null || depthView == null || atlasView == null || lightmapView == null) {
            return false;
        }

        // Refresh the epoch-cached snapshot (+ the region-key map with it).
        TerrainResidency.DrawSnapshot fresh = TerrainResidency.drawSnapshot(cachedEpoch);
        if (fresh != null) {
            snapshot = fresh;
            cachedEpoch = fresh.epoch();
            rebuildRegionMap(fresh);
        }
        TerrainResidency.DrawSnapshot snap = snapshot;
        // EMPTY and ABSENT are different, and treating them alike was a hole.
        //
        // EMPTY (sectionCount == 0) means the world is still streaming in.
        // Meshelium owns the group anyway: an empty pass is skipped, vanilla
        // stays cancelled, and no frame is double-drawn. Vanilla has nothing
        // to draw either, so owning nothing costs nothing.
        //
        // ABSENT (no snapshot, or no arena backing) means Meshelium CANNOT
        // draw this world - standup was refused, or the pump latched broken.
        // Claiming ownership there cancels the only renderer that still
        // works and the player gets an empty world instead of a slow one.
        // Hand the frame back to vanilla.
        if (snap == null || snap.arenaBackingHandle() == 0L) {
            lastDrawnSections = 0;
            // Hand the frame back to vanilla ONLY if vanilla can still draw
            // it. wouldOwnFrame() does not model the arena being absent, so
            // when it predicted true the mixin already skipped vanilla's
            // prep and vanilla's lists are EMPTY: refusing there does not
            // rescue the frame, it blanks it, and the bench legs caught
            // exactly that as 10 prepSkipHoleFrames at world standup.
            // Owning an empty frame costs nothing, because with no arena
            // there is nothing to draw either way.
            return prepSkippedSerial == frameSerial;
        }
        if (snap.sectionCount() == 0) {
            lastDrawnSections = 0;
            return true;
        }

        // The cpuCull escape hatch — re-read every call, like PROPERTY.
        if (Boolean.getBoolean(PROPERTY_CPU_CULL) || snap.sectionRecordsHandle() == 0L) {
            return drawCpuCulled(snap, cam, atlasSampler,
                    colorView, depthView, atlasView, lightmapView);
        }
        // Wave-6 default: GPU-raster occlusion. Effective setting since
        // 1.1: bfsOnly property ?? Auto/On/Off, where AUTO compares the
        // EFFECTIVE render distance against the configured crossover.
        // Re-read every call (MesheliumConfig matrix), so the harness
        // property flip, the options-screen mode change and a mid-session
        // render-distance slider move all land next frame. A latched
        // occlusion failure reverts to wave-5 entirely.
        //
        // getEffectiveRenderDistance, not the raw option: on a server the
        // client cannot see past the server's cap, so the raw option would
        // arm occlusion for terrain that is not there.
        if (MesheliumConfig.occlusionCullingEnabled(effectiveRenderDistance()) && !occlusionBroken) {
            try {
                Boolean occluded = drawOcclusionCulled(snap, cam, atlasSampler,
                        colorView, depthView, atlasView, lightmapView);
                if (occluded != null) {
                    return occluded;
                }
                // null = resources not up yet (device facade pending):
                // fall through to the BFS feed for this frame, no latch.
            } catch (GpuDeviceLossException t) {
                throw t; // wave-8: a lost device is not an occlusion bug —
                         // the outer drawOpaque catch latches the drawer
            } catch (Throwable t) {
                occlusionBroken = true;
                occlusionError = t.toString();
                MesheliumClient.LOGGER.error(
                        "Meshelium occlusion culling failed; reverting to the BFS visibility feed "
                                + "for this session (first and only report)", t);
                // Fall through: the BFS pass below may double-draw opaque
                // terrain over a partially recorded occlusion frame once —
                // depth-tested and idempotent, visually identical.
            }
        }
        return drawTaskCulled(snap, cam, atlasSampler,
                colorView, depthView, atlasView, lightmapView);
    }

    // ------------------------------------------------------------------
    // Wave-6 two-phase occlusion path
    // ------------------------------------------------------------------

    /**
     * Nvidium's temporal occlusion loop (§5), stamp-shaped and same-frame
     * (see {@link TerrainOcclusion} for the visibility representation and
     * the pass/barrier story):
     * <ol>
     *   <li><b>Phase A</b> (pass 1): draw every section marked visible by
     *       LAST frame's raster (VisMode 1) — primes this frame's depth
     *       with last frame's visible set, Nvidium's phase 1.</li>
     *   <li><b>Region raster</b> (pass 2): occupancy-AABB boxes of all
     *       dispatched regions against that depth; survivors stamp their
     *       dispatch slot.</li>
     *   <li><b>Section raster</b> (pass 3): tight section boxes of
     *       raster-visible regions; survivors stamp regionId*256+slot in
     *       the CURRENT ping-pong buffer.</li>
     *   <li><b>Phase B</b> (pass 4): draw sections marked THIS frame that
     *       phase A did not draw (VisMode 2) — Nvidium's temporal pass,
     *       the latency hider: a camera cut is fully repainted within the
     *       same frame.</li>
     * </ol>
     * Regions past the {@link TerrainOcclusion#MAX_OCC_REGIONS} list cap
     * fail OPEN: phase A draws them maskless (VisMode 0 + no-mask
     * sentinel), no boxes raster, phase B skips them.
     *
     * @return true/false = owned/failed (the drawOpaque contract); null =
     *         occlusion resources not available yet, caller falls back
     */
    private static Boolean drawOcclusionCulled(TerrainResidency.DrawSnapshot snap, CameraRenderState cam,
            GpuSampler atlasSampler, GpuTextureView colorView,
            GpuTextureView depthView, GpuTextureView atlasView, GpuTextureView lightmapView) {
        if (occlusion == null) {
            occlusion = TerrainOcclusion.create();
            if (occlusion == null) {
                return null; // device facade not up; retry next frame
            }
        }
        // Wave-10: extended worlds write the occlusion list into the SSBO
        // ring instead of the 16 KiB transient UBO; if the ring could not
        // be created the standard 512-cap UBO path serves as the fallback
        // (fail-open overflow past 512, exactly the wave-6 behaviour).
        boolean extLists = ensureFrameLists();
        int occCapacity = extLists ? frameLists.capacity() : TerrainOcclusion.MAX_OCC_REGIONS;

        int regionCount = snap.regionCount();
        int[] rd = snap.regionData();
        Frustum frustum = cam.cullFrustum;
        double camX = cam.pos.x;
        double camY = cam.pos.y;
        double camZ = cam.pos.z;

        long stamp = ++occFrameStamp;
        int frameStamp32 = (int) stamp;

        // Wave-12 cachedCull (default OFF — knob absent keeps this frame
        // byte-identical to wave 10: same write targets, same loop). Knob
        // ON: the list bytes are built in a PERSISTENT buffer (standard
        // mode: occUpload already is one; ext mode: a shadow that refills
        // the rotating ring slot below), and a bit-identical input key
        // (cachedCullFresh) skips the whole region loop + sort — the memo
        // is exact, so a hit records bitwise the commands a rebuild would.
        boolean cacheOn = MesheliumConfig.cachedCullEnabled();
        ByteBuffer occDst;
        if (!cacheOn) {
            ccValid = false;
            occDst = extLists ? frameLists.occWriteView(frameSerial) : occUpload;
        } else {
            occDst = extLists ? ccShadow(frameLists.occRange()) : occUpload;
        }
        boolean cacheHit = cacheOn && cachedCullFresh(cam, frustum, extLists, occCapacity, regionCount);

        int dispatched;
        long sig;
        if (cacheHit) {
            dispatched = ccDispatched;
            sig = ccSig;
            occOverflowRegions += ccOverflowThisFrame;
            cachedCullHits++;
            // regionMeta/regionOrigins (already front-to-back sorted),
            // occRasteredRegions and the persistent list bytes all survive
            // from the miss frame that built them.
        } else {
            // ---- 1. dispatch list: CPU frustum over regions (no mask feed) ----
            int built = 0;
            long builtSig = 0;
            long overflowThisFrame = 0;
            occRasteredRegions.clear(); // wave-7: which regions get boxes this frame
            for (int r = 0; r < regionCount; r++) {
                int ro = r * TerrainResidency.DrawSnapshot.REGION_STRIDE;
                int id = rd[ro];
                int count = rd[ro + 4];
                if (count <= 0) {
                    continue;
                }
                double bx = rd[ro + 1] * 128.0;
                double by = rd[ro + 2] * 64.0;
                double bz = rd[ro + 3] * 128.0;
                if (!frustum.isVisible(new AABB(bx, by, bz, bx + 128.0, by + 64.0, bz + 128.0))) {
                    continue;
                }
                if ((built + 1) * 3 > regionMeta.length) {
                    regionMeta = Arrays.copyOf(regionMeta, regionMeta.length * 2);
                    regionOrigins = Arrays.copyOf(regionOrigins, regionOrigins.length * 2);
                }
                float ox = (float) (bx - camX);
                float oy = (float) (by - camY);
                float oz = (float) (bz - camZ);
                regionOrigins[built * 3] = ox;
                regionOrigins[built * 3 + 1] = oy;
                regionOrigins[built * 3 + 2] = oz;
                int slot;
                if (built < occCapacity) {
                    slot = built;
                    occRasteredRegions.add(id);
                    int dst = slot * TerrainOcclusion.OCC_ENTRY_BYTES;
                    occDst.putFloat(dst, ox);
                    occDst.putFloat(dst + 4, oy);
                    occDst.putFloat(dst + 8, oz);
                    occDst.putFloat(dst + 12, 0f);
                    occDst.putInt(dst + 16, id);           // meta.x regionId
                    occDst.putInt(dst + 20, count);        // meta.y compacted count
                    occDst.putInt(dst + 24, rd[ro + 5]);   // meta.z occMin packed
                    occDst.putInt(dst + 28, rd[ro + 6]);   // meta.w occMax packed
                } else {
                    slot = -1; // fail-open overflow (standard capacity only —
                               // extended capacity == the region budget, so
                               // this branch is unreachable there)
                    overflowThisFrame++;
                }
                regionMeta[built * 3] = id;
                regionMeta[built * 3 + 1] = slot;
                regionMeta[built * 3 + 2] = (count + taskWorkgroupSections() - 1) / taskWorkgroupSections();
                builtSig += (id + 1L) * (2L * id + 31L);
                built++;
            }
            dispatched = built;
            sig = builtSig;
            occOverflowRegions += overflowThisFrame;
            if (cacheOn) {
                cachedCullMisses++;
                ccDispatched = dispatched;
                ccSig = sig;
                ccOverflowThisFrame = overflowThisFrame;
                ccStoreKey(cam, frustum, extLists, occCapacity, regionCount);
            }
        }

        regionsDispatched = dispatched;
        dispatchSignature = sig;
        if (sig != prevOcclusionSignature) {
            prevOcclusionSignature = sig;
            lastDispatchChangeStatsFrame = statsFrames; // the frame about to record
            changeFrames[changeCursor] = statsFrames;
            changeCursor = (changeCursor + 1) % changeFrames.length;
        }
        if (dispatched == 0) {
            lastDrawnSections = 0;
            return true; // own the group; nothing in the frustum
        }
        // Wave-9: front-to-back early-z ordering. Applies to BOTH phase A
        // and phase B recording below; the occlusion-list/mask SLOTS ride
        // inside the region triplets, so the raster passes and the stamp
        // indexing are untouched by the permutation. Cache hits skip it:
        // the arrays are already the sorted output of their miss frame.
        if (!cacheHit) {
            sortRegionsFrontToBack(dispatched);
        }

        // ---- 2. lagged GPU stats readback (before this frame records) ----
        pullGpuStats(true);

        // ---- 2b. phase-B CPU skip decision (needs this frame's readback
        // fold above AND the signature bookkeeping before it; consumed at
        // pass 4) ----
        boolean phaseBCpuSkip = phaseBCpuSkipDecide(cam, frustum, depthView);

        // ---- 3. per-frame list + transient uploads (before any pass
        // opens, wave-2 note) ----
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuBufferSlice sceneSlice = uploadScene(encoder, cam,
                atlasView.getWidth(0), atlasView.getHeight(0));
        int rasterCount = Math.min(dispatched, occCapacity);
        TerrainOcclusion.ListSlice occListSlice;
        if (extLists) {
            if (cacheOn) {
                // Wave-12: knob ON keeps the list bytes in the persistent
                // shadow — refill THIS frame's ring slot (hit or miss) so
                // the wave-10 ring rotation/safety story is untouched; the
                // slot receives the same bytes the knob-OFF loop would
                // have written directly.
                ByteBuffer ring = frameLists.occWriteView(frameSerial);
                ByteBuffer src = occDst.duplicate();
                src.position(0).limit(rasterCount * TerrainOcclusion.OCC_ENTRY_BYTES);
                ring.put(src);
            }
            // Written in place — the ring slot IS the GPU buffer
            // (host-coherent mapping; ring safety on MesheliumFrameLists).
            occListSlice = new TerrainOcclusion.ListSlice(frameLists.occBuffer(),
                    frameLists.occOffset(frameSerial), frameLists.occRange(), true);
        } else {
            occUpload.clear();
            occListSlice = TerrainOcclusion.ListSlice.ofUniformSlice(encoder.transientMemory()
                    .uploadGpu(occUpload, 256, GpuBuffer.USAGE_UNIFORM));
        }
        long prevStamps = occlusion.prevStampsBuffer(stamp);
        long curStamps = occlusion.curStampsBuffer(stamp);

        long taskGroupsThisFrame = 0;

        // Wave-9 GPU timing: every timestamp is written BETWEEN passes
        // (the encoder is between passes there; vanilla's pass-end
        // ALL_COMMANDS barrier makes consecutive stamps bracket whole
        // passes). Pixel-neutral; failures latch the timers, never us.
        MesheliumGpuTimers.beginOpaque(encoder, frameSerial);

        // ---- pass 1: phase A (+ lazy pipeline builds at the seam) ----
        try (RenderPass pass = encoder.createRenderPass(() -> "meshelium terrain phase A",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            VulkanRenderPass backendPass = (VulkanRenderPass) ((RenderPassAccessor) pass).meshelium$backend();
            VulkanRenderPassAccessor vkPass = (VulkanRenderPassAccessor) backendPass;
            VkCommandBuffer cb = vkPass.meshelium$commandBuffer();

            TerrainDrawPipeline p = pipelineFor(vkPass, colorView, depthView, true, extLists);
            occlusion.ensurePipelines(VulkanConst.toVk(colorView.texture().getFormat()),
                    VulkanConst.toVk(depthView.texture().getFormat()), extLists);

            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipeline());
            // Binding 8 (mask list) is never read in stamp modes — the occ
            // list slice doubles as a type-correct dummy in BOTH variants
            // (UBO slice standard, SSBO slot extended).
            pushDescriptors(cb, p, snap.arenaBlockHandles(), sceneSlice,
                    atlasView, atlasSampler, lightmapView,
                    snap.sectionRecordsHandle(), occListSlice,
                    prevStamps, curStamps, occlusion.statsBuffer());
            taskGroupsThisFrame += recordPhaseDraws(cb, p, dispatched, frameStamp32, MODE_PHASE_A);
        }
        MesheliumGpuTimers.mark(encoder, MesheliumGpuTimers.POINT_AFTER_PHASE_A);

        // ---- pass 2: region boxes ----
        try (RenderPass pass = encoder.createRenderPass(() -> "meshelium occlusion regions",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            VulkanRenderPass backendPass = (VulkanRenderPass) ((RenderPassAccessor) pass).meshelium$backend();
            VkCommandBuffer cb = ((VulkanRenderPassAccessor) backendPass).meshelium$commandBuffer();
            occlusion.recordRegionRaster(cb, occListSlice, sceneSlice, rasterCount, frameStamp32);
        }
        MesheliumGpuTimers.mark(encoder, MesheliumGpuTimers.POINT_AFTER_REGION_RASTER);

        // ---- pass 3: section boxes ----
        try (RenderPass pass = encoder.createRenderPass(() -> "meshelium occlusion sections",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            VulkanRenderPass backendPass = (VulkanRenderPass) ((RenderPassAccessor) pass).meshelium$backend();
            VkCommandBuffer cb = ((VulkanRenderPassAccessor) backendPass).meshelium$commandBuffer();
            occlusion.recordSectionRaster(cb, occListSlice, sceneSlice,
                    snap.sectionRecordsHandle(), rasterCount, frameStamp32, curStamps);
        }
        MesheliumGpuTimers.mark(encoder, MesheliumGpuTimers.POINT_AFTER_SECTION_RASTER);

        // ---- pass 4: phase B (the latency hider) ----
        // Measurement-only switch, re-added 2026-08-16 for the predicate
        // work: skipping phase B outright is DELIBERATELY INCORRECT
        // (revealed terrain arrives a frame late) and exists so the prize of
        // a correct conditional-rendering skip can be re-measured in the
        // same session that evaluates it. Never a setting.
        //
        // phaseBCpuSkip (decided at 2b) rides the same guard and IS exact:
        // it elides the recording only when the pass provably draws zero
        // sections. Passes 1-3, the stamp timeline, the stats transfer and
        // the translucent carry all run unchanged either way.
        boolean phaseBSkipUnsafe = Boolean.getBoolean("meshelium.occlusion.phaseBSkipUnsafe");
        if (!phaseBSkipUnsafe && !phaseBCpuSkip)
        try (RenderPass pass = encoder.createRenderPass(() -> "meshelium terrain phase B",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            VulkanRenderPass backendPass = (VulkanRenderPass) ((RenderPassAccessor) pass).meshelium$backend();
            VulkanRenderPassAccessor vkPass = (VulkanRenderPassAccessor) backendPass;
            VkCommandBuffer cb = vkPass.meshelium$commandBuffer();

            TerrainDrawPipeline p = pipelineFor(vkPass, colorView, depthView, true, extLists);
            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipeline());
            // Push-descriptor state does not survive the foreign layouts
            // bound in passes 2/3 — push again (frame-path Q3.2's
            // compatibility rule).
            pushDescriptors(cb, p, snap.arenaBlockHandles(), sceneSlice,
                    atlasView, atlasSampler, lightmapView,
                    snap.sectionRecordsHandle(), occListSlice,
                    prevStamps, curStamps, occlusion.statsBuffer());
            // The predicate skip: the section raster set this frame's
            // 4-byte predicate to 1 iff any section transitioned to
            // newly-visible, and conditional rendering executes the
            // dispatches below only then. On the measured static frame that
            // is 0.234 ms of task dispatches that draw nothing, skipped by
            // the GPU with no CPU readback and no one-frame reveal artifact
            // (a cut marks, so a cut runs). Vanilla's pass-end ALL_COMMANDS
            // barrier between passes 3 and 4 orders the fragment write
            // against this read exactly as it already orders curStamps
            // against phase B's task reads.
            boolean phaseBPredicated = occlusion.phaseBPredicateActive();
            if (phaseBPredicated) {
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    org.lwjgl.vulkan.VkConditionalRenderingBeginInfoEXT begin =
                            org.lwjgl.vulkan.VkConditionalRenderingBeginInfoEXT.calloc(stack)
                                    .sType(org.lwjgl.vulkan.EXTConditionalRendering
                                            .VK_STRUCTURE_TYPE_CONDITIONAL_RENDERING_BEGIN_INFO_EXT)
                                    .buffer(occlusion.predicateVkBuffer())
                                    .offset(0L)
                                    .flags(0);
                    org.lwjgl.vulkan.EXTConditionalRendering
                            .vkCmdBeginConditionalRenderingEXT(cb, begin);
                }
            }
            taskGroupsThisFrame += recordPhaseDraws(cb, p, dispatched, frameStamp32, MODE_PHASE_B);
            if (phaseBPredicated) {
                org.lwjgl.vulkan.EXTConditionalRendering.vkCmdEndConditionalRenderingEXT(cb);
            }
        }
        MesheliumGpuTimers.mark(encoder, MesheliumGpuTimers.POINT_AFTER_PHASE_B);

        // ---- stats copy + zero (the transfer CB; barrier story in
        // TerrainOcclusion) ----
        occlusion.recordStatsTransfer(statsFrames);
        statsFrames++;

        // Wave-7 carry: the translucent pass (recorded later this frame at
        // vanilla's translucentTerrain position, AFTER pass 3's stamps) may
        // gate on THIS frame's raster verdicts.
        occGateSerial = frameSerial;
        occCurStampsHandle = curStamps;
        occGateStamp32 = frameStamp32;

        perfTaskGroupsAccum += taskGroupsThisFrame;
        framesDrawn++;
        taskCullFrames++;
        occlusionFrames++;
        totalDrawnSections += Math.max(gpuSectionsDrawn, 0);
        return true;
    }

    /**
     * The render distance occlusion's Auto mode decides against: vanilla's
     * EFFECTIVE distance, so a server cap is honoured. Render thread only.
     * Falls back to the option itself if options are somehow absent, which
     * keeps Auto conservative rather than crashing a draw.
     */
    private static int effectiveRenderDistance() {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.options != null
                ? client.options.getEffectiveRenderDistance()
                : 0;
    }

    /**
     * One phase's per-region draws. Phase A: occlusion-listed regions test
     * last frame's stamps; overflow regions draw maskless (fail-open).
     * Phase B: occlusion-listed regions only (overflow was fully drawn in
     * A; drawing it again would double work for nothing).
     */
    private static long recordPhaseDraws(VkCommandBuffer cb, TerrainDrawPipeline p,
            int dispatched, int frameStamp32, int mode) {
        long taskGroups = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.calloc(TerrainDrawPipeline.PUSH_BYTES);
            int pushStages = p.pushStageFlags();
            for (int r = 0; r < dispatched; r++) {
                int slot = regionMeta[r * 3 + 1];
                int visMode;
                int maskSlot;
                if (slot >= 0) {
                    visMode = mode;
                    maskSlot = NO_MASK_SLOT; // unused in stamp modes
                } else if (mode == MODE_PHASE_A) {
                    visMode = MODE_MASK;     // overflow: draw everything once
                    maskSlot = NO_MASK_SLOT;
                } else {
                    continue;                // overflow already drawn in A
                }
                push.putFloat(0, regionOrigins[r * 3]);
                push.putFloat(4, regionOrigins[r * 3 + 1]);
                push.putFloat(8, regionOrigins[r * 3 + 2]);
                push.putInt(12, regionMeta[r * 3]);  // RegionIndex
                push.putInt(16, maskSlot);
                push.putInt(20, visMode);
                push.putInt(24, frameStamp32);
                push.putInt(28, 1);                  // StatsFlags: count survivors
                VK10.vkCmdPushConstants(cb, p.pipelineLayout(), pushStages, 0, push);
                int groups = regionMeta[r * 3 + 2];
                EXTMeshShader.vkCmdDrawMeshTasksEXT(cb, groups, 1, 1);
                taskGroups += groups;
            }
        }
        return taskGroups;
    }

    /**
     * Fold the lagged stats slot into the probes. Runs at the top of every
     * stats-recording frame; the slot read is {@code statsFrames −
     * READBACK_LAG}, complete by the 2-submits-in-flight argument.
     *
     * @param adoptAsPrimary occlusion mode: the GPU count IS the frame's
     *        sections-drawn figure (there is no CPU-side equivalent);
     *        bfs mode passes false and keeps the wave-5 CPU popcount in
     *        {@code lastDrawnSections}/{@code sectionsVisibleIn}
     */
    private static void pullGpuStats(boolean adoptAsPrimary) {
        if (occlusion == null) {
            return;
        }
        long readFrame = statsFrames - TerrainOcclusion.READBACK_LAG;
        int[] s = occlusion.readStats(readFrame);
        if (s == null) {
            return;
        }
        int total = s[0] + s[1] + s[2];
        gpuSectionsDrawn = total;
        gpuPhaseASections = s[1];
        gpuPhaseBSections = s[2];
        if (adoptAsPrimary) {
            lastDrawnSections = total;
            sectionsVisibleIn = total;
        }
        if (s[2] > 0) {
            lastPhaseBStatsFrame = readFrame;
        }
        int i = (int) (readFrame % HISTORY);
        phaseBFrames[i] = readFrame;
        phaseBCounts[i] = s[2];
        lastReadStatsFrame = readFrame;
    }

    /**
     * The phase-B CPU skip (default OFF, {@link #PROPERTY_PHASE_B_CPU_SKIP},
     * re-read every frame like the other draw-path properties).
     *
     * <p>Attempt 2 (the conditional-rendering predicate) proved the skip
     * itself is exact — phase B emptied with zero artifacts and the
     * camera-cut gate green — and then the RDNA4 driver charged 8-11 ms
     * stalls for the mechanism. Attempt 3 moves the DECISION to the CPU,
     * where nothing can charge for it. Induction over the stamp protocol:
     * stamped(t) is a deterministic function of phase-A depth (which is
     * stamped(t-1) rendered), the dispatch list, the section records, the
     * scene matrices and the raster extent. If every one of those is
     * bit-identical since a frame whose read-back verdict showed zero
     * phase-B draws, phase B draws zero again this frame, and not
     * recording its dispatches is exact, not approximate.</p>
     *
     * <p>Every doubt reads as a change, in the raw-bits discipline
     * {@link #cachedCullFresh} established (NaN and rounding drift
     * disarm, the safe direction): camera position and cull-frustum
     * camera bits, the frustum matrix AND the scene UBO's two factor
     * matrices (the product could theoretically collide), the raster
     * extent (an aspect-preserving resize keeps every matrix bit
     * unchanged and still moves raster coverage), the snapshot epoch
     * (every residency mutation bumps it, and the pump runs before the
     * draw, so this frame's uploads are already visible here), a
     * non-empty region-commit backlog (a requeued commitDirty can land
     * records with no same-frame epoch bump), the dispatch-signature
     * tracker, a gap in occlusion frames (bfs/off interlude, ownership
     * loss), and world resets (the readback trackers restart at -1, so
     * the predicate cannot hold until fresh verdicts land). Mutually
     * exclusive with the GPU predicate — when that is active the pass
     * already skips itself.</p>
     *
     * <p>Failure containment, the property that makes this shippable
     * where full static-frame reuse was not: passes 1-3, the stamp
     * timeline, the stats transfer and the translucent carry all run
     * unchanged on skip frames, so a wrong skip (none is known reachable)
     * costs one frame of late reveal and phase A heals it next frame —
     * never a hole that stays.</p>
     */
    private static boolean phaseBCpuSkipDecide(CameraRenderState cam, Frustum frustum,
            GpuTextureView depthView) {
        // Absent means ON (the multiWG rule: measured winners are
        // defaults, the property is the escape hatch).
        String cpuSkipProp = System.getProperty(PROPERTY_PHASE_B_CPU_SKIP);
        if (cpuSkipProp != null && !Boolean.parseBoolean(cpuSkipProp)) {
            pbSkipKeyValid = false;
            return false;
        }
        if (occlusion.phaseBPredicateActive()) {
            return false;
        }
        boolean changed = !pbSkipKeyValid;
        long posX = Double.doubleToRawLongBits(cam.pos.x);
        long posY = Double.doubleToRawLongBits(cam.pos.y);
        long posZ = Double.doubleToRawLongBits(cam.pos.z);
        long frusX = Double.doubleToRawLongBits(frustum.getCamX());
        long frusY = Double.doubleToRawLongBits(frustum.getCamY());
        long frusZ = Double.doubleToRawLongBits(frustum.getCamZ());
        if (posX != pbSkipPosX || posY != pbSkipPosY || posZ != pbSkipPosZ
                || frusX != pbSkipFrusX || frusY != pbSkipFrusY || frusZ != pbSkipFrusZ) {
            changed = true;
            pbSkipPosX = posX;
            pbSkipPosY = posY;
            pbSkipPosZ = posZ;
            pbSkipFrusX = frusX;
            pbSkipFrusY = frusY;
            pbSkipFrusZ = frusZ;
        }
        // |= not ||=: every compare must also refresh its stored key.
        changed |= pbSkipMatrixChanged(((FrustumAccessor) frustum).meshelium$matrix(), pbSkipFrusMatrix);
        changed |= pbSkipMatrixChanged(cam.viewRotationMatrix, pbSkipViewRot);
        changed |= pbSkipMatrixChanged(cam.projectionMatrix, pbSkipProj);
        int extentW = depthView.texture().getWidth(0);
        int extentH = depthView.texture().getHeight(0);
        if (extentW != pbSkipExtentW || extentH != pbSkipExtentH) {
            changed = true;
            pbSkipExtentW = extentW;
            pbSkipExtentH = extentH;
        }
        if (cachedEpoch != pbSkipEpoch) {
            changed = true;
            pbSkipEpoch = cachedEpoch;
        }
        if (!TerrainResidency.gpuCommitBacklogEmpty()) {
            changed = true;
        }
        if (frameSerial != pbSkipLastOccSerial + 1) {
            changed = true;
        }
        pbSkipLastOccSerial = frameSerial;
        pbSkipKeyValid = true;
        if (changed) {
            pbSkipInputChangeStatsFrame = statsFrames;
        }
        long c = Math.max(pbSkipInputChangeStatsFrame, lastDispatchChangeStatsFrame);
        // The induction base is LAGGED by construction (readback is the
        // only way the CPU ever learns a phase-B count): at least one
        // fully-post-change verdict read back (>= c + 2 leaves a one-frame
        // margin over the strict > c) and no read frame after the change
        // showed a phase-B draw. -1 sentinels: lastReadStatsFrame -1 never
        // arms; lastPhaseBStatsFrame -1 with reads after c means every
        // slot read since standup showed zero, which IS evidence.
        boolean skip = lastReadStatsFrame >= c + 2 && lastPhaseBStatsFrame <= c;
        if (skip) {
            phaseBCpuSkipFrames++;
        }
        return skip;
    }

    /** Raw-bits compare AND refresh of one stored matrix key: true iff moved. */
    private static boolean pbSkipMatrixChanged(Matrix4f m, float[] prev) {
        ccReadMatrix(m, pbSkipScratch);
        boolean changed = false;
        for (int i = 0; i < 16; i++) {
            if (Float.floatToRawIntBits(pbSkipScratch[i]) != Float.floatToRawIntBits(prev[i])) {
                changed = true;
            }
            prev[i] = pbSkipScratch[i];
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // Wave-5 task-culled path
    // ------------------------------------------------------------------

    private static boolean drawTaskCulled(TerrainResidency.DrawSnapshot snap, CameraRenderState cam,
            GpuSampler atlasSampler, GpuTextureView colorView,
            GpuTextureView depthView, GpuTextureView atlasView, GpuTextureView lightmapView) {
        // Wave-12: this path stomps the scratch arrays the occlusion-path
        // memo reuses — a bfs frame between occlusion frames must force
        // the next occlusion frame to rebuild.
        ccValid = false;
        // Wave-10: extended worlds write the masks into the SSBO ring
        // (capacity = the whole region budget); standard worlds keep the
        // wave-5 16 KiB transient-UBO path byte-identical. Ring-creation
        // failure falls back to the standard path (fail-open overflow).
        boolean extLists = ensureFrameLists();
        int maskCapacity = extLists ? frameLists.capacity() : MAX_MASK_REGIONS;
        ByteBuffer visDst = extLists ? frameLists.visWriteView(frameSerial) : visUpload;

        int regionCount = snap.regionCount();
        int[] rd = snap.regionData();
        Frustum frustum = cam.cullFrustum;
        double camX = cam.pos.x;
        double camY = cam.pos.y;
        double camZ = cam.pos.z;

        // ---- 1. the visibility feed: vanilla's visibleSections → masks ----
        Arrays.fill(regionMasks, 0, regionCount * 8, 0);
        ObjectArrayList<SectionRenderDispatcher.RenderSection> visible =
                Minecraft.getInstance().levelRenderer.visibleSections();
        for (int i = 0, n = visible.size(); i < n; i++) {
            long node = visible.get(i).getSectionNode();
            int sx = SectionPos.x(node);
            int sy = SectionPos.y(node);
            int sz = SectionPos.z(node);
            int idx = regionSlotByKey.get(regionKey(sx >> 3, sy >> 2, sz >> 3));
            if (idx < 0) {
                continue; // vanilla lists a section Meshelium has no region for
            }
            int posKey = ((sy & 3) << 6) | ((sz & 7) << 3) | (sx & 7);
            regionMasks[idx * 8 + (posKey >>> 5)] |= 1 << (posKey & 31);
        }

        // ---- 2. CPU frustum over regions + dispatch-list build ----
        // Wave-11: vanilla's visibleSections can never list a RETAINED
        // section (its mesh is gone), so the retained mask — same region
        // order, same bit layout (RegionStore.snapshotRetainedMasks) — is
        // OR'd in: retained-only regions dispatch, retained sections pass
        // the task stage's mask gate, and the extra bits are counted as
        // the retention probe. This is the "fail open for retained"
        // choice: reusing the wave-5 mask machinery verbatim needs no
        // record-format or shader change (the alternative — a retained
        // flag in the 32-byte record — would touch the record codec AND
        // every consumer shader for the same pixels).
        int[] retainedMasks = snap.retainedMasks();
        int dispatched = 0;
        int visIn = 0;
        int retainedIn = 0;
        long sig = 0;
        long overflowThisFrame = 0;
        for (int r = 0; r < regionCount; r++) {
            int ro = r * TerrainResidency.DrawSnapshot.REGION_STRIDE;
            int id = rd[ro];
            int count = rd[ro + 4];
            if (count <= 0) {
                continue;
            }
            double bx = rd[ro + 1] * 128.0;
            double by = rd[ro + 2] * 64.0;
            double bz = rd[ro + 3] * 128.0;
            if (!frustum.isVisible(new AABB(bx, by, bz, bx + 128.0, by + 64.0, bz + 128.0))) {
                continue;
            }
            int maskSlot;
            int pop = 0;
            if (dispatched < maskCapacity) {
                maskSlot = dispatched;
                int dst = maskSlot * 32;
                for (int w = 0; w < 8; w++) {
                    int word = regionMasks[r * 8 + w];
                    int retainedWord = retainedMasks[r * 8 + w];
                    int merged = word | retainedWord;
                    visDst.putInt(dst + w * 4, merged);
                    pop += Integer.bitCount(merged);
                    retainedIn += Integer.bitCount(retainedWord & ~word);
                }
                if (pop == 0) {
                    continue; // no vanilla-visible section in it — skip draw
                }
            } else {
                // UBO capacity exceeded: dispatch WITHOUT a mask (the task
                // stage fails open — draws more, never less). Counted.
                maskSlot = NO_MASK_SLOT;
                overflowThisFrame++;
                pop = count;
            }
            if ((dispatched + 1) * 3 > regionMeta.length) {
                regionMeta = Arrays.copyOf(regionMeta, regionMeta.length * 2);
                regionOrigins = Arrays.copyOf(regionOrigins, regionOrigins.length * 2);
            }
            regionOrigins[dispatched * 3] = (float) (bx - camX);
            regionOrigins[dispatched * 3 + 1] = (float) (by - camY);
            regionOrigins[dispatched * 3 + 2] = (float) (bz - camZ);
            regionMeta[dispatched * 3] = id;
            regionMeta[dispatched * 3 + 1] = maskSlot;
            regionMeta[dispatched * 3 + 2] = (count + taskWorkgroupSections() - 1) / taskWorkgroupSections();
            sig += (id + 1L) * (2L * id + 31L); // order-independent set hash
            visIn += pop;
            dispatched++;
        }

        regionsDispatched = dispatched;
        sectionsVisibleIn = visIn;
        dispatchSignature = sig;
        lastDrawnSections = visIn;
        maskOverflowRegions += overflowThisFrame;
        lastRetainedMaskSections = retainedIn;      // wave-11 probe
        retainedMaskSectionsTotal += retainedIn;
        if (dispatched == 0) {
            return true; // own the group; nothing visible to draw
        }
        // Wave-9: front-to-back early-z ordering (mask slots ride along).
        sortRegionsFrontToBack(dispatched);

        // ---- 3. per-frame list + transient uploads (before the pass
        // opens, wave-2 note) ----
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuBufferSlice sceneSlice = uploadScene(encoder, cam,
                atlasView.getWidth(0), atlasView.getHeight(0));
        TerrainOcclusion.ListSlice visList;
        if (extLists) {
            visList = new TerrainOcclusion.ListSlice(frameLists.visBuffer(),
                    frameLists.visOffset(frameSerial), frameLists.visRange(), true);
        } else {
            visUpload.clear();
            visList = TerrainOcclusion.ListSlice.ofUniformSlice(encoder.transientMemory()
                    .uploadGpu(visUpload, 256, GpuBuffer.USAGE_UNIFORM));
        }

        // Wave-6: keep the GPU stats timeline alive in bfs mode too (the
        // hidden-cave A/B compares GPU counts across BOTH modes) — but
        // only when occlusion resources already exist; bfsOnly must never
        // DEPEND on them (it is the correctness fallback).
        boolean stats = occlusion != null && !occlusionBroken;
        if (stats) {
            pullGpuStats(false);
        }

        // ---- 4. the pass: one dispatch per region ----
        long taskGroupsThisFrame = 0;
        MesheliumGpuTimers.beginOpaque(encoder, frameSerial); // single pass ⇒
                                                            // "opaqueA" = whole opaque
        try (RenderPass pass = encoder.createRenderPass(() -> "meshelium terrain opaque",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            VulkanRenderPass backendPass = (VulkanRenderPass) ((RenderPassAccessor) pass).meshelium$backend();
            VulkanRenderPassAccessor vkPass = (VulkanRenderPassAccessor) backendPass;
            VkCommandBuffer cb = vkPass.meshelium$commandBuffer();

            TerrainDrawPipeline p = pipelineFor(vkPass, colorView, depthView, true, extLists);

            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipeline());
            pushDescriptors(cb, p, snap.arenaBlockHandles(), sceneSlice,
                    atlasView, atlasSampler, lightmapView,
                    snap.sectionRecordsHandle(), visList,
                    stats ? occlusion.prevStampsBuffer(occFrameStamp) : 0L,
                    stats ? occlusion.curStampsBuffer(occFrameStamp) : 0L,
                    stats ? occlusion.statsBuffer() : 0L);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer push = stack.calloc(TerrainDrawPipeline.PUSH_BYTES);
                int pushStages = p.pushStageFlags();
                for (int r = 0; r < dispatched; r++) {
                    push.putFloat(0, regionOrigins[r * 3]);
                    push.putFloat(4, regionOrigins[r * 3 + 1]);
                    push.putFloat(8, regionOrigins[r * 3 + 2]);
                    push.putInt(12, regionMeta[r * 3]);      // RegionIndex
                    push.putInt(16, regionMeta[r * 3 + 1]);  // MaskSlot
                    push.putInt(20, MODE_MASK);              // VisMode 0 = wave-5
                    push.putInt(24, 0);                      // FrameStamp unused
                    push.putInt(28, stats ? 1 : 0);          // StatsFlags
                    VK10.vkCmdPushConstants(cb, p.pipelineLayout(), pushStages, 0, push);
                    int groups = regionMeta[r * 3 + 2];
                    EXTMeshShader.vkCmdDrawMeshTasksEXT(cb, groups, 1, 1);
                    taskGroupsThisFrame += groups;
                }
            }
        }
        MesheliumGpuTimers.mark(encoder, MesheliumGpuTimers.POINT_AFTER_PHASE_A);
        if (stats) {
            occlusion.recordStatsTransfer(statsFrames);
            statsFrames++;
        }

        perfTaskGroupsAccum += taskGroupsThisFrame;
        framesDrawn++;
        totalDrawnSections += visIn;
        taskCullFrames++;
        bfsOnlyFrames++;
        return true;
    }

    /** Region key packing — local, self-consistent (map + lookups only). */
    private static long regionKey(int rx, int ry, int rz) {
        return ((rx & 0x1FFFFFL) << 42) | ((ry & 0x1FFFFFL) << 21) | (rz & 0x1FFFFFL);
    }

    /** Section key packing — local, self-consistent (translucent map only). */
    private static long sectionKey(int sx, int sy, int sz) {
        return ((sx & 0x1FFFFFL) << 42) | ((sy & 0x1FFFFFL) << 21) | (sz & 0x1FFFFFL);
    }

    private static void rebuildRegionMap(TerrainResidency.DrawSnapshot snap) {
        regionSlotByKey.clear();
        int[] rd = snap.regionData();
        int regionCount = snap.regionCount();
        for (int r = 0; r < regionCount; r++) {
            int ro = r * TerrainResidency.DrawSnapshot.REGION_STRIDE;
            regionSlotByKey.put(regionKey(rd[ro + 1], rd[ro + 2], rd[ro + 3]), r);
        }
        if (regionMasks.length < regionCount * 8) {
            regionMasks = new int[Math.max(regionCount * 8, regionMasks.length * 2)];
        }
        // Wave-7: coords → snapshot slot for every section with a
        // translucent prefix that OWNS its region slot ([18] >= 0). A
        // slotless resident (its slot stolen by a newer mesh in the
        // promotion-lag window) is EXCLUDED so a section can never
        // double-blend — the slot owner is also what the GPU records
        // already point at, so opaque and translucent stay consistent.
        // Rebuilt fully on EVERY snapshot adoption (the incremental
        // design's v1 choice: atomic-with-the-swap by construction, so a
        // stale slot value can never dereference a reused slot — the ABA
        // rule, dossier-lifecycle §4), from the NEW buffer's live slots;
        // tombstoned slots fail both filter tests ([4] == 0, [18] == −2).
        translucentSlotByPos.clear();
        int[] d = snap.data();
        int n = snap.maxSlot() + 1;
        for (int s = 0; s < n; s++) {
            int o = s * TerrainResidency.DrawSnapshot.STRIDE;
            if (d[o + 4] > 0 && d[o + 18] >= 0) { // bucketStarts[0] == translucent count
                translucentSlotByPos.put(sectionKey(d[o], d[o + 1], d[o + 2]), s);
            }
        }
    }

    // ------------------------------------------------------------------
    // Wave-4 CPU-culled path (the meshelium.terrainDraw.cpuCull hatch)
    // ------------------------------------------------------------------

    private static boolean drawCpuCulled(TerrainResidency.DrawSnapshot snap, CameraRenderState cam,
            GpuSampler atlasSampler, GpuTextureView colorView,
            GpuTextureView depthView, GpuTextureView atlasView, GpuTextureView lightmapView) {
        // Wave-12: see drawTaskCulled — foreign path, invalidate the memo.
        ccValid = false;
        // ---- CPU draw list: frustum cull + facing-bucket runs (wave 4) ----
        Frustum frustum = cam.cullFrustum;
        int camSx = cam.blockPos.getX() >> 4;
        int camSy = cam.blockPos.getY() >> 4;
        int camSz = cam.blockPos.getZ() >> 4;
        double camX = cam.pos.x;
        double camY = cam.pos.y;
        double camZ = cam.pos.z;

        int[] d = snap.data();
        int n = snap.maxSlot() + 1; // slot-indexed since the incremental snapshot
        int runCountTotal = 0;
        int visibleSections = 0;

        for (int s = 0; s < n; s++) {
            int o = s * TerrainResidency.DrawSnapshot.STRIDE;
            if (d[o + 18] == -2) {
                // Tombstoned slot. This path never read [18] before, but
                // the skip must land BEFORE the AABB + frustum work below
                // or dead slots cost 100x their one-branch price — and a
                // zeroed entry's origin box could even pass the frustum.
                continue;
            }
            int sx = d[o];
            int sy = d[o + 1];
            int sz = d[o + 2];
            double bx = sx << 4;
            double by = sy << 4;
            double bz = sz << 4;
            if (!frustum.isVisible(new AABB(bx, by, bz, bx + 16.0, by + 16.0, bz + 16.0))) {
                continue;
            }
            int relX = sx - camSx;
            int relY = sy - camSy;
            int relZ = sz - camSz;
            int addr = d[o + 3];
            float ox = (float) (bx - camX);
            float oy = (float) (by - camY);
            float oz = (float) (bz - camZ);

            int runFirst = -1;
            int runLen = 0;
            boolean any = false;
            for (int b = 0; b < TerrainResidency.DrawSnapshot.BUCKETS; b++) {
                int count = d[o + 11 + b];
                if (count == 0) {
                    continue; // empty bucket never breaks contiguity
                }
                if (QuadFacing.byIndex(b).visibleFrom(relX, relY, relZ)) {
                    int start = d[o + 4 + b];
                    if (runLen > 0 && runFirst + runLen == start) {
                        runLen += count; // contiguous with the open run
                    } else {
                        if (runLen > 0) {
                            runCountTotal = pushRun(runCountTotal, ox, oy, oz, addr + runFirst, runLen);
                            any = true;
                        }
                        runFirst = start;
                        runLen = count;
                    }
                } else if (runLen > 0) {
                    runCountTotal = pushRun(runCountTotal, ox, oy, oz, addr + runFirst, runLen);
                    any = true;
                    runLen = 0;
                }
            }
            if (runLen > 0) {
                runCountTotal = pushRun(runCountTotal, ox, oy, oz, addr + runFirst, runLen);
                any = true;
            }
            if (any) {
                visibleSections++;
            }
        }

        lastDrawnSections = visibleSections;
        if (runCountTotal == 0) {
            cpuCullFrames++;
            return true; // own the group; nothing in the frustum has quads
        }

        // ---- record the pass ----
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        // Transient uploads happen BEFORE the pass opens (wave-2 note: the
        // allocator may record transfer commands; one pass at a time).
        GpuBufferSlice sceneSlice = uploadScene(encoder, cam,
                atlasView.getWidth(0), atlasView.getHeight(0));

        MesheliumGpuTimers.beginOpaque(encoder, frameSerial); // single pass ⇒
                                                            // "opaqueA" = whole opaque
        try (RenderPass pass = encoder.createRenderPass(() -> "meshelium terrain opaque",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            VulkanRenderPass backendPass = (VulkanRenderPass) ((RenderPassAccessor) pass).meshelium$backend();
            VulkanRenderPassAccessor vkPass = (VulkanRenderPassAccessor) backendPass;
            VkCommandBuffer cb = vkPass.meshelium$commandBuffer();

            TerrainDrawPipeline p = pipelineFor(vkPass, colorView, depthView, false);

            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipeline());
            pushDescriptors(cb, p, snap.arenaBlockHandles(), sceneSlice,
                    atlasView, atlasSampler, lightmapView, 0L, null, 0L, 0L, 0L);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer push = stack.calloc(TerrainDrawPipeline.PUSH_BYTES);
                int wg = p.workgroupQuads();
                for (int r = 0; r < runCountTotal; r++) {
                    push.putFloat(0, runOrigins[r * 3]);
                    push.putFloat(4, runOrigins[r * 3 + 1]);
                    push.putFloat(8, runOrigins[r * 3 + 2]);
                    push.putInt(12, runQuads[r * 2]);
                    push.putInt(16, runQuads[r * 2 + 1]);
                    VK10.vkCmdPushConstants(cb, p.pipelineLayout(),
                            EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT, 0, push);
                    int groups = (runQuads[r * 2 + 1] + wg - 1) / wg;
                    EXTMeshShader.vkCmdDrawMeshTasksEXT(cb, groups, 1, 1);
                }
            }
        }
        MesheliumGpuTimers.mark(encoder, MesheliumGpuTimers.POINT_AFTER_PHASE_A);

        framesDrawn++;
        totalDrawnSections += visibleSections;
        cpuCullFrames++;
        return true;
    }

    private static int pushRun(int runCount, float ox, float oy, float oz, int firstQuad, int quads) {
        if ((runCount + 1) * 3 > runOrigins.length) {
            runOrigins = Arrays.copyOf(runOrigins, runOrigins.length * 2);
            runQuads = Arrays.copyOf(runQuads, runQuads.length * 2);
        }
        runOrigins[runCount * 3] = ox;
        runOrigins[runCount * 3 + 1] = oy;
        runOrigins[runCount * 3 + 2] = oz;
        runQuads[runCount * 2] = firstQuad;
        runQuads[runCount * 2 + 1] = quads;
        return runCount + 1;
    }

    // ------------------------------------------------------------------
    // Wave-7 translucent pass
    // ------------------------------------------------------------------

    /**
     * The TRANSLUCENT kill switch's body — replaces vanilla's
     * {@code renderGroup(TRANSLUCENT, sampler)} at its exact frame point
     * (after features/depth-copies, frame-path Q2.3 item 8). Returns true
     * iff Meshelium owns the translucent group this frame; the mixin only
     * cancels then.
     *
     * <h4>Order authority — vanilla's own sort, both granularities</h4>
     * <ul>
     *   <li><b>Across sections:</b> {@code visibleSections} iterated in
     *       REVERSE — exactly the order vanilla's renderGroup draws: the
     *       list is filled front-to-back by the camera-sorted Octree walk
     *       ({@code SectionOcclusionGraph.addSectionsInFrustum} →
     *       {@code Octree.visitNodes}; {@code Octree$Branch} orders
     *       children by its camera-derived {@code AxisSorting}), and
     *       renderGroup calls {@code List.reversed()} on every TRANSLUCENT
     *       draw list (bytecode ip 262-277). Vanilla's only deviation from
     *       that global order is its draw-group bucketing (an
     *       Int2ObjectOpenHashMap keyed by buffer-identity hash, iterated
     *       in HASH order — reversal applies per bucket); Meshelium has one
     *       buffer, so it renders the single-bucket case exactly — which
     *       is also the order vanilla itself produces until its uber heaps
     *       split (128 MiB per heap).</li>
     *   <li><b>Within a section:</b> the translucent prefix is drawn
     *       front-of-buffer-first — the prefix IS vanilla's own sorted
     *       order (build-time sort captured by wave 3b, resorts applied by
     *       the wave-7 permutation), so intra-section order is vanilla's
     *       byte-for-byte. Draw slices are ≤{@code transQuadCapacity}
     *       quads, one workgroup each, recorded in prefix order — see the
     *       mesh shader header for why multi-workgroup dispatches are NOT
     *       used (the UNVERIFIED EXT inter-workgroup ordering).</li>
     * </ul>
     *
     * <h4>Visibility</h4>
     * Membership in {@code visibleSections} (the same BFS source as the
     * opaque paths) + the CPU section frustum, and — on occlusion frames —
     * the per-section stamp gate evaluated in the MESH stage against THIS
     * frame's raster (identity-verified, every mismatch fails open; the
     * guard argument lives in terrain.mesh). Sections in regions past the
     * occlusion list cap draw ungated, mirroring the opaque fail-open.
     */
    public static boolean drawTranslucent(ChunkSectionsToRender sections, GpuSampler atlasSampler) {
        if (broken) {
            return false;
        }
        try {
            long t0 = System.nanoTime();
            boolean owned = drawTranslucentInner(sections, atlasSampler);
            if (owned) {
                cancelledTranslucentGroups++;
                // Wave-12 stage (d2): the translucent recording span.
                if (MesheliumCpuStages.ARMED) {
                    MesheliumCpuStages.record(MesheliumCpuStages.STAGE_MESHELIUM_TRANSLUCENT,
                            System.nanoTime() - t0);
                }
                // Wave-9: translucent is the frame's last Meshelium pass —
                // close the GPU-timing frame (no-op when timers are off or
                // this frame was never armed).
                MesheliumGpuTimers.endFrame(frameSerial);
            } else if (prepSkippedSerial == frameSerial && opaqueOwnedSerial == frameSerial) {
                // Wave-12: opaque owned a skipped-prep frame but translucent
                // refused — vanilla's TRANSLUCENT renderGroup runs on the
                // empty record this one frame. Same hole class, counted
                // (the opaque-side notePrepOutcome cannot see this case).
                countPrepSkipHole();
            }
            return owned;
        } catch (GpuDeviceLossException t) {
            deviceLost = true;
            broken = true;
            latchError(t);
            MesheliumClient.LOGGER.error(
                    "GPU device lost during Meshelium's translucent pass; Meshelium goes passive "
                            + "(vanilla will report the loss on its own next call)", t);
            if (prepSkippedSerial == frameSerial && opaqueOwnedSerial == frameSerial) {
                countPrepSkipHole();
            }
            return false;
        } catch (Throwable t) {
            broken = true;
            latchError(t);
            MesheliumClient.LOGGER.error(
                    "Meshelium translucent draw failed; vanilla terrain resumes for this session "
                            + "(first and only report)", t);
            if (prepSkippedSerial == frameSerial && opaqueOwnedSerial == frameSerial) {
                countPrepSkipHole();
            }
            return false;
        }
    }

    private static boolean drawTranslucentInner(ChunkSectionsToRender sections, GpuSampler atlasSampler) {
        CameraRenderState cam = camera;
        if (cam == null || !cam.initialized || cam.cullFrustum == null) {
            return false;
        }
        if (opaqueOwnedSerial != frameSerial) {
            return false; // vanilla drew opaque this frame — it draws translucent too
        }
        long probeEntry = TranslucentPhaseProbe.ARMED ? System.nanoTime() : 0L;
        RenderTarget target = ChunkSectionLayerGroup.TRANSLUCENT.outputTarget();
        // Wave-16 harness probe: is this the SEPARATE translucent target
        // (improved transparency, what used to be called fabulous) or the
        // main one? The drawer needs no branch here - outputTarget() hands
        // back whatever vanilla would have drawn into either way - but the
        // harness cannot see the difference from outside. LevelTargetBundle
        // is cleared at the end of every frame, so a test thread reading
        // LevelRenderer.translucentTarget() between frames ALWAYS sees null
        // and can never tell the two paths apart. Counting it here, inside
        // the frame, is the only honest way to prove the fabulous leg
        // actually exercised the fabulous path.
        if (target != Minecraft.getInstance().gameRenderer.mainRenderTarget()) {
            translucentSeparateTargetFrames++;
        }
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        GpuTextureView atlasView = sections.textureView();
        GpuTextureView lightmapView = Minecraft.getInstance().gameRenderer.lightmap();
        if (colorView == null || depthView == null || atlasView == null || lightmapView == null) {
            return false;
        }
        long probeViews = 0L;
        if (TranslucentPhaseProbe.ARMED) {
            probeViews = System.nanoTime();
            TranslucentPhaseProbe.targetViews(probeViews - probeEntry);
        }
        TerrainResidency.DrawSnapshot snap = snapshot; // refreshed by drawOpaque this frame
        if (snap == null || snap.sectionCount() == 0 || snap.arenaBackingHandle() == 0L) {
            // Opaque owned an empty world — own translucent-empty too, so
            // the frame stays wholly Meshelium's (never vanilla water over a
            // Meshelium-empty opaque frame).
            lastTranslucentSections = 0;
            lastTranslucentDraws = 0;
            translucentFrames++;
            return true;
        }

        Frustum frustum = cam.cullFrustum;
        double camX = cam.pos.x;
        double camY = cam.pos.y;
        double camZ = cam.pos.z;
        int[] d = snap.data();
        int cap = transQuadCapacity();
        boolean occGate = occGateSerial == frameSerial && occCurStampsHandle != 0L
                && snap.sectionRecordsHandle() != 0L;
        // One multi-workgroup draw per section instead of one 1-WG draw
        // per ≤cap-quad slice: worth 1.3 ms/frame at rd 64 (translucent
        // recording 1.94 -> 0.63 ms, wave 12), pixel-identical iff the
        // device rasterizes a dispatch's workgroups in order.
        //
        // Default ON everywhere since 1.3.0, and the history matters:
        // waves 9 through 12 believed that ordering was SPEC-UNCLEAR and
        // gated the default to the measured AMD device out of caution.
        // Reading the actual text (2026-08-17) disproved that. The
        // VK_EXT_mesh_shader proposal: "A group of mesh shader workgroups
        // either launched directly by the API, indirectly by the API, or
        // indirectly from a single task shader workgroup will rasterize
        // their outputs in sequential order based on their flattened
        // workgroup index." The spec's Mesh Shading chapter: "All output
        // primitives generated from a given mesh workgroup are passed to
        // subsequent pipeline stages before any output primitives
        // generated from subsequent input workgroups." This pipeline
        // launches its workgroups DIRECTLY (the translucent pipeline has
        // no task stage), the ironclad case. Corroboration that the
        // hardware exists on every target vendor: D3D12 requires the same
        // ordering when no amplification shader is present, so every
        // D3D12-mesh-shader GPU (Turing+, RDNA2+, Arc) implements ordered
        // retirement or could not conform. The property forces either
        // way; a non-conformant driver is the only case that needs it.
        String multiWGProp = System.getProperty(PROPERTY_TRANSLUCENT_MULTI_WG);
        boolean multiWG = multiWGProp == null || Boolean.parseBoolean(multiWGProp);

        if (multiWG && !multiWGLogged) {
            multiWGLogged = true;
            MesheliumClient.LOGGER.info(
                    "Meshelium translucent multi-WG batching active: one draw per section "
                            + "instead of one per 64 quads (worth 1.3 ms/frame at rd 64, "
                            + "measured on RDNA4). The workgroup ordering it relies on is "
                            + "guaranteed by VK_EXT_mesh_shader for directly launched "
                            + "workgroups; -D{}=false forces the split-draw path",
                    PROPERTY_TRANSLUCENT_MULTI_WG);
        }

        int drawCount = 0;
        int sectionsDrawn = 0;
        long gated = 0;

        // ---- wave-11 pre-pass: RETAINED translucent sections ----
        // They are absent from visibleSections by construction (vanilla
        // freed their meshes), so without this pass the horizon would lose
        // its water. Drawn FIRST, farthest-first — retained terrain is
        // (almost always) beyond everything vanilla still lists, so
        // far-before-near holds globally; the exception is a retained
        // in-grid section mid-rebuild-churn, transient by construction and
        // skipped from the visible loop via the drawn marks so a section
        // can never blend twice. Intra-section order is the prefix's LAST
        // sort — vanilla resorts only live meshes, so retained sorts go
        // stale exactly as Nvidium's did (accepted; PERFORMANCE.md note).
        long probeT0 = 0L;
        if (TranslucentPhaseProbe.ARMED) {
            probeT0 = System.nanoTime();
            TranslucentPhaseProbe.prologueRest(probeT0 - probeViews);
        }
        int retainedTransDrawn = 0;
        int snapCount = snap.maxSlot() + 1; // slot-indexed; the filter below skips tombstones
        if (transDrawnMark.length < snapCount) {
            transDrawnMark = new boolean[Math.max(snapCount, transDrawnMark.length * 2)];
        }
        int retTransCount = 0;
        for (int s = 0; s < snapCount; s++) {
            int o = s * TerrainResidency.DrawSnapshot.STRIDE;
            if (d[o + 19] == 0 || d[o + 4] <= 0 || d[o + 18] < 0) {
                continue; // live, no translucent prefix, slotless, or a dead slot
            }
            double bx = d[o] << 4;
            double by = d[o + 1] << 4;
            double bz = d[o + 2] << 4;
            if (!frustum.isVisible(new AABB(bx, by, bz, bx + 16.0, by + 16.0, bz + 16.0))) {
                continue;
            }
            float ddx = (float) (bx + 8.0 - camX);
            float ddy = (float) (by + 8.0 - camY);
            float ddz = (float) (bz + 8.0 - camZ);
            float d2 = ddx * ddx + ddy * ddy + ddz * ddz;
            if (retTransCount == retTransKeys.length) {
                retTransKeys = Arrays.copyOf(retTransKeys, retTransKeys.length * 2);
            }
            retTransKeys[retTransCount++] = ((long) Float.floatToIntBits(d2) << 32) | s;
        }
        Arrays.sort(retTransKeys, 0, retTransCount);
        for (int i = retTransCount - 1; i >= 0; i--) { // descending distance
            int s = (int) retTransKeys[i];
            int o = s * TerrainResidency.DrawSnapshot.STRIDE;
            int sx = d[o];
            int sy = d[o + 1];
            int sz = d[o + 2];
            int count = d[o + 4];
            int addr = d[o + 3];
            int gate = NO_GATE;
            if (occGate) {
                int gidx = d[o + 18]; // retained entries always own their slot
                if (gidx >= 0 && occRasteredRegions.contains(gidx >>> 8)) {
                    int posKey = ((sy & 3) << 6) | ((sz & 7) << 3) | (sx & 7);
                    gate = (posKey << 20) | gidx;
                    gated++;
                }
            }
            float ox = (float) ((double) (sx << 4) - camX);
            float oy = (float) ((double) (sy << 4) - camY);
            float oz = (float) ((double) (sz << 4) - camZ);
            int sliceStep = multiWG ? count : cap;
            for (int first = 0; first < count; first += sliceStep) {
                if ((drawCount + 1) * 3 > transMeta.length) {
                    transOrigins = Arrays.copyOf(transOrigins, transOrigins.length * 2);
                    transMeta = Arrays.copyOf(transMeta, transMeta.length * 2);
                }
                transOrigins[drawCount * 3] = ox;
                transOrigins[drawCount * 3 + 1] = oy;
                transOrigins[drawCount * 3 + 2] = oz;
                transMeta[drawCount * 3] = addr + first;
                transMeta[drawCount * 3 + 1] = Math.min(sliceStep, count - first);
                transMeta[drawCount * 3 + 2] = gate;
                drawCount++;
            }
            transDrawnMark[s] = true;
            transDrawnList.add(s);
            sectionsDrawn++;
            retainedTransDrawn++;
        }

        long probeT1 = 0L;
        if (TranslucentPhaseProbe.ARMED) {
            probeT1 = System.nanoTime();
            TranslucentPhaseProbe.retainedScan(probeT1 - probeT0, snapCount);
        }

        // ---- draw list: vanilla's section order, far → near ----
        ObjectArrayList<SectionRenderDispatcher.RenderSection> visible =
                Minecraft.getInstance().levelRenderer.visibleSections();
        for (int i = visible.size() - 1; i >= 0; i--) {
            long node = visible.get(i).getSectionNode();
            int sx = SectionPos.x(node);
            int sy = SectionPos.y(node);
            int sz = SectionPos.z(node);
            int idx = translucentSlotByPos.get(sectionKey(sx, sy, sz));
            if (idx < 0) {
                continue; // no resident translucent prefix for this section
            }
            if (idx < transDrawnMark.length && transDrawnMark[idx]) {
                continue; // wave-11: already drawn by the retained pre-pass
            }
            int o = idx * TerrainResidency.DrawSnapshot.STRIDE;
            double bx = sx << 4;
            double by = sy << 4;
            double bz = sz << 4;
            if (!frustum.isVisible(new AABB(bx, by, bz, bx + 16.0, by + 16.0, bz + 16.0))) {
                continue;
            }
            int count = d[o + 4]; // translucent prefix quad count
            int addr = d[o + 3];
            int gate = NO_GATE;
            if (occGate) {
                int gidx = d[o + 18]; // regionId*256 + slot (owner-verified)
                if (gidx >= 0 && occRasteredRegions.contains(gidx >>> 8)) {
                    int posKey = ((sy & 3) << 6) | ((sz & 7) << 3) | (sx & 7);
                    gate = (posKey << 20) | gidx;
                    gated++;
                }
            }
            float ox = (float) (bx - camX);
            float oy = (float) (by - camY);
            float oz = (float) (bz - camZ);
            // Slice step: cap quads per 1-WG draw normally; the WHOLE
            // prefix as one multi-WG draw under the ledger-17 experiment
            // (the shader derives each workgroup's ≤cap slice from
            // gl_WorkGroupID; the dispatch below sizes the group count).
            int sliceStep = multiWG ? count : cap;
            for (int first = 0; first < count; first += sliceStep) {
                if ((drawCount + 1) * 3 > transMeta.length) {
                    transOrigins = Arrays.copyOf(transOrigins, transOrigins.length * 2);
                    transMeta = Arrays.copyOf(transMeta, transMeta.length * 2);
                }
                transOrigins[drawCount * 3] = ox;
                transOrigins[drawCount * 3 + 1] = oy;
                transOrigins[drawCount * 3 + 2] = oz;
                transMeta[drawCount * 3] = addr + first;
                transMeta[drawCount * 3 + 1] = Math.min(sliceStep, count - first);
                transMeta[drawCount * 3 + 2] = gate;
                drawCount++;
            }
            sectionsDrawn++;
        }
        // Wave-11: clear the pre-pass marks (O(marked), no full fill).
        for (int i = 0; i < transDrawnList.size(); i++) {
            transDrawnMark[transDrawnList.getInt(i)] = false;
        }
        transDrawnList.clear();

        long probeT2 = 0L;
        if (TranslucentPhaseProbe.ARMED) {
            probeT2 = System.nanoTime();
            TranslucentPhaseProbe.visibleLoop(probeT2 - probeT1, sectionsDrawn, drawCount);
        }

        lastTranslucentSections = sectionsDrawn;
        lastTranslucentDraws = drawCount;
        lastRetainedTranslucentSections = retainedTransDrawn;
        translucentGatedSections += gated;
        if (drawCount == 0) {
            translucentFrames++;
            return true; // own the group; nothing translucent in view
        }

        // ---- transient upload, then the pass ----
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuBufferSlice sceneSlice = uploadScene(encoder, cam,
                atlasView.getWidth(0), atlasView.getHeight(0));

        MesheliumGpuTimers.beginTranslucent(encoder, frameSerial);
        try (RenderPass pass = encoder.createRenderPass(() -> "meshelium terrain translucent",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            VulkanRenderPass backendPass = (VulkanRenderPass) ((RenderPassAccessor) pass).meshelium$backend();
            VulkanRenderPassAccessor vkPass = (VulkanRenderPassAccessor) backendPass;
            VkCommandBuffer cb = vkPass.meshelium$commandBuffer();

            TerrainDrawPipeline p = translucentPipelineFor(vkPass, colorView, depthView);

            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipeline());
            pushTranslucentDescriptors(cb, p, snap.arenaBlockHandles(), sceneSlice,
                    atlasView, atlasSampler, lightmapView,
                    snap.sectionRecordsHandle(), occGate ? occCurStampsHandle : 0L);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer push = stack.calloc(TerrainDrawPipeline.PUSH_BYTES);
                for (int r = 0; r < drawCount; r++) {
                    push.putFloat(0, transOrigins[r * 3]);
                    push.putFloat(4, transOrigins[r * 3 + 1]);
                    push.putFloat(8, transOrigins[r * 3 + 2]);
                    push.putInt(12, transMeta[r * 3]);      // FirstQuad
                    push.putInt(16, transMeta[r * 3 + 1]);  // QuadCount
                    push.putInt(20, transMeta[r * 3 + 2]);  // GateIndex
                    push.putInt(24, occGateStamp32);        // FrameStamp
                    VK10.vkCmdPushConstants(cb, p.pipelineLayout(),
                            EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT, 0, push);
                    // 1 workgroup per ≤cap-quad slice normally (count ≤ cap
                    // ⇒ groups == 1, wave-7 exact); under the ledger-17
                    // experiment one draw carries the whole prefix and
                    // ceil(count/cap) workgroups each emit their own slice.
                    int groups = (transMeta[r * 3 + 1] + cap - 1) / cap;
                    EXTMeshShader.vkCmdDrawMeshTasksEXT(cb, groups, 1, 1);
                }
            }
        }
        MesheliumGpuTimers.endTranslucent(encoder, frameSerial);

        if (multiWG) {
            translucentMultiWGFrames++;
        }
        if (TranslucentPhaseProbe.ARMED) {
            TranslucentPhaseProbe.record(System.nanoTime() - probeT2);
        }
        translucentFrames++;
        return true;
    }

    /**
     * Quads one translucent draw carries: {@link #TRANS_QUADS_DEFAULT}
     * clamped to the REAL device's mesh-output caps. The vertex and
     * primitive spec minimums admit the default (256 / 256 against 64x4 /
     * 64x2), but the OUTPUT MEMORY minimum does not: 64 quads x
     * {@value #MESH_OUTPUT_BYTES_PER_QUAD} B = 36864 B against a guaranteed
     * floor of 32768, which admits 56. That gap arrived with the
     * greedy-merge varyings and was found by review; the clamp makes the
     * shape legal per device instead of hoping every card is generous.
     */
    private static int transQuadCapacity() {
        MesheliumVulkanState.MeshShaderCaps caps = MesheliumVulkanState.caps();
        int cap = TRANS_QUADS_DEFAULT;
        if (caps != null) {
            cap = Math.min(cap, Math.min(caps.maxMeshOutputVertices() / 4,
                    caps.maxMeshOutputPrimitives() / 2));
            cap = Math.min(cap, maxQuadsByOutputMemory());
        }
        return Math.max(cap, 1);
    }

    private static TerrainDrawPipeline translucentPipelineFor(VulkanRenderPassAccessor vkPass,
            GpuTextureView colorView, GpuTextureView depthView) {
        TerrainDrawPipeline p = translucentPipeline;
        if (p == null) {
            int vkColorFormat = VulkanConst.toVk(colorView.texture().getFormat());
            int vkDepthFormat = VulkanConst.toVk(depthView.texture().getFormat());
            int transQuads = transQuadCapacity();
            MesheliumVulkanState.MeshShaderCaps caps = MesheliumVulkanState.caps();
            if (caps != null && (caps.maxMeshWorkGroupInvocations() < meshWorkgroupQuads()
                    || caps.maxMeshOutputVertices() < transQuads * 4
                    || caps.maxMeshOutputPrimitives() < transQuads * 2
                    || (caps.maxMeshOutputMemorySize() > 0
                            && caps.maxMeshOutputMemorySize() < transQuads * MESH_OUTPUT_BYTES_PER_QUAD))) {
                throw new IllegalStateException("device mesh caps below the translucent shape ("
                        + transQuads + " quads/draw, " + transQuads * MESH_OUTPUT_BYTES_PER_QUAD
                        + " B output): " + caps);
            }
            p = TerrainDrawPipeline.createTranslucent(vkPass.meshelium$device().vkDevice(),
                    vkColorFormat, vkDepthFormat, meshWorkgroupQuads(), transQuads);
            translucentPipeline = p;
            MesheliumClient.LOGGER.info(
                    "Meshelium translucent pipeline created (color format {}, depth format {}, "
                            + "{} quads/draw in one workgroup — blend SRC_ALPHA/ONE_MINUS_SRC_ALPHA + "
                            + "ONE/ONE_MINUS_SRC_ALPHA, depth GEQUAL write ON, vanilla "
                            + "TRANSLUCENT_TERRAIN verbatim)",
                    vkColorFormat, vkDepthFormat, transQuads);
        }
        return p;
    }

    /**
     * The translucent variant's 9 push-descriptor writes: bindings 0-6 as
     * the cpu variant, 7 = section records + 8 = CUR stamps (both MESH —
     * the occlusion gate; the arena buffer stands in as a never-read dummy
     * when the gate is off: GateIndex == NO_GATE short-circuits before any
     * dynamic access, same discipline as the wave-6 dummies).
     */
    private static void pushTranslucentDescriptors(VkCommandBuffer cb, TerrainDrawPipeline p,
            long[] arenaBlocks, GpuBufferSlice sceneSlice, GpuTextureView atlasView,
            GpuSampler atlasSampler, GpuTextureView lightmapView,
            long sectionRecordsBuffer, long curStamps) {
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
        GpuSampler lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer arenaInfo = arenaBlockInfos(stack, arenaBlocks,
                    com.deds.meshelium.MesheliumScaling.arenaBlockCount());
            VkDescriptorBufferInfo.Buffer sceneInfo = bufferInfo(stack,
                    ((VulkanGpuBuffer) sceneSlice.buffer()).vkBuffer(), sceneSlice.offset(), sceneSlice.length());
            VkDescriptorBufferInfo.Buffer projectionInfo = bufferInfo(stack,
                    ((VulkanGpuBuffer) projection.buffer()).vkBuffer(), projection.offset(), projection.length());
            VkDescriptorBufferInfo.Buffer fogInfo = bufferInfo(stack,
                    ((VulkanGpuBuffer) fog.buffer()).vkBuffer(), fog.offset(), fog.length());
            VkDescriptorBufferInfo.Buffer globalsInfo = bufferInfo(stack,
                    ((VulkanGpuBuffer) globals).vkBuffer(), 0, VK10.VK_WHOLE_SIZE);
            VkDescriptorImageInfo.Buffer atlasInfo = imageInfo(stack, atlasView, atlasSampler);
            VkDescriptorImageInfo.Buffer lightmapInfo = imageInfo(stack, lightmapView, lightmapSampler);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(9, stack);
            bufferWrite(writes.get(0), 0, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, arenaInfo);
            bufferWrite(writes.get(1), 1, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, sceneInfo);
            bufferWrite(writes.get(2), 2, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, projectionInfo);
            bufferWrite(writes.get(3), 3, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, fogInfo);
            bufferWrite(writes.get(4), 4, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, globalsInfo);
            imageWrite(writes.get(5), 5, atlasInfo);
            imageWrite(writes.get(6), 6, lightmapInfo);
            bufferWrite(writes.get(7), 7, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, sectionRecordsBuffer != 0L ? sectionRecordsBuffer : arenaBlocks[0],
                            0, VK10.VK_WHOLE_SIZE));
            bufferWrite(writes.get(8), 8, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, curStamps != 0L ? curStamps : arenaBlocks[0],
                            0, VK10.VK_WHOLE_SIZE));

            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb,
                    VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipelineLayout(), 0, writes);
        }
    }

    // ------------------------------------------------------------------
    // Uploads + pipeline + descriptors
    // ------------------------------------------------------------------

    /**
     * Meshelium's scene UBO out of vanilla's per-submit transient memory —
     * std140, {@link #SCENE_BYTES} bytes:
     * <pre>
     *   0   mat4 ModelViewMat   (CameraRenderState.viewRotationMatrix)
     *  64   vec4 SceneMisc      (xy = block atlas size in texels)
     *  80   vec4 FrustumPlanes[6]
     * 176   ivec4 CameraChunk   (xyz = camera section coords)
     * 192   vec4 CullMisc       (x/y = plant / sub-pixel cull distance
     *                            squared in blocks, or {@link #CULL_OFF_DIST2}
     *                            when that slider is at 0; zw = viewport
     *                            size in pixels)
     * </pre>
     * The planes are the Gribb-Hartmann rows of the RENDER matrices
     * ProjMat*ModelViewMat, extracted with JOML FrustumIntersection.set's
     * exact formulas (−X=(m03+m00,m13+m10,m23+m20,m33+m30), +X=(m03−m00,…),
     * ±Y with m01, ±Z with m02 — disassembly-verified), left unnormalized
     * because the p-vertex test is scale-invariant (JOML's invsqrt
     * normalization is a positive scale). Note vanilla's own cull frustum
     * feeds a LOOSER projection into the same machinery
     * (Camera.createProjectionMatrixForCulling — wave-5 notes item 6);
     * culling with the render volume instead is pixel-safe by the
     * clip-volume argument in the class javadoc.
     */
    private static GpuBufferSlice uploadScene(CommandEncoder encoder, CameraRenderState cam,
            int atlasWidth, int atlasHeight) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer scene = stack.calloc(SCENE_BYTES);
            cam.viewRotationMatrix.get(scene); // 64 bytes at position 0, position unchanged
            scene.putFloat(64, (float) atlasWidth);
            scene.putFloat(68, (float) atlasHeight);

            Matrix4f m = mvpScratch.set(cam.projectionMatrix).mul(cam.viewRotationMatrix);
            putPlane(scene, 80, m.m03() + m.m00(), m.m13() + m.m10(), m.m23() + m.m20(), m.m33() + m.m30());
            putPlane(scene, 96, m.m03() - m.m00(), m.m13() - m.m10(), m.m23() - m.m20(), m.m33() - m.m30());
            putPlane(scene, 112, m.m03() + m.m01(), m.m13() + m.m11(), m.m23() + m.m21(), m.m33() + m.m31());
            putPlane(scene, 128, m.m03() - m.m01(), m.m13() - m.m11(), m.m23() - m.m21(), m.m33() - m.m31());
            putPlane(scene, 144, m.m03() + m.m02(), m.m13() + m.m12(), m.m23() + m.m22(), m.m33() + m.m32());
            putPlane(scene, 160, m.m03() - m.m02(), m.m13() - m.m12(), m.m23() - m.m22(), m.m33() - m.m32());

            scene.putInt(176, cam.blockPos.getX() >> 4);
            scene.putInt(180, cam.blockPos.getY() >> 4);
            scene.putInt(184, cam.blockPos.getZ() >> 4);

            // The two distance-gated culls, re-read from the config every
            // frame so the Advanced sliders are LIVE (pipelines are created
            // once per session, so a compile-time macro could not serve
            // them). A slider at 0 uploads CULL_OFF_DIST2 and the shader
            // gates keep everything, bit-identical to the ungated code.
            float plantDist = MesheliumConfig.plantCullChunks() * 16.0f;
            float subPixelDist = MesheliumConfig.subPixelCullChunks() * 16.0f;
            scene.putFloat(192, plantDist <= 0.0f ? CULL_OFF_DIST2 : plantDist * plantDist);
            scene.putFloat(196, subPixelDist <= 0.0f ? CULL_OFF_DIST2
                    : subPixelDist * subPixelDist);
            // Viewport for the sub-pixel test: every terrain pass draws
            // into the main render target (the opaque path checks exactly
            // that before owning a frame), and vanilla's pass ctor sets
            // the viewport to the full attachment, so the target's size IS
            // the viewport. RenderTarget.width/height are public ints
            // (javap, 26.2 merged jar).
            RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            scene.putFloat(200, (float) mainTarget.width);
            scene.putFloat(204, (float) mainTarget.height);

            return encoder.transientMemory().uploadGpu(scene, 256, GpuBuffer.USAGE_UNIFORM);
        }
    }

    private static void putPlane(ByteBuffer dst, int offset, float a, float b, float c, float d) {
        dst.putFloat(offset, a);
        dst.putFloat(offset + 4, b);
        dst.putFloat(offset + 8, c);
        dst.putFloat(offset + 12, d);
    }

    /**
     * Wave-10: the drawer keeps ensureFrameLists' verdict per frame; the
     * ring is per-world (created at the pinned extended capacity on the
     * first drawing frame, destroyed with the dispatcher). Returns false —
     * the byte-identical standard path — on standard worlds, before the
     * device facade is up, and after a creation failure (once-only WARN;
     * the standard 512-cap UBO path is the documented fallback and its
     * overflow fails open).
     */
    /**
     * Whether the TASK stage can afford the extended list path's fifth
     * storage buffer.
     *
     * <p>The standard path binds four storage buffers to the task stage,
     * which is exactly {@code maxPerStageDescriptorStorageBuffers}' Vulkan
     * spec minimum with nothing to spare. Past render distance 32 the
     * extended path flips one binding from a uniform buffer to a storage
     * buffer and the count becomes five. Exceeding the limit is a VUID
     * violation, so the failure mode is undefined behaviour rather than a
     * clean error return: no exception to catch, possibly no validation
     * message, just wrong pixels or a hang.</p>
     *
     * <p>The existing clamp does NOT cover this. {@code MesheliumScaling}
     * feeds the same limit into the arena block count, but the arena lives
     * on the MESH stage; no value of N can bring the task stage's own count
     * down, because the arena never appears there.</p>
     *
     * <p>Almost certainly theoretical. The dev card reports 0xFFFFFFFF, and
     * LUNARG's desktop_baseline profile, which is generated as the
     * intersection of real gpuinfo.org device reports, requires 31 in every
     * one of its 2022 through 2026 blocks; 4 appears only in the synthetic
     * minimum-requirements profile. Every mesh-shader-capable part is inside
     * that population. So this guards rather than merges: folding the two
     * stamp buffers into one would take the task stage to four, but those
     * stamps ARE the wave-6 parity guard and a base-index slip reintroduces
     * the cross-stage race that design fought. Not a risk worth taking for a
     * device nobody has ever seen.</p>
     *
     * <p>Zero still means NOT REPORTED, matching the convention at
     * {@code MesheliumScaling.arenaBlockCount}: a driver that leaves a
     * chained struct zeroed must not be punished for it.</p>
     */
    private static boolean taskStageHasRoom() {
        long limit = com.deds.meshelium.MesheliumVulkanState.arenaLimits()
                .maxPerStageDescriptorStorageBuffers();
        if (limit <= 0 || limit >= EXTENDED_TASK_STAGE_STORAGE) {
            return true;
        }
        if (!taskStageWarned) {
            taskStageWarned = true;
            MesheliumClient.LOGGER.warn(
                    "Meshelium: this device reports {} per-stage storage buffers and the extended "
                            + "render distance path needs {}, so the standard per-frame list path "
                            + "is used instead. Culling degrades at long render distance; "
                            + "rendering does not change", limit, EXTENDED_TASK_STAGE_STORAGE);
        }
        return false;
    }

    /** Storage buffers the task stage binds on the extended list path. */
    private static final int EXTENDED_TASK_STAGE_STORAGE = 5;

    private static boolean taskStageWarned;

    private static boolean ensureFrameLists() {
        if (!com.deds.meshelium.MesheliumScaling.current().extended() || frameListsFailed) {
            return false;
        }
        if (!taskStageHasRoom()) {
            return false;
        }
        if (frameLists != null) {
            return true;
        }
        try {
            frameLists = MesheliumFrameLists.create(
                    com.deds.meshelium.MesheliumScaling.current().dispatchCapacity());
        } catch (Throwable t) {
            frameListsFailed = true;
            MesheliumClient.LOGGER.warn(
                    "Meshelium extended frame lists failed to create — falling back to the "
                            + "standard 512-region per-frame lists (overflow fails open; "
                            + "culling degrades, parity never)", t);
            return false;
        }
        return frameLists != null;
    }

    private static TerrainDrawPipeline pipelineFor(VulkanRenderPassAccessor vkPass,
            GpuTextureView colorView, GpuTextureView depthView, boolean taskCull) {
        return pipelineFor(vkPass, colorView, depthView, taskCull, false);
    }

    private static TerrainDrawPipeline pipelineFor(VulkanRenderPassAccessor vkPass,
            GpuTextureView colorView, GpuTextureView depthView, boolean taskCull,
            boolean extLists) {
        TerrainDrawPipeline p = taskCull ? (extLists ? taskPipelineExt : taskPipeline) : cpuPipeline;
        if (p == null) {
            int vkColorFormat = VulkanConst.toVk(colorView.texture().getFormat());
            int vkDepthFormat = VulkanConst.toVk(depthView.texture().getFormat());
            int wg = meshWorkgroupQuads();          // wave-9 knob (default 32)
            int tws = taskWorkgroupSections();      // wave-9 knob (default 32)
            MesheliumVulkanState.MeshShaderCaps caps = MesheliumVulkanState.caps();
            if (caps != null) {
                if (caps.maxMeshWorkGroupInvocations() < wg
                        || caps.maxMeshOutputVertices() < wg * 4
                        || caps.maxMeshOutputPrimitives() < wg * 2
                        || (caps.maxMeshOutputMemorySize() > 0
                                && caps.maxMeshOutputMemorySize() < wg * MESH_OUTPUT_BYTES_PER_QUAD)) {
                    throw new IllegalStateException("device mesh caps below the terrain workgroup shape ("
                            + wg + " quads, " + wg * MESH_OUTPUT_BYTES_PER_QUAD
                            + " B output): " + caps);
                }
                if (taskCull) {
                    // Wave-9: the payload-budget assert, extended to the
                    // sweepable shape — BOTH the invocation cap and the
                    // payload byte budget must admit the knob's value on
                    // the REAL device (a 256-section shape fails here by
                    // payload: 256 × 80 = 20480 > spec-min 16384, which is
                    // why TASK_WG_MAX caps the sweep at 128).
                    int payloadBytes = tws * TerrainDrawPipeline.PAYLOAD_BYTES_PER_SECTION;
                    if (caps.maxTaskWorkGroupInvocations() < tws
                            || caps.maxTaskPayloadSize() < payloadBytes) {
                        throw new IllegalStateException("device task caps below the terrain task shape ("
                                + tws + " sections, " + payloadBytes + " B payload — check the "
                                + PROPERTY_TASK_WG + " sweep value): " + caps);
                    }
                    MesheliumClient.LOGGER.info(
                            "Meshelium terrain task stage: {} sections/workgroup, ≤{} B payload vs device caps "
                                    + "maxTaskInvocations={}, maxTaskPayloadSize={}, preferredTaskInvocations={} "
                                    + "(MESHELIUM_TASK_WG_SIZE via " + PROPERTY_TASK_WG + ")",
                            tws, payloadBytes,
                            caps.maxTaskWorkGroupInvocations(), caps.maxTaskPayloadSize(),
                            caps.maxPreferredTaskWorkGroupInvocations());
                }
                MesheliumClient.LOGGER.info(
                        "Meshelium terrain pipeline ({}): workgroup {} quads = {} verts/{} prims vs device caps "
                                + "maxInvocations={}, maxOutVerts={}, maxOutPrims={}, preferredInvocations={} "
                                + "(MESHELIUM_WG_SIZE via " + PROPERTY_MESH_WG + ")",
                        taskCull ? "task-cull" : "cpu-cull", wg, wg * 4, wg * 2,
                        caps.maxMeshWorkGroupInvocations(), caps.maxMeshOutputVertices(),
                        caps.maxMeshOutputPrimitives(), caps.maxPreferredMeshWorkGroupInvocations());
                MesheliumClient.LOGGER.info(
                        "Meshelium mesh-output budget: {} B/workgroup of maxMeshOutputMemorySize={} "
                                + "({} locations/vertex, {} components of maxMeshOutputComponents={}) "
                                + "- the translucent shape is clamped by the same budget",
                        wg * MESH_OUTPUT_BYTES_PER_QUAD, caps.maxMeshOutputMemorySize(),
                        MESH_OUTPUT_LOCATIONS, MESH_OUTPUT_LOCATIONS * 4,
                        caps.maxMeshOutputComponents());
            }
            p = TerrainDrawPipeline.create(vkPass.meshelium$device().vkDevice(),
                    vkColorFormat, vkDepthFormat, wg, tws, MAX_MASK_REGIONS, taskCull, extLists);
            if (taskCull) {
                if (extLists) {
                    taskPipelineExt = p;
                } else {
                    taskPipeline = p;
                }
            } else {
                cpuPipeline = p;
            }
            MesheliumClient.LOGGER.info(
                    "Meshelium terrain pipeline created (color format {}, depth format {}, {} quads/workgroup, "
                            + "taskCull={}, extendedLists={})",
                    vkColorFormat, vkDepthFormat, wg, taskCull, extLists);
        }
        return p;
    }

    /**
     * One {@code vkCmdPushDescriptorSetKHR} per pass — 7 bindings (cpu
     * variant, wave-4 exact) or 12 (task variant: + section records SSBO +
     * visibility-mask UBO + the wave-6 stamp/stats SSBOs). Push
     * descriptors, like every vanilla draw: no pools, no sets, nothing to
     * free.
     *
     * <p>Wave-6 dummies: bindings 9-11 are statically referenced by
     * terrain.task, so a VALID buffer must sit there even on frames whose
     * VisMode/StatsFlags guards never touch them — pass 0 for
     * {@code prevStamps}/{@code curStamps}/{@code statsBuf} and the
     * section-records buffer is substituted (read-only-safe: the guarded
     * code never executes). Binding 8 (the mask list) likewise takes
     * whatever type-correct slice the frame has when masks are off —
     * since wave 10 it arrives as a {@link TerrainOcclusion.ListSlice}
     * whose {@code ssbo} flag must match the pipeline variant
     * ({@code p.extendedLists()}); the caller passes the frame's occ-list
     * slice as the dummy on occlusion frames, which is type-correct in
     * both variants by construction.</p>
     */
    private static void pushDescriptors(VkCommandBuffer cb, TerrainDrawPipeline p, long[] arenaBlocks,
            GpuBufferSlice sceneSlice, GpuTextureView atlasView, GpuSampler atlasSampler,
            GpuTextureView lightmapView, long sectionRecordsBuffer, TerrainOcclusion.ListSlice visList,
            long prevStamps, long curStamps, long statsBuf) {
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
        GpuSampler lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        boolean taskMode = p.taskCull();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer arenaInfo = arenaBlockInfos(stack, arenaBlocks,
                    com.deds.meshelium.MesheliumScaling.arenaBlockCount());
            VkDescriptorBufferInfo.Buffer sceneInfo = bufferInfo(stack,
                    ((VulkanGpuBuffer) sceneSlice.buffer()).vkBuffer(), sceneSlice.offset(), sceneSlice.length());
            VkDescriptorBufferInfo.Buffer projectionInfo = bufferInfo(stack,
                    ((VulkanGpuBuffer) projection.buffer()).vkBuffer(), projection.offset(), projection.length());
            VkDescriptorBufferInfo.Buffer fogInfo = bufferInfo(stack,
                    ((VulkanGpuBuffer) fog.buffer()).vkBuffer(), fog.offset(), fog.length());
            VkDescriptorBufferInfo.Buffer globalsInfo = bufferInfo(stack,
                    ((VulkanGpuBuffer) globals).vkBuffer(), 0, VK10.VK_WHOLE_SIZE);
            VkDescriptorImageInfo.Buffer atlasInfo = imageInfo(stack, atlasView, atlasSampler);
            VkDescriptorImageInfo.Buffer lightmapInfo = imageInfo(stack, lightmapView, lightmapSampler);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(taskMode ? 12 : 7, stack);
            bufferWrite(writes.get(0), 0, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, arenaInfo);
            bufferWrite(writes.get(1), 1, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, sceneInfo);
            bufferWrite(writes.get(2), 2, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, projectionInfo);
            bufferWrite(writes.get(3), 3, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, fogInfo);
            bufferWrite(writes.get(4), 4, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, globalsInfo);
            imageWrite(writes.get(5), 5, atlasInfo);
            imageWrite(writes.get(6), 6, lightmapInfo);
            if (taskMode) {
                VkDescriptorBufferInfo.Buffer recordsInfo =
                        bufferInfo(stack, sectionRecordsBuffer, 0, VK10.VK_WHOLE_SIZE);
                // Binding 8: the mask list (bfs mode) or a type-correct
                // dummy (occlusion mode never reads it). The descriptor
                // TYPE must match the pipeline variant's layout — UBO on
                // the standard pipeline, SSBO on the wave-10 extended one;
                // a null visList falls back to a variant-appropriate dummy
                // (scene slice / section records — both always valid).
                TerrainOcclusion.ListSlice list8 = visList;
                if (list8 == null) {
                    list8 = p.extendedLists()
                            ? new TerrainOcclusion.ListSlice(sectionRecordsBuffer, 0,
                                    VK10.VK_WHOLE_SIZE, true)
                            : TerrainOcclusion.ListSlice.ofUniformSlice(sceneSlice);
                }
                VkDescriptorBufferInfo.Buffer visInfo =
                        bufferInfo(stack, list8.vkBuffer(), list8.offset(), list8.range());
                bufferWrite(writes.get(7), 7, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, recordsInfo);
                bufferWrite(writes.get(8), 8, list8.ssbo()
                        ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                        : VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, visInfo);
                // Bindings 9-11: real occlusion buffers, or the section
                // records as never-dynamically-accessed dummies.
                bufferWrite(writes.get(9), 9, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                        bufferInfo(stack, prevStamps != 0L ? prevStamps : sectionRecordsBuffer,
                                0, VK10.VK_WHOLE_SIZE));
                bufferWrite(writes.get(10), 10, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                        bufferInfo(stack, curStamps != 0L ? curStamps : sectionRecordsBuffer,
                                0, VK10.VK_WHOLE_SIZE));
                bufferWrite(writes.get(11), 11, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                        bufferInfo(stack, statsBuf != 0L ? statsBuf : sectionRecordsBuffer,
                                0, VK10.VK_WHOLE_SIZE));
            }

            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb,
                    VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipelineLayout(), 0, writes);
        }
    }

    private static VkDescriptorBufferInfo.Buffer bufferInfo(MemoryStack stack, long vkBuffer,
            long offset, long range) {
        VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
        info.get(0).buffer(vkBuffer).offset(offset).range(range);
        return info;
    }

    private static VkDescriptorImageInfo.Buffer imageInfo(MemoryStack stack, GpuTextureView view,
            GpuSampler sampler) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0)
                .sampler(((VulkanGpuSampler) sampler).vkSampler())
                .imageView(((VulkanGpuTextureView) view).vkImageView())
                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL); // everything lives in GENERAL, Q1.4
        return info;
    }

    private static void bufferWrite(VkWriteDescriptorSet write, int binding, int type,
            VkDescriptorBufferInfo.Buffer info) {
        write.sType$Default()
                .dstBinding(binding)
                .descriptorCount(info.remaining())
                .descriptorType(type)
                .pBufferInfo(info);
    }

    /**
     * One VkDescriptorBufferInfo per arena block, for the binding-0
     * descriptor array.
     *
     * <p>EVERY element must be a valid descriptor, not just the committed
     * ones: the shader was compiled with an arm for all N, the layout
     * declares N, and a push descriptor write must fill the array it
     * declares. Slots for blocks that do not exist yet are filled with block
     * 0's handle.</p>
     *
     * <p>Block 0 rather than a small zero-filled sentinel, and the reason is
     * robustBufferAccess. It is NOT among vanilla's required device features
     * (javap-verified), so an out-of-bounds read is not guaranteed to return
     * zero, it is undefined. A tiny sentinel bound at VK_WHOLE_SIZE would
     * turn a host bug into undefined behaviour, while a full-sized valid
     * buffer turns the same bug into a wrong-but-defined read. Those arms
     * are dead code while the host keeps its invariant that no address names
     * an uncommitted block, and the shader's own default arm returns zero
     * for anything past the last one.</p>
     */
    private static VkDescriptorBufferInfo.Buffer arenaBlockInfos(MemoryStack stack,
            long[] handles, int declaredBlocks) {
        VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(declaredBlocks, stack);
        long fallback = handles.length > 0 ? handles[0] : 0L;
        for (int i = 0; i < declaredBlocks; i++) {
            long h = i < handles.length && handles[i] != 0L ? handles[i] : fallback;
            info.get(i).buffer(h).offset(0).range(VK10.VK_WHOLE_SIZE);
        }
        return info;
    }

    private static void imageWrite(VkWriteDescriptorSet write, int binding,
            VkDescriptorImageInfo.Buffer info) {
        write.sType$Default()
                .dstBinding(binding)
                .descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .pImageInfo(info);
    }

    // ------------------------------------------------------------------
    // Perf breadcrumb (wave-5 deliverable 4)
    // ------------------------------------------------------------------

    /**
     * CPU-side draw-path time only (mask build + region cull + recording).
     * Honest GPU frame timing waits for wave 9's timestamp queries — a CPU
     * clock around command RECORDING says nothing about GPU execution.
     * Logged once per 5 s: DEBUG normally, INFO when -Dmeshelium.debugStats
     * is set (the residency line's convention).
     */
    private static void recordPerf(long nanos) {
        perfNanosAccum += nanos;
        perfNanosMax = Math.max(perfNanosMax, nanos);
        perfFrames++;
        long now = System.nanoTime();
        if (lastPerfLogNanos == 0) {
            lastPerfLogNanos = now;
            return;
        }
        if (now - lastPerfLogNanos < 5_000_000_000L) {
            return;
        }
        String line = String.format(
                "meshelium draw-path: avg %dus max %dus over %d frames; last frame regions=%d "
                        + "sectionsVisibleIn=%d taskGroups~%d maskOverflows=%d retainedMask=%d "
                        + "retainedTrans=%d cachedCull=%d/%d prepSkipped=%d/%d "
                        + "(CPU-side recording micros ONLY — the wave-9 GPU pass "
                        + "times are MesheliumGpuTimers' separate line; never sum the two; "
                        + "cachedCull=hits/misses is the wave-12 REAL-PLAY hit-rate evidence, "
                        + "prepSkipped=skips/holes must show holes 0)",
                perfFrames == 0 ? 0 : perfNanosAccum / perfFrames / 1_000,
                perfNanosMax / 1_000, perfFrames,
                regionsDispatched, sectionsVisibleIn,
                perfFrames == 0 ? 0 : perfTaskGroupsAccum / perfFrames,
                maskOverflowRegions, lastRetainedMaskSections, lastRetainedTranslucentSections,
                cachedCullHits, cachedCullMisses, prepSkippedFrames, prepSkipHoleFrames);
        if (MesheliumConfig.debugStatsEnabled()) {
            MesheliumClient.LOGGER.info(line);
        } else {
            MesheliumClient.LOGGER.debug(line);
        }
        perfNanosAccum = 0;
        perfNanosMax = 0;
        perfFrames = 0;
        perfTaskGroupsAccum = 0;
        lastPerfLogNanos = now;
    }
}
