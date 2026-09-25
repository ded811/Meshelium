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

    /**
     * Submit intervals a written span must survive before its bytes may be
     * handed out again. Three is the number the class was written for and
     * the number the argument below derives; it is a field, and not the
     * constant it used to be, only so the Sodium host can raise it from the
     * command line.
     *
     * <p>The derivation rests on {@code submit()} always waiting for the
     * submit two before it, which it does - it has no early return and no
     * conditional path - and on every step being counted in submits rather
     * than frames, which they are, so it does not matter how many times a
     * frame submits. Raising this is therefore a control and not a fix: it
     * is the only way to say by measurement, rather than by argument, that
     * a span is never handed out while the card is still reading it. A
     * larger number costs staging bytes and nothing else, and a full ring
     * declines the frame rather than corrupting one.
     */
    private final int freeFrameLag;

    /** Next write offset. */
    private int head;
    /** Bytes not yet retired (incl. wrap padding). */
    private long used;

    private long currentFrame = Long.MIN_VALUE;
    private long currentFrameBytes;
    /** FIFO of {frame, bytes} spans awaiting retirement. */
    private final ArrayDeque<long[]> spans = new ArrayDeque<>();

    private VkStagingRing(long vma, MesheliumVkBuffers.MappedBuffer buffer, int capacity,
            int freeFrameLag) {
        this.vma = vma;
        this.vkBuffer = buffer.vkBuffer();
        this.allocation = buffer.allocation();
        this.mappedAddress = buffer.mappedAddress();
        this.capacity = capacity;
        this.freeFrameLag = freeFrameLag;
    }

    static VkStagingRing create(long vma, int capacity) {
        return create(vma, capacity, MesheliumTerrainGpu.FREE_FRAME_LAG);
    }

    static VkStagingRing create(long vma, int capacity, int freeFrameLag) {
        MesheliumVkBuffers.MappedBuffer buffer = MesheliumVkBuffers.createHostMapped(vma, capacity,
                org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                "vmaCreateBuffer(meshelium staging ring)");
        return new VkStagingRing(vma, buffer, capacity, freeFrameLag);
    }

    /** Submit intervals a span waits before its bytes are handed out again. */
    int freeFrameLag() {
        return freeFrameLag;
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
        while (!spans.isEmpty() && frame - spans.peekFirst()[0] >= freeFrameLag) {
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
