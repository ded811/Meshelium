/*
 * Meshelium — LGPL-3.0-only.
 *
 * Wave 3a acceptance: pure-CPU pins on the terrain data layer. Expected
 * byte arrays are written out LONGHAND from the original Nvidium writers —
 * the test doubles as the layout documentation (docs/TERRAIN-DATA.md carries
 * the same numbers with citations). The SegmentedManager sequences are
 * ported from Nvidium's own fuzz/expand harness
 * (misc/reference/nvidium/.../util/SegmentedManager.java:172-231 main()).
 */
package com.deds.meshelium.gametest.client;

import com.deds.meshelium.terrain.EncodedSectionMesh;
import com.deds.meshelium.terrain.IdProvider;
import com.deds.meshelium.terrain.QuadFacing;
import com.deds.meshelium.terrain.QuadFacingBuckets;
import com.deds.meshelium.terrain.RegionRecord;
import com.deds.meshelium.terrain.SectionMeshEncoder;
import com.deds.meshelium.terrain.SectionRecord;
import com.deds.meshelium.terrain.SegmentedManager;
import com.deds.meshelium.terrain.TerrainArena;
import com.deds.meshelium.terrain.TerrainQuad;
import com.deds.meshelium.terrain.TerrainVertex;
import com.deds.meshelium.terrain.TerrainVertexCodec;
import com.deds.meshelium.terrain.TranslucentPrefix;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Wave-3a data-layer pins. Pure CPU — runs identically on both harness
 * paths (GL and Vulkan); registered after the boot smoke test so the title
 * screen is already settled.
 */
