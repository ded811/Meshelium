/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumVramState;
import com.deds.meshelium.mixin.GpuDeviceAccessor;
import com.deds.meshelium.terrain.host.TerrainResidency;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The GPU half of the record mirror on the Sodium host (D-023, stage 2):
 * one DEVICE_LOCAL buffer holding a stats header, one 64-byte row per
 * Meshelium region id and a verbatim 48-byte copy of every section record
 * of Sodium's two opaque passes, plus the staging ring the commits go
 * through and the host ring the stats come back through.
 *
 * <h2>Why a mirror at all (MEASUREMENTS.md 0k, 0l)</h2>
 * <p>Stage 0 measured the CPU at distance as OURS: the per-section walk of
 * Sodium's lists, 464 us per pass at aerial rd96. Stage 1's run cache
 * removed most of it and left the frame GPU-bound (0l); stage 2 removes
 * the rest by moving the run selection into the task stage, which needs
 * the records on the GPU. Re-uploading them every frame is O(sections)
 * again, tens of MB a frame at rd96, so they are mirrored once and
 * patched on the five mutation hooks (dirty-tracked by the stage-1 epoch
 * machinery), and the per-frame CPU work becomes O(listed regions).
 *
 * <h2>Why DEVICE_LOCAL, written by in-stream copies (contract section 3.1)</h2>
 * <p>Every task lane reads a 48-byte record and every 32 lanes a 64-byte
 * row: the run table's read class, which cost ~0.5 ms a frame on host
 * memory (memory: host-mapped buffers are PCIe). And a record must change
 * at the same GPU-timeline point as Sodium's own defragmentation copy of
 * the geometry it addresses, which Sodium records through the encoder in
 * the extract phase; only an in-stream copy has that property, since a CPU
 * write into mapped memory would race the in-flight previous frame's
 * reads. The copies are recorded in a transient command buffer that ends
 * with {@code VulkanCommandEncoder.memoryBarrier} and is spliced by
 * {@code execute} in submission order before the frame's first pass: the
 * {@code zeroInitialize} convention every Meshelium transfer already uses
 * (BARRIER-EXPERIMENT.md section 1). Nothing else in the frame issues a
 * barrier.
 *
 * <h2>Layout ({@link SodiumGpuVisibilityLayout} is authoritative)</h2>
 * <pre>
 *   0      uint stats[4]      [0] VisMode-0 survivors [1] phase A [2] phase B [3] quads
 *   16     uint layout[4]     capacity, 0, recordBaseUvec4(SOLID), recordBaseUvec4(CUTOUT)
 *   256    row[capacity]      64 B each
 *   256 + cap*64              SOLID records, mid*12288 + slot*48
 *   256 + cap*64 + cap*12288  CUTOUT records
 * </pre>
 *
 * <h2>Stats</h2>
 * <p>The task stage's {@code atomicAdd} targets are the header's first
 * four words, so the stats readback ring lives here rather than in
 * {@link TerrainOcclusion}: rung 0b (GPU draw without occlusion) has no
 * occlusion state and still needs its survivor counts for the parity leg.
 * Same download-stream shape as the standalone's: copy to a host slot,
 * zero, read {@code READBACK_LAG} frames later.
 *
 * <p>Render thread only.
 */
public final class SodiumMirrorGpu {

    static {
        if (SodiumGpuVisibilityLayout.FREE_FRAME_LAG != TerrainResidency.FREE_FRAME_LAG) {
            throw new IllegalStateException("SodiumGpuVisibilityLayout.FREE_FRAME_LAG ("
                    + SodiumGpuVisibilityLayout.FREE_FRAME_LAG + ") != TerrainResidency.FREE_FRAME_LAG ("
                    + TerrainResidency.FREE_FRAME_LAG + ")");
        }
    }

    /** Host ring slots for the stats readback; must exceed {@link #READBACK_LAG}. */
    static final int STATS_RING = 8;

    /** Owned frames between a stats copy and its read: the FREE_FRAME_LAG argument. */
    public static final int READBACK_LAG = SodiumGpuVisibilityLayout.FREE_FRAME_LAG;

    private static final int STATS_BYTES = SodiumGpuVisibilityLayout.MIRROR_STATS_BYTES;

