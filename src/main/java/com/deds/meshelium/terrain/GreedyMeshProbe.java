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
 * Measures how much greedy meshing would win, without doing any.
 *
 * <h2>Why measure before building</h2>
 * <p>Greedy meshing folklore says flat terrain collapses to almost nothing.
 * A simulation over random 4-colourings of a 16x16 grid says natural
 * overworld terrain caps near 33 percent, because vanilla ships four
 * position-hashed rotation variants for exactly the blocks that make flat
 * surfaces: grass_block, dirt, sand, stone, deepslate. Netherrack has
 * sixteen. Meanwhile 1160 of 1198 blockstates bake a single model, so ice
 * sheets, End islands and anything player-built merge almost completely.</p>
 *
 * <p>Both of those are arithmetic about a model of the world, not a
 * measurement of one. This class produces the real number, on the real
 * terrain the player is standing in, before anyone writes a mesher. It runs
 * the actual algorithm and throws the answer away.</p>
 *
 * <h2>The merge predicate, and why it is conservative</h2>
 * <p>Two faces may merge only if the merged rectangle would rasterise to
 * exactly the pixels the two originals did. Vanilla bakes smooth lighting
 * and ambient occlusion PER VERTEX, so a quad whose four corners differ is
 * a bilinear ramp, and two such ramps do not generally tile into one larger
 * ramp. Rather than reason about when they do, this only merges quads that
 * are internally UNIFORM: all four corners carrying the same colour and the
 * same light. That is exactly the case where the merge is provably
 * pixel-identical.</p>
 *
 * <p>The consequence is that this number is a FLOOR. A cleverer predicate
 * could beat it. It cannot be an overestimate, which is the property worth
 * having when deciding whether to spend weeks on something.</p>
 *
 * <h2>What it deliberately does not count</h2>
 * <p>Translucent quads are excluded: Meshelium preserves vanilla's
 * translucent sort order and has a whole resort path keyed to it, so
 * merging there would break ordering for a layer that is a few percent of
 * the geometry. UNASSIGNED quads are excluded because they have no plane to
 * merge within: crosses (grass tufts, flowers) rotate 45 degrees, so their
 * normals fail the facing test by construction.</p>
 *
 * <p>Excluded from the merge, not from measurement: the census pass counts
 * how much of the translucent layer is flat still-water surface - disjoint
 * unit faces sharing one horizontal plane, the one geometry whose relative
 * draw order provably cannot matter - so the decision to ever merge that
 * layer starts from a number rather than a hunch.</p>
 */
public final class GreedyMeshProbe {

    /** One section's worth of answer, plus why the rest did not qualify. */
    public record Result(
            int quadsIn,
            /** Quads that passed every eligibility test and entered the merge. */
            int eligible,
            /** Rectangles those eligible quads collapsed into. */
            int merged,
            int skippedTranslucent,
            int skippedUnassigned,
            /** Not a 1x1 axis-aligned face on the block grid. */
            int skippedNonUnit,
            /** Corners disagree, so the quad is a ramp rather than a flat tile. */
            int skippedNonUniform,
            /** Eligible if lighting were sampled in the shader, not baked. */
            int litEligible,
            /** What those would collapse into. */
            int litMerged) {

        /** Quads eliminated, as a fraction of everything that came in. */
        public double reductionOfAll() {
            return quadsIn == 0 ? 0.0 : (quadsIn - (merged + quadsIn - eligible)) / (double) quadsIn;
        }

        /** Quads eliminated, as a fraction of the ones that could merge. */
        public double reductionOfEligible() {
            return eligible == 0 ? 0.0 : (eligible - merged) / (double) eligible;
        }
    }

