/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Ordering contract derived from Nvidium by MCRcortex (LGPL-3.0). The
 * load-bearing consumer is the task shader's face-culling walk:
 * misc/reference/nvidium/src/main/resources/assets/nvidium/shaders/terrain/task_common.glsl:39-78
 * (Alphadium's task_common.glsl:45-84 keeps the identical order, adding only
 * an opt-out flag that zeroes relChunkPos).
 */
package com.deds.meshelium.terrain;

/**
 * The seven facing buckets of Nvidium's per-section geometry layout, in the
 * EXACT order the ported task shaders will walk the renderRanges counts.
 * Ordinal order is load-bearing for GPU face culling — pinned by
 * MesheliumTerrainDataTest.
 *
 * <p>Derivation (task_common.glsl:39-78, with relChunkPos = sectionChunk −
 * cameraChunk from terrain/task.glsl:40-44): the walk starts at the end of
 * the translucent prefix ({@code fr = (ranges.w>>16)&0xFFFF}, line 39) and
 * consumes the six 16-bit counts of renderRanges.xyz low/high in order,
 * gating each on the camera side, then the seventh (renderRanges.w low)
 * unconditionally:</p>
 *
 * <pre>
 * bucket 0  ranges.x bits  0-15  drawn if relChunk.x &lt;= 0  → +X faces
 * bucket 1  ranges.x bits 16-31  drawn if relChunk.y &lt;= 0  → +Y faces
 * bucket 2  ranges.y bits  0-15  drawn if relChunk.z &lt;= 0  → +Z faces
 * bucket 3  ranges.y bits 16-31  drawn if relChunk.x &gt;= 0  → −X faces
 * bucket 4  ranges.z bits  0-15  drawn if relChunk.y &gt;= 0  → −Y faces
 * bucket 5  ranges.z bits 16-31  drawn if relChunk.z &gt;= 0  → −Z faces
 * bucket 6  ranges.w bits  0-15  always                      → unassigned
 * </pre>
 *
 * <p>(A +X face at plane x=k is visible from camera x ≥ k, i.e. from
 * relChunk.x ≤ 0.) This matches the POS_X, POS_Y, POS_Z, NEG_X, NEG_Y,
 * NEG_Z, UNASSIGNED order the study deduced for Sodium's ModelQuadFacing
 * (NVIDIUM-ARCHITECTURE.md §2, marked UNVERIFIED there because Sodium's
 * enum source is absent from the reference tree — irrelevant for Meshelium:
 * OUR encoder defines the buckets, and this enum + the GLSL above are the
 * whole contract).</p>
 */
public enum QuadFacing {
    POS_X,
    POS_Y,
    POS_Z,
    NEG_X,
    NEG_Y,
    NEG_Z,
    /** Quads with no axis-aligned facing — never face-culled. */
    UNASSIGNED;

    /** Number of buckets (6 directions + unassigned). */
    public static final int COUNT = 7;

    private static final QuadFacing[] VALUES = values();

    public static QuadFacing byIndex(int index) {
        return VALUES[index];
    }

    /**
     * The task shader's camera-side gate for this bucket
     * (task_common.glsl:42,48,54,60,66,72; UNASSIGNED always passes).
     * relChunk = sectionChunkPos − cameraChunkPos.
     */
    public boolean visibleFrom(int relChunkX, int relChunkY, int relChunkZ) {
        return switch (this) {
            case POS_X -> relChunkX <= 0;
            case POS_Y -> relChunkY <= 0;
            case POS_Z -> relChunkZ <= 0;
            case NEG_X -> relChunkX >= 0;
            case NEG_Y -> relChunkY >= 0;
            case NEG_Z -> relChunkZ >= 0;
            case UNASSIGNED -> true;
        };
    }
}
