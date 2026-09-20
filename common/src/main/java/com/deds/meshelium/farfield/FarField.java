/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.farfield;

import com.deds.meshelium.MesheliumPlatform;
import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.farfield.store.FarFieldStore;
import com.deds.meshelium.farfield.store.ShellCodec;


import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

/**
 * The far field's facade: every other wave talks to the store through
 * the static entry points here, and NOTHING else in the mod may touch
 * {@code farfield.store} directly. All entry points are no-ops while the
 * master toggle ({@code FarFieldConfig.enabled()}) is off.
 *
 * <h2>The zero-cost-off contract (FAR-FIELD-DESIGN.md section 6)</h2>
 * Off is the default and off means: no thread exists, no folder is
 * created, no queue or store is allocated. Everything lives behind one
 * lazily-created {@link Io} object, so {@link #isCompletelyIdle()} is a
 * single null check and the suite's zero-cost leg can assert it after a
 * full armed run with the master off. The only allocations that exist
 * regardless are the public counter objects below, one
 * {@link AtomicBoolean} guarding the W4 size walk, the (empty)
 * write-acknowledgement queue, and (M5) the store-generation counter -
 * they are the observability and administration surface the same leg
 * reads, so they must exist to be read. Class-loading this class costs
 * eight LongAdders, one AtomicBoolean, one AtomicLong, one empty
 * ConcurrentLinkedQueue and nothing else.
 *
 * <p><b>Class-loading discipline (W4's zero-cost leg asserts it).</b>
 * This class NAMES {@code FarFieldResidency} in four method bodies
 * ({@link #onWorldJoin}, {@link #onWorldLeave}, {@link #submitShell},
 * {@link #onPinShellAcked}),
 * and every one of them is behind a guard that cannot pass while the
 * master switch has never been on - the enabled() check or the
 * {@code io == null} check. Java resolves a constant-pool method
 * reference at first EXECUTION, so with the far field off that symbol is
 * never resolved and the walker class is never defined. The W4 gametest
 * leg proves it with {@code ClassLoader.findLoadedClass}; anything added
 * here that reaches the walker outside those guards would fail that leg,
 * which is exactly the point. The cache-administration entry points
 * below are held to the same rule: the Far Terrain screen must be
 * openable, measurable and clearable with the master off, so none of
 * them touches the residency.</p>
 *
 * <h2>The mod's first owned thread</h2>
 * FARFIELD-CODEBASE-SEAM.md section 6.4: Meshelium owns ZERO threads
 * today - every existing code path rides vanilla's build workers, the
 * render thread, or the client tick. The single IO thread here
 * ("meshelium-farfield-io", daemon) is therefore the first, and its
 * discipline is strict:
 * <ul>
 * <li>Created lazily on the first ENABLED {@code onWorldJoin} - never at
 *     class load, never while the master is off. Submits and requests
 *     before a world is joined are answered as drop/null, because
 *     without a world there is no cache identity to file under.</li>
 * <li>All file IO and all {@code FarFieldStore} state live on it;
 *     producers only touch the bounded queue. The render thread NEVER
 *     waits on IO: enqueue is a short uncontended lock, and on overflow
 *     the OLDEST pending write is dropped and counted rather than
 *     blocking the caller.</li>
 * <li>Its uncaught-exception handler increments {@link #farStoreErrors},
 *     parks the far field for the session, and touches NOTHING else -
 *     by construction this class imports no renderer type, so a far-field
 *     failure cannot reach TerrainDrawer or TerrainResidency state, and
 *     in particular can never increment the four wave-8 coverage-guard
 *     drop counters (FARFIELD-CODEBASE-SEAM.md landmine L4: a cache bug
 *     must never turn the whole mod passive).</li>
 * </ul>
 *
 * <h2>Ordering and lifecycle</h2>
 * One FIFO queue serves lifecycle, writes and reads, which is what makes
 * {@code onWorldLeave} a flush: the leave task runs after every write
 * enqueued before it, closes the store, and the loop goes back to
 * waiting - flushed, then parked. Lifecycle tasks are never dropped by
 * the overflow policy (only writes are dropped, oldest first, and reads
 * are answered null past their own cap), so a join or leave cannot be
 * lost under load. A write that arrives between leave and the next join
 * finds no store and is dropped-and-counted.
 *
 * <h2>Counters</h2>
 * Public LongAdders, the far field's OWN failure/throughput accounting
 * (standing rule, docs/unreleased/farfield/FARFIELD-WAVES.md): W4 exports them into the
 * bench counter map and the zero-cost leg asserts them zero with the
 * master off. Cheap to increment from any thread, summed only when read.
 *
 * <h2>The extraction-rule signature, stamped here and checked here (pre20)</h2>
 * This class is the far field's only writer and its only reader, so it
 * is where a record's vintage is decided. {@code WriteTask} stamps
 * {@code FarFieldConfig.farSaveSignature()} into every record on the way
 * out ({@link ShellCodec.Shell#withSaveSignature}) and {@code Io}
 * compares it on the way back in, treating a mismatch - and a record too
 * old to carry one at all - as ABSENT. Before this the signature was
 * compared only against a static in {@code FarFieldResidency} that is
 * reset on every world era, so it re-extracted held columns within a
 * session and never once reached a byte on disk. See
 * {@link #saveSignature} and {@link #farStoreStaleRecords}.
 */
public final class FarField {

    /** Records actually appended to the store (tier-refusals excluded). */
    public static final LongAdder farStoreWrites = new LongAdder();
    /** Read requests serviced by the store (hit or miss). */
    public static final LongAdder farStoreReads = new LongAdder();
    /** Far-field failures of every kind. NEVER the wave-8 drop counters. */
    public static final LongAdder farStoreErrors = new LongAdder();

    /**
     * S1 (pre18, R5): store reads the APRON issued that the decoded-shell
     * cache could not answer - the read amplification the seam fix costs,
     * measured rather than argued.
     */
    public static final LongAdder farApronReads = new LongAdder();
    /** S1: apron neighbours answered from {@link Io#decoded} instead. */
    public static final LongAdder farApronHits = new LongAdder();
    /** S1: apron neighbours the store genuinely has nothing for. */
    public static final LongAdder farApronAbsent = new LongAdder();
    /**
     * pre20: records read back whose stored extraction-rule signature is
     * not the current one, and which were therefore treated as ABSENT.
     * The size of a re-extraction wave, measured rather than argued -
     * and, on any session after the first, the number that says whether
     * the store is still carrying old-vintage bytes.
     */
    public static final LongAdder farStoreStaleRecords = new LongAdder();
    /** Bytes appended to region files (tier prefix included). */
    public static final LongAdder farStoreBytesWritten = new LongAdder();

    /**
     * The extraction-rule signature every record written from now on is
     * stamped with, and every record read back is checked against:
     * {@code FarFieldConfig.farSaveSignature()}, republished once per
     * residency pump.
     *
     * <p><b>Cached rather than called per record on purpose.</b> The
     * writer and the reader both live on the IO thread while the
     * signature's inputs are client options and config rows that only the
     * game thread may read; and one value shared by both sides means a
     * record cannot be stamped with one signature and judged against
     * another in the same instant. {@code volatile} because the two
     * threads are genuinely different, and it is one int.</p>
     *
     * <p>Published by {@code FarFieldResidency.noteAppearanceSettings},
     * which already computes exactly this number once a pump to decide
     * whether to call {@code ExtractDispatch.forgetExtractedColumns()} -
     * so the disk rule and the in-session rule can never drift apart:
     * there is one number and one place it is produced.</p>
     */
    private static volatile int saveSignature;
    private static volatile boolean saveSignatureKnown;

