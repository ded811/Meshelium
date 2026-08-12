/*
 * Meshelium — LGPL-3.0-only.
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

import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.fabric.mixin.GpuDeviceAccessor;
import com.deds.meshelium.terrain.host.TerrainResidency;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
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
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
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
 */
final class TerrainOcclusion {

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
    private final MesheliumVkBuffers.DeviceBuffer sectionStampsA;
    private final MesheliumVkBuffers.DeviceBuffer sectionStampsB;
    private final MesheliumVkBuffers.DeviceBuffer regionStamps;
    private final MesheliumVkBuffers.DeviceBuffer stats;
    private final MesheliumVkBuffers.MappedBuffer statsRing;

    private TerrainOcclusion(VulkanDevice device, VulkanCommandEncoder encoder,
            int regionStampSlots,
            MesheliumVkBuffers.DeviceBuffer sectionStampsA, MesheliumVkBuffers.DeviceBuffer sectionStampsB,
            MesheliumVkBuffers.DeviceBuffer regionStamps, MesheliumVkBuffers.DeviceBuffer stats,
            MesheliumVkBuffers.MappedBuffer statsRing) {
        this.device = device;
        this.encoder = encoder;
        this.vma = device.vma();
        this.regionStampSlots = regionStampSlots;
        this.sectionStampsA = sectionStampsA;
        this.sectionStampsB = sectionStampsB;
        this.regionStamps = regionStamps;
        this.stats = stats;
        this.statsRing = statsRing;
    }

    /**
     * Build the per-world occlusion resources on vanilla's device/VMA
     * (the MesheliumTerrainGpu.create seam). Returns null when the device
     * facade isn't up yet — the drawer falls back to the BFS feed for the
     * frame and retries. Zero-fills everything (stamp 0 can never equal a
     * live FrameStamp; the counter starts above 0).
     */
    static TerrainOcclusion create() {
        GpuDevice facade = RenderSystem.tryGetDevice();
        if (facade == null) {
            return null;
        }
        VulkanDevice device = (VulkanDevice) ((GpuDeviceAccessor) (Object) facade).meshelium$backend();
        VulkanCommandEncoder encoder = device.createCommandEncoder(); // singleton (frame-path Q1.2)
        long vma = device.vma();

        // Wave-10: both maxRegions and the per-frame list capacity come
        // from the world's pinned MesheliumScaling snapshot (2048/512 while
        // the configured max render distance is the default 32 — the
        // wave-6 sizes exactly).
        long stampBytes = (long) TerrainResidency.maxRegions() * 256L * 4L;
        int regionStampSlots = listCapacity();
        int stampUsage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        MesheliumVkBuffers.DeviceBuffer a = MesheliumVkBuffers.createDeviceLocal(vma, stampBytes,
                stampUsage, "vmaCreateBuffer(meshelium occlusion section stamps A)");
        MesheliumVkBuffers.DeviceBuffer b = MesheliumVkBuffers.createDeviceLocal(vma, stampBytes,
                stampUsage, "vmaCreateBuffer(meshelium occlusion section stamps B)");
        MesheliumVkBuffers.DeviceBuffer region = MesheliumVkBuffers.createDeviceLocal(vma,
                (long) regionStampSlots * 4L, stampUsage,
                "vmaCreateBuffer(meshelium occlusion region stamps)");
        MesheliumVkBuffers.DeviceBuffer stats = MesheliumVkBuffers.createDeviceLocal(vma, STATS_BYTES,
                stampUsage | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                "vmaCreateBuffer(meshelium occlusion stats)");
        MesheliumVkBuffers.MappedBuffer ring = MesheliumVkBuffers.createHostReadback(vma,
                (long) STATS_RING * STATS_BYTES, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                "vmaCreateBuffer(meshelium occlusion stats ring)");

        TerrainOcclusion occ = new TerrainOcclusion(device, encoder, regionStampSlots,
                a, b, region, stats, ring);
        occ.zeroInitialize(stampBytes);
        MesheliumClient.LOGGER.info(
                "Meshelium occlusion GPU state up: 2×{} KiB section stamps + {} B region stamps "
                        + "({} slots) + {} B stats (+{} B host ring), device '{}'",
                stampBytes >> 10, regionStampSlots * 4, regionStampSlots,
                STATS_BYTES, STATS_RING * STATS_BYTES,
                device.getDeviceInfo().name());
        return occ;
    }

    private void zeroInitialize(long stampBytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
            VK10.vkCmdFillBuffer(cb, sectionStampsA.vkBuffer(), 0, stampBytes, 0);
            VK10.vkCmdFillBuffer(cb, sectionStampsB.vkBuffer(), 0, stampBytes, 0);
            VK10.vkCmdFillBuffer(cb, regionStamps.vkBuffer(), 0, (long) regionStampSlots * 4L, 0);
            VK10.vkCmdFillBuffer(cb, stats.vkBuffer(), 0, STATS_BYTES, 0);
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            checkVk(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer(occlusion zero-init)");
            encoder.execute(cb);
        }
        MemoryUtil.memSet(statsRing.mappedAddress(), 0, (long) STATS_RING * STATS_BYTES);
    }

    // ------------------------------------------------------------------
    // Frame-parity stamp selection
    // ------------------------------------------------------------------

    /** The buffer THIS frame's raster writes (and phase B reads). */
    long curStampsBuffer(long frameStamp) {
        return ((frameStamp & 1L) == 0L ? sectionStampsA : sectionStampsB).vkBuffer();
    }

