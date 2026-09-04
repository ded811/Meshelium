/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.farfield.extract;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.farfield.FarField;
import com.deds.meshelium.farfield.store.ShellCodec;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * M5: the pin drain - ONE worker thread ("meshelium-pin-extract",
 * {@code MIN_PRIORITY}, daemon), oldest-first FIFO, off the frame
 * budget entirely (FARFIELD-SAVE-DESIGN.md section 4's PIN class).
 * Oldest-first because it releases memory fastest; distance is
 * irrelevant to a chunk that is already gone - which is also why the
 * pre-M5 retained-list sort has no successor here (the review's
 * deferred bucket-rebuild question answers itself: the structure it
 * would have rebuilt is deleted).
 *
 * <h2>Thread confinement, the whole protocol</h2>
 * <ul>
 * <li>{@link #queue} and {@link #results} are the ONLY structures
 *     shared between the game thread and this worker, and both are
 *     {@code ConcurrentLinkedQueue}s - each transfer is the
 *     happens-before edge the design names. A {@link Pin} is fully
 *     built on the game thread BEFORE {@link #enqueue}, and its walk
 *     outputs are read on the game thread only AFTER the pin comes
 *     back through {@link #results} (or its write's ack comes through
 *     the store's ack queue, which the worker only feeds AFTER posting
 *     the result).</li>
 * <li>The worker reads: the pin (frozen + the volatile
 *     {@code released}), captured {@code DataLayer} bytes (tear-safe
 *     byte reads; a stayed-live neighbour's mutation is fresher
 *     truth), dropped chunks' sections and heightmaps (no writer can
 *     reach an uncached chunk), {@code SpriteUvResolver}'s caches
 *     (concurrent since M5) and the vanilla model/registry/tint tables
 *     vanilla's own section-compile workers read.</li>
 * <li>The worker writes: the pin's walk-output fields, the two queues,
 *     LongAdders, and {@code FarField}'s write queue through
 *     {@link FarField#submitShellFromWorker} - which never opens a
 *     store, never reads client state and never calls the residency.
 *     <b>The residency lock is never touched from this thread</b>; the
 *     one residency notification a pinned shell owes
 *     ({@code onShellWritten}) rides its write ACK and is issued by
 *     the game-thread pump.</li>
 * <li>The record map is game-thread-only, unchanged: the worker never
 *     sees a {@code ColumnRecord}. {@code writingVersion} was stamped
 *     by the game thread at enqueue (I3's one-write-in-flight), and
 *     every outcome travels back as data for the pump to apply.</li>
 * </ul>
 *
 * <h2>Pacing and the two stop conditions</h2>
 * G3: while the store's write queue is saturated the worker parks
 * 50 ms between pins, so it paces itself exactly like the game-thread
 * producers and the eviction arm stays unreachable. A pin whose
 * {@link Pin#storeGeneration} is no longer the OPEN store's generation
 * missed the world-exit drain window ({@code FarField}'s barrier holds
 * the store open for up to 5 s after a level swap while this queue
 * empties): it is counted on {@code farSaveLost} - the design's
 * level-swap rule, data only we held - and dropped, never filed under
 * the next world's key. A dead IO thread parks the drain (1 s naps);
 * the level-swap seam abandons and counts the queue in that state.
 */
final class PinWorker {

    /**
     * The drain thread's name, and since Phase 3 a CONTRACT rather than
     * a label: {@code ShellExtractor.extractCaptured} counts any walk
     * that runs on a thread not called this on
     * {@code ExtractDispatch.farWalksOffWorker}, and the suite asserts
     * that counter at zero. It is the cheapest possible tripwire against
     * the one regression this wave can suffer silently - a walk finding
     * its way back onto the frame's thread.
     */
    static final String WORKER_THREAD_NAME = "meshelium-pin-extract";

    /** SUBMITTED: a shell went to the store; the ack settles the record. */
    static final byte OUTCOME_SUBMITTED = 0;
    /** EMPTY: nothing to store; the pump stamps stored inline (no ack). */
    static final byte OUTCOME_EMPTY = 1;
    /** FAILED: the walk threw; the pump takes the honest GONE_BEHIND. */
    static final byte OUTCOME_FAILED = 2;

    /** The handoff (game thread -> worker), oldest-first by insertion. */
    private static final ConcurrentLinkedQueue<Pin> queue =
            new ConcurrentLinkedQueue<>();
    /** Walked pins travelling back (worker -> pump), every outcome. */
    private static final ConcurrentLinkedQueue<Pin> results =
            new ConcurrentLinkedQueue<>();
    /**
     * Pins accepted and not yet fully processed - includes the one in
     * hand, because it decrements only after the walk completes, so
     * zero means genuinely idle (the world-leave drain barrier's test).
     */
    private static final AtomicInteger pending = new AtomicInteger();
    private static volatile Thread thread;

    private PinWorker() {
    }

    /** Hand one finished pin to the drain. Game thread. */
    static void enqueue(Pin pin) {
        pending.incrementAndGet();
        queue.add(pin);
        Thread running = ensureThread();
        LockSupport.unpark(running);
    }

    /** Pins accepted and not yet walked (gauge; any thread). */
    static int pendingCount() {
        return pending.get();
    }

    /** Next walked pin for the pump to apply, or null. Game thread. */
    static Pin pollResult() {
        return results.poll();
    }

    private static Thread ensureThread() {
        Thread running = thread;
        if (running == null) {
            synchronized (PinWorker.class) {
                running = thread;
                if (running == null) {
                    running = new Thread(PinWorker::run, WORKER_THREAD_NAME);
                    running.setDaemon(true);
                    running.setPriority(Thread.MIN_PRIORITY);
                    running.setUncaughtExceptionHandler((t, e) -> {
                        // Count on the far field's own ledger and allow a
                        // restart: the queue survives, the next enqueue
                        // spins a fresh drain, and nothing renderer-side
                        // is reachable from here (landmine L4).
                        FarField.farStoreErrors.increment();
                        thread = null;
                        MesheliumLog.LOGGER.error(
                                "Far-field pin worker died; it restarts on"
                                        + " the next pin", e);
                    });
                    thread = running;
                    // The world-leave barrier's idle test (FarField holds
                    // the store open until this drain empties or 5 s pass).
                    FarField.registerPinDrainIdle(() -> pending.get() == 0);
                    running.start();
                }
            }
        }
        return running;
    }

    private static void run() {
        while (true) {
            Pin pin = queue.poll();
            if (pin == null) {
                LockSupport.parkNanos(50_000_000L);
                continue;
            }
            try {
                drainOne(pin);
            } catch (Throwable t) {
                // drainOne's own catch covers the walk; this covers the
                // bookkeeping around it. Never let one pin kill the drain.
                FarField.farStoreErrors.increment();
            } finally {
                pending.decrementAndGet();
            }
        }
    }

    private static void drainOne(Pin pin) {
        if (pin.released) {
            return; // E9 / supersede / master-off: the releaser counted it
        }
        if (FarField.ioDead()) {
            // The store died - permanently, for the session (Io.dead has
            // no recovery path): the design's one exception to
            // retry-forever. Counted NOW rather than parked-until-exit,
            // because a parked pin is a chunk held for a store that can
            // never take it, and the count is the same either way. The
            // FAILED result lets the pump settle the record to the
            // repairable GONE_BEHIND (the E9 shape: gauge + lost).
            //
            // Phase 3: a LIVE capture is not lost by any of that. The
            // client still holds the column, the record stays LIVE_DIRTY
            // and re-files at the result, and the store's death is
            // already on farStoreErrors - adding it to farSaveLost would
            // put a repairable column on the gauge whose contract is
            // ZERO (leg (a) asserts exactly that delta).
            if (!pin.live) {
                ExtractDispatch.farSaveLost.increment();
            }
            pin.walkOutcome = OUTCOME_FAILED;
            results.add(pin);
            return;
        }
        if (FarField.storeGeneration() != pin.storeGeneration) {
            // The world's store closed past the drain deadline: data only
            // we held, counted where the owner reads it. Never filed under
            // the next world's key.
            //
            // Phase 3, same distinction: a LIVE capture stranded by a
            // world change is terrain the next visit re-receives, and
            // ExtractDispatch.countUnsavedAtReset already counted its
            // record on farSaveLeftUnsaved at the swap. Counting it again
            // here would double-count it AND move the wrong ledger.
            if (!pin.live) {
                ExtractDispatch.farSaveLost.increment();
            }
            return;
        }
        // G3: producers pace themselves against the store's saturation
        // predicate; the worker is a producer like any other.
        while (FarField.writeQueueSaturated() && !pin.released) {
            LockSupport.parkNanos(50_000_000L);
        }
        if (pin.released) {
            return;
        }
        ShellCodec.Shell shell;
        long start = System.nanoTime();
        try {
            shell = ShellExtractor.extractCaptured(pin);
        } catch (Throwable t) {
            FarField.farStoreErrors.increment();
            pin.walkOutcome = OUTCOME_FAILED;
            results.add(pin);
            return;
        } finally {
            ExtractDispatch.farExtractNanos.add(
                    Math.max(0L, System.nanoTime() - start));
        }
        // The quality byte: side reason bits from the pin's own capture
        // states (a pinned or strip-captured side is KNOWN - the design's
        // whole-quality storm saves), LIGHT_PARTIAL from the walk.
        byte quality = 0;
        if (pin.sidePin[ShellExtractor.SIDE_W] == null
                && pin.strips[ShellExtractor.SIDE_W] == null) {
            quality |= ExtractDispatch.QUALITY_SIDE_WEST;
        }
        if (pin.sidePin[ShellExtractor.SIDE_E] == null
                && pin.strips[ShellExtractor.SIDE_E] == null) {
            quality |= ExtractDispatch.QUALITY_SIDE_EAST;
        }
        if (pin.sidePin[ShellExtractor.SIDE_N] == null
                && pin.strips[ShellExtractor.SIDE_N] == null) {
            quality |= ExtractDispatch.QUALITY_SIDE_NORTH;
        }
        if (pin.sidePin[ShellExtractor.SIDE_S] == null
                && pin.strips[ShellExtractor.SIDE_S] == null) {
            quality |= ExtractDispatch.QUALITY_SIDE_SOUTH;
        }
        if (pin.walkLightPending) {
            quality |= ExtractDispatch.QUALITY_LIGHT_PARTIAL;
        }
        if (quality == 0) {
            quality = ExtractDispatch.QUALITY_WHOLE;
        }
        pin.walkQuality = quality;
        if (shell == null) {
            pin.walkOutcome = OUTCOME_EMPTY;
            results.add(pin);
            return;
        }
        ExtractDispatch.farExtracts.increment();
        ExtractDispatch.farExtractCells.add(shell.cellCount);
        if ((quality & ExtractDispatch.QUALITY_WHOLE) == 0) {
            ExtractDispatch.farExtractIncomplete.increment();
        }
        // Result BEFORE submit: the pump drains results ahead of write
        // acks, so the record's writingQuality is in place before any
        // ack for this version can apply.
        pin.walkOutcome = OUTCOME_SUBMITTED;
        results.add(pin);
        FarField.submitShellFromWorker(pin.chunkX, pin.chunkZ,
                ShellCodec.TIER_VISITED, shell, pin.version);
    }
}
