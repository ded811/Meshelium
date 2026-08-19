/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.terrain.host;

import com.deds.meshelium.terrain.EncodedSectionMesh;
import com.deds.meshelium.terrain.QuadFacing;
import com.deds.meshelium.terrain.SectionMeshEncoder;
import com.deds.meshelium.terrain.TerrainQuad;
import com.deds.meshelium.terrain.TerrainVertex;

import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The two build-thread ends of the wave-3b tap (section-build doc Q3.1):
 * re-encode at {@code SectionCompiler.compile} RETURN, re-key to the
 * {@code CompiledSectionMesh} at its ctor TAIL. Both hooks run on the SAME
 * thread back to back ({@code CompileTask.doTask} bytecode: compile at
 * ip 100, the ctor at ip 153-161, no thread hop; the compileSync path is
 * the same method inlined on the render thread) — so the park between them
 * is a {@link ThreadLocal}, not a shared map: no contention, no cross-talk
 * when two overlapping doTasks build the SAME section on two workers (a
 * cancelled task can still be mid-flight while its replacement runs), and
 * a park orphaned by an exception is simply overwritten by the thread's
 * next compile. The {@code Results} identity check makes a stale park
 * detectable instead of mis-keyed. This implements the recon's
 * "park keyed by the Results object" (Q3.1) with the keying collapsed
 * onto the thread.
 *
 * <p><b>Resorts never arrive here by construction:</b>
 * {@code ResortTransparencyTask.doTask} calls neither
 * {@code SectionCompiler.compile} nor the {@code CompiledSectionMesh}
 * constructor (bytecode-verified: it only reads the LIVE mesh and hands
 * new index bytes to {@code addSectionBuffersToUberBuffer}) — so a resort
 * cannot trigger a re-encode even in principle.</p>
 *
 * <p>Callers (the mixins) gate on {@code MesheliumGate} BEFORE touching this
 * class, so it never loads on the OpenGL path's hot loop; this class in
 * turn imports no LWJGL.</p>
 */
public final class SectionBuildTap {

    private record Parked(SectionCompiler.Results results, int sx, int sy, int sz,
            EncodedSectionMesh encoded, int[] translucentOrder, byte builtTier) {}

    private static final ThreadLocal<Parked> PARKED = new ThreadLocal<>();

    // ------------------------------------------------------------------
    // The leaf-detail tiers. Strictly increasing aggressiveness — the
    // residency walker's compare (builtTier greater than what the current
    // distance would get) leans on the order, so never renumber.
    // ------------------------------------------------------------------

    /** Full detail: nothing filtered, nothing rewritten. */
    static final byte TIER_NONE = 0;
    /**
     * Smart Leaves Beyond: opposite-facing coplanar cutout pairs dropped
     * ({@link #filterCutoutInteriorPairs}); the outer faces keep their
     * see-through material, so the look survives.
     */
    static final byte TIER_SMART = 1;
    /**
     * Solid Leaves Beyond: Smart's pair filter AND every surviving
     * full-block cutout face rewritten to the solid material
     * ({@link #solidifyCutouts}) — the way Fast graphics draws leaves
     * everywhere, made distance-gated. Crosses, insets and decals keep
     * their cutout look (the rewrite's eligibility rules).
     */
    static final byte TIER_SOLID = 2;

    // ------------------------------------------------------------------
    // Smart/Solid Leaves Beyond: the camera section the build workers test
    // distance against. The RENDER THREAD publishes it once per frame
    // (TerrainDrawer.beginFrame calls publishCameraSection); the field
    // lives HERE rather than on the drawer because this class and
    // TerrainResidency must never reference the LWJGL-importing vk side
    // (both files' class-loading contracts), while the drawer already
    // reaches into this package freely. One packed volatile long is the
    // whole protocol: a torn read is impossible (single 64-bit volatile)
    // and a frame of staleness only moves the ring by however far the
    // camera travels in a frame, which the ±1-chunk hysteresis absorbs.
    // ------------------------------------------------------------------

    /** "No frame has published a camera yet" — the filter stays off. */
    public static final long CAMERA_SECTION_UNKNOWN = Long.MIN_VALUE;

    private static volatile long cameraSectionXZ = CAMERA_SECTION_UNKNOWN;

    private SectionBuildTap() {}

