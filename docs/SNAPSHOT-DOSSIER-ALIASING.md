All evidence gathered. The dossier follows.

---

# ALIASING AUDIT: TerrainResidency.DrawSnapshot — every consumer, every escape

All paths relative to `c:/Users/mrszi/Documents/Projects/Attack Of the B-Team 1.26.2/meshelium-private/src/main/java/com/deds/meshelium/` unless noted. TR = `terrain/host/TerrainResidency.java`, TD = `vk/TerrainDrawer.java`, RS = `terrain/host/RegionStore.java`.

## 0. Complete alias census

**Producer (sole):** `TerrainResidency.drawSnapshot(long)` TR:805-833. Builds `data[n*20]` under `LOCK` (TR:806-822), captures `arena.backingHandle()`/`blockHandles()` (TR:823-824), then `regionStore.snapshotRegions()` + `snapshotRetainedMasks()` in the SAME lock hold (TR:825-828). Entry writer `writeSnapshotEntryLocked` TR:835-849 (fields: [0-2] coords, [3] arenaQuadAddr, [4-10] bucketStarts, [11-17] bucketCounts, [18] globalSectionIndex via `regionStore.globalSectionIndex` TR:844-846 / RS:399-406, [19] retainedFlag).

**Caller (sole):** TD:1646 `TerrainResidency.drawSnapshot(cachedEpoch)` inside `drawOpaqueInner`, render thread only. Grep of the whole repo finds no other caller: only TD:1646, the decl TR:805, and a javadoc xref TR:272. **Zero gametest/counter/debug consumers touch DrawSnapshot or its arrays** — grep of `src/gametest` for `DrawSnapshot|drawSnapshot|snapshotRegions|retainedMasks|STRIDE` matches only an unrelated `TerrainVertexCodec.QUAD_STRIDE` (MesheliumTerrainDataTest.java:310). Gametests observe only `Counters`/volatile probes (e.g. `lastRetainedMaskSections` TD:723).

**Storage (sole):** `private static TerrainResidency.DrawSnapshot snapshot` TD:817. Written at TD:1648 (refresh), nulled at TD:1418 (`onDispatcherDispose`) and TD:1482 (`onPinnedRegrow`). Read at TD:1652 (opaque, same call) and TD:2788 (`drawTranslucentInner`, comment: "refreshed by drawOpaque this frame"). No other read/write of the field exists in the file or repo.

**Derived (index/copy, not array aliases), all render-thread, all epoch-keyed:**
- `regionSlotByKey` (region key → regionData index r) TD:868, rebuilt only in `rebuildRegionMap` TD:2489-2495; cleared implicitly by rebuild.
- `translucentSlotByPos` (section coords → entry index) TD:832, rebuilt TD:2505-2513, cleared at dispose TD:1433 and regrow TD:1488.
- Wave-12 cachedCull memo: `ccDispatched/ccSig/ccOverflowThisFrame` + the *contents* of `regionMeta/regionOrigins/occRasteredRegions` + persistent list bytes survive across frames on a hit (TD:1800-1807), gated by `ccEpoch != cachedEpoch → miss` (TD:637), invalidated by bfs/cpuCull frames (TD:2287, TD:2524), dispose (TD:1421), regrow (TD:1483).
- Per-frame only: `retTransKeys` (distance²<<32|entry index, TD:840-841, built+consumed in one translucent pass), `transDrawnMark/transDrawnList` (cleared same frame TD:2995-2998).
- `pushDescriptors`/`pushTranslucentDescriptors` receive `snap.arenaBlockHandles()` and consume it entirely within the call into stack-allocated descriptor writes (TD:3436-3505) — no retention.

**Conclusion of census: exactly one holder, one producer call site, one thread.** Docs mention it (docs/DRAW-SNAPSHOT-INCREMENTAL.md, docs/MULTIBUFFER-VRAM-PLAN.md, docs/VANILLA-FRAME-PATH.md) but no code escape exists.

## 1. Maximum age of a held snapshot, and what bounds it

