/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Shape follows Nvidium's UploadingBufferStream (32 MB persistent-mapped
 * staging with per-frame fence retirement, NVIDIUM-ARCHITECTURE.md §3) —
 * simplified to a FIFO ring because Meshelium's consumers are recorded and
 * submitted strictly in ring order, and hardened with the overflow check
 * Nvidium's DownloadTaskStream never had (study Q13): a full ring returns
 * −1 and the caller backlogs, it never writes at a bogus offset.
 */
package com.deds.meshelium.vk;

import java.util.ArrayDeque;

/**
 * Persistent host-visible staging ring. All methods render-thread-only.
 *
 * <p><b>Retirement discipline:</b> bytes written during pump frame F are
 * read by GPU copies recorded in frame F, which land in a submission that
 * is closed no later than frame F's end-of-frame {@code submit()}. Vanilla
 * runs 2 submits in flight with a CPU-side timeline wait on submit S at
 * submit S+2 (frame-path Q1.2), and every frame ends with ≥ 1 submit — so
 * by the time the pump runs in frame F+3, frame F's last submission has
 * PROVABLY completed (the wait happened during frame F+2's end submit).
 * {@code beginFrame(F)} therefore retires spans stamped ≤ F−3:
 * 2 submits in flight + 1 frame of safety margin.</p>
 */
final class VkStagingRing {

    private final long vma;
    private final long vkBuffer;
    private final long allocation;
    private final long mappedAddress;
    private final int capacity;

    /** Next write offset. */
    private int head;
    /** Bytes not yet retired (incl. wrap padding). */
    private long used;

    private long currentFrame = Long.MIN_VALUE;
    private long currentFrameBytes;
    /** FIFO of {frame, bytes} spans awaiting retirement. */
    private final ArrayDeque<long[]> spans = new ArrayDeque<>();

    private VkStagingRing(long vma, MesheliumVkBuffers.MappedBuffer buffer, int capacity) {
        this.vma = vma;
        this.vkBuffer = buffer.vkBuffer();
        this.allocation = buffer.allocation();
        this.mappedAddress = buffer.mappedAddress();
        this.capacity = capacity;
    }

    static VkStagingRing create(long vma, int capacity) {
        MesheliumVkBuffers.MappedBuffer buffer = MesheliumVkBuffers.createHostMapped(vma, capacity,
                org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                "vmaCreateBuffer(meshelium staging ring)");
        return new VkStagingRing(vma, buffer, capacity);
    }

    long vkBuffer() {
        return vkBuffer;
    }

    long mappedAddress() {
        return mappedAddress;
    }

    int capacity() {
        return capacity;
    }

    long usedBytes() {
        return used;
    }

    /** Close the previous frame's span; retire spans stamped ≤ frame−3. */
    void beginFrame(long frame) {
        if (currentFrameBytes > 0) {
            spans.addLast(new long[] {currentFrame, currentFrameBytes});
            currentFrameBytes = 0;
        }
        currentFrame = frame;
        while (!spans.isEmpty() && frame - spans.peekFirst()[0] >= MesheliumTerrainGpu.FREE_FRAME_LAG) {
            used -= spans.pollFirst()[1];
        }
    }

    /**
     * Reserve {@code size} bytes at the head (4-byte aligned).
     *
     * @return the ring byte offset, or −1 when the ring is full — caller
     *         backlogs, NEVER spins (threading rule 5.3: Meshelium gets no
     *         guarantee anyone drains its ring within this frame)
     */
    long alloc(int size) {
        int aligned = (size + 3) & ~3;
        if (aligned <= 0 || aligned > capacity) {
            return -1;
        }
        if (head + aligned > capacity) {
            // Wrap: the tail fragment [head, capacity) is wasted but still
            // counts as used until its span retires.
            long pad = capacity - head;
            if (used + pad + aligned > capacity) {
                return -1;
            }
            used += pad;
            currentFrameBytes += pad;
            head = 0;
        } else if (used + aligned > capacity) {
            return -1;
        }
        long offset = head;
        head += aligned;
        used += aligned;
        currentFrameBytes += aligned;
        return offset;
    }

    void destroy() {
        MesheliumVkBuffers.destroy(vma, vkBuffer, allocation);
    }
}
