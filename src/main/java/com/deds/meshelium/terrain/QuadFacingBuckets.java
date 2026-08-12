/*
 * Meshelium — LGPL-3.0-only.
 *
 * Derived from Nvidium by MCRcortex (LGPL-3.0). Layout authority:
 *   misc/reference/nvidium/src/main/java/me/cortex/nvidium/sodiumCompat/SodiumResultCompatibility.java:89-225
 *     (geometry stream order + offsets[8] semantics)
 *   misc/reference/nvidium/src/main/resources/assets/nvidium/shaders/terrain/task_common.glsl:30-87
 *     (the GPU walk that consumes the counts — see QuadFacing)
 */
package com.deds.meshelium.terrain;

import java.util.List;

/**
 * The 7-bucket ordering contract of a section's geometry stream, and the
 * arithmetic that turns bucket counts into the [start,count] ranges the
 * section record carries.
 *
 * <p><b>Stream order</b> (SodiumResultCompatibility.java:89-219): a
 * section's quads are stored <i>translucent prefix first</i> (back-to-front
 * — ordering supplied by the caller, see {@link SectionMeshEncoder}), then
 * the seven facing buckets contiguously in {@link QuadFacing} ordinal order.
 * </p>
 *
 * <p><b>Counts, not offsets</b>: {@code offsets[0..6]} are per-bucket quad
 * COUNTS (deltas, ":219"), {@code offsets[7]} is the translucent quad count
 * — which doubles as the absolute quad index where bucket 0 starts (":166").
 * The GPU reconstructs each bucket's start by walking the counts
 * cumulatively from {@code offsets[7]} (task_common.glsl:39-78).</p>
 */
public final class QuadFacingBuckets {

    /** 6 directional buckets + 1 unassigned (QuadFacing.COUNT). */
    public static final int BUCKET_COUNT = QuadFacing.COUNT;
    /** offsets[] length: 7 bucket counts + translucent count in slot 7. */
    public static final int OFFSETS_LENGTH = 8;
    /** Index of the translucent count within offsets[]. */
    public static final int TRANSLUCENT_SLOT = 7;

    private QuadFacingBuckets() {}

    /**
     * Count solid (non-translucent) quads per facing bucket. Translucent
     * quads are ignored here — they live in the prefix.
     */
    public static int[] countSolidByBucket(List<TerrainQuad> quads) {
        int[] counts = new int[BUCKET_COUNT];
        for (TerrainQuad quad : quads) {
            if (!quad.translucent()) {
                counts[quad.facing().ordinal()]++;
            }
        }
        return counts;
    }

    /**
     * Reproduce the GPU's cumulative walk on the CPU: absolute quad index
     * (from the section's terrain address) where each bucket starts.
     * {@code starts[0] == translucentCount}; {@code starts[i+1] = starts[i]
     * + counts[i]} — exactly the {@code fr} accumulation of
     * task_common.glsl:39-78.
     */
    public static int[] bucketStarts(int translucentCount, int[] bucketCounts) {
        if (bucketCounts.length != BUCKET_COUNT) {
            throw new IllegalArgumentException("expected " + BUCKET_COUNT + " bucket counts");
        }
        int[] starts = new int[BUCKET_COUNT];
        int fr = translucentCount;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            starts[i] = fr;
            fr += bucketCounts[i];
        }
        return starts;
    }

    /**
     * Pack counts into the record's offsets[8] shape (each must fit u16 —
     * the renderRanges fields are 16-bit, task_common.glsl reads
     * {@code &0xFFFF}).
     */
    public static short[] toOffsets(int translucentCount, int[] bucketCounts) {
        if (bucketCounts.length != BUCKET_COUNT) {
            throw new IllegalArgumentException("expected " + BUCKET_COUNT + " bucket counts");
        }
        short[] offsets = new short[OFFSETS_LENGTH];
        for (int i = 0; i < BUCKET_COUNT; i++) {
            offsets[i] = toU16(bucketCounts[i], "bucket " + QuadFacing.byIndex(i) + " quad count");
        }
        offsets[TRANSLUCENT_SLOT] = toU16(translucentCount, "translucent quad count");
        return offsets;
    }

    static short toU16(int value, String what) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException(what + " must fit u16, got " + value);
        }
        return (short) value;
    }
}
