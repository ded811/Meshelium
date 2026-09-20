package com.deds.meshelium.fabric;

import com.deds.meshelium.MesheliumPlatform;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Fabric's answers to the three things Meshelium asks a loader for.
 *
 * <p>This is the only class in the mod, outside the entrypoint itself,
 * that touches a Fabric API. Everything else compiles against vanilla,
 * LWJGL and Vulkan and does not know which loader it is running on. A
 * second loader gets a sibling of this file and nothing more.
 *
 * <p>Package-private on purpose: {@link MesheliumClient} installs it and
 * nothing else should be able to reach for it. Shared code goes through
 * {@link MesheliumPlatform}.
 */
final class FabricPlatformServices implements MesheliumPlatform.Services {

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isDevelopment() {
        // Forgeable, and the interface says so: fabric-loader resolves this
        // to System.getProperty("fabric.development") != null, true for any
        // value but the literal "false". Good enough to let a developer run
        // the far-field suites; not good enough to be the only thing
        // standing between a player and an unfinished feature.
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public void onEndClientTick(Consumer<Minecraft> callback) {
        // Fabric's own functional interface takes the client, which is
        // exactly what both callers want, so this is a method reference
        // rather than a wrapper that throws the argument away.
        ClientTickEvents.END_CLIENT_TICK.register(callback::accept);
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean sodiumAdapterAvailable() {
        // sodium/ is a source set of this jar (fabric/build.gradle).
        return true;
    }

    @Override
    public Optional<String> modVersion(String modId) {
        // getModContainer -> Optional<ModContainer>; ModMetadata.getVersion
        // -> Version; getFriendlyString -> the string a player recognises
        // (fabric-loader 0.19.3, javap).
        return FabricLoader.getInstance().getModContainer(modId)
                .map(c -> c.getMetadata().getVersion().getFriendlyString());
    }
}
