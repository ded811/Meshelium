/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Input contract shaped after the data Nvidium's encoder consumed from
 * Sodium's mesher (misc/reference/nvidium/src/main/java/me/cortex/nvidium/
 * sodiumCompat/NvidiumCompactChunkVertex.java:53-62), made host-agnostic:
 * primitives only, no vanilla/Sodium/LWJGL types.
 */
package com.deds.meshelium.terrain;

/**
 * One abstract terrain vertex, before packing. Wave 3b's vanilla tap builds
 * these; wave 3a tests build them by hand.
 *
 * @param x section-local block coordinate, valid domain [-8, +24)
 *          (Sodium mesh space — see {@link TerrainVertexCodec} assumptions)
 * @param y as x
 * @param z as x
 * @param u atlas UV, valid domain [0, 1]
 * @param v as u
 * @param colorAbgr packed 0xAABBGGRR; alpha is the AO/brightness multiplier
 *                  that {@link TerrainVertexCodec#premultiplyColor(int)}
 *                  folds into RGB at encode time
 * @param blockLight raw 0-255 (clamped to [8,248] at encode time)
 * @param skyLight raw 0-255 (clamped to [8,248] at encode time)
 */
public record TerrainVertex(float x, float y, float z,
                            float u, float v,
                            int colorAbgr,
                            int blockLight, int skyLight) {
}
