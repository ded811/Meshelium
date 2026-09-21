/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.MesheliumCpuStages;
import com.deds.meshelium.vk.SodiumTerrainDrawer;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 2026-08-18 attribution wave (instrumentation ONLY — no behaviour change,
 * ever): brackets {@code VulkanCommandEncoder.submit()} (javap-verified
 * {@code public void submit()}), whose tail is
 * {@code awaitSubmitCompletion(currentSubmitIndex - 2, 5s)} — the
 * 2-submits-in-flight timeline-semaphore throttle where a GPU-paced CPU
 * parks (frame-path doc). The frame-gap analysis concluded the static
 * bench's "unattributed" half is mostly THIS wait; the bracket turns that
 * inference into a measured series. A Vulkan-backend class, so this mixin
 * never applies on the GL path (the class never loads there);
 * {@code MesheliumCpuStages} is pure JDK either way.
 *
 * <p>2026-09-05: the RETURN hook also resets
 * {@link SodiumTerrainDrawer#onEncoderSubmit() the Sodium run table's
 * per-submit advance counter}. Still no change to vanilla's behaviour —
 * the counter only decides whether Meshelium records a pass or hands it
 * to Sodium. It is reset at RETURN, after the wait for the previous
 * submit, because that is the moment the ring's oldest slots become
 * provably free.
 */
@Mixin(VulkanCommandEncoder.class)
abstract class VulkanCommandEncoderMixin {

    @Unique
    private static long meshelium$submitT0;

    @Inject(method = "submit()V", at = @At("HEAD"))
    private void meshelium$submitHead(CallbackInfo ci) {
        if (MesheliumCpuStages.ARMED) {
            meshelium$submitT0 = System.nanoTime();
        }
    }

    @Inject(method = "submit()V", at = @At("RETURN"))
    private void meshelium$submitReturn(CallbackInfo ci) {
        SodiumTerrainDrawer.onEncoderSubmit();
        if (MesheliumCpuStages.ARMED && meshelium$submitT0 != 0) {
            MesheliumCpuStages.record(MesheliumCpuStages.STAGE_ENCODER_SUBMIT,
                    System.nanoTime() - meshelium$submitT0);
            meshelium$submitT0 = 0;
        }
    }
}
