package com.deds.meshelium.gui;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumGate;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.components.AbstractSliderButton;
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

import java.util.function.Consumer;

/**
 * The Meshelium options screen — wave-8 skeleton, wave-13 status header and
 * gate honesty, reworked in wave 15 from the owner's settings playtest
 * (2026-08-10, 12 directives). Reached three ways: the "Meshelium
 * Settings..." button in vanilla's Video Settings screen
 * ({@code VideoSettingsScreenMixin}, the primary route) and the
 * {@code /meshelium} client command. The wave-8 ModMenu adapter was
 * REMOVED at 1.0.0: it was a third route to this same screen and the only
 * thing stopping a clean clone from building.
 *
 * <h2>Wave-15 layout (owner directive 4: "re arange it in a way that
 * makes sense")</h2>
 * Status header first, then the setting players actually touch (the
 * render-distance cap), then the advanced/diagnostic rows (occlusion,
 * the terrain master toggle, debug stats, backend popup), then Done. The
 * wave-13 footer paragraph and the per-row inline notes are GONE
 * (directives 1/10): every row and the status header carry a hover
 * {@link Tooltip} instead ({@code AbstractWidget.setTooltip(Tooltip)} +
 * {@code Tooltip.create}, both javap-cited, 26.2 jar), and each
 * tooltip's last line states the row's apply semantics, the wave-13
 * row-level honesty moved into the hover, with the gate-off reason
 * REPLACING it on a locked screen exactly as before. No textual group
 * headers: the rows plus the locked-state banner have to fit a 240-unit
 * GUI height, so the grouping is carried by ORDER alone (the wave-13
 * small-GUI risk note).
 *
 * <h2>2026-08-11: the two retention rows are gone</h2>
 * The retention toggle and the retention time limit used to sit here,
 * directly under the status header. Both rows were retired the day the
 * owner decided to pair with <b>Bobby</b> instead: vanilla's fog wall is
 * fully opaque exactly where the chunk grid ends, so retained terrain is
 * invisible in normal play, and the real fix is a data-layer cache of
 * server-sent chunks, which is Bobby's job. The wave-11 machinery is
 * untouched behind {@code MesheliumConfig.retainTerrain} (now default
 * FALSE, config file and {@code -Dmeshelium.retainTerrain} only, full
 * reasoning on that field). Five rows are left, so the layout only got
 * roomier; the row count is nowhere a constant, the vertical
 * {@link LinearLayout} measures whatever it is given.
 *
 * <h2>Wave-15 slider (directive 5)</h2>
 * The cap row is a real {@link AbstractSliderButton} over the 32..96
 * lattice in steps of 8, with a [Custom] button opening
 * {@link MesheliumCustomValueScreen} for any value up to the wire-bounded
 * hard max ({@value MesheliumConfig#MAX_MAX_RENDER_DISTANCE}: the
 * requested view distance travels as a SIGNED BYTE, so 127 is a hard
 * cliff; constant javadoc). A config value off the lattice (custom, or
 * hand-edited) is DISPLAYED exactly while the thumb parks at the nearest
 * stop; touching the slider snaps to the lattice. (The retention limit
 * was the second slider until the rows above were retired.)
 *
 * <h2>Wave-15 status header (directives 2/3)</h2>
 * ACTIVE now shows the live <b>chunk section</b> count only — the
 * ticking frame counter is gone (owner: "we dont need to record every
 * frame"); the section count updating as the camera moves remains the
 * live-activity proof. "Chunk sections" is the honest unit: they are
 * 16×16×16 world slices, roughly 24 per chunk column in a 384-tall
 * world, and the header's tooltip says exactly that (never falsely
 * "chunks"). NOT RENDERING still names the exact reason (wave-13/14
 * machinery unchanged). Rebuilt per {@link #tick()} via
 * {@code StringWidget.setMessage} as before.
 *
 * <h2>Wave-15 back-out fix (directive 7 — the owner-hit bug)</h2>
 * In 26.2 {@code Screen.init(int,int)} runs the widget-building
 * {@code init()} only while the per-instance {@code initialized} flag is
 * false — the flag is set once and never cleared (only write: ip 33;
 * re-entry takes the {@code repositionElements()} branch, ip 28,
 * bytecode-cited on {@code ScreenInvoker}). Returning to the CACHED
 * parent therefore never rebuilt the Video Settings OptionsList, and its
 * render-distance slider kept the ValueSet captured at widget creation:
 * a cap change forced the player to back out of the WHOLE options tree.
 * Fix: {@link #onClose()} — when the cap changed while this screen was
 * open and the parent is the Video Settings screen — replaces the stale
 * parent with a FRESH {@code VideoSettingsScreen} carrying the original
 * {@code lastScreen}, exactly how vanilla itself refreshes options
 * screens. ({@code rebuildWidgets()} on the cached parent was tried
 * first and refuted on the real client: {@code addContents()} ADDS a
 * second OptionsList to the accumulating {@code HeaderAndFooterLayout}
 * and the stale first list shadows it — evidence on
 * {@code OptionsSubScreenAccessor}.)
 *
 * <p><b>Dev-override honesty</b> (wave-8 pattern, retained): a system
 * property overriding a row leaves it visible but inactive, with the
 * banner explaining why. <b>Gate honesty</b> (wave-13, retained): when
 * the gate is not VULKAN_MESH_SHADERS the renderer rows render INACTIVE
 * with a banner naming the exact cause, plus the [Enable Vulkan]
 * affordance on the plain-GL path (broken-promise rule unchanged).</p>
 */
