/*
 * Meshelium — LGPL-3.0-only.
 *
 * Derived from Nvidium by MCRcortex (LGPL-3.0). Byte layout re-verified
 * against the ORIGINAL writer, not just the study summary:
 *   misc/reference/nvidium/src/main/java/me/cortex/nvidium/managers/RegionManager.java:95-126 (setRegionMetadata)
 *   ":66-79" (tombstone: memSet(-1) over all 16 bytes)
 * GPU readers (layout cross-check):
 *   misc/reference/nvidium/src/main/resources/assets/nvidium/shaders/occlusion/scene.glsl:19-37
 *   .../occlusion/region_raster/mesh.glsl:42-58 (data.a == uint64_t(-1) tombstone check)
 * Alphadium's setRegionMetadata is identical (alphadium RegionManager.java:96-127).
 */
package com.deds.meshelium.terrain;

import java.nio.ByteBuffer;

/**
 * Writer for the 16-byte GPU region metadata record (one 8×4×8-section
 * region = 256 section slots = 128×64×128 blocks).
 *
 * <h2>Layout (2 little-endian longs), RegionManager.java:118-125</h2>
 * <pre>
 * long A:
 *   bits 62-63  sizeY = maxY-minY of OCCUPIED section positions (0-3)
 *   bits 59-61  sizeX = maxX-minX (0-7)
 *   bits 56-58  sizeZ = maxZ-minZ (0-7)
 *   bits 48-55  lastIdx — highest occupied packed POSITION index 0-255.
 *               The GPU calls it "count" and dispatches lastIdx+1 mesh
 *               workgroups over COMPACTED slots (scene.glsl:35-37,
 *               section_raster/task.glsl) — it is NOT the section count.
 *               Invariants to preserve: compacted ids <= lastIdx, and
 *               trailing empty slots zeroed (study §2 port notes).
 *   bits 24-47  absolute min section X = rx*8 + minX, masked to 24 bits
 *               (GPU sign-extends, scene.glsl:27-33)
 *   bits  0-23  absolute min section Y = ry*4 + minY, masked to 24 bits
 * long B:
 *   bits 40-63  absolute min section Z = rz*8 + minZ, masked to 24 bits
 *   bits 30-39  transformationId (10 bits, max 1024 —
 *               RegionManager.java:20-21,123)
 *   bits  0-29  free (written 0)
 * </pre>
 *
 * <p>Tombstone: all 16 bytes 0xFF (memSet −1, RegionManager.java:72-73);
 * the GPU checks {@code data.a == uint64_t(-1)}
 * (region_raster/mesh.glsl:46). Keep the exact value.</p>
 */
public final class RegionRecord {

    /** Bytes per record (RegionManager.java:24). */
    public static final int META_SIZE = 16;
    /** RegionManager.java:20-21. */
    public static final int MAX_TRANSFORMATION_SIZE_BITS = 10;
    public static final int MAX_TRANSFORMATION_COUNT = 1 << MAX_TRANSFORMATION_SIZE_BITS;

    private RegionRecord() {}

    /** Region metadata buffer size (RegionManager.java:45 — this one Nvidium sized correctly). */
    public static long regionBufferBytes(int maxRegions) {
        return (long) maxRegions * META_SIZE;
    }

