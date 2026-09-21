/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.mixin.VulkanCommandEncoderAccessor;

import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;

import org.joml.Matrix4fc;

/**
 * NEXT (c), 2026-09-15: the half-resolution attachment pair the occlusion
 * box rasters test against, and the per-frame constants the half raster
 * variants need.
 *
 * <h2>Why (0s, NEXT 2f.3 (c))</h2>
 * The two box rasters are PIXEL-bound: 0.133 ms at 1080p against 0.211 ms
 * at 1440p for the same scene, and 0.60 ms when every box passes. Quartering
 * their pixel count is the cheap lever; this class owns the quarter-size
 * target it needs. Owner directive, standing: "performance is king" - and
 * the other one, "computers yours continue working please", is why every
 * ingredient here is FAIL-OPEN (a mistake re-admits hidden sections and
 * costs frame time; it must never delete terrain) and why the whole lever
 * defaults OFF until the 1080p AND 1440p matrix says otherwise.
 *
 * <h2>Shape B: a dummy colour attachment beside the half depth</h2>
 * The pass carries a half-size RGBA8 colour view it never writes (mask 0 in
 * every box pipeline) beside the half-size D32 depth, for three reasons the
 * code already records: the 5-argument {@code createRenderPass} overload
 * the drawers use derives the render area from the COLOUR view; the six
 * existing box pipelines bake {@code colorAttachmentCount(1)} plus the main
 * colour/depth formats and are reused unchanged when the half attachments
 * carry the same {@code GpuFormat}s; and vanilla's zero-colour-attachment
 * path is bytecode-plausible but driver-UNVERIFIED, which is exactly why
 * {@code TerrainOcclusion} attaches a mask-0 colour today. Cost: halfW *
 * halfH * 4 B each, so 3.96 MiB at 1080p and 7.03 MiB at 1440p for the
 * pair. A depth-only variant is a follow-up lever, not a dependency.
 *
 * <h2>The in-pass assertion is the ONLY structural guard</h2>
 * {@link #ensure} throws before it allocates anything if the backend
 * encoder has a render pass open, because nothing else would notice. There
 * is no Java-level refusal of a texture created inside a rendering
 * instance: {@code VulkanCommandEncoder.commandBuffer()} returns the live
 * shared buffer whenever one exists, so the {@code VulkanGpuTexture}
 * constructor's UNDEFINED-to-GENERAL image barrier would simply be recorded
 * INSIDE the {@code vkCmdBeginRenderingKHR} instance
 * (VUID-vkCmdPipelineBarrier-oldLayout-01181,
 * VUID-vkCmdPipelineBarrier-None-07889) and only the validation layer would
 * ever say so. Nor does vanilla refuse a NESTED pass - neither the facade's
 * nor the backend's {@code createRenderPass} tests the field before opening
 * one. See {@link VulkanCommandEncoderAccessor}.
 */
final class OcclusionHalfResTarget {

    /**
     * Vanilla's level near plane, by NAME rather than by a copied literal:
     * {@code net.minecraft.client.Camera.PROJECTION_Z_NEAR} is
     * {@code public static final float = 0.05f} (javap -constants, 26.2), a
     * ConstantValue, so javac inlines it and this class's bytecode gains no
     * client-class reference. {@code Camera.update} ip 161 {@code ldc 0.05f}
     * -> {@code setupPerspective(FFFFF)} -> {@code Projection.setupPerspective}
     * ip 71-72 {@code zNear} is the evidence that this constant IS the plane
     * the level projection is built with.
     */
    static final float OCC_NEAR = net.minecraft.client.Camera.PROJECTION_Z_NEAR;

    /**
     * Added to the near-tip radius: float slop at the plane, and the FLAT
     * arm's KAPPA margin (a box mapped into [1 - 2^-16, 1] can only fail
     * against scene depth nearer than zNear * (1 + 2^-16), which lies inside
     * this slack).
     *
     * <p>NEXT (c1), 2026-09-16: it also absorbs the ORTHOGONALITY term of
     * the recovery gate, and that is charged here in writing rather than
     * left implicit. G3 and G5 admit {@code ||L^T L - I||_inf <= ORTHO_EPS},
     * and for any unit u, {@code ||Lu||^2 = 1 + u^T E u} with
     * {@code |u^T E u| <= ||E||_inf * (sum |u_i|)^2 <= 3 ||E||_inf}, so
     * {@code sigma_min(L_W) >= 1 - 3 * ORTHO_EPS = 0.9997} and the
     * near-tip bound needs R inflated by {@code 1/0.9997 = 1.00030}. At the
     * worst admissible configuration (3.5:1 ultrawide at fov 110, where
     * {@code R_base = 0.264692}) with {@code ||t|| <= 0.1} that excess is
     * {@code 1.094e-4} blocks, against this slack of 0.05 whose other
     * quantified duty is {@code zNear * 2^-16 = 7.6e-7}. So the shipped
     * formula stays a plain sum, with 450x of margin on the term this
     * paragraph adds.</p>
     */
    static final float OCC_NEAR_SLACK = 0.05f;