    private final VulkanDevice device;
    private final VulkanCommandEncoder encoder;
    private final long vma;
    private final int capacity;
    private final MesheliumVkBuffers.DeviceBuffer mirror;
    /** Shared across growths: handed from the old instance to the new one. */
    private VkStagingRing staging;
    private MesheliumVkBuffers.MappedBuffer statsRing;
    /** Audit-only: one 12,288-byte block read back at a time; null until requested. */
    private MesheliumVkBuffers.MappedBuffer blockReadback;
    private long blockReadbackFrame = -1L;
    private int blockReadbackMid = -1;
    private int blockReadbackPass = -1;

    /** Queued copies (staging to mirror), flushed by {@link #endCommit()}. */
    private long[] copySrc = new long[256];
    private long[] copyDst = new long[256];
    private long[] copySize = new long[256];
    private int copyCount;
    /** Queued zero fills. */
    private long[] fillDst = new long[64];
    private long[] fillSize = new long[64];
    private int fillCount;
    private VkBufferCopy.Buffer copyScratch;
    /** Bumped by every commit that copied at least one byte (the phase-B skip key). */
    private long commitSerial;
    private long bytesCommitted;

    private SodiumMirrorGpu(VulkanDevice device, VulkanCommandEncoder encoder, int capacity,
            MesheliumVkBuffers.DeviceBuffer mirror, VkStagingRing staging,
            MesheliumVkBuffers.MappedBuffer statsRing) {
        this.device = device;
        this.encoder = encoder;
        this.vma = device.vma();
        this.capacity = capacity;
        this.mirror = mirror;
        this.staging = staging;
        this.statsRing = statsRing;
    }

    /**
     * Render thread. Null while the device facade is not up yet (the
     * {@code TerrainOcclusion.create} pattern). Allocates the mirror,
     * zero-fills it in a transient CB and writes the layout words.
     */
    public static SodiumMirrorGpu create(int capacity) {
        GpuDevice facade = RenderSystem.tryGetDevice();
        if (facade == null) {
            return null;
        }
        VulkanDevice device = (VulkanDevice) ((GpuDeviceAccessor) (Object) facade).meshelium$backend();
        VulkanCommandEncoder encoder = device.createCommandEncoder();
        long vma = device.vma();
        long bytes = SodiumGpuVisibilityLayout.mirrorBytes(capacity);
        MesheliumVkBuffers.DeviceBuffer buffer = MesheliumVkBuffers.createDeviceLocal(vma, bytes,
                mirrorUsage(), "vmaCreateBuffer(meshelium sodium mirror)");
        VkStagingRing staging = null;
        MesheliumVkBuffers.MappedBuffer ring = null;
        try {
            staging = VkStagingRing.create(vma, SodiumGpuVisibilityLayout.STAGING_BYTES);
            ring = MesheliumVkBuffers.createHostReadback(vma, (long) STATS_RING * STATS_BYTES,
                    VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    "vmaCreateBuffer(meshelium sodium mirror stats ring)");
        } catch (RuntimeException t) {
            if (staging != null) {
                staging.destroy();
            }
            MesheliumVkBuffers.destroy(vma, buffer.vkBuffer(), buffer.allocation());
            throw t;
        }
        MemoryUtil.memSet(ring.mappedAddress(), 0, (long) STATS_RING * STATS_BYTES);
        SodiumMirrorGpu gpu = new SodiumMirrorGpu(device, encoder, capacity, buffer, staging, ring);
        gpu.zeroInitialize();
        MesheliumLog.LOGGER.info(
                "Meshelium Sodium record mirror up: {} region ids, {} MiB device-local (rows + two "
                        + "12 KiB pass blocks per id) + {} MiB staging ring; the task stage now "
                        + "selects Sodium's runs from these records instead of a CPU-built table.",
                capacity, bytes >> 20, SodiumGpuVisibilityLayout.STAGING_BYTES >> 20);
        return gpu;
    }

    private static int mirrorUsage() {
        return VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    }