    /**
     * Quads sharing all of this can tile into one rectangle. Position and
     * UV are deliberately absent: those are what merging changes.
     *
     * <p>The sprite is identified by its UV rectangle rather than by a
     * sprite id, because the decoder never learns which sprite it decoded.
     * Two faces of the same block texture at the same rotation produce
     * byte-identical UV corners, which is precisely the equality wanted;
     * two faces of the same texture at DIFFERENT rotations produce
     * different ones and correctly refuse to merge, since merging them
     * would rotate half the surface.</p>
     */
    private record Key(QuadFacing facing, int plane, int cutoff, boolean mip,
                       int colorAbgr, int blockLight, int skyLight,
                       int u0, int v0, int u1, int v1) {}

    /**
     * The same key with colour and light struck out: what would match if
     * lighting were not baked into the vertex.
     *
     * <p>This is the owner's question made measurable. They asked whether
     * lighting and the plant colour gradient could be done some other way so
     * that it could all be one mesh. If light and tint were sampled in the
     * shader instead of interpolated from the corners, then two adjacent
     * faces of the same block texture would be byte-identical and would
     * merge, and the uniformity requirement would not apply either, because
     * there would be no per-corner ramp to preserve.</p>
     *
     * <p>Measuring it costs one extra pass over quads that are already
     * decoded. Building it costs a shader rewrite and a light-data upload
     * path. Doing the cheap one first is the whole point.</p>
     */
    private record LitKey(QuadFacing facing, int plane, int cutoff, boolean mip,
                          int u0, int v0, int u1, int v1) {}

    /**
     * The census key for translucent flat water: colour and light struck
     * out (whether the corners agree is reported separately, not gated
     * on), orientation kept for the reason {@link AffineMergeProbe} keeps
     * it, and the plane QUANTISED rather than integral, because a still
     * fluid's surface sits at y = block + 8/9 and would fail the lattice
     * test every solid face passes.
     */
    private record WaterKey(QuadFacing facing, int plane, int cutoff, boolean mip,
                            int u0, int v0, int u1, int v1, int orient) {}

    private GreedyMeshProbe() {
    }

    // ------------------------------------------------------------------
    // Accumulation. Decode runs on ForkJoin build workers, many at once,
    // so every counter here is a LongAdder rather than a long.
    // ------------------------------------------------------------------

    private static final java.util.concurrent.atomic.LongAdder SECTIONS =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder QUADS_IN =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder ELIGIBLE =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder MERGED =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder SKIP_TRANSLUCENT =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder SKIP_UNASSIGNED =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder SKIP_NON_UNIT =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder SKIP_NON_UNIFORM =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder LIT_ELIGIBLE =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder LIT_MERGED =
            new java.util.concurrent.atomic.LongAdder();
    /**
     * What the REAL mesher returns for the same sections, not a model of it.
     *
     * <p>Every figure this probe has published was produced by a second
     * implementation of the sweep living a few hundred lines from the first,
     * and the two had silently diverged: the model extended runs without
     * bound while the mesher clamps them to powers of two. Modelling the
     * thing next to the thing is how that happens, so the headline number is
     * now taken from the thing itself and the model is kept only for the
     * breakdown it can give that the mesher cannot. When the two disagree,
     * the report says so.</p>
     */
    private static final java.util.concurrent.atomic.LongAdder SHIPPED_OUT =
            new java.util.concurrent.atomic.LongAdder();
    /** {@link AffineMergeProbe}: the two ceilings that need no shader work. */
    private static final java.util.concurrent.atomic.LongAdder AFFINE_ELIGIBLE =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder CONSISTENT_MERGED =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder AFFINE_MERGED =
            new java.util.concurrent.atomic.LongAdder();
    /** {@link #waterCensus}: the flat-water shape of the translucent layer. */
    private static final java.util.concurrent.atomic.LongAdder WATER_TOPS =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder WATER_UNDERSIDES =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder WATER_UNIFORM =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder WATER_MERGED =
            new java.util.concurrent.atomic.LongAdder();
    /** Sections that had any translucent quad at all. */
    private static final java.util.concurrent.atomic.LongAdder WET_SECTIONS =
            new java.util.concurrent.atomic.LongAdder();
    /**
     * Sections whose entire translucent prefix is flat horizontal water,
     * tops plus their undersides. Inside such a section any draw order
     * produces the same pixels, so a merge there needs no answer to the
     * resort question at all: the provably-safe population.
     */
    private static final java.util.concurrent.atomic.LongAdder PLANE_PURE_SECTIONS =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.AtomicLong NANOS =
            new java.util.concurrent.atomic.AtomicLong();

