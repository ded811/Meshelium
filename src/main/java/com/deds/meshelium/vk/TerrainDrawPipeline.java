/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
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

import java.nio.LongBuffer;
import java.util.Map;

/**
 * The opaque-terrain pipeline — two variants since wave 5, one graphics
 * pipeline each, plus Meshelium's OWN descriptor-set layout and pipeline
 * layout per variant (vanilla's bind-group layouts hardcode
 * {@code stageFlags = VERTEX|FRAGMENT} and can never serve mesh/task
 * stages, frame-path doc Q4.1):
 *
 * <ul>
 *   <li><b>taskCull = true</b> (wave-5 default): task+mesh+fragment.
 *       terrain.task culls per section on the GPU and launches mesh
 *       workgroups through the payload; the drawer records one draw per
 *       region.</li>
 *   <li><b>taskCull = false</b> (the {@code meshelium.terrainDraw.cpuCull}
 *       escape hatch): the wave-4 mesh+fragment shape, byte-identical
 *       behaviour — CPU frustum per section, one draw per contiguous quad
 *       run. Kept compilable for A/B debugging of culling bugs.</li>
 *   <li><b>translucent</b> (wave 7, {@link #createTranslucent}): the BLEND
 *       variant — mesh+fragment, ONE workgroup per draw with a per-thread
 *       quad loop (draw order = API order, sidestepping the UNVERIFIED
 *       VK_EXT_mesh_shader inter-workgroup primitive-order guarantee),
 *       blend/depth-write state copied verbatim from vanilla's
 *       TRANSLUCENT_TERRAIN pipeline (citations at the blend branch in
 *       {@code build}). Bindings 0-6 as the cpu variant, plus 7 = section
 *       records and 8 = CUR stamp buffer, both MESH stage — the occlusion
 *       gate. Push constants (MESH): {@code vec3 OriginRelCamera;
 *       uint FirstQuad; uint QuadCount; uint GateIndex (posKey<<20 |
 *       regionId*256+slot, 0xFFFFFFFF = no gate); uint FrameStamp}.</li>
 * </ul>
 *
 * <p>Every fixed-function convention copies vanilla's
 * {@code VulkanRenderPipeline.compile} (Q4.2) so the pipeline can live in a
 * pass over vanilla's attachments: dynamic states exactly
 * {@code {SCISSOR, VIEWPORT}}, single-sample, dynamic rendering with the
 * formats read off the live attachment views; depth GEQUAL write-ON
 * (reversed-Z), cull BACK, front face CLOCKWISE, no blending — the wave-4
 * citations for each carry over unchanged.</p>
 *
 * <h2>Descriptor-set layout (set 0, push descriptors)</h2>
 * <pre>
 * 0  STORAGE_BUFFER          MESH           terrain vertex arena (std430 uvec4[])
 * 1  UNIFORM_BUFFER          TASK|MESH|FRAG MesheliumScene {mat4; vec4; vec4[6] planes; ivec4 camChunk}
 * 2  UNIFORM_BUFFER          MESH           vanilla Projection slice
 * 3  UNIFORM_BUFFER          FRAGMENT       vanilla Fog slice
 * 4  UNIFORM_BUFFER          FRAGMENT       vanilla Globals buffer (UseRgss)
 * 5  COMBINED_IMAGE_SAMPLER  FRAGMENT       block atlas + vanilla's chunk sampler
 * 6  COMBINED_IMAGE_SAMPLER  MESH           lightmap + clamped-linear sampler
 * --- taskCull variant only ---
 * 7  STORAGE_BUFFER          TASK           section records (32 B × 256 × region)
 * 8  UNIFORM_BUFFER          TASK           per-frame visibility masks (16 KiB slice;
 *                                           dummy slice on occlusion frames)
 * 9  STORAGE_BUFFER          TASK           wave-6 PREV section stamps (ping-pong;
 *                                           dummy SSBO on bfs/cpu frames)
 * 10 STORAGE_BUFFER          TASK           wave-6 CUR section stamps
 * 11 STORAGE_BUFFER          TASK           wave-6 occlusion stats (4 uints)
 * </pre>
 * (Binding 1's TASK flag exists only on the task variant; the cpu variant
 * keeps the wave-4 MESH|FRAGMENT flags. Bindings 9-11 are statically
 * referenced by terrain.task, so every task-mode draw must push SOMETHING
 * valid there — the drawer pushes the section-records buffer as a dummy
 * when occlusion resources don't exist; the shader's VisMode/StatsFlags
 * guards guarantee the dummies are never dynamically accessed.)
 *
 * <h2>Push constants</h2>
 * One range, offset 0, {@link #PUSH_BYTES} bytes; stages MESH (cpu variant,
 * wave-4 exact) or TASK|MESH (task variant). Contents:
 * {@code vec3 OriginRelCamera (0..11)} then either
 * {@code uint FirstQuad (12); uint QuadCount (16)} (cpu) or
 * {@code uint RegionIndex (12); uint MaskSlot (16); uint VisMode (20);
 * uint FrameStamp (24); uint StatsFlags (28)} (task — VisMode 0 = BFS mask
 * feed, 1 = occlusion phase A, 2 = occlusion phase B; layout in
 * terrain.task).
 *
 * <h2>Workgroup shapes and caps</h2>
 * {@code MESHELIUM_WG_SIZE} quads per mesh workgroup (default 32 → 128
 * vertices / 64 primitives) and {@code MESHELIUM_TASK_WG_SIZE} sections per
 * task workgroup (default 32 → one invocation per section, payload
 * {@link #PAYLOAD_BYTES_PER_SECTION} × 32 = 2304 B) — both host-injected
 * shaderc macros, both asserted against the REAL device's
 * {@code VkPhysicalDeviceMeshShaderPropertiesEXT} from the wave-1 caps
 * probe at creation. Since wave 9 both are LIVE tuning knobs:
 * {@code meshelium.tune.meshWorkgroupQuads} / {@code
 * meshelium.tune.taskWorkgroupSections}, resolved once per session by
 * {@code TerrainDrawer} (ceilings + pixel-safety arguments on the
 * property javadocs there). Dispatch-shape rationale lives in
 * docs/VANILLA-FRAME-PATH.md wave-5 notes.
 *
 * <p>Destroy debt DISCHARGED (wave 8): the three handles are destroyed via
 * {@link #destroy} from {@code TerrainDrawer.destroyDeviceObjects}, called
 * by the {@code VulkanDevice.close()} hook after vanilla's encoder destroy
 * (queue idle, device still valid — the {@code MesheliumDeviceTeardown}
 * ordering argument).</p>
 */
