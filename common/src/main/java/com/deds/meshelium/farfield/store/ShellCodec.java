/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.farfield.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/**
 * The MODEL S shell record format: one chunk column's exposed-surface
 * cells, palette-packed at exactly 4 bytes per cell, zlib level 6 on disk.
 * This is the encoding the census measured (SHELL-CENSUS-2026-08.md,
 * "MODEL S ... packed pos 2 B + palette idx 1 B + face mask 1 B"): full
 * shell p50 4.26 KB, p95 8.71 KB per chunk zlib'd; surface band p50
 * 0.94 KB, p95 2.01 KB. Pure JDK code, no vanilla types, unit-testable
 * cold: {@link #encode(Shell)} and {@link #decode(byte[])} are pure
 * functions of their arguments.
 *
 * <h2>Cell packing</h2>
 * A cell is one int, and its low 16 bits are the on-disk packed position:
 * <pre>
 *   bits  0- 3  z            (0..15)
 *   bits  4- 7  x            (0..15)
 *   bits  8-15  yRel         (0..255, y minus the record's {@link Shell#minY})
 *   bits 16-23  paletteIndex (0..255, index into {@link Shell#palette})
 *   bits 24-29  faceMask     (bit per exposed face; bit order below)
 * </pre>
 * yRel is 8 bits ON PURPOSE and it is safe: the world y span is far more
 * than 255, so positions are stored relative to a per-record minY (a
 * short in the header), and the census verified 0 of 300 real-worldgen
 * chunks had a shell y-span above 255 (SHELL-CENSUS-2026-08.md,
 * methodology sanity list). A hypothetical chunk that violated the span
 * simply cannot be represented; the extractor (W2) must split or clamp,
 * and {@link #encode(Shell)} refuses rather than corrupt.
 *
 * <p>Face-mask bit order follows vanilla's 3D data-value order as a
 * CONVENTION (no vanilla call is made): bit 0 down (-y), bit 1 up (+y),
 * bit 2 north (-z), bit 3 south (+z), bit 4 west (-x), bit 5 east (+x).
 * A cell with mask 0 owns no exposed face and has no business existing,
 * so both directions reject it.</p>
 *
 * <h2>THE STORAGE HALF OF THE FAR-FIELD MODEL, and what it costs</h2>
 * <p>This format is the whole reason the far field approximates anything at
 * all, so it is worth saying plainly what it does and does not carry. A
 * cell is <b>a position, a palette index, and which of its six faces are
 * visible</b>, in exactly four bytes. What the palette index POINTS AT is
 * the part that moved at format 4: it used to be a block NAME, and it is
 * now a block STATE.</p>
 *
 * <p>The two halves that read it hold up their end without inventing
 * anything: {@code ShellExtractor} fills the mask with vanilla's own
 * {@code Block.shouldRenderFace}, and {@code SpriteUvResolver} /
 * {@code ShellMesher} draw the state's own baked quads. Before format 4 the
 * palette named a BLOCK and the mesher drew that block's DEFAULT state, so
 * a stair's {@code facing}/{@code half}/{@code shape}, {@code snow}'s
 * {@code layers}, a fence's connections, a wall torch's {@code facing}, a
 * grass block's {@code snowy}, a leaf litter's {@code segment_amount} and a
 * fluid's {@code level} all collapsed to whatever the block registered as
 * its default. That was the far field's last big approximation and format 4
 * is where it is paid for.</p>
 *
 * <h2>Version 5: the record says which rules extracted it</h2>
 * One int, and it closes the hole that made four band-rule revisions
 * invisible: see {@link #SIGNATURE_MIN_VERSION}. The codec only CARRIES
 * the number - it neither computes nor interprets it, so this class stays
 * free of {@code FarFieldConfig} and stays a pure codec. {@code FarField}
 * stamps it on the way in and compares it on the way out.
 *
 * <p><b>A position is NOT a key, and that is load bearing.</b> Two cells
 * may share one (x, yRel, z) with different palette indices, and one
 * producer relies on it: a block standing IN a fluid with air above it
 * stores its own state AND the fluid's, because vanilla draws both there
 * and a cell can name only one of them
 * ({@code ShellExtractor}'s "THE WATER SURFACE UNDER A PLANT"). Nothing in
 * this format needs uniqueness - the cell stream is a LIST, {@code minY} is
 * derived from the extremes and the light plane is parallel by index - so
 * do not add a de-duplicating pass on either side of it.</p>
 *
 * <h2>Record layout (format version 4)</h2>
 * <pre>
 *   bytes 0-3   magic 'M' 'F' 'F' '1'  (plain, outside the compression)
 *   bytes 4-    one zlib stream (Deflater level 6) of the payload:
 *     u8   formatVersion  (1..{@value #FORMAT_VERSION})
 *  v5 s32  saveSignature  (big-endian; the value
 *                          {@code FarFieldConfig.farSaveSignature()} had
 *                          when this record was EXTRACTED. Compared at
 *                          decode; a mismatch means the extraction rules
 *                          moved and the record is stale. First field
 *                          after the version on purpose, so a reader can
 *                          reject a stale record before parsing anything
 *                          else)
 *     u8   tier           ({@link #TIER_GENERATED} / {@link #TIER_IMPORTED}
 *                          / {@link #TIER_VISITED})
 *     s8   bandOffset     ({@link #FULL_SHELL} = no band cut; else the
 *                          blocks-below-top offset the shell was cut with,
 *                          stored per record so the option can become a
 *                          slider without a store migration - resolution 4,
 *                          FAR-FIELD-DESIGN.md section 10)
 *     s16  minY           (big-endian; yRel base)
 *     s32  cellCount      (big-endian; 0..{@value #MAX_CELLS})
 *     u16  paletteCount   (big-endian; 0..{@value #MAX_PALETTE}) - ENTRIES,
 *                          i.e. what a cell's palette index selects
 *  v1-3 paletteCount x writeUTF(block name)       (modified UTF-8)
 *  v4 u16  nameCount      (distinct block names, 0..paletteCount)
 *  v4 nameCount x { writeUTF(block name), u16 stateSig }
 *  v4 paletteCount x { u8 nameIndex, varint stateIndexPlus1 }
 *                         (stateIndexPlus1 == 0 means "no state stored,
 *                          draw the block's default", which is exactly
 *                          what versions 1 to 3 mean everywhere)
 *  v2 u8   tintPresent    (0 = no tint table follows, 1 = it does)
 *  v3 u8   tintGridSide   (0 = none; 1 = one colour per entry, i.e. v2's
 *                          shape and v2's value; {@value #TINT_SIDE_CELL}
 *                          = the 16x16 CELL field, one colour per block,
 *                          which is what pre19 writes; {@value
 *                          #TINT_SIDE_COARSE}, {@value #TINT_SIDE_FINE}
 *                          and {@value #TINT_SIDE_EXACT} = the older NxN
 *                          CORNER grids, still read, no longer written)
 *  v2 paletteCount x u24 tintRGB -- only when the byte above is 1
 *  v3 per entry, only when side &gt; 1:
 *         u8 uniform      (1 = one colour follows and covers the whole
 *                          chunk; 0 = side*side of them, row-major by z
 *                          then x)
 *         u24 tintRGB x (uniform ? 1 : side*side)
 *  v3 u8   lightPresent   (0 = no per-cell light plane, 1 = one follows)
 *     cellCount x { u16 pos16 (big-endian: hi byte yRel, lo byte x&lt;&lt;4|z),
 *                   u8 paletteIndex, u8 faceMask }
 *  v3 cellCount x u8 light (high nibble sky 0-15, low nibble block 0-15),
 *                          in the SAME order as the cells -- only when
 *                          lightPresent is 1
 * </pre>
 *
 * <p><b>The light plane is a PLANE and not a fifth byte inside the cell
 * record, and that is the whole reason it is affordable.</b> Interleaved,
 * every fourth byte would be a light value between two unrelated ones and
 * zlib's match finder would see a 5-byte period it cannot exploit. Written
 * as one contiguous run it is what it really is: a mostly-constant array
 * (the overwhelming majority of surface-band cells are sky 15 / block 0,
 * i.e. the byte {@code 0xF0}), which deflates to almost nothing. The
 * separation costs one extra loop and no format complexity, since
 * {@code cellCount} already bounds both runs.</p>
 * The magic sits OUTSIDE the zlib stream deliberately: a reader can
 * reject foreign or truncated bytes before paying for an inflate, and a
 * future format version that abandons zlib can still be recognized by
 * its magic. The trailing '1' of the magic is the container version; the
 * formatVersion byte inside is the payload schema version, so a payload
 * change like version 2's tint table does NOT move the magic.
 *
 * <h2>Version 2: the per-palette-entry tint table</h2>
 * Version 1 stored geometry only, and the far field had no way to colour
 * a grayscale sprite: distant grass tops, foliage and water rendered gray
 * (docs/unreleased/farfield/FARFIELD-WAVES.md items A1/A3). Biome tint cannot be resolved at
 * MESH time - the chunk is long gone by then and the far field has no
 * biome source - so the colour is sampled once per palette entry while
 * the chunk is still live and carried in the record.
 *
 * <p><b>Measured cost</b> (real 26.2 worldgen, 300 stride-spread chunks
 * from the census world, surface-band streams rebuilt with the archived
 * census engine and zlib'd both ways): <b>+16.1 B mean, +16 B p50,
 * +23 B p95 per chunk record, i.e. +1.17% mean</b>. The census's 0.94 KB
 * p50 surface-band record becomes ~0.96 KB and the 512-radius surface-band
 * projection moves from 1.02 GB to ~1.03 GB. Raw (pre-zlib) cost is
 * 1 + 3 x paletteCount bytes, mean +26 B; the surface-band palette
 * averages 8.4 names, well under the full shell's 18.8.</p>
 *
 * <p><b>Old records are upgraded, never rejected.</b> {@link #decode}
 * accepts version 1 and hands back {@code paletteTint == null}, which the
 * mesher reads as "no sampled colour" and falls back to the block's
 * out-of-world default tint (plains-green grass rather than gray). A
 * player's existing cache therefore keeps working and improves chunk by
 * chunk as places are re-visited; nothing is wiped and no error counter
 * moves. {@link #encode} always writes {@value #FORMAT_VERSION}.</p>
 *
 * <h2>Version 3: the tint GRID and the light plane</h2>
 * Two independent additions, both optional per record, both driven by the
 * owner's pre7 list (docs/unreleased/farfield/FARFIELD-WAVES.md item H4).
 *
 * <p><b>The tint field</b> answers "oceans do not mix colour" and "chunks
 * do not mix". Version 2 stored ONE colour per palette entry per chunk, so
 * a biome boundary running through a chunk snapped to the chunk edge and
 * neighbouring chunks could not blend. Version 3 stores a grid instead,
 * and its shape has moved once:</p>
 * <ul>
 * <li><b>pre8 to pre18, the CORNER grid</b> ({@value #TINT_SIDE_COARSE},
 *     {@value #TINT_SIDE_FINE}, {@value #TINT_SIDE_EXACT}): samples at
 *     block offsets {@code 0, 16/(N-1), ... 16}, read bilinearly per
 *     cell. Seam agreement came from the shared edge row - offset 16 is
 *     the neighbour's offset 0. Still DECODED; never written now.</li>
 * <li><b>pre19, the CELL field</b> ({@value #TINT_SIDE_CELL}): one node
 *     per block column, read with no interpolation at all. Seam
 *     agreement no longer needs a shared row - each side stores
 *     {@code calculateBlockTint} at its own blocks and that is a pure
 *     function of world position. See {@link #TINT_SIDE_CELL} for why
 *     interpolating at one-block spacing was an error rather than a
 *     refinement.</li>
 * </ul>
 *
 * <p><b>The uniform flag is what makes it nearly free.</b> Almost every
 * chunk in a world sits inside one biome, and then all {@code side*side}
 * samples are the same integer; the flag collapses those back to the three
 * bytes version 2 spent. So the field is paid for only where it does
 * something, which is at biome boundaries. Cost per palette entry, raw:
 * 4 B uniform (flag + colour) against version 2's 3 B, and
 * {@code 1 + 3*side*side} B otherwise - 769 B at
 * {@value #TINT_SIDE_CELL}, against pre18's 868 B at
 * {@value #TINT_SIDE_EXACT}. On the census's mean 8.4-name surface-band
 * palette that is +8 B raw for a single-biome chunk (about +3 B after
 * zlib on a ~1.05 KB record) whatever the side, and, with three tinted
 * entries in play, +2.3 KB raw for a boundary chunk - a boundary-only
 * cost that zlib takes a further large bite out of, because a biome ramp
 * is a monotone run of near-equal triples.</p>
 *
 * <p><b>The light plane</b> answers "no smooth lighting" and half of
 * "torches do not render". One byte per stored cell, sky in the high
 * nibble and block light in the low one, sampled from the live chunk's own
 * light engine at extraction time - the only moment the far field has
 * access to real propagated light. Raw cost is exactly
 * {@code cellCount} bytes, +25% on the 4 B/cell stream; after zlib it is
 * far less, because the plane is contiguous and overwhelmingly one value
 * (see the layout note above). A record without it decodes to
 * {@code cellLight == null} and the mesher falls back to synthesized
 * light, so this too is a per-record property and not a store-wide
 * migration.</p>
 *
 * <h2>Version 4: the palette holds STATES, and it is a two-level table</h2>
 * The owner's pre11 report - torches all facing one way, stairs not
 * rotating, flowing water drawn as solid blocks, leaf piles drawn as a box,
 * grass sides green under snow - is one sentence: the palette named a
 * BLOCK. Version 4 keys it by block STATE instead, which is what vanilla's
 * own chunk sections do, and it costs NOTHING per cell: the cell already
 * spends a byte on a palette index and that index simply selects a finer
 * thing. The whole price is in the palette table, and the table is split in
 * two so a second orientation of a block the record already names is a
 * couple of bytes rather than a repeated string.
 *
 * <p><b>What identifies a state, and why it is not the global id.</b>
 * {@code Block.BLOCK_STATE_REGISTRY} numbers every state in the game, and
 * that number would fit in one varint - but it moves whenever ANY block is
 * added or removed anywhere, so installing a single mod would silently
 * reinterpret every rotation in the cache. What is stored instead is the
 * state's INDEX WITHIN ITS OWN BLOCK
 * ({@code block.getStateDefinition().getPossibleStates()}), which depends
 * only on that block's own property set. Adding a mod does not move
 * vanilla's stairs, so a modpack under development keeps its cached
 * rotations. Vanilla's largest block state space is 1,296
 * ({@code redstone_wire}), so the varint is one byte for the overwhelming
 * majority of blocks and two for the widest.</p>
 *
 * <p><b>The guard, per NAME, {@value #STATE_SIG_BYTES} bytes.</b> A state
 * index is only meaningful against the state space it was written for, so
 * each name in the table carries a 16-bit {@code stateSig}: a hash of that
 * block's state count and of every property's name and value count, in
 * definition order (the reader computes the same thing and compares). A
 * mismatch means that block's shape changed under the cache, and every
 * entry naming it falls back to the default state - the pre-version-4 look,
 * for that one block, in that one record, until it is re-saved. The reader
 * ALSO checks that the index is in range. What is not caught is a state
 * space that keeps its count and its property shape and merely reorders a
 * property's VALUES; that would be a vanilla data-format change, the
 * failure would be a wrongly turned block rather than a wrong block, and it
 * self-heals on re-visit. This is written down rather than guarded because
 * guarding it means hashing every value name of every property.</p>
 *
 * <p><b>Fluid level rides in for free, and that retires the two-spare-bits
 * plan.</b> Version 3's javadoc reserved face-mask bits 6-7 for a two-bit
 * fluid height class. It is not needed and it is not built: water's
 * {@code level} is an ordinary block-state property, so a flowing cell is
 * simply a different palette ENTRY carrying a different state index, and
 * the mesher reads {@code FluidState.getOwnHeight()} off the real state for
 * all nine heights plus {@code falling} rather than four classes. The mask
 * byte keeps its meaning, and bits 6-7 stay free.</p>
 *
 * <p><b>Measured cost.</b> The raw overhead is exact and it is
 * {@code 2 + 4*names + 2*(entries - names) + (varint bytes past the
 * first)}: two for the name count, two per NAME for its signature, and one
 * plus a one-byte varint per ENTRY. So a palette with no repeated block is
 * {@code +4 B} an entry, and each EXTRA state of a block the record already
 * names is {@code +2 B} - against the {@code +22 B} a repeated
 * {@code writeUTF} would have cost, which is what the two-level table
 * buys.</p>
 *
 * <p>Rebuilding real streams both ways and deflating each:</p>
 * <table border="1">
 * <caption>version 3 to version 4, bytes per record</caption>
 * <tr><th>record<th>raw<th>on disk
 * <tr><td>the 63 real harness records, mean (superflat plains, 1.86 names,
 *     298 cells, 561 B){@code  }<td>+9.4<td><b>+10.4 (+1.9%)</b>
 * <tr><td>a census-shaped surface-band record (443 cells, 8 names, 3x3
 *     uniform tint, 995 B)<td>+34<td><b>+29 (+2.9%)</b>
 * <tr><td>a full ocean chunk (1,032 cells, 4 names, 1,210 B)<td>+18
 *     <td>+24 (+2.0%)
 * <tr><td>...plus a four-orientation staircase<td>+22<td>+10
 * <tr><td>...plus four wall torches on four walls<td>+22<td>+13
 * <tr><td>...plus a stream with six distinct water levels<td>+30<td>+11
 * <tr><td>...plus one leaf litter (one new name, one entry)<td>+38<td>+7
 * </table>
 *
 * <p>On the census's 512-radius surface-band projection that moves 1.02 GB
 * to about 1.05 GB. Every extra entry also costs its own tint row, 4 B when
 * uniform, which for a second state of a block the record already names it
 * always is - {@code ShellExtractor} copies the sibling's grid rather than
 * re-blending it, so the biome work does not grow with the palette
 * either.</p>
 *
 * <p><b>The two designs this was priced against, on the same streams.</b>
 * The global {@code BLOCK_STATE_REGISTRY} id with a per-record state-space
 * fingerprint costs {@code +38 B raw / +26 B disk} on the census-shaped
 * record - within three bytes of what shipped - so the choice between them
 * was never about bytes, it was that a per-block index survives a modset
 * change and a global id does not. The name plus a version-proof property
 * payload (property and value NAMES, three properties per stated entry)
 * costs {@code +386 B raw / +65 B disk} on the same record, more than twice
 * the disk and eleven times the raw, and it still needs a
 * "which properties matter" rule that the state palette gets from vanilla
 * for nothing.</p>
 *
 * <p><b>Old records are upgraded, never rejected</b>, exactly as at version
 * 2 and 3. A version 1, 2 or 3 record decodes with {@code paletteState ==
 * null} and draws default states - today's picture - so nothing is wiped
 * and no error counter moves. <b>Rotation appears only on terrain saved by
 * this build or later.</b></p>
 *
 * <h2>Error posture</h2>
 * Decode NEVER throws out of this class: any malformed input - wrong
 * magic, bad zlib data, out-of-range counts, palette index past the
 * table, trailing garbage, an inflated payload past
 * {@value #MAX_PAYLOAD_BYTES} bytes (zip-bomb guard) - returns null.
 * COUNTING the error is the caller's job ({@code FarField} increments
 * farStoreErrors when decode returns null), because a pure codec that
 * reached into live counters would not be unit-testable cold, and the
 * far field's failure accounting must stay in one place per the
 * coverage-guard rule (FARFIELD-CODEBASE-SEAM.md landmine L4).
 * {@link #encode(Shell)} mirrors the posture: an unrepresentable shell
 * returns null instead of throwing.
 */
