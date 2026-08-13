/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.terrain.host.TerrainResidency;

import com.mojang.blaze3d.GpuDeviceLossException;

/**
 * Render-thread orchestrator for wave 3b's GPU residency. Called from two
 * gated mixin sites (both target classes that load on BOTH backends, so
 * the mixins early-return on OpenGL before this LWJGL-importing class can
 * ever load — the wave-1/2 class-loading discipline):
 *
 * <ul>
 *   <li>{@link #afterVanillaTerrainUpload()} — {@code LevelRenderer.render}
 *       right after {@code uploadTerrainBuffersToGpu()}, INSIDE vanilla's
 *       {@code dispatcher.lock()} window (shopping-list row 6): lazily
 *       stands the GPU side up, then runs {@link TerrainResidency#pump}.</li>
 *   <li>{@link #onDispatcherDispose()} — {@code SectionRenderDispatcher
 *       .dispose()} HEAD (row 5): snapshots the store (the leak probe),
 *       drops it, and queues every VkBuffer on vanilla's deferred-destroy
 *       rotation. Render thread — vanilla disposes from LevelRenderer
 *       teardown there.</li>
 * </ul>
 *
 * <p>Failure containment mirrors wave 2: any throwable flips
 * {@code broken}, records the error in {@link TerrainResidency}'s latch,
 * logs ONCE, and the pump goes silent for the session — a residency bug
 * must never kill the frame loop.</p>
 */
public final class MesheliumTerrainPump {

    private static boolean broken;             // render thread only
    private static MesheliumTerrainGpu gpu;      // render thread only

    private MesheliumTerrainPump() {}

    public static void afterVanillaTerrainUpload() {
        if (broken) {
            return;
        }
        try {
            if (gpu == null) {
                gpu = MesheliumTerrainGpu.create();
                if (gpu == null) {
                    return; // device facade not up yet; retry next frame
                }
            }
            TerrainResidency.pump(gpu);
        } catch (GpuDeviceLossException t) {
            // Wave-8: a lost device is not a residency bug. Latch and go
            // silent; vanilla's own next GPU call hits the same loss and
            // reports it exactly as an unmodded client would (no
            // client-side handler exists for it, jar-verified) — Meshelium
            // never crashes harder than vanilla.
            broken = true;
            TerrainResidency.recordError("pump: device lost: " + t);
            MesheliumClient.LOGGER.error(
                    "GPU device lost during Meshelium's terrain pump; residency goes passive "
                            + "(vanilla will report the loss on its own next call)", t);
        } catch (Throwable t) {
            broken = true;
            TerrainResidency.recordError("pump: " + t);
            MesheliumClient.LOGGER.error(
                    "Meshelium terrain pump failed; residency disabled for this session", t);
        }
    }

    public static void onDispatcherDispose() {
        try {
            TerrainResidency.Counters snapshot = TerrainResidency.disposeAndReset();
            if (gpu != null) {
                gpu.destroy();
                gpu = null;
            }
            // Wave-6: the occlusion stamp buffers/stats die with the
            // dispatcher too (visibility history reset on world change —
            // the deliverable-3 wiring; TerrainDrawer's stamp counter
            // keeps running so stale values can never read fresh).
            TerrainDrawer.onDispatcherDispose();
            MesheliumClient.LOGGER.info(
                    "Meshelium terrain residency dropped with the dispatcher: {} sections / {} quads "
                            + "were still resident at dispose (releaseAllBuffers should have freed "
                            + "all of them first), {} retained sections / {} quads dropped with the "
                            + "world (wave-11 policy (c): retention is per-world), {} uploads "
                            + "pending, {} frees pending",
                    snapshot.sectionsResident(), snapshot.quadsResident(),
                    snapshot.retainedSections(), snapshot.retainedQuads(),
                    snapshot.stagingBacklogEntries(), snapshot.pendingFreeRanges());
        } catch (Throwable t) {
            broken = true;
            TerrainResidency.recordError("dispose: " + t);
            MesheliumClient.LOGGER.error("Meshelium terrain dispose hook failed", t);
        }
    }

    /**
     * Wave-8 destroy sweep, defensive leg: at device close the per-world
     * GPU state should ALREADY be gone — {@code Minecraft.close()} runs
     * {@code levelRenderer.close()} (→ dispatcher dispose → {@link
     * #onDispatcherDispose}) at bytecode ip 63, long before the device
     * close at ip 128, and the deferred-destroy queue drains inside
     * vanilla's {@code VulkanCommandEncoder.destroy()}. If the dispose
     * hook never ran (its broken-latch, or an exotic shutdown), destroy
     * the buffers directly here — the queue is idle and the destroy queue
     * is already closed, so {@code destroyNow} is the only legal route.
     */
    public static void onDeviceClose() {
        if (gpu != null) {
            MesheliumClient.LOGGER.warn(
                    "Meshelium terrain residency still live at device close (the dispatcher "
                            + "dispose hook never ran); destroying buffers directly");
            try {
                gpu.destroyNow();
            } finally {
                gpu = null;
            }
        }
    }
}