    /**
     * Publish the current extraction-rule signature. Game thread, once
     * per residency pump. See {@link #saveSignature}.
     */
    public static void noteSaveSignature(int signature) {
        saveSignature = signature;
        saveSignatureKnown = true;
    }

    /**
     * The signature to stamp and to check against. Falls back to reading
     * the config directly if no pump has published one yet, which cannot
     * happen on the shipped paths (extraction is driven BY the pump) and
     * costs one config read if it ever does.
     */
    static int saveSignature() {
        if (!saveSignatureKnown) {
            noteSaveSignature(FarFieldConfig.farSaveSignature());
        }
        return saveSignature;
    }
    /**
     * Writes dropped without reaching the store: queue overflow (oldest
     * first), submits with no world joined, submits after the IO thread
     * died. Separate from {@link #farStoreErrors} because a drop under
     * burst load is the overflow policy WORKING, not a malfunction - the
     * harness should see the two move independently.
     */
    public static final LongAdder farStoreDroppedWrites = new LongAdder();
    /**
     * Shells discarded because NO STORE WAS OPEN. Any non-zero value is
     * a bug, not a policy: it means extraction ran and its output went
     * nowhere. It exists because that state was silent for three
     * pre-releases (see {@link #openForCurrentWorld}).
     */
    public static final LongAdder farStoreDroppedNoStore = new LongAdder();
    /**
     * Save-design M1 (docs/unreleased/farfield/FARFIELD-SAVE-DESIGN.md): writes the store
     * ACKNOWLEDGED as durably appended (or as an equal-truth refusal -
     * see {@link WriteTask#run} for why a tier refusal acks OK). The pair
     * {@code farSaveAckOk + farSaveAckFailed} must eventually equal the
     * submits: every {@link #submitShell} call with a non-null shell
     * produces exactly one ack, on every exit of the write path.
     */
    public static final LongAdder farSaveAckOk = new LongAdder();
    /**
     * M1: writes acknowledged as LOST, keyed by the column that actually
     * lost its record - the eviction arm acks the VICTIM's own key, not
     * the submitter whose enqueue caused the eviction. This replaces the
     * LongAdder-delta heuristic that blamed the newest submitter for the
     * oldest victim's loss (the pre16 audit's confirmed systematic
     * misattribution: eviction victims stayed tracker-WHOLE with nothing
     * on disk while the counter re-armed somebody else).
     */
    public static final LongAdder farSaveAckFailed = new LongAdder();
    /**
     * True between a world join and the matching leave, i.e. a store is
     * open or queued to open. Game thread writes; read on the game
     * thread only.
     */
    private static volatile boolean worldOpen;

    /**
     * M1: one write outcome. {@code version} is the caller's opaque
     * monotonic stamp from {@link #submitShell} (the save state machine's
     * {@code writingVersion} once M2 lands; until then a per-submit serial
     * the drain can ignore); {@code ok} true means the store holds a
     * record at least as true as this write claimed (append landed, or an
     * equal/higher-tier record already stood), false means the shell
     * never reached disk and the column is NOT saved at this version.
     */
    public record WriteAck(int chunkX, int chunkZ, long version, boolean ok) {
    }

    /**
     * M1: the acknowledgement channel, MPSC - producers are the IO thread
     * (every {@link WriteTask#run} exit) and whatever thread hits an
     * enqueue-time drop (game thread submits, the eviction arm, the
     * cache-clear discard); the single consumer is the game-thread pump
     * ({@code ExtractDispatch.pumpGameThread} drains it first thing, the
     * save design's E7 ordering). Unbounded but structurally small: it
     * can only ever hold acks for writes that were submitted, and the
     * write queue itself is capped at {@value #WRITE_QUEUE_CAP}.
     */
    private static final java.util.concurrent.ConcurrentLinkedQueue<WriteAck> writeAcks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** M1: every write-path exit funnels here; counts, then queues. */
    private static void postWriteAck(int chunkX, int chunkZ, long version, boolean ok) {
        if (ok) {
            farSaveAckOk.increment();
        } else {
            farSaveAckFailed.increment();
        }
        writeAcks.add(new WriteAck(chunkX, chunkZ, version, ok));
    }

    /**
     * M1: next pending write acknowledgement, or null when there are
     * none. Single consumer by contract (the game-thread pump); the
     * counters above are the multi-thread-readable summary.
     */
    public static WriteAck pollWriteAck() {
        return writeAcks.poll();
    }

    /**
     * M4 (save design G3): is the write queue deep enough that
     * producing more shells would only feed the eviction arm? The store
     * OWNS this predicate (the threshold is 3/4 of its own private
     * {@value #WRITE_QUEUE_CAP}, not a constant copied into a caller),
     * and the read is one volatile int - no monitor is taken on the
     * game thread, and the racy read is exactly right for a pacing
     * threshold. False with no IO thread, which keeps the master-off
     * zero-cost claim intact. Producers pacing themselves against this
     * is what makes the eviction arm unreachable in steady state (the
     * arm stays as the honest last resort).
     */
    public static boolean writeQueueSaturated() {
        Io running = io;
        return running != null
                && running.queuedWrites > WRITE_QUEUE_CAP * 3 / 4;
    }

    /**
     * Pre16 M2 (invariant I2): the record map may claim "stored" only
     * on the store's OWN word - so a cache CLEAR, which falsifies every
     * storedVersion at once, is announced BY THE STORE the same way the
     * write acks are, and only after it actually SUCCEEDED (a failed
     * clear must not re-dirty a correct map). Set on the IO/cleaner
     * thread, consumed once by the game-thread pump, which resets the
     * records exactly as a settings flip does. A store-side event
     * rather than a call at the button means no future clear path can
     * drift the map - and the button never has to touch (or
     * class-load) the extraction layer at all.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean storeInvalidated =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Consume the store-invalidated event; true at most once per clear event. */
    public static boolean consumeStoreInvalidated() {
        return storeInvalidated.getAndSet(false);
    }

    /**
     * M5: the store GENERATION - bumped whenever a store closes for a
     * world exit (leave, the drain barrier's close, a join's
     * close-the-old arm; NEVER a cache clear, which reopens for the
     * same world). A {@code Pin} snapshots it at capture, and the pin
     * worker walks a pin only while it still matches: past the
     * world-exit drain window the shell would otherwise be filed under
     * the NEXT world's key. Bumps are harmless in themselves (only
     * equality is ever tested), which is why every close-for-exit path
     * bumps unconditionally, store or no store.
     */
    private static final java.util.concurrent.atomic.AtomicLong storeGeneration =
            new java.util.concurrent.atomic.AtomicLong();

    /** The open store's generation; see the field. Any thread. */
    public static long storeGeneration() {
        return storeGeneration.get();
    }

    /** M5: has the IO thread died for the session? Any thread. */
    public static boolean ioDead() {
        Io running = io;
        return running != null && running.dead;
    }

    /**
     * M5: the pin drain's idle test, registered by the worker on its
     * first start. {@link #onWorldLeave} keeps the store open behind a
     * barrier task until this answers true or the drain deadline
     * passes, which is what makes "worker drains PINNED with a 5 s
     * deadline; store close is a lifecycle task sequenced AFTER the
     * drain" real (the queue already serializes lifecycle).
     */
    private static volatile java.util.function.BooleanSupplier pinDrainIdle;

    /** See {@link #pinDrainIdle}. Called once, from the worker's start. */
    public static void registerPinDrainIdle(java.util.function.BooleanSupplier idle) {
        pinDrainIdle = idle;
    }

