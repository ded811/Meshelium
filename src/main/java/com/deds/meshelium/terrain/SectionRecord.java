/*
 * Meshelium — LGPL-3.0-only.
 *
 * Derived from Nvidium by MCRcortex (LGPL-3.0). Byte layout re-verified
 * against the ORIGINAL writer, not just the study summary:
 *   misc/reference/nvidium/src/main/java/me/cortex/nvidium/managers/SectionManager.java:102-122
 * GPU readers (layout cross-check):
 *   misc/reference/nvidium/src/main/resources/assets/nvidium/shaders/terrain/task.glsl:39-51
 *   .../occlusion/section_raster/mesh.glsl:54-73
 *   .../occlusion/scene.glsl:4-12 (struct mirror; NOTE its per-field comments
 *   mislabel y/z — the Java writer + task.glsl decode are authoritative)
 * Alphadium keeps these 32 bytes bit-identical and APPENDS 16
 * (translucencyDataIdx at +32; alphadium SectionManager.java:30,208-223).
 */
package com.deds.meshelium.terrain;

import java.nio.ByteBuffer;

/**
 * Writer for the 32-byte GPU section metadata record.
 *
 * <h2>Layout (8 little-endian ints)</h2>
 * <pre>
 * header (ivec4), SectionManager.java:110-114:
 *  i0 header.x  bits 0-3 geomMinX | bits 4-7 geomSizeX | bits 8-31 chunkX (signed 24-bit via &lt;&lt;8)
 *  i1 header.y  bits 0-3 geomMinY | bits 4-7 geomSizeY | bits 8-16 chunkY (9-bit signed,
 *               GPU sign-extends: task.glsl:41-43)     | bit 17 hide flag
 *               | bits 18-25 compacted section ref id (translucency-sort redirect)
 *               | bits 26-31 free
 *  i2 header.z  bits 0-3 geomMinZ | bits 4-7 geomSizeZ | bits 8-31 chunkZ (signed 24-bit)
 *  i3 header.w  terrainAddress — QUAD-granularity offset into the terrain
 *               arena (byte offset = addr * 64, BufferArena.java:41)
 * renderRanges (ivec4), SectionManager.java:118-122; consumed
 * task_common.glsl:30-87 — bucket semantics in {@link QuadFacing}:
 *  i4  bits 0-15 count[POS_X]      | bits 16-31 count[POS_Y]
 *  i5  bits 0-15 count[POS_Z]      | bits 16-31 count[NEG_X]
 *  i6  bits 0-15 count[NEG_Y]      | bits 16-31 count[NEG_Z]
 *  i7  bits 0-15 count[UNASSIGNED] | bits 16-31 translucent quad count
 * </pre>
 *
 * <p>An ALL-ZERO record is the empty-slot tombstone. The GPU's emptiness
 * check ({@code sectionEmpty}, scene.glsl:39-42) is in practice keyed on
 * header.w == 0 — which is why terrain-arena quad address 0 is RESERVED
 * (BufferArena.java:30-31, {@link TerrainArena}): a live section can never
 * have terrainAddress 0.</p>
 *
 * <h2>Buffer sizing — study Q13 fix designed in</h2>
 * <p>Nvidium sizes its section buffer as {@code maxSections(=maxRegions*200)
 * * 32 B} = 320 MB but ADDRESSES it as {@code regionId * 8192 B}
 * (RegionManager.java:46,85-88) — only 39,062 region ids fit while
 * maxRegions = 50,000, with nothing guarding the overflow
 * (NVIDIUM-ARCHITECTURE.md §2 port notes). Meshelium sizes by addressing:
 * {@link #sectionBufferBytes(int)} = maxRegions × 256 × 32.</p>
 */
public final class SectionRecord {

    /** Bytes per record (SectionManager.java:24). */
    public static final int SECTION_SIZE = 32;
    /** Sections per 8×4×8 region (RegionManager.java:18). */
    public static final int SECTIONS_PER_REGION = 256;
    /** Bytes of section metadata per region = 8192 (RegionManager.java:26). */
    public static final int BYTES_PER_REGION = SECTION_SIZE * SECTIONS_PER_REGION;

    /** header.y bit index of the hide flag (SectionManager.java:111,144). */
    public static final int HIDE_BIT = 17;

    private SectionRecord() {}

    /**
     * Correctly sized section metadata buffer for {@code maxRegions} —
     * the Q13 fix: sized by the {@code regionId * 8192} addressing actually
     * used, not by a decoupled maxSections count.
     */
    public static long sectionBufferBytes(int maxRegions) {
        return (long) maxRegions * BYTES_PER_REGION;
    }

