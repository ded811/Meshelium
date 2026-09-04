/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.farfield.extract;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.LevelChunkTicks;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>T2 Phase 3: the LIVE half of "one Pin, two sources".</b> A frozen
 * stand-in for a chunk the client still holds, built on the game thread
 * so the walk that reads it can run on {@link PinWorker} - the same
 * capture-then-walk split Phase 5 built for LEAVING columns, applied to
 * the population that stayed behind because it reads mutable state
 * (docs/FARFIELD-PERF-BRIEF.md section 2).
 *
 * <h2>Why the stand-in is a real {@code LevelChunk}</h2>
 * The walk reads its home column through {@code getSections()},
 * {@code getHeight(Heightmap.Types, int, int)}, {@code getNoiseBiome},
 * {@code getPos()} and the three height accessors, and it reads
 * neighbours through the same surface. Every one of those is
 * {@code ChunkAccess} behaviour over per-chunk state, so the cheapest
 * correct capture is a {@code LevelChunk} of our own holding COPIES of
 * that state: {@link ShellExtractor}'s walk, {@code captureStrip},
 * {@code cutTopAt} and {@link Pin}'s tint facade then work on a snapshot
 * without one line of change, and - the part that matters more - a
 * snapshot walk and a live walk are the SAME code reading the SAME
 * shapes, so they cannot drift apart the way two parallel readers would.
 *
 * <h2>What is copied, what is shared, and why each is safe</h2>
 * <ul>
 * <li><b>Block states: COPIED.</b> {@code PalettedContainer} is mutated
 *   in place by {@code LevelChunkSection.setBlockState}, so a live
 *   container is not readable off-thread at any price.
 *   {@code LevelChunkSection.copy()} is the bit-packed clone that fixes
 *   that (javap, 26.2 merged jar: {@code public LevelChunkSection
 *   copy();} -> the private copy constructor at ip 36-57 does
 *   {@code states.copy()} and {@code biomes.copy()}; {@code
 *   PalettedContainer.copy()} -> {@code Data.copy()} -> {@code
 *   BitStorage.copy()}, whose {@code SimpleBitStorage} implementation is
 *   a {@code long[].clone()} at ip 12-19). It takes no lock and touches
 *   no {@code ThreadingDetector}: this runs on the game thread, which is
 *   the only client thread that ever writes a section
 *   ({@code ClientPacketListener}'s block handlers all pass
 *   {@code ensureRunningOnSameThread}), so the copy is exact rather than
 *   merely tear-free.</li>
 * <li><b>Heightmaps: COPIED, all four, raw.</b> {@code
 *   ChunkAccess.setHeightmap(Types, long[])} forwards to
 *   {@code Heightmap.setRawData}, which is one
 *   {@code System.arraycopy} into OUR storage (javap ip 0-29), and
 *   {@code Heightmap.getRawData()} is a bare {@code data.getRaw()} (ip
 *   0-9). Both chunks have the same world height, so both storages have
 *   the same {@code Mth.ceillog2(height + 1)} bits and the same raw
 *   length, and the size-mismatch arm at ip 30-76 is unreachable.</li>
 * <li><b>Biomes: copied with the section, and separately SHAREABLE.</b>
 *   Both client writers of a section's biome container replace the field
 *   rather than mutate the container - {@code
 *   LevelChunkSection.readBiomes} does {@code recreate()}, {@code read},
 *   {@code putfield biomes} (javap ip 0-20) and {@code fillBiomesFromNoise}
 *   the same (ip 0-9 then a fresh container). A biome container reference
 *   is therefore FROZEN the moment it is taken, exactly like a
 *   {@code DataLayer} ref, which is what {@link TintBiomes} relies on to
 *   give a captured walk vanilla-exact tint across the chunk plane for
 *   the price of a pointer read.</li>
 * <li><b>Block entities: not captured, deliberately.</b> The walk never
 *   reads one ({@code Pin}'s facade already answers null), and an unload
 *   clears them anyway.</li>
 * </ul>
 *
 * <h2>OCEAN_FLOOR, and the priming scan that is not there in 26.2</h2>
 * The census warned that reading the sea-floor heightmap PRIMES a
 * 5,000-15,000 block scan on first touch. <b>That is not true of this
 * jar and it is worth writing down, because the belief is also in
 * {@code ShellExtractor.cutTopAt}'s comment.</b> The {@code LevelChunk}
 * constructor creates a {@code Heightmap} for every type in
 * {@code ChunkStatus.FULL.heightmapsAfter()}, which is
 * {@code ChunkStatus.FINAL_HEIGHTMAPS = EnumSet.of(OCEAN_FLOOR,
 * WORLD_SURFACE, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES)} (javap,
 * static init ip 12-27, and the constructor loop at ip 49-118), so the
 * map entry always EXISTS and {@code ChunkAccess.getHeight}'s priming
 * arm (ip 15-88) is unreachable on a client chunk. What the client never
 * does is FILL it: {@code LevelChunk.replaceWithPacketData} sets only
 * the heightmaps the packet carried (ip 44-51), and only the
 * {@code Usage.CLIENT} types are sent - WORLD_SURFACE, MOTION_BLOCKING
 * and MOTION_BLOCKING_NO_LEAVES; OCEAN_FLOOR is {@code Usage.LIVE_WORLD}
 * (javap, {@code Heightmap$Types} static init ip 66-85). So on the
 * client OCEAN_FLOOR reads as an all-zero storage forever, i.e.
 * {@code minY - 1} for every column.
 *
 * <p>This capture copies that all-zero storage verbatim rather than
 * priming it, and that choice is the whole point: <b>a captured walk
 * must produce the same bytes the live walk produced, or Phase 3 would
 * be smuggling a terrain change in behind a performance change.</b> If
 * the sea-floor cut is ever wanted for real it is a separate wave with
 * its own before/after pictures, and the right place for the scan is
 * then here, on this thread's copy, or on the worker.</p>
 *
 * <h2>Cost, from the sizes above</h2>
 * Per column, overworld, 24 sections: four {@code System.arraycopy}s of
 * 36 longs for the heightmaps (~0.2 us); one {@code copy()} per section,
 * which is free for the single-value ones (air, and solid stone below
 * the surface, both of which carry a {@code ZeroBitStorage}) and a
 * 2-4 KB {@code long[].clone()} for the ~4-8 mixed sections around the
 * surface (~5-10 us); and the {@code LevelChunk} shell itself - an
 * EnumMap, four {@code SimpleBitStorage(9, 256)}, three small maps, two
 * empty tick containers and two 24-element arrays (~1-2 us, ~4 KB).
 * <b>~7-12 us and ~15-35 KB a column</b>, against the ~900 us the live
 * walk took on the same thread.
 */
final class ColumnSnapshot {

    /**
     * The heightmaps a snapshot carries: every type a client chunk owns,
     * copied whether the walk asks for it or not. Four
     * {@code System.arraycopy}s is cheaper than deciding which three the
     * walk will want this time, and copying all of them is what makes
     * "the snapshot answers exactly what the live chunk answered" a
     * property of the type rather than a property of the caller.
     */
    private static final Heightmap.Types[] CAPTURED_HEIGHTMAPS = {
        Heightmap.Types.WORLD_SURFACE,
        Heightmap.Types.MOTION_BLOCKING,
        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
        Heightmap.Types.OCEAN_FLOOR,
    };

    private ColumnSnapshot() {
    }

    /**
     * Freeze one live column. <b>Game thread only</b> - it reads the
     * client's own section containers, which no other thread may touch.
     *
     * @param live the cache-resident chunk; unchanged by this call
     *             except that nothing at all is written to it
     * @return a {@code LevelChunk} holding copies of everything the walk
     *         reads, safe to hand to {@link PinWorker}
     */
    static LevelChunk freeze(LevelChunk live) {
        LevelChunkSection[] liveSections = live.getSections();
        LevelChunkSection[] copies = new LevelChunkSection[liveSections.length];
        for (int i = 0; i < liveSections.length; i++) {
            LevelChunkSection section = liveSections[i];
            // A null slot cannot occur on a constructed chunk
            // (ChunkAccess's constructor runs replaceMissingSections over
            // the array at ip 169-177), but the walk's own stateAt()
            // tolerates one and so does the snapshot: a null here is
            // filled with a fresh empty section by the same constructor.
            copies[i] = section == null ? null : section.copy();
        }
        // The same argument list ClientChunkCache's own
        // "new LevelChunk(level, pos)" expands to (javap, the 2-arg
        // constructor, ip 0-27), with our sections in place of null:
        // UpgradeData.EMPTY, two empty tick containers, inhabited time 0,
        // no post-load processor and no blending data. The constructor
        // stores the level and allocates; it registers nothing and
        // touches no cache (ip 17-136).
        LevelChunk snapshot = new LevelChunk(live.getLevel(), live.getPos(),
                UpgradeData.EMPTY, new LevelChunkTicks<>(), new LevelChunkTicks<>(),
                0L, copies, null, null);
        for (Heightmap.Types type : CAPTURED_HEIGHTMAPS) {
            // getOrCreateHeightmapUnprimed is a computeIfAbsent over a map
            // that already holds all four (see the class javadoc), so this
            // is a map get on both sides and primes nothing.
            snapshot.setHeightmap(type,
                    live.getOrCreateHeightmapUnprimed(type).getRawData());
        }
        return snapshot;
    }

    /**
     * Grab one column's per-section biome containers as REFS - the
     * copy-on-write argument in the class javadoc - for a neighbour the
     * capture wants tint from but not geometry. Game thread; the result
     * is frozen and worker-safe.
     *
     * <p>This is what keeps a captured walk's stored tint identical to
     * the live walk's across a chunk plane. Without it {@link Pin}'s
     * {@code PinTintView} answers an out-of-graph biome probe by clamping
     * into the nearest chunk it does hold - edge repeat, which is honest
     * for a column whose neighbour is genuinely gone but wrong for one
     * whose neighbour is sitting in the cache, and wrong in exactly the
     * direction the owner reported for a whole release train ("just
     * chunks of color"). Twenty-four pointer reads and one list is a
     * cheap price for not re-opening that.</p>
     */
    static TintBiomes biomeRefs(ChunkAccess live) {
        LevelChunkSection[] sections = live.getSections();
        List<PalettedContainerRO<Holder<Biome>>> refs =
                new ArrayList<>(sections.length);
        for (LevelChunkSection section : sections) {
            refs.add(section == null ? null : section.getBiomes());
        }
        return new TintBiomes(live.getMinSectionY(), live.getMinY(),
                live.getHeight(), refs);
    }

    /**
     * One captured column's biomes, addressable in QUART coordinates -
     * a transcription of {@code ChunkAccess.getNoiseBiome(int, int, int)}
     * (javap ip 0-64: clamp the quart y into
     * {@code [QuartPos.fromBlock(minY), that + QuartPos.fromBlock(height)
     * - 1]}, take the section of {@code QuartPos.toBlock(clamped)}, then
     * {@code section.getNoiseBiome(qx & 3, qy & 3, qz & 3)}) over
     * captured containers instead of a live chunk's.
     *
     * <p>Immutable after construction; every field is written on the
     * game thread before the owning {@link Pin} is handed over.</p>
     */
    static final class TintBiomes {
        private final int minSectionY;
        private final int minY;
        private final int height;
        private final List<PalettedContainerRO<Holder<Biome>>> bySection;

        TintBiomes(int minSectionY, int minY, int height,
                List<PalettedContainerRO<Holder<Biome>>> bySection) {
            this.minSectionY = minSectionY;
            this.minY = minY;
            this.height = height;
            this.bySection = bySection;
        }

        /** The biome at a quart position, or null when unanswerable. */
        Holder<Biome> noiseBiome(int qx, int qy, int qz) {
            int lowQuart = QuartPos.fromBlock(minY);
            int highQuart = lowQuart + QuartPos.fromBlock(height) - 1;
            int quartY = Mth.clamp(qy, lowQuart, highQuart);
            int index = (QuartPos.toBlock(quartY) >> 4) - minSectionY;
            if (index < 0 || index >= bySection.size()) {
                return null;
            }
            PalettedContainerRO<Holder<Biome>> container = bySection.get(index);
            return container == null ? null
                    : container.get(qx & 3, quartY & 3, qz & 3);
        }
    }
}
