package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.terrain.host.TerrainResidency;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * Wave-3b lifetime hook, shopping-list row 3: {@code releaseSectionMesh}
 * HEAD — the ONE private method every per-mesh free in the game funnels
 * through (Q3.4's complete caller census: rebuild replacement, cancelled
 * compile, slot eviction via reset(), releaseAllBuffers on renderer
 * reload). Runs on worker OR render thread, always under vanilla's
 * {@code copyLock} (doc 1.6); {@link TerrainResidency} takes Meshelium's
 * lock INSIDE it (innermost — the 5.3 deadlock discipline) and only parks
 * CPU-side state: the arena range waits out the frame fence in an epoch,
 * GPU buffers are untouched here.
 *
 * <p>Sentinel meshes (UNCOMPILED/EMPTY) and meshes Meshelium never encoded
 * fall through as no-ops inside the store. Loads on both backends; gate
 * first.</p>
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class RenderSectionMixin {

    @Unique
    private static boolean meshelium$freeHookBroken;

    @Unique
    private static boolean meshelium$resortHookBroken;

    @Unique
    private static boolean meshelium$resetHookBroken;

    /**
     * Wave-11 discrimination bracket: {@code reset()} is the ONLY
     * slot-revocation path in the game — jar-wide census (wave-11 note in
     * docs/VANILLA-SECTION-BUILD.md): its callers are exactly
     * {@code setSectionNode} (grid reposition; reset runs BEFORE the node
     * field is overwritten, ip 1 vs 4-6) and
     * {@code ViewArea.releaseAllBuffers()} (renderer reload / render
     * distance change / level swap), and {@code releaseSectionMesh} inside
     * it (ip 30) is the distance-class release the retention layer keys
     * on. HEAD/RETURN bracket a thread-local depth in
     * {@link TerrainResidency}; a release that arrives inside the bracket
     * is RETAINED (config-gated) instead of freed. RETURN does not fire if
     * vanilla's reset throws — the residency pump zeroes the render
     * thread's depth every frame, so the failure direction is a transient
     * over-retention, which pressure eviction bounds.
     */
    @Inject(method = "reset()V", at = @At("HEAD"))
    private void meshelium$onResetHead(CallbackInfo ci) {
        if (meshelium$resetHookBroken || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
            return;
        }
        try {
            TerrainResidency.beginSlotReset();
        } catch (Throwable t) {
            meshelium$resetHookBroken = true;
            MesheliumClient.LOGGER.error(
                    "Meshelium reset bracket failed; retention degrades to plain frees "
                            + "for this session", t);
        }
    }

    @Inject(method = "reset()V", at = @At("RETURN"))
    private void meshelium$onResetReturn(CallbackInfo ci) {
        if (meshelium$resetHookBroken || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
            return;
        }
        try {
            TerrainResidency.endSlotReset();
        } catch (Throwable t) {
            meshelium$resetHookBroken = true;
            MesheliumClient.LOGGER.error(
                    "Meshelium reset bracket failed; retention degrades to plain frees "
                            + "for this session", t);
        }
    }

    @Inject(
            method = "releaseSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)V",
            at = @At("HEAD")
    )
    private void meshelium$onReleaseSectionMesh(SectionMesh mesh, CallbackInfo ci) {
        if (meshelium$freeHookBroken || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
            return;
        }
        try {
            TerrainResidency.onMeshReleased(mesh);
        } catch (Throwable t) {
            meshelium$freeHookBroken = true;
            MesheliumClient.LOGGER.error(
                    "Meshelium mesh free hook failed; disabling for this session "
                            + "(residency will over-retain until dispose)", t);
        }
    }

    /**
     * Wave-7 resort tap — section-build shopping-list row 7, filtered to
     * EXACTLY the calls the primary compile tap is structurally blind to
     * (recon Q4.2): {@code ResortTransparencyTask.doTask}'s hand-off is the
     * ONLY caller that passes {@code vertexBuffer == null} (bytecode ip
     * 167-187: {@code addSectionBuffersToUberBuffer(TRANSLUCENT, mesh,
     * null, result.byteBuffer())}), so the filter below is the resort
     * detector. Fires on a build worker OR the render thread, under
     * vanilla's {@code copyLock}; re-fires on every spin-retry while
     * vanilla's staging is full — {@link TerrainResidency#onTranslucentResort}
     * dedupes by CONTENT (the decoded order equals the applied order after
     * the first application), because {@code byteBuffer()} returns a fresh
     * view per retry (ip 179-184) and identity can't be trusted.
     */
    @Inject(
            method = "addSectionBuffersToUberBuffer(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;"
                    + "Lnet/minecraft/client/renderer/chunk/CompiledSectionMesh;"
                    + "Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)Z",
            at = @At("HEAD")
    )
    private void meshelium$onTranslucentResortUpload(ChunkSectionLayer layer, CompiledSectionMesh mesh,
            ByteBuffer vertexBuffer, ByteBuffer indexBuffer, CallbackInfoReturnable<Boolean> cir) {
        if (meshelium$resortHookBroken
                || layer != ChunkSectionLayer.TRANSLUCENT
                || vertexBuffer != null
                || indexBuffer == null
                || MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
            return;
        }
        try {
            TerrainResidency.onTranslucentResort(mesh, indexBuffer);
        } catch (Throwable t) {
            meshelium$resortHookBroken = true;
            MesheliumClient.LOGGER.error(
                    "Meshelium resort tap failed; disabling for this session "
                            + "(translucent intra-section order will go stale as the camera moves)", t);
        }
    }
}
