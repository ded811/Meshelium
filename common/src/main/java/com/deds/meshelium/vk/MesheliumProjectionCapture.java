/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * NEXT (c), 2026-09-15: the matrix the occlusion rasters actually BIND.
 *
 * <h2>Why this exists (plan revision 4, objection 4)</h2>
 * On the standalone host {@code TerrainOcclusion.recordRegionRaster} and
 * {@code recordSectionRaster} push
 * {@code RenderSystem.getProjectionMatrixBuffer()} at binding 2 — vanilla's
 * LEVEL projection buffer. Its content is not
 * {@code CameraRenderState.projectionMatrix}: {@code GameRenderer.renderLevel}
 * (javap -c, 26.2) copies that field, multiplies the COPY by the bob
 * PoseStack ({@code bobHurt} always, {@code bobView} under the option),
 * applies the nether-portal/nausea rotate-scale-rotate to the copy, and
 * uploads THAT through
 * {@code levelProjectionMatrixBuffer.getBuffer(Matrix4f)}.
 *
 * <p>The half-res arm derives two numbers from that matrix — the coverage
 * inflation k and the near-tip radius NearR — and both are WRONG for a
 * matrix carrying an extra transform, in the dangerous direction (a
 * too-small k is a coverage hole, a too-small NearR a near-clip hole). So
 * the arm must see the bound matrix, not a look-alike, and must be able to
 * tell when the bound matrix is not the canonical perspective shape.</p>
 *
 * <h2>What is captured, and the proof that it is the bound one</h2>
 * The mixin records, at every {@code getBuffer(Matrix4f)} RETURN, both the
 * argument (copied — vanilla reuses one {@code tempMatrix}) and the
 * {@code GpuBufferSlice} that call returned. {@link #lastIfBound} hands the
 * matrix back only when that slice is the very object
 * {@code RenderSystem.getProjectionMatrixBuffer()} names at the arm, so the
 * caller is never reasoning about some other
 * {@code ProjectionMatrixBuffer}'s upload. Any other situation returns null
 * and the frame runs full-res, counted.
 *
 * <p>Render thread only in practice (vanilla's projection uploads and the
 * terrain draw are both render-thread work); the fields are volatile so a
 * gametest thread reading {@link #captures()} sees a consistent count.</p>
 */
public final class MesheliumProjectionCapture {

    private MesheliumProjectionCapture() {
    }

    /** A copy, because vanilla reuses one {@code tempMatrix} instance. */
    private static final Matrix4f LAST = new Matrix4f();

    private static volatile GpuBufferSlice lastSlice;
    private static volatile long captures;

    /**
     * NEXT (c1), 2026-09-16: the PRE-BOB perspective, stashed at
     * {@code LevelRenderer.render} HEAD, beside the bound matrix above.
     *
     * <h2>WHY a second matrix, and why the bound one still ships</h2>
     * {@link #LAST} is the matrix the rasters BIND and it must stay that
     * way: the box rasters test against the depth vanilla has just written,
     * and vanilla wrote it with the BOBBED matrix. Binding anything else
     * would compare boxes in one clip space against a depth pyramid built
     * in another.
     *
     * <p>But the two numbers the arm DERIVES from a projection - the
     * coverage inflation k and the near-tip radius NearR - want the
     * canonical perspective shape, and the bobbed matrix is not one.
     * Measured off the 26.2 bytecode: a walking bob is at most a 0.1-block
     * translation and a 0.632-degree rotation (worst off-diagonal of the
     * rotation itself 0.00873), and the PRODUCT {@code P * bobPose} carries
     * the pose's translation into m30/m31 scaled by m00/m11, which at the
     * harness FOV is {@code 1.428148 * 0.1 = 0.143} in front of a gate
     * whose tolerance is 1.0e-5. So the lever refused essentially every
     * walking frame - and every winning cell in the 0t bench matrix
     * (-4.4%, -4.6%, -2.0%) was taken with a STATIONARY camera, so the
     * play-time case had never once been armed.</p>
     *
     * <h2>Why THIS field is the canonical one</h2>
     * {@code CameraRenderState.projectionMatrix} is built by
     * {@code Projection.getMatrix} -> {@code Matrix4f.setPerspective}, which
     * opens with {@code MemUtil.INSTANCE.zero(this)} (javap, joml-1.10.8,
     * ip 0-6), so its zeros are EXACT. {@code GameRenderer.renderLevel}
     * (javap -c, 26.2) COPIES it at ip 75-87 and multiplies the COPY at ip
     * 122-135; the field itself is never written back (one getfield at ip
     * 81, zero putfields in the whole disassembly). Parameter 4 of
     * {@code LevelRenderer.render} is that very object, so a stash taken at
     * its HEAD is THIS frame's P, and the drawn matrix is P times the bob
     * pose by construction.
     */
    private static final Matrix4f PRE_BOB = new Matrix4f();

    /**
     * Advances on every stash, and is both the COUNT and the STALENESS
     * TOKEN - they are the same number, so there is one field and two
     * accessor names.
     *
     * <p>The token is not optional. {@link #PRE_BOB} is preallocated and
     * the count is monotone, so from the second stash on "the stash is
     * stale" is indistinguishable from "the stash is fresh and numerically
     * identical" - which is the steady state of every pinned suite pose and
     * every bench cell. The arm therefore refuses a frame whose serial has
     * not moved since the arm before it, which is what catches an arm
     * reached outside {@code LevelRenderer.render} and a capture that has
     * latched.</p>
     */
    private static volatile long preBobSerial;

    /** Mixin hook: {@code ProjectionMatrixBuffer.getBuffer(Matrix4f)} RETURN. */
    public static void record(Matrix4f matrix, GpuBufferSlice slice) {
        if (matrix == null) {
            return;
        }
        LAST.set(matrix);
        lastSlice = slice;
        captures++;
    }

    /**
     * The captured matrix when {@code bound} is the slice that capture
     * returned, else null. Identity is the right test: vanilla's
     * {@code ProjectionMatrixBuffer} returns ONE {@code bufferSlice} field
     * for the life of the object (constructor, javap -c), so "the same
     * slice object" means "the same projection buffer, written by the call
     * we captured".
     */
    public static Matrix4f lastIfBound(GpuBufferSlice bound) {
        GpuBufferSlice slice = lastSlice;
        if (slice == null || bound == null || slice != bound) {
            return null;
        }
        return LAST;
    }

    /** Level-projection uploads seen (test probe; 0 = the mixin never applied). */
    public static long captures() {
        return captures;
    }

    /**
     * Mixin hook: {@code LevelRenderer.render} HEAD, parameter 4's
     * {@code projectionMatrix}. A copy, for the same reason {@link #record}
     * copies - vanilla reuses one {@code CameraRenderState} and
     * {@code Projection.getMatrix(Matrix4f)} fills the EXISTING instance,
     * so holding the reference would be holding a matrix that changes
     * under us.
     */
    public static void recordPreBob(Matrix4fc matrix) {
        if (matrix == null) {
            return;
        }
        PRE_BOB.set(matrix);
        preBobSerial++;
    }

    /**
     * The pre-bob perspective, or null when nothing has stashed one - a
     * mixin that failed to apply, or a host that reaches the arm outside
     * {@code LevelRenderer.render}. The arm counts that
     * ({@code halfResArmSkipsNoPreBob}) and runs the frame full-res.
     */
    public static Matrix4fc preBob() {
        return preBobSerial == 0L ? null : PRE_BOB;
    }

    /** The per-frame staleness token; see {@link #preBobSerial}. */
    public static long preBobSerial() {
        return preBobSerial;
    }

    /**
     * Pre-bob stashes seen (test probe; 0 = the hook never ran). The same
     * number as {@link #preBobSerial()} under the name the tests read:
     * every stash advances the serial exactly once.
     */
    public static long preBobFrames() {
        return preBobSerial;
    }
}
