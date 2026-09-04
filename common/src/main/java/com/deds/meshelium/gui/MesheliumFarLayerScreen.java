/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.gui;

import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.farfield.FarFieldConfig;
import com.deds.meshelium.farfield.FarFieldConfig.Control;
import com.deds.meshelium.farfield.FarFieldConfig.Layer;
import com.deds.meshelium.farfield.extract.ExtractDispatch;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/**
 * One page per LOD layer: the owner's D1 and D2, "three LOD layers where
 * we can choose what performance optimizations to apply to them" and
 * "feel free in the lod settings page to make as many sub menus as you
 * want to turn all this on and off".
 *
 * <h2>Why one page per layer and not one per feature</h2>
 * <p>The owner allowed "maybe even one per thing". A page per FEATURE
 * would put three rows (layer 1, 2, 3) on each of eight pages, so a
 * player tuning one layer would walk eight pages and a player comparing
 * layers would walk none of them usefully. A page per LAYER is the other
 * transpose of the same grid and matches how the settings are actually
 * used: you pick a layer, then decide what it may give up. The page is a
 * {@link ScrollableLayout}, so the thirteenth row cost nothing and the
 * fourteenth will not either.</p>
 *
 * <h2>Two blocks of rows, and they pull in opposite directions</h2>
 * <p>Rows 1 to 3 and 10 to 13 make a layer CHEAPER (scale, flat water,
 * simplified textures, coarse shading, heightfield), and Background
 * Saving under Distance is the one row that trades neither look nor
 * memory but FRAME RATE, for how fast the horizon comes to exist. The
 * middle block is
 * the ACCURACY set - plants in water, colour blending, lighting, distant
 * water, smooth lighting, shading strength, solid leaves, plus the
 * retired Small Details - and they come from the
 * owner's pre7 playtest
 * word for word: "we should 100% put that in the sub menu for ring of the
 * lod because those are all things we would want to turn on or off
 * depending on distance". On layer 1 they matter most, because layer 1's
 * whole job is to look like ordinary terrain; on a cheap far ring the
 * owner will want most of them off. Every one of their tooltips ends with
 * what it costs, because that is the decision the row exists to
 * support.</p>
 *
 * <h2>Honesty about what is connected</h2>
 * <p>{@link FarFieldConfig#isWired(Layer, Control)} is the single source
 * of truth for whether a row does anything today, and it answers yes for
 * layer 1's on/off, its distance, Background Saving and seven of the
 * eight accuracy rows
 * (Small Details is retired, {@link FarFieldConfig#isRetired}). Every other
 * row on every layer, and every row on layers 2 and 3, is rendered,
 * carries its saved value, and is INACTIVE, with "not used yet" in its
 * own label and a tooltip that says so in a sentence.</p>
 *
 * <p>Inactive rather than clickable-but-inert was a deliberate call. A
 * toggle that moves and changes nothing is the definition of a settings
 * page pretending to work, and the failure is delayed: the player flips
 * it, sees nothing, forgets, and then the update that wires the feature
 * changes their game without them asking. Greyed out with a reason is the
 * honest version of the same information, and the stored value is still
 * there for the wave that connects it. (An inactive slider's
 * {@code getMessage()} returns the same TEXT, only re-styled grey, so a
 * gametest can still find these rows by label: bytecode of
 * {@code AbstractWidget$WithInactiveMessage.getMessage}, 26.2 jar, which
 * branches on {@code active} between the normal message and
 * {@code defaultInactiveMessage(message)} and the latter is a
 * {@code Style.withColor} merge, not a different string.)</p>
 *
 * <h2>The two rules every live control here obeys</h2>
 * <ol>
 * <li><b>Persist</b> through {@code FarFieldConfig.save()}.</li>
 * <li><b>Re-arm the seams</b> through {@link ExtractDispatch#refreshArmed()},
 *     for the reason {@link MesheliumFarFieldScreen} spells out: the
 *     chunk-lifecycle mixins gate on one static volatile, and a live flip
 *     that never reaches it looks to the player like a feature that does
 *     nothing.</li>
 * </ol>
 *
 * <h2>What "applies" means on each row, and why this page does not do it</h2>
 * <p>The owner's pre8 report was "when i change settings they dont apply
 * immedietly, they should probably reload when you change settings"
 * (docs/FARFIELD-WAVES.md, item J4). They do now, and the work is NOT
 * driven from here: {@code FarFieldResidency} watches
 * {@code FarFieldConfig.farMeshSignature()} and
 * {@code farSaveSignature()} once per pump. That is deliberate — a hand
 * edit of {@code meshelium-farfield.json} and a {@code -D} override are
 * both documented as applying live, so hanging the behaviour off a button
 * press would have honoured one of the three routes.</p>
 *
 * <p>The two signatures are two different promises and the
 * {@code appliesKey} on each row is which one it keeps:</p>
 * <ul>
 * <li>rows the MESHER reads (Solid Leaves, Distant Water, Smooth
 *     Lighting, Shading Strength and every step of Lighting) take
 *     {@code applies.far_now}: the shell records are unchanged, so the
 *     far field RELOADS. It is dropped whole in one frame and the walker's
 *     ordinary near-first refill brings it back over the next few seconds
 *     ({@code FarFieldResidency.reloadFarField}). pre9 did this a few
 *     columns at a time and the owner's pre10 report was that it "just
 *     seems like random jittering all over"; a clean drop and a rebuild
 *     outwards is what he asked for instead, and the tooltip now says so
 *     rather than promising an invisible change;</li>
 * <li>Background Saving takes {@code applies.saving_rate} and is in
 *     NEITHER signature: it decides how fast records are written, not
 *     what is in them and not how they are drawn, so a reload or a
 *     re-save would both be work spent to produce an identical
 *     picture;</li>
 * <li>rows the EXTRACTOR reads (Plants In Water, Colour Blending, and
 *     Real Light's stored plane) take {@code applies.new_cache}, which no
 *     longer claims more than it can do. The data is not in the record
 *     and cannot be re-meshed into it; what happens instead is that the
 *     terrain the client is still HOLDING is saved again, and the tooltip
 *     says the rest keeps what it has until the player travels back
 *     through it.</li>
 * </ul>
 * <p>The old {@code applies.far_rebuild} line, "to redraw what is already
 * on screen, turn Layer off and on again", is gone with the workaround it
 * described.</p>
 * <p>And one more that is specific to this page: <b>any change flips the
 * preset selection to Custom</b> without touching another value, which is
 * the brief's rule verbatim.</p>
 *
 * <h2>Reaching these rows from a gametest</h2>
 * <p>Same trap as the parent page: every row lives in a
 * {@link ScrollableLayout} and fabric's {@code clickScreenButton} cannot
 * see into one. Use {@code MesheliumBootSmokeTest.collectWidgets} to
 * assert and {@code pressWidgetTreeButton} to press; Done in the footer
 * is a direct renderable and stays on {@code clickScreenButton}. Getting
 * HERE is one press of
 * {@code "meshelium.options.farfield.l1"} on the Far Terrain page (and
 * the l2/l3 rows carry a "(not used yet)" suffix, so an equals-based
 * press helper needs the whole rendered string).</p>
 *
 * <p><b>A cycle row's rendered string is "name: value", not the label
 * key's own text.</b> {@code CycleButton} composes it with
 * {@code CommonComponents.optionNameValue}, i.e.
 * {@code options.generic_value} = {@code "%s: %s"} (javap,
 * {@code CycleButton.createFullName} ip 0-20, and {@code DisplayState
 * .NAME_AND_VALUE} is the Builder's default at ip 41-45). So Distant
 * Water reads "Distant Water: Stock Minecraft" and a label key must NOT
 * carry a {@code %s} of its own - three of them did until pre13 and were
 * printing it literally. Sliders are the opposite: {@code LayerIntSlider
 * .updateMessage} passes the value as an argument to the label key, so
 * those keys DO take a {@code %s}.</p>
 *
 * <h2>Where the saved-detail row is not</h2>
 * <p>There is no surface-band row here, on purpose. Extraction runs once
 * per chunk and writes one shell record that every layer reads, so the
 * band decides what goes on DISK, not how a layer draws. Three copies of
 * one switch would mean two of them were lying. The page carries a grey
 * line pointing at the single row on the parent page instead.</p>
 */
