/*
 * Meshelium — LGPL-3.0-only.
 *
 * Wave-3b decoder: 26.2's 28-byte DefaultVertexFormat.BLOCK terrain
 * vertices → Meshelium's abstract TerrainQuads. Layout authority is the
 * wave-3 recon, bytecode-proven against the real jar:
 *   docs/VANILLA-SECTION-BUILD.md Q2.2 (BLOCK = pos float3 @0 / RGBA8 @12 /
 *   UV0 float2 @16 / UV2 2×int16 @24, stride 28, NO normal element)
 *   Q2.4 (facing is NOT recoverable from any vertex field — derive per quad
 *   by cross product, non-axis-aligned → UNASSIGNED)
 *   Q2.3 (TRANSLUCENT MeshData carries a distance-sorted index buffer built
 *   at compile time against vanilla's own camera snapshot — the translucent
 *   prefix order comes from THERE, not from a separately captured camera).
 */
package com.deds.meshelium.terrain.host;

import com.deds.meshelium.terrain.QuadFacing;
import com.deds.meshelium.terrain.TerrainQuad;
import com.deds.meshelium.terrain.TerrainVertex;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns one section's {@code Results.renderedLayers} (per-layer
 * {@link MeshData}, alive only until vanilla's staging memcpy) into the
 * quad list {@code SectionMeshEncoder} consumes. Pure CPU; runs on the
 * build thread (FJP worker, or the render thread on the compileSync path).
 *
 * <h2>Vertex field mapping (recon Q2.2, all offsets bytecode-proven)</h2>
 * <ul>
 *   <li><b>@0 position</b>: 3 floats, section-local [0,16) with model
 *       overhang — inside {@code TerrainVertexCodec}'s [-8,24) domain.</li>
 *   <li><b>@12 color</b>: written by {@code putRgba = ARGB.toABGR(int)} →
 *       one LE int read gives {@code 0xAABBGGRR} — exactly the
 *       {@link TerrainVertex#colorAbgr()} convention (alpha = the AO/tint
 *       brightness the codec premultiplies into RGB).</li>
 *   <li><b>@16 UV0</b>: 2 floats, atlas [0,1].</li>
 *   <li><b>@24 UV2</b>: 2 × int16 lightmap texel coords (block, sky), each
 *       0..240 in steps of 16 (vanilla {@code LightTexture.pack} =
 *       {@code block<<4 | sky<<20} split into two shorts by
 *       {@code putPackedUv}). Meshelium stores {@code coord + 8} — the
 *       half-texel centring that lands exactly on Nvidium's [8,248] clamp
 *       range (NvidiumCompactChunkVertex.java:66-71; the GLSL samples the
 *       16-px lightmap at {@code v/256}). NAMED ASSUMPTION: Sodium's
 *       encoder did the same +8; wave-4 pixel parity validates.</li>
 * </ul>
 *
 * <h2>Material bits (from vanilla's own pipeline defines, bytecode)</h2>
 * RenderPipelines: {@code pipeline/cutout_terrain} carries
 * {@code ALPHA_CUTOUT = 0.5f}, the translucent terrain pipeline
 * {@code ALPHA_CUTOUT = 0.1f}, solid none — so SOLID → cutoff index 0,
 * CUTOUT → index 2 (0.5), TRANSLUCENT → index 1 (0.1); mip on for all
 * three (26.2 samples all terrain through one mipped atlas sampler).
 * This deviates from Nvidium's Sodium-material mapping (translucent was
 * cutoff 0 there) because our source data is vanilla's pipelines, not
 * Sodium's materials; wave 7 revisits when the translucent shader lands.
 */
final class VanillaMeshDecoder {

    /** Stride of DefaultVertexFormat.BLOCK (recon Q2.2). */
    static final int BLOCK_STRIDE = 28;

    /**
     * Facing snap threshold: dominant axis component² must carry ≥ this
     * fraction of |n|². Axis-aligned terrain quads pass exactly (the other
     * two components are 0); anything meaningfully slanted (stairs' sloped
     * mod quads, plants' diagonal crosses at 0.5 each axis) falls to
     * UNASSIGNED = never face-culled — the safe bucket (recon Q2.4).
     */
    private static final float DOMINANCE = 0.9999f;

    private VanillaMeshDecoder() {}

    /**
     * Decode outcome + defensive-skip accounting. {@code translucentOrder}
     * (wave 7) is the ORIGINAL-quad-id at each translucent-prefix slot —
     * the build-time sorted order the decoder applied to the TRANSLUCENT
     * layer's quads (identity when vanilla shipped no/unexpected sorted
     * indices); null when the section has no translucent quads. It is the
     * {@code currentOrder} seed the resort permutation starts from
     * ({@code TranslucentPrefix}).
     */
    record DecodedSection(List<TerrainQuad> quads, int skippedLayers, int[] translucentOrder) {}

