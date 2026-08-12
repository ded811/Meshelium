/*
 * Meshelium — LGPL-3.0-only.
 *
 * Derived from Nvidium by MCRcortex (LGPL-3.0) —
 * RepackagedSectionOutput made host-agnostic:
 * misc/reference/nvidium/src/main/java/me/cortex/nvidium/sodiumCompat/RepackagedSectionOutput.java:7-11
 */
package com.deds.meshelium.terrain;

import java.nio.ByteBuffer;

/**
 * The output of {@link SectionMeshEncoder}: a section's packed, bucketed
 * geometry plus the metadata the 32-byte section record needs.
 *
 * <p>Mirror of Nvidium's RepackagedSectionOutput: {@code quads} total count;
 * {@code geometry} = [translucent prefix][bucket 0..6] at 64 B/quad;
 * {@code offsets[8]} per {@link QuadFacingBuckets}; {@code min}/{@code size}
 * = the 4-bit block-granularity AABB of actual geometry.</p>
 */
public final class EncodedSectionMesh {
    private final ByteBuffer geometry;
    private final int quadCount;
    private final short[] offsets;
    private final int[] bucketStarts;
    private final int minX, minY, minZ;
    private final int sizeX, sizeY, sizeZ;

    EncodedSectionMesh(ByteBuffer geometry, int quadCount, short[] offsets, int[] bucketStarts,
                       int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
        this.geometry = geometry;
        this.quadCount = quadCount;
        this.offsets = offsets;
        this.bucketStarts = bucketStarts;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
    }

    /**
     * Read-only view of the packed geometry (positioned at 0, limit =
     * {@code quadCount() * 64}, little-endian).
     */
    public ByteBuffer geometry() {
        return geometry.duplicate().order(geometry.order());
    }

    /** Total quads (translucent + all buckets). */
    public int quadCount() {
        return quadCount;
    }

    /** Geometry bytes = {@code quadCount * 64}. */
    public int geometryBytes() {
        return quadCount * TerrainVertexCodec.QUAD_STRIDE;
    }

    /**
     * offsets[0..6] = per-facing-bucket quad counts, offsets[7] =
     * translucent quad count ({@link QuadFacingBuckets}).
     */
    public short[] offsets() {
        return offsets.clone();
    }

    /**
     * Absolute quad index (relative to the section's terrain address) where
     * each facing bucket starts; [start,count] per bucket is
     * {@code (bucketStart(i), bucketCount(i))}.
     */
    public int bucketStart(int bucket) {
        return bucketStarts[bucket];
    }

    public int bucketCount(int bucket) {
        return Short.toUnsignedInt(offsets[bucket]);
    }

    public int translucentCount() {
        return Short.toUnsignedInt(offsets[QuadFacingBuckets.TRANSLUCENT_SLOT]);
    }

    /** Geometry AABB min, block granularity, clamped 0..15 per axis. */
    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }

    /**
     * Geometry AABB size field = {@code clamp(max - min - 1, 0, 15)} — the
     * GPU adds 1 back when rasterizing the box
     * (occlusion/section_raster/mesh.glsl:69).
     */
    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
}
