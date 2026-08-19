# Incremental draw snapshot - design note (2026-08-18)

## v2 DECISIONS (post-dossier; the three SNAPSHOT-DOSSIER-*.md files
## are the implementation bible - every claim below is cited there)

1. **Log record kinds: WRITE(slot) and TOMBSTONE(slot) only**, plus a
   FULL_REBUILD sentinel. Golden fact: packed ints [0..17] are
   immutable post-admission; only [18]/[19] ever change - so WRITE
   (recompute all 20 ints from the live entry at APPLY time) covers
   every mutation kind. Handles (arena backing/blocks, section
   records) are re-captured on EVERY publish, so S11-S14 need no
   records at all.
2. **The swap-remove fanout** (dossier-mutations section 3):
   RegionStore.remove compacts the tail section into the hole,
   changing the MOVED third-party entry's [18]. RS.remove gains a
   moved-owner callback; residency appends WRITE(movedSlot). Fires on
   every ownsSlot death path. The slot-steal victim (TR:1628) gets
   WRITE(prevSlot) under the same S1 bump.
3. **Tombstone = zero all 20 ints, then [18] = -2** (verified against
   all five consumer skip conditions, dossier-lifecycle section 2).
   GPU side needs nothing. sectionCount early-outs become
   liveSlotCount.
4. **snapshotSlot is per-Resident-OBJECT** (per arena copy), assigned
   at TR:1595, freed at the five death sites; the handover window
   legitimately holds two slots for one position.
5. **Publish point = drawSnapshot under LOCK** (unchanged seam):
   apply log entries newer than the back buffer's index, re-capture
   handles + regionData/retainedMasks (still small full snapshots),
   swap. Worker-thread mutations after the drain are next frame's -
   today's staleness contract verbatim, fence-lag safety unchanged.
6. **translucentSlotByPos must apply its inserts/removes from exactly
   the log range of the adopted swap, atomically with adopting it**
   (the one cross-swap ABA hazard, dossier-lifecycle section 4).
   rebuildRegionMap keys by regionId, never snapshot order.
7. **Free-list starts at 32k slots, grows through the full-rebuild
   fallback** (exhaustion = fallback event, like log overflow and
   dispose). rd120+retention ceiling ~112k entries -> 2^17 max.
8. **assert retained.put() == null** at TR:1081/1099 (the silent-leak
   edge the dossier flagged).


The frame-gap analysis convicted `TerrainResidency.drawSnapshot` +
`TerrainDrawer.rebuildRegionMap` as the #1 real-play smoothness lever:
mesheliumOpaque inflates 0.098 -> 3.07 ms p50 while turning, because
chunk streaming bumps `drawEpoch` essentially every frame and each bump
walks resident+retained (25.5k sections spinning at rd64), fills a fresh
`int[n*20]` under LOCK (~2 MB, ~280 MB/s of garbage at 133 fps - also
the recurring 14-17 ms hitch signature), and the drawer then rescans all
of it into two maps. Static frames cost zero (epoch cached), which is
why no static bench ever saw it.

VERIFY FIRST (one instrumented spin bench, now cheap): the conviction
rests on lag-corrected series alignment. The 2026-08-18 instrumentation
wave moved the stage-row boundary to LevelRenderer.render HEAD and added
compileUpload/encoderSubmit/levelRender/renderFrame brackets plus the
sectionCompiles series - one rd64 spin leg re-confirms mesheliumOpaque's
share with aligned rows before anyone builds this.

## Design (the delta-log double buffer)

Source of truth stays exactly as today (the LOCK'd maps). Add:

1. Each `Resident` gets a stable `snapshotSlot` int from a free-list at
   admission (resident OR retained - both live in the same flat view);
   release returns the slot. The packed entry for a section is written
   at `slot*20` by the SAME code `writeSnapshotEntryLocked` uses today.
2. A persistent change log (slot ints in a grow-only ring) appended by
   every mutator that today bumps `drawEpoch`, under the LOCK it
   already holds. O(1) per mutation. Slot-freeing mutations log the
   slot with a tombstone marker (entry [18] = -2 or count 0 - pick one
   the shaders/drawer already treat as not-drawable, verify).
3. TWO packed buffers ping-pong. Each remembers
   `lastAppliedLogIndex`. `drawSnapshot(knownEpoch)` under LOCK:
   applies log entries newer than the BACK buffer's index into the back
   buffer (touching only changed slots), swaps published/back, returns
   a DrawSnapshot VIEW over the published buffer (record fields become
   {buffer, liveSlotCount, maxSlot} - consumers iterate 0..maxSlot and
   skip tombstones, or a compact live-slot index array is maintained
   the same incremental way).
4. Overflow fallback: if the log grew past a threshold (world load,
   rd change, dispatcher reset), do today's full rebuild into the back
   buffer and reset both indices - the current behavior IS the
   fallback, so worst case equals today.
5. `rebuildRegionMap` + `translucentSlotByPos` on the drawer side get
   the same treatment: region map keyed on a separate regionEpoch
   (bumped only by RegionStore membership changes, which are rare);
   translucent slot map updated from the same delta log (slots carry
   their translucent-count in the entry - the map insert/remove is
   derivable per logged slot).

Safety invariants to preserve, with today's citations:
- The drawer may hold the PUBLISHED buffer across frames; only the back
  buffer is ever written. Nobody may hold a snapshot older than one
  swap - audit every DrawSnapshot escape (tests, counters, gametests)
  before building.
- Stale-by-a-frame draws stay safe by the FREE_FRAME_LAG fence argument
  quoted in drawSnapshot's javadoc today - unchanged, because published
  content always describes a state at most one swap old.
- Retained entries ride the same flat view with [19]=1 (wave-11); the
  admission/release paths for retention must log slots exactly like
  resident ones.
- regionData/retainedMasks snapshots (RegionStore) are SMALL (7 ints +
  8 ints per region, ~450 regions) - leave them as-is initially; they
  are not the cost.

Expected: turn-frame mesheliumOpaque back toward its static 0.1 ms, the
~280 MB/s garbage stream to ~zero (kills the 14-17 ms hitch class if
the GC-log leg convicts it), LOCK hold time on epoch bumps from ~ms to
~us. Bench gates: rd64 spin pair p50 + p99 + hitch count, static pair
unchanged (regression guard), the torture suite (handover, trim, world
hops - slot lifecycle edges), and the retained-horizon leg (retention
slots).
