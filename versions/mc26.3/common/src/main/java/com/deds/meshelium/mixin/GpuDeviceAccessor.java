/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The Vulkan device behind the facade {@code RenderSystem.getDevice()}
 * hands out.
 *
 * <p>Minecraft 26.3: {@code GpuDevice} is an interface and the object
 * behind it is {@code com.mojang.renderpearl.frontend.FrontendGpuDevice},
 * whose {@code backend} field IS the {@code VulkanDevice}
 * ({@code VulkanBackend.createDevice} bytecode ip 452-456: {@code new
 * FrontendGpuDevice(new VulkanDevice(...))}). 26.2's {@code GpuDevice} was
 * a class with the same field, and the 26.2 overlay of this accessor
 * targets it; callers cast the facade to this interface either way.
 */
@Mixin(FrontendGpuDevice.class)
public interface GpuDeviceAccessor {

    @Accessor("backend")
    GpuDeviceBackend meshelium$backend();
}