    /** The world-exit pin-drain grace, the design's 5 seconds. */
    private static final long PIN_DRAIN_DEADLINE_NANOS = 5_000_000_000L;

    /**
     * Bumped on every {@link #onWorldJoin} (game thread; volatile so the
     * IO-thread barrier reads it): a pin-drain barrier created before a
     * join stops waiting the moment one lands, because the join's own
     * close-the-old arm takes over the store swap.
     */
    private static volatile long joinGeneration;

    /**
     * Pending-write ceiling. A shell averages ~7.3 KB uncompressed in
     * memory (SHELL-CENSUS-2026-08.md), so 512 queued shells are a few
     * MB - and a render-distance shrink can abandon whole rings of
     * chunks at once (FARFIELD-VANILLA-SEAM.md section 1.3), so the cap
     * must absorb a burst without letting an unpaced caller balloon the
     * heap. Past it, the OLDEST pending write is dropped and counted.
     */
    private static final int WRITE_QUEUE_CAP = 512;
    /**
     * Pending-read ceiling. W3's promote walker is budgeted at 64 reads
     * per pump (the leaf-tier walker pattern), so thousands of pending
     * reads mean a runaway caller; past the cap a request is answered
     * null immediately on the calling thread.
     */
    private static final int READ_QUEUE_CAP = 4096;
    /** Store-problem log budget per world session; counting never stops. */
    private static final int ERROR_LOG_BUDGET = 8;

    /** The whole far-field runtime; null is the zero-cost-off state. */
    private static volatile Io io;

    private FarField() {
    }

    // ------------------------------------------------------------------
    // Lifecycle (game thread; any thread is safe)
    // ------------------------------------------------------------------

    /**
     * A world became current. Keys come from {@code CacheKeys} on the
     * game thread (they read client state); this method only carries the
     * strings across, so the IO thread never touches a vanilla object.
     * First enabled call creates the IO thread; disabled calls do
     * nothing at all.
     */
    public static void onWorldJoin(String worldKey, String dimensionKey) {
        if (!FarFieldConfig.enabled()) {
            return;
        }
        if (worldKey == null || worldKey.isEmpty()
                || dimensionKey == null || dimensionKey.isEmpty()) {
            return;
        }
        joinGeneration++; // cuts a pending pin-drain barrier short
        ensureIo().enqueueLifecycle(new JoinTask(worldKey, dimensionKey));
        worldOpen = true;
        // W3: the residency walker starts a fresh world era (its per-world
        // sets must not leak across worlds). Game thread, cheap.
        FarFieldResidency.onWorldJoin();
    }

    /**
     * Open the store for whatever world the client is in RIGHT NOW,
     * deriving the cache keys here. Game thread only. Returns true when
     * a store is open (or was already).
     *
     * <p>This exists because the level-swap seam opens the store only
     * when the far field is armed AS THE WORLD LOADS. Enabling Far
     * Terrain from the settings screen in an already-loaded world - the
     * only way a player ever enables it - therefore left the store shut
     * for the rest of the session, and every extraction was dropped on
     * the floor by {@link #submitShell}'s null check. It presented as
     * "nothing saves, chunks missing everywhere", healed only by
     * reloading the world, and nothing counted it. Measured on the
     * owner's machine before the fix: 54,739 extractions, 180 million
     * cells walked, and exactly ZERO writes.</p>
     */
    private static boolean openForCurrentWorld() {
        if (!FarFieldConfig.enabled()) {
            return false;
        }
        if (worldOpen && io != null) {
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return false;
        }
        String worldKey = CacheKeys.worldKey(minecraft);
        String dimensionKey = CacheKeys.dimensionKey(minecraft.level);
        if (worldKey == null || worldKey.isEmpty()
                || dimensionKey == null || dimensionKey.isEmpty()) {
            return false;
        }
        onWorldJoin(worldKey, dimensionKey);
        return worldOpen && io != null;
    }

    /**
     * The current world is going away: flush pending writes, close the
     * store, park the thread. Deliberately NOT gated on the master
     * toggle - if the player disables the far field mid-session, the
     * already-open store still has to close cleanly on the next leave.
     * No-op when nothing was ever armed.
     */
    public static void onWorldLeave() {
        worldOpen = false;
        Io running = io;
        if (running == null) {
            return;
        }
        // W3: release every far-resident section through the residency's
        // far release path BEFORE the store flushes shut, and reset the
        // walker's per-world state (docs/unreleased/farfield/FARFIELD-WAVES.md W3: release
        // everything on onWorldLeave). Guarded by the io null-check above
        // so a never-armed session still never class-loads the walker —
        // the zero-cost-off posture.
        FarFieldResidency.onWorldLeave();
        // M5: the pin drain's grace. With pins still in flight the close
        // becomes a BARRIER task that holds the store open (re-enqueueing
        // itself behind whatever writes the worker lands, so they flush
        // in FIFO order exactly like game-thread writes) until the drain
        // is idle, the design's 5 s deadline passes, or a new world's
        // join overtakes it - whichever is first. The worker counts every
        // pin the deadline strands, on farSaveLost, via the generation
        // test; nothing is silently discarded.
        java.util.function.BooleanSupplier drainIdle = pinDrainIdle;
        if (drainIdle != null && !drainIdle.getAsBoolean() && !running.dead) {
            running.enqueueLifecycle(new DrainThenLeaveTask(drainIdle,
                    System.nanoTime() + PIN_DRAIN_DEADLINE_NANOS,
                    joinGeneration));
        } else {
            running.enqueueLifecycle(new LeaveTask());
        }
    }

    // ------------------------------------------------------------------
    // Pass-throughs (W2 extractor submits; W3 walker requests)
    // ------------------------------------------------------------------

    /**
     * Queue a shell for serialize-and-write on the IO thread. Never
     * blocks: past {@value #WRITE_QUEUE_CAP} pending writes the oldest
     * pending write is dropped and counted on
     * {@link #farStoreDroppedWrites}. {@code tier} must match
     * {@code shell.tier} (it exists separately so the store can enforce
     * the truth ordering without inflating the record); a mismatch is
     * counted as an error and the shell is discarded.
     *
     * <p><b>M1 acknowledgement contract:</b> every call with a non-null
     * shell posts exactly one {@link WriteAck} carrying {@code version},
     * from whichever exit the write takes - append landed (ok), tier
     * refusal (ok - the disk already holds equal-or-higher truth), queue
     * eviction (the VICTIM's key, not the submitter's), dead IO thread,
     * no store, tier mismatch, unencodable shell, IO failure (all not
     * ok). A null shell is the one silent return: nothing was offered,
     * so nothing is owed (unreachable from the single caller, which
     * checks before minting a version).</p>
     *
     * @param version the submitter's monotonic stamp, echoed on the ack
     */
    public static void submitShell(int chunkX, int chunkZ, int tier,
            ShellCodec.Shell shell, long version) {
        if (shell == null) {
            return;
        }
        if (!FarFieldConfig.enabled()) {
            // Disarmed mid-flight (the settings screen's master toggle):
            // the write is not happening, and M1's contract is that no
            // submit goes unanswered.
            postWriteAck(chunkX, chunkZ, version, false);
            return;
        }
        Io running = io;
        if (running == null || !worldOpen) {
            // THE STORE WAS NEVER OPENED, and this used to be a silent
            // return. onWorldJoin is driven from the level-swap seam and
            // only when the far field is ALREADY armed as the world
            // loads - so a player who enables Far Terrain from the
            // settings screen mid-session, which is the only way anybody
            // ever enables it, got a session where every extraction was
            // discarded and nothing was ever saved. It looked exactly
            // like "lots of missing chunks", healed only by a world
            // reload, and was invisible because nothing counted it.
            //
            // Open the store here instead. submitShell is on the game
            // thread by the extractor's threading contract, so reading
            // the client level and deriving the cache keys is safe.
            if (!openForCurrentWorld()) {
                farStoreDroppedNoStore.increment();
                postWriteAck(chunkX, chunkZ, version, false);
                return;
            }
            running = io;
            if (running == null) {
                farStoreDroppedNoStore.increment();
                postWriteAck(chunkX, chunkZ, version, false);
                return;
            }
        }
        running.enqueueWrite(new WriteTask(chunkX, chunkZ, tier, shell, version));
        // W3: a fresh shell invalidates the walker's "absent" memory for
        // this column so the next promote arm can pick it up; SEAM step
        // 2: for a column the far field is already DRAWING it files a
        // replace-in-place refresh instead (the old mesh keeps drawing
        // until the new one binds). Game
        // thread (every submitShell caller sits on a chunk-lifecycle
        // seam, ExtractDispatch's threading contract).
        FarFieldResidency.onShellWritten(chunkX, chunkZ);
    }

