/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.terrain.host.TerrainResidency;

import net.minecraft.client.renderer.SectionOcclusionGraph;

import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * P2/R6: while the camera is high above the terrain, take vanilla's
 * ray-marched "advanced" culling arm out of
 * {@code SectionOcclusionGraph.runUpdates} — and <b>only</b> that arm,
 * and (since R6) only for graph nodes the camera is actually looking
 * DOWN at. See docs/FARFIELD-WAVES.md, "P2 AND P9 ANSWERED" and
 * "R6 AND R7 ANSWERED", and the argument on
 * {@link TerrainResidency#skipAdvancedRayMarch(double)}, which is where
 * the arm, the threshold, the cost and the counters live.
 *
 * <h2>Why this mixin exists at all</h2>
 * <p>O7's unlisted-live mask can only draw geometry Meshelium already
 * owns. Flying straight UP leaves every mesh where it was, so a mask is
 * enough; flying AROUND at altitude rewraps the storage square,
 * {@code RenderSection.reset()} drops the mesh, and
 * {@code LevelExtractor.extract} then never rebuilds it because it only
 * schedules compiles for sections in {@code visibleSections}
 * (docs/VANILLA-SECTION-BUILD.md §1.5.2). There is no geometry left for
 * any mask to draw — "not re-appear" — and the only cure is to stop
 * vanilla refusing to LIST them.</p>
 *
 * <h2>The target, from bytecode (26.2 merged jar, javap, re-verified
 * for R6)</h2>
 * <p>{@code runUpdates}' per-neighbour gate is three tests, all guarded
 * by {@code smartCull}: the came-from test (ip 279-297), the
 * {@code facesCanSeeEachother} source-direction test (ip 300-383), and —
 * only when {@code advanced} (local 18, computed at ip 165-230 from the
 * <b>polled node's own section</b>:
 * {@code |dx|,|dy|,|dz| > MINIMUM_ADVANCED_CULLING_SECTION_DISTANCE}
 * against the camera's section) is also true — the ray march at
 * ip 386-783. The march's start point is a CORNER of the marching
 * node's section: {@code sectionToBlockCoord} of its section coords
 * (ip 396-424) with a conditional +16 per axis (ip 426-616; for a
 * horizontal step with the camera above, the Y term stays at the
 * section's origin). The march is</p>
 * <pre>
 *   boolean flag2 = true;                                  // ip 653
 *   while (point.distanceSquared(camX, camY, camZ) &gt; 3600) // ip 656-677
 *       … step CEILED_SECTION_DIAGONAL toward the camera;  // ip 680-687
 *         accept and break above maxY / below minY;        // ip 688-724
 *         flag2 = false and break when the step lands on a
 *         section with no node yet …                       // ip 752-772
 *   if (!flag2) continue;                                  // ip 778-783
 * </pre>
 * <p>so making the FIRST evaluation of that condition false skips the
 * whole march with {@code flag2} still true, i.e. accepts the neighbour.
 * That is precisely and only what this redirect does. The came-from and
 * face-graph culls are untouched — solid interiors and unconnected caves
 * still get culled exactly as they do within three sections of the
 * camera.</p>
 *
 * <h2>The R6 refinement: the skip is keyed to vanilla's own Y flip,
 * per node</h2>
 * <p>The handler passes {@code camY - point.y} — camera height above the
 * march's start corner — into the verdict.
 * {@code skipAdvancedRayMarch} only skips when that difference reaches
 * 64 blocks, which by the corner arithmetic above is exactly vanilla's
 * own per-node Y arm ({@code |dsy| > 3}, one section stricter for a
 * down-step's +16 corner). Marches for nodes at or near camera height —
 * the ground-level geometry that has always worked — run unchanged even
 * while armed, which is what lets the arm itself fire much earlier (at
 * the FIRST resident section vanilla Y-flips, not the ninetieth
 * percentile — the R6 window) without giving up culling anywhere the
 * defect cannot occur. Along a march {@code point} steps toward the
 * camera, so the difference only shrinks: the test can fire on the
 * first evaluation or never, and a non-skipped march runs bit-identical
 * to vanilla.</p>
 *
 * <h2>S2's correction: this is vanilla's Y DISJUNCT, not vanilla's arm</h2>
 * <p>Re-read from the 26.2 jar for S2: ip 165-230 is
 * {@code advanced = |dsx| > 3 || |dsy| > 3 || |dsz| > 3} — a disjunction,
 * with branch targets 225 (true) and 229 (false) making it explicit. The
 * R6 paragraph above is right that 64 blocks reproduces the Y term
 * exactly, and wrong to read that term as "the very condition
 * {@code runUpdates} tests". At render distance 32 the HORIZONTAL term is
 * true for every section outside a 7x7 core at every altitude, so vanilla
 * has the outer disc on the marched arm — and can refuse it — a full 64
 * blocks of climb before this handler's floor opens for it. That window
 * is the owner's "brief period where its clear", it is why three
 * successive re-keyings of the ARM never moved it, and S2 closes it on
 * the DRAW side instead: a climb unloads nothing
 * ({@code RotatingSectionStorage.repositionCenter} takes Y as
 * {@code minY + k}), so the geometry is still resident and
 * {@code TerrainResidency}'s unlisted-live mask can draw it for the price
 * of a mask bit. Relaxing the march here to the full disjunct would buy
 * the same pixels in compiles, uploads and VRAM, roughly doubling the
 * listed set, and was deliberately NOT done. <b>This file is unchanged by
 * S2.</b></p>
 *
 * <h2>S6: the draw-side rule's ARM changed; this file's did not</h2>
 * <p>S2 gated its draw-side band rule on a disc-wide {@code camSy > p90}
 * and that gate is the fifth attempt's residual: over an ocean beside a
 * mountain, p90 tracks the mountain while the camera flies over the
 * water, so the gate stays shut over exactly the terrain vanilla has
 * already begun refusing. S6 replaces it with a PER-COLUMN test (the
 * resident terrain top in the section's own column) and retires the
 * statistic. <b>None of that reaches this file.</b> The arm here is still
 * {@link TerrainResidency#altitudeCullArmed()}, still R6's p10 arm, still
 * the deliberately-early "can vanilla have Y-flipped anything yet"
 * question; the per-node discrimination is still
 * {@link TerrainResidency#skipAdvancedRayMarch(double)}'s 64-block floor;
 * {@code require = 0} stands and the redirect is byte-identical. A
 * per-column key would be wrong here anyway — this handler runs on
 * {@code Util.backgroundExecutor()} during a full update, where taking the
 * residency monitor to read a census is exactly the deadlock this
 * project's discipline forbids. <b>This file is unchanged by S6.</b></p>
 *
 * <h2>pre21: the owner's rd 2, and why this file is moot there</h2>
 * <p>The seventh attempt (docs/FARFIELD-WAVES.md, "FLY-UP ANSWERED
 * (seventh attempt: the core)") found the residual at render distance 2
 * in {@code getRelativeFrom}, not in the march: ip 20-44 returns null for
 * any neighbour with {@code |camSy - sy| > viewDistance}, so at rd 2 the
 * BFS cannot reach three sections below the camera at all, and the one
 * layer between that clamp and O7's {@code dsy > 3} gate was drawn by
 * nobody. The fix is on the draw side (the band rule's core exclusion is
 * gone; O7's gate and the p10 arm key on {@code min(3, rd)}). Two things
 * follow for this file. The arm it reads,
 * {@link TerrainResidency#altitudeCullArmed()}, now rises one section
 * earlier at rd 2 and is unchanged at every rd from 3 up. And at rd 2
 * (or 3) the redirect is never reached: {@code advanced} needs some axis
 * of the polled node to exceed 3 sections from the camera's, and nothing
 * in a 5x5 (or 7x7) disc does, so the march this handler guards never
 * runs there. <b>This file is unchanged by pre21.</b></p>
 *
 * <h2>Why a redirect on JOML and not {@code @ModifyVariable} on local 18</h2>
 * <p>{@code javap -l} prints {@code runUpdates} with <b>no
 * LocalVariableTable</b>, so a boolean local is indistinguishable from an
 * int and a {@code @ModifyVariable(index = 18)} binding would be a
 * coin-flip on Mixin's frame-inferred descriptor.
 * {@code org.joml.Vector3d.distanceSquared(DDD)D} occurs <b>exactly
 * once</b> in the entire class (grep of the javap dump, ip 670), is a
 * third-party descriptor that no remap touches, and is inside this loop
 * condition and nowhere else. Its receiver is the stepped point and its
 * three arguments are the camera ({@code Vec3.x/y/z}, ip 656-670), so
 * the handler has both ends of vanilla's own Y test for free.</p>
 *
 * <h2>{@code require = 0}, and the counter that makes that honest</h2>
 * <p>A silently-unapplied fix reads exactly like a fix that did not work,
 * which is the census blind spot this project has already paid for. The
 * redirect is {@code require = 0} so a future refactor degrades to
 * vanilla behaviour instead of a crash, and
 * {@link TerrainResidency#bfsAdvancedCullRelaxed()} sits beside
 * {@link TerrainResidency#altitudeArmSweeps()} in the 5-second
 * {@code meshelium seam:} line: armed sweeps climbing with suppressions
 * at zero means THIS FILE did not bind.</p>
 *
 * <h2>Threads and dormancy</h2>
 * <p>{@code runUpdates} runs on the render thread from
 * {@code runPartialUpdate} and on {@code Util.backgroundExecutor()} from
 * {@code lambda$scheduleFullUpdate$0}, so the handler reads one volatile
 * and takes no lock. Disarmed — every OpenGL session, every
 * terrain-draw-off session, and every frame at ground level — the cost is
 * one volatile load and a predicted branch per march STEP before the
 * original call runs unchanged, and vanilla's culling is bit-identical;
 * the Y subtraction is evaluated only once the volatile says armed.</p>
 */
@Mixin(SectionOcclusionGraph.class)
abstract class SectionOcclusionGraphAltitudeMixin {

    @Redirect(
            method = "runUpdates(Lnet/minecraft/client/renderer/SectionOcclusionGraph$GraphStorage;"
                    + "Lnet/minecraft/world/phys/Vec3;Ljava/util/Queue;Z"
                    + "Ljava/util/function/Consumer;"
                    + "Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;"
                    + "Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/joml/Vector3d;distanceSquared(DDD)D"
            ),
            require = 0
    )
    private double meshelium$skipRayMarchFromAltitude(Vector3d point,
            double x, double y, double z) {
        // Volatile gate first so the disarmed march loop pays exactly what
        // it paid before R6: one load and a predicted branch. Only an
        // armed march evaluates the Y test — y is the camera's Y (the
        // second distanceSquared argument, Vec3.y at ip 662-666) and
        // point.y is the march's start corner.
        if (TerrainResidency.altitudeCullArmed()
                && TerrainResidency.skipAdvancedRayMarch(y - point.y)) {
            // 0 is not "> 3600", so the loop never runs and the march's
            // own accept flag survives: the neighbour is queued.
            return 0.0D;
        }
        return point.distanceSquared(x, y, z);
    }
}
