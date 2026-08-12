/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium;

import com.deds.meshelium.fabric.MesheliumClient;

/**
 * Wave-10 buffer sizing, reworked in wave 13: every capacity that used to
 * be a wave-≤9 literal (2048 regions, 512 per-frame list slots, the
 * 256 MiB arena) derives from a per-world pinned render distance and is
 * read by every consumer through {@link #current()}.
 *
 * <h2>Wave-13 change: pin from the OPTION, not the config ceiling</h2>
 * Wave 10 pinned from {@code maxRenderDistanceConfigured()} — the config
 * CEILING — which forced the ceiling to double as an enable switch
 * (default 32) or every world would pay ceiling-sized buffers. Wave 13
 * pins from what the player actually asked for:
 * <pre>
 * pinnedRd(option) = option ≤ 32 ? 32 (the standard snapshot)
 *                  : min(config ceiling, nextMultipleOf8Above(option))
 * </pre>
 * — {@code nextMultipleOf8Above} is the STRICTLY-next lattice step
 * (33→40, 40→48, 48→56), a modest headroom band so a small mid-world
 * slider raise stays inside the pinned budget. A player at rd 12 under
 * the new default ceiling of 96 pins the exact wave-≤9 standard sizes;
 * raising the vanilla slider mid-world past the pinned budget keeps the
 * drawer working at pinned capacity (the wave-5/6 fail-opens; a true
 * budget overflow still ends in the honest guard-trip + clamp-back) and
 * {@code MesheliumExtendedRd} shows the once-per-world "rejoin to apply
 * fully" hint. Rejoining re-pins from the new option value.
 *
 * <h2>The formulas (docs/EXTENDED-RENDER-DISTANCE.md carries the table)</h2>
 * <pre>
 * regionsTouched(rd) = (ceil((2·rd+1)/8) + 1)² × 7
 *     — regions are 8×4×8 sections; the vanilla grid spans 2·rd+1 chunks
 *       per axis, +1 region for grid/region straddle; a 24-section
 *       overworld touches ceil(24/4)+1 = 7 Y-region rows (the same
 *       derivation RegionStore's wave-3b constant used: rd 32 → 700).
 * maxRegions(rd)   = rd ≤ 32 ? 2048 : max(2048, roundUp256(2 × regionsTouched(rd)))
 *     — 2× headroom over the touchable grid (wave-3b shipped ~3× at
 *       rd 32; retention is grid-bounded, recon Q4.3, so 2× is already
 *       conservative). Overflow still drops-with-counter → coverage
 *       guard → passive + clamp-back; budgets cost Meshelium a world,
 *       never pixels.
 * dispatchCapacity = rd ≤ 32 ? 512 : maxRegions(rd)
 *     — per-frame mask/occlusion-list slots. Extended mode sizes the
 *       lists to the whole region budget, so the wave-5/6 fail-open
 *       overflow paths become structurally unreachable there; the lists
 *       then exceed the 16 KiB spec-min UBO range and move to Meshelium's
 *       own host-visible STORAGE ring ({@code MesheliumFrameLists}) with
 *       SSBO-variant pipelines.
 * arenaBytes       = 256 MiB, EVERY pin — the INITIAL size only (wave 14).
 *       Waves 10/13 predicted the resident set with a quadratic formula
 *       anchored on the rd-32 plains bench (~255 quads/section) — and the
 *       owner's first real overworld session proved real terrain runs
 *       several-fold denser, overflowing the 256 MiB standard pin on a
 *       16 GiB card with ~15 GiB free (droppedArenaFull → guard →
 *       "passive for this world"). Wave 14 stops predicting density: the
 *       arena starts modest and GROWS on demand (×1.5, grow-and-copy in
 *       the pump, {@code TerrainResidency}) up to a ceiling derived from
 *       the DEVICE ({@link #arenaCeilingBytes()}), so the only worlds
 *       that can trip the guard on arena bytes are worlds that genuinely
 *       exceed {@value #ARENA_CEILING_HEAP_PCT}% of the card. Density
 *       arithmetic + design: docs/VANILLA-SECTION-BUILD.md wave-14 note.
 * staging          = 32 MiB, unchanged — it bounds per-frame streaming
 *       RATE (ring turnover), not the resident set; a full ring already
 *       backlogs gracefully (wave 3b), and the harness's rd-48 leg
 *       watches for drops.
 * </pre>
 *
 * Values at the corners (section records = 8 KiB/region, stamps = 1 KiB
 * per region per buffer; arena = initial, elastic to the device ceiling):
 * <pre>
 * pinned 32:  2048 regions,  512 slots, arena starts 256 MiB (byte-identical wave ≤9 standup)
 * pinned 48:  2816 regions, 2816 slots, arena starts 256 MiB
 * pinned 64:  4608 regions, 4608 slots, arena starts 256 MiB
 * pinned 96:  9472 regions, 9472 slots, arena starts 256 MiB
 * pinned 120: 14336 regions, 14336 slots, arena starts 256 MiB (wave-15
 *             custom-cap hard max; 112 MiB section records)
 * </pre>
 *
 * <h2>Pinning discipline</h2>
 * {@link #pinForWorld(int)} runs at {@code MesheliumTerrainGpu.create()} —
 * the first GPU standup of a world, strictly before the arena attaches,
 * before {@code TerrainOcclusion}/{@code MesheliumFrameLists} exist and
 * while the fresh {@code RegionStore} is still empty — so every consumer
 * of one world sees ONE consistent snapshot. The caller passes the RAW
 * vanilla option value at standup, deliberately not
 * {@code getEffectiveRenderDistance()}: the effective value is
 * {@code min(option, server radius)}, and at the exact standup moment
 * the server-radius half can still be a stale login value (the radius
 * packet and the tickServer follow race the first frame), while the raw
 * option is the player's stated intent — and on small-radius multiplayer
 * servers the option is also the honest budget wish for wave-11
 * retention. {@link #current()} before any pin returns the STANDARD
 * snapshot (pure-CPU tests; nothing GPU exists yet by construction, and
 * with the wave-13 ceiling default of 96 a config-derived unpinned view
 * would claim 9,472-region record sizes no world has paid for).
 */
