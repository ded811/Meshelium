package com.deds.meshelium.gui;

import com.deds.meshelium.MesheliumConfig;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The one-time title-screen popups required by the standing owner directive
 * (SPEC "graceful OpenGL fallback"): friendly, honest, and never a nag loop.
 *
 * <p>One class, four variants, because they share their whole skeleton and
 * differ only in text and buttons. Built purely out of vanilla widgets and
 * layouts, mirroring {@code ConfirmScreen}'s init/repositionElements shape
 * (26.2's screen rendering runs through {@code GuiGraphicsExtractor}; using
 * stock widgets means we never touch that surface directly). The option
 * write on [Enable Vulkan] is exactly seam doc Q1:
 * {@code options.preferredGraphicsBackend().set(VULKAN)} + {@code save()} —
 * backends are chosen at boot, hence the restart hand-off.</p>
 */
@Environment(EnvType.CLIENT)
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

    public MesheliumPopupScreen(Variant variant, Screen parent) {
        super(Component.translatable(variant.titleKey));
        this.variant = variant;
        this.parent = parent;
    }

    public Variant variant() {
        return this.variant;
    }

    @Override
    protected void init() {
        super.init();
        this.layout.defaultCellSetting().alignHorizontallyCenter();
        this.layout.addChild(new StringWidget(this.getTitle(), this.font));
        this.layout.addChild(new MultiLineTextWidget(
                Component.translatable(this.variant.bodyKey), this.font)
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

    private void enableVulkan() {
        this.minecraft.options.preferredGraphicsBackend().set(PreferredGraphicsApi.VULKAN);
        this.minecraft.options.save();
        // They said yes — never prompt again. If the restarted game lands on
        // Vulkan the gate goes quiet on its own; if the boot falls back to GL
        // the honest VULKAN_FAILED notice takes over (once).
        MesheliumConfig config = MesheliumConfig.get();
        config.showVulkanPrompt = false;
        config.save();
        this.minecraft.gui.setScreen(new MesheliumPopupScreen(Variant.RESTART_REQUIRED, this.parent));
    }

    private void dontShowAgain() {
        MesheliumConfig config = MesheliumConfig.get();
        config.showVulkanPrompt = false;
        config.save();
        this.dismiss();
    }

    private void dismiss() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