    /**
     * One WHOLE half-res pixel of clearance: 2 * sqrt(2). The bound needs
     * 0.707 (half a pixel diagonal); the doubling pays for mesh-stage float
     * rounding, the top-left fill rule refusing a sample exactly on the
     * inflated edge, and the odd-size x/y asymmetry.
     *
     * <h2>NEXT (c1), 2026-09-16: what ELSE draws on that doubling</h2>
     * k is now derived from the PRE-BOB perspective P while the rasters
     * bind the drawn matrix {@code D = P * M}, so the canonical pixel scale
     * and the drawn one are not the same number. This is LOAD-BEARING on
     * every default frame, not documentation: {@code halfResInflateEnabled}
     * is absent-means-ON, and neither the canonical suite invocation nor a
     * player session sets that property, so {@code frameInflateK()} returns
     * the real k and pushes it to rasters bound to D.
     *
     * <p>Exactly ONE term separates the two scales, and the budget is
     * charged against what the GATE enforces, not against what vanilla's
     * bob happens to do.</p>
     *
     * <p><b>There is NO orientation term.</b> The inflation is a Minkowski
     * sum with a CUBE of half-side {@code inflate}
     * (region_raster.mesh:312-313), which contains a BALL of that radius,
     * and a ball's projected half-res pixel radius at view distance d is
     * {@code inflate * (halfW/2) * m00 / d} on x and the
     * {@code (halfH/2) * m11} form on y, with no dependence on orientation
     * at all. So a rotation costs this budget nothing - which matters,
     * because the recovery gate places NO bound on the rotation angle: G3
     * is satisfied EXACTLY by every rotation matrix, and B.7's deliberate
     * admission of the {@code bobHurt} damage tilt reaches 14 degrees
     * ({@code tilt = -f * 14.0 * damageTiltStrength}, GameRenderer.bobHurt
     * ip 126-145) on top of the death spiral's
     * {@code 40 - 8000/220 = 3.6364} (ip 42-65) and the bob's 0.632.</p>
     *
     * <p><b>The one real charge is the APEX DISPLACEMENT.</b> The shader's
     * {@code dFar = length(max(abs(lo), abs(hi)))}
     * (region_raster.mesh:306) is measured from the shader origin, while
     * the coverage argument wants the distance from the DISPLACED apex, so
     * the ratio is {@code (dFar + ||t||) / dFar}. Charged at
     * {@code TRANSLATE_MAX = 0.25} - the gate's own bound, not the bob's
     * proven 0.1 - and at the true section-box floor: each axis of a
     * 16-block box inflated by {@code BOX_INFLATE = 0.1} has
     * {@code max(|lo|,|hi|) >= 8.1}, so
     * {@code dFar >= 8.1 * sqrt(3) = 14.03} and the ratio is at most
     * {@code 14.28 / 14.03 = 1.018}. Against the 2x (100%) margin above
     * that is 1.8% of the budget spent. Anyone editing INFLATE_PIXELS is
     * editing that budget too.</p>
     */
    private static final float INFLATE_PIXELS = 2.8284f;

    private final VulkanCommandEncoder encoder;

    private GpuTexture color;
    private GpuTexture depth;
    private GpuTextureView colorView;
    private GpuTextureView depthView;
    private int halfW;
    private int halfH;
    private int srcW;
    private int srcH;
    private float inflateK;
    private float nearR;

    /** One WARN a session for a projection the arm cannot use. */
    private static volatile boolean armWarned;
    /** One INFO for the first target; DEBUG for every re-creation after it. */
    private static volatile boolean createLogged;

    OcclusionHalfResTarget(VulkanCommandEncoder encoder) {
        this.encoder = encoder;
    }

    int halfWidth() {
        return halfW;
    }

    int halfHeight() {
        return halfH;
    }

    GpuTextureView colorView() {
        return colorView;
    }

    GpuTextureView depthView() {
        return depthView;
    }

    float inflateK() {
        return inflateK;
    }

    float nearR() {
        return nearR;
    }