    /**
     * M5: the pin worker's submit - the ONLY {@code submitShell} variant
     * any thread but the game thread may call, and it is narrower on
     * purpose: it never opens a store ({@code openForCurrentWorld} reads
     * client state), never consults {@code worldOpen} (the world-exit
     * drain window deliberately keeps the store open after the flag
     * drops), and NEVER calls the residency ({@code onShellWritten}
     * takes the residency lock, which the worker must not touch - the
     * notification rides this write's ACK instead, issued by the
     * game-thread pump through {@link #onPinShellAcked}). Same ack
     * contract as {@link #submitShell}: every call posts exactly one
     * {@link WriteAck} from some exit.
     */
    public static void submitShellFromWorker(int chunkX, int chunkZ, int tier,
            ShellCodec.Shell shell, long version) {
        if (shell == null) {
            return;
        }
        if (!FarFieldConfig.enabled()) {
            postWriteAck(chunkX, chunkZ, version, false);
            return;
        }
        Io running = io;
        if (running == null) {
            farStoreDroppedNoStore.increment();
            postWriteAck(chunkX, chunkZ, version, false);
            return;
        }
        running.enqueueWrite(new WriteTask(chunkX, chunkZ, tier, shell, version));
    }

    /**
     * <b>T2 Phase 3:</b> the game thread's ONE remaining store-open
     * trigger, called from the far-field pump while columns are owed.
     *
     * <p>It exists because Phase 3 deleted the last game-thread
     * {@code submitShell} caller, and that caller was carrying a second
     * job nobody had named: opening the store for a player who arms Far
     * Terrain from the settings screen mid-session. {@code onWorldJoin}
     * only fires when the far field is ALREADY armed as the world loads,
     * so without this the whole session would submit through
     * {@link #submitShellFromWorker}, which deliberately never opens a
     * store, and every write would be dropped on
     * {@code farStoreDroppedNoStore} - the exact "lots of missing chunks,
     * healed only by a world reload" failure the submit-side open was
     * added to fix. Moving the walk off the game thread must not move
     * that fix off it too.</p>
     *
     * <p>Two field reads in the steady state; {@code openForCurrentWorld}
     * reads client state, which is why this may only be called here.</p>
     */
    public static void ensureStoreOpenForCapture() {
        if (io != null && worldOpen) {
            return;
        }
        if (!FarFieldConfig.enabled()) {
            return;
        }
        openForCurrentWorld();
    }

    /**
     * M5: a captured column's write acked OK - the pump (game thread)
     * forwards the residency notification the worker's submit could
     * not make. Same role as the {@code onShellWritten} call inside
     * {@link #submitShell}, at ack time instead of enqueue time (later,
     * and truer: the shell is on disk). The io null-check keeps the
     * class-load discipline: this method names the walker, and it is
     * unreachable until a store existed to ack anything.
     *
     * <p><b>Phase 3 widened its caller set from PINNED settles to every
     * captured write</b>, LIVE ones included. That is not a new
     * behaviour, it is the old one arriving by the new road: a live
     * extraction used to notify the residency inside
     * {@link #submitShell} at enqueue time, and the residency's
     * replace-in-place refresh is exactly as load-bearing for a column
     * the player is standing next to as for one that just left.</p>
     */
    public static void onPinShellAcked(int chunkX, int chunkZ) {
        if (io == null) {
            return;
        }
        FarFieldResidency.onShellWritten(chunkX, chunkZ);
    }

    /**
     * Queue an async read. The consumer receives the decoded shell, or
     * null for absent/corrupt/off/unjoined. It normally runs on the IO
     * thread - it must be cheap and thread-safe, handing results to the
     * pump via the volatile-request pattern (FARFIELD-CODEBASE-SEAM.md
     * section 6.4) - but is invoked SYNCHRONOUSLY on the calling thread
     * (with null) when the far field cannot service the request at all:
     * master off, no world joined, read queue past its cap, IO thread
     * dead. Callers must tolerate both.
     */
    public static void requestShell(int chunkX, int chunkZ,
            Consumer<ShellCodec.Shell> consumer) {
        if (consumer == null) {
            return;
        }
        if (!FarFieldConfig.enabled()) {
            consumer.accept(null);
            return;
        }
        Io running = io;
        if (running == null) {
            consumer.accept(null);
            return;
        }
        running.enqueueRead(new ReadTask(chunkX, chunkZ, consumer, null));
    }

    /**
     * One column and its eight neighbours, decoded - S1's input (pre18,
     * the owner's R5). {@code ring} is a 9-slot array laid out
     * {@code (dz + 1) * 3 + (dx + 1)} with the centre slot null and a null
     * for every neighbour the store has nothing for.
     */
    public record Neighborhood(ShellCodec.Shell center, ShellCodec.Shell[] ring) {}

    /**
     * S1: queue an async read for a column AND its 3x3 neighbourhood, so
     * the mesher can answer a face on a chunk plane from the record on the
     * other side of it (AO probes, fluid corner averages and flow, the
     * per-face light donor, the fluid overlay's neighbour test). Same
     * contract as {@link #requestShell} in every other respect, including
     * the synchronous null when the far field cannot service the request.
     *
     * <p><b>The read amplification is bounded by a decoded-shell cache on
     * the IO thread</b> ({@link Io#decoded}, {@value Io#DECODED_CACHE}
     * entries): the walker requests columns in ring order, so a
     * neighbourhood's members are overwhelmingly columns the walk has
     * already touched or is about to. {@code farApronReads} /
     * {@code farApronHits} / {@code farApronAbsent} report what it
     * actually costs. {@code -Dmeshelium.farfield.apron=false} turns the
     * whole thing off and returns to pre18's per-column meshing.</p>
     */
    public static void requestShellNeighborhood(int chunkX, int chunkZ,
            Consumer<Neighborhood> consumer) {
        if (consumer == null) {
            return;
        }
        if (!FarFieldConfig.enabled()) {
            consumer.accept(null);
            return;
        }
        Io running = io;
        if (running == null) {
            consumer.accept(null);
            return;
        }
        running.enqueueRead(new ReadTask(chunkX, chunkZ, null, consumer));
    }

