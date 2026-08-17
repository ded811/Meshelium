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
 * Merges adjacent identical block faces into larger quads.
 *
 * <h2>What it is worth, measured rather than assumed</h2>
 * <p>At render distance 64, over 10,000 real sections
 * (docs/PERFORMANCE.md): <b>5.9 percent</b> fewer quads with vanilla's
 * Smooth Lighting on, and <b>18.0 percent with it off</b>. The gap is the
 * whole story of this class. Vanilla bakes ambient occlusion per VERTEX, so
 * most faces are shaded ramps and two ramps rarely tile into one larger
 * ramp; with Smooth Lighting off vanilla takes its flat path instead, every
 * face is one colour, and nothing is disqualified for its corners at all.</p>
 *
 * <p>Moving lighting into the fragment shader would lift both to a measured
 * ceiling of 29.7 percent, and it is <b>not being built</b>: the cost is per
 * pixel while the prize is per quad, so break-even needs about 31 points of
 * quad reduction at 1440p against roughly 26 available. The arithmetic and
 * the three reviews that produced it are in docs/PERFORMANCE.md.</p>
 *
 * <h2>The merge predicate is conservative on purpose</h2>
 * <p>Two faces merge only if the result rasterises to exactly the pixels the
 * originals did. They must agree on facing, plane, material, sprite and the
 * sprite's ORIENTATION, and their corner values must form an affine field
 * over the merged lattice, which is the exact condition for one four-vertex
 * quad to interpolate what several used to. A cleverer predicate could beat
 * this; it could not be safer, and geometry that renders differently is not
 * an optimisation.</p>
 *
 * <h2>Deliberate exclusions</h2>
 * <ul>
 *   <li><b>Translucent.</b> Meshelium preserves vanilla's back-to-front sort
 *   and has a resort path keyed to it. Merging would destroy that ordering
 *   for a few percent of the geometry.</li>
 *   <li><b>UNASSIGNED facing.</b> Crosses (grass tufts, flowers) rotate 45
 *   degrees, so they have no plane to merge within.</li>
 *   <li><b>Across sections.</b> Never, by construction: this runs per
 *   section build and cannot see its neighbours. That is also the owner's
 *   standing instruction.</li>
 * </ul>
 *
 * <h2>Why runs are powers of two</h2>
 * <p>The merged quad has to tile its sprite, and the tile count rides in the
 * five spare bits of the material byte. {1,2,4,8,16} on each axis is what
 * those bits hold, packed as a joint pair index, and it keeps the shader's
 * wrap to a multiply. A run of 5 becomes a 4 and a 1, which costs one extra
 * quad on a run that already removed four. Sixteen is a section's width, so
 * the cap is the geometry's own limit rather than the encoding's.</p>
 */
public final class GreedyMesher {

    /**
     * Longest tile run on one axis: the material byte's limit, and also a
     * section's width, so nothing is left on the table by stopping here.
     */
    private static final int MAX_RUN = TerrainVertexCodec.MAX_REPEAT;

    /**
     * Per-section self-check, off unless {@code -Dmeshelium.verifyGreedy=true}.
     *
     * <p>The failure mode that matters here is silent: a sweep that drops a
     * face leaves a hole nobody notices until a player is standing in front
     * of it, and a sweep that covers one twice wastes what it just saved.
     * Both are the same invariant - the rectangles must PARTITION the group -
     * and the harness runs this over thousands of real sections, which is
     * worth more than any synthetic case anyone would think to write. It
     * costs a counter and a set size per group, and players never pay it.</p>
     */
    private static final boolean VERIFY = Boolean.getBoolean("meshelium.verifyGreedy");

    private GreedyMesher() {
    }

    // ------------------------------------------------------------------
    // What the merge costs the BUILD threads.
    //
    // The frame-time win is measured and published; this is the other side
    // of the ledger and it was going unmeasured. It never touches the frame
    // path, so it cannot show up in a bench frame time, but it is real: it
    // runs once per section build on a ForkJoin worker, and slower section
    // builds mean slower pop-in when a player flies or breaks blocks. Two
    // adders and a nanoTime pair, only while the merge is on.
    // ------------------------------------------------------------------

    private static final java.util.concurrent.atomic.LongAdder MERGE_NANOS =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder MERGE_SECTIONS =
            new java.util.concurrent.atomic.LongAdder();

    /** Mean microseconds this merge has cost per section, and the count. */
    public static String costSummary() {
        long sections = MERGE_SECTIONS.sum();
        if (sections == 0) {
            return "off";
        }
        return String.format("%.0f us/section over %d", MERGE_NANOS.sum() / 1000.0 / sections,
                sections);
    }

