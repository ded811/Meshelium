package com.deds.meshelium.neoforge;

import com.deds.meshelium.MesheliumPlatform;

import net.minecraft.client.Minecraft;

import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * NeoForge's answers to the three things Meshelium asks a loader for.
 *
 * <p>The sibling of {@code FabricPlatformServices}. Between them they are
 * the entire loader-specific surface of this mod: everything else, the
 * renderer, the terrain host, the far field, the mesher and all 37
 * mixins, compiles from {@code common/} without knowing which loader it
 * is on.
 *
 * <p>Package-private, like its Fabric counterpart: {@link MesheliumNeoForge}
 * installs it and nothing else should reach for it.
 */
final class NeoForgePlatformServices implements MesheliumPlatform.Services {

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isDevelopment() {
        // Unforgeable here, unlike on Fabric: FMLEnvironment derives this
        // by byte-scanning a class file rather than reading a system
        // property, so no launcher argument can turn a shipped jar into a
        // development environment.
        return !FMLEnvironment.isProduction();
    }

    @Override
    public void onEndClientTick(Consumer<Minecraft> callback) {
        // ClientTickEvent.Post is NeoForge's end-of-tick, the counterpart
        // of Fabric's END_CLIENT_TICK. It carries no client reference, so
        // the singleton is fetched here; the callback signature stays the
        // same across loaders because Fabric's does hand one over and
        // both callers want it.
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,
                event -> callback.accept(Minecraft.getInstance()));
    }
}