    /**
     * Write one 16-byte record at the buffer's current position from
     * explicit fields.
     *
     * @param rx region coordinate (sectionX >> 3)
     * @param ry region coordinate (sectionY >> 2)
     * @param rz region coordinate (sectionZ >> 3)
     * @param localMinX min occupied section position in-region, 0-7
     * @param localMinY 0-3
     * @param localMinZ 0-7
     * @param sizeX max-min of occupied positions, 0-7 (NOT +1)
     * @param sizeY 0-3
     * @param sizeZ 0-7
     * @param lastIdx highest occupied packed position index, 0-255
     * @param transformationId 0..1023
     */
    public static void write(ByteBuffer dst, int rx, int ry, int rz,
                             int localMinX, int localMinY, int localMinZ,
                             int sizeX, int sizeY, int sizeZ,
                             int lastIdx, int transformationId) {
        TerrainVertexCodec.checkOrder(dst);
        checkRange("localMinX", localMinX, 7);
        checkRange("localMinY", localMinY, 3);
        checkRange("localMinZ", localMinZ, 7);
        checkRange("sizeX", sizeX, 7);
        checkRange("sizeY", sizeY, 3);
        checkRange("sizeZ", sizeZ, 7);
        checkRange("lastIdx", lastIdx, 255);
        if (transformationId < 0 || transformationId >= MAX_TRANSFORMATION_COUNT) {
            throw new IllegalArgumentException("transformationId out of bounds: " + transformationId);
        }
        checkAbs("absolute min section X", ((long) rx << 3) + localMinX);
        checkAbs("absolute min section Y", ((long) ry << 2) + localMinY);
        checkAbs("absolute min section Z", ((long) rz << 3) + localMinZ);

        // RegionManager.java:118-125 verbatim bit packing.
        long size = (long) sizeY << 62 | (long) sizeX << 59 | (long) sizeZ << 56;
        long count = (long) lastIdx << 48;
        long x = ((((long) rx << 3) + localMinX) & ((1 << 24) - 1)) << 24;
        long y = ((((long) ry << 2) + localMinY) & ((1 << 24) - 1));
        long z = ((((long) rz << 3) + localMinZ) & ((1 << 24) - 1)) << (64 - 24);
        long transformation = ((long) transformationId) << (64 - 24 - MAX_TRANSFORMATION_SIZE_BITS);
        dst.putLong(size | count | x | y);
        dst.putLong(z | transformation);
    }

    /**
     * Build a record from a 256-slot occupancy map indexed by packed
     * position key {@code (y&3)<<6 | (z&7)<<3 | (x&7)}
     * (RegionManager.java:227), replicating setRegionMetadata's scan
     * (":103-115") including its quirks: lastIdx is the highest occupied
     * POSITION index and sizes are max−min without the +1.
     *
     * @throws IllegalArgumentException if no slot is occupied — Nvidium
     *         never writes metadata for an empty region; it tombstones it
     *         (RegionManager.java:66-79)
     */
    public static void fromOccupancy(ByteBuffer dst, boolean[] occupancy,
                                     int rx, int ry, int rz, int transformationId) {
        if (occupancy.length != 256) {
            throw new IllegalArgumentException("occupancy must have 256 slots, got " + occupancy.length);
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int lastIdx = -1;
        for (int i = 0; i < 256; i++) {
            if (!occupancy[i]) continue;          // RegionManager.java:104
            int x = i & 7;                        // ":106"
            int y = i >>> 6;                      // ":107"
            int z = (i >>> 3) & 7;                // ":108"
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            lastIdx = i;
        }
        if (lastIdx < 0) {
            throw new IllegalArgumentException(
                    "empty region - write the tombstone instead (RegionManager.java:66-79)");
        }
        write(dst, rx, ry, rz, minX, minY, minZ,
                maxX - minX, maxY - minY, maxZ - minZ, lastIdx, transformationId);
    }

    /**
     * Write the 16-byte deleted-region tombstone: all bytes 0xFF
     * (RegionManager.java:72-73; GPU check region_raster/mesh.glsl:46).
     */
    public static void writeTombstone(ByteBuffer dst) {
        TerrainVertexCodec.checkOrder(dst);
        dst.putLong(-1L);
        dst.putLong(-1L);
    }

    private static void checkRange(String what, int value, int max) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(what + " must be 0.." + max + ", got " + value);
        }
    }

    private static void checkAbs(String what, long value) {
        // 24-bit signed budget; the GPU sign-extends from bit 23
        // (scene.glsl:27-33). Out-of-budget coords would alias.
        if (value < -(1 << 23) || value >= (1 << 23)) {
            throw new IllegalArgumentException(what + " exceeds the signed 24-bit budget: " + value);
        }
    }
}
