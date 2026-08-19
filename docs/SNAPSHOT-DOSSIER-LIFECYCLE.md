All verification complete. Final dossier follows.

---

# DOSSIER: Resident lifecycle, tombstone, free-list sizing, ABA — for the incremental draw-snapshot build

All paths relative to `meshelium-private/`. TR = `src/main/java/com/deds/meshelium/terrain/host/TerrainResidency.java`, TD = `src/main/java/com/deds/meshelium/vk/TerrainDrawer.java`, RS = `src/main/java/com/deds/meshelium/terrain/host/RegionStore.java`, TASK = `src/main/resources/assets/meshelium/shaders/terrain.task`, DOC = `docs/DRAW-SNAPSHOT-INCREMENTAL.md`.

## 1. Resident lifecycle — object survival per transition

`Resident` is constructed at exactly ONE site: TR:1595 (upload landing in `drainPendingUploadsLocked`). All map ops on `resident`/`retained`: TR:1049, 1081, 1099, 1131, 1225, 1229, 1358, 1377, 1408, 1449, 1618, 1631.

| Transition | Mechanics | Entry object survives? | snapshotSlot implication |
|---|---|---|---|
| **Admission** | `new Resident` TR:1595 + `resident.put(mesh, r)` TR:1631; `drawEpoch++` TR:1641 | Created | Allocate slot here; write all 20 ints. ONLY creation site. |
| **Release → free** | `resident.remove(mesh)` TR:1049, then free tail TR:1107-1114 (`regionStore.remove` only `if (r.ownsSlot)` TR:1111) | Dies | Free slot + log tombstone. |
| **Release → handover retention** | TR:1078-1088: same object, `orphanedAtMillis` stamped (field), moved `resident`→`retained` (put TR:1081) | **Survives** (field mutation + map move) | Slot KEPT; log only the `[19]` 0→1 flip (and `[18]` unchanged — `markRetained` doesn't move region slots, RS:417-424). |
| **Release → distance retention** | TR:1089-1105, identical shape (put TR:1099) | **Survives** | Same as above. |
| **Slot steal, previous LIVE** | upload lands at same position: `addOrReplace` returns previousOwner (RS:196-204), caller sets `previous.ownsSlot = false` TR:1628 (field only; stays in `resident` until vanilla releases its mesh) | **Survives, slotless** | Entry keeps slot; log its `[18]` → −1. Old+new entries for the SAME position coexist until the old mesh's release (the promotion-lag window, TR:82-89). |
| **Supersede (previous RETAINED)** | TR:1610-1626: `previous.ownsSlot=false` TR:1619, `retained.remove` TR:1618, freed via epochs TR:1625 | Dies | Free slot + tombstone (in the same publish window the NEW entry's slot is written — two log records). |
| **Dig-out supersede** | `onSectionCompiledEmpty` TR:1129-1138: `retained.remove` + `freeRetainedLocked` | Dies | Free slot + tombstone. |
| **Eviction (age/pressure/disable)** | `evictRetainedLocked` TR:1346-1415 / `forceEvictRetainedLocked` TR:1441-1456: `it.remove()` (TR:1358/1377/1408/1449) + `freeRetainedLocked` TR:1160-1166 | Dies | Free slot + tombstone; bounded 256/pump (TR:327) or 64/failure (TR:329). |
| **World dispose** | `disposeAndReset` TR:1204-1256: `retained.clear()` TR:1225, `resident.clear()` TR:1229, regionStore/arena replaced TR:1232-1234 | All die at once | Full reset: free-list + log + both buffers rebuilt. This IS the designed overflow-fallback event (DOC:44-47). |
| **Pinned regrow / arena grow / append / trim** | `consumePendingGrowLocked` TR:454-492, `growArenaLocked` TR:1767-1843, `maybeTrimArenaLocked` TR:1741-1751 — NO resident/retained map ops, only `drawEpoch++` | All survive untouched | **Handle-only epoch bumps**: zero slot deltas, but `arenaBackingHandle`/`arenaBlockHandles`/`sectionRecordsHandle` change (captured under LOCK today, TR:823-831). The incremental publish must re-capture handles on EVERY swap, even with an empty log. Pinned regrow additionally nukes the drawer's cache (`cachedEpoch=MIN, snapshot=null` TD:1481-1482) — the publish path must serve a complete published view to a consumer with no prior state, no log replay. |

**Identity answer:** a `Resident`'s identity never changes for the same arena copy — retention flips and slot loss are field mutations on the surviving object (TR:179 `ownsSlot`, TR:198 `orphanedAtMillis`). The *logical section position* DOES change objects on every rebuild (new object TR:1595) and old+new coexist transiently, so **snapshotSlot must be per-Resident-object (per arena copy), not per position** — the handover window needs two simultaneous slots for one position. Assign at TR:1595's landing; free at the five death sites above.

**Golden fact for the log format:** packed ints `[0..17]` (coords, arenaAddr, bucketStarts, bucketCounts) are **immutable post-admission** — `bucketStarts/bucketCounts` are final and written only in the ctor (TR:210-211, read at TR:841-842; grep-verified no other writer). Post-admission changes are exactly: `[18]` and `[19]`, plus death. So a log record of just `slot` (full 20-int rewrite from the live maps at apply time) or `slot+kind` both work.

**The [18] trap — it is a LIVE read today.** `writeSnapshotEntryLocked` computes `[18]` via `regionStore.globalSectionIndex(...)` at snapshot-build time (TR:844-846; RS:399-406 returns `(region.id<<8)|slot`, −1 on owner mismatch/empty). The full rebuild refreshes it for free; the incremental version must LOG every mutation that changes any entry's gidx:
- slot steal → previous owner's `[18]` → −1 (log at TR:1619/1628);
- **`RegionStore.remove`'s swap-compaction moves the TAIL section into the hole** (RS:237-252) — the *moved, unrelated* section's compactedSlot changes, so its `[18]` changes. Log the moved entry too; find its Resident via `region.ownerByPos[movedPos]` (RS:250, 203-205). This fires on every ownsSlot free — miss it and occlusion-gated translucents gate against a stale stamp index.
- Region-id reuse is safe: an id releases only at `count==0` (RS:266-270) — no live entries carry it.
- Prefix resorts (`onTranslucentResort` TR:918-964, `pendingPrefixUploads`) mutate NO packed ints and bump no epoch — correctly outside the log.

**One edge to assert, not assume:** `retained.put` at TR:1081/1099 doesn't check for a displaced prior value. The map javadoc argues re-entry requires prior removal (TR:244-252); if that invariant ever broke, the displaced Resident's slot (and arena range) would silently leak. Assert put()==null in the slot code.

## 2. Tombstone verdict

Consumer skip conditions, verified:

| Consumer | Reads | Exact skip condition |
|---|---|---|
| cpuCull | packed entries, ALL of them — **no [18]/[19] check** | per-bucket `if (count == 0) continue` on `d[o+11..17]` (TD:2562-2564); nothing drawn iff all 7 bucketCounts are 0. Coords still frustum-tested (TD:2547) — harmless. |
| Retained translucent pre-pass | packed entries | `d[o+19]==0 \|\| d[o+4]<=0 \|\| d[o+18]<0` → skip (TD:2873). Note `d[o+4]` is bucketStarts[0]==translucent count — zeroing bucketCounts alone does NOT skip this. |
| Translucent membership (visible loop) | `translucentSlotByPos` map | built with include-filter `d[o+4] > 0 && d[o+18] >= 0` (TD:2510); lookup miss → skip (TD:2946-2948). Under the delta design the map removal comes from the log itself. |
| Occlusion dispatch list build | `regionData` only, NOT packed entries | `count <= 0` per region (TD:1817-1820); identical in the BFS-mask build (TD:2340-2343). Untouched by the packed-entry tombstone — region `count` is already decremented by RS.remove. |
| Task shader section walk | GPU 32-B section records, NOT the CPU snapshot | `if (header.w != 0)` (TASK:228) — header.w = terrainAddress, 0 = tombstone ("a live section can never have terrainAddress 0, quad 0 is reserved", TASK:225-227). The GPU-side tombstone already exists and is maintained independently: RS.remove zeroes the vacated mirror slot (RS:254-257) and emptied regions queue whole-block zero-fills (RS:266-270, 284-293). Every entry-death path with `ownsSlot` calls `regionStore.remove` (TR:1111-1113, TR:1164); slotless entries' records already belong to the new owner — correctly untouched. |

**No single candidate covers all CPU consumers:** `[18]=−1/−2` alone fails cpuCull (never checked there); `bucketCounts=0` alone fails the retained pre-pass (checks `[o+4]`/`[18]`/`[19]`, not counts); a coordinate sentinel only works if the frustum test reliably fails — not guaranteed, rejected.

**Recommendation — THE tombstone: write the full 20-int entry as zeros, then set `[18] = −2`.** That gives: all bucketCounts 0 (cpuCull draws nothing, TD:2562-2564), `[o+4]=0` AND `[18]<0` (translucent map excludes, TD:2510), `[19]=0` AND `[o+4]<=0` AND `[18]<0` (retained pre-pass skips on all three clauses of TD:2873 — triply safe). −2 vs −1 costs nothing (every consumer tests `>= 0` / `< 0`: TD:2510, 2873, 2903, 2965) and makes "dead slot" distinguishable from "live but slotless" in dumps — matching the design note's own `[18] = -2` suggestion (DOC:31-32). Cost is 20 int stores per tombstone application, same class as a live-entry write. GPU-side needs nothing: record zeroing already rides RS.remove on every death path.

Also: today's `sectionCount==0` early-outs (TD:1677, TD:2789) must become **liveSlotCount**, not maxSlot — an all-tombstone buffer must read as empty.

## 3. Free-list sizing

Max simultaneous entries = `resident.size() + retained.size()` (the flat view, TR:810).

- Measured: **26,990 resident sections, 1650 MB arena at rd64 spinning** (docs/PERFORMANCE.md:672-677) ⇒ ~61 KiB/section average; design note's own figure 25.5k (DOC:7).
- rd120 grid is 3.49× rd64's columns (241²/129²; "the world is 3.5x the columns of rd64", docs/SPEEDUP-CANDIDATES-2026-08.md:57-58) ⇒ **~94k resident** fully streamed at rd120. (The owner's measured rd120 arena of ~1.1 GB, SPEEDUP-CANDIDATES:56, is a partially-streamed real-play figure ≈ 18k sections — the extrapolation is the honest ceiling.)
- Retained on top: default OFF since 1.0.0 (docs/PERFORMANCE.md:739-744) — normal play adds only transient handover copies (one per queued-successor rebuild, bounded by the upload backlog draining at 16 MiB/pump, TR:144). Armed, retained is capped by pressure: 85% of arena-ceiling quads (TR:323, 1396-1403) and 90% of region ids (TR:325); rd120 pins 14,336 regions (MesheliumScaling.java:80). On the 16 GiB dev card (ceiling = 50% of largest DEVICE_LOCAL heap, TR:118-120 ⇒ 8 GiB), the arena bound dominates: 6.8 GiB / 61 KiB ≈ **~112k entries total**, regardless of resident/retained split.
- **Recommendation:** free-list and packed buffers sized to next-pow2 above the live need with doubling growth; 2^17 = 131,072 slots covers the rd120+retention worst case at 80 B/slot ⇒ 10.5 MB per buffer (×2 = 21 MB, vs today's ~2 MB transient per rebuild ×133/s). Start at 32k (rd64 spin fits with headroom, 2.6 MB/buffer) and treat free-list exhaustion as a grow event that routes through the same full-rebuild fallback as log overflow (DOC:44-47) — growth is rare (world load, rd raise) and those moments already take the fallback.

## 4. ABA across slot reuse — verified consumer-side

**Answer: safe within a publish window by construction; the one real hazard is cross-swap slot-index holders on the drawer side, and only one exists.**

- The drawer is the ONLY DrawSnapshot consumer (grep: TD:1646 is the sole `drawSnapshot()` call; no gametest/test escapes; RS mention is javadoc-only RS:339). It caches statically (TD:816-817), refreshes at TD:1646-1652 on the render thread, and the translucent pass reuses the same frame's cached object (TD:2788). Same thread, LOCK-serialized — no cross-thread buffer handoff; the GPU never reads this CPU buffer.
- Within a publish window: free(S) + readmit at S both append to the log; the published buffer still shows the OLD entry until the next `drawSnapshot` applies both records *in append order* to the back buffer and swaps. Both ping-pong buffers apply the same log sequentially from their own `lastAppliedLogIndex`, so tombstone-then-rewrite collapses correctly in each. The consumer observes pre-window state or post-swap state, never a mix. **Slot reuse therefore needs NO fence delay** — unlike arena addresses.
- The stale-content-side is today's argument unchanged: a freed entry's arena range is fence-parked FREE_FRAME_LAG=3 pumps (TR:1114, 1169-1178, 1486-1500), and any free bumps the epoch so the drawer's stale window after a free is exactly one frame < 3 pumps (drawSnapshot javadoc TR:798-804). An unchanged epoch means nothing was freed, so an arbitrarily old cached snapshot is still safe.
- **The cross-swap hazard:** `translucentSlotByPos` values are snapshot indices (TD:2511). Today it is rebuilt whenever a new snapshot is adopted (TD:1650), so slot meaning and map always move together. The incremental version must apply the map's inserts/removes from **exactly the same log range** as the buffer swap being adopted, atomically with adopting it — otherwise a stale map value can dereference a reused slot holding a different section. All other slot-index holders are frame-local or epoch-keyed: `transDrawnMark` cleared per frame (TD:2994-2998), cachedCull memo invalidated on `ccEpoch != cachedEpoch` (TD:637), phase-B skip keyed on cachedEpoch (TD:2234), `regionMeta`/`regionOrigins` hold region ids not section slots.
- Occlusion-gate staleness of `[18]` (TD:2902-2903, 2964-2965) one swap old is today's exact behavior and fails open — stamp staleness only ADDS draws (TASK:247-251).