/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
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
     * Stop vanilla uploading its own copy of the terrain that nothing draws.
     *
     * <p>DEFAULT ON as of schema v1. It shipped OFF, and the reason was never
     * doubt about the saving - that is measured, vanilla's copy runs 1.3x to
     * 2.6x Meshelium's whole arena. It was that vanilla's copy was the safety
     * net every giving-up path fell back on, so suppressing it turned a bad
     * frame into an empty world. It did exactly that, twice, in testing.</p>
     *
     * <p>What changed is that the net was replaced rather than removed. The
     * renderers now hand over one at a time (dump one, load the other), the
     * demotion runs from the client tick where nothing can skip it, and the
     * whole round trip is covered by a test that asks the only honest
     * question: after the handover, does the other renderer actually have
     * geometry to draw. Against that, leaving this off costs gigabytes for
     * nothing.</p>
     *
     * <p>Worth almost nothing at ordinary render distances, where vanilla's
     * copy is a few hundred MiB, and worth gigabytes past 64 chunks.</p>
     */
    public boolean suppressVanillaUploads = true;

    /**
     * Schema version of the file on disk, for one-shot migrations.
     *
     * <p>Zero on purpose. Gson runs the field initialisers and then
     * overwrites only the keys the file actually contains, so a file written
     * before this field existed loads as 0 and a fresh install also starts at
     * 0. Both then run every migration, which is correct as long as each
     * migration is idempotent.</p>
     *
     * <p>This exists because the mod writes EVERY field on save. Changing a
     * Java default therefore reaches nobody who has ever opened the settings:
     * their file already contains the old value explicitly, and it wins. A
     * default is only a real default for a brand new install.</p>
     */
    public int configVersion = 0;

    /** Current schema version. Bump when adding a migration below. */
    private static final int CURRENT_CONFIG_VERSION = 2;

    /**
     * Bring an older file up to the current schema. Returns true if anything
     * changed and the file should be rewritten.
     */
    private boolean migrate() {
        if (configVersion >= CURRENT_CONFIG_VERSION) {
            return false;
        }
        if (configVersion < 1) {
            // v1: duplicate-terrain freeing becomes the default.
            //
            // It shipped off because vanilla's copy was Meshelium's safety
            // net and suppressing it turned a bad frame into an empty world.
            // The sequenced swap replaced that net with something better: the
            // renderers hand over one at a time, and the handover is covered
            // by a round-trip test. Meanwhile the cost of leaving it off is
            // gigabytes at long render distance, measured at 3264 MiB against
            // a 2048 MiB arena. Off is no longer the cautious choice, it is
            // just the expensive one.
            //
            // This overwrites an explicit false, which is normally rude. It
            // is right here because no released build ever exposed this
            // setting, so any false on disk is a leftover from testing rather
            // than a considered choice.
            suppressVanillaUploads = true;
        }
        if (configVersion < 2) {
            // v2: distance fog defaults OFF rather than SCALED.
            //
            // Only reaches the handful of configs written by a build that
            // shipped SCALED, since fogMode did not exist before that and an
            // absent key already picks up the new default. Safe to overwrite
            // for the same reason as v1: nobody has had time to form an
            // opinion about a setting that is hours old.
            fogMode = FogMode.OFF;
        }
        configVersion = CURRENT_CONFIG_VERSION;
        return true;
    }

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
     *
     * <p><b>STILL FALSE, but the cost above has since been FIXED and the
     * diagnosis in this javadoc was wrong (2026-08-12).</b> The passes
     * were never fill-rate bound. Every fragment of a box wrote the same
     * word with an atomic, and same-address atomics serialise, so a near
     * box covering the screen cost a million serialised read-modify-writes
     * on one address. Read-guarding that store
     * ({@code shaders/occlusion/box.frag}, docs/OCCLUSION-FILLRATE-DESIGN.md
     * stage 1a) removed about 97 percent of both passes, measured with the
     * guarded and unguarded builds run back to back on the same scene:</p>
     * <pre>
     *   ground-rd32 @1080p: 287 fps -&gt; 1553 fps
     *   plains-rd64 @1080p: 263 fps -&gt;  578 fps
     * </pre>
     *
     * <p><b>That is a 5.4x cheaper occlusion path, and it is still not a
     * reason to default this to true.</b> Making the passes cheap is a
     * different question from whether culling beats not culling, and the
     * same-session answer to the second question is that it depends on the
     * scene, exactly as it did before the fix, only with the magnitudes
     * collapsed. Occlusion ON versus the BFS feed, 1920x1080, static, each
     * pair measured in one session:</p>
     * <pre>
     *   ground rd32   0.662 vs 0.671 ms   -1.3%   (repeat: +9.0%)
     *   plains rd32   0.989 vs 0.863 ms  +14.6%   (repeat: +11.5%)
     *   plains rd64   1.748 vs 2.053 ms  -14.9%
     * </pre>
     * <p>Positive means occlusion is SLOWER. It costs about 10 percent at
     * render distance 32 and gains about 15 percent at 64, so the crossover
     * sits between roughly 3,300 and 9,500 resident sections. Defaulting it
     * on would make the common case slower, which is the same mistake 1.0.0
     * shipped, just smaller. The fix for that is Auto, keyed on the section
     * count this class can already see, not a flipped boolean.</p>
     *
     * <p>Note these are OPEN scenes, near the worst case for occlusion.
     * Caves and mountains should move the crossover much nearer, which is
     * the argument for deciding per scene at runtime.</p>
     *
     * <p>CORRECTION, recorded because it nearly shipped: an earlier version
     * of this javadoc claimed occlusion had become "substantially faster
     * than the BFS feed in both scenes". That compared a fresh ON leg with
     * a BFS leg from a DIFFERENT session (1.089 ms), which re-measures at
     * 0.671 ms same-session. Never compare against a baseline you did not
     * measure beside it.</p>
     */
    /**
     * @deprecated SUPERSEDED by {@link #occlusionMode} (1.1). Kept as a
     *     field so existing {@code meshelium.json} files still parse:
     *     {@code load()} catches only {@code IOException} and
     *     {@code JsonParseException}, and every shipped config carries this
     *     key as a JSON boolean, so retyping it in place risks a parse
     *     failure that would silently reset a player's whole config. Nothing
     *     reads it any more; a 1.0.0 user who had set it true lands on AUTO,
     *     which turns occlusion on exactly where it measures faster.
     */
    @Deprecated
    public boolean enableOcclusionCulling = false;

    /**
     * What to do about the outdoor distance haze.
     *
     * <h2>The problem this exists for</h2>
     * <p>Minecraft 26.2 fogs terrain with two independent ramps combined by
     * {@code max()} in fog.glsl. One is tied to render distance and is fine:
     * it ends exactly at the horizon and its band is
     * {@code clamp(horizon/10, 4, 64)} blocks, so past render distance 40 it
     * is a fixed 64 blocks, four chunks, which is the soft edge that hides
     * chunks popping in.</p>
     *
     * <p>The other is the atmospheric haze, and it is a FIXED ABSOLUTE
     * DISTANCE that ignores render distance completely:
     * {@code EnvironmentAttributes.FOG_END_DISTANCE}, default 1024 blocks.
     * The Overworld dimension does not override it, no biome overrides it,
     * nothing animates it. So it saturates at chunk 64 no matter how far you
     * can see, and every chunk beyond that is drawn, lit, rasterised and
     * then painted flat fog colour. At render distance 120 the horizon is
     * 1920 blocks, so 56 chunks of the view are solid paint.</p>
     *
     * <p>Which is the opposite of the intuitive reading: the fog is not a
     * percentage that grows with distance, it is a constant that the view
     * outgrows. Below render distance 64 it sits beyond the horizon and
     * nobody notices. Vanilla's own maximum is 32, so vanilla never had to
     * care.</p>
     */
    public enum FogMode {
        /** Leave Minecraft's fog exactly as it is. */
        VANILLA,
        /**
         * Push the haze out with the view, so it always ends at the same
         * fraction of the horizon. Never makes fog THICKER than vanilla:
         * below render distance 64 the constant is already past the horizon
         * and this changes nothing at all.
         */
        SCALED,
        /** No outdoor distance haze. The render-distance edge fade stays. */
        OFF
    }

    /**
     * Default OFF, on the owner's call after seeing all three at render
     * distance 120 (2026-08-14): "turning the fog off by default looks the
     * best".
     *
     * <p>SCALED was the cautious choice and looking at it decided otherwise.
     * The reason OFF is not the drastic option it sounds like is that it
     * only removes the ATMOSPHERIC haze. The render-distance fade is a
     * separate term this setting never touches, so the last four chunks
     * still soften into the sky and chunks do not pop in against a hard
     * edge. The owner's words for it: "it has a tiny bit around the edges
     * still, but not like where a lot of the map is covered in a small
     * amount of fog then a ton right at the edge".</p>
     */
    public FogMode fogMode = FogMode.OFF;

    /** Where the haze finishes, as a percentage of how far you can see. */
    public int fogEndPercent = 100;

    public static final int MIN_FOG_END_PERCENT = 50;
    public static final int MAX_FOG_END_PERCENT = 200;
    public static final int DEFAULT_FOG_END_PERCENT = 100;

    /**
     * Merge adjacent identical block faces into larger quads.
     *
     * <p>DEFAULT OFF while it is being tested. Measured over 10,000 real
     * sections at render distance 64: 5.0 percent fewer quads with vanilla's
     * Smooth Lighting on, 25.6 percent with it off. The gap is not a quirk
     * of the algorithm, it is that vanilla bakes ambient occlusion per
     * vertex, so most faces are bilinear ramps that cannot tile; with Smooth
     * Lighting off the disqualified count is exactly zero.</p>
     *
     * <p>Fewer quads is fewer primitives AND a smaller arena, so this is a
     * frame-rate change and a memory change at once. It is not a fill-rate
     * change: the same pixels are covered either way.</p>
     */
    public boolean greedyMeshing = false;

    /**
     * Wave-16: quiet-time trim of the terrain arena's committed-but-unused
     * tail. <b>Default ON.</b>
     *
     * <p>The arena grows in half-gigabyte blocks and never gave the unused
     * remainder of the top block back, which at render distance 64 is a
     * measured 492 to 496 MiB of committed VRAM that nothing has ever
     * touched (PERFORMANCE.md 2026-08-16). The trim copies the block's
     * small used extent onto a right-sized buffer after 30 seconds of no
     * arena work and returns the rest to the driver; flying into new
     * terrain simply regrows through the ordinary ladder. Off, behavior is
     * byte-identical to 1.2.0: the tail stays committed until the world
     * closes.</p>
     */
    public boolean arenaTrim = true;

    /** How occlusion culling decides whether to run. */
    public enum OcclusionMode {
        /** On at or above {@link #occlusionAutoMinRenderDistance}. */
        AUTO,
        ON,
        OFF
    }

    /**
     * Occlusion culling: Auto, On or Off. <b>Default AUTO</b> (1.1).
     *
     * <p>1.0.0 shipped this as a plain boolean defaulting ON, which was
     * wrong, and then as a plain boolean defaulting OFF, which was also
     * wrong. It is not a global answer: measured same-session at 1920x1080
     * on an RX 9070 XT, occlusion is 11 to 15 percent SLOWER than the BFS
     * feed at render distance 32 and 19 to 31 percent FASTER at 64. Auto
     * exists because the correct answer depends on how far you can see.</p>
     */
    public OcclusionMode occlusionMode = OcclusionMode.AUTO;

    /** Lowest render distance Auto will arm occlusion at. */
    public static final int MIN_OCCLUSION_AUTO_RD = 2;

    /**
     * Highest; equal to the hard render-distance ceiling, so Auto can be
     * parked above any reachable distance and behave as Off.
     *
     * <p>Qualified name deliberately: {@code MAX_MAX_RENDER_DISTANCE} is
     * declared further down this file and a SIMPLE name there would be an
     * illegal forward reference. Keeping the occlusion constants next to
     * the occlusion field beats reordering the file.</p>
     */
    public static final int MAX_OCCLUSION_AUTO_RD = MesheliumConfig.MAX_MAX_RENDER_DISTANCE;

    /**
     * The crossover that is safe for every measured case, which is NOT the
     * same as the best value for any one of them.
     *
     * <p>Render distance is only a PROXY. What occlusion exploits is how
     * much terrain is hidden behind other terrain, and CAMERA POSE moves
     * that far more than distance or resolution do. Measured 2026-08-12,
     * two repeats per cell, occlusion ON versus the BFS feed, negative
     * meaning occlusion is faster:</p>
     * <pre>
     *                        1920x1080        2560x1440
     *   eye level, rd 32   -6.5 / -17.8%    -5.6 /  -7.2%   occlusion WINS
     *   elevated,  rd 32  +14.5 / +14.6%   +20.7 / +20.2%   occlusion LOSES
     *   eye level, rd 64  -20.9 / -27.7%    -1.3 / -17.4%   occlusion WINS
     *   elevated,  rd 48   -4.3 /  -3.1%    +2.1 /  +0.9%   about even
     * </pre>
     *
     * <p>At render distance 32 the same scene swings roughly 25 points on
     * camera pose alone: eye level wins, 56 blocks up looking down loses.
     * No single distance threshold can serve both, so this is set where
     * neither is meaningfully hurt: a small win at eye level, a wash from
     * above. 32 would be better for ground play and clearly worse for
     * anyone flying, and the measured elevated loss (about 17 percent
     * averaged) is larger than the measured eye-level gain (about 9
     * percent), so a lower default only pays if a player almost never
     * leaves the ground. The slider exists for players who know which they
     * are; the tooltip tells them which way to move it.</p>
     *
     * <p>Resolution moves the crossover UP, not down: the box-pass tax
     * still scales with pixels (0.245 to 0.299 ms at rd 32 for 78 percent
     * more pixels) because the read guard removed the atomic but every
     * fragment still runs and still loads. So 48 stays right at 1440p and
     * above, and a resolution-scaled default would have to raise it, not
     * lower it. Not worth the hidden magic when a slider is right there.</p>
     */
    public static final int DEFAULT_OCCLUSION_AUTO_RD = 48;

    /**
     * Render distance at or above which Auto arms occlusion culling.
     *
     * <p>Tunable because {@link #DEFAULT_OCCLUSION_AUTO_RD} was fitted to
     * OPEN terrain, which is close to occlusion's worst case. Caves,
     * ravines and mountains hide far more geometry for the same distance
     * and should cross over lower; that case is <b>UNMEASURED</b>, so the
     * shipped default is the conservative one and this exists for players
     * whose worlds are not open plains.</p>
     */
    public int occlusionAutoMinRenderDistance = DEFAULT_OCCLUSION_AUTO_RD;

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
     * The master switch as the PLAYER set it, ignoring the property override.
     *
     * <p>Deliberately different from {@link #terrainRenderingEnabled()}, and
     * the distinction is load-bearing. Flipping the switch now performs a
     * sequenced renderer swap: stop encoding, release the arena, reload the
     * terrain so the other renderer fills in. That is right for a player
     * clicking a button and completely wrong for {@code meshelium.terrainDraw},
     * which the benchmark and the draw test flip on and off between frames to
     * photograph the same scene both ways. Tearing the world down each time
     * would break the twin comparison and cost seconds per flip.</p>
     *
     * <p>So: the property stays a cheap DRAW-level toggle, and only the config
     * field drives the swap. For a real player, with no property set, the two
     * are the same value.</p>
     */
    public static boolean terrainRenderingConfigured() {
        return get().enableTerrainRendering;
    }

    /** {@code meshelium.suppressVanillaUploads} ?? {@link #suppressVanillaUploads}. */
    public static boolean suppressVanillaUploads() {
        return propertyOr("meshelium.suppressVanillaUploads", get().suppressVanillaUploads);
    }

    /**
     * Resolve occlusion culling for a frame at {@code effectiveRenderDistance}.
     *
     * <p>Precedence: {@code meshelium.terrainDraw.bfsOnly} (the harness pin;
     * the property spells the FALLBACK, so its presence inverts) beats the
     * config, and in {@link OcclusionMode#AUTO} the config consults the
     * render distance against {@link #occlusionAutoMinRenderDistance}.</p>
     *
     * <p>The distance is passed IN rather than read here on purpose: this
     * class is pure loader/GSON code callable from either backend and any
     * thread, and reaching into {@code Minecraft.getInstance()} would break
     * that. The one production call site is on the render thread and
     * already knows the number.</p>
     */
    public static boolean occlusionCullingEnabled(int effectiveRenderDistance) {
        String property = System.getProperty("meshelium.terrainDraw.bfsOnly");
        if (property != null) {
            return !Boolean.parseBoolean(property);
        }
        MesheliumConfig config = get();
        return switch (config.occlusionMode) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> effectiveRenderDistance >= config.occlusionAutoMinRenderDistance;
        };
    }

    /** {@code meshelium.greedyMeshing} ?? {@link #greedyMeshing}. */
    public static boolean greedyMeshingEnabled() {
        return propertyOr("meshelium.greedyMeshing", get().greedyMeshing);
    }

    /** {@code meshelium.tune.arenaTrim} ?? {@link #arenaTrim}. */
    public static boolean arenaTrimEnabled() {
        return propertyOr("meshelium.tune.arenaTrim", get().arenaTrim);
    }

    /** {@code meshelium.fogMode} ?? {@link #fogMode}. */
    public static FogMode fogMode() {
        String property = System.getProperty("meshelium.fogMode");
        if (property != null) {
            try {
                return FogMode.valueOf(property.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // A typo in a dev flag must not decide how the world looks.
            }
        }
        return get().fogMode;
    }

    /** Clamped, because the file is hand-editable and the shader is not. */
    public static int fogEndPercent() {
        return Math.max(MIN_FOG_END_PERCENT,
                Math.min(MAX_FOG_END_PERCENT, get().fogEndPercent));
    }

    /**
     * The configured mode, ignoring render distance — for the options
     * screen and for the boot-smoke default assertion. The harness property
     * still outranks it so a pinned leg reports what it actually ran.
     */
    public static OcclusionMode occlusionMode() {
        String property = System.getProperty("meshelium.terrainDraw.bfsOnly");
        if (property != null) {
            return Boolean.parseBoolean(property) ? OcclusionMode.OFF : OcclusionMode.ON;
        }
        return get().occlusionMode;
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

    /**
     * Restore every PLAYER-FACING setting to its shipped default and save.
     *
     * <p>Copies field by field from a fresh instance rather than replacing
     * the singleton, because call sites hold {@code get()} references and
     * swapping the object underneath them would leave stale views.</p>
     *
     * <p>Deliberately NOT reset: {@link #noMeshShaderNoticeShown} and
     * {@link #vulkanFailedNoticeShown}. Those are "we have already told you
     * this once" receipts, not preferences, and clearing them would make a
     * one-time popup reappear at the next boot as a surprise side effect of
     * a button labelled "reset settings". {@link #showVulkanPrompt} IS a
     * preference and IS reset.</p>
     */
    public void resetToDefaults() {
        MesheliumConfig d = new MesheliumConfig();
        this.enableTerrainRendering = d.enableTerrainRendering;
        this.occlusionMode = d.occlusionMode;
        this.occlusionAutoMinRenderDistance = d.occlusionAutoMinRenderDistance;
        this.enableOcclusionCulling = d.enableOcclusionCulling;
        this.debugStats = d.debugStats;
        this.maxRenderDistance = d.maxRenderDistance;
        this.showVulkanPrompt = d.showVulkanPrompt;
        this.fogMode = d.fogMode;
        this.fogEndPercent = d.fogEndPercent;
        // suppressVanillaUploads was missing here, so Reset To Defaults
        // quietly left it wherever the player had put it. Every field with a
        // row has to be listed or the button does not do what it says.
        this.suppressVanillaUploads = d.suppressVanillaUploads;
        // And then greedyMeshing was missing here too, found by the same
        // review rule this comment states. The lesson refuses to stay
        // learned, so the torture test now walks every row-backed field.
        this.greedyMeshing = d.greedyMeshing;
        this.arenaTrim = d.arenaTrim;
        // Retention has no rows any more (Bobby owns that job since
        // 2026-08-11) but the fields are still live behind the config, so a
        // reset must cover them or "reset to defaults" would quietly leave
        // a hand-edited config half-reset.
        this.retainTerrain = d.retainTerrain;
        this.retainTerrainMinutes = d.retainTerrainMinutes;
        save();
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
                        if (loaded.migrate()) {
                            MesheliumClient.LOGGER.info(
                                    "Meshelium migrated {} to settings schema v{}", path,
                                    CURRENT_CONFIG_VERSION);
                            loaded.save();
                        }
                        return loaded;
                    }
                }
            }
        } catch (IOException | JsonParseException e) {
            MesheliumClient.LOGGER.warn("Could not read {}; starting from defaults", path, e);
        }
        MesheliumConfig fresh = new MesheliumConfig();
        fresh.migrate();
        return fresh;
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
