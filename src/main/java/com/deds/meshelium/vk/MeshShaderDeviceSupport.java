/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.MesheliumVramState;
import com.deds.meshelium.MesheliumVulkanState;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMemoryBudget;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkMemoryHeap;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMaintenance3Properties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryBudgetPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.lwjgl.vulkan.EXTConditionalRendering;
import org.lwjgl.vulkan.VkPhysicalDeviceConditionalRenderingFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDevicePushDescriptorPropertiesKHR;

import java.util.Collection;
import java.util.Set;

/**
 * The wave-1 device-creation hook body: appends {@code VK_EXT_mesh_shader}
 * and its two feature bits to vanilla's Vulkan device — iff the physical
 * device supports them — and runs the caps probe.
 *
 * <p>Everything here mirrors Mojang's own optional-extension pattern for
 * {@code VK_EXT_multi_draw} (docs/VANILLA-VULKAN-SEAM.md Q2): the same
 * {@link VulkanPNextStruct}/{@link VulkanFeature} utilities, the same
 * probe-then-append shape, and a reimplementation of the private
 * {@code VulkanBackend.isFeatureSupported} (bytecode-verified, ~10 lines —
 * reimplementing was cleaner than an invoker accessor, seam doc row 2).
 * {@code REQUIRED_DEVICE_EXTENSIONS}/{@code REQUIRED_DEVICE_FEATURES} are
 * never touched; the collections mutated here are the per-creation copies
 * vanilla builds fresh at its single call site.</p>
 *
 * <p>This class is only ever loaded from the {@code VulkanBackend} mixin,
 * so no OpenGL-path code can pull LWJGL Vulkan classes in by accident.</p>
 */
public final class MeshShaderDeviceSupport {

    /** {@code "VK_EXT_mesh_shader"} (LWJGL 3.4.1, jar-verified). */
    public static final String EXTENSION_NAME = EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME;

    /**
     * Mirrors {@code VulkanBackend.MULTI_DRAW_FEATURES_STRUCT}:
     * sType + struct size of {@code VkPhysicalDeviceMeshShaderFeaturesEXT}.
     */
    public static final VulkanPNextStruct MESH_SHADER_FEATURES_STRUCT = new VulkanPNextStruct(
            EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT,
            VkPhysicalDeviceMeshShaderFeaturesEXT.SIZEOF);

    /** Mirrors {@code VulkanBackend.MULTI_DRAW_FEATURE} — member offset from the LWJGL struct. */
    public static final VulkanFeature MESH_SHADER_FEATURE = new VulkanFeature(
            MESH_SHADER_FEATURES_STRUCT, "meshShader", VkPhysicalDeviceMeshShaderFeaturesEXT.MESHSHADER);

    public static final VulkanFeature TASK_SHADER_FEATURE = new VulkanFeature(
            MESH_SHADER_FEATURES_STRUCT, "taskShader", VkPhysicalDeviceMeshShaderFeaturesEXT.TASKSHADER);

    /**
     * The BASE {@code VkPhysicalDeviceFeatures}, addressed as if it were a
     * pNext struct.
     *
     * <p>Vanilla's helpers only know how to reach chained structs, but
     * {@code VulkanPNextStruct.findStructInPNextChain} checks the HEAD
     * struct's own sType before walking the chain (bytecode-verified), and
     * the head IS a {@code VkPhysicalDeviceFeatures2}. So naming that sType
     * resolves to the root, and an offset of
     * {@code FEATURES + <member>} reaches into its inline base-features
     * block. No new plumbing, no reflection.</p>
     */
    private static final VulkanPNextStruct BASE_FEATURES_STRUCT = new VulkanPNextStruct(
            VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
            VkPhysicalDeviceProperties2.SIZEOF);

