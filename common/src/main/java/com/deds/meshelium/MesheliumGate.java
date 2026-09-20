/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium;

import com.deds.meshelium.gui.MesheliumPopupScreen;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

/**
 * The wave-1 backend gate: decides, exactly once per session, which of the
 * three worlds Meshelium woke up in —
 *
 * <ol>
 * <li><b>OPENGL</b> — the GL backend is active. Meshelium stays completely
 *     dormant beyond the one-time popup (owner directive, SPEC "graceful
 *     OpenGL fallback"). This is the common case: 26.2's DEFAULT tries
 *     OpenGL first, so the popup is the mod's front door (seam doc Q1).</li>
 * <li><b>VULKAN_NO_MESH_SHADERS</b> — Vulkan is active but the device has
 *     no usable {@code VK_EXT_mesh_shader}. Dormant, one honest notice.</li>
 * <li><b>VULKAN_MESH_SHADERS</b> — Vulkan is active and the device-creation
 *     mixin successfully requested the extension + features. Later waves
 *     key off this state.</li>
 * </ol>
 *
 * <p><b>Why the decision waits for the title screen.</b> The client
 * entrypoint cannot decide: fabric-loader injects the entrypoint invocation
 * into {@code Minecraft.<init>} immediately before the
 * {@code Thread.currentThread()} call (bytecode offset 563 in the 26.2
 * constructor, EntrypointPatch's 1.19.4+ rule), while the GPU device is
 * created at offset ~1130 ({@code GpuBackend.createDevice}) — even
 * {@code Options} (offset 579) doesn't exist yet. So the entrypoint only
 * registers a tick hook; the decision runs on the first client tick where
 * the loading overlay is gone and {@code RenderSystem.tryGetDevice()}
 * returns a device — at that point the backend is final for the whole
 * session (backends swap only at boot).</p>
 *
 * <p><b>The decision deliberately does NOT wait for the title screen, and
 * used to.</b> The title screen was only ever a proxy for "boot has
 * finished and the device exists", and it is a proxy that fails: a player
 * who launches with quick-play, or uses a launcher's resume-last-world,
 * never sees a title screen, so the gate never decided and Meshelium
 * silently did nothing all session on hardware that fully supports it.
 * Reproduced on 26.2 with {@code --quickPlaySingleplayer}: the device was
 * created, mesh shaders were requested, and no "Backend gate:" line was
 * ever logged. The real precondition is the device, so that is what is
 * tested now.
 *
 * <p><b>The POPUP no longer waits for a title screen either, and used to
 * for the same reason the decision did.</b> "Is a TitleScreen up?" was a
 * proxy for "is it decent to put a modal in front of this player", and a
 * player who launches with quick-play or resume-last-world never satisfies
 * it: they got the decision and then nothing at all, all session, which is
 * the silent-nothing-happens the standing directive forbids. The question
 * asked now is the real one - a title screen, OR the HUD of a live world
 * with no overlay, no screen, an undamaged living player, vanilla's own
 * {@code Gui.canInterruptScreen()} true, held steady for one second
 * ({@link #IN_WORLD_SETTLE_TICKS}).</p>
 *
 * <p>The standing owner directive, 2026-08-09, in the owner's words: if the
 * game is running the OpenGL backend, Meshelium must disable itself
 * completely AND show a one-time popup explaining how to switch to Vulkan,
 * "with a button that applies the switch for the player" - and "No crash, no
 * nag loop, no silent nothing-happens." The title-screen precondition broke
 * the third of those for every player who skips the main menu, which is what
 * the 2026-09-16 rework is for.
 *
 * <p>Shown in a private singleplayer world the popup pauses it, exactly as
 * every vanilla menu does. <b>In multiplayer, or a world published to LAN,
 * {@code Minecraft.runTick}'s pause test cannot be satisfied (ip 533-566:
 * {@code pause = hasSingleplayerServer() && gui.isPausing() &&
 * !isPublished()}), so no modal is shown there at all:</b> the player gets
 * a toast and a line in chat, because a notice nobody asked for must not
 * take the mouse in a world that keeps ticking - a quick-play server join
 * is exactly a player who may be falling, mid-fight or in a spawn PvP
 * zone. For VULKAN_FAILED and NO_MESH_SHADERS that telling is COMPLETE:
 * their popup is an [OK] over one paragraph, and the toast plus the chat
 * line carry the same paragraph, so the once-only latch is spent there.
 * For ENABLE_VULKAN it is not, because that popup carries the [Enable
 * Vulkan] BUTTON and a toast cannot; so that one player is told twice by
 * design, and the second telling - the window with the button - waits for
 * the next title screen.</p>
 *
 * <p><b>The popup is about the backend, so Sodium does not silence it.</b>
 * With Sodium installed the state becomes {@link State#SODIUM_PRESENT},
 * but the backend answer is kept beside it ({@link #backend()}) and the
 * popup runs on that: a Sodium user on OpenGL is exactly who the
 * directive is for, because the adapter that draws Sodium's chunks needs
 * Vulkan with {@code VK_EXT_mesh_shader} too. Every 1.6.0-beta.4..8 jar
 * returned from the Sodium branch before the popup and told that player
 * nothing (owner report 2026-09-08 (beta.8)).</p>
 *
 * <p>The Vulkan-vs-GL call is made from public API only:
 * {@code RenderSystem.getDevice().getDeviceInfo().backendName()}, which the
 * 26.2 jar hardcodes to {@code "Vulkan"}/{@code "OpenGL"} per backend. The
 * mesh-shader half comes from {@link MesheliumVulkanState}, written by the
 * device-creation mixin — and is only trusted when the active backend
 * really is Vulkan, which also covers the corner where a Vulkan attempt got
 * as far as our mixin and then failed, falling back to GL.</p>
 */