public final class TerrainDrawPipeline {

    /** VK_COLOR_COMPONENT_{R,G,B,A}_BIT. */
    private static final int COLOR_WRITE_ALL = 0xF;

    /** Declared push-constant range size (20 bytes used, padded). */
    public static final int PUSH_BYTES = 32;

    /**
     * sizeof(MesheliumSectionTask) in terrain.task: 2 uints + 4 uvec4 = 72 B
     * (std430-tight — scalars first, then the 16-byte-aligned vectors, no
     * padding at this member order... payload blocks are laid out like
     * shared memory; 72 assumes tight packing, and the assertion below
     * uses 80 B/section to stay safe against 16-byte struct rounding).
     */
    static final int PAYLOAD_BYTES_PER_SECTION = 80;

    private final long setLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final int workgroupQuads;
    private final boolean taskCull;
    private final boolean translucent;
    private final boolean extendedLists;
    private final int transQuadCapacity;

    private TerrainDrawPipeline(long setLayout, long pipelineLayout, long pipeline,
            int workgroupQuads, boolean taskCull, boolean translucent, boolean extendedLists,
            int transQuadCapacity) {
        this.setLayout = setLayout;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.workgroupQuads = workgroupQuads;
        this.taskCull = taskCull;
        this.translucent = translucent;
        this.extendedLists = extendedLists;
        this.transQuadCapacity = transQuadCapacity;
    }

    public long pipeline() {
        return pipeline;
    }

    public long pipelineLayout() {
        return pipelineLayout;
    }

    public long setLayout() {
        return setLayout;
    }

    /** Quads per mesh workgroup this pipeline was compiled for. */
    public int workgroupQuads() {
        return workgroupQuads;
    }

    /** True = wave-5 task-culling variant; false = wave-4 cpuCull variant. */
    public boolean taskCull() {
        return taskCull;
    }

    /** True = the wave-7 blend variant (mesh+frag, one WG per draw). */
    public boolean translucent() {
        return translucent;
    }

