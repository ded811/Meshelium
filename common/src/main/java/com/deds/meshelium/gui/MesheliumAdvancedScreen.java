/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.gui;

import com.deds.meshelium.farfield.FarFieldConfig;
import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumGate;

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
import java.util.function.IntSupplier;

/**
 * The rows almost nobody should need to touch.
 *
 * <h2>Why a second screen</h2>
 * <p>The main screen had grown to roughly a dozen rows, and the ones a
 * player actually reaches for (is it on, how far can I see) were sharing
 * space with diagnostics and with a memory setting whose only reason to
 * change is a mod conflict. A long list of switches invites fiddling with
 * switches, and every one of these defaults to the right answer.</p>
 *
 * <p>Deliberately NOT a collapsible section on the main screen: the class
 * javadoc over there records a 240-unit height budget that the flat list was
 * already brushing against, and a section that expands past the bottom of
 * the screen is worse than a second page.</p>
 *
 * <h2>What this screen is not</h2>
 * <p>It is not a copy of the main screen. In particular it must never carry
 * that screen's {@code onClose} back-out fix, which rebuilds a vanilla
 * VideoSettingsScreen when the render-distance cap changed. Nothing here
 * touches the cap, and rebuilding the parent from a child would throw away
 * the live main screen the player is about to return to. This closes
 * straight back to whoever opened it.</p>
 *
 * <p>It also has no {@code tick()}: nothing on it is live state. The two
 * status lines that do need refreshing stay on the main screen.</p>
 *
 * <h2>2026-08-18: header, footer, and a scrolling middle</h2>
 * <p>The rows live in a vanilla {@link ScrollableLayout} between a title
 * header and a Done footer ({@link HeaderAndFooterLayout}), the exact
 * structure vanilla's RestrictionsScreen and ExperimentsScreen use
 * (bytecode-cited; both wrap a plain layout in
 * {@code new ScrollableLayout(minecraft, rows, layout.getContentHeight())}
 * and re-clamp the max height in {@code repositionElements()}). The flat
 * centered stack this replaces could not survive a large GUI scale: with
 * ~11 rows, {@code FrameLayout.centerInRectangle} centres an over-tall
 * stack, so the overflow went half above the window and half below, both
 * unreachable. The owner approved scrolling for exactly this case ("if
 * you end up making a ton more settings in advanced, just scrolling down
 * is fine"), and more rows are coming.</p>
 *
 * <p>What the idiom buys, all verified against the 26.2 jar: the Done
 * button sits in the footer, so it can never scroll away; the scroll
 * container is an {@code AbstractContainerWidget}, so the mouse wheel and
 * the scrollbar both work and tab/arrow navigation descends into the rows
 * ({@code Container.setFocused} scrolls the keyboard-focused row into
 * view); and when the rows FIT, the container is exactly as tall as they
 * are and the screen looks like the centered stack it always was, which
 * keeps it visually of a piece with the main Meshelium screen. Row order
 * is unchanged; every widget keeps its width, tooltip and enabled-state
 * logic from before the conversion.</p>
 */
public class MesheliumAdvancedScreen extends Screen {

    private static final int WIDGET_WIDTH = 200;
    private static final int BANNER_WIDTH = 340;

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    /** The scrolling middle band; built in {@link #init()} (it needs minecraft). */
    private ScrollableLayout scrollArea;

    /**
     * True when the backend gate means Meshelium's OWN chunk path is not
     * running. Read once at construction, exactly like the main screen: the
     * gate cannot change without a restart, so re-reading it per frame
     * would only invite a row that disagrees with its own tooltip.
     *
     * <p>Under Sodium this is true and two rows are live anyway: the
     * Sodium drawer reads the sub-pixel cull per frame and the GPU timers
     * read Debug Stat Logging, so those key on
     * {@link MesheliumGate#sodiumAdapterArmed()} as well (owner report
     * 2026-09-08 (beta.8)).
     */
    private final boolean gateLocked;

    /**
     * Sodium is installed, so Meshelium's own chunk builder is standing
     * aside and the rows that only it reads are not built here at all.
     *
     * <p>Read ONCE, beside {@link #gateLocked} and for the same reason:
     * the gate cannot change without a restart, so re-reading it per frame
     * could only ever produce a row that disagrees with its own tooltip.
     * A screen built while the gate was still UNKNOWN is reachable only
     * from the main screen, which heals itself first.
     */
    private final boolean sodium;