public final class MesheliumGate {

    public enum State {
        /** Boot still in progress; not decided yet. */
        UNKNOWN,
        /** OpenGL backend active: Meshelium fully dormant. */
        OPENGL,
        /** Vulkan active, no usable mesh shaders: Meshelium fully dormant. */
        VULKAN_NO_MESH_SHADERS,
        /** Vulkan active with VK_EXT_mesh_shader enabled: Meshelium may run. */
        VULKAN_MESH_SHADERS,
        /**
         * Another mod owns terrain rendering, so Meshelium stands aside.
         *
         * <p>Today that mod is Sodium, and standing aside is the whole
         * behaviour. It is not politeness: Meshelium replaces the terrain
         * draw by cancelling {@code ChunkSectionsToRender.renderGroup} at
         * HEAD, and Sodium cancels the same method the same way. Mixin
         * returns from a target as soon as any HEAD callback cancels, so
         * whichever injector happens to be applied first wins and the
         * other never draws — silently, and differently depending on mod
         * load order.
         *
         * <p>It is worse than a coin flip, because Sodium also replaces
         * vanilla's chunk build path, and Meshelium's geometry comes from
         * tapping that path. So even when Meshelium wins the cancel it has
         * no sections to draw, and the result is a world with no terrain
         * at all — entities and particles still render, because they never
         * go through that method. That is precisely what was reported.
         *
         * <p>This state will become the trigger for the Sodium render path
         * rather than a reason to stop. Until that exists, stopping is the
         * honest behaviour: Sodium alone draws a correct world, and two
         * mods fighting over one method does not.
         *
         * <p>It overrides the BACKEND answer in {@link #state()} only. The
         * backend answer itself is kept in {@link #backend()}, the popup
         * still fires on it, and {@link #sodiumAdapterArmed()} is the
         * question "is Meshelium drawing Sodium's chunks", which this
         * state alone cannot answer (owner report 2026-09-08 (beta.8)).
         */
        SODIUM_PRESENT
    }

    /**
     * Sodium's mod id, identical on Fabric and NeoForge.
     *
     * <p>Duplicated as a literal in {@code MesheliumSodiumMixinPlugin},
     * which cannot import this class: mixin plugins run during class
     * loading, before Minecraft's own classes are safe to touch.
     */
    public static final String SODIUM_MOD_ID = "sodium";

    /**
     * Set to {@code false} to keep Meshelium out of Sodium's renderer.
     * Duplicated as a literal in {@code MesheliumSodiumMixinPlugin} for
     * the same reason as {@link #SODIUM_MOD_ID}.
     */
    public static final String SODIUM_ADAPTER_PROPERTY = "meshelium.sodium.adapter";

    /**
     * Whether the adapter is in this jar and not declined on the command
     * line: the half of {@link #sodiumAdapterArmed()} that is known before
     * the gate decides, so a popup or a banner can already say whether
     * switching backends would turn the adapter on.
     *
     * <p>The loader half was added when the NeoForge jar had no
     * {@code sodium/} source set: until it was asked, the gate log and
     * the options screen on NeoForge said "Working with Sodium" off the
     * Vulkan device alone while nothing drew (owner report 2026-09-08
     * (beta.8)). Since 2026-09-14 both loaders' builds carry the adapter
     * and both answer yes; the question stays for any loader whose build
     * omits the source set.
     */
    public static boolean sodiumAdapterConfigured() {
        return MesheliumPlatform.sodiumAdapterAvailable()
                && !"false".equalsIgnoreCase(System.getProperty(SODIUM_ADAPTER_PROPERTY));
    }