    /**
     * What two faces must share to be candidates.
     *
     * <p>Colour and light are deliberately NOT here. They used to be, along
     * with a gate demanding all four corners of a face be identical, and
     * together those rejected 65 percent of all terrain because vanilla
     * bakes ambient occlusion per vertex. The lattice test in
     * {@link #affine} replaces both, and it is strictly weaker: identical
     * corners is the constant case of an affine field.</p>
     */
    private record Key(QuadFacing facing, int plane, int cutoff, boolean mip,
                       int u0, int v0, int u1, int v1, int orient) {}

    /**
     * A unit face's grid cell, the quad it came from, and its four corner
     * values flattened as {@code (da * 2 + db) * CHANNELS + channel}, where
     * da and db are the corner's offsets along the face's two in-plane axes.
     * {@code vertexAt} maps the same index back to the quad's own vertex.
     */
    private record Cell(int a, int b, TerrainQuad quad, int[] corners, int[] vertexAt) {}

    /**
     * Merge what can be merged; pass everything else through untouched.
     *
     * <p>Returns the input list itself when nothing merged, so a section
     * that gains nothing costs one pass and no allocation.</p>
     */
    public static List<TerrainQuad> merge(List<TerrainQuad> quads) {
        long t0 = System.nanoTime();
        try {
            return mergeTimed(quads);
        } finally {
            MERGE_NANOS.add(System.nanoTime() - t0);
            MERGE_SECTIONS.increment();
        }
    }

    private static List<TerrainQuad> mergeTimed(List<TerrainQuad> quads) {
        Map<Key, List<Cell>> groups = null;
        List<TerrainQuad> passthrough = new ArrayList<>(quads.size());

        for (TerrainQuad q : quads) {
            Cell cell = eligibleCell(q);
            if (cell == null) {
                passthrough.add(q);
                continue;
            }
            if (groups == null) {
                groups = new HashMap<>();
            }
            int axis = planeAxis(q.facing());
            Key key = new Key(q.facing(), Math.round(component(q.v0(), axis)),
                    q.alphaCutoffIndex(), q.mip(),
                    quant(minU(q)), quant(minV(q)), quant(maxU(q)), quant(maxV(q)),
                    uvOrientation(q, axis == 0 ? 1 : 0, axis == 2 ? 1 : 2));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(cell);
        }
        if (groups == null) {
            return quads;
        }

        List<TerrainQuad> out = passthrough;
        for (List<Cell> group : groups.values()) {
            mergeGroup(group, out);
        }
        return out;
    }

    /**
     * Sweep one group of co-planar identical unit faces into rectangles.
     *
     * <p>Textbook greedy with one addition: runs are clamped to a power of
     * two no greater than {@link #MAX_RUN}, because that is what the tile
     * count can encode. Extending right first and then down is not
     * interchangeable with the reverse; the vertical pass is where most of
     * the saving is, and dropping it costs about ten percentage points.</p>
     */
    private static void mergeGroup(List<Cell> group, List<TerrainQuad> out) {
        if (group.size() == 1) {
            out.add(group.get(0).quad());
            return;
        }
        Map<Long, Cell> grid = new HashMap<>(group.size() * 2);
        for (Cell c : group) {
            grid.put(pack(c.a(), c.b()), c);
        }
        java.util.HashSet<Long> used = new java.util.HashSet<>(group.size() * 2);
        List<Cell> sorted = new ArrayList<>(group);
        sorted.sort((x, y) -> x.b() != y.b()
                ? Integer.compare(x.b(), y.b()) : Integer.compare(x.a(), y.a()));

        long area = 0;
        for (Cell c : sorted) {
            long here = pack(c.a(), c.b());
            if (used.contains(here)) {
                continue;
            }
            int width = 1;
            int height = 1;
            Plane plane = PLANE.get();
            if (plane.begin(c)) {
                while (width < MAX_RUN) {
                    Cell next = freeCell(grid, used, c.a() + width, c.b());
                    if (next == null || !plane.fits(next, width, 0)) {
                        break;
                    }
                    width++;
                }
                // Shrinking to a power of two cannot break the test: a
                // sub-rectangle of a plane is still on that plane.
                width = TerrainVertexCodec.largestRepeat(width);

                heightLoop:
                while (height < MAX_RUN) {
                    for (int i = 0; i < width; i++) {
                        Cell next = freeCell(grid, used, c.a() + i, c.b() + height);
                        if (next == null || !plane.fits(next, i, height)) {
                            break heightLoop;
                        }
                    }
                    height++;
                }
                height = TerrainVertexCodec.largestRepeat(height);
            }

            for (int j = 0; j < height; j++) {
                for (int i = 0; i < width; i++) {
                    used.add(pack(c.a() + i, c.b() + j));
                }
            }
            area += (long) width * height;
            out.add(width == 1 && height == 1
                    ? c.quad()
                    : expand(grid, c.quad(), c.a(), c.b(), width, height));
        }
        if (VERIFY && (area != group.size() || used.size() != group.size())) {
            // area < size drops faces; area > size == used.size() would mean
            // covering a cell the group never had; area > used.size() means
            // two rectangles overlap. One message, all three.
            //
            // Thrown rather than reported: the caller already wraps this pass
            // and routes a throw to the residency error latch the harness
            // reads, so this stays a pure package with no host imports.
            throw new IllegalStateException("greedy merge did not partition a group of "
                    + group.size() + " faces: rectangles cover " + area + " cells over "
                    + used.size() + " distinct positions");
        }
    }

