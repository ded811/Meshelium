/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * One place to tell the player something, and the only place that decides
 * how.
 *
 * <h2>Why</h2>
 * <p>Everything the mod reported went to a toast in the corner or to the
 * log. Toasts fade after a few seconds and stack, so a player who is
 * looking at their inventory, or at the thing that just went wrong, simply
 * misses them; the log is not a player-facing surface at all. Worse, the
 * events that matter MOST were the ones with no surface whatsoever: the
 * renderer error latch, the coverage guard going passive, and the upload
 * seam standing down all logged and said nothing, and that last one runs
 * while the player is watching the world rebuild around them.</p>
 *
 * <p>Chat persists. It is scrollable, it survives the moment, and a player
 * can screenshot it into a bug report.</p>
 *
 * <h2>Threading</h2>
 * <p>{@link net.minecraft.client.gui.components.ChatComponent} is not
 * thread safe, and several callers here are not on the render thread: the
 * residency error latch and the encode drops run on chunk build workers.
 * {@code Minecraft} extends {@code ReentrantBlockableEventLoop<Runnable>}
 * (jar-verified; the method is NOT declared on {@code Minecraft} itself, so
 * javap of that class alone does not show it), whose {@code execute} runs
 * the task inline when already on the owning thread and enqueues it
 * otherwise. That is exactly the behaviour wanted, so every path marshals.
 * </p>
 *
 * <h2>The API</h2>
 * <p>26.2 moved this. There is no {@code Gui.getChat()}: the chat lives on
 * {@code Gui.hud}, which is a public final FIELD with no getter, and
 * {@code ChatComponent.addMessage} is private. The public entry point is
 * {@code addClientSystemMessage}, which needs neither a level nor a player,
 * so it also works at the title screen (the line waits in the buffer).
 * Deliberately NOT {@code LocalPlayer.sendSystemMessage}: that routes
 * through {@code ChatListener.handleSystemMessage}, which drops the message
 * outright when the player is null or chat settings forbid system messages.
 * A diagnostic the player needs is not a message a chat filter should be
 * able to eat.</p>
 */
@Environment(EnvType.CLIENT)
public final class MesheliumNotify {

    private MesheliumNotify() {
    }

    /**
     * Raise a toast AND mirror it to chat, for something that went wrong.
     *
     * <p>The toast stays because it is visible without opening anything;
     * the chat line is the copy that is still there a minute later.</p>
     */
    public static void error(SystemToast.SystemToastId id, Component title, Component body) {
        Minecraft minecraft = client();
        if (minecraft == null) {
            return;
        }
        if (minecraft.gui != null) {
            SystemToast.add(minecraft.gui.toastManager(), id, title, body);
        }
        chat(Component.translatable("meshelium.chat.line", title, body));
    }

    /**
     * Chat only, for the events that deserve to be seen but do not deserve
     * to interrupt: the seam standing down, a guard going passive.
     */
    public static void chat(Component line) {
        Minecraft minecraft = client();
        if (minecraft == null) {
            return;
        }
        // execute() is inline on the render thread and enqueued off it, so
        // this one call covers both the tick-thread and build-worker callers.
        minecraft.execute(() -> {
            try {
                if (minecraft.gui == null || minecraft.gui.hud == null) {
                    return;
                }
                minecraft.gui.hud.getChat().addClientSystemMessage(line);
            } catch (Throwable t) {
                // A diagnostic that crashes the game is worse than a
                // diagnostic nobody sees. The log still has it.
                com.deds.meshelium.fabric.MesheliumClient.LOGGER
                        .debug("Meshelium could not write a chat notice", t);
            }
        });
    }

    /**
     * The client, or null if asking for it is not allowed right now.
     *
     * <p>{@code Minecraft.getInstance()} is not a plain getter under the
     * Fabric client gametest harness: called from the gametest thread it
     * THROWS, to stop tests reaching across threads. Several callers here
     * are state machines that a test drives directly, so an unguarded
     * getInstance turns a notification into a crash in exactly the code that
     * exists to report problems calmly. Any failure to reach the client
     * means no chat line, never an exception into the caller.</p>
     */
    private static Minecraft client() {
        try {
            return Minecraft.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Chat only, from a translation key with no arguments. */
    public static void chat(String translationKey) {
        chat(Component.translatable("meshelium.chat.prefix",
                Component.translatable(translationKey)));
    }
}