public final class ShellCodec {

    /** 'M','F','F','1' - the plain 4-byte record prefix. */
    static final byte[] MAGIC = { 'M', 'F', 'F', '1' };

    /** Payload schema version this codec WRITES (and reads). */
    public static final byte FORMAT_VERSION = 5;

    /**
     * Oldest payload schema that carries {@link Shell#saveSignature}, the
     * extraction-rule fingerprint {@code FarFieldConfig.farSaveSignature()}
     * produces.
     *
     * <p><b>Why a record has to carry it (pre20).</b> The signature
     * existed from pre8 and was compared only against an in-MEMORY copy
     * ({@code FarFieldResidency.lastSaveSignature}) that is reset to
     * "unset" on every world era. It was never written to a record, a
     * region header or the store, so the only staleness it could ever
     * detect was a settings flip WITHIN one session, reaching only the
     * columns the client still held. Across a restart it detected
     * nothing at all: {@code BAND_RULE_REVISION} had by then been bumped
     * four times and had never caused one record on disk to be
     * re-extracted. Every extraction-time fix this project shipped -
     * every band rule, every light gate, every colour fix - reached the
     * WRITER and no existing record, which is why three consecutive
     * correct colour fixes changed nothing the owner could see: at rd 32
     * with an L1 radius of 120, the overwhelming majority of what he was
     * looking at was bytes written by an older extractor.</p>
     *
     * <p>A record written before this version therefore reads as
     * "signature unknown", and {@link Shell#signatureMatches} answers
     * false for it - unknown must read as STALE, never as current, or
     * the whole mechanism has the same hole in a new place.</p>
     */
    public static final byte SIGNATURE_MIN_VERSION = 5;