- **Object lifetime: unbounded.** The epoch cache is the design — a quiescent world returns null forever (TR:807-809) and TD holds the same object for the world's life. During coverage-passive/broken frames drawOpaque returns before the refresh (TD:1581-1590), so the static can also hold an arbitrarily old object *unconsumed* (memory-alive: ~2 MB at rd64) until dispose/regrow nulls it.
- **Consumed staleness: strictly < 1 frame + intra-frame.** Frame order on the render thread: `beginFrame` (LevelRenderer.render HEAD, TD:1569) → pump (`afterVanillaTerrainUpload`, right after `uploadTerrainBuffersToGpu`, inside vanilla's dispatcher.lock window — vk/MesheliumTerrainPump.java:19-22,51-73) → drawOpaque refresh (TD:1646) → drawTranslucent reads the same static (TD:2788). All pump-side epoch bumps (upload drain TR:1641, evictions TR:1361/1380/1412/1452, trim TR:1751, arena grow TR:1798/1841, pinned grow TR:491, dispose TR:1213) land *before* the refresh. The only mutators that can bump `drawEpoch` after the refresh are build-worker hooks: `onMeshReleased` (frees/retention, TR:1042-1115, under vanilla's copyLock with LOCK innermost — RS:37-41) and `onSectionCompiledEmpty` (TR:1129-1138). Those become visible at the *next* frame's refresh; this frame's opaque+translucent consume the frame-start view.
- **What bounds safety of that staleness:** the FREE_FRAME_LAG=3 fence — freed/parked arena ranges cannot be `arena.free`d until 3 pumps later (TR:136, javadoc TR:800-803, `parkAddrLocked` TR:1169-1178, `releaseExpiredFreesLocked` TR:1486-1500); grow/trim keep the outgoing backing alive and coherent for FREE_FRAME_LAG frames (TR:1836-1841); GPU section records may lag but only ever *skip*, never stale-draw (TR:728-735). Margin today: 1 frame of consumed staleness vs a 3-pump fence = 2 frames of headroom. **A ping-pong buffer that can be consumed up to 1 swap old still sits comfortably under the fence.**

## 2. Does any consumer read a snapshot OTHER than the latest? **No — with one same-frame coupling constraint.**

Every consumer reads either the epoch-latest object (TD:1652) or the same object later in the same frame (TD:2788). The cachedCull hit path reuses *derived* arrays from a previous frame, but a hit requires `ccEpoch == cachedEpoch` (TD:637) — same epoch ⇒ bit-identical regionData ⇒ logically the latest. `occRasteredRegions` (populated on the occlusion miss frame TD:1813/1840, reused on hits TD:1805-1807) is consumed by the translucent gate the same frame (TD:2903, TD:2965) — again same-epoch.

**Double-buffering is therefore safe**, with these constraints the implementation must keep:
- (a) **One swap point per frame, before both passes.** `drawTranslucentInner` must see the exact buffer opaque used this frame: entry index maps (`translucentSlotByPos`), `occRasteredRegions`, `occGateStamp32`/`occCurStampsHandle` (TD:2051-2053) and entry [18] gate values all assume it. Since the only `drawSnapshot` call is TD:1646, swaps naturally happen only there — preserve that single-caller property.
- (b) **Back-buffer writes happen only inside that same render-thread call**, i.e. strictly after the previous frame's last read of that buffer (published 2 swaps ago) — no cross-thread reader exists to race.
- (c) `rebuildRegionMap` must run atomically with the swap (today: TD:1647-1651), else `translucentSlotByPos`/`regionSlotByKey` indices dangle into the newly published buffer.

## 3. Iterate-ALL vs index-by-slot; tombstone-skip cost

**Entry-array (`data`) consumers:**

| Consumer | Frequency | Access pattern | Fields |
|---|---|---|---|
| `drawCpuCulled` TD:2534-2591 | every frame, **escape hatch only** (property or `sectionRecordsHandle==0`, TD:1683) | iterates ALL n entries | [0-3], [4-10], [11-17]; never [18]/[19] |
| translucent retained pre-pass TD:2866-2890 | **every Meshelium-owned frame** (default path) | iterates ALL n entries | [19], [4], [18], [0-2]; then draw loop re-reads [0-4],[18] by sorted index TD:2892-2929 |
| translucent visible loop TD:2941-2992 | every owned frame | **index-by-slot** via `translucentSlotByPos` | [3], [4], [18] |
| `rebuildRegionMap` TD:2488-2513 | epoch change only | iterates ALL n entries + ALL regions | entries: [0-2], [4], [18]; regions: rd[1..3] |
| `drawOpaqueInner` TD:1665-1683 | every frame | header fields only | `arenaBackingHandle`, `sectionCount`, `sectionRecordsHandle` |

