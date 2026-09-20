/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.MesheliumPlatform;
import com.deds.meshelium.gui.MesheliumOptionsScreen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The "Meshelium..." row at the bottom of vanilla's Options screen, shown
 * only while Sodium is installed. Owner directive (2026-09-13, verbatim):
 * "probably should add some other place for the meshelium button when
 * sodium is on. maybe just at the bottom of the options menu??".
 *
 * <p>With Sodium installed the "Meshelium Settings..." row in vanilla's
 * Video Settings ({@link VideoSettingsScreenMixin}) is unreachable from
 * the menu: Sodium cancels the supplier lambda behind vanilla's "Video
 * Settings..." button at HEAD and returns its own screen, which extends
 * {@code Screen} directly and has no {@code addOptions} for that row to
 * attach to. The mixin still applied, nothing was logged, and
 * {@code /meshelium} silently became the only route in. This class is the
 * other place.
 *
 * <h2>The seam (all javap-cited, 26.2 jar and the NeoForge-recompiled
 * 26.2.0.75 jar)</h2>
 * {@code OptionsScreen} is not an {@code OptionsSubScreen}: it builds its
 * whole UI inside one {@code init()} and hands a {@code GridLayout} of ten
 * "Noun..." buttons to {@code HeaderAndFooterLayout.addToContents} exactly
 * once (ip 382 on both jars, followed by a {@code pop} at 385, no
 * checkcast back to GridLayout). A {@code @ModifyArg} on that call wraps
 * the grid in a vertical {@code LinearLayout} with this row as the last
 * child, so the row sits below whatever the grid holds without naming a
 * row index (a future eleventh vanilla button would wrap the RowHelper
 * into row 5, column 0, and GridLayout does not detect overlap), and it is
 * registered and repositioned by vanilla's own {@code visitWidgets} and
 * {@code arrangeElements} calls that follow. Sodium hooks one supplier
 * lambda ({@code lambda$init$3}), not {@code init()}, so the two never
 * meet. {@code allow = 1} turns the single-call-site assumption into an
 * enforced invariant: a vanilla or loader patch that adds a second
 * {@code addToContents} call fails the apply loudly on both loaders
 * instead of wrapping the other element in a second column and adding a
 * second "Meshelium..." row ({@code defaultRequire: 1} alone only demands
 * at least one match).
 *
 * <p><b>Layout arithmetic, from the javap constants:</b> the grid's cells
 * are {@code paddingHorizontal(4)} around 150-wide buttons, so a row of
 * two is 316 wide and its visible span 308. Our button is 308 wide in a
 * cell padded 4 each side, 316 again, so the wrapper column has no slack
 * and the button's edges land exactly on the left button's left edge and
 * the right button's right edge: full width like the other rows, with no
 * alignment choice to get wrong. Content height 120 -> 144 (the row and
 * its paddingBottom(4), the same trailing gap every grid row carries). At
 * the 240 px logical floor ({@code Window.calculateScale} keeps logical
 * size at least 320x240) the contents move from y 87 to y 65 and still
 * clear the header block (which ends at y 49) by 16 px; the harness's 854x480 window at
 * GUI scale 2 is exactly that floor, so the screenshots photograph the
 * worst case.</p>
 *
 * <p><b>Why the content area and not the footer:</b> the footer is a
 * {@code FrameLayout} pinned to 33 px at {@code height - 33}, and a
 * FrameLayout stacks every child on one point, so a second footer widget
 * sits on top of Done; raising the footer needs a shadow of the private
 * layout field and costs the content region the same height a grid row
 * costs. The row here reads as one more row of the same menu, which is
 * what was asked for.</p>
 *
 * <p><b>Why only when Sodium is installed:</b> the owner's directive is
 * scoped to "when sodium is on", the vanilla layout was playtested as it
 * stands (wave 13/15), and the predicate is the loader question the gate's
 * decision and the stand-down suite already ask
 * ({@link MesheliumPlatform#isModLoaded}), known before the gate decides
 * (so an Options screen opened in the first frames still shows the row)
 * and answering false with a WARN rather than throwing (so a loader that
 * refuses the question hides one button instead of crashing the Options
 * screen). Deliberately not tied to whether Sodium's own hook is active: a
 * player who disables it gets vanilla Video Settings back AND this row,
 * two working routes, and asking Sodium's mixin config would couple this
 * class to Sodium internals to hide a redundant button. Flipping to
 * "always" later is deleting one {@code if} and inverting the standalone
 * suite leg. Without Sodium the layout tree handed to
 * {@code addToContents} is the untouched GridLayout, so the rendered
 * layout is identical to vanilla; {@code init()} itself gains one handler
 * call, which is what a reader diffing the transformed class will see.</p>
 *
 * <p><b>Navigation and both backends:</b> the button opens
 * {@link MesheliumOptionsScreen} with THIS screen as parent; its
 * {@code onClose} hands a non-VideoSettings parent back unchanged, and
 * 26.2's {@code Screen.init(int,int)} only repositions an initialized
 * screen ({@code initialized} read at ip 11, written only at ip 34; same
 * guard shape on NeoForge), so Done returns to the same Options instance
 * with no rebuild and no duplicate row. Added on OpenGL too: the Meshelium
 * screen shows its rows locked with the reason, which is the whole point
 * of the silent-refusal rule. Touches only vanilla GUI types and the
 * config-only options screen; no vk package, wave-1 class-loading
 * discipline intact.</p>
 *
 * <p><b>Pinned by:</b> {@code MesheliumSodiumStandDownTest} (the row is
 * present, full width, above Done, and presses through to the Meshelium
 * screen and back to the same instance, on every Sodium form) and
 * {@code MesheliumBootSmokeTest} (the row is absent without Sodium). Either
 * leg alone would pass with a mixin that never applies or one that always
 * applies; together they pin the predicate.</p>
 */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {

    /** Vanilla's own cell padding: {@code OptionsScreen.init} calls {@code paddingHorizontal(4)}. */
    private static final int GRID_CELL_PADDING = 4;

    /**
     * Two grid cells of vanilla's own arithmetic, 150 + 4 + 4 per cell, read
     * from the left button's left edge to the right button's right edge: 308.
     */
    private static final int ROW_WIDTH = 2 * Button.DEFAULT_WIDTH + 2 * GRID_CELL_PADDING;

    protected OptionsScreenMixin(Component title) {
        super(title);
    }

    @ModifyArg(method = "init()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;"
                            + "addToContents(Lnet/minecraft/client/gui/layouts/LayoutElement;)"
                            + "Lnet/minecraft/client/gui/layouts/LayoutElement;"),
            allow = 1)
    private LayoutElement meshelium$appendMesheliumRow(LayoutElement grid) {
        if (!MesheliumPlatform.isModLoaded(MesheliumGate.SODIUM_MOD_ID)) {
            return grid; // no Sodium: vanilla's own grid, untouched, so the layout is vanilla's
        }
        // Spacing 0: the grid's own paddingBottom(4) on its last row is the
        // gap above our row; our cell's paddingBottom(4) is the same gap
        // the sibling rows carry beneath them.
        LinearLayout column = LinearLayout.vertical();
        column.addChild(grid);
        column.addChild(Button.builder(Component.translatable("meshelium.options.menu"),
                        button -> this.minecraft.gui.setScreen(new MesheliumOptionsScreen(this)))
                .width(ROW_WIDTH)
                .tooltip(Tooltip.create(Component.translatable("meshelium.options.tooltip.menu")))
                .build(), settings -> settings.paddingHorizontal(GRID_CELL_PADDING)
                        .paddingBottom(GRID_CELL_PADDING));
        return column;
    }
}