    /**
     * Make the half-size pair match the main target, creating or recreating
     * it when the size changed. Render thread, OUTSIDE any render pass -
     * which the first statement proves rather than assumes.
     *
     * @return false when there is no device yet (the frame runs full-res);
     *         a throw is the caller's half-res latch, never occlusion's
     */
    boolean ensure(GpuTextureView mainColor, GpuTextureView mainDepth) {
        // FIRST, before anything is created. See the class javadoc: this is
        // the only Java-level guard that a vanilla pass is closed, and a
        // texture created inside one records an illegal in-pass layout
        // barrier that nothing but the validation layer reports.
        if (((VulkanCommandEncoderAccessor) encoder).meshelium$currentRenderPass() != null) {
            throw new IllegalStateException(
                    "half-res target created inside an open render pass");
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return false;
        }
        int w = mainDepth.texture().getWidth(0);
        int h = mainDepth.texture().getHeight(0);
        if (w == srcW && h == srcH && color != null && depth != null
                && colorView != null && depthView != null
                && !color.isClosed() && !depth.isClosed()
                && !colorView.isClosed() && !depthView.isClosed()) {
            return true;
        }
        closeAll();
        // CEIL, not floor: with floor the last column/row of an odd size
        // would belong to no half pixel and a box covering only it would be
        // dropped - a hole, the one failure class this lever may not have.
        int nw = (w + 1) / 2;
        int nh = (h + 1) / 2;

        // The leak rule (plan revision 3, objection 9): create into LOCALS,
        // and on any failure close what was already made. Revision 2 let a
        // throw propagate with two attachments half-built, leaking 2-4 MiB
        // of image per attempt - never stored, so never closable.
        GpuTexture c = null;
        GpuTexture d = null;
        GpuTextureView cv = null;
        GpuTextureView dv = null;
        try {
            c = device.createTexture(() -> "meshelium occlusion half colour",
                    GpuTexture.USAGE_RENDER_ATTACHMENT, mainColor.texture().getFormat(),
                    nw, nh, 1, 1);
            d = device.createTexture(() -> "meshelium occlusion half depth",
                    GpuTexture.USAGE_RENDER_ATTACHMENT, mainDepth.texture().getFormat(),
                    nw, nh, 1, 1);
            cv = device.createTextureView(c);
            dv = device.createTextureView(d);
        } catch (GpuDeviceLossException t) {
            // Device loss: rethrow WITHOUT touching the encoder. The
            // deferred VulkanGpuTextureView.close() queues itself on the
            // encoder's destroy rotation, which must not run after loss.
            throw t;
        } catch (Throwable t) {
            TerrainOcclusion.halfResAllocationFailures++;
            closeQuietly(cv);
            closeQuietly(c);
            closeQuietly(dv);
            closeQuietly(d);
            throw t;
        }
        // All four exist: only now do the fields change.
        color = c;
        depth = d;
        colorView = cv;
        depthView = dv;
        srcW = w;
        srcH = h;
        halfW = nw;
        halfH = nh;
        TerrainOcclusion.halfResAllocations++;
        TerrainOcclusion.halfResWidth = nw;
        TerrainOcclusion.halfResHeight = nh;
        String line = "Meshelium occlusion half-res target {}x{} for a {}x{} main target "
                + "(colour {}, depth {})";
        if (createLogged) {
            MesheliumLog.LOGGER.debug(line, nw, nh, w, h,
                    mainColor.texture().getFormat(), mainDepth.texture().getFormat());
        } else {
            createLogged = true;
            MesheliumLog.LOGGER.info(line, nw, nh, w, h,
                    mainColor.texture().getFormat(), mainDepth.texture().getFormat());
        }
        return true;
    }