    /**
     * Decode every rendered layer of one section build. Never throws for
     * malformed vanilla data — unexpected formats are skipped and counted
     * (recon ledger 7: the tap reads {@code DrawState.format()} at runtime
     * rather than trusting the pipeline recon).
     */
    static DecodedSection decode(Map<ChunkSectionLayer, MeshData> renderedLayers) {
        List<TerrainQuad> quads = new ArrayList<>();
        int skipped = 0;
        int[] translucentOrder = null;
        // Thread-confined holder: decode runs concurrently on FJP workers,
        // so the applied-order side channel must live on this call's stack.
        int[][] appliedOrder = new int[1][];
        for (Map.Entry<ChunkSectionLayer, MeshData> entry : renderedLayers.entrySet()) {
            appliedOrder[0] = null;
            if (!decodeLayer(entry.getKey(), entry.getValue(), quads, appliedOrder)) {
                skipped++;
            } else if (entry.getKey().translucent() && appliedOrder[0] != null) {
                translucentOrder = appliedOrder[0];
            }
        }
        return new DecodedSection(quads, skipped, translucentOrder);
    }

    private static int[] identityOrder(int n) {
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        return order;
    }

    private static boolean decodeLayer(ChunkSectionLayer layer, MeshData meshData,
            List<TerrainQuad> out, int[][] appliedOrderOut) {
        MeshData.DrawState drawState = meshData.drawState();
        // Defensive format gate (ledger 7): identity check against the one
        // format the offsets below are proven for.
        if (drawState.format() != DefaultVertexFormat.BLOCK
                || drawState.format().getVertexSize() != BLOCK_STRIDE) {
            return false;
        }
        int vertexCount = drawState.vertexCount();
        if (vertexCount <= 0 || (vertexCount & 3) != 0) {
            return false; // topology QUADS ⇒ multiple of 4 (recon Q2.3)
        }
        int quadCount = vertexCount / 4;

        ByteBuffer vertices = meshData.vertexBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base = vertices.position();
        if (vertices.remaining() < quadCount * 4 * BLOCK_STRIDE) {
            return false;
        }

        boolean translucent = layer.translucent();
        int cutoffIndex = materialCutoffIndex(layer);

        // Translucent prefix order = vanilla's own build-time distance sort
        // (recon Q2.3: sortQuads wrote a back-to-front index buffer against
        // the camera snapshot doTask took). Reusing it means Meshelium's
        // prefix carries the exact same camera snapshot semantics without
        // capturing one itself; wave 7 validates ordering on screen AND
        // needs the applied order as the resort permutation's seed.
        int[] order = translucent ? sortedQuadOrder(meshData, drawState, quadCount) : null;
        if (translucent && quadCount > 0) {
            appliedOrderOut[0] = order != null ? order : identityOrder(quadCount);
        }

        for (int i = 0; i < quadCount; i++) {
            int q = order != null ? order[i] : i;
            int offset = base + q * 4 * BLOCK_STRIDE;
            TerrainVertex v0 = readVertex(vertices, offset);
            TerrainVertex v1 = readVertex(vertices, offset + BLOCK_STRIDE);
            TerrainVertex v2 = readVertex(vertices, offset + 2 * BLOCK_STRIDE);
            TerrainVertex v3 = readVertex(vertices, offset + 3 * BLOCK_STRIDE);
            QuadFacing facing = deriveFacing(v0, v1, v2);
            out.add(new TerrainQuad(facing, translucent, cutoffIndex, true, v0, v1, v2, v3));
        }
        return true;
    }

    private static int materialCutoffIndex(ChunkSectionLayer layer) {
        // Alpha-cutoff table is {0.0, 0.1, 0.5} (TerrainVertexCodec).
        return switch (layer) {
            case SOLID -> 0;        // no ALPHA_CUTOUT define on solid_terrain
            case CUTOUT -> 2;       // pipeline/cutout_terrain: 0.5f (bytecode)
            case TRANSLUCENT -> 1;  // translucent terrain: 0.1f (bytecode)
        };
    }

    private static TerrainVertex readVertex(ByteBuffer buf, int offset) {
        float x = buf.getFloat(offset);
        float y = buf.getFloat(offset + 4);
        float z = buf.getFloat(offset + 8);
        int colorAbgr = buf.getInt(offset + 12);
        float u = buf.getFloat(offset + 16);
        float v = buf.getFloat(offset + 20);
        int blockRaw = buf.getShort(offset + 24) & 0xFFFF;
        int skyRaw = buf.getShort(offset + 26) & 0xFFFF;
        // +8 half-texel centring; codec clamps to [8,248] either way.
        return new TerrainVertex(x, y, z, u, v, colorAbgr,
                Math.min(255, blockRaw + 8), Math.min(255, skyRaw + 8));
    }

