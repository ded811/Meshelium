package com.deds.meshelium.fabric.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Wave-3b accessor: {@code com.mojang.blaze3d.systems.GpuDevice} (the
 * public facade) holds its backend in {@code private final
 * GpuDeviceBackend backend} (javap-verified). On the Vulkan path that
 * object is the {@code VulkanDevice}, whose PUBLIC surface hands the pump
 * everything it needs: {@code vma()} (the shared allocator handle, seam
 * doc Q3), {@code vkDevice()}, and {@code createCommandEncoder()} → the
 * singleton {@code VulkanCommandEncoder} (frame-path Q1.2).
 *
 * <p>This replaces frame-path Q6 row 3's {@code CommandEncoder.backend()}
 * invoker with the equivalent one-field read on the device facade — the
 * same choice wave 2 made on the pass side ({@code RenderPassAccessor}).
 * The facade class loads on BOTH backends; an accessor interface is
 * structurally inert there.</p>
 */
@Mixin(GpuDevice.class)
public interface GpuDeviceAccessor {

    @Accessor("backend")
    GpuDeviceBackend meshelium$backend();
}