    /**
     * {@link Shell#paletteState} value for an entry with no stored state:
     * the reader draws the block's DEFAULT state, which is what every
     * version 1 to 3 record means and what the far field did before format
     * 4 existed.
     */
    public static final int NO_STATE = -1;

    /**
     * Largest state index this format can carry. Vanilla's widest block
     * state space is 1,296 ({@code redstone_wire}); a million is a corrupt
     * -varint bound, not a promise about any block.
     */
    public static final int MAX_STATE_INDEX = (1 << 20) - 1;

    /** Width of the per-name state-space guard on disk. */
    static final int STATE_SIG_BYTES = 2;

    /**
     * {@link Shell#chunkX} / {@link Shell#chunkZ} for a shell whose world
     * position was never handed to it.
     *
     * <p>The origin is IN MEMORY ONLY - it is never written to disk and
     * never read from it, because the store is already keyed by chunk and
     * a second copy on disk could only ever disagree with the key. It
     * exists so the mesher can compute the per-POSITION appearance inputs
     * vanilla derives at draw time (see {@code ShellMesher}'s "Per-position
     * offset"), and {@link #ORIGIN_UNKNOWN} means "do not": a shell with no
     * origin draws exactly as it drew before the origin existed, rather
     * than drawing every chunk with chunk (0,0)'s offsets, which would tile
     * one 16x16 pattern across the whole world.</p>
     */
    public static final int ORIGIN_UNKNOWN = Integer.MIN_VALUE;