    /**
     * Derive this frame's two shader constants - the coverage inflation k
     * and the near-tip radius NearR - from the PRE-BOB perspective, and
     * verify per frame that the matrix the rasters BIND really is that
     * perspective times a rigid transform.
     *
     * <h2>NEXT (c1), 2026-09-16: WHY this is no longer a shape gate</h2>
     * The old gate tested the DRAWN matrix for the canonical perspective
     * shape and refused anything else. That is the right screen for a
     * derivation that reads the drawn matrix, and it is why every walking
     * frame was refused: the drawn matrix is {@code P * bobPose}
     * (GameRenderer.renderLevel ip 122-135), and with {@code P} canonical
     * the pose's TRANSLATION column lands in m30/m31 scaled by m00/m11.
     * Measured off the 26.2 bytecode the bob is at most a 0.1-block
     * translation and a 0.632-degree rotation (worst off-diagonal of the
     * rotation itself 0.00873), so at the harness's 854x480 / fov 70 the
     * gate saw {@code m11 * 0.1 = 0.142815} against a tolerance of
     * {@code CANONICAL_EPSILON = 1.0e-5} - 14,281x - and there is no phase
     * of a saturated bob at which it could arm. Every winning cell in the
     * 0t bench matrix was a STATIONARY camera, so the lever had never been
     * measured in the state a player is actually in.
     *
     * <p>So the derivation moved to
     * {@code CameraRenderState.projectionMatrix} (canonical by
     * construction - {@code Matrix4f.setPerspective} opens with
     * {@code MemUtil.INSTANCE.zero(this)}, javap joml-1.10.8 ip 0-6 - and
     * never written back, one getfield at renderLevel ip 81 and zero
     * putfields), and what is left on the drawn matrix is a RECOVERY:
     * solve {@code D = P * M} in closed form and require {@code M} to be an
     * affine isometry with a bounded translation. Both matrices are still
     * used, with different jobs: the rasters keep BINDING {@code D},
     * because the box must raster in the same clip space as the terrain
     * depth it tests against.</p>
     *
     * <h2>The closed form (no matrix inverse, about 20 flops)</h2>
     * With {@code P} canonical its rows as a linear map are
     * {@code (m00,0,0,0)}, {@code (0,m11,0,0)}, {@code (0,0,m22,m32)},
     * {@code (0,0,-1,0)}, so equating rows of {@code P * M} gives
     * {@code D.row0 = P.m00 * M.row0}, {@code D.row1 = P.m11 * M.row1},
     * {@code D.row2 = P.m22 * M.row2 + P.m32 * M.row3} and
     * {@code D.row3 = -M.row2}, which inverts directly. The translation
     * line is not a sanity check against a derivation, it is javap:
     * {@code Matrix4f.mul} dispatches to {@code mulPerspectiveAffine} (ip
     * 87-113) for a PERSPECTIVE times an AFFINE, and that method computes
     * {@code dest.m30 = this.m00 * right.m30},
     * {@code dest.m31 = this.m11 * right.m31},
     * {@code dest.m32 = this.m22 * right.m32 + this.m32} and
     * {@code dest.m33 = this.m23 * right.m32} at ip 155-210, writing them
     * at ip 274/279/284/289.
     *
     * <h2>What each check is FOR</h2>
     * <ul>
     *   <li><b>G-1</b> the 16 entries of {@code D} are finite. See the
     *       spelling note below - this is the one job the old gate did by
     *       accident and this one cannot.</li>
     *   <li><b>G0</b> the stash exists and this frame's serial advanced.</li>
     *   <li><b>G1/G1b</b> {@code P} is the canonical perspective, and the
     *       four divisors are non-zero. Passes by construction; kept
     *       because it IS the guard on those divisors, it is the check that
     *       the stash is what we think it is, and {@code m22 > 0} is the
     *       reversed-Z orientation test everything downstream depends
     *       on.</li>
     *   <li><b>G2</b> {@code M}'s bottom row is {@code (0,0,0,1)}. This is
     *       the ONLY property the FLAT arm's max-over-corners needs: with
     *       {@code P} canonical, {@code z_ndc = -(m22 + m32/q_z)} is
     *       strictly monotone in {@code q_z}, and {@code q_z} is an AFFINE
     *       function of the box point exactly when the composite's bottom
     *       row is {@code (0,0,0,1)}; an affine function attains its
     *       extrema over a box at a corner. That is why the drawn matrix
     *       can go to the mesh stage with no shape gate on it at all.</li>
     *   <li><b>G3</b> {@code ||L^T L - I||_inf <= ORTHO_EPS}. NOT a rescue
     *       of the NearR bound: the portal/nausea composite is
     *       {@code N = I + (1/g - 1) v v^T} with {@code 1/g >= 1}
     *       (renderLevel ip 192-218, {@code h(f) = 5/(f^2+5) - 0.04f <= 1}
     *       on {@code f} in [0,1]), so its singular values are
     *       {@code {1, 1, 1/g}}, {@code sigma_min(L) = 1}, and the bound
     *       does not fail for the one transform that motivated the check.
     *       G3 is here to bound the PIXEL-SCALE term k, and to keep the
     *       gate a statement about a class that was proven rather than a
     *       tolerance that was tuned. Refusing costs one full-res
     *       frame.</li>
     *   <li><b>G4</b> {@code ||t|| <= TRANSLATE_MAX}. A cost, not a hole -
     *       and it IS a derivation input, for k's apex term (see
     *       {@link #INFLATE_PIXELS}).</li>
     *   <li><b>G5</b> {@code ModelViewMat} is a rotation about the origin.
     *       A REGRESSION guard on a fact proven in three javap hops, not an
     *       open question: {@code LevelRenderer.render} ip 356-367 passes
     *       {@code cameraRenderState.viewRotationMatrix} to
     *       {@code prepareChunkRenders}, Sodium's own
     *       {@code LevelRendererMixin.getRenderState} builds its
     *       {@code ChunkRenderMatrices} from that same parameter, and
     *       {@code Camera.getViewRotationMatrix} is
     *       {@code Matrix4f.rotation(...)} - translation exactly 0, bottom
     *       row exactly {@code (0,0,0,1)}. It also turns the full-res
     *       path's unstated precondition (the camera-inside test assumes
     *       the shader origin IS the eye) into a per-frame verified
     *       fact.</li>
     * </ul>
     *
     * <h2>THE SPELLING RULE: every check is written {@code !(x <= LIMIT)}</h2>
     * The old gate was NaN-safe by accident of an idiom. Its
     * {@code !(m00 > 0f)} clause - not the off-diagonal clause - is what
     * caught a non-finite matrix, because {@code NaN > eps} is false and
     * {@code Math.max} propagates NaN. G1 now runs on {@code P}, which
     * cannot be NaN ({@code Projection.getMatrix} builds it), so that
     * second job has NO OWNER unless the negated spelling and G-1 take it.
     * Written the other way round a NaN {@code D} would be ADMITTED: k and
     * NearR would be finite (they come from {@code P}), the attachments
     * would arm, {@code gl_Position} would be NaN for every corner, no box
     * would rasterise, and the frame's FLAT stamps - which the phase-B skip
     * and the ring carry forward - would read as "nothing visible". That is
     * terrain deletion outliving the frame, from the one input the old gate
     * was already screening.
     *
     * <p>The trigger is REPORT section 2.3: {@code bobHurt} at
     * {@code partialTick == 0.0f} with {@code hurtDuration == 0} computes
     * {@code 0.0f / 0} and feeds the result to
     * {@code Axis.ZP.rotationDegrees}. That trigger is currently DEAD, and
     * the bytecode is recorded here so nobody re-opens it:
     * {@code Mth.sin(double)} is a table lookup, not an arithmetic sine -
     * {@code (long) NaN == 0} in Java, so {@code d2l}, {@code land 65535},
     * {@code faload} returns {@code SIN[0] = 0.0f}, the tilt is
     * {@code -0.0f * 14 * damageTiltStrength} and the quaternion is the
     * identity. Today's vanilla cannot reach the arm with a NaN drawn
     * matrix through {@code bobHurt}. The NaN-safe spelling costs nothing
     * and stays.</p>
     *
     * @param drawn the matrix the rasters BIND (vanilla's bob-multiplied
     *        level projection); CHECKED, never derived from
     * @param preBob {@code CameraRenderState.projectionMatrix} as stashed
     *        at {@code LevelRenderer.render} HEAD; DERIVED from
     * @param modelView the scene {@code ModelViewMat}, i.e.
     *        {@code cam.viewRotationMatrix} on both hosts
     * @param preBobSerial this frame's stash token; an arm whose serial has
     *        not advanced since the previous arm is refused as stale
     * @return false when the frame cannot be armed (counted in
     *         {@code halfResArmSkips} and in the per-reason counter the
     *         failing check names); the frame then runs full-res
     */
    boolean arm(Matrix4fc drawn, Matrix4fc preBob, Matrix4fc modelView, long preBobSerial) {
        // ---- G-1: the DRAWN matrix is a number at all ----
        if (drawn == null || !allFinite(drawn)) {
            TerrainOcclusion.halfResArmSkips++;
            TerrainOcclusion.halfResArmSkipsNonFinite++;
            warnRefused("G-1 (the drawn matrix is null or has a non-finite entry)",
                    drawn == null ? "null" : "m00=" + drawn.m00() + " m11=" + drawn.m11()
                            + " m23=" + drawn.m23() + " m33=" + drawn.m33());
            return false;
        }
        // ---- G0: the stash, and that it belongs to THIS frame ----
        if (preBob == null) {
            TerrainOcclusion.halfResArmSkips++;
            TerrainOcclusion.halfResArmSkipsNoPreBob++;
            warnRefused("G0 (no pre-bob stash)",
                    "MesheliumProjectionCapture.preBob() is null: the LevelRenderer.render "
                            + "HEAD hook never ran on this host");
            return false;
        }
        if (!(preBobSerial > lastArmedPreBobSerial)) {
            TerrainOcclusion.halfResArmSkips++;
            TerrainOcclusion.halfResArmSkipsStale++;
            warnRefused("G0 (stale stash)",
                    "preBobSerial " + preBobSerial + " has not advanced since the previous arm "
                            + "at " + lastArmedPreBobSerial + ": this arm was reached outside "
                            + "LevelRenderer.render, or the stash has latched");
            return false;
        }
        // ---- G1 / G1b: the stash IS the canonical perspective ----
        // m22 > 0 is the REVERSED-Z orientation (review, 2026-09-15): with
        // vanilla's (F, N) swap JOML sets m22 = N/(F-N) > 0, while a
        // standard-Z matrix has m22 < 0. Every "nearest is the maximum"
        // statement in the downsample and the rasters depends on it.
        float off = Math.max(
                Math.max(Math.max(Math.abs(preBob.m01()), Math.abs(preBob.m02())),
                        Math.max(Math.abs(preBob.m03()), Math.abs(preBob.m10()))),
                Math.max(Math.max(Math.abs(preBob.m12()), Math.abs(preBob.m13())),
                        Math.max(Math.max(Math.abs(preBob.m20()), Math.abs(preBob.m21())),
                                Math.max(Math.abs(preBob.m30()), Math.abs(preBob.m31())))));
        float w = Math.abs(preBob.m33());
        float persp = Math.abs(preBob.m23() + 1f);
        if (!(off <= CANONICAL_EPSILON) || !(w <= CANONICAL_EPSILON)
                || !(persp <= CANONICAL_EPSILON)
                || !(preBob.m00() > 0f) || !(preBob.m11() > 0f) || !(preBob.m22() > 0f)
                || !(Math.abs(preBob.m32()) > 0f)) {
            TerrainOcclusion.halfResArmSkips++;
            TerrainOcclusion.halfResArmSkipsShape++;
            warnRefused("G1 (the pre-bob stash is not a canonical perspective)",
                    "worst off-diagonal " + off + ", |m33| " + w + ", |m23+1| " + persp
                            + ", m00 " + preBob.m00() + ", m11 " + preBob.m11()
                            + ", m22 " + preBob.m22() + ", m32 " + preBob.m32()
                            + " (tolerance " + CANONICAL_EPSILON + ")");
            return false;
        }
        float p00 = preBob.m00();
        float p11 = preBob.m11();
        float p22 = preBob.m22();
        float p32 = preBob.m32();

        // ---- the recovery: L, t, and M's bottom row ----
        double l00 = drawn.m00() / (double) p00;
        double l01 = drawn.m10() / (double) p00;
        double l02 = drawn.m20() / (double) p00;
        double l10 = drawn.m01() / (double) p11;
        double l11 = drawn.m11() / (double) p11;
        double l12 = drawn.m21() / (double) p11;
        double l20 = -drawn.m03();
        double l21 = -drawn.m13();
        double l22 = -drawn.m23();
        float tx = drawn.m30() / p00;
        float ty = drawn.m31() / p11;
        float tz = -drawn.m33();
        double b0 = (drawn.m02() + (double) p22 * drawn.m03()) / p32;
        double b1 = (drawn.m12() + (double) p22 * drawn.m13()) / p32;
        double b2 = (drawn.m22() + (double) p22 * drawn.m23()) / p32;
        double b3 = (drawn.m32() + (double) p22 * drawn.m33()) / p32;

        // ---- G2: M is AFFINE, which is FLAT's whole premise ----
        double bottom = Math.max(Math.max(Math.abs(b0), Math.abs(b1)),
                Math.max(Math.abs(b2), Math.abs(b3 - 1.0)));
        if (!(bottom <= RIGID_EPS)) {
            TerrainOcclusion.halfResArmSkips++;
            TerrainOcclusion.halfResArmSkipsNonRigid++;
            warnRefused("G2 (the recovered transform is projective, not affine)",
                    "bottom row (" + b0 + ", " + b1 + ", " + b2 + ", " + b3 + ") deviates by "
                            + bottom + " (tolerance " + RIGID_EPS + ")");
            return false;
        }
        // ---- G3: L is an isometry; refuses the portal/nausea stretch ----
        double ortho = orthoResidual(l00, l01, l02, l10, l11, l12, l20, l21, l22);
        if (!(ortho <= ORTHO_EPS)) {
            TerrainOcclusion.halfResArmSkips++;
            TerrainOcclusion.halfResArmSkipsNonRigid++;
            warnRefused("G3 (the recovered transform is not an isometry)",
                    "||L^T L - I||_inf " + ortho + " (tolerance " + ORTHO_EPS + "); the "
                            + "portal/nausea rotate-scale-rotate does exactly this");
            return false;
        }
        // ---- G4: the apex displacement is bounded ----
        double tNorm = Math.sqrt((double) tx * tx + (double) ty * ty + (double) tz * tz);
        if (!(tNorm <= TRANSLATE_MAX)) {
            TerrainOcclusion.halfResArmSkips++;
            TerrainOcclusion.halfResArmSkipsTranslation++;
            warnRefused("G4 (the recovered eye displacement is out of bounds)",
                    "||t|| " + tNorm + " blocks from t=(" + tx + ", " + ty + ", " + tz
                            + ") (bound " + TRANSLATE_MAX + "; vanilla's bob is <= 0.1)");
            return false;
        }
        // ---- G5: ModelViewMat is a rotation about the origin ----
        if (modelView == null || !allFinite(modelView)) {
            TerrainOcclusion.halfResArmSkips++;
            TerrainOcclusion.halfResArmSkipsView++;
            warnRefused("G5 (no usable ModelViewMat)",
                    modelView == null ? "null" : "a non-finite entry");
            return false;
        }
        double viewOrigin = Math.max(
                Math.max(Math.abs(modelView.m30()), Math.abs(modelView.m31())),
                Math.max(Math.abs(modelView.m32()),
                        Math.max(Math.max(Math.abs(modelView.m03()), Math.abs(modelView.m13())),
                                Math.max(Math.abs(modelView.m23()),
                                        Math.abs(modelView.m33() - 1f)))));
        double viewOrtho = orthoResidual(modelView.m00(), modelView.m10(), modelView.m20(),
                modelView.m01(), modelView.m11(), modelView.m21(),
                modelView.m02(), modelView.m12(), modelView.m22());
        if (!(viewOrigin <= RIGID_EPS) || !(viewOrtho <= ORTHO_EPS)) {
            TerrainOcclusion.halfResArmSkips++;
            TerrainOcclusion.halfResArmSkipsView++;
            warnRefused("G5 (ModelViewMat is not a rotation about the origin)",
                    "origin/bottom-row deviation " + viewOrigin + " (tolerance " + RIGID_EPS
                            + "), ||Q^T Q - I||_inf " + viewOrtho + " (tolerance " + ORTHO_EPS
                            + "); the shader origin is assumed to BE the eye");
            return false;
        }

        // ---- derive, from P, never from the drawn matrix ----
        // Absolute values, so a later relaxation of the sign rule cannot
        // change k or R by a sign. JOML's setPerspective: m11 =
        // 1/tan(fovy/2), m00 = m11/aspect, both unaffected by vanilla's
        // near/far swap.
        float a00 = Math.abs(p00);
        float a11 = Math.abs(p11);
        float scale = Math.min(halfW * a00, halfH * a11);
        if (!(scale > 0f)) {
            TerrainOcclusion.halfResArmSkips++;
            TerrainOcclusion.halfResArmSkipsShape++;
            warnRefused("G1 (no half target to scale against)",
                    "halfW " + halfW + " halfH " + halfH + " m00 " + a00 + " m11 " + a11);
            return false;
        }
        // A world offset delta at view distance D projects to
        // delta * (halfW/2) * m00 / D half-res pixels on x and
        // delta * (halfH/2) * m11 / D on y; the MIN makes an odd size's
        // axis asymmetry exact rather than covered by the margin.
        inflateK = INFLATE_PIXELS / scale;
        // The near tip: NDC x = m00 * x_v / dist, so the near face's corner
        // is (N/m00, N/m11, N) and its norm is R_base. A box that can lose
        // a viewport fragment to near clipping contains a point within R of
        // the camera, so the box widened by R contains the origin. The
        // recovered ||t|| is the EXACT apex displacement of THIS frame, not
        // a worst case: at a pinned pose it is exactly 0.0f (see
        // TerrainOcclusion.halfResArmedTransformedFrames for the algebra),
        // so R stays where it is today to within 1e-8 blocks and 97_18's
        // forcedNear equality is unmoved, while a walking frame pays only
        // the displacement it really has instead of a constant 0.1.
        nearR = (float) (OCC_NEAR * Math.sqrt(1.0 + 1.0 / ((double) a00 * a00)
                + 1.0 / ((double) a11 * a11)) + tNorm) + OCC_NEAR_SLACK;
        // Published so 97_18's pose rule can compute how far the near force
        // can REACH at this resolution from the live numbers, instead of
        // quoting the plan's arithmetic back at itself.
        TerrainOcclusion.halfResInflateK = inflateK;
        TerrainOcclusion.halfResNearR = nearR;
        lastArmedPreBobSerial = preBobSerial;
        float tf = (float) tNorm;
        float of = (float) ortho;
        if (tf > TerrainOcclusion.halfResMaxArmedTranslation) {
            TerrainOcclusion.halfResMaxArmedTranslation = tf;
        }
        if (of > TerrainOcclusion.halfResMaxArmedOrtho) {
            TerrainOcclusion.halfResMaxArmedOrtho = of;
        }
        if (tf > TRANSFORMED_TRANSLATION_EPS || of > TRANSFORMED_ORTHO_EPS) {
            TerrainOcclusion.halfResArmedTransformedFrames++;
        }
        return true;
    }

