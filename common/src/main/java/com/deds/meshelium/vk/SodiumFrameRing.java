/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.mixin.GpuDeviceAccessor;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The per-frame region list and its indirect commands for the GPU-visibility
 * draw on the Sodium host (D-023, stage 2): a device-mapped ring the CPU
 * fills once per OWNED frame and the task stage reads once per workgroup.
 *
 * <h2>Why a second buffer beside the mirror</h2>
 * <p>The mirror's rows and records are read by every task lane and written
 * by in-stream GPU copies, which wants DEVICE_LOCAL memory; this list is
 * rewritten by the CPU every frame and read once per task workgroup, which
 * wants the run table's memory class ({@link MesheliumVkBuffers#createDeviceMapped}:
 * HOST_VISIBLE|COHERENT required, DEVICE_LOCAL preferred; MEASUREMENTS.md 0k
 * priced the host-memory alternative at ~0.5 ms a frame). One buffer
 * cannot be both, so there are two; the task stage still binds exactly
 * four storage buffers ({@link SodiumGpuVisibilityLayout#TASK_STAGE_STORAGE_BUFFERS}).
 *
 * <h2>Why a ring, advanced once per owned frame</h2>
 * <p>The bytes are read by the GPU for as long as the frame that referenced
 * them is in flight, and nothing here waits for a fence, so slots rotate
 * (the {@link MesheliumSodiumRunTable} argument). Unlike the run table the
 * slot advances once per FRAME, not per pass: both opaque passes of an
 * owned frame read the same list, which is what lets the CUTOUT call reuse
 * the SOLID call's slot and its group tables. Eight slots at four frames of
 * lag is the run table's margin; the per-submit guard
 * ({@link #guardTripped()}, {@code SLOTS / 2}) is the same bound for the
 * same reason: vanilla's panorama screenshot renders the level six times
 * between submits (D-014).
 *
 * <h2>Write-only</h2>
 * <p>The mapping declares {@code HOST_ACCESS_SEQUENTIAL_WRITE} and must
 * never be read back ({@link MesheliumVkBuffers} rule); the CPU keeps its own
 * copy of anything it needs to compare across frames.
 */
public final class SodiumFrameRing {

    private static final int SLOTS = SodiumGpuVisibilityLayout.RING_SLOTS;

    /**
     * Owned-frame advances since vanilla's last queue submit, reset by
     * {@link #onSubmit()} from {@code SodiumTerrainDrawer.onEncoderSubmit()}
     * (the existing {@code VulkanCommandEncoderMixin} RETURN hook).
     */
    private static int advancesSinceSubmit;

    /**
     * Queue submits RETURNED this session: the staging ring's retirement
     * clock ({@link #submitsReturned()}).
     */
    private static long submitsReturned;

    private static long guardTrips;

    private final VulkanCommandEncoder encoder;
    private final long vma;
    private final MesheliumVkBuffers.MappedBuffer list;
    private final MesheliumVkBuffers.MappedBuffer indirect;
    private final int capacity;
    private final long listSlotBytes;
    private final long indirectSlotBytes;
    private long record;

    private SodiumFrameRing(VulkanCommandEncoder encoder, long vma,
            MesheliumVkBuffers.MappedBuffer list, MesheliumVkBuffers.MappedBuffer indirect,
            int capacity) {
        this.encoder = encoder;
        this.vma = vma;
        this.list = list;
        this.indirect = indirect;
        this.capacity = capacity;
        this.listSlotBytes = (long) capacity * SodiumGpuVisibilityLayout.LIST_ENTRY_BYTES;
        this.indirectSlotBytes = (long) (capacity + SodiumGpuVisibilityLayout.MAX_GROUP_COMMANDS)
                * SodiumGpuVisibilityLayout.INDIRECT_BYTES;
    }

    /**
     * Render thread. Null while the device facade is not up yet, which the
     * caller treats as "not this frame" rather than as failure.
     *
     * @param capacity list entries per slot: the mirror's mid capacity
     *        (every listed region carries a mid, so listed is at most capacity)
     */
    public static SodiumFrameRing create(int capacity) {
        GpuDevice facade = RenderSystem.tryGetDevice();
        if (facade == null) {
            return null;
        }
        VulkanDevice device = (VulkanDevice) ((GpuDeviceAccessor) (Object) facade).meshelium$backend();
        VulkanCommandEncoder encoder = device.createCommandEncoder();
        long listBytes = (long) SLOTS * capacity * SodiumGpuVisibilityLayout.LIST_ENTRY_BYTES;
        long indirectBytes = (long) SLOTS * (capacity + SodiumGpuVisibilityLayout.MAX_GROUP_COMMANDS)
                * SodiumGpuVisibilityLayout.INDIRECT_BYTES;
        MesheliumVkBuffers.MappedBuffer list = MesheliumVkBuffers.createDeviceMapped(device.vma(),
                listBytes, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                "vmaCreateBuffer(meshelium sodium frame list)");
        // Stale slot bytes are never dispatched, but defined contents keep
        // validation and GPU debuggers quiet.
        MemoryUtil.memSet(list.mappedAddress(), 0, listBytes);
        MesheliumVkBuffers.MappedBuffer ind;
        try {
            ind = MesheliumVkBuffers.createDeviceMapped(device.vma(), indirectBytes,
                    VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT,
                    "vmaCreateBuffer(meshelium sodium frame indirect)");
        } catch (RuntimeException t) {
            MesheliumVkBuffers.destroy(device.vma(), list.vkBuffer(), list.allocation());
            throw t;
        }
        MemoryUtil.memSet(ind.mappedAddress(), 0, indirectBytes);
        MesheliumLog.LOGGER.info(
                "Meshelium Sodium frame ring up: {} regions/slot x {} slots = {} KiB list + {} KiB "
                        + "indirect commands, device-local where the card allows it.",
                capacity, SLOTS, listBytes >> 10, indirectBytes >> 10);
        return new SodiumFrameRing(encoder, device.vma(), list, ind, capacity);
    }

    /** Entries one slot holds. */
    public int capacity() {
        return capacity;
    }

    /** True when the next {@link #advance()} would exceed the per-submit bound. */
    public static boolean guardTripped() {
        return advancesSinceSubmit >= SLOTS / 2;
    }

    /** Times the guard declined a frame this session (observability). */
    public static long guardTrips() {
        return guardTrips;
    }

    static void countGuardTrip() {
        guardTrips++;
    }

    /** Called at the RETURN of {@code VulkanCommandEncoder.submit()}, via the drawer. */
    static void onSubmit() {
        advancesSinceSubmit = 0;
        submitsReturned++;
    }

    /**
     * Submit RETURNs so far: the clock {@code SodiumMirrorGpu.beginCommit}
     * stamps and retires staging spans on. WHY this and not the rung-0
     * attempt serial: a span stamped {@code s} is recorded (through
     * {@code execute}) into the submission that submit {@code s+1} closes,
     * and {@code submit()} RETURNS only after
     * {@code awaitSubmitCompletion(index-2)}, so at the RETURN of submit
     * {@code s+3} that submission is complete: {@code now - s >= FREE_FRAME_LAG}
     * (3) is exactly today's lag in the one-attempt-per-frame regime, and
     * unlike an attempt count it is invariant to declines and to any number
     * of attempts per submit interval (vanilla's panorama renders the level
     * six times between submits, which would retire a span the GPU had not
     * read yet). Second stage-2/3 review, 2026-09-07.
     */
    public static long submitsReturned() {
        return submitsReturned;
    }

    /**
     * Rotate to the next slot. Once per OWNED frame (both passes read the
     * same slot); increments the per-submit counter.
     */
    public void advance() {
        this.record++;
        advancesSinceSubmit++;
    }

    /** Little-endian write view of the current slot's list; write-only. */
    public ByteBuffer listView() {
        long base = list.mappedAddress() + slotIndex() * listSlotBytes;
        return MemoryUtil.memByteBuffer(base, (int) listSlotBytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Little-endian write view of the current slot's indirect commands; write-only. */
    public ByteBuffer indirectView() {
        long base = indirect.mappedAddress() + slotIndex() * indirectSlotBytes;
        return MemoryUtil.memByteBuffer(base, (int) indirectSlotBytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Host address of the current list slot, for raw stores. */
    public long listAddress() {
        return list.mappedAddress() + slotIndex() * listSlotBytes;
    }

    /** Host address of the current indirect slot, for raw stores. */
    public long indirectAddress() {
        return indirect.mappedAddress() + slotIndex() * indirectSlotBytes;
    }

    /** CPU address of this slot's group commands (MAX_GROUP_COMMANDS of them, after the per-region ones). */
    public long indirectGroupAddress() {
        return indirectAddress() + (long) capacity * SodiumGpuVisibilityLayout.INDIRECT_BYTES;
    }

    /** Buffer offset of this slot's group commands, for the one-draw-per-group record. */
    public long indirectGroupOffset() {
        return indirectOffset() + (long) capacity * SodiumGpuVisibilityLayout.INDIRECT_BYTES;
    }

    public long listBuffer() {
        return list.vkBuffer();
    }

    public long listOffset() {
        return slotIndex() * listSlotBytes;
    }

    public long listRange() {
        return listSlotBytes;
    }

    public long indirectBuffer() {
        return indirect.vkBuffer();
    }

    public long indirectOffset() {
        return slotIndex() * indirectSlotBytes;
    }

    private long slotIndex() {
        return Math.floorMod(record, (long) SLOTS);
    }

    /** Device close only, after vanilla's queue idle. */
    public void destroyNow() {
        MesheliumVkBuffers.destroy(vma, list.vkBuffer(), list.allocation());
        MesheliumVkBuffers.destroy(vma, indirect.vkBuffer(), indirect.allocation());
    }

    /** Instance retirement: hand to vanilla's deferred-destroy rotation. */
    public void destroy() {
        long vmaHandle = this.vma;
        MesheliumVkBuffers.MappedBuffer l = this.list;
        MesheliumVkBuffers.MappedBuffer i = this.indirect;
        encoder.queueForDestroy(() -> {
            MesheliumVkBuffers.destroy(vmaHandle, l.vkBuffer(), l.allocation());
            MesheliumVkBuffers.destroy(vmaHandle, i.vkBuffer(), i.allocation());
        });
    }
}
