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

    /**
     * Frames left before the arena is handed back regardless of stragglers.
     * Re-armed every time the arena stands up, so each switch-off gets a
     * fresh countdown. Generous, because the release storm has to reach us
     * through vanilla's section reset first.
     */
    private static final int DISABLED_RELEASE_FRAMES = 120;
    private static int disabledFramesLeft = DISABLED_RELEASE_FRAMES;

    private MesheliumTerrainPump() {}

    public static void afterVanillaTerrainUpload() {
        if (broken) {
            return;
        }
        try {
            if (!com.deds.meshelium.MesheliumConfig.terrainRenderingConfigured()) {
                // Meshelium is switched off. Until now that stopped only the
                // DRAW: the build tap kept encoding and this pump kept
                // uploading, so Meshelium held a complete arena copy of a
                // world it was not drawing, for the whole session. That is
                // not a swap transient, it is the steady state, and it is
                // where the doubled VRAM actually came from.
                releaseWhileDisabled();
                return;
            }
            if (gpu == null) {
                gpu = MesheliumTerrainGpu.create();
                if (gpu == null) {
                    return; // device facade not up yet; retry next frame
                }
                disabledFramesLeft = DISABLED_RELEASE_FRAMES;
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

    /**
     * Hand the arena back while the master switch is off.
     *
     * <p>Ordering is the whole of the safety here, and two of the three
     * rules cost a permanently empty world if broken:</p>
     *
     * <ul>
     *   <li>Only AFTER the invalidation has emptied us. The master-switch
     *   edge issues an {@code allChanged()}, whose release storm frees every
     *   Meshelium range and installs a new ViewArea. Destroying before that
     *   lands would strand sections that are already compiled: vanilla never
     *   recompiles them, so the build tap never re-encodes them, and
     *   Meshelium's half never returns. Hence the wait for an empty
     *   residency with no frees still in flight.</li>
     *   <li>Never {@code disposeAndReset()}, which resets the seam. See
     *   {@link TerrainResidency#disposeAndResetKeepingSeam()}.</li>
     *   <li>Idempotent. Once the arena is gone this is a null check per
     *   frame, and standup stays blocked by the same config test above, so
     *   nothing re-allocates until the switch comes back.</li>
     * </ul>
     */
    private static void releaseWhileDisabled() {
        if (gpu == null) {
            return; // already handed back, or never stood up
        }
        // KEEP PUMPING WHILE DRAINING. The release storm frees every range,
        // but those frees retire through the pump's own epoch rotation, so
        // an early return here freezes the drain at whatever it was and the
        // condition below can never come true: the arena would be held for
        // the rest of the world by the very code written to hand it back.
        // With the build tap gated off there is nothing left to upload, so
        // this call now only retires.
        TerrainResidency.pump(gpu);
        TerrainResidency.Counters c = TerrainResidency.counters();
        boolean drained = c.sectionsResident() == 0 && c.pendingFreeRanges() == 0
                && c.stagingBacklogEntries() == 0;
        if (!drained && --disabledFramesLeft > 0) {
            return; // still draining, and the deadline has not run out
        }
        // THE DEADLINE IS NOT A SHORTCUT, IT IS THE CORRECT RULE.
        //
        // Waiting for a perfectly empty store looked right and is not
        // reachable: the harness sees a handful of sections survive the
        // release storm every time, and a condition that is usually but not
        // always true means the arena is usually but not always handed back,
        // which is the same as not handing it back.
        //
        // Holding them is pointless anyway. With Meshelium switched off
        // nothing it holds can ever be drawn, and every route back on issues
        // its own invalidation, so the stragglers are re-encoded from
        // scratch. The fence safety that actually matters is not this
        // counter: it is gpu.destroy() deferring through vanilla's own
        // destroy rotation.
        if (!drained) {
            MesheliumClient.LOGGER.debug(
                    "Meshelium released its arena with {} section(s) still resident; they cannot "
                            + "be drawn while it is switched off and the switch back on rebuilds "
                            + "them", c.sectionsResident());
        }
        MesheliumTerrainGpu dying = gpu;
        gpu = null;
        TerrainResidency.Counters snapshot = TerrainResidency.disposeAndResetKeepingSeam();
        dying.destroy();
        TerrainDrawer.onDispatcherDispose();
        MesheliumClient.LOGGER.info(
                "Meshelium terrain rendering was switched off, so its {} MiB arena went back to "
                        + "the graphics card rather than sitting there holding a copy of a world "
                        + "it is not drawing. Switching it back on reloads the terrain",
                snapshot.arenaCapacityBytes() >> 20);
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
