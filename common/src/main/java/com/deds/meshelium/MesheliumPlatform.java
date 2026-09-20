package com.deds.meshelium;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The whole of Meshelium's dependence on a mod loader, behind one
 * interface that the loader implements and installs.
 *
 * <p>Outside its entrypoint package this mod is 105 files of vanilla,
 * LWJGL and Vulkan, and it needed exactly three things from Fabric: the
 * game directory, the config directory, and an end-of-client-tick
 * callback. Those three were spread across five files, which meant a
 * shared source set could not be carved out without dragging the loader
 * in with it. They are here now, and this class names no loader at all —
 * {@link Services} is implemented once per loader, in that loader's own
 * source set, and installed before anything else runs.
 *
 * <h2>Why a holder and not a compile-time seam</h2>
 * <p>An annotation-driven seam would work and would be one fewer moving
 * part, but it hides its failure: get the wiring wrong and you find out
 * from a linkage error at some arbitrary later moment. This fails at the
 * first call with a sentence saying what was not installed. For three
 * methods, that trade is worth making.
 *
 * <h2>The ordering requirement, which is real</h2>
 * <p>{@link #install} must run before anything touches config or
 * registers a tick callback. On Fabric that is the first statement of
 * {@code MesheliumClient.onInitializeClient()}, which is comfortably
 * before the earliest other thing this mod does — the options mixin that
 * widens the render-distance range while {@code Options} is being
 * constructed, and which reads config to find its ceiling. The observed
 * start-up order in a real client log is: entrypoint line, then the
 * chunk-task ladder, then the option widening. Anything that ever runs
 * earlier than the entrypoint has to be given its own answer rather than
 * this one, and the exception below is what will say so.
 *
 * <h2>What is deliberately NOT here</h2>
 * <p>The entrypoint itself and the {@code /meshelium} command stay on
 * the loader side. They are start-up wiring rather than services asked
 * for mid-flight, they differ in shape between loaders rather than
 * merely in spelling, and nothing in shared code calls them.
 *
 * <p>There is no {@code isDevelopment()} either, and its absence is load
 * bearing. The use for one would be gating an unfinished feature to
 * development builds, and Fabric cannot answer that honestly:
 * {@code isDevelopmentEnvironment()} is a plain {@code -Dfabric.development}
 * system property any player can set from a launcher, and the obvious
 * backstop of "is the mod root a directory" fails too, because
 * fabric-loader returns the root of a ZIP FILESYSTEM for a jar mod and
 * {@code Files.isDirectory} answers true for it (obtainRootPath's own
 * bytecode). NeoForge derives the same answer by scanning a class file
 * rather than reading a property, so it can be trusted there. A question
 * one loader can only answer dishonestly does not belong behind a seam
 * that must degrade to its weakest member; that gate is a compile-time
 * constant instead.
 */
public final class MesheliumPlatform {

    /**
     * What a loader must provide. One implementation per loader, in that
     * loader's own source set.
     */
    public interface Services {

        /**
         * The game directory: the {@code .minecraft} folder, or wherever
         * the launcher pointed this instance.
         */
        Path gameDir();

        /** The config directory, where this mod's two JSON files live. */
        Path configDir();

        /**
         * Register a callback for the end of every client tick.
         *
         * <p>The callback runs on the client thread, twenty times a
         * second. Implementations add no guard of their own: both
         * callers already own their error handling, and swallowing here
         * would hide it.
         */
        void onEndClientTick(Consumer<Minecraft> callback);

        /**
         * Whether this is a development environment rather than a build a
         * player installed.
         *
         * <p><b>The two loaders answer this with very different
         * confidence, and callers must know that.</b> NeoForge derives it
         * by scanning a class file, so it cannot be forged. Fabric reads
         * the {@code fabric.development} system property, which any player
         * can put on a launcher command line - and the obvious backstop of
         * "is the mod root a real directory" does not help either, because
         * fabric-loader hands back the root of a ZIP FILESYSTEM for a jar
         * mod and {@code Files.isDirectory} answers true for it.
         *
         * <p>So this is fit for deciding whether to run development tools
         * and unfit for anything a player must not be able to switch on.
         * Where it gates an unfinished feature, the compile-time constant
         * is what actually ships it off, and this only reopens it for the
         * people building the thing.</p>
         */
        boolean isDevelopment();

        /**
         * Whether another mod is loaded, by its mod id.
         *
         * <p>Both loaders answer this from their own mod list, which is
         * fully populated before any client code runs, so the answer is
         * stable for the whole session and cheap to ask repeatedly.
         *
         * <p>This exists for ONE purpose: knowing when something else
         * owns terrain rendering. Meshelium replaces the terrain draw by
         * cancelling a vanilla method, and so does Sodium. Mixin returns
         * from a target as soon as any HEAD callback cancels it, so two
         * mods doing that to the same method is a silent race decided by
         * apply order, and the loser's terrain never appears. Asking the
         * loader is the only way to find out before drawing rather than
         * after.
         *
         * <p>Do not grow this into general mod sniffing. Behaviour that
         * varies by which mods are installed is behaviour nobody can
         * reproduce from a bug report.
         */
        boolean isModLoaded(String modId);

        /**
         * Whether this jar carries the Sodium render adapter (the
         * {@code sodium/} source set): the mesh-shader draw of Sodium's
         * chunks. Both loaders since 2026-09-14: Fabric compiles it
         * against the Modrinth artefact and NeoForge against the mod jar
         * unwrapped out of Sodium's jar-in-jar wrapper at build time
         * (neoforge/build.gradle, extractSodiumNeoForge). Before that it
         * was Fabric only, and the "no" branch this answer feeds stays
         * for any loader whose build omits {@code sodium/}.
         *
         * <p>Asked so the screens and the gate log can stop promising a
         * draw path that is not in the jar. Before this existed the
         * NeoForge build said "Working with Sodium" off the Vulkan device
         * alone, and nothing ever drew (owner report 2026-09-08 (beta.8)).
         */
        boolean sodiumAdapterAvailable();

        /**
         * The version string of a loaded mod, by its mod id, or empty if
         * no such mod is loaded or the loader will not say.
         *
         * <p>Added 2026-09-14 for the Sodium adapter's config entry,
         * which shows Meshelium's version in Sodium's options screen and
         * used to ask {@code FabricLoader} for it directly - one of the
         * three lines that kept the adapter out of the NeoForge jar. The
         * NeoForge entrypoint's start-up line reads the same answer, so
         * the two cannot drift.
         *
         * <p>Only ever called after the entrypoint has installed the
         * services: the config entry runs at
         * {@code Minecraft.onGameLoadFinished}, and both loaders have
         * their mod list populated before any entrypoint constructs.
         */
        Optional<String> modVersion(String modId);
    }

    private static volatile Services services;

    private MesheliumPlatform() {
    }

    /**
     * Install the loader's implementation. Called once, as early as the
     * loader allows, before any other Meshelium code runs.
     *
     * <p>Installing twice is a programming error rather than a
     * recoverable condition — it would mean two entrypoints, or one
     * running twice — so it throws rather than silently taking the last
     * writer.
     */
    public static void install(Services impl) {
        if (impl == null) {
            throw new IllegalArgumentException("Meshelium platform services must not be null");
        }
        if (services != null) {
            throw new IllegalStateException(
                    "Meshelium platform services were already installed by "
                            + services.getClass().getName() + "; a second install from "
                            + impl.getClass().getName() + " means two entrypoints ran");
        }
        services = impl;
    }

    /** The game directory. See {@link Services#gameDir()}. */
    public static Path gameDir() {
        return require().gameDir();
    }

    /** The config directory. See {@link Services#configDir()}. */
    public static Path configDir() {
        return require().configDir();
    }

    /** Register an end-of-client-tick callback. See {@link Services}. */
    public static void onEndClientTick(Consumer<Minecraft> callback) {
        require().onEndClientTick(callback);
    }

    /**
     * A development environment. Read {@link Services#isDevelopment()}
     * before trusting this for anything: on Fabric it is forgeable.
     */
    public static boolean isDevelopment() {
        return require().isDevelopment();
    }

    /**
     * Whether a mod with this id is loaded. See
     * {@link Services#isModLoaded(String)}.
     *
     * <p>Answers false rather than throwing if the loader refuses the
     * question. A wrong "no" leaves Meshelium behaving as it always has;
     * an exception thrown from the gate would take the client down.
     */
    public static boolean isModLoaded(String modId) {
        try {
            return require().isModLoaded(modId);
        } catch (Throwable t) {
            MesheliumLog.LOGGER.warn(
                    "Meshelium could not ask the loader whether '{}' is installed; assuming it is "
                            + "not. If terrain is missing and another rendering mod is present, "
                            + "this is the first thing to suspect.", modId, t);
            return false;
        }
    }

    /**
     * Whether the Sodium render adapter is compiled into this jar. See
     * {@link Services#sodiumAdapterAvailable()}. Answers false rather than
     * throwing, for the same reason {@link #isModLoaded} does: a wrong
     * "no" only makes the screens say Sodium is drawing alone.
     */
    public static boolean sodiumAdapterAvailable() {
        try {
            return require().sodiumAdapterAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * A loaded mod's version string, or empty. See
     * {@link Services#modVersion(String)}. Answers empty rather than
     * throwing, for the same reason {@link #sodiumAdapterAvailable} does:
     * a version string is never worth failing a load over.
     */
    public static Optional<String> modVersion(String modId) {
        try {
            return require().modVersion(modId);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private static Services require() {
        Services s = services;
        if (s == null) {
            throw new IllegalStateException(
                    "Meshelium asked its loader for a service before the loader installed one. "
                            + "MesheliumPlatform.install(...) must be the first thing the "
                            + "entrypoint does, and something is running earlier than the "
                            + "entrypoint - most likely a mixin that touches config while "
                            + "vanilla is still constructing Options.");
        }
        return s;
    }
}
