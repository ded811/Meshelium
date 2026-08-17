/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.terrain.host;

import java.nio.ByteBuffer;

/**
 * What the render-thread pump needs from the GPU side, expressed without a
 * single LWJGL/Vulkan type so the host package stays loadable on the OpenGL
 * path (the wave-1 discipline: no Vulkan classes reachable from GL-path
 * code). Implemented by {@code com.deds.meshelium.vk.MesheliumTerrainGpu};
 * every method is render-thread-only and called while
 * {@link TerrainResidency}'s lock is held, inside vanilla's
 * {@code dispatcher.lock()} window (frame-path Q1/section-build row 6).
 *
 * <p>The {@code stage*} methods copy the given bytes into the persistent
 * staging ring and queue a {@code vkCmdCopyBuffer} region for this frame's
 * transient command buffer; {@code false} means the ring is full — the
 * caller keeps the work queued and retries next pump (backlog, never a
 * spin: threading rule 5.3, workers and the render thread must never wait
 * on Meshelium staging). The {@code fill*} methods queue unconditional
 * {@code vkCmdFillBuffer} ops (no staging involved, cannot fail). The
 * host records fills before copies with a full barrier between, so a
 * tombstone fill followed by a same-bytes record copy is ordered.</p>
 */
public interface TerrainGpuHost {

    /**
     * Start a pump frame: retires staging-ring spans whose consuming
     * submission provably completed (frame ≤ current − 3; derivation in
     * {@link TerrainResidency}) and resets this frame's op lists.
     *
     * @return false when the GPU side is not ready (skip this pump)
     */
    boolean beginFrame(long frame);

    /** Largest single staging item the ring can ever hold (its capacity). */
    long maxStageBytes();

    /** Bytes currently retired-pending inside the ring (observability). */
    long stagingUsedBytes();

    /** Geometry bytes → terrain arena at {@code arenaByteOffset}. */
    boolean stageArenaCopy(ByteBuffer data, int block, long blockByteOffset);

    /**
     * Wave-7: like {@link #stageArenaCopy} but recorded in the LATE copy
     * batch — after this frame's normal copies, behind a full barrier — so
     * an in-place overwrite of bytes another same-frame copy also wrote
     * (a resorted prefix over a fresh section upload) is WAW-ordered.
     * Resort prefix re-uploads always travel here.
     */
    boolean stageArenaCopyLate(ByteBuffer data, int block, long blockByteOffset);

    /** Section-record bytes → section metadata buffer at {@code byteOffset}. */
    boolean stageSectionRecords(ByteBuffer data, long byteOffset);

    /** One 16-byte region record → region metadata buffer at {@code byteOffset}. */
    boolean stageRegionRecord(ByteBuffer data, long byteOffset);

    /**
     * Wave-14: grow the terrain arena's backing to {@code newSizeBytes}.
     * Atomic from the caller's view — on success the implementation has
     * already: allocated the bigger device-local buffer, SUBMITTED (own
     * transient command buffer, spliced before anything this pump's
     * {@link #endFrame} will submit, trailing full barrier) a GPU copy of
     * every old byte to identical offsets plus a zero-fill of the new
     * tail, swapped the current backing, and parked the old buffer for
     * fence-gated destruction ({@code FREE_FRAME_LAG} pumps — frames in
     * flight may still read it). On failure nothing changed.
     *
     * @return the NEW opaque backing handle for
     *         {@code TerrainArena.grow}, or 0 when the buffer allocation
     *         or the copy recording failed (the caller treats growth as
     *         exhausted for this attempt)
     */
    long growArena(long newSizeBytes);

    /**
     * The wave-16 inverse of {@link #growArena}: shrink the arena's LAST
     * block onto a smaller backing, returning committed-but-untouched tail
     * to the driver. Same atomicity contract, direction reversed - on
     * success the implementation has allocated the smaller buffer,
     * SUBMITTED a copy of {@code [0, copyBytes)} (the block's extent; the
     * caller guarantees every live byte sits below it) plus a zero-fill of
     * {@code [copyBytes, newSizeBytes)}, swapped the backing, and parked
     * the old buffer for {@code FREE_FRAME_LAG}-fenced destruction. On
     * failure nothing changed, and the caller simply keeps the tail - a
     * refused trim costs memory, never correctness.
     *
     * @return the new opaque backing handle for
     *         {@code TerrainArena.shrinkLastBlock}, or 0 on failure
     */
    long trimArena(long newSizeBytes, long copyBytes);

    /**
     * A new arena block was just committed by the allocator: zero it.
     *
     * <p>The allocation itself happens through the {@code ArenaBacking}
     * seam, so this only has to make the fresh bytes DEFINED. Undefined VMA
     * memory that something later reads is how the wave-14 bug looked from
     * the outside, and a zeroed block reads back as an empty section rather
     * than as arbitrary geometry.</p>
     */
    void onArenaBlockAppended(long blockBytes);

    /**
     * Wave-15: grow the region-record and section-record buffers to hold
     * {@code newMaxRegions} ids — the live mid-world render-distance
     * raise's GPU half (the wave-14 grow-and-copy pattern applied to the
     * two remaining regionId-indexed buffers; both are addressed by
     * {@code regionId × fixed-stride}, so an identical-offsets copy keeps
     * every live record valid across the swap, and the zero-filled tails
     * reproduce the standup emptiness invariant for ids that do not exist
     * yet). Atomic exactly like {@link #growArena}: on success both
     * buffers are swapped, the old pair is parked for fence-gated
     * destruction, and the copy is already submitted BEFORE anything this
     * pump's {@link #endFrame} will submit; on failure nothing changed
     * (a half-grown pair is impossible — the second allocation failing
     * destroys the first before returning).
     *
     * <p>The implementation must also drop every drawer-side resource
     * whose size derives from the OLD scaling snapshot (occlusion stamp
     * buffers, extended frame lists) so they recreate at the new sizes
     * before any draw can index past the old budget.</p>
     *
     * @return the NEW section-records buffer handle (the residency store
     *         republishes it through its draw snapshot), or 0 on failure
     *         (the caller keeps the old snapshot and falls back to the
     *         rejoin hint)
     */
    long growRecords(int newMaxRegions);

    /**
     * Wave-15: the dispatchCapacity-only half of a live raise (the record
     * buffers already fit — e.g. standard 2048 regions covers pinned 40 —
     * but the drawer's occlusion stamp slots and frame-list rings are
     * sized from the snapshot about to grow). Drops exactly the resources
     * {@link #growRecords} drops as its side contract, without touching
     * the record buffers. Fence-safe (deferred-destroy paths).
     */
    void dropSnapshotSizedDrawResources();

    /** Queue zero-fill of a whole 8 KiB section block (tombstoned region). */
    void fillSectionBlockZero(long byteOffset, long bytes);

    /** Queue the 16-byte all-0xFF region tombstone (RegionRecord contract). */
    void fillRegionTombstone(long byteOffset);

    /**
     * Record this frame's queued ops on one transient command buffer
     * (fills → barrier → copies → barrier) and splice it into vanilla's
     * submission via the public {@code execute(cb)} API. No-op when
     * nothing was queued (idle frames stay allocation-free on the GPU).
     */
    void endFrame();
}