    /** Render thread, once per frame: the camera's section X/Z, packed. */
    public static void publishCameraSection(int sectionX, int sectionZ) {
        cameraSectionXZ = ((long) sectionX << 32) | (sectionZ & 0xFFFFFFFFL);
    }

    /** Any thread: the last published camera section, or the sentinel. */
    public static long cameraSectionXZ() {
        return cameraSectionXZ;
    }

    /**
     * XZ distance in chunks between a section and the packed camera
     * section, ceiled — the CPU twin of the shader culls' Euclidean
     * distance, on the same integer section lattice the walker uses, so
     * the build-side gate and the restore-side gate cannot disagree about
     * what "beyond N chunks" means.
     */
    static int chunkDistanceXZ(long packedCamera, int sx, int sz) {
        long dx = sx - (packedCamera >> 32);
        long dz = sz - (int) packedCamera;
        return (int) Math.ceil(Math.sqrt((double) (dx * dx + dz * dz)));
    }

    /**
     * {@code SectionCompiler.compile} RETURN, on the build thread: decode
     * the 28-byte BLOCK vertices of every rendered layer, derive facings,
     * order the translucent prefix by vanilla's own build-time distance
     * sort, encode to the 16-byte format, and park the result for the
     * ctor-TAIL re-key. Empty results (vanilla never uploads those either,
     * doTask ip 166-249) and 0-quad decodes park nothing.
     */
    public static void onCompileReturn(SectionPos pos, SectionCompiler.Results results) {
        // 2026-08-18 attribution wave: count every EXECUTED compile task
        // (empties included; resorts never reach the tap, so they are
        // excluded structurally) — the build-storm series' raw feed.
        com.deds.meshelium.MesheliumCpuStages.noteTapCompile();
        PARKED.remove(); // drop any orphan from an earlier failed build
        if (results == null || results.renderedLayers.isEmpty()) {
            // Wave-11: an EMPTY compile is still a statement about the
            // world — the section has no geometry NOW — and it never
            // reaches the upload path (vanilla's empty short-circuit,
            // doTask ip 166-249), so the slot-steal supersede can never
            // fire for it. Signal the position so a RETAINED copy there
            // (rd shrink followed by a dig-out, the ghost-terrain edge)
            // dies with the world edit. No-op when nothing is retained.
            if (results != null) {
                TerrainResidency.onSectionCompiledEmpty(pos.x(), pos.y(), pos.z());
            }
            return;
        }
        try {
            VanillaMeshDecoder.DecodedSection decoded =
                    VanillaMeshDecoder.decode(results.renderedLayers);
            if (decoded.skippedLayers() > 0) {
                TerrainResidency.countDecoderSkips(decoded.skippedLayers());
            }
            if (decoded.quads().isEmpty()) {
                TerrainResidency.onSectionCompiledEmpty(pos.x(), pos.y(), pos.z()); // wave-11
                return;
            }
            // Measurement only, and off unless -Dmeshelium.probe.greedy is
            // set. This is the exact point a real merge pass would go, so
            // measuring here measures the thing that would actually be
            // built rather than a model of it. Observes the UNFILTERED
            // decode: the census measures what vanilla emits, not what the
            // leaf-tier transforms left of it.
            com.deds.meshelium.terrain.GreedyMeshProbe.observe(decoded.quads());
            // Smart/Solid Leaves Beyond (two config sliders, 0 = off).
            // The section's tier is decided ONCE per compile, from one
            // camera read: Solid beyond (solidRing + 1), else Smart beyond
            // (smartRing + 1), else none — each gate one past its ring, so
            // the residency walker's restore at (ring − 1) leaves a dead
            // band and the two can never chase each other. Solid IMPLIES
            // Smart: the pair filter runs for both tiers, and only Solid
            // then rewrites the survivors' material. Both transforms run
            // BEFORE the merge and BEFORE the encoder on purpose: the
            // encoder derives every downstream count (facing buckets,
            // translucent prefix, bucket starts, AABB) from the list it is
            // handed, so filtering here means no bookkeeping anywhere is
            // computed from quads that no longer exist. The merge also has
            // to see the transformed list — a merged run is no longer a
            // unit face and would hide its interior pairs from the
            // matcher, and solidified quads must be opaque BEFORE the
            // merge groups by material. Filter strictly before solidify:
            // the pair matcher keys on the cutout material, so a rewritten
            // list would hide every pair from it.
            List<TerrainQuad> toEncode = decoded.quads();
            byte builtTier = TIER_NONE;
            int smartRing = com.deds.meshelium.MesheliumConfig.smartLeavesChunks();
            int solidRing = com.deds.meshelium.MesheliumConfig.solidLeavesChunks();
            if (smartRing > 0 || solidRing > 0) {
                long camera = cameraSectionXZ;
                if (camera != CAMERA_SECTION_UNKNOWN) {
                    int dist = chunkDistanceXZ(camera, pos.x(), pos.z());
                    byte tier = solidRing > 0 && dist > solidRing + 1 ? TIER_SOLID
                            : smartRing > 0 && dist > smartRing + 1 ? TIER_SMART
                            : TIER_NONE;
                    if (tier != TIER_NONE) {
                        List<TerrainQuad> filtered = filterCutoutInteriorPairs(toEncode);
                        // A section that is NOTHING but interior pairs
                        // (buried inside a giant canopy) must keep its
                        // quads: an empty encode is a deleted section, and
                        // a deleted section leaves no Resident for the
                        // walker to restore.
                        if (filtered != toEncode && !filtered.isEmpty()) {
                            toEncode = filtered;
                            builtTier = TIER_SMART;
                        }
                        if (tier == TIER_SOLID) {
                            List<TerrainQuad> solidified = solidifyCutouts(toEncode);
                            if (solidified != toEncode) {
                                toEncode = solidified;
                                builtTier = TIER_SOLID;
                            }
                        }
                        // The stamp records what actually CHANGED, not what
                        // was attempted: a Solid-range section whose every
                        // cutout left as a pair is byte-identical to its
                        // Smart build, and stamping it Smart spares the
                        // walker one no-op rebuild on approach; one with
                        // nothing to filter or rewrite stays TIER_NONE and
                        // the walker never touches it.
                    }
                }
            }
            // The merge, when it is on. A pure list transform, on this build
            // worker, before anything is packed: the encoder, the arena and
            // the shaders see nothing but a shorter list of ordinary quads
            // carrying a tile repeat.
            if (com.deds.meshelium.MesheliumConfig.greedyMeshingEnabled()) {
                toEncode = com.deds.meshelium.terrain.GreedyMesher.merge(toEncode);
            }
            EncodedSectionMesh encoded = SectionMeshEncoder.encode(toEncode);
            PARKED.set(new Parked(results, pos.x(), pos.y(), pos.z(), encoded,
                    decoded.translucentOrder(), builtTier));
        } catch (Throwable t) {
            // e.g. a modded section beyond the u16-per-bucket quad budget —
            // drop THIS section, never the frame or the worker.
            TerrainResidency.countEncodeFailure(t);
        }
    }

