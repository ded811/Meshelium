package com.deds.meshelium.neoforge;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumExtendedRd;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumPlatform;
import com.deds.meshelium.MesheliumSmokeRun;
import com.deds.meshelium.gui.MesheliumOptionsScreen;

import net.minecraft.commands.Commands;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The NeoForge entrypoint: the counterpart of {@code MesheliumClient}.
 *
 * <p>Client-only, declared through {@code dist = Dist.CLIENT}. This mod
 * replaces terrain rendering; a dedicated server never loads a line of
 * it, and saying so here is what stops NeoForge trying.
 *
 * <p>The body mirrors the Fabric entrypoint deliberately, in the same
 * order, because the order is load bearing:
 *
 * <ol>
 *   <li>Install the platform services FIRST. The next call reads config,
 *       which needs the config directory.</li>
 *   <li>Read config.</li>
 *   <li>Arm the backend gate, which registers a tick callback.</li>
 *   <li>The extended-render-distance monitor AFTER the gate, so that on
 *       the decision tick it already sees the decided state. On Fabric
 *       that works because events run in registration order; NeoForge's
 *       bus is also registration-ordered for listeners of the same
 *       priority, which these are.</li>
 * </ol>
 *
 * <p>Feature parity with the Fabric entrypoint is deliberate and
 * checked: same order, same config read, same gate, same monitor, and
 * the same {@code /meshelium} command. A feature present on one loader
 * and missing on the other is the definition of "works on my machine".
 */
@Mod(value = "meshelium", dist = Dist.CLIENT)
public final class MesheliumNeoForge {

    public MesheliumNeoForge() {
        MesheliumPlatform.install(new NeoForgePlatformServices());
        MesheliumLog.LOGGER.info(
                "Meshelium {} initializing on NeoForge (backend gate armed; the decision runs on "
                        + "the first tick where the GPU device exists)", version());
        MesheliumConfig.get();
        MesheliumGate.init();
        MesheliumExtendedRd.init();
        registerOptionsCommand();
        warnIfEarlyWindowWillBreakVulkan();
        // Does nothing unless -Dmeshelium.smokeTicks asks for it. Shared
        // with Fabric so both loaders shut down the same way under test.
        MesheliumSmokeRun.installIfRequested();
    }

