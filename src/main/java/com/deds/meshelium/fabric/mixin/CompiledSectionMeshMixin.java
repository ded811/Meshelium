package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.fabric.MesheliumClient;
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
 * Wave-3b tap, shopping-list row 2: {@code CompiledSectionMesh.<init>}
 * TAIL. The ctor receives the SAME {@code Results} instance the compile
 * tap just parked (doTask ip 153-164, same thread), so this is the re-key
 * moment: Results → mesh object identity — the exact key of vanilla's own
 * uber-buffer allocationMap (Q3.1/Q4.1). Loads on both backends; gate
 * first, one volatile read on GL, nothing else.
 */
@Mixin(CompiledSectionMesh.class)
abstract class CompiledSectionMeshMixin {

    @Unique
    private static boolean meshelium$rekeyBroken;

    @Inject(
            method = "<init>(Lnet/minecraft/client/renderer/chunk/TranslucencyPointOfView;"
                    + "Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;)V",
            at = @At("TAIL")
    )
    private void meshelium$onConstructed(TranslucencyPointOfView pointOfView,
            SectionCompiler.Results results, CallbackInfo ci) {
        if (meshelium$rekeyBroken || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
            return;
        }
        try {
            SectionBuildTap.onMeshConstructed(this, results);
        } catch (Throwable t) {
            meshelium$rekeyBroken = true;
            MesheliumClient.LOGGER.error(
                    "Meshelium mesh re-key hook failed; disabling for this session", t);
        }
    }
}