    /**
     * Wave-10: true = the extended-render-distance variant — binding 8
     * (the per-frame visibility masks) is a STORAGE buffer read from
     * Meshelium's own host-visible ring instead of a 16 KiB transient UBO
     * slice (shader arrays unsized ⇒ one variant serves any pinned
     * capacity). Task variant only; the drawer must push binding 8 with
     * the matching descriptor type.
     */
    public boolean extendedLists() {
        return extendedLists;
    }

    /**
     * Wave-7: max quads one translucent draw may carry (= the shader's
     * MESHELIUM_TRANS_QUADS — one workgroup emits the whole draw, threads
     * loop). 0 on non-translucent variants.
     */
    public int transQuadCapacity() {
        return transQuadCapacity;
    }

    /**
     * Wave-8: destroy the pipeline, its layout and its set layout. Only
     * legal once every submission that referenced them has completed —
     * the one caller runs after vanilla's device-close {@code waitIdle}.
     */
    public void destroy(VkDevice device) {
        VK10.vkDestroyPipeline(device, pipeline, null);
        VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
        VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
    }

    /**
     * Stage flags of the push-constant range (what vkCmdPushConstants must
     * pass): TASK|MESH for the task variant, MESH for the cpu and
     * translucent variants.
     */
    public int pushStageFlags() {
        return taskCull
                ? EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT | EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT
                : EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
    }

    /**
     * Compile the variant's shader set (with the workgroup-size macros) and
     * build the pipeline. Shader modules are destroyed right after pipeline
     * creation (spec-legal).
     *
     * @param taskWgSections sections per task workgroup (ignored as a
     *        dispatch shape by the cpu variant but still injected — the
     *        mesh source references the macro under MESHELIUM_TASK_CULL)
     * @param visMaskRegions capacity of the per-frame visibility-mask UBO
     *        in regions (the MESHELIUM_VIS_UVEC4S macro = 2×this; ignored
     *        by the extended variant, whose SSBO array is unsized)
     * @param extendedLists wave-10: compile with MESHELIUM_LISTS_SSBO=1 and
     *        declare binding 8 as a STORAGE buffer (the MesheliumFrameLists
     *        ring) instead of the 16 KiB transient UBO slice
     */
    public static TerrainDrawPipeline create(VkDevice device, int vkColorFormat, int vkDepthFormat,
            int workgroupQuads, int taskWgSections, int visMaskRegions, boolean taskCull,
            boolean extendedLists) {
        return createInternal(device, vkColorFormat, vkDepthFormat, workgroupQuads,
                taskWgSections, visMaskRegions, taskCull, extendedLists, 0);
    }

    /**
     * Wave-7 blend variant: mesh+fragment, same shader sources compiled
     * with {@code MESHELIUM_TRANSLUCENT=1} — one workgroup per draw, the
     * push constants carry the section-prefix run, the fixed-function
     * blend/depth state copies vanilla's TRANSLUCENT_TERRAIN pipeline
     * verbatim (see {@link #build}'s translucent branch for the bytecode
     * citations).
     *
     * @param transQuads quads one draw carries (MESHELIUM_TRANS_QUADS) —
     *        caller derives it from the REAL device caps
     */
    public static TerrainDrawPipeline createTranslucent(VkDevice device, int vkColorFormat,
            int vkDepthFormat, int workgroupQuads, int transQuads) {
        return createInternal(device, vkColorFormat, vkDepthFormat, workgroupQuads,
                1, 1, false, false, transQuads);
    }

