/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium;

import com.deds.meshelium.vk.SodiumGpuVisibilityLayout;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Meshelium's own dense region ids for the GPU record mirror, with a
 * release lag (D-023, GPU-VISIBILITY-DESIGN.md section 3.3).
 *
 * <h2>Why not Sodium's {@code RenderRegion.getId()}</h2>
 * <p>Verified with javap (contract section 1.2): Sodium acquires a region's
 * id only on the upload branch that a section reaches with a build time,
 * and a section whose first build had no geometry (block-entity-only,
 * then a block placed) is re-uploaded without one, so a region can hold
 * geometry with {@code getId() == -1} forever. Its pool also recycles an
 * id on the very next acquire after a release. A table keyed on that id
 * would have a hole and a reuse hazard; a table keyed on an id this class
 * hands out per {@code RenderRegion} identity has neither.
 *
 * <h2>The lag</h2>
 * <p>A released id becomes free only {@code FREE_FRAME_LAG} serials after
 * its release ({@link #beginFrame}), so a mid never names two regions
 * within three frames: the GPU may still be reading the old row from an
 * in-flight frame, and the row is zeroed in the commit that follows the
 * delete hook (I5) before any new region could claim the slot. The serial
 * counts rung-0 ATTEMPTS (at most one per Sodium frame), the same clock
 * the staging ring retires on.
 *
 * <p>Ints only; no Sodium object is referenced. Render thread.
 */
final class MesheliumSodiumRegionIds {

    /** Set = allocated, or released and still inside the lag. */
    private final BitSet used = new BitSet();

    private int capacity;

    private int live;

    private long releasedTotal;

    /** FIFO of pending releases: mids and the serial they were released at. */
    private int[] releaseMid = new int[64];

    private long[] releaseSerial = new long[64];

    private int releaseHead;

    private int releaseCount;

    MesheliumSodiumRegionIds(int capacity) {
        this.capacity = capacity;
    }

    /** The lowest free id, or -1 when the capacity is exhausted (the caller grows). */
    int acquire() {
        int mid = used.nextClearBit(0);
        if (mid >= capacity) {
            return -1;
        }
        used.set(mid);
        live++;
        return mid;
    }

    /** Queue a release; the id stays occupied until the lag has passed. */
    void release(int mid, long serial) {
        if (releaseCount == releaseMid.length) {
            int n = releaseMid.length * 2;
            int[] mids = new int[n];
            long[] serials = new long[n];
            for (int i = 0; i < releaseCount; i++) {
                int j = (releaseHead + i) % releaseMid.length;
                mids[i] = releaseMid[j];
                serials[i] = releaseSerial[j];
            }
            releaseMid = mids;
            releaseSerial = serials;
            releaseHead = 0;
        }
        int tail = (releaseHead + releaseCount) % releaseMid.length;
        releaseMid[tail] = mid;
        releaseSerial[tail] = serial;
        releaseCount++;
        live--;
        releasedTotal++;
    }

    /** Drain the release queue: ids released {@code FREE_FRAME_LAG} or more serials ago become free. */
    void beginFrame(long serial) {
        while (releaseCount > 0
                && serial - releaseSerial[releaseHead] >= SodiumGpuVisibilityLayout.FREE_FRAME_LAG) {
            used.clear(releaseMid[releaseHead]);
            releaseHead = (releaseHead + 1) % releaseMid.length;
            releaseCount--;
        }
    }

    int capacity() {
        return capacity;
    }

    /** Ids currently naming a region. */
    int live() {
        return live;
    }

    /** Ids that cannot be handed out: live plus those inside the release lag. */
    int occupied() {
        return used.cardinality();
    }

    long releasedTotal() {
        return releasedTotal;
    }

    /** Raise the capacity; existing ids keep their values. */
    void growTo(int newCapacity) {
        if (newCapacity > capacity) {
            capacity = newCapacity;
        }
    }

    /**
     * Follow the GPU mirror's capacity exactly, down as well as up: an id
     * at or past the mirror's capacity would address bytes past its
     * buffer. Shrinking is legal only while no such id is in use (the
     * mirror is created before the first id is handed out, which is the
     * one moment a test-lowered capacity can differ from the default).
     */
    void setCapacity(int newCapacity) {
        if (newCapacity < capacity && used.length() > newCapacity) {
            throw new IllegalStateException("mirror capacity " + newCapacity
                    + " below an id already in use (" + (used.length() - 1) + ")");
        }
        capacity = newCapacity;
    }

    /** Test/diagnostic: the pending releases, oldest first. */
    int[] pendingReleases() {
        int[] out = new int[releaseCount];
        for (int i = 0; i < releaseCount; i++) {
            out[i] = releaseMid[(releaseHead + i) % releaseMid.length];
        }
        return out;
    }

    @Override
    public String toString() {
        return "ids{capacity=" + capacity + ", live=" + live + ", occupied=" + occupied()
                + ", pending=" + Arrays.toString(pendingReleases()) + "}";
    }
}
