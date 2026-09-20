/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium;

import com.mojang.blaze3d.buffers.GpuBuffer;

import java.util.IdentityHashMap;

/**
 * Stable per-session keys for Sodium's region geometry buffers: the
 * {@code bufferKey} of a mirror row and the {@code GroupKey} of a draw.
 *
 * <h2>Why monotonic, never reused (contract section 0.3)</h2>
 * <p>The task stage draws a region only when its row's {@code bufferKey}
 * equals the push constant of the group being drawn, so a region whose
 * buffer changed can only be OMITTED from the wrong group, never read
 * from the wrong buffer (I4). That check is only as good as the key: a
 * per-frame intern index that renumbers when a buffer dies would make
 * every row stale without a dirty event, the one way the check could be
 * fooled. A counter that never hands out a value twice removes that way.
 *
 * <h2>Refcounts</h2>
 * <p>Rows hold a reference on their key; when the last row that named a
 * buffer moves off it, the map entry is dropped so a dead
 * {@code GpuBuffer} object is not pinned (vanilla destroys the VkBuffer
 * on its own rotation regardless). A buffer that reappears after being
 * forgotten gets a NEW key, which the per-frame identity check then
 * treats as a change, i.e. a recommit: correct, and only ever slower.
 *
 * <p>Render thread. No key is ever 0.
 */
final class MesheliumSodiumBufferKeys {

    /** buffer to {key, refcount}. */
    private final IdentityHashMap<GpuBuffer, int[]> keys = new IdentityHashMap<>();

    private int next;

    /** The buffer's key, assigning a fresh one when it is not known. */
    int keyOf(GpuBuffer buffer) {
        int[] e = keys.get(buffer);
        if (e == null) {
            e = new int[] {++next, 0};
            keys.put(buffer, e);
        }
        return e[0];
    }

    /** The buffer's key without assigning one; 0 when unknown. */
    int peekKey(GpuBuffer buffer) {
        int[] e = keys.get(buffer);
        return e == null ? 0 : e[0];
    }

    /** A row now names this buffer. */
    void retain(GpuBuffer buffer) {
        int[] e = keys.get(buffer);
        if (e != null) {
            e[1]++;
        }
    }

    /**
     * A row stopped naming this buffer; forget it when no row does.
     * Keys are looked up by buffer identity, so the caller passes the
     * buffer it retained (the mirror keeps it beside the row's key).
     */
    void release(GpuBuffer buffer) {
        int[] e = keys.get(buffer);
        if (e != null && --e[1] <= 0) {
            keys.remove(buffer);
        }
    }

    /** Buffers currently known (the bench's {@code bufferKeys}). */
    int count() {
        return keys.size();
    }

    /** Keys handed out this session. */
    int issued() {
        return next;
    }

    void clear() {
        keys.clear();
    }
}
