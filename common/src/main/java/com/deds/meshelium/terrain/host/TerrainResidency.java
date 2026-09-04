/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.terrain.host;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumScaling;
import com.deds.meshelium.terrain.EncodedSectionMesh;
import com.deds.meshelium.terrain.TerrainArena;
import com.deds.meshelium.terrain.TranslucentPrefix;
import com.deds.meshelium.terrain.TerrainVertexCodec;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

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
            TranslucentState translucent, byte builtTier) {}

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

    // ------------------------------------------------------------------
    // THE OWNERSHIP LEDGER (docs/FARFIELD-SEAM-DESIGN.md; shadow at seam
    // step 1, ENFORCING since steps 3-4).
    //
    // A per-Resident state byte written under LOCK at every ownership
    // transition. Since step 4 it is LOAD-BEARING: the eviction sweeps
    // skip AWAITING entries by this byte alone, the far admission's
    // bridged test IS this byte, and E1/E2 act on it. THE INVARIANT it
    // enforces (COVERAGE CONTINUITY): a position that is drawn and is
    // far-domain under the published coverage geometry is never unbound
    // - it is only ever REPLACED (free and bind in one LOCK hold, one
    // drawEpoch bump), or released through exactly three enumerated
    // exits: E1 the position left the far domain (re-evaluated at each
    // decision; the OUTER edge or a withdrawn geometry - the inner edge
    // exits via the supersede bind alone), E2 the AWAITING memory budget
    // evicted it farthest-first (the single sanctioned violation,
    // counted on ledgerEvictedWall), E3 world-era end. There is no deadline after which a hold may
    // flash and no per-frame cap past which a release may free - those
    // mechanisms are DELETED, not disabled.
    //
    // THE LEGAL TABLE ({@link #ledgerLegalTransition}). States:
    //   UNBOUND    - constructed, not yet in any container
    //   NEAR_LIVE  - in {@link #resident} (mesh-keyed; slotless included)
    //   NEAR_HELD  - in {@link #retained}, no queued successor, not
    //                coverage-parked (wave-11 distance retention)
    //   FAR        - in {@link #farResident}
    //   AWAITING_SUCCESSOR - in {@link #retained} AND deliberately held
    //                for a replacement: a queued vanilla upload at the
    //                position (the rebuild handover) or a step-4
    //                coverage park (the position is far-domain and the
    //                far field owes it a successor)
    //   CLEARED    - freed; out of every container. Terminal.
    // Edges:
    //   UNBOUND    -> NEAR_LIVE (upload bind) | FAR (far admission bind)
    //   NEAR_LIVE  -> NEAR_HELD | AWAITING_SUCCESSOR | CLEARED
    //   NEAR_HELD  -> AWAITING_SUCCESSOR (a successor gets queued at a
    //                 held position) | CLEARED (evictions, supersede)
    //   AWAITING_SUCCESSOR -> CLEARED, and ONLY CLEARED: the swap, a
    //                 vanilla supersede/empty compile, E1, E2, E3. The
    //                 step-1 table's AWAITING -> NEAR_HELD edge (deadline
    //                 expiry, dead queued successor) is DEAD: its
    //                 producers were deleted at step 4, so the edge is
    //                 now illegal and {@link #ledgerDemoted} must read
    //                 zero for the rest of the project.
    //   FAR        -> CLEARED (ring-exit demote E1, reload, force-evict
    //                 E2, empty compile, vanilla supersede, and the
    //                 step-2 REPLACEMENT: a fresher shell's admission
    //                 swapping our own far copy in place)
    // Anything else observed is counted on
    // {@link #ledgerIllegalTransitions} and reported (budgeted log).
    // Zero stays the contract; prevention is structural - the sites
    // that produced the dead edges no longer exist.
    //
    // E3 (world-era end, {@link #disposeAndReset}) clears the containers
    // wholesale; the ledger performs NO per-entry transitions there -
    // the entries die with the store - and only the AWAITING gauge is
    // reset. The swap edge is counted on the PREDECESSOR
    // (AWAITING -> CLEARED, or FAR -> CLEARED for a step-2 replacement,
    // via {@link #ledgerResolvedSwap}); the
    // successor is an ordinary UNBOUND -> FAR bind.
    // ------------------------------------------------------------------

    static final byte LEDGER_UNBOUND = 0;
    static final byte LEDGER_NEAR_LIVE = 1;
    static final byte LEDGER_NEAR_HELD = 2;
    static final byte LEDGER_FAR = 3;
    static final byte LEDGER_AWAITING_SUCCESSOR = 4;
    static final byte LEDGER_CLEARED = 5;

    /** Transitions INTO AWAITING_SUCCESSOR: the rebuild handover, the
     * step-4 coverage park (every free of a far-domain-covered drawn
     * position), and a held copy whose position gained a queued
     * successor. */
    private static long ledgerParked;
    /** AWAITING -> NEAR_HELD. DEAD since step 4: its producers (the
     * deadline expiry demote, the dead-successor demote) were deleted
     * with the deadlines, so this counter MUST read zero - it is kept
     * precisely so a resurrection of either producer is visible, and the
     * suites assert on it. */
    private static long ledgerDemoted;
    /** A predecessor ended by the atomic far swap
     * ({@code admitFarSectionLocked}'s previousOwner arm) - the one
     * exchange the invariant permits at a covered position. Two
     * producers since seam step 2: the parked handover (AWAITING ->
     * CLEARED - since step 4 the sole successor to the deleted
     * {@code farHandoverSwaps}) and the replace-in-place
     * refresh (FAR -> CLEARED, an edited drawn column swapping to its
     * fresher shell without ever leaving the screen - the counter the
     * deleted {@code farStaleResidentRefiled} gave way to). */
    private static long ledgerResolvedSwap;
    /** AWAITING (or its position) ended by vanilla's own truth: a fresh
     * upload superseded the parked copy, or the section compiled empty. */
    private static long ledgerResolvedVanilla;
    /** E1: AWAITING released because the position left the coverage's
     * OUTER edge, or the geometry was withdrawn - the step-4 per-pump
     * bucket scan ({@link #scanAwaitingCoverageLocked}) is its producer.
     * NOT the inner edge: a park vanilla's disc re-covers exits via the
     * supersede bind ({@code ledgerResolvedVanilla}), the P9 seamless
     * direction (leg-B first-run catch, 2026-08-24). Every travel-leg
     * exit that is not a swap or a supersede should land here, never on
     * the wall. */
    private static long ledgerResolvedUncovered;
    /** E2: AWAITING entries spent for MEMORY - the AWAITING quad budget
     * ({@link #evictAwaitingFarthestLocked}) and the force-evict wall.
     * The design's one sanctioned continuity violation; the ONLY ledger
     * counter that may be nonzero when the seam misbehaves. */
    private static long ledgerEvictedWall;
    /** Sum of camera-relative Chebyshev rings (chunks) at those
     * evictions; mean = this / {@link #ledgerEvictedWall}. Farthest-first
     * eviction must keep the mean out toward the fog; a mean near
     * coverRadius is the wall eating the visible seam. 0 is added when no
     * camera is published (session edges only). */
    private static long ledgerEvictedWallRingSum;
    /** Milliseconds AWAITING entries held before ANY resolution (swap,
     * vanilla, demote, wall), summed - the P9 swapHold pair generalised
     * to every exit, MISS holds included. Measured from
     * {@code orphanedAtMillis} (stamped at the arm; for a held copy
     * promoted by a queued successor it spans the whole orphan hold,
     * P9's own approximation). */
    private static long ledgerHoldMillis;
    /** Worst single AWAITING hold, milliseconds. */
    private static long ledgerHoldMaxMillis;
    /** GAUGE: quads currently parked AWAITING_SUCCESSOR. Kept consistent
     * with the state bytes by {@link #ledgerTransitionLocked} even across an
     * illegal transition, so the peak below stays meaningful. */
    private static long ledgerAwaitingQuads;
    /** High-water mark of {@link #ledgerAwaitingQuads} this session: the
     * number that prices step 4's AWAITING_BUDGET_QUADS before the caps
     * it replaces are deleted. */
    private static long ledgerAwaitingQuadsPeak;
    /** Observed transitions outside the legal table. Zero is the
     * contract; any nonzero is a model error in the table or a real
     * ownership bug, and either one must be understood BEFORE step 2. */
    private static long ledgerIllegalTransitions;
    /** Debug-audit mismatches (state byte vs container membership).
     * Only moves with {@code -Dmeshelium.debug.ledgerAudit=true}. */
    private static long ledgerAuditMismatches;
    /** Log budget shared by the illegal-transition and audit reports;
     * counting never stops when it runs out. */
    private static int ledgerReportBudget = 8;
    /** The per-pump membership audit's gate. Property-only, default off:
     * it walks every container under LOCK, which is a debug price. */
    private static final boolean LEDGER_AUDIT =
            Boolean.getBoolean("meshelium.debug.ledgerAudit");

    private static final class Resident {
        final int arenaAddr;
        final int quadCount;
        final long regionKey;
        final int posKey;
        boolean ownsSlot = true;
        /**
         * Stable index into the slot-indexed draw-snapshot buffers: taken
         * from the free-list at the SINGLE construction site, freed at the
         * five death sites (release-free, retained supersede, dig-out,
         * eviction, dispose). Per arena COPY, not per position — the
         * handover window legitimately holds two slots for one section
         * (the old retained copy and its freshly uploaded successor).
         */
        final int snapshotSlot;
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
         * Smart/Solid Leaves Beyond: the leaf-detail tier this arena copy
         * was built at ({@code SectionBuildTap.TIER_NONE}/{@code
         * TIER_SMART}/{@code TIER_SOLID} — SMART had its cutout interior
         * pairs filtered out, SOLID additionally had its surviving
         * cutouts rewritten opaque; the build tap stamps the tier that
         * actually CHANGED the list). The pump's ring-crossing walker
         * re-dirties a section through vanilla once its distance would
         * only earn a LOWER tier than this, and RESETS the field to
         * TIER_NONE as it collects, so a section is re-dirtied exactly
         * once per tiered build however long its replacement takes to
         * arrive. Mutable under LOCK like {@link #ownsSlot}; deliberately
         * NOT part of the snapshot entry — nothing drawable changes when
         * it moves.
         */
        byte builtTier;
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
        /**
         * K2/L10: this LIVE entry sits outside vanilla's compile disc, so
         * {@code visibleSections} cannot list it and
         * {@link #syncUnlistedLiveMaskLocked} is drawing it from the
         * wave-11 retained MASK instead. Set and cleared by that sweep
         * only, and ONLY on entries that still own their slot.
         *
         * <p>It exists because the mask fixes the OPAQUE half alone. The
         * translucent pre-pass has no mask to read: it walks the snapshot
         * and gates on {@code [19]}, so before this flag a lobe column's
         * water was dispatched by neither the pre-pass (the entry is
         * live) nor the visible loop (the entry is not listed), and the
         * player saw a sea bed with no surface on it exactly where pre8
         * had just fixed the land. The flag joins {@code orphanedAtMillis}
         * as the second reason to write {@code [19]} = 1, which is why
         * that bit now means "not drawn from visibleSections" rather than
         * "which map holds it".</p>
         *
         * <p>Deliberately NOT {@code orphanedAtMillis} itself, though
         * stamping that would have set {@code [19]} in one line:
         * {@link #drainPendingUploadsLocked} reads the stamp as "this
         * previous owner is retained, so nothing will ever release it and
         * the fresh upload may free it here". Stamping a LIVE resident
         * would hand its arena range and snapshot slot to the epochs
         * while vanilla still holds the mesh that owns them, and the
         * mesh's own later release would free them a second time.</p>
         */
        boolean unlistedDrawn;
        /**
         * The ownership-ledger state byte (the LEDGER_* constants
         * above). Written under LOCK at every transition by
         * {@link #ledgerTransitionLocked}. LOAD-BEARING since seam step
         * 4: the eviction sweeps skip AWAITING entries on this byte
         * alone, {@code admitFarSectionLocked}'s bridged test is
         * {@code ledgerState == LEDGER_AWAITING_SUCCESSOR}, and the
         * E1/E2 machinery acts on it. Step 1 ran it as a pure shadow
         * for a phase to prove it tracks reality before any decision
         * read it.
         */
        byte ledgerState = LEDGER_UNBOUND;

        Resident(int arenaAddr, int quadCount, long regionKey, int posKey,
                int sx, int sy, int sz, int[] bucketStarts, int[] bucketCounts,
                TranslucentState translucent, int snapshotSlot) {
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
            this.snapshotSlot = snapshotSlot;
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
    /**
     * Far-field wave W3: packed section position → far shell Resident.
     * Far sections are born retained-SHAPED (position-keyed, no
     * {@code CompiledSectionMesh} identity — landmine L2,
     * FARFIELD-CODEBASE-SEAM.md) but live in their OWN map, deliberately
     * NOT in {@link #retained}: the retained sweeps' semantics would be
     * wrong for them ({@code evictRetainedLocked}'s toggle-off drain
     * fires whenever {@code retainTerrain} is off — the DEFAULT — and
     * its age/pressure arms order by orphan stamp, while far entries
     * demote by DISTANCE through the {@code FarFieldResidency} walker).
     * They still ride every retained draw mechanism: snapshot flag
     * {@code [19]} = 1 (via {@code orphanedAtMillis}, stamped at
     * admission), {@code RegionStore.markRetained} mask bits for the
     * BFS path, and the ordinary occlusion/cpuCull paths untouched.
     * Insertion order = admission order (near-first, the walker's
     * spiral); {@link #forceEvictFarLocked} deliberately does NOT pop it
     * — since step 5 the wall spends this map farthest-ring-first, so
     * the sanctioned violation lands in the fog, not beside the player.
     */
    private static final LinkedHashMap<Long, Resident> farResident = new LinkedHashMap<>();
    /**
     * Far sections freed by residency-side paths (vanilla supersede,
     * empty recompile, force-evict) whose {@code FarFieldResidency}
     * bookkeeping is deferred to the pump's far drain: the empty-compile
     * hook runs on BUILD threads, and the walker's column maps are
     * render-thread confined — parking the packed position here (under
     * LOCK) keeps that confinement without a second lock.
     */
    private static final it.unimi.dsi.fastutil.longs.LongArrayList farFreedPending =
            new it.unimi.dsi.fastutil.longs.LongArrayList();
    /**
     * SEAM step 4: positions whose park just filed a demand for a
     * successor — the ledger transition's wakeup channel, appended under
     * LOCK wherever a free of a covered position becomes an
     * {@code AWAITING_SUCCESSOR} park (and by {@link #pendingPosDrop}
     * when a queued vanilla successor dies over a covered parked copy).
     * Drained by the pump's far drain exactly as the deleted
     * {@code farBlockerFreedPending} was: handed to the walker's
     * unguarded priority path ({@code onFarBlockerReleased} →
     * {@code unblockedColumns}). This is the ONE channel kept from the
     * pre6 contested watch: the watch set, its cap, and its
     * degrade-to-pre-fix failure mode are gone, because a contested
     * position announces itself when it parks — no arming, no cap, no
     * position that can fall off the end of a set.
     */
    private static final it.unimi.dsi.fastutil.longs.LongArrayList successorRequests =
            new it.unimi.dsi.fastutil.longs.LongArrayList();
    /**
     * SEAM step 4, E2's eviction structure: parked AWAITING positions
     * bucketed by camera-relative Chebyshev ring (chunks) AT PARK TIME.
     * Farthest-bucket-first pop, O(1) amortised, no re-sort as the
     * camera moves — a storm resolves in seconds, so ring-at-park is an
     * honest approximation and {@code ledgerEvictedWallRingSum}'s
     * percentiles are what prove it. Entries are removed LAZILY: a
     * bucket slot is live only while the retained entry at that position
     * is still {@code AWAITING_SUCCESSOR}; every consumer validates
     * before acting, so exits through swap/vanilla/E1/E3 cost nothing
     * here. Maintained by {@link #ledgerTransitionLocked} so bucket
     * membership can never drift from the state byte's own gauge.
     */
    private static final it.unimi.dsi.fastutil.longs.LongArrayList[] awaitingByRing =
            new it.unimi.dsi.fastutil.longs.LongArrayList
                    [com.deds.meshelium.farfield.FarFieldConfig.MAX_L1_RADIUS_CHUNKS + 2];
    /**
     * SEAM step 4, THE MEMORY BOUND that replaced every deleted cap and
     * deadline: quads that may sit parked {@code AWAITING_SUCCESSOR} at
     * once — {@code min(1/8 of the arena quad ceiling,
     * {@value #AWAITING_BUDGET_SECTIONS} sections' worth at the measured
     * mean)}. Parking allocates NOTHING (it defers a free of geometry
     * already resident, the {@code FREE_FRAME_LAG} kind of hold with a
     * data-dependent horizon); this bounds the transient overshoot of
     * predecessor + successor both staged. Past it
     * {@link #evictAwaitingFarthestLocked} spends parked entries
     * farthest-ring-first — the design's ONE sanctioned continuity
     * violation (E2), counted on {@code ledgerEvictedWall}, pushed into
     * the fog where the deleted LRU used to spend them at the camera.
     * Cached once per pump ({@code MesheliumScaling.arenaCeilingBytes}
     * reads system properties; parks happen on build threads).
     */
    private static final int AWAITING_BUDGET_SECTIONS = 6144;
    /**
     * Mean quads per real section, measured: docs/PERFORMANCE.md's spin
     * holds 26,990 sections in 1,650 MiB, ~61 KiB a section at the
     * 64-byte quad — ~953 quads. Priced, not guessed, exactly as the
     * deleted speculative cap's 61 KiB figure was.
     */
    private static final long AWAITING_MEAN_QUADS_PER_SECTION = 953;
    /** The cached budget (quads); refreshed at each pump head. */
    private static long awaitingBudgetQuads =
            AWAITING_BUDGET_SECTIONS * AWAITING_MEAN_QUADS_PER_SECTION;
    /**
     * E1's per-pump sweep cursor over {@link #awaitingByRing}, so the
     * coverage-exit scan resumes where it stopped when the parked
     * population outruns {@link #EVICT_BUDGET_PER_PUMP} in one pump.
     */
    private static int awaitingScanRing;
    /**
     * Far-field admission budget per pump (the leaf-tier walker's
     * 64/pump discipline, docs/FARFIELD-WAVES.md standing rules).
     */
    private static final int FAR_ADMISSIONS_PER_PUMP = 64;
    /** Far promotion stops above this share of the region-id budget. */
    private static final int FAR_REGION_GUARD_PCT = 85;
    /**
     * Far promotion stops above this share of the arena ceiling — set
     * BELOW {@link #ARENA_HIGH_WATER_PCT} on purpose so far fill can
     * never push the arena into the retained pressure sweep and start
     * evicting real geometry to house an approximation of it.
     */
    private static final int FAR_ARENA_GUARD_PCT = 70;

    // ------------------------------------------------------------------
    // H2, THE CORNER GAP: positions Meshelium OWNS and nobody DRAWS
    // (docs/FARFIELD-WAVES.md, OWNER PLAYTEST OF pre7, item H2)
    //
    // The owner described the shape exactly: "its like the center is doing
    // regular cylindrical chunk loading, and the box that swaps the lod
    // chunks in behind me is doing square shaped chunk loading. thats what
    // the gap looks like, the corner of a square." Both halves are
    // literally true of vanilla, and the gap is the set between them.
    //
    // THE CYLINDER — what vanilla LISTS. SectionOcclusionGraph's BFS only
    // steps to a neighbour that passes getRelativeFrom's gate (javap, 26.2
    // merged jar, this session, ip 8-19): isInViewDistance(camera, next),
    // which is ChunkTrackingView.isInViewDistance(IIIII) with iconst_0 ->
    // isWithinDistance(..., false) -> pad 1 ->
    //     max(0,|dx|-1)^2 + max(0,|dz|-1)^2 < vd*vd     (STRICT, ip 45-77)
    // so visibleSections is a pad-1 DISC of radius vd, and the drawer's
    // BFS-mask path (TerrainDrawer.drawTaskCulled, the default whenever
    // occlusion Auto is below its render-distance crossover of 48) draws
    // exactly visibleSections OR the retained mask.
    //
    // THE SQUARE — what vanilla KEEPS, and therefore what Meshelium owns.
    // ViewArea holds its sections in a RotatingSectionStorage built with
    // Options.getEffectiveRenderDistance() as its radius
    // (LevelRenderer.invalidateCompiledGeometry ip 149-184, javap), and
    // that storage's membership test is CHEBYSHEV (containsSection, javap:
    // centerX - radius <= x <= centerX + radius, same for z). A compiled
    // mesh therefore lives until its grid slot is RECYCLED, which happens
    // at Chebyshev radius + 1: repositionCenter rewraps every node and
    // calls Value.setSectionNode on the ones that moved (javap ip 140-166),
    // and RenderSection.setSectionNode's FIRST instruction is
    // invokevirtual reset() (javap ip 0) — the release Meshelium hooks.
    //
    // THE WEDGE. Square minus disc is the four corner lobes. A section
    // there was compiled while the column was inside the disc and has
    // since fallen out of it while staying inside the square, so:
    // vanilla no longer lists it (not drawn), Meshelium still owns its
    // region slot (admitFarSectionLocked's isOccupied pre-flight refuses
    // the far copy with ADMIT_CONTESTED), and the contested watch cannot
    // discharge until the SQUARE releases the slot. Drawn by nobody, and
    // bounded outside by two straight square edges meeting at a right
    // angle: "the corner of a square". It only fills up while RECEDING —
    // a column entering the lobe from outside was never compiled, so its
    // slot is free and the far field may have it.
    //
    // THE FIX. The geometry is already in the arena with a region slot
    // and a snapshot record; all it lacks is a mask bit. So the near
    // field keeps drawing everything it owns once vanilla's list stops
    // covering it — the wave-11 retained mask, pointed at live sections,
    // which is fail-open (a set bit can only ADD a draw) and strictly
    // better than the alternative, since it is real terrain rather than a
    // shell approximation of it.
    // ------------------------------------------------------------------

    /**
     * Camera section the unlisted-live mask sweep last ran against, so it
     * runs on a camera SECTION crossing and never per frame. Render thread
     * only, under LOCK.
     */
    private static long unlistedSweepCamera = SectionBuildTap.CAMERA_SECTION_UNKNOWN;
    /** Effective render distance the sweep last ran against (slider re-arm). */
    private static int unlistedSweepRadius = -1;
    /**
     * O7: camera SECTION Y the sweep last ran against. The H2 arm key was
     * the packed XZ camera and the slider, and a player flying straight up
     * moves NEITHER — so the sweep the altitude arm below lives in would
     * never have re-run for the one motion that needs it.
     * {@link Integer#MIN_VALUE} when no camera has been read.
     */
    private static int unlistedSweepCameraY = Integer.MIN_VALUE;
    /** Live sections the last sweep found outside vanilla's compile disc. */
    private static int unlistedLiveDrawn;
    /**
     * O7: of {@link #unlistedLiveDrawn}, the ones drawn because of the
     * ALTITUDE arm rather than because they left vanilla's disc. This is
     * the whole cost of the O7 fix in one number: it is 0 at ground level
     * and it is how much BFS occlusion culling the BFS-mask path gives up
     * while the camera is high. Read it against frame time.
     */
    private static int unlistedAltitudeDrawn;
    /**
     * S2: of {@link #unlistedAltitudeDrawn}, the marks that the pre18
     * gate ({@code camSy - sy > 3}) would NOT have made — sections within
     * three sections of the camera's own Y that vanilla nevertheless has
     * on its ray-marched arm because they are more than three sections
     * away HORIZONTALLY. This is the S2 band, and the whole added cost of
     * the S2 fix in one number: 0 at ground level in any relief (every
     * column tops at or above the camera, so the local horizon is down
     * everywhere), rising per COLUMN as the camera clears each one — S6's
     * change, and the reason it no longer waits on a disc-wide p90 — and
     * collapsing again at altitude because by then the same sections are
     * more than three sections BELOW and the pre18 gate claims them.
     *
     * <p>pre21 (docs/FARFIELD-WAVES.md, "FLY-UP ANSWERED (seventh
     * attempt: the core)"): the rule no longer stops at the 7x7 core. The
     * core exclusion was an inconsistency (the same section class was
     * covered at {@code |dx| = 4} and not at {@code |dx| = 3}) and, at
     * render distance 2, the whole disc IS the core, so the rule marked
     * nothing at the owner's setting. {@link #unlistedBandCoreDrawn} is
     * the core's share.</p>
     *
     * <p>Read it against {@code SectionBuildTap.visibleSectionsListed()}:
     * that pair is what discriminates the two remaining stories. A
     * collapsing {@code listed} with a rising {@code band} and a clean
     * picture is this fix working. A collapsing {@code listed} with a
     * rising {@code band} and holes STILL on screen is the other story —
     * the missing positions were never compiled, so no mask can draw
     * them, and the cure is listing or far coverage, not marking.</p>
     */
    private static int unlistedBandDrawn;
    /**
     * pre21: of {@link #unlistedBandDrawn}, the marks made INSIDE vanilla's
     * 7x7 adjacency core ({@code |dx| <= 3 && |dz| <= 3}) — the whole
     * added population of the seventh fly-up fix, in one number. At most
     * 49 columns times the slab under the camera; nearly all of them are
     * sections vanilla lists anyway (no ray march runs in the core), so
     * the DRAWN delta is the handful vanilla's face graph refused. At
     * render distance 2 it is the entire {@code band}.
     */
    private static int unlistedBandCoreDrawn;
    /** Mask bits the sweep has flipped this session (both directions). */
    private static long unlistedMaskFlips;
    /**
     * K2/L10: snapshot {@code [19]} flips the sweep has made this session
     * (both directions), i.e. the TRANSLUCENT twin of
     * {@link #unlistedMaskFlips}. The two counters should track each other
     * closely; they are separate because the mask write is owner-checked
     * inside {@code RegionStore} and this one is not, so a persistent gap
     * between them is the shape of a slot-ownership bug rather than noise.
     */
    private static long unlistedTransFlips;
    /**
     * {@code meshelium.drawUnlistedLive} — the A/B lever for the H2 fix.
     * FALSE restores pre7 exactly: the BFS-mask path stops drawing live
     * sections vanilla no longer lists and the corner lobes go back to
     * being drawn by nobody. Read once per sweep so a harness flip lands
     * on the next camera crossing.
     */
    private static final String PROPERTY_DRAW_UNLISTED_LIVE = "meshelium.drawUnlistedLive";
    /**
     * {@code meshelium.drawUnlistedLive.altitude} — the A/B lever for the
     * O7 fix alone. FALSE keeps H2's corner lobes and drops the altitude
     * arm, i.e. flying up looks exactly like pre13 again. Separate from
     * {@link #PROPERTY_DRAW_UNLISTED_LIVE} on purpose: that one turns off
     * a fix the owner has already played and liked, this one turns off the
     * only part of the sweep with a frame-time cost worth measuring.
     */
    private static final String PROPERTY_DRAW_UNLISTED_ALTITUDE =
            "meshelium.drawUnlistedLive.altitude";
    /**
     * Vanilla's own advanced-culling floor, in SECTIONS:
     * {@code SectionOcclusionGraph.MINIMUM_ADVANCED_CULLING_SECTION_DISTANCE
     * = SectionPos.blockToSectionCoord(60) = 3} (javap, 26.2 merged jar,
     * that class's {@code static {}} at ip 12-17). Vanilla switches a
     * section to the ray-marched cull as soon as |dx|, |dy| OR |dz|
     * exceeds it ({@code runUpdates} ip 165-230), so this is not a number
     * of ours to pick: it is the exact altitude at which vanilla stops
     * listing terrain by adjacency and starts requiring an unbroken line
     * of already-visited sections back to the camera. See the O7 block on
     * {@code syncUnlistedLiveMaskLocked}.
     */
    private static final int VANILLA_ADVANCED_CULL_SECTIONS = 3;
    /**
     * P2: the altitude arm's hysteresis band, in sections. The O7 arm had
     * none, and it was a hard binary applied to the WHOLE resident set, so
     * every chunk crossing that changed its answer added or removed the
     * entire horizon's worth of marks at once — the owner's "disapear and
     * glitch out when you fly around". Arm above
     * {@link #VANILLA_ADVANCED_CULL_SECTIONS}, disarm only at
     * {@code VANILLA_ADVANCED_CULL_SECTIONS - this}, so 32 blocks of
     * altitude separate the two edges and ordinary flight cannot chatter
     * across them.
     */
    private static final int ALTITUDE_ARM_HYSTERESIS = 2;
    /**
     * R6: the per-node floor for {@link #skipAdvancedRayMarch(double)},
     * in blocks of camera height above the march's start corner. Derived
     * from the march's own arithmetic, not chosen: {@code runUpdates}
     * starts the ray at a corner of the marching node's section
     * ({@code sectionToBlockCoord} at ip 396-424, conditional +16 per
     * axis at ip 426-616; for a horizontal step with the camera above,
     * the Y term is the section's origin), so
     * {@code camY - point.y >= 64} holds exactly when the node's section
     * is at least FOUR sections below the camera's — i.e. exactly when
     * vanilla's own per-node Y test
     * ({@code |dsy| > MINIMUM_ADVANCED_CULLING_SECTION_DISTANCE = 3},
     * ip 185-202) put the node on the ray-marched arm because of
     * altitude. A down-step's +16 corner makes it one section stricter
     * (dsy ≥ 5), which is the safe direction: that march still runs. A
     * node at or near camera height keeps vanilla's full march even
     * while armed, so arming EARLY (see the p10 re-key in
     * {@link #syncUnlistedLiveMaskLocked}) costs nothing where the
     * look-down defect cannot occur.
     */
    private static final double MARCH_SKIP_MIN_CAMERA_ABOVE_BLOCKS =
            (VANILLA_ADVANCED_CULL_SECTIONS + 1) * 16.0D;
    /**
     * P2: the resident set's section-Y histogram, for the arm's terrain
     * band. Fixed span rather than the level's own, because it is only
     * ever used for percentiles: {@code sy} outside it clamps to an end
     * bucket, which cannot move a p10 or p90 that lives in the terrain
     * band. −32..31 sections is y −512..511, past every vanilla dimension
     * and most mod ones. Scratch, LOCK-held, never published.
     */
    private static final int SY_HISTOGRAM_MIN = -32;
    private static final int SY_HISTOGRAM_SIZE = 64;
    private static final int[] syHistogram = new int[SY_HISTOGRAM_SIZE];
    /**
     * P2: the 90th-percentile section Y of the resident set as of the last
     * sweep — "how high is the terrain around here". Since R6 it is a
     * DIAGNOSTIC (the top of the terrain band, read beside
     * {@link #unlistedTerrainLowSy} and the camera's section Y); the arm
     * keys on the LOW percentile, because vanilla flips each section to
     * the ray-marched arm at ITS OWN {@code dsy > 3}, so every section
     * below p90 flipped before a p90-keyed arm fired — the R6 window.
     * {@link Integer#MIN_VALUE} when nothing is resident.
     */
    private static int unlistedTerrainTopSy = Integer.MIN_VALUE;
    /**
     * R6: the 10th-percentile section Y of the resident set as of the
     * last sweep — the FLOOR of the terrain band, and the arm's input.
     * The arm must be up by the time vanilla Y-flips the FIRST resident
     * section, which happens at {@code camSy = sy + 4} counted from the
     * BOTTOM of the distribution, not the top; p10 rather than the
     * minimum for the same reason p90 was chosen over the maximum — one
     * mined shaft or one ravine is not the terrain. The deepest decile
     * can still flip up to two sections before the arm; it is also the
     * decile the camera cannot usually see.
     * {@link Integer#MIN_VALUE} when nothing is resident.
     */
    private static int unlistedTerrainLowSy = Integer.MIN_VALUE;
    /** P2: the altitude arm's latched state, for {@link #ALTITUDE_ARM_HYSTERESIS}. */
    private static boolean altitudeArmLatched;
    /**
     * S6: THE LOCAL HORIZON — the per-column top section Y of the
     * slot-owning resident set, indexed <b>relative to the camera</b>:
     * {@code columnTopSy[(dx + margin) * side + (dz + margin)]}, where
     * {@code dx = sx - camSx}. {@link Integer#MIN_VALUE} means "no
     * resident section in that column".
     *
     * <p><b>Why this replaced S2's p90 arm.</b> S2 gated the wide
     * per-section rule on a single boolean, {@code camSy > p90} over the
     * WHOLE disc. Its verification table was desk-checked on the uniform
     * ocean, where p10 + 3 and p90 coincide, so the table structurally
     * could not see the new arm's lateness. Over mixed terrain — an ocean
     * beside a mountain, the owner's actual world — p90 tracks the
     * mountain while the camera flies over the water, so the arm stays
     * DOWN over exactly the terrain vanilla has already begun refusing.
     * That is the fifth attempt's residual, and it is a statistic's
     * failure, not a threshold's: no percentile of a bimodal distribution
     * answers "is the camera above the terrain HERE".</p>
     *
     * <p>So there is no statistic any more. The wide rule asks, per
     * section, whether the camera's section is above the top of the
     * resident terrain <b>in that section's own column</b>. Over a
     * uniform scene every column's top IS the p90 and the marked set is
     * exactly S2's; over a mixed scene the water columns mark and the
     * mountain columns do not, which is both the correctness fix and the
     * cost guard — the columns the rule declines are precisely the ones
     * whose terrain reaches the camera and therefore genuinely occludes.
     * Standing on the ground in a forest, on a plain or in a valley every
     * column tops at or above the camera's section, nothing marks, and
     * ground play stays bit-identical to pre18 exactly as the p90 arm
     * promised.</p>
     *
     * <p><b>No hysteresis, deliberately.</b> The p90 arm needed 2 sections
     * of it because one boolean moved the whole horizon at once. This
     * predicate's edge is {@code camSy > columnTop}, which is where
     * vanilla's own refusals begin (S2's frame table: refusals start at
     * {@code camSy = top + 1}); at {@code camSy == columnTop} the ray home
     * is level and vanilla lists the section anyway, so chatter across the
     * edge alternates between "marked" and "vanilla listed it", never
     * between "drawn" and "hole".</p>
     *
     * <p>Scratch, LOCK-held, refilled once per camera-section crossing in
     * the same pass that fills {@link #syHistogram}; never published.</p>
     */
    private static int[] columnTopSy = new int[0];
    /** S6: {@code 2 * margin + 1}; 0 when {@link #columnTopSy} is unfilled. */
    private static int columnTopSide;
    /** S6: the half-extent {@link #columnTopSy} is indexed over, in sections. */
    private static int columnTopMargin;
    /**
     * S6: hard ceiling on {@link #columnTopMargin}, so a mod that reports
     * an absurd render distance cannot turn a scratch array into an
     * allocation event. Beyond it the map simply answers "unknown", which
     * is the fail-open direction (the rule marks).
     */
    private static final int COLUMN_TOP_MAX_MARGIN = 130;
    /**
     * S6: resident entries the last sweep found in a column whose top is
     * strictly below the camera's section — the local horizon rule's
     * reach, before the disc, the core and the {@code dsy} gates narrow
     * it. Replaces {@code bfsRelax[aboveTop=]}, which reported a boolean
     * that no longer exists. Read it against {@link #unlistedBandDrawn}:
     * {@code horizon} is what the rule could see and {@code band} is what
     * it marked.
     */
    private static int unlistedHorizonEntries;
    /**
     * S6: the p10 arm's verdict as of the last sweep, cached so
     * {@link #applyUnlistedVerdictLocked} runs the SAME rule the sweep ran
     * rather than a second approximation of it.
     */
    private static boolean unlistedSweepHighCamera;
    /** S6: the local horizon's enable as of the last sweep. Same reason. */
    private static boolean unlistedSweepLocalHorizon;
    /**
     * S6: was the master {@code meshelium.drawUnlistedLive} lever UP at the
     * last sweep? {@link #applyUnlistedVerdictLocked} must honour it too,
     * or a session started with the lever down would grow marks through
     * the bind path that the sweep refuses to make — and the A/B would
     * stop being an A/B.
     */
    private static boolean unlistedSweepEnabled;
    /**
     * S6: marks made at upload BIND rather than by the sweep — the second
     * hole's whole population, cumulative. Every one of them is a section
     * that would have had no mark at all until the camera next crossed a
     * section boundary, because {@code addOrReplace} clears the bit and
     * the sweep runs before the drain and early-outs on an unchanged
     * camera. Nonzero on any climb through streaming terrain; 0 in a world
     * that never uploads a section between two crossings.
     */
    private static long unlistedBindMarks;
    /** S6: of {@link #unlistedBindMarks}, the local horizon rule's own. */
    private static long unlistedBindBandMarks;
    /**
     * P2: the altitude arm, published for
     * {@code SectionOcclusionGraphAltitudeMixin} to read. <b>Volatile and
     * never LOCK</b>: the mixin's only reader runs inside vanilla's BFS,
     * which runs on the render thread for a partial update and on
     * {@code Util.backgroundExecutor()} for a full one, and taking the
     * residency monitor from a vanilla callback on a pool thread is
     * exactly the deadlock this class's discipline forbids.
     */
    private static volatile boolean altitudeCullRelaxed;
    /**
     * P2: ray marches {@link #skipAdvancedRayMarch} short-circuited —
     * one per neighbour vanilla would otherwise have refused from
     * altitude. <b>Racy on purpose</b>: it is written from the render
     * thread and from {@code Util.backgroundExecutor()} with no
     * synchronisation and read under LOCK for the stats line; a lost
     * increment costs nothing and a fence per march would not be free.
     * Its ONLY job is to be nonzero — the redirect is declared
     * {@code require = 0} so a future refactor makes it silently not
     * apply, and "armed for a whole flight with this at 0" is the one
     * reading that separates "the fix did not help" from "the fix was
     * never installed" (the census blind-spot rule).
     */
    private static long bfsAdvancedCullRelaxed;
    /** P2: sweeps that armed the altitude relaxation, for the same comparison. */
    private static long altitudeArmSweeps;

    // ------------------------------------------------------------------
    // SEAM steps 3-4 (docs/FARFIELD-SEAM-DESIGN.md): THE COVERAGE SPLIT,
    // and the death of the handover bridge's clocks and caps.
    //
    // History, compressed (each wave's full argument lives in
    // docs/FARFIELD-WAVES.md): pre6/H3 armed a PROVEN bridge off the
    // contested watch; pre11/M4 added a SPECULATIVE bridge off ring
    // geometry with a 1.2 s deadline, a 1024 population cap and a 768
    // per-frame cap; pre13/O6 counted the five ways the arming chain
    // said no and added an LRU recycle; pre14/P9 counted the sixth and
    // measured the holds. Five fixes, all negotiating TIMING among
    // producers so the gap between "old owner freed" and "new owner
    // bound" was made unlikely. Steps 3-4 remove the gap from the state
    // space instead: a covered, drawn position is never unbound - it is
    // only ever REPLACED - so there is nothing left to time and nothing
    // left to cap in units of arms-per-frame. What remains is a MEMORY
    // bound (the AWAITING quad budget above) and three enumerated exits
    // (E1 coverage, E2 memory, E3 era end), each counted.
    //
    // THE SPLIT. The old ring publication conflated two facts with three
    // volatiles: WHERE the far domain is (geometry) and WHETHER the
    // walker is accepting work (admission). A stand-down cleared both,
    // and every release during one took the plain-free path - the
    // "stand-down disarmed ring" family of failures, including the
    // owner's rd-change annulus. Now:
    //   - COVERAGE GEOMETRY (the three volatiles below) is always valid
    //     while a world is up. The walker publishes it on every armed
    //     pump; a stand-down does NOT clear it; a vanilla reload storm
    //     republishes it synchronously at the head of
    //     LevelRenderer.invalidateCompiledGeometry (before
    //     releaseAllBuffers iterates - javap: release at ip 138, the
    //     ViewArea swap at ip 149/184, all render-thread, so program
    //     order alone guarantees the storm sees shrink-adjusted
    //     geometry, never last pump's). It clears only when the ring is
    //     genuinely gone: master off / ring collapsed / world era end.
    //   - ADMISSION stays gated exactly where it always was: the
    //     walker's stand-down return and disarmed branch. Only NEW far
    //     reads and admissions wait for a settle; the geometry that
    //     decides frees never does, because coverage geometry cannot be
    //     wrong about a world that is merely recompiling.
    // ------------------------------------------------------------------

    /**
     * The published coverage geometry, read by {@link #coveredNow} on
     * build threads at every free. Packed camera SECTION (x in the high
     * word, z in the low), or {@link SectionBuildTap#CAMERA_SECTION_UNKNOWN}
     * while no geometry is published (far field off, world down).
     *
     * <p>Volatile rather than LOCK-held: {@code onMeshReleased} already
     * holds LOCK when it reads these and the publisher runs outside it, so
     * a lock would only serialise the two for no gain. A value one pump
     * stale can only mis-answer a column exactly on the ring's edge, where
     * both answers are defensible - and {@link #coveredNow} prefers the
     * live camera tap anyway (the P9 rule, kept).</p>
     */
    private static volatile long farCoverageCamera = SectionBuildTap.CAMERA_SECTION_UNKNOWN;
    /** Coverage inner edge (vanilla's disc less the handover band), or -1. */
    private static volatile int farCoverageCoverEdge = -1;
    /** Coverage outer edge in chunks, or -1 when no geometry is published. */
    private static volatile int farCoverageL1 = -1;
    /**
     * Latched when the far-field hook itself failed (class-load or an
     * admission-path throw): the pump never calls the far field again
     * this session. Render thread only. The catch that sets this is
     * structurally unable to touch the four coverage-guard drop
     * counters — a far-field bug must never turn the mod passive
     * (landmine L4).
     */
    private static boolean farHookBroken;
    /**
     * True once the far master switch has been observed ON in this
     * session. Until then NO far walker class is ever named, which is
     * what keeps master-OFF at literally zero cost (no class load, no
     * allocation, no per-frame work). Deliberately never reset per
     * world: the walker's disarmed branch is the drain that releases far
     * residents after a mid-session disable, so once armed it must keep
     * being pumped. Render thread only.
     */
    private static boolean farEverArmed;
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

    // ------------------------------------------------------------------
    // Incremental draw snapshot (2026-08-18; design + binding decisions in
    // docs/DRAW-SNAPSHOT-INCREMENTAL.md, evidence in the three
    // SNAPSHOT-DOSSIER files). All state under LOCK.
    // ------------------------------------------------------------------

    /** One side of the ping-pong pair {@link #drawSnapshot} publishes. */
    private static final class SnapshotBuffer {
        /** Packed entries, SLOT-indexed ({@code snapshotSlot * STRIDE}). */
        int[] data = new int[0];
        /** Absolute change-log index this buffer's contents reflect. */
        long appliedLogIndex;
        /**
         * Route this buffer's next turn as back buffer through the full
         * rebuild (fresh buffer, log overflow, free-list growth, dispose,
         * pinned regrow, an invariant break). The old full walk IS the
         * fallback, so the worst case equals the pre-incremental cost.
         */
        boolean needsFullRebuild = true;
    }

    /**
     * Slot capacity at standup: an rd 64 spin peaks ~27k concurrent
     * entries and free-list reuse pins the high-water mark at the PEAK,
     * not the churn, so 32k fits with headroom at 2.6 MB per buffer.
     * Exhaustion doubles through the full-rebuild fallback — rare by
     * construction (world load, render-distance raise); the rd 120 +
     * retention ceiling is ~112k entries (dossier-lifecycle §3).
     */
    private static final int SNAPSHOT_SLOTS_INITIAL = 1 << 15;
    /** Change-log ring at standup; grows ×2 up to {@link #SNAPSHOT_LOG_MAX}. */
    private static final int SNAPSHOT_LOG_INITIAL = 1 << 12;
    /**
     * Past this the log stops growing and overflow routes through the
     * full rebuild. Sized so turning-frame streaming (order 10² records
     * per pump, two publishes of retention) never comes close; only
     * release storms overflow — releaseAllBuffers frees every section in
     * ONE frame — and those are exactly the designed fallback moments.
     */
    private static final int SNAPSHOT_LOG_MAX = 1 << 16;

    /** Slot capacity of both buffers and the owner index. */
    private static int snapshotSlotCap = SNAPSHOT_SLOTS_INITIAL;
    /** Slots ever assigned this era; the published {@code maxSlot} + 1. */
    private static int snapshotSlotHighWater;
    /** Slot → its live Resident (null = free/dead) — the WRITE-apply join. */
    private static Resident[] snapshotSlotOwner = new Resident[SNAPSHOT_SLOTS_INITIAL];
    /**
     * Freed slots awaiting reuse. Unlike arena addresses, slot reuse needs
     * NO fence delay: both buffers replay the tombstone-then-rewrite
     * records in append order (dossier-lifecycle §4).
     */
    private static final IntArrayList snapshotFreeSlots = new IntArrayList();
    /**
     * The change log: one int per record — WRITE(slot) travels as the
     * slot itself, TOMBSTONE(slot) as its complement (always negative).
     * A ring over ABSOLUTE indices; entries older than both buffers'
     * {@code appliedLogIndex} are dead and get overwritten.
     */
    private static int[] snapshotLog = new int[SNAPSHOT_LOG_INITIAL];
    /** Absolute index one past the newest record (monotonic across worlds). */
    private static long snapshotLogEnd;
    private static SnapshotBuffer snapshotFront = new SnapshotBuffer();
    private static SnapshotBuffer snapshotBack = new SnapshotBuffer();
    // Incremental-snapshot counters (under LOCK) — the bench's proof that
    // delta publishes dominate and the fallback stays rare.
    private static long snapshotPublishes;
    private static long snapshotFullRebuilds;
    private static long snapshotLogRecords;

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
        if (target.maxRd() <= current.maxRd()
                && target.maxRegions() <= current.maxRegions()
                && target.dispatchCapacity() <= current.dispatchCapacity()) {
            // Ceiling-capped or stale request — nothing to grow.
            //
            // pre13/N1: the maxRegions arm is new and it is what lets the
            // FAR-FIELD radius move mid-world. Since MesheliumScaling
            // sizes the region budget from the LOD distance as well as
            // from rd, a player raising the LOD slider needs the same
            // grow a render-distance raise needs, at the SAME pinned
            // maxRd - which the old `maxRd <= maxRd` test refused
            // outright. dispatchCapacity is in here for the standard-pin
            // case, where it does not move with maxRegions.
            return;
        }
        // GROW, never shrink. The far-field arm above makes it possible
        // to arrive here with a target that is bigger on one axis and
        // smaller on another - the LOD slider went up while the render
        // distance went down - and installing that snapshot would shrink
        // buffers that frames in flight are still sized against. A
        // componentwise max keeps the one property every consumer of
        // MesheliumScaling.current() already assumes: within a world the
        // pinned budget only ever goes up.
        target = new com.deds.meshelium.MesheliumScaling.Snapshot(
                Math.max(target.maxRd(), current.maxRd()),
                target.extended() || current.extended(),
                Math.max(target.maxRegions(), current.maxRegions()),
                Math.max(target.dispatchCapacity(), current.dispatchCapacity()),
                Math.max(target.arenaBytes(), current.arenaBytes()));
        if (target.maxRegions() > current.maxRegions()) {
            long newHandle = gpu.growRecords(target.maxRegions());
            if (newHandle == 0L) {
                pinnedGrowFailed = true;
                pinnedGrowFailures++;
                MesheliumLog.LOGGER.warn(
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
        // Full-rebuild-class ERA event (S14, mutations dossier): the grow
        // dropped the drawer's snapshot-sized resources AND its cached
        // snapshot/epoch (TerrainDrawer.onPinnedRegrow), so the next
        // consumer arrives with no prior state — republish from the maps.
        forceSnapshotRebuildLocked();
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

    /**
     * Immutable counter snapshot for tests and the debug line.
     *
     * <h2>Far sections are half in here, and the split is deliberate
     * (W4 review requirement, docs/FARFIELD-WAVES.md)</h2>
     * <p>A far section is retained-shaped but it is NOT a near section,
     * so it is deliberately absent from every COUNT here and unavoidably
     * present in every RESOURCE total. Reading one of these numbers
     * without knowing which kind it is will mislead you:</p>
     * <ul>
     * <li><b>Excluded</b> (near field only): {@link #sectionsResident},
     *     {@link #quadsResident}, {@link #retainedSections},
     *     {@link #retainedQuads}. Far residents live in their own map
     *     and are counted on {@code FarFieldResidency}'s own LongAdders
     *     ({@code farSectionsResident} and friends) instead. That is
     *     what keeps a far-field bug away from the four wave-8
     *     coverage-guard drop counters, and it is why
     *     {@code sectionsResident} can read 0 while the horizon is full
     *     of far terrain.</li>
     * <li><b>Included</b> (shared resources, no way to attribute them
     *     apart without a second allocator): {@link #arenaUsedBytes},
     *     {@link #arenaExtentBytes}, {@link #arenaCapacityBytes},
     *     {@link #arenaBlocks}, {@link #emptyTopBlocks},
     *     {@link #regionsLive}, {@link #regionsDirty}. Far quads are
     *     really in the arena and far sections really hold region ids,
     *     which is precisely why {@link #farPromotionHasRoom()} gates
     *     far fill below the pressure sweep's watermarks rather than
     *     letting it discover the ceiling.</li>
     * </ul>
     * <p>So {@code arenaUsedBytes / sectionsResident} is not a bytes per
     * section figure once the far field is armed, and a bench that
     * publishes arena bytes must publish the far counters beside them
     * (MesheliumBenchmarkTest does).</p>
     */
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

        /**
         * True iff nothing wave-3b ever happened — the GL dormancy proof.
         *
         * <p>This has NO far-field term and cannot grow one: the record
         * carries no far count (see the record's javadoc), and the two
         * shared totals a far section does move, {@code arenaUsedBytes}
         * and {@code regionsLive}, are already in the conjunction below.
         * So an armed far field DOES make this read false, which is
         * correct — the GPU is holding geometry — but the far field's own
         * idleness is a separate question with its own probe,
         * {@code FarField.isCompletelyIdle()}, and the suite's
         * zero-cost-off leg asserts that one.</p>
         */
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
     * Per-frame view of every resident section, flattened for the
     * render thread's draw-list builder: {@link #STRIDE} ints per section —
     * {@code [sx, sy, sz, arenaQuadAddr, bucketStartRel[0..6],
     * bucketCount[0..6], globalSectionIndex]} (the last is wave 7's, see
     * the record javadoc) with bucket order/gating per
     * {@code QuadFacing} (docs/TERRAIN-DATA.md §4). Bucket starts are
     * RELATIVE to the section's allocation; absolute arena quad index =
     * {@code arenaQuadAddr + startRel}. The translucent prefix is excluded
     * by construction ({@code startRel[0] == translucentCount}).
     *
     * <p><b>Incremental rework (2026-08-18):</b> {@code data} is
     * SLOT-indexed — an entry lives at {@code snapshotSlot * STRIDE} for
     * its whole lifetime — and dead slots are TOMBSTONED: all 20 ints
     * zero, then {@code [18]} = −2 (distinct from the live-but-slotless
     * −1). Consumers iterate slots {@code 0..maxSlot} and skip tombstones;
     * {@code liveSlotCount} (returned by {@link #sectionCount()}) carries
     * the emptiness test. The backing array belongs to a persistent
     * ping-pong pair: it is REWRITTEN two publishes later, so a holder
     * must adopt every fresh {@link #drawSnapshot} return — the drawer,
     * the sole consumer, does (docs/SNAPSHOT-DOSSIER-ALIASING.md).</p>
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
    public record DrawSnapshot(long epoch, int liveSlotCount, int maxSlot, int[] data,
            long arenaBackingHandle, long[] arenaBlockHandles,
            int regionCount, int[] regionData, long sectionRecordsHandle, int[] retainedMasks,
            int[] unlistedMasks) {


        /**
         * Ints per section in {@link #data}: {@code [sx, sy, sz,
         * arenaQuadAddr, bucketStartRel[0..6], bucketCount[0..6],
         * globalSectionIndex, retainedFlag]}. Wave-7 additive: {@code [18]
         * globalSectionIndex} = {@code regionId*256 + compactedSlot} when
         * this resident OWNS its region slot (the stamp-buffer index the
         * translucent occlusion gate uses), −1 (slot stolen by a newer
         * mesh / region gone), or −2 (TOMBSTONE — the slot is dead and the
         * whole entry zeroed) — slotless residents are excluded from the
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
         * <p><b>K2/L10 restatement:</b> {@code [19]} now means <b>"the
         * translucent visible loop cannot reach this section, so the
         * pre-pass must"</b>, not "which map holds it". Three populations
         * carry it: retained copies, far shells, and LIVE sections the
         * unlisted-live sweep is drawing from the mask because they left
         * vanilla's compile disc ({@link Resident#unlistedDrawn}). The
         * first two are recognised by {@code orphanedAtMillis}, the third
         * by that field, and {@link #writeSnapshotEntryLocked} ORs them.
         * Nothing may infer "retained" from {@code [19]} any more; the
         * residency's own retained-vs-live test is and remains
         * {@code orphanedAtMillis}.</p>
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
         *
         * <p><b>S2 additive: {@code unlistedMasks}.</b> Same shape, same
         * region order, carrying ONLY the bits the unlisted-live sweep set
         * on entries in {@link TerrainResidency#resident} — i.e. on
         * {@code LEDGER_NEAR_LIVE} sections, the only state that map may
         * hold ({@code auditLedgerLocked} is the contract, and
         * {@code onMeshReleased} removes from {@code resident} BEFORE any
         * retain, park or clear). It is a SUBSET of {@code retainedMasks}
         * by construction — the sweep writes its verdict through
         * {@code RegionStore.setRetained} — so
         * {@code retained | unlisted == retained} and the DRAWN SET IS
         * UNCHANGED. This array exists purely so the drawer can tell the
         * three populations that share one bit apart.</p>
         *
         * <p>It is the {@code retainedMasks} half of the K2/L10 rule
         * already stated above for {@code [19]}: nothing may infer
         * "retained" from a shared flag. Wave-11's
         * {@code TerrainDrawer.lastRetainedMaskSections} did exactly that
         * and was getting away with it only because H2's corner lobes and
         * O7's altitude marks happened to be empty in the scene that
         * asserts on it. Subtracting this array restores that probe to its
         * name — retained and far copies only — and makes the wave-11
         * "retention off draws nothing retained" invariant EXACT rather
         * than accidental.</p>
         *
         * <p>Rebuilt per publish from {@code resident} rather than
         * maintained incrementally, and that is deliberate: a stale bit
         * here would MASK OUT a genuinely retained bit at the same
         * position and turn the invariant vacuous. Exactness beats the
         * increment, and the walk is a fraction of
         * {@code snapshotRegions}' own per-publish 256-slot-per-region
         * scan.</p>
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

        /**
         * Live (non-tombstoned) entries — the emptiness test, kept under
         * its wave-4 name so every {@code == 0} early-out reads unchanged.
         * NOT the iteration bound: iterate slots {@code 0..maxSlot} and
         * skip tombstones ({@code [18]} == −2 / the zeroed shape).
         */
        public int sectionCount() {
            return liveSlotCount;
        }
    }

    /**
     * Render-thread accessor for the drawer. Returns {@code null} when the
     * resident set has not changed since {@code knownEpoch} (keep the
     * cached snapshot); otherwise publishes and returns a fresh snapshot.
     * Stale-by-a-frame draws are safe by the same fence discipline the
     * pump uses: a released range cannot be reallocated (and new bytes
     * copied over it) until {@code FREE_FRAME_LAG} pumps later.
     *
     * <p><b>The incremental publish (2026-08-18):</b> two persistent
     * slot-indexed buffers ping-pong instead of a fresh {@code int[n*20]}
     * walk per epoch bump (that walk — ~2 MB of garbage per bump under
     * chunk streaming — was the #1 real-play smoothness cost, design
     * note). The back buffer catches up by applying only the change-log
     * records it has not seen: WRITE recomputes the full 20-int entry
     * from the live maps through the same {@link #writeSnapshotEntryLocked}
     * as ever ([0..17] are immutable post-admission, so recompute-all
     * covers every mutation kind); TOMBSTONE zeroes the slot and sets
     * {@code [18]} = −2. Handles and the (small) region snapshots are
     * then re-captured under the SAME lock hold as always — which is what
     * keeps the handle-only mutation sites (arena grow/append/trim,
     * attach) record-free — and the pair swaps. Fallback events (log
     * overflow, free-list growth, dispose, pinned regrow, an invariant
     * break) mark BOTH buffers for a full rebuild from the maps, so the
     * worst case equals the old behavior. The caller may hold a returned
     * snapshot across frames: its backing array is rewritten only when
     * the same buffer next serves as back buffer, two swaps later, and
     * the drawer adopts every non-null return before then (single-caller
     * discipline, docs/SNAPSHOT-DOSSIER-ALIASING.md).</p>
     */
    public static DrawSnapshot drawSnapshot(long knownEpoch) {
        synchronized (LOCK) {
            if (drawEpoch == knownEpoch) {
                return null;
            }
            SnapshotBuffer back = snapshotBack;
            if (back.needsFullRebuild
                    || back.data.length != snapshotSlotCap * DrawSnapshot.STRIDE) {
                rebuildSnapshotBufferLocked(back);
                snapshotFullRebuilds++;
            } else {
                for (long i = back.appliedLogIndex; i < snapshotLogEnd; i++) {
                    applySnapshotRecordLocked(back.data,
                            snapshotLog[(int) (i % snapshotLog.length)]);
                }
                back.appliedLogIndex = snapshotLogEnd;
            }
            long handle = arena == null ? 0L : arena.backingHandle();
            long[] blockHandles = arena == null ? new long[0] : arena.blockHandles();
            // Both snapshots inside ONE lock hold, no mutation between —
            // identical region iteration order (RegionStore javadoc). They
            // stay small full per-publish copies (7 + 8 ints per region,
            // ~450 regions) — not the cost the delta log exists to kill.
            int[] regionData = regionStore.snapshotRegions();
            int[] retainedMasks = regionStore.snapshotRetainedMasks();
            // S2: the ownership split of the SAME bits, third call in the
            // same lock hold and the same region order.
            int[] unlistedMasks = snapshotUnlistedMasksLocked(regionData);
            snapshotBack = snapshotFront;
            snapshotFront = back;
            snapshotPublishes++;
            // liveSlotCount counts far entries too (W3): a far-only view
            // (camera beyond everything vanilla holds) must not read as
            // an empty world to the sectionCount()==0 early-outs.
            return new DrawSnapshot(drawEpoch,
                    resident.size() + retained.size() + farResident.size(),
                    snapshotSlotHighWater - 1, back.data, handle, blockHandles,
                    regionData.length / DrawSnapshot.REGION_STRIDE, regionData,
                    sectionRecordsHandle, retainedMasks, unlistedMasks);
        }
    }

    /**
     * S2: scratch for {@link #snapshotUnlistedMasksLocked} — region key to
     * its index in the region snapshot just taken. Reused, cleared per
     * publish, LOCK-held, never published. Default −1 so a region that
     * vanished between the two calls (it cannot, they share one lock hold,
     * but the map must still answer) is skipped rather than misindexed.
     */
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap unlistedRegionIndex =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

    static {
        unlistedRegionIndex.defaultReturnValue(-1);
    }

    /**
     * S2: the OWNERSHIP SPLIT of the retained mask, in the region order
     * {@code regionData} was just produced in.
     *
     * <p>Three populations set the one {@code RegionStore} retained bit —
     * genuinely retained copies ({@code markRetained}), far shells
     * (admission), and LIVE sections the unlisted-live sweep is drawing
     * because vanilla's {@code visibleSections} cannot be relied on to
     * list them (H2's corner lobes, O7's altitude marks, S2's band). The
     * drawer needs the third apart from the first two, because the
     * wave-11 A1 invariant — <i>retention off draws nothing retained</i> —
     * is about OWNERSHIP, and altitude must never change ownership
     * semantics.</p>
     *
     * <p>The split is free of any new state: {@link #resident} holds
     * {@code LEDGER_NEAR_LIVE} entries and nothing else
     * ({@code auditLedgerLocked} asserts it every pump under
     * {@code -Dmeshelium.debug.ledgerAudit}, and {@code onMeshReleased}
     * removes from the map before it can retain, park or clear), and
     * {@link Resident#unlistedDrawn} is the sweep's own per-entry verdict.
     * So "live and swept-unlisted" is exactly this walk, and a retained,
     * parked ({@code AWAITING_SUCCESSOR}) or far entry can no more appear
     * in it than it can appear in {@code resident}.</p>
     *
     * <p>{@code !ownsSlot} is skipped for the reason the sweep skips it:
     * a newer copy owns the position and answers for it, and
     * {@code setRetained} would have refused this entry's write anyway —
     * so its bit is not one of ours to claim.</p>
     */
    private static int[] snapshotUnlistedMasksLocked(int[] regionData) {
        int regions = regionData.length / DrawSnapshot.REGION_STRIDE;
        int[] out = new int[regions * 8];
        if (regions == 0 || resident.isEmpty()) {
            return out;
        }
        unlistedRegionIndex.clear();
        for (int r = 0; r < regions; r++) {
            int ro = r * DrawSnapshot.REGION_STRIDE;
            // regionData carries rx,ry,rz; RegionStore.regionKey takes
            // SECTION coords and shifts them back down, so feed it the
            // region's own origin section rather than re-implementing the
            // packing here (a second copy of it would be free to drift).
            unlistedRegionIndex.put(RegionStore.regionKey(regionData[ro + 1] << 3,
                    regionData[ro + 2] << 2, regionData[ro + 3] << 3), r);
        }
        for (Resident r : resident.values()) {
            if (!r.ownsSlot || !r.unlistedDrawn) {
                continue;
            }
            int idx = unlistedRegionIndex.get(r.regionKey);
            if (idx < 0) {
                continue;
            }
            out[idx * 8 + (r.posKey >>> 5)] |= 1 << (r.posKey & 31);
        }
        return out;
    }

    /**
     * @param retainedFlag 1 when the caller knows the entry is in the
     *                     {@code retained} or {@code farResident} map. The
     *                     stored {@code [19]} is that OR
     *                     {@link Resident#unlistedDrawn} (K2/L10): both
     *                     mean "the translucent visible loop cannot reach
     *                     this section, so the pre-pass must".
     */
    private static void writeSnapshotEntryLocked(int[] data, int o, Resident r, int retainedFlag) {
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
        data[o + 19] = retainedFlag != 0 || r.unlistedDrawn ? 1 : 0;
    }

    /**
     * Apply one change-log record to a packed buffer. WRITE recomputes the
     * whole entry from the live maps at APPLY time, so replaying a slot's
     * records in append order converges on the live state whatever
     * interleaving produced them — free-then-readmit at the same slot
     * included (dossier-lifecycle §4). A WRITE whose owner died later in
     * the log tombstones now; its own TOMBSTONE record follows and lands
     * on the same bytes.
     */
    private static void applySnapshotRecordLocked(int[] data, int record) {
        int slot = record >= 0 ? record : ~record;
        Resident r = record >= 0 ? snapshotSlotOwner[slot] : null;
        if (r == null) {
            tombstoneSnapshotSlot(data, slot);
            return;
        }
        // [19]: orphanedAtMillis is stamped exactly when an entry moves
        // resident → retained (or is born far) and never clears (the
        // Resident javadoc's retained-vs-live test). K2/L10 adds the
        // second source, unlistedDrawn, inside writeSnapshotEntryLocked —
        // so both the recompute here and the rebuild below agree on it,
        // which is why the sweep MUST log every flip of that field.
        writeSnapshotEntryLocked(data, slot * DrawSnapshot.STRIDE, r,
                r.orphanedAtMillis != 0 ? 1 : 0);
    }

    /**
     * THE tombstone (dossier-lifecycle §2): all 20 ints zero, then
     * {@code [18]} = −2. Zero bucket counts keep cpuCull from pushing
     * runs, zero [4] plus negative [18] drop it from the translucent map
     * and pre-pass, zero [19] reads as live-but-empty everywhere else;
     * −2 (vs the live-but-slotless −1) makes dead slots readable in dumps.
     */
    private static void tombstoneSnapshotSlot(int[] data, int slot) {
        int o = slot * DrawSnapshot.STRIDE;
        java.util.Arrays.fill(data, o, o + DrawSnapshot.STRIDE, 0);
        data[o + 18] = -2;
    }

    /**
     * The full-rebuild fallback: the old whole-map walk into {@code buf},
     * PRESERVING slot assignments and tombstoning every unassigned slot
     * below the high-water mark. Worst case equals the pre-incremental
     * rebuild — the current behavior IS the fallback.
     */
    private static void rebuildSnapshotBufferLocked(SnapshotBuffer buf) {
        int capInts = snapshotSlotCap * DrawSnapshot.STRIDE;
        if (buf.data.length != capInts) {
            buf.data = new int[capInts];
        } else {
            java.util.Arrays.fill(buf.data, 0);
        }
        for (int slot = 0; slot < snapshotSlotHighWater; slot++) {
            buf.data[slot * DrawSnapshot.STRIDE + 18] = -2;
        }
        for (Resident r : resident.values()) {
            writeSnapshotEntryLocked(buf.data, r.snapshotSlot * DrawSnapshot.STRIDE, r, 0);
        }
        // Wave-11: retained entries ride the same flat view — the
        // occlusion and cpuCull paths draw them with zero extra code;
        // [19] flags them for the translucent pre-pass and the mask
        // path reads retainedMasks at publish.
        for (Resident r : retained.values()) {
            writeSnapshotEntryLocked(buf.data, r.snapshotSlot * DrawSnapshot.STRIDE, r, 1);
        }
        // W3: far entries are retained-shaped — same flat view, same
        // [19] = 1 (their orphanedAtMillis stamp makes the incremental
        // WRITE apply agree with this rebuild).
        for (Resident r : farResident.values()) {
            writeSnapshotEntryLocked(buf.data, r.snapshotSlot * DrawSnapshot.STRIDE, r, 1);
        }
        buf.appliedLogIndex = snapshotLogEnd;
        buf.needsFullRebuild = false;
    }

    /** A fallback event: BOTH buffers rebuild on their next turn as back. */
    private static void forceSnapshotRebuildLocked() {
        snapshotFront.needsFullRebuild = true;
        snapshotBack.needsFullRebuild = true;
    }

    private static void logSlotWriteLocked(int slot) {
        appendSnapshotLogLocked(slot);
    }

    private static void logSlotTombstoneLocked(int slot) {
        appendSnapshotLogLocked(~slot); // complement — always negative
    }

    /**
     * The moved-owner fanout (dossier-mutations §3): {@code RegionStore
     * .remove}'s swap-compaction changes a THIRD PARTY's compacted slot,
     * hence its snapshot {@code [18]} — invisible to a log that only
     * records the removed slot, and the miss ships a stale stamp index to
     * the translucent occlusion gate (the silent-drop mode). Every remove
     * call site feeds its return value through here.
     */
    private static void logMovedOwnerLocked(Object movedOwner) {
        if (movedOwner instanceof Resident moved) {
            logSlotWriteLocked(moved.snapshotSlot);
        }
    }

    private static void appendSnapshotLogLocked(int record) {
        if (snapshotFront.needsFullRebuild && snapshotBack.needsFullRebuild) {
            return; // both sides rebuild from the maps — records are moot
        }
        long oldest = snapshotLogEnd;
        if (!snapshotFront.needsFullRebuild) {
            oldest = Math.min(oldest, snapshotFront.appliedLogIndex);
        }
        if (!snapshotBack.needsFullRebuild) {
            oldest = Math.min(oldest, snapshotBack.appliedLogIndex);
        }
        if (snapshotLogEnd - oldest >= snapshotLog.length) {
            if (snapshotLog.length >= SNAPSHOT_LOG_MAX) {
                // Overflow — a release storm (releaseAllBuffers frees every
                // section in one frame). The full rebuild IS the fallback,
                // so this frame costs exactly what every frame used to.
                forceSnapshotRebuildLocked();
                return;
            }
            int[] grown = new int[snapshotLog.length << 1];
            for (long i = oldest; i < snapshotLogEnd; i++) {
                grown[(int) (i % grown.length)] = snapshotLog[(int) (i % snapshotLog.length)];
            }
            snapshotLog = grown;
        }
        snapshotLog[(int) (snapshotLogEnd % snapshotLog.length)] = record;
        snapshotLogEnd++;
        snapshotLogRecords++;
    }

    /**
     * A snapshot slot for a freshly admitted Resident: free-list first
     * (reuse within a publish window is safe — both buffers replay the
     * tombstone-then-rewrite records in append order); exhaustion doubles
     * the capacity and routes the next publish through the full rebuild,
     * the designed grow event (decision 7 — world load, render-distance
     * raise; never steady-state streaming).
     */
    private static int allocSnapshotSlotLocked() {
        if (!snapshotFreeSlots.isEmpty()) {
            return snapshotFreeSlots.popInt();
        }
        if (snapshotSlotHighWater == snapshotSlotCap) {
            snapshotSlotCap <<= 1;
            snapshotSlotOwner = java.util.Arrays.copyOf(snapshotSlotOwner, snapshotSlotCap);
            forceSnapshotRebuildLocked();
        }
        return snapshotSlotHighWater++;
    }

    /** Common tail of every per-entry death site: tombstone + free the slot. */
    private static void freeSnapshotSlotLocked(Resident r) {
        snapshotSlotOwner[r.snapshotSlot] = null;
        snapshotFreeSlots.push(r.snapshotSlot);
        logSlotTombstoneLocked(r.snapshotSlot);
    }

    /**
     * Decision-8 check: {@code retained.put} must never displace a prior
     * value (the retained map's re-entry argument — a position re-enters
     * only after a fresh upload REMOVED it). If that invariant ever broke,
     * the displaced entry's slot and arena range would leak silently and
     * the published snapshot would carry its stale entry forever — so this
     * logs loudly and heals the snapshot through the full rebuild instead
     * of a bare assert nobody runs with.
     */
    private static void putRetainedLocked(long packedPos, Resident r) {
        Resident displaced = retained.put(packedPos, r);
        if (displaced != null) {
            MesheliumLog.LOGGER.error(
                    "Meshelium retained.put displaced an entry at packed pos {} — a Meshelium "
                            + "bug (the retained map's re-entry invariant broke); the draw "
                            + "snapshot heals through a full rebuild, the displaced arena "
                            + "range leaks until dispose",
                    packedPos);
            forceSnapshotRebuildLocked();
        }
    }

    // Incremental-snapshot probes (tests, bench): delta publishes should
    // dwarf full rebuilds outside world loads and render-distance raises.

    /** Snapshot publishes (swaps) this session — delta and fallback alike. */
    public static long snapshotPublishes() {
        synchronized (LOCK) {
            return snapshotPublishes;
        }
    }

    /** Publishes that took the full-rebuild fallback. */
    public static long snapshotFullRebuilds() {
        synchronized (LOCK) {
            return snapshotFullRebuilds;
        }
    }

    /** Change-log records appended (lifetime). */
    public static long snapshotLogRecords() {
        synchronized (LOCK) {
            return snapshotLogRecords;
        }
    }

    // ------------------------------------------------------------------
    // Build-thread entry points (via SectionBuildTap)
    // ------------------------------------------------------------------

    static void enqueueUpload(Object mesh, int sx, int sy, int sz, EncodedSectionMesh encoded,
            int[] translucentOrder, byte builtTier) {
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
            PendingUpload previous = pendingUploads.put(mesh,
                    new PendingUpload(sx, sy, sz, encoded, translucent, builtTier));
            if (previous != null) {
                staleParks++; // one mesh, two encodings — should be impossible
                pendingPosDrop(previous.sx(), previous.sy(), previous.sz());
            }
            pendingPosAdd(sx, sy, sz);
        }
    }

    private static void pendingPosAdd(int sx, int sy, int sz) {
        long pos = posPack(sx, sy, sz);
        pendingByPos.merge(pos, 1, Integer::sum);
        // A held copy whose position just gained a queued successor is
        // now deliberately kept for a replacement - since step 4 the
        // ledger byte IS what protects it from every sweep, so the
        // promotion is recorded here, at the fact's one write site. One
        // map probe, retained-guarded.
        if (!retained.isEmpty()) {
            Resident held = retained.get(pos);
            if (held != null && held.ledgerState == LEDGER_NEAR_HELD) {
                ledgerTransitionLocked(held, LEDGER_AWAITING_SUCCESSOR);
            }
        }
    }

    private static void pendingPosDrop(int sx, int sy, int sz) {
        long pos = posPack(sx, sy, sz);
        pendingByPos.compute(pos, (k, n) -> n == null || n <= 1 ? null : n - 1);
        // SEAM step 4: the LAST queued successor at this position died
        // un-landed (discarded before upload, oversize, encoding
        // failure). Under the shadow this was an IMPLICIT demote to
        // ordinary retained; the demote edge is dead now - the hold does
        // not end because a clock or a successor did, it ends by
        // replacement or by an enumerated exit. A COVERED position files
        // the demand the dead successor leaves behind (the far swap
        // becomes the replacement); one inside vanilla's disc waits for
        // vanilla's next compile (the supersede bind - fail-open, the
        // copy keeps drawing); one past the outer edge is the E1
        // sweep's, released within a pump and counted.
        if (!pendingByPos.containsKey(pos) && !retained.isEmpty()) {
            Resident held = retained.get(pos);
            if (held != null && held.ledgerState == LEDGER_AWAITING_SUCCESSOR
                    && coveredNow(held.sx, held.sz, false)) {
                successorRequests.add(pos);
            }
        }
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

    // ------------------------------------------------------------------
    // The ledger's machinery (constants and counters are up beside the
    // Resident class; the legal table is documented there). Everything
    // here runs under LOCK. ENFORCING since seam step 4: the state byte
    // is read by the sweeps, the far admission and the E1/E2 machinery,
    // and every transition into AWAITING enters the ring buckets E2
    // evicts from.
    // ------------------------------------------------------------------

    /**
     * Apply one ownership transition. O(1): a table check, the byte
     * write, the AWAITING gauge/hold arithmetic and (into AWAITING) one
     * bucket append. The gauge follows the BYTE unconditionally - even
     * across an illegal transition - so {@link #ledgerAwaitingQuadsPeak}
     * stays meaningful while a table violation is being investigated
     * rather than compounding it.
     */
    private static void ledgerTransitionLocked(Resident r, byte to) {
        byte from = r.ledgerState;
        r.ledgerState = to;
        if (!ledgerLegalTransition(from, to)) {
            ledgerIllegalTransitions++;
            if (ledgerReportBudget > 0) {
                ledgerReportBudget--;
                MesheliumLog.LOGGER.error(
                        "Meshelium ownership ledger saw a transition outside the "
                                + "legal table: {} -> {} at section {},{},{} ({} more of "
                                + "these will be logged this session). The producing "
                                + "sites of every dead edge were deleted at seam step 4, "
                                + "so this is a resurrected mechanism or a real "
                                + "ownership bug.",
                        ledgerStateName(from), ledgerStateName(to), r.sx, r.sy, r.sz,
                        ledgerReportBudget);
            }
        }
        if (to == LEDGER_AWAITING_SUCCESSOR && from != LEDGER_AWAITING_SUCCESSOR) {
            ledgerParked++;
            ledgerAwaitingQuads += r.quadCount;
            if (ledgerAwaitingQuads > ledgerAwaitingQuadsPeak) {
                ledgerAwaitingQuadsPeak = ledgerAwaitingQuads;
            }
            // E2's eviction structure and E1's scan surface: bucket by
            // ring AT PARK TIME (lazy removal - every consumer validates
            // the entry is still AWAITING before acting on it).
            int ring = (int) Math.min(awaitingByRing.length - 1L,
                    ledgerCameraRingLocked(r));
            it.unimi.dsi.fastutil.longs.LongArrayList bucket = awaitingByRing[ring];
            if (bucket == null) {
                bucket = new it.unimi.dsi.fastutil.longs.LongArrayList();
                awaitingByRing[ring] = bucket;
            }
            bucket.add(posPack(r.sx, r.sy, r.sz));
        } else if (from == LEDGER_AWAITING_SUCCESSOR && to != LEDGER_AWAITING_SUCCESSOR) {
            ledgerAwaitingQuads -= r.quadCount;
            long held = r.orphanedAtMillis == 0
                    ? 0 : monotonicMillis() - r.orphanedAtMillis;
            if (held < 0) {
                held = 0;
            }
            ledgerHoldMillis += held;
            if (held > ledgerHoldMaxMillis) {
                ledgerHoldMaxMillis = held;
            }
            if (to == LEDGER_NEAR_HELD) {
                ledgerDemoted++; // dead edge; counted so a resurrection shows
            }
        }
    }

    /** The legal table, verbatim from the block comment above Resident. */
    private static boolean ledgerLegalTransition(byte from, byte to) {
        return switch (from) {
            case LEDGER_UNBOUND -> to == LEDGER_NEAR_LIVE || to == LEDGER_FAR;
            case LEDGER_NEAR_LIVE -> to == LEDGER_NEAR_HELD
                    || to == LEDGER_AWAITING_SUCCESSOR || to == LEDGER_CLEARED;
            case LEDGER_NEAR_HELD -> to == LEDGER_AWAITING_SUCCESSOR
                    || to == LEDGER_CLEARED;
            // Step 4: AWAITING -> NEAR_HELD (the demote) is DEAD - its
            // producers (deadline expiry, the dead-successor demote)
            // were deleted with the deadlines. A hold ends by
            // replacement or by an enumerated exit, never by demotion.
            case LEDGER_AWAITING_SUCCESSOR -> to == LEDGER_CLEARED;
            case LEDGER_FAR -> to == LEDGER_CLEARED;
            default -> false; // CLEARED is terminal; unknown bytes are bugs
        };
    }

    private static String ledgerStateName(byte state) {
        return switch (state) {
            case LEDGER_UNBOUND -> "UNBOUND";
            case LEDGER_NEAR_LIVE -> "NEAR_LIVE";
            case LEDGER_NEAR_HELD -> "NEAR_HELD";
            case LEDGER_FAR -> "FAR";
            case LEDGER_AWAITING_SUCCESSOR -> "AWAITING_SUCCESSOR";
            case LEDGER_CLEARED -> "CLEARED";
            default -> "?" + state;
        };
    }

    /**
     * Camera-relative Chebyshev ring (chunks) for the wall-eviction
     * percentile counter, the P9 rule kept: prefer the LIVE camera tap,
     * fall back to the walker's published ring centre, and answer 0 when
     * neither exists (session edges; documented on the counter).
     */
    private static long ledgerCameraRingLocked(Resident r) {
        long camera = SectionBuildTap.cameraSectionXZ();
        if (camera == SectionBuildTap.CAMERA_SECTION_UNKNOWN) {
            camera = farCoverageCamera;
        }
        if (camera == SectionBuildTap.CAMERA_SECTION_UNKNOWN) {
            return 0;
        }
        long dx = Math.abs((long) r.sx - (int) (camera >> 32));
        long dz = Math.abs((long) r.sz - (int) camera);
        return Math.max(dx, dz);
    }

    /**
     * The debug membership audit ({@code -Dmeshelium.debug.ledgerAudit}):
     * state byte vs container membership, every entry, once per pump,
     * under LOCK. Since step 4 the state byte is itself the fact for a
     * coverage park, so the audit checks what stays independently
     * derivable: container membership per state, "a queued successor
     * forces AWAITING", and the AWAITING quad gauge against the
     * population it claims to measure. Read-only; reports ride the
     * shared budget.
     */
    private static void auditLedgerLocked() {
        for (Resident r : resident.values()) {
            if (r.ledgerState != LEDGER_NEAR_LIVE) {
                reportLedgerMismatchLocked("live map", r);
            }
        }
        long awaitingQuadsSeen = 0;
        for (Map.Entry<Long, Resident> entry : retained.entrySet()) {
            Resident r = entry.getValue();
            long pos = entry.getKey();
            // Step 4: the state byte IS the fact now (the coverage park
            // has no independent container), so the audit checks what is
            // still independently derivable - a queued successor FORCES
            // AWAITING, a retained entry is never LIVE/FAR/CLEARED, and
            // the AWAITING quad gauge must equal the population it
            // claims to measure.
            if (r.ledgerState != LEDGER_AWAITING_SUCCESSOR
                    && r.ledgerState != LEDGER_NEAR_HELD) {
                reportLedgerMismatchLocked("retained", r);
            } else if (pendingByPos.containsKey(pos)
                    && r.ledgerState != LEDGER_AWAITING_SUCCESSOR) {
                reportLedgerMismatchLocked("retained (successor queued)", r);
            }
            if (r.ledgerState == LEDGER_AWAITING_SUCCESSOR) {
                awaitingQuadsSeen += r.quadCount;
            }
        }
        if (awaitingQuadsSeen != ledgerAwaitingQuads) {
            ledgerAuditMismatches++;
            if (ledgerReportBudget > 0) {
                ledgerReportBudget--;
                MesheliumLog.LOGGER.error(
                        "Meshelium ledger audit: AWAITING quad gauge {} but the retained "
                                + "map holds {} awaiting quads ({} more of these will be "
                                + "logged this session)",
                        ledgerAwaitingQuads, awaitingQuadsSeen, ledgerReportBudget);
            }
        }
        for (Resident r : farResident.values()) {
            if (r.ledgerState != LEDGER_FAR) {
                reportLedgerMismatchLocked("far map", r);
            }
        }
    }

    private static void reportLedgerMismatchLocked(String container, Resident r) {
        ledgerAuditMismatches++;
        if (ledgerReportBudget > 0) {
            ledgerReportBudget--;
            MesheliumLog.LOGGER.error(
                    "Meshelium ledger audit: section {},{},{} sits in {} but carries "
                            + "state {} ({} more of these will be logged this session)",
                    r.sx, r.sy, r.sz, container, ledgerStateName(r.ledgerState),
                    ledgerReportBudget);
        }
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
                // Step 4: no watch to discharge any more. If a parked
                // AWAITING copy was waiting on this very successor, the
                // pendingPosDrop below files the far demand for it
                // (covered) or leaves it to the E1 sweep (uncovered).
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
                    putRetainedLocked(posPack(r.sx, r.sy, r.sz), r);
                    ledgerTransitionLocked(r, LEDGER_AWAITING_SUCCESSOR); // bridge arm (rebuild)
                    quadsResident -= r.quadCount;
                    retainedQuads += r.quadCount;
                    handoverRetained++;
                    logSlotWriteLocked(r.snapshotSlot); // [19] flips; slot + [18] kept
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
                    putRetainedLocked(posPack(r.sx, r.sy, r.sz), r);
                    ledgerTransitionLocked(r, LEDGER_NEAR_HELD); // wave-11 distance retain
                    quadsResident -= r.quadCount;
                    retainedQuads += r.quadCount;
                    orphanedSections++;
                    logSlotWriteLocked(r.snapshotSlot);
                    drawEpoch++; // [19] flips for this entry
                    return;
                }
            }
            // SEAM step 4, THE COVERAGE PARK (docs/FARFIELD-SEAM-DESIGN.md
            // §2, freeOrHoldLocked). Reaching here means vanilla's copy is
            // going for good. If the position is FAR-DOMAIN under the
            // published coverage geometry, this free would unbind a
            // covered, drawn position - the exact event the invariant
            // forbids - so the copy PARKS as AWAITING_SUCCESSOR instead:
            // bridge mechanics verbatim (markRetained + putRetained +
            // orphan stamp + [19] flip), NO deadline, NO population cap,
            // NO per-frame cap. The hold ends by the far swap, vanilla's
            // own re-upload (the ONLY inner-edge exit - the supersede
            // bind), E1 (the position leaves the coverage's OUTER edge,
            // or the geometry is withdrawn), E2 (the AWAITING quad
            // budget, farthest-first) or E3 - never by a clock. This one branch generalises the deleted H3 proven
            // bridge, the deleted M4 speculative bridge and their whole
            // refusal census: there is nothing left to refuse, so the
            // pre7/pre11 flash has no producing site any more.
            //
            // The park FILES THE DEMAND in the same breath
            // (successorRequests -> the walker's unguarded priority path)
            // - the one channel kept from the contested watch. A MISS
            // does not end the hold; when the extractor eventually stores
            // the shell, onShellWritten -> lateShells -> read -> swap.
            //
            // Uncovered positions (vanilla's disc is vanilla's business;
            // past L1 is fog), slotless residents (their successor
            // already took the slot - the position stays drawn) and a
            // refused markRetained (the slot is not this copy's to hold)
            // free exactly as before.
            long pos = posPack(r.sx, r.sy, r.sz);
            if (r.ownsSlot && arena != null && coveredNow(r.sx, r.sz, inSlotReset())
                    && regionStore.markRetained(r.regionKey, r.posKey, r)) {
                r.orphanedAtMillis = monotonicMillis();
                putRetainedLocked(pos, r);
                ledgerTransitionLocked(r, LEDGER_AWAITING_SUCCESSOR); // coverage park
                quadsResident -= r.quadCount;
                retainedQuads += r.quadCount;
                logSlotWriteLocked(r.snapshotSlot); // [19] flips; slot + [18] kept
                drawEpoch++;
                successorRequests.add(pos);
                if (ledgerAwaitingQuads > awaitingBudgetQuads) {
                    evictAwaitingFarthestLocked(); // E2, counted
                }
                return;
            }
            pendingPrefixUploads.remove(r); // a queued resort upload dies with it
            ledgerTransitionLocked(r, LEDGER_CLEARED); // plain free -> clear
            drawEpoch++;
            quadsResident -= r.quadCount;
            freedSections++;
            if (r.ownsSlot) {
                logMovedOwnerLocked(regionStore.remove(r.regionKey, r.posKey, r));
            }
            parkAddrLocked(r.arenaAddr, r.quadCount);
            freeSnapshotSlotLocked(r);
            // The LIVE free. Every branch above that KEEPS the position
            // owned already returned; reaching here means the position is
            // either not far-domain (vanilla's disc, or fog past L1 - not
            // this invariant's business) or this copy was not the drawn
            // one (slotless: its successor already took the slot). No
            // watch to discharge - a position the far field is owed
            // announces itself when it parks, and one that never parks
            // is discovery's problem (the ring walk), not continuity's.
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
            long pos = posPack(sx, sy, sz);
            // W3: an empty compile is equally a statement about a FAR
            // shell copy there — the world says the section is empty
            // now (vanilla precedence, dig-out shape). Build thread:
            // the walker's bookkeeping rides the deferred queue.
            if (!farResident.isEmpty()) {
                Resident far = farResident.remove(pos);
                if (far != null) {
                    freeFarLocked(far);
                    farFreedPending.add(pos);
                    drawEpoch++;
                }
            }
            Resident r = retained.remove(pos);
            if (r == null) {
                return;
            }
            // SEAM shadow: an empty compile is vanilla's own truth ending
            // the hold, so a parked AWAITING entry resolves VANILLA here -
            // classified before the common free tail, whose generic arm
            // would otherwise read it as a memory eviction.
            if (r.ledgerState == LEDGER_AWAITING_SUCCESSOR) {
                ledgerResolvedVanilla++;
            }
            ledgerTransitionLocked(r, LEDGER_CLEARED);
            freeRetainedLocked(r);
            retainedSuperseded++;
            drawEpoch++;
        }
    }

    /**
     * SEAM step 4: E2's eviction, and the ONLY discretionary way an
     * AWAITING hold ends. Called when a park pushes
     * {@code ledgerAwaitingQuads} past {@link #awaitingBudgetQuads}:
     * spend parked entries FARTHEST-RING-FIRST until the gauge is back
     * under budget, so the violation the budget sanctions lands in the
     * fog and the held remainder is the visible inner band. Pops the
     * highest non-empty bucket; a slot whose entry is no longer AWAITING
     * (resolved by swap/vanilla/E1 since it was bucketed - the lazy
     * removal contract) is dropped for free. Never touches an entry
     * whose vanilla successor is queued: that hold is the rebuild
     * handover (correctness for one pump, not memory), and its
     * replacement is already on the way, so evicting it buys nothing
     * and opens the 2026-08-15 black-ocean hole.
     *
     * <p>Counted on {@code ledgerEvictedWall}(+RingSum) by
     * {@link #freeRetainedLocked}'s E2 arm - this method deliberately
     * classifies nothing itself, so the wall count has exactly one
     * bookkeeping site however many callers eviction ever grows.</p>
     */
    private static void evictAwaitingFarthestLocked() {
        for (int ring = awaitingByRing.length - 1;
                ring >= 0 && ledgerAwaitingQuads > awaitingBudgetQuads; ring--) {
            it.unimi.dsi.fastutil.longs.LongArrayList bucket = awaitingByRing[ring];
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            for (int i = bucket.size() - 1;
                    i >= 0 && ledgerAwaitingQuads > awaitingBudgetQuads; i--) {
                long pos = bucket.getLong(i);
                Resident r = retained.get(pos);
                if (r == null || r.ledgerState != LEDGER_AWAITING_SUCCESSOR) {
                    bucket.removeLong(i); // lazy removal
                    continue;
                }
                if (successorQueued(r.sx, r.sy, r.sz)) {
                    // The rebuild handover: its replacement is already on
                    // the way and evicting it opens the black-ocean hole.
                    // The slot STAYS - stripping it would orphan the
                    // entry from the E1 sweep if its successor later
                    // dies un-landed at an uncovered position.
                    continue;
                }
                bucket.removeLong(i);
                retained.remove(pos);
                freeRetainedLocked(r); // E2, counted there
                drawEpoch++;
            }
        }
    }

    /**
     * SEAM step 4: E1, the coverage-exit sweep - the second of the three
     * enumerated ways an AWAITING hold may end, and the one that replaces
     * the deleted deadline demote for entries the far field genuinely no
     * longer covers. Once per pump, budgeted like the retained sweeps:
     * walk the ring buckets (cursor-resumed so a storm backlog drains
     * over following pumps) and release the entries whose position left
     * the coverage's OUTER edge - or whose geometry was withdrawn
     * entirely (master off, latch, era end). Entries whose vanilla
     * successor is queued are skipped for the reason on
     * {@link #evictAwaitingFarthestLocked}; entries no longer AWAITING
     * are the lazy-removal garbage and are dropped for free.
     *
     * <p><b>The INNER edge is deliberately not a release here</b> - the
     * leg-B first-run catch (2026-08-24), and the same lesson the
     * walker's {@code releaseColumns} already carries in its own words
     * ("released by the ARRIVAL of the real thing instead"): a parked
     * copy whose column re-enters vanilla's disc is exactly the copy
     * that must keep drawing until vanilla's compile lands, because the
     * flash-free exit at the inner edge is the wave-11 supersede BIND
     * (atomic, one hold, {@code ledgerResolvedVanilla}), never a
     * geometry test that races the compile. Releasing on
     * {@code coveredNow}'s inner arm reintroduced, one layer down, the
     * very inner-edge demote the walker deleted - the owner's P9 "works
     * good and seemless" direction was the thing at stake. An
     * inner-parked copy that never gets a supersede simply keeps
     * drawing real geometry (fail-open), re-exits still parked, and
     * stays bounded by E2's quad budget.</p>
     *
     * <p>Ring-at-park cannot soundly SKIP buckets (the camera has moved
     * since the park), so the sweep walks entries, not geometry - cheap
     * because the parked population is bounded by the quad budget and is
     * normally a few hundred entries on a travel leg.</p>
     */
    private static void scanAwaitingCoverageLocked() {
        if (ledgerAwaitingQuads <= 0) {
            return;
        }
        int budget = EVICT_BUDGET_PER_PUMP;
        for (int step = 0; step < awaitingByRing.length && budget > 0; step++) {
            awaitingScanRing = (awaitingScanRing + 1) % awaitingByRing.length;
            it.unimi.dsi.fastutil.longs.LongArrayList bucket =
                    awaitingByRing[awaitingScanRing];
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            for (int i = bucket.size() - 1; i >= 0 && budget > 0; i--) {
                long pos = bucket.getLong(i);
                Resident r = retained.get(pos);
                if (r == null || r.ledgerState != LEDGER_AWAITING_SUCCESSOR) {
                    bucket.removeLong(i); // lazy removal
                    continue;
                }
                budget--;
                if (successorQueued(r.sx, r.sy, r.sz)
                        || withinCoverageOuterEdge(r.sx, r.sz)) {
                    // Still held: rebuild handover, or still inside the
                    // outer edge - which includes the whole of vanilla's
                    // disc, where the exit is the supersede BIND.
                    continue;
                }
                // E1: the position left the coverage's outer edge (or the
                // geometry is withdrawn). Classified HERE, before the
                // common tail, so the generic arm cannot read it as a
                // memory eviction.
                bucket.removeLong(i);
                retained.remove(pos);
                ledgerResolvedUncovered++;
                ledgerTransitionLocked(r, LEDGER_CLEARED);
                freeRetainedLocked(r);
                drawEpoch++;
            }
        }
    }

    /** Common tail of every retained-copy release path. Caller bumps counters/epoch. */
    private static void freeRetainedLocked(Resident r) {
        // The ledger's E2 arm. An entry still marked AWAITING when this
        // tail frees it is a parked copy spent for MEMORY - the AWAITING
        // quad budget (evictAwaitingFarthestLocked) or the force-evict
        // wall. That is the one sanctioned continuity violation, so it is
        // counted with its ring. Every OTHER ending classified itself
        // before calling in: empty compiles as VANILLA, the E1 sweep as
        // UNCOVERED - both arrive already CLEARED - and there are no
        // deadline expiries left to arrive at all. A NEAR_HELD entry
        // going out through an ordinary eviction is an ordinary clear.
        if (r.ledgerState == LEDGER_AWAITING_SUCCESSOR) {
            ledgerEvictedWall++;
            ledgerEvictedWallRingSum += ledgerCameraRingLocked(r);
            ledgerTransitionLocked(r, LEDGER_CLEARED);
        } else if (r.ledgerState != LEDGER_CLEARED) {
            ledgerTransitionLocked(r, LEDGER_CLEARED);
        }
        pendingPrefixUploads.remove(r);
        retainedQuads -= r.quadCount;
        freedSections++;
        // Retained entries always own their slot, so this remove always
        // reaches the store — and may swap-compact a third party (§3).
        logMovedOwnerLocked(regionStore.remove(r.regionKey, r.posKey, r));
        parkAddrLocked(r.arenaAddr, r.quadCount);
        freeSnapshotSlotLocked(r);
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
            // SEAM shadow, E3 (world-era end): the containers die
            // wholesale and the ledger performs no per-entry transitions
            // - only the AWAITING gauge resets with the population it
            // was measuring. The peak survives; it is a session number.
            ledgerAwaitingQuads = 0;
            retained.clear();
            // E3: the AWAITING machinery dies with the entries it was
            // holding. Kept out of the farEverArmed guard below on
            // purpose — these are plain containers in THIS class, so
            // clearing them names no far-field symbol and cannot disturb
            // the zero-cost-off posture, and leaving world A's parked
            // positions standing would let world B inherit a demand at
            // the same coordinates.
            successorRequests.clear();
            for (int ring = 0; ring < awaitingByRing.length; ring++) {
                it.unimi.dsi.fastutil.longs.LongArrayList bucket = awaitingByRing[ring];
                if (bucket != null) {
                    bucket.clear();
                }
            }
            awaitingScanRing = 0;
            // ... and so does the coverage geometry the parks were
            // decided against. The walker republishes on its first pump
            // in the new world; until then no release may park for a
            // world whose store has just been thrown away (E3 is an
            // enumerated exit; a park across it would be a leak).
            farCoverageCamera = SectionBuildTap.CAMERA_SECTION_UNKNOWN;
            farCoverageCoverEdge = -1;
            farCoverageL1 = -1;
            // W3: far copies die with the store exactly like retained
            // ones (per-world, policy (c)); the walker is told so its
            // gauge and column maps reconcile. Guarded on the sticky
            // arm so a session that never enabled the far field never
            // class-loads the walker (the zero-cost-off posture), and
            // armed on every disposal after that so the walker's
            // transient state is always reset with the store.
            //
            // The dropped count MUST include farFreedPending: those
            // sections were already freed out of farResident by the
            // supersede/evict paths, but their gauge decrement rides
            // the deferred flush that is about to be discarded. Counting
            // only the map would leave farSectionsResident permanently
            // high for the rest of the session.
            if (farEverArmed) {
                int farDropped = farResident.size() + farFreedPending.size();
                farResident.clear();
                farFreedPending.clear();
                com.deds.meshelium.farfield.FarFieldResidency.onResidencyDisposed(farDropped);
            }
            parkedQuads = 0;
            pendingUploads.clear();
            pendingByPos.clear();
            resident.clear();
            freeEpochs.clear();
            pendingPrefixUploads.clear();
            // Leaf-tier walker state is per-world like the store it
            // walks: the flagged census just went to zero with resident,
            // and a stale camera/ring memory would only skip or waste the
            // next world's first walk.
            tierBuiltFlagged = 0;
            leafTierWalkCamera = SectionBuildTap.CAMERA_SECTION_UNKNOWN;
            leafTierWalkSmartRing = -1;
            leafTierWalkSolidRing = -1;
            leafTierWalkPending = false;
            // H2: same argument for the unlisted-live sweep's arm memory.
            // A new world can easily stand up with the camera in the same
            // SECTION at the same render distance (a dimension hop lands
            // at scaled coordinates, a rejoin lands where you logged out),
            // and a stale memory there would early-out the first sweep and
            // leave the new world's corner lobes unmarked until the player
            // walked a chunk.
            unlistedSweepCamera = SectionBuildTap.CAMERA_SECTION_UNKNOWN;
            unlistedSweepRadius = -1;
            unlistedSweepCameraY = Integer.MIN_VALUE;
            unlistedLiveDrawn = 0;
            unlistedAltitudeDrawn = 0;
            unlistedBandDrawn = 0;
            unlistedBandCoreDrawn = 0;
            // P2: and the altitude arm, which is worse than stale — it is
            // published to a mixin that keeps reading it while no world
            // exists. Relaxing vanilla's cull against an empty resident
            // set is harmless but dishonest; disarm it with the store.
            unlistedTerrainTopSy = Integer.MIN_VALUE;
            unlistedTerrainLowSy = Integer.MIN_VALUE;
            altitudeArmLatched = false;
            altitudeCullRelaxed = false;
            // S6: the local horizon is camera-relative, so a new world in
            // the same section would otherwise read the old world's terrain.
            columnTopSide = 0;
            unlistedHorizonEntries = 0;
            unlistedSweepHighCamera = false;
            unlistedSweepLocalHorizon = false;
            unlistedSweepEnabled = false;
            unlistedBindMarks = 0;
            unlistedBindBandMarks = 0;
            regionStore = new RegionStore();
            arena = null; // the whole allocator dies with its buffer
            sectionRecordsHandle = 0L; // its VkBuffer is on the destroy queue too
            // The incremental draw snapshot dies with the store: every slot
            // above just became garbage, which is exactly the log-overflow
            // CLASS of event (S6, mutations dossier) — reset the free-list,
            // the log ring and the owner index, and route BOTH ping-pong
            // buffers through the full rebuild (they re-size lazily at the
            // next publish; absolute log indices stay monotonic and the
            // rebuild resets both applied indices to the current end).
            snapshotSlotCap = SNAPSHOT_SLOTS_INITIAL;
            snapshotSlotHighWater = 0;
            snapshotSlotOwner = new Resident[SNAPSHOT_SLOTS_INITIAL];
            snapshotFreeSlots.clear();
            snapshotLog = new int[SNAPSHOT_LOG_INITIAL];
            forceSnapshotRebuildLocked();
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
     *
     * <p>The leaf-tier restore runs in two halves around the lock edge:
     * the walk COLLECTS positions under LOCK (it reads and mutates
     * Resident state), the re-dirty calls vanilla AFTER the lock is
     * released — the class javadoc's discipline that no Meshelium code
     * calls back into vanilla while holding LOCK, kept by construction
     * here because the collected list is a plain int triple list with no
     * reference back into the store.</p>
     */
    public static void pump(TerrainGpuHost gpu) {
        IntArrayList leafTierRestores;
        // H2: vanilla's own BFS radius, read on the render thread BEFORE
        // the lock — the no-vanilla-under-LOCK discipline, kept the same
        // way the leaf-tier restore keeps it, by turning the vanilla call
        // into a plain int at the lock edge. Same value ViewArea and
        // SectionOcclusionGraph use (LevelRenderer.invalidateCompiledGeometry
        // ip 173-176, javap), so the disc this feeds is vanilla's own.
        Minecraft minecraft = Minecraft.getInstance();
        int vanillaViewRadius = minecraft != null && minecraft.options != null
                ? minecraft.options.getEffectiveRenderDistance() : 0;
        // O7: and the camera's section Y, read at the same lock edge and
        // by the same rule (a vanilla call turned into a plain int before
        // the monitor). GameRenderer.mainCamera() / Camera.blockPosition()
        // / Camera.isInitialized() are javap-verified public on 26.2;
        // >> 4 is SectionPos.blockToSectionCoord's own arithmetic, so a
        // negative Y floors the way vanilla's does. MIN_VALUE means "no
        // camera", which disarms the O7 arm and nothing else.
        Camera mainCamera = minecraft != null && minecraft.gameRenderer != null
                ? minecraft.gameRenderer.mainCamera() : null;
        int cameraSectionY = mainCamera != null && mainCamera.isInitialized()
                && mainCamera.blockPosition() != null
                        ? mainCamera.blockPosition().getY() >> 4 : Integer.MIN_VALUE;
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
            // SEAM step 4: the AWAITING quad budget is re-priced once per
            // pump (arenaCeilingBytes reads system properties, and parks
            // happen on build threads between pumps, so they read this
            // cached value). min(1/8 of the arena quad ceiling, the
            // sections'-worth constant) - the design's bound, verbatim.
            awaitingBudgetQuads = Math.min(
                    MesheliumScaling.arenaCeilingBytes()
                            / (4L * TerrainVertexCodec.VERTEX_STRIDE) / 8L,
                    AWAITING_BUDGET_SECTIONS * AWAITING_MEAN_QUADS_PER_SECTION);
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
                // SEAM step 4: E1 (coverage exit) and the budget backstop
                // run beside the retained sweep, before the drains, so a
                // parked entry whose position left the far domain frees
                // in the same pump that could have re-used its budget.
                scanAwaitingCoverageLocked();
                if (ledgerAwaitingQuads > awaitingBudgetQuads) {
                    evictAwaitingFarthestLocked(); // ceiling lowered live
                }
                // H2: before the drains, so a mask bit set for a section
                // that is about to be superseded this very pump is cleared
                // again by addOrReplace rather than published stale.
                syncUnlistedLiveMaskLocked(vanillaViewRadius, cameraSectionY);
                drainPendingUploadsLocked(gpu);
                // W3 far field: admissions land HERE, beside the vanilla
                // drain (the seam dossier's plug point) — after it, so
                // live sections always get the pump's budgets first.
                drainFarFieldLocked(gpu);
                drainPendingPrefixUploadsLocked(gpu);
                maybeTrimArenaLocked(gpu);
                if (LEDGER_AUDIT) {
                    // SEAM step 1's membership audit: after every drain,
                    // so it judges the settled pump state. Debug-only.
                    auditLedgerLocked();
                }
            }
            regionStore.commitDirty(gpu);
            gpu.endFrame();
            maybeLogStatsLocked(gpu);
            leafTierRestores = collectLeafTierRestoresLocked();
        }
        redirtyLeafTierSections(leafTierRestores);
        // W3 far field: the walker's act-outside-the-lock half (ring
        // scan, store requests, demote releases) shares the residency
        // pump cadence exactly like the leaf-tier redirty above.
        pumpFarFieldOutsideLock();
    }

    /**
     * W3: the far-field walker's per-pump entry, OUTSIDE the store lock
     * (it calls the store facade and, on demotes, re-enters through
     * {@link #releaseFarColumn} which takes LOCK itself). Its own broken
     * latch so even a class-initialization failure in the farfield
     * package can only cost the far field, never the pump — the
     * surrounding {@code MesheliumTerrainPump} catch would otherwise
     * disable residency for the session.
     */
    private static void pumpFarFieldOutsideLock() {
        if (farHookBroken) {
            return;
        }
        if (!farEverArmed) {
            // Zero-cost-off (docs/FARFIELD-WAVES.md standing rules): never
            // emit a FarFieldResidency symbol in a session where the master
            // switch was never on, so the walker and the sprite resolver
            // never class-load. FarFieldConfig is already loaded in every
            // session by the W2 level-swap path, so naming it costs nothing
            // new. Deliberately STICKY: once armed, the walker keeps being
            // pumped even after a mid-session disable, because its
            // disarmed branch is the drain that RELEASES far residents.
            // Gating on enabled() alone would strand every admitted far
            // section (trading the zero-cost invariant for a leak).
            if (!com.deds.meshelium.farfield.FarFieldConfig.enabled()) {
                return;
            }
            farEverArmed = true;
        }
        try {
            com.deds.meshelium.farfield.FarFieldResidency.pump();
        } catch (Throwable t) {
            farHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium far-field pump hook failed; the far field is off for this "
                            + "session (near-field rendering is unaffected)", t);
        }
    }

    // ------------------------------------------------------------------
    // Smart/Solid Leaves Beyond — the ring-crossing walker (render thread)
    // ------------------------------------------------------------------

    /**
     * At most this many re-dirties per pump. Each one costs vanilla a
     * full section rebuild (plus its neighbors' visibility refresh), so
     * an unbudgeted walk after a big camera jump — or after both settings
     * are turned OFF, which restores EVERYTHING — would storm the build
     * queue and defeat the feature it serves. 64 per pump drains even a
     * worst-case backlog in a few seconds, which the tooltips promise.
     */
    private static final int LEAF_TIER_REDIRTY_BUDGET = 64;

    /** Camera section the walker last armed against (packed X/Z). */
    private static long leafTierWalkCamera = SectionBuildTap.CAMERA_SECTION_UNKNOWN;
    /** Smart ring the walker last armed against (a live config change re-arms). */
    private static int leafTierWalkSmartRing = -1;
    /** Solid ring the walker last armed against (same re-arm rule). */
    private static int leafTierWalkSolidRing = -1;
    /** A walk is armed or mid-drain (budget ran out last pump). */
    private static boolean leafTierWalkPending;
    /**
     * How many {@link Resident} objects still carry a {@code builtTier}
     * above {@code TIER_NONE} — the gate that makes the walker FREE while
     * both features are off or unused: zero means no sweep can ever
     * collect anything, so the O(resident) iteration is skipped on every
     * camera crossing, which is the whole cost the tiers add to a player
     * who never touches either slider. Deliberately an OVER-count: a
     * flagged Resident that dies (supersede, release, eviction) is not
     * decremented — chasing the five death sites for a diagnostic gate is
     * how bookkeeping drifts — so a stale positive only costs a no-op
     * sweep, never skips a real one. Reset with the store at dispose.
     */
    private static int tierBuiltFlagged;

    /**
     * Under LOCK: arm a walk when the camera crossed into a new section
     * OR either slider moved (the second trigger is what makes turning a
     * setting down or OFF drain live — both OFF means no ring earns any
     * tier, so every tiered entry collects), then sweep the resident set
     * for sections whose built tier now EXCEEDS the tier their distance
     * would earn on the restore side of the hysteresis: each ring's
     * restore edge is (ring − 1), one inside its build gate's (ring + 1),
     * so a camera idling on either ring cannot oscillate a section. The
     * one compare covers every case — a Solid section approaching past
     * the solid ring but still beyond the smart ring rebuilds down to
     * Smart, one inside (smartRing − 1) rebuilds to full detail, a
     * lowered slider drains the difference, both sliders Off drain
     * everything. The walker only ever DEMOTES tiers: a section built
     * lighter than its distance now allows stays as built until vanilla
     * rebuilds it anyway, exactly the one-tier walker's behavior.
     * Collected entries have their tier RESET here, so a section is
     * collected once per tiered build no matter how many pumps pass
     * before its rebuild lands; a budget-exhausted sweep stays pending
     * and resumes next pump. Retained entries are deliberately not
     * walked: vanilla holds no mesh for them, so there is nothing to
     * re-dirty — a supersede or eviction is their only exit, exactly as
     * before this feature.
     *
     * @return section coords as (sx, sy, sz) triples, or null
     */
    private static IntArrayList collectLeafTierRestoresLocked() {
        long camera = SectionBuildTap.cameraSectionXZ();
        if (camera == SectionBuildTap.CAMERA_SECTION_UNKNOWN) {
            return null; // no frame has published a camera yet
        }
        int smartRing = MesheliumConfig.smartLeavesChunks();
        int solidRing = MesheliumConfig.solidLeavesChunks();
        if (camera != leafTierWalkCamera || smartRing != leafTierWalkSmartRing
                || solidRing != leafTierWalkSolidRing) {
            leafTierWalkCamera = camera;
            leafTierWalkSmartRing = smartRing;
            leafTierWalkSolidRing = solidRing;
            leafTierWalkPending = true;
        }
        if (!leafTierWalkPending || tierBuiltFlagged == 0) {
            return null; // nothing armed, or provably nothing to restore
        }
        IntArrayList out = null;
        int budget = LEAF_TIER_REDIRTY_BUDGET;
        // Each tier keeps holding a section at distance >= (its ring − 1),
        // floored at 1: without the floor a ring of 1 restores only at
        // "distance < 0", which no section ever satisfies, and tiered
        // terrain at that slider stop would be permanent. The floor keeps
        // the dead band against the build gate's (ring + 1) at every
        // reachable setting.
        int smartKeepsFrom = Math.max(1, smartRing - 1);
        int solidKeepsFrom = Math.max(1, solidRing - 1);
        for (Resident r : resident.values()) {
            if (r.builtTier == SectionBuildTap.TIER_NONE) {
                continue;
            }
            int dist = SectionBuildTap.chunkDistanceXZ(camera, r.sx, r.sz);
            // The tier this distance would still tolerate, restore-side:
            // the build tap's decision table with each (ring + 1) gate
            // replaced by its (ring − 1) twin, and an OFF ring (0) earning
            // nothing — the tap and the walker must rank the tiers
            // identically or a section could rebuild forever.
            int earned = solidRing > 0 && dist >= solidKeepsFrom ? SectionBuildTap.TIER_SOLID
                    : smartRing > 0 && dist >= smartKeepsFrom ? SectionBuildTap.TIER_SMART
                    : SectionBuildTap.TIER_NONE;
            if (r.builtTier <= earned) {
                continue; // built at or below what this distance tolerates
            }
            r.builtTier = SectionBuildTap.TIER_NONE;
            tierBuiltFlagged--;
            if (out == null) {
                out = new IntArrayList(3 * LEAF_TIER_REDIRTY_BUDGET);
            }
            out.add(r.sx);
            out.add(r.sy);
            out.add(r.sz);
            if (--budget == 0) {
                return out; // budget spent: stay pending, resume next pump
            }
        }
        leafTierWalkPending = false; // full sweep fit the budget
        return out;
    }

    /**
     * Render thread, LOCK NOT HELD (the caller released it): hand each
     * collected section back to vanilla for a Fancy rebuild.
     * {@code ClientLevel.setSectionDirtyWithNeighbors(int,int,int)} is
     * public and lock-free the whole way down (javap 26.2 merged jar:
     * ClientLevel delegates to LevelExtractor.setSectionDirtyWithNeighbors
     * → setSectionRangeDirty ±1 → SectionUpdateTracker.setDirty, plain
     * flag writes, zero monitorenter in either class), so calling it
     * inside vanilla's own lock window is safe. The rebuilt section
     * re-enters through the ordinary tap, earns whatever tier its
     * distance rates NOW (Smart for a Solid section that crossed only
     * the solid ring, full detail inside both), and its upload
     * supersedes the old copy through the wave-3b slot steal.
     */
    private static void redirtyLeafTierSections(IntArrayList triples) {
        if (triples == null || triples.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return; // world tearing down; the copies die with the dispatcher
        }
        for (int i = 0; i < triples.size(); i += 3) {
            level.setSectionDirtyWithNeighbors(
                    triples.getInt(i), triples.getInt(i + 1), triples.getInt(i + 2));
        }
    }

    // ------------------------------------------------------------------
    // H2: keep the DRAWN set equal to the OWNED set (under LOCK, pump)
    // ------------------------------------------------------------------

    /**
     * Is this camera-relative section offset inside the set vanilla's BFS
     * can reach, i.e. the set {@code visibleSections} may list? Meshelium's
     * own copy of vanilla's arithmetic, not a call into it — the vanilla
     * method lives in {@code net.minecraft.server.level} and this runs
     * under LOCK, where no vanilla call is allowed.
     *
     * <p>The contract, {@code ChunkTrackingView.isWithinDistance(int, int,
     * int, int, int, boolean)}, javap of the 26.2 merged jar read in this
     * authoring session:</p>
     * <pre>
     *   ip  0-10  pad = includeOuter ? 2 : 1
     *   ip 12-26  dx  = max(0, |x - cx| - pad)
     *   ip 28-43  dz  = max(0, |z - cz| - pad)
     *   ip 45-77  return dx*dx + dz*dz &lt; vd*vd     (STRICT less-than)
     * </pre>
     * <p>with {@code includeOuter} FALSE on the client BFS route:
     * {@code SectionOcclusionGraph.getRelativeFrom} (ip 8-19) calls the
     * private {@code isInViewDistance(JJ)}, which calls
     * {@code ChunkTrackingView.isInViewDistance(IIIII)}, which passes
     * {@code iconst_0} (ip 6). Re-check this against the bytecode on every
     * Minecraft update; it is the same arithmetic {@code FarFieldResidency
     * .nearCovered} keeps on the far side, and the two must not drift.</p>
     *
     * <p>{@code getRelativeFrom}'s second gate,
     * {@code |cameraSectionY - neighbourSectionY| > viewDistance} (ip
     * 20-44), is deliberately NOT part of THIS predicate, which answers
     * the horizontal question only. An earlier version of this paragraph
     * said omitting it "can only leave a section UNMARKED" and that it
     * "matters solely for a camera far above or below the build limit at
     * a small render distance". Both halves were wrong in the one case
     * that counts: at render distance 2 the clamp bites three sections
     * above the sea, an unmarked section there is a HOLE, and it was the
     * owner's seventh fly-up report. The vertical gate now lives in
     * {@link #vanillaAdjacencyReach}, where the camera's section Y is in
     * hand, and the O7 rule and the p10 arm key on it.</p>
     */
    private static boolean vanillaMayList(int dx, int dz, int radiusChunks) {
        if (radiusChunks <= 0) {
            return false;
        }
        long px = Math.max(0, Math.abs(dx) - 1);
        long pz = Math.max(0, Math.abs(dz) - 1);
        return px * px + pz * pz < (long) radiusChunks * radiusChunks;
    }

    /**
     * pre21: the number of chunk columns in vanilla's compile disc at a
     * render distance — {@link #vanillaMayList}'s true set, counted. 25 at
     * rd 2 (the whole 5x5), 49 at 3, 921 at 16, 3,461 at 32. For the fly-up
     * ladder, which sizes its vacuity floor from it rather than from a
     * constant tuned at one render distance; kept here so the disc has one
     * definition. Pure arithmetic, no lock.
     */
    public static int vanillaCompileDiscColumns(int radiusChunks) {
        if (radiusChunks <= 0) {
            return 0;
        }
        int columns = 0;
        for (int dx = -radiusChunks - 1; dx <= radiusChunks + 1; dx++) {
            for (int dz = -radiusChunks - 1; dz <= radiusChunks + 1; dz++) {
                if (vanillaMayList(dx, dz, radiusChunks)) {
                    columns++;
                }
            }
        }
        return columns;
    }

    /**
     * H2: drive the wave-11 retained mask so that every LIVE section
     * Meshelium owns is a section Meshelium draws, whatever vanilla's
     * {@code visibleSections} happens to list.
     *
     * <p><b>The gap this closes</b>, in one worked position. Effective
     * render distance 16, camera at chunk (0,0), a column at (14,14) that
     * the player flew past and is now receding from. It is inside
     * vanilla's section-storage square (Chebyshev 14 &le; 16), so vanilla
     * still holds its compiled mesh and Meshelium still owns its region
     * slot. It is OUTSIDE vanilla's compile disc: {@code
     * max(0,13)^2 + max(0,13)^2 = 338}, and {@code 338 < 256} is false. So
     * the BFS cannot reach it, {@code visibleSections} cannot list it, and
     * the BFS-mask draw path leaves its bit clear. Meanwhile
     * {@link #admitFarSectionLocked} refuses the far copy at that exact
     * position with {@code ADMIT_CONTESTED}, because {@code
     * regionStore.isOccupied} is TRUE — the live section is standing
     * there. Drawn by neither, and the watch that would recover it cannot
     * discharge until the SQUARE gives the slot up at Chebyshev 17. Four
     * such lobes, bounded outside by two straight square edges meeting at
     * a right angle: the owner's "corner of a square".</p>
     *
     * <p><b>Why a sweep and not an event.</b> Nothing fires when a section
     * leaves the disc — the camera moves, and the section's membership
     * changes with no call reaching Meshelium at all. The sweep is
     * therefore keyed to the only thing that can change the answer, the
     * camera's SECTION (plus the render-distance slider), and it early-outs
     * on every pump that is neither. At a walking pace that is a handful
     * of sweeps a second; at elytra cruise about two.</p>
     *
     * <p><b>Cost.</b> One pass over {@link #resident} per camera section
     * crossing: two {@code abs}, two multiplies and one O(1) region probe
     * per live section, and a hash write only on an actual transition.
     * That is roughly 4,500 entries at render distance 16 and 17,000 at 32
     * — tens of microseconds, on the pump that a chunk crossing was
     * already going to make expensive. It buys back more than it spends on
     * the far side: every lobe column it covers is a column the walker
     * stops reading, meshing and offering only to be refused.</p>
     *
     * <p><b>Fail-open by construction.</b> The bit can only ADD a draw
     * (the drawer ORs it into the BFS mask), it is owner-checked in
     * {@code RegionStore.setRetained} so it can never land on somebody
     * else's section, and both of the ways a live section can stop owning
     * its slot — {@code remove} and {@code addOrReplace} — clear it. The
     * failure direction is a section drawn when BFS would have occluded
     * it, which costs fill rate and never a hole.</p>
     *
     * <h2>The water half (K2/L10)</h2>
     * <p>The mask above is the OPAQUE half and it was the whole of pre8.
     * The owner's next report was "that corner issue seems mostly fixed
     * but its happening with water surface now", and the reason is that
     * the translucent pass has no mask to OR anything into. It has exactly
     * two sources: {@code TerrainDrawer}'s retained pre-pass, which walks
     * the snapshot and skips on {@code d[o + 19] == 0}, and a loop over
     * {@code visibleSections}, which is the pad-1 disc and cannot contain
     * a lobe column by definition. So a lobe column's water quad sat in
     * the arena, owned and non-empty, and nothing dispatched it: over land
     * the opaque half IS the terrain and the lobe looked complete, over
     * ocean the opaque half is the sea bed a dozen blocks down and the
     * player saw a dark floor with no surface on it. The sweep therefore
     * writes {@link Resident#unlistedDrawn} beside the mask bit, which
     * carries the same verdict into {@code [19]}.</p>
     *
     * <p><b>The falsifiable prediction that came with the diagnosis, kept
     * because it is the cheapest way to re-test this fix.</b> The water
     * gap was present at EVERY render distance, 48 and above included,
     * where the LAND lobes were never missing. The asymmetry is the opaque
     * path's alone: {@code MesheliumConfig.occlusionCullingEnabled} AUTO
     * arms GPU-raster occlusion at
     * {@code DEFAULT_OCCLUSION_AUTO_RD} = 48, and that path never reads
     * {@code visibleSections}, so at rd 48 the lobes' opaque geometry was
     * always drawn while their water was always missing. The translucent
     * pass has no such crossover. After this fix the two must agree at
     * BOTH ends of the crossover: rd 16 and rd 48 both show a complete
     * water surface in the corners. Probes:
     * {@link #unlistedLiveDrawn()} &gt; 0 with
     * {@link #unlistedTransFlips()} rising and
     * {@code TerrainDrawer.lastRetainedTranslucentSections()} now counting
     * those sections instead of none of them.</p>
     *
     * <p><b>Double blending is structurally impossible</b>, and it is
     * worth writing down which of the two guards does it. The pre-pass
     * stamps {@code transDrawnMark[slot]} and the visible loop consults
     * the same array through {@code translucentSlotByPos}; that map is
     * keyed by POSITION and admits only entries with {@code [18] >= 0},
     * and {@code RegionStore.globalSectionIndex} is owner-checked, so at
     * most one snapshot slot per position can ever be its value. The
     * pre-pass and the visible loop therefore look at the SAME slot, and
     * the mark is decisive even in the case this sweep exists for — a
     * section that {@code vanillaMayList} calls unlisted while vanilla's
     * BFS lists it anyway (the sweep deliberately omits vanilla's
     * section-Y gate, so that direction is reachable).</p>
     *
     * <h2>O7 — the same hole on the axis this sweep never modelled
     * (docs/FARFIELD-WAVES.md, OWNER PLAYTEST OF pre13, item O7)</h2>
     * <p>"minecraft unloads chunks when you fly up that normally you
     * couldnt see due to the circle of fog, but since the lod increases
     * that, you can." <b>Vanilla does not unload anything when you fly
     * up</b>, and that matters because it is what rules out the obvious
     * fix. {@code RotatingSectionStorage} is built with the LEVEL's
     * section-Y span and {@code repositionCenter} recomputes X and Z only
     * (javap: the reposition loop derives {@code i = pos.x() - radius},
     * {@code j = pos.z() - radius} and takes Y as {@code minY + k}), so a
     * pure-Y camera move rewraps nothing and calls no {@code reset()}.
     * The only two callers of {@code RenderSection.reset()} in the whole
     * client are that reposition and {@code releaseAllBuffers}. The
     * geometry is still there.</p>
     *
     * <p>What flying up changes is what vanilla LISTS.
     * {@code SectionOcclusionGraph.runUpdates} switches a section to
     * ray-marched "advanced" culling as soon as any axis, <b>Y
     * included</b>, exceeds
     * {@value #VANILLA_ADVANCED_CULL_SECTIONS} sections (ip 165-230), and
     * that march (ip 653-778) walks from the section toward the camera in
     * {@code CEILED_SECTION_DIAGONAL} steps and gives up the moment it
     * meets a section with no graph node yet. Climb 48 blocks and the
     * whole disc under you is on that path; climb past the top of the
     * world and {@code initializeQueueForFullUpdate} additionally reseeds
     * the entire BFS from the top plane (ip 21-45). Sections the walk does
     * not reach are not in {@code visibleSections} — and
     * {@code LevelExtractor.extract} scans {@code visibleSections} for
     * dirty sections, so they are not even COMPILED
     * (docs/VANILLA-SECTION-BUILD.md §1.5.2).</p>
     *
     * <p>Meshelium inherits it exactly, and only on one path: the BFS-mask
     * draw path reads {@code visibleSections} as its visibility feed, and
     * that is the default whenever occlusion AUTO is below its crossover
     * of render distance {@code DEFAULT_OCCLUSION_AUTO_RD = 48} — i.e. at
     * the owner's 32. So the same climb that hollows out vanilla hollows
     * out Meshelium, vanilla's cylindrical render-distance fog used to
     * bury it, and {@code FarFieldFogMixin} has now moved that wall to the
     * LOD radius. The far field CANNOT be the answer here, and not for
     * cost reasons: the position still has a live near-field owner, so
     * {@link #admitFarSectionLocked}'s {@code isOccupied} pre-flight
     * refuses the shell with {@code ADMIT_CONTESTED} — one owner per
     * position, by construction. Covering it with LOD would mean evicting
     * real geometry to make room for an approximation of itself.</p>
     *
     * <p><b>So it is the same fix H2 already is</b>, on the axis
     * {@code vanillaMayList} was never given: mark the section unlisted
     * and let the mask draw the copy we already own. Applied per section
     * on a {@code camSy - sy} threshold, so nothing near you loses
     * vanilla's list. Deliberately ONE-DIRECTIONAL: a camera BELOW the
     * terrain (caves, mining) is where the BFS cull earns the most and
     * where no report exists, so it keeps vanilla's answer.</p>
     *
     * <h2>P2 — the arm was wrong, and the mask was only ever half the
     * answer (docs/FARFIELD-WAVES.md, "P2 AND P9 ANSWERED")</h2>
     * <p>Two corrections to the block above, both from the owner's pre14
     * report.</p>
     * <p><b>The arm.</b> O7 armed on the CAMERA's own column — nothing of
     * ours resident within {@value #VANILLA_ADVANCED_CULL_SECTIONS}
     * sections below it. Over an ocean the water surface is inside that
     * window, so the arm was OFF for exactly the altitudes at which the
     * water goes missing; flying over any high ground it was off while the
     * sea nine sections below was being culled. And being one boolean over
     * the whole resident set with no hysteresis, every crossing that
     * flipped it moved the entire horizon at once — "disapear and glitch
     * out when you fly around". It is now the camera's height above the
     * resident terrain band ({@link #residentTerrainScanLocked}), with a
     * two-section hysteresis band — keyed since R6 on the band's FLOOR
     * (p10), because vanilla flips each section at its own
     * {@code dsy > 3} and a p90-keyed arm fired only after the whole band
     * below it had already been refused (the R6 window); the per-section
     * discrimination lives in {@link #skipAdvancedRayMarch(double)}'s
     * 64-block floor instead.</p>
     * <p><b>The half this sweep cannot reach.</b> A mask draws geometry we
     * already own. Flying straight UP keeps every mesh (the reposition
     * never reads the camera's Y), which is the case O7 fixed. Flying
     * AROUND at altitude rewraps the storage square, {@code reset()} drops
     * the mesh, and {@code LevelExtractor.extract} then never rebuilds it,
     * because it schedules compiles only for sections in
     * {@code visibleSections}. <b>Nothing is there to mark.</b> That is
     * why {@link #altitudeCullRelaxed} exists and why the same arm now
     * also drives {@code SectionOcclusionGraphAltitudeMixin}: the mask
     * covers the geometry that survived, the mixin stops vanilla refusing
     * to rebuild the geometry that did not.</p>
     *
     * <h2>S2 — the arm was right and the PREDICATE was the Y disjunct of
     * an OR (docs/FARFIELD-WAVES.md, "S2 ANSWERED")</h2>
     * <p>O7, P2 and R6 all keyed their per-section coverage to
     * {@code camSy - sy > }{@value #VANILLA_ADVANCED_CULL_SECTIONS},
     * reading vanilla's advanced-culling flip as a Y test. It is not one.
     * {@code runUpdates} ip 165-230 is a <b>disjunction</b> — the branch
     * targets 225 (true) / 229 (false) put {@code advanced} up when
     * {@code |dsx| > 3} <b>OR</b> {@code |dsy| > 3} <b>OR</b>
     * {@code |dsz| > 3}. At render distance 32 the horizontal disjunct is
     * true for every section outside a 7x7 core <b>at every altitude</b>,
     * so vanilla can ray-march-refuse the whole outer disc from the first
     * section of climb, while the Y-keyed gate here (and the 64-block
     * floor in {@link #skipAdvancedRayMarch}) stay shut until the camera
     * is FOUR sections above. Those 64 blocks are the owner's "brief
     * period where its clear ... until you fly higher", and no amount of
     * re-keying the ARM could reach them: the boundary that mattered was
     * the per-section predicate, and three waves left it alone.</p>
     * <p>The second rule in the loop below is therefore vanilla's own
     * predicate, restricted to look-DOWN and armed on
     * the per-column local horizon ({@link #columnTopSy}) rather than any
     * percentile — see that field for why the statistic had to go. It is
     * deliberately a DRAW-side fix: on a climb nothing is unloaded
     * ({@code RotatingSectionStorage.repositionCenter} takes Y as
     * {@code minY + k}, so a pure-Y move issues no {@code setSectionNode}
     * and no {@code reset()} — re-verified from bytecode this session),
     * so the geometry is already resident and a mask bit costs no
     * compile, no upload and no VRAM. Widening
     * {@link #skipAdvancedRayMarch} to the same predicate would buy the
     * same pixels on the expensive axis and was NOT done;
     * {@link #unlistedBandDrawn} against
     * {@code SectionBuildTap.visibleSectionsListed()} is the reading that
     * says whether it is ever needed.</p>
     *
     * <h2>pre21 — the core, and vanilla's vertical reach
     * (docs/FARFIELD-WAVES.md, "FLY-UP ANSWERED (seventh attempt: the
     * core)")</h2>
     * <p>The owner reproduced the layer at render distance 2, and at rd 2
     * the whole 5x5 disc is inside the 7x7 core the S2 rule excluded, so
     * the rule marked nothing at his setting. What vanilla does inside
     * the core there is not the face graph but a clamp:
     * {@code getRelativeFrom} returns null for any neighbour with
     * {@code |camSy - sy| > viewDistance} (ip 20-44). Three sections up,
     * every section at dsy 3 is unlistable by construction; O7's
     * {@code dsy > 3} drew dsy 4 and below; the one layer between was
     * drawn by nobody, and with the far field on there was a horizon
     * behind it to make the gap obvious. Three edits: the band rule's
     * core exclusion is gone (the per-column verdict applies everywhere
     * in the disc), and O7's gate and the p10 arm key on
     * {@link #vanillaAdjacencyReach} — {@code min(3, rd)} — instead of
     * the constant. The last two are no-ops at every render distance
     * from 3 up.</p>
     *
     * <p><b>Cost, stated plainly.</b> While armed the BFS-mask path gives
     * up vanilla's occlusion culling for everything more than
     * {@value #VANILLA_ADVANCED_CULL_SECTIONS} sections below the camera;
     * per-section FRUSTUM culling is untouched (terrain.task does its own,
     * from the render clip planes) and so is the whole occlusion path,
     * which never read {@code visibleSections}. The set is bounded by what
     * vanilla has actually compiled — sections it never listed were never
     * built and are not resident — and {@link #unlistedAltitudeDrawn} is
     * the exact number, live. Levers:
     * {@code -Dmeshelium.drawUnlistedLive.altitude=false} for this arm
     * alone, and turning Occlusion Culling ON (or render distance ≥ 48)
     * removes the whole class of defect by leaving the BFS feed behind —
     * which is also the cheapest confirmation of the diagnosis, needing no
     * build at all.</p>
     *
     * @param viewRadius vanilla's effective render distance, read by
     *                   {@link #pump} outside the lock; 0 when options are
     *                   not up yet, which disarms the sweep
     * @param cameraSectionY the camera's section Y, read by {@link #pump}
     *                   outside the lock, or {@link Integer#MIN_VALUE}
     *                   when no camera is up (which disarms O7 alone)
     */
    private static void syncUnlistedLiveMaskLocked(int viewRadius, int cameraSectionY) {
        long camera = SectionBuildTap.cameraSectionXZ();
        if (camera == SectionBuildTap.CAMERA_SECTION_UNKNOWN || viewRadius <= 0
                || resident.isEmpty()) {
            // P2: an empty or camera-less store cannot say anything about
            // the terrain's height, and the arm below is PUBLISHED to a
            // mixin that keeps reading it whatever we do. Disarm rather
            // than latch — the failure direction of a stuck-on arm is
            // vanilla listing (and building) more than it needs to.
            altitudeArmLatched = false;
            altitudeCullRelaxed = false;
            columnTopSide = 0; // S6: no census, so no local horizon
            unlistedSweepLocalHorizon = false;
            unlistedSweepHighCamera = false;
            unlistedSweepEnabled = false;
            return;
        }
        if (camera == unlistedSweepCamera && viewRadius == unlistedSweepRadius
                && cameraSectionY == unlistedSweepCameraY) {
            return; // same camera section, same slider: same answer
        }
        unlistedSweepCamera = camera;
        unlistedSweepRadius = viewRadius;
        unlistedSweepCameraY = cameraSectionY;
        // Read AFTER the arm check, so the property costs one lookup per
        // camera crossing rather than one per pump inside the lock, and a
        // harness flip lands on the next crossing (which is also when the
        // marks it governs would next move).
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY_DRAW_UNLISTED_LIVE, "true"))) {
            // A/B off: leave every bit exactly where the wave-11 paths put
            // it. Nothing to undo — this sweep is the only writer of an
            // unlisted-live bit, so a session that starts with the lever
            // down never has one, and a mid-session flip down simply stops
            // adding more (the standing ones clear with their own slots,
            // through remove/addOrReplace, as they always did).
            //
            // K2/L10 reads the same way for the translucent flag, with one
            // difference worth naming: unlistedDrawn does NOT clear with
            // the slot, it dies with the Resident. A mid-session flip down
            // therefore leaves a standing flag on any live entry that was
            // outside the disc at the time and has since come back inside,
            // so its water is blended by the far-first pre-pass instead of
            // in visibleSections order. Over-draw in the wrong order, never
            // a hole, and only on the A/B lever's downward edge.
            unlistedLiveDrawn = 0;
            unlistedAltitudeDrawn = 0;
            unlistedBandDrawn = 0;
            unlistedBandCoreDrawn = 0;
            unlistedHorizonEntries = 0;
            altitudeArmLatched = false;
            altitudeCullRelaxed = false; // the mixin follows the lever too
            columnTopSide = 0;
            unlistedSweepLocalHorizon = false;
            unlistedSweepHighCamera = false;
            unlistedSweepEnabled = false;
            return;
        }
        int camX = (int) (camera >> 32);
        int camZ = (int) camera;
        // P2: THE ARM, REBUILT. O7 asked "is there anything of ours within
        // three sections below the camera's OWN column", four O(1) region
        // lookups. That probe is defeated by the very geometry it is meant
        // to compensate for, in both of the owner's scenes:
        //
        //   * over ocean at y 80-110 the water surface (section Y 3) sits
        //     inside the probe's four-section window, so the arm is OFF
        //     for exactly the altitudes at which the water goes missing;
        //   * flying at y 200 over a peak at y 190 the arm is OFF while
        //     the ocean thirty chunks away is NINE sections below — the
        //     question is per-SECTION and the probe answered it per-CAMERA.
        //
        // And because the answer is one boolean applied to every resident,
        // a crossing that flips it adds or removes the whole horizon at
        // once: "disapear and glitch out when you fly around".
        //
        // The replacement asks how high the camera is above the TERRAIN,
        // from the resident set itself: a 64-bucket section-Y histogram
        // (one extra pass of the same map this sweep already walks, ~25k
        // int reads at rd 32, once per camera-section crossing) and its
        // percentiles. Hysteresis on top, so the two edges are 32 blocks
        // apart and no ordinary flight path chatters across them.
        //
        // R6: THE ARM, RE-KEYED (docs/FARFIELD-WAVES.md, "R6 AND R7
        // ANSWERED"). pre15 keyed it on the p90 — the TOP of the terrain
        // band — and the owner's pre17 report is the arithmetic of why
        // that is a window, not a fix: vanilla flips each section to the
        // ray-marched arm at ITS OWN |dsy| > 3 (runUpdates ip 165-230,
        // per NODE), i.e. at camSy = sy + 4 counted from that section,
        // so every resident section BELOW the p90 flipped before the arm
        // fired. Over a mixed coast disc the inland hills held p90 high
        // while the ocean surface under the camera was already being
        // refused — the histogram lagged the terrain composition — and
        // even over uniform ocean the seafloor flipped three sections
        // before the arm. "You still have that brief period where its
        // clear ... until you fly higher", quantified: the window is
        // 16*(p90 - sy) blocks of climb per section.
        //
        // The arm now fires when the camera clears the p10 + 3 — the
        // first moment vanilla can Y-flip any meaningful tenth of the
        // resident set — and the per-NODE discrimination moved into the
        // mixin's verdict itself (skipAdvancedRayMarch's 64-block floor,
        // vanilla's own per-node Y test applied to the march's own
        // operands), so arming early no longer surrenders the march
        // anywhere near camera height. p10 rather than the minimum for
        // the same outlier argument as p90-over-max; the failure
        // direction of a too-low p10 is vanilla listing terrain we did
        // not need, which is the safe direction. The mask's own altitude
        // marks below were per-section gated all along
        // (cameraSectionY - r.sy > VANILLA_ADVANCED_CULL_SECTIONS), so
        // the earlier arm does not widen them past sections genuinely
        // below the camera.
        boolean altitudeArmEnabled = cameraSectionY != Integer.MIN_VALUE
                && Boolean.parseBoolean(
                        System.getProperty(PROPERTY_DRAW_UNLISTED_ALTITUDE, "true"));
        long band = altitudeArmEnabled
                ? residentTerrainScanLocked(camX, camZ, viewRadius)
                : RESIDENT_SY_BAND_EMPTY;
        int terrainTopSy = Integer.MIN_VALUE;
        int terrainLowSy = Integer.MIN_VALUE;
        if (band != RESIDENT_SY_BAND_EMPTY) {
            terrainTopSy = (int) (band >> 32);
            terrainLowSy = (int) band;
        }
        boolean highCamera = altitudeArmLatched;
        // pre21: the arm's threshold is vanilla's VERTICAL REACH at this
        // render distance, not the constant 3. runUpdates' getRelativeFrom
        // (ip 20-44) refuses any neighbour with |camSy - sy| > viewDistance
        // outright, so at rd 2 vanilla starts losing the lowest decile at
        // dsy 3, one section before the ray-marched arm's own flip. Equal
        // to the old threshold at every rd >= 3 (see vanillaAdjacencyReach).
        int reach = vanillaAdjacencyReach(viewRadius);
        if (terrainLowSy == Integer.MIN_VALUE) {
            highCamera = false;
        } else {
            int above = cameraSectionY - terrainLowSy;
            if (above > reach) {
                highCamera = true;
            } else if (above <= reach - ALTITUDE_ARM_HYSTERESIS) {
                highCamera = false;
            }
        }
        altitudeArmLatched = highCamera;
        // S6: S2's SECOND ARM IS GONE (docs/FARFIELD-WAVES.md, "FLY-UP
        // ANSWERED"). It was `camSy > p90` over the whole disc, latched
        // with two sections of hysteresis, and it is the fifth attempt's
        // residual: p90 is a DISC-WIDE statistic, so over an ocean beside
        // a mountain it tracks the mountain while the camera flies over
        // the water and the arm stays DOWN over exactly the terrain
        // vanilla has already begun refusing. S2's own frame table could
        // not see that, because it was desk-checked on the uniform ocean,
        // where p10 + 3 and p90 coincide.
        //
        // Nothing replaces it as a statistic. The wide rule below asks the
        // question per SECTION instead, against
        // columnTopSyLocked(dx, dz) — the top of the resident terrain in
        // that section's OWN column, filled by the scan above in the same
        // pass that already computed the percentiles. See the
        // {@link #columnTopSy} javadoc for why that needs no hysteresis
        // and why it keeps ground play bit-identical.
        //
        // p90 (terrainTopSy) survives as the DIAGNOSTIC it has been since
        // R6: nothing keys on it any more.
        boolean localHorizon = altitudeArmEnabled && columnTopSide > 0;
        unlistedTerrainTopSy = terrainTopSy;
        unlistedTerrainLowSy = terrainLowSy;
        // P2: the same verdict arms the BFS relaxation in
        // SectionOcclusionGraphAltitudeMixin. The mask below can only draw
        // geometry we ALREADY OWN; the mixin is what stops vanilla
        // refusing to BUILD it, which is the half O7 could not reach.
        altitudeCullRelaxed = highCamera;
        if (highCamera) {
            altitudeArmSweeps++;
        }
        // S6: the sweep's inputs are CACHED, because the sweep is no longer
        // the only writer of a mark — drainPendingUploadsLocked replays the
        // same verdict for every section it binds between two crossings
        // (see applyUnlistedVerdictLocked and the block on it). Everything
        // below reads these, so the two writers cannot drift.
        unlistedSweepHighCamera = highCamera;
        unlistedSweepLocalHorizon = localHorizon;
        unlistedSweepEnabled = true;
        int drawn = 0;
        int altitude = 0;
        int bandOnly = 0;
        int bandCore = 0;
        int horizonEntries = 0;
        boolean changed = false;
        for (Resident r : resident.values()) {
            if (!r.ownsSlot) {
                // Its slot belongs to a newer copy; that owner's own bit is
                // the one the drawer reads, and setRetained would refuse
                // this write anyway. Skip the probe.
                continue;
            }
            if (localHorizon && cameraSectionY > columnTopSyLocked(r.sx - camX, r.sz - camZ)) {
                horizonEntries++;
            }
            int verdict = unlistedVerdictLocked(r, camX, camZ, cameraSectionY,
                    viewRadius, highCamera, localHorizon);
            boolean unlisted = verdict != VERDICT_LISTED;
            if (unlisted) {
                drawn++;
            }
            if (verdict == VERDICT_ALTITUDE || verdict == VERDICT_BAND) {
                altitude++;
            }
            if (verdict == VERDICT_BAND) {
                bandOnly++;
                if (Math.abs(r.sx - camX) <= VANILLA_ADVANCED_CULL_SECTIONS
                        && Math.abs(r.sz - camZ) <= VANILLA_ADVANCED_CULL_SECTIONS) {
                    bandCore++; // pre21: the core's share, priced separately
                }
            }
            if (regionStore.setRetained(r.regionKey, r.posKey, r, unlisted)) {
                changed = true;
                unlistedMaskFlips++;
            }
            // K2/L10, the translucent half. The mask above feeds the
            // OPAQUE BFS path only; water rides snapshot [19] instead, so
            // the same verdict has to be written into the entry — and
            // LOGGED, because the incremental snapshot only recomputes
            // slots that appear in the change log and the bump below
            // moves drawEpoch alone. Without the log the back buffer
            // would publish a new epoch carrying the OLD [19] and the fix
            // would land only when something else happened to log the
            // slot (the pre8 mask fix is safe from this because masks are
            // re-captured whole at every publish; per-entry ints are not).
            if (r.unlistedDrawn != unlisted) {
                r.unlistedDrawn = unlisted;
                logSlotWriteLocked(r.snapshotSlot);
                changed = true;
                unlistedTransFlips++;
            }
        }
        unlistedLiveDrawn = drawn;
        unlistedAltitudeDrawn = altitude;
        unlistedBandDrawn = bandOnly;
        unlistedBandCoreDrawn = bandCore;
        unlistedHorizonEntries = horizonEntries;
        if (changed) {
            // The masks are published by drawSnapshot, which returns null
            // while the epoch is unchanged — without this the drawer would
            // keep last frame's mask and the fix would land a frame late,
            // or not at all on a stationary camera.
            drawEpoch++;
        }
    }

    /** {@link #unlistedVerdictLocked}: vanilla's list is enough. */
    private static final int VERDICT_LISTED = 0;
    /** H2: outside vanilla's compile disc — the corner lobes. */
    private static final int VERDICT_OUTSIDE_DISC = 1;
    /** O7: inside the disc, four or more sections below the camera. */
    private static final int VERDICT_ALTITUDE = 2;
    /** S2/S6: inside the disc, in the band, past the local horizon. */
    private static final int VERDICT_BAND = 3;

    /**
     * THE one per-section rule, in one place, for the two writers that
     * apply it: {@link #syncUnlistedLiveMaskLocked} (every entry, once per
     * camera-section crossing) and {@link #applyUnlistedVerdictLocked}
     * (one entry, at upload bind, between crossings).
     *
     * <p>It was inlined in the sweep until S6. Two writers of one verdict
     * is exactly the shape that drifts, and the second writer exists
     * because of a hole the sweep alone cannot close — see
     * {@link #applyUnlistedVerdictLocked}.</p>
     *
     * @return one of {@link #VERDICT_LISTED}, {@link #VERDICT_OUTSIDE_DISC},
     *         {@link #VERDICT_ALTITUDE}, {@link #VERDICT_BAND}
     */
    private static int unlistedVerdictLocked(Resident r, int camX, int camZ,
            int cameraSectionY, int viewRadius, boolean highCamera, boolean localHorizon) {
        int dx = r.sx - camX;
        int dz = r.sz - camZ;
        if (!vanillaMayList(dx, dz, viewRadius)) {
            return VERDICT_OUTSIDE_DISC;
        }
        int dsy = cameraSectionY - r.sy;
        if (highCamera && dsy > vanillaAdjacencyReach(viewRadius)) {
            // O7. Inside vanilla's disc, so vanilla MAY list it — and from
            // up here it very often does not, because the section is past
            // the advanced-culling floor and the ray back to the camera has
            // to cross sections the BFS has not visited. Fail-open exactly
            // as the H2 arm is: the bit only ever adds a draw.
            //
            // pre21: the gate is min(3, rd), not 3. At render distance 2
            // vanilla CANNOT list a section three below the camera at all -
            // getRelativeFrom's vertical clamp (ip 20-44), not the ray
            // march - and a gate of 3 left exactly that one layer to
            // nobody while this rule drew the layers beneath it: the
            // owner's "layer of no rendered chunks", one section thick.
            return VERDICT_ALTITUDE;
        }
        // S2 — THE BAND THE Y DISJUNCT NEVER COVERED, with S6's key.
        //
        // The gate above is vanilla's Y test. Vanilla's actual test is a
        // DISJUNCTION: runUpdates ip 165-230 sets `advanced` when
        // |dsx| > 3 OR |dsy| > 3 OR |dsz| > 3 (re-read from the 26.2 jar;
        // the branch targets 225/229 make the OR explicit). At render
        // distance 32 the HORIZONTAL disjunct is true for every section
        // outside a 7x7 core, at every altitude — so vanilla has the whole
        // outer disc on its ray-marched arm and can refuse it from the
        // very first section of climb, while both pre18 gates (this mask's
        // `camSy - sy > 3` and skipAdvancedRayMarch's 64-block floor) stay
        // shut until the camera is FOUR sections above. That 64-block
        // window is the owner's "brief period where its clear ... until
        // you fly higher".
        //
        // The rule is vanilla's own predicate, restricted to look-DOWN
        // (dsy >= 0) to keep O7's one-directional discipline — a camera
        // below the terrain keeps vanilla's answer, caves and mining are
        // untouched.
        //
        // S6 REPLACED THE ARM. S2 gated this on `camSy > p90` over the
        // whole disc, and the owner's fifth report is what a disc-wide
        // statistic does over mixed terrain: p90 tracks the mountain while
        // the camera flies over the water, so the gate stays shut over
        // exactly the water vanilla has already refused. The question is
        // per-section, so it is now asked per section — against the top of
        // the resident terrain in THIS section's own column. Over a
        // uniform scene that is the p90 and the marked set is unchanged;
        // over a mixed one the low columns mark and the high ones do not,
        // which is both the fix and the cost guard (a column whose terrain
        // reaches the camera is a column that genuinely occludes, and
        // vanilla's BFS answer for it is worth keeping).
        //
        // OWNERSHIP, stated as code rather than left to the container.
        // `resident` may hold LEDGER_NEAR_LIVE and nothing else
        // (auditLedgerLocked is the contract; onMeshReleased removes from
        // the map before it can retain, park or clear), so this test can
        // never fail — which is exactly why it is written down. Altitude
        // must not change ownership semantics: a NEAR_HELD copy or an
        // AWAITING_SUCCESSOR park does not become drawable because the
        // camera climbed, and the wave-11 A1 leg is entitled to prove
        // that. The drawer reads the split through
        // DrawSnapshot.unlistedMasks, built from the same guarantee.
        //
        // pre21 (the seventh attempt: THE CORE). S2 restricted this rule
        // to `|dx| > 3 || |dz| > 3` - "outside the 7x7 core, where vanilla
        // uses the ray march" - and the pre20 leg excluded the same core
        // from its expectation set. Both were wrong for the same reason:
        // the core's exclusion was keyed to WHICH vanilla test runs there,
        // not to whether vanilla's answer there is one worth keeping.
        //
        //   * At render distance 2 the whole 5x5 disc is inside the core,
        //     so the rule marked NOTHING at the owner's setting, and
        //     vanilla's own reach there is not the face graph but a hard
        //     clamp: getRelativeFrom returns null for any neighbour with
        //     |camSy - sy| > viewDistance (javap, 26.2, ip 20-44). Three
        //     sections up, every section at dsy 3 is unreachable by
        //     construction; O7 above drew dsy >= 4; the one layer between
        //     was drawn by nobody. Measured at rd 2: absentCore 9, 12, 4,
        //     5, 8, 4, 2 as the camera climbed - each rung exactly the
        //     resident sections at sy = camSy - 3.
        //   * At every other render distance the only thing the exclusion
        //     preserved was vanilla's FACE-GRAPH refusal of a section in a
        //     column wholly below the camera - the same refusal this rule
        //     has overridden at |dx| = 4 since S2. One rule, one edge.
        //
        // So the per-column verdict now applies everywhere inside the
        // disc. Inside the core it marks mostly sections vanilla lists
        // anyway (no ray march runs there), so the merged word is
        // unchanged for them; the DRAWN delta is the face-graph refusals,
        // counted in unlistedBandCoreDrawn, at most 49 columns' worth.
        if (localHorizon && dsy >= 0
                && r.ledgerState == LEDGER_NEAR_LIVE
                && cameraSectionY > columnTopSyLocked(dx, dz)) {
            return VERDICT_BAND;
        }
        return VERDICT_LISTED;
    }

    /**
     * pre21: how far BELOW (or above) its own section vanilla's BFS can
     * reach at all, in sections — {@code min(3, viewDistance)}.
     *
     * <p>Two limits in {@code SectionOcclusionGraph} bound the adjacency
     * walk vertically, and the smaller one wins. {@code runUpdates} puts a
     * node on the ray-marched arm at {@code |dsy| > 3} (ip 165-230, the Y
     * disjunct); {@code getRelativeFrom} refuses the neighbour outright at
     * {@code |camSy - sy| > viewArea.getViewDistance()} (ip 20-44, javap
     * of the 26.2 merged jar). At every render distance from 3 up the
     * clamp sits at or beyond the arm and this is the constant 3 the O7
     * gate and the p10 arm always used. At render distance 2 the clamp is
     * the binding limit: nothing three sections below the camera can be
     * listed, by construction rather than by occlusion, and any rule keyed
     * to 3 there leaves exactly one layer uncovered. The residency's own
     * {@code vanillaMayList} javadoc had dismissed this clamp as mattering
     * "solely for a camera far above or below the build limit"; it
     * matters three sections above the sea at rd 2.</p>
     */
    private static int vanillaAdjacencyReach(int viewRadius) {
        return viewRadius > 0
                ? Math.min(VANILLA_ADVANCED_CULL_SECTIONS, viewRadius)
                : VANILLA_ADVANCED_CULL_SECTIONS;
    }

    /**
     * S6 — THE SECOND HOLE, and it survives every fix to the arm.
     *
     * <p>Every mark this class makes lives in one of two places, and a
     * fresh upload destroys BOTH of them at the position it lands on:
     * {@code RegionStore.addOrReplace} clears the retained-mask bit
     * unconditionally (wave-11: a live upload supersedes a retained copy,
     * and the bit must not outlive it), and the new {@link Resident} is
     * born with {@code unlistedDrawn == false}. The only writer that could
     * put them back was {@link #syncUnlistedLiveMaskLocked}, which
     * <b>early-returns on an unchanged {@code (camXZ, viewRadius, camSy)}
     * and runs BEFORE the upload drain in the same pump</b>. So a section
     * bound between two camera-section crossings has no mark at all until
     * the camera next crosses one — and on the climb the owner reports,
     * the sections streaming in behind the BFS are exactly the ones that
     * need it. A hover after a climb never crosses another boundary and
     * the hole simply stands.</p>
     *
     * <p>The fix is not to re-sweep (that is O(resident) inside the pump's
     * lock, per pump, against a 120-180 fps floor). It is to give the ONE
     * new entry its verdict at bind time, O(1), from the sweep's cached
     * inputs — the same {@link #unlistedVerdictLocked} the sweep runs, so
     * the two writers cannot disagree.</p>
     *
     * <p><b>Only ever sets.</b> {@code addOrReplace} has already cleared
     * the bit, so a {@code VERDICT_LISTED} entry is already correct and
     * this does nothing; there is no path here that clears a mark, and
     * therefore none that can turn a drawn section into a hole. The
     * ownership invariant is the sweep's, unchanged: the caller has just
     * put {@code r} into {@link #resident} with {@code LEDGER_NEAR_LIVE},
     * {@code addOrReplace} has bound it to the slot, and
     * {@code setRetained} re-checks the owner anyway.</p>
     *
     * <p>The caller logs {@code r.snapshotSlot} immediately after this
     * returns (it must, for the entry itself), so this deliberately does
     * NOT log — a second record per upload would burn change-log capacity
     * under chunk streaming for a slot that is about to be written.</p>
     */
    private static void applyUnlistedVerdictLocked(Resident r) {
        if (!unlistedSweepEnabled
                || unlistedSweepCamera == SectionBuildTap.CAMERA_SECTION_UNKNOWN
                || unlistedSweepRadius <= 0
                || unlistedSweepCameraY == Integer.MIN_VALUE
                || !r.ownsSlot) {
            return;
        }
        int camX = (int) (unlistedSweepCamera >> 32);
        int camZ = (int) unlistedSweepCamera;
        // The census the local horizon reads is the sweep's, and this
        // section is newer than it. Fold it in first, so a column whose top
        // this very upload raises above the camera is not marked on the
        // strength of a stale, lower top.
        noteColumnTopLocked(r.sx - camX, r.sz - camZ, r.sy);
        int verdict = unlistedVerdictLocked(r, camX, camZ, unlistedSweepCameraY,
                unlistedSweepRadius, unlistedSweepHighCamera, unlistedSweepLocalHorizon);
        if (verdict == VERDICT_LISTED) {
            return;
        }
        if (regionStore.setRetained(r.regionKey, r.posKey, r, true)) {
            unlistedMaskFlips++;
        }
        if (!r.unlistedDrawn) {
            r.unlistedDrawn = true;
            unlistedTransFlips++;
        }
        unlistedBindMarks++;
        if (verdict == VERDICT_BAND) {
            unlistedBindBandMarks++;
        }
    }

    /**
     * S6: fold one section into {@link #columnTopSy}. No-op when the map
     * is unfilled or the column is outside it — both mean "unknown", and
     * unknown is the fail-open answer the rule already handles.
     */
    private static void noteColumnTopLocked(int dx, int dz, int sy) {
        int m = columnTopMargin;
        if (columnTopSide <= 0 || dx < -m || dx > m || dz < -m || dz > m) {
            return;
        }
        int i = (dx + m) * columnTopSide + (dz + m);
        if (sy > columnTopSy[i]) {
            columnTopSy[i] = sy;
        }
    }

    /**
     * S6: the top resident section Y in the column {@code (dx, dz)}
     * sections from the camera, or {@link Integer#MIN_VALUE} for "nothing
     * resident there / outside the map".
     *
     * <p>{@code MIN_VALUE} is the FAIL-OPEN answer: {@code camSy > MIN_VALUE}
     * is true, so an unknown column marks. That direction only ever adds a
     * draw of geometry already in the arena, which is the same failure
     * direction every other bit in this sweep has. It is also unreachable
     * from the band rule, which runs only for sections inside vanilla's
     * disc and therefore inside the map.</p>
     */
    private static int columnTopSyLocked(int dx, int dz) {
        int m = columnTopMargin;
        if (columnTopSide <= 0 || dx < -m || dx > m || dz < -m || dz > m) {
            return Integer.MIN_VALUE;
        }
        return columnTopSy[(dx + m) * columnTopSide + (dz + m)];
    }

    /**
     * R6: marker for {@link #residentTerrainScanLocked} — nothing owns a
     * slot, so there is no terrain band to speak of. Cannot collide with
     * a real packed band: both halves of a real answer are clamped
     * section Ys in {@code [SY_HISTOGRAM_MIN,
     * SY_HISTOGRAM_MIN + SY_HISTOGRAM_SIZE)}.
     */
    private static final long RESIDENT_SY_BAND_EMPTY = Long.MIN_VALUE;

    /**
     * P2/R6: the 10th- and 90th-percentile section Y of the slot-owning
     * resident set — "where is the terrain around here", from the only
     * census of it this class has. One pass over the same map
     * {@link #syncUnlistedLiveMaskLocked} is about to walk, reading one
     * int per entry into a fixed 64-bucket histogram; no allocation, no
     * vanilla call, LOCK-held, once per camera-section crossing. The two
     * percentiles come out of the same fill: p90 is the band's top (a
     * diagnostic since R6), p10 is its floor and the arm's input.
     *
     * <p><b>Why percentiles and not the extremes or the camera's own
     * column.</b> The maximum is one tower, one tree or one mountain peak
     * and would disarm the whole fix for a player cruising above a
     * skyline; the minimum is one mined shaft or one ravine and would arm
     * it for a player who never left the ground. The camera's column is
     * what O7 used and is the arm the owner's pre14 report refutes twice
     * over (see the block in the sweep). The distribution is honest
     * because the resident set IS what vanilla compiled inside the disc —
     * sections it never listed were never built and are not in here.</p>
     *
     * <p>{@code !ownsSlot} entries are skipped for the same reason the
     * sweep skips them: a newer copy owns their slot and answers for the
     * position. Retained and far entries are not in {@link #resident} at
     * all, which is correct — a far shell is an approximation of terrain
     * beyond vanilla's disc and has no business setting the arm for
     * terrain inside it.</p>
     *
     * <h2>S6: the same pass now also fills the LOCAL HORIZON</h2>
     * <p>{@link #columnTopSy} — the top resident section Y per column,
     * camera-relative — comes out of this identical walk, because the S2
     * band rule stopped keying on any percentile and started asking the
     * question per section. See that field for why.</p>
     *
     * <p><b>What it adds, priced against the pass it joins.</b> The
     * histogram pass was: one subtract, two compares and one array
     * increment per resident entry, plus a 64-int fill. It is now that,
     * plus two subtracts, four bounds compares, one multiply-add, one
     * compare and (at most) one array store per entry — call it three to
     * four times the per-entry work of a loop whose per-entry work was
     * already trivial — plus an {@code Arrays.fill} of
     * {@code (2 * (rd + 2) + 1)^2} ints: <b>4,761 at rd 32</b>, 17,689 at
     * rd 64, both of which are smaller than the number of resident entries
     * the loop then walks (~5k-25k at rd 32). It is a fixed 19 KiB scratch
     * array at rd 32, reused, never per-entry allocation, and it runs where
     * the histogram already ran — <b>once per camera-section crossing,
     * under the lock this sweep already holds, not per frame</b>: a handful
     * of times a second at walking pace, about two at elytra cruise. The
     * per-section lookup it buys the sweep is one bounds check and one
     * array read, against the single boolean compare the p90 arm cost.
     * That is the whole price of retiring the statistic.</p>
     *
     * @param camX       camera section X, so the map is camera-relative and
     *                   the sweep's {@code dx} indexes it directly
     * @param camZ       camera section Z
     * @param viewRadius vanilla's effective render distance, which bounds
     *                   the map (the section SQUARE is Chebyshev
     *                   {@code viewRadius + 1}; the margin adds one more)
     * @return {@code (p90 << 32) | (p10 & 0xFFFFFFFF)}, or
     *         {@link #RESIDENT_SY_BAND_EMPTY} when nothing owns a slot
     */
    private static long residentTerrainScanLocked(int camX, int camZ, int viewRadius) {
        java.util.Arrays.fill(syHistogram, 0);
        int margin = Math.min(COLUMN_TOP_MAX_MARGIN, Math.max(0, viewRadius) + 2);
        int side = 2 * margin + 1;
        if (columnTopSide != side || columnTopSy.length != side * side) {
            columnTopSy = new int[side * side];
            columnTopSide = side;
        }
        columnTopMargin = margin;
        java.util.Arrays.fill(columnTopSy, Integer.MIN_VALUE);
        int total = 0;
        for (Resident r : resident.values()) {
            if (!r.ownsSlot) {
                continue;
            }
            int b = r.sy - SY_HISTOGRAM_MIN;
            if (b < 0) {
                b = 0;
            } else if (b >= SY_HISTOGRAM_SIZE) {
                b = SY_HISTOGRAM_SIZE - 1;
            }
            syHistogram[b]++;
            noteColumnTopLocked(r.sx - camX, r.sz - camZ, r.sy);
            total++;
        }
        if (total == 0) {
            columnTopSide = 0; // nothing censused: every lookup answers "unknown"
            return RESIDENT_SY_BAND_EMPTY;
        }
        int lowTarget = total / 10; // floor-ish 10th percentile, counting up
        if (lowTarget == 0) {
            lowTarget = 1;
        }
        int topTarget = total - total / 10; // ceil-ish 90th, counting up
        int lowSy = SY_HISTOGRAM_SIZE - 1 + SY_HISTOGRAM_MIN;
        int topSy = SY_HISTOGRAM_SIZE - 1 + SY_HISTOGRAM_MIN;
        boolean lowFound = false;
        int seen = 0;
        for (int b = 0; b < SY_HISTOGRAM_SIZE; b++) {
            seen += syHistogram[b];
            if (!lowFound && seen >= lowTarget) {
                lowSy = b + SY_HISTOGRAM_MIN;
                lowFound = true;
            }
            if (seen >= topTarget) {
                topSy = b + SY_HISTOGRAM_MIN;
                break;
            }
        }
        return ((long) topSy << 32) | (lowSy & 0xFFFFFFFFL);
    }

    /**
     * P2: should vanilla's ray march be skipped for the neighbour it is
     * about to test? Called from
     * {@code SectionOcclusionGraphAltitudeMixin} inside
     * {@code SectionOcclusionGraph.runUpdates} — <b>the render thread for
     * a partial update and {@code Util.backgroundExecutor()} for a full
     * one</b>, so this method takes no lock and touches nothing but two
     * statics.
     *
     * <h2>Why this exists, when O7 already draws the missing sections</h2>
     * <p>O7's mask can only draw geometry Meshelium ALREADY OWNS, and the
     * owner's report has two halves with two different answers:</p>
     * <ul>
     *   <li>fly straight UP — nothing is unloaded and nothing is rewrapped
     *       ({@code repositionCenter} never reads the camera's Y), so the
     *       meshes are all still there and a mask really can put them back
     *       on screen. That is the half O7 fixed;</li>
     *   <li>fly AROUND at altitude — the storage square rewraps,
     *       {@code RenderSection.reset()} drops the mesh (and, through
     *       {@code releaseSectionMesh}, our copy with it), and the section
     *       is now UNCOMPILED. {@code LevelExtractor.extract} schedules
     *       compiles only for sections in {@code visibleSections}
     *       (docs/VANILLA-SECTION-BUILD.md §1.5.2), so while the BFS is
     *       collapsed it is never rebuilt. <b>There is no geometry for any
     *       mask to draw.</b> That is "disapear and glitch out when you
     *       fly around and not re-appear", and no amount of drawing-side
     *       work can close it.</li>
     * </ul>
     *
     * <h2>What exactly is suppressed</h2>
     * <p>{@code runUpdates}' per-neighbour gate is three tests, each
     * separately guarded by {@code smartCull} (bytecode, 26.2 merged jar):
     * the came-from test (ip 279-297), the {@code facesCanSeeEachother}
     * source-direction test (ip 300-383), and the ray march (ip 386-783),
     * which is entered only when {@code advanced} — local 18, stored once
     * at ip 230 from the polled NODE's own section:
     * {@code |dx|,|dy|,|dz| > MINIMUM_ADVANCED_CULLING_
     * SECTION_DISTANCE} against the camera's section (ip 165-230) — is
     * also true. This turns off <b>only the march, and (since R6) only
     * for nodes the camera is looking DOWN at</b>: the caller passes the
     * camera's height above the march's start corner, and the verdict
     * requires {@link #MARCH_SKIP_MIN_CAMERA_ABOVE_BLOCKS} of it —
     * vanilla's own per-node Y flip, applied to the march's own operands.
     * The face graph keeps culling solid interiors and caves exactly as
     * it does within three sections of the camera, marches at or near
     * camera height run bit-identical to vanilla even while armed, and
     * what goes is the extra requirement that an unbroken chain of
     * already-visited sections exist along the ray home for terrain far
     * BELOW the camera — which from altitude is precisely the
     * conservative approximation that fails.</p>
     *
     * <p>The march is a {@code while (point.distanceSquared(camera) >
     * 3600.0)} walk (ip 656-677) whose {@code flag2} starts TRUE at ip 653
     * and is only ever cleared inside the body. So the whole suppression
     * is "make the loop condition false on the first evaluation", and the
     * mixin does exactly that by redirecting the ONE
     * {@code org.joml.Vector3d.distanceSquared(DDD)D} call in the class
     * (ip 670) to return 0. No local index, no ordinal, one unambiguous
     * descriptor — chosen over a {@code @ModifyVariable} on local 18
     * because <b>the merged jar carries no LocalVariableTable</b>
     * (javap -l: the method prints with no table at all), so a boolean
     * local there is indistinguishable from an int and the binding would
     * be a coin-flip. The point steps TOWARD the camera (ip 680-687), so
     * the height difference only shrinks along a march: the verdict fires
     * on the first evaluation or never, and a march it declines runs
     * unchanged to vanilla's own answer.</p>
     *
     * <h2>Cost, stated plainly</h2>
     * <p>While armed, vanilla lists — and therefore compiles and we draw —
     * the face-graph-reachable set instead of the ray-marched subset of
     * it, for nodes more than {@value #VANILLA_ADVANCED_CULL_SECTIONS}
     * sections below the camera only. That is more sections built and
     * more drawn, bounded by the frustum and by vanilla's own compile
     * quota, and it is the price of the terrain being there at all.
     * {@code SectionBuildTap.visibleSectionsListed()} is the whole cost
     * and the whole proof in one number. Levers:
     * {@code -Dmeshelium.drawUnlistedLive.altitude=false} disarms this and
     * O7's mask arm together (they share
     * {@link #altitudeCullRelaxed}); the redirect itself is
     * {@code require = 0}, so if the target ever moves it simply stops
     * applying — which is why {@link #bfsAdvancedCullRelaxed} has to be
     * reported beside {@link #altitudeArmSweeps}.</p>
     *
     * <h2>S2's correction to the paragraph above, kept because the error
     * cost three waves</h2>
     * <p>The 64-block floor ({@link #MARCH_SKIP_MIN_CAMERA_ABOVE_BLOCKS})
     * is exactly vanilla's Y <i>disjunct</i>, and R6's note calling it "the very
     * condition {@code runUpdates} tests" is wrong: the test is
     * {@code |dsx| > 3 || |dsy| > 3 || |dsz| > 3} (ip 165-230; the branch
     * targets 225/229 make the OR explicit). At rd 32 the horizontal
     * disjunct alone has the whole outer disc on the marched arm at every
     * altitude, so vanilla can refuse a section 64 blocks before this
     * floor opens for it. That window is S2, and S2 closes it on the DRAW
     * side ({@code syncUnlistedLiveMaskLocked}'s second rule) rather than
     * here, because a climb unloads nothing and the geometry is already
     * resident — marking is free, listing is not. This floor is therefore
     * deliberately UNCHANGED by S2: widening it would double the listed
     * set (R6's own ceiling: ~7,000-9,500 vs ~3,000-5,000) and buy the
     * same pixels for compiles, uploads and VRAM. The reading that would
     * justify paying it is {@link #unlistedBandDrawn} high while the
     * picture still holes.</p>
     *
     * @param cameraAboveBlocks the camera's Y minus the march's start
     *        corner's Y, both taken from the redirected call's own
     *        operands — negative when the camera is below the node,
     *        which never skips (caves keep vanilla's answer)
     * @return true to make the march's loop condition false immediately,
     *         i.e. accept the neighbour without walking the ray home
     */
    public static boolean skipAdvancedRayMarch(double cameraAboveBlocks) {
        if (!altitudeCullRelaxed) {
            return false;
        }
        if (cameraAboveBlocks < MARCH_SKIP_MIN_CAMERA_ABOVE_BLOCKS) {
            return false; // R6: not a look-down march; vanilla's answer stands
        }
        bfsAdvancedCullRelaxed++; // racy by design; see the field
        return true;
    }

    /**
     * P2 probe: the 90th-percentile resident section Y as of the last
     * sweep — the terrain band's TOP. A diagnostic since R6 (the arm keys
     * on {@link #unlistedTerrainLowSy()}); read the two together to see
     * the band the histogram measured.
     */
    public static int unlistedTerrainTopSy() {
        synchronized (LOCK) {
            return unlistedTerrainTopSy;
        }
    }

    /**
     * R6 probe: the arm's input — the 10th-percentile resident section Y
     * as of the last sweep, the terrain band's FLOOR. Read it beside the
     * camera's own section Y: the arm is up when the difference exceeds
     * {@value #VANILLA_ADVANCED_CULL_SECTIONS} (minus the hysteresis on
     * the way back down).
     */
    public static int unlistedTerrainLowSy() {
        synchronized (LOCK) {
            return unlistedTerrainLowSy;
        }
    }

    /** P2 probe: is the altitude relaxation armed right now? */
    public static boolean altitudeCullArmed() {
        return altitudeCullRelaxed;
    }

    /**
     * P2 probe: ray marches {@link #skipAdvancedRayMarch}
     * suppressed. <b>Nonzero is the proof the mixin applied at all.</b>
     * Armed sweeps climbing with this at zero means the injection did not
     * bind and the whole "not re-appear" half is still open.
     */
    public static long bfsAdvancedCullRelaxed() {
        synchronized (LOCK) {
            return bfsAdvancedCullRelaxed;
        }
    }

    /**
     * P2 probe: sweeps that found the camera high above the terrain, i.e.
     * the denominator {@link #bfsAdvancedCullRelaxed()} is read against.
     */
    public static long altitudeArmSweeps() {
        synchronized (LOCK) {
            return altitudeArmSweeps;
        }
    }

    /**
     * H2 probe: live sections the last sweep found outside vanilla's
     * compile disc, i.e. real geometry the BFS-mask draw path is drawing
     * only because Meshelium marked it. Zero on a fresh world and while
     * the camera has not moved a section; a steady few hundred at render
     * distance 16 is the corner lobes doing their job.
     */
    public static int unlistedLiveDrawn() {
        synchronized (LOCK) {
            return unlistedLiveDrawn;
        }
    }

    /**
     * O7 probe: the subset of {@link #unlistedLiveDrawn()} the ALTITUDE
     * arm added — live sections inside vanilla's disc that the BFS-mask
     * path is drawing because the camera is more than
     * {@value #VANILLA_ADVANCED_CULL_SECTIONS} sections above them. Must
     * be 0 at ground level, rise when the player flies up, and fall back
     * to 0 on landing. It is also the entire frame-time cost of the fix,
     * so read it against the frame graph before arguing about the arm.
     */
    public static int unlistedAltitudeDrawn() {
        synchronized (LOCK) {
            return unlistedAltitudeDrawn;
        }
    }

    /**
     * S2 probe: the subset of {@link #unlistedAltitudeDrawn()} that the
     * pre18 Y-only gate would have missed — sections within three
     * sections of the camera's own Y that vanilla has on its ray-marched
     * arm through the HORIZONTAL disjunct. Zero at ground level in any
     * relief, zero at high altitude (the Y gate claims those), nonzero
     * exactly across the 64-block climb window S2 is about.
     *
     * <p><b>This is the discriminator</b> the next playtest reads against
     * {@code SectionBuildTap.visibleSectionsListed()}:</p>
     * <ul>
     *   <li>{@code listed} collapsing, {@code band} rising, picture
     *       clean — LISTING starvation, covered by the mask. Fixed.</li>
     *   <li>{@code listed} collapsing, {@code band} rising, picture STILL
     *       holed — the missing positions are not resident, so no mask can
     *       draw them: COMPILE latency (or never-bound). The next move is
     *       the listing side or far coverage, not another mark.</li>
     *   <li>{@code band} flat at 0 while the owner is above the terrain —
     *       the ARM is down; read {@code topSy} against his section Y.</li>
     * </ul>
     */
    public static int unlistedBandDrawn() {
        synchronized (LOCK) {
            return unlistedBandDrawn;
        }
    }

    /**
     * pre21 probe: of {@link #unlistedBandDrawn()}, the marks inside
     * vanilla's 7x7 adjacency core — the seventh fly-up fix's whole
     * population. Equal to {@code band} at render distance 2, where the
     * disc is the core; a few dozen at most anywhere else.
     */
    public static int unlistedBandCoreDrawn() {
        synchronized (LOCK) {
            return unlistedBandCoreDrawn;
        }
    }

    /**
     * S6 probe: resident entries the last sweep found in a column whose
     * resident terrain top is strictly BELOW the camera's section — the
     * local horizon rule's reach, before the disc and the
     * {@code dsy >= 0} gates narrow it to {@link #unlistedBandDrawn()}
     * (the 7x7 core stopped being a gate in pre21).
     *
     * <p>Replaces S2's {@code terrainAboveArmed()}, which reported a
     * disc-wide {@code camSy > p90} boolean that no longer exists — the
     * arm that stayed down over the owner's water because the same disc
     * held a mountain. {@code horizon} at 0 while the owner is flying
     * above the terrain now means the CENSUS is wrong (nothing resident
     * below him), not that a threshold was missed.</p>
     */
    public static int unlistedHorizonEntries() {
        synchronized (LOCK) {
            return unlistedHorizonEntries;
        }
    }

    /** S6 probe: marks made at upload BIND rather than by the sweep. */
    public static long unlistedBindMarks() {
        synchronized (LOCK) {
            return unlistedBindMarks;
        }
    }

    /** S6 probe: of {@link #unlistedBindMarks()}, the band rule's own. */
    public static long unlistedBindBandMarks() {
        synchronized (LOCK) {
            return unlistedBindBandMarks;
        }
    }

    /**
     * S6 &mdash; THE ONE MEASUREMENT THAT REPORTS ABSENCE.
     *
     * <p>Every other number this class publishes is a cardinality of
     * something we HOLD. {@link #unlistedBandDrawn} counts marks that were
     * SET, so it is high precisely when a fix ran, whether or not the fix
     * sufficed; S2's own three-row discriminator table cannot be read
     * without eyes on pixels for exactly that reason. Absence is not a
     * count of anything we hold &mdash; it is a SET DIFFERENCE, and this
     * is it:</p>
     *
     * <pre>
     *   expected = resident, slot-owning, LEDGER_NEAR_LIVE sections
     *              INSIDE  vanilla's compile disc
     *              INSIDE  this frame's cull frustum (per region, the
     *                      drawer's own test on the drawer's own AABB,
     *                      AND per section when the caller supplies the
     *                      drawer's per-section test - pre21)
     *              AT OR BELOW the camera's section   (dsy &gt;= 0)
     *              in a column whose resident top is BELOW the camera
     *   drawn    = vanilla listed it  OR  one of our mask bits carries it
     *   ABSENT   = expected minus drawn            &lt;-- must be empty
     * </pre>
     *
     * <p>pre21: the 7x7 core is INSIDE the expectation. It was excluded
     * ("the ray-marched arm's wave; core absences are reported as
     * {@code absentCore}") and at render distance 2 the whole disc is the
     * core, so the leg was structurally blind at the owner's setting.
     * {@code absentCore} survives as a sub-count of {@code absent}, and
     * {@code absentDsy} says at which depths the absences sit — the rd-2
     * signature is every absence at exactly {@code dsy == 3}.</p>
     *
     * <p>The difference IS the hole, by construction. It cannot be
     * satisfied by a mark that did not help, it cannot be satisfied by a
     * counter that went up, and it does not care which of vanilla's list,
     * H2's lobes, O7's altitude rule or S6's band rule covers a position
     * &mdash; only that SOMETHING does.</p>
     *
     * <h2>Why the expectation set is scoped the way it is</h2>
     * <p>Each clause is a promise this renderer actually makes, and
     * dropping any of them would assert something nobody intends to
     * deliver:</p>
     * <ul>
     *   <li><b>resident</b> &mdash; a mask can only draw geometry we own.
     *       A position vanilla never compiled is a LISTING or COVERAGE
     *       defect and no draw-side rule can touch it. Counted separately
     *       as {@code notCaptured} rather than silently dropped.</li>
     *   <li><b>in frustum</b> &mdash; the drawer culls per REGION, so the
     *       audit uses the drawer's own per-region verdict rather than a
     *       second implementation of it. pre21 adds the drawer's
     *       per-SECTION test on the same frustum when the caller hands it
     *       over, because vanilla's {@code visibleSections} is itself
     *       per-section frustum-filtered
     *       ({@code SectionOcclusionGraph.addSectionsInFrustum}, on the
     *       culling frustum offset by 8 blocks toward the camera): a
     *       section behind the camera inside an in-frustum region is not
     *       a hole, and a region gate alone would count it as one. The
     *       drawer's plain frustum is a subset of vanilla's offset one,
     *       so a section this test admits is one vanilla's list would
     *       have admitted too &mdash; never a false absence.</li>
     *   <li><b>{@code dsy >= 0}</b> &mdash; the one-directional discipline
     *       O7 set and every wave since has kept: a camera BELOW a section
     *       keeps vanilla's answer, so caves and mining stay culled.
     *       Above-camera absences are real and are reported as
     *       {@code absentAbove}, but they are not a broken promise.</li>
     *   <li><b>the 7x7 core, since pre21</b> &mdash; it used to be excluded
     *       ("inside it vanilla uses the cheap adjacency arm"), and the
     *       owner's rd-2 report is what that exclusion cost: at rd 2 the
     *       whole disc is the core, vanilla's adjacency is clamped to
     *       {@code |dsy| <= rd} there, and the leg could not see the layer
     *       that clamp left. Core absences are ABSENT now;
     *       {@code absentCore} only says how many of them were core.</li>
     *   <li><b>column top below the camera</b> &mdash; the local horizon.
     *       The band rule declines a column whose terrain reaches the
     *       camera because vanilla's occlusion answer for it is worth
     *       keeping; those are reported as {@code absentOccluded}.</li>
     * </ul>
     *
     * <p>{@code expected} is returned so a caller can refuse a VACUOUS
     * pass: an empty difference over an empty expectation proves nothing,
     * and a leg that green-exits on it is worse than no leg.</p>
     *
     * @param regionKeys   {@code TerrainDrawer.coverageAuditKeys()}
     * @param regionWords  {@code TerrainDrawer.coverageAuditWords()}
     * @param regionCount  {@code TerrainDrawer.coverageAuditRegions()}
     * @param stride       {@code TerrainDrawer.COVERAGE_AUDIT_STRIDE}
     * @param inFrustumBit {@code TerrainDrawer.COVERAGE_AUDIT_IN_FRUSTUM}
     * @param noMaskBit    {@code TerrainDrawer.COVERAGE_AUDIT_NO_MASK}
     * @param cameraSectionY the camera's section Y for this capture
     * @param viewRadius   vanilla's effective render distance
     * @param maxSamples   concrete section coordinates to name in
     *                     {@link CoverageAudit#samples()}
     * @param sectionInFrustum pre21: the drawer's per-section frustum test
     *                     for the capture frame
     *                     ({@code TerrainDrawer::coverageAuditSectionInFrustum}),
     *                     or null to gate on the region verdict alone
     */
    public static CoverageAudit auditUnlistedCoverage(long[] regionKeys, int[] regionWords,
            int regionCount, int stride, int inFrustumBit, int noMaskBit,
            int cameraSectionY, int viewRadius, int maxSamples,
            SectionFrustumTest sectionInFrustum) {
        synchronized (LOCK) {
            long camera = SectionBuildTap.cameraSectionXZ();
            if (camera == SectionBuildTap.CAMERA_SECTION_UNKNOWN || viewRadius <= 0
                    || cameraSectionY == Integer.MIN_VALUE) {
                return new CoverageAudit(0, 0, 0, 0, 0, 0, 0, 0, "", "no camera");
            }
            int camX = (int) (camera >> 32);
            int camZ = (int) camera;
            // The census is rebuilt HERE rather than reused, so the audit
            // cannot be fooled by a sweep that early-returned on an
            // unchanged camera while the terrain under it changed. It is
            // the same walk the sweep does and it leaves columnTopSy
            // consistent with this camera, which is where the sweep would
            // have left it anyway.
            residentTerrainScanLocked(camX, camZ, viewRadius);
            it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap index =
                    new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(
                            Math.max(16, regionCount * 2));
            index.defaultReturnValue(-1);
            for (int i = 0; i < regionCount; i++) {
                index.put(regionKeys[i], i);
            }
            int expected = 0;
            int absent = 0;
            int absentCore = 0;
            int absentAbove = 0;
            int absentOccluded = 0;
            int outOfFrustum = 0;
            int notCaptured = 0;
            int drawnExpected = 0;
            // pre21: absences by depth below the camera, 0..7 and "8+".
            // The rd-2 defect has one signature - everything at dsy 3 -
            // and a histogram is what lets the next reader see a
            // different one without guessing from samples.
            int[] absentByDsy = new int[9];
            StringBuilder samples = new StringBuilder();
            int named = 0;
            for (Resident r : resident.values()) {
                if (!r.ownsSlot || r.ledgerState != LEDGER_NEAR_LIVE) {
                    continue;
                }
                int dx = r.sx - camX;
                int dz = r.sz - camZ;
                if (!vanillaMayList(dx, dz, viewRadius)) {
                    continue; // H2's lobes: outside the disc, always marked
                }
                int idx = index.get(r.regionKey);
                if (idx < 0) {
                    // The capture is a frame older or newer than this walk
                    // and the region was born or died between them.
                    notCaptured++;
                    continue;
                }
                int o = idx * stride;
                if ((regionWords[o] & inFrustumBit) == 0) {
                    outOfFrustum++;
                    continue;
                }
                if (sectionInFrustum != null && !sectionInFrustum.visible(r.sx, r.sy, r.sz)) {
                    // pre21: inside an on-screen region but itself off
                    // screen - vanilla's per-section frustum filter would
                    // not list it either, so it is correctly not drawn.
                    outOfFrustum++;
                    continue;
                }
                boolean drawn = (regionWords[o] & noMaskBit) != 0
                        || (regionWords[o + 1 + (r.posKey >>> 5)] & (1 << (r.posKey & 31))) != 0;
                int dsy = cameraSectionY - r.sy;
                boolean core = Math.abs(dx) <= VANILLA_ADVANCED_CULL_SECTIONS
                        && Math.abs(dz) <= VANILLA_ADVANCED_CULL_SECTIONS;
                boolean pastHorizon = cameraSectionY > columnTopSyLocked(dx, dz);
                // pre21: the core is expected like everything else. The
                // renderer's promise is per column, not per vanilla arm.
                if (dsy >= 0 && pastHorizon) {
                    expected++;
                    if (drawn) {
                        drawnExpected++;
                    }
                }
                if (drawn) {
                    continue;
                }
                if (dsy < 0) {
                    absentAbove++;
                } else if (!pastHorizon) {
                    absentOccluded++;
                } else {
                    absent++;
                    absentByDsy[Math.min(8, dsy)]++;
                    if (core) {
                        absentCore++;
                    }
                    if (named < maxSamples) {
                        if (named > 0) {
                            samples.append(' ');
                        }
                        samples.append('(').append(r.sx).append(',').append(r.sy)
                                .append(',').append(r.sz).append(" d=")
                                .append(dx).append(',').append(dsy).append(',').append(dz)
                                .append(" colTop=").append(columnTopSyLocked(dx, dz))
                                .append(core ? " core" : " ring")
                                .append(')');
                        named++;
                    }
                }
            }
            StringBuilder byDsy = new StringBuilder();
            for (int d = 0; d < absentByDsy.length; d++) {
                if (absentByDsy[d] == 0) {
                    continue;
                }
                if (byDsy.length() > 0) {
                    byDsy.append(' ');
                }
                byDsy.append(d == 8 ? "8+" : Integer.toString(d)).append(':')
                        .append(absentByDsy[d]);
            }
            return new CoverageAudit(expected, drawnExpected, absent, absentCore,
                    absentAbove, absentOccluded, outOfFrustum, notCaptured,
                    byDsy.toString(), samples.toString());
        }
    }

    /**
     * pre21: the drawer's per-section frustum verdict for the audit's
     * capture frame, handed in by the caller so this class never names a
     * drawer type. {@code TerrainDrawer::coverageAuditSectionInFrustum}
     * is the one implementation.
     */
    @FunctionalInterface
    public interface SectionFrustumTest {
        /** Is the 16-block section at these section coordinates on screen? */
        boolean visible(int sx, int sy, int sz);
    }

    /**
     * S6: the result of {@link #auditUnlistedCoverage}. Every field is a
     * population of RESIDENT, slot-owning, live sections inside vanilla's
     * compile disc; the four {@code absent*} fields partition the ones
     * nothing drew, and only {@link #absent()} is a broken promise.
     *
     * @param expected       sections the renderer promises to draw at this
     *                       pose (in frustum, at or below the camera, past
     *                       the local horizon &mdash; core and ring alike
     *                       since pre21)
     * @param drawn          of those, the ones something actually drew
     * @param absent         <b>THE NUMBER.</b> {@code expected - drawn}:
     *                       resident geometry, on screen, that vanilla did
     *                       not list and no mask of ours carries
     * @param absentCore     of {@code absent}, the ones inside the 7x7
     *                       core (a sub-count since pre21, not a separate
     *                       population)
     * @param absentAbove    undrawn ABOVE the camera, where every rule here
     *                       deliberately keeps vanilla's answer
     * @param absentOccluded undrawn in a column whose terrain reaches the
     *                       camera &mdash; the local horizon's declined
     *                       set, i.e. real occlusion we chose to keep
     * @param outOfFrustum   skipped: the drawer's own region cull (or, with
     *                       a per-section test, its section cull) rejected
     *                       them, so they are correctly not drawn
     * @param notCaptured    skipped: the region is not in the capture (it
     *                       was born or died between capture and walk)
     * @param absentDsy      pre21: the {@code absent} set by depth below the
     *                       camera, {@code "dsy:count"} pairs, empty when
     *                       nothing is absent
     * @param samples        concrete coordinates for the {@code absent} set
     */
    public record CoverageAudit(int expected, int drawn, int absent, int absentCore,
            int absentAbove, int absentOccluded, int outOfFrustum, int notCaptured,
            String absentDsy, String samples) {

        /** One line for a log, in the order a reader needs them. */
        @Override
        public String toString() {
            return "expected=" + expected + " drawn=" + drawn + " ABSENT=" + absent
                    + " absentCore=" + absentCore + " absentAbove=" + absentAbove
                    + " absentOccluded=" + absentOccluded
                    + " outOfFrustum=" + outOfFrustum + " notCaptured=" + notCaptured
                    + (absentDsy.isEmpty() ? "" : " absentByDsy[" + absentDsy + "]");
        }
    }

    /** H2 probe: mask bits the sweep has flipped this session, both ways. */
    public static long unlistedMaskFlips() {
        synchronized (LOCK) {
            return unlistedMaskFlips;
        }
    }

    /**
     * K2/L10 probe: snapshot {@code [19]} flips the sweep has made this
     * session, both ways — the translucent twin of
     * {@link #unlistedMaskFlips}, and the number that proves the water
     * half of the corner fix is armed rather than merely compiled.
     */
    public static long unlistedTransFlips() {
        synchronized (LOCK) {
            return unlistedTransFlips;
        }
    }

    // ------------------------------------------------------------------
    // Ledger probes: the ownership ledger's counters - since step 4 the
    // ONLY counter surface the seam has. Field javadocs (beside the
    // Resident class) carry what each one proves.
    // ------------------------------------------------------------------

    /** Ledger probe: transitions into AWAITING_SUCCESSOR. */
    public static long ledgerParked() {
        synchronized (LOCK) {
            return ledgerParked;
        }
    }

    /** Ledger probe: the DEAD demote edge. MUST read zero since step 4. */
    public static long ledgerDemoted() {
        synchronized (LOCK) {
            return ledgerDemoted;
        }
    }

    /** Ledger probe: AWAITING resolved by the atomic far swap. */
    public static long ledgerResolvedSwap() {
        synchronized (LOCK) {
            return ledgerResolvedSwap;
        }
    }

    /** Ledger probe: AWAITING resolved by vanilla's own truth. */
    public static long ledgerResolvedVanilla() {
        synchronized (LOCK) {
            return ledgerResolvedVanilla;
        }
    }

    /** Ledger probe: E1 releases. Structurally zero until seam step 4. */
    public static long ledgerResolvedUncovered() {
        synchronized (LOCK) {
            return ledgerResolvedUncovered;
        }
    }

    /** Ledger probe: AWAITING spent for memory (E2's shadow). */
    public static long ledgerEvictedWall() {
        synchronized (LOCK) {
            return ledgerEvictedWall;
        }
    }

    /** Ledger probe: ring sum over {@link #ledgerEvictedWall()} evictions. */
    public static long ledgerEvictedWallRingSum() {
        synchronized (LOCK) {
            return ledgerEvictedWallRingSum;
        }
    }

    /** Ledger probe: summed AWAITING hold, ms, over every resolution. */
    public static long ledgerHoldMillis() {
        synchronized (LOCK) {
            return ledgerHoldMillis;
        }
    }

    /** Ledger probe: worst single AWAITING hold, ms. */
    public static long ledgerHoldMaxMillis() {
        synchronized (LOCK) {
            return ledgerHoldMaxMillis;
        }
    }

    /** Ledger GAUGE: quads parked AWAITING right now. */
    public static long ledgerAwaitingQuads() {
        synchronized (LOCK) {
            return ledgerAwaitingQuads;
        }
    }

    /** Ledger probe: session high-water of the AWAITING quad gauge. */
    public static long ledgerAwaitingQuadsPeak() {
        synchronized (LOCK) {
            return ledgerAwaitingQuadsPeak;
        }
    }

    /** Ledger probe: transitions outside the legal table. ZERO is the contract. */
    public static long ledgerIllegalTransitions() {
        synchronized (LOCK) {
            return ledgerIllegalTransitions;
        }
    }

    /** Ledger probe: debug-audit mismatches (moves only under the audit property). */
    public static long ledgerAuditMismatches() {
        synchronized (LOCK) {
            return ledgerAuditMismatches;
        }
    }

    /**
     * SEAM step 2 diagnostics (gametest leg A's draw-set probe): how many
     * FAR sections are bound at chunk column {@code (cx, cz)} right now.
     * A bound far entry always owns a snapshot slot, so this is the
     * per-column "is the far field drawing here" truth the replace-in-
     * place exchange must never let dip to zero. O(farResident) walk
     * under LOCK - a test-cadence probe, not a per-frame path.
     */
    public static int farResidentSectionsAt(int cx, int cz) {
        synchronized (LOCK) {
            int sections = 0;
            for (Resident r : farResident.values()) {
                if (r.sx == cx && r.sz == cz) {
                    sections++;
                }
            }
            return sections;
        }
    }

    /**
     * SEAM step 2 diagnostics, the geometry half: total quads across the
     * FAR sections bound at chunk column {@code (cx, cz)}. Leg A submits
     * a deliberately different shell and asserts this moved while
     * {@link #farResidentSectionsAt} never read zero - the swap really
     * exchanged geometry rather than skipping. Same O(farResident)
     * test-cadence walk as its sibling.
     */
    public static long farResidentQuadsAt(int cx, int cz) {
        synchronized (LOCK) {
            long quads = 0;
            for (Resident r : farResident.values()) {
                if (r.sx == cx && r.sz == cz) {
                    quads += r.quadCount;
                }
            }
            return quads;
        }
    }

    /**
     * SEAM step 4 diagnostics (gametest leg B's continuity oracle):
     * packed CHUNK-COLUMN keys ({@code (cx << 32) | (cz & 0xFFFFFFFF)})
     * holding at least one BOUND section of any kind - live resident,
     * retained (held or parked), or far. This is the invariant's own
     * "bound" set, and it must be the WHOLE of it: the leg's first real
     * run (2026-08-24) judged drawn-ness from the walker's residents
     * plus the parked set alone, and read the handover band's supersede
     * bind - far copy exchanged for a LIVE vanilla copy in one hold,
     * never a frame undrawn - as 23 continuity violations, because a
     * live-bound column was invisible to it. O(resident + retained +
     * farResident) walk under LOCK - a test-cadence probe, not a
     * per-frame path.
     */
    public static it.unimi.dsi.fastutil.longs.LongOpenHashSet boundColumnsSnapshot() {
        synchronized (LOCK) {
            it.unimi.dsi.fastutil.longs.LongOpenHashSet out =
                    new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            for (Resident r : resident.values()) {
                out.add((((long) r.sx) << 32) | (r.sz & 0xFFFFFFFFL));
            }
            for (Resident r : retained.values()) {
                out.add((((long) r.sx) << 32) | (r.sz & 0xFFFFFFFFL));
            }
            for (Resident r : farResident.values()) {
                out.add((((long) r.sx) << 32) | (r.sz & 0xFFFFFFFFL));
            }
            return out;
        }
    }

    /**
     * SEAM step 3: the walker publishes its COVERAGE GEOMETRY, once per
     * armed pump, so {@link #onMeshReleased} can decide on a build
     * thread whether a section vanilla is dropping is far-domain.
     *
     * <p>Three plain volatile writes and no lock. The walker calls this
     * from {@code FarFieldResidency.pumpInner} on the render thread,
     * OUTSIDE the residency lock, which is where the rest of its
     * act-half runs.</p>
     *
     * <p>THE STEP-3 CONTRACT, and the whole difference from the deleted
     * ring publication: a STAND-DOWN never clears this. Geometry states
     * where the far domain is, which stays true while vanilla merely
     * recompiles; only ADMISSION (the walker's own stand-down gating)
     * waits for the settle. The geometry clears exclusively through
     * {@link #clearFarCoverage} - master off / ring collapsed / world
     * era end - because those are the moments the far domain genuinely
     * stops existing and a park would be a leak.</p>
     *
     * @param camera    packed camera SECTION (never
     *                  {@link SectionBuildTap#CAMERA_SECTION_UNKNOWN} -
     *                  that is {@code clearFarCoverage}'s job)
     * @param coverEdge the inner edge in chunks (vanilla's compile disc
     *                  less the handover band)
     * @param l1        the outer edge in chunks
     */
    public static void publishFarCoverage(long camera, int coverEdge, int l1) {
        farCoverageCoverEdge = coverEdge;
        farCoverageL1 = l1;
        farCoverageCamera = camera; // last: the gate every reader tests first
    }

    /**
     * SEAM step 3: withdraw the coverage geometry - the far domain
     * stopped existing (master switch off, ring collapsed inside the
     * render distance, world era end). After this every free takes the
     * plain path, which is what keeps a far-field-off session at
     * literally zero parking cost ({@link #coveredNow} is false
     * everywhere without a published geometry).
     */
    public static void clearFarCoverage() {
        farCoverageCamera = SectionBuildTap.CAMERA_SECTION_UNKNOWN;
        farCoverageCoverEdge = -1;
        farCoverageL1 = -1;
    }

    /** The published outer edge, for the reload republish. Volatile read. */
    public static int publishedFarCoverageL1() {
        return farCoverageL1;
    }

    /**
     * SEAM step 3: {@code LevelRenderer.invalidateCompiledGeometry} HEAD
     * (render thread, via {@code LevelRendererMixin}) - the reload storm
     * is about to run {@code releaseAllBuffers} over the whole grid
     * (javap: release at ip 138, the new ViewArea at ip 149/184), and
     * every one of those frees will consult {@link #coveredNow}. The
     * walker cannot have republished yet (it detects the reload by
     * ViewArea identity NEXT pump), so the storm would otherwise be
     * judged against last pump's edges - stale by exactly the render
     * distance change that caused it. Republish synchronously from the
     * live options instead: program order on the render thread
     * guarantees every reset() in the storm sees shrink-adjusted
     * geometry.
     *
     * <p>ADJUSTS an existing publication only - never creates one (a
     * cleared coverage stays cleared: master-off must not resurrect
     * parking) - and never names a far-field class before the sticky
     * {@code farEverArmed} arm proves it is already loaded, so the
     * zero-cost-off posture holds: a session that never enabled the far
     * field pays two field reads per reload and nothing else.</p>
     */
    public static void onVanillaGeometryInvalidated(int effectiveRenderDistance) {
        if (!farEverArmed || farHookBroken || farCoverageL1 <= 0) {
            return;
        }
        try {
            com.deds.meshelium.farfield.FarFieldResidency
                    .republishCoverage(effectiveRenderDistance);
        } catch (Throwable t) {
            farHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium far-field coverage republish failed; the far field is off "
                            + "for this session (near-field rendering is unaffected)", t);
        }
    }

    /**
     * SEAM steps 3-4: is this chunk column FAR-DOMAIN right now - outside
     * vanilla's compile disc and inside the coverage's outer edge? The
     * free-side half of the invariant: a free of a covered position
     * parks, everything else frees.
     *
     * <p>This is {@code FarFieldResidency.stillWanted} transcribed, and
     * transcribed rather than called for the reason the walker's own
     * {@code nearCovered} is a copy of vanilla's arithmetic: the caller is
     * {@link #onMeshReleased}, which runs on BUILD threads, and every set
     * the walker would consult is render-thread confined. What crosses the
     * thread boundary is three volatiles and nothing else.</p>
     *
     * <p>The inner test is vanilla's own {@code
     * ChunkTrackingView.isWithinDistance} shape - pad 1, STRICT less-than
     * (javap, 26.2 merged jar, ip 45-77) - run against the SHORTENED
     * radius the walker publishes, exactly as {@code nearCovered} does.
     * The outer test is the coverage's Euclidean edge, inclusive, for the
     * same reason {@code withinRing} is: the fog wall it meets is
     * radial.</p>
     *
     * <p>Answers false, and so parks nothing, whenever no geometry is
     * published. A one-pump-stale camera can only mis-answer a column
     * sitting exactly on an edge, where a park is harmless in one
     * direction (E1 releases it within a pump) and a missing park is one
     * frame of the old behaviour in the other - and the live-camera
     * preference below (the P9 rule, kept) removes even that for every
     * release vanilla's own repositioning drives.</p>
     */
    private static boolean coveredNow(int chunkX, int chunkZ, boolean leavingGrid) {
        long camera = farCoverageCamera;
        if (camera == SectionBuildTap.CAMERA_SECTION_UNKNOWN) {
            return false;
        }
        int l1 = farCoverageL1;
        int coverEdge = farCoverageCoverEdge;
        if (l1 <= 0 || coverEdge < 0) {
            return false;
        }
        // P9: prefer the LIVE camera over the walker's published one. The
        // coverage's two EDGES are radii and belong to the walker; its
        // CENTRE is just where the camera was when the walker last
        // pumped, and the release being served happens inside vanilla's
        // own repositionCamera, which is driven by the camera as it is
        // NOW. Using the stale centre answers a question nobody asked.
        long live = SectionBuildTap.cameraSectionXZ();
        if (live != SectionBuildTap.CAMERA_SECTION_UNKNOWN) {
            camera = live;
        }
        return coveredNow(camera, coverEdge, l1, chunkX, chunkZ, leavingGrid);
    }

    /**
     * E1's own test: is this column inside the coverage's OUTER edge?
     * The inner (vanilla's-disc) arm is deliberately absent - a parked
     * copy inside the disc exits via the supersede bind, not via
     * geometry (see {@link #scanAwaitingCoverageLocked}). Answers false
     * with no geometry published, which is what lets the latch/master-
     * off paths drain every park through E1. Same live-camera
     * preference as {@link #coveredNow}, same volatiles, no lock.
     */
    private static boolean withinCoverageOuterEdge(int chunkX, int chunkZ) {
        long camera = farCoverageCamera;
        if (camera == SectionBuildTap.CAMERA_SECTION_UNKNOWN) {
            return false;
        }
        int l1 = farCoverageL1;
        if (l1 <= 0) {
            return false;
        }
        long live = SectionBuildTap.cameraSectionXZ();
        if (live != SectionBuildTap.CAMERA_SECTION_UNKNOWN) {
            camera = live;
        }
        long dx = chunkX - (int) (camera >> 32);
        long dz = chunkZ - (int) camera;
        return dx * dx + dz * dz <= (long) l1 * l1;
    }

    /**
     * The geometry half of {@link #coveredNow}, edges already read.
     *
     * @param leavingGrid P9: the release is inside a {@code reset()}
     *        bracket, i.e. vanilla is recycling this slot because the
     *        section left its storage SQUARE. The inner (cover-edge) test
     *        asks "is this column still vanilla's to cover", and
     *        {@code leavingGrid} is a direct answer of NO from vanilla
     *        itself - Chebyshev radius + 1, four chunks outside
     *        {@code coverRadius} at render distance 32. Running the radius
     *        arithmetic on top of that can only produce a FALSE NEGATIVE,
     *        and a false negative here is exactly the owner's P9 flash:
     *        "when i fly away and unload a chunk, it does flash for a
     *        second while the lod loads in". The outer test still runs -
     *        a column past L1 is nobody's.
     */
    private static boolean coveredNow(long camera, int coverEdge, int l1,
            int chunkX, int chunkZ, boolean leavingGrid) {
        long dx = chunkX - (int) (camera >> 32);
        long dz = chunkZ - (int) camera;
        if (!leavingGrid) {
            long px = Math.max(0L, Math.abs(dx) - 1L);
            long pz = Math.max(0L, Math.abs(dz) - 1L);
            if (px * px + pz * pz < (long) coverEdge * coverEdge) {
                return false; // still vanilla's to cover
            }
        }
        return dx * dx + dz * dz <= (long) l1 * l1;
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
                if (r.ledgerState == LEDGER_AWAITING_SUCCESSOR) {
                    continue; // held for a replacement; exits are enumerated
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
                if (r.ledgerState == LEDGER_AWAITING_SUCCESSOR) {
                    continue; // held for a replacement; exits are enumerated
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
            if (r.ledgerState == LEDGER_AWAITING_SUCCESSOR) {
                // Step 4: the pressure sweep no longer spends parked
                // holds (the deleted speculative-first rule). AWAITING
                // memory is bounded by its own quad budget and spent
                // farthest-first there; this sweep spends the NEAR_HELD
                // horizon, which is what it was built for.
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
     * <p><b>Step 5 (the wall ordering):</b> the batch spends the
     * NEAR_HELD horizon first and parked AWAITING copies only after it,
     * each population farthest-ring-first — the
     * {@link #evictAwaitingFarthestLocked} ordering applied to the wall.
     * The old walk took insertion order, which is age order, and age
     * correlates with NOTHING the player can see; a wall that must eat
     * drawn terrain should eat the fog first. The AWAITING half rides
     * the {@link #awaitingByRing} buckets (ring-at-park, lazy removal),
     * the NEAR_HELD half a one-pass bucketing of the same shape — O(n)
     * once, on an event that is already a memory emergency. Every
     * AWAITING copy the wall takes is E2, counted by
     * {@link #freeRetainedLocked}'s arm as before.</p>
     *
     * @return true when at least one retained entry was evicted
     */
    private static boolean forceEvictRetainedLocked() {
        if (retained.isEmpty()) {
            return false;
        }
        int budget = FORCE_EVICT_BATCH;
        boolean any = false;
        int maxRing = awaitingByRing.length - 1;
        // Pass 1: the NEAR_HELD horizon (non-AWAITING), farthest first.
        it.unimi.dsi.fastutil.longs.LongArrayList[] buckets =
                new it.unimi.dsi.fastutil.longs.LongArrayList[maxRing + 1];
        for (Resident r : retained.values()) {
            if (r.ledgerState == LEDGER_AWAITING_SUCCESSOR) {
                continue; // pass 2's population, already bucketed at park
            }
            int ring = (int) Math.min(maxRing, ledgerCameraRingLocked(r));
            it.unimi.dsi.fastutil.longs.LongArrayList bucket = buckets[ring];
            if (bucket == null) {
                bucket = new it.unimi.dsi.fastutil.longs.LongArrayList();
                buckets[ring] = bucket;
            }
            bucket.add(posPack(r.sx, r.sy, r.sz));
        }
        for (int ring = maxRing; ring >= 0 && budget > 0; ring--) {
            it.unimi.dsi.fastutil.longs.LongArrayList bucket = buckets[ring];
            if (bucket == null) {
                continue;
            }
            for (int i = bucket.size() - 1; i >= 0 && budget > 0; i--) {
                Resident r = retained.remove(bucket.getLong(i));
                if (r == null) {
                    continue; // cannot happen inside one hold; belt only
                }
                freeRetainedLocked(r);
                evictedByPressure++;
                drawEpoch++;
                budget--;
                any = true;
            }
        }
        // Pass 2: parked AWAITING copies, farthest first off the standing
        // buckets. E2 - the wall outranks the hold - and
        // freeRetainedLocked's arm counts each on ledgerEvictedWall, so
        // the wall eating the seam is never silent (the O6 lesson, kept
        // with one counter instead of one per mechanism).
        for (int ring = maxRing; ring >= 0 && budget > 0; ring--) {
            it.unimi.dsi.fastutil.longs.LongArrayList bucket = awaitingByRing[ring];
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            for (int i = bucket.size() - 1; i >= 0 && budget > 0; i--) {
                long pos = bucket.getLong(i);
                Resident r = retained.get(pos);
                if (r == null || r.ledgerState != LEDGER_AWAITING_SUCCESSOR) {
                    bucket.removeLong(i); // lazy removal
                    continue;
                }
                bucket.removeLong(i);
                retained.remove(pos);
                freeRetainedLocked(r); // E2, counted there
                evictedByPressure++;
                drawEpoch++;
                budget--;
                any = true;
            }
        }
        if (any) {
            retainedBackpressure++;
        }
        return any;
    }

    // ------------------------------------------------------------------
    // Far field, wave W3 (docs/FARFIELD-WAVES.md; plug points from
    // FARFIELD-CODEBASE-SEAM.md). Far entries are retained-shaped
    // residents in their own map — see the farResident javadoc.
    // ------------------------------------------------------------------

    /**
     * W3 force-evict twin of {@link #forceEvictRetainedLocked}, tried
     * FIRST: when a LIVE section hits the region or arena wall, the far
     * horizon is the most expendable thing in the store — spend it
     * before the wave-11 retained set, requeue the section, and no drop
     * counter ever moves (the guard stays clean; landmine L4's
     * requirement made mechanical).
     * Bookkeeping to the walker is deferred via {@link #farFreedPending}.
     *
     * <p><b>Step 5 (the wall ordering):</b> farthest-ring-first, by one
     * bucketing pass of the {@link #awaitingByRing} shape. The old walk
     * popped admission order — the walker's near-first spiral — so the
     * wall ate the horizon RIGHT BESIDE the player first, the one place
     * a violation is guaranteed to be seen (the audit's confirmed
     * nearest-first minor). Now the sanctioned violation lands in the
     * fog, the same rule E2's budget eviction already follows.</p>
     *
     * @return true when at least one far entry was evicted
     */
    private static boolean forceEvictFarLocked() {
        if (farResident.isEmpty()) {
            return false;
        }
        int maxRing = awaitingByRing.length - 1;
        it.unimi.dsi.fastutil.longs.LongArrayList[] buckets =
                new it.unimi.dsi.fastutil.longs.LongArrayList[maxRing + 1];
        for (Resident r : farResident.values()) {
            int ring = (int) Math.min(maxRing, ledgerCameraRingLocked(r));
            it.unimi.dsi.fastutil.longs.LongArrayList bucket = buckets[ring];
            if (bucket == null) {
                bucket = new it.unimi.dsi.fastutil.longs.LongArrayList();
                buckets[ring] = bucket;
            }
            bucket.add(posPack(r.sx, r.sy, r.sz));
        }
        int budget = FORCE_EVICT_BATCH;
        boolean any = false;
        for (int ring = maxRing; ring >= 0 && budget > 0; ring--) {
            it.unimi.dsi.fastutil.longs.LongArrayList bucket = buckets[ring];
            if (bucket == null) {
                continue;
            }
            for (int i = bucket.size() - 1; i >= 0 && budget > 0; i--) {
                long pos = bucket.getLong(i);
                Resident r = farResident.remove(pos);
                if (r == null) {
                    continue; // cannot happen inside one hold; belt only
                }
                // Step 4: a drawn far section spent for MEMORY is E2 like
                // any other - counted with its ring, so a wall that eats
                // the horizon is exactly as visible as one that eats a
                // park.
                ledgerEvictedWall++;
                ledgerEvictedWallRingSum += ledgerCameraRingLocked(r);
                freeFarLocked(r);
                farFreedPending.add(pos);
                drawEpoch++;
                budget--;
                any = true;
            }
        }
        return any;
    }

    /**
     * Common tail of every far-copy release path — the retained-release
     * shape ({@link #freeRetainedLocked}) minus the retained counters
     * (far accounting lives on {@code FarFieldResidency}'s LongAdders)
     * and minus the prefix set (far sections carry no translucent state
     * in pre1, ShellMesher's water note). Frees flow through the exact
     * fence machinery: owner-checked region remove with the moved-owner
     * fanout, epoch-parked arena range, tombstoned snapshot slot.
     */
    private static void freeFarLocked(Resident r) {
        // Every far free is FAR -> CLEARED (ring-exit demote = E1 by
        // definition, reload, force-evict = E2 counted by its caller,
        // empty compile, vanilla supersede); the walker's counters split
        // the causes, so the ledger records only the transition.
        ledgerTransitionLocked(r, LEDGER_CLEARED);
        logMovedOwnerLocked(regionStore.remove(r.regionKey, r.posKey, r));
        parkAddrLocked(r.arenaAddr, r.quadCount);
        freeSnapshotSlotLocked(r);
    }

    /**
     * W3 demote entry: release one chunk column's far sections (the
     * walker's ring-exit path — docs/FARFIELD-WAVES.md, both-direction
     * rule). Render thread, takes LOCK itself; called by
     * {@code FarFieldResidency} OUTSIDE the pump's lock window.
     *
     * @param sectionYs the column's admitted section Ys ({@code count}
     *                  entries valid; the array may be longer)
     * @return sections actually released (a far entry can predecease
     *         this through supersede/evict paths; those were reported
     *         separately through the deferred queue)
     */
    public static int releaseFarColumn(int cx, int cz, int[] sectionYs, int count) {
        synchronized (LOCK) {
            int released = 0;
            for (int i = 0; i < count; i++) {
                Resident r = farResident.remove(posPack(cx, sectionYs[i], cz));
                if (r == null) {
                    continue;
                }
                freeFarLocked(r);
                drawEpoch++;
                released++;
            }
            return released;
        }
    }

    /**
     * L9 demote entry: release EVERY far section in ONE lock hold, for a
     * far-field reload.
     *
     * <p><b>Why a wholesale call and not a loop over
     * {@link #releaseFarColumn}.</b> The owner's report was that changing
     * an appearance setting made "all the chunks flash clear briefly for a
     * second" and "just seems like random jittering all over"; the answer
     * (see {@code FarFieldResidency.reloadFarField}) is to drop the far
     * field at once and let the ordinary near-first refill bring it back.
     * Column by column that is one uncontended monitor acquire per COLUMN
     * (about a thousand at the ring the owner plays with) and one
     * {@code drawEpoch} bump per SECTION; here it is one acquire and one
     * bump. The publish count is the same either way — the drawer adopts
     * at most one snapshot a frame however many times the epoch moved —
     * so what this actually buys is the lock traffic and a drop the
     * player sees as a single event.</p>
     *
     * <p><b>It does not widen the lock.</b> The per-entry work is exactly
     * {@link #freeFarLocked}'s and nothing else: no vanilla call, no IO,
     * no allocation beyond the change log's own growth. What changes is
     * that the same total work happens inside one hold instead of many —
     * at 2,000 far sections roughly 2,000 region-slot removes (a tail-slot
     * copy of {@code SectionRecord.SECTION_SIZE} bytes each), 2,000 arena
     * parks onto the current frame epoch and 2,000 snapshot tombstones,
     * which is the same order as the {@code releaseAllBuffers} storm the
     * snapshot log's overflow fallback is already sized for (2,000 records
     * against {@link #SNAPSHOT_LOG_MAX} = 65,536, so it does not even
     * reach that fallback).</p>
     *
     * <p>Deliberately does NOT feed {@link #farFreedPending}: the caller
     * is the walker itself and clears its own manifests, so posting an
     * {@code onSectionEvicted} per section would double-count the gauges.
     * Everything else is a demote like any other — the ranges go through
     * the frame-epoch fence, the region ids come back through
     * {@code RegionStore.remove}'s tombstones, and no wave-8 coverage
     * counter is anywhere near this path.</p>
     *
     * <p>Render thread, takes LOCK itself, called from OUTSIDE the pump's
     * lock window exactly like {@link #releaseFarColumn}.</p>
     *
     * @return sections actually released (0 when the far field is empty,
     *         which is the ordinary case and costs one monitor acquire)
     */
    public static int releaseAllFar() {
        synchronized (LOCK) {
            if (farResident.isEmpty()) {
                return 0;
            }
            int released = 0;
            for (Resident r : farResident.values()) {
                freeFarLocked(r);
                released++;
            }
            farResident.clear();
            // ONE bump for the whole drop: drawEpoch is a "something
            // changed" edge, and every entry above is already in the
            // change log through freeSnapshotSlotLocked's tombstone.
            drawEpoch++;
            return released;
        }
    }

    /**
     * W3 promote gate (landmine L3, the region-id budget): the walker
     * may request more shells only while
     * {@code regionCount < min(85% of maxRegions, maxRegions - nearReserve)}.
     * The 85% arm keeps far fill strictly under the wave-11 pressure
     * sweep's 90% high-water ({@link #REGION_HIGH_WATER_PCT}) so filling
     * the far ring can never start evicting the retained horizon; the
     * reserve arm holds back vanilla's worst-case grid demand —
     * {@code regionsTouched(pinnedRd) = (ceil((2 rd + 1) / 8) + 1)^2 * 7}
     * (the {@code MesheliumScaling} derivation, duplicated here because
     * that method is package-private; 700 at the standard rd-32 pin,
     * 1372 at pinned 48) — so even a far ring at its cap leaves every
     * live section admissible and the coverage guard untouchable by far
     * fill. Worked totals live on {@code FarFieldResidency}'s javadoc.
     * Render thread, takes LOCK (one uncontended monitor per pump).
     *
     * <p><b>pre13: what this gate was measured against changed, and the
     * gate itself did not.</b> {@code maxRegions()} now includes
     * {@code MesheliumScaling.farRegionDemand()}, so at the owner's
     * rd 32 / LOD 120 the budget is 3,840 ids and this gate opens at
     * {@code min(85% x 3840, 3840 - 700) = 3,140} rather than at 1,348 —
     * enough for the ring's 2,427 beside the near field's 700. The
     * pre12 review's rule is honoured exactly: the guard binding
     * constantly was fixed by RAISING THE BUDGET, never by removing or
     * widening the guard, and both arms are byte-for-byte what pre12
     * shipped.</p>
     *
     * <p><b>And the margin is thin by construction, which is worth
     * saying out loud.</b> {@code farRegionDemand} solves the second arm
     * for equality — it sizes the budget so the gate opens at exactly
     * {@code R + N} — so all the slack there is comes from the round-up
     * to 256: 3,140 against 3,127 wanted is <b>13 ids</b>. What makes
     * that safe in practice rather than on paper is that {@code N} is an
     * upper bound (700 assumes all seven Y rows in all 100 near region
     * columns) while the near field usually holds fewer, and that the
     * refusal path is graceful and now retried. If it is not enough the
     * symptom is {@code farAdmitNoBudget} climbing with
     * {@code farExtractSweepOwed} at zero, and the lever is
     * {@code -Dmeshelium.tune.farRegionYRows=4} <b>together with</b>
     * {@code -Dmeshelium.tune.farRegionCap} — on its own the row raise
     * grows the DEMAND past a budget the 4096 cap is already holding
     * down, which at L1 120 makes coverage worse rather than better.</p>
     *
     * <p><b>The arena arm matters just as much as the region arm.</b> Far
     * quads are excluded from {@code quadsResident} and
     * {@code retainedQuads} (the retained precedent) but they are very
     * much inside {@code arena.liveQuads()}, which is what
     * {@link #evictRetainedLocked}'s pressure sweep measures against
     * {@link #ARENA_HIGH_WATER_PCT}. Without a gate here, far fill walks
     * the arena up to that watermark and then evicts the wave-11
     * RETAINED horizon to make room for far decoration — exactly
     * backwards, since retained sections are real geometry and far
     * sections are an approximation of it. Stopping far promotion at
     * {@link #FAR_ARENA_GUARD_PCT} keeps far fill strictly below the
     * sweep's trigger, so the two never fight.</p>
     */
    public static boolean farPromotionHasRoom() {
        synchronized (LOCK) {
            return farFillHasRoomLocked();
        }
    }

    /**
     * B2: the same guard, under a lock the caller already holds, so the
     * ADMISSION can consult it as well as the producer.
     *
     * <p>The pre12 adversarial review found the M4 change had made this
     * guard reachable by exactly one caller and then bypassed that caller
     * for the recovery drain, which is not the narrow exemption it was
     * argued to be: {@code drainUnblockedColumns} issues a read for a
     * whole COLUMN, and every section of that column that is not bridged
     * is an ordinary net-new far admission. Ungating the producer
     * therefore ungated the only fill guard the far field has, and the
     * {@code max - nearReserve} arm of it is enforced nowhere else in the
     * codebase. The guard now lives at the admission instead, where the
     * difference between a swap and net-new fill is actually known.</p>
     */
    private static boolean farFillHasRoomLocked() {
        int max = maxRegions();
        int rd = MesheliumScaling.current().maxRd();
        int chunks = 2 * rd + 1;
        int horizontal = (chunks + 7) / 8 + 1;
        int nearReserve = horizontal * horizontal * 7;
        int gate = Math.min(max * FAR_REGION_GUARD_PCT / 100, max - nearReserve);
        if (regionStore.regionCount() >= gate) {
            return false;
        }
        long capacityQuads = MesheliumScaling.arenaCeilingBytes()
                / (4L * TerrainVertexCodec.VERTEX_STRIDE) - 1;
        long usedQuads = arena.liveQuads() - 1 - parkedQuads;
        return usedQuads < capacityQuads * FAR_ARENA_GUARD_PCT / 100;
    }

    /**
     * W3 admission drain: pull meshed far sections from the walker's
     * queue into residency, at most {@link #FAR_ADMISSIONS_PER_PUMP} per
     * pump, inside the same lock window as
     * {@link #drainPendingUploadsLocked} (the dossier's plug point) and
     * AFTER it. Also flushes the deferred eviction bookkeeping to the
     * walker (render thread — its maps' confinement). Every throwable
     * latches the far hook off; by construction this catch touches no
     * drop counter and no drawer state.
     */
    private static void drainFarFieldLocked(TerrainGpuHost gpu) {
        // The deferred eviction flush runs FIRST and runs even when the
        // hook is broken. Those positions are already freed; what they
        // still owe is one gauge decrement each. Three producers append
        // here (force-evict, empty recompile, vanilla supersede) with no
        // broken check, so skipping the flush on a latch would leave the
        // far gauges permanently over-reporting for the session. It only
        // touches LongAdders and a render-thread map, calls no vanilla
        // code, and cannot re-enter the far path.
        if (!farFreedPending.isEmpty()) {
            try {
                for (int i = 0; i < farFreedPending.size(); i++) {
                    long pos = farFreedPending.getLong(i);
                    // posPack's 21-bit fields, sign-extended back out.
                    int sx = (int) (pos >>> 42) << 11 >> 11;
                    int sy = (int) (pos >>> 21) << 11 >> 11;
                    int sz = (int) pos << 11 >> 11;
                    com.deds.meshelium.farfield.FarFieldResidency.onSectionEvicted(sx, sy, sz);
                }
            } catch (Throwable t) {
                farHookBroken = true;
                MesheliumLog.LOGGER.error(
                        "Meshelium far-field eviction flush failed; the far field is off "
                                + "for this session (near-field rendering is unaffected)", t);
            }
            farFreedPending.clear();
        }
        if (!successorRequests.isEmpty()) {
            drainSuccessorRequestsLocked();
        }
        if (farHookBroken) {
            return;
        }
        if (!farEverArmed) {
            // Zero-cost-off: short-circuit BEFORE naming the walker, or
            // its clinit would run here (inside the lock) in a session
            // that never enabled the far field. The latch is set by
            // pumpFarFieldOutsideLock, which runs after this drain, so
            // the first armed pump simply skips a drain that has nothing
            // pending anyway.
            return;
        }
        if (!com.deds.meshelium.farfield.FarFieldResidency.admissionsPending()) {
            return;
        }
        try {
            int budget = FAR_ADMISSIONS_PER_PUMP;
            // B2: the fill guard, sampled ONCE for the whole drain.
            //
            // Once rather than per admission because the expensive half of
            // it is two system-property reads inside
            // MesheliumScaling.arenaCeilingBytes(), and this runs under
            // LOCK. The cost of sampling once is a bounded OVERSHOOT: at
            // most FAR_ADMISSIONS_PER_PUMP sections may be admitted after
            // the gate would have closed, i.e. at most 64 new regions in
            // the pathological case where no two share one (a region is
            // 8x4x8 sections and a column's sections mostly share theirs,
            // so the real figure is one or two). The gate already stands
            // 700 regions below the hard maximum at render distance 32, so
            // an overshoot of that size cannot reach the ceiling, and the
            // NEXT pump samples a closed gate and refuses everything.
            boolean fillHasRoom = farFillHasRoomLocked();
            while (budget-- > 0) {
                com.deds.meshelium.farfield.FarFieldResidency.Admission a =
                        com.deds.meshelium.farfield.FarFieldResidency.pollAdmission();
                if (a == null) {
                    break;
                }
                int outcome = admitFarSectionLocked(gpu, a, fillHasRoom);
                com.deds.meshelium.farfield.FarFieldResidency.onAdmissionOutcome(outcome);
                if (outcome == com.deds.meshelium.farfield.FarFieldResidency.ADMIT_DEFER) {
                    break; // staging ring full: the rest is next pump's backlog
                }
            }
        } catch (Throwable t) {
            farHookBroken = true;
            com.deds.meshelium.farfield.FarFieldResidency.latchBroken("far admission drain", t);
        }
    }

    /**
     * SEAM step 4: hand each filed successor demand to the walker
     * (render thread, under LOCK, inside the pump's far drain). A
     * position lands here when a free of it PARKED (the coverage park -
     * its copy is AWAITING and keeps drawing) or when a queued vanilla
     * successor died over a covered parked copy: either way the far
     * field is owed a replacement there and nothing else knows it. The
     * walker takes it through {@code onFarBlockerReleased} - the pre6
     * wakeup, kept verbatim as the unguarded priority path into
     * {@code unblockedColumns}.
     *
     * <p>NO occupancy re-check, deliberately, where the deleted watch
     * drain had one: the parked owner IS occupancy, and it is exactly
     * the state that wants a successor - re-arming on it was the old
     * drain's job because a watch entry meant "somebody ELSE holds it".
     * The one skip kept is {@code successorQueued}: vanilla's own
     * rebuild is the replacement then, and a far read would be spent to
     * be told ADMIT_CONTESTED. If that successor later dies un-landed,
     * {@code pendingPosDrop} re-files the demand.</p>
     *
     * <p>Placed before the {@code farHookBroken} return in the drain so
     * a latched hook still frees this state instead of accumulating it.
     * Reached only with a non-empty queue, which can only happen after
     * {@link #coveredNow} answered yes - and that answers no until the
     * walker has published coverage geometry, so naming the walker here
     * cannot break the zero-cost-off posture (a session that never
     * enabled the far field never executes any of it).</p>
     */
    private static void drainSuccessorRequestsLocked() {
        if (farHookBroken) {
            successorRequests.clear();
            return;
        }
        try {
            for (int i = 0; i < successorRequests.size(); i++) {
                long pos = successorRequests.getLong(i);
                // posPack's 21-bit fields, sign-extended back out.
                int sx = (int) (pos >>> 42) << 11 >> 11;
                int sy = (int) (pos >>> 21) << 11 >> 11;
                int sz = (int) pos << 11 >> 11;
                if (successorQueued(sx, sy, sz)) {
                    continue; // vanilla's own rebuild is the successor
                }
                com.deds.meshelium.farfield.FarFieldResidency
                        .onFarBlockerReleased(sx, sy, sz);
            }
        } catch (Throwable t) {
            farHookBroken = true;
            MesheliumLog.LOGGER.error(
                    "Meshelium far-field successor-request flush failed; the far field is "
                            + "off for this session (near-field rendering is unaffected)", t);
        }
        successorRequests.clear();
    }

    /**
     * Admit ONE far section: the {@link #drainPendingUploadsLocked}
     * admission sequence minus everything mesh-identity — arena alloc,
     * staged copy, Resident with a snapshot slot, region record, then
     * straight into {@link #farResident} with the retained mask bit set
     * and {@code orphanedAtMillis} stamped (which is what writes
     * snapshot {@code [19]} = 1 through both the incremental log and
     * the full rebuild — the wave-11 draw paths need nothing else).
     * Deliberate differences from the live path, each per the W3 brief:
     * <ul>
     *   <li>NO arena growth and NO forced eviction on failure — far
     *       fill never spends anyone else's budget; a refusal is a
     *       walker stall, not a drop (no guard counter moves);</li>
     *   <li>positions still owned by real geometry are SKIPPED: a
     *       retained copy (real terrain outranks a shell approximation)
     *       or a queued vanilla upload at the position wins. Since the
     *       pre2 B3 fix the inner ring edge is vanilla's compile DISC
     *       rather than the grid square, so far columns routinely land
     *       INSIDE the vanilla grid and a live owner is expected, not
     *       impossible: the {@code isOccupied} pre-flight below is what
     *       turns that into a skip, and the throw remains only as the
     *       assertion that the pre-flight ran.</li>
     * </ul>
     *
     * <h2>Two kinds of skip (pre6 fix)</h2>
     * The refusals above are not one outcome, they are two, and
     * conflating them is what left permanent holes in the ring:
     * <ul>
     *   <li>{@code ADMIT_SKIPPED} — the position already holds a FAR
     *       section. Nothing to recover: the far field's own copy is
     *       there, and every retry would report the same thing forever
     *       (which is precisely why the walker refuses to treat a skip
     *       as a hole). SEAM step 2 adds the one exception: an admission
     *       carrying the walker's {@code refresh} mark REPLACES our own
     *       far copy in place through the {@code previousOwner} swap and
     *       reports {@code ADMIT_REPLACED} — free and bind in this same
     *       lock hold, one {@code drawEpoch} bump, so the position is
     *       drawable in every published snapshot across the
     *       exchange.</li>
     *   <li>{@code ADMIT_CONTESTED} — a NEAR-FIELD owner holds it. That
     *       is "not yours FOR NOW", not "not yours": the retained copy
     *       gets evicted, the queued upload gets discarded or lands and
     *       is later released, the live section unloads. Each of those
     *       is a real event on a real code path, and since step 4 the
     *       event ANNOUNCES ITSELF: a free of the position, if it is
     *       still covered then, parks and files {@code successorRequests}
     *       (the one channel kept from the deleted contested watch), so
     *       there is no watch to arm, no cap for it to fall off, and no
     *       occupancy to re-check.</li>
     *   <li>{@code ADMIT_NO_BUDGET} — the absolute ceilings, or (B2) the
     *       FILL GUARD: {@code fillHasRoom} was false and this admission
     *       is net-new rather than a bridged swap. Recoverable in exactly
     *       the same way, and by the same machinery.</li>
     * </ul>
     *
     * @param fillHasRoom {@code farFillHasRoomLocked()} for this pump,
     *                    sampled once by the drain. Ignored for a bridged
     *                    position and for a seam-step-2 replacement -
     *                    both are swaps and end below where they started.
     */
    private static int admitFarSectionLocked(TerrainGpuHost gpu,
            com.deds.meshelium.farfield.FarFieldResidency.Admission a,
            boolean fillHasRoom) {
        int sx = a.sx();
        int sy = a.sy();
        int sz = a.sz();
        long pos = posPack(sx, sy, sz);
        // SEAM step 2: an admission carrying the walker's refresh mark
        // may REPLACE our own resident far copy - the previousOwner swap
        // below, generalised from the bridged exchange to a FAR
        // predecessor. Without the mark, already-ours stays the terminal
        // skip it always was (re-uploading identical geometry on every
        // false wakeup is exactly what the skip exists to prevent).
        Resident farPrev = farResident.get(pos);
        if (farPrev != null && !a.refresh()) {
            // Already ours. Terminal, and the one refusal that is.
            return com.deds.meshelium.farfield.FarFieldResidency.ADMIT_SKIPPED;
        }
        boolean farReplace = farPrev != null;
        if (farReplace && successorQueued(sx, sy, sz)) {
            // Vanilla's own rebuild is queued at this position: the
            // wave-11 supersede arm binds it atomically over the far copy
            // within a pump, so a refresh admitted now would be freed
            // again immediately. SKIPPED, not CONTESTED - there is no
            // watch to arm on a position we ourselves own, and if vanilla
            // later leaves, the ordinary handover machinery re-reads the
            // column with the fresh shell already on disk.
            return com.deds.meshelium.farfield.FarFieldResidency.ADMIT_SKIPPED;
        }
        // The one retained entry a far admission may take rather than
        // defer to: a PARKED copy - real geometry vanilla has already let
        // go of, held drawing precisely until this arrives. Since step 4
        // the test IS the ledger byte (the design's own words: the
        // bridged test becomes ledgerState == AWAITING_SUCCESSOR), with
        // one exclusion - a parked copy whose VANILLA successor is
        // queued belongs to that rebuild, not to us (the wave-11
        // supersede arm is its atomic bind). A NEAR_HELD copy is still
        // vanilla's business and still outranks a shell approximation.
        // (A farReplace position can be in none of these maps - one owner
        // per position, and the owner is farPrev - so it skips the arm.)
        Resident held = farReplace ? null : retained.get(pos);
        boolean bridged = held != null
                && held.ledgerState == LEDGER_AWAITING_SUCCESSOR
                && !successorQueued(sx, sy, sz);
        if (!farReplace
                && ((held != null && !bridged) || successorQueued(sx, sy, sz))) {
            // No watch to arm (step 4): when this owner frees, the park
            // itself files the demand. CONTESTED stays the honest answer
            // - recoverable, and something WILL announce the recovery.
            return com.deds.meshelium.farfield.FarFieldResidency.ADMIT_CONTESTED;
        }
        // The ownership pre-flight that makes the collision throw below
        // UNREACHABLE. The two map checks above cannot see a LIVE owner
        // (the live map is mesh-identity-keyed, so it has no position
        // lookup), and discovering the clash from addOrReplace's return
        // is far too late: that call rebinds ownerByPos and overwrites
        // the GPU mirror record with THIS arena address before returning,
        // so the throw would strand a live section whose slot points at
        // far geometry that the epochs later recycle. Reachable in the
        // window where the effective render distance shrinks (a server
        // lowering its view distance moves it with no allChanged) while
        // vanilla still holds live sections further out. O(1).
        if (!bridged && !farReplace
                && regionStore.isOccupied(RegionStore.regionKey(sx, sy, sz),
                RegionStore.posKey(sx, sy, sz))) {
            // A LIVE owner (the two maps above ruled out far and
            // retained), so this is the contested case in its purest
            // form: the section is drawn by vanilla right now and the
            // far copy is wanted the moment it stops being.
            //
            // H2 note: "drawn by vanilla right now" is exactly what
            // syncUnlistedLiveMaskLocked makes true again. Before it, a
            // live owner outside vanilla's compile disc was refused HERE
            // and drawn by nobody; the refusal was always correct — the
            // slot really is taken — and the defect was on the drawing
            // side, which is where it is now fixed.
            return com.deds.meshelium.farfield.FarFieldResidency.ADMIT_CONTESTED;
        }
        // B2, THE FILL GUARD, AND THE ONLY PLACE IT CAN HONESTLY LIVE
        // (pre12 adversarial review, item B2).
        //
        // Reaching here means the position is genuinely free, so this
        // admission is one of exactly two things and they have opposite
        // memory signs. A BRIDGED one is a SWAP: the region record already
        // exists because the near field owned this position a moment ago,
        // and the exchange frees a real section (61 KB on the arena, by
        // docs/PERFORMANCE.md's spin measurement) to house a shell, so it
        // ends strictly BELOW where it started and no guard should stop
        // it. Anything else is NET-NEW fill, and net-new fill is exactly
        // what farFillHasRoomLocked() exists to stop.
        //
        // Putting the test here rather than back on the producer is the
        // review's own prescription and it is right: the producer works in
        // COLUMNS ({@code drainUnblockedColumns} issues one read per
        // column) while a bridge is per SECTION, so a producer-side test
        // can only be all-or-nothing and either re-breaks the M4 handover
        // or re-breaks the pre6 contested-watch recovery. Down here the
        // difference is a field of the admission itself.
        //
        // NO_BUDGET rather than DEFER on purpose: DEFER holds the meshed
        // column in {@code pendingAdmissions} and retries the same section
        // next pump, which would pin every refused column's mesh for as
        // long as the guard stayed closed. NO_BUDGET drains the queue,
        // frees the mesh and marks the column {@code incomplete}, which is
        // what lets the ring walk come back for it once the guard opens —
        // the same contract the capacity refusal below already has.
        // SEAM step 2: a REPLACEMENT is exempt exactly like a bridged
        // swap and by the same sign argument - it frees as much as it
        // binds (its predecessor's arena range parks in this very hold),
        // so it ends where it started and the guard has nothing to
        // protect. The guard stays fully intact for NET-NEW admissions,
        // including the non-resident sections of a refresh column.
        if (!bridged && !farReplace && !fillHasRoom) {
            return com.deds.meshelium.farfield.FarFieldResidency.ADMIT_NO_BUDGET;
        }
        EncodedSectionMesh encoded = a.mesh();
        if (!regionStore.hasCapacityFor(sx, sy, sz)) {
            return com.deds.meshelium.farfield.FarFieldResidency.ADMIT_NO_BUDGET;
        }
        int addr = arena.allocQuads(encoded.quadCount());
        if (addr == TerrainArena.ALLOC_FAILED) {
            return com.deds.meshelium.farfield.FarFieldResidency.ADMIT_NO_BUDGET;
        }
        if (!gpu.stageArenaCopy(encoded.geometry(), arena.blockOf(addr),
                arena.byteOffsetInBlock(addr))) {
            // Nothing recorded — the same clean undo as the live drain.
            arena.free(addr);
            arena.releasePending();
            return com.deds.meshelium.farfield.FarFieldResidency.ADMIT_DEFER;
        }
        int[] bucketStarts = new int[com.deds.meshelium.terrain.QuadFacingBuckets.BUCKET_COUNT];
        int[] bucketCounts = new int[com.deds.meshelium.terrain.QuadFacingBuckets.BUCKET_COUNT];
        for (int b = 0; b < bucketStarts.length; b++) {
            bucketStarts[b] = encoded.bucketStart(b);
            bucketCounts[b] = encoded.bucketCount(b);
        }
        Resident r = new Resident(addr, encoded.quadCount(),
                RegionStore.regionKey(sx, sy, sz), RegionStore.posKey(sx, sy, sz),
                sx, sy, sz, bucketStarts, bucketCounts,
                null /* no translucent prefix in pre1 */, allocSnapshotSlotLocked());
        // Far copies are orphan-like from birth: no mesh will ever
        // release them, and the stamp is exactly what flags snapshot
        // [19] for the retained pre-pass/mask machinery.
        r.orphanedAtMillis = monotonicMillis();
        snapshotSlotOwner[r.snapshotSlot] = r;
        RegionStore.Assignment assignment =
                regionStore.addOrReplace(sx, sy, sz, encoded, addr, r);
        if (assignment == null) {
            // Copy already recorded: leak the range (bounded — the far
            // latch stops all future admissions) rather than stack an
            // unbarriered same-frame free/realloc; the pre-flight makes
            // this unreachable, same as the live drain's argument.
            throw new IllegalStateException(
                    "far region assignment failed after capacity pre-flight");
        }
        if (assignment.previousOwner() != null) {
            // H3, THE SWAP ITSELF. Two previous owners a far admission
            // may find since seam step 2: the bridge this position was
            // put on when vanilla let go of it, or (refresh) our own far
            // copy being replaced by a fresher shell's mesh. Either is
            // freed exactly the way a fresh live
            // upload frees a superseded retained copy
            // (drainPendingUploadsLocked's wave-11 arm) — addOrReplace has
            // already rebound ownerByPos and cleared the mask bit, so only
            // the arena range and the snapshot slot are left to release,
            // and both go through the fence epochs like every free.
            //
            // The atomicity that makes this seamless is not subtle and is
            // worth stating: the free and the bind are the same statement
            // sequence inside one LOCK hold, published as ONE draw-epoch
            // bump, so no snapshot the drawer can ever observe has the
            // position unowned. The real section draws in every frame up
            // to the swap and the shell draws in every frame after it.
            Resident previous = assignment.previousOwner() instanceof Resident p ? p : null;
            if (farReplace) {
                // SEAM step 2, THE REPLACEMENT: the same atomicity as the
                // bridged swap below, with our own FAR copy as the
                // predecessor. addOrReplace has already rebound ownerByPos
                // and overwritten the mirror record, so - exactly like the
                // wave-11 vanilla-precedence arm - only the arena range
                // and the snapshot slot are left to free, and both go
                // through the fence epochs. Free and bind in one LOCK
                // hold, published under the single drawEpoch bump at the
                // tail: no snapshot the drawer can ever observe has the
                // position unowned, which is what "an edited column
                // updates on screen without ever leaving it" means.
                if (previous == null || previous != farPrev) {
                    // The occupant is not the far copy this admission was
                    // sized against - the structural impossibility the
                    // farPrev probe keeps unreachable; latch rather than
                    // silently steal a slot.
                    throw new IllegalStateException(
                            "far refresh displaced a stranger at "
                                    + sx + "," + sy + "," + sz);
                }
                previous.ownsSlot = false;
                pendingPrefixUploads.remove(previous);
                // SEAM shadow: FAR -> FAR via replacement - predecessor
                // FAR -> CLEARED (a legal-table edge, see the Resident
                // block comment) resolved by the swap, successor bound
                // FAR at the tail; counted on ledgerResolvedSwap like
                // every previousOwner exchange.
                ledgerResolvedSwap++;
                ledgerTransitionLocked(previous, LEDGER_CLEARED);
                parkAddrLocked(previous.arenaAddr, previous.quadCount);
                freeSnapshotSlotLocked(previous);
                // Deliberately NOT farFreedPending: onSectionEvicted would
                // strike the section from the walker's manifest, and the
                // manifest row is still right - same section, fresh
                // geometry. The walker is told through ADMIT_REPLACED
                // instead, which moves no residency gauge (the exchange
                // is net-zero) and re-enters nothing. The bind falls
                // through to the shared tail below - the same statement
                // sequence, the same single epoch bump.
            } else if (!bridged || previous == null || previous != held) {
                // Anything else here is the structural impossibility the
                // pre-flight exists to keep unreachable; latch rather than
                // silently steal a live slot.
                throw new IllegalStateException(
                        "far admission found an owned slot at " + sx + "," + sy + "," + sz);
            } else {
                retained.remove(pos);
                previous.ownsSlot = false;
                pendingPrefixUploads.remove(previous);
                retainedQuads -= previous.quadCount;
                freedSections++;
                // THE SWAP - the one exchange the invariant permits at a
                // covered position. Predecessor resolves out of AWAITING
                // in the same hold its successor binds FAR below; the
                // ledger transition carries the hold-time arithmetic that
                // replaced the P9 swapHold pair (MISS holds included).
                ledgerResolvedSwap++;
                ledgerTransitionLocked(previous, LEDGER_CLEARED);
                parkAddrLocked(previous.arenaAddr, previous.quadCount);
                freeSnapshotSlotLocked(previous);
            }
        } else if (farReplace) {
            // farPrev was read from farResident in THIS lock hold, so the
            // region slot must have held it; an empty slot here means the
            // two structures disagree, and binding over it would leak the
            // predecessor's arena range. Latch, same posture as above.
            throw new IllegalStateException(
                    "far refresh found no previous owner at " + sx + "," + sy + "," + sz);
        }
        regionStore.markRetained(r.regionKey, r.posKey, r);
        farResident.put(pos, r); // for a replacement this rebinds over farPrev
        ledgerTransitionLocked(r, LEDGER_FAR); // far admission bind
        logSlotWriteLocked(r.snapshotSlot);
        drawEpoch++; // ONE bump - swap and bind publish together
        stagedBytesThisPump += encoded.geometryBytes();
        return farReplace
                ? com.deds.meshelium.farfield.FarFieldResidency.ADMIT_REPLACED
                : com.deds.meshelium.farfield.FarFieldResidency.ADMIT_OK;
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
                    // W3: the far horizon is even cheaper than the
                    // retained one, so it is spent FIRST — a live
                    // section must never drop while far decoration
                    // holds ids (landmine L4's ladder, extended down).
                    if (forceEvictFarLocked() || forceEvictRetainedLocked()) {
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
                    // failed if we are here), far-then-retained eviction
                    // second (wave-11 shape: requeue, no drop counter
                    // moves; W3 spends the far horizon before the
                    // retained one), the honest wave-8 drop LAST — the
                    // guard now trips only when growth is
                    // exhausted-or-impossible AND nothing far or
                    // retained is left to evict.
                    if (forceEvictFarLocked() || forceEvictRetainedLocked()) {
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
                // The snapshot slot is assigned at this SINGLE Resident
                // construction site (per arena copy, not per position — the
                // handover window holds two slots for one section). If the
                // iteration dies between here and resident.put, the slot
                // leaks until dispose exactly like the already-recorded
                // copy's arena range (the pre-flight comment above) —
                // bounded, and the full rebuild tombstones it either way.
                Resident r = new Resident(addr, p.encoded().quadCount(),
                        RegionStore.regionKey(p.sx(), p.sy(), p.sz()),
                        RegionStore.posKey(p.sx(), p.sy(), p.sz()),
                        p.sx(), p.sy(), p.sz(), bucketStarts, bucketCounts,
                        p.translucent(), allocSnapshotSlotLocked());
                if (p.builtTier() != SectionBuildTap.TIER_NONE) {
                    r.builtTier = p.builtTier();
                    tierBuiltFlagged++;
                }
                snapshotSlotOwner[r.snapshotSlot] = r;
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
                        long prevPos = posPack(previous.sx, previous.sy, previous.sz);
                        // W3 vanilla precedence: a real compile landed at
                        // a FAR-resident position (render distance raised,
                        // ring lag) — the far copy yields through the same
                        // slot steal retained copies use. Its walker
                        // bookkeeping rides the deferred queue; the region
                        // slot is already rebound, so only the arena range
                        // and snapshot slot free here.
                        Resident farGone = farResident.remove(prevPos);
                        if (farGone != null) {
                            previous.ownsSlot = false;
                            if (farGone == previous) {
                                // SEAM shadow: vanilla precedence over a
                                // far copy is FAR -> CLEARED (the cause is
                                // already counted one line below).
                                ledgerTransitionLocked(previous, LEDGER_CLEARED);
                                parkAddrLocked(previous.arenaAddr, previous.quadCount);
                                freeSnapshotSlotLocked(previous);
                                farFreedPending.add(prevPos);
                                // The handover the walker now RELIES on:
                                // since the inner-edge distance demote was
                                // removed, this is the only path that
                                // retires a far copy vanilla has covered.
                                // A travel leg that leaves this at zero
                                // means far copies are piling up inside
                                // the near field instead.
                                com.deds.meshelium.farfield.FarFieldResidency
                                        .farSupersededByVanilla.increment();
                            } else {
                                // Two owners at one position — a Meshelium
                                // bug; heal like the retained twin below.
                                forceSnapshotRebuildLocked();
                            }
                        } else {
                            // Wave-11 supersede: the previous owner is a
                            // RETAINED copy — nothing will ever release it
                            // again (its mesh is long dead), so the fresh
                            // upload frees it here, through the epochs.
                            // addOrReplace already cleared the retained mask
                            // bit and rebound the slot to the new owner.
                            Resident gone = retained.remove(prevPos);
                            previous.ownsSlot = false;
                            if (gone == previous) {
                                // SEAM shadow: vanilla's own re-upload is
                                // the RESOLVED-VANILLA ending for a parked
                                // copy - the wave-11 arm was already an
                                // atomic bind, which is why the design
                                // keeps it verbatim. A plain held copy
                                // superseding out is an ordinary clear.
                                if (previous.ledgerState == LEDGER_AWAITING_SUCCESSOR) {
                                    ledgerResolvedVanilla++;
                                }
                                ledgerTransitionLocked(previous, LEDGER_CLEARED);
                                pendingPrefixUploads.remove(previous);
                                retainedQuads -= previous.quadCount;
                                freedSections++;
                                retainedSuperseded++;
                                parkAddrLocked(previous.arenaAddr, previous.quadCount);
                                freeSnapshotSlotLocked(previous);
                            } else {
                                // Impossible by the retained-map invariant (an
                                // orphaned owner IS the entry at its own
                                // position) — heal like the decision-8 check
                                // rather than trusting a broken map.
                                forceSnapshotRebuildLocked();
                            }
                        }
                    } else {
                        previous.ownsSlot = false;
                        // The slot-steal victim (promotion-lag window):
                        // nothing removes the previous entry — only its
                        // [18] flips to −1, which a slot log cannot see
                        // without this record (S1(b), mutations dossier).
                        logSlotWriteLocked(previous.snapshotSlot);
                    }
                }
                resident.put(mesh, r);
                ledgerTransitionLocked(r, LEDGER_NEAR_LIVE); // upload bind
                // S6: addOrReplace above cleared this position's mask bit
                // and this Resident is born with unlistedDrawn false, so
                // without this line the section has NO mark until the
                // camera next crosses a section boundary - the second hole
                // (see applyUnlistedVerdictLocked). It must run BEFORE the
                // log write below, which is the record that publishes the
                // [19] it may have just set.
                applyUnlistedVerdictLocked(r);
                logSlotWriteLocked(r.snapshotSlot); // the new entry, same S1 bump
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
            MesheliumLog.LOGGER.error(
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
     * The far walker's own half of the seam line, or {@code ""} when the
     * far field has never armed or has latched.
     *
     * <p>Reads {@code FarFieldResidency}'s public adders directly, which
     * is a class reference from inside the pump's lock — hence the two
     * guards and the catch. {@code farEverArmed} means the class is
     * already loaded and running (the pump's own arming path proved it),
     * so this can neither trigger a class load under LOCK nor resurrect a
     * latched far field. Diagnostics only: a throwable here must never
     * reach the pump, so it degrades to an empty string.</p>
     */
    private static String farSeamCountersLocked() {
        if (!farEverArmed || farHookBroken) {
            return ""; // never loaded, or latched: say nothing
        }
        try {
            return " far[resident=" + com.deds.meshelium.farfield.FarFieldResidency
                    .farSectionsResident.sum()
                    + " requests=" + com.deds.meshelium.farfield.FarFieldResidency
                            .farShellRequests.sum()
                    + " admissions=" + com.deds.meshelium.farfield.FarFieldResidency
                            .farAdmissions.sum()
                    + " contested=" + com.deds.meshelium.farfield.FarFieldResidency
                            .farContestedSections.sum()
                    + " noBudget=" + com.deds.meshelium.farfield.FarFieldResidency
                            .farAdmitNoBudget.sum()
                    + " budgetStalls=" + com.deds.meshelium.farfield.FarFieldResidency
                            .farPromoteBudgetStalls.sum()
                    + " holesFiled=" + com.deds.meshelium.farfield.FarFieldResidency
                            .farHolesFiled.sum()
                    + " holesDrained=" + com.deds.meshelium.farfield.FarFieldResidency
                            .farHolesDrained.sum()
                    + " idleRescans=" + com.deds.meshelium.farfield.FarFieldResidency
                            .farIdleRescans.sum()
                    + " retryBackoffs=" + com.deds.meshelium.farfield.FarFieldResidency
                            .farHoleRetryBackoffs.sum()
                    + "]";
        } catch (Throwable ignored) {
            return ""; // diagnostics must never reach the pump
        }
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
        MesheliumLog.LOGGER.info(
                "meshelium residency: sections={} quads={} retained={}/{} quads arena={}/{} MiB "
                        + "(ceiling {} MiB, growths={}, trims={}, growthFailures={}) "
                        + "holes={} MiB tail={} MiB emptyTopBlocks={}/{} "
                        + "regions={} (dirty {}) stagingBacklog={} entries/{} KiB ringUsed={} KiB "
                        + "freesPending={} encoded={} uploaded={} "
                        + "drops[oversize={},arena={},region={},encode={}] "
                        + "retention[orphaned={},superseded={},evictAge={},evictPressure={},"
                        + "evictOff={},backpressure={}] handoverHeld={} discarded={} staleParks={} "
                        + "snapshot[publishes={},fullRebuilds={},logRecords={}] "
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
                // The incremental snapshot's health: publishes should dwarf
                // fullRebuilds outside world loads and rd raises, or the
                // delta log is not doing its job.
                snapshotPublishes, snapshotFullRebuilds, snapshotLogRecords,
                // The other side of the merge's ledger: what it costs the
                // build workers. Off the frame path, so no bench frame time
                // can show it, but slower section builds are slower pop-in.
                com.deds.meshelium.terrain.GreedyMesher.costSummary()
                        + " " + com.deds.meshelium.terrain.GreedyMesher.quadSummary());
        // The near-to-far SEAM, on the ledger's counter surface (the O6
        // refusal census died with the mechanisms it was counting - a
        // refusal no longer exists to refuse). Read it as: parked is
        // holds opened, resolved[swap/vanilla/uncovered] is holds ended
        // the three legitimate ways, and evictedWall is THE number - the
        // one sanctioned violation, zero except under genuine memory
        // pressure, with ring/n saying how deep in the fog it landed.
        // demoted is a DEAD edge and must print 0 forever; illegal is
        // the table contract, also 0. awaitingQuads against its budget
        // is the storm gauge (peak prices the whole phase). holdMs
        // spans EVERY resolution, MISS holds included - the P9 pair,
        // generalised. unlistedAltitude is the O7 fix's whole cost,
        // live. P2's bfsRelax group gained loSy (R6): the arm's input,
        // the p10 terrain floor — armed should flip when the camera's
        // section Y clears loSy + 3, and topSy - loSy is the band the
        // p90 arm used to lag by. S2 added unlisted[band=], the wide
        // rule's own population (vanilla's HORIZONTAL disjunct, i.e. the
        // 64-block climb window three Y-keyed fixes never covered).
        //
        // S6 retired that rule's ARM and replaced it with a per-column
        // test, so the pair to read changed with it:
        //   * unlisted[horizon=] is how many resident entries sit in a
        //     column whose top is below the camera's section - what the
        //     local horizon could see. It replaces bfsRelax[aboveTop=],
        //     a disc-wide `camSy > p90` boolean that stayed DOWN over the
        //     owner's water because the same disc held a mountain.
        //   * unlisted[bind=b/t] is the marks made at UPLOAD BIND rather
        //     than by the sweep - the second hole - as the band rule's
        //     own b out of a total t. Nonzero on any climb through
        //     streaming terrain; 0 means either nothing uploaded between
        //     two camera crossings or the bind path is not running, and
        //     the two are worth telling apart.
        //   * unlisted[core=] (pre21) is the band rule's marks INSIDE the
        //     7x7 adjacency core, which S2 had excluded - the seventh
        //     fly-up fix's whole population. At render distance 2 it is
        //     all of band; elsewhere a few dozen at most, nearly all of
        //     them sections vanilla lists anyway.
        // Read band against listed: band rising while listed collapses
        // AND the picture stays clean is the fix working; band rising
        // while the picture still holes means the missing positions were
        // never COMPILED, which no mask can cure and which sends the next
        // wave to listing or far coverage instead. And no counter here
        // reports ABSENCE - only TerrainResidency.auditUnlistedCoverage
        // does, and only when something asks it to.
        MesheliumLog.LOGGER.info(
                "meshelium seam: ledger[parked={} resolved[swap={} vanilla={} "
                        + "uncovered={}] evictedWall={} wallRingSum={} demoted={} "
                        + "illegal={} awaitingQuads={}/{} peak={} holdMs[sum={} "
                        + "max={}]] unlisted[live={} altitude={} band={} core={} "
                        + "horizon={} bind={}/{} maskFlips={} "
                        + "transFlips={}] bfsRelax[armed={} armSweeps={} "
                        + "topSy={} loSy={} suppressed={} listed={} lo={} hi={}]{}",
                ledgerParked, ledgerResolvedSwap, ledgerResolvedVanilla,
                ledgerResolvedUncovered, ledgerEvictedWall, ledgerEvictedWallRingSum,
                ledgerDemoted, ledgerIllegalTransitions,
                ledgerAwaitingQuads, awaitingBudgetQuads, ledgerAwaitingQuadsPeak,
                ledgerHoldMillis, ledgerHoldMaxMillis,
                unlistedLiveDrawn, unlistedAltitudeDrawn, unlistedBandDrawn,
                unlistedBandCoreDrawn,
                unlistedHorizonEntries, unlistedBindBandMarks, unlistedBindMarks,
                unlistedMaskFlips, unlistedTransFlips,
                altitudeCullRelaxed, altitudeArmSweeps,
                unlistedTerrainTopSy, unlistedTerrainLowSy,
                bfsAdvancedCullRelaxed,
                SectionBuildTap.visibleSectionsListed(),
                SectionBuildTap.visibleSectionsListedMin(),
                SectionBuildTap.visibleSectionsListedMax(),
                farSeamCountersLocked());
        // The other half of the terrain bill: vanilla's own copy, which
        // nothing draws while Meshelium owns the frame. Measured, not
        // derived - see VanillaTerrainCensus.
        long vanillaBytes = com.deds.meshelium.VanillaTerrainCensus.committedBytes();
        long arenaBytes = c.arenaCapacityBytes();
        MesheliumLog.LOGGER.info(
                "meshelium terrain bill: meshelium {} MiB, VANILLA {} MiB{} (vanilla keeps a full "
                        + "second copy that nothing draws while Meshelium owns the frame)",
                arenaBytes >> 20,
                vanillaBytes < 0 ? -1 : vanillaBytes >> 20,
                vanillaBytes > 0 && arenaBytes > 0
                        ? String.format(" = %.2fx ours", (double) vanillaBytes / arenaBytes) : "");
        long budget = com.deds.meshelium.MesheliumVramState.budgetBytes();
        MesheliumLog.LOGGER.info(
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
