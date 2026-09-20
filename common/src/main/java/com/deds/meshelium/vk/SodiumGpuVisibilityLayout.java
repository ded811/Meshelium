/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

/**
 * The one file every partition of the GPU-visibility build on the Sodium
 * host agrees on: byte offsets, strides, binding numbers, push-constant
 * slots, flag bits, VisMode values and macro names — once, as constants.
 *
 * <h2>Why a constants-only file (D-023)</h2>
 * <p>Three implementers build stages 2 and 3 of
 * {@code docs/unreleased/sodium/GPU-VISIBILITY-DESIGN.md} without talking
 * to each other: the CPU side (the mirror bookkeeping, ids, the per-frame
 * loop), the GPU side (buffers, pipelines, shader arms) and the tests.
 * Every number below is read by at least two of them, and a literal that
 * drifts between a Java writer and a shader reader is a wrong-geometry
 * bug with no error anywhere (the stage-1 run table's first version paid
 * exactly that: MEASUREMENTS.md 0g). The shaders repeat these literals
 * with a {@code // SodiumGpuVisibilityLayout.X} comment and the reviewer
 * diffs them; nothing on the Java side may spell one of them again.
 *
 * <h2>Two buffers, not one</h2>
 * <p>The records and rows are read by every task lane and written by
 * in-stream copies, so they must be DEVICE_LOCAL
 * ({@link MesheliumVkBuffers#createDeviceLocal}); the per-frame list is
 * CPU-written every frame and read once per task workgroup, so it must be
 * host-visible ({@link MesheliumVkBuffers#createDeviceMapped}, the run
 * table's class — MEASUREMENTS.md 0k/0l priced host-mapped reads at
 * ~0.5 ms a frame once). One buffer cannot be both, hence
 * {@link SodiumMirrorGpu} and {@link SodiumFrameRing}; the task stage still
 * binds exactly four storage buffers, the spec minimum.
 *
 * <p>{@code java.lang} only: the mixin plugin's classmates must be able to
 * import this during class transformation.
 */
public final class SodiumGpuVisibilityLayout {

    // ---- mirror buffer (SodiumMirrorGpu): one DEVICE_LOCAL VkBuffer ----

    /** Stats + layout words, 256-aligned so the rows may be bound at an offset. */
    public static final int MIRROR_HEADER_BYTES = 256;

    /**
     * uint[4]: [0] VisMode-0 survivors, [1] phase A, [2] phase B, [3] quads
     * emitted (all modes). {@code atomicAdd} targets of the task stage.
     */
    public static final int MIRROR_STATS_OFFSET = 0;

    public static final int MIRROR_STATS_BYTES = 16;

    /**
     * uint[4]: capacity, rowsBase (uvec4 index, always 0 relative to
     * {@code data[]}), solidBase, cutoutBase (uvec4 indices). Debug and
     * readback only; the push constant carries RecordBase.
     */
    public static final int MIRROR_LAYOUT_OFFSET = 16;

    /** 4 uvec4 per mid; see the {@code ROW_*} offsets. */
    public static final int ROW_BYTES = 64;

    /** Sodium's {@code SectionRenderDataUnsafe.STRIDE}, verbatim. */
    public static final int RECORD_BYTES = 48;

    public static final int SLOTS_PER_REGION = 256;

    /** One region's records for one pass: 12,288 bytes, one memcpy. */
    public static final int PASS_BLOCK_BYTES = RECORD_BYTES * SLOTS_PER_REGION;

    public static final int PASS_SOLID = 0;

    public static final int PASS_CUTOUT = 1;

    public static final int PASS_COUNT = 2;

    public static long rowsOffset(int capacity) {
        return MIRROR_HEADER_BYTES;
    }

    public static long passOffset(int capacity, int pass) {
        return MIRROR_HEADER_BYTES + (long) capacity * ROW_BYTES
                + (long) pass * capacity * PASS_BLOCK_BYTES;
    }