    /**
     * Whether Meshelium intends to draw Sodium's terrain with mesh
     * shaders — the conditions the adapter needs, all of which are known
     * before any world loads: Sodium installed, the backend probe's own
     * answer VULKAN_MESH_SHADERS, and {@link #sodiumAdapterConfigured()}.
     *
     * <p>Keyed on {@link #backend()} rather than on the raw
     * {@code MesheliumVulkanState.meshShadersRequested()} flag on purpose.
     * The flag is stamped by the device-creation mixin at the end of its
     * probe, and a VULKAN-selected boot can still throw inside
     * {@code createDevice} after that stamp and fall back to OpenGL; the
     * flag then says "requested" on a GL session. The gate's backend
     * answer is read off the device that actually exists, so it cannot
     * (owner report 2026-09-08 (beta.8)).
     *
     * <p>Distinct from {@link State#SODIUM_PRESENT}, which is about the
     * VANILLA path and correctly says "stand down" either way: under
     * Sodium, Meshelium's chunk tap, its level-renderer takeover and its
     * own arena are all dormant because Sodium owns the geometry. Those
     * two facts are both true at once, and conflating them is what made
     * the options screen say Meshelium was off while it was drawing the
     * world.
     *
     * <p>Intent, not confirmation. It says the adapter is configured to
     * run, not that it has drawn a frame — nothing has, from a title
     * screen. {@code SodiumTerrainDrawer.framesDrawn()} is the fact.
     */
    public static boolean sodiumAdapterArmed() {
        return state == State.SODIUM_PRESENT
                && backendDecided == State.VULKAN_MESH_SHADERS
                && sodiumAdapterConfigured();
    }

    private static volatile State state = State.UNKNOWN;

    /**
     * The backend probe's own answer — OPENGL, VULKAN_NO_MESH_SHADERS or
     * VULKAN_MESH_SHADERS — kept even when Sodium overrides {@link #state}
     * to SODIUM_PRESENT.
     *
     * <p>The popup and the options-screen banner are about the BACKEND,
     * and Sodium being installed does not change which backend is
     * running. Folding the two answers into one state is what silenced
     * the OpenGL popup under Sodium and had the settings screen tell a
     * Sodium-on-Vulkan player it was "still checking the graphics
     * backend" (owner report 2026-09-08 (beta.8)).
     */
    private static volatile State backendDecided = State.UNKNOWN;

    /** The backend half of the decision; see {@link #backendDecided}. */
    public static State backend() {
        return backendDecided;
    }

    private MesheliumGate() {
    }

    public static State state() {
        return state;
    }

    /** Called once from the client entrypoint. */
    public static void init() {
        MesheliumPlatform.onEndClientTick(MesheliumGate::onEndTick);
    }

    /**
     * A popup MAY still be owed. Set on the decision tick for the two
     * states that have something to say, and cleared by the one attempt
     * that puts it in front of the player.
     *
     * <p>It is not "a popup will appear": {@link #showPopupIfNeeded}
     * still consults {@code showVulkanPrompt} and the two once-only
     * latches, and one attempt spends the flag whatever it decides. That
     * is the no-nag-loop half of the standing directive.
     */
    private static boolean popupOwed;

    /**
     * Consecutive client ticks the in-world conditions have held. Reset to
     * zero the moment any of them stops holding.
     */
    private static int inWorldSettleTicks;

    /**
     * The no-screen telling has been given this session.
     *
     * <p>Separate from {@link #popupOwed} on purpose. In a world nothing
     * can pause, the toast and the chat line are spent here; whether the
     * MODAL is still owed afterwards depends on the variant and is
     * {@link #showPopupIfNeeded}'s answer, not this flag's.
     */
    private static boolean inWorldNoticeShown;

    /**
     * The toast id for that telling. The house pattern is
     * {@code MesheliumExtendedRd.java:160-161}: a plain {@code new
     * SystemToast.SystemToastId()}, which is a public no-arg constructor
     * on 26.2 (javap).
     */
    private static final SystemToast.SystemToastId POPUP_TOAST_ID =
            new SystemToast.SystemToastId();

    /**
     * One second at the HUD before an owed popup lands there.
     *
     * <p>A judgement rather than a measurement. It has to be long enough
     * that the popup does not arrive on top of the chunks still popping in
     * around the player, and short enough that it is still obviously a
     * consequence of the world loading. Public because the harness waits
     * on it rather than on a number of its own that could drift.
     */
    public static final int IN_WORLD_SETTLE_TICKS = 20;

