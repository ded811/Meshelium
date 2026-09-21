/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.MesheliumLog;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Minecraft 26.3 only: Meshelium's device features as vanilla's own
 * optional {@code FeatureSet}s.
 *
 * <p>26.2 had no such mechanism, so the 26.2 mixin reached into the
 * private {@code createDevice}'s mutable collections and added extension
 * strings and feature bits by hand after probing each one itself
 * ({@link MeshShaderDeviceSupport#onCreateDevice}). 26.3's
 * {@code VulkanBackend.createDevice(GpuDebugOptions)} asks
 * {@code VulkanFeatureSets.optionalFeatureSets()} for sets it should enable
 * IF the device supports them: for each one it checks the extensions
 * against the device's list and the features against
 * {@code vkGetPhysicalDeviceFeatures2}, composites the supported sets into
 * the enabled one, and logs each by name. That is the probe-then-request
 * discipline this project has always followed, now done by vanilla, so
 * Meshelium's sets are simply appended to that list
 * ({@code VulkanFeatureSetsMixin}) and read back at the head of the
 * two-argument {@code createDevice} ({@code VulkanBackendMixin}).
 *
 * <p>The feature descriptors are the shared constants
 * ({@link MeshShaderDeviceSupport#MESH_SHADER_FEATURE} and friends): their
 * structs are built through {@code McCompat.pnextStruct}, so vanilla's
 * {@code VulkanFeature.get/set} walk the same pNext chain descriptors
 * Meshelium's own probes do. The one exception is
 * {@code fragmentStoresAndAtomics}: on 26.3 it is declared on vanilla's
 * own {@code VK10_FEATURES_STRUCT} exactly as vanilla declares
 * {@code multiDrawIndirect} (bytecode of {@code VulkanFeatureSets.<clinit>}),
 * rather than through the head-struct addressing trick the shared
 * constant uses, which was verified against 26.2's chain walker and not
 * against 26.3's.
 */
public final class MeshShaderFeatureSets {

    /** VK_EXT_mesh_shader with meshShader + taskShader. The whole point. */
    public static final FeatureSet MESH_SHADERS = new FeatureSet(
            "Meshelium mesh shaders",
            Set.of(MeshShaderDeviceSupport.EXTENSION_NAME),
            Set.of(MeshShaderDeviceSupport.MESH_SHADER_FEATURE,
                    MeshShaderDeviceSupport.TASK_SHADER_FEATURE));

    /** Occlusion culling's fragment-stage storage-buffer write. */
    public static final FeatureSet FRAGMENT_STORES_AND_ATOMICS = new FeatureSet(
            "Meshelium occlusion culling (fragmentStoresAndAtomics)",
            Set.of(),
            Set.of(new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "fragmentStoresAndAtomics")));

    /** The phase-B predicate skip. Optional both ways. */
    public static final FeatureSet CONDITIONAL_RENDERING = new FeatureSet(
            "Meshelium conditional rendering",
            Set.of(MeshShaderDeviceSupport.CONDITIONAL_RENDERING_EXTENSION),
            Set.of(MeshShaderDeviceSupport.CONDITIONAL_RENDERING_FEATURE));

    /** NEXT (c) part B; no feature struct exists for it, extension only. */
    public static final FeatureSet CONSERVATIVE_RASTERIZATION = new FeatureSet(
            "Meshelium conservative rasterization",
            Set.of(MeshShaderDeviceSupport.CONSERVATIVE_RASTERIZATION_EXTENSION),
            Set.of());

    /** The memory-budget query the VRAM policy re-samples. Extension only. */
    public static final FeatureSet MEMORY_BUDGET = new FeatureSet(
            "Meshelium memory budget",
            Set.of(MeshShaderDeviceSupport.MEMORY_BUDGET_EXTENSION),
            Set.of());

    private MeshShaderFeatureSets() {
    }

    /**
     * The sets to append to vanilla's optional list. Conservative
     * rasterization is offered ONLY when the boot-time property asks for
     * it, for the reason on {@link MeshShaderDeviceSupport#onCreateDevice}:
     * an enabled extension changes {@code VkDeviceCreateInfo}, and with the
     * property absent every boot must stay identical to what the parity
     * baseline measured. Its PRESENCE is still probed and logged.
     */
    public static List<FeatureSet> optional() {
        List<FeatureSet> out = new ArrayList<>(5);
        out.add(MESH_SHADERS);
        out.add(FRAGMENT_STORES_AND_ATOMICS);
        out.add(CONDITIONAL_RENDERING);
        out.add(MEMORY_BUDGET);
        if (Boolean.getBoolean(TerrainOcclusion.PROPERTY_CONSERVATIVE_RASTER)) {
            out.add(CONSERVATIVE_RASTERIZATION);
        }
        return out;
    }

    /**
     * Head of the private {@code createDevice(FeatureSet, VulkanPhysicalDevice)}:
     * {@code enabled} is the composite vanilla is about to create the device
     * with, so {@code enabled.contains(ours)} is the answer to "did vanilla
     * find this supported and enable it". The two mesh-shader bits are read
     * individually through vanilla's own {@code supportedFeatures} so the
     * "no usable mesh shader" log line can say which one was missing.
     */
    public static void onCreateDevice(FeatureSet enabled, VulkanPhysicalDevice physicalDevice) {
        VkPhysicalDevice vk = physicalDevice.vkPhysicalDevice();
        boolean hasExtension = physicalDevice.hasDeviceExtension(MeshShaderDeviceSupport.EXTENSION_NAME);
        boolean meshShader = false;
        boolean taskShader = false;
        if (hasExtension) {
            Set<VulkanFeature> supported = MESH_SHADERS.supportedFeatures(vk);
            meshShader = supported.contains(MeshShaderDeviceSupport.MESH_SHADER_FEATURE);
            taskShader = supported.contains(MeshShaderDeviceSupport.TASK_SHADER_FEATURE);
        }
        boolean meshEnabled = enabled.contains(MESH_SHADERS);
        if (meshEnabled != (meshShader && taskShader)) {
            // Vanilla's verdict and ours disagree; vanilla's is what the
            // device was created with, so it wins, and the disagreement is
            // worth a line because it means one of the two probes is wrong.
            MesheliumLog.LOGGER.warn(
                    "Meshelium: vanilla {} the mesh-shader feature set but the direct probe says "
                            + "meshShader={}, taskShader={}; going with vanilla",
                    meshEnabled ? "enabled" : "did not enable", meshShader, taskShader);
            meshShader = meshEnabled;
            taskShader = meshEnabled;
        }
        MeshShaderDeviceSupport.onDeviceFeaturesDecided(physicalDevice, hasExtension, meshShader,
                taskShader,
                meshEnabled && enabled.contains(FRAGMENT_STORES_AND_ATOMICS),
                meshEnabled && enabled.contains(CONDITIONAL_RENDERING),
                physicalDevice.hasDeviceExtension(MeshShaderDeviceSupport.CONSERVATIVE_RASTERIZATION_EXTENSION),
                meshEnabled && enabled.contains(CONSERVATIVE_RASTERIZATION),
                enabled.contains(MEMORY_BUDGET));
    }
}