@Environment(EnvType.CLIENT)
public final class MesheliumOptionsScreen extends Screen {

    private static final int WIDGET_WIDTH = 200;
    private static final int SLIDER_WIDTH = 150;
    private static final int CUSTOM_WIDTH = 46;
    private static final int BANNER_MAX_WIDTH = 340;

    /** The cap slider's lattice: 32..96 in 8s (custom box goes to 120). */
    private static final int[] CAP_STOPS = buildCapStops();

    private final Screen parent;
    private final LinearLayout layout = LinearLayout.vertical().spacing(2);
    /** Wave-13 harness probe: true when the gate locked the renderer rows. */
    private boolean gateLocked;
    /** The tick-updated status header (see class javadoc). */
    private StringWidget statusLine;
    /** Wave-15: cap value at init — onClose rebuilds a stale parent on change. */
    private int capAtOpen;
    private CapSlider capSlider;

    public MesheliumOptionsScreen(Screen parent) {
        super(Component.translatable("meshelium.options.title"));
        this.parent = parent;
    }

    private static int[] buildCapStops() {
        int n = (MesheliumConfig.SLIDER_MAX_RENDER_DISTANCE
                - MesheliumConfig.MIN_MAX_RENDER_DISTANCE) / 8 + 1;
        int[] stops = new int[n];
        for (int i = 0; i < n; i++) {
            stops[i] = MesheliumConfig.MIN_MAX_RENDER_DISTANCE + i * 8;
        }
        return stops;
    }

    // ------------------------------------------------------------------
    // Harness probes
    // ------------------------------------------------------------------

    /** Wave-13 harness probe: were the renderer rows locked by the gate? */
    public boolean gateLocked() {
        return this.gateLocked;
    }

    /** Wave-13 harness probe: the status header's current text. */
    public String statusText() {
        return this.statusLine != null ? this.statusLine.getMessage().getString() : "";
    }

    /** Wave-15 harness probe: the cap slider's displayed label. */
    public String capSliderText() {
        return this.capSlider != null ? this.capSlider.getMessage().getString() : "";
    }

    /** Wave-15 harness probe: drive the cap exactly like the slider does. */
    public void testSetCap(int cap) {
        applyCap(cap);
    }

