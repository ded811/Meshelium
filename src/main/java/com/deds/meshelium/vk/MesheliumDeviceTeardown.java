/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.fabric.MesheliumClient;

import com.mojang.blaze3d.vulkan.VulkanDevice;

import org.lwjgl.vulkan.VkDevice;

/**
 * Wave-8 destroy sweep, the device-lifetime half. One entry point, called
 * by {@code VulkanDeviceMixin} from inside {@code VulkanDevice.close()} at
 * the exact point vanilla itself tears its pipeline cache down:
 *
 * <pre>
 * VulkanDevice.close() bytecode (26.2 jar):
 *   ip  4  checkpointExtension.close()
 *   ip 13  commandEncoder.destroy()   ← final submit + graphicsQueue
 *          .waitIdle() + destroy-queue drain (VulkanCommandEncoder.destroy
 *          bytecode ip 21/28) — after this, NOTHING Meshelium ever submitted
 *          is still executing
 *   -----  MESHELIUM INJECTS HERE (shift AFTER on the encoder destroy) -----
 *   ip 17  clearPipelineCache()       ← vanilla destroys ITS pipelines
 *   ip 24  vmaDestroyAllocator
 *   ip 32  vkDestroyDevice
 * </pre>
 *
 * So at the injection point the queue is provably idle, the deferred-
 * destroy queue has already drained every per-world buffer the dispatcher
 * dispose parked, and the {@code VkDevice} is still valid — the one window
 * where destroying device-lifetime pipelines/layouts is both legal and
 * cannot race a frame. Meshelium destroys, in order:
 *
 * <ol>
 *   <li>the hello-meshlet pipelines (wave 2's five handles, if the dev
 *       property ever armed a build);</li>
 *   <li>the three terrain pipelines + Meshelium's descriptor-set layouts
 *       (waves 4/5/7) and the two static occlusion pipelines (wave 6);</li>
 *   <li>defensively, any per-world buffers that somehow outlived their
 *       dispose hook (WARN — the normal path already freed them).</li>
 * </ol>
 *
 * <p>Everything here is render-thread ({@code Minecraft.close()} runs on
 * the client thread) and Vulkan-path-only by construction: the mixin
 * targets {@code VulkanDevice}, a class that never loads on OpenGL.</p>
 */
public final class MesheliumDeviceTeardown {

    private MesheliumDeviceTeardown() {}

    public static void onDeviceClose(VulkanDevice device) {
        VkDevice vk = device.vkDevice();
        HelloMeshletRenderer.destroyDeviceObjects(vk);
        TerrainDrawer.destroyDeviceObjects(vk);
        MesheliumTerrainPump.onDeviceClose();
        MesheliumClient.LOGGER.info(
                "Meshelium device-lifetime objects destroyed at device close (wave-8 sweep)");
    }
}
