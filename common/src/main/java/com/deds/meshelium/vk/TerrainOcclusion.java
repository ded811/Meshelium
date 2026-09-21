/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Wave-6 GPU occlusion culling — Nvidium's two-level box-raster
 * architecture (by MCRcortex, LGPL-3.0) in cross-vendor form:
 *   misc/reference/nvidium/.../shaders/occlusion/{region_raster,section_raster}
 *   misc/reference/nvidium/src/main/java/me/cortex/nvidium/RenderPipeline.java
 *     :336-402 (the phase order: prime depth → region boxes → section
 *     boxes → temporal catch-up)
 * with representative-fragment-test DROPPED per the study's verdict
 * (NVIDIUM-ARCHITECTURE.md §10 row 6: NV-only even in Vulkan; stores are
 * idempotent so correctness is unaffected) and the GPU-written indirect
 * command buffers replaced by same-frame stamp consumption (Meshelium's
 * wave-5 dispatch is CPU-recorded per region, so no command format
 * redesign is needed — §10 row 3 is sidestepped, not solved).
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumVulkanState;
import com.deds.meshelium.mixin.GpuDeviceAccessor;
import com.deds.meshelium.terrain.host.TerrainResidency;

import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;

import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTConditionalRendering;
import org.lwjgl.vulkan.EXTConservativeRasterization;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationConservativeStateCreateInfoEXT;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Owns the wave-6 occlusion GPU state — per-world (created lazily on the
 * first occlusion frame, destroyed with the dispatcher via
 * {@link MesheliumTerrainPump}) — and records the two box-raster draws into
 * passes the {@link TerrainDrawer} opens.
 *
 * <h2>Visibility representation — frame stamps, not the shifting byte</h2>
 * Nvidium keeps one byte per section, shifted left each frame with bit 0
 * injected by the raster (study §5). Only bits 0 and 1 are ever consumed
 * (terrain task bit 0; temporal task {@code (vis&3)==1}); the deeper
 * history is unused surplus. The byte's write protocol is a cross-stage
 * same-address race (mesh pre-writes {@code hist|cameraIn}, fragment
 * overwrites {@code hist|1}) that only NV hardware is known to tolerate.
 * Meshelium stores one {@code uint} STAMP per global section slot in two
 * ping-pong buffers selected by frame parity:
 * <pre>
 *   curStamps[gidx]  == FrameStamp     ⇔  Nvidium bit 0 (marked this frame)
 *   prevStamps[gidx] == FrameStamp - 1 ⇔  Nvidium bit 1 (marked last frame)
 * </pre>
 * Every writer of a frame writes the IDENTICAL value via atomicExchange —
 * defined on every conformant device, order-irrelevant. Freshness is
 * equality against the current frame, so <b>no reset pass ever runs</b>:
 * <ul>
 *   <li><b>Section slot reuse / region id reuse:</b> a stale stamp can at
 *       worst equal {@code FrameStamp-1} for one frame, which makes phase
 *       A draw whatever section CURRENTLY occupies the slot — a real,
 *       current record with fence-protected geometry (one wasted
 *       depth-tested draw, zero wrong pixels). It can never SUPPRESS a
 *       draw: phase B keys on this frame's raster mark alone.</li>
 *   <li><b>World change / dispatcher swap:</b> buffers are destroyed and
 *       recreated zero-filled here; the stamp counter keeps counting
 *       monotonically across worlds, so even a surviving value could
 *       never read as fresh.</li>
 *   <li><b>F3+A-class invalidation:</b> if it disposes the dispatcher the
 *       previous point applies; if it only rebuilds sections, records are
 *       rewritten in place and stale stamps again only over-draw current
 *       records for a frame.</li>
 * </ul>
 * Nvidium's per-region 256-byte clears on frustum exit (RenderPipeline
 * .java:215-221, the known-bug fix the study flags) exist to stop stale
 * "visible" bits from replaying freed/moved geometry through GPU-written
 * commands; stamps make that class of artifact structurally impossible,
 * so the clears have no equivalent here.
 *
 * <h2>Buffers</h2>
 * <pre>
 * sectionStampsA/B  maxRegions × 256 × 4 B (2 MiB each @ 2048 regions;
 *                   maxRegions scales with wave-10 extended RD),
 *                   DEVICE_LOCAL, indexed regionId*256 + compactedSlot
 * regionStamps      {@link #listCapacity()} × 4 B (512 standard; the
 *                   pinned dispatchCapacity on extended worlds), indexed
 *                   by DISPATCH SLOT (per-frame list position — Nvidium's
 *                   frustum-list-slot indexing, study §5)
 * stats             4 × u32, atomicAdd'd by terrain.task ([0] mask mode,
 *                   [1] phase A, [2] phase B), copied to…
 * statsRing         {@link #STATS_RING} × 16 B host-visible readback ring
 *                   — the download-stream consumer wave 3b deferred to
 *                   this wave; read {@link #READBACK_LAG} frames later
 *                   (the FREE_FRAME_LAG fence argument, same constant)
 * </pre>
 *
 * <h2>Pass/barrier story (frame-path Q1.3)</h2>
 * The drawer records four passes; every vanilla pass-end emits a full
 * ALL_COMMANDS MEMORY_READ|MEMORY_WRITE barrier, which is exactly the
 * dependency chain the phases need — <b>no Meshelium barrier exists inside
 * or between the passes</b>:
 * <pre>
 * pass 1  phase A terrain (VisMode 1)      — primes depth
 *         └ barrier: A depth → box depth tests, A stats → transfer
 * pass 2  region boxes (this class)        — writes regionStamps
 *         └ barrier: regionStamps → section-raster task reads
 * pass 3  section boxes (this class)       — writes curStamps
 *         └ barrier: curStamps → phase B task reads
 * pass 4  phase B terrain (VisMode 2)      — the latency hider
 *         └ barrier: depth/color complete for vanilla's feature passes
 * + one transient CB: copy stats→ring slot, barrier, zero stats, barrier
 *   (the MesheliumTerrainGpu transfer-CB convention — transfer work is the
 *   one place Meshelium issues its own barriers)
 * </pre>
 * Cost over wave 5: +3 render-pass begin/end pairs (+3 inherited full
 * barriers) + 1 transient transfer CB (2 barriers) per occlusion frame.
 *
 * <h2>The Sodium host (D-023 stage 3)</h2>
 * The same two rasters run on Sodium's terrain: MEASUREMENTS.md 0n found
 * the one scene where the box rasters pay on that host (under the canopy
 * they cut the draw by 0.256 ms for a 0.103 ms tax; 0k/0m found nothing
 * aerially or at the shore, which is why the path is a lever). What
 * differs is only the DATA the boxes come from, so the class is
 * generalised rather than copied: {@link #create(int, int, boolean)} sizes
 * the stamps by the record mirror's id capacity instead of the residency's
 * region budget, keeps NO stats buffer (the task stage's counters live in
 * the mirror header and {@link SodiumMirrorGpu#recordStatsTransfer} reads
 * them back) and never arms the phase-B predicate (RDNA4 stalls, above);
 * {@link #ensureSodiumPipelines} compiles the raster shaders with
 * {@code MESHELIUM_SODIUM=1} (64-byte list entries, the mirror's row slice
 * at the section raster's binding 5, full 16-cubed boxes because Sodium
 * has no per-section bounds); and the two {@code record*Sodium} overloads
 * take Sodium's projection slice so box depth and terrain depth come from
 * the identical transform. Every standalone call site keeps its exact
 * arguments and defaults; {@code create()} delegates with the wave-10
 * values, and {@code MESHELIUM_SODIUM=0} on the standard raster compiles
 * is byte-identical SPIR-V (tools/shader_parity.sh).
 *
 * <p><b>Why the class is public (NEXT (c), 2026-09-15):</b> the half-res
 * lever's property, its runtime setters and its counters are read and
 * flipped by the gametest suite, which lives in another package. Only those
 * members are public; everything the drawers use stays package-private, so
 * nothing outside {@code com.deds.meshelium.vk} can reach the buffers, the
 * pipelines or the recording methods.</p>
 */
public final class TerrainOcclusion {

    /**
     * STANDARD occlusion region-list capacity per frame: 512 × 32 B =
     * 16 KiB = the spec-minimum {@code maxUniformBufferRange} (the wave-5
     * mask-UBO precedent). Regions dispatched beyond it fail OPEN: phase A
     * draws them maskless (VisMode 0 + no-mask sentinel), no boxes are
     * rastered for them, phase B skips them — more work, never fewer
     * pixels. <b>Wave 10:</b> in extended-render-distance worlds the list
     * capacity is the pinned {@code MesheliumScaling.dispatchCapacity()}
     * (== the whole region budget, so the overflow path becomes
     * structurally unreachable) and the list travels as an SSBO slice of
     * {@code MesheliumFrameLists} — {@link #listCapacity()} is the value
     * every consumer reads; this constant is the standard-mode floor.
     */
    static final int MAX_OCC_REGIONS = 512;

    /** The pinned per-frame list capacity (512 standard; scaled extended). */
    static int listCapacity() {
        return Math.max(MAX_OCC_REGIONS,
                com.deds.meshelium.MesheliumScaling.current().dispatchCapacity());
    }

    /** Bytes per occlusion region-list entry: vec4 origin + uvec4 meta. */
    static final int OCC_ENTRY_BYTES = 32;

    /** Bytes of the per-frame occlusion region list. */
    static final int OCC_LIST_BYTES = MAX_OCC_REGIONS * OCC_ENTRY_BYTES;

    /** Stats readback lag in stats frames — the FREE_FRAME_LAG argument. */
    static final int READBACK_LAG = TerrainResidency.FREE_FRAME_LAG;

    /** Host ring slots; must exceed {@link #READBACK_LAG}. */
    static final int STATS_RING = 8;

    /** Bytes of the GPU stats buffer / one ring slot: 4 × u32. */
    static final int STATS_BYTES = 16;

    // Static pipeline cache — device-lifetime like TerrainDrawPipeline's
    // (destroy debt DISCHARGED in wave 8: destroyPipelines() runs at
    // device close via TerrainDrawer.destroyDeviceObjects); the buffers
    // below are per-world. Wave-10 adds the extended-lists variants
    // (binding 0 = SSBO, MESHELIUM_LISTS_SSBO=1, unsized arrays) — both
    // variants may coexist across worlds of one session, both die at
    // device close.
    private static long regionSetLayout;
    private static long regionPipelineLayout;
    private static long regionPipeline;
    private static long sectionSetLayout;
    private static long sectionPipelineLayout;
    private static long sectionPipeline;
    private static long regionSetLayoutExt;
    private static long regionPipelineLayoutExt;
    private static long regionPipelineExt;
    private static long sectionSetLayoutExt;
    private static long sectionPipelineLayoutExt;
    private static long sectionPipelineExt;
    // D-023 stage 3: the Sodium-host variants (MESHELIUM_SODIUM=1: 64-B
    // list entries, the mirror's row slice at binding 5, no predicate).
    // Same device-lifetime cache discipline as the two pairs above; may
    // never be built in a session, die at device close either way.
    private static long regionSetLayoutSodium;
    private static long regionPipelineLayoutSodium;
    private static long regionPipelineSodium;
    private static long sectionSetLayoutSodium;
    private static long sectionPipelineLayoutSodium;
    private static long sectionPipelineSodium;

    // ---- NEXT (c) 2026-09-15: the half-resolution occlusion depth ----
    // TWELVE more pipeline handles, six per arm, built LAZILY on the first
    // half-res frame of that arm and destroyed with the rest at device
    // close. They SHARE the full-res sets' descriptor-set and pipeline
    // layouts (same bindings, same 16-byte push range), which is why each
    // ensure* below begins with its full-res ensure: without that ordering
    // a session booted straight into half-res would build them with
    // pipelineLayout == 0L.
    //
    // FLAT is THE arm - the only one whose superset holds by construction -
    // and it is built on BOTH hosts in this change. The bias set is a
    // MEASUREMENT-ONLY COMPARATOR, sound only where z + o <= 1 (Vulkan
    // leaves z_f undefined outside the depth range without depth clamping),
    // and is built only in a session that asks for it by property.
    private static long regionPipelineFlat;
    private static long sectionPipelineFlat;
    private static long regionPipelineExtFlat;
    private static long sectionPipelineExtFlat;
    private static long regionPipelineSodiumFlat;
    private static long sectionPipelineSodiumFlat;
    private static long regionPipelineHalf;
    private static long sectionPipelineHalf;
    private static long regionPipelineExtHalf;
    private static long sectionPipelineExtHalf;
    private static long regionPipelineSodiumHalf;
    private static long sectionPipelineSodiumHalf;

    // Pass D's own pipeline. It shares NOTHING with the box sets (its own
    // set layout, its own pipeline layout), so it has no ordering
    // dependence on ensurePipelines.
    private static long downsampleSetLayout;
    private static long downsamplePipelineLayout;
    private static long downsamplePipeline;

    /**
     * A per-frame list binding: the transient UBO slice (standard) or the
     * {@code MesheliumFrameLists} ring slot (extended, {@code ssbo}=true).
     * Wave-10 seam between {@code TerrainDrawer} and the raster passes —
     * pipeline variant and descriptor type both key off {@code ssbo}.
     */
    record ListSlice(long vkBuffer, long offset, long range, boolean ssbo) {

        static ListSlice ofUniformSlice(GpuBufferSlice slice) {
            return new ListSlice(((VulkanGpuBuffer) slice.buffer()).vkBuffer(),
                    slice.offset(), slice.length(), false);
        }
    }

    private final VulkanDevice device;
    private final VulkanCommandEncoder encoder;
    private final long vma;
    /** Region-stamp slots (== the pinned per-frame list capacity). */
    private final int regionStampSlots;
    /**
     * D-023: true for an instance created for the Sodium host — stamps
     * sized by the record mirror's id capacity, no stats buffer (null
     * below), no predicate; the {@code record*Sodium} overloads are the
     * only recording entry points that may be used on it.
     */
    private final boolean sodium;
    private final MesheliumVkBuffers.DeviceBuffer sectionStampsA;
    private final MesheliumVkBuffers.DeviceBuffer sectionStampsB;
    private final MesheliumVkBuffers.DeviceBuffer regionStamps;
    /** Null on a Sodium-host instance: its counters live in the mirror header. */
    private final MesheliumVkBuffers.DeviceBuffer stats;
    /** Null on a Sodium-host instance (see {@link #stats}). */
    private final MesheliumVkBuffers.MappedBuffer statsRing;
    /**
     * The phase-B predicate: 4 bytes the section raster sets to 1 when any
     * section transitions to newly-visible, consumed by conditional
     * rendering around the phase-B dispatches, zeroed by the stats CB after
     * phase B each frame. Null when the predicate path is off (extension
     * absent or -Dmeshelium.occlusion.phaseBPredicate=false), in which case
     * phase B records directly, exactly the pre-predicate behavior.
     */
    private final MesheliumVkBuffers.DeviceBuffer predicate;

    /**
     * NEXT (c): section-stamp rows, i.e. the {@code maxRegions} this
     * instance was created with. The stamp index space is
     * {@code row * 256 + slot}, so this bounds both the test-only fold and
     * the word index of the {@code forcedNear} counter pair.
     */
    private final int stampRows;

    /**
     * NEXT (c): the half-resolution attachment pair, created lazily on the
     * first half-res frame and dying with this instance (which is what
     * makes 97_13's "allocations move with recreates" assertion true).
     * Null until then, and on every session that never arms the lever.
     */
    private OcclusionHalfResTarget halfTarget;

    // ---- the test-only stamps readback (meshelium.occlusion.diag.stampsReadback) ----
    // Null on every shipped path. See recordStampsTransfer / foldVisibleSet.
    private final MesheliumVkBuffers.MappedBuffer stampsRing;
    private final long copyBytes;
    private final long slotBytes;
    private final int copyRows;
    private final int[] ringStamp;
    private final long[] ringFrame;
    private final int[] ringForcedRegionWide;
    private final int[] ringForcedRegionPlain;
    private final int[] ringForcedSectionWide;
    private final int[] ringForcedSectionPlain;
    private volatile VisibleSetSample lastVisibleSet;
    private boolean stampsSizeLogged;
    /**
     * The capture window. Default OPEN when the ring exists, so the suite
     * legs that simply set the property behave as written; a leg that wants
     * the 1 MiB copy and the quarter-million-int fold OFF outside its own
     * sample window closes it with {@link #endVisibleSetCapture()}.
     */
    private boolean captureOpen = true;

    /** Tri-state: unset = on when the device supports it; true/false force. */
    static final String PROPERTY_PHASE_B_PREDICATE = "meshelium.occlusion.phaseBPredicate";

    // ==================================================================
    // NEXT (c) 2026-09-15: the half-resolution occlusion depth - levers,
    // latch and counters
    //
    // WHY. 0s measured the two box rasters as PIXEL-bound: 0.133 ms at
    // 1080p against 0.211 at 1440p for the same scene, 0.60 when every box
    // passes. A quarter-area depth target is the cheap way to buy that
    // back. The lever DEFAULTS OFF and stays a property until the 1080p AND
    // 1440p bench matrix says otherwise - the standing uncertainty rule,
    // and the owner directive it exists for: "computers yours continue
    // working please". The other standing directive, "performance is king",
    // is why it was built at all.
    // ==================================================================

    /** The lever. Default OFF; safe to flip mid-session (see the setter). */
    public static final String PROPERTY_HALF_RES = "meshelium.occlusion.halfRes";

    /**
     * Which depth arm the half-res rasters use: {@code flat} (default, and
     * the ONLY sound one) or {@code bias} (a measurement-only comparator).
     *
     * <p>The bias arm is kept because it is cheap to measure and its cost
     * profile is the natural comparison, but it can never ship and can
     * never flip a default: its superset argument needs the BIASED depth to
     * survive the depth test, and Vulkan leaves z_f undefined outside
     * [z_min, z_max] without depth clamping (fragops.adoc, "Depth Clamping
     * and Range Adjustment"). This backend gives exactly that case -
     * {@code depthClampEnable(false)} in {@code buildBoxPipeline}, viewport
     * minDepth 0 / maxDepth 1 from {@code VulkanRenderPass.<init>}, and no
     * VK_KHR/EXT_depth_clamp_zero_one in vanilla's required or optional
     * extension lists - and the bound N/d + 2.8 N L/d^2 > 1 puts every
     * section within ~2 blocks and every region box within ~6 blocks of the
     * eye past 1.0, all OUTSIDE the ~0.24-block near force. Neither rescue
     * works: {@code depthBiasClamp} is a constant clamp that cannot bound
     * z + o, and its FEATURE is VK_FALSE on vanilla's device
     * ({@code VulkanBackend.createDevice} callocs a zeroed Features2 and
     * sets only its own ten features); {@code depthClampEnable(true)} needs
     * the same VK_FALSE {@code depthClamp} feature and would disable the
     * near clipping the whole near-force derivation rests on.</p>
     */
    public static final String PROPERTY_HALF_RES_ARM = "meshelium.occlusion.halfResArm";

    /**
     * Boot-time (read at device creation): append
     * VK_EXT_conservative_rasterization and build the box pipelines in
     * overestimate mode. Part B of the design - an ADDITIVE alternative to
     * the coverage inflation, never load-bearing, because the extension may
     * be absent on a device.
     */
    public static final String PROPERTY_CONSERVATIVE_RASTER = "meshelium.occlusion.conservativeRaster";

    /**
     * Measurement only, never a setting: false forces the coverage
     * inflation to zero so part B can be measured as the coverage fix on
     * its own. UNSOUND without part B.
     */
    static final String PROPERTY_HALF_RES_INFLATE = "meshelium.occlusion.diag.halfResInflate";

    /** Test-only, never a setting: the visible-set readback 97_18 needs. */
    public static final String PROPERTY_STAMPS_READBACK = "meshelium.occlusion.diag.stampsReadback";

    /**
     * Test-only: narrow the readback copy and fold to this many stamp rows
     * (0 = every row). The escape hatch for the cost the readback really
     * carries at a large mirror capacity - 1 MiB a slot and a quarter of a
     * million int comparisons per owned frame - when a leg knows its world
     * is small. {@code beginVisibleSetCapture()} is the other one.
     */
    public static final String PROPERTY_STAMPS_ROWS = "meshelium.occlusion.diag.stampsRows";

    /** {@code halfResArm} values. 0 is "full-res this frame". */
    public static final int HALF_RES_ARM_BIAS = 1;
    public static final int HALF_RES_ARM_FLAT = 2;

    private static volatile boolean halfResArmValueWarned;

    /**
     * The arm the properties ask for: FLAT unless the session explicitly
     * asked for the comparator. Also the value 97_18 restores when it is
     * finished flipping arms.
     */
    public static int configuredArm() {
        String v = System.getProperty(PROPERTY_HALF_RES_ARM, "flat");
        if ("bias".equalsIgnoreCase(v)) {
            return HALF_RES_ARM_BIAS;
        }
        if (!"flat".equalsIgnoreCase(v) && !halfResArmValueWarned) {
            halfResArmValueWarned = true;
            MesheliumLog.LOGGER.warn(
                    "Meshelium: {}='{}' is not 'flat' or 'bias'; reading it as flat (the only "
                            + "arm whose superset holds by construction)",
                    PROPERTY_HALF_RES_ARM, v);
        }
        return HALF_RES_ARM_FLAT;
    }

    private static volatile boolean halfResEnabled = Boolean.getBoolean(PROPERTY_HALF_RES);
    private static volatile int halfResArm = configuredArm();
    private static volatile boolean halfResBroken;
    private static volatile String halfResError;

    // Counters. Volatile statics with public getters, the SodiumTerrainDrawer
    // pattern; every one of them is exported to the bench JSON and the
    // stand-down census so no cell can be misfiled.
    /**
     * Frames that ARMED half-res. Counted in {@link #armHalfRes} on a
     * non-zero return, NOT in the record methods: those can return before
     * recording anything ({@code recordSectionRasterSodium} on
     * {@code drawCount <= 0}, and the whole CUTOUT call on an unowned
     * frame), so counting there made a leg start or a teleport look like a
     * lever fault. 1:1 with {@code occlusionFrames} except on a frame that
     * latches between the arm and the counter, which can only make THIS the
     * larger - hence the [0.98, 1.02] fraction rule rather than equality.
     */
    static volatile long halfResFrames;
    /** Of those, the frames whose section raster actually recorded. */
    static volatile long halfResRasterFrames;
    /** Of the armed frames, those on the FLAT arm. */
    static volatile long halfResFlatFrames;
    /** Target (re)creations: moves with {@code occlusionRecreates} (97_13). */
    static volatile long halfResAllocations;
    /** Attachment allocations that threw. Asserted 0 by the census and F.4. */
    static volatile long halfResAllocationFailures;
    /**
     * Frames the arm refused, as the SUM of the per-reason counters below.
     * Kept as the sum so the readers that assert on it do not move:
     * {@code assertHalfResHealthy}, 97_10's {@code legOcclusionOn} and the
     * standalone's {@code assertHalfResBootTime} all assert it is 0
     * ABSOLUTELY at a pinned pose, and {@code gpuCensus} bounds it run-wide
     * as a RATE.
     */
    static volatile long halfResArmSkips;
    /** Standalone: the bound matrix was not bit-equal to cam.projectionMatrix. */
    static volatile long halfResArmProjectionMismatches;
    /** G-1: the DRAWN matrix was null or carried a non-finite entry. */
    static volatile long halfResArmSkipsNonFinite;
    /** G0: no pre-bob stash - the LevelRenderer.render HEAD hook never ran. */
    static volatile long halfResArmSkipsNoPreBob;
    /** G0: the stash serial did not advance since the previous arm. */
    static volatile long halfResArmSkipsStale;
    /** G1/G1b: the pre-bob stash is not the canonical perspective. */
    static volatile long halfResArmSkipsShape;
    /** G2/G3: portal, nausea, or a future vanilla non-rigid transform. */
    static volatile long halfResArmSkipsNonRigid;
    /** G4: the recovered eye displacement exceeded the gate's bound. */
    static volatile long halfResArmSkipsTranslation;
    /** G5: ModelViewMat was not a rotation about the origin. */
    static volatile long halfResArmSkipsView;
    /**
     * The largest recovered {@code ||t||} the lever ever ARMED under, in
     * blocks. A session maximum: PRINTED by the walking legs, and asserted
     * only as a BOUND on vanilla ({@code <= 0.1 + 1e-3}), never as a
     * witness that this window bobbed - a monotone max only rises, so
     * {@code > 0} could be satisfied by a frame from an earlier leg.
     */
    static volatile float halfResMaxArmedTranslation;
    /**
     * The largest {@code ||L^T L - I||_inf} the lever ever armed under.
     * This is the ONLY evidence for {@code ORTHO_EPS = 1e-4}'s acceptance
     * side: the {@code m23 = -0.99999994} figure it used to be
     * extrapolated from belongs to a frame carrying a real 0.0198-degree
     * rotation, not to an identity pose (JOML's {@code mul} short-circuits
     * on an identity right operand, ip 15-31). Until this is read,
     * ORTHO_EPS is a choice, not a headroom claim. PRINTED, never asserted.
     */
    static volatile float halfResMaxArmedOrtho;
    /**
     * Armed frames whose recovered transform was NOT the identity - the
     * non-vacuity instrument, and its floor is a derivation rather than a
     * tuning knob.
     *
     * <p><b>{@code ||t||} is EXACTLY zero at a pinned pose.</b>
     * {@code mulPerspectiveAffine} writes {@code D.m30 = P.m00 * M.m30},
     * {@code D.m31 = P.m11 * M.m31} and {@code D.m33 = P.m23 * M.m32}, and
     * the recovery divides by exactly the factors that were multiplied in.
     * {@code bobView} at {@code bob == 0} translates by
     * {@code (0.0f, -|cos(f pi) * 0|, 0.0f) = (0.0f, -0.0f, 0.0f)}, so the
     * recovered {@code t} is {@code (0.0f, -0.0f, +0.0f)} - note the LAST
     * component: {@code t_z = -D.m33 = -((-1.0f) * 0.0f) = +0.0f} - and
     * {@code ||t||} is exactly {@code 0.0f}. So {@code ||t|| > 0f} needs no
     * epsilon and cannot drift.</p>
     *
     * <p>The orthogonality half has {@code TRANSFORMED_ORTHO_EPS = 1e-6f},
     * which exists only so the rounding residue of a recovered REAL
     * rotation is not counted twice on top of the translation term.</p>
     *
     * <p>Monotone, so a WINDOW DELTA is a window-scoped statement - which
     * is what the walking legs assert, on both hosts. It is strictly
     * stronger than a client-side bob reading, because it is produced
     * inside the arm by the very recovery this change is about.</p>
     */
    static volatile long halfResArmedTransformedFrames;
    static volatile int halfResWidth;
    static volatile int halfResHeight;
    /**
     * The LIVE k and R of the most recent successful arm. Test probes:
     * 97_18's pose rule needs the real numbers to say how far the near
     * force can reach at THIS resolution, rather than quoting the plan's
     * arithmetic back at itself.
     */
    static volatile float halfResInflateK;
    static volatile float halfResNearR;

    public static boolean halfResEnabled() {
        return halfResEnabled;
    }

    /**
     * Safe mid-session, which is what lets 97_18 prove off, on AND the
     * transition in one run: the only state is the attachment pair (created
     * lazily) and the per-arm pipelines (built on that arm's first frame),
     * and stamps self-invalidate by frame-stamp equality, so no buffer
     * carries anything across the flip.
     */
    public static void setHalfResEnabled(boolean enabled) {
        halfResEnabled = enabled;
    }

    /** 1 = the bias comparator, 2 = FLAT. Runtime-safe for the same reason. */
    public static int halfResArm() {
        return halfResArm;
    }

    public static void setHalfResArm(int arm) {
        halfResArm = arm == HALF_RES_ARM_BIAS ? HALF_RES_ARM_BIAS : HALF_RES_ARM_FLAT;
    }

    public static long halfResFrames() {
        return halfResFrames;
    }

    public static long halfResRasterFrames() {
        return halfResRasterFrames;
    }

    public static long halfResFlatFrames() {
        return halfResFlatFrames;
    }

    public static long halfResAllocations() {
        return halfResAllocations;
    }

    public static long halfResAllocationFailures() {
        return halfResAllocationFailures;
    }

    public static long halfResArmSkips() {
        return halfResArmSkips;
    }

    public static long halfResArmProjectionMismatches() {
        return halfResArmProjectionMismatches;
    }

    public static long halfResArmSkipsNonFinite() {
        return halfResArmSkipsNonFinite;
    }

    public static long halfResArmSkipsNoPreBob() {
        return halfResArmSkipsNoPreBob;
    }

    public static long halfResArmSkipsStale() {
        return halfResArmSkipsStale;
    }

    public static long halfResArmSkipsShape() {
        return halfResArmSkipsShape;
    }

    public static long halfResArmSkipsNonRigid() {
        return halfResArmSkipsNonRigid;
    }

    public static long halfResArmSkipsTranslation() {
        return halfResArmSkipsTranslation;
    }

    public static long halfResArmSkipsView() {
        return halfResArmSkipsView;
    }

    /** The session's largest armed {@code ||t||}; see the field. */
    public static float halfResMaxArmedTranslation() {
        return halfResMaxArmedTranslation;
    }

    /** The session's largest armed orthogonality residual; see the field. */
    /**
     * Test only: clear the two armed maxima so a leg can attribute them to
     * ONE window instead of to the whole session. Added 2026-09-18 to find
     * out how big the recovered translation really is inside 97_19's
     * stationary control, rather than only whether it was bit-exactly zero.
     */
    public static void resetHalfResMaxArmedForTest() {
        halfResMaxArmedTranslation = 0f;
        halfResMaxArmedOrtho = 0f;
    }

    public static float halfResMaxArmedOrtho() {
        return halfResMaxArmedOrtho;
    }

    /** Armed frames whose recovered transform was not the identity. */
    public static long halfResArmedTransformedFrames() {
        return halfResArmedTransformedFrames;
    }

    /**
     * Every refusal reason on one line, so a red leg names WHICH check
     * refused instead of leaving the coordinator with one aggregate number.
     */
    public static String halfResArmSkipReasons() {
        return "total=" + halfResArmSkips
                + " nonFinite=" + halfResArmSkipsNonFinite
                + " noPreBob=" + halfResArmSkipsNoPreBob
                + " stale=" + halfResArmSkipsStale
                + " shape=" + halfResArmSkipsShape
                + " nonRigid=" + halfResArmSkipsNonRigid
                + " translation=" + halfResArmSkipsTranslation
                + " view=" + halfResArmSkipsView;
    }

    /** The live target's width, 0 when there is none. */
    public static int halfResWidth() {
        return halfResWidth;
    }

    public static int halfResHeight() {
        return halfResHeight;
    }

    /** k of the coverage inflation at the most recent arm (0 = never armed). */
    public static float halfResInflateK() {
        return halfResInflateK;
    }

    /** The near-tip radius at the most recent arm (0 = never armed). */
    public static float halfResNearR() {
        return halfResNearR;
    }

    public static boolean halfResBroken() {
        return halfResBroken;
    }

    public static String halfResError() {
        return halfResError;
    }

    /**
     * Part B is live: the device was created with
     * VK_EXT_conservative_rasterization because the property asked for it,
     * so every box pipeline built from here carries overestimate mode.
     */
    public static boolean conservativeRasterActive() {
        return MesheliumVulkanState.conservativeRasterizationEnabled();
    }

    /** The measurement-only inflation switch (absent = on). */
    private static boolean halfResInflateEnabled() {
        String v = System.getProperty(PROPERTY_HALF_RES_INFLATE);
        return v == null || Boolean.parseBoolean(v);
    }

    /**
     * Stand down the half-res path for the rest of the session; occlusion
     * itself keeps running at full resolution. One report, then silence -
     * and the suite turns that report into a RED run
     * ({@code halfResError() == null} is asserted by 97_10, 97_18 and F.4),
     * so a latch is never a quiet fallback.
     */
    private static void latchHalfRes(Throwable t) {
        if (halfResBroken) {
            return;
        }
        halfResBroken = true;
        halfResError = String.valueOf(t);
        MesheliumLog.LOGGER.error(
                "Meshelium's half-resolution occlusion depth is standing down for the rest of "
                        + "this session; the rasters run at full resolution from this frame "
                        + "(first and only report)", t);
    }

    private TerrainOcclusion(VulkanDevice device, VulkanCommandEncoder encoder,
            int regionStampSlots, boolean sodium,
            MesheliumVkBuffers.DeviceBuffer sectionStampsA, MesheliumVkBuffers.DeviceBuffer sectionStampsB,
            MesheliumVkBuffers.DeviceBuffer regionStamps, MesheliumVkBuffers.DeviceBuffer stats,
            MesheliumVkBuffers.MappedBuffer statsRing,
            MesheliumVkBuffers.DeviceBuffer predicate,
            int stampRows, MesheliumVkBuffers.MappedBuffer stampsRing, int copyRows) {
        this.device = device;
        this.encoder = encoder;
        this.vma = device.vma();
        this.regionStampSlots = regionStampSlots;
        this.sodium = sodium;
        this.sectionStampsA = sectionStampsA;
        this.sectionStampsB = sectionStampsB;
        this.regionStamps = regionStamps;
        this.stats = stats;
        this.statsRing = statsRing;
        this.predicate = predicate;
        this.stampRows = stampRows;
        this.stampsRing = stampsRing;
        this.copyRows = copyRows;
        this.copyBytes = (long) copyRows * 256L * 4L;
        // Plus the 16-byte tail carrying the two forcedNear pairs.
        this.slotBytes = this.copyBytes + 16L;
        if (stampsRing == null) {
            this.ringStamp = null;
            this.ringFrame = null;
            this.ringForcedRegionWide = null;
            this.ringForcedRegionPlain = null;
            this.ringForcedSectionWide = null;
            this.ringForcedSectionPlain = null;
        } else {
            this.ringStamp = new int[STATS_RING];
            this.ringFrame = new long[STATS_RING];
            java.util.Arrays.fill(this.ringFrame, -1L);
            this.ringForcedRegionWide = new int[STATS_RING];
            this.ringForcedRegionPlain = new int[STATS_RING];
            this.ringForcedSectionWide = new int[STATS_RING];
            this.ringForcedSectionPlain = new int[STATS_RING];
        }
    }

    /**
     * Build the per-world occlusion resources on vanilla's device/VMA
     * (the MesheliumTerrainGpu.create seam). Returns null when the device
     * facade isn't up yet — the drawer falls back to the BFS feed for the
     * frame and retries. Zero-fills everything (stamp 0 can never equal a
     * live FrameStamp; the counter starts above 0).
     */
    static TerrainOcclusion create() {
        // Wave-10: both maxRegions and the per-frame list capacity come
        // from the world's pinned MesheliumScaling snapshot (2048/512 while
        // the configured max render distance is the default 32 — the
        // wave-6 sizes exactly).
        return create(TerrainResidency.maxRegions(), listCapacity(), false);
    }

    /**
     * D-023 stage 3: the generalised constructor the standalone
     * {@link #create()} delegates to with its wave-10 values (behaviour
     * unchanged) and the Sodium host calls with the record mirror's id
     * capacity for both sizes.
     *
     * @param maxRegions   section-stamp rows: {@code maxRegions * 256}
     *        slots per ping-pong buffer, indexed {@code regionId * 256 +
     *        slot} (the residency's region budget standalone; the mirror's
     *        {@code mid} capacity on the Sodium host, where the slot is
     *        Sodium's {@code LocalSectionIndex})
     * @param listCapacity region-stamp slots, indexed by per-frame list
     *        position ({@link #listCapacity()} standalone; every listed
     *        Sodium region carries a mid, so the mirror capacity bounds it)
     * @param sodium       the Sodium host: no stats buffer (the task stage's
     *        counters are the mirror header's, read back by
     *        {@link SodiumMirrorGpu#recordStatsTransfer}), no phase-B
     *        predicate ever (the RDNA4 stall, D-023 keeps it off), and the
     *        {@code record*Sodium} overloads are the recording entry points
     */
    static TerrainOcclusion create(int maxRegions, int listCapacity, boolean sodium) {
        GpuDevice facade = RenderSystem.tryGetDevice();
        if (facade == null) {
            return null;
        }
        VulkanDevice device = (VulkanDevice) ((GpuDeviceAccessor) (Object) facade).meshelium$backend();
        VulkanCommandEncoder encoder = device.createCommandEncoder(); // singleton (frame-path Q1.2)
        long vma = device.vma();

        long stampBytes = (long) maxRegions * 256L * 4L;
        int regionStampSlots = listCapacity;
        int stampUsage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        // NEXT (c), test-only: the stamps readback needs TRANSFER_SRC on the
        // buffers it copies (VUID-vkCmdCopyBuffer-srcBuffer-00118) and eight
        // bytes past the live index space for the forcedNear counter pair.
        // With the property ABSENT the VkBufferCreateInfo below is
        // byte-identical to the shipped one - no usage bit, no extra byte.
        boolean stampsReadback = Boolean.getBoolean(PROPERTY_STAMPS_READBACK);
        int diagBytes = stampsReadback ? 8 : 0;
        if (stampsReadback) {
            stampUsage |= VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        }
        MesheliumVkBuffers.DeviceBuffer a = MesheliumVkBuffers.createDeviceLocal(vma,
                stampBytes + diagBytes,
                stampUsage, "vmaCreateBuffer(meshelium occlusion section stamps A)");
        MesheliumVkBuffers.DeviceBuffer b = MesheliumVkBuffers.createDeviceLocal(vma,
                stampBytes + diagBytes,
                stampUsage, "vmaCreateBuffer(meshelium occlusion section stamps B)");
        MesheliumVkBuffers.DeviceBuffer region = MesheliumVkBuffers.createDeviceLocal(vma,
                (long) regionStampSlots * 4L + diagBytes, stampUsage,
                "vmaCreateBuffer(meshelium occlusion region stamps)");
        MesheliumVkBuffers.DeviceBuffer stats = null;
        MesheliumVkBuffers.MappedBuffer ring = null;
        MesheliumVkBuffers.DeviceBuffer predicate = null;
        if (!sodium) {
            stats = MesheliumVkBuffers.createDeviceLocal(vma, STATS_BYTES,
                    stampUsage | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    "vmaCreateBuffer(meshelium occlusion stats)");
            ring = MesheliumVkBuffers.createHostReadback(vma,
                    (long) STATS_RING * STATS_BYTES, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    "vmaCreateBuffer(meshelium occlusion stats ring)");

            // Phase-B predicate: DEFAULT OFF EVERYWHERE, measured 2026-08-16.
            // The mechanism works exactly as designed - phase B's GPU time
            // collapses 0.239 to 0.008 ms at rd 64 and the camera-cut gate
            // still passes - but on RDNA4 (driver 26.7.1, LLPC) conditional
            // rendering itself costs a periodic ~9 ms stall every ~20 frames:
            // frame p99 went 3.6 to 10.6 ms against a median win of 0.03 ms.
            // The 0.163 ms ceiling is real and stays measured in
            // docs/OCCLUSION-FILLRATE-DESIGN.md; the property exists so silicon
            // with cheap conditional rendering can be measured without a
            // rebuild, and any future default flip must be per-vendor and
            // per-measurement, the multiWG pattern inverted. Never on the
            // Sodium host (D-023).
            String predProp = System.getProperty(PROPERTY_PHASE_B_PREDICATE);
            boolean wantPredicate = predProp != null && Boolean.parseBoolean(predProp);
            if (wantPredicate && MesheliumVulkanState.conditionalRenderingSupported()) {
                predicate = MesheliumVkBuffers.createDeviceLocal(vma, 4L,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | EXTConditionalRendering.VK_BUFFER_USAGE_CONDITIONAL_RENDERING_BIT_EXT,
                        "vmaCreateBuffer(meshelium phase-B predicate)");
            } else if (wantPredicate && predProp != null) {
                MesheliumLog.LOGGER.warn(
                        "Meshelium: {}=true but the device did not offer "
                                + "VK_EXT_conditional_rendering; phase B records directly",
                        PROPERTY_PHASE_B_PREDICATE);
            }
        }

        // NEXT (c), test-only: the visible-set readback ring. The ring is
        // sized at CREATION from maxRegions (a growth recreates the whole
        // instance, so it never has to grow), and the per-frame copy and
        // fold cover copyRows only - the full row count by default, or the
        // narrower prefix -Dmeshelium.occlusion.diag.stampsRows names. The
        // real numbers matter and are printed at the first copy: at the
        // Sodium host's CAPACITY_INITIAL = 1024 a full slot is 1 MiB, the
        // ring 8 MiB, and the fold a pass over 262,144 ints EVERY owned
        // frame. That is why this is test-only, why it is set in the two
        // suite runs whose 97_18 needs a set and never in a bench or a
        // latency leg, and why beginVisibleSetCapture/end exist.
        MesheliumVkBuffers.MappedBuffer stampsRing = null;
        int copyRows = maxRegions;
        if (stampsReadback) {
            int wanted = Integer.getInteger(PROPERTY_STAMPS_ROWS, 0);
            if (wanted > 0) {
                copyRows = Math.min(maxRegions, wanted);
            }
            long slot = (long) copyRows * 256L * 4L + 16L;
            stampsRing = MesheliumVkBuffers.createHostReadback(vma, (long) STATS_RING * slot,
                    VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    "vmaCreateBuffer(meshelium occlusion stamps readback)");
        }

        TerrainOcclusion occ = new TerrainOcclusion(device, encoder, regionStampSlots, sodium,
                a, b, region, stats, ring, predicate, maxRegions, stampsRing, copyRows);
        occ.zeroInitialize(stampBytes + diagBytes, (long) regionStampSlots * 4L + diagBytes);
        if (sodium) {
            MesheliumLog.LOGGER.info(
                    "Meshelium occlusion GPU state up for the Sodium host: 2×{} KiB section stamps "
                            + "({} region ids × 256 slots) + {} B region stamps ({} slots), device "
                            + "'{}'; stats in the record mirror's header, phase-B predicate off.",
                    stampBytes >> 10, maxRegions, regionStampSlots * 4, regionStampSlots,
                    device.getDeviceInfo().name());
        } else {
            MesheliumLog.LOGGER.info(
                    "Meshelium occlusion GPU state up: 2×{} KiB section stamps + {} B region stamps "
                            + "({} slots) + {} B stats (+{} B host ring), device '{}'; phase-B "
                            + "predicate {} (VK_EXT_conditional_rendering: the GPU skips the phase-B "
                            + "dispatches on frames where no section became newly visible)",
                    stampBytes >> 10, regionStampSlots * 4, regionStampSlots,
                    STATS_BYTES, STATS_RING * STATS_BYTES,
                    device.getDeviceInfo().name(),
                    predicate != null ? "ON" : "off");
        }
        return occ;
    }

    /** D-023: true for an instance created for the Sodium host. */
    boolean sodiumHost() {
        return sodium;
    }

    private void zeroInitialize(long stampBytes, long regionBytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
            // Both sizes INCLUDE the test-only forcedNear tail when it
            // exists, so the diagnostic starts at zero like everything else.
            VK10.vkCmdFillBuffer(cb, sectionStampsA.vkBuffer(), 0, stampBytes, 0);
            VK10.vkCmdFillBuffer(cb, sectionStampsB.vkBuffer(), 0, stampBytes, 0);
            VK10.vkCmdFillBuffer(cb, regionStamps.vkBuffer(), 0, regionBytes, 0);
            if (stats != null) {
                VK10.vkCmdFillBuffer(cb, stats.vkBuffer(), 0, STATS_BYTES, 0);
            }
            if (predicate != null) {
                // 1, not 0: until the raster passes have voted once, phase B
                // must run. Skipping is only ever earned by evidence.
                VK10.vkCmdFillBuffer(cb, predicate.vkBuffer(), 0, 4L, 1);
            }
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            checkVk(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer(occlusion zero-init)");
            encoder.execute(cb);
        }
        if (statsRing != null) {
            MemoryUtil.memSet(statsRing.mappedAddress(), 0, (long) STATS_RING * STATS_BYTES);
        }
        if (stampsRing != null) {
            // NEXT (c), test-only: a slot that was never copied must read as
            // "no stamps", not as whatever VMA handed back. The ringFrame
            // tags already refuse such a slot; this is the belt.
            MemoryUtil.memSet(stampsRing.mappedAddress(), 0, (long) STATS_RING * slotBytes);
        }
    }

    // ------------------------------------------------------------------
    // Frame-parity stamp selection
    // ------------------------------------------------------------------

    /** Phase-B predicate is live: wrap the phase-B draws in conditional rendering. */
    boolean phaseBPredicateActive() {
        return predicate != null;
    }

    /** The predicate's VkBuffer; only meaningful when {@link #phaseBPredicateActive()}. */
    long predicateVkBuffer() {
        return predicate.vkBuffer();
    }

    /** The buffer THIS frame's raster writes (and phase B reads). */
    long curStampsBuffer(long frameStamp) {
        return ((frameStamp & 1L) == 0L ? sectionStampsA : sectionStampsB).vkBuffer();
    }

    /** The OTHER ping-pong buffer: parity-proof prev for a given cur handle. */
    long otherStampsBuffer(long curBuffer) {
        return curBuffer == sectionStampsA.vkBuffer()
                ? sectionStampsB.vkBuffer() : sectionStampsA.vkBuffer();
    }

    /** The buffer LAST frame's raster wrote (phase A/B read). */
    long prevStampsBuffer(long frameStamp) {
        return ((frameStamp & 1L) == 0L ? sectionStampsB : sectionStampsA).vkBuffer();
    }

    /** The GPU stats buffer; a Sodium-host instance has none (its counters are the mirror header's). */
    long statsBuffer() {
        if (stats == null) {
            throw new IllegalStateException("no occlusion stats buffer on the Sodium host: "
                    + "read the mirror header through SodiumMirrorGpu");
        }
        return stats.vkBuffer();
    }

    // ------------------------------------------------------------------
    // Pipelines (lazy, device-lifetime static cache)
    // ------------------------------------------------------------------

    /**
     * Compile + build both box-raster pipelines on first use. Fixed
     * function per the deliverable, justified against vanilla's
     * conventions (frame-path Q4.2):
     * <ul>
     *   <li>depth test ON, compare GEQUAL (reversed-Z — Nvidium's LEQUAL
     *       under standard Z), depth WRITE OFF;</li>
     *   <li>color writes off via {@code colorWriteMask = 0} on the single
     *       color attachment — NOT an empty color-attachment state: the
     *       passes are opened through vanilla's public encoder API whose
     *       zero-color-attachment path is bytecode-UNVERIFIED (output
     *       size derivation reads "the first non-null attachment"), while
     *       a LOADed, STOREd, mask-0 attachment is provably bit-identical
     *       to not attaching one and keeps every pipeline the exact
     *       attachment shape vanilla's passes and pipelines already use;</li>
     *   <li>cull NONE — the box interiors must mark visibility whichever
     *       face the sample lands on; back faces are farther and simply
     *       fail depth (idempotent stores make extra faces free of
     *       correctness cost);</li>
     *   <li>dynamic {SCISSOR, VIEWPORT}, single-sample, no blending —
     *       vanilla's conventions verbatim.</li>
     * </ul>
     */
    void ensurePipelines(int vkColorFormat, int vkDepthFormat, boolean extendedLists) {
        if ((extendedLists ? regionPipelineExt : regionPipeline) != 0L) {
            return;
        }
        VkDevice vk = device.vkDevice();
        // MESHELIUM_OCC_REGIONS sizes the UBO array (standard variant only —
        // the wave-10 SSBO variant's array is unsized); MESHELIUM_LISTS_SSBO
        // selects the declaration, mirroring TerrainDrawPipeline.
        // MESHELIUM_SODIUM=0: the D-023 Sodium-host arms of the three
        // raster shaders are tested with #if, and a macro defined 0 and
        // an undefined one preprocess to the same text, so every standard
        // and extended compile stays byte-identical (tools/shader_parity.sh).
        Map<String, String> macros = Map.of(
                "MESHELIUM_OCC_REGIONS", Integer.toString(MAX_OCC_REGIONS),
                "MESHELIUM_LISTS_SSBO", extendedLists ? "1" : "0",
                "MESHELIUM_SODIUM", "0");
        // The SECTION pipeline's modules additionally learn to mark the
        // phase-B predicate on newly-visible transitions. The region
        // pipeline never marks (regions have no prev buffer, and a region
        // transition does not imply a phase-B draw), so it compiles the
        // plain variant - which is also why box.frag is compiled twice.
        Map<String, String> sectionMacros = Map.of(
                "MESHELIUM_OCC_REGIONS", Integer.toString(MAX_OCC_REGIONS),
                "MESHELIUM_LISTS_SSBO", extendedLists ? "1" : "0",
                "MESHELIUM_SODIUM", "0",
                "MESHELIUM_MARK_NEW", phaseBPredicateActive() ? "1" : "0");
        long regionMesh = 0L;
        long sectionTask = 0L;
        long sectionMesh = 0L;
        long boxFrag = 0L;
        long boxFragSection = 0L;
        try {
            regionMesh = MesheliumShaderCompiler.compileResourceToModule(vk,
                    "/assets/meshelium/shaders/occlusion/region_raster.mesh",
                    MesheliumShaderCompiler.KIND_MESH, macros);
            sectionTask = MesheliumShaderCompiler.compileResourceToModule(vk,
                    "/assets/meshelium/shaders/occlusion/section_raster.task",
                    MesheliumShaderCompiler.KIND_TASK, sectionMacros);
            sectionMesh = MesheliumShaderCompiler.compileResourceToModule(vk,
                    "/assets/meshelium/shaders/occlusion/section_raster.mesh",
                    MesheliumShaderCompiler.KIND_MESH, sectionMacros);
            boxFrag = MesheliumShaderCompiler.compileResourceToModule(vk,
                    "/assets/meshelium/shaders/occlusion/box.frag",
                    MesheliumShaderCompiler.KIND_FRAGMENT, macros);
            boxFragSection = MesheliumShaderCompiler.compileResourceToModule(vk,
                    "/assets/meshelium/shaders/occlusion/box.frag",
                    MesheliumShaderCompiler.KIND_FRAGMENT, sectionMacros);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                int taskStage = EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT;
                int meshStage = EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
                int fragStage = VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
                // Wave-10: the extended variant reads the list as an SSBO.
                int listType = extendedLists
                        ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                        : VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
                String tag = extendedLists ? " (extended lists)" : "";

                // Region raster: b0 occList UBO|SSBO(M), b1 scene UBO(M),
                // b2 projection UBO(M), b3 regionStamps SSBO(M|F).
                VkDescriptorSetLayoutBinding.Buffer rb = VkDescriptorSetLayoutBinding.calloc(4, stack);
                binding(rb.get(0), 0, listType, meshStage);
                binding(rb.get(1), 1, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, meshStage);
                binding(rb.get(2), 2, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, meshStage);
                binding(rb.get(3), 3, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, meshStage | fragStage);
                long rSet = createSetLayout(vk, stack, rb, "region raster" + tag);
                long rLayout = createPipelineLayout(vk, stack, rSet,
                        meshStage | fragStage, "region raster" + tag);
                long rPipe = buildBoxPipeline(vk, stack, vkColorFormat, vkDepthFormat,
                        rLayout, 0L, regionMesh, boxFrag, "region raster" + tag, false, false);

                // Section raster: b0 occList UBO|SSBO(T), b1 scene UBO(M),
                // b2 projection UBO(M), b3 curStamps SSBO(M|F),
                // b4 regionStamps SSBO(T), b5 sectionRecords SSBO(M),
                // and when the phase-B predicate is live: b6 prevStamps
                // SSBO(M|F), b7 predicate SSBO(M|F).
                boolean mark = phaseBPredicateActive();
                VkDescriptorSetLayoutBinding.Buffer sb =
                        VkDescriptorSetLayoutBinding.calloc(mark ? 8 : 6, stack);
                binding(sb.get(0), 0, listType, taskStage);
                binding(sb.get(1), 1, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, meshStage);
                binding(sb.get(2), 2, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, meshStage);
                binding(sb.get(3), 3, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, meshStage | fragStage);
                binding(sb.get(4), 4, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, taskStage);
                binding(sb.get(5), 5, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, meshStage);
                if (mark) {
                    binding(sb.get(6), 6, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                            meshStage | fragStage);
                    binding(sb.get(7), 7, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                            meshStage | fragStage);
                }
                long sSet = createSetLayout(vk, stack, sb, "section raster" + tag);
                long sLayout = createPipelineLayout(vk, stack, sSet,
                        taskStage | meshStage | fragStage, "section raster" + tag);
                long sPipe = buildBoxPipeline(vk, stack, vkColorFormat, vkDepthFormat,
                        sLayout, sectionTask, sectionMesh,
                        mark ? boxFragSection : boxFrag, "section raster" + tag, false, false);

                if (extendedLists) {
                    regionSetLayoutExt = rSet;
                    regionPipelineLayoutExt = rLayout;
                    regionPipelineExt = rPipe;
                    sectionSetLayoutExt = sSet;
                    sectionPipelineLayoutExt = sLayout;
                    sectionPipelineExt = sPipe;
                } else {
                    regionSetLayout = rSet;
                    regionPipelineLayout = rLayout;
                    regionPipeline = rPipe;
                    sectionSetLayout = sSet;
                    sectionPipelineLayout = sLayout;
                    sectionPipeline = sPipe;
                }
            }
            MesheliumLog.LOGGER.info(
                    "Meshelium occlusion pipelines created (color format {}, depth format {}, "
                            + "{} regions/frame list cap{}, GEQUAL write-off, colorWriteMask 0, cull NONE)",
                    vkColorFormat, vkDepthFormat,
                    extendedLists ? listCapacity() : MAX_OCC_REGIONS,
                    extendedLists ? " via SSBO frame lists (wave 10)" : "");
        } finally {
            VkDevice dev = vk;
            if (regionMesh != 0L) {
                VK10.vkDestroyShaderModule(dev, regionMesh, null);
            }
            if (sectionTask != 0L) {
                VK10.vkDestroyShaderModule(dev, sectionTask, null);
            }
            if (sectionMesh != 0L) {
                VK10.vkDestroyShaderModule(dev, sectionMesh, null);
            }
            if (boxFrag != 0L) {
                VK10.vkDestroyShaderModule(dev, boxFrag, null);
            }
            if (boxFragSection != 0L) {
                VK10.vkDestroyShaderModule(dev, boxFragSection, null);
            }
        }
    }

    /**
     * D-023 stage 3: compile + build the Sodium-host box-raster pipelines
     * on first use ({@code MESHELIUM_SODIUM=1}, {@code MESHELIUM_LISTS_SSBO=1},
     * {@code MESHELIUM_MARK_NEW=0}; box.frag standard). Fixed function as
     * {@link #ensurePipelines}: GEQUAL, depth write off, colorWriteMask 0,
     * cull NONE, dynamic viewport/scissor.
     *
     * <p>Bindings (GPU-VISIBILITY-CONTRACT.md section 5.1): region raster
     * b0 list SSBO (M), b1 scene (M), b2 Sodium projection (M), b3
     * regionStamps (M|F); section raster b0 list SSBO (T), b1 scene (M),
     * b2 projection (M), b3 curStamps (M|F), b4 regionStamps (T), b5 the
     * mirror's ROW slice (M) — the section raster walks the row's 256-bit
     * occupancy instead of per-section records, because Sodium has no
     * per-section bounds and every occupied slot rasters its full box.
     * Numbering as the standard pipelines' so box.frag serves all three.
     */
    void ensureSodiumPipelines(int vkColorFormat, int vkDepthFormat) {
        if (regionPipelineSodium != 0L) {
            return;
        }
        VkDevice vk = device.vkDevice();
        Map<String, String> macros = Map.of(
                "MESHELIUM_OCC_REGIONS", Integer.toString(MAX_OCC_REGIONS),
                "MESHELIUM_LISTS_SSBO", "1",
                "MESHELIUM_SODIUM", "1",
                "MESHELIUM_MARK_NEW", "0",
                // Section boxes per mesh workgroup (SodiumTerrainDrawer
                // .PROPERTY_OCC_BOXES_PER_WG); 1 compiles the original text.
                "MESHELIUM_OCC_BOXES_PER_WG", Integer.toString(SodiumTerrainDrawer.OCC_BOXES_PER_WG));
        long regionMesh = 0L;
        long sectionTask = 0L;
        long sectionMesh = 0L;
        long boxFrag = 0L;
        try {
            regionMesh = MesheliumShaderCompiler.compileResourceToModule(vk,
                    "/assets/meshelium/shaders/occlusion/region_raster.mesh",
                    MesheliumShaderCompiler.KIND_MESH, macros);
            sectionTask = MesheliumShaderCompiler.compileResourceToModule(vk,
                    "/assets/meshelium/shaders/occlusion/section_raster.task",
                    MesheliumShaderCompiler.KIND_TASK, macros);
            sectionMesh = MesheliumShaderCompiler.compileResourceToModule(vk,
                    "/assets/meshelium/shaders/occlusion/section_raster.mesh",
                    MesheliumShaderCompiler.KIND_MESH, macros);
            boxFrag = MesheliumShaderCompiler.compileResourceToModule(vk,
                    "/assets/meshelium/shaders/occlusion/box.frag",
                    MesheliumShaderCompiler.KIND_FRAGMENT, macros);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                int taskStage = EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT;
                int meshStage = EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
                int fragStage = VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
                String tag = " (Sodium host)";

                // Region raster: b0 list SSBO(M), b1 scene UBO(M),
                // b2 Sodium projection UBO(M), b3 regionStamps SSBO(M|F).
                VkDescriptorSetLayoutBinding.Buffer rb = VkDescriptorSetLayoutBinding.calloc(4, stack);
                binding(rb.get(0), 0, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, meshStage);
                binding(rb.get(1), 1, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, meshStage);
                binding(rb.get(2), 2, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, meshStage);
                binding(rb.get(3), 3, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, meshStage | fragStage);
                long rSet = createSetLayout(vk, stack, rb, "region raster" + tag);
                long rLayout = createPipelineLayout(vk, stack, rSet,
                        meshStage | fragStage, "region raster" + tag);
                long rPipe = buildBoxPipeline(vk, stack, vkColorFormat, vkDepthFormat,
                        rLayout, 0L, regionMesh, boxFrag, "region raster" + tag, false, false);

                // Section raster: b0 list SSBO(T), b1 scene UBO(M),
                // b2 projection UBO(M), b3 curStamps SSBO(M|F),
                // b4 regionStamps SSBO(T), b5 mirror rows slice SSBO(M).
                VkDescriptorSetLayoutBinding.Buffer sb = VkDescriptorSetLayoutBinding.calloc(6, stack);
                binding(sb.get(0), 0, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, taskStage);
                binding(sb.get(1), 1, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, meshStage);
                binding(sb.get(2), 2, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, meshStage);
                binding(sb.get(3), 3, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, meshStage | fragStage);
                binding(sb.get(4), 4, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, taskStage);
                binding(sb.get(5), 5, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, meshStage);
                long sSet = createSetLayout(vk, stack, sb, "section raster" + tag);
                long sLayout = createPipelineLayout(vk, stack, sSet,
                        taskStage | meshStage | fragStage, "section raster" + tag);
                long sPipe = buildBoxPipeline(vk, stack, vkColorFormat, vkDepthFormat,
                        sLayout, sectionTask, sectionMesh, boxFrag, "section raster" + tag,
                        false, false);

                regionSetLayoutSodium = rSet;
                regionPipelineLayoutSodium = rLayout;
                regionPipelineSodium = rPipe;
                sectionSetLayoutSodium = sSet;
                sectionPipelineLayoutSodium = sLayout;
                sectionPipelineSodium = sPipe;
            }
            MesheliumLog.LOGGER.info(
                    "Meshelium occlusion pipelines created for the Sodium host (color format {}, "
                            + "depth format {}; 64-B list entries via the frame ring, section boxes "
                            + "from the record mirror's rows; GEQUAL write-off, colorWriteMask 0, "
                            + "cull NONE)",
                    vkColorFormat, vkDepthFormat);
        } finally {
            if (regionMesh != 0L) {
                VK10.vkDestroyShaderModule(vk, regionMesh, null);
            }
            if (sectionTask != 0L) {
                VK10.vkDestroyShaderModule(vk, sectionTask, null);
            }
            if (sectionMesh != 0L) {
                VK10.vkDestroyShaderModule(vk, sectionMesh, null);
            }
            if (boxFrag != 0L) {
                VK10.vkDestroyShaderModule(vk, boxFrag, null);
            }
        }
    }

    // ------------------------------------------------------------------
    // NEXT (c): the half-resolution variants of the two box rasters
    // ------------------------------------------------------------------

    /**
     * Build the standalone half-res raster pipelines of one arm on first
     * use. Records NO commands (shaderc plus
     * {@code vkCreateGraphicsPipelines} only), so it is legal at the arm,
     * outside every pass.
     *
     * <p>THE ORDERING THAT MATTERS: this begins with
     * {@link #ensurePipelines}, because the half pipelines SHARE the
     * full-res descriptor-set and pipeline layouts. The standalone's only
     * other {@code ensurePipelines} call site is inside pass 1
     * ({@code TerrainDrawer}), so a session booted with
     * {@code -Dmeshelium.occlusion.halfRes=true} would otherwise build
     * these at the arm with {@code pipelineLayout == 0L} - a VUID under
     * validation and a session-long latch without it. F.4's boot-time
     * assertion (the FIRST occlusion frames must already be half-res) is
     * the count that catches this class.</p>
     */
    void ensureHalfResPipelines(int vkColorFormat, int vkDepthFormat, boolean extendedLists,
            boolean flat) {
        ensurePipelines(vkColorFormat, vkDepthFormat, extendedLists);
        long built = extendedLists
                ? (flat ? regionPipelineExtFlat : regionPipelineExtHalf)
                : (flat ? regionPipelineFlat : regionPipelineHalf);
        if (built != 0L) {
            return;
        }
        buildHalfVariant(vkColorFormat, vkDepthFormat, false, extendedLists, flat);
    }

    /** The Sodium host's half-res raster pipelines of one arm, same rules. */
    void ensureSodiumHalfResPipelines(int vkColorFormat, int vkDepthFormat, boolean flat) {
        ensureSodiumPipelines(vkColorFormat, vkDepthFormat);
        if ((flat ? regionPipelineSodiumFlat : regionPipelineSodiumHalf) != 0L) {
            return;
        }
        buildHalfVariant(vkColorFormat, vkDepthFormat, true, true, flat);
    }

    /**
     * Compile the raster modules with {@code MESHELIUM_OCC_HALFRES=1} (and,
     * for the FLAT arm, {@code MESHELIUM_OCC_HALFRES_FLAT=1}) and build the
     * two pipelines on the EXISTING full-res layouts. Only the modules and
     * the depth-bias state differ from the full-res build; the bindings,
     * the push range and the attachment shape are identical, which is what
     * lets the half passes reuse them.
     */
    private void buildHalfVariant(int vkColorFormat, int vkDepthFormat,
            boolean sodiumHost, boolean extendedLists, boolean flat) {
        VkDevice vk = device.vkDevice();
        boolean mark = !sodiumHost && phaseBPredicateActive();
        Map<String, String> macros = new HashMap<>();
        macros.put("MESHELIUM_OCC_REGIONS", Integer.toString(MAX_OCC_REGIONS));
        macros.put("MESHELIUM_LISTS_SSBO", extendedLists ? "1" : "0");
        macros.put("MESHELIUM_SODIUM", sodiumHost ? "1" : "0");
        macros.put("MESHELIUM_MARK_NEW", mark ? "1" : "0");
        macros.put("MESHELIUM_OCC_HALFRES", "1");
        if (flat) {
            macros.put("MESHELIUM_OCC_HALFRES_FLAT", "1");
        }
        if (sodiumHost) {
            macros.put("MESHELIUM_OCC_BOXES_PER_WG",
                    Integer.toString(SodiumTerrainDrawer.OCC_BOXES_PER_WG));
        }
        // The region raster never marks (regions have no prev buffer), so
        // it compiles the plain variant exactly as the full-res build does.
        Map<String, String> regionMacros = new HashMap<>(macros);
        regionMacros.put("MESHELIUM_MARK_NEW", "0");

        String arm = flat ? "FLAT" : "bias (comparator; sound only where z+o<=1)";
        String tag = " (half-res " + arm + (sodiumHost ? ", Sodium host"
                : extendedLists ? ", extended lists" : "") + ")";
        long regionMesh = 0L;
        long sectionTask = 0L;
        long sectionMesh = 0L;
        long boxFrag = 0L;
        long boxFragSection = 0L;
        try {
            regionMesh = MesheliumShaderCompiler.compileResourceToModule(vk,
                    "/assets/meshelium/shaders/occlusion/region_raster.mesh",
                    MesheliumShaderCompiler.KIND_MESH, regionMacros);
            sectionTask = MesheliumShaderCompiler.compileResourceToModule(vk,
                    "/assets/meshelium/shaders/occlusion/section_raster.task",
                    MesheliumShaderCompiler.KIND_TASK, macros);
            sectionMesh = MesheliumShaderCompiler.compileResourceToModule(vk,
                    "/assets/meshelium/shaders/occlusion/section_raster.mesh",
                    MesheliumShaderCompiler.KIND_MESH, macros);
            boxFrag = MesheliumShaderCompiler.compileResourceToModule(vk,
                    "/assets/meshelium/shaders/occlusion/box.frag",
                    MesheliumShaderCompiler.KIND_FRAGMENT, regionMacros);
            boxFragSection = mark
                    ? MesheliumShaderCompiler.compileResourceToModule(vk,
                            "/assets/meshelium/shaders/occlusion/box.frag",
                            MesheliumShaderCompiler.KIND_FRAGMENT, macros)
                    : 0L;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                long rLayout;
                long sLayout;
                if (sodiumHost) {
                    rLayout = regionPipelineLayoutSodium;
                    sLayout = sectionPipelineLayoutSodium;
                } else if (extendedLists) {
                    rLayout = regionPipelineLayoutExt;
                    sLayout = sectionPipelineLayoutExt;
                } else {
                    rLayout = regionPipelineLayout;
                    sLayout = sectionPipelineLayout;
                }
                if (rLayout == 0L || sLayout == 0L) {
                    throw new IllegalStateException("half-res occlusion pipelines were asked for "
                            + "before the full-res layouts existed (region " + rLayout
                            + ", section " + sLayout + ")");
                }
                long rPipe = buildBoxPipeline(vk, stack, vkColorFormat, vkDepthFormat,
                        rLayout, 0L, regionMesh, boxFrag, "region raster" + tag, true, flat);
                long sPipe = buildBoxPipeline(vk, stack, vkColorFormat, vkDepthFormat,
                        sLayout, sectionTask, sectionMesh,
                        mark ? boxFragSection : boxFrag, "section raster" + tag, true, flat);
                if (sodiumHost) {
                    if (flat) {
                        regionPipelineSodiumFlat = rPipe;
                        sectionPipelineSodiumFlat = sPipe;
                    } else {
                        regionPipelineSodiumHalf = rPipe;
                        sectionPipelineSodiumHalf = sPipe;
                    }
                } else if (extendedLists) {
                    if (flat) {
                        regionPipelineExtFlat = rPipe;
                        sectionPipelineExtFlat = sPipe;
                    } else {
                        regionPipelineExtHalf = rPipe;
                        sectionPipelineExtHalf = sPipe;
                    }
                } else {
                    if (flat) {
                        regionPipelineFlat = rPipe;
                        sectionPipelineFlat = sPipe;
                    } else {
                        regionPipelineHalf = rPipe;
                        sectionPipelineHalf = sPipe;
                    }
                }
            }
            MesheliumLog.LOGGER.info(
                    "Meshelium half-resolution occlusion pipelines created{} (color format {}, "
                            + "depth format {}; arm {}, conservative raster {}; the full-res set's "
                            + "layouts, GEQUAL write-off, colorWriteMask 0, cull NONE)",
                    tag, vkColorFormat, vkDepthFormat, arm,
                    conservativeRasterActive() ? "on" : "off");
        } finally {
            if (regionMesh != 0L) {
                VK10.vkDestroyShaderModule(vk, regionMesh, null);
            }
            if (sectionTask != 0L) {
                VK10.vkDestroyShaderModule(vk, sectionTask, null);
            }
            if (sectionMesh != 0L) {
                VK10.vkDestroyShaderModule(vk, sectionMesh, null);
            }
            if (boxFrag != 0L) {
                VK10.vkDestroyShaderModule(vk, boxFrag, null);
            }
            if (boxFragSection != 0L) {
                VK10.vkDestroyShaderModule(vk, boxFragSection, null);
            }
        }
    }

    /**
     * Pass D's pipeline: the depth downsample. One mesh workgroup emitting
     * a screen-filling triangle, one fragment stage reducing vanilla's main
     * depth with a MIN (reversed-Z far) into the half-size attachment.
     *
     * <p>It owns its set layout and pipeline layout - it shares nothing
     * with the box sets - so it has no ordering dependence on
     * {@link #ensurePipelines}. Depth state is the one real difference from
     * a box pipeline: test ENABLED with compare ALWAYS and write ON, since
     * a write needs the test enabled and the fragment stage supplies the
     * value through {@code gl_FragDepth}.</p>
     *
     * <p><b>NEXT (c1), 2026-09-16: the depth this reduces is the BOBBED
     * main depth, and that is REQUIRED, not incidental.</b> The reduction
     * itself is matrix-independent - {@code depth_downsample.frag} is a
     * clamped MIN over a {@code 2 + odd} window of {@code MainDepth} with
     * no matrix in it - but "matrix-independent" understates the
     * requirement. The box rasters bind the DRAWN matrix, so the boxes and
     * this pyramid must live in the SAME clip space; binding the pre-bob
     * perspective on the box side would poison the terrain side too, by
     * comparing boxes in one clip space against a depth pyramid built in
     * another. That is why c1 moved only what k and NearR are DERIVED from
     * and left what the rasters BIND exactly where it was. This sentence
     * lives here rather than in the shader so the shipped shader tree
     * stays byte-identical and that can be PROVEN by hashing it before and
     * after the change.</p>
     */
    void ensureDownsamplePipeline(int vkColorFormat, int vkDepthFormat) {
        if (downsamplePipeline != 0L) {
            return;
        }
        VkDevice vk = device.vkDevice();
        long meshModule = 0L;
        long fragModule = 0L;
        try {
            meshModule = MesheliumShaderCompiler.compileResourceToModule(vk,
                    "/assets/meshelium/shaders/occlusion/depth_downsample.mesh",
                    MesheliumShaderCompiler.KIND_MESH, Map.of());
            fragModule = MesheliumShaderCompiler.compileResourceToModule(vk,
                    "/assets/meshelium/shaders/occlusion/depth_downsample.frag",
                    MesheliumShaderCompiler.KIND_FRAGMENT, Map.of());
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int fragStage = VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
                VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(1, stack);
                binding(b.get(0), 0, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, fragStage);
                long set = createSetLayout(vk, stack, b, "depth downsample");
                long layout = createPipelineLayout(vk, stack, set, fragStage, "depth downsample");
                long pipe = buildDownsamplePipeline(vk, stack, vkColorFormat, vkDepthFormat,
                        layout, meshModule, fragModule);
                downsampleSetLayout = set;
                downsamplePipelineLayout = layout;
                downsamplePipeline = pipe;
            }
            MesheliumLog.LOGGER.info(
                    "Meshelium occlusion depth downsample pipeline created (color format {}, "
                            + "depth format {}; full-screen mesh triangle at z=0.5, depth compare "
                            + "ALWAYS + write ON, MIN over the 2x2 (3-wide on an odd axis) window "
                            + "- reversed-Z, so the min is the FARTHEST sample and a max here "
                            + "would delete terrain)",
                    vkColorFormat, vkDepthFormat);
        } finally {
            if (meshModule != 0L) {
                VK10.vkDestroyShaderModule(vk, meshModule, null);
            }
            if (fragModule != 0L) {
                VK10.vkDestroyShaderModule(vk, fragModule, null);
            }
        }
    }

    /**
     * Pass D's body. The main depth is SAMPLED here (never attached), so
     * there is no feedback loop, and phase A's writes are already visible
     * through vanilla's pass-end ALL_COMMANDS barrier.
     */
    void recordDepthDownsample(VkCommandBuffer cb, GpuTextureView mainDepth) {
        if (downsamplePipeline == 0L) {
            throw new IllegalStateException("the occlusion depth downsample pipeline was not created");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, downsamplePipeline);
            VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack);
            image.get(0)
                    .sampler(((VulkanGpuSampler) RenderSystem.getSamplerCache()
                            .getClampToEdge(FilterMode.NEAREST)).vkSampler())
                    .imageView(((VulkanGpuTextureView) mainDepth).vkImageView())
                    // GENERAL everywhere on this backend (frame-path 1.4).
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
            writes.get(0).sType$Default()
                    .dstBinding(0)
                    .descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(image);
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb,
                    VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, downsamplePipelineLayout, 0, writes);
            ByteBuffer push = stack.calloc(16);
            push.putInt(0, mainDepth.texture().getWidth(0));
            push.putInt(4, mainDepth.texture().getHeight(0));
            VK10.vkCmdPushConstants(cb, downsamplePipelineLayout,
                    VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 0, push);
            EXTMeshShader.vkCmdDrawMeshTasksEXT(cb, 1, 1, 1);
        }
    }

    /**
     * Arm the half-resolution path for THIS owned frame, and return the
     * frame's half mode (0 = run full-res, 1 = the bias comparator,
     * 2 = FLAT).
     *
     * <p>THE PLACEMENT IS THE SAFETY. Both hosts call this as the FIRST
     * encoder-touching statement of the owned frame - before any pass,
     * upload, clear or timestamp - because {@code ensure} may create the
     * attachment pair, whose UNDEFINED-to-GENERAL layout barrier must not
     * land inside a rendering instance. The only guards are that placement,
     * {@code ensure}'s own assertion on the backend encoder's
     * {@code currentRenderPass} (loud, on the first frame) and the
     * validation layer, which the suite runs grep for 07889 / 01181.</p>
     *
     * <p>Failures stand down the HALF-RES path only, never occlusion: a
     * throw latches (a red run, by 97_10 / 97_18 / F.4 asserting
     * {@code halfResError() == null}), and a false from {@code ensure} (no
     * device) or {@code arm} (an unusable projection, counted) is a quiet
     * full-res frame.</p>
     *
     * <p>NEXT (c1), 2026-09-16: the arm takes TWO matrices with TWO jobs,
     * and the old one-line {@code @param} ("the matrix the rasters BIND -
     * NOT cam.projectionMatrix") became the single most misleading sentence
     * in the tree the moment that stopped being the whole story.</p>
     *
     * @param projection the matrix the rasters BIND - vanilla's
     *        bob-multiplied level projection. CHECKED (that it is the
     *        pre-bob perspective times a finite, affine, bounded isometry),
     *        never DERIVED from. It stays bound because the box must raster
     *        in the same clip space as the terrain depth it tests against
     * @param preBob {@code CameraRenderState.projectionMatrix} as stashed
     *        at {@code LevelRenderer.render} HEAD - canonical by
     *        construction, and the matrix k and NearR are derived FROM
     * @param modelView this frame's {@code ModelViewMat}, i.e.
     *        {@code cam.viewRotationMatrix} on both hosts; G5's input
     * @param preBobSerial the stash's per-frame token; an arm whose serial
     *        has not advanced is refused as stale, which is what catches an
     *        arm reached outside {@code LevelRenderer.render}
     */
    int armHalfRes(GpuTextureView mainColor, GpuTextureView mainDepth, Matrix4fc projection,
            Matrix4fc preBob, Matrix4fc modelView, long preBobSerial,
            int vkColorFormat, int vkDepthFormat, boolean sodiumHost, boolean extendedLists) {
        if (!halfResEnabled || halfResBroken) {
            return 0;
        }
        int mode = halfResArm();
        boolean flat = mode == HALF_RES_ARM_FLAT;
        try {
            if (halfTarget == null) {
                halfTarget = new OcclusionHalfResTarget(encoder);
            }
            if (!halfTarget.ensure(mainColor, mainDepth)) {
                return 0;
            }
            if (!halfTarget.arm(projection, preBob, modelView, preBobSerial)) {
                return 0;
            }
            ensureDownsamplePipeline(vkColorFormat, vkDepthFormat);
            if (sodiumHost) {
                ensureSodiumHalfResPipelines(vkColorFormat, vkDepthFormat, flat);
            } else {
                ensureHalfResPipelines(vkColorFormat, vkDepthFormat, extendedLists, flat);
            }
        } catch (GpuDeviceLossException t) {
            throw t;
        } catch (Throwable t) {
            latchHalfRes(t);
            return 0;
        }
        halfResFrames++;
        if (flat) {
            halfResFlatFrames++;
        }
        return mode;
    }

    /** The half colour attachment; null until a frame has armed. */
    GpuTextureView halfColorView() {
        return halfTarget == null ? null : halfTarget.colorView();
    }

    /** The half depth attachment; null until a frame has armed. */
    GpuTextureView halfDepthView() {
        return halfTarget == null ? null : halfTarget.depthView();
    }

    /** k of the coverage inflation for this frame (0 when the diag switch is off). */
    private float frameInflateK() {
        return halfTarget == null || !halfResInflateEnabled() ? 0f : halfTarget.inflateK();
    }

    private float frameNearR() {
        return halfTarget == null ? 0f : halfTarget.nearR();
    }

    // ------------------------------------------------------------------
    // Per-frame recording (called by TerrainDrawer inside its own passes)
    // ------------------------------------------------------------------

    /**
     * Pass 2's body: all region boxes in one draw of {@code drawCount}
     * workgroups. The list arrives as a {@link ListSlice} since wave 10 —
     * transient UBO slice (standard) or MesheliumFrameLists SSBO slot
     * (extended); pipeline variant and descriptor type follow it.
     */
    void recordRegionRaster(VkCommandBuffer cb, ListSlice occList, GpuBufferSlice scene,
            int drawCount, int frameStamp, int halfMode) {
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        boolean ext = occList.ssbo();
        long pipeline = ext
                ? (halfMode == HALF_RES_ARM_FLAT ? regionPipelineExtFlat
                        : halfMode == HALF_RES_ARM_BIAS ? regionPipelineExtHalf : regionPipelineExt)
                : (halfMode == HALF_RES_ARM_FLAT ? regionPipelineFlat
                        : halfMode == HALF_RES_ARM_BIAS ? regionPipelineHalf : regionPipeline);
        long layout = ext ? regionPipelineLayoutExt : regionPipelineLayout;
        int listType = ext ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                : VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
            bufferWrite(writes.get(0), 0, listType,
                    bufferInfo(stack, occList.vkBuffer(), occList.offset(), occList.range()));
            bufferWrite(writes.get(1), 1, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    sliceInfo(stack, scene));
            bufferWrite(writes.get(2), 2, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    sliceInfo(stack, projection));
            bufferWrite(writes.get(3), 3, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, regionStamps.vkBuffer(), 0, VK10.VK_WHOLE_SIZE));
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb,
                    VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, writes);
            pushStamp(cb, stack, layout,
                    EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT,
                    frameStamp, halfMode != 0 ? frameInflateK() : 0f,
                    halfMode != 0 ? frameNearR() : 0f, halfMode != 0 ? regionDiagBase() : 0);
            EXTMeshShader.vkCmdDrawMeshTasksEXT(cb, drawCount, 1, 1);
        }
    }

    /** Pass 3's body: section boxes of region-raster-visible regions. */
    void recordSectionRaster(VkCommandBuffer cb, ListSlice occList, GpuBufferSlice scene,
            long sectionRecordsBuffer, int drawCount, int frameStamp, long curStampsBuffer,
            int halfMode) {
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        boolean ext = occList.ssbo();
        long pipeline = ext
                ? (halfMode == HALF_RES_ARM_FLAT ? sectionPipelineExtFlat
                        : halfMode == HALF_RES_ARM_BIAS ? sectionPipelineExtHalf : sectionPipelineExt)
                : (halfMode == HALF_RES_ARM_FLAT ? sectionPipelineFlat
                        : halfMode == HALF_RES_ARM_BIAS ? sectionPipelineHalf : sectionPipeline);
        long layout = ext ? sectionPipelineLayoutExt : sectionPipelineLayout;
        if (halfMode != 0) {
            halfResRasterFrames++;
        }
        int listType = ext ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                : VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            boolean mark = phaseBPredicateActive();
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(mark ? 8 : 6, stack);
            bufferWrite(writes.get(0), 0, listType,
                    bufferInfo(stack, occList.vkBuffer(), occList.offset(), occList.range()));
            bufferWrite(writes.get(1), 1, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    sliceInfo(stack, scene));
            bufferWrite(writes.get(2), 2, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    sliceInfo(stack, projection));
            bufferWrite(writes.get(3), 3, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, curStampsBuffer, 0, VK10.VK_WHOLE_SIZE));
            bufferWrite(writes.get(4), 4, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, regionStamps.vkBuffer(), 0, VK10.VK_WHOLE_SIZE));
            bufferWrite(writes.get(5), 5, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, sectionRecordsBuffer, 0, VK10.VK_WHOLE_SIZE));
            if (mark) {
                // prevStamps: the OTHER ping-pong buffer, holding last
                // frame's marks - the "was it visible last frame" half of
                // the newly-visible test.
                bufferWrite(writes.get(6), 6, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                        bufferInfo(stack, otherStampsBuffer(curStampsBuffer), 0, VK10.VK_WHOLE_SIZE));
                bufferWrite(writes.get(7), 7, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                        bufferInfo(stack, predicate.vkBuffer(), 0, VK10.VK_WHOLE_SIZE));
            }
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb,
                    VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, writes);
            pushStamp(cb, stack, layout,
                    EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT
                            | EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT
                            | VK10.VK_SHADER_STAGE_FRAGMENT_BIT,
                    frameStamp, halfMode != 0 ? frameInflateK() : 0f,
                    halfMode != 0 ? frameNearR() : 0f, halfMode != 0 ? sectionDiagBase() : 0);
            EXTMeshShader.vkCmdDrawMeshTasksEXT(cb, drawCount, 1, 1);
        }
    }

    /**
     * The word index, in {@code regionStamps}, of the region raster's
     * {@code forcedNear} counter pair - the two words the test-only
     * allocation added past the live slots. 0 means "off", and index 0 can
     * never BE the pair (the list capacity is at least
     * {@value #MAX_OCC_REGIONS}).
     */
    private int regionDiagBase() {
        return stampsRing == null ? 0 : regionStampSlots;
    }

    /** The same, in whichever {@code curStamps} buffer the section raster binds. */
    private int sectionDiagBase() {
        return stampsRing == null ? 0 : stampRows * 256;
    }

    // ------------------------------------------------------------------
    // Per-frame recording, Sodium host (D-023 stage 3; called by
    // SodiumTerrainDrawer.drawCutoutOwned inside its own passes)
    // ------------------------------------------------------------------

    /**
     * Pass 2 on the Sodium host: every listed region's occupancy box in
     * one draw of {@code drawCount} workgroups (one per list entry), the
     * list being the frame ring's 64-byte-entry slot and the projection
     * Sodium's own slice ({@code SodiumTerrainDrawer.uploadProjection}) —
     * the matrix the terrain draw used, so box depth and terrain depth
     * come from the identical transform. Requires
     * {@link #ensureSodiumPipelines} to have run.
     */
    void recordRegionRasterSodium(VkCommandBuffer cb, ListSlice occList, GpuBufferSlice scene,
            GpuBufferSlice projection, int drawCount, int frameStamp, int halfMode) {
        if (drawCount <= 0) {
            return; // nothing listed: no boxes, and no stamp can be written
        }
        long pipeline = halfMode == HALF_RES_ARM_FLAT ? regionPipelineSodiumFlat
                : halfMode == HALF_RES_ARM_BIAS ? regionPipelineSodiumHalf : regionPipelineSodium;
        if (pipeline == 0L) {
            throw new IllegalStateException("Sodium-host occlusion pipelines were not created");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
            bufferWrite(writes.get(0), 0, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, occList.vkBuffer(), occList.offset(), occList.range()));
            bufferWrite(writes.get(1), 1, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    sliceInfo(stack, scene));
            bufferWrite(writes.get(2), 2, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    sliceInfo(stack, projection));
            bufferWrite(writes.get(3), 3, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, regionStamps.vkBuffer(), 0, VK10.VK_WHOLE_SIZE));
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb,
                    VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, regionPipelineLayoutSodium, 0, writes);
            pushStamp(cb, stack, regionPipelineLayoutSodium,
                    EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT,
                    frameStamp, halfMode != 0 ? frameInflateK() : 0f,
                    halfMode != 0 ? frameNearR() : 0f, halfMode != 0 ? regionDiagBase() : 0);
            EXTMeshShader.vkCmdDrawMeshTasksEXT(cb, drawCount, 1, 1);
        }
    }

    /**
     * Pass 3 on the Sodium host: section boxes of region-raster-visible
     * regions. The task stage launches {@code popcount} mesh workgroups
     * per stamped list entry; each maps its rank to the k-th occupied slot
     * over the mirror row bound at binding 5 (the row slice: the mirror at
     * {@code rowsOffset}, range {@code capacity * ROW_BYTES}) and rasters
     * the full 16-cubed box into {@code curStampsBuffer} at
     * {@code mid * 256 + slot}.
     */
    void recordSectionRasterSodium(VkCommandBuffer cb, ListSlice occList, GpuBufferSlice scene,
            GpuBufferSlice projection, long rowsVkBuffer, long rowsOffset, long rowsRange,
            int drawCount, int frameStamp, long curStampsBuffer, int halfMode) {
        if (drawCount <= 0) {
            return;
        }
        long pipeline = halfMode == HALF_RES_ARM_FLAT ? sectionPipelineSodiumFlat
                : halfMode == HALF_RES_ARM_BIAS ? sectionPipelineSodiumHalf : sectionPipelineSodium;
        if (pipeline == 0L) {
            throw new IllegalStateException("Sodium-host occlusion pipelines were not created");
        }
        if (halfMode != 0) {
            halfResRasterFrames++;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(6, stack);
            bufferWrite(writes.get(0), 0, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, occList.vkBuffer(), occList.offset(), occList.range()));
            bufferWrite(writes.get(1), 1, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    sliceInfo(stack, scene));
            bufferWrite(writes.get(2), 2, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    sliceInfo(stack, projection));
            bufferWrite(writes.get(3), 3, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, curStampsBuffer, 0, VK10.VK_WHOLE_SIZE));
            bufferWrite(writes.get(4), 4, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, regionStamps.vkBuffer(), 0, VK10.VK_WHOLE_SIZE));
            // The rows slice: offset 256 satisfies every
            // minStorageBufferOffsetAlignment the spec allows (max 256).
            bufferWrite(writes.get(5), 5, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, rowsVkBuffer, rowsOffset, rowsRange));
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb,
                    VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, sectionPipelineLayoutSodium, 0, writes);
            pushStamp(cb, stack, sectionPipelineLayoutSodium,
                    EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT
                            | EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT
                            | VK10.VK_SHADER_STAGE_FRAGMENT_BIT,
                    frameStamp, halfMode != 0 ? frameInflateK() : 0f,
                    halfMode != 0 ? frameNearR() : 0f, halfMode != 0 ? sectionDiagBase() : 0);
            EXTMeshShader.vkCmdDrawMeshTasksEXT(cb, drawCount, 1, 1);
        }
    }

    // ------------------------------------------------------------------
    // Stats readback (the wave-6 download-stream consumer)
    // ------------------------------------------------------------------

    /**
     * After the frame's last Meshelium pass: copy the stats into the host
     * ring's slot for {@code statsFrame} and zero the GPU counters for the
     * next frame. Transient CB spliced via {@code encoder.execute} — the
     * MesheliumTerrainGpu transfer pattern (fills/copies with explicit
     * barriers is the one sanctioned Meshelium-barrier site; the preceding
     * pass-end barrier already made the shader atomics visible).
     */
    void recordStatsTransfer(long statsFrame) {
        if (stats == null) {
            throw new IllegalStateException("no occlusion stats buffer on the Sodium host: "
                    + "SodiumMirrorGpu.recordStatsTransfer reads the mirror header");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
            copy.get(0).srcOffset(0)
                    .dstOffset((statsFrame % STATS_RING) * STATS_BYTES)
                    .size(STATS_BYTES);
            VK10.vkCmdCopyBuffer(cb, stats.vkBuffer(), statsRing.vkBuffer(), copy);
            // Read-then-zero on the same bytes: the WAR needs an explicit
            // dependency inside our own CB.
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            VK10.vkCmdFillBuffer(cb, stats.vkBuffer(), 0, STATS_BYTES, 0);
            if (predicate != null) {
                // Queued AFTER this frame's phase B in submission order, so
                // the consume already happened; the CB-final barrier orders
                // this zero before the NEXT frame's raster marks. A quiet
                // frame therefore skips, a camera cut marks and runs - the
                // exact semantics phase B exists for.
                VK10.vkCmdFillBuffer(cb, predicate.vkBuffer(), 0, 4L, 0);
            }
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            checkVk(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer(occlusion stats)");
            encoder.execute(cb);
        }
    }

    /**
     * Read the ring slot of {@code statsFrame} (call with the current
     * stats frame minus {@link #READBACK_LAG}; returns null when that is
     * negative). Safe without a fence by the FREE_FRAME_LAG argument: the
     * copy was recorded ≥3 frames ago and vanilla's 2-submits-in-flight
     * throttle guarantees its submission completed before the CPU got
     * here; the memory is HOST_COHERENT.
     */
    int[] readStats(long statsFrame) {
        if (statsFrame < 0 || statsRing == null) {
            return null;
        }
        long base = statsRing.mappedAddress() + (statsFrame % STATS_RING) * STATS_BYTES;
        ByteBuffer slot = MemoryUtil.memByteBuffer(base, STATS_BYTES)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        return new int[] {slot.getInt(0), slot.getInt(4), slot.getInt(8), slot.getInt(12)};
    }

    // ------------------------------------------------------------------
    // NEXT (c): the TEST-ONLY visible-set readback
    //
    // WHY IT IS NOT OPTIONAL FOR THE PROOF. 97_18's superset leg used to be
    // a SUM of counters, and a sum can hide the only failure that matters:
    // half-res re-admits a handful of edge sections BY CONSTRUCTION while
    // dropping one hidden section, the two cancel, and the bound passes
    // over a hole that persists at a static pose (phase A never redraws an
    // unstamped section). A SET INCLUSION cannot be fooled that way.
    //
    // WHY IT IS TEST-ONLY. At the Sodium host's CAPACITY_INITIAL = 1024 a
    // slot is 1 MiB, the ring 8 MiB, and the fold a pass over 262,144 ints
    // EVERY owned frame on a client rendering hundreds of them a second.
    // The property is therefore set only in the two suite runs whose 97_18
    // needs a set and in the standalone pair - never in a bench, never in a
    // latency leg - and beginVisibleSetCapture/end narrow it further.
    // ------------------------------------------------------------------

    /** True when this instance was created under the readback property. */
    boolean visibleSetCaptureAvailable() {
        return stampsRing != null;
    }

    /**
     * Re-open the per-frame copy and fold. Default OPEN, so a leg that just
     * sets the property gets what it asks for; a leg that wants the cost
     * confined to its own sample window closes it first.
     */
    void beginVisibleSetCapture() {
        captureOpen = true;
    }

    void endVisibleSetCapture() {
        captureOpen = false;
    }

    /**
     * After the frame's last Meshelium pass, beside the stats copy: copy
     * this frame's stamps into the ring slot, copy and then ZERO the two
     * {@code forcedNear} pairs. A no-op unless the readback property armed
     * the ring.
     *
     * <p>Ordering: the preceding pass-end ALL_COMMANDS barrier has already
     * made the rasters' stamp writes visible (the stats copy's own
     * argument), and phase B only READS stamps. The read-then-zero of the
     * counter pair is a WAR on the same bytes, so it takes an explicit
     * barrier inside our own transient buffer - frame N's counts are read
     * and frame N+1 starts at zero.</p>
     *
     * <p>{@code encoder.execute} throws if a render pass were open
     * ("Cannot execute command buffer while inside RenderPass"), so a
     * misplaced call here is loud, unlike a misplaced texture creation.</p>
     */
    void recordStampsTransfer(long statsFrame, long curStampsBuffer, int frameStamp) {
        if (stampsRing == null || !captureOpen || statsFrame < 0) {
            return;
        }
        int slot = (int) (statsFrame % STATS_RING);
        long dst = slot * slotBytes;
        long sectionDiagBytes = (long) stampRows * 256L * 4L;
        long regionDiagBytes = (long) regionStampSlots * 4L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(2, stack);
            copy.get(0).srcOffset(0).dstOffset(dst).size(copyBytes);
            copy.get(1).srcOffset(sectionDiagBytes).dstOffset(dst + copyBytes).size(8L);
            VK10.vkCmdCopyBuffer(cb, curStampsBuffer, stampsRing.vkBuffer(), copy);
            VkBufferCopy.Buffer regionCopy = VkBufferCopy.calloc(1, stack);
            regionCopy.get(0).srcOffset(regionDiagBytes).dstOffset(dst + copyBytes + 8L).size(8L);
            VK10.vkCmdCopyBuffer(cb, regionStamps.vkBuffer(), stampsRing.vkBuffer(), regionCopy);
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            VK10.vkCmdFillBuffer(cb, curStampsBuffer, sectionDiagBytes, 8L, 0);
            VK10.vkCmdFillBuffer(cb, regionStamps.vkBuffer(), regionDiagBytes, 8L, 0);
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            checkVk(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer(occlusion stamps)");
            encoder.execute(cb);
        }
        ringStamp[slot] = frameStamp;
        ringFrame[slot] = statsFrame;
        if (!stampsSizeLogged) {
            stampsSizeLogged = true;
            MesheliumLog.LOGGER.info(
                    "Meshelium occlusion stamps readback ARMED (test-only): {} of {} stamp rows "
                            + "copied per owned frame = {} KiB a slot, {} KiB ring, and a "
                            + "render-thread fold over {} ints EVERY owned frame. Never set this "
                            + "in a bench or a latency leg.",
                    copyRows, stampRows, copyBytes >> 10, (STATS_RING * slotBytes) >> 10,
                    copyBytes / 4L);
        }
    }

    /**
     * Fold the ring slot of {@code readFrame} into a visible set, ON THE
     * RENDER THREAD, from {@code pullStats} / {@code pullGpuStats} - the
     * way every other per-frame probe here works.
     *
     * <p>Plan revision 3, objection 4: the earlier design had the SUITE
     * thread read a ring slot by frame id. The slot for stats frame f is
     * rewritten and retagged when frame f+8 is RECORDED, and the suite
     * reaches it through a readback wait, a 100 ms poll loop and a
     * {@code runOnClient} round trip while the client renders hundreds of
     * owned frames a second, so the slot was retagged or mid-copy by the
     * time the leg looked: flaky-to-always-red, not a proof.</p>
     *
     * <p>Why the slot is complete AND unreused here: the copy for
     * {@code readFrame} was recorded {@value #READBACK_LAG} owned frames
     * ago and its submission completed by vanilla's 2-submits-in-flight
     * throttle (the {@code readStats} argument, same index space, same
     * lag); the slot is next rewritten when frame
     * {@code readFrame + STATS_RING} is recorded, five owned frames after
     * this fold, and the fold runs on the thread that records. No other
     * frame's copy can be in flight on that slot and no host write touches
     * the ring, so there is no torn read.</p>
     */
    void foldVisibleSet(long readFrame) {
        if (stampsRing == null || readFrame < 0) {
            return;
        }
        int slot = (int) (readFrame % STATS_RING);
        if (ringFrame[slot] != readFrame) {
            return; // never copied, or already overwritten
        }
        long base = stampsRing.mappedAddress() + slot * slotBytes;
        ByteBuffer buf = MemoryUtil.memByteBuffer(base, (int) slotBytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int words = copyRows * 256;
        int stamp = ringStamp[slot];
        BitSet bits = new BitSet(words);
        for (int i = 0; i < words; i++) {
            if (buf.getInt(i * 4) == stamp) {
                bits.set(i);
            }
        }
        int tail = (int) copyBytes;
        int sectionWide = buf.getInt(tail);
        int sectionPlain = buf.getInt(tail + 4);
        int regionWide = buf.getInt(tail + 8);
        int regionPlain = buf.getInt(tail + 12);
        lastVisibleSet = new VisibleSetSample(readFrame, bits,
                regionWide, regionPlain, sectionWide, sectionPlain);
    }

    /**
     * The most recently folded visible set, or null when the readback is
     * off or nothing has been folded yet. Takes no argument on purpose: the
     * caller must not be able to ask for a frame whose slot has moved on.
     */
    VisibleSetSample debugVisibleSet() {
        return lastVisibleSet;
    }

    /**
     * Wave-8: destroy the static (device-lifetime) pipeline objects.
     * Called at device close, after vanilla's encoder destroy (queue idle,
     * VkDevice still valid). Nulls the cache so a fresh device would
     * rebuild lazily. No-op when the pipelines were never built.
     */
    static void destroyPipelines(VkDevice vk) {
        if (regionPipeline != 0L) {
            VK10.vkDestroyPipeline(vk, regionPipeline, null);
            regionPipeline = 0L;
        }
        if (regionPipelineLayout != 0L) {
            VK10.vkDestroyPipelineLayout(vk, regionPipelineLayout, null);
            regionPipelineLayout = 0L;
        }
        if (regionSetLayout != 0L) {
            VK10.vkDestroyDescriptorSetLayout(vk, regionSetLayout, null);
            regionSetLayout = 0L;
        }
        if (sectionPipeline != 0L) {
            VK10.vkDestroyPipeline(vk, sectionPipeline, null);
            sectionPipeline = 0L;
        }
        if (sectionPipelineLayout != 0L) {
            VK10.vkDestroyPipelineLayout(vk, sectionPipelineLayout, null);
            sectionPipelineLayout = 0L;
        }
        if (sectionSetLayout != 0L) {
            VK10.vkDestroyDescriptorSetLayout(vk, sectionSetLayout, null);
            sectionSetLayout = 0L;
        }
        // Wave-10 extended-lists variants (may never have been built).
        if (regionPipelineExt != 0L) {
            VK10.vkDestroyPipeline(vk, regionPipelineExt, null);
            regionPipelineExt = 0L;
        }
        if (regionPipelineLayoutExt != 0L) {
            VK10.vkDestroyPipelineLayout(vk, regionPipelineLayoutExt, null);
            regionPipelineLayoutExt = 0L;
        }
        if (regionSetLayoutExt != 0L) {
            VK10.vkDestroyDescriptorSetLayout(vk, regionSetLayoutExt, null);
            regionSetLayoutExt = 0L;
        }
        if (sectionPipelineExt != 0L) {
            VK10.vkDestroyPipeline(vk, sectionPipelineExt, null);
            sectionPipelineExt = 0L;
        }
        if (sectionPipelineLayoutExt != 0L) {
            VK10.vkDestroyPipelineLayout(vk, sectionPipelineLayoutExt, null);
            sectionPipelineLayoutExt = 0L;
        }
        if (sectionSetLayoutExt != 0L) {
            VK10.vkDestroyDescriptorSetLayout(vk, sectionSetLayoutExt, null);
            sectionSetLayoutExt = 0L;
        }
        // D-023 Sodium-host variants (may never have been built).
        if (regionPipelineSodium != 0L) {
            VK10.vkDestroyPipeline(vk, regionPipelineSodium, null);
            regionPipelineSodium = 0L;
        }
        if (regionPipelineLayoutSodium != 0L) {
            VK10.vkDestroyPipelineLayout(vk, regionPipelineLayoutSodium, null);
            regionPipelineLayoutSodium = 0L;
        }
        if (regionSetLayoutSodium != 0L) {
            VK10.vkDestroyDescriptorSetLayout(vk, regionSetLayoutSodium, null);
            regionSetLayoutSodium = 0L;
        }
        if (sectionPipelineSodium != 0L) {
            VK10.vkDestroyPipeline(vk, sectionPipelineSodium, null);
            sectionPipelineSodium = 0L;
        }
        if (sectionPipelineLayoutSodium != 0L) {
            VK10.vkDestroyPipelineLayout(vk, sectionPipelineLayoutSodium, null);
            sectionPipelineLayoutSodium = 0L;
        }
        if (sectionSetLayoutSodium != 0L) {
            VK10.vkDestroyDescriptorSetLayout(vk, sectionSetLayoutSodium, null);
            sectionSetLayoutSodium = 0L;
        }
        // NEXT (c): the twelve half-res pipelines (handles only - their set
        // and pipeline layouts are the full-res sets', destroyed above) and
        // pass D's own three objects. Every one may never have been built.
        long[] halfPipelines = {
                regionPipelineFlat, sectionPipelineFlat,
                regionPipelineExtFlat, sectionPipelineExtFlat,
                regionPipelineSodiumFlat, sectionPipelineSodiumFlat,
                regionPipelineHalf, sectionPipelineHalf,
                regionPipelineExtHalf, sectionPipelineExtHalf,
                regionPipelineSodiumHalf, sectionPipelineSodiumHalf};
        for (long p : halfPipelines) {
            if (p != 0L) {
                VK10.vkDestroyPipeline(vk, p, null);
            }
        }
        regionPipelineFlat = 0L;
        sectionPipelineFlat = 0L;
        regionPipelineExtFlat = 0L;
        sectionPipelineExtFlat = 0L;
        regionPipelineSodiumFlat = 0L;
        sectionPipelineSodiumFlat = 0L;
        regionPipelineHalf = 0L;
        sectionPipelineHalf = 0L;
        regionPipelineExtHalf = 0L;
        sectionPipelineExtHalf = 0L;
        regionPipelineSodiumHalf = 0L;
        sectionPipelineSodiumHalf = 0L;
        if (downsamplePipeline != 0L) {
            VK10.vkDestroyPipeline(vk, downsamplePipeline, null);
            downsamplePipeline = 0L;
        }
        if (downsamplePipelineLayout != 0L) {
            VK10.vkDestroyPipelineLayout(vk, downsamplePipelineLayout, null);
            downsamplePipelineLayout = 0L;
        }
        if (downsampleSetLayout != 0L) {
            VK10.vkDestroyDescriptorSetLayout(vk, downsampleSetLayout, null);
            downsampleSetLayout = 0L;
        }
    }

    /**
     * Wave-8 defensive teardown: destroy the per-world buffers DIRECTLY,
     * bypassing the deferred-destroy queue — only legal at device close
     * (after vanilla's {@code waitIdle}, when the destroy queue itself is
     * already drained and closed). The normal path is {@link #destroy()}.
     */
    void destroyNow() {
        // NEXT (c): the half-res attachments go DIRECTLY too - the deferred
        // rotation is already drained at device close.
        if (halfTarget != null) {
            halfTarget.destroyNow();
            halfTarget = null;
        }
        if (stampsRing != null) {
            MesheliumVkBuffers.destroy(vma, stampsRing.vkBuffer(), stampsRing.allocation());
        }
        MesheliumVkBuffers.destroy(vma, sectionStampsA.vkBuffer(), sectionStampsA.allocation());
        MesheliumVkBuffers.destroy(vma, sectionStampsB.vkBuffer(), sectionStampsB.allocation());
        MesheliumVkBuffers.destroy(vma, regionStamps.vkBuffer(), regionStamps.allocation());
        if (stats != null) {
            MesheliumVkBuffers.destroy(vma, stats.vkBuffer(), stats.allocation());
        }
        if (statsRing != null) {
            MesheliumVkBuffers.destroy(vma, statsRing.vkBuffer(), statsRing.allocation());
        }
        if (predicate != null) {
            MesheliumVkBuffers.destroy(vma, predicate.vkBuffer(), predicate.allocation());
        }
    }

    /** Queue every per-world buffer on vanilla's deferred-destroy rotation. */
    void destroy() {
        // NEXT (c): the half-res pair rides the same deferred rotation
        // (VulkanGpuTextureView.close queues itself on the encoder), and it
        // dies WITH this instance - which is exactly why 97_13 can assert
        // that halfResAllocations moves with occlusionRecreates.
        if (halfTarget != null) {
            halfTarget.destroy();
            halfTarget = null;
        }
        long vmaHandle = this.vma;
        MesheliumVkBuffers.MappedBuffer stampRing = stampsRing;
        MesheliumVkBuffers.DeviceBuffer a = sectionStampsA;
        MesheliumVkBuffers.DeviceBuffer b = sectionStampsB;
        MesheliumVkBuffers.DeviceBuffer region = regionStamps;
        MesheliumVkBuffers.DeviceBuffer st = stats;
        MesheliumVkBuffers.MappedBuffer ring = statsRing;
        MesheliumVkBuffers.DeviceBuffer pred = predicate;
        encoder.queueForDestroy(() -> {
            MesheliumVkBuffers.destroy(vmaHandle, a.vkBuffer(), a.allocation());
            MesheliumVkBuffers.destroy(vmaHandle, b.vkBuffer(), b.allocation());
            MesheliumVkBuffers.destroy(vmaHandle, region.vkBuffer(), region.allocation());
            if (st != null) {
                MesheliumVkBuffers.destroy(vmaHandle, st.vkBuffer(), st.allocation());
            }
            if (ring != null) {
                MesheliumVkBuffers.destroy(vmaHandle, ring.vkBuffer(), ring.allocation());
            }
            if (pred != null) {
                MesheliumVkBuffers.destroy(vmaHandle, pred.vkBuffer(), pred.allocation());
            }
            if (stampRing != null) {
                MesheliumVkBuffers.destroy(vmaHandle, stampRing.vkBuffer(), stampRing.allocation());
            }
        });
    }

    // ------------------------------------------------------------------
    // Vulkan plumbing
    // ------------------------------------------------------------------

    private static long createSetLayout(VkDevice vk, MemoryStack stack,
            VkDescriptorSetLayoutBinding.Buffer bindings, String what) {
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                .pBindings(bindings);
        LongBuffer handle = stack.mallocLong(1);
        checkVk(VK10.vkCreateDescriptorSetLayout(vk, info, null, handle),
                "vkCreateDescriptorSetLayout(occlusion " + what + ")");
        return handle.get(0);
    }

    private static long createPipelineLayout(VkDevice vk, MemoryStack stack, long setLayout,
            int pushStages, String what) {
        VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
        range.get(0).stageFlags(pushStages).offset(0).size(16);
        VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(stack.longs(setLayout))
                .setLayoutCount(1)
                .pPushConstantRanges(range);
        LongBuffer handle = stack.mallocLong(1);
        checkVk(VK10.vkCreatePipelineLayout(vk, info, null, handle),
                "vkCreatePipelineLayout(occlusion " + what + ")");
        return handle.get(0);
    }

    /**
     * @param half true for a half-resolution variant
     * @param flat true for the FLAT depth arm (the sound one). {@code half
     *        && !flat} is the BIAS COMPARATOR and is the only combination
     *        that turns depth bias on; {@code flat} and full-res both leave
     *        the rasterization state byte-for-byte as it was, which is what
     *        keeps the FLAT set identical to the shipped sets in everything
     *        but its modules.
     */
    private static long buildBoxPipeline(VkDevice vk, MemoryStack stack, int vkColorFormat,
            int vkDepthFormat, long pipelineLayout, long taskModule, long meshModule,
            long fragModule, String what, boolean half, boolean flat) {
        VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType$Default()
                .viewportCount(1)
                .scissorCount(1);

        VkPipelineRasterizationStateCreateInfo rasterState = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType$Default()
                .depthClampEnable(false)
                .rasterizerDiscardEnable(false)
                .polygonMode(VK10.VK_POLYGON_MODE_FILL)
                .cullMode(VK10.VK_CULL_MODE_NONE)          // both box faces raster (fail-open)
                .frontFace(VK10.VK_FRONT_FACE_CLOCKWISE)
                .depthBiasEnable(false)
                .lineWidth(1.0f);
        if (half && !flat) {
            // Measurement-only comparator; sound only where z + o <= 1
            // (Vulkan: z_f undefined outside [z_min, z_max] without depth
            // clamping - fragops.adoc). depthBiasClamp(0.0f) is MANDATORY,
            // not a nicety: the depthBiasClamp FEATURE is VK_FALSE on
            // vanilla's device, so any other value is a VUID.
            rasterState.depthBiasEnable(true)
                    .depthBiasConstantFactor(2.0f)
                    .depthBiasClamp(0.0f)
                    .depthBiasSlopeFactor(2.0f);
        }
        if (conservativeRasterActive()) {
            // Part B (boot-time lever): overestimate mode emits a fragment
            // for every pixel a primitive touches, closing the COVERAGE
            // hole without the inflation. Additive, never load-bearing -
            // the extension may be absent on a device, so nothing above
            // depends on it. Its extra fragments' depth is the plane
            // extrapolated to the pixel centre, which the FLAT arm makes
            // moot (a constant extrapolates to itself).
            rasterState.pNext(VkPipelineRasterizationConservativeStateCreateInfoEXT.calloc(stack)
                    .sType$Default()
                    .flags(0)
                    .conservativeRasterizationMode(EXTConservativeRasterization
                            .VK_CONSERVATIVE_RASTERIZATION_MODE_OVERESTIMATE_EXT)
                    .extraPrimitiveOverestimationSize(0.0f));
        }

        VkPipelineMultisampleStateCreateInfo multisampleState = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType$Default()
                .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT)
                .sampleShadingEnable(false);

        // Reversed-Z: box passes where it is at least as NEAR as the
        // phase-A terrain depth; never writes depth.
        VkPipelineDepthStencilStateCreateInfo depthState = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType$Default()
                .depthTestEnable(true)
                .depthWriteEnable(false)
                .depthCompareOp(VK10.VK_COMPARE_OP_GREATER_OR_EQUAL);

        // Color attachment present (vanilla pass shape) but writes fully
        // masked — see ensurePipelines javadoc.
        VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                VkPipelineColorBlendAttachmentState.calloc(1, stack);
        blendAttachment.get(0)
                .blendEnable(false)
                .colorWriteMask(0);
        VkPipelineColorBlendStateCreateInfo blendState = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType$Default()
                .logicOpEnable(false)
                .pAttachments(blendAttachment)
                .attachmentCount(1);

        VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType$Default()
                .pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_SCISSOR, VK10.VK_DYNAMIC_STATE_VIEWPORT));

        VkPipelineRenderingCreateInfoKHR renderingInfo = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                .sType$Default()
                .colorAttachmentCount(1)
                .pColorAttachmentFormats(stack.ints(vkColorFormat))
                .depthAttachmentFormat(vkDepthFormat);

        int stageCount = taskModule != 0L ? 3 : 2;
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(stageCount, stack);
        int si = 0;
        if (taskModule != 0L) {
            stages.get(si++)
                    .sType$Default()
                    .stage(EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT)
                    .module(taskModule)
                    .pName(stack.UTF8("main"));
        }
        stages.get(si++)
                .sType$Default()
                .stage(EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT)
                .module(meshModule)
                .pName(stack.UTF8("main"));
        stages.get(si)
                .sType$Default()
                .stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                .module(fragModule)
                .pName(stack.UTF8("main"));

        VkGraphicsPipelineCreateInfo.Buffer createInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
        createInfo.get(0)
                .sType$Default()
                .pNext(renderingInfo.address())
                .pStages(stages)
                .stageCount(stageCount)
                .pViewportState(viewportState)
                .pRasterizationState(rasterState)
                .pMultisampleState(multisampleState)
                .pDepthStencilState(depthState)
                .pColorBlendState(blendState)
                .pDynamicState(dynamicState)
                .layout(pipelineLayout)
                .renderPass(0L); // dynamic rendering

        LongBuffer pipelines = stack.mallocLong(1);
        checkVk(VK10.vkCreateGraphicsPipelines(vk, 0L, createInfo, null, pipelines),
                "vkCreateGraphicsPipelines(meshelium occlusion " + what + ")");
        return pipelines.get(0);
    }

    /**
     * NEXT (c) pass D. {@link #buildBoxPipeline} with exactly these
     * differences: no task stage (the two-stage path), depth compare ALWAYS
     * with WRITE ON (a write needs the test enabled; the fragment supplies
     * the value), and no depth bias ever. Everything else - cull NONE, one
     * mask-0 colour attachment, dynamic viewport and scissor, the SAME two
     * attachment formats, dynamic rendering - is identical, because the
     * pass is shape B and the half attachments carry the main target's
     * formats.
     */
    private static long buildDownsamplePipeline(VkDevice vk, MemoryStack stack, int vkColorFormat,
            int vkDepthFormat, long pipelineLayout, long meshModule, long fragModule) {
        VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType$Default()
                .viewportCount(1)
                .scissorCount(1);

        VkPipelineRasterizationStateCreateInfo rasterState = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType$Default()
                .depthClampEnable(false)
                .rasterizerDiscardEnable(false)
                .polygonMode(VK10.VK_POLYGON_MODE_FILL)
                .cullMode(VK10.VK_CULL_MODE_NONE)
                .frontFace(VK10.VK_FRONT_FACE_CLOCKWISE)
                .depthBiasEnable(false)
                .lineWidth(1.0f);

        VkPipelineMultisampleStateCreateInfo multisampleState = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType$Default()
                .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT)
                .sampleShadingEnable(false);

        VkPipelineDepthStencilStateCreateInfo depthState = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType$Default()
                .depthTestEnable(true)
                .depthWriteEnable(true)
                .depthCompareOp(VK10.VK_COMPARE_OP_ALWAYS);

        VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                VkPipelineColorBlendAttachmentState.calloc(1, stack);
        blendAttachment.get(0)
                .blendEnable(false)
                .colorWriteMask(0);
        VkPipelineColorBlendStateCreateInfo blendState = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType$Default()
                .logicOpEnable(false)
                .pAttachments(blendAttachment)
                .attachmentCount(1);

        VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType$Default()
                .pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_SCISSOR, VK10.VK_DYNAMIC_STATE_VIEWPORT));

        VkPipelineRenderingCreateInfoKHR renderingInfo = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                .sType$Default()
                .colorAttachmentCount(1)
                .pColorAttachmentFormats(stack.ints(vkColorFormat))
                .depthAttachmentFormat(vkDepthFormat);

        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0)
                .sType$Default()
                .stage(EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT)
                .module(meshModule)
                .pName(stack.UTF8("main"));
        stages.get(1)
                .sType$Default()
                .stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                .module(fragModule)
                .pName(stack.UTF8("main"));

        VkGraphicsPipelineCreateInfo.Buffer createInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
        createInfo.get(0)
                .sType$Default()
                .pNext(renderingInfo.address())
                .pStages(stages)
                .stageCount(2)
                .pViewportState(viewportState)
                .pRasterizationState(rasterState)
                .pMultisampleState(multisampleState)
                .pDepthStencilState(depthState)
                .pColorBlendState(blendState)
                .pDynamicState(dynamicState)
                .layout(pipelineLayout)
                .renderPass(0L); // dynamic rendering

        LongBuffer pipelines = stack.mallocLong(1);
        checkVk(VK10.vkCreateGraphicsPipelines(vk, 0L, createInfo, null, pipelines),
                "vkCreateGraphicsPipelines(meshelium occlusion depth downsample)");
        return pipelines.get(0);
    }

    /**
     * The 16-byte push block both rasters share. Layout, and it is the
     * layout the shaders declare: FrameStamp 0, InflateK 4, NearR 8,
     * DiagBase 12. A full-res call passes 0f, 0f, 0 and the block is
     * byte-identical to the one-field version, which is why the full-res
     * SPIR-V never moved.
     */
    private static void pushStamp(VkCommandBuffer cb, MemoryStack stack, long layout,
            int stages, int frameStamp, float inflateK, float nearR, int diagBase) {
        ByteBuffer push = stack.calloc(16);
        push.putInt(0, frameStamp);
        push.putFloat(4, inflateK);
        push.putFloat(8, nearR);
        push.putInt(12, diagBase);
        VK10.vkCmdPushConstants(cb, layout, stages, 0, push);
    }

    private static VkDescriptorBufferInfo.Buffer sliceInfo(MemoryStack stack, GpuBufferSlice slice) {
        return bufferInfo(stack, ((VulkanGpuBuffer) slice.buffer()).vkBuffer(),
                slice.offset(), slice.length());
    }

    private static VkDescriptorBufferInfo.Buffer bufferInfo(MemoryStack stack, long vkBuffer,
            long offset, long range) {
        VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
        info.get(0).buffer(vkBuffer).offset(offset).range(range);
        return info;
    }

    private static void bufferWrite(VkWriteDescriptorSet write, int binding, int type,
            VkDescriptorBufferInfo.Buffer info) {
        write.sType$Default()
                .dstBinding(binding)
                .descriptorCount(1)
                .descriptorType(type)
                .pBufferInfo(info);
    }

    private static void binding(VkDescriptorSetLayoutBinding b, int index, int type, int stages) {
        b.binding(index)
                .descriptorType(type)
                .descriptorCount(1)
                .stageFlags(stages);
    }

    private static void checkVk(int result, String what) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: VkResult " + result);
        }
    }
}
