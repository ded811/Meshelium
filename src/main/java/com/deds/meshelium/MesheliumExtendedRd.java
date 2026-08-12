/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium;

import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.fabric.mixin.OptionInstanceAccessor;

import com.mojang.serialization.Codec;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Wave-10: render distance above vanilla's 32 — the option-range widening
 * and its safety half, the <b>clamp-back invariant</b>: <i>the widened
 * range may only ever take effect while Meshelium actually draws.</i> A GL
 * user or a coverage-guard-passive world at rd 64 on the vanilla renderer
 * would be a slideshow Meshelium caused, so every path that ends with
 * vanilla rendering while the option sits above 32 clamps it back to the
 * vanilla ceiling, saves, and says so once (toast + WARN).
 *
 * <h2>Wave-13 rework — the vanilla slider is the interface</h2>
 * The owner's playtest verdict on wave 10 ("overriding the render
 * distance does nothing") traced to three boot-time/world-pinned wirings
 * (dead-end inventory: docs/EXTENDED-RENDER-DISTANCE.md wave-13
 * section). What changed here:
 * <ul>
 *   <li><b>The chunk-task priority ladder is widened UNCONDITIONALLY at
 *       boot</b> (+{@value com.deds.meshelium.MesheliumConfig#MAX_MAX_RENDER_DISTANCE}−32
 *       rungs, {@link #widenChunkTaskLadder}) — wave 10 gated it on the
 *       BOOT-time config exceeding 32, while the two server
 *       {@code @ModifyConstant} caps read {@link #serverViewDistanceCap()}
 *       LIVE: a ceiling raised mid-session then a world rejoin got a
 *       96-range ticket tracker over a 46-rung ladder — the first rd-48
 *       run's AIOOBE chunk-worker crash, reachable in production. Cost of
 *       unconditional: 64 extra EMPTY Long2ObjectLinkedOpenHashMaps per
 *       queue ({@code ChunkTaskPriorityQueue.<init>} bytecode:
 *       {@code IntStream.range(0, PRIORITY_LEVEL_COUNT).mapToObj(map::new)});
 *       ChunkMap's only other read is {@code min(queueLevel, COUNT-1)} —
 *       a clamp INTO the ladder, coherent under widening.</li>
 *   <li><b>The config ceiling defaults to 96 and is only a CAP</b> — GPU
 *       buffers pin from the OPTION at world standup
 *       ({@code MesheliumScaling.pinForWorld}), so the wide default costs a
 *       small-rd player nothing. The vanilla Video Settings slider is the
 *       render-distance control, out of the box, under the gate.</li>
 *   <li><b>Mid-world slider raises are live on BOTH halves (wave 15)</b>:
 *       the server half was always live (ChunkMap's clamp is re-read per
 *       change; the tracker pinned from the ceiling at world creation
 *       covers the whole cap), and the GPU half now GROWS in place —
 *       {@link #maybeGrowOrHint} asks the pump to grow the pinned-side
 *       record buffers (the wave-14 grow-and-copy generalized), residency
 *       expands past the old pin, no rejoin. The once-per-world "rejoin
 *       to apply fully" hint survives only as the fallback for a FAILED
 *       grow (GPU allocation refused).</li>
 * </ul>
 * The clamp-back invariant is byte-identically enforced — none of the
 * three triggers changed.
 *
 * <h2>Where 32 lives (all javap-verified against the 26.2 jar)</h2>
 * <ul>
 *   <li>{@code Options.<init>}: {@code renderDistance = new OptionInstance(
 *       "options.renderDistance", …, new IntRange(2, maxMemory≥1e9 ? 32 :
 *       16, false), 12, listener)} — the range AND the persistence codec:
 *       the 6-arg {@code OptionInstance} ctor captures {@code codec =
 *       values.codec()} = {@code Codec.intRange(min, max+1)} at
 *       construction ({@code IntRange.codec()} bytecode reads the FIELDS),
 *       and {@code Options.load()} runs at the ctor's tail (ip 5050). So
 *       widening must swap BOTH the {@code values} ValueSet and the
 *       {@code codec} field ({@link OptionInstanceAccessor}), and must do
 *       it BEFORE {@code load()} or a saved 48 is rejected at boot
 *       (that is {@code OptionsMixin}'s injection point). The slider and
 *       {@code validateValue} both dispatch through the IntRange
 *       accessors, so swapping the record instance is sufficient for
 *       them.</li>
 *   <li>{@code getEffectiveRenderDistance()} = {@code serverRenderDistance
 *       > 0 ? min(option, serverRenderDistance) : option} — on
 *       singleplayer the INTEGRATED SERVER'S radius caps the client, so
 *       the server half below is load-bearing, not cosmetic.</li>
 * </ul>
 *
 * <h2>The server half (singleplayer)</h2>
 * {@code IntegratedServer.tickServer} already follows the client option
 * every tick with no upper clamp ({@code max(2, renderDistance.get())} →
 * {@code PlayerList.setViewDistance}, bytecode ip 112-181). The two REAL
 * caps live deeper, each widened by a gated {@code @ModifyConstant}:
 * <ul>
 *   <li>{@code ChunkMap.setServerViewDistance}: {@code Mth.clamp(i, 2,
 *       32)} ({@code ChunkMapMixin});</li>
 *   <li>{@code DistanceManager.<init>}: {@code new PlayerTicketTracker(
 *       this, 32)} — the tracker's {@code maxDistance} bounds how far
 *       PLAYER_LOADING ticket levels can propagate (chunks past
 *       maxDistance+2 never enter its map), i.e. how far chunks LOAD
 *       ({@code DistanceManagerMixin}).</li>
 * </ul>
 * Both mixins read {@link #serverViewDistanceCap()}, which returns the
 * vanilla 32 unless the gate is VULKAN_MESH_SHADERS with terrain enabled
 * and a config ceiling above 32 — so a GL boot, a quickPlay session whose
 * gate never decided, or a dedicated server (which never loads this
 * client-env mod at all) keeps vanilla's exact clamps. On multiplayer the
 * server rules: {@code getEffectiveRenderDistance}'s min() means an
 * extended option does nothing beyond the server's distance — honest
 * limitation, documented; Nvidium-style retention of unloaded chunks is
 * out of scope this wave (possible wave 11).
 *
 * <p><b>The third half — per-player SENDING (no mixin, found by the first
 * rd-48 run):</b> the two clamps govern how far the server LOADS; what it
 * SENDS each player is {@code ChunkMap.getPlayerViewDistance} =
 * {@code clamp(ServerPlayer.requestedViewDistance, 2, serverViewDistance)},
 * re-applied to every player every tick by {@code ChunkMap.tick()} →
 * {@code updateChunkTracking}. {@code requestedViewDistance} updates ONLY
 * via {@code ServerboundClientInformationPacket}, which the client emits
 * from {@code Options.broadcastOptions()} — called by {@code Options.save()}
 * (and at login), and {@code OptionsSubScreen.removed()} calls
 * {@code save()}: closing the video-settings screen after moving the
 * slider is the production path, and {@code buildPlayerInformation()}
 * passes the RAW option (signed-byte wire — values through 127 fit, which
 * is exactly why wave 15's hard cap stops at 120: 128 would wrap to −128
 * and clamp to 2, silently breaking this whole chain), so vanilla plumbing
 * already carries &gt;32 end-to-end. A programmatic
 * {@code OptionInstance.set()} without {@code save()} leaves the server
 * sending at the OLD radius no matter how far it loads — exactly the
 * first rd-48 harness run: frozen at 157 client chunks, the vd-5
 * {@code ChunkTrackingView} fingerprint. All bytecode-cited in
 * docs/EXTENDED-RENDER-DISTANCE.md §2b.</p>
 *
 * <h2>Clamp-back triggers (the complete inventory)</h2>
 * <ol>
 *   <li><b>Gate decision tick</b> (title screen, via
 *       {@link #evaluateNow}): gate ≠ VULKAN_MESH_SHADERS, or terrain
 *       rendering disabled, or config ceiling ≤ 32 → the option range is
 *       restored to vanilla AND a value &gt; 32 clamps + saves + toasts.
 *       This runs before the user can reach any screen past the title.</li>
 *   <li><b>Per-tick monitor</b> (every client tick, cheap): catches the
 *       coverage guard going passive mid-world (arena full ⇒
 *       {@code TerrainDrawer.coveragePassive()}), the drawer's session
 *       error latch, live config/property flips that disable terrain, and
 *       the quickPlay edge where a world exists while the gate is still
 *       UNKNOWN (the gate only decides on a title screen). During boot
 *       (gate UNKNOWN, no level) it waits — clamping before the gate
 *       decides would wrongly strip a healthy Vulkan user's setting.</li>
 *   <li><b>Options-screen change</b> ({@link #onConfigChanged}): lowering
 *       the Meshelium ceiling below the current option value re-validates
 *       immediately through the same monitor logic.</li>
 * </ol>
 * The monitor runs at tick granularity, so after a mid-world trigger
 * vanilla may render up to a few frames above 32 before the clamp lands —
 * transient by construction (the option write + save is same-tick).
 *
 * <p><b>Class-loading discipline:</b> this class touches only client/JDK
 * types; the {@code TerrainDrawer} probes (LWJGL-importing class) are
 * reached exclusively behind a {@code state == VULKAN_MESH_SHADERS} check,
 * so GL sessions never load the vk package through this path.</p>
 */
@Environment(EnvType.CLIENT)
public final class MesheliumExtendedRd {

    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();
    private static final SystemToast.SystemToastId REJOIN_TOAST_ID = new SystemToast.SystemToastId();

    // Captured once at Options construction (render-thread-confined after).
    private static OptionInstance.ValueSet<?> vanillaValues;
    private static Codec<?> vanillaCodec;
    private static int vanillaMax = 32;
    private static int vanillaMin = 2;
    private static boolean captured;
    private static boolean captureFailedLogged;

    /** The max the option's CURRENT range allows (== vanillaMax when not widened). */
    private static int appliedMax = 32;
    /**
     * BENCH-ONLY escape from the clamp-back, so an extended-distance run
     * can measure VANILLA at a distance vanilla's own slider cannot reach
     * (owner directive 2026-08-11). Never set by shipped code, never
     * documented to players, and read in exactly one place.
     */
    public static final String PROPERTY_BENCH_NO_CLAMP = "meshelium.bench.noClampBack";
    /** Re-armed whenever the option drops to a legal vanilla value. */
    private static boolean clampNoticeArmed = true;
    // Probes (harness).
    private static volatile long sessionClamps;
    private static volatile boolean rangeWidenedNow;
    private static volatile long rejoinHints;

    /**
     * Wave-13 mid-world-raise hint state: the pinned scaling snapshot the
     * hint last fired against (identity per {@code pinForWorld} call), so
     * the hint fires at most once per world. Cleared by
     * {@link #onWorldPinned} at every world standup.
     */
    private static Object hintedSnapshot;
    private static boolean ladderWidened;

    private MesheliumExtendedRd() {
    }

    /** Called once from the client entrypoint, after {@code MesheliumGate.init()}. */
    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(MesheliumExtendedRd::onEndTick);
    }

    // ------------------------------------------------------------------
    // Probes (harness)
    // ------------------------------------------------------------------

    /** True while the vanilla option range is widened past its vanilla max. */
    public static boolean rangeWidened() {
        return rangeWidenedNow;
    }

    /** Clamp-back events this session (each = option forced back + save). */
    public static long sessionClamps() {
        return sessionClamps;
    }

    /** The vanilla ceiling on this machine (32; 16 on sub-GiB heaps). */
    public static int vanillaMaxRenderDistance() {
        return captured ? vanillaMax : 32;
    }

    /** Wave-13 rejoin hints shown this session (at most one per world). */
    public static long rejoinHints() {
        return rejoinHints;
    }

    // ------------------------------------------------------------------
    // Hooks
    // ------------------------------------------------------------------

    /**
     * {@code Options.<init>}, immediately BEFORE {@code load()} — capture
     * the vanilla ValueSet/codec and widen when the config asks for it, so
     * a previously saved value above 32 survives the load's codec. Runs on
     * the render thread during {@code Minecraft.<init>}; the entrypoint
     * has already loaded MesheliumConfig by then (fabric injects entrypoints
     * at ctor offset 563, Options is built at 579 — MesheliumGate javadoc).
     * The gate is UNKNOWN here by construction; the decision tick narrows
     * the range again before any world can exist (clamp-back trigger 1).
     */
    public static void widenAtConstruction(Options options) {
        // Wave-13: the ladder headroom is UNCONDITIONAL — before the
        // values-shape check below (a foreign ValueSet must not leave the
        // ladder narrow while the live server caps read the config), and
        // regardless of backend/config (see the method javadoc for the
        // crash class this closes and the bytecode-cited cost).
        widenChunkTaskLadder(MesheliumConfig.MAX_MAX_RENDER_DISTANCE - 32);

        OptionInstance<Integer> rd = options.renderDistance();
        Object values = ((OptionInstanceAccessor) (Object) rd).meshelium$values();
        if (!(values instanceof OptionInstance.IntRange range)) {
            // Another mod replaced the ValueSet — do not fight it; the
            // feature stays off and everything else in this class no-ops.
            if (!captureFailedLogged) {
                captureFailedLogged = true;
                MesheliumClient.LOGGER.warn(
                        "Meshelium extended render distance disabled: the renderDistance option's "
                                + "ValueSet is {} (not vanilla's IntRange — another mod got here "
                                + "first)", values == null ? "null" : values.getClass().getName());
            }
            return;
        }
        vanillaValues = range;
        vanillaCodec = ((OptionInstanceAccessor) (Object) rd).meshelium$codec();
        vanillaMax = range.maxInclusive();
        vanillaMin = range.minInclusive();
        appliedMax = vanillaMax;
        captured = true;

        int configured = MesheliumConfig.maxRenderDistanceConfigured();
        if (configured > vanillaMax && MesheliumConfig.terrainRenderingEnabled()) {
            applyRange(options, configured);
            MesheliumClient.LOGGER.info(
                    "Meshelium widened the render-distance option range to [{}, {}] before "
                            + "options.txt loads (config maxRenderDistance ceiling; the "
                            + "title-screen gate decision re-validates it)", vanillaMin, configured);
        }
    }

    /**
     * The THIRD server-side 32-derived ladder, found by the first rd-48
     * run crashing chunk workers (AIOOBE index 50 vs length 46):
     * {@code ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT} =
     * {@code ChunkLevel.MAX_LEVEL + 2} sizes a per-queue priority list
     * indexed directly by ticket level, so a ticket tracker widened by
     * {@code extraChunks} needs this ladder widened by the same amount.
     * Boot-time (before any server exists, queues size themselves in
     * their ctor from the static), write-once. Both runtime readers read
     * the static — no constant folding (clinit-computed): the queue ctor
     * builds {@code IntStream.range(0, PRIORITY_LEVEL_COUNT).mapToObj(…)}
     * (one EMPTY Long2ObjectLinkedOpenHashMap per rung — the whole cost
     * of headroom), and ChunkMap's only read is
     * {@code min(queueLevel, PRIORITY_LEVEL_COUNT − 1)}, a clamp INTO the
     * ladder (both javap-cited, 26.2 jar).
     *
     * <p><b>Wave-13: UNCONDITIONAL at boot, always
     * +({@value com.deds.meshelium.MesheliumConfig#MAX_MAX_RENDER_DISTANCE}−32)</b>
     * (the hard ceiling's worst case — +88 since wave 15 raised the hard
     * max to 120; a 120-cap tracker produces ticket levels up to 122, so
     * the ladder must hold ≥ 123 rungs). Wave 10 gated this on the boot-time config,
     * but the two server {@code @ModifyConstant} caps read
     * {@link #serverViewDistanceCap()} LIVE — so a ceiling raised
     * mid-session followed by a world REJOIN built a PlayerTicketTracker
     * whose levels (maxDistance + 2, up to 98) overran the never-widened
     * 46-rung ladder: the same AIOOBE crash class the first rd-48 run
     * hit, silently reachable in production. Removing the boot-config
     * dependency removes the crash class; a few empty maps per queue is
     * the price, on every backend (the GL path gains rungs no ticket
     * level can ever reach — vanilla's own tracker stays at 32 there, so
     * the extra rungs are dead weight, not behaviour).</p>
     */
    private static void widenChunkTaskLadder(int extraChunks) {
        if (ladderWidened) {
            return;
        }
        ladderWidened = true;
        int base = com.deds.meshelium.fabric.mixin.ChunkTaskPriorityQueueAccessor
                .meshelium$priorityLevelCount();
        com.deds.meshelium.fabric.mixin.ChunkTaskPriorityQueueAccessor
                .meshelium$setPriorityLevelCount(base + extraChunks);
        MesheliumClient.LOGGER.info(
                "Meshelium widened the chunk-task priority ladder: {} -> {} rungs (+{} chunks of "
                        + "unconditional headroom for the {}-chunk ceiling; wave 13)",
                base, base + extraChunks, extraChunks, MesheliumConfig.MAX_MAX_RENDER_DISTANCE);
    }

    /** Harness probe: the ladder's current rung count (46 + 88 once widened). */
    public static int chunkTaskLadderRungs() {
        return com.deds.meshelium.fabric.mixin.ChunkTaskPriorityQueueAccessor
                .meshelium$priorityLevelCount();
    }

    /**
     * The gate just decided (title screen, same tick) — run the full
     * evaluation immediately so a GL/no-mesh-shader session is back on the
     * vanilla range before the player can leave the title screen.
     */
    public static void evaluateNow(Minecraft minecraft) {
        onEndTick(minecraft);
    }

    /**
     * The Meshelium options screen changed {@code maxRenderDistance} (the
     * ceiling row's setter calls this right after {@code config.save()}) —
     * the same evaluation as the per-tick monitor, immediately: raising
     * the ceiling re-applies the widened option range this tick under the
     * gate; lowering it restores/clamps this tick. The vanilla slider
     * widget picks the new range up at the next Video Settings open
     * ({@code OptionsSubScreen.init()} rebuilds the OptionsList and
     * {@code OptionInstance.createButton} reads the {@code values} field
     * at widget creation — both bytecode-cited in the wave-13 doc note).
     */
    public static void onConfigChanged(Minecraft minecraft) {
        onEndTick(minecraft);
    }

    /**
     * Wave-13: a world's scaling snapshot was just pinned
     * ({@code MesheliumTerrainGpu.create}, render thread) — re-arm the
     * once-per-world rejoin hint against the fresh snapshot.
     */
    public static void onWorldPinned() {
        hintedSnapshot = null;
    }

    // ------------------------------------------------------------------
    // The monitor (every client tick — a handful of field reads)
    // ------------------------------------------------------------------

    private static void onEndTick(Minecraft minecraft) {
        if (!captured || minecraft.options == null) {
            return;
        }
        Options options = minecraft.options;
        MesheliumGate.State state = MesheliumGate.state();
        int configured = MesheliumConfig.maxRenderDistanceConfigured();
        boolean extendedWanted = configured > vanillaMax && MesheliumConfig.terrainRenderingEnabled();
        boolean gateOk = state == MesheliumGate.State.VULKAN_MESH_SHADERS;
        boolean bootGrace = state == MesheliumGate.State.UNKNOWN && minecraft.level == null;

        applyRange(options, extendedWanted && (gateOk || bootGrace) ? configured : vanillaMax);

        int rd = options.renderDistance().get();
        if (rd <= vanillaMax) {
            clampNoticeArmed = true;
            return;
        }
        if (bootGrace) {
            return; // options loaded, gate undecided, no world — wait for the decision tick
        }
        if (Boolean.getBoolean(PROPERTY_BENCH_NO_CLAMP)) {
            // BENCH ONLY (owner directive 2026-08-11: "for vanilla please
            // find some way to set it above 32"). The clamp-back exists so
            // a player never sits at rd 64 with Meshelium dormant and vanilla
            // struggling. That is exactly the state a fair extended-distance
            // BASELINE needs: vanilla's own renderer, at a distance its
            // slider cannot reach, so the comparison stops being "Meshelium
            // versus a clamped 32" and becomes a real ratio. Never set
            // outside the benchmark harness; the shipped default is the
            // clamp, and every non-bench path below is unchanged.
            return;
        }
        if (gateOk && extendedWanted && drawerHealthy(minecraft)) {
            // Meshelium draws — the widened range may take effect. Wave-15:
            // an option above this world's PINNED GPU budget asks the
            // pump to GROW the pinned-side buffers between frames
            // (grow-and-copy, the wave-14 machinery generalized); the
            // once-per-world rejoin hint fires only when a grow FAILED.
            maybeGrowOrHint(minecraft, rd);
            return;
        }
        clampBack(minecraft, options, state, extendedWanted);
    }

    /**
     * Wave-15: the mid-world raise handler. The option exceeding the
     * pinned budget now REQUESTS a live grow — the pump grows the
     * region/section record buffers (grow-and-copy, identical offsets,
     * wave-14 fence discipline), drops the drawer's snapshot-sized
     * occlusion/frame-list resources for same-frame recreation, and swaps
     * the scaling snapshot; residency then expands in place and the
     * request is consumed within one frame, so this re-requests at most a
     * tick or two before {@code pinned().maxRd()} covers the option. The
     * wave-13 rejoin hint survives ONLY as the fallback: it fires (at
     * most once per world, keyed on the pinned snapshot's identity —
     * every successful grow installs a fresh identity, re-arming it for a
     * LATER failed raise) when the GPU refused the grow
     * ({@code TerrainResidency.pinnedGrowFailedThisWorld()}). Only
     * host-package/pure-CPU classes are touched — the class-loading
     * discipline is unchanged, and this runs strictly inside the
     * healthy-drawer branch (a clamping session never sees it).
     */
    private static void maybeGrowOrHint(Minecraft minecraft, int rd) {
        if (minecraft.level == null) {
            return;
        }
        MesheliumScaling.Snapshot pinned = MesheliumScaling.pinned();
        if (pinned == null || rd <= pinned.maxRd()) {
            return;
        }
        if (!com.deds.meshelium.terrain.host.TerrainResidency.pinnedGrowFailedThisWorld()) {
            com.deds.meshelium.terrain.host.TerrainResidency.requestPinnedGrow(rd);
            return; // next pump grows the pinned budget; no rejoin, no hint
        }
        if (pinned == hintedSnapshot) {
            return;
        }
        hintedSnapshot = pinned;
        rejoinHints++;
        MesheliumClient.LOGGER.warn(
                "Meshelium: render distance {} exceeds this world's pinned GPU budget ({} chunks) "
                        + "and the live grow FAILED (GPU allocation refused) — drawing continues "
                        + "at pinned capacity; rejoin the world to apply the full distance "
                        + "(wave-15: the hint is the grow path's fallback)",
                rd, pinned.maxRd());
        if (minecraft.gui != null) {
            SystemToast.add(minecraft.gui.toastManager(), REJOIN_TOAST_ID,
                    Component.translatable("meshelium.rd.rejoin.title"),
                    Component.translatable("meshelium.rd.rejoin.body", rd, pinned.maxRd()));
        }
    }

    /**
     * Probes on the vk side — only reached when the gate already said
     * VULKAN_MESH_SHADERS (class-loading discipline; see class javadoc).
     * Coverage-guard passivity counts only while a world exists: the
     * static flag holds the LAST world's verdict at the title screen,
     * where re-raising the option must stay legal for the next world.
     */
    private static boolean drawerHealthy(Minecraft minecraft) {
        if (com.deds.meshelium.vk.TerrainDrawer.lastError() != null) {
            return false; // session error latch: vanilla draws from here on
        }
        return minecraft.level == null || !com.deds.meshelium.vk.TerrainDrawer.coveragePassive();
    }

    private static void clampBack(Minecraft minecraft, Options options,
            MesheliumGate.State state, boolean extendedWanted) {
        int target = vanillaMax;
        options.renderDistance().set(target);
        options.save();
        sessionClamps++;

        boolean guardPassive = false;
        if (state == MesheliumGate.State.VULKAN_MESH_SHADERS) {
            guardPassive = extendedWanted && minecraft.level != null
                    && com.deds.meshelium.vk.TerrainDrawer.coveragePassive();
        }
        if (clampNoticeArmed) {
            clampNoticeArmed = false;
            String bodyKey = guardPassive ? "meshelium.rd.clamped.passive" : "meshelium.rd.clamped.off";
            MesheliumClient.LOGGER.warn(
                    "Meshelium clamped the render distance back to {} (gate={}, terrainEnabled={}, "
                            + "configuredMax={}, guardPassive={}) — the extended range only takes "
                            + "effect while Meshelium draws (wave-10 clamp-back invariant)",
                    target, state, MesheliumConfig.terrainRenderingEnabled(),
                    MesheliumConfig.maxRenderDistanceConfigured(), guardPassive);
            if (minecraft.gui != null) {
                SystemToast.add(minecraft.gui.toastManager(), TOAST_ID,
                        Component.translatable("meshelium.rd.clamped.title", target),
                        Component.translatable(bodyKey));
            }
        }
    }

    // ------------------------------------------------------------------
    // Range plumbing
    // ------------------------------------------------------------------

    /**
     * Swap the option's ValueSet AND codec (both captured by the
     * OptionInstance at construction — codec from {@code values.codec()} =
     * {@code Codec.intRange(min, max+1)}, bytecode-verified, replicated
     * exactly). Idempotent and cheap: no-op unless the target max changed.
     */
    private static void applyRange(Options options, int newMax) {
        if (!captured || newMax == appliedMax) {
            return;
        }
        OptionInstance<Integer> rd = options.renderDistance();
        OptionInstanceAccessor accessor = (OptionInstanceAccessor) (Object) rd;
        if (newMax <= vanillaMax) {
            accessor.meshelium$setValues(vanillaValues);
            accessor.meshelium$setCodec(vanillaCodec);
            appliedMax = vanillaMax;
            rangeWidenedNow = false;
        } else {
            accessor.meshelium$setValues(new OptionInstance.IntRange(vanillaMin, newMax, false));
            accessor.meshelium$setCodec(Codec.intRange(vanillaMin, newMax + 1));
            appliedMax = newMax;
            rangeWidenedNow = true;
        }
    }

    // ------------------------------------------------------------------
    // Server half
    // ------------------------------------------------------------------

    /**
     * The value the two server-side {@code @ModifyConstant} mixins
     * substitute for their literal 32 ({@code ChunkMap.setServerViewDistance}'s
     * clamp ceiling; {@code DistanceManager.<init>}'s PlayerTicketTracker
     * range). Vanilla-exact 32 unless Meshelium's extended range is live
     * RIGHT NOW (gate + terrain + config — the same condition the option
     * widening uses, evaluated on the caller's thread AT EACH CALL; all
     * reads are volatile/immutable — wave-13 re-verified: neither mixin
     * captures a boot value, {@code setServerViewDistance} re-clamps on
     * every view-distance change and the tracker reads this at world
     * construction). With the wave-13 ceiling default of 96, a world
     * created under the gate gets a 96-range tracker up front — which is
     * exactly what makes a mid-world SLIDER raise fully live on the
     * server half (loading and sending both follow without a rejoin);
     * only the GPU buffer budget stays pinned (the rejoin hint's
     * subject). Deliberately still derived from the CONFIG ceiling, not
     * the option: deriving the tracker from the option would re-create
     * the wave-10 dead-end (a construction-pinned cap the slider cannot
     * cross). Cost note on {@code DistanceManagerMixin}.
     */
    public static int serverViewDistanceCap() {
        if (MesheliumGate.state() == MesheliumGate.State.VULKAN_MESH_SHADERS
                && MesheliumConfig.terrainRenderingEnabled()) {
            int configured = MesheliumConfig.maxRenderDistanceConfigured();
            if (configured > 32) {
                return configured;
            }
        }
        return 32;
    }
}
