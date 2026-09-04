/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
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

import java.nio.LongBuffer;

/**
 * The wave-2 mesh+fragment pipelines: one NDC (zero descriptors) and one
 * world-space (a single push-descriptor UBO visible to the mesh stage).
 * Built lazily on the first hello draw — the only moment the REAL
 * attachment formats are readable off {@code mainRenderTarget()}'s views
 * (docs/VANILLA-FRAME-PATH.md Q6 step 3) — and cached for the device's
 * lifetime.
 *
 * <p>Every convention here copies vanilla's {@code VulkanRenderPipeline
 * .compile} (frame-path doc Q4.2) so the pipelines can live in a pass
 * vanilla began: dynamic states exactly {@code {VIEWPORT, SCISSOR}} (the
 * pass ctor sets both, so we inherit them with zero calls), single-sample,
 * {@code frontFace = CLOCKWISE}, dynamic rendering via
 * {@code VkPipelineRenderingCreateInfoKHR} with the color+depth formats
 * passed in by the caller from the live attachments. Differences from
 * vanilla, all deliberate:</p>
 *
 * <ul>
 * <li><b>Stages are MESH_EXT + FRAGMENT</b> — no vertex input exists.
 *     {@code pVertexInputState} and {@code pInputAssemblyState} stay NULL:
 *     LWJGL 3.4.1's {@code VkGraphicsPipelineCreateInfo.validate} bytecode
 *     only validates {@code pVertexInputState} (and {@code pDynamicState})
 *     when the pointer is non-zero ({@code memGetAddress == 0 → skip}), so
 *     both members are optional to the binding; the Vulkan spec ignores
 *     both when a mesh stage is present.</li>
 * <li><b>Depth compare is {@code GREATER_OR_EQUAL}, write OFF</b> — NOT
 *     the LESS_OR_EQUAL the wave-2 brief guessed: 26.2 is reversed-Z
 *     (depth clears to 0.0 — {@code dconst_0} in LevelRenderer's clear
 *     pass — and vanilla's depth-tested pipelines use
 *     {@code CompareOp.GREATER_THAN_OR_EQUAL}, 14 sites in
 *     RenderPipelines). GEQUAL + write-off gives the brief's intent:
 *     visible against the sky (depth 0.0), occluded by near terrain,
 *     vanilla's depth buffer untouched.</li>
 * <li><b>Cull NONE</b> (vanilla culls back faces on most pipelines) — a
 *     hello triangle must not vanish over winding.</li>
 * <li>The world set layout is Meshelium's own: vanilla's bind-group layouts
 *     hardcode {@code stageFlags = VERTEX|FRAGMENT} and can never serve a
 *     mesh stage (Q4.1), so this one is created fresh with
 *     {@code stageFlags = MESH_EXT} and the push-descriptor flag —
 *     {@code VK_KHR_push_descriptor} is required-in-practice (every
 *     vanilla draw already depends on it, Q3.2).</li>
 * </ul>
 *
 * <p>Destroy debt DISCHARGED (wave 8): the five handles (2 pipelines, 2
 * pipeline layouts, 1 set layout) are destroyed via {@link #destroy} from
 * {@code HelloMeshletRenderer.destroyDeviceObjects}, called by the
 * {@code VulkanDevice.close()} hook after vanilla's encoder destroy. The
 * clearPipelineCache/resource-reload story needed NOTHING: reload clears
 * vanilla's own pipeline cache ({@code ShaderManager} → {@code
 * GpuDevice.clearPipelineCache}, jar-verified caller census), while these
 * are Meshelium-owned raw handles whose shaders load from the mod jar —
 * structurally unaffected; the wave-8 torture test pins it.</p>
 */
public final class HelloMeshletPipeline {

    /** RGBA write mask — VK_COLOR_COMPONENT_{R,G,B,A}_BIT = 1|2|4|8. */
    private static final int COLOR_WRITE_ALL = 0xF;

    private final long worldSetLayout;
    private final long ndcPipelineLayout;
    private final long worldPipelineLayout;
    private final long ndcPipeline;
    private final long worldPipeline;

    private HelloMeshletPipeline(long worldSetLayout, long ndcPipelineLayout,
            long worldPipelineLayout, long ndcPipeline, long worldPipeline) {
        this.worldSetLayout = worldSetLayout;
        this.ndcPipelineLayout = ndcPipelineLayout;
        this.worldPipelineLayout = worldPipelineLayout;
        this.ndcPipeline = ndcPipeline;
        this.worldPipeline = worldPipeline;
    }

