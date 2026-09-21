/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.terrain.host.SectionBuildTap;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-keys a freshly built mesh to the section that owns it
 * ({@link SectionBuildTap#onMeshConstructed}), at the tail of the one
 * constructor {@code CompiledSectionMesh} has.
 *
 * <p>Minecraft 26.3's constructor gained a trailing {@code long}, the
 * compile task's start time ({@code SectionMesh.getCompileTaskStartTime}
 * is new with it); the 26.2 overlay of this mixin names the two-argument
 * descriptor. The body is identical on both.
 */
@Mixin(CompiledSectionMesh.class)
abstract class CompiledSectionMeshMixin {

    @Unique
    private static boolean meshelium$rekeyBroken;

    @Inject(
            method = "<init>(Lnet/minecraft/client/renderer/chunk/TranslucencyPointOfView;"
                    + "Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;J)V",
            at = @At("TAIL")
    )
    private void meshelium$onConstructed(TranslucencyPointOfView pointOfView,
            SectionCompiler.Results results, long compileTaskStartTimeNs, CallbackInfo ci) {
        if (meshelium$rekeyBroken || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
            return;
        }
        try {
            SectionBuildTap.onMeshConstructed(this, results);
        } catch (Throwable t) {
            meshelium$rekeyBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium mesh re-key hook failed; disabling for this session", t);
        }
    }
}