    /** R, G, B, blockLight, skyLight: the five channels a corner carries. */
    private static final int CHANNELS = 5;

    /**
     * Can this rectangle be drawn as ONE quad without changing a pixel?
     *
     * <p>A merged rectangle has four vertices, so the hardware interpolates
     * its corner values linearly across each triangle. That reproduces the
     * original per-corner field exactly when, and only when, the field is
     * AFFINE over the lattice: {@code v(i,j) == v(0,0) + i*du + j*dv}, in
     * exact integer arithmetic, on every channel. Affinity also makes the
     * result independent of how each original quad was triangulated, because
     * a linear function restricted to any triangle is the barycentric
     * interpolation of that triangle's vertices.</p>
     *
     * <p>The lattice must be single-valued first: two faces meeting at a
     * point have to agree on its value, or there is no field to interpolate.
     * With vanilla's Smooth Lighting on they usually do, because both faces
     * derive that corner from the same 2x2 block square.</p>
     *
     * <p>This replaces the old all-four-corners-identical gate, which was
     * the affine test's constant case and nothing else. Measured at render
     * distance 64, relaxing it to affine takes the merge from 3.4 to 5.9
     * percent of all quads with Smooth Lighting on, and changes nothing with
     * it off (every face is already constant there). The gain is small
     * because vanilla truncates when it scales a colour by a face's shade,
     * so a genuine ambient-occlusion ramp lands on unevenly spaced integers
     * and is not exactly affine; what this does collect is the very common
     * face whose shading is constant ALONG the run and varies only across
     * it, which the old gate rejected outright.</p>
     */
    /**
     * The affine field the seed cell defines, tested one cell at a time.
     *
     * <p>The first build of this materialised the whole (W+1)x(H+1) lattice
     * and re-checked all of it at every step of the sweep's probing, which
     * cost 408 microseconds per section on the build workers. It was also
     * unnecessary. The seed cell alone pins the plane: its four corners give
     * v00, du and dv, and every other cell either lies on that plane or does
     * not. Checking each cell against the plane is simultaneously the
     * single-valued test and the affinity test, because two cells that both
     * match the plane at a shared lattice point necessarily agree there.</p>
     *
     * <p>So there is no lattice, each cell is examined exactly once, and the
     * whole sweep is linear in the cells it touches.</p>
     */
    private static final class Plane {
        private final int[] v00 = new int[CHANNELS];
        private final int[] du = new int[CHANNELS];
        private final int[] dv = new int[CHANNELS];

        /**
         * Pin the plane to a seed cell. False when the seed's own four
         * corners are not planar, in which case no rectangle containing it
         * can be affine and it can only stand alone.
         */
        boolean begin(Cell seed) {
            int[] c = seed.corners();
            for (int ch = 0; ch < CHANNELS; ch++) {
                int p00 = c[ch];
                int p01 = c[CHANNELS + ch];
                int p10 = c[2 * CHANNELS + ch];
                int p11 = c[3 * CHANNELS + ch];
                v00[ch] = p00;
                du[ch] = p10 - p00;
                dv[ch] = p01 - p00;
                if (p11 != p00 + du[ch] + dv[ch]) {
                    return false;
                }
            }
            return true;
        }

