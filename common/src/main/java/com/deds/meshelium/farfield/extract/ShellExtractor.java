/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.farfield.extract;

import com.deds.meshelium.farfield.mesh.SpriteUvResolver;
import com.deds.meshelium.farfield.store.ShellCodec;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Far-field wave W2: one {@link LevelChunk} in, one MODEL S shell out
 * (FAR-FIELD-DESIGN.md section 3.1; SHELL-CENSUS-2026-08.md methodology).
 * A shell is the set of block positions owning at least one EXPOSED face,
 * palette-packed at block-NAME level; it exists only to be meshed at far
 * distances and can never reconstruct block data.
 *
 * <h2>THE MODEL: what is stored, and what decides it (pre11, the owner's L0)</h2>
 * The owner's standing instruction for this build: "we dont need a case by
 * case basis here we need a solid plan. if a block is not solid and
 * partially see through we need to treat it as such." Three sentences
 * answer it, and everything else in this class serves them.
 *
 * <ol>
 * <li><b>A CELL is a position plus a palette entry, and since format 4
 *     that entry is a block STATE</b> ({@link ShellCodec}), so a stair
 *     faces the way it faced, a torch leans on the wall it leaned on, snow
 *     is as deep as it was, flowing water is as tall as it was and a grass
 *     block under snow has white sides. What a cell still does not carry is
 *     anything a neighbour decides at MESH time; see the palette section
 *     below for what that costs and what it does not.</li>
 * <li><b>A FACE is stored when vanilla would draw it</b>, i.e. when
 *     {@code Block.shouldRenderFace(owner, neighbour, direction)} says so.
 *     Not {@code canOcclude()} - a boolean cannot answer a question about
 *     SHAPES. See {@link #exposed}, which carries the derivation, the one
 *     far-field deviation and the four defects that were all this one
 *     defect.</li>
 * <li><b>A RAY passes anything that is not a FULL occluder</b>,
 *     {@code !isSolidRender()}, which is the same first question
 *     {@code shouldRenderFace} asks. {@link #closeLeaks} floods on exactly
 *     that predicate, so "what hides a face" and "what stops a ray" are one
 *     rule and cannot drift apart.</li>
 * </ol>
 *
 * <p>The mesher's half of the model is the matching sentence: a cell draws
 * the block's own BAKED QUADS, gated by this face mask
 * ({@code SpriteUvResolver}, {@code ShellMesher}). A quad's
 * {@code cullface} is the model author's statement of which neighbour may
 * hide it and the mask is this class's statement of which neighbours do, so
 * the two halves meet without a reconciliation rule.</p>
 *
 * <h2>What that replaced</h2>
 * Until pre11 this class held two exposure rules and a pile of notes about
 * where they disagreed with reality:
 * <ul>
 * <li>an OPAQUE owner asked {@code Block.shouldRenderFace} (added in pre10,
 *     for the snowy-slope hole below) and a SEE-THROUGH owner asked
 *     {@code neighbour.isAir()}, which treats every non-air neighbour as a
 *     full occluder. That is the owner's L2 verbatim: leaves beside a snow
 *     layer lost their sides because snow is {@code canOcclude() == true}
 *     while covering 2/16 of the face it claims;</li>
 * <li>a KELP EXCEPTION: a see-through block whose mask came out zero was
 *     admitted with one nominal UP bit if {@code SpriteUvResolver} said it
 *     would DRAW as a plant. Deleted. Kelp's faces are exposed against
 *     water for the same reason a leaf's are exposed against snow, and
 *     water still hides against water because
 *     {@code LiquidBlock.skipRendering} says so;</li>
 * <li>a long note explaining that a partial neighbour suppressed faces from
 *     BOTH sides and that this was survivable only while the mesher drew
 *     partials as cubes. Both halves of that are now gone: the shape is the
 *     model's, and the occlusion is the shape's.</li>
 * </ul>
 *
 * <h2>The census's classification, and where this now differs from it</h2>
 * The census (SHELL-CENSUS-2026-08.md) classified 178 observed block NAMES
 * by hand into AIR-LIKE / TRANSPARENT-RENDERED / OPAQUE and counted a face
 * exposed iff the neighbour was air-like, or - for an opaque owner - the
 * neighbour was transparent-rendered. This class no longer classifies at
 * all; it asks vanilla per state. The differences are all in the same
 * direction, MORE faces, and all bounded by geometry:
 * <ul>
 * <li>a see-through owner against a PARTIAL occluder (leaves beside snow,
 *     water beside a slab, a plant beside a path) now emits where both the
 *     census and pre10 dropped. This is the fix, not the cost;</li>
 * <li>a see-through owner against a DIFFERENT see-through block (a leaf
 *     against glass, water against a lily pad, a flower against a fern) now
 *     emits. Vanilla draws these; they are surface features and there are
 *     few of them;</li>
 * <li>a see-through owner against the SAME BLOCK still does not emit, and
 *     that is the ONE place this class overrules vanilla. Without it a
 *     canopy on Fancy graphics stores every interior leaf face - about
 *     1,000 faces and 4 KB of raw record for four trees in a chunk - for
 *     geometry no ray from outside can reach. It is vanilla's own Fast
 *     graphics rule ({@code LeavesBlock.skipRendering}) and glass's rule
 *     ({@code HalfTransparentBlock.skipRendering}) applied uniformly rather
 *     than to two families. See {@link #exposed}.</li>
 * </ul>
 *
 * <h2>THE PALETTE IS KEYED BY STATE (format 4), and it is not free</h2>
 * The owner's pre11 list - torches all facing one way, stairs not rotating,
 * flowing water drawn solid, leaf piles drawn as a box, grass sides green
 * under snow - was one defect: the palette was keyed by {@link Block}, so
 * every state of a block collapsed onto one entry and the mesher drew the
 * default. It is keyed by {@code BlockState} now. Three things keep that
 * from being expensive:
 *
 * <ul>
 * <li><b>Nothing per cell.</b> A cell already spent one byte on a palette
 *     index; that index now selects a finer thing. The whole cost is in the
 *     palette TABLE, and the table's disk form stores each block name once
 *     ({@link ShellCodec}'s two-level palette), so a second orientation of
 *     a block the record already names costs about two bytes rather than a
 *     repeated 22-byte string.</li>
 * <li><b>States that DRAW the same share an entry</b>, decided by vanilla's
 *     own oracle ({@code SpriteUvResolver.sameAppearance}, i.e.
 *     {@code ModelManager.requiresRender}), which is what vanilla uses to
 *     decide whether a block update needs a chunk re-render. So all seven
 *     {@code distance} values of a leaf block are one entry, all sixteen
 *     {@code power} values of a redstone wire that share a shape and a
 *     colour are one entry, and a stair's forty rendered variants are forty
 *     - because they really do look like forty different things. Without
 *     this rule a forest chunk would have grown six leaf entries for
 *     nothing.</li>
 * <li><b>An extra state of a block already in the palette re-uses that
 *     block's sampled tint grid</b> when its corner sample agrees, so the
 *     game-thread biome-colour work does not grow with the palette. See
 *     {@link #paletteIndexOf}.</li>
 * </ul>
 *
 * <p><b>What happens when the palette fills.</b> The one-byte cell index
 * caps the table at {@value ShellCodec#MAX_PALETTE} ENTRIES where it used
 * to cap NAMES, and the census's largest real chunk palette was 44 names.
 * A chunk that somehow exhausts it does not drop cells any more: a state
 * whose block is already in the table falls back to that block's first
 * entry, which is the pre-format-4 look for that block and never a hole.
 * Only a cell whose block is not in the table at all is still dropped.</p>
 *
 * <h2>Boundaries</h2>
 * <ul>
 * <li><b>Lateral chunk edges</b>: the real neighbour chunk when loaded
 * ({@code ClientChunkCache.getChunk(x, z, ChunkStatus.FULL, false)} —
 * javap-verified; bytecode returns null when absent, ip 53-54, and the
 * empty chunk only when the flag is true, ip 48-52). An absent neighbour is
 * UNKNOWN and not air, and {@link #exposedLateral} chooses which way to be
 * wrong about it - the one place a plain {@code canOcclude()} is still the
 * right test, because there is no neighbour to ask about shapes.</li>
 * <li><b>Above world top</b> ({@code getMaxY()} is INCLUSIVE — default body
 * is {@code getMinY() + getHeight() - 1}, javap ip 0-15): AIR.</li>
 * <li><b>Below world bottom</b>: void — NO face (census rule; a superflat
 * validation chunk emits exactly 256 top faces).</li>
 * </ul>
 *
 * <h2>A SNOW LAYER still reports {@code canOcclude() == true}, and it no
 * longer matters</h2>
 * {@code Blocks.SNOW} is registered
 * {@code of().mapColor().replaceable().forceSolidOff().randomTicks()
 * .strength(0.1f).requiresCorrectToolForDrops().sound().isViewBlocking()
 * .pushReaction()} (javap, {@code Blocks} clinit ip 10206-10264). That
 * chain never calls {@code noOcclusion()} and never calls
 * {@code noCollision()}, the only two things that clear the flag
 * ({@code Properties} ctor sets {@code canOcclude = true} at ip 68-70;
 * {@code noOcclusion} ip 0-2 and {@code noCollision} ip 5-7 clear it), and
 * {@code BlockStateBase} copies it unconditionally (ctor ip 128-134). So
 * the flag is a lie about a 2/16-tall block, and pre10's snowy-slope hole
 * (128 suppressed side faces on a 16x16 slope dropping one block every two
 * columns) came straight out of believing it. Nothing in this class asks
 * the flag about occlusion any more:
 * <ul>
 * <li><b>the exposure rule</b> asks the SHAPE, so a ground block beside a
 * snowy step keeps the face the snow does not cover, and snow against snow
 * at the same height still emits nothing (identical face shapes, empty
 * subtraction) - a flat snow field is still 256 top faces;</li>
 * <li><b>{@link #closeLeaks}</b> asks {@code isSolidRender()}, so the flood
 * now walks THROUGH a snow layer, a slab, a path and a stair, which is what
 * a ray does. On a snowy surface that is one extra level per column, all of
 * it already inside the band; where it buys something is a cave mouth or a
 * cliff ledge whose lip carries a partial block, which used to stop the
 * flood dead and stay cut;</li>
 * <li><b>the band floor</b> is unaffected either way. {@code forceSolidOff()}
 * makes {@code isSolid()} false ({@code calculateSolid} ip 10-28), so
 * {@code blocksMotion()} is false ({@code BlockStateBase.blocksMotion} ip
 * 19-23) and MOTION_BLOCKING_NO_LEAVES steps over the snow to the ground
 * beneath it, while WORLD_SURFACE counts it. {@code cutTopAt} takes the
 * MINIMUM, so the cut lands on the ground and the snow, sitting one above,
 * is inside the band. A snowy slope stores exactly what a bare slope stores
 * - measured 352 cells either way.</li>
 * </ul>
 *
 * <h2>Surface band (the 74.3% lever, design section 3.1)</h2>
 * Per-column {@code cutTop} from the chunk's own heightmaps: WORLD_SURFACE
 * in general; columns whose WORLD_SURFACE block carries a non-empty
 * {@code FluidState} re-cut from OCEAN_FLOOR so sea floors survive (the
 * design's ocean rule — a water-plane cut would keep the surface sheet and
 * drop the floor). Cells with {@code y >= cutTop - bandOffset} survive.
 * The band is a LOWER bound only, so the water surface sheet above an
 * ocean-floor cut still survives (it sits above the cut by construction).
 *
 * <p>The floor is then lowered twice more, and the second of those is what
 * makes layer 1 hole-free: {@link #computeBandFloors} takes the minimum
 * over the column and its four lateral neighbours (the heightfield rule,
 * exact for cliffs and steps), and {@link #closeLeaks} then grows the band
 * wherever the shell is still OPEN — see its javadoc for the argument and
 * the measured price.</p>
 *
 * <p><b>One rule shared by both stages, and it is the pre15 P5 fix
 * (2026-08-23).</b> Wherever the band has to GUESS how far down to keep,
 * it guesses from the minimum over the WHOLE CHUNK and not from a
 * per-column or per-edge-row quantity. Two places guessed locally and both
 * left the owner's "the bottom is missing": {@link #computeBandFloors}'s
 * absent-neighbour estimate (the edge row, which reports a built
 * structure's ROOF) and {@link #closeLeaks}'s leak budget (the column's own
 * floor, which rises with whatever stands over the opening). A local guess
 * assumes the terrain is locally flat; a mansion, a cliff and a cave mouth
 * are each a counter-example, and in every one of them the guess comes out
 * too HIGH and the bottom of something goes see-through. See
 * docs/FARFIELD-WAVES.md, "P4 AND P5 ANSWERED".</p>
 *
 * <p><b>Client heightmap honesty</b>: WORLD_SURFACE is Usage.CLIENT (the
 * server sends it; Types clinit ip 30-41), but OCEAN_FLOOR is
 * Usage.LIVE_WORLD (clinit ip 74-85) and {@code sendToClient()} is a plain
 * {@code usage == CLIENT} compare — the client NEVER receives it. The
 * first {@code getHeight(OCEAN_FLOOR, ...)} call primes it on the spot:
 * {@code ChunkAccess.getHeight} bytecode ip 66-71 calls
 * {@code Heightmap.primeHeightmaps(this, EnumSet.of(type))} on a map miss,
 * then returns {@code getFirstAvailable(x&15, z&15) - 1} (ip 99-103, so
 * the value is the y OF the highest counted block). That prime is a
 * one-time 16x16-column scan of a chunk that is about to be discarded —
 * the only mutation this "pure" function performs, and a harmless one.</p>
 *
 * <h2>Output format ({@link ShellCodec}'s, census MODEL S: 4 B/cell)</h2>
 * Cells are packed exclusively through {@link ShellCodec#packCell}, so
 * the bit layout (z bits 0-3, x bits 4-7, yRel 8-15, palette index
 * 16-23, face mask 24-29) lives in exactly one place — the codec's class
 * javadoc is the format authority. The {@code FACE_*} constants below
 * implement the codec's documented mask bit order (bit 0 down .. bit 5
 * east). Cells are emitted in ascending (y, z, x) order — deterministic
 * and zlib-friendly — with ONE exception: the water-surface rule below
 * emits a second cell at a position that already has one, immediately
 * after it, so the order is weakly ascending and a position is not a key. {@code minY} is the lowest cell y. The census
 * measured no real chunk with shell y-span over 255, but a pathological
 * span is clamped from the TOP (keep {@code maxY-255..maxY}, drop deeper
 * cells) because the far camera sees tops, not cave bottoms — the codec
 * refuses out-of-span cells rather than corrupt, so the clamp here is
 * what keeps every extracted shell encodable. The palette holds registry
 * names ({@code BuiltInRegistries.BLOCK.getKey(block)} — javap:
 * {@code public abstract net.minecraft.resources.Identifier getKey(T)};
 * the 26.2 Identifier rename, no ResourceLocation); a pathological chunk
 * with more than {@link ShellCodec#MAX_PALETTE} distinct shell block
 * names drops cells past the cap (census max was 44 names).
 *
 * <h2>The biome tint (format 2, the A1/A3 fix)</h2>
 * Grayscale sprites need a colour: {@code block/grass_block_top.png} is
 * PNG colour type 0 (literally grayscale) and {@code block/short_grass.png}
 * is a palette PNG whose entries are all gray. Vanilla multiplies them by
 * a biome tint at build time; the far field cannot, because by the time a
 * shell is MESHED the chunk and its biomes are gone. So the tint is
 * sampled HERE, while the chunk is still live, and travels in the record
 * ({@link ShellCodec}'s tint table).
 *
 * <h2>The biome tint GRID (format 3, the owner's colour-blending items)</h2>
 * <p>Format 2 stored ONE colour per palette entry per chunk, sampled at
 * the first cell that used the entry. The owner's pre7 playtest named both
 * halves of what that costs: "oceans do not mix colour" and "chunks do not
 * mix", i.e. a biome boundary running THROUGH a chunk snaps to the chunk
 * edge and neighbouring chunks cannot blend into each other. Vanilla
 * softens the same boundary over 5 to 11 blocks.</p>
 *
 * <p><b>What replaced it, twice.</b> pre8 through pre18 wrote an
 * {@code N x N} CORNER grid per entry, node
 * {@code (gx, gz)} at world x {@code baseX + gx * 16 / (N - 1)}, read
 * bilinearly by the mesher, with the last row at block offset 16 - the
 * neighbouring chunk's offset 0 - so the two chunks agreed there without
 * exchanging anything. That is a RECONSTRUCTION of a field, and the
 * owner's pre18 S1 report ("just chunks of color that sometimes have a
 * gradient") is what a reconstruction looks like when the thing being
 * reconstructed is cheap enough to store outright.</p>
 *
 * <p><b>pre19 stores it outright: the 16x16 CELL FIELD</b>
 * ({@link #sampleCellField}, {@link Palette#rebuildTints}). Node
 * {@code (x, z)} is {@code ClientLevel.calculateBlockTint} at world
 * {@code (baseX + x, y, baseZ + z)} - where pre20 made y the topmost
 * stored cell of THAT column rather than one height for the whole
 * entry - the same integer vanilla's own
 * renderer multiplies into that block's quad, with the player's
 * {@code biomeBlendRadius} box average already folded in - and the mesher
 * indexes it with no interpolation at all. There is no shared edge row
 * and none is needed: {@code BiomeColors} is a pure function of world
 * position and the biome map, each record stores it for ITS OWN blocks,
 * and neither record holds an opinion about a block the other one owns,
 * so two adjacent records cannot disagree at the seam. No neighbour
 * shell, record or palette is consulted, and the corner grids stay
 * DECODABLE so nothing on disk is rejected.</p>
 *
 * <p><b>Price, and the two things that keep it small.</b> The row is now
 * two-valued in effect: Per Chunk (side 1, format 2's single colour, kept
 * because the owner asked for the flat look) or the cell field. First,
 * the WHITE SHORT CIRCUIT - an entry with no tint source at all is
 * detected on its first sample and costs one blend and one colour,
 * exactly as at format 2, which is most of a palette (stone, dirt, sand,
 * wood). Second, the codec's UNIFORM FLAG - a chunk inside one biome has
 * all 256 samples equal and the record stores one colour plus a flag
 * byte. So the field is paid for only at biome boundaries: raw
 * {@code 1 + 3*256} = 769 bytes per non-uniform tinted entry there,
 * against format 2's 3 B and pre18's 868 B at side 17, and +1 B per
 * untinted entry everywhere. See {@link ShellCodec}'s version-3 section
 * for the arithmetic against the census's 1.05 KB mean record.</p>
 *
 * <p>Game-thread cost is one {@code BiomeColors} blend per grid node of
 * each TINTED entry: typically two or three entries, so 18 to 27 blends
 * per chunk at side 3 where format 2 paid 2 to 3. Against
 * {@link #closeLeaks}'s measured mean of 24.7k block probes per chunk that
 * is noise, and it is bounded by the palette size and not by the cell
 * count.</p>
 *
 * <h2>Plants that stand in water (the owner's "kelp does not render")</h2>
 * <p>The census's exposure rule gave a see-through owner a face only
 * against TRUE AIR, so a kelp stalk - surrounded on all six sides by water,
 * which is not air - came out with mask ZERO and the codec rejected it: the
 * stalk never reached the store at all. The same sentence covered seagrass,
 * tall seagrass, sea pickles, coral fans and any waterlogged plant. pre8
 * patched it by asking {@code SpriteUvResolver} whether the block would be
 * DRAWN as a plant and admitting such a cell with one nominal UP bit.</p>
 *
 * <p><b>That exception is gone and the case is now ordinary.</b>
 * {@link #exposed} asks {@code Block.shouldRenderFace(kelp, water, dir)}:
 * water's face occlusion shape is empty (it does not occlude), kelp is not
 * the same block as water, and kelp's own occlusion shape is empty too, so
 * the method returns true and the stalk owns six real faces. Nothing names
 * kelp, nothing asks the resolver, and the hot cell walk lost an
 * {@code invokestatic} into a class it would rather not load.</p>
 *
 * <p>Water does NOT gain faces around the stalk in exchange, and that is
 * vanilla's doing rather than a second exception:
 * {@code LiquidBlock.skipRendering} (javap ip 0-14) is
 * {@code neighbour.getFluidState().getType().isSame(this.fluid)}, and kelp
 * is a {@code SimpleWaterloggedBlock} whose fluid state IS water, so
 * water-against-kelp is hidden exactly as water-against-water is.</p>
 *
 * <p><b>Price, and it is the one accuracy row that costs GPU rather than
 * disk.</b> Storage is 4 B per admitted cell as usual. Geometry is not:
 * {@code block/kelp} is a cross, four quads, so a kelp forest is the
 * expensive shape. A worked case, 40 kelp columns of 15 blocks in one
 * chunk: 600 extra cells (+2.4 KB raw, roughly +1.4 KB after zlib on a
 * ~1 KB record, so it more than doubles an ocean record) and 2,400 extra
 * quads at 64 B each, i.e. 154 KB of arena for that one chunk against a few
 * hundred quads for the water around it. An ocean with no kelp in it pays
 * NOTHING.</p>
 *
 * <p><b>The Underwater Plants row is therefore a REFUSAL now, not an
 * admission</b> ({@link #standsInFluidOnly}): with it off, a see-through
 * block that is not itself the fluid and has no air on any of its six sides
 * is dropped. That is the same set of cells the pre8 row used to add, named
 * by geometry instead of by asking what something looks like.</p>
 *
 * <h2>THE WATER SURFACE UNDER A PLANT (the owner's pre9 "kelp removes the
 * water surface in lod if its in the top water block")</h2>
 * <p>The rule above stores the kelp. It does not, and cannot, store the
 * WATER at the kelp's own position, and that is the whole defect. A shell
 * holds one block STATE per cell, and where a kelp stalk stands in the
 * topmost water block of its column the block at that position IS
 * {@code minecraft:kelp}: there is no water block there to store. The
 * water below it is worse off still - its UP neighbour is the kelp, which
 * is not air, so {@link #exposed} gives it no UP face and its mask comes
 * out zero. <b>The column therefore contains no fluid cell with an exposed
 * UP face at all</b>, and two things downstream key on exactly that cell:
 * {@code ShellMesher}'s 8/9-height surface sheet (the sea's top surface,
 * so the column draws a 1x1 hole in the sea) and pre9's underwater shading
 * (which finds the column's water surface as "the only UP-exposed fluid
 * cell in the column", so the sea bed under a kelp forest stays lit at
 * full daylight while its neighbours darken).</p>
 *
 * <p><b>The fix is to store the fluid the block is standing in.</b> A
 * stored cell with AIR DIRECTLY ABOVE IT whose own {@code FluidState} is
 * non-empty, and whose block is not that fluid's own block, emits a SECOND
 * cell at the same position: the fluid's legacy block state
 * ({@code FluidState.createLegacyBlock()} - javap:
 * {@code public net.minecraft.world.level.block.state.BlockState
 * createLegacyBlock();}) with a single UP face bit. Two cells may share a
 * position: the codec packs x, yRel, z, palette index and mask per cell and
 * says nothing about uniqueness, and the mesher walks cells independently.
 * That is also exactly what vanilla draws there - a waterlogged block
 * renders its own model AND the fluid's surface - so this is reproduction,
 * not invention. The same sentence covers tall seagrass, sea pickles, coral
 * fans and a waterlogged slab, fence or stair standing at the waterline.</p>
 *
 * <p><b>Price: 4 bytes and no quads.</b> One extra cell per column whose
 * top water block holds something else, so a 40-stalk kelp forest in one
 * chunk pays 160 B raw (well under the +2.4 KB the stalks themselves
 * cost) and an ocean with no kelp in it pays NOTHING - the test needs a
 * non-empty fluid state, which every land cell fails on a field read
 * ({@code BlockStateBase.getFluidState} is {@code getfield fluidState},
 * javap ip 0-4). The quad count goes DOWN, not up: the surface sheet is
 * merged, and a missing cell in the middle of a 16x16 plane splits that
 * one merged quad into three or four, so filling the hole restores the
 * merge it was breaking. Game-thread cost is one field read and one
 * {@code isEmpty} per stored cell that has air above it - the surface
 * layer, about 256 of them per chunk against {@link #closeLeaks}'s
 * measured 24.7k block probes.</p>
 *
 * <h2>The light plane (format 3, the owner's "no smooth lighting")</h2>
 * <p>{@code ShellMesher} synthesizes flat light: sky 15, block 0, on
 * everything. That is why a distant cave mouth is as bright as a hilltop
 * and a distant torch lights nothing. Real light cannot be recovered at
 * mesh time for the same reason biome colour cannot - the chunk is gone -
 * so, like the tint, it is sampled HERE and carried in the record, one
 * packed byte per stored cell ({@link #sampleCellLight}).</p>
 *
 * <p>It is OFF by default and it is the only accuracy row that costs disk
 * per CELL rather than per palette entry: +1 B on a 4 B/cell stream, +25%
 * raw. The codec writes it as a contiguous trailing PLANE rather than a
 * fifth byte inside each cell precisely so that deflate can see it for
 * what it is - a run that is overwhelmingly the single value {@code 0xF0}
 * (sky 15, block 0) - which is what turns +25% raw into single-digit
 * percent on disk. Extraction cost is two light-engine reads per stored
 * cell, about 1.4k reads on the census's mean 703-cell surface-band chunk,
 * against {@link #closeLeaks}'s 24.7k block probes.</p>
 *
 * <p><b>pre12b: the plane is only written when the read is PROVED real.</b>
 * Two gates through pre21. The first was {@code lightReadable}, a cheap
 * pre-test that refused to spend the ~1.4k light reads at all when the
 * engine demonstrably could not answer for this column yet; it belonged
 * to the LIVE wrapper and T2 Phase 3 deleted it with that wrapper. Its
 * job is now done earlier and per SOURCE: a capture is only taken for a
 * column whose record says {@code lightReady} (E3 seen), and the pin's
 * own retirement detector refuses a grab that found only nulls, so a
 * walk never starts against an engine that cannot answer. The second
 * gate remains: {@link #planeVerdict} judges the finished plane and
 * throws it away unless it could only have come from real data. A record
 * therefore carries a light plane only when that plane is real AND says
 * something the mesher could not synthesize for free, and a record with
 * no plane draws exactly what it drew before this row existed. Both
 * report "the column is owed a better plane" through
 * {@code pin.walkLightPending}, which is what stamps the record's
 * LIGHT_PARTIAL quality bit (pre16 M2) so an arrival beside it files the
 * upgrade.</p>
 *
 * <p><b>The pre11/pre12 gate this replaces was inverted, and it shipped
 * black terrain.</b> {@code lightIsPublished} decided "the engine has not
 * published this column" from a sky reading ABOVE zero at the world floor,
 * on the derivation that an unpublished column reads a constant 15. That
 * derivation is right for a column the light engine has never heard of and
 * wrong for the case that actually matters: vanilla hands out an ALL-ZERO
 * placeholder {@code DataLayer} for a column whose neighbour arrived first,
 * and a placeholder reads 0 at the floor -
 * indistinguishable from correctly-lit bedrock. So the old gate passed the
 * state that stores an all-black plane and caught only the state whose
 * plane {@code planeVerdict} would have dropped as free anyway. Measured
 * on the real GPU: scattered whole-chunk BLACK patches across the far band
 * of a superflat world at noon.</p>
 *
 * <p>Sampling needs a {@code BlockAndTintGetter}, which {@code ClientLevel}
 * implements (javap: {@code public class ClientLevel extends Level
 * implements net.minecraft.client.renderer.block.BlockAndTintGetter}). If
 * the level is not one - impossible on the client, kept as a guard - the
 * shell is written with a null tint table and the mesher falls back to
 * out-of-world default colours.</p>
 *
 * <p><b>Zero-cost-off note, do not "clean up" the references.</b> The W4
 * suite asserts {@code SpriteUvResolver} is NOT among the loaded classes
 * when the master switch never armed. This class names it as ONE
 * {@code invokestatic}, {@link SpriteUvResolver#sampleTint}, which HotSpot
 * resolves lazily on first EXECUTION, and that site is reachable only from
 * {@link #extractCaptured}, which only ever executes with the far field
 * armed. The
 * only other reference, {@link SpriteUvResolver#WHITE_RGB}, is a
 * compile-time constant and is inlined into this class file, loading
 * nothing. Turning either into a field read, a {@code Class} literal, or an
 * instance of a resolver type would load the class at extractor-load time
 * and break that leg. pre11 made this leg easier rather than harder: the
 * underwater-plant rule used to call {@code drawsAsPlant}, a second
 * {@code invokestatic} into the resolver from the hot cell walk, and the
 * occlusion rule now answers that question from vanilla's own shapes.</p>
 *
 * <p>Every shell built here is stamped {@link ShellCodec#TIER_VISITED}:
 * a live {@link LevelChunk} on the client IS server-sent reality, the
 * highest truth tier. pre2's save-importer works from NBT, not from
 * LevelChunk, so it never passes through this class.</p>
 *
 * <p><b>Threading</b>: game thread only. Every caller in pre1 sits on a
 * chunk-lifecycle seam, and all chunk receive/forget handling runs on the
 * client main thread (FARFIELD-VANILLA-SEAM.md section 1.1's
 * PacketProcessor proof), so section data is readable with no locking.</p>
 */
public final class ShellExtractor {

    // Face mask bits, vanilla Direction ordinal order == ShellCodec's
    // documented mask convention (its class javadoc: bit 0 down, 1 up,
    // 2 north, 3 south, 4 west, 5 east). The codec serializes the mask
    // byte verbatim and rejects mask 0 — this walk never emits one.
    /** The -Y face bit (Direction ordinal 0). */
    public static final int FACE_DOWN = 1;
    /** +Y face bit (ordinal 1). */
    public static final int FACE_UP = 1 << 1;
    /** -Z face bit (ordinal 2). */
    public static final int FACE_NORTH = 1 << 2;
    /** +Z face bit (ordinal 3). */
    public static final int FACE_SOUTH = 1 << 3;
    /** -X face bit (ordinal 4). */
    public static final int FACE_WEST = 1 << 4;
    /** +X face bit (ordinal 5). */
    public static final int FACE_EAST = 1 << 5;

    /**
     * How far below the chunk's LOWEST heightfield band floor the
     * {@link #closeLeaks} flood may admit, in blocks. This is the bound
     * that stops a column over a huge cavern walking to bedrock.
     *
     * <p>64 is where the census sample stops leaking: on the 300 real
     * chunks, budget 16 left 8 of 300 chunks with reachable open boundary,
     * 32 left 3, and 64 left 0, while the cost between 32 and 64 is 3
     * cells per chunk on average. Cheap insurance on a number that only
     * ever binds inside a large cave system.</p>
     *
     * <h2>What "below" is measured from, and the pre14 P5 defect (fixed
     * 2026-08-23)</h2>
     * <p>Until pre15 this was measured PER COLUMN, from that column's own
     * heightfield floor. That makes the cut-off a horizontal plane at
     * {@code cutTop(column) - bandOffset - 64}, i.e. <b>a plane that rises
     * with whatever happens to stand above the column</b> - a mountain, a
     * mansion roof, a tree canopy. The flood's job is to follow an opening
     * inward from daylight, and how tall the rock above the opening is has
     * nothing to do with how far the flood has travelled. Worked, on the
     * owner's own scene: a cave mouth at the foot of a cliff, valley floor
     * y 64, mountain top y 140. The mouth's own columns (in the cliff face,
     * with the valley as a lateral neighbour) get floor 56 and limit -8, so
     * they are admitted; the column ONE BLOCK further into the mountain has
     * all five cut tops at 140, floor 132 and <b>limit 68</b>, so a tunnel
     * running in at y 66-69 loses its floor and its lower walls from the
     * second block onward. The player sees into the mouth and straight
     * through the bottom of it. Exactly the same arithmetic cuts the bottom
     * of a notch in a tall cliff and the bottom corner where a built
     * structure meets a slope, which is why the owner's three reports all
     * say "the BOTTOM is missing".</p>
     *
     * <p>It is now measured from {@code min} of the chunk's OWN 256 band
     * floors, one scalar for the whole chunk. That minimum already carries
     * the four lateral neighbours' cut tops, because
     * {@link #computeBandFloors} takes the minimum over them for every edge
     * column - so no new input, no new read, and no new parameter. And it
     * is <b>free in memory and in domain depth</b>: {@link LeakFlood}'s
     * {@code lowest} was already
     * {@code min_i max(spanFloor, floors[i] - LEAK_BUDGET)}, which is
     * algebraically {@code max(spanFloor, min_i floors[i] - LEAK_BUDGET)},
     * i.e. the new limit exactly. The {@code seen} bitset, the packed stack
     * entry and the walk's y range are bit-for-bit what they were; the only
     * thing that changes is which reachable position the flood is allowed
     * to ADMIT once it has got there.</p>
     */
    private static final int LEAK_BUDGET = 64;

    /**
     * The same allowance for the CROSS-CHUNK pass, which is seeded from a
     * neighbour's ground rather than from our own sky.
     *
     * <p>32, not {@value #LEAK_BUDGET}, and the difference is worth real
     * money. Measured on the census's 300 chunks with the reachability
     * flood run over the whole 3x3 (see {@link #closeLeaks}'s price
     * section), a cross pass at the full 64 costs +24.1% mean cells, p95
     * 1166 to 1689 and worst chunk 2399 to 3993; at 32 it costs +15.3%,
     * p95 1414 and worst chunk 2417 - and it closes exactly the same set
     * of SHALLOW leaks, 0 of 300 either way. All 64 buys over 32 is deep
     * cave that is flood-connected across a plane but has no straight line
     * to daylight, and it buys it by tripling the worst chunk.</p>
     *
     * <p>32 is also comfortably past what the shape this pass exists for
     * needs: a tunnel crossing a chunk plane 22 blocks below the band floor
     * is the desk-check case, a 16-block allowance leaves it open and 24
     * closes it.</p>
     *
     * <p><b>Measured from the same place {@link #LEAK_BUDGET} now is</b> -
     * the chunk's lowest band floor - so the cross pass stopped being
     * relief-dependent at the same time and for the same reason. The
     * numbers quoted above were taken with the old per-column reference and
     * are therefore an upper bound on the DIFFERENCE between 32 and 64,
     * not on either one's absolute cost; raising this to 64 is still a
     * one-constant change and still unmeasured under the new reference.</p>
     */
    private static final int CROSS_BUDGET = 32;

    /**
     * How many LEVELS, summed over all 256 columns, the two
     * {@link #closeLeaks} passes may admit between them.
     *
     * <p>{@value} is exactly {@code 256 x }{@value #LEAK_BUDGET}, which was
     * the arithmetic ceiling of the per-column budget this pass used until
     * pre15. Rebasing that budget onto the chunk's lowest band floor
     * (see {@link #LEAK_BUDGET}) is what fixes the owner's P5, but on its
     * own it would let a chunk holding both a mountain and a sky-open
     * ravine drag a column up to 255 levels instead of 64. This keeps the
     * TOTAL where it already was, so <b>the worst-case record this pass can
     * produce does not move at all</b>; only where the allowance is spent
     * does.</p>
     *
     * <p>It binds on the same chunks the per-column form bound on - a
     * cavern under most of the footprint - and on nothing else: a 3-wide
     * tunnel descending 100 blocks crosses of the order of 30 columns and
     * spends a few thousand levels. When it does bind, WHICH opening keeps
     * its allowance depends on the walk order, which is depth-first; a
     * breadth-first walk would spend it nearest-the-daylight first and is
     * the obvious next refinement, recorded rather than built.</p>
     */
    private static final int ADMIT_LEVEL_BUDGET = 256 * LEAK_BUDGET;

    /**
     * Positions {@link #closeLeaks} refused to admit because a budget was
     * spent - either the chunk-wide depth plane or
     * {@link #ADMIT_LEVEL_BUDGET}. <b>Every one of these is a hole the
     * player can see through</b>, so this is the direct measurement of the
     * owner's P5 and the only honest way to know whether pre15's rebase was
     * enough. A session that ends with this at zero has no budget-caused
     * hole in anything it saved.
     *
     * <p>Read it beside {@link #farBandDegradedPlanes}: they are the two
     * halves of P5 and they fail in different places, so one of them being
     * zero says nothing about the other.</p>
     *
     * <p>It counts OFFERS refused, not distinct positions: a refusal
     * returns before the {@code seen} mark (it must, because the floor
     * around it may still drop and make the same position admissible), so
     * one stubbornly out-of-budget cell can be counted up to six times.
     * Use it as a zero/non-zero signal and as a trend, not as a cell
     * count.</p>
     *
     * <p><b>It has no reader yet.</b> One line in
     * {@code MesheliumFarFieldVisualTest.counterSnapshot} publishes it, the
     * same way {@code farExtractIncomplete} is published; until that line
     * exists this is as unobservable as those counters were before pre12.
     * </p>
     */
    public static final LongAdder farBandLeakRefusals = new LongAdder();

    /**
     * Chunk PLANES walked with the neighbour on the far side absent, so
     * {@link #computeBandFloors} had to fall back to our own chunk's
     * minimum cut top instead of reading the neighbour's real one. Counted
     * per plane, so a fully degraded extraction adds four.
     *
     * <p>This is the other half of P5 and the one that matches the owner's
     * woodland mansion: the fallback keeps more than it used to but it is
     * still a guess, and a column extracted on a guess only becomes right
     * when it is extracted again. A number that keeps climbing during
     * ordinary flight means degraded extractions are the steady state and
     * not the exception, which would make the sweep's upgrade path - not
     * this file - the thing to fix.</p>
     *
     * <p><b>It has no reader yet</b>, same as
     * {@link #farBandLeakRefusals}.</p>
     */
    public static final LongAdder farBandDegradedPlanes = new LongAdder();

    /**
     * Extractions whose light was refused WHOLE because the extracted
     * chunk itself was no longer (or not yet) the {@code ClientChunkCache}
     * slot's occupant — the home half of the pre16 probe-residency gate.
     *
     * <p>Near-ZERO since M5, by design: the populations that used to land
     * here every flight (drop-seam and retained-drain extractions, whose
     * own chunk was already out of the cache - bytecode:
     * {@code ClientChunkCache$Storage.drop} ip 0-7 CASes the slot to null
     * before {@code ClientLevel.unload} at ip 28-36; {@code replace} is
     * the same shape, ip 6 then ip 40) now extract through the pin
     * capture, whose light is the capture's, not the engine's. The gate
     * stays as the LIVE wrapper's defense-in-depth; nonzero now means a
     * LIVE job raced a replace inside one pump, which is rare and
     * harmless (flat light, pending, upgraded on the re-send's E2).</p>
     *
     * <p><b>It has no reader yet</b>, same as
     * {@link #farBandLeakRefusals}.</p>
     */
    public static final LongAdder farLightHomeNotResident = new LongAdder();

    /*
     * T2 Phase 3 note on the counter above: its ONE producer was the LIVE
     * wrapper's home-residency identity gate, which is deleted with the
     * live walk, so it is now permanently zero. The field is kept rather
     * than removed for the same reason farExtractIdleBoosts was kept at
     * Phase 2 - a deletion that reads as a number beside an older log is
     * worth more than one that reads as a missing key - and because the
     * fact it was checking is now structural: a capture is taken from the
     * cache's current occupant of the slot, so a walk can no longer be
     * handed a chunk the cache has already replaced.
     */

    /**
     * Cells whose EVERY admissible face probe was refused by the lateral
     * half of the pre16 probe-residency gate (each exposed face's probe
     * would land in a chunk absent from the {@code ClientChunkCache}), so
     * the cell stored the flat fallback byte instead of a probe-read one.
     * Counts cells, once each.
     *
     * <p>These are the receive-frontier wall cells: the chunk arrived
     * before its neighbour, {@code exposedLateral} over-included the
     * boundary wall, and pre15 stored the placeholder's 0x00 for it. A
     * plane containing such cells is stored (its interior light is real)
     * but reported pending (through {@code pin.walkLightPending} since
     * Phase 3), so the sweep re-extracts it once with the neighbour in
     * place.</p>
     *
     * <p><b>It has no reader yet</b>, same as
     * {@link #farBandLeakRefusals}.</p>
     */
    public static final LongAdder farLightProbeGateRefusals = new LongAdder();

    /**
     * Cells with no exposed UP face where more than one admissible face
     * competed and the BRIGHTEST one was stored — the population the pre15
     * bit-order rule (DOWN first) mislit. Zero with Real Light off; zero
     * on a superflat suite world (every cell there has an UP face), which
     * is exactly the harness blind spot the pre15 regression shipped
     * through, so read it on real terrain.
     *
     * <p><b>It has no reader yet</b>, same as
     * {@link #farBandLeakRefusals}.</p>
     */
    public static final LongAdder farLightBrightestFacePicks = new LongAdder();

    private ShellExtractor() {}

    // ------------------------------------------------------------------
    // M5: the two source interfaces (FARFIELD-SAVE-DESIGN.md section 5).
    // The walk below reads its world through these and ONLY these, which
    // is what lets the same code run on the game thread against the live
    // cache (LIVE implementations, built by extract()) and on the pin
    // worker against a capture (CAPTURED implementations, Pin.java) -
    // and what enforces the LightView contract: a probe into a source
    // that is neither published-LIVE nor captured yields UNKNOWN, and
    // UNKNOWN never stores a byte.
    // ------------------------------------------------------------------

    /** Lateral side index: west (x-1). Shared by both views and the pin. */
    static final int SIDE_W = 0;
    /** East (x+1). */
    static final int SIDE_E = 1;
    /** North (z-1). */
    static final int SIDE_N = 2;
    /** South (z+1). */
    static final int SIDE_S = 3;
    /** Chunk-x delta per side index. */
    static final int[] SIDE_DX = {-1, 1, 0, 0};
    /** Chunk-z delta per side index. */
    static final int[] SIDE_DZ = {0, 0, -1, 1};
    /** Diagonal corner index: NW, NE, SW, SE (the flood-domain order). */
    static final int[] CORNER_DX = {-1, 1, -1, 1};
    /** See {@link #CORNER_DX}. */
    static final int[] CORNER_DZ = {-1, -1, 1, 1};

    /**
     * The GEOMETRY source: who is on the far side of each chunk plane.
     * Exactly one of three answers per lateral side, the design's own
     * taxonomy: a readable {@code ChunkAccess} ({@code lateral()}
     * non-null - the live cache's occupant, or a pinned neighbour's
     * captured chunk), a border STRIP ({@code strip()} non-null - the
     * neighbour stayed live and the worker must not read it, so its
     * facing row was captured at pin time), or UNKNOWN (both null - the
     * old absent-neighbour policy applies and the side sets its quality
     * reason bit). Diagonals are flood conduits only and are never
     * strip-captured: null simply means the flood cannot cross there.
     */
    interface NeighborView {
        /** The lateral neighbour's chunk, or null (strip or unknown). */
        ChunkAccess lateral(int side);

        /** The diagonal neighbour's chunk (flood conduit), or null. */
        ChunkAccess diagonal(int corner);

        /** The captured border strip, non-null iff this side is one. */
        CapturedStrip strip(int side);
    }

    /**
     * The LIGHT source, per probe. {@code skyBlock} answers the packed
     * light byte at a world position, or {@code -1} for UNKNOWN - and
     * the contract that closes the pre16 lighting family is exactly
     * that -1: <b>an UNKNOWN probe never stores a byte</b>, neither the
     * dead neighbour's 0x00 (the drop-seam curtain), nor the retired
     * world's 0xF0 (the drainRetained fake-noon), nor a resident
     * neighbour's still-queued all-zero placeholder (the arm Phase 0
     * left open and {@code planeVerdict} could not see, because it
     * judges one plane while the probes read up to five chunks).
     * {@code sideAdmissible} is the per-SOURCE half: false when the
     * chunk a boundary probe would land in has no published-or-captured
     * light, so the face is stripped from the admissible mask before
     * any probe runs - Phase 0's residency gate semantics, kept as the
     * floor and widened from "resident in the cache" to "resident AND
     * its light is published" (the LIVE view reads the record's
     * {@code lightReady}; the CAPTURED view reads the pin).
     */
    interface LightView {
        /** May a probe cross this chunk plane at all? */
        boolean sideAdmissible(int side);

        /**
         * The packed light byte {@code (sky << 4) | block} at a world
         * position, or -1 when this source cannot honestly answer.
         */
        int skyBlock(int wx, int y, int wz);
    }

    /**
     * One captured border strip: what a pinned chunk's walk may know
     * about a lateral neighbour that STAYED live (the worker must never
     * read a live chunk off-thread, so its facing row is captured on
     * the game thread at pin-finish time, while the neighbour is still
     * in the cache). Per the design: the edge heightmap row (feeds the
     * band floors exactly as a live neighbour's {@code edgeCutTops}
     * would), and per band row an occlusion bit, a fluid family and a
     * half-transparent block token - roughly 2 KB a side, band-bounded
     * (plus the water depth over a fluid seam, pre18's R2 span).
     *
     * <p>Out-of-range rows are policy, not data, and both directions
     * are chosen conservatively: above the captured span is AIR (above
     * the neighbour's cut tops by construction), below it is ROCK
     * (below both sides' band floors less the leak allowance, where a
     * face would sit against unmined stone; a cave crossing the plane
     * deeper than {@code LEAK_BUDGET} below the floors loses its
     * boundary face - the same admission the flood itself makes).</p>
     */
    static final class CapturedStrip {
        /** The neighbour's facing-row cut tops, index 0..15. */
        final int[] edgeCutTops;
        /** Absolute y of slot 0. */
        final int yLo;
        /** Slots per row. */
        final int rows;
        /**
         * Per (row, slot): bit 0 = full occluder
         * ({@code BlockState.isSolidRender()}, the cached full-cube
         * occlusion answer), bits 1-2 = fluid family
         * ({@link #fluidFamily}), bits 3-5 = 1-based index into
         * {@link #transparents} for a {@code HalfTransparentBlock}
         * (0 = none or the palette overflowed). Row-major.
         */
        final byte[] cells;
        /**
         * The distinct {@code HalfTransparentBlock}s of this strip, for
         * vanilla's same-block hide rule across the pinned plane
         * ({@code HalfTransparentBlock.skipRendering} is
         * {@code neighbour.is(this)} - its only branch). Without it a
         * frozen seam's home ICE face read the neighbour's ice as
         * "family 0, not solid" and emitted: the owner's P6 "you can see
         * like the ice through the ice, the outside edge of it" -
         * explained and closed by the same R2 pass that fixed the water
         * wall. An eighth distinct half-transparent block in one 16-row
         * strip overflows to token 0 and merely re-emits the face, the
         * cheap direction.
         */
        final Block[] transparents;

        CapturedStrip(int[] edgeCutTops, int yLo, int rows, byte[] cells,
                Block[] transparents) {
            this.edgeCutTops = edgeCutTops;
            this.yLo = yLo;
            this.rows = rows;
            this.cells = cells;
            this.transparents = transparents;
        }
    }

    /**
     * Fluid identity classes for the strip's hide-same-fluid rule -
     * the captured stand-in for {@code LiquidBlock.skipRendering}'s
     * {@code getFluidState().getType().isSame(fluid)} (javap ip 0-14),
     * which is what keeps an ocean from growing a curtain of interior
     * water faces on a pinned chunk plane. 0 none, 1 water family,
     * 2 lava family, 3 anything modded.
     */
    static int fluidFamily(FluidState fluid) {
        if (fluid.isEmpty()) {
            return 0;
        }
        if (fluid.getType().isSame(Fluids.WATER)) {
            return 1;
        }
        if (fluid.getType().isSame(Fluids.LAVA)) {
            return 2;
        }
        return 3;
    }

    /**
     * Capture one border strip from a still-cached neighbour, on the
     * game thread. {@code side} is the side of the HOME chunk the
     * neighbour sits on; the strip reads the neighbour's facing row
     * (west side = the neighbour's x=15 row, and so on - the same
     * fixed coordinates {@code extract}'s own {@code edgeCutTops}
     * calls use).
     *
     * <p>The span: from {@code LEAK_BUDGET} plus the band offset below
     * the lower of the two facing rows' lowest cut top (so every face
     * the home walk or its leak flood can emit at this plane has a
     * real answer), up to one block above the neighbour's highest
     * WORLD_SURFACE - the heightmap whose own definition is "everything
     * above me is air" - so that {@code exposedAgainstStrip}'s
     * above-the-span arm ("open air over there") is TRUE for every
     * column. 16 rows x that span at one byte per level - the design's
     * ~2 KB per side, plus the water depth over a fluid seam.</p>
     *
     * <h2>Why the top is WORLD_SURFACE and not the cut top (the owner's
     * pre17 R2, "walls inbetween the chunks that stay when i backup")</h2>
     * <p>The first build of this took the span's top from the same
     * {@code edgeCutTops} the flood seeding uses, and {@code cutTopAt}
     * RE-CUTS a fluid-topped column from OCEAN_FLOOR - the bed. Over an
     * ocean seam the strip therefore ended at the sea BED plus one, and
     * every water cell on the seam plane between bed+2 and the surface
     * fell into {@code exposedAgainstStrip}'s "above the captured span:
     * open air over there" arm and UN-CULLED its lateral face - the face
     * vanilla hides on the fluid type alone ({@code FluidRenderer
     * .isNeighborSameFluid}, javap ip 0-11, compares {@code getType()}
     * and nothing else). The result was a stored, full-height translucent
     * water curtain standing exactly on the chunk plane, bed to surface,
     * 16 columns wide - and it was PERSISTENT, because this path runs at
     * the drop seam (the pin's CAPTURED sides, i.e. precisely the
     * LOD/near transition ring the owner flies along), so the wall was in
     * the record and re-drawn every time the far field showed that chunk
     * again. The absent-neighbour policy cannot produce it
     * ({@code exposedLateral} answers {@code canOcclude()} there, and
     * water's registration goes through {@code Properties.noCollision()},
     * which clears {@code canOcclude} - javap ip 0-11 of that method,
     * {@code putfield hasCollision false; putfield canOcclude false});
     * only the strip's too-short span could.</p>
     */
    static CapturedStrip captureStrip(LevelChunk home, ChunkAccess neighbor,
            int side, int bandOffset) {
        int minSectionY = home.getMinSectionY();
        int worldMinY = home.getMinY();
        int worldMaxY = home.getMaxY();
        // The neighbour's facing row and our own facing row, as cut tops.
        int[] tops;
        int[] ours;
        switch (side) {
            case SIDE_W -> {
                tops = edgeCutTops(neighbor, minSectionY, worldMinY, 15, -1);
                ours = edgeCutTops(home, minSectionY, worldMinY, 0, -1);
            }
            case SIDE_E -> {
                tops = edgeCutTops(neighbor, minSectionY, worldMinY, 0, -1);
                ours = edgeCutTops(home, minSectionY, worldMinY, 15, -1);
            }
            case SIDE_N -> {
                tops = edgeCutTops(neighbor, minSectionY, worldMinY, -1, 15);
                ours = edgeCutTops(home, minSectionY, worldMinY, -1, 0);
            }
            default -> {
                tops = edgeCutTops(neighbor, minSectionY, worldMinY, -1, 0);
                ours = edgeCutTops(home, minSectionY, worldMinY, -1, 15);
            }
        }
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int i = 0; i < 16; i++) {
            lowest = Math.min(lowest, Math.min(tops[i], ours[i]));
            // The span's TOP comes from WORLD_SURFACE, not the cut top:
            // cutTopAt re-cuts a fluid-topped column to its bed, and a
            // span ending at the bed turns the whole water column above
            // it into "open air over there" - the R2 water wall. See the
            // javadoc above.
            int nx = switch (side) {
                case SIDE_W -> 15;
                case SIDE_E -> 0;
                default -> i;
            };
            int nz = switch (side) {
                case SIDE_N -> 15;
                case SIDE_S -> 0;
                default -> i;
            };
            highest = Math.max(highest,
                    neighbor.getHeight(Heightmap.Types.WORLD_SURFACE, nx, nz));
        }
        int yLo = Math.max(worldMinY, lowest - bandOffset - LEAK_BUDGET);
        int yHi = Math.min(worldMaxY, highest + 1);
        if (yHi < yLo) {
            yHi = yLo; // an all-air facing row: one vacuous slot
        }
        int rows = yHi - yLo + 1;
        byte[] cells = new byte[16 * rows];
        Block[] transparents = new Block[7];
        int transparentCount = 0;
        LevelChunkSection[] sections = neighbor.getSections();
        for (int i = 0; i < 16; i++) {
            int nx = switch (side) {
                case SIDE_W -> 15;
                case SIDE_E -> 0;
                default -> i;
            };
            int nz = switch (side) {
                case SIDE_N -> 15;
                case SIDE_S -> 0;
                default -> i;
            };
            for (int y = yLo; y <= yHi; y++) {
                BlockState state = stateAt(sections, minSectionY, nx, y, nz);
                if (state == null || state.isAir()) {
                    continue; // zero byte: not occluding, no fluid
                }
                int b = state.isSolidRender() ? 1 : 0;
                b |= fluidFamily(state.getFluidState()) << 1;
                // Bits 3-5: which HalfTransparentBlock, for the same-block
                // hide rule across the plane (the P6 ice seam; see
                // CapturedStrip.transparents). 1-based; 0 = none/overflow.
                if (state.getBlock() instanceof HalfTransparentBlock) {
                    Block block = state.getBlock();
                    int token = 0;
                    for (int t = 0; t < transparentCount; t++) {
                        if (transparents[t] == block) {
                            token = t + 1;
                            break;
                        }
                    }
                    if (token == 0 && transparentCount < transparents.length) {
                        transparents[transparentCount++] = block;
                        token = transparentCount;
                    }
                    b |= token << 3;
                }
                cells[i * rows + (y - yLo)] = (byte) b;
            }
        }
        return new CapturedStrip(tops, yLo, rows, cells, transparents);
    }

    /**
     * Exposure across a chunk plane whose far side is a captured strip.
     * The strip cannot run {@code Block.shouldRenderFace}'s shape
     * subtraction (it has one byte, not a {@code BlockState}), so the
     * rule is the conservative projection of it: a full occluder hides
     * the face; the same fluid family hides a fluid face (the ocean-plane
     * rule); the same half-transparent BLOCK hides its own face (the
     * frozen-seam rule, pre18); everything else shows it. What that
     * over-emits relative to the live rule is a redundant quad against a
     * PARTIAL occluder - the direction {@code exposedLateral} already
     * documents as the cheap way to be wrong.
     */
    private static boolean exposedAgainstStrip(BlockState owner,
            CapturedStrip strip, int row, int y) {
        int slot = y - strip.yLo;
        if (slot >= strip.rows) {
            // Above the captured span: open air over there. TRUE only
            // because captureStrip spans to the neighbour's WORLD_SURFACE
            // - the heightmap that counts water and kelp - and not to the
            // fluid-re-cut top. With the shorter span this arm un-culled
            // the whole water column at every pinned ocean seam: the
            // owner's pre17 R2 wall. See captureStrip's javadoc.
            return true;
        }
        if (slot < 0) {
            return false; // below both band floors: unmined rock
        }
        int b = strip.cells[row * strip.rows + slot];
        if ((b & 1) != 0) {
            return false; // full occluder across the plane
        }
        // Vanilla's same-block hide for half-transparent blocks
        // (HalfTransparentBlock.skipRendering is neighbour.is(this), its
        // only branch): a frozen seam's ice-against-ice face is culled
        // exactly as the in-record exposed() deviation culls it. Without
        // this token the byte read ice as "not solid, no fluid" and the
        // face survived - the owner's P6 "ice through the ice" seam.
        int token = (b >> 3) & 7;
        if (token != 0 && strip.transparents[token - 1] == owner.getBlock()) {
            return false;
        }
        int family = (b >> 1) & 3;
        return family == 0 || family != fluidFamily(owner.getFluidState());
    }

    /**
     * Everything one walk reads and writes that depends on WHERE the
     * data comes from - the bundle both entry points fill. Per-call
     * state on purpose: nothing the walk touches may be static, which
     * since Phase 3 is a property of the whole class rather than a rule
     * with one exception - the game-thread {@code lightVerdict*} memo
     * that used to sit beside it died with the LIVE wrapper that wrote
     * it, and the verdict now travels on {@code pin.walkLightPending}.
     */
    static final class WalkRequest {
        LevelChunk chunk;
        NeighborView neighbors;
        LightView light;
        /** Tint source; null = no sampled colour (mesher default). */
        BlockAndTintGetter tint;
        boolean hasSkyLight;
        boolean surfaceBand;
        int bandOffset;
        /** Colour Blending divisor (0 = single tint node). */
        int blendDivisor;
        boolean keepUnderwaterPlants;
        /** Real Light wanted AND the home light is readable. */
        boolean realLight;
        /**
         * OUT: this walk's plane was refused or padded - owed better.
         * Travels back on {@code pin.walkLightPending}; since Phase 3
         * that is the only channel, the game-thread memo having died
         * with the LIVE wrapper that wrote it.
         */
        boolean lightPendingOut;
    }

    /*
     * T2 Phase 3 DELETED the LIVE entry point and both LIVE source
     * implementations that stood here: extract(LevelChunk, boolean, int),
     * the LiveNeighbors record (the client chunk cache as a NeighborView)
     * and the LiveLight class (ClientLevel.getBrightness as a LightView),
     * along with the lightVerdict memo they wrote and the lightReadable
     * pre-test they ran.
     *
     * There is now exactly ONE walk entry point - extractCaptured(Pin) -
     * and exactly one pair of source implementations, the captured ones
     * in Pin. The game thread FREEZES a column into a ColumnSnapshot and
     * the worker walks it, whether the column was dropped or is still
     * live (docs/FARFIELD-PERF-BRIEF.md section 2, "unify LIVE onto
     * Pin"). The measurement that forced it: the far-armed bench put this
     * walk at p50 6.816 ms and p99 20.972 ms on a travel leg, against a
     * 5.0 ms frame - an atom no budget rule can schedule.
     *
     * Two behaviours went with the code, and both are improvements the
     * unification hands over rather than changes made on purpose:
     *
     *  - LIGHT. The live path read ClientLevel.getBrightness, i.e. the
     *    engine's visible maps at walk time. The captured path reads the
     *    DataLayer refs grabbed at capture time and reproduces the
     *    engine's own read semantics from them (Pin's skyBlock, javap'd
     *    against SkyLightSectionStorage/BlockLightSectionStorage at M5),
     *    under the UNKNOWN-never-stores contract. Same numbers where both
     *    could answer; the captured one additionally REFUSES where the
     *    live one would have fabricated.
     *
     *  - TINT. The live path resolved every node through
     *    ClientLevel.getBlockTint, which is a per-chunk BlockTintCache
     *    that ClientLevel.onChunkLoaded INVALIDATES for each arriving
     *    chunk (javap: tintCaches.forEach -> BlockTintCache
     *    .invalidateForChunk(x, z)) - precisely the columns a travel leg
     *    is extracting, which is the likeliest single reason a travel
     *    walk measured 6.8 ms against a settled walk's 0.7 ms. The
     *    captured path resolves through Pin's own summed-area table over
     *    captured biomes, which no arrival can invalidate.
     */

    /**
     * The CAPTURED entry point: one pinned column's walk, on the pin
     * worker (or the IO thread's level-swap drain). Reads ONLY the
     * capture - the pin's own chunk, its neighbours through the pin
     * graph or their strips, its light through the captured layer refs -
     * and per-call state; the game-thread memo above is never touched
     * (the verdict lands on {@code pin.walkLightPending} instead).
     */
    static ShellCodec.Shell extractCaptured(Pin pin) {
        // Phase 3's one structural tripwire. This is now the ONLY walk
        // entry point in the mod, so "is the game thread walking again?"
        // reduces to "did a walk run on a thread other than the pin
        // worker?" - one reference compare and, in the failing case, one
        // string compare, against a call that costs milliseconds. The
        // suite asserts the counter at zero; without it a regression that
        // put a walk back on the frame's thread would show up only as a
        // frame-time number somebody has to notice.
        if (!PinWorker.WORKER_THREAD_NAME.equals(Thread.currentThread().getName())) {
            ExtractDispatch.farWalksOffWorker.increment();
        }
        WalkRequest req = new WalkRequest();
        req.chunk = pin.chunk;
        req.surfaceBand = pin.surfaceBand;
        req.bandOffset = pin.bandOffset;
        req.tint = pin.tintView();
        req.hasSkyLight = pin.hasSkyLight;
        req.neighbors = pin;
        req.light = pin;
        req.blendDivisor = pin.blendDivisor;
        req.keepUnderwaterPlants = pin.keepUnderwaterPlants;
        // The home half of the gate, captured form: no plane at all when
        // the pin's own light was never published or never captured -
        // geometry with flat light, quality LIGHT_PARTIAL, upgradeable on
        // revisit. UNKNOWN never stores.
        req.realLight = pin.realLight && pin.ownLightReady();
        req.lightPendingOut = pin.realLight && !pin.ownLightReady();
        ShellCodec.Shell shell = walk(req);
        pin.walkLightPending = req.lightPendingOut;
        return shell;
    }

    /**
     * The one walk body both entry points share. Every world read goes
     * through {@code req}'s views; everything it writes is per-call.
     */
    private static ShellCodec.Shell walk(WalkRequest req) {
        LevelChunk chunk = req.chunk;
        boolean surfaceBand = req.surfaceBand;
        int bandOffset = req.bandOffset;
        // javap (26.2 merged jar): ChunkAccess:
        //   public net.minecraft.world.level.chunk.LevelChunkSection[] getSections();
        //   public net.minecraft.world.level.ChunkPos getPos();
        // LevelHeightAccessor (defaults): getMinY(), getMaxY(), getMinSectionY().
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getMinSectionY();
        int worldMinY = chunk.getMinY();
        int worldMaxY = chunk.getMaxY(); // inclusive
        ChunkPos pos = chunk.getPos();
        BlockAndTintGetter tintLevel = req.tint;
        int baseX = pos.getMinBlockX(); // javap: public int getMinBlockX();
        int baseZ = pos.getMinBlockZ(); // javap: public int getMinBlockZ();
        // The lateral GEOMETRY sources, fetched once. A null chunk with a
        // null strip is UNKNOWN - the documented over-inclusion policy
        // applies and the side's quality reason bit is set by the caller.
        NeighborView neighbors = req.neighbors;
        ChunkAccess westChunk = neighbors.lateral(SIDE_W);
        ChunkAccess eastChunk = neighbors.lateral(SIDE_E);
        ChunkAccess northChunk = neighbors.lateral(SIDE_N);
        ChunkAccess southChunk = neighbors.lateral(SIDE_S);
        CapturedStrip westStrip = westChunk == null ? neighbors.strip(SIDE_W) : null;
        CapturedStrip eastStrip = eastChunk == null ? neighbors.strip(SIDE_E) : null;
        CapturedStrip northStrip = northChunk == null ? neighbors.strip(SIDE_N) : null;
        CapturedStrip southStrip = southChunk == null ? neighbors.strip(SIDE_S) : null;
        LevelChunkSection[] west = westChunk == null ? null : westChunk.getSections();
        LevelChunkSection[] east = eastChunk == null ? null : eastChunk.getSections();
        LevelChunkSection[] north = northChunk == null ? null : northChunk.getSections();
        LevelChunkSection[] south = southChunk == null ? null : southChunk.getSections();
        // PROBE-RESIDENCY GATE, lateral half - per SOURCE since M5 (the
        // LightView contract): a light probe may cross a chunk plane only
        // into a source whose light is published-LIVE or captured. The
        // view answered once per side; the walk is synchronous on its one
        // thread, so the answers cannot go stale mid-extraction, and a
        // probe only ever moves one block along one axis, so the home
        // chunk plus the four laterals are the only chunks a probe can
        // land in - the diagonals are unreachable by construction.
        LightView light = req.light;
        boolean northLightOk = light.sideAdmissible(SIDE_N);
        boolean southLightOk = light.sideAdmissible(SIDE_S);
        boolean westLightOk = light.sideAdmissible(SIDE_W);
        boolean eastLightOk = light.sideAdmissible(SIDE_E);

        // Per-column lowest kept y, ABSOLUTE (replaces cutTop[] and the
        // repeated "- bandOffset" at every use site).
        int[] bandFloor = null;
        int minCut = Integer.MIN_VALUE;
        if (surfaceBand) {
            // The neighbours' facing edge rows, fetched once and used by
            // BOTH floor stages: the heightfield minimum needs their cut
            // tops, and the leak flood needs them to know which air on a
            // chunk plane is open sky on the far side. Null means that
            // neighbour is UNKNOWN - a CAPTURED side answers with the
            // strip's edge row, which is the same 16 cut tops a live
            // neighbour would have yielded, so the band floors of a
            // pinned column keep their apron depth at a stayed-live
            // plane (the design's whole-quality storm saves).
            int[] westEdge = westChunk != null
                    ? edgeCutTops(westChunk, minSectionY, worldMinY, 15, -1)
                    : westStrip != null ? westStrip.edgeCutTops : null;
            int[] eastEdge = eastChunk != null
                    ? edgeCutTops(eastChunk, minSectionY, worldMinY, 0, -1)
                    : eastStrip != null ? eastStrip.edgeCutTops : null;
            int[] northEdge = northChunk != null
                    ? edgeCutTops(northChunk, minSectionY, worldMinY, -1, 15)
                    : northStrip != null ? northStrip.edgeCutTops : null;
            int[] southEdge = southChunk != null
                    ? edgeCutTops(southChunk, minSectionY, worldMinY, -1, 0)
                    : southStrip != null ? southStrip.edgeCutTops : null;
            // The DOMAIN of the leak flood, indexed as a 3x3 neighbourhood
            // ((dz + 1) * 3 + (dx + 1), so the centre is slot 4): our chunk,
            // plus every loaded neighbour as a CONDUIT the flood may travel
            // through but never store into (see closeLeaks). A null slot is
            // simply absent - a strip side is null here too (the strip has
            // no interior to conduct through; the flood just cannot cross
            // that plane, exactly as for an unloaded neighbour). Their
            // per-column WORLD_SURFACE caps the conduit so the flood never
            // walks a neighbour's empty sky.
            ChunkAccess nwChunk = neighbors.diagonal(0);
            ChunkAccess neChunk = neighbors.diagonal(1);
            ChunkAccess swChunk = neighbors.diagonal(2);
            ChunkAccess seChunk = neighbors.diagonal(3);
            LevelChunkSection[][] domain = {
                nwChunk == null ? null : nwChunk.getSections(), north,
                neChunk == null ? null : neChunk.getSections(),
                west, sections, east,
                swChunk == null ? null : swChunk.getSections(), south,
                seChunk == null ? null : seChunk.getSections(),
            };
            int[][] domainCap = {
                conduitCaps(nwChunk, worldMinY), conduitCaps(northChunk, worldMinY),
                conduitCaps(neChunk, worldMinY),
                conduitCaps(westChunk, worldMinY), null,
                conduitCaps(eastChunk, worldMinY),
                conduitCaps(swChunk, worldMinY), conduitCaps(southChunk, worldMinY),
                conduitCaps(seChunk, worldMinY),
            };
            // Our own highest WORLD_SURFACE, wanted twice: surfaceMax + 1 is
            // the all-air seed plane, and surfaceMax - 255 is the span guard
            // that keeps every floor EITHER stage produces encodable. Both
            // stages take it as an argument so there is one definition.
            int surfaceMax = worldMinY - 1;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    surfaceMax = Math.max(surfaceMax,
                            chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
                }
            }
            int spanFloor = Math.max(worldMinY, surfaceMax - 255);
            bandFloor = computeBandFloors(chunk, sections, minSectionY, worldMinY,
                    spanFloor, bandOffset, westEdge, eastEdge, northEdge, southEdge);
            closeLeaks(sections, minSectionY, worldMinY, worldMaxY, surfaceMax,
                    spanFloor, bandFloor, westEdge, eastEdge, northEdge, southEdge,
                    domain, domainCap);
            minCut = Integer.MAX_VALUE;
            for (int i = 0; i < 256; i++) {
                minCut = Math.min(minCut, bandFloor[i]);
            }
        }

        // Provisional cells: absolute y in the high 32 bits (the walk is
        // bottom-up so y is nondecreasing — first entry pins minY, last
        // pins maxY), the finished cell int MINUS its y-rel bits in the
        // low 32. The palette is keyed by block STATE (format 4) and is
        // built once per new state, never per cell; see Palette below.
        LongArrayList raw = new LongArrayList(1024);
        Palette pal = new Palette(SpriteUvResolver.stateStorageAvailable());
        // Extraction-time settings, from the bundle (the LIVE wrapper read
        // them once; the captured path snapshotted them into the pin at
        // capture time, so a settings flip mid-drain cannot tear a walk).
        int blendDivisor = req.blendDivisor;
        // S1 (pre19): the Colour Blending row is now two-valued in effect
        // - Per Chunk (one colour per palette entry, the cheap flat look
        // the owner explicitly asked to keep) or the CELL FIELD, which is
        // vanilla's own per-block answer and has no coarser step left to
        // offer. The old divisor values 2 (Coarse) and 4 (Fine) both land
        // on the cell field; normalizeBlend folds 4 onto 2 so the save
        // signature cannot move between two settings that produce the
        // same bytes.
        int tintSide = blendDivisor <= 0
                ? ShellCodec.TINT_SIDE_SINGLE : ShellCodec.TINT_SIDE_CELL;
        // The walk itself samples ONE SEED colour per entry (the chunk
        // origin's, at the entry's mint y); rebuildTints below throws it
        // away and rebuilds the whole table at the final side and at the
        // per-column heights the finished cells name, so the per-block
        // sampling is done once, not twice, and never at a mint y.
        boolean keepUnderwaterPlants = req.keepUnderwaterPlants;
        // The home half of the light decision was made by the entry
        // point (the pin's ownLightReady) - lightUntrusted starts from
        // ITS verdict, so a refused home still reports the column
        // pending.
        boolean realLight = req.realLight;
        boolean lightUntrusted = req.lightPendingOut;
        // One packed light byte per emitted cell, in the SAME order as
        // raw, so the top-clamp below can slice both with one offset.
        ByteArrayList rawLight = realLight ? new ByteArrayList(1024) : null;
        // Set when any cell stored the flat FALLBACK byte because the gate
        // refused its every probe; such a plane is never FINAL and is
        // reported pending so the sweep upgrades it once the neighbour is
        // in place. See planeVerdict.
        boolean anyProbeRefused = false;
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection section = sections[s];
            // javap: public boolean hasOnlyAir(); — the brief's section
            // emptiness fast path. LevelChunk's own getBlockState does the
            // same two checks (bytecode ip 96-110), no null entries in
            // practice; the null check is belt and braces.
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            int bottom = (minSectionY + s) << 4;
            if (bandFloor != null && bottom + 15 < minCut) {
                continue; // whole section below every column's band
            }
            for (int ly = 0; ly < 16; ly++) {
                int y = bottom + ly;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        // Band test FIRST: an int compare against this
                        // column's floor, ahead of the container read.
                        // With the floor now reaching a neighbouring
                        // ravine or sea bed, a chunk spans more sections
                        // than its own band, and this ordering keeps
                        // those sections costing an array compare per
                        // cell instead of a state fetch per cell.
                        if (bandFloor != null && y < bandFloor[(z << 4) | x]) {
                            continue; // below this column's band
                        }
                        // javap: public net.minecraft.world.level.block.state.BlockState
                        //   getBlockState(int, int, int);
                        // argument order (x, y, z), proven by LevelChunk.getBlockState's
                        // call site: (x & 15, y & 15, z & 15), bytecode ip 113-128.
                        BlockState state = section.getBlockState(x, ly, z);
                        if (state.isAir()) {
                            continue;
                        }
                        // The six neighbours, fetched once. They were
                        // fetched inline per direction until pre11; the
                        // occlusion rule now asks two questions of each
                        // (does it hide the face, and is it air) and the
                        // underwater-plant row asks a third, so one local
                        // apiece is both cheaper and the only way those
                        // three answers cannot drift apart. A null is
                        // air-like: an empty section, past the world top,
                        // or a lateral chunk that is not loaded - and the
                        // last of those is told apart by the *Known flags,
                        // because unknown is not the same as air.
                        BlockState below = y > worldMinY
                                ? stateAt(sections, minSectionY, x, y - 1, z) : null;
                        BlockState above = y >= worldMaxY
                                ? null : stateAt(sections, minSectionY, x, y + 1, z);
                        boolean northKnown = z > 0 || north != null;
                        boolean southKnown = z < 15 || south != null;
                        boolean westKnown = x > 0 || west != null;
                        boolean eastKnown = x < 15 || east != null;
                        BlockState nNorth = z > 0
                                ? stateAt(sections, minSectionY, x, y, z - 1)
                                : stateAt(north, minSectionY, x, y, 15);
                        BlockState nSouth = z < 15
                                ? stateAt(sections, minSectionY, x, y, z + 1)
                                : stateAt(south, minSectionY, x, y, 0);
                        BlockState nWest = x > 0
                                ? stateAt(sections, minSectionY, x - 1, y, z)
                                : stateAt(west, minSectionY, 15, y, z);
                        BlockState nEast = x < 15
                                ? stateAt(sections, minSectionY, x + 1, y, z)
                                : stateAt(east, minSectionY, 0, y, z);
                        boolean airAbove = above == null || above.isAir();
                        int mask = 0;
                        // DOWN: below the world bottom is VOID, not air, and
                        // owns no face - the census rule, and what makes a
                        // superflat validation chunk emit exactly 256 top
                        // faces.
                        if (y > worldMinY && exposed(state, Direction.DOWN, below)) {
                            mask |= FACE_DOWN;
                        }
                        // UP: above the world top is AIR (exposed() reads a
                        // null neighbour as air, so the world-top case needs
                        // no second branch).
                        if (exposed(state, Direction.UP, above)) {
                            mask |= FACE_UP;
                        }
                        // A boundary cell against a CAPTURED side asks the
                        // strip (occlusion bit + fluid family) instead of
                        // the unknown-neighbour policy: better data than
                        // absent, read without touching a live chunk.
                        if (z == 0 && north == null && northStrip != null
                                ? exposedAgainstStrip(state, northStrip, x, y)
                                : exposedLateral(state, Direction.NORTH, nNorth, northKnown)) {
                            mask |= FACE_NORTH;
                        }
                        if (z == 15 && south == null && southStrip != null
                                ? exposedAgainstStrip(state, southStrip, x, y)
                                : exposedLateral(state, Direction.SOUTH, nSouth, southKnown)) {
                            mask |= FACE_SOUTH;
                        }
                        if (x == 0 && west == null && westStrip != null
                                ? exposedAgainstStrip(state, westStrip, z, y)
                                : exposedLateral(state, Direction.WEST, nWest, westKnown)) {
                            mask |= FACE_WEST;
                        }
                        if (x == 15 && east == null && eastStrip != null
                                ? exposedAgainstStrip(state, eastStrip, z, y)
                                : exposedLateral(state, Direction.EAST, nEast, eastKnown)) {
                            mask |= FACE_EAST;
                        }
                        if (mask == 0) {
                            continue; // nothing of this block can be seen
                        }
                        if (!keepUnderwaterPlants
                                && standsInFluidOnly(state, below, above,
                                        nNorth, nSouth, nWest, nEast)) {
                            // The Underwater Plants row, turned OFF. See its
                            // section in the class javadoc: the rule above
                            // now stores a kelp stalk because vanilla would
                            // draw it, and this is what declining to pay for
                            // it costs and means.
                            continue;
                        }
                        int pi = pal.indexOf(state, tintLevel,
                                baseX, y, baseZ);
                        if (pi < 0) {
                            continue; // 1-byte index cap; census max 44
                        }
                        // Absolute y rides the high 32 bits until minY is
                        // known; the low 32 are the codec's cell with
                        // yRel 0 (packCell is the single format truth).
                        raw.add(((long) y << 32)
                                | (long) ShellCodec.packCell(x, 0, z, pi, mask));
                        if (rawLight != null) {
                            // The lateral gate: strip a boundary face whose
                            // probe would cross into a source with no
                            // published-or-captured light (the LightView
                            // contract's per-side half). Interior cells'
                            // lateral probes stay in this chunk, so only
                            // edge rows pay a mask edit.
                            int probeMask = mask;
                            if (z == 0 && !northLightOk) {
                                probeMask &= ~FACE_NORTH;
                            }
                            if (z == 15 && !southLightOk) {
                                probeMask &= ~FACE_SOUTH;
                            }
                            if (x == 0 && !westLightOk) {
                                probeMask &= ~FACE_WEST;
                            }
                            if (x == 15 && !eastLightOk) {
                                probeMask &= ~FACE_EAST;
                            }
                            int lightByte = sampleCellLight(light, probeMask,
                                    baseX + x, y, baseZ + z);
                            if (lightByte < 0) {
                                // Every admissible probe refused: no
                                // fabricated byte — the flat fallback,
                                // and the plane is marked pending below.
                                farLightProbeGateRefusals.increment();
                                anyProbeRefused = true;
                                lightByte = LIGHT_FALLBACK_FLAT & 0xFF;
                            }
                            rawLight.add((byte) lightByte);
                        }
                        if (!airAbove) {
                            continue;
                        }
                        // THE WATER SURFACE UNDER A PLANT. This block
                        // stands in a fluid and has open air over it, so
                        // the fluid's own surface is visible here and no
                        // cell holds it - the position's block name is the
                        // plant's. Emit the fluid as a SECOND cell at the
                        // same position with one UP bit: the surface sheet
                        // gets its quad back and the mesher's underwater
                        // shading gets the UP-exposed fluid cell it finds
                        // the column's water surface with. See the class
                        // javadoc's "THE WATER SURFACE UNDER A PLANT".
                        // javap: BlockBehaviour$BlockStateBase:
                        //   public net.minecraft.world.level.material.FluidState
                        //     getFluidState();   (ip 0-4: getfield fluidState)
                        FluidState fluid = state.getFluidState();
                        if (fluid.isEmpty()) {
                            continue;
                        }
                        // javap: FluidState:
                        //   public net.minecraft.world.level.block.state.BlockState
                        //     createLegacyBlock();
                        // Compared by BLOCK and not by state: water itself
                        // (source or flowing) is its own legacy block and
                        // has already been stored by the walk above, so
                        // this fires only for something standing IN the
                        // fluid.
                        BlockState fluidBlock = fluid.createLegacyBlock();
                        if (fluidBlock.getBlock() == state.getBlock()) {
                            continue;
                        }
                        int fi = pal.indexOf(fluidBlock, tintLevel,
                                baseX, y, baseZ);
                        if (fi < 0) {
                            continue;
                        }
                        raw.add(((long) y << 32)
                                | (long) ShellCodec.packCell(x, 0, z, fi, FACE_UP));
                        if (rawLight != null) {
                            // FACE_UP, so the probe lands on the air above
                            // and the sheet takes full daylight - the same
                            // sample the water in the next column takes.
                            // An UP probe stays in this column, whose own
                            // light the entry point already vouched for,
                            // so the sample cannot be refused; the belt-
                            // and-braces arm keeps the contract literal
                            // (a -1 stores flat, never a fabricated byte).
                            int sheetByte = sampleCellLight(light, FACE_UP,
                                    baseX + x, y, baseZ + z);
                            if (sheetByte < 0) {
                                farLightProbeGateRefusals.increment();
                                anyProbeRefused = true;
                                sheetByte = LIGHT_FALLBACK_FLAT & 0xFF;
                            }
                            rawLight.add((byte) sheetByte);
                        }
                    }
                }
            }
        }

        if (raw.isEmpty()) {
            return null;
        }
        int minY = (int) (raw.getLong(0) >> 32);
        int maxY = (int) (raw.getLong(raw.size() - 1) >> 32);
        int shellMinY = minY;
        int start = 0;
        if (maxY - minY > 255) {
            // Never observed by the census; clamp from the top so the far
            // camera keeps what it can actually see. Orphaned palette
            // entries (referenced only by dropped cells) are tolerated.
            shellMinY = maxY - 255;
            while (start < raw.size() && (int) (raw.getLong(start) >> 32) < shellMinY) {
                start++;
            }
        }
        int[] cells = new int[raw.size() - start];
        for (int i = start; i < raw.size(); i++) {
            long entry = raw.getLong(i);
            // OR the now-known yRel into the parked cell (bits 8-15 were
            // packed as 0; yRel is 0..255 by the clamp above).
            cells[i - start] = (int) entry | (((int) (entry >> 32) - shellMinY) << 8);
        }
        // The light plane is sliced by the SAME offset as the cells, which
        // is the whole reason it was accumulated in lockstep rather than
        // rebuilt afterwards: the codec requires the two to be parallel.
        byte[] cellLight = null;
        if (rawLight != null) {
            cellLight = new byte[cells.length];
            for (int i = 0; i < cells.length; i++) {
                cellLight[i] = rawLight.getByte(start + i);
            }
            int verdict = planeVerdict(cellLight, cells.length,
                    req.hasSkyLight);
            if (anyProbeRefused && verdict == PLANE_FLAT) {
                // FINAL means "the plane is real and byte-identical to
                // flat", and a plane padded with fallback bytes is not
                // real in those cells — demote to pending so the sweep's
                // one retry can read the wall with the neighbour present.
                verdict = PLANE_UNTRUSTED;
            }
            if (verdict != PLANE_KEEP) {
                cellLight = null;
                lightUntrusted = verdict == PLANE_UNTRUSTED;
            } else if (anyProbeRefused) {
                // Stored — the interior light is real — but owed an
                // upgrade: the gate flattened the wall cells against an
                // absent neighbour. Reported pending, and the bound is the
                // existing one (at most one sweep re-extraction per
                // arrival, see lightPending).
                lightUntrusted = true;
            }
        }
        req.lightPendingOut = lightUntrusted;
        // A null tint table means "no sampled colour, use the block's
        // out-of-world default" and is what a pre-format-2 record decodes
        // to; a level with no tint view produces the same thing, so both
        // paths land on one documented fallback in the mesher.
        // The world position rides along in MEMORY ONLY (ShellCodec's
        // ORIGIN_UNKNOWN): the mesher needs it to reproduce vanilla's
        // per-position appearance inputs, and nothing about it is written.
        // S1 (pre19): every tinted entry stores vanilla's own per-block
        // colour for every block column of this chunk. Unconditional -
        // there is no boundary DETECTOR any more, because a detector that
        // misses stores a wrong flat colour and R8's did not have to miss
        // for the owner to see chunks of colour. Extraction-time, so
        // existing records keep pre18's corner grid until re-saved, which
        // is what the BAND_RULE_REVISION bump this change ships is for.
        // (A) The per-entry, per-column SAMPLING HEIGHTS, read off the
        // finished cells - after the top-clamp, so every height is the
        // height of a block this record really stores. See
        // Palette.columnHeights and Palette.mintY.
        int[] columnY = pal.columnHeights(cells, cells.length, shellMinY);
        int finalTintSide = pal.rebuildTints(tintSide, tintLevel,
                baseX, baseZ, columnY);
        return new ShellCodec.Shell(cells.length, cells,
                pal.names(), pal.states(), pal.signatures(),
                tintLevel == null ? null : pal.tints(),
                finalTintSide, cellLight,
                (byte) ShellCodec.TIER_VISITED,
                ShellCodec.FORMAT_VERSION,
                surfaceBand ? (byte) bandOffset : ShellCodec.FULL_SHELL,
                (short) shellMinY, baseX >> 4, baseZ >> 4);
    }

    /**
     * One chunk record's palette while it is being built: block STATES to
     * entry indices, with the name, the state index, the state-space
     * signature and the sampled tint grid of each entry.
     *
     * <h2>What decides that two states share an entry</h2>
     * {@code SpriteUvResolver.sameAppearance}, which is
     * {@code ModelManager.requiresRender} inverted - vanilla's own answer
     * to "would a chunk have to be re-rendered if this block became that
     * one". It compares the model GROUP (a baked model plus the values of
     * every property the block's tint sources read) and the
     * {@code FluidState} identity, so:
     * <ul>
     * <li>seven {@code distance} values of a leaf block, and its
     *     {@code persistent} flag, are ONE entry - none of them changes a
     *     texel;</li>
     * <li>a stair's {@code facing} / {@code half} / {@code shape} are up to
     *     forty entries, because they are up to forty different models;</li>
     * <li>{@code water}'s sixteen levels are separate entries even though
     *     all sixteen share one model, because their fluid states differ -
     *     which is exactly the clause that carries flowing height into the
     *     record without this class knowing anything about fluids.</li>
     * </ul>
     *
     * <h2>Cost, per chunk, on the game thread</h2>
     * One identity hash per CELL, which is what the block-keyed map cost.
     * Per newly seen STATE (a few dozen per chunk at most): one hash miss,
     * a scan of the entries this block already owns - one
     * {@code requiresRender} call each, two map reads inside - and, only if
     * it is genuinely new, one registry key lookup and the tint work below.
     * No new block reads, no new light reads and nothing per cell.
     *
     * <h2>Tint, and why the palette can grow without the biome blend doing so</h2>
     * A tint grid costs {@code side*side} {@code BiomeColors} blends and is
     * the most expensive thing in this class per entry. A second state of a
     * block the record already names samples node (0,0) ALONE and, if it
     * matches that block's first entry, copies the rest of the grid: one
     * blend instead of nine. Every vanilla tint source is a function of
     * position times a per-state constant, so agreeing at one node means
     * agreeing at all of them, and the one source that really does vary by
     * state ({@code redstone_wire}'s {@code power}) disagrees at node (0,0)
     * and pays the full grid, correctly.
     *
     * <h2>Why this is PUBLIC (pre20)</h2>
     * It is the unit under test, and until pre20 nothing tested it. The
     * green-water hunt's finding, verbatim: the only tint coverage
     * anywhere hand-built a {@code Shell}, encoded it, decoded it and
     * compared the result against the values it had just written - it
     * tested the CODEC, not the EXTRACTOR, and
     * {@code ShellExtractor.Palette} was never run against a level by any
     * test at all. A suite that only ever compares our arithmetic to our
     * arithmetic cannot catch "we asked the right function at the wrong
     * place", which is precisely what the green water was.
     *
     * <p>The three methods a test needs - {@link #indexOf},
     * {@link #columnHeights} and {@link #rebuildTints} - are the walk's
     * own call sequence in order, and the walk is the only production
     * caller. Driving them with a synthetic palette, a synthetic cell
     * array and a {@code BlockAndTintGetter} that answers a different
     * colour per y reproduces the whole defect cold: no world, no client
     * flight, no chunk. See {@code MesheliumFarFieldTest}'s
     * "the surface cell wears the SURFACE biome" leg.</p>
     */
    public static final class Palette {

        /** Are block states storable at all this session? See the ctor. */
        private final boolean storeStates;
        /** The per CELL lookup: state identity to entry index. */
        private final IdentityHashMap<BlockState, Integer> byState =
                new IdentityHashMap<>();
        /** Per BLOCK: the entry indices this block already owns, in order. */
        private final IdentityHashMap<Block, IntArrayList> byBlock =
                new IdentityHashMap<>();
        /** Per entry, parallel: name, representative state, index, signature. */
        private final List<String> names = new ArrayList<>();
        private final List<BlockState> entryStates = new ArrayList<>();
        private final IntArrayList stateIndices = new IntArrayList();
        private final IntArrayList signatures = new IntArrayList();
        /** Per entry: {@code side*side} 0xRRGGBB samples, appended in order. */
        private final IntArrayList tints = new IntArrayList(32 * 9);
        /**
         * Per entry: the y of the cell that MINTED it - the walk being
         * bottom-up, the LOWEST cell of that state in the chunk.
         *
         * <p><b>This is no longer where the tint field is sampled, and
         * that change is pre20's whole fix (docs/FARFIELD-WAVES.md
         * "GREEN WATER ANSWERED").</b> It used to be: every one of an
         * entry's 256 nodes was read at this single y while vanilla
         * evaluates biome tint in 3D, so an entry spanning two biome
         * layers wore the colour of its DEEPEST exposed cell over the
         * whole chunk. {@code minecraft:water[level=0]} is one state for
         * a whole chunk, its deepest exposed cell is a flooded cave or
         * aquifer (the band admits to about seabed-72), and vanilla 26.2
         * has exactly one cave biome declaring a {@code waterColor} -
         * {@code sulfur_caves}, 0x34BF89, a vivid spring green. The
         * result was a whole chunk of green ocean surface next to a
         * correct blue one.</p>
         *
         * <p>What it is still for, and it is not a sampling height: the
         * fallback for an entry the top-clamp ORPHANED (its every cell
         * dropped, so the finished cells name no y for it at all) and the
         * seed {@link #appendTint} writes before any cell y is known. See
         * {@link #rebuildTints}, which derives the real per-column
         * heights from the finished cells.</p>
         */
        private final IntArrayList mintY = new IntArrayList();

        /**
         * Nodes in one entry's column-height field and in one entry's
         * cell tint field: the same 16x16, so one base offset
         * {@code e * COLUMN_NODES} indexes both.
         */
        private static final int COLUMN_NODES =
                ShellCodec.TINT_SIDE_CELL * ShellCodec.TINT_SIDE_CELL;

        /**
         * Column-height sentinel: this entry has no surviving cell in
         * this column, so no cell y names a height for it. Distinct from
         * every legal world y (the format's own floor is
         * {@code ShellCodec.MIN_WORLD_Y}, -2032).
         */
        private static final int NO_CELL = Integer.MIN_VALUE;

        /**
         * @param storeStates false when the client has no model manager, in
         *     which case no state index is written and the record decodes
         *     exactly like a format-3 one. It is never a reason to skip a
         *     cell, and never an error.
         */
        public Palette(boolean storeStates) {
            this.storeStates = storeStates;
        }

        /**
         * This state's palette index, minting an entry on first sight, or
         * -1 when the table is full AND this block has no entry to fall
         * back on.
         *
         * <p>The 1-byte cell index caps the table at
         * {@link ShellCodec#MAX_PALETTE} ENTRIES where it used to cap
         * NAMES. On overflow a state whose block is already in the table
         * takes that block's FIRST entry - the pre-format-4 look for that
         * one block, never a hole - and only a genuinely new block is
         * refused. The census's largest real chunk palette was 44 names, so
         * both branches are theoretical; they are here because
         * "prefer storing more over showing a hole" is the standing L1
         * rule.</p>
         *
         * <p>The y handed in is remembered as {@link #mintY} - the FIRST
         * cell's, the walk being bottom-up - and is the seed
         * {@link #appendTint} samples at. It is NOT where the record's
         * stored colours end up being read: {@link #rebuildTints} throws
         * that away at walk end and re-derives a per-column height from
         * the finished cells. See {@link #mintY}.</p>
         */
        public int indexOf(BlockState state, BlockAndTintGetter tintLevel,
                int baseX, int y, int baseZ) {
            Integer known = byState.get(state);
            if (known != null) {
                return known;
            }
            Block block = state.getBlock();
            IntArrayList siblings = byBlock.get(block);
            if (siblings != null) {
                // A state this block already draws identically: same entry,
                // and remember it so the next cell is one hash again.
                for (int i = 0; i < siblings.size(); i++) {
                    int e = siblings.getInt(i);
                    BlockState seen = entryStates.get(e);
                    if (!storeStates
                            || SpriteUvResolver.sameAppearance(seen, state)) {
                        byState.put(state, e);
                        return e;
                    }
                }
            }
            if (names.size() >= ShellCodec.MAX_PALETTE) {
                if (siblings == null || siblings.isEmpty()) {
                    return -1;
                }
                int fallback = siblings.getInt(0);
                byState.put(state, fallback);
                return fallback;
            }
            int index = names.size();
            // javap: BuiltInRegistries:
            //   public static final net.minecraft.core.DefaultedRegistry
            //     <net.minecraft.world.level.block.Block> BLOCK;
            // Registry: public abstract net.minecraft.resources.Identifier getKey(T);
            names.add(BuiltInRegistries.BLOCK.getKey(block).toString());
            entryStates.add(state);
            stateIndices.add(storeStates
                    ? SpriteUvResolver.stateIndexOf(state) : ShellCodec.NO_STATE);
            signatures.add(storeStates ? SpriteUvResolver.stateSignatureOf(block) : 0);
            appendTint(state, tintLevel, baseX, y, baseZ);
            mintY.add(y);
            byState.put(state, index);
            if (siblings == null) {
                siblings = new IntArrayList(2);
                byBlock.put(block, siblings);
            }
            siblings.add(index);
            return index;
        }

        /**
         * Append this entry's ONE walk-time SEED colour: the tint at the
         * chunk's own origin column, at the entry's {@link #mintY}. It is
         * a placeholder and nothing else - {@link #rebuildTints} rewrites
         * it at walk end on EVERY row, Per Chunk included, once the
         * finished cells say which y and which column the entry actually
         * occupies. White for a block with no tint source at all, which
         * makes the mesher's multiply a no-op.
         *
         * <p><b>pre20:</b> the Per Chunk row used to keep this value as
         * its final answer, which made it carry the green-water defect in
         * its purest form - one colour for the chunk, sampled at the
         * deepest exposed cell of the deepest column, in a column that
         * need not even contain the entry. That is why "set Colour
         * Blending to Per Chunk" was a negative control for the bug
         * rather than a workaround for it.</p>
         */
        private void appendTint(BlockState state, BlockAndTintGetter tintLevel,
                int baseX, int y, int baseZ) {
            tints.add(tintLevel == null ? SpriteUvResolver.WHITE_RGB
                    : SpriteUvResolver.sampleTint(state, tintLevel,
                            new BlockPos(baseX, y, baseZ)));
        }

        /**
         * Rebuild the whole tint table as the 16x16 CELL FIELD - one node
         * per block column of this chunk, each holding
         * {@code ClientLevel.calculateBlockTint}'s own answer for that
         * block. Runs unconditionally at walk end for every record whose
         * Colour Blending row is not Per Chunk.
         *
         * <h2>Why this replaced the adaptive corner grid (the owner's S1)</h2>
         * "the color blending still doesnt happen its just chunks of
         * color that sometimes have a gradient, im starting to think our
         * approach is fundamentally wrong." He was right, and the wrong
         * thing was the MODEL, not its tuning. A corner grid is a
         * RECONSTRUCTION: N nodes and a bilinear read standing in for a
         * field too expensive to store. Three properties of that model
         * are visible at layer-1 range and no amount of densification
         * removes them:
         * <ul>
         * <li>a record whose nodes agree renders FLAT over 16x16 blocks,
         *     which is only correct if the true field is also flat
         *     there - and the DETECTOR that decided so (pre18's
         *     {@code densifyTints}: stored-grid disagreement, else a
         *     step-4 probe lattice) is a sampler of the same field it is
         *     trying to prove constant;</li>
         * <li>a record the detector did fire on rendered vanilla's
         *     {@code 2r+1}-block ramp as a 16-block bilinear smear
         *     displaced by up to r + 4 blocks - "sometimes have a
         *     gradient", in the wrong place and three times too wide;</li>
         * <li>even at the 17x17 per-block corner grid the mesher's
         *     centre-sampled bilinear returned the MEAN of the four
         *     surrounding blocks' values - a second box blur on top of
         *     vanilla's, widening the ramp by a block and displacing it
         *     half a block.</li>
         * </ul>
         * The cell field has no reconstruction in it at all: node
         * {@code (x, z)} is the value, the mesher indexes it, and the
         * field IS vanilla's. There is nothing left to detect, so there
         * is no detector to get wrong.
         *
         * <h2>Seam agreement, by construction and without a shared node</h2>
         * The corner grid bought seam agreement with a shared edge row
         * (offset 16 = the neighbour's offset 0). The cell field does not
         * need one: {@code calculateBlockTint} is a pure function of world
         * position, each record stores it at ITS OWN blocks, and neither
         * record holds an opinion about a block the other one owns. Two
         * adjacent records therefore cannot disagree at the seam - not
         * "agree to within a rounding step", cannot disagree. The 33
         * nodes per entry the corner convention spent on its shared row
         * are given back.
         *
         * <h2>Price</h2>
         * 256 {@code getBlockTint} reads per tinted BLOCK (sibling
         * entries of one block re-use the first's field by the same
         * node-(0,0) equality {@link #appendTint} uses; an untinted block
         * fills 256 whites without a single sample). On the LIVE path
         * those land in {@code BlockTintCache}, which caches an
         * {@code int[256]} per (chunk, y) and is normally already warm
         * for this chunk's own layer because vanilla's section renderer
         * filled it - so the cost is 256 array reads, and a cold layer is
         * 256 misses x {@code (2r+1)^2} biome lookups, once, shared with
         * the near field. <b>pre20 note:</b> the layers an entry touches
         * is now the number of distinct ys its own cells occupy rather
         * than one, which for a level entry (ocean water, a flat grass
         * plain) is still exactly one and for a relief entry is one per
         * surface height in the chunk - never the chunk's height range. On the PIN path {@code Pin.PinTintView} answers
         * the same box average from a summed-area table over the pin
         * graph: ~400 raw samples per (resolver, y) instead of 6400.
         * <b>Disk: unchanged for the overwhelming majority</b> - the
         * encoder's per-entry uniform shortcut stores a one-colour field
         * as 4 bytes whatever the side, so only a genuinely non-uniform
         * entry grows, to {@code 1 + 3*256} = 769 B raw (against pre18's
         * 868 B at side 17) before zlib. In RAM a boundary record's
         * decoded table is {@code entries * 256} ints.
         *
         * <h2>THE RESIDUAL THAT WAS THE BUG (pre20, the green water)</h2>
         * This javadoc used to close with a paragraph naming exactly the
         * defect below and declining to fix it, and three waves then
         * hunted colour FUNCTIONS because the source had pre-declared the
         * POSITION axis out of scope. It said: all 256 nodes of an entry
         * share one y - the first cell's, i.e. the lowest, the walk being
         * bottom-up - while vanilla's biome lookup is 3D; per-column y is
         * "derivable from the finished cells for free" but was declined
         * because it "turns one {@code BlockTintCache} layer per entry
         * into up to forty".
         *
         * <p><b>Both halves were wrong about water, which is the case
         * that mattered.</b> {@code minecraft:water[level=0]} is ONE
         * state for a whole chunk, so it is one entry, stamped at its
         * deepest EXPOSED cell - a flooded cave, an aquifer surface, a
         * ravine, reachable because {@code closeLeaks} admits down to
         * about seabed-72 - and vanilla 26.2 has exactly one cave biome
         * declaring a {@code waterColor}: {@code sulfur_caves}, 0x34BF89,
         * a vivid spring green, registered by
         * {@code OverworldBiomeBuilder.addUndergroundBiomes} under flat
         * low-lying coastal terrain, i.e. under water. The whole chunk's
         * water surface wore cave water; a neighbour with no exposed deep
         * cell was correct. "Vibrantly green, then immediately blue at
         * the next chunk."</p>
         *
         * <p><b>And the price was never forty layers.</b> The layers an
         * entry can touch are the ys ITS OWN CELLS occupy, not the
         * chunk's height range: ocean water is {62} plus a handful of
         * cave ys, a grass field is one y per surface height in the
         * chunk, and an entry whose cells are all at one y costs exactly
         * what it cost before. The cost objection priced the worst case
         * of a different quantity.</p>
         *
         * <p>So: {@link #mintY} is dead as a sampling height.
         * {@code columnY} - derived by the caller from the finished
         * cells, AFTER the top-clamp so the heights match what is
         * actually stored - carries the topmost cell y of entry
         * {@code e} in column {@code (x, z)}, and every node is read
         * there. Zero extra world reads: the walk already wrote absolute
         * y into the high 32 bits of every {@code raw} entry and is
         * bottom-up, so last-write-wins is the topmost cell.</p>
         *
         * @param columnY {@code names.size() * COLUMN_NODES} heights,
         *     {@link #NO_CELL} where the entry owns no surviving cell,
         *     laid out {@code e * COLUMN_NODES + (z << 4) + x} - the SAME
         *     index space as the tint field, so one base serves both.
         *     MUTATED: the {@link #NO_CELL} holes of an entry are filled
         *     with that entry's topmost y.
         * @return {@link ShellCodec#TINT_SIDE_CELL} when the cell field
         *         was built, else {@code tintSide} unchanged (Per Chunk -
         *         whose one colour per entry is still re-sampled here -
         *         or no world to sample)
         */
        public int rebuildTints(int tintSide, BlockAndTintGetter tintLevel,
                int baseX, int baseZ, int[] columnY) {
            if (tintLevel == null || names.isEmpty() || columnY == null
                    || columnY.length != names.size() * COLUMN_NODES) {
                return tintSide;
            }
            if (tintSide != ShellCodec.TINT_SIDE_CELL) {
                // (D) The Per Chunk row: one colour per entry, but taken
                // at the entry's OWN topmost cell in a column that really
                // contains it, not at the chunk origin at the mint y.
                rebuildChunkTints(tintLevel, baseX, baseZ, columnY);
                return tintSide;
            }
            int cellNodes = COLUMN_NODES;
            IntArrayList rebuilt = new IntArrayList(names.size() * cellNodes);
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
            // First rebuilt entry per block, for the sibling short
            // circuit: vanilla tint sources are a per-state constant
            // times a function of POSITION, so two entries of one block
            // that agree at node (0,0) agree at all 256 - but only when
            // they are read at the same 256 positions. pre20 added the
            // height guard, because with per-column y two siblings of one
            // block (water source and flowing water are the live case)
            // generally own DIFFERENT cells and so different heights, and
            // then one shared sample proves nothing. Comparing 256 ints
            // is far cheaper than 256 biome box averages, so the guard
            // keeps the short circuit affordable rather than deleting it.
            IdentityHashMap<Block, Integer> firstOfBlock = new IdentityHashMap<>();
            for (int e = 0; e < names.size(); e++) {
                BlockState state = entryStates.get(e);
                int base = e * cellNodes;
                fillColumnHeights(columnY, base, mintY.getInt(e));
                Block block = state.getBlock();
                Integer prior = firstOfBlock.get(block);
                if (prior != null) {
                    int priorBase = prior * cellNodes;
                    if (sameHeights(columnY, base, priorBase)) {
                        int firstNode = SpriteUvResolver.sampleTint(state,
                                tintLevel, probe.set(baseX, columnY[base], baseZ));
                        if (firstNode == rebuilt.getInt(priorBase)) {
                            for (int n = 0; n < cellNodes; n++) {
                                rebuilt.add(rebuilt.getInt(priorBase + n));
                            }
                            continue;
                        }
                    }
                }
                sampleCellField(rebuilt, state, tintLevel, baseX, baseZ,
                        columnY, base);
                firstOfBlock.putIfAbsent(block, e);
            }
            tints.clear();
            tints.addAll(rebuilt);
            return ShellCodec.TINT_SIDE_CELL;
        }

        /**
         * (D) The Per Chunk row's rebuild: replace each entry's single
         * seed colour with vanilla's answer at the entry's OWN topmost
         * cell - both the y and the column of it.
         *
         * <p>{@link #appendTint} sampled the CHUNK ORIGIN column at the
         * mint y, which is wrong twice over: the mint y is the entry's
         * deepest cell (the green-water defect in its purest form, one
         * flat wrong colour for the whole record), and column (0, 0) need
         * not contain the entry at all - an ocean chunk with a strip of
         * beach in the north-west corner sampled its water at a sand
         * column. Both are fixed by asking at a block the entry really
         * occupies.</p>
         *
         * <p>Cost: one {@code sampleTint} per palette entry, so about
         * eight per record on the census's mean surface-band palette, and
         * a 256-int scan per entry to find the topmost. An entry the
         * top-clamp orphaned names no cell and keeps its seed colour -
         * nothing references it.</p>
         */
        private void rebuildChunkTints(BlockAndTintGetter tintLevel,
                int baseX, int baseZ, int[] columnY) {
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
            for (int e = 0; e < names.size(); e++) {
                int base = e * COLUMN_NODES;
                int topY = NO_CELL;
                int topNode = -1;
                for (int n = 0; n < COLUMN_NODES; n++) {
                    int y = columnY[base + n];
                    if (y != NO_CELL && (topNode < 0 || y > topY)) {
                        topY = y;
                        topNode = n;
                    }
                }
                if (topNode < 0) {
                    continue; // orphaned by the top-clamp; unreferenced
                }
                tints.set(e, SpriteUvResolver.sampleTint(entryStates.get(e),
                        tintLevel, probe.set(baseX + (topNode & 15), topY,
                                baseZ + (topNode >> 4))));
            }
        }

        /**
         * Fill one entry's {@link #NO_CELL} holes with that entry's
         * topmost cell y, and return that y.
         *
         * <p>A hole is a column where the entry owns no surviving cell,
         * so no cell of it is ever drawn there and
         * {@code ShellMesher.cellTint} never reads the node (it indexes
         * by the CELL's own x and z). The value is therefore free, and
         * the entry's own topmost y is the choice that keeps a uniform
         * field uniform - which matters, because the encoder collapses a
         * uniform entry to four bytes and a gratuitously varied filler
         * would inflate every single-biome ocean record to 769 B.</p>
         *
         * @param fallback the entry's {@link #mintY}, used only when the
         *     top-clamp orphaned every one of its cells
         */
        private static int fillColumnHeights(int[] columnY, int base,
                int fallback) {
            int top = NO_CELL;
            for (int n = 0; n < COLUMN_NODES; n++) {
                int y = columnY[base + n];
                if (y != NO_CELL && (top == NO_CELL || y > top)) {
                    top = y;
                }
            }
            if (top == NO_CELL) {
                top = fallback;
            }
            for (int n = 0; n < COLUMN_NODES; n++) {
                if (columnY[base + n] == NO_CELL) {
                    columnY[base + n] = top;
                }
            }
            return top;
        }

        /** Do two entries' filled height fields agree node for node? */
        private static boolean sameHeights(int[] columnY, int aBase, int bBase) {
            for (int n = 0; n < COLUMN_NODES; n++) {
                if (columnY[aBase + n] != columnY[bBase + n]) {
                    return false;
                }
            }
            return true;
        }

        /**
         * (A) THE SAMPLING HEIGHTS - one y per (palette entry, block
         * column), read straight off the record's FINISHED cells.
         *
         * <p>Last write wins, and that is the whole algorithm: the walk
         * emits cells bottom-up with y nondecreasing and this preserves
         * that order, so the surviving write for a given (entry, column)
         * is the entry's TOPMOST cell there - the block a viewer of the
         * far field actually sees. A column an entry owns no cell in is
         * left {@link #NO_CELL} and filled later, by
         * {@link #fillColumnHeights}, from the entry's topmost.</p>
         *
         * <p><b>From the cells rather than from the walk's scratch, on
         * purpose.</b> The same numbers could be scraped out of the
         * walk's {@code raw} list one line earlier, for one fewer pass.
         * Taking them from {@code cells} instead means the heights are
         * derived from the bytes THIS RECORD STORES, after the top-clamp
         * has dropped whatever it drops - so "the colour was sampled at
         * the block we are drawing" is a property of the array being
         * encoded and not an argument about two loops agreeing. It also
         * makes the whole defect surface a pure function of
         * {@code (palette, cells, minY)} that a cold test can drive with
         * a synthetic column and no world at all, which is the coverage
         * the green-water hunt found missing. The price is one pass over
         * {@code cellCount} ints - 443 at the census mean.</p>
         *
         * @param cells the finished cell array, yRel already packed
         * @param cellCount valid prefix of {@code cells}
         * @param minY the record's {@code minY}, i.e. what yRel 0 means
         * @return {@code names.size() * COLUMN_NODES} heights, indexed
         *     {@code e * COLUMN_NODES + (z << 4) + x}
         */
        public int[] columnHeights(int[] cells, int cellCount, int minY) {
            int[] field = new int[names.size() * COLUMN_NODES];
            Arrays.fill(field, NO_CELL);
            for (int i = 0; i < cellCount; i++) {
                int cell = cells[i];
                int index = ShellCodec.cellPaletteIndex(cell) * COLUMN_NODES
                        + (ShellCodec.cellZ(cell) << 4) + ShellCodec.cellX(cell);
                if (index >= 0 && index < field.length) {
                    field[index] = minY + ShellCodec.cellYRel(cell);
                }
            }
            return field;
        }

        String[] names() {
            return names.toArray(new String[0]);
        }

        /** Null when this record stores no states, which the codec wants. */
        int[] states() {
            return storeStates ? stateIndices.toIntArray() : null;
        }

        /** Null exactly when {@link #states()} is; the codec requires it. */
        int[] signatures() {
            return storeStates ? signatures.toIntArray() : null;
        }

        public int[] tints() {
            return tints.toIntArray();
        }
    }

    /**
     * Append this palette entry's 16x16 CELL FIELD to {@code out}, row
     * major by z then x: node {@code (gx, gz)} is
     * {@code ClientLevel.calculateBlockTint} at world
     * {@code (baseX + gx, y, baseZ + gz)}, i.e. the exact integer the near
     * field multiplies into that block's quad.
     *
     * <p><b>No shared edge row, and none needed.</b> Unlike the corner
     * convention this replaced (nodes at offsets {@code 0, 16/(N-1), ...
     * 16}, the last row shared with the neighbour), this samples only the
     * 256 blocks the record actually owns. Seam agreement comes from the
     * function, not from the layout: {@code calculateBlockTint} is a pure
     * function of world position and the biome map, so the neighbour's own
     * node for its own block is the same number vanilla draws there, and
     * neither record has an opinion about a block it does not contain.
     * Nothing here reads the neighbour's shell, its record or its
     * palette.</p>
     *
     * <p><b>(B) Each node is read at ITS OWN column's y</b>
     * ({@code columnY[base + (gz &lt;&lt; 4) + gx]}, the topmost cell of
     * this entry in that column, filled with the entry's topmost y where
     * it owns no cell). Vanilla's biome lookup is 3D and the far field
     * draws a specific block, so the only defensible height to ask at is
     * the height of the block being drawn. Sampling all 256 at one y was
     * the green water: see {@link Palette#rebuildTints}.</p>
     *
     * <p><b>(C) The white short circuit, re-derived.</b> It used to be
     * "node (0,0) came back {@link SpriteUvResolver#WHITE_RGB}, so fill
     * 256 whites" - sound only while every node shared one sampling
     * position-family, where a white ANSWER really did prove the block
     * had no source. With per-column y it proves nothing: a tinted block
     * can be white at one position and coloured at another, and one
     * unlucky column would flatten fifteen correct ones. The premise it
     * always MEANT is now asked directly and without a sample -
     * {@link SpriteUvResolver#hasTintSource}, a fact about the state
     * alone - so the affordability argument is unchanged (on the
     * census's mean 8.4-name surface-band palette, two or three entries
     * per record pay for a field) and it no longer depends on a value
     * that has stopped being representative.</p>
     *
     * <p><b>The layers this really touches.</b> The cost objection that
     * kept the field at one y priced "up to forty {@code BlockTintCache}
     * layers per entry" - the chunk's height range. The true figure is
     * the number of distinct ys THIS ENTRY'S OWN CELLS occupy: {62} for
     * ocean water plus a handful of cave ys, one per surface height for
     * a grass field, and exactly one - i.e. exactly the old cost - for
     * any entry whose cells are level.</p>
     */
    private static void sampleCellField(IntArrayList out, BlockState state,
            BlockAndTintGetter level, int baseX, int baseZ,
            int[] columnY, int base) {
        int side = ShellCodec.TINT_SIDE_CELL;
        int nodes = side * side;
        if (level == null || !SpriteUvResolver.hasTintSource(state)) {
            for (int n = 0; n < nodes; n++) {
                out.add(SpriteUvResolver.WHITE_RGB);
            }
            return;
        }
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                out.add(SpriteUvResolver.sampleTint(state, level,
                        probe.set(baseX + gx, columnY[base + (gz << 4) + gx],
                                baseZ + gz)));
            }
        }
    }

    /**
     * The packed light byte for one cell: sky in the high nibble, block
     * light in the low one, both 0..15.
     *
     * <p><b>Sampled at the NEIGHBOUR across a face, never at the cell
     * itself</b>, and that is not a refinement, it is the difference
     * between working and black. The light engine stores 0 inside an
     * opaque block, so a grass block's own position reads sky 0 / block 0;
     * vanilla's own renderer samples {@code pos.relative(direction)} for
     * every face for exactly this reason.</p>
     *
     * <p><b>One sample per cell, not six.</b> Six would be correct and
     * would cost six bytes per cell in the record instead of one, i.e. the
     * whole saving the surface band exists to make. The face chosen is UP
     * when UP is exposed (the surface case, and the face a distant camera
     * looking down at terrain overwhelmingly sees), otherwise the
     * BRIGHTEST admissible exposed face — unsigned compare of the packed
     * byte, which IS "sky nibble first, then block nibble" because sky
     * rides the high nibble.</p>
     *
     * <p><b>Brightest, not first-in-bit-order</b> (the pre15 rule took the
     * FIRST exposed face in bit order, and {@link #FACE_DOWN} is bit 0 —
     * so a cell with no UP face took the darkest pocket's light for ALL
     * its faces, and P5's deeper band floors created the wall-base
     * population where that reads a real 0x00 and paints the face vanilla
     * draws sunlit solid black). Since M5 the rule is no longer the Phase
     * 0 stopgap standing in for a missing verdict: it is the documented
     * AGGREGATION over per-probe-source-verified samples — every face
     * probe resolves individually through the {@link LightView} contract
     * (published-LIVE, captured, or UNKNOWN), an UNKNOWN face contributes
     * nothing, and the brightest KNOWN face is the one byte the format
     * has room for. Brightest stays the conservative error direction: a
     * wrongly-bright face matches the pre11 flat-light look the owner
     * accepted, a wrongly-black face is the pre15 regression.</p>
     *
     * <p><b>THE AGGREGATE IS NO LONGER WHAT A FACE DRAWS (pre18, R5a),
     * and no format bump was needed for that.</b> The old note here said
     * per-FACE bytes were a record-format change awaiting one. They are
     * not: the plane already CONTAINS the number every face wants,
     * filed under whichever cell sampled that air.
     * {@code ShellMesher.airLight} inverts the filing at mesh time - a
     * cell with UP exposed donates its byte to the air above it, a cell
     * with exactly ONE exposed face donates to the air across that face
     * (both exact, because this method is deterministic) - and each
     * cubic face then draws the donor for the cell it looks into, which
     * is what {@code BlockModelLighter.prepareQuadFlat} ip 16-52 does.
     * So NOTHING about this method changes and nothing about the record
     * changes; what changed is that a multi-face cell's aggregate is now
     * only the FALLBACK for a face whose air nobody donated for.
     * {@link #farLightBrightestFacePicks} therefore keeps its counter and
     * sharpens its meaning: it counts the cells whose stored byte is an
     * aggregate, i.e. exactly the population the donor map exists to
     * correct. See docs/FARFIELD-WAVES.md, "R4 AND R5 ANSWERED (S1)".</p>
     *
     * <p><b>The mask here is the ADMISSIBLE mask</b> — the caller has
     * already stripped any boundary face whose probe would cross into a
     * source with no published-or-captured light (the per-side half of
     * the LightView contract; Phase 0's cache-residency gate is its
     * floor). Returns {@code -1} when no admissible face is left OR every
     * admissible probe answered UNKNOWN: a refused probe never fabricates
     * a byte, neither the dead neighbour's 0x00 nor the retired world's
     * 0xF0 nor a queued placeholder's zero. The caller stores the flat
     * fallback for that cell and reports the plane pending.</p>
     */
    private static int sampleCellLight(LightView light, int mask,
            int wx, int y, int wz) {
        if ((mask & FACE_UP) != 0) {
            int up = probeFaceLight(light, 1, wx, y, wz);
            if (up >= 0) {
                return up;
            }
            mask &= ~FACE_UP; // UNKNOWN up: let the other faces compete
        }
        if (mask == 0) {
            return -1;
        }
        if ((mask & (mask - 1)) == 0) {
            // One admissible face: no competition to resolve.
            return probeFaceLight(light,
                    Integer.numberOfTrailingZeros(mask), wx, y, wz);
        }
        farLightBrightestFacePicks.increment();
        int best = -1; // stays -1 iff every probe answered UNKNOWN
        for (int m = mask; m != 0; m &= m - 1) {
            int sample = probeFaceLight(light,
                    Integer.numberOfTrailingZeros(m), wx, y, wz);
            if (sample > best) {
                best = sample;
            }
        }
        return best;
    }

    /**
     * One face's packed light byte, probed at the neighbour across it
     * through the walk's {@link LightView} — 0..255, or -1 when that
     * source cannot honestly answer (UNKNOWN never stores).
     */
    private static int probeFaceLight(LightView light, int face,
            int wx, int y, int wz) {
        int dx = 0;
        int dy = 0;
        int dz = 0;
        switch (face) {
            case 0 -> dy = -1;
            case 1 -> dy = 1;
            case 2 -> dz = -1;
            case 3 -> dz = 1;
            case 4 -> dx = -1;
            default -> dx = 1;
        }
        return light.skyBlock(wx + dx, y + dy, wz + dz);
    }

    /**
     * The per-cell fallback when the probe-residency gate refuses a cell's
     * every probe: sky 15 / block 0, byte-identical to the flat light the
     * mesher synthesizes for a record with NO plane — so the refused cell
     * draws exactly what it would draw if the whole plane had been
     * refused, not a fabricated reading. A plane containing any of these
     * is never marked FINAL (see {@link #planeVerdict}'s caller).
     */
    private static final byte LIGHT_FALLBACK_FLAT = (byte) 0xF0;

    /** One light-engine level into the record's 4-bit field. */
    private static int clampNibble(int light) {
        return light < 0 ? 0 : Math.min(light, 15);
    }

    /** {@link #planeVerdict}: store this plane. */
    private static final int PLANE_KEEP = 0;
    /**
     * {@link #planeVerdict}: drop it, and the column is FINAL - the plane
     * is real and is byte-identical to what the mesher synthesizes free.
     */
    private static final int PLANE_FLAT = 1;
    /**
     * {@link #planeVerdict}: drop it, and the column is owed a better one -
     * the plane could not have come from real data.
     */
    private static final int PLANE_UNTRUSTED = 2;

    /**
     * Judge a finished light plane. <b>Belt and braces behind the
     * capture's own light gate, and deliberately independent of it:</b> a
     * stored plane that is wrong is wrong for as long as that record lives,
     * so the cheap conservative move - refuse it and fall back to the flat
     * light pre11 shipped, which looks fine - beats storing something
     * suspect every time. Falling back is never a regression against pre11;
     * storing zeros is.
     *
     * <p>Three rules, one pass:</p>
     * <ul>
     * <li><b>uniformly {@code 0xF0}</b> (sky 15, block 0) is exactly
     *     {@code ShellMesher}'s flat light, so storing it buys nothing and
     *     costs one byte per cell - about 700 B of raw record on the
     *     census's mean 703-cell chunk. Dropped, and the column is FINAL:
     *     the capture's light gate has already ruled out the two states
     *     that could fake this.</li>
     * <li><b>uniformly {@code 0x00}</b> is the symmetric case and it is the
     *     placeholder's own signature. Dropped and marked UNTRUSTED. In a
     *     dimension with no sky engine this also covers a genuinely unlit
     *     chunk, which is indistinguishable from a placeholder by
     *     construction; the price is one re-extraction and a fall back to
     *     flat, and the alternative is storing a black plane that might be
     *     a lie.</li>
     * <li><b>no cell at sky 15, in a dimension that HAS sky light</b> is
     *     impossible for a correctly-read shell and is the mixed case: the
     *     highest non-air block of every column owns an exposed UP face, so
     *     it is always stored, and {@link #sampleCellLight} samples it
     *     through that face at the open air above - sky 15. A plane with no
     *     such cell was read from a placeholder. Dropped and marked
     *     UNTRUSTED.</li>
     * </ul>
     *
     * <p>The one shape that survives all three is a MIXED plane - real
     * bytes beside wrong ones. The pre12b javadoc argued that shape
     * unreachable because {@code enableChunkLight} seeds a column's
     * placeholders over its whole height at once; that argument covered
     * VERTICAL mixing only, and the pre15 black family walked in through
     * the horizontal arm it missed: {@code sampleCellLight} probes one
     * block across the chunk plane, so a plane mixes bytes from up to five
     * chunks while this method judges one. Since M5 that blindness has no
     * open arm to miss: every probe resolves per SOURCE through the
     * {@link LightView} contract - absent-from-cache (Phase 0's gate, the
     * floor: drop-seam curtain, retained-drain fake-noon,
     * receive-frontier placeholder) and resident-with-queued-
     * {@code applyLightData} alike (the arm Phase 0 documented as open;
     * the LIVE view refuses any lateral whose record has not seen its
     * light published, and the CAPTURED view refuses any side that is
     * neither pinned-with-light nor captured). This method stays as
     * belt-and-braces behind the contract, exactly as it stood behind
     * the deleted {@code lightReadable} - a stored plane that is wrong is
     * wrong for as long as the record lives, so two independent judges
     * beat one.</p>
     *
     * <p>The caller also demotes PLANE_FLAT to PLANE_UNTRUSTED when any
     * cell stored the gate's fallback byte, so FINAL is never pronounced
     * over a padded plane.</p>
     */
    private static int planeVerdict(byte[] light, int count, boolean skyLightDimension) {
        boolean anyNonFlat = false;
        boolean anyLit = false;
        boolean anyFullSky = false;
        for (int i = 0; i < count; i++) {
            int b = light[i] & 0xFF;
            if (b != 0xF0) {
                anyNonFlat = true;
            }
            if (b != 0) {
                anyLit = true;
            }
            if ((b & 0xF0) == 0xF0) {
                anyFullSky = true;
            }
        }
        if (!anyLit) {
            return PLANE_UNTRUSTED;
        }
        if (skyLightDimension && !anyFullSky) {
            return PLANE_UNTRUSTED;
        }
        if (!anyNonFlat) {
            return PLANE_FLAT;
        }
        return PLANE_KEEP;
    }

    /*
     * The lightVerdict memo (lightVerdictKey / lightVerdictPending) and
     * lightPending(LevelChunk) stood here and died with the LIVE walk
     * that wrote them. The captured path has always reported its verdict
     * on pin.walkLightPending instead - a per-pin field rather than a
     * single static slot - and that is now the only channel.
     *
     * Its one reader outside this class was the safety-net sweep's
     * degraded-repair gate in ExtractDispatch, which now asks the
     * record's own lightReady. That is a TIGHTER gate, not a looser one:
     * a single-slot memo only ever answered for the chunk walked most
     * recently, so for every other column it read false and let the
     * repair through.
     */

    /*
     * neighborChunk(ChunkSource, int, int) - the client chunk cache probe
     * - stood here and died with the LIVE wrapper that was its only
     * caller. Nothing in this class reaches the live cache any more: a
     * walk's world is entirely the pin it was handed, which is what makes
     * the walk safe on PinWorker. The cache probes that decide a pin's
     * SIDES still exist, on the game thread, in ExtractDispatch.finishPin.
     */

    /**
     * Block state at chunk-local (x, z) and ABSOLUTE y, or {@code null}
     * for air-by-construction (outside the section array, or an all-air
     * section). {@code y >> 4} is floor division, correct for negative y.
     *
     * <p>A null {@code sections} is an UNLOADED chunk and also answers
     * null. The caller must not read that as air: the cell walk pairs every
     * lateral read with a {@code *Known} flag and routes it through
     * {@link #exposedLateral}, and the leak flood only ever asks about a
     * chunk it put in its own domain.</p>
     */
    private static BlockState stateAt(LevelChunkSection[] sections, int minSectionY,
            int x, int y, int z) {
        if (sections == null) {
            return null;
        }
        int idx = (y >> 4) - minSectionY;
        if (idx < 0 || idx >= sections.length) {
            return null;
        }
        LevelChunkSection section = sections[idx];
        if (section == null || section.hasOnlyAir()) {
            return null;
        }
        return section.getBlockState(x, y & 15, z);
    }

    /**
     * <b>THE occlusion rule.</b> Is any part of this block's {@code dir}
     * face visible from the other side of it?
     *
     * <p>Vanilla's own predicate, unmodified:
     * {@code Block.shouldRenderFace(owner, neighbour, dir)}. javap on the
     * merged jar, in order (ip 0-70): take
     * {@code neighbour.getFaceOcclusionShape(dir.getOpposite())}; if it is
     * {@code Shapes.block()} the face is hidden outright; else if
     * {@code owner.skipRendering(neighbour, dir)} it is hidden; else if
     * either shape is {@code Shapes.empty()} it is visible; else subtract
     * the neighbour's shape from the owner's with {@code ONLY_FIRST} and
     * ask whether anything is left, through a thread-local shape-pair
     * cache. Every branch is either an identity compare or a cached join,
     * which is why the two dominant cases - a full occluder and air - cost
     * two reference compares.</p>
     *
     * <p>{@code null} neighbour means air-like - an empty section, or past
     * the world top - and is exposed. Below the world BOTTOM is void rather
     * than air and the caller does not ask; an unloaded lateral chunk is
     * unknown rather than air and goes through
     * {@link #exposedLateral}.</p>
     *
     * <h2>The ONE far-field deviation, and it is vanilla's own rule at a
     * cheaper graphics setting</h2>
     * <p>A face between two cells of the SAME BLOCK, where the OWNER is
     * see-through ({@code !canOcclude()}), is dropped. That is exactly
     * {@code LeavesBlock.skipRendering}'s first branch (javap ip 0-17:
     * {@code if (!cutoutLeaves && neighbour.getBlock() instanceof
     * LeavesBlock) return true;}) and
     * {@code HalfTransparentBlock.skipRendering}'s only branch (ip 0-9:
     * {@code neighbour.is(this)}), generalised from two families to all of
     * them. Without it a canopy on Fancy graphics stores every interior
     * leaf face: a 5x5x3 oak canopy is 75 blocks and about 260 interior
     * faces, so four trees in a chunk would add roughly 1,000 faces and
     * 4 KB of raw record for geometry no ray from outside can reach.
     * {@code Block.shouldRenderFace} would say yes to all of them and be
     * right about vanilla; this says no and is right about a horizon.</p>
     *
     * <p><b>It is narrow on purpose.</b> Only a see-through owner takes it,
     * so every OCCLUDING partial - slab against slab, stair against stair,
     * snow against snow - goes to vanilla's shape subtraction and comes back
     * exactly right: two snow layers at the same height each present a 2/16
     * face strip, the subtraction is empty, and a flat snow field is still
     * 256 top faces and nothing sideways. Two slabs of opposite halves DO
     * show the face between them, which is correct and which the wider
     * version of this rule would have thrown away.</p>
     *
     * <h2>What it replaced, and the four defects that were the same defect</h2>
     * Until pre11 this method was two rules bolted together: an OPAQUE
     * owner asked {@code Block.shouldRenderFace} and a see-through owner
     * asked {@code neighbour.isAir()}. That second half is where the
     * owner's L0 lived - "if a block is not solid and partially see through
     * we need to treat it as such" - because it treats EVERY non-air
     * neighbour as a full occluder:
     * <ul>
     * <li><b>L2, leaves beside a snow layer lose their sides.</b> Snow is
     *     {@code canOcclude() == true} ({@code Blocks} clinit ip
     *     10206-10264 registers it with neither {@code noOcclusion()} nor
     *     {@code noCollision()}, the only two setters that clear the flag)
     *     while covering 2/16 of the face it claims. The leaf is
     *     see-through, the snow is not air, the face was dropped, and only
     *     the tops drew. Under the rule above:
     *     {@code snow.getFaceOcclusionShape(WEST)} is the 2/16 strip and not
     *     {@code Shapes.block()}, {@code skipRendering} is false (snow is
     *     not a LeavesBlock and not the same block), and the leaf's OWN
     *     occlusion shape is {@code Shapes.empty()} because it does not
     *     occlude - so the method returns true at its fourth branch and the
     *     side face is stored. Nothing about snow was named to fix it.</li>
     * <li><b>Plants standing in water.</b> A kelp stalk is surrounded by
     *     water on all six sides, water is not air, the mask came out zero
     *     and the codec rejected the cell - the stalk never reached the
     *     store. pre8 patched that with an admission hack: ask
     *     {@code SpriteUvResolver} whether the block DRAWS as a plant and,
     *     if so, admit the cell with one nominal UP bit. Gone. Water's face
     *     occlusion shape is empty and kelp's own is empty, so the stalk's
     *     six faces are simply exposed, and the mesher draws its model.</li>
     * <li><b>Water beside anything that is not a full cube.</b> The same
     *     sentence, from the other end: a stream's water lost the face it
     *     showed a lily pad, a slab lip, a snowy bank, a fence or a grass
     *     tuft standing in the shallows - and where it lost its UP face it
     *     also lost its surface sheet, its 8/9 height and its underwater
     *     shading, because all three key on "the only UP-exposed fluid cell
     *     in the column". Part of the owner's L4.</li>
     * <li><b>Water against water, which had to keep working.</b> It does,
     *     and not by our exception: {@code LiquidBlock.skipRendering}
     *     (javap ip 0-14) is
     *     {@code neighbour.getFluidState().getType().isSame(this.fluid)},
     *     so water hides against water AND against every waterlogged block,
     *     which is what keeps a kelp forest from adding a water face per
     *     stalk.</li>
     * </ul>
     *
     * <h2>Price</h2>
     * Opaque owners are unchanged - they already asked this. See-through
     * owners now ask it too, which is one virtual call and two reference
     * compares where it used to be one {@code isAir()}. What that admits is
     * bounded by geometry rather than by a block list: a face is added only
     * where a see-through block meets a NON-air, NON-full-occluding
     * neighbour of a DIFFERENT block, which is leaf-against-snow,
     * plant-against-plant-of-another-kind, water-against-partial and the
     * waterlogged-plant column. On the census's 300 chunks the shapes that
     * fire are all surface features; the shape that would have been
     * expensive, leaf-against-leaf, is the deviation above.
     */
    private static boolean exposed(BlockState owner, Direction dir, BlockState neighbor) {
        if (neighbor == null) {
            return true; // air-like: an empty section, or past the world top
        }
        if (!owner.canOcclude() && neighbor.getBlock() == owner.getBlock()) {
            // The far field's one deviation; see the javadoc above. It is
            // deliberately narrow: only a SEE-THROUGH owner, so every
            // occluding partial - slab against slab, stair against stair,
            // snow against snow - falls through to vanilla's own shape
            // subtraction and gets the exactly right answer there (a flat
            // snow field still emits nothing sideways, because a 2/16 strip
            // minus a 2/16 strip is empty). A field read before a virtual
            // call, on the cheapest branch of the method.
            return false;
        }
        return Block.shouldRenderFace(owner, neighbor, dir);
    }

    /**
     * Would this cell be stored ONLY because it is standing in a fluid,
     * i.e. is it a see-through block that is not itself the fluid and has
     * no air anywhere around it?
     *
     * <p>This is the whole of the Underwater Plants row's extraction half,
     * and it is a REFUSAL rather than an admission. Kelp, seagrass, sea
     * pickles, coral fans and any waterlogged plant now reach the store by
     * the ordinary occlusion rule, because vanilla draws them; a player who
     * would rather not pay for a kelp forest at layer-1 range turns the row
     * off and this drops exactly the cells that only water can see.</p>
     *
     * <p>The fluid itself is excluded by comparing the fluid's own legacy
     * block ({@code FluidState.createLegacyBlock()}) with the owner's, the
     * same test the water-surface rule below uses, so a submerged sheet of
     * water is never mistaken for a plant standing in one.</p>
     */
    private static boolean standsInFluidOnly(BlockState state, BlockState below,
            BlockState above, BlockState north, BlockState south,
            BlockState west, BlockState east) {
        if (state.canOcclude()) {
            return false;
        }
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty() || fluid.createLegacyBlock().getBlock() == state.getBlock()) {
            return false;
        }
        return !airLike(below) && !airLike(above) && !airLike(north)
                && !airLike(south) && !airLike(west) && !airLike(east);
    }

    /** A neighbour a ray can see daylight through: air, or nothing at all. */
    private static boolean airLike(BlockState neighbor) {
        return neighbor == null || neighbor.isAir();
    }

    /**
     * Exposure across ONE lateral chunk boundary, where the neighbour chunk
     * may not be loaded at all.
     *
     * <p>{@code known} is false when that chunk is absent, which is UNKNOWN
     * DATA rather than air. Answering it "air" (the pre-pre3 behaviour) is
     * what built the owner's "wall between the chunks in the ocean": in a
     * kelp or seagrass sea the band floor sits near the sea bed because
     * OCEAN_FLOOR ignores kelp, so the whole water column survives the cut,
     * and every interior water cell emits nothing because its neighbours
     * are water. The ONLY water and kelp faces that ever reached the shell
     * were therefore the ones facing an unknown neighbour, i.e. a flat
     * curtain standing exactly on the chunk plane.</p>
     *
     * <p>The rule is deliberately asymmetric by owner. For a see-through
     * owner (water, kelp, leaves, glass) an unknown neighbour means NO face:
     * a missing see-through face is nearly invisible, while a spurious one
     * is an opaque-looking wall. For an occluding owner it means air, i.e.
     * emit: a missing opaque face is a hole straight through the terrain at
     * a chunk seam, which is far worse than one redundant quad, and the seam
     * heals anyway the next time either side is re-extracted with its
     * neighbour present.</p>
     *
     * <p>{@code canOcclude()} is the right test HERE and only here, and the
     * distinction is worth keeping straight: this is not asking whether the
     * neighbour hides the face - there is no neighbour to ask - it is
     * choosing which way to be wrong about a face nobody can see. That is a
     * policy, and a boolean is the right shape for it.</p>
     */
    private static boolean exposedLateral(BlockState owner, Direction dir,
            BlockState neighbor, boolean known) {
        if (!known) {
            return owner.canOcclude();
        }
        return exposed(owner, dir, neighbor);
    }

    /**
     * Per-column band cut tops, index {@code (z << 4) | x}. WORLD_SURFACE
     * top in general; when that top block carries a non-empty FluidState
     * (javap: {@code public net.minecraft.world.level.material.FluidState
     * getFluidState();} / {@code public boolean isEmpty();}) the column
     * re-cuts from OCEAN_FLOOR — the design's under-water rule. Values
     * are the y OF the top block ({@code getHeight} returns
     * {@code getFirstAvailable - 1}, bytecode ip 99-103); an all-air
     * column yields {@code worldMinY - 1} and keeps nothing, which is
     * vacuously right (no cells exist there).
     */
    private static int cutTopAt(ChunkAccess chunk, LevelChunkSection[] sections,
            int minSectionY, int worldMinY, int x, int z) {
        // javap: ChunkAccess:
        //   public int getHeight(net.minecraft.world.level.levelgen.Heightmap$Types, int, int);
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        if (top >= worldMinY) {
            BlockState topState = stateAt(sections, minSectionY, x, top, z);
            if (topState != null && !topState.getFluidState().isEmpty()) {
                // OCEAN_FLOOR is never sent to the client
                // (Usage.LIVE_WORLD); this first query primes it,
                // once per chunk — see the class javadoc.
                top = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
            }
        }
        // THE GROUND UNDER TREES. WORLD_SURFACE's predicate is literally
        // Heightmap.NOT_AIR (javap, Types static init ip 26-38), so a
        // column under a canopy reports the TREETOP as its surface. The
        // band then keeps only bandOffset blocks below the leaves, and
        // the real forest floor - typically 15 or more blocks lower -
        // was never stored. Inside a forest every lateral neighbour is
        // also a tree column, so the neighbour minimum could not rescue
        // it either and the whole floor went missing. That is the
        // owner's "a lot of the missing areas are below trees".
        //
        // MOTION_BLOCKING_NO_LEAVES is the same heightmap with leaves
        // excluded, and it is Usage.CLIENT (javap, ip 116-130), so it is
        // sent with the chunk and costs nothing to query - no priming,
        // unlike OCEAN_FLOOR. Taking the MINIMUM is always safe because
        // the band is a LOWER bound only: a lower cut keeps strictly
        // more, so the canopy above still survives. It also picks up
        // tall-grass and flower columns, whose plants are NOT_AIR and
        // were lifting their own cut in exactly the same way.
        int solid = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return Math.min(top, solid);
    }

    /**
     * Per-column lowest kept y, index {@code (z << 4) | x}: the defect-B5
     * rule, {@code min(own, 4 neighbours) - bandOffset}, clamped to the
     * world bottom.
     *
     * <p><b>Why a strictly per-column cut left holes.</b> Across a step
     * between column A (top TA) and column B (top TB) the wall that is
     * genuinely exposed spans {@code [TB+1, TA]}, but a per-column band
     * kept only {@code y >= TA - bandOffset}, so every drop deeper than
     * {@code bandOffset + 1} lost {@code TA - TB - bandOffset - 1} blocks
     * of wall the player can see straight through. That is the owner's
     * "a lot of random holes in terrain", and the census predicted it in
     * so many words: the band keeps everything a distant camera can see
     * EXCEPT cliffs that open below the local surface line and cave
     * mouths deeper than 8 blocks.</p>
     *
     * <p><b>Why the neighbour minimum is exactly enough for a
     * HEIGHTFIELD.</b> A face of column c at height y is emitted only when
     * the block across it does not occlude, which for an AIR neighbour n
     * means {@code y > cutTop_n}. The floor is
     * {@code min_n(cutTop_n) - bandOffset}, below
     * every such y by at least bandOffset. So no air-exposed face is ever
     * cut, at any relief or depth, and the connected surface sheet is
     * complete. What the cut still removes is solid-against-solid
     * boundary, unreachable by any ray from outside once the sheet in
     * front of it is whole.</p>
     *
     * <p><b>Cost is paid only where relief exists.</b> A 1-block step
     * lowers the floor by 1 and that level's faces are solid against
     * solid, so a flat or gently sloped chunk stores nothing new; a
     * ravine-edge chunk stores the ravine wall it used to hide.</p>
     *
     * <p><b>What this rule is blind to, and what covers it.</b> The
     * argument above assumes the terrain is a HEIGHTFIELD: that a
     * neighbour's air begins above its cut top. Everywhere that is false —
     * an overhang, a ledge undercut, a cave mouth in a cliff face, a
     * tunnel — the air sits BELOW every surrounding column top, the
     * minimum cannot see it, and the cut slices it open. That is the
     * owner's pre4 report ("still seeing some on the sides of cliffs and
     * the main one, caves"). {@link #closeLeaks} runs immediately after
     * this and closes exactly those, by looking at the blocks instead of
     * at the column tops.</p>
     *
     * <p><b>The span guard, and the defect it closes.</b> Floors are
     * clamped to {@code spanFloor = max(worldMinY, surfaceMax - 255)} and
     * not merely to the world bottom. The highest cell any chunk emits is
     * the topmost block of some column, so it can never sit above
     * {@code surfaceMax}; clamping the floor 255 below that is what makes
     * every cell this stage keeps representable in {@link ShellCodec}'s
     * 8-bit yRel. Until pre6 only {@link #closeLeaks} applied that clamp
     * and this stage did not, so a chunk whose neighbour minimum reached
     * more than 255 blocks below its own peak - a tall mountain column
     * beside a deep ravine column - produced a shell that
     * the walk's top-clamp then truncated FROM THE BOTTOM,
     * throwing away precisely the cliff cells the minimum had just been
     * lowered to keep. The census sample never contained such a chunk (0
     * of 300 had a shell y-span over 255) so it was never measured, but
     * the sample has no mountain-peak biome in it at all, which is where
     * the shape lives. Costs nothing on any chunk that was already inside
     * the span.</p>
     *
     * <p><b>An unloaded neighbour</b> hands us no cut tops, and there is
     * no honest way to invent the terrain on the far side of the plane.
     * The estimate used is the one piece of real evidence available: the
     * MINIMUM cut top of our OWN CHUNK.
     *
     * <p>It used to be the minimum of the EDGE ROW facing that plane, and
     * that is the woodland-mansion half of the owner's pre14 P5. The
     * edge-row estimate rested on spatial coherence - "a drop that runs
     * across the seam almost always shows part of itself in our own edge
     * row" - which is true of natural terrain and maximally FALSE of a
     * BUILT structure standing on a chunk plane, because every column of
     * that row reports the structure's ROOF. A mansion chunk extracted with
     * one neighbour absent therefore took
     * {@code floor = roof - bandOffset} on that whole plane and cut the
     * cobblestone foundation wall from the bottom up: one side, cut from
     * the bottom, which is exactly what the owner reported. The chunk
     * minimum is never higher than the edge-row minimum, so this only ever
     * keeps MORE; it costs nothing at all on flat ground (every cut top is
     * the same number there), the measured price of the OLD estimate with
     * all four neighbours pretended absent was +7.6 cells mean / +45 p95 on
     * the census sample, and the delta from that to the chunk minimum is
     * bounded by the chunk's own relief over 16 columns per absent plane
     * and is unmeasured.
     *
     * <p>It is a mitigation and
     * not a fix: a cliff whose drop is entirely on the far side of the
     * plane still keeps its slit until that column is re-extracted with
     * the neighbour present — and note that inside a single session the
     * only path that extracts a column with a missing neighbour is the
     * frontier rescue, which also marks that column stored for the rest
     * of the session, so today the re-extraction does not come until the
     * next join. That gate lives in the dispatcher, not here.</p>
     */
    private static int[] computeBandFloors(LevelChunk chunk, LevelChunkSection[] sections,
            int minSectionY, int worldMinY, int spanFloor, int bandOffset,
            int[] westEdge, int[] eastEdge, int[] northEdge, int[] southEdge) {
        int[] tops = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                tops[(z << 4) | x] = cutTopAt(chunk, sections, minSectionY, worldMinY, x, z);
            }
        }
        // THE FALLBACK FOR AN ABSENT NEIGHBOUR: the lowest cut top in the
        // WHOLE of our own chunk, not the lowest in the edge row facing
        // that plane. An ALL-AIR column reports worldMinY - 1 and is
        // excluded, so one void column cannot drag the estimate to the
        // world bottom; MAX_VALUE survives only when the chunk is empty,
        // and then the Math.min below is a no-op and every column keeps its
        // own top, which is the right answer for a chunk with nothing in it.
        //
        // WHY THE EDGE ROW WAS THE WRONG ROW (the owner's pre14 P5, the
        // woodland-mansion half). The edge-row estimate rested on spatial
        // coherence: "a drop that runs across the seam almost always shows
        // part of itself in our own edge row". That is a statement about
        // NATURAL terrain, and a BUILT structure standing on a chunk plane
        // is its exact counter-example. A mansion chunk's whole edge row
        // reports the ROOF, so the estimate came back roof-high, the floor
        // came back roof - bandOffset, and the cobblestone foundation wall
        // that stands 40 blocks below it - the wall you see where the
        // structure meets the mountain - was cut from the bottom up. One
        // plane, one side, cut from the bottom: "one of the sides where it
        // meets with the mountain the bottom corner is just not there".
        // The chunk minimum is never higher than the edge-row minimum, so
        // this only ever keeps MORE, and it is bounded by our own relief.
        int ownFallback = Integer.MAX_VALUE;
        for (int i = 0; i < 256; i++) {
            if (tops[i] >= worldMinY) {
                ownFallback = Math.min(ownFallback, tops[i]);
            }
        }
        int degraded = (westEdge == null ? 1 : 0) + (eastEdge == null ? 1 : 0)
                + (northEdge == null ? 1 : 0) + (southEdge == null ? 1 : 0);
        if (degraded > 0) {
            farBandDegradedPlanes.add(degraded);
        }
        int[] floors = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int idx = (z << 4) | x;   // x is the low nibble: x-1 is idx-1,
                int own = tops[idx];      // z-1 is idx-16
                int lowest = own;
                lowest = Math.min(lowest, x > 0 ? tops[idx - 1]
                        : westEdge == null ? ownFallback : westEdge[z]);
                lowest = Math.min(lowest, x < 15 ? tops[idx + 1]
                        : eastEdge == null ? ownFallback : eastEdge[z]);
                lowest = Math.min(lowest, z > 0 ? tops[idx - 16]
                        : northEdge == null ? ownFallback : northEdge[x]);
                lowest = Math.min(lowest, z < 15 ? tops[idx + 16]
                        : southEdge == null ? ownFallback : southEdge[x]);
                // spanFloor as well as worldMinY: see the javadoc's span
                // note. Without it this stage could hand back a floor more
                // than 255 below the highest cell the chunk will emit, and
                // extract's top-clamp would then silently drop the deepest
                // cells - which are exactly the ones a cliff needs.
                floors[idx] = Math.max(spanFloor, lowest - bandOffset);
            }
        }
        return floors;
    }

    /**
     * Grow the band wherever the shell is still OPEN, so that no ray from
     * outside the terrain can cross the cut plane. This is the fix for the
     * two defects the heightfield rule of {@link #computeBandFloors}
     * cannot see: gaps on the sides of cliffs (F1) and gaps at caves (F2,
     * the owner's "main one").
     *
     * <h2>Why the neighbour minimum leaves them, in one sentence</h2>
     * It reasons about column TOPS, and both defects are air that lies
     * BELOW every surrounding top — an overhang or ledge undercut on a
     * cliff face, a cave mouth in a hillside, a tunnel, a shaft that does
     * not reach daylight. The tops are all high, nothing lowers the floor,
     * and the cut plane passes straight through the opening: the player
     * looks into the mouth and sees unstored void behind it.
     *
     * <h2>What this does instead: reach, then admit</h2>
     * Flood the see-through medium (anything that is not a FULL occluder,
     * {@code !isSolidRender()} — the same first question
     * {@code Block.shouldRenderFace} asks, so "what a face is exposed
     * against" and "what a ray travels through" cannot disagree) from
     * OUTSIDE the terrain inward, and every
     * time the flood touches a position that the band would cut, LOWER
     * that column's floor to admit it. Admitting a solid position stores
     * the wall the player was seeing through; admitting a see-through one
     * lets the flood carry on into the opening, so a cave mouth keeps its
     * real walls, its real floor and its real ceiling for as far as the
     * budget allows, which is what the owner's layer-1 bar asks for
     * ("just like its normally rendering"). Nothing that a ray cannot
     * reach is ever admitted, which is the whole reason this is cheap.
     *
     * <h2>Three seeds, and the domain they run in</h2>
     * <ol>
     * <li>The plane {@code y = max(WORLD_SURFACE) + 1} over our own chunk,
     * where every column is air by definition of the heightmap. A TALLER
     * NEIGHBOUR does not raise this plane: everything of ours above our own
     * surfaceMax is air and is already seeded here, so a ray arriving over
     * the neighbour joins the reachable set the moment it is over us.</li>
     * <li>For each LOADED lateral neighbour, our own edge-plane positions
     * that sit above THAT neighbour's cut top, because open sky on the far
     * side of a chunk plane is just as much "outside" as the sky overhead.
     * This is what lets a cave mouth sitting on a chunk boundary, or a
     * cliff face standing on one, be seen and therefore stored - and,
     * because a cut top is the top of the column and not the top of the
     * terrain, it is also what already handled a SKY-OPEN RAVINE crossing a
     * plane: the neighbour's ravine columns report the ravine FLOOR as
     * their cut top, so this seed offers our side of the plane all the way
     * down to it.</li>
     * <li>Each loaded neighbour's own open sky, one level above that
     * column's WORLD_SURFACE. This is the pre7 addition and the cross-chunk
     * fix: seeds 1 and 2 both reason about air ABOVE a column top, so
     * neither can see a TUNNEL - an opening with rock over it, whose cut
     * top is therefore above it everywhere. Seed 3 puts the flood into the
     * neighbour's ground and lets it travel UNDER the chunk plane into
     * ours.</li>
     * </ol>
     *
     * <p>The domain is our chunk plus every loaded neighbour in the 3x3, as
     * CONDUITS: the flood travels through them and never admits anything in
     * them, because those columns belong to their own chunk's shell and
     * their floors are not ours to lower. Each conduit is capped at one
     * level above its own per-column WORLD_SURFACE, so its empty sky is
     * never walked - see {@link LeakFlood#offer}, that cap is what keeps
     * the pass affordable.</p>
     *
     * <h2>The bound, and why a cavern cannot walk to bedrock</h2>
     * No column may be lowered below
     * {@code min(all 256 band floors) - }{@value #LEAK_BUDGET}, and never
     * below {@code max(WORLD_SURFACE) - 255}. The second guard matters as
     * much as the first: the shell's highest cell cannot sit above
     * {@code max(WORLD_SURFACE)}, so nothing THIS pass admits can push the
     * record past {@link ShellCodec}'s 8-bit yRel and trip the top-clamp
     * in the walk — and that clamp drops the DEEPEST cells, which
     * are precisely the ones closing the hole.
     *
     * <p>The bound is one PLANE for the whole chunk, at
     * {@code min_i floors[i] - }{@value #LEAK_BUDGET}, and that is the
     * pre15 change (see {@link #LEAK_BUDGET}). It used to be one plane PER
     * COLUMN, which made the cut-off rise with the rock standing over the
     * opening rather than with the terrain the flood came in through, and
     * that was the owner's pre14 P5: a cave mouth losing its floor one
     * block in, a notch in a tall cliff losing its bottom, a structure
     * losing the bottom corner where it meets a slope.</p>
     *
     * <p>Worst case, restated for the new bound: each column may fall from
     * its own floor to the plane, i.e.
     * {@code (floors[i] - min_i floors[i]) + }{@value #LEAK_BUDGET} levels,
     * and the first term is at most 255 because both ends are clamped to
     * {@code spanFloor}. So the ceiling is 256 x 319 levels, itself capped
     * by the 256-level representable span, and by
     * {@link ShellCodec#MAX_CELLS} in cells. The old per-column form's
     * ceiling was 256 x {@value #LEAK_BUDGET} = 16,384 levels, so the
     * PATHOLOGICAL chunk can now cost more; the everyday one cannot,
     * because the two bounds coincide wherever the chunk's relief is under
     * the budget, which is every chunk the census sampled. A column over a
     * huge cavern still stops at the plane whether the cavern is 20 blocks
     * deep or 200; what is left open there is a residual, counted below.
     *
     * <h2>THE pre6 DEFECT this pass also carries, and why nobody saw it</h2>
     * Until pre7 {@link LeakFlood#offer} packed its stack entry as
     * {@code column = (z << 4) | x} while {@code run()} unpacked it as
     * {@code x = (idx >> 4) & 15; z = idx & 15}. x and z were swapped on
     * every single expansion, so from the first hop onward the flood was
     * walking the chunk MIRRORED ACROSS ITS OWN DIAGONAL: it admitted the
     * transpose of the opening instead of the opening. On any geometry that
     * is not diagonally symmetric it therefore closed nothing and stored
     * the wrong cells while doing it.
     *
     * <p>It survived a release because the measurement was made against a
     * Python port of this algorithm that decodes x and z correctly, so the
     * port scored 0 leaks and the shipped code was never scored at all.
     * Re-scored now, with the defect reproduced verbatim, on the ORIGINAL
     * interior-only metric the pre5 note quotes: <b>the shipped pass leaves
     * 17 of 300 chunks leaking, against 20 of 300 for doing nothing at all
     * and 0 of 300 once the decode is fixed.</b> Desk-check, one 3-wide
     * cave mouth running east into a hillside: no flood 904 cells / 9 open
     * faces, <b>pre6 as shipped 904 cells / 9 open faces after spending
     * 1,166 probes</b>, decode fixed 1,012 cells / 0 open. Everything pre5
     * claimed for cave mouths and cliff faces was true of the design and
     * false of the build, which is the most likely single reason the owner
     * still saw holes in pre6.</p>
     *
     * <p>Standing lesson, worth more than the fix: a port that agrees with
     * the design is not evidence about the code. Prove the port against the
     * code, or measure the code.</p>
     *
     * <h2>Measured price (300 real 26.2 chunks, the census's own sample)</h2>
     * Two metrics, because the old one could not see this defect class.
     * <b>interior</b> is aperture_probe's original: flood the in-band air
     * from OUR sky, count faces from a reached position to anything the
     * band cut, chunk planes excluded because scoring them needs the
     * neighbours. <b>visible</b> runs the same idea with the reachability
     * flood over the whole 3x3 through the real medium, counts only faces
     * whose far side holds a REAL BLOCK, and restricts to blocks within 24
     * of their own column's surface - the ones a straight line from outside
     * plausibly reaches, as opposed to a cave that is merely
     * flood-connected through a winding tunnel.
     * <table border="1">
     * <caption>chunks leaking / 300, and stored cells</caption>
     * <tr><th>band rule<th>interior<th>visible<th>mean<th>p50<th>p95<th>max
     * <tr><td>heightfield only<td>20<td>121<td>564<td>552<td>941<td>1247
     * <tr><td>+ pre6 pass as shipped<td>17<td>113<td>597<td>552<td>1164
     *     <td>2497
     * <tr><td>+ pass A, decode fixed<td>0<td>85<td>610<td>563<td>1166
     *     <td>2399
     * <tr><td><b>+ pass B, 3x3 at 32 (this)</b><td><b>0</b><td><b>0</b>
     *     <td><b>703</b><td><b>618</b><td><b>1414</b><td><b>2417</b>
     * <tr><td>+ pass B, 3x3 at 64<td>0<td>0<td>757<td>623<td>1689<td>3993
     * </table>
     *
     * <ul>
     * <li><b>Cost of the whole change: +15.3% mean cells over a fixed
     * pass A (610 to 703), +9.8% p50, +21.3% p95, and the worst single
     * chunk barely moves (2399 to 2417).</b> Against
     * SHELL-CENSUS-2026-08.md's surface-band projection that is p50 ~0.96
     * KB to ~1.05 KB and the 512-radius disc ~1.08 GB to ~1.25 GB. Against
     * what players actually have today it is 597 to 703, +17.8%.</li>
     * <li><b>The 3x3 domain, not the 5-chunk cross, is what reaches zero.</b>
     * The cross leaves 26 of 300 visibly leaking at any budget, because the
     * limit there is the DOMAIN and not the depth. The diagonals cost 9.2k
     * extra probes and 17 mean cells.</li>
     * <li>Honesty about that zero: the scoring flood is also confined to
     * the 3x3, so the 3x3 rule is graded by a marker that cannot see
     * further than it can. It is a genuine zero for everything reachable
     * within one chunk of us and says nothing about two.</li>
     * <li>The obvious cheap alternative - seed every edge position whose
     * counterpart across the plane is see-through, with no reachability
     * test on the far side - reaches the same visible zero for
     * <b>+110.8% mean cells</b>. Paying to prove the far side is reachable
     * is what buys the other 95%.</li>
     * <li>Probes: mean 3.3k for pass A alone, <b>24.7k for both</b>, p95
     * 54.0k, worst chunk 83.2k. That figure already includes pass B
     * re-walking the centre, which is what the shipped code does too (see
     * {@link LeakFlood#beginCrossPass}). The conduit cap is worth 26% of
     * the pass on its own: a 3x3 conduit without it costs 37.1k probes for
     * bit-identical output.</li>
     * </ul>
     *
     * <h2>Known residuals, stated rather than hidden</h2>
     * <ul>
     * <li>A cave that runs deeper than {@value #LEAK_BUDGET} blocks below
     * the chunk's LOWEST band floor is stored down to the plane and open
     * past it. Visible only along the tunnel's own axis, from outside, at
     * layer-1 range. Before pre15 the same sentence read "below its own
     * column's band floor", which is what made it fire on a shallow cave
     * under a tall mountain.</li>
     * <li><b>The domain is the 3x3 in this code and 5 to 7 chunks in
     * practice.</b> {@code ExtractDispatch.neighborsLoaded} gates
     * extraction on the four LATERAL neighbours only, so at the moment a
     * column is walked its diagonals have usually not arrived - under a
     * distance-ordered send the two diagonals flanking the last lateral to
     * arrive are strictly further from the player than it is. The measured
     * table below is for a full 3x3; the 5-chunk cross row of that same
     * measurement leaves 26 of 300 chunks visibly leaking at ANY budget,
     * because there the limit is the domain and not the depth. Closing it
     * is a change in that class, not in this one: see
     * docs/FARFIELD-WAVES.md, "P4 AND P5 ANSWERED".</li>
     * <li><b>Reachability is ONE HOP.</b> The domain is the 3x3, so an
     * opening whose daylight is two or more chunks away along a roofed
     * tunnel is still cut. Desk-checked: a tunnel at y 50 under a surface
     * at y 80 whose only pit is in the ADJACENT chunk is closed (256 to 480
     * cells, floor 72 to 48, 24 open faces to 0); move the pit one chunk
     * further and all policies leave the same 24 faces open. Closing it
     * would need a domain that grows without bound, or per-chunk state this
     * class deliberately does not keep. A tunnel bore is a few blocks wide
     * and is sub-pixel at layer-1 range, which is why this is the residual
     * that was chosen to keep.</li>
     * <li>An absent lateral neighbour contributes neither a sky seed nor a
     * conduit, so an opening that is only visible from that side stays cut.
     * Same bargain as {@link #exposedLateral}, and it heals on
     * re-extraction with the neighbour present.</li>
     * <li>Reachability is a FLOOD, not a straight line, so it is a
     * conservative over-approximation of what a camera can see: a cave that
     * is connected to daylight only through a long winding tunnel counts as
     * reachable and is paid for. That is the direction the layer-1 bar asks
     * to err in ("prefer storing more over showing a hole"), and
     * {@value #LEAK_BUDGET} is what keeps the over-approximation bounded.
     * </li>
     * </ul>
     *
     * <h2>Cost on the game thread</h2>
     * {@code getHeight(WORLD_SURFACE)} for every column of every chunk in
     * the domain - 256 for ours plus 256 per loaded neighbour, and all of
     * them array reads because WORLD_SURFACE is {@code Usage.CLIENT} and
     * arrives with the chunk (see {@link #columnSurfaces}). Then at most
     * ONE {@code getBlockState} per position in the domain, because
     * {@link LeakFlood#offer} marks every position it looks at.
     *
     * <p>The span guard holds the level count to 257 for any chunk whose
     * band floors are themselves representable, and the domain to 9 chunks,
     * so the ceiling is 592,128 probes (the dimension height, 884,736, in
     * the pathological case the guard cannot shrink). The realistic figure
     * is one to two orders of magnitude lower and is measured below,
     * because the conduit cap keeps a neighbour's empty sky out of the walk
     * entirely and {@link #stateAt}'s {@code hasOnlyAir} fast path (three
     * branches, no container read) answers most of what is left. An ocean
     * chunk is the expensive shape: water does not occlude, so the flood
     * walks the whole water column - which is also exactly why an aquifer
     * opening in a cliff gets closed.</p>
     *
     * <h2>What a change here does to terrain already saved: NOTHING</h2>
     * This runs at EXTRACTION time and decides which cells reach the
     * record. A record written under the old rule does not contain the
     * cells the new rule would have kept, and no amount of re-meshing can
     * invent them - so a fix here heals a column only when that column is
     * extracted again. The mechanism that forces that without asking the
     * player to re-travel already exists and is not in this file:
     * {@code FarFieldConfig.farSaveSignature()} covers exactly the
     * extraction-time inputs, and a change to it makes
     * {@code FarFieldResidency} call
     * {@code ExtractDispatch.forgetExtractedColumns()}, after which every
     * column the client still holds is re-saved on the budget. Shipping a
     * band-rule change without also moving that signature ships the fix to
     * new terrain only.
     *
     * @param floors the heightfield floors, LOWERED IN PLACE
     */
    private static void closeLeaks(LevelChunkSection[] sections,
            int minSectionY, int worldMinY, int worldMaxY, int surfaceMax,
            int spanFloor, int[] floors,
            int[] westEdge, int[] eastEdge, int[] northEdge, int[] southEdge,
            LevelChunkSection[][] domain, int[][] domainCap) {
        // The lowest y at which EVERY column of OUR chunk is air: one above
        // our highest WORLD_SURFACE (the heightmap's own definition -
        // predicate NOT_AIR, javap Types clinit ip 30-41). Anything higher
        // is more empty sky to walk for nothing, and a TALLER NEIGHBOUR does
        // not raise it: everything in our chunk above our own surfaceMax is
        // air and is already seeded from this plane, so a ray that arrives
        // over the neighbour re-enters the reachable set the moment it is
        // over us.
        int seedY = Math.min(surfaceMax + 1, worldMaxY);
        if (seedY <= worldMinY) {
            return; // an all-air column set: nothing is stored, nothing leaks
        }
        // ONE depth budget per pass for the WHOLE chunk, measured from the
        // chunk's LOWEST heightfield band floor. See LEAK_BUDGET's javadoc
        // for why a per-column reference was the pre14 P5 defect: it made
        // the cut-off a plane that rose with the rock above the opening,
        // so a cave mouth lost its floor one block in, a notch in a tall
        // cliff lost its bottom, and a structure meeting a slope lost the
        // bottom corner. spanFloor is computed by the caller and shared
        // with computeBandFloors so both stages honour it. BOTH budgets are
        // measured from the floors as the heightfield stage left them, so
        // the second pass cannot compound the first pass's depth.
        int deepest = Integer.MAX_VALUE;
        for (int i = 0; i < 256; i++) {
            deepest = Math.min(deepest, floors[i]);
        }
        int ownLimit = Math.max(spanFloor, deepest - LEAK_BUDGET);
        int crossLimit = Math.max(spanFloor, deepest - CROSS_BUDGET);
        // The domain must cover everything already inside the band as well
        // as everything the budget could admit: a column the heightfield
        // stage already took below spanFloor (a ravine deeper than the
        // whole representable span) is stored, so the flood has to be able
        // to travel through it. ownLimit <= deepest <= floors[i] for every
        // column, so this is the same value the per-column form produced -
        // the walk's y range, the seen bitset and the packed stack entry
        // are unchanged by the rebase.
        int lowest = Math.min(seedY, ownLimit);
        LeakFlood flood = new LeakFlood(domain, domainCap, minSectionY,
                floors, ownLimit, lowest, seedY);
        // PASS A, our own chunk alone, at the full LEAK_BUDGET.
        // Seed 1: the all-air plane overhead.
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                flood.offer(LeakFlood.CENTRE, x, seedY, z);
            }
        }
        // Seed 2: our own edge planes, wherever the LOADED neighbour on the
        // far side is above its own cut top and so is open air. NOT made
        // redundant by pass B: a neighbour column whose cut top is below
        // its world surface has something between the two that the flood
        // may still not pass (a full-cube block sitting over a ledge), and
        // this seed reaches past it. Since pre11 the flood DOES walk a snow
        // layer, a slab, a path and a stair, because isSolidRender() is
        // false for all of them and a ray really does pass them - which is
        // one level per column of extra reach on a snowy surface and the
        // difference between storing and cutting a cave mouth with a slab
        // in its lip.
        seedFromNeighbourSky(flood, westEdge, 0, -1, seedY);
        seedFromNeighbourSky(flood, eastEdge, 15, -1, seedY);
        seedFromNeighbourSky(flood, northEdge, -1, 0, seedY);
        seedFromNeighbourSky(flood, southEdge, -1, 15, seedY);
        flood.run();
        // PASS B, the conduits, at CROSS_BUDGET.
        // Seed 3: each conduit's own open sky, one level above that column's
        // world surface. This is what lets a ray that entered the world
        // through a neighbour's ground travel under the chunk plane and into
        // our terrain - the cross-chunk fix.
        flood.beginCrossPass(crossLimit);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                flood.offer(LeakFlood.CENTRE, x, seedY, z);
            }
        }
        flood.seedConduits();
        flood.run();
        // Seed 2 is NOT repeated here. Every position it reaches was
        // already admitted in pass A at the LOOSER budget, so repeating it
        // could not lower a floor pass A did not; all it would add is the
        // ability to step from one of those positions into a conduit, and
        // a conduit is seeded from its own sky in any case.
    }

    /**
     * Seed the flood from one chunk plane. {@code edge} is that
     * neighbour's cut-top row (null when it is not loaded, in which case
     * there is nothing to seed and the plane keeps whatever the
     * absent-neighbour estimate gave it). {@code fixedX} / {@code fixedZ}
     * follow {@link #edgeCutTops}'s SHAPE — -1 names the axis walked — but
     * carry OUR edge coordinate rather than the neighbour's, so the west
     * plane is x 0 here where {@code edgeCutTops} read the neighbour's
     * x 15. The row index is the walked axis in both, so {@code edge[i]}
     * lines up with the position offered.
     */
    private static void seedFromNeighbourSky(LeakFlood flood, int[] edge,
            int fixedX, int fixedZ, int seedY) {
        if (edge == null) {
            return;
        }
        for (int i = 0; i < 16; i++) {
            int x = fixedX < 0 ? i : fixedX;
            int z = fixedZ < 0 ? i : fixedZ;
            // Strictly above the far side's cut top is open on that side.
            for (int y = Math.max(edge[i] + 1, flood.lowest); y < seedY; y++) {
                flood.offer(LeakFlood.CENTRE, x, y, z);
            }
        }
    }

    /**
     * The bounded flood of {@link #closeLeaks}, kept as a small object so
     * the recursion-free walk does not need an eleven-argument static
     * helper. Instantiated only from {@link #extractCaptured}, so it loads no
     * earlier than the extractor itself runs.
     *
     * <p>Within one pass every position is evaluated at most once:
     * {@link #seen} is set the first time {@link #offer} looks at it, and a
     * column's floor only ever moves DOWN, so a second look could not admit
     * anything a first look did not. That is what bounds a pass at one
     * {@code getBlockState} per position in its domain, and the whole of
     * {@link #closeLeaks} at two passes' worth.</p>
     *
     * <p>One object runs both passes. Pass A sees only the centre (every
     * other {@link #slot} is -1) at the full {@value #LEAK_BUDGET};
     * {@link #beginCrossPass} then opens the conduits, tightens the budget
     * to {@value #CROSS_BUDGET} and clears {@link #seen}.</p>
     */
    private static final class LeakFlood {

        /**
         * Domain slots are 3x3 positions, {@code (dz + 1) * 3 + (dx + 1)},
         * so the centre is 4 and a lateral step is a change of 1 or 3. A
         * slot whose chunk is absent from the domain (not loaded, or a
         * diagonal the caller chose not to include) holds a null in
         * {@link #sections} and a -1 in {@link #slot}.
         */
        static final int CENTRE = 4;

        /** Per-3x3-slot section arrays; null means not in the domain. */
        private final LevelChunkSection[][] sections;
        /** Per-3x3-slot per-column conduit cap; unused for CENTRE. */
        private final int[][] cap;
        /**
         * 3x3 slot to compact domain index, or -1 when absent. Every
         * conduit reads -1 during pass A and its real index from
         * {@link #beginCrossPass} onwards, which is how one object runs
         * both passes over one {@link #seen} allocation.
         */
        private final int[] slot = new int[9];
        /** {@link #slot} as the domain really is, restored by pass B. */
        private final int[] slotAll = new int[9];
        /** How many chunks the domain actually holds (1..9). */
        private final int width;
        private final int minSectionY;
        /** The band floors, lowered in place as the flood admits cells. */
        private final int[] floors;
        /**
         * The pass's hard bottom for ADMISSION, one value for the whole
         * chunk; a leak below it stays open (bounded). Per column until
         * pre15 - see {@link ShellExtractor#LEAK_BUDGET}.
         */
        private int limit;
        /**
         * Levels still available to admit, across BOTH passes and all 256
         * columns. See {@link ShellExtractor#ADMIT_LEVEL_BUDGET}: it is
         * what keeps the chunk-wide {@link #limit} from costing more in the
         * worst case than the per-column one it replaced.
         */
        private int levels = ADMIT_LEVEL_BUDGET;
        /** Lowest y the domain covers: {@code min(limit, floors)}. */
        final int lowest;
        /** Highest y the domain covers: our own all-air seed plane. */
        private final int seedY;
        /**
         * One bit per domain position,
         * {@code (((y - lowest) * width) + slot) * 256 + (z << 4 | x)}.
         */
        private final long[] seen;
        /** Positions reached and see-through, still to be expanded. */
        private final IntArrayList stack = new IntArrayList(1024);

        LeakFlood(LevelChunkSection[][] sections, int[][] cap,
                int minSectionY, int[] floors, int limit, int lowest,
                int seedY) {
            this.sections = sections;
            this.cap = cap;
            this.minSectionY = minSectionY;
            this.floors = floors;
            this.limit = limit;
            this.lowest = lowest;
            this.seedY = seedY;
            int w = 0;
            for (int p = 0; p < 9; p++) {
                // A conduit needs BOTH its blocks and its surface heights;
                // the centre needs only its blocks.
                boolean present = sections[p] != null
                        && (p == CENTRE || cap[p] != null);
                slotAll[p] = present ? w++ : -1;
                // Pass A is our chunk alone.
                slot[p] = p == CENTRE ? slotAll[p] : -1;
            }
            this.width = w;
            // (levels x chunks x 256) bits. The dimension height bounds
            // the level count at 384 and the domain at 9 chunks, so the
            // worst case is 884,736 bits = 108 KB; the span guard holds
            // the everyday case to 257 levels over the 5-chunk cross,
            // which is 41 KB.
            this.seen = new long[(((seedY - lowest + 1) * w * 256) + 63) >> 6];
        }

        /**
         * Look at one position from outside. Admits it into the band when
         * the band was cutting it and the budget allows, then queues it if
         * a ray could carry on through it.
         *
         * <p>Admission happens for the CENTRE only. A conduit position is
         * traversed and never stored: those columns belong to the
         * neighbour's own shell and its floors are not ours to lower.</p>
         */
        void offer(int p, int x, int y, int z) {
            if (y < lowest || y > seedY) {
                return; // outside the bounded domain
            }
            int s = slot[p];
            if (s < 0) {
                return; // that chunk is not in the domain
            }
            int column = (z << 4) | x;
            if (p == CENTRE) {
                if (y < floors[column]) {
                    // Two bounds, and both are budgets rather than
                    // geometry: how deep this chunk may go at all, and how
                    // many levels it may buy in total. Either one refusing
                    // leaves this position open, which is the residual
                    // closeLeaks's javadoc counts.
                    if (y < limit || floors[column] - y > levels) {
                        farBandLeakRefusals.increment();
                        return;
                    }
                    // The band was cutting a position a ray can reach. Lower
                    // the floor so this cell and everything above it in the
                    // column is stored.
                    levels -= floors[column] - y;
                    floors[column] = y;
                }
            } else if (y > cap[p][column]) {
                // THE CONDUIT CAP, and the reason this pass is affordable.
                // A neighbour is walked from one level above its own world
                // surface downwards and never through its empty sky: that
                // sky reaches nothing our own seed plane and seed 2 do not
                // already reach, and walking it would multiply the pass by
                // the neighbourhood's relief.
                return;
            }
            int idx = (((y - lowest) * width) + s) * 256 + column;
            long bit = 1L << idx; // Java shifts mask to 6 bits: the word offset
            int word = idx >> 6;
            if ((seen[word] & bit) != 0) {
                return;
            }
            seen[word] |= bit;
            BlockState state = stateAt(sections[p], minSectionY, x, y, z);
            if (state == null || !state.isSolidRender()) {
                // The ray continues, and so does the flood. The predicate is
                // isSolidRender(), i.e. "the occlusion shape is the whole
                // cube" - the SAME first question Block.shouldRenderFace
                // asks (javap ip 0-17: the neighbour's face occlusion shape
                // identity-compared against Shapes.block()), so what a ray
                // travels through and what hides a face are one rule and
                // cannot drift apart. A FULL occluder stops the flood and is
                // stored, which is the closure.
                stack.add((p << 24) | ((y - lowest) << 8) | column);
            }
        }

        /**
         * Open the conduits, swap in the cross pass's tighter budget, and
         * forget every position pass A looked at.
         *
         * <p>The forgetting is deliberate and is not free (it re-walks the
         * centre, about 3.3k of the pass's 24.7k mean probes). Keeping the
         * marks would be ALMOST safe - pass A ran with the looser budget,
         * so anything it marked it also expanded as far as pass B ever
         * could - but not quite: pass A had the conduits closed, so a
         * centre position it marked was never expanded ACROSS a chunk
         * plane, and pass B would then skip it and never make that step.
         * A cave that leaves our chunk and comes back would be lost. The
         * cost of clearing is small, the case is hard to reason about, and
         * clearing is also exactly the shape that was measured.</p>
         */
        void beginCrossPass(int crossLimit) {
            System.arraycopy(slotAll, 0, slot, 0, 9);
            this.limit = crossLimit;
            Arrays.fill(seen, 0L);
        }

        /**
         * Seed every conduit at its own open sky. One position per column
         * is enough: the flood walks down from there, and the region above
         * is empty by the heightmap's definition.
         */
        void seedConduits() {
            for (int p = 0; p < 9; p++) {
                if (p == CENTRE || slot[p] < 0) {
                    continue;
                }
                int[] caps = cap[p];
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        offer(p, x, Math.min(caps[(z << 4) | x], seedY), z);
                    }
                }
            }
        }

        /** Expand until nothing reachable is left unadmitted. */
        void run() {
            while (!stack.isEmpty()) {
                int packed = stack.removeInt(stack.size() - 1);
                int p = packed >>> 24;
                int y = ((packed >> 8) & 0x1FF) + lowest;
                int column = packed & 255;
                int x = column & 15;
                int z = column >> 4;
                offer(p, x, y - 1, z);
                offer(p, x, y + 1, z);
                step(p, x, y, z, -1, 0);
                step(p, x, y, z, 1, 0);
                step(p, x, y, z, 0, -1);
                step(p, x, y, z, 0, 1);
            }
        }

        /**
         * One lateral step, following the move into the adjacent domain
         * chunk when it leaves this one. A step that would leave the 3x3
         * altogether is dropped, which is the one-hop bound this pass is
         * built on.
         */
        private void step(int p, int x, int y, int z, int dx, int dz) {
            int nx = x + dx;
            int nz = z + dz;
            if (nx >= 0 && nx <= 15 && nz >= 0 && nz <= 15) {
                offer(p, nx, y, nz);
                return;
            }
            int px = (p % 3) + (nx < 0 ? -1 : nx > 15 ? 1 : 0);
            int pz = (p / 3) + (nz < 0 ? -1 : nz > 15 ? 1 : 0);
            if (px < 0 || px > 2 || pz < 0 || pz > 2) {
                return; // outside the 3x3 neighbourhood
            }
            offer(pz * 3 + px, nx & 15, y, nz & 15);
        }
    }

    /**
     * The 16 cut tops of a neighbour chunk's edge row facing this chunk,
     * or null when that neighbour is not loaded. Exactly one of
     * {@code fixedX} / {@code fixedZ} is a real coordinate; the other is
     * -1 and names the axis walked.
     */
    /**
     * Per-column WORLD_SURFACE of one chunk, index {@code (z << 4) | x}, or
     * null when that chunk is not loaded.
     *
     * <p>Unlike OCEAN_FLOOR this costs nothing to read: WORLD_SURFACE is
     * {@code Usage.CLIENT} (javap, {@code Heightmap$Types} clinit ip 26-41)
     * so the server sends it with the chunk and {@code getHeight} is an
     * array read with no priming scan. 256 of them per chunk in the domain.
     * </p>
     *
     * <p>The value is the y OF the highest non-air block
     * ({@code getFirstAvailable - 1}, bytecode ip 99-103), so
     * {@code surface + 1} is the lowest position in that column that is air
     * by the heightmap's own definition - which is exactly the conduit
     * seed and the conduit cap in {@link LeakFlood}.</p>
     */
    private static int[] columnSurfaces(ChunkAccess chunk) {
        if (chunk == null) {
            return null;
        }
        int[] out = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                out[(z << 4) | x] =
                        chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            }
        }
        return out;
    }

    /**
     * The per-column CONDUIT CAP of one neighbour, index
     * {@code (z << 4) | x}: the highest y at which {@link LeakFlood} may
     * walk that column, or null when the chunk is not loaded.
     *
     * <p>One above its WORLD_SURFACE, which is the lowest position in the
     * column that is air by the heightmap's own definition. Everything
     * higher is that neighbour's empty sky, and walking it would buy
     * nothing: our own seed plane already covers the air above OUR columns,
     * and {@link #seedFromNeighbourSky} already covers our side of each
     * plane above the neighbour's cut top.</p>
     *
     * <p>An ALL-AIR column is the exception and reports
     * {@code worldMinY - 1}. Capping it at {@code worldMinY} would make the
     * one column that really is open sky all the way down the one column
     * the flood may not enter, so it is uncapped instead. Not reachable in
     * the overworld, where every column has bedrock under it, but the
     * extractor is not overworld-only.</p>
     */
    private static int[] conduitCaps(ChunkAccess chunk, int worldMinY) {
        int[] caps = columnSurfaces(chunk);
        if (caps == null) {
            return null;
        }
        for (int i = 0; i < 256; i++) {
            caps[i] = caps[i] < worldMinY ? Integer.MAX_VALUE : caps[i] + 1;
        }
        return caps;
    }

    private static int[] edgeCutTops(ChunkAccess neighbor, int minSectionY, int worldMinY,
            int fixedX, int fixedZ) {
        if (neighbor == null) {
            return null;
        }
        LevelChunkSection[] sections = neighbor.getSections();
        int[] tops = new int[16];
        for (int i = 0; i < 16; i++) {
            tops[i] = cutTopAt(neighbor, sections, minSectionY, worldMinY,
                    fixedX < 0 ? i : fixedX, fixedZ < 0 ? i : fixedZ);
        }
        return tops;
    }
}
