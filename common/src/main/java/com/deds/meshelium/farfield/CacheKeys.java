/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.farfield;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Cache-folder identity for the far-field store: which
 * {@code <worldKey>/<dim>} folder a session's shells file under
 * (FAR-FIELD-DESIGN.md section 3.2; seams and citations in
 * FARFIELD-VANILLA-SEAM.md section 3 "Cache keying"). W2's lifecycle
 * hooks call {@link #worldKey(Minecraft)} and {@link #dimensionKey(Level)}
 * on the game thread and hand the resulting strings to
 * {@code FarField.onWorldJoin}, so the IO thread never touches a vanilla
 * object.
 *
 * <h2>Key scheme</h2>
 * <ul>
 * <li><b>Singleplayer</b>: the level storage FOLDER name (stable across
 *     renames of the display name), taken without an accessor mixin via
 *     {@code MinecraftServer.getWorldPath(LevelResource.ROOT)} - the
 *     no-mixin alternative FARFIELD-VANILLA-SEAM.md section 3 documents.
 *     W1 may not add mixins (W2 owns the mixin registration), so the
 *     dossier's preferred {@code LevelStorageAccess.getLevelId()} route
 *     stays available to a later wave if the path route ever proves
 *     insufficient.</li>
 * <li><b>Multiplayer</b>: the sanitized server address. Realms get their
 *     own "realms" bucket ({@code ServerData.isRealm()}); LAN addresses
 *     are ephemeral, so LAN worlds churn keys - accepted for now, per
 *     the dossier's note. The dossier's biome-zoom-seed sub-key for
 *     multi-world servers is deliberately NOT in W1: it needs an
 *     accessor mixin, so it lands with W2's mixins if wanted.</li>
 * <li><b>Dimension</b>: namespace and path of
 *     {@code Level.dimension().identifier()}, sanitized into one segment
 *     ("minecraft_overworld"), giving the store layout
 *     {@code <gamedir>/meshelium/farfield/<worldKey>/<dim>/r.X.Z.mfr}.</li>
 * </ul>
 *
 * <h2>javap citations (26.2 merged jar, house rule: every vanilla name
 * verified in the authoring session)</h2>
 * <pre>
 * Minecraft:
 *   public boolean hasSingleplayerServer();
 *   public net.minecraft.client.server.IntegratedServer getSingleplayerServer();
 *   public net.minecraft.client.multiplayer.ServerData getCurrentServer();
 * MinecraftServer (IntegratedServer extends it):
 *   public java.nio.file.Path getWorldPath(net.minecraft.world.level.storage.LevelResource);
 * LevelResource:
 *   public static final net.minecraft.world.level.storage.LevelResource ROOT;
 *   (clinit: ldc "." before the ROOT putstatic - ROOT is the "." entry,
 *    so getWorldPath(ROOT) ends in "/." and MUST be normalize()d before
 *    getFileName(), see below)
 * ServerData:
 *   public java.lang.String ip;
 *   public boolean isLan();
 *   public boolean isRealm();
 * Level:
 *   public net.minecraft.resources.ResourceKey&lt;net.minecraft.world.level.Level&gt; dimension();
 * ResourceKey (the 26.2 RENAME - there is no location() and no
 * ResourceLocation class):
 *   public net.minecraft.resources.Identifier identifier();
 * Identifier:
 *   public java.lang.String getNamespace();
 *   public java.lang.String getPath();
 * </pre>
 */
public final class CacheKeys {

    /** Fallback key when no identity is resolvable; never a path escape. */
    public static final String UNKNOWN = "unknown";

    /** Windows path budgets are finite; keys are truncated to this. */
    private static final int MAX_KEY_LENGTH = 64;

    private CacheKeys() {
    }

    /**
     * The world half of the cache key for the current session. Game
     * thread only (reads live client state). Returns {@link #UNKNOWN}
     * when neither a singleplayer server nor server data exists (should
     * not happen while a world is joined; the caller may skip the join
     * on it).
     */
    public static String worldKey(Minecraft minecraft) {
        if (minecraft.hasSingleplayerServer()) {
            IntegratedServer server = minecraft.getSingleplayerServer();
            if (server != null) {
                return singleplayerKey(server.getWorldPath(LevelResource.ROOT));
            }
        }
        ServerData data = minecraft.getCurrentServer();
        if (data == null) {
            return UNKNOWN;
        }
        if (data.isRealm()) {
            // One bucket: a realm's transport address is not a stable
            // identity the way a server address is.
            return "realms";
        }
        return sanitize(data.ip);
    }

    /**
     * The dimension half of the cache key, one folder segment:
     * "minecraft_overworld" style (the ':' of the canonical id falls to
     * the sanitizer's ':' rule).
     */
    public static String dimensionKey(Level level) {
        Identifier id = level.dimension().identifier();
        return sanitize(id.getNamespace() + ":" + id.getPath());
    }

    /**
     * Singleplayer key from the world root path. ROOT is the "." level
     * resource (javap cite in the class javadoc), so the path arrives as
     * {@code .../saves/<folder>/.} and is normalized before the folder
     * name is taken. Package-visible for a cold unit test - pure path
     * arithmetic, no client state.
     */
    static String singleplayerKey(Path worldRoot) {
        Path normalized = worldRoot.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        return name == null ? UNKNOWN : sanitize(name.toString());
    }

    /**
     * Folder-safe form of any identity string: lowercase (root locale),
     * ':' to '_' (ports, dimension ids), every character outside
     * {@code [a-z0-9._-]} dropped, truncated to {@value #MAX_KEY_LENGTH}.
     * A result that is empty or all dots (the "." / ".." traversal
     * shapes) becomes {@link #UNKNOWN}, so a sanitized key can NEVER
     * name a parent directory - {@code FarFieldStore} re-checks the same
     * grammar on its side (defense in depth around a folder we delete).
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String lower = raw.toLowerCase(Locale.ROOT).replace(':', '_');
        StringBuilder out = new StringBuilder(Math.min(lower.length(), MAX_KEY_LENGTH));
        boolean allDots = true;
        for (int i = 0; i < lower.length() && out.length() < MAX_KEY_LENGTH; i++) {
            char c = lower.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (ok) {
                out.append(c);
                allDots &= c == '.';
            }
        }
        if (out.isEmpty() || allDots) {
            return UNKNOWN;
        }
        return out.toString();
    }
}
