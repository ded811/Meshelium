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

    public static final int MIRROR_STATS_BYTES = 96;

    /**
     * Words 4-6 of the stats: the GPU's own count of task workgroups whose
     * REGION was rejected before a single section was looked at.
     *
     * <p>{@code terrain.task} draws nothing at all for a region unless its
     * row says live AND its buffer key equals the group being drawn. That
     * gate is the only thing on this path that can take a whole 8x4x8
     * region off the screen in one stroke, which is the shape the owner
     * reports (2026-09-21: "8x8 chunk groups", "large groups in sync"),
     * and it was the only part of the frame nothing measured. The CPU
     * cross-checks its OWN shadow before listing a region and declines the
     * frame on a mismatch, but nothing ever compared that shadow with the
     * row the card actually holds.
     *
     * <p>In normal operation every dispatched workgroup belongs to a region
     * of the group being drawn, so a rejection is not a tuning signal with
     * a threshold: it is an anomaly, and any non-zero value is a statement
     * that the card's copy of a row disagrees with the CPU that listed it.
     * [4] totals them, [5] counts a clear live bit and [6] a key that did
     * not match, so the two causes are told apart in the log.
     */
    public static final int STAT_REGION_REJECTED = 4, STAT_ROW_DEAD = 5, STAT_KEY_MISMATCH = 6;

    /**
     * Words 8-15: everything the FIRST rejecting workgroup of a frame was
     * looking at when it refused. A count says a region was lost; these say
     * which region, and which of the two possible causes it was.
     *
     * <p>WHAT THESE DO NOT SETTLE, corrected 2026-09-21 night. The
     * comment here used to claim that {@code slot} landing outside
     * {@code [firstSlot, firstSlot + regionCount)} would show a search
     * bug. It cannot happen: the shipped search is a binary search whose
     * {@code lo} starts at {@code FirstSlot} and only rises and whose
     * {@code hi} starts at {@code FirstSlot + RegionCount - 1} and only
     * falls, so its answer is clamped to the run by arithmetic; and with
     * one-draw-per-group off, {@code RegionCount} is pushed as 0 and the
     * test is skipped outright. The verdict was a constant. What the
     * search CAN do and this still cannot see is land on the wrong entry
     * INSIDE the run - which matters only if the entries the shader read
     * are not the ones the processor wrote this frame.
     */
    public static final int STAT_FIRST_MID = 8, STAT_FIRST_ROW_KEY = 9,
            STAT_FIRST_GROUP_KEY = 10, STAT_FIRST_SLOT = 11, STAT_FIRST_GROUP_START = 12,
            STAT_FIRST_GROUP_COUNT = 13, STAT_FIRST_LIST_POP = 14, STAT_FIRST_ROW_POP = 15;

    /**
     * Words 16-18: the chunk coordinates the rejected ROW carries.
     *
     * <p>This is the one reading that tells the two remaining causes apart,
     * and neither the counts nor the keys can. A row holds the region's own
     * chunk origin, written by the same staged block as its key. If those
     * coordinates name the region the processor put in this draw, the row
     * is an OLDER copy of the right row and the fault is that a copy did
     * not reach the card. If they name somewhere else entirely, the shader
     * is reading the wrong row and the fault is in the id, the addressing
     * or a capacity the two sides disagree about - a much simpler thing,
     * and a completely different fix.
     *
     * <p>Read against the row ledger in {@code SodiumTerrainDrawer}, which
     * is what the processor last wrote for that same id.
     */
    public static final int STAT_FIRST_ROW_CX = 16, STAT_FIRST_ROW_CY = 17,
            STAT_FIRST_ROW_CZ = 18;

    /**
     * Word 7: the {@code VisMode} of the draw that refused.
     *
     * <p>Up to four draws a frame write these same counters - phase A and
     * phase B, each for the solid and the cutout pass - and the snapshot
     * belongs to whichever of them won the atomic. Without this the line
     * cannot say which, and the two phases select their sections by
     * completely different rules.
     */
    public static final int STAT_FIRST_VISMODE = 7;

    /**
     * Words 19-23: task workgroups whose MIRROR row disagreed with the list
     * entry's fresh copy (key or popcount), counted once per workgroup, and
     * what the first one of the frame saw: {mid, mirror key, list key in the
     * high 16 bits and list popcount in the low 16, mirror popcount}.
     *
     * <p>With the rows taken from the list this costs nothing on screen; it
     * is the proof that the thing being worked around is still happening
     * underneath, and a direct measurement of how often. Zero here with
     * the mirror reads marked coherent would say the stale copy lived in a
     * cache the coherent read bypasses.
     */
    public static final int STAT_MIRROR_STALE = 19, STAT_STALE_MID = 20,
            STAT_STALE_MIRROR_KEY = 21, STAT_STALE_LIST_KEY = 22, STAT_STALE_MIRROR_POP = 23;

    /**
     * uint[4]: capacity, rowsBase (uvec4 index, always 0 relative to
     * {@code data[]}), solidBase, cutoutBase (uvec4 indices). Debug and
     * readback only; the push constant carries RecordBase.
     */
    public static final int MIRROR_LAYOUT_OFFSET = 96;

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

    /**
     * vec4 origin | uvec4 meta | uvec4 bfsMask[2] | uvec4 row[4].
     *
     * <p>128 bytes since 2026-09-22: the second half is a copy of the
     * region's mirror row, written by the processor every frame from its
     * own shadow. See {@link #LIST_ROW} for why.
     */
    public static final int LIST_ENTRY_BYTES = 128;

    /**
     * Byte 64 of the entry: the region's row, in exactly the mirror row's
     * layout ({@link #ROW_CHUNK_X} .. {@link #ROW_OCC_MAX}), rewritten
     * every frame.
     *
     * <p>The owner's laptop (Radeon 780M, RADV) proved over 311 refusing
     * frames that the graphics card keeps reading an OLD copy of a region's
     * row out of the device-local mirror, and not one frame old, which a
     * late copy could explain: one group held its old key at stats frame 13
     * and STILL held it at frame 17, four frames past the point where every
     * submit has waited for the one two before it; another held key 2 after
     * the processor had written key 6 to it. Only a cache serving a stale
     * line for several frames does that. The rows are
     * the one thing on this path that is tiny, read by every workgroup of
     * a region with a uniform address (so through the per-core scalar
     * cache), and re-read every single frame, so their lines never age
     * out; everything else is either large enough to be evicted or, like
     * this list, rotates through eight slots. The list has never once been
     * stale: in every sample where the two popcounts differed, the list's
     * was the newer one.
     *
     * <p>So the draw path takes the row from here. The mirror keeps the
     * 48-byte section records, which are read per lane and are fine.
     */
    public static final int LIST_ROW = 64;

    /**
     * The row copy's last two words ({@code row3.z/w}) are NOT occMin/occMax
     * in the list: nothing reads them there (the region raster takes the box
     * from {@code meta.z/w}), so they carry the RECORD PROBE instead - the
     * base vertex of the region's first occupied section's record, as the
     * processor last committed it, for the SOLID and the CUTOUT pass.
     *
     * <p>This puts back the guarantee the key gate used to give. The rows
     * now come from the list, so the key gate compares two copies of the
     * same processor value and can no longer fail; but the 48-byte section
     * records still come from the device-local mirror, which on the owner's
     * machine was served stale. The task stage reads the probe section's
     * record on the card and compares its base vertex with this word: equal
     * means the card holds this commit's records, different means it does
     * not, and the region is refused - a hole, never geometry read at old
     * offsets out of a new buffer.
     */
    public static final int LIST_ROW_PROBE_SOLID = LIST_ROW + 56,
            LIST_ROW_PROBE_CUTOUT = LIST_ROW + 60;

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

    /** Word 28: 0 when this draw's RecordBase is the SOLID table, 1 CUTOUT (the probe to compare). */
    public static final int PUSH_RECORD_PASS = PUSH_RESERVED1;

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
     * SodiumTerrainDrawer.PROPERTY_STAMP_HOLD: phase A also draws a section
     * that was marked visible TWO frames ago, not only one.
     *
     * <p>Without it the whole picture rests on a single frame's marks. A
     * section is drawn by phase A only when the section raster of the
     * previous frame stamped it, and by phase B only when the raster of
     * this frame did; so one frame in which a region's boxes are not
     * rastered takes every section of that region off the screen for the
     * NEXT frame, and nothing else can put it back. The scheme has no
     * second leg.
     *
     * <p>That is what the owner's laptop showed on 2026-09-21: phase A
     * fell from 151 section-pass emissions to 5 and back to 151 in three
     * consecutive frames, with phase B recorded and drawing 0, the frame's
     * own list steady at 82 sections in 3 regions, no declines, no dropped
     * regions and no unreachable sections. The CPU side was identical on
     * all three frames; one frame's marks simply were not there.
     *
     * <p>With the flag, phase A tests the OTHER ping-pong buffer as well.
     * Read in passes 1a/1b, before this frame's raster writes it, that
     * buffer still holds the marks of two frames ago, so a section marked
     * then is drawn now. One lost frame of marks becomes invisible; two
     * lost in a row would still show. The cost is one storage load per
     * section per pass and one frame of extra retention: terrain that has
     * just become hidden is drawn for a frame longer than it needs to be,
     * which is the direction this renderer already errs in everywhere else
     * ("draw more, never fewer").
     */
    public static final int FLAG_STAMP_HOLD = 64;

    /**
     * The task stage takes the region's row from the list entry
     * ({@link #LIST_ROW}) instead of the mirror. Set by default;
     * {@code SodiumTerrainDrawer.PROPERTY_ROWS_FROM_LIST} can clear it for
     * a controlled comparison.
     */
    public static final int FLAG_ROWS_FROM_LIST = 128;

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