    public static long mirrorBytes(int capacity) {
        return passOffset(capacity, PASS_COUNT);
    }

    /** The push constant RecordBase: uvec4 index of a pass table inside {@code data[]}. */
    public static int recordBaseUvec4(int capacity, int pass) {
        return (int) ((passOffset(capacity, pass) - MIRROR_HEADER_BYTES) / 16);
    }

    /** Mids; grows x1.5 rounded up to a multiple of {@link #CAPACITY_ROUND}. */
    public static final int CAPACITY_INITIAL = 1024;

    public static final int CAPACITY_ROUND = 64;

    /**
     * The smallest capacity a test may pin a NEW instance to
     * ({@code SodiumTerrainDrawer.setMirrorCapacityForTest}). Nothing in
     * the layout needs more: every offset above is 16-aligned for any
     * capacity (64 B rows, 12 KiB blocks), the stamps and the list ring
     * are sized per id, and the shaders index unsized arrays. Below
     * {@link #CAPACITY_ROUND} on purpose: leg 97_07 proves growth from the
     * regions the client already holds at its pose (24 at rd 5), a count
     * a 64-id floor can never overflow in the suite's world.
     */
    public static final int CAPACITY_TEST_MIN = 16;

    // ---- row layout, byte offsets inside the 64 B ----

    /** ivec4 {chunkX, chunkY, chunkZ, flags}. */
    public static final int ROW_CHUNK_X = 0, ROW_CHUNK_Y = 4, ROW_CHUNK_Z = 8, ROW_FLAGS = 12;

    /** uvec4[2]: bit s (s = LocalSectionIndex) at word s>>5, bit s&31. */
    public static final int ROW_OCC_MASK = 16;

    /** uvec4 {bufferKey, popcount, occMinPacked, occMaxPacked}. */
    public static final int ROW_BUFFER_KEY = 48, ROW_POPCOUNT = 52, ROW_OCC_MIN = 56, ROW_OCC_MAX = 60;

    /** flags bit 0. */
    public static final int ROW_FLAG_LIVE = 1;

    /** flags bit 1, RESERVED for stage 4; always 0 here. */
    public static final int ROW_FLAG_FORCE_FULL = 2;

    // ---- list ring (SodiumFrameRing): one createDeviceMapped VkBuffer, SLOTS slots ----

    /** vec4 origin | uvec4 meta | uvec4 bfsMask[2]. */
    public static final int LIST_ENTRY_BYTES = 64;

    /**
     * xyz float: the region's min corner minus the camera (double math,
     * cast at the end, the list path's arithmetic).
     */
    public static final int LIST_ORIGIN = 0;

    /**
     * Byte 12 of the entry (the origin's unused fourth component): the
     * cumulative task-workgroup count of this entry's group up to and
     * including this entry, an int. terrain.task declares the entry as
     * {@code vec3 origin; uint groupEnd;} (std430: the same 16 bytes) and
     * reads it in the one-draw search; the raster shaders keep their
     * {@code vec4 origin} declaration and never read .w.
     */
    public static final int LIST_GROUP_END = 12;

    public static final int LIST_META_MID = 16, LIST_META_POPCOUNT = 20, LIST_META_OCC_MIN = 24,
            LIST_META_OCC_MAX = 28;

    /** 256 bits, read in VisMode 0 only. */
    public static final int LIST_BFS_MASK = 32;

    /** {@code VkDrawMeshTasksIndirectCommandEXT}. */
    public static final int INDIRECT_BYTES = 12;

    /** Per-submit guard is SLOTS/2, the run table's rule. */
    public static final int RING_SLOTS = 8;

    /** {@code VkStagingRing} capacity for commits. */
    public static final int STAGING_BYTES = 32 << 20;

    // ---- descriptor set 0 of the Sodium GPU-visibility pipeline ----

