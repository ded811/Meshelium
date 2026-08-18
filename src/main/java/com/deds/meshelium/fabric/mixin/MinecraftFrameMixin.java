/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.MesheliumCpuStages;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 2026-08-18 attribution wave (instrumentation ONLY — no behaviour change,
 * ever): brackets {@code Minecraft.renderFrame(boolean)} (javap-verified).
 * Frame delta minus this span = client tick + input polling; this span
 * minus levelRender minus encoderSubmit minus extract ~= GUI + swapchain
 * acquire + blit + present. Backend-neutral vanilla class, pure-JDK
 * recorder, ARMED-gated and JIT-dead on normal runs — the wave-12
 * dormancy argument verbatim.
 */
@Mixin(Minecraft.class)
abstract class MinecraftFrameMixin {

    @Unique
    private static long meshelium$frameT0;

    @Inject(method = "renderFrame(Z)V", at = @At("HEAD"))
    private void meshelium$renderFrameHead(boolean tick, CallbackInfo ci) {
        if (MesheliumCpuStages.ARMED) {
            meshelium$frameT0 = System.nanoTime();
        }
    }

    @Inject(method = "renderFrame(Z)V", at = @At("RETURN"))
    private void meshelium$renderFrameReturn(boolean tick, CallbackInfo ci) {
        if (MesheliumCpuStages.ARMED && meshelium$frameT0 != 0) {
            MesheliumCpuStages.record(MesheliumCpuStages.STAGE_RENDER_FRAME,
                    System.nanoTime() - meshelium$frameT0);
            meshelium$frameT0 = 0;
        }
    }
}
