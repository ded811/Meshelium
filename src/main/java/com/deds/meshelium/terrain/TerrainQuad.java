/*
 * Meshelium — LGPL-3.0-only.
 *
 * Host-agnostic quad input for the wave-3a data layer. Shape follows what
 * Nvidium's repackager consumed per quad (facing bucket + pass-derived
 * material + 4 packed vertices): misc/reference/nvidium/src/main/java/
 * me/cortex/nvidium/sodiumCompat/SodiumResultCompatibility.java.
 */
package com.deds.meshelium.terrain;

import java.util.Objects;

/**
 * One abstract terrain quad: four vertices, a facing bucket, a translucency
 * flag, and the quad-level material that gets stamped on all four packed
 * vertices ({@link TerrainVertexCodec#materialBits(int, boolean)}).
 *
 * <p>Material conventions inherited from the repackager: solid and
 * translucent quads use {@code mip=true, alphaCutoffIndex=0}
 * (SodiumResultCompatibility.java:112,185); cutout quads use their cutoff
 * index 1 (0.1) or 2 (0.5) (":202-211").</p>
 *
 * @param facing the facing bucket; for translucent quads it is recorded but
 *               not used for bucketing (translucent geometry bypasses the
 *               facing buckets and sits in the section-front prefix)
 * @param translucent true = goes into the translucent prefix
 * @param alphaCutoffIndex 0..2 into {@code float[]{0.0, 0.1, 0.5}}
 * @param mip whether the fragment shader samples with mipping
 */
public record TerrainQuad(QuadFacing facing, boolean translucent,
                          int alphaCutoffIndex, boolean mip,
                          TerrainVertex v0, TerrainVertex v1,
                          TerrainVertex v2, TerrainVertex v3) {

    public TerrainQuad {
        Objects.requireNonNull(facing, "facing");
        Objects.requireNonNull(v0, "v0");
        Objects.requireNonNull(v1, "v1");
        Objects.requireNonNull(v2, "v2");
        Objects.requireNonNull(v3, "v3");
        if (alphaCutoffIndex < 0 || alphaCutoffIndex > 2) {
            throw new IllegalArgumentException("alphaCutoffIndex must be 0..2, got " + alphaCutoffIndex);
        }
    }

    public TerrainVertex vertex(int i) {
        return switch (i) {
            case 0 -> v0;
            case 1 -> v1;
            case 2 -> v2;
            case 3 -> v3;
            default -> throw new IndexOutOfBoundsException("quad vertex index " + i);
        };
    }
}