    /** {@link Shell#tintGridSide} for a record with no sampled colours. */
    public static final int TINT_SIDE_NONE = 0;
    /**
     * <b>The CELL field, and the only side pre19 writes.</b> 16x16 nodes,
     * one per BLOCK COLUMN of the chunk, cell-centred: node {@code (x, z)}
     * is {@code ClientLevel.calculateBlockTint}'s own answer at world
     * {@code (chunkX*16 + x, y, chunkZ*16 + z)} - the exact integer the
     * near field multiplies into that block's quad. The mesher reads it
     * with NO interpolation ({@code ShellMesher.cellTint}: cell
     * {@code (x, z)} takes node {@code (x, z)}), so the reconstructed
     * field is vanilla's own piecewise-constant-per-block field rather
     * than an approximation of it.
     *
     * <p><b>Why this is not "TINT_SIDE_EXACT minus one".</b> A CORNER
     * grid exists to interpolate BETWEEN samples, which is only ever a
     * reconstruction of a field the record could not afford to store. At
     * one-block spacing there is nothing left to reconstruct, and the
     * interpolation actively harms: 17 corner nodes read at cell centres
     * average two adjacent blocks' values, i.e. a second box blur on top
     * of vanilla's, widening every biome ramp by a block and shifting it
     * half a block (pre18's documented "half-block phase"). Dropping to
     * 16 cell nodes deletes that error, stores 33 fewer nodes per entry,
     * and needs no shared edge row: <b>seam agreement is by construction
     * because each side stores {@code calculateBlockTint} at ITS OWN
     * blocks, and that function is a pure function of world position.</b>
     * Two adjacent records cannot disagree about a block only one of them
     * contains.</p>
     *
     * <p>Same v3 container - the side byte was always a u8, and the
     * encoder's per-entry uniform shortcut keeps untinted and one-biome
     * entries at 4 bytes each whatever the side. A build older than pre19
     * rejects the value in {@code legalTintSide} and reads such a record
     * as corrupt (decode null, counted): the documented downgrade cost,
     * the same one {@link #TINT_SIDE_EXACT} carried at pre18.</p>
     */
    public static final int TINT_SIDE_CELL = 16;
    /**
     * Per-BLOCK CORNER grid: 17x17 nodes at one-block spacing. Written by
     * pre18 only, for a record a biome boundary ran through (the owner's
     * pre17 R8). <b>Still read, never written since pre19</b>, which
     * replaced the whole corner-grid family with {@link #TINT_SIDE_CELL};
     * a record on disk carrying this side keeps pre18's bilinear read and
     * its half-block phase until the band-rule bump re-extracts it.
     */
    public static final int TINT_SIDE_EXACT = 17;
    /** One colour per palette entry per chunk: version 2's whole table. */
    public static final int TINT_SIDE_SINGLE = 1;
    /** 3x3 corner grid, one sample every 8 blocks. Read-only since pre19. */
    public static final int TINT_SIDE_COARSE = 3;
    /** 5x5 corner grid, one sample every 4 blocks. Read-only since pre19. */
    public static final int TINT_SIDE_FINE = 5;

    /**
     * Oldest payload schema {@link #decode} still accepts. Version 1 is
     * the pre-tint format; it upgrades cleanly to a shell with a null
     * tint table (see the class javadoc).
     */
    public static final byte MIN_READ_VERSION = 1;

    /** bandOffset value meaning "full shell, no surface-band cut". */
    public static final byte FULL_SHELL = (byte) 0xFF;

    // Truth tiers, FAR-FIELD-DESIGN.md section 3.2: server-sent beats
    // cached-import beats seed-generated. The STORE enforces the ordering
    // (FarFieldStore.write); the codec just carries the byte.
    public static final int TIER_GENERATED = 0;
    public static final int TIER_IMPORTED = 1;
    public static final int TIER_VISITED = 2;

    /** 16 x 16 columns x 256 representable yRel values. */
    public static final int MAX_CELLS = 16 * 16 * 256;

    /** One-byte palette index; census max per-chunk palette was 44. */
    public static final int MAX_PALETTE = 256;

    /**
     * Inflate ceiling. The largest legal payload is well under 400 KB
     * (65,536 cells x 4 B, plus at most 65,536 B of light plane, plus 256
     * short palette names with a 2 B signature each, plus 256 x (1 B name
     * index + at most 3 B varint) of state table, plus 256 x (1 + 3 x 25) B
     * of tint grid, plus the header and version 5's 4-byte signature);
     * 1 MiB keeps every legal record and
     * stops a corrupt length from ballooning.
     */
    public static final int MAX_PAYLOAD_BYTES = 1 << 20;

    /**
     * Vanilla dimension height hard limits (min_y >= -2032, logical
     * height <= 4064, so top < 2032); a minY outside this is corrupt.
     * Our own format bound, held as data - no vanilla class is touched.
     */
    static final int MIN_WORLD_Y = -2032;
    static final int MAX_WORLD_Y = 2031;

    private static final int FACE_MASK_ALL = 0x3F;

    private ShellCodec() {
    }

    // ------------------------------------------------------------------
    // The in-memory record
    // ------------------------------------------------------------------