    /** Sections between reports. Enough that the number has settled. */
    private static final long REPORT_EVERY = 2000L;

    /**
     * {@code -Dmeshelium.probe.greedy=true}. A property rather than a
     * setting: this measures whether a feature is worth building, so it is
     * for whoever is deciding that, not for players. It also costs real CPU
     * on every section build, which is not something to leave reachable
     * from a menu.
     */
    public static boolean enabled() {
        return Boolean.getBoolean("meshelium.probe.greedy");
    }

    /**
     * Measure this section and fold it into the running total. Safe to call
     * from any thread; returns immediately when the probe is off.
     */
    public static void observe(List<TerrainQuad> quads) {
        if (!enabled() || quads.isEmpty()) {
            return;
        }
        long t0 = System.nanoTime();
        Result r;
        try {
            r = measure(quads);
        } catch (Throwable t) {
            // A probe must never take a section down with it.
            return;
        }
        try {
            SHIPPED_OUT.add(GreedyMesher.merge(quads).size());
            long[] affine = AffineMergeProbe.measure(quads);
            CONSISTENT_MERGED.add(affine[0]);
            AFFINE_MERGED.add(affine[1]);
            AFFINE_ELIGIBLE.add(affine[2]);
            long[] water = waterCensus(quads);
            WATER_TOPS.add(water[0]);
            WATER_UNDERSIDES.add(water[1]);
            WATER_UNIFORM.add(water[2]);
            WATER_MERGED.add(water[3]);
            if (water[4] > 0) {
                WET_SECTIONS.increment();
                // Plane-pure: EVERY translucent quad classified as a flat
                // cell (top or underside), so the whole prefix lies in the
                // water planes and no ordering question survives. Each
                // cell is one translucent quad, so >= is equality here -
                // a single glass block or shore face breaks the property.
                if (water[0] + water[1] >= water[4]) {
                    PLANE_PURE_SECTIONS.increment();
                }
            }
        } catch (Throwable t) {
            return; // same containment as the model pass
        }
        NANOS.addAndGet(System.nanoTime() - t0);
        QUADS_IN.add(r.quadsIn());
        ELIGIBLE.add(r.eligible());
        MERGED.add(r.merged());
        SKIP_TRANSLUCENT.add(r.skippedTranslucent());
        SKIP_UNASSIGNED.add(r.skippedUnassigned());
        SKIP_NON_UNIT.add(r.skippedNonUnit());
        SKIP_NON_UNIFORM.add(r.skippedNonUniform());
        LIT_ELIGIBLE.add(r.litEligible());
        LIT_MERGED.add(r.litMerged());
        SECTIONS.increment();
        if (SECTIONS.sum() % REPORT_EVERY == 0) {
            com.deds.meshelium.fabric.MesheliumClient.LOGGER.info(report());
        }
    }

