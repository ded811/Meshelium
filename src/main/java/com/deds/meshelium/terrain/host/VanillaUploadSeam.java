/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.terrain.host;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.fabric.MesheliumClient;

/**
 * Whether vanilla is currently allowed to upload its own copy of the
 * terrain, and who owns the frame while it is not.
 *
 * <h2>The problem</h2>
 * <p>Meshelium's kill switch cancels vanilla's terrain DRAWS but never its
 * UPLOADS, so vanilla keeps a complete second copy of the world that
 * nothing renders. Measured on the owner's card at render distance 120,
 * that copy runs between 1.3x and 2.6x the size of Meshelium's whole
 * arena - at 2048 MiB of arena, vanilla was holding 3648 MiB. It is the
 * larger half of the terrain bill.</p>
 *
 * <h2>Why this class exists rather than a boolean</h2>
 * <p>Vanilla's copy is currently Meshelium's SAFETY NET. Every path that
 * gives up - the coverage guard tripping, a device error, the player
 * turning Meshelium off - hands the frame back to vanilla on the
 * assumption that vanilla can draw it. Suppression is precisely what makes
 * that assumption false, and a handover into empty buffers is a black
 * world, which is worse than every bug this release fixed.</p>
 *
 * <p>So the rule this class enforces is an OWNERSHIP rule, not a memory
 * optimisation:</p>
 *
 * <blockquote>While anything has been suppressed this world, Meshelium
 * keeps drawing - holes and all - from the moment of demotion until
 * vanilla is plausibly whole again. A holey Meshelium picture always beats
 * an empty vanilla one.</blockquote>
 *
 * <h2>Why the recovery is affordable</h2>
 * <p>Because the mod already clamps. Every route that hands rendering to
 * vanilla also drops the render distance to vanilla's own maximum (the
 * wave-10 clamp-back invariant), so the rebuild that has to happen is
 * sized for 32 chunks and not for 120 - roughly 65x65 columns rather than
 * 241x241. Seconds, and the player is looking at Meshelium's picture
 * throughout.</p>
 */
public final class VanillaUploadSeam {

    /** Frames vanilla must look complete, once its rebuild was seen running. */
    private static final int HANDOVER_FRAMES = 20;

    /**
     * Frames of unbroken calm that hand the frame over even though the
     * rebuild was never CAUGHT running.
     *
     * <p>The busy-then-idle test is the honest one, and on its own it
     * deadlocks. The completion signal is sampled once per client tick, at
     * 20 Hz, while a small world rebuilds in less than one tick, so the busy
     * frame simply never appears: the seam waits forever for evidence that
     * has already come and gone, Meshelium owns the frame permanently, and
     * after a coverage-guard trip that means a holey picture forever instead
     * of handing back to a whole vanilla. The gametest found this at render
     * distance 5; the owner's machine at 120 would never have shown it.</p>
     *
     * <p>So calm still counts, it just has to last a lot longer to stand in
     * for the missing evidence. This is nothing like the original bug, which
     * counted calm before the rebuild had even been ASKED for.</p>
     */
    private static final int QUIET_HANDOVER_FRAMES = 100;

    private static volatile boolean armed;
    private static volatile boolean suppressedThisWorld;
    private static volatile boolean vanillaHasGeometry = true;
    private static volatile boolean demotedThisWorld;
    private static volatile boolean pendingRebuild;
    private static volatile boolean rebuildIssued;
    private static volatile boolean sawRebuildRunning;
    private static volatile int completeFrames;
    private static volatile String demoteReason = "";
    private static volatile long suppressedSections;

    private VanillaUploadSeam() {
    }

    /**
     * True when the seam should cancel vanilla's upload for this section.
     *
     * <p>Deliberately cheap and deliberately conservative: anything unclear
     * answers false, because a false negative costs memory while a false
     * positive costs the player their world.</p>
     */
    public static boolean armed() {
        return armed;
    }

    /** True once anything was suppressed, until the world is torn down. */
    public static boolean suppressedThisWorld() {
        return suppressedThisWorld;
    }

    /**
     * Whether vanilla can be trusted to draw right now.
     *
     * <p>The single question the ownership rule turns on. True when nothing
     * was ever suppressed (the old world, unchanged), or when a rebuild has
     * since run long enough for vanilla to look whole again.</p>
     */
    public static boolean vanillaHasGeometry() {
        return vanillaHasGeometry;
    }

    /** Count of uploads cancelled this world, for the log and the harness. */
    public static long suppressedSections() {
        return suppressedSections;
    }

    public static String demoteReason() {
        return demoteReason;
    }

