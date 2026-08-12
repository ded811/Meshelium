/*
 * Meshelium — LGPL-3.0-only.
 *
 * Derived from Nvidium by MCRcortex (LGPL-3.0). Algorithm authority:
 *   misc/reference/nvidium/src/main/java/me/cortex/nvidium/sodiumCompat/SodiumResultCompatibility.java
 *     :89-225 (stream order + offsets), :26-48 (AABB clamping), :228-247
 *     (bounds from DEQUANTIZED positions — floor/ceil of u16/2048-8)
 * Host difference: Nvidium repackaged Sodium's already-encoded vertex
 * ranges; Meshelium's encoder takes abstract quads and encodes + buckets in
 * one pass. The output stream layout and metadata are bit-compatible.
 */
package com.deds.meshelium.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes a section's abstract quads into Nvidium's bucketed geometry
 * stream: translucent prefix first, then the seven facing buckets
 * contiguously in {@link QuadFacing} ordinal order, 64 bytes per quad.
 *
 * <p><b>Translucent ordering is the caller's job:</b> Nvidium sorts the
 * translucent prefix back-to-front against a camera snapshot at build time
 * (SodiumResultCompatibility.java:89-164) — a host-side, camera-dependent
 * concern that lands in wave 3b. This encoder preserves the caller-given
 * translucent order; solid quads keep input order within their bucket.</p>
 *
 * <p><b>AABB semantics</b> (":26-48, :228-247"): bounds are computed from
 * the QUANTIZED positions (decode-after-encode), i.e. floor/ceil of
 * {@code u16/2048 - 8}, so the record's box always contains what the GPU
 * will actually reconstruct. min clamps to [0,15]; max clamps to [0,16];
 * size = {@code clamp(max - min - 1, 0, 15)}.</p>
 *
 * <p>Empty sections are rejected: Nvidium never encodes a 0-quad section —
 * it deletes it instead (SectionManager.java:58-61). Callers must do the
 * same.</p>
 */
public final class SectionMeshEncoder {

    private SectionMeshEncoder() {}

    /** Encode into a fresh heap buffer (little-endian). */
    public static EncodedSectionMesh encode(List<TerrainQuad> quads) {
        ByteBuffer dst = ByteBuffer
                .allocate(quads.size() * TerrainVertexCodec.QUAD_STRIDE)
                .order(ByteOrder.LITTLE_ENDIAN);
        return encodeInto(quads, dst);
    }

    /**
     * Encode into a caller-supplied little-endian buffer at its current
     * position (wave 3b will hand a mapped staging view here). Needs
     * {@code quads.size() * 64} bytes remaining.
     */
    public static EncodedSectionMesh encodeInto(List<TerrainQuad> quads, ByteBuffer dst) {
        if (quads.isEmpty()) {
            throw new IllegalArgumentException(
                    "0-quad sections are never encoded - delete the section instead (SectionManager.java:58-61)");
        }
        TerrainVertexCodec.checkOrder(dst);

        // Partition, preserving input order inside each group.
        List<TerrainQuad> translucent = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<TerrainQuad>[] buckets = new List[QuadFacingBuckets.BUCKET_COUNT];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new ArrayList<>();
        }
        for (TerrainQuad quad : quads) {
            if (quad.translucent()) {
                translucent.add(quad);
            } else {
                buckets[quad.facing().ordinal()].add(quad);
            }
        }

        int[] bucketCounts = new int[QuadFacingBuckets.BUCKET_COUNT];
        for (int i = 0; i < buckets.length; i++) {
            bucketCounts[i] = buckets[i].size();
        }
        short[] offsets = QuadFacingBuckets.toOffsets(translucent.size(), bucketCounts);
        int[] starts = QuadFacingBuckets.bucketStarts(translucent.size(), bucketCounts);

        int geometryStart = dst.position();
        Bounds bounds = new Bounds();
        for (TerrainQuad quad : translucent) {
            encodeQuadTracked(dst, quad, bounds);
        }
        for (List<TerrainQuad> bucket : buckets) {
            for (TerrainQuad quad : bucket) {
                encodeQuadTracked(dst, quad, bounds);
            }
        }

        ByteBuffer geometry = dst.duplicate().order(dst.order());
        geometry.limit(dst.position()).position(geometryStart);
        geometry = geometry.slice().asReadOnlyBuffer().order(dst.order());

        bounds.clampLikeRepackager();
        return new EncodedSectionMesh(geometry, quads.size(), offsets, starts,
                bounds.minX, bounds.minY, bounds.minZ,
                bounds.sizeX, bounds.sizeY, bounds.sizeZ);
    }

    private static void encodeQuadTracked(ByteBuffer dst, TerrainQuad quad, Bounds bounds) {
        TerrainVertexCodec.encodeQuad(dst, quad);
        for (int i = 0; i < 4; i++) {
            TerrainVertex v = quad.vertex(i);
            // Bounds from the QUANTIZED position, like the repackager which
            // reads back the encoded shorts (SodiumResultCompatibility.java:228-247).
            bounds.add(
                    TerrainVertexCodec.decodePosition(TerrainVertexCodec.encodePosition(v.x())),
                    TerrainVertexCodec.decodePosition(TerrainVertexCodec.encodePosition(v.y())),
                    TerrainVertexCodec.decodePosition(TerrainVertexCodec.encodePosition(v.z())));
        }
    }

    /** SodiumResultCompatibility.java:23-24 initial sentinels, :239-247 update, :29-48 clamp. */
    private static final class Bounds {
        int minX = 2000, minY = 2000, minZ = 2000;
        int maxX = -2000, maxY = -2000, maxZ = -2000;
        int sizeX, sizeY, sizeZ;

        void add(float x, float y, float z) {
            minX = Math.min(minX, (int) Math.floor(x));
            minY = Math.min(minY, (int) Math.floor(y));
            minZ = Math.min(minZ, (int) Math.floor(z));
            maxX = Math.max(maxX, (int) Math.ceil(x));
            maxY = Math.max(maxY, (int) Math.ceil(y));
            maxZ = Math.max(maxZ, (int) Math.ceil(z));
        }

        void clampLikeRepackager() {
            minX = Math.min(Math.max(minX, 0), 15);
            minY = Math.min(Math.max(minY, 0), 15);
            minZ = Math.min(Math.max(minZ, 0), 15);
            maxX = Math.max(Math.min(maxX, 16), 0);
            maxY = Math.max(Math.min(maxY, 16), 0);
            maxZ = Math.max(Math.min(maxZ, 16), 0);
            sizeX = Math.min(15, Math.max(maxX - minX - 1, 0));
            sizeY = Math.min(15, Math.max(maxY - minY - 1, 0));
            sizeZ = Math.min(15, Math.max(maxZ - minZ - 1, 0));
        }
    }
}
