/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.vk.MeshShaderFeatureSets;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Minecraft 26.3 only: appends Meshelium's feature sets to the optional
 * list vanilla probes and enables on device creation
 * ({@link MeshShaderFeatureSets}).
 *
 * <p>{@code optionalFeatureSets()} builds a fresh {@code ObjectOpenHashSet}
 * of five vanilla sets on every call (bytecode ip 6-34), so replacing the
 * return value with a copy plus ours changes nothing vanilla holds on to.
 * RETURN rather than a redirect of the call site in {@code createDevice}:
 * the method has one caller and one shape, and a RETURN seam is the kind
 * this project lets be required. A failure here leaves vanilla's list
 * untouched, which is a boot with no mesh shaders, never a boot that
 * fails.
 */
@Mixin(VulkanFeatureSets.class)
abstract class VulkanFeatureSetsMixin {

    @Inject(method = "optionalFeatureSets()Ljava/util/Set;", at = @At("RETURN"), cancellable = true)
    private static void meshelium$offerMeshShaderSets(CallbackInfoReturnable<Set<FeatureSet>> cir) {
        try {
            Set<FeatureSet> withOurs = new ObjectOpenHashSet<>(cir.getReturnValue());
            withOurs.addAll(MeshShaderFeatureSets.optional());
            cir.setReturnValue(withOurs);
        } catch (Throwable t) {
            MesheliumLog.LOGGER.error(
                    "Meshelium could not offer its Vulkan feature sets; leaving vanilla device "
                            + "creation untouched (no mesh shaders this session)", t);
        }
    }
}