    /**
     * W3: run a pure-CPU job on the far-field IO thread (the shell
     * meshing hop — {@code FarFieldResidency}'s two-hop pipeline). The
     * far field owns exactly ONE thread, so mesh work shares it with
     * store IO rather than minting a second (section 6.4's discipline;
     * meshing is hundreds of microseconds per column against
     * millisecond-class region-file reads). Lifecycle-class task: never
     * dropped by the overflow policy — the CALLER bounds outstanding
     * jobs (the walker's 8-requests-in-flight budget), and the job must
     * catch its own throwables (an escape here would count on
     * {@link #farStoreErrors}, the store's ledger, not the mesher's).
     *
     * @return false when the job was NOT queued (master off, no IO
     *         thread, or the thread died) — the caller unwinds its own
     *         bookkeeping; the job is never run in that case
     */
    static boolean submitCompute(Runnable job) {
        if (job == null || !FarFieldConfig.enabled()) {
            return false;
        }
        Io running = io;
        if (running == null || running.dead) {
            return false;
        }
        running.enqueueLifecycle(new ComputeTask(job));
        return true;
    }

    // ------------------------------------------------------------------
    // Cache administration (W4: the Far Terrain screen's folder line,
    // size readout and delete button)
    // ------------------------------------------------------------------

    /**
     * {@code <gamedir>/meshelium/farfield}: the whole cache, every world
     * and every dimension. Pure path arithmetic - it never creates
     * anything, so the screen may show it with the master off and the
     * zero-cost leg still finds no folder on disk. Byte-identical to the
     * base {@code FarFieldStore}'s constructor derives, deliberately:
     * {@link #requestCacheClear} deletes exactly this subtree and the
     * store's own delete guard anchors on the same prefix.
     */
    public static Path cacheFolder() {
        return MesheliumPlatform.gameDir().toAbsolutePath().normalize()
                .resolve("meshelium").resolve("farfield");
    }

    /** At most one size walk at a time; see {@link #requestCacheSizeBytes}. */
    private static final AtomicBoolean sizeWalkInFlight = new AtomicBoolean();

    /**
     * Measure {@link #cacheFolder()} OFF the render thread and hand the
     * total to {@code consumer}. A directory walk over thousands of
     * region files is not something a screen may do between frames
     * (FAR-FIELD-DESIGN.md section 3.2: the readout is computed off the
     * render thread and cached), so this spawns a short-lived daemon
     * thread and answers from it.
     *
     * <p>Deliberately NOT the far-field IO thread. That thread may not
     * exist at all (the master is off and the screen still has a size to
     * show and a folder to clear), and when it does exist a whole-cache
     * walk queued ahead of shell reads would stall the horizon for the
     * duration. Reading file SIZES never conflicts with the writer:
     * {@code Files.size} is a metadata query and needs no handle of its
     * own, and a file that vanishes under a concurrent compaction is
     * counted as zero rather than failing the walk.</p>
     *
     * <p>{@code consumer} runs on the walker thread, NOT the game thread:
     * callers store the value in a volatile field and read it from their
     * own tick (the volatile-request pattern,
     * FARFIELD-CODEBASE-SEAM.md section 6.4). A total of -1 means the
     * walk failed and the last known value should stand.</p>
     *
     * @return false when no walk was started because one is already
     *         running - the caller keeps whatever it last displayed and
     *         may ask again later; the consumer is NOT called in that
     *         case, so a caller with its own in-flight flag must only
     *         set it when this returns true
     */
    public static boolean requestCacheSizeBytes(LongConsumer consumer) {
        if (consumer == null || !sizeWalkInFlight.compareAndSet(false, true)) {
            return false;
        }
        Thread walker = new Thread(() -> {
            long total;
            try {
                total = folderBytes(cacheFolder());
            } catch (Throwable t) {
                // Includes the UncheckedIOException Files.walk can raise
                // mid-stream when a directory disappears under it.
                farStoreErrors.increment();
                total = -1L;
            } finally {
                sizeWalkInFlight.set(false);
            }
            consumer.accept(total);
        }, "meshelium-farfield-size");
        walker.setDaemon(true);
        walker.start();
        return true;
    }