    /**
     * {@code CompiledSectionMesh.<init>} TAIL, same thread: re-key the
     * parked encoding from the Results object to the mesh identity —
     * exactly the key vanilla's uber-buffer allocationMap uses (Q3.1), so
     * store and vanilla share lifetime semantics by construction.
     */
    public static void onMeshConstructed(Object mesh, SectionCompiler.Results results) {
        Parked parked = PARKED.get();
        if (parked == null) {
            return; // empty section, 0-quad decode, or tap disabled mid-build
        }
        PARKED.remove();
        if (parked.results() != results) {
            TerrainResidency.countStalePark();
            return;
        }
        TerrainResidency.enqueueUpload(mesh, parked.sx(), parked.sy(), parked.sz(),
                parked.encoded(), parked.translucentOrder(), parked.builtTier());
    }

    // ------------------------------------------------------------------
    // Smart Leaves Beyond — the pair filter (Solid runs it too)
    // ------------------------------------------------------------------

    /**
     * The census key for one unit cell boundary: facing axis, the plane
     * coordinate along it, and the two in-plane cell coordinates. Facing
     * SIGN deliberately absent — the pair filter matches a POS face
     * against the NEG face on the same boundary, and the solidify
     * eligibility test wants a decal flagged whichever way the opaque
     * face under it points — and so are material and UV, because
     * Fast-style opacity culls the pair whatever sprites it carries
     * ({@code GreedyMeshProbe.BoundaryKey}, verbatim).
     */
    private record BoundaryKey(int axis, int plane, int a, int b) {}

