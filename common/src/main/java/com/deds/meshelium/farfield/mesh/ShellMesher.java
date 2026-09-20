/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.farfield.mesh;

import com.deds.meshelium.farfield.FarFieldConfig;
import com.deds.meshelium.farfield.store.ShellCodec;
import com.deds.meshelium.terrain.EncodedSectionMesh;
import com.deds.meshelium.terrain.GreedyMesher;
import com.deds.meshelium.terrain.QuadFacing;
import com.deds.meshelium.terrain.SectionMeshEncoder;
import com.deds.meshelium.terrain.TerrainQuad;
import com.deds.meshelium.terrain.TerrainVertex;

import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Far field: one cached shell (a chunk column's exposed cells,
 * {@link ShellCodec.Shell}) to per-SECTION encoded meshes in the exact GPU
 * format the near field uses (16-byte vertices, 64-byte quads, the seven
 * facing buckets plus the translucent prefix — {@code TerrainVertexCodec}/
 * {@code SectionMeshEncoder}). Pure CPU over primitives + the by-value
 * {@link SpriteUvResolver.ResolvedPalette}: no vanilla types, so it runs
 * on the far-field IO thread (FARFIELD-CODEBASE-SEAM.md section 6.4 — the
 * IO thread never touches a vanilla object).
 *
 * <p>The one non-primitive dependency is {@link FarFieldConfig}, read ONCE
 * per shell column into {@link Style}. That is deliberate and it is safe:
 * the config is pure loader/GSON/JDK code with an explicit
 * any-thread contract in its own class javadoc, and it never so much as
 * names {@code Minecraft}. Reading it here rather than taking it as an
 * argument keeps {@link #mesh}'s signature - and therefore
 * {@code FarFieldResidency}, which this change does not own - untouched.
 *
 * <h2>What each cell becomes</h2>
 * <b>One quad per quad of the block's own baked model</b>, gated by the
 * cell's face mask: {@link SpriteUvResolver} copies a block's real
 * {@code BakedQuad}s into primitive form ({@link SpriteUvResolver.Quads})
 * and {@link #emitQuads} draws the ones the mask lets through. There is no
 * per-shape branch here and there is no per-shape branch in the resolver.
 *
 * <p><b>That replaced a per-family rule stack</b>, which is the owner's L0
 * for this build: "we dont need a case by case basis here we need a solid
 * plan", and "all 3d models should work and just use the regular minecraft
 * ones". pre2 through pre10 grew a CROSS kind (two hand-built blades for
 * anything whose model touched no block boundary), a DECAL kind (one
 * up-facing sheet, carved out of CROSS when ground flowers stood up like
 * bushes), a per-entry bounding BOX with a UV crop, and a tolerance pinned
 * between iron bars and fire. Each was right for the block that prompted it
 * and wrong for the next one: a <b>wall torch</b> is a 2x10x2 cuboid tilted
 * 22.5 degrees, so no face of it lands on a cell plane, so it was a CROSS,
 * so it drew as the owner's "streched torch texture taking up a x shape". A
 * bounding box cannot carve a stair's notch and a representative sprite per
 * direction cannot carry a grass block's tinted side overlay. Emitting the
 * model's quads gets all of them for the same reason: it is not
 * approximating the shape at all.</p>
 *
 * <p>Quads are emitted in section-local coordinates (the codec's [-8,24)
 * domain trivially holds [0,16]), carrying:</p>
 * <ul>
 * <li><b>sprite</b>: the quad's own per-vertex ATLAS uv, unpacked from
 *     {@code BakedQuad.packedUV} - so a snow layer's declared
 *     {@code [0,14,16,16]} side, a torch's {@code [7,6,9,16]} and a
 *     stair's per-element rectangles arrive exactly as the model author
 *     wrote them, where the box model had to re-derive a crop from the
 *     bounding box and get four of them right by construction. Merged runs
 *     tile via the material byte's repeat pair, the near-field greedy
 *     convention;</li>
 * <li><b>tint</b>: the record's sampled biome colour for the palette entry
 *     AT THIS CELL — since pre19 a direct read of the record's 16x16 CELL
 *     field, i.e. vanilla's own {@code calculateBlockTint} value for that
 *     block ({@link #cellTint}) — applied only to faces the MODEL
 *     marks tinted, else white (see the tint section below);</li>
 * <li><b>material</b>: the face's own alpha cutoff, taken from vanilla's
 *     {@code ChunkSectionLayer} for that baked quad — solid 0, cutout 2,
 *     translucent 0 plus the prefix — and overridden by exactly one player
 *     row, Solid Leaves ({@link Style#cutoffFor}). That layer is vanilla's
 *     own per-quad measurement of the quad's OWN uv window, which is why
 *     nothing else is allowed to second-guess it;</li>
 * <li><b>light</b>: the record's real per-cell sky and block levels when
 *     it carries a light plane ({@link Style#lightFor}), which is what
 *     layer 1 ships with since pre12; otherwise synthesized flat light,
 *     blockLight 8 / skyLight 248 stored — {@code clampLight(0)} and
 *     {@code clampLight(255)}, i.e. full skylight and no blocklight.
 *     <b>Either way the number is a vanilla LIGHTMAP COORDINATE, not a
 *     colour</b>: {@code rawLevel} is {@code level * 16 + 8}, byte for
 *     byte what {@code VanillaMeshDecoder} writes for near-field geometry
 *     ({@code LightCoordsUtil.pack}'s 0..240 plus the half-texel
 *     centring), and {@code terrain.mesh} samples it out of
 *     {@code GameRenderer.lightmap()} — the player's OWN lightmap texture,
 *     which is where their Brightness slider, the day/night curve, night
 *     vision, the darkness effect and the dimension's ambient colour all
 *     live ({@code LightmapRenderStateExtractor} reads
 *     {@code Options.gamma()} into {@code BrightnessFactor}, ip 262-328;
 *     {@code lightmap.fsh} applies it). So the far field honours "whatever
 *     you have set in game" by construction and cannot drift from the near
 *     field at the handover band. The Distant Water row then fades
 *     the SKY channel out with depth below a water surface
 *     ({@link #submergedLight});</li>
 * <li><b>shading</b>: vanilla's directional face shade baked into the
 *     vertex color's alpha (up 1.0, down 0.5, north/south 0.8, east/west
 *     0.6 — section 4.3's table); {@code premultiplyColor} folds it into
 *     RGB at encode time. Ambient occlusion is added on top by the Smooth
 *     Lighting row, into the RGB rather than the alpha so the merge can
 *     see it ({@link #ambientRgb}); with that row off every corner is 1.0
 *     and these quads merge at SL-off-or-better rates (section 2.3). At
 *     its Full step the same row also blends the LIGHTMAP per corner
 *     ({@link #smoothBlend}), which is the other half of what vanilla
 *     calls smooth lighting;</li>
 * <li><b>facing bucket</b>: the {@link QuadFacing} of the face — far
 *     sections get GPU face culling like every near section.</li>
 * </ul>
 *
 * <h2>Per-position appearance (pre15, the owner's P1)</h2>
 * Vanilla derives some of a block's appearance from the POSITION it stands
 * at, which a stored shell has no memory of. One of those is now
 * reproduced here and it costs no stored byte: {@code state.getOffset(pos)},
 * the pseudorandom displacement that stops a field of grass standing in
 * rows ({@code ModelBlockRenderer.tesselateBlock} ip 39-46 translates every
 * vertex by it). {@link #positionSeed} is {@code Mth.getSeed} transcribed
 * from the bytecode and {@link #offsetXZ} / {@link #offsetY} are the two
 * {@code offsetType} lambdas; the two per-block magnitudes come off the
 * palette ({@code SpriteUvResolver.offsetMagnitudes}), so nothing vanilla
 * is touched off the game thread. It is armed only when the record knows
 * its own chunk position ({@code ShellCodec.Shell.hasOrigin()}); a shell
 * with no origin draws exactly as it did before, never with chunk (0,0)'s
 * pattern stamped on it.
 *
 * <p>The other position-keyed input in 26.2 is the weighted model VARIANT
 * (38 blockstates, 75 rows), and since pre18 it is reproduced too, also
 * for zero stored bytes: {@link #variantDraw} transcribes vanilla's
 * {@code setSeed(getSeed(pos)); nextInt(totalWeight)} chain and
 * {@code SpriteUvResolver.enumerateVariants} holds one quad table per
 * draw. It rides the Block Variants row (default ON) because it is the
 * one accuracy feature that costs quads on FLAT ground - see
 * docs/unreleased/farfield/FARFIELD-WAVES.md, "R4 AND R5 ANSWERED (S1)", for the arithmetic
 * and the merge price, and "P1, P6, P7, P8 ANSWERED" for the census.</p>
 *
 * <h2>Meshing with the 3x3 (pre18, S1 - the owner's R5)</h2>
 * {@link #mesh(ShellCodec.Shell, SpriteUvResolver.ResolvedPalette,
 * NeighborColumn[])} takes the eight neighbouring columns and loads
 * their edge cells beside this record's own, so a face ON a chunk plane
 * is answered from the record on the other side of it: ambient
 * occlusion and contact shading probe across the seam, the fluid corner
 * average and flow read the neighbour's real heights, a face's light
 * finds its donor there, and a water face learns whether it faces
 * glass. Zero stored bytes; a missing neighbour leaves that side at
 * exactly the pre18 behaviour. See {@link Apron}.
 *
 * <h2>Winding (the silent killer, derived not assumed)</h2>
 * Vanilla block quads are wound CCW viewed from their facing side, and
 * the decoder derives facing as the right-handed cross
 * {@code (v1-v0) x (v2-v0)} pointing ALONG the facing
 * (VanillaMeshDecoder.deriveFacing javadoc; the mesh shader's
 * {0,1,2}/{2,3,0} split + CW front + BACK cull consume exactly that
 * order). Every face table below is desk-checked against that rule —
 * the cross of its first three vertices points out of the face; see the
 * per-face comments. The fluid-height edit below moves vertices only
 * along Y within their own face, so it cannot flip a winding.
 *
 * <h2>Tint (pre2's A1/A3 fix, pre8's blending, pre19's cell field)</h2>
 * WHERE a tint applies is a model property resolved per face
 * ({@code SpriteUvResolver.ResolvedPalette.tinted}); WHICH colour is biome
 * data sampled at EXTRACTION time and carried in the record
 * ({@code ShellCodec} format 3's per-entry tint field). This class just
 * multiplies:
 * <pre>
 *   rgb = tinted[face] ? cellTint(shell, entry, x, z) : 0xFFFFFF
 * </pre>
 * Since pre19 {@link #cellTint} is a DIRECT read of a 16x16 cell field
 * whose node {@code (x, z)} is vanilla's own
 * {@code ClientLevel.calculateBlockTint} at that block - so the far field
 * reproduces the near field's colour field exactly, including the
 * {@code biomeBlendRadius} ramp at a biome boundary, and two adjacent
 * records agree at the seam because the stored function is a pure
 * function of world position. Records written before pre19 carry a CORNER
 * grid and keep the bilinear read; see {@link #cellTint}'s javadoc for
 * both conventions and for the price the merge pays. A record written at
 * grid side 1 - every format-2 cache, and the Per Chunk setting - takes
 * the uniform fast path and behaves exactly as it did.
 * When {@code shell.paletteTint} is null — a format-1 record written
 * before the tint table existed, or a shell built with no world to sample
 * — the entry's out-of-world default
 * ({@code ResolvedPalette.fallbackTint}) stands in, so an old cache draws
 * plains-green grass rather than the gray of the pre-fix build. Nothing
 * is rejected and nothing renders garbage; the colour sharpens as chunks
 * are re-visited and re-written at format 2.
 *
 * <h2>Fluids: translucent, and at the right height (A4 and A5)</h2>
 * pre1 drew every fluid face OPAQUE at FULL block height. Both are fixed:
 * <ul>
 * <li><b>Transparency</b> is real, not simulated. A face whose vanilla
 *     layer is TRANSLUCENT (water; also ice, stained glass, slime) is
 *     emitted with {@code translucent = true}, which
 *     {@link SectionMeshEncoder} puts in the section's translucent PREFIX.
 *     The drawer already draws far prefixes with no change: far residents
 *     are stamped {@code orphanedAtMillis}, so their snapshot
 *     {@code [19]} is 1 and they own their region slot ({@code [18] >= 0}),
 *     which is exactly the wave-11 retained-translucent pre-pass's filter
 *     ({@code TerrainDrawer.drawTranslucentInner}: {@code d[o+19] == 0 ||
 *     d[o+4] <= 0 || d[o+18] < 0} skips). That pre-pass runs
 *     farthest-first and already walks every far slot each frame, so the
 *     added per-frame cost is a frustum test, a sort key and a draw record
 *     per far section that CONTAINS translucent geometry — not per far
 *     section. The alpha itself comes from the sprite:
 *     {@code block/water_still.png} carries a tRNS alpha of 180 (0.706) on
 *     every palette entry and the translucent pipeline blends
 *     SRC_ALPHA / ONE_MINUS_SRC_ALPHA verbatim from vanilla's
 *     TRANSLUCENT_TERRAIN. Our vertex format has no alpha channel at all
 *     ({@code premultiplyColor} folds the shade byte into RGB), so this is
 *     the only mechanism available and it is also vanilla's.</li>
 * <li><b>Height.</b> Vanilla renders a fluid at {@code MAX_FLUID_HEIGHT =
 *     0.8888889f} (= 8/9, {@code FlowingFluid.getOwnHeight} is
 *     {@code getAmount() / 9.0f} and a source's amount is 8) unless the
 *     block ABOVE holds the same fluid, in which case 1.0
 *     (FluidRenderer.getHeight bytecode ip 0-46). pre1 drew 1.0 always,
 *     which is the "little gap between it and the normal water" the owner
 *     reported. The shell has no per-cell fluid level, so the rule is
 *     re-derived from the face mask: a fluid cell whose UP face is EXPOSED
 *     is a surface cell (the extractor exposes a transparent owner's face
 *     only against true air, so nothing but air can be above it) and gets
 *     8/9; a fluid cell with no UP face has fluid or a solid above and
 *     stays at 1.0. That reproduces vanilla exactly for the case that
 *     matters. The one divergence: a fluid cell with a NON-fluid,
 *     non-air block directly above (ice, a lily pad) renders 1.0 where
 *     vanilla renders 8/9 — a 1/9-block sliver, hidden under the thing
 *     that caused it.</li>
 * </ul>
 * <p><b>The corner heights are vanilla's since pre15 (the owner's P8).</b>
 * The paragraph that used to stand here said "vanilla averages the top
 * plane's four corners against the neighbouring columns' heights, so a
 * shoreline dips; the far field emits a flat 8/9 plane", and while every
 * fluid cell in the game was 8/9 that was a cosmetic approximation.
 * <b>Format 4 made it a hole.</b> Vanilla hides a fluid-to-fluid lateral
 * face on the fluid TYPE alone ({@code FluidRenderer.isNeighborSameFluid},
 * javap ip 0-11 - it compares {@code getType()} and nothing else) and the
 * extractor hides the same face by the same test
 * ({@code LiquidBlock.skipRendering}). Vanilla may do that because
 * {@code calculateAverageHeight} makes the two tops MEET; we drew flat
 * slabs at up to nine different heights with the wall between them culled,
 * so every 1/9 step in a stream was an open slot into the water body.
 * {@link #fluidCorner} is vanilla's own weighted average, answered from
 * the record's own cells ({@link #fluidField}), and it is only consulted
 * for a fluid SURFACE cell - a cell whose four corners all come back at
 * its own height, which is every cell of an open ocean, keeps the merged
 * surface bucket and its one rigid drop.</p>
 *
 * <p>One honest approximation is left in the fluid path: the shortened
 * side faces keep their full sprite V range over a quad 1/9 shorter, so
 * the water texture is squashed 11% vertically on the surface ring only.</p>
 *
 * <h2>Underwater shading: why a distant ocean was two-tone (pre8 item J2)</h2>
 * The owner, three releases running: "the oceans just seem to be two main
 * colours that dont work right", then "oceans still draw as layered sheets
 * with a dark sea bed beneath", then the fix itself - "normal ocean is dark
 * blue because the ground is dark, im sure you can just find some way to
 * cheat this based on distance from the surface."
 *
 * <p><b>He is describing vanilla's light engine, and it is not a cheat at
 * all.</b> What makes a real deep ocean read as one dark blue mass is that
 * skylight is absorbed on the way down, so the sea bed under it is unlit.
 * Chased through the 26.2 merged jar:</p>
 * <ul>
 * <li>{@code BlockBehaviour.getLightDampening(state)} returns
 *     {@code isSolidRender() ? 15 : propagatesSkylightDown() ? 0 : 1}
 *     (javap, ip 0-22);</li>
 * <li>{@code LiquidBlock.propagatesSkylightDown} is a bare
 *     {@code iconst_0 / ireturn}, and water is not {@code solidRender}
 *     (its chain calls {@code noCollision()}, which clears
 *     {@code canOcclude}), so <b>water dampens by exactly 1</b>;</li>
 * <li>{@code LightEngine.getOpacity} is {@code Math.max(1,
 *     state.getLightDampening())} (ip 0-8) and
 *     {@code SkyLightEngine.propagateIncrease} subtracts that opacity from
 *     the source level at every step (ip 128-137).</li>
 * </ul>
 * So the air above the water is 15, the topmost water block is 14, each
 * one below it is one less, and everything is 0 at fifteen blocks. Read
 * per BED rather than per water block: a bed with {@code d} water blocks
 * over it has {@code 15 - d} in the block directly above it, which is 15
 * at the waterline, 12 at a three-block shore and 0 from fifteen blocks
 * down. That block above the bed is precisely the position
 * {@code ShellExtractor.sampleCellLight} samples for the Real Light plane,
 * so {@link #submergedLight} computes the number Real Light would have
 * STORED - for zero bytes, zero quads and zero game-thread work.
 *
 * <p><b>The fog path is a different thing and is deliberately not copied.</b>
 * {@code FogRenderer.getFogType} reads {@code Camera.getFluidInCamera()} and
 * {@code WaterFogEnvironment.isApplicable} answers only for
 * {@code FogType.WATER}, i.e. the fog colour
 * ({@code EnvironmentAttributes.WATER_FOG_COLOR}, default
 * {@code 0xFF050533}) and its {@code -8 .. 96} block range apply when the
 * CAMERA is submerged. Looking at an ocean from above, vanilla applies none
 * of it: the water surface is one translucent quad and the darkness under
 * it is light, not fog. Reproducing the fog curve here would have invented
 * a look; reproducing the light curve reproduces vanilla.
 *
 * <p><b>What it costs.</b> Nothing on disk (the format does not move, and
 * every shell already saved is fixed the moment it is re-meshed), nothing
 * in quads, nothing on the game thread. The mesher pays one scan of the
 * palette kinds per column - which is where a land chunk stops, because a
 * palette with no non-emitting {@link SpriteUvResolver#KIND_FLUID} entry
 * cannot have a water surface, and a lava lake is deliberately excluded by
 * its own emission ({@link #darkening}) - and, for a column that does hold
 * water, two integer
 * passes over its cells plus two 256-int scratch arrays, on the IO thread.
 * On the census's mean 703-cell surface-band record that is about 1,400
 * array reads, against the 24.7k block probes {@code ShellExtractor
 * .closeLeaks} already spends building it.
 *
 * <p><b>What it costs the merge, which is the only real price.</b> Light is
 * two of {@link GreedyMesher}'s five affine channels. A merged horizontal
 * plane lies at ONE y, and with the ocean at one level the depth over it is
 * constant, so a 16x16 sea bed top is still one quad and the surface sheet
 * is still one quad. What stops merging is a VERTICAL run of underwater
 * side faces, whose depth changes by one per block: a wall inside the
 * 14-block band under the surface splits into one strip per block. The
 * bound is a full 16-wide, 14-tall underwater wall going from 1 quad to 14,
 * i.e. +13 quads (832 B of arena) on that plane; a gentle sea floor with
 * one-block steps pays +0, since a one-block wall never merged vertically
 * anyway. Below 15 blocks of depth every level is 0 again and the merge
 * returns.
 *
 * <p><b>Limitations, stated rather than found later.</b> A column whose
 * topmost water block has something ON it - a lily pad, frogspawn, a block
 * overhanging the water - has no UP-exposed fluid cell, so the column has
 * no surface and is not shaded at all (pre8's look, never a wrong one). A
 * frozen ocean is not in that list and never was: ice REPLACES the top
 * water block, so the column genuinely has no water surface and vanilla
 * draws none either. Nor, since pre10, is a column whose top water block is
 * occupied by a PLANT: {@code ShellExtractor} stores the fluid such a block
 * stands in as a second cell at the same position, so a kelp-topped column
 * has a real UP-exposed fluid cell again and both this shading and the
 * surface sheet come back (the owner's "kelp removes the water surface in
 * lod if its in the top water block"). A water cell that is deep but has open AIR beside it (the lip
 * of a waterfall) is darkened where vanilla would sample the bright air.
 * And a cell's single light value is the one its UP face wants, so a
 * submerged wall face is one level brighter than vanilla - the same
 * approximation, in the same direction, that the Real Light plane already
 * makes. (On a record that HAS a plane the per-face donor map fixes the
 * general case of that - see {@link #airLight} - but this derivation is
 * the no-plane path, where there are no donor bytes to read.)
 *
 * <p><b>Why this is the Distant Water row and not a competing one (pre13
 * N4).</b> The owner asked for "an option for this that just looks like
 * stock minecraft", and the answer is that this IS stock: the curve above
 * is {@code SkyLightEngine}'s own subtraction, not a look. So the row that
 * selects it is named for its target rather than for its mechanism, and
 * ON means "apply the light engine's rule to every record, from its saved
 * plane where it has one and from this derivation where it does not".
 * pre12 locked the row instead, on the argument that a stored plane makes
 * it a no-op; that is true per RECORD and false for a world, because a
 * column taken at the chunk-receive seam is saved with no plane and only
 * gets one when the catch-up sweep re-extracts it. Desk-checked over the
 * four biomes in docs/unreleased/farfield/FARFIELD-WAVES.md's N4 table, the two settings agree
 * to the byte on a planed record and differ by up to 64 of 255 on one
 * channel on an un-planed one - a bright ocean beside a dark one, which is
 * the only thing this row can draw that vanilla never shows.</p>
 *
 * <h2>Merging translucent and short quads</h2>
 * {@link GreedyMesher} refuses translucent quads by design (it must not
 * disturb vanilla's back-to-front prefix order) and refuses non-integral
 * planes and non-unit spans, which a shortened fluid face is. Taken
 * literally that would cost a far ocean-surface section 256 unmerged
 * water quads (16 KB) where one merged quad (64 B) does the job. So this
 * mesher merges in INTEGRAL, OPAQUE space and transforms afterwards:
 * quads are built full-height and non-translucent, merged, then the fluid
 * surface set is lowered and the translucent set is flagged. Buckets are
 * merged separately so the transform never has to guess:
 * <pre>
 *   FIXED    [0] opaque, full height   [1] translucent, full height
 *   DYNAMIC  one per (palette row, translucency) for a fluid SURFACE,
 *            keyed (row &lt;&lt; 1) | trans, transform = drop to that row's
 *            own fluid height
 *   DYNAMIC  one per (palette row, quad index, translucency) for the
 *            shifted plane of a partial block, keyed by {@link #shiftKey},
 *            transform = a rigid plane shift
 * </pre>
 * The shifted-plane buckets are pre5's; the fluid-surface ones became
 * dynamic at format 4, when a record started carrying a fluid's real
 * {@code level}. Before that there was exactly one fluid height in the
 * game as far as the far field was concerned (the source's 8/9), so two
 * fixed buckets carried every fluid top; now a source at 8/9 and a 3/9
 * flowing film are different palette ROWS and must not merge into one
 * rectangle that then takes one drop.
 * <b>Why the split is required and not merely tidy:</b> a water column's
 * side faces run from the sea floor to the surface, and only the TOP cell
 * of that column shortens. If surface and body faces shared a bucket the
 * sweep would merge them into one tall rectangle whose top edge belongs to
 * both. Splitting makes vertical runs inside a surface bucket provably
 * length 1: two fluid cells that are both UP-exposed cannot be vertically
 * adjacent (the lower one's UP neighbour would be the upper one, which is
 * not air). {@link #lowerFluidSurface} re-checks the one-block span anyway
 * and leaves anything else at full height rather than stretch it.
 *
 * <p>Reordering is safe here in a way it is not near: a far section's
 * prefix is never resorted (the resort path is mesh-identity keyed and
 * far residents have no mesh identity — {@code TerrainResidency
 * .onTranslucentResort} looks up {@code resident.get(mesh)}), so the
 * prefix order this class emits is the order that draws forever. Within
 * one far section that means emission order, not distance order: a water
 * surface is one sheet and does not self-overlap, but stacked ice or
 * glass in one far section can blend in the wrong order. Accepted at
 * range.</p>
 *
 * <h2>Shape, and where the approximation went</h2>
 * Since store format 4 a shell stores a block STATE per cell, so the quads
 * are that state's: a stair's {@code facing}/{@code half}/{@code shape},
 * {@code snow}'s {@code layers}, a fence's connections, a wall torch's
 * {@code facing}, a grass block's {@code snowy} and a fluid's
 * {@code level} all survive extraction now. Both the SHAPE and its
 * ORIENTATION are vanilla's. A record written before format 4 still draws
 * default states and reads exactly as it did; rotation appears on terrain
 * saved from that build on.
 *
 * <p>The fluid HEIGHT is the one case where wrong-shape is not survivable -
 * a flowing edge at 1/9 drawn at 8/9 is a solid block of water where
 * vanilla has a film - and it is called out in the fluid section below.</p>
 *
 * <h3>The exposure consequence, and it is settled</h3>
 * A quad's {@code cullface} is the model author's own statement of which
 * neighbour may hide it, and the cell's face mask is
 * {@code ShellExtractor}'s statement of which neighbours do. The two are
 * the same question asked from the two ends, so {@link #emitQuads} needs no
 * reconciliation rule: a snow layer's four sides carry cullfaces and vanish
 * against a neighbour that covers them, while its UP face carries none and
 * always draws. What the extractor had to change with this work is the
 * OCCLUSION rule that fills the mask - {@code canOcclude()} is a boolean
 * where vanilla asks a per-state SHAPE - and that is argued in
 * {@code ShellExtractor}'s own class javadoc, which is now the single
 * authority on what a stored face means.
 *
 * <h3>What the merge costs, measured</h3>
 * A full cube's baked cullface quad is an integral unit face, so the
 * overwhelming majority of the world merges exactly as it did: a flat 16x16
 * grass top is ONE quad, not 256. Two cases move:
 * <ul>
 * <li>a quad that spans the cell but sits off its boundary (snow's top at
 *     2/16, a slab's at 8/16, a nudged coplanar overlay) keeps its merge
 *     through the dynamic shift buckets ({@link #shiftKey},
 *     {@link #addMergedShifted}) - pre5's trick, now keyed per QUAD, which
 *     is what lets a model own two such planes;</li>
 * <li>everything with real inset geometry - a cross blade, a torch's walls,
 *     a fence post's sides, a petal's stems - falls through the merge's
 *     pass-through list, exactly as the box model's inset faces did.</li>
 * </ul>
 *
 * <h3>Quad count against the box model, on the real 26.2 assets</h3>
 * Full cubes are quad-for-quad identical (one cullface quad per direction,
 * no unculled quads), and they are most of every chunk. The deltas:
 * grass_block <b>+1 per exposed side</b> (the tinted overlay element, the
 * owner's L5); a cross <b>4 to 4</b> ({@code block/cross} declares
 * {@code north} and {@code south} on each of two elements, which is what
 * {@code emitCross} was hand-building); a floor torch 5 to 6; a wall torch
 * 4 to 6 and correct; a stair 5 to 8-12 and correct; a rail or lily pad 1
 * to 2; a pink petal or wildflower <b>1 to 14, 22, 36 or 42</b> by
 * {@code flower_amount} (the multipart STACKS models: 1 draws
 * {@code flowerbed_1} = 14 quads, 2 adds {@code flowerbed_2} = 8, 3 adds
 * {@code flowerbed_3} = 14, 4 adds {@code flowerbed_4} = 6) and a leaf
 * litter <b>1 to 2, except 4 at {@code segment_amount} 3</b> where the
 * multipart's {@code 2|3} and {@code 3} selectors both apply
 * ({@code template_leaf_litter_*} is one zero-thickness sheet with an up
 * and a down face and no stems). Per chunk that is about <b>+0 to +10 quads
 * on flat plains</b>, <b>+150 (+9.6 KB of arena) on a hilly grass chunk</b>
 * from the overlay, and <b>+2,460 (+157 KB) on a cherry grove with 60
 * four-flower petal cells</b> - the one case that really pays, bounded to
 * the three ground-cover blockstates, and the honest price of drawing the
 * model.
 *
 * <h2>Two-block plants: which half a cell draws (pre9 defect)</h2>
 * A record written before store format 4 stores block NAMES, and both
 * halves of a tall grass share one (a format-4 record stores {@code half}
 * and this whole mechanism sits idle).
 * {@link SpriteUvResolver} resolves the upper half into its own palette ROW
 * ({@code ResolvedPalette.upperRow}); {@link #upperHalves} decides per cell
 * which row to read, from the record's own cells and with no stored bit.
 * Everything downstream is unchanged - the row substitutes for the entry
 * index at every appearance read, so an upper half picks up its own quad
 * table and nothing else moves.
 *
 * <h2>Merging (general)</h2>
 * Reuses {@link GreedyMesher#merge} directly — its input contract is a
 * plain {@code List<TerrainQuad>}, fully separable from vanilla compile
 * output, so no dedicated far merger is needed. Quads that are not unit
 * faces on an integral plane (a decal's sheet, a partial block's side)
 * are handed to it anyway and come back untouched through its
 * pass-through list, which is cheaper than a second list here and keeps
 * one code path. With
 * constant corners (flat light, no ambient occlusion) every rectangle is
 * trivially affine, so the merge runs at its flat-lighting best (18-30%+,
 * PERFORMANCE.md via the seam dossier section 2.3).
 *
 * <p><b>Three rows can spend some of that back, and only where they do
 * something.</b> Colour and light are two of the five channels the affine
 * test compares, and ambient occlusion rides in the colour. A quad
 * otherwise carries ONE colour and ONE light pair on all four corners, so
 * the plane it pins is constant and the merge is bit-identical to pre7's
 * wherever the neighbouring cells agree - which is every chunk inside one
 * biome, and every flat lit surface. It breaks exactly where the value
 * moves: across a biome boundary (Colour Blending), under an overhang or
 * beside a torch (Real Light), and against a block that stands over a
 * face (Smooth Lighting at its Full step). All three are the geometry the
 * setting exists to make look right, and none of them touches a chunk
 * that has nothing of the kind in it - an open plain's top plane is one
 * quad with all three on. <b>What they cost where they DO fire is not
 * measured</b>: the only number in the tree is the near field's 18.0
 * percent merge with flat lighting against 5.9 percent with vanilla's
 * ambient occlusion, it was gathered on a different kind of geometry, and
 * two of the three channels are now varying at once. See the {@code Style}
 * javadoc's "Smooth lighting (pre12 M2b)" for the riser case that refutes
 * the cheerful reading, and read
 * {@code GreedyMesher.quadSummary()}'s far figure rather than either
 * guess.</p>
 *
 * <p>Run unconditionally:
 * the near-field config gate exists because near merges risk parity;
 * far accuracy is waived and the quad reduction is the point. Note the
 * merge's cost telemetry ({@code GreedyMesher.costSummary()}) then
 * includes far sections meshed on the IO thread, and now counts up to
 * four merge calls per far section rather than one, plus one more per
 * distinct PARTIAL palette entry the section holds (pre5's dynamic
 * buckets, typically zero and at most a handful) — an accepted, flagged
 * stat mix. The QUAD telemetry beside it is not mixed: every merge here
 * goes through {@code GreedyMesher.mergeFar}, so
 * {@code GreedyMesher.quadSummary()} reports the far field's own quads in
 * and out separately from the near field's.
 */
public final class ShellMesher {

    /** Stored block-light byte: {@code clampLight(0)} = the clamp floor. */
    static final int BLOCK_LIGHT_RAW = 8;
    /** Stored sky-light byte: {@code clampLight(255)} = sky 15 (+8 centring). */
    static final int SKY_LIGHT_RAW = 248;

    /**
     * The synthesized pair, packed {@code (sky << 8) | block}: full
     * daylight, no block light. Both channels ride through the emitters as
     * ONE int so that adding real lighting did not add two parameters to
     * every geometry method in this class.
     */
    private static final int FLAT_LIGHT = (SKY_LIGHT_RAW << 8) | BLOCK_LIGHT_RAW;

    /** Vanilla's full sky level; {@link #submergedLight} counts down from it. */
    private static final int SKY_LEVEL_MAX = 15;

    /**
     * {@link WaterColumns} sentinel: this column carries no water surface,
     * or the depth of its water body is unknown. {@code Integer.MIN_VALUE}
     * rather than a world y, so no legal y can collide with it.
     */
    private static final int NO_WATER = Integer.MIN_VALUE;

    /** Alpha cutoff index 2 = 0.5, vanilla's CUTOUT_TERRAIN threshold. */
    private static final int CUTOFF_CUTOUT = 2;
    /** Alpha cutoff index 0 = no discard at all. */
    private static final int CUTOFF_NONE = 0;

    /**
     * Vanilla's {@code FluidRenderer.MAX_FLUID_HEIGHT}, javap-confirmed as
     * the literal {@code 0.8888889f} the renderer loads (ip 167) and equal
     * to {@code FlowingFluid.getOwnHeight}'s {@code amount / 9.0f} for a
     * source block (amount 8).
     *
     * <p>Before format 4 this was the ONLY fluid height the far field could
     * draw, because a shell stored a block name and every water cell
     * resolved the source state. It is now the value of one particular
     * state and the mesher reads
     * {@link SpriteUvResolver.ResolvedPalette#fluidHeight} per palette ROW;
     * this constant survives as the fallback for a fluid whose height
     * cannot be read at all.</p>
     */
    static final float FLUID_SURFACE_HEIGHT = 0.8888889f;

    /** Span tolerance for the one-block guard in {@link #lowerFluidSurface}. */
    private static final float SPAN_EPSILON = 1.0e-4f;

    // Directional shade in the color's alpha channel (premultiplied into
    // RGB by TerrainVertexCodec.premultiplyColor): round(shade * 255).
    private static final int SHADE_UP = 255;    // 1.0
    private static final int SHADE_DOWN = 128;  // 0.5
    private static final int SHADE_NZ = 204;    // 0.8 (north/south)
    private static final int SHADE_EW = 153;    // 0.6 (east/west)
    /**
     * Cross blades take no directional shade. Vanilla's own answer, not a
     * shortcut: every element of {@code block/cross.json} and
     * {@code block/tinted_cross.json} carries {@code "shade": false}
     * (26.2 surfaces it as {@code BakedQuad$MaterialInfo.shade()}), so a
     * near-field tuft is drawn at full brightness and a far one matches.
     */
    private static final int SHADE_CROSS = 255; // 1.0

    /**
     * What the strength slider's 100 means on the EDGES step, as a
     * fraction of a vertex's brightness removed at a corner where BOTH of
     * its edges are occluded. Half, so the top of the slider lands on the
     * same order as vanilla's own darkest ambient-occlusion step without
     * ever reaching black: an edge vertex loses half of this, so 100 gives
     * 0.75 on an edge and 0.5 in a corner.
     *
     * <p>The FULL step has no constant of its own: there the slider is a
     * straight fraction of {@link #AMBIENT_STEP} per occluder, so 100 is
     * exactly vanilla's 1.0 / 0.8 / 0.6 / 0.4 and nothing here is
     * invented. That is why {@code DEFAULT_CONTACT_SHADE} is 100 and not
     * a taste call - see its javadoc.</p>
     */
    private static final float CONTACT_SHADE_MAX = 0.5f;

    /** Slider top; the row is an integer percent so the screen can show it. */
    static final int CONTACT_SHADE_FULL = 100;

    /**
     * How close a merged corner has to be to the cell lattice to count as
     * on it. A merged rectangle's corners are exact integers by
     * construction ({@code GreedyMesher.eligibleCell} refuses anything
     * else), so this only has to survive float arithmetic, not model
     * geometry.
     */
    private static final float PLANE_EPSILON = 1.0e-3f;

    // Fixed merge buckets; see the class javadoc's table. The dynamic
    // shifted-plane buckets live in Section.boxBuckets keyed by shiftKey,
    // and the fluid-surface ones in Section.surfaceBuckets keyed by the
    // palette ROW, because since format 4 two water cells in one section
    // can stand at two different heights and must not merge into one
    // rectangle that then takes one drop.
    private static final int BUCKET_OPAQUE = 0;
    private static final int BUCKET_TRANS = 1;
    private static final int BUCKETS = 2;

    /** One meshed section of a shell column: absolute section Y, geometry. */
    public record SectionMesh(int sy, EncodedSectionMesh mesh) {}

    /**
     * One meshed shell column. {@code sections} is ordered by ascending
     * {@code sy}; empty when every cell was skipped. {@code skippedCells}
     * counts cells whose palette entry did not resolve
     * ({@code farMeshSkippedCells}); {@code fluidQuads} counts fluid unit
     * faces emitted BEFORE the greedy merge, which is what pre1 counted,
     * so the series stays comparable across this change.
     *
     * <p><b>Counter naming note for the coordinator:</b>
     * {@code FarFieldResidency} still calls this
     * {@code farWaterOpaqueQuads}. Since fluid faces now go into the
     * translucent prefix, that name is a misnomer; the number is still
     * the useful one (how much fluid geometry the far field carries) and
     * the counter lives in a file this change does not own.</p>
     */
    public record Result(SectionMesh[] sections, int skippedCells, int fluidQuads) {}

    private ShellMesher() {
    }

    /**
     * Per-section scratch: the two fixed merge buckets plus the dynamic
     * fluid-surface and partial-box ones, all allocated on first use. Most
     * sections only ever touch bucket 0, and a shell column can span
     * several sections, so eagerly allocating a list apiece would be a
     * wasted allocation per section on the IO thread's hot path — and the
     * two maps are more that a land chunk with no partial blocks in view
     * never pays.
     */
    private static final class Section {
        @SuppressWarnings("unchecked")
        final List<TerrainQuad>[] buckets = new List[BUCKETS];

        /**
         * The DYNAMIC buckets: quads that span the whole cell on both
         * in-plane axes but sit OFF its boundary, keyed by
         * {@link #shiftKey}, merged at the integral plane and translated
         * back per quad ({@link #addMergedShifted}). Null until such a quad
         * appears in the section, which for most of the world is never —
         * the whole point of the lazy fixed buckets above.
         */
        Int2ObjectOpenHashMap<List<TerrainQuad>> boxBuckets;

        /**
         * The FLUID SURFACE buckets: quads of a cell whose fluid has air
         * above it, so its top plane and the top edge of its sides drop to
         * the row's own {@link SpriteUvResolver.ResolvedPalette#fluidHeight}.
         * Keyed by {@code (row << 1) | translucent} - per ROW because the
         * drop is a property of the state, and a source at 8/9 merging with
         * a 3/9 flowing film would put both at one height. Null until a
         * fluid surface appears in the section, which for every land chunk
         * in the world is never.
         */
        Int2ObjectOpenHashMap<List<TerrainQuad>> surfaceBuckets;

        /**
         * {@code yRel - ly} for this section: add a section-local y and
         * get the {@link Occluders} row. Set once when the section is
         * created, because every quad in it shares one.
         *
         * <p>This is what lets the shading read a column-wide occupancy
         * grid from geometry that is built in section-local coordinates.
         * Before pre12 the grid was per SECTION and an occluder one block
         * above a section boundary was invisible, which drew a faint
         * horizontal line across the horizon every sixteen blocks - the
         * y-axis twin of the chunk grid the seam rule exists to
         * prevent.</p>
         */
        int yBase;

        List<TerrainQuad> bucket(int index) {
            List<TerrainQuad> list = buckets[index];
            if (list == null) {
                list = new ArrayList<>(64);
                buckets[index] = list;
            }
            return list;
        }

        List<TerrainQuad> boxBucket(int key) {
            if (boxBuckets == null) {
                boxBuckets = new Int2ObjectOpenHashMap<>(4);
            }
            // Explicit get/put rather than computeIfAbsent: fastutil
            // declares that method for BOTH java.util.function.IntFunction
            // and Int2ObjectFunction, so a lambda is ambiguous there.
            List<TerrainQuad> list = boxBuckets.get(key);
            if (list == null) {
                list = new ArrayList<>(64);
                boxBuckets.put(key, list);
            }
            return list;
        }

        List<TerrainQuad> surfaceBucket(int key) {
            if (surfaceBuckets == null) {
                surfaceBuckets = new Int2ObjectOpenHashMap<>(2);
            }
            List<TerrainQuad> list = surfaceBuckets.get(key);
            if (list == null) {
                list = new ArrayList<>(64);
                surfaceBuckets.put(key, list);
            }
            return list;
        }
    }

    /**
     * The layer-1 appearance settings, resolved ONCE per shell column, plus
     * the two decisions that depend on them. One object rather than three
     * booleans threaded through nine signatures, and one config read rather
     * than one per cell.
     *
     * <h2>THE ALPHA TEST IS VANILLA'S (pre13 N2/N3, and the Small Detail
     * row is gone with it)</h2>
     * <p><b>A quad's {@code ChunkSectionLayer} is already vanilla's
     * measurement of that quad's OWN uv window.</b> javap, 26.2 merged jar:
     * {@code FaceBakery.bakeQuad} calls
     * {@code computeMaterialTransparency(material, uvs)} (ip 20-27), which
     * is {@code sprite.contents().computeTransparency(minU/16, minV/16,
     * maxU/16, maxV/16)} (ip 11-77); the result is handed to
     * {@code BakedQuad$MaterialInfo.of(...)}, whose first act is
     * {@code layer = ChunkSectionLayer.byTransparency(transparency)} (ip
     * 0-4). {@code NativeImage.computeTransparency} walks exactly the
     * texels in that window and sets {@code hasTransparent} on
     * {@code alpha == 0} and {@code hasTranslucent} on anything between
     * (ip 155-197), and {@code byTransparency} maps translucent to
     * TRANSLUCENT, transparent to CUTOUT, and neither to SOLID.
     *
     * <p><b>So CUTOUT means "vanilla found transparent texels inside this
     * quad's own crop", and dropping its alpha test necessarily paints
     * texels vanilla discards.</b> That is what the Small Detail row's
     * tight-crop step did, and it is the owner's pre13 N2/N3: "those leaves
     * on the ground still arent right. also wild flowers are the same. im
     * assuming the pink ones are the same aswell." The step's premise was
     * that a strict sub-rectangle is "opaque by construction". Measured on
     * the real 26.2 textures over the exact crops:
     * {@code leaf_litter_1} {@code uv [0,0,8,8]} is 42% opaque,
     * {@code leaf_litter_3} {@code [8,8,16,16]} 50%,
     * {@code pink_petals_1} 23%, {@code wildflowers_2} 19%. And "painted"
     * means painted BLACK: {@code terrain.frag}'s
     * {@code ALPHA_CUTOFFS[0]} is {@code 0.0}, so {@code color.a < 0.0} is
     * never true, the opaque pass ignores alpha and writes RGB, and every
     * fully transparent texel of all three sprites is {@code (0,0,0,0)}
     * (139, 153 and 195 of them respectively). A distant leaf pile drew as
     * a black quarter-tile with a few leaves in it. The full
     * census over every model a blockstate reaches (22,833 faces, 429
     * skipped and counted) found <b>883 faces across 164 models</b> in that
     * state - the three ground covers, sea pickles, hanging signs, sculk
     * sensor tendrils, lanterns, small dripleaf, spore blossom, tripwire
     * hooks and the rest.
     *
     * <p><b>And the case the row was written for does not need it.</b> The
     * owner's original "torches do not render" was diagnosed as the alpha
     * test erasing {@code block/torch}'s crop down the mip chain - but in
     * 26.2 that crop is 100% opaque over all six of
     * {@code template_torch}'s faces ({@code uv [7,6,9,16]} 20/20 texels,
     * {@code [7,13,9,15]} and {@code [7,6,9,8]} 4/4), so
     * {@code computeTransparency} returns {@code Transparency.NONE} and
     * FaceBakery bakes the torch <b>SOLID</b>. It never reaches the CUTOUT
     * branch at all, with the row on or off. Same for iron and copper bars,
     * fence and wall posts and lanterns' opaque crops: 12,740 faces over
     * 1,090 models are tight AND already SOLID, i.e. the row was a no-op
     * for every one of them.
     *
     * <p>The row's other step went in pre12 for the same shape of reason
     * (M6, "lod kelp ... clearly green and above the water"):
     * {@code MipmapGenerator.generateMipLevels} rescales each level with
     * {@code scaleAlphaToCoverage} so a cutout sprite keeps its
     * 0.5-coverage all the way down (ip 148-166 and 405-421), so a lowered
     * threshold only ever ADDED coverage vanilla had deliberately
     * discarded. With both steps gone the row has nothing left to do and
     * {@link Style} no longer reads it. <b>Config follow-up, not done
     * here:</b> {@code FarFieldConfig.isWired(L1, SMALL_DETAIL)} must
     * become false, {@code farMeshSignature()} must stop hashing it (a flip
     * would otherwise spend a whole ring reload to change nothing) and
     * {@code MesheliumFarLayerScreen}'s row must go with it.
     *
     * <p>Cost: NOTHING. One 2-bit material field per quad stops being
     * overwritten; no storage, no extra quads, no CPU.</p>
     *
     * <h2>SOLID LEAVES: the near field's rule, expressed on shell data</h2>
     * {@code SectionBuildTap.solidifyCutouts} rewrites a cutout UNIT
     * BOUNDARY face to cutoff 0 and leaves crosses, insets and decals
     * alone, "the way Fast graphics draws leaves". The same rule here is
     * one line, because the shell already knows which entries are boxes and
     * which of those fill their cell: a {@code KIND_BLOCK} entry that is
     * not {@code partial}. Crosses, decals and every partial box are
     * excluded by construction - they reach {@link #cutoffFor} with
     * {@code partialBox} true and keep vanilla's 0.5 - which is what makes
     * this the ONLY row that may now override the baked layer, and only
     * where vanilla's own Fast graphics would.
     *
     * <h2>LIGHTING</h2>
     * Three steps, and only the third costs anything.
     * <ul>
     * <li>{@link FarFieldConfig#LIGHT_FLAT} is pre7's behaviour: sky 15 and
     *     block 0 on every vertex, so a cave mouth is as bright as a
     *     hilltop.</li>
     * <li>{@link FarFieldConfig#LIGHT_GLOW} adds the palette entry's OWN
     *     light emission to its own faces ({@code getLightEmission()},
     *     resolved per block name and never stored). A distant torch,
     *     lantern, glowstone block, campfire or lava pool then reads as
     *     lit. It costs one array read per cell and nothing on disk, and it
     *     cannot break the greedy merge because the value is constant per
     *     palette entry.</li>
     * <li>{@link FarFieldConfig#LIGHT_REAL} uses the record's per-cell
     *     light plane when it has one and falls back to GLOW when it does
     *     not - which is every record saved before the row was turned on,
     *     so switching it on repaints the world as you re-travel it rather
     *     than all at once.</li>
     * </ul>
     * <p>{@link FarFieldConfig.Control#WATER_LOOK} (the Distant Water row)
     * is a fourth, orthogonal step that rides on top of the first two and
     * is IDLE under the third: see the class javadoc's "Underwater
     * shading". It is off in {@link #waterDepth} whenever
     * {@link #realLight} holds, because the plane the third step stored
     * already carries the light engine's own answer for that record.
     * <b>Idle is not superseded</b>, and pre12 made exactly that mistake
     * (see {@code FarFieldConfig.isSuperseded}): the two paths are meant
     * to be the SAME function - the plane is what the engine held, and
     * {@link #submergedLight} re-derives that same number from the
     * record's own geometry - so on a planed record the row cannot change
     * a pixel, while on a record saved WITHOUT a plane it is the only
     * thing that darkens the water at all. Both populations exist in
     * every live world, because a column taken at the chunk-receive seam
     * is saved with no plane until the catch-up sweep re-extracts it.</p>
     *
     * <p><b>"The same function" is a claim that has to be MAINTAINED, and
     * pre13 shipped with it false (the owner's O1).</b> A stale kind test
     * in {@link #waterColumns}'s bed scan made the derived half measure a
     * kelp column's depth as ONE, so the two populations drew a pale
     * ocean and a dark one side by side - "random chunks are just solid
     * lighter color ... deep in the ocean around kelp seems to be the
     * worst offender". The predicate is now {@link #solidCube}, one
     * definition shared with {@link #occluders}, and the equivalence is
     * asserted rather than argued: {@code MesheliumFarFieldTest}'s
     * ocean leg meshes one synthetic kelp column twice, once with its
     * light plane and once with the plane stripped, and requires the two
     * encoded section meshes to be byte-identical. If a future change
     * splits the two halves again, that leg is what says so.</p>
     *
     * <h2>Smooth lighting (pre12 M2b), and the two refusals it retires</h2>
     * The owner, restating what he wanted: "for the gradient i was talking
     * about per block shading with smooth lighting. like it darkens corners
     * and edges when smooth lighting is on inside of the faces all being
     * exactly the same lighting. aka being rendered without smooth
     * lighting ... with this smooth lighting stuff though what im taking
     * about is the sublt shade in corners and stuff." That is vanilla's
     * ambient occlusion, per CORNER, and it is now built:
     * {@link #ambientCorners} at {@code SMOOTH_FULL}. Both of the standing
     * refusals were checked rather than repeated, and each turned out to
     * be about a different thing.
     *
     * <p><b>Refusal one - "the merge collapses a flat plane into 256
     * quads" - was measured against the wrong object.</b> It is true of a
     * TILED pattern, a sawtooth repeated cell by cell, which is what J3
     * priced. Real ambient occlusion is not a pattern, it is a function of
     * the neighbours, and on flat ground there are no neighbours: every
     * corner of every top face on an open plain has three empty probes,
     * every corner value is the same, the field is constant, and
     * {@code GreedyMesher.Plane} merges the 16x16 plane into ONE quad
     * exactly as before.
     *
     * <p><b>What it DOES cost was under-stated here, and the correction
     * matters more than the original claim.</b> An earlier version of this
     * javadoc argued that a one-block riser is constant along its run and
     * therefore free. The riser's own face is; <b>the ground row at its
     * foot is not</b>, and that row is the whole point of the feature.
     * Take a step across a chunk, ground at y for z 0..7 and at y+1 from
     * z 8. The lower plane is 16 wide by 8 deep and used to merge to ONE
     * quad. Now row z 7 carries the darkened corners on its z 8 lattice
     * edge, {@code Plane.fits} rejects it from the rectangle behind it,
     * and the sweep is left with a 7-row remainder that
     * {@code TerrainVertexCodec.largestRepeat} can only take in powers of
     * two: 4 + 2 + 1, then the shaded row itself. <b>One quad becomes
     * four.</b> On a full 16-row run it is 8 + 4 + 2 + 1 plus the shaded
     * row, i.e. <b>five</b>. The power-of-two clamp is what turns "one row
     * differs" into a binary expansion of everything behind it.
     *
     * <p><b>And this row is not the only new merge channel.</b> Real Light,
     * layer 1's other pre12 default, makes BOTH light bytes vary wherever
     * the world's light does - a canopy edge, a cave mouth, an ocean
     * floor - and those are two more of the five channels
     * {@code Plane} compares. Two independent disqualifiers now stack on
     * the same surfaces, and nothing in this file or in
     * docs/unreleased/farfield/FARFIELD-WAVES.md has ever priced them together.
     *
     * <p><b>So the honest state of the cost is: unmeasured.</b> The only
     * number that exists is a NEAR-field one - over 10,000 real sections at
     * render distance 64 the greedy merge returns 18.0 percent with flat
     * lighting and 5.9 percent with vanilla's own AO (docs/PERFORMANCE.md)
     * - and it was gathered on vanilla's near-field geometry, which is
     * built from full block models rather than from a surface shell.
     * Extrapolating it to the far field in either direction is a guess,
     * and the guess this javadoc used to make (that the far field pays
     * LESS) is the one the riser case refutes. {@code GreedyMesher
     * .quadSummary()} now reports the far merge rate live, next to
     * {@code costSummary()} on the residency line; that number, taken with
     * the row off and then on in one session, is what settles this.
     *
     * <p><b>Refusal two - the SEAM - was real and is FIXED at pre18 by
     * S1</b> ({@link Apron}): the mesher is handed the eight neighbouring
     * shells and {@link Occluders} carries their edge cells, so a probe
     * across a chunk plane answers the neighbour's real occupancy. The
     * paragraphs below describe what the seam DID, and they still
     * describe the one place it survives - a boundary whose neighbour is
     * not in the store (the frontier), where every probe outside the
     * records answers NOT OCCLUDED exactly as before:
     * <ul>
     * <li>the error is always in the BRIGHT direction, so the artefact is
     *     a contact line that stops one block short of the chunk plane on
     *     each side - a gap in a dark line, never a bright line drawn
     *     where the neighbour has none, and never darker than the
     *     unshaded ground beside it;</li>
     * <li>both sides of the boundary make that same mistake in that same
     *     direction, so the two chunks agree on "nothing here" rather than
     *     disagreeing about how dark it is;</li>
     * <li>it can only appear where ambient occlusion fires AT ALL: a top
     *     face is occluded only by a solid block one step UP and sideways,
     *     so a plain, a beach, a still ocean and the flat top of a mesa
     *     have none anywhere and nothing to break at a chunk line. It
     *     fires at the risers between heights, at cliffs, tree trunks and
     *     cave mouths - features whose own scale is one block;</li>
     * <li>and it needs the OCCLUDER, not merely the feature, to sit on the
     *     boundary. A riser running up a hillside is shaded on the column
     *     of ground below it; that column loses its shading only for the
     *     stretch where the riser itself stands in the next chunk, which
     *     is one x out of sixteen and one z out of sixteen. Everywhere
     *     else the contour crosses the line with its shading intact.</li>
     * </ul>
     * The 16-block GRID that refusal exists to prevent needs an artefact
     * that appears on every chunk boundary whatever the terrain does.
     * This one appears at a minority of them, as a missing stretch of a
     * dark line rather than as a line of its own.
     *
     * <p><b>The STORED fix (S2) was priced, refused, and is now
     * withdrawn.</b> Storing per-cell AO is 4 corners x 6 faces x 2 bits
     * = 6 bytes on a 4-byte cell, and even a 1-cell occupancy apron is
     * about 540 B raw on a 1,047 B mean record - and it would stop this
     * row being one of the ones that improve terrain ALREADY on disk.
     * S1 buys the same correctness for 0 stored bytes and keeps the
     * repaint, so the format-5 apron is off the table; what S1 costs
     * instead is read amplification and cross-record staleness, both
     * bounded and counted (see {@link Apron}).
     *
     * <h2>And what PRECOMPUTED shading patterns cannot be either (item J3)</h2>
     * The owner's follow-up: "i wonder if you could cheat and fake the
     * lighting too by just creating some premade things and applying them
     * based on brightness." Taken seriously, it splits in two and only one
     * half survives.
     *
     * <p><b>The half that works is already here.</b> A premade CURVE chosen
     * per cell by a signal both chunks derive identically is exactly what
     * Distant Water is: {@code 15 - depth}, selected by a per-column
     * water surface that each chunk reads out of its own record. Face
     * direction is another such signal and its pattern has always been
     * applied ({@link #SHADE_UP} and friends, vanilla's own table), and so
     * is the block's own emission (Glowing Blocks). Those pass the seam
     * test that killed AO, because none of them consults a neighbouring
     * chunk's shell.</p>
     *
     * <p><b>The half that does not work is the per-corner TILE</b>, and the
     * arithmetic is worth keeping because it is what separates a tile from
     * real ambient occlusion. Corner colour and corner light are four of
     * the five channels {@code GreedyMesher.Plane} compares, and it
     * accepts a neighbour only when the field stays AFFINE across the run.
     * A pattern repeated cell by cell is a sawtooth, not a plane, so the
     * first neighbour fails {@code fits} and every merge in the section
     * collapses: a flat 16x16 top plane goes from ONE quad to 256, 64 B to
     * 16 KB, on the most common surface the far field draws. Real ambient
     * occlusion is not that field - on flat ground it is CONSTANT, so the
     * plane still merges to one quad - which is why {@code SMOOTH_FULL}
     * exists and a tile still does not. See "Smooth lighting (pre12 M2b)"
     * above.</p>
     *
     * <p>Selecting a pattern from "height below the column top" has the
     * same shape of answer for a different reason: it is seam-continuous,
     * but it is an INVENTION - vanilla lights an open cliff face 40 blocks
     * below the ridge at full sky, and the near/far comparison inside one
     * frame would show it. That one is still refused.</p>
     *
     * <h2>The owner's gradient (pre10 item L8): what post-merge shading CAN
     * be, and the half of J3 that was refused too fast</h2>
     * The owner came back with it sharpened: "couldnt instead of
     * calculating it we just make a setting to impose like a gradient or
     * like pre rendered image, we would only need a few for most situations
     * and we can change its opacity/intensity based on your set
     * brightness." J3 answered post-merge shading with one sentence - that
     * a merged rectangle is chunk-bounded, so a gradient with the
     * rectangle's period is a gradient with the CHUNK's period - and that
     * sentence is true of a gradient applied to EVERY rectangle. It is not
     * true of one applied only to the rectangle edges that run against an
     * occluder, because a rectangle edge that lies ON the chunk plane reads
     * its occluders from outside the section, finds nothing, and is never
     * shaded. The chunk-period gradient the refusal was protecting against
     * is exactly the case this rule cannot draw.
     *
     * <p>{@link #contactShade} is that rule and the Contact Shading slider
     * is the intensity control he asked for. It costs no bytes on disk, no
     * bytes in the arena and no quads; it cannot split a merge because the
     * merge has already returned; and, being derived from the record's own
     * cells rather than saved with them, <b>it is the only lighting-shaped
     * row that improves terrain that is already on disk</b> - which is the
     * thing Real Light structurally cannot do.</p>
     *
     * <p><b>What is still refused, with the number.</b> The other reading
     * of "a pre rendered image" is a small tile applied to every block face
     * from its position within the cell. That one is cheap and still wrong:
     * vanilla's ambient occlusion darkens a face's edge only where a
     * neighbour is actually there, so a tiled vignette would draw a dark
     * border around every block of an OPEN PLAIN, where vanilla draws none.
     * And it would draw it at a size nothing can use: at the far field's
     * own distances a block is 2.4 screen pixels at the handover band
     * (16 blocks subtending 16/512 rad at 1,222 px/rad, 1080p at 90
     * degrees) and 0.64 pixels at layer-1 radius, so the pattern is either
     * a grid on flat ground or invisible. Contact Shading spends the same
     * idea where the terrain says there is something to shade.</p>
     *
     * <h2>Smooth Lighting, finished (pre19, the owner's S3 and S5)</h2>
     * Two things this class had priced as invisible and the owner then saw
     * on ordinary terrain. Both were mesh-time and both are closed at zero
     * stored bytes, so every record already on disk repaints on its next
     * remesh; {@code ShellCodec.FORMAT_VERSION} and
     * {@code BAND_RULE_REVISION} do not move. (pre20 moved both, for an
     * unrelated extraction-time reason - the green water and the
     * signature the records never carried. Nothing in this section
     * depends on either number; it says only that S3 and S5 cost no
     * stored bytes, which is still true.)
     *
     * <p><b>S3 - "the smooth lighting shading seems to not work on [non]
     * full blocks, i noticed dirt paths have this, then i noticed
     * farmland, slabs, stairs, and fences".</b> He named the family
     * exactly. pre18 gated both per-corner paths on
     * {@code SpriteUvResolver.Quads#flush} - a UNIT face whose plane is
     * the cell boundary - and recorded the exclusion as sub-pixel. Vanilla
     * does not exclude anything: {@code BlockModelLighter} moves the probe
     * ORIGIN onto the block's own cell when the face is not cubic and then
     * blends the cell's four lattice-corner values by the face's real
     * extent. {@link #faceAtCellBoundary} is the first half,
     * {@link #inPlaneA} the second, and between them <b>cubic, shifted and
     * non-unit faces take one rule</b> - the same rule, which is what
     * keeps the pre14 grass-overlay fix from needing a mechanism of its
     * own.</p>
     *
     * <p><b>S5 - "smooth lighting works perfect for the shading of full
     * blocks, but ... regular light shading, like that from a torch isnt
     * actually smooth or blended".</b> Vanilla smooths TWO things per
     * corner and this class smoothed one. {@link #smoothBlend} is the
     * other, {@code LightCoordsUtil.smoothBlend} transcribed, and
     * {@link #cornerLights} feeds it the light donors
     * ({@link #airLight}) at the eight probes {@link #ambientCorners}
     * already reads. The two refusals it retires:</p>
     * <ul>
     * <li><i>"one block is 2.4 px at the handover band"</i> - true of an
     *     AO step and false of a torch, which lays fifteen levels over a
     *     span of fifteen blocks. The owner found it on the ground he
     *     actually flies over;</li>
     * <li><i>"it would make light a varying merge channel over large flat
     *     surfaces where it is currently constant"</i> - <b>measured and
     *     wrong</b>. Where light is uniform every probe returns the face's
     *     own reference, all four corners come back equal to it, and the
     *     encoded section is byte-identical to pre18. Where it is not
     *     uniform the blended field is AFFINE where the per-face staircase
     *     was not, and {@code GreedyMesher.Plane} takes affine: on a 16x16
     *     floor round a torch the top-face group falls from 251 quads to
     *     68. The one case that costs is a light FRONT crossing the plane,
     *     where the blend widens the varying band by one cell - worst
     *     measured +16 quads on 256 cells. See docs/unreleased/farfield/FARFIELD-WAVES.md,
     *     "S3 AND S5 ANSWERED".</li>
     * </ul>
     *
     * <p><b>No new row.</b> Vanilla decides both halves in one method and
     * writes both in one pass, so Smooth Lighting = Full simply means more
     * now ({@link Style#smoothLight}); a second control would be a second
     * thing to explain for a step that costs nothing where nothing is
     * happening.</p>
     *
     * <p><b>Two thirds of vanilla's own gate, and the third recorded.</b>
     * {@code ModelBlockRenderer} ip 48-80 takes the corner path only when
     * the global setting is on, the block emits no light, and the model
     * part declares {@code useAmbientOcclusion()}. The Smooth Lighting row
     * is the first, {@code palette.emission[row] == 0} is the second - so
     * glowstone and a sea lantern now draw flat corners as vanilla draws
     * them, which they did not at pre18. The third is a model flag the
     * shell palette does not carry: 151 vanilla block models declare
     * {@code "ambientocclusion": false} (censused this session over the
     * jar's 2,657 block models), and the ones with an axis-aligned face -
     * doors, ladders, hoppers, cauldrons, panes, bars, rails, redstone
     * components, signs, wall fence gates - therefore take shading here
     * that the near field does not draw. The error is DARK, it needs a
     * full cube adjacent, and the fix is one {@code boolean[]} on
     * {@code SpriteUvResolver.ResolvedPalette}; see docs/unreleased/farfield/FARFIELD-WAVES.md,
     * "S3 AND S5 ANSWERED".</p>
     *
     * <p><b>Cross-chunk, on pre18's apron and nothing else.</b> Both halves
     * read {@link Occluders} and {@link #airLight}, both of which already
     * carry the 3x3's edge cells, and both probe at most one cell outside
     * the column - which is exactly what the apron holds. A torch at a
     * chunk edge therefore lights the neighbouring record's corners at
     * mesh time, and the two records compute the SAME number for a corner
     * they share (worked in the waves doc). What this requires of the
     * store is only that the neighbour's plane be current: the mesher
     * assumes the record it is handed is not stale.</p>
     */
    private record Style(boolean solidLeaves,
                         boolean glow, boolean realLight, boolean waterDepth,
                         float contactShade, float ambient, boolean variants) {

        /** Read the layer-1 rows and decide what this column can honour. */
        static Style current(ShellCodec.Shell shell,
                SpriteUvResolver.ResolvedPalette palette) {
            int lighting = FarFieldConfig.layerLighting(FarFieldConfig.Layer.L1);
            boolean real = lighting >= FarFieldConfig.LIGHT_REAL
                    && shell.cellLight != null
                    && shell.cellLight.length >= shell.cellCount
                    && palette != null;
            // ONE strength slider, and the step above it decides which
            // of the two shading paths spends it. They are deliberately
            // exclusive: both derive their darkening from the same
            // occupancy grid, so running both would darken a contact
            // corner twice.
            int strength = FarFieldConfig.layerContactShade(FarFieldConfig.Layer.L1);
            int step = strength <= 0 ? FarFieldConfig.SMOOTH_OFF
                    : FarFieldConfig.layerSmoothLight(FarFieldConfig.Layer.L1);
            return new Style(
                    FarFieldConfig.layerFlag(FarFieldConfig.Layer.L1,
                            FarFieldConfig.Control.SOLID_LEAVES),
                    lighting >= FarFieldConfig.LIGHT_GLOW,
                    real,
                    // NOT while a real light plane is in play. The engine
                    // that wrote that plane already applied water's own
                    // dampening, exactly (see the class javadoc's
                    // "Underwater shading"), so deriving the same number a
                    // second time could only cost time. The test is PER
                    // RECORD and that is the whole point of the row: the
                    // same setting is a no-op here and the only source of
                    // underwater darkening one branch away.
                    !real && FarFieldConfig.layerFlag(FarFieldConfig.Layer.L1,
                            FarFieldConfig.Control.WATER_LOOK),
                    step == FarFieldConfig.SMOOTH_EDGES
                            ? CONTACT_SHADE_MAX * strength / CONTACT_SHADE_FULL
                            : 0.0f,
                    step == FarFieldConfig.SMOOTH_FULL
                            ? (float) strength / CONTACT_SHADE_FULL
                            : 0.0f,
                    // Block Variants (R4): vanilla's per-position weighted
                    // variant, derived from the cell's world position at
                    // mesh time for zero stored bytes. Default ON - the
                    // accuracy directive - and a row because the merge
                    // price on open rotated ground is real (see
                    // variantDraw's javadoc).
                    FarFieldConfig.layerFlag(FarFieldConfig.Layer.L1,
                            FarFieldConfig.Control.BLOCK_VARIANTS));
        }

        /**
         * The alpha cutoff index one face should carry.
         *
         * <p>{@code resolved} is vanilla's own answer, per quad, over that
         * quad's own uv window ({@code FaceBakery} ->
         * {@code SpriteContents.computeTransparency} ->
         * {@code ChunkSectionLayer.byTransparency}; the chain is transcribed
         * in the {@link Style} javadoc). <b>Exactly one player row is
         * allowed to override it</b>, and only in the direction vanilla's
         * own Fast graphics setting overrides it: SOLID LEAVES.
         * {@code partialBox} is what excludes a quad from that - true unless
         * the quad is a flush, non-overlay face of an entry that fills its
         * cell, which is the near-field rule that only a full unit face is
         * ever solidified, and it is what keeps a grass block's tinted side
         * OVERLAY from being painted solid green.</p>
         */
        int cutoffFor(int resolved, boolean partialBox) {
            if (resolved != CUTOFF_CUTOUT) {
                return resolved; // solid and translucent are untouched
            }
            if (this.solidLeaves && !partialBox) {
                return CUTOFF_NONE;
            }
            // Everything else keeps vanilla's own 0.5, and there is nothing
            // else to decide: CUTOUT is already vanilla's per-quad verdict
            // on this quad's OWN uv window. See the Style javadoc's
            // "THE ALPHA TEST IS VANILLA'S".
            return resolved;
        }

        /**
         * The packed {@code (sky << 8) | block} light byte pair for one
         * cell, both channels already in the codec's [8,248] domain
         * ({@code raw = level * 16 + 8}, the near field's convention -
         * {@code VanillaMeshDecoder}'s "+8 half-texel centring" over
         * vanilla's {@code LightTexture.pack} 0..240 steps).
         */
        int lightFor(ShellCodec.Shell shell, SpriteUvResolver.ResolvedPalette p,
                int pi, int cellIndex) {
            int light = FLAT_LIGHT;
            if (this.realLight) {
                int packed = shell.cellLight[cellIndex] & 0xFF;
                light = (rawLevel(packed >>> 4) << 8) | rawLevel(packed & 0xF);
            }
            if (this.glow) {
                int emission = p.emission[pi];
                if (emission > 0) {
                    light = (light & 0xFF00)
                            | Math.max(light & 0xFF, rawLevel(emission));
                }
            }
            return light;
        }

        /**
         * The same conversion for one FACE's donor byte (the per-face
         * light of R5 - see {@link #airLight}): the stored
         * {@code sky << 4 | block} nibble pair to the codec's packed
         * {@code (skyRaw << 8) | blockRaw}, with the block's own emission
         * folded in exactly as {@link #lightFor} folds it, so a
         * glowstone's face never reads darker through a donor than
         * through its own byte. Only called with {@link #realLight} set -
         * donors are stored-plane bytes.
         */
        int lightFromByte(int packed, SpriteUvResolver.ResolvedPalette p,
                int pi) {
            return withGlow(lightRaw(packed), p, pi);
        }

        /**
         * The stored {@code sky << 4 | block} nibble pair as this class's
         * packed {@code (skyRaw << 8) | blockRaw}, with NO emission folded
         * in - the light the engine really holds for that position.
         *
         * <p>{@link #cornerLights} blends these and
         * {@link #withGlow} is applied once to the RESULT, which is why
         * the two are separated. Folding a glowing block's emission into
         * each of the four probes instead would let the average dilute it
         * to a quarter of its value on a corner whose other three probes
         * are ordinary air.</p>
         */
        int lightRaw(int packed) {
            return (rawLevel(packed >>> 4) << 8) | rawLevel(packed & 0xF);
        }

        /**
         * The Glowing Blocks row's synthetic emission, folded into a packed
         * pair's BLOCK channel exactly as {@link #lightFor} folds it, so a
         * glowstone's face never reads darker through a donor or through a
         * corner blend than through its own byte. Vanilla's own
         * {@code LightCoordsUtil.lightCoordsWithEmission} takes the same
         * {@code max} against {@code getLightEmission}.
         */
        int withGlow(int light, SpriteUvResolver.ResolvedPalette p, int pi) {
            if (this.glow) {
                int emission = p.emission[pi];
                if (emission > 0) {
                    return (light & 0xFF00)
                            | Math.max(light & 0xFF, rawLevel(emission));
                }
            }
            return light;
        }

        /**
         * Does this column smooth the LIGHTMAP COORDINATE per corner as
         * well as the ambient-occlusion factor (the owner's S5)?
         *
         * <p>The two are one step of one row on purpose: vanilla decides
         * them together, in one method
         * ({@code BlockModelLighter.prepareQuadAmbientOcclusion} writes
         * both {@code setColor} and {@code setLightCoords};
         * {@code prepareQuadFlat} writes neither), so <b>Smooth Lighting =
         * Full simply means more now</b> and there is no second control to
         * confuse. It is {@link #ambient} rather than a field of its own
         * because {@code Style.current} already collapses "Full with the
         * strength slider at zero" to {@code SMOOTH_OFF}.</p>
         */
        boolean smoothLight() {
            return this.ambient > 0.0f;
        }
    }

    // ------------------------------------------------------------------
    // Smooth lighting at distance (the owner's pre11 M2b). See the class
    // javadoc's "Smooth lighting" section.
    // ------------------------------------------------------------------

    /**
     * The column's FULL-CUBE occupancy, one bit per cell, read by both
     * shading paths.
     *
     * <h2>Why it is per COLUMN and not per section</h2>
     * A shell record IS one chunk column, so every cell it holds is
     * available to every other cell in it at no cost. The pre10 grid was
     * per section, which meant an occluder one block above a section
     * boundary answered "not there" and the shading stopped dead at every
     * multiple of sixteen in y. That is the same artefact as the chunk
     * grid, drawn on the other axis, and it was free to fix: one array for
     * the column instead of one per section, sized to the record's own y
     * span rather than to the world's.
     *
     * <h2>Why a shell has the occluders at all</h2>
     * A shell stores only cells with an exposed face, so most of a hill is
     * not in it. That costs nothing here: the occluder of a visible face
     * sits directly against the air that face looks into, so it has an
     * exposed face of its own and is always stored.
     *
     * <h2>Full cubes only</h2>
     * Vanilla's ambient occlusion weighs a neighbour by
     * {@code getShadeBrightness}, which is
     * {@code isCollisionShapeFullBlock ? 0.2 : 1.0} (javap, 26.2 merged
     * jar: {@code BlockBehaviour.getShadeBrightness} ip 0-16). So a
     * flower, a torch, a snow layer or a sheet of water casts no ambient
     * shade in vanilla either, and the palette's own full-cube flag asks
     * the same question once per palette row.
     */
    /**
     * One neighbouring column handed to {@link #mesh(ShellCodec.Shell,
     * SpriteUvResolver.ResolvedPalette, NeighborColumn[])} - its decoded
     * shell and its resolved palette, both by-value/primitive and therefore
     * IO-thread safe. See {@link Apron}.
     */
    public record NeighborColumn(ShellCodec.Shell shell,
            SpriteUvResolver.ResolvedPalette palette) {}

    /**
     * S1 - MESH WITH THE 3x3 OF SHELLS (pre18, the owner's R5; the open
     * ledger's item 6; the water wave's three routed asks). The mesher
     * receives the eight neighbouring columns' records and loads their
     * EDGE CELLS - the one-cell ring at x/z -1 and 16 - into the same
     * structures its own cells fill:
     * <ul>
     * <li>{@link Occluders}: AO and contact probes cross the chunk plane,
     *     closing the P4 seam (the missing stretch of a dark line);</li>
     * <li>{@link #fluidField}: {@code fluidCorner} and {@code fluidFlow}
     *     read the neighbour's real heights, so a seam corner is the SAME
     *     average on both sides (the pre15 fluidCorner asymmetry) and a
     *     seam cell's flow rotation gets its real inputs;</li>
     * <li>the light donor map ({@link #airLight}): a face on the boundary
     *     reads the light of the air it looks into from the neighbour's
     *     own stored plane;</li>
     * <li>the glassy set: a water face against the neighbour's glass or
     *     leaves wears vanilla's {@code water_overlay} (R3 row 10).</li>
     * </ul>
     *
     * <p><b>Zero stored bytes.</b> Everything here is read at mesh time
     * from records that already exist; every record on disk repaints on
     * its next remesh. A missing neighbour (frontier, store miss, apron
     * off) leaves that side exactly at pre18 behaviour - the structures
     * simply hold no apron cells there and every out-of-record policy is
     * unchanged. The honest costs live where they are paid: read
     * amplification on the IO thread ({@code FarField}'s decoded-shell
     * LRU bounds it; {@code farApron*} counters report it) and staleness
     * - a neighbour record older than the world answers for its side
     * until IT re-saves, the same bound every cross-record fact accepts.
     *
     * <p><b>pre19 correction, retiring this paragraph's original closing
     * sentence.</b> pre18 said "rewriting a record does NOT re-mesh its
     * eight neighbours; their seam data heals on their own refresh or
     * revisit", and with S5's per-corner light the symptom of that
     * upgraded from a missing stretch of dark line to a light STEP along
     * the chunk plane. It does now:
     * {@code FarFieldResidency.fileApronNeighbors} files every RESIDENT
     * neighbour of a rewritten column for a replace-in-place re-mesh,
     * drained under a per-pump cap. What is left is a PROMPTNESS bound
     * rather than a correctness one - see docs/unreleased/farfield/FARFIELD-WAVES.md,
     * "APRON STALENESS ANSWERED".</p>
     */
    private static final class Apron {

        /** Index of the column itself in the 9-slot layout. */
        static final int CENTER = 4;

        /** {@code (dz + 1) * 3 + (dx + 1)}; slot {@link #CENTER} unused. */
        final ShellCodec.Shell[] shells = new ShellCodec.Shell[9];
        final SpriteUvResolver.ResolvedPalette[] palettes =
                new SpriteUvResolver.ResolvedPalette[9];
        /** Per neighbour: its own two-block-plant verdicts, or null. */
        final boolean[][] upperHalf = new boolean[9][];
        /**
         * Per neighbour: indices into its cell array of the cells that map
         * into this column's extended domain (x and z in -1..16) - one
         * edge column for a lateral neighbour, one corner column for a
         * diagonal one. Precomputed once so the three structure builders
         * do not each rescan ~700 cells per neighbour.
         */
        final int[][] edgeCells = new int[9][];
        /**
         * Per neighbour: the lowest OUR-frame yRel its record covers
         * (its yRel 0 mapped into this record's frame), or
         * {@link Integer#MAX_VALUE} for an absent neighbour. Below this
         * the neighbour's ground was never extracted, so absence there
         * means UNKNOWN, not air - the fluid sampler's -1 arm.
         */
        final int[] loYRel = new int[9];

        private Apron() {
            java.util.Arrays.fill(loYRel, Integer.MAX_VALUE);
        }

        /**
         * Build, or null when no neighbour is present - the null is what
         * keeps every apron branch below free for a mesh with no
         * neighbours (the compat overload, the tests, apron off).
         */
        static Apron of(ShellCodec.Shell shell, NeighborColumn[] neighbors) {
            if (neighbors == null) {
                return null;
            }
            Apron apron = null;
            for (int d = 0; d < 9 && d < neighbors.length; d++) {
                NeighborColumn nc = neighbors[d];
                if (d == CENTER || nc == null || nc.shell() == null
                        || nc.palette() == null) {
                    continue;
                }
                ShellCodec.Shell nb = nc.shell();
                int dx = ((d % 3) - 1) << 4;
                int dz = ((d / 3) - 1) << 4;
                int count = 0;
                int[] edge = new int[nb.cellCount];
                for (int i = 0; i < nb.cellCount; i++) {
                    int cell = nb.cells[i];
                    int ox = ShellCodec.cellX(cell) + dx;
                    int oz = ShellCodec.cellZ(cell) + dz;
                    if (ox >= -1 && ox <= 16 && oz >= -1 && oz <= 16) {
                        edge[count++] = i;
                    }
                }
                if (count == 0) {
                    continue; // nothing of it borders this column
                }
                if (apron == null) {
                    apron = new Apron();
                }
                apron.shells[d] = nb;
                apron.palettes[d] = nc.palette();
                apron.upperHalf[d] = upperHalves(nb, nc.palette());
                apron.edgeCells[d] = java.util.Arrays.copyOf(edge, count);
                apron.loYRel[d] = nb.minY - shell.minY;
            }
            return apron;
        }
    }

    // ------------------------------------------------------------------
    // The extended-domain cell key, shared by the apron-aware structures
    // ------------------------------------------------------------------

    /**
     * Key for a cell position in the EXTENDED domain (x and z in -1..16,
     * yRel in -1..257): {@code ((yRel + 1) << 10) | ((z + 1) << 5) |
     * (x + 1)}. The old in-record packing could not carry -1 or 16;
     * every map keyed this way ({@link #fluidField}, the light donor map,
     * the glassy set) uses this one so the apron cells and the record's
     * own cells live in one address space.
     */
    private static int apronKey(int x, int yRel, int z) {
        return ((yRel + 1) << 10) | ((z + 1) << 5) | (x + 1);
    }

    /** One cell up in {@link #apronKey} space. */
    private static final int APRON_UP = 1 << 10;

    /** The fluid family plane's shift in a {@link #fluidField} key. */
    private static final int FAMILY_SHIFT = 19;

    // ------------------------------------------------------------------
    // Per-face light (the owner's R5a). See airLight.
    // ------------------------------------------------------------------

    /**
     * The LIGHT DONOR MAP: air position ({@link #apronKey}) to the stored
     * light byte some record's plane holds FOR that air, or absent.
     *
     * <h2>The defect this closes (R5a)</h2>
     * <p>The record stores ONE light byte per cell - UP's sample when UP
     * is exposed, else the brightest admissible face
     * ({@code ShellExtractor.sampleCellLight}) - and the mesher wore that
     * byte on every face of the cell. Vanilla lights each cubic face at
     * the cell the face LOOKS INTO ({@code BlockModelLighter
     * .prepareQuadFlat} ip 16-52: {@code faceCubic} moves the light read
     * to {@code pos.setWithOffset(pos, quad.direction())}; the AO path's
     * per-corner blend is centred on the same offset cell). So a wall
     * cell whose UP face is sunlit wore sky 15 on the side face that
     * looks into a shaded pocket, right beside the pocket's own floor
     * cell drawing the pocket's true light through its UP byte - the
     * owner's "perfect on some block faces ... the blocks next to it just
     * arent shaded right", as a 6-level step between two faces that share
     * an edge.</p>
     *
     * <h2>Zero new bytes: the donor rules</h2>
     * <p>The plane already CONTAINS the number each face needs; it is
     * just filed under a different cell. Two rules recover it, both exact
     * because {@code sampleCellLight} is deterministic:</p>
     * <ul>
     * <li><b>UP donors:</b> a stored cell with UP in its face mask has
     *     air above it, and its byte IS that air's light (UP wins
     *     unconditionally in the sampler; the lateral admissibility gate
     *     never strips UP). The cell under any air-on-ground position is
     *     solid-beside-air, hence stored - so every face looking into air
     *     that SITS ON GROUND finds a donor, which is the whole hillside/
     *     pocket family.</li>
     * <li><b>single-face donors:</b> a stored cell with exactly ONE
     *     exposed face sampled exactly that face's air (no aggregation
     *     possible), so its byte is that air's light. Cave walls and
     *     ceilings are almost all of these.</li>
     * </ul>
     * <p>All donors of one air position sampled the SAME light-engine
     * position, so within one record they agree by construction; across
     * records (the apron's donors) staleness can split them, so the
     * column's OWN donors are written last and win. A face whose air has
     * no donor anywhere keeps the cell byte - the pre18 look, wrong only
     * at a dark pocket with no floor under the probed air (an overhang
     * mid-air), and wrong in the BRIGHT direction there.</p>
     *
     * <h2>The same map now feeds the PER-CORNER blend (pre19, S5)</h2>
     * <p>Vanilla's smooth-lighting path also blends the LIGHTMAP over the
     * four cells around each corner of the offset cell, and pre18 refused
     * that on the arithmetic "a block is 2.4 px at the handover band, so
     * the intra-face gradient is sub-pixel". <b>The owner refuted it in
     * the field</b> - "smooth lighting works perfect for the shading of
     * full blocks, but ... regular light shading, like that from a torch
     * isnt actually smooth or blended" - because a torch's falloff puts
     * fifteen whole levels inside a few blocks, where the gradient is not
     * sub-pixel at all. {@link #cornerLights} reads this same map at the
     * eight probe positions {@link #ambientCorners} already reads, so S5
     * cost no new structure and no new stored byte; the residual against
     * vanilla is now zero on the blend itself (see
     * {@link #smoothBlend}'s exactness note).</p>
     *
     * <p>Cost: one {@code Int2IntOpenHashMap} of at most one entry per
     * stored cell, built in one pass on the IO thread, plus one hash read
     * per face at emit for the flat value and up to eight more for the
     * corners when Smooth Lighting is at Full. Merge cost ~0: where light
     * is uniform every donor byte equals the cell byte, every corner comes
     * back equal to it, and the channel is as constant as it was; where it
     * varies, light already split the merge (it is two of the five
     * channels) and the blended field is AFFINE where the raw one was a
     * staircase, so the merge takes MORE of it - measured in
     * docs/unreleased/farfield/FARFIELD-WAVES.md, "S3 AND S5 ANSWERED".</p>
     */
    private static it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap airLight(
            ShellCodec.Shell shell, Apron apron) {
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap donors =
                new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(
                        Math.max(16, shell.cellCount));
        donors.defaultReturnValue(-1);
        // Apron donors first, own donors after: on a disagreement (two
        // records extracted at different times) the column's own plane is
        // the one its geometry was cut with, so it wins the overwrite.
        if (apron != null) {
            for (int d = 0; d < 9; d++) {
                ShellCodec.Shell nb = apron.shells[d];
                if (d == Apron.CENTER || nb == null || nb.cellLight == null
                        || nb.cellLight.length < nb.cellCount) {
                    continue;
                }
                int[] edge = apron.edgeCells[d];
                int dyRel = nb.minY - shell.minY;
                int ddx = ((d % 3) - 1) << 4;
                int ddz = ((d / 3) - 1) << 4;
                for (int k = 0; k < edge.length; k++) {
                    int idx = edge[k];
                    donateCell(donors, nb.cells[idx],
                            nb.cellLight[idx] & 0xFF,
                            ddx, dyRel, ddz);
                }
            }
        }
        for (int i = 0; i < shell.cellCount; i++) {
            donateCell(donors, shell.cells[i], shell.cellLight[i] & 0xFF,
                    0, 0, 0);
        }
        return donors;
    }

    /** One cell's donor contribution(s) to {@link #airLight}. */
    private static void donateCell(
            it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap donors,
            int cell, int lightByte, int dx, int dyRel, int dz) {
        int mask = ShellCodec.cellFaceMask(cell);
        int face;
        if ((mask & (1 << SpriteUvResolver.FACE_IDX_UP)) != 0) {
            face = SpriteUvResolver.FACE_IDX_UP; // UP wins in the sampler
        } else if ((mask & (mask - 1)) == 0 && mask != 0) {
            face = Integer.numberOfTrailingZeros(mask); // one face, one sample
        } else {
            return; // multi-face aggregate: which face won is not recorded
        }
        int x = ShellCodec.cellX(cell) + dx;
        int yRel = ShellCodec.cellYRel(cell) + dyRel;
        int z = ShellCodec.cellZ(cell) + dz;
        switch (face) {
            case SpriteUvResolver.FACE_IDX_DOWN -> yRel--;
            case SpriteUvResolver.FACE_IDX_UP -> yRel++;
            case SpriteUvResolver.FACE_IDX_NORTH -> z--;
            case SpriteUvResolver.FACE_IDX_SOUTH -> z++;
            case SpriteUvResolver.FACE_IDX_WEST -> x--;
            default -> x++;
        }
        if (x < -1 || x > 16 || z < -1 || z > 16 || yRel < -1 || yRel > 256) {
            return; // outside anything this mesh's faces can look into
        }
        donors.put(apronKey(x, yRel, z), lightByte);
    }

    /**
     * The positions (own + apron, {@link #apronKey}) holding a block that
     * passes vanilla's fluid-overlay neighbour test ({@code instanceof
     * HalfTransparentBlock || instanceof LeavesBlock},
     * {@code FluidRenderer.tesselate} ip 1498-1511), or null when neither
     * this palette nor any neighbour's has such a row - every ocean and
     * lake without glass or leaves at the waterline, i.e. almost all of
     * them. A fluid side face looking into a member swaps its window onto
     * {@code water_overlay} ({@link SpriteUvResolver.FluidLook#overlayU}).
     * Closes R3 row 10 for the in-record case AND the seam case on the
     * same mechanism.
     */
    private static IntOpenHashSet glassyCells(ShellCodec.Shell shell,
            SpriteUvResolver.ResolvedPalette palette, boolean[] upperHalf,
            int[] upperRow, Apron apron) {
        boolean any = palette.anyGlassy;
        if (!any && apron != null) {
            for (int d = 0; d < 9 && !any; d++) {
                any = d != Apron.CENTER && apron.palettes[d] != null
                        && apron.palettes[d].anyGlassy;
            }
        }
        if (!any) {
            return null;
        }
        IntOpenHashSet glassy = new IntOpenHashSet(64);
        int paletteSize = palette.size();
        for (int i = 0; i < shell.cellCount; i++) {
            int cell = shell.cells[i];
            int pi = ShellCodec.cellPaletteIndex(cell);
            if (pi >= paletteSize) {
                continue;
            }
            int pe = upperHalf != null && upperHalf[i] ? upperRow[pi] : pi;
            if (palette.glassy[pe]) {
                glassy.add(apronKey(ShellCodec.cellX(cell),
                        ShellCodec.cellYRel(cell), ShellCodec.cellZ(cell)));
            }
        }
        if (apron != null) {
            for (int d = 0; d < 9; d++) {
                ShellCodec.Shell nb = apron.shells[d];
                if (d == Apron.CENTER || nb == null) {
                    continue;
                }
                SpriteUvResolver.ResolvedPalette np = apron.palettes[d];
                if (!np.anyGlassy) {
                    continue;
                }
                boolean[] nbUpper = apron.upperHalf[d];
                int[] nbUpperRow = np.upperRow;
                int npSize = np.size();
                int[] edge = apron.edgeCells[d];
                int dyRel = nb.minY - shell.minY;
                int ddx = ((d % 3) - 1) << 4;
                int ddz = ((d / 3) - 1) << 4;
                for (int k = 0; k < edge.length; k++) {
                    int idx = edge[k];
                    int cell = nb.cells[idx];
                    int ourY = ShellCodec.cellYRel(cell) + dyRel;
                    if (ourY < -1 || ourY > 256) {
                        continue;
                    }
                    int pi = ShellCodec.cellPaletteIndex(cell);
                    if (pi >= npSize) {
                        continue;
                    }
                    int pe = nbUpper != null && nbUpper[idx]
                            ? nbUpperRow[pi] : pi;
                    if (np.glassy[pe]) {
                        glassy.add(apronKey(ShellCodec.cellX(cell) + ddx,
                                ourY, ShellCodec.cellZ(cell) + ddz));
                    }
                }
            }
        }
        return glassy.isEmpty() ? null : glassy;
    }

    // ------------------------------------------------------------------
    // Per-position weighted variants (the owner's R4)
    // ------------------------------------------------------------------

    /** {@code (1L << 48) - 1}: the LCG's modulus mask. */
    private static final long LCG_MASK = (1L << 48) - 1;

    /** The LCG multiplier, {@code 25214903917}. */
    private static final long LCG_MULT = 0x5DEECE66DL;

    /**
     * Vanilla's weighted-variant draw for one cell, transcribed: which
     * index of an n-total-weight variant list the position picks.
     *
     * <p>javap, 26.2 merged jar, the whole chain:</p>
     * <ul>
     * <li>{@code SectionCompiler} ip 319-326: {@code state.getSeed(pos)}
     *     into {@code tesselateBlock}; {@code BlockBehaviour.getSeed} is
     *     {@code Mth.getSeed(pos)} (ip 0-4) = {@link #positionSeed} with
     *     the cell's FULL world position (the offset lambdas force y = 0;
     *     this does not);</li>
     * <li>{@code ModelBlockRenderer.tesselateBlock} ip 0-21:
     *     {@code random.setSeed(seed); model.collectParts(random, parts)},
     *     and the renderer's random is a
     *     {@code SingleThreadedRandomSource}
     *     ({@code RandomSource.createThreadLocalInstance(0)}, the ctor at
     *     ip 4-9). {@code setSeed} (ip 0-10) is
     *     {@code seed = (s ^ 25214903917) & (2^48 - 1)};</li>
     * <li>{@code WeightedVariants.collectParts} ip 0-18:
     *     {@code list.getRandomOrThrow(random)};
     *     {@code WeightedList.getRandomOrThrow} ip 17-38:
     *     {@code selector.get(random.nextInt(totalWeight))};</li>
     * <li>{@code BitRandomSource.nextInt(int)} ip 14-38 (power-of-two
     *     bound): {@code (int) ((bound * (long) next(31)) >> 31)}; ip
     *     39-63 (general): {@code next(31) % bound} with the standard
     *     rejection loop. {@code SingleThreadedRandomSource.next(bits)}
     *     ip 0-29: {@code seed = (seed * 25214903917 + 11) & (2^48 - 1);
     *     return (int) (seed >> (48 - bits))} - {@code lshr} on a value
     *     the mask keeps non-negative, so {@code >>>} and {@code >>}
     *     agree and the transcription uses {@code >>>}.</li>
     * </ul>
     *
     * <p>The selector's weight mapping is NOT transcribed - it does not
     * need to be: {@code SpriteUvResolver.enumerateVariants} built one
     * table per DRAW VALUE by forcing this same draw through the real
     * selector, so whatever {@code Flat}/{@code Compact} do with weights
     * is already baked into which table index {@code i} holds.</p>
     *
     * <h2>The price, measured where it bites (the merge)</h2>
     * <p>A y-rotation changes a top face's UV ORIENTATION, which is in
     * {@code GreedyMesher}'s key ({@code uvOrientation}'s javadoc says
     * why), so two differently-rotated tops rightly refuse to merge. On a
     * flat 16x16 plane of one 4-rotation block the expected greedy run
     * length is 4/3 cells, so ONE merged quad becomes ~190-256 - about
     * +12-16 KB of arena per flat rotated plane, roughly DOUBLING a
     * plains/desert column's quads, and +0 wherever the surface is not in
     * the rotation family (stone cliffs, snowy grass - {@code snowy=true}
     * is a single variant - forests' leaf canopies, all water). That is
     * why this rides the Block Variants row rather than shipping
     * unconditionally; the row defaults ON per the accuracy directive and
     * the tooltip states the cost.</p>
     */
    public static int variantDraw(long seed, int bound) {
        long s = (seed ^ LCG_MULT) & LCG_MASK;                 // setSeed
        s = (s * LCG_MULT + 0xBL) & LCG_MASK;                  // next(31)
        int bits = (int) (s >>> 17);
        if ((bound & (bound - 1)) == 0) {
            return (int) ((bound * (long) bits) >> 31);
        }
        int r = bits % bound;
        while (bits - r + (bound - 1) < 0) {
            s = (s * LCG_MULT + 0xBL) & LCG_MASK;
            bits = (int) (s >>> 17);
            r = bits % bound;
        }
        return r;
    }

    private static final class Occluders {

        /** In-plane stride: 18 columns, x and z each spanning -1..16. */
        private static final int SIDE = 18;

        /** Bit {@code ((yRel + 1) * 18 + (z + 1)) * 18 + (x + 1)}. */
        private final long[] bits;

        /**
         * Highest legal {@code yRel}: the grid covers {@code -1..span},
         * i.e. the record's own y span plus one guard row each way for
         * the apron.
         */
        private final int span;

        Occluders(long[] bits, int span) {
            this.bits = bits;
            this.span = span;
        }

        static long[] allocate(int span) {
            return new long[(((span + 2) * SIDE * SIDE) + 63) >>> 6];
        }

        static int bitOf(int x, int yRel, int z) {
            return ((yRel + 1) * SIDE + (z + 1)) * SIDE + (x + 1);
        }

        void set(int x, int yRel, int z) {
            int bit = bitOf(x, yRel, z);
            this.bits[bit >>> 6] |= 1L << (bit & 63);
        }

        /**
         * Is this cell a full cube?
         *
         * <p><b>The grid reaches ONE CELL past the chunk on every side
         * since pre18 (S1)</b>: the mesher now receives the eight
         * neighbouring shells and loads their edge cells into the same
         * grid, so a probe at x -1 or 16 answers the neighbour's real
         * occupancy wherever a neighbour record exists. That closes the
         * owner's pre14 P4 (the AO chunk seam - the missing stretch of a
         * dark line, the corner pair split by a cross-plane diagonal) for
         * every boundary both of whose records are on disk, at zero
         * stored bytes, repainting on remesh.</p>
         *
         * <p><b>Anything outside what the records cover is still NOT
         * occluded</b> - the frontier rule and the safe direction: the
         * far field never darkens on data it does not have, so the worst
         * a no-record boundary can do is leave a contact line undrawn
         * for one block on each side, exactly the pre18 behaviour. Both
         * sides of such a boundary make the same mistake in the same
         * direction, and a mid-air probe row past the neighbour's band
         * answers false like the air it is. The one residual asymmetry
         * is STALENESS: a neighbour record older than the world can
         * shade a line the near field no longer draws, until that
         * neighbour re-saves - the same bound every cross-record fact
         * accepts (tint, strips, fluid corners).</p>
         */
        boolean solid(int x, int yRel, int z) {
            if (x < -1 || x > 16 || z < -1 || z > 16
                    || yRel < -1 || yRel > this.span) {
                return false;
            }
            int bit = bitOf(x, yRel, z);
            return (this.bits[bit >>> 6] & (1L << (bit & 63))) != 0;
        }
    }

    /**
     * Build the column's occupancy grid, or null when it would be empty.
     *
     * <p>Two integer passes over the cell array the mesher is about to
     * walk anyway: one for the y span so the array is sized to the record
     * rather than to the world (a surface-band column spans tens of
     * blocks, not 384), one to set the bits. About 1,400 array reads on
     * the census's mean 703-cell record, on the IO thread, and only when
     * one of the two shading steps is on.</p>
     */
    private static Occluders occluders(ShellCodec.Shell shell,
            SpriteUvResolver.ResolvedPalette palette, boolean[] upperHalf,
            int[] upperRow, int paletteSize, Apron apron) {
        int maxYRel = -1;
        for (int i = 0; i < shell.cellCount; i++) {
            int yRel = ShellCodec.cellYRel(shell.cells[i]);
            if (yRel > maxYRel) {
                maxYRel = yRel;
            }
        }
        if (maxYRel < 0) {
            return null;
        }
        int span = maxYRel + 1;
        Occluders occ = new Occluders(Occluders.allocate(span), span);
        boolean any = false;
        for (int i = 0; i < shell.cellCount; i++) {
            int cell = shell.cells[i];
            int pi = ShellCodec.cellPaletteIndex(cell);
            if (pi >= paletteSize
                    || palette.kind[pi] == SpriteUvResolver.KIND_MISSING) {
                continue;
            }
            // The same row the emit loop will draw, so a two-block plant's
            // upper half is judged on the row that actually renders.
            int pe = upperHalf != null && upperHalf[i] ? upperRow[pi] : pi;
            if (!solidCube(palette, pe)) {
                continue;
            }
            occ.set(ShellCodec.cellX(cell), ShellCodec.cellYRel(cell),
                    ShellCodec.cellZ(cell));
            any = true;
        }
        // The apron (S1): the eight neighbouring records' edge cells, so
        // an AO or contact probe at x/z -1 or 16 answers real occupancy.
        // Judged by the NEIGHBOUR's own palette rows through the same
        // solidCube rule, including its two-block-plant upper halves.
        if (apron != null) {
            for (int d = 0; d < 9; d++) {
                ShellCodec.Shell nb = apron.shells[d];
                if (d == Apron.CENTER || nb == null) {
                    continue;
                }
                SpriteUvResolver.ResolvedPalette np = apron.palettes[d];
                boolean[] nbUpper = apron.upperHalf[d];
                int[] nbUpperRow = np.upperRow;
                int npSize = np.size();
                int[] edge = apron.edgeCells[d];
                int dyRel = nb.minY - shell.minY;
                int dx = ((d % 3) - 1) << 4;
                int dz = ((d / 3) - 1) << 4;
                for (int k = 0; k < edge.length; k++) {
                    int idx = edge[k];
                    int cell = nb.cells[idx];
                    int ourY = ShellCodec.cellYRel(cell) + dyRel;
                    if (ourY < -1 || ourY > span) {
                        continue;
                    }
                    int pi = ShellCodec.cellPaletteIndex(cell);
                    if (pi >= npSize
                            || np.kind[pi] == SpriteUvResolver.KIND_MISSING) {
                        continue;
                    }
                    int pe = nbUpper != null && nbUpper[idx] ? nbUpperRow[pi] : pi;
                    if (!solidCube(np, pe)) {
                        continue;
                    }
                    occ.set(ShellCodec.cellX(cell) + dx, ourY,
                            ShellCodec.cellZ(cell) + dz);
                    any = true;
                }
            }
        }
        return any ? occ : null;
    }

    /**
     * One step of vanilla's ambient-occlusion ramp, as a fraction of a
     * corner's brightness. Vanilla averages four shade values, three of
     * them the probes and the fourth the block in FRONT of the face (air,
     * 1.0, because the face is visible at all), so one occluder at 0.2
     * takes {@code (1 - 0.2) / 4 = 0.2} off and the ramp is exactly 1.0,
     * 0.8, 0.6, 0.4. Verified against
     * {@code BlockModelLighter.prepareQuadAmbientOcclusion} ip 937-998
     * (four {@code fadd}s and an {@code ldc 0.25f} per corner) and
     * {@code BlockBehaviour.getShadeBrightness} ip 0-16.
     */
    private static final float AMBIENT_STEP = 0.2f;

    /**
     * Vanilla's ambient occlusion for one unit face, as the OCCLUDER COUNT
     * (0..3) of each of its four corners, two bits per corner at
     * {@code slot * 2} with {@code slot = da * 2 + db}. Zero when nothing
     * around the face is occluded, which is the whole of an open plain and
     * is the fast path this method exists to make cheap.
     *
     * <h2>The rule, verified rather than remembered</h2>
     * javap on the 26.2 merged jar,
     * {@code BlockModelLighter.prepareQuadAmbientOcclusion}:
     * <ul>
     * <li>the probes are the eight cells around the centre of the layer
     *     one step along the face normal ({@code AdjacencyInfo.corners}
     *     applied to {@code blockPos.relative(direction)}, ip 24-34 and
     *     43-471);</li>
     * <li>a corner's value is
     *     {@code (side1 + side2 + corner + front) * 0.25f} (ip 937-998),
     *     each term a {@code getShadeBrightness};</li>
     * <li><b>the diagonal is only read when at least one of its two sides
     *     is transparent</b> (ip 473-483: the branch is taken when either
     *     side flag is set); when both sides occlude, the diagonal takes a
     *     side's value, which is what makes an inside corner the darkest
     *     step vanilla draws.</li>
     * </ul>
     *
     * <h2>Axis convention</h2>
     * A and B are the face's two in-plane axes in the same order
     * {@link #contactShade} and {@code GreedyMesher} use, so a corner slot
     * here and a merged rectangle's corner there mean the same thing.
     *
     * <h2>Why a record with only EXPOSED cells in it can answer this
     * exactly, and the two places it cannot</h2>
     * {@link Occluders} is built from the record's cells, and a cell
     * reaches the record only if some face of it is exposed. That looks
     * like it should lose buried probes, and it provably does not:
     * <ul>
     * <li>the probe layer is one step ALONG THE NORMAL, and the cell at
     *     {@code origin + normal} is air by definition (it is why the face
     *     was emitted at all);</li>
     * <li>{@code side1} and {@code side2} are 6-adjacent to that air cell,
     *     so if either is solid it owns a face against it and is stored;</li>
     * <li>the DIAGONAL is read only when at least one side is transparent
     *     (vanilla's own rule, above), and it is 6-adjacent to both sides -
     *     so whenever it is read it touches air and is stored too.</li>
     * </ul>
     * The same argument covers {@code ShellExtractor.closeLeaks}: its flood
     * offers all six neighbours of every see-through position it reaches,
     * which is exactly this probe set, so a cave that the flood lined also
     * has its ambient occlusion.
     *
     * <p>So there are exactly three ways a probe can lie, all of them
     * BRIGHT. A probe outside the 3x3 the apron covers (the frontier -
     * pre18's S1 closed the ordinary chunk seam, see {@link Apron}); a
     * probe below its own column's band floor, which happens only where
     * the leak flood was cut off by its budget, i.e. only where the
     * GEOMETRY is missing too; and - new with the non-cubic origin below -
     * a probe in the block's OWN layer that no exposed face reaches. The
     * argument above turns on the probe layer being the AIR layer, and
     * a non-cubic face's does not: the cell beside a sunken dirt path is
     * stored because the sky is above it, but a cell beside a slab set
     * into a floor and roofed over on that side has no exposed face of its
     * own and is absent, so it answers not-occluded. All three are
     * written up in docs/unreleased/farfield/FARFIELD-WAVES.md ("P4 AND P5 ANSWERED",
     * "R4 AND R5 ANSWERED (S1)", "S3 AND S5 ANSWERED").</p>
     *
     * <h2>The ORIGIN is a parameter, and that is S3 (pre19)</h2>
     * <p>Vanilla does not always probe the layer one step along the normal.
     * {@code prepareQuadAmbientOcclusion} ip 17-34 is
     * {@code BlockPos origin = faceCubic ? blockPos.relative(direction)
     * : blockPos}, and {@code faceCubic} (set by {@code prepareQuadShape}
     * ip 510-771) is <b>flat on the normal axis AND (the plane IS the cell
     * boundary the facing names OR the block's collision shape is a full
     * block)</b>. A slab's top at 8/16, a dirt path's or farmland's at
     * 15/16, a fence post's inset sides: all of them fail it, and vanilla
     * moves the probe origin onto the block's OWN cell rather than
     * skipping the shading. Until pre19 this method was only ever called
     * for a flush unit face, so the far field drew no ambient occlusion at
     * all on that whole family - the owner's S3. The caller now decides
     * the origin with {@link #faceAtCellBoundary} and passes it in; this
     * method's arithmetic is unchanged, which is why every full cube in
     * every record on disk still gets byte-identical corners.</p>
     *
     * @param ox the probe layer's centre x - the cell's own x for a
     *           non-cubic face, one step along the normal for a cubic one
     * @param oy the same in {@link Occluders} y coordinates
     * @param oz the same z
     */
    private static int ambientCorners(Occluders occ, QuadFacing facing,
            int ox, int oy, int oz) {
        int ax = 0;
        int ay = 0;
        int az = 0;
        int bx = 0;
        int by = 0;
        int bz = 0;
        switch (facing) {
            case POS_Y, NEG_Y -> {
                ax = 1;
                bz = 1;
            }
            case POS_X, NEG_X -> {
                ay = 1;
                bz = 1;
            }
            case POS_Z, NEG_Z -> {
                ax = 1;
                by = 1;
            }
            default -> {
                return 0; // UNASSIGNED: a blade has no plane and no corners
            }
        }
        boolean aLow = occ.solid(ox - ax, oy - ay, oz - az);
        boolean aHigh = occ.solid(ox + ax, oy + ay, oz + az);
        boolean bLow = occ.solid(ox - bx, oy - by, oz - bz);
        boolean bHigh = occ.solid(ox + bx, oy + by, oz + bz);
        int packed = 0;
        for (int slot = 0; slot < 4; slot++) {
            boolean high = (slot & 2) != 0;
            boolean far = (slot & 1) != 0;
            boolean side1 = high ? aHigh : aLow;
            boolean side2 = far ? bHigh : bLow;
            int count = (side1 ? 1 : 0) + (side2 ? 1 : 0);
            if (side1 && side2) {
                count++; // vanilla's shortcut: the diagonal takes a side
            } else {
                int sa = high ? 1 : -1;
                int sb = far ? 1 : -1;
                if (occ.solid(ox + ax * sa + bx * sb, oy + ay * sa + by * sb,
                        oz + az * sa + bz * sb)) {
                    count++;
                }
            }
            packed |= count << (slot * 2);
        }
        return packed;
    }

    /**
     * How far along the face's FIRST in-plane axis one vertex sits inside
     * its cell, clamped to 0..1 - the {@code da} of the
     * {@code da * 2 + db} slot {@link #ambientCorners} packs, as a
     * fraction rather than a bit.
     *
     * <h2>Why a fraction, and why it is still exact on a full face</h2>
     * <p>This is vanilla's non-cubic weighting
     * ({@code prepareQuadAmbientOcclusion} ip 1212-1746: four
     * {@code faceShape} products per vertex, applied to the four corner
     * values by {@code ARGB.gray} and by
     * {@code LightCoordsUtil.smoothWeightedBlend}). Vanilla builds those
     * products from the quad's BOUNDING BOX and hands them to the vertex
     * that {@code AmbientVertexRemap} says sits at that corner; taking
     * each vertex's OWN in-plane coordinate is the same number for every
     * axis-aligned rectangle - which is every quad that reaches here -
     * and needs no winding assumption. Worked for a lower slab's north
     * face (x 0..1, y 0..1/2): vanilla's {@code vert0Weights} for NORTH
     * are {@code UP x FLIP_WEST, UP x WEST, FLIP_UP x WEST,
     * FLIP_UP x FLIP_WEST} = {@code maxY(1-minX), maxY minX,
     * (1-maxY)minX, (1-maxY)(1-minX)} = {@code 0.5, 0, 0, 0.5} at
     * {@code minX = 0, maxY = 0.5}, i.e. the midpoint of the cell's two
     * corner values on that edge - which is what
     * {@code fb = 0.5} produces here.
     *
     * <p><b>A full cell face is unchanged to the byte.</b> Its vertices
     * are at 0 or 1 on both axes, the four weights are exactly
     * {@code 1.0f} and {@code 0.0f}, and the interpolation returns the
     * lattice corner itself - the same value pre18's slot lookup
     * returned.</p>
     */
    private static float inPlaneA(QuadFacing facing, float[] pos, int vb) {
        return switch (facing) {
            case POS_Y, NEG_Y, POS_Z, NEG_Z -> clamp01(pos[vb]);
            case POS_X, NEG_X -> clamp01(pos[vb + 1]);
            default -> 0.0f;
        };
    }

    /** The same along the face's SECOND in-plane axis. See {@link #inPlaneA}. */
    private static float inPlaneB(QuadFacing facing, float[] pos, int vb) {
        return switch (facing) {
            case POS_Y, NEG_Y, POS_X, NEG_X -> clamp01(pos[vb + 2]);
            case POS_Z, NEG_Z -> clamp01(pos[vb + 1]);
            default -> 0.0f;
        };
    }

    /**
     * Vanilla clamps the weighted RESULT rather than the weights
     * ({@code Math.clamp(v, 0, 1)} at ip 1778); clamping the coordinate
     * instead keeps the interpolation a convex combination, which is the
     * same guard for a model element that reaches outside its own cell and
     * is one compare cheaper per vertex.
     */
    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : v > 1.0f ? 1.0f : v;
    }

    /**
     * Is this quad's plane the cell boundary its facing names? The
     * {@code faceCubic} half of vanilla's rule
     * ({@code BlockModelLighter.prepareQuadShape} ip 510-771: per
     * direction, {@code min == max} on the normal axis AND
     * {@code min < 1.0E-4} / {@code max > 0.9999} on the low / high side).
     * A quad that reaches here already has an axis-aligned normal
     * ({@code SpriteUvResolver.facingOf} refuses anything else), so the
     * {@code min == max} half is given and only the plane's position is
     * asked.
     *
     * <h2>The tolerance is {@link SpriteUvResolver}'s coplanar nudge, and
     * this is what keeps pre18's {@code flush} flag from needing a second
     * mechanism</h2>
     * <p>{@code SpriteUvResolver} pushes the second and further coplanar
     * quads of one model 1/1024 block outward so the far field's single
     * pass does not z-fight, and pre18 answered "are this quad's vertices
     * on the cell's own lattice" with the pre-nudge {@code Quads#flush}
     * flag precisely because the post-nudge {@code shift} says no. That
     * flag only exists for UNIT faces, so it could not answer for a dirt
     * path's side or a fence post. One tolerance answers for all of them:
     * the nudge is {@code layers/1024} and no vanilla model stacks more
     * than a handful of coplanar layers, while the coarsest thing a model
     * can mean by a real inset is 1/16. 1/128 sits an order of magnitude
     * above the first and three below the second, so a grass block's
     * tinted side overlay is still judged flush - the pre14 P4 fix, kept -
     * and a snow layer's 2/16 top and a slab's 8/16 top are still judged
     * inset.</p>
     */
    private static boolean faceAtCellBoundary(QuadFacing facing, float[] pos, int pb) {
        return switch (facing) {
            case POS_X -> Math.abs(pos[pb] - 1.0f) <= NUDGE_TOLERANCE;
            case NEG_X -> Math.abs(pos[pb]) <= NUDGE_TOLERANCE;
            case POS_Y -> Math.abs(pos[pb + 1] - 1.0f) <= NUDGE_TOLERANCE;
            case NEG_Y -> Math.abs(pos[pb + 1]) <= NUDGE_TOLERANCE;
            case POS_Z -> Math.abs(pos[pb + 2] - 1.0f) <= NUDGE_TOLERANCE;
            case NEG_Z -> Math.abs(pos[pb + 2]) <= NUDGE_TOLERANCE;
            default -> false;
        };
    }

    /**
     * See {@link #faceAtCellBoundary}: three orders above
     * {@code SpriteUvResolver}'s 1/1024 coplanar nudge and one below the
     * coarsest inset a block model can declare.
     */
    private static final float NUDGE_TOLERANCE = 1.0f / 128.0f;

    /**
     * One corner's colour, darkened by how many of its three probes are
     * occluded.
     *
     * <h2>Into the RGB, not into the alpha, and that is the design</h2>
     * {@code GreedyMesher} compares five channels - R, G, B and the two
     * light bytes - and deliberately not alpha, so shading carried in the
     * alpha rides through the merge invisibly. That is exactly what
     * {@link #contactShade} wants, because it runs AFTER the merge and
     * must not split anything; and it is exactly wrong here. A merged
     * rectangle takes its corner values from its own four outer cells, so
     * a one-block darkening at one end would be stretched into a gradient
     * over the whole rectangle - which is precisely the broad wash the
     * owner said was not what he meant. Putting it in the RGB lets the
     * merge see it, split where the field stops being affine, and draw
     * vanilla's one-block ramp.
     *
     * <p>The near field arrives at the same place by a different route:
     * vanilla carries ambient occlusion in the vertex ALPHA and
     * {@code TerrainVertexCodec.premultiplyColor} folds it into the RGB at
     * encode time, so by the time the two fields reach the GPU they hand
     * it the same number and the handover band has no shading step.</p>
     */
    private static int ambientRgb(int rgb, float level, float strength) {
        if (level <= 0.0f) {
            return rgb;
        }
        float factor = 1.0f - strength * AMBIENT_STEP * level;
        int r = Math.round(((rgb >>> 16) & 0xFF) * factor);
        int g = Math.round(((rgb >>> 8) & 0xFF) * factor);
        int b = Math.round((rgb & 0xFF) * factor);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * A 0..15 light level as the stored byte, {@code level * 16 + 8} - the
     * near field's convention ({@code VanillaMeshDecoder}'s "+8 half-texel
     * centring" over vanilla's 0..240 lightmap steps). One definition,
     * because {@link Style#lightFor} and {@link #submergedLight} must agree
     * to the byte or the greedy merge sees two encodings of one value.
     */
    private static int rawLevel(int level) {
        return (Math.max(0, level) << 4) + BLOCK_LIGHT_RAW;
    }

    // ------------------------------------------------------------------
    // PER-CORNER LIGHT (the owner's S5). See smoothBlend and cornerLights.
    // ------------------------------------------------------------------

    /**
     * The packed pair for a position the light engine holds at zero on both
     * channels - what {@code getLightCoords} returns inside an opaque
     * block, and vanilla's {@code 0} written in this class's
     * {@code level * 16 + 8} domain.
     */
    private static final int DARK_LIGHT = (BLOCK_LIGHT_RAW << 8) | BLOCK_LIGHT_RAW;

    /**
     * <b>Vanilla's {@code LightCoordsUtil.smoothBlend(a, b, c, d)},
     * transcribed</b> - the other half of smooth lighting, and the one the
     * far field did not have (the owner's S5: "smooth lighting works
     * perfect for the shading of full blocks, but ... regular light
     * shading, like that from a torch isnt actually smooth or blended").
     * Vanilla smooths TWO things per corner and pre18 smoothed one: the AO
     * factor ({@link #ambientCorners}) and the LIGHTMAP COORDINATE, this.
     *
     * <h2>The rule, off the jar rather than remembered</h2>
     * javap, 26.2 merged jar, {@code net.minecraft.util.LightCoordsUtil}:
     * <ul>
     * <li>ip 0-13: the zero substitution runs only when the reference
     *     coordinate {@code d} is not itself nearly dark -
     *     {@code sky(d) > 2 || block(d) > 2}. In a genuinely black cave
     *     the zeros are real and are averaged in;</li>
     * <li>ip 16-84, three times over: a coordinate that is <b>entirely</b>
     *     zero takes {@code d} whole; one whose SKY level alone is zero
     *     takes {@code d}'s sky byte and keeps its own block byte
     *     ({@code a | (d & 0x00FF0000)}). That is the anti-bleed rule -
     *     a probe inside rock must not drag a torch-lit corner's sky
     *     channel down, nor a sunlit corner's block channel;</li>
     * <li>ip 85-97: {@code ((a + b + c + d) >> 2) & 0x00FF00FF} - the
     *     plain mean of the four PACKED words, the mask stripping the
     *     carry each channel's sum makes into the next. Adding per channel
     *     as this does is the same number and does not need the mask.</li>
     * </ul>
     *
     * <h2>Why the arithmetic is exact in this class's domain, and why the
     * near/far seam therefore closes</h2>
     * <p>Every raw byte here is {@code level * 16 + 8}, so four of them sum
     * to {@code 16 S + 32} and {@code >> 2} is {@code 4 S + 8} with nothing
     * discarded: the blend has quarter-level resolution and no rounding at
     * all. The near field's decoder writes vanilla's own
     * {@code smoothBlend} output, {@code 4 S}, plus the same
     * {@code + 8} half-texel centring ({@code VanillaMeshDecoder:196}), so
     * the two fields now put the SAME byte on a shared corner. M2's
     * desk-check #4 recorded "the worst divergence is half a level" at the
     * handover band; this closes it to zero.</p>
     *
     * @param a the corner's first side probe
     * @param b its second side probe
     * @param c its diagonal probe
     * @param d the face's own reference - the light of the cell the face
     *          looks into, or the cell's own byte where there is no donor
     */
    private static int smoothBlend(int a, int b, int c, int d) {
        if ((d >>> 12) > 2 || ((d & 0xFF) >>> 4) > 2) {
            a = substituteDark(a, d);
            b = substituteDark(b, d);
            c = substituteDark(c, d);
        }
        int sky = ((a >>> 8) + (b >>> 8) + (c >>> 8) + (d >>> 8)) >> 2;
        int block = ((a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF)) >> 2;
        return (sky << 8) | block;
    }

    /** One probe's substitution; see {@link #smoothBlend}, ip 16-38. */
    private static int substituteDark(int probe, int reference) {
        if (probe == DARK_LIGHT) {
            return reference;
        }
        if ((probe >>> 8) == BLOCK_LIGHT_RAW) {
            return (reference & 0xFF00) | (probe & 0xFF);
        }
        return probe;
    }

    /**
     * One probe position's lightmap coordinate for {@link #smoothBlend}.
     *
     * <h2>Three answers, and the third is the frontier rule</h2>
     * <ul>
     * <li>a DONOR is present: that air position's real stored byte, from
     *     whichever record's plane holds it ({@link #airLight}). This is
     *     what vanilla reads;</li>
     * <li>no donor but the grid says the position is a FULL CUBE: vanilla
     *     reads the light engine inside an opaque block and gets zero, so
     *     this answers {@link #DARK_LIGHT} and {@link #smoothBlend}'s own
     *     substitution decides what to do with it - which is exactly
     *     vanilla's composition;</li>
     * <li>neither: <b>the reference</b>. The far field never darkens on
     *     data it does not have (the same rule {@link Occluders#solid}
     *     states for ambient occlusion), and taking {@code d} makes the
     *     blend degenerate to pre18's single per-face value rather than
     *     inventing a zero. A record with no apron, no light plane or no
     *     donors is therefore byte-identical to pre18 on every corner.</li>
     * </ul>
     *
     * <p>The range guard is not decoration: {@link #apronKey} packs x and
     * z into five bits each, so a probe two cells outside the extended
     * domain - which a corner diagonal off an apron cell really is - would
     * alias onto another position's key. The single donor read the flat
     * path makes never leaves -1..16 and never needed it.</p>
     */
    private static int probeLight(
            it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap donors, Occluders occ,
            Style style, int px, int py, int pz, int reference) {
        if (px >= -1 && px <= 16 && pz >= -1 && pz <= 16 && py >= -1 && py <= 256) {
            int donor = donors.get(apronKey(px, py, pz));
            if (donor >= 0) {
                return style.lightRaw(donor);
            }
        }
        return occ != null && occ.solid(px, py, pz) ? DARK_LIGHT : reference;
    }

    /**
     * The four corners' blended lightmap coordinates for one face, packed
     * sixteen bits per {@code da * 2 + db} slot - the same slot order
     * {@link #ambientCorners} uses, so a vertex resolves both from one
     * pair of in-plane fractions.
     *
     * <h2>The probe set is the AO probe set, and that is not a shortcut</h2>
     * <p>Vanilla reads {@code getShadeBrightness} and
     * {@code getLightCoords} at the SAME eight positions in the same loop
     * ({@code prepareQuadAmbientOcclusion} ip 49-259 takes both off each
     * of the four sides, ip 483-807 both off each diagonal), and feeds
     * corner {@code k} the same three neighbours to both
     * {@code ARGB.gray((s1 + s2 + diag + self) * 0.25f)} and
     * {@code LightCoordsUtil.smoothBlend(s1, s2, diag, d)} (ip 937-1082).
     * So the origin this is called with is {@link #ambientCorners}'s
     * origin - S3's cell-or-neighbour choice reaches the light for free -
     * and the "both sides occlude, so the diagonal is not read" shortcut
     * is read straight back out of the AO word: a slot's count is 3
     * <b>if and only if</b> both its sides are occluded, because that is
     * the only branch that adds the third without a diagonal probe.</p>
     *
     * <p><b>Where this diverges from vanilla, stated rather than
     * discovered later.</b> Vanilla decides "read the diagonal" from four
     * flags taken ONE CELL FURTHER OUT along the normal
     * ({@code setWithOffset(origin, corners[i]).move(direction)}, ip
     * 261-471) and with a different predicate
     * ({@code !isViewBlocking || getLightDampening() == 0}), and when it
     * declines it takes {@code corners[0]}'s value for every one of the
     * four diagonals rather than that corner's own side. pre12
     * transcribed the simpler reading into {@link #ambientCorners} and it
     * has shipped since; this method matches {@link #ambientCorners}
     * rather than vanilla so the two halves of one corner cannot disagree,
     * which is the property that matters. The case is an inside corner
     * with both sides opaque, where the substitution rule sends every term
     * to {@code d} on both readings anyway.</p>
     *
     * @param ambient the packed AO counts for the same face and origin, or
     *                0 when ambient occlusion did not run - then no slot
     *                reports both sides occluded and every diagonal is read
     */
    private static long cornerLights(
            it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap donors, Occluders occ,
            Style style, QuadFacing facing, int ox, int oy, int oz,
            int reference, int ambient) {
        int ax = 0;
        int ay = 0;
        int az = 0;
        int bx = 0;
        int by = 0;
        int bz = 0;
        switch (facing) {
            case POS_Y, NEG_Y -> {
                ax = 1;
                bz = 1;
            }
            case POS_X, NEG_X -> {
                ay = 1;
                bz = 1;
            }
            case POS_Z, NEG_Z -> {
                ax = 1;
                by = 1;
            }
            default -> {
                return 0L; // UNASSIGNED: a blade has no plane and no corners
            }
        }
        int aLow = probeLight(donors, occ, style,
                ox - ax, oy - ay, oz - az, reference);
        int aHigh = probeLight(donors, occ, style,
                ox + ax, oy + ay, oz + az, reference);
        int bLow = probeLight(donors, occ, style,
                ox - bx, oy - by, oz - bz, reference);
        int bHigh = probeLight(donors, occ, style,
                ox + bx, oy + by, oz + bz, reference);
        long packed = 0L;
        for (int slot = 0; slot < 4; slot++) {
            boolean high = (slot & 2) != 0;
            boolean far = (slot & 1) != 0;
            int side1 = high ? aHigh : aLow;
            int side2 = far ? bHigh : bLow;
            int diag;
            if (((ambient >>> (slot * 2)) & 3) == 3) {
                diag = side1; // both sides occlude: vanilla reads no diagonal
            } else {
                int sa = high ? 1 : -1;
                int sb = far ? 1 : -1;
                diag = probeLight(donors, occ, style,
                        ox + ax * sa + bx * sb, oy + ay * sa + by * sb,
                        oz + az * sa + bz * sb, reference);
            }
            packed |= (long) (smoothBlend(side1, side2, diag, reference) & 0xFFFF)
                    << (slot * 16);
        }
        return packed;
    }

    /**
     * One vertex's lightmap coordinate: the four corner blends, weighted by
     * where in its cell the vertex actually sits.
     *
     * <p>This is vanilla's {@code LightCoordsUtil.smoothWeightedBlend}
     * (javap: {@code (IIIIFFFF)I}, ip 0-83 - each channel a float dot
     * product of the four coordinates with the four weights, then
     * {@code f2i} and repack), applied through the same in-plane fractions
     * {@link #inPlaneA} explains. On a full cell face the weights are
     * exactly one and zero, so this returns a corner untouched and the
     * result is byte-identical to pre18's single per-face value wherever
     * that value was right.</p>
     */
    private static int vertexLight(long cornerLight, QuadFacing facing,
            float[] pos, int vb) {
        float fa = inPlaneA(facing, pos, vb);
        float fb = inPlaneB(facing, pos, vb);
        float w00 = (1.0f - fa) * (1.0f - fb);
        float w01 = (1.0f - fa) * fb;
        float w10 = fa * (1.0f - fb);
        float w11 = fa * fb;
        int c00 = (int) (cornerLight & 0xFFFF);
        int c01 = (int) ((cornerLight >>> 16) & 0xFFFF);
        int c10 = (int) ((cornerLight >>> 32) & 0xFFFF);
        int c11 = (int) ((cornerLight >>> 48) & 0xFFFF);
        int sky = (int) ((c00 >>> 8) * w00 + (c01 >>> 8) * w01
                + (c10 >>> 8) * w10 + (c11 >>> 8) * w11);
        int block = (int) ((c00 & 0xFF) * w00 + (c01 & 0xFF) * w01
                + (c10 & 0xFF) * w10 + (c11 & 0xFF) * w11);
        return (sky << 8) | block;
    }

    // ------------------------------------------------------------------
    // Underwater shading (the owner's J2). See the class javadoc.
    // ------------------------------------------------------------------

    /**
     * Two per-column planes, indexed {@code (z << 4) | x}, both in ABSOLUTE
     * world y and both {@link #NO_WATER} where they do not apply.
     *
     * @param surface the y of the column's topmost water block, i.e. the
     *                fluid cell whose UP face is exposed. {@link #NO_WATER}
     *                for a column with no water surface in it, which is
     *                every land column
     * @param floor   the y of the highest full block UNDER that surface -
     *                the water body's own bed - after the lateral
     *                relaxation in {@link #relaxFloors}. {@link #NO_WATER}
     *                means "the bed is not in this record", i.e. do not
     *                clamp
     */
    private record WaterColumns(int[] surface, int[] floor) {}

    /**
     * Is this palette entry a fluid whose body should DARKEN what is under
     * it? A fluid that emits its own light is not: lava is
     * {@link SpriteUvResolver#KIND_FLUID} with emission 15, vanilla lights
     * the rock under a lava lake from the lava rather than from the sky,
     * and the far field has no skylight model for the Nether at all. One
     * pair of array reads, and it also keeps any modded glowing fluid out.
     */
    private static boolean darkening(SpriteUvResolver.ResolvedPalette p, int pi) {
        return p.kind[pi] == SpriteUvResolver.KIND_FLUID && p.emission[pi] == 0;
    }

    /**
     * Is this palette ROW a SOLID FULL CUBE - a block whose model fills its
     * cell on all six faces? <b>The one definition, read by both callers
     * that need it</b>, and it exists because having two of them shipped
     * the pre13 ocean.
     *
     * <h2>The defect this method is the fix for (the owner's pre13 O1)</h2>
     * Until pre11 {@link SpriteUvResolver} had a five-value KIND ladder,
     * and {@code KIND_CROSS} was one of its values. Two places asked "is
     * this a solid block" by writing {@code kind == KIND_BLOCK}, and while
     * a plant answered {@code KIND_CROSS} that test was exactly right.
     * <b>pre11 collapsed the ladder to {@code MISSING / BLOCK / FLUID}</b>
     * ("a palette entry's geometry is the block's own BAKED QUADS"), so
     * every plant, blade, fan, torch, slab and stair became
     * {@code KIND_BLOCK}. {@link #occluders}, written afterwards, was
     * built against the new world and ANDs in {@code fullCube};
     * {@link #waterColumns}'s bed scan was not touched and kept the bare
     * kind test, with a comment still describing the deleted rule.
     *
     * <p>What that cost: a water column's BED became "the highest cell of
     * any block under the surface", so a kelp stalk standing in the water
     * set the bed one block below the waterline and
     * {@link #submergedLight} measured a depth of ONE for the whole
     * column. A kelp forest's sea floor - and the stalks themselves -
     * drew at sky 14 where vanilla's engine holds sky 0, i.e. a pale flat
     * ocean beside a correctly dark one, per chunk and per record. See
     * docs/unreleased/farfield/FARFIELD-WAVES.md, "O1 ANSWERED".
     *
     * <h2>Why full-cube is the right question and not merely a narrower
     * one</h2>
     * The bed exists to answer "where does the water body end", and
     * vanilla answers that with sky-light dampening:
     * {@code BlockBehaviour.getLightDampening} is
     * {@code isSolidRender() ? 15 : propagatesSkylightDown() ? 0 : 1}
     * (javap, 26.2 merged jar), so only a block that fills its cell stops
     * the {@code 15 - depth} ramp. Kelp, seagrass, coral fans, sea pickles
     * and every waterlogged partial dampen like the water they stand in
     * and are not the floor of anything.
     * {@link SpriteUvResolver.ResolvedPalette#fullCube} is derived from
     * the model's own quads, which is the same evidence vanilla's
     * {@code isCollisionShapeFullBlock} is derived from, and it is already
     * computed once per palette row.
     *
     * <p><b>The failure direction is bright, deliberately.</b> A water body
     * whose floor really is a partial block (a pond on a slab) finds no
     * bed, {@link #waterColumns} leaves {@link #NO_WATER}, and
     * {@link #submergedLight} declines to shade it at all - pre8's look,
     * never a hole. Widening this test to rescue that case is what the
     * O1 defect WAS.
     */
    private static boolean solidCube(SpriteUvResolver.ResolvedPalette p, int row) {
        return p.kind[row] == SpriteUvResolver.KIND_BLOCK && p.fullCube[row];
    }

    /**
     * Find each column's water surface and the bed beneath it, from the
     * record's OWN cells. Null when this column has no water in it at all,
     * which is the fast exit every land chunk takes.
     *
     * <h2>Why the shell already knows this</h2>
     * The extractor gives a see-through owner a face only against TRUE AIR
     * ({@code ShellExtractor.exposed}), so in an ocean the ONLY water cell
     * with an exposed UP face is the top block of the column - interior
     * water is masked out entirely and never stored. That one cell IS the
     * water surface, and finding it is a scan of the cells that are already
     * in hand. Nothing is read from the neighbouring chunk, nothing is
     * stored, and the format does not move.
     *
     * <p>A column whose top water block is occupied by a PLANT has no water
     * BLOCK there to store, which used to leave it with no surface cell at
     * all - no sheet and no shading. The extractor now stores the fluid
     * that block stands in as a second cell at the same position
     * ({@code ShellExtractor}'s "THE WATER SURFACE UNDER A PLANT"), so the
     * scan below finds it exactly as it finds open water's. Nothing here
     * had to change for it.</p>
     *
     * <h2>Why it is seam-safe</h2>
     * Two neighbouring chunks each derive their own columns' surfaces from
     * their own records, and an ocean is at one y in both, so they agree at
     * the chunk line by construction - the same argument the tint grid
     * makes. A column whose surface cell is genuinely absent (see the
     * class javadoc's limitations) falls back to no shading at all, which
     * is pre8's behaviour, never to a different shading.
     *
     * <h2>The bed, and why the clamp exists</h2>
     * Darkening everything below the surface y is wrong for one shape: a
     * pond or river sitting at the top of a drop, whose column also stores
     * cliff cells twenty blocks lower that the pond's water does not cover.
     * The band floor reaches those cells (it takes the minimum over the
     * four lateral neighbours), so they are real and they would go black.
     * {@code floor} bounds it: depth is measured to the water body's own
     * bed, so a 2-block pond can darken by at most 2 no matter what is
     * under it. {@link #relaxFloors} then lets a column borrow a NEIGHBOUR
     * bed when that neighbour is under the same water surface, which is
     * what keeps a genuine underwater slope darkening all the way down.
     */
    private static WaterColumns waterColumns(ShellCodec.Shell shell,
            SpriteUvResolver.ResolvedPalette palette) {
        int paletteSize = palette.size();
        boolean anyFluid = false;
        for (int e = 0; e < paletteSize && !anyFluid; e++) {
            anyFluid = darkening(palette, e);
        }
        if (!anyFluid) {
            // A palette with no darkening fluid in it cannot have a water
            // surface. This is the whole cost a plains chunk pays: at most
            // MAX_PALETTE pairs of array reads, and the census mean
            // palette is 8.4 names.
            return null;
        }
        int[] surface = new int[256];
        int[] floor = new int[256];
        java.util.Arrays.fill(surface, NO_WATER);
        java.util.Arrays.fill(floor, NO_WATER);
        // Pass 1: the highest UP-exposed fluid cell per column. Two passes
        // rather than one snapshot, so nothing here depends on the cell
        // walk being ascending in y - it is (ShellExtractor emits in
        // (y, z, x) order) but that is the extractor's contract, not the
        // codec's, and pre2's save importer will be a second producer.
        boolean anySurface = false;
        for (int i = 0; i < shell.cellCount; i++) {
            int cell = shell.cells[i];
            int pi = ShellCodec.cellPaletteIndex(cell);
            if (pi >= paletteSize || !darkening(palette, pi)
                    || (ShellCodec.cellFaceMask(cell)
                        & (1 << SpriteUvResolver.FACE_IDX_UP)) == 0) {
                continue;
            }
            int column = (ShellCodec.cellZ(cell) << 4) | ShellCodec.cellX(cell);
            int y = shell.minY + ShellCodec.cellYRel(cell);
            if (y > surface[column]) {
                surface[column] = y;
                anySurface = true;
            }
        }
        if (!anySurface) {
            return null;
        }
        // Pass 1b: drop the surfaces that are not a BROAD SHEET of water.
        // The 15-minus-depth curve is only vanilla's answer where the sky
        // above the cell really is blocked by water for that whole depth,
        // and a one-column fall of water has open air on every side of it.
        // See narrowSurfaces.
        if (!narrowSurfaces(surface)) {
            return null; // every surface found was a fall or a puddle
        }
        // Pass 2: the water body's own bed, the highest SOLID FULL CUBE
        // strictly under that surface. A blade is never a bed: kelp,
        // seagrass, coral fans and sea pickles stand IN the water and
        // dampen sky light exactly as the water does, so a stalk that set
        // the bed would leave the sea floor under it lit as if it stood at
        // the waterline. THAT IS THE pre13 O1 DEFECT and the test is
        // solidCube, not kind - read its javadoc before widening this
        // line, because widening it is what broke the ocean.
        for (int i = 0; i < shell.cellCount; i++) {
            int cell = shell.cells[i];
            int pi = ShellCodec.cellPaletteIndex(cell);
            if (pi >= paletteSize || !solidCube(palette, pi)) {
                continue;
            }
            int column = (ShellCodec.cellZ(cell) << 4) | ShellCodec.cellX(cell);
            int y = shell.minY + ShellCodec.cellYRel(cell);
            if (y < surface[column] && y > floor[column]) {
                floor[column] = y;
            }
        }
        return new WaterColumns(surface, relaxFloors(surface, floor));
    }

    /**
     * Clear every water surface that is not part of a BROAD SHEET, in
     * place, and answer whether any survived. The pre11 fix for the
     * owner's "isolated dark blocks on mountainsides".
     *
     * <h2>The defect</h2>
     * A column's surface is "the highest UP-exposed fluid cell in it", and
     * the top block of a WATERFALL is exactly that. A fall from y 90 down
     * to the ground at y 70 therefore reported surface 90 and bed 70, and
     * {@link #submergedLight} faded the rock at the bottom of the fall by
     * 20 levels: black rock and a black stripe of falling water, on an open
     * mountainside at noon. The same shape covers a spring, a one-block
     * pond above a drop, and a single waterlogged block on a ledge.
     *
     * <h2>Why the curve was never valid there</h2>
     * The curve reproduces {@code SkyLightEngine.propagateIncrease}
     * subtracting water's opacity of 1 per block DOWNWARD, which is the
     * right answer only when the sky above the cell is blocked by water for
     * that whole depth. Beside a one-column fall the sky is not blocked at
     * all: vanilla's engine reaches those cells sideways through open air
     * and stores full daylight. The class javadoc already recorded the
     * lip-of-a-waterfall case as a known approximation; what it missed is
     * that the error is unbounded in the DARK direction, which is the one
     * direction the far field must never take on a guess.
     *
     * <h2>The gate</h2>
     * A column keeps its surface only when all four of its lateral
     * neighbours are under the SAME surface. A neighbour outside the chunk
     * counts as agreeing, because this record cannot see it and the far
     * field's other cross-chunk rules all resolve the same way.
     *
     * <ul>
     * <li>A one-column fall or spring: at least two IN-CHUNK neighbours are
     *     dry however the column sits against the chunk edge, so it is
     *     always cleared - the out-of-chunk allowance can never rescue
     *     one.</li>
     * <li>An ocean or a lake: every interior column keeps its surface, and
     *     so does every column on the chunk edge, because its off-chunk
     *     neighbours are counted as agreeing and the mirror column in the
     *     next chunk reasons identically. <b>The seam still matches by
     *     construction.</b></li>
     * <li>The one-column ring at a shoreline is cleared, and that is the
     *     deliberate price: shore water is one to three blocks deep, so at
     *     most three levels of shading are lost on the strip where the
     *     sand is visible through it anyway.</li>
     * <li>A one-block-wide river or stream is cleared. It is at most two
     *     blocks deep, so at most two levels are lost.</li>
     * </ul>
     *
     * <p>Cost: 256 columns x 4 int compares, once, and only for a record
     * that already got past the "is there a darkening fluid in the
     * palette" gate. Read from a COPY of the surfaces so that a column
     * cleared early in the walk cannot clear its neighbours behind it.</p>
     *
     * @return true when at least one surface survived
     */
    private static boolean narrowSurfaces(int[] surface) {
        int[] seen = surface.clone();
        boolean any = false;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int c = (z << 4) | x;
                int here = seen[c];
                if (here == NO_WATER) {
                    continue;
                }
                boolean broad = (x == 0 || seen[c - 1] == here)
                        && (x == 15 || seen[c + 1] == here)
                        && (z == 0 || seen[c - 16] == here)
                        && (z == 15 || seen[c + 16] == here);
                if (broad) {
                    any = true;
                } else {
                    surface[c] = NO_WATER;
                }
            }
        }
        return any;
    }

    /**
     * Lower each column's bed to the lowest bed among itself and the four
     * lateral columns that sit under the SAME water surface.
     *
     * <p>This is the band floor's heightfield rule applied to light: a
     * column's own bed stops the darkening at the top of its own sea floor,
     * which would leave the wall of an underwater step lit as if it were at
     * the top of the step. Borrowing the neighbour's bed measures the wall
     * at its own depth instead. The same-surface test is what keeps the
     * pond-above-a-drop case fixed: a dry neighbour has no surface and is
     * never borrowed from.</p>
     *
     * <p>Exactly one relaxation pass, which is exact for a one-column step
     * (the only cells a column owns below its own bed are the wall facing
     * its lowest neighbour) and saturating beyond it. Cross-chunk it does
     * not run at all: a wall column on the chunk edge whose lower neighbour
     * is in the next chunk keeps its own bed and is shaded slightly
     * LIGHTER, never darker, so the failure direction is pre8's look. It is
     * also confined to water shallower than 15 blocks: past that every
     * level involved is already 0 and the two answers coincide.</p>
     */
    private static int[] relaxFloors(int[] surface, int[] floor) {
        int[] out = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int c = (z << 4) | x;
                int lowest = floor[c];
                if (surface[c] == NO_WATER || lowest == NO_WATER) {
                    out[c] = lowest;
                    continue;
                }
                if (x > 0) {
                    lowest = borrowFloor(surface, floor, c, c - 1, lowest);
                }
                if (x < 15) {
                    lowest = borrowFloor(surface, floor, c, c + 1, lowest);
                }
                if (z > 0) {
                    lowest = borrowFloor(surface, floor, c, c - 16, lowest);
                }
                if (z < 15) {
                    lowest = borrowFloor(surface, floor, c, c + 16, lowest);
                }
                out[c] = lowest;
            }
        }
        return out;
    }

    /**
     * One neighbour's contribution to {@link #relaxFloors}.
     *
     * <p><b>A neighbour with no bed contributes NOTHING, and that is the
     * pre11 fix.</b> This used to answer {@link #NO_WATER} there, on the
     * reasoning that a neighbour under the same surface with no stored bed
     * means the body runs deeper than the record and there is no honest
     * clamp left to apply. The consequence was not "no clamp", it was
     * <b>no bound</b>: {@link #submergedLight} then measured depth from the
     * surface to the CELL, so one unclamped column faded to black however
     * far down the record reached. Keeping this column's own bed instead
     * can only under-darken, which is pre8's look and never a hole in the
     * world.</p>
     */
    private static int borrowFloor(int[] surface, int[] floor, int c, int n,
            int lowest) {
        if (lowest == NO_WATER || surface[n] != surface[c]
                || floor[n] == NO_WATER) {
            return lowest;
        }
        return Math.min(lowest, floor[n]);
    }

    /**
     * Fade a cell's SKY light out with its depth below the water surface,
     * reproducing what vanilla's light engine would have stored there.
     *
     * <p>The curve is not invented. {@code BlockBehaviour.getLightDampening}
     * (javap, 26.2 merged jar) answers {@code isSolidRender() ? 15 :
     * propagatesSkylightDown() ? 0 : 1}, and {@code LiquidBlock
     * .propagatesSkylightDown} is a bare {@code iconst_0}, so water dampens
     * by 1. {@code LightEngine.getOpacity} is {@code max(1,
     * getLightDampening())} and {@code SkyLightEngine.propagateIncrease}
     * subtracts exactly that per step (ip 128-137). So sky light falls one
     * level per block of water, and the block ABOVE a bed sitting {@code d}
     * water blocks under the surface holds {@code 15 - d} - which is the
     * position {@code ShellExtractor.sampleCellLight} samples for the Real
     * Light plane. This method therefore computes the same number that row
     * would have stored, for free and without a byte on disk.</p>
     *
     * <p>Taken as a MINIMUM against whatever light the cell already had, so
     * it can only ever darken, and so the block-light channel (a distant
     * sea lantern under Glowing Blocks) is untouched.</p>
     *
     * <p><b>An unknown bed now means NO shading at all (pre11).</b> It used
     * to mean "measure to the cell", i.e. an unbounded fade, and that is
     * one of the two ways this method could paint an open-sky column
     * black. A bed the record does not contain is a depth this method does
     * not know, and the far field's rule for a light it does not know is
     * daylight, never a hole.</p>
     *
     * @param surfaceY the column's water surface y, or {@link #NO_WATER}
     * @param floorY   the water body's own bed, or {@link #NO_WATER} when
     *                 the record does not contain it, in which case this
     *                 column is left at whatever light it already had
     * @param y        the cell's absolute y
     */
    /**
     * Is this cell a fluid that is OPEN TO THE AIR from the side, and so
     * must not be depth-darkened?
     *
     * <p>The owner's L4, the half of it that is real and is lighting: a
     * waterfall renders BLACK. {@link #waterColumns} takes a column's water
     * surface to be its topmost UP-exposed fluid cell, which for a fall is
     * the source at the lip, and its bed to be the ground twenty blocks
     * below - so every falling water cell more than fifteen blocks down
     * gets sky level 0 and the fall disappears against dark rock. Vanilla
     * does the opposite: sky light reaches that water from the SIDE, where
     * there is nothing but air, and a waterfall is one of the brightest
     * things in a valley. The class javadoc listed this as a known residual
     * ("the lip of a waterfall is darkened where vanilla would sample the
     * bright air"); the owner has now walked into it.</p>
     *
     * <p>The test is geometry and needs no new data: a fluid cell with an
     * exposed LATERAL face has air or something see-through beside it,
     * because the extractor only gives it that face when the neighbour does
     * not cover it. It separates the cases cleanly:</p>
     * <ul>
     * <li>a falling or flowing column, four lateral faces open: NOT
     *     darkened, which is the fix;</li>
     * <li>the interior of an ocean, whose lateral neighbours are water
     *     (hidden by {@code LiquidBlock.skipRendering}) or sea bed (hidden,
     *     a full occluder): no lateral faces at all, so it darkens exactly
     *     as before - and it is the whole reason the row exists;</li>
     * <li>a shoreline ring cell, one lateral face against air: not
     *     darkened, and its depth was one to three levels, so the visible
     *     difference is under a fifth of a light step;</li>
     * <li>the sea BED and any submerged wall: not a fluid, so this never
     *     fires and the bed still goes dark, which is the row's point.</li>
     * </ul>
     * <p>Cost: one integer AND on the fluid cells of a column that already
     * has water in it, and nothing at all anywhere else.</p>
     */
    private static boolean openFluid(boolean fluid, int mask) {
        return fluid && (mask & LATERAL_FACES) != 0;
    }

    /** The four lateral face-mask bits: north, south, west and east. */
    private static final int LATERAL_FACES =
            (1 << SpriteUvResolver.FACE_IDX_NORTH)
            | (1 << SpriteUvResolver.FACE_IDX_SOUTH)
            | (1 << SpriteUvResolver.FACE_IDX_WEST)
            | (1 << SpriteUvResolver.FACE_IDX_EAST);

    private static int submergedLight(int light, int surfaceY, int floorY, int y) {
        if (surfaceY == NO_WATER || floorY == NO_WATER || y > surfaceY) {
            return light;
        }
        int depth = surfaceY - Math.max(y, floorY);
        if (depth <= 0) {
            return light; // the surface sheet itself, at full daylight
        }
        int sky = rawLevel(SKY_LEVEL_MAX - depth);
        return (Math.min(sky, (light >>> 8) & 0xFF) << 8) | (light & 0xFF);
    }

    // ------------------------------------------------------------------
    // Two-block plants (the owner's pre9 "doublke tall grass is broken").
    // ------------------------------------------------------------------

    /**
     * Per cell: does it draw its palette entry's UPPER half? {@code null}
     * when the question does not arise, which is every column whose palette
     * holds no two-block plant and no door.
     *
     * <h2>The defect</h2>
     * A record written before store format 4 stores block NAMES, so both
     * halves of a tall grass are
     * {@code minecraft:tall_grass} and both resolve the DEFAULT
     * state's model - which is the LOWER half ({@code DoublePlantBlock}'s
     * constructor is {@code registerDefaultState(any().setValue(HALF,
     * LOWER))}, javap ip 5-31). Two cells, one texture, and the owner's
     * "it just appears as two regular one tall grasses". The seven vanilla
     * plants ({@code tall_grass}, {@code large_fern}, {@code sunflower},
     * {@code lilac}, {@code rose_bush}, {@code peony},
     * {@code tall_seagrass}) each map their two halves to two different
     * models with two different textures; {@link SpriteUvResolver} now
     * resolves both.
     *
     * <h2>The rule, and why it needs no storage</h2>
     * A cell draws the UPPER half iff a cell of the SAME palette entry sits
     * DIRECTLY BELOW IT in the same column. That is exact for a two-block
     * plant, because vanilla cannot place an upper half without its lower
     * half under it, and both halves carry one name. Nothing is stored, no
     * format moves and every shell already on disk is fixed the moment it
     * is re-meshed.
     *
     * <p><b>Where it declines rather than guesses.</b> If the lower half is
     * absent from the record the upper reads as a lower - exactly today's
     * look, never a new one. That happens for a lower half whose whole mask
     * came out zero (a plant boxed in on all four sides by other plants,
     * with the Plants In Water row off), and it is the RIGHT fallback: a
     * lone {@code tall_grass_top} floating over bare ground reads worse
     * than a lone {@code tall_grass_bottom}, which at least looks like
     * short grass.</p>
     *
     * <p><b>The one case it gets wrong, named rather than found later.</b>
     * Two doubling blocks stacked with no gap - a door directly on top of
     * another door, the only vanilla arrangement that can do it, since a
     * tall plant cannot grow on a tall plant - make the second block's
     * LOWER half read as an upper. Fixing it means counting the run from
     * its bottom and taking the parity, which is a walk per cell instead of
     * one lookup; it buys the right door texture on a stacked doorway at
     * 100-plus chunks and it is not worth a loop in this method.</p>
     *
     * <h2>Price</h2>
     * A palette with no doubling entry pays ONE null check
     * ({@code palette.upperRow == null}, decided once per column when the
     * palette was resolved). A palette with one pays two passes over the
     * cells - an array read and a palette lookup each - plus one hash-set
     * insert per doubling CELL, which on a plains chunk is a few dozen. On
     * the census's mean 703-cell record that is about 1,400 array reads on
     * the IO thread, the same order as {@link #waterColumns}, and no quads,
     * no bytes and no game-thread work at all.
     */
    private static boolean[] upperHalves(ShellCodec.Shell shell,
            SpriteUvResolver.ResolvedPalette palette) {
        int[] upperRow = palette.upperRow;
        if (upperRow == null) {
            return null; // no two-block plant in this palette
        }
        int paletteSize = palette.size();
        // Pass 1: where every doubling cell is. Keyed by entry as well as
        // position, so two different tall plants in one column cannot
        // stand in for each other.
        IntOpenHashSet placed = new IntOpenHashSet(64);
        for (int i = 0; i < shell.cellCount; i++) {
            int cell = shell.cells[i];
            int pi = ShellCodec.cellPaletteIndex(cell);
            if (pi < paletteSize && upperRow[pi] != pi) {
                placed.add(halfKey(cell, pi));
            }
        }
        if (placed.isEmpty()) {
            return null;
        }
        // Pass 2: a doubling cell with the same entry one block under it is
        // the UPPER half. yRel is the key's low field, so "one block under"
        // is key - 1; yRel 0 is excluded because there is no cell below it
        // in this record to be the lower half.
        boolean[] upper = new boolean[shell.cellCount];
        boolean any = false;
        for (int i = 0; i < shell.cellCount; i++) {
            int cell = shell.cells[i];
            int pi = ShellCodec.cellPaletteIndex(cell);
            if (pi >= paletteSize || upperRow[pi] == pi
                    || ShellCodec.cellYRel(cell) == 0) {
                continue;
            }
            if (placed.contains(halfKey(cell, pi) - 1)) {
                upper[i] = true;
                any = true;
            }
        }
        return any ? upper : null;
    }

    /**
     * One cell's identity for {@link #upperHalves}: palette entry, column
     * and yRel packed so that subtracting one steps DOWN one block within
     * the same entry and column. 24 bits, all three fields byte-or-nibble
     * aligned and none of them able to borrow into the next while
     * {@code yRel > 0}.
     */
    private static int halfKey(int cell, int paletteIndex) {
        return (paletteIndex << 16) | (ShellCodec.cellZ(cell) << 12)
                | (ShellCodec.cellX(cell) << 8) | ShellCodec.cellYRel(cell);
    }

    /**
     * Per palette entry: is its whole tint grid one colour? True for every
     * entry of a record written at grid side 1 (format 2 and the Per Chunk
     * setting), and for every entry of a chunk that sits inside a single
     * biome, which is the overwhelming majority of chunks in a world. The
     * bilinear read below is skipped for those.
     */
    private static boolean[] uniformTints(ShellCodec.Shell shell, int paletteSize) {
        boolean[] uniform = new boolean[paletteSize];
        int side = shell.tintGridSide;
        int[] tint = shell.paletteTint;
        if (tint == null || side <= 1) {
            java.util.Arrays.fill(uniform, true);
            return uniform;
        }
        int nodes = side * side;
        for (int e = 0; e < paletteSize; e++) {
            int base = e * nodes;
            if (base + nodes > tint.length) {
                uniform[e] = true; // a short table: treat as one colour
                continue;
            }
            boolean same = true;
            int first = tint[base];
            for (int n = 1; n < nodes && same; n++) {
                same = tint[base + n] == first;
            }
            uniform[e] = same;
        }
        return uniform;
    }

    /**
     * The biome colour of one palette entry AT ONE CELL.
     *
     * <h2>The CELL field (pre19, {@code TINT_SIDE_CELL}) - a direct read</h2>
     * Node {@code (x, z)} IS block column {@code (x, z)} of this chunk,
     * and its value is {@code ClientLevel.calculateBlockTint}'s own answer
     * there - the integer the near field multiplies into that block's
     * quad. So the read is an array index and the reconstructed field is
     * vanilla's own piecewise-constant-per-block field, not an
     * approximation of it. <b>Two adjacent records agree at the seam by
     * construction and without a shared node</b>: each stores
     * {@code calculateBlockTint} at ITS OWN blocks, that function is a
     * pure function of world position, and neither record has an opinion
     * about a block the other one owns.
     *
     * <h2>The legacy CORNER grids (sides 3, 5, 17) - bilinear</h2>
     * Records written before pre19 carry nodes on block offsets
     * {@code 0, 16/(side-1), ... 16}, where the outermost row is the
     * neighbouring chunk's innermost one and the two agree
     * ({@code ShellExtractor.sampleTintGrid} carries the argument). The
     * cell is sampled at its CENTRE, {@code x + 0.5}, which is why the
     * arithmetic runs in half-block units: {@code 2x+1} over a chunk of 32
     * half-blocks, in integers, so two chunks meeting at a seam cannot
     * disagree by a rounding step.
     *
     * <p><b>Why that convention was retired rather than densified.</b> At
     * side 17 the nodes were already vanilla's per-block values, and the
     * centre-sampled bilinear then returned the MEAN of the four
     * surrounding blocks' values - a second box blur on top of vanilla's,
     * widening every biome ramp by one block and displacing it half a
     * block. Interpolation is a reconstruction of a field you could not
     * afford to store; at one-block spacing there is nothing left to
     * reconstruct and it can only add error. This branch stays because
     * records on disk still use it, and it costs 3 divides and 9
     * multiply-adds per cell for an entry whose grid is not uniform.</p>
     *
     * <p><b>What it costs the greedy merge, stated because it is the real
     * price.</b> Vertex colour is one of the five channels
     * {@code GreedyMesher}'s affine test compares, and a quad built here
     * carries ONE colour on all four corners, so the plane it pins is
     * constant and a neighbour merges only if its byte-quantized colour is
     * identical. Inside one biome every cell is identical and the merge is
     * exactly what it was. Across a boundary the colour moves by one byte
     * step every few blocks, and a 16x16 merged top plane becomes a
     * handful of strips. That is confined to boundary chunks and it is the
     * geometry the setting exists to make look right.</p>
     */
    private static int cellTint(ShellCodec.Shell shell, int pi, int x, int z,
            boolean uniform) {
        int side = shell.tintGridSide;
        if (uniform || side <= 1) {
            return shell.tintNode(pi, 0, 0);
        }
        if (side == ShellCodec.TINT_SIDE_CELL) {
            // The cell field: one node per block column, no interpolation.
            return shell.tintNode(pi, x, z);
        }
        int spans = side - 1;
        // Half-block units: the cell centre of x is 2x+1 out of 32.
        int fx = (2 * x + 1) * spans;
        int fz = (2 * z + 1) * spans;
        int gx = Math.min(fx >> 5, spans - 1);
        int gz = Math.min(fz >> 5, spans - 1);
        int wx = ((fx - (gx << 5)) << 8) >> 5;   // 0..255
        int wz = ((fz - (gz << 5)) << 8) >> 5;
        int c00 = shell.tintNode(pi, gx, gz);
        int c10 = shell.tintNode(pi, gx + 1, gz);
        int c01 = shell.tintNode(pi, gx, gz + 1);
        int c11 = shell.tintNode(pi, gx + 1, gz + 1);
        int r = bilinear(c00 >>> 16, c10 >>> 16, c01 >>> 16, c11 >>> 16, wx, wz);
        int g = bilinear((c00 >>> 8) & 0xFF, (c10 >>> 8) & 0xFF,
                (c01 >>> 8) & 0xFF, (c11 >>> 8) & 0xFF, wx, wz);
        int b = bilinear(c00 & 0xFF, c10 & 0xFF, c01 & 0xFF, c11 & 0xFF, wx, wz);
        return (r << 16) | (g << 8) | b;
    }

    /** One channel of the bilinear read; weights are 0..255. */
    private static int bilinear(int c00, int c10, int c01, int c11, int wx, int wz) {
        int top = ((c00 & 0xFF) << 8) + ((c10 & 0xFF) - (c00 & 0xFF)) * wx;
        int bottom = ((c01 & 0xFF) << 8) + ((c11 & 0xFF) - (c01 & 0xFF)) * wx;
        return (top + (((bottom - top) * wz) >> 8)) >> 8;
    }

    // ------------------------------------------------------------------
    // Fluid corner heights (the owner's pre14 P8)
    // ------------------------------------------------------------------

    /**
     * The record's own fluid/solid field, keyed
     * {@code (yRel << 8) | (z << 4) | x}: a fluid cell stores its HEIGHT
     * and a solid full cube stores {@code -1} (vanilla excludes it from a
     * corner average). Everything else - a plant, a slab, a decal, and AIR
     * itself - is simply ABSENT and reads back as the map's default 0,
     * which is the height vanilla gives all of them. Null, and therefore
     * free, for every column with no fluid in its palette, which is every
     * land chunk.
     *
     * <h2>Why an absent key really is air, next to a fluid (this is the
     * load-bearing step)</h2>
     * A shell stores only cells with an exposed face, so most blocks are
     * missing from it. Next to a fluid cell they are not: a solid block
     * laterally against water gets that face from
     * {@code Block.shouldRenderFace} (water's face occlusion shape is
     * {@code Shapes.empty()}, so the subtraction leaves the solid's whole
     * face and it is stored), and a plant standing in water is stored by
     * the same rule. So within one record, "no cell at (x, y, z) beside a
     * fluid cell" leaves only air - which is exactly the case vanilla's
     * {@code FluidRenderer.getHeight} answers 0 for.
     *
     * <h2>Keyed per fluid FAMILY since pre18 (the pre15/pre16 noise
     * finding, closed)</h2>
     * Vanilla's corner average admits a neighbour on the fluid TYPE alone:
     * {@code FluidRenderer.getHeight} takes the same-fluid branch off
     * {@code fluidState.getType().isSame(fluid)}, and a DIFFERENT fluid
     * falls to the not-solid branch's 0 exactly as air does. The first
     * build of this map stored every fluid's height under one key space,
     * so a lava pool beside a water pond averaged each other's surfaces.
     * The family (1 water, 2 lava, 3 modded -
     * {@code SpriteUvResolver.FluidLook}) now rides bits 16-17 of the key;
     * plane 0 keeps the solid markers, which both families read. Two
     * modded fluids share family 3 and would still cross-average - the
     * honest residual of a 2-bit class, recorded here.
     */
    private static Int2FloatOpenHashMap fluidField(ShellCodec.Shell shell,
            SpriteUvResolver.ResolvedPalette palette, boolean[] upperHalf,
            int[] upperRow, Apron apron) {
        int paletteSize = palette.size();
        boolean any = false;
        for (int e = 0; e < paletteSize && !any; e++) {
            any = palette.kind[e] == SpriteUvResolver.KIND_FLUID;
        }
        if (!any) {
            return null;
        }
        Int2FloatOpenHashMap field =
                new Int2FloatOpenHashMap(Math.max(16, shell.cellCount / 2));
        field.defaultReturnValue(0.0f);
        for (int i = 0; i < shell.cellCount; i++) {
            int cell = shell.cells[i];
            int pi = ShellCodec.cellPaletteIndex(cell);
            if (pi >= paletteSize) {
                continue;
            }
            int pe = upperHalf != null && upperHalf[i] ? upperRow[pi] : pi;
            int key = apronKey(ShellCodec.cellX(cell),
                    ShellCodec.cellYRel(cell), ShellCodec.cellZ(cell));
            // Only the values that are not the DEFAULT are stored: a key
            // that is absent already reads 0, which is what a plant, a
            // slab and an air gap all contribute. That also settles the one
            // position that holds TWO cells - a plant plus the fluid it
            // stands in (ShellExtractor's "THE WATER SURFACE UNDER A
            // PLANT"): the plant writes nothing and the FLUID wins, which
            // is what vanilla reads when it asks the level there.
            if (palette.kind[pe] == SpriteUvResolver.KIND_FLUID) {
                field.put(key | (familyOf(palette, pe) << FAMILY_SHIFT),
                        palette.fluidHeight[pe]);
            } else if (solidCube(palette, pe)) {
                field.put(key, -1.0f);
            }
        }
        // The apron (S1): the neighbours' edge cells under the same rules,
        // through the NEIGHBOUR's palette. A seam corner then averages the
        // same heights on both sides of the plane, a seam cell's flow gets
        // its real lateral inputs, and the -1 out-of-record policy is left
        // for ground no record covers.
        if (apron != null) {
            for (int d = 0; d < 9; d++) {
                ShellCodec.Shell nb = apron.shells[d];
                if (d == Apron.CENTER || nb == null) {
                    continue;
                }
                SpriteUvResolver.ResolvedPalette np = apron.palettes[d];
                boolean[] nbUpper = apron.upperHalf[d];
                int[] nbUpperRow = np.upperRow;
                int npSize = np.size();
                int[] edge = apron.edgeCells[d];
                int dyRel = nb.minY - shell.minY;
                int dx = ((d % 3) - 1) << 4;
                int dz = ((d / 3) - 1) << 4;
                for (int k = 0; k < edge.length; k++) {
                    int idx = edge[k];
                    int cell = nb.cells[idx];
                    int ourY = ShellCodec.cellYRel(cell) + dyRel;
                    if (ourY < -1 || ourY > 257) {
                        continue;
                    }
                    int pi = ShellCodec.cellPaletteIndex(cell);
                    if (pi >= npSize) {
                        continue;
                    }
                    int pe = nbUpper != null && nbUpper[idx] ? nbUpperRow[pi] : pi;
                    int key = apronKey(ShellCodec.cellX(cell) + dx, ourY,
                            ShellCodec.cellZ(cell) + dz);
                    if (np.kind[pe] == SpriteUvResolver.KIND_FLUID) {
                        field.put(key | (familyOf(np, pe) << FAMILY_SHIFT),
                                np.fluidHeight[pe]);
                    } else if (solidCube(np, pe)) {
                        field.put(key, -1.0f);
                    }
                }
            }
        }
        return field;
    }

    /**
     * This fluid row's identity class for the same-fluid rules, 1..3;
     * 1 (water) for a fluid row missing its {@code FluidLook}, which only
     * a hand-built palette can produce - never 0, so a fluid can never
     * land in the solid-marker plane.
     */
    private static int familyOf(SpriteUvResolver.ResolvedPalette palette, int row) {
        SpriteUvResolver.FluidLook look = palette.fluidLook[row];
        int family = look == null ? 1 : look.family();
        return family <= 0 || family > 3 ? 1 : family;
    }

    /**
     * One neighbouring cell's height contribution, i.e. vanilla's
     * {@code FluidRenderer.getHeight(level, fluid, pos, state, fluidState)}
     * (javap ip 0-60) answered from the record instead of from the level:
     * <pre>
     *   same fluid?  fluid above too -&gt; 1.0 ; else its own height
     *   not solid?   -&gt; 0.0
     *   solid        -&gt; -1.0        (excluded from the average)
     * </pre>
     *
     * <p><b>Outside the record is {@code -1}, not 0</b>, and that is the
     * one place this is deliberately not vanilla. We do not know what is in
     * the next chunk, and answering 0 would pull every corner on a chunk
     * edge down toward the floor - a visible notch on every ocean seam in
     * the world. Excluding it makes an edge corner the average of what we
     * DO know, which for a broad sheet is the sheet's own height and
     * therefore identical on both sides of the seam.</p>
     */
    private static float fluidSample(Int2FloatOpenHashMap field, int[] apronLo,
            int family, int x, int yRel, int z) {
        if (x < -1 || x > 16 || z < -1 || z > 16 || yRel < 0 || yRel > 255) {
            return -1.0f; // past even the apron: excluded, never 0
        }
        // Which record answers for this position: the column's own, or a
        // neighbour's edge column (S1). Absent neighbour, or a position
        // below the neighbour record's own band floor, is the old
        // out-of-record -1: unknown ground is excluded, never air.
        int d = ((z < 0 ? 0 : z > 15 ? 2 : 1) * 3) + (x < 0 ? 0 : x > 15 ? 2 : 1);
        boolean inApron = d != Apron.CENTER;
        if (inApron
                && (apronLo == null || apronLo[d] == Integer.MAX_VALUE
                        || yRel < apronLo[d])) {
            return -1.0f;
        }
        int key = apronKey(x, yRel, z);
        float h = field.get(key | (family << FAMILY_SHIFT));
        if (h > 0.0f) {
            if (field.get((key + APRON_UP) | (family << FAMILY_SHIFT)) > 0.0f) {
                return 1.0f; // fluid above it: vanilla draws that corner full
            }
            return h;
        }
        float stored = field.get(key);
        if (stored < 0.0f) {
            return -1.0f; // a solid full cube: excluded (the marker plane)
        }
        // Not this family's fluid and not a stored solid. In the record's
        // own domain absence is air by the exposure argument (the method
        // javadoc above). In the APRON one more case hides in absence: a
        // neighbour cell SUBMERGED in its own record (same fluid above it)
        // has no exposed face and is never stored - detectable, because
        // the cell above it IS stored (its DOWN or UP face is exposed). A
        // same-family hit one up therefore reads as vanilla's
        // fluid-with-fluid-above 1.0 rather than as air. The residual
        // misread is fluid falling over AIR at a seam corner (absent over
        // stored-fluid); vanilla answers 0 there and this answers 1 - a
        // 1/9 corner pull-up, one cell wide, only under a seam waterfall.
        if (inApron
                && field.get((key + APRON_UP) | (family << FAMILY_SHIFT)) > 0.0f) {
            return 1.0f;
        }
        return stored;
    }

    /**
     * One corner of a fluid surface cell, by vanilla's own weighted
     * average - {@code FluidRenderer.calculateAverageHeight} (javap ip
     * 0-94) over {@code addWeightedHeight} (ip 0-51):
     * <pre>
     *   if (hA &gt;= 1 || hB &gt;= 1) return 1;
     *   if (hA &gt; 0 || hB &gt; 0) { hD = height(diagonal);
     *                            if (hD &gt;= 1) return 1; add(hD); }
     *   add(own); add(hA); add(hB);
     *   return sum / weight;
     *   // add(h): h &gt;= 0.8 -&gt; sum += 10h, weight += 10
     *   //         h &gt;= 0   -&gt; sum +=   h, weight +=  1
     *   //         h &lt;  0   -&gt; nothing
     * </pre>
     *
     * <h2>What this is the fix for (the owner's pre14 P8)</h2>
     * <p>"flowing water and lava do not work. they just appear all glitchy
     * and wrong or not at all." pre12's M1 gave a fluid cell its real
     * {@code level} and therefore its real height, and desk-checked ONE
     * cell's height against vanilla's. The height was right. <b>What was
     * wrong is the RELATION between two cells</b>, and it is a defect M1
     * created: vanilla hides a water-to-water lateral face on the fluid
     * TYPE alone ({@code FluidRenderer.isNeighborSameFluid}, javap ip 0-11,
     * compares {@code getType()} and nothing else) and our extractor hides
     * the same face by the same test ({@code LiquidBlock.skipRendering}).
     * Vanilla may do that because this method makes the two tops MEET at
     * their shared corner. We drew flat slabs at nine different heights
     * with the wall between them culled, so <b>every 1/9 step in a stream
     * was an open slot straight into the water body</b> - and a 1/9 or 2/9
     * film at the thin end of a flow, seen at layer-1 range and edge-on
     * through those slots, is the "or not at all". Before format 4 every
     * fluid cell was 8/9 and the tops met by construction, which is why
     * this shipped green.</p>
     *
     * @param family this cell's fluid identity class ({@link #familyOf}) -
     *            vanilla's corner average is per TYPE
     *            ({@code isNeighborSameFluid}, javap ip 0-11)
     * @param dx  -1 or +1: which lateral neighbour this corner touches on x
     * @param dz  the same on z
     * @param own this cell's own fluid height
     */
    private static float fluidCorner(Int2FloatOpenHashMap field, int[] apronLo,
            int family, int x, int yRel, int z, int dx, int dz, float own) {
        float hA = fluidSample(field, apronLo, family, x + dx, yRel, z);
        float hB = fluidSample(field, apronLo, family, x, yRel, z + dz);
        if (hA >= 1.0f || hB >= 1.0f) {
            return 1.0f;
        }
        // Two accumulators rather than vanilla's float[2], so a corner
        // allocates nothing: this runs per ramped fluid cell on the IO
        // thread and a stream can be a few hundred of them.
        float sum = 0.0f;
        float weight = 0.0f;
        if (hA > 0.0f || hB > 0.0f) {
            float hD = fluidSample(field, apronLo, family, x + dx, yRel, z + dz);
            if (hD >= 1.0f) {
                return 1.0f;
            }
            if (hD >= 0.8f) {
                sum += hD * 10.0f;
                weight += 10.0f;
            } else if (hD >= 0.0f) {
                sum += hD;
                weight += 1.0f;
            }
        }
        if (own >= 0.8f) {
            sum += own * 10.0f;
            weight += 10.0f;
        } else if (own >= 0.0f) {
            sum += own;
            weight += 1.0f;
        }
        if (hA >= 0.8f) {
            sum += hA * 10.0f;
            weight += 10.0f;
        } else if (hA >= 0.0f) {
            sum += hA;
            weight += 1.0f;
        }
        if (hB >= 0.8f) {
            sum += hB * 10.0f;
            weight += 10.0f;
        } else if (hB >= 0.0f) {
            sum += hB;
            weight += 1.0f;
        }
        return weight <= 0.0f ? own : sum / weight;
    }

    /**
     * How far two corner heights may differ and still count as one flat
     * plane, i.e. as a cell the fluid-surface bucket may merge and drop
     * rigidly. A ninth of a block is {@code 0.111}, so anything an actual
     * level change produces is three orders of magnitude clear of this;
     * it exists to absorb the float division in {@link #fluidCorner}.
     */
    private static final float CORNER_EPSILON = 1.0e-4f;

    /** {@link #fluidFlow}'s "flow is exactly zero" answer (see there). */
    private static final long FLOW_NONE = 0L;

    /**
     * Vanilla's flow vector for one fluid surface cell, answered from the
     * record: {@code FlowingFluid.getFlow} (javap ip 0-218) transcribed.
     * For each horizontal neighbour:
     * <pre>
     *   nf = neighbour fluid; if (!affectsFlow(nf)) continue;
     *       // affectsFlow (its own method): empty OR same type
     *   h = nf.getOwnHeight(); delta = 0;
     *   if (h == 0) {                            // ip 86-159
     *       if (!neighbourBlock.blocksMotion()) {
     *           below = fluid at neighbour.below();
     *           if (affectsFlow(below) && below.getOwnHeight() > 0)
     *               delta = own - (belowHeight - 0.8888889f);
     *       }
     *   } else if (h > 0) delta = own - h;       // ip 162-176
     *   dx += stepX * delta; dz += stepZ * delta;
     * </pre>
     * In record terms: a same-family plane hit is the same-fluid height; a
     * hit on another family's plane is {@code affectsFlow == false} and
     * skips the direction whole; the solid-marker plane's -1 is
     * {@code blocksMotion()}; everything else is empty.
     *
     * <h2>What this feeds, and why the FALLING arm (ip 232-335) is
     * deliberately omitted</h2>
     * The only consumer is the TOP face's sprite choice and rotation
     * ({@code FluidRenderer.tesselate} ip 671-688: still when
     * {@code flow.x == 0 && flow.z == 0}, else rotate by
     * {@code atan2(flow.z, flow.x) - PI/2}). The falling arm only ever
     * ADDS {@code (0, -6, 0)} to the normalized vector: {@code normalize}
     * preserves the x:z ratio and sign, so the angle is unchanged, and in
     * the one degenerate case (dx = dz = 0 with a solid face beside a
     * falling cell) vanilla's result is {@code (0, -1, 0)}, whose x and z
     * are 0 - the still branch either way. So the arm cannot move a
     * pixel here and its {@code isSolidFace} probes are not paid for.
     *
     * <h2>Documented deviations, each bounded</h2>
     * <ul>
     * <li>An out-of-record neighbour contributes nothing (vanilla reads
     *     the real chunk). Affects the ROTATION of seam-edge cells only -
     *     a texture direction, never geometry - and the two sides settle
     *     it from their own data, deterministically.</li>
     * <li>{@code blocksMotion()} is answered by the solid-cube marker, so
     *     a stored non-cube motion blocker (a waterlogged fence's post)
     *     reads "open" and can add a spurious downhill term vanilla would
     *     not. Rotation-only, one cell wide, beside waterlogged
     *     fences.</li>
     * <li>{@code Math.atan2}/{@code Math.sin}/{@code Math.cos} stand in
     *     for {@code Mth.atan2}/{@code Mth.sin}'s table approximations
     *     (max error ~1e-3 rad); the window corner moves by at most
     *     {@code 0.25 * 1e-3} of a sprite = 1/250 texel.</li>
     * </ul>
     *
     * @return {@link #FLOW_NONE} when the flow is exactly zero (a real
     *         flow cannot produce it: the packed pair below always has
     *         {@code c^2 + s^2 = 1/16}), else
     *         {@code (bits(cos(theta) * 0.25) << 32) | bits(sin(theta) * 0.25)}
     */
    private static long fluidFlow(Int2FloatOpenHashMap field, int[] apronLo,
            int family, int x, int yRel, int z, float own) {
        double dx = 0.0;
        double dz = 0.0;
        for (int dir = 0; dir < 4; dir++) {
            int sx = dir == 0 ? -1 : dir == 1 ? 1 : 0;
            int sz = dir == 2 ? -1 : dir == 3 ? 1 : 0;
            int nx = x + sx;
            int nz = z + sz;
            // Which record answers for the neighbour (S1): the column's
            // own, or the apron. No record - or ground below the
            // neighbour record's band - contributes nothing, the old
            // out-of-record posture.
            int d = ((nz < 0 ? 0 : nz > 15 ? 2 : 1) * 3)
                    + (nx < 0 ? 0 : nx > 15 ? 2 : 1);
            boolean inApron = d != Apron.CENTER;
            if (inApron
                    && (apronLo == null || apronLo[d] == Integer.MAX_VALUE
                            || yRel < apronLo[d])) {
                continue;
            }
            int key = apronKey(nx, yRel, nz);
            float h = field.get(key | (family << FAMILY_SHIFT));
            float delta = 0.0f;
            if (h > 0.0f) {
                delta = own - h;
            } else if (inApron
                    && field.get((key + APRON_UP) | (family << FAMILY_SHIFT))
                            > 0.0f) {
                // Absent under same-family fluid in the apron: a SUBMERGED
                // neighbour cell (never stored - no exposed face). Its real
                // flowing amount is not in any record, so the direction is
                // skipped rather than fabricated - the same posture as no
                // record at all. Rotation-only, seam cells only.
                continue;
            } else if (!foreignFluidAt(field, key, family)) {
                if (field.get(key) >= 0.0f && yRel > 0) { // not a full cube
                    float below = field.get((key - APRON_UP)
                            | (family << FAMILY_SHIFT));
                    if (below > 0.0f) {
                        delta = own - (below - FLUID_SURFACE_HEIGHT);
                    }
                }
            }
            if (delta != 0.0f) {
                dx += sx * (double) delta;
                dz += sz * (double) delta;
            }
        }
        if (dx == 0.0 && dz == 0.0) {
            return FLOW_NONE;
        }
        double theta = Math.atan2(dz, dx) - Math.PI / 2.0;
        float c = (float) (Math.cos(theta) * 0.25);
        float s = (float) (Math.sin(theta) * 0.25);
        return ((long) Float.floatToRawIntBits(c) << 32)
                | (Float.floatToRawIntBits(s) & 0xFFFFFFFFL);
    }

    /** Does ANOTHER family's fluid stand at this key? (affectsFlow false.) */
    private static boolean foreignFluidAt(Int2FloatOpenHashMap field, int key,
            int family) {
        for (int f = 1; f <= 3; f++) {
            if (f != family && field.get(key | (f << FAMILY_SHIFT)) > 0.0f) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Per-position appearance (the owner's pre14 P1)
    // ------------------------------------------------------------------

    /**
     * Vanilla's position hash, {@code Mth.getSeed(x, y, z)}, transcribed.
     *
     * <p>javap on the 26.2 merged jar, {@code Mth.getSeed(III)J} ip 0-34,
     * instruction for instruction:</p>
     * <pre>
     *   long l = ((long) (x * 3129871)) ^ (z * 116129781L) ^ (long) y;
     *   l = l * l * 42317861L + l * 11L;
     *   return l &gt;&gt; 16;
     * </pre>
     * <p>Two details are load bearing and are the reason this is
     * transcribed rather than remembered: {@code x * 3129871} is an
     * <b>int</b> multiply that is allowed to overflow before it is widened
     * (bytecode {@code imul} then {@code i2l}), and the final shift is
     * {@code lshr} (ip 31-33: {@code bipush 16; lshr}), the <b>SIGNED</b>
     * arithmetic shift, Java's {@code >>} — {@code lushr} would be the
     * unsigned one. The pre14 transcription used {@code >>> 16} under a
     * javadoc that read lshr as unsigned; no pixel moved, because every
     * present consumer reads bits 0-11 of the result (the offset lanes and
     * the magnitude probe), which the two fills share — but a consumer of
     * the seed's high bits (the roadmapped weighted-variant selection)
     * would have silently diverged from vanilla on the ~half of positions
     * whose pre-shift value is negative. Fixed in pre16 Phase 0.</p>
     *
     * <p>It lives here, in the mesher, because the offset is a MESH-TIME
     * value: it costs no stored byte and it is pure integer arithmetic on
     * the cell's own world position, so the IO thread can compute it
     * without touching a vanilla object (FARFIELD-CODEBASE-SEAM 6.4).
     * {@code SpriteUvResolver} calls the same method on the game thread
     * when it probes a block's offset magnitudes, so the two halves of the
     * fix cannot drift apart.</p>
     */
    public static long positionSeed(int x, int y, int z) {
        long l = ((long) (x * 3129871)) ^ ((long) z * 116129781L) ^ (long) y;
        l = l * l * 42317861L + l * 11L;
        return l >> 16;
    }

    /**
     * The per-position displacement of one cell, packed as two floats: X in
     * the high 32 bits and Z in the low 32. The Y term is
     * {@link #offsetY(long, float)} because only {@code OffsetType.XYZ}
     * blocks have one and it is derived from the same seed.
     *
     * <p>javap, {@code BlockBehaviour$Properties.lambda$offsetType$1} (the
     * {@code XZ} lambda, ip 24-53) and {@code lambda$offsetType$0} (the
     * {@code XYZ} one, ip 47-110) - the X and Z halves are the same
     * expression in both, computed in FLOAT and then widened:</p>
     * <pre>
     *   dx = clamp((double) ((s        &amp; 15L) / 15.0f) - 0.5) * 0.5, -maxH, maxH)
     *   dz = clamp((double) (((s &gt;&gt;&gt; 8) &amp; 15L) / 15.0f) - 0.5) * 0.5, -maxH, maxH)
     * </pre>
     * <p>reproduced here in the same order and the same widths, so a far
     * plant lands on exactly the pixel vanilla's own copy of it lands on
     * when the chunk crosses back into the near field.</p>
     */
    private static long offsetXZ(long seed, float maxH) {
        double rawX = ((double) ((float) (seed & 15L) / 15.0f) - 0.5) * 0.5;
        double rawZ = ((double) ((float) ((seed >>> 8) & 15L) / 15.0f) - 0.5) * 0.5;
        float dx = (float) Math.max(-maxH, Math.min(maxH, rawX));
        float dz = (float) Math.max(-maxH, Math.min(maxH, rawZ));
        return ((long) Float.floatToRawIntBits(dx) << 32)
                | (Float.floatToRawIntBits(dz) & 0xFFFFFFFFL);
    }

    /**
     * The DOWNWARD half of the displacement, 0 for every block whose
     * {@code OffsetType} is {@code XZ} (their {@code maxV} is 0 by
     * construction - see {@code SpriteUvResolver.offsetMagnitudes}).
     *
     * <p>javap, {@code lambda$offsetType$0} ip 18-39:
     * {@code (((s >>> 4) & 15L) / 15.0f - 1.0) * maxV}, never clamped
     * because the raw term already lives in {@code [-1, 0]}.</p>
     */
    private static float offsetY(long seed, float maxV) {
        if (maxV == 0.0f) {
            return 0.0f;
        }
        return (float) (((double) ((float) ((seed >>> 4) & 15L) / 15.0f) - 1.0)
                * maxV);
    }

    /**
     * The dynamic bucket key: palette ROW in the high bits, the QUAD's index
     * within that row's table in bits 1-8, translucency in bit 0.
     *
     * <p>Keyed per QUAD and not per entry, which is what the baked-quad
     * model needs and the box model could not express: a stair owns two
     * horizontal planes at different heights, a model with a nudged
     * coplanar overlay owns two at almost the same height, and each has to
     * be merged among its own kind and shifted by its own amount. The row is
     * still in the key because two entries with the same shift have
     * different sprites and could never have merged with each other anyway,
     * and the translucent flag because it is applied after the merge for
     * every bucket.</p>
     *
     * <p>{@value #MAX_SHIFT_QUADS} quads per row can take this path; past
     * that a quad is emitted at its true geometry and simply does not merge,
     * which is what every non-unit quad already does. No vanilla model comes
     * close (the largest, {@code flowerbed_4}, is 68 quads and only two of
     * them are unit faces).</p>
     */
    private static int shiftKey(int row, int quadIndex, boolean translucent) {
        return (row << 9) | ((quadIndex & (MAX_SHIFT_QUADS - 1)) << 1)
                | (translucent ? 1 : 0);
    }

    /** Quads per row that may take a dynamic shift bucket. See {@link #shiftKey}. */
    private static final int MAX_SHIFT_QUADS = 256;

    /**
     * Mesh one shell column. Pure function of its arguments; any thread.
     * Throws only on codec-contract violations (a malformed shell that
     * {@link ShellCodec#decode} would never produce) — the caller treats
     * a throw as a far-field failure and latches the far field off.
     *
     * <p>The no-neighbours overload: the tests' and any legacy caller's
     * contract, and bit-identical to pre18 output (every apron structure
     * stays empty and every out-of-record policy is unchanged).</p>
     */
    public static Result mesh(ShellCodec.Shell shell, SpriteUvResolver.ResolvedPalette palette) {
        return mesh(shell, palette, null);
    }

    /**
     * Mesh one shell column WITH its 3x3 neighbourhood (S1 - see
     * {@link Apron}). {@code neighbors} is a 9-slot array laid out
     * {@code (dz + 1) * 3 + (dx + 1)} with the centre slot ignored; null
     * (or any null slot) simply leaves that side at the no-neighbour
     * behaviour.
     */
    public static Result mesh(ShellCodec.Shell shell,
            SpriteUvResolver.ResolvedPalette palette, NeighborColumn[] neighbors) {
        // Section Y -> buckets. A tree map because cells arrive sorted
        // ascending by y and the output must be ordered by sy; the map
        // holds a handful of sections (surface band: 1-3 typical).
        Int2ObjectAVLTreeMap<Section> bySection = new Int2ObjectAVLTreeMap<>();
        int skipped = 0;
        int fluidQuads = 0;
        int paletteSize = palette.size();
        int[] storedTint = shell.paletteTint;
        // Layer-1 appearance settings, read ONCE per column. FarFieldConfig
        // is pure loader/GSON/JDK code and is explicitly safe off the game
        // thread (its class javadoc), which is what lets this method stay a
        // pure function of the world's settings without growing a
        // parameter that FarFieldResidency would have to pass.
        Style style = Style.current(shell, palette);
        // Per entry: is this entry's tint grid all one colour? Almost every
        // chunk in a world is inside one biome, and then the bilinear read
        // below is 24 wasted integer ops per cell. Answered once per entry
        // instead of once per cell.
        boolean[] uniformTint = uniformTints(shell, paletteSize);
        // Where the water is, and how deep, derived from this record's own
        // cells. Null - and therefore free - for any column with the row
        // off, with a real light plane in play, or with no fluid in its
        // palette, which is every land chunk in the world.
        WaterColumns water = style.waterDepth()
                ? waterColumns(shell, palette) : null;
        // Which cells draw the UPPER half of a two-block plant. Null - and
        // therefore free - for any column whose palette holds no doubling
        // block, which is most of them.
        boolean[] upperHalf = upperHalves(shell, palette);
        int[] upperRow = palette.upperRow;
        // The 3x3 neighbourhood (S1), or null - and therefore free - when
        // the caller has none (tests, apron off, frontier).
        Apron apron = Apron.of(shell, neighbors);
        int[] apronLo = apron == null ? null : apron.loYRel;
        // The column's full-cube occupancy, for whichever shading step is
        // on. Null - and therefore free - when both are off, and the
        // OCCLUDER GRID IS THE ONLY THING EITHER OF THEM READS, so a
        // column with no full cube in its palette pays two integer passes
        // and nothing else.
        Occluders occ = style.contactShade() > 0.0f || style.ambient() > 0.0f
                ? occluders(shell, palette, upperHalf, upperRow, paletteSize,
                        apron)
                : null;
        // Per-position displacement (P1) and per-position variants (R4).
        // Both are armed only when the record knows where in the world it
        // is; a shell with no origin draws exactly as it always did,
        // rather than stamping chunk (0,0)'s pattern on every chunk.
        boolean offsets = palette.anyOffset && shell.hasOrigin();
        boolean variants = style.variants() && palette.anyVariants
                && shell.hasOrigin();
        boolean positioned = offsets || variants;
        int originX = positioned ? shell.chunkX << 4 : 0;
        int originZ = positioned ? shell.chunkZ << 4 : 0;
        // The record's own fluid/solid field, for vanilla's per-corner
        // fluid heights (P8). Null - and free - for every land chunk.
        Int2FloatOpenHashMap field = fluidField(shell, palette, upperHalf,
                upperRow, apron);
        // Per-face light donors (R5a). Null - and free - unless a real
        // light plane is in play.
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap faceLight =
                style.realLight() ? airLight(shell, apron) : null;
        // Glass/leaves positions for the fluid overlay (R3 row 10). Null -
        // and free - for a column with no fluid or no glassy row anywhere
        // in reach.
        IntOpenHashSet glassy = field == null ? null
                : glassyCells(shell, palette, upperHalf, upperRow, apron);
        for (int i = 0; i < shell.cellCount; i++) {
            int cell = shell.cells[i];
            int pi = ShellCodec.cellPaletteIndex(cell);
            if (pi >= paletteSize || palette.kind[pi] == SpriteUvResolver.KIND_MISSING) {
                skipped++;
                continue;
            }
            // The palette ROW this cell draws: its own entry, or that
            // entry's upper-half row for the top block of a two-block
            // plant. pi stays the RECORD index (the tint grid and the
            // light plane are indexed by it); pe is the appearance.
            int pe = upperHalf != null && upperHalf[i] ? upperRow[pi] : pi;
            int mask = ShellCodec.cellFaceMask(cell);
            if (mask == 0) {
                continue; // codec rejects these; belt and braces
            }
            int x = ShellCodec.cellX(cell);
            int z = ShellCodec.cellZ(cell);
            int y = shell.minY + ShellCodec.cellYRel(cell);
            int sy = y >> 4;         // floor division for negative y
            int ly = y & 15;         // floorMod for a power-of-two base
            Section section = bySection.get(sy);
            if (section == null) {
                section = new Section();
                // yRel = yBase + ly for every quad this section will hold.
                section.yBase = (sy << 4) - shell.minY;
                bySection.put(sy, section);
            }
            // The record's sampled colour at THIS cell when it has one -
            // bilinear over the tint grid, so a biome boundary inside the
            // chunk is a gradient and the chunk edge matches its
            // neighbour. The block's out-of-world default when the record
            // carries no tints at all (a format-1 cache).
            int tint = storedTint == null
                    ? palette.fallbackTint[pe]
                    : cellTint(shell, pi, x, z, uniformTint[pi]);
            // Light for this cell, packed (sky << 8) | block. See Style.
            int light = style.lightFor(shell, palette, pe, i);
            boolean fluid = palette.kind[pe] == SpriteUvResolver.KIND_FLUID;
            if (water != null && !openFluid(fluid, mask)) {
                // Underwater shading: one subtract for a submerged cell,
                // one compare for every other cell in the chunk.
                int column = (z << 4) | x;
                light = submergedLight(light, water.surface()[column],
                        water.floor()[column], y);
            }
            // A fluid cell with an exposed UP face has air above it, so it
            // is the surface of its column and renders 8/9 tall; see the
            // class javadoc's height derivation. There is no other
            // per-KIND branch: a plant, a sheet, a stair and a stone cube
            // all take the one path below and differ only in the quads
            // their palette row holds.
            boolean surface = fluid && (mask & (1 << SpriteUvResolver.FACE_IDX_UP)) != 0;
            // Vanilla's four corner heights for a fluid surface cell (P8).
            // Null when they are all this row's own height, which is EVERY
            // cell of an open ocean - so the surface bucket, its merge and
            // its one rigid drop are untouched there, and only a cell that
            // genuinely sits beside a different level pays a quad.
            float[] corners = null;
            long flow = FLOW_NONE;
            if (surface && field != null) {
                float own = palette.fluidHeight[pe];
                int yRel = ShellCodec.cellYRel(cell);
                int family = familyOf(palette, pe);
                float c00 = fluidCorner(field, apronLo, family, x, yRel, z,
                        -1, -1, own);
                float c10 = fluidCorner(field, apronLo, family, x, yRel, z,
                        1, -1, own);
                float c01 = fluidCorner(field, apronLo, family, x, yRel, z,
                        -1, 1, own);
                float c11 = fluidCorner(field, apronLo, family, x, yRel, z,
                        1, 1, own);
                if (Math.abs(c00 - own) > CORNER_EPSILON
                        || Math.abs(c10 - own) > CORNER_EPSILON
                        || Math.abs(c01 - own) > CORNER_EPSILON
                        || Math.abs(c11 - own) > CORNER_EPSILON) {
                    corners = new float[] {c00, c10, c01, c11};
                    // The top sprite's choice and rotation follow the FLOW
                    // (vanilla keys them on getFlow, not on source-ness).
                    // Computed only for a ramped cell: a non-zero flow
                    // needs a neighbouring height difference, and any
                    // height difference moves the corner average orders of
                    // magnitude past CORNER_EPSILON, so a merged flat cell
                    // is always the zero-flow case its baked still-whole
                    // window already draws.
                    flow = fluidFlow(field, apronLo, family, x, yRel, z, own);
                }
            }
            // The cell's own displacement, from its WORLD position. Zero
            // for every row that does not offset, which is every row of
            // almost every chunk (see positionSeed / offsetXZ).
            float offX = 0.0f;
            float offY = 0.0f;
            float offZ = 0.0f;
            if (offsets && (palette.offsetH[pe] != 0.0f || palette.offsetV[pe] != 0.0f)) {
                long seed = positionSeed(originX + x, 0, originZ + z);
                long xz = offsetXZ(seed, palette.offsetH[pe]);
                offX = Float.intBitsToFloat((int) (xz >>> 32));
                offZ = Float.intBitsToFloat((int) xz);
                offY = offsetY(seed, palette.offsetV[pe]);
            }
            // The cell's own weighted VARIANT (R4). Vanilla rolls it from
            // the position before it collects the model's parts; the roll
            // is reproduced here and the row's own table for that draw
            // substitutes for its baked one. Zero stored bytes, and zero
            // work for every row with no variant list, which is all but
            // 38 blockstates in the game.
            SpriteUvResolver.Quads variantTable = null;
            boolean variantUniform = true;
            if (variants && palette.variantQuads[pe] != null) {
                SpriteUvResolver.Quads[] table = palette.variantQuads[pe];
                variantTable = table[variantDraw(
                        positionSeed(originX + x, y, originZ + z), table.length)];
                variantUniform = palette.variantUniform[pe];
            }
            fluidQuads += emitQuads(section, mask, x, ly, z, palette, pe, tint,
                    light, fluid, surface, style, occ,
                    ShellCodec.cellYRel(cell), offX, offY, offZ, corners, flow,
                    variantTable, variantUniform, faceLight, glassy);
        }
        if (bySection.isEmpty()) {
            return new Result(new SectionMesh[0], skipped, fluidQuads);
        }
        List<SectionMesh> out = new ArrayList<>(bySection.size());
        for (var entry : bySection.int2ObjectEntrySet()) {
            Section section = entry.getValue();
            List<TerrainQuad>[] buckets = section.buckets;
            int total = 0;
            for (List<TerrainQuad> bucket : buckets) {
                total += bucket == null ? 0 : bucket.size();
            }
            if (section.boxBuckets != null) {
                for (List<TerrainQuad> bucket : section.boxBuckets.values()) {
                    total += bucket.size();
                }
            }
            if (section.surfaceBuckets != null) {
                for (List<TerrainQuad> bucket : section.surfaceBuckets.values()) {
                    total += bucket.size();
                }
            }
            List<TerrainQuad> quads = new ArrayList<>(total);
            // Merge each bucket in integral, opaque space, then apply the
            // bucket's transform. See the class javadoc for why they are
            // kept apart across the merge.
            // Only the plain opaque bucket takes Contact Shading. The
            // surface buckets are fluid tops (a sheet of water has no
            // contact with anything), the translucent one is fluid, and
            // the box buckets are re-planed after the merge so their
            // corners are no longer where the merge left them.
            addMerged(quads, buckets[BUCKET_OPAQUE], false,
                    style.contactShade(), occ, section.yBase);
            addMerged(quads, buckets[BUCKET_TRANS], true, 0.0f, null, 0);
            // The fluid-surface buckets, one drop each, taken from the
            // palette row's own state (format 4). A section with no fluid
            // surface in it never allocated the map.
            if (section.surfaceBuckets != null) {
                for (var surf : section.surfaceBuckets.int2ObjectEntrySet()) {
                    int key = surf.getIntKey();
                    addMergedSurface(quads, surf.getValue(),
                            fluidHeightOfRow(palette, key >>> 1), (key & 1) != 0);
                }
            }
            // Then the dynamic shifted-plane buckets, one rigid shift each.
            if (section.boxBuckets != null) {
                for (var box : section.boxBuckets.int2ObjectEntrySet()) {
                    int key = box.getIntKey();
                    addMergedShifted(quads, box.getValue(), palette,
                            key >>> 9, (key >>> 1) & (MAX_SHIFT_QUADS - 1),
                            (key & 1) != 0);
                }
            }
            if (quads.isEmpty()) {
                continue; // cannot happen (a section exists only if it emitted)
            }
            out.add(new SectionMesh(entry.getIntKey(),
                    SectionMeshEncoder.encode(orderTranslucent(quads))));
        }
        return new Result(out.toArray(new SectionMesh[0]), skipped, fluidQuads);
    }

    /**
     * Put a section's TRANSLUCENT quads in back-to-front order for a camera
     * ABOVE them, i.e. lowest first, and move them to the head of the list.
     *
     * <h2>The defect this is the fix for (the owner's pre14 P6, "the ice
     * surface looks wrong, its way clearer than the ice in an actual
     * chunk")</h2>
     * <p>{@code SectionMeshEncoder} writes the TRANSLUCENT PREFIX first and
     * <b>preserves the caller's order inside it</b> (its javadoc says so in
     * as many words: "translucent ordering is the caller's job"). Until this
     * method existed the caller's order was the order the phases above
     * happen to run in - {@code BUCKET_TRANS} first, then the fluid SURFACE
     * buckets - and that puts a sheet of ICE, which is an ordinary
     * translucent block, in front of the WATER SURFACE one block under it.
     * Alpha compositing is order dependent and the LAST thing drawn carries
     * the most weight, so drawing near-to-far inverts exactly the wrong
     * pair.</p>
     *
     * <p>Arithmetic, on the real numbers: {@code ice.png} is a palette image
     * whose {@code tRNS} is 190 on all six entries, i.e. alpha 0.745 on
     * every texel; {@code water_still.png}'s is 180, i.e. 0.706. A frozen
     * ocean column presents, from the top, the ice's UP face, the ice's
     * DOWN face and the water's UP face. Back to front (correct) the ice
     * contributes {@code 0.745 + 0.745 x 0.255 = 0.935} of the pixel and
     * the water {@code 0.046}. Front to back (what shipped) the ice
     * contributes {@code 0.745 x 0.294 + 0.745 x 0.255 x 0.294 = 0.275} and
     * the WATER contributes {@code 0.706}. The ice was showing at 27.5% of
     * its weight instead of 93.5%, which is "way clearer" almost exactly.</p>
     *
     * <h2>Why ascending Y and not a camera sort</h2>
     * The mesher is a pure function of the record and has no camera; a far
     * section is meshed once and drawn from everywhere. Ascending Y is the
     * one camera-free order that is right for the shape this actually
     * happens to - stacked horizontal sheets (water, ice, a glass roof)
     * seen from above, which is where every player spends their time.
     * <b>The failure direction is a camera BELOW a stack of far translucent
     * sheets</b>, which gets today's order back and no worse than it.
     * A real per-camera resort is possible later without touching this
     * (the prefix is physically ordered and {@code TranslucentPrefix
     * .permute} exists for exactly that), and it would need the residency
     * to drive it.
     *
     * <p>Cost: one scan of the section's quads, and a sort only when the
     * section has more than one translucent quad in it. A land chunk has
     * none and pays the scan.</p>
     */
    private static List<TerrainQuad> orderTranslucent(List<TerrainQuad> quads) {
        int trans = 0;
        for (TerrainQuad q : quads) {
            if (q.translucent()) {
                trans++;
            }
        }
        if (trans < 2) {
            return quads; // nothing to order
        }
        List<TerrainQuad> ordered = new ArrayList<>(quads.size());
        List<TerrainQuad> solid = new ArrayList<>(quads.size() - trans);
        for (TerrainQuad q : quads) {
            if (q.translucent()) {
                ordered.add(q);
            } else {
                solid.add(q);
            }
        }
        // Stable, so two sheets at one height keep the order the buckets
        // gave them (which is the cell walk's, i.e. deterministic).
        ordered.sort((a, b) -> Float.compare(lowestY(a), lowestY(b)));
        ordered.addAll(solid);
        return ordered;
    }

    /** The lowest vertex y of a quad - its distance key for {@link #orderTranslucent}. */
    private static float lowestY(TerrainQuad q) {
        return min4(q.v0().y(), q.v1().y(), q.v2().y(), q.v3().y());
    }

    /**
     * Merge one bucket and append it, applying the bucket's transform.
     *
     * @param translucent flag the results for the section's prefix
     * @param contact strength of the Edges gradient, 0 for off (which is
     *                every bucket but the plain opaque one, and also the
     *                whole of the Full step - the two are exclusive)
     * @param occ     the column's full-cube occupancy grid, or null
     * @param yBase   {@code yRel - ly} for this section
     */
    private static void addMerged(List<TerrainQuad> out, List<TerrainQuad> bucket,
            boolean translucent, float contact, Occluders occ, int yBase) {
        if (bucket == null || bucket.isEmpty()) {
            return;
        }
        List<TerrainQuad> merged = GreedyMesher.mergeFar(bucket);
        boolean shade = contact > 0.0f && occ != null;
        for (TerrainQuad q : merged) {
            TerrainQuad r = shade ? contactShade(q, contact, occ, yBase) : q;
            out.add(translucent ? asTranslucent(r) : r);
        }
    }

    /**
     * Merge one FLUID SURFACE bucket and append it, dropping every merged
     * rectangle from the block top to this row's own fluid height.
     *
     * <p>The bucket is keyed by palette ROW precisely so that this drop is
     * one number for everything in it: a source at 8/9 and a 3/9 flowing
     * film are different rows, so they merge separately and fall
     * separately. Before format 4 there was one height in the whole game
     * and two fixed buckets were enough.</p>
     */
    private static void addMergedSurface(List<TerrainQuad> out,
            List<TerrainQuad> bucket, float height, boolean translucent) {
        if (bucket.isEmpty()) {
            return;
        }
        float drop = 1.0f - height;
        for (TerrainQuad q : GreedyMesher.mergeFar(bucket)) {
            TerrainQuad r = drop <= 0.0f ? q : lowerFluidSurface(q, drop);
            out.add(translucent ? asTranslucent(r) : r);
        }
    }

    /**
     * How tall one palette ROW's fluid stands, with the source height as
     * the fallback for a row the palette cannot answer for. Only a fluid
     * cell ever reaches a surface bucket, so the fallback is unreachable in
     * practice; if it were reached it would draw a water top 1/9 of a block
     * out of place rather than a hole.
     */
    private static float fluidHeightOfRow(SpriteUvResolver.ResolvedPalette p, int row) {
        float[] heights = p.fluidHeight;
        return row >= 0 && row < heights.length && heights[row] > 0.0f
                ? heights[row] : FLUID_SURFACE_HEIGHT;
    }

    // ------------------------------------------------------------------
    // Contact Shading (the owner's pre10 L8). See the class javadoc's
    // "The owner's gradient".
    // ------------------------------------------------------------------

    /**
     * Darken the corners of one MERGED rectangle where the rectangle's own
     * edge runs along an occluder, and leave every other quad untouched.
     * This is the {@code SMOOTH_EDGES} step, the cheap rung of the Smooth
     * Lighting row.
     *
     * <h2>What it costs and what it cannot do</h2>
     * It is free: {@code GreedyMesher.merge} has already run and returned,
     * corner colour is one of the five channels its affine test compares,
     * and the value it compared is still the value every cell carried.
     * Nothing here can split a rectangle, so a flat 16x16 top plane is
     * still ONE quad.
     *
     * <p>The price of running after the merge is that its gradient is as
     * wide as the RECTANGLE. On broken ground the rectangles are one to
     * four cells across and that is as tight as vanilla; on flat ground it
     * is a wash sixteen blocks wide where vanilla darkens one block, which
     * is what the owner meant by "the subtle shade in corners" being
     * missing. {@code SMOOTH_FULL} is the step that fixes that, and it
     * pays the merge for it. The two never run together.</p>
     *
     * <p>Its occluder grid is the column's, not the section's, since
     * pre12: an edge lying on a SECTION boundary in y used to read
     * "not occluded" and leave a horizontal line of unshaded blocks every
     * sixteen blocks. Off the end of the COLUMN the answer is still
     * "not occluded" - see {@link Occluders#solid}.</p>
     *
     * <h2>Why the shell already holds the occluders</h2>
     * A shell stores only cells with an exposed face, so most of a hill is
     * not in it. That costs nothing here: the occluder of a visible face
     * sits directly against the air that face looks into, so it has an
     * exposed face of its own and is always stored. A top face's occluder
     * is the cell one block up and one block sideways; that cell borders
     * the air above our own face.
     *
     * <h2>What it draws</h2>
     * An edge is shaded only when EVERY cell along it is occluded, so a
     * ragged edge draws nothing rather than a stripe. A vertex on one such
     * edge keeps {@code 1 - contact/2} of its brightness and a vertex where
     * two of them meet keeps {@code 1 - contact}, which is the shape of
     * vanilla's own three ambient-occlusion steps. The gradient then runs
     * the width of the rectangle, so it is soft where the rectangle is
     * large - and a rectangle is large exactly where the ground is flat,
     * which is where its edges are chunk boundaries and nothing is shaded
     * at all. On broken ground, where this actually fires, the greedy
     * rectangles are one to four cells wide and the gradient is as tight as
     * vanilla's.
     *
     * <h2>Price</h2>
     * Zero bytes on disk, zero bytes in the arena (the factor rides the
     * colour's alpha, which {@code TerrainVertexCodec.premultiplyColor}
     * folds into RGB at encode time and never stores), and zero quads. At
     * mesh time: one bit per stored full cube during the walk the mesher
     * already makes, then at most four edge scans per merged rectangle,
     * whose total length is bounded by the rectangles' perimeter. Off, it
     * is one float compare per bucket.
     *
     * <p>Skipped for anything whose merged corners are not integral - an
     * inset box's face never entered the merge as a unit cell and its
     * rectangle does not correspond to the cell lattice.</p>
     */
    private static TerrainQuad contactShade(TerrainQuad q, float contact,
            Occluders occ, int yBase) {
        float x0 = min4(q.v0().x(), q.v1().x(), q.v2().x(), q.v3().x());
        float x1 = max4(q.v0().x(), q.v1().x(), q.v2().x(), q.v3().x());
        float y0 = min4(q.v0().y(), q.v1().y(), q.v2().y(), q.v3().y());
        float y1 = max4(q.v0().y(), q.v1().y(), q.v2().y(), q.v3().y());
        float z0 = min4(q.v0().z(), q.v1().z(), q.v2().z(), q.v3().z());
        float z1 = max4(q.v0().z(), q.v1().z(), q.v2().z(), q.v3().z());
        if (!integral(x0) || !integral(x1) || !integral(y0) || !integral(y1)
                || !integral(z0) || !integral(z1)) {
            return q; // an inset box's face; not on the cell lattice
        }
        int ix0 = Math.round(x0);
        int ix1 = Math.round(x1);
        int iy0 = Math.round(y0);
        int iy1 = Math.round(y1);
        int iz0 = Math.round(z0);
        int iz1 = Math.round(z1);
        // The OUTSIDE cell layer (one step along the face normal from the
        // cells that own the face) and the two in-plane axes, as a fixed
        // origin plus one unit step per axis. Axis A is the first of the
        // two the rectangle spans, axis B the second.
        int fx = 0;
        int fy = 0;
        int fz = 0;
        int ax = 0;
        int ay = 0;
        int az = 0;
        int bx = 0;
        int by = 0;
        int bz = 0;
        int a0;
        int a1;
        int b0;
        int b1;
        switch (q.facing()) {
            case POS_Y, NEG_Y -> {
                fy = q.facing() == QuadFacing.POS_Y ? iy0 : iy0 - 1;
                ax = 1;
                bz = 1;
                a0 = ix0;
                a1 = ix1;
                b0 = iz0;
                b1 = iz1;
            }
            case POS_X, NEG_X -> {
                fx = q.facing() == QuadFacing.POS_X ? ix0 : ix0 - 1;
                ay = 1;
                bz = 1;
                a0 = iy0;
                a1 = iy1;
                b0 = iz0;
                b1 = iz1;
            }
            case POS_Z, NEG_Z -> {
                fz = q.facing() == QuadFacing.POS_Z ? iz0 : iz0 - 1;
                ax = 1;
                by = 1;
                a0 = ix0;
                a1 = ix1;
                b0 = iy0;
                b1 = iy1;
            }
            default -> {
                return q; // UNASSIGNED: no plane, no edges
            }
        }
        if (a1 <= a0 || b1 <= b0) {
            return q; // degenerate; nothing to walk
        }
        boolean aLow = edgeOccluded(occ, yBase, fx, fy, fz, ax, ay, az, bx, by, bz,
                a0 - 1, b0, b1);
        boolean aHigh = edgeOccluded(occ, yBase, fx, fy, fz, ax, ay, az, bx, by, bz,
                a1, b0, b1);
        boolean bLow = edgeOccluded(occ, yBase, fx, fy, fz, bx, by, bz, ax, ay, az,
                b0 - 1, a0, a1);
        boolean bHigh = edgeOccluded(occ, yBase, fx, fy, fz, bx, by, bz, ax, ay, az,
                b1, a0, a1);
        if (!aLow && !aHigh && !bLow && !bHigh) {
            return q; // the common case: nothing touches this rectangle
        }
        return new TerrainQuad(q.facing(), q.translucent(), q.alphaCutoffIndex(),
                q.mip(),
                shaded(q.v0(), contact, ax, ay, az, bx, by, bz, a0, a1, b0, b1,
                        aLow, aHigh, bLow, bHigh),
                shaded(q.v1(), contact, ax, ay, az, bx, by, bz, a0, a1, b0, b1,
                        aLow, aHigh, bLow, bHigh),
                shaded(q.v2(), contact, ax, ay, az, bx, by, bz, a0, a1, b0, b1,
                        aLow, aHigh, bLow, bHigh),
                shaded(q.v3(), contact, ax, ay, az, bx, by, bz, a0, a1, b0, b1,
                        aLow, aHigh, bLow, bHigh),
                q.repeatU(), q.repeatV());
    }

    /**
     * Is EVERY cell along one edge of the rectangle occluded? The walk runs
     * along the OTHER in-plane axis at the outside layer, one step past the
     * edge on this one. A single unoccluded cell answers false, which is
     * what keeps a ragged edge from drawing a stripe.
     */
    private static boolean edgeOccluded(Occluders occ, int yBase,
            int fx, int fy, int fz,
            int ex, int ey, int ez, int wx, int wy, int wz,
            int edge, int from, int to) {
        for (int w = from; w < to; w++) {
            if (!occ.solid(fx + ex * edge + wx * w,
                    yBase + fy + ey * edge + wy * w,
                    fz + ez * edge + wz * w)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One vertex, darkened by how many of the rectangle's occluded edges it
     * sits on. Its position decides, not its index, because the merge is
     * free to hand the four corners back in any order.
     */
    private static TerrainVertex shaded(TerrainVertex v, float contact,
            int ax, int ay, int az, int bx, int by, int bz,
            int a0, int a1, int b0, int b1,
            boolean aLow, boolean aHigh, boolean bLow, boolean bHigh) {
        float a = ax * v.x() + ay * v.y() + az * v.z();
        float b = bx * v.x() + by * v.y() + bz * v.z();
        int touched = 0;
        if (aLow && Math.abs(a - a0) < PLANE_EPSILON) {
            touched++;
        }
        if (aHigh && Math.abs(a - a1) < PLANE_EPSILON) {
            touched++;
        }
        if (bLow && Math.abs(b - b0) < PLANE_EPSILON) {
            touched++;
        }
        if (bHigh && Math.abs(b - b1) < PLANE_EPSILON) {
            touched++;
        }
        if (touched == 0) {
            return v;
        }
        int colour = v.colorAbgr();
        int alpha = (colour >>> 24) & 0xFF;
        int lit = Math.round(alpha * (1.0f - contact * touched * 0.5f));
        return new TerrainVertex(v.x(), v.y(), v.z(), v.u(), v.v(),
                (Math.max(0, Math.min(255, lit)) << 24) | (colour & 0x00FFFFFF),
                v.blockLight(), v.skyLight());
    }

    /** Is this merged corner on the cell lattice? */
    private static boolean integral(float value) {
        return Math.abs(value - Math.round(value)) < PLANE_EPSILON;
    }

    private static float min4(float a, float b, float c, float d) {
        return Math.min(Math.min(a, b), Math.min(c, d));
    }

    private static float max4(float a, float b, float c, float d) {
        return Math.max(Math.max(a, b), Math.max(c, d));
    }

    /**
     * Merge one SHIFTED-PLANE bucket and append it, moving each merged
     * rectangle back to the quad's own plane.
     *
     * <p>Only a {@link SpriteUvResolver.Quads#unit} quad with a non-zero
     * {@link SpriteUvResolver.Quads#shift} enters one of these buckets, so
     * the transform is a rigid translation of the whole rectangle along the
     * facing's own axis. It needs no span guard, unlike
     * {@link #lowerFluidSurface}: every cell that merged into the rectangle
     * carries the SAME palette row and the same quad index, therefore the
     * same shift, so a 16x16 merged snow top is one plane at one height
     * whatever its extent.</p>
     *
     * <p>This is pre5's dynamic-bucket trick with the box taken out of it.
     * The box model could shift Y only, and only one plane per entry, which
     * is why a stair's two horizontal planes were unrepresentable and a
     * partial block's SIDE faces could never merge. Keyed per quad
     * ({@link #shiftKey}) all six axes work the same way, which is also what
     * lets the nudged coplanar overlay of a grass block keep its merge.</p>
     */
    private static void addMergedShifted(List<TerrainQuad> out,
            List<TerrainQuad> bucket, SpriteUvResolver.ResolvedPalette p,
            int row, int quadIndex, boolean translucent) {
        if (bucket.isEmpty()) {
            return;
        }
        SpriteUvResolver.Quads q = p.quads[row];
        float by = quadIndex < q.count() ? q.shift()[quadIndex] : 0.0f;
        QuadFacing facing = quadIndex < q.count()
                ? QuadFacing.byIndex(q.facing()[quadIndex]) : QuadFacing.UNASSIGNED;
        float dx = facing == QuadFacing.POS_X || facing == QuadFacing.NEG_X ? by : 0.0f;
        float dy = facing == QuadFacing.POS_Y || facing == QuadFacing.NEG_Y ? by : 0.0f;
        float dz = facing == QuadFacing.POS_Z || facing == QuadFacing.NEG_Z ? by : 0.0f;
        for (TerrainQuad merged : GreedyMesher.mergeFar(bucket)) {
            TerrainQuad r = dx == 0.0f && dy == 0.0f && dz == 0.0f
                    ? merged : shiftBy(merged, dx, dy, dz);
            out.add(translucent ? asTranslucent(r) : r);
        }
    }

    /** The same quad with every vertex translated. */
    private static TerrainQuad shiftBy(TerrainQuad q, float dx, float dy, float dz) {
        return new TerrainQuad(q.facing(), q.translucent(), q.alphaCutoffIndex(), q.mip(),
                moved(q.v0(), dx, dy, dz), moved(q.v1(), dx, dy, dz),
                moved(q.v2(), dx, dy, dz), moved(q.v3(), dx, dy, dz),
                q.repeatU(), q.repeatV());
    }

    private static TerrainVertex moved(TerrainVertex v, float dx, float dy, float dz) {
        return new TerrainVertex(v.x() + dx, v.y() + dy, v.z() + dz, v.u(), v.v(),
                v.colorAbgr(), v.blockLight(), v.skyLight());
    }

    /** The same quad, flagged for the section's translucent prefix. */
    private static TerrainQuad asTranslucent(TerrainQuad q) {
        return new TerrainQuad(q.facing(), true, q.alphaCutoffIndex(), q.mip(),
                q.v0(), q.v1(), q.v2(), q.v3(), q.repeatU(), q.repeatV());
    }

    /**
     * Drop a fluid surface quad from the block top by {@code drop} blocks,
     * i.e. to the palette row's own {@code 1 - drop} height.
     *
     * <p>A +Y face is a plane and moves whole. A -Y face is the column's
     * bottom and never moves (it is only in this bucket because it belongs
     * to a surface cell). A side face moves its TOP edge only, and only
     * when the quad is exactly one block tall — the merge cannot stack two
     * fluid-surface cells vertically (class javadoc), so a taller quad here
     * would mean that invariant broke, and leaving it full height is a
     * fraction-of-a-block error rather than a stretched wall.</p>
     */
    private static TerrainQuad lowerFluidSurface(TerrainQuad q, float drop) {
        QuadFacing facing = q.facing();
        if (facing == QuadFacing.NEG_Y) {
            return q;
        }
        if (facing == QuadFacing.POS_Y) {
            return new TerrainQuad(facing, q.translucent(), q.alphaCutoffIndex(), q.mip(),
                    lowered(q.v0(), drop), lowered(q.v1(), drop),
                    lowered(q.v2(), drop), lowered(q.v3(), drop),
                    q.repeatU(), q.repeatV());
        }
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float vy = q.vertex(i).y();
            minY = Math.min(minY, vy);
            maxY = Math.max(maxY, vy);
        }
        if (Math.abs(maxY - minY - 1.0f) > SPAN_EPSILON) {
            return q; // not a single-block-tall side face; leave it alone
        }
        // The top edge's V follows the height it drops to - vanilla's side
        // window is getV((1-h)*0.5)..getV(0.5) (FluidRenderer.tesselate ip
        // 1545-1573), and with the baked window being h = 1's,
        // vBottom + h * (vTop - vBottom) is exactly that. Pre18 the drop
        // moved the vertex and kept the whole window, stretching the side
        // texture by 1/9 of the sprite's top half on every merged surface
        // rectangle (the D5 residual, now closed for free at drop time).
        float vTop = Float.NaN;
        float vBottom = Float.NaN;
        for (int i = 0; i < 4; i++) {
            TerrainVertex vx = q.vertex(i);
            if (Math.abs(vx.y() - maxY) <= SPAN_EPSILON) {
                vTop = vx.v();
            } else {
                vBottom = vx.v();
            }
        }
        float height = 1.0f - drop;
        float croppedV = Float.isNaN(vTop) || Float.isNaN(vBottom)
                ? Float.NaN : vBottom + height * (vTop - vBottom);
        return new TerrainQuad(facing, q.translucent(), q.alphaCutoffIndex(), q.mip(),
                loweredIfTop(q.v0(), maxY, drop, croppedV),
                loweredIfTop(q.v1(), maxY, drop, croppedV),
                loweredIfTop(q.v2(), maxY, drop, croppedV),
                loweredIfTop(q.v3(), maxY, drop, croppedV),
                q.repeatU(), q.repeatV());
    }

    private static TerrainVertex lowered(TerrainVertex v, float drop) {
        return new TerrainVertex(v.x(), v.y() - drop, v.z(), v.u(), v.v(),
                v.colorAbgr(), v.blockLight(), v.skyLight());
    }

    private static TerrainVertex loweredIfTop(TerrainVertex v, float topY,
            float drop, float croppedV) {
        if (Math.abs(v.y() - topY) > SPAN_EPSILON) {
            return v;
        }
        return new TerrainVertex(v.x(), v.y() - drop, v.z(), v.u(),
                Float.isNaN(croppedV) ? v.v() : croppedV,
                v.colorAbgr(), v.blockLight(), v.skyLight());
    }

    /**
     * Emit one cell: every quad of its palette row that the face mask lets
     * through, at the model's own geometry.
     *
     * <p><b>This is the whole geometry path.</b> There is no per-kind
     * branch, no box, no blade and no sheet; a stone cube, a snow layer, a
     * stair, a wall torch, a grass tuft, a pink petal and a sheet of water
     * all come out of this loop and differ only in the
     * {@link SpriteUvResolver.Quads} their row holds. The rule is one
     * line:</p>
     * <pre>
     *   draw the quad if it declares no cullface,
     *   or if the mask says the cullface direction it declares is exposed
     * </pre>
     * which is what a cullface MEANS in a vanilla model, and what
     * {@code ShellExtractor} spent its whole occlusion pass deciding.
     *
     * <h2>What the face MASK means here</h2>
     * The mask bit is a statement about the CELL BOUNDARY - "something is
     * visible across this plane of the cell" - and a cullface is the model
     * author's statement that a quad is hidden when it is not. The two line
     * up exactly, which is why this needs no reconciliation: a snow layer's
     * north face carries {@code "cullface": "north"} and is hidden by a
     * north neighbour that covers it, while its UP face carries no cullface
     * and always draws, because nothing in the cell above can cover a plane
     * 2/16 up. The box model had to reason about this in prose ("a cleared
     * mask bit hides a face the neighbour does NOT cover"); vanilla's own
     * cullface annotations answer it per quad.
     *
     * <h2>Buckets, and how the merge survives real quads</h2>
     * <ul>
     * <li>a quad flush with the cell ({@link SpriteUvResolver.Quads#unit}
     *     with zero {@link SpriteUvResolver.Quads#shift}) goes in a FIXED
     *     bucket, where it is an integral unit face and merges exactly as a
     *     cube's face always did. A flat 16x16 grass top is still ONE
     *     quad;</li>
     * <li>a quad that spans the cell but sits OFF its boundary (a snow
     *     layer's top at 2/16, a slab's at 8/16, a nudged coplanar overlay)
     *     is built AT the boundary in a DYNAMIC bucket keyed by
     *     {@link #shiftKey}, merged, and translated back by
     *     {@link #addMergedShifted}. The same trick pre5 used, per quad
     *     instead of per entry, so a model with two such planes works;</li>
     * <li>everything else - a cross blade, a torch's four walls, a fence
     *     post's sides, a petal's stems - is emitted at its true geometry
     *     into a fixed bucket, where {@code GreedyMesher.eligibleCell}
     *     refuses it and it falls through the pass-through list untouched.
     *     That is what the box model already did for inset faces.</li>
     * </ul>
     *
     * <h2>Winding</h2>
     * The four vertices are emitted in the order {@link SpriteUvResolver}
     * stored them, which is vanilla's own counter-clockwise-from-outside
     * order, already checked there against the cullface the quad declares.
     * The facing rides with them, derived from the same cross product the
     * decoder uses, so this method makes no winding claim of its own -
     * which is the point, because the six hand-written face tables it
     * replaces each carried one.
     *
     * <h2>Smooth lighting rides here, per corner - AND SO DOES THE LIGHT</h2>
     * When the Full step is on, every axis-aligned face of a non-fluid,
     * non-translucent, non-offset row gets vanilla's own four corner
     * shades ({@link #ambientCorners}) folded into its RGB <b>and</b> its
     * four corner lightmap coordinates ({@link #cornerLights}) written
     * per vertex, both before the merge sees them. Only a blade, a
     * fluid, a translucent quad and a per-position-offset quad take the
     * cell values unchanged. The cost when nothing is occluded and the
     * light does not vary is eight bit tests, up to nine hash reads and
     * one compare against zero - and the RESULT is byte-identical to
     * pre18's, so the merge is too.
     *
     * <p><b>pre19 (S3) removed the limitation this paragraph used to
     * record.</b> It said a unit face that is not
     * {@link SpriteUvResolver.Quads#flush} - a snow layer's top at
     * {@code 2/16}, a slab's at {@code 8/16} - is skipped, and priced that
     * as a fraction of a pixel. The owner found it on dirt paths,
     * farmland, slabs, stairs and fences. Vanilla's non-cubic path is now
     * transcribed instead: {@link #faceAtCellBoundary} moves the probe
     * origin onto the block's own cell exactly where
     * {@code BlockModelLighter.prepareQuadShape} clears {@code faceCubic},
     * and {@link #inPlaneA} / {@link #inPlaneB} apply
     * {@code AdjacencyInfo.doNonCubicWeight}'s weighted blend, which in
     * 26.2 is enabled on all six faces.
     *
     * <p><b>Fixed in pre15 (the owner's P4), and still fixed:</b> the test
     * used to be {@code shift == 0}, which ALSO skipped a quad that is
     * flush with the cell and merely carries this resolver's
     * coplanar-overlay nudge - every grass block's tinted side overlay is
     * such a quad. pre18 answered that with the pre-nudge
     * {@link SpriteUvResolver.Quads#flush} flag; pre19 answers it with
     * {@link #faceAtCellBoundary}'s tolerance, which covers the non-unit
     * faces the flag could not speak for. One mechanism, not two.</p>
     *
     * <h2>Per-position offset</h2>
     * {@code offX/offY/offZ} are vanilla's {@code state.getOffset(pos)} for
     * this cell, already derived from its world position by the caller, and
     * they are added to every vertex - the same thing
     * {@code ModelBlockRenderer.tesselateBlock} does at ip 83-163. A cell
     * that moves is excluded from the shifted-plane bucket and from the
     * ambient corners, because both of those reason about a lattice the
     * quad is no longer on; it falls through to its true geometry, which
     * {@code GreedyMesher.eligibleCell} refuses (a non-integral plane) and
     * passes through unmerged. That costs nothing measurable, because not
     * one of the 39 offsetting blocks in the game owns a mergeable unit
     * face - they are 34 plants, 2 speleothems, bamboo, a propagule and
     * hanging roots.
     *
     * @param occ  the column's full-cube occupancy grid, or null when
     *             neither shading step is on
     * @param yRel this cell's y in {@code occ}'s coordinates
     * @param offX vanilla's per-position x displacement, 0 for the blocks
     *             that do not have one (which is all but 39 of them)
     * @param offY the same, downward
     * @param offZ the same, on z
     * @param corners vanilla's four fluid corner heights for a fluid
     *                SURFACE cell whose corners are not all its own height
     *                ({@link #fluidCorner}), else null. When present the
     *                cell leaves the fluid-surface bucket - a merged
     *                rectangle can take one rigid drop and this cell is a
     *                ramp - and every vertex the model puts at the top of
     *                the cell takes its own corner's height instead, its
     *                lateral top V follows that height
     *                ({@code getV((1 - h) * 0.5)}, the vanilla side
     *                window), and its top face may take the flow-rotated
     *                window below.
     * @param flow    {@link #fluidFlow}'s packed {@code (cos, sin) * 0.25}
     *                for a ramped fluid surface cell, else
     *                {@link #FLOW_NONE}. Non-zero swaps the top face's
     *                baked still-whole window for the flowing sprite's
     *                rotated quarter window - vanilla's ip 748-939 branch.
     * @return the number of FLUID quads emitted (the counter's input)
     */
    private static int emitQuads(Section section, int mask, int x, int y, int z,
            SpriteUvResolver.ResolvedPalette p, int row, int tint, int light,
            boolean fluid, boolean surface, Style style, Occluders occ, int yRel,
            float offX, float offY, float offZ, float[] corners, long flow,
            SpriteUvResolver.Quads variant, boolean variantUniform,
            it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap faceLight,
            IntOpenHashSet glassy) {
        boolean moved = offX != 0.0f || offY != 0.0f || offZ != 0.0f;
        // The cell's per-position VARIANT table (R4) when it has one, the
        // row's single baked table otherwise.
        SpriteUvResolver.Quads q = variant != null ? variant : p.quads[row];
        if (q == null || q.count() == 0) {
            return 0;
        }
        float[] pos = q.pos();
        float[] uv = q.uv();
        int emitted = 0;
        for (int i = 0; i < q.count(); i++) {
            byte cull = q.cull()[i];
            if (cull != SpriteUvResolver.NO_CULL && (mask & (1 << cull)) == 0) {
                continue; // the neighbour across that plane covers this quad
            }
            QuadFacing facing = QuadFacing.byIndex(q.facing()[i]);
            boolean unit = q.unit()[i];
            float shift = q.shift()[i];
            int pb = i * SpriteUvResolver.POS_STRIDE;
            int ub = i * SpriteUvResolver.UV_STRIDE;
            // Past MAX_SHIFT_QUADS the key could not carry the quad's index
            // and two quads of one row would share a bucket and a shift, so
            // the quad is emitted at its true geometry instead: correct, and
            // merely unmerged. No vanilla model reaches the cap.
            // A variant row whose tables disagree on the merge-routing
            // fields cannot use the shifted-plane bucket: that bucket is
            // keyed by quad INDEX and takes ONE rigid translate, so two
            // variants whose quad i sits at different shifts would merge
            // and then both move by one of the two. Emitted at true
            // geometry instead - correct, merely unmerged. Every rotation
            // family is uniform (a rotation permutes a cube's faces
            // without moving any routing field), so this costs nothing
            // where the census says the quads are.
            boolean shifted = unit && shift != 0.0f && i < MAX_SHIFT_QUADS
                    && !moved && (variant == null || variantUniform);
            boolean trans = q.translucent()[i];
            // A surface fluid cell's -Y face is the bottom of the column and
            // keeps full geometry, so it rides the non-surface bucket and can
            // still merge with its neighbours' bottoms.
            boolean lower = surface && facing != QuadFacing.NEG_Y && corners == null;
            List<TerrainQuad> out;
            if (lower) {
                // Keyed by ROW, so only cells of the SAME fluid state merge
                // and the whole rectangle takes that state's own drop.
                out = section.surfaceBucket((row << 1) | (trans ? 1 : 0));
            } else if (shifted) {
                out = section.boxBucket(shiftKey(row, i, trans));
            } else {
                out = section.bucket(trans ? BUCKET_TRANS : BUCKET_OPAQUE);
            }
            // Solid Leaves takes only a full cube's own flush faces. Nothing
            // else touches the alpha test: the cutoff already IS vanilla's
            // per-quad verdict on this quad's own uv window.
            int cutoff = style.cutoffFor(q.cutoff()[i],
                    !p.fullCube[row] || !unit || q.overlay()[i]);
            int rgb = q.tinted()[i] ? tint : SpriteUvResolver.WHITE_RGB;
            // Vanilla's directional shade, but only where the MODEL asks for
            // it: every element of cross.json, template_torch and the
            // redstone torches declares "shade": false, so those come out at
            // full brightness with no list of exceptions here.
            int shadeAlpha = q.shaded()[i] ? shadeFor(facing) : SHADE_CROSS;
            int c = color(rgb, shadeAlpha);
            // SMOOTH LIGHTING, ONE RULE FOR EVERY FACE (the owner's S3 and
            // S5). Ambient occlusion and the lightmap coordinate are both
            // per corner, both before the merge, and both taken at the
            // same eight probes - which is how vanilla does it, in one
            // method (BlockModelLighter.prepareQuadAmbientOcclusion).
            //
            // What is excluded, and why each: a quad with no axis has no
            // plane and no corners; a fluid's byte is its surface sample
            // and lava lights the rock around it rather than being shaded
            // by it (R3 row 13 verified the fluid case against
            // getLightCoords); a translucent quad is not in this bucket;
            // and a per-position OFFSET quad (P1's 39 plants) is left at
            // pre18 exactly, because its cell's probe lattice is right but
            // nothing in that family owns an axis-aligned face anyway.
            //
            // WHAT IS NO LONGER EXCLUDED IS THE WHOLE OF S3. pre18 gated
            // both paths on Quads#flush - a UNIT face whose plane is the
            // cell boundary - so a dirt path's and farmland's 15/16 top, a
            // slab's 8/16 top, and every non-unit face of a stair or a
            // fence got no ambient occlusion at all. Vanilla does not skip
            // them: it moves the probe ORIGIN onto the block's own cell
            // (faceAtCellBoundary below is its faceCubic test) and weights
            // the four corner values by the face's real extent (inPlaneA /
            // inPlaneB). The flush flag's own job - keeping a grass
            // block's 1/1024-nudged side overlay judged flush, the pre14
            // P4 fix - is subsumed by faceAtCellBoundary's tolerance, so
            // this is ONE mechanism and not two.
            //
            // AN EMITTING BLOCK TAKES NEITHER CORNER PATH - it keeps the
            // flat per-face light below - and that is vanilla's own
            // gate rather than ours: ModelBlockRenderer ip 48-80 routes a
            // quad to prepareQuadAmbientOcclusion only when the global AO
            // setting is on AND `state.getLightEmission() == 0` AND the
            // model part's own `useAmbientOcclusion()`; anything else goes
            // to prepareQuadFlat, which writes no corner colour and no
            // corner light but still moves the LIGHT read to the cell the
            // face looks into. So glowstone, a sea lantern, magma and a
            // jack o'lantern keep flat corners here, which they did not at
            // pre18. The third clause of that gate is the one thing the
            // far field still cannot answer - see the S3 write-up's
            // "the gate we cannot yet read".
            boolean litFace = !fluid && !moved
                    && facing != QuadFacing.UNASSIGNED;
            boolean smooth = litFace && !trans && p.emission[row] == 0;
            int ox = x;
            int oy = yRel;
            int oz = z;
            if (smooth && (faceAtCellBoundary(facing, pos, pb)
                    || p.fullCube[row])) {
                // faceCubic: the probe layer is one step along the normal.
                ox += stepX(facing);
                oy += stepY(facing);
                oz += stepZ(facing);
            }
            int ambient = smooth && occ != null && style.ambient() > 0.0f
                    ? ambientCorners(occ, facing, ox, oy, oz) : 0;
            // PER-FACE LIGHT (R5a). Vanilla lights a face at the cell it
            // LOOKS INTO - prepareQuadAmbientOcclusion ip 821-867 and
            // prepareQuadFlat ip 16-52 both read
            // pos.setWithOffset(pos, quad.direction()) - and only keeps
            // the block's own coordinate when the face is non-cubic AND
            // that cell renders solid. The donor map answers both arms at
            // once: it holds a byte only for positions some record's plane
            // sampled as AIR, so a solid neighbour is simply absent and
            // the cell byte stands. Without this a wall cell whose UP face
            // is sunlit wore sky 15 on the side face looking into a shaded
            // pocket, beside the pocket floor's own correct byte - the
            // owner's R5 "perfect on some block faces and not right on
            // others". It now runs for every face the rule above admits,
            // not only the flush unit ones, which is S3's half of it.
            int faceLightValue = light;
            long cornerLight = 0L;
            if (faceLight != null && litFace) {
                int donor = -1;
                int nx = x + stepX(facing);
                int ny = yRel + stepY(facing);
                int nz = z + stepZ(facing);
                if (nx >= -1 && nx <= 16 && nz >= -1 && nz <= 16
                        && ny >= -1 && ny <= 256) {
                    donor = faceLight.get(apronKey(nx, ny, nz));
                }
                if (donor >= 0) {
                    faceLightValue = style.lightFromByte(donor, p, row);
                }
                // S5: and the four corners around that reference. Zero
                // when the row is off; also effectively zero-cost where
                // the light does not vary, because every probe then
                // returns the reference and all four corners come back
                // equal to faceLightValue - byte-identical to pre18, and
                // therefore a constant merge channel exactly as before.
                if (smooth && style.smoothLight()) {
                    cornerLight = cornerLights(faceLight, occ, style, facing,
                            ox, oy, oz,
                            donor >= 0 ? style.lightRaw(donor) : light, ambient);
                }
            }
            int lv0 = faceLightValue;
            int lv1 = faceLightValue;
            int lv2 = faceLightValue;
            int lv3 = faceLightValue;
            if (cornerLight != 0L) {
                lv0 = style.withGlow(vertexLight(cornerLight, facing, pos, pb),
                        p, row);
                lv1 = style.withGlow(
                        vertexLight(cornerLight, facing, pos, pb + 3), p, row);
                lv2 = style.withGlow(
                        vertexLight(cornerLight, facing, pos, pb + 6), p, row);
                lv3 = style.withGlow(
                        vertexLight(cornerLight, facing, pos, pb + 9), p, row);
            }
            // A shifted quad is built at the cell boundary its facing names,
            // so the sweep sees an integral plane; addMergedShifted puts it
            // back afterwards.
            float dx = offX;
            float dy = offY;
            float dz = offZ;
            if (shifted) {
                switch (facing) {
                    case POS_X, NEG_X -> dx = -shift;
                    case POS_Y, NEG_Y -> dy = -shift;
                    case POS_Z, NEG_Z -> dz = -shift;
                    default -> { }
                }
            }
            // A ramped fluid surface cell's UVs are per POSITION (the
            // flow-rotated top window, the height-cropped side V) and
            // cannot stay baked; everything else keeps the row's table by
            // reference and pays one null check.
            float[] uvq = uv;
            int ubq = ub;
            if (fluid && surface && corners != null) {
                float[] over = fluidUv(p, row, facing, pos, pb, uv, ub,
                        corners, flow);
                if (over != null) {
                    uvq = over;
                    ubq = 0;
                }
            }
            // THE FLUID OVERLAY (R3 row 10, closed on S1's mechanism).
            // Vanilla swaps a fluid SIDE face's sprite to water_overlay
            // when the neighbour across it is glass or leaves
            // (FluidRenderer.tesselate ip 1483-1527: overlayMaterial()
            // non-null AND the neighbour block instanceof
            // HalfTransparentBlock || instanceof LeavesBlock), and it
            // computes its side windows as fractions of whichever sprite
            // won - so remapping this quad's flowing-rect fraction onto
            // the overlay rect IS vanilla's arithmetic. The neighbour's
            // identity comes from the record beside this one when the
            // face lies on a chunk plane, which is why this could not be
            // answered before the 3x3.
            if (fluid && glassy != null && facing != QuadFacing.POS_Y
                    && facing != QuadFacing.NEG_Y
                    && facing != QuadFacing.UNASSIGNED) {
                SpriteUvResolver.FluidLook look = p.fluidLook[row];
                if (look != null && look.hasOverlay()
                        && glassy.contains(apronKey(x + stepX(facing),
                                yRel + stepY(facing), z + stepZ(facing)))) {
                    float[] over = new float[8];
                    for (int k = 0; k < 4; k++) {
                        over[k * 2] = look.overlayU(uvq[ubq + k * 2]);
                        over[k * 2 + 1] = look.overlayV(uvq[ubq + k * 2 + 1]);
                    }
                    uvq = over;
                    ubq = 0;
                }
            }
            out.add(quad(facing, cutoff,
                    v(x + pos[pb] + dx, y + rampY(corners, pos, pb) + dy,
                            z + pos[pb + 2] + dz,
                            uvq[ubq], uvq[ubq + 1],
                            cornerColor(c, ambient, facing, rgb, shadeAlpha,
                                    style.ambient(), pos, pb), lv0),
                    v(x + pos[pb + 3] + dx, y + rampY(corners, pos, pb + 3) + dy,
                            z + pos[pb + 5] + dz,
                            uvq[ubq + 2], uvq[ubq + 3],
                            cornerColor(c, ambient, facing, rgb, shadeAlpha,
                                    style.ambient(), pos, pb + 3), lv1),
                    v(x + pos[pb + 6] + dx, y + rampY(corners, pos, pb + 6) + dy,
                            z + pos[pb + 8] + dz,
                            uvq[ubq + 4], uvq[ubq + 5],
                            cornerColor(c, ambient, facing, rgb, shadeAlpha,
                                    style.ambient(), pos, pb + 6), lv2),
                    v(x + pos[pb + 9] + dx, y + rampY(corners, pos, pb + 9) + dy,
                            z + pos[pb + 11] + dz,
                            uvq[ubq + 6], uvq[ubq + 7],
                            cornerColor(c, ambient, facing, rgb, shadeAlpha,
                                    style.ambient(), pos, pb + 9), lv3)));
            if (fluid) {
                emitted++;
            }
        }
        return emitted;
    }

    /**
     * One vertex's colour: the cell colour, or that colour with its
     * corner's ambient occlusion folded into the RGB.
     *
     * <p>{@code ambient} is zero for every quad the Full step does not
     * apply to and for every face with nothing occluded around it, which
     * is the whole of an open plain, so the common path is one compare
     * and the cell colour.</p>
     *
     * <h2>The level is a FRACTION now, and that is S3 (pre19)</h2>
     * <p>{@code ambient} holds the four LATTICE corners of the cell; a
     * vertex that does not sit on one of them - a slab's mid-height side,
     * a fence post's inset edge, a dirt path's 15/16 rim - takes the
     * bilinear value at where it really is, which is vanilla's own
     * {@code facePartial && doNonCubicWeight} arm
     * ({@code BlockModelLighter.prepareQuadAmbientOcclusion} ip 922-936
     * branches to it; ip 1748-1909 applies four {@code faceShape} products
     * per vertex). {@code doNonCubicWeight} is <b>true on all six faces</b>
     * in 26.2 (javap, {@code AdjacencyInfo}'s {@code <clinit>}: the
     * boolean after the shade float is {@code iconst_1} at ip 37, 300,
     * 564, 828, 1092 and 1356), so there is no face this does not cover
     * and no second rule to remember. A vertex ON a lattice corner gets
     * weights of exactly {@code 1.0f} and {@code 0.0f} and therefore the
     * integer level pre18 looked up, which is why every full cube in every
     * record already on disk still encodes byte for byte.</p>
     *
     * @param vb index into {@code pos} of this vertex's three floats
     */
    private static int cornerColor(int cellColor, int ambient, QuadFacing facing,
            int rgb, int shadeAlpha, float strength, float[] pos, int vb) {
        if (ambient == 0) {
            return cellColor;
        }
        float fa = inPlaneA(facing, pos, vb);
        float fb = inPlaneB(facing, pos, vb);
        float level = (ambient & 3) * (1.0f - fa) * (1.0f - fb)
                + ((ambient >>> 2) & 3) * (1.0f - fa) * fb
                + ((ambient >>> 4) & 3) * fa * (1.0f - fb)
                + ((ambient >>> 6) & 3) * fa * fb;
        return level <= 0.0f ? cellColor
                : color(ambientRgb(rgb, level, strength), shadeAlpha);
    }

    /**
     * The x step from a cell to the cell one face of it looks into. Local
     * to this class rather than on {@link QuadFacing}, whose ordinal order
     * is a GPU-bucket contract that nothing here should widen.
     */
    private static int stepX(QuadFacing facing) {
        return facing == QuadFacing.POS_X ? 1 : facing == QuadFacing.NEG_X ? -1 : 0;
    }

    /** The y step; see {@link #stepX}. */
    private static int stepY(QuadFacing facing) {
        return facing == QuadFacing.POS_Y ? 1 : facing == QuadFacing.NEG_Y ? -1 : 0;
    }

    /** The z step; see {@link #stepX}. */
    private static int stepZ(QuadFacing facing) {
        return facing == QuadFacing.POS_Z ? 1 : facing == QuadFacing.NEG_Z ? -1 : 0;
    }

    /**
     * Vanilla's directional face shade for one facing, as the alpha byte
     * {@code premultiplyColor} folds into RGB (seam dossier section 4.3's
     * table). {@link QuadFacing#UNASSIGNED} takes full brightness: a quad
     * with no axis has no direction to shade by, and vanilla's own answer
     * for the models that produce them is {@code "shade": false} anyway.
     */
    private static int shadeFor(QuadFacing facing) {
        return switch (facing) {
            case POS_Y -> SHADE_UP;
            case NEG_Y -> SHADE_DOWN;
            case POS_Z, NEG_Z -> SHADE_NZ;
            case POS_X, NEG_X -> SHADE_EW;
            default -> SHADE_CROSS;
        };
    }

    /**
     * One vertex's y inside the cell, with vanilla's fluid corner height
     * substituted for a vertex the model puts at the cell's TOP.
     *
     * <p>{@code corners} is null for everything that is not a ramped fluid
     * surface cell, which is every quad in the world but a stream's, and
     * then this is one null check. The corner is chosen by the vertex's own
     * x and z, which for the synthesized fluid cube are exactly 0 or 1;
     * the order matches {@link #fluidCorner}'s call sites,
     * {@code index = 2*z + x}.</p>
     */
    private static float rampY(float[] corners, float[] pos, int vb) {
        float py = pos[vb + 1];
        if (corners == null || py < 1.0f - CORNER_EPSILON) {
            return py;
        }
        return corners[(pos[vb + 2] >= 0.5f ? 2 : 0) + (pos[vb] >= 0.5f ? 1 : 0)];
    }

    /**
     * The per-position UVs of one ramped fluid surface cell's quad, or
     * null when the baked row window already IS vanilla's answer.
     *
     * <h2>The top face (vanilla {@code FluidRenderer.tesselate} ip
     * 748-939)</h2>
     * With a non-zero flow the top wears {@code flowingMaterial()} through
     * a quarter-size window rotated to the flow angle. From the bytecode,
     * with {@code c = cos(theta) * 0.25} and {@code s = sin(theta) * 0.25}
     * (theta = {@code atan2(flow.z, flow.x) - PI/2}), the four corners'
     * window fractions are
     * <pre>
     *   (X=0,Z=0): u 0.5-c-s  v 0.5-c+s      (ip 807-839)
     *   (X=0,Z=1): u 0.5-c+s  v 0.5+c+s      (ip 841-872)
     *   (X=1,Z=1): u 0.5+c+s  v 0.5+c-s      (ip 874-905)
     *   (X=1,Z=0): u 0.5+c-s  v 0.5-c-s      (ip 907-939)
     * </pre>
     * i.e. {@code u = 0.5 + (X ? c : -c) + (Z ? s : -s)} and
     * {@code v = 0.5 + (Z ? c : -c) - (X ? s : -s)}, evaluated per vertex
     * from its own cell corner - so this does not depend on the
     * synthesized quad's vertex order. Desk check, flow due +z:
     * {@code theta = 0, c = 0.25, s = 0}: the axis-aligned centre window
     * with v increasing along +z - the streak scrolls downstream.
     *
     * <h2>The side faces (ip 1545-1573)</h2>
     * Vanilla's side window is {@code getU(0)..getU(0.5)} by
     * {@code getV((1 - h) * 0.5)..getV(0.5)}, the TOP edge's V following
     * that corner's height. The row bakes {@code h = 1}'s window, so the
     * top vertices interpolate: with {@code vTop = getV(0)} (the baked
     * value they carry) and {@code vBottom = getV(0.5)} (a bottom
     * vertex's), {@code vBottom + h * (vTop - vBottom)} IS
     * {@code getV((1 - h) * 0.5)} exactly. Before this the ramp moved the
     * geometry and left the window whole, stretching the side texture by
     * the ramp's depth - up to 7/9 of the half-window on a level-7 edge,
     * the last visible piece of the owner's "flowing water still not
     * perfect". The merged flat cells' equivalent (one rigid drop) gets
     * the same crop in {@code lowerFluidSurface}.
     */
    private static float[] fluidUv(SpriteUvResolver.ResolvedPalette p, int row,
            QuadFacing facing, float[] pos, int pb, float[] uv, int ub,
            float[] corners, long flow) {
        if (facing == QuadFacing.POS_Y) {
            if (flow == FLOW_NONE) {
                return null; // zero flow: the baked still-whole window
            }
            SpriteUvResolver.FluidLook look = p.fluidLook[row];
            if (look == null) {
                return null; // a hand-built palette without the rect
            }
            float c = Float.intBitsToFloat((int) (flow >>> 32));
            float s = Float.intBitsToFloat((int) flow);
            float[] over = new float[8];
            for (int k = 0; k < 4; k++) {
                boolean xHigh = pos[pb + k * 3] >= 0.5f;
                boolean zHigh = pos[pb + k * 3 + 2] >= 0.5f;
                float fu = 0.5f + (xHigh ? c : -c) + (zHigh ? s : -s);
                float fv = 0.5f + (zHigh ? c : -c) - (xHigh ? s : -s);
                over[k * 2] = look.flowU0() + fu * (look.flowU1() - look.flowU0());
                over[k * 2 + 1] = look.flowV0() + fv * (look.flowV1() - look.flowV0());
            }
            return over;
        }
        if (facing == QuadFacing.NEG_Y || facing == QuadFacing.UNASSIGNED) {
            return null; // the bottom face's still-whole window is height-free
        }
        float vBottom = Float.NaN;
        for (int k = 0; k < 4; k++) {
            if (pos[pb + k * 3 + 1] < 0.5f) {
                vBottom = uv[ub + k * 2 + 1];
                break;
            }
        }
        if (Float.isNaN(vBottom)) {
            return null; // no bottom vertex: not the synthesized side shape
        }
        float[] over = null;
        for (int k = 0; k < 4; k++) {
            if (pos[pb + k * 3 + 1] < 1.0f - CORNER_EPSILON) {
                continue;
            }
            if (over == null) {
                over = new float[8];
                System.arraycopy(uv, ub, over, 0, 8);
            }
            float h = rampY(corners, pos, pb + k * 3);
            over[k * 2 + 1] = vBottom + h * (uv[ub + k * 2 + 1] - vBottom);
        }
        return over;
    }

    private static TerrainQuad quad(QuadFacing facing, int cutoff,
            TerrainVertex v0, TerrainVertex v1, TerrainVertex v2, TerrainVertex v3) {
        // mip=true for ALL far quads again - the pre16 far-cutout mip
        // clamp (mip=false + CUTOUT keyed terrain.frag's FAR_CUTOUT_MAX_LOD
        // rescale) is DELETED at pre18 (R1), because the coverage table
        // that justified it was mis-simulated. The pre16 numbers modelled
        // MipmapGenerator as gamma-blend + scaleAlphaToCoverage at 0.5 and
        // nothing else; the real 26.2 chain (generateMipLevels, javap:
        // signature carries a MipmapStrategy AND the sprite's
        // alpha_cutoff_bias float; scaleAlphaToCoverage ip 150-160 writes
        // clamp(alpha * bestScale + BIAS + 0.025)) also solidifies the
        // base (TextureUtil.solidify) and ADDS the per-sprite bias -
        // kelp.png.mcmeta and kelp_plant.png.mcmeta both declare
        // "alpha_cutoff_bias": 0.1. Re-measured over the real PNGs with
        // the full chain, fraction of texels passing the CUTOUT 0.5 test:
        //
        //             mip2   mip3          mip4
        //   kelp      0.27   0.50          0.00  (alpha 99..104: GONE)
        //   kelp_plant 0.52  1.00 (20/20)  0.00
        //   seagrass  0.36   0.28          9/18 frames solid
        //
        // So the clamp pinned every far cutout at the one level where
        // kelp_plant is a HALF-ON dither (52%) - a permanent bright-green
        // shimmer over every kelp sea, at distances where vanilla's own
        // mips draw either a solid (and depth-lit, dark) wall at mip 3 or
        // NOTHING at mip 4. The identical-by-construction answer is to
        // sample exactly what vanilla samples: the atlas's own
        // coverage-rescaled mips, unclamped. Mesh/draw-time; every record
        // repaints on the next remesh.
        // translucent=false ALWAYS here: the merge refuses translucent
        // input, so the flag is applied after merging (class javadoc).
        return new TerrainQuad(facing, false, cutoff, true, v0, v1, v2, v3);
    }

    /**
     * One vertex. {@code light} is the packed pair
     * {@code (skyRaw &lt;&lt; 8) | blockRaw}, both already in the codec's
     * [8,248] byte domain - see {@link Style#lightFor}.
     */
    private static TerrainVertex v(float x, float y, float z, float u, float vv,
            int colorAbgr, int light) {
        return new TerrainVertex(x, y, z, u, vv, colorAbgr,
                light & 0xFF, (light >>> 8) & 0xFF);
    }

    /** 0xAABBGGRR from an 0xRRGGBB tint + the face shade in alpha. */
    private static int color(int rgb, int shadeAlpha) {
        int r = (rgb >>> 16) & 0xFF;
        int g = (rgb >>> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (shadeAlpha << 24) | (b << 16) | (g << 8) | r;
    }
}