    /** {@code Float.isFinite} over all 16 entries; see {@link #arm}'s G-1. */
    private static boolean allFinite(Matrix4fc m) {
        return Float.isFinite(m.m00()) && Float.isFinite(m.m01())
                && Float.isFinite(m.m02()) && Float.isFinite(m.m03())
                && Float.isFinite(m.m10()) && Float.isFinite(m.m11())
                && Float.isFinite(m.m12()) && Float.isFinite(m.m13())
                && Float.isFinite(m.m20()) && Float.isFinite(m.m21())
                && Float.isFinite(m.m22()) && Float.isFinite(m.m23())
                && Float.isFinite(m.m30()) && Float.isFinite(m.m31())
                && Float.isFinite(m.m32()) && Float.isFinite(m.m33());
    }

    /**
     * {@code ||R^T R - I||_inf} as the max absolute ENTRY, from R's ROWS.
     * The max-entry convention is the one both bounds in this class are
     * stated in: the cost side ({@code sigma_min >= sqrt(1 - 3 eps)}) and
     * the rejection side (see {@link #ORTHO_EPS}).
     */
    private static double orthoResidual(double r00, double r01, double r02,
            double r10, double r11, double r12, double r20, double r21, double r22) {
        double e00 = r00 * r00 + r10 * r10 + r20 * r20 - 1.0;
        double e11 = r01 * r01 + r11 * r11 + r21 * r21 - 1.0;
        double e22 = r02 * r02 + r12 * r12 + r22 * r22 - 1.0;
        double e01 = r00 * r01 + r10 * r11 + r20 * r21;
        double e02 = r00 * r02 + r10 * r12 + r20 * r22;
        double e12 = r01 * r02 + r11 * r12 + r21 * r22;
        double diag = Math.max(Math.abs(e00), Math.max(Math.abs(e11), Math.abs(e22)));
        return Math.max(diag,
                Math.max(Math.abs(e01), Math.max(Math.abs(e02), Math.abs(e12))));
    }

