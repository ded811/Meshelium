/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.farfield.mesh;

import com.deds.meshelium.farfield.store.ShellCodec;
import com.deds.meshelium.terrain.QuadFacing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Far field: palette block STATES to the by-value GEOMETRY the off-thread
 * {@link ShellMesher} draws. This is the ONLY far-field mesh class that
 * touches vanilla objects, and it runs on the GAME thread exclusively
 * ({@code FarFieldResidency}'s pump) — the resolved {@link ResolvedPalette}
 * is plain primitive arrays, safe to hand to the IO thread by value
 * (FARFIELD-CODEBASE-SEAM.md section 6.4: the IO thread never touches a
 * vanilla object).
 *
 * <h2>THE MODEL, and what it replaced (pre11, the owner's L0)</h2>
 * <p>The owner's standing instruction: "we dont need a case by case basis
 * here we need a solid plan. if a block is not solid and partially see
 * through we need to treat it as such", and "all 3d models should work and
 * just use the regular minecraft ones".</p>
 *
 * <p><b>There is one rule in this class and this is it: a palette entry's
 * geometry is the block's own BAKED QUADS.</b> Not a kind, not a bounding
 * box, not a representative sprite per direction — the quads vanilla itself
 * would draw, positions and atlas UVs and material and tint flag and shade
 * flag, copied out of {@code BakedQuad} into primitive arrays
 * ({@link Quads}) and handed to the mesher. {@link ShellMesher} emits a
 * quad when it has no cullface, or when the cell's face mask says the
 * cullface direction it declares is exposed. That sentence is the whole
 * geometry contract.</p>
 *
 * <p><b>What that deleted.</b> pre2 to pre10 accumulated a separate shape
 * rule per block family, each one correct for the family that prompted it
 * and wrong for the next one:</p>
 * <ul>
 * <li>{@code KIND_CROSS}, "no quad of the model lies on a block-boundary
 *     plane, so draw two crossed blades". It classified by what the model
 *     does NOT have, so every small block that floats clear of its own cell
 *     was a plant: <b>a wall torch</b> ({@code block/template_torch_wall} is
 *     one 2x10x2 cuboid from {@code [-1,3.5,7]} rotated -22.5 degrees about
 *     Z, and after that rotation not one of its faces sits on x, y or z
 *     equal to 0 or 1) came out a cross and drew as the owner's "streched
 *     torch texture taking up a x shape". A floor torch escaped only by the
 *     accident that {@code template_torch}'s {@code down} face is at y = 0.
 *     The discriminator was not regressed; it was never able to tell a
 *     small BOX from a pair of SHEETS, and no amount of tuning gives it
 *     that.</li>
 * <li>{@code KIND_DECAL}, the second exception carved out of the first when
 *     ground flowers stood up like bushes, with a height cap
 *     ({@code DECAL_MAX_HEIGHT}) pinned between pink petals and a pitcher
 *     crop.</li>
 * <li>The per-entry BOX, which drew a slab at slab height and a stair as a
 *     cube, because "a bounding box cannot express a missing quarter".</li>
 * <li>{@code PLANE_EPSILON}, a tolerance pinned by iron bars on one side
 *     and fire on the other.</li>
 * </ul>
 * All four are gone. A stair is its two elements, a fence post is a post, a
 * wall torch is a tilted stick, a cross is the model's own two rescaled
 * 45-degree blades, and a pink petal is a sheet with stems — because in
 * every case the mesher is drawing what vanilla bakes.
 *
 * <h2>Format 4 removed the last approximation: the STATE is stored</h2>
 * <p>Until format 4 a shell stored a block NAME per cell, so the quads
 * resolved here were the DEFAULT state's and everything a blockstate
 * property would have changed was lost - a stair's {@code facing} /
 * {@code half} / {@code shape}, {@code snow}'s {@code layers}, a fence's
 * connections, a wall torch's {@code facing}, a grass block's
 * {@code snowy}, a leaf litter's {@code segment_amount} and {@code facing},
 * a fluid's {@code level}. That was the owner's whole pre11 list and it was
 * one sentence in {@code ShellCodec}, not fifteen heuristics here.</p>
 *
 * <p><b>A palette entry is now a block STATE</b>
 * ({@link ShellCodec.Shell#paletteState}: its index within its own block's
 * state list, guarded by a per-name signature), and this class resolves
 * that state's own model. Nothing else about the rule changed: the geometry
 * is still the baked quads, and it is now the baked quads of the right
 * state. Two consequences worth naming, because both DELETE work that used
 * to live here:</p>
 * <ul>
 * <li>a {@code DOUBLE_BLOCK_HALF} block no longer needs the upper-half row
 *     and the mesher's per-cell run test - the record says which half it
 *     is. The machinery stays for records already on disk (see
 *     {@link #resolveOne}) and costs a null check when it is not needed;</li>
 * <li>{@code ShellCodec}'s reserved two mask bits for a four-class fluid
 *     height are not needed either: water's {@code level} is an ordinary
 *     property, so a flowing cell is a different palette entry and
 *     {@link #resolveFluid} reads all nine of vanilla's heights, plus
 *     {@code falling}, off the real {@code FluidState}.</li>
 * </ul>
 *
 * <h2>Per-POSITION appearance (pre15, the owner's P1)</h2>
 * <p>Two cells of the same state are one palette entry, which is right -
 * but vanilla does not draw two such cells identically. It derives a
 * pseudorandom DISPLACEMENT from the block position
 * ({@code ModelBlockRenderer.tesselateBlock} ip 39-46:
 * {@code state.getOffset(pos)}), which is what stops a field of grass
 * standing in rows, and the far field had none of it. That is the owner's
 * "grass plant displacement isnt correct".</p>
 *
 * <p>It costs <b>zero stored bytes</b>, because it is derivable at MESH
 * time from the cell's world position. This class publishes the two
 * per-block magnitudes it needs ({@link #offsetMagnitudes}, probed because
 * {@code getMaxHorizontalOffset} is {@code protected}) and
 * {@code ShellMesher} does the position arithmetic itself, so no vanilla
 * object crosses to the IO thread. The whole family is 39 blocks - 34
 * {@code XZ} and 5 {@code XYZ} - and not one of them owns a mergeable unit
 * face, so the merge pays nothing.</p>
 *
 * <p><b>What a record still cannot say.</b> Two cells of the same state at
 * different positions are one entry, which is correct; what is NOT stored
 * is anything derived from a neighbour AT MESH TIME rather than at
 * extraction time - a fluid's flow VECTOR (so a flowing top wears an
 * unrotated streak), vanilla's per-corner fluid height averaging (so a
 * slope steps rather than ramps), and the {@code overlayMaterial()} a fluid
 * wears against glass or leaves. All three want the neighbouring cells'
 * states at draw time, and the shell is meshed one chunk at a time.</p>
 *
 * <h2>Price, measured against what the box model emitted</h2>
 * Per cell the mesher emits one quad per model quad that survives the mask,
 * where it used to emit one per set mask bit. Counted on the real 26.2
 * assets:
 * <table border="1">
 * <caption>quads per exposed cell, box model vs baked quads</caption>
 * <tr><th>block<th>was<th>now<th>note
 * <tr><td>full cube (stone, dirt, sand, log, leaves)<td>1 per exposed face
 *     <td>1 per exposed face<td>the model has exactly one cullface quad per
 *     direction and no unculled ones, so this is quad-for-quad identical.
 *     It is the overwhelming majority of every chunk.
 * <tr><td>{@code grass_block} side<td>1<td>2<td>the tinted
 *     {@code #overlay} element, i.e. the owner's L5
 * <tr><td>cross plant<td>4 blades<td>4<td>{@code block/cross} is two
 *     zero-thickness elements each declaring {@code north} and
 *     {@code south}: four quads, which is what {@code emitCross} was
 *     hand-building
 * <tr><td>snow layer, slab, carpet, path<td>1 per exposed face<td>same
 *     <td>one element, six faces, five with a cullface
 * <tr><td>floor torch<td>5<td>6<td>one cullface quad plus five interior
 * <tr><td>wall torch<td>4 blades<td>6<td>and correct
 * <tr><td>stairs<td>5<td>8 to 12<td>the notch exists now
 * <tr><td>rail, lily pad<td>1<td>2<td>the model declares up and down
 * <tr><td>pink petals, wildflowers<td>1<td>14, 22, 36 or 42
 *     <td>the multipart STACKS by {@code flower_amount}:
 *     {@code flowerbed_1} (a sheet plus six two-quad stems, 14 quads) is
 *     unconditional, {@code flowerbed_2} (+8) joins at 2,
 *     {@code flowerbed_3} (+14) at 3 and {@code flowerbed_4} (+6) at 4.
 *     Counted off the 26.2 jar. The one real regression; see below.
 * <tr><td>leaf litter<td>1<td>2, or 4 at {@code segment_amount} 3
 *     <td>{@code template_leaf_litter_*} is ONE zero-thickness sheet with
 *     an up and a down face and no stems, so 2 quads - except at
 *     {@code segment_amount} 3, where the multipart's {@code 2|3} and
 *     {@code 3} selectors both apply and it is 4.
 * </table>
 *
 * <p><b>Per chunk.</b> The census's mean surface-band chunk owns about 768
 * exposed faces (25.7% of its 2,986 total). Everything in the "identical"
 * row is unchanged, so the delta is the sum of the rows that are not:</p>
 * <ul>
 * <li>a flat plains chunk: 256 top faces and almost no exposed sides, so
 *     the grass overlay costs nearly nothing. <b>+0 to +10 quads</b>;</li>
 * <li>a hilly grass chunk: roughly 150 exposed grass sides on a 16-block
 *     relief, one overlay each. <b>+150 quads pre-merge, +9.6 KB of arena
 *     at 64 B/quad</b> — and the overlay merges exactly as well as the base
 *     it sits on (same plane, same span, one sprite, one tint), so the
 *     post-merge delta is the same 20% and not worse;</li>
 * <li>a cherry grove or flower forest with 60 petal cells at
 *     {@code flower_amount} 4: <b>+2,460 quads, +157 KB</b> (60 x 42
 *     against 60 x 1). This is the case that pays, and it pays because six
 *     two-quad stems and up to four sheets now exist where one sheet did.
 *     It is bounded to three blockstates - {@code pink_petals},
 *     {@code wildflowers} and {@code leaf_litter} - and it is the honest
 *     cost of "use the regular minecraft ones".</li>
 * </ul>
 *
 * <p><b>Resolve-time and memory.</b> One model walk per distinct block
 * NAME, cached forever (same as before). A {@link Quads} table is 12
 * position floats, 8 UV floats and 10 small flags per quad, so a 10-quad
 * model is about 900 bytes; a world's whole block set at 200 distinct names
 * is under 200 KB, held once in {@link #CACHE} and SHARED by reference into
 * every {@link ResolvedPalette} (the tables are never mutated after
 * construction). A palette therefore costs one object reference per entry
 * where the box model cost six floats plus 42 per-face slots.</p>
 *
 * <h2>Where the merge survives, which is the part that had to be checked</h2>
 * {@link com.deds.meshelium.terrain.GreedyMesher#merge} accepts a quad only
 * when it is a UNIT face on an INTEGRAL plane. Two facts keep it:
 * <ul>
 * <li>a full cube's baked cullface quad IS that, exactly, so a flat 16x16
 *     grass top is still ONE merged quad and not 256;</li>
 * <li>a quad that spans the whole cell on both in-plane axes but sits OFF
 *     the cell boundary (a snow layer's top at y = 2/16, a slab's at 8/16,
 *     the nudged grass overlay below) is flagged {@link Quads#unit} with a
 *     {@link Quads#shift}. The mesher builds it AT the cell boundary, lets
 *     the sweep merge it, and shifts the merged plane back — pre5's
 *     dynamic-bucket trick, now driven per QUAD instead of per entry, so a
 *     model with two shifted planes (a stair) gets two buckets instead of
 *     being unrepresentable.</li>
 * </ul>
 * Everything else — a cross blade, a fence post's side, a torch's four
 * walls — is handed to the merge at its true geometry and falls through its
 * pass-through list untouched, which is what the box model already did for
 * inset faces.
 *
 * <h2>Coplanar elements: the grass overlay, and the ONE nudge</h2>
 * <p>{@code block/grass_block} is two elements at the same bounds. The
 * first carries {@code #side} (untinted dirt-with-a-gray-fringe) on all
 * four sides; the second carries {@code #overlay}
 * ({@code grass_block_side_overlay}) on the same four, {@code tintindex 0}.
 * The old ladder took "the first quad per direction" and therefore always
 * got the untinted one — the owner's L5, "the grass block side tint is
 * missing". Emitting the model's quads emits both, which is what vanilla
 * draws.</p>
 *
 * <p>Vanilla can leave the two exactly coplanar because it draws SOLID and
 * CUTOUT in two passes with the depth test admitting equality. The far
 * field draws one pass, and its depth compare is
 * {@code VK_COMPARE_OP_GREATER_OR_EQUAL} (reversed Z,
 * {@code TerrainDrawPipeline}), so a later coplanar primitive does win —
 * but the greedy merge reorders by plane and sprite key, so "later" is not
 * a promise this class may make. So the SECOND and any further quad sharing
 * one entry's plane and facing is pushed {@value #OVERLAY_NUDGE} block
 * outward along its own normal, in model order. Cost: nothing (a float add
 * once per block name, no extra quads), and 1/1024 block is 1/64 of a
 * texture pixel. Depth resolution at 1,000 blocks under reversed-Z float is
 * about 4e-12 per ULP against the 5e-11 this buys, so it separates.</p>
 *
 * <p><b>The nudge is ours, not the model's, and one consumer has to be
 * told so.</b> {@link Quads#shift} carries it, because the mesher must
 * really displace the plane to separate it; but a quad that was flush
 * before the nudge is still, to any reader asking "are this quad's four
 * vertices on the cell's own lattice corners", flush after it. That reader
 * is {@code ShellMesher}'s per-corner smooth lighting, and testing
 * {@code shift == 0} instead cost every grass block its side overlay's
 * ambient occlusion (the owner's pre14 P4). {@link Quads#flush} is the
 * pre-nudge answer and is what that gate reads now.</p>
 *
 * <p>The test is coplanar-and-same-facing, not coplanar-and-OVERLAPPING,
 * which means a model whose two elements put disjoint faces on one plane
 * takes the nudge too - {@code block/oak_stairs} declares a {@code north}
 * face at z = 0 on each of its two elements, one the lower half and one the
 * upper-right quarter, and the second is pushed 1/1024 out for nothing.
 * Testing the overlap as well would be a rectangle intersection per pair
 * per block name to save a displacement of 1/64 of a pixel, so it is not
 * done and it is written down instead.</p>
 *
 * <h2>Winding is DERIVED, not asserted</h2>
 * The decoder reads a quad's facing as the right-handed cross
 * {@code (v1-v0) x (v2-v0)} pointing ALONG the facing
 * ({@code VanillaMeshDecoder.deriveFacing}). Vanilla's baked quads are
 * wound counter-clockwise seen from outside, which is the same convention,
 * and the near field proves it every frame by decoding vanilla's own
 * compiled sections. {@link #facingOf} computes that cross and takes the
 * axis it lands on; a quad that is not axis-aligned (a cross blade, a
 * tilted torch) is {@link QuadFacing#UNASSIGNED} and is never face-culled.
 * As a belt-and-braces check that costs one compare per quad at resolve
 * time, a quad that DECLARES a cullface whose derived facing disagrees with
 * it has its vertex order reversed, so the convention is verified per model
 * rather than assumed once.
 *
 * <h2>Material, tint and shade all come from the quad</h2>
 * <ul>
 * <li><b>Layer</b>: {@code materialInfo().layer()} is vanilla's own answer
 *     to "which chunk layer does this draw in", derived at bake time from
 *     the sprite's alpha content plus {@code force_translucent} (26.2 has
 *     no {@code render_type} field in any vanilla block model — verified by
 *     scanning all 2,657 of them). SOLID maps to cutoff index 0, CUTOUT to
 *     index 2 (0.5, the near field's {@code VanillaMeshDecoder
 *     .materialCutoffIndex}), TRANSLUCENT to index 0 plus the section's
 *     translucent prefix.</li>
 * <li><b>Tint</b>: WHERE tint applies is {@code materialInfo().tintIndex()
 *     >= 0}, per QUAD, which is what makes the grass overlay green and the
 *     base beneath it plain. WHICH colour is biome data sampled at
 *     EXTRACTION time and carried in the record (see {@link #sampleTint}),
 *     because the far field has no biome source at mesh time. The index
 *     sampled is the MODEL's own ({@link #modelTintIndex}), not a
 *     hard-wired 0: sixty vanilla models use index 0 and eight use index 1
 *     (the {@code pink_petals_*} and {@code wildflowers_*} stems), none
 *     uses two, and sampling 0 for the eight painted their stems white.</li>
 * <li><b>Shade</b>: {@code materialInfo().shade()}, per quad. Vanilla's
 *     directional face shade (up 1.0, down 0.5, north/south 0.8, east/west
 *     0.6) applies only where the model asks for it, so a cross, a torch
 *     and a redstone torch — every element of which declares
 *     {@code "shade": false} — come out at full brightness exactly as
 *     vanilla draws them, with no list of exceptions here.</li>
 * </ul>
 *
 * <h2>Fluids (and the waterlogged-vegetation rule)</h2>
 * A block whose model yields at least one quad is a BLOCK, whatever fluid
 * it carries; only a block with a genuinely empty model AND a non-empty
 * fluid state takes {@link #KIND_FLUID}, which is precisely water and lava
 * ({@code assets/minecraft/models/block/water.json} is
 * {@code {"textures":{"particle":"block/water_still"}}}, no elements). A
 * fluid has no baked model to copy, so {@link #resolveFluid} synthesizes
 * the six cube faces it needs; from the mesher's point of view it is an
 * ordinary {@link Quads} table and takes the ordinary path.
 *
 * <p>A block with an empty model and no fluid is {@link #KIND_MISSING} and
 * draws nothing: barriers, light blocks, structure voids, and the block
 * entities (chests, banners, heads, shulker boxes) that have no block model
 * at all.</p>
 *
 * <h2>26.2 accessors (javap-verified on the merged jar this session)</h2>
 * <pre>
 * Minecraft:          public net.minecraft.client.resources.model.ModelManager getModelManager();
 *                     public net.minecraft.client.color.block.BlockColors getBlockColors();
 * ModelManager:       public net.minecraft.client.renderer.block.BlockStateModelSet getBlockStateModelSet();
 *                     public net.minecraft.client.renderer.block.FluidStateModelSet getFluidStateModelSet();
 * BlockStateModelSet: public net.minecraft.client.renderer.block.dispatch.BlockStateModel
 *                       get(net.minecraft.world.level.block.state.BlockState);
 *                     (bytecode: modelByState.getOrDefault(state, missingModel) - NEVER null)
 * BlockStateModel:    public abstract void collectParts(net.minecraft.util.RandomSource,
 *                       java.util.List&lt;...BlockStateModelPart&gt;);
 * BlockStateModelPart: public abstract java.util.List
 *                       &lt;net.minecraft.client.resources.model.geometry.BakedQuad&gt;
 *                       getQuads(net.minecraft.core.Direction);
 * QuadCollection.getQuads: enumSwitch maps NULL to case -1 =&gt; the
 *                       "unculled" list (bytecode ip 4-66).
 * BakedQuad:          public org.joml.Vector3fc position(int);
 *                     public long packedUV(int);
 *                     public net.minecraft.core.Direction direction();
 *                     public ...BakedQuad$MaterialInfo materialInfo();
 *                     public static final int VERTEX_COUNT = 4;
 * UVPair:             public static float unpackU(long);
 *                     public static float unpackV(long);
 *                     (FaceBakery.bakeVertex ip 159-185 writes
 *                      UVPair.pack(sprite.getU(u), sprite.getV(v)), so an
 *                      unpacked pair is ATLAS space, directly usable in the
 *                      16-byte terrain vertex's UV field.)
 * BakedQuad$MaterialInfo:
 *                     public net.minecraft.client.renderer.texture.TextureAtlasSprite sprite();
 *                     public net.minecraft.client.renderer.chunk.ChunkSectionLayer layer();
 *                     public int tintIndex();
 *                     public boolean shade();
 * FaceBakery:         bakeVertex ip 7-21: select(from,to).div(16.0f), so a
 *                      baked position is BLOCK space, where the cell runs
 *                      0..1; BLOCK_MIDDLE is (0.5, 0.5, 0.5).
 * ChunkSectionLayer:  public static final ...SOLID / CUTOUT / TRANSLUCENT;
 * FluidStateModelSet: public net.minecraft.client.renderer.block.FluidModel
 *                       get(net.minecraft.world.level.material.FluidState);
 *                     (bake() maps WATER and FLOWING_WATER to ONE model and
 *                      LAVA and FLOWING_LAVA to another, ip 26-45, so a
 *                      flowing state resolves the same model as its source)
 * FluidModel:         public net.minecraft.client.renderer.chunk.ChunkSectionLayer layer();
 *                     public ...Material$Baked stillMaterial();
 *                     public ...Material$Baked flowingMaterial();
 *                     public net.minecraft.client.color.block.BlockTintSource tintSource();
 * Material$Baked:     public net.minecraft.client.renderer.texture.TextureAtlasSprite sprite();
 * TextureAtlasSprite: public float getU0(); getU1(); getV0(); getV1();
 * BlockColors:        public java.util.List&lt;...BlockTintSource&gt;
 *                       getTintSources(net.minecraft.world.level.block.state.BlockState);
 * BlockTintSource:    public abstract int color(BlockState);
 *                     public default int colorInWorld(BlockState,
 *                       BlockAndTintGetter, BlockPos);
 * Direction:          DOWN, UP, NORTH, SOUTH, WEST, EAST - declaration order
 *                     0..5, the codec's face-mask bit order verbatim.
 * BlockStateBase:     public boolean isAir();
 *                     public net.minecraft.world.level.material.FluidState getFluidState();
 *                     public int getLightEmission();
 * ModelManager:       public boolean requiresRender(BlockState, BlockState);
 *                     (the visual-equality oracle format 4's palette keys
 *                      on; bytecode transcribed in {@link #sameAppearance})
 * StateDefinition:    public com.google.common.collect.ImmutableList&lt;S&gt;
 *                       getPossibleStates();
 *                     public java.util.Collection
 *                       &lt;...properties.Property&lt;?&gt;&gt; getProperties();
 * Property:           public java.lang.String getName();
 *                     public abstract java.util.List&lt;T&gt; getPossibleValues();
 * FluidState:         public boolean isSource();
 *                     public float getOwnHeight();   (= amount / 9)
 * </pre>
 * Every sprite reached here lives on the BLOCK atlas, the same atlas the
 * terrain drawer binds.
 *
 * <h2>Two-block plants: one NAME, two models (pre-format-4 records only)</h2>
 * Tall grass, large fern, sunflower, lilac, rose bush, peony, tall seagrass
 * and every door are two blocks whose halves wear DIFFERENT models. A
 * format-4 record stores the {@code half} property like any other and this
 * whole mechanism sits idle; a record written before format 4 knows only
 * the name the two halves share, so both halves are resolved here and the
 * upper one rides inside the cached entry
 * ({@link ResolvedPalette#upperRow} is the row it lands in) while
 * {@link ShellMesher} decides per CELL which of the two a cell draws. The
 * derivation and the property test are in {@link #resolveOne}.
 *
 * <h2>Cache</h2>
 * Resolution is cached per block STATE (and, for a record older than
 * store format 4, per NAME) on the game thread — one model walk
 * per distinct block in the world, never one per cell and never one per
 * chunk. The cache is cleared whenever the far walker re-arms (camera
 * crossing / slider change), which bounds staleness after a resource-pack
 * reload changes atlas stitching or model geometry to a few seconds of
 * walking.
 */
public final class SpriteUvResolver {

    /** Faces of a cell, in the codec's mask bit order. */
    public static final int FACES = 6;

    // Face INDICES, not mask bits. They are the codec's mask BIT NUMBERS
    // (so bit == 1 << index) and also Direction's declaration order, which
    // is what makes Direction.values()[index] the matching direction.
    // ShellExtractor's same-named FACE_* constants are the BITS; these are
    // the indices, hence the different prefix.
    /** Face index 0: -Y (codec mask bit 0). */
    public static final int FACE_IDX_DOWN = 0;
    /** Face index 1: +Y (mask bit 1). */
    public static final int FACE_IDX_UP = 1;
    /** Face index 2: -Z (mask bit 2). */
    public static final int FACE_IDX_NORTH = 2;
    /** Face index 3: +Z (mask bit 3). */
    public static final int FACE_IDX_SOUTH = 3;
    /** Face index 4: -X (mask bit 4). */
    public static final int FACE_IDX_WEST = 4;
    /** Face index 5: +X (mask bit 5). */
    public static final int FACE_IDX_EAST = 5;

    /** Palette entry could not resolve; the mesher skips its cells. */
    public static final byte KIND_MISSING = 0;
    /** A block with a real baked model: {@link Quads} copied out of it. */
    public static final byte KIND_BLOCK = 1;
    /**
     * A fluid: empty block model plus a non-empty {@code FluidState}. Its
     * {@link Quads} are the six cube faces {@link #resolveFluid}
     * synthesizes, and the mesher lowers a surface cell's top to this
     * row's own {@link ResolvedPalette#fluidHeight} (see
     * {@code ShellMesher}'s fluid section).
     */
    public static final byte KIND_FLUID = 2;

    /** Untinted: multiplying by white is the identity. */
    public static final int WHITE_RGB = 0xFFFFFF;

    /**
     * How far the second and further coplanar quads of one entry are pushed
     * outward along their own normal, in blocks.
     *
     * <p>1/1024. See the class javadoc's "Coplanar elements": vanilla
     * separates {@code grass_block}'s base side from its tinted overlay by
     * pass order across two chunk layers, and a single-pass renderer whose
     * merge is free to reorder needs a geometric separation instead. The
     * value has to clear the depth buffer's resolution at layer-1 range and
     * stay under a texture pixel: at 1,000 blocks under reversed-Z float
     * depth one ULP is about 4e-12 of normalized depth and 1/1024 block is
     * about 5e-11, thirteen ULPs; and 1/1024 block is 1/64 of a 16x16
     * texture pixel.</p>
     */
    static final float OVERLAY_NUDGE = 1.0f / 1024.0f;

    /**
     * How far a coordinate may sit from a cell boundary and still count as
     * ON it, in blocks, for the {@link Quads#unit} test only.
     *
     * <p>6.25e-5 block is the smallest real offset vanilla produces
     * ({@code iron_bars_post_ends} caps at element y 0.001 and 15.999) and
     * float error in the bake is around 1e-7, so 2e-4 sits three orders of
     * magnitude above the noise and three times under the smallest thing it
     * must not swallow. Unlike the constant it replaces this tolerance
     * decides NOTHING about what a block is; it only decides whether a quad
     * can take the merge's fast path, and being wrong about that costs
     * quads, never correctness.</p>
     */
    static final float CELL_EPSILON = 2.0e-4f;

    /**
     * How far a quad's normal may lean off an axis and still be treated as
     * axis-aligned, as a fraction of its longest component.
     *
     * <p>1e-3. Everything vanilla bakes is either exactly axis-aligned
     * (float error 1e-7) or a deliberate rotation of at least 22.5 degrees,
     * whose smallest off-axis component is {@code tan(22.5) = 0.414} of the
     * largest. There is nothing in between for this to get wrong.</p>
     */
    static final float AXIS_EPSILON = 1.0e-3f;

    /**
     * Deterministic model seed for {@code collectParts}. Vanilla passes
     * {@code state.getSeed(pos)} = {@code Mth.getSeed(pos)} (javap:
     * {@code BlockBehaviour.getSeed} ip 0-4, and
     * {@code SectionCompiler} ip 319-326 hands it to
     * {@code tesselateBlock}), so a WEIGHTED variant list is chosen per
     * POSITION; a palette entry has one geometry table, so the far field
     * chooses once, here, with a stable seed.
     *
     * <p><b>The old comment on this field claimed the choice was
     * invisible, and the census says otherwise.</b> Of the 75 weighted rows
     * in the 26.2 jar (38 blockstates, 0 skipped), 46 are y or x rotations
     * of ONE model - invisible only where the texture is rotationally
     * symmetric, which grass, sand and dirt are not - and <b>29 list
     * genuinely different models</b>: {@code stone}/{@code stone_mirrored},
     * {@code deepslate}/{@code deepslate_mirrored}, {@code bedrock},
     * {@code sculk}, {@code bamboo1..4}, {@code chorus_plant_noside0..3}
     * and the two fires. So a far-field stone wall is one texture where
     * vanilla mixes two, and a far bamboo grove has every stalk in the same
     * corner of its cell.</p>
     *
     * <p><b>Fixed at pre18 (the owner's R4) for every single-draw variant
     * list</b> - see docs/FARFIELD-WAVES.md, "R4 AND R5 ANSWERED (S1)".
     * {@link #enumerateVariants} builds one {@link Quads} table per draw
     * value and {@code ShellMesher.variantDraw} reproduces vanilla's own
     * roll from the cell's world position, behind the Block Variants row
     * (default ON, the accuracy directive). This seed still matters twice:
     * it is the table a record with no origin draws, and it is the whole
     * look when the row is OFF or enumeration refused a model
     * ({@link #farVariantMultiDraw} - the four multiparts). One number
     * worth knowing about it: for every 4-option row, seed 42's draw is
     * variant 2 (y=180), so the pre18 far field showed EVERY grass top,
     * sand cell and dirt cell at 180 degrees - which is why the owner
     * could see "the grass texture on the top is rotated" against the
     * near field's per-position mix.</p>
     */
    private static final long MODEL_SEED = 42L;

    /** Floats per quad in {@link Quads#pos}: four vertices of x, y and z. */
    public static final int POS_STRIDE = 12;
    /** UVs per quad in {@link Quads#uv}: four vertices of u, v. */
    public static final int UV_STRIDE = 8;

    /** {@link Quads#cull} value for a quad the face mask never gates. */
    public static final byte NO_CULL = -1;

    /**
     * {@code tintIndex} value for a model no quad of which is tinted, i.e.
     * one whose sampled colour is never multiplied into anything. It is
     * vanilla's own "no tint" value ({@code BakedQuad$MaterialInfo.isTinted}
     * is {@code tintIndex != -1}).
     */
    public static final int NO_TINT_INDEX = -1;

    /**
     * One block's baked geometry, by value. Every array is
     * {@link #count}-long except {@link #pos} ({@value #POS_STRIDE} floats
     * per quad) and {@link #uv} ({@value #UV_STRIDE} per quad), and none is
     * ever mutated after construction — which is what lets one table be
     * shared by reference from {@link #CACHE} into every
     * {@link ResolvedPalette} that names the block, and read from the IO
     * thread.
     *
     * <p>Positions are in CELL space, where the cell runs 0..1 on every
     * axis; that is the same BLOCK space {@code FaceBakery} bakes into, so
     * nothing is converted. UVs are ATLAS space, directly usable in the
     * terrain vertex.</p>
     *
     * @param count       how many quads
     * @param pos         4 x (x, y, z) per quad, in vanilla's own winding
     *                    order (corrected against the cullface where the
     *                    quad declares one)
     * @param uv          4 x (u, v) per quad, atlas space
     * @param cull        per quad: the face INDEX 0..5 whose mask bit gates
     *                    it, or {@link #NO_CULL} for a quad that always
     *                    draws
     * @param facing      per quad: the {@link QuadFacing} ordinal derived
     *                    from the quad's own winding
     * @param cutoff      per quad: alpha cutoff index from the vanilla layer
     * @param translucent per quad: goes in the section's translucent prefix
     * @param tinted      per quad: the model tints it, so multiply by the
     *                    record's sampled biome colour
     * @param shaded      per quad: {@code materialInfo().shade()} — apply
     *                    vanilla's directional face shade
     * @param unit        per quad: an axis-aligned rectangle covering the
     *                    whole cell on both of its in-plane axes, so the
     *                    greedy merge can take it (after {@link #shift})
     * @param overlay     per quad: it is coplanar with an earlier quad of
     *                    the same entry and has been nudged outward, so it
     *                    is a decorative layer over another face and must
     *                    never be solidified by the Solid Leaves row
     * @param shift       per quad, meaningful only when {@link #unit}: how
     *                    far the quad's plane sits from the cell boundary
     *                    plane its facing names (0 for a face flush with the
     *                    cell). The mesher builds at the boundary, merges,
     *                    then shifts by this.
     * @param flush       per quad: it is a {@link #unit} face whose plane
     *                    IS the cell boundary its facing names, judged
     *                    BEFORE the {@value #OVERLAY_NUDGE} coplanar nudge
     *                    this class applies. That nudge is our own
     *                    z-fighting fix and not a property of the model, so
     *                    {@link #shift} alone cannot answer "are this
     *                    quad's four vertices on the cell's own lattice
     *                    corners" - and that question is what
     *                    {@code ShellMesher}'s smooth lighting asks. See
     *                    docs/FARFIELD-WAVES.md, "P4 AND P5 ANSWERED".
     */
    public record Quads(int count, float[] pos, float[] uv, byte[] cull,
                        byte[] facing, byte[] cutoff, boolean[] translucent,
                        boolean[] tinted, boolean[] shaded, boolean[] unit,
                        boolean[] overlay, float[] shift, boolean[] flush) {

        /** An entry with no geometry at all. */
        static Quads empty() {
            return new Quads(0, new float[0], new float[0], new byte[0],
                    new byte[0], new byte[0], new boolean[0], new boolean[0],
                    new boolean[0], new boolean[0], new boolean[0],
                    new float[0], new boolean[0]);
        }
    }

    /**
     * One shell palette, resolved. Owned by the request after construction;
     * contains no vanilla references, so the IO thread may read it freely.
     *
     * <p>Per-entry arrays are indexed by palette index, except that an entry
     * with a distinct upper half owns a second ROW past
     * {@link #size()} — {@link #upperRow} names it.</p>
     */
    public static final class ResolvedPalette {
        /**
         * RECORD palette entries, i.e. {@code shell.palette.length}. The
         * arrays below hold one row per entry PLUS one for each entry that
         * has a distinct upper half, so this is not their length.
         */
        private final int entries;
        /**
         * Per entry: the ROW that entry's UPPER half lives in, or the
         * entry's own index when it has none. {@code null} when no entry in
         * this palette doubles, which is the fast exit for every palette
         * without a tall plant or a door in it.
         */
        final int[] upperRow;
        /** Per row: one of the KIND_* constants. */
        final byte[] kind;
        /**
         * Per row: the block's own light emission, 0..15
         * ({@code BlockStateBase.getLightEmission()}, javap-verified).
         *
         * <p>This is the ZERO-COST half of the lighting work
         * ({@code FarFieldConfig.LIGHT_GLOW}). It is a property of the
         * BLOCK, not of the world, so it needs no storage and no
         * extraction-time sample: a torch is emission 14 wherever it stands.
         * {@link ShellMesher} puts it in the vertex's block-light channel for
         * the emitting block's OWN quads.</p>
         */
        final byte[] emission;
        /**
         * Per row: the out-of-world default tint (0xRRGGBB), used only when
         * the record carries no sampled tint table (a format-1 cache written
         * before the table existed).
         */
        final int[] fallbackTint;
        /** Per row: the block's baked geometry. Never null. */
        final Quads[] quads;
        /**
         * Per row: how tall this row's fluid stands when its cell is the
         * top of its column, as a fraction of a block. 1.0 for everything
         * that is not a fluid, and for a fluid it is vanilla's own
         * {@code FluidState.getOwnHeight()} = {@code amount / 9}
         * (javap: {@code FlowingFluid.getOwnHeight} ip 0-9), so a source is
         * 8/9 and a one-ninth flowing film is 1/9.
         *
         * <p>This is the piece of block state that is not a rotation: a
         * flowing fluid drawn at the source height is the whole block
         * wrong, which is why {@code ShellCodec}'s version 3 javadoc
         * reserved two mask bits for it. Format 4 stores the fluid's real
         * {@code level} as an ordinary state index and this field is read
         * off it, so the two spare bits stay spare.</p>
         */
        final float[] fluidHeight;
        /**
         * Per row: the fluid's mesh-time appearance half (identity family
         * for the corner average's same-fluid rule, the flowing sprite's
         * rect for the flow-rotated top window), or null for every row
         * that is not a fluid. See {@link FluidLook}.
         */
        final FluidLook[] fluidLook;
        /**
         * Per row: the model owns a flush, unshifted, non-overlay quad on
         * ALL SIX cell faces, i.e. it fills its cell exactly.
         *
         * <p>Derived from the quads rather than declared, and it is the one
         * summary the mesher wants that scanning the table per cell would be
         * silly for. Two rows read it: Solid Leaves solidifies only a full
         * cube's faces, and Contact Shading counts only a full cube as an
         * occluder. A snow layer, a stair, a fence post, a torch and a cross
         * are all false; leaves, stone and grass_block are true. A FLUID is
         * true as well (its synthesized cube is exactly that), which is why
         * both readers also test the KIND.</p>
         */
        final boolean[] fullCube;
        /**
         * Per row: how far this block's model may be displaced HORIZONTALLY
         * from its cell, in blocks, or 0 for a block that is never
         * displaced. See {@link #offsetMagnitudes}.
         */
        final float[] offsetH;
        /**
         * Per row: how far DOWNWARD, in blocks, or 0. Zero also for an
         * {@code OffsetType.XZ} block, whose y term vanilla never
         * computes - which is why the mesher needs no offset TYPE, only
         * these two numbers. See {@link #offsetMagnitudes}.
         */
        final float[] offsetV;
        /**
         * Does ANY row of this palette move? False for the overwhelming
         * majority of chunks (the whole family is 39 blocks, all of them
         * plants and speleothems), and it is what keeps the mesher's per
         * cell cost at one boolean read.
         */
        boolean anyOffset;
        /**
         * Per row: one {@link Quads} table per DRAW of the blockstate's
         * weighted variant list ({@link #enumerateVariants}), or null for
         * the overwhelming majority of rows that have no per-position
         * choice. Length is the list's total weight; equal draws share one
         * table by reference. {@code ShellMesher.variantDraw} picks the
         * index from the cell's world position - vanilla's own
         * {@code setSeed(getSeed(pos)); nextInt(totalWeight)} transcribed -
         * so this costs zero stored bytes.
         */
        final Quads[][] variantQuads;
        /**
         * Per row: all variant tables agree on the merge-routing fields
         * ({@link #variantsUniform}); meaningless when
         * {@link #variantQuads} is null there.
         */
        final boolean[] variantUniform;
        /**
         * Per row: vanilla's fluid-overlay neighbour test answers true for
         * this block - {@code instanceof HalfTransparentBlock ||
         * instanceof LeavesBlock} ({@code FluidRenderer.tesselate} ip
         * 1498-1511). Read at mesh time for the water face NEXT TO this
         * block, which is why it is a palette property and not a quad one.
         */
        final boolean[] glassy;
        /** Does any row of this palette carry variant tables? */
        boolean anyVariants;
        /** Does any row of this palette pass the overlay neighbour test? */
        boolean anyGlassy;

        ResolvedPalette(int entries, int doublingCount) {
            this.entries = entries;
            this.upperRow = doublingCount > 0 ? new int[entries] : null;
            int rows = entries + doublingCount;
            kind = new byte[rows];
            emission = new byte[rows];
            fallbackTint = new int[rows];
            quads = new Quads[rows];
            fluidHeight = new float[rows];
            fluidLook = new FluidLook[rows];
            fullCube = new boolean[rows];
            offsetH = new float[rows];
            offsetV = new float[rows];
            variantQuads = new Quads[rows][];
            variantUniform = new boolean[rows];
            glassy = new boolean[rows];
        }

        /**
         * How many RECORD palette entries this holds — the bound a cell's
         * palette index is checked against. NOT the array length: an entry
         * with a distinct upper half owns a second row past this.
         */
        public int size() {
            return entries;
        }

        /**
         * One row's per-draw variant tables (R4), or null when that row
         * has no per-position choice this class could enumerate. Public
         * for the gametest leg that proves the owner's own blocks -
         * grass_block, sand, dirt - really do enumerate: an "absence of
         * refusals" counter cannot say that, and a fixed-seed regression
         * would read exactly like a correct run without it.
         */
        public Quads[] variantTables(int row) {
            return variantQuads[row];
        }

        /** One row's {@link #variantUniform} verdict; see that field. */
        public boolean variantTablesUniform(int row) {
            return variantUniform[row];
        }
    }

    /**
     * Per-NAME cache, i.e. the block's DEFAULT state: what a record with no
     * stored state resolves through. Cleared on walker re-arm.
     *
     * <h2>Threading (M5): concurrent, and the fills are worker-legal</h2>
     * Until pre16 M5 all four caches here were plain maps under a
     * GAME THREAD ONLY contract. The pin worker now runs whole
     * extractions off-thread, and every extraction resolves palette
     * entries through this class - so the four maps are
     * {@code ConcurrentHashMap}s. What that has to be true FOR:
     * <ul>
     * <li>the KEYS keep their semantics: {@code BlockState} and
     *     {@code Block} inherit identity {@code equals}/{@code hashCode}
     *     (both are registry singletons that override neither), so a
     *     CHM keyed on them IS an identity map;</li>
     * <li>the FILLS read only what vanilla's own section-compile
     *     workers read - baked models, {@code BlockColors}, the frozen
     *     registries, {@code FluidStateModelSet} - immutable after
     *     resource load, swapped by reference on reload (a reader sees
     *     old or new, never torn);</li>
     * <li>no fill nests a compute on the same map (one
     *     {@code computeIfAbsent} in the class, and {@code resolveOne}
     *     never re-enters a cache), so the CHM recursion hazard is
     *     structurally absent. A racing double-resolve publishes one of
     *     two identical entries - idempotent by construction.</li>
     * </ul>
     */
    private static final Map<String, Entry> CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Per-STATE cache, keyed by the state object itself (states are
     * singletons, so identity is the right key and costs one reference
     * hash). This is what a format-4 record resolves through, and it is
     * separate from {@link #CACHE} because the two answer different
     * questions: a name resolves the default state and may carry an upper
     * half, a state resolves itself and never needs one. Concurrent since
     * M5 - see {@link #CACHE}'s threading note.
     */
    private static final Map<BlockState, Entry> STATE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * State object to its index within its own block's state list, filled
     * one whole block at a time by {@link #stateIndexOf}. Identity-keyed
     * in effect (see {@link #CACHE}'s threading note): every
     * {@code BlockState} in the game is a singleton built once during
     * registry bootstrap.
     *
     * <p>Lives for the SESSION, not for one arm cycle - see
     * {@link #clearCache()}. It grows with the distinct states the far
     * field has actually touched, bounded by the game's whole state space
     * (about 28,000 in vanilla 26.2, so roughly 1.3 MB if every block in
     * the game ever appears in far terrain, and a few hundred kilobytes in
     * practice).</p>
     */
    private static final Map<BlockState, Integer> STATE_INDEX =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Block to its 16-bit state-space signature; see
     * {@link #stateSignatureOf}. Concurrent since M5, like the rest.
     */
    private static final Map<Block, Integer> STATE_SIG =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * One cached block name. The {@link Quads} table is never mutated after
     * construction, so sharing it across every {@link ResolvedPalette} that
     * names the block is safe.
     *
     * @param tintIndex the TINT INDEX this entry's own model asks for - the
     *              lowest {@code materialInfo().tintIndex()} over its quads,
     *              or {@link #NO_TINT_INDEX} when no quad of it is tinted.
     *              It is what {@link #sampleTint} looks the colour up under,
     *              because a model does not have to use index 0 (see that
     *              method's "WHICH INDEX" section).
     * @param upper the same thing resolved for the block's UPPER half when
     *              it has one and draws the same KIND (see
     *              {@link #resolveOne}), else null. Nested rather than
     *              cached under its own name because a shell has no name for
     *              it: both halves of a tall grass are
     *              {@code minecraft:tall_grass}
     */
    private record Entry(byte kind, byte emission, int fallbackTint,
                         int tintIndex, Quads quads,
                         float fluidHeight, float offsetH, float offsetV,
                         FluidLook fluid, Entry upper,
                         Quads[] variants, boolean variantsUniform,
                         boolean glassy) {

        /** A block entry: no fluid height, so the cell is drawn full size. */
        Entry(byte kind, byte emission, int fallbackTint, int tintIndex,
                Quads quads, float offsetH, float offsetV, Entry upper) {
            this(kind, emission, fallbackTint, tintIndex, quads, 1.0f,
                    offsetH, offsetV, null, upper, null, true, false);
        }

        /** The same entry, carrying its upper half. */
        Entry withUpper(Entry half) {
            return new Entry(kind, emission, fallbackTint, tintIndex, quads,
                    fluidHeight, offsetH, offsetV, fluid, half,
                    variants, variantsUniform, glassy);
        }
    }

    /**
     * The mesh-time half of a fluid row's appearance that the baked
     * {@link Quads} table cannot carry, because vanilla decides it per
     * POSITION: the top face's sprite choice and rotation follow the FLOW
     * VECTOR ({@code FluidRenderer.tesselate} ip 662-693 takes
     * {@code stillMaterial()} whole when {@code getFlow()} is exactly zero,
     * ip 748-939 takes {@code flowingMaterial()} through a quarter window
     * rotated by {@code Mth.atan2(flow.z, flow.x) - PI/2}). The mesher
     * reproduces {@code FlowingFluid.getFlow} from the record
     * ({@code ShellMesher.fluidFlow}) and needs the FLOWING sprite's own
     * rect to build the rotated window, plus the fluid's identity class for
     * the corner average's same-fluid rule.
     *
     * @param family the same ladder {@code ShellExtractor.fluidFamily}
     *               uses for the pin strip (1 water, 2 lava, 3 modded; the
     *               two must not drift - each javadoc names the other)
     * @param flowU0 the flowing sprite's atlas rect;
     *               {@code getU(f) = u0 + f * (u1 - u0)}
     *               ({@code TextureAtlasSprite.getU}, javap ip 0-18)
     */
    public record FluidLook(byte family, float flowU0, float flowV0,
                            float flowU1, float flowV1,
                            boolean hasOverlay, float overlayU0, float overlayV0,
                            float overlayU1, float overlayV1) {

        /**
         * Map one uv off this fluid's FLOWING sprite rect onto the same
         * fraction of its OVERLAY sprite rect ({@code water_overlay}).
         * Vanilla computes the side windows as fractions of whichever
         * sprite the overlay test chose ({@code FluidRenderer.tesselate}
         * ip 1527-1583: {@code getU(0)}, {@code getU(0.5)},
         * {@code getV((1-h)*0.5)}, {@code getV(0.5)} on the CHOSEN
         * sprite), and the baked side windows here are those same
         * fractions of the flowing rect - so remapping the fraction is
         * exactly vanilla's arithmetic on the other sprite.
         */
        float overlayU(float u) {
            return overlayU0 + (u - flowU0) / (flowU1 - flowU0)
                    * (overlayU1 - overlayU0);
        }

        /** The V half of {@link #overlayU}. */
        float overlayV(float v) {
            return overlayV0 + (v - flowV0) / (flowV1 - flowV0)
                    * (overlayV1 - overlayV0);
        }
    }

    /**
     * Fluid identity classes: 0 none, 1 water family, 2 lava family, 3
     * anything modded - the stand-in for
     * {@code FluidState.getType().isSame(...)} once the state is gone.
     * THE TWIN of {@code ShellExtractor.fluidFamily} (the pin strip's
     * hide-same-fluid rule); the two ladders must stay identical.
     */
    private static byte fluidFamilyOf(FluidState fluid) {
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

    private static final Entry MISSING =
            new Entry(KIND_MISSING, (byte) 0, WHITE_RGB, NO_TINT_INDEX,
                    Quads.empty(), 0.0f, 0.0f, null);

    private SpriteUvResolver() {
    }

    /**
     * Resolve one shell record's palette, honouring the block STATE each
     * entry stores (format 4) and falling back to the block's default state
     * for an entry that stores none - which is every entry of a format 1, 2
     * or 3 record, i.e. every shell a player already has on disk. GAME
     * THREAD ONLY (registry, model manager and atlas sprites are client
     * state). Never throws for a bad name or a stale state index:
     * unresolvable entries come back {@link #KIND_MISSING} and the mesher
     * counts their cells as skipped.
     */
    public static ResolvedPalette resolve(ShellCodec.Shell shell) {
        String[] palette = shell.palette;
        Entry[] resolved = new Entry[palette.length];
        for (int i = 0; i < palette.length; i++) {
            resolved[i] = entryFor(palette[i], shell.stateIndex(i), shell.stateSig(i));
        }
        return assemble(palette.length, resolved);
    }

    /**
     * Resolve a palette of block NAMES with no stored states, i.e. every
     * entry draws its block's default. This is the pre-format-4 contract,
     * kept for callers (and tests) that have a name array and nothing else.
     */
    public static ResolvedPalette resolve(String[] palette) {
        Entry[] resolved = new Entry[palette.length];
        for (int i = 0; i < palette.length; i++) {
            resolved[i] = entryFor(palette[i], ShellCodec.NO_STATE, 0);
        }
        return assemble(palette.length, resolved);
    }

    /**
     * One palette entry: the stored state when the record has one AND the
     * guard passes, the block's default state otherwise.
     *
     * <p>The fallback is deliberately silent and deliberately the OLD look.
     * A stale state index means the block's state space changed under the
     * cache; drawing that block's default state is what every record on
     * disk did before format 4 and what the record will do again as soon as
     * the chunk is re-saved. It is not an error and nothing counts it.</p>
     */
    private static Entry entryFor(String name, int stateIndex, int stateSig) {
        BlockState state = stateIndex == ShellCodec.NO_STATE
                ? null : storedState(name, stateIndex, stateSig);
        if (state == null) {
            return CACHE.computeIfAbsent(name, SpriteUvResolver::resolveOne);
        }
        return entryOf(state);
    }

    /**
     * One block STATE's cached entry - one model walk per distinct
     * state, then a hash hit. Game thread or the pin worker, since M5
     * (see {@link #CACHE}'s threading note).
     *
     * <p>A failure resolves {@link #MISSING} and is deliberately NOT
     * published, so a transient throw is retried next column instead of
     * becoming a permanent hole - the same posture {@link #kindOf} takes.</p>
     */
    private static Entry entryOf(BlockState state) {
        Entry known = STATE_CACHE.get(state);
        if (known != null) {
            return known;
        }
        Entry made;
        try {
            made = resolveState(state, Minecraft.getInstance().getModelManager());
        } catch (Throwable t) {
            made = MISSING;
        }
        if (made != MISSING) {
            STATE_CACHE.put(state, made);
        }
        return made;
    }

    /**
     * The block state one format-4 palette entry names, or null when the
     * record's index cannot be trusted: unknown name, no signature, a
     * signature that no longer matches the block's state space, or an index
     * past the end of it. Every one of those is a fall back to the default
     * state, never a dropped cell.
     */
    private static BlockState storedState(String name, int stateIndex, int stateSig) {
        try {
            Identifier id = Identifier.tryParse(name);
            if (id == null) {
                return null;
            }
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
            if (block.isEmpty()) {
                return null; // modded world's mod removed, foreign pack
            }
            Block b = block.get();
            if (stateSig == 0 || stateSig != stateSignatureOf(b)) {
                return null; // this block's state space moved under the cache
            }
            BlockState state = stateAtIndex(b, stateIndex);
            return state != null && !state.isAir() ? state : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Lay resolved entries out into rows, minting one row per upper half. */
    private static ResolvedPalette assemble(int entries, Entry[] resolved) {
        // Two passes, because the row count depends on how many entries turn
        // out to have an upper half and the arrays are sized once.
        int doubling = 0;
        for (Entry e : resolved) {
            if (e.upper() != null) {
                doubling++;
            }
        }
        ResolvedPalette out = new ResolvedPalette(entries, doubling);
        int nextRow = entries;
        for (int i = 0; i < entries; i++) {
            Entry e = resolved[i];
            copyRow(out, i, e);
            if (out.upperRow == null) {
                continue;
            }
            if (e.upper() == null) {
                out.upperRow[i] = i; // no upper half: the row IS the entry
                continue;
            }
            out.upperRow[i] = nextRow;
            copyRow(out, nextRow++, e.upper());
        }
        return out;
    }

    /** Point one row of a palette at one resolved entry. */
    private static void copyRow(ResolvedPalette out, int row, Entry e) {
        out.kind[row] = e.kind();
        out.emission[row] = e.emission();
        out.fallbackTint[row] = e.fallbackTint();
        // By REFERENCE: the table is immutable and is shared with the cache.
        out.quads[row] = e.quads();
        out.fluidHeight[row] = e.fluidHeight();
        out.fluidLook[row] = e.fluid();
        out.fullCube[row] = fillsCell(e.quads());
        out.offsetH[row] = e.offsetH();
        out.offsetV[row] = e.offsetV();
        if (e.offsetH() != 0.0f || e.offsetV() != 0.0f) {
            out.anyOffset = true;
        }
        out.variantQuads[row] = e.variants();
        out.variantUniform[row] = e.variantsUniform();
        out.glassy[row] = e.glassy();
        if (e.variants() != null) {
            out.anyVariants = true;
        }
        if (e.glassy()) {
            out.anyGlassy = true;
        }
    }

    /**
     * Does this table own a flush, unshifted, non-overlay quad on all six
     * cell faces? See {@link ResolvedPalette#fullCube}. At most one pass
     * over one block's quads, once per palette row.
     */
    private static boolean fillsCell(Quads q) {
        int seen = 0;
        for (int i = 0; i < q.count(); i++) {
            if (q.unit()[i] && q.shift()[i] == 0.0f && !q.overlay()[i]) {
                int f = q.facing()[i];
                if (f != QuadFacing.UNASSIGNED.ordinal()) {
                    seen |= 1 << f;
                }
            }
        }
        return seen == 0x3F; // the six axis-aligned QuadFacing ordinals
    }

    /**
     * Drop the resolved-geometry caches (walker re-arm; bounds staleness
     * after a resource reload to one arm cycle).
     *
     * <p>{@link #STATE_INDEX} and {@link #STATE_SIG} are deliberately NOT
     * cleared. They hold registry data - which states a block has, and in
     * what order - which a resource pack cannot change and which is fixed
     * for the session, so clearing them would only make the next few
     * columns re-walk every state list for nothing. Only the two caches
     * that hold ATLAS coordinates go.</p>
     */
    public static void clearCache() {
        CACHE.clear();
        STATE_CACHE.clear();
    }

    // ------------------------------------------------------------------
    // Format 4's state identity: ONE definition, read by both sides
    // ------------------------------------------------------------------

    /**
     * Is a block STATE storable in a shell record at all, i.e. is the
     * client far enough up that {@link #sameAppearance} can be answered?
     *
     * <p>{@code ShellExtractor} asks once per chunk. A false answer makes
     * it write a format-4 record with no state indices, which decodes and
     * draws exactly like a format-3 one; it never makes it write a state it
     * could not stand behind.</p>
     */
    public static boolean stateStorageAvailable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.getModelManager() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Do these two states DRAW the same, so that one palette entry may
     * stand for both?
     *
     * <p><b>Vanilla answers this, and it is the same answer vanilla uses to
     * decide whether a block update needs a chunk re-render.</b> javap on
     * the 26.2 merged jar, {@code ModelManager.requiresRender}:</p>
     * <pre>
     *   if (a == b) return false;                       // ip 0-6
     *   int ga = modelGroups.getInt(a);                 // ip 7-17
     *   if (ga == -1) return true;                      // SINGLETON group
     *   if (ga != modelGroups.getInt(b)) return true;   // ip 23-38
     *   return a.getFluidState() != b.getFluidState();  // ip 41-65
     * </pre>
     * <p>{@code modelGroups} is built by {@code ModelGroupCollector.build},
     * whose key is the model's own {@code UnbakedRoot.visualEqualityGroup}
     * PLUS the values of every property the block's {@code BlockColors}
     * tint sources read ({@code GroupKey.create}). So two states in one
     * group share a baked model AND a tint, which is exactly the pair of
     * things a palette entry carries.</p>
     *
     * <p><b>The fluid clause is why this class needs no fluid special
     * case.</b> Every water level maps to the same one-variant model
     * ({@code blockstates/water.json} has a single {@code ""} entry), so a
     * model-only test would have collapsed all sixteen; the reference
     * compare on {@code FluidState} keeps them apart, and that is what
     * carries {@code level} and {@code falling} into the record.</p>
     *
     * <p>Conservative on failure: an unavailable model manager answers
     * "different", which costs palette entries and never appearance.</p>
     */
    public static boolean sameAppearance(BlockState a, BlockState b) {
        if (a == b) {
            return true;
        }
        try {
            return !Minecraft.getInstance().getModelManager().requiresRender(a, b);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The index of one state within its own block's
     * {@code getPossibleStates()} list - what a format-4 record stores.
     *
     * <p>Deliberately NOT {@code Block.getId(state)}: the global registry
     * renumbers whenever any block anywhere is added or removed, so a
     * modset change would reinterpret every cached rotation, while a
     * per-block index depends only on that block's own property set. See
     * {@code ShellCodec}'s version 4 section.</p>
     *
     * <p>Answered from a lazily filled map, one pass over a block's states
     * the first time that block is seen, so the cost is O(1) per call after
     * that. Returns {@link ShellCodec#NO_STATE} if the state is somehow not
     * in its own block's list, which a caller stores as "no state".</p>
     */
    public static int stateIndexOf(BlockState state) {
        try {
            Block block = state.getBlock();
            if (!STATE_INDEX.containsKey(state)) {
                List<BlockState> all = block.getStateDefinition().getPossibleStates();
                for (int i = 0; i < all.size(); i++) {
                    STATE_INDEX.put(all.get(i), i);
                }
            }
            return STATE_INDEX.getOrDefault(state, ShellCodec.NO_STATE);
        } catch (Throwable t) {
            return ShellCodec.NO_STATE;
        }
    }

    /**
     * The state at one index of one block's own state list, or null when
     * the index is out of range - the read side of {@link #stateIndexOf}.
     */
    private static BlockState stateAtIndex(Block block, int index) {
        try {
            List<BlockState> all = block.getStateDefinition().getPossibleStates();
            return index >= 0 && index < all.size() ? all.get(index) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A 16-bit signature of one block's STATE SPACE: how many states it
     * has, and every property's name and value count in definition order.
     * A record stores it beside the block's name so a reader can tell
     * whether the indices it carries still mean anything.
     *
     * <p><b>Both halves of format 4 rest on this being deterministic
     * across sessions, and javap says it is.</b>
     * {@code StateDefinition.getProperties()} returns
     * {@code propertiesByName.values()} where {@code propertiesByName} is a
     * Guava {@code ImmutableSortedMap<String, Property<?>>} built with
     * {@code ImmutableSortedMap.copyOf} (ctor ip 58-61), i.e. properties in
     * NAME order, never in hash order. {@code getPossibleStates()} is the
     * cartesian product built over that same sorted list, so a state's
     * index depends only on the property names and each property's own
     * value order - not on registry order, not on modset, not on JVM run.
     * And {@code String.hashCode} is specified by the JDK, so this hash is
     * the same number on every machine, which is what lets it be written to
     * disk at all.</p>
     *
     * <p>What it catches: a property added, removed or widened, and a
     * property renamed - the ordinary shapes of a version upgrade or a
     * mod's own change. What it does not catch, and this is written down
     * rather than guarded, is a state space that keeps its count and its
     * property shape and reorders one property's VALUES; that would be a
     * vanilla data-format change, the failure is a wrongly turned block
     * rather than a wrong block, and it heals when the chunk is re-saved.
     * Guarding it means hashing every value name of every property, which
     * is a per-block string walk to buy nothing anybody has ever seen.</p>
     *
     * <p>Never 0: 0 is reserved by the writer for "this entry stores no
     * state", so the hash is forced off it.</p>
     */
    public static int stateSignatureOf(Block block) {
        Integer known = STATE_SIG.get(block);
        if (known != null) {
            return known;
        }
        int hash = 0;
        try {
            var definition = block.getStateDefinition();
            hash = definition.getPossibleStates().size() * 31;
            for (var property : definition.getProperties()) {
                hash = hash * 31 + property.getName().hashCode();
                hash = hash * 31 + property.getPossibleValues().size();
            }
        } catch (Throwable t) {
            hash = 0;
        }
        int sig = hash & 0xFFFF;
        if (sig == 0) {
            sig = 1; // 0 means "no signature"; never let a real one collide
        }
        STATE_SIG.put(block, sig);
        return sig;
    }

    /**
     * Sample the biome-dependent tint of one block state, in world, as an
     * 0xRRGGBB value. Only valid while the world view handed in can
     * answer for {@code pos} — the LIVE extractor passes the client
     * level on the game thread; the pin worker passes the pin's own
     * tint facade (its captured biomes), never the live level. The
     * vanilla machinery underneath (BlockColors, the tint sources,
     * BiomeColors) is what vanilla's own section-compile workers run
     * off-thread, and the caches here are concurrent since M5.
     *
     * <p><b>Order of preference, and defect E2 is why the FLUID branch is
     * FIRST.</b> Vanilla DOES register {@code Blocks.WATER} in
     * {@link BlockColors}, but only for PARTICLES:
     * {@code BlockColors.createDefault} ip 257-281 registers
     * {@code BlockTintSources.waterParticles()} for WATER and BUBBLE_COLUMN,
     * and that source overrides {@code colorAsTerrainParticle} ALONE — its
     * {@code color(BlockState)} is {@code iconst_m1} and it does not
     * override {@code colorInWorld}, so the interface default hands back -1,
     * i.e. WHITE. White on the grayscale water_still palette is the gray
     * ocean the owner saw. The biome colour lives on
     * {@code FluidModel.tintSource()}, which for water is
     * {@code BlockTintSources.water()} and DOES resolve through
     * {@code BiomeColors.getAverageWaterColor}. So anything the far field
     * draws AS A FLUID is asked of the FLUID model; everything else keeps
     * the block's own tint sources (grass, foliage, dry foliage, stem, sugar
     * cane, redstone), with the fluid model as a last resort for a block
     * that has neither.</p>
     *
     * <p>The fluid test is the resolved {@link #KIND_FLUID}, not the raw
     * {@code FluidState}, so the waterlogged-vegetation rule still holds: a
     * waterlogged block whose model yields quads is a BLOCK and keeps its
     * own tint.</p>
     *
     * <h2>WHICH INDEX, and the defect that was (pre13 N3)</h2>
     * <p>A tint index is a SUBSCRIPT into
     * {@code BlockColors.getTintSources(state)}, and this method used to
     * read {@code sources.get(0)} for every block. That is wrong whenever a
     * model asks for a different subscript, and two vanilla blocks do:
     * {@code BlockColors.createDefault} ip 92-118 registers
     * {@code PINK_PETALS} and {@code WILDFLOWERS} with
     * {@code List.of(BLANK_LAYER, BlockTintSources.grass())}, where
     * {@code BLANK_LAYER} is {@code constant(-1)} - WHITE - and every
     * {@code flowerbed_*} stem element carries {@code "tintindex": 1}. The
     * stems therefore sampled white, and both stem sprites are pure
     * greyscale, so a distant wildflower or pink petal stood on grey stems
     * where vanilla draws green ones.</p>
     *
     * <p>The rule now is the model's: {@link #modelTintIndex} reads the
     * index off the state's own baked quads and this samples THAT source.
     * One colour per palette entry is still enough, because no vanilla
     * model uses two indices (census in {@link #modelTintIndex}); a modded
     * model that did would take the lowest, which is a wrong colour on its
     * other index and never a crash.</p>
     *
     * <p>Never throws: an unexpected failure yields {@link #WHITE_RGB},
     * i.e. no tint, which is the pre-fix appearance rather than a crash.</p>
     */
    public static int sampleTint(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        try {
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty() && drawsAsFluid(state)) {
                return fluidTint(state, fluid, level, pos);
            }
            BlockColors colors = Minecraft.getInstance().getBlockColors();
            List<BlockTintSource> sources = colors.getTintSources(state);
            BlockTintSource source = tintSourceFor(sources, tintIndexOf(state));
            if (source != null) {
                return source.colorInWorld(state, level, pos) & 0xFFFFFF;
            }
            if (!fluid.isEmpty()) {
                return fluidTint(state, fluid, level, pos);
            }
        } catch (Throwable t) {
            // A tint is decoration; never let one kill an extraction.
        }
        return WHITE_RGB;
    }

    /**
     * The tint index one block state's own model asks for, or
     * {@link #NO_TINT_INDEX} when no quad of it is tinted. Answered from
     * the same per-STATE cache {@link #resolve} fills (concurrent since
     * M5), so the extractor's tint sampling costs one hash hit per entry
     * after the first, on whichever thread walks.
     */
    public static int tintIndexOf(BlockState state) {
        try {
            return entryOf(state).tintIndex();
        } catch (Throwable t) {
            return NO_TINT_INDEX;
        }
    }

    /**
     * Does {@link #sampleTint} have any source to ask for this state, i.e.
     * can its answer depend on WHERE the block is? A y- and xz-INDEPENDENT
     * fact about the state alone.
     *
     * <p><b>Why this exists (pre20, the green water).</b>
     * {@code ShellExtractor.sampleCellField} used to fill a whole 16x16
     * field from white the moment its FIRST node came back
     * {@link #WHITE_RGB}. That shortcut was sound only while every node of
     * an entry was sampled at one position-family: one white ANSWER then
     * really did prove the other 255, because the block had no source and
     * white is what a sourceless block returns everywhere. Once the field
     * samples each column at its OWN y (which is the whole green-water
     * fix), a white first node proves nothing - a tinted block can be
     * white at one position and coloured at another, and a fluid whose
     * first column happens to resolve white would flatten fifteen
     * correct ones. So the shortcut is re-derived here on the
     * y-independent premise it always meant: "this state has no tint
     * source at all".</p>
     *
     * <p>Deliberately structural, not value-based: a source that exists
     * but happens to be a constant (vanilla's {@code BLANK_LAYER}, i.e.
     * {@code constant(-1)}) answers TRUE here and costs 256 samples of a
     * constant. That is the safe direction - paying for a field we did not
     * need, never flattening one we did.</p>
     *
     * <p>Mirrors {@link #sampleTint}'s own branch order exactly (fluid
     * first when the state draws as a fluid, then the block's own sources
     * at the model's tint index, then the fluid as a last resort) so the
     * two can never disagree about which source is in play. A throw yields
     * FALSE, which matches {@code sampleTint}'s own catch: it returns
     * {@link #WHITE_RGB} for the same failure, so filling white without
     * sampling produces byte-identical output for less work.</p>
     */
    public static boolean hasTintSource(BlockState state) {
        try {
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty() && drawsAsFluid(state)) {
                return fluidTintSource(fluid) != null;
            }
            BlockColors colors = Minecraft.getInstance().getBlockColors();
            List<BlockTintSource> sources = colors.getTintSources(state);
            if (tintSourceFor(sources, tintIndexOf(state)) != null) {
                return true;
            }
            return !fluid.isEmpty() && fluidTintSource(fluid) != null;
        } catch (Throwable t) {
            // sampleTint's own catch returns WHITE_RGB here, so "no
            // source" is not a guess, it is the same answer.
            return false;
        }
    }

    /** The fluid model's in-world tint source, 0xRRGGBB, white if none. */
    private static int fluidTint(BlockState state, FluidState fluid,
            BlockAndTintGetter level, BlockPos pos) {
        BlockTintSource src = fluidTintSource(fluid);
        // FluidStateModelSet builds LAVA_MODEL with a NULL tint source
        // (javap, static init ip 77-79), so lava must not dereference it.
        // White is right there anyway: lava_still carries its own colour.
        return src == null ? WHITE_RGB : src.colorInWorld(state, level, pos) & 0xFFFFFF;
    }

    /**
     * One fluid's tint source, or null when it has none (LAVA). Split out
     * of {@link #fluidTint} so {@link #hasTintSource} asks the SAME
     * question without a position and without a sample.
     */
    private static BlockTintSource fluidTintSource(FluidState fluid) {
        FluidModel model = Minecraft.getInstance().getModelManager()
                .getFluidStateModelSet().get(fluid);
        return model.tintSource();
    }

    /**
     * True when this state resolves to {@link #KIND_FLUID}, i.e. the mesher
     * will draw the fluid's own sprite with {@code tinted} set. Answered
     * through the SAME per-name cache {@link #resolve} uses, so the tint rule
     * can never disagree with the KIND that decides where the tint lands.
     * Any extraction thread since M5.
     */
    private static boolean drawsAsFluid(BlockState state) {
        return kindOf(state) == KIND_FLUID;
    }

    /**
     * The resolved KIND of one block state, for a caller that is not
     * building a whole palette. Same cache, same contract as
     * {@link #resolve}; any extraction thread since M5.
     *
     * <p>A failure resolves {@link #KIND_MISSING} and is deliberately NOT
     * published to the cache: {@link #resolve} shares the same entries and
     * the mesher DROPS every cell of a {@link #KIND_MISSING} one, so a
     * transient throw here would become permanent holes until the next
     * {@link #clearCache()}.</p>
     */
    public static byte kindOf(BlockState state) {
        try {
            Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (id == null) {
                return KIND_MISSING;
            }
            String name = id.toString();
            Entry entry = CACHE.get(name);
            if (entry == null) {
                entry = resolveOne(name);
                if (entry != MISSING) {
                    CACHE.put(name, entry);
                }
            }
            return entry.kind();
        } catch (Throwable t) {
            return KIND_MISSING;
        }
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /**
     * Resolve one block NAME to its DEFAULT state, including its upper half
     * when it has one. This is the path a record older than store format 4
     * takes; a format-4 entry resolves its own state and never comes
     * here.
     *
     * <h2>Two-block plants (the owner's pre9 "double tall grass is broken
     * it just appears as two regular one tall grasses")</h2>
     * <p>A record older than store format 4 stores block NAMES, so both
     * halves of a tall grass arrive here as {@code minecraft:tall_grass} and
     * this method used to answer for the DEFAULT state alone.
     * A format-4 record stores {@code half} like any other property and
     * this whole mechanism sits idle for it.</p>
     *
     * <p> {@code DoublePlantBlock}'s constructor is
     * {@code registerDefaultState(any().setValue(HALF, LOWER))} (javap ip
     * 5-31), so the default is the LOWER half and both cells drew
     * {@code block/tall_grass_bottom}: one plant became two short ones. The
     * halves are genuinely different models — {@code blockstates/
     * tall_grass.json} maps {@code half=lower} to
     * {@code block/tall_grass_bottom} and {@code half=upper} to
     * {@code block/tall_grass_top}, and the same file shape covers
     * large_fern, sunflower, lilac, rose_bush, peony and tall_seagrass, plus
     * every door ({@code DoorBlock} shares the property).</p>
     *
     * <p><b>So both halves are resolved, and the mesher picks.</b> The test
     * is the property and not a block list:
     * {@code state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)}
     * (javap: {@code DoublePlantBlock.HALF} is a {@code getstatic} of
     * {@code BlockStateProperties.DOUBLE_BLOCK_HALF}, clinit ip 11-14, so the
     * two are the same object and this covers every subclass and any modded
     * block that uses it). The base entry is forced to LOWER rather than
     * trusted to be it, and the UPPER entry rides along inside it
     * ({@link Entry#upper}) because a shell has no second name to cache it
     * under.</p>
     *
     * <p><b>The upper half is only carried when the two halves have the same
     * KIND</b>, so {@code ShellMesher}'s per-cell row substitution can never
     * see a block and a fluid disagree.</p>
     */
    private static Entry resolveOne(String name) {
        try {
            Identifier id = Identifier.tryParse(name);
            if (id == null) {
                return MISSING;
            }
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
            if (block.isEmpty()) {
                return MISSING; // modded world's mod removed, foreign pack
            }
            BlockState state = block.get().defaultBlockState();
            if (state.isAir()) {
                return MISSING; // air has no face to draw
            }
            ModelManager models = Minecraft.getInstance().getModelManager();
            if (!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                return resolveState(state, models);
            }
            Entry lower = resolveState(state.setValue(
                    BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), models);
            if (lower.kind() == KIND_MISSING) {
                return lower;
            }
            // The upper half gets its OWN catch: a modded block whose upper
            // state has no model must cost its top texture, not the whole
            // entry. Without this the outer catch would hand back MISSING and
            // the mesher would drop both halves' cells.
            Entry upper;
            try {
                upper = resolveState(state.setValue(
                        BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), models);
            } catch (Throwable t) {
                return lower;
            }
            return upper.kind() == lower.kind() ? lower.withUpper(upper) : lower;
        } catch (Throwable t) {
            // One bad name must never kill the whole palette; the cells are
            // skipped and counted by the mesher.
            return MISSING;
        }
    }

    /**
     * Resolve one block STATE: the model if it has one, else the fluid it
     * is, else nothing. Never returns null.
     */
    private static Entry resolveState(BlockState state, ModelManager models) {
        Entry fromModel = resolveFromModel(state, models);
        if (fromModel != null) {
            return fromModel;
        }
        // No model geometry at all. A fluid draws its own sprite; anything
        // else (barrier, light, structure_void, a block entity) draws
        // nothing.
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) {
            return MISSING;
        }
        return resolveFluid(state, fluid, models);
    }

    /**
     * Collect every baked quad of every part, remember which cullface bucket
     * each came out of, and build the entry's {@link Quads}. Returns null
     * when the model produced no quads at all — the fluid / invisible-block
     * case.
     *
     * <p>This is the whole geometry resolution. There is no ladder, no
     * boundary test, no kind and no box: the model's quads ARE the answer,
     * and the only thing recorded alongside a quad is the bucket it was
     * found in, because that bucket is vanilla's own statement of which
     * neighbour may hide it.</p>
     *
     * <p>A part that refuses {@code getQuads(null)} — a modded part that
     * does not honour the null contract — costs only its unculled quads and
     * keeps everything the cullface buckets already gave, hence the local
     * catch rather than the method-wide one.</p>
     */
    private static Entry resolveFromModel(BlockState state, ModelManager models) {
        BlockStateModel model = models.getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>(4);
        model.collectParts(RandomSource.create(MODEL_SEED), parts);
        if (parts.isEmpty()) {
            return null;
        }
        List<BakedQuad> found = new ArrayList<>(16);
        List<Byte> culls = new ArrayList<>(16);
        collectQuads(parts, found, culls);
        if (found.isEmpty()) {
            return null; // parts but no geometry: treat as no model
        }
        int tintIndex = modelTintIndex(found);
        long offsets = offsetMagnitudes(state);
        Quads[] variants = enumerateVariants(model);
        Block block = state.getBlock();
        boolean glassy = block instanceof HalfTransparentBlock
                || block instanceof LeavesBlock;
        return new Entry(KIND_BLOCK, lightEmission(state),
                defaultBlockTint(state, tintIndex), tintIndex,
                buildQuads(found, culls), 1.0f,
                Float.intBitsToFloat((int) (offsets >>> 32)),
                Float.intBitsToFloat((int) offsets), null, null,
                variants, variants == null || variantsUniform(variants),
                glassy);
    }

    /** The quad walk of {@link #resolveFromModel}, shared with the variant
     * enumeration so every table is built by the same rules. */
    private static void collectQuads(List<BlockStateModelPart> parts,
            List<BakedQuad> found, List<Byte> culls) {
        Direction[] dirs = Direction.values();
        for (BlockStateModelPart part : parts) {
            for (int f = 0; f < FACES; f++) {
                for (BakedQuad q : part.getQuads(dirs[f])) {
                    found.add(q);
                    culls.add((byte) f);
                }
            }
            List<BakedQuad> unculled;
            try {
                unculled = part.getQuads(null);
            } catch (Throwable t) {
                continue; // a modded part that refuses null; keep the rest
            }
            for (BakedQuad q : unculled) {
                found.add(q);
                culls.add(NO_CULL);
            }
        }
    }

    // ------------------------------------------------------------------
    // Weighted-variant enumeration (the owner's R4; the P1 census's 46
    // pure-rotation rows plus the plain-variants half of the 29)
    // ------------------------------------------------------------------

    /**
     * How large a variant list's total weight may be before enumeration
     * declines. NOT silent: a refusal counts on
     * {@link #farVariantOverBound} and the row falls back to the one
     * baked table, which is exactly the pre18 look. Vanilla's largest is
     * netherrack's 16; 64 is {@code WeightedList}'s own Flat/Compact
     * selector boundary and costs at most 64 tables x ~0.5 KB = 32 KB of
     * RAM per distinct state, cached with the entry.
     */
    private static final int MAX_VARIANT_DRAWS = 64;

    // ------------------------------------------------------------------
    // Why enumeration declined, SPLIT BY CAUSE (pre18 review). One
    // counter could not tell "the four multiparts we knowingly defer"
    // from "the owner's own grass is falling back to the baked table",
    // and R4 is a report about grass tops - so each cause counts
    // separately and all four are published in the visual snapshot.
    //
    // THE STATIC ANSWER, censused over all 1,198 blockstate files in the
    // 26.2 jar this session, so a playtest reading these knows what is
    // POSSIBLE before it reads what happened:
    //
    //   * 34 blockstates carry a plain `variants` ARRAY. Every one is a
    //     single draw and every one ENUMERATES. Max total weight in the
    //     game is 16 (netherrack), so OVER-BOUND is unreachable in
    //     vanilla and can only ever be a mod.
    //   * 0 multiparts have exactly one weighted selector.
    //   * 4 multiparts have two or more: bamboo (2 selectors, both keyed
    //     on `age`), chorus_plant (6, one per direction), fire (6) and
    //     soul_fire (5, all with an EMPTY `when`, i.e. always
    //     applicable). Whether a given STATE actually multi-draws
    //     depends on how many of its selectors apply - every soul_fire
    //     state does; a chorus_plant with one connection does not.
    //   * NONE of the four is grass_block, sand, dirt, stone, podzol,
    //     mycelium, netherrack or a concrete powder. The blocks the
    //     owner reported cannot reach any of these counters.
    //
    // And these count CALLS, not blocks: clearCache() drops the state
    // cache on every walker re-arm (FarFieldResidency:1540, :3289), so a
    // long flight re-resolves the same handful of states many times.
    // Read them against each other, not as a population.
    // ------------------------------------------------------------------

    /**
     * Enumeration declined because {@code collectParts} THREW under the
     * probe - a modded model that will not tolerate a forced draw. Zero
     * in vanilla by construction.
     */
    public static final java.util.concurrent.atomic.LongAdder
            farVariantProbeThrew = new java.util.concurrent.atomic.LongAdder();

    /**
     * Enumeration declined because the model consumed the random source
     * more than once (or used it in some other way), so a single forced
     * draw cannot speak for it. In vanilla this is exactly the four
     * multiparts above, at the states where two or more of their
     * weighted selectors apply.
     */
    public static final java.util.concurrent.atomic.LongAdder
            farVariantMultiDraw = new java.util.concurrent.atomic.LongAdder();

    /**
     * Enumeration declined because the list's total weight exceeds
     * {@link #MAX_VARIANT_DRAWS}. <b>Unreachable in vanilla</b> - the
     * heaviest list in the game is netherrack's 16 - so any non-zero
     * here is a mod, and the cap is the thing to revisit.
     */
    public static final java.util.concurrent.atomic.LongAdder
            farVariantOverBound = new java.util.concurrent.atomic.LongAdder();

    /**
     * Enumeration declined because one draw produced PARTS BUT NO
     * GEOMETRY, which would make that variant an invisible cell. Zero in
     * vanilla; kept separate because it is the one cause that would be a
     * real defect rather than a known deferral.
     */
    public static final java.util.concurrent.atomic.LongAdder
            farVariantNoGeometry = new java.util.concurrent.atomic.LongAdder();

    /**
     * One {@link Quads} table per DRAW VALUE of this model's weighted
     * variant list, or null when the model has no per-position choice
     * this class can reproduce.
     *
     * <h2>What vanilla does, and why enumeration reproduces it exactly</h2>
     * <p>javap, 26.2 merged jar: {@code SectionCompiler} ip 319-326 hands
     * {@code state.getSeed(pos)} to {@code ModelBlockRenderer
     * .tesselateBlock}, whose ip 0-21 is {@code random.setSeed(seed);
     * model.collectParts(random, parts)}. For a blockstate whose variants
     * value is an ARRAY the model is {@code WeightedVariants}, whose
     * {@code collectParts} (ip 0-18) is
     * {@code list.getRandomOrThrow(random).collectParts(random, parts)},
     * and {@code WeightedList.getRandomOrThrow} (ip 17-38) is
     * {@code selector.get(random.nextInt(totalWeight))}. So the whole
     * position dependence is ONE {@code nextInt(totalWeight)} draw, and
     * feeding {@code collectParts} a random whose single
     * {@code nextInt} answer is forced to {@code i} yields exactly the
     * part list vanilla draws whenever its own roll lands on {@code i} -
     * whatever the selector's weights are, because the selector itself
     * ran. {@code ShellMesher.variantDraw} then reproduces the roll from
     * the cell's position at mesh time, for zero stored bytes.</p>
     *
     * <p>A model that draws twice (a multipart with two weighted
     * selectors), consumes any other randomness, or reports an unstable
     * bound is refused and counted, each on its own cause counter -
     * enumeration by a single forced draw cannot speak for it. A list
     * whose every draw lands on one part list (a single-variant row, or
     * weights collapsing to one model) returns null too: there is
     * nothing position-dependent to reproduce.</p>
     */
    private static Quads[] enumerateVariants(BlockStateModel model) {
        ForcedRandom probe = new ForcedRandom();
        List<BlockStateModelPart> parts = new ArrayList<>(4);
        try {
            probe.forced = 0;
            model.collectParts(probe, parts);
        } catch (Throwable t) {
            // A modded model that dislikes the probe: the baked look, and
            // counted rather than silently dropped.
            farVariantProbeThrew.increment();
            return null;
        }
        if (probe.draws == 0 && probe.others == 0) {
            return null; // SingleVariant / deterministic multipart
        }
        if (probe.draws != 1 || probe.others != 0) {
            farVariantMultiDraw.increment();
            return null; // more than one draw: unobservable sequence
        }
        int bound = probe.bound;
        if (bound < 2) {
            return null;
        }
        if (bound > MAX_VARIANT_DRAWS) {
            farVariantOverBound.increment();
            return null;
        }
        Quads[] variants = new Quads[bound];
        // Dedupe by the part list itself: weight > 1 repeats the same
        // BlockStateModelPart instances, so equal lists share one table.
        List<List<BlockStateModelPart>> seenParts = new ArrayList<>(4);
        List<Quads> seenTables = new ArrayList<>(4);
        for (int i = 0; i < bound; i++) {
            probe.forced = i;
            probe.draws = 0;
            probe.others = 0;
            parts = new ArrayList<>(4);
            try {
                model.collectParts(probe, parts);
            } catch (Throwable t) {
                farVariantProbeThrew.increment();
                return null;
            }
            if (probe.draws != 1 || probe.others != 0 || probe.bound != bound) {
                farVariantMultiDraw.increment();
                return null; // the draw structure moved between values
            }
            Quads table = null;
            for (int s = 0; s < seenParts.size(); s++) {
                if (seenParts.get(s).equals(parts)) {
                    table = seenTables.get(s);
                    break;
                }
            }
            if (table == null) {
                List<BakedQuad> found = new ArrayList<>(16);
                List<Byte> culls = new ArrayList<>(16);
                collectQuads(parts, found, culls);
                if (found.isEmpty()) {
                    farVariantNoGeometry.increment();
                    return null; // a variant with no geometry: keep the bake
                }
                table = buildQuads(found, culls);
                seenParts.add(parts);
                seenTables.add(table);
            }
            variants[i] = table;
        }
        if (seenTables.size() < 2) {
            return null; // every draw is one look: nothing to select
        }
        return variants;
    }

    /**
     * Do all of a row's variant tables agree on the fields that ROUTE a
     * quad through {@code ShellMesher}'s merge buckets (count, cullface,
     * facing, unit, shift, translucency)? When they do - every rotation
     * family, because a rotation permutes a cube's faces without moving
     * any of this - a variant cell keeps the shifted-plane merge path;
     * when they do not, {@code ShellMesher} routes that row's quads to
     * their true geometry instead, which is correct and merely unmerged
     * (the {@code shiftKey} bucket is keyed by quad INDEX, and an index
     * that means different shifts in different variants must not share a
     * rigid translate).
     */
    private static boolean variantsUniform(Quads[] variants) {
        Quads first = variants[0];
        for (int i = 1; i < variants.length; i++) {
            Quads v = variants[i];
            if (v == first) {
                continue;
            }
            if (v.count() != first.count()
                    || !java.util.Arrays.equals(v.cull(), first.cull())
                    || !java.util.Arrays.equals(v.facing(), first.facing())
                    || !java.util.Arrays.equals(v.unit(), first.unit())
                    || !java.util.Arrays.equals(v.shift(), first.shift())
                    || !java.util.Arrays.equals(v.translucent(),
                            first.translucent())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The enumeration probe: a {@link RandomSource} whose single
     * {@code nextInt(bound)} answer is chosen by the caller. Every other
     * method counts on {@link #others} and answers a constant, because a
     * model that consumes any of them is one this enumeration must refuse
     * rather than misrepresent ({@link #enumerateVariants}).
     */
    private static final class ForcedRandom implements RandomSource {
        int forced;
        int draws;
        int others;
        int bound;

        @Override
        public RandomSource fork() {
            others++;
            return this;
        }

        @Override
        public net.minecraft.world.level.levelgen.PositionalRandomFactory forkPositional() {
            others++;
            throw new UnsupportedOperationException(
                    "far-field variant probe has no positional fork");
        }

        @Override
        public void setSeed(long seed) {
            // A defensive setSeed costs nothing and reveals nothing.
        }

        @Override
        public int nextInt() {
            others++;
            return 0;
        }

        @Override
        public int nextInt(int b) {
            draws++;
            bound = b;
            return Math.min(forced, Math.max(0, b - 1));
        }

        @Override
        public long nextLong() {
            others++;
            return 0L;
        }

        @Override
        public boolean nextBoolean() {
            others++;
            return false;
        }

        @Override
        public float nextFloat() {
            others++;
            return 0.0f;
        }

        @Override
        public double nextDouble() {
            others++;
            return 0.0;
        }

        @Override
        public double nextGaussian() {
            others++;
            return 0.0;
        }
    }

    /**
     * How far vanilla may displace this state's model from its own cell:
     * the horizontal magnitude in the high 32 bits and the DOWNWARD
     * vertical magnitude in the low 32, both as float bits, both 0 for a
     * block that never moves.
     *
     * <h2>What this is, and why the far field had none of it (the owner's
     * pre14 P1)</h2>
     * <p>"grass plant displacement isnt correct, like you know how grass
     * can appear ontop of the block with a certain amount of random
     * offset." Vanilla computes that in
     * {@code ModelBlockRenderer.tesselateBlock} (javap, ip 39-46):
     * {@code Vec3 off = state.getOffset(pos)} and every vertex of every
     * part is translated by it. A far-field shell has no memory of the
     * position a cell sat at, so it drew all 39 offsetting blocks
     * perfectly lattice-aligned - a field of grass in rows, which is the
     * one thing vanilla's offset exists to prevent.</p>
     *
     * <h2>The two numbers, and why two are enough</h2>
     * <p>{@code BlockBehaviour.Properties.offsetType} (javap ip 0-62)
     * installs one of exactly two lambdas, and their X and Z halves are
     * the SAME expression:</p>
     * <pre>
     *   long s  = Mth.getSeed(pos.getX(), 0, pos.getZ());
     *   dx = clamp(((s        &amp; 15) / 15f - 0.5) * 0.5, -maxH, maxH);
     *   dz = clamp((((s &gt;&gt;&gt; 8) &amp; 15) / 15f - 0.5) * 0.5, -maxH, maxH);
     *   dy = (((s &gt;&gt;&gt; 4) &amp; 15) / 15f - 1.0) * maxV;   // XYZ only
     * </pre>
     * <p>so an {@code XZ} block is an {@code XYZ} block with
     * {@code maxV = 0}, and the mesher needs the TYPE for nothing. It
     * needs two floats.</p>
     *
     * <h2>Why they are PROBED rather than read</h2>
     * <p>{@code getMaxHorizontalOffset()} and {@code getMaxVerticalOffset()}
     * are {@code protected} on {@code BlockBehaviour} (javap: default 0.25f
     * and 0.2f; {@code SpeleothemBlock} and {@code SmallDripleaf} are the
     * only overriders in the game), so no caller outside that package can
     * read them. {@code BlockStateBase.getOffset(BlockPos)} is public and
     * is the whole function, so the magnitudes are recovered by evaluating
     * it at positions whose seed bits are known:</p>
     * <ul>
     * <li>a position with {@code (s &amp; 15) == 15} makes the raw x term
     *     exactly {@code +0.25}, so {@code off.x} comes back as
     *     {@code min(0.25, maxH)} - which is the EFFECTIVE bound, since
     *     the raw term never leaves {@code [-0.25, 0.25]} and the clamp
     *     can only ever bite at the top;</li>
     * <li>a position with {@code ((s &gt;&gt;&gt; 4) &amp; 15) == 0} makes the raw y
     *     term exactly {@code -1}, so {@code off.y} comes back as
     *     {@code -maxV} - and as {@code 0} for an XZ block, which is the
     *     answer we want it to give.</li>
     * </ul>
     * <p>Both searches walk x with the seed computed by
     * {@code ShellMesher.positionSeed} (the same transcription the mesher
     * draws with, so the two cannot drift), hit in about sixteen tries
     * each, and run once per distinct block state, cached forever with the
     * rest of the entry. A search that somehow found nothing falls back to
     * vanilla's declared defaults rather than to zero, so the failure
     * direction is "the ordinary offset", never "no offset".</p>
     */
    private static long offsetMagnitudes(BlockState state) {
        try {
            if (!state.hasOffsetFunction()) {
                return 0L;
            }
            float maxH = 0.25f;  // BlockBehaviour.getMaxHorizontalOffset default
            float maxV = 0.0f;
            boolean gotH = false;
            boolean gotV = false;
            for (int x = 0; x < OFFSET_PROBE_LIMIT && !(gotH && gotV); x++) {
                long s = ShellMesher.positionSeed(x, 0, 0);
                if (!gotH && (s & 15L) == 15L) {
                    maxH = (float) state.getOffset(new BlockPos(x, 0, 0)).x;
                    gotH = true;
                }
                if (!gotV && ((s >>> 4) & 15L) == 0L) {
                    maxV = (float) -state.getOffset(new BlockPos(x, 0, 0)).y;
                    gotV = true;
                }
            }
            if (!gotV) {
                // Never reached with vanilla's hash; declared default, not 0.
                maxV = 0.2f;
            }
            return ((long) Float.floatToIntBits(Math.abs(maxH)) << 32)
                    | (Float.floatToIntBits(Math.abs(maxV)) & 0xFFFFFFFFL);
        } catch (Throwable t) {
            return 0L; // an offset is decoration; never kill a palette entry
        }
    }

    /**
     * How far the offset probe walks before giving up. 4,096 positions
     * against a 1-in-16 hit rate per probe, i.e. eighteen orders of
     * magnitude of headroom; it exists so a modded hash that never sets
     * the bits cannot spin.
     */
    private static final int OFFSET_PROBE_LIMIT = 4096;

    /**
     * The tint index one model asks for: the LOWEST non-negative
     * {@code materialInfo().tintIndex()} over its quads, or
     * {@link #NO_TINT_INDEX} when none of them is tinted.
     *
     * <p><b>Why "the model's index" and not "index 0".</b> A tint index is
     * a subscript into {@code BlockColors.getTintSources(state)}, and a
     * model is under no obligation to use the first entry of that list.
     * Census of the 26.2 jar (all 1,198 blockstates, 2,321 resolvable
     * models, 59 skipped for having no elements at all): sixty models use
     * index 0, eight use index 1, and <b>no model in the game uses more
     * than one</b> - so one sampled colour per palette entry is still
     * enough, it just has to be the colour of the right source. The eight
     * are {@code pink_petals_1..4} and {@code wildflowers_1..4}, whose stem
     * elements carry {@code "tintindex": 1}; {@code BlockColors
     * .createDefault} ip 92-118 registers those two blocks with
     * {@code List.of(BLANK_LAYER, BlockTintSources.grass())} and
     * {@code BLANK_LAYER} is {@code constant(-1)}, i.e. WHITE (clinit ip
     * 0-4). Sampling index 0 therefore painted every wildflower and pink
     * petal stem white, and both stem sprites are pure greyscale
     * (178,178,178 and 152,152,152 - they have no colour of their own at
     * all), so the stems came out light grey where vanilla draws them
     * biome green.</p>
     *
     * <p>The lowest index is taken rather than the first found because
     * {@code found} is in cullface-bucket order, not model order, and a
     * deterministic answer must not depend on which bucket a quad landed
     * in. For every vanilla block the set has one element, so the choice
     * only ever matters to a modded model that mixes indices - and for that
     * one the lowest is at least stable across sessions.</p>
     */
    private static int modelTintIndex(List<BakedQuad> found) {
        int best = NO_TINT_INDEX;
        for (BakedQuad q : found) {
            int index = q.materialInfo().tintIndex();
            if (index >= 0 && (best == NO_TINT_INDEX || index < best)) {
                best = index;
            }
        }
        return best;
    }

    /**
     * Copy a model's quads into one immutable primitive table, deriving the
     * five facts the mesher needs that a {@code BakedQuad} does not state
     * outright: the facing, whether the quad is a full cell face, how far
     * off the cell boundary it sits, whether its UV is a strict crop of its
     * sprite, and whether it is a coplanar overlay of an earlier quad.
     */
    private static Quads buildQuads(List<BakedQuad> found, List<Byte> culls) {
        int n = found.size();
        float[] pos = new float[n * POS_STRIDE];
        float[] uv = new float[n * UV_STRIDE];
        byte[] cull = new byte[n];
        byte[] facing = new byte[n];
        byte[] cutoff = new byte[n];
        boolean[] translucent = new boolean[n];
        boolean[] tinted = new boolean[n];
        boolean[] shaded = new boolean[n];
        boolean[] unit = new boolean[n];
        boolean[] overlay = new boolean[n];
        float[] shift = new float[n];
        boolean[] flush = new boolean[n];
        for (int i = 0; i < n; i++) {
            BakedQuad q = found.get(i);
            int pb = i * POS_STRIDE;
            int ub = i * UV_STRIDE;
            for (int v = 0; v < BakedQuad.VERTEX_COUNT; v++) {
                Vector3fc p = q.position(v);
                pos[pb + v * 3] = p.x();
                pos[pb + v * 3 + 1] = p.y();
                pos[pb + v * 3 + 2] = p.z();
                long packed = q.packedUV(v);
                uv[ub + v * 2] = UVPair.unpackU(packed);
                uv[ub + v * 2 + 1] = UVPair.unpackV(packed);
            }
            cull[i] = culls.get(i);
            QuadFacing f = facingOf(pos, pb);
            // Belt and braces on the winding convention: a quad that
            // DECLARES a cullface and whose derived facing disagrees with it
            // was wound the other way round, so reverse it and re-derive.
            // Vanilla winds counter-clockwise from outside and this never
            // fires on vanilla assets; it is here so the convention is
            // checked per model rather than assumed once for all packs.
            if (cull[i] != NO_CULL && f != QuadFacing.UNASSIGNED
                    && f.ordinal() != facingOrdinalOf(cull[i])) {
                reverseWinding(pos, pb, uv, ub);
                f = facingOf(pos, pb);
            }
            facing[i] = (byte) f.ordinal();
            BakedQuad.MaterialInfo info = q.materialInfo();
            ChunkSectionLayer layer = info.layer();
            translucent[i] = layer == ChunkSectionLayer.TRANSLUCENT;
            cutoff[i] = (byte) (layer == ChunkSectionLayer.CUTOUT ? 2 : 0);
            tinted[i] = info.tintIndex() >= 0;
            shaded[i] = info.shade();
            unit[i] = unitFace(pos, pb, f);
            shift[i] = unit[i] ? planeShift(pos, pb, f) : 0.0f;
            // BEFORE the overlay nudge below, which is this class's own
            // z-fighting displacement and not the model's geometry. A quad
            // that is flush here stays flush in every sense the mesher's
            // per-corner lighting cares about, however many coplanar layers
            // are stacked on it afterwards.
            flush[i] = unit[i] && shift[i] == 0.0f;
        }
        // Coplanar overlays, in MODEL order: grass_block's tinted #overlay
        // element sits exactly on its #side element. See the class javadoc.
        // The planes are SNAPSHOT first, because the loop below moves them:
        // comparing against an already-nudged quad would find the third
        // coplanar quad 1/1024 away from the second, call it separate, and
        // stack the two on top of each other.
        float[] plane = new float[n];
        for (int i = 0; i < n; i++) {
            plane[i] = planeOf(pos, i * POS_STRIDE, facing[i]);
        }
        for (int i = 0; i < n; i++) {
            if (facing[i] == QuadFacing.UNASSIGNED.ordinal()) {
                continue; // no normal axis to nudge along
            }
            int layers = 0;
            for (int j = 0; j < i; j++) {
                if (facing[j] == facing[i]
                        && Math.abs(plane[j] - plane[i]) <= CELL_EPSILON) {
                    layers++;
                }
            }
            if (layers == 0) {
                continue;
            }
            overlay[i] = true;
            nudge(pos, i * POS_STRIDE, facing[i], layers * OVERLAY_NUDGE);
            if (unit[i]) {
                shift[i] = planeShift(pos, i * POS_STRIDE,
                        QuadFacing.byIndex(facing[i]));
            }
        }
        return new Quads(n, pos, uv, cull, facing, cutoff, translucent, tinted,
                shaded, unit, overlay, shift, flush);
    }

    /**
     * The {@link QuadFacing} of one quad, from the right-handed cross
     * {@code (v1-v0) x (v2-v0)} the decoder derives facing with
     * ({@code VanillaMeshDecoder.deriveFacing}).
     *
     * <p>Axis-aligned within {@link #AXIS_EPSILON} of its own longest
     * component, else {@link QuadFacing#UNASSIGNED} — a cross blade at 45
     * degrees and a wall torch at 22.5 both land there and are correctly
     * never face-culled.</p>
     */
    private static QuadFacing facingOf(float[] pos, int base) {
        float ax = pos[base + 3] - pos[base];
        float ay = pos[base + 4] - pos[base + 1];
        float az = pos[base + 5] - pos[base + 2];
        float bx = pos[base + 6] - pos[base];
        float by = pos[base + 7] - pos[base + 1];
        float bz = pos[base + 8] - pos[base + 2];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float mx = Math.abs(nx);
        float my = Math.abs(ny);
        float mz = Math.abs(nz);
        float max = Math.max(mx, Math.max(my, mz));
        if (max <= 0.0f) {
            return QuadFacing.UNASSIGNED; // degenerate quad
        }
        float tol = max * AXIS_EPSILON;
        if (mx == max && my <= tol && mz <= tol) {
            return nx > 0.0f ? QuadFacing.POS_X : QuadFacing.NEG_X;
        }
        if (my == max && mx <= tol && mz <= tol) {
            return ny > 0.0f ? QuadFacing.POS_Y : QuadFacing.NEG_Y;
        }
        if (mz == max && mx <= tol && my <= tol) {
            return nz > 0.0f ? QuadFacing.POS_Z : QuadFacing.NEG_Z;
        }
        return QuadFacing.UNASSIGNED;
    }

    /**
     * The {@link QuadFacing} ordinal a cullface INDEX names.
     * {@code Direction}'s declaration order is DOWN, UP, NORTH, SOUTH, WEST,
     * EAST; {@code QuadFacing}'s is POS_X, POS_Y, POS_Z, NEG_X, NEG_Y,
     * NEG_Z, UNASSIGNED. Two different orders for the same six directions,
     * mapped once here rather than at every site that needs both.
     */
    private static int facingOrdinalOf(int faceIndex) {
        return switch (faceIndex) {
            case FACE_IDX_DOWN -> QuadFacing.NEG_Y.ordinal();
            case FACE_IDX_UP -> QuadFacing.POS_Y.ordinal();
            case FACE_IDX_NORTH -> QuadFacing.NEG_Z.ordinal();
            case FACE_IDX_SOUTH -> QuadFacing.POS_Z.ordinal();
            case FACE_IDX_WEST -> QuadFacing.NEG_X.ordinal();
            default -> QuadFacing.POS_X.ordinal();
        };
    }

    /** Swap vertices 1 and 3 in place, which reverses a quad's winding. */
    private static void reverseWinding(float[] pos, int pb, float[] uv, int ub) {
        for (int c = 0; c < 3; c++) {
            float t = pos[pb + 3 + c];
            pos[pb + 3 + c] = pos[pb + 9 + c];
            pos[pb + 9 + c] = t;
        }
        for (int c = 0; c < 2; c++) {
            float t = uv[ub + 2 + c];
            uv[ub + 2 + c] = uv[ub + 6 + c];
            uv[ub + 6 + c] = t;
        }
    }

    /** 0 = x, 1 = y, 2 = z: the axis a facing's normal runs along. */
    private static int axisOf(QuadFacing facing) {
        return switch (facing) {
            case POS_X, NEG_X -> 0;
            case POS_Y, NEG_Y -> 1;
            case POS_Z, NEG_Z -> 2;
            default -> -1;
        };
    }

    /** True for the three facings whose cell boundary plane is at 1. */
    private static boolean positive(QuadFacing facing) {
        return facing == QuadFacing.POS_X || facing == QuadFacing.POS_Y
                || facing == QuadFacing.POS_Z;
    }

    /** The plane coordinate of an axis-aligned quad (vertex 0 is enough). */
    private static float planeOf(float[] pos, int base, int facingOrdinal) {
        int axis = axisOf(QuadFacing.byIndex(facingOrdinal));
        return axis < 0 ? 0.0f : pos[base + axis];
    }

    /** Move every vertex of one quad along its own outward normal. */
    private static void nudge(float[] pos, int base, int facingOrdinal, float by) {
        QuadFacing facing = QuadFacing.byIndex(facingOrdinal);
        int axis = axisOf(facing);
        if (axis < 0) {
            return;
        }
        float delta = positive(facing) ? by : -by;
        for (int v = 0; v < BakedQuad.VERTEX_COUNT; v++) {
            pos[base + v * 3 + axis] += delta;
        }
    }

    /**
     * Is this quad a rectangle filling the whole cell on both of its
     * in-plane axes, and flat on its own?
     *
     * <p>That is the greedy merge's entry condition ({@code eligibleCell}
     * demands a unit in-plane span), so it is what decides whether the quad
     * can take the merge path at all. Being wrong in the conservative
     * direction costs quads and never correctness, which is why
     * {@link #CELL_EPSILON} is a comfort tolerance here and not a
     * classifier.</p>
     */
    private static boolean unitFace(float[] pos, int base, QuadFacing facing) {
        int axis = axisOf(facing);
        if (axis < 0) {
            return false;
        }
        float plane = pos[base + axis];
        for (int v = 1; v < BakedQuad.VERTEX_COUNT; v++) {
            if (Math.abs(pos[base + v * 3 + axis] - plane) > CELL_EPSILON) {
                return false; // not flat on its own normal axis
            }
        }
        for (int a = 0; a < 3; a++) {
            if (a == axis) {
                continue;
            }
            float lo = Float.MAX_VALUE;
            float hi = -Float.MAX_VALUE;
            for (int v = 0; v < BakedQuad.VERTEX_COUNT; v++) {
                float c = pos[base + v * 3 + a];
                lo = Math.min(lo, c);
                hi = Math.max(hi, c);
            }
            if (Math.abs(lo) > CELL_EPSILON || Math.abs(hi - 1.0f) > CELL_EPSILON) {
                return false; // does not span the cell on this axis
            }
        }
        return true;
    }

    /**
     * How far a {@link #unitFace} sits from the cell boundary plane its
     * facing names: 0 for a face flush with the cell, {@code -14/16} for a
     * snow layer's top, {@code +1/1024} for a nudged overlay. The mesher
     * builds at the boundary so the sweep can merge, then shifts by this.
     */
    private static float planeShift(float[] pos, int base, QuadFacing facing) {
        int axis = axisOf(facing);
        if (axis < 0) {
            return 0.0f;
        }
        return pos[base + axis] - (positive(facing) ? 1.0f : 0.0f);
    }

    /**
     * Water and lava: vanilla's own sprite choices on a unit cube, plus the
     * state's real height.
     *
     * <p>A fluid has no baked model to copy, so this is the one place the
     * class BUILDS geometry rather than reading it, and it builds the only
     * shape a fluid ever has — the unit cube, whose top the mesher then
     * lowers to {@link Entry#fluidHeight}. The six faces are written in
     * vanilla's winding (counter-clockwise from outside), which
     * {@link #facingOf} then confirms rather than trusts.</p>
     *
     * <h2>Which sprite goes on which face (javap, {@code FluidRenderer})</h2>
     * <p>Before format 4 every face wore {@code stillMaterial()}, and that
     * was wrong on four of the six for every water block in the world, not
     * only for flowing ones. Vanilla's own choices, read off
     * {@code FluidRenderer.tesselate} on the 26.2 merged jar:</p>
     * <ul>
     * <li><b>DOWN</b>: {@code stillMaterial()}, whole sprite (ip 1052-1081).</li>
     * <li><b>UP</b>: {@code stillMaterial()} whole sprite when the flow
     *     vector is exactly zero (ip 671-745), else {@code flowingMaterial()}
     *     through a half-size window rotated to the flow angle
     *     ({@code atan2(flow.z, flow.x) - PI/2}, radius {@code 0.25}, ip
     *     748-830). The choice keys on the FLOW, not on source-ness: a
     *     source feeding a drop rotates, a level pool of flowing cells does
     *     not. This table therefore bakes the ZERO-FLOW answer (still,
     *     whole) for every fluid row, and {@code ShellMesher} reproduces
     *     {@code FlowingFluid.getFlow} from the record's own levels
     *     ({@code fluidField}) and swaps in the rotated window per cell -
     *     the {@link FluidLook} beside this row carries the flowing
     *     sprite's rect for it. The pre14-pre17 bake (flowing centre
     *     window for every non-source) was the owner's "flowing water
     *     still not perfect": every far stream's streak pointed the same
     *     way.</li>
     * <li><b>SIDES</b>: {@code flowingMaterial()} always - not the still
     *     sprite, whatever the level - through the sprite's left half
     *     horizontally and top half vertically
     *     ({@code getU(0)..getU(0.5)}, {@code getV((1-h)*0.5)..getV(0.5)},
     *     ip 1470-1583). The {@code (1-h)} term follows the corner height;
     *     the far field uses {@code h = 1} for the window and lets the
     *     mesher lower the geometry, which stretches the side texture by at
     *     most {@code (1 - 8/9)/2 = 5.6%} of the sprite's top half on a
     *     surface cell and not at all on a submerged one. Vanilla's
     *     {@code overlayMaterial()} case (fluid against glass or leaves) is
     *     not modelled: the shell does not know what its neighbour is at
     *     mesh time.</li>
     * </ul>
     *
     * <p>Every face declares its own direction as its cullface, so a fluid
     * cell draws exactly the faces its mask names — which is what the
     * extractor's occlusion rule already decided, using
     * {@code LiquidBlock.skipRendering} to drop water against water.</p>
     */
    private static Entry resolveFluid(BlockState state, FluidState fluid,
            ModelManager models) {
        FluidModel model = models.getFluidStateModelSet().get(fluid);
        TextureAtlasSprite still = model.stillMaterial().sprite();
        TextureAtlasSprite flowing = flowingSprite(model, still);
        float u0 = still.getU0();
        float v0 = still.getV0();
        float u1 = still.getU1();
        float v1 = still.getV1();
        // UP: the still sprite whole, for SOURCE AND FLOWING alike - which
        // is vanilla's zero-flow branch (FluidRenderer.tesselate ip
        // 662-693: the sprite choice keys on getFlow(), NOT on source-ness;
        // a flowing cell whose neighbours all stand level takes the still
        // sprite whole, and a SOURCE feeding a drop takes the flowing
        // sprite rotated). Every cell whose flow is non-zero also has an
        // unequal corner average (a flow delta needs a neighbouring height
        // difference, and any height difference moves fluidCorner by
        // >= 1/9 per weighted slot, orders past CORNER_EPSILON), so it
        // leaves the merged surface bucket, and ShellMesher overrides its
        // top UVs per cell with the rotated quarter window this row's
        // FluidLook carries. The old bake here (flowing centre window for
        // every non-source) was vanilla's formula frozen at one angle -
        // every far stream wore a southward streak.
        float tu0 = u0;
        float tv0 = v0;
        float tu1 = u1;
        float tv1 = v1;
        // SIDES: the flowing sprite's left half by its top half, always.
        float su0 = flowing.getU(0.0f);
        float sv0 = flowing.getV(0.0f);
        float su1 = flowing.getU(0.5f);
        float sv1 = flowing.getV(0.5f);
        // Vanilla's own layer for this fluid: water TRANSLUCENT, lava SOLID.
        // Water's transparency then comes from water_still.png's tRNS alpha
        // of 180 under the translucent pipeline's blend.
        boolean trans = model.layer() == ChunkSectionLayer.TRANSLUCENT;
        float[] pos = {
            // DOWN (-Y), cullface down
            0, 0, 0,  1, 0, 0,  1, 0, 1,  0, 0, 1,
            // UP (+Y), cullface up
            0, 1, 0,  0, 1, 1,  1, 1, 1,  1, 1, 0,
            // NORTH (-Z)
            0, 0, 0,  0, 1, 0,  1, 1, 0,  1, 0, 0,
            // SOUTH (+Z)
            0, 0, 1,  1, 0, 1,  1, 1, 1,  0, 1, 1,
            // WEST (-X)
            0, 0, 1,  0, 1, 1,  0, 1, 0,  0, 0, 0,
            // EAST (+X)
            1, 0, 0,  1, 1, 0,  1, 1, 1,  1, 0, 1,
        };
        float[] uv = {
            u0, v0,  u1, v0,  u1, v1,  u0, v1,          // down  still, whole
            tu0, tv0,  tu0, tv1,  tu1, tv1,  tu1, tv0,  // up
            su0, sv1,  su0, sv0,  su1, sv0,  su1, sv1,  // north flowing
            su0, sv1,  su1, sv1,  su1, sv0,  su0, sv0,  // south
            su1, sv1,  su1, sv0,  su0, sv0,  su0, sv1,  // west
            su0, sv1,  su0, sv0,  su1, sv0,  su1, sv1,  // east
        };
        byte[] cull = new byte[FACES];
        byte[] facing = new byte[FACES];
        byte[] cutoff = new byte[FACES];
        boolean[] translucent = new boolean[FACES];
        boolean[] tinted = new boolean[FACES];
        boolean[] shaded = new boolean[FACES];
        boolean[] unit = new boolean[FACES];
        boolean[] overlay = new boolean[FACES];
        float[] shift = new float[FACES];
        boolean[] flush = new boolean[FACES];
        for (int f = 0; f < FACES; f++) {
            cull[f] = (byte) f;
            facing[f] = (byte) facingOf(pos, f * POS_STRIDE).ordinal();
            cutoff[f] = 0;
            translucent[f] = trans;
            // Fluid sprites are grayscale sources (water_still's palette is
            // 165/216/249/255 grays); the colour is always a tint. For lava
            // the tint source is a constant white, so marking the face tinted
            // is a no-op there rather than a lie.
            tinted[f] = true;
            shaded[f] = true;
            unit[f] = true;
            overlay[f] = false;
            shift[f] = 0.0f;
            flush[f] = true;
        }
        int fallback = WHITE_RGB;
        try {
            fallback = model.tintSource().color(state) & 0xFFFFFF;
        } catch (Throwable t) {
            // Out-of-world colour is a fallback for old caches only.
        }
        // Emission IS read downstream: lava is 15 and is the reason this is
        // not hard-coded to zero on the fluid path.
        // NO_TINT_INDEX: a fluid has no baked model and therefore no
        // tintIndex to read. Its colour comes from FluidModel.tintSource()
        // and not from BlockColors at all, which sampleTint answers in its
        // FIRST branch, so this value is never looked up.
        // The overlay sprite (water_overlay): vanilla swaps a side face's
        // sprite to it against glass and leaves (FluidRenderer.tesselate
        // ip 1483-1527: overlayMaterial() non-null AND the neighbour block
        // instanceof HalfTransparentBlock || instanceof LeavesBlock). Lava's
        // FluidModel declares none, so hasOverlay is false there and the
        // mesher never asks.
        TextureAtlasSprite overlaySprite = null;
        try {
            var overlayMat = model.overlayMaterial();
            overlaySprite = overlayMat == null ? null : overlayMat.sprite();
        } catch (Throwable t) {
            // A modded FluidModel that throws keeps the flowing side look.
        }
        return new Entry(KIND_FLUID, lightEmission(state), fallback,
                NO_TINT_INDEX,
                new Quads(FACES, pos, uv, cull, facing, cutoff, translucent,
                        tinted, shaded, unit, overlay, shift, flush),
                fluidHeightOf(fluid), 0.0f, 0.0f,
                new FluidLook(fluidFamilyOf(fluid), flowing.getU0(),
                        flowing.getV0(), flowing.getU1(), flowing.getV1(),
                        overlaySprite != null,
                        overlaySprite == null ? 0.0f : overlaySprite.getU0(),
                        overlaySprite == null ? 0.0f : overlaySprite.getV0(),
                        overlaySprite == null ? 0.0f : overlaySprite.getU1(),
                        overlaySprite == null ? 0.0f : overlaySprite.getV1()),
                null, null, true, false);
    }

    /**
     * The flowing sprite of one fluid model, with the still one as a
     * fallback. javap: {@code FluidModel.flowingMaterial()} is a record
     * accessor and vanilla builds both fluid models with a real material,
     * so the fallback is for a resource pack that removed one.
     */
    private static TextureAtlasSprite flowingSprite(FluidModel model,
            TextureAtlasSprite still) {
        try {
            Material.Baked material = model.flowingMaterial();
            TextureAtlasSprite sprite = material == null ? null : material.sprite();
            return sprite == null ? still : sprite;
        } catch (Throwable t) {
            return still;
        }
    }

    /**
     * How tall this fluid state stands, in blocks, clamped into (0, 1].
     *
     * <p>javap: {@code FluidState.getOwnHeight()} delegates to
     * {@code Fluid.getOwnHeight(FluidState)}, whose flowing implementation
     * is {@code getAmount() / 9.0f} (ip 0-9), and
     * {@code WaterFluid$Flowing.getAmount} reads the fluid state's own
     * {@code LEVEL} property, 1 to 8. A source is therefore 8/9 =
     * {@code FluidRenderer.MAX_FLUID_HEIGHT}, the one height the mesher
     * could draw before format 4.</p>
     */
    private static float fluidHeightOf(FluidState fluid) {
        try {
            float h = fluid.getOwnHeight();
            if (!(h > 0.0f)) {
                return ShellMesher.FLUID_SURFACE_HEIGHT; // NaN or 0: source look
            }
            return Math.min(h, 1.0f);
        } catch (Throwable t) {
            return ShellMesher.FLUID_SURFACE_HEIGHT;
        }
    }

    /**
     * The block's own light emission, clamped to 0..15.
     *
     * <p>javap on the merged jar:
     * {@code BlockBehaviour$BlockStateBase: public int getLightEmission();}
     * over a {@code private final int lightEmission} the state caches at
     * construction, so this is a field read and not a computation. It is a
     * per-STATE value, so since store format 4 a redstone lamp that was LIT
     * when the chunk was saved reads 15 and one that was not reads 0, which
     * is the same fix the geometry got and for the same reason. A record
     * older than format 4 still resolves the block's default state and so
     * still reads a redstone lamp unlit.</p>
     */
    private static byte lightEmission(BlockState state) {
        try {
            return (byte) Math.max(0, Math.min(15, state.getLightEmission()));
        } catch (Throwable t) {
            return 0; // never let a lighting nicety kill a palette entry
        }
    }

    /**
     * The block's out-of-world tint at index 0, or white. This is the
     * biome-independent default ({@code GrassColor.getDefaultColor()} and
     * friends) and is used ONLY for format-1 records, whose palettes carry no
     * sampled colour. It is why an old cache renders plains-green grass
     * rather than the gray of the pre-fix build.
     */
    private static int defaultBlockTint(BlockState state, int tintIndex) {
        try {
            List<BlockTintSource> sources =
                    Minecraft.getInstance().getBlockColors().getTintSources(state);
            BlockTintSource source = tintSourceFor(sources, tintIndex);
            if (source != null) {
                return source.color(state) & 0xFFFFFF;
            }
        } catch (Throwable t) {
            // Fall through to white.
        }
        return WHITE_RGB;
    }

    /**
     * The tint source a model's own {@code tintIndex} names, or null when
     * the block registers none at all.
     *
     * <p>An index past the end of the list, and {@link #NO_TINT_INDEX}
     * (a model with no tinted quad, whose colour is therefore never
     * multiplied into anything), both fall back to the first source - which
     * is what this class did for every block before the index was read at
     * all, so the fallback is the old behaviour rather than a new guess.</p>
     */
    private static BlockTintSource tintSourceFor(List<BlockTintSource> sources,
            int tintIndex) {
        if (sources.isEmpty()) {
            return null;
        }
        return sources.get(tintIndex >= 0 && tintIndex < sources.size()
                ? tintIndex : 0);
    }
}