    private static TerrainDrawPipeline createInternal(VkDevice device, int vkColorFormat,
            int vkDepthFormat, int workgroupQuads, int taskWgSections, int visMaskRegions,
            boolean taskCull, boolean extendedLists, int transQuads) {
        boolean translucent = transQuads > 0;
        Map<String, String> macros = Map.of(
                "MESHELIUM_WG_SIZE", Integer.toString(workgroupQuads),
                "MESHELIUM_TASK_WG_SIZE", Integer.toString(taskWgSections),
                "MESHELIUM_VIS_UVEC4S", Integer.toString(visMaskRegions * 2),
                "MESHELIUM_TASK_CULL", taskCull ? "1" : "0",
                "MESHELIUM_TRANSLUCENT", translucent ? "1" : "0",
                "MESHELIUM_TRANS_QUADS", Integer.toString(Math.max(transQuads, 1)),
                // Wave-10: flips terrain.task's binding-8 declaration to an
                // unsized read-only SSBO (extended render distance).
                "MESHELIUM_LISTS_SSBO", extendedLists ? "1" : "0");
        long taskModule = 0L;
        long meshModule = 0L;
        long fragModule = 0L;
        try {
            if (taskCull) {
                taskModule = MesheliumShaderCompiler.compileResourceToModule(device,
                        "/assets/meshelium/shaders/terrain.task", MesheliumShaderCompiler.KIND_TASK, macros);
            }
            meshModule = MesheliumShaderCompiler.compileResourceToModule(device,
                    "/assets/meshelium/shaders/terrain.mesh", MesheliumShaderCompiler.KIND_MESH, macros);
            fragModule = MesheliumShaderCompiler.compileResourceToModule(device,
                    "/assets/meshelium/shaders/terrain.frag", MesheliumShaderCompiler.KIND_FRAGMENT, macros);
            return build(device, vkColorFormat, vkDepthFormat,
                    taskModule, meshModule, fragModule, workgroupQuads, taskCull, extendedLists,
                    transQuads);
        } finally {
            if (taskModule != 0L) {
                VK10.vkDestroyShaderModule(device, taskModule, null);
            }
            if (meshModule != 0L) {
                VK10.vkDestroyShaderModule(device, meshModule, null);
            }
            if (fragModule != 0L) {
                VK10.vkDestroyShaderModule(device, fragModule, null);
            }
        }
    }

