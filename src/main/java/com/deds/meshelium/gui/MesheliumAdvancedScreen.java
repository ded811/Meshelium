/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.gui;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumGate;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

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
@Environment(EnvType.CLIENT)
public class MesheliumAdvancedScreen extends Screen {

    private static final int WIDGET_WIDTH = 200;
    private static final int BANNER_WIDTH = 340;

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    /** The scrolling middle band; built in {@link #init()} (it needs minecraft). */
    private ScrollableLayout scrollArea;

    /**
     * True when the backend gate means none of this can take effect. Read
     * once at construction, exactly like the main screen: the gate cannot
     * change without a restart, so re-reading it per frame would only invite
     * a row that disagrees with its own tooltip.
     */
    private final boolean gateLocked;

    public MesheliumAdvancedScreen(Screen parent) {
        super(Component.translatable("meshelium.options.advanced.title"));
        this.parent = parent;
        this.gateLocked = MesheliumGate.state() != MesheliumGate.State.VULKAN_MESH_SHADERS;
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
        boolean greedyOverridden = System.getProperty("meshelium.greedyMeshing") != null;
        if (statsOverridden || greedyOverridden) {
            MultiLineTextWidget banner = new MultiLineTextWidget(
                    Component.translatable("meshelium.options.dev_override"), this.font);
            banner.setMaxWidth(BANNER_WIDTH);
            banner.setCentered(true);
            rows.addChild(banner, s -> s.paddingTop(2).paddingBottom(2));
        }

        if (this.gateLocked) {
            MultiLineTextWidget locked = new MultiLineTextWidget(
                    Component.translatable("meshelium.options.advanced.locked")
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

        CullDistanceSlider subPixelCull = new CullDistanceSlider(
                "meshelium.options.detail_cull.label",
                () -> MesheliumConfig.get().subPixelCullChunks,
                chunks -> {
                    config.subPixelCullChunks = chunks;
                    config.save();
                }, !this.gateLocked);
        subPixelCull.setTooltip(tip("meshelium.options.tooltip.detail_cull",
                "meshelium.options.applies.now"));
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

        // Wave-16: the quiet-time tail trim. Lives here rather than the
        // main screen for the same reason Duplicate Terrain Memory does -
        // the default is right for effectively everyone, and the one reason
        // to touch it is diagnosing a mod conflict around VRAM.
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

        CycleButton<Boolean> stats = CycleButton.onOffBuilder(config.debugStats)
                .create(Component.translatable("meshelium.options.debug_stats"), (b, value) -> {
                    config.debugStats = value;
                    config.save();
                });
        stats.setWidth(WIDGET_WIDTH);
        stats.active = !this.gateLocked && !statsOverridden;
        stats.setTooltip(tip("meshelium.options.tooltip.debug_stats",
                "meshelium.options.applies.now"));
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
    private Tooltip tip(String descriptionKey, String appliesKey) {
        return Tooltip.create(withSemantics(Component.translatable(descriptionKey),
                this.gateLocked ? "meshelium.options.applies.vulkan" : appliesKey));
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
