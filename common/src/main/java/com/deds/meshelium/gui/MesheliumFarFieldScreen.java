/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.gui;

import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.farfield.FarField;
import com.deds.meshelium.farfield.FarFieldConfig;
import com.deds.meshelium.farfield.FarFieldConfig.Control;
import com.deds.meshelium.farfield.FarFieldConfig.Layer;
import com.deds.meshelium.farfield.FarFieldConfig.Preset;
import com.deds.meshelium.farfield.FarFieldResidency;
import com.deds.meshelium.farfield.extract.ExtractDispatch;

import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/**
 * Far Terrain: the horizon past the render distance, drawn from shells
 * of chunks the player has already seen (docs/FAR-FIELD-DESIGN.md, and
 * items D1 to D3 of the owner's pre1 playtest list).
 *
 * <h2>The page the owner asked for</h2>
 * <p>Verbatim: "maybe even have like a slider with preset... so they just
 * set the preset to like always load chunks (no lod), highest detail,
 * balanced, performant, ultra performant. and then just have it go to
 * custom anytime they change anything. also maybe below we would just want
 * like a render distance slider, and then a lod slider since thats what
 * most people would want". So the top of this page is exactly three
 * controls under the master switch: the preset, the render distance and
 * the LOD distance. Everything else is a section further down or a page
 * behind a button, and a player who never scrolls has still had the whole
 * feature offered to them.</p>
 *
 * <p>The preset control is a SLIDER and not a cycle button because that is
 * what was asked for, and because the five presets are a single ordered
 * axis: leftmost keeps the most real chunks, rightmost keeps the fewest.
 * Custom is stop zero rather than a hidden state, so the handle always
 * sits somewhere honest. Dragging TO Custom writes no values at all, it
 * only stops the page claiming to track a preset; dragging to any other
 * stop writes the whole config from {@link Preset}'s table.</p>
 *
 * <h2>Why its own screen and not three more Advanced rows</h2>
 * <p>Two of the things the mandate asks for are not rows at all: a
 * FOLDER PATH, which is a wrapped paragraph of text and often longer
 * than the window, and a DELETE button, which needs a confirmation step
 * and a result line beside it. Stuffing either into the Advanced screen's
 * 200-wide row list would either truncate the path (useless: a path you
 * cannot read is not a path) or put a destructive button one slip away
 * from the render rows. The Advanced screen keeps one entry row that
 * opens this page, exactly as the main screen keeps one that opens
 * Advanced. The three layer pages sit behind this one for the same
 * reason, one level further down.</p>
 *
 * <p>Structure is the house recipe verbatim from
 * {@link MesheliumAdvancedScreen}: title header, rows in a vanilla
 * {@link ScrollableLayout}, Done in the footer where no future row can
 * push it off the window ({@link HeaderAndFooterLayout}), and an
 * {@code onClose} that returns to the LIVE parent instance rather than
 * building a fresh one.</p>
 *
 * <h2>The two rules every control here obeys</h2>
 * <ol>
 * <li><b>Persist.</b> Every change writes the field and calls
 *     {@code FarFieldConfig.save()} - the same write-per-change the cull
 *     sliders do, so nothing is lost if the game exits without a clean
 *     shutdown.</li>
 * <li><b>Re-arm the seams.</b> Every change also calls
 *     {@link ExtractDispatch#refreshArmed()}. That method's javadoc makes
 *     it mandatory after any master-switch change and it is not
 *     optional: the chunk-lifecycle mixins gate on ONE static volatile
 *     that is resolved at class load, so without the refresh a player
 *     who turns Far Terrain on mid-session gets a store that is never
 *     written to until the next world load, and reasonably concludes the
 *     feature does nothing. It is a volatile write; calling it from the
 *     rows that only move the radius costs nothing and removes a whole
 *     class of "which rows need it" mistakes.</li>
 * </ol>
 * <p>And the third, added with the presets: <b>every individual control
 * flips the selection to Custom</b>, values untouched.</p>
 *
 * <h2>Why the render distance is applied late, and the far rows are not</h2>
 * <p>Vanilla's own render-distance slider does not apply while you drag
 * it: the option is built as {@code new OptionInstance.IntRange(min, max,
 * false)} and that last flag is applyValueImmediately (bytecode-cited in
 * {@code MesheliumExtendedRd.applyRange}, which replicates it exactly).
 * Dragging 32 down to 8 through every intermediate value would make the
 * client re-tier its whole chunk storage four times on the way. So the
 * render-distance slider and the preset slider both hold their value and
 * commit it on release, or after {@value #APPLY_DEBOUNCE_TICKS} quiet
 * ticks for the keyboard path. The far rows keep applying instantly:
 * their walker is budgeted per pump and moves a handful of sections a
 * frame in either direction, which is the whole point of the both-
 * direction rule.</p>
 *
 * <h2>The size readout never touches the disk on the render thread</h2>
 * <p>{@link FarField#requestCacheSizeBytes} walks the cache folder on a
 * short-lived daemon thread and answers from it; this screen only ever
 * reads a volatile long in {@link #tick()}. A {@code Files.walk} over a
 * few thousand region files inside {@code render} would be a visible
 * hitch on the one screen whose whole job is explaining a disk cache.
 * The refresh is paced at {@value #SIZE_REFRESH_TICKS} ticks and is
 * re-requested immediately after a delete, so the number the player sees
 * after pressing the button is the number after the button.</p>
 *
 * <h2>Reaching these rows from a gametest</h2>
 * <p>Every widget on this page lives inside the {@link ScrollableLayout},
 * and fabric's {@code clickScreenButton} cannot see into one: it walks
 * {@code renderables} and descends only through
 * {@code LayoutElement.visitWidgets}, which no class in the
 * {@code AbstractContainerWidget} hierarchy overrides (javap census on
 * the 26.2 jar). {@code MesheliumBootSmokeTest} already carries the two
 * helpers that do work, and they are what a future leg must use here:
 * {@code collectWidgets(screen, list)} walks the real event tree
 * ({@code Screen.children()} to the scroll container to the rows) for
 * assertions, and {@code pressWidgetTreeButton(context, key)} presses a
 * match the way fabric would. So the three layer rows are reachable as
 * {@code pressWidgetTreeButton(context, "meshelium.options.farfield.l1")}
 * and so on for {@code l2} and {@code l3}, and the Done button on any of
 * these pages stays on plain {@code clickScreenButton} because the footer
 * is a direct renderable. One catch for label matching: the layer rows
 * for anything not yet wired render as "Layer 2: Middle... (not used
 * yet)", so a press helper that compares with {@code equals} needs the
 * full string while {@code requireRowWidget}'s {@code contains} does
 * not.</p>
 *
 * <h2>Player-facing text</h2>
 * <p>No em or en dashes anywhere (a lang lint in the boot smoke test
 * enforces it), no jargon beyond the one word the owner uses himself:
 * the feature is "Far Terrain", the cache is "saved terrain", and the
 * radius tooltip says in plain words that a very large number may stop
 * short of itself because the graphics card's terrain budget fills -
 * which is the honest description of the promotion stall in
 * {@code TerrainResidency.farPromotionHasRoom}, and the reason the
 * shipped default is
 * {@value FarFieldConfig#DEFAULT_L1_RADIUS_CHUNKS} rather than a bigger
 * number that would silently render smaller.</p>
 */
