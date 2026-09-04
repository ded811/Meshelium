/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.farfield;

import com.deds.meshelium.MesheliumPlatform;
import com.deds.meshelium.MesheliumLog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;


import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Far-field settings: a plain GSON POJO at
 * {@code config/meshelium-farfield.json}, mirroring the house pattern of
 * {@code MesheliumConfig} exactly (GSON, no config library, lazy singleton,
 * schema version + idempotent {@code migrate()}, and the wave-8 precedence
 * matrix: a PRESENT system property overrides the config field, an absent
 * property lets the config rule; every resolver is re-read per call so both
 * property flips and config edits apply next frame, no restart).
 *
 * <h2>Why a separate file instead of new fields on MesheliumConfig</h2>
 * W1 of the far-field plan (docs/FARFIELD-WAVES.md, pre1) is pure new files
 * and may not touch {@code MesheliumConfig.java}. The house config is one
 * GSON POJO per file, so new fields there would mean editing that class.
 * TODO-FOR-LATER (central registration): a future wave may fold these
 * fields into {@code config/meshelium.json} (with a one-shot migration that
 * imports and then deletes this file), or keep the separate file and simply
 * register the screen row. Until then this file is the single source of
 * truth for far-field settings.
 *
 * <h2>The three-layer ladder (owner playtest of pre1, items D1 to D3)</h2>
 * <p>The owner's ask, close to verbatim: three LOD layers "where we can
 * choose what performance optimizations to apply to them", layer 1's
 * default being that "everything looks normal but its only the visible
 * surface being rendered". So the shape here is deliberately regular: one
 * {@link LayerSettings} per layer, the SAME control set on each, and a
 * single place ({@link #isWired}) that says which of those controls is
 * actually connected to code today.</p>
 *
 * <p><b>Layer 1 defaults to maximum accuracy.</b> Every cheapening control
 * on layer 1 is off: full detail scale, no forced solid leaves, no flat
 * water sheet, no texture simplification, no coarse shading, no
 * heightfield. The one cut that IS on by default is the store-wide surface
 * band, and that is not a cheapening of appearance: it drops the block
 * faces that are buried underground, which is exactly the owner's "only
 * the visible surface being rendered".</p>
 *
 * <p><b>pre8 adds the other direction.</b> The eight original controls all
 * make a layer CHEAPER; the six accuracy ones (underwater plants, small
 * detail, colour blending, lighting, pre9's underwater shading and pre11's
 * contact shading) make it more ACCURATE, so their
 * conservative value is "off" for layers 2 and 3 and layer 1 turns on
 * every one whose cost is negligible. The two steps that are not free -
 * real per-cell lighting and the fine colour grid - are one click away
 * with their price in their own tooltip, per the owner's "make it super
 * customizable" and the standing rule that anything expensive ships
 * off.</p>
 *
 * <p><b>Layers 2 and 3 default off entirely</b> (the standing uncertainty
 * rule, CLAUDE.md: nothing changes the owner's game until they turn it
 * on). They are disabled, their radius is 0, and every feature on them is
 * neutral. Nothing about them is wired yet either, so
 * {@link #isWired(Layer, Control)} answers false for all of it and the
 * screens label those rows "not used yet" rather than pretending.</p>
 *
 * <h2>Why the surface band is NOT a per-layer control</h2>
 * <p>The design doc lists "surface-only extraction" among the per-level
 * feature candidates, but extraction happens ONCE per chunk and writes ONE
 * shell record that every layer reads. The band therefore decides what
 * goes on DISK, not how a layer draws; duplicating it per layer would give
 * three switches for one file format and two of them would be lies. It
 * stays a single store-wide row ({@link #surfaceBand}) in the saved-terrain
 * section of the screen, and the layer pages say so.</p>
 *
 * <h2>The fields, and where each default comes from</h2>
 * <ul>
 * <li>{@link #enabled} default FALSE: the master OFF switch. Off means
 *     zero cost (FAR-FIELD-DESIGN.md section 6): no thread, no folder, no
 *     allocation. {@code FarField} gates every entry point on the resolver
 *     {@link #enabled()}, so this one branch is the whole off path.</li>
 * <li>{@link #l1RadiusChunks} default 64: LEGACY MIRROR of
 *     {@code layer1.radiusChunks} since schema v2, kept so a hand-editable
 *     file (and a downgrade) still shows the number where it always was,
 *     and so the long-standing property override
 *     {@code -Dmeshelium.farfield.l1RadiusChunks} keeps its name.
 *     {@link #save()} refreshes it; nothing reads it except
 *     {@link #migrate()}.</li>
 * <li>{@link #surfaceBand} default TRUE: the census-backed cave cutoff
 *     (SHELL-CENSUS-2026-08.md: 74.3 percent of exposed faces sit below
 *     heightmap top minus 8; the band cuts disk bytes by about 77
 *     percent). Resolution 4 in FAR-FIELD-DESIGN.md section 10: surface
 *     band ON by default, full underground detail is the off position.</li>
 * <li>{@link #bandOffset} default 8, range 4..32: how many blocks below
 *     the per-column top the band keeps. Fixed at 8 initially by
 *     resolution 4, but the FORMAT stores the offset per record
 *     (ShellCodec header) so this can become a slider without a store
 *     migration.</li>
 * <li>{@link #preset} default {@link Preset#CUSTOM}: which preset the
 *     player last selected, or Custom when they have hand-edited anything
 *     since. A fresh install is Custom on purpose - it matches no preset
 *     (the master is off but the render distance is whatever the player
 *     already had), and claiming otherwise would make the screen lie on
 *     first open.</li>
 * </ul>
 *
 * <p>Property overrides, harness-style, all under
 * {@code meshelium.farfield.}: {@code enabled}, {@code surfaceBand},
 * {@code bandOffset}, {@code extractBudgetMillis},
 * {@code adaptiveExtraction}, {@code extractFrameRateFloor},
 * {@code preset}, and per layer
 * {@code l1Enabled}/{@code l1RadiusChunks}/{@code l1Scale}/
 * {@code l1SolidLeaves}/{@code l1FlatWater}/{@code l1TextureSimplify}/
 * {@code l1CoarseShading}/{@code l1Heightfield}/{@code l1UnderwaterPlants}/
 * {@code l1SmallDetail}/{@code l1ColourBlend}/{@code l1Lighting}/
 * {@code l1WaterDepth}/{@code l1ContactShade}/{@code l1CacheSpeed} and
 * the same fifteen for {@code l2} and {@code l3}. Pure loader/GSON/JDK code - safe to call from
 * mixins on both backends and from any thread, exactly like
 * {@code MesheliumConfig} (its class javadoc carries the argument). In
 * particular this class must never touch {@code Minecraft}: the preset
 * table therefore only STATES its render distance
 * ({@link Preset#renderDistanceChunks()}) and the screen is what applies
 * it to the vanilla option.</p>
 *
 * <p>Zero-cost note: {@code load()} never CREATES the config file - a
 * fresh install runs on in-memory defaults until something calls
 * {@link #save()} (the settings screen). So with the master off the far
 * field leaves no trace on disk, not even a config file, matching the
 * suite's zero-cost-off assertions.</p>
 */
public final class FarFieldConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile FarFieldConfig instance;

    /** Every property override in this class sits under this prefix. */
    private static final String PROPERTY_PREFIX = "meshelium.farfield.";

    // ------------------------------------------------------------------
    // Fields (GSON-populated; defaults are the shipped defaults)
    // ------------------------------------------------------------------

    /** Master switch. FALSE = the far field does not exist (zero cost). */
    public boolean enabled = false;

    /**
     * LEGACY MIRROR of {@code layer1.radiusChunks} (schema v2). Written by
     * {@link #save()}, read only by {@link #migrate()}. See the class
     * javadoc for why it survives.
     */
    public int l1RadiusChunks = DEFAULT_L1_RADIUS_CHUNKS;

    /**
     * Game-thread milliseconds the far field may spend on shell
     * EXTRACTION in any ONE frame, <b>at most</b> (it was "at minimum"
     * until T2 - see {@link #DEFAULT_EXTRACT_BUDGET_MILLIS} for why the
     * direction inverted and why that is the same setting rather than a
     * new one). Zero means never. See {@code ExtractDispatch}'s class
     * javadoc for what the budget covers.
     */
    public int extractBudgetMillis = DEFAULT_EXTRACT_BUDGET_MILLIS;

    /**
     * Size the extraction slice as a share of the measured frame time
     * instead of using {@link #extractBudgetMillis} as a flat per-frame
     * figure. TRUE is the shipped behaviour; FALSE is the pre9 shape and
     * exists as the A/B lever for the playtest that has to confirm this.
     *
     * <p>T2 kept the lever pointing at the same place on purpose: FALSE
     * is now the only way to reproduce a pre-T2-shaped absolute slice
     * deliberately, which is exactly what bisecting a report against this
     * wave needs. See {@code ExtractDispatch}'s class javadoc.</p>
     */
    public boolean adaptiveExtraction = true;

    /**
     * RETIRED at T2 and read by nothing; the field survives so this wave
     * is one revertible file rather than a config-schema change. See
     * {@link #DEFAULT_EXTRACT_FRAME_RATE_FLOOR}, which explains why a
     * frame-rate floor was the defect rather than the safeguard.
     */
    public int extractFrameRateFloor = DEFAULT_EXTRACT_FRAME_RATE_FLOOR;

    /** Surface-band cut (drop cells below column top minus the offset). */
    public boolean surfaceBand = true;

    /** Blocks kept below the per-column top while the band is on. */
    public int bandOffset = DEFAULT_BAND_OFFSET;

    /** Which preset is selected, by {@link Preset#id}; Custom by default. */
    public String preset = Preset.CUSTOM.id;

    /** Layer 1: the first ring past the render distance. Maximum accuracy. */
    public LayerSettings layer1 = LayerSettings.layer1Defaults();

    /** Layer 2: off, radius 0, every feature neutral. Not wired yet. */
    public LayerSettings layer2 = new LayerSettings();

    /** Layer 3: off, radius 0, every feature neutral. Not wired yet. */
    public LayerSettings layer3 = new LayerSettings();

    /**
     * Schema version for one-shot migrations, same contract as
     * {@code MesheliumConfig.configVersion}: zero on a fresh object so a
     * pre-versioning file and a fresh install both run every migration,
     * which is correct as long as each migration is idempotent.
     */
    public int configVersion = 0;

    /** Current schema version. Bump when adding a migration below. */
    private static final int CURRENT_CONFIG_VERSION = 8;

    // ------------------------------------------------------------------
    // Ranges (public: the screens and the harness read them)
    // ------------------------------------------------------------------

    public static final int MIN_L1_RADIUS_CHUNKS = 0;
    /** Format ceiling for now; the honest max is pinned in W3 review. */
    public static final int MAX_L1_RADIUS_CHUNKS = 192;
    /**
     * Default far radius in chunks. 64, NOT the 96 first drafted: the
     * region-id arithmetic was worked against the real constants after
     * the fact (regions span 8x8 chunks by 4 sections; the id budget is
     * 2048; the promote gate reserves vanilla's worst-case grid demand)
     * and a 96-chunk ring in ordinary overworld terrain wants roughly
     * 530 to 1060 ids on top of the near field's 300 to 700, so
     * promotion stalls somewhere around 78 to 88 chunks. The stall is
     * graceful - the horizon simply stops as a coherent disc, nothing
     * drops, no guard trips - but a default that silently cannot reach
     * its own number is dishonest. 64 fits with room to spare (about
     * 390 ids at two region rows even with a busy near field); larger
     * values stay available on the slider with the stall as backstop.
     */
    public static final int DEFAULT_L1_RADIUS_CHUNKS = 64;

    /**
     * The largest radius any PRESET is allowed to ask for, today.
     *
     * <p>Same arithmetic as {@link #DEFAULT_L1_RADIUS_CHUNKS}: full-scale
     * shells stall somewhere around 78 to 88 chunks on ordinary overworld
     * terrain, so a preset written at 96 or 128 would render as something
     * smaller and the player would have no way to know. The slider still
     * goes to {@link #MAX_L1_RADIUS_CHUNKS} for anyone who wants to push
     * it; the presets stay inside what the code can actually deliver. This
     * ceiling moves when half-scale remesh lands and a layer can cover
     * four times the ground for the same region ids.</p>
     */
    public static final int MAX_HONEST_PRESET_RADIUS_CHUNKS = 64;

    public static final int MIN_BAND_OFFSET = 4;
    public static final int MAX_BAND_OFFSET = 32;
    /** Resolution 4, FAR-FIELD-DESIGN.md section 10: fixed at 8 initially. */
    public static final int DEFAULT_BAND_OFFSET = 8;

    /** Zero means the far field never extracts on the game thread at all. */
    public static final int MIN_EXTRACT_BUDGET_MILLIS = 0;
    /**
     * Ceiling on the value of the extraction-slice setting. 8 ms is
     * already half of a 60 fps frame.
     *
     * <p>Since T2 this bound is nearly decorative on the shipped rule:
     * the budget is a fraction of the measured frame capped at 2 ms, and
     * this setting is a further USER ceiling on top of that, so any value
     * above 2 never binds at all. It matters at the bottom of the range,
     * where a player can pin the far field under the fraction rule's own
     * cap, and at zero, which still means "never touch the game
     * thread".</p>
     */
    public static final int MAX_EXTRACT_BUDGET_MILLIS = 8;
    /**
     * The MOST game-thread time the far field may take in one frame, in
     * milliseconds - a hard user ceiling, and zero still means the far
     * field never extracts on the game thread at all.
     *
     * <p><b>Read the direction carefully: it inverted at T2.</b> This was
     * the slice the far field may ALWAYS take - a floor under an adaptive
     * budget - and that floor is the single largest term in the owner's
     * "drops 200 to 60" report: three milliseconds is 60% of the 5 ms
     * frame a 200 fps client has, and the floor guaranteed it whatever
     * the frame rate. The budget is now a fraction of the MEASURED frame
     * (a tenth, capped at 2 ms, floored at 0.25 ms - see
     * {@code ExtractDispatch.sliceBudgetFor}) and this value is a ceiling
     * over that fraction: it can only ever make the far field cheaper.
     * The stored value, the range and the meaning a player would attach
     * to it ("how many milliseconds of my frame may this take") are all
     * unchanged, so there is no migration; at the shipped 3 it does not
     * bind, because the fraction rule's own cap is 2.</p>
     *
     * <p>The unit was already per-FRAME rather than per 50 ms of wall
     * clock (pre9): a fixed wall-clock window did nothing at all in six
     * or seven frames out of every seven on a fast client.</p>
     *
     * <p>Sized from the measured cost of one extraction rather than a
     * guess. {@code ShellExtractor}'s own census (300 real 26.2 chunks)
     * puts {@code closeLeaks} at a mean of 24.7k probes, p95 54.0k and a
     * worst chunk of 83.2k, on top of the base band walk's ~26k paletted
     * reads, which that class times at order 0.4 ms. At the same ~15 ns
     * per read that is roughly 0.8 ms for a mean chunk, 1.2 ms at p95 and
     * 1.7 ms worst. So 3 ms buys three to four chunks per frame - and it
     * is a CEILING, not a target: the budget is only ever spent when there
     * is something to extract.</p>
     */
    public static final int DEFAULT_EXTRACT_BUDGET_MILLIS = 3;

    /** Lower clamp on the retired frame-rate floor field. */
    public static final int MIN_EXTRACT_FRAME_RATE_FLOOR = 30;
    /** Upper clamp on the retired frame-rate floor field. */
    public static final int MAX_EXTRACT_FRAME_RATE_FLOOR = 240;
    /**
     * <b>RETIRED at T2. Nothing reads this any more, and the reason it
     * was retired is the reason the whole wave exists.</b>
     *
     * <p>It named the frame rate the extraction budget tried not to spend
     * below, and the budget rule took a third of the gap between the
     * measured frame and it. That reads like prudence and is the
     * opposite: against a FIXED floor the gap GROWS as the frame shrinks,
     * so a faster machine was handed a bigger absolute slice out of a
     * smaller frame. The owner reported "this drops 200 to 60 while its
     * going on" - and 60 is exactly the number in this field. The
     * algorithm was not failing; it was defending precisely the target it
     * had been given, and the target was wrong.</p>
     *
     * <p>The replacement ({@code ExtractDispatch.sliceBudgetFor}) is a
     * share of the frame the player is actually getting, with no target
     * frame rate at all, so it behaves identically at 30, 60, 144 and
     * 300 fps.</p>
     *
     * <p><b>Why the field survives its own retirement.</b> It is a
     * persisted setting with a stored value in every existing farfield
     * JSON, an accessor, a clamp and a {@code -D} property override.
     * Gson ignores unknown keys and defaults absent ones, so removing it
     * would in fact load cleanly - but a schema change is not what a
     * small, reversible budget wave should also be doing, and keeping the
     * field means this wave can be reverted by reverting one file. It is
     * in NO settings row ({@code Control} has no entry for it), so no
     * page shows the player a dial that does nothing. Delete it in the
     * next wave that has a migration in it anyway.</p>
     */
    public static final int DEFAULT_EXTRACT_FRAME_RATE_FLOOR = 60;

    // ------------------------------------------------------------------
    // Background Saving (the owner's pre14 O4: "probably should have some
    // setting if the caching is going to cause that much of a performance
    // issue"). The value is a SPEED, not a millisecond count: what the
    // player is choosing is how much of their frame rate the far field
    // may spend turning loaded terrain into saved terrain.
    // ------------------------------------------------------------------

    /**
     * Background Saving: <b>3% of the measured frame</b>, capped at
     * 0.6 ms and floored at 0.075 ms.
     *
     * <p><b>T2 re-pointed the whole dial from milliseconds to a share of
     * the frame</b>, and Gentle is where that matters most: its published
     * promise has always been "a fixed sliver; your frame rate never
     * moves", and only a fraction can keep that promise on a machine
     * nobody measured it on. 3% is under a third of a millisecond at
     * 120 fps and 0.15 ms at 200. M6's note below still applies to why
     * the three points must differ at all - the earlier full-slice Gentle
     * was numerically identical to Balanced at every frame rate above
     * ~60, so the P3 audit found two of the dial's three points were the
     * same number on the owner's machine; scaling the floor and the cap
     * along with the share is what stops that recurring at the extremes,
     * where three different shares would otherwise all clamp to one
     * floor.</p>
     *
     * <p>Loss is unaffected on every point of the dial, unchanged from
     * P3: the leaving reserve is deliberately not scaled by it, and the
     * pin worker is off the frame budget entirely. The dial chooses how
     * fast the horizon FILLS, never how much terrain is kept.</p>
     */
    public static final int CACHE_GENTLE = 0;
    /**
     * Background Saving: <b>6% of the measured frame</b>, capped at
     * 1.2 ms and floored at 0.15 ms. The middle point, and the one to
     * choose on a machine where the shipped setting is still noticeable.
     */
    public static final int CACHE_BALANCED = 1;
    /**
     * Background Saving: <b>10% of the measured frame</b>, capped at
     * 2 ms and floored at 0.25 ms - the shipped point, and
     * docs/FARFIELD-PERF-BRIEF.md section 3's rule exactly.
     *
     * <p>At 200 fps that is 0.5 ms of a 5 ms frame. The rule it replaced
     * took 3 to 6.7 ms of that same frame and settled the client at the
     * 60 fps its frame-rate floor was written to defend, which is the
     * owner's T2 report in one sentence.</p>
     */
    public static final int CACHE_FAST = 2;
    /**
     * Layer 1 as shipped: {@link #CACHE_FAST}.
     *
     * <p><b>Argued rather than inherited.</b> CLAUDE.md's uncertainty rule
     * ships anything not visually certain default OFF, and this row is not
     * a look control - nothing it can be set to changes a pixel. What it
     * changes is how long the horizon takes to exist, and the three
     * releases before pre13 are the evidence: N1 found the ring had failed
     * to fill for the THIRD time, and the escalation this row can switch
     * off is most of what fixed it (7.2 min to 1.5-2.3 min at rd 32 /
     * LOD 120). Shipping {@link #CACHE_BALANCED} would hand that
     * regression back to every player who never opens this page, to spare
     * them a frame-rate dip that ENDS when the backlog does. The dip is
     * the thing worth making switchable; it is not the thing worth
     * making default.</p>
     *
     * <p><b>T2 did not change this default</b>, and the argument above is
     * why: the escalation N1 needed is gone, the shipped point is now a
     * tenth of the frame rather than most of it, and shipping anything
     * gentler than that would hand the slow-fill regression back for a
     * dip that no longer exists.</p>
     */
    public static final int DEFAULT_CACHE_SPEED = CACHE_FAST;

    /** Detail scale: 1:1, the only scale the mesher can produce today. */
    public static final int SCALE_FULL = 1;
    /** Detail scale: 2x2x2 downsample (scale tag 1). Not wired yet. */
    public static final int SCALE_HALF = 2;
    /** Detail scale: 4x4x4 downsample (scale tag 2). Not wired yet. */
    public static final int SCALE_QUARTER = 4;

    /** Texture simplification: off, full resolution. */
    public static final int TEXTURE_FULL = 0;
    /** Texture simplification: the strongest step, a flat colour. */
    public static final int TEXTURE_FLAT = 4;

    // ------------------------------------------------------------------
    // Colour blending (the owner's "oceans do not mix colour" and "chunks
    // do not mix"). The VALUE IS THE GRID DIVISOR the store writes: a
    // record carries an (N+1)x(N+1) corner grid of sampled biome colours
    // per palette entry, sampled at block offsets 0, 16/N, ... 16, so a
    // sample on a chunk edge is at the same world position as the
    // neighbour's and the two agree by construction. 0 means the format-2
    // shape: one colour per palette entry per chunk.
    // ------------------------------------------------------------------

    /** One sampled colour per block type per chunk (the pre-pre8 look). */
    public static final int BLEND_PER_CHUNK = 0;
    /**
     * Vanilla's own per-block colour: the record stores a 16x16 CELL
     * FIELD ({@code ShellCodec.TINT_SIDE_CELL}) holding
     * {@code ClientLevel.calculateBlockTint} for every block column, and
     * the mesher reads it with no interpolation. Was "a 3x3 corner grid,
     * one sample every 8 blocks" through pre18; pre19's S1 replaced the
     * whole corner-grid family with the field it was approximating, so
     * this value now means "per block" and there is nothing finer to
     * offer.
     */
    public static final int BLEND_COARSE = 2;
    /**
     * RETIRED at pre19: the 5x5 corner grid it named no longer exists,
     * and {@link #normalizeBlend} folds it onto {@link #BLEND_COARSE} so
     * a config already holding it keeps working, produces identical
     * bytes, and cannot move {@link #farSaveSignature()} against a
     * setting that draws the same pixels. Kept as a constant only so an
     * existing {@code -Dmeshelium.farfield.l1ColourBlend=4} stays legal.
     */
    public static final int BLEND_FINE = 4;

    // ------------------------------------------------------------------
    // Lighting. FLAT is what pre7 shipped: every far vertex carries sky 15
    // and block 0. GLOW costs nothing and lights a light-emitting block's
    // OWN faces. REAL stores the world's real light values per cell while
    // the chunk is live, which is the only way to get cave mouths, tree
    // shade and a torch's spill at far range - and the only one that
    // costs disk.
    // ------------------------------------------------------------------

    /** Flat synthesized light: full daylight, no block light. */
    public static final int LIGHT_FLAT = 0;
    /** Flat daylight plus each block's own light emission on its own faces. */
    public static final int LIGHT_GLOW = 1;
    /** Real per-cell sky and block light, sampled and stored at save time. */
    public static final int LIGHT_REAL = 2;

    // ------------------------------------------------------------------
    // Smooth lighting at distance (the owner's pre11 M2b: "per block
    // shading with smooth lighting ... it darkens corners and edges").
    // Three steps, cheapest first, because the third one is the only
    // shading row on this page that costs geometry.
    // ------------------------------------------------------------------

    /** No shading beyond the flat per-face directional shade. */
    public static final int SMOOTH_OFF = 0;
    /**
     * Edges only: the pre10 gradient laid along the edges of a MERGED
     * rectangle that run against an occluder. Free - it runs after the
     * greedy merge and cannot split a quad - but its gradient is as wide
     * as the rectangle, so on flat ground it is a broad wash rather than
     * vanilla's one-block darkening.
     */
    public static final int SMOOTH_EDGES = 1;
    /**
     * Full: vanilla's own per-corner ambient occlusion, computed per CELL
     * before the merge, so the darkening is one block wide exactly as it
     * is up close. Costs quads on broken ground (the merge can no longer
     * join two faces whose corners disagree); costs nothing on flat
     * ground, where nothing is occluded and every corner is still equal.
     */
    public static final int SMOOTH_FULL = 2;

    /** Layer 1 as shipped: the owner asked for vanilla's look at range. */
    public static final int DEFAULT_SMOOTH_LIGHT = SMOOTH_FULL;

    // ------------------------------------------------------------------
    // Shading strength (the owner's pre10 "we can change its
    // opacity/intensity"). A percentage, because the owner asked for the
    // strength to be his to set. It scales whichever of the two steps
    // above is selected; 0 is off either way and costs a float compare
    // per merge bucket.
    // ------------------------------------------------------------------

    /** Shading off. */
    public static final int MIN_CONTACT_SHADE = 0;
    /** Full strength. */
    public static final int MAX_CONTACT_SHADE = 100;
    /**
     * Layer 1 as shipped, and 100 is not a taste call.
     *
     * <p>In {@link #SMOOTH_FULL} the slider is a fraction of vanilla's own
     * ambient-occlusion depth, so 100 draws EXACTLY what the near field
     * draws (corners at 0.8, 0.6 and 0.4 of full brightness) and anything
     * less would put a visible shading step at the handover band, where
     * the same hillside is drawn by both renderers in one frame. In
     * {@link #SMOOTH_EDGES} it keeps its pre10 meaning: 100 takes half the
     * brightness off a corner where two occluded edges meet.</p>
     */
    public static final int DEFAULT_CONTACT_SHADE = 100;

    // ------------------------------------------------------------------
    // The ladder
    // ------------------------------------------------------------------

    /**
     * The three LOD layers, nearest first.
     *
     * <p>{@link #key} is both the JSON field prefix and the property
     * prefix, which is what keeps {@code -Dmeshelium.farfield.l1RadiusChunks}
     * spelled the way it always was.</p>
     */
    public enum Layer {
        L1("l1", 1),
        L2("l2", 2),
        L3("l3", 3);

        /** Property/field prefix, e.g. {@code l2}. */
        public final String key;
        /** 1, 2 or 3: what the player sees in the row label. */
        public final int number;

        Layer(String key, int number) {
            this.key = key;
            this.number = number;
        }
    }

    /**
     * One control on a layer page. The enum exists so the screens can walk
     * a layer generically and so {@link #isWired(Layer, Control)} is the
     * SINGLE place that has to change when a wave connects one of them.
     */
    public enum Control {
        /** Is this layer drawn at all. */
        LAYER_ENABLED,
        /** How far out this layer reaches, in chunks. */
        DISTANCE,
        /** Full / half / quarter downsample of the stored shell. */
        SCALE,
        /** Force see-through blocks solid at this range. */
        SOLID_LEAVES,
        /** Draw oceans as one flat top sheet instead of a shell. */
        FLAT_WATER,
        /** Mip clamp, from a slight blur down to a flat colour. */
        TEXTURE_SIMPLIFY,
        /** 2x2 fragment shading rate for this layer's draws. */
        COARSE_SHADING,
        /** Synthesize this layer from a column heightfield only. */
        HEIGHTFIELD,
        /**
         * Save and draw plants that stand in water (kelp, seagrass, coral).
         * Extraction-time: it decides what reaches the store.
         */
        UNDERWATER_PLANTS,
        /**
         * Keep thin see-through blocks (torches, bars, plants) from being
         * erased by the alpha test at range. Mesh-time, no storage.
         */
        SMALL_DETAIL,
        /**
         * How finely biome colour is sampled: {@link #BLEND_PER_CHUNK},
         * {@link #BLEND_COARSE} or {@link #BLEND_FINE}. Extraction-time.
         */
        COLOUR_BLEND,
        /**
         * {@link #LIGHT_FLAT}, {@link #LIGHT_GLOW} or {@link #LIGHT_REAL}.
         * The first two are mesh-time; the third is extraction-time.
         */
        LIGHTING,
        /**
         * <b>Distant Water</b>: draw water the way Minecraft draws it, or
         * only with the light that happens to be saved with the record.
         *
         * <p>ON is <i>Stock Minecraft</i>. A record that carries a
         * {@link #LIGHT_REAL} plane is already exact and the mesher
         * changes nothing for it; a record that does not gets the light
         * engine's OWN rule re-derived from the record's geometry
         * ({@code ShellMesher.submergedLight}: sky falls one level per
         * block of water, because {@code BlockBehaviour.getLightDampening}
         * answers 1 for water and {@code SkyLightEngine.propagateIncrease}
         * subtracts exactly that per step). The two are the same function,
         * which is why this row is not a competing estimate and is not
         * superseded by Lighting — see {@link #isSuperseded}.</p>
         *
         * <p>OFF is <i>As Saved</i>: no derivation at all, so distant
         * water saved before Real Light — or saved at a chunk-receive seam
         * and not yet upgraded — draws at flat daylight while its
         * neighbours draw dark. That two-tone ocean is the ONLY thing this
         * row can produce that vanilla never shows, which is why ON is the
         * shipped value.</p>
         *
         * <p>Mesh-time, no storage: it repaints every record already on
         * disk the moment the ring re-meshes.</p>
         */
        WATER_LOOK,
        /**
         * {@link #SMOOTH_OFF}, {@link #SMOOTH_EDGES} or
         * {@link #SMOOTH_FULL}: how distant terrain is shaded where it
         * meets itself. Mesh-time, no storage, and it applies to every
         * record already on disk the moment the ring re-meshes.
         */
        SMOOTH_LIGHT,
        /**
         * Strength, {@link #MIN_CONTACT_SHADE} to
         * {@link #MAX_CONTACT_SHADE}, of whichever {@link #SMOOTH_LIGHT}
         * step is selected. Mesh-time, no storage, and it applies to every
         * record already on disk the moment the ring re-meshes.
         */
        CONTACT_SHADE,
        /**
         * <b>Block Variants</b> (pre18, the owner's R4): draw vanilla's
         * per-POSITION choice from a blockstate's variant list instead of
         * one fixed pick for the whole world.
         *
         * <p>38 blockstates in 26.2 list several variants and vanilla
         * rolls one per block from its position
         * ({@code SectionCompiler} ip 319-326 -> {@code tesselateBlock}
         * ip 0-21 -> {@code WeightedVariants.collectParts}); 46 of the 75
         * rows are the same model at a y or x rotation - grass_block's
         * top, sand, dirt, podzol, mycelium, netherrack, the concrete
         * powders - and the rest list genuinely different models
         * ({@code stone}/{@code stone_mirrored}, {@code bedrock},
         * {@code sculk}). With this OFF the far field draws one of them
         * everywhere and a grass plain reads as a repeating stamp beside
         * the near field's mix, which is what the owner saw.</p>
         *
         * <p>Mesh-time and NO storage: the roll is reproduced from the
         * cell's world position ({@code ShellMesher.variantDraw}), so it
         * repaints every record already on disk. <b>What it costs is the
         * MERGE</b>: two differently-rotated top faces have different UV
         * orientations and rightly refuse to merge, so a flat plane of
         * one rotating block goes from one quad toward one per cell
         * (~+190-256 quads, ~+12-16 KB of arena on that plane) and a
         * plains or desert column roughly doubles its quads. Nothing
         * outside the family pays anything. Default ON, because the bar
         * is that layer 1 looks identical.</p>
         */
        BLOCK_VARIANTS,
        /**
         * <b>Background Saving</b>: {@link #CACHE_GENTLE},
         * {@link #CACHE_BALANCED} or {@link #CACHE_FAST} - how much of the
         * frame the far field may spend turning terrain the client is
         * holding into terrain on disk. Since T2 that is literally true:
         * the three points are 3%, 6% and 10% OF THE MEASURED FRAME, so
         * the row means the same thing on every machine instead of
         * meaning three different things depending on the frame rate.
         *
         * <p>The only row on the page that is in NEITHER appearance
         * signature, and that is not an oversight. It changes no pixel of
         * any record and no byte of any record; it changes the RATE at
         * which records are written. Putting it in
         * {@link #farMeshSignature()} would spend a whole ring reload to
         * change nothing, and putting it in {@link #farSaveSignature()}
         * would drop the extract-once tracker and re-save the entire held
         * window to change nothing - the L9 regression in both
         * directions at once.</p>
         */
        CACHE_SPEED
    }

    /**
     * Per-layer settings. Deliberately the same fourteen fields on every
     * layer even though only layer 1 reads any of them today: the owner
     * asked for "three LOD layers where we can choose what performance
     * optimizations to apply to them", and a ladder whose rungs have
     * different shapes is a ladder nobody can reason about.
     *
     * <p>The field initializers here are the CONSERVATIVE values (off,
     * radius 0, nothing simplified), which is what layers 2 and 3 ship
     * with. Layer 1's louder defaults come from {@link #layer1Defaults()}.
     * A hand-written config containing a partial {@code "layer1": {}}
     * object therefore lands on the conservative set and layer 1 reads as
     * disabled; that is visible on the screen in one glance and fixable
     * there, which is the right failure for a hand edit.</p>
     */
    public static final class LayerSettings {

        /** Is this layer drawn at all. */
        public boolean enabled = false;
        /** How far out this layer reaches, chunks. 0 = no ring. */
        public int radiusChunks = 0;
        /**
         * {@link FarFieldConfig#SCALE_FULL},
         * {@link FarFieldConfig#SCALE_HALF} or
         * {@link FarFieldConfig#SCALE_QUARTER}.
         */
        public int scale = SCALE_FULL;
        /** Force see-through blocks solid at this range. */
        public boolean solidLeaves = false;
        /** Draw oceans as one flat top sheet. */
        public boolean flatWater = false;
        /**
         * {@link FarFieldConfig#TEXTURE_FULL} to
         * {@link FarFieldConfig#TEXTURE_FLAT}.
         */
        public int textureSimplify = TEXTURE_FULL;
        /** 2x2 fragment shading rate for this layer. */
        public boolean coarseShading = false;
        /** Synthesize from a column heightfield instead of stored shells. */
        public boolean heightfield = false;

        // ------------------------------------------------------------
        // The pre8 ACCURACY controls. Unlike the eight above, these do
        // not cheapen a layer, they make it look more like the real
        // world, so their conservative value is the OFF one and layer 1
        // turns them on in layer1Defaults().
        // ------------------------------------------------------------

        /** Save and draw kelp, seagrass and other plants that stand in water. */
        public boolean underwaterPlants = false;
        /** Keep thin see-through blocks visible at range (torches, bars, plants). */
        public boolean smallDetail = false;
        /**
         * {@link Control#WATER_LOOK}: true is <i>Stock Minecraft</i>,
         * false is <i>As Saved</i>. Mesh-time; costs nothing and repaints
         * records already on disk.
         *
         * <p><b>The field and the property key keep the old
         * {@code waterDepth} spelling on purpose.</b> The row was called
         * Underwater Shading through pre12 and this value is in every
         * saved {@code meshelium-farfield.json}; a rename here would make
         * GSON drop the player's choice silently, and a rename of
         * {@code l1WaterDepth} would break a {@code -D} flag that is
         * documented in docs/FARFIELD-WAVES.md. The stale name is the
         * cheaper of the two wrongs and it is confined to this line and
         * to {@code propertySuffix}.</p>
         */
        public boolean waterDepth = false;
        /**
         * {@link FarFieldConfig#BLEND_PER_CHUNK},
         * {@link FarFieldConfig#BLEND_COARSE} or
         * {@link FarFieldConfig#BLEND_FINE}.
         */
        public int colourBlend = BLEND_PER_CHUNK;
        /**
         * {@link FarFieldConfig#LIGHT_FLAT},
         * {@link FarFieldConfig#LIGHT_GLOW} or
         * {@link FarFieldConfig#LIGHT_REAL}.
         */
        public int lighting = LIGHT_FLAT;
        /**
         * {@link FarFieldConfig#SMOOTH_OFF},
         * {@link FarFieldConfig#SMOOTH_EDGES} or
         * {@link FarFieldConfig#SMOOTH_FULL}: how distant terrain is
         * shaded where it meets itself. Mesh-time; costs no bytes
         * anywhere and fixes terrain already on disk.
         */
        public int smoothLight = SMOOTH_OFF;
        /**
         * Strength of whichever {@link #smoothLight} step is selected,
         * {@link FarFieldConfig#MIN_CONTACT_SHADE} to
         * {@link FarFieldConfig#MAX_CONTACT_SHADE}. Mesh-time; costs no
         * bytes anywhere and fixes terrain already on disk.
         */
        public int contactShade = MIN_CONTACT_SHADE;
        /**
         * {@link Control#BLOCK_VARIANTS}: draw vanilla's per-position
         * weighted variant. Mesh-time; costs no bytes anywhere, fixes
         * terrain already on disk, and spends quads on rotated ground.
         */
        public boolean blockVariants = false;
        /**
         * {@link FarFieldConfig#CACHE_GENTLE},
         * {@link FarFieldConfig#CACHE_BALANCED} or
         * {@link FarFieldConfig#CACHE_FAST}: how much frame time the far
         * field may spend SAVING. Not an appearance setting at all - see
         * {@link Control#CACHE_SPEED} for why it is in neither signature.
         */
        public int cacheSpeed = CACHE_GENTLE;

        /**
         * Layer 1 as shipped: on,
         * {@value FarFieldConfig#DEFAULT_L1_RADIUS_CHUNKS} chunks, every
         * cheapening control off and every accuracy control on, because
         * the owner's layer 1 must look like ordinary terrain.
         *
         * <p><b>The two that cost real money are on here since pre12, and
         * the owner asked for both by name.</b>
         * {@link FarFieldConfig#LIGHT_REAL} adds a light byte per stored
         * cell - measured at +12 to +71 B of zlib'd record, because the
         * codec writes it as a trailing plane and the extractor drops a
         * plane that is uniform daylight - and it is the only thing that
         * makes a distant cave dark, a tree cast shade, or the horizon
         * follow night and day. {@link FarFieldConfig#SMOOTH_FULL} costs
         * quads where the ground is broken and nothing where it is flat.
         * The one still NOT on is {@link FarFieldConfig#BLEND_FINE}, which
         * quadruples the colour grid that
         * {@link FarFieldConfig#BLEND_COARSE} already makes seamless.</p>
         *
         * <p><b>No default moved in pre13's N4.</b>
         * {@link Control#WATER_LOOK} ships true, which is the value the
         * boolean it replaced ({@code waterDepth}, Underwater Shading) has
         * shipped since pre8 and which every existing config already
         * holds through the v4 migration. What changed is that the row is
         * no longer locked, is named for what it targets, and is hashed
         * into {@link #farMeshSignature()} again - not what it draws.</p>
         */
        public static LayerSettings layer1Defaults() {
            LayerSettings settings = new LayerSettings();
            settings.enabled = true;
            settings.radiusChunks = DEFAULT_L1_RADIUS_CHUNKS;
            settings.underwaterPlants = true;
            settings.smallDetail = true;
            settings.waterDepth = true;   // WATER_LOOK: Stock Minecraft
            settings.colourBlend = BLEND_COARSE;
            settings.lighting = LIGHT_REAL;
            settings.smoothLight = DEFAULT_SMOOTH_LIGHT;
            settings.contactShade = DEFAULT_CONTACT_SHADE;
            settings.blockVariants = true;   // R4: the accuracy directive
            settings.cacheSpeed = DEFAULT_CACHE_SPEED;
            return settings;
        }

        /** Clamp anything a hand edit could have put out of range. */
        void normalize() {
            this.radiusChunks = Math.max(MIN_L1_RADIUS_CHUNKS,
                    Math.min(MAX_L1_RADIUS_CHUNKS, this.radiusChunks));
            this.scale = normalizeScale(this.scale);
            this.textureSimplify = Math.max(TEXTURE_FULL,
                    Math.min(TEXTURE_FLAT, this.textureSimplify));
            this.colourBlend = normalizeBlend(this.colourBlend);
            this.lighting = Math.max(LIGHT_FLAT, Math.min(LIGHT_REAL, this.lighting));
            this.smoothLight = Math.max(SMOOTH_OFF,
                    Math.min(SMOOTH_FULL, this.smoothLight));
            this.contactShade = Math.max(MIN_CONTACT_SHADE,
                    Math.min(MAX_CONTACT_SHADE, this.contactShade));
            this.cacheSpeed = Math.max(CACHE_GENTLE,
                    Math.min(CACHE_FAST, this.cacheSpeed));
        }
    }

    /**
     * Snap to one of the two surviving values; anything else is
     * per-chunk. {@link #BLEND_FINE} folds onto {@link #BLEND_COARSE}
     * (pre19, S1): both now mean the per-block cell field, and letting
     * two values that write identical bytes carry different save
     * signatures would spend a full re-extraction on a no-op flip.
     */
    private static int normalizeBlend(int blend) {
        return switch (blend) {
            case BLEND_COARSE, BLEND_FINE -> BLEND_COARSE;
            default -> BLEND_PER_CHUNK;
        };
    }

    /** Snap to one of the three legal scales; anything else reads as full. */
    private static int normalizeScale(int scale) {
        return switch (scale) {
            case SCALE_HALF -> SCALE_HALF;
            case SCALE_QUARTER -> SCALE_QUARTER;
            default -> SCALE_FULL;
        };
    }

    // ------------------------------------------------------------------
    // Presets
    // ------------------------------------------------------------------

    /**
     * The preset ladder the owner asked for (D3): "no lod (always load
     * chunks), highest detail, balanced, performant, ultra performant, and
     * then just have it go to custom anytime they change anything".
     *
     * <h2>What each preset writes, today</h2>
     * <p>Every preset writes EVERY field below; nothing is left at
     * whatever the player had. Read the table as "after selecting this
     * row, the config is exactly this".</p>
     *
     * <table border="1">
     * <caption>Preset table, pre2</caption>
     * <tr><th>Preset</th><th>Far Terrain</th><th>Render distance</th>
     *     <th>L1 on</th><th>L1 distance</th><th>L1 features</th>
     *     <th>L2</th><th>L3</th><th>Saved detail</th></tr>
     * <tr><td>No LOD</td><td>OFF</td><td>32</td>
     *     <td>on</td><td>64</td><td>all neutral</td>
     *     <td>off, 0</td><td>off, 0</td><td>Surface Only, 8</td></tr>
     * <tr><td>Highest Detail</td><td>ON</td><td>32</td>
     *     <td>on</td><td>64</td><td>all neutral</td>
     *     <td>off, 0</td><td>off, 0</td><td>Surface Only, 8</td></tr>
     * <tr><td>Balanced</td><td>ON</td><td>16</td>
     *     <td>on</td><td>64</td><td>all neutral</td>
     *     <td>off, 0</td><td>off, 0</td><td>Surface Only, 8</td></tr>
     * <tr><td>Performant</td><td>ON</td><td>12</td>
     *     <td>on</td><td>48</td><td>all neutral</td>
     *     <td>off, 0</td><td>off, 0</td><td>Surface Only, 8</td></tr>
     * <tr><td>Ultra Performant</td><td>ON</td><td>8</td>
     *     <td>on</td><td>32</td><td>all neutral</td>
     *     <td>off, 0</td><td>off, 0</td><td>Surface Only, 8</td></tr>
     * <tr><td>Custom</td><td colspan="8">writes nothing at all; it only
     *     records that the config is the player's own mix</td></tr>
     * </table>
     *
     * <h2>Why those numbers and not bigger ones</h2>
     * <ul>
     * <li><b>No preset asks for more than
     *     {@value FarFieldConfig#MAX_HONEST_PRESET_RADIUS_CHUNKS} chunks of
     *     far terrain.</b> Full-scale shells stall around 78 to 88 chunks
     *     on the region-id budget, so a preset at 96 would silently render
     *     smaller than its own name. The slider still reaches
     *     {@value FarFieldConfig#MAX_L1_RADIUS_CHUNKS} for anyone who wants
     *     to find their own stall.</li>
     * <li><b>The ladder spends its budget on the render distance, not the
     *     LOD ring</b>, because that is where the cost is. The owner's own
     *     pre1 playtest measured an absurd LOD radius with a 16-chunk
     *     render distance "running great" at about 3 GB of VRAM and very
     *     little RAM. So Balanced keeps the full honest 64 chunks of far
     *     terrain and pays for it by halving the expensive half.</li>
     * <li><b>Highest Detail sets the render distance to 32</b>, the owner's
     *     own words ("set the render distance to like 32"). 32 is also
     *     vanilla's own maximum, so it is always inside the option's range
     *     and can never be silently rejected.</li>
     * <li><b>Every preset keeps Surface Only</b>, including Highest
     *     Detail. Underground faces cannot be seen from the far ring by
     *     construction, so "Everything" would buy roughly four times the
     *     disk for nothing visible. It stays a hand switch for players who
     *     want it.</li>
     * <li><b>No LOD turns the master off rather than setting the radius to
     *     zero.</b> Master-off is the zero-cost path (no thread, no folder,
     *     no allocation); a zero radius still arms everything and then
     *     draws nothing. The layer-1 rows are left at the Highest Detail
     *     values so that flipping the master back on by hand does
     *     something sensible.</li>
     * </ul>
     *
     * <h2>PLANNED (do not read as shipped behaviour)</h2>
     * <p>When half-scale remesh, flat water and texture simplification are
     * wired, this table is where their preset values land: Performant is
     * intended to gain a half-scale layer 2 out to about 128 chunks and
     * Ultra Performant a quarter-scale layer 3 beyond that, with solid
     * leaves and simplified textures on the cheap layers only. None of
     * that is written today, ON PURPOSE: a preset that stored
     * {@code solidLeaves = true} into an inert field would silently change
     * the owner's game on the update that wires it, which is exactly the
     * trap the uncertainty rule exists to prevent. Presets write only
     * fields that do something now, and set everything else neutral.</p>
     */
    public enum Preset {

        /**
         * The player's own mix. Selecting it writes NOTHING except the
         * selection itself, which is the whole point: it is where the
         * screen lands the moment any single control is touched.
         */
        CUSTOM("custom", -1, DEFAULT_L1_RADIUS_CHUNKS, false),

        /** No distant terrain at all; real chunks out to vanilla's 32. */
        NO_LOD("no_lod", 32, DEFAULT_L1_RADIUS_CHUNKS, false),

        /** Everything as far as it honestly goes. */
        HIGHEST_DETAIL("highest_detail", 32, 64, true),

        /** The owner's measured sweet spot: half the chunks, all the view. */
        BALANCED("balanced", 16, 64, true),

        /** For machines that need the chunk count down. */
        PERFORMANT("performant", 12, 48, true),

        /** For machines that need everything down. */
        ULTRA_PERFORMANT("ultra_performant", 8, 32, true);

        /** Stable id written to the config file. */
        public final String id;
        private final int renderDistanceChunks;
        private final int l1RadiusChunks;
        private final boolean farFieldOn;

        Preset(String id, int renderDistanceChunks, int l1RadiusChunks,
                boolean farFieldOn) {
            this.id = id;
            this.renderDistanceChunks = renderDistanceChunks;
            this.l1RadiusChunks = l1RadiusChunks;
            this.farFieldOn = farFieldOn;
        }

        /**
         * Vanilla render distance this preset wants, in chunks, or -1 for
         * {@link #CUSTOM} which manages nothing.
         *
         * <p>Applied by the screen, never here: this class is reachable
         * from mixins on both backends and from the IO thread, and it must
         * not so much as name {@code Minecraft}.</p>
         */
        public int renderDistanceChunks() {
            return this.renderDistanceChunks;
        }

        /** Layer-1 radius this preset writes, chunks. */
        public int l1RadiusChunks() {
            return this.l1RadiusChunks;
        }

        /** Whether this preset leaves the master switch on. */
        public boolean farFieldOn() {
            return this.farFieldOn;
        }

        /** True for every preset except {@link #CUSTOM}. */
        public boolean managesValues() {
            return this != CUSTOM;
        }

        /** The row order on the screen: Custom first, then cheapest last. */
        public static final Preset[] LADDER = {
            CUSTOM, NO_LOD, HIGHEST_DETAIL, BALANCED, PERFORMANT, ULTRA_PERFORMANT
        };

        /** Parse an id back, falling back to {@link #CUSTOM}. */
        public static Preset byId(String id) {
            if (id != null) {
                for (Preset preset : values()) {
                    if (preset.id.equals(id)) {
                        return preset;
                    }
                }
            }
            return CUSTOM;
        }
    }

    /**
     * Which layer/control pairs are connected to running code TODAY.
     *
     * <p>One method, one truth. Every screen row asks this before deciding
     * whether it is a live control or a "not used yet" placeholder, so the
     * wave that wires half-scale remesh edits exactly this switch and the
     * two screens follow. Getting it wrong in the generous direction is
     * the expensive mistake: a control that looks live and does nothing is
     * how a settings page loses a player's trust.</p>
     *
     * <p>Today, and layer 1 only: on/off and distance (read by
     * {@code FarFieldResidency}'s promote/demote walker every pump),
     * {@link Control#CACHE_SPEED} (read once per slice by
     * {@code ExtractDispatch.sliceBudgetFor}), plus the ACCURACY rows -
     * {@link Control#UNDERWATER_PLANTS} and {@link Control#COLOUR_BLEND}
     * in {@code ShellExtractor}, and
     * {@link Control#SOLID_LEAVES}, {@link Control#LIGHTING},
     * {@link Control#WATER_LOOK}, {@link Control#SMOOTH_LIGHT} and
     * {@link Control#CONTACT_SHADE} in
     * {@code ShellMesher} (LIGHTING's third step is read in both). The
     * remaining rows - scale, flat water, texture simplification,
     * coarse shading and heightfield - are still placeholders, and
     * {@link Control#SMALL_DETAIL} is {@link #isRetired}.</p>
     *
     * <p><b>Every one of them is layer 1 only by construction.</b>
     * The extractor writes ONE record that every layer reads, and the
     * mesher meshes it once, so both of them ask
     * {@code Layer.L1} by name. When layer 2 lands, whatever it wants
     * from these rows has to arrive with a remesh path that can tell the
     * layers apart; until then answering "wired" for L2 would be the
     * generous mistake this method exists to prevent.</p>
     */
    public static boolean isWired(Layer layer, Control control) {
        if (layer != Layer.L1) {
            return false;
        }
        return switch (control) {
            case LAYER_ENABLED, DISTANCE, UNDERWATER_PLANTS,
                 COLOUR_BLEND, LIGHTING, SOLID_LEAVES, WATER_LOOK,
                 SMOOTH_LIGHT, CONTACT_SHADE, BLOCK_VARIANTS,
                 CACHE_SPEED -> true;
            // SMALL_DETAIL joined the unwired list in pre13 and the reason
            // is worth keeping: it had two steps and vanilla owns both.
            // pre12's M6 removed the first (vanilla already rescales alpha
            // per mip to preserve cutout coverage, so lowering the cutoff
            // only added back coverage vanilla had deliberately discarded -
            // that was the kelp bug). N2/N3 removed the second: a quad's
            // material ALREADY is vanilla's own measurement of that quad's
            // own UV window (FaceBakery.bakeQuad -> computeMaterialTransparency
            // -> SpriteContents.computeTransparency), so overriding it could
            // only paint what vanilla discards - that was the black leaf
            // litter. Nothing honest is left for the row to do in 26.2, and
            // a control that looks live and does nothing is how a settings
            // page loses a player's trust.
            case SCALE, FLAT_WATER, TEXTURE_SIMPLIFY, COARSE_SHADING, HEIGHTFIELD,
                 SMALL_DETAIL -> false;
        };
    }

    /**
     * A row that is unwired because the feature is GONE, not because it
     * has not been built yet — the distinction {@link #isWired} alone
     * cannot draw, and the screen would otherwise label it "not used yet"
     * and promise an update that is never coming.
     *
     * <p>{@link Control#SMALL_DETAIL} is the first of these. Both of its
     * steps turned out to be things vanilla already does, and doing them
     * a second time is what produced two of the owner's bug reports: the
     * kelp standing above the water (pre12's M6 — vanilla rescales alpha
     * per mip to hold cutout coverage, so lowering the cutoff only added
     * back what vanilla had deliberately discarded) and the black leaf
     * litter and flower beds (pre13's N2/N3 — a quad's material already
     * IS vanilla's measurement of that quad's own UV window, so
     * overriding it could only paint what vanilla discards). The row's
     * own motivating case never needed it either: a torch's texture is
     * fully opaque across its crop, so {@code FaceBakery} bakes it SOLID
     * and it never reaches the cutout path at all.</p>
     *
     * <p>Kept as a held row rather than deleted, because the value is
     * still in every saved config and a player who set it deserves to see
     * where it went.</p>
     */
    public static boolean isRetired(Layer layer, Control control) {
        return layer == Layer.L1 && control == Control.SMALL_DETAIL;
    }

    /**
     * Is this control WIRED but currently overruled by another row, so
     * that moving it cannot change a pixel?
     *
     * <p><b>No control answers true today, and the one that did was
     * wrong.</b> pre12's F4 locked {@link Control#WATER_LOOK} whenever
     * {@link #LIGHT_REAL} was selected, on the argument that the stored
     * light plane already carries the answer the row derives. That is
     * true of a record that HAS a plane and false of every record that
     * does not, and a live world always contains both: a column taken at
     * the chunk-receive seam is saved with no plane by construction
     * (its record has not seen E3 yet, so G1 holds the job) and only
     * gets one when the catch-up sweep re-extracts it, which is exactly
     * the backlog pre13's N1 is about. So the lock froze a row that was
     * still deciding how a large and moving fraction of the ring drew its
     * water - and froze it at whatever value the player happened to hold,
     * with no way back to it from the screen.</p>
     *
     * <p>{@link Control#WATER_LOOK}'s own javadoc carries the reason the
     * supersession argument does not hold: the derivation and the plane
     * are the SAME function, so on a planed record the row is a no-op
     * rather than a competing estimate, and on an un-planed one it is the
     * only thing standing between the owner and a two-tone ocean. A no-op
     * is not a dead control; a dead control is one that cannot matter for
     * any record, and this one can.</p>
     *
     * <p><b>Kept as the mechanism rather than deleted.</b>
     * {@code MesheliumFarLayerScreen} asks this in {@code isActive} and in
     * {@code tip}, and {@code refreshLockedRows} re-applies it live so a
     * lock lands the moment the row that imposes it moves rather than on
     * the next visit to the page. Both call sites run today on the false
     * answer, so the day a real relation appears it is one line here.</p>
     */
    public static boolean isSuperseded(Layer layer, Control control) {
        return false;
    }

    /** True when nothing on this layer is connected to code yet. */
    public static boolean isLayerWired(Layer layer) {
        for (Control control : Control.values()) {
            if (isWired(layer, control)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Mutation helpers (the screens call these; each one is one intent)
    // ------------------------------------------------------------------

    /** The settings object for a layer; never null after {@link #load()}. */
    public LayerSettings layer(Layer layer) {
        return switch (layer) {
            case L1 -> this.layer1;
            case L2 -> this.layer2;
            case L3 -> this.layer3;
        };
    }

    /**
     * Record that the config no longer matches any preset.
     *
     * <p>Values are NOT touched, per the brief: "changing ANY individual
     * control flips the selection to Custom without touching the values".
     * Returns true when the selection actually moved, so a caller can skip
     * a redundant save.</p>
     */
    public boolean markCustom() {
        if (Preset.CUSTOM.id.equals(this.preset)) {
            return false;
        }
        this.preset = Preset.CUSTOM.id;
        return true;
    }

    /**
     * Write a whole preset over the config. See {@link Preset} for the
     * table of what each one lands on.
     *
     * <p>Does NOT save and does NOT touch the vanilla render distance: the
     * caller owns both, because saving belongs with the caller's other
     * writes and the render distance is a {@code Minecraft} object this
     * class may not reach.</p>
     */
    public void applyPreset(Preset preset) {
        this.preset = preset.id;
        if (!preset.managesValues()) {
            return;
        }
        this.enabled = preset.farFieldOn();
        this.surfaceBand = true;
        this.bandOffset = DEFAULT_BAND_OFFSET;
        this.layer1 = LayerSettings.layer1Defaults();
        this.layer1.radiusChunks = Math.min(preset.l1RadiusChunks(),
                MAX_HONEST_PRESET_RADIUS_CHUNKS);
        this.layer2 = new LayerSettings();
        this.layer3 = new LayerSettings();
        this.l1RadiusChunks = this.layer1.radiusChunks;
    }

    // ------------------------------------------------------------------
    // Schema
    // ------------------------------------------------------------------

    /**
     * Bring an older file up to the current schema. Returns true if
     * anything changed and the file should be rewritten. Every migration
     * must be idempotent: {@link #configVersion} is zero on a fresh object
     * as well as on a pre-versioning file, so they all run on both.
     */
    private boolean migrate() {
        if (configVersion >= CURRENT_CONFIG_VERSION) {
            return false;
        }
        if (configVersion < 2) {
            // v1 kept the layer-1 radius at the top level and had no
            // layers at all. The top-level number is the truth for such a
            // file; on a fresh object it is already the default, so this
            // is a no-op there. Clamped here rather than trusting the
            // earlier normalize() pass, which ran before this value
            // existed in its new home.
            this.layer1.enabled = true;
            this.layer1.radiusChunks = Math.max(MIN_L1_RADIUS_CHUNKS,
                    Math.min(MAX_L1_RADIUS_CHUNKS, this.l1RadiusChunks));
        }
        if (configVersion < 3) {
            // v2 had no accuracy rows at all, so GSON left the four new
            // fields at LayerSettings' own initializers, which are the
            // CONSERVATIVE (off) values that layers 2 and 3 want. On layer
            // 1 that is the wrong answer: an existing player would silently
            // land on the pre8 look and would have to find four rows to get
            // back to the shipped one. Layer 1's shipped accuracy set is
            // written here instead, and only for layer 1. Idempotent:
            // re-running it writes the same four values.
            LayerSettings shipped = LayerSettings.layer1Defaults();
            this.layer1.underwaterPlants = shipped.underwaterPlants;
            this.layer1.smallDetail = shipped.smallDetail;
            this.layer1.colourBlend = shipped.colourBlend;
            this.layer1.lighting = shipped.lighting;
        }
        if (configVersion < 4) {
            // v3 had no water row at all (it shipped as Underwater
            // Shading and is now Distant Water, same field - see
            // LayerSettings.waterDepth), so GSON left the new field false.
            // On layer 1 that is the wrong answer for the same reason as
            // above: the row costs nothing, it is what makes a distant
            // ocean read as an ocean, and an existing player would
            // otherwise have to go and find it. Idempotent.
            this.layer1.waterDepth = LayerSettings.layer1Defaults().waterDepth;
        }
        if (configVersion < 5) {
            // v4 had no Contact Shading row, so GSON left the new field at
            // LayerSettings' own initializer, which is OFF - the right
            // answer for layers 2 and 3 and the wrong one for layer 1, for
            // the same reason as the two blocks above: the row costs no
            // bytes anywhere, it is what the owner asked for in the pre10
            // playtest, and an existing player would otherwise have to go
            // and find it. Idempotent.
            this.layer1.contactShade = LayerSettings.layer1Defaults().contactShade;
        }
        if (configVersion < 6) {
            // v5 had no Smooth Lighting row and shipped layer 1 on Glowing
            // Blocks. The owner's pre11 M2 asked for both of the things
            // this block writes, so an existing player lands on them
            // without having to find two rows.
            LayerSettings shipped = LayerSettings.layer1Defaults();
            this.layer1.smoothLight = shipped.smoothLight;
            // Only from the value v3 wrote: a player who deliberately
            // chose Flat Daylight, or who already found Real Light, keeps
            // what they chose. Idempotent - after this runs the field is
            // LIGHT_REAL and the test no longer matches.
            if (this.layer1.lighting == LIGHT_GLOW) {
                this.layer1.lighting = shipped.lighting;
            }
            // Same care with the strength: v5's shipped 50 moves to the
            // new shipped 100 (which is exactly vanilla's own ambient
            // occlusion, see DEFAULT_CONTACT_SHADE), and a hand-picked
            // number is left alone.
            if (this.layer1.contactShade == 50) {
                this.layer1.contactShade = shipped.contactShade;
            }
        }
        if (configVersion < 7) {
            // v6 had no Background Saving row, so GSON leaves the new
            // field at LayerSettings' own initializer, which is
            // CACHE_GENTLE - the conservative value layers 2 and 3 want
            // and the WRONG one for layer 1. Landing an existing player
            // on Gentle would silently hand back pre13's N1 (the ring
            // failing to fill, for the third time) to everyone who never
            // opens the page, which is the opposite of what the row is
            // for: it exists so a player who minds the frame-rate dip can
            // turn it DOWN. Idempotent - re-running writes the same
            // value.
            this.layer1.cacheSpeed = LayerSettings.layer1Defaults().cacheSpeed;
        }
        if (configVersion < 8) {
            // v7 had no Block Variants row, so GSON leaves the new field
            // at LayerSettings' own initializer, which is OFF - the right
            // answer for layers 2 and 3 and the wrong one for layer 1.
            // The row is the owner's R4 ("the grass texture on the top is
            // rotated and its not in lod") and the accuracy directive
            // says it ships on; an existing player would otherwise have
            // to go and find it. Idempotent.
            this.layer1.blockVariants = LayerSettings.layer1Defaults().blockVariants;
        }
        configVersion = CURRENT_CONFIG_VERSION;
        return true;
    }

    /**
     * Repair anything a hand edit (or a truncated write) could have left
     * unusable, so no caller downstream has to null-check a layer.
     */
    private void normalize() {
        if (this.layer1 == null) {
            this.layer1 = LayerSettings.layer1Defaults();
        }
        if (this.layer2 == null) {
            this.layer2 = new LayerSettings();
        }
        if (this.layer3 == null) {
            this.layer3 = new LayerSettings();
        }
        this.layer1.normalize();
        this.layer2.normalize();
        this.layer3.normalize();
        this.bandOffset = Math.max(MIN_BAND_OFFSET,
                Math.min(MAX_BAND_OFFSET, this.bandOffset));
        this.extractBudgetMillis = Math.max(MIN_EXTRACT_BUDGET_MILLIS,
                Math.min(MAX_EXTRACT_BUDGET_MILLIS, this.extractBudgetMillis));
        this.extractFrameRateFloor = Math.max(MIN_EXTRACT_FRAME_RATE_FLOOR,
                Math.min(MAX_EXTRACT_FRAME_RATE_FLOOR, this.extractFrameRateFloor));
        if (Preset.byId(this.preset) == Preset.CUSTOM) {
            // Normalizes a typo'd or removed id back to the honest answer
            // instead of leaving a string no screen can display.
            this.preset = Preset.CUSTOM.id;
        }
    }

    public static FarFieldConfig get() {
        FarFieldConfig loaded = instance;
        if (loaded == null) {
            synchronized (FarFieldConfig.class) {
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
    // The 1.6 ship gate
    // ------------------------------------------------------------------

    /**
     * <b>SHIP GATE.</b> {@code false} means Far Terrain is off and
     * unreachable in a released build: it never arms, and its page is
     * never built into the Advanced screen.
     *
     * <p>The feature is 1.7. Release 1.6 is NeoForge support, and this is
     * what keeps an unfinished distant-terrain layer out of it. Nothing is
     * deleted - the two screens, the preset table and every lang key still
     * ship - so flipping this to {@code true} restores the runtime, the
     * button and the boot-smoke assertions in one edit.
     *
     * <p>Deliberately a compile-time constant, not a config field and not
     * a system property. A player must not be able to switch on an
     * unfinished feature from a launcher argument, and a release must not
     * depend on a default that an existing
     * {@code config/meshelium-farfield.json} outranks - the owner's own
     * machine carries such a file from the pre-releases.
     *
     * <p><b>Flip it with a clean build.</b> A {@code public static final
     * boolean} is inlined into every reader by javac; a stale incremental
     * compile can leave the button hidden while the runtime is armed.</p>
     */
    public static final boolean FEATURE_ENABLED = false;

    /** Set once, the first time the gate turns the feature away. */
    private static volatile boolean gateReported;

    // ------------------------------------------------------------------
    // Effective-value resolvers (property ?? config, re-read per call -
    // the MesheliumConfig matrix, see its class javadoc)
    // ------------------------------------------------------------------

    /** {@code meshelium.farfield.enabled} ?? {@link #enabled}. */
    public static boolean enabled() {
        // THE SHIP GATE, at the one chokepoint every arm passes through:
        // FarField's gates, ExtractDispatch's `armed` volatile that all the
        // chunk-seam mixins test, the residency, TerrainResidency's sticky
        // farEverArmed, the scaling VRAM term and the fog mixin. Nothing
        // outside the two far screens reads the `enabled` FIELD directly,
        // so gating the reader gates the feature.
        if (!FEATURE_ENABLED && !MesheliumPlatform.isDevelopment()) {
            return false;
        }
        boolean on = propertyOr("enabled", get().enabled);
        // Warn only when the feature is ACTUALLY ON while unreleased - not
        // merely when the gate was consulted. The first version of this said
        // "is being armed anyway" on every call in a development
        // environment, including the overwhelming majority where the answer
        // was false, which is a lie in a log and would have trained everyone
        // to ignore the line that matters.
        if (on && !FEATURE_ENABLED && !gateReported) {
            gateReported = true;
            com.deds.meshelium.MesheliumLog.LOGGER.warn(
                    "Far Terrain is UNRELEASED and has been armed anyway - this looks like a "
                            + "development environment. It ships disabled and its settings page "
                            + "is not built. If you are a player and did not expect this, you "
                            + "have -Dfabric.development set somewhere, and any bug you hit "
                            + "beyond here is in an unfinished feature.");
        }
        return on;
    }

    /**
     * {@code meshelium.farfield.l1RadiusChunks} ?? layer 1's radius,
     * clamped, and 0 whenever layer 1 is switched off.
     *
     * <p>UNCHANGED CONTRACT. This is the accessor
     * {@code FarFieldResidency}, {@code FarFieldFogMixin} and the harness
     * have always called, and it still answers the same question with the
     * same property name and the same clamp. What schema v2 added is one
     * more way to reach zero, namely the layer-1 on/off row, and zero was
     * already a reachable and handled value (the slider's own Off stop):
     * the walker drains its far residents and the fog hands the horizon
     * back, both by existing paths.</p>
     */
    public static int l1RadiusChunks() {
        return layerRadiusChunks(Layer.L1);
    }

    /** {@code meshelium.farfield.<layer>Enabled} ?? the layer's flag. */
    public static boolean layerEnabled(Layer layer) {
        return propertyOr(layer.key + "Enabled", get().layer(layer).enabled);
    }

    /**
     * How far this layer reaches, chunks, clamped to
     * {@value #MIN_L1_RADIUS_CHUNKS}..{@value #MAX_L1_RADIUS_CHUNKS}, and
     * 0 when the layer is off.
     *
     * <p>The off-gate is applied AFTER the property override so a harness
     * can force a radius without also having to know about the layer
     * switch, and can force the switch separately when it wants to.</p>
     */
    public static int layerRadiusChunks(Layer layer) {
        Integer override = Integer.getInteger(
                PROPERTY_PREFIX + layer.key + "RadiusChunks");
        int chunks = override != null ? override : get().layer(layer).radiusChunks;
        if (!layerEnabled(layer)) {
            return MIN_L1_RADIUS_CHUNKS;
        }
        return Math.max(MIN_L1_RADIUS_CHUNKS,
                Math.min(MAX_L1_RADIUS_CHUNKS, chunks));
    }

    /**
     * {@link #SCALE_FULL}, {@link #SCALE_HALF} or {@link #SCALE_QUARTER}.
     * NOT WIRED: no mesher path reads this yet, and every layer ships
     * full.
     */
    public static int layerScale(Layer layer) {
        Integer override = Integer.getInteger(PROPERTY_PREFIX + layer.key + "Scale");
        return normalizeScale(override != null ? override : get().layer(layer).scale);
    }

    /**
     * {@link #TEXTURE_FULL}..{@link #TEXTURE_FLAT}. NOT WIRED: the mip
     * clamp arrives with the half-scale wave.
     */
    public static int layerTextureSimplify(Layer layer) {
        Integer override = Integer.getInteger(
                PROPERTY_PREFIX + layer.key + "TextureSimplify");
        int value = override != null
                ? override : get().layer(layer).textureSimplify;
        return Math.max(TEXTURE_FULL, Math.min(TEXTURE_FLAT, value));
    }

    /**
     * How biome colour is sampled on this layer: {@link #BLEND_PER_CHUNK}
     * (one colour per palette entry per chunk) or {@link #BLEND_COARSE}
     * (vanilla's per-block cell field). {@link #BLEND_FINE} normalizes
     * onto the latter. Read by {@code ShellExtractor} when it writes a
     * record, so it is the SAVE-time setting: changing it re-colours
     * terrain saved from now on, and terrain already saved keeps the
     * field it was saved with (the codec carries the side per record).
     */
    public static int layerColourBlend(Layer layer) {
        Integer override = Integer.getInteger(
                PROPERTY_PREFIX + layer.key + "ColourBlend");
        return normalizeBlend(override != null
                ? override : get().layer(layer).colourBlend);
    }

    /**
     * {@link #SMOOTH_OFF}, {@link #SMOOTH_EDGES} or {@link #SMOOTH_FULL}:
     * how distant terrain is shaded where it meets itself.
     *
     * <p>Read ONLY by {@code ShellMesher}. Like
     * {@link #layerContactShade}, it derives everything from the record's
     * own cells, stores nothing and asks the world for nothing, so it is
     * one of the two rows that also fix terrain already on disk.</p>
     */
    public static int layerSmoothLight(Layer layer) {
        Integer override = Integer.getInteger(
                PROPERTY_PREFIX + layer.key + "SmoothLight");
        int value = override != null ? override : get().layer(layer).smoothLight;
        return Math.max(SMOOTH_OFF, Math.min(SMOOTH_FULL, value));
    }

    /**
     * {@link #MIN_CONTACT_SHADE}..{@link #MAX_CONTACT_SHADE}: how strong
     * the shading selected by {@link #layerSmoothLight} is, as a
     * percentage.
     *
     * <p>Read ONLY by {@code ShellMesher}, which is what makes this the
     * one lighting-shaped row that also fixes terrain already on disk: it
     * derives the gradient from the record's own cells, stores nothing and
     * asks the world for nothing. 0 is off and costs one float compare per
     * merge bucket.</p>
     */
    public static int layerContactShade(Layer layer) {
        Integer override = Integer.getInteger(
                PROPERTY_PREFIX + layer.key + "ContactShade");
        int value = override != null ? override : get().layer(layer).contactShade;
        return Math.max(MIN_CONTACT_SHADE, Math.min(MAX_CONTACT_SHADE, value));
    }

    /**
     * {@link #LIGHT_FLAT}, {@link #LIGHT_GLOW} or {@link #LIGHT_REAL}.
     *
     * <p>Read in TWO places and they answer different questions.
     * {@code ShellExtractor} asks whether it is {@link #LIGHT_REAL} and,
     * if so, samples a light byte per stored cell; {@code ShellMesher}
     * asks which of the three to draw with, and falls back from
     * {@link #LIGHT_REAL} to {@link #LIGHT_GLOW} for any record that has
     * no light plane in it - which is every record written before this
     * setting was turned on.</p>
     */
    public static int layerLighting(Layer layer) {
        Integer override = Integer.getInteger(
                PROPERTY_PREFIX + layer.key + "Lighting");
        int value = override != null ? override : get().layer(layer).lighting;
        return Math.max(LIGHT_FLAT, Math.min(LIGHT_REAL, value));
    }

    /**
     * {@link #CACHE_GENTLE}, {@link #CACHE_BALANCED} or
     * {@link #CACHE_FAST} - the Background Saving row.
     *
     * <p>Read once per extraction SLICE by
     * {@code ExtractDispatch.sliceBudgetFor}, the same place
     * {@link #extractBudgetMillis()} is read, so both the row and
     * {@code -Dmeshelium.farfield.l1CacheSpeed} apply live without
     * putting a config lookup on the chunk-arrival path.</p>
     *
     * <p>Layer 1 by construction, like every other wired row: there is
     * ONE extractor writing ONE record that every layer reads, so a
     * per-layer saving rate would be three switches for one pipe and two
     * of them would be lying. {@code ExtractDispatch} asks
     * {@code Layer.L1} by name.</p>
     */
    public static int layerCacheSpeed(Layer layer) {
        Integer override = Integer.getInteger(
                PROPERTY_PREFIX + layer.key + "CacheSpeed");
        int value = override != null ? override : get().layer(layer).cacheSpeed;
        return Math.max(CACHE_GENTLE, Math.min(CACHE_FAST, value));
    }

    /**
     * A boolean feature on a layer. {@link #isWired} is what tells a
     * caller whether the answer means anything yet; the value is always
     * answered honestly from the config so a setting made today survives
     * to the wave that connects it.
     *
     * @throws IllegalArgumentException for the non-boolean controls, which
     *     have their own typed resolvers
     */
    public static boolean layerFlag(Layer layer, Control control) {
        LayerSettings settings = get().layer(layer);
        return switch (control) {
            case LAYER_ENABLED -> layerEnabled(layer);
            case SOLID_LEAVES ->
                    propertyOr(layer.key + "SolidLeaves", settings.solidLeaves);
            case FLAT_WATER ->
                    propertyOr(layer.key + "FlatWater", settings.flatWater);
            case COARSE_SHADING ->
                    propertyOr(layer.key + "CoarseShading", settings.coarseShading);
            case HEIGHTFIELD ->
                    propertyOr(layer.key + "Heightfield", settings.heightfield);
            case UNDERWATER_PLANTS ->
                    propertyOr(layer.key + "UnderwaterPlants", settings.underwaterPlants);
            case SMALL_DETAIL ->
                    propertyOr(layer.key + "SmallDetail", settings.smallDetail);
            case WATER_LOOK ->
                    // The property key stays l1WaterDepth: it is documented in
                    // docs/FARFIELD-WAVES.md and a -D flag a player already
                    // has must keep working across the rename.
                    propertyOr(layer.key + "WaterDepth", settings.waterDepth);
            case BLOCK_VARIANTS ->
                    propertyOr(layer.key + "BlockVariants", settings.blockVariants);
            case DISTANCE, SCALE, TEXTURE_SIMPLIFY, COLOUR_BLEND, LIGHTING,
                 SMOOTH_LIGHT, CONTACT_SHADE, CACHE_SPEED ->
                    throw new IllegalArgumentException(
                            control + " is not a boolean control; use "
                                    + "layerRadiusChunks/layerScale/"
                                    + "layerTextureSimplify/layerColourBlend/"
                                    + "layerLighting/layerSmoothLight/"
                                    + "layerContactShade/layerCacheSpeed");
        };
    }

    /** Write a boolean feature back. Same rejection as {@link #layerFlag}. */
    public void setLayerFlag(Layer layer, Control control, boolean value) {
        LayerSettings settings = layer(layer);
        switch (control) {
            case LAYER_ENABLED -> settings.enabled = value;
            case SOLID_LEAVES -> settings.solidLeaves = value;
            case FLAT_WATER -> settings.flatWater = value;
            case COARSE_SHADING -> settings.coarseShading = value;
            case HEIGHTFIELD -> settings.heightfield = value;
            case UNDERWATER_PLANTS -> settings.underwaterPlants = value;
            case SMALL_DETAIL -> settings.smallDetail = value;
            case WATER_LOOK -> settings.waterDepth = value;
            case BLOCK_VARIANTS -> settings.blockVariants = value;
            case DISTANCE, SCALE, TEXTURE_SIMPLIFY, COLOUR_BLEND, LIGHTING,
                 SMOOTH_LIGHT, CONTACT_SHADE, CACHE_SPEED ->
                    throw new IllegalArgumentException(
                            control + " is not a boolean control");
        }
    }

    /** {@code meshelium.farfield.preset} ?? {@link #preset}. */
    public static Preset selectedPreset() {
        String override = System.getProperty(PROPERTY_PREFIX + "preset");
        return Preset.byId(override != null ? override : get().preset);
    }

    /** True when a {@code -D} flag is pinning any control on this page. */
    public static boolean anyLayerPropertyOverride(Layer layer) {
        String[] suffixes = {
            "Enabled", "RadiusChunks", "Scale", "SolidLeaves", "FlatWater",
            "TextureSimplify", "CoarseShading", "Heightfield",
            "UnderwaterPlants", "SmallDetail", "ColourBlend", "Lighting",
            "WaterDepth", "SmoothLight", "ContactShade", "BlockVariants",
            "CacheSpeed"
        };
        for (String suffix : suffixes) {
            if (System.getProperty(PROPERTY_PREFIX + layer.key + suffix) != null) {
                return true;
            }
        }
        return false;
    }

    /** True when a {@code -D} flag pins one specific control on a layer. */
    public static boolean isPropertyOverridden(Layer layer, Control control) {
        String suffix = switch (control) {
            case LAYER_ENABLED -> "Enabled";
            case DISTANCE -> "RadiusChunks";
            case SCALE -> "Scale";
            case SOLID_LEAVES -> "SolidLeaves";
            case FLAT_WATER -> "FlatWater";
            case TEXTURE_SIMPLIFY -> "TextureSimplify";
            case UNDERWATER_PLANTS -> "UnderwaterPlants";
            case SMALL_DETAIL -> "SmallDetail";
            case WATER_LOOK -> "WaterDepth";
            case COLOUR_BLEND -> "ColourBlend";
            case LIGHTING -> "Lighting";
            case COARSE_SHADING -> "CoarseShading";
            case HEIGHTFIELD -> "Heightfield";
            case SMOOTH_LIGHT -> "SmoothLight";
            case CONTACT_SHADE -> "ContactShade";
            case BLOCK_VARIANTS -> "BlockVariants";
            case CACHE_SPEED -> "CacheSpeed";
        };
        return System.getProperty(PROPERTY_PREFIX + layer.key + suffix) != null;
    }

    /** {@code meshelium.farfield.surfaceBand} ?? {@link #surfaceBand}. */
    public static boolean surfaceBandEnabled() {
        return propertyOr("surfaceBand", get().surfaceBand);
    }

    /**
     * {@code meshelium.farfield.bandOffset} ?? {@link #bandOffset},
     * clamped. Only consulted by the extractor (W2) while
     * {@link #surfaceBandEnabled()} holds; the store records whatever
     * offset each shell was actually cut with, so changing this never
     * invalidates existing records.
     */
    public static int bandOffset() {
        Integer override = Integer.getInteger(PROPERTY_PREFIX + "bandOffset");
        int offset = override != null ? override : get().bandOffset;
        return Math.max(MIN_BAND_OFFSET, Math.min(MAX_BAND_OFFSET, offset));
    }

    /**
     * {@code meshelium.farfield.extractBudgetMillis} ?? the config field,
     * clamped to
     * {@value #MIN_EXTRACT_BUDGET_MILLIS}..{@value #MAX_EXTRACT_BUDGET_MILLIS}.
     *
     * <p>Read once per extraction SLICE rather than per extraction (see
     * {@code ExtractDispatch.beginSlice}), so both the property and the
     * config field apply live without putting a config lookup on the
     * chunk-arrival path.</p>
     */
    public static int extractBudgetMillis() {
        Integer override = Integer.getInteger(PROPERTY_PREFIX + "extractBudgetMillis");
        int millis = override != null ? override : get().extractBudgetMillis;
        return Math.max(MIN_EXTRACT_BUDGET_MILLIS,
                Math.min(MAX_EXTRACT_BUDGET_MILLIS, millis));
    }

    /** {@link #extractBudgetMillis()} in nanoseconds, for the slice. */
    public static long extractBudgetNanos() {
        return extractBudgetMillis() * 1_000_000L;
    }

    /**
     * {@code meshelium.farfield.adaptiveExtraction} ??
     * {@link #adaptiveExtraction}. FALSE restores the pre9 rule exactly
     * (a flat {@link #extractBudgetMillis()} per slice and nothing else),
     * which is the A/B lever for the playtest.
     */
    public static boolean adaptiveExtraction() {
        return propertyOr("adaptiveExtraction", get().adaptiveExtraction);
    }

    /**
     * {@code meshelium.farfield.extractFrameRateFloor} ?? the config
     * field, clamped to
     * {@value #MIN_EXTRACT_FRAME_RATE_FLOOR}..{@value #MAX_EXTRACT_FRAME_RATE_FLOOR}.
     * See {@link #DEFAULT_EXTRACT_FRAME_RATE_FLOOR}.
     */
    public static int extractFrameRateFloor() {
        Integer override = Integer.getInteger(
                PROPERTY_PREFIX + "extractFrameRateFloor");
        int fps = override != null ? override : get().extractFrameRateFloor;
        return Math.max(MIN_EXTRACT_FRAME_RATE_FLOOR,
                Math.min(MAX_EXTRACT_FRAME_RATE_FLOOR, fps));
    }

    /**
     * {@link #extractFrameRateFloor()} as a frame time in nanoseconds:
     * the frame length the adaptive budget treats as "no headroom left".
     */
    public static long extractTargetFrameNanos() {
        return 1_000_000_000L / extractFrameRateFloor();
    }

    // ------------------------------------------------------------------
    // Appearance signatures (playtest item J4: "when i change settings
    // they dont apply immedietly, they should probably reload when you
    // change settings")
    // ------------------------------------------------------------------

    /**
     * A signature of every layer-1 row that changes how a stored shell is
     * MESHED, so a watcher can notice a change with one int compare.
     *
     * <p>These are the rows {@code ShellMesher.Style.current} reads, and
     * only those: {@link Control#SMALL_DETAIL},
     * {@link Control#SOLID_LEAVES}, {@link Control#LIGHTING} and
     * {@link Control#WATER_LOOK}. Every one of them can be applied to
     * terrain that is ALREADY on disk, because the record is unchanged and
     * only the mesh pass over it differs - which is what lets
     * {@code FarFieldResidency} re-mesh the resident ring in place instead
     * of telling the player to travel.</p>
     *
     * <p><b>Deliberately not included:</b> {@link Control#COLOUR_BLEND}
     * and {@link Control#UNDERWATER_PLANTS}, which decide what the
     * EXTRACTOR writes. Re-meshing a record for those would produce the
     * identical mesh, because the record already carries the colour grid
     * it was saved with and simply does not contain a plant that was never
     * saved. They belong to {@link #farSaveSignature()} instead, and their
     * tooltips say so. {@link Control#LIGHTING} is in BOTH: its third step
     * needs a stored light plane, but all three steps change the mesh, and
     * the mesher already falls back from Real Light to Glowing Blocks for
     * a record that has no plane.</p>
     *
     * <p>{@link Control#SMOOTH_LIGHT} and {@link Control#CONTACT_SHADE}
     * are MESH rows and nothing else: both are derived from the record's
     * own cells, so they need no stored byte and they improve every record
     * already on disk the moment the ring re-meshes. They are in this
     * signature and not in {@link #farSaveSignature()}.</p>
     *
     * <p><b>{@link Control#CACHE_SPEED} is wired and is in NEITHER
     * signature</b>, which is the only row on the page that can say that.
     * It decides how fast records are written, not what is in them and
     * not how they are drawn, so hashing it here would spend a whole ring
     * reload on a rate change and hashing it into
     * {@link #farSaveSignature()} would drop the extract-once tracker and
     * re-save the entire held window on one. Both are the L9 regression
     * this javadoc warns about, arrived at from the generous side.</p>
     *
     * <p>Also not included: the rows {@link #isWired} still answers false
     * for. When one of those is connected, it belongs in whichever of the
     * two signatures matches where it is read - and if it is read in the
     * mesher, forgetting to add it here is a J4 regression rather than a
     * crash, so the wiring switch and these two methods are meant to be
     * edited in the same change.</p>
     */
    public static int farMeshSignature() {
        int signature = layerLighting(Layer.L1);
        // SMALL_DETAIL is deliberately absent: it went unwired in pre13
        // (see isWired for why vanilla owns both of its steps), and an
        // unwired row that still hashed here would spend a full ring
        // reload to change nothing - the L9 regression this javadoc warns
        // about, in the disconnect direction rather than the connect one.
        signature = signature * 31
                + (layerFlag(Layer.L1, Control.SOLID_LEAVES) ? 1 : 0);
        // WATER_LOOK is hashed UNCONDITIONALLY again (pre13 N4). pre12
        // masked it behind isSuperseded so that a row Real Light had
        // locked could not spend a ring reload; the lock is gone because
        // it was wrong (isSuperseded says why), and with it gone the mask
        // would be the L9 regression in the other direction - a live row
        // that changes how every un-planed record draws its water and
        // never redraws one. Flipping it now reloads the ring, which for
        // a fully-planed world is a pixel-identical reload; the row's
        // tooltip says so rather than leaving the player to wonder.
        signature = signature * 31
                + (layerFlag(Layer.L1, Control.WATER_LOOK) ? 1 : 0);
        signature = signature * 31 + layerSmoothLight(Layer.L1);
        signature = signature * 31 + layerContactShade(Layer.L1);
        // BLOCK_VARIANTS (R4): mesh-time and nothing else - the roll is
        // derived from the cell's world position, so flipping it repaints
        // every record already on disk and belongs in no save signature.
        signature = signature * 31
                + (layerFlag(Layer.L1, Control.BLOCK_VARIANTS) ? 1 : 0);
        return signature;
    }

    /**
     * A signature of every setting that changes what the EXTRACTOR writes
     * into a shell record, so a watcher can notice a change with one int
     * compare.
     *
     * <p>{@link Control#UNDERWATER_PLANTS} and {@link Control#COLOUR_BLEND}
     * are read by {@code ShellExtractor} when it builds a record, and
     * {@link Control#LIGHTING} is too at its Real Light step (the light
     * plane). The surface band and its offset are here for the same
     * reason: they decide which cells reach the record at all, and the
     * codec stores the offset each record was cut with.</p>
     *
     * <p>A change to any of these cannot be applied to terrain already on
     * disk - the data is simply not in the record - so the honest response
     * is to re-save what the client is still HOLDING and to say plainly in
     * the tooltip that the rest keeps what it was saved with.</p>
     */
    /**
     * Bumped whenever the EXTRACTOR's band rules change in a way that
     * makes an existing record wrong rather than merely older, so that
     * {@code FarFieldResidency} calls
     * {@code ExtractDispatch.forgetExtractedColumns()} and every column
     * the client is still holding is re-saved on the extraction budget.
     *
     * <p>This is the one thing that separates "the fix works" from "the
     * fix works on terrain you have not visited yet". Neither
     * {@link #surfaceBandEnabled()} nor {@link #bandOffset()} moves when
     * the RULE changes underneath them — the owner's settings are
     * identical before and after — so without a revision counter the
     * signature is stable and nothing is re-extracted. He has reported
     * caching gaps in four consecutive playtests; shipping a band fix
     * that reaches only new terrain would be the fifth.</p>
     *
     * <p><b>2 (pre15, P5):</b> two band rules that cut terrain away and
     * left the bottoms of things see-through — {@code closeLeaks} took
     * its flood budget from the per-column floor, so the limit rose with
     * whatever stood over an opening and refused a cave's own floor and
     * lower walls; and {@code computeBandFloors}' absent-neighbour
     * estimate used the edge ROW minimum, which is true of natural
     * terrain and maximally false of a building, cutting a mansion's
     * wall upward from its base. Both now measure from the chunk
     * minimum.</p>
     *
     * <p><b>4 (pre19, S1):</b> the tint MODEL changed, not its tuning.
     * A record now stores a 16x16 CELL FIELD - vanilla's own
     * {@code calculateBlockTint} for every block column, read with no
     * interpolation - in place of the N x N corner grid the mesher used
     * to reconstruct a field from; and the PIN path, which writes every
     * column the client drops at the LOD seam, now reproduces the
     * {@code biomeBlendRadius} box average and the {@code BiomeManager}
     * zoom instead of returning a raw quart-snapped biome colour. Records
     * written before the bump carry both errors - a hard, 4-block
     * quantised colour step where vanilla ramps over {@code 2r+1} blocks,
     * and two chunk-shaped populations that disagree at their shared
     * line - and only re-extraction removes them. This is the owner's S1
     * ("just chunks of color that sometimes have a gradient").</p>
     *
     * <p><b>3 (pre18, R2 + R8):</b> two extractor rules whose output an
     * existing record cannot be reinterpreted into. R2: the pin strip's
     * captured span now tops out at the neighbour's WORLD_SURFACE instead
     * of the fluid-re-cut top, so a pinned ocean seam no longer stores the
     * full-height translucent water curtain ("walls inbetween the chunks
     * that stay") - records written before the bump carry the wall in
     * their face masks and only a re-extraction removes it. R8: a biome
     * boundary through a record now stores the tint at
     * {@code ShellCodec.TINT_SIDE_EXACT} (per block, vanilla's own
     * {@code calculateBlockTint} values); records written before it keep
     * the 16-block COARSE smear at swamp and river edges until
     * re-saved.</p>
     *
     * <p><b>Revision 5 (pre20), THE GREEN WATER.</b> An extracted
     * palette entry's whole tint field used to be sampled at ONE y - the
     * y of the LOWEST cell that minted the entry - while vanilla's biome
     * lookup is 3D. {@code minecraft:water[level=0]} is one state for a
     * whole chunk, so its entry was stamped at the deepest EXPOSED water
     * cell (a flooded cave, an aquifer, a ravine; the band admits to
     * about seabed-72), and vanilla 26.2 has exactly one cave biome
     * declaring a {@code waterColor}: {@code sulfur_caves}, 0x34BF89, a
     * vivid spring green. Whole chunks of ocean surface therefore wore
     * cave water while the chunk next door, with no exposed deep cell,
     * was correct. {@code ShellExtractor} now derives a per-entry,
     * per-COLUMN sampling height from the finished cells after the
     * top-clamp, on the Per Chunk row as well as the cell field.
     * Extraction-time, so a record written before it keeps the green
     * until it is re-extracted.</p>
     *
     * <p><b>And revision 5 is the first one that can actually reach a
     * record on disk.</b> Revisions 1 to 4 moved
     * {@link #farSaveSignature()}, which was compared only against an
     * in-memory copy reset on every world era: it re-extracted columns
     * the client still HELD within a session and did nothing whatever
     * across a restart. Since pre20 the signature is written into the
     * record ({@code ShellCodec.SIGNATURE_MIN_VERSION}) and checked at
     * decode, so a mismatched record reads as absent and the ordinary
     * machinery replaces it. Every record any earlier build wrote carries
     * no signature at all and is therefore stale by definition - the
     * whole existing cache is re-extracted, once.</p>
     */
    private static final int BAND_RULE_REVISION = 5;

    /**
     * Q2's one-time heal lever (pre16 Phase 1, docs/FARFIELD-WAVES.md "Q2
     * ANSWERED"): {@code -Dmeshelium.farfield.healBlackRecords=true} bumps
     * the save signature by one, which makes {@code FarFieldResidency}
     * call {@code ExtractDispatch.forgetExtractedColumns()} exactly as a
     * band-rule bump would - every column the client still holds is
     * re-extracted under Phase 0's light gates and, since Phase 5, the
     * UNKNOWN-never-stores-a-byte source contract, repainting standing
     * black records that no staleness path can reach: staleness is
     * version arithmetic over CLIENT truth (M3), and a black shell whose
     * column was never edited is stored==live and permanently clean by
     * that arithmetic. Only a signature bump re-dirties it.
     *
     * <p>DEFAULT OFF and property-only ON PURPOSE - no settings row. The
     * price is re-extracting every stored column the player flies past
     * (the rd-120 storm, again), so the OWNER flips it, once, only if
     * standing black persists on re-flown ground after pre16's extractor
     * fixes; flipping it back off afterwards leaves the signature where
     * it started, which is fine - the records rewritten meanwhile carry
     * the same content either way. A SEPARATE constant rather than a
     * {@link #BAND_RULE_REVISION} bump so the two levers compose: a
     * future band-rule revision changes the signature with the lever on
     * or off, and the lever heals without pretending the band rules
     * moved. Read once at class load - it is a launch flag, matching
     * every other {@code meshelium.farfield.*} property's lifecycle.</p>
     */
    private static final int HEAL_BLACK_RECORDS_REVISION =
            Boolean.getBoolean(PROPERTY_PREFIX + "healBlackRecords") ? 1 : 0;

    /**
     * Vanilla's Biome Blend radius, {@code Options.biomeBlendRadius()} -
     * 0 to 7, default 2 (a 5x5 box). It is OUR setting too since pre19's
     * S1: {@code ShellExtractor} stores
     * {@code ClientLevel.calculateBlockTint}'s answer, which folds this
     * radius in, and {@code Pin.PinTintView} reproduces the same average
     * off the game thread. Hence its presence in
     * {@link #farSaveSignature()}.
     *
     * <p>Answers 2 rather than throwing when there is no client (the
     * cold-JVM test path): a stable constant keeps the signature stable,
     * which is the only property callers rely on.</p>
     */
    public static int biomeBlendRadius() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.options != null) {
                return Math.max(0, Math.min(7,
                        client.options.biomeBlendRadius().get()));
            }
        } catch (Throwable ignored) {
            // No client, or an option instance that is not there yet.
        }
        return 2;
    }

    public static int farSaveSignature() {
        int signature = layerColourBlend(Layer.L1);
        signature = signature * 31
                + (layerFlag(Layer.L1, Control.UNDERWATER_PLANTS) ? 1 : 0);
        signature = signature * 31
                + (layerLighting(Layer.L1) == LIGHT_REAL ? 1 : 0);
        signature = signature * 31 + (surfaceBandEnabled() ? 1 : 0);
        signature = signature * 31 + bandOffset();
        signature = signature * 31 + BAND_RULE_REVISION;
        // S1 (pre19): the stored tint field IS vanilla's box average at
        // the player's Biome Blend, so that VANILLA option is a save-time
        // input of ours. Without it here, moving Biome Blend re-colours
        // the near field and leaves every stored record at the old ramp
        // width - a two-population world with no path back, the same
        // shape of bug pre12's locked WATER_LOOK row was.
        signature = signature * 31 + biomeBlendRadius();
        signature = signature + HEAL_BLACK_RECORDS_REVISION; // Q2 heal lever: +1, composes
        return signature;
    }

    private static boolean propertyOr(String suffix, boolean configValue) {
        String property = System.getProperty(PROPERTY_PREFIX + suffix);
        return property != null ? Boolean.parseBoolean(property) : configValue;
    }

    private static Path path() {
        return MesheliumPlatform.configDir().resolve("meshelium-farfield.json");
    }

    private static FarFieldConfig load() {
        Path path = path();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    FarFieldConfig loaded = GSON.fromJson(reader, FarFieldConfig.class);
                    if (loaded != null) {
                        loaded.normalize();
                        if (loaded.migrate()) {
                            MesheliumLog.LOGGER.info(
                                    "Meshelium migrated {} to far-field settings schema v{}",
                                    path, CURRENT_CONFIG_VERSION);
                            loaded.save();
                        }
                        return loaded;
                    }
                }
            }
        } catch (IOException | JsonParseException e) {
            MesheliumLog.LOGGER.warn("Could not read {}; starting from defaults", path, e);
        }
        // Fresh defaults are NOT saved: no file appears until the settings
        // screen changes something (the zero-cost-off posture).
        FarFieldConfig fresh = new FarFieldConfig();
        fresh.normalize();
        fresh.migrate();
        return fresh;
    }

    /**
     * Persist. Refreshes the legacy {@link #l1RadiusChunks} mirror first,
     * so a file this method wrote is internally consistent no matter which
     * of the two places a later reader looks in.
     */
    public void save() {
        this.l1RadiusChunks = this.layer1 != null
                ? this.layer1.radiusChunks : DEFAULT_L1_RADIUS_CHUNKS;
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            MesheliumLog.LOGGER.warn("Could not write {}; settings will not persist", path, e);
        }
    }
}