    /**
     * {@code fragmentStoresAndAtomics} — REQUIRED by occlusion culling, and
     * missing since wave 6.
     *
     * <p>The whole box-raster technique is a fragment shader writing
     * visibility into a storage buffer. Without this feature the spec
     * demands every fragment-stage storage buffer be NonWritable
     * (VUID-RuntimeSpirv-NonWritable-06340), which forbids exactly that
     * write. Vanilla does not enable it, AMD's driver allowed it anyway,
     * and so occlusion culling has never been spec-valid on any machine.
     * The validation layer refuses pipeline creation outright, and a
     * stricter driver may too: the failure is silent and total, occlusion
     * latches an error and the renderer falls back to the BFS feed, which
     * from the outside just looks like occlusion not helping.</p>
     *
     * <p>Unlike the {@code geometryShader} requirement that {@code
     * gl_PrimitiveID} used to impose, this one cannot be engineered away in
     * the shader: writing from the fragment stage IS the design. It is also
     * universally supported on desktop hardware, so requesting it costs
     * nothing. It is still PROBED before being requested, because asking
     * for an unsupported feature fails device creation, and Meshelium's one
     * inviolable rule is never to break a boot vanilla could finish.</p>
     */
    public static final VulkanFeature FRAGMENT_STORES_AND_ATOMICS = new VulkanFeature(
            BASE_FEATURES_STRUCT, "fragmentStoresAndAtomics",
            VkPhysicalDeviceFeatures.FRAGMENTSTORESANDATOMICS);

    private static final VulkanPNextStruct MESH_SHADER_PROPERTIES_STRUCT = new VulkanPNextStruct(
            EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_PROPERTIES_EXT,
            VkPhysicalDeviceMeshShaderPropertiesEXT.SIZEOF);

    /**
     * {@code VkPhysicalDeviceMaintenance3Properties} — core since Vulkan
     * 1.1, so it is always chainable on the 1.2-era device vanilla creates.
     * Carries {@code maxMemoryAllocationSize}, the largest single
     * allocation, which LunarG's desktop-baseline profile reports as low as
     * 1.5 GiB for 2023 parts: BELOW a 2 GiB arena block.
     */
    /**
     * {@code VK_EXT_memory_budget}. A heap SIZE is a hardware fact; a heap
     * BUDGET is what is actually available right now, after vanilla's own
     * textures and buffers, after other processes, and after the desktop
     * compositor. Sizing terrain from the former is how a renderer takes
     * the last of a card and makes something else crash.
     */
    public static final String MEMORY_BUDGET_EXTENSION =
            EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME;

    /**
     * {@code VK_EXT_conditional_rendering} - lets a 4-byte GPU buffer decide
     * whether recorded draws execute. The phase-B skip rides on it: phase B
     * costs a quarter millisecond of task dispatches at rd 64 and draws
     * nothing on almost every frame, and with this the raster passes
     * themselves flip the predicate on exactly the frames with something to
     * reveal. Probed before requested, like everything here: asking for an
     * absent feature fails device creation.
     */
    public static final String CONDITIONAL_RENDERING_EXTENSION =
            EXTConditionalRendering.VK_EXT_CONDITIONAL_RENDERING_EXTENSION_NAME;

    public static final VulkanPNextStruct CONDITIONAL_RENDERING_FEATURES_STRUCT =
            new VulkanPNextStruct(
                    EXTConditionalRendering
                            .VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_CONDITIONAL_RENDERING_FEATURES_EXT,
                    VkPhysicalDeviceConditionalRenderingFeaturesEXT.SIZEOF);

    public static final VulkanFeature CONDITIONAL_RENDERING_FEATURE = new VulkanFeature(
            CONDITIONAL_RENDERING_FEATURES_STRUCT, "conditionalRendering",
            VkPhysicalDeviceConditionalRenderingFeaturesEXT.CONDITIONALRENDERING);

    private static final VulkanPNextStruct MAINTENANCE_3_PROPERTIES_STRUCT = new VulkanPNextStruct(
            VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_3_PROPERTIES,
            VkPhysicalDeviceMaintenance3Properties.SIZEOF);

    /**
     * {@code VkPhysicalDevicePushDescriptorPropertiesKHR} — from
     * {@code VK_KHR_push_descriptor}, which VANILLA enables itself (the
     * string is in {@code VulkanBackend}, javap-verified), so it is present
     * whenever Meshelium runs. Carries {@code maxPushDescriptors}, which
     * bounds how many elements the arena binding may become: the task
     * variant already declares 12 bindings, so the ceiling on block count
     * is {@code maxPushDescriptors - 11}.
     */
    private static final VulkanPNextStruct PUSH_DESCRIPTOR_PROPERTIES_STRUCT = new VulkanPNextStruct(
            KHRPushDescriptor.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PUSH_DESCRIPTOR_PROPERTIES_KHR,
            VkPhysicalDevicePushDescriptorPropertiesKHR.SIZEOF);

