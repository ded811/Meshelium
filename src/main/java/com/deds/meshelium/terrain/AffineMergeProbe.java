/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures the one merge that survived the shader-lighting review.
 *
 * <h2>The question</h2>
 * <p>{@link GreedyMesher} refuses any face whose four corners disagree, and
 * that refusal costs 65 percent of all terrain: vanilla bakes ambient
 * occlusion per vertex, so most faces are shaded ramps rather than flat
 * colour. The obvious answer was to move lighting into the fragment shader
 * so the corners stop mattering. Three independent reviews killed that on
 * arithmetic: it needs a per-pixel light lattice whose cost scales with
 * resolution while the prize does not, and break-even lands past 1440p.</p>
 *
 * <p>This is the other answer, and it is free. A merged rectangle is drawn
 * as one four-vertex quad, so the hardware interpolates its corner values
 * linearly across each triangle. If the ORIGINAL corner field over the
 * (W+1)x(H+1) lattice is itself affine - value(i,j) == v00 + i*du + j*dv,
 * exactly, in integer arithmetic, per channel - then that linear
 * interpolation reproduces every original corner exactly, and it does so
 * whatever diagonal each original quad was split along, because a linear
 * function restricted to any triangle IS the barycentric interpolation of
 * that triangle's vertices. Pixel parity by construction, no shader change,
 * no new data, no per-pixel cost.</p>
 *
 * <p>The common case it is aimed at: a row of top faces running beside a
 * wall has ambient occlusion that is constant ALONG the row and varies only
 * across it. Constant along one axis is affine. {@code uniform()} rejects
 * every one of them today.</p>
 *
 * <h2>Why this is a probe and not an implementation</h2>
 * <p>Building it means teaching {@link GreedyMesher#expand} to read the
 * rectangle's real outer corner values instead of copying the seed cell's,
 * which is a real change to the merge path. The cheap experiment comes
 * first: if the affine field is rare, none of that gets written. An
 * adversarial reviewer argued it will be rare, because vanilla truncates
 * when it scales a colour by a face's shade factor and the resulting
 * integer steps are not exactly even, which would break exact affinity on
 * any genuine ramp and leave only the constant-along-one-axis case. That is
 * a prediction, and this measures it.</p>
 *
 * <p>Two numbers come out, because the difference between them is the
 * decision. CONSISTENT counts merges where the lattice is merely
 * single-valued (adjacent faces agree at the corners they share) - that is
 * an upper bound needing per-pixel evaluation to collect. AFFINE counts the
 * subset that also interpolates exactly, which is the part that is free. If
 * they are close, build the free one and stop.</p>
 */
public final class AffineMergeProbe {

    /** R, G, B, blockLight, skyLight. */
    private static final int CHANNELS = 5;

    private AffineMergeProbe() {
    }

    /**
     * Colour and light struck out, orientation kept.
     *
     * <p>Orientation must stay: a 90 degree model rotation leaves the
     * sprite's atlas RECTANGLE identical, so without it this would count
     * merges between rotated variants that cannot be drawn.</p>
     */
    private record Key(QuadFacing facing, int plane, int cutoff, boolean mip,
                       int u0, int v0, int u1, int v1, int orient) {}

    /** One unit face and its four corners, indexed {@code da * 2 + db}. */
    private record Cell(int a, int b, int[] corners) {}

    /** {consistentRectangles, affineRectangles, eligibleCells}. */
    public static long[] measure(List<TerrainQuad> quads) {
        Map<Key, List<Cell>> groups = new HashMap<>();
        for (TerrainQuad q : quads) {
            Cell cell = cellOf(q);
            if (cell == null) {
                continue;
            }
            int axis = planeAxis(q.facing());
            int axisA = axis == 0 ? 1 : 0;
            int axisB = axis == 2 ? 1 : 2;
            groups.computeIfAbsent(new Key(q.facing(), Math.round(component(q.v0(), axis)),
                            q.alphaCutoffIndex(), q.mip(),
                            quant(minU(q)), quant(minV(q)), quant(maxU(q)), quant(maxV(q)),
                            orientation(q, axisA, axisB)),
                    k -> new ArrayList<>()).add(cell);
        }
        long consistent = 0;
        long affine = 0;
        long cells = 0;
        for (List<Cell> group : groups.values()) {
            cells += group.size();
            consistent += sweep(group, false);
            affine += sweep(group, true);
        }
        return new long[] {consistent, affine, cells};
    }

    /**
     * The same greedy sweep {@link GreedyMesher} runs, with the same run
     * clamp, but gated on the lattice test rather than on uniformity.
     *
     * @param requireAffine false to accept any single-valued lattice, true
     *                      to also require it to interpolate exactly
     */
    private static int sweep(List<Cell> group, boolean requireAffine) {
        Map<Long, Cell> grid = new HashMap<>(group.size() * 2);
        for (Cell c : group) {
            grid.put(pack(c.a(), c.b()), c);
        }
        java.util.HashSet<Long> used = new java.util.HashSet<>(group.size() * 2);
        List<Cell> sorted = new ArrayList<>(group);
        sorted.sort((x, y) -> x.b() != y.b()
                ? Integer.compare(x.b(), y.b()) : Integer.compare(x.a(), y.a()));

        int rectangles = 0;
        for (Cell c : sorted) {
            if (used.contains(pack(c.a(), c.b()))) {
                continue;
            }
            int width = 1;
            while (width < TerrainVertexCodec.MAX_REPEAT
                    && free(grid, used, c.a() + width, c.b())
                    && latticeOk(grid, c.a(), c.b(), width + 1, 1, requireAffine)) {
                width++;
            }
            // Shrinking to a power of two is safe for both tests: a
            // sub-rectangle of a single-valued lattice is single-valued, and
            // a sub-rectangle of an affine field is affine.
            width = TerrainVertexCodec.largestRepeat(width);

            int height = 1;
            heightLoop:
            while (height < TerrainVertexCodec.MAX_REPEAT) {
                for (int i = 0; i < width; i++) {
                    if (!free(grid, used, c.a() + i, c.b() + height)) {
                        break heightLoop;
                    }
                }
                if (!latticeOk(grid, c.a(), c.b(), width, height + 1, requireAffine)) {
                    break;
                }
                height++;
            }
            height = TerrainVertexCodec.largestRepeat(height);

            for (int j = 0; j < height; j++) {
                for (int i = 0; i < width; i++) {
                    used.add(pack(c.a() + i, c.b() + j));
                }
            }
            rectangles++;
        }
        return rectangles;
    }

    private static boolean free(Map<Long, Cell> grid, java.util.Set<Long> used, int a, int b) {
        long k = pack(a, b);
        return grid.containsKey(k) && !used.contains(k);
    }

    /**
     * Is the corner field over this rectangle drawable as one quad?
     *
     * <p>Builds the (w+1)x(h+1) lattice from the cells' own corners. Two
     * cells meeting at a lattice point must write the same value there or
     * the field is not even single-valued and no four-vertex quad can carry
     * it. With {@code requireAffine}, the field must additionally satisfy
     * {@code v(i,j) == v(0,0) + i*du + j*dv} exactly, which is the condition
     * for linear interpolation across the merged quad to reproduce every
     * original corner.</p>
     */
    private static boolean latticeOk(Map<Long, Cell> grid, int baseA, int baseB,
            int w, int h, boolean requireAffine) {
        int stride = w + 1;
        int[] lattice = new int[stride * (h + 1) * CHANNELS];
        boolean[] set = new boolean[stride * (h + 1)];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                Cell cell = grid.get(pack(baseA + i, baseB + j));
                if (cell == null) {
                    return false;
                }
                for (int da = 0; da < 2; da++) {
                    for (int db = 0; db < 2; db++) {
                        int point = (j + db) * stride + (i + da);
                        int src = (da * 2 + db) * CHANNELS;
                        int dst = point * CHANNELS;
                        if (set[point]) {
                            for (int ch = 0; ch < CHANNELS; ch++) {
                                if (lattice[dst + ch] != cell.corners()[src + ch]) {
                                    return false; // two faces disagree at a shared corner
                                }
                            }
                        } else {
                            set[point] = true;
                            System.arraycopy(cell.corners(), src, lattice, dst, CHANNELS);
                        }
                    }
                }
            }
        }
        if (!requireAffine) {
            return true;
        }
        for (int ch = 0; ch < CHANNELS; ch++) {
            int v00 = lattice[ch];
            int du = lattice[CHANNELS + ch] - v00;              // (1,0)
            int dv = lattice[stride * CHANNELS + ch] - v00;     // (0,1)
            for (int j = 0; j <= h; j++) {
                for (int i = 0; i <= w; i++) {
                    if (lattice[(j * stride + i) * CHANNELS + ch] != v00 + i * du + j * dv) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** A unit face's grid cell and corner values, or null if it cannot merge. */
    private static Cell cellOf(TerrainQuad q) {
        if (q.translucent() || q.facing() == QuadFacing.UNASSIGNED) {
            return null;
        }
        int axis = planeAxis(q.facing());
        int axisA = axis == 0 ? 1 : 0;
        int axisB = axis == 2 ? 1 : 2;
        float planeF = component(q.v0(), axis);
        if (!integral(planeF)) {
            return null;
        }
        float minA = Float.MAX_VALUE;
        float maxA = -Float.MAX_VALUE;
        float minB = Float.MAX_VALUE;
        float maxB = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            TerrainVertex v = q.vertex(i);
            if (!near(component(v, axis), planeF)) {
                return null;
            }
            minA = Math.min(minA, component(v, axisA));
            maxA = Math.max(maxA, component(v, axisA));
            minB = Math.min(minB, component(v, axisB));
            maxB = Math.max(maxB, component(v, axisB));
        }
        if (!unitSpan(minA, maxA) || !unitSpan(minB, maxB)) {
            return null;
        }
        int baseA = Math.round(minA);
        int baseB = Math.round(minB);
        int[] corners = new int[4 * CHANNELS];
        boolean[] seen = new boolean[4];
        for (int i = 0; i < 4; i++) {
            TerrainVertex v = q.vertex(i);
            int da = Math.round(component(v, axisA)) > baseA ? 1 : 0;
            int db = Math.round(component(v, axisB)) > baseB ? 1 : 0;
            int slot = da * 2 + db;
            if (seen[slot]) {
                return null; // degenerate: two vertices on the same corner
            }
            seen[slot] = true;
            int base = slot * CHANNELS;
            int color = v.colorAbgr();
            corners[base] = color & 0xFF;
            corners[base + 1] = (color >> 8) & 0xFF;
            corners[base + 2] = (color >> 16) & 0xFF;
            corners[base + 3] = v.blockLight();
            corners[base + 4] = v.skyLight();
        }
        return new Cell(baseA, baseB, corners);
    }

    /** The UV-to-position mapping, the same three bits {@link GreedyMesher} keys on. */
    private static int orientation(TerrainQuad q, int axisA, int axisB) {
        TerrainVertex origin = q.v0();
        float a0 = component(origin, axisA);
        float b0 = component(origin, axisB);
        TerrainVertex alongA = null;
        TerrainVertex alongB = null;
        for (int i = 1; i < 4; i++) {
            TerrainVertex o = q.vertex(i);
            boolean sameA = near(component(o, axisA), a0);
            boolean sameB = near(component(o, axisB), b0);
            if (!sameA && sameB) {
                alongA = o;
            } else if (sameA && !sameB) {
                alongB = o;
            }
        }
        if (alongA == null || alongB == null) {
            return -1;
        }
        float signA = component(alongA, axisA) > a0 ? 1.0f : -1.0f;
        float signB = component(alongB, axisB) > b0 ? 1.0f : -1.0f;
        float duA = (alongA.u() - origin.u()) * signA;
        float dvA = (alongA.v() - origin.v()) * signA;
        float duB = (alongB.u() - origin.u()) * signB;
        float dvB = (alongB.v() - origin.v()) * signB;
        boolean uAlongB = Math.abs(duB) > Math.abs(duA);
        float du = uAlongB ? duB : duA;
        float dv = uAlongB ? dvA : dvB;
        if (du == 0.0f || dv == 0.0f) {
            return -1;
        }
        return (uAlongB ? 1 : 0) | (du < 0.0f ? 2 : 0) | (dv < 0.0f ? 4 : 0);
    }

    private static final float EPS = 1.0e-4f;

    private static int planeAxis(QuadFacing f) {
        return switch (f) {
            case POS_X, NEG_X -> 0;
            case POS_Y, NEG_Y -> 1;
            case POS_Z, NEG_Z -> 2;
            case UNASSIGNED -> -1;
        };
    }

    private static float component(TerrainVertex v, int axis) {
        return axis == 0 ? v.x() : axis == 1 ? v.y() : v.z();
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= EPS;
    }

    private static boolean integral(float f) {
        return Math.abs(f - Math.round(f)) <= EPS;
    }

    private static boolean unitSpan(float min, float max) {
        return integral(min) && Math.abs((max - min) - 1.0f) <= EPS;
    }

    private static long pack(long a, long b) {
        return (a & 0xFFFFFFFFL) << 32 | (b & 0xFFFFFFFFL);
    }

    private static int quant(float uv) {
        return Math.round(uv * 32768.0f);
    }

    private static float minU(TerrainQuad q) {
        return Math.min(Math.min(q.v0().u(), q.v1().u()), Math.min(q.v2().u(), q.v3().u()));
    }

    private static float maxU(TerrainQuad q) {
        return Math.max(Math.max(q.v0().u(), q.v1().u()), Math.max(q.v2().u(), q.v3().u()));
    }

    private static float minV(TerrainQuad q) {
        return Math.min(Math.min(q.v0().v(), q.v1().v()), Math.min(q.v2().v(), q.v3().v()));
    }

    private static float maxV(TerrainQuad q) {
        return Math.max(Math.max(q.v0().v(), q.v1().v()), Math.max(q.v2().v(), q.v3().v()));
    }
}
