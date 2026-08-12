# Extended render distance (waves 10 + 13) — recon, design, and the clamp-back invariant

> **Wave 13 (2026-08-10) reworked the UX half of this design** — see the
> wave-13 section at the bottom for the owner-playtest dead-end
> inventory, the new apply-semantics matrix, the pin-from-option scaling
> change, and the Video Settings integration. §§1–6 below are the wave-10
> record; where wave 13 superseded a mechanism the wave-13 section says
> so explicitly. **The clamp-back invariant (§4) is unchanged.**

Wave-10 recon + implementation notes, 2026-08-10. Method: `javap -p -c`
against the real jar
(`attack-of-the-bteam-1.26.2/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/…`).
Owner request (2026-08-10): "make a way to set render distance above 32" —
Nvidium's headline capability, affordable here because waves 0–9 measured
1.73× at rd 32 (docs/PERFORMANCE.md) with GPU-driven culling that scales
where per-section CPU draws do not.

**The wave's central safety invariant (clamp-back):** the widened range may
only ever take effect while Meshelium actually draws. A GL user or a
coverage-guard-passive world at rd 64 on the VANILLA renderer would be a
slideshow Meshelium caused. Every path that ends with vanilla rendering while
the option sits above 32 clamps it back, saves, and says so once (toast +
WARN). Trigger inventory in §4.

---

## Q1 — Where 32 lives (client)

All bytecode-verified:

- **The option**: `Options.<init>(Minecraft, File)` (the only ctor) builds
  `renderDistance = new OptionInstance("options.renderDistance",
  noTooltip, …, new OptionInstance$IntRange(2, max, false), 12, listener)`
  at ctor ip 4915–4965, where `max = Runtime.maxMemory() >= 1e9 ? 32 : 16`
  (ip 4896–4914 — 26.2's "is64Bit" heuristic). The `valueChanged` listener
  is `lambda$new$106` = `setGraphicsPresetToCustom()` only (BootstrapMethods
  #118 → #2541).
- **Persistence is a SECOND copy of the range**: the 6-arg `OptionInstance`
  ctor captures `codec = values.codec()`, and `IntRange.codec()` =
  `Codec.intRange(minInclusive, maxInclusive + 1)` reading the FIELDS
  (getfield, not the accessors). `Options.load()` runs as the ctor's LAST
  action (ip 5050, single call site). ⇒ Widening must swap **both** the
  `values` ValueSet and the `codec` field, and must do it **before
  `load()`** or a previously saved 48 is rejected at boot (vanilla resets
  the option to its default 12 on codec failure).
- **Validation/slider dispatch through the record accessors**:
  `IntRange.validateValue` calls `minInclusive()`/`maxInclusive()`
  (invokevirtual); the slider mapping lives in `IntRangeBase`'s default
  `toSliderValue`/`fromSliderValue`, also via the accessors — so swapping
  the IntRange **instance** widens everything the UI touches. `IntRange` is
  a record (immutable) with a public `(int, int, boolean)` ctor.
- **`OptionInstance.set` semantics**: `values.validateValue(v)
  .orElseGet(→ initialValue)` — an out-of-range set resets to the DEFAULT
  (12), it does not clamp. (The GL harness assertion accounts for this.)
- **`Options.getEffectiveRenderDistance()`** = `serverRenderDistance > 0 ?
  min(option, serverRenderDistance) : option` — **on singleplayer the
  integrated server's radius caps the client**, so Q2's server half is
  load-bearing for the client's own ViewArea, not just for chunk data.
- **Presets**: `RENDER_DISTANCE_SHORT/FAR/REALLY_FAR/EXTREME = 4/12/16/32`
  are graphics-preset constants only; a widened value simply reads as the
  Custom preset. No other client-side clamp found (binary grep for the
  option's consumers; everything downstream reads
  `getEffectiveRenderDistance()`).
- **Where a change takes effect**: `LevelExtractor.extract` HEAD compares
  `getEffectiveRenderDistance()` to `lastViewDistance` and calls
  `allChanged()` on any difference (ip 0–18) → full
  `invalidateCompiledGeometry` → new ViewArea. Automatic; Meshelium adds no
  rebuild plumbing.

**Implementation** (`MesheliumExtendedRd` + 2 mixins):
`OptionInstanceAccessor` (@Mutable accessors for `values` + `codec`);
`OptionsMixin` injects at the `load()` INVOKE inside `Options.<init>` and
widens config-gated (`maxRenderDistance` > vanilla max ∧ terrain enabled).
The gate cannot have decided yet (the device doesn't exist during
`Options.<init>` — MesheliumGate javadoc), so the widening is provisional and
the title-screen decision tick re-validates (§4 trigger 1).

## Q2 — The server half

### Singleplayer: the follow chain and its three literal 32-derived caps

- `IntegratedServer.tickServer` ip 112–181, **every tick**: `vd = max(2,
  options.renderDistance().get()); if (vd != playerList.getViewDistance())
  playerList.setViewDistance(vd)` — the integrated server ALREADY follows
  the client option with **no upper clamp**. Same pattern for simulation
  distance (untouched by this wave).
- `PlayerList.setViewDistance(i)`: stores raw, broadcasts
  `ClientboundSetChunkCacheRadiusPacket(i)` (raw), and per level calls
  `ServerChunkCache.setViewDistance(i)` → `ChunkMap.setServerViewDistance`.
- **Hard cap #1 — `ChunkMap.setServerViewDistance`**: `Mth.clamp(i, 2,
  32)` (bipush 32, the method's only int-32 literal). Governs chunk
  **sending** (`getPlayerViewDistance` = `clamp(requestedViewDistance, 2,
  serverViewDistance)`) and the value `DistanceManager.updatePlayerTickets`
  receives. Widened by `ChunkMapMixin` (@ModifyConstant).
- **Hard cap #2 — `DistanceManager.<init>`**: `new PlayerTicketTracker(
  this, 32)` (bipush 32 at ip 35, the ctor's only int-32 literal). That 32
  is `FixedPlayerDistanceChunkTracker.maxDistance`; the tracker's per-chunk
  player-distance map defaults to `maxDistance + 2` and levels never
  propagate past it — chunks farther than ~34 from every player can never
  receive the PLAYER_LOADING ticket that **loads** them, no matter what
  `updateViewDistance` is later passed. Widened by `DistanceManagerMixin`
  (@ModifyConstant). Construction-time: fixed per world load ⇒ mid-world
  config raises fully apply after rejoining. Known cost while extended: the
  tracker maintains its distance field to cap+2 chunks per player
  regardless of the current option (~38k map entries at cap 96 vs ~1.2k
  vanilla) — accepted for the integrated server, noted here.
- **No other caps found**: `ClientInformation` carries the client's raw
  option as its requested view distance (wire format signed byte — 96
  fits); `ServerPlayer.requestedViewDistance()` returns the stored field;
  `ChunkTrackingView` has no numeric literals at all; client receive
  (`handleSetChunkCacheRadius`) stores the raw radius
  (`setServerRenderDistance`) and resizes `ClientChunkCache` storage to
  `max(2, radius) + 3` — unbounded.
- **Hard cap #3 — the chunk-task priority ladder** (found by the first
  rd-48 run crashing chunk workers, AIOOBE index 50 vs length 46):
  `ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT = ChunkLevel.MAX_LEVEL + 2`
  (= 46, clinit-computed so javac cannot constant-fold it) sizes a
  priority list indexed directly by queue level, and the queue levels on
  the player-ticket path are the TRACKER's player-distance levels
  (`PlayerTicketTracker.onLevelChange` submits with `() -> level`), which
  reach `maxDistance + 2` — 50 with the tracker widened to 48. Widened by
  the same +N at boot via `ChunkTaskPriorityQueueAccessor` (@Mutable
  static write from `MesheliumExtendedRd.widenChunkTaskLadder`, before any
  server exists).
- **Gating**: both mixins substitute
  `MesheliumExtendedRd.serverViewDistanceCap()` = vanilla-exact **32 unless**
  gate == VULKAN_MESH_SHADERS ∧ terrain enabled ∧ configured max > 32. A GL
  boot, a quickPlay session with an undecided gate, or the feature at its
  default keeps vanilla's exact clamps — which is itself a second
  enforcement layer of the invariant: even if the option somehow said 64,
  the SP server would clamp to 32 and `getEffectiveRenderDistance`'s min()
  would keep the client at 32. Dedicated servers never load this
  client-env mod at all.

### §2b — The third half: per-player SENDING, and the 157-chunk postmortem

Second recon, 2026-08-10, after the first `-Pmeshelium.rd=48` run reported
option 48, effective 48 (radius packet round trip healthy), ladder fix
holding — and the client frozen at **157 loaded chunks** (regions 4,
sections 49) after 5 minutes of superflat. Full loading+sending path
re-read from bytecode. Verdict: **the two widened clamps are the complete
server-side set; the stall was the sending half's third input, which needs
no mixin at all.**

**Loading — works at 48, no architectural ceiling.** The exact path:
`ChunkMap.setServerViewDistance` (clamp widened) stores and calls
`ChunkMap$DistanceManager.updatePlayerTickets(serverViewDistance)` — the
RAW field, no ±1 (ip 21–29) → `PlayerTicketTracker.updateViewDistance`.
The tracker (a `FixedPlayerDistanceChunkTracker`, per-chunk chebyshev
distance-to-nearest-player as its level, entries kept while level ≤
maxDistance) tickets each chunk with `haveTicketFor(level)` = `level <=
viewDistance`, issuing **a per-chunk `Ticket(TicketType.PLAYER_LOADING,
PLAYER_TICKET_LEVEL)` at the FIXED level 31** (`PLAYER_TICKET_LEVEL =
ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING)`, clinit). Fixed
per-chunk levels mean ticket-LEVEL space never bounds the loading RADIUS —
levels only bound the generation halo around each ticketed chunk
(26.2 splits `LoadingChunkTracker` / `SimulationChunkTracker` over
`TicketStorage`; `LoadingChunkTracker` has `MAX_LEVEL + 1` levels and
sources from `TicketStorage.getTicketLevelAt`). The
`ThrottlingChunkTaskDispatcher` (maxChunksInExecution = 4, released on the
entity-ticking future in `DistanceManager.runAllUpdates`) paces ticket
adds — throughput, not a cap. So with caps #1–#3 widened, loading proceeds
to the full extended radius. True >32 loading is achievable and the
existing three widenings are sufficient — **verdict (a), no de-scope.**

**Sending — was pinned at the harness's boot-time 5.** `ChunkMap.tick()`
runs `updateChunkTracking(player)` for every player every tick
(early-return when the `Positioned` view's center AND viewDistance are
unchanged), building `ChunkTrackingView.of(center,
getPlayerViewDistance(player))` where `getPlayerViewDistance` =
`clamp(ServerPlayer.requestedViewDistance(), 2, serverViewDistance)`.
`requestedViewDistance` is written ONLY by
`ServerPlayer.updateOptions(ClientInformation)` ←
`ServerGamePacketListenerImpl.handleClientInformation` ←
`ServerboundClientInformationPacket`. The client emits that packet from
`Options.broadcastOptions()` (deduped by
`ClientPacketListener.broadcastClientInformation`'s equals-check), whose
only steady-state caller is **`Options.save()`** (its last action, after
the file write) — and the vanilla UI calls `save()` from
`OptionsSubScreen.removed()`, i.e. **closing the video-settings screen is
what re-broadcasts the slider**. `buildPlayerInformation()` passes the RAW
`renderDistance.get()` (wire = signed byte, 96 fits; no clamp anywhere in
the chain). A programmatic `OptionInstance.set()` without `save()`
therefore widens LOADING but leaves SENDING at the old radius forever.

**The 157 fingerprint.** `Positioned.contains(x, z)` dispatches with
`includeEdge = true` → `ChunkTrackingView.isWithinDistance`: a chunk is in
view iff `max(0,|dx|−2)² + max(0,|dz|−2)² < vd²` (slack 2, strict `<`).
At vd 5 that counts 65+26+26+22+18 = **exactly 157** — the harness sets
rd 5 at boot, login carried it, and the leg's `set(48)` never broadcast.
(vd 32 counts 3,725; vd 48 would count ~7.9k.) Every other stage honestly
reported 48 because every other stage READS the option or the server view
distance; only the per-player requested value was stale. Server-side
loading past the sent ring was simply never observed by the client-side
probes.

**Fix.** The harness leg now does what the UI does — `options.save()`
after the set — then waits for `ServerPlayer.requestedViewDistance ≥ 48`
(the round-trip probe) and requires `clientLoadedChunks > 3,725` (the
exact vd-32 sendable ceiling, same formula, computed in the test) plus
the region-growth bar. Production needs no change: the options screen
saves on close. The only user-visible caveat inherited from vanilla:
render-distance changes apply when the screen closes, not per slider
notch — true at 8 chunks as at 48.

### Multiplayer: answered by Bobby, not by us (2026-08-11)

Server-controlled: `getEffectiveRenderDistance` = min(option, server
radius). An extended option cannot make chunk DATA arrive from beyond the
server's distance. That part is physics.

**Superseded text.** This section used to say wave-11 retained terrain
answered the multiplayer case: terrain that HAS arrived keeps rendering
out of Meshelium's arena after vanilla lets go of it, so the horizon
accumulates as the player travels. The claim was half true and the wrong
half mattered. `getEffectiveRenderDistance` sizes BOTH the compilable set
and the fog wall (bytecode in MP-RETENTION-RECON.md and
FRONTIER-HOLES-RECON.md), so the fog is fully opaque at exactly the
radius where the retained band begins: the retained copies are there, and
in normal play nobody can see them. Waves 16 and 17 widened the fog and
the far plane to expose them, produced worse artifacts on the owner's real
server, and were reverted (619aa8e).

**What replaces it.** Pair Meshelium with **Bobby**, which fixes the
actual problem one layer down: it caches the chunks the server sends to
disk and serves them back as real chunks, so the client genuinely holds a
wide world, vanilla compiles it, the fog wall moves out with it, and
Meshelium draws all of it. Retention is now default OFF and off the
options screen (`retainTerrain`, config file and
`-Dmeshelium.retainTerrain` only, SPEC row 11's 2026-08-11 amendment); the
machinery is intact for a future data-layer wave. The widened OPTION
above 32 stays SP-only either way, since this wave's server-side clamps
exist only in the integrated server.

## Q3 — Vanilla client scaling at rd > 32 (does it survive?)

- **ViewArea/RotatingSectionStorage**: `RotatingSectionStorage.<init>`
  builds `(2·rd+1)² × (maxY−minY+1)` nodes (bytecode: `radius*2+1` per
  axis). rd 48 → 97²·24 ≈ 226k RenderSections; rd 64 → 399k; rd 96 → 894k.
  Pure allocation growth (each node is a small object + a RenderSection);
  no literal caps, no asserts. This is CPU-heap quadratic cost vanilla
  pays wherever the option goes — part of why 96 is the config ceiling.
- **SectionOcclusionGraph/Octree**: allocation-based, sized from the
  ViewArea it is reset with (`waitAndReset(viewArea)`); no literals found.
- **DynamicUniforms** ("Chunk Sections UBO"): `DynamicUniformStorage`
  resizes (`resizeBuffers`) — vanilla's own per-visible-section uniform
  path grows on demand. Note vanilla's `prepareChunkRenders` still runs
  every frame even when Meshelium cancels the draws, so this growth is real
  on extended worlds either way.
- **ClientChunkCache**: storage ring = `max(2, radius) + 3` per axis —
  follows the server radius packet.
- **No hard asserts or literal-32 arrays** were found on the client render
  path beyond the option itself (Q1). Vanilla at rd 48/64 is slow, not
  broken — which is exactly why the clamp-back invariant exists.

## §4 — The clamp-back invariant (implementation)

`MesheliumExtendedRd`, one mechanism, three triggers:

1. **Gate decision tick** (title screen; `MesheliumGate` calls
   `evaluateNow`): gate ≠ VULKAN_MESH_SHADERS ∨ terrain disabled ∨ config
   ≤ 32 ⇒ the option's ValueSet+codec are restored to the captured vanilla
   instances AND a value > 32 is set back to the vanilla max + `save()` +
   toast/WARN once. Runs before the player can leave the title screen; the
   boot-time widening (needed so saved values survive `load()`) is
   therefore never user-visible on GL.
2. **Per-tick monitor** (END_CLIENT_TICK, registered after the gate's own
   hook so the decision tick is seen same-tick; a handful of field reads):
   catches the coverage guard going passive mid-world
   (`TerrainDrawer.coveragePassive()` — wire-not-duplicate: the guard
   itself is untouched, the monitor just watches it), the drawer's session
   error latch (`lastError() != null`, incl. device loss), live
   config/property flips that disable terrain, config lowering, and the
   quickPlay edge (world exists, gate still UNKNOWN because no title
   screen ever showed). During boot (gate UNKNOWN ∧ no level) it waits.
   The vk-package probes are only reached when the gate already said
   VULKAN_MESH_SHADERS (class-loading discipline). Tick granularity means
   a mid-world trigger can leave vanilla above 32 for a few frames before
   the clamp lands — transient by construction.
3. **Options-screen change**: the Max Render Distance row calls
   `onConfigChanged` → same evaluation immediately.

Guard integration: a passive world at extended RD clamps with the
distinct "Meshelium went passive; render distance restored for this world"
toast (`meshelium.rd.clamped.passive`); everything else uses
`meshelium.rd.clamped.off`. Clamps are counted (`sessionClamps()`, harness
probe) and the notice re-arms when the option returns to a legal value.

## §5 — Buffer scaling (`MesheliumScaling`, pinned per world standup)

Pinned at `MesheliumTerrainGpu.create()` — strictly before the arena
attaches, before `TerrainOcclusion`/`MesheliumFrameLists` exist, while the
fresh `RegionStore` is empty — so one world sees one snapshot. Default
(config 32) reproduces every wave-≤9 literal exactly at STANDUP; the GL
path is byte-identical to wave 9, and since wave 14 the arena (only the
arena) may grow past its 256 MiB start when the world's density demands
it — see the wave-14 row below and VANILLA-SECTION-BUILD.md wave-14
note.

| Quantity | Formula | rd 32 | rd 48 | rd 64 | rd 96 |
|---|---|---|---|---|---|
| regionsTouched(rd) | (⌈(2rd+1)/8⌉+1)² × 7 | 700 | 1,372 | 2,268 | 4,732 |
| maxRegions | rd≤32: 2048; else max(2048, ⌈2×touched⌉₂₅₆) | **2,048** | 2,816 | 4,608 | 9,472 |
| region records (16 B×) | maxRegions | 32 KiB | 44 KiB | 72 KiB | 148 KiB |
| section records (8 KiB×) | maxRegions (regionId·8192 addressing) | 16 MiB | 22 MiB | 36 MiB | 74 MiB |
| section stamps A+B (1 KiB× each) | maxRegions | 2×2 MiB | 2×2.75 MiB | 2×4.5 MiB | 2×9.25 MiB |
| dispatchCapacity (mask/occ lists) | rd≤32: 512; else maxRegions | **512** | 2,816 | 4,608 | 9,472 |
| region stamps (4 B×) | dispatchCapacity | 2 KiB | 11 KiB | 18 KiB | 37 KiB |
| vis+occ list rings (32 B× ×4 slots each) | extended only | — (transient UBO) | 2×352 KiB | 2×576 KiB | 2×1.16 MiB |
| arena (wave 14: INITIAL — elastic) | 256 MiB initial at EVERY pin; grows ×1.5 on demand to the device ceiling (default 50% of the largest DEVICE_LOCAL heap, floor 256 MiB) | **256 MiB → ceiling** | 256 MiB → ceiling | 256 MiB → ceiling | 256 MiB → ceiling |
| staging ring | 32 MiB (unchanged — bounds streaming RATE, not the resident set; full ring backlogs gracefully) | 32 MiB | 32 MiB | 32 MiB | 32 MiB |

- **maxRegions headroom**: 2× the touchable grid (wave 3b shipped ~3× at
  rd 32; retention is grid-bounded, recon Q4.3). Overflow still
  drops-with-counter → coverage guard → passive → clamp-back: budgets can
  cost Meshelium a world, never pixels, never a slideshow.
- **Arena (SUPERSEDED by wave 14 — history kept honest)**: the wave-10/13
  row was min(1 GiB, ⌈256·(2rd+1)²/65²⌉ MiB), a quadratic anchored on the
  wave-9 plains measurement (51 MiB live @ rd 32 ≈ 255 quads/section).
  The owner's first real overworld session proved real terrain runs
  several-fold denser and overflowed the 256 MiB standard pin on a
  16 GiB card — the formula is retired. Since wave 14 the arena starts
  at 256 MiB at every pin and GROWS on demand (×1.5 grow-and-copy,
  fence-gated old-buffer retirement) up to a ceiling read from the
  DEVICE: max(256 MiB, 50% of the largest DEVICE_LOCAL heap), overridable
  via `meshelium.tune.arenaCeilingMiB` (initial via
  `meshelium.tune.arenaInitialMiB`). The heap SIZE is a static fact from
  `vkGetPhysicalDeviceMemoryProperties` (core 1.0) — NOT the
  `vmaGetHeapBudgets` usage estimate this doc rejected in wave 10
  (vanilla's allocator still lacks VK_EXT_memory_budget; that rejection
  stands). iGPUs report their shared system heap — the fraction then
  bounds Meshelium's share of SYSTEM memory (caveat + density arithmetic +
  full design: docs/VANILLA-SECTION-BUILD.md wave-14 note). Worlds that
  exceed even the ceiling still end in the honest guard trip — which now
  NAMES the budget and its size in the WARN and the options screen.
- **The 512-slot per-frame lists become SSBOs** (extended worlds only):
  the wave-5/6 lists are 16 KiB transient-memory UBO slices (spec-min
  `maxUniformBufferRange`), and vanilla's transient memory cannot mint
  STORAGE usage (wave-3b finding) — so `MesheliumFrameLists` owns two
  host-visible/coherent, persistently mapped STORAGE rings (4 slots =
  FREE_FRAME_LAG+1; ring-safety argument on the class), written in place
  by the drawer and bound as read-only SSBO slices. Shaders grow
  `#if MESHELIUM_LISTS_SSBO` variants with **unsized** arrays (terrain.task
  binding 8; region_raster.mesh + section_raster.task binding 0) — one
  extended pipeline serves any pinned capacity; uvec4/OccRegion strides
  are identical under std140 and std430, so the CPU byte layout is
  unchanged. Pipeline variants (TerrainDrawPipeline `extendedLists`,
  TerrainOcclusion ext statics) coexist with the standard ones and die at
  device close. With dispatchCapacity == maxRegions, the wave-5/6
  fail-open overflow paths become structurally unreachable on extended
  worlds; ring-creation failure falls back to the standard 512-cap UBO
  path (overflow fails open — culling degrades, parity never).