    /**
     * The physical device, kept so the memory budget can be RE-queried
     * later. A budget sampled once at boot is worthless: the whole point is
     * that vanilla and other processes take memory afterwards. Held here in
     * the vk package rather than in MesheliumVulkanState, which is
     * deliberately free of every LWJGL and blaze3d Vulkan type so the
     * OpenGL path can read it without loading one.
     */
    private static volatile VkPhysicalDevice budgetProbeDevice;

    /**
     * Re-sample the memory budget into {@link MesheliumVramState}. Cheap
     * but not free (a driver round trip), so callers rate-limit it; the
     * quantity moves on the scale of seconds, not frames.
     */
    public static void refreshMemoryBudget(long nowNanos) {
        VkPhysicalDevice device = budgetProbeDevice;
        if (device == null || !MesheliumVulkanState.memoryBudgetSupported()) {
            return;
        }
        long[] sample = queryMemoryBudget(device);
        if (sample != null) {
            MesheliumVramState.sample(sample[0], sample[1], nowNanos);
        }
    }

    private MeshShaderDeviceSupport() {
    }

    /**
     * Called from the mixin at the head of the private
     * {@code VulkanBackend.createDevice(Collection, VulkanPhysicalDevice, Set)}.
     * Mutates {@code extensions}/{@code features} in place — both are fresh
     * mutable copies ({@code HashSet}/{@code ObjectOpenHashSet}) built by the
     * public {@code createDevice} overload, verified against the 26.2 jar.
     */
    public static void onCreateDevice(Collection<String> extensions,
            VulkanPhysicalDevice physicalDevice, Set<VulkanFeature> features) {
        String name = physicalDevice.deviceName();
        String driver = physicalDevice.driverInfo();

        boolean hasExtension = physicalDevice.hasDeviceExtension(EXTENSION_NAME);
        VkPhysicalDevice vk = physicalDevice.vkPhysicalDevice();
        boolean meshShader = hasExtension && isFeatureSupported(vk, MESH_SHADER_FEATURE);
        boolean taskShader = hasExtension && isFeatureSupported(vk, TASK_SHADER_FEATURE);

        // Wave-14: the memory probe runs on every Vulkan device creation
        // (mesh shaders or not — the state line is cheap and honest either
        // way); the arena-ceiling policy consumes it via MesheliumScaling.
        long localHeapBytes = queryDeviceLocalHeapBytes(vk);
        long storageRange = queryMaxStorageBufferRange(vk);
        // Enable VK_EXT_memory_budget when the device has it. The spec text
        // that would settle whether the chained struct is filled for a
        // merely-SUPPORTED extension, or only for an ENABLED one, is not
        // available here, so this covers both readings: the cost is one
        // string in a set vanilla already builds fresh per creation.
        boolean hasMemoryBudget = physicalDevice.hasDeviceExtension(MEMORY_BUDGET_EXTENSION);
        if (hasMemoryBudget) {
            extensions.add(MEMORY_BUDGET_EXTENSION);
        }
        MesheliumVulkanState.setMemoryBudgetSupported(hasMemoryBudget);
        MesheliumVulkanState.setIntegratedGpu(isIntegrated(vk));
        budgetProbeDevice = vk;
        MesheliumVramState.reset();

        if (meshShader && taskShader) {
            extensions.add(EXTENSION_NAME);
            features.add(MESH_SHADER_FEATURE);
            features.add(TASK_SHADER_FEATURE);
            // Occlusion culling's fragment shader writes visibility stamps
            // into a storage buffer, which the spec forbids without this.
            // Probed, never assumed: requesting an unsupported feature
            // fails device creation and takes the whole game down with it.
            // If it is genuinely absent, mesh-shader terrain still works
            // and only occlusion culling is unavailable.
            if (isFeatureSupported(vk, FRAGMENT_STORES_AND_ATOMICS)) {
                features.add(FRAGMENT_STORES_AND_ATOMICS);
            } else {
                MesheliumClient.LOGGER.warn(
                        "Meshelium: this device reports no fragmentStoresAndAtomics, so occlusion "
                                + "culling cannot run (mesh-shader terrain is unaffected). "
                                + "Device '{}', driver '{}'", name, driver);
            }

            // Conditional rendering, for the phase-B predicate skip. Optional
            // both ways: absent, phase B records its draws directly, exactly
            // the pre-predicate behavior.
            boolean condRender = physicalDevice.hasDeviceExtension(CONDITIONAL_RENDERING_EXTENSION)
                    && isFeatureSupported(vk, CONDITIONAL_RENDERING_FEATURE);
            if (condRender) {
                extensions.add(CONDITIONAL_RENDERING_EXTENSION);
                features.add(CONDITIONAL_RENDERING_FEATURE);
            }
            MesheliumVulkanState.setConditionalRenderingSupported(condRender);

            MesheliumVulkanState.MeshShaderCaps caps = queryCaps(vk);
            MesheliumVulkanState.recordDeviceCreation(name, driver, true, caps, localHeapBytes,
                    storageRange, queryArenaLimits(vk));
            // The wave-14 acceptance evidence: the device-derived terrain
            // memory ceiling, computed from the probe just recorded and
            // logged next to the caps block it belongs with.
            // Java heap alongside the GPU heap. Cheap, and it settles an
            // argument that is otherwise guesswork when a benchmark run and
            // a real game disagree: the launcher hands Minecraft 2 GiB by
            // default while a bare JVM takes a quarter of system RAM, and
            // the two behave differently under GC pressure.
            MesheliumClient.LOGGER.info(
                    "Meshelium java heap: max {} MiB (the launcher's default is 2048; a raw "
                            + "JVM takes a quarter of system RAM, so benchmark runs and real "
                            + "sessions can differ here)",
                    Runtime.getRuntime().maxMemory() >> 20);
            MesheliumClient.LOGGER.info(
                    "Meshelium memory probe on '{}': device-local heap {} MiB, "
                            + "maxStorageBufferRange {} MiB -> terrain arena ceiling {} MiB "
                            + "({}% of the largest DEVICE_LOCAL heap, floor {} MiB, CLAMPED to "
                            + "what a shader can address; -Dmeshelium.tune.arenaCeilingMiB "
                            + "overrides; integrated GPUs report their shared heap here — the "
                            + "fraction then bounds Meshelium's share of SYSTEM memory)",
                    name, localHeapBytes >> 20, storageRange >> 20,
                    com.deds.meshelium.MesheliumScaling.arenaCeilingBytes() >> 20,
                    com.deds.meshelium.MesheliumScaling.ARENA_CEILING_HEAP_PCT,
                    com.deds.meshelium.MesheliumScaling.ARENA_CEILING_FLOOR_BYTES >> 20);
            // The four descriptor/allocation limits that bound the arena's
            // SHAPE. None of them had ever been measured on any hardware
            // before this line existed, on this desk or anywhere else, while
            // the terrain pipelines were already pushing 12 descriptors.
            MesheliumVulkanState.ArenaLimits limits = MesheliumVulkanState.arenaLimits();
            long blockBytes = com.deds.meshelium.MesheliumScaling.arenaBlockBytes();
            int blocks = com.deds.meshelium.MesheliumScaling.arenaBlockCount();
            MesheliumClient.LOGGER.info(
                    "Meshelium arena geometry: block {} MiB x {} = {} MiB addressable "
                            + "(limits: maxStorageBufferRange {} MiB, maxMemoryAllocationSize {} MiB, "
                            + "maxPushDescriptors {}, maxPerStageDescriptorStorageBuffers {}, "
                            + "maxDescriptorSetStorageBuffers {} — a 0 means the driver did not "
                            + "report it and it was treated as no-information, never as no-capacity)",
                    blockBytes >> 20, blocks, (blockBytes * blocks) >> 20,
                    limits.maxStorageBufferRange() >> 20,
                    limits.maxMemoryAllocationSize() >> 20,
                    limits.maxPushDescriptors(),
                    limits.maxPerStageDescriptorStorageBuffers(),
                    limits.maxDescriptorSetStorageBuffers());
            MesheliumClient.LOGGER.info(
                    "Meshelium memory budget: VK_EXT_memory_budget {}, device type {} (reserve "
                            + "kept clear for vanilla and everything else: {} MiB). A heap SIZE is "
                            + "a hardware fact; a heap BUDGET is what is free after vanilla's "
                            + "textures and every other process, and only the budget can stop "
                            + "Meshelium taking the last of the card and crashing something else",
                    MesheliumVulkanState.memoryBudgetSupported() ? "supported" : "ABSENT",
                    MesheliumVulkanState.integratedGpu() ? "INTEGRATED (heap is shared system "
                            + "memory)" : "discrete",
                    (MesheliumVulkanState.integratedGpu()
                            ? MesheliumVramState.RESERVE_INTEGRATED_BYTES
                            : MesheliumVramState.RESERVE_DISCRETE_BYTES) >> 20);
            if (blockBytes < com.deds.meshelium.MesheliumScaling.ARENA_BLOCK_PREFERRED_BYTES) {
                MesheliumClient.LOGGER.warn(
                        "Meshelium: arena blocks clamped to {} MiB, below the preferred {} MiB, "
                                + "because this device's limits round down to a power of two. "
                                + "More blocks are used to compensate",
                        blockBytes >> 20,
                        com.deds.meshelium.MesheliumScaling.ARENA_BLOCK_PREFERRED_BYTES >> 20);
            }
            // Say out loud what actually bounds the arena now, because this
            // line used to say maxStorageBufferRange and that stopped being
            // true when the arena became several separately bound blocks.
            // The limit still applies, but PER BLOCK; the total is the sum.
            long ceilingNow = com.deds.meshelium.MesheliumScaling.arenaCeilingBytes();
            long blockTotal = blockBytes * blocks;
            if (storageRange > 0 && blockTotal > storageRange) {
                MesheliumClient.LOGGER.info(
                        "Meshelium: terrain memory can now exceed maxStorageBufferRange ({} MiB). "
                                + "That limit bounds ONE binding, and the arena is {} separately "
                                + "bound blocks of {} MiB, so {} MiB is reachable and the ceiling "
                                + "is {} MiB. Before the split this card stopped at {} MiB",
                        storageRange >> 20, blocks, blockBytes >> 20, blockTotal >> 20,
                        ceilingNow >> 20, storageRange >> 20);
            }

            // The wave-1 acceptance evidence: one INFO block from the real GPU.
            // maxTaskPayloadSize joined for wave 5 (the task-stage payload
            // budget the terrain task shader is dimensioned against).
            MesheliumClient.LOGGER.info(
                    "Requesting {} on '{}' (driver {}, vendor {}): meshShader+taskShader supported. "
                            + "Caps: maxTaskWorkGroupInvocations={}, maxTaskPayloadSize={}, "
                            + "maxMeshWorkGroupInvocations={}, "
                            + "maxMeshOutputVertices={}, maxMeshOutputPrimitives={}, "
                            + "maxPreferredTaskWorkGroupInvocations={}, maxPreferredMeshWorkGroupInvocations={}, "
                            + "prefersLocalInvocationVertexOutput={}, prefersLocalInvocationPrimitiveOutput={}, "
                            + "prefersCompactVertexOutput={}, prefersCompactPrimitiveOutput={}",
                    EXTENSION_NAME, name, driver, physicalDevice.vendorName(),
                    caps.maxTaskWorkGroupInvocations(), caps.maxTaskPayloadSize(),
                    caps.maxMeshWorkGroupInvocations(),
                    caps.maxMeshOutputVertices(), caps.maxMeshOutputPrimitives(),
                    caps.maxPreferredTaskWorkGroupInvocations(), caps.maxPreferredMeshWorkGroupInvocations(),
                    caps.prefersLocalInvocationVertexOutput(), caps.prefersLocalInvocationPrimitiveOutput(),
                    caps.prefersCompactVertexOutput(), caps.prefersCompactPrimitiveOutput());
        } else {
            MesheliumVulkanState.recordDeviceCreation(name, driver, false, null, localHeapBytes,
                    storageRange, queryArenaLimits(vk));
            MesheliumClient.LOGGER.info(
                    "'{}' (driver {}) has no usable {} (extension present: {}, meshShader: {}, taskShader: {}); "
                            + "Meshelium will stay off on this device",
                    name, driver, EXTENSION_NAME, hasExtension, meshShader, taskShader);
        }

        MesheliumClient.LOGGER.debug(
                "Vulkan validation layers can be enabled for debugging with the "
                        + "--vulkanValidation launch argument (vanilla 26.2 flag)");
    }

