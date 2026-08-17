/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Derived from Nvidium by MCRcortex (LGPL-3.0). Bit layout re-derived from
 * BOTH sides of the original format (per the study's instruction not to
 * trust summarized offsets):
 *   encoder: misc/reference/nvidium/src/main/java/me/cortex/nvidium/sodiumCompat/NvidiumCompactChunkVertex.java (lines 21-96)
 *   decoder: misc/reference/nvidium/src/main/resources/assets/nvidium/shaders/terrain/vertex_format.glsl (lines 1-44)
 * Cross-checked against NVIDIUM-ARCHITECTURE.md §4 "Packed terrain vertex"
 * — the study's description matched the source exactly.
 */
package com.deds.meshelium.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Codec for Nvidium's 16-byte packed terrain vertex — 4 consecutive vertices
 * form one 64-byte quad; the terrain arena addresses geometry in those quad
 * units. Encoding is HOST-AGNOSTIC: inputs are primitives, no vanilla or
 * LWJGL types.
 *
 * <h2>Bit layout (little-endian ints i0..i3, 16 bytes total)</h2>
 * <pre>
 * i0  bits  0-15  posX  u16   quantized (x+8) * 2048        [NvidiumCompactChunkVertex.java:56,73-75]
 *     bits 16-31  posY  u16   quantized (y+8) * 2048
 * i1  bits  0-15  posZ  u16   quantized (z+8) * 2048        [":57"]
 *     bits 16-23  material    bit16-17 = alpha-cutoff index  [vertex_format.glsl:29-35]
 *                             into float[]{0.0, 0.1, 0.5};
 *                             bit18 = has-mipping flag       [vertex_format.glsl:25-27]
 *     bits 24-31  blockLight  u8, CPU-clamped to [8, 248]    [":57,66-71"]
 * i2  bits  0- 7  red   u8 \
 *     bits  8-15  green u8  } pre-multiplied by vertex alpha [":58,82-90"; GLSL decode
 *     bits 16-23  blue  u8 /  (AO brightness), alpha dropped  vertex_format.glsl:16-19]
 *     bits 24-31  skyLight    u8, CPU-clamped to [8, 248]
 * i3  bits  0-15  U     u16   round(u * 32768)               [":59,93-96"]
 *     bits 16-31  V     u16   round(v * 32768)
 * </pre>
 *
 * <h2>Quantization domains</h2>
 * <ul>
 *   <li><b>Position:</b> 16-bit over [-8, +24) blocks around the section
 *       origin at 1/2048-block steps (MODEL_ORIGIN=8, MODEL_RANGE=32,
 *       POSITION_MAX_VALUE=65536 — NvidiumCompactChunkVertex.java:21-27).
 *       GLSL dequantize: {@code pos = packed * (32.0/65536.0) - 8.0}
 *       (vertex_format.glsl:1-13).</li>
 *   <li><b>UV:</b> scale 32768 = 2^15, so [0,1] atlas UVs land on [0,32768]
 *       and the 16-bit field wraps only above ~2.0
 *       (TEXTURE_MAX_VALUE=32768; GLSL divides by the host-injected
 *       TEXTURE_MAX_SCALE define, ShaderLoader.java:36).</li>
 *   <li><b>Light:</b> raw 0-255 per channel, clamped to [8, 248] — the
 *       lightmap-edge guard Nvidium inherited from Sodium's convention
 *       (NvidiumCompactChunkVertex.java:66-71). GLSL reads the pair as
 *       {@code uvec2(v.y>>24, v.z>>24) / 256.0} (vertex_format.glsl:41-44).</li>
 * </ul>
 *
 * <h2>Named Sodium-constant assumptions</h2>
 * <ul>
 *   <li>The [-8, 24) position domain is Sodium's chunk-mesh vertex space
 *       (8-block overhang each side); Nvidium's MODEL_ORIGIN/MODEL_RANGE
 *       exist to match it. Wave 3b must feed positions in that space.</li>
 *   <li>TEXTURE_MAX_VALUE=32768 is the value Nvidium injects into its
 *       shaders as TEXTURE_MAX_SCALE; it assumed Sodium's ChunkVertexType
 *       textureScale contract (getTextureScale() = 1/32768).</li>
 *   <li>Colour premultiply: Nvidium multiplies R/G/B by the alpha channel
 *       (Sodium's AO brightness) via ColorU8 float round-trips and zeroes
 *       alpha. Sodium's ColorU8 source is NOT in the reference tree, so the
 *       exact rounding is UNVERIFIED; this codec uses
 *       {@code Math.round(channel * alpha/255f)} (round-half-up), which can
 *       differ from Sodium's by at most 1 LSB per channel. Pinned by test.</li>
 *   <li>The material-bit VALUES (which cutoff index a given pass uses) came
 *       from repackaging Sodium's Material.bits(); Meshelium's wave-3b encoder
 *       chooses them itself: solid/translucent = mip + cutoff 0
 *       (SodiumResultCompatibility.java:112,185), cutout = mip flag +
 *       cutoff 1 or 2 (":202-211").</li>
 * </ul>
 *
 * <h2>Deviations from the original encoder (defensive, pinned by tests)</h2>
 * <ul>
 *   <li>Positions outside [-8, 24) are CLAMPED to the [0, 65535] quantized
 *       range. Nvidium does not mask or clamp — an out-of-range position
 *       there corrupts the neighbouring bit-field (65536 << 0 sets bit 16).
 *       Clamping keeps every in-range input bit-identical and makes
 *       out-of-range inputs safe instead of corrupting.</li>
 *   <li>UV keeps Nvidium's exact {@code round(uv*32768) & 0xFFFF}
 *       (wraps above ~2.0, like the original — atlas UVs are [0,1]).</li>
 * </ul>
 */
public final class TerrainVertexCodec {
    /** Bytes per packed vertex (NvidiumCompactChunkVertex.java:18 STRIDE). */
    public static final int VERTEX_STRIDE = 16;
    /** Bytes per quad = 4 vertices (BufferArena.java:41 addr*4*stride). */
    public static final int QUAD_STRIDE = 4 * VERTEX_STRIDE;

    /** NvidiumCompactChunkVertex.java:21. */
    public static final int POSITION_MAX_VALUE = 65536;
    /** NvidiumCompactChunkVertex.java:22; injected as TEXTURE_MAX_SCALE. */
    public static final int TEXTURE_MAX_VALUE = 32768;
    /** NvidiumCompactChunkVertex.java:24. */
    public static final float MODEL_ORIGIN = 8.0f;
    /** NvidiumCompactChunkVertex.java:25. */
    public static final float MODEL_RANGE = 32.0f;
    /** 2048 quantized steps per block. */
    public static final float MODEL_SCALE_INV = POSITION_MAX_VALUE / MODEL_RANGE;
    /** 1/2048 block per quantized step. */
    public static final float MODEL_SCALE = MODEL_RANGE / POSITION_MAX_VALUE;

    /** Light clamp bounds (NvidiumCompactChunkVertex.java:67-68). */
    public static final int LIGHT_MIN = 8;
    public static final int LIGHT_MAX = 248;

    private TerrainVertexCodec() {}

    // ------------------------------------------------------------------
    // Field-level quantizers (public so tests pin each field separately)
    // ------------------------------------------------------------------

    /**
     * Quantize one position component. Truncating cast exactly like
     * NvidiumCompactChunkVertex.java:73-75 ({@code (int)((8+v)*2048)}), then
     * clamped to [0, 65535] (deviation — see class doc).
     */
    public static int encodePosition(float v) {
        int q = (int) ((MODEL_ORIGIN + v) * MODEL_SCALE_INV);
        return Math.min(Math.max(q, 0), 0xFFFF);
    }

    /** Dequantize: vertex_format.glsl:6-13 ({@code q * 32/65536 - 8}). */
    public static float decodePosition(int q) {
        return (q & 0xFFFF) * MODEL_SCALE - MODEL_ORIGIN;
    }

    /** Quantize one UV component: NvidiumCompactChunkVertex.java:93-96. */
    public static int encodeUv(float uv) {
        return Math.round(uv * TEXTURE_MAX_VALUE) & 0xFFFF;
    }

    /** Dequantize: vertex_format.glsl:21-23 ({@code q / 32768}). */
    public static float decodeUv(int q) {
        return (q & 0xFFFF) * (1.0f / TEXTURE_MAX_VALUE);
    }

    /** Clamp a raw 0-255 light channel: NvidiumCompactChunkVertex.java:66-71. */
    public static int clampLight(int light) {
        return Math.min(Math.max(light & 0xFF, LIGHT_MIN), LIGHT_MAX);
    }

    /**
     * Pack the material byte: bits 0-1 = alpha-cutoff index into
     * {@code float[]{0.0, 0.1, 0.5}}, bit 2 = has-mipping flag
     * (vertex_format.glsl:25-39; values chosen by the repackager,
     * SodiumResultCompatibility.java:112,185,202-211).
     */
    public static int materialBits(int alphaCutoffIndex, boolean mip) {
        return materialBits(alphaCutoffIndex, mip, 1, 1);
    }

    /**
     * The material byte, with a greedy-merge tile repeat in the spare bits.
     *
     * <p>Bits 0-1 alpha cutoff, bit 2 mip, and then the five bits at 3-7
     * that have been unused since the format was ported. They are already
     * stamped identically on all four vertices of a quad, so they are
     * already a per-QUAD channel, and {@code terrain.mesh} already forwards
     * the whole byte to the fragment stage as a flat varying where
     * everything above bit 1 is currently read by nothing. A repeat count
     * therefore costs no vertex bytes and no new interpolant.</p>
     *
     * <p>Power-of-two runs, {1,2,4,8,16} on each axis. Sixteen is the width
     * of a section, so it is the largest run this merge can ever produce,
     * and it is a common one: the top faces of flat ground fill a section's
     * whole footprint. Capping at 8 turned one such face into four quads.</p>
     *
     * <p>Five values on each axis will not fit as two independent 2-bit
     * fields, and 3 bits each would need nine bits in an eight-bit byte. So
     * the PAIR is encoded jointly: {@code log2(u) * 5 + log2(v)}, which is
     * 0..24 and fits the five bits at 3-7 exactly. The shader turns it back
     * into two numbers once per primitive, not once per pixel.</p>
     *
     * @param repeatU tiles along the quad's U axis, a power of two in 1..16
     * @param repeatV tiles along the quad's V axis, a power of two in 1..16
     */
    public static int materialBits(int alphaCutoffIndex, boolean mip, int repeatU, int repeatV) {
        if (alphaCutoffIndex < 0 || alphaCutoffIndex > 2) {
            throw new IllegalArgumentException("alphaCutoffIndex must be 0..2, got " + alphaCutoffIndex);
        }
        int pair = log2Repeat(repeatU, "repeatU") * REPEAT_STEPS
                + log2Repeat(repeatV, "repeatV");
        return (alphaCutoffIndex & 3) | (mip ? 4 : 0) | (pair << 3);
    }

    /** Distinct run lengths per axis: 1, 2, 4, 8, 16. */
    public static final int REPEAT_STEPS = 5;

    /** Longest run the encoding can express on one axis, and a section's width. */
    public static final int MAX_REPEAT = 16;

    /** 1,2,4,8,16 to 0..4. Anything else is a caller bug, not a clamp. */
    private static int log2Repeat(int repeat, String what) {
        return switch (repeat) {
            case 1 -> 0;
            case 2 -> 1;
            case 4 -> 2;
            case 8 -> 3;
            case 16 -> 4;
            default -> throw new IllegalArgumentException(
                    what + " must be 1, 2, 4, 8 or 16, got " + repeat);
        };
    }

    /** Largest power-of-two tile run that fits in {@code span} blocks. */
    public static int largestRepeat(int span) {
        if (span >= 16) {
            return 16;
        }
        if (span >= 8) {
            return 8;
        }
        if (span >= 4) {
            return 4;
        }
        return span >= 2 ? 2 : 1;
    }

    /**
     * Premultiply RGB by the alpha channel and drop alpha, like
     * NvidiumCompactChunkVertex.java:82-90 (encodeColor). Input is packed
     * ABGR {@code 0xAABBGGRR} (vanilla/Sodium channel order: R in the low
     * byte — matching the GLSL decode of R at bits 0-7,
     * vertex_format.glsl:16-19). Returns {@code B<<16 | G<<8 | R} with the
     * top byte zero. Rounding is round-half-up per channel (named
     * assumption — see class doc).
     */
    public static int premultiplyColor(int colorAbgr) {
        float brightness = ((colorAbgr >>> 24) & 0xFF) / 255.0f;
        int r = Math.round((colorAbgr & 0xFF) * brightness);
        int g = Math.round(((colorAbgr >>> 8) & 0xFF) * brightness);
        int b = Math.round(((colorAbgr >>> 16) & 0xFF) * brightness);
        return r | (g << 8) | (b << 16);
    }

    // ------------------------------------------------------------------
    // Vertex / quad encode
    // ------------------------------------------------------------------

    /**
     * Encode one vertex (16 bytes) at the buffer's current position.
     * The buffer MUST be little-endian — GPU-side consumption is raw
     * little-endian bit-fields on every Meshelium target ISA.
     */
    public static void encodeVertex(ByteBuffer dst, TerrainVertex v, int materialBits) {
        checkOrder(dst);
        int blockLight = clampLight(v.blockLight());
        int skyLight = clampLight(v.skyLight());
        dst.putInt(encodePosition(v.x()) | (encodePosition(v.y()) << 16));
        dst.putInt(encodePosition(v.z()) | ((materialBits & 0xFF) << 16) | (blockLight << 24));
        dst.putInt(premultiplyColor(v.colorAbgr()) | (skyLight << 24));
        dst.putInt(encodeUv(v.u()) | (encodeUv(v.v()) << 16));
    }

    /**
     * Encode one quad (4 vertices, 64 bytes) at the buffer's current
     * position. The quad's material (cutoff index + mip) is stamped on all
     * four vertices, mirroring the repackager's per-range stamping
     * (SodiumResultCompatibility.java:110-113,183-189).
     */
    public static void encodeQuad(ByteBuffer dst, TerrainQuad quad) {
        int material = materialBits(quad.alphaCutoffIndex(), quad.mip(),
                quad.repeatU(), quad.repeatV());
        for (int i = 0; i < 4; i++) {
            encodeVertex(dst, quad.vertex(i), material);
        }
    }

    // ------------------------------------------------------------------
    // Decode (test/debug use — the GPU is the production decoder)
    // ------------------------------------------------------------------

    /** Decoded view of one packed vertex; field semantics as in the class doc. */
    public record Decoded(float x, float y, float z,
                          float u, float v,
                          int red, int green, int blue,
                          int blockLight, int skyLight,
                          int alphaCutoffIndex, boolean mip) {}

    /**
     * Decode one vertex from the buffer's current position, mirroring
     * vertex_format.glsl:6-44 field by field.
     */
    public static Decoded decodeVertex(ByteBuffer src) {
        checkOrder(src);
        int i0 = src.getInt();
        int i1 = src.getInt();
        int i2 = src.getInt();
        int i3 = src.getInt();
        return new Decoded(
                decodePosition(i0 & 0xFFFF),
                decodePosition((i0 >>> 16) & 0xFFFF),
                decodePosition(i1 & 0xFFFF),
                decodeUv(i3 & 0xFFFF),
                decodeUv((i3 >>> 16) & 0xFFFF),
                i2 & 0xFF,
                (i2 >>> 8) & 0xFF,
                (i2 >>> 16) & 0xFF,
                (i1 >>> 24) & 0xFF,
                (i2 >>> 24) & 0xFF,
                (i1 >>> 16) & 3,
                ((i1 >>> 16) & 4) != 0);
    }

    static void checkOrder(ByteBuffer buffer) {
        if (buffer.order() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException(
                    "Terrain data buffers must be LITTLE_ENDIAN (GPU bit-field layout), got " + buffer.order());
        }
    }
}
