/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.farfield.extract;

import com.deds.meshelium.farfield.FarField;
import com.deds.meshelium.farfield.FarFieldConfig;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * M5: everything a column's extraction NEEDS, captured on the game
 * thread while it is still alive - the save design of record's
 * replacement for retaining whole {@code LevelChunk}s on a heap ladder
 * (FARFIELD-SAVE-DESIGN.md section 5).
 *
 * <h2>T2 Phase 3: ONE pin, TWO sources</h2>
 * A pin's home column now comes from one of two places, and everything
 * downstream of the capture - the side graph, the strips, the light
 * contract, the tint facade, the worker, the result drain, the write
 * ack - is the same code for both:
 * <ul>
 * <li><b>DROPPED</b> ({@link #live} false, the M5 population): the
 *     client's own {@code LevelChunk}, immutable by virtue of having
 *     been dropped;</li>
 * <li><b>LIVE</b> ({@link #live} true, new at Phase 3): a
 *     {@link ColumnSnapshot} of a chunk the client still holds -
 *     immutable because it is OUR copy. This is what takes the ~900 us
 *     live walk off the game thread and leaves a ~40 us capture in its
 *     place (docs/unreleased/farfield/FARFIELD-PERF-BRIEF.md section 2).</li>
 * </ul>
 * The two differ in exactly three places, all of them named at their
 * site: a live pin's record stays {@code LIVE_DIRTY} rather than going
 * {@code PINNED}; a live pin does not defer its strips onto the leaving
 * reserve (it is funded by the fill line that admitted it); and a live
 * pin stranded by a world change is terrain the next visit re-receives,
 * so it is never counted as LOST.
 *
 * <p>A pin holds:</p>
 *
 * <ul>
 * <li><b>the chunk</b>: the strong ref. Immutable from the capture
 *     moment - for a DROPPED column because
 *     {@code ClientChunkCache$Storage.drop} CASes the slot to null
 *     before {@code ClientLevel.unload} runs (bytecode ip 0-7 vs
 *     28-36) and no client {@code setBlockState} can reach an uncached
 *     chunk ({@code Level.setBlock} routes through {@code getChunkAt}),
 *     so nothing writes it after the seam; for a LIVE column because
 *     the ref is a snapshot nobody else has;</li>
 * <li><b>the light</b>: {@code DataLayer} REFS for the column's own
 *     sections, both layers, via
 *     {@code LevelLightEngine.getLayerListener(layer)
 *     .getDataLayerData(sectionPos)} (javap this session:
 *     {@code LevelLightEngine: public LayerLightEventListener
 *     getLayerListener(LightLayer); public int getMinLightSection();
 *     public int getMaxLightSection();} /
 *     {@code LayerLightEventListener: public abstract DataLayer
 *     getDataLayerData(SectionPos);} / {@code DataLayer: public int
 *     get(int, int, int);}). Refs, not copies: the queued forget-packet
 *     removal only unlinks the arrays from the engine's maps, so our
 *     refs keep the live bytes. MUST be grabbed at the seam (E5 unload
 *     HEAD, E6 {@code updateViewRadius} HEAD), never deferred to the
 *     triage: {@code ClientLevel.pollLightUpdates} drains its whole
 *     queue in one frame once it holds 1000+ runnables (bytecode ip
 *     10-30: budget = size when size >= 1000), so a forget storm
 *     retires the annulus's light within about a frame of the flood
 *     landing;</li>
 * <li><b>the geometry sides</b>: per lateral side, PINNED (the
 *     neighbour left in the same storm - resolved through the pin
 *     graph at finish time into a direct ref, so the walk reads its
 *     captured chunk), CAPTURED (the neighbour STAYED live - its
 *     border strip and its light layer refs are grabbed at finish
 *     time, while it is still in the cache), or UNKNOWN (the quality
 *     reason bit; the old absent-neighbour policy).</li>
 * </ul>
 *
 * <h2>Thread confinement, stated per field</h2>
 * Every field except the three below is written on the GAME THREAD,
 * before the pin is handed to {@link PinWorker}'s queue, and never
 * written again - the queue transfer is the happens-before edge (the
 * design's own rule), so the worker reads a frozen object. The three
 * exceptions: {@link #released} is a volatile the game thread sets and
 * the worker polls (E9 backstop, master-off abandon);
 * {@link #walkLightPending} and {@link #walkQuality} are written by
 * whichever thread runs the walk and read by the game thread only
 * AFTER the result travels back through {@link PinWorker}'s result
 * queue or the write-ack queue - again a queue edge. The captured
 * {@code DataLayer} bytes may still be MUTATED under a ref that
 * belongs to a stayed-live neighbour; byte-array reads are tear-safe
 * and any later value is fresher truth (the design's own argument).
 * Nothing in this class ever touches the residency lock, the record
 * map, or the live chunk cache after capture.
 */
final class Pin implements ShellExtractor.NeighborView, ShellExtractor.LightView {

    /** Packed column key ({@code ExtractDispatch.columnKey}). */
    final long key;
    /** Chunk coordinates, denormalized for the worker's submit. */
    final int chunkX;
    final int chunkZ;
    /**
     * The home column's frozen data: for a DROPPED capture the client's
     * own chunk (the only copy of that terrain left in the game), for a
     * LIVE capture our {@link ColumnSnapshot} of one it still holds.
     */
    final LevelChunk chunk;
    /**
     * True when {@link #chunk} is a {@link ColumnSnapshot} of a column
     * the client STILL HOLDS - the Phase 3 source. Read at three
     * decision points and nowhere else: the record transition in
     * {@code ExtractDispatch.finishPin}, the strip-deferral arm in the
     * same method, and the world-change loss accounting in
     * {@link PinWorker}. The walk itself never asks.
     */
    final boolean live;
    /**
     * The store generation this pin's shell belongs to
     * ({@code FarField.storeGeneration()} at capture). The worker walks
     * a pin only while this is still the OPEN store's generation: after
     * a world exit's drain deadline the store closes, the generation
     * moves, and a stale pin is counted on {@code farSaveLost} instead
     * of being filed under the next world's key.
     */
    final long storeGeneration;
    /** Dimension fact, snapshot (the worker must not touch the level). */
    final boolean hasSkyLight;
    /** {@code LevelLightEngine.getMinLightSection()} at capture. */
    final int minLightSection;
    /** The level's cardinal-lighting record, for the tint facade. */
    final CardinalLighting cardinal;
    /**
     * Own-column layer refs, index = sectionY - {@link #minLightSection};
     * null array = light not captured (never published, or the capture
     * valve skipped it). Individual null entries are the engine's own
     * gaps and carry the walk-up semantics below.
     */
    final DataLayer[] ownSky;
    final DataLayer[] ownBlock;
    /**
     * Own light was PUBLISHED this session (the record's
     * {@code lightReady}, or false for an untracked fast-flight drop)
     * AND the layer grab found live arrays. False = the extractor
     * stores geometry with no light plane, quality LIGHT_PARTIAL -
     * flat over fabricated, both directions.
     */
    private final boolean ownLightCaptured;

    // Extraction-time settings, snapshot at capture so a settings flip
    // mid-drain cannot tear a walk (the walk reads these, never the
    // config).
    final boolean surfaceBand;
    final int bandOffset;
    final int blendDivisor;
    final boolean keepUnderwaterPlants;
    final boolean realLight;
    /** Vanilla's Biome Blend radius at capture; see {@link PinTintView}. */
    final int biomeBlendRadius;
    /**
     * The level's own {@code BiomeManager}, captured for its
     * {@code biomeZoomSeed}. {@link PinTintView} re-points it at the pin
     * graph with {@code withDifferentSource}, which is how the pin gets
     * vanilla's exact quart-to-block zoom without reading the level.
     */
    final BiomeManager levelBiomes;

    // ------------------------------------------------------------------
    // Finish-time fields (game thread, at triage, before the worker
    // handoff). Direct refs on purpose: the pin GRAPH is resolved once,
    // here, so the worker never needs a shared registry - phase 1
    // registered every storm sibling before any triage runs, which is
    // what makes an interior pin's four sides resolve even though its
    // neighbours' own triage may not have run yet.
    // ------------------------------------------------------------------

    /** Per side: the neighbour's pin, when it left in the same storm. */
    final Pin[] sidePin = new Pin[4];
    /** Per corner (NW/NE/SW/SE): flood conduits through the pin graph. */
    final Pin[] diagPin = new Pin[4];
    /** Per side: the border strip of a STAYED-live neighbour, or null. */
    final ShellExtractor.CapturedStrip[] strips = new ShellExtractor.CapturedStrip[4];
    /** Per side: a stayed-live neighbour's captured layer refs. */
    final DataLayer[][] sideSky = new DataLayer[4][];
    final DataLayer[][] sideBlock = new DataLayer[4][];
    /** Per side: that captured neighbour's light was published. */
    final boolean[] sideLightCaptured = new boolean[4];
    /**
     * Phase 3: per side and per corner, a stayed-live neighbour's BIOME
     * containers, captured as frozen refs
     * ({@link ColumnSnapshot#biomeRefs}). Purely a TINT source - the
     * geometry of such a side is the strip (laterals) or nothing
     * (corners), and these arrays never change that.
     *
     * <p>Before Phase 3 a pin whose neighbours stayed live answered
     * every out-of-graph biome probe by edge-repeat clamping into its
     * own chunk, which put an r-block band of the home biome along each
     * such plane at the player's Biome Blend radius. That was tolerable
     * when the population was leaving columns at the LOD seam; it is not
     * tolerable now that it would be EVERY column the far field stores,
     * because the near field it butts against has the exact answer. Nine
     * pointer-read grabs a column buy the exact answer here too.</p>
     */
    final ColumnSnapshot.TintBiomes[] sideTint = new ColumnSnapshot.TintBiomes[4];
    /** See {@link #sideTint}. Corner order matches {@link #diagPin}. */
    final ColumnSnapshot.TintBiomes[] diagTint = new ColumnSnapshot.TintBiomes[4];

    /**
     * The version this pin's write will carry - the record's
     * {@code liveVersion} at finish. Written on the game thread before
     * the worker handoff.
     */
    long version;

    /**
     * Phase 3: how many lateral sides the finish has already resolved,
     * 0..4 - the RESUME CURSOR that makes one border strip, rather than
     * one whole capture, the game thread's largest uninterruptible unit.
     *
     * <p>It replaces the undo loop the finish used to run when it ran
     * out of budget mid-strip. Undoing was safe but wasteful (up to
     * three strips of work thrown away per deferral) and it was only
     * ever there so a retry started from a clean slate; resuming is
     * equivalent, because the pin graph a retry would re-read is
     * registered in full before any finish runs, and it is strictly
     * cheaper. A side already resolved keeps whatever it resolved to; a
     * side whose neighbour has since left the cache resolves to UNKNOWN
     * on the retry, exactly as it would have on a first attempt.</p>
     *
     * <p>Game thread only, like every other finish-time field.</p>
     */
    int finishSide;

    /**
     * E9 backstop / supersede / master-off: the game thread renounces
     * the pin and the worker skips it at dequeue. The loss (if any) was
     * counted by whoever set this - the worker only obeys.
     */
    volatile boolean released;

    /** Walk output: the plane was refused or padded - LIGHT_PARTIAL. */
    boolean walkLightPending;
    /** Walk output: the shell's quality byte (reason bits + WHOLE). */
    byte walkQuality;
    /** Walk output: one of {@code PinWorker}'s OUTCOME_* bytes. */
    byte walkOutcome;

    private PinTintView tintView;

    /**
     * The extraction-time settings a pin freezes at capture. Snapshot
     * ONCE per capture walk (an E6 storm creates tens of thousands of
     * pins, and the config reads behind these five values go through
     * {@code System.getProperty}) and shared by every pin of the walk -
     * the walk is one game-thread moment, so one snapshot IS the
     * settings of that moment.
     */
    record Settings(boolean surfaceBand, int bandOffset, int blendDivisor,
            boolean keepUnderwaterPlants, boolean realLight,
            int biomeBlendRadius) {
        static Settings snapshot() {
            return new Settings(FarFieldConfig.surfaceBandEnabled(),
                    FarFieldConfig.bandOffset(),
                    FarFieldConfig.layerColourBlend(FarFieldConfig.Layer.L1),
                    FarFieldConfig.layerFlag(FarFieldConfig.Layer.L1,
                            FarFieldConfig.Control.UNDERWATER_PLANTS),
                    FarFieldConfig.layerLighting(FarFieldConfig.Layer.L1)
                            == FarFieldConfig.LIGHT_REAL,
                    // S1: a VANILLA option, and a save-time input of ours
                    // because the stored tint IS the box average it sizes.
                    // Snapshotted here for the same reason as the rest -
                    // Options must not be read off the game thread.
                    FarFieldConfig.biomeBlendRadius());
        }
    }

    private Pin(long key, LevelChunk chunk, boolean live, boolean lightPublished,
            boolean captureLight, Settings settings, long storeGeneration) {
        this.key = key;
        this.chunkX = chunk.getPos().x();
        this.chunkZ = chunk.getPos().z();
        this.chunk = chunk;
        this.live = live;
        this.storeGeneration = storeGeneration;
        this.hasSkyLight = chunk.getLevel().dimensionType().hasSkyLight();
        // ClientLevel implements BlockAndTintGetter and owns the value;
        // the DEFAULT record is the honest stand-in for anything else.
        this.cardinal = chunk.getLevel() instanceof BlockAndTintGetter tinted
                ? tinted.cardinalLighting() : CardinalLighting.DEFAULT;
        this.surfaceBand = settings.surfaceBand();
        this.bandOffset = settings.bandOffset();
        this.blendDivisor = settings.blendDivisor();
        this.keepUnderwaterPlants = settings.keepUnderwaterPlants();
        this.realLight = settings.realLight();
        this.biomeBlendRadius = settings.biomeBlendRadius();
        this.levelBiomes = chunk.getLevel().getBiomeManager();
        LevelLightEngine engine = chunk.getLevel().getLightEngine();
        this.minLightSection = engine.getMinLightSection();
        if (lightPublished && captureLight && realLight) {
            int sections = engine.getMaxLightSection() - minLightSection + 1;
            DataLayer[] sky = grabLayers(engine, LightLayer.SKY,
                    chunkX, chunkZ, minLightSection, sections);
            DataLayer[] block = grabLayers(engine, LightLayer.BLOCK,
                    chunkX, chunkZ, minLightSection, sections);
            // The retirement detector: a PUBLISHED column always holds
            // layers (enableChunkLight's 26-neighbour init created them),
            // so all-null means the forget flood's removal beat this
            // capture - the fake-noon state. Refuse it whole rather than
            // read virgin 15s: flat over fabricated.
            if (anyLayer(sky) || anyLayer(block)) {
                this.ownSky = sky;
                this.ownBlock = block;
                this.ownLightCaptured = true;
            } else {
                this.ownSky = null;
                this.ownBlock = null;
                this.ownLightCaptured = false;
            }
        } else {
            this.ownSky = null;
            this.ownBlock = null;
            this.ownLightCaptured = false;
        }
    }

    /**
     * Capture one leaving column at its seam (E5 unload HEAD, or the E6
     * walk at {@code updateViewRadius}/{@code updateViewCenter} HEAD) -
     * the one moment chunk AND light are both still alive. Game thread.
     *
     * @param lightPublished the record's {@code lightReady}, or false
     *                       for an untracked drop (the fast-flight
     *                       population: light that was never published
     *                       cannot be captured, only fabricated)
     * @param captureLight   false when the E6 light valve is spent -
     *                       the pin stores geometry with flat light,
     *                       counted by the caller
     */
    static Pin capture(long key, LevelChunk chunk, boolean lightPublished,
            boolean captureLight, Settings settings) {
        return new Pin(key, chunk, false, lightPublished, captureLight, settings,
                FarField.storeGeneration());
    }

    /**
     * <b>Phase 3:</b> capture one column the client STILL HOLDS - freeze
     * its blocks and heightmaps into a {@link ColumnSnapshot}, then grab
     * exactly the same light-layer refs a leaving capture grabs. Game
     * thread, from the fill/edit scheduler rather than from a packet
     * seam.
     *
     * <p>The light grab is unchanged and needs no special case: a
     * snapshot's {@code getLevel()} and {@code getPos()} are the live
     * level and the live position, so the constructor below reads the
     * SAME engine columns it would have read for the live chunk, and the
     * refs it takes are frozen for the same copy-on-write reason (Phase
     * 5's {@code LightView} contract). Only {@code lightPublished}
     * differs in practice: a live column that has not seen E3 yet is
     * held back by G1 and never reaches this call at all.</p>
     *
     * @param key            the packed column key
     * @param liveChunk      the cache-resident chunk; never retained
     * @param lightPublished the record's {@code lightReady}
     */
    static Pin captureLive(long key, LevelChunk liveChunk, boolean lightPublished,
            Settings settings) {
        return new Pin(key, ColumnSnapshot.freeze(liveChunk), true, lightPublished,
                true, settings, FarField.storeGeneration());
    }

    private static DataLayer[] grabLayers(LevelLightEngine engine,
            LightLayer layer, int cx, int cz, int minSection, int count) {
        LayerLightEventListener listener = engine.getLayerListener(layer);
        DataLayer[] grabbed = new DataLayer[count];
        for (int i = 0; i < count; i++) {
            grabbed[i] = listener.getDataLayerData(
                    SectionPos.of(cx, minSection + i, cz));
        }
        return grabbed;
    }

    private static boolean anyLayer(DataLayer[] layers) {
        for (DataLayer layer : layers) {
            if (layer != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finish-time: capture a STAYED-live lateral neighbour's light refs
     * (game thread, the neighbour still in the cache so its engine
     * column is live). The refs stay valid bytes whatever the engine
     * does later; a mutation under them is fresher truth.
     */
    void captureSideLight(int side, LevelChunk neighbor, boolean published) {
        if (!published || !realLight) {
            return;
        }
        LevelLightEngine engine = neighbor.getLevel().getLightEngine();
        int sections = engine.getMaxLightSection() - minLightSection + 1;
        DataLayer[] sky = grabLayers(engine, LightLayer.SKY,
                neighbor.getPos().x(), neighbor.getPos().z(), minLightSection, sections);
        DataLayer[] block = grabLayers(engine, LightLayer.BLOCK,
                neighbor.getPos().x(), neighbor.getPos().z(), minLightSection, sections);
        if (anyLayer(sky) || anyLayer(block)) {
            sideSky[side] = sky;
            sideBlock[side] = block;
            sideLightCaptured[side] = true;
        }
    }

    /**
     * Phase 3, finish-time: grab a stayed-live LATERAL neighbour's biome
     * containers (game thread, refs only - see {@link #sideTint}).
     */
    void captureSideTint(int side, ChunkAccess neighbor) {
        sideTint[side] = ColumnSnapshot.biomeRefs(neighbor);
    }

    /**
     * Phase 3, finish-time: the same for a stayed-live DIAGONAL. A
     * diagonal is still not a geometry conduit (the flood cannot cross a
     * plane it has no interior for), but the tint box average at Biome
     * Blend 2 does reach the corner 2x2 of the pad, so refusing it would
     * leave a visible corner artefact where the two edge-repeat bands
     * meet.
     */
    void captureDiagTint(int corner, ChunkAccess neighbor) {
        diagTint[corner] = ColumnSnapshot.biomeRefs(neighbor);
    }

    /** May this pin's walk store a light plane at all? */
    boolean ownLightReady() {
        return ownLightCaptured;
    }

    /** The walk's tint source: the pin's own biomes, no live reads. */
    BlockAndTintGetter tintView() {
        PinTintView view = tintView;
        if (view == null) {
            view = new PinTintView();
            tintView = view; // built at finish (game thread), read by the walk
        }
        return view;
    }

    // ------------------------------------------------------------------
    // NeighborView (CAPTURED): the pin graph and the strips
    // ------------------------------------------------------------------

    @Override
    public ChunkAccess lateral(int side) {
        Pin neighbor = sidePin[side];
        return neighbor == null ? null : neighbor.chunk;
    }

    @Override
    public ChunkAccess diagonal(int corner) {
        Pin neighbor = diagPin[corner];
        return neighbor == null ? null : neighbor.chunk;
    }

    @Override
    public ShellExtractor.CapturedStrip strip(int side) {
        return strips[side];
    }

    // ------------------------------------------------------------------
    // LightView (CAPTURED): the layer refs, with the engine's own read
    // semantics reproduced from the capture (javap'd this session):
    // sky = SkyLightSectionStorage.getLightValue with cached=false -
    // a present layer answers directly (ip 86-94, 147-173); a null
    // layer walks UP to the first present one and reads its bottom row
    // (ip 101-144: BlockPos.getFlatIndex zeroes y, then
    // SectionPos.offset(UP) until a layer answers); off the top is 15
    // (ip 121-123), and the no-entry/above-top arm (ip 51-85) is the
    // same all-null-walk 15. Block = BlockLightSectionStorage
    // .getLightValue: null layer is 0, else the layer's nibble
    // (ip 13-19, 20-46). A dimension with no sky engine answers sky 0
    // (DummyLightLayerEventListener), reproduced by the hasSkyLight
    // guard.
    // ------------------------------------------------------------------

    @Override
    public boolean sideAdmissible(int side) {
        Pin neighbor = sidePin[side];
        if (neighbor != null) {
            return neighbor.ownLightReady();
        }
        return sideLightCaptured[side];
    }

    @Override
    public int skyBlock(int wx, int y, int wz) {
        int dx = (wx >> 4) - chunkX;
        int dz = (wz >> 4) - chunkZ;
        DataLayer[] sky;
        DataLayer[] block;
        if (dx == 0 && dz == 0) {
            if (!ownLightCaptured) {
                return -1; // UNKNOWN never stores
            }
            sky = ownSky;
            block = ownBlock;
        } else {
            int side = sideOf(dx, dz);
            if (side < 0) {
                return -1; // unreachable by construction (one-axis probes)
            }
            Pin neighbor = sidePin[side];
            if (neighbor != null) {
                if (!neighbor.ownLightCaptured) {
                    return -1;
                }
                sky = neighbor.ownSky;
                block = neighbor.ownBlock;
            } else if (sideLightCaptured[side]) {
                sky = sideSky[side];
                block = sideBlock[side];
            } else {
                return -1;
            }
        }
        int idx = (y >> 4) - minLightSection;
        int lx = wx & 15;
        int lz = wz & 15;
        int skyValue = hasSkyLight ? skyValue(sky, idx, lx, y & 15, lz) : 0;
        int blockValue = 0;
        if (idx >= 0 && idx < block.length) {
            DataLayer layer = block[idx];
            if (layer != null) {
                blockValue = layer.get(lx, y & 15, lz);
            }
        }
        return (nibble(skyValue) << 4) | nibble(blockValue);
    }

    private static int skyValue(DataLayer[] sky, int idx, int lx, int ly, int lz) {
        if (idx >= sky.length) {
            return 15; // above the light range: open sky
        }
        if (idx >= 0) {
            DataLayer layer = sky[idx];
            if (layer != null) {
                return layer.get(lx, ly, lz);
            }
        }
        // The engine's walk-up: first present layer above answers with
        // its BOTTOM row; none at all is open sky (or a virgin column).
        for (int i = Math.max(0, idx + 1); i < sky.length; i++) {
            if (sky[i] != null) {
                return sky[i].get(lx, 0, lz);
            }
        }
        return 15;
    }

    private static int nibble(int value) {
        return value < 0 ? 0 : Math.min(value, 15);
    }

    /** The side index of a one-chunk lateral step, or -1. */
    private static int sideOf(int dx, int dz) {
        if (dz == 0) {
            if (dx == -1) {
                return ShellExtractor.SIDE_W;
            }
            if (dx == 1) {
                return ShellExtractor.SIDE_E;
            }
        } else if (dx == 0) {
            if (dz == -1) {
                return ShellExtractor.SIDE_N;
            }
            if (dz == 1) {
                return ShellExtractor.SIDE_S;
            }
        }
        return -1;
    }

    /** The pin-graph chunk owning block (bx, bz), or null. */
    private ChunkAccess chunkFor(int bx, int bz) {
        int dx = (bx >> 4) - chunkX;
        int dz = (bz >> 4) - chunkZ;
        if (dx == 0 && dz == 0) {
            return chunk;
        }
        int side = sideOf(dx, dz);
        if (side >= 0) {
            return lateral(side);
        }
        for (int corner = 0; corner < 4; corner++) {
            if (ShellExtractor.CORNER_DX[corner] == dx
                    && ShellExtractor.CORNER_DZ[corner] == dz) {
                return diagonal(corner);
            }
        }
        return null;
    }

    /**
     * The tint facade: a {@code BlockAndTintGetter} whose colour
     * resolution reads the PIN's biomes, never the live level - the
     * pinned chunk is out of the cache, so {@code ClientLevel
     * .getBlockTint} would resolve the fallback biome for it (the same
     * wrong answer the deleted {@code drainRetained} always got), and
     * the live level must not be read off-thread anyway.
     *
     * <h2>pre19 (the owner's S1): this is now vanilla's own function</h2>
     * <b>Through pre18 it was not, and that is the defect the owner kept
     * reporting.</b> The old body answered
     * {@code resolver.getColor(chunk.getNoiseBiome(quart of x, y, z), x,
     * z)} - one raw biome colour per position - which diverges from
     * {@code ClientLevel.getBlockTint} three separate ways, all of them
     * visible:
     * <ul>
     * <li><b>no box average.</b> {@code ClientLevel.calculateBlockTint}
     *     (javap ip 19-239) means over {@code [x-r, x+r] x [z-r, z+r]} at
     *     {@code Options.biomeBlendRadius()}, default 2. The pin returned
     *     the r = 0 answer whatever the player's setting: a HARD STEP
     *     where the near field ramps over {@code 2r+1} blocks. On a
     *     swamp/plains water seam that is 128 of 255 on blue in one
     *     block, against vanilla's five steps of 25;</li>
     * <li><b>no {@code BiomeManager} zoom.</b> {@code getBiome(pos)} is
     *     {@code getBiomeManager().getBiome(pos)}, which subtracts 2 from
     *     x/y/z, takes the quart, and picks fuzzily among the eight
     *     surrounding quarts on {@code biomeZoomSeed} (javap ip 0-46+).
     *     A raw {@code QuartPos.fromBlock} lookup skips all of it, so the
     *     step also sat on the 4-block quart grid, displaced ~2 blocks
     *     from where vanilla puts it;</li>
     * <li><b>a HOME clamp.</b> Outside the pin graph the old body used
     *     the home chunk's biome at the asked position - so record A and
     *     record B answered differently for the SAME world column, and
     *     the "two chunks sample the same world position, therefore they
     *     agree" argument the tint grid was built on was void on this
     *     path.</li>
     * </ul>
     * Every column the client DROPS at the LOD seam is written through
     * here, so this was not a corner: it was one of the two populations
     * on the owner's disk, differing from the other by up to a whole
     * biome delta near any boundary. "just chunks of color."
     *
     * <p><b>What it does now.</b> {@link #getBlockTint} is a
     * transcription of {@code calculateBlockTint}: the same box, the same
     * {@code (2r+1)^2} count, the same per-channel integer divide, over
     * biomes resolved through {@link #pinBiomes()} - the LEVEL's own
     * {@code BiomeManager}, captured at pin time for its zoom seed and
     * re-pointed at the pin graph with
     * {@code BiomeManager.withDifferentSource}, so the zoom is vanilla's
     * arithmetic on our biome source. The extractor asks for all 256
     * columns of one chunk at one y, so the second such ask builds a
     * SUMMED-AREA TABLE over the {@code (16+2r)^2} pad and every
     * remaining node is four array reads. The SAT is bit-exact, not an
     * approximation: it sums the same integers in a different order, and
     * integer addition is associative.</p>
     *
     * <p><b>The frontier, bounded.</b> At {@code r <= 7} (vanilla's own
     * maximum) the pad never leaves the 3x3 pin graph, so a COMPLETE
     * graph is exact. A missing neighbour is answered by clamping the
     * quart into the nearest chunk the graph does hold - edge repeat, the
     * standard box-filter policy - which confines the error to the
     * r-block strip against that side and leaves the interior exact. The
     * old home clamp put a whole foreign biome under the record's edge
     * instead. Same frontier rule as the S1 apron's: absence outside the
     * graph is answered conservatively, per side.</p>
     *
     * <p>The light-engine accessor deliberately throws: no vanilla
     * tint source reads it, and an unexpected consumer must degrade to
     * {@code sampleTint}'s white fallback (its catch-all) rather than
     * read a dead engine.</p>
     */
    private final class PinTintView implements BlockAndTintGetter {
        /** The pin graph re-pointed under vanilla's own zoom. */
        private BiomeManager pinBiomes;
        /** Scratch for {@link #rawColor}; worker thread, one walk. */
        private final BlockPos.MutableBlockPos biomeProbe =
                new BlockPos.MutableBlockPos();
        /** The (resolver, y) the last un-tabled query used. */
        private ColorResolver warmResolver;
        private int warmY = Integer.MIN_VALUE;
        /** The (resolver, y) {@link #satRed} and friends were built for. */
        private ColorResolver satResolver;
        private int satY;
        /** Per-channel summed-area tables over the pad, {@code (pad+1)^2}. */
        private int[] satRed;
        private int[] satGreen;
        private int[] satBlue;

        @Override
        public CardinalLighting cardinalLighting() {
            return cardinal;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();
            int r = biomeBlendRadius;
            if (r <= 0) {
                // calculateBlockTint ip 19-52: r == 0 short-circuits to
                // the single biome at the position, no average at all.
                return rawColor(resolver, x, y, z);
            }
            int homeX = chunkX << 4;
            int homeZ = chunkZ << 4;
            if (x >= homeX && x < homeX + 16 && z >= homeZ && z < homeZ + 16) {
                if (satRed == null || resolver != satResolver || y != satY) {
                    if (resolver == warmResolver && y == warmY) {
                        buildSat(resolver, y, r);
                    } else {
                        // First ask for this (resolver, y): the walk's own
                        // one-node-per-entry probe is often the only one,
                        // and a 25-sample mean is cheaper than a 400-sample
                        // table. The cell field's second node builds it.
                        warmResolver = resolver;
                        warmY = y;
                        return boxMean(resolver, x, y, z, r);
                    }
                }
                int w = 16 + 2 * r + 1;
                int lx = x - homeX + r;
                int lz = z - homeZ + r;
                int count = (2 * r + 1) * (2 * r + 1);
                return ARGB.color(
                        rectSum(satRed, w, lx - r, lz - r, lx + r, lz + r) / count,
                        rectSum(satGreen, w, lx - r, lz - r, lx + r, lz + r) / count,
                        rectSum(satBlue, w, lx - r, lz - r, lx + r, lz + r) / count);
            }
            return boxMean(resolver, x, y, z, r);
        }

        /**
         * {@code calculateBlockTint} ip 53-239, verbatim: sum
         * {@code ARGB.red/green/blue} over the {@code (2r+1)^2} box at
         * this y, then one integer divide per channel. The
         * {@code Cursor3D} is a plain nested loop here; the order of an
         * integer sum does not change it.
         */
        private int boxMean(ColorResolver resolver, int x, int y, int z, int r) {
            int count = (2 * r + 1) * (2 * r + 1);
            int red = 0;
            int green = 0;
            int blue = 0;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int c = rawColor(resolver, x + dx, y, z + dz);
                    red += ARGB.red(c);
                    green += ARGB.green(c);
                    blue += ARGB.blue(c);
                }
            }
            return ARGB.color(red / count, green / count, blue / count);
        }

        /**
         * Build the per-channel summed-area tables over the home chunk's
         * pad, {@code [homeX - r, homeX + 15 + r]} squared, at one y and
         * one resolver. {@code (16+2r)^2} raw samples serve all 256 of the
         * cell field's nodes - 400 instead of 6400 at the default radius.
         */
        private void buildSat(ColorResolver resolver, int y, int r) {
            int pad = 16 + 2 * r;
            int w = pad + 1;
            int minX = (chunkX << 4) - r;
            int minZ = (chunkZ << 4) - r;
            int[] red = new int[w * w];
            int[] green = new int[w * w];
            int[] blue = new int[w * w];
            for (int j = 0; j < pad; j++) {
                for (int i = 0; i < pad; i++) {
                    int c = rawColor(resolver, minX + i, y, minZ + j);
                    int at = (j + 1) * w + (i + 1);
                    red[at] = ARGB.red(c)
                            + red[at - 1] + red[at - w] - red[at - w - 1];
                    green[at] = ARGB.green(c)
                            + green[at - 1] + green[at - w] - green[at - w - 1];
                    blue[at] = ARGB.blue(c)
                            + blue[at - 1] + blue[at - w] - blue[at - w - 1];
                }
            }
            this.satRed = red;
            this.satGreen = green;
            this.satBlue = blue;
            this.satResolver = resolver;
            this.satY = y;
        }

        /** Inclusive box sum in pad coordinates. */
        private static int rectSum(int[] sat, int w, int x0, int z0, int x1, int z1) {
            return sat[(z1 + 1) * w + (x1 + 1)] - sat[z0 * w + (x1 + 1)]
                    - sat[(z1 + 1) * w + x0] + sat[z0 * w + x0];
        }

        /**
         * One position's UNBLENDED colour: the resolver applied to the
         * biome {@code BiomeManager} zooms to there. This is the term
         * vanilla's box average is an average OF.
         */
        private int rawColor(ColorResolver resolver, int x, int y, int z) {
            return resolver.getColor(
                    pinBiomes().getBiome(biomeProbe.set(x, y, z)).value(), x, z);
        }

        /**
         * The level's {@code BiomeManager} with its noise source swapped
         * for the pin graph. {@code withDifferentSource} keeps the
         * {@code biomeZoomSeed}, so {@code getBiome(BlockPos)} runs
         * vanilla's own quart-to-block zoom (javap: the -2 shift, the
         * {@code >> 2} quart, and the seeded fuzzy pick among eight
         * corners) over our biomes.
         */
        private BiomeManager pinBiomes() {
            BiomeManager built = pinBiomes;
            if (built == null) {
                built = levelBiomes.withDifferentSource(this::pinNoiseBiome);
                pinBiomes = built;
            }
            return built;
        }

        /**
         * The pin graph as a {@code NoiseBiomeSource}, in QUART
         * coordinates. Outside the graph the quart is clamped into the
         * nearest chunk the graph holds - stepping x first, then z, and
         * the home chunk is always present, so this terminates - and then
         * into that chunk's own four quarts, because
         * {@code ChunkAccess.getNoiseBiome} masks {@code & 3} rather than
         * clamping and would otherwise WRAP a foreign quart onto a local
         * one silently.
         */
        private Holder<Biome> pinNoiseBiome(int qx, int qy, int qz) {
            int dx = Mth.clamp(QuartPos.toSection(qx) - chunkX, -1, 1);
            int dz = Mth.clamp(QuartPos.toSection(qz) - chunkZ, -1, 1);
            // Phase 3: a neighbour whose GEOMETRY is a strip (or, for a
            // corner, nothing at all) can still answer its own biome
            // exactly, from the frozen containers the finish grabbed.
            // Asked before the clamp below, because the clamp is the
            // fallback for absence and this is presence.
            ColumnSnapshot.TintBiomes captured = tintAt(dx, dz);
            if (captured != null) {
                Holder<Biome> exact = captured.noiseBiome(qx, qy, qz);
                if (exact != null) {
                    return exact;
                }
            }
            ChunkAccess owner = graphAt(dx, dz);
            while (owner == null && (dx != 0 || dz != 0)) {
                if (dx != 0) {
                    dx -= Integer.signum(dx);
                } else {
                    dz -= Integer.signum(dz);
                }
                owner = graphAt(dx, dz);
            }
            if (owner == null) {
                owner = chunk; // unreachable: graphAt(0, 0) is the home chunk
                dx = 0;
                dz = 0;
            }
            int baseQx = QuartPos.fromSection(chunkX + dx);
            int baseQz = QuartPos.fromSection(chunkZ + dz);
            return owner.getNoiseBiome(
                    Mth.clamp(qx, baseQx, baseQx + 3), qy,
                    Mth.clamp(qz, baseQz, baseQz + 3));
        }

        /** The pin-graph chunk at a one-step offset, or null. */
        private ChunkAccess graphAt(int dx, int dz) {
            return chunkFor((chunkX + dx) << 4, (chunkZ + dz) << 4);
        }

        /**
         * The captured biome refs for a one-step offset, or null. The
         * home slot deliberately answers null: the home column's own
         * biomes ride its chunk (a snapshot copies them with the
         * section; a dropped chunk simply has them), so
         * {@link #graphAt} is the one answer for it and a second source
         * for the same data could only ever disagree.
         */
        private ColumnSnapshot.TintBiomes tintAt(int dx, int dz) {
            if (dx == 0 && dz == 0) {
                return null;
            }
            if (dx == 0 || dz == 0) {
                int side = sideOf(dx, dz);
                return side < 0 ? null : sideTint[side];
            }
            for (int corner = 0; corner < 4; corner++) {
                if (ShellExtractor.CORNER_DX[corner] == dx
                        && ShellExtractor.CORNER_DZ[corner] == dz) {
                    return diagTint[corner];
                }
            }
            return null;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            throw new IllegalStateException(
                    "pinned extraction must not read the light engine");
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null; // unloads clear them anyway (dossier 1.2)
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            ChunkAccess owner = chunkFor(pos.getX(), pos.getZ());
            return owner == null ? Blocks.AIR.defaultBlockState()
                    : owner.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return chunk.getHeight();
        }

        @Override
        public int getMinY() {
            return chunk.getMinY();
        }
    }
}
