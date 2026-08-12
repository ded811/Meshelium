# Multiplayer retention recon (pre-wave-16) — why the owner's server "forces the render distance down"

> **STATUS 2026-08-11: THE APPROACH THIS DOCUMENT DESIGNS WAS REVERTED.**
> Waves 16 and 17 widened PRESENTATION (fog and far plane) so retained
> terrain would be visible past a server's radius. On the owner's real
> server that produced worse artifacts than it fixed: terrain visible
> past the fog that never unrenders, and an extended render distance
> that appears not to work at all. Owner directive: "can we maybe undo
> our way of doing it and look how other mods achive this? because it
> seems like you introduced some issues." Both waves are reverted.
>
> WHAT REMAINS TRUE AND VALUABLE HERE is the bytecode: vanilla's fog
> wall and its COMPILABLE SET are the same cylinder, both sized by
> getEffectiveRenderDistance(), and SectionOcclusionGraph.getRelativeFrom
> gates on BOTH a horizontal disc (ip 8-19) and a vertical span
> (ip 20-44). Any future approach must respect that: terrain the client
> was never sent is terrain vanilla never compiles, and no presentation
> trick creates it. The fix belongs in the DATA layer (real chunks the
> client actually holds), which is how Bobby and Distant Horizons do it.


Recon, 2026-08-10, RECON ONLY (no code changed). Method: `javap -p -c`
against the real 26.2 merged jar
(`attack-of-the-bteam-1.26.2/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2.jar`),
plus a source audit of Meshelium's own wave-10/11/13 code. Every vanilla
claim below is bytecode-cited; where a claim rests on an existing doc's
census, the doc is named.

**As-built note (2026-08-10):** the title still says "pre-wave-16" because
everything from here to the recommendation is the recon exactly as written
on the day. Wave 16 has since been built. What actually shipped, where it
deviates from the recommendation and why, the visible-staleness decision and
its two cosmetic windows, and three corrections to this recon's own bytecode
claims all live in the **"Wave 16 as BUILT"** section near the bottom of this
file, just before UNVERIFIED.

**Owner playtest facts under investigation (2026-08-10):** Meshelium ACTIVE
on a real server at 1200+ fps, but "the server im on is forcing my render
distance down… i wanted that for servers like this", and (second report)
"on the server it was shrinking my render distance, like i had the short
render distance fog". Wave-11 retention verified working in SP shrink
tests (rd 16 → 8, counters + retained-mask probe + screenshot).

## TL;DR — the verdict up front

**The wave-11 mesh lifecycle is NOT broken on vanilla-behaving servers.**
The MP forget-chunk path frees no section meshes (finding 1), forgotten
chunks are structurally unreachable by the rebuild scheduler on a
steady-radius server (finding 1.4), a server radius change funnels through
exactly the wave-11-visible `releaseAllBuffers`/`reset()` path (finding 2),
and dimension change/disconnect hit the per-world `dispose()` boundary as
designed (finding 5.5). The working hypothesis ("MP unload flows through a
release path wave 11 never hooks") is **REFUTED** — there is no such path
on the direct packet flow.

**What actually killed the horizon is PRESENTATION, not lifecycle:** the
client's fog wall and projection far plane are both computed from
`Options.getEffectiveRenderDistance()` = `min(option, serverRenderDistance)`
(finding 3a). On a radius-8 server that is a fully-opaque render-distance
fog band ending at **128 blocks** and a far plane at 512 blocks — retained
terrain is retained, dispatched, and drawn, then fogged to the fog color
and/or clipped. That is byte-for-byte the owner's "short render distance
fog". Meshelium's own terrain.frag applies vanilla's fog verbatim (wave-4
parity decision), so Meshelium's draws are equally hidden. **Meshelium's own
clamp-back code is innocent** (finding 3b): it never clamps at or below
vanilla's 32 and never touches the server radius.

Wave 16 is therefore a **fog/far-plane wave first** (2 one-line
`@Redirect`s: presentation follows the OPTION while retention draws; chunk
DATA stays server-limited), plus one cheap lifecycle hardening for
non-vanilla servers that forget in-range chunks (finding 5.4), plus an
honest re-verification of the SP test's visual claim (finding 4.3).

---

## Finding 1 — the MP unload path, end to end: it frees NOTHING

### 1.1 The packet and its handler

The clientbound forget packet in 26.2 is
**`net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket`**
(record carrying a `ChunkPos`; siblings verified in the jar:
`ClientboundSetChunkCacheRadiusPacket`, `ClientboundSetChunkCacheCenterPacket`,
`ClientboundSetSimulationDistancePacket`, `ClientboundChunkBatchStart/FinishedPacket`).

`ClientPacketListener.handleForgetLevelChunk(ClientboundForgetLevelChunkPacket)V`
does exactly three things (ip 12–23, 26–34, 37–39):

1. `ClientChunkCache.drop(packet.pos())`;
2. `ClientDebugSubscriber.dropChunk(pos)` (debug only);
3. `queueLightRemoval(packet)` → `ClientLevel.queueLightUpdate(runnable)`;
   the runnable (`lambda$queueLightRemoval$0(ChunkPos)V`) is
   `LevelLightEngine.setLightEnabled(pos, false)` (ip 8–11), per light
   section `queueSectionData(BLOCK/SKY, SectionPos, null)` (ip 19–57), per
   level section `updateSectionStatus(SectionPos, true)` (ip 68–92).

### 1.2 The chunk-cache side: set bookkeeping + entity/BE cleanup only

- `ClientChunkCache.drop(ChunkPos)V`: `Storage.inRange` gate (ip 0–15),
  `Storage.getIndex`/`getChunk` (ip 19–40), `isValidChunk` (ip 44–56),
  then `Storage.drop(index, chunk)` (ip 59–65).
- `ClientChunkCache$Storage.drop(ILnet/minecraft/world/level/chunk/LevelChunk;)V`:
  CAS the array slot to null (ip 0–7), `chunkCount--` +
  `onChunkRemoved(chunk)` (ip 13–25), `ClientLevel.unload(chunk)`
  (ip 28–36).
- `Storage.onChunkRemoved`: adds the chunk to
  `removedLoadedChunks[updatingSetsIndex]` (ip 5–18) and every one of its
  sections to `removedEmptySections` (ip 22–73). Pure set bookkeeping.
- `ClientLevel.unload(LevelChunk)V`: `LevelChunk.clearAllBlockEntities()`
  (ip 0–1), `LevelLightEngine.setLightEnabled(pos, false)` (ip 4–16),
  `TransientEntitySectionManager.stopTicking(pos)` (ip 19–27). **No
  renderer call of any kind.**