    /** The buffer LAST frame's raster wrote (phase A/B read). */
    long prevStampsBuffer(long frameStamp) {
        return ((frameStamp & 1L) == 0L ? sectionStampsB : sectionStampsA).vkBuffer();
    }

    long statsBuffer() {
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
        Map<String, String> macros = Map.of(
                "MESHELIUM_OCC_REGIONS", Integer.toString(MAX_OCC_REGIONS),
                "MESHELIUM_LISTS_SSBO", extendedLists ? "1" : "0");
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
                        rLayout, 0L, regionMesh, boxFrag, "region raster" + tag);

                // Section raster: b0 occList UBO|SSBO(T), b1 scene UBO(M),
                // b2 projection UBO(M), b3 curStamps SSBO(M|F),
                // b4 regionStamps SSBO(T), b5 sectionRecords SSBO(M).
                VkDescriptorSetLayoutBinding.Buffer sb = VkDescriptorSetLayoutBinding.calloc(6, stack);
                binding(sb.get(0), 0, listType, taskStage);
                binding(sb.get(1), 1, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, meshStage);
                binding(sb.get(2), 2, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, meshStage);
                binding(sb.get(3), 3, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, meshStage | fragStage);
                binding(sb.get(4), 4, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, taskStage);
                binding(sb.get(5), 5, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, meshStage);
                long sSet = createSetLayout(vk, stack, sb, "section raster" + tag);
                long sLayout = createPipelineLayout(vk, stack, sSet,
                        taskStage | meshStage | fragStage, "section raster" + tag);
                long sPipe = buildBoxPipeline(vk, stack, vkColorFormat, vkDepthFormat,
                        sLayout, sectionTask, sectionMesh, boxFrag, "section raster" + tag);

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
            MesheliumClient.LOGGER.info(
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
        }
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
            int drawCount, int frameStamp) {
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        boolean ext = occList.ssbo();
        long pipeline = ext ? regionPipelineExt : regionPipeline;
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
                    frameStamp);
            EXTMeshShader.vkCmdDrawMeshTasksEXT(cb, drawCount, 1, 1);
        }
    }

    /** Pass 3's body: section boxes of region-raster-visible regions. */
    void recordSectionRaster(VkCommandBuffer cb, ListSlice occList, GpuBufferSlice scene,
            long sectionRecordsBuffer, int drawCount, int frameStamp, long curStampsBuffer) {
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        boolean ext = occList.ssbo();
        long pipeline = ext ? sectionPipelineExt : sectionPipeline;
        long layout = ext ? sectionPipelineLayoutExt : sectionPipelineLayout;
        int listType = ext ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                : VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(6, stack);
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
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb,
                    VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, writes);
            pushStamp(cb, stack, layout,
                    EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT
                            | EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT
                            | VK10.VK_SHADER_STAGE_FRAGMENT_BIT,
                    frameStamp);
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
        if (statsFrame < 0) {
            return null;
        }
        long base = statsRing.mappedAddress() + (statsFrame % STATS_RING) * STATS_BYTES;
        ByteBuffer slot = MemoryUtil.memByteBuffer(base, STATS_BYTES)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        return new int[] {slot.getInt(0), slot.getInt(4), slot.getInt(8), slot.getInt(12)};
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
    }

    /**
     * Wave-8 defensive teardown: destroy the per-world buffers DIRECTLY,
     * bypassing the deferred-destroy queue — only legal at device close
     * (after vanilla's {@code waitIdle}, when the destroy queue itself is
     * already drained and closed). The normal path is {@link #destroy()}.
     */
    void destroyNow() {
        MesheliumVkBuffers.destroy(vma, sectionStampsA.vkBuffer(), sectionStampsA.allocation());
        MesheliumVkBuffers.destroy(vma, sectionStampsB.vkBuffer(), sectionStampsB.allocation());
        MesheliumVkBuffers.destroy(vma, regionStamps.vkBuffer(), regionStamps.allocation());
        MesheliumVkBuffers.destroy(vma, stats.vkBuffer(), stats.allocation());
        MesheliumVkBuffers.destroy(vma, statsRing.vkBuffer(), statsRing.allocation());
    }

    /** Queue every per-world buffer on vanilla's deferred-destroy rotation. */
    void destroy() {
        long vmaHandle = this.vma;
        MesheliumVkBuffers.DeviceBuffer a = sectionStampsA;
        MesheliumVkBuffers.DeviceBuffer b = sectionStampsB;
        MesheliumVkBuffers.DeviceBuffer region = regionStamps;
        MesheliumVkBuffers.DeviceBuffer st = stats;
        MesheliumVkBuffers.MappedBuffer ring = statsRing;
        encoder.queueForDestroy(() -> {
            MesheliumVkBuffers.destroy(vmaHandle, a.vkBuffer(), a.allocation());
            MesheliumVkBuffers.destroy(vmaHandle, b.vkBuffer(), b.allocation());
            MesheliumVkBuffers.destroy(vmaHandle, region.vkBuffer(), region.allocation());
            MesheliumVkBuffers.destroy(vmaHandle, st.vkBuffer(), st.allocation());
            MesheliumVkBuffers.destroy(vmaHandle, ring.vkBuffer(), ring.allocation());
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

    private static long buildBoxPipeline(VkDevice vk, MemoryStack stack, int vkColorFormat,
            int vkDepthFormat, long pipelineLayout, long taskModule, long meshModule,
            long fragModule, String what) {
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

    private static void pushStamp(VkCommandBuffer cb, MemoryStack stack, long layout,
            int stages, int frameStamp) {
        ByteBuffer push = stack.calloc(16);
        push.putInt(0, frameStamp);
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