    /** Whole-buffer zero fill, then the layout words; one transient CB. */
    private void zeroInitialize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
            VK10.vkCmdFillBuffer(cb, mirror.vkBuffer(), 0L,
                    SodiumGpuVisibilityLayout.mirrorBytes(capacity), 0);
            // Fill then update on the same bytes: a WAW inside our own CB
            // needs an explicit dependency.
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            writeLayoutWords(cb, stack);
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            checkVk(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer(sodium mirror zero-init)");
            encoder.execute(cb);
        }
    }

    private void writeLayoutWords(VkCommandBuffer cb, MemoryStack stack) {
        ByteBuffer words = stack.calloc(16).order(ByteOrder.LITTLE_ENDIAN);
        words.putInt(0, capacity);
        words.putInt(4, 0);
        words.putInt(8, SodiumGpuVisibilityLayout.recordBaseUvec4(capacity,
                SodiumGpuVisibilityLayout.PASS_SOLID));
        words.putInt(12, SodiumGpuVisibilityLayout.recordBaseUvec4(capacity,
                SodiumGpuVisibilityLayout.PASS_CUTOUT));
        VK10.vkCmdUpdateBuffer(cb, mirror.vkBuffer(),
                (long) SodiumGpuVisibilityLayout.MIRROR_LAYOUT_OFFSET, words);
    }

    public int capacity() {
        return capacity;
    }

    public long vkBuffer() {
        return mirror.vkBuffer();
    }

    /** The rows slice for the section raster's binding 5. */
    public long rowsOffset() {
        return SodiumGpuVisibilityLayout.rowsOffset(capacity);
    }

    public long rowsRange() {
        return (long) capacity * SodiumGpuVisibilityLayout.ROW_BYTES;
    }

    public long statsVkBuffer() {
        return mirror.vkBuffer();
    }

    public long statsOffset() {
        return SodiumGpuVisibilityLayout.MIRROR_STATS_OFFSET;
    }

    /** Commits that copied at least one byte, this instance and its ancestors. */
    public long commitSerial() {
        return commitSerial;
    }

    /** Bytes copied by the most recent {@link #endCommit()}. */
    public long lastCommitBytes() {
        return bytesCommitted;
    }

    // ------------------------------------------------------------------
    // Commit (one transient CB per rung-0 attempt, before any pass)
    // ------------------------------------------------------------------

    /**
     * Open a commit: retires staging spans written {@code FREE_FRAME_LAG}
     * or more submit RETURNs ago. Spans are stamped with the submit
     * interval they were written in ({@link SodiumFrameRing#submitsReturned()}),
     * not with the attempt serial: a span stamped {@code s} lands (through
     * {@code execute}) in the submission that submit {@code s+1} closes,
     * and submit {@code s+3}'s RETURN has waited for that submission
     * ({@code awaitSubmitCompletion(index-2)}), so the span is free exactly
     * when {@code now - s >= FREE_FRAME_LAG}. That is today's lag in the
     * one-attempt-per-frame regime, and it stays true across declines and
     * across any number of attempts per submit interval (vanilla's panorama
     * renders the level six times between submits), where an attempt count
     * would retire a span the GPU had not read yet. Second stage-2/3
     * review, 2026-09-07.
     *
     * @param frameSerial the rung-0 attempt serial: the id lag's clock
     *        ({@code MesheliumRegionMirror.beginFrame}), not the staging ring's
     * @return false when there is no device to record on
     */
    public boolean beginCommit(long frameSerial) {
        if (encoder == null || staging == null) {
            return false;
        }
        staging.beginFrame(SodiumFrameRing.submitsReturned());
        copyCount = 0;
        fillCount = 0;
        bytesCommitted = 0L;
        return true;
    }

    /**
     * Reserve {@code bytes} in the staging ring.
     *
     * @return the mapped host address to memcpy into, or 0 when the ring
     *         is full: the caller declines the frame whole (I1) and keeps
     *         the rest dirty. Write-only memory: never read it back.
     */
    public long stageBlock(int bytes) {
        long off = staging.alloc(bytes);
        if (off < 0L) {
            return 0L;
        }
        return staging.mappedAddress() + off;
    }

    /** Queue a 64-byte row copy from a {@link #stageBlock} address. */
    public void copyRow(int mid, long stagedAddress) {
        queueCopy(stagedAddress - staging.mappedAddress(),
                SodiumGpuVisibilityLayout.rowsOffset(capacity)
                        + (long) mid * SodiumGpuVisibilityLayout.ROW_BYTES,
                SodiumGpuVisibilityLayout.ROW_BYTES);
    }

    /** Queue a 12,288-byte pass-block copy from a {@link #stageBlock} address. */
    public void copyPassBlock(int mid, int pass, long stagedAddress) {
        queueCopy(stagedAddress - staging.mappedAddress(),
                SodiumGpuVisibilityLayout.passOffset(capacity, pass)
                        + (long) mid * SodiumGpuVisibilityLayout.PASS_BLOCK_BYTES,
                SodiumGpuVisibilityLayout.PASS_BLOCK_BYTES);
    }

    /** The region has no storage for that pass: zero its block. */
    public void zeroPassBlock(int mid, int pass) {
        queueFill(SodiumGpuVisibilityLayout.passOffset(capacity, pass)
                + (long) mid * SodiumGpuVisibilityLayout.PASS_BLOCK_BYTES,
                SodiumGpuVisibilityLayout.PASS_BLOCK_BYTES);
    }

    /** A dead mid (I5): its row reads live = 0 from the commit that follows the delete hook. */
    public void zeroRow(int mid) {
        queueFill(SodiumGpuVisibilityLayout.rowsOffset(capacity)
                + (long) mid * SodiumGpuVisibilityLayout.ROW_BYTES,
                SodiumGpuVisibilityLayout.ROW_BYTES);
    }

    private void queueCopy(long src, long dst, long size) {
        if (copyCount == copySrc.length) {
            copySrc = Arrays.copyOf(copySrc, copySrc.length * 2);
            copyDst = Arrays.copyOf(copyDst, copyDst.length * 2);
            copySize = Arrays.copyOf(copySize, copySize.length * 2);
        }
        copySrc[copyCount] = src;
        copyDst[copyCount] = dst;
        copySize[copyCount] = size;
        copyCount++;
        bytesCommitted += size;
    }

    private void queueFill(long dst, long size) {
        if (fillCount == fillDst.length) {
            fillDst = Arrays.copyOf(fillDst, fillDst.length * 2);
            fillSize = Arrays.copyOf(fillSize, fillSize.length * 2);
        }
        fillDst[fillCount] = dst;
        fillSize[fillCount] = size;
        fillCount++;
    }

    /**
     * Record the zero fills FIRST, then (when both fills and copies are
     * queued) a barrier, then every queued copy in ONE {@code vkCmdCopyBuffer},
     * then the trailing barrier; end; splice through {@code execute}. No-op
     * when nothing was queued. Must run before the frame's first
     * {@code createRenderPass} ({@code execute} refuses inside a pass).
     *
     * <p>WHY fills before copies: a dead row's zero fill and a re-acquired
     * mid's row copy can target the same 64 bytes in one commit (delete,
     * then attempts that decline before a commit, then the lag frees the id
     * and a fresh region takes it): a write-after-write inside our own CB,
     * and the copy must win. Two transfer writes in one CB are unordered
     * without a barrier between them. Second stage-2/3 review, 2026-09-07.
     */
    public void endCommit() {
        if (copyCount == 0 && fillCount == 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
            for (int i = 0; i < fillCount; i++) {
                VK10.vkCmdFillBuffer(cb, mirror.vkBuffer(), fillDst[i], fillSize[i], 0);
            }
            if (fillCount > 0 && copyCount > 0) {
                VulkanCommandEncoder.memoryBarrier(cb, stack);
            }
            if (copyCount > 0) {
                VkBufferCopy.Buffer regions = copyScratch(copyCount);
                for (int i = 0; i < copyCount; i++) {
                    regions.get(i).srcOffset(copySrc[i]).dstOffset(copyDst[i]).size(copySize[i]);
                }
                regions.position(0).limit(copyCount);
                VK10.vkCmdCopyBuffer(cb, staging.vkBuffer(), mirror.vkBuffer(), regions);
                regions.clear();
                commitSerial++;
            }
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            checkVk(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer(sodium mirror commit)");
            encoder.execute(cb);
        }
        copyCount = 0;
        fillCount = 0;
    }

    /**
     * A reusable heap-allocated copy array: a busy commit can queue
     * thousands of regions and {@code MemoryStack} is 64 KiB per thread.
     */
    private VkBufferCopy.Buffer copyScratch(int n) {
        if (copyScratch == null || copyScratch.capacity() < n) {
            if (copyScratch != null) {
                copyScratch.free();
            }
            copyScratch = VkBufferCopy.calloc(Math.max(n, 256));
        }
        return copyScratch;
    }

    // ------------------------------------------------------------------
    // Growth
    // ------------------------------------------------------------------

    /**
     * A larger mirror holding this one's bytes: the header and rows, then
     * each pass table into its new offset (three copies), the new tails
     * zero-filled, the layout words rewritten; the OLD buffer goes on
     * vanilla's deferred-destroy rotation (the copy reads it in this
     * frame's submission; the rotation destroys behind the in-flight
     * submits). The staging and stats rings move to the new instance.
     *
     * <p>Checked against the sampled memory budget first: when the growth
     * would not fit, returns null and the caller latches the GPU draw off
     * (rung 1) rather than risk an allocation failure mid-frame.
     *
     * <p>The caller DECLINES the growth frame (contract section 0.3): the
     * dirty regions are committed next frame, so the old-to-new copy and
     * the dirty copies never share a CB and no mid-CB barrier is needed.
     */
    public SodiumMirrorGpu grow(int newCapacity) {
        if (newCapacity <= capacity) {
            throw new IllegalArgumentException("grow to " + newCapacity + " from " + capacity);
        }
        long newBytes = SodiumGpuVisibilityLayout.mirrorBytes(newCapacity);
        long oldBytes = SodiumGpuVisibilityLayout.mirrorBytes(capacity);
        // A budget sampled at boot is worthless here: re-sample before the
        // one allocation decision this class makes (VK_EXT_memory_budget
        // through MeshShaderDeviceSupport; a no-op without the extension).
        MeshShaderDeviceSupport.refreshMemoryBudget(System.nanoTime());
        if (MesheliumVramState.known()) {
            long headroom = MesheliumVramState.headroomBytes();
            if (newBytes > headroom) {
                MesheliumLog.LOGGER.warn(
                        "Meshelium Sodium record mirror will not grow {} -> {} ids: {} MiB would "
                                + "exceed the sampled VRAM headroom of {} MiB.",
                        capacity, newCapacity, newBytes >> 20, headroom >> 20);
                return null;
            }
        }
        MesheliumVkBuffers.DeviceBuffer larger = MesheliumVkBuffers.createDeviceLocal(vma, newBytes,
                mirrorUsage(), "vmaCreateBuffer(meshelium sodium mirror grow)");
        SodiumMirrorGpu next = new SodiumMirrorGpu(device, encoder, newCapacity, larger,
                staging, statsRing);
        next.commitSerial = this.commitSerial + 1L; // records moved: the skip key must see it
        // The audit's block readback moves whole: the buffer AND the
        // request. A copy already recorded came from the old buffer and
        // lands in the host buffer whichever instance reads it; one not yet
        // recorded is served from the new buffer, which holds the same
        // records after the copy below. Handing over the buffer alone left
        // the mirror's pending mid waiting on a frame that could never
        // come, so the GPU half of the audit went vacuous after every
        // growth (stage-2/3 review, 2026-09-07).
        next.blockReadback = this.blockReadback;
        next.blockReadbackFrame = this.blockReadbackFrame;
        next.blockReadbackMid = this.blockReadbackMid;
        next.blockReadbackPass = this.blockReadbackPass;
        this.blockReadback = null;
        this.blockReadbackFrame = -1L;
        this.blockReadbackMid = -1;
        this.blockReadbackPass = -1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
            long headerAndRows = SodiumGpuVisibilityLayout.rowsOffset(capacity)
                    + (long) capacity * SodiumGpuVisibilityLayout.ROW_BYTES;
            long passBytes = (long) capacity * SodiumGpuVisibilityLayout.PASS_BLOCK_BYTES;
            VkBufferCopy.Buffer regions = VkBufferCopy.calloc(3, stack);
            regions.get(0).srcOffset(0L).dstOffset(0L).size(headerAndRows);
            regions.get(1)
                    .srcOffset(SodiumGpuVisibilityLayout.passOffset(capacity, SodiumGpuVisibilityLayout.PASS_SOLID))
                    .dstOffset(SodiumGpuVisibilityLayout.passOffset(newCapacity, SodiumGpuVisibilityLayout.PASS_SOLID))
                    .size(passBytes);
            regions.get(2)
                    .srcOffset(SodiumGpuVisibilityLayout.passOffset(capacity, SodiumGpuVisibilityLayout.PASS_CUTOUT))
                    .dstOffset(SodiumGpuVisibilityLayout.passOffset(newCapacity, SodiumGpuVisibilityLayout.PASS_CUTOUT))
                    .size(passBytes);
            VK10.vkCmdCopyBuffer(cb, mirror.vkBuffer(), larger.vkBuffer(), regions);
            // The tails: rows beyond the old capacity and each pass table's
            // new ids.
            long newRowsEnd = SodiumGpuVisibilityLayout.rowsOffset(newCapacity)
                    + (long) newCapacity * SodiumGpuVisibilityLayout.ROW_BYTES;
            VK10.vkCmdFillBuffer(cb, larger.vkBuffer(), headerAndRows, newRowsEnd - headerAndRows, 0);
            for (int pass = 0; pass < SodiumGpuVisibilityLayout.PASS_COUNT; pass++) {
                long tailStart = SodiumGpuVisibilityLayout.passOffset(newCapacity, pass) + passBytes;
                long tailBytes = (long) (newCapacity - capacity) * SodiumGpuVisibilityLayout.PASS_BLOCK_BYTES;
                VK10.vkCmdFillBuffer(cb, larger.vkBuffer(), tailStart, tailBytes, 0);
            }
            // The header copy carried the OLD layout words; overwrite after
            // a dependency on that copy.
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            next.writeLayoutWords(cb, stack);
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            checkVk(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer(sodium mirror grow)");
            encoder.execute(cb);
        }
        // The old buffer only: the rings moved. Deferred, behind the
        // in-flight submits that may still read it.
        long vmaHandle = this.vma;
        MesheliumVkBuffers.DeviceBuffer old = this.mirror;
        encoder.queueForDestroy(() -> MesheliumVkBuffers.destroy(vmaHandle, old.vkBuffer(), old.allocation()));
        if (copyScratch != null) {
            next.copyScratch = copyScratch;
            copyScratch = null;
        }
        this.staging = null;
        this.statsRing = null;
        MesheliumLog.LOGGER.info(
                "Meshelium Sodium record mirror grew {} -> {} ids ({} -> {} MiB); this frame is "
                        + "drawn by the list path and the mirror resumes next frame.",
                capacity, newCapacity, oldBytes >> 20, newBytes >> 20);
        return next;
    }

    // ------------------------------------------------------------------
    // Stats readback (the download-stream consumer, per owned frame)
    // ------------------------------------------------------------------

    /**
     * After the frame's last Meshelium pass: copy the four stats words into
     * the host ring's slot for {@code statsFrame}, zero them for the next
     * frame, and, when an audit block readback is pending, copy that block
     * too. Transient CB spliced via {@code execute}, barriers inside our
     * own CB only (the preceding pass-end barrier already made the shader
     * atomics visible).
     */
    public void recordStatsTransfer(long statsFrame) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
            copy.get(0).srcOffset(SodiumGpuVisibilityLayout.MIRROR_STATS_OFFSET)
                    .dstOffset((statsFrame % STATS_RING) * STATS_BYTES)
                    .size(STATS_BYTES);
            VK10.vkCmdCopyBuffer(cb, mirror.vkBuffer(), statsRing.vkBuffer(), copy);
            if (blockReadback != null && blockReadbackFrame < 0L && blockReadbackMid >= 0) {
                VkBufferCopy.Buffer block = VkBufferCopy.calloc(1, stack);
                block.get(0)
                        .srcOffset(SodiumGpuVisibilityLayout.passOffset(capacity, blockReadbackPass)
                                + (long) blockReadbackMid * SodiumGpuVisibilityLayout.PASS_BLOCK_BYTES)
                        .dstOffset(0L)
                        .size(SodiumGpuVisibilityLayout.PASS_BLOCK_BYTES);
                VK10.vkCmdCopyBuffer(cb, mirror.vkBuffer(), blockReadback.vkBuffer(), block);
                blockReadbackFrame = statsFrame;
            }
            // Read-then-zero on the same bytes: the WAR needs an explicit
            // dependency inside our own CB.
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            VK10.vkCmdFillBuffer(cb, mirror.vkBuffer(),
                    (long) SodiumGpuVisibilityLayout.MIRROR_STATS_OFFSET, STATS_BYTES, 0);
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            checkVk(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer(sodium mirror stats)");
            encoder.execute(cb);
        }
    }

    /**
     * The four stats words of {@code statsFrame} (call with the current
     * stats frame minus {@link #READBACK_LAG}; null when negative). Safe
     * without a fence by the FREE_FRAME_LAG argument; the memory is
     * HOST_COHERENT and RANDOM-access mapped.
     */
    public int[] readStats(long statsFrame) {
        if (statsFrame < 0L || statsRing == null) {
            return null;
        }
        long base = statsRing.mappedAddress() + (statsFrame % STATS_RING) * STATS_BYTES;
        ByteBuffer slot = MemoryUtil.memByteBuffer(base, STATS_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        return new int[] {slot.getInt(0), slot.getInt(4), slot.getInt(8), slot.getInt(12)};
    }

    // ------------------------------------------------------------------
    // Audit block readback (diagnostic lever only)
    // ------------------------------------------------------------------

    /**
     * Ask the next stats CB to copy one mid's pass block into a host
     * readback buffer. One at a time; ignored while a readback is pending.
     */
    public void requestBlockReadback(int mid, int pass) {
        if (blockReadbackFrame >= 0L) {
            return;
        }
        if (blockReadback == null) {
            blockReadback = MesheliumVkBuffers.createHostReadback(vma,
                    SodiumGpuVisibilityLayout.PASS_BLOCK_BYTES, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    "vmaCreateBuffer(meshelium sodium mirror audit readback)");
        }
        blockReadbackMid = mid;
        blockReadbackPass = pass;
        blockReadbackFrame = -1L;
    }

    /** The mid whose block was copied and is now waiting for its lag, or -1. */
    public int blockReadbackMid() {
        return blockReadbackFrame >= 0L ? blockReadbackMid : -1;
    }

    public int blockReadbackPass() {
        return blockReadbackPass;
    }

    /**
     * Host address of the read-back block once {@code currentStatsFrame -
     * recordedFrame >= READBACK_LAG}, else 0. Consumes the request.
     */
    public long takeBlockReadback(long currentStatsFrame) {
        if (blockReadback == null || blockReadbackFrame < 0L
                || currentStatsFrame - blockReadbackFrame < READBACK_LAG) {
            return 0L;
        }
        blockReadbackFrame = -1L;
        blockReadbackMid = -1;
        return blockReadback.mappedAddress();
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    /** Instance retirement: everything onto vanilla's deferred-destroy rotation. */
    public void destroy() {
        long vmaHandle = this.vma;
        MesheliumVkBuffers.DeviceBuffer m = this.mirror;
        VkStagingRing s = this.staging;
        MesheliumVkBuffers.MappedBuffer r = this.statsRing;
        MesheliumVkBuffers.MappedBuffer b = this.blockReadback;
        this.staging = null;
        this.statsRing = null;
        this.blockReadback = null;
        if (copyScratch != null) {
            copyScratch.free();
            copyScratch = null;
        }
        encoder.queueForDestroy(() -> {
            MesheliumVkBuffers.destroy(vmaHandle, m.vkBuffer(), m.allocation());
            if (s != null) {
                s.destroy();
            }
            if (r != null) {
                MesheliumVkBuffers.destroy(vmaHandle, r.vkBuffer(), r.allocation());
            }
            if (b != null) {
                MesheliumVkBuffers.destroy(vmaHandle, b.vkBuffer(), b.allocation());
            }
        });
    }

    /** Device close only, after vanilla's queue idle. */
    public void destroyNow() {
        MesheliumVkBuffers.destroy(vma, mirror.vkBuffer(), mirror.allocation());
        if (staging != null) {
            staging.destroy();
            staging = null;
        }
        if (statsRing != null) {
            MesheliumVkBuffers.destroy(vma, statsRing.vkBuffer(), statsRing.allocation());
            statsRing = null;
        }
        if (blockReadback != null) {
            MesheliumVkBuffers.destroy(vma, blockReadback.vkBuffer(), blockReadback.allocation());
            blockReadback = null;
        }
        if (copyScratch != null) {
            copyScratch.free();
            copyScratch = null;
        }
    }

    private static void checkVk(int result, String what) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: VkResult " + result);
        }
    }
}
