/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.MesheliumConfig;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Let the outdoor haze keep up with how far you can actually see.
 *
 * <h2>The bug, stated precisely</h2>
 * <p>26.2 fogs terrain with two ramps combined by {@code max()}:</p>
 * <ul>
 *   <li>The RENDER DISTANCE ramp, set unconditionally in
 *   {@code FogRenderer.setupFog} after every environment has run:
 *   {@code end = horizon}, {@code start = horizon - clamp(horizon/10, 4, 64)}.
 *   Healthy. Past render distance 40 it is a fixed 64-block band, which is
 *   the soft edge that hides chunks appearing.</li>
 *   <li>The ATMOSPHERIC ramp, which this class adjusts:
 *   {@code start = FOG_START_DISTANCE} (0), {@code end = FOG_END_DISTANCE}
 *   (1024). A FIXED ABSOLUTE DISTANCE. It does not scale with render
 *   distance, the Overworld does not override it, no biome overrides it,
 *   nothing animates it.</li>
 * </ul>
 *
 * <p>So at render distance 120 the horizon is 1920 blocks and the haze
 * saturates at 1024: 56 chunks that the game loads, meshes, culls, and
 * rasterises, only to paint them flat fog colour. Vanilla never had to care,
 * because vanilla stops at 32 where 1024 is far beyond the horizon. It is
 * only reachable with a mod like this one, so it is this mod's problem.</p>
 *
 * <h2>Why here and not in FogRenderer</h2>
 * <p>{@code FogRenderer.setupFog} walks FOG_ENVIRONMENTS and BREAKS on the
 * first applicable one. Atmospheric is last, and its {@code isApplicable} is
 * exactly {@code fogType == ATMOSPHERIC}, so it runs if and only if none of
 * lava, powder snow, blindness, darkness or water did. Injecting here is
 * therefore structurally incapable of touching underwater or lava fog: not
 * safe by a guard I remembered to write, safe because the code cannot reach
 * here in those states.</p>
 *
 * <p>TAIL, not HEAD, because vanilla's own rain and boss-fog branches run
 * inside this method and a HEAD change would simply be overwritten.</p>
 *
 * <h2>The one guard that is not structural</h2>
 * <p>Vanilla deliberately makes fog THICK in three places, and every one of
 * them lands here: the Nether (dimension attributes, 10 to 96 blocks), a
 * boss fight ({@code shouldCreateWorldFog}, clamped to 10 to 96), and heavy
 * rain (end pulled toward 768). Reduced visibility is the POINT in all
 * three, so:</p>
 * <ul>
 *   <li>Anything ending closer than {@value #DELIBERATE_FOG_BLOCKS} blocks is
 *   left completely alone. That covers the Nether and boss fog, which both
 *   sit at 96.</li>
 *   <li>Everything above it is SCALED rather than overwritten, so rain stays
 *   proportionally foggier than clear weather and there is no visible pop as
 *   a storm rolls in. Rain never drives the end below 768, comfortably above
 *   the threshold, so the scaling is continuous across the whole weather
 *   range.</li>
 * </ul>
 *
 * <h2>Sky and clouds move too</h2>
 * <p>Vanilla ends the sky bowl's fog at {@code min(horizon, 512)}. Pushing
 * terrain haze out to 1920 while leaving the sky at 512 gives crisp terrain
 * in front of a flat fog-coloured band, which looks worse than the problem
 * being fixed. Both are raised with the terrain.</p>
 */
@Mixin(AtmosphericFogEnvironment.class)
public abstract class AtmosphericFogEnvironmentMixin {

    /**
     * Fog ending closer than this is vanilla making a point, not vanilla
     * running out of constant. The Nether and boss fog both sit at 96; rain
     * bottoms out at 768. Anything in between would be a datapack being
     * deliberate too.
     */
    @Unique
    private static final float DELIBERATE_FOG_BLOCKS = 400.0f;

    /** OFF pushes the haze this many times past the horizon. */
    @Unique
    private static final float MESHELIUM_OFF_MULTIPLE = 4.0f;

    @Inject(
            method = "setupFog(Lnet/minecraft/client/renderer/fog/FogData;"
                    + "Lnet/minecraft/client/Camera;"
                    + "Lnet/minecraft/client/multiplayer/ClientLevel;F"
                    + "Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("TAIL")
    )
    private void meshelium$stretchHaze(FogData data, Camera camera, ClientLevel level,
            float renderDistanceBlocks, DeltaTracker deltaTracker, CallbackInfo ci) {
        MesheliumConfig.FogMode mode = MesheliumConfig.fogMode();
        if (mode == MesheliumConfig.FogMode.VANILLA) {
            return;
        }
        float end = data.environmentalEnd;
        if (!(end >= DELIBERATE_FOG_BLOCKS) || renderDistanceBlocks <= 0.0f) {
            return; // the Nether, a boss fight, or nothing sensible to scale
        }

        float target = mode == MesheliumConfig.FogMode.OFF
                ? renderDistanceBlocks * MESHELIUM_OFF_MULTIPLE
                : renderDistanceBlocks * (MesheliumConfig.fogEndPercent() / 100.0f);
        if (target <= end) {
            // Never thicker than vanilla. Below render distance 64 the
            // constant already sits past the horizon, so SCALED at 100 is a
            // no-op and a player who could reach this distance in vanilla
            // sees exactly what vanilla shows them.
            return;
        }

        float scale = target / end;
        data.environmentalStart *= scale; // keep the ramp's shape, not just its end
        data.environmentalEnd = target;

        // The sky bowl and the clouds have their own, shorter ends. Leaving
        // them behind makes terrain look like it is floating in front of a
        // painted backdrop.
        float skyTarget = Math.min(renderDistanceBlocks, target);
        data.skyEnd = Math.max(data.skyEnd, skyTarget);
        data.cloudEnd = Math.max(data.cloudEnd, skyTarget);
    }
}
