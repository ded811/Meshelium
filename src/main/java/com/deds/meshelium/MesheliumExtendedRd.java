/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
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

    /**
     * Vanilla's own render-distance ceiling, read from the 26.2 jar rather
     * than remembered: {@code Options.<init>} builds
     * {@code new IntRange(2, mem >= 1_000_000_000L ? 32 : 16, false)} with
     * a default of 12 (javap, {@code Options} bytecode offset 4931-4948).
     *
     * <p>Used only as a CAP on the captured maximum, never as the value
     * itself, because 32 is not always right: a JVM under a gigabyte gets
     * 16, and clamping such a player to 32 would hand
     * {@code OptionInstance.set} a value its own ValueSet rejects, whereupon
     * validateValue falls back to the initial 12 and the player silently
     * loses their setting.</p>
     */
    private static final int VANILLA_RD_HARD_MAX = 32;

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

    /**
     * Arena fraction that triggers a render-distance step DOWN. Above the
     * 85% mark that starts retained eviction, so the cheap remedy gets
     * first refusal and this only fires when that was not enough.
     */
    private static final long ARENA_BACKOFF_PCT = 92;

    /*
     * THERE IS DELIBERATELY NO AUTOMATIC RESTORE. The backoff only ever
     * lowers the render distance; giving a step back is the player's call,
     * from the ordinary Video Settings slider.
     *
     * This was tried and removed (owner's call, 2026-08-12, and the
     * measurements agree). A restore is worth something only if the bigger
     * distance would now fit, and the evidence says it usually would not:
     * the 352 MiB harness run ping-ponged 48 to 40 and back every three
     * seconds for a minute and a half. Both halves of that cycle were the
     * design's own fault. Changing the render distance makes vanilla reset
     * the level, so a second after a step the arena is nearly EMPTY (12%
     * was recorded) — the low reading authorising the restore was
     * manufactured by the step before it. And restoring walked back into
     * the distance that had just failed to fit, so the re-trip was
     * arithmetic, not bad luck.
     *
     * A predictive guard was then built that projected usage to the target
     * distance by area and refused restores that would not fit. It worked,
     * and in the run that proved it, it refused EVERY restore. That is the
     * argument for deleting the whole path rather than tuning it: each step
     * costs a full level rebuild, so a restore that re-trips buys one hitch
     * going up and another coming down, in exchange for nothing. Lowering
     * is cheap to get wrong in the player's favour and expensive to get
     * wrong against them; raising is the reverse.
     */

    /**
     * Arena fraction at which the normal cooldown is abandoned. Measured,
     * not guessed: in the 192 MiB harness run of 2026-08-12 the arena went
     * from 78 MiB to 176 MiB in three seconds while a world was streaming
     * in, so one step per three seconds was never going to keep up, and the
     * guard tripped with the backoff still waiting out its cooldown. Above
     * this line the arena is filling faster than the polite rate can shed,
     * and the fence lag the cooldown exists to respect is the lesser
     * problem: a step taken against slightly stale numbers costs the player
     * eight chunks, a step not taken costs them the renderer.
     */
    private static final long ARENA_CRITICAL_PCT = 97;

    /** Chunks per step, matching the option's own 8-lattice. */
    private static final int BACKOFF_STEP = 8;

    /**
     * Ticks between steps. Freed arena space only comes back after the
     * fence lag, so an immediate re-read still looks full; without this the
     * first spike would walk the distance to the floor in about a second.
     */
    private static final int BACKOFF_COOLDOWN_TICKS = 60;

    /**
     * Ticks between steps once past {@link #ARENA_CRITICAL_PCT}. Half a
     * second: still long enough that a step is not taken twice against the
     * same stale reading, short enough to shed 16 chunks a second, which
     * outruns any fill rate this renderer has been measured at.
     */
    private static final int BACKOFF_CRITICAL_COOLDOWN_TICKS = 10;

    /**
     * Ticks since the last step, counting UP. Up rather than down because
     * how long the wait needs to be is not known when the step is taken: it
     * depends on the pressure read on each later tick.
     */
    private static int backoffElapsedTicks;
    /** Steps taken this session, for the harness and the log. */
    private static volatile long pressureSteps;
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

    /**
     * Last seen value of the master switch, so the tick can spot the EDGE.
     * Deliberately not read from the options screen handler: the harness and
     * the benchmark flip the {@code meshelium.terrainDraw} property instead,
     * and that never passes through any screen.
     */
    private static boolean masterSwitchWas = true;

    /**
     * Last seen value of the greedy-meshing switch, watched here for the
     * same reason as the master switch above: the harness and the benchmark
     * flip {@code meshelium.greedyMeshing} as a property, which no screen
     * handler ever sees.
     *
     * <p>Null until the first tick sees it, and that first sight is never an
     * edge. A plain {@code false} default would read the first world of a
     * session with the setting ON as a switch-on and rebuild the terrain the
     * player just watched load, for nothing.</p>
     */
    private static Boolean greedyMeshingWas;

    /**
     * A re-encode is owed to the world. Deliberately NOT the seam's rebuild
     * request: that one carries handover semantics (it tells the player the
     * renderers are swapping and starts the ownership dance). Greedy meshing
     * changes what the sections CONTAIN, not who draws them, so it wants the
     * same {@code allChanged()} and none of the rest.
     */
    private static boolean pendingReencode;

    /** Arena-pressure backoff has already written one chat line this world. */
    private static boolean pressureChatted;

    /** The arena-shape report has been written once for this world. */
    private static boolean arenaShapeReported;

    /**
     * Render distance the player had before the MASTER SWITCH clamped it,
     * or 0 when nothing is owed back.
     *
     * <p>Read the big comment above {@link #ARENA_BACKOFF_PCT} before
     * touching this: automatic restore was built, measured, and deleted
     * once already, and it must stay deleted for the case it was deleted
     * for. That case is arena PRESSURE, where the distance came down
     * because memory ran out, so putting it back walks straight into the
     * distance that just failed to fit. The harness ping-ponged 48 to 40
     * and back every three seconds for a minute and a half.</p>
     *
     * <p>This is the other case entirely, and none of that reasoning
     * applies. The clamp here happens because Meshelium is switched OFF and
     * vanilla cannot draw past 32; the cause is a boolean the player
     * flipped, not a guess about memory, and when they flip it back the
     * cause is provably gone. Nothing is being predicted, so nothing can
     * ping-pong.</p>
     *
     * <p>Scoped hard: set ONLY by a clamp whose cause is the master switch,
     * never by the pressure backoff, never by the gate (a backend needs a
     * restart anyway), and never by the coverage guard or an error latch
     * (both last the world, so there is no moment they stop being true).</p>
     */
    private static int switchClampedFrom;

    /**
     * What the clamp actually wrote, so a restore can tell "the player has
     * not touched it" from "the player chose 32 themselves". Restoring over
     * a deliberate choice would be worse than not restoring at all.
     */
    private static int switchClampedTo;

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
        // CAP THE CAPTURE. This read range.maxInclusive() directly, which is
        // only vanilla's number if nothing widened the range before us, and
        // mod load order does not guarantee that. Bobby raises this same
        // ceiling, and if it got here first every use of vanillaMax
        // inherited ITS number: clamp-back would "restore" a struggling
        // player to 128 instead of 32, the pressure backoff's floor would
        // sit at 128 so the safety valve could never step down to anything
        // useful, and extendedWanted (configured > vanillaMax) would read
        // false for any ceiling under Bobby's, silently disabling Meshelium's
        // own extended distance.
        //
        // Only the NUMBER is capped. vanillaValues keeps the ValueSet object
        // exactly as found, so applyRange still restores whatever range that
        // mod installed and its feature keeps working; we simply stop
        // treating its ceiling as though vanilla had set it.
        int capturedMax = range.maxInclusive();
        vanillaMax = Math.min(capturedMax, VANILLA_RD_HARD_MAX);
        if (capturedMax > VANILLA_RD_HARD_MAX) {
            MesheliumClient.LOGGER.info(
                    "Meshelium: the render-distance range was already widened to {} before this "
                            + "mod looked at it (another mod, Bobby or similar). Treating {} as the "
                            + "vanilla ceiling for clamp-back and backoff purposes; the other mod's "
                            + "range is left intact", capturedMax, vanillaMax);
        }
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
        pressureChatted = false;
        arenaShapeReported = false;
        // Re-sync the edge detector rather than assume: a world can be
        // entered with the switch already off, and a stale `true` here would
        // fire a spurious swap on the first tick.
        masterSwitchWas = MesheliumConfig.terrainRenderingConfigured();
        // Same re-sync, same reason: a world entered with the setting already
        // flipped must not spend its first tick rebuilding everything.
        greedyMeshingWas = MesheliumConfig.greedyMeshingEnabled();
        pendingReencode = false;
    }

    // ------------------------------------------------------------------
    // The monitor (every client tick — a handful of field reads)
    // ------------------------------------------------------------------

    private static void onEndTick(Minecraft minecraft) {
        driveVanillaUploadSeamRecovery(minecraft);
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

        // Give back what the master switch took. Strictly AFTER applyRange,
        // because the option's range is only widened past 32 there and a set
        // before it would be clamped straight back down by the ValueSet.
        if (switchClampedFrom > 0 && extendedWanted && gateOk) {
            int current = options.renderDistance().get();
            if (current != switchClampedTo) {
                // The player moved the slider themselves while Meshelium was
                // off. That is a choice, and overwriting it would be worse
                // than never restoring; forget the debt.
                switchClampedFrom = 0;
            } else {
                int restore = Math.min(switchClampedFrom, configured);
                switchClampedFrom = 0;
                if (restore > current) {
                    options.renderDistance().set(restore);
                    options.save();
                    MesheliumClient.LOGGER.info(
                            "Meshelium put the render distance back to {} now that it is drawing "
                                    + "again (it was clamped to {} while switched off, because "
                                    + "vanilla cannot draw past {})",
                            restore, current, vanillaMax);
                    MesheliumNotify.error(TOAST_ID,
                            Component.translatable("meshelium.rd.restored.title", restore),
                            Component.translatable("meshelium.rd.restored.body"));
                    // Re-read, because everything below reasons about the
                    // distance we are actually on now.
                    return;
                }
            }
        }

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
            maybeBackOffForArenaPressure(minecraft, options, rd);
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
        MesheliumNotify.error(REJOIN_TOAST_ID,
                Component.translatable("meshelium.rd.rejoin.title"),
                Component.translatable("meshelium.rd.rejoin.body", rd, pinned.maxRd()));
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

    // ------------------------------------------------------------------
    // Arena pressure backoff (1.2)
    // ------------------------------------------------------------------

    /**
     * Back the render distance off before the arena runs out, instead of
     * dropping terrain once it has.
     *
     * <p>WHY THIS EXISTS. The arena ceiling is now clamped to what a shader
     * can address, about 4 GiB, and at render distance 120 a real world can
     * reach it. Before this, reaching it meant dropped sections and the
     * coverage guard flipping the whole renderer to passive: a cliff. The
     * owner's framing was the right one, that hitting the limit should feel
     * like the render distance being limited, because a player who turns
     * around and sees chunks failing to load will blame the mod, and they
     * will be right to.</p>
     *
     * <p>WHY IT LOWERS THE OPTION rather than evicting distant sections
     * directly, which sounds equivalent and is not. Freeing a section
     * vanilla still believes it handed us means nothing ever asks for it
     * back, which is precisely the permanent-hole failure this release
     * fixed. Lowering the option makes VANILLA release the far chunks
     * through {@code reset()}, the path wave 11 already handles, and
     * vanilla then knows to rebuild them when the player returns.</p>
     *
     * <p>IT ONLY EVER GOES DOWN. See the note on the constants above for
     * why the automatic restore was built, measured, and then deleted. The
     * value is written through the ordinary render-distance option and
     * saved, so the Video Settings slider shows the new number and the
     * player can drag it straight back up whenever they want to.</p>
     *
     * <p>The cooldown is not decoration: freed arena space only returns
     * after the fence lag, so an immediate re-read still looks full and a
     * naive loop would walk the distance to the floor in a second. Past
     * {@link #ARENA_CRITICAL_PCT} that wait is cut short, because a world
     * streaming in was measured crossing the whole remaining gap inside one
     * polite cooldown.</p>
     */
    private static void maybeBackOffForArenaPressure(Minecraft minecraft, Options options,
            int rd) {
        var counters = com.deds.meshelium.terrain.host.TerrainResidency.counters();
        // The EFFECTIVE ceiling, not the static one: how far the arena could
        // actually grow on this machine right now. When the card has memory
        // to spare these are the same number and nothing changes. When it is
        // genuinely short the effective ceiling collapses toward what is
        // already committed, so this existing trip fires BEFORE terrain
        // starts dropping - which matters because a dropped section trips
        // the coverage guard, and that means passive for the whole world
        // plus a clamp to 32. Stepping down by 8 is far kinder than the
        // cliff it prevents, and the slider gives it straight back.
        long ceiling = MesheliumScaling.effectiveCeilingBytes(counters.arenaCapacityBytes());
        if (ceiling <= 0) {
            return;
        }
        long used = counters.arenaUsedBytes();
        long pct = used * 100L / ceiling;

        // How long to wait is decided HERE, from the pressure on this tick,
        // not at the moment the last step was taken. A world streaming in
        // can cross the whole remaining gap inside one polite cooldown.
        backoffElapsedTicks++;
        int required = pct >= ARENA_CRITICAL_PCT
                ? BACKOFF_CRITICAL_COOLDOWN_TICKS
                : BACKOFF_COOLDOWN_TICKS;
        if (backoffElapsedTicks < required) {
            return;
        }
        if (pct < ARENA_BACKOFF_PCT || rd <= vanillaMax) {
            return;
        }

        int target = Math.max(vanillaMax, rd - BACKOFF_STEP);
        backoffElapsedTicks = 0;
        pressureSteps++;
        options.renderDistance().set(target);
        options.save(); // save() is what re-broadcasts ClientInformation
        showRenderDistanceChange(minecraft, target);
        MesheliumClient.LOGGER.info(
                "Meshelium lowered the render distance {} -> {}: terrain memory is at {}% of the "
                        + "{} MiB ceiling. The far chunks are released through vanilla's own path. "
                        + "This does not go back up on its own - raise it in Video Settings when "
                        + "you want it back",
                rd, target, pct, ceiling >> 20);
        reportArenaShapeOnce(counters, ceiling);
    }

    /**
     * Say what the arena actually looks like at the one moment it matters.
     *
     * <p>Pressure is the only time the shape of the arena decides anything,
     * and it is the moment we have never measured. The mod has always
     * printed live-versus-committed, which cannot answer the question that
     * follows from here, namely whether the memory could be won back and
     * how. So this splits it three ways:</p>
     *
     * <ul>
     *   <li><b>holes</b>: free space BELOW the high-water mark, scattered
     *   between live sections. The only thing compaction could ever recover,
     *   and it would cost a stutter of device-to-device copies to do it.</li>
     *   <li><b>tail</b>: free space above the high-water mark. Already free,
     *   recoverable with no copying whatsoever.</li>
     *   <li><b>empty top blocks</b>: whole blocks that could go straight
     *   back to the driver today if a release path existed. Zero copy.</li>
     * </ul>
     *
     * <p>Nothing acts on this yet, deliberately. A block release path is
     * worth building only if this number turns out to be non-trivial in a
     * real session, and a compactor only if the holes are. Measuring first
     * is cheaper than being wrong about which one to write.</p>
     *
     * <p>The ceiling quoted here is the EFFECTIVE ceiling this call site
     * already computed, not the static one the residency stats line prints.
     * They differ, and not saying which would make the two logs look like
     * they disagree.</p>
     */
    private static void reportArenaShapeOnce(
            com.deds.meshelium.terrain.host.TerrainResidency.Counters counters, long ceiling) {
        if (arenaShapeReported) {
            return;
        }
        arenaShapeReported = true;
        long holes = Math.max(0, counters.arenaExtentBytes() - counters.arenaUsedBytes());
        long tail = Math.max(0, counters.arenaCapacityBytes() - counters.arenaExtentBytes());
        MesheliumClient.LOGGER.info(
                "Meshelium arena shape at pressure: live {} MiB, holes {} MiB, unused tail {} MiB, "
                        + "committed {} MiB of a {} MiB effective ceiling, across {} block(s) of "
                        + "which {} at the top are completely empty. Holes are what compaction "
                        + "could recover and it would cost copies; the empty top blocks could go "
                        + "back to the driver for free. Neither is acted on yet: this line exists "
                        + "to decide which is worth building",
                counters.arenaUsedBytes() >> 20, holes >> 20, tail >> 20,
                counters.arenaCapacityBytes() >> 20, ceiling >> 20,
                counters.arenaBlocks(), counters.emptyTopBlocks());
    }

    /**
     * Put vanilla's terrain back after the upload seam stopped suppressing.
     *
     * <p>On the CLIENT TICK deliberately, never from the pump. The rebuild
     * call constructs a fresh section grid - at render distance 120 that is
     * 241 x 241 x 24, about 1.4 million slots - and the pump runs inside
     * LevelRenderer.render's lock window, where every build worker is
     * waiting on the same lock to stage its uploads. Doing it there would
     * stall all of them for the duration.</p>
     *
     * <p>The request is only cleared once the call RETURNS, so a throw
     * retries next tick rather than losing the recovery for the world.</p>
     */
    private static void driveVanillaUploadSeamRecovery(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.levelRenderer == null) {
            // DELIBERATELY NOT freeing the arena here, though it looks like
            // the obvious place and the owner asked whether it should be.
            //
            // The observation behind the question is real: leaving a world
            // does not return the terrain memory, because vanilla's dispose
            // only fires from LevelExtractor.extract(), which does not run
            // without a level, so the teardown waits for the NEXT world.
            //
            // Freeing early was tried and a retention test rejected it:
            // "vanilla keeps its meshes at the title screen but Meshelium's
            // store went to zero". Vanilla holds its terrain at the menu on
            // purpose, so rejoining the world you just left is instant, and
            // Meshelium's residency MIRRORS vanilla's. Break the mirror and
            // vanilla still believes its meshes are uploaded while ours are
            // gone: rejoin, vanilla sees no reason to rebuild, and the
            // terrain is missing. Trading a fast rejoin for memory nobody is
            // using at the menu is a bad trade twice over.
            return;
        }
        // THE OFF EDGE NOBODY WAS WATCHING.
        //
        // The ownership rule and its demote() lived in TerrainDrawer.enabled(),
        // which had ZERO callers in the whole repository - the draw hook reads
        // MesheliumConfig.terrainRenderingEnabled() directly and never touches
        // the drawer. So switching Meshelium off with the seam armed stopped
        // Meshelium drawing that same frame, never demoted, and left vanilla's
        // uploads cancelled forever. Nobody drew. Permanently. That is exactly
        // the see-through world the owner reported, and no amount of fixing
        // the handover helped, because the handover was never started.
        //
        // Here instead, because this tick runs unconditionally. It also covers
        // the harness flipping the meshelium.terrainDraw PROPERTY, which the
        // options screen handler never sees.
        boolean nowEnabled = MesheliumConfig.terrainRenderingConfigured();
        if (nowEnabled != masterSwitchWas) {
            masterSwitchWas = nowEnabled;
            if (nowEnabled) {
                // Coming back. Re-arm the seam if the player wants vanilla's
                // duplicate freed; either way the invalidation below is what
                // makes Meshelium's own copy exist again.
                com.deds.meshelium.terrain.host.VanillaUploadSeam.onTerrainRenderingEnabled();
                com.deds.meshelium.terrain.host.VanillaUploadSeam
                        .requestVanillaRebuild("Meshelium terrain rendering switched on");
            } else {
                com.deds.meshelium.terrain.host.VanillaUploadSeam
                        .demote("terrain rendering turned off");
                com.deds.meshelium.terrain.host.VanillaUploadSeam
                        .requestVanillaRebuild("Meshelium terrain rendering switched off");
            }
        }
        // Greedy meshing decides what a section's geometry IS, so a flip only
        // reaches the world through a rebuild. Sections already compiled are
        // never recompiled on their own, so without this the setting would
        // appear to do nothing until the player walked away and back.
        boolean greedyNow = MesheliumConfig.greedyMeshingEnabled();
        if (greedyMeshingWas == null) {
            greedyMeshingWas = greedyNow;
        } else if (greedyNow != greedyMeshingWas) {
            greedyMeshingWas = greedyNow;
            // Only while Meshelium is the one encoding. Switched off, the
            // master-switch edge above already rebuilds on the way back in;
            // on OpenGL there is nothing of ours in the sections at all, and
            // rebuilding somebody else's terrain is not "disable itself
            // completely".
            if (nowEnabled
                    && MesheliumGate.state() == MesheliumGate.State.VULKAN_MESH_SHADERS) {
                pendingReencode = true;
                MesheliumClient.LOGGER.info(
                        "Meshelium greedy meshing switched {}; re-encoding the loaded terrain",
                        greedyNow ? "on" : "off");
            }
        }
        boolean seamRebuild =
                com.deds.meshelium.terrain.host.VanillaUploadSeam.consumeRebuildRequest();
        if (seamRebuild || pendingReencode) {
            try {
                // allChanged lives on LevelExtractor in 26.2, NOT on
                // LevelRenderer where the plan placed it. Verified by javap;
                // Minecraft.levelExtractor is a public final field.
                minecraft.levelExtractor.allChanged();
                pendingReencode = false;
                if (!seamRebuild) {
                    // A plain re-encode: the seam said nothing, so neither of
                    // the two handover messages below applies.
                } else if (com.deds.meshelium.terrain.host.VanillaUploadSeam.armed()) {
                    // Armed: the same call, used the other way round. Every
                    // section is dropped, which empties vanilla's heaps, and
                    // the seam stops them refilling.
                    MesheliumClient.LOGGER.info(
                            "Meshelium dropped vanilla's terrain sections to empty its heaps; "
                                    + "vanilla terrain memory was {} MiB and should fall to near "
                                    + "zero over the next few seconds",
                            com.deds.meshelium.VanillaTerrainCensus.committedBytes() >> 20);
                } else {
                    MesheliumClient.LOGGER.info(
                            "Meshelium asked vanilla to rebuild its terrain after the upload seam "
                                    + "stood down; Meshelium keeps drawing until it looks complete");
                }
            } catch (Throwable t) {
                if (seamRebuild) {
                    com.deds.meshelium.terrain.host.VanillaUploadSeam.reinstateRebuildRequest();
                }
                MesheliumClient.LOGGER.error(
                        "Meshelium could not ask vanilla to rebuild; retrying next tick", t);
                return;
            }
        }
        com.deds.meshelium.terrain.host.VanillaUploadSeam.noteRebuildProgress(
                minecraft.levelRenderer.hasRenderedAllSections());
    }

    /**
     * Make the change VISIBLE. {@code set()} already updates what the
     * render-distance slider reads, so simply opening Video Settings shows
     * the new number ({@code OptionInstance.createButton} reads
     * {@code get()} at widget creation). The gap is a screen that is
     * ALREADY OPEN: its slider widget was built from the old value and
     * nothing rebuilds it, so a player sitting in Video Settings while the
     * world streams in would watch their distance change with the slider
     * still claiming the old number.
     *
     * <p>{@code Screen.resize} is the sanctioned rebuild ({@code resize} to
     * {@code repositionElements} to {@code rebuildWidgets}, all three
     * verified in the 26.2 jar), and it is restricted to
     * {@code OptionsSubScreen} on purpose: that covers vanilla's Video
     * Settings, which owns the slider, while leaving Meshelium's own screen
     * alone, because rebuilding that one would discard whatever the player
     * is part-way through typing into a value box.</p>
     */
    private static void showRenderDistanceChange(Minecraft minecraft, int target) {
        if (minecraft.gui == null) {
            return;
        }
        // Toast every time, chat only the FIRST step of a world. Toasts
        // coalesce on their id, so a stepped backoff shows as one toast that
        // keeps updating; chat does not coalesce, and eight identical lines
        // scrolling past would read as eight separate faults.
        if (pressureChatted) {
            SystemToast.add(minecraft.gui.toastManager(), TOAST_ID,
                    Component.translatable("meshelium.rd.pressure.title", target),
                    Component.translatable("meshelium.rd.pressure.body"));
        } else {
            pressureChatted = true;
            MesheliumNotify.error(TOAST_ID,
                    Component.translatable("meshelium.rd.pressure.title", target),
                    Component.translatable("meshelium.rd.pressure.body"));
        }
        // 26.2 moved the current screen onto Gui: Minecraft has no `screen`
        // field any more and no getter returning one, while Gui has both
        // `screen()` and `setScreen` (javap on the merged 26.2 jar).
        net.minecraft.client.gui.screens.Screen screen = minecraft.gui.screen();
        if (screen instanceof net.minecraft.client.gui.screens.options.OptionsSubScreen
                && minecraft.getWindow() != null) {
            screen.resize(minecraft.getWindow().getGuiScaledWidth(),
                    minecraft.getWindow().getGuiScaledHeight());
        }
    }

    /** Steps the arena-pressure backoff has taken this session (harness). */
    public static long pressureSteps() {
        return pressureSteps;
    }

    private static void clampBack(Minecraft minecraft, Options options,
            MesheliumGate.State state, boolean extendedWanted) {
        int target = vanillaMax;
        int before = options.renderDistance().get();

        boolean guardPassive = false;
        if (state == MesheliumGate.State.VULKAN_MESH_SHADERS) {
            guardPassive = extendedWanted && minecraft.level != null
                    && com.deds.meshelium.vk.TerrainDrawer.coveragePassive();
        }
        // Remember the distance ONLY when the master switch is the reason.
        // The gate needs a restart, and the coverage guard and the error
        // latch both last the rest of the world, so for those there is no
        // later moment at which the cause stops being true and a restore
        // would be honest. This runs every tick while disabled, so the
        // `before > target` test is what stops the second pass overwriting
        // the memory with the clamped value.
        boolean switchIsTheReason = state == MesheliumGate.State.VULKAN_MESH_SHADERS
                && !guardPassive
                && !MesheliumConfig.terrainRenderingConfigured();
        if (switchIsTheReason && before > target) {
            switchClampedFrom = before;
            switchClampedTo = target;
        }

        options.renderDistance().set(target);
        options.save();
        sessionClamps++;

        if (clampNoticeArmed) {
            clampNoticeArmed = false;
            String bodyKey = guardPassive ? "meshelium.rd.clamped.passive" : "meshelium.rd.clamped.off";
            MesheliumClient.LOGGER.warn(
                    "Meshelium clamped the render distance back to {} (gate={}, terrainEnabled={}, "
                            + "configuredMax={}, guardPassive={}) — the extended range only takes "
                            + "effect while Meshelium draws (wave-10 clamp-back invariant)",
                    target, state, MesheliumConfig.terrainRenderingEnabled(),
                    MesheliumConfig.maxRenderDistanceConfigured(), guardPassive);
            MesheliumNotify.error(TOAST_ID,
                    Component.translatable("meshelium.rd.clamped.title", target),
                    Component.translatable(bodyKey));
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
