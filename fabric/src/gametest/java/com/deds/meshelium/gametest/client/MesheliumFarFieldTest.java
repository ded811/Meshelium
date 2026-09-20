/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Far-field wave W4: the legs that prove pre1 works, and the one leg
 * that proves it costs nothing when it is off.
 */
package com.deds.meshelium.gametest.client;

import com.deds.meshelium.farfield.FarField;
import com.deds.meshelium.farfield.FarFieldConfig;
import com.deds.meshelium.farfield.extract.ExtractDispatch;
import com.deds.meshelium.farfield.extract.ShellExtractor;
import com.deds.meshelium.farfield.mesh.ShellMesher;
import com.deds.meshelium.farfield.mesh.SpriteUvResolver;
import com.deds.meshelium.farfield.store.ShellCodec;
import com.deds.meshelium.gui.MesheliumFarFieldScreen;
import com.deds.meshelium.terrain.host.TerrainResidency;
import com.deds.meshelium.vk.TerrainDrawer;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The far field's acceptance suite (docs/unreleased/farfield/FARFIELD-WAVES.md, pre1 W4).
 *
 * <h2>Two runs, eight legs, and the order matters</h2>
 * <ol>
 *   <li><b>Store round trip</b> (every run, no world, no GPU): a
 *       synthetic shell through {@code ShellCodec.encode/decode} and back
 *       field for field, plus the corruption posture that keeps a bad
 *       record from ever becoming an exception.</li>
 *   <li><b>Zero cost off</b> (every run where the far field was never
 *       enabled): a full world load and hundreds of pumps must leave the
 *       facade unarmed, every counter zero, no folder on disk, and
 *       {@code FarFieldResidency} and {@code SpriteUvResolver} NOT
 *       LOADED. Runs FIRST, before anything here can arm anything.</li>
 *   <li><b>Extraction</b> (far-armed run): walking away from chunks must
 *       push them into the store, on disk, with bytes.</li>
 *   <li><b>Write-ack attribution</b> (far-armed run, save M1): flood the
 *       write queue past its cap; every submit is acked, the eviction's
 *       VICTIMS - not the submitter - are the columns re-armed, and the
 *       newest record survives on disk.</li>
 *   <li><b>The rd storm loses nothing</b> (far-armed run, save M5,
 *       gametest (a)): bulk-load, drop the render distance while the
 *       new ring is still dirty, and the E6 capture + pin worker must
 *       land EVERY tracked column on disk - the whole loss ledger and
 *       both capture-bound counters asserted at zero, the store read
 *       back for the entire recorded window, gold witnesses
 *       decoded.</li>
 *   <li><b>Far draw</b> (far-armed run): the same walk revisited, camera
 *       pinned on the horizon, far sections resident and admitted, and
 *       three screenshots for the coordinator to read.</li>
 *   <li><b>Replace in place</b> (far-armed run, SEAM step 2 leg A): a
 *       fresher shell submitted for a DRAWN far column swaps its
 *       geometry atomically - the column never leaves the draw set
 *       across the exchange (polled between ticks), the manifest holds
 *       no duplicate sections afterwards, and the ledger counted the
 *       swap with zero illegal transitions.</li>
 *   <li><b>Both directions</b> (far-armed run): the radius slider must
 *       act DOWN as well as up.</li>
 *   <li><b>Delete the cache</b> (far-armed run, LAST because it
 *       destroys what the legs above needed): the real button on the
 *       real screen, two clicks, against open region-file handles, and
 *       the store has to come back afterwards.</li>
 * </ol>
 *
 * <h2>How to run the far-armed legs</h2>
 * <pre>
 *   ./gradlew runClientGameTest -Pmeshelium.backend=vulkan \
 *       -Pmeshelium.terrain -Pmeshelium.rd=48 -Pmeshelium.farfield
 * </pre>
 * {@code -Pmeshelium.farfield} sets ONLY {@code meshelium.test.farfield};
 * it does not arm the master switch at boot. This class arms it itself,
 * around its own legs, and clears it in a finally. That is deliberate:
 * an armed far field changes what the OTHER four test classes see (far
 * shells set the same RegionStore retained bit as the wave-11 horizon,
 * so the retained-mask probe in MesheliumTerrainDrawTest is legitimately
 * non-zero), and this class runs last in the entrypoint list precisely so
 * nothing downstream inherits an armed far field. It also means the
 * zero-cost leg is valid in BOTH runs: at the point it executes, nothing
 * in this JVM has ever turned the far field on.
 *
 * <h2>The world is the harness superflat, on purpose</h2>
 * <p>{@code worldBuilder().create()} gives the fabric-consistent FLAT
 * preset at seed 1. That is a blind spot for MERGE tests (the sibling
 * lesson: every face is a top face) and exactly right here: the legs
 * measure whether shells are extracted, stored, read back, meshed and
 * admitted, and a superflat chunk generates in milliseconds, which is
 * what makes a three-stop walk affordable inside a gametest. The far
 * ring it produces is a flat sheet of grass tops, which reads clearly in
 * a screenshot against the sky.</p>
 */
public final class MesheliumFarFieldTest implements FabricClientGameTest {

    /** Near render distance for the far legs: the far ring starts at rd+1. */
    private static final int FAR_RD = 8;
    /**
     * Save leg (a)'s storm: bulk-load at this render distance, then drop
     * to {@link #STORM_RD_LOW} while the new ring is still dirty - the
     * design's own leg parameters (FARFIELD-SAVE-DESIGN.md section 10:
     * "set client render distance 16 ... set render distance 4"), which
     * exercise the integrated server's SetChunkCacheRadius ->
     * updateViewRadius path, i.e. the REAL repro-(a) mechanism, at a
     * scale the suite can drain inside its timeout. The rd-120 case is
     * the same machinery times a constant; its bound is asserted here
     * through the same zero-valued skip counters.
     */
    private static final int STORM_RD_HIGH = 16;
    /** See {@link #STORM_RD_HIGH}. */
    private static final int STORM_RD_LOW = 4;
    /** The radius the draw leg runs at, chunks. Comfortably inside the */
    /** projection far plane at rd 8 (4*rd = 32) so nothing is clamped. */
    private static final int FAR_L1_HIGH = 24;
    /** The radius the shrink half of the both-directions leg drops to. */
    private static final int FAR_L1_LOW = 12;

    /**
     * The walk. rd 8 holds a 17x17 chunk window, so each stop is 17
     * chunks past the last: no overlap, therefore every chunk of the
     * previous window is abandoned and extracted. Stop 2 is what caches
     * the ring the draw leg looks at (chunks 9..25 east of spawn).
     */
    private static final int WALK_STOP_1_X = 272; // chunk 17
    private static final int WALK_STOP_2_X = 544; // chunk 34
    /** Home: chunk 0, the pose every screenshot is taken from. */
    private static final int HOME_X = 8;
    private static final int HOME_Z = 8;
    /**
     * Blocks above the ground for the evidence shots. Looking at a
     * superflat plane from standing height puts the whole far ring
     * edge on and a few pixels tall; 40 blocks up with a 12 degree
     * downward pitch puts the rd-8 near horizon (128 blocks) low in
     * frame and the far ring (up to 384 blocks) across the middle of
     * it, which is where a missing far field is obvious.
     */
    private static final double CAMERA_HEIGHT = 40.0;
    /** Downward pitch for the evidence shots, degrees. */
    private static final String CAMERA_PITCH = "12";

    private static final int DRAW_TIMEOUT_TICKS = 1200;
    private static final int FAR_TIMEOUT_TICKS = 2400;
    private static final int RD_TIMEOUT_TICKS = 1200;

    private static final String RESIDENCY_CLASS =
            "com.deds.meshelium.farfield.FarFieldResidency";
    private static final String SPRITE_RESOLVER_CLASS =
            "com.deds.meshelium.farfield.mesh.SpriteUvResolver";

    @Override
    public void runTest(ClientGameTestContext context) {
        // Leg 1: pure CPU, every backend, no world. First because a
        // broken codec makes every later leg's failure unreadable.
        assertShellCodecRoundTrip();
        assertShellCodecRefusesGarbage();
        assertSaveSignatureSurvivesTheRecord();

        // Leg 2: THE INVARIANT. Must execute before anything here arms
        // the far field, and it asserts a class-loading fact that any
        // later leg would destroy.
        assertZeroCostOff(context);

        // Leg 2b: the two water-light paths must be ONE function (the
        // owner's pre13 O1). Pure CPU, both backends, no world, no GPU -
        // but it runs AFTER the zero-cost leg on purpose, because it
        // loads SpriteUvResolver and that leg asserts the class has
        // never been touched.
        assertOceanLightIsPathIndependent(context);

        // Leg 2c: R4's POSITIVE proof. The refusal counters can only ever
        // say "nothing complained"; this says the owner's own three
        // blocks enumerate and that two positions really do draw two
        // different looks. Same placement rationale as 2b - it needs the
        // model manager, so it runs after the zero-cost leg.
        assertOwnersBlocksRotatePerPosition(context);

        // Leg 2d: THE GREEN WATER. Cold, world-free, deterministic: the
        // extractor's own palette driven over a synthetic column whose
        // water entry has cells in two biome layers. Runs here for the
        // same reason as 2b and 2c - it needs BlockColors and the fluid
        // model set, which the zero-cost leg must not see loaded.
        assertSurfaceCellWearsTheSurfaceBiome(context);

        // Leg 2e: THE BUDGET RULE (T2). Pure arithmetic over the public
        // rule function - no world, no GPU, no client state, no clock -
        // so it runs on every backend and every run. It sits here rather
        // than with the other cold legs for one reason: touching
        // ExtractDispatch at all loads it, and the zero-cost leg above
        // asserts a class-loading fact that must be taken first.
        assertBudgetIsAFractionOfTheFrame();

        boolean vulkanRun = "vulkan".equalsIgnoreCase(
                System.getProperty("meshelium.test.expectBackend", "opengl"));
        boolean terrainRun = Boolean.getBoolean("meshelium.terrainDraw");
        boolean farRun = Boolean.getBoolean("meshelium.test.farfield");
        if (!vulkanRun || !terrainRun || !farRun) {
            // Nothing below can mean anything without a live drawer and
            // an explicit opt-in: the far field draws through the
            // retained paths, which only exist on the terrain run.
            return;
        }
        assertFarArmedLegs(context);
    }

    // ------------------------------------------------------------------
    // Leg 1: the store round trip (cold, no world, no GPU)
    // ------------------------------------------------------------------

    /**
     * A synthetic shell in, the same shell out. Deliberately not a
     * trivial one: 512 cells spread over the full 0..15 x/z lattice and
     * a 200-deep yRel span, three palette entries exercised by index,
     * every legal face-mask value cycled through, a negative
     * {@code minY} (the real overworld base), the surface-band offset
     * carried in the header, and a cells array LONGER than
     * {@code cellCount} because {@link ShellCodec.Shell}'s contract
     * allows an extractor to hand over its scratch buffer untrimmed.
     *
     * <p><b>What makes this fail:</b> any bit-packing change that moves
     * a field (x/z swap, yRel width, palette or mask shift), a header
     * field written and read in a different order or width, the padded
     * tail leaking into the record (cellCount honoured on the way out
     * but not on the way in), a palette string mangled by the modified
     * UTF-8 round trip, the band offset or minY dropped, or a
     * compression change that loses the last block. Every one of those
     * silently corrupts a horizon that only shows up chunks away from
     * the player, which is why it is tested cold.</p>
     */
    private static void assertShellCodecRoundTrip() {
        final int cellCount = 512;
        final int padding = 37; // the untrimmed-buffer case
        int[] cells = new int[cellCount + padding];
        String[] palette = {
            "minecraft:stone", "minecraft:grass_block", "minecraft:water",
        };
        for (int i = 0; i < cellCount; i++) {
            int x = i & 0xF;
            int z = (i >>> 4) & 0xF;
            int yRel = (i * 7) % 200;
            int paletteIndex = i % palette.length;
            int faceMask = 1 + (i % 63); // 1..63, never the illegal 0
            cells[i] = ShellCodec.packCell(x, yRel, z, paletteIndex, faceMask);
        }
        // Poison the padding: it must never reach the record.
        Arrays.fill(cells, cellCount, cells.length, 0x7FFFFFFF);

        ShellCodec.Shell original = new ShellCodec.Shell(cellCount, cells, palette,
                (byte) ShellCodec.TIER_VISITED, ShellCodec.FORMAT_VERSION,
                (byte) 8, (short) -64);
        byte[] record = ShellCodec.encode(original);
        if (record == null) {
            throw new AssertionError("ShellCodec.encode refused a legal shell");
        }
        ShellCodec.Shell back = ShellCodec.decode(record);
        if (back == null) {
            throw new AssertionError("ShellCodec.decode returned null for a record its own "
                    + "encode() had just produced (" + record.length + " bytes)");
        }
        if (back.cellCount != original.cellCount) {
            throw new AssertionError("cellCount " + back.cellCount + " != "
                    + original.cellCount);
        }
        if (back.tier != original.tier || back.formatVersion != original.formatVersion
                || back.bandOffset != original.bandOffset || back.minY != original.minY) {
            throw new AssertionError("header lost across the round trip: tier " + back.tier
                    + " version " + back.formatVersion + " bandOffset " + back.bandOffset
                    + " minY " + back.minY);
        }
        if (!Arrays.equals(back.palette, palette)) {
            throw new AssertionError("palette lost across the round trip: "
                    + Arrays.toString(back.palette));
        }
        if (back.cells.length != cellCount) {
            throw new AssertionError("decode returned " + back.cells.length + " cells for a "
                    + cellCount + "-cell record - the padded tail leaked into the record");
        }
        for (int i = 0; i < cellCount; i++) {
            if (back.cells[i] != cells[i]) {
                throw new AssertionError("cell " + i + " changed: packed 0x"
                        + Integer.toHexString(cells[i]) + " came back 0x"
                        + Integer.toHexString(back.cells[i])
                        + " (x " + ShellCodec.cellX(cells[i]) + "/"
                        + ShellCodec.cellX(back.cells[i])
                        + " z " + ShellCodec.cellZ(cells[i]) + "/"
                        + ShellCodec.cellZ(back.cells[i])
                        + " yRel " + ShellCodec.cellYRel(cells[i]) + "/"
                        + ShellCodec.cellYRel(back.cells[i])
                        + " pal " + ShellCodec.cellPaletteIndex(cells[i]) + "/"
                        + ShellCodec.cellPaletteIndex(back.cells[i])
                        + " mask " + ShellCodec.cellFaceMask(cells[i]) + "/"
                        + ShellCodec.cellFaceMask(back.cells[i]) + ")");
            }
        }
        // An empty shell is legal and must survive too (a chunk with
        // nothing above the band produces one and the walker has to be
        // able to tell it apart from a corrupt record).
        ShellCodec.Shell empty = new ShellCodec.Shell(0, new int[0], new String[0],
                (byte) ShellCodec.TIER_GENERATED, ShellCodec.FORMAT_VERSION,
                ShellCodec.FULL_SHELL, (short) 0);
        byte[] emptyRecord = ShellCodec.encode(empty);
        if (emptyRecord == null || ShellCodec.decode(emptyRecord) == null) {
            throw new AssertionError("an empty full-shell record did not round trip");
        }
        assertStatePaletteRoundTrip();
        assertTintCellFieldRoundTrip();
    }

