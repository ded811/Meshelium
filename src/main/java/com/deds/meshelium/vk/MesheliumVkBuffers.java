/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Raw VMA buffer creation on vanilla's own allocator handle
 * ({@code VulkanDevice.vma()}, seam doc Q3). The recipe mirrors vanilla's
 * {@code VulkanGpuBuffer$Direct.<init>} instruction for instruction
 * (bytecode, 26.2 jar): VkBufferCreateInfo{size, usage, sharingMode 0} +
 * VmaAllocationCreateInfo{usage AUTO_PREFER_DEVICE(8)}, host-visible
 * variants adding requiredFlags HOST_VISIBLE|HOST_COHERENT and the
 * HOST_ACCESS flags — then vmaCreateBuffer / vmaDestroyBuffer.
 *
 * Why raw VMA instead of vanilla's public {@code VulkanDevice.createBuffer}:
 * {@code VulkanConst.bufferUsageToVk} (bytecode) can express only
 * TRANSFER_SRC/DST, VERTEX, INDEX, UNIFORM(+texel) — there is NO mapping to
 * VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, and wave 4's mesh shaders read the
 * arena as a storage buffer. Raw creation with vanilla's allocator keeps
 * one memory owner and adds the one usage bit vanilla's enum cannot say.
 *
 * BUFFER_DEVICE_ADDRESS deliberately NOT used in wave 3b: vanilla creates
 * its VMA allocator with no flags (VulkanBackend bytecode — no
 * VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT) and its
 * VkPhysicalDeviceVulkan12Features chain enables only timelineSemaphore +
 * hostQueryReset, so device-address usage would be invalid on this device
 * as created. Wave 4 either binds the arena as an SSBO (works as-is) or
 * grows the wave-1 device mixin by the bufferDeviceAddress feature.
 */
package com.deds.meshelium.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.LongBuffer;

/** Package-private VMA helpers shared by the wave-3b GPU residency code. */
final class MesheliumVkBuffers {

    /** A device-local buffer: {@code vkBuffer} handle + its VMA allocation. */
    record DeviceBuffer(long vkBuffer, long allocation) {}

    /** A host-visible persistently mapped buffer. */
    record MappedBuffer(long vkBuffer, long allocation, long mappedAddress) {}

    private MesheliumVkBuffers() {}

    /**
     * DEVICE_LOCAL-preferred buffer (VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE=8,
     * vanilla's own default for unmapped buffers).
     */
    static DeviceBuffer createDeviceLocal(long vma, long sizeBytes, int vkUsage, String what) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(sizeBytes)
                    .usage(vkUsage)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer pBuffer = stack.callocLong(1);
            PointerBuffer pAllocation = stack.callocPointer(1);
            check(Vma.vmaCreateBuffer(vma, bufferInfo, allocInfo, pBuffer, pAllocation, null), what);
            return new DeviceBuffer(pBuffer.get(0), pAllocation.get(0));
        }
    }

    /**
     * Host-visible, host-coherent, persistently mapped, sequential-write —
     * the staging-ring flavour (VMA MAPPED_BIT hands back pMappedData; the
     * COHERENT requirement removes any flush obligation, matching vanilla's
     * own mapped-buffer recipe which requires HOST_VISIBLE|HOST_COHERENT).
     */
    static MappedBuffer createHostMapped(long vma, long sizeBytes, int vkUsage, String what) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(sizeBytes)
                    .usage(vkUsage)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
                    .requiredFlags(VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                            | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                    .flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT
                            | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            LongBuffer pBuffer = stack.callocLong(1);
            PointerBuffer pAllocation = stack.callocPointer(1);
            VmaAllocationInfo allocationInfo = VmaAllocationInfo.calloc(stack);
            check(Vma.vmaCreateBuffer(vma, bufferInfo, allocInfo, pBuffer, pAllocation, allocationInfo),
                    what);
            long mapped = allocationInfo.pMappedData();
            if (mapped == 0L) {
                Vma.vmaDestroyBuffer(vma, pBuffer.get(0), pAllocation.get(0));
                throw new IllegalStateException(what + ": VMA returned no persistent mapping");
            }
            return new MappedBuffer(pBuffer.get(0), pAllocation.get(0), mapped);
        }
    }

    /**
     * Host-visible, host-coherent, persistently mapped, RANDOM host access
     * — the wave-6 readback flavour (the stats ring the CPU READS from;
     * SEQUENTIAL_WRITE's promise forbids reads, RANDOM_BIT lets VMA pick
     * cached-if-available memory; COHERENT means no invalidate needed).
     */
    static MappedBuffer createHostReadback(long vma, long sizeBytes, int vkUsage, String what) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(sizeBytes)
                    .usage(vkUsage)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
                    .requiredFlags(VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                            | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                    .flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT
                            | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
            LongBuffer pBuffer = stack.callocLong(1);
            PointerBuffer pAllocation = stack.callocPointer(1);
            VmaAllocationInfo allocationInfo = VmaAllocationInfo.calloc(stack);
            check(Vma.vmaCreateBuffer(vma, bufferInfo, allocInfo, pBuffer, pAllocation, allocationInfo),
                    what);
            long mapped = allocationInfo.pMappedData();
            if (mapped == 0L) {
                Vma.vmaDestroyBuffer(vma, pBuffer.get(0), pAllocation.get(0));
                throw new IllegalStateException(what + ": VMA returned no persistent mapping");
            }
            return new MappedBuffer(pBuffer.get(0), pAllocation.get(0), mapped);
        }
    }

    static void destroy(long vma, long vkBuffer, long allocation) {
        if (vkBuffer != 0L) {
            Vma.vmaDestroyBuffer(vma, vkBuffer, allocation);
        }
    }

    /**
     * Thrown when an allocation failed because the card is FULL, as
     * distinct from failing for any other reason.
     *
     * <p>The distinction is the whole point. Out of memory is the one
     * failure Meshelium can do something intelligent about - allocate less,
     * pull the render distance in, refuse to grow - while every other
     * VkResult means something is wrong that backing off will not fix.
     * Vanilla makes no such distinction: {@code VulkanUtils.crashIfFailure}
     * turns every negative result except {@code VK_ERROR_DEVICE_LOST} into
     * a bare {@code IllegalStateException}, with no OOM branch and no
     * retry. Catching a typed exception is the difference between backing
     * off and crashing the game.</p>
     */
    public static final class OutOfDeviceMemoryException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final int vkResult;

        OutOfDeviceMemoryException(String message, int vkResult) {
            super(message);
            this.vkResult = vkResult;
        }

        /** {@code VK_ERROR_OUT_OF_DEVICE_MEMORY} or {@code ..._HOST_MEMORY}. */
        public int vkResult() {
            return vkResult;
        }
    }

    /**
     * True for the two "there is no memory left" results. Host-memory
     * exhaustion is included deliberately: from a caller's point of view it
     * is the same decision, allocate less, and treating it as a generic
     * failure would crash for a condition that backing off can survive.
     */
    public static boolean isOutOfMemory(int vkResult) {
        return vkResult == VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY
                || vkResult == VK10.VK_ERROR_OUT_OF_HOST_MEMORY;
    }

    private static void check(int vkResult, String what) {
        if (vkResult == VK10.VK_SUCCESS) {
            return;
        }
        String message = what + " failed: VkResult " + vkResult;
        if (isOutOfMemory(vkResult)) {
            throw new OutOfDeviceMemoryException(message
                    + " (out of memory - the graphics card could not fit this allocation)",
                    vkResult);
        }
        throw new IllegalStateException(message);
    }
}