**Region-array consumers (never touch `data`):** `drawOcclusionCulled` iterates all regions TD:1814-1861 (fields rd[0],rd[1-3],rd[4],rd[5],rd[6]); `drawTaskCulled` iterates all regions TD:2337-2386 + `retainedMasks[r*8+w]` TD:2355-2361; both already **skip `count<=0` regions** (TD:1818-1820, TD:2341-2343) — region tombstone-skipping is precedent in the hot paths today.

**Tombstone cost estimate** (design: free-list slots, dead slots skipped): at rd64, n≈25.5k live (doc:7); with a free-list high-watermark of, say, 1.25×, ~6k dead slots. Per-frame full scans that pay the skip: the retained pre-pass (1 int load + branch each; its first test `d[o+19]==0` at TD:2873 already rejects ~all live entries, so a tombstone encoding with [19]=0 merges into the existing branch — ~0.5-1 ns/slot ⇒ ~3-6 µs/frame worst) and, on the hatch only, cpuCull — where the skip MUST precede the per-entry `new AABB` + `frustum.isVisible` (TD:2547; ~30-50 ns each, and note sectionCount-driven `new AABB` allocation per entry) or tombstones cost 100× more there. `rebuildRegionMap` full-scan is being replaced/delta-fed anyway. Net: tombstone skip is noise (<0.01 ms) against the 2.8 ms being recovered. **Tombstone encoding caveat:** cpuCull never reads [18]/[19] — it is gated purely by bucketCounts [11-17]; the translucent visible loop reaches a tombstone only through the map (and with count [o+4]=0 draws nothing but still increments `sectionsDrawn` TD:2992 — counter skew unless the map delta-removes tombstoned slots). Safest tombstone = zero the whole entry (count fields 0 ⇒ cpuCull pushes no runs, pre-pass rejects at [4]<=0, maps drop it) — matches "count 0" option in doc:31-32.

## 4. regionData/retainedMasks vs entry array — the ONE-lock-hold guarantee and its dependents

The guarantee: TR:825-828 — both `snapshotRegions()` and `snapshotRetainedMasks()` are taken in the same LOCK hold with no mutation between, giving identical region iteration order (RS javadoc:427-433: back-to-back under the lock; fastutil map order stable while unmutated). Additionally the entry writer's [18] (`globalSectionIndex`, TR:844-846) is computed in the SAME hold, so entries ↔ regionData ↔ retainedMasks are one consistent cut.

Who relies on it:
1. **retainedMasks[r] == region r of regionData** — `drawTaskCulled` TD:2355-2361 ORs `retainedMasks[r*8+w]` into masks for the region at `rd[r*REGION_STRIDE]`; a misalignment ORs another region's retained bits in: phantom draws + wrong `lastRetainedMaskSections` (TD:2361/2393), which the retained-horizon leg asserts on (MesheliumTerrainDrawTest.java:559-565, 598-601).
2. **regionSlotByKey index r → regionData/regionMasks/retainedMasks row r** — built TD:2489-2495 from the same snap, consumed TD:2312-2317 (mask fill) and TD:2355-2357. Also sizes `regionMasks` from `regionCount` (TD:2496-2498, fill at TD:2304).
3. **Entry [18] ↔ regionData id set ↔ rd[ro+4] count** — occlusion dispatch lists regions from regionData (TD:1816-1858); the section raster stamps `regionId*256+slot` sized by `count` (TD:1970-1980); the translucent gate reads entry [18] and requires `occRasteredRegions.contains(gidx>>>8)` (TD:2901-2907, 2963-2969). If [18] were from a different cut than regionData: a gate against a region never rastered ⇒ `gate=NO_GATE` fail-open (harmless), but a slot ≥ that frame's `count` ⇒ gated draw against a never-written stamp ⇒ **section invisibly dropped**. This is the sharp edge.
4. **Incremental-design trap found by this audit:** [18] is a JOIN of resident and RegionStore state, and RegionStore mutations change *third parties'* [18] without touching their Resident: (a) `remove` swap-moves the tail record — the moved section's compacted slot changes (RS:237-252), so the tail owner's `globalSectionIndex` changes; (b) `addOrReplace` slot steal flips the previous owner to −1 (RS:196-205, TR:1610-1629). Today's full rebuild recomputes [18] for everyone; **the change log must log the moved-tail owner's slot (via `region.ownerByPos[id2pos[last]]`) and the stolen-from owner's slot**, or their packed [18] goes stale and item 3's drop mode fires. Region-id reuse is safe (a region only releases its id at count 0, all entries already removed, RS:266-271).
5. Design's regionEpoch split (doc:45-50) is sound w.r.t. order: fastutil order changes only on structural mutation (add/remove/rehash = membership change), which is exactly what bumps regionEpoch; per-section count/occAABB changes don't reorder. But regionData *content* (rd[ro+4] count, occMin/occMax) changes on every section add/remove — it must keep rebuilding per epoch (doc:63-65 keeps it full, correct).

