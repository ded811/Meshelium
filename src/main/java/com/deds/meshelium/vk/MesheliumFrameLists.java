/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.fabric.mixin.GpuDeviceAccessor;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Wave-10: the per-frame visibility-mask and occlusion-region lists at
 * EXTENDED capacity. The wave-5/6 lists travel as 16 KiB transient-memory
 * UBO slices — the spec-minimum {@code maxUniformBufferRange}, capping
 * them at 512 regions/frame — and vanilla's transient memory cannot mint
 * STORAGE usage ({@code bufferUsageToVk} has no mapping, wave-3b bytecode
 * finding). Extended render distance needs thousands of slots, so both
 * lists move here: two Meshelium-owned host-visible, host-coherent,
 * persistently mapped STORAGE buffers, ring-sliced per frame and bound as
 * read-only SSBOs by the {@code MESHELIUM_LISTS_SSBO=1} pipeline variants
 * (whose GLSL arrays are unsized — one extended pipeline serves any
 * capacity; layout note: a uvec4 array and the vec4+uvec4 OccRegion
 * struct have identical 16/32-byte strides under std140 and std430, so
 * the CPU byte layout is unchanged from the UBO path).
 *
 * <p><b>Ring safety</b> — {@link #SLOTS} = FREE_FRAME_LAG + 1 = 4: a slot
 * written for frame F is only referenced by frame F's submission; the
 * CPU rewrites it at frame F+4, and the FREE_FRAME_LAG argument
 * ({@link VkStagingRing}) proves frame F's submission completed strictly
 * before the CPU records frame F+3 — one full slot of margin on top.
 * Host-coherent mapping ⇒ no flushes; the GPU-read visibility is carried
 * by the same submission-ordering guarantee vanilla's own transient
 * memory relies on.</p>
 *
 * <p>Standard mode (configured max RD = 32, the default) never constructs
 * this class — the wave-5/6 UBO paths run byte-identical.</p>
 */
final class MesheliumFrameLists {

    /** Ring depth: {@code TerrainResidency.FREE_FRAME_LAG} + 1. */
    static final int SLOTS = 4;

    /** Bytes per visibility-mask slot entry (8 uints = 256 bits/region). */
    static final int VIS_ENTRY_BYTES = 32;

    private final VulkanCommandEncoder encoder;
    private final long vma;
    private final int capacity;
    private final long slotVisBytes;
    private final long slotOccBytes;
    private final MesheliumVkBuffers.MappedBuffer vis;
    private final MesheliumVkBuffers.MappedBuffer occ;

    private MesheliumFrameLists(VulkanCommandEncoder encoder, long vma, int capacity,
            MesheliumVkBuffers.MappedBuffer vis, MesheliumVkBuffers.MappedBuffer occ) {
        this.encoder = encoder;
        this.vma = vma;
        this.capacity = capacity;
        this.slotVisBytes = (long) capacity * VIS_ENTRY_BYTES;
        this.slotOccBytes = (long) capacity * TerrainOcclusion.OCC_ENTRY_BYTES;
        this.vis = vis;
        this.occ = occ;
    }

    /**
     * Create at the pinned dispatch capacity (render thread, first
     * extended frame of a world — the MesheliumTerrainGpu.create seam).
     * Returns null while the device facade isn't up (caller retries).
     */
    static MesheliumFrameLists create(int capacity) {
        GpuDevice facade = RenderSystem.tryGetDevice();
        if (facade == null) {
            return null;
        }
        VulkanDevice device = (VulkanDevice) ((GpuDeviceAccessor) (Object) facade).meshelium$backend();
        VulkanCommandEncoder encoder = device.createCommandEncoder(); // singleton (frame-path Q1.2)
        long vma = device.vma();
        long visBytes = (long) capacity * VIS_ENTRY_BYTES * SLOTS;
        long occBytes = (long) capacity * TerrainOcclusion.OCC_ENTRY_BYTES * SLOTS;
        MesheliumVkBuffers.MappedBuffer vis = MesheliumVkBuffers.createHostMapped(vma, visBytes,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                "vmaCreateBuffer(meshelium extended visibility list ring)");
        MesheliumVkBuffers.MappedBuffer occ = MesheliumVkBuffers.createHostMapped(vma, occBytes,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                "vmaCreateBuffer(meshelium extended occlusion list ring)");
        // Zero both mappings once: stale slot bytes are never read (each
        // frame writes its own prefix and dispatches only that many), but
        // defined contents keep validation/debug tooling quiet.
        MemoryUtil.memSet(vis.mappedAddress(), 0, visBytes);
        MemoryUtil.memSet(occ.mappedAddress(), 0, occBytes);
        MesheliumClient.LOGGER.info(
                "Meshelium extended frame lists up: {} regions/frame — vis ring {} KiB + occlusion "
                        + "list ring {} KiB ({} slots each, host-visible STORAGE; the wave-5/6 "
                        + "16 KiB UBO paths are bypassed this world)",
                capacity, visBytes >> 10, occBytes >> 10, SLOTS);
        return new MesheliumFrameLists(encoder, vma, capacity, vis, occ);
    }

    /** Regions per frame both lists carry (== the pinned dispatchCapacity). */
    int capacity() {
        return capacity;
    }

    /** CPU write view of this frame's visibility-mask slot (little-endian). */
    ByteBuffer visWriteView(long frameSerial) {
        long base = vis.mappedAddress() + slot(frameSerial) * slotVisBytes;
        return MemoryUtil.memByteBuffer(base, (int) slotVisBytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** CPU write view of this frame's occlusion-list slot (little-endian). */
    ByteBuffer occWriteView(long frameSerial) {
        long base = occ.mappedAddress() + slot(frameSerial) * slotOccBytes;
        return MemoryUtil.memByteBuffer(base, (int) slotOccBytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    long visBuffer() {
        return vis.vkBuffer();
    }

    long visOffset(long frameSerial) {
        return slot(frameSerial) * slotVisBytes;
    }

    long visRange() {
        return slotVisBytes;
    }

    long occBuffer() {
        return occ.vkBuffer();
    }

    long occOffset(long frameSerial) {
        return slot(frameSerial) * slotOccBytes;
    }

    long occRange() {
        return slotOccBytes;
    }

    private static long slot(long frameSerial) {
        return Math.floorMod(frameSerial, (long) SLOTS);
    }

    /** Queue both buffers on vanilla's deferred-destroy rotation (dispose path). */
    void destroy() {
        long vmaHandle = this.vma;
        MesheliumVkBuffers.MappedBuffer v = vis;
        MesheliumVkBuffers.MappedBuffer o = occ;
        encoder.queueForDestroy(() -> {
            MesheliumVkBuffers.destroy(vmaHandle, v.vkBuffer(), v.allocation());
            MesheliumVkBuffers.destroy(vmaHandle, o.vkBuffer(), o.allocation());
        });
    }

    /** Direct destroy — device close only (after vanilla's waitIdle). */
    void destroyNow() {
        MesheliumVkBuffers.destroy(vma, vis.vkBuffer(), vis.allocation());
        MesheliumVkBuffers.destroy(vma, occ.vkBuffer(), occ.allocation());
    }
}