    /**
     * The whole answer in one line.
     *
     * <p>Reports the saving two ways on purpose. Against ELIGIBLE quads it
     * says how well the merge algorithm did on the geometry it was allowed
     * to touch. Against ALL quads it says what the renderer would actually
     * draw, which is the only number that turns into frames, and it is
     * always the smaller and less flattering of the two.</p>
     */
    public static String report() {
        long quads = QUADS_IN.sum();
        long eligible = ELIGIBLE.sum();
        long merged = MERGED.sum();
        if (quads == 0) {
            return "meshelium greedy probe: no quads seen yet";
        }
        long after = merged + (quads - eligible);
        double ofAll = 100.0 * (quads - after) / quads;
        double ofEligible = eligible == 0 ? 0.0 : 100.0 * (eligible - merged) / eligible;
        long sections = Math.max(1L, SECTIONS.sum());
        long litEligible = LIT_ELIGIBLE.sum();
        long litMerged = LIT_MERGED.sum();
        long litAfter = litMerged + (quads - litEligible);
        double litOfAll = 100.0 * (quads - litAfter) / quads;
        long shipped = SHIPPED_OUT.sum();
        double shippedOfAll = 100.0 * (quads - shipped) / quads;
        long affineEligible = AFFINE_ELIGIBLE.sum();
        long consistentAfter = CONSISTENT_MERGED.sum() + (quads - affineEligible);
        double consistentOfAll = 100.0 * (quads - consistentAfter) / quads;
        long affineAfter = AFFINE_MERGED.sum() + (quads - affineEligible);
        double affineOfAll = 100.0 * (quads - affineAfter) / quads;
        // The model and the mesher should now agree. When they do not, the
        // model has drifted again and every projection below it is suspect,
        // so the line says which one is which rather than quietly averaging.
        String agreement = shipped == after
                ? "model agrees"
                : String.format("MODEL DISAGREES by %d quads - trust the shipped column", after - shipped);
        long waterTops = WATER_TOPS.sum();
        long waterUndersides = WATER_UNDERSIDES.sum();
        long waterCells = waterTops + waterUndersides;
        long waterMerged = WATER_MERGED.sum();
        long translucentQuads = SKIP_TRANSLUCENT.sum();
        double waterOfTranslucent = translucentQuads == 0
                ? 0.0 : 100.0 * (waterCells - waterMerged) / translucentQuads;
        double waterOfAll = 100.0 * (waterCells - waterMerged) / quads;
        return String.format(
                "meshelium greedy probe: %d sections, %d quads -> %d SHIPPED (%.1f%% fewer overall); "
                        + "model says %d (%.1f%% overall, %.1f%% of the %d eligible, %s). "
                        + "Not eligible: translucent %d, unassigned %d, "
                        + "not a unit face %d, corners disagree %d. "
                        + "IF LIGHTING WERE IN THE SHADER: %d eligible -> %d (%.1f%% fewer overall). "
                        + "FREE CEILINGS over %d cells: single-valued lattice -> %d (%.1f%% overall), "
                        + "AFFINE (exact, no shader work) -> %d (%.1f%% overall). "
                        + "TRANSLUCENT FLAT WATER over %d cells (%d tops, %d undersides, %d uniform) "
                        + "-> %d rectangles (%.1f%% fewer of translucent, %.1f%% fewer overall), "
                        + "PLANE-PURE sections %d of %d wet. "
                        + "Cost %.2f ms/section",
                sections, quads, shipped, shippedOfAll,
                after, ofAll, ofEligible, eligible, agreement,
                translucentQuads, SKIP_UNASSIGNED.sum(),
                SKIP_NON_UNIT.sum(), SKIP_NON_UNIFORM.sum(),
                litEligible, litAfter, litOfAll,
                affineEligible, consistentAfter, consistentOfAll, affineAfter, affineOfAll,
                waterCells, waterTops, waterUndersides, WATER_UNIFORM.sum(),
                waterMerged, waterOfTranslucent, waterOfAll,
                PLANE_PURE_SECTIONS.sum(), WET_SECTIONS.sum(),
                NANOS.get() / 1.0e6 / sections);
    }

