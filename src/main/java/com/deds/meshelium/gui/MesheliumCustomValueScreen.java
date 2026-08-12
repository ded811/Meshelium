/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * Wave-15: the "Custom..." mini-screen behind the two options-screen
 * sliders (owner directive 5/8: "a slider with a custom value option for
 * people who really want to crank it"). One {@link EditBox} (javap: ctor
 * {@code (Font, int, int, Component)}, {@code setResponder},
 * {@code setValue/getValue}, {@code setMaxLength} — all 26.2-verified),
 * a range line, a cost note, and Done/Cancel. Digits-only by validation,
 * not by filter (26.2's EditBox has no setFilter; the responder disables
 * Done while the text is not an in-range integer — invalid input can
 * never be accepted, only never applied). Done hands the parsed value to
 * the caller and returns to the parent; the caller owns writing the
 * config and refreshing its slider (this screen never touches config).
 */
@Environment(EnvType.CLIENT)
public final class MesheliumCustomValueScreen extends Screen {

    private final Screen parent;
    private final Component rangeLine;
    private final Component costNote;
    private final int min;
    private final int max;
    private final int initial;
    private final IntConsumer onAccept;

    private EditBox input;
    private Button doneButton;

    /**
     * @param rangeLine one short line naming the legal range
     * @param costNote  one short paragraph on what big values cost
     * @param onAccept  called with the validated value on Done, before
     *                  the screen returns to {@code parent}
     */
    public MesheliumCustomValueScreen(Screen parent, Component title, Component rangeLine,
            Component costNote, int initial, int min, int max, IntConsumer onAccept) {
        super(title);
        this.parent = parent;
        this.rangeLine = rangeLine;
        this.costNote = costNote;
        this.initial = initial;
        this.min = min;
        this.max = max;
        this.onAccept = onAccept;
    }

    /** Harness probe: the current text's parsed value, or null when invalid. */
    public Integer parsedValue() {
        if (this.input == null) {
            return null;
        }
        try {
            int value = Integer.parseInt(this.input.getValue().trim());
            return value >= this.min && value <= this.max ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Harness probe: drive the box like typing would (responder included). */
    public void setText(String text) {
        this.input.setValue(text);
    }

    /** Harness probe: press Done programmatically (no-op while invalid). */
    public boolean pressDone() {
        if (parsedValue() == null) {
            return false;
        }
        accept();
        return true;
    }

    private void accept() {
        Integer value = parsedValue();
        if (value != null) {
            this.onAccept.accept(value);
            this.minecraft.gui.setScreen(this.parent);
        }
    }

    @Override
    protected void init() {
        super.init();
        LinearLayout layout = LinearLayout.vertical().spacing(6);
        layout.defaultCellSetting().alignHorizontallyCenter();
        layout.addChild(new StringWidget(this.getTitle(), this.font));
        layout.addChild(new StringWidget(this.rangeLine, this.font));
        this.input = new EditBox(this.font, 100, 20, this.getTitle());
        this.input.setMaxLength(6);
        this.input.setValue(Integer.toString(this.initial));
        this.input.setResponder(text -> {
            if (this.doneButton != null) {
                this.doneButton.active = parsedValue() != null;
            }
        });
        layout.addChild(this.input);
        layout.addChild(new MultiLineTextWidget(
                this.costNote.copy().withStyle(ChatFormatting.GRAY), this.font)
                .setMaxWidth(240)
                .setCentered(true));
        LinearLayout buttons = LinearLayout.horizontal().spacing(8);
        this.doneButton = buttons.addChild(Button.builder(CommonComponents.GUI_DONE,
                b -> accept()).width(96).build());
        buttons.addChild(Button.builder(CommonComponents.GUI_CANCEL,
                b -> this.minecraft.gui.setScreen(this.parent)).width(96).build());
        layout.addChild(buttons, s -> s.paddingTop(4));
        layout.visitWidgets(this::addRenderableWidget);
        layout.arrangeElements();
        FrameLayout.centerInRectangle(layout, this.getRectangle());
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
