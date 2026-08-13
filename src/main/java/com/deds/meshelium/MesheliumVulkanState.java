/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium;

/**
 * What the device-creation mixin learned, in plain Java types only.
 *
 * <p>This class is deliberately free of any {@code org.lwjgl.vulkan} or
 * {@code com.mojang.blaze3d.vulkan} reference so that {@link MesheliumGate}
 * can read it on the OpenGL path without ever touching a Vulkan class. All
 * writes happen once, on the render thread, inside vanilla's
 * {@code VulkanBackend.createDevice} (via the wave-1 mixin); reads happen
 * later from the client tick. Fields are volatile out of caution, not need.</p>
 */
public final class MesheliumVulkanState {

    /**
     * The mesh-shader numbers wave 5+ tunes against, captured from
     * {@code VkPhysicalDeviceMeshShaderPropertiesEXT} at device creation
     * (docs/VANILLA-VULKAN-SEAM.md Q3: the physical device is reachable there).
     */
    public record MeshShaderCaps(
            int maxTaskWorkGroupInvocations,
            int maxTaskPayloadSize,
            int maxMeshWorkGroupInvocations,
            int maxMeshOutputVertices,
            int maxMeshOutputPrimitives,
            int maxPreferredTaskWorkGroupInvocations,
            int maxPreferredMeshWorkGroupInvocations,
            boolean prefersLocalInvocationVertexOutput,
            boolean prefersLocalInvocationPrimitiveOutput,
            boolean prefersCompactVertexOutput,
            boolean prefersCompactPrimitiveOutput) {
    }

    private static volatile boolean vulkanDeviceCreationSeen;
    private static volatile boolean meshShadersRequested;
    private static volatile String deviceName = "<no vulkan device>";
    private static volatile String driverInfo = "";
    private static volatile MeshShaderCaps caps;
    private static volatile long deviceLocalHeapBytes;

    private MesheliumVulkanState() {
    }

    /** True once vanilla's Vulkan device creation ran with our mixin watching. */
    public static boolean vulkanDeviceCreationSeen() {
        return vulkanDeviceCreationSeen;
    }

    /**
     * True iff the mixin appended {@code VK_EXT_mesh_shader} plus the
     * meshShader and taskShader features to the device being created —
     * which it only does when the physical device supports all three.
     */
    public static boolean meshShadersRequested() {
        return meshShadersRequested;
    }

    public static String deviceName() {
        return deviceName;
    }

    public static String driverInfo() {
        return driverInfo;
    }

    /** Non-null exactly when {@link #meshShadersRequested()} is true. */
    public static MeshShaderCaps caps() {
        return caps;
    }

    /**
     * Wave-14: size in bytes of the LARGEST {@code DEVICE_LOCAL} memory
     * heap of the physical device Meshelium runs on, captured by the same
     * device-creation hook as {@link #caps()} (probe:
     * {@code vkGetPhysicalDeviceMemoryProperties} — core Vulkan 1.0, no
     * extension involved). The largest single heap, deliberately NOT the
     * sum: discrete cards typically report a small (~256 MiB) host-visible
     * BAR heap as a second DEVICE_LOCAL heap, which must not inflate the
     * budget. 0 until a Vulkan device was created (== forever on the
     * OpenGL path — the GL log carries no probe line, and every consumer
     * treats 0 as "no probe"). <b>Integrated-GPU caveat:</b> UMA devices
     * report their shared system-memory heap here, so a fraction of this
     * value is a fraction of SYSTEM memory — the arena-ceiling policy
     * takes its fraction of what is reported and documents exactly that
     * (docs/VANILLA-SECTION-BUILD.md wave-14 note).
     */
    public static long deviceLocalHeapBytes() {
        return deviceLocalHeapBytes;
    }

    /** Called only from the device-creation hook, once per device creation. */
    public static void recordDeviceCreation(String name, String driver,
            boolean requested, MeshShaderCaps queriedCaps, long localHeapBytes) {
        deviceName = name;
        driverInfo = driver;
        meshShadersRequested = requested;
        caps = queriedCaps;
        deviceLocalHeapBytes = localHeapBytes;
        vulkanDeviceCreationSeen = true;
    }
}