### 1.3 The renderer side: the sets go into the BFS, and only the BFS

`LevelExtractor.extract` copies the four tracking sets into
`ChunkLoadingRenderState` and flips them (ip 324–395;
`flipUpdateTrackingSets` at ip 393–395). They are consumed by
`SectionOcclusionGraph.update(CameraRenderState, int, ChunkLoadingRenderState)`:

- ip 0–9 → `updateLoadedChunks(added, removed)` — bytecode is literally
  `loadedChunks.addAll(added); loadedChunks.removeAll(removed);` (ip 0–17
  of that method). **No reset, no release, no node removal, no scheduled
  update.**
- ip 12–21 → `updateEmptySections` — schedules BFS propagation for
  sections that STOPPED being empty; nothing for removals.
- Chunk removal schedules NO graph work: `needsFullUpdate` is untouched;
  `runPartialUpdate` propagates only from `loadedExpectedChunks` and
  `sectionsToPropagateFrom` (ip 24–153). A forgotten chunk's RenderSection
  therefore stays in the graph and in `visibleSections` until the next
  full update (camera-movement-driven, `invalidateIfNeeded`) rebuilds the
  traversal without it — vanilla stops DRAWING it then, but its
  `CompiledSectionMesh` and uber-buffer allocations are untouched.

**Conclusion (re-proves recon Q4.3 / the wave-11 note for the MP packet
flow):** `handleForgetLevelChunk` never reaches `RenderSection.reset()`
nor `releaseSectionMesh` — directly or transitively. The wave-3 census
stands: the only mesh frees in the game remain (a) rebuild replacement /
cancelled-compile (`checkSectionMesh` ip 84–86, `doTask` ip 209/343), (b)
`reset()` via `setSectionNode` (reposition) and via
`ViewArea.releaseAllBuffers()`, (c) `SectionRenderDispatcher.dispose()`
(docs/VANILLA-SECTION-BUILD.md Q3.4 + wave-11 note, re-verified against
this jar 2026-08-10).

### 1.4 The one INDIRECT free the forget path can theoretically feed — and why vanilla servers can't fire it

The light removal (1.1.3) reaches the render side as *dirty flags*:
`LayerLightSectionStorage.swapSectionMap()` calls
`LightChunkGetter.onLightUpdate(layer, sectionPos)` (ip 88) →
`ClientChunkCache.onLightUpdate` → `LevelExtractor.setSectionDirty(x,y,z)`
(ip 0–18) → `SectionUpdateTracker.setDirty`. Light runnables and updates
run per tick in `ClientLevel.update()` (`pollLightUpdates` ip 13–14,
`LevelLightEngine.runLightUpdates` ip 26–33).

The rebuild scheduler is the dirty loop in `LevelExtractor.extract`
(ip 441–584): it iterates **`LevelRenderer.visibleSections()`** (ip
441–470), takes `getDirtyState(sectionNode)` + `isDirty()` (ip 475–499),
gates on `hasAllNeighbors` **only when the mesh is UNCOMPILED** (ip
502–532 — a COMPILED dirty section is scheduled unconditionally), then
snapshots a region **at extract time**:
`RenderRegionCache.createRegion(level, node)` (ip 556–570) →
`SectionUpdateRenderState` → compile.

Two bytecode facts make this a real (if narrow) kill path:

- `RenderRegionCache.createRegion` **cannot fail**: each of the 27
  `SectionCopy`s comes from `Level.getChunk(x, z)` (lambda ip 0–5), which
  is `getChunk(x, z, FULL, true)` (`Level.getChunk(II)` ip 3–6 →
  `LevelReader.getChunk(II,Status)` default, `iconst_1` at ip 4) — and
  `ClientChunkCache.getChunk(..., true)` returns the shared **emptyChunk**
  for a missing chunk (ip 43–52). A scheduled rebuild of a forgotten
  section therefore compiles to EMPTY, releasing the old mesh OUTSIDE any
  `reset()` bracket (`doTask` empty path, ip 209 — a plain wave-3b free)
  AND firing Meshelium's `onSectionCompiledEmpty` retained-copy drop
  (wave-11 note, "the empty-compile hole and its plug").
- BUT the dirty flag can never be planted on a vanilla server:
  `SectionUpdateTracker.setDirty` is a no-op outside its storage
  (`RotatingSectionStorage.getValue(III)` returns null unless
  `containsSection` = within `centerSectionPos ± radius`, ip 0–11 /
  containsSection ip 18–52), and the tracker's radius is
  `lastViewDistance` = `getEffectiveRenderDistance()` (`allChanged` ip
  23–47). A vanilla server only forgets chunks leaving its tracked view —
  distance ≥ serverRadius+1 > min(option, serverRadius) = tracker radius —
  so **forgotten chunks are outside the tracker AND outside the ViewArea
  grid**: un-dirtyable, un-schedulable, un-freeable. Their meshes ride the
  wave-11 reposition/retain path exactly as designed.

The path IS reachable when a *non-vanilla* server (plugin world resets,
anti-xray reloads, Paper tricks) forgets a chunk INSIDE the player's
effective radius: drop → light removal → in-tracker dirty → still in
`visibleSections` (finding 1.3: removal never prunes the list promptly) →
empty rebuild → plain free + retained-drop. Wave-16 hardening for this is
finding 5.4 / recommendation R3.

## Finding 2 — the server view-distance packets

### 2.1 `ClientboundSetChunkCacheRadiusPacket`

`ClientPacketListener.handleSetChunkCacheRadius` (ip 12–45), in order:

1. stores `serverChunkRadius` (field, ip 12–18);
2. **`Options.setServerRenderDistance(radius)`** (ip 20–31) — a bare
   putfield (`Options.setServerRenderDistance(I)V` ip 0–2); from this
   instant `Options.getEffectiveRenderDistance()` returns
   `serverRenderDistance > 0 ? min(option, serverRenderDistance) : option`
   (bytecode ip 0–43, re-cited from EXTENDED-RENDER-DISTANCE.md Q1);