    /**
     * One chunk column's shell, the meshable in-memory form. Fields are
     * final and the arrays are owned by the shell after construction
     * (callers hand them over; W3's mesher reads them in place).
     * {@code cells[0..cellCount-1]} are valid; the array may be longer
     * so extraction buffers can be handed over without a trim copy.
     */
    public static final class Shell {
        public final int cellCount;
        /** Packed cells, layout in the class javadoc. */
        public final int[] cells;
        /**
         * Block names per palette ENTRY, e.g. "minecraft:oak_stairs".
         * Since format 4 the same name may appear on several entries - one
         * per distinct block STATE the chunk held - and the disk table
         * stores it once. Length is the entry count, which is what a cell's
         * palette index is bounded by.
         */
        public final String[] palette;
        /**
         * Format 4: per palette entry, the state's index within its block's
         * {@code getPossibleStates()} list, or {@link #NO_STATE} for an
         * entry that stores no state (every entry of a version 1 to 3
         * record, and any entry a producer had no state for). {@code null}
         * when the whole record carries none, which is the fast exit and
         * the exact shape an old cache decodes to.
         *
         * <p>The index is deliberately per BLOCK and not the global
         * {@code Block.BLOCK_STATE_REGISTRY} id: see the class javadoc's
         * version 4 section for why a modset change must not reinterpret
         * a cached rotation.</p>
         */
        public final int[] paletteState;
        /**
         * Format 4: per palette entry, the 16-bit signature of that block's
         * STATE SPACE, so a reader can tell whether {@link #paletteState}
         * still means what it meant when the record was written. Parallel
         * to {@link #palette} and equal for every entry sharing a name;
         * {@code null} exactly when {@link #paletteState} is.
         */
        public final int[] paletteStateSig;
        /**
         * Sampled biome tints (0xRRGGBB), or {@code null} when the record
         * carries none (a format-1 record, or a shell built by a caller
         * that had no world to sample from). {@code null} is a first-class
         * value, NOT an error: the mesher falls back to the block's
         * out-of-world default tint for it.
         *
         * <p>Laid out as {@code palette.length * tintGridSide *
         * tintGridSide} entries, palette entry major, then z, then x:
         * {@code tint[(pi * side + gz) * side + gx]}. Format 2 records
         * decode to {@code tintGridSide == 1}, i.e. one colour per entry,
         * which is exactly what they stored - so a v2 cache needs no
         * special case anywhere downstream, only a degenerate
         * interpolation.</p>
         */
        public final int[] paletteTint;
        /**
         * Side of the per-entry tint grid: {@link #TINT_SIDE_NONE} when
         * {@link #paletteTint} is null, {@link #TINT_SIDE_SINGLE},
         * {@link #TINT_SIDE_CELL} (what pre19 writes), or one of the
         * legacy corner grids {@link #TINT_SIDE_COARSE},
         * {@link #TINT_SIDE_FINE}, {@link #TINT_SIDE_EXACT}.
         *
         * <p><b>The side also selects the sampling CONVENTION, and the
         * two are different fields of the same byte.</b> At
         * {@link #TINT_SIDE_CELL} node {@code (x, z)} is the block column
         * {@code (x, z)} of this chunk and the mesher reads it directly.
         * At every other side {@code > 1} the nodes sit on the chunk's
         * CORNERS - sample {@code g} of {@code side} is at block offset
         * {@code g * 16 / (side - 1)}, so the last row coincides with the
         * neighbouring chunk's first - and the mesher interpolates
         * bilinearly at the cell centre. {@code ShellMesher.cellTint}
         * branches on exactly this.</p>
         */
        public final int tintGridSide;
        /**
         * Format 3: one packed light byte per cell (high nibble sky 0-15,
         * low nibble block 0-15) in the same order as {@link #cells}, or
         * {@code null} for a record saved without real lighting. Length is
         * exactly {@link #cellCount} when present.
         */
        public final byte[] cellLight;
        /** {@link #TIER_GENERATED} / {@link #TIER_IMPORTED} / {@link #TIER_VISITED}. */
        public final byte tier;
        public final byte formatVersion;
        /** {@link #FULL_SHELL} or the surface-band offset in blocks. */
        public final byte bandOffset;
        /** World y that yRel 0 maps to. */
        public final short minY;
        /**
         * The value {@code FarFieldConfig.farSaveSignature()} had when
         * this record was extracted, or 0 for a record written before
         * {@link #SIGNATURE_MIN_VERSION} - which is why the test is
         * {@link #signatureMatches(int)} and not an {@code ==}: 0 is a
         * legal signature value, so "no signature" is told from "signature
         * zero" by {@link #formatVersion} and by nothing else.
         *
         * <p>Stored on disk since version 5. Carried, never interpreted:
         * the codec has no opinion about what makes two signatures
         * different, and no dependency on the config that mints them.</p>
         */
        public final int saveSignature;
        /**
         * This record's chunk X, or {@link #ORIGIN_UNKNOWN}. NOT stored on
         * disk and NOT part of the format - see {@link #ORIGIN_UNKNOWN}.
         * The mesher needs the world position of a cell to reproduce
         * vanilla's per-position appearance inputs, and the store's own key
         * is the only honest source of it.
         */
        public final int chunkX;
        /** This record's chunk Z, or {@link #ORIGIN_UNKNOWN}; see {@link #chunkX}. */
        public final int chunkZ;

        /**
         * Full format-4 constructor. {@code paletteState} and
         * {@code paletteStateSig} are both null or both
         * {@code palette.length} long; {@code paletteTint} may be null
         * (then {@code tintGridSide} must be {@link #TINT_SIDE_NONE}), and
         * when it is not, its length must be exactly
         * {@code palette.length * side * side}. {@code cellLight} may be
         * null; when it is not it must hold {@code cellCount} bytes.
         * {@link #encode(Shell)} refuses anything else rather than writing
         * a record it could not read back.
         */
        public Shell(int cellCount, int[] cells, String[] palette,
                int[] paletteState, int[] paletteStateSig,
                int[] paletteTint, int tintGridSide, byte[] cellLight,
                byte tier, byte formatVersion, byte bandOffset, short minY) {
            this(cellCount, cells, palette, paletteState, paletteStateSig,
                    paletteTint, tintGridSide, cellLight, tier, formatVersion,
                    bandOffset, minY, ORIGIN_UNKNOWN, ORIGIN_UNKNOWN, 0);
        }

        /**
         * The same shell, plus the world position the store keyed it by.
         * The two extra ints are memory-only; {@link #encode(Shell)} does
         * not write them and {@link #decode(byte[])} does not invent them.
         */
        public Shell(int cellCount, int[] cells, String[] palette,
                int[] paletteState, int[] paletteStateSig,
                int[] paletteTint, int tintGridSide, byte[] cellLight,
                byte tier, byte formatVersion, byte bandOffset, short minY,
                int chunkX, int chunkZ) {
            this(cellCount, cells, palette, paletteState, paletteStateSig,
                    paletteTint, tintGridSide, cellLight, tier, formatVersion,
                    bandOffset, minY, chunkX, chunkZ, 0);
        }

        /**
         * The widest shape: the shell, its world position, and the
         * extraction-rule signature it was written under. Every other
         * constructor funnels here.
         */
        public Shell(int cellCount, int[] cells, String[] palette,
                int[] paletteState, int[] paletteStateSig,
                int[] paletteTint, int tintGridSide, byte[] cellLight,
                byte tier, byte formatVersion, byte bandOffset, short minY,
                int chunkX, int chunkZ, int saveSignature) {
            this.saveSignature = saveSignature;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.cellCount = cellCount;
            this.cells = cells;
            this.palette = palette;
            boolean states = paletteState != null && paletteStateSig != null;
            this.paletteState = states ? paletteState : null;
            this.paletteStateSig = states ? paletteStateSig : null;
            this.paletteTint = paletteTint;
            this.tintGridSide = paletteTint == null ? TINT_SIDE_NONE : tintGridSide;
            this.cellLight = cellLight;
            this.tier = tier;
            this.formatVersion = formatVersion;
            this.bandOffset = bandOffset;
            this.minY = minY;
        }

        /**
         * Format-3 shape: no per-entry block state, so every entry draws
         * its block's default. Kept so callers written against version 3
         * keep compiling and keep meaning the same thing.
         */
        public Shell(int cellCount, int[] cells, String[] palette, int[] paletteTint,
                int tintGridSide, byte[] cellLight,
                byte tier, byte formatVersion, byte bandOffset, short minY) {
            this(cellCount, cells, palette, null, null, paletteTint, tintGridSide,
                    cellLight, tier, formatVersion, bandOffset, minY);
        }

        /**
         * Format-2 shape: one tint per palette entry, no light plane. Kept
         * so callers written against version 2 keep compiling and keep
         * meaning the same thing.
         */
        public Shell(int cellCount, int[] cells, String[] palette, int[] paletteTint,
                byte tier, byte formatVersion, byte bandOffset, short minY) {
            this(cellCount, cells, palette, paletteTint, TINT_SIDE_SINGLE, null,
                    tier, formatVersion, bandOffset, minY);
        }

        /**
         * Geometry-only constructor: no sampled tints. Kept as the format-1
         * shape so existing callers (the codec's own gametest) build a
         * legal shell without knowing about the tint table.
         */
        public Shell(int cellCount, int[] cells, String[] palette, byte tier,
                byte formatVersion, byte bandOffset, short minY) {
            this(cellCount, cells, palette, null, TINT_SIDE_NONE, null,
                    tier, formatVersion, bandOffset, minY);
        }

        /**
         * The same record, told where in the world it came from. Arrays are
         * shared by reference (a {@code Shell} is immutable by contract), so
         * this is one small object and no copying.
         *
         * <p>Called on exactly two paths, and they are the only two places
         * a shell is minted: {@code ShellExtractor} knows the chunk it is
         * walking, and {@code FarField.ReadTask} knows the key it read.</p>
         */
        public Shell withOrigin(int chunkX, int chunkZ) {
            return new Shell(cellCount, cells, palette, paletteState,
                    paletteStateSig, paletteTint, tintGridSide, cellLight,
                    tier, formatVersion, bandOffset, minY, chunkX, chunkZ,
                    saveSignature);
        }

