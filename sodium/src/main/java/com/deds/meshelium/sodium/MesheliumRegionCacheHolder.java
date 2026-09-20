/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium;

/**
 * What Meshelium adds to Sodium's {@code RenderRegion}: a mutation epoch
 * and a slot for the per-region run cache.
 *
 * <h2>Why the cache lives on the region</h2>
 * <p>D-021's cache is keyed by region and dies with the region. Keeping it
 * anywhere else would need Meshelium's own region-id table with its own
 * lifetime rules (the design's §3.3 machinery, built for the GPU mirror
 * that stage 1 does not need); a field on the region gets the same
 * lifetime for free, because {@code RenderRegion.delete()} is hooked and
 * nulls it, and a deleted region is never listed again.
 *
 * <h2>Why an epoch, not a dirty flag</h2>
 * <p>Two passes read the cache each frame. A flag cleared by the first
 * reader would hand the second a stale entry; a counter that only ever
 * rises lets each pass's entry remember the epoch it was built at and
 * compare. Every hook increments it (see {@code RenderRegionMixin});
 * nothing ever resets it.
 *
 * <p>Implemented by {@code RenderRegionMixin} and read through a cast in
 * {@code MesheliumSodiumDrawList}. The names carry the {@code meshelium$}
 * prefix so they cannot collide with anything Sodium adds later.
 */
public interface MesheliumRegionCacheHolder {

    /** The region's mutation epoch: bumped at every hooked record write. */
    int meshelium$epoch();

    /** Called by a hook that lives outside the region's own mixin (H1). */
    void meshelium$bumpEpoch();

    /** The cache built against this region, or null before the first miss. */
    MesheliumRegionRunCache meshelium$runCache();

    void meshelium$setRunCache(MesheliumRegionRunCache cache);

    /**
     * Stage 2 (D-023): Meshelium's own dense region id in the GPU record
     * mirror, -1 until the region is first mirrored and again after
     * {@code delete()}. Not Sodium's {@code getId()}, which can be -1 for
     * a region with geometry (contract section 1.2).
     */
    int meshelium$mid();

    void meshelium$setMid(int mid);

    /** Dedup flag for the mirror's dirty list: true while queued and not yet committed. */
    boolean meshelium$dirtyQueued();

    void meshelium$setDirtyQueued(boolean value);
}
