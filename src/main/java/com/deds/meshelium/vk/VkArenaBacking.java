/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.terrain.ArenaBacking;
import com.deds.meshelium.terrain.TerrainArena;

import org.lwjgl.vulkan.VK10;

import java.util.ArrayList;
import java.util.List;

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

    private static final int USAGE = VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
            | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
            | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

    private final long vma;
    /** One entry per block: {vkBuffer, allocation, sizeBytes}. */
    private final List<long[]> blocks = new ArrayList<>();

    public VkArenaBacking(long vma) {
        this.vma = vma;
    }

    @Override
    public long allocate(long sizeBytes) {
        if (!blocks.isEmpty()) {
            throw new IllegalStateException("arena backing allocated twice");
        }
        // TRANSFER_SRC joined in wave 14: every arena backing must be
        // READABLE by the next growth's old→new vkCmdCopyBuffer (usage
        // bits are free — they change no memory-type decision here).
        MesheliumVkBuffers.DeviceBuffer buffer = MesheliumVkBuffers.createDeviceLocal(vma, sizeBytes,
                USAGE, "vmaCreateBuffer(meshelium terrain arena)");
        blocks.add(new long[] {buffer.vkBuffer(), buffer.allocation(), sizeBytes});
        return buffer.vkBuffer();
    }

    /**
     * Allocate an additional block. Returns 0 rather than throwing when the
     * card is full: growth exhaustion is an ordinary outcome the caller
     * degrades on, not an error.
     */
    @Override
    public long appendBlock(long sizeBytes) {
        try {
            MesheliumVkBuffers.DeviceBuffer buffer = MesheliumVkBuffers.createDeviceLocal(
                    vma, sizeBytes, USAGE,
                    "vmaCreateBuffer(meshelium terrain arena block " + blocks.size() + ")");
            blocks.add(new long[] {buffer.vkBuffer(), buffer.allocation(), sizeBytes});
            return buffer.vkBuffer();
        } catch (MesheliumVkBuffers.OutOfDeviceMemoryException e) {
            return 0L;
        }
    }

    /** Block 0's buffer, for the callers that only need "is there an arena". */
    long vkBuffer() {
        return blocks.isEmpty() ? 0L : blocks.get(0)[0];
    }

    /** Every block's VkBuffer, index == block. */
    long[] blockHandles() {
        long[] out = new long[blocks.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = blocks.get(i)[0];
        }
        return out;
    }

    int blockCount() {
        return blocks.size();
    }

    /** Total committed bytes across all blocks. */
    long sizeBytes() {
        long total = 0;
        for (long[] b : blocks) {
            total += b[2];
        }
        return total;
    }

    /** Physical size of the LAST block, the one grow-and-copy extends. */
    long lastBlockBytes() {
        return blocks.isEmpty() ? 0L : blocks.get(blocks.size() - 1)[2];
    }

    long lastBlockBuffer() {
        return blocks.isEmpty() ? 0L : blocks.get(blocks.size() - 1)[0];
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
        int last = blocks.size() - 1;
        long[] old = blocks.get(last).clone();
        blocks.set(last, new long[] {grown.vkBuffer(), grown.allocation(), grownSizeBytes});
        return old;
    }

    /**
     * Handles for the deferred-destroy queue; empties the block list.
     *
     * <p>Returns a FLAT {buffer, allocation} pair per block. The caller
     * destroys each pair; missing one leaks a whole block, which is why
     * this empties the list rather than leaving stale entries behind.</p>
     */
    long[][] takeAllForDestroy() {
        long[][] out = new long[blocks.size()][];
        for (int i = 0; i < out.length; i++) {
            out[i] = new long[] {blocks.get(i)[0], blocks.get(i)[1]};
        }
        blocks.clear();
        return out;
    }

    // takeForDestroy() is deliberately gone. It read as a convenience for
    // "the legacy single-buffer destroy paths" and was in fact a leak: it
    // called takeAllForDestroy(), which EMPTIES this list, and then returned
    // block 0 alone. Every other block was silently unreferenced and never
    // destroyed. All three callers wanted every block, and the javadoc
    // above already said missing one leaks a whole block. There is now one
    // way to do this and it hands back all of them.
}