    /**
     * Whether the player has already chosen the Vulkan backend, read from
     * {@code options.txt} on disk.
     *
     * <p>Read from the FILE rather than from {@code Minecraft.options},
     * because this runs during mod loading and that object does not exist
     * yet - the game's own "Graphics backend forced to..." line comes
     * seconds later. The file is the same source vanilla will read.
     *
     * <p>Returns false for anything it cannot read or does not understand,
     * including a first run with no options.txt at all. False means "do
     * not touch their config", which is the safe direction: a player who
     * has not chosen Vulkan is not heading for this crash.
     *
     * <p>Note this does not see {@code --graphicsBackend} on the command
     * line. A launcher argument overrides the option, so a player forcing
     * Vulkan that way still gets the warning rather than the fix. That is
     * a narrower case and it is not worth parsing a command line for.
     */
    private static boolean vulkanIsSelected() {
        try {
            Path options = FMLPaths.GAMEDIR.get().resolve("options.txt");
            if (!Files.isRegularFile(options)) {
                return false;
            }
            for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("preferredGraphicsBackend:")) {
                    continue;
                }
                String value = trimmed.substring("preferredGraphicsBackend:".length())
                        .replace("\"", "").trim();
                return "vulkan".equalsIgnoreCase(value);
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The mod's own version, for the start-up line - the first thing read
     * in any bug report, and the Fabric entrypoint has always logged it.
     * Falls back rather than throwing: a version string is not worth
     * failing a load over.
     *
     * <p>Through the platform service since 2026-09-14, so that this
     * line and the version the Sodium adapter shows in Sodium's options
     * screen ({@code MesheliumSodiumConfigEntry}) are one lookup; the
     * {@code ModList} body that used to live here moved into
     * {@link NeoForgePlatformServices#modVersion}. Safe to call here:
     * the services are installed on the first line of the constructor.
     */
    private static String version() {
        return MesheliumPlatform.modVersion("meshelium").orElse("(unknown version)");
    }

    /**
     * {@code /meshelium} opens the settings screen, as it does on Fabric.
     *
     * <p>The registration cannot be shared: this event carries vanilla's
     * own {@code CommandSourceStack}, so the builder is vanilla's
     * {@code Commands.literal} and there is no client-specific source to
     * ask for the client. The ACTION is shared, in
     * {@link MesheliumOptionsScreen#openFromCommand()}, so the two
     * loaders cannot drift into opening different screens.
     *
     * <p>Game bus, not the mod bus: RegisterClientCommandsEvent extends
     * the plain Event type rather than implementing IModBusEvent.
     */
    private static void registerOptionsCommand() {
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, event ->
                event.getDispatcher().register(Commands.literal("meshelium").executes(context -> {
                    MesheliumOptionsScreen.openFromCommand();
                    return 1;
                })));
    }

    /**
     * NeoForge's early loading screen and Minecraft's Vulkan backend
     * cannot both exist, and the failure is fatal and unreadable.
     *
     * <p>The early screen creates the GLFW window with an OpenGL context.
     * Minecraft then asks Vulkan for a surface on that same window, and
     * GLFW refuses: <em>"Vulkan: Window surface creation requires the
     * window to have the client API set to GLFW_NO_API"</em>, error 65540.
     * The game dies inside {@code Minecraft.<init>}, before any mod screen
     * exists, and the message box it raises then trips a second, more
     * confusing NPE on a cursor callback because the client is not
     * constructed yet. A player sees a GLFW error about drivers and is
     * told to update them, which will not help.
     *
     * <p>It is a known NeoForge issue (neoforged/NeoForge#3230) with a
     * one-line workaround, and it matters to us specifically because
     * Meshelium does nothing at all except on Vulkan: anyone installing
     * this mod on NeoForge is going to select that backend.
     *
     * <p><b>This is the safety net, not the fix.</b> The fix is
     * {@code WindowEarlyDisplayMixin}, which closes the early screen
     * during window creation and rescues the launch in progress. What
     * happens here is the fallback for the one case that cannot rescue:
     * a NeoForge build where that mixin no longer applies. Turning the
     * setting off costs a Vulkan player nothing they were going to keep -
     * the mixin closes that screen anyway - and it means a player whose
     * first launch does crash gets a working second one instead of a
     * loop. That asymmetry is the whole argument for doing it here.
     *
     * <p>Only when Vulkan is already selected. A player on OpenGL is not
     * heading for this crash and keeps their loading screen; changing a
     * NeoForge setting for someone who does not need it changed is not
     * this mod's business.
     */
    private static void warnIfEarlyWindowWillBreakVulkan() {
        boolean earlyWindow;
        try {
            earlyWindow = FMLConfig.getBoolConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL);
        } catch (Throwable t) {
            // A config we cannot read must never stop the mod loading.
            return;
        }
        if (!earlyWindow) {
            return;
        }

        // Vulkan is already selected, so this launch would crash without
        // help. The mixin is what actually helps; this only guarantees
        // that a launch it could not help is followed by one that works.
        if (vulkanIsSelected()) {
            boolean fixed = false;
            try {
                FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL, false);
                fixed = true;
            } catch (Throwable t) {
                MesheliumLog.LOGGER.warn("Meshelium could not turn NeoForge's early loading "
                        + "screen off automatically; do it by hand.", t);
            }
            if (fixed) {
                MesheliumLog.LOGGER.info(
                        "You are on the Vulkan backend, and NeoForge's early loading screen "
                                + "cannot coexist with it: that screen makes the game window an "
                                + "OpenGL one, and Vulkan cannot draw to it, so the game fails to "
                                + "start with GLFW error 65540 (NeoForge issue #3230, whose fix is "
                                + "not merged yet). Meshelium has set earlyWindowControl = false in "
                                + "config/fml.toml so this cannot bite you again, and it will also "
                                + "close that screen during start-up to rescue THIS launch. If a "
                                + "GLFW error appears anyway, the rescue did not apply on this "
                                + "NeoForge build - just start the game again and it will work.");
                return;
            }
        }

        MesheliumLog.LOGGER.warn(
                "NeoForge's early loading screen is ON, and it cannot coexist with Minecraft's "
                        + "Vulkan backend - which is the only backend Meshelium runs on. The early "
                        + "screen makes the window an OpenGL one, so Vulkan cannot create a surface "
                        + "on it, and the game dies during start-up with \"GLFW error 65540 ... "
                        + "requires the window to have the client API set to GLFW_NO_API\". The "
                        + "dialog blames your drivers; it is not your drivers. Set "
                        + "earlyWindowControl = false in config/fml.toml and start again. Known "
                        + "NeoForge issue neoforged/NeoForge#3230. If you are staying on OpenGL, "
                        + "ignore this: Meshelium is switched off there anyway.");
    }
}