    /** Wave-15 harness probe: open the cap custom screen (the button's action). */
    public void testOpenCapCustom() {
        openCapCustom();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.statusLine != null) {
            this.statusLine.setMessage(buildStatusLine());
        }
    }

    // ------------------------------------------------------------------
    // Status header (wave 13, counters reworked in wave 15)
    // ------------------------------------------------------------------

    /**
     * The status header, recomputed per tick. Reads: the gate (volatile),
     * config resolvers (pure), and — ONLY under a decided
     * VULKAN_MESH_SHADERS gate — the drawer's volatile probe statics
     * (class-loading discipline, wave-10 pattern).
     */
    private Component buildStatusLine() {
        return switch (MesheliumGate.state()) {
            case OPENGL -> off("meshelium.options.status.reason.opengl");
            case VULKAN_NO_MESH_SHADERS -> off("meshelium.options.status.reason.no_mesh");
            case UNKNOWN -> Component.translatable("meshelium.options.status.checking")
                    .withStyle(ChatFormatting.YELLOW);
            case VULKAN_MESH_SHADERS -> vulkanStatusLine();
        };
    }

    private Component vulkanStatusLine() {
        if (!MesheliumConfig.terrainRenderingEnabled()) {
            return off("meshelium.options.status.reason.terrain_off");
        }
        if (com.deds.meshelium.vk.TerrainDrawer.lastError() != null) {
            return off("meshelium.options.status.reason.error");
        }
        if (this.minecraft == null || this.minecraft.level == null) {
            return Component.translatable("meshelium.options.status.ready")
                    .withStyle(ChatFormatting.GREEN);
        }
        if (com.deds.meshelium.vk.TerrainDrawer.coveragePassive()) {
            return offPassive();
        }
        // Wave-15: the live chunk-section count is the activity proof;
        // the per-frame counter is gone (owner directive 2). "Chunk
        // sections", not "chunks" — the tooltip carries the definition.
        return Component.translatable("meshelium.options.status.active",
                com.deds.meshelium.vk.TerrainDrawer.lastDrawnSections())
                .withStyle(ChatFormatting.GREEN);
    }

    private static Component off(String reasonKey) {
        return Component.translatable("meshelium.options.status.off",
                Component.translatable(reasonKey)).withStyle(ChatFormatting.RED);
    }

    /**
     * Wave-14 guard honesty: the passive line names WHICH budget tripped
     * and its size at trip time ({@code TerrainResidency.guardTrip()} —
     * host package, LWJGL-free, safe from the client tick; non-null
     * whenever the guard is passive because every drop site notes its
     * cause first). The generic line stays as the null-race fallback.
     */
    private static Component offPassive() {
        com.deds.meshelium.terrain.host.TerrainResidency.GuardTrip trip =
                com.deds.meshelium.terrain.host.TerrainResidency.guardTrip();
        if (trip == null) {
            return off("meshelium.options.status.reason.passive");
        }
        Component reason = switch (trip.kind()) {
            case "arena" -> Component.translatable(
                    "meshelium.options.status.reason.passive.arena", trip.value(), trip.limit());
            case "oversize" -> Component.translatable(
                    "meshelium.options.status.reason.passive.oversize", trip.value(), trip.limit());
            case "region" -> Component.translatable(
                    "meshelium.options.status.reason.passive.region", trip.value(), trip.limit());
            default -> Component.translatable("meshelium.options.status.reason.passive.encoding");
        };
        return Component.translatable("meshelium.options.status.off", reason)
                .withStyle(ChatFormatting.RED);
    }

    /**
     * The wave-1 popup's [Enable Vulkan] mechanics, verbatim (seam Q1):
     * write {@code preferredGraphicsBackend = VULKAN}, save, suppress the
     * boot prompt (they said yes here), hand off to the RESTART_REQUIRED
     * popup — backends swap only at boot. Dismissing the popup returns
     * to this screen.
     */
    private void enableVulkan() {
        this.minecraft.options.preferredGraphicsBackend().set(PreferredGraphicsApi.VULKAN);
        this.minecraft.options.save();
        MesheliumConfig config = MesheliumConfig.get();
        config.showVulkanPrompt = false;
        config.save();
        this.minecraft.gui.setScreen(new MesheliumPopupScreen(
                MesheliumPopupScreen.Variant.RESTART_REQUIRED, this));
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        MesheliumConfig config = MesheliumConfig.get();
        MesheliumGate.State gate = MesheliumGate.state();
        this.gateLocked = gate != MesheliumGate.State.VULKAN_MESH_SHADERS;
        this.capAtOpen = config.maxRenderDistance;
        boolean terrainOverridden = System.getProperty("meshelium.terrainDraw") != null;
        boolean statsOverridden = System.getProperty("meshelium.debugStats") != null;
        boolean maxRdOverridden = System.getProperty("meshelium.maxRenderDistance") != null;
        // meshelium.retainTerrain / meshelium.retainSeconds are deliberately
        // absent from this census: retention has no row left to lock
        // (2026-08-11, see the class javadoc), so a dev arming it must
        // not raise the "some rows are locked" banner over rows it
        // cannot touch.

        this.layout.defaultCellSetting().alignHorizontallyCenter();
        this.layout.addChild(new StringWidget(this.getTitle(), this.font));

        // 1. Status header: is Meshelium rendering, right now? (tick()
        // keeps the section count live; tooltip defines chunk sections.)
        this.statusLine = this.layout.addChild(
                new StringWidget(buildStatusLine(), this.font), s -> s.paddingTop(2));
        this.statusLine.setTooltip(Tooltip.create(
                Component.translatable("meshelium.options.tooltip.status")));

        // Gate banner: WHY the rows below are locked, by exact cause —
        // plus the wave-1 [Enable Vulkan] affordance on the plain-GL path
        // (no button when the option already says VULKAN but boot fell
        // back — the broken-promise rule).
        if (this.gateLocked) {
            boolean vulkanAlreadyRequested = gate == MesheliumGate.State.OPENGL
                    && this.minecraft.options.preferredGraphicsBackend().get()
                            == PreferredGraphicsApi.VULKAN;
            String reasonKey = switch (gate) {
                case OPENGL -> vulkanAlreadyRequested
                        ? "meshelium.options.gate.vulkan_failed"
                        : "meshelium.options.gate.opengl";
                case VULKAN_NO_MESH_SHADERS -> "meshelium.options.gate.no_mesh";
                default -> "meshelium.options.gate.unknown";
            };
            this.layout.addChild(new MultiLineTextWidget(
                    Component.translatable(reasonKey).withStyle(ChatFormatting.YELLOW), this.font)
                    .setMaxWidth(BANNER_MAX_WIDTH)
                    .setCentered(true), s -> s.paddingTop(2));
            if (gate == MesheliumGate.State.OPENGL && !vulkanAlreadyRequested) {
                Button enable = Button.builder(
                        Component.translatable("meshelium.popup.enable_vulkan"),
                        b -> this.enableVulkan()).width(WIDGET_WIDTH).build();
                enable.setTooltip(Tooltip.create(
                        Component.translatable("meshelium.options.tooltip.enable_vulkan")));
                this.layout.addChild(enable);
            }
        }
        if (terrainOverridden || statsOverridden || maxRdOverridden) {
            this.layout.addChild(new MultiLineTextWidget(
                    Component.translatable("meshelium.options.dev_override"), this.font)
                    .setMaxWidth(BANNER_MAX_WIDTH)
                    .setCentered(true));
        }

        // 2. The setting players actually touch. (The retention toggle
        // and its time limit used to follow here; retired 2026-08-11,
        // Bobby owns that job now. Class javadoc has the argument.)
        this.capSlider = new CapSlider(config, !this.gateLocked && !maxRdOverridden);
        this.capSlider.setTooltip(tip("meshelium.options.tooltip.max_rd",
                "meshelium.options.applies.now"));
        Button capCustom = Button.builder(Component.translatable("meshelium.options.custom"),
                b -> openCapCustom()).width(CUSTOM_WIDTH).build();
        capCustom.active = !this.gateLocked && !maxRdOverridden;
        capCustom.setTooltip(tip("meshelium.options.tooltip.max_rd_custom",
                "meshelium.options.applies.now"));
        this.layout.addChild(sliderRow(this.capSlider, capCustom), s -> s.paddingTop(4));

        // 3. Advanced / diagnostic rows.
        //
        // NO OCCLUSION ROW AT 1.0.0. The feature stays in the build and
        // still works, but offering it to players would be offering a
        // setting that currently makes the game slower: the two box
        // rasters cost about 3.1 ms per frame at 1920x1080 while the
        // drawing they save costs about 0.1 ms (GPU timestamps, ground
        // level taiga, 2026-08-12). It culls well and pays for it in fill
        // rate, because a bounding box near the camera shades most of the
        // screen and the pipelines do not cull back faces.
        //
        // Hidden rather than deleted, because the fix is known and
        // scoped: exempt boxes near the camera, then enable back face
        // culling, then rasterise the test at reduced resolution. When
        // that lands and measures as a win, the row comes back, ideally
        // as Auto rather than a plain toggle. Until then developers reach
        // it through config/meshelium.json or
        // -Dmeshelium.terrainDraw.bfsOnly=true.

        CycleButton<Boolean> terrain = toggle("meshelium.options.terrain",
                config.enableTerrainRendering, !this.gateLocked && !terrainOverridden, value -> {
                    config.enableTerrainRendering = value;
                    config.save();
                    com.deds.meshelium.MesheliumExtendedRd.onConfigChanged(this.minecraft);
                });
        terrain.setTooltip(tip("meshelium.options.tooltip.terrain", "meshelium.options.applies.now"));
        this.layout.addChild(terrain);

        CycleButton<Boolean> stats = toggle("meshelium.options.debug_stats",
                config.debugStats, !this.gateLocked && !statsOverridden, value -> {
                    config.debugStats = value;
                    config.save();
                });
        stats.setTooltip(tip("meshelium.options.tooltip.debug_stats",
                "meshelium.options.applies.now"));
        this.layout.addChild(stats);

        // The backend-popup re-arm: active on EVERY backend (it is about
        // the non-Vulkan case); next-boot semantics by nature — its
        // tooltip states them even on a locked screen.
        CycleButton<Boolean> popup = toggle("meshelium.options.popup",
                config.showVulkanPrompt, true, value -> {
                    config.showVulkanPrompt = value;
                    config.noMeshShaderNoticeShown = !value;
                    config.vulkanFailedNoticeShown = !value;
                    config.save();
                });
        popup.setTooltip(Tooltip.create(withSemantics(
                Component.translatable("meshelium.options.tooltip.popup"),
                "meshelium.options.applies.restart")));
        this.layout.addChild(popup);

        this.layout.addChild(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
                .width(WIDGET_WIDTH).build(), s -> s.paddingTop(4));

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    /** One slider row: the slider plus its [Custom] button. */
    private static LinearLayout sliderRow(AbstractSliderButton slider, Button custom) {
        LinearLayout row = LinearLayout.horizontal().spacing(4);
        row.defaultCellSetting().alignVerticallyMiddle();
        row.addChild(slider);
        row.addChild(custom);
        return row;
    }

    /**
     * Wave-15 tooltip builder: the row's description plus its apply
     * semantics as the last line — the wave-13 inline-note honesty moved
     * into the hover. On a gate-locked screen the semantics line is
     * REPLACED by the gate reason (a locked row's only honest annotation
     * is why it is locked — the wave-13 rule, unchanged).
     */
    private Tooltip tip(String descriptionKey, String appliesKey) {
        return Tooltip.create(withSemantics(Component.translatable(descriptionKey),
                this.gateLocked ? "meshelium.options.applies.vulkan" : appliesKey));
    }

    private static Component withSemantics(MutableComponent description, String semanticsKey) {
        return description.append(Component.literal("\n\n"))
                .append(Component.translatable(semanticsKey).withStyle(ChatFormatting.GRAY));
    }

    private static CycleButton<Boolean> toggle(String key, boolean initial, boolean active,
            Consumer<Boolean> onChange) {
        CycleButton<Boolean> button = CycleButton.onOffBuilder(initial)
                .create(Component.translatable(key), (b, value) -> onChange.accept(value));
        button.setWidth(WIDGET_WIDTH);
        button.active = active;
        return button;
    }

    // ------------------------------------------------------------------
    // The cap row (wave 15: slider + custom)
    // ------------------------------------------------------------------

    /** The one write path for the cap — slider, custom box and probe share it. */
    private void applyCap(int cap) {
        MesheliumConfig config = MesheliumConfig.get();
        config.maxRenderDistance = cap;
        config.save();
        com.deds.meshelium.MesheliumExtendedRd.onConfigChanged(this.minecraft);
        if (this.capSlider != null) {
            this.capSlider.refreshFromConfig();
        }
    }

    private void openCapCustom() {
        this.minecraft.gui.setScreen(new MesheliumCustomValueScreen(this,
                Component.translatable("meshelium.custom.max_rd.title"),
                Component.translatable("meshelium.custom.range",
                        MesheliumConfig.MIN_MAX_RENDER_DISTANCE,
                        MesheliumConfig.MAX_MAX_RENDER_DISTANCE),
                Component.translatable("meshelium.custom.max_rd.cost"),
                MesheliumConfig.get().maxRenderDistance,
                MesheliumConfig.MIN_MAX_RENDER_DISTANCE,
                MesheliumConfig.MAX_MAX_RENDER_DISTANCE,
                this::applyCap));
    }

    /**
     * Wave-15: the render-distance-cap SLIDER (owner directive 5),
     * discrete over {@link #CAP_STOPS}. {@code applyValue()} fires on
     * release/drag with the snapped lattice value; a custom value beyond
     * the lattice (from the box) is displayed exactly while the thumb
     * parks at the nearest stop — touching the slider then deliberately
     * snaps back onto the lattice (the box exists for off-lattice
     * values). AbstractSliderButton ctor/overrides javap-cited (value is
     * the 0..1 fraction; updateMessage/applyValue are the two abstracts).
     */
    private final class CapSlider extends AbstractSliderButton {
        private int displayed;

        CapSlider(MesheliumConfig config, boolean active) {
            super(0, 0, SLIDER_WIDTH, 20, Component.empty(),
                    fraction(config.maxRenderDistance, CAP_STOPS));
            this.active = active;
            this.displayed = config.maxRenderDistance;
            updateMessage();
        }

        void refreshFromConfig() {
            this.displayed = MesheliumConfig.get().maxRenderDistance;
            this.value = fraction(this.displayed, CAP_STOPS);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            Component shown = this.displayed <= MesheliumConfig.MIN_MAX_RENDER_DISTANCE
                    ? Component.translatable("meshelium.options.max_rd.vanilla")
                    : Component.literal(Integer.toString(this.displayed));
            setMessage(Component.translatable("meshelium.options.max_rd.label", shown));
        }

        @Override
        protected void applyValue() {
            int snapped = nearestStop(this.value, CAP_STOPS);
            if (snapped != this.displayed) {
                this.displayed = snapped;
                applyCap(snapped);
            }
            updateMessage();
        }
    }

    /** Slider fraction (0..1) for a value, by nearest lattice stop index. */
    private static double fraction(int value, int[] stops) {
        int best = 0;
        for (int i = 1; i < stops.length; i++) {
            if (Math.abs(value - stops[i]) < Math.abs(value - stops[best])) {
                best = i;
            }
        }
        return best / (double) (stops.length - 1);
    }

    /** The lattice stop nearest to a 0..1 slider fraction. */
    private static int nearestStop(double fraction, int[] stops) {
        int index = (int) Math.round(fraction * (stops.length - 1));
        return stops[Math.max(0, Math.min(stops.length - 1, index))];
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        FrameLayout.centerInRectangle(this.layout, this.getRectangle());
    }

    /**
     * Wave-15 back-out fix (class javadoc): hand the screen back, then —
     * if the cap changed while this screen was open and the parent is a
     * vanilla options screen — rebuild the parent's widgets through
     * vanilla's own {@code rebuildWidgets()} so its render-distance
     * slider re-reads the already-swapped ValueSet. Ordered AFTER
     * {@code setScreen} so the parent's {@code init(II)} has refreshed
     * its width/height first (a window resized while this screen was
     * open). Scoped to {@code OptionsSubScreen} parents: they are the
     * only ones holding option-widget caches, and rebuilding arbitrary
     * mod screens is not this fix's business.
     */
    @Override
    public void onClose() {
        boolean capChanged = MesheliumConfig.get().maxRenderDistance != this.capAtOpen;
        Screen target = this.parent;
        if (capChanged
                && this.parent instanceof net.minecraft.client.gui.screens.options.VideoSettingsScreen) {
            // Fresh instance, vanilla-style: rebuildWidgets() on the cached
            // parent DUPLICATES its HeaderAndFooterLayout contents and the
            // stale first OptionsList shadows the fresh one (bytecode +
            // run-log evidence on OptionsSubScreenAccessor). The original
            // navigation chain rides along via lastScreen.
            target = new net.minecraft.client.gui.screens.options.VideoSettingsScreen(
                    ((com.deds.meshelium.fabric.mixin.OptionsSubScreenAccessor) this.parent)
                            .meshelium$lastScreen(),
                    this.minecraft, this.minecraft.options);
        }
        this.minecraft.gui.setScreen(target);
    }
}
