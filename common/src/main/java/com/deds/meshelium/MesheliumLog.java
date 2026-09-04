package com.deds.meshelium;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod's logger, on a class that knows nothing about a mod loader.
 *
 * <p>It used to live on {@code MesheliumClient}, the Fabric entrypoint,
 * and 39 files reached through that entrypoint to get at it — 24 of them
 * from outside the {@code fabric} package entirely. That was harmless
 * while there was one loader and fatal to having two: the renderer, the
 * terrain host, the far field and the mesher are all loader-neutral code
 * that would otherwise drag {@code ClientModInitializer} along with them
 * into a shared source set.
 *
 * <p>So the logger moved and the entrypoint kept only what an entrypoint
 * should have. Nothing about the logging changed: same name, same
 * instance, same output. If a future entrypoint on another loader wants
 * to log during its own start-up, it uses this too.
 *
 * <p>Deliberately not an interface, a holder or a service lookup. A
 * logger is a constant, it is read on error paths where indirection is
 * the last thing wanted, and every one of those 39 call sites is a plain
 * static read that the JIT folds away.
 */
public final class MesheliumLog {

    /** The mod's logger. Named {@code meshelium}, as it always was. */
    public static final Logger LOGGER = LoggerFactory.getLogger("meshelium");

    private MesheliumLog() {
    }
}