    /**
     * Arm the seam. Called ONLY at a point where vanilla's heaps are
     * already empty or emptying - world standup, or a render-distance
     * change, both of which invalidate every section.
     *
     * <p>Arming at any other moment is close to useless and the bytecode
     * says why: {@code UberGpuBuffer.uploadStagedAllocations} closes at most
     * one heap per call and only when that heap's allocator is COMPLETELY
     * free. In a settled world every heap has live allocations pinning it,
     * so suppressing new uploads frees nothing at all. The saving comes
     * from heaps never being committed, not from heaps being reclaimed.</p>
     */
    public static void armForNewWorld() {
        if (!MesheliumConfig.suppressVanillaUploads()) {
            return;
        }
        if (MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
            return;
        }
        armed = true;
        demotedThisWorld = false;
        demoteReason = "";
        MesheliumClient.LOGGER.info(
                "Meshelium is suppressing vanilla's duplicate terrain uploads for this world. "
                        + "Vanilla keeps a full second copy of the terrain that nothing draws, "
                        + "measured at 1.3x to 2.6x Meshelium's own arena. While this is on, "
                        + "Meshelium owns the frame even if its own picture has holes, because "
                        + "vanilla's would be empty");
    }

    /**
     * The player moved the setting while a world is loaded. Make it true
     * NOW, in whichever direction it went.
     *
     * <p>The original build wrote the config file and nothing else, on the
     * reasoning that arming mid-world saves nothing: vanilla frees a heap
     * only when its allocator is completely free, and in a settled world
     * every heap has live allocations pinning it. That reasoning was right
     * about heaps and wrong about the remedy, because the recovery path
     * built alongside it supplies exactly the missing piece.
     * {@code LevelExtractor.allChanged()} drops every section, which
     * releases every allocation, which empties every heap - and an armed
     * seam then stops them being refilled. The same call that puts vanilla
     * BACK is the call that clears it OUT.</p>
     *
     * <p>Turning it off is the more important direction and the one that
     * was actually broken: it left the seam armed, so vanilla kept being
     * suppressed by a setting the player had switched off, and the next
     * demotion handed over a world that had never been rebuilt.</p>
     */
    public static void onSettingChanged() {
        if (MesheliumConfig.suppressVanillaUploads()) {
            if (armed) {
                return;
            }
            if (MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS) {
                return;
            }
            if (!MesheliumConfig.terrainRenderingConfigured()) {
                // Meshelium is not drawing. Suppressing vanilla here would
                // leave NOBODY drawing. The seam arms when it comes back on
                // or at the next world, whichever is first.
                MesheliumClient.LOGGER.info(
                        "Meshelium will free vanilla's duplicate terrain once Meshelium terrain "
                                + "rendering is switched back on; with both off there would be "
                                + "nothing left to draw the world");
                return;
            }
            armed = true;
            demotedThisWorld = false;
            demoteReason = "";
            pendingRebuild = true;
            rebuildIssued = false;
            sawRebuildRunning = false;
            completeFrames = 0;
            com.deds.meshelium.MesheliumNotify.chat("meshelium.chat.seam.freeing");
            MesheliumClient.LOGGER.info(
                    "Meshelium is freeing vanilla's duplicate terrain copy now. Vanilla's heaps "
                            + "only close once completely empty, so every section is being dropped "
                            + "and the seam stops them coming back; the memory returns over the "
                            + "next few seconds rather than instantly");
        } else {
            if (!armed && !suppressedThisWorld) {
                return;
            }
            demote("setting turned off");
        }
    }

    /**
     * Terrain rendering came back on. If the setting is on and the seam
     * stood aside for the kill switch, this is its moment.
     */
    public static void onTerrainRenderingEnabled() {
        // The guard here used to read `!armed && !suppressedThisWorld`, which
        // is false in every scenario where suppression actually happened - so
        // this method was dead on arrival for the only case it existed to
        // serve. Coming back on after a demotion is PRECISELY when vanilla's
        // freshly rebuilt copy became a duplicate again.
        onSettingChanged();
    }

    /** Record that a section's upload was cancelled. */
    public static void noteSuppressed() {
        suppressedThisWorld = true;
        vanillaHasGeometry = false;
        suppressedSections++;
    }

