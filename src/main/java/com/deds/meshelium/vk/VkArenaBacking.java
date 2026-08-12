/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.terrain.ArenaBacking;
import com.deds.meshelium.terrain.TerrainArena;

import org.lwjgl.vulkan.VK10;

/**
 * The wave-3b filling of wave 3a's one-method {@link ArenaBacking} seam:
 * one device-local VkBuffer backing the whole terrain-quad arena, created
 * through vanilla's own VMA allocator ({@code VulkanDevice.vma()}).
 *
 * <p><b>Size: starts at 256 MiB and grows on demand since wave 14</b>
 * ({@link MesheliumTerrainGpu#ARENA_INITIAL_BYTES}; test-shrinkable via
 * {@code meshelium.test.arenaMiB}) — the original 256 MiB rationale:
 * ≈ 0.57× of a heavy 448 MiB vanilla vertex-heap load (recon §5.4's
 * ratio). Waves 10/13 predicted the per-world size with a density formula;
 * wave 14 retired the prediction after it overflowed on the owner's first
 * real overworld — an allocation failure now triggers grow-and-copy
 * ({@link MesheliumTerrainGpu#growArena}, ×1.5 up to the device-derived
 * ceiling) with the failed upload retried, and only EXHAUSTED growth
 * reaches the wave-8 drop → coverage guard → passive path (Meshelium goes
 * passive, vanilla draws everything, no holes —
 * TerrainDrawer.coverageGuardBlocks). Usage is
 * {@code TRANSFER_SRC | TRANSFER_DST | STORAGE_BUFFER}: SRC so the next
 * growth can read this backing (wave 14), DST for the staging-ring
 * copies, STORAGE so wave 4 can bind the very same buffer to mesh shaders
 * without recreation. No SHADER_DEVICE_ADDRESS — see
 * {@link MesheliumVkBuffers}'s header for the verified reason (vanilla's
 * allocator and device lack the BDA feature as created; wave 4 decides
 * SSBO vs. feature request).</p>
 *
 * <p>{@link #allocate(long)} is called exactly once, by the
 * {@link TerrainArena} constructor, and hands back the {@code VkBuffer}
 * handle as the arena's opaque {@code backingHandle} — the arena never
 * interprets it (3a contract).</p>
 */
public final class VkArenaBacking implements ArenaBacking {

    private final long vma;
    private long vkBuffer;
    private long allocation;
    private long sizeBytes;

    public VkArenaBacking(long vma) {
        this.vma = vma;
    }

    @Override
    public long allocate(long sizeBytes) {
        if (vkBuffer != 0L) {
            throw new IllegalStateException("arena backing allocated twice");
        }
        // TRANSFER_SRC joined in wave 14: every arena backing must be
        // READABLE by the next growth's old→new vkCmdCopyBuffer (usage
        // bits are free — they change no memory-type decision here).
        MesheliumVkBuffers.DeviceBuffer buffer = MesheliumVkBuffers.createDeviceLocal(vma, sizeBytes,
                VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                "vmaCreateBuffer(meshelium terrain arena)");
        this.vkBuffer = buffer.vkBuffer();
        this.allocation = buffer.allocation();
        this.sizeBytes = sizeBytes;
        return buffer.vkBuffer();
    }

    long vkBuffer() {
        return vkBuffer;
    }

    long sizeBytes() {
        return sizeBytes;
    }

    /**
     * Wave-14 growth swap: make {@code grown} the CURRENT backing and hand
     * the old one back as {@code {vkBuffer, allocation, sizeBytes}} — the
     * caller ({@link MesheliumTerrainGpu#growArena}) records the old→new
     * GPU copy and parks the old buffer for {@code FREE_FRAME_LAG}-fenced
     * destruction. The {@code allocate}-once contract of the
     * {@link ArenaBacking} seam is untouched — growth never re-enters
     * {@link #allocate(long)}.
     */
    long[] swapForGrowth(MesheliumVkBuffers.DeviceBuffer grown, long grownSizeBytes) {
        long[] old = {vkBuffer, allocation, sizeBytes};
        this.vkBuffer = grown.vkBuffer();
        this.allocation = grown.allocation();
        this.sizeBytes = grownSizeBytes;
        return old;
    }

    /** Handles for the deferred-destroy queue; zeroes the fields. */
    long[] takeForDestroy() {
        long[] handles = {vkBuffer, allocation};
        vkBuffer = 0L;
        allocation = 0L;
        return handles;
    }
}