## 5. Gametest legs that exercise snapshot edges (the implementation's test plan)

All in `src/gametest/java/com/deds/meshelium/gametest/client/`:
- **Handover** (slot steal + retained-supersede + [19] flip churn): `MesheliumLifecycleTortureTest.assertRebuildHandover` :165 (invoked :126; edits-not-allChanged rationale :179-191; asserts `handoverRetained` rises :193-200, drains :202, no drops :203, shot `82_meshelium_rebuild_handover` :212).
- **Trim** (arena handle era swap ⇒ epoch republish TR:1751): `assertArenaTrim` :240 (invoked :127; trim fires :251-256, capacity drops but ≥ extent :257-265, fence retire :269-271, regrow storm :274-285).
- **World hops** (dispose → snapshot null TD:1418 + fresh store): `assertWorldHops` :605 (invoked :125; dispose observed per hop :614-620, draw resumes :622-623). Coverage-guard leg :633 additionally exercises the unconsumed-stale-static path (passive freeze :696-704).
- **Retained horizon** (retained entries in the flat view, [18]/[19], retainedMasks): `MesheliumTerrainDrawTest.assertRetainedHorizon` :516 (invoked :220; retained>0 after rd drop :532-547, mask-attribution probe under bfsOnly :555-569, horizon shots A0/A1 :578/:591, disable/age/no-limit eviction sweeps :581-652 — every eviction is a `drawEpoch++` slot-free, TR:1361/1380/1412).
- **rd regrow** (pinned grow ⇒ records grow-and-copy, `onPinnedRegrow` drop TD:1471-1509, epoch republish TR:491): `assertExtendedRenderDistance` :745 (invoked :236-239; live grow bars :814-850, growth-beyond-horizon :852-935, honest guard-trip alternative :896-911) and the fresh-world pin `assertRejoinAppliesFullBudget` :418.
- Also load-bearing for the drawer-side maps: `assertTranslucentParity`/`assertResortsApplyWithoutReencode` (invoked :212-213) exercise `translucentSlotByPos` + prefix permutation; `MesheliumBenchmarkTest` supplies the rd64 spin/static bench gates the design note names (doc:70-73).

## Implementation-critical facts, condensed

1. Single producer call site (TD:1646), single holder (TD:817), single thread — double-buffering has no hidden reader; the doc's "audit every escape" comes back clean.
2. All within-frame consumers (translucent, occlusion carry, index maps) need the SAME published buffer as this frame's opaque — swap only inside the TD:1646 call, rebuild maps in the same breath (TD:1647-1651), and keep dispose/regrow nulling both (TD:1418-1433, TD:1481-1488).
3. Consumed staleness today <1 frame vs a 3-pump fence — one extra swap of latency is within margin (TR:136, TR:1486-1500, TR:1836-1841).
4. Change log must capture third-party [18] changes: swap-remove tail move (RS:237-252) and slot steal (TR:1610-1629) — else the occlusion-gated translucent path silently drops sections.
5. Tombstone = fully zeroed entry (count-0 semantics) so cpuCull ([11-17]-gated, TD:2561-2563), the pre-pass ([4]/[19]-gated, TD:2873) and both maps reject it without new branches; skip cost ~µs/frame; put the skip before cpuCull's AABB/frustum work (TD:2547).
6. Region snapshots stay full-rebuild per epoch (small: 7+8 ints × ~450 regions, doc:63-65); regionEpoch-keyed `regionSlotByKey` is sound because fastutil order only changes on structural (membership) mutation — but any byKey add/remove/rehash MUST bump regionEpoch, including removes that shift unrelated keys' probe positions.
7. Epoch-bump sites the log must cover (= today's `drawEpoch++`): TR:491, 1085, 1103, 1108, 1137, 1213, 1361, 1380, 1412, 1452, 1641, 1751, 1798, 1841. Build-thread sites (1085/1103/1108/1137) append under LOCK from vanilla's copyLock — the log append inherits that safety.