/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import com.deds.meshelium.gui.MesheliumOptionsScreen;

import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wave-13: the "Meshelium Settings..." button in vanilla's Video Settings
 * screen — owner directive (2026-08-10, verbatim): "our settings shouldnt
 * be hidden and need mod menu to access i think they should either be
 * directly in graphics settings or add another setting in options".
 * Video Settings is the "directly in graphics settings" half; and
 * {@code /meshelium} remain as before, now merely redundant routes.
 *
 * <h2>The seam (all javap-cited, 26.2 jar)</h2>
 * {@code VideoSettingsScreen extends OptionsSubScreen}, whose
 * {@code init()} runs {@code addTitle() → addContents() → addFooter()};
 * {@code addContents()} assigns {@code this.list = new OptionsList(…)}
 * (ip 27) and THEN calls the subclass's {@code addOptions()} (ip 31) — so
 * at {@code addOptions} HEAD the list exists and is empty.
 * {@code OptionsList.addHeader(Component)} and
 * {@code addBig(AbstractWidget)} are public;
 * {@code OptionsList$Entry.big(AbstractWidget, Screen)} calls
 * {@code setWidth(310)} on the widget itself, so a stock {@code Button}
 * needs no width plumbing. HEAD (not TAIL) placement is deliberate: the
 * row sits ABOVE the Display section, visible without scrolling — a
 * bottom-of-list row would be "hidden" all over again, and HEAD needs no
 * fragile ordinal targeting.
 *
 * <p><b>Navigation:</b> the button opens {@link MesheliumOptionsScreen}
 * with THIS screen as parent, so its Done returns here. <b>Wave-15
 * correction of the wave-13 claim:</b> returning does NOT rebuild the
 * OptionsList — 26.2's {@code Screen.init(int,int)} guards the
 * widget-building {@code init()} behind a once-per-instance
 * {@code initialized} flag (re-entry only repositions; bytecode-cited on
 * {@code ScreenInvoker}), which was exactly the owner-hit "have to back
 * out of the whole menu" bug. The Meshelium screen's {@code onClose} now
 * invokes {@code rebuildWidgets()} on this instance after a cap change,
 * so the render-distance slider re-reads its ValueSet
 * ({@code OptionInstance.createButton} reads the {@code values} field at
 * widget creation, ip 0-3) without leaving the options tree.</p>
 *
 * <p><b>Both backends:</b> the button is added unconditionally — on
 * OpenGL / no-mesh-shaders the Meshelium screen opens and shows its rows
 * locked with the reason (wave-13 options-screen rework). A button that
 * only exists on healthy Vulkan would be the silent-refusal class the
 * owner hit. This mixin touches only vanilla GUI types + the config-only
 * options screen — no vk package, wave-1 class-loading discipline
 * intact.</p>
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {

    protected VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
        super(lastScreen, options, title);
    }

    @Inject(method = "addOptions", at = @At("HEAD"))
    private void meshelium$addMesheliumSettingsRow(CallbackInfo ci) {
        this.list.addHeader(Component.translatable("meshelium.videosettings.header"));
        this.list.addBig(Button.builder(Component.translatable("meshelium.options.open"),
                button -> this.minecraft.gui.setScreen(new MesheliumOptionsScreen(this)))
                .build());
    }
}
