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
            // The two OUTPUT-SIZE limits, queried since 2026-08-16. They were
            // omitted for the whole first year of this renderer because the
            // vertex/primitive counts alone happened to admit every shape it
            // used - until the greedy-merge varyings grew a vertex to 9
            // locations and the 256-vertex translucent workgroup quietly
            // crossed the spec-minimum maxMeshOutputMemorySize of 32768 B.
            // The dev card reports far more, so only a review caught it.
            int maxMeshOutputComponents,
            int maxMeshOutputMemorySize,
            int maxPreferredTaskWorkGroupInvocations,
            int maxPreferredMeshWorkGroupInvocations,
            boolean prefersLocalInvocationVertexOutput,
            boolean prefersLocalInvocationPrimitiveOutput,
            boolean prefersCompactVertexOutput,
            boolean prefersCompactPrimitiveOutput) {
    }

    /**
     * The device limits that bound how the terrain arena may be SHAPED, as
     * opposed to how large it may be in total.
     *
     * <p>All five were unprobed before the multi-buffer work, and every one
     * of them can silently invalidate a design that assumes desktop values.
     * The dev card reports 0xFFFFFFFF for {@code maxStorageBufferRange};
     * LunarG's desktop-baseline profile reports 1 GiB minus 4 for the
     * 2022-2024 blocks and 128 MiB for 2026. A limit nobody measured is a
     * limit nobody is respecting.</p>
     *
     * @param maxStorageBufferRange bytes of ONE storage buffer a shader can
     *        address; bounds a single block, NOT the total arena
     * @param maxPerStageDescriptorStorageBuffers storage buffers one stage
     *        may see; required minimum is 4, and the task stage already
     *        declares 4 today (5 with extended lists) before any split
     * @param maxDescriptorSetStorageBuffers storage buffers one set may hold
     * @param maxMemoryAllocationSize largest single allocation; 0 = not
     *        reported. Notably NO VUID mentions it, so exceeding it is
     *        invisible to the validation layer and arrives as an opaque
     *        VkResult
     * @param maxPushDescriptors descriptors one push-descriptor set may
     *        hold; 0 = not reported. The 32 commonly quoted is a Vulkan 1.4
     *        core guarantee, and vanilla creates a 1.2-era device that gets
     *        push descriptors from VK_KHR_push_descriptor, which mandates
     *        no minimum at all
     */
    public record ArenaLimits(
            long maxStorageBufferRange,
            long maxPerStageDescriptorStorageBuffers,
            long maxDescriptorSetStorageBuffers,
            long maxMemoryAllocationSize,
            long maxPushDescriptors) {
    }

    private static volatile ArenaLimits arenaLimits =
            new ArenaLimits(0L, 0L, 0L, 0L, 0L);

    /** Never null; all-zero until a Vulkan device was created. */
    public static ArenaLimits arenaLimits() {
        return arenaLimits;
    }

    /**
     * Both features the batched Sodium draw needs to skip its binary
     * search: {@code multiDrawIndirect} (so one indirect command can carry
     * many draws) and {@code shaderDrawParameters} (so the mesh shader can
     * read {@code gl_DrawID} and index its run directly).
     *
     * <p>One flag rather than two because neither is any use alone here.
     * Both are probed before being requested — asking for an unsupported
     * feature fails device creation, and the rule is never to break a boot
     * vanilla could finish — so false simply means the batched path falls
     * back to searching the table.
     */
    private static volatile boolean drawIndirectSupported;

    public static boolean drawIndirectSupported() {
        return drawIndirectSupported;
    }

    public static void setDrawIndirectSupported(boolean supported) {
        drawIndirectSupported = supported;
    }

    private static volatile boolean memoryBudgetSupported;
    /** True when the device advertised VK_EXT_memory_budget. */
    public static boolean memoryBudgetSupported() {
        return memoryBudgetSupported;
    }

    public static void setMemoryBudgetSupported(boolean supported) {
        memoryBudgetSupported = supported;
    }

    private static volatile boolean conditionalRenderingSupported;

    /**
     * True when VK_EXT_conditional_rendering was offered by the device and
     * enabled at creation. Consumed by the phase-B predicate skip: with it,
     * the GPU itself decides each frame whether the phase-B dispatches run.
     */
    public static boolean conditionalRenderingSupported() {
        return conditionalRenderingSupported;
    }

    public static void setConditionalRenderingSupported(boolean supported) {
        conditionalRenderingSupported = supported;
    }

    private static volatile boolean conservativeRasterizationSupported;
    private static volatile boolean conservativeRasterizationEnabled;
    private static volatile ConservativeRasterCaps conservativeRasterCaps;

    /**
     * NEXT (c) part B: the device OFFERED
     * VK_EXT_conservative_rasterization. Probed on every boot and logged,
     * whether or not it was requested, so
     * docs/unreleased/sodium/MEASUREMENTS.md's UNVERIFIED entry 6 - "does
     * the RX 9070 XT's driver expose it at all" - is answered by any run.
     */
    public static boolean conservativeRasterizationSupported() {
        return conservativeRasterizationSupported;
    }

    public static void setConservativeRasterizationSupported(boolean supported) {
        conservativeRasterizationSupported = supported;
    }

    /**
     * The device was CREATED with the extension because
     * {@code meshelium.occlusion.conservativeRaster} was true at device
     * creation. A BOOT-TIME lever on purpose: appending an extension is a
     * change to {@code VkDeviceCreateInfo}, and with the property absent
     * every shipped boot's structure stays byte-identical. There is no
     * runtime setter; the box pipelines bake the mode.
     */
    public static boolean conservativeRasterizationEnabled() {
        return conservativeRasterizationEnabled;
    }

    public static void setConservativeRasterizationEnabled(boolean enabled) {
        conservativeRasterizationEnabled = enabled;
    }

    /**
     * What the device reports about overestimate mode. Null when the
     * extension is absent (never queried); a zeroed record would be
     * "reported as zero", and the ArenaLimits rule says a number the driver
     * did not give us is NOT a number.
     *
     * @param primitiveOverestimationSize how far, in pixels, the
     *        implementation uncertainty region already extends
     * @param maxExtraPrimitiveOverestimationSize the extra the pipeline may
     *        ask for on top
     * @param extraPrimitiveOverestimationSizeGranularity the step that
     *        extra is quantised to
     * @param degenerateTrianglesRasterized whether an edge-on face - which
     *        projects to a degenerate triangle and covers no full-res
     *        sample either - still produces fragments. LOGGED, never relied
     *        on: the coverage argument must not depend on it.
     * @param fullyCoveredFragmentShaderInputVariable whether
     *        {@code gl_FragFullyCoveredNV}'s equivalent exists (unused here)
     * @param conservativeRasterizationPostDepthCoverage whether coverage is
     *        computed after the depth test (unused here)
     */
    public record ConservativeRasterCaps(float primitiveOverestimationSize,
            float maxExtraPrimitiveOverestimationSize,
            float extraPrimitiveOverestimationSizeGranularity,
            boolean degenerateTrianglesRasterized,
            boolean fullyCoveredFragmentShaderInputVariable,
            boolean conservativeRasterizationPostDepthCoverage) {
    }

    /** Null when the extension is absent; see {@link ConservativeRasterCaps}. */
    public static ConservativeRasterCaps conservativeRasterCaps() {
        return conservativeRasterCaps;
    }

    public static void setConservativeRasterCaps(ConservativeRasterCaps caps) {
        conservativeRasterCaps = caps;
    }

    /**
     * True when this is an INTEGRATED GPU, i.e. its "device local" heap is
     * really shared system memory.
     *
     * <p>This changes what every memory policy MEANS. Taking half of a
     * discrete card's 16 GiB is aggressive but bounded; taking half of a
     * laptop's 16 GiB of system RAM is taking half the machine, and the
     * player's browser and game logic are competing for the same pool.
     * Confirmed relevant rather than theoretical: Meshelium runs on a
     * Radeon 780M.</p>
     */
    public static boolean integratedGpu() {
        return integratedGpu;
    }

    public static void setIntegratedGpu(boolean integrated) {
        integratedGpu = integrated;
    }

    private static volatile boolean integratedGpu;

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

    /**
     * {@code VkPhysicalDeviceLimits.maxStorageBufferRange}: the most bytes
     * of one storage buffer a shader can address. 0 = not probed.
     *
     * <p>Separate from the heap size and NOT interchangeable with it, which
     * is the mistake that cost a player their terrain at render distance
     * 120. A 16 GiB card happily allocates a 4,374 MiB arena; the shader
     * can still only reach the first 4 GiB of it, and reads past that
     * return zero, which the task shader reads as an empty section and
     * skips. See {@code MesheliumScaling.addressable}.</p>
     */
    public static long maxStorageBufferRangeBytes() {
        return maxStorageBufferRangeBytes;
    }

    private static volatile long maxStorageBufferRangeBytes;

    /** Called only from the device-creation hook, once per device creation. */
    public static void recordDeviceCreation(String name, String driver,
            boolean requested, MeshShaderCaps queriedCaps, long localHeapBytes,
            long storageBufferRangeBytes, ArenaLimits limits) {
        deviceName = name;
        driverInfo = driver;
        meshShadersRequested = requested;
        caps = queriedCaps;
        deviceLocalHeapBytes = localHeapBytes;
        maxStorageBufferRangeBytes = storageBufferRangeBytes;
        arenaLimits = limits == null
                ? new ArenaLimits(storageBufferRangeBytes, 0L, 0L, 0L, 0L) : limits;
        vulkanDeviceCreationSeen = true;
    }
}