    /**
     * Reimplementation of the private static
     * {@code VulkanBackend.isFeatureSupported(VkPhysicalDevice, VulkanFeature)},
     * matched instruction-for-instruction against the 26.2 bytecode
     * (calloc → sType$Default → findOrCreateStructInPNextChain →
     * vkGetPhysicalDeviceFeatures2 → feature.get).
     */
    private static boolean isFeatureSupported(VkPhysicalDevice device, VulkanFeature feature) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            feature.struct().findOrCreateStructInPNextChain(features2, stack);
            VK12.vkGetPhysicalDeviceFeatures2(device, features2);
            return feature.get(features2);
        }
    }

    /**
     * Wave-14: the size of the LARGEST {@code DEVICE_LOCAL} memory heap —
     * {@code vkGetPhysicalDeviceMemoryProperties} (core Vulkan 1.0; LWJGL
     * names javap-verified against lwjgl-vulkan-3.4.1). Largest single
     * heap, NOT the sum: discrete cards report the small host-visible BAR
     * heap as a second DEVICE_LOCAL heap and it must not inflate the
     * budget. This is the heap's fixed SIZE, not a usage budget — a
     * static hardware fact, unlike the {@code vmaGetHeapBudgets}
     * estimates wave 10 rejected (vanilla's allocator lacks
     * VK_EXT_memory_budget; the rejection note stands, this probe is a
     * different, always-available query).
     */
    private static long queryDeviceLocalHeapBytes(VkPhysicalDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties memory =
                    VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceMemoryProperties(device, memory);
            long largest = 0L;
            for (int i = 0; i < memory.memoryHeapCount(); i++) {
                VkMemoryHeap heap = memory.memoryHeaps(i);
                if ((heap.flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    largest = Math.max(largest, heap.size());
                }
            }
            return largest;
        }
    }

    /**
     * {@code VkPhysicalDeviceLimits.maxStorageBufferRange}: the largest
     * span of ONE storage buffer a shader can address.
     *
     * <p>Probed because fitting in memory and being readable are different
     * questions, and confusing them silently deleted terrain at render
     * distance 120: the arena grew to 4,374 MiB on a 16 GiB card and
     * everything past the 4 GiB line became unreachable. The spec floor is
     * 2^27, 128 MiB, and desktop drivers report 0xFFFFFFFF, so this is
     * effectively a 4 GiB ceiling on how large one arena can usefully be.
     * The value is an unsigned 32-bit field, so it is read through
     * {@code Integer.toUnsignedLong} rather than as a signed int.</p>
     */
    private static long queryMaxStorageBufferRange(VkPhysicalDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(device, properties);
            return Integer.toUnsignedLong(properties.limits().maxStorageBufferRange());
        }
    }

    /**
     * Live memory budget for the largest DEVICE_LOCAL heap, or null when
     * the extension is absent.
     *
     * <p>Re-queried rather than cached: the whole point is that another
     * process, or vanilla itself, can take memory after Meshelium started.
     * Returns {@code {budget, usage}} for the SAME heap the ceiling policy
     * sizes from, so the two numbers are comparable.</p>
     *
     * <p>A zero budget means the driver did not fill the struct - some do
     * not, and treating that as "no headroom" would pin the render distance
     * at its floor forever while treating it as "unlimited" would be
     * catastrophic. Zero is reported as UNKNOWN and the caller falls back
     * to the static ceiling, changing nothing.</p>
     */
    /**
     * Is this an integrated GPU? Decides whether the "device local" heap is
     * really shared system memory, which changes every memory policy that
     * takes a fraction of it.
     */
    private static boolean isIntegrated(VkPhysicalDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(device, properties);
            return properties.deviceType() == VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
        }
    }

    public static long[] queryMemoryBudget(VkPhysicalDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Chained by hand: vanilla's VulkanPNextStruct helper is typed
            // for the properties/features heads and does not accept a
            // memory-properties head.
            VkPhysicalDeviceMemoryBudgetPropertiesEXT budget =
                    VkPhysicalDeviceMemoryBudgetPropertiesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceMemoryProperties2 props2 = VkPhysicalDeviceMemoryProperties2
                    .calloc(stack).sType$Default().pNext(budget.address());
            VK11.vkGetPhysicalDeviceMemoryProperties2(device, props2);

            VkPhysicalDeviceMemoryProperties memory = props2.memoryProperties();
            long best = 0L;
            int bestHeap = -1;
            for (int i = 0; i < memory.memoryHeapCount(); i++) {
                VkMemoryHeap heap = memory.memoryHeaps(i);
                if ((heap.flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0
                        && heap.size() > best) {
                    best = heap.size();
                    bestHeap = i;
                }
            }
            if (bestHeap < 0) {
                return null;
            }
            return new long[] {budget.heapBudget(bestHeap), budget.heapUsage(bestHeap)};
        }
    }

    /**
     * Every limit that bounds how the terrain arena may be SHAPED.
     *
     * <p>Three come free from {@code VkPhysicalDeviceLimits}, which is
     * already being fetched. Two need {@code vkGetPhysicalDeviceProperties2}
     * with a struct chained in, which is the pattern {@link #queryCaps}
     * already uses, so this is one extra call and no new plumbing.</p>
     *
     * <p>A zero anywhere means NOT REPORTED, and every consumer must treat
     * it as "no information" rather than "no capacity" - a driver that
     * ignores a chained struct leaves it zeroed, and reading that as a
     * limit of zero would refuse to allocate anything at all.</p>
     */
    private static MesheliumVulkanState.ArenaLimits queryArenaLimits(VkPhysicalDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(device, properties);
            long range = Integer.toUnsignedLong(properties.limits().maxStorageBufferRange());
            // UNSIGNED. AMD reports 0xFFFFFFFF here to mean "no practical
            // limit", which as a signed Java int is -1 - it printed as -1 in
            // the first probe run. Read signed, every clamp against these
            // would be skipped by a `> 0` guard for entirely the wrong
            // reason, and a real limit near 2^31 would come back negative.
            long perStage = Integer.toUnsignedLong(
                    properties.limits().maxPerStageDescriptorStorageBuffers());
            long perSet = Integer.toUnsignedLong(
                    properties.limits().maxDescriptorSetStorageBuffers());

            VkPhysicalDeviceProperties2 properties2 =
                    VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            long maintenance3 =
                    MAINTENANCE_3_PROPERTIES_STRUCT.findOrCreateStructInPNextChain(properties2, stack);
            long pushDescriptor =
                    PUSH_DESCRIPTOR_PROPERTIES_STRUCT.findOrCreateStructInPNextChain(properties2, stack);
            VK11.vkGetPhysicalDeviceProperties2(device, properties2);
            long maxAllocation =
                    VkPhysicalDeviceMaintenance3Properties.create(maintenance3).maxMemoryAllocationSize();
            long maxPush = Integer.toUnsignedLong(VkPhysicalDevicePushDescriptorPropertiesKHR
                    .create(pushDescriptor).maxPushDescriptors());

            return new MesheliumVulkanState.ArenaLimits(
                    range, perStage, perSet, maxAllocation, maxPush);
        }
    }

    /**
     * {@code vkGetPhysicalDeviceProperties2} with
     * {@code VkPhysicalDeviceMeshShaderPropertiesEXT} chained in — core in
     * Vulkan 1.1, and vanilla's instance requests 1.2 (seam doc Q6), so the
     * core entry point is always present here.
     */
    private static MesheliumVulkanState.MeshShaderCaps queryCaps(VkPhysicalDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties2 properties2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            long address = MESH_SHADER_PROPERTIES_STRUCT.findOrCreateStructInPNextChain(properties2, stack);
            VK11.vkGetPhysicalDeviceProperties2(device, properties2);
            VkPhysicalDeviceMeshShaderPropertiesEXT props = VkPhysicalDeviceMeshShaderPropertiesEXT.create(address);
            return new MesheliumVulkanState.MeshShaderCaps(
                    props.maxTaskWorkGroupInvocations(),
                    props.maxTaskPayloadSize(),
                    props.maxMeshWorkGroupInvocations(),
                    props.maxMeshOutputVertices(),
                    props.maxMeshOutputPrimitives(),
                    props.maxMeshOutputComponents(),
                    props.maxMeshOutputMemorySize(),
                    props.maxPreferredTaskWorkGroupInvocations(),
                    props.maxPreferredMeshWorkGroupInvocations(),
                    props.prefersLocalInvocationVertexOutput(),
                    props.prefersLocalInvocationPrimitiveOutput(),
                    props.prefersCompactVertexOutput(),
                    props.prefersCompactPrimitiveOutput());
        }
    }
}