    /**
     * How far the PRE-BOB stash may sit from the canonical perspective
     * shape and still be armed. It passes by construction - JOML's
     * {@code setPerspective} writes into a zeroed matrix and
     * {@code Projection.getMatrix} copies it verbatim on BOTH of its paths
     * (the {@code isMatrixDirty} early return at ip 0-15 and the rebuild
     * whose {@code setPerspective} call is built at ip 62-87 and copied out
     * at ip 90) - so this is a check on the plumbing, not a tolerance on a
     * transform. Kept because it is also the guard on the four divisors of
     * the recovery and the reversed-Z orientation test.
     */
    private static final float CANONICAL_EPSILON = 1.0e-5f;

    /**
     * G2 and G5's bound on "this row or column is exactly what it must
     * be". Absolute, because the quantities are pure numbers: M's bottom
     * row is {@code (0,0,0,1)} for every affine transform vanilla can
     * build, and {@code ModelViewMat}'s translation is exactly 0
     * ({@code Matrix4f.rotation}). 1e-4 is three decades of room for the
     * two divisions the recovery performs.
     */
    private static final float RIGID_EPS = 1.0e-4f;

    /**
     * G3 and G5's orthogonality bound, and it is a DERIVATION on the
     * rejection side. The nausea composite is
     * {@code N = R diag(1/g,1,1) R^T = I + (1/g - 1) v v^T} with
     * {@code g = h(f)^2} and {@code h(f) = 5/(f^2+5) - 0.04f} (renderLevel
     * ip 192-218). N is symmetric, so
     * {@code N^T N - I = (1/g^2 - 1) v v^T} and the max-ENTRY norm is
     * {@code (1/g^2 - 1) * max_ij |v_i v_j|}, whose factor is at WORST
     * {@code 1/3} (at {@code v = (1,1,1)/sqrt(3)}, the hardest v to
     * detect). Near {@code f = 0}, {@code h ~ 1 - 0.04f} so
     * {@code 1/g^2 - 1 ~ 0.16f}, and {@code 1e-4} refuses every {@code f}
     * above about {@code 1.875e-3} - where
     * {@code 1/g = sqrt(1 + 3 * ORTHO_EPS) = 1.00015}, a 0.015% stretch.
     * Any portal or nausea effect a player could perceive is refused by
     * more than three orders of magnitude.
     *
     * <p>On the ACCEPTANCE side this is an EXPECTATION, not a measured
     * headroom, and it must not be written as though it had one. The
     * figure it used to be extrapolated from
     * ({@code m23 = -0.99999994} at what was recorded as an identity pose)
     * belongs to a frame carrying a REAL rotation of
     * {@code acos(-nextafter(1,0)) = 0.0198 degrees}: JOML's
     * {@code Matrix4f.mul} short-circuits at ip 15-31
     * ({@code if (right.properties() & PROPERTY_IDENTITY) return
     * dest.set(this)}), so an exactly-identity pose reproduces P bit for
     * bit. {@code TerrainOcclusion.halfResMaxArmedOrtho} is the
     * measurement that settles this constant.</p>
     */
    private static final float ORTHO_EPS = 1.0e-4f;