        /**
         * The same record, stamped with the extraction-rule signature it
         * was produced under. Arrays are shared by reference, so this is
         * one small object and no copying.
         *
         * <p>Called on exactly one path, immediately before
         * {@link #encode(Shell)}: {@code FarField.WriteTask}, the far
         * field's only writer. Keeping the stamp out of {@code encode}
         * keeps this class free of {@code FarFieldConfig} and keeps an
         * encode/decode round trip in a test deterministic.</p>
         */
        public Shell withSaveSignature(int saveSignature) {
            return new Shell(cellCount, cells, palette, paletteState,
                    paletteStateSig, paletteTint, tintGridSide, cellLight,
                    tier, formatVersion, bandOffset, minY, chunkX, chunkZ,
                    saveSignature);
        }

        /**
         * Does this record carry a stored signature at all? False for
         * every record written before {@link #SIGNATURE_MIN_VERSION}.
         */
        public boolean hasSaveSignature() {
            return formatVersion >= SIGNATURE_MIN_VERSION;
        }

        /**
         * Was this record extracted under the rules {@code current}
         * fingerprints?
         *
         * <p><b>False when the record carries no signature</b>, which is
         * every record an older build wrote. That is deliberate and it is
         * the whole point of version 5: "cannot tell" has to resolve to
         * STALE. Resolving it to "current" is exactly the assumption that
         * let four {@code BAND_RULE_REVISION} bumps ship without
         * re-extracting one byte on disk.</p>
         */
        public boolean signatureMatches(int current) {
            return hasSaveSignature() && saveSignature == current;
        }

        /** Does this record know where in the world it came from? */
        public boolean hasOrigin() {
            return chunkX != ORIGIN_UNKNOWN && chunkZ != ORIGIN_UNKNOWN;
        }

        /**
         * The stored state index of one palette entry, or {@link #NO_STATE}
         * when this record carries none for it. Null-safe and bounds-safe,
         * so a caller never has to know whether the record predates format
         * 4.
         */
        public int stateIndex(int paletteIndex) {
            return paletteState == null || paletteIndex < 0
                    || paletteIndex >= paletteState.length
                    ? NO_STATE : paletteState[paletteIndex];
        }

        /**
         * The stored state-space signature of one palette entry's block, or
         * 0 when this record carries none. Null-safe and bounds-safe.
         */
        public int stateSig(int paletteIndex) {
            return paletteStateSig == null || paletteIndex < 0
                    || paletteIndex >= paletteStateSig.length
                    ? 0 : paletteStateSig[paletteIndex];
        }

        /**
         * The sampled colour of one palette entry at one grid node, or
         * {@link com.deds.meshelium.farfield.mesh.SpriteUvResolver#WHITE_RGB}
         * -shaped white when this record carries no tints. Bounds-safe:
         * a caller that asks outside the grid gets the nearest node.
         */
        public int tintNode(int paletteIndex, int gx, int gz) {
            if (paletteTint == null || tintGridSide <= 0) {
                return 0xFFFFFF;
            }
            int side = tintGridSide;
            int cx = gx < 0 ? 0 : Math.min(gx, side - 1);
            int cz = gz < 0 ? 0 : Math.min(gz, side - 1);
            int index = (paletteIndex * side + cz) * side + cx;
            return index >= 0 && index < paletteTint.length
                    ? paletteTint[index] : 0xFFFFFF;
        }
    }

    // ------------------------------------------------------------------
    // Cell pack/unpack (W2's extractor and W3's mesher both use these)
    // ------------------------------------------------------------------

    public static int packCell(int x, int yRel, int z, int paletteIndex, int faceMask) {
        return (faceMask << 24) | (paletteIndex << 16) | ((yRel & 0xFF) << 8)
                | ((x & 0xF) << 4) | (z & 0xF);
    }

    public static int cellX(int cell) {
        return (cell >>> 4) & 0xF;
    }

    public static int cellZ(int cell) {
        return cell & 0xF;
    }

    public static int cellYRel(int cell) {
        return (cell >>> 8) & 0xFF;
    }

    public static int cellPaletteIndex(int cell) {
        return (cell >>> 16) & 0xFF;
    }

    public static int cellFaceMask(int cell) {
        return (cell >>> 24) & 0xFF;
    }

    // ------------------------------------------------------------------
    // Encode
    // ------------------------------------------------------------------