    /** How the notice is being put in front of the player. */
    private enum Presentation {
        /** Over a title screen: the original path. */
        TITLE,
        /** Over the HUD of a world a modal really pauses, plus a chat line. */
        IN_WORLD,
        /** A world nothing can pause: a toast and a chat line, no modal. */
        NO_SCREEN
    }

    private static void onEndTick(Minecraft minecraft) {
        if (state != State.UNKNOWN) {
            // Decided already. The POPUP may still be owed - see popupOwed -
            // and it waits for a decent moment even though the decision did
            // not.
            tryShowOwedPopup(minecraft);
            return;
        }
        if (minecraft.gui.overlay() != null) {
            return; // still loading; the device may not exist yet
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return;
        }

        DeviceInfo info = device.getDeviceInfo();
        boolean vulkan = "Vulkan".equals(info.backendName());
        State decided;
        if (vulkan) {
            decided = MesheliumVulkanState.meshShadersRequested()
                    ? State.VULKAN_MESH_SHADERS
                    : State.VULKAN_NO_MESH_SHADERS;
        } else {
            decided = State.OPENGL;
        }
        // The backend answer is kept whatever happens next: the popup and
        // the options-screen banner are about the backend, and Sodium being
        // installed does not change which backend is running. Written
        // BEFORE state, because sodiumAdapterArmed() below reads it.
        backendDecided = decided;

        // Another terrain renderer outranks every backend answer above:
        // whatever the hardware can do, two mods cancelling the same draw
        // method is a race one of them loses invisibly. Decided AFTER the
        // backend probe rather than before it, so the device and driver
        // still reach the log - a bug report from a Sodium user is worth
        // as much hardware detail as any other.
        if (MesheliumPlatform.isModLoaded(SODIUM_MOD_ID)) {
            state = State.SODIUM_PRESENT;
            // WHICH Sodium, on every one of the three lines below. The fact
            // lived only in gradle.properties as a Modrinth coordinate, so
            // no bug report could say whether the Sodium in the game was the
            // Sodium this adapter was compiled against - and the adapter
            // mixes into Sodium internals that are explicitly not a stable
            // API (gradle.properties:25-29). Null when the loader will not
            // say, which is a different answer from "a different version"
            // and is logged as such.
            String installedSodium = MesheliumPlatform.modVersion(SODIUM_MOD_ID).orElse(null);
            String sodiumForLog = installedSodium != null ? installedSodium : "not reported";
            if (sodiumAdapterArmed()) {
                MesheliumLog.LOGGER.info(
                        "Backend gate: SODIUM_PRESENT — Sodium is installed, so Meshelium's own "
                                + "chunk tap and terrain draw stand down and Sodium builds the "
                                + "chunks. Meshelium then draws Sodium's opaque terrain with mesh "
                                + "shaders, reading it out of Sodium's own buffers. "
                                + "-D{}=false turns that off and leaves Sodium to draw it all. "
                                + "(backend={}, device='{}', driver={}, sodium={}, built "
                                + "against {})",
                        SODIUM_ADAPTER_PROPERTY, info.backendName(), info.name(),
                        info.driverInfo(), sodiumForLog, MesheliumSodiumVersion.BUILT_AGAINST);
            } else if (!MesheliumPlatform.sodiumAdapterAvailable()) {
                // A loader whose build has no adapter (NeoForge until
                // 2026-09-14; none of the shipped loaders now): the
                // adapter is not in this jar, so no backend would turn it
                // on and the line must not suggest one.
                MesheliumLog.LOGGER.warn(
                        "Backend gate: SODIUM_PRESENT — Sodium is installed and this loader's "
                                + "build of Meshelium has no adapter for it, so Sodium is drawing "
                                + "the terrain on its own and Meshelium is doing nothing to it on "
                                + "any backend. The render-distance range above 32 still "
                                + "applies. (backend={}, device='{}', driver={}, sodium={}, "
                                + "built against {})",
                        info.backendName(), info.name(), info.driverInfo(), sodiumForLog,
                        MesheliumSodiumVersion.BUILT_AGAINST);
            } else {
                // The property's VALUE is printed (2026-09-14), because
                // this is the branch that runs whenever the adapter is not
                // armed, on both loaders, and "declined on the command
                // line" and "wrong backend" were indistinguishable in the
                // log: the adapter's own plugin can only say so on the
                // console, and under -D...=false no adapter class runs
                // that could carry the answer into a logger.
                MesheliumLog.LOGGER.warn(
                        "Backend gate: SODIUM_PRESENT — Sodium is installed and Meshelium's "
                                + "mesh-shader adapter is not running, so Sodium is drawing the "
                                + "terrain on its own and Meshelium is doing nothing to it. The "
                                + "adapter needs the Vulkan backend with VK_EXT_mesh_shader, and "
                                + "-D{}=false switches it off. (backend={}, device='{}', "
                                + "driver={}, {}={}, sodium={}, built against {})",
                        SODIUM_ADAPTER_PROPERTY, info.backendName(), info.name(),
                        info.driverInfo(), SODIUM_ADAPTER_PROPERTY,
                        System.getProperty(SODIUM_ADAPTER_PROPERTY), sodiumForLog,
                        MesheliumSodiumVersion.BUILT_AGAINST);
            }
            // The mismatch notice, and everything it is deliberately NOT.
            //
            // It is a WARN and nothing else: no toast, no chat line, no
            // screen, no latch. Once per session for free, because the gate
            // decides once and every later tick leaves at the state !=
            // UNKNOWN check above. A mod that shouts at a player for
            // updating another mod is a mod they uninstall, and Sodium
            // moving is not by itself a fault - the settings screen carries
            // the same fact in a yellow row for anyone who goes looking.
            //
            // What it does NOT say is that a moved hook degrades safely.
            // meshelium.sodium.mixins.json carries "required": false at the
            // config level AND "injectors": { "defaultRequire": 1 }, and
            // three injections inherit that default, so whether a moved
            // target fails the APPLY rather than going quietly absent is
            // unverified on this Mixin version. Until it is, the sentence
            // says what is known and names the lever.
            if (installedSodium != null
                    && !MesheliumSodiumVersion.matches(installedSodium)) {
                MesheliumLog.LOGGER.warn(
                        "Meshelium's Sodium adapter was written against Sodium {} and this game "
                                + "is running Sodium {}. Meshelium's draw hooks are optional and "
                                + "every error inside the draw hands the pass straight back to "
                                + "Sodium, but whether a MOVED hook fails to APPLY rather than "
                                + "degrade has not been verified on this Mixin version. If "
                                + "terrain looks wrong, this is the first thing to suspect: put "
                                + "Sodium {} back, or start with -D{}=false to leave Sodium to "
                                + "draw it all.",
                        MesheliumSodiumVersion.BUILT_AGAINST, installedSodium,
                        MesheliumSodiumVersion.BUILT_AGAINST, SODIUM_ADAPTER_PROPERTY);
            }
        } else {
            state = decided;
            MesheliumLog.LOGGER.info("Backend gate: {} (backend={}, device='{}', driver={})",
                    decided, info.backendName(), info.name(), info.driverInfo());
        }

        // Wave-10: the extended-render-distance range was widened (config-
        // gated) BEFORE options.txt loaded; now that the gate is decided,
        // re-validate immediately — a GL/no-mesh-shader session narrows
        // back to vanilla's range and clamps any value above 32 with the
        // notice, before the player can leave the title screen (the
        // clamp-back invariant, trigger 1).
        MesheliumExtendedRd.evaluateNow(minecraft);

        // The popup is OWED from here, and tryShowOwedPopup decides when
        // and how it is delivered. It used to be "show it if a TitleScreen
        // is up, otherwise wait for one", which on a quick-play or
        // resume-last-world launch meant waiting forever (owner report,
        // 2026-09-08).
        //
        // Armed on the BACKEND answer, because the popup is about the
        // backend. A Sodium user on OpenGL is exactly who the directive is
        // for: the adapter needs Vulkan with VK_EXT_mesh_shader too, and
        // the Sodium branch used to return before this line, so that player
        // was told nothing. Sodium on Vulkan without mesh shaders gets the
        // one-time NO_MESH_SHADERS notice the same way; VULKAN_MESH_SHADERS
        // arms nothing at all.
        popupOwed = decided == State.OPENGL || decided == State.VULKAN_NO_MESH_SHADERS;
        inWorldSettleTicks = 0;
        tryShowOwedPopup(minecraft);
    }

