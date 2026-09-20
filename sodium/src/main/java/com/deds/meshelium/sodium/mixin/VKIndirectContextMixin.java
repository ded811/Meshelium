/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium.mixin;

import com.deds.meshelium.sodium.MesheliumSodiumHooks;

import net.caffeinemc.mods.sodium.client.gpu.device.context.VKIndirectContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Gives Sodium's indirect-command ring enough room that it never has to
 * grow, because growing it corrupts the frame.
 *
 * <h2>The defect, from bytecode</h2>
 * <p>{@code VKIndirectContext.addCommand(int)} is, in effect:
 *
 * <pre>
 *   int offset = currentOffset;                       // saved BEFORE growth
 *   currentOffset += size;
 *   if (currentOffset &gt;= currentSize) {
 *       recreateRingBuffer(max(currentOffset, currentSize * 2));
 *   }
 *   return offset;                                    // into the NEW buffer
 * </pre>
 *
 * <p>{@code recreateRingBuffer} allocates a new {@code MappableRingBuffer}
 * and records a GPU copy of the old buffer into the new one's
 * {@code [0, lastSize)}. The caller,
 * {@code VKIndirectDrawBatch.draw}, then writes its command with a host
 * {@code memCopy} into the NEW {@code mappedView} at that returned
 * {@code offset} - and {@code offset < lastSize} whenever growth fired, so
 * the queued GPU copy later overwrites the very command just written. What
 * the following {@code drawIndexedIndirect} reads instead is whatever
 * occupied that offset in the old ring buffer: a command three frames stale,
 * or uninitialised bytes in a freshly created one. An uninitialised
 * {@code indexCount} can be up to 2^32 - one draw that grinds for seconds,
 * makes forward progress throughout, and so never trips the Windows TDR.
 *
 * <p>The same three lines carry a second, independent defect: the copy and
 * its barrier are recorded INSIDE an active render pass. The validation
 * layer says so directly (2026-09-18, forest-rd96):
 * {@code VUID-vkCmdCopyBuffer-renderpass} from
 * {@code VKIndirectContext.recreateRingBuffer}, plus ten barrier VUIDs from
 * the same call. Vanilla's {@code CommandEncoder.copyToBuffer} is the one
 * encoder entry point with no render-pass guard, which is how it gets there.
 *
 * <h2>Why Meshelium fixes somebody else's bug</h2>
 * <p>Because our players hit it: a 5-second {@code VK semaphore} timeout
 * inside Sodium's {@code Terrain} pass, reproduced five times at render
 * distance 96 with a turning camera (MEASUREMENTS.md 0v). We cannot correct
 * the logic without copying Sodium's code, which the clean-room rule
 * forbids. What we can do is remove the precondition: if the ring is large
 * enough that {@code addCommand} never crosses {@code currentSize},
 * {@code recreateRingBuffer} is never called after construction and neither
 * defect can fire.
 *
 * <h2>The size, and what it costs</h2>
 * <p>Stock is {@code INITIAL_SIZE = 512_000} bytes. A
 * {@code VkDrawIndexedIndirectCommand} is 20 bytes, so that is a budget of
 * 25,600 commands for a WHOLE frame - {@code rotate()} resets
 * {@code currentOffset} once per frame from
 * {@code RenderSectionManager.prepareRender}, so all three terrain passes
 * share it. For scale, Meshelium's own run table counted 28,207 opaque runs
 * in one rd96 frame. {@code MappableRingBuffer} allocates
 * {@code BUFFER_COUNT = 3} buffers of the size given (javap), so stock costs
 * 1.5 MB and the default here costs 12.6 MB - about 11 MB more, against the
 * 54-81 MB the record mirror alone takes at that distance.
 *
 * <p>{@code -Dmeshelium.sodium.indirectRingBytes=<n>} overrides it;
 * {@code 0} leaves Sodium's own value alone and restores the defect.
 *
 * <h2>Failure mode</h2>
 * <p>This is a {@code @ModifyConstant}, which is the injection class that
 * is FATAL when its target matches nothing - so it ships {@code require = 0,
 * expect = 0} plus an instruction-level check in
 * {@code MesheliumSodiumMixinPlugin.preApply}, which looks for the literal
 * in {@code <init>} rather than merely for the method. If a Sodium update
 * changes the constant, the injector does nothing, the check reports it, and
 * the ring is Sodium's own size again - today's behaviour, no crash.
 *
 * <h2>Clean room</h2>
 * <p>Sodium is PolyForm Shield. This mixin names one class and one integer
 * literal of Sodium's and contains no Sodium code.
 */
@Mixin(VKIndirectContext.class)
public class VKIndirectContextMixin {

    @ModifyConstant(
            method = "<init>",
            constant = @Constant(intValue = MesheliumSodiumHooks.INDIRECT_RING_STOCK_BYTES),
            require = 0,
            expect = 0)
    private int meshelium$widenIndirectRing(int stock) {
        return MesheliumSodiumHooks.indirectRingBytes(stock);
    }
}
