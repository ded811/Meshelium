/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.mixin.RenderPassAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import net.minecraft.client.Minecraft;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Minecraft 26.3 only: ends vanilla's open main pass around Meshelium's
 * own render passes, then reopens it onto the same target so vanilla's
 * frame carries on as if nothing had happened.
 *
 * <h2>Why this exists</h2>
 * <p>On 26.2 every terrain group was drawn in a pass of its own, so the
 * kill switch fired with no pass open and the drawer opened whatever
 * passes it needed. On 26.3 {@code LevelRenderer.addMainPass} opens ONE
 * pass on the main target and calls {@code renderGroup}, the solid
 * feature renderers, the classic translucent group, clouds and weather
 * inside it - and Sodium's {@code ChunkRenderer.render} is called from the
 * same place. Meshelium's draw cannot live inside that pass: its occlusion
 * stages render to other attachments, the Sodium mirror issues transfer
 * commands, and the frontend refuses a nested pass outright ("Close the
 * existing render pass before creating a new one!").
 *
 * <h2>What it does, step by step</h2>
 * <ol>
 * <li>Checks the pass is the one it thinks: one colour attachment that IS
 *     the main render target's colour view, plus a depth attachment. The
 *     depth view is not stored on either pass object (bytecode), which is
 *     why the target is verified rather than read back. Anything else
 *     declines, and vanilla draws that group itself.</li>
 * <li>Snapshots the state vanilla set on the pass - bound pipeline,
 *     uniform slots, last push constants ({@link MesheliumPassState}) - and
 *     the number of debug groups it has open.</li>
 * <li>Pops those debug groups, then {@code close()}s the pass: that is
 *     vanilla's own end-of-pass path ({@code FrontendRenderPass.close} runs
 *     {@code FrontendCommandEncoder.submitRenderPass}, which clears
 *     {@code isInRenderPass} and ends the Vulkan rendering instance). The
 *     command buffer is left outside any rendering, which is exactly what
 *     Meshelium's passes and transfers need.</li>
 * <li>On {@link #close()}: opens a fresh pass on the same colour and depth
 *     views with LOAD on both (the same descriptor shape vanilla used:
 *     no clear values), transplants the fresh backend into vanilla's pass
 *     wrapper (the object the level renderer is still holding), clears
 *     the wrapper's closed flag so vanilla's own {@code close()} later
 *     ends the resumed instance, replays the snapshot onto the fresh
 *     backend, and re-pushes the debug groups so vanilla's pops balance.</li>
 * </ol>
 *
 * <p>Why the replay is enough: vanilla's frontend forwards every
 * {@code setPipeline} (no rebind skip), {@code setPipeline} marks all
 * descriptors dirty, and {@code setVertexBuffer} always forwards; Meshelium
 * binds no vertex or index buffers of its own, so the only GPU state its
 * passes disturb is the pipeline, the pushed descriptors and the push
 * constants, and all three are replayed. Viewport and scissor are set by
 * the pass constructor from the same render area.
 *
 * <p>Any failure latches this class off for the session and the frame's
 * group is left to vanilla: the one inviolable rule is that Meshelium
 * never breaks a frame vanilla could finish. The failure that cannot be
 * recovered from is one AFTER the pass has been closed and BEFORE the
 * fresh one is open; the steps are ordered so that everything fallible
 * happens before the close, and the reopen is vanilla's own API.
 */
public final class MainPassSuspension implements AutoCloseable {

    private static boolean broken;
    private static long suspensions;

    private final RenderPass wrapper;
    private final RenderPassAccessor access;
    private final CommandEncoder encoder;
    private final RenderPassDescriptor descriptor;
    private final int debugGroups;
    private final VulkanRenderPipeline pipeline;
    private final Object[] uniforms;
    private final ByteBuffer constants;

    private MainPassSuspension(RenderPass wrapper, RenderPassAccessor access, CommandEncoder encoder,
            RenderPassDescriptor descriptor, int debugGroups, VulkanRenderPipeline pipeline,
            Object[] uniforms, ByteBuffer constants) {
        this.wrapper = wrapper;
        this.access = access;
        this.encoder = encoder;
        this.descriptor = descriptor;
        this.debugGroups = debugGroups;
        this.pipeline = pipeline;
        this.uniforms = uniforms;
        this.constants = constants;
    }

    /** How many times a main pass has been suspended this session (diagnostics). */
    public static long suspensions() {
        return suspensions;
    }

    /**
     * Suspends {@code pass}, vanilla's open main pass. Returns null - and
     * changes nothing - when the pass is not the main pass as described
     * above, when a previous attempt failed, or when {@code pass} is not
     * the frontend wrapper this class knows.
     */
    public static MainPassSuspension suspend(RenderPass pass) {
        if (broken || !(pass instanceof FrontendRenderPass)) {
            return null;
        }
        try {
            RenderPassAccessor access = (RenderPassAccessor) pass;
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            GpuTextureView color = main.getColorTextureView();
            GpuTextureView depth = main.getDepthTextureView();
            List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = access.meshelium$colorAttachments();
            if (color == null || depth == null || colors.size() != 1
                    || colors.get(0).textureView() != color || !access.meshelium$hasDepthAttachment()) {
                return null; // not the main pass as this class knows it
            }
            RenderPassBackend backend = access.meshelium$backend();
            if (!(backend instanceof MesheliumPassState state)) {
                return null;
            }
            VulkanRenderPipeline pipeline = state.meshelium$pipeline();
            Object[] uniforms = state.meshelium$uniformsSnapshot();
            ByteBuffer constants = state.meshelium$lastPushConstants();
            RenderPassDescriptor descriptor = new RenderPassDescriptor(
                    () -> "meshelium: main pass resumed",
                    List.of(new RenderPassDescriptor.Attachment<>(color, Optional.empty())),
                    new RenderPassDescriptor.Attachment<>(depth, OptionalDouble.empty()),
                    access.meshelium$renderArea());
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            int debugGroups = access.meshelium$pushedDebugGroups();
            // Nothing above touched the pass. From here on it is committed.
            for (int i = 0; i < debugGroups; i++) {
                pass.popDebugGroup();
            }
            pass.close();
            suspensions++;
            return new MainPassSuspension(pass, access, encoder, descriptor, debugGroups, pipeline,
                    uniforms, constants);
        } catch (Throwable t) {
            broken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium could not suspend vanilla's main render pass; the terrain kill switch "
                            + "stays off for this session and vanilla draws the terrain", t);
            return null;
        }
    }

    /** Reopens vanilla's main pass. Never throws: a failure here latches the class off. */
    @Override
    public void close() {
        try {
            RenderPass fresh = this.encoder.createRenderPass(this.descriptor);
            RenderPassBackend backend = ((RenderPassAccessor) fresh).meshelium$backend();
            this.access.meshelium$setBackend(backend);
            this.access.meshelium$setClosed(false);
            if (this.pipeline != null) {
                backend.setPipeline(this.pipeline);
                for (int slot = 0; slot < this.uniforms.length; slot++) {
                    if (this.uniforms[slot] != null) {
                        backend.setUniform(slot, this.uniforms[slot]);
                    }
                }
                if (this.constants != null) {
                    backend.pushConstants(this.constants.duplicate());
                }
            }
            for (int i = 0; i < this.debugGroups; i++) {
                this.wrapper.pushDebugGroup(() -> "meshelium: resumed");
            }
        } catch (Throwable t) {
            broken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium could not resume vanilla's main render pass after drawing terrain; "
                            + "the rest of this frame may be wrong and the kill switch stays off for "
                            + "this session", t);
        }
    }
}