    /**
     * Show an owed popup the first tick it is decent to.
     *
     * <p>A title screen is still instant. Everything else waits for the
     * HUD of a live world to settle for {@link #IN_WORLD_SETTLE_TICKS},
     * and the conditions below are each a way a modal could land somewhere
     * indefensible:
     *
     * <p>1. {@code Gui.canInterruptScreen()} is vanilla's own "may another
     * screen take over now" question and the ONLY public reader of
     * {@code Gui.clientLevelTeardownInProgress}, which is private (javap:
     * {@code canInterruptScreen()Z} is {@code (screen == null ||
     * screen.canInterruptWithAnotherScreen()) && !teardown}, ip 0-29).
     * With {@code screen() == null} it reduces to the teardown term - the
     * window in which the level is being pulled out from under the player,
     * and the window in which {@code Gui.setScreen(null)} itself THROWS.
     * The explicit screen/overlay/level/player checks stay as extra
     * strictness on top of vanilla's answer, rather than as a
     * reimplementation of it that is missing a term.
     *
     * <p>2. A dying player. {@code Gui.setScreen(null)} over one does not
     * return to the HUD: it builds a {@code DeathScreen}, or calls
     * {@code LocalPlayer.respawn()} outright under {@code
     * doImmediateRespawn}. Pressing [Not Now] must never respawn somebody.
     *
     * <p>3. {@code hurtTime != 0}. A modal arriving on the frame the
     * player took damage is the seize-the-mouse-mid-fight case. hurtTime
     * counts ten ticks down from every hit, so this also restarts the
     * settle for as long as a fight lasts.
     */
    private static void tryShowOwedPopup(Minecraft minecraft) {
        if (!popupOwed) {
            return;
        }
        if (minecraft.gui.screen() instanceof TitleScreen) {
            popupOwed = false;
            inWorldSettleTicks = 0;
            showPopupIfNeeded(minecraft, backendDecided, Presentation.TITLE);
            return;
        }
        if (!minecraft.gui.canInterruptScreen()
                || minecraft.gui.overlay() != null
                || minecraft.gui.screen() != null
                || minecraft.level == null
                || minecraft.player == null
                || minecraft.player.isDeadOrDying()
                || minecraft.player.hurtTime != 0) {
            inWorldSettleTicks = 0;
            return;
        }
        if (++inWorldSettleTicks < IN_WORLD_SETTLE_TICKS) {
            return;
        }
        inWorldSettleTicks = 0;
        if (popupWouldPauseThisWorld(minecraft)) {
            popupOwed = false;
            showPopupIfNeeded(minecraft, backendDecided, Presentation.IN_WORLD);
            return;
        }
        // A server, or a world published to LAN. Minecraft's own pause test
        // cannot be satisfied here, so a modal would take the mouse while
        // the world kept ticking. The standing directive requires that the
        // player be TOLD, not that a modal seize the controls: toast plus
        // chat line now, and showPopupIfNeeded answers whether the MODAL is
        // still owed afterwards. It is owed for ENABLE_VULKAN alone,
        // because that one carries a button a toast cannot.
        if (!inWorldNoticeShown) {
            inWorldNoticeShown = true;
            popupOwed = showPopupIfNeeded(minecraft, backendDecided, Presentation.NO_SCREEN);
        }
    }