    /** The sign-free boundary key of a face {@link #unitCell} accepted. */
    private static BoundaryKey boundaryKey(QuadFacing facing, long[] cell) {
        int axis = switch (facing) {
            case POS_X, NEG_X -> 0;
            case POS_Y, NEG_Y -> 1;
            case POS_Z, NEG_Z -> 2;
            case UNASSIGNED -> -1; // unreachable: unitCell rejected it
        };
        return new BoundaryKey(axis, (int) cell[2], (int) cell[0], (int) cell[1]);
    }

    /**
     * Remove BOTH quads of every opposite-facing coplanar cutout pair —
     * the leaf-against-leaf interior faces Fast graphics never meshes,
     * which the fast-graphics census measured at 50-52% of forest cutout
     * quads (docs/PERFORMANCE.md). This is
     * {@code GreedyMeshProbe.cutoutInteriorCensus}'s exact matcher turned
     * from a counter into a filter: cutout means a nonzero alpha cutoff on
     * a non-translucent quad (the decoder stamps SOLID 0 / TRANSLUCENT 1 /
     * CUTOUT 2, so the translucency flag carries half the test), only
     * axis-facing 1x1 faces on the block lattice can be interior (crosses
     * are UNASSIGNED and fail {@link #unitCell}), and each boundary
     * greedily pairs one POS with one NEG — min(pos, neg) pairs per
     * bucket, exactly the census's count, so stacked duplicates pair off
     * correctly and any odd face out survives. The matcher's geometry
     * helpers are duplicated from the probe rather than shared because
     * the probe is a measurement tool whose privates must stay free to
     * drift with what is being measured; THIS copy is pinned by
     * MesheliumTerrainDataTest instead.
     *
     * <p>Pure list transform, worker thread, no section-boundary
     * knowledge: pairs straddling two sections are missed exactly as the
     * census missed them, so the census's saving is this filter's floor.
     * Translucent quads are structurally untouchable here, which is what
     * keeps the decoder's translucentOrder valid over the filtered list.
     * Returns the INPUT LIST ITSELF when nothing pairs, so the no-leaves
     * common case costs one scan and no copy.</p>
     */
    public static List<TerrainQuad> filterCutoutInteriorPairs(List<TerrainQuad> quads) {
        Map<BoundaryKey, ArrayDeque<Integer>> unpairedPos = new HashMap<>();
        Map<BoundaryKey, ArrayDeque<Integer>> unpairedNeg = new HashMap<>();
        boolean[] removed = new boolean[quads.size()];
        int removedCount = 0;
        for (int i = 0; i < quads.size(); i++) {
            TerrainQuad q = quads.get(i);
            if (q.translucent() || q.alphaCutoffIndex() == 0) {
                continue; // not cutout material
            }
            long[] cell = unitCell(q);
            if (cell == null) {
                continue; // crosses and non-unit faces: nothing to pair
            }
            boolean positive = q.facing() == QuadFacing.POS_X
                    || q.facing() == QuadFacing.POS_Y
                    || q.facing() == QuadFacing.POS_Z;
            BoundaryKey key = boundaryKey(q.facing(), cell);
            ArrayDeque<Integer> opposite = (positive ? unpairedNeg : unpairedPos).get(key);
            if (opposite != null && !opposite.isEmpty()) {
                removed[opposite.pollFirst()] = true;
                removed[i] = true;
                removedCount += 2;
            } else {
                (positive ? unpairedPos : unpairedNeg)
                        .computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(i);
            }
        }
        if (removedCount == 0) {
            return quads;
        }
        List<TerrainQuad> out = new ArrayList<>(quads.size() - removedCount);
        for (int i = 0; i < quads.size(); i++) {
            if (!removed[i]) {
                out.add(quads.get(i));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Solid Leaves Beyond — the material rewrite
    // ------------------------------------------------------------------

    /**
     * Rewrite every remaining full-block cutout face to the solid
     * material — alpha cutoff 0, everything else carried over verbatim
     * (facing, mip, vertices, and a merged quad's tile repeat). This is
     * the whole Solid tier once the pair filter has run: with the cutoff
     * at 0 the fragment shader discards nothing, so a distant leaf face
     * covers its pixels the way Fast graphics covers them everywhere,
     * and distant woods become occluders.
     *
     * <p>Eligibility is GEOMETRIC, the pair filter's own discipline —
     * "cutout material" alone is not license to fill pixels, because the
     * cutout pass carries plenty of geometry whose transparent texels are
     * structural. A quad is rewritten only if ALL three hold:</p>
     *
     * <ul>
     * <li><b>axis-aligned facing</b> — crosses (flowers, tall grass,
     * kelp) are UNASSIGNED, and filling one paints its whole diagonal
     * rectangle;</li>
     * <li><b>{@link #unitCell} passes</b> — vines, cactus sides and
     * panes sit inset from the lattice or span less than a full face,
     * and filling them stretches sprite pixels over geometry the sprite
     * never covered;</li>
     * <li><b>no opaque face on its boundary</b> — a grass or snow side
     * overlay is a decal exactly coplanar with the solid face it
     * decorates, and filling the decal z-fights that face as a stretched
     * smear of the overlay's edge texels.</li>
     * </ul>
     *
     * <p>A quad failing any test passes through UNCHANGED — still drawn
     * cutout, so it looks right, it just is not solidified. The rule-3
     * set is built by {@link #solidBoundaryKeys} in one pass over the
     * list, lazily on the FIRST geometrically eligible cutout, so the
     * whole transform stays O(quads) and a section with no cutouts (or
     * only ineligible ones) never allocates it.</p>
     *
     * <p>MUST run AFTER {@link #filterCutoutInteriorPairs}: the pair
     * matcher keys on the cutout material this rewrite erases. Runs
     * BEFORE the merge for the same reason the filter does — the merge
     * groups by material, and solidified quads should merge as the
     * solids they now are (and a merged run is no longer a unit face,
     * which would blind every geometric test here).</p>
     *
     * <p>Counts stay consistent by construction: nothing is added or
     * removed, translucent quads are structurally untouchable (the guard
     * tests {@code translucent()} first), so the encoder's facing
     * buckets, translucent prefix and AABB come out identical to the
     * unrewritten list's — only the packed material bits differ. Pure
     * list transform, worker thread; returns the INPUT LIST ITSELF when
     * nothing is rewritten, so the no-leaves common case costs one scan
     * and no copy ({@code filterCutoutInteriorPairs}'s contract, kept
     * deliberately).</p>
     */
    public static List<TerrainQuad> solidifyCutouts(List<TerrainQuad> quads) {
        Set<BoundaryKey> solidBoundaries = null;
        List<TerrainQuad> out = null;
        for (int i = 0; i < quads.size(); i++) {
            TerrainQuad q = quads.get(i);
            if (q.translucent() || q.alphaCutoffIndex() == 0) {
                continue; // not cutout material: carried over untouched
            }
            long[] cell = unitCell(q);
            if (cell == null) {
                continue; // crosses, insets, non-unit faces: the cutout look is structural
            }
            if (solidBoundaries == null) {
                solidBoundaries = solidBoundaryKeys(quads);
            }
            if (solidBoundaries.contains(boundaryKey(q.facing(), cell))) {
                continue; // a decal on an opaque face (grass/snow side overlays)
            }
            if (out == null) {
                out = new ArrayList<>(quads);
            }
            out.set(i, new TerrainQuad(q.facing(), false, 0, q.mip(),
                    q.v0(), q.v1(), q.v2(), q.v3(), q.repeatU(), q.repeatV()));
        }
        return out == null ? quads : out;
    }

    /**
     * Every boundary an OPAQUE unit face occupies, keyed sign-free — the
     * set {@link #solidifyCutouts}'s rule 3 tests decals against. One
     * pass, solid material only (cutoff 0, not translucent), and only
     * faces {@link #unitCell} accepts: a decal's host face is by nature
     * a full block face, so an inset or non-unit solid can never be one
     * and has no business suppressing a rewrite.
     */
    private static Set<BoundaryKey> solidBoundaryKeys(List<TerrainQuad> quads) {
        Set<BoundaryKey> keys = new HashSet<>();
        for (int i = 0; i < quads.size(); i++) {
            TerrainQuad q = quads.get(i);
            if (q.translucent() || q.alphaCutoffIndex() != 0) {
                continue; // not solid material
            }
            long[] cell = unitCell(q);
            if (cell != null) {
                keys.add(boundaryKey(q.facing(), cell));
            }
        }
        return keys;
    }

    /**
     * The block-grid cell of a 1x1 axis-aligned face, or null if the quad
     * is not one. Returns {a, b, plane}: the two in-plane integer
     * coordinates and the constant one ({@code GreedyMeshProbe.unitCell},
     * duplicated — see {@link #filterCutoutInteriorPairs} for why).
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
}