3. `ClientChunkCache.updateViewRadius(radius)` (ip 34–45): compares
   `calculateStorageRange` = `max(2, radius) + 3` (ip 0–7 of that helper)
   against the current storage ring; on change builds a NEW `Storage` and
   copies only chunks `inRange` of the new ring via `Storage.replace`
   (ip 18–143), then swaps (ip 146–149). **Chunks outside the new radius
   are silently discarded — no `onChunkRemoved`, no `ClientLevel.unload`,
   no `removedLoadedChunks` entries** (the copy loop only ever calls
   `replace` on kept chunks; the old storage's pending sets die with it).

The RENDERER follows through the effective-rd seam, not the packet:
`LevelExtractor.extract` HEAD compares `getEffectiveRenderDistance()` to
`lastViewDistance` (ip 0–18) → `allChanged()`:

- `lastViewDistance = effective` (ip 23–28), **fresh**
  `SectionUpdateTracker(level, effective)` (ip 31–47 — all pending dirty
  flags are discarded), `shouldInvalidateCompiledGeometry = true`
  (ip 77–79);
- same extract, ip 177–212: `LevelRenderer.invalidateCompiledGeometry`:
  **`viewArea.releaseAllBuffers()`** (ip 134–138) — every slot through
  `reset()`, i.e. **inside the wave-11 bracket ⇒ mass RETAIN** —
  `clearCompileQueue` (ip 141–145), `new ViewArea(..., 
  options.getEffectiveRenderDistance(), graph)` (ip 148–184, the
  effective-rd read at ip 174), `sectionOcclusionGraph.waitAndReset(viewArea)`
  (ip 187–195), **`clearVisibleSections()`** (ip 198–199), reposition.

**Answer:** a server-pushed radius change moves ALL sections through the
`releaseAllBuffers → reset()` path — wave-11-visible, retained. It also
clears `visibleSections` and the dirty tracker in the same extract call
*before* the dirty loop runs (extract order: HEAD allChanged → ip 177
invalidate → ip 441 dirty loop), so the radius-shrink storm cannot
schedule stale empty rebuilds. Race-free by construction.

Footnote: `waitAndReset(nonNull)` does NOT clear the graph's
`loadedChunks` set (only the null path clears, ip 68–88), and 2.1.3
recorded no removals — after a radius shrink the graph can hold stale
loadedChunks entries. Harmless (BFS may traverse into UNCOMPILED
sections); noted for completeness.

### 2.2 `ClientboundSetSimulationDistancePacket`

`handleSetSimulationDistance` (ip 12–28): stores
`serverSimulationDistance` + `ClientLevel.setServerSimulationDistance(i)`.
Entity/tick simulation only — touches no renderer state, no fog, no
storage. Irrelevant to retention.

### 2.3 `ClientboundSetChunkCacheCenterPacket`

`handleSetChunkCacheCenter` (ip 12–27) →
`ClientChunkCache.updateViewCenter(II)` — writes `Storage.viewCenterX/Z`
only (ip 0–16). The renderer's own recentering is the camera-driven
`ViewArea.repositionCamera`/`RotatingSectionStorage.repositionCenter`
(reset-bracketed ⇒ retained), independent of this packet.

## Finding 3 — "the server is forcing my render distance down": fog and far plane follow min(option, server)

### 3a — the client presentation chain (the headline finding)

Everything the player SEES as "render distance" keys on
**`Options.getEffectiveRenderDistance()`**. Jar-wide census of its callers
(string census over `net/minecraft/client/**`): `Options` (self),
`LevelExtractor` (grid + world-border), `LevelRenderer` (ViewArea ctor),
**`Camera`** (far plane), **`GameRenderer`** (fog + OptionsRenderState),
`Minecraft` (`fillSystemReport` only), `BeaconRenderer` (beam length),
`PerformanceMetricsEvent` (telemetry). The two that hid the owner's
horizon:

- **Fog**: `GameRenderer.extractCamera(DeltaTracker,FF)V` ip 44–67 calls
  `FogRenderer.setupFog(camera, options.getEffectiveRenderDistance(), …)`.
  `FogRenderer.setupFog(Camera,I,DeltaTracker,F,ClientLevel)` computes
  `blocks = rd * 16` (ip 9–14), runs the fog environments, then
  unconditionally (ip 118–149):
  `edge = clamp(blocks/10, 4, 64)`;
  **`FogData.renderDistanceStart = blocks − edge; renderDistanceEnd = blocks`**.
  The shader (`assets/minecraft/shaders/include/fog.glsl`, and Meshelium's
  verbatim copy in `terrain.frag` lines 71–89) takes
  `max(environmental_fog, linear_fog(cylindricalDistance,
  renderDistanceStart, renderDistanceEnd))` and mixes to `FogColor` at
  value 1.0 — **a hard fog wall at effective_rd × 16 blocks**. Server
  radius 8 ⇒ wall at 128 blocks, band starting at 115.2. This is the
  owner's "short render distance fog", verbatim. (Side consumers of the
  same `blocks` argument: `AtmosphericFogEnvironment.setupFog` clamps
  `FogData.skyEnd = min(blocks, SKY_FOG_END_DISTANCE attr)` (ip 110–134)
  and `cloudEnd` likewise (ip ~164–181), so the sky ring follows the same
  int; the ENVIRONMENTAL band (biome haze, rain offsets ip 62–107) comes
  from `EnvironmentAttributes.FOG_START/END_DISTANCE` and does NOT follow
  render distance.)
- **Far plane**: `Camera.update(DeltaTracker)V` ip 0–46:
  `blocks = getEffectiveRenderDistance() * 16`;
  **`depthFar = max(blocks * 4.0f, cloudRange * 16)`**; ip 160–175:
  `setupPerspective(0.05f, depthFar, fov, width, height)` — the render
  projection AND (via `createProjectionMatrixForCulling`, ip 124) the cull
  frustum. Server radius 8 ⇒ far plane 512 blocks: an accumulated retained
  horizon (the owner traveling on a small-radius server) is CLIPPED past
  512 even where the fog leaves silhouettes. Meshelium is equally bound:
  the drawer's CPU region cull extracts the six Gribb–Hartmann planes of
  the render `ProjMat × ModelViewMat` (TerrainDrawer header, lines
  118–124) and its raster clips in vanilla's clip space; `terrain.frag`
  binds vanilla's own Fog UBO slice (`RenderSystem.getShaderFog()`,
  TerrainDrawer ~2590/2808).
- **Grid** (for completeness): `LevelExtractor.extract` HEAD +
  `invalidateCompiledGeometry`'s `new ViewArea(effective)` (finding 2.1)
  size the section grid — this one SHOULD stay at the effective value
  (small grid = the reposition engine retention feeds on).
  `OptionsRenderState.renderDistance` (GameRenderer.extractOptions ip
  268–271) is read only by `LevelRenderer.addWeatherPass` (ip 0–9,
  ×16 weather radius) — cosmetic.

