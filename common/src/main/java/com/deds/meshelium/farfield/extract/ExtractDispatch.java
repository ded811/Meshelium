/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.farfield.extract;

import com.deds.meshelium.farfield.FarField;
import com.deds.meshelium.farfield.FarFieldConfig;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * The extraction trigger layer between the client chunk-lifecycle mixins
 * and the store. One static entry point per seam, all game-thread only
 * (every chunk lifecycle handler passes {@code ensureRunningOnSameThread}
 * against the game thread's PacketProcessor — FARFIELD-VANILLA-SEAM.md
 * section 1.1), all inert while the far field is off.
 *
 * <h2>pre2 change: extraction moved to the RECEIVE side (defect B1)</h2>
 * W2 extracted ONLY at {@code ClientLevel.unload}. The owner's pre1
 * playtest reported "a lot of gaps and missing chunks, worse in parts i
 * havent been to" (docs/FARFIELD-WAVES.md, OWNER PLAYTEST OF pre1, B1).
 * The unload seam cannot be complete, because three paths make a chunk
 * unreachable with no unload callback at all (dossier section 1.3):
 * a view-radius shrink copies only the surviving chunks into a fresh
 * Storage, a view-center move merely slides the valid window, and a
 * level swap or disconnect discards the whole {@code ClientChunkCache}
 * wholesale. Every chunk lost that way was a permanent hole in the cache
 * until the player happened to walk back through it.
 *
 * <p>{@link #onChunkReceived} closes all three at their common source:
 * a chunk can only be lost if it first ARRIVED, so extracting on arrival
 * catches every chunk the server ever sent, whatever kills it later. The
 * seam is {@code ClientPacketListener.enableChunkLight} TAIL — the
 * dossier's "chunk AND light both applied" moment (section 1.4), and
 * javap-confirmed in this authoring session to have exactly ONE call
 * site, {@code lambda$handleLevelChunkWithLight$0} ip 33, so it fires
 * once per chunk delivery and never for a stand-alone light packet.</p>
 *
 * <p><b>What this does NOT fix, stated plainly.</b> The other half of B1
 * ("worse in parts i havent been to") is not a seam bug and no client
 * hook can reach it: the server never sends a chunk the player has not
 * been near, so the client-side cache can only ever hold the union of
 * everywhere the player has loaded. Filling the rest is pre2's
 * save-importer (singleplayer region files) and pre4's background
 * generation. This class now guarantees the smaller, achievable claim:
 * <i>every chunk the server sends is stored exactly once per session</i>.
 * </p>
 *
 * <h2>Neighbour-complete gating (why arrival alone is not the trigger)</h2>
 * {@link ShellExtractor} treats an unloaded lateral neighbour as AIR, and
 * with the apron fix it also needs the neighbour COLUMN heights to know
 * how far down a cliff face must be kept. A chunk that arrives before its
 * neighbours would therefore be extracted with four over-included
 * boundary planes and no apron at those planes. So arrival of chunk C
 * does not extract C; it extracts every position in the PLUS around C
 * (C and its four lateral neighbours) that is now neighbour-complete —
 * a diagonal neighbour cannot be completed by C, because C is not one of
 * its four lateral neighbours. Under the server's distance-ordered send
 * this settles to exactly one extraction per arrival, one chunk behind
 * the frontier, with all four neighbours present.
 *
 * <h2>The per-column state machine (pre16 save design, M2-M4)</h2>
 * One {@code Long2ObjectOpenHashMap<ColumnRecord>} is the ONLY
 * bookkeeping structure: the extract-once tracker, the incomplete marks,
 * the stale marks, the refresh epoch and the deferral list all collapsed
 * into it (docs/FARFIELD-SAVE-DESIGN.md). Truth is a pair of version
 * numbers advanced only by real events - {@code liveVersion} stamps the
 * freshest client truth seen, {@code storedVersion} what the store has
 * ACKED - and <i>dirty</i> is their difference, derived at decision time
 * and never cached (invariant I2). Every writer's old "is this column
 * finished" formula (five variants over eight cached booleans, none
 * consulting the store) is now one derivation, and the store corrects it
 * through {@code FarField.WriteAck} (E8) rather than trusting an
 * optimistic mark. States: {@code LIVE_CLEAN}, {@code LIVE_DIRTY},
 * {@code PINNED} (M5: a {@link Pin} capture, drained by the worker),
 * {@code GONE_SAVED}, {@code GONE_BEHIND} (the only honest loss state -
 * counted on {@link #farSaveBehind()}, repaired on revisit); a column
 * with no record is ABSENT. Redundant re-extraction stays cheap for the
 * old reason: the store accepts an equal-tier overwrite
 * (FarFieldStore's truth-tier rule).
 *
 * <p>Work is a job on one of two ring-bucketed queues (EDIT and FILL,
 * {@link #fileEdit}/{@link #fileFill}), drained nearest-first by the ONE
 * scheduler in {@link #pumpGameThread} - the only thing that spends the
 * game-thread budget. The seams (receive, drop, rd change, block change,
 * the safety-net walk, the on-demand rescue) are event producers: they
 * transition records and file jobs, O(1) each. A block edit is prompt by
 * construction - the E4 hook files an EDIT job coalesced over
 * {@value #EDIT_COALESCE_MILLIS} ms, which replaced the 30-second
 * staleness epoch outright (the epoch was a cost bound for POLLING,
 * applied to events that had already paid their cost).</p>
 *
 * <h2>The game-thread budget, and the render-distance freeze it fixes</h2>
 * Extraction is a synchronous walk over a 16x16 column band plus a 3x3
 * leak flood on the game thread. Post-pre7 that is not cheap:
 * {@code ShellExtractor}'s own 300-chunk census puts {@code closeLeaks}
 * at a mean of 24.7k probes, p95 54.0k and a worst chunk of 83.2k, on top
 * of the base walk's ~26k paletted-container reads. At the ~15 ns per
 * read that class derives, one extraction is order <b>0.8 ms mean,
 * 1.2 ms p95, 1.7 ms worst</b>.
 *
 * <p><b>The freeze, traced end to end (all javap on the 26.2 merged
 * jar, this authoring session).</b> Lowering the render distance is a
 * mass-unload event, and until pre8 every one of those unloads ran a full
 * extraction synchronously:</p>
 * <ol>
 *   <li>the client broadcasts its new view distance; the integrated
 *       server untracks every chunk outside it and calls
 *       {@code PlayerChunkSender.dropChunk} for each, inside ONE server
 *       tick;</li>
 *   <li>each of those is a {@code ClientboundForgetLevelChunkPacket}, and
 *       the client's {@code PacketProcessor.processQueuedPackets} drains
 *       its WHOLE queue in one pass with no per-tick cap (bytecode
 *       ip 7-34 is a bare {@code while (!queue.isEmpty()) poll().handle()});
 *       </li>
 *   <li>{@code ClientPacketListener.handleForgetLevelChunk} ip 12-23 calls
 *       {@code ClientChunkCache.drop}, which calls
 *       {@code Storage.drop(int, LevelChunk)} (ip 59-65), whose tail
 *       ip 28-36 calls {@code ClientLevel.unload} - our seam;</li>
 *   <li>so {@link #onChunkDropping} used to fire ~(2E+1)^2 minus the new
 *       square times in a single tick. Dropping 32 to 8 abandons about
 *       3,900 columns.</li>
 * </ol>
 * <p>Note what is NOT in that chain: {@code ClientChunkCache.updateViewRadius}
 * is called only from {@code ClientPacketListener}'s handler for
 * {@code ClientboundSetChunkCacheRadiusPacket}, whose only constructor
 * site is {@code PlayerList}. On a REMOTE server that means the forget
 * packets carry a client-side render-distance change - but on the
 * INTEGRATED server the client's slider IS the server view distance, so
 * {@code PlayerList} re-broadcasts the radius and {@code updateViewRadius}
 * runs after all (the jar-refuted premise the pre16 audit named as the
 * REAL repro-(a) path). Its silent-abandon path - copy the survivors into
 * a fresh Storage, drop the rest with no unload callback (ip 18-149) -
 * therefore carries real traffic, and {@link #onViewRadiusShrink} (E6)
 * now walks the abandoned annulus through the still-installed old
 * Storage instead of counting it.
 *
 * <p><b>The shape.</b> One budget covers every extraction this class
 * performs, measured in real time and refreshed once per FRAME (see the
 * adaptive-slice section below for what sizes it, and why the refill
 * period used to be 50 ms and was wrong). Nothing here extracts outside
 * it, including the drop seam, which is why the storm is a burst of hash
 * probes instead of seconds of block reads. The pieces:</p>
 * <ul>
 *   <li>{@link #onChunkReceived} (E1) transitions the record and files a
 *       FILL job - it never walks a chunk at the seam;</li>
 *   <li>{@link #onChunkDropping} (E5) CAPTURES a dirty column - a
 *       {@link Pin}: chunk ref, light layer refs, border strips on the
 *       leave reserve - and never extracts (invariant I4, enforced at
 *       M5; the pin worker walks the capture off-thread);</li>
 *   <li>{@link #pumpGameThread} (E7) drains the worker's results and
 *       the store acks, triages the E6 captures, then the EDIT and
 *       FILL queues nearest-first, then advances the safety-net walk over
 *       the loaded window - which FILES jobs for anything unstored and
 *       extracts nothing itself;</li>
 *   <li>{@link #tryExtractLoaded} files a rescue job and answers
 *       "a shell is coming" instead of running a walk of its own.</li>
 * </ul>
 *
 * <h2>The adaptive slice, and the regression a fixed one caused</h2>
 * pre8 sized that budget as a flat 3 ms per 50 ms of WALL CLOCK, and both
 * halves of that were wrong for the way the owner actually plays.
 *
 * <p><b>The refill period.</b> A 50 ms window refills twenty times a
 * second whatever the client is doing. At the owner's measured 120 to 180
 * fps (docs/FARFIELD-WAVES.md, OWNER PLAYTEST OF pre8) that is one slice
 * every six to nine FRAMES: the far field did nothing at all in the other
 * five to eight, and a faster machine bought exactly nothing. The slice is
 * now opened by {@link #pumpGameThread}, once per frame, so throughput
 * follows the frame rate the way every other budget in this mod does. The
 * stall bound is unchanged by that alone, because a per-window budget and
 * a per-frame budget cap a single packet storm at the same figure - a
 * storm lands inside one tick either way.
 *
 * <p><b>The size.</b> 3 ms per 50 ms is 75 columns a second at the
 * measured 0.8 ms per extraction. The owner's workflow is render distance
 * 120, load everything, then drop to 16 with the far ring at 120; the
 * client's storage window at rd 120 is
 * {@code (2 * (120 + 3) + 1)^2 = 61,009} columns, which at 75 a second is
 * <b>thirteen and a half minutes</b> of standing still before the window
 * is saved. It never was saved, so when the render distance dropped there
 * was nothing on disk to draw and the far field showed "mostly empty with
 * little bits here and there". pre8's own note admitted the shape of this
 * at render distance 32 - 5,041 columns, "on the order of a minute of
 * standing still" - and nobody multiplied it out for 120. The fixed
 * budget was not too small by a little; it was smaller than the job by a
 * factor of the render distance squared.
 *
 * <p><b>What replaced it</b> was an adaptive rule built out of measured
 * HEADROOM - a third of the gap between the measured frame and a
 * configured 60 fps floor - plus a KEEP-UP floor sized from the chunks
 * the client accepted last frame. Both of those are gone; see the T2
 * section below for what replaced THEM and why the replacement is a
 * change of goal rather than a retune.
 *
 * <p>{@code FarFieldConfig.adaptiveExtraction()} turns the rule off and
 * restores a flat slice exactly, per the standing uncertainty rule.</p>
 *
 * <h2>pre13 (N1): the adaptive slice was not adaptive, and every column
 * was walked twice</h2>
 * The owner reported the ring failing to fill for the THIRD time
 * (docs/FARFIELD-WAVES.md, OWNER PLAYTEST OF pre12, N1). Three separate
 * things were wrong and they multiplied:
 * <ol>
 *   <li><b>the cap ate the whole rule.</b> pre10's ceiling was
 *       {@code min(MAX_SLICE, max(guaranteed, frame/3))}, which is the
 *       3 ms guaranteed slice exactly for any frame under 9 ms. The
 *       owner plays at 120 fps. So the idle boost computed a 5.6 ms
 *       headroom share and the cap discarded it, every frame, for three
 *       releases: the "adaptive" budget had collapsed back to the pre8
 *       constant on the only machine it was written for. The cap was
 *       then made a function of {@link #backlogged()} rather than of
 *       frame time (and T2 removed both);</li>
 *   <li><b>the idle boost was too small to matter even if it had got
 *       through.</b> It moved the frame-time TARGET and nothing else, so
 *       N1 gave it a bigger share of the headroom as well, armed by
 *       staged VOLUME rather than by quiet. <b>T2 deleted it</b>: that
 *       bigger share is the arm the owner's 200-to-60 report names, and
 *       an escalation whose whole purpose is to trade frame rate for
 *       fill rate has no place in a rule whose purpose is the
 *       opposite;</li>
 *   <li><b>Real Light doubled the work.</b> Every receive-seam column
 *       was extracted twice - pre13's light deferral removed the second
 *       walk by postponing the first one past {@code runLightUpdates}.
 *       The state machine keeps that property structurally: arrivals
 *       file jobs and ALL extraction runs at the pump, which
 *       {@code Minecraft.runTick} reaches after {@code ClientLevel
 *       .update()} has published the frame's light, so the whole
 *       deferral apparatus (list, dedupe set, liveness probe) is
 *       deleted rather than preserved.</li>
 * </ol>
 * <p>The sweep also stopped rastering: it walks Chebyshev rings from the
 * camera outward now, so a half-finished pass is a DISC rather than a
 * band. That is not a throughput change, it is an answer to "it being
 * patchy with random chunks looks way worse then not having it".</p>
 *
 * <p><b>The stall guarantee is untouched.</b> {@link #MAX_SLICE_NANOS} is
 * the same 16 ms and nothing may raise it. Since T2 it is also
 * unreachable: the fill line caps at 2 ms and the leaving line at 2 ms,
 * so a saturated slice is 4 ms, and the worst frame this class can
 * produce is one walk over its grant rather than a whole slice over it.
 * Nothing here can reach the pre8 freeze.</p>
 *
 * <h2>T2 (pre21): the budget is a FRACTION of the measured frame</h2>
 * The owner's report is the whole of this section:
 *
 * <blockquote>"saving the chunks to lod causes HUGE fps drops. this
 * drops 200 to 60 while its going on. when its doing constant saves.
 * this is a bad experience. especially for minecraft since a lot of game
 * modes teleport you around a lot. i need you to drastically improve
 * fps."</blockquote>
 *
 * <p><b>Why it was exactly 60.</b> Not a hot loop and not a leak. Every
 * budget above this line is an absolute count of milliseconds, and each
 * was sized against a machine that ran 120 to 180 fps. But the deeper
 * fault is not the unit - it is the GOAL. The rule's target was a
 * frame-rate FLOOR ({@code extractFrameRateFloor}, shipped at 60), and
 * its headroom term spent the gap down toward it on purpose: at 200 fps
 * a 5 ms frame showed 11.7 ms of apparent headroom, the rule took a
 * third of it, and the idle boost took half of an even bigger gap to a
 * 25 ms target when the player stood still. <b>The owner sees 60 fps
 * because 60 fps is the number the algorithm was defending.</b> It was
 * not misbehaving; it was succeeding at the wrong objective.</p>
 *
 * <p><b>The replacement is scale-free.</b> {@link #sliceBudgetFor} takes
 * a SHARE of the frame the player is actually getting - a tenth, capped
 * at 2 ms, floored at 0.25 ms - and has no target frame rate at all. At
 * 300 fps that is 0.33 ms; at 200, 0.5 ms; at 120, 0.83 ms; at 60,
 * 1.67 ms; at 30 the cap binds and a slow laptop takes 2 ms of a 33 ms
 * frame and keeps saving. The same rule, unchanged, is correct on all of
 * them, which no absolute millisecond count and no frame-rate floor can
 * be. The leaving reserve is on the same discipline. <b>Nothing in this
 * class may reintroduce a target frame rate in any spelling</b> - see
 * the T2 note beside the budget constants for the full list of what died
 * and what it cost.</p>
 *
 * <h2>T2 Phase 3: the atom, and why the budget rule was not enough</h2>
 * Phase 2 tested the budget before a walk and never during one, because
 * the work unit - one {@code ShellExtractor.extract} call - took no
 * deadline and kept no resumable state. It funded that indivisible walk
 * from a cross-frame credit bucket so a walk bigger than one frame's
 * grant could still run.
 *
 * <p><b>Then the far-armed bench measured the walk, and the number was
 * not the census's ~0.9 ms.</b> At a pinned 200 fps (5.0 ms frames), on
 * a travel leg over fresh ground: {@code walk_p50 = 6.816 ms},
 * {@code walk_p99 = 20.972 ms}, {@code walk_max = 23.057 ms}. Phase 2's
 * rule did everything a rule can do - mean far-field cost per frame fell
 * from 3.99 ms to 0.274 ms, a 14x cut, and the count of frames that
 * overran their grant fell from 1,369 to 210 - and the overruns
 * themselves did not move at all ({@code overrun_p95} 11.5 -> 13.6 ms,
 * worst slice still 23 ms). <b>A scheduler cannot subdivide an atom.
 * Only a smaller atom can.</b>
 *
 * <p><b>So the atom is the wave.</b> The game thread no longer walks a
 * column; it FREEZES one ({@link ColumnSnapshot}) and hands it to
 * {@link PinWorker}, which is the same capture-and-walk split M5 built
 * for leaving columns, now carrying both sources. What is left on the
 * frame's thread per column is a section-copy freeze and up to four
 * border strips, each of them its own step with the grant tested
 * between them ({@link #finishPin}'s resume cursor), so the worst
 * single-frame overshoot is one step of tens of microseconds instead of
 * one walk of tens of milliseconds. The credit bucket goes with the
 * atom that needed it: a dozen steps fit inside a 0.5 ms grant, so a
 * plain fit test against this frame's remainder terminates.</p>
 *
 * <p><b>Pacing is by the worker, not by a clock</b>
 * ({@link #MAX_WORKER_BACKLOG}): no column is frozen while the worker is
 * already 32 columns behind. That is the rule the brief asked for and it
 * needs no tuning, but it also names the honest limit of this wave -
 * <b>the work did not get cheaper, only better placed</b>. Throughput is
 * now set by one worker thread at the measured walk cost, and a worker
 * POOL (the brief's section 8) is the next thing that buys fill rate.
 * The frame, which is what the owner reported, is fixed here.</p>
 *
 * <p><b>What it costs, stated rather than buried.</b> Phase 2's deleted
 * keep-up term is still deleted, so a travel leg still stores more
 * slowly than the old rule did per second of wall clock until a worker
 * pool lands. The population that term protected is still protected - a
 * column that arrives dirty and leaves before the fill queue reaches it
 * is CAPTURED at the drop seam and walked by the same worker - so this
 * is a fill-RATE cost, not a loss.</p>
 *
 * <h2>pre14: the order it saves in, the rate, and the staleness</h2>
 * Three of the owner's pre13 items land in this class and they are one
 * subject seen from three sides - WHEN a column is written, in WHAT
 * ORDER, and whether what is written is still true:
 * <ul>
 *   <li><b>O2, "as soon as a chunk loads".</b> An arrival files a job
 *       and the pump takes it later in the SAME frame, after
 *       {@code runLightUpdates}. The two gates that remain are
 *       deliberate and neither can be lifted for free:
 *       {@link #neighborsLoaded} as a FILING heuristic (filing a
 *       frontier column before its outward neighbour arrives buys a
 *       degraded shell plus an upgrade walk - the double work pre13
 *       removed; a popped job races nothing, because completeness only
 *       LABELS the outcome), and {@link #sliceBudgetFor} itself - the
 *       integrated server delivers up to
 *       {@code PlayerChunkSender.MAX_CHUNKS_PER_TICK = 64.0f} chunks a
 *       tick, i.e. 1,280 a second, and at the measured ~1 ms a walk
 *       that is more game-thread time per second than a second has;</li>
 *   <li><b>O3, "instead of center out ... the chunks at the end of your
 *       render distance".</b> The server sends nearest first
 *       ({@code PlayerChunkSender.collectChunksToSend} sorts by
 *       {@code ChunkPos.distanceSquared} on both arms - javap, 26.2
 *       merged jar), and the ring-bucketed queues preserve that:
 *       lowest non-empty Chebyshev ring first, FIFO within a ring, and
 *       a popped entry more than two rings out of place is re-filed
 *       rather than run. (The retained queue this bullet used to cover
 *       died at M5: the pin worker drains oldest-first, because
 *       distance is irrelevant to a chunk that is already gone);</li>
 *   <li><b>O5, the stale cache.</b> Version arithmetic now: the E4 hook
 *       stamps {@code liveVersion} on every real client block change,
 *       and a stored version behind it IS the staleness.</li>
 * </ul>
 * <p>O4, the Background Saving row, is read here in exactly one place
 * ({@link #resolveCacheSpeed}, once per slice, and handed to
 * {@link #sliceBudgetFor} as a parameter so the rule itself stays pure)
 * and changes no behaviour of this class other than the size of its own
 * ceiling. Since T2 it scales the FRACTION, the floor and the cap
 * together rather than an absolute millisecond count.</p>
 *
 * <h2>pre15 (P3): the leaving path had no budget, no urgency and no
 * measurement</h2>
 * The owner has now asked for the same thing four playtests running, and
 * pre15 is the first answer that is not another scheduling tweak
 * (docs/FARFIELD-WAVES.md, OWNER PLAYTEST OF pre14, P3: "it should always
 * cache, especially when leaving a chunk"). Every wave from pre10 to
 * pre14 changed how the FILL work was ordered or funded; none of them
 * looked at what happens to a column on its way out. Four things did:
 * <ul>
 *   <li><b>The drop seam was insolvent by construction.</b> It tested
 *       {@link #budgetLeft}, and a forget packet is handled before the
 *       frame's slice is opened, so it spent the previous frame's
 *       remainder - which {@link #sweepLoadedWindow} zeroes whenever
 *       anything is owed. The LEAVING RESERVE gives that path its own
 *       line, inside the same stall guarantee (T2 made it a fraction of
 *       the measured frame like everything else here);</li>
 *   <li><b>the 30-second staleness epoch gated the LAST CHANCE.</b> A
 *       column re-saved once in the current epoch was skipped outright at
 *       the drop seam, so up to thirty seconds of building went to the
 *       horizon in its pre-edit form every time the player walked away.
 *       pre15 punched a last-chance hole in the throttle; M3 deleted the
 *       throttle itself (version arithmetic needs no cost bound - the E4
 *       hook is O(1) and the EDIT coalesce window bounds farm churn);</li>
 *   <li><b>the stand-down gagged the drop seam</b> during exactly the
 *       event - a render-distance change - that abandons the most
 *       terrain. It no longer does; the bound is the reserve;</li>
 *   <li><b>nothing measured any of it.</b> There was no drop-seam counter
 *       at all, and no saved-versus-wanted figure anywhere in the game.
 *       P3 answered with a sweep-pass census of the loaded window; M6
 *       replaced that with {@link #saveCensus} - a fold over the record
 *       map, which sees every column that ever produced an event rather
 *       than only the ones still loaded - shown on the Layer 1 page.</li>
 * </ul>
 * <p>The one supporting change that survived the M2-M4 rebuild - the
 * retain queue's heap ladder - died at M5 with the queue itself: the
 * {@link Pin} capture has no count cap at all (the design's memory
 * argument), and the heap's last word is E9's counted backstop
 * ({@link #admitHeapBackstop}).</p>
 *
 * <h2>What pre9 changed, and why (playtest item J1, the corners)</h2>
 * Three of the pieces above were load-bearing in a way pre8 did not
 * intend, and all three fail in the same direction: they stop covering
 * the OUTER edge of the loaded window exactly while the player is moving,
 * which is the edge the far field has to take over from the near field.
 * <ol>
 *   <li><b>The sweep was off for the whole of every travel leg.</b> Its
 *       gate was {@code hasRenderedAllSections()}, and a moving camera
 *       keeps vanilla's compile queue non-empty continuously. It is now a
 *       throttle instead ({@link #SWEEP_PROBES_PER_PUMP_BUSY}) and cannot
 *       cost more game-thread time either way, because every walk it
 *       finds is paid for out of the same budget.</li>
 *   <li><b>A degraded shell was locked in for the session.</b> The drop
 *       seam extracts without the neighbour gate — it must, it is the last
 *       chance — and then remembered the column WHOLE, after which every
 *       other writer short-circuited on the tracker and the missing apron
 *       and spurious boundary plane were permanent. The record's quality
 *       byte carries the per-side reason bits now, and an arrival that
 *       completes the plus files the upgrade (E2 fanout in
 *       {@link #onChunkReceived}).</li>
 *   <li><b>A lost write became an endless re-read.</b> See
 *       {@link #tryExtractLoaded}: the tracker was being read as proof
 *       that a record exists, which it is not.</li>
 * </ol>
 *
 * <p><b>Standing down.</b> {@link #setStandDown} is the walker's storm
 * switch (see {@code FarFieldResidency}'s vanilla-reload detection).
 * Since M4 it is a FILL pacing hint and no longer a correctness actor:
 * while it is set the FILL drain and the safety-net walk pause, but records
 * still transition, jobs still file, EDIT jobs still run (an edit is the
 * player's own work and outranks a rebuild storm), the drop seam still
 * spends its reserve on captures, the E6 triage still runs, and the pin
 * worker never so much as notices - a stand-down is a game-thread storm
 * signal and the worker is not on the game thread. See
 * {@link #pumpGameThread}.
 *
 * <h2>The rd-change seams act now (E6), in two phases</h2>
 * {@link #onViewRadiusShrink} CAPTURES the abandoned annulus of a
 * server-view-distance shrink through the still-installed old Storage
 * (the radius packet precedes the forget storm on the same ordered
 * stream; after HEAD returns the annulus is unreachable, so this half
 * cannot be deferred): a ring-ordered, inner-first ref-grab loop - one
 * getChunk and a record probe per cell - clean records settle inline,
 * the dirty minority becomes {@link Pin}s WITH their light (deferring
 * the light loses it to the forget flood's removal drain), under the
 * two counted valves {@link #captureLeaving} documents. The TRIAGE -
 * side/strip resolution plus one record transition per pin - runs on
 * the pump, a bounded count per frame ({@link #drainLeavingCaptures}),
 * pacing its strips on the leave reserve; the walks themselves are the
 * worker's. {@code updateViewCenter} captures through the SAME
 * machinery - its old "Storage.replace evicts them through unload"
 * premise is false past the send pad ({@code ClientChunkCache.drop}
 * no-ops out-of-range at ip 12-18, and replace only fires for slots the
 * new send disc refills), which gametest (b)'s first real run proved
 * with a lost edit. The level swap hands the worker its 5 s drain
 * window (the store's close is sequenced behind it - see
 * {@code FarField.onWorldLeave}); pins the deadline strands are counted
 * on {@link #farSaveLost}, the discarded dirty window on the aggregate,
 * so a portal hop still never moves the zero-is-the-contract gauges
 * silently.
 *
 * <h2>The armed fast path</h2>
 * Mixin handlers gate on {@link #armed()} — one static volatile read, no
 * config lookup per call. The volatile resolves from
 * {@code FarFieldConfig.enabled()} once at class load;
 * <b>{@link #refreshArmed()} MUST be called by whatever flips the master
 * switch</b> (FarField's enable/disable path, the Far Terrain screen) or
 * a live toggle will not reach these seams until the next session. Class
 * load allocates the counter LongAdders and nothing else — the record
 * map, the job queues and the pin structures are created on
 * first ARMED use — so the master-off zero-cost claim holds to the same
 * standard the other counter holders meet. {@link #pumpGameThread} is
 * reached only from the walker's pump, which
 * {@code TerrainResidency.pumpFarFieldOutsideLock} gates behind its
 * sticky {@code farEverArmed} latch, so master-off never calls it either.
 * {@link #refreshArmed()} releases the pins when the switch goes OFF,
 * because holding tens of megabytes of terrain for a disabled feature
 * is the same promise broken a different way.
 *
 * <p>Counters are public LongAdders for the bench export, same pattern as
 * the terrain counters. Game-thread confinement means the view
 * bookkeeping, the record map and the job queues need no
 * synchronization; the LongAdders are safe from any thread regardless.</p>
 */
public final class ExtractDispatch {

    /** Shells actually submitted to the store (empty extractions skip). */
    public static final LongAdder farExtracts = new LongAdder();

    /** Total cells across all submitted shells. */
    public static final LongAdder farExtractCells = new LongAdder();

    /**
     * M3/M4: the honest loss gauges, replacing the deleted upper-bound
     * and drop counters as the owner-facing ledger.
     *
     * <p>{@link #farSaveLost} is MONOTONE and permanent: a pinned column
     * stranded past a level swap's drain window, released by E9's heap
     * backstop or a master-off, or a store that died under an
     * unrecoverable write. <b>Zero is the contract</b>; any nonzero value is a bug or
     * the counted backstop doing its counted job. The GONE_BEHIND gauge
     * ({@link #farSaveBehind()}) is the repairable class - a column whose
     * only copy left reach before it could be captured - and E1 repair
     * decrements it when the player revisits.</p>
     */
    public static final LongAdder farSaveLost = new LongAdder();

    /**
     * M3: E4 block-change events folded into an EDIT job already queued
     * for that column. This is the coalesce window working - a farm or a
     * fill churns this counter instead of the extraction budget.
     */
    public static final LongAdder farSaveEditsCoalesced = new LongAdder();

    /**
     * M3: columns whose stored shell was DEGRADED (a side unknown, or
     * light partial) at the moment the client let go of them - the drop
     * transition closes them GONE_SAVED in one probe rather than
     * re-walking a column whose missing neighbours are leaving in the
     * same storm. The design keeps this number to measure whether an
     * upgrade-pin class is ever worth adding (M5's question).
     */
    public static final LongAdder farSaveDegradedLeft = new LongAdder();

    /**
     * Terrain that left the client with NOTHING WORTH GRIEVING on disk
     * and nothing to repair: never-stored columns beyond the retain
     * cap in an rd-shrink storm, and the dirty window a level swap
     * discards. Deliberately NOT {@link #farSaveLost} (that class is
     * data we HELD and let die - pins dropped at a swap, a dead store)
     * and NOT the behind gauge (no record exists to repair): a revisit
     * simply re-receives and re-extracts. Kept separate precisely so
     * the Layer 1 "lost" line can stay at zero across a routine slider
     * move or portal hop while still accounting for every column.
     */
    public static final LongAdder farSaveLeftUnsaved = new LongAdder();

    /**
     * Tripwire: {@link #farSaveBehind()}'s gauge tried to go negative.
     * Structurally impossible - the gauge moves only inside
     * {@link #leaveBehind} and {@link #repairBehind}, which are guarded
     * by the state byte - so any nonzero here is an accounting bug that
     * would otherwise hide inside a clamped readout. The suite asserts
     * zero.
     */
    public static final LongAdder farSaveGaugeUnderflow = new LongAdder();
    /**
     * Columns RESCUED on demand because the far field wanted them and
     * the store had nothing, while the client still held the real chunk
     * ({@link #tryExtractLoaded} - since M4 it files the job the
     * scheduler runs, so this counts filings, not walks). This is the
     * blank ring between the near field's edge and the far ring closing
     * itself; a healthy travelling session shows this climbing steadily.
     */
    public static final LongAdder farExtractOnDemand = new LongAdder();

    /**
     * M5: pins ADMITTED - leaving columns whose extraction needs were
     * captured (chunk ref, light layer refs, sides) and handed to the
     * worker. A steady trickle while travelling is ordinary drops; a
     * storm's worth on a render-distance shrink is E6 working.
     */
    public static final LongAdder farSavePins = new LongAdder();

    /**
     * M5: pins RELEASED - settled by an ack or an empty walk, superseded
     * by a fresh arrival (E1), or discarded by the counted backstops.
     * Tracks {@link #farSavePins} a drain-length behind; the difference
     * is {@link #pinnedCount()}.
     */
    public static final LongAdder farSavePinReleases = new LongAdder();

    /**
     * M5: cells the E6 capture walk never reached because its HARD
     * valve cut it (the geometric remainder, an upper bound) - the ONE
     * producer, since leg (a)'s first real run deleted the other (the
     * drop seam's per-side strip skip: a whole forget storm against one
     * frame's leave reserve counted 521 of 673 pins' sides, so strips
     * moved wholly to the triage, where they DEFER on the refilling
     * reserve and are never skipped at all). The bound's own honesty
     * counter: gametest (a) asserts it ZERO for the whole rd storm,
     * which is what "the capture bound was never hit" means as a
     * number.
     */
    public static final LongAdder farSaveCaptureSkipped = new LongAdder();

    /**
     * M5: pins captured past the E6 LIGHT valve - geometry pinned, light
     * left uncaptured (flat plane, LIGHT_PARTIAL, upgradeable). Separate
     * from {@link #farSaveCaptureSkipped} because the terrain is NOT
     * lost, only its plane. Zero in gametest (a)'s storm.
     */
    public static final LongAdder farSaveLightUncaptured = new LongAdder();

    /**
     * M5, G2's leak tripwire: pin admissions past {@code PIN_HARD_CAP}.
     * NOT a refusal (the design forbids a count cap on admission; the
     * heap backstop E9 is the only sanctioned discard) - a nonzero here
     * means pins are not settling and something is leaking them.
     */
    public static final LongAdder farSavePinTripwire = new LongAdder();

    /**
     * Columns the safety-net walk found wanting - unstored, or dirty with no
     * job on a queue - and FILED for the scheduler (it extracts nothing
     * itself since M4). Large right after Far Terrain is switched on
     * mid-session and near zero once the loaded window is covered.
     */
    public static final LongAdder farExtractSweepExtracts = new LongAdder();

    /**
     * Shells submitted while at least one lateral neighbour was NOT
     * loaded (the record's quality byte carries which side, and whether
     * the light plane was partial). A steady trickle while travelling is
     * the outermost row of the send disc doing what it structurally
     * must; a large number next to {@link #farExtractUpgrades} means the
     * upgrades are not happening.
     */
    public static final LongAdder farExtractIncomplete = new LongAdder();

    /**
     * Degraded shells replaced by a whole re-extraction, counted at the
     * write ACK (the store confirmed the better shell). In a healthy
     * travelling session this tracks {@link #farExtractIncomplete} a few
     * seconds behind; a flat zero means the E2 fanout is not firing.
     */
    public static final LongAdder farExtractUpgrades = new LongAdder();

    /**
     * Extraction attempts refused because the game-thread budget for this
     * window was spent, or because the far field was standing down through
     * a vanilla reload. Not a failure: this counter moving is the whole
     * point of the budget. Read it against {@link #farSaveBehind()} -
     * refusals with nothing behind is the budget pacing work; since M5 a
     * refusal can never lose a LEAVING column at all (leaving columns are
     * pinned, not walked, and the worker is off this budget entirely).
     */
    public static final LongAdder farExtractBudgetRefusals = new LongAdder();

    /**
     * Game-thread nanoseconds actually spent inside {@code ShellExtractor}
     * this session. The one number that makes the budget auditable from
     * outside: divided by the session's wall clock it IS the duty cycle
     * the far field took from the game thread, and divided by
     * {@link #farExtracts} it is the real per-chunk cost on this machine
     * and this terrain, which is the figure every sizing argument in this
     * class used to have to assume.
     */
    public static final LongAdder farExtractNanos = new LongAdder();

    /**
     * Extraction slices that spent their whole budget, i.e. frames where
     * the far field had more to do than it was allowed. Read against
     * {@link #farExtractSlices}: a high ratio while the horizon is still
     * filling means the budget is the limiter and the frame-rate floor
     * has room to come down; a low ratio means the far field has caught
     * up and the budget is costing nothing.
     */
    public static final LongAdder farExtractSlicesSpent = new LongAdder();

    /** Extraction slices opened (one per far-field pump). */
    public static final LongAdder farExtractSlices = new LongAdder();

    /**
     * N1's idle boost, RETIRED at the T2 budget wave (Phase 2 of
     * docs/FARFIELD-PERF-BRIEF.md). <b>Permanently zero.</b>
     *
     * <p>The counter is kept, and kept in the bench's export, precisely
     * so the deletion is READABLE as a number: a pre21 log shows this
     * climbing every frame the player stood still with a backlog, and
     * the owner's "drops 200 to 60 ... when its doing constant saves"
     * is that arm spending half the headroom to a 25 ms target. Any
     * nonzero value here after this wave means the escalation was
     * re-introduced somewhere, which is the one thing the brief says
     * must not happen.</p>
     */
    public static final LongAdder farExtractIdleBoosts = new LongAdder();

    // ------------------------------------------------------------------
    // T2 / Phase 2: the fraction rule, measured. Every counter below
    // exists because the owner's report ("this drops 200 to 60") could
    // not be checked against anything the mod itself reported: the only
    // budget numbers were a microsecond gauge of the CEILING and a count
    // of slices, neither of which says what SHARE OF THE FRAME the far
    // field actually took. The bench reads these; they are named for
    // what they measure and not for the wave that added them.
    // ------------------------------------------------------------------

    /**
     * Sum over slices of the budget the rule GRANTED, nanoseconds.
     *
     * <p>Divided by {@link #farBudgetFrameNanos} this is <b>the fraction
     * the rule took</b>, which is the one number the whole wave is about:
     * it must sit at the configured share (a tenth at the shipped
     * Background Saving point) whatever the frame rate, and the moment it
     * does not, either the cap or the floor is binding and
     * {@link #farBudgetCapBound} / {@link #farBudgetFloorBound} say
     * which. Divided by {@link #farExtractSlices} it is the mean ceiling
     * in nanoseconds, i.e. the old {@link #budgetMicrosNow()} gauge
     * averaged over a whole scenario rather than sampled.</p>
     *
     * <p>A GRANT is not a spend: the budget is a ceiling and
     * {@link #farExtractNanos} is what was actually taken. The pair is
     * deliberate - grant/frame is the RULE, spend/frame is the COST, and
     * a bench that reports only one of them cannot tell a budget that is
     * too generous from a backlog that is too small.</p>
     */
    public static final LongAdder farBudgetGrantedNanos = new LongAdder();

    /**
     * Sum over slices of the MEASURED frame time the rule used,
     * nanoseconds - the smoothed {@code Minecraft.getFrameTimeNs()}
     * reading ({@link #frameNanosEma}), not the wall clock between
     * pumps and not the vsync target.
     *
     * <p>The denominator of every fraction the bench computes, and the
     * evidence that the rule was reading a sane frame at all: divided by
     * {@link #farExtractSlices} it is the mean frame time the far field
     * believed it was living in, which a bench can check against its own
     * per-frame timings. A large disagreement means the smoothing is
     * lying (a loading screen leaking into the EMA, or the clamp in
     * {@link #beginSlice} rejecting real samples), and every fraction
     * below it is then meaningless.</p>
     */
    public static final LongAdder farBudgetFrameNanos = new LongAdder();

    /**
     * Slices where the HARD CAP bound - the measured frame was long
     * enough that the configured share exceeded the cap, so the far
     * field took the cap instead.
     *
     * <p>This is the arm that keeps a slow machine SAVING: at a 33 ms
     * frame a tenth is 3.3 ms and the cap hands back 2 ms, which is 6%
     * of that frame rather than 10%. Expected to be at or near
     * {@link #farExtractSlices} on a 30 fps laptop and at zero on the
     * owner's machine; a nonzero value on a fast client means the frame
     * time the rule read was not the frame time the player experienced.</p>
     */
    public static final LongAdder farBudgetCapBound = new LongAdder();

    /**
     * Slices where the FLOOR bound - the measured frame was short enough
     * that the configured share fell under the floor, so the far field
     * took the floor instead.
     *
     * <p>The arm that keeps a very fast machine saving at all: at a 2 ms
     * frame (500 fps) a tenth is 0.2 ms, which is under one fifth of a
     * single column walk and would stall the horizon forever. The floor
     * is the smallest slice worth opening, and a client that spends all
     * its time here is a client whose fill rate is set by the floor
     * rather than by the share.</p>
     */
    public static final LongAdder farBudgetFloorBound = new LongAdder();

    /**
     * Capture steps the scheduler REFUSED TO START because the measured
     * recent cost of one ({@link #captureCostNanosEma}) would not fit in
     * what is left of this frame's grant - the in-loop clock test, in
     * the form the brief originally asked for and Phase 2 could not
     * provide.
     *
     * <p>Read it against {@link #farExtractBudgetRefusals}, which counts
     * every refusal including this one. Under Phase 2 a high share here
     * was the rule working as designed (the grant was 0.5 ms and the
     * atom was 3-7 ms, so most frames legitimately refused and the
     * credit bucket carried the remainder). Under Phase 3 the atom is a
     * dozen times smaller than the grant, so a high share here means the
     * grant is genuinely spent - by strips, by a very fast frame hitting
     * the 0.25 ms floor, or by the user ceiling - and not that the rule
     * is fighting its own work unit.</p>
     */
    public static final LongAdder farBudgetNoFitRefusals = new LongAdder();

    /**
     * Total nanoseconds by which slices OVERSHOT their own grant, summed
     * - the honest price of a work unit that cannot be interrupted.
     *
     * <p>A step is started when the grant covers its MEASURED AVERAGE
     * cost and then runs to completion, so a step that comes in over the
     * average overshoots by the difference. <b>This is the number Phase 3
     * exists to collapse.</b> The far-armed bench measured Phase 2's
     * travel leg at {@code overrun_p95 = 13.6 ms} and a worst slice of
     * 23 ms - the whole of one walk, landing in a 5 ms frame - and no
     * budget rule could have done better, because the atom was bigger
     * than the frame. With the atom at tens of microseconds this should
     * read as noise: divided by {@link #farExtractSlices} it is the mean
     * per-frame overshoot, and {@link #worstOverrunMicros()} is the tail
     * the owner actually feels as a stutter.</p>
     */
    public static final LongAdder farBudgetOverrunNanos = new LongAdder();

    /**
     * <b>Phase 3:</b> LIVE columns FROZEN into a {@link ColumnSnapshot}
     * and handed to {@link PinWorker} - the fill/edit path's own volume,
     * where {@link #farSavePins} is the leaving path's. Their sum is
     * every column the worker was asked to walk.
     *
     * <p>Zero while the far field is armed and columns are owed means
     * the capture path is not running at all, which is the one failure
     * this wave can have that still looks green: the frame cost would be
     * perfect and nothing would ever be stored.</p>
     */
    public static final LongAdder farCaptures = new LongAdder();

    /**
     * <b>Phase 3:</b> game-thread nanoseconds spent CAPTURING - freezes
     * plus border strips, the whole of what the far field still does per
     * column on the frame's thread.
     *
     * <p>{@code farCaptureNanos / farCaptures} is the per-column
     * game-thread price and the direct successor to the bench's
     * {@code walk_*} figures; {@code farExtractNanos / farExtracts} is
     * the per-column WORKER price, which is the same work as before and
     * is not expected to fall much. Reading the two together is how the
     * report distinguishes "the frame got cheaper" (it should) from "the
     * work got cheaper" (it mostly did not).</p>
     */
    public static final LongAdder farCaptureNanos = new LongAdder();

    /**
     * <b>Phase 3:</b> captures refused because {@link PinWorker} was
     * already {@value #MAX_WORKER_BACKLOG} columns behind - the pacing
     * rule's own counter.
     *
     * <p>This is the number that says the pipeline is WORKER-BOUND, and
     * on current measurements it is expected to be large. One worker
     * thread at the measured walk cost stores on the order of 150-1,400
     * columns a second depending on how cold the ground is; the game
     * thread can capture several times that inside a tenth of a frame.
     * A large count here is therefore not a defect of this rule - it is
     * the rule correctly declining to build a queue - but it IS the
     * evidence for adding worker threads, which is the next wave and not
     * this one.</p>
     */
    public static final LongAdder farCaptureWorkerFull = new LongAdder();

    /**
     * <b>Phase 3:</b> capture finishes DEFERRED to a later frame because
     * the grant ran out between border strips - the mechanism that keeps
     * the game-thread atom down to one strip instead of one whole
     * capture. Latency, never loss: the frozen snapshot waits, and the
     * next pump resumes it at the side it stopped on.
     */
    public static final LongAdder farCaptureDeferrals = new LongAdder();

    /**
     * <b>Phase 3's structural tripwire:</b> shell walks that ran on a
     * thread other than {@link PinWorker}'s.
     *
     * <p><b>Zero is the contract.</b> The whole wave is one claim - that
     * the game thread has stopped walking live chunk state - and this is
     * that claim expressed as a number a test can read, counted at the
     * single walk entry point rather than inferred from a frame-time
     * distribution somebody has to interpret. A regression that puts a
     * walk back on the frame's thread (a well-meant "just do it inline
     * for this one case") moves this counter on its first execution,
     * where the frame-time evidence for the same regression would need a
     * bench run and an argument.</p>
     */
    public static final LongAdder farWalksOffWorker = new LongAdder();

    /**
     * Columns the safety-net walk found wanting and FILED on its last
     * complete pass over the loaded window. Not a rate: a per-pass
     * census figure (the live backlog input to the budget rule is the
     * job queues now, {@link #jobsQueued}). Watch it fall to zero -
     * that is the horizon being saved.
     */
    public static final LongAdder farExtractSweepOwed = new LongAdder();

    /**
     * A stored column's shell went OUT OF DATE: the E4 block-change hook
     * (or a re-send at E1) stamped a fresh {@code liveVersion} over a
     * column whose stored version was current a moment before.
     *
     * <p>Never zero in a world the player is playing in rather than
     * flying over: every block the server sets on the client lands in
     * the E4 hook, and the server sets blocks for crops, water, fire,
     * leaves and redstone as well as for the player's own building. A
     * flat zero while the player is placing blocks means the E4 mixin is
     * not firing at all.</p>
     */
    public static final LongAdder farExtractStaleFiled = new LongAdder();

    /**
     * Stale columns actually re-written, counted at the write ACK of a
     * column that already had a stored version. Should track
     * {@link #farExtractStaleFiled} a second or two behind; a large
     * gap means the extraction budget is not reaching them, which at
     * Background Saving Gentle is expected and otherwise is not.
     */
    public static final LongAdder farExtractStaleRefreshes = new LongAdder();

    /**
     * E10 (pre19 S4): stored columns re-dirtied because VANILLA'S OWN
     * light engine said their light changed - overwhelmingly the
     * NEIGHBOURS of an edited column, which is the whole point of the
     * event (a torch is 14 blocks of reach and a chunk is 16 wide, so
     * an edge torch changes the light stored for the column next door
     * and E4, which knows only the edited column, could never see it).
     *
     * <p>Reads as a fanout ratio against {@link #farExtractStaleFiled}:
     * roughly zero for a player building in the middle of a chunk in
     * the dark, a few per edit at a chunk plane in the open. A flat
     * zero while the owner places torches near a boundary means the
     * {@code ClientChunkCacheLightMixin} hook is not firing; a ratio
     * far above ~8 per edit means the arrival-halo suppression in
     * {@link #onLightChanged} is not holding and the mirror is
     * re-extracting settled ground.</p>
     */
    public static final LongAdder farLightCrossChunkDirty = new LongAdder();

    // ------------------------------------------------------------------
    // P3: the leaving path, measured. Every counter below exists because
    // the pre14 answer to "it should always cache, especially when
    // leaving a chunk" could not be checked against anything - the drop
    // seam had no counter of its own at all, so four waves of scheduling
    // work were argued about a seam nobody could see.
    // ------------------------------------------------------------------

    /**
     * Columns whose write the store acknowledged as LOST (M1: one per
     * failed {@code FarField.WriteAck}, incremented at the pump's drain
     * as the exact victim is un-remembered). EXACT now, where it used to
     * be a LongAdder-delta guess that blamed whichever column happened to
     * submit as the queue evicted somebody else. Should be zero; a
     * nonzero value means the IO thread is the bottleneck, not the game
     * thread, and no amount of budget will help -
     * {@code FarField.farSaveAckFailed} is the same number counted at the
     * boundary, and the two must track (the drain consumes every ack).
     */
    public static final LongAdder farExtractWriteLost = new LongAdder();

    /**
     * Frame time assumed for the first slice of a session, in
     * nanoseconds - there is no frame to measure yet, and the rule must
     * still be safe on the frame it cannot see.
     *
     * <p><b>This number flipped sign at the T2 wave and the reason is
     * worth stating, because getting it wrong would have shipped the
     * defect the wave exists to remove.</b> Under the old HEADROOM rule
     * the conservative assumption was a LONG frame: 50 ms (one vanilla
     * tick) left no gap to the frame-rate floor, so an unmeasured slice
     * collapsed to the guaranteed {@code extractBudgetMillis} and
     * nothing more. Under a FRACTION rule the direction reverses
     * exactly - a share of a long frame is a BIG slice, and 50 ms would
     * have handed the very first frames of every world join the hard
     * cap. The conservative assumption for a fraction is therefore the
     * SHORTEST plausible frame, and 2.5 ms (400 fps) is short enough
     * that a tenth of it is under the floor: an unmeasured slice takes
     * exactly {@link #BUDGET_FLOOR_NANOS} and not a nanosecond more,
     * which is the smallest thing the rule can do while still saving.</p>
     *
     * <p>It stands in for at most a handful of frames: the EMA takes its
     * first real sample on the first pump that sees a frame time, and
     * {@link #beginSlice} seeds rather than blends that sample, so the
     * assumption is gone by the second pump.</p>
     */
    private static final long ASSUMED_FRAME_NANOS = 2_500_000L;

    /**
     * Smallest frame-time sample the rule will believe, nanoseconds.
     * Samples below it are clamped UP, not dropped.
     *
     * <p>0.2 ms is 5,000 fps. Nothing renders a world that fast; a
     * reading under it is a timer artefact (a frame that did no work
     * because the window was minimised, or a coarse clock), and letting
     * one into the EMA would pull the whole budget onto the floor for
     * the twenty frames it takes to decay out.</p>
     */
    private static final long MIN_FRAME_SAMPLE_NANOS = 200_000L;

    /**
     * Largest frame-time sample the rule will believe, nanoseconds.
     * Samples above it are clamped DOWN, not dropped.
     *
     * <p>100 ms is 10 fps, and this is the clamp that answers "it must
     * not be fooled by a paused or loading screen". A frame straddling a
     * resource reload, a debugger breakpoint, an alt-tab or a world
     * transition is not a measurement of the frame rate; under a
     * fraction rule an unclamped 800 ms reading would grant the hard cap
     * for as long as it took to decay, which is the wrong answer on
     * exactly the frames the client can least afford it. Clamped rather
     * than dropped because a genuinely slow client must still be read:
     * at 100 ms the cap binds, the far field takes 2 ms, and a 10 fps
     * machine still saves - which is the stated requirement.</p>
     *
     * <p>Note what the clamp CANNOT do, stated plainly: it cannot tell a
     * loading screen from a real 12 fps machine, and it does not try.
     * The safety net is the cap, not the clamp - the worst a
     * misidentified frame can buy is {@link #BUDGET_CAP_NANOS}, and the
     * old rule's worst was {@link #MAX_SLICE_NANOS}.</p>
     */
    private static final long MAX_FRAME_SAMPLE_NANOS = 100_000_000L;

    /**
     * Absolute ceiling on ONE slice, in nanoseconds - the stall
     * guarantee, and the only number in this class that no setting, no
     * measurement and no backlog can raise.
     *
     * <p>16 ms is one vanilla tick's worth of frame. The worst hitch this
     * class can produce is this plus one overshooting extraction
     * ({@code ShellExtractor}'s worst measured chunk, 1.7 ms), because the
     * budget is tested BEFORE a walk and never during one. That is the
     * property the pre8 freeze fix bought and this wave may not spend:
     * see the class javadoc's freeze trace for what an unbounded drop seam
     * cost instead (3 to 7 seconds at render distance 32, and about 47
     * seconds at the render distance 120 the owner actually tests at).</p>
     *
     * <p><b>T2: this is now UNREACHABLE by construction, and it stays
     * anyway.</b> The fraction rule caps the fill line at
     * {@link #BUDGET_CAP_NANOS} and the leaving line at
     * {@link #LEAVE_RESERVE_CAP_NANOS}, so the two together can never
     * exceed 4 ms - a quarter of this. It is retained as the outermost
     * belt because it is the only clamp in the class that no
     * measurement, setting or arithmetic error can get past: every other
     * number here is now derived from a reading of
     * {@code Minecraft.getFrameTimeNs()}, and a clamp that survives a
     * bad reading is worth the one {@code Math.min} it costs.</p>
     */
    private static final long MAX_SLICE_NANOS = 16_000_000L;

    /**
     * <b>THE RULE.</b> The share of the MEASURED frame the far field may
     * take on the game thread, in permille - a tenth of the frame at the
     * shipped Background Saving point.
     *
     * <p><b>Why a fraction and not milliseconds</b>
     * (docs/FARFIELD-PERF-BRIEF.md section 3; the owner's T2 report is
     * "saving the chunks to lod causes HUGE fps drops. this drops 200 to
     * 60 while its going on"). Every budget this class has ever had was
     * an absolute count of milliseconds - a 3 ms guaranteed slice, an
     * 8 ms ceiling, a 16 ms stall allowance, an idle boost aimed at a
     * 25 ms frame - and all of them were sized in 2026 against a machine
     * running 120 to 180 fps, where a frame is 8.3 ms and 3 ms of it is
     * survivable. The owner's machine now runs 200 fps. A frame is 5 ms.
     * <b>The budget did not know that</b>, so it kept taking the same
     * three-to-seven milliseconds it always had, which at 5 ms a frame is
     * 60% to 130% of the frame by construction. 200 fps became 60 exactly
     * as written.</p>
     *
     * <p>A fraction is the only form of this rule that is correct at
     * every frame rate, and it is correct without knowing anything about
     * the machine. At a 3.3 ms frame (300 fps) a tenth is 0.33 ms; at
     * 16.7 ms (60 fps) it is 1.67 ms; at 33 ms (30 fps) the cap hands
     * back 2 ms, which is 6% of the frame - a slow machine still saves,
     * it simply saves more slowly, which is the correct trade and the one
     * an absolute millisecond count cannot express.</p>
     *
     * <p><b>The feedback loop, priced.</b> The frame time this is a share
     * OF is measured by vanilla around vanilla's own work and therefore
     * already includes whatever the far field spent last frame (see
     * {@link #frameNanosEma}). The fixed point of {@code B = f(F + B)} is
     * {@code B = f * F / (1 - f)}, so at f = 1/10 the settled cost is
     * {@code F/9} - about 11.1% of the frame the client would have had
     * without us, not 10%. That is a deliberate 1.1 point of honesty
     * rather than a hidden overrun: the alternative is subtracting our
     * own spend out of the reading, which would make the rule depend on
     * the accuracy of our own accounting instead of on a number vanilla
     * measures for its own reasons.</p>
     */
    private static final int BUDGET_PERMILLE = 100;

    /**
     * The hard cap on the fill line at the shipped Background Saving
     * point, nanoseconds - the share is never worth more than this
     * however long the frame is.
     *
     * <p>Two milliseconds, and the number comes from the SLOW end of the
     * range rather than the fast one. A tenth of a 33 ms frame is 3.3 ms,
     * and a 3.3 ms slice on a machine already at 30 fps is a machine at
     * 27 fps: the share is right in principle and too expensive in
     * practice once the frame is long, because the player's problem at
     * 30 fps is the frame, not the horizon. The cap is what makes the
     * rule "a tenth of the frame, and never more than 2 ms" rather than
     * "a tenth of the frame" - and it is the reason a 30 fps laptop still
     * saves at all instead of being switched off by a floor test.</p>
     */
    private static final long BUDGET_CAP_NANOS = 2_000_000L;

    /**
     * The floor under the fill line at the shipped Background Saving
     * point, nanoseconds - the share is never worth less than this
     * however short the frame is.
     *
     * <p>A quarter of a millisecond. At 300 fps a tenth of the frame is
     * 0.33 ms and the floor does not bind; at 500 fps it is 0.2 ms and
     * the floor does. The floor exists because a walk is ~0.9 ms on this
     * terrain, so a budget that shrinks without limit does not degrade
     * gracefully - it crosses a threshold below which the credit takes
     * so many frames to accumulate one walk that the horizon stops
     * filling in any useful sense. 0.25 ms of a 2 ms frame is 12.5%,
     * which is more than the share but still small enough to be
     * invisible, and it is the smallest slice worth opening.</p>
     */
    private static final long BUDGET_FLOOR_NANOS = 250_000L;

    /**
     * P3: the part of every slice that ONLY the leaving path may spend -
     * since M5 (and leg (a)'s first real run) exactly ONE spender: the
     * TRIAGE's border strips (the design's rule: the reserve funds
     * capture, never extraction - and the seams themselves fund
     * nothing, which is what lets a whole forget storm land in one
     * frame without a single skipped side).
     *
     * <p><b>T2: this was 2 ms ABSOLUTE and it had exactly the defect the
     * fill budget had.</b> Its own sizing note said so out loud - "at the
     * owner's 120 fps" - and the owner does not run at 120 fps any more.
     * Two milliseconds on top of the fill line at a 5 ms frame is a
     * second 40% of the frame handed to the leaving path, so a forget
     * storm on a 200 fps client charged the same absolute hitch a 120 fps
     * client had been measured for. It is a fraction now, on exactly the
     * same discipline as {@link #BUDGET_PERMILLE}: a tenth of the
     * measured frame, capped at {@link #LEAVE_RESERVE_CAP_NANOS} and
     * floored at {@link #LEAVE_RESERVE_FLOOR_NANOS}.</p>
     *
     * <p><b>Why shrinking it cannot lose terrain, which is the only
     * question that matters here.</b> The reserve funds ONE thing: the
     * border strips captured during the E6 triage ({@link #finishPin}).
     * A strip the reserve cannot fund right now does not degrade and does
     * not skip - {@link #finishPin} returns false, undoes the sides it
     * had already taken, and the whole pin goes back on the triage list
     * for the next pump's fresh reserve. The pin itself holds the chunk
     * reference, so the data is not going anywhere while it waits; the
     * only thing a smaller reserve costs is LATENCY on the triage, and
     * the only bound on that latency is the pin population, which is
     * governed by the E9 heap backstop and not by this number. A reserve
     * of a tenth of a frame therefore spreads a storm's strips over more
     * frames and loses nothing, where a fixed 2 ms concentrated them into
     * fewer, longer frames on exactly the machine that could see it.</p>
     *
     * <p><b>The dial does not scale it, deliberately</b> (P3's rule,
     * unchanged). Background Saving chooses how fast the horizon FILLS;
     * it must never choose how much terrain is LOST, and the reserve is
     * the only line in this class on the loss side. So the reserve takes
     * the full share at every point of the dial, including Gentle.</p>
     *
     * <p><b>It costs nothing when nothing is leaving.</b> The budget is a
     * ceiling, not an allocation: {@link #sliceSpentNanos} only moves when
     * a strip or an extraction runs. And the stall guarantee is a
     * formality now rather than a binding clamp - the fill line caps at
     * 2 ms and this one at 2 ms, so a slice cannot reach half of
     * {@link #MAX_SLICE_NANOS} even with both lines saturated.</p>
     */
    private static final int LEAVE_RESERVE_PERMILLE = 100;

    /** Cap on the leaving line's share; see {@link #BUDGET_CAP_NANOS}. */
    private static final long LEAVE_RESERVE_CAP_NANOS = 2_000_000L;

    /**
     * Floor under the leaving line's share. Deliberately the same
     * quarter-millisecond as {@link #BUDGET_FLOOR_NANOS}: one strip is a
     * 16-wide plane read over the surface band, far cheaper than a whole
     * column walk, so a quarter of a millisecond still funds several and
     * the triage never stalls outright on a fast client.
     */
    private static final long LEAVE_RESERVE_FLOOR_NANOS = 250_000L;

    /*
     * P3's fill/leave split, kept and re-expressed. The fill line is
     * sliceBudgetNanos; the leaving line is that plus the reserve; both
     * are derived per frame from the measured frame time, and the pair
     * is clamped by MAX_SLICE_NANOS, which they can no longer approach.
     *
     * Why the split exists, and it is the fourth answer to the same
     * report (docs/FARFIELD-WAVES.md, OWNER PLAYTEST OF pre14, P3:
     * "it should always cache, especially when leaving a chunk"). One
     * budget served two populations with completely different deadlines,
     * and the one with no deadline was spending it first. The order is
     * forced by vanilla, not by us. javap, 26.2 merged jar:
     * Minecraft.runTick(boolean) calls
     * PacketProcessor.processQueuedPackets() at ip 124 (profiler
     * section "scheduledPacketProcessing"), then runAllTasks() at
     * ip 137, tick() at ip 258 and renderFrame at ip 457 - and our pump
     * is inside LevelRenderer.render, i.e. inside that last one. So a
     * forget packet is handled BEFORE the frame's slice is opened and
     * spends what is left of the PREVIOUS frame's - and the previous
     * frame's sweepLoadedWindow runs until budgetLeft says no, so
     * whenever anything at all was owed it left exactly zero.
     * onChunkDropping - the seam the class javadoc calls the last
     * chance - was therefore unfunded by construction in every session
     * with a backlog, which is every session that has not finished
     * saving its window. It retained instead, and a retained chunk was
     * walked with its neighbours already gone - the DEGRADED shell the
     * record's quality reason bits exist for, and the population M5's
     * pin graph and border strips now save whole.
     */

    // ------------------------------------------------------------------
    // T2: THE FOUR TERMS THAT USED TO SIT HERE, AND WHY EACH IS GONE.
    //
    // The old rule was  min(cap, max(guaranteed, headroom, keepUp))  and
    // its GOAL - not merely its unit - was the defect. Written out, it
    // said: "defend a 16.7 ms frame and spend everything above it." The
    // owner reports the far field pinning him at 60 fps, and 60 fps is
    // precisely the number extractFrameRateFloor tells it to defend. The
    // rule was not misbehaving. It was hitting its target.
    //
    //   * THE GUARANTEED SLICE (extractBudgetMillis, 3 ms) was a FLOOR,
    //     so it could only ever raise the budget; at 200 fps it was 60%
    //     of the frame on its own. It is a user CEILING now
    //     (resolveCeilingNanos): the same setting doing the opposite
    //     job. Zero still means "never touch the game thread", and a
    //     value under the fraction cap still binds - but nothing it can
    //     be set to makes the far field take MORE than its share.
    //
    //   * THE HEADROOM SHARE was a third of the gap between the measured
    //     frame and that 60 fps floor. It reads like a fraction and is
    //     the opposite of one: against a FIXED floor the gap GROWS as
    //     the frame shrinks, so a faster machine was handed a BIGGER
    //     absolute slice out of a SMALLER frame. That is the 200-to-60
    //     shape exactly, and it is why a share of the frame is a change
    //     of goal rather than a retune.
    //
    //   * THE IDLE BOOST is deleted outright (see farExtractIdleBoosts).
    //
    //   * THE KEEP-UP TERM (eventsLastSlice * measured walk cost, capped
    //     at a third of a frame) is deleted from the expression, and it
    //     is the one deletion with a real cost, so it is stated plainly
    //     rather than buried. It existed to stop a bulk load outrunning
    //     extraction; without it a travel leg over fresh terrain fills
    //     the store more slowly than the server sends. What keeps that
    //     from being a data-LOSS regression is the M5 pin path: a column
    //     that arrives dirty and leaves before the fill queue reaches it
    //     is CAPTURED at the drop seam and walked by the worker, off
    //     this budget entirely. So the population keep-up protected is
    //     still protected, by a mechanism that spends no frame time.
    //     What is genuinely slower is the horizon behind a travelling
    //     player, and Phase 3 of docs/FARFIELD-PERF-BRIEF.md gives it
    //     back by making a live column cost ~40 us instead of ~900.
    //
    // WHAT MUST NOT COME BACK. A frame-rate floor, in any spelling. The
    // replacement is scale-free ON PURPOSE: it has no target frame rate,
    // so it behaves identically at 30, 60, 144 and 300 fps and there is
    // no number in it that a future machine can invalidate. Any future
    // term of the form "spend the difference between the frame and X" is
    // this bug returning, whatever X is called.
    // ------------------------------------------------------------------

    /**
     * Frame-time EMA smoothing, as a right shift: the new sample gets one
     * eighth. About twenty frames to follow a step, which at 120 fps is a
     * sixth of a second - fast enough to notice a bulk load starting,
     * slow enough that one long frame cannot swing the budget.
     */
    private static final int FRAME_EMA_SHIFT = 3;

    /** Per-capture cost EMA smoothing, same shape as the frame EMA. */
    private static final int COST_EMA_SHIFT = 3;

    /**
     * Seed for {@link #captureCostNanosEma}: one game-thread CAPTURE
     * step, nanoseconds. Only ever the seed - every timed capture
     * replaces it with what this machine and this terrain actually cost.
     *
     * <p><b>T2 Phase 3 re-seated this from 800 us to 40 us, and the unit
     * it measures changed with it.</b> Until Phase 3 the game thread's
     * work unit was a whole {@code ShellExtractor} walk and the seed was
     * the census's estimate of one. The unit is now a capture step - a
     * {@link ColumnSnapshot} freeze or one border strip - and the walk
     * happens on {@link PinWorker}. The seed matters for about eight
     * captures and then the measurement owns it.</p>
     */
    private static final long SEED_CAPTURE_COST_NANOS = 40_000L;

    /** Clamp on the cost EMA, so one pathological sample cannot steer it. */
    private static final long MIN_CAPTURE_COST_NANOS = 2_000L;
    /**
     * Clamp on the cost EMA, upper end. Deliberately low: a capture step
     * that measures a whole millisecond is a bug in the capture, not a
     * budget to plan around, and letting the EMA follow it there would
     * make the fit test refuse everything for the next eight frames.
     */
    private static final long MAX_CAPTURE_COST_NANOS = 500_000L;

    /**
     * <b>Phase 3's pacing rule, part 1: pace by the WORKER, not by a
     * clock</b> (docs/FARFIELD-PERF-BRIEF.md section 3). No new capture
     * is started while {@link PinWorker} already holds this many
     * un-walked columns. Self-limiting and untunable in the way that
     * matters: if the worker is behind, the game thread simply stops
     * capturing, and the backlog is bounded by this number rather than
     * by how fast the client can copy sections.
     *
     * <p>32 is the brief's figure and it is a LATENCY choice, not a
     * throughput one - throughput is set by the worker, and queueing
     * more than a second or so of its work ahead only delays the EDIT
     * class behind a FILL storm. At the measured walk costs (0.7 ms on
     * settled ground, ~7 ms over fresh) 32 columns is between 20 ms and
     * 4 s of worker time; the low end is what keeps an edit prompt, the
     * high end is why this is not 512.</p>
     *
     * <p>It counts LEAVING pins too, because they share the one worker
     * and they hold the only copy of their terrain - a forget storm
     * SHOULD stop live capture until it drains.</p>
     */
    private static final int MAX_WORKER_BACKLOG = 32;

    /*
     * T2: IDLE_QUIET_NANOS, IDLE_TARGET_NUMERATOR and
     * IDLE_TARGET_DENOMINATOR stood here and are deleted with the idle
     * boost. The one-second quiet test, the 25 ms idle target and the
     * half-the-headroom share were the arm the owner's report names:
     * stand still with a backlog and the far field re-aimed at 40 fps
     * and spent half the way there. The quiet-detector fields went with
     * them (lastBusyNanos, busySeen); lastSliceCameraX/Z stayed, because
     * the ring-bucketed job queues always needed the camera and only
     * borrowed it for the idle test. The fraction rule has nothing to do
     * with whether the player is moving,
     * because a share of the frame is already the right answer either
     * way, and a rule that behaves differently when the player stops is
     * a rule that surprises the player exactly when they are looking.
     */

    /**
     * M5, G2: the pin LEAK TRIPWIRE - not an admission cap. The design
     * forbids a count cap on pin admission outright (the memory argument:
     * pins are a subset of chunks the client held one frame earlier and
     * releases are monotone, so a cap could only re-create the
     * anti-correlated heap ladder it replaced), so this number never
     * refuses anything; past it every admission dev-asserts and counts
     * {@link #farSavePinTripwire}. 65,536 is comfortably above the
     * rd-120 worst case (59,488 leaving columns), so the only way to
     * reach it is a settle path that stopped settling.
     */
    private static final int PIN_HARD_CAP = 65536;

    /**
     * M5, E9: the heap backstop's floor - pin admission starts releasing
     * the OLDEST pins once the reclaimable headroom
     * ({@code maxMemory - totalMemory + freeMemory}, the same reading the
     * deleted retain ladder sampled) falls under a tenth of the max
     * heap. The single sanctioned discard in the whole pipeline, counted
     * on BOTH ledgers ({@code farSaveBehind} via the GONE_BEHIND record,
     * {@link #farSaveLost} per the design's E9 row) where the owner
     * reads them. {@link #E9_CONFIRM_NANOS} makes it a confirmed
     * condition rather than a one-frame garbage spike: the reading must
     * hold across two admissions at least that far apart, because
     * {@code freeMemory} does not see collectable garbage and a storm
     * generates plenty (the "after a GC hint" in the design's E9 row -
     * the JVM's own full-GC-before-OOM guarantee is the hint; a manual
     * {@code System.gc()} on the game thread is a stall, not a
     * backstop).
     */
    private static final int E9_HEAP_FLOOR_DIVISOR = 10;
    /** See {@link #E9_HEAP_FLOOR_DIVISOR}: breach confirmation window. */
    private static final long E9_CONFIRM_NANOS = 100_000_000L;
    /** Oldest pins released per CONFIRMED E9 breach (bounded per check). */
    private static final int E9_RELEASE_BATCH = 64;

    /**
     * Columns the safety-net walk examines per pump while vanilla's own
     * compile queue is EMPTY. A probe on a settled column is one
     * chunk-cache slot read plus one record probe, so a settled window
     * costs microseconds: the loaded square at render distance 32 is
     * {@code (2*35+1)^2 = 5,041} columns, five pumps at this budget, and
     * the cursor then cycles. The extractions behind whatever it FILES
     * are paced by the scheduler's time budget, not by this number.
     */
    private static final int SWEEP_PROBES_PER_PUMP = 1024;

    /**
     * Columns the sweep examines per pump while vanilla still has chunk
     * work of its own.
     *
     * <p><b>Why this is not zero, which is what pre8 shipped.</b> The
     * sweep's gate was {@code LevelRenderer.hasRenderedAllSections()} and
     * nothing else, so the sweep ran only while vanilla's compile queue
     * was empty — and a player who is MOVING keeps that queue non-empty
     * continuously, because every chunk boundary crossing hands the
     * occlusion BFS a fresh ring to schedule. So the one mechanism that
     * guarantees the extract-once tracker is COMPLETE was switched off for
     * the whole of every travel leg, which is exactly the leg the owner
     * walks when the horizon behind them fails to fill
     * (docs/FARFIELD-WAVES.md, OWNER PLAYTEST OF pre8, item J1). During
     * travel the only remaining writers are the arrival plus, the
     * deferral drain, the drop seam and the on-demand rescue, and none of
     * them is a coverage guarantee: the arrival plus only ever touches
     * the five positions around a chunk that just landed.</p>
     *
     * <p>Deferring to vanilla is still right, so this is a THROTTLE
     * rather than a switch: an eighth of the idle rate, which is a full
     * pass over the render-distance-32 window every forty pumps — under a
     * second at 60 fps — for about a hundred microseconds of hash probes
     * per pump. Extraction itself is unaffected either way: every walk
     * this finds is paid for out of {@code extractBudgetMillis}, the same
     * ceiling that already bounds the arrival and drop seams, so a busier
     * sweep cannot take a millisecond more of the game thread than pre8
     * allowed. It only changes WHICH columns that budget is spent on.</p>
     */
    private static final int SWEEP_PROBES_PER_PUMP_BUSY = 128;

    /**
     * Cap on the record map, in columns. A very long flight visits more
     * columns than this; past the cap {@link #ensureRecord} evicts the
     * FARTHEST {@code GONE_SAVED} records only (re-extract on revisit is
     * safe - the store accepts an equal-tier overwrite), and dirty or
     * PINNED records are NEVER evicted: bounded memory may cost a
     * redundant re-extraction, never a silent loss (invariant I1). If
     * every record is dirty - pathological - the map grows past the cap
     * rather than lie.
     */
    private static final int MAX_TRACKED_COLUMNS = 1 << 18;

    /**
     * M3 (E4): how long a burst of block changes coalesces into ONE
     * EDIT job, in milliseconds. This window replaced the 30-second
     * staleness epoch as the only churn bound the save pipeline has -
     * and it is enough, because the E4 hook itself is O(1) (a map get
     * and field writes; a farm's churn lands on
     * {@link #farSaveEditsCoalesced}, not on the extraction budget).
     * A quarter second: edit-to-store latency is coalesce + schedule +
     * write, never an epoch.
     */
    private static final long EDIT_COALESCE_MILLIS = 250L;

    /**
     * M4: Chebyshev-ring buckets per job queue. Ring 255 collects
     * everything farther (a camera ring past 255 chunks is beyond any
     * storage window this client can hold). Buckets are lazily created
     * {@code LongArrayList}s; an empty queue is 2 KB of null slots.
     */
    private static final int RING_BUCKETS = 256;

    private static volatile boolean armed = resolveEnabled();

    // Last known view geometry, game-thread confined. Radius -1 / center
    // unknown until the first event after arming; the first event only
    // establishes the baseline (documented honesty: we never guess the
    // pre-arm state).
    private static int lastViewRadius = -1;
    private static int lastViewCenterX;
    private static int lastViewCenterZ;
    private static boolean viewCenterKnown;

    // ------------------------------------------------------------------
    // M2: the per-column record - the ONLY bookkeeping structure
    // (docs/FARFIELD-SAVE-DESIGN.md sections 1-3). The extract-once
    // tracker, the incomplete marks, the stale marks, the refresh epoch
    // and the deferral list all collapsed into this map; every decision
    // derives from the record at decision time (invariant I2).
    // ------------------------------------------------------------------

    /** Record state: loaded, and the store has ACKED its live version. */
    static final byte STATE_LIVE_CLEAN = 0;
    /** Record state: loaded, {@code liveVersion > storedVersion}. */
    static final byte STATE_LIVE_DIRTY = 1;
    /**
     * Record state: the chunk left the client's cache and WE hold its
     * capture - {@code rec.pin != null}, a {@link Pin} on the worker's
     * queue or waiting on its write's ack. Dirty by definition: a clean
     * column is never pinned.
     */
    static final byte STATE_PINNED = 2;
    /** Record state: chunk gone, stored version was (or is being) acked. */
    static final byte STATE_GONE_SAVED = 3;
    /**
     * Record state: chunk gone with truth unsaved - the only honest loss
     * state. Counted on the {@link #farSaveBehind()} gauge; E1 repairs
     * it on revisit and decrements. A column with no record is ABSENT.
     */
    static final byte STATE_GONE_BEHIND = 4;

    /** No job wanted (clean, or gone). */
    static final byte JOB_NONE = 0;
    /** An edit wants re-extraction, coalesced and prompt. */
    static final byte JOB_EDIT = 1;
    /** An arrival / upgrade / rescue wants extraction, nearest-first. */
    static final byte JOB_FILL = 2;

    /** Quality bit: all four laterals were loaded and light was read. */
    static final byte QUALITY_WHOLE = 1;
    /** Quality reason bit: west (x-1) neighbour unknown at extraction. */
    static final byte QUALITY_SIDE_WEST = 2;
    /** Quality reason bit: east (x+1) neighbour unknown at extraction. */
    static final byte QUALITY_SIDE_EAST = 4;
    /** Quality reason bit: north (z-1) neighbour unknown at extraction. */
    static final byte QUALITY_SIDE_NORTH = 8;
    /** Quality reason bit: south (z+1) neighbour unknown at extraction. */
    static final byte QUALITY_SIDE_SOUTH = 16;
    /** Quality reason bit: the light plane was refused (stored flat). */
    static final byte QUALITY_LIGHT_PARTIAL = 32;
    /**
     * Quality bit: the column extracted to NOTHING (all air, or entirely
     * below the band) - the store legitimately holds no shell, and
     * {@code storedVersion} was stamped inline with no write. This is
     * what lets {@link #tryExtractLoaded} answer "the store is right to
     * miss" honestly instead of re-walking an all-air column forever.
     */
    static final byte QUALITY_EMPTY = 64;

    /**
     * One column's whole truth. Game-thread confined, like the map that
     * holds it. {@code dirty = liveVersion > storedVersion} is DERIVED,
     * never cached (I2); at most one write is in flight per column
     * ({@code writingVersion != 0}, I3); {@code jobClass} names the queue
     * the record wants, and the {@code filed*} bits dedupe the physical
     * queue entries (a stale entry dies on pop when the class moved on).
     */
    static final class ColumnRecord {
        /** Stamp of the freshest client truth seen (E1/E4/rescue). */
        long liveVersion;
        /** Stamp the store has ACKED; 0 = nothing this session. */
        long storedVersion;
        /** Stamp handed to the IO queue; 0 = no write in flight. */
        long writingVersion;
        /** Quality of the in-flight write, applied at its ack. */
        byte writingQuality;
        /** Quality of the STORED shell (bits above). */
        byte quality;
        /** One of the STATE_* bytes. */
        byte state;
        /** One of the JOB_* bytes - what the record wants run. */
        byte jobClass;
        /** An entry for this key is physically in the EDIT queue. */
        boolean filedEdit;
        /** An entry for this key is physically in the FILL queue. */
        boolean filedFill;
        /**
         * {@link #frameMillis} when this column's EDIT job was FILED -
         * never refreshed by a coalesced edit, so the coalesce deadline
         * is fixed from the FIRST unflushed edit and a column edited
         * more often than the window still ripens (the sliding-stamp
         * shape starves a redstone clock's column forever).
         */
        long editStampMs;
        /**
         * M5: own light published this session (E3, which shares the
         * enableChunkLight seam with E1 - so every arrival-born record
         * is born ready). {@code ensureRecord} defaults it TRUE because
         * every other producer (E4 arming, census discovery, rescue)
         * creates records for chunks demonstrably live in the cache,
         * whose publish either already happened or is caught by the
         * extractor's lightReadable floor - a false default would
         * starve them behind G1 forever, since their E3 already fired
         * before the record existed. The ONE honest false producer is
         * the fast-flight pin capture (an untracked drop whose light
         * was never published); its record goes LIVE again only through
         * E1/E4, and G1 holds its jobs until E3 re-opens them.
         */
        boolean lightReady;
        /**
         * E10: the {@code lightRun} in which this column's light-layer
         * SET was (de)initialised by a chunk arrival in its Chebyshev-2
         * neighbourhood - not a light change, a light ALLOCATION. The
         * mirror in {@code onLightChanged} ignores callbacks for a
         * record carrying the current run here; see the suppression
         * argument there. 0 is "never", and {@code lightRun} starts at
         * 1, so a fresh record is never born suppressed.
         */
        long lightNoiseRun;
        /**
         * The capture that owns this column right now, or null.
         *
         * <p><b>M5:</b> non-null iff {@code state == PINNED} - the
         * capture that replaced the retained-chunk list. <b>Phase 3
         * widened it</b>: it is now non-null iff a capture of this
         * column is in flight, which is {@code PINNED} for a dropped
         * column and {@code LIVE_DIRTY} for a live one. The wider
         * invariant is what lets one field answer "does something
         * already own this column" for the FILL drain, the safety-net
         * sweep and the deferral list alike, instead of three of them
         * inferring it from a state byte that no longer distinguishes
         * the case.</p>
         *
         * <p>Attached at {@link #beginLiveCapture} (live) or
         * {@link #finishPin} (leaving) and released ONLY through
         * {@link #releasePin}, so the two gauges, the pin index and the
         * release counter cannot diverge.</p>
         */
        Pin pin;
    }

    /**
     * THE map: every column that produced an event this session, by
     * packed key. Lazily created on first armed use (the zero-cost-off
     * leg's standard). At all times every column in it is acked at its
     * live version, carrying a queued/in-flight job, or counted on
     * exactly one of behind/lost (invariant I1).
     */
    private static Long2ObjectOpenHashMap<ColumnRecord> columns;

    /**
     * The one monotonic stamp source - liveVersion, storedVersion and
     * writingVersion all draw from it (never nanoTime; no ties). A write
     * is submitted AT the record's liveVersion, which is what makes
     * "stored == live" at ack mean exactly "the shell on disk is the
     * truth the client held when the job ran".
     */
    private static long nextStamp;

    /** GONE_BEHIND population (E1 repair decrements). Game thread. */
    private static long farSaveBehindGauge;

    /** At most one {@link #evictFarthestSaved} attempt per slice; see there. */
    private static boolean evictionTriedThisSlice;

    /**
     * M4: one job queue - a Chebyshev-ring-bucketed FIFO for a single
     * job class. Pop lowest non-empty ring; FIFO within a ring; buckets
     * are NOT rebuilt on camera moves - on pop, an entry more than two
     * rings out of place is re-filed instead of run. O(1) amortized, no
     * per-frame sort (the 120-180 fps bar). Heads mirror the old
     * deferral list's cursor trick: amortized O(1) removal from the
     * front.
     *
     * <p><b>No cap, and that is an argument rather than an oversight:</b>
     * the filed flag makes entries structurally at most one per record
     * per class, and the record map is itself bounded, so the queues
     * cannot outgrow ~2 entries per tracked column - the old
     * MAX_DEFERRED_COLUMNS cap guarded a FIFO that had no dedupe and
     * held mostly its own repeats. Nothing is refused, so there is
     * nothing to count.</p>
     *
     * <p>The two instances below are the whole population; the arrays
     * inside are created on first armed use (the class-load footprint
     * is two empty holders, smaller than one LongAdder).</p>
     */
    private static final class JobQueue {
        /** The JOB_* class whose entries this queue holds. */
        final byte jobClass;
        /** True for the EDIT queue (selects the record's filed flag). */
        final boolean edit;
        /** Lazily created ring buckets; null while never used. */
        LongArrayList[] rings;
        /** Per-ring head cursors, created beside {@link #rings}. */
        int[] heads;
        /** Live entries, including stale ones that die on pop. */
        int queued;
        /** Lowest possibly-non-empty ring - the pop scan's hint. */
        int minRing;
        /** Ring bucket of the entry {@link #pop} just returned. */
        int poppedRing;

        JobQueue(byte jobClass, boolean edit) {
            this.jobClass = jobClass;
            this.edit = edit;
        }

        /** Is this record's key physically filed in THIS queue? */
        boolean filed(ColumnRecord rec) {
            return edit ? rec.filedEdit : rec.filedFill;
        }

        void setFiled(ColumnRecord rec, boolean value) {
            if (edit) {
                rec.filedEdit = value;
            } else {
                rec.filedFill = value;
            }
        }

        /**
         * Append at {@code ring}; one entry per record per queue. The
         * ONE filing site - the drain's re-files go through here too,
         * so the count, the flag and the hint can never diverge.
         */
        void file(ColumnRecord rec, long key, int ring) {
            if (filed(rec)) {
                return; // structural dedupe
            }
            if (rings == null) {
                rings = new LongArrayList[RING_BUCKETS];
                heads = new int[RING_BUCKETS];
            }
            LongArrayList bucket = rings[ring];
            if (bucket == null) {
                bucket = new LongArrayList(16);
                rings[ring] = bucket;
            }
            bucket.add(key);
            setFiled(rec, true);
            queued++;
            if (ring < minRing) {
                minRing = ring;
            }
        }

        /**
         * Pop the oldest entry of the lowest non-empty ring, updating
         * {@link #minRing} and {@link #poppedRing}. {@code
         * Long.MIN_VALUE} means empty (it cannot be a column key of any
         * reachable chunk - it would need chunk x = -2^31 exactly).
         * Does NOT touch the record flags or {@link #queued}; the drain
         * owns that bookkeeping because it holds the record.
         */
        long pop() {
            LongArrayList[] r = rings;
            if (r != null) {
                for (int i = minRing; i < RING_BUCKETS; i++) {
                    LongArrayList bucket = r[i];
                    if (bucket == null) {
                        continue;
                    }
                    int head = heads[i];
                    if (head >= bucket.size()) {
                        bucket.clear();
                        heads[i] = 0;
                        continue;
                    }
                    long key = bucket.getLong(head++);
                    if (head >= bucket.size()) {
                        bucket.clear();
                        heads[i] = 0;
                    } else if (head * 2 >= bucket.size()) {
                        bucket.removeElements(0, head);
                        heads[i] = 0;
                    } else {
                        heads[i] = head;
                    }
                    poppedRing = i;
                    minRing = i;
                    return key;
                }
            }
            minRing = RING_BUCKETS - 1;
            return Long.MIN_VALUE;
        }

        /** Per-world reset: drop the arrays outright. */
        void reset() {
            rings = null;
            heads = null;
            queued = 0;
            minRing = 0;
        }
    }

    private static final JobQueue editQueue = new JobQueue(JOB_EDIT, true);
    private static final JobQueue fillQueue = new JobQueue(JOB_FILL, false);

    /**
     * The EDIT coalesce stage: filed edits wait HERE, invisible to
     * {@link #backlogged} and untouched by the drain, until their
     * file-time deadline passes; {@link #ripenEdits} then moves them
     * into {@link #editQueue}. Deadlines are file-time + the window and
     * therefore MONOTONE, so a plain FIFO with a head cursor is a
     * correct timer wheel - and a ticking farm's one perpetual edit can
     * no longer hold the budget rule's backlog term true forever or
     * make the drain pop-and-refile an unripe job every pump.
     */
    private static LongArrayList editPendingKeys;
    private static LongArrayList editPendingDue;
    private static int editPendingHead;

    /**
     * The capture queue - EVERY pin pends here between its seam and its
     * triage, E5 drops and E6 walks alike (one list, one finisher, one
     * ordering; the drop seam stopped finishing in place when leg (a)'s
     * first real run showed a storm's worth of seam-time strips against
     * one frame's reserve). PINS since M5, not bare chunk refs.
     * The capture walk MUST be synchronous and MUST take the light with
     * it: the old Storage is only installed at {@code updateViewRadius}
     * HEAD (a deferred cursor would read a storage that no longer holds
     * the annulus), and the light dies within about a frame of the
     * forget flood landing ({@code ClientLevel.pollLightUpdates} drains
     * its WHOLE queue once it holds 1000+ runnables - bytecode ip
     * 10-30 - so a 59k-removal storm is gone by the next pump, which is
     * why pin creation cannot wait for the triage). The TRIAGE - one
     * record transition plus side/strip resolution per pin - runs on
     * the pump, a bounded count per frame ({@link #drainLeavingCaptures}).
     * Holding the pins briefly is the design's memory argument in
     * miniature: these are chunks (and light arrays) the client held
     * one frame earlier, released monotonically as the worker drains.
     * The walk settles CLEAN records inline (one map probe, no pin, no
     * allocation - the E5 clean row is O(1) wherever it runs), so this
     * list only ever holds the dirty-or-untracked minority. Inner rings
     * first, so triage order is nearest-first.
     */
    private static java.util.ArrayList<Pin> leavingPins;
    /** Head cursor into {@link #leavingPins}; compacted like the queues. */
    private static int leavingHead;
    /** Triage bound per pump; strips inside it pace on the leave reserve. */
    private static final int LEAVING_TRIAGE_PER_PUMP = 4096;

    /**
     * <b>Phase 3:</b> LIVE captures frozen but not yet FINISHED - the
     * same two-phase shape the leaving path uses, for the same reason
     * and with one difference.
     *
     * <p>Phase 1 ({@link #beginLiveCapture}) is one atom: freeze the
     * column's blocks and heightmaps into a {@link ColumnSnapshot} and
     * grab its light refs. Phase 2 ({@link #finishPin}) resolves the side
     * graph and captures up to four border strips off stayed-live
     * neighbours, and each strip is its own atom - the grant is tested
     * between them and a capture that runs out of grant DEFERS here,
     * resuming next pump at the side it stopped on
     * ({@link Pin#finishSide}).</p>
     *
     * <p><b>That split is the wave's whole answer to stutter.</b> The
     * far-armed bench showed Phase 2 leaving 210 frames overrunning
     * their grant with an overrun p95 of 13.6 ms, because the atom was a
     * whole 3-7 ms walk. Freezing costs tens of microseconds and a strip
     * costs tens of microseconds, so the largest uninterruptible piece
     * of far-field work left on the game thread is one of those - and
     * nothing this class does can put a millisecond-scale step back
     * without a new entry on this list.</p>
     *
     * <p>Unlike {@link #leavingPins} these pins hold no unique terrain:
     * the client still has the column. A capture stranded here by a
     * world change is dropped, not counted as lost.</p>
     */
    private static java.util.ArrayList<Pin> liveCaptures;
    /** Head cursor into {@link #liveCaptures}; compacted like the queues. */
    private static int liveCaptureHead;
    /**
     * Hard valve on the whole E6 capture walk, nanoseconds - a
     * BACKSTOP, never the plan, and no longer 4 ms, deliberately: the
     * walk now carries the light-layer ref grab (~2 lookups per section
     * per layer, ~4 us a column measured shape) because deferring the
     * light loses it to the removal flood (see {@link #leavingPins}).
     * The worst reachable storm - rd 120 shrunk in one packet, 59,488
     * leaving columns, every one dirty - is ~250 ms of capture, paid
     * ONCE, inside the multi-second storage-copy-plus-renderer-teardown
     * hitch vanilla itself charges for that same slider move; the
     * common case (a settled window, clean majority settled inline at
     * one probe each) is milliseconds. Past the valve the remainder is
     * counted on {@link #farSaveCaptureSkipped} - the "no silent caps"
     * rule; gametest (a) asserts it zero - and behaves as pre-Phase-4
     * (discarded by vanilla, repaired on revisit; the census membership
     * arm settles the stale records). The frame stall guarantee
     * ({@link #MAX_SLICE_NANOS}) governs EXTRACTION and is untouched;
     * this is the once-per-rd-change capture exemption the Phase 4
     * landing already documented, priced honestly for what it now
     * carries.
     */
    private static final long E6_CAPTURE_HARD_DEADLINE_NANOS = 400_000_000L;
    /**
     * The LIGHT half's own, earlier valve: past it pins are still taken
     * (geometry is never lost to a time budget) but without their layer
     * refs - flat plane, LIGHT_PARTIAL, counted on
     * {@link #farSaveLightUncaptured}, upgradeable on revisit.
     */
    private static final long E6_LIGHT_DEADLINE_NANOS = 250_000_000L;

    /**
     * The coalesce clock, milliseconds, refreshed once per slice from
     * the nanoTime {@link #openSlice} already took - so the E4 hook
     * pays ZERO clock reads per block change (an edit storm was paying
     * a nanoTime + divide per block for work the coalesce throws away).
     * Quantized to slice boundaries, so the coalesce window is its
     * nominal length plus at most one frame; when no pump runs, no
     * drain runs either, so a stale value cannot make an edit run early
     * relative to anything.
     */
    private static long frameMillis;

    /**
     * E10: the light-run counter. Vanilla drains its whole affected-set
     * once per {@code ClientLevel.update()}, which
     * {@code Minecraft.runTick} calls once per FRAME at ip 372-376,
     * immediately before the extract phase that ends in our pump - so
     * "one pump to the next" and "one light run" are the same window,
     * and one long is the whole epoch machinery this needs. Bumped at
     * the head of {@link #pumpGameThread} (never anywhere the mirror or
     * E1 can see a half-advanced value) and never reset: it is a
     * sequence, not a population. Starts at 1 so a fresh record's
     * zeroed {@code lightNoiseRun} is never mistaken for "stamped".
     */
    private static long lightRun = 1L;

    /**
     * E10: how far a chunk arrival's light-layer ALLOCATION halo
     * reaches, in columns. {@code enableChunkLight} calls
     * {@code LevelLightEngine.updateSectionStatus} for every section of
     * the arriving column (javap ip 24-76); that walks the 26 section
     * neighbours to bump their neighbour counts (ip 54-161), and any of
     * those seen for the first time runs {@code initializeSection},
     * which calls {@code markSectionAndNeighborsAsAffected} - one more
     * +/-1 section expansion. Two expansions of one section each: the
     * arriving column's halo can reach two columns out, so the
     * suppression stamp does too. Not a tunable; it is arithmetic off
     * the bytecode.
     */
    private static final int LIGHT_NOISE_RADIUS = 2;

    /** Extraction nanoseconds spent inside the current slice. */
    private static long sliceSpentNanos;
    /**
     * False until the first {@link #beginSlice}; see the note there.
     * (The slice-open stamp this note used to hedge about died with
     * {@code ensureSlice} at M5 - the pump is the only slice opener
     * now, so nothing needs "how long since the slice opened".)
     */
    private static boolean sliceOpen;
    /**
     * The ceiling for the current slice, in nanoseconds - the whole
     * output of {@link #sliceBudgetFor}. Cached in a field so a
     * chunk-arrival burst does no config lookup and no arithmetic per
     * chunk, and recomputed once per frame so both the slider and the
     * property still apply live.
     */
    private static long sliceBudgetNanos;

    /**
     * P3: the ceiling for the LEAVING path, nanoseconds -
     * {@link #sliceBudgetNanos} plus the leaving reserve
     * ({@link #LEAVE_RESERVE_PERMILLE} of the measured frame), capped at
     * {@link #MAX_SLICE_NANOS}. Same clock ({@link #sliceSpentNanos}), a
     * higher line: the fill work stops at the lower one and cannot take
     * the reserve, so the triage's strips are always solvent no matter
     * how hard the sweep has been spending.
     */
    private static long sliceLeaveBudgetNanos;

    /**
     * T2: the measured frame time this slice's budget was derived from,
     * nanoseconds - the smoothed reading, cached so the counters and the
     * gauges report the same number the rule used rather than
     * re-sampling a field that has moved on.
     */
    private static long sliceFrameNanos;

    /*
     * T2 Phase 2's CREDIT BUCKET (walkCreditNanos, creditCapNanos,
     * projectedWalkNanos, the debt clamp in chargeSlice) stood here and
     * is DELETED by Phase 3, exactly as the brief predicted it would be.
     *
     * It existed for one reason: a fit test against a single frame's
     * remainder deadlocks when the work unit is bigger than the grant.
     * At a 5 ms frame the grant is 0.5 ms and a walk measured 2.9-6.8 ms
     * at the median, so nothing would ever fit and the horizon would
     * stop filling; the bucket let several frames club together to fund
     * one indivisible walk, and repaid the overshoot afterwards.
     *
     * Phase 3 removes the premise instead of the symptom. The game
     * thread's unit is now a CAPTURE STEP - tens of microseconds, and a
     * dozen of them fit inside one 0.5 ms grant - so a plain
     * "does the next step fit in what is left of this frame's grant"
     * test terminates on every machine, and the frame can no longer
     * overshoot by more than one step. Nothing carries between frames,
     * which also means nothing can be hoarded, mortgaged or forgiven,
     * and farBudgetOverrunNanos stops having a legitimate producer.
     */

    /**
     * T2: the worst single slice's total far-field cost, nanoseconds -
     * a running maximum over {@link #sliceSpentNanos} at slice close.
     * The tail figure the owner actually feels, where
     * {@link #farExtractNanos} is the mean one.
     */
    private static long worstSliceNanos;

    /**
     * T2: the worst single slice's OVERRUN, nanoseconds - the largest
     * amount by which one slice exceeded its own grant.
     *
     * <p>Distinct from {@link #worstSliceNanos} on purpose: a slice that
     * spends 2 ms of a 2 ms grant is the rule working, and a slice that
     * spends 1.4 ms of a 0.5 ms grant is the un-interruptible walk being
     * paid for. Only the second is a defect of this design, and it is
     * bounded by one walk - which is precisely the bound Phase 3 removes
     * by shrinking the work unit from ~900 us to ~40.</p>
     */
    private static long worstOverrunNanos;

    /**
     * M1: the last few columns whose write the store acknowledged as
     * LOST, newest overwriting oldest - a diagnostic ring for "WHICH
     * columns", where the counters only say how many. Game thread only.
     */
    private static final long[] recentWriteLossRing = new long[16];
    /** Total losses ever filed into {@link #recentWriteLossRing}. */
    private static long recentWriteLossFiled;

    /**
     * E8, the save design's acks-before-everything ordering: runs first
     * thing in {@link #pumpGameThread}, before the slice opens, so every
     * decision this pump makes derives from records the store has
     * already corrected.
     *
     * <p>A record is touched only by ITS OWN write's ack
     * ({@code ack.version == writingVersion} - I3 serializes to one in
     * flight, so versions cannot cross). Foreign acks (a test submitting
     * shells directly, or an ack that outlived its world and hits a
     * fresh map) still move the counters, honestly: the loss or the
     * write was real, whichever producer it belonged to.</p>
     *
     * <p>Ok: {@code storedVersion} advances to the acked version and the
     * stored quality becomes the shell's; a LIVE record whose live
     * version is now stored goes CLEAN, one that gathered new dirt in
     * flight RE-QUEUES (version ordering makes the race harmless). Fail:
     * a LIVE record re-queues - <b>refusal re-queues, never
     * discards</b> - and a GONE record, whose data no longer exists to
     * re-extract, goes {@code GONE_BEHIND}, counted where the owner
     * reads it.</p>
     */
    /**
     * THE one GONE_SAVED writer: state and job class move together, so
     * a settled record can never carry a live job class into a later
     * pop. Invariant I1 is enforced in one place, not by hand at every
     * site - the review that mandated this found two hand-spelled
     * copies already diverging (they skipped the job-class clear;
     * unreachable as a bug in that diff, because every GONE_SAVED
     * producer had already cleared it, but latent the moment anyone
     * added a producer that had not).
     */
    private static void settleGoneSaved(ColumnRecord rec) {
        releaseLiveCapture(rec); // Phase 3; see there for why it is here
        rec.state = STATE_GONE_SAVED;
        rec.jobClass = JOB_NONE;
    }

    /**
     * THE one GONE_BEHIND writer: state, job class and the gauge move
     * together - the only place the gauge increments.
     */
    private static void leaveBehind(ColumnRecord rec) {
        releaseLiveCapture(rec); // Phase 3; see there for why it is here
        rec.state = STATE_GONE_BEHIND;
        rec.jobClass = JOB_NONE;
        farSaveBehindGauge++;
    }

    /**
     * THE one repair site: the player (or the cache) demonstrably has
     * the column again - the only place the gauge decrements. With both
     * moves guarded by the state byte a negative gauge is structurally
     * impossible; the tripwire counts (and floors) it anyway, because a
     * clamped readout is how an accounting bug hides.
     */
    private static void repairBehind(ColumnRecord rec) {
        if (--farSaveBehindGauge < 0) {
            farSaveBehindGauge = 0;
            farSaveGaugeUnderflow.increment();
        }
    }

    private static void drainWriteAcks() {
        for (FarField.WriteAck ack; (ack = FarField.pollWriteAck()) != null;) {
            long key = columnKey(ack.chunkX(), ack.chunkZ());
            ColumnRecord rec = columns == null ? null : columns.get(key);
            boolean ours = rec != null && rec.writingVersion != 0
                    && rec.writingVersion == ack.version();
            if (!ack.ok()) {
                farExtractWriteLost.increment();
                recentWriteLossRing[(int) (recentWriteLossFiled++
                        % recentWriteLossRing.length)] = key;
                if (!ours) {
                    continue;
                }
                rec.writingVersion = 0;
                if (rec.state == STATE_LIVE_DIRTY) {
                    // Phase 3: the capture that produced this write is
                    // spent. Release it BEFORE re-filing, or drainJobs'
                    // "a capture already owns this column" guard would
                    // swallow the retry and the column would never be
                    // saved again.
                    if (rec.pin != null && rec.pin.live) {
                        releasePin(rec);
                    }
                    refileJob(rec, key); // retry while the chunk lives
                } else if (rec.state == STATE_GONE_SAVED) {
                    leaveBehind(rec);
                } else if (rec.state == STATE_PINNED && rec.pin != null
                        && !rec.pin.released) {
                    // M5: the pin still holds the only copy - re-enqueue
                    // it to the worker at its own version. Refusal
                    // re-queues, never discards; the retry paces itself
                    // on the ack round trip plus the worker's G3 naps,
                    // and the two permanent-death exits (store dead,
                    // world exited) are counted in the worker's drain.
                    rec.writingVersion = rec.pin.version;
                    PinWorker.enqueue(rec.pin);
                }
                continue;
            }
            if (!ours) {
                continue;
            }
            boolean hadStored = rec.storedVersion > 0;
            boolean wasDegraded = hadStored
                    && (rec.quality & (QUALITY_WHOLE | QUALITY_EMPTY)) == 0;
            rec.storedVersion = Math.max(rec.storedVersion, ack.version());
            rec.quality = rec.writingQuality;
            rec.writingVersion = 0;
            if (hadStored) {
                farExtractStaleRefreshes.increment();
            }
            if (wasDegraded && (rec.quality & QUALITY_WHOLE) != 0) {
                farExtractUpgrades.increment();
            }
            if (rec.state == STATE_LIVE_DIRTY) {
                // Phase 3: the LIVE capture's write landed. Release the
                // snapshot (it is on disk now), forward the residency
                // notification the worker's submit deliberately could not
                // make - the same forwarding the PINNED arm below does,
                // and the successor to the onShellWritten call that used
                // to sit inside the game thread's own submitShell - and
                // only then decide clean or re-file.
                if (rec.pin != null && rec.pin.live) {
                    releasePin(rec);
                    FarField.onPinShellAcked(ack.chunkX(), ack.chunkZ());
                }
                if (rec.liveVersion <= rec.storedVersion) {
                    rec.state = STATE_LIVE_CLEAN;
                    rec.jobClass = JOB_NONE;
                } else {
                    refileJob(rec, key); // new dirt landed mid-flight
                }
            } else if (rec.state == STATE_GONE_SAVED
                    && rec.liveVersion > rec.storedVersion) {
                // Dirt arrived between the drop-seam walk and the drop
                // itself - the chunk is gone, so the newer truth is not
                // extractable. Honest, counted, repaired on revisit.
                leaveBehind(rec);
            } else if (rec.state == STATE_PINNED) {
                if (rec.liveVersion <= rec.storedVersion) {
                    // The pin's own write (or a live write that was in
                    // flight when the drop pinned the column) acked: the
                    // disk holds the truth. Settle, release the capture,
                    // and forward the residency notification the worker's
                    // submit deliberately could not make (the residency
                    // lock is game-thread business).
                    settleGoneSaved(rec);
                    releasePin(rec);
                    FarField.onPinShellAcked(ack.chunkX(), ack.chunkZ());
                } else if (rec.pin != null && !rec.pin.released) {
                    // A live write older than the capture acked while the
                    // pin waited (I3's in-flight arm): the pin's own walk
                    // is still owed - hand it to the worker now.
                    rec.writingVersion = rec.pin.version;
                    PinWorker.enqueue(rec.pin);
                }
            }
        }
    }

    /** Re-file a dirty LIVE record on the queue its {@code jobClass} names. */
    private static void refileJob(ColumnRecord rec, long key) {
        if (rec.jobClass == JOB_EDIT) {
            fileEdit(rec, key);
        } else {
            rec.jobClass = JOB_FILL;
            fileFill(rec, key);
        }
    }

    /**
     * M1 probe: a copy of the loss ring's live entries, packed column
     * keys, unordered. Game thread only (tests reach it through
     * {@code runOnClient}); empty until a write is first lost.
     */
    public static long[] recentWriteLossKeys() {
        int live = (int) Math.min(recentWriteLossFiled, recentWriteLossRing.length);
        long[] copy = new long[live];
        System.arraycopy(recentWriteLossRing, 0, copy, 0, live);
        return copy;
    }

    /**
     * Smoothed frame time, nanoseconds, from
     * {@code Minecraft.getFrameTimeNs()} - <b>the one input the whole
     * budget rule is a function of.</b>
     *
     * <p><b>The source, re-verified against the 26.2 merged jar at the
     * T2 wave</b> (javap, this authoring session):
     * {@code net.minecraft.client.Minecraft: public long
     * getFrameTimeNs();} whose entire body is {@code aload_0 / getfield
     * frameTimeNs:J / lreturn}, and the field is assigned exactly once
     * per frame inside {@code renderFrame} at ip 626-631
     * ({@code Util.getNanos()} then {@code putfield frameTimeNs}). The
     * ordering matters and was re-read rather than assumed: that
     * assignment precedes the {@code "swapBuffers"} profiler section at
     * ip 647 and {@code FramerateLimiter.limitDisplayFPS} at ip 767, so
     * the reading is the frame's CPU SPAN and excludes both the present
     * and the framerate-limiter sleep.</p>
     *
     * <p><b>Why that source and not a new hook, and not the wall clock
     * between pumps.</b> Three reasons, and the third is the one that
     * makes the fraction rule work at all:</p>
     * <ol>
     *   <li>it is MEASURED, not a target - it is not the vsync interval,
     *       not the framerate cap and not a tick length, so a client
     *       pinned at 60 by vsync while capable of 300 reports the 3 ms
     *       it really spent and the far field takes a tenth of THAT,
     *       which is the conservative reading of a capped client;</li>
     *   <li>it is vanilla's own number, taken for vanilla's own reasons,
     *       so nothing this mod does can bias it and no new mixin, timer
     *       or allocation is needed to obtain it;</li>
     *   <li>it already INCLUDES whatever the far field spent last frame,
     *       which makes the rule a converging feedback loop rather than
     *       an open-loop guess. The fixed point of {@code B = f(F + B)}
     *       is {@code B = f F / (1 - f)}: at a tenth, the far field
     *       settles at {@code F/9} of the clean frame and stays there.
     *       See {@link #BUDGET_PERMILLE}.</li>
     * </ol>
     *
     * <p><b>Smoothing, and why an EMA rather than a median.</b> One
     * eighth of each new sample ({@link #FRAME_EMA_SHIFT}), so about
     * twenty frames to follow a step - a sixth of a second at 120 fps.
     * A median over a ring would reject an outlier outright rather than
     * dilute it, and it was considered and refused: it costs a buffer
     * and a sort on the game thread to defend against a hazard the CAP
     * already bounds absolutely, since the worst a single absurd sample
     * can buy under a fraction rule is {@link #BUDGET_CAP_NANOS}. The
     * EMA is a rolling average of recent frames, which is what the rule
     * needs, and it is the signal this class already computed.</p>
     *
     * <p>Zero until the first measured frame; {@link #ASSUMED_FRAME_NANOS}
     * stands in until then, and note that its VALUE flipped at this wave
     * for the reason documented there.</p>
     */
    private static long frameNanosEma;

    /**
     * Measured cost of ONE game-thread CAPTURE STEP on this machine,
     * nanoseconds, smoothed - the freeze of one column, or one border
     * strip. Seeded and then replaced by measurement; every sizing
     * argument in this class reads this and nothing else.
     *
     * <p><b>What it used to be, and why the change is the wave.</b> This
     * field was {@code extractCostNanosEma}, the cost of a whole walk,
     * and the far-armed bench measured that at <b>2.9-6.8 ms at the
     * median and 21-25 ms at p99 over fresh ground</b> - three to five
     * times the brief's estimate at p50 and an order of magnitude at the
     * tail. A work unit that large cannot be scheduled into a 5 ms frame
     * by any budget rule, which is exactly what Phase 2's numbers showed:
     * it cut the far field's mean frame cost 14x and still left 210
     * frames overrunning their grant, with an overrun p95 of 13.6 ms.
     * <b>No rule can subdivide an atom; only a smaller atom can.</b>
     * That is this wave.</p>
     */
    private static long captureCostNanosEma = SEED_CAPTURE_COST_NANOS;

    /**
     * Chunk-lifecycle events (arrivals plus drops) seen since the current
     * slice opened. A plain int increment on the storm path.
     *
     * <p><b>T2: this no longer funds anything.</b> It used to feed the
     * keep-up term, which claimed {@code events * measured walk cost} of
     * the frame; that term is deleted (see the T2 section by the budget
     * constants) because a budget made of absolute milliseconds is the
     * defect the wave removes, and a keep-up FLOOR is that defect in its
     * most direct form - it grew without limit as the client accepted
     * more chunks, which is exactly when the frame could least afford
     * it. The measurement is kept and published
     * ({@link #chunkEventsLastSlice()}) because it is the honest input
     * to the question the term was trying to answer - "is the client
     * accepting chunks faster than we are storing them" - and Phase 3
     * consumes it as the worker's in-flight pacing signal rather than as
     * a claim on the game thread.</p>
     */
    private static int eventsThisSlice;
    /** {@link #eventsThisSlice} as of the previous slice. */
    private static int eventsLastSlice;

    /**
     * Camera chunk at the previous slice - the ring-bucket queues'
     * reference point ({@link #cameraRing}), refreshed once per pump.
     * (It was also half of the deleted idle test; the queues are its
     * only remaining reader and they always needed it.)
     */
    private static int lastSliceCameraX;
    /** See {@link #lastSliceCameraX}. */
    private static int lastSliceCameraZ;

    // ------------------------------------------------------------------
    // M5: the pin bookkeeping. ALL game-thread-only: the worker sees
    // pins only through its own queues (see PinWorker's confinement
    // note) and never these structures.
    // ------------------------------------------------------------------

    /**
     * Every un-settled pin by column key, registered at CAPTURE (phase
     * 1, before any triage) so a pin's finish can resolve its storm
     * siblings into direct {@code sidePin}/{@code diagPin} refs - the
     * pin GRAPH. Game thread only; entries leave through
     * {@link #releasePin} and the world-change resets.
     */
    private static Long2ObjectOpenHashMap<Pin> pinIndex;

    /**
     * Admitted pins in admission order - E9's oldest-first release
     * ladder replacement (one deque, no ladder: the heap backstop is
     * the ONLY discard). Settled pins are skipped lazily at the head.
     */
    private static java.util.ArrayDeque<Pin> admittedPins;

    /** PINNED-record population; see {@link #pinnedCount()}. */
    private static int pinnedGauge;

    /**
     * Phase 3: LIVE_DIRTY records whose capture is with the worker -
     * the fill path's in-flight population, kept apart from
     * {@link #pinnedGauge} because the two answer different questions
     * (this one is "how much is being saved right now", that one is
     * "how much terrain do we hold the only copy of"). Moved only by
     * {@link #finishPin} and {@link #releasePin}.
     */
    private static int liveCaptureGauge;

    /** First E9 breach observation, for the confirmation window. */
    private static long e9BreachSinceNanos = Long.MIN_VALUE;

    /**
     * The far field is standing down: extract NOTHING, capture references
     * only. Set by {@code FarFieldResidency} while vanilla rebuilds its
     * geometry (a render-distance change, a resource reload, a dimension
     * swap). Volatile because the walker sets it from the pump and the
     * chunk-lifecycle seams read it from the packet handlers; both are the
     * client main thread today, and the volatile costs nothing next to an
     * extraction.
     */
    private static volatile boolean standDown;

    /**
     * Cyclic CHEBYSHEV-RING cursor for the catch-up sweep - the ring
     * radius half; see {@link #sweepLoadedWindow}. Reset on any world
     * change.
     *
     * <p><b>N1: this was a raster cursor and the order was the visible
     * half of the defect.</b> The owner's complaint is not only that the
     * ring fills slowly, it is what a half-filled ring LOOKS like: "it
     * being patchy with random chunks looks way worse then not having
     * it". A raster over the loaded square stores a horizontal BAND, and
     * a band of far terrain with nothing north or south of it reads as
     * corruption. Chebyshev rings from the camera outward visit the same
     * square, exactly once each, for the same one-step-per-probe cost -
     * and a partial pass is then a DISC that grows outward, which is the
     * same visual language as the ring walk's own near-first refill and
     * reads as loading rather than as damage.</p>
     */
    private static int sweepRing;

    /** Index within {@link #sweepRing}; {@code 8 * r} entries, 1 at r = 0. */
    private static int sweepIndex;

    /**
     * Columns the safety-net walk found wanting and FILED since the cursor
     * last wrapped - published on {@link #farExtractSweepOwed} at the
     * wrap. (The backlog signal itself is the job queues now; this is
     * the per-pass census figure.)
     */
    private static int sweepOwedThisPass;

    // ------------------------------------------------------------------
    // M6: the save census is the RECORD MAP now. P3's window census -
    // five pass accumulators and five published gauges, classified probe
    // by probe over a full sweep pass - died with the sweep's census
    // role: it could only see the loaded window, so a column that left
    // dirty (the exact population the rebuild is about) fell out of its
    // denominator, and its figures lagged a full pass behind the world.
    // The record map already holds every column that ever produced an
    // event, with derived dirtiness (I2), so the page's numbers are one
    // bounded fold over it, cached a second - see saveCensus(). The
    // design's "gauges maintained on transitions" became this fold
    // deliberately: state writes sit at ~15 sites and the review already
    // caught two hand-spelled transition copies diverging once, so the
    // census DERIVES from the one truth instead of shadowing it.
    // ------------------------------------------------------------------

    /**
     * M6: the record-derived save census, the Layer 1 page's two lines.
     * Everything here is a whole-SESSION figure over the record map -
     * columns the client has held at some point - not a window figure:
     * {@code waiting == 0} (with the leaving line at zero) means every
     * column the client ever held this session is stored at its latest
     * truth, which is the question "can I turn my render distance down"
     * actually asks. Game thread only, like the map it folds.
     */
    public static final class SaveCensus {
        /** Records stored at their live version, whole (or empty). */
        public final int savedWhole;
        /** Records stored at their live version with a quality reason bit. */
        public final int savedDegraded;
        /** Dirty records still in reach: LIVE_DIRTY (PINNED is the leaving line). */
        public final int waiting;
        /** Of the dirty records, those with an OLD shell on disk (Q4's number). */
        public final int stale;

        SaveCensus(int savedWhole, int savedDegraded, int waiting, int stale) {
            this.savedWhole = savedWhole;
            this.savedDegraded = savedDegraded;
            this.waiting = waiting;
            this.stale = stale;
        }
    }

    /** {@link #saveCensus} cache; the fold is bounded but not free. */
    private static SaveCensus censusCache;
    /**
     * Wall-clock stamp of {@link #censusCache}. Wall clock rather than
     * {@code frameMillis} on purpose: the screen ticks while the
     * integrated server is paused, when no pump runs and
     * {@code frameMillis} stands still.
     */
    private static long censusCacheMillis = Long.MIN_VALUE;

    /**
     * Fold the record map into the page's numbers, at most once a
     * second (the map is bounded at {@code MAX_TRACKED_COLUMNS} but a
     * long session can genuinely hold six figures of records, and the
     * screen ticks twenty times a second). GONE_BEHIND is deliberately
     * NOT in {@code waiting}: it is the behind gauge's population and
     * counting it twice is how the old page's {@code lost} sum lied.
     * Game thread only.
     */
    public static SaveCensus saveCensus() {
        long now = System.currentTimeMillis();
        SaveCensus cached = censusCache;
        if (cached != null && now - censusCacheMillis < 1_000L) {
            return cached;
        }
        int savedWhole = 0;
        int savedDegraded = 0;
        int waiting = 0;
        int stale = 0;
        Long2ObjectOpenHashMap<ColumnRecord> map = columns;
        if (map != null) {
            for (ColumnRecord rec : map.values()) {
                if (rec.liveVersion > rec.storedVersion) {
                    if (rec.state == STATE_LIVE_DIRTY) {
                        waiting++;
                        if (rec.storedVersion > 0) {
                            stale++;
                        }
                    }
                    // PINNED rides the leaving line's pin gauges;
                    // GONE_BEHIND rides the behind gauge.
                } else if (rec.storedVersion > 0) {
                    if ((rec.quality & (QUALITY_WHOLE | QUALITY_EMPTY)) != 0) {
                        savedWhole++;
                    } else {
                        savedDegraded++;
                    }
                }
            }
        }
        cached = new SaveCensus(savedWhole, savedDegraded, waiting, stale);
        censusCache = cached;
        censusCacheMillis = now;
        return cached;
    }

    /**
     * Level identity watch, the {@code FarFieldResidency.lastLevel}
     * precedent. Column (0, 0) of the next world is not column (0, 0) of
     * this one, so the extract-once tracker must never survive a world
     * change — and the enumerated world-change seams cannot be trusted to
     * cover every case on their own, because
     * {@code MinecraftLevelSwapMixin} calls {@link #onLevelSwap()} only
     * while the far field is ARMED. Toggle the master switch off, change
     * world, toggle it back on, and this identity check is the only thing
     * standing between the player and a tracker full of another world's
     * columns. A weak reference so a dead level is never pinned here.
     */
    private static java.lang.ref.WeakReference<Object> lastLevel =
            new java.lang.ref.WeakReference<>(null);

    private ExtractDispatch() {}

    /** The mixins' fast-path gate: one volatile read, nothing else. */
    public static boolean armed() {
        return armed;
    }

    /**
     * Re-resolve {@link #armed()} from {@code FarFieldConfig.enabled()}.
     * Call after ANY master-switch change; cheap enough to call freely.
     */
    public static void refreshArmed() {
        armed = resolveEnabled();
        if (!armed) {
            // Switched off mid-session: nothing will ever drain the
            // pins, and holding tens of megabytes of terrain for a
            // feature the player just turned off is exactly the "off
            // costs nothing" promise being broken.
            dropLeavingCaptures();
            dropLiveCaptures(); // Phase 3: the deferred ones...
            abandonPins();      // ...and the ones already with the worker
            standDown = false;
        }
        // Whichever way the switch went, the open slice was sized for the
        // other state: its budget and its event count both describe a far
        // field that was doing something else. Closing it makes the next
        // seam or pump take a fresh measurement rather than inherit one.
        // Phase 3: there is no cross-frame credit left to clear - the
        // slice's own odometer is reset by the next openSlice, and the
        // frozen-but-unfinished captures were dropped above on the arm
        // that needs it. A RE-arm deliberately drops nothing: it has
        // nothing to drop, because the disarm already did.
        sliceOpen = false;
        eventsThisSlice = 0;
        eventsLastSlice = 0;
    }

    private static boolean resolveEnabled() {
        try {
            return FarFieldConfig.enabled();
        } catch (Throwable t) {
            // Config unavailable this early (or broken): stay inert. A
            // chunk unload must never fail because the far field cannot
            // read its own switch.
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Seam 1 (pre2 primary): the receive side
    // ------------------------------------------------------------------

    /**
     * E1 (and E3, same seam): {@code ClientPacketListener
     * .enableChunkLight(LevelChunk, int, int)} TAIL - chunk data and
     * light are both applied and the chunk is in the cache (the caller
     * re-fetched it through {@code ClientChunkCache.getChunk} and
     * null-checked it, lambda bytecode ip 18-25).
     *
     * <p>Transitions the record and FILES work; it never walks a chunk.
     * Every arrival - first send, re-send, window return - stamps a
     * fresh {@code liveVersion}, because the copy that just arrived is
     * the server's own and the world went on ticking while the column
     * was away. That single rule deletes two pre16 loss classes at this
     * seam: the re-send discard (the epoch throttle refused the truth of
     * an in-place re-send - ClientChunkCache.replaceWithPacketData
     * mutates the SAME LevelChunk object with no unload and no
     * markUnsaved, so the packet's arrival is the only evidence there
     * is) and the deliberate re-extract-on-return, which the code's own
     * "right rather than wasteful" comment always wanted and the tracker
     * always overruled.</p>
     *
     * <p>The E2 fanout: C's arrival is what can complete the plus of its
     * four LATERAL neighbours (a diagonal is not completed by C), so
     * each lateral that is now neighbour-complete gets its queued FILL
     * opened, and one whose STORED shell is degraded on the side facing
     * C (or light-partial) is re-dirtied for its upgrade. Neighbour
     * completeness is a FILING heuristic, never a pop guard: filing a
     * frontier column early would buy a degraded shell plus an upgrade
     * walk, but a job that races a drop anyway just extracts degraded
     * and labels it - the quality byte, not the schedule, carries the
     * outcome.</p>
     */
    public static void onChunkReceived(LevelChunk chunk) {
        if (!armed) {
            return;
        }
        noteLevel(chunk);
        // Counted BEFORE anything else: this is the keep-up term's input,
        // and an arrival is an arrival whatever the stand-down says. The
        // bookkeeping below is O(1) per arrival; only EXTRACTION honours
        // the stand-down, and none happens at this seam any more.
        eventsThisSlice++;
        ChunkSource source = chunk.getLevel().getChunkSource();
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        long key = columnKey(cx, cz);
        ColumnRecord rec = ensureRecord(key);
        if (rec.state == STATE_GONE_BEHIND) {
            repairBehind(rec); // the repair path: the player came back
        } else if (rec.state == STATE_PINNED) {
            // A fresh arrival supersedes the capture (the design's E1
            // row: release the pin; a worker already mid-walk finishes
            // harmlessly - version ordering at E8 sorts the two writes).
            releasePin(rec);
        }
        if (rec.storedVersion > 0 && rec.liveVersion <= rec.storedVersion) {
            farExtractStaleFiled.increment(); // a stored shell just went stale
        }
        rec.liveVersion = ++nextStamp;
        rec.state = STATE_LIVE_DIRTY;
        // E3 shares this seam: the light half of this packet has been
        // applied (the mixin runs at enableChunkLight TAIL), so the
        // record's own light is publishable truth from here on - and a
        // job G1 parked while the light was pending re-files now.
        boolean wasLightReady = rec.lightReady;
        rec.lightReady = true;
        if (!wasLightReady && rec.jobClass != JOB_NONE
                && !rec.filedEdit && !rec.filedFill) {
            refileJob(rec, key);
        }
        // The four laterals, fetched ONCE: C's completeness test and all
        // four fanouts read these references (each fanout then probes
        // only ITS three unknown sides - C is its known-loaded fourth).
        LevelChunk west = source.getChunk(cx - 1, cz, false);
        LevelChunk east = source.getChunk(cx + 1, cz, false);
        LevelChunk north = source.getChunk(cx, cz - 1, false);
        LevelChunk south = source.getChunk(cx, cz + 1, false);
        if (west != null && east != null && north != null && south != null) {
            fileFill(rec, key);
        }
        // E2/E3 fanout over the four laterals.
        fanOutNeighbour(source, cx - 1, cz, QUALITY_SIDE_EAST, west);
        fanOutNeighbour(source, cx + 1, cz, QUALITY_SIDE_WEST, east);
        fanOutNeighbour(source, cx, cz - 1, QUALITY_SIDE_SOUTH, north);
        fanOutNeighbour(source, cx, cz + 1, QUALITY_SIDE_NORTH, south);
        // E10's half of the arrival: this seam IS enableChunkLight, and
        // enableChunkLight is what ALLOCATES this column's light layers
        // (javap: LevelLightEngine.updateSectionStatus per section, ip
        // 24-76) - which makes vanilla mark a two-column halo affected
        // without a single light value changing. The mirror must not
        // read that as an edit; stamp the halo so it does not. The
        // ordering is guaranteed, not hoped for: this whole handler runs
        // inside ClientLevel.pollLightUpdates (the arrival's light work
        // is a queued runnable, ip 38-52 of handleLevelChunkWithLight),
        // and ClientLevel.update calls pollLightUpdates at ip 13 and
        // runLightUpdates - which publishes the halo - at ip 26-33, in
        // that order, in the same frame.
        stampLightNoise(cx, cz);
    }

    /**
     * E2 (neighbour became usable), fanned out from E1: the arrival of C
     * may have completed the plus of the lateral neighbour at (x, z) -
     * {@code sideBit} names the side of THAT column which C fixes.
     *
     * <p>Three cases, all O(1) plus at most five chunk-cache probes: a
     * DIRTY live neighbour whose plus is now complete gets its FILL
     * filed (this is the old deferral's "not our turn" opening); a CLEAN
     * stored neighbour whose quality is degraded on this side, or
     * light-partial, is re-dirtied for its upgrade (bounded exactly as
     * the old sweep upgrade was: a re-walk can only be re-armed by a
     * fresh ARRIVAL, never by the passage of time, so the cost is one
     * re-extraction per column per arrival in every dimension whatever
     * the light engine does); and a loaded column with NO record - the
     * far field was armed mid-session - is discovered here for free.</p>
     */
    private static void fanOutNeighbour(ChunkSource source, int x, int z,
            byte sideBit, LevelChunk held) {
        if (held == null) {
            return; // not loaded; its own arrival will file it
        }
        long key = columnKey(x, z);
        ColumnRecord rec = columns.get(key);
        if (rec == null) {
            if (!plusCompleteExcept(source, x, z, sideBit)) {
                return; // the safety-net walk discovers it if nothing else does
            }
            rec = ensureRecord(key);
            fileFill(rec, key);
            return;
        }
        if (rec.state == STATE_LIVE_DIRTY) {
            if (rec.jobClass != JOB_EDIT && !rec.filedFill
                    && plusCompleteExcept(source, x, z, sideBit)) {
                fileFill(rec, key);
            }
            return;
        }
        if (rec.state != STATE_LIVE_CLEAN) {
            return; // PINNED/GONE: their own seams own them
        }
        if ((rec.quality & (QUALITY_WHOLE | QUALITY_EMPTY)) != 0) {
            return; // nothing to upgrade
        }
        if ((rec.quality & (sideBit | QUALITY_LIGHT_PARTIAL)) == 0) {
            return; // degraded for a side C does not fix
        }
        if (!plusCompleteExcept(source, x, z, sideBit)) {
            return; // upgrade when the plus is whole, like the old sweep
        }
        rec.liveVersion = ++nextStamp;
        rec.state = STATE_LIVE_DIRTY;
        fileFill(rec, key);
    }

    /**
     * The plus of (x, z) is complete, given that the neighbour on
     * {@code knownSide} is already known loaded (it is C, the arrival
     * that triggered the fanout): three probes instead of four.
     */
    private static boolean plusCompleteExcept(ChunkSource source, int x, int z,
            byte knownSide) {
        return (knownSide == QUALITY_SIDE_WEST
                        || source.getChunk(x - 1, z, false) != null)
                && (knownSide == QUALITY_SIDE_EAST
                        || source.getChunk(x + 1, z, false) != null)
                && (knownSide == QUALITY_SIDE_NORTH
                        || source.getChunk(x, z - 1, false) != null)
                && (knownSide == QUALITY_SIDE_SOUTH
                        || source.getChunk(x, z + 1, false) != null);
    }

    // ------------------------------------------------------------------
    // E4: the block-change event (M3) - the hook that killed the epoch
    // ------------------------------------------------------------------

    /**
     * E4: a real client-side block change landed in this chunk -
     * {@code LevelChunk.setBlockState} TAIL, which vanilla only reaches
     * when the section palette actually changed ({@code old == new}
     * returns null at ip 84-85 before any heightmap work, javap 26.2
     * merged jar; the other null exits precede it too, so TAIL is the
     * success path and nothing else).
     *
     * <p><b>The cost argument, exactly:</b> one static volatile read and
     * one {@code isClientSide} test in the mixin, then here one weak-ref
     * identity compare ({@link #noteLevel}), one map get, a handful of
     * field writes, and - only when no EDIT job is already queued for
     * the column - one {@code LongArrayList} append. A farm, a fill or a
     * redstone machine churning one column lands on the coalesce arm:
     * {@link #farSaveEditsCoalesced} moves, the stamp refreshes, and
     * NOTHING is allocated or filed. O(1), tens of nanoseconds, no cap,
     * no epoch, no throttle - the {@value #EDIT_COALESCE_MILLIS} ms
     * coalesce window plus the scheduler's budget are the entire churn
     * bound, which is what makes edits PROMPT: edit-to-store latency is
     * coalesce + schedule + write, never a 30-second epoch.</p>
     *
     * <p>This one hook replaces the whole {@code isUnsaved()} polling
     * detector, and with it three loss classes the audit convicted: the
     * 30 s epoch lag (Q4), the degraded-column silent edit loss (the
     * staleness test now runs for every quality - it is arithmetic), and
     * the MAX_STALE_COLUMNS drop-seam overflow loss (there is no set to
     * overflow).</p>
     *
     * <p>PINNED/GONE states are unreachable here in principle (no client
     * {@code setBlockState} can land on a chunk outside the cache -
     * {@code Level.setBlock} routes through {@code getChunkAt}), but a
     * record CAN lag reality across an E6 walk racing the forget storm;
     * the chunk in hand is the truth, so the record self-heals to LIVE
     * instead of asserting.</p>
     */
    public static void onBlockChanged(LevelChunk chunk) {
        if (!armed) {
            return;
        }
        noteLevel(chunk);
        long key = columnKey(chunk.getPos().x(), chunk.getPos().z());
        ColumnRecord rec = columns == null ? null : columns.get(key);
        if (rec == null) {
            // Armed mid-session (or overflow-evicted): the edit is the
            // discovery. The chunk is loaded and lit by construction.
            rec = ensureRecord(key);
        } else if (rec.state == STATE_GONE_BEHIND) {
            repairBehind(rec); // self-heal: it is demonstrably cached
        } else if (rec.state == STATE_PINNED) {
            // Self-heal across an E6/forget race: the chunk in hand IS
            // in the cache (Level.setBlock routes through getChunkAt),
            // so the capture is superseded. lightReady keeps whatever
            // the record knew - a fast-flight pin's column stays unlit
            // until its re-send's E3, and G1 holds this EDIT till then.
            releasePin(rec);
        }
        if (rec.storedVersion > 0 && rec.liveVersion <= rec.storedVersion) {
            farExtractStaleFiled.increment();
        }
        rec.liveVersion = ++nextStamp;
        rec.state = STATE_LIVE_DIRTY;
        fileEdit(rec, key);
    }

    // ------------------------------------------------------------------
    // E10: the light-change event (pre19 S4) - the neighbour a block
    // change dirties without ever touching it
    // ------------------------------------------------------------------

    /**
     * E10: vanilla's light engine published a changed section, so its
     * column may hold a stored shell whose light plane is now a lie.
     * Called from {@code ClientChunkCacheLightMixin} at
     * {@code ClientChunkCache.onLightUpdate} HEAD, on the game thread,
     * inside {@code ClientLevel.update()} and therefore BEFORE this
     * frame's pump.
     *
     * <h2>The bug, stated exactly</h2>
     * E4 dirties the column CONTAINING the changed block. Block light
     * propagates up to 15 blocks and a chunk is 16 wide, so a torch
     * placed anywhere but dead centre changes light values INSIDE a
     * neighbouring column - and that column was clean, so
     * {@link #onChunkDropping} closed it GONE_SAVED in one probe with
     * its pre-torch plane on disk, permanently. The owner saw it as
     * "the lod lighting works within its chunk, the next chunk over
     * doesnt have any of that lighting". The invalidation UNIT was
     * wrong: for lighting purposes a block change dirties a REGION, and
     * the region is not a column.
     *
     * <h2>Why this is vanilla's answer and not a radius of our own</h2>
     * A conservative radius keyed on the emission degenerates: the
     * reach of block light is 15, so "which columns can this reach"
     * answers "all four laterals" for 15 of the 16 offsets on each
     * axis. Keying on the emission does not shrink it either - a torch
     * IS 14. What DOES shrink it is knowing which light values actually
     * changed, and vanilla computes exactly that:
     * {@code LayerLightSectionStorage.setStoredLevel} feeds every write
     * through {@code SectionPos.aroundAndAtBlockPos} into
     * {@code sectionsAffectedByLightUpdates}, and {@code swapSectionMap}
     * hands the deduped set to this callback once per light run (the
     * javap citations live in the mixin). A torch buried in stone marks
     * one column; the same torch in the open marks what it really
     * lights; a stone block placed in a pitch-black cave marks NOTHING,
     * because no light value changed - {@code hasDifferentLightProperties}
     * was true, {@code checkBlock} ran, and the propagation found
     * nothing to write. That last case is the common one for a fill,
     * and it is free.
     *
     * <h2>The one thing vanilla marks that is not a light change</h2>
     * {@code initializeSection} - a light data layer being CREATED -
     * runs {@code markSectionAndNeighborsAsAffected}, a 3x3x3 section
     * halo, so a chunk ARRIVAL marks a 5x5 of columns affected without
     * a single light value changing anywhere. Mirroring that would
     * re-extract a 5x5 of settled, already-stored columns around every
     * arriving chunk - ruinous on a flight back over ground the player
     * has already saved. It is suppressed by {@link #stampLightNoise},
     * which E1 runs over the arriving column's Chebyshev-2
     * neighbourhood: the two producers cannot be told apart at THIS
     * callback, but they can be told apart at their source, and E1 is
     * that source. The suppression cannot hide a real edit in practice -
     * arrivals happen at the send disc's rim, edits happen where the
     * player is standing, and a column that is both is dirty from E1
     * anyway.
     *
     * <h2>Cost</h2>
     * One map get and two long compares per affected section per light
     * run - per SECTION and per RUN, never per block change, because
     * vanilla's own {@code LongSet} did the deduping. The first
     * callback for a column files the EDIT; the other ~47 (up to 24
     * sections x 2 layers) take the "already ahead of both versions"
     * exit one compare in. Zero allocation, zero clock reads, no cap
     * and no epoch: the coalesce window and the scheduler budget bound
     * this exactly as they bound E4.
     */
    public static void onLightChanged(int chunkX, int chunkZ) {
        if (!armed) {
            return;
        }
        Long2ObjectOpenHashMap<ColumnRecord> map = columns;
        if (map == null) {
            return;
        }
        long key = columnKey(chunkX, chunkZ);
        ColumnRecord rec = map.get(key);
        if (rec == null) {
            // No record means nothing stored and no arrival seen: there
            // is no plane to invalidate, and manufacturing records from
            // vanilla's halo would populate the map off an allocation
            // (the review's allocation-spike rule). Discovery stays the
            // census walk's job, exactly as it was.
            return;
        }
        if (rec.lightNoiseRun == lightRun) {
            return; // an arrival ALLOCATED light here; nothing changed
        }
        if (rec.state != STATE_LIVE_CLEAN && rec.state != STATE_LIVE_DIRTY) {
            // PINNED/GONE: their own seams own them, exactly as the E2
            // fanout decides. A pinned column is out of the cache, so
            // its sections cannot be marked here in the first place.
            return;
        }
        if (rec.liveVersion > rec.storedVersion
                && rec.liveVersion > rec.writingVersion) {
            // Already dirty AND no walk has read it at this version yet:
            // the queued job will see the new light when it runs. This
            // is the exit every repeat callback for the same column
            // takes. The complement is why the test is not simply "is it
            // dirty": a write IN FLIGHT was walked at
            // liveVersion == writingVersion, so its shell already holds
            // the OLD light and the version must move or its ack settles
            // a lie as LIVE_CLEAN.
            return;
        }
        if (rec.storedVersion > 0 && rec.liveVersion <= rec.storedVersion) {
            farExtractStaleFiled.increment(); // a stored shell went stale
        }
        farLightCrossChunkDirty.increment();
        rec.liveVersion = ++nextStamp;
        rec.state = STATE_LIVE_DIRTY;
        fileEdit(rec, key);
    }

    /**
     * E10's suppression stamp: mark this column's
     * Chebyshev-{@value #LIGHT_NOISE_RADIUS} neighbourhood as "light
     * layers were ALLOCATED here in this run", so
     * {@link #onLightChanged} ignores the halo
     * {@code initializeSection} is about to publish for them.
     *
     * <p>Run at E1 only. The FORGET path adds nothing to the affected
     * set (javap: {@code markNewInconsistencies} puts removals on
     * {@code changedSections} at ip 167-182, and only
     * {@code sectionsAffectedByLightUpdates} reaches
     * {@code onLightUpdate}), and the E6 storm walks tens of thousands
     * of cells under a hard deadline where 25 probes a cell would not
     * fit and would buy nothing.</p>
     *
     * <p>Existing records only - a plain {@code get}, never
     * {@code ensureRecord}: only a record that already holds a stored
     * or in-flight shell can be wrongly invalidated, and those exist by
     * definition. 25 map probes an arrival, about 3 microseconds a
     * frame at a 130-chunk-a-second flight, against the ~1 ms per
     * column the spurious re-extractions would each have cost.</p>
     */
    private static void stampLightNoise(int cx, int cz) {
        Long2ObjectOpenHashMap<ColumnRecord> map = columns;
        if (map == null || map.isEmpty()) {
            return;
        }
        long run = lightRun;
        for (int dz = -LIGHT_NOISE_RADIUS; dz <= LIGHT_NOISE_RADIUS; dz++) {
            for (int dx = -LIGHT_NOISE_RADIUS; dx <= LIGHT_NOISE_RADIUS; dx++) {
                ColumnRecord neighbour = map.get(columnKey(cx + dx, cz + dz));
                if (neighbour != null) {
                    neighbour.lightNoiseRun = run;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // The slice: a fraction of the measured frame (T2, Phase 2 of
    // docs/FARFIELD-PERF-BRIEF.md)
    // ------------------------------------------------------------------

    /**
     * Open the slice for this frame, taking the ONE measurement the
     * budget rule needs. Called once per far-field pump and nowhere else.
     *
     * <p>The frame-time sample is CLAMPED into a believable band rather
     * than dropped ({@link #MIN_FRAME_SAMPLE_NANOS} ..
     * {@link #MAX_FRAME_SAMPLE_NANOS}) before it is folded into the EMA.
     * Clamping rather than rejecting is deliberate: a genuinely slow
     * client must still be read - at the top of the band the cap binds
     * and a 10 fps machine takes 2 ms and keeps saving - while a frame
     * that straddled a loading screen, a resource reload or a debugger
     * breakpoint is pulled back to something the rule can act on sanely.
     * Under the old headroom rule an unclamped long frame SUPPRESSED the
     * budget, which was self-correcting; under a fraction rule it
     * INFLATES it, which is not, so this clamp carries more weight than
     * the one it replaces and the band is tighter for it.</p>
     *
     * <p>The first sample SEEDS the EMA rather than blending into zero,
     * so the {@link #ASSUMED_FRAME_NANOS} stand-in governs at most the
     * frames before the first pump that sees a reading.</p>
     *
     * @param frameNanos {@code Minecraft.getFrameTimeNs()}, or 0 when it
     *                   is not available yet
     * @param cameraX    camera chunk X, for the ring-bucket queues
     * @param cameraZ    camera chunk Z, for the ring-bucket queues
     */
    private static void beginSlice(long frameNanos, int cameraX, int cameraZ) {
        long now = System.nanoTime();
        if (frameNanos > 0L) {
            long sample = Math.max(MIN_FRAME_SAMPLE_NANOS,
                    Math.min(MAX_FRAME_SAMPLE_NANOS, frameNanos));
            frameNanosEma = frameNanosEma == 0L ? sample
                    : frameNanosEma + ((sample - frameNanosEma) >> FRAME_EMA_SHIFT);
        }
        lastSliceCameraX = cameraX;
        lastSliceCameraZ = cameraZ;
        openSlice(now, frameNanosEma == 0L ? ASSUMED_FRAME_NANOS : frameNanosEma);
    }

    private static void openSlice(long now, long frameNanos) {
        if (sliceOpen) {
            // Close the previous slice's books BEFORE the odometer is
            // reset. Two tail figures the mean cannot show: what the
            // worst single frame cost, and by how much the worst frame
            // exceeded its own grant. The second is the price of a walk
            // that cannot be interrupted, and it is bounded by one walk;
            // the first is what the owner actually feels.
            if (sliceSpentNanos >= sliceBudgetNanos && sliceBudgetNanos > 0L) {
                farExtractSlicesSpent.increment();
            }
            if (sliceSpentNanos > worstSliceNanos) {
                worstSliceNanos = sliceSpentNanos;
            }
            long overrun = sliceSpentNanos - sliceBudgetNanos;
            if (overrun > 0L) {
                farBudgetOverrunNanos.add(overrun);
                if (overrun > worstOverrunNanos) {
                    worstOverrunNanos = overrun;
                }
            }
        }
        // Phase 1 instrument: fold the slice that is ending into the
        // per-frame distributions while its clock and ceiling are both
        // still readable. static-final-gated, so this is `if (false)`
        // on every run that did not ask for it.
        if (PERF_STATS && sliceOpen) {
            recordClosingSlice();
        }
        sliceOpen = true;
        sliceSpentNanos = 0L;
        frameMillis = now / 1_000_000L;
        evictionTriedThisSlice = false;
        eventsLastSlice = eventsThisSlice;
        eventsThisSlice = 0;
        sliceFrameNanos = frameNanos;
        int cacheSpeed = resolveCacheSpeed();
        long ceiling = resolveCeilingNanos();
        if (resolveAdaptive()) {
            sliceBudgetNanos = sliceBudgetFor(frameNanos, cacheSpeed, ceiling);
            // Which clamp bound, computed from the UNCLAMPED share so the
            // two counters cannot both fire and neither can be confused
            // by the user ceiling. The bench reads these to check the
            // rule against its own per-frame timings: cap-bound frames
            // are the slow-machine arm, floor-bound frames the very fast
            // one, and a scenario with neither is the pure share.
            long share = budgetShareNanos(frameNanos, cacheSpeed);
            if (share > budgetCapNanos(cacheSpeed)) {
                farBudgetCapBound.increment();
            } else if (share < budgetFloorNanos(cacheSpeed)) {
                farBudgetFloorBound.increment();
            }
        } else {
            // The dev A/B lever, unchanged in spirit and re-pointed in
            // fact: meshelium.farfield.adaptiveExtraction=false restores
            // a FLAT slice of exactly extractBudgetMillis, which is now
            // the pre-T2 rule's floor standing on its own. It is the one
            // way to reproduce the old cost on purpose, which is what a
            // bisect of this wave needs.
            sliceBudgetNanos = Math.max(0L, ceiling);
        }
        // Phase 3: nothing carries over. sliceBudgetNanos IS the whole
        // grant for this frame, sliceSpentNanos is the odometer against
        // it, and a capture step either fits in what is left or waits for
        // the next frame. "Never touch the game thread" (a zero ceiling)
        // therefore takes effect on the very frame the player sets it,
        // with no bucket left holding a walk's worth of credit.
        //
        // P3: the leaving path's line, always above the fill path's and
        // never above the stall guarantee. Computed here so neither the
        // drop seam nor the E6 triage does arithmetic per chunk; the
        // reserve is a fraction of the same measured frame, and it is
        // NOT scaled by the Background Saving dial (that dial governs
        // fill rate, never loss).
        sliceLeaveBudgetNanos = Math.min(MAX_SLICE_NANOS,
                sliceBudgetNanos + leaveReserveNanosFor(frameNanos));
        farBudgetGrantedNanos.add(sliceBudgetNanos);
        farBudgetFrameNanos.add(frameNanos);
        farExtractSlices.increment();
    }

    /**
     * What one game-thread CAPTURE STEP is expected to cost right now,
     * nanoseconds - the measured recent average
     * ({@link #captureCostNanosEma}), a running average of real capture
     * steps on this machine and this terrain rather than any constant.
     *
     * <p>The plain mean, not a padded p95. Padding would make the fit
     * test refuse more often to buy a smaller overshoot, and the
     * overshoot it is buying against is now one capture step - tens of
     * microseconds - so there is nothing worth paying for it with. About
     * half of all steps come in over the mean and overshoot by the
     * difference, which is counted on {@link #farBudgetOverrunNanos} and
     * should read as noise there rather than as milliseconds.</p>
     */
    private static long projectedCaptureNanos() {
        return captureCostNanosEma;
    }

    /**
     * The leaving path's reserve for a frame of this length, nanoseconds
     * - the same fraction discipline as the fill line, with its own cap
     * and floor, and deliberately NOT scaled by the Background Saving
     * dial. See {@link #LEAVE_RESERVE_PERMILLE}.
     */
    private static long leaveReserveNanosFor(long frameNanos) {
        long share = Math.max(0L, frameNanos) * LEAVE_RESERVE_PERMILLE / 1000L;
        return Math.max(LEAVE_RESERVE_FLOOR_NANOS,
                Math.min(LEAVE_RESERVE_CAP_NANOS, share));
    }

    /**
     * <b>THE BUDGET RULE.</b> One term, two clamps, one user ceiling, and
     * every input measured rather than assumed:
     *
     * <pre>
     *     share  = frameNanos * permille(cacheSpeed) / 1000
     *     budget = clamp(share, floor(cacheSpeed), cap(cacheSpeed))
     *     budget = min(budget, userCeilingNanos)      // 0 means never
     * </pre>
     *
     * <p>At the shipped Background Saving point that is <b>a tenth of the
     * measured frame, never more than 2 ms and never less than
     * 0.25 ms</b>, which is docs/FARFIELD-PERF-BRIEF.md section 3 exactly.
     * Worked through, with the walk cost this machine measures
     * ({@code ~0.9 ms}) beside it:</p>
     *
     * <table border="1">
     *  <caption>The rule at five frame times, shipped dial point</caption>
     *  <tr><th>frame</th><th>fps</th><th>share</th><th>granted</th>
     *      <th>which clamp</th></tr>
     *  <tr><td>3.3 ms</td><td>300</td><td>0.33 ms</td><td>0.33 ms</td>
     *      <td>none</td></tr>
     *  <tr><td>5.0 ms</td><td>200</td><td>0.50 ms</td><td>0.50 ms</td>
     *      <td>none</td></tr>
     *  <tr><td>8.3 ms</td><td>120</td><td>0.83 ms</td><td>0.83 ms</td>
     *      <td>none</td></tr>
     *  <tr><td>16.7 ms</td><td>60</td><td>1.67 ms</td><td>1.67 ms</td>
     *      <td>none</td></tr>
     *  <tr><td>33.0 ms</td><td>30</td><td>3.30 ms</td><td>2.00 ms</td>
     *      <td>CAP</td></tr>
     *  <tr><td>2.0 ms</td><td>500</td><td>0.20 ms</td><td>0.25 ms</td>
     *      <td>FLOOR</td></tr>
     * </table>
     *
     * <p>Read the 30 fps row against the old rule to see the size of the
     * change: at a 33 ms frame the old rule found NO headroom below its
     * 16.7 ms target, fell back to the 3 ms guaranteed slice, and took
     * 9% of that frame; at 5 ms it found 11.7 ms of apparent headroom and
     * took 3 to 6.7 ms, i.e. 60% to 130%. The rule was upside down - it
     * spent the most exactly where there was the least to spare - and it
     * was upside down because its goal was a FRAME-RATE FLOOR. This rule
     * has no target frame rate. It cannot be upside down; a share is a
     * share at every frame rate, which is the whole argument for it.</p>
     *
     * <h2>The Background Saving dial scales the whole rule</h2>
     * <p>The owner asked for a control rather than a removal (pre14, O4:
     * "probably should have some setting if the caching is going to cause
     * that much of a performance issue"), and this method is still the
     * only place in the mod that reads it. It used to scale an absolute
     * millisecond count; it scales the FRACTION now, and the floor and
     * the cap with it, so the three points stay distinguishable at every
     * frame rate instead of collapsing together the way M6 found them
     * collapsed:</p>
     * <ul>
     *   <li>{@code CACHE_GENTLE}: 3% of the frame, 0.075 ms floor,
     *       0.6 ms cap - Gentle's published promise ("a fixed sliver;
     *       your frame rate never moves") is now literally true rather
     *       than true-on-the-machine-it-was-measured-on;</li>
     *   <li>{@code CACHE_BALANCED}: 6%, 0.15 ms floor, 1.2 ms cap;</li>
     *   <li>{@code CACHE_FAST} (shipped, and the default is unchanged):
     *       10%, 0.25 ms floor, 2 ms cap - the brief's rule exactly.</li>
     * </ul>
     * <p>Scaling the clamps and not only the share is what keeps the
     * points apart at the extremes: three shares that all clamp to the
     * same floor on a 500 fps client would be three names for one number,
     * which is the dial-that-lies defect M6 fixed once already.</p>
     *
     * <h2>What this method may never grow back</h2>
     * <p>No target frame rate, in any spelling. No term of the form
     * "spend the difference between the frame and X". No floor made of
     * absolute milliseconds - the {@code extractBudgetMillis} setting is
     * a CEILING here and its zero still means never. And no term that
     * grows with the amount of work owed: the budget is a rate, the
     * backlog is a queue, and the moment the size of the queue is allowed
     * to buy frame time the rule stops being a share of anything. That
     * last one is why {@link #backlogged()} no longer appears in this
     * method at all, and it costs nothing to give up, because <b>the
     * budget is a CEILING, not an allocation</b>: {@code sliceSpentNanos}
     * only moves when a walk actually runs, so a grant with no work
     * behind it is free.</p>
     *
     * <p>PURE, and public for the suite: no clock, no config read, no
     * static state. Everything it needs is a parameter, which is what
     * lets the budget leg in {@code MesheliumFarFieldTest} assert the
     * table above without a world, a client or a GPU.</p>
     *
     * @param frameNanos       the MEASURED recent frame time, nanoseconds
     * @param cacheSpeed       {@code FarFieldConfig.CACHE_*}
     * @param userCeilingNanos hard user ceiling; 0 means never extract
     * @return the game-thread nanoseconds this frame may spend on FILL
     */
    public static long sliceBudgetFor(long frameNanos, int cacheSpeed,
            long userCeilingNanos) {
        if (userCeilingNanos <= 0L) {
            return 0L; // "never extract on the game thread" means never
        }
        long budget = Math.max(budgetFloorNanos(cacheSpeed),
                Math.min(budgetCapNanos(cacheSpeed),
                        budgetShareNanos(frameNanos, cacheSpeed)));
        // The user ceiling is applied LAST and can only ever lower the
        // answer. extractBudgetMillis used to be a FLOOR under this
        // expression - the single largest contributor to the owner's
        // report, because 3 ms is 60% of his frame - and this is the same
        // setting with its sign corrected rather than a new one. At the
        // shipped 3 ms it never binds (the cap is 2 ms); it exists so a
        // player on a machine we have never seen can still say "not more
        // than one millisecond, ever", and so that zero still means zero.
        return Math.min(budget, userCeilingNanos);
    }

    /**
     * The unclamped share of a frame this dial point asks for,
     * nanoseconds. Pure. Split out from {@link #sliceBudgetFor} so the
     * clamp counters can say WHICH clamp bound without the rule itself
     * having a side effect - a budget function that increments a counter
     * cannot be called from a test twice with the same meaning.
     */
    private static long budgetShareNanos(long frameNanos, int cacheSpeed) {
        long share = Math.max(0L, frameNanos) * BUDGET_PERMILLE / 1000L;
        return share * dialScalePermille(cacheSpeed) / 1000L;
    }

    /** The hard cap at this dial point, nanoseconds. Pure. */
    private static long budgetCapNanos(int cacheSpeed) {
        return BUDGET_CAP_NANOS * dialScalePermille(cacheSpeed) / 1000L;
    }

    /** The floor at this dial point, nanoseconds. Pure. */
    private static long budgetFloorNanos(int cacheSpeed) {
        return BUDGET_FLOOR_NANOS * dialScalePermille(cacheSpeed) / 1000L;
    }

    /**
     * The Background Saving dial as a scale on the whole rule, permille -
     * 300, 600 or 1000. Applied to the share, the floor and the cap
     * alike; see {@link #sliceBudgetFor}.
     */
    private static int dialScalePermille(int cacheSpeed) {
        if (cacheSpeed <= FarFieldConfig.CACHE_GENTLE) {
            return 300;
        }
        return cacheSpeed >= FarFieldConfig.CACHE_FAST ? 1000 : 600;
    }

    /**
     * Does the far field still owe the store columns it could store right
     * now? The input pre10's budget rule never had.
     *
     * <p>Two populations, and each is a thing that is genuinely
     * WAITING on THIS thread, never a guess:</p>
     * <ul>
     *   <li>{@link #leavingPins}' un-triaged captures - E6 storms whose
     *       records and strips are still owed their pump work;</li>
     *   <li>the job queues ({@link #jobsQueued}) - every arrival,
     *       upgrade, edit and rescue waiting for the scheduler, PLUS
     *       whatever the safety-net walk has discovered owed. The walk files
     *       as it probes (up to {@value #SWEEP_PROBES_PER_PUMP} columns a
     *       pump), so a mid-session arming shows up here within a pump
     *       or two of the cursor reaching it.</li>
     * </ul>
     *
     * <p>Deliberately NOT a measure of the far RING's emptiness. This
     * class can only store what the client is holding; a ring that is
     * empty because the player has never been there is not a backlog and
     * must not hold a frame-time budget open forever.</p>
     */
    public static boolean backlogged() {
        // Unripe edits wait in the pending stage and are DELIBERATELY
        // invisible here: a ticking farm's one perpetually-coalescing
        // column must not read as owed work that is not yet runnable.
        // Pins are invisible too, since M5: their drain is the worker's,
        // off the game thread entirely. Captures pending TRIAGE are
        // game-thread work and count.
        //
        // T2: no longer an INPUT to the budget - see sliceBudgetFor's
        // "what this method may never grow back". A backlog is a queue
        // and the budget is a rate; the moment the length of the queue
        // is allowed to buy frame time, the rule stops being a share of
        // anything. It is published instead, because the bench needs to
        // know whether a scenario had work owed at all before any
        // fill-rate number from it means anything.
        return leavingCount() > 0 || liveCaptureCount() > 0 || jobsQueued() > 0;
    }

    /**
     * The player's hard ceiling on one slice, nanoseconds
     * ({@code extractBudgetMillis}; zero means the far field never
     * touches the game thread).
     *
     * <p><b>The same setting with its sign corrected.</b> Until T2 this
     * was {@code resolveGuaranteedNanos} - a FLOOR that the budget could
     * never fall below, which at the shipped 3 ms was 60% of the owner's
     * 5 ms frame all by itself and is the single largest term in his
     * report. Nothing about the setting's range, default, storage or
     * meaning-to-the-player changed ("how many milliseconds of the game
     * thread the far field may take"); what changed is that it can now
     * only ever LOWER the answer. Zero still means never, which is the
     * one promise it had that was worth keeping intact.</p>
     */
    private static long resolveCeilingNanos() {
        try {
            return FarFieldConfig.extractBudgetNanos();
        } catch (Throwable t) {
            // Same posture as resolveEnabled: a config that cannot be read
            // must never make a chunk packet throw. Fall back to the
            // shipped default rather than to "unlimited".
            return FarFieldConfig.DEFAULT_EXTRACT_BUDGET_MILLIS * 1_000_000L;
        }
    }

    private static boolean resolveAdaptive() {
        try {
            return FarFieldConfig.adaptiveExtraction();
        } catch (Throwable t) {
            return false; // unreadable config falls back to the flat rule
        }
    }

    /**
     * The Background Saving row (pre14, O4). An unreadable config falls
     * back to the SHIPPED value rather than to the quiet one: the same
     * posture as {@link #resolveCeilingNanos}, because a config this
     * class cannot read must not silently hand the player back the ring
     * that would not fill.
     */
    private static int resolveCacheSpeed() {
        try {
            return FarFieldConfig.layerCacheSpeed(FarFieldConfig.Layer.L1);
        } catch (Throwable t) {
            return FarFieldConfig.DEFAULT_CACHE_SPEED;
        }
    }

    /*
     * T2: resolveTargetFrameNanos() stood here and is deleted with the
     * rule that read it. It resolved FarFieldConfig.extractFrameRateFloor
     * (default 60) into a target frame time, and that target WAS the
     * defect: "defend 16.7 ms and spend the surplus" is why a 200 fps
     * client was pinned at 60 - the algorithm was hitting its stated
     * goal. A share of the frame has no target frame rate and must never
     * acquire one.
     *
     * The CONFIG FIELD survives on purpose and is now unread by this
     * class. It is a persisted field with a stored value in every
     * player's farfield JSON and its own accessor, clamp and property
     * override; deleting it is a config-schema change with a migration
     * question attached, and this wave is meant to be small and
     * reversible. FarFieldConfig.extractFrameRateFloor()/
     * extractTargetFrameNanos() therefore still work and still clamp -
     * nothing reads them. It is not in any settings ROW (Control has no
     * entry for it), so no page shows a dial that does nothing.
     */

    /**
     * M5: columns in the PINNED state - captured, waiting on the worker
     * or on their write's ack. Diagnostics for the Layer 1 page and the
     * residency's sweep-hold test; game thread (the gauge moves only
     * with {@link #finishPin}/{@link #releasePin}).
     */
    public static int pinnedCount() {
        return pinnedGauge;
    }

    /** M5: E6 captures still waiting for their pump triage. Game thread. */
    public static int pendingCaptureCount() {
        return leavingCount();
    }

    /**
     * Gametest probe (save leg (a)): every column currently tracked
     * LIVE whose truth must exist in the store once the machine
     * quiesces - i.e. excluding stored-EMPTY columns, which
     * legitimately hold no record. Game thread only (reach it through
     * {@code computeOnClient}); one pass over the map, test-time cost.
     */
    public static long[] liveColumnKeys() {
        Long2ObjectOpenHashMap<ColumnRecord> map = columns;
        if (map == null) {
            return new long[0];
        }
        LongArrayList keys = new LongArrayList(map.size());
        for (var it = map.long2ObjectEntrySet().fastIterator(); it.hasNext();) {
            var entry = it.next();
            ColumnRecord rec = entry.getValue();
            if ((rec.state == STATE_LIVE_CLEAN || rec.state == STATE_LIVE_DIRTY)
                    && (rec.quality & QUALITY_EMPTY) == 0) {
                keys.add(entry.getLongKey());
            }
        }
        return keys.toLongArray();
    }

    /**
     * T2: the ONE place game-thread nanoseconds are charged to the slice.
     * Every spender goes through it - a {@link ColumnSnapshot} freeze
     * ({@link #beginLiveCapture}) and a border strip
     * ({@link #finishPin}) - so the odometer can never disagree with what
     * the thread actually spent, which is the same lesson the
     * pin-release helper exists for.
     *
     * <p>{@link #sliceSpentNanos} is the per-FRAME odometer and, since
     * Phase 3, the only ledger: it is what both budget lines are measured
     * against and what the overrun counters close their books on. The
     * cross-frame credit bucket that used to sit beside it is gone with
     * the work unit that needed it.</p>
     *
     * <p><b>Why strips charge the same line as freezes.</b> They cost the
     * same thread the same nanoseconds. A forget storm's strips are the
     * game thread being busy on far-field work, and the fill path pausing
     * while that is true is the correct behaviour, not an accident of
     * which counter they happened to share.</p>
     */
    private static void chargeSlice(long cost) {
        sliceSpentNanos += cost;
    }

    /**
     * Fold one measured capture step into the cost model. The ONE place
     * a capture is timed, so the EMA, the counter and the histogram
     * cannot drift apart from what the budget actually spent.
     */
    private static void chargeCapture(long cost) {
        chargeSlice(cost);
        farCaptureNanos.add(cost);
        if (PERF_STATS) {
            captureCostHistogram.record(cost);
        }
        long clamped = Math.max(MIN_CAPTURE_COST_NANOS,
                Math.min(MAX_CAPTURE_COST_NANOS, cost));
        captureCostNanosEma += (clamped - captureCostNanosEma) >> COST_EMA_SHIFT;
    }

    /**
     * Is there room in THIS FRAME's grant for one more capture step?
     * Ignores the stand-down; {@link #budgetLeft} is the arm that honours
     * it, and the leaving path has its own line entirely
     * ({@link #leaveBudgetLeft}).
     *
     * <h2>Phase 3: the brief's part (c), finally in the form it asked
     * for</h2>
     * <p>The brief asked for the clock to be tested INSIDE the work loop
     * so a slice cannot overshoot by a whole extraction. Phase 2 could
     * not do it and said so: the work unit was one
     * {@code ShellExtractor.extract} call with no deadline, no clock read
     * and no resumable state, so there was no point inside it at which to
     * test anything, and the honest substitute was a cross-frame credit
     * bucket that funded the indivisible walk over several frames.
     *
     * <p><b>The measurement that settled it.</b> The far-armed bench, run
     * at a pinned 200 fps (5.0 ms frames), measured that walk at
     * {@code walk_p50 = 6.816 ms} and {@code walk_p99 = 20.972 ms} on a
     * travel leg over fresh ground - not the ~0.9 ms the census assumed.
     * Phase 2's rule behaved exactly as an un-subdividable atom demands:
     * mean far-field cost per frame fell from 3.99 ms to 0.274 ms (14x),
     * the number of overrunning frames fell from 1,369 to 210, and the
     * overruns themselves did not shrink at all
     * ({@code overrun_p95} 11.5 -> 13.6 ms, worst slice still 23 ms).
     * <b>The stutter has a floor set by the size of the atom, and no
     * scheduler can lower it.</b></p>
     *
     * <p>So Phase 3 makes the atom small instead. The unit is now one
     * capture step - a section-copy freeze, or one border strip - and a
     * dozen of them fit inside a 0.5 ms grant, which is what makes a
     * plain fit test against THIS frame's remaining grant terminate
     * instead of deadlocking. The worst single-frame overshoot becomes
     * one step rather than one walk.</p>
     */
    private static boolean budgetRemains() {
        return sliceSpentNanos + projectedCaptureNanos() <= sliceBudgetNanos;
    }

    /**
     * Is there game-thread time left for one more capture step? False
     * while standing down, whatever the clock says.
     */
    private static boolean budgetLeft() {
        return !standDown && budgetRemains();
    }

    /**
     * <b>Phase 3's pacing rule, part 1.</b> May the game thread hand the
     * worker another column at all? False while {@link PinWorker} is
     * already {@value #MAX_WORKER_BACKLOG} columns behind - the
     * self-limiting half of the rule, which needs no clock and no tuning
     * and which is what stops a fill storm from queueing minutes of
     * worker time ahead of the player's own edits.
     */
    private static boolean workerHasRoom() {
        if (PinWorker.pendingCount() < MAX_WORKER_BACKLOG) {
            return true;
        }
        farCaptureWorkerFull.increment();
        return false;
    }

    /**
     * P3: is there time left in the LEAVING half of this slice?
     *
     * <p>Two differences from {@link #budgetLeft}, and both are the
     * answer to "it should always cache, especially when leaving a
     * chunk":</p>
     * <ol>
     *   <li>it measures against {@link #sliceLeaveBudgetNanos}, which is
     *       the leaving reserve above the line the fill work stops at -
     *       so the sweep cannot starve the triage's strips, which it did
     *       deterministically before P3;</li>
     *   <li>it ignores the stand-down, for the reason it always has: a
     *       stand-down IS a render-distance change, i.e. the one moment
     *       tens of thousands of columns are being abandoned, and the
     *       strips this line funds are the last reads their captures
     *       will ever get. The cost is bounded by the reserve exactly
     *       as it is bounded everywhere else in this class: tested
     *       BEFORE a strip, never during one.</li>
     * </ol>
     */
    private static boolean leaveBudgetLeft() {
        return sliceSpentNanos < sliceLeaveBudgetNanos;
    }

    /** The slice ceiling in effect right now, microseconds. Diagnostics. */
    public static long budgetMicrosNow() {
        return sliceBudgetNanos / 1000L;
    }

    /**
     * Measured cost of one game-thread CAPTURE STEP on this machine,
     * microseconds. <b>Renamed and re-pointed at Phase 3</b> (it was
     * {@code extractCostMicros}, the cost of a whole walk): the walk is
     * the worker's now, and its cost is
     * {@code farExtractNanos / farExtracts}.
     */
    public static long captureCostMicros() {
        return captureCostNanosEma / 1000L;
    }

    // ------------------------------------------------------------------
    // T2: the budget rule, readable from outside. Gauges are "right now"
    // and the LongAdders above are "over the session"; a bench wants both
    // - the gauge to sanity-check one frame, the adders to characterise a
    // scenario. All game-thread reads of game-thread state; a test reaches
    // them through the client executor like every other probe here.
    // ------------------------------------------------------------------

    /**
     * The MEASURED frame time the rule used for the current slice,
     * microseconds - the smoothed {@code Minecraft.getFrameTimeNs()}
     * reading, not the wall clock and not the vsync target.
     *
     * <p>The first thing to check when a budget number looks wrong: if
     * this does not match what the bench measures per frame, nothing
     * derived from it can be trusted, and the likely cause is the sample
     * clamp in {@link #beginSlice} or a frame the client spent in a
     * loading screen.</p>
     */
    public static long frameMicrosNow() {
        return sliceFrameNanos / 1000L;
    }

    /**
     * The share of the measured frame the rule granted for the current
     * slice, in PERMILLE - the rule's own output, checked against its own
     * input.
     *
     * <p>At the shipped dial point this reads 100 (a tenth) on any client
     * between about 2.5 ms and 20 ms a frame, less than 100 when the cap
     * binds (a slow client) and more than 100 when the floor binds (a
     * very fast one). It is the single number that says whether the wave
     * worked: the old rule's equivalent reading on the owner's machine
     * was 600 to 1,300.</p>
     */
    public static long budgetPermilleNow() {
        long frame = sliceFrameNanos;
        return frame <= 0L ? 0L : sliceBudgetNanos * 1000L / frame;
    }

    /**
     * <b>Phase 3, replacing {@code walkCreditMicrosNow}:</b> columns
     * handed to {@link PinWorker} and not yet walked. This is the
     * pacing rule's own input, and the number that says which side of
     * the pipeline is the bottleneck: parked at
     * {@value #MAX_WORKER_BACKLOG} means the WORKER is the limit and
     * more game-thread budget would buy nothing; hovering near zero
     * while columns are owed means the game thread is.
     */
    public static int workerBacklogNow() {
        return PinWorker.pendingCount();
    }

    /** Live captures frozen but not yet finished (strips owed). */
    public static int liveCaptureCount() {
        return liveCaptures == null ? 0 : liveCaptures.size() - liveCaptureHead;
    }

    /**
     * The worst single slice's total far-field cost this session,
     * microseconds - the tail figure, where {@code farExtractNanos}
     * divided by {@code farExtractSlices} is the mean one.
     */
    public static long worstSliceMicros() {
        return worstSliceNanos / 1000L;
    }

    /**
     * The worst single slice's OVERRUN this session, microseconds - the
     * largest amount by which one slice exceeded its own grant.
     *
     * <p>This is the honest price of an un-interruptible walk and it is
     * bounded by one walk, so it should land near the difference between
     * the worst walk and the average one (about 0.8 ms on the census
     * numbers: a 1.7 ms worst chunk against a ~0.9 ms mean). A value much
     * larger than one walk means something is spending the slice without
     * going through {@link #chargeSlice}.</p>
     */
    public static long worstOverrunMicros() {
        return worstOverrunNanos / 1000L;
    }

    /**
     * Chunk-lifecycle events (arrivals plus drops) the client saw in the
     * previous slice - the arrival rate the deleted keep-up term used to
     * turn into a claim on the frame. Published so the bench can tell a
     * scenario with no chunk traffic from one the far field is simply
     * failing to keep up with.
     */
    public static int chunkEventsLastSlice() {
        return eventsLastSlice;
    }

    /**
     * Clear the T2 peak gauges ({@link #worstSliceMicros()} and
     * {@link #worstOverrunMicros()}).
     *
     * <p>The LongAdders can be characterised per scenario by snapshotting
     * them before and after and subtracting; a running MAXIMUM cannot,
     * and a peak carried in from an earlier scenario would silently
     * become that scenario's answer. So the bench zeroes these at each
     * scenario boundary. Nothing in the mod itself calls this - a peak
     * the player never sees reset is the right behaviour for a debug
     * readout.</p>
     */
    public static void resetBudgetPeaks() {
        worstSliceNanos = 0L;
        worstOverrunNanos = 0L;
    }

    /**
     * The quality reason bits for the sides whose neighbour is NOT in
     * the client's cache right now - the ONE spelling of "which sides
     * are missing", shared by the completeness tests and the
     * extraction's quality capture so the two can never diverge.
     */
    private static byte missingSideBits(ChunkSource source, int x, int z) {
        byte q = 0;
        if (source.getChunk(x - 1, z, false) == null) {
            q |= QUALITY_SIDE_WEST;
        }
        if (source.getChunk(x + 1, z, false) == null) {
            q |= QUALITY_SIDE_EAST;
        }
        if (source.getChunk(x, z - 1, false) == null) {
            q |= QUALITY_SIDE_NORTH;
        }
        if (source.getChunk(x, z + 1, false) == null) {
            q |= QUALITY_SIDE_SOUTH;
        }
        return q;
    }

    /** All four lateral neighbours in the client's cache right now? */
    private static boolean neighborsLoaded(ChunkSource source, int x, int z) {
        return missingSideBits(source, x, z) == 0;
    }

    // ------------------------------------------------------------------
    // M4: the ONE scheduler - two ring-bucketed queues, one priority
    // rule (PIN > EDIT > FILL), one budget. The seams FILE; only the
    // pump SPENDS. Filing preserves the server's own nearest-first
    // order (O3) without a sort: entries land in the Chebyshev ring
    // bucket of their distance from the camera at filing time, the
    // drain pops the lowest non-empty ring FIFO, and an entry the
    // camera has since left more than two rings out of place is
    // re-filed instead of run. Buckets are never rebuilt on a camera
    // move - O(1) amortized per job, no per-frame sort.
    // ------------------------------------------------------------------

    /** Queue a FILL job (arrival, upgrade, rescue, census discovery). */
    private static void fileFill(ColumnRecord rec, long key) {
        if (rec.jobClass == JOB_EDIT) {
            return; // EDIT outranks FILL and re-walks everything anyway
        }
        rec.jobClass = JOB_FILL;
        fillQueue.file(rec, key, cameraRing(columnX(key), columnZ(key)));
    }

    /**
     * Queue an EDIT job (E4). The coalesce deadline is fixed at FILE
     * time - a coalesced edit counts {@link #farSaveEditsCoalesced} and
     * touches NOTHING else (no stamp refresh: a sliding stamp would let
     * any column edited more often than the window starve forever), and
     * the entry waits in the pending stage, invisible to the drain and
     * the backlog, until {@link #ripenEdits} moves it into the ring
     * queue.
     */
    private static void fileEdit(ColumnRecord rec, long key) {
        if (rec.jobClass == JOB_EDIT && rec.filedEdit) {
            farSaveEditsCoalesced.increment();
            return;
        }
        rec.jobClass = JOB_EDIT; // promote: a stale FILL entry dies on pop
        if (rec.filedEdit) {
            return;
        }
        rec.editStampMs = frameMillis;
        rec.filedEdit = true;
        if (editPendingKeys == null) {
            editPendingKeys = new LongArrayList(64);
            editPendingDue = new LongArrayList(64);
        }
        editPendingKeys.add(key);
        editPendingDue.add(frameMillis + EDIT_COALESCE_MILLIS);
    }

    /**
     * Move ripe edits from the pending stage into the EDIT ring queue.
     * Deadlines are monotone (file time + a constant), so the stage is
     * a plain FIFO and this stops at the first not-yet-due entry. The
     * ring is assigned HERE, at ripen time, which is even better for
     * nearest-first than filing time was. A pending entry whose record
     * moved on (promDemoted, went GONE, world reset) is dropped for one
     * map probe; its {@code filedEdit} flag was the pending membership,
     * so it clears here.
     */
    private static void ripenEdits() {
        LongArrayList keys = editPendingKeys;
        if (keys == null || editPendingHead >= keys.size()) {
            return;
        }
        LongArrayList due = editPendingDue;
        while (editPendingHead < keys.size()
                && due.getLong(editPendingHead) <= frameMillis) {
            long key = keys.getLong(editPendingHead++);
            ColumnRecord rec = columns == null ? null : columns.get(key);
            if (rec == null || !rec.filedEdit) {
                continue;
            }
            rec.filedEdit = false; // hand over from stage to queue
            if (rec.jobClass != JOB_EDIT || rec.state != STATE_LIVE_DIRTY) {
                continue; // moved on while coalescing
            }
            editQueue.file(rec, key, cameraRing(columnX(key), columnZ(key)));
        }
        if (editPendingHead >= keys.size()) {
            keys.clear();
            due.clear();
            editPendingHead = 0;
        } else if (editPendingHead > 256 && editPendingHead * 2 >= keys.size()) {
            keys.removeElements(0, editPendingHead);
            due.removeElements(0, editPendingHead);
            editPendingHead = 0;
        }
    }

    /** ONE spelling of camera distance; every consumer calls it. */
    private static int chebyshev(int x, int z, int camX, int camZ) {
        return Math.max(Math.abs(x - camX), Math.abs(z - camZ));
    }

    /** Chebyshev ring of (x, z) from the last slice's camera, clamped. */
    private static int cameraRing(int x, int z) {
        return Math.min(chebyshev(x, z, lastSliceCameraX, lastSliceCameraZ),
                RING_BUCKETS - 1);
    }

    /**
     * Live RUNNABLE entries across both queues - the scheduler's
     * backlog gauge. Edits still coalescing sit in the pending stage
     * and are not counted (see {@link #backlogged}).
     */
    public static int jobsQueued() {
        return editQueue.queued + fillQueue.queued;
    }

    /**
     * Edits still coalescing in the pending stage - the population the
     * runnable gauge deliberately hides. Game thread. Diagnostics: the
     * one number missing from leg (b)'s first failure message was this
     * one, and "dirty=true jobsQueued=0" is ambiguous without it.
     */
    public static int editsPending() {
        LongArrayList keys = editPendingKeys;
        return keys == null ? 0 : keys.size() - editPendingHead;
    }

    /**
     * G3: the store owns its own saturation predicate
     * ({@code FarField.writeQueueSaturated()} - a volatile read, no
     * monitor on this thread). While true the EDIT and FILL drains
     * pause, so producers pace themselves and the write queue's
     * eviction arm becomes unreachable in steady state.
     */
    private static boolean storeBacklogged() {
        return FarField.writeQueueSaturated();
    }

    /**
     * Drain one queue within its budget line - EDIT ignores the
     * stand-down (the player's own work outranks a rebuild storm), FILL
     * honours it. Each pop is validated against the record (a stale
     * entry - class moved on, column went clean or GONE - dies here for
     * one map probe); an entry more than two rings out of camera-place
     * is re-filed at its true ring instead of run. Ripeness never
     * appears here: the pending stage only hands over edits whose
     * coalesce deadline has passed. Bounded by the queue's size at
     * entry, so re-files cannot spin the loop.
     */
    private static void drainJobs(JobQueue q, ChunkSource source) {
        int budget = q.queued;
        if (budget <= 0) {
            return;
        }
        boolean edit = q.edit;
        while (budget-- > 0) {
            // Phase 3's pacing rule, both halves, before any work: the
            // WORKER must have room (part 1, self-limiting, no clock)
            // and this frame's grant must cover one more capture step
            // (part 2, the fraction of the frame Phase 2 established).
            if (!workerHasRoom()) {
                farExtractBudgetRefusals.increment();
                break;
            }
            if (edit ? !budgetRemains() : !budgetLeft()) {
                farExtractBudgetRefusals.increment();
                // Say WHICH refusal this was. A no-fit refusal is the
                // fit test doing its job on a frame whose grant is
                // genuinely spent; a refusal against an untouched grant
                // would mean the EMA has run away from reality. Only the
                // second is ever worth tuning, and separating them is
                // what lets a log say which.
                if (sliceSpentNanos > 0L) {
                    farBudgetNoFitRefusals.increment();
                }
                break;
            }
            long key = q.pop();
            if (key == Long.MIN_VALUE) {
                break;
            }
            q.queued--;
            ColumnRecord rec = columns == null ? null : columns.get(key);
            if (rec == null) {
                continue; // world changed under the entry; the map reset
            }
            q.setFiled(rec, false);
            if (rec.jobClass != q.jobClass || rec.state != STATE_LIVE_DIRTY
                    || rec.liveVersion <= rec.storedVersion) {
                continue; // stale entry: promoted, superseded, or clean
            }
            if (rec.writingVersion != 0) {
                continue; // I3: one write in flight; E8 re-files at ack
            }
            if (rec.pin != null) {
                // Phase 3: a capture of this column is frozen and waiting
                // for its strips (beginLiveCapture's ownership note). The
                // entry dies; drainLiveCaptures owns the column until it
                // finishes or is superseded.
                continue;
            }
            if (!rec.lightReady) {
                // G1: an unlit LIVE record is not runnable - a queued job
                // with a closed guard IS the deferral. The population is
                // exactly the pin self-heal path (a fast-flight capture's
                // column re-entering the cache through E4 before its
                // re-send's light publishes); E1/E3 - one seam - sets the
                // flag and re-files within the tick, so nothing can
                // starve here. The entry dies; the class stays on the
                // record for the re-file.
                continue;
            }
            int x = columnX(key);
            int z = columnZ(key);
            int trueRing = cameraRing(x, z);
            if (Math.abs(trueRing - q.poppedRing) > 2) {
                // The camera moved on; keep nearest-first without a sort.
                q.file(rec, key, trueRing);
                continue;
            }
            LevelChunk chunk = source.getChunk(x, z, false);
            if (chunk == null) {
                // The cache no longer answers for this column. While
                // captures are pending triage, a BETTER copy may be en
                // route and writing the column off here would make the
                // triage's idempotency skip data we still hold - the
                // entry dies quietly and the capture owns the column.
                // With NOTHING pending, this is the orphan the seventh
                // review scan warned about (leg (b)'s first real run
                // produced exactly it: dirty=true, jobsQueued=0,
                // nothing coming) - honest exit: behind, counted,
                // repaired on revisit (E1).
                if (leavingCount() == 0) {
                    leaveBehind(rec);
                }
                continue;
            }
            rec.jobClass = JOB_NONE; // the job is being spent
            beginLiveCapture(rec, chunk);
        }
    }

    /**
     * <b>Phase 3, phase 1 of a LIVE capture</b> - and the whole of what
     * the fill and edit classes now do on the game thread.
     *
     * <p>Freeze the column ({@link ColumnSnapshot}), grab its light
     * refs, register it in the pin graph so a neighbour's finish can
     * resolve to it, and clear vanilla's unsaved flag; then try to finish
     * it in the same pump, and park it on {@link #liveCaptures} if the
     * grant ran out between strips. What used to stand here was
     * {@code runExtraction}, which called
     * {@code ShellExtractor.extract(LevelChunk, boolean, int)} inline -
     * the 3-7 ms atom the bench measured.</p>
     *
     * <h2>What moved to capture time, by name</h2>
     * <ul>
     * <li><b>The version stamp (I3).</b> {@code writingVersion} is set
     *     in {@link #finishPin}, at the version the column had when it
     *     was frozen, because the walk is no longer here to set it and
     *     the record must be closed to a second job the moment the
     *     snapshot leaves.</li>
     * <li><b>The saved-marking.</b> {@code LevelChunk.tryMarkSaved()} is
     *     a call on the LIVE chunk, so it cannot ride the worker. It
     *     happens below, immediately after the freeze - a strictly
     *     SMALLER window than the old one (the flag used to be cleared
     *     after the walk, i.e. milliseconds later), and any edit that
     *     lands after it re-sets the flag through vanilla's own
     *     {@code markUnsaved} and re-dirties the record through E4.</li>
     * <li><b>The per-side quality facts.</b> Which sides are UNKNOWN is
     *     decided by the finish, from the pin graph and the live cache,
     *     on this thread; {@link PinWorker} folds them into the quality
     *     byte from the pin's own arrays. The old {@code missingSideBits}
     *     probe of the live cache at walk time has no successor because
     *     it asked the same question one moment later.</li>
     * <li><b>The light verdict does NOT move</b> - it is the walk's own
     *     output and travels back on {@code pin.walkLightPending}, which
     *     is where the captured path always put it.</li>
     * </ul>
     */
    private static void beginLiveCapture(ColumnRecord rec, LevelChunk chunk) {
        long start = System.nanoTime();
        Pin pin = Pin.captureLive(columnKey(chunk.getPos().x(), chunk.getPos().z()),
                chunk, rec.lightReady, Pin.Settings.snapshot());
        registerPin(pin);
        // THE VERSION IS STAMPED AT THE FREEZE, not at the finish, and
        // getting this wrong loses edits - which is worse than being
        // slow, so it is spelled out. A live capture's finish can be
        // DEFERRED to a later frame, and E4 can fire in between; if the
        // pin then took rec.liveVersion at finish time it would carry a
        // stamp NEWER than the content it froze, the ack would mark the
        // record clean at that version, and the edit would be on the
        // record as saved while the disk held the pre-edit shell -
        // silently, forever, until something else re-dirtied the column.
        // Stamped here, the ack settles at the frozen version, the
        // record is still dirty at the newer one, and E8 re-files.
        //
        // (The leaving path stamps at finish and is safe there for a
        // reason that does not transfer: its chunk is out of the cache,
        // so no client setBlockState can reach it and E1 releases the
        // pin outright.)
        pin.version = rec.liveVersion;
        // OWNERSHIP MOVES HERE, not at the finish, and the reason is the
        // deferral: a capture whose strips are owed sits on liveCaptures
        // for a frame or more with its record still LIVE_DIRTY and no
        // write in flight, which is exactly the shape the FILL drain and
        // the safety-net sweep both read as "this column needs a job".
        // Without the record pointing at its own capture they would file
        // one, freeze the column a second time, and leave the first
        // snapshot to be dismissed by the identity guard - correct, but a
        // ping-pong that wastes a freeze per frame. rec.pin is the ONE
        // fact that says "a capture already owns this column", and both
        // of those sites now check it.
        rec.pin = pin;
        liveCaptureGauge++;
        farCaptures.increment();
        // The E4 backstop's flag, cleared on the thread that owns it. Its
        // one consumer is chunk serialization and a ClientLevel is never
        // serialized, so the clear is free of side effects; without it
        // the sweep's isUnsaved() backstop would re-file every
        // once-edited column on every pass forever.
        if (chunk.getLevel().isClientSide()) {
            chunk.tryMarkSaved();
        }
        // One clock read, one charge. recordPinCaptureNanos is
        // deliberately NOT called: that histogram prices the LEAVING
        // seam's ref grab and leg (a) reads it against the E6 valves, so
        // folding a different population into it would make its
        // percentiles answer a question nobody asked. The live capture's
        // own distribution is captureCostStats().
        chargeCapture(Math.max(0L, System.nanoTime() - start));
        if (!finishPin(rec, pin)) {
            java.util.ArrayList<Pin> pending = liveCaptures;
            if (pending == null) {
                pending = new java.util.ArrayList<>(64);
                liveCaptures = pending;
            }
            pending.add(pin);
        }
    }

    /**
     * <b>Phase 3, phase 2:</b> resume the live captures whose finish ran
     * out of grant, before any new column is started. Oldest first, and
     * the whole drain stops at the first pin that defers again, so the
     * game thread never spends more than one strip past its line.
     *
     * <p>Runs BEFORE the EDIT and FILL drains for the reason the leaving
     * triage runs before them: a column already frozen is holding a copy
     * of the world and the cheapest thing to do with it is finish it. A
     * pin here whose record has moved on - superseded by a fresher
     * capture, or the column dropped and pinned by E5 - is dismissed by
     * the same identity guard the leaving triage uses.</p>
     */
    private static void drainLiveCaptures() {
        java.util.ArrayList<Pin> pending = liveCaptures;
        if (pending == null || liveCaptureHead >= pending.size()) {
            return;
        }
        while (liveCaptureHead < pending.size()) {
            Pin pin = pending.get(liveCaptureHead);
            ColumnRecord rec = columns == null ? null : columns.get(pin.key);
            if (rec != null && rec.pin == pin && !pin.released
                    && rec.state == STATE_LIVE_DIRTY && rec.writingVersion == 0
                    && pinIndex != null && pinIndex.get(pin.key) == pin) {
                if (!finishPin(rec, pin)) {
                    return; // grant spent mid-strip: resume next pump
                }
                liveCaptureHead++;
                continue;
            }
            // The record moved on under the capture: E5 pinned the
            // column, a write went in flight, a fresher capture
            // superseded this one, or the map reset. Whoever moved it
            // owns the column now and this snapshot is garbage. Release
            // through the ONE release site when the record still points
            // at this pin, so the gauge and the graph move together.
            if (rec != null && rec.pin == pin) {
                releasePin(rec);
            } else {
                dismissPin(pin);
            }
            liveCaptureHead++;
        }
        pending.clear();
        liveCaptureHead = 0;
    }

    /*
     * T2 Phase 3 DELETED runExtraction() and, with it, spend(),
     * extractAndSubmit() and ShellExtractor.extract(LevelChunk, boolean,
     * int) - the whole LIVE walk. Its four duties are now discharged
     * where each can honestly run:
     *
     *   missingSideBits(source, x, z)  -> finishPin's side resolution,
     *                                     folded into the quality byte by
     *                                     PinWorker from the pin's own
     *                                     sidePin/strips arrays;
     *   spend(chunk, version)          -> Pin.captureLive + PinWorker;
     *   ShellExtractor.lightPending()  -> pin.walkLightPending, the
     *                                     captured path's own verdict
     *                                     channel since M5;
     *   the version stamp and the      -> finishPin, at CAPTURE time
     *   empty-outcome inline stamp        (I3) and drainPinResults'
     *                                     OUTCOME_EMPTY arm.
     *
     * Deleting rather than keeping it dark is deliberate: a walk body
     * with no caller reads exactly like one that runs, and this file has
     * been burned by that before.
     */

    // ------------------------------------------------------------------
    // Seam 2: the unload backstop
    // ------------------------------------------------------------------

    /**
     * E5: {@code ClientLevel.unload(LevelChunk)} HEAD - block data is
     * intact at HEAD and unreachable through the chunk cache after
     * (dossier section 1.2), and the light is still fully live (the
     * forget packet's removal is queued behind this seam), so a DIRTY
     * column is CAPTURED here or it is behind.
     *
     * <p>Since M5 this seam never extracts, and since leg (a)'s first
     * real run it never FINISHES either - invariant I4 taken at its
     * word: a packet-time seam may only capture references. It grabs
     * exactly the two things that die with the packet - the chunk ref
     * and the light layer refs (both provably alive at this HEAD;
     * nothing else about the capture is time-critical) - and queues the
     * pin on the SAME triage list the E6 walk feeds. The finishing
     * (side graph, border strips, E9 admission, the record transition,
     * the worker handoff) happens at the pump, where the leave reserve
     * refills every frame and a strip can DEFER instead of being
     * skipped. The first cut finished pins here, per-side strips
     * against the seam's own leave line - and a whole forget storm
     * drains in ONE PacketProcessor pass against ONE frame's 2 ms
     * reserve, so leg (a)'s harness storm skipped 521 of 673 pins'
     * strip sides (the counters told the story exactly:
     * captureSkipped=521 with lightUncaptured=0 is impossible for the
     * E6 valves, whose light valve trips first and whose skip counts
     * are 256-quantized; only the per-side seam arm fits). The record
     * decides, one question, version arithmetic:</p>
     * <ol>
     *   <li><b>clean</b> (stored == live, whatever the quality): one map
     *       probe, GONE_SAVED, done. A degraded-but-clean column also
     *       leaves in one probe and is counted on
     *       {@link #farSaveDegradedLeft} so the design's upgrade-pin
     *       question keeps its number;</li>
     *   <li><b>dirty, or untracked</b> (the fast-flight population that
     *       used to race the light teardown): capture chunk + light
     *       refs, register in the pin graph, queue for triage. No
     *       record is created here (the triage creates it, the E6
     *       allocation rule); no reserve is spent here (nothing here
     *       costs more than refs).</li>
     * </ol>
     *
     * <p>An already-PINNED or GONE record no-ops - and a column already
     * CAPTURED (E5 or E6, still pending triage) is superseded by key:
     * {@link #registerPin} overwrites the graph entry, and the triage's
     * identity guard dismisses the stale capture, so whichever capture
     * is FRESHEST is the one that finishes. This is what makes the
     * radius-vs-forget packet ordering a non-question: forgets-first
     * pins every annulus column here, with the E6 walk then finding
     * nulls; radius-first captures the annulus at the E6 walk, with
     * these drops no-opping out-of-range - either order, every column
     * is captured exactly once, light alive.</p>
     */
    public static void onChunkDropping(LevelChunk chunk) {
        if (!armed) {
            return;
        }
        noteLevel(chunk);
        // Still the keep-up term's input: a drop is a chunk the client
        // has given up on, and the pumps after a burst of them fund the
        // strips its triage needs.
        eventsThisSlice++;
        long key = columnKey(chunk.getPos().x(), chunk.getPos().z());
        ColumnRecord rec = columns == null ? null : columns.get(key);
        if (rec != null && rec.state != STATE_LIVE_CLEAN
                && rec.state != STATE_LIVE_DIRTY) {
            return; // already pinned or gone: idempotent
        }
        if (rec != null && rec.liveVersion <= rec.storedVersion) {
            // Clean. One probe, and the honest label travels with it.
            if ((rec.quality & (QUALITY_WHOLE | QUALITY_EMPTY)) == 0) {
                farSaveDegradedLeft.increment();
            }
            settleGoneSaved(rec);
            return;
        }
        long startNanos = System.nanoTime();
        Pin pin = Pin.capture(key, chunk, rec != null && rec.lightReady, true,
                Pin.Settings.snapshot());
        registerPin(pin);
        recordPinCaptureNanos(System.nanoTime() - startNanos);
        java.util.ArrayList<Pin> pending = leavingPins;
        if (pending == null) {
            pending = new java.util.ArrayList<>(256);
            leavingPins = pending;
        }
        pending.add(pin);
    }

    /**
     * The on-demand rescue: the far field WANTS a column, the store
     * missed, and the client may still hold the real chunk. Since M4 it
     * is an event producer like every other seam - it FILES the job and
     * the scheduler runs it (nearest-first serves a wanted ring-edge
     * column within a pump), so the walker's read-result drain never
     * pays for a walk again.
     *
     * @return true when a shell for this column exists or is coming -
     *         the job is filed (or a write is in flight) and the caller
     *         must NOT write the column off; false when the honest
     *         answer is "the store is right to miss": the client does
     *         not hold the chunk, or the record is clean and stamped
     *         {@code QUALITY_EMPTY} (the column extracted to nothing -
     *         the fact that keeps an all-air column from becoming the
     *         pre8 re-read treadmill). A CLEAN, non-empty record whose
     *         read missed is the store overruling the record - a
     *         cleared cache, a lost region file - and the store is the
     *         truth (I2): the record is re-dirtied and the column
     *         re-files; the store accepts the redundant rewrite as an
     *         equal-tier overwrite.
     */
    public static boolean tryExtractLoaded(int chunkX, int chunkZ) {
        if (!armed) {
            return false;
        }
        try {
            long key = columnKey(chunkX, chunkZ);
            Minecraft minecraft = Minecraft.getInstance();
            ClientLevel level = minecraft == null ? null : minecraft.level;
            if (level == null) {
                return false;
            }
            // javap: ChunkSource.getChunk(int, int, boolean) - false never
            // loads and never returns the empty chunk.
            LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
            if (chunk == null) {
                return false; // not held; the store is right to miss
            }
            noteLevel(chunk);
            ColumnRecord rec = columns == null ? null : columns.get(key);
            if (rec != null && rec.liveVersion <= rec.storedVersion) {
                if ((rec.quality & QUALITY_EMPTY) != 0) {
                    return false; // stored-empty: an honest miss
                }
                // Clean but the store missed: the store wins. Re-dirty.
                rec.liveVersion = ++nextStamp;
            }
            if (rec == null) {
                rec = ensureRecord(key);
            } else if (rec.state == STATE_GONE_BEHIND) {
                repairBehind(rec); // held after all: repair in place
            } else if (rec.state == STATE_PINNED) {
                releasePin(rec); // held after all: the cache wins
            }
            rec.state = STATE_LIVE_DIRTY;
            if (rec.writingVersion == 0 && rec.jobClass == JOB_NONE
                    && !rec.filedFill && !rec.filedEdit) {
                // Completeness is deliberately NOT required here: the
                // blank ring must not wait for a neighbour the server may
                // never send. The shell is labeled degraded instead - the
                // same bargain the drop seam has always made.
                fileFill(rec, key);
                farExtractOnDemand.increment();
            }
            return true;
        } catch (Throwable t) {
            return false; // never let a rescue break the pump
        }
    }

    // ------------------------------------------------------------------
    // E7, the pump: pin results, acks, the E6 triage, the EDIT and FILL
    // drains, and the safety-net walk (the PIN drain itself is the worker's)
    // ------------------------------------------------------------------

    /**
     * Stand down, or resume ({@code FarFieldResidency}'s vanilla-reload
     * detection owns this switch). Since M4 a FILL pacing hint, not a
     * correctness actor: while standing down the FILL drain and the
     * safety-net walk pause; records still transition, jobs still file, the
     * EDIT drain still runs, the drop seam still captures on its
     * reserve, the E6 triage still finishes pins, and the worker drains
     * regardless (it is not on this thread) - which is what keeps a
     * ten-second stand-down from losing a render-distance change's
     * terrain twice.
     */
    public static void setStandDown(boolean value) {
        standDown = value;
    }

    /**
     * E7: one game-thread slice of far-field work, called once per
     * far-field pump from {@code FarFieldResidency} (client main thread,
     * outside the residency's store lock).
     *
     * <p>This is also where the slice for the whole frame is OPENED
     * ({@link #beginSlice}), so it is where the frame time is measured and
     * the budget sized; the drop seam spends what is left of that same
     * slice's leave line. The scheduler body, in the design's exact
     * order, all under {@link #sliceBudgetFor}'s ceiling plus the
     * leaving reserve plus one overshooting extraction, and never more
     * than {@link #MAX_SLICE_NANOS} plus that extraction:</p>
     * <ol>
     *   <li><b>pin results, then acks</b> ({@link #drainPinResults},
     *       {@link #drainWriteAcks} - E8): every decision this pump
     *       makes derives from records the worker and the store have
     *       already corrected;</li>
     *   <li><b>the E6 triage</b> - finishing captures into pins,
     *       through a stand-down, strips on the leave line
     *       (<i>leaving-chunks-never-lost</i>; the PIN class itself
     *       drains on the worker, off this budget entirely);</li>
     *   <li><b>EDIT</b> - through a stand-down too (the player's own
     *       edits outrank a rebuild storm), paused only by the store
     *       backpressure guard G3 (<i>edits-promptly</i>);</li>
     *   <li><b>FILL</b> - arrivals, upgrades, rescues, discoveries;
     *       honours the stand-down and G3, nearest-first;</li>
     *   <li><b>the safety-net walk</b> - probes only, files what it
     *       finds owed; throttled while vanilla has chunk work of its
     *       own ({@link #SWEEP_PROBES_PER_PUMP_BUSY}). (Its census role
     *       died at M6; the page folds the record map instead.)</li>
     * </ol>
     *
     * <p>{@code nearFieldBusy} comes from
     * {@code LevelRenderer.hasRenderedAllSections()}, whose whole body is
     * {@code sectionRenderDispatcher == null || dispatcher.isQueueEmpty()}
     * (javap ip 0-22). Never throws: the walker's pump has its own latch
     * and a far-field failure must not reach it through this door.</p>
     */
    public static void pumpGameThread(int centerChunkX, int centerChunkZ,
            int radiusChunks, boolean nearFieldBusy) {
        if (!armed) {
            return;
        }
        // E10: one pump to the next IS one vanilla light run (
        // ClientLevel.update runs pollLightUpdates + runLightUpdates once
        // per frame, before the extract phase this pump hangs off), so
        // advancing the sequence here retires the previous frame's
        // arrival stamps in one increment - no set, no sweep, no clock.
        lightRun++;
        try {
            // The store invalidated itself (a cache clear SUCCEEDED on
            // the IO thread): every storedVersion is now a lie and the
            // settings-flip reset applies - consumed here, on the game
            // thread, the same shape as the acks (I2: the store is the
            // only thing that may invalidate the records, and a FAILED
            // clear must not re-dirty a correct map, so the event posts
            // only on success).
            if (FarField.consumeStoreInvalidated()) {
                forgetExtractedColumns();
            }
            // M5: the worker's walk outcomes land BEFORE the acks, so a
            // pinned write's quality byte is on the record before its
            // own ack can apply (the worker posts the result before it
            // enqueues the write, and both queues drain here).
            drainPinResults();
            // E8 before everything else: the save design's E7 ordering.
            drainWriteAcks();
            beginSlice(frameNanos(), centerChunkX, centerChunkZ);
            // E6 triage: bounded record transitions over the capture
            // list, through a stand-down (these pins hold the only
            // copies left in the game); strips inside it pace on the
            // leave reserve. The PIN drain itself is the worker's now -
            // nothing on this thread walks a pinned chunk.
            drainLeavingCaptures();
            // Phase 3: resume any LIVE capture whose finish ran out of
            // grant last frame, before starting a new one. A frozen
            // column is holding a copy of the world; finishing it is
            // both cheaper and more useful than beginning another.
            drainLiveCaptures();
            // Phase 3: and OPEN THE STORE while anything is owed. This
            // used to happen inside the game thread's own submitShell,
            // which is deleted with the live walk; the worker's submit
            // deliberately never opens one. Without this a player who
            // arms Far Terrain mid-session would save nothing all
            // session - see FarField.ensureStoreOpenForCapture.
            if (jobsQueued() > 0 || leavingCount() > 0 || liveCaptureCount() > 0) {
                FarField.ensureStoreOpenForCapture();
            }
            ClientLevel level = Minecraft.getInstance() == null ? null
                    : Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
            ChunkSource source = level.getChunkSource();
            ripenEdits(); // pending -> runnable; deadlines are file-time
            boolean backpressure = storeBacklogged();
            if (!backpressure) {
                // EDIT runs through a stand-down; G3 is its only pause.
                drainJobs(editQueue, source);
            }
            if (standDown) {
                return;
            }
            if (!backpressure) {
                drainJobs(fillQueue, source);
            }
            sweepLoadedWindow(centerChunkX, centerChunkZ, radiusChunks,
                    nearFieldBusy ? SWEEP_PROBES_PER_PUMP_BUSY : SWEEP_PROBES_PER_PUMP);
        } catch (Throwable t) {
            // Same posture as tryExtractLoaded: the far field going quiet
            // is always preferable to breaking the residency pump. Counted
            // on the far field's OWN failure ledger, never on the wave-8
            // coverage-guard drop counters (landmine L4).
            FarField.farStoreErrors.increment();
        }
    }

    /**
     * What vanilla says the last frame cost, nanoseconds, or 0 when there
     * is no client to ask. <b>The single input to the T2 budget rule</b>,
     * and re-verified against the jar at that wave rather than inherited.
     *
     * <p>javap, 26.2 merged jar, this authoring session:
     * {@code net.minecraft.client.Minecraft: public long getFrameTimeNs();}
     * whose whole body is {@code aload_0 / getfield frameTimeNs:J /
     * lreturn} - a bare field read, no allocation, no lock. The field is
     * assigned exactly once per frame inside {@code renderFrame}:
     * {@code Util.getNanos()} at <b>ip 626</b> and
     * {@code putfield frameTimeNs} at <b>ip 631</b>.</p>
     *
     * <p><b>The ordering is the part that matters and it was re-read, not
     * assumed.</b> That assignment precedes the {@code "swapBuffers"}
     * profiler section (ip 647) and
     * {@code FramerateLimiter.limitDisplayFPS} (ip 767), so the reading
     * is the frame's CPU SPAN and excludes both the present and the
     * framerate-limiter sleep. That is what makes it a MEASURED frame
     * time rather than a vsync target: a client pinned at 60 by vsync
     * while capable of 300 reports the ~3 ms it really spent, and the
     * fraction rule takes a tenth of that rather than a tenth of the
     * 16.7 ms interval it was idling through. See {@link #frameNanosEma}
     * for the smoothing and for the feedback-loop arithmetic.</p>
     */
    private static long frameNanos() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? 0L : minecraft.getFrameTimeNs();
    }

    // ------------------------------------------------------------------
    // M5: the pin lifecycle (game-thread half). The worker's half is
    // PinWorker; the boundary is exactly two ConcurrentLinkedQueues.
    // ------------------------------------------------------------------

    /** Enter a fresh capture into the pin graph (phase 1 / E5 seam). */
    private static void registerPin(Pin pin) {
        Long2ObjectOpenHashMap<Pin> index = pinIndex;
        if (index == null) {
            index = new Long2ObjectOpenHashMap<>(256);
            pinIndex = index;
        }
        index.put(pin.key, pin);
    }

    /**
     * THE one pin-release writer: the index entry, the record's ref, the
     * gauge and the release counter move together (the GONE-helper
     * lesson, applied before two hand-spelled copies can diverge). The
     * released flag tells a worker still holding a queue entry for this
     * pin to skip it; a walk already past the flag is harmless by
     * version ordering at E8.
     */
    private static void releasePin(ColumnRecord rec) {
        detachPin(rec, true);
    }

    /**
     * The one detach BODY. {@link #releasePin} is the ordinary entry
     * point; the second one exists for a case Phase 3 introduced and is
     * worth naming, because it is the only way two pins can ever want the
     * same record.
     *
     * <p><b>The supersede case.</b> A column with a LIVE capture in
     * flight can be dropped - the client gives it up while our snapshot
     * of it is still with the worker - and E5 then builds a LEAVING pin
     * for the same key. {@code registerPin} has already overwritten the
     * graph entry with the fresh pin by the time the finish runs, so the
     * stale pin must be detached WITHOUT touching the index: removing by
     * key there would evict the successor from the graph and every
     * neighbour resolving through it would silently fall back to a strip
     * or to UNKNOWN.</p>
     *
     * @param dropIndexEntry false when a FRESHER pin already owns this
     *                       key in {@link #pinIndex}
     */
    private static void detachPin(ColumnRecord rec, boolean dropIndexEntry) {
        Pin pin = rec.pin;
        if (pin == null) {
            return;
        }
        pin.released = true;
        rec.pin = null;
        if (dropIndexEntry && pinIndex != null) {
            pinIndex.remove(pin.key);
        }
        // Phase 3: two gauges, one release site. A LIVE capture is not a
        // pin in the Layer 1 sense (nothing is leaving, no unique terrain
        // is held), and folding it into pinnedGauge would make the page's
        // "leaving" line and the residency's sweep-hold test read every
        // ordinary fill as an evacuation.
        if (pin.live) {
            liveCaptureGauge--;
        } else {
            pinnedGauge--;
        }
        farSavePinReleases.increment();
    }

    /**
     * Phase 3: a record leaving the LIVE states takes its live capture
     * with it.
     *
     * <p>Before Phase 3 {@code rec.pin != null} implied
     * {@code state == PINNED}, so the two GONE writers below could not
     * possibly be holding one. Widening {@code rec.pin} to mean "a
     * capture owns this column" broke that implication, and every
     * argument that a particular GONE path cannot be reached with a live
     * capture attached is now a chain of three or four inferences about
     * ack ordering. Those chains are how a gauge leak or a stuck
     * {@code rec.pin} gets in. This costs one null check at two sites
     * and makes the rule structural instead: <b>a record that is no
     * longer LIVE does not hold a live capture.</b> A LEAVING pin is
     * deliberately untouched here - its whole purpose is to outlive the
     * record's transition to GONE.</p>
     */
    private static void releaseLiveCapture(ColumnRecord rec) {
        Pin pin = rec.pin;
        if (pin == null || !pin.live) {
            return;
        }
        if (rec.writingVersion == pin.version) {
            // Its walk or its write is still owed, and the ack path owns
            // the release (E8, both arms). Letting go here would look
            // tidy and would STRAND writingVersion: a released pin is
            // skipped at dequeue and posts no result, so no ack would
            // ever clear the stamp, and I3 would then block that column
            // from ever being saved again for the rest of the session.
            // Never release a capture that something is still waiting on.
            return;
        }
        // What is left is the frozen-but-unfinished capture: on the
        // deferral list, no write in flight, and its column has just
        // gone GONE under it. Nobody else is coming for it.
        releasePin(rec);
    }

    /**
     * Finish one capture: resolve the four sides and four corners
     * through the pin graph (direct refs - the seams registered every
     * sibling before any finish runs, so an interior pin's whole plus
     * resolves even though its neighbours' own finish has not run),
     * capture border strips, lateral light and neighbour biomes for the
     * stayed-live sides, run the E9 admission backstop for a LEAVING
     * pin, transition the record and hand the pin to the worker.
     *
     * <p><b>Two callers, one body</b> - {@link #drainLeavingCaptures}
     * for a dropped column and {@link #drainLiveCaptures} for a live
     * one - and the source is read at exactly two points below, both
     * marked: which budget line funds the strips, and which record
     * transition the finish makes. Everything else is the same work on
     * the same shapes, which is the point of "one Pin, two sources".</p>
     *
     * <p><b>The line is tested between strips, and progress is
     * monotone.</b> A strip the line cannot fund right now DEFERS the
     * pin, and {@link Pin#finishSide} means the retry resumes at the
     * side it stopped on rather than redoing the ones already captured.
     * Never a skip, never a degraded side from a time budget - the
     * seam-time per-side skip arm that used to live here is the producer
     * that failed leg (a)'s storm (521 of 673 pins' sides against one
     * frame's 2 ms line). One strip is therefore the largest
     * uninterruptible far-field step the game thread can take, which is
     * the property this whole wave exists to establish.</p>
     *
     * @return false when the line is spent mid-strip - retry next pump
     */
    private static boolean finishPin(ColumnRecord rec, Pin pin) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        ChunkSource source = level == null ? null : level.getChunkSource();
        int resumedAt = pin.finishSide;
        // Sides: pin graph first, then the live cache (strip), else
        // UNKNOWN (the quality reason bit, set by the worker).
        for (int side = pin.finishSide; side < 4; side++) {
            long neighborKey = columnKey(pin.chunkX + ShellExtractor.SIDE_DX[side],
                    pin.chunkZ + ShellExtractor.SIDE_DZ[side]);
            Pin neighborPin = pinIndex == null ? null : pinIndex.get(neighborKey);
            if (neighborPin != null && neighborPin != pin) {
                pin.sidePin[side] = neighborPin;
                pin.finishSide = side + 1;
                continue;
            }
            LevelChunk live = source == null ? null
                    : source.getChunk(pin.chunkX + ShellExtractor.SIDE_DX[side],
                            pin.chunkZ + ShellExtractor.SIDE_DZ[side], false);
            if (live == null) {
                pin.finishSide = side + 1;
                continue; // UNKNOWN side: the old absent-neighbour policy
            }
            // SOURCE DIFFERENCE 1 of 2: which line funds this strip. A
            // leaving pin spends the LEAVE RESERVE, which sits above the
            // fill line so a sweep cannot starve terrain that is about to
            // be gone forever. A live capture spends the FILL line that
            // admitted it, because nothing about it is urgent - the
            // client still holds the column and next frame is fine.
            //
            // The FIRST strip of any one pump is allowed through
            // unconditionally, which is what makes deferral terminate: a
            // grant already spent by an earlier capture would otherwise
            // refuse this pin every pump forever. With it, a pin finishes
            // in at most four pumps whatever the clock says, and the
            // overshoot that buys is one strip.
            boolean funded = side == resumedAt
                    || (pin.live ? budgetRemains() : leaveBudgetLeft());
            if (!funded) {
                farCaptureDeferrals.increment();
                return false;
            }
            long start = System.nanoTime();
            pin.strips[side] = ShellExtractor.captureStrip(pin.chunk, live,
                    side, pin.bandOffset);
            ColumnRecord neighborRec = columns == null ? null
                    : columns.get(neighborKey);
            pin.captureSideLight(side, live,
                    neighborRec != null && neighborRec.lightReady);
            // Phase 3: and its BIOMES, as frozen refs - the tint half of
            // the plane, which costs 24 pointer reads and is what keeps a
            // captured column's stored colour identical to the near
            // field's across the seam. See Pin.sideTint.
            pin.captureSideTint(side, live);
            pin.finishSide = side + 1;
            // Strips are capture-class work: charge the one odometer, so
            // a drop burst paces its strips across frames instead of
            // stacking them into one.
            chargeCapture(Math.max(0L, System.nanoTime() - start));
        }
        for (int corner = 0; corner < 4; corner++) {
            long cornerKey = columnKey(pin.chunkX + ShellExtractor.CORNER_DX[corner],
                    pin.chunkZ + ShellExtractor.CORNER_DZ[corner]);
            Pin cornerPin = pinIndex == null ? null : pinIndex.get(cornerKey);
            if (cornerPin != null && cornerPin != pin) {
                pin.diagPin[corner] = cornerPin;
                continue;
            }
            // A stayed-live diagonal is still NOT captured for GEOMETRY:
            // it is a flood conduit only, and the flood treats a missing
            // conduit exactly like an unloaded neighbour. Phase 3 does
            // take its biomes, because the tint box average at Biome
            // Blend 2 reaches the corner 2x2 of the pad and edge repeat
            // there is a visible corner artefact where two edge-repeat
            // bands meet. Refs only, no clock test: four times 24 pointer
            // reads is not a budget event.
            LevelChunk liveCorner = source == null ? null
                    : source.getChunk(pin.chunkX + ShellExtractor.CORNER_DX[corner],
                            pin.chunkZ + ShellExtractor.CORNER_DZ[corner], false);
            if (liveCorner != null) {
                pin.captureDiagTint(corner, liveCorner);
            }
        }
        if (!pin.live) {
            // A LIVE pin stamped itself at its freeze - see
            // beginLiveCapture. A leaving pin stamps here, and may: no
            // client write can reach an uncached chunk, so its
            // liveVersion cannot have moved since the capture.
            pin.version = rec.liveVersion;
        }
        if (rec.pin != pin) {
            // A LEAVING pin taking over a column whose LIVE capture is
            // still with the worker: the client dropped it mid-flight.
            // Detach the stale one WITHOUT touching the index - the
            // fresh pin already owns that key (registerPin overwrote it
            // at the seam) and removing by key here would evict the
            // successor from the graph. Without this the live capture's
            // gauge never comes back and rec.pin is silently overwritten,
            // which is the one-writer rule this file keeps re-learning.
            detachPin(rec, false);
        }
        rec.pin = pin; // a live capture already set this at its freeze
        rec.jobClass = JOB_NONE; // the worker's queue is its only queue
        // SOURCE DIFFERENCE 2 of 2: the record transition.
        if (pin.live) {
            // The column is STILL LIVE, so it stays LIVE_DIRTY and its
            // write is stamped HERE rather than after a walk - I3 moves
            // from walk time to CAPTURE time, which is the state-machine
            // half of this wave. Everything downstream already reads that
            // stamp the way it needs to: drainJobs skips a record with a
            // write in flight, E4 bumps liveVersion under it, and E8
            // re-files the job at the ack if new dirt landed while the
            // worker was walking. A mid-walk edit therefore costs one
            // re-capture AFTER the ack and never a second capture in
            // flight.
            rec.writingVersion = pin.version;
            PinWorker.enqueue(pin);
            return true;
        }
        rec.state = STATE_PINNED;
        pinnedGauge++;
        farSavePins.increment();
        admitHeapBackstop();
        java.util.ArrayDeque<Pin> admitted = admittedPins;
        if (admitted == null) {
            admitted = new java.util.ArrayDeque<>(64);
            admittedPins = admitted;
        }
        admitted.addLast(pin);
        // Prune settled heads NOW, not just at E9: settles are roughly
        // FIFO (the worker drains oldest-first), so without this the
        // deque would keep strong refs - and through them whole
        // LevelChunks - for every pin the session ever admitted.
        while (!admitted.isEmpty() && admitted.peekFirst().released) {
            admitted.pollFirst();
        }
        if (pinnedGauge > PIN_HARD_CAP) {
            // G2's tripwire: counted, asserted in dev, REFUSING nothing
            // (the design forbids a count cap; E9 is the only discard).
            farSavePinTripwire.increment();
            assert false : "pin population past PIN_HARD_CAP: settles are leaking";
        }
        if (rec.writingVersion == 0) {
            rec.writingVersion = pin.version;
            PinWorker.enqueue(pin);
        }
        // else: I3 - a live write is in flight; its ack hands the pin to
        // the worker (drainWriteAcks' PINNED arms).
        return true;
    }

    /**
     * E9, the heap backstop - the single sanctioned discard in the
     * pipeline, checked at pin admission. Reclaimable headroom under a
     * tenth of the max heap, CONFIRMED across
     * {@link #E9_CONFIRM_NANOS} (freeMemory cannot see collectable
     * garbage, and a storm makes plenty; the JVM's own full-GC-before-
     * OOM is the design's "GC hint" - an explicit System.gc() on the
     * game thread would be a stall pretending to be a safety), releases
     * the OLDEST pins: GONE_BEHIND on the gauge, {@link #farSaveLost}
     * per the design's E9 row, refs freed, all counted where the owner
     * reads them.
     */
    private static void admitHeapBackstop() {
        Runtime runtime = Runtime.getRuntime();
        long free = runtime.maxMemory() - runtime.totalMemory()
                + runtime.freeMemory();
        if (free >= runtime.maxMemory() / E9_HEAP_FLOOR_DIVISOR) {
            e9BreachSinceNanos = Long.MIN_VALUE;
            return;
        }
        long now = System.nanoTime();
        if (e9BreachSinceNanos == Long.MIN_VALUE) {
            e9BreachSinceNanos = now;
            return;
        }
        if (now - e9BreachSinceNanos < E9_CONFIRM_NANOS) {
            return;
        }
        java.util.ArrayDeque<Pin> admitted = admittedPins;
        if (admitted == null) {
            return;
        }
        int released = 0;
        while (released < E9_RELEASE_BATCH && !admitted.isEmpty()) {
            Pin oldest = admitted.pollFirst();
            ColumnRecord rec = columns == null ? null : columns.get(oldest.key);
            if (rec == null || rec.pin != oldest) {
                continue; // already settled; the deque prunes lazily
            }
            releasePin(rec);
            leaveBehind(rec);
            farSaveLost.increment();
            released++;
        }
    }

    /**
     * M5: apply the worker's walk outcomes (the pump's first drain).
     * Only the record's OWN pin applies - a superseded pin's outcome
     * (E1 released it mid-walk) is dropped here and its write's ack
     * settles or goes foreign by version ordering, exactly like every
     * other stale messenger in this file.
     *
     * <p><b>Phase 3</b> gives the two non-SUBMITTED outcomes a LIVE arm.
     * The difference is one fact: a live capture's column is still in
     * the client's cache, so "the walk produced nothing useful" is
     * repairable by walking it again, where for a pinned column it is
     * the end of that terrain. GONE_BEHIND therefore stays a LEAVING
     * outcome and a live failure simply re-files its job - which is also
     * what {@code drainWriteAcks} does for a failed WRITE, so the two
     * failure channels agree.</p>
     */
    private static void drainPinResults() {
        for (Pin pin; (pin = PinWorker.pollResult()) != null;) {
            ColumnRecord rec = columns == null ? null : columns.get(pin.key);
            if (rec == null || rec.pin != pin) {
                continue;
            }
            switch (pin.walkOutcome) {
                case PinWorker.OUTCOME_SUBMITTED ->
                    // The ack still travels; only the quality byte lands
                    // early (drainWriteAcks applies it at the ack).
                    rec.writingQuality = pin.walkQuality;
                case PinWorker.OUTCOME_EMPTY -> {
                    // All air or below the band: the store legitimately
                    // holds nothing and no ack is coming - stamped stored
                    // inline, the rule the deleted runExtraction applied
                    // on the game thread.
                    rec.storedVersion = Math.max(rec.storedVersion, pin.version);
                    rec.quality = (byte) (pin.walkQuality | QUALITY_EMPTY);
                    rec.writingVersion = 0;
                    if (rec.state == STATE_PINNED
                            && rec.liveVersion <= rec.storedVersion) {
                        settleGoneSaved(rec);
                    } else if (rec.state == STATE_LIVE_DIRTY) {
                        // A live column that extracted to nothing: clean
                        // if no dirt landed while the worker had it, and
                        // otherwise re-filed at the fresher version. The
                        // stored-EMPTY quality is what keeps this column
                        // from becoming a re-read treadmill later
                        // (tryExtractLoaded reads it).
                        if (rec.liveVersion <= rec.storedVersion) {
                            rec.state = STATE_LIVE_CLEAN;
                            rec.jobClass = JOB_NONE;
                        } else {
                            refileJob(rec, pin.key);
                        }
                    }
                    releasePin(rec);
                }
                case PinWorker.OUTCOME_FAILED -> {
                    // The walk threw, or the store is dead (both already
                    // on farStoreErrors). For a PINNED column the honest
                    // exit is the repairable loss state, never a silent
                    // retry loop against a crashing extractor; for a LIVE
                    // one the chunk is still there, so the job re-files
                    // and the next capture tries again - the same
                    // treatment a failed write gets at E8.
                    rec.writingVersion = 0;
                    if (rec.state == STATE_PINNED) {
                        leaveBehind(rec);
                    } else if (rec.state == STATE_LIVE_DIRTY) {
                        refileJob(rec, pin.key);
                    }
                    releasePin(rec);
                }
                default -> throw new IllegalStateException(
                        "unknown pin outcome " + pin.walkOutcome);
            }
        }
    }

    /**
     * M5, the LIVE LightView's per-side source test: has this column's
     * light been published this session? Package-private for
     * {@code ShellExtractor}'s wrapper; game thread only (the record
     * map's confinement). A recordless column answers false - for a
     * resident chunk that is the mid-session-arming transient (the
     * census discovers it within a pass; flat over fabricated until
     * then), never the steady state, because E1 records every arrival
     * while armed.
     */
    static boolean lateralLightReady(int x, int z) {
        ColumnRecord rec = columns == null ? null : columns.get(columnKey(x, z));
        return rec != null && rec.lightReady;
    }

    /**
     * Per-pin phase-1 capture cost, log2-bucketed - added after leg
     * (a)'s first real run, whose failure had to be diagnosed by
     * counter arithmetic because nothing measured the capture itself
     * (the skip counter's producer turned out to be the deleted
     * seam-time strip arm, not a slow walk). Every {@code Pin.capture}
     * call at both seams records here, so the next anomaly names its
     * cost directly: the leg carries {@link #pinCaptureNanosSummary}
     * in its failure messages, and the rd-120 arithmetic can be
     * checked against the MEASURED per-pin figure (59,488 x p95 must
     * sit inside the E6 valves). Game thread only, session-cumulative
     * like the counters; two nanoTime reads per pin, noise next to
     * the ~52 layer lookups they bracket.
     */
    private static final int[] pinCaptureNanosBuckets = new int[40];
    /** Highest single capture seen, nanoseconds. */
    private static long pinCaptureMaxNanos;
    /** Total captures recorded. */
    private static long pinCaptureSamples;

    private static void recordPinCaptureNanos(long nanos) {
        long clamped = Math.max(1L, nanos);
        int bucket = Math.min(pinCaptureNanosBuckets.length - 1,
                64 - Long.numberOfLeadingZeros(clamped));
        pinCaptureNanosBuckets[bucket]++;
        pinCaptureSamples++;
        if (nanos > pinCaptureMaxNanos) {
            pinCaptureMaxNanos = nanos;
        }
    }

    /**
     * Approximate percentiles of per-pin capture cost (log2 bucket
     * upper bounds - within 2x, which is the resolution a cost-model
     * argument needs). Game thread; test/diagnostic surface.
     */
    public static String pinCaptureNanosSummary() {
        long n = pinCaptureSamples;
        if (n == 0) {
            return "no pins captured";
        }
        return "n=" + n
                + " p50<=" + formatNanos(captureRankUpperBound(n / 2 + 1))
                + " p95<=" + formatNanos(captureRankUpperBound((n * 95 + 99) / 100))
                + " max=" + formatNanos(pinCaptureMaxNanos);
    }

    private static long captureRankUpperBound(long rank) {
        long seen = 0;
        for (int i = 0; i < pinCaptureNanosBuckets.length; i++) {
            seen += pinCaptureNanosBuckets[i];
            if (seen >= rank) {
                return 1L << i; // the bucket's upper bound
            }
        }
        return pinCaptureMaxNanos;
    }

    private static String formatNanos(long nanos) {
        if (nanos >= 1_000_000L) {
            return (nanos / 1_000_000L) + "ms";
        }
        if (nanos >= 1_000L) {
            return (nanos / 1_000L) + "us";
        }
        return nanos + "ns";
    }

    // ------------------------------------------------------------------
    // Phase 1 of the performance plan (docs/FARFIELD-PERF-BRIEF.md):
    // the three distributions its claims stand or fall on
    //
    // The brief's central numbers are DISTRIBUTIONS and this class
    // published only totals. farExtractNanos over farExtracts is a
    // MEAN, and a mean cannot tell "every column costs 1.2 ms" from
    // "most cost 0.3 ms and the ocean ones cost six". Three of the
    // brief's claims are checkable here and nowhere else:
    //
    //  1. "one LIVE walk costs ~1.15-2 ms today; one capture would cost
    //     ~40 us, worst ~110" - captureCostStats() is the AFTER number
    //     and a Phase 2 log's walk_p50 is the BEFORE one
    //     Phase 3 will be judged against, and it has to be recorded
    //     before the fix or the comparison is retrospective arithmetic;
    //  2. "the budget is tested before a column and never during one,
    //     so a slice overshoots by up to one whole extraction - mean
    //     +0.5-1 ms, worst +4 ms" - sliceOverrunStats() measures the
    //     overshoot directly, per slice, instead of inferring it from a
    //     ratio of totals;
    //  3. "the idle boost targets a 25 ms frame and takes half the
    //     headroom it finds, so a standing player pays 6.7 ms" -
    //     sliceBudgetStats() is the ceiling the rule ACTUALLY chose,
    //     frame by frame; its p95 beside farExtractIdleBoosts is the
    //     whole claim.
    //
    // ONE SLICE IS ONE FRAME. The pump hangs off the render thread's
    // frame hook (the residency pump calls FarFieldResidency.pump(),
    // which calls pumpGameThread, once per rendered frame), so a
    // per-slice histogram is a per-frame histogram of what the far
    // field took from the game thread - which is the unit the brief's
    // target ("10% of a frame, capped at 2 ms") is stated in.
    //
    // COST DISCIPLINE. Everything below is behind PERF_STATS, a
    // static final resolved at class load, so on every run that does
    // not ask for it these sites are "if (false)" after JIT - the
    // MesheliumCpuStages shape, and the only reason a measurement this
    // fine-grained may sit on the game thread at all. Armed, it is four
    // array increments and three comparisons per frame plus one per
    // extraction, against work measured in milliseconds.
    // ------------------------------------------------------------------

    /**
     * {@code meshelium.farfield.perfStats}: arm the Phase 1
     * distributions. The harness sets it from
     * {@code -Pmeshelium.farbench}; nothing else does, and a played
     * session never pays for it.
     */
    public static final boolean PERF_STATS =
            Boolean.getBoolean("meshelium.farfield.perfStats");

    /**
     * A distribution, as the far-armed bench prints it.
     *
     * <p>The percentiles are BUCKET UPPER BOUNDS, clamped to the observed
     * maximum: eight sub-buckets per octave, so a percentile overstates
     * by at most 12.5%. <b>The first shakedown run is why they are not
     * plain octaves.</b> At one bucket per octave a 40-sample walk
     * distribution reported p50 = p99 = 4.194 ms - both of them the
     * 2^22 ns bound and neither of them a measurement - and a 17-sample
     * slice distribution reported a p95 of 8.389 ms against its own EXACT
     * max of 5.361 ms, which is a statistic no reader should ever be
     * shown. Worse, 2^24 ns is 16.777 ms, close enough to
     * {@link #MAX_SLICE_NANOS} to be misread as the stall guarantee
     * binding when it was only the bucket. Sub-buckets fix the
     * resolution; the clamp fixes the p95-above-max.</p>
     *
     * <p>{@code minNanos}, {@code maxNanos} and {@code totalNanos} are
     * EXACT. Total over the scenario's wall clock is the duty cycle;
     * total over its frame count is the per-frame cost the brief's target
     * is written against.</p>
     */
    public record PerfStats(long samples, long minNanos, long p50Nanos,
            long p95Nanos, long p99Nanos, long maxNanos, long totalNanos) {
    }

    /**
     * Sub-buckets per octave, as a shift: 3 gives 8 of them, i.e. a
     * worst-case 12.5% over-estimate on any percentile. The cost over
     * plain octaves is one shift and one mask per record, and 320 ints
     * per histogram instead of 40.
     */
    private static final int PERF_SUB_BITS = 3;
    private static final int PERF_SUB = 1 << PERF_SUB_BITS;
    /** Octaves covered: 2^40 ns is eighteen minutes, past any real slice. */
    private static final int PERF_OCTAVES = 40;
    private static final int PERF_BUCKETS = PERF_OCTAVES * PERF_SUB;

    /**
     * One armed distribution. Game thread only, like every gauge in
     * this class that is not a {@link LongAdder}: the recorders run
     * inside {@link #chargeCapture} and {@link #openSlice}, and the
     * readers are
     * the bench's {@code computeOnClient}.
     */
    private static final class PerfHistogram {
        private final int[] buckets = new int[PERF_BUCKETS];
        private long samples;
        private long min = Long.MAX_VALUE;
        private long max;
        private long total;

        void record(long nanos) {
            long value = Math.max(1L, nanos);
            int exp = 63 - Long.numberOfLeadingZeros(value);
            // The top PERF_SUB_BITS bits BELOW the leading one, which is
            // the mantissa at this resolution.
            int sub = exp >= PERF_SUB_BITS
                    ? (int) ((value >>> (exp - PERF_SUB_BITS)) & (PERF_SUB - 1))
                    : (int) ((value << (PERF_SUB_BITS - exp)) & (PERF_SUB - 1));
            buckets[Math.min(PERF_BUCKETS - 1, exp * PERF_SUB + sub)]++;
            samples++;
            total += nanos;
            if (nanos < min) {
                min = nanos;
            }
            if (nanos > max) {
                max = nanos;
            }
        }

        void reset() {
            Arrays.fill(buckets, 0);
            samples = 0;
            min = Long.MAX_VALUE;
            max = 0;
            total = 0;
        }

        PerfStats stats() {
            if (samples == 0) {
                return new PerfStats(0L, 0L, 0L, 0L, 0L, 0L, 0L);
            }
            return new PerfStats(samples, min, rankUpperBound(samples / 2 + 1),
                    rankUpperBound((samples * 95 + 99) / 100),
                    rankUpperBound((samples * 99 + 99) / 100), max, total);
        }

        /**
         * The upper bound of the bucket the given rank falls in, CLAMPED
         * to the exact maximum. Without the clamp a percentile can be
         * reported above the largest value ever recorded, which is how
         * the first shakedown printed a p95 of 8.389 ms next to a max of
         * 5.361 ms.
         */
        private long rankUpperBound(long rank) {
            long seen = 0;
            for (int i = 0; i < buckets.length; i++) {
                seen += buckets[i];
                if (seen >= rank) {
                    return Math.min(max, bucketUpperBound(i));
                }
            }
            return max;
        }

        private static long bucketUpperBound(int index) {
            int exp = index / PERF_SUB;
            int sub = index % PERF_SUB;
            return (((long) (PERF_SUB + sub + 1)) << exp) >>> PERF_SUB_BITS;
        }
    }

    /**
     * Per-CAPTURE-STEP game-thread cost. <b>Phase 3 re-pointed this
     * histogram</b>: it recorded whole walks (the bench's "before"
     * number, 6.8 ms p50 over fresh ground) and now records the
     * game-thread step that replaced them. The walk still has a number -
     * {@code farExtractNanos / farExtracts} - but it is produced on
     * {@link PinWorker} and this class's histograms are game-thread-only
     * structures, so it is not folded in here.
     */
    private static final PerfHistogram captureCostHistogram = new PerfHistogram();
    /** Per-slice game-thread spend, i.e. per FRAME (see the block above). */
    private static final PerfHistogram sliceSpendHistogram = new PerfHistogram();
    /** Per-slice spend BEYOND the slice's own ceiling: the overshoot. */
    private static final PerfHistogram sliceOverrunHistogram = new PerfHistogram();
    /** The ceiling {@link #sliceBudgetFor} chose, per slice. */
    private static final PerfHistogram sliceBudgetHistogram = new PerfHistogram();

    /**
     * {@link #PERF_STATS}: the per-column GAME-THREAD cost, nanoseconds.
     *
     * <p><b>Renamed from {@code extractCostStats} at Phase 3, because
     * what it measures changed.</b> It recorded the whole walk, which
     * the far-armed bench measured at p50 6.816 ms / p99 20.972 ms over
     * fresh ground; it now records one capture step, which is the only
     * far-field work the game thread still does per column. The rename
     * is the loud part: a reader comparing a Phase 2 log's
     * {@code walk_p50} against a Phase 3 log's would otherwise be
     * comparing two different quantities and calling the difference a
     * speedup.</p>
     */
    public static PerfStats captureCostStats() {
        return captureCostHistogram.stats();
    }

    /** {@link #PERF_STATS}: game-thread nanoseconds spent per frame. */
    public static PerfStats sliceSpendStats() {
        return sliceSpendHistogram.stats();
    }

    /**
     * {@link #PERF_STATS}: how far past its own ceiling a slice ran,
     * nanoseconds, counted only for the slices that overshot at all.
     * {@code samples} against {@link #farExtractSlices} is how OFTEN it
     * happens; {@code p95Nanos} and {@code maxNanos} are how much it
     * costs when it does.
     */
    public static PerfStats sliceOverrunStats() {
        return sliceOverrunHistogram.stats();
    }

    /** {@link #PERF_STATS}: the slice ceiling actually chosen, nanoseconds. */
    public static PerfStats sliceBudgetStats() {
        return sliceBudgetHistogram.stats();
    }

    /**
     * Start the four distributions over. The bench calls this between
     * scenarios, on the client thread, because a percentile cannot be
     * differenced the way a counter can: p99 over "the whole session so
     * far" answers no question the brief asks, and the standing-still
     * scenario's distribution must not carry the travel leg's tail.
     */
    public static void resetPerfStats() {
        captureCostHistogram.reset();
        sliceSpendHistogram.reset();
        sliceOverrunHistogram.reset();
        sliceBudgetHistogram.reset();
    }

    /**
     * Fold the slice that is ending into the three per-frame
     * distributions. Called from {@link #openSlice} while the closing
     * slice's clock and its ceiling are both still readable, and only
     * when {@link #PERF_STATS} is armed.
     *
     * <p>Slices that spent NOTHING are deliberately absent from the
     * spend histogram and present in the budget one: a budget held open
     * over an idle far field is free (the budget is a ceiling, not an
     * allocation - see {@link #sliceBudgetFor}), and folding those zeros
     * in would drag every percentile of the spend distribution towards
     * zero and hide the frames that actually cost something.</p>
     */
    private static void recordClosingSlice() {
        if (sliceBudgetNanos > 0L) {
            sliceBudgetHistogram.record(sliceBudgetNanos);
        }
        if (sliceSpentNanos > 0L) {
            sliceSpendHistogram.record(sliceSpentNanos);
            long overrun = sliceSpentNanos - sliceBudgetNanos;
            if (overrun > 0L) {
                sliceOverrunHistogram.record(overrun);
            }
        }
    }

    /**
     * Advance the cyclic SAFETY-NET walk over the client's loaded window
     * - demoted at M4 from extractor to event producer, and at M6 from
     * census-taker to pure safety net. It spends NOTHING: each probe is
     * one chunk-cache slot read plus one record-map probe, and a column
     * found wanting - loaded with no record (the far field was armed
     * mid-session, or the map's overflow evicted it), or dirty with no
     * job queued (a fanout raced a drop, a settings flip re-dirtied the
     * world) - is FILED for the scheduler, which is the only thing that
     * extracts. Discovery = E1, staleness = E4, upgrades = E2/E3; this
     * walk is the safety net under all three.
     *
     * <p><b>M6, the walk's disposition as built:</b> the design of
     * record said the sweep "dies last" and the gauges replace its
     * census. HALF of that happened: the census half died (the page
     * folds the record map now, {@link #saveCensus}), but the walk
     * itself stays, because the M4 review restored four arms the
     * design's premise predated - discovery, the E4 backstop, the
     * degraded repair (without it, standing still would never heal
     * degraded ground again) and the record-vs-cache membership settle -
     * and every one of them needs exactly this probe loop. Deleting the
     * walk wholesale would have re-opened the review's blocker to honour
     * a sentence written before it.</p>
     *
     * <p>The window is the square the client actually keeps chunks in:
     * {@code ClientChunkCache.calculateStorageRange} is
     * {@code max(2, viewDistance) + 3} (javap ip 0-7), and the caller
     * passes that radius. The cursor is a CHEBYSHEV-RING index that
     * wraps (pre13, {@link #sweepRing}), so a partial pass is a disc
     * around the camera - and since filing preserves ring order, the
     * jobs it files drain nearest-first like everything else (O3).</p>
     *
     * <p>Filing a dirty column requires the plus complete OR a stored
     * version behind the live one: a stale shell is refreshed even
     * degraded (a degraded-current shell beats a whole-wrong one - the
     * pre15 rule), while a never-stored frontier column waits for its
     * neighbour exactly as the receive plus always made it.</p>
     *
     * @param probes how many columns to examine this pump; see
     *               {@link #SWEEP_PROBES_PER_PUMP_BUSY}
     */
    private static void sweepLoadedWindow(int centerChunkX, int centerChunkZ,
            int radiusChunks, int probes) {
        if (radiusChunks <= 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        if (level == null) {
            return;
        }
        ChunkSource source = level.getChunkSource();
        // Hoisted out of the probe loop: the answer cannot change
        // mid-loop, and a WeakReference.get() per probe is a GC barrier
        // paid 1024 times for nothing.
        noteLevelObject(level);
        if (sweepRing > radiusChunks || sweepRing < 0) {
            wrapSweep();
        }
        for (int probe = 0; probe < probes; probe++) {
            int r = sweepRing;
            int perimeter = r == 0 ? 1 : 8 * r;
            if (sweepIndex >= perimeter || sweepIndex < 0) {
                sweepIndex = 0;
            }
            int x = centerChunkX + ringDx(r, sweepIndex);
            int z = centerChunkZ + ringDz(r, sweepIndex);
            if (++sweepIndex >= perimeter) {
                sweepIndex = 0;
                if (++sweepRing > radiusChunks) {
                    wrapSweep();
                }
            }
            long key = columnKey(x, z);
            LevelChunk chunk = source.getChunk(x, z, false);
            if (chunk == null) {
                // The client is not holding this column. Not counted in
                // the census (the square is a fifth larger than the
                // pad-2 disc the client really keeps chunks in) - but
                // the RECORD-vs-cache membership rule is enforced here:
                // a record still claiming LIVE for a column the cache
                // does not hold missed its drop event (the E6 valve, an
                // exotic unload path), and it settles NOW, which
                // demotes the rd-change hook from correctness path to
                // promptness optimization inside this window. PAUSED
                // while E6 captures are pending triage: settling a
                // column whose only copy sits in the capture list would
                // make the triage's idempotency skip data we still
                // hold.
                if (leavingCount() != 0) {
                    continue;
                }
                ColumnRecord stale = columns == null ? null : columns.get(key);
                if (stale != null && stale.writingVersion == 0) {
                    if (stale.state == STATE_LIVE_CLEAN) {
                        settleGoneSaved(stale);
                    } else if (stale.state == STATE_LIVE_DIRTY) {
                        leaveBehind(stale);
                    }
                }
                continue;
            }
            ColumnRecord rec = columns == null ? null : columns.get(key);
            if (rec == null) {
                if (neighborsLoaded(source, x, z)) {
                    rec = ensureRecord(key);
                    fileFill(rec, key);
                    farExtractSweepExtracts.increment();
                    sweepOwedThisPass++;
                }
                continue;
            }
            boolean dirty = rec.liveVersion > rec.storedVersion;
            if (!dirty) {
                boolean whole = (rec.quality
                        & (QUALITY_WHOLE | QUALITY_EMPTY)) != 0;
                if (rec.state != STATE_LIVE_CLEAN || rec.writingVersion != 0) {
                    continue;
                }
                // The E4 backstop: setBlockState is the client's only
                // markUnsaved producer, so with the E4 mixin healthy
                // this read never fires for a CLEAN record (E4 already
                // dirtied it) - it exists for the day the mixin latches
                // broken, when mutations land on the next pass instead
                // of being lost. Costs the one slot-flag read the old
                // detector always paid; beginLiveCapture clears it.
                if (chunk.isUnsaved()) {
                    rec.liveVersion = ++nextStamp;
                    rec.state = STATE_LIVE_DIRTY;
                    fileFill(rec, key);
                    farExtractStaleFiled.increment();
                    farExtractSweepExtracts.increment();
                    sweepOwedThisPass++;
                    continue;
                }
                // The DEGRADED REPAIR (the review's blocker): a stored
                // shell weaker than what a walk can achieve NOW is
                // re-filed - standing still heals degraded ground again.
                // Self-terminating without an epoch: the repair walk
                // either stores whole (gate closes on quality) or finds
                // the light still unpublished (gate closes on the memo),
                // and only a fresh ARRIVAL re-opens either.
                // Phase 3: the gate was !ShellExtractor.lightPending(chunk)
                // - a single-slot memo of the LAST live walk, which the
                // live walk no longer exists to write. The record's own
                // lightReady is the successor and it is a strictly
                // TIGHTER gate: the memo only ever answered for the one
                // chunk walked most recently, so for every other column
                // it read false and let the repair through, whereas a
                // column whose light was never published now stays shut
                // out until E3 fires. Said out loud because it is a
                // behaviour change inside a performance wave: fewer
                // repair re-files, none of them ones that could have
                // succeeded.
                if (!whole && rec.lightReady && neighborsLoaded(source, x, z)) {
                    rec.liveVersion = ++nextStamp;
                    rec.state = STATE_LIVE_DIRTY;
                    fileFill(rec, key);
                    farExtractSweepExtracts.increment();
                    sweepOwedThisPass++;
                }
                continue;
            }
            if (rec.state == STATE_LIVE_DIRTY && rec.jobClass == JOB_NONE
                    && rec.writingVersion == 0 && rec.pin == null
                    && (rec.storedVersion > 0 || neighborsLoaded(source, x, z))) {
                fileFill(rec, key);
                farExtractSweepExtracts.increment();
                sweepOwedThisPass++;
            }
        }
    }

    /**
     * World change / master-switch change: the cursor AND the arrears
     * belong to the window we just left.
     */
    private static void resetSweep() {
        sweepRing = 0;
        sweepIndex = 0;
        sweepOwedThisPass = 0;
        farExtractSweepOwed.reset();
        // M6: the page's census is the record map's fold now; the cache
        // belongs to the world we just left.
        censusCache = null;
        censusCacheMillis = Long.MIN_VALUE;
    }

    /**
     * The sweep cursor reached the outer edge of the window: publish the
     * pass's arrears and start again at the camera. (The window census
     * this used to publish died at M6 - the page folds the record map
     * instead, see {@link #saveCensus}.)
     */
    private static void wrapSweep() {
        sweepRing = 0;
        sweepIndex = 0;
        farExtractSweepOwed.reset();
        farExtractSweepOwed.add(sweepOwedThisPass);
        sweepOwedThisPass = 0;
    }

    /**
     * X offset of index {@code i} on the Chebyshev ring of radius
     * {@code r} - the same enumeration {@code FarFieldResidency.ringChunk}
     * uses, so the sweep and the ring walk visit a ring in the same order
     * and a partial pass of one lines up with a partial pass of the other.
     * {@code r == 0} is the single centre cell.
     */
    private static int ringDx(int r, int i) {
        if (r == 0) {
            return 0;
        }
        int top = 2 * r + 1;
        int side = 2 * r - 1;
        if (i < top) {
            return -r + i;
        }
        if (i < top + side) {
            return r;
        }
        if (i < top + side + top) {
            return r - (i - top - side);
        }
        return -r;
    }

    /** Z offset of index {@code i} on the ring of radius {@code r}. */
    private static int ringDz(int r, int i) {
        if (r == 0) {
            return 0;
        }
        int top = 2 * r + 1;
        int side = 2 * r - 1;
        if (i < top) {
            return -r;
        }
        if (i < top + side) {
            return -r + 1 + (i - top);
        }
        if (i < top + side + top) {
            return r;
        }
        return r - 1 - (i - top - side - top);
    }

    // ------------------------------------------------------------------
    // E6: the rd-change seam ACTS now (the pre16 audit's repro-(a) path)
    // ------------------------------------------------------------------

    /**
     * E6: {@code ClientChunkCache.updateViewRadius(int)} HEAD.
     *
     * <p><b>The old javadoc's premise here - "a client-side
     * render-distance change never reaches this hook" - was false on the
     * integrated server and is rewritten as the design demands.</b> The
     * only caller in the 26.2 jar is {@code ClientPacketListener}'s
     * handler for {@code ClientboundSetChunkCacheRadiusPacket}, and the
     * only constructor site is {@code PlayerList} - but in singleplayer
     * the client's slider IS the integrated server's view distance, so
     * PlayerList re-broadcasts every slider move and this hook fires for
     * exactly the owner's workflow (load at rd 120, drop to 16). The
     * silent-abandon path is real traffic: {@code updateViewRadius}
     * copies only the survivors into a fresh Storage (ip 18-149) and the
     * rest leave with NO unload callback - the one population that
     * reached no seam at all, which is the pre16 audit's confirmed
     * repro-(a) loss mechanism.</p>
     *
     * <p>So a SHRINK now walks the OLD window {@code [center +-
     * (oldRd+3)]} through the public {@code getChunk(x, z, false)} - the
     * old Storage is still installed at HEAD, so no private accessor is
     * needed (the dossier's UNRESOLVED row stays unresolved,
     * deliberately) - and applies the E5 transition to every column
     * outside the NEW storage range {@code max(2, newRd) + 3}: clean
     * columns close GONE_SAVED in one probe; dirty ones become
     * {@link Pin}s, chunk ref AND light layer refs, at the seam (the
     * one moment both are alive - see {@link #captureLeaving} for the
     * two counted valves), never extracted here. The walk is exempt
     * from the per-frame leave reserve (a once-per-rd-change storm);
     * its triage's strips are not. The radius packet precedes the
     * forget storm on the same ordered stream, so a column this walk
     * pins that later reaches the drop seam anyway no-ops there (the
     * E5 rows for PINNED/GONE are idempotent). A GROW updates the
     * tracked radius and nothing else.</p>
     */
    public static void onViewRadiusShrink(int newRadius) {
        if (!armed) {
            return;
        }
        int prev = lastViewRadius;
        lastViewRadius = newRadius;
        if (prev < 0 || newRadius >= prev) {
            return; // first sighting, or a grow: nothing abandoned
        }
        if (!viewCenterKnown) {
            return; // no baseline to walk (documented honesty: we never
                    // guess the pre-arm window; E1 repairs on revisit)
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        if (level == null) {
            return;
        }
        // Phase 1, HERE and synchronous because it must be: the old
        // Storage is only installed at HEAD. Phase 2 is
        // drainLeavingCaptures() on the pump.
        captureLeaving(level.getChunkSource(),
                lastViewCenterX, lastViewCenterZ, Math.max(2, prev) + 3,
                lastViewCenterX, lastViewCenterZ, Math.max(2, newRadius) + 3);
    }

    /**
     * The E6 capture walk, phase 1, shared by BOTH silent-discard
     * seams: over every cell of the OLD window (rings around
     * {@code oldCx/oldCz} out to {@code oldRange}) that falls outside
     * the KEPT window ({@code keepRange} of {@code keepCx/keepCz}),
     * one getChunk, one record probe, and then the cheapest honest
     * ending - a CLEAN record settles GONE_SAVED inline (one state
     * write, no allocation: the review's allocation-spike rule holds,
     * records are never CREATED here), and only the dirty-or-untracked
     * minority pays for a {@link Pin}. The pin takes its light HERE, at
     * the seam, because deferring it loses it to the forget flood (see
     * {@link #leavingPins}); pins register in the graph immediately so
     * every storm sibling resolves at triage. A radius shrink
     * enumerates rings inner-first, so capture and triage order are
     * nearest-first; a centre move enumerates ONLY its leaving bands
     * directly - the strip a chunk crossing sheds, not the whole old
     * window skip-tested. Two valves, both counted, neither a plan:
     * {@link #E6_LIGHT_DEADLINE_NANOS} (pins past it go flat-light,
     * {@link #farSaveLightUncaptured}) and
     * {@link #E6_CAPTURE_HARD_DEADLINE_NANOS} (cells past it are
     * skipped outright, {@link #farSaveCaptureSkipped}, and behave as
     * pre-Phase-4: repaired on revisit, settled by the census).
     */
    private static void captureLeaving(ChunkSource source,
            int oldCx, int oldCz, int oldRange,
            int keepCx, int keepCz, int keepRange) {
        java.util.ArrayList<Pin> pending = leavingPins;
        if (pending == null) {
            pending = new java.util.ArrayList<>(256);
            leavingPins = pending;
        }
        long start = System.nanoTime();
        long lightDeadline = start + E6_LIGHT_DEADLINE_NANOS;
        long hardDeadline = start + E6_CAPTURE_HARD_DEADLINE_NANOS;
        // One settings snapshot for the whole walk: the walk is one
        // game-thread moment, and a storm must not pay five property
        // reads per pin (Pin.Settings' own note).
        Pin.Settings settings = Pin.Settings.snapshot();
        boolean lightOpen = true;
        boolean open = true;
        int cells = 0;
        if (oldCx == keepCx && oldCz == keepCz) {
            // Radius shrink: the leaving set is a centred annulus, and
            // the ring enumeration IS the nearest-first order.
            for (int r = keepRange + 1; r <= oldRange && open; r++) {
                int perimeter = 8 * r;
                for (int i = 0; i < perimeter; i++) {
                    if ((++cells & 0xFF) == 0) {
                        long now = System.nanoTime();
                        lightOpen = now <= lightDeadline;
                        if (now > hardDeadline) {
                            open = false;
                            // The geometric remainder, counted whole: an
                            // upper bound (unloaded cells included), and
                            // honest for it - "no silent caps".
                            farSaveCaptureSkipped.add(
                                    annulusCells(oldRange, keepRange) - cells);
                            break;
                        }
                    }
                    captureLeavingCell(source,
                            oldCx + ringDx(r, i), oldCz + ringDz(r, i),
                            pending, lightOpen, settings);
                }
            }
            return;
        }
        // Centre move: the leaving set is two rectangular bands of the
        // old window (the x rows outside the kept x-span, plus the z
        // strip of the shared x-span outside the kept z-span), and it
        // is enumerated DIRECTLY - iterating the whole old window per
        // chunk crossing to skip-test 61k cells at rd 120 would put a
        // half-millisecond scan on the packet path for a 247-cell
        // strip, which is the freeze-trace lesson in miniature.
        int lowKeepX = Math.max(oldCx - oldRange, keepCx - keepRange);
        int highKeepX = Math.min(oldCx + oldRange, keepCx + keepRange);
        capture:
        for (int x = oldCx - oldRange; x <= oldCx + oldRange; x++) {
            boolean keptColumn = x >= lowKeepX && x <= highKeepX;
            for (int z = oldCz - oldRange; z <= oldCz + oldRange; z++) {
                if (keptColumn && z >= keepCz - keepRange
                        && z <= keepCz + keepRange) {
                    // Inside the kept window: hop straight past its
                    // whole z-span instead of testing every cell.
                    z = keepCz + keepRange;
                    continue;
                }
                if ((++cells & 0xFF) == 0) {
                    long now = System.nanoTime();
                    lightOpen = now <= lightDeadline;
                    if (now > hardDeadline) {
                        // No cheap closed form for the remainder here;
                        // one is the honest floor that keeps the counter
                        // non-zero whenever the valve ever fires.
                        farSaveCaptureSkipped.increment();
                        break capture;
                    }
                }
                captureLeavingCell(source, x, z, pending, lightOpen, settings);
            }
        }
    }

    /** Cells in a centred annulus between two Chebyshev ranges. */
    private static long annulusCells(int oldRange, int keepRange) {
        long outer = (2L * oldRange + 1) * (2L * oldRange + 1);
        long inner = (2L * keepRange + 1) * (2L * keepRange + 1);
        return outer - inner;
    }

    /** One phase-1 cell: settle clean inline, pin the dirty minority. */
    private static void captureLeavingCell(ChunkSource source, int x, int z,
            java.util.ArrayList<Pin> pending, boolean captureLight,
            Pin.Settings settings) {
        LevelChunk leaving = source.getChunk(x, z, false);
        if (leaving == null) {
            return;
        }
        long key = columnKey(x, z);
        ColumnRecord rec = columns == null ? null : columns.get(key);
        if (rec != null && rec.state != STATE_LIVE_CLEAN
                && rec.state != STATE_LIVE_DIRTY) {
            return; // already pinned or gone: idempotent
        }
        if (rec != null && rec.liveVersion <= rec.storedVersion) {
            // The E5 clean row, run where it is cheapest: one probe, one
            // state write, the honest label travelling with it.
            if ((rec.quality & (QUALITY_WHOLE | QUALITY_EMPTY)) == 0) {
                farSaveDegradedLeft.increment();
            }
            settleGoneSaved(rec);
            return;
        }
        if (!captureLight) {
            farSaveLightUncaptured.increment();
        }
        long startNanos = System.nanoTime();
        Pin pin = Pin.capture(key, leaving,
                rec != null && rec.lightReady, captureLight, settings);
        registerPin(pin);
        recordPinCaptureNanos(System.nanoTime() - startNanos);
        pending.add(pin);
    }

    /**
     * THE TRIAGE - E6's phase 2 and, since leg (a)'s first real run,
     * the drop seam's second half too: a bounded count per pump over
     * {@link #leavingPins}, nearest (inner-ring) first for storm
     * captures, drop order for E5 singletons. The seams already settled
     * the clean majority inline, so every pin here is dirty or
     * untracked: each
     * gets its sides resolved through the pin graph, its stayed-live
     * strips on the leave reserve, its record transitioned to PINNED
     * and its handoff to the worker ({@link #finishPin}). When the
     * reserve runs out mid-strip the pin DEFERS whole - the triage
     * stops and resumes next pump with a fresh reserve, so a strip is
     * paced, never skipped, on this path. A column the client holds
     * AGAIN (the radius came back up before its triage) is dismissed:
     * its record never left LIVE and its own seams own it; the stale
     * pin leaves the graph.
     */
    private static void drainLeavingCaptures() {
        java.util.ArrayList<Pin> pending = leavingPins;
        if (pending == null || pending.isEmpty()) {
            return;
        }
        Object level = lastLevel.get();
        int drained = 0;
        while (leavingHead < pending.size()
                && drained++ < LEAVING_TRIAGE_PER_PUMP) {
            Pin pin = pending.get(leavingHead);
            if (pinIndex == null || pinIndex.get(pin.key) != pin) {
                // Superseded by a FRESHER capture of the same column
                // (registerPin overwrites by key): a drop-arrive-drop
                // inside one triage window must finish the newest
                // chunk's capture, not write stale content under a
                // newer version stamp. The stale entry just dies.
                pin.released = true;
                leavingHead++;
                continue;
            }
            if (pin.chunk.getLevel() != level) {
                dismissPin(pin); // dead world's capture; dropLeavingCaptures
                leavingHead++;   // counted it at the swap (belt and braces)
                continue;
            }
            ChunkSource source = pin.chunk.getLevel().getChunkSource();
            if (source.getChunk(pin.chunkX, pin.chunkZ, false) != null) {
                dismissPin(pin); // live again: the record never left LIVE
                leavingHead++;
                continue;
            }
            ColumnRecord rec = columns == null ? null : columns.get(pin.key);
            if (rec != null && rec.state != STATE_LIVE_CLEAN
                    && rec.state != STATE_LIVE_DIRTY) {
                dismissPin(pin); // pinned by E5 or settled meanwhile
                leavingHead++;
                continue;
            }
            if (rec != null && rec.liveVersion <= rec.storedVersion) {
                // Went clean between capture and triage (its in-flight
                // write acked): the E5 clean row, one probe.
                if ((rec.quality & (QUALITY_WHOLE | QUALITY_EMPTY)) == 0) {
                    farSaveDegradedLeft.increment();
                }
                settleGoneSaved(rec);
                dismissPin(pin);
                leavingHead++;
                continue;
            }
            if (rec == null) {
                rec = ensureRecord(pin.key);
                rec.lightReady = false; // untracked drop: never saw E3
            }
            if (!finishPin(rec, pin)) {
                return; // reserve spent mid-strip: resume next pump
            }
            leavingHead++;
        }
        if (leavingHead >= pending.size()) {
            pending.clear();
            leavingHead = 0;
        } else if (leavingHead > 4096 && leavingHead * 2 >= pending.size()) {
            pending.subList(0, leavingHead).clear();
            leavingHead = 0;
        }
    }

    /**
     * Phase 3: forget every LIVE capture that has been frozen but not
     * finished. Called at a master-switch flip and at a world change -
     * both moments when the snapshots on that list describe a world the
     * pump will not be asked about again.
     *
     * <p>Nothing is counted, and that is correct rather than lazy: the
     * client still holds every one of these columns, their records are
     * still LIVE_DIRTY, and either the far field is off (in which case
     * nothing is owed) or the world changed (in which case
     * {@link #countUnsavedAtReset} already counted the record on
     * {@code farSaveLeftUnsaved}). Counting here as well would
     * double-count, and counting them as LOST would put repairable
     * columns on a gauge whose contract is zero.</p>
     */
    private static void dropLiveCaptures() {
        java.util.ArrayList<Pin> pending = liveCaptures;
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (int i = liveCaptureHead; i < pending.size(); i++) {
            Pin pin = pending.get(i);
            ColumnRecord rec = columns == null ? null : columns.get(pin.key);
            if (rec != null && rec.pin == pin) {
                releasePin(rec);
            } else {
                dismissPin(pin);
            }
        }
        pending.clear();
        liveCaptureHead = 0;
    }

    /** A capture that never became a pin leaves the graph here. */
    private static void dismissPin(Pin pin) {
        pin.released = true;
        if (pinIndex != null && pinIndex.get(pin.key) == pin) {
            pinIndex.remove(pin.key); // never a successor's entry
        }
    }

    /** Captures not yet triaged, for {@link #backlogged}. */
    private static int leavingCount() {
        java.util.ArrayList<Pin> pending = leavingPins;
        return pending == null ? 0 : pending.size() - leavingHead;
    }

    /**
     * World change / master-off: the pending captures belong to a level
     * that is going away. A capture whose record says dirty - or that
     * has no record at all: unknown is dirty - is terrain leaving
     * unsaved, counted on the aggregate; then the pins go. (Phase 1
     * settles clean columns inline, so in practice every entry here
     * counts; the record probe keeps the accounting honest anyway.)
     */
    private static void dropLeavingCaptures() {
        java.util.ArrayList<Pin> pending = leavingPins;
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (int i = leavingHead; i < pending.size(); i++) {
            Pin pin = pending.get(i);
            if (pinIndex != null && pinIndex.get(pin.key) != pin) {
                pin.released = true;
                continue; // a superseded duplicate: its successor counts
            }
            ColumnRecord rec = columns == null ? null : columns.get(pin.key);
            if (rec == null || rec.liveVersion > rec.storedVersion) {
                farSaveLeftUnsaved.increment();
            }
            dismissPin(pin);
        }
        pending.clear();
        leavingHead = 0;
    }

    /**
     * The level-swap accounting (the review's discarded-window
     * blocker): every record about to be reset while still owed work is
     * COUNTED before it dies. LIVE_DIRTY and GONE_BEHIND columns are
     * terrain the next visit re-receives and re-extracts - the
     * left-unsaved class, not the lost one - while the pins ride the
     * worker's drain window and land on {@link #farSaveLost} only if
     * the deadline strands them (the design's own level-swap rule,
     * counted in the worker's drain). A dimension hop mid-travel
     * therefore moves the aggregate, never the zero-is-the-contract
     * gauge.
     */
    private static void countUnsavedAtReset() {
        Long2ObjectOpenHashMap<ColumnRecord> map = columns;
        if (map == null || map.isEmpty()) {
            return;
        }
        int unsaved = 0;
        for (var it = map.long2ObjectEntrySet().fastIterator(); it.hasNext();) {
            byte state = it.next().getValue().state;
            if (state == STATE_LIVE_DIRTY || state == STATE_GONE_BEHIND) {
                unsaved++;
            }
        }
        if (unsaved > 0) {
            farSaveLeftUnsaved.add(unsaved);
        }
    }

    /**
     * {@code ClientChunkCache.updateViewCenter(int, int)} HEAD - the
     * OTHER silent-discard seam, and it CAPTURES now, because the
     * premise that used to stand here ("the columns it slides out of
     * range are later evicted through {@code Storage.replace}, which
     * routes through unload") is FALSE for any move farther than the
     * send pad, and gametest (b)'s first real run proved it with a
     * lost edit. The jar facts: {@code ClientChunkCache.drop} returns
     * at ip 12-18 when {@code !storage.inRange(x, z)}, so the server's
     * forget packets NO-OP for chunks the centre has already slid out
     * of the valid window; and {@code Storage.replace} fires only for
     * a slot the new send disc actually REFILLS - after a teleport of
     * more than one window, whole residue rows of the old window map
     * to slots outside the new send disc and are never replaced. Those
     * chunks sit in storage, invisible to {@code getChunk}, with no
     * unload ever coming: reachable at exactly ONE moment, this HEAD,
     * where the old centre is still installed. So a centre move
     * captures its leaving annulus exactly as a radius shrink does -
     * for the ordinary one-chunk crossing that is a single
     * {@code 2*range+1} strip of probes, microseconds, and the strip's
     * clean majority closes in one record probe each at triage.
     */
    public static void onViewCenterMove(int newCenterX, int newCenterZ) {
        if (!armed) {
            return;
        }
        boolean known = viewCenterKnown;
        int prevX = lastViewCenterX;
        int prevZ = lastViewCenterZ;
        lastViewCenterX = newCenterX;
        lastViewCenterZ = newCenterZ;
        viewCenterKnown = true;
        if (!known || lastViewRadius < 0
                || (prevX == newCenterX && prevZ == newCenterZ)) {
            return; // no baseline, or no movement: nothing slid out
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        if (level == null) {
            return;
        }
        int range = Math.max(2, lastViewRadius) + 3;
        captureLeaving(level.getChunkSource(),
                prevX, prevZ, range, newCenterX, newCenterZ, range);
    }

    /**
     * The whole {@code ClientLevel} is being replaced or torn down
     * ({@code Minecraft.setLevel} HEAD for respawn/login dimension swaps,
     * {@code Minecraft.clearClientLevel} HEAD for disconnect — the two
     * are disjoint by bytecode: setLevel never calls clearClientLevel and
     * clearClientLevel nulls the field directly, so no double count). At
     * either HEAD {@code Minecraft.level} still points at the OLD, fully
     * populated level (setLevel's putfield is at ip 2, after HEAD;
     * clearClientLevel's null putfield at ip 71-73), so the loaded-chunk
     * count read here is the real traffic figure.
     *
     * <p>Also resets EVERY piece of per-world state: the view baselines
     * (the next level's cache has its own geometry) and the record map
     * with its job queues, because column (0, 0) of the next world is
     * not column (0, 0) of this one. {@code MinecraftLevelSwapMixin}
     * calls this method only while armed, so {@link #lastLevel} is what
     * actually makes a cross-world record map impossible. Both,
     * deliberately: the identity watch cannot fire until the next chunk
     * event, and this one empties the structures at the swap itself.
     * The admitted PINS are NOT discarded here - M5 gives the worker
     * the design's 5 s drain: {@code FarField.onWorldLeave}'s barrier
     * holds the old store open while the queue empties, and every pin
     * the deadline strands is counted on {@link #farSaveLost} by the
     * worker's own generation test. The one game-thread duty is
     * {@link #handHeldPinsToWorker}: a pin parked behind an in-flight
     * write (I3) was never enqueued, its ack is about to go foreign
     * against a reset map, and nothing else would ever queue it.</p>
     */
    public static void onLevelSwap() {
        dropLeavingCaptures(); // BEFORE the reset: it reads the records
        dropLiveCaptures();    // Phase 3: the same, for the fill path's
        if (FarField.ioDead()) {
            abandonPins(); // no drain window exists for a dead store
        } else {
            handHeldPinsToWorker();
        }
        countUnsavedAtReset();
        resetRecords();
        resetSweep();
        lastViewRadius = -1;
        viewCenterKnown = false;
    }

    /**
     * Level swap: enqueue every pin that was captured but never handed
     * to the worker (the I3 in-flight-write arm - its ack would have
     * done this, and that ack lands on a reset map as a foreign no-op).
     * The worker's drain window either saves them into the closing
     * store or counts them on {@link #farSaveLost}; either way they are
     * represented, which is invariant I1 across a world change.
     */
    private static void handHeldPinsToWorker() {
        Long2ObjectOpenHashMap<ColumnRecord> map = columns;
        if (map == null || pinnedGauge == 0) {
            return;
        }
        for (var it = map.long2ObjectEntrySet().fastIterator(); it.hasNext();) {
            ColumnRecord rec = it.next().getValue();
            if (rec.state == STATE_PINNED && rec.pin != null
                    && rec.writingVersion != rec.pin.version) {
                PinWorker.enqueue(rec.pin);
            }
        }
    }

    // ------------------------------------------------------------------
    // The extract-once tracker
    // ------------------------------------------------------------------

    /**
     * Drop the per-world bookkeeping when the chunk arrives from a
     * different {@code Level} than the last one we saw (see
     * {@link #lastLevel}). Two reference compares in the common case.
     */
    private static void noteLevel(LevelChunk chunk) {
        noteLevelObject(chunk.getLevel());
    }

    /** {@link #noteLevel} for callers that already hold the level. */
    private static void noteLevelObject(Object level) {
        if (level == lastLevel.get()) {
            return;
        }
        lastLevel = new java.lang.ref.WeakReference<>(level);
        dropLeavingCaptures(); // BEFORE the reset: it reads the records
        // Pins belong to the level we just left; the worker's drain
        // window (FarField's leave barrier) either files them under the
        // OLD store or counts them lost - the generation test makes a
        // wrong-world write impossible, so nothing here needs to drop
        // them. Held-but-unqueued pins are handed over first (or
        // abandoned outright when no store exists to drain into).
        if (FarField.ioDead()) {
            abandonPins();
        } else {
            handHeldPinsToWorker();
        }
        countUnsavedAtReset();
        resetRecords();
        resetSweep();
    }

    /**
     * Per-world reset of the record map and both job queues. The
     * GONE_BEHIND gauge resets with them (it is a population of THIS
     * world's records); {@link #farSaveLost} is monotone across the
     * session, per the design.
     */
    private static void resetRecords() {
        if (columns != null) {
            columns.clear();
        }
        editQueue.reset();
        fillQueue.reset();
        if (editPendingKeys != null) {
            editPendingKeys.clear();
            editPendingDue.clear();
            editPendingHead = 0;
        }
        farSaveBehindGauge = 0;
        // The pin GRAPH is per-world bookkeeping like the records (the
        // Pin objects themselves live on in the worker's queue,
        // self-contained, until the drain window settles or counts
        // them - see onLevelSwap).
        if (pinIndex != null) {
            pinIndex.clear();
        }
        if (admittedPins != null) {
            admittedPins.clear();
        }
        pinnedGauge = 0;
        // Phase 3: the live captures are per-world bookkeeping like the
        // records. The Pin objects already handed to the worker live on
        // in its queue and are dropped there on the store-generation
        // test (see PinWorker.drainOne, which does NOT count a live
        // capture as lost - countUnsavedAtReset already counted its
        // record on farSaveLeftUnsaved).
        if (liveCaptures != null) {
            liveCaptures.clear();
        }
        liveCaptureHead = 0;
        liveCaptureGauge = 0;
        e9BreachSinceNanos = Long.MIN_VALUE;
    }

    /**
     * Get or create the record for a column. Creation defaults are the
     * E1 ABSENT row's - live at a fresh stamp, LIVE_DIRTY - and every
     * caller overwrites what its own event says differently. Overflow
     * past {@value #MAX_TRACKED_COLUMNS} evicts the farthest
     * GONE_SAVED records only; see {@link #evictFarthestSaved}.
     */
    private static ColumnRecord ensureRecord(long key) {
        Long2ObjectOpenHashMap<ColumnRecord> map = columns;
        if (map == null) {
            map = new Long2ObjectOpenHashMap<>(4096);
            columns = map;
        }
        ColumnRecord rec = map.get(key);
        if (rec != null) {
            return rec;
        }
        if (map.size() >= MAX_TRACKED_COLUMNS && !evictionTriedThisSlice) {
            // At most ONE eviction scan per slice: a map with nothing
            // evictable must not pay an O(n) scan on EVERY insert of a
            // storm frame (the map growing past the cap is the
            // documented bounded-memory-loses-to-bounded-truth arm).
            evictionTriedThisSlice = true;
            evictFarthestSaved(map);
        }
        rec = new ColumnRecord();
        rec.liveVersion = ++nextStamp;
        rec.state = STATE_LIVE_DIRTY;
        // lightReady defaults TRUE (see the field's javadoc: every
        // creator but the fast-flight capture is looking at a chunk
        // whose publish already happened or is caught by the extractor's
        // lightReadable floor; a false default would starve them behind
        // G1, since their E3 fired before the record existed). The
        // capture paths overwrite it explicitly.
        rec.lightReady = true;
        map.put(key, rec);
        return rec;
    }

    /**
     * The map's overflow policy (design section 1): evict the FARTHEST
     * quarter of the GONE_SAVED population - a re-extract on revisit is
     * safe, an evicted dirty record never is, so dirty and PINNED
     * records are never candidates (this is what killed the orphaned
     * staleColumns-vs-tracker-wipe bug: one structure, one lifetime).
     * One O(n) pass plus one sort of the saved subset, reached once per
     * {@value #MAX_TRACKED_COLUMNS} columns visited - a multi-hour
     * flight - never on a frame that did not just insert. If nothing is
     * GONE_SAVED the map simply grows: bounded memory loses to bounded
     * truth.
     */
    private static void evictFarthestSaved(
            Long2ObjectOpenHashMap<ColumnRecord> map) {
        int camX = lastSliceCameraX;
        int camZ = lastSliceCameraZ;
        int savedCount = 0;
        for (var it = map.long2ObjectEntrySet().fastIterator(); it.hasNext();) {
            ColumnRecord rec = it.next().getValue();
            // In-flight writes are NOT candidates: evicting one would
            // orphan its ack (rec==null reads as foreign), and a failed
            // ack would then land on no counter and no repair path.
            if (rec.state == STATE_GONE_SAVED && rec.writingVersion == 0) {
                savedCount++;
            }
        }
        if (savedCount == 0) {
            return; // all dirty/pinned: grow rather than lose truth
        }
        long[] keys = new long[savedCount];
        long[] order = new long[savedCount];
        int n = 0;
        for (var it = map.long2ObjectEntrySet().fastIterator(); it.hasNext();) {
            var entry = it.next();
            ColumnRecord rec = entry.getValue();
            if (rec.state == STATE_GONE_SAVED && rec.writingVersion == 0) {
                long key = entry.getLongKey();
                long dist = chebyshev(columnX(key), columnZ(key), camX, camZ);
                keys[n] = key;
                // Distance in the high bits: a plain long sort orders
                // nearest-first and the tail is the farthest quarter.
                order[n] = (Math.min(dist, 0x3FFFFFL) << 24) | n;
                n++;
            }
        }
        java.util.Arrays.sort(order, 0, n);
        int evict = Math.max(1, n / 4);
        for (int i = n - evict; i < n; i++) {
            map.remove(keys[(int) (order[i] & 0xFFFFFF)]);
        }
    }

    /**
     * Master switched OFF mid-session: nothing will ever drain the
     * admitted pins (the seams are gated, the pump stops being reached),
     * and holding tens of megabytes of terrain for a disabled feature is
     * the "off costs nothing" promise broken - so every pin still
     * attached to a PINNED record is released and counted on
     * {@link #farSaveLost} (data only we held, discarded - the same
     * accounting a world change gives the worker's stragglers). The
     * records go GONE_BEHIND: repairable if the player re-arms and
     * revisits, honest either way. World CHANGES do not come here - the
     * worker gets its 5 s drain window there instead (the design's
     * level-swap rule; see {@code FarField.onWorldLeave}).
     */
    private static void abandonPins() {
        Long2ObjectOpenHashMap<ColumnRecord> map = columns;
        if (map == null || (pinnedGauge == 0 && liveCaptureGauge == 0)) {
            return;
        }
        for (var it = map.long2ObjectEntrySet().fastIterator(); it.hasNext();) {
            ColumnRecord rec = it.next().getValue();
            if (rec.pin == null) {
                continue;
            }
            if (rec.pin.live) {
                // Phase 3: a LIVE capture is a copy of a column the
                // client still holds, so abandoning it loses nothing -
                // no GONE_BEHIND, no farSaveLost, and the record stays
                // LIVE_DIRTY for whenever the far field is armed again.
                // Its write, if it reached the queue, will be refused by
                // submitShellFromWorker's disabled check and its ack will
                // land on a pump that is not running, which is why
                // writingVersion has to be cleared here: nothing else
                // will, and a stuck stamp would block that column for the
                // rest of the session.
                releasePin(rec);
                rec.writingVersion = 0;
                continue;
            }
            if (rec.state == STATE_PINNED) {
                releasePin(rec);
                leaveBehind(rec);
                farSaveLost.increment();
            }
        }
        if (admittedPins != null) {
            admittedPins.clear();
        }
    }

    /**
     * The design's residency-interface seam (Q1/Q4 coordination): is
     * this column's stored shell behind its live truth right now? Game
     * thread only, one map probe, derived - never cached (I2). The seam
     * design's refresh-at-promote consumes it.
     */
    public static boolean columnDirty(long key) {
        ColumnRecord rec = columns == null ? null : columns.get(key);
        return rec != null && rec.liveVersion > rec.storedVersion;
    }

    /**
     * The GONE_BEHIND gauge: columns whose only copy left the client
     * unsaved and un-captured - the honest, repairable loss class (E1
     * repair decrements on revisit). Zero is the contract; the Layer 1
     * page shows it beside {@link #farSaveLost}.
     */
    public static long farSaveBehind() {
        // RAW, deliberately unclamped: the gauge's writers are the two
        // state-guarded helpers, so negative is structurally impossible
        // - and if an accounting bug ever broke that, a clamp here is
        // exactly how it would hide from the suite's zero assertions.
        // (repairBehind floors the field and trips
        // farSaveGaugeUnderflow, which the suite also asserts zero.)
        return farSaveBehindGauge;
    }

    /**
     * Forget which columns have been stored this session, so every column
     * the client is still holding is extracted again.
     *
     * <p>Called by the Far Terrain layer screen when a SAVE-TIME setting
     * changes (Plants In Water, Colour Blending, Real Light). Those rows
     * decide what the extractor writes, so terrain already on disk keeps
     * the detail it was saved with and no amount of re-meshing can change
     * it; the one thing that CAN change is the terrain the client is
     * currently holding, and this is what lets the ordinary machinery
     * re-save it. Nothing is walked here: the safety-net walk re-files the
     * loaded columns over its following passes and the drop seam covers
     * whatever leaves first, both inside
     * {@code FarFieldConfig.extractBudgetMillis()}, so a settings flip
     * cannot cost a frame.</p>
     *
     * <p>The store accepts the rewrite as an equal-tier overwrite
     * (FarFieldStore's truth-tier rule), so the worst case is that a
     * column is written twice with the same content.</p>
     */
    public static void forgetExtractedColumns() {
        Long2ObjectOpenHashMap<ColumnRecord> map = columns;
        if (map != null) {
            // The design's settings-flip administrative event: all
            // stored versions to 0 and quality NONE (the disk keeps the
            // old detail; only a rewrite can change it), loaded columns
            // to LIVE_DIRTY - the safety-net walk files their FILL jobs over
            // the following passes, which is the same pacing the old
            // tracker wipe relied on - and GONE records to ABSENT
            // (nothing to re-walk until the player returns; E1 recreates
            // them). An in-flight write's ack goes foreign (version
            // mismatch after the zeroing below never happens - the
            // writingVersion is cleared, so the ack is skipped) and the
            // rewrite simply lands as an equal-tier overwrite later.
            var it = map.long2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                var entry = it.next();
                ColumnRecord rec = entry.getValue();
                switch (rec.state) {
                    case STATE_GONE_SAVED -> it.remove();
                    case STATE_GONE_BEHIND -> {
                        // The record is being forgotten with its truth
                        // still unsaved: the population leaves the gauge
                        // through the ONE decrement site and the fact
                        // moves to the revisit-repairable aggregate.
                        repairBehind(rec);
                        farSaveLeftUnsaved.increment();
                        it.remove();
                    }
                    case STATE_PINNED -> {
                        // M5: the pin's in-flight save completes under
                        // the PRE-flip settings - for a column that is
                        // leaving anyway, that is exactly the flip's own
                        // contract ("terrain already on disk keeps the
                        // detail it was saved with"). Only the stored
                        // claim resets; the write, its version and the
                        // pin stay so the ack can settle it.
                        rec.storedVersion = 0;
                        rec.quality = 0;
                    }
                    default -> {
                        rec.storedVersion = 0;
                        rec.quality = 0;
                        rec.writingVersion = 0;
                        if (rec.state == STATE_LIVE_CLEAN) {
                            rec.state = STATE_LIVE_DIRTY;
                        }
                        rec.jobClass = JOB_NONE;
                        // The queues are reset wholesale below, so the
                        // filed flags must fall with them - a resurrected
                        // record carrying a stale flag would refuse its
                        // own re-file forever.
                        rec.filedEdit = false;
                        rec.filedFill = false;
                    }
                }
            }
        }
        // Wholesale: the entries all referenced pre-flip truth, and the
        // safety-net walk re-files the loaded window over its next passes
        // (the same pacing the old tracker wipe relied on).
        editQueue.reset();
        fillQueue.reset();
        if (editPendingKeys != null) {
            editPendingKeys.clear();
            editPendingDue.clear();
            editPendingHead = 0;
        }
        resetSweep();
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int columnX(long key) {
        return (int) (key >> 32);
    }

    private static int columnZ(long key) {
        return (int) key;
    }
}
