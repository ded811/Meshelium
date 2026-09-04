/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium;

import net.minecraft.client.Minecraft;

/**
 * Run for N client ticks, then shut the game down the way the quit button
 * does. One counter, both loaders.
 *
 * <h2>What it is for</h2>
 * <p>The Fabric client-gametest API is this project's verification story,
 * and it drives its own world. What it cannot do is start the game the way
 * a launcher does — straight into a world through {@code
 * --quickPlaySingleplayer}, with no title screen in between. That path has
 * already shipped one silent bug: the backend gate used to wait for a title
 * screen, so a player who resumed their last world got no renderer at all
 * and nothing in the log to say why.
 *
 * <p>NeoForge has no client-gametest API at all, so on that loader this is
 * the only shutdown the harness has.
 *
 * <p>Paired with {@code -Pmeshelium.world=<name>} this is meant to give
 * both loaders the same check: boot exactly as a launcher would, render a
 * real world, then leave through {@code Minecraft.stop()} so teardown
 * actually runs and the residency accounting is readable in the log — how
 * many sections were still resident, how many buffers were still pending,
 * whether any error latched. A process killed on a timeout runs no
 * teardown, so it can never show a leak, and it can never show the absence
 * of one either.
 *
 * <p><b>It only half works today, and the honest half is NeoForge.</b>
 * {@code :neoforge:runClient -Pmeshelium.world=<name>} really does boot
 * into the world: the log shows the spawn area preparing, the gate
 * deciding with no title screen anywhere, and a clean teardown. The
 * equivalent Fabric run does not. The argument reaches the game — it is
 * on the launch line, and {@code --graphicsBackend} passed the same way
 * takes effect — but no integrated server ever starts and no quick-play
 * error is logged, with a freshly copied known-good world as much as with
 * the checked-in one. That is a dev-environment limitation rather than
 * anything the mod does, and it means the quick-play boot is verified
 * end-to-end on NeoForge ONLY. The gate itself is shared, loader-neutral
 * code, so the risk is small — but small is not verified, and anyone
 * claiming otherwise should reproduce it on Fabric first.
 *
 * <p>The lever is still worth having on Fabric: it gives an ordinary
 * {@code runClient} a clean shutdown, which is the only way to read the
 * teardown accounting outside the gametest suite.
 *
 * <h2>Why it lives in common/</h2>
 * <p>It was NeoForge-only, which meant Fabric's quick-play path had no
 * clean-shutdown check even though the bug it guards against was in shared
 * code. A harness that only exists on one loader tests the wrong half.
 *
 * <h2>What it is not</h2>
 * <p>It asserts nothing and takes no screenshots. It is a shutdown lever,
 * not a test framework, and calling it one would be the kind of
 * overstatement this project keeps a detector for elsewhere. Reading the
 * teardown lines it enables is still a human job.
 *
 * <h2>Cost when the property is absent</h2>
 * <p>None that survives the JIT: {@link #TICKS} is read once at class init
 * and the registration below never happens when it is zero, so no listener
 * is added and no per-tick work exists at all.
 */
public final class MesheliumSmokeRun {

    /**
     * Client ticks to run before quitting, or 0 to do nothing at all.
     * Twenty ticks is one second.
     */
    private static final int TICKS = Integer.getInteger("meshelium.smokeTicks", 0);

    private static int ticks;
    private static boolean stopping;

    private MesheliumSmokeRun() {
    }

    /** Arms the shutdown lever if the property asked for it. */
    public static void installIfRequested() {
        if (TICKS <= 0) {
            return;
        }
        MesheliumLog.LOGGER.warn(
                "Meshelium smoke run: this client will SHUT ITSELF DOWN after {} client ticks so "
                        + "that teardown runs and can be read. Set no such property in normal play.",
                TICKS);
        MesheliumPlatform.onEndClientTick(MesheliumSmokeRun::onEndTick);
    }

    private static void onEndTick(Minecraft client) {
        if (stopping || client == null) {
            return;
        }
        if (++ticks < TICKS) {
            return;
        }
        stopping = true;
        MesheliumLog.LOGGER.warn(
                "Meshelium smoke run: {} ticks reached, stopping the client. Everything logged "
                        + "after this line is teardown.", TICKS);
        // The quit button's own path, so the level unloads and the GPU
        // device closes exactly as they would for a player. schedule()
        // because stopping from inside a tick would tear down the loop
        // that is currently running.
        client.schedule(client::stop);
    }
}