    /**
     * Stop suppressing and start putting vanilla back.
     *
     * <p>Called from every path that used to hand the frame straight to
     * vanilla. The handover itself does NOT happen here: {@link
     * #vanillaHasGeometry()} stays false until a rebuild has run, and until
     * it flips the ownership rule keeps Meshelium drawing.</p>
     */
    public static void demote(String reason) {
        armed = false;
        if (!suppressedThisWorld) {
            // Nothing was ever suppressed, so vanilla is whole and the old
            // behaviour is correct as it stands.
            vanillaHasGeometry = true;
            return;
        }
        if (demotedThisWorld) {
            return; // already recovering; do not restart the clock
        }
        demotedThisWorld = true;
        demoteReason = reason;
        pendingRebuild = true;
        completeFrames = 0;
        com.deds.meshelium.MesheliumNotify.chat("meshelium.chat.seam.demoted");
        MesheliumClient.LOGGER.warn(
                "Meshelium stopped suppressing vanilla's terrain uploads ({}). Vanilla's copy is "
                        + "incomplete, so Meshelium keeps drawing until a rebuild refills it; the "
                        + "render distance clamp that usually follows makes that rebuild a 32-chunk "
                        + "job rather than a 120-chunk one", reason);
    }

    /**
     * Ask for a full terrain invalidation regardless of suppression state.
     *
     * <p>Needed because the master switch now gates Meshelium's section
     * ENCODER as well as its draw. Sections compiled while Meshelium was off
     * were never encoded, so switching it back on with the existing meshes
     * still compiled would leave Meshelium with an empty arena and no reason
     * to refill it: a world that never comes back. Every master-switch edge
     * therefore invalidates, in both directions, whether or not vanilla's
     * uploads were ever suppressed.</p>
     */
    public static void requestVanillaRebuild(String why) {
        pendingRebuild = true;
        com.deds.meshelium.MesheliumNotify.chat("meshelium.chat.swap");
        MesheliumClient.LOGGER.info(
                "Meshelium is reloading the terrain so the renderers can swap cleanly ({})", why);
    }

    /** True once, when the client tick should ask vanilla to rebuild. */
    public static boolean consumeRebuildRequest() {
        if (!pendingRebuild) {
            return false;
        }
        pendingRebuild = false;
        rebuildIssued = true;
        sawRebuildRunning = false;
        return true;
    }

    /** Put the request back if the rebuild call threw. */
    public static void reinstateRebuildRequest() {
        pendingRebuild = true;
    }

    /**
     * Progress the handover. Called once per frame with vanilla's own
     * "everything is built" signal.
     *
     * <p>That signal is {@code LevelRenderer.hasRenderedAllSections()},
     * which is only {@code dispatcher.isQueueEmpty()} - it reads true early
     * and it flickers. So it is one weak term and never the whole test: a
     * frame floor has to elapse as well.</p>
     */
    public static void noteRebuildProgress(boolean vanillaLooksComplete) {
        if (!suppressedThisWorld || vanillaHasGeometry) {
            return;
        }
        if (armed) {
            // Re-armed mid-world. The rebuild running right now is being
            // cancelled section by section, so its completion means the
            // heaps have DRAINED, never that vanilla has geometry.
            return;
        }
        // THE REBUILD MUST BE SEEN TO RUN BEFORE IT CAN BE SEEN TO FINISH.
        //
        // The completion signal is LevelRenderer.hasRenderedAllSections(),
        // which is only dispatcher.isQueueEmpty() - and in a settled world
        // that queue is ALREADY empty. The first version counted those
        // frames, decided vanilla was whole before the rebuild had even
        // been issued, and let Meshelium stop drawing into empty buffers.
        // The owner saw the ground go clear; this is that bug.
        //
        // So: nothing counts until the rebuild has been issued, and even
        // then not until the queue has been observed BUSY at least once.
        // An empty queue only means "finished" after it meant "working".
        if (!rebuildIssued) {
            return;
        }
        if (!vanillaLooksComplete) {
            sawRebuildRunning = true; // the queue filled: the rebuild is real
            completeFrames = 0;
            return;
        }
        completeFrames++;
        int floor = sawRebuildRunning ? HANDOVER_FRAMES : QUIET_HANDOVER_FRAMES;
        if (completeFrames >= floor) {
            vanillaHasGeometry = true;
            rebuildIssued = false;
            MesheliumClient.LOGGER.info(
                    "Meshelium handed the terrain frame back to vanilla after {} calm frames ({})",
                    completeFrames,
                    sawRebuildRunning
                            ? "its rebuild was seen to run and then finish"
                            : "the rebuild was asked for and never caught running, which a world "
                                    + "small enough to rebuild inside one tick will do");
        }
    }

    /** A world is going away; the next one starts from a clean sheet. */
    public static void resetForWorld() {
        armed = false;
        suppressedThisWorld = false;
        vanillaHasGeometry = true;
        demotedThisWorld = false;
        pendingRebuild = false;
        rebuildIssued = false;
        sawRebuildRunning = false;
        completeFrames = 0;
        demoteReason = "";
        suppressedSections = 0;
    }
}