    public long ndcPipeline() {
        return ndcPipeline;
    }

    public long worldPipeline() {
        return worldPipeline;
    }

    /** Needed by the per-frame {@code vkCmdPushDescriptorSetKHR}. */
    public long worldPipelineLayout() {
        return worldPipelineLayout;
    }

    public long worldSetLayout() {
        return worldSetLayout;
    }

    public long ndcPipelineLayout() {
        return ndcPipelineLayout;
    }

    /**
     * Wave-8: destroy all five device-lifetime handles. Only legal once
     * every submission that referenced the pipelines has completed — the
     * one caller runs after vanilla's device-close {@code waitIdle}.
     */
    public void destroy(VkDevice device) {
        VK10.vkDestroyPipeline(device, ndcPipeline, null);
        VK10.vkDestroyPipeline(device, worldPipeline, null);
        VK10.vkDestroyPipelineLayout(device, ndcPipelineLayout, null);
        VK10.vkDestroyPipelineLayout(device, worldPipelineLayout, null);
        VK10.vkDestroyDescriptorSetLayout(device, worldSetLayout, null);
    }

    /**
     * Compiles the three shaders and builds both pipelines in one
     * {@code vkCreateGraphicsPipelines} call. Shader modules are destroyed
     * immediately after pipeline creation (spec-legal: modules may be
     * destroyed once the pipelines that consumed them exist).
     *
     * @param device        {@code VulkanDevice.vkDevice()}
     * @param vkColorFormat {@code VulkanConst.toVk(colorView.texture().getFormat())}
     * @param vkDepthFormat {@code VulkanConst.toVk(depthView.texture().getFormat())}
     */
    public static HelloMeshletPipeline create(VkDevice device, int vkColorFormat, int vkDepthFormat) {
        long meshModule = MesheliumShaderCompiler.compileResourceToModule(device,
                "/assets/meshelium/shaders/hello.mesh", MesheliumShaderCompiler.KIND_MESH);
        long worldMeshModule = 0L;
        long fragModule = 0L;
        try {
            worldMeshModule = MesheliumShaderCompiler.compileResourceToModule(device,
                    "/assets/meshelium/shaders/hello_world.mesh", MesheliumShaderCompiler.KIND_MESH);
            fragModule = MesheliumShaderCompiler.compileResourceToModule(device,
                    "/assets/meshelium/shaders/hello.frag", MesheliumShaderCompiler.KIND_FRAGMENT);
            return build(device, vkColorFormat, vkDepthFormat, meshModule, worldMeshModule, fragModule);
        } finally {
            // Success or failure, the modules are no longer needed.
            VK10.vkDestroyShaderModule(device, meshModule, null);
            if (worldMeshModule != 0L) {
                VK10.vkDestroyShaderModule(device, worldMeshModule, null);
            }
            if (fragModule != 0L) {
                VK10.vkDestroyShaderModule(device, fragModule, null);
            }
        }
    }