public class MesheliumFarLayerScreen extends Screen {

    private static final int WIDGET_WIDTH = 200;
    private static final int BANNER_WIDTH = 340;

    private final Screen parent;
    private final Layer layer;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    /** The scrolling middle band; built in {@link #init()} (it needs minecraft). */
    private ScrollableLayout scrollArea;

    /**
     * True when the backend gate means none of this can take effect. Read
     * once at construction, the house rule on all three Meshelium pages:
     * the gate cannot change without a restart, so re-reading it per frame
     * would only invite a row that disagrees with its own tooltip.
     */
    private final boolean gateLocked;

    /**
     * The row {@link #refreshLockedRows()} re-applies its lock to. No
     * supersede relation answers true today ({@code
     * FarFieldConfig.isSuperseded} says why pre12's did not hold), so the
     * re-apply is currently a no-op that keeps the page correct the moment
     * one does return, without waiting for the player to leave and come
     * back. Null before {@code init()} and re-assigned by every rebuild.
     */
    private CycleButton<Boolean> waterLookRow;

    /**
     * The live "how much of what this session has seen reached disk"
     * line (P3; session-scoped since M6), layer 1 only. Null on layers
     * 2 and 3 and before {@code init()}; refreshed by {@link #tick()}.
     */
    private net.minecraft.client.gui.components.StringWidget savingLine;

    /**
     * The live "what is being written in the background, what is
     * behind, what is lost" line (P3; behind/lost worded separately
     * since M6). See {@link #savingLine}.
     */
    private net.minecraft.client.gui.components.StringWidget leavingLine;

    public MesheliumFarLayerScreen(Screen parent, Layer layer) {
        super(Component.translatable(
                "meshelium.options.farfield." + layer.key + ".title"));
        this.parent = parent;
        this.layer = layer;
        this.gateLocked = MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS;
    }

    /** Which layer this page is editing (the harness reads it). */
    public Layer layer() {
        return this.layer;
    }

