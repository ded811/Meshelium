/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.mixin;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.farfield.CacheKeys;
import com.deds.meshelium.farfield.FarField;
import com.deds.meshelium.farfield.extract.ExtractDispatch;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Far-field wave W2, extraction seam 3: the whole {@code ClientLevel} is
 * being replaced or torn down (FARFIELD-VANILLA-SEAM.md section 1.3
 * item 3 and recommended seam 3). The dossier's citations put every such
 * event through exactly two disjoint choke points on {@code Minecraft}:
 *
 * <ul>
 * <li>{@code setLevel(ClientLevel)} — dimension swaps and joins:
 * {@code handleRespawn} invokes it at ip 194 and {@code handleLogin} at
 * ip 175 after constructing the fresh level; setLevel's own body is just
 * the field write (ip 2) plus {@code updateLevelInEngines} and never
 * calls clearClientLevel;</li>
 * <li>{@code clearClientLevel(Screen)} — disconnect: nulls the level
 * field directly (ip 71-73) and never calls setLevel.</li>
 * </ul>
 *
 * Hooking both @HEAD therefore covers respawn, login and disconnect with
 * no double fire, and at either HEAD {@code Minecraft.level} still points
 * at the OLD, fully populated level.
 *
 * <p><b>No extraction here, and the accounting tells the truth about
 * BOTH discarded populations.</b> Bulk-extracting an entire outgoing
 * level (potentially tens of thousands of chunks) synchronously inside
 * a dimension swap would stall the swap for seconds;
 * {@link ExtractDispatch#onLevelSwap()} instead counts what it resets:
 * every dirty retained chunk (data only WE still held - the design's
 * level-swap rule) lands on {@code farSaveLost}, and every LIVE_DIRTY
 * or GONE_BEHIND record - terrain the next visit simply re-receives
 * and re-extracts - lands on the {@code farSaveLeftUnsaved} aggregate,
 * so a routine portal hop moves the aggregate and never the
 * zero-is-the-contract loss gauges. Arrival-side extraction closes the
 * gap in the next world one chunk at a time.</p>
 *
 * <p><b>These two choke points also own the {@link FarField} store
 * lifecycle</b> (coordinator wiring after W1/W2 landed): setLevel is the
 * single place every join and dimension swap passes through, so it
 * closes any previous store ({@code onWorldLeave}, a no-op when nothing
 * was armed), re-resolves the armed flag (a config edit between worlds
 * must reach the seams: {@code refreshArmed()} is documented as
 * mandatory after any master-switch change), and opens the store for
 * the incoming level ({@code onWorldJoin}, itself a no-op when the
 * master toggle is off). clearClientLevel closes the store on
 * disconnect. The lifecycle calls run OUTSIDE the armed() gate on
 * purpose: leave-must-flush even after a mid-session disable, and the
 * refresh is what lets an enable ever be observed.</p>
 */
@Mixin(Minecraft.class)
abstract class MinecraftLevelSwapMixin {

    @Unique
    private static boolean meshelium$levelSwapHookBroken;

    // javap, 26.2 merged jar (verified in this authoring session):
    //   public void setLevel(net.minecraft.client.multiplayer.ClientLevel);
    @Inject(
            method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V",
            at = @At("HEAD")
    )
    private void meshelium$beforeSetLevel(ClientLevel newLevel, CallbackInfo ci) {
        if (meshelium$levelSwapHookBroken) {
            return;
        }
        try {
            // Lifecycle first, outside the armed gate (class javadoc):
            // a config edit between worlds must arm the seams, and a
            // previously-open store must always close.
            ExtractDispatch.refreshArmed();
            FarField.onWorldLeave();
            if (ExtractDispatch.armed()) {
                ExtractDispatch.onLevelSwap();
                Minecraft mc = (Minecraft) (Object) this;
                FarField.onWorldJoin(
                        CacheKeys.worldKey(mc),
                        CacheKeys.dimensionKey(newLevel));
            }
        } catch (Throwable t) {
            meshelium$levelSwapHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium far-field level-swap bookkeeping failed; "
                            + "disabling the hook for this session", t);
        }
    }

    // javap, 26.2 merged jar (verified in this authoring session):
    //   public void clearClientLevel(net.minecraft.client.gui.screens.Screen);
    @Inject(
            method = "clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V",
            at = @At("HEAD")
    )
    private void meshelium$beforeClearClientLevel(Screen screen, CallbackInfo ci) {
        if (meshelium$levelSwapHookBroken) {
            return;
        }
        try {
            if (ExtractDispatch.armed()) {
                ExtractDispatch.onLevelSwap();
            }
            // Always: a disconnect must flush and park an open store
            // even if the master switch was flipped off mid-session.
            FarField.onWorldLeave();
        } catch (Throwable t) {
            meshelium$levelSwapHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium far-field level-swap bookkeeping failed; "
                            + "disabling the hook for this session", t);
        }
    }
}
