package com.deds.meshelium.neoforge;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumExtendedRd;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.MesheliumLaunchCheck;
import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumPlatform;
import com.deds.meshelium.MesheliumSmokeRun;
import com.deds.meshelium.gui.MesheliumOptionsScreen;

import net.minecraft.commands.Commands;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

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
        // Did the launcher pass the Java setting Minecraft 26.3 needs? Log +
        // one toast if not; nothing below Java 25. Shared with the other loader.
        MesheliumLaunchCheck.run();
        MesheliumExtendedRd.init();
        registerOptionsCommand();
        // Per Minecraft version: 26.2 rescues the Vulkan boot from the early
        // loading screen, 26.3 has nothing to rescue. versions/mc*/neoforge.
        EarlyWindowPolicy.apply();
        // Does nothing unless -Dmeshelium.smokeTicks asks for it. Shared
        // with Fabric so both loaders shut down the same way under test.
        MesheliumSmokeRun.installIfRequested();
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
}