public class MesheliumFarFieldScreen extends Screen {

    private static final int WIDGET_WIDTH = 200;
    private static final int BANNER_WIDTH = 340;

    /** Cache-size refresh cadence, ticks (2 seconds at 20 tps). */
    private static final int SIZE_REFRESH_TICKS = 40;
    /** How long the delete button holds its result before resetting. */
    private static final int CLEAR_RESULT_TICKS = 100;
    /**
     * Quiet ticks before a held slider value is committed. Short enough
     * that a keyboard nudge feels immediate, long enough that dragging
     * across the range commits once instead of thirty times.
     */
    private static final int APPLY_DEBOUNCE_TICKS = 8;

    /** {@link #cacheBytes} sentinel: nothing measured yet this screen. */
    private static final long SIZE_PENDING = -2L;
    /** {@link #cacheBytes} sentinel: the walk failed. */
    private static final long SIZE_FAILED = -1L;

    private static final int CLEAR_IDLE = 0;
    private static final int CLEAR_WORKING = 1;
    private static final int CLEAR_DONE = 2;
    private static final int CLEAR_FAILED = 3;

    /** Vanilla's own floor and ceiling if the option cannot be read. */
    private static final int FALLBACK_MIN_RENDER_DISTANCE = 2;
    private static final int FALLBACK_MAX_RENDER_DISTANCE = 32;

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    /** The scrolling middle band; built in {@link #init()} (it needs minecraft). */
    private ScrollableLayout scrollArea;

    /**
     * True when the backend gate means none of this can take effect.
     * Read once at construction, the main screen's rule: the gate cannot
     * change without a restart, so re-reading it per frame would only
     * invite a row that disagrees with its own tooltip.
     */
    private final boolean gateLocked;

    // ---- live state, all written from tick() unless marked volatile ----

    private CycleButton<Boolean> masterButton;
    private PresetSlider presetSlider;
    private RenderDistanceSlider renderDistanceSlider;
    private FarRadiusSlider radiusSlider;
    private CycleButton<Boolean> bandButton;
    private StringWidget sizeLine;
    /** Live "what is it doing" row; see its construction for the why. */
    private StringWidget statusLine;
    private Button clearButton;

    /** Bytes under the cache folder, or one of the SIZE_* sentinels. */
    private volatile long cacheBytes = SIZE_PENDING;
    /** True between issuing a size walk and its answer (off-thread write). */
    private volatile boolean sizeInFlight;
    /** Set off-thread when something invalidated the readout. */
    private volatile boolean sizeDirty = true;
    /** One of the CLEAR_* codes; written from the delete callback too. */
    private volatile int clearState = CLEAR_IDLE;

    private int sizeCooldownTicks;
    /** First click arms, second click deletes (the reset-button pattern). */
    private boolean clearArmed;
    private int clearResultTicks;

    /** Preset chosen on the slider but not committed yet, or null. */
    private Preset pendingPreset;
    /** Render distance chosen on the slider but not committed yet, or -1. */
    private int pendingRenderDistance = -1;
    /** Ticks left before the pending values above are committed. */
    private int applyDebounceTicks;