**Answer to 3a:** the small horizon on the owner's server is NOT merely
absence-of-chunks — fog wall and far plane both actively shrink to
`min(option, serverRadius)`. Retained terrain beyond the server radius is
mathematically invisible no matter how correct the mesh lifecycle is.

### 3b — audit of OUR code: Meshelium did NOT shrink the option

`MesheliumExtendedRd` (wave 10/13) audited with MP in mind:

- The per-tick monitor bails **before any clamp logic** whenever
  `rd <= vanillaMax` (`onEndTick`, lines 374–378: `if (rd <= vanillaMax)
  { clampNoticeArmed = true; return; }`). At the owner's option (≤ 32 or
  even 48-with-healthy-drawer) nothing fires.
- `clampBack` targets **`vanillaMax` (32) only** (line 443–445) — never
  the server radius; nothing in the class reads `serverRenderDistance` or
  `serverChunkRadius` at all.
- The gate-decision and options-screen triggers call the same `onEndTick`.
- `serverViewDistanceCap()` feeds two SERVER-side `@ModifyConstant`s
  (`ChunkMap.setServerViewDistance`, `DistanceManager.<init>`) — loaded
  only in the integrated server; a dedicated server never runs this
  client-env mod. It cannot lower anything (returns 32 or higher).
- With Meshelium ACTIVE and healthy on the owner's server, the only branch
  that can run above 32 is `maybeHintRejoin` — a toast, no writes.

**Verdict: no Meshelium code path lowers the option or the effective
distance in MP.** The shrink the owner saw is vanilla's
`getEffectiveRenderDistance()` min() feeding findings 3a. (The slider
itself never moved — the LOOK shrank.)

## Finding 4 — the SP-vs-MP asymmetry, resolved

The hypothesis in the wave-16 brief ("SP tests shrank the CLIENT option →
repositioning; the server-forget path never fired") is **half right and
the packet-flow half is moot**:

1. **In the SP shrink test** (`MesheliumTerrainDrawTest.assertRetainedHorizon`,
   rd 16 → 8 via `setRenderDistanceLikeTheUi`): the option change makes
   the integrated server follow (`IntegratedServer.tickServer`, wave-10
   doc §2) — the client gets the SAME radius packet and the SAME forget
   packets a dedicated server sends. Both paths then do what findings 1–2
   prove: the radius packet funnels the mass release through
   `releaseAllBuffers` (retained), and the forgets free nothing (and land
   outside the fresh rd-8 tracker/grid — un-dirtyable). So retention
   passed in SP **not because the MP paths never fired — they did fire —
   but because those paths genuinely free nothing.**
2. **In MP steady travel** the release driver is slot REPOSITION
   (`RotatingSectionStorage.repositionCenter` → `setSectionNode` →
   `reset()`) — also wave-11-visible, also retained. The lifecycle works
   in both worlds. **The DIFFERENCE the owner experienced is presentation
   only**: in his session `option ≫ serverRadius`, so effective (= fog +
   far plane) collapsed to the server's 8-ish while his option stayed
   high. In the SP test both collapsed together (option 8 = effective 8).
3. **Honesty note on the SP test's "keeps the horizon"** (per the standing
   verify-claims rule): the leg's hard assertions are counters +
   `lastRetainedMaskSections > 0` under `bfsOnly` — that proves retention,
   dispatch, and mask entry, NOT pixels. At rd 8 the fog wall sits at 128
   blocks and the retained 8→16 ring lives at 128–256 blocks CYLINDRICAL —
   inside the fully-fogged band by finding 3a's formula. The
   `A0_meshelium_retained_horizon` screenshot therefore cannot have shown
   unfogged retained terrain; the wave-11 visual claim was optimistic. The
   mechanism is proven; the pixels were never provable pre-wave-16. The
   wave-16 harness must close this (R4).

## Finding 5 — complete MP release/free entry-point inventory, and what wave 16 must do at each

