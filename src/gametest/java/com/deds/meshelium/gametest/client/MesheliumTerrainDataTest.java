/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
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
import com.deds.meshelium.terrain.host.SectionBuildTap;

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
        greedyMergeTileAxes();
        greedyMergeCornerShading();
        leafTierFilters();
        resetCoversEveryField();
        arenaBasics();
        arenaPendingRelease();
        arenaExhaustionAndStats();
        arenaCoalescingPathological();
        arenaChurnLeakCounters();
        translucentPrefixPermutation();
    }

    // ==================================================================
    // Reset To Defaults must cover every field, enforced by reflection
    // ==================================================================

    /**
     * The same bug has now shipped twice: a config field gains a UI row,
     * {@code resetToDefaults()} is not taught about it, and the reset
     * button quietly leaves that one setting behind (suppressVanillaUploads
     * first, then greedyMeshing). Listing fields by hand is exactly the
     * pattern that keeps failing, so this walks EVERY public instance field
     * by reflection: perturb it, reset, and require the default back.
     *
     * <p>Fields the reset deliberately preserves go in the skip set BY
     * NAME, so adding a config field makes this test fail until the author
     * either resets it or consciously exempts it. That converts the
     * forgotten-field bug from silent to compile-adjacent.</p>
     */
    private static void resetCoversEveryField() {
        java.util.Set<String> deliberatelyKept = java.util.Set.of(
                // Once-per-install notice latches: a reset of SETTINGS must
                // not re-arm popups the player has already dismissed (the
                // popup row re-arms them itself, deliberately).
                "noMeshShaderNoticeShown", "vulkanFailedNoticeShown",
                // The migration cursor is history, not a setting.
                "configVersion");
        com.deds.meshelium.MesheliumConfig config = new com.deds.meshelium.MesheliumConfig();
        com.deds.meshelium.MesheliumConfig defaults = new com.deds.meshelium.MesheliumConfig();
        java.util.List<String> stuck = new ArrayList<>();
        try {
            for (java.lang.reflect.Field f : com.deds.meshelium.MesheliumConfig.class.getFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || deliberatelyKept.contains(f.getName())) {
                    continue;
                }
                perturb(f, config);
            }
            config.resetToDefaults();
            for (java.lang.reflect.Field f : com.deds.meshelium.MesheliumConfig.class.getFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || deliberatelyKept.contains(f.getName())) {
                    continue;
                }
                Object got = f.get(config);
                Object want = f.get(defaults);
                if (!java.util.Objects.equals(got, want)) {
                    stuck.add(f.getName() + " (left at " + got + ", default " + want + ")");
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reset reflection walk failed", e);
        }
        check(stuck.isEmpty(), "Reset To Defaults left row-backed fields behind: " + stuck
                + " - add them to resetToDefaults() or, deliberately, to this test's skip set");
    }

    /** Set a field to something that is provably not its default. */
    private static void perturb(java.lang.reflect.Field f,
            com.deds.meshelium.MesheliumConfig config) throws ReflectiveOperationException {
        Class<?> t = f.getType();
        if (t == boolean.class) {
            f.setBoolean(config, !f.getBoolean(config));
        } else if (t == int.class) {
            f.setInt(config, f.getInt(config) + 7);
        } else if (t.isEnum()) {
            Object[] values = t.getEnumConstants();
            int i = java.util.Arrays.asList(values).indexOf(f.get(config));
            f.set(config, values[(i + 1) % values.length]);
        } else {
            throw new AssertionError("config field " + f.getName() + " has type " + t
                    + " this walk cannot perturb - teach perturb() about it");
        }
    }

    // ==================================================================
    // Greedy meshing: the tile counts must land on the SPRITE's axes
    // ==================================================================

    /**
     * A run counted along a POSITION axis is not necessarily a run along the
     * sprite's U axis, and on east and west faces it is not.
     *
     * <p>Vanilla's own face UVs take U from Z and V from Y there
     * ({@code FaceBakery.defaultFaceUV}), while the mesher's two in-plane
     * axes for an X-facing quad are Y then Z. Hand a four-tall wall merge to
     * the shader as {@code repeatU = 4} and it repeats the sprite four times
     * SIDEWAYS across one block of Z and stretches it once vertically over
     * four blocks of Y.</p>
     *
     * <p>This is the test the harness could not have caught by itself: its
     * world is superflat, so every merge it has ever made was on a top face,
     * which is the one orientation where the naive mapping happens to be
     * right. The top-face case is kept here as the control that proves the
     * fix did not simply swap the bug to the other faces.</p>
     */
    private static void greedyMergeTileAxes() {
        // A wall: four unit faces stacked in Y at x = 1, one block deep in Z.
        List<TerrainQuad> wall = new ArrayList<>();
        for (int y = 0; y < 4; y++) {
            wall.add(eastFace(y));
        }
        TerrainQuad mergedWall = onlyMerged(com.deds.meshelium.terrain.GreedyMesher.merge(wall),
                "four stacked east faces");
        checkEq(1, mergedWall.repeatU(),
                "a wall merged in Y must NOT tile along U, which runs along Z on an east face");
        checkEq(4, mergedWall.repeatV(), "it tiles four times along V, which runs along Y");

        // The control: four unit faces in a row along X on a top face, where
        // U really does run along X.
        List<TerrainQuad> floor = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            floor.add(topFace(x));
        }
        TerrainQuad mergedFloor = onlyMerged(com.deds.meshelium.terrain.GreedyMesher.merge(floor),
                "four east-west top faces");
        checkEq(4, mergedFloor.repeatU(), "a floor run along X tiles along U");
        checkEq(1, mergedFloor.repeatV(), "and not along V, which runs along Z");

        // Two faces with the same sprite RECTANGLE but rotated UVs must not
        // merge: the rectangle is identical under a 90 degree rotation, so
        // the rect alone cannot tell them apart, and a merged quad would
        // draw the seed's orientation over both.
        List<TerrainQuad> mixed = new ArrayList<>();
        mixed.add(topFace(0));
        mixed.add(topFaceRotated(1));
        List<TerrainQuad> mixedOut = com.deds.meshelium.terrain.GreedyMesher.merge(mixed);
        checkEq(2, mixedOut.size(),
                "a rotated sprite must not merge with an unrotated one sharing its rectangle");
        for (TerrainQuad q : mixedOut) {
            check(!q.merged(), "neither survivor may claim a tile repeat");
        }
    }

    /**
     * The merged quad must carry the RECTANGLE's corner values, not the seed
     * cell's, and it must refuse a field that linear interpolation cannot
     * reproduce.
     *
     * <p>This is the pin for the affine merge. Copying the seed's four
     * values would paint a whole run with one cell's shading, which is
     * exactly the bug that a superflat harness cannot see, because every
     * face there carries the same light anyway.</p>
     */
    private static void greedyMergeCornerShading() {
        // Light rising 100, 110, 120, 130, 140 across five lattice points:
        // affine, so four faces collapse to one and the merged quad must
        // span the full 100 to 140.
        List<TerrainQuad> ramp = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            ramp.add(litTopFace(x, 100 + 10 * x, 100 + 10 * (x + 1)));
        }
        TerrainQuad merged = onlyMerged(com.deds.meshelium.terrain.GreedyMesher.merge(ramp),
                "an affine light ramp");
        checkEq(4, merged.repeatU(), "the whole ramp is one four-tile quad");
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int i = 0; i < 4; i++) {
            low = Math.min(low, merged.vertex(i).blockLight());
            high = Math.max(high, merged.vertex(i).blockLight());
        }
        checkEq(100, low, "the merged quad's low edge keeps the FIRST cell's light");
        checkEq(140, high, "and its high edge the LAST cell's, not the seed's");

        // The same run with one step doubled is not affine, so interpolating
        // four corners across it would move pixels. It must not collapse.
        List<TerrainQuad> bent = new ArrayList<>();
        int[] lattice = {100, 110, 130, 140, 150};
        for (int x = 0; x < 4; x++) {
            bent.add(litTopFace(x, lattice[x], lattice[x + 1]));
        }
        List<TerrainQuad> bentOut = com.deds.meshelium.terrain.GreedyMesher.merge(bent);
        check(bentOut.size() > 1, "a bent light ramp must not become one quad: "
                + "linear interpolation cannot reproduce it");
    }

    /** A +Y face at y=1 spanning x..x+1, with its own light at each X edge. */
    private static TerrainQuad litTopFace(int x, int lightLow, int lightHigh) {
        return new TerrainQuad(QuadFacing.POS_Y, false, 0, true,
                new TerrainVertex(x, 1.0f, 0, 0.0f, 0.0f, 0xFF808080, lightLow, 200),
                new TerrainVertex(x + 1, 1.0f, 0, TILE, 0.0f, 0xFF808080, lightHigh, 200),
                new TerrainVertex(x + 1, 1.0f, 1, TILE, TILE, 0xFF808080, lightHigh, 200),
                new TerrainVertex(x, 1.0f, 1, 0.0f, TILE, 0xFF808080, lightLow, 200));
    }

    private static TerrainQuad onlyMerged(List<TerrainQuad> out, String what) {
        checkEq(1, out.size(), what + " must collapse to exactly one quad");
        TerrainQuad q = out.get(0);
        check(q.merged(), what + " must come back carrying a tile repeat");
        return q;
    }

    /** Sprite extent: one 16-texel tile in a 256-texel atlas. */
    private static final float TILE = 16.0f / 256.0f;

    /** A +X face at x=1 spanning y..y+1, z 0..1. U runs along Z, V along Y. */
    private static TerrainQuad eastFace(int y) {
        return new TerrainQuad(QuadFacing.POS_X, false, 0, true,
                wallVertex(y, 0, 0.0f, 0.0f),
                wallVertex(y + 1, 0, 0.0f, TILE),
                wallVertex(y + 1, 1, TILE, TILE),
                wallVertex(y, 1, TILE, 0.0f));
    }

    private static TerrainVertex wallVertex(int y, int z, float u, float v) {
        return new TerrainVertex(1.0f, y, z, u, v, 0xFF808080, 32, 200);
    }

    /** A +Y face at y=1 spanning x..x+1, z 0..1. U runs along X, V along Z. */
    private static TerrainQuad topFace(int x) {
        return new TerrainQuad(QuadFacing.POS_Y, false, 0, true,
                floorVertex(x, 0, 0.0f, 0.0f),
                floorVertex(x + 1, 0, TILE, 0.0f),
                floorVertex(x + 1, 1, TILE, TILE),
                floorVertex(x, 1, 0.0f, TILE));
    }

    /** The same face with its sprite turned 90 degrees: U now runs along Z. */
    private static TerrainQuad topFaceRotated(int x) {
        return new TerrainQuad(QuadFacing.POS_Y, false, 0, true,
                floorVertex(x, 0, 0.0f, 0.0f),
                floorVertex(x + 1, 0, 0.0f, TILE),
                floorVertex(x + 1, 1, TILE, TILE),
                floorVertex(x, 1, TILE, 0.0f));
    }

    private static TerrainVertex floorVertex(int x, int z, float u, float v) {
        return new TerrainVertex(x, 1.0f, z, u, v, 0xFF808080, 32, 200);
    }

    // ==================================================================
    // Smart/Solid Leaves Beyond: the pair filter and the solidify rewrite
    // ==================================================================

    /**
     * The census matcher turned removal filter
     * ({@code SectionBuildTap.filterCutoutInteriorPairs}): a POS and a NEG
     * cutout face on the same unit boundary both go; everything the
     * matcher excludes survives, with each exclusion carried by a quad
     * built to fail EXACTLY one test. The translucent probe sits on the
     * very boundary the pair occupies (only its translucency saves it),
     * the solid face is coplanar-opposite the lone cutout (only its
     * cutoff-0 material saves that pairing), and the lone cutout's plane
     * is one block over from the pair's (only the plane keeps it out of
     * their bucket).
     *
     * <p>The bookkeeping half re-encodes the SURVIVORS and checks every
     * count the GPU walk consumes — this is the "filter before encode"
     * seam's pin: the encoder derives buckets, prefix and starts from the
     * list it is handed, so a filtered list yields internally consistent
     * metadata by construction, and this asserts it stayed that way.</p>
     *
     * <p>The Solid tier's half then runs the SAME survivors through
     * {@code SectionBuildTap.solidifyCutouts} — filter strictly first,
     * the tap's order, because the pair matcher keys on the cutout
     * material the rewrite erases — and pins the rewrite's GEOMETRIC
     * eligibility: the lone cutout sits on the very boundary the solid
     * face occupies (the grass-side-overlay shape), so nothing may
     * change and the same list instance must come back. A second list
     * then carries one quad per eligibility rule — a cross (UNASSIGNED
     * facing), an inset vine face (off the lattice), a same-facing decal
     * on an opaque face — plus the one plain leaf face that passes all
     * three, and only the leaf may solidify, pinned all the way into the
     * packed stream's material bits.</p>
     */
    private static void leafTierFilters() {
        TerrainQuad water = xFace(QuadFacing.POS_X, 1, 3, 5, true, 1, 201);  // translucent
        TerrainQuad interiorPos = xFace(QuadFacing.POS_X, 1, 3, 5, false, 2, 1);
        TerrainQuad solid = xFace(QuadFacing.NEG_X, 2, 3, 5, false, 0, 7);   // cutoff 0
        TerrainQuad interiorNeg = xFace(QuadFacing.NEG_X, 1, 3, 5, false, 2, 2);
        TerrainQuad lonely = xFace(QuadFacing.POS_X, 2, 3, 5, false, 2, 3);  // no partner

        List<TerrainQuad> in = List.of(water, interiorPos, solid, interiorNeg, lonely);
        List<TerrainQuad> out = SectionBuildTap.filterCutoutInteriorPairs(in);
        checkEq(3, out.size(), "exactly the interior pair is removed");
        check(out.get(0) == water && out.get(1) == solid && out.get(2) == lonely,
                "survivors keep input order: the translucent probe, the solid, the lone cutout");
        check(SectionBuildTap.filterCutoutInteriorPairs(out) == out,
                "nothing left to pair: the filter returns the input list itself");

        // Counts and prefix bookkeeping stay consistent because they are
        // DERIVED from the filtered list — assert the derivation.
        EncodedSectionMesh mesh = SectionMeshEncoder.encode(out);
        checkEq(3, mesh.quadCount(), "filtered quad count");
        checkEq(3 * 64, mesh.geometryBytes(), "filtered geometry bytes");
        checkEq(1, mesh.offsets()[QuadFacingBuckets.TRANSLUCENT_SLOT],
                "translucent prefix count survives the filter untouched");
        checkEq(1, mesh.bucketCount(QuadFacing.POS_X.ordinal()), "POS_X bucket holds the loner");
        checkEq(1, mesh.bucketCount(QuadFacing.NEG_X.ordinal()), "NEG_X bucket holds the solid");
        for (QuadFacing f : new QuadFacing[] {QuadFacing.POS_Y, QuadFacing.POS_Z,
                QuadFacing.NEG_Y, QuadFacing.NEG_Z, QuadFacing.UNASSIGNED}) {
            checkEq(0, mesh.bucketCount(f.ordinal()), "bucket " + f + " is empty");
        }
        // Stream order (prefix, then buckets 0..6) via the u-tags, the
        // sectionEncoderBucketing technique: water, loner, solid.
        int[] expectedTags = {201, 3, 7};
        ByteBuffer geo = mesh.geometry();
        for (int q = 0; q < expectedTags.length; q++) {
            checkEq(expectedTags[q], geo.getInt(q * 64 + 12) & 0xFFFF,
                    "filtered stream slot " + q);
        }

        // min(pos, neg) per boundary: two POS against one NEG remove ONE
        // pair, first-unmatched-first, and the odd face out survives.
        TerrainQuad dupPosA = xFace(QuadFacing.POS_X, 1, 8, 5, false, 2, 11);
        TerrainQuad dupPosB = xFace(QuadFacing.POS_X, 1, 8, 5, false, 2, 12);
        TerrainQuad dupNeg = xFace(QuadFacing.NEG_X, 1, 8, 5, false, 2, 13);
        List<TerrainQuad> stacked = SectionBuildTap.filterCutoutInteriorPairs(
                List.of(dupPosA, dupPosB, dupNeg));
        checkEq(1, stacked.size(), "min(2 POS, 1 NEG) = one pair removed");
        check(stacked.get(0) == dupPosB, "the earliest unmatched POS paired off first");

        // A pair with nothing else collapses to the empty list. The pure
        // filter is allowed to say so; it is the TAP's job to refuse the
        // empty result and keep such a section Fancy (an empty encode is
        // a deleted section, which the walker could never restore).
        check(SectionBuildTap.filterCutoutInteriorPairs(
                        List.of(interiorPos, interiorNeg)).isEmpty(),
                "a bare pair filters to empty; the tap must fall back to the unfiltered list");

        // ---- The Solid tier, on the SAME survivors (solid implies Smart,
        // and the order is load-bearing: the pair is already gone before
        // the rewrite looks). Eligibility is geometric, and NOTHING here
        // passes it: the water is translucent, the solid is already
        // solid, and the lone cutout sits on the very boundary the solid
        // face occupies — a decal on an opaque face, the grass-side-
        // overlay shape (here opposite-facing; the key is sign-free) —
        // so rewriting it would fill a coplanar smear over the face
        // beneath. Unchanged means the input list itself comes back.
        check(SectionBuildTap.solidifyCutouts(out) == out,
                "a cutout coplanar with an opaque face is a decal: nothing to rewrite, same list");

        // ---- One quad per eligibility rule, plus the one that passes
        // all three. Only the plain leaf face — axis facing, unit cell,
        // no opaque face on its boundary — may solidify; the cross, the
        // inset vine and the decal keep their cutout material, because
        // for them the transparent texels are structural.
        TerrainQuad cross = crossQuad(21);                                       // no axis facing
        TerrainQuad vine = xFace(QuadFacing.POS_X, 4.0625f, 3, 5, false, 2, 22); // off the lattice
        TerrainQuad backing = xFace(QuadFacing.POS_X, 5, 3, 5, false, 0, 23);    // opaque host face
        TerrainQuad decal = xFace(QuadFacing.POS_X, 5, 3, 5, false, 2, 24);      // coplanar overlay
        TerrainQuad leaf = xFace(QuadFacing.POS_X, 6, 3, 5, false, 2, 25);       // passes all three
        List<TerrainQuad> tiers = List.of(cross, vine, backing, decal, leaf);
        List<TerrainQuad> solidified = SectionBuildTap.solidifyCutouts(tiers);
        check(solidified != tiers, "an eligible cutout forces a fresh list");
        checkEq(5, solidified.size(), "solidify never adds or removes");
        check(solidified.get(0) == cross, "a cross survives untouched: UNASSIGNED facing");
        check(solidified.get(1) == vine, "an inset face survives untouched: not a unit cell");
        check(solidified.get(2) == backing, "an already-solid quad is untouched, same object");
        check(solidified.get(3) == decal,
                "a same-facing decal on an opaque face survives untouched");
        TerrainQuad rewritten = solidified.get(4);
        check(rewritten != leaf, "the leaf face was rewritten to a copy");
        checkEq(0, rewritten.alphaCutoffIndex(), "the copy carries the solid material, cutoff 0");
        check(!rewritten.translucent(), "and stays in the opaque pass");
        check(rewritten.facing() == leaf.facing() && rewritten.mip() == leaf.mip()
                        && rewritten.repeatU() == leaf.repeatU()
                        && rewritten.repeatV() == leaf.repeatV(),
                "facing, mip and tile repeat carried over verbatim");
        for (int i = 0; i < 4; i++) {
            check(rewritten.vertex(i) == leaf.vertex(i),
                    "vertex " + i + " carried over by reference");
        }

        // The rewrite must reach the packed stream with every count
        // intact: re-encode and read each quad's material byte (i1 bits
        // 16-23, the codecBitExactness layout). Stream order is buckets
        // 0..6, no translucent prefix here: the four POS_X quads in
        // input order, then the cross in UNASSIGNED. Cutout 0.5 + mip =
        // 0b110, solid + mip = 0b100 — the leaf is the only cutout that
        // crossed, and solidify moves no quad between buckets.
        EncodedSectionMesh solidMesh = SectionMeshEncoder.encode(solidified);
        checkEq(5, solidMesh.quadCount(), "solidified quad count");
        checkEq(0, solidMesh.offsets()[QuadFacingBuckets.TRANSLUCENT_SLOT],
                "no translucent quads in the eligibility list");
        checkEq(4, solidMesh.bucketCount(QuadFacing.POS_X.ordinal()),
                "the X-boundary quads stay in POS_X");
        checkEq(1, solidMesh.bucketCount(QuadFacing.UNASSIGNED.ordinal()),
                "the cross stays in UNASSIGNED");
        ByteBuffer solidGeo = solidMesh.geometry();
        int[] solidTags = {22, 23, 24, 25, 21}; // vine, backing, decal, leaf, cross
        int[] expectedMaterials = {0b110, 0b100, 0b110, 0b100, 0b110};
        for (int q = 0; q < solidTags.length; q++) {
            checkEq(solidTags[q], solidGeo.getInt(q * 64 + 12) & 0xFFFF,
                    "solidified stream slot " + q);
            checkEq(expectedMaterials[q], (solidGeo.getInt(q * 64 + 4) >> 16) & 0xFF,
                    "material byte of stream slot " + q);
        }

        // Every remaining cutout is ineligible: the input list itself
        // comes back, no copy — the no-leaves common case costs one scan
        // (the filter's own contract, shared deliberately).
        check(SectionBuildTap.solidifyCutouts(solidified) == solidified,
                "nothing left to rewrite: solidify returns the input list itself");
    }

    /** A flower-cross diagonal in cell x4..5 z5..6: UNASSIGNED facing, cutout, u-tagged. */
    private static TerrainQuad crossQuad(int uTag) {
        float u = uTag / 32768.0f;
        return new TerrainQuad(QuadFacing.UNASSIGNED, false, 2, true,
                new TerrainVertex(4.15f, 3f, 5.15f, u, 0f, 0xFF808080, 32, 200),
                new TerrainVertex(4.15f, 4f, 5.15f, u, 0f, 0xFF808080, 32, 200),
                new TerrainVertex(4.85f, 4f, 5.85f, u, 1f, 0xFF808080, 32, 200),
                new TerrainVertex(4.85f, 3f, 5.85f, u, 1f, 0xFF808080, 32, 200));
    }

    /** A unit X-boundary face at plane {@code x}, cell y..y+1, z..z+1, u-tagged. */
    private static TerrainQuad xFace(QuadFacing facing, float x, int y, int z,
            boolean translucent, int cutoff, int uTag) {
        float u = uTag / 32768.0f;
        return new TerrainQuad(facing, translucent, cutoff, true,
                new TerrainVertex(x, y, z, u, 0f, 0xFF808080, 32, 200),
                new TerrainVertex(x, y + 1, z, u, 0f, 0xFF808080, 32, 200),
                new TerrainVertex(x, y + 1, z + 1, u, 1f, 0xFF808080, 32, 200),
                new TerrainVertex(x, y, z + 1, u, 1f, 0xFF808080, 32, 200));
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

        // The greedy-merge tile repeat, packed as ONE index in bits 3-7:
        // log2(u) * 5 + log2(v), so 0..24. Two independent 2-bit fields
        // could not hold five values per axis and three bits each would
        // need nine of the byte's eight, so the pair travels jointly and
        // terrain.mesh divides it back apart once per quad. Every case here
        // is a number the shader has to agree with exactly.
        checkEq(0b100, TerrainVertexCodec.materialBits(0, true, 1, 1),
                "repeat 1x1 is pair index 0, so an unmerged quad is bit-identical");
        checkEq((0 * 5 + 1) << 3 | 0b100, TerrainVertexCodec.materialBits(0, true, 1, 2),
                "repeat 1x2 = index 1");
        checkEq((1 * 5 + 0) << 3 | 0b100, TerrainVertexCodec.materialBits(0, true, 2, 1),
                "repeat 2x1 = index 5, and u/v are not interchangeable");
        checkEq((4 * 5 + 4) << 3 | 0b100, TerrainVertexCodec.materialBits(0, true, 16, 16),
                "repeat 16x16 = index 24, the largest, still inside the byte");
        check((TerrainVertexCodec.materialBits(2, false, 16, 16) & ~0xFF) == 0,
                "the whole material byte, repeat included, fits in 8 bits");
        checkThrows(() -> TerrainVertexCodec.materialBits(0, true, 3, 1),
                "a non-power-of-two run is a caller bug, not something to clamp");
        checkThrows(() -> TerrainVertexCodec.materialBits(0, true, 32, 1),
                "a run past 16 has nowhere to go in the encoding");

        // largestRepeat is what the sweep uses to fall back to a legal run.
        checkEq(1, TerrainVertexCodec.largestRepeat(1), "span 1 -> 1");
        checkEq(2, TerrainVertexCodec.largestRepeat(3), "span 3 floors to 2");
        checkEq(8, TerrainVertexCodec.largestRepeat(15), "span 15 floors to 8");
        checkEq(16, TerrainVertexCodec.largestRepeat(16), "span 16 -> 16, a section's width");
        checkEq(16, TerrainVertexCodec.largestRepeat(64), "anything larger still caps at 16");
    }

    private static void checkThrows(Runnable body, String what) {
        try {
            body.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected an IllegalArgumentException: " + what);
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
        checkEq(0, arena.blockOf(a), "single-block arena: everything is block 0");
        checkEq(64, arena.byteOffsetInBlock(a), "byte offset within block = local * 64");
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