    public MesheliumFarFieldScreen(Screen parent) {
        super(Component.translatable("meshelium.options.farfield.title"));
        this.parent = parent;
        this.gateLocked = MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS;
    }

    @Override
    protected void init() {
        FarFieldConfig config = FarFieldConfig.get();
        reconcilePresetWithRenderDistance(config);

        this.layout.addTitleHeader(this.getTitle(), this.font);

        LinearLayout rows = LinearLayout.vertical().spacing(2);
        rows.defaultCellSetting().alignHorizontallyCenter();

        // Per-screen dev-override census, the house rule: a -D flag only
        // raises a banner over rows THIS screen can show.
        boolean enabledOverridden =
                System.getProperty("meshelium.farfield.enabled") != null;
        boolean radiusOverridden =
                FarFieldConfig.isPropertyOverridden(Layer.L1, Control.DISTANCE)
                        || FarFieldConfig.isPropertyOverridden(Layer.L1,
                                Control.LAYER_ENABLED);
        boolean bandOverridden =
                System.getProperty("meshelium.farfield.surfaceBand") != null;
        boolean presetOverridden =
                System.getProperty("meshelium.farfield.preset") != null;
        if (enabledOverridden || radiusOverridden || bandOverridden || presetOverridden) {
            rows.addChild(banner(Component.translatable("meshelium.options.dev_override")),
                    s -> s.paddingTop(2).paddingBottom(2));
        }

        if (this.gateLocked) {
            rows.addChild(banner(Component.translatable("meshelium.options.advanced.locked")
                            .withStyle(ChatFormatting.YELLOW)),
                    s -> s.paddingTop(2).paddingBottom(2));
        }

        // ---------------- section 1: the three controls -----------------

        rows.addChild(sectionHeader("meshelium.options.farfield.section.quick"),
                s -> s.paddingTop(6).paddingBottom(2));

        // 1. The master switch. Default OFF and it stays off until a
        // player asks for it: off means no thread, no folder and no
        // allocation (FAR-FIELD-DESIGN.md section 6), which is a promise
        // the suite asserts, so this row is the only thing in the mod
        // that can break it.
        this.masterButton = CycleButton.onOffBuilder(config.enabled)
                .create(Component.translatable("meshelium.options.farfield.enabled"),
                        (b, value) -> {
                            config.enabled = value;
                            markCustomAndSave(config);
                        });
        this.masterButton.setWidth(WIDGET_WIDTH);
        this.masterButton.active = !this.gateLocked && !enabledOverridden;
        this.masterButton.setTooltip(tip("meshelium.options.tooltip.farfield.enabled",
                "meshelium.options.applies.now"));
        rows.addChild(this.masterButton, s -> s.paddingTop(4));

        // 2. The preset. It writes the master switch, both distances and
        // the saved-detail row, so it locks whenever ANY of them is
        // locked; a preset that silently skips a row it cannot write
        // would be the worst kind of half-applied.
        this.presetSlider = new PresetSlider(!this.gateLocked && !enabledOverridden
                && !radiusOverridden && !bandOverridden && !presetOverridden);
        this.presetSlider.setTooltip(tip("meshelium.options.tooltip.farfield.preset",
                "meshelium.options.applies.now"));
        rows.addChild(this.presetSlider);

        // 3. Minecraft's own render distance, put here because the owner
        // asked for it here and because the whole trade this page exists
        // to offer is between this number and the next one. Never
        // gate-locked: it is vanilla's setting and it works whatever
        // Meshelium is doing.
        this.renderDistanceSlider = new RenderDistanceSlider();
        this.renderDistanceSlider.setTooltip(
                Tooltip.create(withSemantics(Component.translatable(
                        "meshelium.options.tooltip.farfield.render_distance"),
                        "meshelium.options.applies.now")));
        rows.addChild(this.renderDistanceSlider);

        // 4. How far out. Live in BOTH directions: the walker re-reads
        // this every pump and demotes as readily as it promotes. Zero is
        // the same state as layer 1 switched off on its own page, and
        // this slider writes both so the two can never disagree.
        this.radiusSlider = new FarRadiusSlider(!this.gateLocked && !radiusOverridden);
        this.radiusSlider.setTooltip(tip("meshelium.options.tooltip.farfield.radius",
                "meshelium.options.applies.now"));
        rows.addChild(this.radiusSlider);

        // ---------------- section 2: the layer ladder -------------------

        rows.addChild(sectionHeader("meshelium.options.farfield.section.layers"),
                s -> s.paddingTop(8).paddingBottom(2));

        rows.addChild(banner(Component.translatable(
                        "meshelium.options.farfield.layers.note")
                        .withStyle(ChatFormatting.GRAY)),
                s -> s.paddingBottom(2));

        for (Layer layer : Layer.values()) {
            rows.addChild(layerButton(layer));
        }

        // ---------------- section 3: what is on disk --------------------

        rows.addChild(sectionHeader("meshelium.options.farfield.section.storage"),
                s -> s.paddingTop(8).paddingBottom(2));

        // What gets saved. Named outcomes rather than On/Off, the
        // Duplicate Terrain Memory rule: "Surface Only: Off" could mean
        // either half of the sentence. It lives in this section and NOT
        // on a layer page because extraction runs once per chunk and
        // writes one record every layer reads: it decides what goes on
        // disk, not how any one layer draws.
        this.bandButton = CycleButton
                .booleanBuilder(
                        Component.translatable("meshelium.options.farfield.band.surface"),
                        Component.translatable("meshelium.options.farfield.band.full"),
                        config.surfaceBand)
                .create(Component.translatable("meshelium.options.farfield.band"),
                        (b, value) -> {
                            config.surfaceBand = value;
                            markCustomAndSave(config);
                        });
        this.bandButton.setWidth(WIDGET_WIDTH);
        this.bandButton.active = !this.gateLocked && !bandOverridden;
        this.bandButton.setTooltip(tip("meshelium.options.tooltip.farfield.band",
                "meshelium.options.applies.new_cache"));
        rows.addChild(this.bandButton);

        // The note the mandate asks for, on the screen and not only in a
        // tooltip: this row is WHY the cache is affordable at all, and a
        // player who flips it to Everything should know that before they
        // wonder where their disk went.
        rows.addChild(banner(Component.translatable("meshelium.options.farfield.band.note")
                        .withStyle(ChatFormatting.GRAY)),
                s -> s.paddingTop(2).paddingBottom(4));

        // Where it lives. Wrapped, because a Windows game directory
        // under a long user name is easily wider than the window.
        MultiLineTextWidget folder = banner(Component.translatable(
                "meshelium.options.farfield.folder",
                Component.literal(FarField.cacheFolder().toString())));
        folder.setTooltip(Tooltip.create(
                Component.translatable("meshelium.options.tooltip.farfield.folder")));
        rows.addChild(folder, s -> s.paddingTop(2));

        // What it costs. Refreshed off-thread; see the class javadoc.
        // setMaxWidth is load bearing, not decoration: without it a
        // StringWidget keeps the width it was CONSTRUCTED with (getWidth
        // falls through to the AbstractWidget field, bytecode ip 51-55)
        // and clamps any longer message, so the first line this row ever
        // shows would silently truncate every longer one after it. With
        // a max width the cached width is re-measured on every
        // setMessage instead.
        this.sizeLine = new StringWidget(sizeText(), this.font);
        this.sizeLine.setMaxWidth(BANNER_WIDTH);
        this.sizeLine.setTooltip(Tooltip.create(
                Component.translatable("meshelium.options.tooltip.farfield.size")));
        rows.addChild(this.sizeLine, s -> s.paddingTop(2).paddingBottom(2));

        // What it is doing RIGHT NOW. Added after the owner's first
        // playtest, where the honest answer to "why can I not see it"
        // was unknowable from this screen: disk size alone cannot tell
        // "nothing saved yet" from "saved but not drawn", and those two
        // want completely different fixes. Both numbers are plain
        // counter reads, no disk and no locks.
        this.statusLine = new StringWidget(statusText(), this.font);
        this.statusLine.setMaxWidth(BANNER_WIDTH);
        this.statusLine.setTooltip(Tooltip.create(
                Component.translatable("meshelium.options.tooltip.farfield.status")));
        rows.addChild(this.statusLine, s -> s.paddingTop(2).paddingBottom(2));

        // Delete it. TWO clicks, the reset-button pattern from the
        // main screen: the first arms and relabels in red, the second
        // does it. This one deletes files a player cannot get back
        // except by travelling again, so a modal-free confirmation that
        // stays under the cursor is the least it deserves.
        this.clearButton = Button.builder(clearText(), b -> {
            if (this.clearState == CLEAR_WORKING) {
                return; // a delete is already running; ignore the mash
            }
            if (!this.clearArmed) {
                this.clearArmed = true;
                this.clearState = CLEAR_IDLE;
                b.setMessage(clearText());
                return;
            }
            this.clearArmed = false;
            this.clearState = CLEAR_WORKING;
            b.setMessage(clearText());
            // pre16 M2 note: the record map's reset rides the STORE's
            // own invalidated event (posted only when the clear
            // SUCCEEDS, consumed by the game-thread pump) - never a
            // call from this button, which must neither class-load the
            // extraction layer on the master-off path nor re-dirty a
            // correct map when the delete fails. See
            // FarField.consumeStoreInvalidated().
            FarField.requestCacheClear(ok -> {
                // Off-thread callback: volatiles only, tick() renders it.
                this.clearState = ok ? CLEAR_DONE : CLEAR_FAILED;
                this.cacheBytes = SIZE_PENDING;
                this.sizeDirty = true;
            });
        }).width(WIDGET_WIDTH).build();
        // Never gate-locked: the cache is on disk whatever the backend is
        // doing, and a player on OpenGL who wants the space back should
        // be able to take it.
        this.clearButton.setTooltip(Tooltip.create(
                Component.translatable("meshelium.options.tooltip.farfield.clear")));
        rows.addChild(this.clearButton, s -> s.paddingTop(2));

        this.scrollArea = new ScrollableLayout(this.minecraft, rows,
                this.layout.getContentHeight());
        this.layout.addToContents(this.scrollArea);

        this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
                .width(WIDGET_WIDTH).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    private MultiLineTextWidget banner(Component text) {
        MultiLineTextWidget widget = new MultiLineTextWidget(text, this.font);
        widget.setMaxWidth(BANNER_WIDTH);
        widget.setCentered(true);
        return widget;
    }

    /** A quiet divider line, so three sections do not read as one list. */
    private StringWidget sectionHeader(String key) {
        StringWidget widget = new StringWidget(
                Component.translatable(key).withStyle(ChatFormatting.GRAY), this.font);
        widget.setMaxWidth(BANNER_WIDTH);
        return widget;
    }

    /**
     * One entry row per layer. The row carries "not used yet" for any
     * layer nothing reads, so a player learns that before they walk into
     * the page rather than after.
     */
    private Button layerButton(Layer layer) {
        MutableComponent label =
                Component.translatable("meshelium.options.farfield." + layer.key);
        boolean wired = FarFieldConfig.isLayerWired(layer);
        Component shown = wired
                ? label
                : Component.translatable("meshelium.options.farfield.planned.row", label);
        Button button = Button.builder(shown, b -> {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(new MesheliumFarLayerScreen(this, layer));
            }
        }).width(WIDGET_WIDTH).build();
        // Never locked, even when the layer is not wired and even on a
        // gate-locked backend: the page behind it is readable either way
        // and every row on it locks itself. A button a player cannot press
        // to read an explanation of why they cannot press it is a joke.
        button.setTooltip(Tooltip.create(Component.translatable(
                "meshelium.options.tooltip.farfield." + layer.key)));
        return button;
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Hand-editing any single control means the config is the player's own
     * mix from now on. Values are NOT touched, only the selection moves,
     * which is the brief's rule verbatim.
     */
    private void markCustomAndSave(FarFieldConfig config) {
        config.markCustom();
        config.save();
        ExtractDispatch.refreshArmed();
    }

    /**
     * Apply a whole preset: the far-field half here, the vanilla render
     * distance right after, then push every row's displayed value back
     * into line with what was just written.
     */
    private void applyPreset(Preset preset) {
        FarFieldConfig config = FarFieldConfig.get();
        config.applyPreset(preset);
        config.save();
        ExtractDispatch.refreshArmed();
        if (preset.managesValues()) {
            commitRenderDistance(preset.renderDistanceChunks());
        }
        syncRows();
    }

    /**
     * Write the vanilla render distance, clamped into the option's LIVE
     * range.
     *
     * <p>The clamp is not decoration. {@code OptionInstance.set} runs the
     * value through {@code ValueSet.validateValue} and, on a rejection,
     * quietly substitutes the old one (bytecode: {@code validateValue(v)}
     * then {@code Optional.orElseGet(...)}), so an out-of-range preset
     * would look applied and do nothing. Every preset asks for 32 or less
     * and vanilla's own maximum is 32, so this can only ever bite if a
     * future preset grows or another mod narrows the range.</p>
     *
     * <p>{@code options.save()} is what re-broadcasts ClientInformation to
     * the server, so it is deliberately paired with every write here.</p>
     */
    private void commitRenderDistance(int chunks) {
        if (this.minecraft == null || this.minecraft.options == null) {
            return;
        }
        Options options = this.minecraft.options;
        int target = Math.max(renderDistanceMin(),
                Math.min(renderDistanceMax(), chunks));
        if (options.renderDistance().get() != target) {
            options.renderDistance().set(target);
        }
        options.save();
    }

    /**
     * The option's live minimum. Read per call rather than cached: the
     * extended-range plumbing swaps the whole ValueSet at world standup
     * and again when the cap row changes, so a cached bound would leave
     * this slider unable to reach numbers the player just unlocked.
     */
    private int renderDistanceMin() {
        return renderDistanceBound(true);
    }

    private int renderDistanceMax() {
        return renderDistanceBound(false);
    }

    private int renderDistanceBound(boolean wantMin) {
        if (this.minecraft != null && this.minecraft.options != null
                && this.minecraft.options.renderDistance().values()
                        instanceof OptionInstance.IntRangeBase range) {
            return wantMin ? range.minInclusive() : range.maxInclusive();
        }
        return wantMin ? FALLBACK_MIN_RENDER_DISTANCE : FALLBACK_MAX_RENDER_DISTANCE;
    }

    private int currentRenderDistance() {
        if (this.minecraft != null && this.minecraft.options != null) {
            return this.minecraft.options.renderDistance().get();
        }
        return FALLBACK_MAX_RENDER_DISTANCE;
    }

    /**
     * A preset owns the render distance, and the render distance can move
     * without this page: Video Settings, the extended-range clamp-back and
     * the arena-pressure step-down all write it. So on every open, a
     * selection whose render distance no longer matches reality becomes
     * Custom.
     *
     * <p>Deliberately the only reconciliation done here. The far-field
     * fields cannot drift behind the page's back (nothing else writes
     * them), so checking them would be work that can only ever answer
     * yes. And on a fresh install the selection is already Custom, which
     * manages no render distance, so this never writes a config file the
     * zero-cost-off posture promised would not exist.</p>
     */
    private void reconcilePresetWithRenderDistance(FarFieldConfig config) {
        Preset selected = FarFieldConfig.selectedPreset();
        if (!selected.managesValues()) {
            return;
        }
        int want = Math.max(renderDistanceMin(),
                Math.min(renderDistanceMax(), selected.renderDistanceChunks()));
        if (want != currentRenderDistance() && config.markCustom()) {
            config.save();
        }
    }

    /**
     * Push the config back into the widgets. Called after a preset and
     * once per tick, because the layer pages write the same fields this
     * page shows and returning from one must not leave a stale number on
     * a slider.
     *
     * <p>Every branch compares before it writes, so a row that is already
     * right is untouched: {@code CycleButton.setValue} does not fire the
     * change handler (bytecode: it moves the index and calls the private
     * {@code updateValue}, which sets the message and the field and stops
     * there), but writing a slider's fraction while the player drags it
     * would fight the mouse.</p>
     */
    private void syncRows() {
        FarFieldConfig config = FarFieldConfig.get();
        if (this.masterButton != null
                && !Boolean.valueOf(config.enabled).equals(this.masterButton.getValue())) {
            this.masterButton.setValue(config.enabled);
        }
        if (this.bandButton != null
                && !Boolean.valueOf(config.surfaceBand).equals(this.bandButton.getValue())) {
            this.bandButton.setValue(config.surfaceBand);
        }
        if (this.presetSlider != null && this.pendingPreset == null) {
            this.presetSlider.showPreset(FarFieldConfig.selectedPreset());
        }
        if (this.renderDistanceSlider != null && this.pendingRenderDistance < 0) {
            this.renderDistanceSlider.showChunks(currentRenderDistance());
        }
        if (this.radiusSlider != null) {
            this.radiusSlider.showChunks(FarFieldConfig.layerRadiusChunks(Layer.L1));
        }
    }

    /** Commit whatever the two held sliders are holding, right now. */
    private void flushPending() {
        this.applyDebounceTicks = 0;
        Preset preset = this.pendingPreset;
        int chunks = this.pendingRenderDistance;
        this.pendingPreset = null;
        this.pendingRenderDistance = -1;
        if (preset != null) {
            applyPreset(preset);
        }
        if (chunks >= 0) {
            commitRenderDistance(chunks);
            markCustomAndSave(FarFieldConfig.get());
            syncRows();
        }
    }

    /**
     * Live state, all of it cheap: commit any held slider, pace the size
     * walk, publish whatever the walker last answered, and let the delete
     * button's result line fade back to its normal label so the button can
     * be used again.
     */
    @Override
    public void tick() {
        super.tick();

        if (this.applyDebounceTicks > 0 && --this.applyDebounceTicks == 0) {
            flushPending();
        }

        if (this.sizeDirty || this.sizeCooldownTicks <= 0) {
            this.sizeDirty = false;
            this.sizeCooldownTicks = SIZE_REFRESH_TICKS;
            requestSize();
        } else {
            this.sizeCooldownTicks--;
        }
        if (this.sizeLine != null) {
            this.sizeLine.setMessage(sizeText());
        }
        if (this.statusLine != null) {
            this.statusLine.setMessage(statusText());
        }

        syncRows();

        if (this.clearState == CLEAR_DONE || this.clearState == CLEAR_FAILED) {
            if (this.clearResultTicks++ >= CLEAR_RESULT_TICKS) {
                this.clearResultTicks = 0;
                this.clearState = CLEAR_IDLE;
            }
        } else {
            this.clearResultTicks = 0;
        }
        if (this.clearButton != null) {
            this.clearButton.setMessage(clearText());
        }
    }

    /**
     * Ask for a fresh total unless one is already on its way.
     *
     * <p>The flag is raised BEFORE the call and lowered again only if
     * the facade refused to start a walk. Raising it afterwards would be
     * a real bug and a quiet one: an empty cache folder answers in
     * microseconds, so the walker thread routinely finishes and clears
     * the flag before {@code requestCacheSizeBytes} has even returned,
     * and a later {@code sizeInFlight = true} would then latch the flag
     * on forever. The first readout would still be right, so the screen
     * would look correct and simply never refresh again.</p>
     */
    private void requestSize() {
        if (this.sizeInFlight) {
            return;
        }
        this.sizeInFlight = true;
        boolean started = FarField.requestCacheSizeBytes(bytes -> {
            this.cacheBytes = bytes;
            this.sizeInFlight = false;
        });
        if (!started) {
            this.sizeInFlight = false; // another walk owns the slot; retry later
        }
    }

    private Component sizeText() {
        long bytes = this.cacheBytes;
        Component value;
        if (bytes == SIZE_PENDING) {
            value = Component.translatable("meshelium.options.farfield.size.checking");
        } else if (bytes == SIZE_FAILED) {
            value = Component.translatable("meshelium.options.farfield.size.unknown");
        } else {
            value = Component.literal(humanBytes(bytes));
        }
        return Component.translatable("meshelium.options.farfield.size", value);
    }

    /**
     * The live "what is it doing" row: sections currently drawn from the
     * cache, and chunks recorded this session.
     *
     * <p>Both are plain {@code LongAdder.sum()} reads. Naming the far
     * walker here is safe for the master-off zero-cost rule because
     * reaching this screen at all means the player opened the far-field
     * page, and the row only resolves the counters when the feature is
     * enabled; a session that never turns it on never runs this code.</p>
     */
    private Component statusText() {
        if (!FarFieldConfig.enabled()) {
            return Component.translatable("meshelium.options.farfield.status.off");
        }
        long drawn = FarFieldResidency.farSectionsResident.sum();
        long saved = ExtractDispatch.farExtracts.sum();
        return Component.translatable("meshelium.options.farfield.status",
                Component.literal(Long.toString(Math.max(0L, drawn))),
                Component.literal(Long.toString(Math.max(0L, saved))));
    }

    private Component clearText() {
        return switch (this.clearState) {
            case CLEAR_WORKING ->
                    Component.translatable("meshelium.options.farfield.clear.working");
            case CLEAR_DONE ->
                    Component.translatable("meshelium.options.farfield.clear.done");
            case CLEAR_FAILED ->
                    Component.translatable("meshelium.options.farfield.clear.failed")
                            .withStyle(ChatFormatting.RED);
            default -> this.clearArmed
                    ? Component.translatable("meshelium.options.farfield.clear.confirm")
                            .withStyle(ChatFormatting.RED)
                    : Component.translatable("meshelium.options.farfield.clear");
        };
    }

    /**
     * Bytes as something a person reads. Root locale on purpose: the
     * number is glued to an ASCII unit here, and a locale-specific
     * decimal comma next to a period-free unit reads as a thousands
     * separator to half the world.
     */
    private static String humanBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f GB",
                bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * The row's description plus its apply semantics, with the honesty
     * rule the other two screens share: on a gate-locked screen the
     * semantics line is REPLACED by why the row is locked, because that
     * is a locked row's only truthful annotation.
     */
    private Tooltip tip(String descriptionKey, String appliesKey) {
        return Tooltip.create(withSemantics(Component.translatable(descriptionKey),
                this.gateLocked ? "meshelium.options.applies.vulkan" : appliesKey));
    }

    private static Component withSemantics(MutableComponent description, String semanticsKey) {
        return description.append(Component.literal("\n\n"))
                .append(Component.translatable(semanticsKey).withStyle(ChatFormatting.GRAY));
    }

    // ------------------------------------------------------------------
    // The three sliders
    // ------------------------------------------------------------------

    /**
     * The preset ladder as a slider, stop 0 being Custom.
     *
     * <p>Six stops, ordered by how much real chunk work they leave on the
     * machine: Custom, No LOD, Highest Detail, Balanced, Performant, Ultra
     * Performant. Sliding to a preset HOLDS it and commits on release (or
     * after {@value #APPLY_DEBOUNCE_TICKS} quiet ticks), so dragging from
     * one end to the other applies one preset instead of four, and the
     * render distance is written once instead of being walked down through
     * every intermediate value.</p>
     *
     * <p>Custom is a real stop rather than a hidden state so the handle is
     * never parked somewhere that contradicts the label. Selecting it
     * writes no values at all: it only records that the config is the
     * player's own mix, which is exactly what it already was.</p>
     */
    private final class PresetSlider extends AbstractSliderButton {

        private Preset displayed;

        PresetSlider(boolean active) {
            super(0, 0, WIDGET_WIDTH, 20, Component.empty(),
                    presetFraction(FarFieldConfig.selectedPreset()));
            this.active = active;
            this.displayed = FarFieldConfig.selectedPreset();
            updateMessage();
        }

        /** Move the handle without applying anything (the tick sync). */
        void showPreset(Preset preset) {
            if (preset == this.displayed) {
                return;
            }
            this.displayed = preset;
            this.value = presetFraction(preset);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("meshelium.options.farfield.preset.label",
                    Component.translatable(
                            "meshelium.options.farfield.preset." + this.displayed.id)));
        }

        @Override
        protected void applyValue() {
            int index = (int) Math.round(this.value * (Preset.LADDER.length - 1));
            index = Math.max(0, Math.min(Preset.LADDER.length - 1, index));
            Preset picked = Preset.LADDER[index];
            if (picked != this.displayed) {
                this.displayed = picked;
                MesheliumFarFieldScreen.this.pendingPreset = picked;
                // A preset owns the render distance, so a half-finished
                // hand edit of that slider is dropped rather than being
                // replayed over the top of what the preset just wrote.
                MesheliumFarFieldScreen.this.pendingRenderDistance = -1;
                MesheliumFarFieldScreen.this.applyDebounceTicks = APPLY_DEBOUNCE_TICKS;
            }
            updateMessage();
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            if (MesheliumFarFieldScreen.this.pendingPreset != null) {
                MesheliumFarFieldScreen.this.flushPending();
            }
        }
    }