| # | Entry point | Path (bytecode-cited above) | Reaches wave-11 hooks? | Today's behavior | Wave-16 requirement |
|---|---|---|---|---|---|
| 5.1 | **Forget packet** (`handleForgetLevelChunk`) | `drop` → sets + `unload` (light/entities/BEs only) | Never (no mesh free exists on this path) | Mesh stays live in its slot until reposition retains it. Correct. | **Nothing.** Do not hook the packet for the lifecycle (only optionally for 5.4's forgotten-position set). |
| 5.2 | **Radius packet** (`handleSetChunkCacheRadius`) | `setServerRenderDistance` → effective change → `allChanged` → `invalidateCompiledGeometry` → `releaseAllBuffers` (in-bracket) | YES — mass retain | Retains everything; fresh tracker + cleared visibleSections make it race-free. Correct. | **Nothing** (lifecycle). Presentation fix (R1/R2) is what makes the retained ring visible after the shrink. |
| 5.3 | **Reposition while traveling** (`repositionCenter` → `setSectionNode` → `reset()`) | wave-11 bracket | YES — retain | The MP workhorse. Correct. | Nothing. |
| 5.4 | **Light-update-driven rebuild of a forgotten section** (empty compile) | dirty (only possible in-tracker) + still in `visibleSections` → `createRegion` over `emptyChunk` → EMPTY compile → `doTask` ip 209 plain free + `onSectionCompiledEmpty` retained-drop | Free happens OUTSIDE the bracket | Unreachable on vanilla servers (finding 1.4: forgets land beyond the tracker radius). Reachable on plugin servers that forget in-range chunks → hole in the world under retention. | **R3**: guard the empty-compile drop with a chunk-presence check; orphan instead of free when the position's chunk is absent. Cheap, no-op on vanilla servers. |
| 5.5 | **Dimension change / respawn / disconnect** | `handleRespawn` → `Minecraft.setLevel` (ip ~171–194) → `updateLevelInEngines` → `LevelExtractor.setLevel` — which sets `shouldResetLevelRenderData = true` UNCONDITIONALLY (ip 31–33) → next extract (ip 46–62) → `LevelRenderer.resetLevelRenderData`: `releaseAllBuffers` (ip 0–11) then **`dispatcher.dispose()`** (ip 19–30) | YES — retain-then-dispose | Retained copies die with the dispatcher, per world/dimension. Matches wave-11's boundary and the leak test's frees-flow-on-next-world. | **Nothing.** Verify the ordering survives in the wave-16 client run (dispose after the bracket's mass-retain is a store-drop, not a leak). |
| 5.6 | **In-range rebuilds** (block/light changes inside the server radius; re-sent chunks) | `checkSectionMesh`/`doTask` replacement frees | Supersede via slot-steal (wave-3b note 9) | Correct; the fresh build always parks before the old free (wave-11 note, rebuild ordering). | Nothing (verified again in finding 6.1). |

Coverage-guard interaction: none of the above changes the wave-8 rule.
Retention must never convert a drop into a free (5.4's fix converts a
WRONG free into an orphan — the guard's `dropsThisWorld` accounting is
untouched), dispose-per-world stays the boundary (5.5), and eviction
pressure still precedes any drop (wave-14 ladder: grow → evict retained →
guard trip).

## Finding 6 — wave-16 risks and edge cases (verified where bytecode can)

1. **Server re-sends a chunk we retain** (player returns): 
   `handleLevelChunkWithLight` → `updateLevelChunk` →
   `ClientChunkCache.replaceWithPacketData` (`Storage.replace`, old chunk
   unloaded if present, `onChunkAdded` sets) + queued
   `applyLightData` + `enableChunkLight` (lambda ip 5–33 of
   `lambda$handleLevelChunkWithLight$0`) → 
   `ClientLevel.setSectionRangeDirty(x−1…x+1, allY, z−1…z+1)` (ip 76–106 of
   `enableChunkLight`) → in-tracker dirty → extract schedules → compile →
   **the primary build tap fires → slot-steal supersede frees the retained
   copy** (wave-3b/11 machinery). Verified: the rebuild flows through the
   tap; no separate hook needed. The graph re-adds the section via
   `loadedExpectedChunks` partial updates (finding 1.3), so vanilla
   parity resumes too.
2. **Cross-world teleports on servers**: center packet is bookkeeping
   (2.3); the camera jump drives one big `repositionCenter` sweep — every
   crossed slot resets in-bracket (retain) and the retained map inflates
   by up to a full grid per hop. Wave-14's ceiling-based eviction
   (`evictedByPressure`, arena grow-then-evict) is the existing bound;
   wave 16 adds no new mechanism but the torture test should include a
   teleport-loop leg (R4).
3. **Void/edge sections**: empty sections never enter Meshelium (never
   enqueued — wave-3b), `onSectionEmptinessChanged` flows only into the
   BFS sets (`Storage.onSectionEmptinessChanged` ip 0–56). No retention
   interaction.
4. **Retained terrain vs vanilla fog**: not a fight — a max() in the
   shader (finding 3a). Vanilla draws nothing beyond its grid, so there is
   no z-contest; retained terrain is simply fog-mixed to `FogColor` at
   ≥ `renderDistanceEnd` and clipped at `depthFar`. R1/R2 move both walls
   to the OPTION; retained geometry beyond the option stays invisible by
   design (and the wave-13 §9 budget pins from the option, so the retained
   set is option-bounded anyway — consistent).
5. **Stale `loadedChunks` after radius shrink** (finding 2.1 footnote):
   vanilla quirk, no mesh consequence; ignore.
6. **`getEffectiveRenderDistance` is called every frame from
   `extractCamera`/`Camera.update`** — R1/R2's redirects run per frame and
   must stay a handful of field reads (same discipline as the wave-10
   monitor).

---

## Wave-16 design recommendation — "presentation follows the option; data follows the server"

The minimal hook set (2 mixin methods + 1 residency guard + harness):

**R1 — fog follows the option while retention draws.**
`GameRendererMixin`: `@Redirect` the single
`Options.getEffectiveRenderDistance()` INVOKEVIRTUAL inside
`GameRenderer.extractCamera(Lnet/minecraft/client/DeltaTracker;FF)V`
(ip 51 — the method's only call) to
`MesheliumRetentionHorizon.presentationRenderDistance(options)`:

```java
presentationRenderDistance(options) =
    gate == VULKAN_MESH_SHADERS
      && MesheliumConfig.retainTerrain() && terrainEnabled
      && drawerHealthy (no error latch, not coverage-passive)
    ? options.renderDistance().get()          // the RAW option
    : options.getEffectiveRenderDistance();   // vanilla-exact fallback
```

This moves `FogData.renderDistanceStart/End` — and, via the same int,
the `skyEnd`/`cloudEnd` clamps (finding 3a) — from min(option, server) to
the option. Environmental/biome fog is untouched (attribute-driven).

**R2 — far plane follows the option under the same predicate.**
`CameraMixin`: `@Redirect` the single `getEffectiveRenderDistance()` call
in `Camera.update(Lnet/minecraft/client/DeltaTracker;)V` (ip 7) to the
same helper → `depthFar = max(option×64, cloudRange×16)`, feeding both the
render projection and the cull frustum (`setupPerspective` ip 160–175,
`createProjectionMatrixForCulling` ip 124). **Deliberately NOT redirected:**
`LevelExtractor.extract`/`allChanged` and
`LevelRenderer.invalidateCompiledGeometry` (the ViewArea/tracker must stay
at the effective radius — the small grid is what feeds retention),
`extractOptions` (weather), `BeaconRenderer`, telemetry.

Safety/invariants: the predicate re-evaluates per frame; any unhealthy
state returns the vanilla-exact effective value (the wave-10 clamp-back
philosophy applied to presentation — a passive/GL session renders with
byte-identical vanilla fog). On SP the integrated server follows the
option (wave-10 §2), so effective == option and both redirects are
identity — zero SP behavior change outside the deliberate MP case.
Known cosmetic trade-off to record in the doc + options screen: on a
fresh join (nothing retained yet) the fog sits at the option while chunks
only exist to the server radius — the player sees sky/void past the data
edge until the horizon accumulates. That IS the Nvidium feel ("memory of
terrain once seen"); an adaptive ramp
(`clamp(retainedHorizon, server, option)`) is the fallback if the owner
dislikes it — keep R1's helper as the single place that decides.

**R3 — the plugin-server hardening (finding 5.4).**
In `SectionBuildTap.onCompileReturn`'s empty/0-quad branches (the
`onSectionCompiledEmpty` signal) and in `TerrainResidency.onMeshReleased`'s
out-of-bracket free: before dropping/freeing a copy for position P, check
`level.getChunk(P.x, P.z, FULL, false) == null` (AtomicReferenceArray read,
thread-safe, cheap). Chunk absent ⇒ the "empty" compile is an
emptyChunk-fallback artifact (finding 1.4) ⇒ **orphan under retention
instead of dropping/freeing**; chunk present ⇒ today's behavior (real air
supersede / real replacement). No-op on vanilla servers by finding 1.4;
closes the hole-in-the-world on forgetting-in-range servers. The leak
test's invariant is preserved: orphans still die at 5.5's dispose.

**R4 — harness (the honest-pixels upgrade).**
(a) Re-shape the retention leg to the MP geometry WITHOUT moving the
option: inject `options.setServerRenderDistance(8)` client-side on the
rd-16 world (the exact field the radius packet writes — finding 2.1;
`IntegratedServer.tickServer` only re-broadcasts when the OPTION changes,
so the injection sticks). Effective drops to 8 → mass retain fires; the
option stays 16 → with R1/R2 the fog/far-plane stay at 16 → the retained
ring at 128–256 blocks is now OUTSIDE the fog band → `A0` becomes a real
pixel assertion (coordinator reads the PNG; compare against an `A1`
retention-off shot). (b) Assert `FogData.renderDistanceEnd == option×16`
and `depthFar == option×64` via probes on the redirect. (c) Keep every
existing counter assertion; add a teleport-loop leg for 6.2's budget
pressure. (d) Unhealthy-path leg: force coverage-passive, assert the
redirects return vanilla-exact values that frame.

**Explicitly NOT in wave 16:** any hook on `handleForgetLevelChunk`,
`ClientChunkCache.drop`, `updateViewRadius`, or the occlusion graph — the
bytecode shows they need nothing; hooking them would be wire-duplication
against findings 1–2.

## Wave 16 as BUILT (2026-08-10): implemented deltas vs the recommendation above

Everything above is left exactly as it was written on recon day, so the
deltas stay legible. This section is the as-built record. Code complete
2026-08-10, pending the coordinator's harness runs.

| Piece | Where it lives |
|---|---|
| R1, the fog redirect | `fabric/mixin/GameRendererMixin` (new; registered in `meshelium.client.mixins.json`) |
| R2, the far-plane redirect | `fabric/mixin/CameraMixin` (new; same registration) |
| The single per-frame decision | `MesheliumRetentionHorizon` (new, package `com.deds.meshelium`) |
| R3, the chunk-presence probe | `SectionBuildTap.chunkAbsentClientSide(sx, sz)` (package-private, build-thread side) |
| R3, the wiring and counters | `TerrainResidency.onMeshReleased` and `onSectionCompiledEmpty`, over the extracted `retainLocked` / `freeLiveLocked` tails; counters `retainedKeptChunkAbsent` and `orphanedChunkAbsent`, both inside `Counters.isCompletelyIdle` (the GL dormancy set) and the residency stats line |
| R4, the harness | `MesheliumTerrainDrawTest.assertMpRetainedHorizonPixels` plus the in-harness pixel analysis `assertRetainedBandPixels`, entered as step 4 of `assertRetainedHorizon` |
| Observability in a real session | the four presentation probes (`fogWidenedCalls`, `farPlaneWidenedCalls`, `lastFogRenderDistance`, `lastFarPlaneRenderDistance`) print as the `horizon[...]` group on `TerrainDrawer`'s draw-path stats line; the two R3 counters print on `TerrainResidency`'s line, where their subject lives. On a real server session those are the probes that answer "is the widening happening, and at what value" |

### The decision, in the order the code evaluates it

`MesheliumRetentionHorizon.presentationRenderDistance(Options, boolean fogSite)`
is PRIVATE; the two redirects reach it through the public
`fogRenderDistance(Options)` and `farPlaneRenderDistance(Options)`. Term
order is load bearing twice over (class-loading discipline, and the
cheap-terms-first cost rule of finding 6.6), so the as-built order is
recorded here rather than the recommendation's reading order:

```text
effective = options.getEffectiveRenderDistance()   // the ORIGINAL call's result

1. gate is not VULKAN_MESH_SHADERS          return effective
     (GL, no mesh shaders, and the UNKNOWN boot / quickPlay frames; this
      term is also the class-loading guard that keeps step 5 unreachable
      on a GL session, so the vk package is never loaded through here)
2. option = options.renderDistance().get()
   option <= effective                      return effective
     (nothing to widen: EVERY singleplayer steady state lands here, before
      any config read or level lookup)
3. terrain rendering off, or retention off  return effective   (config
                                            matrix, live read)
4. Minecraft.getInstance().level == null    return effective   (title
                                            screen, dimension change,
                                            disconnect frames)
5. TerrainDrawer.lastError() != null, or
   TerrainDrawer.coveragePassive()          return effective   (unhealthy
                                            drawer: the wave-10 clamp-back
                                            philosophy applied to
                                            presentation)

otherwise: count the widened call for THIS site, record it as this site's
           last returned value, and return the RAW option (capped AT the
           option by construction, never beyond it)
```

### Deltas vs the recommendation

| Recommendation | As built | Why |
|---|---|---|
| One entry point, `MesheliumRetentionHorizon.presentationRenderDistance(options)` (R1, above) | That method is private and takes a `fogSite` flag; the two public wrappers record `lastFogRenderDistance` / `lastFarPlaneRenderDistance` and bump `fogWidenedCalls` / `farPlaneWidenedCalls` separately | R4 has to see each site's own last value and call count; one shared probe could not tell a fog-only regression from a far-plane-only one. Accepted consequence, stated on the class javadoc: the two sites can disagree for exactly one frame if a config flag flips between them (transient, cosmetic) |
| The predicate as sketched (gate, retention, terrain, drawer health) | Two more terms, both failing closed: `Minecraft.level != null`, and `option > effective` | The level term keeps the title screen and the level-null frames of a dimension change or disconnect vanilla exact. The `option > effective` term is what makes every singleplayer steady state byte identical to vanilla (the integrated server follows the option, so the two are equal and the method returns before reading any config), and it is why the harness can assert SP parity at all |
| R3 in `SectionBuildTap.onCompileReturn`'s empty branches plus `TerrainResidency.onMeshReleased`'s out-of-bracket free | The probe itself lives in `SectionBuildTap` (`Minecraft.level` read plus one `ClientChunkCache.getChunk(x, z, FULL, false)`, no vanilla monitor anywhere in the chain); BOTH decision sites are in `TerrainResidency`, each split into two `LOCK` sections around the unlocked probe | Meshelium's 5.3 discipline: no vanilla call while holding `LOCK`. The empty-compile site keeps its vanilla fast path: `retained.containsKey(pos)` false in the first section returns without making any vanilla call, so a vanilla world pays nothing per empty compile |
| R4(a): inject `options.setServerRenderDistance(8)` on the rd-16 world | Built exactly so, and it does stick (the only writer of the radius packet in SP is `IntegratedServer.tickServer`, which re-broadcasts only when the OPTION moves, and the option never moves while the injection stands) | The real packet handler also calls `ClientChunkCache.updateViewRadius` (`handleSetChunkCacheRadius` ip 34-45), which the injection deliberately omits. That reproduces the PRESENTATION geometry faithfully, which is all the pixel assert needs, but it does NOT reproduce data absence: see UNVERIFIED item 7 |
| R4(b): assert `FogData.renderDistanceEnd == option*16` and `depthFar == option*64` | SUBSTITUTED: the harness asserts the redirect's RETURN value (both probes equal the option under the injection, and equal the server radius with retention off) | Neither field is reachable from the harness. The `FogData` instance is created and consumed inside `FogRenderer.setupFog`, and `Camera.depthFar` is private with no accessor, so reaching either means a third mixin. The link from the redirected int to the two fields is the cited bytecode, so the harness proves the INPUT and infers the outputs. That is an inference, and it is recorded as one |
| R4(c): keep every counter assertion, add a teleport-loop leg for 6.2 | Built: two 256-block hops under the injected radius, each asserting `orphanedSections` rises while `dropsThisWorld` stays 0 and no latch trips | The 6.2 pressure path at test scale |
| R4(d): unhealthy-path leg (force coverage-passive, assert the redirects return vanilla-exact values that frame) | NOT BUILT as of this section's writing (2026-08-10, the wave-16 completion pass) | The only place that forces genuine coverage-passive is `MesheliumLifecycleTortureTest` (`meshelium.test.arenaMiB=1`, then `waitFor(TerrainDrawer::coveragePassive)`), and that leg does not inject a server radius, so `option == effective` there and term 2 short-circuits before the health term is ever reached: an assertion added there today would be vacuous. Making it real means adding the same `setServerRenderDistance` injection to that test. A terrain-master-off or retention-off leg is NOT a substitute: those exercise the CONFIG terms, which the shipped leg already covers. UNVERIFIED item 8 |
| The pixel proof (R4's whole purpose, finding 4.3) | Built: `A0_meshelium_retained_horizon` and `A1_meshelium_retention_off` from the SAME pinned spectator camera, then an in-harness analysis of the band between effective*16 and option*16 (A1's window a uniform fog wall, A0's window holding terrain, the pair provably differing) | This is the claim wave 11 could not make. Note the window constants are calibrated for the harness's FLAT preset world and the pinned camera rise and pitch; they are not portable to another world shape |

### R3's two lock sections: the invariant that makes the split safe

The probe cannot run under Meshelium's `LOCK`, so both R3 sites are shaped
lock, probe, lock. Teardown is what makes that delicate, and the reason is
bytecode: Meshelium's per-world teardown (`TerrainResidency.disposeAndReset`)
is driven from `SectionRenderDispatcher.dispose()` at HEAD, and `dispose()`
does `putfield closed` at ip 0 and `clearCompileQueue()` at ip 5, taking
`copyLock` only at ip 9-13. So `copyLock`, which `onMeshReleased`'s callers
do hold, does NOT exclude a teardown from the probe window, and
`onSectionCompiledEmpty` holds no vanilla lock at all (it runs at
`SectionCompiler.compile` RETURN, `doTask` ip 100-103, long before the empty
branch takes `copyLock` at ip 200).

The invariant each site must satisfy is therefore: **the entry stays
reachable from a Meshelium map across the unlocked probe, and the second
section re-validates it**, so a release either completes wholly before a
dispose or is swallowed wholly after one. `onSectionCompiledEmpty` satisfies
this by construction (it only peeks in section one and re-fetches from
`retained` in section two). The wave-16 audit (2026-08-10) flagged the first
cut of `onMeshReleased` for violating it: the candidate was removed from the
identity map in section one, so a dispose landing in the window would let an
old-world arena address be parked into the next world's fence epochs.
**Coordinator: confirm the shipped `onMeshReleased` re-checks the identity
map in its second section before it retains or frees.**

### Staleness is visible by design, and the two cosmetic windows

With the predicate true, terrain is lit, fogged and clipped out to the
OPTION, and the band between the server radius and the option shows RETAINED
terrain: a snapshot the server is no longer updating. That is the intended
behaviour, not a defect. Owner directive (2026-08-10, verbatim, about the
small-radius server that started this recon): *"i wanted that for servers
like this"*. Block changes out in the band (other players' builds,
explosions, anything) are invisible until those chunks come back into range,
at which point the ordinary rebuild supersedes the retained copy (finding
6.1). Retention has always been a memory of terrain once seen, never live
data; wave 16 is only what makes the memory visible.

Two cosmetic windows are accepted with it, both of them the same shape (the
fog moves before the data does):

1. **Fresh join, and the first frames of a world standup.** Nothing is
   retained yet, so the fog and the far plane sit at the option while chunk
   data only exists out to the server radius: the far band shows sky or void
   until the horizon accumulates as the player travels. The recorded
   fallback, if the owner dislikes the look, is an adaptive ramp
   `clamp(retainedHorizon, serverRadius, option)`; `MesheliumRetentionHorizon`
   is deliberately the single place that would decide it, so that change
   stays one method.
2. **A mid-session option raise, on any server.** The client writes the new
   option before the round trip that raises the effective radius, so
   `option > effective` holds for a few frames and the fog opens slightly
   before the new chunks land. Bounded by the round trip. (The R4 leg
   captures all of its flat-window baselines strictly after this settles,
   for exactly this reason.)

Neither window touches a lifecycle path, a counter or a budget. Both are
pixels only.

### Corrections to this recon, found by re-reading the bytecode while implementing

Three claims above are wrong or incomplete. None of them changes the design
and no shipped code depends on them, so they are corrected here rather than
silently edited into the findings.

1. **`cloudEnd` does NOT follow the render distance** (the parenthetical at
   the end of finding 3a's fog bullet, and the same phrase in the R1
   recommendation). In `AtmosphericFogEnvironment.setupFog`, `skyEnd` really
   is derived from the propagated float (ip 110-134,
   `min(rd*16, SKY_FOG_END_DISTANCE)`), but `cloudEnd` is computed
   independently at ip 137-181 from
   `Minecraft.getInstance().options.cloudRange() * 16` clamped against
   `CLOUD_FOG_END_DISTANCE`; the render-distance float is never on that
   stack. Clouds keep vanilla's `cloudRange` fade whatever the redirect
   returns, which is the desirable behaviour anyway. (The boss-fog branch at
   ip 202-241 overwrites both fields from `environmentalEnd` and is likewise
   render-distance independent.)
2. **The far plane on a radius-8 server is 2048 blocks, not 512** (finding
   3a's far-plane bullet). The formula quoted there is right,
   `depthFar = max(rd*16*4.0f, cloudRange*16)`, but the conclusion drops the
   second term: `cloudRange` is an `IntRange(2, 128)` whose DEFAULT is 128
   (`Options.<init>` ip 456-483, javap'd 2026-08-10), so
   `depthFar = max(rd*64, 2048)` and the render-distance term only binds
   when `option > cloudRange/4`, which at the default cloud distance means
   above option 32. Two consequences, both real: at the harness's rd 8/16
   pair R2 cannot change the far plane at all (2048 either way), so that leg
   proves R2 by counter and by returned value only, never by a changed
   projection; and on the owner's own sessions R2 is load bearing precisely
   because his option is extended past 32 (option 48 gives 3072, option 96
   gives 6144), where an accumulated retained horizon really would be
   clipped. The FOG wall was the whole of the sub-32 symptom; R2 is what
   stops an EXTENDED option's retained horizon from being cut off.
3. **The redirected int also reaches the fog COLOUR**, which the recon did
   not mention. `FogRenderer.setupFog` passes the same int to
   `computeFogColor` (ip 44), whose only use of it is
   `FogEnvironment.getBaseColor(level, camera, renderDistance, partialTick)`
   (ip 119-122). `AtmosphericFogEnvironment.getBaseColor` reads it twice: a
   `renderDistance >= 4` gate on the sunrise/sunset branch (ip 20-22,
   satisfied by every realistic value), and inside that branch the blend
   weight `1 - pow(clampedLerp(0.25, 1, min(SKY_FOG_END_DISTANCE/16, rd)/32),
   0.25)` (ip 201-258). So widening the int slightly reduces the
   sunrise/sunset tint mixed into the fog colour while facing the sun, which
   is exactly what the same option would do on a server that allowed the
   larger radius. No other colour term reads it.

## UNVERIFIED / limits of this recon

1. **No JVM was run** (house rule): every chain above is static bytecode
   + source reading. The owner-server behavior model (fog wall = the whole
   symptom; lifecycle healthy) predicts that on the same server with
   R1/R2 the horizon accumulates as the player travels — only the wave-16
   playtest can confirm, and `TerrainResidency.counters()` on a real
   server session (retainedSections rising while traveling) is the
   decisive probe if it doesn't.
2. **`FogColor.a`**: `apply_fog` scales by `fogColor.a`
   (terrain.frag line 89); `computeFogColor` (FogRenderer ip 139+) was not
   fully traced — if some dimension sets alpha < 1 the "wall" is partial
   there. Does not change the recommendation.
3. **Which server software the owner plays on** is unknown; finding 1.4's
   "vanilla servers cannot fire the empty-rebuild path" depends on
   forget-packets-only-beyond-radius behavior. R3 exists precisely so the
   answer doesn't matter.
4. **The A0 screenshot re-read** (finding 4.3) is an inference from the
   fog formula, not a re-viewing of the PNG; the coordinator should
   re-read the existing `A0_meshelium_retained_horizon` against it.
5. `ClientboundLoginPacket`'s own radius/simulation fields were not
   re-traced (the radius packet broadcast on join via
   `PlayerList.setViewDistance` is already cited in
   EXTENDED-RENDER-DISTANCE.md Q2); irrelevant to the conclusions.

6. *(added with the as-built section, 2026-08-10)* **The POSITIVE half of R3
   is runtime-unverified.** Nothing in the harness
   produces what R3 exists for: a chunk that is absent client-side while its
   section is still in the dirty tracker and in `visibleSections`. That
   state needs a server that forgets in-range chunks, and it is not
   arrangeable in singleplayer (the integrated server keeps sending, and no
   forget packet fires). The guard is proven by bytecode reading only; the
   first plugin-server session is its first real test. Probe to read there:
   `orphanedChunkAbsent` and `retainedKeptChunkAbsent` in the residency
   stats line.
7. **The R3 no-op assertion in the wave-16 leg is true by construction, not
   by proof.** The harness injects `Options.setServerRenderDistance` only.
   The real packet handler ALSO calls `ClientChunkCache.updateViewRadius`
   (`handleSetChunkCacheRadius` ip 34-45), which rebuilds the client cache's
   `Storage` at `max(2, radius) + 3` per axis and discards what falls
   outside. Without that resize every chunk out to the ORIGINAL radius stays
   present in the `AtomicReferenceArray`, so `chunkAbsentClientSide` cannot
   return true and both R3 counters are pinned at 0 whether the guard is
   right or wrong. Keep the assertion as a regression tripwire; do not cite
   it as proof. Making it real means adding the resize to the injection and
   flipping the leg's expectation (the counters SHOULD then move, while
   `retainedSections` must not shrink).
8. **The health term of the presentation predicate is untested** (the R4(d)
   row in the as-built section). No test anywhere forces a coverage-passive
   or error-latched drawer while `option > effective`, so nothing proves
   that an unhealthy drawer sends the fog and far plane back to
   vanilla-exact. The gate, config and `option > effective` terms are all
   covered; the health term is code-reviewed only.
9. **"Both R3 counters stay 0 on vanilla servers and singleplayer" is a
   steady-state claim, not an all-states claim.** `Minecraft.setLevel`
   writes the `level` field FIRST (ip 0-2) and only then calls
   `updateLevelInEngines` (ip 7), which is what eventually drives finding
   5.5's `resetLevelRenderData` (`releaseAllBuffers`, then
   `dispatcher.dispose()`). For the whole of that teardown the probe reads
   the BRAND NEW `ClientLevel`, whose chunk storage is empty, so every
   old-world position reads absent: any out-of-bracket release or
   empty-compile signal landing in that window moves an R3 counter on 100%
   vanilla singleplayer (nether portal, End, respawn). The harness never
   sees it, because its leg contains no dimension change, so the `== 0`
   assertion can stand as written; but the invariant stated in
   `TerrainResidency`'s counter comment is stronger than the code
   guarantees. If those counters are ever cited as evidence, make the probe
   level-identity-aware first (compare against the level the Resident was
   uploaded under, or a per-world generation stamp bumped in
   `disposeAndReset`), which also removes the teardown storm from the
   two-lock-section window.
