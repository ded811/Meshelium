/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.gui;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumGate;
import com.deds.meshelium.MesheliumPlatform;

import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The one-time popups required by the standing owner directive (SPEC
 * "graceful OpenGL fallback"): friendly, honest, and never a nag loop.
 *
 * <p><b>2026-09-16: no longer title-screen only.</b> A player who launches
 * straight into a world with quick-play, or a launcher's resume-last-world,
 * never sees a title screen, and used to get nothing at all. The gate now
 * shows this screen over the HUD as well, once the world has settled
 * ({@code MesheliumGate.IN_WORLD_SETTLE_TICKS}), and it PAUSES a private
 * singleplayer world while it is up, exactly as every vanilla menu does
 * (see {@link #isPauseScreen()}). In a world nothing can pause - a server,
 * or a world opened to LAN - the gate does not show this screen at all and
 * tells the player another way, because a modal there would take the mouse
 * while the world kept ticking.
 *
 * <p>Two consequences for this class, both handled below: {@code parent}
 * can now be null over a live level, which makes {@link #dismiss()} a
 * {@code Gui.setScreen(null)} with branches this screen never used to
 * reach; and [Quit Game] on the RESTART_REQUIRED variant can now be pressed
 * from inside a world. That path was re-verified in the 26.2 bytecode
 * rather than remembered: {@code Minecraft.stop()} is nothing but
 * {@code running = false} (ip 0-5), {@code Minecraft.run()} then returns
 * normally, and {@code Main.main} calls {@code exitWorldAndClose()} at ip
 * 1646 - which disconnects the level ({@code ClientLevel.disconnect} at ip
 * 25, {@code disconnectWithProgressScreen} at 29) and closes at ip 53. The
 * same route the pause menu's own quit takes. Both loaders reach
 * {@code Main.main} and neither can interpose between ip 1641 and 1646
 * (Fabric: {@code MinecraftGameProvider.launch} resolves
 * {@code net/minecraft/client/main/Main} and invokes its {@code main};
 * NeoForge: {@code net.neoforged.fml.startup.Client.main} does the same).
 *
 * <p>One class, four variants, because they share their whole skeleton and
 * differ only in text and buttons. Built purely out of vanilla widgets and
 * layouts, mirroring {@code ConfirmScreen}'s init/repositionElements shape
 * (26.2's screen rendering runs through {@code GuiGraphicsExtractor}; using
 * stock widgets means we never touch that surface directly). The option
 * write on [Enable Vulkan] is exactly seam doc Q1:
 * {@code options.preferredGraphicsBackend().set(VULKAN)} + {@code save()} —
 * backends are chosen at boot, hence the restart hand-off.</p>
 *
 * <p>With Sodium installed the body changes and the buttons do not. The
 * stock sentences say "Meshelium is staying off", which under Sodium is
 * half the story: Sodium is drawing the terrain, and what the Vulkan
 * switch buys is Meshelium drawing Sodium's chunks with mesh shaders. On
 * a loader whose jar has no adapter (NeoForge until 2026-09-14; the
 * branch is kept for any build that omits sodium/) the body says so, and
 * [Enable Vulkan] stays because the directive's popup is about the
 * backend and the sentence above the button says plainly what it will
 * and will not do (owner report 2026-09-08 (beta.8)).</p>
 */
public final class MesheliumPopupScreen extends Screen {

    public enum Variant {
        /** State (a): OpenGL active, Vulkan not requested — the mod's front door. */
        ENABLE_VULKAN("meshelium.popup.opengl.title", "meshelium.popup.opengl.body"),
        /** State (a) sub-case: option already says Vulkan but the boot fell back to GL. */
        VULKAN_FAILED("meshelium.popup.vulkan_failed.title", "meshelium.popup.vulkan_failed.body"),
        /** State (b): Vulkan active but the device has no usable VK_EXT_mesh_shader. */
        NO_MESH_SHADERS("meshelium.popup.no_mesh.title", "meshelium.popup.no_mesh.body"),
        /** Confirmation after [Enable Vulkan] wrote the option. */
        RESTART_REQUIRED("meshelium.popup.restart.title", "meshelium.popup.restart.body");

        final String titleKey;
        final String bodyKey;

        Variant(String titleKey, String bodyKey) {
            this.titleKey = titleKey;
            this.bodyKey = bodyKey;
        }
    }

    private static final int BODY_MAX_WIDTH = 320;
    private static final int BUTTON_WIDTH = 150;

    private final Variant variant;
    private final Screen parent;
    private final LinearLayout layout = LinearLayout.vertical().spacing(8);
    /** The body sentence, kept so the harness can read which wording was chosen. */
    private MultiLineTextWidget body;

    public MesheliumPopupScreen(Variant variant, Screen parent) {
        super(Component.translatable(variant.titleKey));
        this.variant = variant;
        this.parent = parent;
    }

    public Variant variant() {
        return this.variant;
    }

    /** Harness probe: the body sentence as rendered, for the Sodium wording legs. */
    public String bodyText() {
        return this.body != null ? this.body.getMessage().getString() : "";
    }

    @Override
    protected void init() {
        super.init();
        this.layout.defaultCellSetting().alignHorizontallyCenter();
        this.layout.addChild(new StringWidget(this.getTitle(), this.font));
        // Sodium-aware body: see the class javadoc. sodiumAdapterConfigured()
        // rather than sodiumAdapterArmed(), because the popup is shown on the
        // backend the adapter CANNOT run on, and the question here is
        // whether switching would let it.
        boolean sodium = MesheliumPlatform.isModLoaded(MesheliumGate.SODIUM_MOD_ID);
        boolean adapter = sodium && MesheliumGate.sodiumAdapterConfigured();
        String bodyKey = switch (this.variant) {
            case ENABLE_VULKAN -> adapter ? "meshelium.popup.opengl.body.sodium"
                    : sodium ? "meshelium.popup.opengl.body.sodium_no_adapter"
                    : this.variant.bodyKey;
            case VULKAN_FAILED -> sodium ? "meshelium.popup.vulkan_failed.body.sodium"
                    : this.variant.bodyKey;
            case NO_MESH_SHADERS -> sodium ? "meshelium.popup.no_mesh.body.sodium"
                    : this.variant.bodyKey;
            case RESTART_REQUIRED -> this.variant.bodyKey;
        };
        this.body = this.layout.addChild(new MultiLineTextWidget(
                Component.translatable(bodyKey), this.font)
                .setMaxWidth(BODY_MAX_WIDTH)
                .setCentered(true));
        this.addButtons();
        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    private void addButtons() {
        switch (this.variant) {
            case ENABLE_VULKAN -> {
                LinearLayout row = this.layout.addChild(
                        LinearLayout.horizontal().spacing(8), s -> s.paddingTop(8));
                row.addChild(button("meshelium.popup.enable_vulkan", this::enableVulkan));
                row.addChild(button("meshelium.popup.not_now", this::dismiss));
                this.layout.addChild(Button.builder(
                        Component.translatable("meshelium.popup.dont_show_again"),
                        b -> this.dontShowAgain()).width(200).build());
            }
            case VULKAN_FAILED, NO_MESH_SHADERS -> this.layout.addChild(
                    button("meshelium.popup.ok", this::dismiss), s -> s.paddingTop(8));
            case RESTART_REQUIRED -> {
                LinearLayout row = this.layout.addChild(
                        LinearLayout.horizontal().spacing(8), s -> s.paddingTop(8));
                row.addChild(button("meshelium.popup.quit", () -> this.minecraft.stop()));
                row.addChild(button("meshelium.popup.later", this::dismiss));
            }
        }
    }

    private static Button button(String key, Runnable action) {
        return Button.builder(Component.translatable(key), b -> action.run())
                .width(BUTTON_WIDTH).build();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        FrameLayout.centerInRectangle(this.layout, this.getRectangle());
    }

    @Override
    public void onClose() {
        this.dismiss();
    }

    /**
     * Pauses a private singleplayer world while it is up. Explicit rather
     * than inherited: {@code Screen.isPauseScreen} already defaults to true
     * (26.2 bytecode is {@code iconst_1; ireturn}), and this screen can now
     * appear in a world, so the behaviour is worth stating where a reader
     * looking for it will find it.
     *
     * <p>It is the right choice. {@code Gui.isPausing()} feeds
     * {@code Minecraft.runTick}'s
     * {@code pause = hasSingleplayerServer() && gui.isPausing() &&
     * !isPublished()} (ip 533-566), so a private world stops and its sounds
     * duck to MUSIC+UI while the player reads. On a server or a
     * LAN-published world it pauses nothing, which is exactly why the gate
     * does not show this screen there at all
     * ({@code MesheliumGate.popupWouldPauseThisWorld}).
     *
     * <p>The BACKGROUND is vanilla's and we do nothing to it.
     * {@code Screen.extractBackground} tests {@code isInGameUi()} at ip 0,
     * and its {@code Minecraft.level} test at ip 15-22 gates ONLY
     * {@code extractPanorama} (ip 29); {@code extractBlurredBackground} (ip
     * 34) and {@code extractMenuBackground} (ip 39) run on both sides of
     * that branch, and the in-world menu texture is chosen further in, on
     * the same {@code level != null} test. So in a world the player sees
     * blurred terrain and the in-world menu background - and a PANORAMA in
     * the in-world screenshot would mean the leg that took it ran at a
     * title screen and proved nothing.
     */
    @Override
    public boolean isPauseScreen() {
        return true;
    }

    private void enableVulkan() {
        this.minecraft.options.preferredGraphicsBackend().set(PreferredGraphicsApi.VULKAN);
        this.minecraft.options.save();
        // showVulkanPrompt is deliberately NOT cleared here. "Yes, switch
        // me to Vulkan" is not "never ask me again", and spending the
        // prompt on it cost the player the only notice they had.
        //
        // This used to clear it, reasoning that a boot which fell back to
        // GL would be caught by the VULKAN_FAILED notice instead. That
        // notice only fires while the option still READS Vulkan, and after
        // a crashed start-up it does not: Minecraft.<init> in 26.2
        // escalates on an unexpected shutdown during the previous start-up
        // — VULKAN is reset to DEFAULT and saved, and a DEFAULT that has
        // already crashed is forced to OPENGL (verified in the 26.2
        // bytecode, "Detected unexpected shutdown during last game
        // startup"). So a player who tried Vulkan and crashed came back to
        // an option that no longer said Vulkan, a VULKAN_FAILED notice
        // that could not fire, and a prompt they had silently spent:
        // Meshelium dormant for good with nothing on screen, ever again.
        // Reported by the owner, who remembered only ever pressing
        // [Enable Vulkan] and [Restart].
        //
        // [Don't Show This Again] is now the only thing that spends it.
        this.minecraft.gui.setScreen(new MesheliumPopupScreen(Variant.RESTART_REQUIRED, this.parent));
    }

    private void dontShowAgain() {
        MesheliumConfig config = MesheliumConfig.get();
        config.showVulkanPrompt = false;
        config.save();
        this.dismiss();
    }

    private void dismiss() {
        // parent == null is reachable now: in a world this popup is shown
        // over the HUD, so this is Gui.setScreen(null) - vanilla's own
        // in-game close. Two of its branches are new to this screen (javap
        // of Gui.setScreen):
        //   ip 61-78   IllegalStateException("Trying to return to in-game
        //              GUI during disconnection") while
        //              clientLevelTeardownInProgress is set;
        //   ip 100-168 with a dead player: a DeathScreen, or
        //              LocalPlayer.respawn() OUTRIGHT under
        //              doImmediateRespawn.
        //
        // The first is guarded here: a player who presses a button in the
        // frame their connection drops did nothing wrong and must not get a
        // crash. Gui.canInterruptScreen() is the only public reader of that
        // private flag, and with THIS screen up it is exactly !teardown,
        // because its other term is screen.canInterruptWithAnotherScreen(),
        // which is Screen.shouldCloseOnEsc() (iconst_1/ireturn) and this
        // class does not override it (grep over common/ sodium/ fabric/
        // neoforge/: zero hits). If it ever overrides it, this guard has to
        // be rewritten rather than deleted.
        //
        // The second is left to vanilla: it is what closing ANY screen over
        // a dead player does, and the gate no longer shows this screen over
        // one (tryShowOwedPopup checks isDeadOrDying before the settle).
        if (this.parent == null && !this.minecraft.gui.canInterruptScreen()) {
            return; // the teardown owns the screen; vanilla will set its own
        }
        this.minecraft.gui.setScreen(this.parent);
    }
}