    public static Result measure(List<TerrainQuad> quads) {
        int translucent = 0;
        int unassigned = 0;
        int nonUnit = 0;
        int nonUniform = 0;
        Map<Key, List<long[]>> cells = new HashMap<>();

        for (TerrainQuad q : quads) {
            if (q.translucent()) {
                translucent++;
                continue;
            }
            if (q.facing() == QuadFacing.UNASSIGNED) {
                unassigned++;
                continue;
            }
            if (!uniform(q)) {
                nonUniform++;
                continue;
            }
            long[] cell = unitCell(q);
            if (cell == null) {
                nonUnit++;
                continue;
            }
            TerrainVertex a = q.v0();
            Key key = new Key(q.facing(), (int) cell[2], q.alphaCutoffIndex(), q.mip(),
                    a.colorAbgr(), a.blockLight(), a.skyLight(),
                    quant(minU(q)), quant(minV(q)), quant(maxU(q)), quant(maxV(q)));
            cells.computeIfAbsent(key, k -> new ArrayList<>()).add(cell);
        }

        int eligible = 0;
        int merged = 0;
        for (List<long[]> group : cells.values()) {
            eligible += group.size();
            merged += greedyRectangles(group);
        }

        // Second pass: the same terrain, if lighting lived in the shader.
        // Colour and light leave the key, and the uniformity test that
        // rejected two thirds of everything no longer applies.
        Map<LitKey, List<long[]>> litCells = new HashMap<>();
        for (TerrainQuad q : quads) {
            if (q.translucent() || q.facing() == QuadFacing.UNASSIGNED) {
                continue;
            }
            long[] cell = unitCell(q);
            if (cell == null) {
                continue;
            }
            LitKey key = new LitKey(q.facing(), (int) cell[2], q.alphaCutoffIndex(), q.mip(),
                    quant(minU(q)), quant(minV(q)), quant(maxU(q)), quant(maxV(q)));
            litCells.computeIfAbsent(key, k -> new ArrayList<>()).add(cell);
        }
        int litEligible = 0;
        int litMerged = 0;
        for (List<long[]> group : litCells.values()) {
            litEligible += group.size();
            litMerged += greedyRectangles(group);
        }

        return new Result(quads.size(), eligible, merged,
                translucent, unassigned, nonUnit, nonUniform, litEligible, litMerged);
    }

    /**
     * The flat-water shape of the translucent layer, measured rather than
     * assumed.
     *
     * <p>Nothing here merges anything, and translucent stays excluded from
     * every pass above, because draw order inside that layer is
     * load-bearing. The one geometry where it provably is not is a set of
     * disjoint quads sharing a single plane: any camera ray meets the plane
     * once, so at most one of them covers any pixel and their relative
     * order cannot change the image. Open water is exactly that shape -
     * sheets of horizontal unit faces at y = block + 8/9 (vanilla's
     * MAX_FLUID_HEIGHT), with back-faces at the same height - and this
     * counts how much of the layer has it, before anyone touches the sort
     * path on a hunch.</p>
     *
     * <p>Returns {tops, undersides, uniform, rectangles, translucent}: the
     * flat POS_Y and NEG_Y cell counts, the subset whose corners pass
     * {@link #uniform}, what the flat cells collapse into under the same
     * clamped sweep as everything else, and all translucent quads seen.</p>
     */
    private static long[] waterCensus(List<TerrainQuad> quads) {
        long translucent = 0;
        long tops = 0;
        long undersides = 0;
        long uniformCells = 0;
        Map<WaterKey, List<long[]>> groups = new HashMap<>();
        for (TerrainQuad q : quads) {
            if (!q.translucent()) {
                continue;
            }
            translucent++;
            if (q.facing() != QuadFacing.POS_Y && q.facing() != QuadFacing.NEG_Y) {
                continue;
            }
            long[] cell = flatCell(q);
            if (cell == null) {
                continue;
            }
            if (q.facing() == QuadFacing.POS_Y) {
                tops++;
            } else {
                undersides++;
            }
            if (uniform(q)) {
                uniformCells++;
            }
            WaterKey key = new WaterKey(q.facing(), (int) cell[2], q.alphaCutoffIndex(), q.mip(),
                    quant(minU(q)), quant(minV(q)), quant(maxU(q)), quant(maxV(q)),
                    orientation(q));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(cell);
        }
        long rectangles = 0;
        for (List<long[]> group : groups.values()) {
            rectangles += greedyRectangles(group);
        }
        return new long[] {tops, undersides, uniformCells, rectangles, translucent};
    }