    /**
     * Would a pause screen actually pause this world?
     *
     * <p>Exactly {@code Minecraft.runTick}'s own test (ip 533-566) minus
     * the {@code gui.isPausing()} term, which is the term OUR screen would
     * supply:
     * {@code pause = hasSingleplayerServer() && gui.isPausing() &&
     * !singleplayerServer.isPublished()}. False on a remote server and on a
     * world opened to LAN.
     */
    private static boolean popupWouldPauseThisWorld(Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        return minecraft.hasSingleplayerServer() && server != null && !server.isPublished();
    }

    /** Harness probe: is a popup still owed? */
    public static boolean popupOwed() {
        return popupOwed;
    }

    /** Harness probe: has the no-modal telling been given this session? */
    public static boolean inWorldNoticeShown() {
        return inWorldNoticeShown;
    }

    /**
     * HARNESS ONLY: would a modal pause the world the client is in?
     *
     * <p>The singleplayer leg asserts this is TRUE before it arms, because
     * the no-screen branch above shows no modal at all and would otherwise
     * swallow the one leg that proves the release blocker fixed; the
     * dedicated-server leg asserts it is FALSE for the same reason in
     * reverse.
     */
    public static boolean testPopupWouldPauseThisWorld(Minecraft minecraft) {
        return popupWouldPauseThisWorld(minecraft);
    }

    /**
     * HARNESS ONLY: re-arm the owed popup, as the decision tick leaves it.
     *
     * <p>The harness cannot boot into a world - {@code
     * ClientGameTestContext} has no quick-play entry point - so the leg
     * enters a world and then puts the gate back into the state such a
     * session leaves it in. What regressed was the predicate, and this is
     * how the predicate gets driven.
     */
    public static void testRearmPopup() {
        popupOwed = true;
        inWorldSettleTicks = 0;
        inWorldNoticeShown = false;
    }