    /**
     * G4's bound on the recovered apex displacement, 2.5x the 0.1 blocks
     * {@code bobView}'s bytecode proves ({@code translate(sin(f pi) * b *
     * 0.5, -|cos(f pi) * b|, 0)} with {@code b} in [0, 0.1] from
     * {@code updateBob}'s {@code min(0.1F, horizontalDistance)} clamp and
     * the convex {@code bob += (target - bob) * 0.4F} recurrence; the x and
     * y extremes are 90 degrees out of phase and {@code |(x,y)|} is
     * maximised at {@code t = 0}, giving {@code (0, -0.1, 0)}).
     *
     * <p>It is a refusal guard for a modded or future vanilla bob AND a
     * derivation input: {@link #INFLATE_PIXELS}'s apex term is charged at
     * THIS number rather than at the bob's 0.1, because this is the bound
     * the gate actually enforces.</p>
     */
    private static final float TRANSLATE_MAX = 0.25f;

    /**
     * The floor of the orthogonality half of
     * {@code halfResArmedTransformedFrames}. It is NOT separating signal
     * from noise at rest: at an identity-valued pose the recovered L is
     * EXACTLY I, by the same {@code mulPerspectiveAffine} algebra that
     * makes {@code ||t||} exactly {@code 0.0f} there
     * ({@code D.m10 = P.m00 * M.m10 = m00 * 0 = 0}). It exists so that the
     * rounding residue of a recovered REAL rotation is not counted twice,
     * on top of the {@code ||t|| > 0f} term.
     */
    private static final float TRANSFORMED_ORTHO_EPS = 1.0e-6f;