    @Override
    protected void init() {
        FarFieldConfig config = FarFieldConfig.get();
        boolean layerWired = FarFieldConfig.isLayerWired(this.layer);

        this.layout.addTitleHeader(this.getTitle(), this.font);

        LinearLayout rows = LinearLayout.vertical().spacing(2);
        rows.defaultCellSetting().alignHorizontallyCenter();

        // Per-screen dev-override census, the house rule: a -D flag only
        // raises a banner over rows THIS screen can show, so this page
        // counts only its own layer's thirteen properties.
        if (FarFieldConfig.anyLayerPropertyOverride(this.layer)) {
            rows.addChild(banner(Component.translatable("meshelium.options.dev_override")),
                    s -> s.paddingTop(2).paddingBottom(2));
        }

        if (this.gateLocked) {
            rows.addChild(banner(Component.translatable("meshelium.options.advanced.locked")
                            .withStyle(ChatFormatting.YELLOW)),
                    s -> s.paddingTop(2).paddingBottom(2));
        }

        // What this layer IS, in the player's words. Layer 1 in particular
        // has to say out loud that it is not a stylised tier: the owner's
        // re-spec of pre1 is that it must look like ordinary terrain.
        rows.addChild(banner(Component.translatable(
                        "meshelium.options.farfield." + this.layer.key + ".about")
                        .withStyle(ChatFormatting.GRAY)),
                s -> s.paddingTop(2).paddingBottom(2));

        // The whole-layer honesty banner. Only layer 1 escapes it today.
        if (!layerWired) {
            rows.addChild(banner(Component.translatable(
                            "meshelium.options.farfield.planned.layer",
                            Component.literal(Integer.toString(this.layer.number)))
                            .withStyle(ChatFormatting.GRAY)),
                    s -> s.paddingTop(2).paddingBottom(4));
        }

        // 1. Is this layer drawn at all.
        rows.addChild(onOff(Control.LAYER_ENABLED,
                "meshelium.options.farfield.layer.enabled",
                "meshelium.options.tooltip.farfield.layer.enabled",
                "meshelium.options.applies.now",
                () -> config.layer(this.layer).enabled,
                value -> config.setLayerFlag(this.layer, Control.LAYER_ENABLED, value)),
                s -> s.paddingTop(4));

        // 2. How far it reaches. Live in BOTH directions on layer 1: the
        // walker re-reads it every pump and demotes as readily as it
        // promotes.
        rows.addChild(slider(Control.DISTANCE,
                "meshelium.options.farfield.layer.distance",
                "meshelium.options.tooltip.farfield.layer.distance",
                "meshelium.options.applies.now",
                FarFieldConfig.MIN_L1_RADIUS_CHUNKS,
                FarFieldConfig.MAX_L1_RADIUS_CHUNKS,
                () -> config.layer(this.layer).radiusChunks,
                chunks -> config.layer(this.layer).radiusChunks = chunks,
                MesheliumFarLayerScreen::distanceText));

        // 2b. Background Saving, the owner's pre13 O4: "probably should
        // have some setting if the caching is going to cause that much of
        // a performance issue." It sits directly under Distance because
        // the two are one decision seen twice - Distance says how much
        // terrain has to be saved, this says how fast the game is allowed
        // to save it.
        //
        // The row is in NEITHER appearance signature and that is
        // deliberate: it changes no pixel of any record and no byte of
        // any record, only the RATE at which records are written, so
        // farMeshSignature would spend a ring reload on a rate change and
        // farSaveSignature would drop the extract-once tracker and
        // re-save the whole held window on one. FarFieldConfig.CACHE_SPEED
        // carries the argument. Its applies line therefore says the third
        // thing, which no other row on this page says: nothing you can
        // already see changes at all.
        rows.addChild(cycle(Control.CACHE_SPEED,
                "meshelium.options.farfield.layer.cache_speed",
                "meshelium.options.tooltip.farfield.layer.cache_speed",
                "meshelium.options.applies.saving_rate",
                new int[] {FarFieldConfig.CACHE_GENTLE, FarFieldConfig.CACHE_BALANCED,
                        FarFieldConfig.CACHE_FAST},
                MesheliumFarLayerScreen::cacheSpeedText,
                () -> config.layer(this.layer).cacheSpeed,
                value -> config.layer(this.layer).cacheSpeed = value));

        // 2c. P3: what the saver is actually achieving, directly under
        // the dial that decides how fast it may work.
        //
        // The owner has now asked for the same thing four playtests
        // running - "it should always cache, especially when leaving a
        // chunk" - and every previous answer was a scheduling change
        // nobody could check, because the only saving figure anywhere in
        // the game was "chunks saved this session" on the Far Terrain
        // page: a raw submission count that includes re-saves and
        // partial shells and has no denominator at all. It cannot tell
        // "the ground around me is saved" from "it is not", which is
        // exactly the question he has to answer before turning his render
        // distance down. These two lines can.
        //
        // M6: the numbers are the record map's own fold now
        // (ExtractDispatch.saveCensus, cached a second) rather than the
        // deleted window census. The window census could only see loaded
        // ground, so a column that left DIRTY - the exact population the
        // pre16 rebuild exists for - silently fell out of its
        // denominator; the fold sees every column the session ever held,
        // so "waiting: 0" here plus a quiet leaving line genuinely means
        // everything is saved and the render distance is safe to drop.
        //
        // Layer 1 only: the extractor writes ONE record that every layer
        // reads, and repeating the same global figures on the L2 and L3
        // pages would read as three separate caches.
        if (this.layer == Layer.L1) {
            this.savingLine = new net.minecraft.client.gui.components.StringWidget(
                    savingText(), this.font);
            this.savingLine.setMaxWidth(BANNER_WIDTH);
            this.savingLine.setTooltip(Tooltip.create(Component.translatable(
                    "meshelium.options.tooltip.farfield.layer.saving_progress")));
            rows.addChild(this.savingLine, s -> s.paddingTop(2));

            this.leavingLine = new net.minecraft.client.gui.components.StringWidget(
                    leavingText(), this.font);
            this.leavingLine.setMaxWidth(BANNER_WIDTH);
            this.leavingLine.setTooltip(Tooltip.create(Component.translatable(
                    "meshelium.options.tooltip.farfield.layer.saving_leaving")));
            rows.addChild(this.leavingLine, s -> s.paddingBottom(2));
        } else {
            this.savingLine = null;
            this.leavingLine = null;
        }

        // 3. Detail scale. A three-way cycle rather than the design doc's
        // "half-scale" boolean because quarter-scale is the same machinery
        // one more time (FAR-FIELD-DESIGN.md section 5), and two booleans
        // for one axis can be set to a state that means nothing.
        rows.addChild(cycle(Control.SCALE,
                "meshelium.options.farfield.layer.scale",
                "meshelium.options.tooltip.farfield.layer.scale",
                "meshelium.options.applies.new_builds",
                new int[] {FarFieldConfig.SCALE_FULL, FarFieldConfig.SCALE_HALF,
                        FarFieldConfig.SCALE_QUARTER},
                MesheliumFarLayerScreen::scaleText,
                () -> config.layer(this.layer).scale,
                scale -> config.layer(this.layer).scale = scale));

        // ------------------------------------------------------------
        // The ACCURACY block (the owner's pre7 item H4: "we should 100%
        // put that in the sub menu for ring of the lod because those are
        // all things we would want to turn on or off depending on
        // distance"). These six make the ring look MORE like ordinary
        // Minecraft; everything else on this page makes it cheaper. They
        // sit together, above the cheapening rows, because on layer 1
        // they are the point.
        // ------------------------------------------------------------

        // 4. Plants that stand in water. SAVE-time: it decides what is
        // written to the store, so no amount of re-meshing can add a
        // plant that was never saved. The walker's save-signature watch
        // re-saves the terrain the client is still holding and the
        // tooltip says the rest keeps what it has.
        rows.addChild(onOff(Control.UNDERWATER_PLANTS,
                "meshelium.options.farfield.layer.underwater_plants",
                "meshelium.options.tooltip.farfield.layer.underwater_plants",
                "meshelium.options.applies.new_cache",
                () -> config.layer(this.layer).underwaterPlants,
                value -> config.setLayerFlag(this.layer, Control.UNDERWATER_PLANTS, value)));

        // 5. Thin see-through blocks. MESH-time and free: it moves one
        // material field per face and nothing else, so the resident ring
        // can be re-meshed from the records it already has.
        rows.addChild(onOff(Control.SMALL_DETAIL,
                "meshelium.options.farfield.layer.small_detail",
                "meshelium.options.tooltip.farfield.layer.small_detail",
                "meshelium.options.applies.far_now",
                () -> config.layer(this.layer).smallDetail,
                value -> config.setLayerFlag(this.layer, Control.SMALL_DETAIL, value)));

        // 6. Colour blending. SAVE-time, and TWO-way since pre19's S1:
        // the two grid sizes it used to offer were both approximations of
        // the same field, and the record now stores that field itself
        // (one colour per block, Minecraft's own). There is nothing finer
        // to sell, so the row is Per Chunk or Per Block. A re-mesh would
        // produce the identical mesh: the record carries the colour field
        // it was saved with, so this row is honestly a save-signature row
        // and nothing else.
        rows.addChild(cycle(Control.COLOUR_BLEND,
                "meshelium.options.farfield.layer.colour_blend",
                "meshelium.options.tooltip.farfield.layer.colour_blend",
                "meshelium.options.applies.new_cache",
                new int[] {FarFieldConfig.BLEND_PER_CHUNK,
                        FarFieldConfig.BLEND_COARSE},
                MesheliumFarLayerScreen::blendText,
                () -> config.layer(this.layer).colourBlend,
                blend -> config.layer(this.layer).colourBlend = blend));

        // 7. Lighting. Three-way for the same reason: the first two steps
        // are free and the third is the only accuracy row on this page
        // that costs disk space per block face saved. It is also the one
        // row in BOTH signatures, so moving it reloads the ring AND
        // re-saves the held window - two speeds, and the tooltip is worded
        // for the slower one.
        rows.addChild(cycle(Control.LIGHTING,
                "meshelium.options.farfield.layer.lighting",
                "meshelium.options.tooltip.farfield.layer.lighting",
                // The one row that straddles the two classes: all three
                // steps change the MESH, so the ring re-meshes itself the
                // moment this moves, but Real Light also needs a light
                // plane in the record and no re-mesh can invent one.
                "meshelium.options.applies.far_now_saved",
                new int[] {FarFieldConfig.LIGHT_FLAT, FarFieldConfig.LIGHT_GLOW,
                        FarFieldConfig.LIGHT_REAL},
                MesheliumFarLayerScreen::lightingText,
                () -> config.layer(this.layer).lighting,
                light -> config.layer(this.layer).lighting = light));

        // 8. Distant Water, the owner's pre13 N4: "the ocean
        // color/blending still needs some work, we should have an option
        // for this that just looks like stock minecraft." Mesh-time and
        // free, and one of the two accuracy rows that also fix terrain
        // ALREADY on disk, because it derives the water surface from the
        // record rather than storing it.
        //
        // A named two-value cycle rather than On/Off on purpose: the row
        // exists because he asked for an option that "just looks like
        // stock minecraft", and a row whose value READS "Stock Minecraft"
        // is the answer to that sentence in a way that "On" is not.
        // Through pre12 this was Underwater Shading, an On/Off that Real
        // Light locked; it is the same stored value and the same code
        // path, unlocked and renamed to what it actually decides.
        this.waterLookRow = boolCycle(Control.WATER_LOOK,
                "meshelium.options.farfield.layer.water_look",
                "meshelium.options.tooltip.farfield.layer.water_look",
                "meshelium.options.applies.far_now",
                "meshelium.options.farfield.layer.water_look.stock",
                "meshelium.options.farfield.layer.water_look.saved",
                () -> config.layer(this.layer).waterDepth,
                value -> config.setLayerFlag(this.layer, Control.WATER_LOOK, value));
        rows.addChild(this.waterLookRow);

        // 8b. Smooth lighting, the owner's pre11 "the sublt shade in
        // corners and stuff". Three-way rather than a switch because the
        // two ON steps cost very different amounts: Edges is free and
        // Full buys vanilla's own one-block darkening with quads. Both
        // are mesh-time and storage-free, so like Distant Water they
        // improve terrain already saved.
        rows.addChild(cycle(Control.SMOOTH_LIGHT,
                "meshelium.options.farfield.layer.smooth_light",
                "meshelium.options.tooltip.farfield.layer.smooth_light",
                "meshelium.options.applies.far_now",
                new int[] {FarFieldConfig.SMOOTH_OFF, FarFieldConfig.SMOOTH_EDGES,
                        FarFieldConfig.SMOOTH_FULL},
                MesheliumFarLayerScreen::smoothText,
                () -> config.layer(this.layer).smoothLight,
                value -> config.layer(this.layer).smoothLight = value));

        // 8c. Its strength, the owner's pre10 "we can change its
        // opacity/intensity". One slider for whichever step is selected;
        // 100 on the Full step is exactly vanilla's own ambient
        // occlusion, which is what keeps the handover band free of a
        // shading step.
        rows.addChild(slider(Control.CONTACT_SHADE,
                "meshelium.options.farfield.layer.contact",
                "meshelium.options.tooltip.farfield.layer.contact",
                "meshelium.options.applies.far_now",
                FarFieldConfig.MIN_CONTACT_SHADE,
                FarFieldConfig.MAX_CONTACT_SHADE,
                () -> config.layer(this.layer).contactShade,
                value -> config.layer(this.layer).contactShade = value,
                MesheliumFarLayerScreen::contactText));

        // 8d. Block Variants, the owner's R4: "the grass texture on the
        // top is rotated and its not in lod". Mesh-time and storage-free
        // like the three rows above it - the roll is derived from the
        // cell's world position - so it also fixes terrain already saved.
        // The one accuracy row on this page that spends QUADS on FLAT
        // ground, which is why it is a row at all and why its tooltip
        // says so plainly.
        rows.addChild(onOff(Control.BLOCK_VARIANTS,
                "meshelium.options.farfield.layer.block_variants",
                "meshelium.options.tooltip.farfield.layer.block_variants",
                "meshelium.options.applies.far_now",
                () -> config.layer(this.layer).blockVariants,
                value -> config.setLayerFlag(this.layer, Control.BLOCK_VARIANTS,
                        value)));

        // 9. Solid leaves, this layer only. The near field's own Solid
        // Leaves slider on the Advanced page is a different setting for a
        // different renderer path and neither reads the other.
        rows.addChild(onOff(Control.SOLID_LEAVES,
                "meshelium.options.farfield.layer.solid_leaves",
                "meshelium.options.tooltip.farfield.layer.solid_leaves",
                "meshelium.options.applies.far_now",
                () -> config.layer(this.layer).solidLeaves,
                value -> config.setLayerFlag(this.layer, Control.SOLID_LEAVES, value)));

        // 10. Flat water. This row is about replacing the ocean shell with
        // one top sheet, which is a different saving from the depth
        // shading two rows up: that one changes how the sea bed is LIT,
        // this one would stop drawing it.
        rows.addChild(onOff(Control.FLAT_WATER,
                "meshelium.options.farfield.layer.flat_water",
                "meshelium.options.tooltip.farfield.layer.flat_water",
                "meshelium.options.applies.now",
                () -> config.layer(this.layer).flatWater,
                value -> config.setLayerFlag(this.layer, Control.FLAT_WATER, value)));

        // 11. Texture simplification, a slider because the design calls for
        // a range from a slight mip clamp down to a flat colour and the
        // owner tunes the floor in a playtest.
        rows.addChild(slider(Control.TEXTURE_SIMPLIFY,
                "meshelium.options.farfield.layer.texture",
                "meshelium.options.tooltip.farfield.layer.texture",
                "meshelium.options.applies.now",
                FarFieldConfig.TEXTURE_FULL,
                FarFieldConfig.TEXTURE_FLAT,
                () -> config.layer(this.layer).textureSimplify,
                step -> config.layer(this.layer).textureSimplify = step,
                MesheliumFarLayerScreen::textureText));

        // 12. Coarse shading (a 2x2 fragment shading rate on this layer's
        // draws only).
        rows.addChild(onOff(Control.COARSE_SHADING,
                "meshelium.options.farfield.layer.shading",
                "meshelium.options.tooltip.farfield.layer.shading",
                "meshelium.options.applies.now",
                () -> config.layer(this.layer).coarseShading,
                value -> config.setLayerFlag(this.layer, Control.COARSE_SHADING, value)));

        // 13. Heightfield: not a saving on top of the shell, a different
        // representation instead of it, so it sits last.
        rows.addChild(onOff(Control.HEIGHTFIELD,
                "meshelium.options.farfield.layer.heightfield",
                "meshelium.options.tooltip.farfield.layer.heightfield",
                "meshelium.options.applies.now",
                () -> config.layer(this.layer).heightfield,
                value -> config.setLayerFlag(this.layer, Control.HEIGHTFIELD, value)));

        // The row that is deliberately NOT here, said out loud, so nobody
        // goes looking for it three times.
        rows.addChild(banner(Component.translatable(
                        "meshelium.options.farfield.layer.band_note")
                        .withStyle(ChatFormatting.GRAY)),
                s -> s.paddingTop(6).paddingBottom(2));

        this.scrollArea = new ScrollableLayout(this.minecraft, rows,
                this.layout.getContentHeight());
        this.layout.addToContents(this.scrollArea);

        this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
                .width(WIDGET_WIDTH).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    // ------------------------------------------------------------------
    // Row builders. Each one takes the control it represents so exactly
    // one place decides wired / locked / overridden.
    // ------------------------------------------------------------------

    /**
     * The saving line, M6 shape: the record map's own fold
     * ({@code ExtractDispatch.saveCensus}, cached a second), whole
     * session rather than loaded window. "Waiting" is the dirty
     * population still in the client's hands; when it and the leaving
     * line are both at zero, everything the session ever held is stored
     * at its latest truth - which is the exact "is it safe to turn my
     * render distance down" answer the old window census could only
     * approximate (it never saw ground that left dirty).
     */
    private Component savingText() {
        if (!FarFieldConfig.enabled()) {
            return Component.translatable(
                    "meshelium.options.farfield.layer.saving.off")
                    .withStyle(ChatFormatting.GRAY);
        }
        ExtractDispatch.SaveCensus census = ExtractDispatch.saveCensus();
        int saved = census.savedWhole + census.savedDegraded;
        MutableComponent line = Component.translatable(
                "meshelium.options.farfield.layer.saving_progress",
                Component.literal(Integer.toString(saved)),
                Component.literal(Integer.toString(census.savedDegraded)),
                Component.literal(Integer.toString(census.waiting)),
                Component.literal(Integer.toString(census.stale)));
        // Green only when nothing is waiting anywhere - here AND on the
        // leaving line - because "is it safe to turn my render distance
        // down yet" is the exact question this line exists to answer,
        // and a pinned column still being written in the background is
        // not yet a yes.
        boolean finished = saved > 0 && census.waiting == 0
                && ExtractDispatch.pinnedCount() == 0
                && ExtractDispatch.pendingCaptureCount() == 0;
        return line.withStyle(finished ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    /**
     * The leaving line, M6 shape: the pins being written out in the
     * background, then the two loss populations WORDED SEPARATELY - the
     * review's adjudication. {@code behind} is repairable (the gauge
     * decrements when a revisit re-receives the column); {@code lost}
     * is permanent (the counted heap backstop, a dead store, or a world
     * closed before the five-second drain finished). The old line
     * showed one lumped "lost" figure and a "held of limit" pair whose
     * limit was really the leak tripwire, not a capacity - there is no
     * admission cap on pins, by design, so showing one promised a loss
     * mode that no longer exists.
     *
     * <p>ZERO is the contract for both numbers: behind or lost moving
     * on an ordinary session is a bug report, not a tuning hint - and
     * the dial above cannot help either way, because the pin worker is
     * off the frame budget entirely.</p>
     */
    private Component leavingText() {
        if (!FarFieldConfig.enabled()) {
            return Component.empty();
        }
        long behind = Math.max(0L, ExtractDispatch.farSaveBehind());
        long lost = ExtractDispatch.farSaveLost.sum();
        int held = ExtractDispatch.pinnedCount()
                + ExtractDispatch.pendingCaptureCount();
        MutableComponent line = Component.translatable(
                "meshelium.options.farfield.layer.saving_leaving",
                Component.literal(Integer.toString(held)),
                Component.literal(Long.toString(behind)),
                Component.literal(Long.toString(lost)));
        return line.withStyle(lost > 0 ? ChatFormatting.RED
                : behind > 0 ? ChatFormatting.YELLOW : ChatFormatting.GRAY);
    }

    /**
     * Refresh the two live lines. Gauge reads plus the record fold,
     * which ExtractDispatch caches for a second so twenty ticks of an
     * open page cost one bounded walk - no disk, no locks - and they
     * exist only on layer 1.
     */
    @Override
    public void tick() {
        super.tick();
        if (this.savingLine != null) {
            this.savingLine.setMessage(savingText());
        }
        if (this.leavingLine != null) {
            this.leavingLine.setMessage(leavingText());
        }
    }

    private MultiLineTextWidget banner(Component text) {
        MultiLineTextWidget widget = new MultiLineTextWidget(text, this.font);
        widget.setMaxWidth(BANNER_WIDTH);
        widget.setCentered(true);
        return widget;
    }

    /**
     * True when this control may be operated right now.
     *
     * <p>THE ONE LINE to change if the call in this class's javadoc is
     * ever reversed. Dropping the {@code isWired} term makes every
     * planned row clickable and persistent while still labelled "not used
     * yet"; the rest of the page, including the labels and the tooltips,
     * needs no other edit. The reason it is not written that way today is
     * in the class javadoc: an inert toggle that moves is how a settings
     * page pretends to work, and the bill arrives on the update that
     * wires the feature.</p>
     */
    private boolean isActive(Control control) {
        return FarFieldConfig.isWired(this.layer, control)
                && !this.gateLocked
                && !FarFieldConfig.isSuperseded(this.layer, control)
                && !FarFieldConfig.isPropertyOverridden(this.layer, control);
    }

    /**
     * The label a row shows: the plain one when the control is connected,
     * and the same one wrapped in "not used yet" when it is not. Note this
     * keys off WIRED only, never off the gate or a {@code -D} flag: those
     * two have their own banners and their own tooltip lines, and stacking
     * three explanations into one label helps nobody.
     */
    private Component rowLabel(Control control, String labelKey) {
        MutableComponent label = Component.translatable(labelKey);
        if (FarFieldConfig.isWired(this.layer, control)) {
            return label;
        }
        // Retired outranks planned: both are unwired, but "not used yet"
        // promises an update, and for a row whose job vanilla turned out
        // to own already there is no such update coming.
        if (FarFieldConfig.isRetired(this.layer, control)) {
            return Component.translatable("meshelium.options.farfield.retired.row", label);
        }
        return Component.translatable("meshelium.options.farfield.planned.row", label);
    }

    /**
     * The row's description plus its apply semantics, with the honesty
     * rule the other pages share: an unusable row's only truthful
     * annotation is why it is unusable, so the semantics line is REPLACED
     * rather than appended to. "Not used yet" outranks "needs Vulkan",
     * because a row that does not exist yet would not work on Vulkan
     * either.
     */
    private Tooltip tip(Control control, String descriptionKey, String appliesKey) {
        String semanticsKey;
        if (FarFieldConfig.isRetired(this.layer, control)) {
            semanticsKey = "meshelium.options.applies.retired";
        } else if (!FarFieldConfig.isWired(this.layer, control)) {
            semanticsKey = "meshelium.options.applies.planned";
        } else if (FarFieldConfig.isSuperseded(this.layer, control)) {
            // Outranks the Vulkan line for the same reason "not used yet"
            // does: a row another row has overruled would be overruled on
            // Vulkan too, and the useful sentence is the one that names
            // the row holding it down.
            semanticsKey = "meshelium.options.applies.superseded";
        } else if (this.gateLocked) {
            semanticsKey = "meshelium.options.applies.vulkan";
        } else {
            semanticsKey = appliesKey;
        }
        return Tooltip.create(Component.translatable(descriptionKey)
                .append(Component.literal("\n\n"))
                .append(Component.translatable(semanticsKey)
                        .withStyle(ChatFormatting.GRAY)));
    }

    /** Everything a live row does on change, in the mandated order. */
    private void onChanged() {
        FarFieldConfig config = FarFieldConfig.get();
        // Hand-editing anything means the config is the player's own mix.
        // Values are untouched, only the selection moves.
        config.markCustom();
        config.save();
        // Mandatory after any change that can alter the master state. It
        // is a volatile write; calling it from rows that only move a
        // distance costs nothing and removes a whole class of "which rows
        // need it" mistakes.
        ExtractDispatch.refreshArmed();
        refreshLockedRows();
    }

    /**
     * Re-apply the locks one row can put on another, without rebuilding
     * the page.
     *
     * <p><b>No relation answers true today.</b> pre12's did - Lighting
     * locked Underwater Shading - and pre13's N4 removed it, because the
     * argument only held for records that carry a light plane and a live
     * world always contains records that do not
     * ({@link FarFieldConfig#isSuperseded} carries the whole reasoning).
     * The pass is kept and still runs on every change: it is two field
     * writes on one widget, it is the only thing that makes a lock land
     * the moment the row imposing it moves rather than on the next visit
     * to the page, and a relation added to {@code isSuperseded} without a
     * row named here would be a lock the player could not see. That
     * method names this one for exactly that reason.</p>
     */
    private void refreshLockedRows() {
        if (this.waterLookRow == null) {
            return; // change arrived before init(), or from a headless test
        }
        this.waterLookRow.active = isActive(Control.WATER_LOOK);
        this.waterLookRow.setTooltip(tip(Control.WATER_LOOK,
                "meshelium.options.tooltip.farfield.layer.water_look",
                "meshelium.options.applies.far_now"));
    }

    private CycleButton<Boolean> onOff(Control control, String labelKey,
            String tooltipKey, String appliesKey,
            java.util.function.BooleanSupplier current,
            java.util.function.Consumer<Boolean> apply) {
        CycleButton<Boolean> button = CycleButton.onOffBuilder(current.getAsBoolean())
                .create(rowLabel(control, labelKey), (b, value) -> {
                    apply.accept(value);
                    onChanged();
                });
        button.setWidth(WIDGET_WIDTH);
        button.active = isActive(control);
        button.setTooltip(tip(control, tooltipKey, appliesKey));
        return button;
    }

    /**
     * A two-value cycle over a BOOLEAN whose steps have names of their own
     * rather than On and Off.
     *
     * <p>Used by exactly one row, Distant Water, and the reason is the
     * owner's own sentence: he asked for "an option for this that just
     * looks like stock minecraft", so the value the row shows has to say
     * <i>Stock Minecraft</i>. It stores a boolean because the setting was
     * shipped as one through pre12 and every saved config already holds it
     * under that shape; changing the field's TYPE would make GSON fail the
     * whole file and silently reset every other row with it (see
     * {@code FarFieldConfig.LayerSettings.waterDepth}).</p>
     *
     * <p>The cycle order is true then false, which is
     * {@code CycleButton.BOOLEAN_OPTIONS}' own order.</p>
     */
    private CycleButton<Boolean> boolCycle(Control control, String labelKey,
            String tooltipKey, String appliesKey,
            String trueKey, String falseKey,
            java.util.function.BooleanSupplier current,
            java.util.function.Consumer<Boolean> apply) {
        // javap, 26.2 merged jar: CycleButton.booleanBuilder(Component,
        // Component, boolean) builds the stringifier from the two labels
        // and calls withValues(BOOLEAN_OPTIONS), which is
        // ImmutableList.of(TRUE, FALSE) (clinit ip 8-17). So the cycle
        // order is true then false and no withValues call is needed here.
        CycleButton<Boolean> button = CycleButton
                .booleanBuilder(Component.translatable(trueKey),
                        Component.translatable(falseKey), current.getAsBoolean())
                .create(rowLabel(control, labelKey), (b, value) -> {
                    apply.accept(value);
                    onChanged();
                });
        button.setWidth(WIDGET_WIDTH);
        button.active = isActive(control);
        button.setTooltip(tip(control, tooltipKey, appliesKey));
        return button;
    }

    /**
     * A cycle over a fixed set of int values. Used for the detail scale,
     * where the legal values are 1, 2 and 4 and nothing between them means
     * anything.
     */
    private CycleButton<Integer> cycle(Control control, String labelKey,
            String tooltipKey, String appliesKey, int[] values,
            IntFunction<Component> naming,
            IntSupplier current, IntConsumer apply) {
        Integer[] boxed = new Integer[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        CycleButton<Integer> button = CycleButton
                .<Integer>builder(value -> naming.apply(value), current.getAsInt())
                .withValues(boxed)
                .create(rowLabel(control, labelKey), (b, value) -> {
                    apply.accept(value);
                    onChanged();
                });
        button.setWidth(WIDGET_WIDTH);
        button.active = isActive(control);
        button.setTooltip(tip(control, tooltipKey, appliesKey));
        return button;
    }

    private LayerIntSlider slider(Control control, String labelKey,
            String tooltipKey, String appliesKey, int min, int max,
            IntSupplier current, IntConsumer apply,
            IntFunction<Component> naming) {
        LayerIntSlider widget = new LayerIntSlider(labelKey,
                !FarFieldConfig.isWired(this.layer, control),
                min, max, current.getAsInt(), naming, value -> {
                    apply.accept(value);
                    onChanged();
                });
        widget.active = isActive(control);
        widget.setTooltip(tip(control, tooltipKey, appliesKey));
        return widget;
    }

    // ------------------------------------------------------------------
    // Value naming
    // ------------------------------------------------------------------

    /** A distance in chunks, whose 0 stop reads Off. */
    private static Component distanceText(int chunks) {
        return chunks <= FarFieldConfig.MIN_L1_RADIUS_CHUNKS
                ? Component.translatable("meshelium.options.cull.off")
                : Component.translatable("meshelium.options.cull.chunks",
                        Component.literal(Integer.toString(chunks)));
    }

    private static Component scaleText(int scale) {
        return switch (scale) {
            case FarFieldConfig.SCALE_HALF ->
                    Component.translatable("meshelium.options.farfield.layer.scale.half");
            case FarFieldConfig.SCALE_QUARTER ->
                    Component.translatable("meshelium.options.farfield.layer.scale.quarter");
            default ->
                    Component.translatable("meshelium.options.farfield.layer.scale.full");
        };
    }

    /**
     * Colour blending: Per Chunk or Per Block. The retired
     * {@code BLEND_FINE} still reads as Per Block, because
     * {@code normalizeBlend} folds it there and a config that predates
     * pre19 may still hold it until it is next saved.
     */
    private static Component blendText(int blend) {
        return switch (blend) {
            case FarFieldConfig.BLEND_COARSE, FarFieldConfig.BLEND_FINE ->
                    Component.translatable("meshelium.options.farfield.layer.colour_blend.coarse");
            default ->
                    Component.translatable("meshelium.options.farfield.layer.colour_blend.chunk");
        };
    }

    /** Background Saving: the three speeds, named for what they cost. */
    private static Component cacheSpeedText(int speed) {
        return switch (speed) {
            case FarFieldConfig.CACHE_BALANCED -> Component.translatable(
                    "meshelium.options.farfield.layer.cache_speed.balanced");
            case FarFieldConfig.CACHE_FAST -> Component.translatable(
                    "meshelium.options.farfield.layer.cache_speed.fast");
            default -> Component.translatable(
                    "meshelium.options.farfield.layer.cache_speed.gentle");
        };
    }

    private static Component lightingText(int lighting) {
        return switch (lighting) {
            case FarFieldConfig.LIGHT_GLOW ->
                    Component.translatable("meshelium.options.farfield.layer.lighting.glow");
            case FarFieldConfig.LIGHT_REAL ->
                    Component.translatable("meshelium.options.farfield.layer.lighting.real");
            default ->
                    Component.translatable("meshelium.options.farfield.layer.lighting.flat");
        };
    }

    private static Component smoothText(int step) {
        return switch (step) {
            case FarFieldConfig.SMOOTH_EDGES ->
                    Component.translatable(
                            "meshelium.options.farfield.layer.smooth_light.edges");
            case FarFieldConfig.SMOOTH_FULL ->
                    Component.translatable(
                            "meshelium.options.farfield.layer.smooth_light.full");
            default -> Component.translatable("meshelium.options.cull.off");
        };
    }

    /** Shading strength, whose 0 stop reads Off. */
    private static Component contactText(int percent) {
        return percent <= FarFieldConfig.MIN_CONTACT_SHADE
                ? Component.translatable("meshelium.options.cull.off")
                : Component.translatable(
                        "meshelium.options.farfield.layer.contact.percent",
                        Component.literal(Integer.toString(percent)));
    }

    private static Component textureText(int step) {
        return switch (step) {
            case 1 -> Component.translatable("meshelium.options.farfield.layer.texture.slight");
            case 2 -> Component.translatable("meshelium.options.farfield.layer.texture.medium");
            case 3 -> Component.translatable("meshelium.options.farfield.layer.texture.strong");
            case FarFieldConfig.TEXTURE_FLAT ->
                    Component.translatable("meshelium.options.farfield.layer.texture.flat");
            default -> Component.translatable("meshelium.options.cull.off");
        };
    }

    /**
     * One int slider for every numeric row on this page. Continuous over
     * its range like the Advanced screen's cull sliders, because both
     * numbers here are things a player tunes by feel.
     *
     * <p>Writes go through the {@code apply} callback and only when the
     * rounded value actually MOVED, so dragging inside one step does not
     * rewrite the config file forty times a second.</p>
     *
     * <p>The "not used yet" marker wraps the WHOLE message here, value
     * included, where on a cycle button it wraps only the caption. Both
     * read naturally in place and both contain the same substring, which
     * is what a gametest would search for.</p>
     */
    private static final class LayerIntSlider extends AbstractSliderButton {

        private final String labelKey;
        private final boolean planned;
        private final int min;
        private final int max;
        private final IntFunction<Component> naming;
        private final IntConsumer apply;
        private int displayed;

        LayerIntSlider(String labelKey, boolean planned, int min, int max,
                int initial, IntFunction<Component> naming, IntConsumer apply) {
            super(0, 0, WIDGET_WIDTH, 20, Component.empty(),
                    fraction(initial, min, max));
            this.labelKey = labelKey;
            this.planned = planned;
            this.min = min;
            this.max = max;
            this.naming = naming;
            this.apply = apply;
            this.displayed = initial;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            MutableComponent base = Component.translatable(this.labelKey,
                    this.naming.apply(this.displayed));
            setMessage(this.planned
                    ? Component.translatable(
                            "meshelium.options.farfield.planned.row", base)
                    : base);
        }

        @Override
        protected void applyValue() {
            // Named "picked", not "value": the inherited slider fraction
            // is also called value, and a local that shadows it in the one
            // method that reads both is a trap for the next editor.
            int picked = this.min
                    + (int) Math.round(this.value * (this.max - this.min));
            if (picked != this.displayed) {
                this.displayed = picked;
                this.apply.accept(picked);
            }
            updateMessage();
        }

        private static double fraction(int value, int min, int max) {
            if (max <= min) {
                return 0.0;
            }
            return Math.max(0.0, Math.min(1.0, (value - min) / (double) (max - min)));
        }
    }

    /** RestrictionsScreen's sequence, the same one the sibling pages use. */
    @Override
    protected void repositionElements() {
        this.scrollArea.arrangeElements();
        this.scrollArea.setMaxHeight(this.layout.getContentHeight());
        this.layout.arrangeElements();
    }

    @Override
    public void onClose() {
        // Straight back to the LIVE parent instance, never a fresh one.
        // The Far Terrain page re-reads the config in its own tick, so the
        // sliders up there catch up with anything changed down here
        // without being rebuilt (rebuilding would duplicate its
        // HeaderAndFooterLayout contents; see MesheliumOptionsScreen).
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}