    public MesheliumAdvancedScreen(Screen parent) {
        super(Component.translatable("meshelium.options.advanced.title"));
        this.parent = parent;
        this.gateLocked = MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS;
        this.sodium = MesheliumGate.state() == MesheliumGate.State.SODIUM_PRESENT;
    }

    @Override
    protected void init() {
        MesheliumConfig config = MesheliumConfig.get();

        this.layout.addTitleHeader(this.getTitle(), this.font);

        // The rows, in the same order as ever, in their own vertical stack.
        // Cells centre horizontally like the main screen's, so the
        // 200-wide rows sit centred under a 340-wide banner when one shows.
        LinearLayout rows = LinearLayout.vertical().spacing(2);
        rows.defaultCellSetting().alignHorizontallyCenter();

        // The dev-override census is per screen, following the precedent on
        // the main screen: a -D flag must not raise a banner over rows the
        // screen it appears on cannot show. Only properties that lock a row
        // HERE count.
        boolean statsOverridden = System.getProperty("meshelium.debugStats") != null;
        // Under Sodium the greedy-meshing row is not built, so its property
        // must not raise a banner over a row that is not on screen. Same
        // rule as the main screen's census, and the reason retention is
        // absent from both.
        boolean greedyOverridden = !this.sodium
                && System.getProperty("meshelium.greedyMeshing") != null;
        // The two troubleshooting rows lock under their flags, and exist
        // only under Sodium.
        boolean sodiumRowsOverridden = this.sodium
                && (System.getProperty("meshelium.sodium.occlusion") != null
                        || System.getProperty("meshelium.sodium.remirrorOnRefusal") != null);
        if (statsOverridden || greedyOverridden || sodiumRowsOverridden) {
            MultiLineTextWidget banner = new MultiLineTextWidget(
                    Component.translatable("meshelium.options.dev_override"), this.font);
            banner.setMaxWidth(BANNER_WIDTH);
            banner.setCentered(true);
            rows.addChild(banner, s -> s.paddingTop(2).paddingBottom(2));
        }

        if (this.gateLocked) {
            // THREE sentences, because there are three situations and two
            // of them used to share a wrong one.
            //
            // The stock banner says Meshelium is not running, which with
            // the adapter drawing Sodium's chunks is false. But the Sodium
            // banner says only the settings that still apply are SHOWN, and
            // that is false in the other two Sodium shapes: subPixelLive
            // and statsLive are both (!gateLocked || sodiumAdapterArmed()),
            // so on Sodium-declined and Sodium-on-OpenGL two of the rows on
            // screen are grey. A banner asserting everything shown applies,
            // over two dead rows, is exactly the honesty fault the Sodium
            // twin was added to fix.
            //
            // So: standalone keeps the plain sentence; Sodium with the
            // adapter drawing gets the Sodium one; Sodium with it not
            // drawing gets its own, which says Meshelium is not drawing,
            // that the rows here are held too, and points at the main
            // screen for why.
            String lockedKey = !this.sodium ? "meshelium.options.advanced.locked"
                    : MesheliumGate.sodiumAdapterArmed()
                            ? "meshelium.options.advanced.locked.sodium"
                            : "meshelium.options.advanced.locked.sodium_off";
            MultiLineTextWidget locked = new MultiLineTextWidget(
                    Component.translatable(lockedKey)
                            .withStyle(ChatFormatting.YELLOW), this.font);
            locked.setMaxWidth(BANNER_WIDTH);
            locked.setCentered(true);
            rows.addChild(locked, s -> s.paddingTop(2).paddingBottom(2));
        }

        // First rows, because they are the ones here that change frame rate.
        // The tick watches this field for the edge and reloads the terrain, so
        // nothing needs doing beyond writing it: sections already compiled are
        // never recompiled on their own, and a setting that appears to do
        // nothing until the player walks away and back is a bug report.
        //
        // NOT BUILT UNDER SODIUM (2026-09-16), like the two leaf tiers, the
        // plant cull, idle trim and the duplicate-memory row below: every
        // one of them is read by Meshelium's OWN chunk builder, which does
        // not run while Sodium builds the chunks. The banner above names
        // them, which is what the greyed rows were providing.
        if (!this.sodium) {
        CycleButton<Boolean> greedy = CycleButton.onOffBuilder(config.greedyMeshing)
                .create(Component.translatable("meshelium.options.greedy_meshing"), (b, value) -> {
                    config.greedyMeshing = value;
                    config.save();
                });
        greedy.setWidth(WIDGET_WIDTH);
        greedy.active = !this.gateLocked && !greedyOverridden;
        greedy.setTooltip(tip("meshelium.options.tooltip.greedy_meshing",
                "meshelium.options.applies.rebuild"));
        rows.addChild(greedy, s -> s.paddingTop(4));

        // The two distance-gated culls. Both default Off because neither
        // win is measured yet (the owner's rule: an optimization nobody is
        // certain of ships as a slider to play with, not a default). Both
        // are LIVE: the scene UBO re-reads the config every frame, so
        // dragging either one changes the very next frame, no rebuild.
        CullDistanceSlider plantCull = new CullDistanceSlider(
                "meshelium.options.plant_cull.label",
                () -> MesheliumConfig.get().plantCullChunks,
                chunks -> {
                    config.plantCullChunks = chunks;
                    config.save();
                }, !this.gateLocked);
        plantCull.setTooltip(tip("meshelium.options.tooltip.plant_cull",
                "meshelium.options.applies.now"));
        rows.addChild(plantCull);
        }

        // Live under the Sodium adapter too: SodiumTerrainDrawer reads
        // subPixelCullChunks() into the scene UBO every frame, exactly as
        // the standalone drawer does. The plant cull is standalone-only,
        // and since 2026-09-16 is not built here at all under Sodium.
        boolean subPixelLive = !this.gateLocked || MesheliumGate.sodiumAdapterArmed();
        CullDistanceSlider subPixelCull = new CullDistanceSlider(
                "meshelium.options.detail_cull.label",
                () -> MesheliumConfig.get().subPixelCullChunks,
                chunks -> {
                    config.subPixelCullChunks = chunks;
                    config.save();
                }, subPixelLive);
        // Its own held sentence rather than the generic one: this row is
        // NOT held because Sodium builds the terrain (it is live exactly
        // when Sodium is installed and Meshelium is drawing), it is held
        // because the adapter is not drawing. applies.sodium would say the
        // opposite of the truth here.
        subPixelCull.setTooltip(tip(!subPixelLive, "meshelium.options.tooltip.detail_cull",
                "meshelium.options.applies.now",
                "meshelium.options.applies.sodium_gpu_held"));
        rows.addChild(subPixelCull);

        // The two leaf-detail tiers: BUILD-time filters, unlike the two
        // shader culls above, so their apply semantics are their own key —
        // new builds pick a change up immediately and the residency walker
        // rebuilds tiered sections when the camera or a slider moves them
        // inside a ring, budgeted, over a few seconds. applies.now would
        // be a lie here and applies.rebuild promises a reload that raising
        // a slider deliberately never does. Smart first, Solid directly
        // under it: reading order is escalation order (Smart keeps the
        // look, Solid trades it), and the pair shares one walker.
        if (!this.sodium) {
        CullDistanceSlider smartLeaves = new CullDistanceSlider(
                "meshelium.options.smart_leaves.label",
                () -> MesheliumConfig.get().smartLeavesChunks,
                chunks -> {
                    config.smartLeavesChunks = chunks;
                    config.save();
                }, !this.gateLocked);
        smartLeaves.setTooltip(tip("meshelium.options.tooltip.smart_leaves",
                "meshelium.options.applies.new_builds"));
        rows.addChild(smartLeaves);

        CullDistanceSlider solidLeaves = new CullDistanceSlider(
                "meshelium.options.solid_leaves.label",
                () -> MesheliumConfig.get().solidLeavesChunks,
                chunks -> {
                    config.solidLeavesChunks = chunks;
                    config.save();
                }, !this.gateLocked);
        solidLeaves.setTooltip(tip("meshelium.options.tooltip.solid_leaves",
                "meshelium.options.applies.new_builds"));
        rows.addChild(solidLeaves);
        }

        // 1.6: THE ONLY DOOR TO FAR TERRAIN, held shut by one constant.
        // This button is the single reachable entry to
        // MesheliumFarFieldScreen, and that page is the only thing that
        // builds MesheliumFarLayerScreen, so guarding it here makes both
        // pages unreachable at once. Guarded rather than deleted: the
        // screens, the preset table and every farfield lang key stay
        // compiled and translated, and 1.7 comes back by flipping
        // FarFieldConfig.FEATURE_ENABLED alone.
        if (FarFieldConfig.FEATURE_ENABLED) {
        // Far Terrain gets a PAGE, not a row: it owns a folder path, a
        // disk-size readout and a delete button, none of which fit a
        // 200-wide row list (MesheliumFarFieldScreen's class javadoc has
        // the argument). It sits here because it belongs with the four
        // distance rows above it and not with the memory and diagnostics
        // rows below. Never gate-locked, for the same reason the popup
        // row is not: the saved terrain is on disk whatever the backend
        // is doing, and the page's own rows lock themselves.
        Button farField = Button.builder(
                Component.translatable("meshelium.options.farfield"),
                b -> {
                    if (this.minecraft != null) {
                        this.minecraft.gui.setScreen(new MesheliumFarFieldScreen(this));
                    }
                }).width(WIDGET_WIDTH).build();
        farField.setTooltip(Tooltip.create(
                Component.translatable("meshelium.options.tooltip.farfield")));
        rows.addChild(farField, s -> s.paddingTop(4).paddingBottom(2));
        }

        // Wave-16: the quiet-time tail trim. Lives here rather than the
        // main screen for the same reason Duplicate Terrain Memory does -
        // the default is right for effectively everyone, and the one reason
        // to touch it is diagnosing a mod conflict around VRAM.
        if (!this.sodium) {
        CycleButton<Boolean> trim = CycleButton.onOffBuilder(config.arenaTrim)
                .create(Component.translatable("meshelium.options.arena_trim"), (b, value) -> {
                    config.arenaTrim = value;
                    config.save();
                });
        trim.setWidth(WIDGET_WIDTH);
        trim.active = !this.gateLocked;
        trim.setTooltip(tip("meshelium.options.tooltip.arena_trim",
                "meshelium.options.applies.now"));
        rows.addChild(trim);

        // Named states rather than On/Off, matching the master switch:
        // "Duplicate Terrain Memory: OFF" is unreadable, because OFF could
        // mean the memory or the freeing. Freed and Kept each name an
        // outcome, and the default reads as the good one.
        CycleButton<Boolean> suppress = CycleButton
                .booleanBuilder(Component.translatable("meshelium.options.suppress_vanilla.freed"),
                        Component.translatable("meshelium.options.suppress_vanilla.kept"),
                        config.suppressVanillaUploads)
                .create(Component.translatable("meshelium.options.suppress_vanilla"), (b, value) -> {
                    config.suppressVanillaUploads = value;
                    config.save();
                    if (this.minecraft != null && this.minecraft.level != null) {
                        // Mid-world only. From the main menu there is nothing
                        // armed and nothing to drop, and world standup arms
                        // the seam by itself.
                        com.deds.meshelium.terrain.host.VanillaUploadSeam.onSettingChanged();
                    }
                });
        suppress.setWidth(WIDGET_WIDTH);
        suppress.active = !this.gateLocked;
        suppress.setTooltip(tip("meshelium.options.tooltip.suppress_vanilla",
                "meshelium.options.applies.now"));
        rows.addChild(suppress);
        }

        // The troubleshooting rows. Sodium-path only: built only under
        // Sodium (without it they could never act, and a row that cannot
        // act is furniture), and held there whenever the adapter is not
        // drawing. Same order as Meshelium's page inside Sodium's video
        // settings, which has to show exactly the rows these screens build
        // under Sodium.
        if (this.sodium) {
        boolean adapterArmed = MesheliumGate.sodiumAdapterArmed();
        rows.addChild(sodiumToggle("meshelium.options.sodium_occlusion",
                "meshelium.options.tooltip.sodium_occlusion", "meshelium.sodium.occlusion",
                adapterArmed, config.sodiumOcclusion, value -> {
                    config.sodiumOcclusion = value;
                    config.save();
                    com.deds.meshelium.vk.SodiumTerrainDrawer.applyConfiguredGpuVisibility();
                }));
        rows.addChild(sodiumToggle("meshelium.options.sodium_record_repair",
                "meshelium.options.tooltip.sodium_record_repair",
                "meshelium.sodium.remirrorOnRefusal",
                adapterArmed, config.sodiumRemirrorOnRefusal, value -> {
                    config.sodiumRemirrorOnRefusal = value;
                    config.save();
                }));
        }

        CycleButton<Boolean> stats = CycleButton.onOffBuilder(config.debugStats)
                .create(Component.translatable("meshelium.options.debug_stats"), (b, value) -> {
                    config.debugStats = value;
                    config.save();
                });
        stats.setWidth(WIDGET_WIDTH);
        // Live under the Sodium adapter too: its GPU pass timers log
        // through MesheliumGpuTimers, which reads debugStatsEnabled().
        boolean statsLive = !this.gateLocked || MesheliumGate.sodiumAdapterArmed();
        stats.active = statsLive && !statsOverridden;
        // Same reason as the sub-pixel row above: held because the adapter
        // is not drawing, not because Sodium builds the terrain.
        stats.setTooltip(tip(!statsLive, "meshelium.options.tooltip.debug_stats",
                "meshelium.options.applies.now",
                "meshelium.options.applies.sodium_gpu_held"));
        rows.addChild(stats);

        // Active on EVERY backend, unlike the rows above: this one is about
        // the non-Vulkan case, so a gate-locked screen is exactly when a
        // player might want it back.
        CycleButton<Boolean> popup = CycleButton.onOffBuilder(config.showVulkanPrompt)
                .create(Component.translatable("meshelium.options.popup"), (b, value) -> {
                    config.showVulkanPrompt = value;
                    // Re-arm the once-per-install notices with it. Turning
                    // the prompt back on and still never seeing it because a
                    // "shown" flag latched years ago is not a re-arm.
                    config.noMeshShaderNoticeShown = !value;
                    config.vulkanFailedNoticeShown = !value;
                    config.save();
                });
        popup.setWidth(WIDGET_WIDTH);
        popup.setTooltip(Tooltip.create(withSemantics(
                Component.translatable("meshelium.options.tooltip.popup"),
                "meshelium.options.applies.restart")));
        rows.addChild(popup);

        // The scrolling middle: the rows wrapped in vanilla's own scroll
        // container (RestrictionsScreen's exact recipe, including seeding
        // the max height with the header/footer band so the very first
        // arrange cannot overshoot). When the rows fit, the container is
        // exactly their height and nothing scrolls; when they do not, the
        // wheel, the scrollbar and keyboard focus all do.
        this.scrollArea = new ScrollableLayout(this.minecraft, rows,
                this.layout.getContentHeight());
        this.layout.addToContents(this.scrollArea);

        // Done lives in the footer, OUTSIDE the scroll container, so no
        // future row count can ever push the way out of the screen out of
        // reach. That was the flat stack's failure mode at large GUI
        // scales, and it clipped rows off both edges unreachably.
        this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
                .width(WIDGET_WIDTH).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    /**
     * The row's description plus its apply semantics, with the main
     * screen's honesty rule: on a gate-locked screen the semantics line is
     * REPLACED by why the row is locked, because that is a locked row's
     * only truthful annotation.
     */
    /**
     * One Sodium-path troubleshooting row: on/off, live, held while the
     * adapter is not drawing, and held again while its launch flag is set.
     */
    private CycleButton<Boolean> sodiumToggle(String nameKey, String tipKey, String property,
            boolean adapterArmed, boolean initial, java.util.function.Consumer<Boolean> onChange) {
        CycleButton<Boolean> row = CycleButton.onOffBuilder(initial)
                .create(Component.translatable(nameKey), (b, value) -> onChange.accept(value));
        row.setWidth(WIDGET_WIDTH);
        row.active = adapterArmed && System.getProperty(property) == null;
        row.setTooltip(tip(!adapterArmed, tipKey, "meshelium.options.applies.now",
                "meshelium.options.applies.sodium_gpu_held"));
        return row;
    }

    private Tooltip tip(String descriptionKey, String appliesKey) {
        return tip(this.gateLocked, descriptionKey, appliesKey);
    }

    /**
     * A held row's semantics line names WHY it is held. Under Sodium that
     * is never "Needs the Vulkan renderer": the game may well be on Vulkan,
     * and the row is held because Sodium builds the terrain (owner report
     * 2026-09-08 (beta.8)).
     */
    private Tooltip tip(boolean locked, String descriptionKey, String appliesKey) {
        return tip(locked, descriptionKey, appliesKey, "meshelium.options.applies.sodium");
    }

    /**
     * As above, with the row's OWN held-under-Sodium sentence.
     *
     * <p>Two rows here are live exactly when the Sodium adapter is drawing
     * (the sub-pixel cull, read into the scene UBO every frame; debug stat
     * logging, read by the GPU pass timers). The default sentence, "held
     * while Sodium builds the terrain: this only changes how Meshelium
     * builds and stores its own", is the opposite of true for them - they
     * are the rows that follow Sodium's draw rather than Meshelium's own
     * builder. They pass applies.sodium_gpu_held instead.
     */
    private Tooltip tip(boolean locked, String descriptionKey, String appliesKey,
            String sodiumHeldKey) {
        String semantics = !locked ? appliesKey
                : MesheliumGate.state() == MesheliumGate.State.SODIUM_PRESENT ? sodiumHeldKey
                : "meshelium.options.applies.vulkan";
        return Tooltip.create(withSemantics(Component.translatable(descriptionKey), semantics));
    }

    private static Component withSemantics(MutableComponent description, String semanticsKey) {
        return description.append(Component.literal("\n\n"))
                .append(Component.translatable(semanticsKey).withStyle(ChatFormatting.GRAY));
    }

    /**
     * A chunk-distance slider whose 0 stop reads Off. Continuous over
     * 0..{@value MesheliumConfig#MAX_DETAIL_CULL_CHUNKS} like the main
     * screen's Auto-crossover slider, because the value is a distance a
     * player tunes by feel, not a count that must land on a lattice. One
     * class serves both cull rows; only the label key and the field they
     * write differ.
     */
    private final class CullDistanceSlider extends AbstractSliderButton {
        private final String labelKey;
        private final IntConsumer apply;
        private int displayed;

        CullDistanceSlider(String labelKey, IntSupplier current, IntConsumer apply,
                boolean active) {
            super(0, 0, WIDGET_WIDTH, 20, Component.empty(), cullFraction(current.getAsInt()));
            this.labelKey = labelKey;
            this.apply = apply;
            this.active = active;
            this.displayed = current.getAsInt();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            Component shown = this.displayed <= MesheliumConfig.MIN_DETAIL_CULL_CHUNKS
                    ? Component.translatable("meshelium.options.cull.off")
                    : Component.translatable("meshelium.options.cull.chunks",
                            Component.literal(Integer.toString(this.displayed)));
            setMessage(Component.translatable(this.labelKey, shown));
        }

        @Override
        protected void applyValue() {
            int span = MesheliumConfig.MAX_DETAIL_CULL_CHUNKS
                    - MesheliumConfig.MIN_DETAIL_CULL_CHUNKS;
            int chunks = MesheliumConfig.MIN_DETAIL_CULL_CHUNKS
                    + (int) Math.round(this.value * span);
            if (chunks != this.displayed) {
                this.displayed = chunks;
                this.apply.accept(chunks);
            }
            updateMessage();
        }
    }

    /** Slider fraction (0..1) for a cull distance in chunks. */
    private static double cullFraction(int chunks) {
        int span = MesheliumConfig.MAX_DETAIL_CULL_CHUNKS
                - MesheliumConfig.MIN_DETAIL_CULL_CHUNKS;
        double f = (chunks - MesheliumConfig.MIN_DETAIL_CULL_CHUNKS) / (double) span;
        return Math.max(0.0, Math.min(1.0, f));
    }

    /**
     * RestrictionsScreen's sequence, verbatim: arrange the rows so their
     * height is fresh, re-clamp the scroll container to the band between
     * header and footer (getContentHeight() reads the LIVE screen height,
     * so a resize mid-screen re-fits), then let the header-and-footer
     * layout place everything.
     */
    @Override
    protected void repositionElements() {
        this.scrollArea.arrangeElements();
        this.scrollArea.setMaxHeight(this.layout.getContentHeight());
        this.layout.arrangeElements();
    }

    @Override
    public void onClose() {
        // Straight back to the LIVE parent instance, never a fresh one.
        // Returning to a cached Screen only repositions it, so the main
        // screen keeps its widgets, its tick loop and its capAtOpen
        // snapshot, which is what keeps its own back-out fix working.
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}