    private static TerrainDrawPipeline build(VkDevice device, int vkColorFormat, int vkDepthFormat,
            long taskModule, long meshModule, long fragModule, int workgroupQuads, boolean taskCull,
            boolean extendedLists, int transQuads) {
        boolean translucent = transQuads > 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int taskStage = EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT;
            int meshStage = EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
            int fragStage = VK10.VK_SHADER_STAGE_FRAGMENT_BIT;

            // --- descriptor set layout (see class javadoc table).
            int bindingCount = taskCull ? 12 : (translucent ? 9 : 7);
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);
            int sceneStages = taskCull ? (taskStage | meshStage | fragStage) : (meshStage | fragStage);
            binding(bindings.get(0), 0, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, meshStage);
            binding(bindings.get(1), 1, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, sceneStages);
            binding(bindings.get(2), 2, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, meshStage);
            binding(bindings.get(3), 3, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, fragStage);
            binding(bindings.get(4), 4, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, fragStage);
            binding(bindings.get(5), 5, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, fragStage);
            binding(bindings.get(6), 6, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, meshStage);
            if (taskCull) {
                binding(bindings.get(7), 7, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, taskStage);
                // Wave-10: binding 8 (per-frame visibility masks) is a UBO
                // slice on the standard path, a STORAGE slice of the
                // MesheliumFrameLists ring on the extended path — must match
                // the shader's MESHELIUM_LISTS_SSBO declaration exactly.
                binding(bindings.get(8), 8, extendedLists
                        ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                        : VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, taskStage);
                // Wave-6 occlusion: prev/cur stamp buffers + stats.
                binding(bindings.get(9), 9, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, taskStage);
                binding(bindings.get(10), 10, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, taskStage);
                binding(bindings.get(11), 11, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, taskStage);
            } else if (translucent) {
                // Wave-7: the MESH-stage occlusion gate — section records
                // (identity check) + CUR stamps (this frame's raster verdict).
                binding(bindings.get(7), 7, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, meshStage);
                binding(bindings.get(8), 8, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, meshStage);
            }
            VkDescriptorSetLayoutCreateInfo setLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                    .pBindings(bindings);
            LongBuffer handle = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(device, setLayoutInfo, null, handle),
                    "vkCreateDescriptorSetLayout(terrain)");
            long setLayout = handle.get(0);

            // --- pipeline layout: set 0 + one push-constant range.
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0)
                    .stageFlags(taskCull ? (taskStage | meshStage) : meshStage)
                    .offset(0)
                    .size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(setLayout))
                    .setLayoutCount(1)
                    .pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(device, layoutInfo, null, handle),
                    "vkCreatePipelineLayout(terrain)");
            long pipelineLayout = handle.get(0);

            // --- fixed function, vanilla conventions (class javadoc).
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .viewportCount(1)
                    .scissorCount(1);

            VkPipelineRasterizationStateCreateInfo rasterState = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(VK10.VK_POLYGON_MODE_FILL)
                    .cullMode(VK10.VK_CULL_MODE_BACK_BIT)      // terrain default cull=true
                    .frontFace(VK10.VK_FRONT_FACE_CLOCKWISE)   // vanilla convention, Q4.2
                    .depthBiasEnable(false)
                    .lineWidth(1.0f);

            VkPipelineMultisampleStateCreateInfo multisampleState = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT)
                    .sampleShadingEnable(false);

            // Reversed-Z opaque terrain: GEQUAL, WRITE ON (wave-2 notes; the
            // depth we write is the frame's depth buffer from here on).
            VkPipelineDepthStencilStateCreateInfo depthState = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthTestEnable(true)
                    .depthWriteEnable(true)
                    .depthCompareOp(VK10.VK_COMPARE_OP_GREATER_OR_EQUAL);

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack);
            if (translucent) {
                // Verbatim vanilla TRANSLUCENT_TERRAIN blend state, bytecode-
                // cited (docs/VANILLA-FRAME-PATH.md wave-7 notes):
                // RenderPipelines.<clinit> ip 1347-1357 wraps
                // BlendFunction.TRANSLUCENT in a ColorTargetState (1-arg
                // ctor → writeMask 15); BlendFunction.<clinit> ip 60-79:
                // color = {SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ADD}, alpha =
                // {ONE, ONE_MINUS_SRC_ALPHA, ADD};
                // VulkanRenderPipeline.applyBlendInformation maps them via
                // VulkanConst.toVk — SRC_ALPHA→6, ONE_MINUS_SRC_ALPHA→7,
                // ONE→1, ADD→0 (the $SwitchMap assignments read out of
                // VulkanConst$1.<clinit>).
                blendAttachment.get(0)
                        .blendEnable(true)
                        .srcColorBlendFactor(VK10.VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK10.VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .alphaBlendOp(VK10.VK_BLEND_OP_ADD)
                        .colorWriteMask(COLOR_WRITE_ALL);
            } else {
                blendAttachment.get(0)
                        .blendEnable(false)
                        .colorWriteMask(COLOR_WRITE_ALL);
            }
            // NOTE the depth state above is shared deliberately: vanilla's
            // TRANSLUCENT_TERRAIN never overrides TERRAIN_SNIPPET's
            // DepthStencilState.DEFAULT = (GREATER_THAN_OR_EQUAL, write
            // TRUE) and never calls withCull — translucent terrain KEEPS
            // depth writes and BACK culling in 26.2 (RenderPipelines
            // bytecode ip 1328-1374; DepthStencilState.<clinit>).
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

            int stageCount = taskCull ? 3 : 2;
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(stageCount, stack);
            int si = 0;
            if (taskCull) {
                stages.get(si++)
                        .sType$Default()
                        .stage(taskStage)
                        .module(taskModule)
                        .pName(stack.UTF8("main"));
            }
            stages.get(si++)
                    .sType$Default()
                    .stage(meshStage)
                    .module(meshModule)
                    .pName(stack.UTF8("main"));
            stages.get(si)
                    .sType$Default()
                    .stage(fragStage)
                    .module(fragModule)
                    .pName(stack.UTF8("main"));

            VkGraphicsPipelineCreateInfo.Buffer createInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            createInfo.get(0)
                    .sType$Default()
                    .pNext(renderingInfo.address())
                    .pStages(stages)
                    .stageCount(stageCount)
                    // pVertexInputState / pInputAssemblyState stay NULL —
                    // mesh pipeline (LWJGL validate() tolerates NULL, wave-2 note)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterState)
                    .pMultisampleState(multisampleState)
                    .pDepthStencilState(depthState)
                    .pColorBlendState(blendState)
                    .pDynamicState(dynamicState)
                    .layout(pipelineLayout)
                    .renderPass(0L); // dynamic rendering

            LongBuffer pipelines = stack.mallocLong(1);
            check(VK10.vkCreateGraphicsPipelines(device, 0L, createInfo, null, pipelines),
                    "vkCreateGraphicsPipelines(meshelium terrain, taskCull=" + taskCull
                            + ", translucent=" + translucent + ")");

            return new TerrainDrawPipeline(setLayout, pipelineLayout, pipelines.get(0),
                    workgroupQuads, taskCull, translucent, extendedLists, transQuads);
        }
    }

    private static void binding(VkDescriptorSetLayoutBinding b, int index, int type, int stages) {
        b.binding(index)
                .descriptorType(type)
                .descriptorCount(1)
                .stageFlags(stages);
    }

    private static void check(int vkResult, String what) {
        if (vkResult != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: VkResult " + vkResult);
        }
    }
}
