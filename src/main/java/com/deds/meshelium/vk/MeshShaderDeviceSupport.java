package com.deds.meshelium.vk;

import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.MesheliumVulkanState;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkMemoryHeap;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;

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

    private static final VulkanPNextStruct MESH_SHADER_PROPERTIES_STRUCT = new VulkanPNextStruct(
            EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_PROPERTIES_EXT,
            VkPhysicalDeviceMeshShaderPropertiesEXT.SIZEOF);

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

        if (meshShader && taskShader) {
            extensions.add(EXTENSION_NAME);
            features.add(MESH_SHADER_FEATURE);
            features.add(TASK_SHADER_FEATURE);

            MesheliumVulkanState.MeshShaderCaps caps = queryCaps(vk);
            MesheliumVulkanState.recordDeviceCreation(name, driver, true, caps, localHeapBytes);
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
                    "Meshelium memory probe on '{}': device-local heap {} MiB -> terrain arena "
                            + "ceiling {} MiB ({}% of the largest DEVICE_LOCAL heap, floor {} MiB; "
                            + "-Dmeshelium.tune.arenaCeilingMiB overrides; integrated GPUs report "
                            + "their shared heap here — the fraction then bounds Meshelium's share "
                            + "of SYSTEM memory)",
                    name, localHeapBytes >> 20,
                    com.deds.meshelium.MesheliumScaling.arenaCeilingBytes() >> 20,
                    com.deds.meshelium.MesheliumScaling.ARENA_CEILING_HEAP_PCT,
                    com.deds.meshelium.MesheliumScaling.ARENA_CEILING_FLOOR_BYTES >> 20);

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
            MesheliumVulkanState.recordDeviceCreation(name, driver, false, null, localHeapBytes);
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
                    props.maxPreferredTaskWorkGroupInvocations(),
                    props.maxPreferredMeshWorkGroupInvocations(),
                    props.prefersLocalInvocationVertexOutput(),
                    props.prefersLocalInvocationPrimitiveOutput(),
                    props.prefersCompactVertexOutput(),
                    props.prefersCompactPrimitiveOutput());
        }
    }
}
