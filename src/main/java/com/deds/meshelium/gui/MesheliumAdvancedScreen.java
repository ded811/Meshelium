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
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

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
 */
@Environment(EnvType.CLIENT)
public class MesheliumAdvancedScreen extends Screen {

    private static final int WIDGET_WIDTH = 200;
    private static final int BANNER_WIDTH = 340;

    private final Screen parent;
    private final LinearLayout layout = LinearLayout.vertical().spacing(2);

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

        this.layout.addChild(new StringWidget(this.getTitle(), this.font));

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
            this.layout.addChild(banner, s -> s.paddingTop(2).paddingBottom(2));
        }

        if (this.gateLocked) {
            MultiLineTextWidget locked = new MultiLineTextWidget(
                    Component.translatable("meshelium.options.advanced.locked")
                            .withStyle(ChatFormatting.YELLOW), this.font);
            locked.setMaxWidth(BANNER_WIDTH);
            locked.setCentered(true);
            this.layout.addChild(locked, s -> s.paddingTop(2).paddingBottom(2));
        }

        // First row, because it is the only one here that changes frame rate.
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
        this.layout.addChild(greedy, s -> s.paddingTop(4));

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
        this.layout.addChild(trim);

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
        this.layout.addChild(suppress);

        CycleButton<Boolean> stats = CycleButton.onOffBuilder(config.debugStats)
                .create(Component.translatable("meshelium.options.debug_stats"), (b, value) -> {
                    config.debugStats = value;
                    config.save();
                });
        stats.setWidth(WIDGET_WIDTH);
        stats.active = !this.gateLocked && !statsOverridden;
        stats.setTooltip(tip("meshelium.options.tooltip.debug_stats",
                "meshelium.options.applies.now"));
        this.layout.addChild(stats);

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
        this.layout.addChild(popup);

        this.layout.addChild(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
                .width(WIDGET_WIDTH).build(), s -> s.paddingTop(6));

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

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        FrameLayout.centerInRectangle(this.layout, this.getRectangle());
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