public final class MesheliumScaling {

    /**
     * One world's pinned sizes. {@code maxRd} is the PINNED render
     * distance. {@code arenaBytes} is the arena's INITIAL size since wave
     * 14 (the arena is elastic; records/lists stay pinned) — kept in the
     * snapshot so the standup log and the tests read one source.
     */
    public record Snapshot(int maxRd, boolean extended, int maxRegions,
            int dispatchCapacity, long arenaBytes) {
    }

    /** Wave-≤9 literals — the extended==false snapshot reproduces exactly these. */
    public static final int STANDARD_MAX_REGIONS = 2048;
    public static final int STANDARD_DISPATCH_CAPACITY = 512;
    public static final long STANDARD_ARENA_BYTES = 256L << 20;

    // ------------------------------------------------------------------
    // Wave-14: the arena ceiling comes from the DEVICE, not a formula
    // ------------------------------------------------------------------

    /** Default ceiling = this % of the largest DEVICE_LOCAL heap. */
    public static final int ARENA_CEILING_HEAP_PCT = 50;
    /** Absolute ceiling floor — a tiny reported heap still gets a workable arena. */
    public static final long ARENA_CEILING_FLOOR_BYTES = 256L << 20;
    /**
     * Ceiling when no heap was ever probed (a Vulkan world standing up
     * before the device hook recorded — structurally impossible today,
     * kept as a defensive constant): the wave-13 cap, the last honest
     * number this project shipped without a probe.
     */
    public static final long ARENA_CEILING_FALLBACK_BYTES = 1024L << 20;

