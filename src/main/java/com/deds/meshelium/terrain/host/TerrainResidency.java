/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.terrain.host;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumScaling;
import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.terrain.EncodedSectionMesh;
import com.deds.meshelium.terrain.TerrainArena;
import com.deds.meshelium.terrain.TranslucentPrefix;
import com.deds.meshelium.terrain.TerrainVertexCodec;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The wave-3b CPU-side residency store: every {@code CompiledSectionMesh}
 * vanilla keeps alive has (or is queued to get) a Meshelium 16-byte-vertex
 * copy in the terrain arena plus a 32-byte section record in its region's
 * CPU mirror — keyed by the mesh object itself, the exact key vanilla's
 * own uber-buffer {@code allocationMap} uses, so Meshelium's lifetimes equal
 * vanilla's by construction (section-build doc Q3.1/Q4.3).
 *
 * <p><b>No LWJGL/Vulkan imports here</b> — this class is reachable from
 * mixin bodies that also run (gated, early-returning) on the OpenGL
 * backend, and from the GL gametest that asserts dormancy. The GPU side
 * lives behind {@link TerrainGpuHost}.</p>
 *
 * <h2>Threading and lock order</h2>
 * <ul>
 *   <li>{@link #enqueueUpload}: build threads (FJP workers, or the render
 *       thread via compileSync). No vanilla lock held (the ctor-TAIL hook
 *       site precedes any {@code copyLock} acquisition in doTask).</li>
 *   <li>{@link #onMeshReleased}: any thread, always under vanilla's
 *       {@code copyLock} (doc 1.6 — every release site holds it).</li>
 *   <li>{@link #pump}: render thread only, inside vanilla's
 *       {@code dispatcher.lock()} window (which IS {@code copyLock},
 *       bytecode) — so releases can never interleave with a pump.</li>
 * </ul>
 * All of them take {@code LOCK} internally: the order is always vanilla's
 * {@code copyLock} (when held at all) → Meshelium's {@code LOCK}, never the
 * reverse — Meshelium's lock is innermost, and no Meshelium code calls back
 * into vanilla while holding it (doc 5.3's deadlock discipline).
 *
 * <h2>Free-fence discipline ({@code FREE_FRAME_LAG} = 3)</h2>
 * A released mesh's arena range and a consumed staging span become
 * reusable only when the GPU provably finished the last submission that
 * could touch them. Vanilla runs 2 submits in flight and CPU-waits on
 * submit S's timeline value while closing submit S+2 (frame-path Q1.2),
 * and every frame ends with ≥1 submit — so work last referenced in pump
 * frame F is complete by the pump of frame F+3: 2 in flight + 1 safety.
 * Frees are parked in per-frame epochs; the pump moves expired epochs into
 * {@link TerrainArena#free} and immediately {@link TerrainArena#releasePending()}s
 * them (3a's two-phase free — the epoch queue IS the fence gate the 3a
 * Javadoc demanded, so "releasePending is only as good as your fence
 * discipline" is discharged here, in one place).
 *
 * <h2>Wave-11 — retained terrain (Nvidium's "infinite horizon")</h2>
 * Vanilla frees section meshes for two very different reasons, and since
 * wave 11 the store tells them apart at the {@code releaseSectionMesh}
 * hook (complete caller census re-verified against the jar, wave-11 note
 * in docs/VANILLA-SECTION-BUILD.md):
 * <ul>
 *   <li><b>(a) Distance/reposition</b> — {@code RenderSection.reset()}
 *       (called ONLY from {@code setSectionNode} on grid reposition and
 *       from {@code ViewArea.releaseAllBuffers()}; jar-wide census). The
 *       reset-scoped mixin hooks bracket it with a thread-local depth, so
 *       releases arriving inside a reset are distance-class: the entry is
 *       ORPHANED — moved from the mesh-identity map to the position-keyed
 *       {@code retained} map, stamped {@code orphanedAtMillis}, its region
 *       slot and arena range kept — and keeps drawing.</li>
 *   <li><b>(b) Replacement/cancel</b> — every release OUTSIDE a reset
 *       ({@code checkSectionMesh} after promotion, {@code doTask}'s
 *       empty-mesh and cancelled-mid-copy paths): the normal free, exactly
 *       as before. Rebuild ordering is fixed by bytecode: the NEW mesh's
 *       ctor (doTask ip 161) precedes every old-mesh release site (ip 209
 *       / checkSectionMesh ip 86), so the tap always parks the successor
 *       before the predecessor dies; whichever of {old-release, new-upload}
 *       lands first, the wave-3b slot-steal machinery keeps one owner. A
 *       new mesh arriving at a RETAINED position supersedes it: the slot
 *       steal in the upload path frees the retained copy through the
 *       normal epochs; an EMPTY recompile at a retained position (no
 *       enqueue ever happens for empty results) supersedes through
 *       {@link #onSectionCompiledEmpty} — the build tap signals the
 *       position so stale geometry can never outlive a dig-out.</li>
 *   <li><b>(c) Dispose</b> — retention is per-WORLD: the per-level
 *       renderer's dispose drops retained copies with everything else
 *       (cross-dimension bleed-through would render nether terrain in the
 *       overworld; the per-level dispose is exactly the right boundary,
 *       section-build note 11).</li>
 * </ul>
 * <b>Eviction (the wave's central safety rule):</b> retention must never
 * trip the wave-8 coverage guard. The pump sweeps the retained set —
 * insertion-ordered, which IS oldest-first because orphan stamps come from
 * a monotonic clock — for (i) age when a limit is configured (0 = no
 * limit, the default), and (ii) ARENA/REGION pressure regardless of any
 * limit: past the high-water marks, oldest retained evict BEFORE a live
 * section can be dropped, and an alloc/budget failure with retained
 * entries present force-evicts retained and REQUEUES the section instead
 * of dropping it (no drop counter moves, the guard stays clean). Only
 * when nothing retained is left do drops count — wave-8 behaviour exactly.
 *
 * <h2>Wave-14 — the arena grows on demand (the owner-hit fix)</h2>
 * The first real-overworld session tripped the guard on a 16 GiB card:
 * the fixed 256 MiB standard arena was sized by a density formula
 * calibrated on the plains bench, and real terrain runs several-fold
 * denser (arithmetic: docs/VANILLA-SECTION-BUILD.md wave-14 note). The
 * arena is now ELASTIC: an allocation failure in the drain first tries
 * {@link #growArenaLocked} — ×1.5 grow-and-copy through
 * {@link TerrainGpuHost#growArena} up to the device-derived ceiling
 * ({@code MesheliumScaling.arenaCeilingBytes}, default 50% of the largest
 * DEVICE_LOCAL heap) — and the failing upload is served by the grown
 * arena in the same pump. The failure ladder is growth → retained
 * eviction (wave 11, requeue) → drop (wave 8, guard): a drop on arena
 * bytes now MEANS growth was exhausted-or-impossible with nothing left
 * to evict, and {@link #guardTrip()} names the budget and its size at
 * trip time for the WARN and the options-screen status line. Records,
 * stamps and dispatch lists stay PINNED (their worst case is single-digit
 * MiB and their overflow paths either fail open or are grid-bounded —
 * the wave-14 doc note carries the per-budget audit); only the arena is
 * elastic. Guard re-arm stays world-load-only: a dropped section is one
 * vanilla holds and Meshelium lost — no later growth can prove coverage
 * again mid-world, because nothing re-enqueues that mesh until vanilla
 * itself rebuilds it.
 */
public final class TerrainResidency {

    /** See class Javadoc; must match the GPU side's staging retirement. */
    public static final int FREE_FRAME_LAG = 3;

    /**
     * Per-pump staging budget: half the ring. Keeps the render thread's
     * time inside vanilla's lock window bounded (workers block on
     * {@code copyLock} while the pump runs — the documented stall risk);
     * the rest stays queued as backlog and drains over following frames.
     */
    static final long UPLOAD_BYTES_PER_PUMP = 16L << 20;

    private static final Object LOCK = new Object();

    private record PendingUpload(int sx, int sy, int sz, EncodedSectionMesh encoded,
            TranslucentState translucent) {}

    /**
     * Wave-7 CPU source of truth for one section's translucent PREFIX:
     * {@code prefix} always holds the CURRENTLY DESIRED prefix bytes in
     * {@code order} (original-vanilla-quad-id per slot). Created on the
     * build thread at enqueue (seeded from the encoder's output + the
     * decoder's applied order), mutated only under {@code LOCK} by
     * {@link #onTranslucentResort} ({@code TranslucentPrefix.permute}),
     * consumed by the pump's prefix re-upload. Keeping a CPU copy is what
     * makes resorts pure permutations — no readback, no re-encode; cost is
     * 64 B per translucent quad of resident CPU memory.
     */
    private static final class TranslucentState {
        final byte[] prefix;
        int[] order;
        /** A resort landed while the section was still pending upload. */
        boolean dirtySinceEncode;

        TranslucentState(byte[] prefix, int[] order) {
            this.prefix = prefix;
            this.order = order;
        }
    }

    private static final class Resident {
        final int arenaAddr;
        final int quadCount;
        final long regionKey;
        final int posKey;
        boolean ownsSlot = true;
        // Wave-4 additive draw data (read-only after construction): section
        // coords + the 7 facing-bucket [relative start, count] pairs from
        // the encoder — what the CPU draw-list builder needs, so the drawer
        // never has to reach into region mirrors (docs/TERRAIN-DATA.md §4).
        final int sx, sy, sz;
        final int[] bucketStarts;  // relative quad index within the section's allocation
        final int[] bucketCounts;
        /** Wave-7 translucent prefix state; null for fully-opaque sections. */
        final TranslucentState translucent;
        /**
         * Wave-11: 0 = live (vanilla's mesh still holds this copy);
         * non-zero = the monotonic-clock millisecond this entry was
         * ORPHANED (vanilla released the mesh for distance reasons and the
         * copy moved to the retained map). Age/pressure eviction orders by
         * it; the upload path uses it to tell a retained previous owner
         * (free now — nothing will ever release it again) from a live one
         * (mark slotless, its own release frees it).
         */
        long orphanedAtMillis;

        Resident(int arenaAddr, int quadCount, long regionKey, int posKey,
                int sx, int sy, int sz, int[] bucketStarts, int[] bucketCounts,
                TranslucentState translucent) {
            this.arenaAddr = arenaAddr;
            this.quadCount = quadCount;
            this.regionKey = regionKey;
            this.posKey = posKey;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.bucketStarts = bucketStarts;
            this.bucketCounts = bucketCounts;
            this.translucent = translucent;
        }
    }

    /** Not a record: {@code quads} accumulates as parks join the epoch. */
    private static final class FreeEpoch {
        final long frame;
        final IntArrayList addrs = new IntArrayList();
        /** Quad total of the parked ranges (wave-11 pressure accounting). */
        long quads;

        FreeEpoch(long frame) {
            this.frame = frame;
        }
    }

    /** Encoded, waiting for the render-thread pump. Insertion-ordered. */
    private static final LinkedHashMap<Object, PendingUpload> pendingUploads = new LinkedHashMap<>();
    /**
     * How many queued uploads each section POSITION has, so a release can
     * ask "is a successor already on its way here?" without scanning.
     *
     * <p>{@link #pendingUploads} is keyed by mesh identity, which is the
     * right key for its own lifecycle and the wrong one for that question:
     * a rebuild's successor is a DIFFERENT mesh object at the SAME position.
     * Every mutation of {@code pendingUploads} goes through
     * {@link #pendingPosAdd} / {@link #pendingPosDrop} so the two cannot
     * drift.</p>
     */
    private static final java.util.HashMap<Long, Integer> pendingByPos = new java.util.HashMap<>();
    /** Mesh identity → its arena/region residency. */
    private static final IdentityHashMap<Object, Resident> resident = new IdentityHashMap<>();
    /**
     * Wave-11: packed section position → orphaned {@link Resident}.
     * Insertion order IS age order (orphan stamps come from a monotonic
     * clock and entries are only ever appended — a position can re-enter
     * only after a fresh upload superseded and REMOVED it first), so the
     * "time-bucketed eviction queue" the design asked for degenerates to
     * head-popping this map: O(evicted) per sweep, no full scan, exact
     * order — strictly cheaper than buckets.
     */
    private static final LinkedHashMap<Long, Resident> retained = new LinkedHashMap<>();
    /** Released arena addresses awaiting the frame fence. */
    private static final ArrayDeque<FreeEpoch> freeEpochs = new ArrayDeque<>();
    /**
     * Residents whose permuted prefix awaits its GPU re-upload (wave 7).
     * Drained by the pump through {@link TerrainGpuHost#stageArenaCopyLate}
     * — the barrier-separated LATE copy batch, so a fresh-geometry copy and
     * a prefix overwrite of the same range in one pump are WAW-ordered.
     */
    private static final java.util.LinkedHashSet<Resident> pendingPrefixUploads =
            new java.util.LinkedHashSet<>();

    private static RegionStore regionStore = new RegionStore();
    private static TerrainArena arena; // attached by the GPU side, null on GL forever
    /** Opaque VkBuffer handle of the GPU section-records buffer (wave 5). */
    private static long sectionRecordsHandle;
    private static long frameCounter;
    /**
     * Wave-4 additive: bumped (under LOCK) whenever the resident SET
     * changes — upload, release, dispose. {@link #drawSnapshot(long)}
     * callers cache by it, so the per-frame cost of an unchanged world is
     * one lock + one long compare.
     */
    private static long drawEpoch;

    // Wave-11 retention state (all under LOCK).
    /** Quads held by RETAINED entries (excluded from {@link #quadsResident}). */
    private static long retainedQuads;
    /** Quads parked in {@link #freeEpochs} — freed but fence-immature. */
    private static long parkedQuads;
    // Wave-11 cumulative counters.
    private static long orphanedSections;
    private static long retainedSuperseded;
    private static long evictedByAge;
    private static long evictedByPressure;
    private static long evictedByDisable;
    /** Times an alloc/budget failure was answered by eviction+requeue. */
    private static long retainedBackpressure;
    /**
     * Old copies held across a rebuild because their successor's upload was
     * still queued. Every one of these is a black chunk that did not happen.
     */
    private static long handoverRetained;
    /** Wave-16: quiet-time trims of the arena's committed-but-untouched tail. */
    private static long arenaTrims;
    /**
     * Last pump that had arena work in flight, in monotonic millis. The
     * trim fires only after {@code meshelium.tune.arenaTrimQuietSec} of
     * silence, so its one GPU copy can never land inside load-in or a
     * rebuild storm - the exact moments the grow-and-copy spikes taught
     * this codebase to fear.
     */
    private static long lastBusyMillis;
    /** Arena bytes staged THIS pump; the trim's hard no-swap condition. */
    private static long stagedBytesThisPump;
    /** Arena bytes staged since the quiet timer last reset (trickle meter). */
    private static long stagedBytesSinceQuiet;

    /**
     * Wave-11 arena high-water mark: past this fraction of the arena's
     * quad capacity (counting only ranges that are NOT already on their
     * way out through the epochs), the pump evicts oldest retained first —
     * BEFORE any live section can fail its allocation. Since wave 14 the
     * fraction is of the CEILING (the arena is elastic — see the pressure
     * sweep): 15% of even the 256 MiB floor is ~38 MiB ≈ 2+ pumps of the
     * 16 MiB upload budget, and real ceilings are gigabytes — eviction
     * (matures in FREE_FRAME_LAG=3 pumps) wins the race against the fill
     * rate by construction, with growth additionally serving live
     * allocations below the ceiling.
     */
    private static final int ARENA_HIGH_WATER_PCT = 85;
    /** Region-id high-water: retention also consumes region ids. */
    private static final int REGION_HIGH_WATER_PCT = 90;
    /** Sweep bound per pump — keeps the lock window flat. */
    private static final int EVICT_BUDGET_PER_PUMP = 256;
    /** Evictions per alloc/budget FAILURE (mature in 3 pumps; see javadoc). */
    private static final int FORCE_EVICT_BATCH = 64;

    /**
     * Wave-11 reset bracket: {@code RenderSection.reset()} depth of the
     * CURRENT thread — nonzero means releases arriving now are
     * distance-class (retain). Thread-local because the hook can fire on
     * any thread holding vanilla's copyLock; int[] holder avoids autobox
     * churn on the hot path. Self-heals at pump start (reset only ever
     * runs on the render thread — both its callers are render-thread-only
     * — so a depth stranded by an exotic exception clears next frame).
     */
    private static final ThreadLocal<int[]> RESET_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    // Counters (all guarded by LOCK; quadsResident excludes reserved quad 0).
    private static long quadsResident;
    private static long freedSections;
    private static long encodedSections;
    private static long uploadedSections;
    private static long discardedBeforeUpload;
    private static long droppedOversize;
    private static long droppedArenaFull;
    private static long droppedRegionBudget;
    private static long droppedEncoding;
    private static long staleParks;
    private static long decoderSkippedLayers;
    // Wave-14 growth counters (under LOCK).
    /** Successful arena grow-and-copies (world-lifetime diagnostic). */
    private static long arenaGrowths;
    /** Growth attempts the GPU side refused (allocation/record failure). */
    private static long arenaGrowthFailures;
    /**
     * Wave-8 coverage guard: sum of the four drop counters as they stood at
     * the last {@link #disposeAndReset()} (world change). The counters are
     * lifetime diagnostics and never reset, so "drops in the CURRENT world"
     * is total-minus-baseline — see {@link #dropsThisWorld()}.
     */
    private static long dropBaseline;
    // Wave-7 resort counters (all under LOCK).
    private static long resortsApplied;
    private static long resortBytes;
    private static long resortsNoop;
    private static long resortsUnknownMesh;
    private static long resortsMalformed;

    private static volatile String lastError;
    private static volatile Counters disposeSnapshot;
    private static long lastStatsNanos;

    // ------------------------------------------------------------------
    // Wave-15: live mid-world render-distance raise (pinned-budget grow)
    // ------------------------------------------------------------------

    /**
     * The option value a pinned-budget grow was requested for (0 = none).
     * Written by the client-tick monitor ({@code MesheliumExtendedRd}),
     * consumed at the head of the next {@link #pump} — the pump is the
     * one place that may create GPU buffers and swap the scaling snapshot
     * (render thread, inside vanilla's lock window, the wave-14 growArena
     * site). Volatile: tick and pump run on the same thread in practice,
     * but the field's contract is cross-hook.
     */
    private static volatile int pendingGrowOption;
    /**
     * Latched when a requested grow FAILED this world (GPU refused the
     * allocation). The monitor reads it and falls back to the wave-13
     * rejoin hint — the hint is now the fallback, not the rule. Cleared
     * with the world ({@link #disposeAndReset}).
     */
    private static volatile boolean pinnedGrowFailed;
    /** Wave-15 probes (under LOCK for writes). */
    private static long pinnedGrows;
    private static long pinnedGrowFailures;

    /**
     * The monitor saw the render-distance option exceed the pinned
     * budget under a healthy drawer: ask the next pump to grow the
     * pinned-side buffers. Idempotent and cheap; safe from any thread
     * (pure field write). No-ops after a failed grow this world (the
     * monitor then shows the rejoin hint instead).
     */
    public static void requestPinnedGrow(int optionRd) {
        if (!pinnedGrowFailed) {
            pendingGrowOption = optionRd;
        }
    }

    /** Wave-15 probe: true when a grow failed this world (hint fallback armed). */
    public static boolean pinnedGrowFailedThisWorld() {
        return pinnedGrowFailed;
    }

    /** Wave-15 probe: successful mid-world pinned-budget grows (lifetime). */
    public static long pinnedGrows() {
        synchronized (LOCK) {
            return pinnedGrows;
        }
    }

    /** Wave-15 probe: failed mid-world grow attempts (lifetime). */
    public static long pinnedGrowFailures() {
        synchronized (LOCK) {
            return pinnedGrowFailures;
        }
    }

    /**
     * Consume a pending grow request at pump head (under LOCK, render
     * thread, arena attached). Ordering is the whole design: (1) the GPU
     * record buffers grow FIRST (grow-and-copy, identical offsets, old
     * pair fence-parked — {@link TerrainGpuHost#growRecords}, which also
     * drops the drawer's snapshot-sized occlusion/frame-list resources so
     * they recreate at the new sizes this same frame); (2) only then does
     * the scaling snapshot swap ({@code MesheliumScaling.growPinned}), so
     * {@code RegionStore.maxRegions()} — a live read of the snapshot —
     * can never admit a region id the buffers cannot hold; (3) the new
     * section-records handle republishes through the draw snapshot
     * (epoch bump; frames still in flight read the fence-parked old
     * buffers, the wave-14 era argument). A no-grow-needed request
     * (target not above current, e.g. the ceiling already caps it) is
     * dropped silently; a GPU refusal latches {@link #pinnedGrowFailed}
     * and the monitor falls back to the once-per-world rejoin hint.
     * dispatchCapacity-only grows (standard 32 -> extended 40: maxRegions
     * stays 2048) skip the record copy but still swap the snapshot and
     * drop the drawer resources so the frame lists/stamp slots rebuild.
     */
    private static void consumePendingGrowLocked(TerrainGpuHost gpu) {
        int optionRd = pendingGrowOption;
        if (optionRd <= 0) {
            return;
        }
        pendingGrowOption = 0;
        com.deds.meshelium.MesheliumScaling.Snapshot current =
                com.deds.meshelium.MesheliumScaling.current();
        com.deds.meshelium.MesheliumScaling.Snapshot target =
                com.deds.meshelium.MesheliumScaling.computeForOption(optionRd);
        if (target.maxRd() <= current.maxRd()) {
            return; // ceiling-capped or stale request — nothing to grow
        }
        if (target.maxRegions() > current.maxRegions()) {
            long newHandle = gpu.growRecords(target.maxRegions());
            if (newHandle == 0L) {
                pinnedGrowFailed = true;
                pinnedGrowFailures++;
                MesheliumClient.LOGGER.warn(
                        "Meshelium live render-distance raise: record growth {} -> {} regions "
                                + "failed; this world keeps the pinned budget (the rejoin hint "
                                + "takes over)",
                        current.maxRegions(), target.maxRegions());
                return;
            }
            sectionRecordsHandle = newHandle;
        } else {
            // Records already big enough; the drawer's dispatch-capacity
            // resources still derive from the snapshot — drop them the
            // same way growRecords does, through the host seam's side
            // contract (growRecords calls it; here we must ourselves).
            gpu.dropSnapshotSizedDrawResources();
        }
        // The fresh snapshot identity re-arms the rejoin-hint keying by
        // itself (the hint compares identities; no onWorldPinned needed).
        com.deds.meshelium.MesheliumScaling.growPinned(target);
        pinnedGrows++;
        drawEpoch++;
    }

    /**
     * Wave-14 guard honesty: WHICH budget tripped the coverage guard for
     * the CURRENT world, with its size at trip time. {@code kind} is one
     * of {@code "arena"|"oversize"|"region"|"encoding"} (the four drop
     * counters); {@code value}/{@code limit} are kind-specific ({@code
     * arena}: capacity MiB at trip / ceiling MiB; {@code oversize}:
     * section MiB / staging MiB; {@code region}: live regions / id
     * budget; {@code encoding}: 0/0). First drop of the world wins (the
     * cause, not the aftershocks); cleared by {@link #disposeAndReset}
     * with the drop baseline. Volatile: the drawer's once-only WARN and
     * the options screen's status line read it off the render/client
     * threads.
     */
    public record GuardTrip(String kind, long value, long limit) {}

    private static volatile GuardTrip guardTrip;

    private TerrainResidency() {}

    /** Immutable counter snapshot for tests and the debug line. */
    public record Counters(
            long frame,
            int sectionsResident, long quadsResident,
            long arenaUsedBytes, long arenaCapacityBytes,
            int regionsLive, int regionsDirty,
            int stagingBacklogEntries, long stagingBacklogBytes,
            int pendingFreeRanges,
            long encodedSections, long uploadedSections, long freedSections,
            long discardedBeforeUpload, long droppedOversize, long droppedArenaFull,
            long droppedRegionBudget, long droppedEncoding,
            long staleParks, long decoderSkippedLayers,
            long resortsApplied, long resortBytes, long resortsNoop,
            long resortsUnknownMesh, long resortsMalformed,
            int retainedSections, long retainedQuads,
            long orphanedSections, long retainedSuperseded,
            long evictedByAge, long evictedByPressure, long evictedByDisable,
            long retainedBackpressure,
            long arenaGrowths, long arenaGrowthFailures,
            // The two numbers that decide whether reclaiming arena memory is
            // worth building, and which kind is worth building.
            //
            // arenaExtentBytes is the allocator's high-water mark. Committed
            // minus extent is untouched tail that costs nothing to give back;
            // extent minus USED is the holes, and holes are the only thing
            // compaction could ever recover. Live-versus-committed, which is
            // what the mod printed before, cannot tell those two apart, so it
            // could not answer the question at all.
            //
            // emptyTopBlocks is the zero-copy alternative: blocks that are
            // already completely free and could be handed straight back with
            // no moving of anything.
            long arenaExtentBytes, int arenaBlocks, int emptyTopBlocks) {

        /** True iff nothing wave-3b ever happened — the GL dormancy proof. */
        public boolean isCompletelyIdle() {
            return frame == 0 && sectionsResident == 0 && quadsResident == 0
                    && arenaUsedBytes == 0 && arenaCapacityBytes == 0 && regionsLive == 0
                    && regionsDirty == 0 && stagingBacklogEntries == 0 && stagingBacklogBytes == 0
                    && pendingFreeRanges == 0 && encodedSections == 0 && uploadedSections == 0 && freedSections == 0
                    && discardedBeforeUpload == 0 && droppedOversize == 0 && droppedArenaFull == 0
                    && droppedRegionBudget == 0 && droppedEncoding == 0 && staleParks == 0
                    && decoderSkippedLayers == 0
                    && resortsApplied == 0 && resortBytes == 0 && resortsNoop == 0
                    && resortsUnknownMesh == 0 && resortsMalformed == 0
                    && retainedSections == 0 && retainedQuads == 0
                    && orphanedSections == 0 && retainedSuperseded == 0
                    && evictedByAge == 0 && evictedByPressure == 0 && evictedByDisable == 0
                    && retainedBackpressure == 0
                    && arenaGrowths == 0 && arenaGrowthFailures == 0
                    && arenaExtentBytes == 0 && arenaBlocks == 0 && emptyTopBlocks == 0;
        }
    }

    // ------------------------------------------------------------------
    // Probes (tests, debug line)
    // ------------------------------------------------------------------

    /**
     * Old copies held across a rebuild until their successor's upload
     * landed. Each one is a section that would otherwise have had NO
     * drawable copy for a frame or more, which with the upload seam armed
     * means nobody drew it at all. The harness asserts this rises.
     */
    public static long handoverRetained() {
        synchronized (LOCK) {
            return handoverRetained;
        }
    }

    public static Counters counters() {
        synchronized (LOCK) {
            return countersLocked();
        }
    }

    /**
     * TRUE iff nothing already encoded can still LAND on the GPU without
     * a fresh {@code drawEpoch} bump first: no dirty regions queued for
     * commit and no staged uploads pending. The distinction matters
     * because a requeued {@code commitDirty} from an earlier full-staging
     * pump can deliver section records on a frame whose epoch is
     * otherwise quiet; the phase-B CPU skip (TerrainDrawer) treats any
     * backlog as an input change for exactly that reason.
     */
    public static boolean gpuCommitBacklogEmpty() {
        synchronized (LOCK) {
            return regionStore.dirtyRegionCount() == 0 && pendingUploads.isEmpty();
        }
    }

    /** Null = healthy (the wave-2 {@code lastError()} latch pattern). */
    public static String lastError() {
        return lastError;
    }

    /**
     * The counter snapshot taken at the {@code dispose()} HEAD hook,
     * BEFORE the store was cleared — the leak test that matters: by then
     * vanilla has already released every section mesh through
     * {@code releaseAllBuffers()} (section-build Q3.4), so a non-zero
     * {@code sectionsResident} here means a mesh whose free never reached
     * Meshelium. Null until the first dispose.
     */
    public static Counters lastDisposeSnapshot() {
        return disposeSnapshot;
    }

    /**
     * Region-id budget, needed by the GPU side for buffer sizing. Since
     * wave 10 this is the {@code MesheliumScaling} world snapshot's value
     * (2048 while the configured max render distance is the default 32);
     * the GPU side pins the snapshot at standup before sizing anything,
     * and the store enforces the same live value, so records can never
     * outgrow the buffers sized here.
     */
    public static int maxRegions() {
        return RegionStore.maxRegions();
    }

    /**
     * Wave-8 coverage guard input: sections dropped (arena-full, oversize,
     * region-budget or encode failure) since the CURRENT world's dispatcher
     * came up. Nonzero means Meshelium's resident set is a strict SUBSET of
     * vanilla's — a Meshelium-owned frame would have holes — so the drawer
     * goes passive until a world load whose counters stay clean.
     * Monotonic within a world (counters only grow; the baseline moves only
     * at {@link #disposeAndReset()}), so the guard can never flap back to
     * active mid-world.
     */
    public static long dropsThisWorld() {
        synchronized (LOCK) {
            return droppedOversize + droppedArenaFull + droppedRegionBudget + droppedEncoding
                    - dropBaseline;
        }
    }

    /**
     * Wave-14: the budget that tripped the coverage guard this world (see
     * the record javadoc), or null while the world is clean. Non-null
     * exactly when {@link #dropsThisWorld()} is nonzero — every drop site
     * notes its cause before moving its counter.
     */
    public static GuardTrip guardTrip() {
        return guardTrip;
    }

    /**
     * Human sentence for the drawer's once-only WARN and the log: names
     * the tripped budget and its size at trip time. Empty when clean.
     */
    public static String guardTripDescription() {
        GuardTrip trip = guardTrip;
        if (trip == null) {
            return "";
        }
        return switch (trip.kind()) {
            case "arena" -> "terrain memory: the " + trip.value()
                    + " MiB arena reached its " + trip.limit()
                    + " MiB ceiling (growth exhausted; raise meshelium.tune.arenaCeilingMiB "
                    + "or lower the render distance)";
            case "oversize" -> "a single section's " + trip.value()
                    + " MiB mesh exceeded the " + trip.limit() + " MiB staging ring";
            case "region" -> "region budget: " + trip.value() + " of " + trip.limit()
                    + " region ids in use";
            case "vram" -> "the graphics card is out of room: growing terrain memory to "
                    + trip.value() + " MiB needs more than the " + trip.limit()
                    + " MiB actually free, so the allocation was refused rather than risk "
                    + "crashing the game";
            case "encoding" -> "a section failed to encode (see the residency error latch)";
            // Every cause must be NAMED above. This used to be the encoding
            // arm's default, which meant a new cause code silently reported
            // itself as an encode failure - exactly what "vram" did, sending
            // the owner and me after a bug that was not there while the log
            // right beside it read encoding=0.
            default -> "unknown cause '" + trip.kind() + "' (this is a Meshelium bug; the "
                    + "drop counters in the same line say which one really fired)";
        };
    }

    /** First drop of the world wins — the cause, not the aftershocks. */
    private static void noteGuardTripLocked(String kind, long value, long limit) {
        if (guardTrip == null) {
            guardTrip = new GuardTrip(kind, value, limit);
        }
    }

    // ------------------------------------------------------------------
    // Wave-4 read-only draw view (additive; no LWJGL imports — the arena
    // backing handle travels as an opaque long, exactly as ArenaBacking
    // defined it in 3a)
    // ------------------------------------------------------------------

    /**
     * Immutable per-frame view of every resident section, flattened for the
     * render thread's draw-list builder: {@link #STRIDE} ints per section —
     * {@code [sx, sy, sz, arenaQuadAddr, bucketStartRel[0..6],
     * bucketCount[0..6], globalSectionIndex]} (the last is wave 7's, see
     * the record javadoc) with bucket order/gating per
     * {@code QuadFacing} (docs/TERRAIN-DATA.md §4). Bucket starts are
     * RELATIVE to the section's allocation; absolute arena quad index =
     * {@code arenaQuadAddr + startRel}. The translucent prefix is excluded
     * by construction ({@code startRel[0] == translucentCount}).
     *
     * <p>{@code arenaBackingHandle} is the {@code ArenaBacking} opaque
     * handle (the terrain VkBuffer on the Vulkan path), captured under the
     * same lock as the data so handle and addresses can never mix eras.</p>
     *
     * <p><b>Wave-5 additive region view:</b> {@code regionData} flattens
     * every live region as {@code [regionId, rx, ry, rz, compactedCount,
     * occMinPacked, occMaxPacked]}
     * ({@link #REGION_STRIDE} ints — see {@code RegionStore.snapshotRegions})
     * for the per-region task dispatch; {@code sectionRecordsHandle} is the
     * opaque VkBuffer handle of the GPU section-records buffer the task
     * shader reads (0 on GL forever, captured under the same lock as
     * everything else for the same no-mixed-eras reason). NOTE: the GPU
     * records can lag this CPU view by a pump when the staging ring is
     * full ({@code RegionStore.commitDirty} requeues) — the task shader
     * then sees a zeroed/older record and skips, i.e. a just-built section
     * appears a frame or two late under heavy streaming, never a stale
     * draw (freed arena ranges are fence-parked for {@link #FREE_FRAME_LAG}
     * pumps). The parity harness quiesces before its screenshots, which
     * closes the window there.</p>
     */
    /*
     * arenaBlockHandles: every arena block's buffer, index == block. The
     * drawer MUST bind all of them, not just arenaBackingHandle. A quad
     * address carries its block in its high bits, so a section living in
     * block 1 but read through block 0's buffer fetches whatever geometry
     * happens to sit at that offset - plausible, wrong, and drawn. Snapshot
     * takes its own array because the arena keeps mutating its list under
     * the residency lock while the drawer reads outside it.
     */
    public record DrawSnapshot(long epoch, int sectionCount, int[] data, long arenaBackingHandle,
            long[] arenaBlockHandles,
            int regionCount, int[] regionData, long sectionRecordsHandle, int[] retainedMasks) {


        /**
         * Ints per section in {@link #data}: {@code [sx, sy, sz,
         * arenaQuadAddr, bucketStartRel[0..6], bucketCount[0..6],
         * globalSectionIndex, retainedFlag]}. Wave-7 additive: {@code [18]
         * globalSectionIndex} = {@code regionId*256 + compactedSlot} when
         * this resident OWNS its region slot (the stamp-buffer index the
         * translucent occlusion gate uses), or −1 (slot stolen by a newer
         * mesh / region gone) — slotless residents are excluded from the
         * translucent draw so the promotion-lag window can never
         * double-blend one section (the opaque paths draw from the GPU
         * records, which already point at the slot owner). The translucent
         * prefix itself is {@code [start 0, count bucketStartRel[0]]} of
         * the section's allocation (bucket 0 starts after the prefix).
         *
         * <p><b>Wave-11 additive:</b> {@code [19] retainedFlag} — 1 when
         * the entry is a RETAINED copy (vanilla released its mesh; it is
         * absent from {@code visibleSections} by construction), else 0.
         * The drawer's translucent pass uses it to draw retained prefixes
         * in its own far-first pre-pass; the opaque paths need no flag
         * (occlusion/cpuCull draw retained entries exactly like live ones,
         * and the BFS-mask path consumes {@link #retainedMasks} instead).
         * Retained entries always own their slot ({@code [18] >= 0}) —
         * a stolen slot frees the retained copy at steal time.</p>
         *
         * <p>{@code retainedMasks} is the wave-11 twin of
         * {@code regionData}: 8 mask ints per region, SAME region order
         * (position-keyed 256-bit masks — word {@code posKey >>> 5}, bit
         * {@code posKey & 31}), listing each region's retained sections.
         * The BFS-mask draw path ORs them into vanilla's visibility masks
         * so retained-only regions dispatch and retained sections pass the
         * task stage — the fail-open chosen over a per-record retained
         * flag because it needs NO record-format or shader change (the
         * mask bind point already exists; wave-5's machinery is reused
         * verbatim).</p>
         */
        public static final int STRIDE = 20;
        public static final int BUCKETS = 7;
        /**
         * Ints per region in {@link #regionData}: id, rx, ry, rz, count,
         * occMinPacked, occMaxPacked (wave-6 additive — the occupancy AABB
         * in section-local units, {@code x | y<<8 | z<<16}, for the
         * occlusion region-raster box; see {@code RegionStore.snapshotRegions}).
         */
        public static final int REGION_STRIDE = 7;
    }

    /**
     * Render-thread accessor for the drawer. Returns {@code null} when the
     * resident set has not changed since {@code knownEpoch} (keep the
     * cached snapshot); otherwise a freshly built snapshot. Stale-by-a-
     * -frame draws are safe by the same fence discipline the pump uses: a
     * released range cannot be reallocated (and new bytes copied over it)
     * until {@code FREE_FRAME_LAG} pumps later.
     */
    public static DrawSnapshot drawSnapshot(long knownEpoch) {
        synchronized (LOCK) {
            if (drawEpoch == knownEpoch) {
                return null;
            }
            int n = resident.size() + retained.size();
            int[] data = new int[n * DrawSnapshot.STRIDE];
            int o = 0;
            for (Resident r : resident.values()) {
                o = writeSnapshotEntryLocked(data, o, r, 0);
            }
            // Wave-11: retained entries ride the same flat view — the
            // occlusion and cpuCull paths draw them with zero extra code;
            // [19] flags them for the translucent pre-pass and the mask
            // path reads retainedMasks below.
            for (Resident r : retained.values()) {
                o = writeSnapshotEntryLocked(data, o, r, 1);
            }
            long handle = arena == null ? 0L : arena.backingHandle();
            long[] blockHandles = arena == null ? new long[0] : arena.blockHandles();
            // Both snapshots inside ONE lock hold, no mutation between —
            // identical region iteration order (RegionStore javadoc).
            int[] regionData = regionStore.snapshotRegions();
            int[] retainedMasks = regionStore.snapshotRetainedMasks();
            return new DrawSnapshot(drawEpoch, n, data, handle, blockHandles,
                    regionData.length / DrawSnapshot.REGION_STRIDE, regionData,
                    sectionRecordsHandle, retainedMasks);
        }
    }

    private static int writeSnapshotEntryLocked(int[] data, int o, Resident r, int retainedFlag) {
        data[o] = r.sx;
        data[o + 1] = r.sy;
        data[o + 2] = r.sz;
        data[o + 3] = r.arenaAddr;
        for (int b = 0; b < DrawSnapshot.BUCKETS; b++) {
            data[o + 4 + b] = r.bucketStarts[b];
            data[o + 11 + b] = r.bucketCounts[b];
        }
        data[o + 18] = r.ownsSlot
                ? regionStore.globalSectionIndex(r.regionKey, r.posKey, r)
                : -1;
        data[o + 19] = retainedFlag;
        return o + DrawSnapshot.STRIDE;
    }

    // ------------------------------------------------------------------
    // Build-thread entry points (via SectionBuildTap)
    // ------------------------------------------------------------------

    static void enqueueUpload(Object mesh, int sx, int sy, int sz, EncodedSectionMesh encoded,
            int[] translucentOrder) {
        TranslucentState translucent = null;
        int translucentCount = encoded.translucentCount();
        if (translucentCount > 0) {
            // Seed the CPU prefix copy OUTSIDE the lock (build thread work):
            // bytes exactly as encoded, order = the decoder's applied order
            // (vanilla's build-time sort; identity fallback carried through).
            byte[] prefix = new byte[translucentCount * com.deds.meshelium.terrain.TerrainVertexCodec.QUAD_STRIDE];
            java.nio.ByteBuffer geometry = encoded.geometry();
            geometry.get(0, prefix, 0, prefix.length);
            int[] order = translucentOrder;
            if (order == null || order.length != translucentCount) {
                order = new int[translucentCount];
                for (int i = 0; i < translucentCount; i++) {
                    order[i] = i;
                }
            }
            translucent = new TranslucentState(prefix, order);
        }
        synchronized (LOCK) {
            encodedSections++;
            PendingUpload previous =
                    pendingUploads.put(mesh, new PendingUpload(sx, sy, sz, encoded, translucent));
            if (previous != null) {
                staleParks++; // one mesh, two encodings — should be impossible
                pendingPosDrop(previous.sx(), previous.sy(), previous.sz());
            }
            pendingPosAdd(sx, sy, sz);
        }
    }

    private static void pendingPosAdd(int sx, int sy, int sz) {
        pendingByPos.merge(posPack(sx, sy, sz), 1, Integer::sum);
    }

    private static void pendingPosDrop(int sx, int sy, int sz) {
        pendingByPos.compute(posPack(sx, sy, sz), (k, n) -> n == null || n <= 1 ? null : n - 1);
    }

    /** Is another encoding for this position already queued for the GPU? */
    private static boolean successorQueued(int sx, int sy, int sz) {
        return pendingByPos.containsKey(posPack(sx, sy, sz));
    }

    /**
     * Wave-7 resort tap (section-build shopping-list row 7, inverted
     * filter: ONLY {@code vertexBuffer == null} calls arrive here):
     * {@code addSectionBuffersToUberBuffer(TRANSLUCENT, mesh, null,
     * indexBytes)} HEAD. Decodes vanilla's NEW sorted order from the index
     * bytes and permutes the section's CPU prefix copy; the GPU re-upload
     * is queued for the pump ({@code stageArenaCopyLate}). Content-based
     * dedupe (new order == current order → no-op) absorbs the spin-retry
     * refires AND vacuous resorts — {@code doTask} re-calls with a fresh
     * {@code byteBuffer()} view per retry (bytecode ip 179-184), so
     * identity dedupe is structurally impossible.
     *
     * <p>Runs on a build worker or the render thread, under vanilla's
     * {@code copyLock}; Meshelium's LOCK is innermost as everywhere. NEVER
     * re-encodes: the only inputs are the index bytes and the stored
     * prefix copy (the harness pins this — resorts move {@code
     * resortsApplied} while {@code encodedSections} stays flat).</p>
     */
    public static void onTranslucentResort(Object mesh, java.nio.ByteBuffer indexBytes) {
        if (mesh == null) {
            return;
        }
        synchronized (LOCK) {
            TranslucentState state = null;
            Resident r = resident.get(mesh);
            if (r != null) {
                state = r.translucent;
            } else {
                PendingUpload pending = pendingUploads.get(mesh);
                if (pending != null) {
                    state = pending.translucent();
                }
            }
            if (state == null) {
                // Mesh Meshelium never uploaded (dropped section, budget path)
                // or already released — vanilla resorts it, we have nothing.
                resortsUnknownMesh++;
                return;
            }
            int n = state.order.length;
            int[] newOrder = VanillaMeshDecoder.resortQuadOrder(indexBytes, n);
            if (newOrder == null) {
                resortsMalformed++; // stale order kept — fail-safe
                return;
            }
            if (java.util.Arrays.equals(newOrder, state.order)) {
                resortsNoop++; // spin-retry refire or vacuous resort
                return;
            }
            try {
                TranslucentPrefix.permute(state.prefix, state.order, newOrder,
                        new byte[state.prefix.length]);
            } catch (IllegalArgumentException e) {
                resortsMalformed++;
                return;
            }
            state.order = newOrder;
            resortsApplied++;
            if (r != null) {
                pendingPrefixUploads.add(r);
            } else {
                state.dirtySinceEncode = true; // upload path re-stages the prefix
            }
        }
    }

    static void countStalePark() {
        synchronized (LOCK) {
            staleParks++;
        }
    }

    static void countDecoderSkips(int layers) {
        synchronized (LOCK) {
            decoderSkippedLayers += layers;
        }
    }

    static void countEncodeFailure(Throwable cause) {
        synchronized (LOCK) {
            noteGuardTripLocked("encoding", 0, 0);
            droppedEncoding++;
        }
        recordError("encode: " + cause);
    }

    // ------------------------------------------------------------------
    // Lifetime hooks (mixins; always under vanilla's copyLock)
    // ------------------------------------------------------------------

    /**
     * Wave-11 reset bracket ({@code RenderSection.reset()} HEAD): releases
     * arriving until {@link #endSlotReset()} are distance-class — vanilla
     * is revoking the SLOT (grid reposition / releaseAllBuffers), not
     * replacing the mesh — so {@link #onMeshReleased} retains instead of
     * freeing (when the toggle is on). Depth-counted per thread; reset
     * cannot recurse but the counter costs nothing and survives misuse.
     */
    public static void beginSlotReset() {
        RESET_DEPTH.get()[0]++;
    }

    /** Closes {@link #beginSlotReset}'s bracket ({@code reset()} RETURN). */
    public static void endSlotReset() {
        int[] depth = RESET_DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    private static boolean inSlotReset() {
        return RESET_DEPTH.get()[0] > 0;
    }

    /** Monotonic milliseconds for orphan stamps (never wall clock). */
    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    static long posPack(int sx, int sy, int sz) {
        return ((sx & 0x1FFFFFL) << 42) | ((sy & 0x1FFFFFL) << 21) | (sz & 0x1FFFFFL);
    }

    /**
     * Vanilla is freeing this mesh ({@code releaseSectionMesh} HEAD — every
     * per-mesh free in the game funnels through it, Q3.4). Unknown objects
     * (the UNCOMPILED/EMPTY sentinels, empty-section meshes we never
     * encoded) are no-ops. A mesh released before its upload is simply
     * discarded; a resident mesh parks its arena range on the current
     * frame's epoch and leaves its region slot (unless a newer mesh stole
     * the slot first — the promotion-lag window, see RegionStore).
     *
     * <p><b>Wave-11:</b> a release inside a {@code reset()} bracket with
     * retention enabled ORPHANS the entry instead (class javadoc case (a));
     * everything else keeps the wave-3b free path. Slotless residents are
     * never retained — their slot owner already superseded them, so a
     * retained copy would be a duplicate of newer geometry.</p>
     */
    public static void onMeshReleased(Object mesh) {
        if (mesh == null) {
            return;
        }
        synchronized (LOCK) {
            PendingUpload pending = pendingUploads.remove(mesh);
            if (pending != null) {
                discardedBeforeUpload++;
                pendingPosDrop(pending.sx(), pending.sy(), pending.sz());
                return;
            }
            Resident r = resident.remove(mesh);
            if (r == null) {
                return;
            }
            // THE HANDOVER GAP (fixed 2026-08-15; the owner's "ocean flashes
            // black" report).
            //
            // A rebuild releases the OLD mesh while its successor's upload is
            // still queued. Freeing here leaves the position with no drawable
            // copy until the pump catches up - and with the upload seam armed
            // vanilla has no copy either, because the seam cancelled it and
            // then, faithfully emulating vanilla's bookkeeping, called
            // checkSectionMesh, which is the very call that lands here. So
            // the seam was freeing Meshelium's only copy of a section a frame
            // or more before its replacement existed.
            //
            // Over land the hole shows the terrain behind it and nobody
            // notices. Over an ocean it shows straight down into unlit water
            // and reads as a black chunk, which is how it was finally seen,
            // three versions after it shipped.
            //
            // The cure is the wave-11 retention path, which already exists
            // for exactly this shape of problem: keep the old copy drawing,
            // and let the successor's upload supersede it through the slot
            // steal. Deliberately NOT gated on retainTerrainEnabled - that
            // setting is about holding terrain past its render distance,
            // which is a feature. Holding it for the frame between a rebuild
            // and its upload is correctness, and a player who turned the
            // feature off did not ask for holes.
            if (r.ownsSlot && arena != null && successorQueued(r.sx, r.sy, r.sz)) {
                if (regionStore.markRetained(r.regionKey, r.posKey, r)) {
                    r.orphanedAtMillis = monotonicMillis();
                    retained.put(posPack(r.sx, r.sy, r.sz), r);
                    quadsResident -= r.quadCount;
                    retainedQuads += r.quadCount;
                    handoverRetained++;
                    drawEpoch++;
                    return;
                }
            }
            if (r.ownsSlot && inSlotReset() && MesheliumConfig.retainTerrainEnabled()
                    && arena != null) {
                // (a) distance/reposition — RETAIN: keyed by position now
                // (the mesh identity is dead), region slot + records +
                // arena range all kept; the copy keeps drawing. The
                // owner-checked mark cannot fail here (ownsSlot == the
                // store's own ownership), but a false return degrades to
                // the free path below rather than leaking.
                if (regionStore.markRetained(r.regionKey, r.posKey, r)) {
                    r.orphanedAtMillis = monotonicMillis();
                    retained.put(posPack(r.sx, r.sy, r.sz), r);
                    quadsResident -= r.quadCount;
                    retainedQuads += r.quadCount;
                    orphanedSections++;
                    drawEpoch++; // [19] flips for this entry
                    return;
                }
            }
            pendingPrefixUploads.remove(r); // a queued resort upload dies with it
            drawEpoch++;
            quadsResident -= r.quadCount;
            freedSections++;
            if (r.ownsSlot) {
                regionStore.remove(r.regionKey, r.posKey, r);
            }
            parkAddrLocked(r.arenaAddr, r.quadCount);
        }
    }

    /**
     * Wave-11: the build tap saw section {@code (sx,sy,sz)} compile to an
     * EMPTY result (no rendered layers / zero quads — vanilla never
     * uploads those, doTask ip 166-249, and Meshelium never enqueues them).
     * A retained copy at that position is now provably stale — the world
     * says the section has no geometry — so it is dropped immediately
     * (through the fence epochs as always). Without this signal a
     * dig-everything-out edit racing a render-distance change could leave
     * ghost terrain at an in-range position. Build thread; no-op when
     * nothing is retained there.
     */
    public static void onSectionCompiledEmpty(int sx, int sy, int sz) {
        synchronized (LOCK) {
            Resident r = retained.remove(posPack(sx, sy, sz));
            if (r == null) {
                return;
            }
            freeRetainedLocked(r);
            retainedSuperseded++;
            drawEpoch++;
        }
    }

    /**
     * A retained copy that must NOT be evicted, whatever the pressure.
     *
     * <p>An entry whose position still has a queued upload is not horizon
     * decoration, it is the only drawable copy of a section that is being
     * rebuilt right now. Evicting it is precisely the hole this retention
     * exists to prevent, so every eviction sweep skips it. It stops being
     * protected the moment its successor lands, and the slot steal in the
     * upload path removes it then anyway.</p>
     *
     * <p>Cheap by construction: the sweeps walk retained in age order and a
     * handover entry is always among the youngest, so this test is reached
     * rarely and answers false almost always.</p>
     */
    private static boolean awaitingSuccessor(Resident r) {
        return successorQueued(r.sx, r.sy, r.sz);
    }

    /** Common tail of every retained-copy release path. Caller bumps counters/epoch. */
    private static void freeRetainedLocked(Resident r) {
        pendingPrefixUploads.remove(r);
        retainedQuads -= r.quadCount;
        freedSections++;
        regionStore.remove(r.regionKey, r.posKey, r);
        parkAddrLocked(r.arenaAddr, r.quadCount);
    }

    /** Park an arena range on the current frame's fence epoch. */
    private static void parkAddrLocked(int addr, int quadCount) {
        FreeEpoch epoch = freeEpochs.peekLast();
        if (epoch == null || epoch.frame != frameCounter) {
            epoch = new FreeEpoch(frameCounter);
            freeEpochs.addLast(epoch);
        }
        epoch.addrs.add(addr);
        epoch.quads += quadCount;
        parkedQuads += quadCount;
    }

    /**
     * {@code SectionRenderDispatcher.dispose()} HEAD: drop the whole store
     * (the GPU buffers are queued for deferred destroy by the vk side,
     * which calls this). Returns the pre-clear snapshot for the log line.
     */
    public static Counters disposeAndReset() {
        return disposeAndReset(true);
    }

    /**
     * Drop the store WITHOUT touching the upload seam, for the mid-world
     * renderer swap.
     *
     * <p>The seam must survive this. {@code resetForWorld()} sets
     * {@code suppressedThisWorld=false, vanillaHasGeometry=true}, and every
     * section the seam cancelled is one vanilla believes it already
     * uploaded. Wiping that state mid-world means nothing ever re-requests
     * those sections: a permanently empty world, not a transient gap. This
     * is the single sharpest edge in the whole swap.</p>
     */
    public static Counters disposeAndResetKeepingSeam() {
        return disposeAndReset(false);
    }

    private static Counters disposeAndReset(boolean resetSeam) {
        // The seam is per-world: a new world starts with vanilla whole
        // again, and must not inherit the last one's suppression state.
        if (resetSeam) {
            VanillaUploadSeam.resetForWorld();
        }
        synchronized (LOCK) {
            Counters snapshot = countersLocked();
            disposeSnapshot = snapshot;
            drawEpoch++;
            // Wave-11 policy (c): retention is per-WORLD — retained copies
            // die with the per-level dispatcher (dimension changes/world
            // exits route here via the next level's standup, note 11), so
            // nether terrain can never bleed into an overworld horizon.
            // They count as freed NOW (the copies leave custody with the
            // arena), which is also what keeps the wave-3b leak test's
            // "frees flow while the second world builds" assertion true
            // under retention: the releaseAllBuffers storm right before
            // this dispose orphans instead of freeing.
            freedSections += retained.size();
            retainedQuads = 0;
            retained.clear();
            parkedQuads = 0;
            pendingUploads.clear();
            pendingByPos.clear();
            resident.clear();
            freeEpochs.clear();
            pendingPrefixUploads.clear();
            regionStore = new RegionStore();
            arena = null; // the whole allocator dies with its buffer
            sectionRecordsHandle = 0L; // its VkBuffer is on the destroy queue too
            quadsResident = 0;
            // freedSections deliberately SURVIVES the reset: it is a
            // lifetime diagnostic, and dispose fires immediately AFTER the
            // frees it should witness (the per-level renderer's close runs
            // at the NEXT level's creation — run-log evidence 2026-08-09:
            // "dropped with the dispatcher: 0 sections / 0 quads ... 43
            // frees pending" right before the new world's "residency up").
            // A reset here erased the very evidence the leak test polls.
            // Wave-8 coverage guard: the drop counters are lifetime
            // diagnostics too, so the guard keys on drops SINCE this
            // baseline — a clean next world re-arms the kill switch.
            dropBaseline = droppedOversize + droppedArenaFull
                    + droppedRegionBudget + droppedEncoding;
            // Wave-14: the trip cause is per-world like the baseline.
            guardTrip = null;
            // Wave-15: grow state is per-world (a fresh world re-pins and
            // may grow again; a failed grow must not poison the next world).
            pendingGrowOption = 0;
            pinnedGrowFailed = false;
            return snapshot;
        }
    }

    // ------------------------------------------------------------------
    // Render-thread pump (via the vk side)
    // ------------------------------------------------------------------

    /**
     * The GPU side attached its arena (render thread, once per world).
     * {@code recordsHandle} is the section-records VkBuffer as an opaque
     * long (wave 5's task stage binds it; 0 keeps the task path off).
     */
    public static void attachArena(TerrainArena attached, long recordsHandle) {
        synchronized (LOCK) {
            arena = attached;
            sectionRecordsHandle = recordsHandle;
            // A fresh world starts busy by definition: the entire load-in
            // is about to happen, and a trim before it would only be
            // regrown through.
            lastBusyMillis = monotonicMillis();
        }
    }

    /**
     * One pump: advance the frame, release fence-expired frees, upload
     * queued sections (bounded), commit dirty regions, record the command
     * buffer. Render thread, inside vanilla's lock window — see class
     * Javadoc for why releases cannot interleave.
     */
    public static void pump(TerrainGpuHost gpu) {
        synchronized (LOCK) {
            long frame = ++frameCounter;
            // Wave-11 self-heal: reset() runs only on the render thread
            // (both callers are render-thread-only), which is this thread —
            // a depth stranded by an exception inside vanilla's reset can
            // therefore be cleared here, once per frame, before any hook
            // could misclassify a release. Misclassification's failure
            // direction is over-retention, which pressure eviction bounds;
            // this makes even that transient.
            RESET_DEPTH.get()[0] = 0;
            stagedBytesThisPump = 0;
            if (!gpu.beginFrame(frame)) {
                return;
            }
            if (arena != null) {
                // Wave-15: a live render-distance raise grows the pinned
                // budget FIRST — before any drain could hit the region
                // budget the raise is about to lift.
                consumePendingGrowLocked(gpu);
                releaseExpiredFreesLocked(frame);
                evictRetainedLocked();
                drainPendingUploadsLocked(gpu);
                drainPendingPrefixUploadsLocked(gpu);
                maybeTrimArenaLocked(gpu);
            }
            regionStore.commitDirty(gpu);
            gpu.endFrame();
            maybeLogStatsLocked(gpu);
        }
    }

    // ------------------------------------------------------------------
    // Wave-11 eviction (all under LOCK, called from the pump)
    // ------------------------------------------------------------------

    /**
     * The per-pump retained sweep, three rules in priority order, all
     * bounded by {@link #EVICT_BUDGET_PER_PUMP} so the lock window stays
     * flat (a backlog drains over following pumps):
     * <ol>
     *   <li><b>Toggle off</b> — retention disabled evicts everything
     *       retained (the harness's A1 leg; the copies leave through the
     *       fence epochs like every free, so frames in flight stay
     *       safe).</li>
     *   <li><b>Age</b> — when a limit is configured (minutes in config, 0
     *       = NO LIMIT; {@code meshelium.retainSeconds} test override), pop
     *       entries older than the cutoff off the head. Insertion order is
     *       age order (map javadoc), so this is O(evicted).</li>
     *   <li><b>Pressure</b> — regardless of any limit: past
     *       {@link #ARENA_HIGH_WATER_PCT} of the arena's quads (counting
     *       out ranges already parked toward freedom) or
     *       {@link #REGION_HIGH_WATER_PCT} of the region-id budget, evict
     *       oldest first until below. This runs BEFORE the upload drain,
     *       so retained hoarding is relieved before a live section could
     *       fail its allocation — the coverage guard never sees retention
     *       (the wave's central safety rule). Live sets alone cannot reach
     *       the region high-water (grid-bounded at ≤~700 of 2048 standard;
     *       ≤half the pinned budget extended), so pressure eviction only
     *       ever spends retained entries.</li>
     * </ol>
     */
    private static void evictRetainedLocked() {
        if (retained.isEmpty()) {
            return;
        }
        int budget = EVICT_BUDGET_PER_PUMP;
        if (!MesheliumConfig.retainTerrainEnabled()) {
            Iterator<Resident> it = retained.values().iterator();
            while (it.hasNext() && budget-- > 0) {
                Resident r = it.next();
                if (awaitingSuccessor(r)) {
                    continue;
                }
                it.remove();
                freeRetainedLocked(r);
                evictedByDisable++;
                drawEpoch++;
            }
            return;
        }
        long limitMillis = MesheliumConfig.retainLimitMillis();
        if (limitMillis > 0) {
            long cutoff = monotonicMillis() - limitMillis;
            Iterator<Resident> it = retained.values().iterator();
            while (it.hasNext() && budget > 0) {
                Resident r = it.next();
                if (r.orphanedAtMillis > cutoff) {
                    break; // insertion order == age order: the rest is younger
                }
                if (awaitingSuccessor(r)) {
                    continue;
                }
                it.remove();
                freeRetainedLocked(r);
                evictedByAge++;
                drawEpoch++;
                budget--;
            }
        }
        if (budget <= 0 || retained.isEmpty()) {
            return;
        }
        // Pressure: quads not already on their way out vs the CEILING
        // capacity (wave 14 — the arena is elastic, so measuring against
        // the current allocation would evict the horizon at 85% of a
        // small buffer growth was about to replace; the ceiling is the
        // real budget, and 15% of it dwarfs the 16 MiB/pump fill rate, so
        // eviction still beats exhaustion by construction). A ceiling
        // lowered below the current size (live property flip) makes this
        // deliberately aggressive: retained drains first, exactly the
        // wave-11 priority.
        long capacityQuads =
                MesheliumScaling.arenaCeilingBytes() / (4L * TerrainVertexCodec.VERTEX_STRIDE) - 1;
        long usedQuads = arena.liveQuads() - 1 - parkedQuads;
        long arenaHighWater = capacityQuads * ARENA_HIGH_WATER_PCT / 100;
        int regionHighWater = maxRegions() * REGION_HIGH_WATER_PCT / 100;
        Iterator<Resident> it = retained.values().iterator();
        while (it.hasNext() && budget > 0
                && (usedQuads > arenaHighWater || regionStore.regionCount() > regionHighWater)) {
            Resident r = it.next();
            if (awaitingSuccessor(r)) {
                continue;
            }
            it.remove();
            usedQuads -= r.quadCount;
            freeRetainedLocked(r);
            evictedByPressure++;
            drawEpoch++;
            budget--;
        }
    }

    /**
     * Wave-11 force-evict: an allocation or region-budget FAILURE landed
     * while retained entries exist. Evict a batch of the oldest (their
     * ranges mature in {@link #FREE_FRAME_LAG} pumps) so the caller can
     * REQUEUE the section instead of dropping it — no drop counter moves,
     * the coverage guard stays clean. Monotone progress: every call
     * shrinks the retained set, so a section too big for any horizon
     * eventually meets an empty retained set and the honest wave-8 drop
     * path. Over-eviction is bounded at {@value #FORCE_EVICT_BATCH} ×
     * {@value #FREE_FRAME_LAG} entries in the worst case — the correct
     * bias (retained horizon is decoration; live coverage is the
     * guard's contract).
     *
     * <p><b>This one does NOT skip handover copies</b>, unlike the three
     * ordinary sweeps. Those skip them because evicting a section's only
     * drawable copy while its replacement is in flight is the whole bug
     * this retention prevents. Here the alternative is dropping the
     * incoming section outright, which trips the coverage guard and makes
     * Meshelium passive for the rest of the world. A one-frame flash beats
     * that, and this method's own bias statement above already says so:
     * live coverage is the guard's contract.</p>
     *
     * @return true when at least one retained entry was evicted
     */
    private static boolean forceEvictRetainedLocked() {
        if (retained.isEmpty()) {
            return false;
        }
        int budget = FORCE_EVICT_BATCH;
        Iterator<Resident> it = retained.values().iterator();
        while (it.hasNext() && budget-- > 0) {
            Resident r = it.next();
            it.remove();
            freeRetainedLocked(r);
            evictedByPressure++;
            drawEpoch++;
        }
        retainedBackpressure++;
        return true;
    }

    /**
     * Wave-7: stage every permuted translucent prefix as a LATE arena copy
     * (recorded after this pump's normal copies behind a barrier — the
     * only same-frame WAW is a fresh upload of the same section, which the
     * late batch must overwrite). Staging-full keeps the resident queued;
     * the GPU shows the OLD (still coherent, fence-protected) order until
     * the copy lands — resort lag, never corruption. In-place overwrites
     * vs PRIOR frames' draws are ordered by vanilla's pass-end ALL_COMMANDS
     * barriers (every draw pass ends with one; the pump records after the
     * frame graph executed).
     */
    private static void drainPendingPrefixUploadsLocked(TerrainGpuHost gpu) {
        if (pendingPrefixUploads.isEmpty()) {
            return;
        }
        Iterator<Resident> it = pendingPrefixUploads.iterator();
        while (it.hasNext()) {
            Resident r = it.next();
            byte[] prefix = r.translucent.prefix;
            if (!gpu.stageArenaCopyLate(meterPrefixStage(prefix),
                    arena.blockOf(r.arenaAddr), arena.byteOffsetInBlock(r.arenaAddr))) {
                break; // ring full — the rest is next pump's backlog
            }
            it.remove();
            resortBytes += prefix.length;
        }
    }

    private static void releaseExpiredFreesLocked(long frame) {
        boolean freedAny = false;
        while (!freeEpochs.isEmpty() && frame - freeEpochs.peekFirst().frame >= FREE_FRAME_LAG) {
            FreeEpoch epoch = freeEpochs.pollFirst();
            IntArrayList addrs = epoch.addrs;
            for (int i = 0; i < addrs.size(); i++) {
                arena.free(addrs.getInt(i));
            }
            parkedQuads -= epoch.quads; // wave-11 pressure accounting
            freedAny = true;
        }
        if (freedAny) {
            arena.releasePending();
        }
    }

    private static void drainPendingUploadsLocked(TerrainGpuHost gpu) {
        long budget = UPLOAD_BYTES_PER_PUMP;
        Iterator<Map.Entry<Object, PendingUpload>> it = pendingUploads.entrySet().iterator();
        while (it.hasNext() && budget > 0) {
            Map.Entry<Object, PendingUpload> entry = it.next();
            PendingUpload p = entry.getValue();
            // The position index is reconciled once, in the finally below,
            // rather than beside each of this loop's five removal sites.
            // Patching them individually is how one gets missed, and a
            // pendingByPos that over-counts would retain a copy forever.
            Object key = entry.getKey();
            boolean removed = false;
            try {
                int bytes = p.encoded().geometryBytes();
                if (bytes > gpu.maxStageBytes()) {
                    it.remove();
                    removed = true;
                    noteGuardTripLocked("oversize",
                            (bytes + (1 << 20) - 1) >> 20, gpu.maxStageBytes() >> 20);
                    droppedOversize++;
                    continue;
                }
                // Pre-flight the region budget BEFORE any irreversible step
                // (once the staging copy is recorded there is no safe undo
                // within this command buffer — freeing and reallocating the
                // range would stack two same-frame copies with no barrier).
                if (!regionStore.hasCapacityFor(p.sx(), p.sy(), p.sz())) {
                    // Wave-15: a pinned-budget grow is queued (the raise
                    // that caused this very pressure) — keep the section
                    // queued and let next pump's grown budget admit it,
                    // instead of burning retained entries or dropping.
                    if (pendingGrowOption > 0) {
                        break;
                    }
                    // Wave-11: retained entries hoard region ids too —
                    // evict oldest and RETRY next pump instead of dropping
                    // (a drop here would trip the guard for retention's
                    // sake, the exact inversion of the wave's safety rule).
                    if (forceEvictRetainedLocked()) {
                        break; // section stays queued; ids free as regions empty
                    }
                    it.remove();
                    removed = true;
                    noteGuardTripLocked("region", regionStore.regionCount(), maxRegions());
                    droppedRegionBudget++;
                    continue;
                }
                int addr = arena.allocQuads(p.encoded().quadCount());
                if (addr == TerrainArena.ALLOC_FAILED
                        && growArenaLocked(gpu, p.encoded().quadCount())) {
                    // Wave-14: growth replaces prediction — the grown
                    // arena serves the very allocation that failed, same
                    // pump (the old→new copy is already submitted and
                    // barrier-ordered before this pump's staged copies).
                    addr = arena.allocQuads(p.encoded().quadCount());
                }
                if (addr == TerrainArena.ALLOC_FAILED) {
                    // Wave-14 order: growth first (above; exhausted or
                    // failed if we are here), retained eviction second
                    // (wave-11: requeue, no drop counter moves), the
                    // honest wave-8 drop LAST — the guard now trips only
                    // when growth is exhausted-or-impossible AND nothing
                    // retained is left to evict.
                    if (forceEvictRetainedLocked()) {
                        break; // section stays queued for the retry
                    }
                    it.remove();
                    removed = true;
                    noteGuardTripLocked("arena", arena.memoryBytes() >> 20,
                            MesheliumScaling.arenaCeilingBytes() >> 20);
                    droppedArenaFull++; // trips the wave-8 coverage guard
                    continue;
                }
                if (!gpu.stageArenaCopy(p.encoded().geometry(), arena.blockOf(addr),
                        arena.byteOffsetInBlock(addr))) {
                    // Staging full: nothing was recorded, the GPU never saw
                    // this range — undo is an immediate park-and-release
                    // (only this address is parked right now; epoch frees
                    // were flushed at pump start), and the loop stops: the
                    // remaining entries are this frame's backlog.
                    arena.free(addr);
                    arena.releasePending();
                    break;
                }
                it.remove();
                removed = true;
                Object mesh = entry.getKey();
                int[] bucketStarts = new int[com.deds.meshelium.terrain.QuadFacingBuckets.BUCKET_COUNT];
                int[] bucketCounts = new int[com.deds.meshelium.terrain.QuadFacingBuckets.BUCKET_COUNT];
                for (int b = 0; b < bucketStarts.length; b++) {
                    bucketStarts[b] = p.encoded().bucketStart(b);
                    bucketCounts[b] = p.encoded().bucketCount(b);
                }
                Resident r = new Resident(addr, p.encoded().quadCount(),
                        RegionStore.regionKey(p.sx(), p.sy(), p.sz()),
                        RegionStore.posKey(p.sx(), p.sy(), p.sz()),
                        p.sx(), p.sy(), p.sz(), bucketStarts, bucketCounts,
                        p.translucent());
                RegionStore.Assignment assignment =
                        regionStore.addOrReplace(p.sx(), p.sy(), p.sz(), p.encoded(), addr, r);
                if (assignment == null) {
                    // Cannot happen after the pre-flight (same lock, same
                    // iteration); if it ever does, the range is deliberately
                    // LEAKED until dispose rather than freed — its copy is
                    // already recorded (see the pre-flight comment).
                    throw new IllegalStateException(
                            "region assignment failed after capacity pre-flight");
                }
                if (assignment.previousOwner() instanceof Resident previous) {
                    if (previous.orphanedAtMillis != 0) {
                        // Wave-11 supersede: the previous owner is a
                        // RETAINED copy — nothing will ever release it
                        // again (its mesh is long dead), so the fresh
                        // upload frees it here, through the epochs.
                        // addOrReplace already cleared the retained mask
                        // bit and rebound the slot to the new owner.
                        Resident gone = retained.remove(posPack(previous.sx, previous.sy, previous.sz));
                        previous.ownsSlot = false;
                        if (gone == previous) {
                            pendingPrefixUploads.remove(previous);
                            retainedQuads -= previous.quadCount;
                            freedSections++;
                            retainedSuperseded++;
                            parkAddrLocked(previous.arenaAddr, previous.quadCount);
                        }
                    } else {
                        previous.ownsSlot = false;
                    }
                }
                resident.put(mesh, r);
                if (r.translucent != null && r.translucent.dirtySinceEncode) {
                    // A resort landed while this section waited in the
                    // backlog: the geometry copy above still carries the
                    // BUILD-time prefix (the encoder's buffer is immutable);
                    // queue the permuted prefix as a LATE copy — same pump,
                    // barrier-ordered after the geometry copy (WAW-safe).
                    r.translucent.dirtySinceEncode = false;
                    pendingPrefixUploads.add(r);
                }
                drawEpoch++;
                uploadedSections++;
                quadsResident += r.quadCount;
                budget -= bytes;
                stagedBytesThisPump += bytes;
            } catch (Throwable t) {
                // One bad section (e.g. a modded world outside the 9-bit
                // chunkY budget) must not kill the pump for the session.
                if (!removed) {
                    it.remove();
                }
                noteGuardTripLocked("encoding", 0, 0);
                droppedEncoding++;
                recordError("upload: " + t);
            } finally {
                if (!pendingUploads.containsKey(key)) {
                    pendingPosDrop(p.sx(), p.sy(), p.sz());
                }
            }
        }
    }

    /**
     * Wave-14 growth policy (under LOCK, render thread, mid-drain). Grows
     * the arena so the FAILING allocation fits: target =
     * min(ceiling, max(1.5 × current, current + needed)), whole MiB —
     * geometric so a world reaching N bytes pays O(log N) grow-and-copies
     * (total copy traffic ≤ ~2× final size), the {@code needed} term so a
     * single huge section cannot out-run the 1.5× step. The ceiling is
     * re-read per attempt ({@code MesheliumScaling.arenaCeilingBytes}:
     * property/device-derived — live property flips are the harness's
     * lever). Returns false when growth is EXHAUSTED (at ceiling, target
     * not above current) or the GPU side refused (allocation failure —
     * counted separately); the caller then falls through to the wave-11
     * retained eviction and, last, the honest wave-8 drop.
     */
    /**
     * Wave-16 quiet-time trim: hand the arena's committed-but-untouched
     * tail back to the driver.
     *
     * <p>Measured before built (PERFORMANCE.md 2026-08-16): an rd 64
     * session holds 492 to 496 MiB of tail with zero empty blocks, so
     * whole-block release recovers nothing and a shrink-copy of the last
     * block's extent recovers nearly all of it. The copy is bounded by the
     * EXTENT, roughly 20 MiB in that shape, and fires only after
     * {@code meshelium.tune.arenaTrimQuietSec} (default 30) of no arena
     * work, so it can never land inside load-in or a rebuild storm.
     * Regrowth after a trim is the ordinary wave-14 ladder; the quiet
     * window is what keeps the two from oscillating.</p>
     *
     * <p>Skipped unless the saving clears
     * {@code meshelium.tune.arenaTrimMinMiB} (default 64): a trim that
     * returns pennies still costs a copy and a retired buffer, and the
     * point is the half gigabyte, not the pennies.</p>
     */
    private static void maybeTrimArenaLocked(TerrainGpuHost gpu) {
        long now = monotonicMillis();
        // TWO conditions, deliberately different, because the first build
        // conflated them and could never fire in a real world.
        //
        // The TIMER measures "is the world settled". A live server random
        // ticks blocks forever - grass spreads, fluids flow - so a real
        // world recompiles a section every few seconds and is never
        // perfectly idle; a timer that reset on every trickle rebuild made
        // the trim fire only in a void superflat, which is exactly the
        // world the test used and exactly the world no player is in. So
        // the timer resets only when staged VOLUME says real streaming is
        // happening (load-in moves hundreds of MiB; the tick trickle moves
        // kilobytes), or when growth is queued.
        stagedBytesSinceQuiet += stagedBytesThisPump;
        if (stagedBytesSinceQuiet > (8L << 20) || pendingGrowOption > 0) {
            lastBusyMillis = now;
            stagedBytesSinceQuiet = 0;
        }
        // The HARD condition is "is THIS pump safe to swap in". Any arena
        // bytes staged this pump would be recorded at endFrame against
        // whichever backing is current by then, so swapping mid-pump under
        // them is forbidden absolutely - but it only skips THIS pump, it
        // does not reset the timer. The trickle keeps its own rebuilds
        // safe the same way: on their pump the trim yields, on the next
        // quiet pump it fires.
        if (stagedBytesThisPump > 0 || !pendingUploads.isEmpty()
                || !pendingPrefixUploads.isEmpty()) {
            return;
        }
        if (!MesheliumConfig.arenaTrimEnabled()
                || now - lastBusyMillis < Long.getLong("meshelium.tune.arenaTrimQuietSec", 30L) * 1000L) {
            return;
        }
        long capacity = arena.lastBlockBytes();
        long extent = arena.lastBlockExtentBytes();
        // Round the target up to whole MiB, floored so a nearly-empty top
        // block cannot shrink to a sliver the next join would immediately
        // regrow through.
        long target = Math.max(extent, 16L << 20);
        target = (target + (1L << 20) - 1) >> 20 << 20;
        long minSave = Long.getLong("meshelium.tune.arenaTrimMinMiB", 64L) << 20;
        if (capacity - target < minSave) {
            return;
        }
        long newHandle = gpu.trimArena(target, extent);
        if (newHandle == 0L) {
            // Refused (allocation failed mid-pressure, say). Re-arm the
            // quiet timer rather than retrying every pump: a driver that
            // just said no does not want to be asked 700 times a second.
            lastBusyMillis = now;
            return;
        }
        arena.shrinkLastBlock(target, newHandle);
        arenaTrims++;
        drawEpoch++;
    }

    /** Wave-16 probe: quiet-time tail trims this session. */
    public static long arenaTrims() {
        synchronized (LOCK) {
            return arenaTrims;
        }
    }

    /** Meter a resort-prefix restage for the trim's trickle accounting. */
    private static java.nio.ByteBuffer meterPrefixStage(byte[] prefix) {
        stagedBytesThisPump += prefix.length;
        return java.nio.ByteBuffer.wrap(prefix);
    }

    private static boolean growArenaLocked(TerrainGpuHost gpu, int quadCount) {
        long ceiling = MesheliumScaling.arenaCeilingBytes();
        long current = arena.memoryBytes();
        if (current >= ceiling) {
            return false; // growth exhausted — the ceiling is the honest limit
        }
        long needed = (long) quadCount * 4L * TerrainVertexCodec.VERTEX_STRIDE;
        long blockBytes = arena.blockBytes();
        long lastBlock = arena.lastBlockBytes();

        // GROW THE LAST BLOCK while it has room, THEN APPEND a new one.
        //
        // The split's real payoff for a player is here, not in the ceiling.
        // Growing means allocating a second buffer, copying every live byte
        // into it, and holding both until the fence clears - at multi-
        // gigabyte sizes that is a visible hitch precisely when flying into
        // new terrain, and it doubles peak VRAM at the worst moment.
        // Appending copies nothing and holds nothing extra. Keeping the
        // grow path for the first block matters just as much: a player who
        // needs 300 MiB must not be handed a 2 GiB allocation, so small
        // arenas behave exactly as before and only the tail becomes free.
        if (lastBlock < blockBytes) {
            long target = Math.max(lastBlock + (lastBlock >> 1), lastBlock + needed);
            target = (target + (1L << 20) - 1) >> 20 << 20; // whole MiB
            target = Math.min(target, blockBytes);
            target = Math.min(target, lastBlock + (ceiling - current)); // respect the ceiling
            if (target > lastBlock) {
                long newHandle = gpu.growArena(target);
                if (newHandle != 0L) {
                    arena.grow(target, newHandle);
                    arenaGrowths++;
                    drawEpoch++;
                    return true;
                }
                arenaGrowthFailures++;
                // Fall through: appending a fresh block asks the driver for
                // a DIFFERENT and possibly easier allocation - no copy, no
                // transient double-residency - so a refused grow is not
                // proof that a append will also fail.
            }
        }

        long appendBytes = Math.min(blockBytes, ceiling - current);
        appendBytes = appendBytes >> 20 << 20; // whole MiB
        if (appendBytes < needed || appendBytes <= 0) {
            return false; // no room under the ceiling for a useful block
        }
        // Will the CARD take it? Not "is Meshelium being greedy" - it is
        // supposed to be greedy, that is what buys the frames - but "is
        // there actually memory left". The static ceiling is a fraction of
        // a heap SIZE and knows nothing about what vanilla, the compositor
        // or another process already hold. If Meshelium takes the last of
        // it, the thing that dies is usually VANILLA, whose OOM path is a
        // bare IllegalStateException, and the crash report names a vanilla
        // texture upload. Unknown budget returns MAX_VALUE and this is a
        // no-op, which is exactly the behaviour before the probe existed.
        long headroom = com.deds.meshelium.MesheliumVramState.headroomBytes();
        if (headroom < appendBytes) {
            arenaGrowthFailures++;
            noteGuardTripLocked("vram", (current + appendBytes) >> 20,
                    (current + Math.max(0L, headroom)) >> 20);
            return false;
        }
        if (!arena.appendBlock(appendBytes)) {
            arenaGrowthFailures++;
            return false;
        }
        gpu.onArenaBlockAppended(appendBytes);
        arenaGrowths++;
        // The drawer binds the arena from the snapshot's opaque handle —
        // a new backing is a new era; the epoch bump republishes it (the
        // outgoing buffer stays alive and coherent for FREE_FRAME_LAG
        // frames, so a stale-by-a-frame draw stays safe exactly like
        // every other free in this store).
        drawEpoch++;
        return true;
    }

    // ------------------------------------------------------------------
    // Error latch + stats
    // ------------------------------------------------------------------

    /** First error wins; logged once (wave-2 containment pattern). */
    public static void recordError(String message) {
        if (lastError == null) {
            lastError = message;
            MesheliumClient.LOGGER.error(
                    "Meshelium terrain residency error (first and only report): {}", message);
            // Called from chunk build WORKERS as well as the render thread;
            // MesheliumNotify marshals, so this is safe from either.
            com.deds.meshelium.MesheliumNotify.chat("meshelium.chat.error.residency");
        }
    }

    private static Counters countersLocked() {
        long backlogBytes = 0;
        for (PendingUpload p : pendingUploads.values()) {
            backlogBytes += p.encoded().geometryBytes();
        }
        int pendingFreeRanges = arena == null ? 0 : arena.pendingFreeCount();
        for (FreeEpoch epoch : freeEpochs) {
            pendingFreeRanges += epoch.addrs.size();
        }
        long arenaUsed = 0;
        long arenaCapacity = 0;
        long arenaExtent = 0;
        int arenaBlocks = 0;
        int emptyTopBlocks = 0;
        if (arena != null) {
            // liveQuads includes the reserved quad 0; report without it so
            // an empty arena reads 0 (the teardown assertion's baseline).
            arenaUsed = (arena.liveQuads() - 1) * 4L * TerrainVertexCodec.VERTEX_STRIDE;
            arenaCapacity = arena.memoryBytes();
            // Same reserved-quad baseline, so extent and used are comparable
            // and a fully churned arena reads 0 rather than one quad.
            arenaExtent = (arena.quadExtent() - 1) * 4L * TerrainVertexCodec.VERTEX_STRIDE;
            arenaBlocks = arena.blockCount();
            emptyTopBlocks = arena.emptyTopBlocks();
        }
        return new Counters(
                frameCounter,
                resident.size(), quadsResident,
                arenaUsed, arenaCapacity,
                regionStore.regionCount(), regionStore.dirtyRegionCount(),
                pendingUploads.size(), backlogBytes,
                pendingFreeRanges,
                encodedSections, uploadedSections, freedSections,
                discardedBeforeUpload, droppedOversize, droppedArenaFull,
                droppedRegionBudget, droppedEncoding,
                staleParks, decoderSkippedLayers,
                resortsApplied, resortBytes, resortsNoop,
                resortsUnknownMesh, resortsMalformed,
                retained.size(), retainedQuads,
                orphanedSections, retainedSuperseded,
                evictedByAge, evictedByPressure, evictedByDisable,
                retainedBackpressure,
                arenaGrowths, arenaGrowthFailures,
                arenaExtent, arenaBlocks, emptyTopBlocks);
    }

    /**
     * Once-per-5s INFO line. Since wave 8 the switch is the config matrix:
     * {@code meshelium.debugStats} property ?? {@code config.debugStats}.
     */
    private static void maybeLogStatsLocked(TerrainGpuHost gpu) {
        if (!MesheliumConfig.debugStatsEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastStatsNanos < 5_000_000_000L) {
            return;
        }
        lastStatsNanos = now;
        Counters c = countersLocked();
        MesheliumClient.LOGGER.info(
                "meshelium residency: sections={} quads={} retained={}/{} quads arena={}/{} MiB "
                        + "(ceiling {} MiB, growths={}, trims={}, growthFailures={}) "
                        + "holes={} MiB tail={} MiB emptyTopBlocks={}/{} "
                        + "regions={} (dirty {}) stagingBacklog={} entries/{} KiB ringUsed={} KiB "
                        + "freesPending={} encoded={} uploaded={} "
                        + "drops[oversize={},arena={},region={},encode={}] "
                        + "retention[orphaned={},superseded={},evictAge={},evictPressure={},"
                        + "evictOff={},backpressure={}] handoverHeld={} discarded={} staleParks={} "
                        + "greedyMerge[{}]",
                c.sectionsResident(), c.quadsResident(),
                c.retainedSections(), c.retainedQuads(),
                c.arenaUsedBytes() >> 20, c.arenaCapacityBytes() >> 20,
                MesheliumScaling.arenaCeilingBytes() >> 20,
                c.arenaGrowths(), arenaTrims, c.arenaGrowthFailures(),
                // holes = what compaction could recover; tail = what is
                // already free above the high-water mark and needs no
                // compaction at all. Keeping them apart is the whole point:
                // used-vs-committed conflates them and answers neither.
                Math.max(0, c.arenaExtentBytes() - c.arenaUsedBytes()) >> 20,
                Math.max(0, c.arenaCapacityBytes() - c.arenaExtentBytes()) >> 20,
                c.emptyTopBlocks(), c.arenaBlocks(),
                c.regionsLive(), c.regionsDirty(),
                c.stagingBacklogEntries(), c.stagingBacklogBytes() >> 10,
                gpu.stagingUsedBytes() >> 10, c.pendingFreeRanges(),
                c.encodedSections(), c.uploadedSections(),
                c.droppedOversize(), c.droppedArenaFull(), c.droppedRegionBudget(),
                c.droppedEncoding(),
                c.orphanedSections(), c.retainedSuperseded(), c.evictedByAge(),
                c.evictedByPressure(), c.evictedByDisable(), c.retainedBackpressure(),
                // handoverHeld: old copies kept alive across a rebuild until
                // their successor landed. Each one is a black chunk that did
                // not happen, so this rising while the picture stays clean IS
                // the fix working. discarded/staleParks are the two ways an
                // encoded section can vanish before reaching the GPU, and
                // both were invisible in this line while the bug was hunted.
                handoverRetained, c.discardedBeforeUpload(), c.staleParks(),
                // The other side of the merge's ledger: what it costs the
                // build workers. Off the frame path, so no bench frame time
                // can show it, but slower section builds are slower pop-in.
                com.deds.meshelium.terrain.GreedyMesher.costSummary());
        // The other half of the terrain bill: vanilla's own copy, which
        // nothing draws while Meshelium owns the frame. Measured, not
        // derived - see VanillaTerrainCensus.
        long vanillaBytes = com.deds.meshelium.VanillaTerrainCensus.committedBytes();
        long arenaBytes = c.arenaCapacityBytes();
        MesheliumClient.LOGGER.info(
                "meshelium terrain bill: meshelium {} MiB, VANILLA {} MiB{} (vanilla keeps a full "
                        + "second copy that nothing draws while Meshelium owns the frame)",
                arenaBytes >> 20,
                vanillaBytes < 0 ? -1 : vanillaBytes >> 20,
                vanillaBytes > 0 && arenaBytes > 0
                        ? String.format(" = %.2fx ours", (double) vanillaBytes / arenaBytes) : "");
        long budget = com.deds.meshelium.MesheliumVramState.budgetBytes();
        MesheliumClient.LOGGER.info(
                "meshelium vram: budget={} MiB used={} MiB ({}%) headroom={} MiB{}",
                budget >> 20,
                com.deds.meshelium.MesheliumVramState.usageBytes() >> 20,
                com.deds.meshelium.MesheliumVramState.pressurePct(),
                budget <= 0 ? -1
                        : com.deds.meshelium.MesheliumVramState.headroomBytes() >> 20,
                budget <= 0 ? " (UNKNOWN - no VK_EXT_memory_budget; the static ceiling is the "
                        + "only bound, exactly as before this probe existed)" : "");
    }
}