    private static HelloMeshletPipeline build(VkDevice device, int vkColorFormat, int vkDepthFormat,
            long meshModule, long worldMeshModule, long fragModule) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // --- descriptor set layout (world variant only): one UBO,
            // mesh stage, push-descriptor flavoured (Q4.1: must be ours).
            VkDescriptorSetLayoutBinding.Buffer uboBinding = VkDescriptorSetLayoutBinding.calloc(1, stack);
            uboBinding.get(0)
                    .binding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT);
            VkDescriptorSetLayoutCreateInfo setLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                    .pBindings(uboBinding);
            LongBuffer handle = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(device, setLayoutInfo, null, handle),
                    "vkCreateDescriptorSetLayout(world UBO)");
            long worldSetLayout = handle.get(0);

            // --- pipeline layouts: NDC has NO sets (an empty pSetLayouts
            // list is legal), world has exactly set 0. No push constants,
            // matching vanilla's layout shape (Q4.1).
            VkPipelineLayoutCreateInfo ndcLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default();
            check(VK10.vkCreatePipelineLayout(device, ndcLayoutInfo, null, handle),
                    "vkCreatePipelineLayout(ndc)");
            long ndcPipelineLayout = handle.get(0);

            VkPipelineLayoutCreateInfo worldLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(worldSetLayout))
                    .setLayoutCount(1);
            check(VK10.vkCreatePipelineLayout(device, worldLayoutInfo, null, handle),
                    "vkCreatePipelineLayout(world)");
            long worldPipelineLayout = handle.get(0);

            // --- shared fixed-function state (consumed at create time, so
            // both pipelines can point at the same structs).
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .viewportCount(1)
                    .scissorCount(1); // values are dynamic — set by the pass ctor (Q3.2)

            VkPipelineRasterizationStateCreateInfo rasterState = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(VK10.VK_POLYGON_MODE_FILL)
                    .cullMode(VK10.VK_CULL_MODE_NONE)
                    .frontFace(VK10.VK_FRONT_FACE_CLOCKWISE) // vanilla convention, Q4.2
                    .depthBiasEnable(false)
                    .lineWidth(1.0f);

            VkPipelineMultisampleStateCreateInfo multisampleState = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT) // single-sample everywhere, Q1.5/Q4.2
                    .sampleShadingEnable(false);

            // Reversed-Z (see class javadoc): GEQUAL test, NO depth write.
            VkPipelineDepthStencilStateCreateInfo depthState = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthTestEnable(true)
                    .depthWriteEnable(false)
                    .depthCompareOp(VK10.VK_COMPARE_OP_GREATER_OR_EQUAL);

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAttachment.get(0)
                    .blendEnable(false)
                    .colorWriteMask(COLOR_WRITE_ALL);
            VkPipelineColorBlendStateCreateInfo blendState = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .logicOpEnable(false)
                    .pAttachments(blendAttachment)
                    .attachmentCount(1);

            // Exactly the two dynamic states vanilla pipelines declare
            // (Q4.2: stack.ints(1, 0) = {SCISSOR, VIEWPORT}); the pass sets
            // both at creation, so nothing else is owed.
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_SCISSOR, VK10.VK_DYNAMIC_STATE_VIEWPORT));

            // Dynamic rendering: formats read from the REAL attachments by
            // the caller (Q1.5 — view.texture().getFormat() + VulkanConst.toVk).
            VkPipelineRenderingCreateInfoKHR renderingInfo = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .colorAttachmentCount(1)
                    .pColorAttachmentFormats(stack.ints(vkColorFormat))
                    .depthAttachmentFormat(vkDepthFormat);

            // --- stages: mesh + fragment ONLY (task stage joins in wave 5).
            VkPipelineShaderStageCreateInfo.Buffer ndcStages =
                    stages(stack, meshModule, fragModule);
            VkPipelineShaderStageCreateInfo.Buffer worldStages =
                    stages(stack, worldMeshModule, fragModule);

            VkGraphicsPipelineCreateInfo.Buffer createInfos = VkGraphicsPipelineCreateInfo.calloc(2, stack);
            for (int i = 0; i < 2; i++) {
                createInfos.get(i)
                        .sType$Default()
                        .pNext(renderingInfo.address())
                        .pStages(i == 0 ? ndcStages : worldStages)
                        .stageCount(2)
                        // pVertexInputState / pInputAssemblyState stay NULL
                        // (mesh pipeline; see class javadoc for the LWJGL
                        // validate-bytecode citation)
                        .pViewportState(viewportState)
                        .pRasterizationState(rasterState)
                        .pMultisampleState(multisampleState)
                        .pDepthStencilState(depthState)
                        .pColorBlendState(blendState)
                        .pDynamicState(dynamicState)
                        .layout(i == 0 ? ndcPipelineLayout : worldPipelineLayout)
                        .renderPass(0L); // dynamic rendering — no VkRenderPass
            }

            LongBuffer pipelines = stack.mallocLong(2);
            check(VK10.vkCreateGraphicsPipelines(device, 0L, createInfos, null, pipelines),
                    "vkCreateGraphicsPipelines(hello ndc+world)");

            return new HelloMeshletPipeline(worldSetLayout, ndcPipelineLayout, worldPipelineLayout,
                    pipelines.get(0), pipelines.get(1));
        }
    }

    private static VkPipelineShaderStageCreateInfo.Buffer stages(MemoryStack stack,
            long meshModule, long fragModule) {
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
        return stages;
    }

    private static void check(int vkResult, String what) {
        if (vkResult != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: VkResult " + vkResult);
        }
    }
}