    /**
     * Serialize a shell to its on-disk record, always at format version
     * {@value #FORMAT_VERSION}. Returns null (never throws) if the shell
     * is unrepresentable: unknown tier, zero band offset, cell/palette
     * counts out of range, a palette index past the table, an empty face
     * mask, or a tint table whose length disagrees with the palette. Null
     * is a bug upstream - the caller counts it as a far-field error.
     */
    public static byte[] encode(Shell shell) {
        if (shell == null || !encodable(shell)) {
            return null;
        }
        ByteArrayOutputStream out =
                new ByteArrayOutputStream(64 + shell.cellCount * 2);
        out.write(MAGIC[0]);
        out.write(MAGIC[1]);
        out.write(MAGIC[2]);
        out.write(MAGIC[3]);
        Deflater deflater = new Deflater(6);
        try {
            try (DataOutputStream data = new DataOutputStream(
                    new DeflaterOutputStream(out, deflater, 4096))) {
                data.writeByte(FORMAT_VERSION);
                // v5: the extraction-rule signature, written first so a
                // reader can reject a stale record before parsing one
                // palette string.
                data.writeInt(shell.saveSignature);
                data.writeByte(shell.tier);
                data.writeByte(shell.bandOffset);
                data.writeShort(shell.minY);
                data.writeInt(shell.cellCount);
                data.writeShort(shell.palette.length);
                writePalette(data, shell);
                // v2/v3: the tint table, behind a presence byte so a shell
                // built without a world round-trips as tint-less rather
                // than as an all-white table (the two mean different
                // things to the mesher's fallback). In v3 the byte is the
                // grid SIDE, whose 0 and 1 are v2's 0 and 1 verbatim.
                int[] tints = shell.paletteTint;
                int side = tints == null ? TINT_SIDE_NONE : shell.tintGridSide;
                data.writeByte(side);
                if (tints != null) {
                    int nodes = side * side;
                    for (int e = 0; e < shell.palette.length; e++) {
                        int base = e * nodes;
                        if (side > TINT_SIDE_SINGLE) {
                            boolean uniform = true;
                            int first = tints[base];
                            for (int n = 1; n < nodes && uniform; n++) {
                                uniform = tints[base + n] == first;
                            }
                            data.writeByte(uniform ? 1 : 0);
                            if (uniform) {
                                writeRgb(data, first);
                                continue;
                            }
                        }
                        for (int n = 0; n < nodes; n++) {
                            writeRgb(data, tints[base + n]);
                        }
                    }
                }
                // v3: the per-cell light plane's presence. The plane
                // itself trails the cells (class javadoc: contiguous is
                // what makes it compress).
                byte[] light = shell.cellLight;
                data.writeByte(light == null ? 0 : 1);
                for (int i = 0; i < shell.cellCount; i++) {
                    int cell = shell.cells[i];
                    data.writeShort(cell & 0xFFFF);
                    data.writeByte(cellPaletteIndex(cell));
                    data.writeByte(cellFaceMask(cell));
                }
                if (light != null) {
                    data.write(light, 0, shell.cellCount);
                }
            }
        } catch (IOException impossible) {
            // ByteArrayOutputStream cannot raise IO; keep the no-throw
            // contract anyway.
            return null;
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    /**
     * The format-4 two-level palette: distinct names with their state-space
     * signatures, then one row per ENTRY naming which of them it uses and
     * which state index inside it.
     *
     * <p>The name order is FIRST APPEARANCE in {@code shell.palette}, so an
     * encode/decode round trip reproduces the entry array exactly, including
     * which entries share a name. {@code encodable} has already checked that
     * entries sharing a name also share a signature, so taking the first
     * one's is not a choice, it is the only value there is.</p>
     */
    private static void writePalette(DataOutputStream data, Shell shell)
            throws IOException {
        String[] palette = shell.palette;
        // First-appearance name table. A palette is at most 256 entries
        // (MAX_PALETTE) so the quadratic scan is at worst 32k reference
        // compares on the IO thread, against an inflate and a zlib pass.
        String[] names = new String[palette.length];
        int[] nameSig = new int[palette.length];
        int[] nameOf = new int[palette.length];
        int nameCount = 0;
        for (int e = 0; e < palette.length; e++) {
            int found = -1;
            for (int n = 0; n < nameCount; n++) {
                if (names[n].equals(palette[e])) {
                    found = n;
                    break;
                }
            }
            if (found < 0) {
                found = nameCount;
                names[nameCount] = palette[e];
                nameSig[nameCount] = shell.stateSig(e) & 0xFFFF;
                nameCount++;
            }
            nameOf[e] = found;
        }
        data.writeShort(nameCount);
        for (int n = 0; n < nameCount; n++) {
            data.writeUTF(names[n]);
            data.writeShort(nameSig[n]);
        }
        for (int e = 0; e < palette.length; e++) {
            data.writeByte(nameOf[e]);
            int state = shell.stateIndex(e);
            writeVarInt(data, state == NO_STATE ? 0 : state + 1);
        }
    }

    /** Unsigned LEB128, 7 bits a byte, low group first. */
    private static void writeVarInt(DataOutputStream data, int value)
            throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            data.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        data.writeByte(v);
    }

    /**
     * Unsigned LEB128, bounded to five groups so a corrupt stream cannot
     * spin. Returns -1 for a malformed or over-long value; every caller
     * treats that as corruption.
     */
    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value < 0 ? -1 : value;
            }
        }
        return -1;
    }

    /** One 0xRRGGBB as three big-endian bytes. */
    private static void writeRgb(DataOutputStream data, int rgb) throws IOException {
        data.writeByte((rgb >>> 16) & 0xFF);
        data.writeByte((rgb >>> 8) & 0xFF);
        data.writeByte(rgb & 0xFF);
    }

    /**
     * Is this a side this codec can write and read back? The corner
     * grids ({@value #TINT_SIDE_COARSE}, {@value #TINT_SIDE_FINE},
     * {@value #TINT_SIDE_EXACT}) stay legal for READING records written
     * before pre19; only {@value #TINT_SIDE_SINGLE} and
     * {@value #TINT_SIDE_CELL} are produced now.
     */
    private static boolean legalTintSide(int side) {
        return side == TINT_SIDE_SINGLE || side == TINT_SIDE_COARSE
                || side == TINT_SIDE_FINE || side == TINT_SIDE_CELL
                || side == TINT_SIDE_EXACT;
    }

    private static boolean encodable(Shell shell) {
        if (shell.formatVersion != FORMAT_VERSION) {
            return false;
        }
        if (shell.tier < TIER_GENERATED || shell.tier > TIER_VISITED) {
            return false;
        }
        if (shell.bandOffset != FULL_SHELL && shell.bandOffset < 1) {
            return false;
        }
        if (shell.minY < MIN_WORLD_Y || shell.minY > MAX_WORLD_Y) {
            return false;
        }
        if (shell.cellCount < 0 || shell.cellCount > MAX_CELLS) {
            return false;
        }
        if (shell.cells == null || shell.cells.length < shell.cellCount) {
            return false;
        }
        String[] palette = shell.palette;
        if (palette == null || palette.length > MAX_PALETTE
                || (shell.cellCount > 0 && palette.length == 0)) {
            return false;
        }
        for (String name : palette) {
            if (name == null) {
                return false;
            }
        }
        int[] states = shell.paletteState;
        if (states != null) {
            int[] sigs = shell.paletteStateSig;
            if (states.length != palette.length || sigs == null
                    || sigs.length != palette.length) {
                return false;
            }
            for (int e = 0; e < palette.length; e++) {
                if (states[e] != NO_STATE
                        && (states[e] < 0 || states[e] > MAX_STATE_INDEX)) {
                    return false;
                }
                if (sigs[e] != (sigs[e] & 0xFFFF)) {
                    return false;
                }
                // One name, one state space: the disk table stores the
                // signature once per NAME, so two entries that disagree
                // could not be read back as they were written.
                for (int f = 0; f < e; f++) {
                    if (palette[f].equals(palette[e]) && sigs[f] != sigs[e]) {
                        return false;
                    }
                }
            }
        }
        if (shell.paletteTint != null) {
            int side = shell.tintGridSide;
            if (!legalTintSide(side)
                    || shell.paletteTint.length != palette.length * side * side) {
                return false;
            }
        }
        if (shell.cellLight != null && shell.cellLight.length < shell.cellCount) {
            return false;
        }
        for (int i = 0; i < shell.cellCount; i++) {
            int cell = shell.cells[i];
            if (cellPaletteIndex(cell) >= palette.length) {
                return false;
            }
            int mask = cellFaceMask(cell);
            if (mask == 0 || mask > FACE_MASK_ALL) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Decode
    // ------------------------------------------------------------------

    /**
     * Parse an on-disk record. Returns the shell, or null for ANY
     * malformed input - this method never throws. The caller counts the
     * null as a far-field error when the bytes came from the store (a
     * corrupt record must degrade to "no cached shell here", never to an
     * exception that could reach the coverage guard's neighborhood -
     * FARFIELD-CODEBASE-SEAM.md landmine L4).
     *
     * <p>Versions {@value #MIN_READ_VERSION}..{@value #FORMAT_VERSION} are
     * accepted, and an old one is UPGRADED rather than rejected. A version-1
     * record decodes with {@code paletteTint == null}; a version-1, -2 or -3
     * record decodes with {@code paletteState == null}, i.e. every entry
     * drawing its block's DEFAULT state, which is exactly what those records
     * were written to mean. The returned {@code formatVersion} field says
     * which, so a caller can tell. Rejecting old records would have wiped
     * every player's cache and, worse, run every one of those reads through
     * the store's error counter; an upgrade costs one branch and leaves the
     * horizon drawable while the cache refreshes on re-visit.</p>
     *
     * <p>This overload also tells the shell where in the world it came
     * from, which the format does not and must not carry ({@link
     * #ORIGIN_UNKNOWN}). It is what lets the mesher reproduce vanilla's
     * per-POSITION appearance inputs, and it costs one small object per
     * read.</p>
     */
    public static Shell decode(byte[] record, int chunkX, int chunkZ) {
        Shell shell = decode(record);
        return shell == null ? null : shell.withOrigin(chunkX, chunkZ);
    }

    /**
     * As {@link #decode(byte[])}, without the world position. Kept for
     * callers (and tests) that have bytes and nothing else; the shell it
     * returns draws exactly as every shell drew before the origin existed
     * ({@link #ORIGIN_UNKNOWN}).
     */
    public static Shell decode(byte[] record) {
        if (record == null || record.length < MAGIC.length + 1) {
            return null;
        }
        if (record[0] != MAGIC[0] || record[1] != MAGIC[1]
                || record[2] != MAGIC[2] || record[3] != MAGIC[3]) {
            return null;
        }
        byte[] payload = inflateBounded(record, MAGIC.length,
                record.length - MAGIC.length);
        if (payload == null) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            int version = in.readUnsignedByte();
            if (version < MIN_READ_VERSION || version > FORMAT_VERSION) {
                return null;
            }
            // v5: any int is legal here - the codec carries the signature
            // and does not judge it. A pre-v5 record has none, and the 0
            // it decodes to is told from a real 0 by the version, through
            // Shell.hasSaveSignature.
            int saveSignature = version >= SIGNATURE_MIN_VERSION
                    ? in.readInt() : 0;
            int tier = in.readUnsignedByte();
            if (tier > TIER_VISITED) {
                return null;
            }
            byte bandOffset = in.readByte();
            if (bandOffset != FULL_SHELL && bandOffset < 1) {
                return null;
            }
            short minY = in.readShort();
            if (minY < MIN_WORLD_Y || minY > MAX_WORLD_Y) {
                return null;
            }
            int cellCount = in.readInt();
            if (cellCount < 0 || cellCount > MAX_CELLS) {
                return null;
            }
            int paletteCount = in.readUnsignedShort();
            if (paletteCount > MAX_PALETTE
                    || (cellCount > 0 && paletteCount == 0)) {
                return null;
            }
            String[] palette = new String[paletteCount];
            int[] paletteState = null;
            int[] paletteStateSig = null;
            if (version < 4) {
                for (int i = 0; i < paletteCount; i++) {
                    palette[i] = in.readUTF();
                }
            } else {
                int nameCount = in.readUnsignedShort();
                if (nameCount > paletteCount) {
                    return null;
                }
                String[] names = new String[nameCount];
                int[] sigs = new int[nameCount];
                for (int n = 0; n < nameCount; n++) {
                    names[n] = in.readUTF();
                    sigs[n] = in.readUnsignedShort();
                }
                paletteState = new int[paletteCount];
                paletteStateSig = new int[paletteCount];
                boolean anyState = false;
                for (int e = 0; e < paletteCount; e++) {
                    int nameIndex = in.readUnsignedByte();
                    if (nameIndex >= nameCount) {
                        return null;
                    }
                    int state = readVarInt(in);
                    if (state < 0 || state > MAX_STATE_INDEX + 1) {
                        return null;
                    }
                    palette[e] = names[nameIndex];
                    paletteState[e] = state == 0 ? NO_STATE : state - 1;
                    paletteStateSig[e] = sigs[nameIndex];
                    anyState |= state != 0;
                }
                if (!anyState) {
                    // A format-4 record that stored no state at all (a
                    // producer with no client, or a shell built by hand)
                    // decodes to the SAME shape a format-3 one does, so
                    // there is one representation of "no states" and every
                    // reader downstream has one branch rather than two.
                    paletteState = null;
                    paletteStateSig = null;
                }
            }
            int[] paletteTint = null;
            int tintSide = TINT_SIDE_NONE;
            boolean hasLight = false;
            if (version >= 2) {
                // Version 2 wrote 0 or 1 here; version 3 writes the grid
                // SIDE, and its 0 and 1 carry version 2's meanings
                // unchanged, so one read serves both.
                int declared = in.readUnsignedByte();
                if (declared != TINT_SIDE_NONE && !legalTintSide(declared)) {
                    return null; // reserved values are corruption, not future
                }
                if (version == 2 && declared > TINT_SIDE_SINGLE) {
                    return null; // a v2 record cannot carry a grid
                }
                tintSide = declared;
                if (tintSide != TINT_SIDE_NONE) {
                    int nodes = tintSide * tintSide;
                    paletteTint = new int[paletteCount * nodes];
                    // Every entry uniform means the record's whole colour
                    // field is one colour per entry, whatever side it was
                    // written at - and then side 1 is the SAME field in
                    // 1/256th of the memory. It is not a lossy collapse:
                    // the mesher's side <= 1 branch returns node (0,0),
                    // which is the value every node held. This is what
                    // keeps the pre19 cell field free for the single-biome
                    // records that are the overwhelming majority - without
                    // it a resident 8.4-entry shell would carry 8.6 KB of
                    // identical ints.
                    boolean allUniform = true;
                    for (int e = 0; e < paletteCount; e++) {
                        int base = e * nodes;
                        boolean uniform = tintSide == TINT_SIDE_SINGLE;
                        if (!uniform) {
                            int flag = in.readUnsignedByte();
                            if (flag > 1) {
                                return null;
                            }
                            uniform = flag == 1;
                        }
                        allUniform &= uniform;
                        if (uniform) {
                            int rgb = readRgb(in);
                            for (int n = 0; n < nodes; n++) {
                                paletteTint[base + n] = rgb;
                            }
                        } else {
                            for (int n = 0; n < nodes; n++) {
                                paletteTint[base + n] = readRgb(in);
                            }
                        }
                    }
                    if (allUniform && tintSide != TINT_SIDE_SINGLE) {
                        int[] collapsed = new int[paletteCount];
                        for (int e = 0; e < paletteCount; e++) {
                            collapsed[e] = paletteTint[e * nodes];
                        }
                        paletteTint = collapsed;
                        tintSide = TINT_SIDE_SINGLE;
                    }
                }
            }
            if (version >= 3) {
                int lightPresent = in.readUnsignedByte();
                if (lightPresent > 1) {
                    return null;
                }
                hasLight = lightPresent == 1;
            }
            int[] cells = new int[cellCount];
            for (int i = 0; i < cellCount; i++) {
                int pos = in.readUnsignedShort();
                int paletteIndex = in.readUnsignedByte();
                if (paletteIndex >= paletteCount) {
                    return null;
                }
                int mask = in.readUnsignedByte();
                if (mask == 0 || mask > FACE_MASK_ALL) {
                    return null;
                }
                cells[i] = (mask << 24) | (paletteIndex << 16) | pos;
            }
            byte[] cellLight = null;
            if (hasLight) {
                cellLight = new byte[cellCount];
                in.readFully(cellLight);
            }
            if (in.read() != -1) {
                // Trailing bytes after the last cell: corrupt.
                return null;
            }
            return new Shell(cellCount, cells, palette, paletteState,
                    paletteStateSig, paletteTint, tintSide, cellLight,
                    (byte) tier, (byte) version, bandOffset, minY,
                    ORIGIN_UNKNOWN, ORIGIN_UNKNOWN, saveSignature);
        } catch (IOException malformed) {
            // EOFException from a short payload, UTFDataFormatException
            // from a bad palette string - all corrupt-record shapes.
            return null;
        }
    }

    /** Three big-endian bytes back to one 0xRRGGBB. */
    private static int readRgb(DataInputStream in) throws IOException {
        return (in.readUnsignedByte() << 16)
                | (in.readUnsignedByte() << 8)
                | in.readUnsignedByte();
    }

    /**
     * Inflate one whole zlib stream from {@code src[off..off+len)} with a
     * hard output ceiling. Returns null on bad data, a truncated stream,
     * trailing bytes after the stream, or output past
     * {@link #MAX_PAYLOAD_BYTES}.
     */
    private static byte[] inflateBounded(byte[] src, int off, int len) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(src, off, len);
            // long math: a corrupt multi-hundred-MB length must not
            // overflow into a negative stream size and throw.
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    (int) Math.max(64L, Math.min((long) len * 4L, 1L << 16)));
            byte[] buffer = new byte[8192];
            int total = 0;
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) {
                    if (inflater.finished()) {
                        break;
                    }
                    // Needs more input (truncated) or a preset dictionary
                    // (we never use one): corrupt either way.
                    return null;
                }
                total += n;
                if (total > MAX_PAYLOAD_BYTES) {
                    return null;
                }
                out.write(buffer, 0, n);
            }
            if (inflater.getRemaining() != 0) {
                // Bytes after the zlib stream end: corrupt.
                return null;
            }
            return out.toByteArray();
        } catch (DataFormatException corrupt) {
            return null;
        } finally {
            inflater.end();
        }
    }
}