    private static double presetFraction(Preset preset) {
        for (int i = 0; i < Preset.LADDER.length; i++) {
            if (Preset.LADDER[i] == preset) {
                return i / (double) (Preset.LADDER.length - 1);
            }
        }
        return 0.0;
    }

    /**
     * Minecraft's own render distance, on this page because the owner
     * asked for it here.
     *
     * <p>Held and committed on release for the reason in the class
     * javadoc: vanilla's own slider does not apply while dragging either,
     * and walking a client's chunk storage down through every value
     * between 32 and 8 is a stutter nobody asked for.</p>
     */
    private final class RenderDistanceSlider extends AbstractSliderButton {

        private int displayed;

        RenderDistanceSlider() {
            super(0, 0, WIDGET_WIDTH, 20, Component.empty(), 0.0);
            this.displayed = currentRenderDistance();
            this.value = renderDistanceFraction(this.displayed);
            updateMessage();
        }

        /** Move the handle without applying anything (the tick sync). */
        void showChunks(int chunks) {
            if (chunks == this.displayed) {
                return;
            }
            this.displayed = chunks;
            this.value = renderDistanceFraction(chunks);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(
                    "meshelium.options.farfield.render_distance.label",
                    Component.literal(Integer.toString(this.displayed))));
        }

        @Override
        protected void applyValue() {
            int min = renderDistanceMin();
            int max = renderDistanceMax();
            int chunks = min + (int) Math.round(this.value * (max - min));
            if (chunks != this.displayed) {
                this.displayed = chunks;
                MesheliumFarFieldScreen.this.pendingRenderDistance = chunks;
                MesheliumFarFieldScreen.this.applyDebounceTicks = APPLY_DEBOUNCE_TICKS;
            }
            updateMessage();
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            if (MesheliumFarFieldScreen.this.pendingRenderDistance >= 0) {
                MesheliumFarFieldScreen.this.flushPending();
            }
        }
    }

    private double renderDistanceFraction(int chunks) {
        int min = renderDistanceMin();
        int max = renderDistanceMax();
        if (max <= min) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (chunks - min) / (double) (max - min)));
    }

    /**
     * The far radius, in chunks, whose 0 stop reads Off. Continuous over
     * {@value FarFieldConfig#MIN_L1_RADIUS_CHUNKS}..{@value
     * FarFieldConfig#MAX_L1_RADIUS_CHUNKS} like the Advanced screen's
     * cull sliders, because this is a distance a player tunes by feel.
     *
     * <p>Applies instantly in both directions, unlike the two sliders
     * above it: the walker moves a budgeted handful of sections per pump
     * and re-reads this number every time, so dragging it is a smooth
     * fill or drain rather than a rebuild.</p>
     *
     * <p>Writes layer 1's radius AND layer 1's on/off switch together.
     * Zero here and "Layer: Off" on the layer 1 page are the same state,
     * so letting the two rows hold different opinions would leave a page
     * saying the layer is on while the front page says the distance is
     * nothing.</p>
     *
     * <p>Reads the RESOLVER for its starting position and writes the
     * FIELD, which is only consistent while no {@code -D} override is
     * present - hence the {@code active} flag the constructor takes: a
     * slider that moves and changes nothing is the dev-override banner's
     * whole reason to exist.</p>
     */
    private final class FarRadiusSlider extends AbstractSliderButton {

        private int displayed;

        FarRadiusSlider(boolean active) {
            super(0, 0, WIDGET_WIDTH, 20, Component.empty(),
                    radiusFraction(FarFieldConfig.layerRadiusChunks(Layer.L1)));
            this.active = active;
            this.displayed = FarFieldConfig.layerRadiusChunks(Layer.L1);
            updateMessage();
        }

        /** Move the handle without applying anything (the tick sync). */
        void showChunks(int chunks) {
            if (chunks == this.displayed) {
                return;
            }
            this.displayed = chunks;
            this.value = radiusFraction(chunks);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            Component shown = this.displayed <= FarFieldConfig.MIN_L1_RADIUS_CHUNKS
                    ? Component.translatable("meshelium.options.cull.off")
                    : Component.translatable("meshelium.options.cull.chunks",
                            Component.literal(Integer.toString(this.displayed)));
            setMessage(Component.translatable(
                    "meshelium.options.farfield.radius.label", shown));
        }

        @Override
        protected void applyValue() {
            int span = FarFieldConfig.MAX_L1_RADIUS_CHUNKS
                    - FarFieldConfig.MIN_L1_RADIUS_CHUNKS;
            int chunks = FarFieldConfig.MIN_L1_RADIUS_CHUNKS
                    + (int) Math.round(this.value * span);
            if (chunks != this.displayed) {
                this.displayed = chunks;
                FarFieldConfig config = FarFieldConfig.get();
                config.layer(Layer.L1).radiusChunks = chunks;
                config.layer(Layer.L1).enabled =
                        chunks > FarFieldConfig.MIN_L1_RADIUS_CHUNKS;
                markCustomAndSave(config);
            }
            updateMessage();
        }
    }

    /** Slider fraction (0..1) for a far radius in chunks. */
    private static double radiusFraction(int chunks) {
        int span = FarFieldConfig.MAX_L1_RADIUS_CHUNKS
                - FarFieldConfig.MIN_L1_RADIUS_CHUNKS;
        double f = (chunks - FarFieldConfig.MIN_L1_RADIUS_CHUNKS) / (double) span;
        return Math.max(0.0, Math.min(1.0, f));
    }

    /** RestrictionsScreen's sequence, the same one the Advanced screen uses. */
    @Override
    protected void repositionElements() {
        this.scrollArea.arrangeElements();
        this.scrollArea.setMaxHeight(this.layout.getContentHeight());
        this.layout.arrangeElements();
    }

    @Override
    public void onClose() {
        // Anything a slider is still holding is committed on the way out:
        // closing the page with the mouse still down over a slider is a
        // real thing players do, and a preset that silently did not apply
        // is worse than one that applied late.
        flushPending();
        // Straight back to the LIVE parent instance, never a fresh one:
        // the Advanced screen keeps its widgets and its scroll position.
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}