    /**
     * The translation twin of {@link #TRANSFORMED_ORTHO_EPS}, and it exists
     * because comparing against exact zero was measuring the harness.
     *
     * <p>Until 2026-09-18 this arm read {@code tf > 0f} while the ortho arm
     * beside it already had an epsilon - an asymmetry, not a decision. The
     * comment on {@code halfResArmProjectionMismatches} had already recorded
     * why exact comparisons are wrong here ("the +/-0.0f algebra of
     * mulPerspectiveAffine can make it increment on every armed frame even
     * at a pinned pose"); the same hazard applies to the recovered
     * translation, and this arm was simply missed.
     *
     * <p>It surfaced when the Sodium indirect-ring fix shifted frame pacing
     * by a few frames: 97_19's stationary control went from 0 of 120 armed
     * frames "transformed" to 120 of 120, and instrumenting the window
     * printed the culprit - a recovered {@code ||t||} of <b>1.4E-45</b>,
     * which is {@code Float.MIN_VALUE}, the smallest positive denormal. That
     * is not a displacement; a real view bob is about 0.1 blocks, and this
     * project's own derivation bounds it at exactly 0.1.
     *
     * <p>1e-6 blocks sits roughly 10^39 above the noise and 10^5 below the
     * smallest bob the leg cares about, so it cannot hide a real transform
     * and cannot admit a denormal. Same value as the ortho arm, for the same
     * reason.
     */
    private static final float TRANSFORMED_TRANSLATION_EPS = 1.0e-6f;

    /** The stash serial of the last frame this target armed; see G0. */
    private long lastArmedPreBobSerial;

    /**
     * One report per session, naming the CHECK that failed and printing the
     * RECOVERED numbers rather than raw off-diagonals. The first version
     * printed "off-diagonals present={}" with a null CHECK as the argument,
     * so it always said true and never said which term was out of band -
     * which is why the first red run needed a second look to find m23
     * (2026-09-15).
     */
    private void warnRefused(String check, String detail) {
        if (armWarned) {
            return;
        }
        armWarned = true;
        MesheliumLog.LOGGER.warn(
                "Meshelium half-res occlusion depth: the arm refused a frame at {}: {}. "
                        + "Running full-res this frame and counting the skip (first and only "
                        + "report; the per-reason counters keep counting).", check, detail);
    }

    /** Queue both attachments on vanilla's deferred-destroy rotation. */
    void destroy() {
        closeAll();
    }

    /**
     * Destroy DIRECTLY, bypassing the deferred queue - legal only at device
     * close, after vanilla's waitIdle, when the destroy queue is drained.
     */
    void destroyNow() {
        if (colorView instanceof VulkanGpuTextureView v) {
            v.destroy();
        }
        if (depthView instanceof VulkanGpuTextureView v) {
            v.destroy();
        }
        if (color instanceof VulkanGpuTexture t) {
            t.destroy();
        }
        if (depth instanceof VulkanGpuTexture t) {
            t.destroy();
        }
        forget();
    }

    private void closeAll() {
        closeQuietly(colorView);
        closeQuietly(color);
        closeQuietly(depthView);
        closeQuietly(depth);
        forget();
    }

    private void forget() {
        color = null;
        depth = null;
        colorView = null;
        depthView = null;
        srcW = 0;
        srcH = 0;
        halfW = 0;
        halfH = 0;
        TerrainOcclusion.halfResWidth = 0;
        TerrainOcclusion.halfResHeight = 0;
    }

    /** A second failure while cleaning up must not mask the first. */
    private static void closeQuietly(AutoCloseable o) {
        if (o == null) {
            return;
        }
        try {
            o.close();
        } catch (Throwable ignored) {
            // Deliberate: this runs on the failure path of ensure().
        }
    }
}
