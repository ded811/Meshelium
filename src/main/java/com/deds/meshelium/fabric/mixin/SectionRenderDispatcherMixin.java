package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.vk.MesheliumTerrainPump;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wave-3b lifetime hook, shopping-list row 5: {@code dispose()} HEAD —
 * wholesale teardown (level change / renderer death; vanilla closes all
 * uber buffers + staging right after). Drops Meshelium's whole store and
 * queues its VkBuffers on vanilla's deferred-destroy rotation. Vanilla
 * disposes on the render thread (LevelRenderer teardown), which
 * {@code queueForDestroy} requires.
 *
 * <p>The gate check runs FIRST so {@link MesheliumTerrainPump} — a class
 * that imports LWJGL Vulkan — is never class-loaded on the OpenGL path
 * (wave-1/2 discipline).</p>
 */
@Mixin(SectionRenderDispatcher.class)
abstract class SectionRenderDispatcherMixin {

    @Unique
    private static boolean meshelium$disposeHookBroken;

    @Inject(method = "dispose()V", at = @At("HEAD"))
    private void meshelium$onDispose(CallbackInfo ci) {
        if (meshelium$disposeHookBroken
                || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
            return;
        }
        try {
            MesheliumTerrainPump.onDispatcherDispose();
        } catch (Throwable t) {
            meshelium$disposeHookBroken = true;
            MesheliumClient.LOGGER.error(
                    "Meshelium dispose hook failed outside its own guard; disabling", t);
        }
    }
}
