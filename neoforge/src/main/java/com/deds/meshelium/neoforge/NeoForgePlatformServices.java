package com.deds.meshelium.neoforge;

import com.deds.meshelium.MesheliumPlatform;

import net.minecraft.client.Minecraft;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * NeoForge's answers to the things Meshelium asks a loader for: the
 * game and config directories, the end-of-tick callback, whether this is
 * a development environment, whether a mod is loaded, whether the Sodium
 * adapter is in this jar, and a mod's version string.
 *
 * <p>The sibling of {@code FabricPlatformServices}. Between them they are
 * the entire loader-specific surface of this mod: everything else, the
 * renderer, the terrain host, the far field, the mesher, all 38 shared
 * mixins and the Sodium adapter, compiles from {@code common/} and
 * {@code sodium/} without knowing which loader it is on.
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

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean sodiumAdapterAvailable() {
        // sodium/ is a source set of this jar (neoforge/build.gradle,
        // since 2026-09-14: the nested mod jar is unwrapped out of Sodium's
        // jar-in-jar wrapper at build time and compiled against). Until
        // then this answered false, which is what kept the options screen
        // from claiming a draw path that was not in the jar (owner report
        // 2026-09-08 (beta.8)); the gate's "no adapter" branch stays for
        // any loader whose build omits the source set.
        return true;
    }

    @Override
    public Optional<String> modVersion(String modId) {
        // ModList.get() -> getModContainerById -> Optional<? extends
        // ModContainer>; getModInfo().getVersion() is a Maven
        // ArtifactVersion whose toString is the version string (FML
        // 11.0.16, javap). ModList exists by the time the @Mod constructor
        // installs this (ModLoader.gatherAndInitializeMods precedes mod
        // construction), which is the earliest anything can ask.
        return ModList.get().getModContainerById(modId)
                .map(c -> c.getModInfo().getVersion().toString());
    }
}
