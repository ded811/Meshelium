/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.mixin.GpuDeviceAccessor;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The per-pass run table for the batched Sodium draw: a host-visible,
 * persistently mapped storage ring the mesh shader reads to find out which
 * geometry its workgroup belongs to.
 *
 * <h2>Why this exists at all</h2>
 * <p>Measured 2026-09-04: with one draw command per section, ~28% of the
 * frame was Vulkan command recording. The {@code ksplit} diagnostic priced
 * a draw command at ~66 ns and ~4,270 of them a frame at ~0.28 ms out of a
 * 0.99 ms frame. Batching replaces them with one draw per distinct
 * geometry buffer — single digits — and moves the "which section am I?"
 * question into the shader, which answers it with a binary search over
 * this table.
 *
 * <h2>Why not a vanilla transient allocation</h2>
 * <p>Because Blaze3D cannot make a storage buffer. {@code GpuBuffer}'s
 * usage constants have no storage bit, {@code VulkanConst.bufferUsageToVk}
 * cannot emit {@code VK_BUFFER_USAGE_STORAGE_BUFFER_BIT}, and vanilla's
 * transient blocks are created with a fixed usage mask that lacks it. The
 * table has to be Meshelium's own buffer.
 *
 * <h2>Why a ring, and why this many slots</h2>
 * <p>The bytes are read by the GPU for as long as the frame that
 * referenced them is in flight, and nothing here waits for a fence. Slots
 * rotate so that a slot is only rewritten once its frame is provably
 * finished.
 *
 * <p>{@link #SLOTS} is 8, not 4. Two things forced it:</p>
 * <ul>
 *   <li><b>Two passes a frame.</b> {@code render} is called once per
 *       non-translucent pass, so a per-record rotation would come back
 *       around in half the frames it looks like.</li>
 *   <li><b>No usable frame counter.</b> {@code TerrainDrawer.frameSerial}
 *       advances from a vanilla hook that the Sodium path does not run
 *       through, so on this path it is permanently zero. A ring keyed on
 *       it would pin slot 0 and rewrite bytes the GPU was still reading.
 *       This class therefore counts its own {@link #advance() records}.</li>
 * </ul>
 *
 * <p>Eight slots at four frames of lag with two passes each is the same
 * safety margin the arena's {@code FREE_FRAME_LAG} buys, arrived at from
 * the other direction.</p>
 *
 * <h2>Write-only</h2>
 * <p>The allocation declares {@code HOST_ACCESS_SEQUENTIAL_WRITE}, so the
 * mapping must never be read back — VMA is entitled to give us
 * write-combined memory where reads are catastrophically slow. It is
 * host-coherent, so there is no flush and no barrier; the only rule is
 * that every write lands before the render pass that reads it closes.
 */
final class MesheliumSodiumRunTable {

    /**
     * Bytes per run record. Must match the {@code MesheliumRun} struct in
     * terrain.mesh exactly:
     * <pre>
     *  0  uint firstVertex   base vertex of this run in Sodium's buffer
     *  4  uint quadCount
     *  8  uint groupEnd      cumulative workgroups, inclusive, within the draw
     * 12  uint (padding)
     * 16  vec4 origin        xyz = section min corner relative to the camera
     * </pre>
     * std430 gives the vec4 a 16-byte alignment, which is why the padding
     * word is there and why the stride is 32 rather than 28.
     */
    static final int RUN_BYTES = 32;

    /** See the class javadoc: four frames of lag, two passes each. */
    private static final int SLOTS = 8;

    /**
     * Runs one slot can hold. With facing culling a section is one to four
     * runs; the reference scene at render distance 64 produces ~2,800 runs
     * a pass, and 16,384 leaves room for a scene five times denser or a
     * render distance well past 64. 512 KiB per slot, 4 MiB for the ring.
     * (An earlier 8,192 was described as "four times the section count",
     * which it was — of the pre-culling count, and of the wrong unit.)
     *
     * <p>Overflow is not an error: the drawer stops adding runs and the
     * frame draws less. That is a visible bug, so the drawer logs once —
     * but a dropped section beats a heap corruption, and a growable ring
     * would need fence tracking this class deliberately does not have.
     */
    private static final int RUNS_PER_SLOT = 16384;

    /**
     * Runs one slot holds, DERIVED from the Distance Cap at creation.
     *
     * <h2>Why the constant above could not stay</h2>
     * <p>Its 16,384 was justified as "the reference scene at render distance
     * 64 produces ~2,800 runs a pass ... room for a render distance well past
     * 64". That figure predates 0j, which found that every "rd64" Sodium-path
     * row before 2026-09-06 was really a 32-chunk server world - so the
     * constant was fitted to a world a quarter of the intended size. Measured
     * on a REAL world (2026-09-18): rd64 fits with no overflow, and rd96
     * overflows at 22,515-28,207 runs, printing "Some terrain will not be
     * drawn this frame". The Distance Cap offers 96 and its ceiling is 120,
     * so the shipped size was too small for distances the settings screen
     * itself allows.
     *
     * <h2>The formula, and where each number comes from</h2>
     * <p>Runs scale with the visible section count, which scales with the
     * frustum's cross-section, i.e. with distance SQUARED. Anchored on the
     * largest count actually observed - 28,207 at render distance 96, on the
     * list path with a turning camera, the worst case this project has
     * measured - times 3/2 for headroom, clamped into [16,384, 131,072] and
     * rounded up to a whole KiB of records.
     *
     * <p>Sized from the CAP rather than from the live render distance because
     * the table is created once per session and the player may raise the
     * slider afterwards; the cap is the highest the slider can reach. A
     * player who raises the CAP ITSELF mid-session can still outgrow it, and
     * the overflow warning remains as the backstop for exactly that.
     *
     * <p>This memory is DEVICE-LOCAL (see {@link #create}), so the cost is
     * VRAM, not system RAM. At a cap of 120 the records and their indirect
     * commands together come to roughly 23 MiB, against a record mirror that
     * is already 54-81 MiB at rd96.
     */
    private static volatile int runsPerSlot = RUNS_PER_SLOT;

    /** The worst per-pass run count this project has measured (rd96, list path, turning). */
    private static final int RUNS_OBSERVED_AT_96 = 28_207;

    private static final int ANCHOR_DISTANCE = 96;

    private static final int SAFETY_NUMERATOR = 3;

    private static final int SAFETY_DENOMINATOR = 2;

    /** Enough for the Distance Cap's own ceiling of 120 with the safety factor applied. */
    private static final int RUNS_PER_SLOT_CEILING = 131_072;

    /** {@code -Dmeshelium.sodium.runsPerSlot=<n>} overrides the derivation entirely. */
    private static final String RUNS_PROPERTY = "meshelium.sodium.runsPerSlot";

    /**
     * Bytes per indirect command: {@code VkDrawMeshTasksIndirectCommandEXT}
     * is three uints (groupCountX/Y/Z), and 12 is also the tightest legal
     * stride.
     */
    static final int INDIRECT_BYTES = 12;

    /**
     * Workgroups one slot's group map can address.
     *
     * <p>The map answers "which run owns workgroup g" in one read, so it
     * needs an entry per workgroup rather than per run, and it is ONE map
     * per pass shared by every buffer group — indexed globally, with each
     * dispatch's GroupBase push constant carrying its offset. At 32 quads
     * a workgroup, 131,072 entries is a bit over four million quads a
     * pass, seven times the reference scene. 512 KiB per slot. Overflow
     * clips the dispatch to the mapped range and is reported once; an
     * unmapped workgroup would otherwise read entry 0 and draw the first
     * run's geometry a second time at the wrong origin.
     */
    private static final int GROUPS_PER_SLOT = 131072;

    /**
     * Workgroups one slot's group map addresses, derived the same way and for
     * the same reason. At 32 quads a workgroup, 0j's measured 2.3M quads at
     * rd96 is about 72,000 workgroups - so the fixed 131,072 was 1.8x
     * headroom at 96 and only about 1.17x at the cap's ceiling of 120, which
     * is not headroom at all. Its overflow clips the dispatch to the mapped
     * range, so it drops terrain exactly as the run table does.
     */
    private static volatile int groupsPerSlot = GROUPS_PER_SLOT;

    /** 2.3M quads at rd96 (0j) over 32 quads a workgroup. */
    private static final int GROUPS_OBSERVED_AT_96 = 72_000;

    private static final int GROUPS_PER_SLOT_CEILING = 262_144;

    /**
     * A group-map entry: {@code runIndex << 12 | localGroup}.
     *
     * <p>Twelve bits of local group is 4,095 workgroups inside one run —
     * 131,040 quads — which no single chunk section can reach. The
     * remaining twenty bits hold a million runs against a capacity of
     * eight thousand.
     */
    static final int GROUP_LOCAL_BITS = 12;

    private final VulkanCommandEncoder encoder;
    private final long vma;
    private final MesheliumVkBuffers.MappedBuffer buffer;
    private final MesheliumVkBuffers.MappedBuffer indirect;
    private final MesheliumVkBuffers.MappedBuffer groupMap;
    private final long slotBytes;
    private final long indirectSlotBytes;
    private final long groupSlotBytes;
    private long record;

    private MesheliumSodiumRunTable(VulkanCommandEncoder encoder, long vma,
            MesheliumVkBuffers.MappedBuffer buffer, MesheliumVkBuffers.MappedBuffer indirect,
            MesheliumVkBuffers.MappedBuffer groupMap,
            long slotBytes, long indirectSlotBytes, long groupSlotBytes) {
        this.encoder = encoder;
        this.vma = vma;
        this.buffer = buffer;
        this.indirect = indirect;
        this.groupMap = groupMap;
        this.slotBytes = slotBytes;
        this.indirectSlotBytes = indirectSlotBytes;
        this.groupSlotBytes = groupSlotBytes;
    }

    /**
     * Render thread. Returns null while the device facade is not up yet,
     * which the caller treats as "not this frame" rather than as failure.
     */
    static MesheliumSodiumRunTable create() {
        GpuDevice facade = RenderSystem.tryGetDevice();
        if (facade == null) {
            return null;
        }
        VulkanDevice device = (VulkanDevice) ((GpuDeviceAccessor) (Object) facade).meshelium$backend();
        VulkanCommandEncoder encoder = device.createCommandEncoder();
        // Derived BEFORE the allocations below, once per session. The cap is
        // pure JDK and safe to read here; an explicit property wins outright,
        // so a machine that wants the old size (or a larger one) can have it
        // without a rebuild.
        int cap = MesheliumConfig.maxRenderDistanceConfigured();
        int wantRuns = sizeForCap(RUNS_OBSERVED_AT_96, cap, RUNS_PER_SLOT, RUNS_PER_SLOT_CEILING);
        int wantGroups = sizeForCap(GROUPS_OBSERVED_AT_96, cap, GROUPS_PER_SLOT,
                GROUPS_PER_SLOT_CEILING);
        String override = System.getProperty(RUNS_PROPERTY);
        if (override != null) {
            try {
                wantRuns = Math.max(1024, Integer.parseInt(override.trim()));
            } catch (NumberFormatException e) {
                MesheliumLog.LOGGER.warn("Unparseable {}='{}' - using the derived {} runs/slot",
                        RUNS_PROPERTY, override, wantRuns);
            }
        }
        runsPerSlot = wantRuns;
        groupsPerSlot = wantGroups;
        long slotBytes = (long) runsPerSlot * RUN_BYTES;
        long totalBytes = slotBytes * SLOTS;
        // DEVICE-mapped, not host-mapped, and the difference is the whole
        // feature. Every mesh workgroup reads this table, so on host memory
        // each read is a PCIe round trip: measured at roughly 0.5 ms a
        // frame, more than the batching it exists to serve ever saved. See
        // MesheliumVkBuffers.createDeviceMapped.
        MesheliumVkBuffers.MappedBuffer buf = MesheliumVkBuffers.createDeviceMapped(device.vma(),
                totalBytes, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                "vmaCreateBuffer(meshelium sodium run table)");
        // Stale slot bytes are never read — each pass writes its own prefix
        // and dispatches only what it wrote — but defined contents keep
        // validation and GPU debuggers quiet.
        MemoryUtil.memSet(buf.mappedAddress(), 0, totalBytes);
        // The indirect command list, one command per run. Only read when
        // gl_DrawID is available; allocated unconditionally because it is
        // 768 KiB and a conditional allocation is one more state to get
        // wrong on a path that already has enough.
        long indirectSlotBytes = (long) runsPerSlot * INDIRECT_BYTES;
        long indirectTotal = indirectSlotBytes * SLOTS;
        MesheliumVkBuffers.MappedBuffer ind = MesheliumVkBuffers.createDeviceMapped(device.vma(),
                indirectTotal, VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT,
                "vmaCreateBuffer(meshelium sodium indirect commands)");
        MemoryUtil.memSet(ind.mappedAddress(), 0, indirectTotal);
        long groupSlotBytes = (long) groupsPerSlot * 4L;
        long groupTotal = groupSlotBytes * SLOTS;
        MesheliumVkBuffers.MappedBuffer gm = MesheliumVkBuffers.createDeviceMapped(device.vma(),
                groupTotal, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                "vmaCreateBuffer(meshelium sodium group map)");
        MemoryUtil.memSet(gm.mappedAddress(), 0, groupTotal);
        MesheliumLog.LOGGER.info(
                "Meshelium Sodium run table up: {} runs/slot x {} slots = {} KiB run records + "
                        + "{} KiB indirect commands + {} KiB group map ({} workgroups/slot), "
                        + "device-local where the card allows it, sized for a Distance Cap of {}. "
                        + "This is what lets one command cover a whole region group instead of "
                        + "one draw per section.",
                runsPerSlot, SLOTS, totalBytes >> 10, indirectTotal >> 10, groupTotal >> 10,
                groupsPerSlot, cap);
        return new MesheliumSodiumRunTable(encoder, device.vma(), buf, ind, gm,
                slotBytes, indirectSlotBytes, groupSlotBytes);
    }

    /**
     * Scale an observed count at {@link #ANCHOR_DISTANCE} to this session's
     * Distance Cap, apply the safety factor, round to a whole KiB and clamp.
     *
     * <p>All arithmetic in long: {@code observed * cap * cap} passes 2^31
     * well before the cap does.
     */
    private static int sizeForCap(int observed, int cap, int floor, int ceiling) {
        long scaled = (long) observed * cap * cap / ((long) ANCHOR_DISTANCE * ANCHOR_DISTANCE);
        long safe = scaled * SAFETY_NUMERATOR / SAFETY_DENOMINATOR;
        long rounded = (safe + 1023L) / 1024L * 1024L;
        return (int) Math.max(floor, Math.min(ceiling, rounded));
    }

    /** Runs one pass may write before it has to start dropping geometry. */
    static int capacity() {
        return runsPerSlot;
    }

    /**
     * Slots consumed since vanilla's last queue submit, reset by
     * {@link #onSubmit()} from the encoder's submit hook.
     *
     * <p>The ring's safety argument (class javadoc) assumes a bounded
     * number of passes between two submits: vanilla keeps two submits in
     * flight, and a slot may be rewritten only once the submit that read
     * it has completed, which with {@link #SLOTS} slots holds for up to
     * {@code SLOTS / 2} advances per submit interval. The ordinary frame
     * uses two. Review (2026-09-05) found vanilla itself has a path that
     * breaks the bound: the debug panorama screenshot renders the level
     * six times between submits — twelve advances on an eight-slot ring,
     * overwriting the in-flight frame's slots and the first faces' slots
     * before anything is submitted. Debug-only and property-gated, but
     * first-party, so the bound is enforced rather than assumed: past it,
     * {@code SodiumTerrainDrawer.drawOpaque} declines the pass to Sodium.
     */
    private static int advancesSinceSubmit;

    /** True when the next {@link #advance()} would exceed the safe bound. */
    static boolean ringGuardTripped() {
        return advancesSinceSubmit >= safeAdvancesPerSubmit();
    }

    static int safeAdvancesPerSubmit() {
        return SLOTS / 2;
    }

    /** Called at the RETURN of {@code VulkanCommandEncoder.submit()}, via the drawer. */
    static void onSubmit() {
        advancesSinceSubmit = 0;
    }

    /**
     * Rotate to the next slot and return a little-endian write view of it.
     *
     * <p>Called once per pass, and the rotation is per CALL rather than
     * per frame precisely because this path has no trustworthy frame
     * number. See the class javadoc.
     */
    ByteBuffer advance() {
        this.record++;
        advancesSinceSubmit++;
        long base = buffer.mappedAddress() + slotIndex() * slotBytes;
        return MemoryUtil.memByteBuffer(base, (int) slotBytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Write view of the CURRENT slot's indirect command list. Call after
     * {@link #advance()}, never instead of it — the two views must land in
     * the same slot or the commands describe a different frame's runs.
     */
    ByteBuffer indirectView() {
        long base = indirect.mappedAddress() + slotIndex() * indirectSlotBytes;
        return MemoryUtil.memByteBuffer(base, (int) indirectSlotBytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    long indirectBuffer() {
        return indirect.vkBuffer();
    }

    long indirectOffset() {
        return slotIndex() * indirectSlotBytes;
    }

    /** Workgroups one pass may map before it starts dropping geometry. */
    static int groupCapacity() {
        return groupsPerSlot;
    }

    /** Write view of the CURRENT slot's group map. Call after advance(). */
    ByteBuffer groupMapView() {
        long base = groupMap.mappedAddress() + slotIndex() * groupSlotBytes;
        return MemoryUtil.memByteBuffer(base, (int) groupSlotBytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    long groupMapBuffer() {
        return groupMap.vkBuffer();
    }

    long groupMapOffset() {
        return slotIndex() * groupSlotBytes;
    }

    long groupMapRange() {
        return groupSlotBytes;
    }

    long vkBuffer() {
        return buffer.vkBuffer();
    }

    /** Byte offset of the slot {@link #advance()} last handed out. */
    long offset() {
        return slotIndex() * slotBytes;
    }

    long range() {
        return slotBytes;
    }

    private long slotIndex() {
        return Math.floorMod(record, (long) SLOTS);
    }

    /** Device close only, after vanilla's queue idle. */
    void destroyNow() {
        MesheliumVkBuffers.destroy(vma, buffer.vkBuffer(), buffer.allocation());
        MesheliumVkBuffers.destroy(vma, indirect.vkBuffer(), indirect.allocation());
        MesheliumVkBuffers.destroy(vma, groupMap.vkBuffer(), groupMap.allocation());
    }

    /** World teardown: hand to vanilla's deferred-destroy rotation. */
    void destroy() {
        long vmaHandle = this.vma;
        MesheliumVkBuffers.MappedBuffer b = this.buffer;
        MesheliumVkBuffers.MappedBuffer i = this.indirect;
        MesheliumVkBuffers.MappedBuffer g = this.groupMap;
        encoder.queueForDestroy(() -> {
            MesheliumVkBuffers.destroy(vmaHandle, b.vkBuffer(), b.allocation());
            MesheliumVkBuffers.destroy(vmaHandle, i.vkBuffer(), i.allocation());
            MesheliumVkBuffers.destroy(vmaHandle, g.vkBuffer(), g.allocation());
        });
    }
}