    /**
     * The terrain arena's growth ceiling in bytes, resolved per call (the
     * growth path asks at most once per pump — property flips are live,
     * the harness's requirement). Resolution order:
     * <ol>
     *   <li>{@code meshelium.test.arenaMiB} — the wave-8 torture override:
     *       initial AND ceiling both collapse to it, so the guard leg
     *       still deterministically forces "growth exhausted" (the wave-14
     *       trip condition) exactly where it used to force "arena
     *       full".</li>
     *   <li>{@code meshelium.tune.arenaCeilingMiB} — operator override
     *       (coordinator/power user; also the guard-leg knob when a
     *       nonstandard initial is in play).</li>
     *   <li>max({@value #ARENA_CEILING_HEAP_PCT}% of the largest
     *       DEVICE_LOCAL heap, 256 MiB) — the wave-14 default. The heap
     *       SIZE is a static hardware fact
     *       ({@code vkGetPhysicalDeviceMemoryProperties}, core 1.0),
     *       unlike the {@code vmaGetHeapBudgets} usage estimates wave 10
     *       rightly rejected (vanilla's allocator lacks
     *       VK_EXT_memory_budget — that rejection stands; this is a
     *       different query). Integrated GPUs report their shared
     *       system-memory heap: the fraction then bounds Meshelium's share
     *       of SYSTEM memory — documented caveat, and the reason the
     *       default is a fraction rather than "heap minus slack".</li>
     * </ol>
     */
    public static long arenaCeilingBytes() {
        long testMiB = Long.getLong("meshelium.test.arenaMiB", 0L);
        if (testMiB > 0) {
            return testMiB << 20;
        }
        long overrideMiB = Long.getLong("meshelium.tune.arenaCeilingMiB", 0L);
        if (overrideMiB > 0) {
            return overrideMiB << 20;
        }
        long heap = MesheliumVulkanState.deviceLocalHeapBytes();
        if (heap <= 0) {
            return ARENA_CEILING_FALLBACK_BYTES;
        }
        // Whole MiB — growth fills/copies then stay trivially 4-aligned.
        long fraction = (heap / 100L * ARENA_CEILING_HEAP_PCT) >> 20 << 20;
        return Math.max(ARENA_CEILING_FLOOR_BYTES, fraction);
    }

    /**
     * The arena's INITIAL allocation in bytes: {@code meshelium.test.arenaMiB}
     * (torture: tiny initial AND ceiling) ?? {@code meshelium.tune.arenaInitialMiB}
     * (the growth-leg knob: tiny initial, normal ceiling) ?? the pinned
     * snapshot's 256 MiB — always clamped to the ceiling (an iGPU whose
     * ceiling resolves below 256 MiB starts at the ceiling and simply
     * never grows).
     */
    public static long arenaInitialBytes() {
        long ceiling = arenaCeilingBytes();
        long testMiB = Long.getLong("meshelium.test.arenaMiB", 0L);
        if (testMiB > 0) {
            return testMiB << 20; // == ceiling by construction above
        }
        long initialMiB = Long.getLong("meshelium.tune.arenaInitialMiB", 0L);
        long initial = initialMiB > 0 ? initialMiB << 20 : current().arenaBytes();
        return Math.min(initial, ceiling);
    }

    private static final Snapshot STANDARD = new Snapshot(
            MesheliumConfig.MIN_MAX_RENDER_DISTANCE, false,
            STANDARD_MAX_REGIONS, STANDARD_DISPATCH_CAPACITY, STANDARD_ARENA_BYTES);

    private static volatile Snapshot current;

    private MesheliumScaling() {
    }

    /** The pinned world snapshot; the STANDARD snapshot before the first pin. */
    public static Snapshot current() {
        Snapshot pinned = current;
        return pinned != null ? pinned : STANDARD;
    }

    /**
     * The pinned snapshot, or null before the first world standup —
     * wave-13's mid-world-raise hint keys on this (identity per pin):
     * a hint may only fire against a REAL world budget, never the
     * unpinned fallback.
     */
    public static Snapshot pinned() {
        return current;
    }

