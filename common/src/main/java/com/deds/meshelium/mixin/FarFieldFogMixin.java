/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.farfield.FarFieldConfig;
import com.deds.meshelium.farfield.FarFieldResidency;
import com.deds.meshelium.vk.TerrainDrawer;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.util.Mth;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Move the fog walls out past the far field, so the far field can be seen
 * at all.
 *
 * <h2>The defect this exists to fix</h2>
 * <p>The far field draws terrain STRICTLY BEYOND vanilla's effective
 * render distance ({@code FarFieldResidency}'s wanted ring is chunk
 * Chebyshev distance in {@code [nearEdge+1, L1]}). Nothing moved the fog,
 * so {@code FogRenderer.setupFog} kept writing the render-distance ramp
 * from vanilla's horizon alone. javap, 26.2 merged jar, that method's
 * tail — the whole derivation, verbatim:</p>
 * <pre>
 *   ip   9-14  iload_2 / bipush 16 / imul / i2f  -> f = rdChunks * 16
 *   ip 118-133 fload 7 / ldc 10.0f / fdiv
 *              / ldc 4.0f / ldc 64.0f
 *              / invokestatic Mth.clamp:(FFF)F  -> band = clamp(f/10,4,64)
 *   ip 135-142 putfield FogData.renderDistanceStart:F -> f - band
 *   ip 145-149 putfield FogData.renderDistanceEnd:F   -> f
 *   ip 152-154 areturn (the same FogData built at ip 29-36)
 * </pre>
 *
 * <p>{@code FogRenderer.updateBuffer(FogData)} then copies the colour and
 * every one of the six floats into the fog UBO (javap ip 19-47: color,
 * environmentalStart, environmentalEnd, renderDistanceStart,
 * renderDistanceEnd, skyEnd, cloudEnd) — so every field written below
 * really does reach the shader.
 * {@code assets/meshelium/shaders/terrain.frag} consumes them with
 * vanilla's own fog.glsl, copied verbatim into that file:</p>
 * <pre>
 *   total_fog_value = max(linear_fog_value(spherical,   envStart, envEnd),
 *                         linear_fog_value(cylindrical, rdStart,  rdEnd))
 *   linear_fog_value returns 1.0 as soon as distance >= end
 * </pre>
 *
 * <p>Every far fragment sits at cylindrical distance greater than
 * {@code rdChunks * 16}, which was exactly {@code renderDistanceEnd}. So
 * the second term was 1.0 for the ENTIRE far field and {@code apply_fog}
 * returned pure fog colour: arena bytes, region ids, disk IO, meshing and
 * per-frame dispatch all paid, and not one texel of terrain reached the
 * screen. The far field was bit-correct and completely invisible.</p>
 *
 * <h2>What this hook changes, and why each field</h2>
 * <ul>
 *   <li>{@code renderDistanceEnd} — raised to the far ring's own radius,
 *   which is the only change that actually clears the {@code max()}
 *   above. {@code renderDistanceStart} is then RE-DERIVED with vanilla's
 *   own expression at the new end (ip 118-142 above), not scaled, so the
 *   soft band keeps vanilla's exact shape and width at the new
 *   horizon.</li>
 *   <li>{@code environmentalEnd} / {@code environmentalStart} — the
 *   OTHER half of that {@code max()}. Leaving them alone leaves a
 *   spherical haze ramp calibrated for vanilla's horizon: at render
 *   distance 32 with the far field at L1 64 the atmospheric end is still
 *   the dimension's 1024, so a fragment 900 blocks out comes back 88 per
 *   cent fog even after the render-distance ramp has been cleared.
 *   Scaling both ends by the SAME factor the horizon grew by keeps the
 *   haze at the same fraction of the horizon it had in vanilla, which is
 *   the only way to widen it without either washing the far field out or
 *   making the near field look thinner than the player set it.</li>
 *   <li>{@code skyEnd} / {@code cloudEnd} — vanilla ends the sky bowl at
 *   {@code min(horizon, SKY_FOG_END_DISTANCE)} and the clouds at
 *   {@code min(cloudRange*16, CLOUD_FOG_END_DISTANCE)}
 *   ({@code AtmosphericFogEnvironment.setupFog}, javap ip 110-134 and
 *   137-181). Push terrain haze to 1024 and leave the sky at its 512 cap
 *   and the far terrain stands in front of a nearer painted fog band —
 *   worse looking than the bug. Both are raised, the same way
 *   {@code AtmosphericFogEnvironmentMixin} raises them, and cloudEnd is
 *   never pushed past {@code cloudRange*16} because that is where the
 *   cloud sheet itself stops.</li>
 * </ul>
 *
 * <h2>Ordering against AtmosphericFogEnvironmentMixin: structural, not
 * priority</h2>
 * <p>That mixin injects at TAIL of
 * {@code AtmosphericFogEnvironment.setupFog}. This one injects at RETURN
 * of {@code FogRenderer.setupFog}. They are different target classes, so
 * mixin priority does not order them and setting one would be
 * cargo cult. The CALL GRAPH orders them, unconditionally: FogRenderer's
 * environment loop invokes {@code FogEnvironment.setupFog} at ip 99-109
 * and breaks at ip 112, while this hook's injection point is the
 * {@code areturn} at ip 154. The atmospheric mixin's writes are therefore
 * already final in the very FogData instance handed to this method, thirty
 * bytecodes earlier, on every path that reaches the return. No
 * {@code @Mixin(priority = ...)} is set, and none is needed.</p>
 *
 * <p>Against THIRD-PARTY injectors on this same method, priority would
 * not be a reliable answer either, so the writes below are shaped so
 * order does not matter. The whole body is guarded on the far ring
 * reaching past whatever end is ALREADY in the FogData, and the two
 * ends it does not own outright ({@code skyEnd}, {@code cloudEnd}) move
 * by {@code max()}. A mod that widened further first is therefore left
 * completely alone; a mod that widens after simply wins.</p>
 *
 * <h2>Zero cost when the far field was never switched on</h2>
 * <p>The invariant is that a session which never enabled the far field
 * must never LOAD {@code FarFieldResidency}. The gate order below is what
 * guarantees it:</p>
 * <ol>
 *   <li>{@link FarFieldConfig#enabled()} is checked FIRST. That class is
 *   already loaded in every session — {@code MinecraftLevelSwapMixin}
 *   calls {@code ExtractDispatch.refreshArmed()} on every
 *   {@code setLevel}, outside any gate, and that resolves
 *   {@code FarFieldConfig.enabled()} — so naming it here loads nothing
 *   new and costs one {@code System.getProperty} plus a field read, once
 *   per frame, on a class that was already resident.</li>
 *   <li>{@link FarFieldResidency} is named only AFTER that gate has
 *   passed at least once. Java resolves a constant-pool field reference
 *   lazily, at the first execution of the {@code getstatic}, and
 *   verification of {@code getstatic FarFieldResidency.farSectionsResident
 *   : Ljava/util/concurrent/atomic/LongAdder;} needs no load either
 *   (the declared descriptor is an exact match for the receiver of
 *   {@code LongAdder.sum()}). If that instruction never runs, the class
 *   never loads.</li>
 *   <li>The sticky latch {@code meshelium$farFieldSeenEnabled} mirrors
 *   the discipline TerrainResidency uses: once the switch has been
 *   observed ON, the class IS loaded and staying loaded, so the gauge
 *   stays readable after a mid-session disable. That matters — without
 *   it, flipping the far field off would snap the fog back to vanilla's
 *   horizon on the very next frame while far sections were still resident
 *   and still drawing, which is a visible pop. With it, the fog holds
 *   until the residency has actually drained.</li>
 * </ol>
 *
 * <h2>The other two gates: only when something is actually out there</h2>
 * <p>An ENABLED but EMPTY far field must not alter vanilla's look, so the
 * far resident gauge has to be non-zero before anything is written. And
 * {@code TerrainDrawer.coveragePassive()} is consulted because a tripped
 * coverage guard hands the whole frame back to vanilla — vanilla cannot
 * draw far sections, they live only in Meshelium's arena, so a pushed-out
 * fog wall in that state would open sky where the fog used to close the
 * horizon. Both are cheap reads with no side effects: a static boolean
 * and a LongAdder sum, once per frame, and only past gate 1.</p>
 *
 * <h2>Why the far end is clamped to the projection far plane</h2>
 * <p>Geometry past the projection's far plane is clipped, so fog past it
 * describes pixels that do not exist. {@code Camera.update(DeltaTracker)},
 * javap:</p>
 * <pre>
 *   ip  0-14 Options.getEffectiveRenderDistance() / bipush 16 / imul / i2f
 *   ip 15-46 fload_2 / ldc 4.0f / fmul
 *            / Options.cloudRange().get().intValue() / bipush 16 / imul
 *            / invokestatic Math.max:(FF)F
 *            / putfield depthFar:F
 *   ip 160-175 setupPerspective(0.05f, depthFar, fov, w, h)
 * </pre>
 * <p>so the far plane is {@code max(rd*64, cloudRange*16)}.
 * {@code Camera.depthFar} is private and Camera exposes no accessor for
 * it (javap: the public surface has getFov, getCullFrustum, position...,
 * and no depth getter), and the frame's
 * {@code CameraRenderState.depthFar} is only reachable through
 * {@code GameRenderer.gameRenderState}, which is private. Rather than add
 * a second mixin, this recomputes it from the SAME two inputs Camera uses:
 * the {@code int} parameter of this very method, which the call site
 * proves is the same call ({@code GameRenderer}, javap ip 44-51:
 * {@code Minecraft.options.getEffectiveRenderDistance()} pushed directly
 * as that argument), and the same {@code cloudRange} option. Both
 * derivations are one line and both are cited above; if 26.2.x ever
 * changes the formula this is a place that must change with it, which is
 * why the citation is here and not in a doc.</p>
 *
 * <h2>Corners stay foggy, on purpose</h2>
 * <p>The far ring is a Chebyshev SQUARE, so its corner columns sit at up
 * to {@code L1*16*sqrt(2)} blocks while the end written here is
 * {@code L1*16}. Those corners fade to fog before the geometry runs out.
 * That is not a shortfall, it is exactly what vanilla does with its own
 * square of chunks at its own radius, and it is what makes the horizon
 * read as a circle instead of a visible box.</p>
 *
 * <h2>Failure posture</h2>
 * <p>The body is wrapped in the house broken-latch idiom (the same shape
 * as {@code MinecraftLevelSwapMixin}): any throwable disables THIS hook
 * for the session and logs once. Fog then reverts to exactly vanilla's
 * numbers, which is a far field that is invisible again but a game that
 * still renders. Nothing here can throw into vanilla's fog path.</p>
 */
@Mixin(FogRenderer.class)
abstract class FarFieldFogMixin {

    /**
     * Fog ending closer than this is vanilla being deliberate rather than
     * vanilla running out of horizon: the Nether's dimension attributes
     * and boss fog both land at 96, heavy rain bottoms out around 768.
     * Reduced visibility is the POINT in those cases, so the atmospheric
     * ramp is left alone below this line. Same threshold and same
     * reasoning as {@code AtmosphericFogEnvironmentMixin}, which cannot be
     * shared because a {@code @Unique} constant is private to its mixin.
     */
    @Unique
    private static final float DELIBERATE_FOG_BLOCKS = 400.0f;

    /** Broken-latch: a fog failure parks this hook only, logged once. */
    @Unique
    private static boolean meshelium$farFogHookBroken;

    /**
     * Sticky: the master switch has been seen ON at least once this
     * session, so {@code FarFieldResidency} is loaded and its gauge may be
     * read even after a mid-session disable. Render-thread only, like
     * everything in this file.
     */
    @Unique
    private static boolean meshelium$farFieldSeenEnabled;

    // javap, 26.2 merged jar (verified in this authoring session):
    //   public net.minecraft.client.renderer.fog.FogData setupFog(
    //       net.minecraft.client.Camera, int,
    //       net.minecraft.client.DeltaTracker, float,
    //       net.minecraft.client.multiplayer.ClientLevel);
    //   descriptor: (Lnet/minecraft/client/Camera;I
    //       Lnet/minecraft/client/DeltaTracker;F
    //       Lnet/minecraft/client/multiplayer/ClientLevel;)
    //       Lnet/minecraft/client/renderer/fog/FogData;
    @Inject(
            method = "setupFog(Lnet/minecraft/client/Camera;I"
                    + "Lnet/minecraft/client/DeltaTracker;F"
                    + "Lnet/minecraft/client/multiplayer/ClientLevel;)"
                    + "Lnet/minecraft/client/renderer/fog/FogData;",
            at = @At("RETURN")
    )
    private void meshelium$coverFarField(Camera camera, int renderDistanceChunks,
            DeltaTracker deltaTracker, float worldDarkening, ClientLevel level,
            CallbackInfoReturnable<FogData> cir) {
        if (meshelium$farFogHookBroken) {
            return;
        }
        try {
            meshelium$widenForFarField(renderDistanceChunks, cir.getReturnValue());
        } catch (Throwable t) {
            meshelium$farFogHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium far-field fog widening failed; disabling the hook "
                            + "for this session (far terrain will render as fog)", t);
        }
    }

    /**
     * The body. Every early return leaves the FogData byte-identical to
     * what vanilla wrote.
     *
     * @param renderDistanceChunks vanilla's effective render distance in
     *     chunks — the same {@code Options.getEffectiveRenderDistance()}
     *     the caller passed and {@code Camera.update} used
     * @param data the instance about to be returned and copied into the
     *     fog UBO
     */
    @Unique
    private static void meshelium$widenForFarField(int renderDistanceChunks, FogData data) {
        if (data == null || renderDistanceChunks <= 0) {
            return;
        }

        // Gate 1, and it must be first: FarFieldConfig is warm in every
        // session, FarFieldResidency must not be named until this passes.
        if (FarFieldConfig.enabled()) {
            meshelium$farFieldSeenEnabled = true;
        } else if (!meshelium$farFieldSeenEnabled) {
            return; // the far field has never existed here; nothing loads
        }

        // Gate 2: Meshelium is the one drawing terrain, and is not sitting
        // passive behind a tripped coverage guard. Vanilla cannot draw far
        // sections at all, so a pushed fog wall in that state opens sky.
        if (!MesheliumConfig.terrainRenderingConfigured()
                || TerrainDrawer.coveragePassive()) {
            return;
        }

        // Gate 3 USED to require resident far sections, so that an empty
        // ring could not push the wall back and open bare sky where the
        // horizon used to close. OWNER DECISION (2026-08-19): the fog
        // belongs at the end of the LOD distance whenever the far field
        // is on, full stop - a wall sitting at vanilla's render distance
        // is the thing hiding the far terrain, and having it move only
        // once terrain happens to be resident makes the horizon jump.
        // The cost of the change is that a not-yet-filled ring shows sky
        // rather than fog; that is the intended look while it fills.
        if (!FarFieldConfig.enabled()) {
            return; // switched off mid-session: hand the horizon straight back
        }

        float vanillaEnd = data.renderDistanceEnd;
        if (!(vanillaEnd > 0.0f)) {
            return; // nothing sensible to scale against (NaN-safe)
        }

        // The far ring's own radius, never past where geometry is clipped.
        float farEnd = Math.min(FarFieldConfig.l1RadiusChunks() * 16.0f,
                meshelium$projectionFarPlane(renderDistanceChunks));
        if (!(farEnd > vanillaEnd)) {
            // The ring is inside vanilla's horizon (or the far plane is),
            // so vanilla's own fog already covers everything drawn.
            // NaN-safe by the same shape.
            return;
        }

        // The render-distance ramp, re-derived exactly as vanilla derives
        // it (FogRenderer.setupFog ip 118-149) at the new horizon.
        data.renderDistanceEnd = farEnd;
        data.renderDistanceStart = farEnd - Mth.clamp(farEnd / 10.0f, 4.0f, 64.0f);

        // The atmospheric ramp, scaled by the factor the horizon grew by
        // so the haze keeps the same strength at the same fraction of the
        // view. Deliberately thick fog (Nether, boss, heavy rain) is left
        // exactly where vanilla put it.
        float environmentalEnd = data.environmentalEnd;
        if (environmentalEnd >= DELIBERATE_FOG_BLOCKS) {
            float horizonScale = farEnd / vanillaEnd; // > 1 by the guard above
            data.environmentalStart *= horizonScale;
            data.environmentalEnd = environmentalEnd * horizonScale;
        }

        // The sky bowl follows the horizon; the clouds follow the horizon
        // but never go past the cloud sheet's own edge.
        data.skyEnd = Math.max(data.skyEnd, farEnd);
        data.cloudEnd = Math.max(data.cloudEnd,
                Math.min(farEnd, meshelium$cloudRangeBlocks()));
    }

    /**
     * {@code max(rd*64, cloudRange*16)} — the value
     * {@code Camera.update(DeltaTracker)} stores in its private
     * {@code depthFar} (javap ip 15-46) and feeds to
     * {@code setupPerspective} (ip 160-175). Recomputed rather than read
     * because the field is private with no accessor; see the class
     * javadoc for why that is the lesser evil here.
     */
    @Unique
    private static float meshelium$projectionFarPlane(int renderDistanceChunks) {
        float horizon = renderDistanceChunks * 16.0f;
        return Math.max(horizon * 4.0f, meshelium$cloudRangeBlocks());
    }

    /**
     * The cloud sheet's radius in blocks, {@code cloudRange * 16}. Same
     * read {@code Camera.update} (ip 20-42) and
     * {@code AtmosphericFogEnvironment.setupFog} (ip 137-159) perform.
     * Returns 0 when options are not up yet, which makes both callers
     * degrade to the render-distance term alone.
     */
    @Unique
    private static float meshelium$cloudRangeBlocks() {
        Minecraft minecraft = Minecraft.getInstance();
        Options options = minecraft != null ? minecraft.options : null;
        if (options == null) {
            return 0.0f;
        }
        Integer chunks = options.cloudRange().get();
        return chunks != null ? chunks * 16.0f : 0.0f;
    }
}