    /**
     * Write one 32-byte record at the buffer's current position.
     *
     * @param chunkX absolute section (chunk) X, signed 24-bit
     * @param chunkY absolute section Y, signed 9-bit (world height budget —
     *               ±256 sections; MC 26.2 fits, re-checked in the test)
     * @param chunkZ absolute section Z, signed 24-bit
     * @param geomMinX..geomSizeZ the 4-bit geometry AABB fields from
     *               {@link EncodedSectionMesh}
     * @param hidden api0 hide bit (SectionManager.java:125-146)
     * @param compactedSectionId this record's own compacted slot id 0-255,
     *               the translucency-sort self-reference (bits 18-25;
     *               rewritten on swap-remove, RegionManager.java:191-195)
     * @param terrainAddress quad-granularity arena address (never 0 for a
     *               live section; {@link TerrainArena} reserves quad 0)
     * @param offsets the 8 bucket counts per {@link QuadFacingBuckets}
     */
    public static void write(ByteBuffer dst,
                             int chunkX, int chunkY, int chunkZ,
                             int geomMinX, int geomMinY, int geomMinZ,
                             int geomSizeX, int geomSizeY, int geomSizeZ,
                             boolean hidden, int compactedSectionId,
                             int terrainAddress, short[] offsets) {
        TerrainVertexCodec.checkOrder(dst);
        checkSigned("chunkX", chunkX, 24);
        checkSigned("chunkY", chunkY, 9);
        checkSigned("chunkZ", chunkZ, 24);
        checkNibble("geomMinX", geomMinX);
        checkNibble("geomMinY", geomMinY);
        checkNibble("geomMinZ", geomMinZ);
        checkNibble("geomSizeX", geomSizeX);
        checkNibble("geomSizeY", geomSizeY);
        checkNibble("geomSizeZ", geomSizeZ);
        if (compactedSectionId < 0 || compactedSectionId > 255) {
            throw new IllegalArgumentException("compactedSectionId must be 0..255, got " + compactedSectionId);
        }
        if (offsets.length != QuadFacingBuckets.OFFSETS_LENGTH) {
            throw new IllegalArgumentException("offsets must have length 8, got " + offsets.length);
        }

        // SectionManager.java:110-113 verbatim bit packing.
        int px = chunkX << 8 | geomSizeX << 4 | geomMinX;
        int py = (chunkY & 0x1FF) << 8 | geomSizeY << 4 | geomMinY
                | (hidden ? 1 << HIDE_BIT : 0) | (compactedSectionId << 18);
        int pz = chunkZ << 8 | geomSizeZ << 4 | geomMinZ;
        dst.putInt(px);
        dst.putInt(py);
        dst.putInt(pz);
        dst.putInt(terrainAddress);

        // SectionManager.java:118-122 verbatim: pairs of u16 counts.
        for (int i = 0; i < 4; i++) {
            dst.putInt(Short.toUnsignedInt(offsets[i * 2])
                    | (Short.toUnsignedInt(offsets[i * 2 + 1]) << 16));
        }
    }

    /** Convenience overload taking the encoder's output directly. */
    public static void write(ByteBuffer dst, int chunkX, int chunkY, int chunkZ,
                             EncodedSectionMesh mesh, boolean hidden,
                             int compactedSectionId, int terrainAddress) {
        write(dst, chunkX, chunkY, chunkZ,
                mesh.minX(), mesh.minY(), mesh.minZ(),
                mesh.sizeX(), mesh.sizeY(), mesh.sizeZ(),
                hidden, compactedSectionId, terrainAddress, mesh.offsets());
    }

    /**
     * Write the 32-byte empty-slot tombstone (all zeros) — what
     * RegionManager writes over removed slots (RegionManager.java:161,175)
     * and what trailing never-used slots must hold for the GPU's
     * {@code sectionEmpty} skip to work (study §2 port notes).
     */
    public static void writeEmpty(ByteBuffer dst) {
        TerrainVertexCodec.checkOrder(dst);
        for (int i = 0; i < SECTION_SIZE / 4; i++) {
            dst.putInt(0);
        }
    }

    private static void checkSigned(String what, int value, int bits) {
        int min = -(1 << (bits - 1));
        int max = (1 << (bits - 1)) - 1;
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    what + " must fit signed " + bits + "-bit [" + min + ".." + max + "], got " + value);
        }
    }

    private static void checkNibble(String what, int value) {
        if (value < 0 || value > 15) {
            throw new IllegalArgumentException(what + " must be 0..15, got " + value);
        }
    }
}