    /**
     * Facing = dominant axis of cross(v1−v0, v2−v0), or UNASSIGNED when the
     * quad is degenerate or not axis-aligned (recon Q2.4). Sign convention:
     * vanilla block quads are wound CCW viewed from their facing side (the
     * Vulkan pipelines' CLOCKWISE front face is the post-Y-flip view of the
     * same winding), so the right-handed cross points along the facing.
     * UNVERIFIED at the margins (recon ledger 4) — a systematic sign flip
     * would surface as inverted face culling in wave 4's parity shot, and
     * the fix is one negation here.
     */
    static QuadFacing deriveFacing(TerrainVertex v0, TerrainVertex v1, TerrainVertex v2) {
        float e1x = v1.x() - v0.x(), e1y = v1.y() - v0.y(), e1z = v1.z() - v0.z();
        float e2x = v2.x() - v0.x(), e2y = v2.y() - v0.y(), e2z = v2.z() - v0.z();
        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        float len2 = ax * ax + ay * ay + az * az;
        if (len2 < 1.0e-12f) {
            return QuadFacing.UNASSIGNED; // degenerate (zero-area) quad
        }
        if (ax >= ay && ax >= az) {
            return (ax * ax >= DOMINANCE * len2)
                    ? (nx > 0 ? QuadFacing.POS_X : QuadFacing.NEG_X)
                    : QuadFacing.UNASSIGNED;
        }
        if (ay >= az) {
            return (ay * ay >= DOMINANCE * len2)
                    ? (ny > 0 ? QuadFacing.POS_Y : QuadFacing.NEG_Y)
                    : QuadFacing.UNASSIGNED;
        }
        return (az * az >= DOMINANCE * len2)
                ? (nz > 0 ? QuadFacing.POS_Z : QuadFacing.NEG_Z)
                : QuadFacing.UNASSIGNED;
    }

    /**
     * Wave 7 — decode a RESORT's new order from the raw index bytes the
     * row-7 tap sees ({@code addSectionBuffersToUberBuffer(TRANSLUCENT,
     * mesh, null, indexBytes)} HEAD; {@code ResortTransparencyTask.doTask}
     * passes {@code buildSortedIndexBuffer(...).byteBuffer()}). Same
     * whole-quad-groups shape as the build-time buffer, but no DrawState
     * travels with it — the index width is inferred from the byte count:
     * {@code remaining == quadCount*6*2} (u16) or {@code *4} (u32), the
     * only two widths {@code IndexType} has. Returns the original-quad-id
     * per sorted position, or null when anything is off (caller counts
     * malformed and keeps the current order — fail-safe: stale order, never
     * corrupt geometry).
     */
    static int[] resortQuadOrder(ByteBuffer indexBytes, int quadCount) {
        if (indexBytes == null || quadCount <= 0) {
            return null;
        }
        ByteBuffer ib = indexBytes.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base = ib.position();
        long remaining = ib.remaining();
        int indexBytesPer;
        if (remaining == (long) quadCount * 6 * 2) {
            indexBytesPer = 2;
        } else if (remaining == (long) quadCount * 6 * 4) {
            indexBytesPer = 4;
        } else {
            return null; // not a whole-quad index buffer for this count
        }
        int[] order = new int[quadCount];
        boolean[] seen = new boolean[quadCount];
        for (int i = 0; i < quadCount; i++) {
            int first = indexBytesPer == 2
                    ? ib.getShort(base + i * 6 * 2) & 0xFFFF
                    : ib.getInt(base + i * 6 * 4);
            int quad = first >>> 2;
            if (quad >= quadCount || seen[quad]) {
                return null;
            }
            seen[quad] = true;
            order[i] = quad;
        }
        return order;
    }

    /**
     * Recover vanilla's back-to-front quad order from the sorted index
     * buffer: 6 indices per quad ({@code 0,1,2, 2,3,0} + quad*4), so the
     * first index of sorted group i divided by 4 is the source quad id
     * (recon Q2.3: {@code buildSortedIndexBuffer} emits whole quads).
     * Falls back to source order when anything looks unexpected.
     */
    private static int[] sortedQuadOrder(MeshData meshData, MeshData.DrawState drawState,
            int quadCount) {
        ByteBuffer indices = meshData.indexBuffer();
        if (indices == null || drawState.indexCount() != quadCount * 6) {
            return null;
        }
        ByteBuffer ib = indices.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base = ib.position();
        boolean shortType = drawState.indexType() == IndexType.SHORT;
        int indexBytes = shortType ? 2 : 4;
        if (ib.remaining() < quadCount * 6 * indexBytes) {
            return null;
        }
        int[] order = new int[quadCount];
        boolean[] seen = new boolean[quadCount];
        for (int i = 0; i < quadCount; i++) {
            int first = shortType
                    ? ib.getShort(base + i * 6 * 2) & 0xFFFF
                    : ib.getInt(base + i * 6 * 4);
            int quad = first >>> 2;
            if (quad >= quadCount || seen[quad]) {
                return null; // not the whole-quad permutation we expect
            }
            seen[quad] = true;
            order[i] = quad;
        }
        return order;
    }
}
