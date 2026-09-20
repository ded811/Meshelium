/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium;

import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;

/**
 * The Java twin of the task shader's facing test, and the lattice that
 * proves both equal Sodium's {@code DefaultChunkRenderer.getVisibleFaces}.
 *
 * <h2>Why a copy exists at all (D-010, contract section 6.2)</h2>
 * <p>D-010's rule is to CALL Sodium's facing test, never reimplement it,
 * because its answer decides which vertices are drawn and a copy that
 * drifted by one block of slack would put holes in the world. A shader
 * cannot call a Java method, so stage 2 carries the formula in
 * {@code terrain.task} as six integer comparisons, and this class is the
 * same six comparisons in Java so a runtime check can hold them against
 * Sodium's method on a lattice every suite run. The shader mirrors this
 * method line for line; the reviewer diffs the two.
 *
 * <h2>The formula (javap, Sodium 0.9.2-beta.1, {@code getVisibleFaces} @0-149)</h2>
 * <p>With {@code origin = section << 4} and {@code end = origin + 16}: the
 * UNASSIGNED bit is always set (@38-43); each positive facing is set when
 * {@code cam > origin - 3} ({@code BitwiseMath.greaterThan}, @45-94); each
 * negative facing when {@code cam < end + 3} ({@code lessThan}, @96-145).
 * {@code greaterThan(a, b)} is {@code (b - a) >>> 31} and {@code lessThan(a, b)}
 * is {@code (a - b) >>> 31}, which for every block coordinate a game can
 * produce equal {@code a > b} and {@code a < b}. The inputs are
 * {@code CameraTransform.intX/Y/Z} verbatim (the {@code d2i} truncation),
 * exactly what stage 1 passes.
 *
 * <h2>Clean room</h2>
 * <p>Sodium is PolyForm Shield. This class reproduces one published
 * arithmetic fact as six comparisons, calls Sodium's public method to
 * check itself, and contains no Sodium code.
 */
public final class MesheliumSodiumFacing {

    /** Facing ordinals, as {@code ModelQuadFacing} declares them (verified by {@link #latticeFailure()}). */
    public static final int POS_X = 0, POS_Y = 1, POS_Z = 2, NEG_X = 3, NEG_Y = 4, NEG_Z = 5, UNASSIGNED = 6;

    /** {@code ModelQuadFacing.ALL}: every facing including UNASSIGNED. */
    public static final int ALL = 0x7F;

    private MesheliumSodiumFacing() {
    }

    /** The shader's formula: which facings of section (sx, sy, sz) the camera at (camX, camY, camZ) can see. */
    public static int visibleFaces(int camX, int camY, int camZ, int sx, int sy, int sz) {
        int ox = sx << 4;
        int oy = sy << 4;
        int oz = sz << 4;
        int ex = ox + 16;
        int ey = oy + 16;
        int ez = oz + 16;
        int mask = 1 << UNASSIGNED;
        mask |= (camX > ox - 3 ? 1 : 0) << POS_X;
        mask |= (camY > oy - 3 ? 1 : 0) << POS_Y;
        mask |= (camZ > oz - 3 ? 1 : 0) << POS_Z;
        mask |= (camX < ex + 3 ? 1 : 0) << NEG_X;
        mask |= (camY < ey + 3 ? 1 : 0) << NEG_Y;
        mask |= (camZ < ez + 3 ? 1 : 0) << NEG_Z;
        return mask;
    }

    /**
     * Self-check against Sodium's own method: every camera coordinate in
     * [-40, 40] on each axis (the other two swept over a coarse set that
     * crosses both signs), every section in [-4, 4]^3, so the
     * {@code origin - 3} and {@code end + 3} bands are crossed on both
     * signs. Also proves the ordinals this class assumes are Sodium's, and
     * that the check can fail: the naive "camera section" key, which the
     * run cache rejected for the same reason, must NOT pass the same
     * lattice. Pure CPU, a few million comparisons.
     *
     * @return null when everything holds, else the first counterexample
     */
    public static String latticeFailure() {
        String ordinals = ordinalFailure();
        if (ordinals != null) {
            return ordinals;
        }
        int[] coarse = {-40, -19, -3, -2, 0, 2, 13, 14, 19, 40};
        for (int axis = 0; axis < 3; axis++) {
            for (int c = -40; c <= 40; c++) {
                for (int a : coarse) {
                    for (int b : coarse) {
                        int camX = axis == 0 ? c : (axis == 1 ? a : b);
                        int camY = axis == 1 ? c : (axis == 0 ? a : b);
                        int camZ = axis == 2 ? c : a;
                        for (int sx = -4; sx <= 4; sx++) {
                            for (int sy = -4; sy <= 4; sy++) {
                                for (int sz = -4; sz <= 4; sz++) {
                                    int ours = visibleFaces(camX, camY, camZ, sx, sy, sz);
                                    int theirs = DefaultChunkRenderer.getVisibleFaces(
                                            camX, camY, camZ, sx, sy, sz);
                                    if (ours != theirs) {
                                        return "visibleFaces(" + camX + "," + camY + "," + camZ
                                                + " ; " + sx + "," + sy + "," + sz + ") = " + ours
                                                + " but Sodium's getVisibleFaces = " + theirs
                                                + "; the task shader would cull differently";
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // The lattice can tell an exact key from a wrong one: the camera
        // SECTION does not determine the mask (13 and 14 share a section;
        // 13 > 13 is false and 14 > 13 is true).
        boolean naiveFailed = false;
        for (int cam = -40; cam <= 40 && !naiveFailed; cam++) {
            for (int other = -40; other <= 40 && !naiveFailed; other++) {
                if ((cam >> 4) != (other >> 4)) {
                    continue;
                }
                for (int s = -4; s <= 4; s++) {
                    if (DefaultChunkRenderer.getVisibleFaces(cam, 0, 0, s, 0, 0)
                            != DefaultChunkRenderer.getVisibleFaces(other, 0, 0, s, 0, 0)) {
                        naiveFailed = true;
                        break;
                    }
                }
            }
        }
        if (!naiveFailed) {
            return "the camera-section key passed the facing lattice, so the lattice cannot "
                    + "tell an exact formula from a wrong one; the check is vacuous";
        }
        return null;
    }

    /** The bit positions this class and the shader use must be Sodium's enum ordinals. */
    private static String ordinalFailure() {
        int[][] expected = {
                {ModelQuadFacing.POS_X.ordinal(), POS_X},
                {ModelQuadFacing.POS_Y.ordinal(), POS_Y},
                {ModelQuadFacing.POS_Z.ordinal(), POS_Z},
                {ModelQuadFacing.NEG_X.ordinal(), NEG_X},
                {ModelQuadFacing.NEG_Y.ordinal(), NEG_Y},
                {ModelQuadFacing.NEG_Z.ordinal(), NEG_Z},
                {ModelQuadFacing.UNASSIGNED.ordinal(), UNASSIGNED}};
        for (int[] pair : expected) {
            if (pair[0] != pair[1]) {
                return "ModelQuadFacing ordinal " + pair[0] + " != assumed " + pair[1]
                        + "; the task shader's facing bits are wrong for this Sodium";
            }
        }
        if (ModelQuadFacing.ALL != ALL || ModelQuadFacing.COUNT != 7) {
            return "ModelQuadFacing.ALL/COUNT are " + ModelQuadFacing.ALL + "/" + ModelQuadFacing.COUNT
                    + ", not 0x7F/7; the record walk's run count is wrong for this Sodium";
        }
        return null;
    }
}
