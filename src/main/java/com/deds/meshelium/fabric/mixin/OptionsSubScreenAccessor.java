/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Wave-15 back-out fix, second attempt (the first is a coordinator-run
 * lesson): reads {@code protected final Screen lastScreen} so
 * {@code MesheliumOptionsScreen.onClose} can construct a FRESH
 * {@code VideoSettingsScreen} with the original navigation chain after a
 * cap change.
 *
 * <p>Why fresh-instance and not {@code rebuildWidgets()} on the cached
 * parent: {@code OptionsSubScreen.addContents()} does
 * {@code layout.addToContents(new OptionsList(...))} against a
 * {@code HeaderAndFooterLayout} FIELD that accumulates (bytecode: ip 2
 * getfield layout, ip 5 new OptionsList, ip 21 addToContents, ip 27
 * putfield list — no removal of the previous entry anywhere in
 * {@code init()}). A second {@code init()} therefore duplicates every
 * layout widget, and the STALE first OptionsList still sits in
 * {@code children()} ahead of the fresh one — the harness's identity
 * assert caught exactly that on the real client (run log 2026-08-10:
 * all three onClose guards true, rebuild invoked, widget unchanged).
 * Vanilla itself only ever rebuilds options screens by constructing new
 * instances, which is why this landmine is invisible in vanilla play —
 * so Meshelium does what vanilla does.</p>
 */
@Mixin(OptionsSubScreen.class)
public interface OptionsSubScreenAccessor {

    @Accessor("lastScreen")
    Screen meshelium$lastScreen();
}
