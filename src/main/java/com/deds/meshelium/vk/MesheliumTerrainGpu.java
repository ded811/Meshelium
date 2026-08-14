/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.MesheliumScaling;
import com.deds.meshelium.MesheliumVramState;
import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.fabric.mixin.GpuDeviceAccessor;
import com.deds.meshelium.terrain.RegionRecord;
import com.deds.meshelium.terrain.SectionRecord;
import com.deds.meshelium.terrain.TerrainArena;
import com.deds.meshelium.terrain.TerrainVertexCodec;
import com.deds.meshelium.terrain.host.TerrainGpuHost;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Wave 3b's GPU residency: the terrain arena VkBuffer (via
 * {@link VkArenaBacking}), the region/section metadata buffers, the 32 MiB
 * persistent staging ring, and the per-frame copy pump. Implements the
 * host-side {@link TerrainGpuHost} seam; every method runs on the render
 * thread inside vanilla's {@code dispatcher.lock()} window, under
 * {@link TerrainResidency}'s lock (lock order: vanilla {@code copyLock}
 * outermost, Meshelium's lock innermost — section-build doc 5.3).
 *
 * <p><b>Command-buffer flow</b> (frame-path Q1.2/Q3.1, all public API):
 * one {@code allocateAndBeginTransientCommandBuffer()} per pump that has
 * work; fills → full barrier → copies → full barrier;
 * {@code vkEndCommandBuffer}; {@code encoder.execute(cb)} splices it into
 * vanilla's in-flight submission in order ({@code execute} ends vanilla's
 * own current buffer, not ours — bytecode-verified). The barrier between
 * fills and copies orders the tombstone-fill-then-record-copy WAW on the
 * same bytes; the trailing barrier makes the transfer writes visible to
 * everything after (and covers arena WAW across reallocation, though
 * vanilla's after-every-pass ALL_COMMANDS barriers already would).</p>
 */
public final class MesheliumTerrainGpu implements TerrainGpuHost {

    /**
     * Frames a resource must age before reuse/free: 2 submits in flight
     * (VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT, javap -constants) + 1
     * safety frame. One shared constant with the CPU store's epoch gate;
     * full derivation on {@link VkStagingRing}.
     */
    static final int FREE_FRAME_LAG = TerrainResidency.FREE_FRAME_LAG;

    /**
     * Terrain arena INITIAL capacity — 256 MiB at every pin since wave 14
     * ({@link VkArenaBacking} javadoc carries the original 256 MiB
     * rationale). Waves 3b–13 treated this as the world's FIXED capacity,
     * sized by a density formula calibrated on the rd-32 plains bench —
     * and the owner's first real overworld session overflowed it on a
     * 16 GiB card (real terrain packs several-fold more quads per section
     * than the bench; arithmetic in docs/VANILLA-SECTION-BUILD.md
     * wave-14 note). Since wave 14 the arena GROWS on demand: an
     * allocation failure in the pump triggers a ×1.5 grow-and-copy
     * ({@link #growArena}) up to the device-derived ceiling
     * ({@code MesheliumScaling.arenaCeilingBytes}: default
     * {@code ARENA_CEILING_HEAP_PCT}% of the largest DEVICE_LOCAL heap),
     * and the failed upload retries next pump instead of dropping. The
     * wave-8 guard remains the backstop — but its arena trip now requires
     * growth EXHAUSTED (at ceiling / allocation failed) with nothing
     * retained left to evict, so a too-small ceiling still can never mean
     * holes, only "Meshelium sat this world out" — now with the tripping
     * budget named in the WARN and the options-screen status line.
     */
    static final long ARENA_INITIAL_BYTES = MesheliumScaling.STANDARD_ARENA_BYTES;

    /**
     * The initial allocation, resolved at world standup (this method runs
     * at {@link #create}, AFTER the scaling snapshot was pinned):
     * {@code meshelium.test.arenaMiB} (wave-8 torture — since wave 14 it
     * pins the CEILING too, so the guard leg still deterministically
     * forces growth-exhausted drops) ?? {@code meshelium.tune.arenaInitialMiB}
     * (wave-14 growth leg: tiny initial, normal ceiling) ?? 256 MiB —
     * always ≤ the ceiling. Resolution lives in
     * {@code MesheliumScaling.arenaInitialBytes()}.
     */
    static long arenaBytes() {
        long testMiB = Long.getLong("meshelium.test.arenaMiB", 0L);
        if (testMiB > 0) {
            MesheliumClient.LOGGER.warn(
                    "meshelium.test.arenaMiB={} — TEST-ONLY tiny terrain arena (initial AND "
                            + "growth ceiling) in use; expect drops and a coverage-guard trip",
                    testMiB);
        }
        return MesheliumScaling.arenaInitialBytes();
    }

    /**
     * Staging ring capacity — Nvidium's UploadingBufferStream constant
     * (32,000,000 B, NVIDIUM-ARCHITECTURE.md §3) rounded up to 32 MiB.
     */
    static final int STAGING_BYTES = 32 << 20;

    /** Budget re-sample interval. Seconds, because that is how fast it moves. */
    private static final long BUDGET_SAMPLE_INTERVAL_NANOS = 1_000_000_000L;

    private final VulkanCommandEncoder encoder;
    private final long vma;

    private final VkArenaBacking arenaBacking;
    /** Non-final since wave 15: {@link #growRecords} swaps both (grow-and-copy). */
    private MesheliumVkBuffers.DeviceBuffer regionBuffer;
    private MesheliumVkBuffers.DeviceBuffer sectionBuffer;
    private final VkStagingRing ring;
    /** The maxRegions both record buffers are currently sized for. */
    private int recordCapacityRegions;

    /** Per-frame queued ops: {srcRingOffset, dstOffset, size}. */
    private final List<long[]> arenaCopies = new ArrayList<>();
    /** Wave-7 LATE arena copies (resort prefixes): recorded after the
     *  normal batch behind a barrier — see {@code stageArenaCopyLate}. */
    private final List<long[]> arenaLateCopies = new ArrayList<>();
    private final List<long[]> sectionCopies = new ArrayList<>();
    private final List<long[]> regionCopies = new ArrayList<>();
    /** {dstBuffer, offset, size, value} for vkCmdFillBuffer. */
    private final List<long[]> fills = new ArrayList<>();

    /**
     * Wave-14: outgrown arena backings awaiting fence-gated destruction —
     * {parkFrame, vkBuffer, allocation}. Parked by {@link #growArena} at
     * the pump frame that submitted the old→new copy; destroyed by
     * {@link #beginFrame} once {@code frame − parkFrame ≥ FREE_FRAME_LAG}
     * (2 submits in flight + 1 safety — the same derivation as every
     * other free in this file), with the lag re-checked at destroy time
     * as an explicit fence assert. Swept into vanilla's deferred-destroy
     * rotation by {@link #destroy}, destroyed directly by
     * {@link #destroyNow} (post-waitIdle only).
     */
    private final ArrayDeque<long[]> retiredBackings = new ArrayDeque<>();
    /** The pump frame most recently begun (stamp source for retirement). */
    private long currentFrame;
    /** Wave-14 probes (volatile: gametests read them off-thread). */
    private static volatile long arenaBuffersRetired;
    /** Wave-15 probe: successful record-buffer grow-and-copies. */
    private static volatile long recordGrowths;

    private MesheliumTerrainGpu(VulkanDevice device, VulkanCommandEncoder encoder,
            VkArenaBacking arenaBacking, MesheliumVkBuffers.DeviceBuffer regionBuffer,
            MesheliumVkBuffers.DeviceBuffer sectionBuffer, VkStagingRing ring) {
        this.encoder = encoder;
        this.vma = device.vma();
        this.arenaBacking = arenaBacking;
        this.regionBuffer = regionBuffer;
        this.sectionBuffer = sectionBuffer;
        this.ring = ring;
    }

    /**
     * Build the whole GPU side, attach the arena to the residency store,
     * and zero-initialize all three device buffers (the §2 port note:
     * freshly created VkBuffer memory is undefined, and the region/section
     * consumers' emptiness checks depend on zeroed slots).
     *
     * @return null when the device facade isn't up yet (retry next frame)
     */
    static MesheliumTerrainGpu create() {
        GpuDevice facade = RenderSystem.tryGetDevice();
        if (facade == null) {
            return null;
        }
        VulkanDevice device = (VulkanDevice) ((GpuDeviceAccessor) (Object) facade).meshelium$backend();
        VulkanCommandEncoder encoder = device.createCommandEncoder(); // singleton (frame-path Q1.2)
        long vma = device.vma();

        // Wave-10/13: pin this world's scaling snapshot FIRST — everything
        // sized below (arena, region/section records) and everything the
        // world creates later (occlusion stamps, frame lists, the
        // RegionStore's id budget) reads the same pinned values. Wave 13
        // pins from the RAW option value at standup (not the config
        // ceiling — a player at rd 12 under a 96 ceiling pins standard
        // sizes; not getEffectiveRenderDistance() — its server-radius
        // half can be a stale login value in the standup frame, see the
        // MesheliumScaling javadoc). Render thread by contract, so the
        // option read is safe; the once-per-world rejoin hint re-arms
        // against this fresh snapshot (MesheliumExtendedRd.onWorldPinned).
        MesheliumScaling.pinForWorld(
                net.minecraft.client.Minecraft.getInstance().options.renderDistance().get());
        com.deds.meshelium.MesheliumExtendedRd.onWorldPinned();
        // Arm the upload seam HERE and nowhere else. Vanilla's heaps only
        // release when an allocator is completely free, so suppressing
        // mid-session frees nothing; the saving comes from heaps never
        // being committed in the first place, which means arming before
        // the world has any.
        com.deds.meshelium.terrain.host.VanillaUploadSeam.armForNewWorld();

        // EVERY allocation below is unwound if a LATER one throws. Without
        // this the standup was a leak waiting for a full card: the object
        // that owns these handles is not constructed until the end, so a
        // throw at the staging ring stranded the arena and both record
        // buffers with no surviving reference to free them by - hundreds of
        // megabytes, invisible, for the rest of the process. It is
        // unreachable while allocations succeed, which is exactly why it
        // has never been seen, and it becomes reachable the moment the
        // ceiling goes up. The split multiplies the surface by N.
        //
        // Direct destroys, not encoder.queueForDestroy: nothing here has
        // ever been referenced by a command buffer, so there is no fence to
        // respect, and the deferred queue may not be usable if the failure
        // happened during standup.
        VkArenaBacking arenaBacking = new VkArenaBacking(vma);
        MesheliumVkBuffers.DeviceBuffer regionBuffer = null;
        MesheliumVkBuffers.DeviceBuffer sectionBuffer = null;
        VkStagingRing ring = null;
        boolean handedOff = false;
        try {
            // The TerrainArena ctor calls arenaBacking.allocate(arenaBytes).
            long arenaBytes = arenaBytes();
            TerrainArena arena = new TerrainArena(arenaBacking, arenaBytes,
                    TerrainVertexCodec.VERTEX_STRIDE,
                    MesheliumScaling.arenaBlockBytes(), MesheliumScaling.arenaBlockCount());
            long regionBytes = RegionRecord.regionBufferBytes(TerrainResidency.maxRegions());
            long sectionBytes = SectionRecord.sectionBufferBytes(TerrainResidency.maxRegions());
            regionBuffer = MesheliumVkBuffers.createDeviceLocal(vma,
                    regionBytes, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    "vmaCreateBuffer(meshelium region records)");
            sectionBuffer = MesheliumVkBuffers.createDeviceLocal(vma,
                    sectionBytes, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    "vmaCreateBuffer(meshelium section records)");
            ring = VkStagingRing.create(vma, STAGING_BYTES);

            MesheliumTerrainGpu gpu = new MesheliumTerrainGpu(
                    device, encoder, arenaBacking, regionBuffer, sectionBuffer, ring);
            gpu.recordCapacityRegions = TerrainResidency.maxRegions();
            gpu.zeroInitialize(regionBytes, sectionBytes);
            handedOff = true; // gpu owns all of it from here; destroy() frees it
            return finishCreate(gpu, arena, arenaBytes, regionBytes, sectionBytes,
                    sectionBuffer, device);
        } finally {
            if (!handedOff) {
                releasePartialStandup(vma, arenaBacking, regionBuffer, sectionBuffer, ring);
            }
        }
    }

    /**
     * Unwind a standup that threw part-way. Each step is independently
     * guarded: one failing destroy must not strand the buffers after it,
     * because this only ever runs when something has already gone wrong.
     */
    private static void releasePartialStandup(long vma, VkArenaBacking arenaBacking,
            MesheliumVkBuffers.DeviceBuffer regionBuffer,
            MesheliumVkBuffers.DeviceBuffer sectionBuffer, VkStagingRing ring) {
        MesheliumClient.LOGGER.warn("Meshelium terrain standup failed part-way - releasing "
                + "whatever was already allocated so it does not leak for the process lifetime");
        try {
            for (long[] block : arenaBacking.takeAllForDestroy()) {
                if (block[0] != 0L) {
                    MesheliumVkBuffers.destroy(vma, block[0], block[1]);
                }
            }
        } catch (Throwable t) {
            MesheliumClient.LOGGER.warn("Meshelium: arena backing release failed during unwind", t);
        }
        for (MesheliumVkBuffers.DeviceBuffer b : new MesheliumVkBuffers.DeviceBuffer[] {
                regionBuffer, sectionBuffer }) {
            if (b == null) {
                continue;
            }
            try {
                MesheliumVkBuffers.destroy(vma, b.vkBuffer(), b.allocation());
            } catch (Throwable t) {
                MesheliumClient.LOGGER.warn("Meshelium: record buffer release failed during unwind", t);
            }
        }
        if (ring != null) {
            try {
                ring.destroy();
            } catch (Throwable t) {
                MesheliumClient.LOGGER.warn("Meshelium: staging ring release failed during unwind", t);
            }
        }
    }

    private static MesheliumTerrainGpu finishCreate(MesheliumTerrainGpu gpu, TerrainArena arena,
            long arenaBytes, long regionBytes, long sectionBytes,
            MesheliumVkBuffers.DeviceBuffer sectionBuffer, VulkanDevice device) {
        // The section-records buffer handle rides along for wave 5's task
        // stage (opaque long — the host package stays LWJGL-free).
        TerrainResidency.attachArena(arena, sectionBuffer.vkBuffer());
        MesheliumClient.LOGGER.info(
                "Meshelium terrain GPU residency up: arena {} MiB initial (elastic to the {} MiB "
                        + "device ceiling, wave 14) + region {} KiB + section {} MiB records + "
                        + "staging ring {} MiB (device '{}')",
                arenaBytes >> 20, MesheliumScaling.arenaCeilingBytes() >> 20,
                regionBytes >> 10, sectionBytes >> 20, STAGING_BYTES >> 20,
                device.getDeviceInfo().name());
        return gpu;
    }

    private void zeroInitialize(long regionBytes, long sectionBytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
            VK10.vkCmdFillBuffer(cb, arenaBacking.vkBuffer(), 0, arenaBacking.sizeBytes(), 0);
            VK10.vkCmdFillBuffer(cb, regionBuffer.vkBuffer(), 0, regionBytes, 0);
            VK10.vkCmdFillBuffer(cb, sectionBuffer.vkBuffer(), 0, sectionBytes, 0);
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            checkVk(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer(zero-init)");
            encoder.execute(cb);
        }
    }

    // ------------------------------------------------------------------
    // TerrainGpuHost
    // ------------------------------------------------------------------

    @Override
    public boolean beginFrame(long frame) {
        currentFrame = frame;
        long now = System.nanoTime();
        if (now - MesheliumVramState.lastSampleNanos() >= BUDGET_SAMPLE_INTERVAL_NANOS) {
            MeshShaderDeviceSupport.refreshMemoryBudget(now);
        }
        // Wave-14: destroy outgrown arena backings whose last possible
        // reader (frames in flight at park time, incl. the old→new copy
        // itself) has provably completed — the FREE_FRAME_LAG derivation.
        while (!retiredBackings.isEmpty()
                && frame - retiredBackings.peekFirst()[0] >= FREE_FRAME_LAG) {
            long[] retired = retiredBackings.pollFirst();
            if (frame - retired[0] < FREE_FRAME_LAG) {
                // Structurally unreachable (the loop condition IS the
                // fence); kept as the wave-14 fence assert — a violation
                // here would be a use-after-free the validation layer
                // would also catch, and must never be destroyed quietly.
                throw new IllegalStateException("arena backing retired after "
                        + (frame - retired[0]) + " frames < FREE_FRAME_LAG " + FREE_FRAME_LAG);
            }
            MesheliumVkBuffers.destroy(vma, retired[1], retired[2]);
            arenaBuffersRetired++;
        }
        ring.beginFrame(frame);
        arenaCopies.clear();
        arenaLateCopies.clear();
        sectionCopies.clear();
        regionCopies.clear();
        fills.clear();
        return true;
    }

    @Override
    public long maxStageBytes() {
        return ring.capacity();
    }

    @Override
    public long stagingUsedBytes() {
        return ring.usedBytes();
    }

    @Override
    public boolean stageArenaCopy(ByteBuffer data, int block, long blockByteOffset) {
        return stage(data, blockByteOffset, arenaCopies, block);
    }

    @Override
    public boolean stageArenaCopyLate(ByteBuffer data, int block, long blockByteOffset) {
        return stage(data, blockByteOffset, arenaLateCopies, block);
    }

    @Override
    public boolean stageSectionRecords(ByteBuffer data, long byteOffset) {
        return stage(data, byteOffset, sectionCopies);
    }

    @Override
    public boolean stageRegionRecord(ByteBuffer data, long byteOffset) {
        return stage(data, byteOffset, regionCopies);
    }

    private boolean stage(ByteBuffer data, long dstOffset, List<long[]> copies) {
        return stage(data, dstOffset, copies, 0);
    }

    /**
     * @param block destination arena block. Carried in the tuple because an
     *        arena copy's offset is meaningless without knowing WHICH buffer
     *        it indexes; the record/section copies always target one buffer
     *        and pass 0.
     */
    private boolean stage(ByteBuffer data, long dstOffset, List<long[]> copies, int block) {
        int size = data.remaining();
        if (size <= 0) {
            return true;
        }
        long srcOffset = ring.alloc(size);
        if (srcOffset < 0) {
            return false; // ring full — caller backlogs, never spins
        }
        // Heap-safe copy into the persistent mapping (geometry and mirrors
        // are heap buffers; memByteBuffer wraps the mapped range).
        MemoryUtil.memByteBuffer(ring.mappedAddress() + srcOffset, size).put(data.duplicate());
        copies.add(new long[] {srcOffset, dstOffset, size, block});
        return true;
    }

    /**
     * Wave-14 grow-and-copy (contract on {@link TerrainGpuHost#growArena}).
     * Runs mid-pump on the render thread, between {@link #beginFrame} and
     * {@link #endFrame}, when no other Meshelium command buffer is open —
     * the same transient-buffer pattern as {@link #zeroInitialize}. The
     * whole growth is one atomic step: allocate → record zero-fill of the
     * new tail + copy of every old byte to identical offsets → full
     * barrier → submit via {@code encoder.execute} (spliced into
     * vanilla's in-flight submission IN ORDER, so it lands strictly
     * before this pump's own {@link #endFrame} buffer — the copies staged
     * this pump then overwrite freshly copied bytes in a barrier-ordered
     * WAW, never the reverse) → swap the current backing → park the old
     * buffer for {@code FREE_FRAME_LAG}-fenced destruction. Any failure
     * is caught HERE and leaves every field untouched (the fresh buffer,
     * if created, is destroyed) — the caller sees 0 and treats growth as
     * exhausted; a throw must never leave the allocator pointing at a
     * buffer whose copy was not submitted.
     *
     * <p>Why grow-and-copy and not the alternatives: the drawer binds the
     * arena as ONE whole-buffer SSBO (push-descriptored per pass from the
     * snapshot's opaque handle), so a same-offsets replacement buffer is
     * a one-frame handle swap with zero shader/record changes. Multi-block
     * paging would turn every {@code terrainAddress} into a
     * {@code (block, offset)} pair — a record-format and shader change
     * for no benefit at these sizes. Sparse binding is unavailable as
     * created: vanilla's device enables no sparse features and its VMA
     * allocator is plain (bytecode-verified in wave 3b for the BDA
     * finding; same chain) — and Nvidium's own non-sparse fallback is the
     * lineage this arena already ports.</p>
     */
    @Override
    public void onArenaBlockAppended(long blockBytes) {
        // Zero the fresh block. Its bytes are undefined out of VMA, and
        // while nothing should ever read an unallocated range, "should" is
        // what the wave-14 bug was made of: a defined zero reads back as an
        // empty section rather than as arbitrary geometry.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
            VK10.vkCmdFillBuffer(cb, arenaBacking.lastBlockBuffer(), 0, blockBytes, 0);
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            checkVk(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer(meshelium arena append)");
            encoder.execute(cb);
        }
        MesheliumClient.LOGGER.info(
                "Meshelium terrain arena APPENDED a {} MiB block (no copy, no retirement - every "
                        + "existing address keeps its meaning; total {} MiB across {} blocks, "
                        + "ceiling {} MiB)",
                blockBytes >> 20, arenaBacking.sizeBytes() >> 20, arenaBacking.blockCount(),
                MesheliumScaling.arenaCeilingBytes() >> 20);
    }

    public long growArena(long newSizeBytes) {
        long oldSize = arenaBacking.lastBlockBytes();
        if (newSizeBytes <= oldSize) {
            return 0L;
        }
        MesheliumVkBuffers.DeviceBuffer grown = null;
        try {
            grown = MesheliumVkBuffers.createDeviceLocal(vma, newSizeBytes,
                    VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    "vmaCreateBuffer(meshelium terrain arena growth)");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
                // New tail zeroed (fresh VMA memory is undefined; keeps the
                // standup invariant that arena bytes below the extent are
                // defined), old bytes copied to IDENTICAL offsets — every
                // live quad address stays valid across the swap.
                VK10.vkCmdFillBuffer(cb, grown.vkBuffer(), oldSize, newSizeBytes - oldSize, 0);
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                region.get(0).srcOffset(0).dstOffset(0).size(oldSize);
                VK10.vkCmdCopyBuffer(cb, arenaBacking.lastBlockBuffer(), grown.vkBuffer(), region);
                VulkanCommandEncoder.memoryBarrier(cb, stack);
                checkVk(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer(meshelium arena growth)");
                encoder.execute(cb);
            }
        } catch (Throwable t) {
            if (grown != null) {
                MesheliumVkBuffers.destroy(vma, grown.vkBuffer(), grown.allocation());
            }
            MesheliumClient.LOGGER.warn(
                    "Meshelium terrain arena growth to {} MiB failed (treated as growth "
                            + "exhausted; the coverage guard remains the backstop): {}",
                    newSizeBytes >> 20, t.toString());
            return 0L;
        }
        // Copy submitted — the swap + park below cannot fail.
        long[] old = arenaBacking.swapForGrowth(grown, newSizeBytes);
        retiredBackings.addLast(new long[] {currentFrame, old[0], old[1]});
        MesheliumClient.LOGGER.info(
                "Meshelium terrain arena grown {} -> {} MiB (grow-and-copy; old buffer retires "
                        + "after {} frames; ceiling {} MiB)",
                oldSize >> 20, newSizeBytes >> 20, FREE_FRAME_LAG,
                MesheliumScaling.arenaCeilingBytes() >> 20);
        return grown.vkBuffer();
    }

    /** Wave-14 probe: outgrown arena backings destroyed after their fence lag. */
    public static long arenaBuffersRetired() {
        return arenaBuffersRetired;
    }

    /**
     * Wave-15 grow-and-copy for the two regionId-indexed record buffers —
     * the GPU half of the live mid-world render-distance raise (contract
     * on {@link TerrainGpuHost#growRecords}). Same shape as
     * {@link #growArena}: one transient command buffer records, for EACH
     * buffer, a zero-fill of the new tail (fresh VMA memory is undefined;
     * ids beyond the old budget must read as the standup emptiness the
     * consumers' checks depend on) and a copy of every old byte to
     * IDENTICAL offsets ({@code regionId × 16} / {@code regionId × 8192}
     * addressing is position-stable, so no live record moves), then a
     * full barrier; {@code encoder.execute} splices it strictly before
     * this pump's own {@link #endFrame} buffer, so record copies staged
     * THIS pump overwrite freshly copied bytes in a barrier-ordered WAW,
     * never the reverse. The old pair parks on the same
     * {@code retiredBackings} fence queue as outgrown arenas
     * (FREE_FRAME_LAG pumps; frames in flight may still read the old
     * handles from the last published draw snapshot). Any failure leaves
     * both fields untouched and destroys whatever was created.
     *
     * <p>On success this also tells the drawer to drop its per-world
     * occlusion resources and extended frame lists
     * ({@link TerrainDrawer#onPinnedRegrow}) — both are sized from the
     * scaling snapshot the caller is about to swap, and the stamp buffers
     * are indexed by regionId: a draw with a new-budget id against the
     * old-sized stamps would write out of bounds. Dropping them here, in
     * the same pump, means they recreate at the new sizes before the
     * frame's first draw (the create paths are the lazy per-world ones
     * that already run at every world standup).</p>
     */
    @Override
    public long growRecords(int newMaxRegions) {
        if (newMaxRegions <= recordCapacityRegions) {
            return 0L;
        }
        long oldRegionBytes = RegionRecord.regionBufferBytes(recordCapacityRegions);
        long oldSectionBytes = SectionRecord.sectionBufferBytes(recordCapacityRegions);
        long newRegionBytes = RegionRecord.regionBufferBytes(newMaxRegions);
        long newSectionBytes = SectionRecord.sectionBufferBytes(newMaxRegions);
        MesheliumVkBuffers.DeviceBuffer grownRegion = null;
        MesheliumVkBuffers.DeviceBuffer grownSection = null;
        try {
            int usage = VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                    | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            grownRegion = MesheliumVkBuffers.createDeviceLocal(vma, newRegionBytes, usage,
                    "vmaCreateBuffer(meshelium region records growth)");
            grownSection = MesheliumVkBuffers.createDeviceLocal(vma, newSectionBytes, usage,
                    "vmaCreateBuffer(meshelium section records growth)");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
                VK10.vkCmdFillBuffer(cb, grownRegion.vkBuffer(),
                        oldRegionBytes, newRegionBytes - oldRegionBytes, 0);
                VK10.vkCmdFillBuffer(cb, grownSection.vkBuffer(),
                        oldSectionBytes, newSectionBytes - oldSectionBytes, 0);
                VkBufferCopy.Buffer regionCopy = VkBufferCopy.calloc(1, stack);
                regionCopy.get(0).srcOffset(0).dstOffset(0).size(oldRegionBytes);
                VK10.vkCmdCopyBuffer(cb, regionBuffer.vkBuffer(), grownRegion.vkBuffer(), regionCopy);
                VkBufferCopy.Buffer sectionCopy = VkBufferCopy.calloc(1, stack);
                sectionCopy.get(0).srcOffset(0).dstOffset(0).size(oldSectionBytes);
                VK10.vkCmdCopyBuffer(cb, sectionBuffer.vkBuffer(), grownSection.vkBuffer(), sectionCopy);
                VulkanCommandEncoder.memoryBarrier(cb, stack);
                checkVk(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer(meshelium record growth)");
                encoder.execute(cb);
            }
        } catch (Throwable t) {
            if (grownRegion != null) {
                MesheliumVkBuffers.destroy(vma, grownRegion.vkBuffer(), grownRegion.allocation());
            }
            if (grownSection != null) {
                MesheliumVkBuffers.destroy(vma, grownSection.vkBuffer(), grownSection.allocation());
            }
            MesheliumClient.LOGGER.warn(
                    "Meshelium record-buffer growth to {} regions failed (the live render-distance "
                            + "raise falls back to the rejoin hint): {}",
                    newMaxRegions, t.toString());
            return 0L;
        }
        // Copies submitted — the swap + park below cannot fail.
        retiredBackings.addLast(new long[] {currentFrame,
                regionBuffer.vkBuffer(), regionBuffer.allocation()});
        retiredBackings.addLast(new long[] {currentFrame,
                sectionBuffer.vkBuffer(), sectionBuffer.allocation()});
        regionBuffer = grownRegion;
        sectionBuffer = grownSection;
        int oldCapacity = recordCapacityRegions;
        recordCapacityRegions = newMaxRegions;
        recordGrowths++;
        // The drawer's snapshot-sized resources recreate at the new sizes
        // before any draw can index past the old budget (method javadoc).
        TerrainDrawer.onPinnedRegrow();
        MesheliumClient.LOGGER.info(
                "Meshelium record buffers grown {} -> {} regions (region records {} KiB, section "
                        + "records {} MiB; old pair retires after {} frames; live render-distance "
                        + "raise, wave 15)",
                oldCapacity, newMaxRegions, newRegionBytes >> 10, newSectionBytes >> 20,
                FREE_FRAME_LAG);
        return sectionBuffer.vkBuffer();
    }

    /** Wave-15 probe: successful record-buffer grow-and-copies this session. */
    public static long recordGrowths() {
        return recordGrowths;
    }

    /** Wave-15 (contract on {@link TerrainGpuHost#dropSnapshotSizedDrawResources}). */
    @Override
    public void dropSnapshotSizedDrawResources() {
        TerrainDrawer.onPinnedRegrow();
    }

    @Override
    public void fillSectionBlockZero(long byteOffset, long bytes) {
        fills.add(new long[] {sectionBuffer.vkBuffer(), byteOffset, bytes, 0L});
    }

    @Override
    public void fillRegionTombstone(long byteOffset) {
        // All 16 bytes 0xFF — RegionRecord's exact tombstone value
        // (memSet(-1) in the original; GPU checks a == uint64_t(-1)).
        fills.add(new long[] {regionBuffer.vkBuffer(), byteOffset, RegionRecord.META_SIZE, 0xFFFFFFFFL});
    }

    @Override
    public void endFrame() {
        if (arenaCopies.isEmpty() && arenaLateCopies.isEmpty() && sectionCopies.isEmpty()
                && regionCopies.isEmpty() && fills.isEmpty()) {
            return; // idle frame: no command buffer, no allocations
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
            if (!fills.isEmpty()) {
                for (long[] fill : fills) {
                    VK10.vkCmdFillBuffer(cb, fill[0], fill[1], fill[2], (int) fill[3]);
                }
                // Order tombstone fills before record copies that may touch
                // the same bytes (WAW needs an explicit dependency).
                VulkanCommandEncoder.memoryBarrier(cb, stack);
            }
            recordArenaCopies(cb, stack, arenaCopies);
            recordCopies(cb, stack, sectionCopies, sectionBuffer.vkBuffer());
            recordCopies(cb, stack, regionCopies, regionBuffer.vkBuffer());
            if (!arenaLateCopies.isEmpty()) {
                // Wave-7 LATE batch (resort prefixes): a permuted prefix may
                // overwrite bytes the normal batch just wrote for the same
                // section — barrier makes the WAW an ordered dependency.
                VulkanCommandEncoder.memoryBarrier(cb, stack);
                recordArenaCopies(cb, stack, arenaLateCopies);
            }
            VulkanCommandEncoder.memoryBarrier(cb, stack);
            checkVk(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer(meshelium terrain pump)");
            encoder.execute(cb);
        } finally {
            arenaCopies.clear();
            arenaLateCopies.clear();
            sectionCopies.clear();
            regionCopies.clear();
            fills.clear();
        }
    }

    /** Batch one vkCmdCopyBuffer per destination, ≤256 regions per call (stack budget). */
    /**
     * Arena copies, routed to the block each one names.
     *
     * <p>Grouped by destination rather than issued one at a time: sections
     * stream in bursts and almost all of a pump's copies land in the same
     * block, so this is normally one vkCmdCopyBuffer batch exactly like
     * before the split. It costs a pass over the list to partition, which
     * is nothing beside the copies themselves.</p>
     */
    private void recordArenaCopies(VkCommandBuffer cb, MemoryStack stack, List<long[]> copies) {
        if (copies.isEmpty()) {
            return;
        }
        long[] handles = arenaBacking.blockHandles();
        int blocks = handles.length;
        if (blocks == 1) {
            recordCopies(cb, stack, copies, handles[0]);
            return;
        }
        List<long[]> perBlock = new ArrayList<>();
        for (int b = 0; b < blocks; b++) {
            perBlock.clear();
            for (long[] copy : copies) {
                if ((int) copy[3] == b) {
                    perBlock.add(copy);
                }
            }
            if (!perBlock.isEmpty()) {
                recordCopies(cb, stack, perBlock, handles[b]);
            }
        }
    }

    private void recordCopies(VkCommandBuffer cb, MemoryStack stack, List<long[]> copies, long dst) {
        for (int start = 0; start < copies.size(); start += 256) {
            int n = Math.min(256, copies.size() - start);
            VkBufferCopy.Buffer regions = VkBufferCopy.calloc(n, stack);
            for (int i = 0; i < n; i++) {
                long[] copy = copies.get(start + i);
                regions.get(i).srcOffset(copy[0]).dstOffset(copy[1]).size(copy[2]);
            }
            VK10.vkCmdCopyBuffer(cb, ring.vkBuffer(), dst, regions);
        }
    }

    /**
     * Queue every buffer for vanilla's deferred-destroy rotation
     * (frame-path Q6.6: {@code queueForDestroy} destroys once the submit
     * that could last reference the memory has completed). Render thread —
     * vanilla calls {@code dispose()} there (LevelRenderer teardown).
     */
    void destroy() {
        // EVERY block, not block 0. takeForDestroy() returned only the first
        // pair while takeAllForDestroy() underneath it CLEARED the whole
        // list, so blocks 1..N-1 were dropped on the floor: their VkBuffers
        // and VMA allocations were never destroyed. At 512 MiB a block that
        // is the entire arena minus one block leaked on every world exit,
        // and a long-render-distance session leaked gigabytes per rejoin.
        long[][] arenaHandles = arenaBacking.takeAllForDestroy();
        // Wave-14: outgrown backings still inside their fence lag ride the
        // same deferred-destroy rotation (their last reader is in flight
        // by definition of the lag — exactly what queueForDestroy fences).
        long[][] parked = retiredBackings.toArray(new long[0][]);
        retiredBackings.clear();
        long vmaHandle = this.vma;
        encoder.queueForDestroy(() -> {
            for (long[] block : arenaHandles) {
                MesheliumVkBuffers.destroy(vmaHandle, block[0], block[1]);
            }
            for (long[] old : parked) {
                MesheliumVkBuffers.destroy(vmaHandle, old[1], old[2]);
            }
            MesheliumVkBuffers.destroy(vmaHandle, regionBuffer.vkBuffer(), regionBuffer.allocation());
            MesheliumVkBuffers.destroy(vmaHandle, sectionBuffer.vkBuffer(), sectionBuffer.allocation());
            ring.destroy();
        });
    }

    /**
     * Wave-8 defensive teardown: destroy every buffer DIRECTLY, bypassing
     * the deferred-destroy queue — only legal at device close (after
     * vanilla's {@code waitIdle}, when the destroy queue is already
     * drained and closed). The normal path is {@link #destroy()}; this
     * exists so a dispose hook that never fired cannot leak into
     * {@code vmaDestroyAllocator}.
     */
    void destroyNow() {
        for (long[] block : arenaBacking.takeAllForDestroy()) {
            MesheliumVkBuffers.destroy(vma, block[0], block[1]);
        }
        while (!retiredBackings.isEmpty()) { // wave-14: legal only post-waitIdle
            long[] old = retiredBackings.pollFirst();
            MesheliumVkBuffers.destroy(vma, old[1], old[2]);
        }
        MesheliumVkBuffers.destroy(vma, regionBuffer.vkBuffer(), regionBuffer.allocation());
        MesheliumVkBuffers.destroy(vma, sectionBuffer.vkBuffer(), sectionBuffer.allocation());
        ring.destroy();
    }

    private static void checkVk(int result, String what) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: VkResult " + result);
        }
    }
}
