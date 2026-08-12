package com.deds.meshelium.fabric.mixin;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Wave-2 accessor: {@code com.mojang.blaze3d.systems.RenderPass} (the
 * public facade) holds its backend pass in {@code private final
 * RenderPassBackend backend} (javap-verified). Meshelium opens its hello
 * pass through the PUBLIC {@code CommandEncoder.createRenderPass}
 * (docs/VANILLA-FRAME-PATH.md Q3.3) and needs the backend object — a
 * {@code VulkanRenderPass} on the Vulkan path — to reach the raw
 * {@code VkCommandBuffer} via {@link VulkanRenderPassAccessor}.
 *
 * <p>The recon priced this in: "the backend object comes from
 * {@code CommandEncoder.backend()} — protected, one more trivial accessor"
 * (Q3.3 step 2). Going through the PASS's backend field instead of the
 * ENCODER's is one hop shorter and reads the exact object our pass wraps.</p>
 */
@Mixin(RenderPass.class)
public interface RenderPassAccessor {

    @Accessor("backend")
    RenderPassBackend meshelium$backend();
}