    /**
     * pre19's S1: the 16x16 CELL FIELD, its all-uniform collapse, and the
     * legacy corner side that records on disk still carry. Cold, no world.
     *
     * <p>Three legs, each of which silently wrecks distant colour if it
     * breaks:
     * <ul>
     *   <li><b>a non-uniform field survives verbatim.</b> The whole point
     *       of the model is that node {@code (x, z)} IS vanilla's answer
     *       for that block; a codec that reordered, truncated or
     *       re-interpolated it would look like a slightly-wrong biome
     *       ramp, which is exactly the class of defect four playtests
     *       failed to pin down;</li>
     *   <li><b>an all-uniform field collapses to side 1 and answers the
     *       same colour at every node.</b> This is what keeps the cell
     *       field free for the single-biome records that are most of a
     *       world - without it every resident shell would carry 8.6 KB
     *       of identical ints - and a collapse that changed an ANSWER
     *       would repaint whole chunks;</li>
     *   <li><b>{@code TINT_SIDE_EXACT} still decodes.</b> pre18 wrote it;
     *       nothing writes it now; a reader that rejected it would count
     *       every pre19-era record on disk as corrupt.</li>
     * </ul>
     */
    private static void assertTintCellFieldRoundTrip() {
        final int side = ShellCodec.TINT_SIDE_CELL;
        final int nodes = side * side;
        String[] palette = {"minecraft:grass_block", "minecraft:water"};
        int[] cells = {ShellCodec.packCell(0, 0, 0, 0, 2),
                ShellCodec.packCell(1, 0, 1, 1, 2)};

        // Entry 0: a ramp, one step per block, so any reordering shows.
        // Entry 1: one colour at all 256 nodes.
        int[] tint = new int[palette.length * nodes];
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                tint[gz * side + gx] = (gx << 16) | (gz << 8) | 0x40;
                tint[nodes + gz * side + gx] = 0x617B64;
            }
        }
        ShellCodec.Shell mixed = new ShellCodec.Shell(cells.length, cells, palette,
                tint, side, null, (byte) ShellCodec.TIER_VISITED,
                ShellCodec.FORMAT_VERSION, (byte) 8, (short) -64);
        byte[] record = ShellCodec.encode(mixed);
        if (record == null) {
            throw new AssertionError("encode refused a legal cell-field shell");
        }
        ShellCodec.Shell back = ShellCodec.decode(record);
        if (back == null || back.tintGridSide != side) {
            throw new AssertionError("a mixed cell field decoded at side "
                    + (back == null ? "null" : back.tintGridSide)
                    + ", expected " + side);
        }
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                int want = (gx << 16) | (gz << 8) | 0x40;
                if (back.tintNode(0, gx, gz) != want) {
                    throw new AssertionError("cell field node (" + gx + "," + gz
                            + ") came back 0x"
                            + Integer.toHexString(back.tintNode(0, gx, gz))
                            + ", wrote 0x" + Integer.toHexString(want));
                }
                if (back.tintNode(1, gx, gz) != 0x617B64) {
                    throw new AssertionError("the uniform entry's node ("
                            + gx + "," + gz + ") came back 0x"
                            + Integer.toHexString(back.tintNode(1, gx, gz)));
                }
            }
        }

        // All entries uniform: the decoder must collapse to side 1 and
        // still answer the same colour for every block column.
        int[] flat = new int[palette.length * nodes];
        for (int e = 0; e < palette.length; e++) {
            Arrays.fill(flat, e * nodes, (e + 1) * nodes, e == 0 ? 0x91BD59 : 0x3F76E4);
        }
        ShellCodec.Shell allFlat = new ShellCodec.Shell(cells.length, cells, palette,
                flat, side, null, (byte) ShellCodec.TIER_VISITED,
                ShellCodec.FORMAT_VERSION, (byte) 8, (short) -64);
        ShellCodec.Shell flatBack = ShellCodec.decode(ShellCodec.encode(allFlat));
        if (flatBack == null || flatBack.tintGridSide != ShellCodec.TINT_SIDE_SINGLE
                || flatBack.paletteTint.length != palette.length) {
            throw new AssertionError("an all-uniform cell field did not collapse: side "
                    + (flatBack == null ? "null" : flatBack.tintGridSide)
                    + ", table " + (flatBack == null ? -1 : flatBack.paletteTint.length)
                    + " ints for " + palette.length + " entries");
        }
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                if (flatBack.tintNode(0, gx, gz) != 0x91BD59
                        || flatBack.tintNode(1, gx, gz) != 0x3F76E4) {
                    throw new AssertionError("the collapse changed an answer at ("
                            + gx + "," + gz + "): 0x"
                            + Integer.toHexString(flatBack.tintNode(0, gx, gz)) + " / 0x"
                            + Integer.toHexString(flatBack.tintNode(1, gx, gz)));
                }
            }
        }

        // pre18's corner side: never written again, always readable.
        int legacy = ShellCodec.TINT_SIDE_EXACT;
        int[] corner = new int[palette.length * legacy * legacy];
        for (int i = 0; i < corner.length; i++) {
            corner[i] = 0x102030 + (i & 0xFF);
        }
        ShellCodec.Shell old = new ShellCodec.Shell(cells.length, cells, palette,
                corner, legacy, null, (byte) ShellCodec.TIER_VISITED,
                ShellCodec.FORMAT_VERSION, (byte) 8, (short) -64);
        ShellCodec.Shell oldBack = ShellCodec.decode(ShellCodec.encode(old));
        if (oldBack == null || oldBack.tintGridSide != legacy
                || oldBack.tintNode(0, 5, 7) != corner[7 * legacy + 5]) {
            throw new AssertionError("a pre18 corner-grid record no longer round trips");
        }
    }

    /**
     * (F) THE RECORD SAYS WHICH RULES EXTRACTED IT, and a record that
     * does not say must never pass for current.
     *
     * <h2>What this is the alarm for</h2>
     * {@code FarFieldConfig.farSaveSignature()} existed from pre8 and was
     * compared only against a static in {@code FarFieldResidency} that is
     * reset on every world era. It was never written to a record, a
     * region header or the store - so across a restart it detected
     * nothing, and {@code BAND_RULE_REVISION} had been bumped four times
     * without causing one record on disk to be re-extracted. Every
     * extraction-time fix this project shipped reached the WRITER and no
     * existing record. That is the whole reason three consecutive correct
     * colour fixes changed nothing the owner could see.
     *
     * <p>Three things have to hold, and the third is the one that is easy
     * to get subtly wrong: a signature must survive the round trip; a
     * DIFFERENT signature must not match; and a record that carries no
     * signature at all - every record any build before format 5 wrote -
     * must answer FALSE rather than "probably fine". Signature 0 is a
     * legal value, so "absent" cannot be encoded as a sentinel and is
     * told from it by the format version alone; the last two assertions
     * below are exactly that distinction.</p>
     */
    private static void assertSaveSignatureSurvivesTheRecord() {
        int[] cells = { ShellCodec.packCell(0, 0, 0, 0, 1 << 1) };
        String[] palette = { "minecraft:stone" };
        ShellCodec.Shell plain = new ShellCodec.Shell(1, cells, palette,
                (byte) ShellCodec.TIER_VISITED, ShellCodec.FORMAT_VERSION,
                (byte) 8, (short) -64);
        int signature = 0x5A17C0DE;
        byte[] record = ShellCodec.encode(plain.withSaveSignature(signature));
        ShellCodec.Shell back = record == null ? null : ShellCodec.decode(record);
        if (back == null) {
            throw new AssertionError("a stamped shell no longer encodes or decodes");
        }
        if (back.saveSignature != signature) {
            throw new AssertionError("the save signature did not survive the record: "
                    + "wrote " + signature + ", read " + back.saveSignature
                    + ". Without it on disk, a BAND_RULE_REVISION bump reaches new "
                    + "terrain only, which is the pre20 hole verbatim");
        }
        if (!back.hasSaveSignature() || !back.signatureMatches(signature)) {
            throw new AssertionError("a freshly written record does not report itself "
                    + "as current, so every read would be treated as stale and the far "
                    + "field would never keep a single cached column");
        }
        if (back.signatureMatches(signature + 1)) {
            throw new AssertionError("a record matched a signature it was not written "
                    + "under: the staleness check cannot fire, and the store silently "
                    + "keeps old-vintage bytes forever");
        }
        // Signature ZERO is a legal value and must round trip as one -
        // not as "no signature".
        ShellCodec.Shell zero = ShellCodec.decode(
                ShellCodec.encode(plain.withSaveSignature(0)));
        if (zero == null || !zero.hasSaveSignature() || !zero.signatureMatches(0)
                || zero.signatureMatches(1)) {
            throw new AssertionError("signature 0 did not round trip as a real value; "
                    + "it is being confused with 'no signature stored'");
        }
        // A record from before format 5 carries none, and "cannot tell"
        // has to resolve to STALE.
        ShellCodec.Shell legacy = new ShellCodec.Shell(1, cells, palette,
                (byte) ShellCodec.TIER_VISITED,
                (byte) (ShellCodec.SIGNATURE_MIN_VERSION - 1), (byte) 8, (short) -64);
        if (legacy.hasSaveSignature() || legacy.signatureMatches(0)
                || legacy.signatureMatches(signature)) {
            throw new AssertionError("a pre-format-" + ShellCodec.SIGNATURE_MIN_VERSION
                    + " record claimed a signature. Every record every earlier build "
                    + "wrote carries none, and treating that as 'current' is precisely "
                    + "the assumption that let four band-rule bumps ship without "
                    + "re-extracting a byte");
        }
    }

    /**
     * Format 4's STATE-keyed palette, which the shell above does not
     * reach: everything there takes the all-{@code NO_STATE} fast exit,
     * so before this the two-level name/state table was executed by
     * nothing in either suite leg (the harness world is superflat, and a
     * superflat column is stone/dirt/grass in their default states).
     *
     * <p>Three paths, each one a silent horizon corrupter if it breaks:
     * <ul>
     *   <li><b>The shared name index.</b> Entries 0 and 1 are the same
     *       block name at different states, which is the whole point of
     *       splitting the table - the name is written once and the second
     *       entry must come back pointing at it, not at a copy and not at
     *       the wrong row;</li>
     *   <li><b>The varint continuation.</b> State 1295 is over 127, so
     *       {@code stateIndexPlus1} spills to a second byte. A stair has
     *       forty variants and a fluid sixteen, but a modded block with
     *       hundreds is ordinary, and a truncated varint would renumber
     *       every entry after it;</li>
     *   <li><b>The mixed record.</b> Entry 3 carries {@code NO_STATE}
     *       beside three that do not, so the "0 means no state" sentinel
     *       is exercised in the same table as real indices rather than
     *       only in a record where every entry is 0.</li>
     * </ul>
     *
     * <p>The signature array rides along because a mismatch there is how
     * a record written against a different modset falls back to default
     * states per block; it has to survive a clean round trip or the
     * fallback fires on records that are perfectly good.</p>
     */
    private static void assertStatePaletteRoundTrip() {
        String[] palette = {
            "minecraft:oak_stairs", "minecraft:oak_stairs",
            "minecraft:water", "minecraft:stone",
        };
        int[] states = {0, 1295, 7, ShellCodec.NO_STATE};
        int[] sigs = {0xBEEF, 0xBEEF, 0x1234, 0x0000};

        final int cellCount = 64;
        int[] cells = new int[cellCount];
        for (int i = 0; i < cellCount; i++) {
            cells[i] = ShellCodec.packCell(i & 0xF, i, (i >>> 4) & 0xF,
                    i % palette.length, 1 + (i % 63));
        }

        ShellCodec.Shell original = new ShellCodec.Shell(cellCount, cells, palette,
                states, sigs, null, 0, null,
                (byte) ShellCodec.TIER_VISITED, ShellCodec.FORMAT_VERSION,
                (byte) 8, (short) -64);
        byte[] record = ShellCodec.encode(original);
        if (record == null) {
            throw new AssertionError("ShellCodec.encode refused a legal state-keyed shell");
        }
        ShellCodec.Shell back = ShellCodec.decode(record);
        if (back == null) {
            throw new AssertionError("ShellCodec.decode returned null for a state-keyed "
                    + "record its own encode() had just produced (" + record.length
                    + " bytes)");
        }
        if (!Arrays.equals(back.palette, palette)) {
            throw new AssertionError("state-keyed palette names lost: "
                    + Arrays.toString(back.palette) + " != " + Arrays.toString(palette));
        }
        if (back.paletteState == null) {
            throw new AssertionError("decode dropped the state table entirely - a format-4 "
                    + "record came back as if it were format 3, so every block would draw "
                    + "its default state");
        }
        if (!Arrays.equals(back.paletteState, states)) {
            throw new AssertionError("state indices changed across the round trip: "
                    + Arrays.toString(back.paletteState) + " != " + Arrays.toString(states)
                    + " (the 1295 entry is the varint continuation, the NO_STATE entry is "
                    + "the sentinel, and entries 0 and 1 share a name)");
        }
        if (!Arrays.equals(back.paletteStateSig, sigs)) {
            throw new AssertionError("state signatures changed across the round trip: "
                    + Arrays.toString(back.paletteStateSig) + " != " + Arrays.toString(sigs)
                    + " - every block in this record would fall back to its default state");
        }
        for (int i = 0; i < cellCount; i++) {
            if (back.cells[i] != cells[i]) {
                throw new AssertionError("state-keyed cell " + i + " changed: packed 0x"
                        + Integer.toHexString(cells[i]) + " came back 0x"
                        + Integer.toHexString(back.cells[i]));
            }
        }
    }

    /**
     * The error posture: a malformed record decodes to null and NEVER
     * throws. That contract is load bearing twice over. It is what lets
     * {@code FarField} degrade a corrupt record to "no cached shell
     * here" and count it, and it is what keeps a cache bug away from the
     * four wave-8 coverage-guard drop counters (a throw escaping the
     * read path is how a far-field problem would turn the whole mod
     * passive).
     *
     * <p><b>What makes this fail:</b> any decode path that lets an
     * exception out (EOF on a short payload, a bad zlib checksum, a
     * palette index past the table, a UTF string with a bogus length),
     * or one that accepts damaged bytes and hands back a shell the
     * mesher would then read out of bounds. Both directions are checked:
     * "did not throw" alone would pass if decode returned a garbage
     * shell.</p>
     */
    private static void assertShellCodecRefusesGarbage() {
        int[] cells = { ShellCodec.packCell(1, 2, 3, 0, 0x3F) };
        ShellCodec.Shell shell = new ShellCodec.Shell(1, cells,
                new String[] { "minecraft:stone" }, (byte) ShellCodec.TIER_VISITED,
                ShellCodec.FORMAT_VERSION, (byte) 8, (short) -64);
        byte[] good = ShellCodec.encode(shell);
        if (good == null) {
            throw new AssertionError("encode refused the corruption leg's seed shell");
        }

        requireDecodesToNull(null, "null buffer");
        requireDecodesToNull(new byte[0], "empty buffer");
        requireDecodesToNull(new byte[] { 'M', 'F', 'F', '1' }, "magic only");
        requireDecodesToNull(new byte[] { 'X', 'X', 'X', 'X', 0, 0, 0, 0 },
                "foreign magic");

        byte[] truncated = Arrays.copyOf(good, Math.max(5, good.length / 2));
        requireDecodesToNull(truncated, "truncated to " + truncated.length + " of "
                + good.length + " bytes");

        byte[] badMagic = good.clone();
        badMagic[0] = 'Z';
        requireDecodesToNull(badMagic, "first magic byte flipped");

        byte[] flipped = good.clone();
        // Inside the deflate stream, past the magic: zlib's Adler32 must
        // catch it (or the inflater must starve), either way null.
        flipped[good.length - 3] ^= 0x5A;
        requireDecodesToNull(flipped, "payload byte flipped");

        byte[] trailing = Arrays.copyOf(good, good.length + 4);
        requireDecodesToNull(trailing, "four bytes appended after the zlib stream");

        // The encode side of the same posture: an unrepresentable shell
        // returns null rather than writing a record nothing can read.
        ShellCodec.Shell maskZero = new ShellCodec.Shell(1,
                new int[] { ShellCodec.packCell(0, 0, 0, 0, 0) },
                new String[] { "minecraft:stone" }, (byte) ShellCodec.TIER_VISITED,
                ShellCodec.FORMAT_VERSION, (byte) 8, (short) 0);
        if (ShellCodec.encode(maskZero) != null) {
            throw new AssertionError("encode accepted a cell with an empty face mask");
        }
        ShellCodec.Shell badPaletteIndex = new ShellCodec.Shell(1,
                new int[] { ShellCodec.packCell(0, 0, 0, 9, 1) },
                new String[] { "minecraft:stone" }, (byte) ShellCodec.TIER_VISITED,
                ShellCodec.FORMAT_VERSION, (byte) 8, (short) 0);
        if (ShellCodec.encode(badPaletteIndex) != null) {
            throw new AssertionError("encode accepted a palette index past the table");
        }
    }

    private static void requireDecodesToNull(byte[] record, String what) {
        ShellCodec.Shell result;
        try {
            result = ShellCodec.decode(record);
        } catch (Throwable t) {
            throw new AssertionError("ShellCodec.decode THREW on " + what
                    + " - a corrupt record must degrade to a miss, never to an exception "
                    + "on the store read path", t);
        }
        if (result != null) {
            throw new AssertionError("ShellCodec.decode accepted " + what
                    + " and returned a shell with " + result.cellCount + " cells");
        }
    }


    // ------------------------------------------------------------------
    // Leg 2e: the budget is a FRACTION of the frame (T2, Phase 2 of
    // docs/unreleased/farfield/FARFIELD-PERF-BRIEF.md)
    // ------------------------------------------------------------------

    /** Frame times the rule is pinned at, nanoseconds: 300 fps. */
    private static final long FRAME_300_FPS = 3_300_000L;
    /** 200 fps - the owner's machine, and the frame the wave is about. */
    private static final long FRAME_200_FPS = 5_000_000L;
    /** 120 fps - the frame every pre-T2 constant was sized against. */
    private static final long FRAME_120_FPS = 8_300_000L;
    /** 60 fps - the frame rate the deleted rule was defending. */
    private static final long FRAME_60_FPS = 16_700_000L;
    /** 30 fps - the slow laptop that must still save. */
    private static final long FRAME_30_FPS = 33_000_000L;
    /** 500 fps - short enough that the floor binds. */
    private static final long FRAME_500_FPS = 2_000_000L;

    /** The shipped user ceiling, nanoseconds (3 ms). Never binds. */
    private static final long CEILING_SHIPPED =
            FarFieldConfig.DEFAULT_EXTRACT_BUDGET_MILLIS * 1_000_000L;

    /**
     * <b>The T2 budget rule, pinned at six frame rates and all three
     * Background Saving points.</b>
     *
     * <h2>Why this leg exists at all</h2>
     * The owner's report is "saving the chunks to lod causes HUGE fps
     * drops. this drops 200 to 60 while its going on", and the cause was
     * a budget whose goal was a 60 fps FLOOR: it measured the gap between
     * the frame and 16.7 ms and spent a third of it, so the faster the
     * machine the bigger the absolute slice it took out of the smaller
     * frame. Nothing in the suite could see that, because the rule needed
     * a live client to evaluate and every assertion about it was a
     * sentence in a javadoc. {@code sliceBudgetFor} is pure now -
     * frame time in, cache speed in, ceiling in, nanoseconds out - which
     * makes the whole rule assertable on the CPU in microseconds, with no
     * world, no GPU and no clock.
     *
     * <h2>What makes this fail</h2>
     * Any reintroduction of an absolute floor (the budget stops tracking
     * the frame), any reintroduction of a frame-rate target (the 300 and
     * 200 fps rows stop being a tenth and start growing as the frame
     * shrinks), a cap that stops binding at 30 fps (a slow machine gets a
     * 3.3 ms slice again), a floor that stops binding at 500 fps (the
     * horizon stalls on a very fast client), a dial point that collides
     * with its neighbour at either clamp (the M6 dial-that-lies defect
     * returning through the back door), or the user ceiling ceasing to
     * bind or ceasing to mean never at zero.
     *
     * <p>The expected values are written out as literals rather than
     * recomputed from the constants on purpose: a test that recomputes
     * the rule from the rule's own constants passes whatever the
     * constants are, which is exactly the failure this leg is here to
     * catch.</p>
     */
    private static void assertBudgetIsAFractionOfTheFrame() {
        // ---- the shipped point: a tenth, cap 2 ms, floor 0.25 ms ----
        int fast = FarFieldConfig.CACHE_FAST;
        requireBudget(FRAME_300_FPS, fast, 330_000L, "300 fps, shipped");
        requireBudget(FRAME_200_FPS, fast, 500_000L, "200 fps, shipped");
        requireBudget(FRAME_120_FPS, fast, 830_000L, "120 fps, shipped");
        requireBudget(FRAME_60_FPS, fast, 1_670_000L, "60 fps, shipped");
        // THE CAP. A tenth of 33 ms is 3.3 ms; the rule hands back 2 ms,
        // which is 6% of that frame. A 30 fps laptop still saves - just
        // more slowly - which is the trade an absolute slice cannot make.
        requireBudget(FRAME_30_FPS, fast, 2_000_000L, "30 fps, shipped (CAP)");
        // THE FLOOR. A tenth of 2 ms is 0.2 ms, under a quarter of one
        // column walk; without the floor the horizon would stop filling
        // in any useful sense on a very fast client.
        requireBudget(FRAME_500_FPS, fast, 250_000L, "500 fps, shipped (FLOOR)");

        // ---- the dial scales the whole rule, share, cap and floor ----
        int balanced = FarFieldConfig.CACHE_BALANCED;
        requireBudget(FRAME_300_FPS, balanced, 198_000L, "300 fps, balanced");
        requireBudget(FRAME_200_FPS, balanced, 300_000L, "200 fps, balanced");
        requireBudget(FRAME_120_FPS, balanced, 498_000L, "120 fps, balanced");
        requireBudget(FRAME_60_FPS, balanced, 1_002_000L, "60 fps, balanced");
        requireBudget(FRAME_30_FPS, balanced, 1_200_000L, "30 fps, balanced (CAP)");
        requireBudget(FRAME_500_FPS, balanced, 150_000L, "500 fps, balanced (FLOOR)");

        int gentle = FarFieldConfig.CACHE_GENTLE;
        requireBudget(FRAME_300_FPS, gentle, 99_000L, "300 fps, gentle");
        requireBudget(FRAME_200_FPS, gentle, 150_000L, "200 fps, gentle");
        requireBudget(FRAME_120_FPS, gentle, 249_000L, "120 fps, gentle");
        requireBudget(FRAME_60_FPS, gentle, 501_000L, "60 fps, gentle");
        requireBudget(FRAME_30_FPS, gentle, 600_000L, "30 fps, gentle (CAP)");
        requireBudget(FRAME_500_FPS, gentle, 75_000L, "500 fps, gentle (FLOOR)");

        // ---- the dial's three points must be three numbers ----
        // M6 found two of them identical on the owner's machine, because
        // an absolute slice collapses the adaptive terms under it. A
        // fraction cannot collapse that way as long as the CLAMPS scale
        // with the share too, which is what this asserts at both clamps
        // as well as in the middle.
        long[] frames = {FRAME_300_FPS, FRAME_200_FPS, FRAME_120_FPS,
            FRAME_60_FPS, FRAME_30_FPS, FRAME_500_FPS};
        for (long frame : frames) {
            long g = ExtractDispatch.sliceBudgetFor(frame, gentle, CEILING_SHIPPED);
            long b = ExtractDispatch.sliceBudgetFor(frame, balanced, CEILING_SHIPPED);
            long f = ExtractDispatch.sliceBudgetFor(frame, fast, CEILING_SHIPPED);
            if (!(g < b && b < f)) {
                throw new AssertionError("Background Saving must be three DISTINCT "
                        + "and increasing budgets at every frame time, including at "
                        + "the cap and the floor; at frame " + frame + " ns they are "
                        + "gentle=" + g + " balanced=" + b + " fast=" + f
                        + " - a dial whose points cannot be told apart is a dial "
                        + "that lies (M6's finding, returning)");
            }
        }

        // ---- the rule is SCALE-FREE: no target frame rate anywhere ----
        // The deleted rule took a third of the gap to a 16.7 ms floor, so
        // its share of the frame GREW without limit as the frame shrank -
        // 60% of a 5 ms frame, 130% with the idle boost. Between the two
        // clamps the share must be exactly constant instead. This is the
        // single assertion that would have caught the original defect.
        for (long frame : new long[] {FRAME_300_FPS, FRAME_200_FPS,
            FRAME_120_FPS, FRAME_60_FPS}) {
            long budget = ExtractDispatch.sliceBudgetFor(frame, fast, CEILING_SHIPPED);
            long permille = budget * 1000L / frame;
            if (permille != 100L) {
                throw new AssertionError("between the clamps the shipped rule must be "
                        + "exactly a tenth of the measured frame at EVERY frame rate; "
                        + "at " + frame + " ns it took " + permille + " permille. A "
                        + "share that varies with the frame rate is a frame-rate "
                        + "TARGET wearing a fraction's clothes, which is the T2 "
                        + "defect itself (docs/unreleased/farfield/FARFIELD-PERF-BRIEF.md section 3)");
            }
        }

        // ---- the user ceiling lowers, and only lowers ----
        long capped = ExtractDispatch.sliceBudgetFor(FRAME_30_FPS, fast, 1_000_000L);
        if (capped != 1_000_000L) {
            throw new AssertionError("extractBudgetMillis must bind as a CEILING: "
                    + "at 30 fps with a 1 ms ceiling the rule returned " + capped
                    + " ns, not 1000000");
        }
        long raised = ExtractDispatch.sliceBudgetFor(FRAME_200_FPS, fast,
                8L * 1_000_000L);
        if (raised != 500_000L) {
            throw new AssertionError("extractBudgetMillis must never RAISE the "
                    + "budget - that direction is the pre-T2 floor that cost the "
                    + "owner 60% of a 5 ms frame; at 200 fps with the maximum 8 ms "
                    + "setting the rule returned " + raised + " ns, not 500000");
        }
        for (long frame : frames) {
            long never = ExtractDispatch.sliceBudgetFor(frame, fast, 0L);
            if (never != 0L) {
                throw new AssertionError("extractBudgetMillis=0 must still mean the "
                        + "far field NEVER extracts on the game thread; at frame "
                        + frame + " ns it returned " + never + " ns");
            }
        }

        // ---- a nonsense frame cannot buy more than the cap ----
        // Not a substitute for the sample clamp in beginSlice, which is
        // what keeps a loading-screen frame out of the EMA in the first
        // place; this is the belt behind it. Under a FRACTION rule a long
        // frame inflates the budget instead of suppressing it, which is
        // the opposite of the old rule's failure direction, so the bound
        // is worth pinning: whatever number arrives, the answer is 2 ms.
        long absurd = ExtractDispatch.sliceBudgetFor(2_000_000_000L, fast,
                CEILING_SHIPPED);
        if (absurd != 2_000_000L) {
            throw new AssertionError("a two-SECOND frame (a loading screen, an "
                    + "alt-tab, a breakpoint) must still be capped at 2 ms; the rule "
                    + "returned " + absurd + " ns");
        }
        long negative = ExtractDispatch.sliceBudgetFor(-1L, fast, CEILING_SHIPPED);
        if (negative != 250_000L) {
            throw new AssertionError("a negative frame time must fall to the floor, "
                    + "not underflow; the rule returned " + negative + " ns");
        }
    }

    /** One row of the budget table, with the whole rule in the message. */
    private static void requireBudget(long frameNanos, int cacheSpeed,
            long expectedNanos, String what) {
        long actual = ExtractDispatch.sliceBudgetFor(frameNanos, cacheSpeed,
                CEILING_SHIPPED);
        if (actual != expectedNanos) {
            throw new AssertionError("budget rule at " + what + ": frame "
                    + frameNanos + " ns expected " + expectedNanos + " ns, got "
                    + actual + " ns (" + (actual * 1000L / Math.max(1L, frameNanos))
                    + " permille of the frame). The rule is share = frame * 10%, "
                    + "clamped to [0.25 ms, 2 ms], scaled 30/60/100% by Background "
                    + "Saving, then lowered by the user ceiling");
        }
    }

    // ------------------------------------------------------------------
    // Leg 2b: one ocean, two light paths, one answer (pre13 O1)
    // ------------------------------------------------------------------

    /** The bed of the synthetic ocean column, absolute y. */
    private static final int OCEAN_BED_Y = 40;
    /** Its water surface, six blocks up: every level 15..9 is distinct. */
    private static final int OCEAN_SURFACE_Y = 46;

    /**
     * <b>A far record with a light plane and the same record without one
     * must mesh to the same bytes.</b>
     *
     * <h2>What this is for</h2>
     * A live world always holds BOTH populations. A column taken at the
     * chunk-receive seam is filed with no plane
     * (its record has not seen E3, so G1 holds the job) and gets
     * one only when the catch-up sweep re-extracts it, so at any moment
     * some far chunks carry the light engine's own bytes and some are
     * shaded by {@code ShellMesher.submergedLight} deriving
     * {@code 15 - depth} from the record's geometry. Those are two code
     * paths that MUST produce one pixel, and pre13 shipped with them
     * disagreeing: the derived half read a kelp stalk as the sea bed, so
     * a kelp forest's floor drew at sky 14 where the engine holds sky 0.
     * The owner saw it as "random chunks are just solid lighter color ...
     * deep in the ocean around kelp seems to be the worst offender".
     *
     * <h2>Why a synthetic column and not a world</h2>
     * The harness world is the fabric superflat and has no ocean at all
     * (the sibling lesson about superflat blind spots, on a third
     * channel). This leg needs no world: it builds one chunk column by
     * hand - sand at {@value #OCEAN_BED_Y}, a kelp stalk through the
     * water, and the surface sheet sharing the top water block's position
     * exactly as {@code ShellExtractor}'s "water surface under a plant"
     * writes it - fills a light plane with the values
     * {@code sampleCellLight} would have read, and meshes it twice.
     *
     * <h2>Three assertions, and the third is the anti-vacuity one</h2>
     * <ol>
     *   <li>the two meshes are byte-identical;</li>
     *   <li>they are not empty;</li>
     *   <li>they differ from the SAME column meshed with a uniform
     *       daylight plane. Without (3) a regression that made both paths
     *       ignore depth entirely would pass (1) and (2) with a green
     *       tick.</li>
     * </ol>
     * The layer-1 rows have to be at their shipped values for the
     * comparison to mean anything, so the leg checks them and fails
     * loudly rather than skipping.
     */
    private static void assertOceanLightIsPathIndependent(ClientGameTestContext context) {
        context.runOnClient(client -> {
            int lighting = FarFieldConfig.layerLighting(FarFieldConfig.Layer.L1);
            boolean stockWater = FarFieldConfig.layerFlag(
                    FarFieldConfig.Layer.L1, FarFieldConfig.Control.WATER_LOOK);
            if (lighting != FarFieldConfig.LIGHT_REAL || !stockWater) {
                throw new AssertionError("this leg compares the PLANED and DERIVED water "
                        + "paths and needs both armed: Lighting must be Real Light (is "
                        + lighting + ") and Distant Water must be Stock Minecraft (is "
                        + stockWater + ")");
            }
            int depth = OCEAN_SURFACE_Y - OCEAN_BED_Y;
            int upMask = 1 << SpriteUvResolver.FACE_IDX_UP;
            String[] palette = {
                "minecraft:sand", "minecraft:kelp_plant", "minecraft:water",
            };
            // One cell for the bed, one stalk block per water block, and
            // the sheet, which shares the top stalk block's position.
            int[] cells = new int[256 * (depth + 2)];
            byte[] plane = new byte[cells.length];
            int n = 0;
            for (int y = OCEAN_BED_Y; y <= OCEAN_SURFACE_Y; y++) {
                int yRel = y - OCEAN_BED_Y;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (y == OCEAN_BED_Y) {
                            cells[n] = ShellCodec.packCell(x, yRel, z, 0, upMask);
                            // sampleCellLight probes UP: the water block
                            // directly over the bed, sky 15 - depth.
                            plane[n++] = skyOnly(15 - depth);
                            continue;
                        }
                        cells[n] = ShellCodec.packCell(x, yRel, z, 1, upMask);
                        plane[n++] = skyOnly(15 - (OCEAN_SURFACE_Y - y));
                        if (y == OCEAN_SURFACE_Y) {
                            cells[n] = ShellCodec.packCell(x, yRel, z, 2, upMask);
                            plane[n++] = skyOnly(15); // the air above the sheet
                        }
                    }
                }
            }
            byte[] daylight = new byte[cells.length];
            Arrays.fill(daylight, skyOnly(15));

            // The name overload: this record stores no block states, so
            // every entry draws its block's default - water level 0, the
            // ocean's own source state.
            SpriteUvResolver.ResolvedPalette resolved =
                    SpriteUvResolver.resolve(palette);
            ShellMesher.Result planed =
                    ShellMesher.mesh(oceanShell(cells, palette, plane), resolved);
            ShellMesher.Result derived =
                    ShellMesher.mesh(oceanShell(cells, palette, null), resolved);
            ShellMesher.Result flat =
                    ShellMesher.mesh(oceanShell(cells, palette, daylight), resolved);

            if (planed.sections().length == 0
                    || planed.sections()[0].mesh().quadCount() == 0) {
                throw new AssertionError("the synthetic ocean column meshed to nothing - "
                        + "the leg proves nothing until it draws something");
            }
            if (sameGeometry(planed, flat)) {
                throw new AssertionError("the " + depth + "-block depth ramp did not reach "
                        + "a single vertex: the planed mesh is byte-identical to the same "
                        + "column lit at flat daylight, so this leg would pass whatever "
                        + "the water code did");
            }
            String mismatch = firstGeometryDifference(planed, derived);
            if (mismatch != null) {
                throw new AssertionError("the PLANED and DERIVED water paths disagree, which "
                        + "is the owner's pre13 O1 (a pale ocean chunk beside a dark one): "
                        + mismatch);
            }
        });
    }

    /** One packed light byte: sky level in the high nibble, no block light. */
    private static byte skyOnly(int level) {
        return (byte) (Math.max(0, Math.min(15, level)) << 4);
    }

    /**
     * The synthetic column, with or without its light plane. A null
     * {@code paletteTint} is deliberate: the mesher then takes each row's
     * out-of-world default colour, which is the same in both runs and
     * keeps the comparison about LIGHT.
     */
    private static ShellCodec.Shell oceanShell(int[] cells, String[] palette, byte[] plane) {
        return new ShellCodec.Shell(cells.length, cells, palette,
                null, ShellCodec.TINT_SIDE_SINGLE, plane,
                (byte) ShellCodec.TIER_VISITED, ShellCodec.FORMAT_VERSION,
                (byte) 8, (short) OCEAN_BED_Y);
    }

    private static boolean sameGeometry(ShellMesher.Result a, ShellMesher.Result b) {
        return firstGeometryDifference(a, b) == null;
    }

    /**
     * Null when two mesh results are byte-identical, else a description of
     * the first place they part company.
     */
    private static String firstGeometryDifference(ShellMesher.Result a, ShellMesher.Result b) {
        if (a.sections().length != b.sections().length) {
            return "section count " + a.sections().length + " vs " + b.sections().length;
        }
        for (int s = 0; s < a.sections().length; s++) {
            ShellMesher.SectionMesh sa = a.sections()[s];
            ShellMesher.SectionMesh sb = b.sections()[s];
            if (sa.sy() != sb.sy()) {
                return "section " + s + " is sy " + sa.sy() + " vs " + sb.sy();
            }
            if (sa.mesh().quadCount() != sb.mesh().quadCount()) {
                return "section sy " + sa.sy() + " holds " + sa.mesh().quadCount()
                        + " quads vs " + sb.mesh().quadCount()
                        + " - the greedy merge split differently, which means the two "
                        + "paths wrote different light or colour into the cells";
            }
            ByteBuffer ba = sa.mesh().geometry();
            ByteBuffer bb = sb.mesh().geometry();
            int bytes = sa.mesh().geometryBytes();
            for (int i = 0; i < bytes; i++) {
                if (ba.get(i) != bb.get(i)) {
                    return "section sy " + sa.sy() + ", quad " + (i / 64) + ", byte "
                            + (i % 64) + " of 64: 0x"
                            + Integer.toHexString(ba.get(i) & 0xFF) + " vs 0x"
                            + Integer.toHexString(bb.get(i) & 0xFF);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Leg 2: zero cost off (the invariant)
    // ------------------------------------------------------------------

    /**
     * Off means off: FAR-FIELD-DESIGN.md section 6 promises no thread,
     * no folder, no allocation, and the W4 review sharpened that into a
     * CLASS-LOADING claim, because "the counters read zero" is also what
     * a fully loaded, fully wired, merely idle far field looks like.
     *
     * <p>The probe is {@code ClassLoader.findLoadedClass}, reached
     * reflectively. It is the only thing in a plain JVM that answers
     * "was this class ever defined" without defining it:
     * {@code Class.forName(name, false, loader)} DEFINES the class (the
     * {@code initialize} flag only skips the static initializer), and
     * {@code ClassLoadingMXBean} counts classes without naming them. The
     * method is protected in {@code java.base}, so the harness passes
     * {@code --add-opens=java.base/java.lang=ALL-UNNAMED} (build.gradle,
     * clientGameTest run config). If the probe cannot run, this leg
     * FAILS rather than falling back to a weaker check that cannot tell
     * absence from a broken probe.</p>
     *
     * <p><b>What makes this fail:</b> anything that reaches the far
     * walker with the master off. Concretely: dropping the
     * {@code farEverArmed} latch in {@code TerrainResidency.pumpFarField}
     * or {@code drainFarFieldLocked} back to a plain {@code enabled()}
     * check (the W3 review's CRITICAL finding, which cost a frame's
     * class load inside the residency LOCK on every pump), removing the
     * {@code io == null} guard from {@code FarField.onWorldLeave} (the
     * level-swap mixin calls it unconditionally, so the guard is the
     * only thing standing between a disconnect and the walker's
     * clinit), a counter export or a debug line naming
     * {@code FarFieldResidency} unconditionally, or the Far Terrain
     * screen touching residency to render a size readout. It also fails
     * if the store ever creates its folder chain before a write, or if
     * {@code FarFieldConfig.load()} starts writing a default config
     * file.</p>
     *
     * <p>The leg runs on EVERY run, but the run that makes it bite is
     * the Vulkan terrain run: on a backend where the drawer stays
     * dormant, {@code TerrainResidency.pump} never runs and the guards
     * this leg is checking are never even reached, so the assertion is
     * true for an uninteresting reason. It also inherits four earlier
     * test classes' worth of world loads in the same JVM, which is more
     * evidence than its own world provides, not less.</p>
     */
    /**
     * R4, PROVED FROM THE POSITIVE SIDE: {@code grass_block}, {@code sand}
     * and {@code dirt} - the three the owner named - really do enumerate
     * their four rotations, the four really are four different looks, and
     * two world positions really do select two of them.
     *
     * <h2>Why the counters could not do this</h2>
     * {@code farVariantMultiDraw} and its three siblings can only ever say
     * "nothing complained". Every failure mode this feature has is SILENT
     * on them: an enumeration that handed back one table four times, a
     * draw that came out constant, a table array that resolved and was
     * never read. <b>The bug this wave actually found is of exactly that
     * shape</b> - {@code MODEL_SEED = 42} draws variant 2 for every
     * 4-option row in the game, so the far field stamped {@code y: 180}
     * on every grass top and sand cell, and not one counter anywhere was
     * non-zero. So the assertion has to be that two positions DIFFER, and
     * it has to name the owner's blocks rather than sample whatever the
     * test world happens to hold.
     *
     * <h2>The numbers are the jar's, not this test's</h2>
     * {@code positionSeed(100, 71, 200)} and the draws below are
     * transcribed in docs/unreleased/farfield/FARFIELD-WAVES.md, "R4 AND R5 ANSWERED (S1)",
     * from {@code Mth.getSeed} ip 0-34, {@code SingleThreadedRandomSource
     * .setSeed} ip 0-10 / {@code next} ip 0-29 and
     * {@code BitRandomSource.nextInt(int)} ip 14-38. Pinning
     * {@code variantDraw(42, 4) == 2} pins the OLD look too, so a
     * regression back to a fixed seed fails HERE rather than in a
     * playtest.
     *
     * <p>No world, no GPU, both backends: it needs the model manager and
     * nothing else.</p>
     */
    private static void assertOwnersBlocksRotatePerPosition(
            ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (!FarFieldConfig.layerFlag(FarFieldConfig.Layer.L1,
                    FarFieldConfig.Control.BLOCK_VARIANTS)) {
                throw new AssertionError("this leg proves the Block Variants row and "
                        + "needs it armed; it is OFF, so either the default moved or "
                        + "the v8 migration did not run");
            }
            // The arithmetic first, against the jar-derived numbers, so a
            // failure is unambiguous about WHICH half broke.
            long seed = ShellMesher.positionSeed(100, 71, 200);
            if (seed != 41726661399728L) {
                throw new AssertionError("positionSeed(100,71,200) = " + seed
                        + ", expected 41726661399728 (Mth.getSeed ip 0-34): the position "
                        + "hash moved, so every per-position feature is now wrong");
            }
            long seedB = ShellMesher.positionSeed(102, 71, 200);
            if (ShellMesher.variantDraw(seed, 4) != 3
                    || ShellMesher.variantDraw(seedB, 4) != 0) {
                throw new AssertionError("variantDraw disagrees with vanilla's own LCG "
                        + "chain: (100,71,200) must draw 3 and (102,71,200) must draw 0 "
                        + "(setSeed ip 0-10, next ip 0-29, nextInt ip 14-38)");
            }
            if (ShellMesher.variantDraw(42L, 4) != 2) {
                throw new AssertionError("variantDraw(MODEL_SEED=42, 4) must be 2 - the "
                        + "pre18 look this wave replaced. If this moved, the desk check "
                        + "in the waves doc no longer describes the bug that was fixed");
            }

            String[] names = {"minecraft:grass_block", "minecraft:sand", "minecraft:dirt"};
            SpriteUvResolver.ResolvedPalette palette = SpriteUvResolver.resolve(names);
            for (int row = 0; row < names.length; row++) {
                String name = names[row];
                SpriteUvResolver.Quads[] tables = palette.variantTables(row);
                if (tables == null) {
                    throw new AssertionError(name + " did not enumerate its variants at "
                            + "all, so the far field draws ONE rotation of it everywhere "
                            + "- which is the owner's R4 verbatim. Read the four "
                            + "farVariant* counters to see which cause fired");
                }
                if (tables.length != 4) {
                    throw new AssertionError(name + " enumerated " + tables.length
                            + " variants, expected 4 (the jar's blockstate lists four "
                            + "y-rotations, all weight 1)");
                }
                // Four DIFFERENT looks, not one table handed back four
                // times: the fixed-seed bug satisfies everything above.
                int distinct = 0;
                for (int i = 0; i < tables.length; i++) {
                    boolean seen = false;
                    for (int j = 0; j < i; j++) {
                        seen |= tables[i] == tables[j];
                    }
                    if (!seen) {
                        distinct++;
                    }
                }
                if (distinct != 4) {
                    throw new AssertionError(name + " enumerated 4 variants but only "
                            + distinct + " distinct tables: the rotations are collapsing, "
                            + "so per-position selection cannot change a pixel");
                }
                // And the difference has to be in the UVs, because that is
                // what a y-rotation moves and what the player sees.
                SpriteUvResolver.Quads a = tables[ShellMesher.variantDraw(seed, 4)];
                SpriteUvResolver.Quads b = tables[ShellMesher.variantDraw(seedB, 4)];
                if (a == b) {
                    throw new AssertionError(name + ": (100,71,200) and (102,71,200) draw "
                            + "3 and 0 but select the SAME table");
                }
                if (Arrays.equals(a.uv(), b.uv())) {
                    throw new AssertionError(name + ": the two positions select different "
                            + "tables whose UVs are identical, so the rotation is not "
                            + "reaching the vertices - the merge would rejoin them and "
                            + "the horizon would look exactly as it did");
                }
                // The merge-routing claim the shifted-plane bucket rests on.
                if (!palette.variantTablesUniform(row)) {
                    throw new AssertionError(name + " reports non-uniform variant routing; "
                            + "a pure rotation permutes a cube's faces without moving "
                            + "cullface, facing, unit, shift or translucency, so this "
                            + "means the tables are not the rotations they should be");
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Leg 2d: THE GREEN WATER (cold, no world, no GPU, no flight)
    // ------------------------------------------------------------------

    /** Vanilla {@code ocean}: baseBiome's default, javap ip 39 int 4159204. */
    private static final int OCEAN_WATER_RGB = 0x3F76E4;
    /**
     * Vanilla {@code sulfur_caves}: {@code OverworldBiomes.sulfurCaves}
     * ip 314 {@code ldc_w int -13320311}, ip 317 {@code waterColor:(I)}.
     * The ONLY cave biome in 26.2 that declares a water colour -
     * {@code lushCaves}, {@code dripstoneCaves} and {@code deepDark}
     * declare none and inherit the blue above - which is why a y-only
     * biome error on water in this version can substitute exactly one
     * wrong colour, and it is this vivid spring green.
     */
    private static final int SULFUR_CAVES_WATER_RGB = 0x34BF89;
    /** The waterline of the synthetic column. */
    private static final int GREEN_SURFACE_Y = 62;
    /** The flooded cave under its seabed. */
    private static final int GREEN_CAVE_Y = 34;
    /** Anything at or above this y is ocean; below it is sulfur caves. */
    private static final int GREEN_BIOME_SPLIT_Y = 50;

    /**
     * <b>The colour stored for a block is vanilla's answer for the block
     * we are drawing.</b> One entry, two biome layers, and the SURFACE
     * cell must wear the SURFACE biome.
     *
     * <h2>The bug this is the alarm for</h2>
     * A palette entry's whole 16x16 tint field used to be sampled at ONE
     * y - the y of the LOWEST cell that minted the entry - while
     * vanilla's biome lookup is 3D. {@code minecraft:water[level=0]} is
     * one state for a whole chunk, so it is one entry, and it was stamped
     * at the deepest EXPOSED water cell: a flooded cave, an aquifer or a
     * ravine, reachable because the surface band admits to roughly
     * seabed-72. Vanilla 26.2 has exactly one cave biome declaring a
     * water colour and it is {@link #SULFUR_CAVES_WATER_RGB}, a vivid
     * spring green. So an entire chunk's ocean SURFACE wore cave water,
     * while the chunk next door - no exposed deep cell, so its entry was
     * minted at the waterline - was correct. The owner's report, three
     * releases running: "vibrantly green, then immediately blue at the
     * next chunk".
     *
     * <h2>Why nothing caught it, and what this does differently</h2>
     * Every tint assertion in this suite hand-built a {@code Shell},
     * encoded it, decoded it and compared the result against the values
     * it had just written. That tests the CODEC. The EXTRACTOR - the
     * palette, the height it samples at, the field it builds - was run
     * against nothing, by any test, ever. A suite that only compares our
     * arithmetic to our arithmetic cannot catch "we asked the right
     * function at the wrong place", and the colour VALUE was right at
     * every hop: the resolver, the tint source, the blend, the codec and
     * the mesher were all correct and all irrelevant.
     *
     * <p>So this asserts against an ORACLE instead. The tint view below
     * answers one colour above {@link #GREEN_BIOME_SPLIT_Y} and another
     * below it, which is a biome source that varies in y and nothing
     * else. The stored colour for the surface column is then either the
     * surface biome's or the cave biome's, and there is no third
     * possibility and no tuning: <b>one bit of input, two outcomes.</b>
     *
     * <h2>Three columns, and the third is the one that used to lie</h2>
     * <ul>
     *   <li><b>The cave column</b> (3, 5) has cells at both y - the
     *       exposed cave water AND the surface sheet. It stored the cave
     *       green before this fix and must store ocean blue now;</li>
     *   <li><b>a plain column</b> (9, 11) has only the surface cell, and
     *       must be BIT-IDENTICAL to what pre19 stored - the fix must not
     *       move a record it was never wrong about;</li>
     *   <li><b>the deep-only entry</b> is the control: remove the surface
     *       cell from a column and its node must fall back to the cave
     *       colour, because that really is the topmost block there. A fix
     *       that just hardcoded "sample high" would pass the first two
     *       and fail this.</li>
     * </ul>
     *
     * <p>And the Per Chunk row is asserted beside it, because it carried
     * the same defect in its purest form (one flat wrong colour per
     * record, sampled at the chunk origin at the mint y) and because
     * "set Colour Blending to Per Chunk" was a diagnostic control for
     * this bug rather than a workaround for it.</p>
     */
    private static void assertSurfaceCellWearsTheSurfaceBiome(
            ClientGameTestContext context) {
        context.runOnClient(client -> {
            BlockState water = Blocks.WATER.defaultBlockState();
            LayeredTintView view = new LayeredTintView();

            // Sanity: the oracle itself, before anything is asserted
            // against it. If the fluid tint path stopped resolving, every
            // assertion below would pass for the wrong reason.
            int surfaceTruth = SpriteUvResolver.sampleTint(water, view,
                    new BlockPos(3, GREEN_SURFACE_Y, 5));
            int caveTruth = SpriteUvResolver.sampleTint(water, view,
                    new BlockPos(3, GREEN_CAVE_Y, 5));
            if (surfaceTruth != OCEAN_WATER_RGB || caveTruth != SULFUR_CAVES_WATER_RGB) {
                throw new AssertionError("the synthetic tint view is not being asked: "
                        + "water at y=" + GREEN_SURFACE_Y + " resolved "
                        + hex(surfaceTruth) + " (want " + hex(OCEAN_WATER_RGB)
                        + ") and at y=" + GREEN_CAVE_Y + " resolved " + hex(caveTruth)
                        + " (want " + hex(SULFUR_CAVES_WATER_RGB) + "). Either the fluid "
                        + "branch of sampleTint stopped reaching getBlockTint or water "
                        + "lost its FluidModel tint source - fix that before reading "
                        + "anything else in this leg");
            }

            // ---- the record ----------------------------------------
            // Bottom-up, exactly as the walk emits: the deep cave cell
            // first (it is what mints the entry), then the surface sheet
            // for every column but one.
            ShellExtractor.Palette pal = new ShellExtractor.Palette(
                    SpriteUvResolver.stateStorageAvailable());
            int e = pal.indexOf(water, view, 0, GREEN_CAVE_Y, 0);
            if (e != 0) {
                throw new AssertionError("the first indexOf minted entry " + e);
            }
            int minY = GREEN_CAVE_Y;
            int faceUp = ShellExtractor.FACE_UP;
            int faceNorth = ShellExtractor.FACE_NORTH;
            List<Integer> cellList = new ArrayList<>();
            // The flooded cave: one cell, one exposed lateral face, in
            // the column that will also carry a surface sheet, plus one
            // in a column that will NOT (the control).
            cellList.add(ShellCodec.packCell(3, 0, 5, e, faceNorth));
            cellList.add(ShellCodec.packCell(14, 0, 2, e, faceNorth));
            // The waterline, every column except the control's.
            int surfaceRel = GREEN_SURFACE_Y - minY;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (x == 14 && z == 2) {
                        continue; // the deep-only control column
                    }
                    cellList.add(ShellCodec.packCell(x, surfaceRel, z, e, faceUp));
                }
            }
            int[] cells = new int[cellList.size()];
            for (int i = 0; i < cells.length; i++) {
                cells[i] = cellList.get(i);
            }

            // ---- the extractor's own call sequence -----------------
            int[] columnY = pal.columnHeights(cells, cells.length, minY);
            int side = pal.rebuildTints(ShellCodec.TINT_SIDE_CELL, view, 0, 0, columnY);
            if (side != ShellCodec.TINT_SIDE_CELL) {
                throw new AssertionError("rebuildTints refused the cell field");
            }
            int[] tints = pal.tints();
            if (tints.length != 256) {
                throw new AssertionError("one entry must build 256 nodes, built "
                        + tints.length);
            }

            // ---- THE ASSERTION -------------------------------------
            int cave = tints[(5 << 4) + 3];
            if (cave != OCEAN_WATER_RGB) {
                throw new AssertionError("GREEN WATER: the water entry's node for column "
                        + "(3, 5) stored " + hex(cave) + ", but that column's TOPMOST "
                        + "stored cell is the surface sheet at y=" + GREEN_SURFACE_Y
                        + ", where vanilla answers " + hex(OCEAN_WATER_RGB) + ". "
                        + (cave == SULFUR_CAVES_WATER_RGB
                            ? "It stored the CAVE colour, so the field is being sampled "
                              + "at the entry's lowest cell again - the pre20 defect "
                              + "verbatim, and the owner's whole chunk of green ocean."
                            : "It stored neither layer, so the sampling y is not a cell "
                              + "y at all.")
                        + " See Palette.columnHeights and Palette.mintY");
            }
            int plain = tints[(11 << 4) + 9];
            if (plain != OCEAN_WATER_RGB) {
                throw new AssertionError("a column with only a surface cell stored "
                        + hex(plain) + " instead of " + hex(OCEAN_WATER_RGB)
                        + ": the fix moved a record it was never wrong about");
            }
            int deepOnly = tints[(2 << 4) + 14];
            if (deepOnly != SULFUR_CAVES_WATER_RGB) {
                throw new AssertionError("the deep-only control column (14, 2) stored "
                        + hex(deepOnly) + " instead of " + hex(SULFUR_CAVES_WATER_RGB)
                        + ". Its only stored cell IS the cave water, so the cave colour "
                        + "is the right answer there. Storing the surface colour means "
                        + "the field is sampling a fixed height rather than each "
                        + "column's own topmost cell, which is the same class of bug "
                        + "pointing the other way");
            }

            // ---- (D) the Per Chunk row, same column, same defect ----
            ShellExtractor.Palette flat = new ShellExtractor.Palette(
                    SpriteUvResolver.stateStorageAvailable());
            flat.indexOf(water, view, 0, GREEN_CAVE_Y, 0);
            int[] flatColumnY = flat.columnHeights(cells, cells.length, minY);
            int flatSide = flat.rebuildTints(
                    ShellCodec.TINT_SIDE_SINGLE, view, 0, 0, flatColumnY);
            int[] flatTints = flat.tints();
            if (flatSide != ShellCodec.TINT_SIDE_SINGLE || flatTints.length != 1) {
                throw new AssertionError("the Per Chunk row must stay one colour per "
                        + "entry; got side " + flatSide + " and " + flatTints.length
                        + " nodes");
            }
            if (flatTints[0] != OCEAN_WATER_RGB) {
                throw new AssertionError("Per Chunk stored " + hex(flatTints[0])
                        + " instead of " + hex(OCEAN_WATER_RGB) + ". appendTint samples "
                        + "the chunk-ORIGIN column at the MINT y, and rebuildTints has "
                        + "to replace that with the entry's own topmost cell in a column "
                        + "that actually contains it. This is why 'set Colour Blending "
                        + "to Per Chunk' never fixed the green");
            }

            // ---- the height field itself, so a failure above is
            //      readable rather than just wrong ------------------
            if (columnY[(5 << 4) + 3] != GREEN_SURFACE_Y
                    || columnY[(2 << 4) + 14] != GREEN_CAVE_Y
                    || columnY[(11 << 4) + 9] != GREEN_SURFACE_Y) {
                throw new AssertionError("columnHeights is wrong before any sampling "
                        + "happens: (3,5)=" + columnY[(5 << 4) + 3] + " want "
                        + GREEN_SURFACE_Y + ", (14,2)=" + columnY[(2 << 4) + 14]
                        + " want " + GREEN_CAVE_Y + ", (9,11)="
                        + columnY[(11 << 4) + 9] + " want " + GREEN_SURFACE_Y
                        + ". Last-write-wins over a bottom-up cell array is the whole "
                        + "algorithm, so this is a packing or ordering error");
            }
        });
    }

    /** 0xRRGGBB, because a colour failure read in decimal is unreadable. */
    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "0x%06X", rgb & 0xFFFFFF);
    }

    /**
     * A biome source that varies in Y AND NOTHING ELSE: ocean water at or
     * above {@link #GREEN_BIOME_SPLIT_Y}, sulfur-caves water below it.
     *
     * <p>That is the entire fixture. No world, no chunk, no biome
     * registry, no blend radius - {@code BlockTintSources.water()}
     * resolves through {@code BiomeColors.getAverageWaterColor}, which
     * calls exactly {@code getBlockTint(pos, resolver)}, so answering
     * that one method with a step function in y reproduces "one palette
     * entry spans two biome layers" precisely and deterministically.
     * Every other method of the interface throws or answers empty: if
     * the extractor ever starts reading blocks or light through the tint
     * view, this leg must fail loudly rather than quietly test something
     * else.</p>
     */
    private static final class LayeredTintView implements BlockAndTintGetter {
        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return pos.getY() >= GREEN_BIOME_SPLIT_Y
                    ? OCEAN_WATER_RGB : SULFUR_CAVES_WATER_RGB;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            throw new IllegalStateException(
                    "the tint oracle must not be asked for lighting");
        }

        @Override
        public LevelLightEngine getLightEngine() {
            throw new IllegalStateException(
                    "the tint oracle must not be asked for light");
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState().getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }
    private static void assertZeroCostOff(ClientGameTestContext context) {
        if (FarFieldConfig.enabled()) {
            throw new AssertionError("the zero-cost-off leg ran with the far field ARMED "
                    + "(meshelium.farfield.enabled resolves true). Something set the master "
                    + "switch before this leg; it can only prove anything from a cold start");
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            // Hundreds of residency pumps with a real world up: this is
            // the window in which a mis-gated hook would name the walker.
            context.waitTicks(120);
        }
        context.waitTicks(20); // the world-leave path runs here

        if (!FarField.isCompletelyIdle()) {
            throw new AssertionError("FarField armed itself with the master switch off: an "
                    + "IO thread, a queue or a store exists after a full world load");
        }
        requireZero("farStoreWrites", FarField.farStoreWrites.sum());
        requireZero("farStoreReads", FarField.farStoreReads.sum());
        requireZero("farStoreErrors", FarField.farStoreErrors.sum());
        requireZero("farStoreBytesWritten", FarField.farStoreBytesWritten.sum());
        requireZero("farStoreStaleRecords", FarField.farStoreStaleRecords.sum());
        requireZero("farStoreDroppedWrites", FarField.farStoreDroppedWrites.sum());
        // M1: the acknowledgement ledger is part of FarField's class-load
        // surface, so the zero-cost leg owns it like the other counters.
        requireZero("farSaveAckOk", FarField.farSaveAckOk.sum());
        requireZero("farSaveAckFailed", FarField.farSaveAckFailed.sum());
        requireZero("farExtracts", ExtractDispatch.farExtracts.sum());
        requireZero("farExtractCells", ExtractDispatch.farExtractCells.sum());
        // Phase 3's own surface joins the class-load footprint the same
        // way M1's and M2-M4's did. farWalksOffWorker is here twice over:
        // zero-with-the-switch-off, and zero as a standing contract.
        requireZero("farCaptures", ExtractDispatch.farCaptures.sum());
        requireZero("farCaptureNanos", ExtractDispatch.farCaptureNanos.sum());
        requireZero("farWalksOffWorker", ExtractDispatch.farWalksOffWorker.sum());
        // M2-M4: the state machine's own surface is part of the
        // class-load footprint now, so the zero-cost leg owns it too.
        // The plain gauges are game-thread-confined; read them THERE
        // (computeOnClient) rather than tearing them from this thread.
        requireZero("farSaveLost", ExtractDispatch.farSaveLost.sum());
        requireZero("farSaveLeftUnsaved", ExtractDispatch.farSaveLeftUnsaved.sum());
        requireZero("farSaveGaugeUnderflow",
                ExtractDispatch.farSaveGaugeUnderflow.sum());
        requireZero("farSaveBehind", context.computeOnClient(
                client -> ExtractDispatch.farSaveBehind()));
        requireZero("jobsQueued", context.computeOnClient(
                client -> (long) ExtractDispatch.jobsQueued()));
        requireZero("editsPending", context.computeOnClient(
                client -> (long) ExtractDispatch.editsPending()));
        if (ExtractDispatch.armed()) {
            throw new AssertionError("ExtractDispatch reports ARMED with the master switch "
                    + "off - the chunk-lifecycle seams are live and extracting");
        }
        // NOTE: FarFieldResidency's counters are deliberately NOT read
        // here. Reading them would load the very class the next two
        // assertions require to be absent, and the leg would pass on the
        // strength of an assertion it had itself invalidated.

        Path cache = FarField.cacheFolder();
        if (Files.exists(cache)) {
            throw new AssertionError("the far-field cache folder exists after a world load "
                    + "with the master switch off: " + cache + " ("
                    + cacheBytesOnDisk() + " bytes)");
        }

        requireClassNotLoaded(RESIDENCY_CLASS);
        requireClassNotLoaded(SPRITE_RESOLVER_CLASS);
    }

    private static void requireZero(String name, long value) {
        if (value != 0L) {
            throw new AssertionError("far-field counter " + name + " moved to " + value
                    + " with the master switch off");
        }
    }

    /**
     * Fails unless {@code className} has never been defined by the
     * mod's class loader or any of its parents. Throws (rather than
     * skipping) when the probe itself is unavailable: an assertion that
     * cannot fail is worse than no assertion, and this repo has the scar
     * to prove it.
     */
    private static void requireClassNotLoaded(String className) {
        Boolean loaded = findLoadedClass(className);
        if (loaded == null) {
            throw new AssertionError("cannot probe class loading: "
                    + "ClassLoader.findLoadedClass is not reachable from this JVM. Add "
                    + "--add-opens=java.base/java.lang=ALL-UNNAMED to the clientGameTest "
                    + "run config (build.gradle already does). Refusing to pass the "
                    + "zero-cost-off leg on a probe that cannot fail");
        }
        if (loaded) {
            throw new AssertionError(className + " was LOADED in a session that never "
                    + "enabled the far field. Master OFF must mean the far-field walker is "
                    + "never even defined (FAR-FIELD-DESIGN.md section 6); some code path "
                    + "now names it outside the farEverArmed / io-null guards");
        }
    }

    /**
     * TRUE/FALSE from {@code ClassLoader.findLoadedClass}, or null when
     * the reflective route is closed. Walks the parent chain because the
     * answer must be "no loader anywhere has this class", not "not this
     * one".
     */
    private static Boolean findLoadedClass(String className) {
        try {
            Method probe = ClassLoader.class.getDeclaredMethod(
                    "findLoadedClass", String.class);
            probe.setAccessible(true);
            for (ClassLoader loader = MesheliumFarFieldTest.class.getClassLoader();
                    loader != null; loader = loader.getParent()) {
                if (probe.invoke(loader, className) != null) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (ReflectiveOperationException | RuntimeException probeClosed) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Legs 3 to 5: the far-armed run
    // ------------------------------------------------------------------

    private static void assertFarArmedLegs(ClientGameTestContext context) {
        armFarField(context, true);
        setFarRadius(context, FAR_L1_HIGH);
        try {
            try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
                freezeWorld(singleplayer);
                // Spectator BEFORE anything moves: the camera poses below
                // are 40 blocks up and a survival player would fall out of
                // them mid screenshot.
                singleplayer.getServer().runCommand("gamemode spectator @p");
                // ORDER MATTERS. waitForChunksRender has a FIXED internal
                // timeout, so it can only be used at the harness boot
                // render distance; raising the option first makes it wait
                // on a far larger chunk set than that timeout allows and
                // it fails with "Timed out waiting for predicate". This is
                // the same trap MesheliumTerrainDrawTest documents at its
                // own setup ("Do NOT raise the option here"), and it is
                // exactly how this leg failed on its first real run
                // (2026-08-19). Wait at boot rd, THEN raise, then settle
                // on counters that have no fixed deadline.
                singleplayer.getClientLevel().waitForChunksRender();
                setRenderDistanceLikeTheUi(context, FAR_RD);
                settleLoadedChunks(context);
                context.waitFor(client -> TerrainDrawer.framesDrawn() > 0
                        && TerrainDrawer.lastDrawnSections() > 0, DRAW_TIMEOUT_TICKS);
                assertNoErrors();

                assertExtractionOnWalkAway(context, singleplayer);
                assertTheGameThreadStoppedWalking(context, singleplayer);
                assertEditReachesStoreOnLeave(context, singleplayer);
                assertTorchLightsTheNeighbourColumn(context, singleplayer);
                assertWriteAckAttribution(context);
                assertRdShrinkStormLosesNothing(context, singleplayer);
                // Resolved ONCE, while the player is still standing on
                // the ground: every camera command below is absolute, so
                // re-posing cannot walk the camera up 40 blocks at a time
                // and turn the evidence pair into two different shots.
                double cameraY = groundY(context) + CAMERA_HEIGHT;
                assertFarDrawBeyondRenderDistance(context, singleplayer, cameraY);
                assertReplaceInPlaceOnFresherShell(context);
                assertRadiusActsInBothDirections(context);
                // LAST: it destroys the cache the legs above needed.
                assertClearCacheThroughTheScreen(context, singleplayer);
                assertNoErrors();
            }
        } finally {
            armFarField(context, false);
            context.runOnClient(client ->
                    System.clearProperty("meshelium.farfield.l1RadiusChunks"));
        }
    }

    /**
     * Leg 3: extraction. Three teleports, each a full window past the
     * last, so every chunk of the previous window is abandoned; the
     * client's forget/replace path calls {@code ClientLevel.unload} and
     * the W2 mixin extracts there, synchronously, while the section data
     * is still intact.
     *
     * <p><b>What makes this fail:</b> the {@code ClientLevelUnloadMixin}
     * not firing at all (wrong target after a vanilla rename, mixin not
     * registered, the {@code armed()} gate never refreshed after the
     * master switch flipped); the extractor returning null for every
     * chunk (a band cut that eats the whole column, a heightmap query
     * that primes wrong); the store refusing every write (a truth-tier
     * regression, a folder that never gets created, an IO thread that
     * died on its first task); or the queue silently dropping everything
     * (a write cap regression would show up as farStoreDroppedWrites
     * moving while farStoreWrites stays flat, which the message
     * prints). The disk check is separate from the counter check on
     * purpose: a store that counts writes it never flushed would pass
     * the counters and fail the bytes.</p>
     */
    private static void assertExtractionOnWalkAway(ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        long extractsBefore = ExtractDispatch.farExtracts.sum();
        long writesBefore = FarField.farStoreWrites.sum();

        hopTo(context, singleplayer, WALK_STOP_1_X);
        hopTo(context, singleplayer, WALK_STOP_2_X);
        hopTo(context, singleplayer, HOME_X);

        long extracts = ExtractDispatch.farExtracts.sum() - extractsBefore;
        if (extracts <= 0) {
            throw new AssertionError("walking three full windows away extracted NOTHING "
                    + "(farExtracts flat at " + extractsBefore + ", armed="
                    + ExtractDispatch.armed() + ", jobsQueued="
                    + ExtractDispatch.jobsQueued() + ", behind="
                    + ExtractDispatch.farSaveBehind()
                    + ") - no chunk-unload seam fired");
        }
        try {
            context.waitFor(client -> FarField.farStoreWrites.sum() > writesBefore,
                    DRAW_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("extracted " + extracts + " shells but the store wrote "
                    + "none: writes=" + FarField.farStoreWrites.sum() + " dropped="
                    + FarField.farStoreDroppedWrites.sum() + " errors="
                    + FarField.farStoreErrors.sum(), t);
        }
        // The store writes through a RandomAccessFile with no buffering
        // of its own, so once a write is counted the bytes are on disk.
        Path cache = FarField.cacheFolder();
        if (!Files.isDirectory(cache)) {
            throw new AssertionError("the store counted "
                    + FarField.farStoreWrites.sum() + " writes but "
                    + cache + " does not exist");
        }
        long bytes = cacheBytesOnDisk();
        if (bytes <= 0) {
            throw new AssertionError("the cache folder " + cache + " exists but holds "
                    + bytes + " bytes after " + FarField.farStoreWrites.sum() + " writes");
        }
        if (FarField.farStoreErrors.sum() != 0) {
            throw new AssertionError("the far-field store reported "
                    + FarField.farStoreErrors.sum() + " errors during extraction");
        }
    }

    /**
     * <b>Leg 3b (T2 Phase 3): the game thread stopped walking, and it
     * must not start again.</b>
     *
     * <p>Phase 3's entire claim is a threading claim - that a column's
     * ~3-7 ms walk happens on {@code PinWorker} and the frame's thread
     * only takes a cheap immutable snapshot - and a threading claim is
     * the kind that decays silently. Nothing about a walk that drifts
     * back onto the game thread is visibly broken: the terrain is right,
     * the counters move, the suite is green, and the only symptom is a
     * frame-time distribution somebody has to run a bench to see. So
     * this leg asserts the claim directly, three ways, in increasing
     * order of how sharp the failure is.</p>
     *
     * <ol>
     * <li><b>Not one walk ran off the worker.</b>
     *     {@code farWalksOffWorker} is incremented at the single walk
     *     entry point whenever the running thread is not
     *     {@code meshelium-pin-extract}. Exact, non-statistical, and it
     *     fires on the first offending walk. This is the assertion that
     *     really guards the architecture; the two below guard its
     *     benefit.</li>
     * <li><b>The per-column GAME-THREAD cost stayed small.</b>
     *     {@code farCaptureNanos / farCaptures} is what the frame's
     *     thread spends per stored column. The far-armed bench measured
     *     the walk this replaced at {@code p50 6.816 ms} and
     *     {@code p99 20.972 ms} over fresh ground, so the threshold here
     *     is set at {@value #CAPTURE_BUDGET_MICROS} us - roughly seven
     *     times the arithmetic estimate for a freeze plus four strips,
     *     and roughly seven times BELOW the walk's own median. That gap
     *     is deliberate: a threshold tight enough to catch jitter would
     *     be a flaky test on a cold JIT in a 2 GB harness JVM, and a
     *     threshold this loose still cannot be passed by anything that
     *     walks a column inline.</li>
     * <li><b>The worst single slice stayed small.</b>
     *     {@code worstOverrunMicros()} is how far the worst frame ran
     *     past its own grant, and it is the stutter the owner actually
     *     feels. Phase 2 measured it at 13.6 ms p95 with a 23 ms worst
     *     slice and could not do better, because its work unit was
     *     bigger than the frame. With the unit at one strip this must
     *     land far below {@value #WORST_OVERRUN_MICROS} us.</li>
     * </ol>
     *
     * <p><b>The vacuity guards, because a green exit here would
     * otherwise be free.</b> Every threshold above is trivially met by a
     * far field that did nothing at all, so the leg first requires real
     * volume on BOTH threads - {@value #PHASE3_MIN_COLUMNS} captures on
     * the game thread and the same number of walks on the worker - and
     * fails with the counters printed if the machine will not produce
     * them inside the timeout. Requiring both is the point: captures
     * without walks is a wedged worker, and walks without captures would
     * mean the numbers came from the leaving path rather than the fill
     * path this leg exists to test.</p>
     */
    private static void assertTheGameThreadStoppedWalking(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        // Through the client executor: the peak gauges are game-thread
        // state, and a test-thread write racing the pump could clear a
        // peak the pump is in the middle of setting. The LongAdders below
        // need no such care.
        context.runOnClient(client -> ExtractDispatch.resetBudgetPeaks());
        long capturesBefore = ExtractDispatch.farCaptures.sum();
        long captureNanosBefore = ExtractDispatch.farCaptureNanos.sum();
        long walksBefore = ExtractDispatch.farExtracts.sum();
        long offWorkerBefore = ExtractDispatch.farWalksOffWorker.sum();

        // Give the fill path something to do that is not the leaving
        // path: two window hops over ground this session has not stored,
        // then a settle. hopTo already waits for streaming to stop, so
        // by the end the FILL queue is the thing that is busy.
        hopTo(context, singleplayer, WALK_STOP_1_X);
        hopTo(context, singleplayer, WALK_STOP_2_X);
        try {
            context.waitFor(client ->
                    ExtractDispatch.farCaptures.sum() - capturesBefore
                            >= PHASE3_MIN_COLUMNS
                    && ExtractDispatch.farExtracts.sum() - walksBefore
                            >= PHASE3_MIN_COLUMNS,
                    DRAW_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("the capture pipeline did not move "
                    + PHASE3_MIN_COLUMNS + " columns on BOTH threads: captures="
                    + (ExtractDispatch.farCaptures.sum() - capturesBefore)
                    + " walks=" + (ExtractDispatch.farExtracts.sum() - walksBefore)
                    + " workerBacklog=" + ExtractDispatch.workerBacklogNow()
                    + " liveCaptures=" + ExtractDispatch.liveCaptureCount()
                    + " jobsQueued=" + ExtractDispatch.jobsQueued()
                    + " workerFullRefusals=" + ExtractDispatch.farCaptureWorkerFull.sum()
                    + " storeErrors=" + FarField.farStoreErrors.sum()
                    + " - captures without walks is a wedged worker; neither is a "
                    + "capture path that never started", t);
        }

        long offWorker = ExtractDispatch.farWalksOffWorker.sum() - offWorkerBefore;
        if (offWorker != 0) {
            throw new AssertionError(offWorker + " shell walk(s) ran on a thread "
                    + "other than the pin worker. T2 Phase 3's whole "
                    + "claim is that the game thread captures and the worker walks; "
                    + "a walk anywhere else is a 3-7 ms uninterruptible step back on "
                    + "the frame's thread, which is the stutter the owner reported");
        }

        long captures = ExtractDispatch.farCaptures.sum() - capturesBefore;
        long captureNanos = ExtractDispatch.farCaptureNanos.sum() - captureNanosBefore;
        long perColumnMicros = captureNanos / Math.max(1L, captures) / 1000L;
        if (perColumnMicros > CAPTURE_BUDGET_MICROS) {
            throw new AssertionError("the game thread spent " + perColumnMicros
                    + " us per stored column, over the " + CAPTURE_BUDGET_MICROS
                    + " us this leg allows (" + captures + " captures, "
                    + captureNanos / 1_000_000L + " ms total). Phase 3's contract is "
                    + "a snapshot on this thread and a walk on the worker; a figure "
                    + "in the milliseconds means something is walking, or reading, "
                    + "live chunk state here again");
        }

        long worstOverrun = context.computeOnClient(
                client -> ExtractDispatch.worstOverrunMicros());
        if (worstOverrun > WORST_OVERRUN_MICROS) {
            throw new AssertionError("the worst frame ran " + worstOverrun
                    + " us past its own far-field grant, over the "
                    + WORST_OVERRUN_MICROS + " us this leg allows. The overshoot is "
                    + "bounded by ONE capture step by construction (finishPin tests "
                    + "the grant between strips), so a figure this large means an "
                    + "uninterruptible multi-millisecond step is back on the game "
                    + "thread. Phase 2 measured 13,600 us here and could not do "
                    + "better, because its work unit was bigger than the frame");
        }
        assertNoErrors();
        // RESTORE THE POSE. Every leg after this one is written against
        // HOME, and the two hops above leave the player 34 chunks away -
        // which is exactly how this leg broke leg (b) on its first real
        // run: the server answered leg (b)'s setblock with "That position
        // is not loaded", no block update was ever sent to the client,
        // and leg (b) reported a dead E4 mixin. A leg that moves the
        // camera owns putting it back, the same way leg (a) restores the
        // render distance before it returns.
        hopTo(context, singleplayer, HOME_X);
    }

    /**
     * Leg 3b's floor for real volume, on BOTH the capture thread and the
     * worker. Small enough that a slow harness machine still reaches it
     * inside the leg's timeout, large enough that the per-column mean
     * below is not one cold-JIT outlier wearing a suit.
     */
    private static final int PHASE3_MIN_COLUMNS = 64;

    /**
     * Leg 3b's ceiling on the per-column GAME-THREAD cost, microseconds.
     * Arithmetic says a freeze is ~7-12 us and four border strips are
     * ~25-80 us each, i.e. ~50-200 us for a column with four stayed-live
     * neighbours; the measured walk this replaced was 6,816 us at the
     * median. 700 sits above the arithmetic worst case and an order of
     * magnitude below the walk, which is the widest gap a threshold can
     * sit in and still mean something.
     */
    private static final long CAPTURE_BUDGET_MICROS = 700L;

    /**
     * Leg 3b's ceiling on the worst single frame's overshoot,
     * microseconds. One capture step is the bound by construction; 2,000
     * allows for a cold strip on a contended harness and still fails
     * against Phase 2's measured 13,600.
     */
    private static final long WORST_OVERRUN_MICROS = 2000L;

    /**
     * Leg 3c - save gametest (b), landed with M3 (docs/unreleased/farfield/FARFIELD-SAVE-DESIGN.md
     * section 10): PLACE A BLOCK AND LEAVE, and the edit reaches the
     * store within {@value #EDIT_TO_STORE_SECONDS} seconds of wall
     * clock. This pins Q4's contract - edit-to-store latency is
     * coalesce (0.25 s) + schedule + write, NEVER a 30-second epoch -
     * and it is the leg that fails if the E4 mixin stops firing, if the
     * EDIT class stops being prompt, or if anyone resurrects a
     * staleness throttle.
     *
     * <p>Mechanism under test, end to end: server {@code setblock} ->
     * {@code ClientboundBlockUpdatePacket} -> client
     * {@code LevelChunk.setBlockState} TAIL (the E4 mixin, which vanilla
     * only reaches for a REAL change) -> the record goes LIVE_DIRTY with
     * an EDIT job -> the teleport drops the chunk -> the drop seam
     * extracts the DIRTY column (or the EDIT job already ran) -> the
     * write acks -> the decoded shell's surface cell at that x,z is the
     * gold block. The old code lost exactly this: the drop seam skipped
     * a column refreshed earlier in the same 30 s epoch.</p>
     *
     * <p><b>What makes this fail:</b> the {@code LevelChunkEditMixin}
     * not firing (wrong target, not registered, the armed gate); the
     * coalesce window never ripening (a stamp bug turns every EDIT
     * unripe forever); the drop seam judging the column by anything
     * other than version arithmetic (the epoch back from the dead); or
     * the store never acking the write. The vacuity guard is the
     * QUIESCE: the leg first waits for the column to be observably
     * CLEAN (arrival FILL acked), so the dirty transition it then
     * asserts can only be E4 - a dead mixin cannot green-exit through
     * the return hop's own re-receive dirt, which is exactly how the
     * first draft of this leg would have passed vacuously.</p>
     *
     * <p>The rapid-edit tail is the coalesce contract under churn: ten
     * alternating edits across two windows must coalesce (the counter
     * moves), still extract (a sliding coalesce stamp would starve the
     * column forever - the review's edit-starvation finding), and the
     * stored surface must end on the burst's LAST state.</p>
     */
    private static void assertEditReachesStoreOnLeave(ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        long lostBefore = ExtractDispatch.farSaveLost.sum();
        long editColumnKey = 0L; // chunk (0, 0): HOME is block (8, 8)
        // POSE FIRST, and pose OURSELVES. This leg is written entirely
        // against HOME - the setblock, the surface cell it decodes, the
        // heightmap y it derives - and until now it INHERITED that pose
        // from whichever leg ran before it. That assumption broke the
        // moment a leg was inserted above it that ends somewhere else:
        // the server refused the setblock with "That position is not
        // loaded", the client never saw a block update, and this leg
        // blamed the E4 mixin. Own the precondition instead of hoping
        // for it. (This hop IS the "return hop" the quiesce below exists
        // to survive, so nothing about the leg's meaning changes - it is
        // guaranteed now rather than assumed.)
        hopTo(context, singleplayer, HOME_X);
        boolean columnHeld = context.computeOnClient(client -> client.level != null
                && client.level.getChunkSource().getChunk(0, 0, false) != null);
        if (!columnHeld) {
            throw new AssertionError("(b): the client does not hold chunk (0,0) after "
                    + "hopping to HOME - every assertion in this leg is about that "
                    + "column, so nothing below would mean anything. Check that the "
                    + "leg before this one did not leave the render distance or the "
                    + "camera somewhere else");
        }
        // QUIESCE, and this is the leg's real vacuity guard (the
        // first draft's "wait until dirty" was itself vacuous: the
        // return hop re-receives chunk (0,0), so the record was ALREADY
        // dirty with a pending FILL and the leg would pass with the E4
        // mixin completely dead). Wait until the arrival's job has run
        // and ACKED - the record observably CLEAN - so the dirty
        // transition asserted below can only be the setblock's E4.
        try {
            context.waitFor(client -> !ExtractDispatch.columnDirty(editColumnKey),
                    FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("(b): column (0,0) never went CLEAN after the "
                    + "return hop - the arrival FILL/ack pipeline is stuck and the "
                    + "E4 assertion below would be unreadable", t);
        }
        // Replace the surface block under the player's feet: feet stand
        // ON the top solid block, so feet-1 is inside the heightmap and
        // the shell's surface cell at (8, 8) must become the gold.
        int goldY = (int) Math.floor(groundY(context)) - 1;
        singleplayer.getServer().runCommand(
                "setblock " + HOME_X + " " + goldY + " " + HOME_Z
                        + " minecraft:gold_block");
        long staleFiledBefore = ExtractDispatch.farExtractStaleFiled.sum();
        long coalescedAtEdit = ExtractDispatch.farSaveEditsCoalesced.sum();
        // STEP 1: did the edit reach the CLIENT at all? That is a
        // question about the server and the wire, not about us, and
        // separating it from step 2 is the difference between "our hook
        // missed a change" and "there was no change to miss". Merging
        // the two is what sent the first Phase 3 failure to the wrong
        // file.
        BlockPos goldPos = new BlockPos(HOME_X, goldY, HOME_Z);
        try {
            context.waitFor(client -> client.level != null
                    && client.level.getBlockState(goldPos).getBlock()
                            == Blocks.GOLD_BLOCK,
                    60);
        } catch (Throwable t) {
            throw new AssertionError("(b): the server was asked to setblock "
                    + "gold_block at " + goldPos + " and the CLIENT never saw it. "
                    + "Nothing here is about the far field: either the command was "
                    + "refused (the server answers 'That position is not loaded' when "
                    + "the player is not near enough to keep the column ticketed - "
                    + "check the pose the previous leg left behind), or the block was "
                    + "already gold and vanilla's own old==new filter ate the change",
                    t);
        }
        // STEP 2: and did OUR hook notice? Dirty within a few ticks from
        // a PROVEN-clean start, with the change PROVEN to have landed on
        // the client, so the only transition that can do this is E4.
        try {
            context.waitFor(client -> ExtractDispatch.columnDirty(editColumnKey), 60);
        } catch (Throwable t) {
            long staleSession = ExtractDispatch.farExtractStaleFiled.sum();
            long staleHere = staleSession - staleFiledBefore;
            throw new AssertionError("(b): the client HAS the gold block at " + goldPos
                    + " on a proven-CLEAN column, and the record never went DIRTY. "
                    + "The change landed, so this one IS ours. Read the counters "
                    + "before picking a file, and read the DELTA rather than the sum "
                    + "- a session total says whether E4 has ever worked, only the "
                    + "delta says whether it worked HERE (the first Phase 3 failure "
                    + "of this leg printed a sum of 1001 earned by earlier legs and "
                    + "read it as evidence about this one). "
                    + "farExtractStaleFiled: session=" + staleSession
                    + " thisEdit=" + staleHere
                    + " - it counts E4 calls landing on an up-to-date column, so a "
                    + "non-zero DELTA means the mixin fired for this very edit and "
                    + "something after it refused to dirty the record (a "
                    + "state-machine guard, an owner check, a version comparison); a "
                    + "zero delta with a non-zero session total means the mixin is "
                    + "alive but was never called for THIS column, which is a world "
                    + "or wire problem and not ours; zero for both, in a world that "
                    + "has been played in, is the mixin itself (wrong target after a "
                    + "vanilla rename, not registered, the armed gate). "
                    + "editsCoalescedHere="
                    + (ExtractDispatch.farSaveEditsCoalesced.sum() - coalescedAtEdit)
                    + " liveCaptures=" + ExtractDispatch.liveCaptureCount()
                    + " workerBacklog=" + ExtractDispatch.workerBacklogNow(), t);
        }
        // Leave: a full window past home, so chunk (0,0) is dropped. The
        // wall clock starts HERE - the contract is edit -> store within
        // N seconds of the player leaving, whatever the tick rate does.
        // (tpTo, not hopTo: the settle loop must not eat the deadline.)
        long deadline = System.nanoTime() + EDIT_TO_STORE_SECONDS * 1_000_000_000L;
        tpTo(singleplayer, WALK_STOP_2_X);
        String last = pollStoreForSurface(context, "minecraft:gold_block", deadline);
        if (last != null) {
            // Game-thread reads for the diagnosis (the record map is
            // game-thread confined; the counters are safe from anywhere).
            // Typed locals rather than inline generics: String.valueOf's
            // overload set breaks inference on a generic argument.
            boolean dirtyNow = context.computeOnClient(
                    client -> ExtractDispatch.columnDirty(editColumnKey));
            long behindNow = context.computeOnClient(
                    client -> ExtractDispatch.farSaveBehind());
            long jobsNow = context.computeOnClient(
                    client -> (long) ExtractDispatch.jobsQueued());
            long pendingNow = context.computeOnClient(
                    client -> (long) ExtractDispatch.editsPending());
            throw new AssertionError("(b): the placed gold block did not reach the "
                    + "store within " + EDIT_TO_STORE_SECONDS + "s of leaving ("
                    + last + "; dirty=" + dirtyNow
                    + " behind=" + behindNow
                    + " jobsQueued=" + jobsNow
                    + " editsPending=" + pendingNow
                    + " ackFailed=" + FarField.farSaveAckFailed.sum()
                    + ") - Q4's edit-to-store contract is broken. dirty with no "
                    + "job, no pending edit and no ack failure means the "
                    + "column's exit event never fired at all");
        }
        long lost = ExtractDispatch.farSaveLost.sum() - lostBefore;
        if (lost != 0) {
            throw new AssertionError("(b): farSaveLost moved by " + lost
                    + " during an ordinary place-and-leave - zero is the contract");
        }
        // Home again, settled, then the RAPID-EDIT case: the coalesce
        // window's contract under churn. The review's finding was that
        // a sliding coalesce stamp starves any column edited more often
        // than the window - the deadline must be fixed from the FIRST
        // unflushed edit - and one placed block cannot catch that.
        hopTo(context, singleplayer, HOME_X);
        try {
            context.waitFor(client -> !ExtractDispatch.columnDirty(editColumnKey),
                    FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("(b): column (0,0) never went CLEAN before the "
                    + "rapid-edit case", t);
        }
        long coalescedBefore = ExtractDispatch.farSaveEditsCoalesced.sum();
        // A burst of alternating edits at one position spanning about
        // two coalesce windows, ending on IRON. Under the fixed
        // file-time deadline this coalesces into a small number of
        // walks whose LAST one carries the final state; under a sliding
        // stamp it would never extract at all and the clean-wait below
        // would time out.
        for (int i = 0; i < 10; i++) {
            // Starts on gold - a no-op over the block already there, so
            // vanilla's own old==new filter eats it - and ENDS on iron
            // at i = 9, which is the state the store must carry.
            String block = (i & 1) == 0 ? "minecraft:gold_block"
                    : "minecraft:iron_block";
            singleplayer.getServer().runCommand("setblock " + HOME_X + " " + goldY
                    + " " + HOME_Z + " " + block);
            context.waitTicks(1);
        }
        long editDeadline = System.nanoTime()
                + EDIT_TO_STORE_SECONDS * 1_000_000_000L;
        try {
            context.waitFor(client -> !ExtractDispatch.columnDirty(editColumnKey),
                    FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("(b): the rapid-edit burst never went CLEAN - "
                    + "an edited-faster-than-the-window column is starving (the "
                    + "sliding-stamp bug) or the EDIT drain is stuck (coalesced="
                    + (ExtractDispatch.farSaveEditsCoalesced.sum() - coalescedBefore)
                    + " jobsQueued=" + context.computeOnClient(
                            client -> (long) ExtractDispatch.jobsQueued())
                    + " editsPending=" + context.computeOnClient(
                            client -> (long) ExtractDispatch.editsPending())
                    + ")", t);
        }
        long coalesced = ExtractDispatch.farSaveEditsCoalesced.sum() - coalescedBefore;
        if (coalesced <= 0) {
            throw new AssertionError("(b): ten edits inside the coalesce window "
                    + "coalesced NOTHING - either every edit extracted (the churn "
                    + "bound is dead) or the E4 hook missed the burst");
        }
        String rapidLast = pollStoreForSurface(context, "minecraft:iron_block",
                editDeadline);
        if (rapidLast != null) {
            throw new AssertionError("(b): after the rapid-edit burst the store's "
                    + "surface cell is not the LAST state (" + rapidLast
                    + ") - coalescing dropped the end of the burst");
        }
        assertNoErrors();
    }

    /**
     * Leg 3c-light - E10, the pre19 S4 pin: A LIGHT SOURCE AT A CHUNK
     * EDGE DIRTIES THE COLUMN NEXT DOOR. The owner's report was "i
     * placed a torch at the edge of a chunk and backed away and while
     * the lod lighting works within its chunk, the next chunk over
     * doesnt have any of that lighting", and the mechanism was that E4
     * marks only the column CONTAINING the changed block: block light
     * reaches 15 and a chunk is 16 wide, so the neighbour's stored
     * plane kept its pre-torch light and nothing was ever going to
     * dirty it.
     *
     * <p>Mechanism under test, end to end: server {@code setblock} of a
     * glowstone at chunk (0,0)'s EAST edge (local x 15) three blocks
     * clear of the ground -> the client's own light engine propagates
     * across the plane and writes light values inside chunk (1,0) ->
     * {@code LayerLightSectionStorage.swapSectionMap} publishes those
     * sections -> {@code ClientChunkCache.onLightUpdate} ->
     * {@code ExtractDispatch.onLightChanged} dirties the NEIGHBOUR's
     * record -> its EDIT re-walks -> the stored plane's block-light
     * nibble is no longer flat zero. Glowstone rather than a torch on
     * purpose: emission 15, no support block, and immune to whatever
     * height the harness world happens to have at x 15.</p>
     *
     * <p><b>The stored-plane contract, and why "it GAINED a plane" is
     * the strongest signal this world offers.</b>
     * {@code ShellExtractor.planeVerdict} drops a finished plane that is
     * uniformly {@code 0xF0} (PLANE_FLAT - "byte-identical to what the
     * mesher synthesizes free"), and the superflat harness world at noon
     * produces exactly that for every settled column. So the neighbour's
     * stored shell legitimately carries NO light plane before the
     * glowstone: that is the correct steady state, not a lighting
     * misconfiguration - Real Light has been the layer-1 default since
     * pre12. A light source one block across the shared plane makes a
     * FALLOFF, which is not uniform, so the plane now survives the
     * verdict and the shell gains one - and its very existence is proof
     * the neighbour was re-walked with light that crossed the chunk
     * plane, which is a sharper statement than any brightness delta.
     * Where the neighbour DID already hold a plane (a world with relief,
     * or an existing light source nearby) the strictly-brighter
     * comparison is sharper still, so the leg keeps both and branches on
     * what the store actually held.</p>
     *
     * <p><b>The vacuity guards, and there are three.</b> Both columns
     * are proven CLEAN first, so the dirty transition asserted can only
     * be the light event (E4 cannot reach (1,0) - no block changed
     * there). {@code farLightCrossChunkDirty} must MOVE, so a future
     * change that dirties the neighbour through some other seam cannot
     * green this leg by accident. And the neighbour must have a stored
     * shell at all - with nothing on disk there is no stale plane to
     * invalidate and the leg could not tell a working mirror from a dead
     * one.</p>
     *
     * <p><b>What makes this fail:</b> {@code ClientChunkCacheLightMixin}
     * not firing (wrong descriptor, not registered, the armed gate);
     * {@code onLightChanged}'s arrival-halo suppression over-reaching
     * and swallowing a real edit; the mirror dirtying the record but
     * the EDIT never ripening; or the walk reading light it cannot see
     * across the plane (the LightView contract's per-source refusal
     * firing where it should not).</p>
     */
    private static void assertTorchLightsTheNeighbourColumn(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        long homeKey = 0L;                 // chunk (0, 0)
        long neighbourKey = 1L << 32;      // chunk (1, 0)
        // QUIESCE BOTH. The neighbour must be observably clean or the
        // transition below proves nothing; the home column must be too,
        // so the glowstone's own E4 is not racing an older job.
        try {
            context.waitFor(client -> !ExtractDispatch.columnDirty(homeKey)
                    && !ExtractDispatch.columnDirty(neighbourKey), FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("(S4): columns (0,0) and (1,0) never went CLEAN "
                    + "together - the E10 assertion below would be unreadable", t);
        }
        // The BEFORE picture of the neighbour's stored light plane.
        ShellCodec.Shell before = readShellBlocking(context, 1, 0);
        if (before == null) {
            throw new AssertionError("(S4): the store has no shell for the neighbour "
                    + "column (1,0) - nothing to invalidate, so the leg cannot "
                    + "distinguish a working mirror from a dead one");
        }
        // NO PLANE IS THE CORRECT STEADY STATE HERE, not a misconfiguration.
        // Layer 1 has defaulted to Real Light since pre12, but the
        // superflat harness world at noon lights every stored cell
        // identically, so the finished plane is uniformly 0xF0 and
        // ShellExtractor.planeVerdict returns PLANE_FLAT - "byte-identical
        // to what the mesher synthesizes free" - and the extractor drops
        // it (cellLight = null). That drop is deliberate and predates this
        // leg. It is also what makes the assertion below STRONGER than a
        // brightness comparison: a uniform plane cannot represent a
        // torch's falloff, so the plane's very EXISTENCE afterwards is
        // proof the neighbour was re-walked with light that crossed the
        // chunk plane. In a world that does keep a plane (real relief, or
        // an existing light source nearby) the strictly-brighter
        // comparison is sharper still, so both are kept and the branch is
        // chosen by what the store actually held.
        boolean hadPlaneBefore = before.cellLight != null
                && before.cellLight.length > 0;
        int blockLightBefore = hadPlaneBefore ? maxEdgeBlockLight(before) : -1;
        long crossBefore = ExtractDispatch.farLightCrossChunkDirty.sum();
        // The light source: chunk (0,0)'s east edge column, three blocks
        // above the player's feet so nothing buries it. Emission 15
        // reaches 14 at x 16 - the neighbour's first column - and the
        // decrement carries it about fourteen blocks in.
        int lightY = (int) Math.floor(groundY(context)) + 3;
        singleplayer.getServer().runCommand(
                "setblock 15 " + lightY + " " + HOME_Z + " minecraft:glowstone");
        // THE PIN. From a proven-clean start, only a cross-chunk light
        // event can dirty (1,0): no block in it changed, so E4 cannot,
        // and no chunk arrived, so E1/E2 cannot.
        try {
            context.waitFor(client -> ExtractDispatch.columnDirty(neighbourKey), 60);
        } catch (Throwable t) {
            throw new AssertionError("(S4): a light source at chunk (0,0)'s east edge "
                    + "did NOT dirty the neighbour column (1,0) - the light-change "
                    + "mirror is not reaching across the chunk plane, which is the "
                    + "owner's \"the next chunk over doesnt have any of that "
                    + "lighting\" exactly (crossChunkDirty="
                    + ExtractDispatch.farLightCrossChunkDirty.sum()
                    + ")", t);
        }
        long crossMoved = ExtractDispatch.farLightCrossChunkDirty.sum() - crossBefore;
        if (crossMoved <= 0) {
            throw new AssertionError("(S4): the neighbour went dirty but "
                    + "farLightCrossChunkDirty did not move - something OTHER than "
                    + "the light mirror dirtied it and this leg is not testing E10");
        }
        // And it must reach the STORE: the re-walk runs, acks, and the
        // shell comes back CARRYING a light plane whose shared-edge
        // column is lit - where before the light was uniform enough that
        // planeVerdict dropped the plane entirely.
        try {
            context.waitFor(client -> !ExtractDispatch.columnDirty(neighbourKey),
                    FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("(S4): the neighbour was dirtied by the light "
                    + "event and never went CLEAN again - the EDIT it filed is not "
                    + "ripening or not draining (jobsQueued="
                    + context.computeOnClient(
                            client -> (long) ExtractDispatch.jobsQueued())
                    + " editsPending=" + context.computeOnClient(
                            client -> (long) ExtractDispatch.editsPending())
                    + ")", t);
        }
        ShellCodec.Shell after = readShellBlocking(context, 1, 0);
        if (after == null) {
            throw new AssertionError("(S4): the neighbour's shell vanished from the "
                    + "store after its light-driven re-extraction");
        }
        if (after.cellLight == null || after.cellLight.length == 0) {
            throw new AssertionError("(S4): the neighbour column was re-extracted and "
                    + "its stored shell STILL carries no light plane. A plane is "
                    + "dropped only when it is uniform (planeVerdict PLANE_FLAT, "
                    + "byte-identical to the mesher's free flat light) or untrusted, "
                    + "and a light source one block across the chunk plane makes a "
                    + "falloff, which is not uniform - so the record was invalidated "
                    + "correctly and the WALK is not reading the light that crossed "
                    + "the plane"
                    + (hadPlaneBefore ? "" : " (it held no plane before either, which "
                            + "IS the correct superflat steady state, not a lighting "
                            + "setting)"));
        }
        int blockLightAfter = maxEdgeBlockLight(after);
        if (blockLightAfter <= 0) {
            throw new AssertionError("(S4): the neighbour gained a light plane but its "
                    + "shared-edge column (local x 0, the one facing the source) reads "
                    + "BLOCK light 0 - the plane came from something other than the "
                    + "light that crossed the chunk plane");
        }
        if (hadPlaneBefore && blockLightAfter <= blockLightBefore) {
            // The stronger comparison, kept for any world whose neighbour
            // already held a plane to compare against.
            throw new AssertionError("(S4): the neighbour column was re-extracted but "
                    + "its stored BLOCK light did not brighten on the shared edge "
                    + "(max nibble " + blockLightBefore + " -> " + blockLightAfter
                    + ") - the record was invalidated correctly and the WALK is not "
                    + "reading the light that crossed the plane");
        }
        // Leave the world as the following legs expect it.
        singleplayer.getServer().runCommand(
                "setblock 15 " + lightY + " " + HOME_Z + " minecraft:air");
        assertNoErrors();
    }

    /**
     * Brightest BLOCK-light nibble among the cells on a shell's WEST
     * edge (local x 0) - the column that faces chunk (0,0)'s east
     * plane, and the one a light source at local x 15 next door reaches
     * first. Narrowed to that column deliberately: a whole-shell
     * maximum would be hostage to whatever lava or glow the harness
     * world happens to have generated somewhere else in the chunk.
     * The plane is parallel to {@code cells} by index (the codec's own
     * contract), so the filter is on the cell and the value is at the
     * same slot.
     */
    private static int maxEdgeBlockLight(ShellCodec.Shell shell) {
        int max = 0;
        byte[] plane = shell.cellLight;
        int n = Math.min(shell.cellCount, plane.length);
        for (int i = 0; i < n; i++) {
            if (ShellCodec.cellX(shell.cells[i]) != 0) {
                continue;
            }
            int block = plane[i] & 0x0F;
            if (block > max) {
                max = block;
            }
        }
        return max;
    }

    /**
     * Leg 3d - save gametest (a), landed with M5 (docs/unreleased/farfield/FARFIELD-SAVE-
     * DESIGN.md section 10): THE RD STORM LOSES NOTHING. Bulk-load a
     * bigger window ({@value #STORM_RD_HIGH}), record every live tracked
     * column into W while the new ring is still draining (so the shrink
     * catches hundreds of DIRTY columns - the population every wave
     * before M5 lost some of), place gold-block witnesses in the doomed
     * ring, then drop the render distance to {@value #STORM_RD_LOW}.
     * That drop rides the integrated server's SetChunkCacheRadius packet
     * into {@code ClientChunkCache.updateViewRadius} - the silent-
     * discard seam that never reached ANY extraction path before Phase 4
     * counted it and M5 captured it - and the annulus becomes E6 pins:
     * chunk refs plus light-layer refs at the seam, walked off-thread by
     * the pin worker, written under their captured versions.
     *
     * <p><b>The contract, as numbers:</b> after the drain quiesces
     * (no pending captures, no pins, no jobs, no ripening edits),
     * {@code farSaveLost} moved ZERO, the GONE_BEHIND gauge moved ZERO,
     * {@code farSaveLeftUnsaved} moved ZERO, and BOTH capture-bound
     * counters ({@code farSaveCaptureSkipped},
     * {@code farSaveLightUncaptured}) moved ZERO - the design's "zero
     * permanently-lost columns, left-unsaved only past the capture
     * bound, and the bound itself asserted". Then the store is read
     * back for EVERY key in W - exact zero-loss, not a ratio - and the
     * gold witnesses' shells must carry the gold at their surface
     * cells, whether their EDIT extracted before the storm or their
     * pin carried it out.</p>
     *
     * <p><b>Vacuity guards:</b> W must be storm-sized (a small W means
     * the bulk load never happened and the leg is testing a breeze),
     * and {@code farSavePins} must MOVE (a zero-pin storm means every
     * column drained before the shrink and the leg proved scheduling,
     * not capture - the wait below is deliberately NOT a quiesce for
     * exactly that reason).</p>
     *
     * <p><b>What makes this fail:</b> the E6 mixin not firing (the
     * repro-(a) silent discard back from the dead); the capture walk
     * skipping cells (its valve counter moves); pins losing their light
     * race (the light valve counter moves); the worker dying (pins
     * never settle, the drain wait times out); the ack path dropping a
     * pinned write (behind/lost move); or the store missing a W key
     * (the exact column is named in the failure).</p>
     */
    private static void assertRdShrinkStormLosesNothing(ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        long lostBefore = ExtractDispatch.farSaveLost.sum();
        long behindBefore = context.computeOnClient(
                client -> ExtractDispatch.farSaveBehind());
        long leftBefore = ExtractDispatch.farSaveLeftUnsaved.sum();
        long skippedBefore = ExtractDispatch.farSaveCaptureSkipped.sum();
        long lightSkippedBefore = ExtractDispatch.farSaveLightUncaptured.sum();
        long pinsBefore = ExtractDispatch.farSavePins.sum();
        long tripwireBefore = ExtractDispatch.farSavePinTripwire.sum();
        // BULK LOAD: the ring 8..16 streams in and its FILL backlog is
        // deliberately left draining - the storm must catch dirt.
        setRenderDistanceLikeTheUi(context, STORM_RD_HIGH);
        settleLoadedChunks(context);
        // Gold witnesses in the doomed ring, one column each, real path
        // (server setblock -> block-update packet -> E4).
        int goldY = (int) Math.floor(groundY(context)) - 1;
        int[] goldChunks = {10, 12, 14};
        for (int cx : goldChunks) {
            singleplayer.getServer().runCommand("setblock " + (cx * 16 + 8)
                    + " " + goldY + " " + HOME_Z + " minecraft:gold_block");
        }
        context.waitTicks(2); // the updates land; whether their EDITs
                              // run before the shrink is immaterial -
                              // gold survives either as a live write or
                              // inside the pin's captured chunk
        long[] window = context.computeOnClient(
                client -> ExtractDispatch.liveColumnKeys());
        if (window.length < 600) {
            throw new AssertionError("(a): only " + window.length + " live tracked "
                    + "columns after the bulk load to rd " + STORM_RD_HIGH
                    + " - W is not storm-sized and the leg would prove nothing");
        }
        // THE STORM: SetChunkCacheRadius -> updateViewRadius -> E6.
        setRenderDistanceLikeTheUi(context, STORM_RD_LOW);
        // Drain: captures triaged, pins walked off-thread, writes acked,
        // the kept window's own FILL backlog flushed.
        try {
            // Phase 3 added two more places a column can be in flight,
            // and BOTH are invisible to the four terms this leg used to
            // wait on. A frozen-but-unfinished capture sits on the live
            // capture list (not a job queue, not a pin); a finished one
            // sits in the worker's queue with its record still
            // LIVE_DIRTY (not a job queue, not a pin, no PINNED state).
            // Without these terms the drain can report DONE while
            // columns are still on their way to the store, and the
            // readback below would then name them as LOST - a false
            // accusation, and the worst kind, because it points at the
            // save path when the leg simply did not wait.
            context.waitFor(client -> ExtractDispatch.pendingCaptureCount() == 0
                    && ExtractDispatch.pinnedCount() == 0
                    && ExtractDispatch.liveCaptureCount() == 0
                    && ExtractDispatch.workerBacklogNow() == 0
                    && ExtractDispatch.jobsQueued() == 0
                    && ExtractDispatch.editsPending() == 0, FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("(a): the storm never drained (liveCaptures="
                    + context.computeOnClient(client ->
                            (long) ExtractDispatch.liveCaptureCount())
                    + " workerBacklog=" + context.computeOnClient(client ->
                            (long) ExtractDispatch.workerBacklogNow())
                    + " captures="
                    + context.computeOnClient(client ->
                            (long) ExtractDispatch.pendingCaptureCount())
                    + " pinned=" + context.computeOnClient(client ->
                            (long) ExtractDispatch.pinnedCount())
                    + " jobs=" + context.computeOnClient(client ->
                            (long) ExtractDispatch.jobsQueued())
                    + " ackFailed=" + FarField.farSaveAckFailed.sum()
                    + " storeErrors=" + FarField.farStoreErrors.sum()
                    + " pinCapture[" + context.computeOnClient(client ->
                            ExtractDispatch.pinCaptureNanosSummary())
                    + "]) - the pin worker is stuck, dead, or starved", t);
        }
        long pins = ExtractDispatch.farSavePins.sum() - pinsBefore;
        if (pins <= 0) {
            throw new AssertionError("(a): the rd " + STORM_RD_HIGH + " -> "
                    + STORM_RD_LOW + " storm pinned NOTHING - either E6 never "
                    + "fired (the repro-(a) silent discard) or the window was "
                    + "already clean and the leg proved scheduling, not capture");
        }
        long lost = ExtractDispatch.farSaveLost.sum() - lostBefore;
        long behind = context.computeOnClient(
                client -> ExtractDispatch.farSaveBehind()) - behindBefore;
        long left = ExtractDispatch.farSaveLeftUnsaved.sum() - leftBefore;
        long skipped = ExtractDispatch.farSaveCaptureSkipped.sum() - skippedBefore;
        long lightSkipped = ExtractDispatch.farSaveLightUncaptured.sum()
                - lightSkippedBefore;
        long tripwire = ExtractDispatch.farSavePinTripwire.sum() - tripwireBefore;
        if (lost != 0 || behind != 0 || left != 0 || skipped != 0
                || lightSkipped != 0 || tripwire != 0) {
            // The per-pin capture histogram rides every ledger failure:
            // the FIRST real run of this leg had to be diagnosed by
            // counter arithmetic (captureSkipped=521 + lightUncaptured=0
            // acquitted the E6 valves and convicted the deleted seam-time
            // strip arm); the next failure names the measured cost
            // directly instead of leaving it to inference.
            throw new AssertionError("(a): the storm's loss ledger is not zero "
                    + "(lost=" + lost + " behind=" + behind + " leftUnsaved=" + left
                    + " captureSkipped=" + skipped
                    + " lightUncaptured=" + lightSkipped
                    + " tripwire=" + tripwire + " over " + pins
                    + " pins; pinCapture[" + context.computeOnClient(client ->
                            ExtractDispatch.pinCaptureNanosSummary())
                    + "]) - zero is the contract, and the two skip "
                    + "counters ARE the capture bound's assertion");
        }
        // Exact zero-loss: the store answers for EVERY column of W.
        java.util.List<String> missing = readMissingColumns(context, window);
        if (!missing.isEmpty()) {
            throw new AssertionError("(a): " + missing.size() + " of "
                    + window.length + " storm-window columns have NO store "
                    + "record after the drain (first: " + missing.get(0)
                    + ") - the counters said nothing was lost and the disk "
                    + "disagrees");
        }
        // The witnesses: content, not just presence.
        long contentDeadline = System.nanoTime()
                + EDIT_TO_STORE_SECONDS * 1_000_000_000L;
        for (int cx : goldChunks) {
            String last = pollStoreForSurfaceAt(context, cx, 0, 8, HOME_Z & 15,
                    "minecraft:gold_block", contentDeadline);
            if (last != null) {
                throw new AssertionError("(a): the gold witness in chunk (" + cx
                        + ", 0) did not survive the storm (" + last
                        + ") - the pin carried out stale or empty truth");
            }
        }
        // Restore the suite's baseline window for the legs behind us.
        setRenderDistanceLikeTheUi(context, FAR_RD);
        settleLoadedChunks(context);
        assertNoErrors();
    }

    /**
     * Batch store readback: which of these columns does the store MISS?
     * All reads are fired in one client tick (the read queue's 4,096 cap
     * comfortably holds a storm window) and answered on the IO thread;
     * the misses collect concurrently.
     */
    private static java.util.List<String> readMissingColumns(
            ClientGameTestContext context, long[] keys) {
        java.util.concurrent.atomic.AtomicInteger answered =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ConcurrentLinkedQueue<String> missing =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        context.runOnClient(client -> {
            for (long key : keys) {
                int cx = (int) (key >> 32);
                int cz = (int) key;
                FarField.requestShell(cx, cz, shell -> {
                    if (shell == null) {
                        missing.add(cx + "," + cz);
                    }
                    answered.incrementAndGet();
                });
            }
        });
        context.waitFor(client -> answered.get() >= keys.length, FAR_TIMEOUT_TICKS);
        return new java.util.ArrayList<>(missing);
    }

    /**
     * One store read, blocking on the IO round trip: the shell for
     * (cx, cz), or null on a MISS. The read is FIFO on the one IO
     * thread, so it observes every write and clear queued before it.
     */
    private static ShellCodec.Shell readShellBlocking(ClientGameTestContext context,
            int cx, int cz) {
        java.util.concurrent.atomic.AtomicBoolean answered =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<ShellCodec.Shell> read =
                new java.util.concurrent.atomic.AtomicReference<>();
        context.runOnClient(client -> FarField.requestShell(cx, cz, shell -> {
            read.set(shell);
            answered.set(true);
        }));
        context.waitFor(client -> answered.get(), DRAW_TIMEOUT_TICKS);
        return read.get();
    }

    /**
     * Poll-decode column (0,0)'s stored shell until its TOPMOST cell at
     * HOME's local (x, z) is {@code expected}, or the wall-clock
     * deadline passes.
     *
     * @return null on success; on timeout, a description of the last
     *         answer the store gave
     */
    private static String pollStoreForSurface(ClientGameTestContext context,
            String expected, long deadlineNanos) {
        return pollStoreForSurfaceAt(context, 0, 0, HOME_X & 15, HOME_Z & 15,
                expected, deadlineNanos);
    }

    /** {@link #pollStoreForSurface} for any column and local cell. */
    private static String pollStoreForSurfaceAt(ClientGameTestContext context,
            int chunkX, int chunkZ, int localX, int localZ,
            String expected, long deadlineNanos) {
        String last = "no shell decoded yet";
        while (System.nanoTime() < deadlineNanos) {
            java.util.concurrent.atomic.AtomicBoolean answered =
                    new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<ShellCodec.Shell> read =
                    new java.util.concurrent.atomic.AtomicReference<>();
            context.runOnClient(client -> FarField.requestShell(chunkX, chunkZ,
                    shell -> {
                        read.set(shell);
                        answered.set(true);
                    }));
            context.waitFor(client -> answered.get(), DRAW_TIMEOUT_TICKS);
            ShellCodec.Shell shell = read.get();
            if (shell == null) {
                last = "store still has no record for (" + chunkX + "," + chunkZ + ")";
            } else {
                // The SURFACE cell at the local column: the topmost cell.
                int topY = Integer.MIN_VALUE;
                int topPalette = -1;
                for (int i = 0; i < shell.cellCount; i++) {
                    int cell = shell.cells[i];
                    if (ShellCodec.cellX(cell) == localX
                            && ShellCodec.cellZ(cell) == localZ
                            && ShellCodec.cellYRel(cell) > topY) {
                        topY = ShellCodec.cellYRel(cell);
                        topPalette = ShellCodec.cellPaletteIndex(cell);
                    }
                }
                String block = topPalette < 0
                        ? "<no cell at " + localX + "," + localZ + ">"
                        : shell.palette[topPalette];
                if (expected.equals(block)) {
                    return null;
                }
                last = "surface cell at (" + localX + "," + localZ + ") is " + block;
            }
            context.waitTicks(5);
        }
        return last;
    }

    /** Gametest (b)'s N: the design of record names ten seconds of wall clock. */
    private static final long EDIT_TO_STORE_SECONDS = 10L;

    /** Columns the M1 leg floods the write queue with; must beat the
     * 512-entry cap by enough that the IO thread cannot drain past it
     * mid-burst (game-thread enqueues are microseconds, region-file
     * appends are not). */
    private static final int ACK_FLOOD_COLUMNS = 1600;
    /** Chunk X of the flood keys: nowhere near any other leg's terrain. */
    private static final int ACK_FLOOD_X = 60000;

    /**
     * Leg 3b - save M1 (docs/unreleased/farfield/FARFIELD-SAVE-DESIGN.md): forced queue
     * eviction, and the VICTIM, not the submitter, is the column that
     * re-queues. Floods the write queue far past {@code WRITE_QUEUE_CAP}
     * in one game-thread burst, so the eviction arm must fire, then
     * asserts the acknowledgement ledger end to end:
     * <ul>
     *   <li>every submit is acked, exactly once (ok + failed == flood);</li>
     *   <li>failures happened (a flood that all landed proves nothing);</li>
     *   <li>the pump attributed EVERY failure
     *       ({@code farExtractWriteLost} delta == failed delta - the
     *       drain consumed each ack and un-remembered its exact key);</li>
     *   <li>the LAST submitted column is readable from the store. Under
     *       the deleted delta heuristic this is the column that would
     *       have been blamed - its submit is what tripped the eviction
     *       counter - while the real victims stayed marked saved. Acks
     *       invert that: the newest write survives (nothing later ever
     *       evicts it) and the oldest keys carry the failures;</li>
     *   <li>the loss ring does not name the last column (the submitter
     *       was not blamed).</li>
     * </ul>
     * Runs while the far field is armed with a store open; the junk
     * records it writes sit 60,000 chunks from anything the other legs
     * look at and die with the clear-cache leg.
     */
    private static void assertWriteAckAttribution(ClientGameTestContext context) {
        long okBefore = FarField.farSaveAckOk.sum();
        long failedBefore = FarField.farSaveAckFailed.sum();
        long lostBefore = ExtractDispatch.farExtractWriteLost.sum();

        // One legal single-cell shell, reused: encode() only reads it.
        int[] cells = {ShellCodec.packCell(0, 0, 0, 0, 1)};
        ShellCodec.Shell shell = new ShellCodec.Shell(1, cells,
                new String[] {"minecraft:stone"}, (byte) ShellCodec.TIER_VISITED,
                ShellCodec.FORMAT_VERSION, (byte) 8, (short) -64);
        context.runOnClient(client -> {
            for (int z = 0; z < ACK_FLOOD_COLUMNS; z++) {
                FarField.submitShell(ACK_FLOOD_X, z, ShellCodec.TIER_VISITED,
                        shell, 1_000_000L + z);
            }
        });

        try {
            context.waitFor(client -> FarField.farSaveAckOk.sum()
                    + FarField.farSaveAckFailed.sum()
                    - okBefore - failedBefore >= ACK_FLOOD_COLUMNS, FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("M1: " + ACK_FLOOD_COLUMNS + " submits produced only "
                    + (FarField.farSaveAckOk.sum() - okBefore) + " ok + "
                    + (FarField.farSaveAckFailed.sum() - failedBefore)
                    + " failed acks - a write-path exit is not acknowledging", t);
        }
        long failedDelta = FarField.farSaveAckFailed.sum() - failedBefore;
        if (failedDelta <= 0) {
            throw new AssertionError("M1: flooding " + ACK_FLOOD_COLUMNS
                    + " writes past the 512 cap evicted NOTHING (ackFailed flat) - the "
                    + "leg is vacuous; raise ACK_FLOOD_COLUMNS or the eviction arm broke");
        }
        try {
            context.waitFor(client -> ExtractDispatch.farExtractWriteLost.sum()
                    - lostBefore >= failedDelta, DRAW_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("M1: the store acked " + failedDelta
                    + " losses but the pump attributed only "
                    + (ExtractDispatch.farExtractWriteLost.sum() - lostBefore)
                    + " (farExtractWriteLost) - drainWriteAcks is not consuming", t);
        }

        // The submitter survives; the victims were the OLDEST writes.
        long lastKey = (((long) ACK_FLOOD_X) << 32)
                | ((ACK_FLOOD_COLUMNS - 1) & 0xFFFFFFFFL);
        for (long lossKey : ExtractDispatch.recentWriteLossKeys()) {
            if (lossKey == lastKey) {
                throw new AssertionError("M1: the loss ring names the LAST submitted "
                        + "column - the ack blamed the submitter, which is exactly the "
                        + "delta heuristic's misattribution this change deletes");
            }
        }
        java.util.concurrent.atomic.AtomicBoolean answered =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<ShellCodec.Shell> readBack =
                new java.util.concurrent.atomic.AtomicReference<>();
        context.runOnClient(client -> FarField.requestShell(ACK_FLOOD_X,
                ACK_FLOOD_COLUMNS - 1, result -> {
                    readBack.set(result);
                    answered.set(true);
                }));
        context.waitFor(client -> answered.get(), DRAW_TIMEOUT_TICKS);
        if (readBack.get() == null) {
            throw new AssertionError("M1: the LAST submitted column is not in the store. "
                    + "The newest write can never be the eviction victim (only later "
                    + "writes evict, and none came), so its ack lied or the write died");
        }
        if (FarField.farStoreErrors.sum() != 0) {
            throw new AssertionError("M1: the flood raised farStoreErrors="
                    + FarField.farStoreErrors.sum()
                    + " - eviction is policy and must never count as an error");
        }
    }

    /**
     * Leg 4: the one that proves the feature. The walk above cached a
     * corridor of chunks running east from spawn; the camera comes home,
     * climbs 40 blocks and looks east along it with the near render
     * distance at {@value #FAR_RD} chunks and the far radius at
     * {@value #FAR_L1_HIGH}, so everything past 128 blocks in that
     * direction can only be on screen because the far field put it
     * there.
     *
     * <p>Three screenshots, all from the same position:</p>
     * <ul>
     * <li>{@code C0_meshelium_farfield_off} and
     *     {@code C1_meshelium_farfield_on}, the same pose with the master
     *     switch flipped between them. The pair is EXPECTED to differ:
     *     the ground should run visibly further east in C1, and the fog
     *     should be pushed out with it.</li>
     * <li>{@code C2_meshelium_farfield_sparse}, turned 90 degrees north
     *     where the walk cached NOTHING. The fog push-out is global, so a
     *     direction with no cached shells shows the near horizon ending
     *     early under a widened fog. That is the pre1 artifact the owner
     *     needs to see before deciding how the fog should behave, and it
     *     is a screenshot precisely because no assertion can judge
     *     it.</li>
     * </ul>
     *
     * <p><b>What makes this fail:</b> nothing resident (the promote
     * walker never arming, the ring maths putting the corridor outside
     * [rd+1, L1], the store read missing, the shell decoding to nothing,
     * the palette resolving to KIND_MISSING for grass so every cell is
     * skipped, the mesher producing zero sections, or the region/arena
     * gate refusing every admission); nothing admitted (a residency
     * admission path that reports OK without ever adding); and the OFF
     * shot is guarded too, so a master switch that no longer DRAINS
     * fails here rather than producing an identical pair of PNGs that
     * look like a working A/B.</p>
     */
    private static void assertFarDrawBeyondRenderDistance(ClientGameTestContext context,
            TestSingleplayerContext singleplayer, double cameraY) {
        lookEast(singleplayer, cameraY);
        context.waitTicks(40);

        try {
            context.waitFor(client -> FarWalkerProbe.resident() > 0
                    && FarWalkerProbe.admissions() > 0, FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("no far section ever became resident: "
                    + FarWalkerProbe.describe() + " store reads="
                    + FarField.farStoreReads.sum() + " writes="
                    + FarField.farStoreWrites.sum(), t);
        }
        long resident = settleFarResident(context);
        if (resident <= 0) {
            throw new AssertionError("far sections became resident and then all left again "
                    + "before the evidence screenshot: " + FarWalkerProbe.describe());
        }
        assertNoErrors();

        // --- the OFF half of the pair: drain, then shoot ---
        armFarField(context, false);
        try {
            context.waitFor(client -> FarWalkerProbe.resident() == 0, FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("turning the master switch off left "
                    + FarWalkerProbe.resident() + " far sections drawing - the walker's "
                    + "disarmed branch is not draining, so the OFF screenshot would not be "
                    + "an OFF screenshot", t);
        }
        context.waitTicks(20);
        context.takeScreenshot(TestScreenshotOptions.of("C0_meshelium_farfield_off"));

        // --- the ON half, identical pose ---
        armFarField(context, true);
        try {
            context.waitFor(client -> FarWalkerProbe.resident() > 0, FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("the far field did not come back after being toggled "
                    + "off and on again: " + FarWalkerProbe.describe(), t);
        }
        settleFarResident(context);
        context.takeScreenshot(TestScreenshotOptions.of("C1_meshelium_farfield_on"));
        assertNoErrors();

        // --- the sparse direction, same spot, nothing cached north ---
        singleplayer.getServer().runCommand("execute as @p at @s run tp @s "
                + HOME_X + " " + formatY(cameraY) + " " + HOME_Z + " 180 " + CAMERA_PITCH);
        context.waitTicks(60);
        context.takeScreenshot(TestScreenshotOptions.of("C2_meshelium_farfield_sparse"));
        assertNoErrors();

        lookEast(singleplayer, cameraY);
        context.waitTicks(40);
    }

    /** Chebyshev candidate band for the replace leg: past the client's
     * held window (rd+3, so no extraction path can re-save the column
     * underneath the leg) and short of L1's outer demote band. */
    private static final int REPLACE_MIN_RING = FAR_RD + 5;
    private static final int REPLACE_MAX_RING = FAR_L1_HIGH - 2;

    /**
     * Leg 4b - SEAM step 2's gametest leg A (docs/unreleased/farfield/FARFIELD-SEAM-DESIGN.md
     * section 6): the edit-a-drawn-column scenario. A fresher shell
     * submitted for a column the far field is ALREADY DRAWING must be an
     * atomic REPLACEMENT - meshed, then swapped against the resident
     * sections inside one residency lock hold under one draw-epoch bump -
     * never the old release-then-refill hop ({@code releaseStaleResident},
     * deleted), which un-drew the column for the whole store round trip.
     *
     * <p>Mechanics: pick a RESIDENT far column beyond the client's held
     * window, read its shell back from the store, submit a legally
     * mutated copy (every other cell dropped - a perforated lattice no
     * greedy merge can reproduce, so the geometry is guaranteed to
     * differ) at the same truth tier, and then poll BETWEEN TICKS until
     * the swap lands. What is asserted:</p>
     * <ul>
     *   <li>the column is NEVER absent from the draw set across the
     *       exchange - both the residency's bound-section count and the
     *       walker's manifest stay nonzero on every poll (the pre-step-2
     *       code fails this on the first pump after the submit, because
     *       the release ran before the re-read was even issued);</li>
     *   <li>the swap really happened: {@code ledgerResolvedSwap} moved
     *       and the column's resident quad total CHANGED (a skip-only
     *       pass would leave it byte-identical);</li>
     *   <li>the manifest holds NO duplicate sections afterwards -
     *       manifest size equals bound-section count exactly, which is
     *       the {@code retireColumn existing.addAll} hazard the
     *       per-section merge closes (a duplicate shows as manifest
     *       &gt; bound and would have released the section twice on
     *       demote);</li>
     *   <li>{@code ledgerIllegalTransitions} is still zero - the
     *       FAR-&gt;FAR-via-replacement exchange stays inside the legal
     *       table, which is the step's acceptance gate.</li>
     * </ul>
     *
     * <p><b>The pre19 half - APRON STALENESS</b> (docs/unreleased/farfield/FARFIELD-WAVES.md,
     * "APRON STALENESS ANSWERED"). Since pre18 a column's mesh is a
     * function of its EIGHT NEIGHBOURS' stored contents as well as its
     * own, so the same submit that makes this column's mesh stale makes
     * every resident neighbour's mesh stale along their shared plane -
     * and pre18 refreshed only the written column. Three more
     * assertions, all hung off the write this leg was already making:</p>
     * <ul>
     *   <li>the fanout FIRED, measured across the {@code submitShell}
     *       call itself - {@code onShellWritten} is synchronous on the
     *       game thread, so the {@code farApronRefreshFiled} delta over
     *       that one call is attributable to this write and not to the
     *       background write stream;</li>
     *   <li>the drain ISSUED - {@code farApronRefreshIssued} moves. A
     *       set that fills and never drains looks identical from
     *       outside, and this drain is capped per pump and sequenced
     *       behind the direct refreshes, so it has three ways to be
     *       silently dead;</li>
     *   <li>the NEIGHBOUR never leaves the draw set either. A neighbour
     *       re-mesh takes the identical {@code issueRequest(key, true)}
     *       path, so the replacement invariant covers it by
     *       construction - and this is what would catch anyone
     *       re-introducing a release on that path, on a column the
     *       player never edited.</li>
     * </ul>
     *
     * <p>Deliberately NOT asserted: that the neighbour's GEOMETRY
     * changed. The harness world is superflat, where a boundary top face
     * has no occluders above it and uniform sky light on both sides, so
     * the neighbour's mesh can legitimately come back byte-identical -
     * asserting a quad delta there would be a coin flip
     * ([[superflat harness blind spot]], a sixth channel). The
     * seam VALUE is desk-checked in "S3 AND S5 ANSWERED" desk-check 3;
     * what this leg pins is the INVALIDATION, which is the half that was
     * missing.</p>
     *
     * <p><b>What makes this fail:</b> reverting to release-first (the
     * continuity poll trips immediately); a refresh admission skipped as
     * already-ours (swap counter flat, quads unchanged - the timeout
     * names both); the manifest double-entering a replaced section
     * (manifest &gt; bound); the swap arm freeing the wrong resident or
     * missing its ledger mirror (illegal transitions nonzero, or the
     * residency latches and {@code assertNoErrors} trips);
     * {@code fileApronNeighbors} filing nothing, or filing and never
     * draining.</p>
     */
    private static void assertReplaceInPlaceOnFresherShell(ClientGameTestContext context) {
        // --- 1. a drawn, wanted column, past the held window ---
        java.util.List<long[]> candidates = context.computeOnClient(client -> {
            java.util.List<long[]> found = new ArrayList<>();
            var snap = com.deds.meshelium.farfield.FarFieldResidency.walkerSnapshot();
            if (!snap.armed()) {
                return found;
            }
            for (int dx = REPLACE_MIN_RING; dx <= REPLACE_MAX_RING; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int cx = snap.cameraChunkX() + dx;
                    int cz = snap.cameraChunkZ() + dz;
                    int state = com.deds.meshelium.farfield.FarFieldResidency
                            .classifyColumn(cx, cz);
                    if (state == com.deds.meshelium.farfield.FarFieldResidency.COL_RESIDENT
                            && com.deds.meshelium.farfield.FarFieldResidency
                                    .residentSectionCount(cx, cz) > 0) {
                        found.add(new long[] {cx, cz});
                        if (found.size() >= 8) {
                            return found;
                        }
                    }
                }
            }
            return found;
        });
        if (candidates.isEmpty()) {
            throw new AssertionError("replace-in-place: no RESIDENT far column in the "
                    + "corridor band [" + REPLACE_MIN_RING + ".." + REPLACE_MAX_RING
                    + "] east of the camera - " + FarWalkerProbe.describe());
        }

        // --- 2. read a shell back and mutate it (half the cells) ---
        int cx = 0;
        int cz = 0;
        ShellCodec.Shell original = null;
        for (long[] candidate : candidates) {
            int candX = (int) candidate[0];
            int candZ = (int) candidate[1];
            java.util.concurrent.atomic.AtomicBoolean answered =
                    new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<ShellCodec.Shell> read =
                    new java.util.concurrent.atomic.AtomicReference<>();
            context.runOnClient(client -> FarField.requestShell(candX, candZ, shell -> {
                read.set(shell);
                answered.set(true);
            }));
            context.waitFor(client -> answered.get(), DRAW_TIMEOUT_TICKS);
            ShellCodec.Shell shell = read.get();
            if (shell != null && shell.cellCount >= 8) {
                cx = candX;
                cz = candZ;
                original = shell;
                break;
            }
        }
        if (original == null) {
            throw new AssertionError("replace-in-place: none of " + candidates.size()
                    + " resident candidate columns read back a usable shell (a resident "
                    + "column's record cannot be absent) - " + FarWalkerProbe.describe());
        }
        // Keep every OTHER cell, not the first half: a superflat surface
        // greedy-merges, and a contiguous half-plane can merge to the
        // same quad count as the full plane (the harness blind-spot
        // lesson - every face here is a top face). A perforated lattice
        // cannot: its quad count differs from any merged plane's.
        int keptCells = original.cellCount / 2;
        int[] keptLattice = new int[keptCells];
        byte[] keptLight = original.cellLight == null ? null : new byte[keptCells];
        for (int i = 0; i < keptCells; i++) {
            keptLattice[i] = original.cells[i * 2];
            if (keptLight != null) {
                keptLight[i] = original.cellLight[i * 2];
            }
        }
        ShellCodec.Shell mutated = new ShellCodec.Shell(keptCells, keptLattice,
                original.palette, original.paletteState, original.paletteStateSig,
                original.paletteTint, original.tintGridSide, keptLight,
                original.tier, original.formatVersion, original.bandOffset,
                original.minY);

        // --- 2b. APRON STALENESS (pre19): a RESIDENT NEIGHBOUR of it ---
        // Since pre18 the mesher reads one cell into each of the eight
        // neighbours, so this write makes THEIR meshes stale too. Pick
        // one and hold it to the same continuity contract as the edited
        // column itself.
        final int chosenX = cx;
        final int chosenZ = cz;
        long[] neighbour = context.computeOnClient(client -> {
            int[] ring = {1, 0, -1, 0, 0, 1, 0, -1, 1, 1, -1, -1, 1, -1, -1, 1};
            for (int i = 0; i < ring.length; i += 2) {
                int nx = chosenX + ring[i];
                int nz = chosenZ + ring[i + 1];
                if (com.deds.meshelium.farfield.FarFieldResidency.classifyColumn(nx, nz)
                        == com.deds.meshelium.farfield.FarFieldResidency.COL_RESIDENT
                        && com.deds.meshelium.farfield.FarFieldResidency
                                .residentSectionCount(nx, nz) > 0
                        && TerrainResidency.farResidentSectionsAt(nx, nz) > 0) {
                    return new long[] {nx, nz};
                }
            }
            return new long[0]; // "none", without handing the harness a null
        });
        if (neighbour.length != 2) {
            throw new AssertionError("apron staleness: no RESIDENT neighbour of the "
                    + "chosen column " + cx + "," + cz + " - the pre19 half of this leg "
                    + "cannot observe anything, and a lone resident column in a "
                    + "corridor of eight candidates means the ring is far emptier than "
                    + "this leg assumes - " + FarWalkerProbe.describe());
        }
        final int nbX = (int) neighbour[0];
        final int nbZ = (int) neighbour[1];

        // --- 3. the before-picture, then the fresher shell ---
        final int colX = cx;
        final int colZ = cz;
        long swapsBefore = TerrainResidency.ledgerResolvedSwap();
        long quadsBefore = context.computeOnClient(client ->
                TerrainResidency.farResidentQuadsAt(colX, colZ));
        int boundBefore = context.computeOnClient(client ->
                TerrainResidency.farResidentSectionsAt(colX, colZ));
        if (quadsBefore <= 0 || boundBefore <= 0) {
            throw new AssertionError("replace-in-place: chose column " + colX + "," + colZ
                    + " but the residency binds " + boundBefore + " sections / "
                    + quadsBefore + " quads there - candidate scan and residency disagree");
        }
        final ShellCodec.Shell fresher = mutated;
        long apronIssuedBefore = com.deds.meshelium.farfield.FarFieldResidency
                .farApronRefreshIssued.sum();
        // APRON STALENESS: the fanout is SYNCHRONOUS inside submitShell
        // (onShellWritten runs on the game thread at enqueue time), so
        // sampling either side of this one call attributes the filed
        // count to THIS write and to no other - which is what makes the
        // assertion below a pin rather than a global drift reading.
        long apronFiled = context.computeOnClient(client -> {
            long before = com.deds.meshelium.farfield.FarFieldResidency
                    .farApronRefreshFiled.sum();
            FarField.submitShell(colX, colZ, fresher.tier, fresher, 7_000_000L);
            return com.deds.meshelium.farfield.FarFieldResidency
                    .farApronRefreshFiled.sum() - before;
        });
        if (apronFiled <= 0) {
            throw new AssertionError("apron staleness: rewriting column " + colX + ","
                    + colZ + " filed ZERO neighbour re-meshes, with " + nbX + "," + nbZ
                    + " resident and drawing right beside it - fileApronNeighbors is "
                    + "not running, and every resident neighbour of every edited "
                    + "column keeps a mesh built from the OLD record across its shared "
                    + "chunk plane (the pre18 accepted bound, which S5's per-corner "
                    + "light turned into a visible step)");
        }

        // --- 4. the exchange, polled between ticks: never absent ---
        java.util.concurrent.atomic.AtomicReference<String> torn =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            context.waitFor(client -> {
                int bound = TerrainResidency.farResidentSectionsAt(colX, colZ);
                int manifest = com.deds.meshelium.farfield.FarFieldResidency
                        .residentSectionCount(colX, colZ);
                if (bound <= 0 || manifest <= 0) {
                    torn.compareAndSet(null, "column " + colX + "," + colZ
                            + " left the draw set mid-exchange (bound=" + bound
                            + " manifest=" + manifest + ") - the replace-in-place "
                            + "invariant is violated, this is the releaseStaleResident "
                            + "tear-out back from the dead");
                    return true; // stop waiting; the throw below names it
                }
                // APRON STALENESS: the NEIGHBOUR is under the same
                // contract. Its re-mesh goes through the identical
                // issueRequest(key, true) -> refresh admission, so a
                // neighbour that blinks out here would mean the seam fix
                // reintroduced the release-then-refill hop by the back
                // door - on a column the player never even edited.
                int nbBound = TerrainResidency.farResidentSectionsAt(nbX, nbZ);
                int nbManifest = com.deds.meshelium.farfield.FarFieldResidency
                        .residentSectionCount(nbX, nbZ);
                if (nbBound <= 0 || nbManifest <= 0) {
                    torn.compareAndSet(null, "the NEIGHBOUR column " + nbX + "," + nbZ
                            + " left the draw set while its apron re-mesh was in "
                            + "flight (bound=" + nbBound + " manifest=" + nbManifest
                            + ") - a neighbour refresh must be a REPLACEMENT, never a "
                            + "release");
                    return true;
                }
                return TerrainResidency.ledgerResolvedSwap() > swapsBefore
                        && TerrainResidency.farResidentQuadsAt(colX, colZ) != quadsBefore;
            }, FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("replace-in-place: the fresher shell for "
                    + colX + "," + colZ + " never swapped in (ledgerResolvedSwap "
                    + TerrainResidency.ledgerResolvedSwap() + " vs " + swapsBefore
                    + " before, quads " + TerrainResidency.farResidentQuadsAt(colX, colZ)
                    + " vs " + quadsBefore + " before) - "
                    + FarWalkerProbe.describe(), t);
        }
        if (torn.get() != null) {
            throw new AssertionError("replace-in-place: " + torn.get());
        }

        // --- 4b. APRON STALENESS: filed is not enough, it must ISSUE ---
        // A set that fills and never drains reads exactly like a working
        // one from the outside, and this drain is the only consumer, is
        // capped per pump, and sits behind the direct refreshes - three
        // independent ways to make it never run. Pin the read.
        try {
            context.waitFor(client -> com.deds.meshelium.farfield.FarFieldResidency
                    .farApronRefreshIssued.sum() > apronIssuedBefore, FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("apron staleness: " + apronFiled + " neighbour "
                    + "re-meshes were FILED for column " + colX + "," + colZ
                    + " and drainApronRefreshColumns issued none of them "
                    + "(farApronRefreshIssued still " + apronIssuedBefore
                    + ") - the set fills and never drains, which leaves every seam "
                    + "stale for the session and costs memory doing it - "
                    + FarWalkerProbe.describe(), t);
        }

        // --- 5. settled: no duplicates, geometry really exchanged ---
        context.waitTicks(20); // let the refresh column retire (manifest merge)
        int boundAfter = context.computeOnClient(client ->
                TerrainResidency.farResidentSectionsAt(colX, colZ));
        int manifestAfter = context.computeOnClient(client ->
                com.deds.meshelium.farfield.FarFieldResidency
                        .residentSectionCount(colX, colZ));
        long quadsAfter = context.computeOnClient(client ->
                TerrainResidency.farResidentQuadsAt(colX, colZ));
        if (boundAfter <= 0 || manifestAfter != boundAfter) {
            throw new AssertionError("replace-in-place: after the swap the walker's "
                    + "manifest lists " + manifestAfter + " sections for " + colX + ","
                    + colZ + " while the residency binds " + boundAfter
                    + " - a mismatch above is the duplicate-manifest hazard "
                    + "(retireColumn appending an already-listed section), below is a "
                    + "lost row; either double-frees or leaks on demote");
        }
        if (quadsAfter == quadsBefore) {
            throw new AssertionError("replace-in-place: the swap was counted but the "
                    + "column's geometry is byte-identical (" + quadsAfter + " quads) - "
                    + "the admission skipped instead of replacing");
        }
        long illegal = TerrainResidency.ledgerIllegalTransitions();
        if (illegal != 0) {
            throw new AssertionError("replace-in-place: the ledger shadow saw " + illegal
                    + " transitions outside the legal table - the FAR->FAR replacement "
                    + "must land as predecessor FAR->CLEARED + successor UNBOUND->FAR, "
                    + "and zero is step 2's acceptance gate");
        }
        assertNoErrors();
    }

    /**
     * Leg 5: the ground-cover lesson, applied to the far radius. A
     * slider that only ever adds is half a slider, and the failure is
     * invisible in a screenshot because the horizon looks right either
     * way; it only shows up as memory that never comes back.
     *
     * <p><b>What makes this fail:</b> a promote-only walker (lowering
     * the radius leaves the resident count flat, so the shrink assertion
     * trips); a walker whose scan latches after its first full pass
     * (raising the radius again leaves the count at the low value, so
     * the regrow assertion trips); a demote sweep with no budget that
     * releases everything including the ring it should keep (the low
     * count would fall to zero, which the shrink assertion also
     * rejects); and a hysteresis band wide enough to swallow the whole
     * change (the counts would not move at all).</p>
     */
    private static void assertRadiusActsInBothDirections(ClientGameTestContext context) {
        setFarRadius(context, FAR_L1_HIGH);
        context.waitFor(client -> FarWalkerProbe.resident() > 0, FAR_TIMEOUT_TICKS);
        long high = settleFarResident(context);
        long releasesBefore = FarWalkerProbe.releases();

        // --- DOWN ---
        setFarRadius(context, FAR_L1_LOW);
        try {
            context.waitFor(client -> FarWalkerProbe.resident() < high, FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("lowering the far radius from " + FAR_L1_HIGH + " to "
                    + FAR_L1_LOW + " released nothing: still " + FarWalkerProbe.resident()
                    + " far sections resident (was " + high + "). The walker promotes but "
                    + "does not demote", t);
        }
        long low = settleFarResident(context);
        if (low <= 0) {
            throw new AssertionError("lowering the radius to " + FAR_L1_LOW + " (still well "
                    + "outside the rd-" + FAR_RD + " near field) drained the far ring "
                    + "completely - the demote sweep is releasing the ring it should keep");
        }
        if (low >= high) {
            throw new AssertionError("far residency did not shrink: " + high + " -> " + low);
        }
        if (FarWalkerProbe.releases() <= releasesBefore) {
            throw new AssertionError("far sections left residency without being counted on "
                    + "farReleases (" + releasesBefore + " -> " + FarWalkerProbe.releases()
                    + ") - the gauge and the ledger disagree");
        }

        // --- and back UP ---
        setFarRadius(context, FAR_L1_HIGH);
        try {
            context.waitFor(client -> FarWalkerProbe.resident() > low, FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("raising the far radius back to " + FAR_L1_HIGH
                    + " promoted nothing: still " + FarWalkerProbe.resident()
                    + " resident (low water was " + low + ", high water was " + high
                    + "). The promotion scan does not re-arm on a slider change", t);
        }
        long regrown = settleFarResident(context);
        if (regrown <= low) {
            throw new AssertionError("far residency did not grow back: " + low + " -> "
                    + regrown + " (it had reached " + high + " at this radius before)");
        }
        assertNoErrors();
    }

    /**
     * Leg 6: the delete button, pressed for real, through the screen,
     * against a cache with LIVE FILE HANDLES on it.
     *
     * <p>This is the only code path in the far field that destroys a
     * player's data, and it is the one with a platform hazard the other
     * legs cannot see: a region file open in the store's
     * {@code RandomAccessFile} cannot be deleted on Windows, which is
     * why {@code FarField.requestCacheClear} routes through the IO
     * thread, closes the store, deletes, and reopens. All three halves
     * are checked here.</p>
     *
     * <p><b>What makes this fail:</b> the confirmation step vanishing
     * (the leg asserts the FIRST click changes the label and deletes
     * NOTHING, so a one-click wipe fails here rather than in a bug
     * report); the delete not happening (folder still on disk); the
     * delete happening but leaving files behind (the button reports
     * failure and the folder survives, which the folder check catches);
     * and the reopen not happening, which is the subtle one: without it
     * everything looks fine until the player notices their horizon
     * stopped filling in for the rest of the session, so the leg walks
     * again afterwards and requires the store to write.</p>
     */
    private static void assertClearCacheThroughTheScreen(ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        long bytesBefore = cacheBytesOnDisk();
        if (bytesBefore <= 0) {
            throw new AssertionError("the delete leg needs a populated cache and found "
                    + bytesBefore + " bytes - the earlier legs did not leave one");
        }
        // The delete-worked witness: a column the walk legs stored that
        // sits FAR OUTSIDE the held window at HOME (storage range is
        // max(2, rd) + 3 = 11 chunks; these are 17+ out), so nothing
        // can legitimately re-save it after the clear while the player
        // stands at HOME. Proven readable BEFORE the clear, so the MISS
        // assertion after it cannot pass vacuously on a never-stored
        // key. (The old assertion here - "the folder stays empty" - was
        // asserting the BROKEN heal: pre-M2 the tracker kept claiming
        // stored after a wipe, so nothing ever re-saved. The M2 store-
        // invalidated event re-dirties the held window on purpose, and
        // the fresh refill is the behaviour the owner wants.)
        int[] witnessCandidates = {
                WALK_STOP_2_X >> 4, WALK_STOP_1_X >> 4, (WALK_STOP_1_X >> 4) + 3};
        int witnessX = Integer.MIN_VALUE;
        for (int candidate : witnessCandidates) {
            if (readShellBlocking(context, candidate, 0) != null) {
                witnessX = candidate;
                break;
            }
        }
        if (witnessX == Integer.MIN_VALUE) {
            throw new AssertionError("the delete leg found " + bytesBefore
                    + " bytes on disk but none of the corridor witness columns "
                    + "(chunk x " + (WALK_STOP_2_X >> 4) + "/" + (WALK_STOP_1_X >> 4)
                    + "/" + ((WALK_STOP_1_X >> 4) + 3) + ", z 0) read back a shell - "
                    + "the pre-clear HIT guard is unsatisfiable and the post-clear "
                    + "MISS assertion would be vacuous");
        }

        context.runOnClient(client ->
                client.gui.setScreen(new MesheliumFarFieldScreen(null)));
        context.waitForScreen(MesheliumFarFieldScreen.class);
        context.waitTicks(5);

        String idle = Component.translatable("meshelium.options.farfield.clear").getString();
        String confirm = Component.translatable(
                "meshelium.options.farfield.clear.confirm").getString();
        String done = Component.translatable(
                "meshelium.options.farfield.clear.done").getString();

        // One click ARMS. It must not delete anything.
        pressScreenTreeButton(context, idle);
        context.waitTicks(3);
        if (!screenTreeHasButton(context, confirm)) {
            throw new AssertionError("the first click on the delete button did not arm a "
                    + "confirmation step - one slip would wipe every world's cache");
        }
        long bytesAfterFirstClick = cacheBytesOnDisk();
        // GROWTH IS NOT A DELETION, and the difference matters. This was
        // an exact-equality check until pre21, which made it a race
        // against the store's own writer: the far field keeps saving
        // while the screen is open, so any shell that lands between the
        // two measurements failed a leg that is only trying to prove the
        // first click DELETES NOTHING. Phase 2's budget made saving
        // slower and turned that latent race into a reproducible red -
        // 1,520,804 bytes "became" 1,522,101, an INCREASE of 1,297, and
        // the message still said something had been deleted.
        //
        // The assertion is therefore one-sided. Only a DECREASE can mean
        // the arm click destroyed data, and only a decrease is what this
        // leg exists to catch; bytes arriving from the writer are the
        // store working as intended.
        if (bytesAfterFirstClick < bytesBefore) {
            throw new AssertionError("the FIRST click already deleted something: "
                    + bytesBefore + " bytes became " + bytesAfterFirstClick
                    + " (a decrease of " + (bytesBefore - bytesAfterFirstClick)
                    + "). One click must ARM the confirmation and destroy "
                    + "nothing; growth here would be the writer and is allowed, "
                    + "shrinkage is the bug this leg guards.");
        }

        // The second click does it. Poll for the BUTTON's answer rather
        // than for the folder: the label is the last thing the delete
        // path does (the callback runs after the store reopen), so
        // waiting on it removes the race where the folder has gone but
        // the screen has not been told yet, and it distinguishes "it
        // worked" from "it tried and could not".
        String failed = Component.translatable(
                "meshelium.options.farfield.clear.failed").getString();
        // The click itself, which the first version of this leg forgot:
        // it armed the confirmation and then polled for an answer that
        // nothing had asked for, so the leg failed with "never answered"
        // and an untouched cache. Press the CONFIRM label, not the idle
        // one - the button relabels itself when it arms.
        pressScreenTreeButton(context, confirm);
        boolean reportedDone = false;
        boolean reportedFailure = false;
        for (int i = 0; i < 200 && !reportedDone && !reportedFailure; i++) {
            context.waitTicks(2);
            reportedDone = screenTreeHasButton(context, done);
            reportedFailure = screenTreeHasButton(context, failed);
        }
        if (reportedFailure) {
            throw new AssertionError("the delete button reported failure with "
                    + cacheBytesOnDisk() + " bytes still at " + FarField.cacheFolder()
                    + " (was " + bytesBefore + "). On Windows this is what an unclosed "
                    + "region-file handle looks like: the store must close BEFORE the "
                    + "delete, and reopen after");
        }
        if (!reportedDone) {
            throw new AssertionError("the delete button never answered at all: still "
                    + cacheBytesOnDisk() + " bytes at " + FarField.cacheFolder()
                    + ". A clear that is queued behind the IO thread and never runs "
                    + "leaves the screen saying Deleting for the rest of the session");
        }
        // THE DELETE-WORKED ASSERTION: the pre-clear witness is gone.
        // Deliberately NOT a folder-byte-count check - the clear's
        // success legitimately refills the folder within a second or
        // two: the ClearTask reopens the store and posts the store-
        // invalidated event, the pump resets the records, and the
        // scheduler re-saves the HELD window (the heal). The read is
        // FIFO behind the ClearTask on the one IO thread, so a MISS
        // here is deterministic, not a race.
        ShellCodec.Shell witnessAfter = readShellBlocking(context, witnessX, 0);
        if (witnessAfter != null) {
            throw new AssertionError("the delete button reported success but the "
                    + "pre-clear witness column (" + witnessX + ", 0) still reads "
                    + "back a shell - the wipe did not take, or a write of "
                    + "pre-clear data slipped past enqueueClear's discard");
        }

        // Screen CLOSED before the refill wait: the witness probe above
        // needs only the IO queue, but the refill needs the pump and
        // the scheduler, and they must not be asked to race whatever
        // pause semantics an open screen brings.
        context.runOnClient(client -> client.gui.setScreen(null));
        context.waitTicks(5);

        // THE REFILL-IS-LEGITIMATE ASSERTION: what comes back is a
        // CURRENT shell of a HELD column. Column (0,0) is where the
        // player is standing - inside the live window, dirtied by the
        // store-invalidated reset, nearest-first in the refill.
        boolean refilled = false;
        for (int i = 0; i < 60 && !refilled; i++) {
            refilled = readShellBlocking(context, 0, 0) != null;
            if (!refilled) {
                context.waitTicks(10);
            }
        }
        if (!refilled) {
            throw new AssertionError("the cache cleared but the held window never "
                    + "re-saved: column (0,0) - under the player's feet - still "
                    + "misses after the clear. The store-invalidated event did not "
                    + "reach forgetExtractedColumns, or the refill pipeline is "
                    + "stuck (jobsQueued=" + context.computeOnClient(
                            client -> (long) ExtractDispatch.jobsQueued())
                    + " writes=" + FarField.farStoreWrites.sum()
                    + " dropped=" + FarField.farStoreDroppedWrites.sum() + ")");
        }

        // The reopen: the store must still be usable for THIS world.
        long writesBefore = FarField.farStoreWrites.sum();
        hopTo(context, singleplayer, WALK_STOP_1_X);
        hopTo(context, singleplayer, HOME_X);
        try {
            context.waitFor(client -> FarField.farStoreWrites.sum() > writesBefore,
                    DRAW_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("nothing was written after the cache was cleared: the "
                    + "store did not reopen, so this world stopped caching for the rest of "
                    + "the session (writes=" + FarField.farStoreWrites.sum() + " dropped="
                    + FarField.farStoreDroppedWrites.sum() + " errors="
                    + FarField.farStoreErrors.sum() + ")", t);
        }
        if (!Files.isDirectory(FarField.cacheFolder())) {
            throw new AssertionError("writes were counted after the clear but "
                    + FarField.cacheFolder() + " was never recreated");
        }
    }

    /** Press a button anywhere in the current screen's real widget tree. */
    private static void pressScreenTreeButton(ClientGameTestContext context, String label) {
        context.runOnClient(client -> {
            for (AbstractWidget widget : screenWidgets(client.gui.screen())) {
                if (widget instanceof Button button
                        && button.getMessage().getString().equals(label)) {
                    // fabric's own pressMatchingButton, verbatim.
                    button.onPress(new MouseButtonInfo(-1, 0));
                    return;
                }
            }
            throw new AssertionError("no button labelled '" + label + "' on "
                    + client.gui.screen());
        });
    }

    private static boolean screenTreeHasButton(ClientGameTestContext context, String label) {
        Boolean found = context.computeOnClient(client -> {
            for (AbstractWidget widget : screenWidgets(client.gui.screen())) {
                if (widget instanceof Button
                        && widget.getMessage().getString().equals(label)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        });
        return Boolean.TRUE.equals(found);
    }

    /**
     * Depth-first over the REAL event tree. Not fabric's
     * {@code clickScreenButton}: these rows live inside a
     * {@code ScrollableLayout}, whose container widget does not override
     * {@code visitWidgets}, so fabric's renderable walk cannot see them
     * (the same reason the boot smoke test has its own walker).
     */
    private static List<AbstractWidget> screenWidgets(Screen screen) {
        List<AbstractWidget> out = new ArrayList<>();
        if (screen != null) {
            collectWidgets(screen, out);
        }
        return out;
    }

    private static void collectWidgets(ContainerEventHandler container,
            List<AbstractWidget> out) {
        for (GuiEventListener child : container.children()) {
            if (child instanceof AbstractWidget widget) {
                out.add(widget);
            }
            if (child instanceof ContainerEventHandler nested) {
                collectWidgets(nested, out);
            }
        }
    }

    // ------------------------------------------------------------------
    // Far-walker access, deliberately in its own class
    // ------------------------------------------------------------------

    /**
     * Every {@code FarFieldResidency} reference in this test file lives
     * here, in a nested class of its own.
     *
     * <p>Class VERIFICATION is eager: the JVM links a class by verifying
     * all of its methods, and although the verifier does not resolve the
     * owner of a {@code getstatic}, "does not" is a property of the
     * current implementation rather than something this test should bet
     * its central invariant on. A nested class is a separate class file
     * that is not loaded until something touches it, so with this
     * indirection the zero-cost-off leg cannot be broken by its own test
     * class linking, only by the code it is testing. Nothing in legs 1
     * and 2 touches this type.</p>
     */
    private static final class FarWalkerProbe {

        private FarWalkerProbe() {
        }

        static long resident() {
            return com.deds.meshelium.farfield.FarFieldResidency.farSectionsResident.sum();
        }

        static long admissions() {
            return com.deds.meshelium.farfield.FarFieldResidency.farAdmissions.sum();
        }

        static long releases() {
            return com.deds.meshelium.farfield.FarFieldResidency.farReleases.sum();
        }

        /** One line of far state for an assertion message. */
        static String describe() {
            return "resident=" + resident()
                    + " requests=" + com.deds.meshelium.farfield.FarFieldResidency
                            .farShellRequests.sum()
                    + " meshed=" + com.deds.meshelium.farfield.FarFieldResidency
                            .farMeshedSections.sum()
                    + " admitted=" + admissions()
                    + " released=" + releases()
                    + " budgetStalls=" + com.deds.meshelium.farfield.FarFieldResidency
                            .farPromoteBudgetStalls.sum()
                    + " skippedCells=" + com.deds.meshelium.farfield.FarFieldResidency
                            .farMeshSkippedCells.sum()
                    + " meshErrors=" + com.deds.meshelium.farfield.FarFieldResidency
                            .farMeshErrors.sum()
                    + " broken=" + com.deds.meshelium.farfield.FarFieldResidency.isBroken();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Flip the master switch through the PROPERTY, which outranks the
     * config field and is re-read per call, and then do what the Far
     * Terrain screen does: refresh the extraction seams. Without the
     * refresh the mixins keep their boot-time answer and a mid-session
     * enable never reaches them, which is the exact bug
     * {@code ExtractDispatch.refreshArmed()} exists to prevent and the
     * exact thing this test would otherwise fail to notice.
     */
    private static void armFarField(ClientGameTestContext context, boolean armed) {
        context.runOnClient(client -> {
            if (armed) {
                System.setProperty("meshelium.farfield.enabled", "true");
            } else {
                System.clearProperty("meshelium.farfield.enabled");
            }
            ExtractDispatch.refreshArmed();
        });
    }

    private static void setFarRadius(ClientGameTestContext context, int chunks) {
        context.runOnClient(client -> System.setProperty(
                "meshelium.farfield.l1RadiusChunks", Integer.toString(chunks)));
    }

    /**
     * The pose every C-series screenshot is taken from: chunk 0, an
     * absolute height, yaw -90 (east, along the cached corridor), a
     * shallow downward pitch. Absolute on purpose - a relative {@code ~}
     * height would climb another {@value #CAMERA_HEIGHT} blocks every
     * time the pose is restored and the A/B pair would stop being a
     * pair.
     */
    private static void lookEast(TestSingleplayerContext singleplayer, double cameraY) {
        singleplayer.getServer().runCommand("execute as @p at @s run tp @s "
                + HOME_X + " " + formatY(cameraY) + " " + HOME_Z + " -90 " + CAMERA_PITCH);
    }

    /** Command-safe decimal, root locale (a comma would split the argument). */
    private static String formatY(double y) {
        return String.format(Locale.ROOT, "%.1f", y);
    }

    /** The local player's current feet height, for an absolute camera pose. */
    private static double groundY(ClientGameTestContext context) {
        Double y = context.computeOnClient(client ->
                client.player == null ? null : client.player.getY());
        if (y == null) {
            throw new AssertionError("no local player: cannot resolve a camera height");
        }
        return y;
    }

    /**
     * Teleport a whole render-distance window east and let the client
     * settle. rd {@value #FAR_RD} means a 17x17 window, and the stops
     * are 17 chunks apart, so nothing overlaps: every chunk of the old
     * window is abandoned, which is what produces the extraction.
     */
    private static void hopTo(ClientGameTestContext context,
            TestSingleplayerContext singleplayer, int blockX) {
        tpTo(singleplayer, blockX);
        context.waitTicks(20);
        settleLoadedChunks(context);
    }

    /**
     * The bare teleport, split out of {@link #hopTo} so a leg racing a
     * wall clock (leg 3c's ten-second store deadline) can leave WITHOUT
     * paying the settle loop first - and without re-typing the command
     * string hopTo owns.
     */
    private static void tpTo(TestSingleplayerContext singleplayer, int blockX) {
        singleplayer.getServer().runCommand(
                "execute as @p at @s run tp @s " + blockX + " ~ " + HOME_Z + " -90 0");
    }

    /** Wait until the client's chunk count stops moving (streaming done). */
    private static void settleLoadedChunks(ClientGameTestContext context) {
        int last = -1;
        for (int i = 0; i < 40; i++) {
            int now = context.computeOnClient(client -> client.level == null ? -1
                    : client.level.getChunkSource().getLoadedChunksCount());
            if (now > 0 && now == last) {
                return;
            }
            last = now;
            context.waitTicks(20);
        }
        throw new AssertionError("the client's loaded-chunk count never settled after a "
                + "teleport (last " + last + ") - the world is still streaming");
    }

    /**
     * Wait for the far resident gauge to stop moving and return it. The
     * walker only re-arms on a camera crossing or a slider change, so
     * with a pinned camera it always converges; the loop bounds how long
     * a leg will wait for that.
     */
    private static long settleFarResident(ClientGameTestContext context) {
        long last = -1;
        for (int i = 0; i < 40; i++) {
            context.waitTicks(20);
            long now = FarWalkerProbe.resident();
            if (now == last) {
                return now;
            }
            last = now;
        }
        return last;
    }

    /** Bytes under the whole far-field cache; the gametest thread's own walk. */
    private static long cacheBytesOnDisk() {
        Path base = FarField.cacheFolder();
        if (!Files.isDirectory(base)) {
            return 0L;
        }
        try (Stream<Path> files = Files.walk(base)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException vanished) {
                    return 0L;
                }
            }).sum();
        } catch (IOException | RuntimeException e) {
            throw new AssertionError("could not measure " + base, e);
        }
    }

    /** The draw test's production render-distance path: set + save + follow. */
    private static void setRenderDistanceLikeTheUi(ClientGameTestContext context, int rd) {
        context.runOnClient(client -> {
            client.options.renderDistance().set(rd);
            client.options.save();
        });
        context.waitFor(client -> client.options.getEffectiveRenderDistance() == rd,
                RD_TIMEOUT_TICKS);
    }

    /** The draw test's deterministic scene, 26.2 gamerule names. */
    private static void freezeWorld(TestSingleplayerContext singleplayer) {
        var server = singleplayer.getServer();
        server.runCommand("time set noon");
        server.runCommand("gamerule minecraft:advance_time false");
        server.runCommand("weather clear");
        server.runCommand("gamerule minecraft:advance_weather false");
        server.runCommand("gamerule minecraft:spawn_mobs false");
        server.runCommand("gamerule minecraft:random_tick_speed 0");
        server.runCommand("kill @e[type=!minecraft:player]");
    }

    /**
     * The near field must be healthy throughout. A far-field bug that
     * turned the mod passive would show up here first, and that is the
     * standing rule the far field exists under: a cache problem may
     * never cost the player their terrain renderer.
     */
    private static void assertNoErrors() {
        String drawError = TerrainDrawer.lastError();
        if (drawError != null) {
            throw new AssertionError("terrain drawer reported an error: " + drawError);
        }
        String residencyError = TerrainResidency.lastError();
        if (residencyError != null) {
            throw new AssertionError("terrain residency reported an error: " + residencyError);
        }
        if (TerrainDrawer.coveragePassive()) {
            throw new AssertionError("the coverage guard went passive during a far-field "
                    + "leg - a cache bug must never turn the mod passive: "
                    + TerrainResidency.counters());
        }
    }
}