    /**
     * Count the rectangles a set of unit cells collapses into.
     *
     * <p>Textbook greedy: sweep in row-major order, extend right while the
     * row continues, then extend down while the WHOLE span continues. The
     * vertical pass is not optional. Dropping it turns a measured 33
     * percent into 23, because although most rectangles end up 1x1, the
     * few tall ones carry most of the saving.</p>
     *
     * <p><b>Runs are clamped exactly as {@link GreedyMesher} clamps them</b>,
     * and until 2026-08-15 they were not. That omission made every number
     * this probe ever printed an upper bound the encoder could not reach: a
     * run of 15 is not one rectangle, it is 8 + 4 + 2 + 1. The error is
     * worst precisely where the merge is best, so it inflated the
     * shader-lighting projection more than the baseline it was compared
     * against. A model of the real thing has to clamp where the real thing
     * clamps.</p>
     */
    private static int greedyRectangles(List<long[]> cells) {
        // Cells are (a, b, plane). Hash the 2D coordinates for O(1) probing.
        java.util.HashSet<Long> grid = new java.util.HashSet<>(cells.size() * 2);
        for (long[] c : cells) {
            grid.add(pack(c[0], c[1]));
        }
        java.util.HashSet<Long> used = new java.util.HashSet<>(cells.size() * 2);
        // Deterministic order, so the answer does not depend on decode order.
        List<long[]> sorted = new ArrayList<>(cells);
        sorted.sort((x, y) -> x[1] != y[1] ? Long.compare(x[1], y[1]) : Long.compare(x[0], y[0]));

        int rectangles = 0;
        for (long[] c : sorted) {
            long a = c[0];
            long b = c[1];
            if (used.contains(pack(a, b))) {
                continue;
            }
            int width = 1;
            while (width < TerrainVertexCodec.MAX_REPEAT
                    && grid.contains(pack(a + width, b)) && !used.contains(pack(a + width, b))) {
                width++;
            }
            width = TerrainVertexCodec.largestRepeat(width);
            int height = 1;
            outer:
            while (height < TerrainVertexCodec.MAX_REPEAT) {
                for (int i = 0; i < width; i++) {
                    long probe = pack(a + i, b + height);
                    if (!grid.contains(probe) || used.contains(probe)) {
                        break outer;
                    }
                }
                height++;
            }
            height = TerrainVertexCodec.largestRepeat(height);
            for (int j = 0; j < height; j++) {
                for (int i = 0; i < width; i++) {
                    used.add(pack(a + i, b + j));
                }
            }
            rectangles++;
        }
        return rectangles;
    }

    private static long pack(long a, long b) {
        return (a & 0xFFFFFFFFL) << 32 | (b & 0xFFFFFFFFL);
    }