    private static long folderBytes(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return 0L;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException vanished) {
                    return 0L; // compacted away between walk and stat
                }
            }).sum();
        }
    }

    /**
     * Delete the whole cache: every world, every dimension, the folder
     * itself included, so a cleared install is indistinguishable from a
     * fresh one. {@code done} receives true when everything went, false
     * when at least one file survived (it runs OFF the game thread, same
     * contract as {@link #requestCacheSizeBytes}).
     *
     * <p><b>Why this must go through the IO thread when one exists.</b>
     * An open region file is an open {@code RandomAccessFile} handle, and
     * Windows refuses to delete a file another handle holds. So with a
     * store open the delete is queued as a lifecycle task that closes the
     * store, deletes, and REOPENS a store for the same world keys - the
     * player stays in their world and extraction keeps working, it just
     * starts from an empty cache. With no store open (master off, or no
     * world joined) there are no handles and a short-lived daemon thread
     * does the same work directly, without arming the far field.</p>
     *
     * <p>Lifecycle-class task, so the write-overflow policy can never
     * drop it, and it cannot throw out of the IO loop: every failure
     * counts on {@link #farStoreErrors} and still answers {@code done},
     * because a screen left saying "Deleting..." forever is worse than an
     * honest failure.</p>
     */
    public static void requestCacheClear(Consumer<Boolean> done) {
        Io running = io;
        if (running == null) {
            Thread cleaner = new Thread(() -> {
                boolean ok;
                try {
                    ok = clearCacheFolder();
                } catch (Throwable t) {
                    farStoreErrors.increment();
                    MesheliumLog.LOGGER.warn("Far-field cache clear failed", t);
                    ok = false;
                }
                if (ok) {
                    storeInvalidated.set(true); // success only - see the field
                }
                if (done != null) {
                    done.accept(ok);
                }
            }, "meshelium-farfield-clear");
            cleaner.setDaemon(true);
            cleaner.start();
            return;
        }
        running.enqueueClear(new ClearTask(done));
    }

    /**
     * Recursive delete of {@link #cacheFolder()}, deepest first. Every
     * victim is re-checked to live under that folder before it is
     * touched: the paths all came from walking it, so the check can only
     * ever be redundant, which is the right amount of paranoia for the
     * one code path in this mod that deletes a player's files. Never
     * throws; a failure counts and returns false.
     */
    private static boolean clearCacheFolder() {
        Path base = cacheFolder();
        if (!Files.isDirectory(base)) {
            return true; // nothing on disk IS the cleared state
        }
        List<Path> victims = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.sorted(Comparator.reverseOrder()).forEach(victims::add);
        } catch (IOException | RuntimeException e) {
            farStoreErrors.increment();
            MesheliumLog.LOGGER.warn("Could not list {} to clear it", base, e);
            return false;
        }
        boolean ok = true;
        for (Path victim : victims) {
            Path normalized = victim.toAbsolutePath().normalize();
            if (!normalized.startsWith(base)) {
                farStoreErrors.increment();
                MesheliumLog.LOGGER.error(
                        "Refusing to delete {}: outside {}", normalized, base);
                return false;
            }
            try {
                Files.deleteIfExists(normalized);
            } catch (IOException | RuntimeException e) {
                // A live handle (another launcher instance), a read-only
                // file, a security manager: all "did not go", none of
                // them a reason to stop trying the rest.
                ok = false;
                farStoreErrors.increment();
            }
        }
        return ok;
    }

    // ------------------------------------------------------------------
    // The zero-cost proof hook
    // ------------------------------------------------------------------

    /**
     * True iff the far field has never armed in this session: no IO
     * thread exists and no queue or store is allocated (they all live
     * inside the one {@link Io} object this checks). The suite's
     * zero-cost-off leg asserts this after a full armed run with the
     * master off, alongside all five counters reading zero and no
     * {@code meshelium/farfield} folder existing.
     */
    public static boolean isCompletelyIdle() {
        return io == null;
    }

    private static Io ensureIo() {
        Io running = io;
        if (running == null) {
            synchronized (FarField.class) {
                running = io;
                if (running == null) {
                    running = new Io();
                    io = running;
                }
            }
        }
        return running;
    }

    // ------------------------------------------------------------------
    // The IO runtime
    // ------------------------------------------------------------------

    /**
     * The single-consumer runtime: one bounded FIFO queue (a plain
     * ArrayDeque under its own monitor - producers hold the lock for an
     * add or a bounded drop-scan, the consumer for a single poll; all
     * file IO happens outside it) and the one daemon thread draining it.
     * Store and log budget are IO-thread confined.
     */
    private static final class Io implements Runnable {

        final ArrayDeque<Task> queue = new ArrayDeque<>();
        final Thread thread;
        /**
         * Writes are read-modify-written only under the queue monitor
         * (which serializes them); volatile so
         * {@link #writeQueueSaturated} can read the depth from the game
         * thread without taking the IO queue's monitor. queuedReads
         * stays monitor-guarded (no cross-thread reader).
         */
        volatile int queuedWrites;
        int queuedReads;
        /** IO-thread confined. */
        FarFieldStore store;
        /**
         * The keys the open store was built from, IO-thread confined.
         * Kept so {@link ClearTask} can rebuild a store for the SAME
         * world after wiping the folder out from under it: the player
         * pressed a button, they did not leave their world.
         */
        String worldKey;
        String dimensionKey;
        int errorLogBudget = ERROR_LOG_BUDGET;
        /** Set by the uncaught handler; producers then stop feeding. */
        volatile boolean dead;

        static final int DECODED_CACHE = 128;

        /** {@link #decoded}'s "the store has nothing here" memo. */
        static final Object ABSENT = new Object();

        /**
         * S1's decoded-shell LRU, IO-THREAD CONFINED (every reader and
         * writer is a task body). Access-ordered, capped at
         * {@value #DECODED_CACHE}; an ABSENT column is memoised as
         * {@link #ABSENT} so a frontier neighbourhood does not re-read
         * eight holes per column.
         *
         * <p>Bounded memory: a decoded shell is the census's ~700-cell
         * {@code int[]} plus its palette and tint tables, order 4-8 KB, so
         * 128 entries is roughly 0.5-1 MB - held on the IO thread, not per
         * resident. Invalidated per key by {@link WriteTask} (a rewritten
         * record must not be answered from a stale decode) and wholesale
         * by join, leave and cache-clear.</p>
         */
        final java.util.LinkedHashMap<Long, Object> decoded =
                new java.util.LinkedHashMap<>(32, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            java.util.Map.Entry<Long, Object> eldest) {
                        return size() > DECODED_CACHE;
                    }
                };

        /**
         * Read one column through {@link #decoded}. IO thread only.
         * {@code amplified} marks an APRON read for the counters, so the
         * seam fix's real cost is visible rather than argued.
         */
        ShellCodec.Shell readDecoded(int chunkX, int chunkZ, boolean amplified) {
            long key = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
            Object cached = decoded.get(key);
            if (cached != null) {
                if (amplified) {
                    farApronHits.increment();
                }
                return cached == ABSENT ? null : (ShellCodec.Shell) cached;
            }
            ShellCodec.Shell result = null;
            if (store != null) {
                try {
                    byte[] record = store.read(chunkX, chunkZ);
                    farStoreReads.increment();
                    if (amplified) {
                        farApronReads.increment();
                    }
                    if (record != null) {
                        // WITH the key's own chunk position. The record does
                        // not store it (ShellCodec.ORIGIN_UNKNOWN) and the
                        // mesher needs it to reproduce vanilla's
                        // per-POSITION appearance inputs. Memory only.
                        result = ShellCodec.decode(record, chunkX, chunkZ);
                        if (result == null) {
                            // Corrupt record: degrade to a miss, count it.
                            farStoreErrors.increment();
                            warnThrottled(this,
                                    "Corrupt far-field record ignored", null);
                        } else if (!result.signatureMatches(saveSignature())) {
                            // pre20 (F): THE STALENESS CHECK THAT WAS
                            // NEVER THERE. The record was extracted under
                            // rules that have since moved - a band-rule
                            // revision, a save-time settings row, or (the
                            // case that made this necessary) a colour fix.
                            // Treat it as ABSENT so the ordinary machinery
                            // re-extracts the column the moment the client
                            // holds it, exactly as it would for a column
                            // the store never had.
                            //
                            // NOT an error and never counted as one: a
                            // stale record is the cache working as
                            // designed across a version change, and
                            // routing it through farStoreErrors would make
                            // an upgrade look like corruption.
                            //
                            // The cost is real and is the point. Until the
                            // column is re-extracted it draws as nothing,
                            // and re-extraction needs the client to HOLD
                            // the chunk (ExtractDispatch.tryExtractLoaded
                            // takes getChunk(x, z, false) and gives up on
                            // null), so the far field beyond the vanilla
                            // render distance refills only as the player
                            // travels. That is the same state the owner
                            // reaches by deleting
                            // <gamedir>/meshelium/farfield/<world>/<dim>/,
                            // which is what every colour investigation so
                            // far has had to ask him to do by hand -
                            // except that this reaches only records whose
                            // vintage actually moved, and it does it
                            // without asking. The alternative, drawing a
                            // record we know was written under superseded
                            // rules, is what made three consecutive
                            // correct fixes unfalsifiable.
                            result = null;
                            farStoreStaleRecords.increment();
                        }
                    }
                } catch (java.io.IOException e) {
                    farStoreErrors.increment();
                    warnThrottled(this, "Far-field read failed", e);
                    return null; // NOT memoised: an IO failure is not absence
                }
            }
            if (result == null && amplified) {
                farApronAbsent.increment();
            }
            decoded.put(key, result == null ? ABSENT : result);
            return result;
        }

        /** Drop one column's decode: its record on disk just changed. */
        void invalidateDecoded(int chunkX, int chunkZ) {
            decoded.remove((((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL));
        }

        Io() {
            thread = new Thread(this, "meshelium-farfield-io");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) -> {
                // Count on the far field's OWN counter and park. No
                // renderer state is reachable from this class, so the
                // coverage guard cannot see this failure - by design.
                farStoreErrors.increment();
                dead = true;
                MesheliumLog.LOGGER.error(
                        "Far-field IO thread died; the far field is parked"
                                + " until the game restarts", e);
            });
            thread.start();
        }

        void enqueueLifecycle(Task task) {
            synchronized (queue) {
                queue.addLast(task);
                queue.notify();
            }
        }

        void enqueueWrite(WriteTask task) {
            if (dead) {
                farStoreDroppedWrites.increment();
                postWriteAck(task.chunkX, task.chunkZ, task.version, false);
                return;
            }
            WriteTask victim = null;
            boolean newcomerDropped = false;
            synchronized (queue) {
                if (queuedWrites >= WRITE_QUEUE_CAP) {
                    Iterator<Task> pending = queue.iterator();
                    while (pending.hasNext()) {
                        if (pending.next() instanceof WriteTask evicted) {
                            pending.remove();
                            queuedWrites--;
                            farStoreDroppedWrites.increment();
                            // M1: THE ATTRIBUTION FIX. The evicted task is
                            // the oldest pending write - a DIFFERENT column
                            // from the submitter whose enqueue tripped the
                            // cap - and it is the one whose record is now
                            // not on disk. Acked below, outside the
                            // monitor, under its OWN key, so the victim
                            // re-queues and the submitter's fresh write
                            // rides on untouched. The delta heuristic this
                            // replaces could only blame the submitter.
                            victim = evicted;
                            break;
                        }
                    }
                    if (queuedWrites >= WRITE_QUEUE_CAP) {
                        // No write found to evict (cannot happen while
                        // the count is honest); drop the newcomer rather
                        // than grow without bound.
                        farStoreDroppedWrites.increment();
                        newcomerDropped = true;
                    }
                }
                if (!newcomerDropped) {
                    queue.addLast(task);
                    queuedWrites++;
                    queue.notify();
                }
            }
            if (victim != null) {
                postWriteAck(victim.chunkX, victim.chunkZ, victim.version, false);
            }
            if (newcomerDropped) {
                postWriteAck(task.chunkX, task.chunkZ, task.version, false);
            }
        }

        /**
         * Queue a cache CLEAR, discarding every write still waiting.
         *
         * <p>Two reasons, and both matter. Correctness: a write queued
         * before the delete would land AFTER it and immediately
         * re-create files the player just asked to be gone, so the
         * folder would come back non-empty with no further action.
         * Responsiveness: the queue is FIFO, so without this the clear
         * sits behind as many as {@link #WRITE_QUEUE_CAP} shell writes
         * and the screen says "Deleting" for as long as that backlog
         * takes to drain - which is exactly how the W4 delete-cache leg
         * failed on 2026-08-19 (the folder still held 741 KB when the
         * leg gave up). Lifecycle tasks are NOT reordered: join/leave
         * keep their FIFO position, so a leave still flushes what came
         * before it.</p>
         *
         * <p>Discarded writes are counted on
         * {@link #farStoreDroppedWrites} - they genuinely never reached
         * disk - though here it is policy working, not pressure.</p>
         */
        void enqueueClear(Task task) {
            List<WriteTask> discarded = new ArrayList<>();
            synchronized (queue) {
                Iterator<Task> pending = queue.iterator();
                while (pending.hasNext()) {
                    if (pending.next() instanceof WriteTask write) {
                        pending.remove();
                        queuedWrites--;
                        farStoreDroppedWrites.increment();
                        discarded.add(write);
                    }
                }
                queue.addLast(task);
                queue.notify();
            }
            // M1: the discard is policy working, but the records still
            // never reached disk, so each victim is acked under its own
            // key (outside the monitor - postWriteAck touches no queue
            // state and the ack order does not matter to the drain).
            for (WriteTask write : discarded) {
                postWriteAck(write.chunkX, write.chunkZ, write.version, false);
            }
        }

        void enqueueRead(ReadTask task) {
            boolean queued = false;
            if (!dead) {
                synchronized (queue) {
                    if (queuedReads < READ_QUEUE_CAP) {
                        queue.addLast(task);
                        queuedReads++;
                        queue.notify();
                        queued = true;
                    }
                }
            }
            if (!queued) {
                // Answered on the calling thread, documented on
                // requestShell.
                task.answerNull();
            }
        }

        @Override
        public void run() {
            while (true) {
                Task task;
                synchronized (queue) {
                    while (queue.isEmpty()) {
                        try {
                            queue.wait();
                        } catch (InterruptedException interrupted) {
                            // Nothing of ours interrupts this thread;
                            // treat it as a shutdown request and park
                            // the far field for the session.
                            Thread.currentThread().interrupt();
                            dead = true;
                            return;
                        }
                    }
                    task = queue.removeFirst();
                    if (task instanceof WriteTask) {
                        queuedWrites--;
                    } else if (task instanceof ReadTask) {
                        queuedReads--;
                    }
                }
                try {
                    task.run(this);
                } catch (Throwable t) {
                    farStoreErrors.increment();
                    warnThrottled(this, "Far-field store task failed", t);
                }
            }
        }
    }

    private static void warnThrottled(Io io, String message, Throwable t) {
        if (io.errorLogBudget > 0) {
            io.errorLogBudget--;
            MesheliumLog.LOGGER.warn(
                    "{} ({} more of these will be logged this world)",
                    message, io.errorLogBudget, t);
        }
    }

    // ------------------------------------------------------------------
    // Tasks (all run(...) bodies execute on the IO thread only)
    // ------------------------------------------------------------------

    private abstract static class Task {
        abstract void run(Io io) throws Exception;
    }

    private static final class JoinTask extends Task {
        final String worldKey;
        final String dimensionKey;

        JoinTask(String worldKey, String dimensionKey) {
            this.worldKey = worldKey;
            this.dimensionKey = dimensionKey;
        }

        @Override
        void run(Io io) throws IOException {
            io.decoded.clear(); // S1: another world's decodes are not ours
            if (io.store != null) {
                // A join without a leave (dimension swap routed only
                // through join): close the old world's store first. A
                // world-exit close, so the pin generation moves with it.
                io.store.close();
                io.store = null;
                storeGeneration.incrementAndGet();
            }
            // Loader API, thread-safe statics; javap-verified on
            // the loader seam: MesheliumPlatform.gameDir()
            // "public abstract java.nio.file.Path getGameDir();"
            // (getConfigDir precedent: MesheliumConfig.path()).
            Path gameDir = MesheliumPlatform.gameDir();
            io.store = new FarFieldStore(gameDir, worldKey, dimensionKey,
                    farStoreErrors);
            io.worldKey = worldKey;
            io.dimensionKey = dimensionKey;
            io.errorLogBudget = ERROR_LOG_BUDGET;
        }
    }

    /**
     * W4's clear-cache button, running where the file handles live. The
     * store is closed BEFORE the delete and rebuilt after, so a player
     * who clears mid-world keeps extracting into a fresh cache. Never
     * throws: {@code done} is answered on every path (see
     * {@link #requestCacheClear}).
     */
    private static final class ClearTask extends Task {
        final Consumer<Boolean> done;

        ClearTask(Consumer<Boolean> done) {
            this.done = done;
        }

        @Override
        void run(Io io) {
            boolean ok = false;
            io.decoded.clear(); // S1: the records behind them are going away
            try {
                if (io.store != null) {
                    io.store.close();
                    io.store = null;
                }
                ok = clearCacheFolder();
            } catch (Throwable t) {
                farStoreErrors.increment();
                warnThrottled(io, "Far-field cache clear failed", t);
            }
            try {
                if (io.worldKey != null && io.dimensionKey != null) {
                    io.store = new FarFieldStore(
                            MesheliumPlatform.gameDir(),
                            io.worldKey, io.dimensionKey, farStoreErrors);
                }
            } catch (Throwable t) {
                // The delete may well have succeeded; only the reopen
                // failed, so the cache is cleared but this world stops
                // caching until the next join. Counted, not hidden, and
                // deliberately NOT folded into the button's answer.
                farStoreErrors.increment();
                warnThrottled(io, "Far-field store did not reopen after a cache clear", t);
            }
            if (ok) {
                storeInvalidated.set(true); // success only - see the field
            }
            if (done != null) {
                done.accept(ok);
            }
        }
    }

    /** W3: one meshing job riding the IO thread ({@link #submitCompute}). */
    private static final class ComputeTask extends Task {
        final Runnable job;

        ComputeTask(Runnable job) {
            this.job = job;
        }

        @Override
        void run(Io io) {
            job.run();
        }
    }

    private static final class LeaveTask extends Task {
        @Override
        void run(Io io) {
            // FIFO makes this a flush: every write enqueued before the
            // leave has already run by the time this closes the store.
            io.decoded.clear(); // S1: another world reuses these keys
            if (io.store != null) {
                io.store.close();
                io.store = null;
            }
            // A world-exit boundary whether or not a store was open: any
            // pin still carrying the old generation must not write under
            // the next world's key (the bump is harmless when no pin
            // exists - only equality is ever tested).
            storeGeneration.incrementAndGet();
            // The world is gone, so a clear that lands after this must
            // NOT rebuild a store for it (see ClearTask).
            io.worldKey = null;
            io.dimensionKey = null;
        }
    }

    /**
     * M5: the world-exit close, gated behind the pin drain - "store
     * close is a lifecycle task sequenced AFTER the drain; the queue
     * already serializes lifecycle" (FARFIELD-SAVE-DESIGN.md section 3,
     * level swap). Each pass either closes (drain idle, deadline
     * passed, or a new join overtook it - the join's own close-the-old
     * arm owns the swap then) or naps briefly and RE-ENQUEUES itself at
     * the tail, so every write the worker lands during the grace runs
     * ahead of the close in plain FIFO order. The worker itself counts
     * whatever the deadline strands (the generation test in its drain
     * loop); this task never discards anything.
     */
    private static final class DrainThenLeaveTask extends Task {
        final java.util.function.BooleanSupplier drainIdle;
        final long deadlineNanos;
        final long joinGenerationAtLeave;

        DrainThenLeaveTask(java.util.function.BooleanSupplier drainIdle,
                long deadlineNanos, long joinGenerationAtLeave) {
            this.drainIdle = drainIdle;
            this.deadlineNanos = deadlineNanos;
            this.joinGenerationAtLeave = joinGenerationAtLeave;
        }

        @Override
        void run(Io io) throws InterruptedException {
            if (joinGeneration != joinGenerationAtLeave) {
                return; // a join overtook the grace; its close-old arm won
            }
            if (!drainIdle.getAsBoolean()
                    && System.nanoTime() < deadlineNanos && !io.dead) {
                Thread.sleep(5L);
                io.enqueueLifecycle(this);
                return;
            }
            io.decoded.clear(); // S1
            if (io.store != null) {
                io.store.close();
                io.store = null;
            }
            storeGeneration.incrementAndGet();
            io.worldKey = null;
            io.dimensionKey = null;
        }
    }

    private static final class WriteTask extends Task {
        final int chunkX;
        final int chunkZ;
        final int tier;
        final ShellCodec.Shell shell;
        /** M1: the submitter's stamp, echoed on this task's one ack. */
        final long version;

        WriteTask(int chunkX, int chunkZ, int tier, ShellCodec.Shell shell,
                long version) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.tier = tier;
            this.shell = shell;
            this.version = version;
        }

        /**
         * M1: exactly one ack per task, posted from the {@code finally}
         * so even the throw path (an {@code IOException} out of
         * {@code store.write}, which still propagates to {@link Io#run}'s
         * catch and counts on {@link #farStoreErrors} exactly as before)
         * answers the submitter. The one deliberate OK-without-append is
         * the tier refusal: a generated shell declining to clobber a
         * visited record leaves the disk holding STRICTLY MORE truth than
         * this write carried, so "is this column saved?" is yes and a
         * re-queue would spin forever writing a record the store is right
         * to refuse.
         */
        @Override
        void run(Io io) throws IOException {
            boolean ok = false;
            try {
                FarFieldStore store = io.store;
                if (store == null) {
                    farStoreDroppedWrites.increment();
                    return;
                }
                if (tier != shell.tier) {
                    farStoreErrors.increment();
                    warnThrottled(io, "submitShell tier disagrees with shell.tier;"
                            + " shell discarded", null);
                    return;
                }
                // pre20 (F): stamp the extraction-rule signature into
                // the record itself. Here rather than inside the codec so
                // ShellCodec stays a pure codec with no config dependency,
                // and here rather than in the extractor so the stamp and
                // the decode-time check read the one cached value (see
                // FarField.saveSignature).
                byte[] record = ShellCodec.encode(
                        shell.withSaveSignature(saveSignature()));
                if (record == null) {
                    // Unrepresentable shell: an extractor bug, counted here
                    // (the codec is pure and counts nothing).
                    farStoreErrors.increment();
                    warnThrottled(io, "Unencodable shell discarded", null);
                    return;
                }
                // S1: whatever the write's verdict, the decoded copy this
                // task might have raced is no longer the truth for this
                // column. Dropped BEFORE the write so a concurrent-looking
                // reader can only re-read, never re-serve a stale decode
                // (both run on this thread, so "concurrent" means "the
                // next task", but the ordering keeps the rule obvious).
                io.invalidateDecoded(chunkX, chunkZ);
                if (store.write(chunkX, chunkZ, tier, record)) {
                    farStoreWrites.increment();
                    // +1: the store's tier prefix byte, appended per record.
                    farStoreBytesWritten.add(1L + record.length);
                }
                // Tier-refused writes stay un-logged by design: a generated
                // shell must never clobber a visited one, and that refusal
                // is the store WORKING (FAR-FIELD-DESIGN.md section 3.2).
                // M1 acks it OK - see the method javadoc.
                ok = true;
            } finally {
                postWriteAck(chunkX, chunkZ, version, ok);
            }
        }
    }

    private static final class ReadTask extends Task {
        final int chunkX;
        final int chunkZ;
        /** The plain-shell consumer, or null for a neighbourhood read. */
        final Consumer<ShellCodec.Shell> consumer;
        /** S1's consumer, or null for a plain read. Exactly one is set. */
        final Consumer<Neighborhood> neighborhood;

        ReadTask(int chunkX, int chunkZ, Consumer<ShellCodec.Shell> consumer,
                Consumer<Neighborhood> neighborhood) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.consumer = consumer;
            this.neighborhood = neighborhood;
        }

        /** Both consumers answer null the same way; see requestShell. */
        void answerNull() {
            if (consumer != null) {
                consumer.accept(null);
            } else if (neighborhood != null) {
                neighborhood.accept(null);
            }
        }

        @Override
        void run(Io io) {
            ShellCodec.Shell result = io.readDecoded(chunkX, chunkZ, false);
            if (consumer != null) {
                consumer.accept(result);
                return;
            }
            if (result == null) {
                neighborhood.accept(null); // a MISS: no apron to gather
                return;
            }
            ShellCodec.Shell[] ring = null;
            if (apronEnabled()) {
                ring = new ShellCodec.Shell[9];
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        ring[(dz + 1) * 3 + (dx + 1)] =
                                io.readDecoded(chunkX + dx, chunkZ + dz, true);
                    }
                }
            }
            neighborhood.accept(new Neighborhood(result, ring));
        }
    }

    /**
     * S1's kill switch, {@code -Dmeshelium.farfield.apron} (default true).
     * A documented lever rather than a settings row: with it false the
     * mesher meshes one record at a time exactly as pre18 did, which is
     * the fallback if a live world ever shows the read amplification
     * costing more than the seam is worth.
     *
     * <p>Package-visible since pre19 so {@code FarFieldResidency}'s
     * neighbour invalidation ({@code fileApronNeighbors}) can be turned
     * off by the same lever: with no apron there is no cross-record fact
     * to go stale, and the switch has to take the invalidation with the
     * feature or it is not a return to pre18.</p>
     */
    static boolean apronEnabled() {
        String override = System.getProperty("meshelium.farfield.apron");
        return override == null || !"false".equalsIgnoreCase(override.trim());
    }
}
