package com.deds.meshelium;

import com.deds.meshelium.fabric.MesheliumClient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Meshelium's whole config: a plain JSON file at {@code config/meshelium.json}.
 * Deliberately no config library — GSON is already on every Minecraft
 * classpath. Wave 1 shipped the three popup-persistence flags; wave 8
 * graduates the renderer itself from dev system properties to real,
 * player-facing settings with sane defaults.
 *
 * <h2>The wave-8 config matrix (precedence: property overrides config)</h2>
 * The dev/harness system properties keep their exact wave-4/5/6 semantics —
 * a property that is PRESENT (set to anything) overrides the config field;
 * an ABSENT property lets the config rule. Every resolver below is re-read
 * per frame by its call sites, so config toggles take effect next frame,
 * no restart (the properties were always live-read; the config inherits
 * that).
 *
 * <pre>
 * effective terrain rendering =
 *     VULKAN_MESH_SHADERS (the wave-1 gate — hard AND, never overridable)
 *   ∧ (meshelium.terrainDraw present ? Boolean(meshelium.terrainDraw)
 *                                  : enableTerrainRendering [default TRUE])
 *   ∧ the coverage guard is clean (wave-8 deliverable 3 — drops ⇒ passive)
 *   ∧ the drawer's own error latches are clear (wave-2 containment)
 *
 * effective occlusion culling (only meaningful while terrain renders) =
 *     meshelium.terrainDraw.bfsOnly present ? !Boolean(bfsOnly)
 *                                         : enableOcclusionCulling [default TRUE]
 *   ∧ no occlusion error latched (auto-revert to the BFS feed, wave 6)
 *   (meshelium.terrainDraw.cpuCull stays a pure dev hatch, property-only,
 *    and outranks both — precedence cpuCull &gt; bfsOnly/occlusion.)
 *
 * effective debug stats =
 *     meshelium.debugStats present ? Boolean(meshelium.debugStats)
 *                                : debugStats [default FALSE]
 *
 * effective terrain retention (wave 11; RETIRED from the options screen
 * on 2026-08-11, developer surface only, see {@link #retainTerrain}) =
 *     terrain residency running at all (the wave-1 gate: on OpenGL or
 *       without mesh shaders nothing is ever resident, so retention is
 *       structurally unreachable there; no extra gate term needed)
 *   ∧ (meshelium.retainTerrain present ? Boolean(meshelium.retainTerrain)
 *                                    : retainTerrain [default FALSE])
 *
 * retention age limit (only reachable while the above is armed) =
 *     meshelium.retainSeconds present ? that many SECONDS (0 = no limit,
 *       the harness's test-scale override)
 *   : retainTerrainMinutes minutes   [default 0 = NO LIMIT]
 * </pre>
 *
 * <p>Wave-9 tuning knobs stay PROPERTY-ONLY (dev/coordinator surface,
 * like cpuCull — deliberately not in the player-facing config):
 * {@code meshelium.tune.meshWorkgroupQuads}, {@code
 * meshelium.tune.taskWorkgroupSections}, {@code meshelium.tune.frontToBack},
 * {@code meshelium.translucentMultiWG}, {@code meshelium.gpuTimers},
 * {@code meshelium.bench} — each documented (with its safety argument)
 * on its constant in {@code TerrainDrawer} / {@code MesheliumGpuTimers} /
 * {@code MesheliumBenchRecorder}. Defaults reproduce wave-8 behaviour
 * exactly except the front-to-back ordering and the GPU timers, which
 * default ON with pixel-neutrality arguments on their javadocs. The
 * wave-12 CPU candidates ({@link #PROPERTY_SKIP_VANILLA_PREP},
 * {@link #PROPERTY_CACHED_CULL}, both default OFF) and the stage
 * attribution arm ({@code meshelium.cpustages}, default = bench runs only)
 * follow the same property-only discipline — measure first, flip defaults
 * only on the coordinator's numbers.</p>
 *
 * On the OpenGL backend and on Vulkan-without-mesh-shaders every row above
 * is dead at the gate: no config value can wake Meshelium up there (wave-1
 * dormancy is byte-identical, popups included).
 *
 * <p>SPEC's no-nag-loop rule still lives here: {@link #showVulkanPrompt} is
 * the "don't show again" persistence, the other two popup flags are
 * shown-once markers for the honest notices. The wave-8 options screen can
 * re-arm all three.</p>
 */
public final class MesheliumConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile MesheliumConfig instance;

    // ------------------------------------------------------------------
    // Wave-1 popup persistence
    // ------------------------------------------------------------------

    /** State (a) prompt. Cleared by [Don't show this again] and by [Enable Vulkan]. */
    public boolean showVulkanPrompt = true;
    /** State (b) notice (Vulkan active, no mesh shaders). Set when first shown. */
    public boolean noMeshShaderNoticeShown = false;
    /** GL active although the option asks for Vulkan (boot fell back). Set when first shown. */
    public boolean vulkanFailedNoticeShown = false;

    // ------------------------------------------------------------------
    // Wave-8 renderer settings (see the class-javadoc matrix)
    // ------------------------------------------------------------------

    /**
     * Mesh-shader terrain rendering. DEFAULT TRUE — wave 8 is the wave the
     * mod turns on: install on a mesh-shader-capable Vulkan setup and
     * terrain renders through Meshelium with no flags. Overridden by the
     * {@code meshelium.terrainDraw} property when present.
     */
    public boolean enableTerrainRendering = true;

    /**
     * Wave-6 GPU occlusion culling. False = the wave-5 BFS visibility
     * feed, which is vanilla's own culling and is always correct, just
     * less aggressive. Overridden by the
     * {@code meshelium.terrainDraw.bfsOnly} property when present.
     *
     * <p><b>DEFAULT FLIPPED TO FALSE AT 1.0.0 (2026-08-11), and the
     * reason is a measurement error this project made for months.</b> The
     * benchmark harness runs in an 854x480 window, 0.41 megapixels, which
     * is a fifth of 1080p and a ninth of 1440p. The occlusion passes are
     * RASTERISATION, so their cost scales with pixels while their benefit
     * scales with occluded geometry. In the tiny window they looked
     * almost free, and every table on this project's performance page was
     * written from that window.
     *
     * <p>The owner found it in real play at 1440p: turning occlusion off
     * made their game substantially faster. Re-measured properly at
     * 1920x1080, open plains:</p>
     * <pre>
     *   rd 32: occlusion ON 317 fps, OFF 697 fps, VANILLA 362 fps
     *   rd 64: occlusion ON 263 fps, OFF 413 fps
     * </pre>
     * At render distance 32 the shipped default was slower than vanilla
     * itself. Off by default is therefore not a tuning preference, it is
     * a correctness-of-defaults fix.
     *
     * <p>Turning it back on is safe and may still win in scenes that
     * genuinely occlude (deep caves, mountain valleys) or on a GPU whose
     * raw fill rate is weaker than an RX 9070 XT's, where drawing the
     * extra sections costs more than the passes. Deciding that per scene
     * instead of per build is the proper fix and is not done yet: it
     * wants the pass cost weighed against the section count the drawer
     * already tracks, exposed as Auto / On / Off.</p>
     */
    public boolean enableOcclusionCulling = false;

    /**
     * Once-per-5s INFO stat lines (residency + draw path; default false —
     * they are DEBUG-level without it). Overridden by the
     * {@code meshelium.debugStats} property when present.
     */
    public boolean debugStats = false;

    // ------------------------------------------------------------------
    // Wave-11 retained terrain
    // ------------------------------------------------------------------

    /**
     * Wave-11: keep rendering terrain vanilla has released for DISTANCE
     * reasons (slot reposition as the camera moves, render-distance drops,
     * chunks beyond a multiplayer server's small view distance) out of
     * Meshelium's own arena. Nvidium's signature "infinite horizon".
     *
     * <h3>DEFAULT FALSE since 2026-08-11: retired from the user
     * interface, deliberately NOT removed</h3>
     * The retention toggle and the retention time limit were the two top
     * rows of the options screen; both rows are gone and this flag now
     * defaults OFF. Why, in one sentence of evidence: <b>vanilla's fog
     * wall and vanilla's compilable set are the same cylinder</b>, both
     * sized by {@code getEffectiveRenderDistance()} (bytecode in
     * docs/MP-RETENTION-RECON.md and docs/FRONTIER-HOLES-RECON.md), so
     * the fog is fully opaque exactly where the chunk grid ends and
     * retained terrain is invisible in normal play while still costing
     * arena bytes and region ids. Waves 16 and 17 tried to widen the
     * PRESENTATION (fog wall, then far plane) to expose it; on the
     * owner's real server that produced worse artifacts than it fixed and
     * both waves were reverted (commit 619aa8e). The real problem is a
     * DATA problem: terrain the client was never sent is terrain vanilla
     * never compiles. <b>Bobby</b> already solves it the right way, by
     * caching server-sent chunks to disk and feeding them back as real
     * chunks, and Meshelium is documented as pairing with it (README).
     * So this feature has no job left in front of players.
     *
     * <h3>Still fully reachable for developers</h3>
     * Every piece of the wave-11 machinery is intact behind this flag:
     * set {@code "retainTerrain": true} in {@code config/meshelium.json},
     * or pass {@code -Dmeshelium.retainTerrain=true} (the property still
     * overrides the field, and both are re-read per pump). The bytecode
     * work behind it is sound (release-hook discrimination, supersede on
     * rebuild, pressure eviction that can never starve live terrain), and
     * a future data-layer wave may want exactly this machinery over
     * chunks a cache supplies. The harness arms the property explicitly
     * rather than leaning on the default.
     *
     * <p>Safety while it IS armed is unchanged: on OpenGL / without mesh
     * shaders nothing is ever resident, so retention cannot engage; while
     * the coverage guard holds Meshelium passive, vanilla draws its own
     * complete set and retained copies are simply not drawn; retention is
     * invisible until something is actually released. Replacement meshes
     * always supersede a retained copy; retained copies never survive a
     * world or dimension change (they die with the per-level dispatcher).
     * Turning it back OFF evicts every retained section within a few
     * frames (fence-safe).</p>
     *
     * <p>Historical note, for anyone reading the wave-11 row in
     * docs/SPEC.md: this flag existed because of an owner directive
     * (2026-08-10, verbatim) "also that rendering remembered terrain
     * beyond server range that should be a toggle, and have some way to
     * set a time limit on it and turn that limit off." It was superseded
     * by the owner's 2026-08-11 decision to pair with Bobby instead.</p>
     */
    public boolean retainTerrain = false;

    /**
     * Wave-11: how long a retained section may live, in MINUTES, measured
     * from the moment vanilla released it. <b>0 = NO LIMIT (the
     * default).</b> When non-zero, retained sections older than the limit
     * are evicted oldest-first by the pump's sweep. Independent of the
     * limit, arena/region-budget pressure always evicts retained terrain
     * first (oldest-first) so retention can never starve live terrain of
     * memory or trip the coverage guard. The harness overrides at seconds
     * scale via {@code meshelium.retainSeconds} (present overrides this
     * field entirely; its 0 also means no limit).
     *
     * <p>Config-file and property surface only since 2026-08-11: the
     * limit slider and its custom box left the options screen together
     * with the retention toggle, and this value does nothing at all while
     * {@link #retainTerrain} is at its new FALSE default (read that
     * field's javadoc for why the feature was retired). Sane values are
     * 0 to {@link #MAX_RETAIN_MINUTES}.</p>
     */
    public int retainTerrainMinutes = 0;

    // ------------------------------------------------------------------
    // Wave-10/13 extended render distance
    // ------------------------------------------------------------------

    /** Wave-10 hard floor of {@link #maxRenderDistance} (32 = cap at vanilla). */
    public static final int MIN_MAX_RENDER_DISTANCE = 32;

    /**
     * Wave-15 HARD ceiling of {@link #maxRenderDistance} — the custom-entry
     * box's upper bound. <b>120, and it cannot rise past 127:</b> the
     * client's requested view distance travels to the server as a SIGNED
     * BYTE ({@code ClientInformation} stream codec: {@code writeByte} /
     * {@code readByte():B}, bytecode-cited in
     * docs/EXTENDED-RENDER-DISTANCE.md wave-15) — 128 would arrive as −128,
     * clamp to 2, and silently break the whole server-follow chain. 120 is
     * the last 8-lattice stop with margin under that cliff. Cost at 120
     * (documented, wave-15 cost table): a (2·120+1)² ≈ 58k-chunk
     * PlayerTicketTracker per player per SP world and, when a world
     * actually PINS there, ~14 336 regions ⇒ 112 MiB section records +
     * 2×14 MiB stamps. The chunk-task ladder widens by
     * ({@value #MAX_MAX_RENDER_DISTANCE}−32) rungs at boot to match.
     */
    public static final int MAX_MAX_RENDER_DISTANCE = 120;

    /**
     * Wave-15: the options-screen SLIDER's top stop. Values in
     * ({@value #SLIDER_MAX_RENDER_DISTANCE}, {@value #MAX_MAX_RENDER_DISTANCE}]
     * are reachable only through the custom-entry box — deliberate
     * friction: past 96 the tracker/records cost curve is steep enough
     * that it should be an explicit choice, not a slider flick.
     */
    public static final int SLIDER_MAX_RENDER_DISTANCE = 96;

    /** The default cap (owner decision, wave 15: default stays 96). */
    public static final int DEFAULT_MAX_RENDER_DISTANCE = 96;

    /**
     * The documented sane ceiling of {@link #retainTerrainMinutes}, in
     * minutes (7 days): anything above it is indistinguishable from "no
     * limit", which is what 0 is for. Wave 15 enforced it as the bound of
     * the limit's custom-entry box; that box was retired with the rest of
     * the retention UI on 2026-08-11, so this is now advice to whoever
     * hand-edits the config, not a clamp (nothing rejects a larger value,
     * and {@code retainLimitMillis()} will honour it).
     */
    public static final int MAX_RETAIN_MINUTES = 10_080;

    /**
     * The CEILING Meshelium offers the vanilla render-distance slider, in
     * chunks. <b>Wave-13 default: {@value #DEFAULT_MAX_RENDER_DISTANCE}</b> —
     * this field is a CAP, not an enable switch. The interface for
     * actually raising the render distance is <em>the vanilla slider</em>
     * (Options → Video Settings → Render Distance), which Meshelium widens
     * to this ceiling while the gate is VULKAN_MESH_SHADERS and terrain
     * rendering is enabled. Wave-15: the options screen sets this with a
     * 32..{@value #SLIDER_MAX_RENDER_DISTANCE} slider plus a custom box
     * up to the {@value #MAX_MAX_RENDER_DISTANCE} hard max (wire-bounded,
     * see the constant).
     *
     * <p>Why the 96 default is affordable (it was 32 in wave 10): since
     * wave 13 GPU buffer sizing pins from the OPTION value at world
     * standup ({@code MesheliumScaling.pinForWorld}), not from this
     * ceiling — a player at rd 12 under a 96 ceiling pins the exact
     * wave-≤9 standard sizes. The only ceiling-derived cost left is the
     * integrated server's PlayerTicketTracker range (per world load,
     * {@code DistanceManagerMixin} — O(cap²) tracked chunks per player,
     * documented there); lowering this cap on the options screen reduces
     * it at the next world load.</p>
     *
     * <p>The clamp-back invariant is unchanged: the widened slider range
     * only ever takes EFFECT while Meshelium actually draws — on the OpenGL
     * backend, without mesh shaders, with terrain rendering off, or when
     * the coverage guard puts Meshelium passive, {@code MesheliumExtendedRd}
     * clamps the option back to 32 with a notice (the vanilla renderer at
     * rd 64 would be a slideshow Meshelium caused). Setting the cap to 32
     * restores every vanilla-exact path. Overridden by the
     * {@code meshelium.maxRenderDistance} property when present (harness).</p>
     */
    public int maxRenderDistance = DEFAULT_MAX_RENDER_DISTANCE;

    public static MesheliumConfig get() {
        MesheliumConfig loaded = instance;
        if (loaded == null) {
            synchronized (MesheliumConfig.class) {
                loaded = instance;
                if (loaded == null) {
                    loaded = load();
                    instance = loaded;
                }
            }
        }
        return loaded;
    }

    // ------------------------------------------------------------------
    // Wave-8 effective-value resolvers (property ?? config; see matrix).
    // Pure loader/JDK code — safe to call from mixins on BOTH backends and
    // from any thread; call sites re-read per frame, so both the property
    // flips (harness) and the config toggles (options screen) are live.
    // ------------------------------------------------------------------

    /** {@code meshelium.terrainDraw} ?? {@link #enableTerrainRendering}. */
    public static boolean terrainRenderingEnabled() {
        return propertyOr("meshelium.terrainDraw", get().enableTerrainRendering);
    }

    /**
     * {@code !meshelium.terrainDraw.bfsOnly} ?? {@link #enableOcclusionCulling}
     * (the property spells the FALLBACK, so its presence inverts).
     */
    public static boolean occlusionCullingEnabled() {
        String property = System.getProperty("meshelium.terrainDraw.bfsOnly");
        if (property != null) {
            return !Boolean.parseBoolean(property);
        }
        return get().enableOcclusionCulling;
    }

    /** {@code meshelium.debugStats} ?? {@link #debugStats}. */
    public static boolean debugStatsEnabled() {
        return propertyOr("meshelium.debugStats", get().debugStats);
    }

    /**
     * Wave-11: {@code meshelium.retainTerrain} ?? {@link #retainTerrain}
     * (default FALSE since the feature was retired from the UI, see the
     * field). Re-read by the release hook and the pump every time, so a
     * config edit and a harness property flip both apply within a frame
     * (OFF additionally drains the already-retained set, see
     * {@code TerrainResidency}).
     */
    public static boolean retainTerrainEnabled() {
        return propertyOr("meshelium.retainTerrain", get().retainTerrain);
    }

    /**
     * Wave-11 effective retention age limit in MILLISECONDS; 0 = no limit.
     * {@code meshelium.retainSeconds} (test-scale, seconds) when present —
     * including an explicit 0 for "no limit" — else
     * {@link #retainTerrainMinutes} minutes. Monotonic-clock consumers
     * compare against {@code System.nanoTime()/1e6} deltas, never wall
     * time.
     */
    public static long retainLimitMillis() {
        String property = System.getProperty("meshelium.retainSeconds");
        if (property != null) {
            try {
                return Math.max(0L, Long.parseLong(property.trim())) * 1000L;
            } catch (NumberFormatException e) {
                MesheliumClient.LOGGER.warn(
                        "Unparseable meshelium.retainSeconds='{}' — using the config minutes", property);
            }
        }
        return Math.max(0L, get().retainTerrainMinutes) * 60_000L;
    }

    /**
     * Wave-10/13: {@code meshelium.maxRenderDistance} ?? {@link #maxRenderDistance},
     * clamped into [{@value #MIN_MAX_RENDER_DISTANCE},
     * {@value #MAX_MAX_RENDER_DISTANCE}]. This is the CEILING the vanilla
     * slider may reach (32 = cap at vanilla, extended range OFF); the
     * actual render distance is the vanilla OPTION. Pure JDK/loader code
     * — callable from any thread (the server-side view distance mixins
     * read it off the server thread).
     */
    public static int maxRenderDistanceConfigured() {
        int value = get().maxRenderDistance;
        String property = System.getProperty("meshelium.maxRenderDistance");
        if (property != null) {
            try {
                value = Integer.parseInt(property.trim());
            } catch (NumberFormatException e) {
                MesheliumClient.LOGGER.warn(
                        "Unparseable meshelium.maxRenderDistance='{}' — using the config value {}",
                        property, value);
            }
        }
        return Math.max(MIN_MAX_RENDER_DISTANCE, Math.min(MAX_MAX_RENDER_DISTANCE, value));
    }

    // ------------------------------------------------------------------
    // Wave-12 CPU-optimization candidates — PROPERTY-ONLY (coordinator
    // A/B surface, like the wave-9 knobs; deliberately not in the
    // player-facing config until a bench run makes one a measured winner —
    // that default flip is a coordinator commit). Both live here rather
    // than on TerrainDrawer because their FIRST reader is a mixin on a
    // backend-neutral vanilla class (LevelRendererMixin): referencing a
    // TerrainDrawer constant there would class-load the LWJGL-importing
    // drawer on the GL path — MesheliumConfig is pure loader/GSON code,
    // wave-1-safe to touch anywhere (the kill-switch mixin's precedent).
    // ------------------------------------------------------------------

    /**
     * Wave-12 candidate: skip vanilla's {@code prepareChunkRenders} work
     * product on frames Meshelium will own end-to-end. DEFAULT OFF —
     * byte-identical behaviour without it. Consumer census + the one-frame
     * failure edge documented in docs/VANILLA-FRAME-PATH.md wave-12 notes;
     * the predictive gate lives in {@code TerrainDrawer.wouldOwnFrame}.
     */
    public static final String PROPERTY_SKIP_VANILLA_PREP = "meshelium.tune.skipVanillaPrep";

    /**
     * Wave-12 candidate: exact memoization of the drawer's per-frame
     * occlusion dispatch-list build (region frustum cull + occlusion-list
     * bytes + front-to-back sort), reused only when EVERY input is
     * bit-identical. DEFAULT OFF. Honesty note on the drawer's constant:
     * this wins on still frames — 100% of the bench's static camera,
     * only the stationary slice of real play; the hit/miss counters are
     * the real-play evidence the coordinator must weigh before any flip.
     */
    public static final String PROPERTY_CACHED_CULL = "meshelium.tune.cachedCull";

    /**
     * {@code meshelium.tune.skipVanillaPrep}, re-read every call.
     * <b>DEFAULT ON since wave 12's measurements</b> (property=false is the
     * escape hatch): the consumer census proved vanilla's per-frame
     * ChunkSectionsToRender build feeds ONLY the renderGroup calls the kill
     * switch cancels, and the rd-64 bench measured it at 3.54 ms of a
     * 6.53 ms frame — 54% of the frame spent building draws that never
     * happen. Parity shots 40/41 + 60/61 verified clean with the skip
     * live (2026-08-10). The one-frame first-throw hole stays counted by
     * prepSkipHoleFrames and bench-asserted zero.
     */
    public static boolean skipVanillaPrepEnabled() {
        String p = System.getProperty(PROPERTY_SKIP_VANILLA_PREP);
        return p != null ? Boolean.parseBoolean(p) : true;
    }

    /** {@code meshelium.tune.cachedCull}, re-read every call, default false. */
    public static boolean cachedCullEnabled() {
        return Boolean.getBoolean(PROPERTY_CACHED_CULL);
    }

    private static boolean propertyOr(String key, boolean configValue) {
        String property = System.getProperty(key);
        return property != null ? Boolean.parseBoolean(property) : configValue;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("meshelium.json");
    }

    private static MesheliumConfig load() {
        Path path = path();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    MesheliumConfig loaded = GSON.fromJson(reader, MesheliumConfig.class);
                    if (loaded != null) {
                        return loaded;
                    }
                }
            }
        } catch (IOException | JsonParseException e) {
            MesheliumClient.LOGGER.warn("Could not read {}; starting from defaults", path, e);
        }
        return new MesheliumConfig();
    }

    public void save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            MesheliumClient.LOGGER.warn("Could not write {}; settings will not persist", path, e);
        }
    }
}