    /** True when all four corners agree, so the quad is a flat tile. */
    private static boolean uniform(TerrainQuad q) {
        TerrainVertex a = q.v0();
        for (int i = 1; i < 4; i++) {
            TerrainVertex o = q.vertex(i);
            if (o.colorAbgr() != a.colorAbgr()
                    || o.blockLight() != a.blockLight()
                    || o.skyLight() != a.skyLight()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The block-grid cell of a 1x1 axis-aligned face, or null if the quad
     * is not one.
     *
     * <p>Returns {a, b, plane}: the two in-plane integer coordinates and
     * the constant one. Positions arrive as floats in Sodium's [-8, +24)
     * section space, so a face lands on integers and its extent is exactly
     * one. Anything else, a slab, a stair, a fence, a rotated model, fails
     * here and is counted rather than merged.</p>
     */
    private static long[] unitCell(TerrainQuad q) {
        int axis = switch (q.facing()) {
            case POS_X, NEG_X -> 0;
            case POS_Y, NEG_Y -> 1;
            case POS_Z, NEG_Z -> 2;
            case UNASSIGNED -> -1;
        };
        if (axis < 0) {
            return null;
        }
        float planeF = component(q.v0(), axis);
        float minA = Float.MAX_VALUE;
        float maxA = -Float.MAX_VALUE;
        float minB = Float.MAX_VALUE;
        float maxB = -Float.MAX_VALUE;
        int axisA = axis == 0 ? 1 : 0;
        int axisB = axis == 2 ? 1 : 2;
        for (int i = 0; i < 4; i++) {
            TerrainVertex v = q.vertex(i);
            if (!near(component(v, axis), planeF)) {
                return null; // not planar on its own facing axis
            }
            float ca = component(v, axisA);
            float cb = component(v, axisB);
            minA = Math.min(minA, ca);
            maxA = Math.max(maxA, ca);
            minB = Math.min(minB, cb);
            maxB = Math.max(maxB, cb);
        }
        if (!isUnitSpan(minA, maxA) || !isUnitSpan(minB, maxB) || !isIntegral(planeF)) {
            return null;
        }
        return new long[] {Math.round(minA), Math.round(minB), Math.round(planeF)};
    }

    /**
     * The water variant of {@link #unitCell}: a 1x1 horizontal face at ANY
     * height, its plane quantised instead of required integral.
     *
     * <p>A still fluid's surface sits at y = block + 8/9, so the integral
     * plane test every solid face passes would reject every water top. The
     * in-plane extent must still be a unit cell on integer coordinates -
     * shore and flow faces, whose corners sit at different heights, fail
     * the planarity test and are counted only as translucent.</p>
     */
    private static long[] flatCell(TerrainQuad q) {
        float planeF = q.v0().y();
        float minA = Float.MAX_VALUE;
        float maxA = -Float.MAX_VALUE;
        float minB = Float.MAX_VALUE;
        float maxB = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            TerrainVertex v = q.vertex(i);
            if (!near(v.y(), planeF)) {
                return null; // corners at different heights: not a flat sheet
            }
            minA = Math.min(minA, v.x());
            maxA = Math.max(maxA, v.x());
            minB = Math.min(minB, v.z());
            maxB = Math.max(maxB, v.z());
        }
        if (!isUnitSpan(minA, maxA) || !isUnitSpan(minB, maxB)) {
            return null;
        }
        return new long[] {Math.round(minA), Math.round(minB), quant(planeF)};
    }

    /**
     * {@link AffineMergeProbe}'s orientation bits, specialised to the
     * horizontal plane the census lives in. Kept in the key for the same
     * reason it keeps them: a rotated variant shares its atlas rectangle,
     * and merging across the rotation would rotate half the surface.
     */
    private static int orientation(TerrainQuad q) {
        TerrainVertex origin = q.v0();
        float a0 = origin.x();
        float b0 = origin.z();
        TerrainVertex alongA = null;
        TerrainVertex alongB = null;
        for (int i = 1; i < 4; i++) {
            TerrainVertex o = q.vertex(i);
            boolean sameA = near(o.x(), a0);
            boolean sameB = near(o.z(), b0);
            if (!sameA && sameB) {
                alongA = o;
            } else if (sameA && !sameB) {
                alongB = o;
            }
        }
        if (alongA == null || alongB == null) {
            return -1;
        }
        float signA = alongA.x() > a0 ? 1.0f : -1.0f;
        float signB = alongB.z() > b0 ? 1.0f : -1.0f;
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

    private static float component(TerrainVertex v, int axis) {
        return axis == 0 ? v.x() : axis == 1 ? v.y() : v.z();
    }

    // A face lands on the block lattice; the tolerance only absorbs the
    // float round-trip through vanilla's buffer, never a real offset.
    private static final float EPS = 1.0e-4f;

    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= EPS;
    }

    private static boolean isIntegral(float f) {
        return Math.abs(f - Math.round(f)) <= EPS;
    }

    private static boolean isUnitSpan(float min, float max) {
        return isIntegral(min) && Math.abs((max - min) - 1.0f) <= EPS;
    }

    /** UV to a stable integer, at the codec's own 1/32768 resolution. */
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