    /**
     * Pin (or re-pin) at world standup — render thread, called by
     * {@code MesheliumTerrainGpu.create()} with the raw vanilla option
     * value at that moment. Logs the derivation once per pin so every
     * scaled buffer's standup line has it next to it.
     */
    public static Snapshot pinForWorld(int optionRd) {
        Snapshot snapshot = compute(pinnedRd(optionRd));
        current = snapshot;
        if (snapshot.extended()) {
            MesheliumClient.LOGGER.info(
                    "Meshelium extended render distance pinned for this world: option {} chunks "
                            + "-> pinned {} (next-8 headroom, ceiling {}) -> maxRegions={} "
                            + "({} MiB section records), dispatchCapacity={} (SSBO frame "
                            + "lists), arena starts at {} MiB and grows on demand to the "
                            + "{} MiB device ceiling (wave 14; raising the slider past the "
                            + "pinned budget mid-world grows the records live, wave 15)",
                    optionRd, snapshot.maxRd(), MesheliumConfig.maxRenderDistanceConfigured(),
                    snapshot.maxRegions(), (snapshot.maxRegions() * 8192L) >> 20,
                    snapshot.dispatchCapacity(), snapshot.arenaBytes() >> 20,
                    arenaCeilingBytes() >> 20);
        } else {
            MesheliumClient.LOGGER.info(
                    "Meshelium scaling pinned for this world: option {} chunks -> the standard "
                            + "wave-<=9 sizes (2048 regions, 512 dispatch slots, arena starts "
                            + "at 256 MiB and grows on demand to the {} MiB device ceiling — "
                            + "wave 14; extended record sizing pins only when the option "
                            + "exceeds 32 at world standup)",
                    optionRd, arenaCeilingBytes() >> 20);
        }
        return snapshot;
    }

    /**
     * Wave-15: the snapshot a LIVE mid-world raise to {@code optionRd}
     * would need — pure (no state change); the pump compares it against
     * {@link #current()} to size the record grow. Same formula as
     * {@link #pinForWorld}.
     */
    public static Snapshot computeForOption(int optionRd) {
        return compute(pinnedRd(optionRd));
    }

    /**
     * Wave-15: swap the pinned snapshot mid-world after the GPU record
     * buffers grew to the new sizes (render thread, under the residency
     * LOCK, called by {@code TerrainResidency}'s pump ONLY — the ordering
     * is load-bearing: every consumer that live-reads {@link #current()}
     * for a BUDGET ({@code RegionStore.maxRegions()},
     * {@code TerrainOcclusion.listCapacity()}) must never see the grown
     * budget before the grown buffers exist). A fresh snapshot identity
     * on purpose: the rejoin-hint keying and the harness's grow probes
     * both key on it.
     */
    public static void growPinned(Snapshot grown) {
        current = grown;
        MesheliumClient.LOGGER.info(
                "Meshelium scaling grown mid-world: pinned {} chunks -> maxRegions={} "
                        + "({} MiB section records), dispatchCapacity={} (live render-distance "
                        + "raise, wave 15; no rejoin needed)",
                grown.maxRd(), grown.maxRegions(), (grown.maxRegions() * 8192L) >> 20,
                grown.dispatchCapacity());
    }

    /**
     * The wave-13 pin formula: standard at option ≤ 32; otherwise the
     * strictly-next multiple of 8 above the option (a modest headroom
     * band: 33→40, 40→48, 48→56), capped by the config ceiling.
     */
    static int pinnedRd(int optionRd) {
        if (optionRd <= MesheliumConfig.MIN_MAX_RENDER_DISTANCE) {
            return MesheliumConfig.MIN_MAX_RENDER_DISTANCE;
        }
        int next8 = (optionRd / 8 + 1) * 8;
        return Math.min(MesheliumConfig.maxRenderDistanceConfigured(), next8);
    }

    private static Snapshot compute(int pinnedRd) {
        if (pinnedRd <= MesheliumConfig.MIN_MAX_RENDER_DISTANCE) {
            return STANDARD;
        }
        int maxRegions = Math.max(STANDARD_MAX_REGIONS, roundUp(2 * regionsTouched(pinnedRd), 256));
        // Wave-14: the arena no longer scales with rd — every pin starts
        // at the standard 256 MiB and grows on demand (the wave-10/13
        // quadratic density prediction is retired; density arithmetic in
        // docs/VANILLA-SECTION-BUILD.md wave-14 note).
        return new Snapshot(pinnedRd, true, maxRegions, maxRegions, STANDARD_ARENA_BYTES);
    }

    /** (ceil((2·rd+1)/8)+1)² × 7 — see the class javadoc derivation. */
    static int regionsTouched(int rd) {
        int chunks = 2 * rd + 1;
        int horizontal = (chunks + 7) / 8 + 1;
        return horizontal * horizontal * 7;
    }

    private static int roundUp(int value, int multiple) {
        return (value + multiple - 1) / multiple * multiple;
    }
}