- Every scaled buffer logs its size at standup (the existing
  "residency up"/"occlusion GPU state up" lines + the new pin line and
  frame-lists line).
- **Mid-session config changes**: the option range widens/narrows
  immediately (CPU-side); buffer sizes and the DistanceManager tracker cap
  apply at the next world load (options screen note says so). Raising the
  OPTION mid-world within an already-extended world works within the
  pinned budgets; a world pinned standard that is pushed past its budgets
  ends in an honest guard trip + clamp-back.

## §6 — Harness

- `-Pmeshelium.rd=48` (build.gradle) → `-Dmeshelium.maxRenderDistance=48`
  (the config-override property, present at boot for the Options mixin) +
  `-Dmeshelium.test.rd=48` (test arming).
- **Vulkan+terrain run** (`MesheliumTerrainDrawTest.assertExtendedRenderDistance`,
  recalibrated after the first run's 157-chunk postmortem, §2b): option
  accepts 48 under the gate (range widened, set sticks); the test then
  **broadcasts like the real UI** (`options.save()` → ClientInformation)
  and waits for `ServerPlayer.requestedViewDistance ≥ 48` — the round
  trip the first run skipped; server follows
  (`getEffectiveRenderDistance` reaches 48 — proves the whole SP chain
  incl. the radius packet round trip); chunks cross the OLD horizon:
  client loaded chunks exceed **3,725** (the exact vd-32 sendable
  ceiling under `isWithinDistance` — impossible without both widened
  clamps and the broadcast) AND live regions outgrow the pre-leg horizon
  (3× baseline, floor 40 — the superflat world's single populated
  y-layer caps ~170 regions at rd 48, so the original 700-region
  noise-world bar is unreachable by construction there); drawer live
  with zero drops and no latches → screenshot `95_meshelium_rd48`; OR the
  coverage guard trips honestly, in which case the leg asserts the
  clamp-back fired (option back to 32, sessionClamps advanced). Growth
  budget 5 min (worldgen is the long pole; the wave-9 contention
  lesson).
- **GL run** (`MesheliumBootSmokeTest`, same -P): after the gate decision,
  the range must be vanilla (`rangeWidened()` false, `validateValue(48)`
  empty) and `set(48)` must not stick above 32 (vanilla resets to the
  initial value — asserted accordingly).

## UNVERIFIED / risks

1. **The §2b fix has not run on a JVM yet** (house rule: agents never run
   gradle/Minecraft). Every vanilla name/ctor/literal above is
   javap-verified against the real jar, and the 157 = vd-5
   `isWithinDistance` count is arithmetic-exact against the bytecode
   formula — but "save() unfreezes sending, chunks cross 3,725, regions
   grow" are claims only the coordinator's second `-Pmeshelium.rd=48` run
   can make. (The first run already verified: widening applies cleanly,
   the option accepts 48, the server follows to effective 48, and the
   ladder fix holds — those are now empirical.)
2. **@ModifyConstant breadth**: both server mixins match `intValue = 32`
   within one method each; javap shows exactly one such literal per
   target today. A future 26.2.x patch adding another 32 to those methods
   would silently widen it too — re-verify on version bumps.
3. **Mid-world clamp latency**: the monitor runs per client tick; vanilla
   can render above 32 for a few frames after a mid-world trigger before
   the clamp lands. Transient, documented in §4.
4. **Slider widget staleness**: a video-settings screen already OPEN when
   the range swaps keeps its old slider bounds until reopened (widgets
   capture the ValueSet at creation). Cosmetic; the value itself is
   validated live.
5. **PlayerTicketTracker cost at high caps** (§2): O(cap²) tracked chunks
   per player while extended is configured, regardless of the current
   option value. SP-only by construction.
6. **Worldgen pacing at rd 48+**: the harness leg budgets 5 minutes for
   the 700-region crossing on the dev rig; slower machines may need more
   (the assertion message says which counter stalled).
7. **The precision trade grows with distance** (PERFORMANCE.md): the
   16-byte vertex quantization + camera-relative fp32 shows sub-block
   misalignment through zoom mods at extreme range — inherited from
   Nvidium by design, revisit only if visible without magnification.

---

## Wave 13 — the UX rework: the vanilla slider is the interface

Owner playtest findings (2026-08-10, verbatim directives): (1)
"overriding the render distance does nothing"; (2) "i would like you to
be able to do this with the normal settings slider"; (3) "our settings
shouldnt be hidden and need mod menu to access i think they should
either be directly in graphics settings or add another setting in
options". A second playtest report added: vanilla-level fps with no
complaint from the mod — every symptom consistent with Meshelium silently
dormant for the whole session (most likely a GL boot: 26.2 defaults to
OpenGL, and dormancy there was working AS DESIGNED, but silently).

### §7 — Root causes: the wave-10 dead-end inventory

Each one made an owner action a silent no-op (or worse). All code paths
re-read from the shipped wave-12 sources.

1. **The ceiling default doubled as an enable switch.** `maxRenderDistance`
   defaulted to 32 (= OFF) because §5 pinned every GPU buffer from the
   CONFIG value — a 96 default would have pinned 9 472 regions/1 GiB
   arena for a player at rd 12. Consequence: out of the box the vanilla
   slider stopped at 32, and the only way to change that was finding
   Meshelium's own screen (finding 3).
2. **The Meshelium "Max Render Distance" row was not a render-distance
   control.** It raised the CEILING; the actual distance is the vanilla
   OPTION. Raising the row to 96 and closing the screen changed nothing
   visible unless the player then ALSO raised the vanilla slider —
   nothing said so ("does nothing", reading 1).
3. **Even with both raised, the current world could not follow.**
   `DistanceManager.<init>`'s PlayerTicketTracker range is
   construction-pinned per world load from `serverViewDistanceCap()` —
   a world opened while the config said 32 got a 32-range tracker, and
   chunks past ~34 can never receive a PLAYER_LOADING ticket (§2, cap
   #2). ChunkMap's clamp (cap #1) is live and DID widen, so the server
   view distance rose while loading stayed pinned: terrain simply ended
   at ~32-34 chunks. The options-row note said "applies at the next
   world load" — which led straight into dead-end 4.
4. **The rejoin path was a latent chunk-worker crash.** The
   `ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT` ladder widening (§2 cap
   #3) was gated on the BOOT-time config (`widenAtConstruction`, config
   > 32 at boot). The two server caps read the config LIVE. So: boot at
   ceiling 32 → raise the ceiling mid-session → rejoin the world → the
   tracker constructs at the new cap (levels to cap+2, up to 98) over a
   never-widened 46-rung ladder → the exact AIOOBE
   (`resortChunkTasks`, unguarded `queuesPerPriority.get(newLevel)` at
   ip 102–112) the first rd-48 run crashed on — in production, with no
   harness flag involved. Only a full game restart applied the raise
   safely.
5. **The options row silently accepted changes it could not honor.** On
   GL / no-mesh-shaders the row was ACTIVE, wrote the config, and the
   clamp-back monitor then kept everything vanilla — correct per the
   invariant, but the UI never said WHY nothing happened (the
   silent-refusal class).
6. **Dormancy itself was silent** (playtest 2). On the GL backend the
   wave-1 gate correctly parks the whole mod; after dismissing the
   one-time popup, NOTHING in the UI ever said "Meshelium is not
   rendering". Every extension being inert is then indistinguishable
   from every extension being broken. This is plausibly the true root of
   finding 1, regardless of which config dead-end the owner also hit —
   the status header (§10) closes the entire symptom class.

### §8 — The wave-13 semantics matrix (what applies when)

| Control | Where | Takes effect |
|---|---|---|
| **Render distance** (the control) | vanilla slider, Video Settings → Quality (option `qualityOptions[1]`, javap) | Range up to the Meshelium cap while the gate is open (default 96). Within the world's pinned GPU budget: on screen close, exactly like vanilla (`OptionsSubScreen.removed()` → `save()` → ClientInformation broadcast, §2b — server loading AND sending follow live; the tracker already spans the whole cap). Past the pinned budget: drawing continues at pinned capacity + once-per-world toast/WARN "rejoin to apply fully"; rejoin re-pins from the option. |
| **Max render distance cap** (`maxRenderDistance`, options-screen row) | Meshelium screen (Video Settings button / ModMenu / `/meshelium`) | Slider RANGE: same tick (`onConfigChanged` → the monitor's `applyRange`, both directions; lowering below the current option clamps + saves same tick). ~~An OPEN Video Settings screen shows the new bounds at its next open — vanilla rebuilds the slider per open~~ **WRONG — wave-15 correction (§15): 26.2 rebuilds only FRESH screen instances; returning to the cached parent repositions without rebuilding. The Meshelium screen now rebuilds the parent's widgets on Done after a cap change.** Server caps: `serverViewDistanceCap()` is read live (ChunkMap clamp per change; tracker at next world load). |
| Terrain rendering / Occlusion / Debug stats | Meshelium screen | Next frame/pump (wave-8 resolvers, unchanged; now stated INLINE on each row). |
| ~~Retention / Retain limit~~ | ~~Meshelium screen~~ **no UI since 2026-08-11**: `retainTerrain` / `retainTerrainMinutes` in `config/meshelium.json`, or `-Dmeshelium.retainTerrain` / `-Dmeshelium.retainSeconds` | Next frame/pump, unchanged (the wave-11 resolvers are re-read live and the machinery is untouched). Retired from the screen and defaulted OFF when the owner chose to pair with Bobby: see the multiplayer section above and SPEC row 11. |
| Backend popup | Meshelium screen | Next game start (unchanged, stated inline). |
| Chunk-task ladder (+64 rungs) | none — automatic | Boot, unconditional, both backends (§7 dead-end 4's fix). Not a range: GL keeps vanilla clamps everywhere; the extra rungs are unreachable there. |
| GPU buffer sizes | none — automatic | World standup, pinned from the OPTION (§9). Mid-world raises: pinned capacity + hint; rejoin applies fully. |

Clamp-back (§4) is UNTOUCHED: all three triggers, both toast variants,
the server-cap second enforcement layer, and the GL harness assertions
are byte-identical in intent — a GL or passive session still never
keeps an option above 32.

### §9 — Scaling change: pin from the OPTION (supersedes §5's pin source)

`MesheliumScaling.pinForWorld(int optionRd)` (caller:
`MesheliumTerrainGpu.create()`, render thread, same pin point as §5):

```text
pinnedRd(option) = option ≤ 32 ? 32 (the standard wave-≤9 snapshot)
                 : min(config ceiling, nextMultipleOf8Above(option))
      nextMultipleOf8Above: 33→40, 40→48, 48→56 (strictly next —
      a headroom band so a small mid-world raise stays in budget)
```

- The RAW option is used, deliberately not `getEffectiveRenderDistance()`:
  the effective value's server-radius half can be a stale login value in
  the standup frame (the radius packet and the tickServer follow race
  the first frames), and on small-radius multiplayer servers the option
  is the honest budget wish for wave-11 retention.
- §5's formula table is unchanged — only its INPUT moved from the config
  ceiling to `pinnedRd(option)`. A player at option 12 under the new
  default ceiling of 96 pins exactly 2 048 regions / 512 slots / 256 MiB
  — which is what makes the 96 default free and turns the config into a
  pure cap (§7 dead-end 1). (Wave 14: the 256 MiB is the arena's INITIAL
  size — the arena itself is elastic now; records/slots stay pinned
  exactly as written here.)
- **Mid-world raise past the pinned budget** (option > pinned maxRd,
  monitor-detected under the healthy-drawer branch only): once-per-world
  toast + WARN (`meshelium.rd.rejoin.*`, keyed on the pinned snapshot's
  identity, re-armed by each `pinForWorld`). The drawer keeps working at
  pinned capacity: >512 dispatched regions on a standard-pinned world
  fail OPEN per the wave-5/6 paths (culling degrades, parity never); a
  true arena overflow first GROWS the arena (wave 14, up to the device
  ceiling), then evicts retained terrain (wave 11), and only then trips
  the coverage guard honestly → passive → clamp-back — the §4 path, no
  crash anywhere (region-budget overflow: same ladder minus the growth
  rung).
- `current()` before any pin now returns the STANDARD snapshot (wave 10
  computed an unpinned view from config — under a 96 default that would
  claim 1 GiB sizes no world has paid for).
- The remaining ceiling-derived cost is the PlayerTicketTracker range
  (§2 cap #2): with the 96 default every gate-open+terrain-enabled SP
  world now pays ~38k tracked chunks per player up front. Accepted and
  documented (risk row below); lowering the cap row reduces it at the
  next world load. Kept ceiling-derived ON PURPOSE: an option-derived
  tracker would recreate dead-end 3 (a construction cap the slider
  cannot cross mid-world).

### §10 — Vanilla UI integration (the Video Settings seam)

All javap-cited against the 26.2 jar:

- `VideoSettingsScreen extends OptionsSubScreen`;
  `OptionsSubScreen.init()` = `addTitle() → addContents() → addFooter()`;
  `addContents()` assigns `this.list = new OptionsList(minecraft, width,
  this)` (ip 27) then calls `addOptions()` (ip 31) — so at
  `addOptions` HEAD the list exists and is empty.
- `OptionsList.addHeader(Component)` and `addBig(AbstractWidget)` are
  public; `OptionsList$Entry.big(AbstractWidget, Screen)` does
  `widget.setWidth(310)` itself (ip 1–4).
- **`VideoSettingsScreenMixin`** (`@Inject(method = "addOptions", at =
  @At("HEAD"))`): adds a "Meshelium" header + a "Meshelium Settings..."
  button opening `MesheliumOptionsScreen(parent = this)`. HEAD, not TAIL:
  the row sits ABOVE the Display section, visible without scrolling
  (a bottom row would be hidden again), and needs no ordinal targeting.
  Done-navigation returns to Video Settings, whose `init()` rebuild
  means the slider already shows any cap change made inside.
  Added on BOTH backends — on GL the screen opens with rows locked and
  the reason shown (never the silent-refusal class).
- `removed()` (→ `options.save()` → §2b broadcast) and `onClose()` are
  untouched — the production apply path is still "close the screen".
- Harness note: fabric's `clickScreenButton` walks `renderables` and
  descends only through `LayoutElement.visitWidgets`; nothing in the
  `AbstractSelectionList` hierarchy overrides `visitWidgets` (javap
  census), so a button inside an OptionsList entry is unreachable for
  it — the boot-smoke leg walks `Screen.children()` → OptionsList →
  entries → widgets and presses via
  `Button.onPress(new MouseButtonInfo(-1, 0))` (fabric's own
  pressMatchingButton call shape).

**The status header** (owner playtest 2): the first line of
`MesheliumOptionsScreen`, rebuilt every `tick()` via
`StringWidget.setMessage` (the widget renders `getMessage()` live —
javap). ACTIVE shows `TerrainDrawer.lastDrawnSections()` + a visibly
ticking `framesDrawn()` (a counting header cannot be faked by a dormant
session); NOT RENDERING names the exact reason: OpenGL (+ the wave-1
popup's [Enable Vulkan] affordance, same option write + restart
hand-off, same broken-promise rule when the option already says
VULKAN), no mesh shaders, terrain disabled (config or property), drawer
error latch, coverage-guard passive. Thread/class-loading legality:
`tick()` is client-thread; the vk probes are volatile statics touched
only under a decided VULKAN_MESH_SHADERS gate (the §4 monitor's own
pattern).

### §11 — Wave-13 harness additions

- **Boot smoke, both backends**: ladder ≥ 99 rungs unconditionally;
  Video Settings opens → shot `B0_meshelium_video_settings_button` (row at
  HEAD = visible) → click-through → `MesheliumOptionsScreen` with
  `gateLocked()` matching the run (GL: locked + status header contains
  "NOT RENDERING"; Vulkan title: unlocked + "READY") → shot `B1` → Done
  returns to Video Settings → Done returns to title. GL clamp-back
  assertions from wave 10 unchanged.
- **Draw test (Vulkan+terrain)**: world stands up at option 12 (set +
  save at title) → pinned snapshot must be STANDARD despite the >32
  ceiling (buffers-from-option proof) + ladder probe; live-range leg
  (config-property-absent runs): ceiling 96 widened live → lower to 40
  narrows same tick (48 illegal, 40 legal) → 32 restores vanilla-exact
  (cap == 32) → restore re-widens.
- **rd-48 leg** (recalibrated): the mid-world raise now must ALSO fire
  the rejoin hint exactly once (and must NOT re-pin the snapshot);
  everything else (broadcast, 3 725-chunk crossing, region growth,
  zero drops or honest guard trip) unchanged. Then a SECOND world at
  the still-raised option must pin extended
  (`min(ceiling, next8(48)) = 48` with the harness ceiling 48), draw,
  fire NO hint, shot `96_meshelium_rd48_rejoined` — the SSBO frame-lists
  path's standing coverage now lives in this leg (world 1 pins standard
  by design).

### §12 — Wave-13 UNVERIFIED / risks

1. **Nothing here has run on a JVM** (house rule). Every vanilla
   name/ip above is javap-verified; the semantics matrix and the two
   playtest fixes are claims only the coordinator's runs can confirm:
   GL + Vulkan boot smokes, the plain Vulkan+terrain run, and the
   `-Pmeshelium.rd=48` pair.
2. **Top risk — owner's change still silent anywhere?** The remaining
   candidate: a mid-world CAP raise still cannot un-pin the CURRENT
   world's tracker if that world was created before wave 13 semantics
   (or under quickPlay's undecided gate → vanilla caps). The rejoin
   hint covers the GPU half only; the tracker half is why the 96
   default matters — worlds created under the gate now always get the
   full-cap tracker. A world created with the cap LOWERED (say 40) and
   then the cap raised to 96 mid-world will follow the slider only to
   40 until rejoin, with no hint (the GPU pin may still cover it).
   Documented; acceptable because the cap row is explicitly "next world
   load" for server range — but it is the one residual silent-ish path.
3. **GL/passive widened-range exposure**: unchanged from wave 10 —
   tick-granularity transients (§4) only. The new surfaces add none:
   the ladder is not a range; the status header only reads; the Video
   Settings button adds no option writes.
4. **PlayerTicketTracker at cap 96 is now the DEFAULT SP cost** under
   the gate (~38k tracked chunks/player, a few MiB; O(cap²)). Wave 10
   accepted it as opt-in; wave 13 makes it standard — if a bench run
   shows measurable tick cost, derive the tracker from
   `max(32, option at world creation)` instead and accept the rejoin
   requirement for slider raises (would reopen §7 dead-end 3 partially;
   decide on numbers).
5. **`MultiLineTextWidget`/`StringWidget` layout at small GUI sizes**:
   the row notes (widget 200 px plus note 104 px) fit 320-wide UIs on
   paper, and the gate-locked screen (status, banner, button, 7 rows,
   note) is the tallest variant yet; not rendered — the B1/70
   screenshots are the check.
5b. **The unconditional ladder write now class-loads
   `ChunkTaskPriorityQueue` (→ ChunkLevel/ChunkStatus) during
   `Options.<init>` on EVERY boot, GL included.** The identical call at
   the identical injection point ran clean on the armed Vulkan rd-48
   runs (registries bootstrap in `Main.main`, before `Minecraft.<init>`),
   but a GL boot never executed it before wave 13 — the GL boot smoke is
   the verification.
6. **`Screen.tick()` cadence**: the status header updates per tick, not
   per frame; framesDrawn advances several frames per tick, so the
   counter visibly moves — but on a paused integrated server (Esc menu
   is not this screen, so N/A in practice) it could freeze while still
   saying ACTIVE. Not misleading: a frozen game draws nothing new.
   (Wave 15 dropped the frame counter from the header; the live section
   count keeps the same argument.)
7. **Localization**: only `en_us` ships, as before.

## Wave 15 — settings/UX rework: live grow, real sliders, the back-out fix

Owner playtest (2026-08-10, real server, 1200+ fps, "i really like this
it work so well") returned a 12-item settings feedback list. The three
mechanism-level items land here; the UI items are documented on
`MesheliumOptionsScreen`.

### §13 — Live mid-world raise: the pinned budget GROWS (supersedes §9's rejoin row)

Owner: "is there a way to make it not have to rejoin after increasing
render distance". Yes — the wave-14 grow-and-copy machinery generalizes
to the two remaining rd-sized GPU buffers.

**The rd-sized inventory** (what is actually pinned, audited):

| Resource | Sized by | Live-raise treatment |
|---|---|---|
| Terrain arena | nothing (elastic since wave 14) | already grows on demand |
| Region records (16 B/id) | `maxRegions` | **grow-and-copy** (`MesheliumTerrainGpu.growRecords`) |
| Section records (8 KiB/id) | `maxRegions` | **grow-and-copy** (same call, atomic pair) |
| Occlusion section stamps A/B (1 KiB/id) | `maxRegions` | **drop + lazy recreate** (`TerrainDrawer.onPinnedRegrow`) — zero-filled stamps cost one standup-identical frame (phase A empty, phase B repaints same frame) |
| Occlusion region stamps + frame-list rings | `dispatchCapacity` | drop + lazy recreate (same call; the SSBO pipelines have unsized arrays, one pipeline serves any capacity) |
| RegionStore id budget | live read of the snapshot | follows the snapshot swap automatically |
| Staging ring (32 MiB) | per-frame RATE, not rd | untouched |
| PlayerTicketTracker (server) | config CEILING at world load | already spans the whole cap (wave 13) — no rejoin needed for raises up to the cap; the §12.2 lowered-cap residual remains (below) |

**The flow** (all bytecode/threading arguments carried on the code):

1. The per-tick monitor's healthy branch sees `option > pinned.maxRd()`
   and calls `TerrainResidency.requestPinnedGrow(option)` — a volatile
   write, nothing else (`MesheliumExtendedRd.maybeGrowOrHint`).
2. The NEXT pump (render thread, inside vanilla's lock window — the
   proven wave-14 grow site) consumes it BEFORE any drain:
   `growRecords` allocates the bigger pair, records zero-fill-tail +
   copy-old-bytes-to-identical-offsets + full barrier on one transient
   command buffer, `encoder.execute`s it (spliced strictly before this
   pump's own endFrame buffer — staged record copies overwrite copied
   bytes in a barrier-ordered WAW, never the reverse), swaps the fields,
   and parks the old pair on the SAME `retiredBackings` fence queue as
   outgrown arenas (FREE_FRAME_LAG = 3, destroy-time re-assert).
3. Only then does `MesheliumScaling.growPinned` swap the snapshot — the
   ordering is load-bearing: `RegionStore.maxRegions()` live-reads the
   snapshot, so an id the buffers cannot hold can never be admitted.
4. `TerrainDrawer.onPinnedRegrow()` (called inside `growRecords`, same
   pump) drops occlusion stamps + frame lists + the cached snapshot;
   they recreate lazily at the new sizes before this frame's first draw
   (the stamps are regionId-indexed — a new-budget id against old-sized
   stamps would be an OOB shader write, which is why the drop is
   same-pump, not next-frame).
5. The residency store then expands in place; the new section-records
   handle republishes through the draw snapshot (epoch bump; in-flight
   frames read the fence-parked old buffers — the wave-14 era argument).

**Failure = the old behaviour.** A refused allocation latches
`pinnedGrowFailed` for the world; the monitor then falls back to the
once-per-world **rejoin hint** (same keying as wave 13 — the hint is
now the fallback, not the rule). Every invariant is untouched:
clamp-back triggers, GL dormancy (the request path is only reachable
from the healthy-drawer branch under the gate), the coverage guard
(drain-time region-budget pressure with a grow in flight requeues
instead of dropping), and parity (no draw-path changes — the transition
frame is the world-standup frame, which the parity shots already cover).

**Race window honesty**: between the slider write and the monitor's
END_CLIENT_TICK there is a sub-tick window where vanilla could in
principle build a beyond-budget section; it cannot in practice (the
server has not even been told the new distance until `Options.save()`
broadcasts). If a drop ever slips through anyway, the guard trips
honestly — passive + clamp-back, the unchanged §4 path.

**Updated semantics matrix rows** (replacing §8's "GPU buffer sizes"
and §9's mid-world-raise bullet):

| Control | Takes effect |
|---|---|
| Vanilla slider raised mid-world, within the cap | Server half: live (unchanged). GPU half: pinned budget grows in place within ~1 frame of the option settling; NO rejoin, NO hint. |
| Same, but the grow failed (GPU allocation refused) | Drawing continues at pinned capacity; once-per-world "rejoin to apply fully" toast/WARN (the wave-13 behaviour, now the fallback). |
| Cap raised mid-world above what the world's tracker was built with | Slider range widens live; loading past the OLD cap still needs a rejoin (tracker is construction-pinned per world — the §12.2 residual, unchanged and documented). |

### §14 — The >96 cap: custom entry, the wire cliff, and the cost curve

Owner: "a slider with a custom value option for people who really want
to crank it". The options-screen cap row is now a real slider over
32..96 (step 8) plus a Custom box accepting values up to the hard max.

**The hard max is 120, and it is not a taste choice.** The client's
requested view distance reaches the server inside `ClientInformation`,
whose stream codec writes it with `FriendlyByteBuf.writeByte` and reads
it back with `readByte():B` — a SIGNED byte (both disassembled, 26.2
jar; the write at the codec's ip 14, the read at ip 8). 127 is the
largest value that survives; 128 arrives as −128 and
`ChunkMap.getPlayerViewDistance`'s `clamp(requested, 2, serverView)`
turns it into 2 — the whole server-follow chain silently collapses to
minimum view distance. 120 is the last 8-lattice stop with margin under
that cliff.

**The cost curve** (why the slider stops at 96 and the box exists —
tracker chunks = (2·cap+1)²; regions/records from §5's formulas):

| cap | tracker chunks/player | maxRegions (if pinned there) | section records | stamps (×2) |
|---|---|---|---|---|
| 96 (slider max, default) | 37 249 | 9 472 | 74 MiB | 9.25 MiB |
| 112 | 50 625 | 12 544 | 98 MiB | 12.25 MiB |
| 120 (hard max) | 58 081 | 14 336 | 112 MiB | 14 MiB |
| 128 (rejected) | 66 049 | — | — | **wire overflow: view distance wraps negative** |
| 192/256 (rejected) | 148k/263k | 35k/61k | 274/480 MiB | tracker CPU + records leave sanity long before the wire even matters |

The tracker cost is paid per SP world load at the CONFIG cap (the §9
decision to derive it from the ceiling is unchanged — it is what makes
mid-world raises live); the records/stamps costs are paid only when a
world actually PINS (or grows to) that distance. The chunk-task ladder
now widens by (hard max − 32) = 88 rungs at boot (the +64 literal
followed the old 96; `widenChunkTaskLadder` derives from the constant).

The custom box states the cost and the wire bound in its own text; the
codec/range widening is value-generic (wave 10's `IntRange` +
`Codec.intRange` swap), and the harness round-trips a 112 through the
swapped persistence codec (the wave-13 OptionsMixin lesson at custom
scale). ~~The retention limit gets the same treatment: slider over a
log-scale lattice (Off, 1 min .. 24 h, the owner's "our time windows
are too small"), Custom box in minutes up to 7 days (10 080).~~
**Retired 2026-08-11**: the retention limit's slider and Custom box left
the screen with the retention toggle (multiplayer section above, SPEC
row 11). `retainTerrainMinutes` is a config-file value now; 10 080 is
documented advice on `MesheliumConfig.MAX_RETAIN_MINUTES` rather than an
enforced box bound.

### §15 — The back-out bug: root cause (bytecode) and fix

Owner: "changing max render distance causes you to have to fully back
out of the menu screen not just the sub menu". REAL BUG; the wave-13
claim ("vanilla rebuilds the OptionsList per screen-open") was wrong
for the return path. The actual 26.2 mechanism:

- `Gui.setScreen(S)` calls `S.init(width, height)` on EVERY transition
  (ip 218–239) — so far so wave-13.
- But `Screen.init(int,int)` guards the widget-building `init()` behind
  a per-instance `initialized` flag: `if (!initialized) { init();
  setInitialFocus(); } else { repositionElements(); }` then
  `initialized = true` (ip 10–34). The flag's ONLY write is ip 33 —
  nothing ever clears it. `Screen.resize` likewise only repositions.
- Returning from the Meshelium screen hands back the CACHED
  `VideoSettingsScreen` instance → `repositionElements()` only → the
  OptionsList survives → the render-distance slider widget keeps the
  `ValueSet` it captured at creation (`OptionInstance.createButton`
  reads the `values` field at widget creation, ip 0–3). Backing out of
  the whole tree discards the instance; re-entering builds a fresh one
  with `initialized == false` — exactly the owner's observed workaround.

**Fix**: `MesheliumOptionsScreen.onClose()` — when the cap changed while
the screen was open and the parent is an `OptionsSubScreen` — invokes
vanilla's own `Screen.rebuildWidgets()` (`clearWidgets → clearFocus →
init → setInitialFocus`, disassembled) on the parent via a mixin
Invoker (`ScreenInvoker`), AFTER `setScreen` so the parent's
`init(II)` has refreshed width/height first. Same instance, fresh
widgets, navigation chain intact. Regression pinned in the boot smoke:
open Video Settings → Meshelium → change cap → Done → assert the SAME
parent instance is showing, its renderDistance widget is a NEW object,
and the option range narrowed — without ever leaving the options tree.

### §16 — Wave-15 harness delta

- **Boot smoke** (both backends): ladder bar follows the constant
  (≥ hard max + 3 = 123 rungs); lang lint (no U+2014/U+2013 anywhere in
  `en_us.json`); retention-limit values round-trip the resolver (300 and
  4 320 minutes; kept unweakened after the 2026-08-11 retirement, where
  it now guards hand-edited config values instead of custom-box input);
  pinned-grow state provably dormant with no world. Vulkan only: the §15
  back-out regression walk.
- **Draw test**: the live-range leg adds the >96 probes (cap 112 legal /
  120 not, swapped codec parses 112, server cap follows 112, config 130
  clamps to 120). The rd-48 leg's hint expectations became grow
  expectations: pinned snapshot GROWS to `min(cap, next8(48))`,
  `pinnedGrows` > 0, `recordGrowths` moves when maxRegions grew, the
  hint counter stays FLAT, `pinnedGrowFailed` stays false, zero drops
  (the honest guard-trip branch is unchanged). The world-2 rejoin leg
  stays as the fresh-pin proof.
- **Torture**: the options smoke drives the custom cap box end-to-end
  (121 and non-numeric unacceptable; 112 lands in config + live range +
  slider label; restored after).

### §17 — Wave-15 UNVERIFIED / risks

1. **Nothing here has run on a JVM** (house rule). The grow flow's
   fence/ordering arguments are the wave-14 ones re-instantiated, but
   the record-buffer grow, the same-pump occlusion recreate, and the
   §15 walk are claims until the coordinator's runs: GL + Vulkan boot
   smokes, plain Vulkan+terrain, `-Pmeshelium.rd=48` pair, torture.
   A `--vulkanValidation` leg once over the rd-48 run is the record-grow
   fence check (same as wave 14's arena leg).
2. **The transition frame**: one frame after a grow draws with
   zero-filled stamps (phase A empty → phase B repaints same-frame).
   Standup-identical by construction; a visible flicker would falsify
   this — the coordinator should eyeball the rd-48 leg's video/shots.
3. **§12.2 residual unchanged**: a world created under a LOWERED cap
   and raised past it mid-world follows the slider only to the old cap
   until rejoin, with no hint (tracker construction-pinned; the GPU pin
   now follows, which makes the tracker the only stale half). Still
   documented-not-fixed; fixing it would mean rebuilding the ticket
   tracker live — out of scope.
4. **Custom caps and other mods**: a foreign ValueSet on the
   renderDistance option still disables the whole feature (wave-10
   no-fight rule, unchanged); the custom box then writes a config value
   that never takes effect — the row is locked only by gate/override,
   not by the foreign-ValueSet case. Cosmetic at worst (range never
   widens, clamp-back never fires because `captured` stays false).
5. **Screen-height budget**: the locked-GL variant (banner + button +
   7 rows) sits near the 240-unit floor at GUI scale "auto"; spacing
   was tightened to 2 and the banner strings shortened, but B0/B1/70
   screenshots remain the check.
6. **`rebuildWidgets` on a live screen**: vanilla calls it on screens
   it owns; calling it on `VideoSettingsScreen` from outside is novel
   placement of a vanilla primitive (idempotent by construction —
   clearWidgets first). The §15 walk exercises exactly this path.
