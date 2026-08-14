/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.gui;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumGate;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
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
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

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
 * <h2>Wave-15 slider (directive 5), 1.1 inline box</h2>
 * The cap row is a real {@link AbstractSliderButton} over the 32..96
 * lattice in steps of 8, with an INLINE {@code ValueBox} beside it for any
 * value up to the wire-bounded
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
    private StringWidget memoryLine;
    /** Wave-15: cap value at init — onClose rebuilds a stale parent on change. */
    private int capAtOpen;
    /**
     * Vanilla's render distance at init, for the same reason as
     * {@link #capAtOpen} and covering the case that one missed.
     *
     * <p>Toggling Meshelium Rendering changes the render distance VALUE (the
     * clamp to 32 on the way off, the restore on the way back) and the
     * option's RANGE, while leaving the cap alone. The back-out fix keyed on
     * the cap only, so returning to Video Settings handed back a cached
     * screen whose slider widget had been built from the old range: it read
     * 32 and would not go higher, no matter what the underlying value said.
     * The owner reported both halves of that as separate bugs, "it says it
     * set the render distance to 120 but it never actually does" and "its
     * still capped at 32 until i leave and re open settings". One cause.</p>
     */
    private int rdAtOpen;
    private CapSlider capSlider;
    private ValueBox capBox;
    private OcclusionRdSlider occlusionSlider;
    private ValueBox occlusionBox;
    private FogEndSlider fogSlider;
    private ValueBox fogBox;
    /** Reset button arming: first click asks, second click resets. */
    private boolean resetArmed;
    private Button resetButton;

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

    /** Harness probe: type into the inline cap box without committing. */
    public void testSetCapBoxText(String text) {
        if (this.capBox != null) {
            this.capBox.setValue(text);
        }
    }

    /** Harness probe: what the inline cap box currently shows. */
    public String testCapBoxText() {
        return this.capBox != null ? this.capBox.getValue() : "";
    }

    /**
     * Harness probe: alpha of the cap box's current text colour.
     *
     * <p>Exists because the first inline-box build set an RGB value where
     * {@code EditBox} wants ARGB, so the text rendered fully transparent
     * the moment anything was typed and the box looked like it had gone
     * blank. Every existing assertion passed, because they all read the
     * VALUE and none of them could see the pixels. Alpha 0 is never a
     * legitimate state for text that is meant to be read.</p>
     */
    public int testCapBoxTextAlpha() {
        return this.capBox != null ? this.capBox.testTextAlpha() : 0;
    }

    /**
     * Harness probe: commit the inline cap box, exactly as Enter or losing
     * focus does. Separate from typing because the box deliberately does
     * NOT commit per keystroke.
     */
    public void testCommitCapBox() {
        if (this.capBox != null) {
            this.capBox.testCommit();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.statusLine != null) {
            this.statusLine.setMessage(buildStatusLine());
        }
        if (this.memoryLine != null) {
            this.memoryLine.setMessage(memoryStatus());
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

    /**
     * Live terrain memory, appended to the status line.
     *
     * <p>Here rather than as a bar in the settings list, and the reason is
     * that Meshelium's memory is DYNAMIC: it grows as the player explores
     * and shrinks when they leave. Games that draw a VRAM bar are showing
     * the cost of STATIC choices - texture quality, shadow resolution -
     * where an estimate is stable enough to act on. A bar whose number
     * moves while you walk teaches nobody anything. What is actionable is
     * what it is using right now, next to whether it is running at all.</p>
     *
     * <p>Shows the free figure only when the driver reports a real budget;
     * on a driver without VK_EXT_memory_budget there is no honest number
     * for it and inventing one would be worse than omitting it.</p>
     */
    private static Component memoryStatus() {
        long used = com.deds.meshelium.terrain.host.TerrainResidency.counters().arenaUsedBytes();
        long budget = com.deds.meshelium.MesheliumVramState.budgetBytes();
        if (budget <= 0) {
            return Component.translatable("meshelium.options.status.memory", used >> 20)
                    .withStyle(ChatFormatting.GRAY);
        }
        long free = Math.max(0L, budget - com.deds.meshelium.MesheliumVramState.usageBytes());
        return Component.translatable("meshelium.options.status.memory_free",
                used >> 20, free >> 20).withStyle(ChatFormatting.GRAY);
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
            // "vram" was missing here and fell to the default, so a graphics
            // card refusing more memory told the player a section had failed
            // to encode. The owner hit exactly that and reported the wrong
            // cause back, because we printed the wrong cause. The sibling
            // switch in TerrainResidency was fixed earlier; this one was not,
            // and this is the one the player actually reads.
            case "vram" -> Component.translatable(
                    "meshelium.options.status.reason.passive.vram", trip.value(), trip.limit());
            case "encoding" -> Component.translatable(
                    "meshelium.options.status.reason.passive.encoding");
            // Name every cause above. A new kind reaching here is a bug in
            // the caller, and saying so beats blaming the encoder again.
            default -> Component.translatable("meshelium.options.status.reason.passive");
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
        this.rdAtOpen = this.minecraft != null && this.minecraft.options != null
                ? this.minecraft.options.renderDistance().get() : -1;
        boolean terrainOverridden = System.getProperty("meshelium.terrainDraw") != null;
        boolean maxRdOverridden = System.getProperty("meshelium.maxRenderDistance") != null;
        // Hoisted from the occlusion row below so the dev-override banner,
        // which is built before it, can count it.
        boolean occlusionOverridden = System.getProperty("meshelium.terrainDraw.bfsOnly") != null;
        boolean fogOverridden = System.getProperty("meshelium.fogMode") != null;
        // meshelium.retainTerrain / meshelium.retainSeconds are deliberately
        // absent from this census: retention has no row left to lock
        // (2026-08-11, see the class javadoc), so a dev arming it must
        // not raise the "some rows are locked" banner over rows it
        // cannot touch.
        //
        // meshelium.debugStats left for the same reason when Debug Stat
        // Logging moved to the Advanced screen. That screen runs its own
        // census over its own rows; a banner here would point at a row this
        // screen no longer has.

        this.layout.defaultCellSetting().alignHorizontallyCenter();
        this.layout.addChild(new StringWidget(this.getTitle(), this.font));

        // 1. Status header: is Meshelium rendering, right now? (tick()
        // keeps the section count live; tooltip defines chunk sections.)
        this.statusLine = this.layout.addChild(
                new StringWidget(buildStatusLine(), this.font), s -> s.paddingTop(2));
        this.statusLine.setTooltip(Tooltip.create(
                Component.translatable("meshelium.options.tooltip.status")));

        // Terrain memory on its OWN line under the status, not appended to
        // it (owner's request). The status answers "is it running"; the
        // memory answers "what is it costing", and jamming both into one
        // line made the row long enough to crowd the screen.
        this.memoryLine = this.layout.addChild(
                new StringWidget(memoryStatus(), this.font), s -> s.paddingTop(1));

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
        if (terrainOverridden || maxRdOverridden || occlusionOverridden) {
            this.layout.addChild(new MultiLineTextWidget(
                    Component.translatable("meshelium.options.dev_override"), this.font)
                    .setMaxWidth(BANNER_MAX_WIDTH)
                    .setCentered(true));
        }

        // 1a. THE MASTER SWITCH, first because it governs every row below
        // it: with this off Meshelium draws nothing and the rest of the
        // screen is describing a renderer that is not running. Worded
        // Enabled/Disabled rather than ON/OFF because "Terrain Rendering:
        // OFF" reads like a rendering feature being disabled rather than
        // the whole mod being switched off, which is what it actually does.
        CycleButton<Boolean> terrain = CycleButton
                .booleanBuilder(Component.translatable("meshelium.options.enabled"),
                        Component.translatable("meshelium.options.disabled"),
                        config.enableTerrainRendering)
                .create(Component.translatable("meshelium.options.terrain"), (b, value) -> {
                    config.enableTerrainRendering = value;
                    config.save();
                    com.deds.meshelium.MesheliumExtendedRd.onConfigChanged(this.minecraft);
                    // Both edges of this switch are handled by the client
                    // tick's edge detector, which also catches the harness
                    // flipping the property. One owner for the logic.
                });
        terrain.setWidth(WIDGET_WIDTH);
        terrain.active = !this.gateLocked && !terrainOverridden;
        terrain.setTooltip(tip("meshelium.options.tooltip.terrain", "meshelium.options.applies.now"));
        this.layout.addChild(terrain, s -> s.paddingBottom(4));

        // 2. The setting players actually touch. (The retention toggle
        // and its time limit used to follow here; retired 2026-08-11,
        // Bobby owns that job now. Class javadoc has the argument.)
        this.capSlider = new CapSlider(config, !this.gateLocked && !maxRdOverridden);
        this.capSlider.setTooltip(tip("meshelium.options.tooltip.max_rd",
                "meshelium.options.applies.now"));
        this.capBox = new ValueBox(MesheliumConfig.MIN_MAX_RENDER_DISTANCE,
                MesheliumConfig.MAX_MAX_RENDER_DISTANCE,
                () -> MesheliumConfig.get().maxRenderDistance,
                this::applyCap,
                Component.translatable("meshelium.options.max_rd.label",
                        Component.literal("")));
        this.capBox.active = !this.gateLocked && !maxRdOverridden;
        this.capBox.setTooltip(tip("meshelium.options.tooltip.max_rd_custom",
                "meshelium.options.applies.now"));
        this.layout.addChild(sliderRow(this.capSlider, this.capBox), s -> s.paddingTop(4));

        // 3. Occlusion culling — BACK AT 1.1, as Auto/On/Off.
        //
        // It was hidden at 1.0.0 because the two box rasters cost ~3.1 ms
        // per frame while the drawing they saved cost ~0.1 ms. That cost is
        // fixed: every fragment of a box wrote the SAME word with an
        // atomic, and same-address atomics serialise, so a near box cost a
        // million serialised read-modify-writes rather than a million cheap
        // shaded pixels. Read-guarding it took ground-rd32 from 287 to 1553
        // fps (shaders/occlusion/box.frag).
        //
        // The row is a THREE-WAY and not a toggle because there is no
        // global right answer. Same-session at 1920x1080 on an RX 9070 XT,
        // occlusion is 11-15% SLOWER than the BFS feed at render distance
        // 32 and 19-31% FASTER at 64, and it is smoother at distance too
        // (worst frame while spinning 69 ms against 228 ms). 1.0.0 shipped
        // a plain boolean defaulting ON, which made the common case slower;
        // a plain boolean defaulting OFF hides a large win from exactly the
        // players this mod is for. Auto keys on RENDER DISTANCE because
        // that is what separated the measurements cleanly (8/16/24/32 all
        // lose, 48 and 64 both win) where a section count does not:
        // ground-rd64 wins 31% at ~4,000 resident sections while
        // plains-rd32 loses at ~3,300.
        CycleButton<MesheliumConfig.OcclusionMode> occlusion = CycleButton
                .builder((MesheliumConfig.OcclusionMode m) -> Component.translatable(
                        switch (m) {
                            case AUTO -> "meshelium.options.occlusion.auto";
                            case ON -> "meshelium.options.occlusion.on";
                            case OFF -> "meshelium.options.occlusion.off";
                        }), config.occlusionMode)
                .withValues(MesheliumConfig.OcclusionMode.values())
                .create(Component.translatable("meshelium.options.occlusion"), (b, value) -> {
                    config.occlusionMode = value;
                    config.save();
                    rebuildOcclusionRows();
                });
        occlusion.setWidth(WIDGET_WIDTH);
        occlusion.active = !this.gateLocked && !occlusionOverridden;
        occlusion.setTooltip(tip("meshelium.options.tooltip.occlusion",
                "meshelium.options.applies.now"));
        this.layout.addChild(occlusion);

        // The Auto crossover. Only meaningful in Auto, so it greys out in
        // On/Off rather than vanishing: a row that disappears makes the
        // screen jump under the cursor, and its presence is the discoverable
        // hint that Auto is tunable at all. The default (48) was fitted to
        // OPEN terrain, occlusion's worst case, so a player whose world is
        // caves or mountains should be able to pull it down.
        boolean autoActive = !this.gateLocked && !occlusionOverridden
                && config.occlusionMode == MesheliumConfig.OcclusionMode.AUTO;
        this.occlusionSlider = new OcclusionRdSlider(config, autoActive);
        this.occlusionSlider.setTooltip(tip("meshelium.options.tooltip.occlusion_rd",
                "meshelium.options.applies.now"));
        this.occlusionBox = new ValueBox(MesheliumConfig.MIN_OCCLUSION_AUTO_RD,
                MesheliumConfig.MAX_OCCLUSION_AUTO_RD,
                () -> MesheliumConfig.get().occlusionAutoMinRenderDistance,
                this::applyOcclusionRd,
                Component.translatable("meshelium.options.occlusion_rd.label",
                        Component.literal("")));
        this.occlusionBox.active = autoActive;
        this.occlusionBox.setTooltip(tip("meshelium.options.tooltip.occlusion_rd_custom",
                "meshelium.options.applies.now"));
        this.layout.addChild(sliderRow(this.occlusionSlider, this.occlusionBox));

        // 3b. Distance fog.
        //
        // A rendering setting rather than an advanced one, because it is the
        // only row here that changes what the world LOOKS like, and because
        // at the distances this mod unlocks the vanilla behaviour is simply
        // wrong: the atmospheric haze ends at a hard-coded 1024 blocks that
        // ignores render distance, so at 120 chunks the outer 56 chunks are
        // loaded, meshed and then painted flat grey. See
        // AtmosphericFogEnvironmentMixin for the bytecode.
        //
        // Active on every backend, unlike the rows above it. The fog mixin
        // has nothing to do with mesh shaders, so it keeps working with
        // Meshelium switched off or on OpenGL, and greying it out on a
        // gate-locked screen would be a lie.
        CycleButton<MesheliumConfig.FogMode> fog = CycleButton
                .builder((MesheliumConfig.FogMode m) -> Component.translatable(
                        switch (m) {
                            case VANILLA -> "meshelium.options.fog.vanilla";
                            case SCALED -> "meshelium.options.fog.scaled";
                            case OFF -> "meshelium.options.fog.off";
                        }), config.fogMode)
                .withValues(MesheliumConfig.FogMode.values())
                .create(Component.translatable("meshelium.options.fog"), (b, value) -> {
                    config.fogMode = value;
                    config.save();
                    // The slider only means anything in Scaled, and it greys
                    // out rather than vanishing, so the row has to be rebuilt
                    // to re-evaluate that. Same trick the occlusion mode row
                    // uses for its crossover slider.
                    rebuildOcclusionRows();
                });
        fog.setWidth(WIDGET_WIDTH);
        fog.active = !fogOverridden;
        fog.setTooltip(tipAlways("meshelium.options.tooltip.fog", "meshelium.options.applies.now"));
        this.layout.addChild(fog, s -> s.paddingTop(4));

        boolean fogSliderActive = !fogOverridden
                && config.fogMode == MesheliumConfig.FogMode.SCALED;
        this.fogSlider = new FogEndSlider(config, fogSliderActive);
        this.fogSlider.setTooltip(tipAlways("meshelium.options.tooltip.fog_end",
                "meshelium.options.applies.now"));
        this.fogBox = new ValueBox(MesheliumConfig.MIN_FOG_END_PERCENT,
                MesheliumConfig.MAX_FOG_END_PERCENT,
                () -> MesheliumConfig.get().fogEndPercent,
                this::applyFogEnd,
                Component.translatable("meshelium.options.fog_end.box",
                        Component.literal("")));
        this.fogBox.active = fogSliderActive;
        this.fogBox.setTooltip(tipAlways("meshelium.options.tooltip.fog_end_custom",
                "meshelium.options.applies.now"));
        this.layout.addChild(sliderRow(this.fogSlider, this.fogBox));

        // 4. Everything else lives one click away.
        //
        // These rows all default to the right answer and the only reason to
        // touch any of them is a mod conflict or a bug report. Keeping them
        // on the front page put a memory setting worth gigabytes next to a
        // logging switch, invited fiddling with both, and pushed the flat
        // list past the height budget this class's javadoc records.
        //
        // The live instance is handed over as the parent, never a fresh one:
        // returning to a cached Screen only repositions it, so this screen
        // keeps its widgets, its tick loop and its capAtOpen snapshot, and
        // the wave-15 back-out fix keeps working.
        this.layout.addChild(Button.builder(
                Component.translatable("meshelium.options.advanced"),
                b -> {
                    // Disarm the two-click reset on the way out, or it would
                    // still be armed and relabelled when the player comes
                    // back and one stray click would wipe their settings.
                    this.resetArmed = false;
                    if (this.resetButton != null) {
                        this.resetButton.setMessage(
                                Component.translatable("meshelium.options.reset"));
                    }
                    if (this.minecraft != null) {
                        this.minecraft.gui.setScreen(new MesheliumAdvancedScreen(this));
                    }
                })
                .width(WIDGET_WIDTH).build(), s -> s.paddingTop(6));

        // Reset. TWO CLICKS on purpose: the first arms and relabels, the
        // second does it. A single click would be one slip away from wiping
        // a hand-tuned render-distance cap and Auto crossover, and those are
        // exactly the values a player spends time getting right. A modal
        // would be heavier than the action deserves; relabelling the button
        // itself keeps the confirmation where the cursor already is.
        this.resetButton = Button.builder(Component.translatable("meshelium.options.reset"),
                b -> {
                    if (!this.resetArmed) {
                        this.resetArmed = true;
                        b.setMessage(Component.translatable("meshelium.options.reset.confirm")
                                .withStyle(ChatFormatting.RED));
                        return;
                    }
                    MesheliumConfig config2 = MesheliumConfig.get();
                    config2.resetToDefaults();
                    // The cap feeds the vanilla slider's range, so the same
                    // live-apply path the cap row uses has to run here too.
                    com.deds.meshelium.MesheliumExtendedRd.onConfigChanged(this.minecraft);
                    rebuildOcclusionRows();
                })
                .width(WIDGET_WIDTH).build();
        this.resetButton.setTooltip(tip("meshelium.options.tooltip.reset",
                "meshelium.options.applies.now"));
        // Never locked: resetting is how a player recovers from a bad value
        // even on a gate-locked screen, and it cannot make the gate worse.
        this.layout.addChild(this.resetButton, s -> s.paddingTop(6));

        this.layout.addChild(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
                .width(WIDGET_WIDTH).build(), s -> s.paddingTop(4));

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    /** One slider row: the slider plus its inline value box. */
    private static LinearLayout sliderRow(AbstractSliderButton slider, AbstractWidget box) {
        LinearLayout row = LinearLayout.horizontal().spacing(4);
        row.defaultCellSetting().alignVerticallyMiddle();
        row.addChild(slider);
        row.addChild(box);
        return row;
    }

    /**
     * An inline number box beside a slider: type an exact value, including
     * one the slider's lattice cannot reach.
     *
     * <p>Replaces the [Custom] button that opened a whole separate screen
     * (owner, 2026-08-12). A sub-screen for one integer is a lot of
     * ceremony, and worse, it hides the value you are tuning behind a
     * screen transition so you cannot see the slider move with it.</p>
     *
     * <p><b>Commits on Enter or on losing focus, never per keystroke.</b>
     * Per-keystroke would be actively harmful here: both write paths save
     * the config to disk and the cap one re-applies the vanilla slider
     * range, so typing "100" would fire for "1", "10" and "100" and the
     * first two are values the player never asked for. Out-of-range or
     * unparseable text turns the digits red while typing and reverts on
     * commit rather than clamping, because silently rewriting what someone
     * typed is worse than visibly refusing it.</p>
     */
    /**
     * Box text colours, ARGB with the alpha byte SET.
     *
     * <p>{@code EditBox.setTextColor} takes ARGB, not RGB: vanilla's own
     * default is {@code -2039584}, which is {@code 0xFFE0E0E0}. Passing a
     * bare {@code 0xE0E0E0} means alpha 0, so the text renders fully
     * transparent and the box looks like it went blank the instant you
     * typed into it. That shipped in the first inline-box build and is
     * exactly the bug the owner hit.</p>
     */
    private static final int TEXT_OK = 0xFFE0E0E0;
    private static final int TEXT_BAD = 0xFFFF5555;

    private final class ValueBox extends EditBox {
        private final int min;
        private final int max;
        private final IntSupplier current;
        private final IntConsumer onCommit;
        /** Mirror of the colour handed to setTextColor; EditBox has no getter. */
        private int lastTextColor = TEXT_OK;

        ValueBox(int min, int max, IntSupplier current, IntConsumer onCommit, Component narration) {
            super(MesheliumOptionsScreen.this.font, CUSTOM_WIDTH, 20, narration);
            this.min = min;
            this.max = max;
            this.current = current;
            this.onCommit = onCommit;
            setMaxLength(3);
            setValue(Integer.toString(current.getAsInt()));
            setResponder(text -> paint(parsed() != null ? TEXT_OK : TEXT_BAD));
        }

        /** The one place a text colour is set, so the harness can check it. */
        private void paint(int argb) {
            this.lastTextColor = argb;
            setTextColor(argb);
        }

        /** Harness probe: the alpha byte of the colour last applied. */
        int testTextAlpha() {
            return (this.lastTextColor >>> 24) & 0xFF;
        }

        private Integer parsed() {
            try {
                int v = Integer.parseInt(getValue().trim());
                return v >= this.min && v <= this.max ? v : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** Follow the slider, unless the player is mid-edit in this box. */
        void refreshFromConfig() {
            if (!isFocused()) {
                setValue(Integer.toString(this.current.getAsInt()));
            }
        }

        /** Harness hook for the commit the player gets from Enter or blur. */
        void testCommit() {
            commit();
        }

        private void commit() {
            Integer v = parsed();
            if (v != null) {
                this.onCommit.accept(v);
            } else {
                setValue(Integer.toString(this.current.getAsInt()));
            }
            paint(TEXT_OK);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (isFocused()
                    && (event.key() == InputConstants.KEY_RETURN
                            || event.key() == InputConstants.KEY_NUMPADENTER)) {
                commit();
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public void setFocused(boolean focused) {
            boolean was = isFocused();
            super.setFocused(focused);
            if (was && !focused) {
                commit();
            }
        }
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

    /**
     * A tooltip that keeps its apply semantics even on a gate-locked screen.
     *
     * <p>{@link #tip} replaces the semantics line with the gate reason,
     * which is the honest thing to do for a row that genuinely cannot take
     * effect without Vulkan and mesh shaders. The fog rows are not such a
     * row: they are a plain vanilla-side change that works on OpenGL, with
     * Meshelium switched off, on any hardware. Telling a player it needs
     * Vulkan would be the lie the gate rule exists to prevent.</p>
     */
    private Tooltip tipAlways(String descriptionKey, String appliesKey) {
        return Tooltip.create(withSemantics(Component.translatable(descriptionKey), appliesKey));
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
        if (this.capBox != null) {
            this.capBox.refreshFromConfig();
        }
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

    // ------------------------------------------------------------------
    // The occlusion Auto-crossover row (1.1: three-way + slider + custom)
    // ------------------------------------------------------------------

    /**
     * Re-open the screen so the Auto crossover row's enabled state follows
     * the mode.
     *
     * <p>Constructing a FRESH screen rather than calling
     * {@code rebuildWidgets()} is the wave-15 lesson, paid for on the real
     * client: {@code Screen.init(II)} builds widgets ONCE and the
     * initialized flag is never cleared, and rebuilding in place on an
     * accumulating layout leaves the stale row shadowing the fresh one. A
     * new instance carrying the same parent is vanilla's own idiom.</p>
     */
    private void rebuildOcclusionRows() {
        this.minecraft.gui.setScreen(new MesheliumOptionsScreen(this.parent));
    }

    /** The one write path for the Auto crossover — slider and box share it. */
    private void applyOcclusionRd(int rd) {
        MesheliumConfig config = MesheliumConfig.get();
        config.occlusionAutoMinRenderDistance = rd;
        config.save();
        if (this.occlusionSlider != null) {
            this.occlusionSlider.refreshFromConfig();
        }
        if (this.occlusionBox != null) {
            this.occlusionBox.refreshFromConfig();
        }
    }

    /**
     * The render distance at or above which Auto arms occlusion culling.
     *
     * <p>Continuous over {@link MesheliumConfig#MIN_OCCLUSION_AUTO_RD} to
     * {@link MesheliumConfig#MAX_OCCLUSION_AUTO_RD} rather than snapped to
     * the cap row's 8-lattice, because the crossover is a measured
     * boundary a player is tuning by feel, not a chunk count that has to
     * land on a legal option value.</p>
     */
    private final class OcclusionRdSlider extends AbstractSliderButton {
        private int displayed;

        OcclusionRdSlider(MesheliumConfig config, boolean active) {
            super(0, 0, SLIDER_WIDTH, 20, Component.empty(),
                    rdFraction(config.occlusionAutoMinRenderDistance));
            this.active = active;
            this.displayed = config.occlusionAutoMinRenderDistance;
            updateMessage();
        }

        void refreshFromConfig() {
            this.displayed = MesheliumConfig.get().occlusionAutoMinRenderDistance;
            this.value = rdFraction(this.displayed);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("meshelium.options.occlusion_rd.label",
                    Component.literal(Integer.toString(this.displayed))));
        }

        @Override
        protected void applyValue() {
            int span = MesheliumConfig.MAX_OCCLUSION_AUTO_RD - MesheliumConfig.MIN_OCCLUSION_AUTO_RD;
            int rd = MesheliumConfig.MIN_OCCLUSION_AUTO_RD + (int) Math.round(this.value * span);
            if (rd != this.displayed) {
                this.displayed = rd;
                applyOcclusionRd(rd);
            }
            updateMessage();
        }
    }

    /**
     * Percentage of the view distance at which the haze finishes.
     *
     * <p>Labelled with the resulting distance in blocks as well as the
     * percentage, because "120%" means nothing on its own and "120% of view
     * (2304 blocks)" tells the player what they are actually choosing. That
     * also answers wanting the control in blocks rather than percent without
     * adding a second unit and a second knob: percent is the unit that keeps
     * looking right when the render distance changes, blocks is the number
     * that makes it concrete, so the slider is one and the label is both.</p>
     */
    private final class FogEndSlider extends AbstractSliderButton {
        private int displayed;

        FogEndSlider(MesheliumConfig config, boolean active) {
            super(0, 0, SLIDER_WIDTH, 20, Component.empty(),
                    fogFraction(config.fogEndPercent));
            this.active = active;
            this.displayed = config.fogEndPercent;
            updateMessage();
        }

        void refreshFromConfig() {
            this.displayed = MesheliumConfig.get().fogEndPercent;
            this.value = fogFraction(this.displayed);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("meshelium.options.fog_end.label",
                    Component.literal(Integer.toString(this.displayed)),
                    Component.literal(Integer.toString(fogBlocksFor(this.displayed)))));
        }

        @Override
        protected void applyValue() {
            int span = MesheliumConfig.MAX_FOG_END_PERCENT - MesheliumConfig.MIN_FOG_END_PERCENT;
            int pct = MesheliumConfig.MIN_FOG_END_PERCENT + (int) Math.round(this.value * span);
            pct = Math.round(pct / 5.0f) * 5; // 5% steps; 1% is not a visible difference
            pct = Math.max(MesheliumConfig.MIN_FOG_END_PERCENT,
                    Math.min(MesheliumConfig.MAX_FOG_END_PERCENT, pct));
            if (pct != this.displayed) {
                this.displayed = pct;
                applyFogEnd(pct);
            }
            updateMessage();
        }
    }

    private static double fogFraction(int pct) {
        int span = MesheliumConfig.MAX_FOG_END_PERCENT - MesheliumConfig.MIN_FOG_END_PERCENT;
        double f = (pct - MesheliumConfig.MIN_FOG_END_PERCENT) / (double) span;
        return Math.max(0.0, Math.min(1.0, f));
    }

    /**
     * The percentage expressed in blocks, against the distance the player is
     * actually on right now. Falls back to the mod's cap when there is no
     * world yet, so the number in the label is never blank or zero.
     */
    private int fogBlocksFor(int pct) {
        int chunks = MesheliumConfig.get().maxRenderDistance;
        if (this.minecraft != null && this.minecraft.options != null
                && this.minecraft.level != null) {
            chunks = this.minecraft.options.getEffectiveRenderDistance();
        }
        return Math.round(chunks * 16 * (pct / 100.0f));
    }

    /** The one write path for the fog percentage. */
    private void applyFogEnd(int pct) {
        MesheliumConfig config = MesheliumConfig.get();
        config.fogEndPercent = pct;
        config.save();
        if (this.fogSlider != null) {
            this.fogSlider.refreshFromConfig();
        }
        if (this.fogBox != null) {
            this.fogBox.refreshFromConfig();
        }
    }

    private static double rdFraction(int rd) {
        int span = MesheliumConfig.MAX_OCCLUSION_AUTO_RD - MesheliumConfig.MIN_OCCLUSION_AUTO_RD;
        double f = (rd - MesheliumConfig.MIN_OCCLUSION_AUTO_RD) / (double) span;
        return Math.max(0.0, Math.min(1.0, f));
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
        // The render distance itself, not just the cap. Toggling Meshelium
        // Rendering moves the value and the option's range without touching
        // the cap, and a cached Video Settings screen keeps the slider widget
        // it built from the old range.
        boolean rdChanged = this.rdAtOpen >= 0 && this.minecraft.options != null
                && this.minecraft.options.renderDistance().get() != this.rdAtOpen;
        Screen target = this.parent;
        if ((capChanged || rdChanged)
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
