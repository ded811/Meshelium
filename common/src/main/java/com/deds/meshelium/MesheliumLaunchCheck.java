// Copyright (C) 2026 ded811
// SPDX-License-Identifier: LGPL-3.0-or-later
// This file is part of Meshelium.

package com.deds.meshelium;

import java.lang.management.ManagementFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * One start-up check: did the launcher pass the Java setting this
 * Minecraft's launch profile asks for?
 *
 * <p>Minecraft 26.3's launch profile (Mojang's {@code 26.3.json},
 * {@code arguments.jvm}) adds {@code -XX:StackShadowPages=32} to every
 * launch, with no platform rule; 26.2's profile does not, although 26.2
 * runs on Java 25 as well. The official launcher passes it. Third-party
 * launchers that build the command line from their own metadata do not,
 * and on those the game can die without a word - exit code
 * {@code 0xC0000005}, no crash report, no {@code hs_err} file - during
 * the first resource reload, at the title screen, or when a menu opens.
 * Reported on MultiMC 0.7.0 (MultiMC/Launcher#5779) and Prism 11.1.0
 * (PrismLauncher#6073, where Windows named {@code jvm.dll} as the faulting
 * module); Prism now adds the flag through its launcher metadata
 * (PrismLauncher#6135). Reproduced on 2026-09-20 on the owner's machine
 * with Meshelium removed, and it stopped once the flag was added. It is
 * not a Meshelium problem, but the first thing a player sees is Meshelium
 * in the mods list, so the mod says what is wrong.
 *
 * <p>Which value a Minecraft version asks for is a fact about that
 * version's launch profile, so it comes from the build:
 * {@code launch_shadow_pages} in {@code versions/mc<target>.properties},
 * expanded into {@code meshelium-build.properties} and read back as
 * {@link MesheliumBuild#LAUNCH_SHADOW_PAGES}. 32 for 26.3; 0 for 26.2,
 * which switches the check off entirely, so no 26.2 player is nagged about
 * a flag their game never asked for. What is compared is the EFFECTIVE
 * value of the VM flag, read back from HotSpot, so a launcher that passes
 * a smaller number is caught too. Nothing here can throw into the caller:
 * a JVM without the diagnostic bean gets the command-line fallback, and a
 * JVM without either gets no check.
 */
public final class MesheliumLaunchCheck {

    /**
     * What this build's Minecraft launch profile passes, or 0 when the
     * profile passes nothing and the check is off.
     */
    public static final int REQUIRED_SHADOW_PAGES = MesheliumBuild.LAUNCH_SHADOW_PAGES;

    /** The first Java the flag matters on; a secondary guard only. */
    static final int FIRST_JAVA_WITH_FLAG = 25;

    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();

    private static volatile long effectiveShadowPages = -1;
    private static volatile boolean noticeOwed;
    private static volatile boolean noticeShown;

    private MesheliumLaunchCheck() {
    }

    /** Runs the check once at mod construction. Never throws. */
    public static void run() {
        try {
            if (REQUIRED_SHADOW_PAGES <= 0) {
                MesheliumLog.LOGGER.debug(
                        "Launch check: Minecraft {}'s launch profile passes no -XX:StackShadowPages; "
                                + "nothing to check", MesheliumBuild.MINECRAFT_TARGET);
                return;
            }
            int java = Runtime.version().feature();
            if (java < FIRST_JAVA_WITH_FLAG) {
                return;
            }
            long pages = readShadowPages();
            effectiveShadowPages = pages;
            if (pages < 0) {
                MesheliumLog.LOGGER.debug(
                        "Launch check: could not read -XX:StackShadowPages on this JVM; skipped");
                return;
            }
            if (pages >= REQUIRED_SHADOW_PAGES) {
                MesheliumLog.LOGGER.info(
                        "Launch check: -XX:StackShadowPages={} (Minecraft {}'s launch profile asks for {}) - fine",
                        pages, MesheliumBuild.MINECRAFT_TARGET, REQUIRED_SHADOW_PAGES);
                return;
            }
            MesheliumLog.LOGGER.warn(
                    "Your launcher started Minecraft without -XX:StackShadowPages={} (the JVM is running "
                            + "with {}). Minecraft {}'s own launch profile passes it, and without it the "
                            + "game can close without a word - no crash report, no hs_err file - at "
                            + "start-up, at the title screen, or when a menu opens. Add "
                            + "-XX:StackShadowPages={} to this instance's Java arguments. This is a launcher "
                            + "setting, not a Meshelium problem (MultiMC/Launcher#5779, "
                            + "PrismLauncher/PrismLauncher#6073); Meshelium only reports it so the closing "
                            + "stops being a mystery.",
                    REQUIRED_SHADOW_PAGES, pages, MesheliumBuild.MINECRAFT_TARGET, REQUIRED_SHADOW_PAGES);
            noticeOwed = true;
            MesheliumPlatform.onEndClientTick(MesheliumLaunchCheck::onEndTick);
        } catch (Throwable t) {
            MesheliumLog.LOGGER.debug("Launch check skipped", t);
        }
    }

    /**
     * The effective {@code StackShadowPages}, or -1 if it cannot be read.
     *
     * <p>HotSpot's diagnostic bean reports the value the VM is actually
     * running with; the command line is the fallback for a runtime that
     * does not ship the bean.
     */
    static long readShadowPages() {
        try {
            com.sun.management.HotSpotDiagnosticMXBean bean =
                    ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            if (bean != null) {
                return Long.parseLong(bean.getVMOption("StackShadowPages").getValue().trim());
            }
        } catch (Throwable t) {
            MesheliumLog.LOGGER.debug("Launch check: no HotSpot diagnostic bean, reading the command line", t);
        }
        try {
            String prefix = "-XX:StackShadowPages=";
            for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (argument.startsWith(prefix)) {
                    return Long.parseLong(argument.substring(prefix.length()).trim());
                }
            }
        } catch (Throwable t) {
            MesheliumLog.LOGGER.debug("Launch check: could not read the JVM arguments", t);
        }
        return -1;
    }

    /** Once, at the title screen: a toast, mirrored into chat so it can be read later. */
    private static void onEndTick(Minecraft minecraft) {
        if (!noticeOwed || noticeShown || minecraft == null || minecraft.gui == null) {
            return;
        }
        if (!(minecraft.gui.screen() instanceof TitleScreen)) {
            return;
        }
        noticeShown = true;
        MesheliumNotify.error(TOAST_ID,
                Component.translatable("meshelium.toast.launch.title"),
                Component.translatable("meshelium.toast.launch.shadow",
                        REQUIRED_SHADOW_PAGES, effectiveShadowPages));
    }

    /** Harness probe: the value the check read, or -1. */
    public static long effectiveShadowPages() {
        return effectiveShadowPages;
    }

    /** Harness probe: did the check decide the launcher is missing the flag? */
    public static boolean noticeOwed() {
        return noticeOwed;
    }
}