    public static final int B_GEOMETRY = 0, B_SCENE = 1, B_PROJECTION = 2, B_FOG = 3, B_GLOBALS = 4,
            B_ATLAS = 5, B_LIGHTMAP = 6;

    public static final int B_MIRROR = 7, B_LIST = 8, B_PREV_STAMPS = 9, B_CUR_STAMPS = 10;

    /** 7, 8, 9, 10 — the spec minimum, no {@code taskStageHasRoom} guard needed. */
    public static final int TASK_STAGE_STORAGE_BUFFERS = 4;

    // ---- push constants (32 B, TASK|MESH) ----

    public static final int PUSH_FIRST_SLOT = 0, PUSH_GROUP_KEY = 4, PUSH_VIS_MODE = 8,
            PUSH_FRAME_STAMP = 12, PUSH_FLAGS = 16, PUSH_RECORD_BASE = 20, PUSH_RESERVED0 = 24,
            PUSH_RESERVED1 = 28;

    /** The former RESERVED0: this draw's list-entry count (FLAG_ONE_DRAW). */
    public static final int PUSH_REGION_COUNT = PUSH_RESERVED0;

    public static final int FLAG_STATS = 1, FLAG_FACE_ALL = 2, FLAG_DIST_GATE = 4;

    /**
     * Measurement-only flags (SodiumTerrainDrawer.PROPERTY_DIAG_TASK_SKIP):
     * SKIP_ALL makes every task lane emit nothing before it reads a row
     * or a record (prices the launch and payload machinery alone);
     * SKIP_STAMPS makes VisMode 1/2 lanes read no stamps and treat the
     * section as not visible (prices the stamp loads). Never set by a
     * shipped path.
     */
    public static final int FLAG_DIAG_SKIP_ALL = 8, FLAG_DIAG_SKIP_STAMPS = 16;

    /**
     * SodiumTerrainDrawer.PROPERTY_ONE_DRAW: one indirect draw per buffer
     * group instead of one per listed region. The task workgroup finds
     * its region by a binary search over the group's list entries on
     * {@link #LIST_GROUP_END} (the cumulative task-workgroup count within
     * the group, in list order); the push constant at
     * {@link #PUSH_REGION_COUNT} carries the group's entry count.
     */
    public static final int FLAG_ONE_DRAW = 32;

    /**
     * Group commands per ring slot, appended after the per-region
     * commands: the indirect slot is (capacity + this) commands. Sodium's
     * arenas resolve to single digits of distinct buffers at any render
     * distance (D-018); a frame with more groups than this declines.
     */
    public static final int MAX_GROUP_COMMANDS = 64;

    /** == TerrainDrawer.MODE_MASK / MODE_PHASE_A / MODE_PHASE_B. */
    public static final int VIS_MODE_BFS = 0, VIS_MODE_PHASE_A = 1, VIS_MODE_PHASE_B = 2;

    // ---- scene UBO tail (SodiumTerrainDrawer.uploadScene grows 208 -> 240) ----

    /** vec4 {fracX, fracY, fracZ, searchDistance (blocks)}. */
    public static final int SCENE_CAMERA_FRAC = 208;

    /** ivec4 {intX, intY, intZ, 0}. */
    public static final int SCENE_CAMERA_BLOCK = 224;

    public static final int SCENE_BYTES_SODIUM = 240;

    // ---- shader macros (names; defaults keep every existing compile byte-identical) ----

    /**
     * 1 on the GPU-visibility pipeline only; implies SODIUM=1, TASK_CULL=1,
     * SODIUM_BATCH=0, SODIUM_DRAWID=0.
     */
    public static final String MACRO_SODIUM_TASK = "MESHELIUM_SODIUM_TASK";

    // ---- lifetimes ----

    /** == TerrainResidency.FREE_FRAME_LAG (asserted equal by SodiumMirrorGpu's static initializer). */
    public static final int FREE_FRAME_LAG = 3;

    private SodiumGpuVisibilityLayout() {
    }
}