public final class MesheliumTerrainDataTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        codecFieldQuantizers();
        codecBitExactness();
        codecRoundtripEdges();
        bucketOrderPin();
        sectionEncoderBucketing();
        sectionRecordBytes();
        regionRecordBytes();
        bufferSizingFix();
        segmentedManagerDeterministic();
        segmentedManagerLimit();
        segmentedManagerFuzz();
        idProviderBehaviour();
        arenaBasics();
        arenaPendingRelease();
        arenaExhaustionAndStats();
        arenaCoalescingPathological();
        arenaChurnLeakCounters();
        translucentPrefixPermutation();
    }

    // ==================================================================
    // Wave 7: TranslucentPrefix (the resort permutation, pure CPU)
    // ==================================================================

    /**
     * Hand-built permutation pin: 4 quads whose 64-byte blocks are filled
     * with their ORIGINAL quad id, walked through two chained resorts and
     * back to the identity — every slot checked byte-for-byte. This is the
     * unit-level twin of the wave-7 resort tap: currentOrder is what the
     * prefix HOLDS, newOrder is what vanilla's re-sorted index buffer
     * names, and after permute slot j holds original quad newOrder[j].
     */
    private static void translucentPrefixPermutation() {
        final int stride = TerrainVertexCodec.QUAD_STRIDE;
        final int n = 4;
        byte[] prefix = new byte[n * stride];
        byte[] scratch = new byte[n * stride];

        // Build-time state: prefix laid out in currentOrder {2,0,3,1} —
        // slot 0 holds original quad 2, slot 1 holds quad 0, ...
        int[] currentOrder = {2, 0, 3, 1};
        for (int slot = 0; slot < n; slot++) {
            java.util.Arrays.fill(prefix, slot * stride, (slot + 1) * stride,
                    (byte) currentOrder[slot]);
        }

        // Resort to {1, 3, 0, 2}: slot j must then hold quad newOrder[j].
        int[] newOrder = {1, 3, 0, 2};
        TranslucentPrefix.permute(prefix, currentOrder, newOrder, scratch);
        for (int slot = 0; slot < n; slot++) {
            for (int b = 0; b < stride; b++) {
                checkEq(newOrder[slot], prefix[slot * stride + b],
                        "resort 1: slot " + slot + " byte " + b);
            }
        }

        // Chain a second resort back to identity — cumulative permutations
        // must compose (the state's order is now newOrder).
        int[] identity = {0, 1, 2, 3};
        TranslucentPrefix.permute(prefix, newOrder, identity, scratch);
        for (int slot = 0; slot < n; slot++) {
            for (int b = 0; b < stride; b++) {
                checkEq(slot, prefix[slot * stride + b],
                        "resort 2 (identity): slot " + slot + " byte " + b);
            }
        }

        // Malformed inputs must throw (the tap counts them and keeps the
        // current order — fail-safe, never corrupt).
        expectThrows(() -> TranslucentPrefix.permute(prefix, identity,
                new int[] {0, 1, 2, 2}, scratch), "duplicate id in newOrder");
        expectThrows(() -> TranslucentPrefix.permute(prefix, identity,
                new int[] {0, 1, 2}, scratch), "length mismatch");
        expectThrows(() -> TranslucentPrefix.permute(prefix, identity,
                new int[] {0, 1, 2, 4}, scratch), "id out of range");
        expectThrows(() -> TranslucentPrefix.permute(new byte[stride], identity,
                identity, scratch), "prefix sized wrong");
    }

    // ==================================================================
    // 2. TerrainVertexCodec
    // ==================================================================

    private static void codecFieldQuantizers() {
        // Position: [-8, 24) at 1/2048 steps (NvidiumCompactChunkVertex.java:21-27,73-75).
        checkEq(0, TerrainVertexCodec.encodePosition(-8.0f), "pos min quantizes to 0");
        checkEq(65535, TerrainVertexCodec.encodePosition(23.99951171875f), "pos max quantizes to 65535");
        checkEq(16384, TerrainVertexCodec.encodePosition(0.0f), "pos 0 -> 8*2048");
        check(TerrainVertexCodec.decodePosition(0) == -8.0f, "decode 0 -> -8");
        check(TerrainVertexCodec.decodePosition(65535) == 23.99951171875f, "decode 65535 -> 24 - 1/2048");
        // Deviation pin: out-of-range clamps instead of corrupting (Nvidium
        // would emit 65536 and flip a neighbouring bit-field).
        checkEq(65535, TerrainVertexCodec.encodePosition(24.0f), "pos 24.0 clamps to 65535");
        checkEq(0, TerrainVertexCodec.encodePosition(-8.01f), "pos below -8 clamps to 0");

        // UV: round(uv * 32768) (NvidiumCompactChunkVertex.java:93-96).
        checkEq(0, TerrainVertexCodec.encodeUv(0.0f), "uv 0");
        checkEq(32768, TerrainVertexCodec.encodeUv(1.0f), "uv 1.0 -> 32768 (2^15 fits u16)");
        check(TerrainVertexCodec.decodeUv(32768) == 1.0f, "uv decode 32768 -> 1.0");
        checkEq(16384, TerrainVertexCodec.encodeUv(0.5f), "uv 0.5");

        // Light clamp [8,248] (NvidiumCompactChunkVertex.java:66-71).
        checkEq(8, TerrainVertexCodec.clampLight(0), "light 0 clamps to 8");
        checkEq(248, TerrainVertexCodec.clampLight(255), "light 255 clamps to 248");
        checkEq(100, TerrainVertexCodec.clampLight(100), "light 100 passes");
        checkEq(8, TerrainVertexCodec.clampLight(8), "light 8 passes");
        checkEq(248, TerrainVertexCodec.clampLight(248), "light 248 passes");

        // Colour premultiply (NvidiumCompactChunkVertex.java:82-90).
        checkEq(0x0080FF40, TerrainVertexCodec.premultiplyColor(0xFF80FF40), "alpha 255 = identity, alpha dropped");
        checkEq(0x00808080, TerrainVertexCodec.premultiplyColor(0x80FFFFFF), "alpha 128 halves channels");
        checkEq(0, TerrainVertexCodec.premultiplyColor(0x00FFFFFF), "alpha 0 zeroes rgb");

        // Material bits (vertex_format.glsl:25-39).
        checkEq(0b100, TerrainVertexCodec.materialBits(0, true), "solid/translucent material = 0b100");
        checkEq(0b101, TerrainVertexCodec.materialBits(1, true), "cutout 0.1 + mip = 0b101");
        checkEq(0b010, TerrainVertexCodec.materialBits(2, false), "cutout 0.5 no mip = 0b010");
    }

    /**
     * THE 16-byte layout, longhand. Vertex: pos(1.5, -0.25, 15.0),
     * uv(0.25, 0.75), colour 0xFF80FF40 (ABGR: A=FF B=80 G=FF R=40),
     * blockLight 32, skyLight 200, material cutoff=1 mip=true (bits 0b101).
     *
     * i0 = 19456 | 15872<<16          = 0x3E004C00   (x=9.5*2048, y=7.75*2048)
     * i1 = 47104 | 5<<16 | 32<<24     = 0x2005B800   (z=23*2048)
     * i2 = 0x40 | 0xFF<<8 | 0x80<<16 | 200<<24 = 0xC880FF40
     * i3 = 8192 | 24576<<16           = 0x60002000
     */
    private static void codecBitExactness() {
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        TerrainVertexCodec.encodeVertex(buf,
                new TerrainVertex(1.5f, -0.25f, 15.0f, 0.25f, 0.75f, 0xFF80FF40, 32, 200),
                TerrainVertexCodec.materialBits(1, true));
        byte[] expected = {
                0x00, 0x4C, 0x00, 0x3E,               // i0 = 0x3E004C00
                0x00, (byte) 0xB8, 0x05, 0x20,        // i1 = 0x2005B800
                0x40, (byte) 0xFF, (byte) 0x80, (byte) 0xC8, // i2 = 0xC880FF40
                0x00, 0x20, 0x00, 0x60                // i3 = 0x60002000
        };
        checkBytes(expected, buf.array(), "packed vertex bit layout");
    }

    private static void codecRoundtripEdges() {
        TerrainVertex[] vertices = {
                // min corner, dark, uv origin
                new TerrainVertex(-8.0f, -8.0f, -8.0f, 0.0f, 0.0f, 0xFF000000, 0, 0),
                // max quantized corner, full light, uv far corner
                new TerrainVertex(23.99951171875f, 23.99951171875f, 23.99951171875f,
                        1.0f, 1.0f, 0xFFFFFFFF, 255, 255),
                // interior
                new TerrainVertex(1.5f, -0.25f, 15.0f, 0.25f, 0.75f, 0xFF80FF40, 32, 200),
        };
        int[][] expectedLights = {{8, 8}, {248, 248}, {32, 200}};
        for (int i = 0; i < vertices.length; i++) {
            TerrainVertex v = vertices[i];
            ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            TerrainVertexCodec.encodeVertex(buf, v, TerrainVertexCodec.materialBits(0, true));
            buf.flip();
            TerrainVertexCodec.Decoded d = TerrainVertexCodec.decodeVertex(buf);
            check(d.x() == v.x() && d.y() == v.y() && d.z() == v.z(),
                    "roundtrip position exact for lattice input " + i + ": " + d);
            check(d.u() == v.u() && d.v() == v.v(), "roundtrip uv exact " + i);
            checkEq(expectedLights[i][0], d.blockLight(), "roundtrip blockLight clamped " + i);
            checkEq(expectedLights[i][1], d.skyLight(), "roundtrip skyLight clamped " + i);
            checkEq(0, d.alphaCutoffIndex(), "roundtrip cutoff " + i);
            check(d.mip(), "roundtrip mip " + i);
        }
        // Big-endian buffers must be rejected, not silently mis-encoded.
        expectThrows(() -> TerrainVertexCodec.encodeVertex(
                        ByteBuffer.allocate(16), vertices[0], 0),
                "big-endian buffer rejected");
    }

    // ==================================================================
    // 5. Facing buckets + section encoder
    // ==================================================================

    /**
     * THE ordering contract (task_common.glsl:39-78; see QuadFacing docs).
     * If this test moves, the ported shaders' face culling silently breaks.
     */
    private static void bucketOrderPin() {
        checkEq(0, QuadFacing.POS_X.ordinal(), "bucket 0 = POS_X (drawn if relChunk.x <= 0)");
        checkEq(1, QuadFacing.POS_Y.ordinal(), "bucket 1 = POS_Y (drawn if relChunk.y <= 0)");
        checkEq(2, QuadFacing.POS_Z.ordinal(), "bucket 2 = POS_Z (drawn if relChunk.z <= 0)");
        checkEq(3, QuadFacing.NEG_X.ordinal(), "bucket 3 = NEG_X (drawn if relChunk.x >= 0)");
        checkEq(4, QuadFacing.NEG_Y.ordinal(), "bucket 4 = NEG_Y (drawn if relChunk.y >= 0)");
        checkEq(5, QuadFacing.NEG_Z.ordinal(), "bucket 5 = NEG_Z (drawn if relChunk.z >= 0)");
        checkEq(6, QuadFacing.UNASSIGNED.ordinal(), "bucket 6 = UNASSIGNED (always drawn)");
        checkEq(7, QuadFacing.COUNT, "7 buckets total");

        // Camera-side gates, exactly the GLSL signs.
        check(QuadFacing.POS_X.visibleFrom(-1, 5, 5), "+X visible from greater camera X");
        check(!QuadFacing.POS_X.visibleFrom(1, 5, 5), "+X hidden from lesser camera X");
        check(QuadFacing.POS_X.visibleFrom(0, 5, 5) && QuadFacing.NEG_X.visibleFrom(0, 5, 5),
                "same-chunk draws both X buckets (<=0 and >=0 overlap at 0)");
        check(QuadFacing.UNASSIGNED.visibleFrom(9, 9, 9), "unassigned never culled");
    }

    private static void sectionEncoderBucketing() {
        // Scrambled input; unique u-tag per quad (tag = raw u16 uv value).
        List<TerrainQuad> quads = new ArrayList<>(List.of(
                taggedQuad(QuadFacing.NEG_Y, false, 5),
                taggedQuad(QuadFacing.POS_X, true, 101),   // translucent 1
                taggedQuad(QuadFacing.UNASSIGNED, false, 7),
                taggedQuad(QuadFacing.POS_Z, false, 3),
                taggedQuad(QuadFacing.NEG_X, false, 4),
                taggedQuad(QuadFacing.NEG_Z, true, 102),   // translucent 2
                taggedQuad(QuadFacing.POS_X, false, 1),
                taggedQuad(QuadFacing.NEG_Z, false, 6),
                taggedQuad(QuadFacing.POS_Y, false, 2)));
        EncodedSectionMesh mesh = SectionMeshEncoder.encode(quads);

        checkEq(9, mesh.quadCount(), "quad count");
        checkEq(9 * 64, mesh.geometryBytes(), "64 bytes per quad");

        // Stream order: translucent prefix (input order) then buckets 0..6.
        int[] expectedTags = {101, 102, 1, 2, 3, 4, 5, 6, 7};
        ByteBuffer geo = mesh.geometry();
        for (int q = 0; q < expectedTags.length; q++) {
            int i3 = geo.getInt(q * 64 + 12); // first vertex of quad q, int3
            checkEq(expectedTags[q], i3 & 0xFFFF,
                    "quad " + q + " in stream is the tag-" + expectedTags[q] + " quad");
        }

        // offsets[]: counts per bucket, translucent count in slot 7
        // (SodiumResultCompatibility.java:166,219).
        short[] offsets = mesh.offsets();
        for (int i = 0; i < 7; i++) {
            checkEq(1, offsets[i], "bucket " + QuadFacing.byIndex(i) + " count");
        }
        checkEq(2, offsets[QuadFacingBuckets.TRANSLUCENT_SLOT], "translucent count");

        // [start,count] ranges reproduce the GPU's cumulative walk.
        int[] starts = QuadFacingBuckets.bucketStarts(2, new int[]{1, 1, 1, 1, 1, 1, 1});
        for (int i = 0; i < 7; i++) {
            checkEq(2 + i, starts[i], "bucket " + i + " start");
            checkEq(starts[i], mesh.bucketStart(i), "mesh bucket start " + i);
            checkEq(1, mesh.bucketCount(i), "mesh bucket count " + i);
        }

        // AABB from taggedQuad's fixed box x[1,2] y[3,4] z[5,6]:
        // size = max - min - 1 (SodiumResultCompatibility.java:43).
        checkEq(1, mesh.minX(), "aabb minX");
        checkEq(3, mesh.minY(), "aabb minY");
        checkEq(5, mesh.minZ(), "aabb minZ");
        checkEq(0, mesh.sizeX(), "aabb sizeX = 2-1-1");
        checkEq(0, mesh.sizeY(), "aabb sizeY");
        checkEq(0, mesh.sizeZ(), "aabb sizeZ");

        // Full-section span clamps to min 0, size 15.
        EncodedSectionMesh full = SectionMeshEncoder.encode(List.of(
                boxQuad(QuadFacing.UNASSIGNED, false, 0f, 0f, 0f, 16f, 16f, 16f, 1)));
        checkEq(0, full.minX(), "full-section minX");
        checkEq(15, full.sizeX(), "full-section sizeX clamps to 15");
        checkEq(15, full.sizeY(), "full-section sizeY");
        checkEq(15, full.sizeZ(), "full-section sizeZ");

        // 0-quad sections are deleted, never encoded (SectionManager.java:58-61).
        expectThrows(() -> SectionMeshEncoder.encode(List.of()), "empty section rejected");
    }

    // ==================================================================
    // 3. Record builders — byte-exactness, longhand
    // ==================================================================

    /**
     * Section record vector: chunk (5, -3, -7), geomMin (1,2,3),
     * geomSize (14,10,9), not hidden, refId 37, terrainAddress 123456,
     * offsets [3,0,7,1,0,2,4] + translucent 11.
     *
     * i0 = 5<<8 | 14<<4 | 1                          = 0x000005E1
     * i1 = (-3 & 0x1FF)<<8 | 10<<4 | 2 | 37<<18      = 0x0095FDA2
     * i2 = -7<<8 | 9<<4 | 3                          = 0xFFFFF993
     * i3 = 123456                                    = 0x0001E240
     * i4 = 3 | 0<<16   i5 = 7 | 1<<16   i6 = 0 | 2<<16   i7 = 4 | 11<<16
     * (SectionManager.java:110-122)
     */
    private static void sectionRecordBytes() {
        ByteBuffer buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        short[] offsets = {3, 0, 7, 1, 0, 2, 4, 11};
        SectionRecord.write(buf, 5, -3, -7, 1, 2, 3, 14, 10, 9, false, 37, 123456, offsets);
        byte[] expected = {
                (byte) 0xE1, 0x05, 0x00, 0x00,                     // i0 = 0x000005E1
                (byte) 0xA2, (byte) 0xFD, (byte) 0x95, 0x00,       // i1 = 0x0095FDA2
                (byte) 0x93, (byte) 0xF9, (byte) 0xFF, (byte) 0xFF,// i2 = 0xFFFFF993
                0x40, (byte) 0xE2, 0x01, 0x00,                     // i3 = 0x0001E240
                0x03, 0x00, 0x00, 0x00,                            // i4 = counts POS_X | POS_Y<<16
                0x07, 0x00, 0x01, 0x00,                            // i5 = counts POS_Z | NEG_X<<16
                0x00, 0x00, 0x02, 0x00,                            // i6 = counts NEG_Y | NEG_Z<<16
                0x04, 0x00, 0x0B, 0x00                             // i7 = UNASSIGNED | translucent<<16
        };
        checkBytes(expected, buf.array(), "32-byte section record");

        // Hide bit = header.y bit 17 (SectionManager.java:111,144).
        ByteBuffer hiddenBuf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        SectionRecord.write(hiddenBuf, 5, -3, -7, 1, 2, 3, 14, 10, 9, true, 37, 123456, offsets);
        checkEq(0x0095FDA2 | (1 << 17), hiddenBuf.getInt(4), "hide bit flips header.y bit 17");

        // Empty tombstone = 32 zero bytes.
        ByteBuffer empty = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        SectionRecord.writeEmpty(empty);
        checkBytes(new byte[32], empty.array(), "empty section record is all-zero");

        // 9-bit signed chunkY budget (task.glsl:41-43 sign-extension).
        expectThrows(() -> SectionRecord.write(
                ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN),
                0, 256, 0, 0, 0, 0, 0, 0, 0, false, 0, 1, new short[8]), "chunkY 256 out of 9-bit budget");
        // MC 26.2 world sections span y -4..19 — comfortably inside ±256.
    }

    /**
     * Region record vector: region (3, -1, -2), local mins (2,1,4),
     * sizes (5,2,3), lastIdx 201, transformationId 5.
     *
     * absMinX = 3*8+2 = 26; absMinY = -1*4+1 = -3; absMinZ = -2*8+4 = -12
     * A = 2<<62 | 5<<59 | 3<<56 | 201<<48 | (26&0xFFFFFF)<<24 | (-3&0xFFFFFF)
     *   = 0xABC900001AFFFFFD
     * B = (-12&0xFFFFFF)<<40 | 5<<30 = 0xFFFFF40140000000
     * (RegionManager.java:118-125)
     */
    private static void regionRecordBytes() {
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        RegionRecord.write(buf, 3, -1, -2, 2, 1, 4, 5, 2, 3, 201, 5);
        byte[] expected = {
                (byte) 0xFD, (byte) 0xFF, (byte) 0xFF, 0x1A, 0x00, 0x00, (byte) 0xC9, (byte) 0xAB, // A
                0x00, 0x00, 0x00, 0x40, 0x01, (byte) 0xF4, (byte) 0xFF, (byte) 0xFF                // B
        };
        checkBytes(expected, buf.array(), "16-byte region record");

        // fromOccupancy replicates setRegionMetadata's scan: occupy posKeys
        // 98 (y1 z4 x2) and 255 (y3 z7 x7) -> mins (2,1,4), sizes (5,2,3),
        // lastIdx 255 (highest occupied POSITION index, not a count).
        boolean[] occupancy = new boolean[256];
        occupancy[98] = true;
        occupancy[255] = true;
        ByteBuffer occBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        RegionRecord.fromOccupancy(occBuf, occupancy, 3, -1, -2, 5);
        ByteBuffer directBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        RegionRecord.write(directBuf, 3, -1, -2, 2, 1, 4, 5, 2, 3, 255, 5);
        checkBytes(directBuf.array(), occBuf.array(), "fromOccupancy == direct write");
        checkEq(0xABFF00001AFFFFFDL, occBuf.order(ByteOrder.LITTLE_ENDIAN).getLong(0),
                "occupancy A word (lastIdx 255)");

        // Tombstone: 16 bytes 0xFF; GPU checks a == uint64_t(-1)
        // (RegionManager.java:72-73, region_raster/mesh.glsl:46).
        ByteBuffer tomb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        RegionRecord.writeTombstone(tomb);
        byte[] allFF = new byte[16];
        java.util.Arrays.fill(allFF, (byte) 0xFF);
        checkBytes(allFF, tomb.array(), "region tombstone is all-0xFF");

        // Empty occupancy must not produce a record.
        expectThrows(() -> RegionRecord.fromOccupancy(
                ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN),
                new boolean[256], 0, 0, 0, 0), "empty region rejected");
    }

    /**
     * Study Q13 fix: Nvidium sized sectionBuffer = maxRegions*200*32 B
     * (320 MB) but addresses it regionId*8192 — only 39,062 of 50,000
     * region ids fit. Meshelium sizes by the addressing.
     */
    private static void bufferSizingFix() {
        checkEq(409_600_000L, SectionRecord.sectionBufferBytes(50_000), "section buffer sized for addressing");
        check(SectionRecord.sectionBufferBytes(50_000) > 320_000_000L,
                "fixed sizing exceeds Nvidium's undersized 320 MB");
        checkEq(8192, SectionRecord.BYTES_PER_REGION, "8 KB of section metadata per region");
        checkEq(800_000L, RegionRecord.regionBufferBytes(50_000), "region buffer bytes");
    }

    // ==================================================================
    // 1. Ported utilities vs originals
    // ==================================================================

    /**
     * The alloc/free/expand sequence from Nvidium's own commented-out
     * harness (SegmentedManager.java:174-187), with the outcomes it
     * expected, made explicit.
     */
    private static void segmentedManagerDeterministic() {
        SegmentedManager m = new SegmentedManager();
        long a = m.alloc(10);
        long b = m.alloc(11);
        long c = m.alloc(1);
        checkEq(0, a, "first alloc at 0");
        checkEq(10, b, "second alloc appended");
        checkEq(21, c, "third alloc appended");
        check(!m.expand(a, 1), "no room to expand into b");
        checkEq(11, m.free(b), "free returns size");
        check(m.expand(a, 1), "expand into freed hole");
        checkEq(11, m.getSize(a), "a grew to 11");
        check(m.expand(a, 10), "expand consumes exactly the rest of the hole");
        checkEq(21, m.getSize(a), "a grew to 21");
        check(!m.expand(a, 1), "hole exhausted, c blocks further growth");
        checkEq(21, m.free(a), "free a");
        checkEq(1, m.free(c), "free c");
        checkEq(0, m.getSize(), "all freed => total size 0 (no leak)");
    }

    private static void segmentedManagerLimit() {
        SegmentedManager m = new SegmentedManager();
        m.setLimit(100);
        checkEq(SegmentedManager.SIZE_LIMIT, m.alloc(101), "over-limit alloc returns SIZE_LIMIT");
        checkEq(0, m.alloc(60), "fits");
        checkEq(SegmentedManager.SIZE_LIMIT, m.alloc(41), "tail growth past limit refused");
        checkEq(60, m.alloc(40), "exact fit to the limit");
        checkEq(60, m.free(0), "free head block");
        checkEq(SegmentedManager.SIZE_LIMIT, m.alloc(100), "hole of 60 cannot take 100, tail is capped");
        checkEq(0, m.alloc(60), "freed hole reused");
        expectThrows(() -> m.alloc(0), "alloc(0) rejected");
    }

    /**
     * Nvidium's fuzz harness (SegmentedManager.java:208-231) with reduced
     * bounds: random alloc/free/expand churn, then free everything and
     * assert the allocator's total size returns to exactly 0.
     */
    private static void segmentedManagerFuzz() {
        for (int seed = 0; seed < 200; seed++) {
            Random r = new Random(seed);
            SegmentedManager m = new SegmentedManager();
            List<Long> live = new ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                int action = r.nextInt(3);
                if (action == 0 || live.isEmpty()) {
                    live.add(m.alloc(r.nextInt(1000) + 1));
                } else if (action == 1) {
                    m.free(live.remove(r.nextInt(live.size())));
                } else {
                    m.expand(live.get(r.nextInt(live.size())), r.nextInt(10) + 1);
                }
            }
            for (long addr : live) {
                m.free(addr);
            }
            checkEq(0, m.getSize(), "fuzz seed " + seed + " leaked");
        }
    }

    private static void idProviderBehaviour() {
        IdProvider p = new IdProvider();
        checkEq(0, p.provide(), "first id 0");
        checkEq(1, p.provide(), "second id 1");
        checkEq(2, p.provide(), "third id 2");
        checkEq(3, p.provide(), "fourth id 3");
        p.release(1);
        checkEq(1, p.provide(), "lowest free id reused first");
        p.release(3);
        checkEq(3, p.maxIndex(), "tail shrank past released 3");
        p.release(2);
        checkEq(2, p.maxIndex(), "cascade shrink stops at live id 1");
        checkEq(2, p.provide(), "regrows from the compacted tail");
        checkEq(3, p.maxIndex(), "maxIndex tracks the tail");
    }

    // ==================================================================
    // 4. TerrainArena
    // ==================================================================

    private static void arenaBasics() {
        long[] backingSize = {-1};
        TerrainArena arena = new TerrainArena(size -> {
            backingSize[0] = size;
            return 0x5EED;
        }, 64L * 1024, 16);
        checkEq(64L * 1024, backingSize[0], "backing allocated once with the arena size");
        checkEq(0x5EED, arena.backingHandle(), "opaque handle republished");
        checkEq(1024, arena.quadLimit(), "quad limit = bytes / 64");
        checkEq(16, arena.vertexStride(), "stride");

        // Quad 0 reserved (BufferArena.java:30-31) — the section record's
        // empty sentinel depends on no live section ever holding address 0.
        checkEq(1, arena.liveQuads(), "reserved quad 0 counted");
        int a = arena.allocQuads(10);
        checkEq(1, a, "first real alloc lands after the reserved quad");
        checkEq(64, arena.byteOffset(a), "byte offset = addr * 64");
        checkEq(640, arena.byteSize(a), "byte size = quads * 64");
        check(arena.canReuse(a, 10), "canReuse exact size");
        check(!arena.canReuse(a, 9), "canReuse rejects size mismatch");
    }

    private static void arenaPendingRelease() {
        TerrainArena arena = new TerrainArena(size -> 0, 64L * 1024, 16);
        int a = arena.allocQuads(10);
        int b = arena.allocQuads(10);
        checkEq(1, a, "a at 1");
        checkEq(11, b, "b at 11");

        // Use-after-free fix (study Q13/§3 TODO): freed range NOT reusable
        // until releasePending() — the render loop calls it after fences.
        arena.free(a);
        checkEq(1, arena.pendingFreeCount(), "a pending");
        checkEq(10, arena.pendingQuads(), "10 quads pending");
        int c = arena.allocQuads(10);
        checkEq(21, c, "pre-release alloc must NOT reuse the pending hole");
        checkEq(1, arena.releasePending(), "one range released");
        checkEq(0, arena.pendingFreeCount(), "pending drained");
        int d = arena.allocQuads(10);
        checkEq(1, d, "post-release alloc reuses the hole");

        arena.free(b);
        expectThrows(() -> arena.free(b), "double free throws immediately");
        expectThrows(() -> arena.canReuse(b, 10), "canReuse on pending address throws");
        expectThrows(() -> arena.free(9999), "free of unknown address throws");
    }

    private static void arenaExhaustionAndStats() {
        // 8-quad arena: 1 reserved, 7 usable.
        TerrainArena arena = new TerrainArena(size -> 0, 8L * 64, 16);
        checkEq(TerrainArena.ALLOC_FAILED, arena.allocQuads(8), "over-capacity alloc fails");
        // Stats-leak fix: the failed alloc must NOT inflate liveQuads
        // (original bug: BufferArena.java:35-39 counts before checking).
        checkEq(1, arena.liveQuads(), "failed alloc did not inflate stats");
        checkEq(1, arena.allocQuads(7), "exact remaining capacity fits");
        checkEq(8, arena.liveQuads(), "arena full");
        checkEq(TerrainArena.ALLOC_FAILED, arena.allocQuads(1), "full arena refuses");
        check(arena.getFragmentation() == 1.0f, "occupancy 1.0 when full");
        checkEq(0, arena.getUsedMB(), "512 bytes rounds to 0 MB");
    }

    private static void arenaCoalescingPathological() {
        TerrainArena arena = new TerrainArena(size -> 0, 64L * 1024, 16);
        int[] addrs = new int[8];
        for (int i = 0; i < 8; i++) {
            addrs[i] = arena.allocQuads(10);
            checkEq(1 + i * 10, addrs[i], "dense packing");
        }
        // Checkerboard free: holes of 10 at 11, 31, 51; freeing the TAIL
        // block (71) shrinks the arena extent instead of leaving a hole.
        arena.free(addrs[1]);
        arena.free(addrs[3]);
        arena.free(addrs[5]);
        arena.free(addrs[7]);
        checkEq(4, arena.releasePending(), "four ranges released");
        checkEq(71, arena.quadExtent(), "tail free shrank the extent 81 -> 71");
        checkEq(71, arena.allocQuads(11), "11 fits no 10-hole -> appended at the tail");
        checkEq(11, arena.allocQuads(10), "10 reuses the lowest 10-hole (best-fit)");
        // Freeing the live block BETWEEN the two remaining holes ([31,41)
        // and [51,61)) fuses all three into one 30-quad span — merge-on-free
        // through both neighbours (SegmentedManager.java free()).
        arena.free(addrs[4]); // 41..50, live, flanked by holes
        arena.releasePending();
        checkEq(31, arena.allocQuads(20), "coalesced 30-hole serves a 20-quad alloc at 31");
    }

    private static void arenaChurnLeakCounters() {
        TerrainArena arena = new TerrainArena(size -> 0, 1024L * 64, 16);
        Random r = new Random(42);
        List<Integer> live = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            int action = r.nextInt(3);
            if (action == 0 || live.isEmpty()) {
                int addr = arena.allocQuads(r.nextInt(30) + 1);
                if (addr != TerrainArena.ALLOC_FAILED) {
                    live.add(addr);
                }
            } else if (action == 1) {
                arena.free(live.remove(r.nextInt(live.size())));
            } else {
                arena.releasePending();
            }
        }
        for (int addr : live) {
            arena.free(addr);
        }
        arena.releasePending();
        // Leak invariant: only the reserved quad remains, allocator extent
        // shrank back to exactly 1 quad.
        checkEq(1, arena.liveQuads(), "leak counter: only reserved quad live");
        checkEq(1, arena.liveAllocationCount(), "leak counter: one live allocation");
        checkEq(0, arena.pendingFreeCount(), "leak counter: nothing pending");
        checkEq(0, arena.pendingQuads(), "leak counter: no pending quads");
        checkEq(1, arena.quadExtent(), "allocator extent back to the reserved quad");
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** Quad with all vertices in box x[1,2] y[3,4] z[5,6], u-tagged. */
    private static TerrainQuad taggedQuad(QuadFacing facing, boolean translucent, int uTag) {
        float u = uTag / 32768.0f;
        return new TerrainQuad(facing, translucent, 0, true,
                new TerrainVertex(1f, 3f, 5f, u, 0f, 0xFFFFFFFF, 240, 240),
                new TerrainVertex(2f, 3f, 5f, u, 0f, 0xFFFFFFFF, 240, 240),
                new TerrainVertex(2f, 4f, 6f, u, 1f, 0xFFFFFFFF, 240, 240),
                new TerrainVertex(1f, 4f, 6f, u, 1f, 0xFFFFFFFF, 240, 240));
    }

    private static TerrainQuad boxQuad(QuadFacing facing, boolean translucent,
                                       float x0, float y0, float z0,
                                       float x1, float y1, float z1, int uTag) {
        float u = uTag / 32768.0f;
        return new TerrainQuad(facing, translucent, 0, true,
                new TerrainVertex(x0, y0, z0, u, 0f, 0xFFFFFFFF, 240, 240),
                new TerrainVertex(x1, y0, z0, u, 0f, 0xFFFFFFFF, 240, 240),
                new TerrainVertex(x1, y1, z1, u, 1f, 0xFFFFFFFF, 240, 240),
                new TerrainVertex(x0, y1, z1, u, 1f, 0xFFFFFFFF, 240, 240));
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("terrain-data pin failed: " + what);
        }
    }

    private static void checkEq(long expected, long actual, String what) {
        if (expected != actual) {
            throw new AssertionError("terrain-data pin failed: " + what
                    + " — expected " + expected + " (0x" + Long.toHexString(expected)
                    + "), got " + actual + " (0x" + Long.toHexString(actual) + ")");
        }
    }

    private static void checkBytes(byte[] expected, byte[] actual, String what) {
        if (expected.length != actual.length) {
            throw new AssertionError("terrain-data pin failed: " + what
                    + " — length " + expected.length + " vs " + actual.length);
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                throw new AssertionError("terrain-data pin failed: " + what
                        + " — byte " + i + " expected 0x" + Integer.toHexString(expected[i] & 0xFF)
                        + ", got 0x" + Integer.toHexString(actual[i] & 0xFF)
                        + "\nexpected: " + hex(expected) + "\nactual:   " + hex(actual));
            }
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private static void expectThrows(Runnable action, String what) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("terrain-data pin failed: expected an exception — " + what);
    }
}