    /**
     * Put the notice in front of the player in the way this situation
     * allows, and in a world leave a line in chat as well.
     *
     * <p>At a title screen a dismissed popup leaves the player one click
     * from the settings. In a world they go straight back to playing and
     * Escape is a reflex, so the chat line is the difference between "I saw
     * something" and "I saw nothing"; chat persists, scrolls back, and goes
     * into a bug report (MesheliumNotify's class javadoc).
     *
     * <p>The no-screen case raises the toast and writes the chat line as
     * two separate calls, deliberately NOT through {@code
     * MesheliumNotify.error}, which mirrors a toast into chat through
     * {@code meshelium.chat.line} = "[Meshelium] %s: %s". With the toast
     * title "Meshelium is off" and a body that also began "Meshelium is
     * off", that produced "[Meshelium] Meshelium is off: Meshelium is off:
     * ..." - an engineer's string concatenation, not a sentence. The toast
     * gets a short second line; chat gets the whole sentence under the
     * house "[Meshelium] ..." prefix.
     *
     * @return whether the MODAL is still owed after this telling
     */
    private static boolean present(Minecraft minecraft, MesheliumPopupScreen.Variant variant,
            Screen parent, Presentation how) {
        if (how == Presentation.NO_SCREEN) {
            try {
                SystemToast.add(minecraft.gui.toastManager(), POPUP_TOAST_ID,
                        Component.translatable("meshelium.toast.popup.title"),
                        Component.translatable(toastBodyKeyFor(variant)));
            } catch (Throwable t) {
                // A diagnostic that crashes the game is worse than a
                // diagnostic nobody sees; the chat line below and the log
                // still carry it (MesheliumNotify.java:90-95's rule).
                MesheliumLog.LOGGER.debug("Meshelium could not raise the backend toast", t);
            }
            MesheliumNotify.chat(chatKeyFor(variant));
            // ENABLE_VULKAN alone: its popup carries the [Enable Vulkan]
            // button, and a toast has no button. The other two variants'
            // popups are one paragraph and an [OK], which the toast and the
            // chat line between them have just delivered in full, so their
            // once-only latch is rightly spent.
            return variant == MesheliumPopupScreen.Variant.ENABLE_VULKAN;
        }
        minecraft.gui.setScreen(new MesheliumPopupScreen(variant, parent));
        if (how == Presentation.IN_WORLD) {
            MesheliumNotify.chat(chatKeyFor(variant));
        }
        return false;
    }

    /**
     * The chat sentence for this variant, on this install.
     *
     * <p>ONE KEY PER POPUP BODY, and the predicate is character for
     * character the one {@code MesheliumPopupScreen.init()} uses to pick
     * that body (that file, the {@code sodium}/{@code adapter} pair), so
     * the line in chat can never contradict the window above it. It could:
     * with Sodium installed and {@code -Dmeshelium.sodium.adapter=false} -
     * a property the mod's own text names at {@code
     * status.reason.sodium_declined} and {@code gate.sodium_declined} - the
     * popup body says switching to Vulkan will NOT turn Meshelium's terrain
     * rendering on, while a single-keyed chat line said "switch to the
     * Vulkan renderer" directly beneath it, and the settings screen it
     * pointed at does not even build an [Enable Vulkan] button in that
     * state ({@code MesheliumOptionsScreen}, the button's own condition).
     *
     * <p>One key for three variants was wrong twice over before that. At
     * the VULKAN_FAILED site Vulkan is ALREADY selected - that is what
     * makes it VULKAN_FAILED - so offering the switch is the broken promise
     * the comment above that branch exists to forbid. At the
     * NO_MESH_SHADERS site the backend IS Vulkan, so "Minecraft is drawing
     * with OpenGL" is flatly untrue and the advice impossible.
     *
     * <p>And the ROUTE is split, because the drafted one does not exist on
     * the install it was mostly for. {@code OptionsScreenMixin} returns
     * vanilla's untouched grid when Sodium is not loaded, so there is no
     * "Meshelium..." row in vanilla's Options screen on a standalone
     * install, and {@code MesheliumBootSmokeTest} asserts its absence.
     * Standalone the routes are Options > Video Settings > "Meshelium
     * Settings..." and {@code /meshelium}; under Sodium, Video Settings
     * usually opens Sodium's own screen, which is why the Options row
     * exists at all. Both loaders register {@code /meshelium}.
     */
    private static String chatKeyFor(MesheliumPopupScreen.Variant variant) {
        boolean sodium = MesheliumPlatform.isModLoaded(SODIUM_MOD_ID);
        boolean adapter = sodium && sodiumAdapterConfigured();
        return switch (variant) {
            case ENABLE_VULKAN -> adapter ? "meshelium.chat.popup.opengl.sodium"
                    : sodium ? "meshelium.chat.popup.opengl.sodium_no_adapter"
                    : "meshelium.chat.popup.opengl";
            case VULKAN_FAILED -> sodium ? "meshelium.chat.popup.vulkan_failed.sodium"
                    : "meshelium.chat.popup.vulkan_failed";
            case NO_MESH_SHADERS -> sodium ? "meshelium.chat.popup.no_mesh.sodium"
                    : "meshelium.chat.popup.no_mesh";
            case RESTART_REQUIRED -> throw new AssertionError(
                    "RESTART_REQUIRED is never presented by the gate");
        };
    }