        /** Does this cell, at grid offset (ci, cj) from the seed, lie on it? */
        boolean fits(Cell cell, int ci, int cj) {
            int[] c = cell.corners();
            for (int slot = 0; slot < 4; slot++) {
                int i = ci + (slot >> 1);
                int j = cj + (slot & 1);
                int base = slot * CHANNELS;
                for (int ch = 0; ch < CHANNELS; ch++) {
                    if (c[base + ch] != v00[ch] + i * du[ch] + j * dv[ch]) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /** Build workers are a fixed pool, so one plane each beats one per rectangle. */
    private static final ThreadLocal<Plane> PLANE = ThreadLocal.withInitial(Plane::new);

    /** The cell at (a, b) if it exists and no rectangle has taken it yet. */
    private static Cell freeCell(Map<Long, Cell> grid, java.util.Set<Long> used, int a, int b) {
        long k = pack(a, b);
        return used.contains(k) ? null : grid.get(k);
    }

    /**
     * Stretch a unit face over a rectangle, keeping its UVs.
     *
     * <p>Each vertex keeps everything except its two in-plane coordinates,
     * and those move to the corresponding corner of the rectangle: a vertex
     * on the cell's low edge goes to the rectangle's low edge, one on the
     * high edge to the high edge. That preserves winding and keeps each
     * corner's UV attached to the same corner, so the sprite still maps the
     * way it did. The TILING is the shader's job, driven by the repeat
     * counts in the material byte; the UVs deliberately still describe one
     * tile.</p>
     *
     * <p><b>Colour and light come from the rectangle's OWN outer corners,
     * not from the seed cell.</b> Since {@link #affine} allowed neighbours
     * whose corner values differ, copying the seed's four values would paint
     * the whole rectangle with one cell's shading. The corner at
     * {@code (0,0)} belongs to the seed, but {@code (W,0)} belongs to the
     * cell at the far end of the run, and so on. The affine invariant is
     * what makes taking only those four correct: every interior corner the
     * merge swallowed lies exactly on the plane they define.</p>
     */
    private static TerrainQuad expand(Map<Long, Cell> grid, TerrainQuad q,
            int baseA, int baseB, int width, int height) {
        int axis = planeAxis(q.facing());
        int axisA = axis == 0 ? 1 : 0;
        int axisB = axis == 2 ? 1 : 2;
        TerrainVertex[] v = new TerrainVertex[4];
        for (int i = 0; i < 4; i++) {
            TerrainVertex s = q.vertex(i);
            boolean highA = Math.round(component(s, axisA)) > baseA;
            boolean highB = Math.round(component(s, axisB)) > baseB;
            float na = highA ? baseA + width : baseA;
            float nb = highB ? baseB + height : baseB;
            TerrainVertex shade = cornerVertex(grid, baseA, baseB, width, height, highA, highB);
            v[i] = withPlaneCoords(withShading(s, shade), axis, axisA, na, axisB, nb);
        }
        // width and height count along the POSITION axes A and B; the shader
        // tiles along the SPRITE's U and V. On a face where U runs along B
        // (east and west by vanilla's own convention, and anywhere a model
        // rotates its UVs) those are swapped. See uvOrientation.
        boolean uAlongB = (uvOrientation(q, axisA, axisB) & 1) != 0;
        int repeatU = uAlongB ? height : width;
        int repeatV = uAlongB ? width : height;
        return new TerrainQuad(q.facing(), q.translucent(), q.alphaCutoffIndex(), q.mip(),
                v[0], v[1], v[2], v[3], repeatU, repeatV);
    }

    /**
     * The vertex sitting at one outer corner of the merged rectangle.
     *
     * <p>The corner {@code (highA, highB)} lives on the cell at that end of
     * the rectangle, at that cell's own matching corner: the rectangle's far
     * corner is the far cell's far corner, not the seed's.</p>
     */
    private static TerrainVertex cornerVertex(Map<Long, Cell> grid, int baseA, int baseB,
            int width, int height, boolean highA, boolean highB) {
        int cellA = baseA + (highA ? width - 1 : 0);
        int cellB = baseB + (highB ? height - 1 : 0);
        Cell cell = grid.get(pack(cellA, cellB));
        int slot = (highA ? 1 : 0) * 2 + (highB ? 1 : 0);
        return cell.quad().vertex(cell.vertexAt()[slot]);
    }

    /** Take position and UV from one vertex, colour and light from another. */
    private static TerrainVertex withShading(TerrainVertex geometry, TerrainVertex shade) {
        if (geometry == shade) {
            return geometry;
        }
        return new TerrainVertex(geometry.x(), geometry.y(), geometry.z(),
                geometry.u(), geometry.v(),
                shade.colorAbgr(), shade.blockLight(), shade.skyLight());
    }

    private static TerrainVertex withPlaneCoords(TerrainVertex s, int axis,
            int axisA, float a, int axisB, float b) {
        float x = s.x();
        float y = s.y();
        float z = s.z();
        if (axisA == 0) {
            x = a;
        } else if (axisA == 1) {
            y = a;
        } else {
            z = a;
        }
        if (axisB == 0) {
            x = b;
        } else if (axisB == 1) {
            y = b;
        } else {
            z = b;
        }
        return new TerrainVertex(x, y, z, s.u(), s.v(), s.colorAbgr(),
                s.blockLight(), s.skyLight());
    }

    /** The grid cell and corner values of a mergeable unit face, or null. */
    private static Cell eligibleCell(TerrainQuad q) {
        if (q.translucent() || q.facing() == QuadFacing.UNASSIGNED) {
            return null;
        }
        int axis = planeAxis(q.facing());
        float planeF = component(q.v0(), axis);
        int axisA = axis == 0 ? 1 : 0;
        int axisB = axis == 2 ? 1 : 2;
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
        if (!unitSpan(minA, maxA) || !unitSpan(minB, maxB) || !integral(planeF)) {
            return null;
        }
        if (uvOrientation(q, axisA, axisB) < 0) {
            return null;
        }
        int baseA = Math.round(minA);
        int baseB = Math.round(minB);
        int[] corners = new int[4 * CHANNELS];
        int[] vertexAt = new int[4];
        java.util.Arrays.fill(vertexAt, -1);
        for (int i = 0; i < 4; i++) {
            TerrainVertex v = q.vertex(i);
            int da = Math.round(component(v, axisA)) > baseA ? 1 : 0;
            int db = Math.round(component(v, axisB)) > baseB ? 1 : 0;
            int slot = da * 2 + db;
            if (vertexAt[slot] >= 0) {
                return null; // degenerate: two vertices on one corner
            }
            vertexAt[slot] = i;
            int base = slot * CHANNELS;
            int color = v.colorAbgr();
            corners[base] = color & 0xFF;
            corners[base + 1] = (color >> 8) & 0xFF;
            corners[base + 2] = (color >> 16) & 0xFF;
            corners[base + 3] = v.blockLight();
            corners[base + 4] = v.skyLight();
        }
        return new Cell(baseA, baseB, q, corners, vertexAt);
    }

    /**
     * How the sprite's U and V axes sit on the face's two in-plane axes.
     *
     * <p>This is not decoration, it is a correctness requirement, and getting
     * it wrong is invisible on flat ground and obvious on a wall.</p>
     *
     * <p><b>The tile counts are per UV axis, but the sweep counts along
     * POSITION axes, and on some faces those are swapped.</b> This mesher
     * calls the in-plane axes A and B, and for an east or west face those are
     * Y and Z; vanilla's own face UVs for those faces take U from Z and V
     * from Y, so a run counted along A is a run along V, not U. Handing it to
     * the shader as {@code repeatU} tiles the sprite along the wrong axis: a
     * four-block-tall merge on a wall would repeat four times sideways and
     * stretch once vertically. Individual models can rotate their UVs too, so
     * a per-facing table would still be wrong for those.</p>
     *
     * <p>So the mapping is DERIVED from the quad's own data rather than
     * assumed, and it also joins the merge key. Two faces with the same
     * sprite rectangle but different UV orientations (a 90 degree rotation
     * leaves the rectangle identical, and a mirror leaves it identical too)
     * would otherwise merge into one quad drawing the seed's orientation over
     * both.</p>
     *
     * @return bit 0 = U runs along axis B rather than axis A; bit 1 = U
     *         decreases as its axis increases; bit 2 = the same for V; or
     *         -1 if the quad is degenerate in UV space, which is a refusal
     *         to merge rather than a guess
     */
    private static int uvOrientation(TerrainQuad q, int axisA, int axisB) {
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
        // Normalise both steps to point along INCREASING position, so the
        // signs below describe the axis and not which corner happens to be
        // vertex 0.
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
            return -1; // a sprite with no extent on one axis has no mapping
        }
        return (uAlongB ? 1 : 0) | (du < 0.0f ? 2 : 0) | (dv < 0.0f ? 4 : 0);
    }

    private static int planeAxis(QuadFacing f) {
        return switch (f) {
            case POS_X, NEG_X -> 0;
            case POS_Y, NEG_Y -> 1;
            case POS_Z, NEG_Z -> 2;
            case UNASSIGNED -> -1;
        };
    }

    private static final float EPS = 1.0e-4f;

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