    /**
     * The toast's second line: the bare reason, in a few words.
     *
     * <p>No Sodium twins, and that is a decision rather than an omission.
     * A toast fades, so it carries only what is true in every shape - the
     * reason Meshelium is off - and points at the chat line, which is
     * still there a minute later and does carry the Sodium wording.
     */
    private static String toastBodyKeyFor(MesheliumPopupScreen.Variant variant) {
        return switch (variant) {
            case ENABLE_VULKAN -> "meshelium.toast.popup.opengl";
            case VULKAN_FAILED -> "meshelium.toast.popup.vulkan_failed";
            case NO_MESH_SHADERS -> "meshelium.toast.popup.no_mesh";
            case RESTART_REQUIRED -> throw new AssertionError(
                    "RESTART_REQUIRED is never presented by the gate");
        };
    }

    /**
     * The three-way switch, unchanged in every branch except that each
     * {@code setScreen(new MesheliumPopupScreen(...))} is now a {@link
     * #present} call, which knows how to deliver the notice in a world
     * nothing can pause.
     *
     * <p>The two once-only latches are still written here, before the
     * presentation, and they are still spent on all three presentations -
     * because on all three the notice they guard really was given. What
     * the toast does NOT spend is the modal itself for ENABLE_VULKAN, and
     * that variant has no latch: {@code showVulkanPrompt} is spent by
     * [Don't Show This Again] and by nothing else.
     *
     * @return whether the MODAL is still owed after this call
     */
    private static boolean showPopupIfNeeded(Minecraft minecraft, State decided,
            Presentation how) {
        MesheliumConfig config = MesheliumConfig.get();
        Screen parent = minecraft.gui.screen();
        switch (decided) {
            case OPENGL -> {
                boolean vulkanWasRequested =
                        minecraft.options.preferredGraphicsBackend().get() == PreferredGraphicsApi.VULKAN;
                if (vulkanWasRequested) {
                    // Offering [Enable Vulkan] would be a broken promise —
                    // the option is already set and the boot fell back to GL.
                    if (!config.vulkanFailedNoticeShown) {
                        config.vulkanFailedNoticeShown = true;
                        config.save();
                        return present(minecraft,
                                MesheliumPopupScreen.Variant.VULKAN_FAILED, parent, how);
                    }
                } else if (config.showVulkanPrompt) {
                    return present(minecraft,
                            MesheliumPopupScreen.Variant.ENABLE_VULKAN, parent, how);
                }
            }
            case VULKAN_NO_MESH_SHADERS -> {
                // Only accuse the hardware when we actually looked at it.
                // meshShadersRequested() is written by the device-creation
                // mixin at the END of its probe, and that mixin catches
                // Throwable and lets vanilla carry on, so "our probe threw"
                // and "this GPU has no VK_EXT_mesh_shader" both arrive here
                // as false. Telling the second story for the first is a
                // double insult: it blames a card that may be perfectly
                // capable, and it advises a driver update that cannot
                // possibly help. vulkanDeviceCreationSeen() is the
                // discriminator that already existed for this and had no
                // callers.
                if (!MesheliumVulkanState.vulkanDeviceCreationSeen()) {
                    MesheliumLog.LOGGER.error(
                            "Meshelium is off, and this one is Meshelium's fault rather than your "
                                    + "hardware's: the Vulkan backend is running but our "
                                    + "mesh-shader probe never finished, so we never learned what "
                                    + "this device supports. Look for a 'mesh-shader probe failed' "
                                    + "error earlier in this log and report it. No popup is shown "
                                    + "for this, because the honest message would be an apology "
                                    + "rather than advice.");
                    return false;
                }
                if (!config.noMeshShaderNoticeShown) {
                    config.noMeshShaderNoticeShown = true;
                    config.save();
                    return present(minecraft,
                            MesheliumPopupScreen.Variant.NO_MESH_SHADERS, parent, how);
                }
            }
            default -> {
                // VULKAN_MESH_SHADERS: no popup — the caps INFO block was
                // already logged at device creation by the mixin helper.
            }
        }
        return false;
    }
}
