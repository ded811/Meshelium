# Frontier holes at the live/retained seam: the wave-17 fix design

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


Recon and design, 2026-08-10, on the wave-16 jar (HEAD 707d671). DESIGN
ONLY, no code changed by this document. Method: `javap -p -c` against the
real 26.2 merged jar
(`attack-of-the-bteam-1.26.2/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2.jar`),
plus a source audit of Meshelium's wave-11/16 code, plus adjudication of four
independent recon lenses (frontier, culling, lifecycle, exposure). Every
vanilla claim carries a class, method, descriptor and instruction offset.
Claims I could not prove are marked UNVERIFIED and are collected at the end.

**The bug under investigation** (owner, real server, 2026-08-10, wave-16
jar): *"im having some weird missing empty chunks on the transition chunks
between what we have saved and what we have rendered in the server like i
fly around and i get missing chunks there."*

Read `docs/MP-RETENTION-RECON.md` first. This document assumes its findings
1 through 6 and its "Wave 16 as BUILT" section, and it closes that file's
UNVERIFIED item 2.

---

## TL;DR

**Primary mechanism: the un-compiled shell. Absence of source data, not
loss of it.**

Vanilla's fog wall and vanilla's compilable set are the same cylinder,
because they are computed from the same int. Wave 16 moved the fog cylinder
out to `option * 16` and left the compile cylinder at
`getEffectiveRenderDistance() * 16`. Everything the owner is seeing lives in
the shell between the two, and that shell contains exactly two kinds of
pixel: wave-11 retained terrain (positions that were once inside the compile
cylinder while Meshelium was watching) and nothing at all. There is no third
thing. Wave 16's premise was that the shell would be full of retained
terrain; it is not, because retention can only ever hold what vanilla once
built, and the shell's inner boundary is precisely where vanilla stops
building.

The decisive supporting fact is a NEGATIVE one, verified in code below:
Meshelium never drops a retained copy prematurely. Wherever a retained copy
exists at a position, it keeps drawing across the whole of that position's
rebuild. So every hole at the seam is a position that never had a copy, and
the question is not "what loses terrain" but "what never had it".

**Class of defect: (b) pre-existing, exposed.** Vanilla has the identical
holes. Vanilla hides them behind fog it derives from the same int. Nothing
in the mesh lifecycle is at fault for the primary.

**Six real Meshelium defects were found alongside it**, all of class (a), all
of which wave 16 also exposed, none of which can be sold as the fix for the
owner's ring. They are listed and designed below and they should be fixed on
their own merits.

**Recommended presentation fix: the adaptive ramp, default ON, fog site
only.** `MesheliumRetentionHorizon.fogRenderDistance` returns
`clamp(measuredCoverageHorizon, effective, option)` instead of the raw
option. `farPlaneRenderDistance` stays on the raw option (ramping it would
pop whole regions through a real cull frustum, see M9). The ramp needs a
coverage measure Meshelium cannot compute today, and building that measure is
also the only way to write a hole test that can tell a hole from
legitimately absent terrain.

**Diagnosis must land before anything else.** Two of the discriminating
probes are unreadable in a real session today.

---

## 1. The organising fact: vanilla's fog wall IS the boundary of vanilla's compilable set

This is the single geometric statement that everything else hangs off, and
neither the wave-16 design nor any of the four lenses stated it in full.

### 1.1 The fog wall is a cylinder of radius and half-height `rd * 16`

`FogRenderer.setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;`

* ip 9 to 14: `blocks = rd * 16` (`iload_2`, `bipush 16`, `imul`, `i2f`).
* ip 118 to 130: `edge = Mth.clamp(blocks / 10.0f, 4.0f, 64.0f)`.
* ip 135 to 142: `putfield FogData.renderDistanceStart = blocks - edge`.
* ip 145 to 149: `putfield FogData.renderDistanceEnd = blocks`.

`assets/minecraft/shaders/include/fog.glsl` (extracted from the same jar):

```glsl
float fog_cylindrical_distance(vec3 pos) {
    float distXZ = length(pos.xz);
    float distY = abs(pos.y);
    return max(distXZ, distY);
}
```

so the render-distance term is `max(|xz|, |y|)`, a CYLINDER, capped in Y at
the same number of blocks it reaches in XZ. `apply_fog` mixes by
`fogValue * fogColor.a`.

**Recon UNVERIFIED item 2 is now closed.** `FogRenderer.computeFogColor`
ends at ip 550 to 562 with `org/joml/Vector4f.set:(FFFF)` whose fourth
argument is `fconst_1` at ip 558, unconditionally, on every branch of every
fog environment. `FogColor.a == 1.0` always, so at fog value 1.0 the mix is
a total replacement. The pre-wave-16 wall was 100 percent opaque, and wave
16 revealed 100 percent of what was behind it, warts included.

Meshelium's `terrain.frag` is a verbatim copy of the same functions (lines 83
to 90, consuming `cylindricalVertexDistance` at line 66 and applying at line
178), so Meshelium's draws obey the identical wall.

### 1.2 Vanilla's BFS reach is the same cylinder, in chunk units

`SectionOcclusionGraph.getRelativeFrom(JLnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;Lnet/minecraft/core/Direction;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;`
refuses a neighbour on two independent tests, and a refusal is `aconst_null`
which the caller turns into `goto 864` (skip, no octree add, no
propagation):

* ip 8 to 19: `isInViewDistance(cameraNode, neighbourNode)` false returns
  null. That private method (ip 0 to 26) is
  `ChunkTrackingView.isInViewDistance(SectionPos.x(cameraNode),
  SectionPos.z(cameraNode), viewArea.getViewDistance(),
  SectionPos.x(neighbourNode), SectionPos.z(neighbourNode))`, which is
  `isWithinDistance(..., false)`.
* **ip 20 to 44: a VERTICAL gate nobody reported.**
  `Mth.abs(SectionPos.y(cameraNode) - SectionPos.y(neighbourNode)) >
  viewArea.getViewDistance()` returns null. So the BFS cannot reach more
  than E SECTIONS above or below the camera's own section, where E =
  `ViewArea.getViewDistance()`.

`ChunkTrackingView.isWithinDistance(IIIIIZ)Z`, bytecode read in full (ip 0
to 77):

```text
pad  = arg5 ? 2 : 1                       (ip 0-10: iconst_2 / iconst_1)
dx   = max(0, |x - cx| - pad)             (ip 12-26)
dz   = max(0, |z - cz| - pad)             (ip 28-43)
return dx*dx + dz*dz < vd*vd              (ip 45-77, ifge -> false: STRICT <)
```

The camera argument really is the camera. `SectionOcclusionGraph.runUpdates`
ip 0 to 11 computes `SectionPos.of(cameraPos).asLong()` into local 9, and
local 9 is the first argument at the `getRelativeFrom` call site, ip 259 to
266.

So the set vanilla can BFS to is a cylinder: a pad-1 disc of radius E chunks
in XZ, and `|dy| <= E` sections in Y, centred on the camera's section. Both
halves measure `E * 16` blocks. **That is the same cylinder as the fog
wall,** because `ViewArea.getViewDistance()` and the fog's `rd` are both
`Options.getEffectiveRenderDistance()` (`LevelRenderer.invalidateCompiledGeometry`
ip 149 `new ViewArea(...)` with the effective read at ip 174;
`GameRenderer.extractCamera` ip 51 for the fog).

### 1.3 Compiles only happen inside that cylinder, and only inside the frustum

`LevelExtractor.extract` ip 441 to 448: the dirty loop iterates
`LevelRenderer.visibleSections()` and nothing else. A section absent from
that list is never scheduled, never compiled, never uploaded to Meshelium.
`visibleSections` is cleared and refilled only by `LevelExtractor.applyFrustum`
ip 28 to 57, which calls `SectionOcclusionGraph.addSectionsInFrustum`,
which walks the octree, and a node reaches the octree only through
`runUpdates` ip 117 to 129, which is only reached for nodes the
cylinder-bounded BFS actually polled. `runUpdates` ip 61 to 98 additionally
parks any node whose chunk is not in `loadedChunks` into
`sectionsWaitingForChunkLoads` and skips it entirely.

**Therefore: Meshelium's record set is, exactly, the union over time of
(BFS cylinder AND camera frustum AND chunk arrived).** Retention cannot
widen it. `TerrainResidency.onMeshReleased` line 995 returns immediately
when `resident.get(mesh)` is null, and an un-compiled slot only ever
releases the `CompiledSectionMesh.UNCOMPILED` sentinel, which was never
resident. There is nothing to retain for a position vanilla never built.

### 1.4 The seam is a SQUARE ring, and the old curtain was ROUND

The ViewArea grid is a square: `RotatingSectionStorage.<init>` ip 41 to 47
sets `sectionGridSizeXZ = radius * 2 + 1`, and `containsSection` ip 18 to 86
is a `centre +/- radius` test per axis. Retention deposits at Chebyshev E+1
(a slot leaves the square and `reset()` retains it at its old node).

So the retained band's inner boundary is a SQUARE at Chebyshev E+1, while
the old fog wall was a CIRCLE of radius E*16 inscribed in it. The shell
between them is thin on the compass axes (where the pad-1 disc reaches
`|dx| = E`, exactly the square's edge) and deep at the diagonals (where the
disc test at the corner is `2*(E-1)^2 < E^2`, false for every E >= 4).

That is why the holes do not read as a clean ring. They read as four lobes,
deepest at north-east, north-west, south-east and south-west.

---

## 2. The negative finding that reframes everything

**Meshelium never drops a retained copy before its replacement exists.**
Verified in the shipped code, not inferred:

* The only supersede of a retained copy by a rebuild is in
  `TerrainResidency.drainPendingUploadsLocked`. The replacement `Resident r`
  is constructed at line 1546, `regionStore.addOrReplace` runs at lines 1551
  to 1552, the `previous.orphanedAtMillis != 0` branch removes and parks the
  old copy at lines 1561 to 1577, `resident.put(mesh, r)` is line 1582 and
  `drawEpoch++` is line 1592. All of that is one `synchronized (LOCK)` hold,
  and `drawSnapshot` takes the same LOCK (line 733), so no frame can observe
  the retained copy gone and the replacement absent. The old arena range
  additionally survives `FREE_FRAME_LAG = 3` pumps (line 135).
* The supersede fires at UPLOAD, which is strictly after build completion.
  The build thread reaches `enqueueUpload` only at
  `CompiledSectionMesh.<init>` TAIL (`SectionBuildTap.onMeshConstructed`
  line 110), which `CompileTask.doTask` performs at ip 153 to 164, after
  `SectionCompiler.compile` returned at ip 100. Nothing happens to the
  retained copy at build START.
* Re-entering the grid does not touch the retained map.
  `RenderSection.setSectionNode(J)` calls `reset()` at ip 0 to 1 BEFORE the
  `putfield sectionNode` at ip 4 to 6, and `reset()`'s release concerns the
  DEPARTING mesh only. `retainLocked` (TerrainResidency.java:1060 to 1071)
  keys on the departing `Resident`'s own stored `sx/sy/sz`, captured at
  upload, never on the slot's new node.

**Consequence.** The hole surface is confined to positions that never had a
copy. Any future investigation that starts from "the retained copy is being
dropped when the player flies back in" is re-litigating a settled question.

---

## 3. Ranked mechanisms

Ranked by how well each explains the owner's exact words. "Owner fit" scores
three things his sentence asserts: the hole is AT the transition, terrain
exists on BOTH sides of it, and it appears while flying.

| # | Mechanism | Class | Persistence | Owner fit |
|---|---|---|---|---|
| **M1** | **The un-compiled shell (PRIMARY)** | (b)+(c) | both, by sub-shape | exact |
| M2 | bfsOnly latch drops live-but-unlisted sections | (a) | persistent | exact, IF latched |
| M3 | Translucent live half sourced from `visibleSections` | (a) | persistent | partial (water only) |
| M4 | Decode miss aliased onto the empty-compile signal | (a) | persistent | good, scattered |
| M5 | Out-of-bracket free for a position outside the grid | (a) | persistent | exact, narrow race |
| M6 | Pending upload discarded inside a reset bracket | (a) | persistent | good, bursty |
| M7 | Pump is one frame behind vanilla | (a) | transient | negligible at 1200 fps |
| M8 | Eviction spends oldest first, which is not farthest first | (a) | persistent | inert today |
| M9 | Real far plane at `depthFar` in the CPU cull | (b) | persistent | wrong radius |
| M10 | GateIndex truncation above option ~72 | (a) | transient | not the symptom |

### M1 (PRIMARY) The un-compiled shell

Everything in section 1. One mechanism, five sub-shapes, all of which sit in
the shell between the compile cylinder and the new fog cylinder:

* **S1, the leading crescent. TRANSIENT.** Positions that have entered the
  square grid but have not yet entered the pad-1 disc, or whose chunk data
  has not arrived, or whose compile has not landed. Vanilla draws nothing
  there because `reset()` leaves the slot holding `CompiledSectionMesh.UNCOMPILED`,
  whose `hasRenderableLayers` and `getSectionDraw` are the `SectionMesh`
  interface defaults (the `CompiledSectionMesh$1` anonymous class overrides
  only `facesCanSeeEachother`, returning `iconst_0`). Chunk delivery is
  server-throttled (`PlayerChunkSender`, `START_CHUNKS_PER_TICK = 9.0f`,
  `MAX_UNACKNOWLEDGED_BATCHES = 10`), so depth scales with flight speed.
  Collapses to nothing within about a second of stopping.
* **S2, the corner lune. PERSISTENT, MOVES WITH YOU.** The square's four
  diagonal corners are permanently outside the pad-1 disc for every E >= 4
  (`2*(E-1)^2 < E^2` is false). Those positions are not even sent by the
  server: the server's tracking view uses pad 2
  (`ChunkTrackingView$Positioned.contains(II)Z` reaching
  `isWithinDistance(..., true)`), and at the corner `2*(E-2)^2 <= E^2` is
  false for E >= 7. So the corners are legitimately absent terrain, not
  lost terrain. They do NOT fill in when you stop.
* **S3, the diagonal scar. PERMANENT.** Under a diagonal heading, a position
  at perpendicular offset `u` (in the rotated lattice) is in the square iff
  `|t| + |u| <= E` and in the disc at closest approach iff
  `2*max(0, |u| - 1)^2 < E^2`, that is `|u| <= floor(E / sqrt(2)) + 1`. For
  E = 8 the offsets `|u|` in {7, 8} are in-grid and never in-disc, for every
  `t`. Those positions cross the grid, are never compiled, and leave with
  nothing to retain, so every diagonal leg of a flight leaves two permanent
  empty lanes at perpendicular distance 9.9 to 11.3 chunks (158 to 181
  blocks at E = 8) flanking the corridor. An axis-aligned flight leaves
  none, because at `dz = 0` the disc reaches `|dx| = E` exactly.
* **S4, the frustum corridor. PERMANENT.** Compiles are frustum-gated
  (section 1.3), so the retained set is a looked-at corridor, not a disc.
  Sectors the player has flown past without looking at hold nothing.
* **S5, the vertical cap. PERSISTENT.** The BFS Y gate of section 1.2. An
  aerial player more than E sections above the terrain never compiles the
  terrain. The old fog cylinder capped the view at exactly the same `|y|`
  (`fog_cylindrical_distance` is `max(|xz|, |y|)`), so wave 16 lifted the
  curtain on this one too, and by the largest factor of anything here: at
  option 48 the vertical reveal went from `E*16` to 768 blocks.

**Why this is primary, and not one of M2 through M6.** Four reasons, in
order of weight.

1. **It is deterministic.** Every one of M2 through M6 needs a narrow race
   or a latched failure. M1 fires on every frame of every flight, by
   construction, with no window to hit.
2. **It is exactly co-located with the only thing wave 16 changed.** The
   shell's inner boundary IS the old fog wall, by the same int. A defect
   that appears the moment a curtain is removed and sits exactly where the
   curtain hung is the curtain's defect, not a coincidence.
3. **Section 2 rules out the alternative shape.** Meshelium does not lose
   retained terrain prematurely. The seam hole is an ABSENCE of retained
   data, and M1 is the only mechanism that produces absence at scale.
4. **It matches "between what we have saved and what we have rendered".**
   Live terrain inside the disc, the shell empty, retained terrain outside
   the square in every direction he has previously flown. On the flanks of
   the leading crescent and all around the corner lunes, both sides are
   populated and the gap is between them, which is what he described.

**What M1 cannot explain**: a hole pinned to a world position that the
player can fly at and that stays put while everything around it is drawn.
That shape belongs to M4 or M5.

### M2 bfsOnly latch drops live-but-unlisted sections

The single alternative that would change the whole answer, and the cheapest
to check.

`drawTaskCulled` builds its per-region visibility masks from
`Minecraft.getInstance().levelRenderer.visibleSections()`
(TerrainDrawer.java:1883 to 1896) and OR's in `snap.retainedMasks()` only
(lines 1909, 1933 to 1940). A LIVE resident that vanilla's disc-bounded BFS
does not list gets neither bit, and `if (pop == 0) continue;` (lines 1941 to
1943) can skip an entire region. That set is exactly the square's corners
holding sections compiled earlier, when they were inside the disc, and since
drifted out of it as the camera moved. Full opaque holes, persistent, right
on the seam.

This path is NOT the default. `drawOpaqueInner` line 1456 takes the
occlusion path whenever `MesheliumConfig.occlusionCullingEnabled() &&
!occlusionBroken`, and `enableOcclusionCulling` defaults true
(MesheliumConfig.java:123). But `occlusionBroken` LATCHES for the whole
session on any occlusion throw (lines 1468 to 1477), and the log line
`"Meshelium occlusion culling failed; reverting to the BFS visibility feed for
this session (first and only report)"` is emitted exactly once. If the
owner's session took that, M2 is the answer and M1 is a passenger.

On the DEFAULT occlusion path this cannot fire: `drawOcclusionCulled`'s
dispatch-list build (lines 1567 to 1619) reads only `snap.regionData()` and
one CPU frustum test. There is no mask, no retained flag, no
`visibleSections` and no render-distance value in it. **On the default path
Meshelium's opaque coverage is strictly MORE complete than vanilla's**, which
is itself a useful diagnostic: a ring of missing OPAQUE terrain at the
effective radius argues the session is not on the occlusion path.

### M3 Translucent live half sourced from `visibleSections`

Real, always on, including the default path, and it is a genuine
opaque-versus-translucent asymmetry that vanilla does not have.

`drawTranslucentInner` draws exactly two sets: the wave-11 retained pre-pass,
gated on `d[o + 19] != 0` (TerrainDrawer.java:2411 to 2413), and the live
half, iterated from `visibleSections` (lines 2473 to 2483). A LIVE resident
that vanilla does not list is in neither set, so its opaque geometry draws
(occlusion path, no mask) while its WATER does not. Geometrically pinned to
the same corner set as M2, cylindrical `E*16` out to `E*sqrt(2)*16`.

Not the owner's primary symptom: he said chunks, not water. But it is the
same root cause as M2 (using `visibleSections` as a coverage contract when
it is a culling hint) and it should be fixed in the same wave.

### M4 Decode miss aliased onto the empty-compile signal

`SectionBuildTap.onCompileReturn` has two calls to
`TerrainResidency.onSectionCompiledEmpty`. The first, lines 69 to 71, is
correct: `results.renderedLayers.isEmpty()` is a genuine statement that the
section has no geometry. The second, lines 80 to 83, is not:

```java
if (decoded.quads().isEmpty()) {
    TerrainResidency.onSectionCompiledEmpty(pos.x(), pos.y(), pos.z()); // wave-11
    return;
}
```

That branch is reached only when `results.renderedLayers` was NOT empty, so
vanilla has geometry and Meshelium's decoder produced none (all layers skipped,
counted into `decoderSkippedLayers` at lines 77 to 79).
`onSectionCompiledEmpty` (TerrainResidency.java:1122 to 1153) then removes
the retained copy at that position and frees it, and the wave-16 R3
chunk-presence guard cannot save it because the chunk IS present. No
replacement upload follows, because the method returned before `PARKED.set`.
The position is left with neither a live nor a retained record while vanilla
draws terrain there.

Permanent single-section holes, not seam-correlated, but they land on the
seam like anywhere else. Class (a), unambiguous, cheap to fix.

### M5 Out-of-bracket free for a position outside the grid

`onMeshReleased`'s wave-16 R3 branch (TerrainResidency.java:1021 to 1050)
orphans only when the chunk is absent client-side and otherwise calls
`freeLiveLocked`. But `chunkAbsentClientSide` answers a question about DATA,
and the case that matters here is about GEOMETRY OWNERSHIP.

The interleaving, every site javap'd:

* `CompileTask.doTask` reads its position LIVE, not captured: ip 14 to 18
  `getfield RenderSection.sectionNode:J`, ip 22 to 26 `SectionPos.of(J)`,
  then ip 100 `SectionCompiler.compile`, with cancel checks only at ip 0,
  ip 28 and ip 309 to 316.
* The mesh is constructed at ip 153 to 164, which fires
  `onMeshConstructed` and lands the encoding in `pendingUploads`.
* The render thread crosses a section boundary, `repositionCenter` calls
  `setSectionNode`, which calls `reset()` at ip 0 to 1, which cancels tasks
  and releases the OLD mesh in-bracket. The old mesh is correctly retained
  at position P. P is now outside the square grid.
* Meshelium's pump drains the NEW mesh for P, supersedes the fresh retained
  copy (correctly, per section 2), and P is now owned by a live resident
  that vanilla's slot no longer points at.
* The new mesh is later released out of bracket, either by the worker's
  cancel check at `doTask` ip 309 to 316 into ip 343 `releaseSectionMesh`
  (worker thread, `RESET_DEPTH` is 0, so `inSlotReset()` is false), or by
  `checkSectionMesh` ip 78 to 86 when the slot's NEW position rebuilds.
* `onMeshReleased` probes `chunkAbsentClientSide(P.x, P.z)`.
  `ClientChunkCache$Storage.inRange` ip 0 to 37 uses
  `chunkRadius = max(2, radius) + 3`, and P sits about E chunks out, so the
  chunk is PRESENT. `chunkAbsent == false`, `freeLiveLocked`. P now has
  nothing, and P is outside the grid so nothing will re-drive it.

Persistent single-section holes exactly one ring outside the grid, that is,
on the seam. Only fires while the view centre moves. Note that the free
itself is byte-identical to wave 15: **R3 did not introduce this, R3 simply
answers "present" here.** The defect predates wave 16 and the fog hid it.

Frequency is genuinely uncertain (see UNVERIFIED 3). It needs a rebuild in
flight for a position at the moment that position's slot repositions, and
under steady flight the repositioning column is the trailing edge, which is
not where rebuild traffic concentrates. `ViewArea.releaseAllBuffers()` has
no such protection and hits every slot at once.

### M6 Pending upload discarded inside a reset bracket

`TerrainResidency.onMeshReleased` lines 987 to 992:

```java
PendingUpload pending = pendingUploads.remove(mesh);
if (pending != null) {
    discardedBeforeUpload++;
    return;
}
```

That runs BEFORE the `inSlotReset()` retention branch at line 1001. A
section vanilla built, that Meshelium encoded, that the pump has not drained
yet, and whose slot is then revoked, is dropped outright. No live copy, no
retained copy, no record.

This is not a loss of an existing copy, so it does not contradict section 2;
it is a failure to ACQUIRE coverage that retention was supposed to acquire.
The window is roughly one pump, which at 1200 fps is under a millisecond,
so the steady-flight case needs an implausibly deep backlog. The burst case
does not: `ViewArea.releaseAllBuffers()` (a radius packet, an option change,
a graphics setting) empties the whole backlog in one call while the backlog
is at its deepest.

Probe: `discardedBeforeUpload`. It exists in `Counters`
(TerrainResidency.java:338, 513, 1690) but is NOT printed on the residency
stats line, so today it cannot be read in a real session. See W17-A.

### M7 Pump lag

`SectionBuildTap.onMeshConstructed` enqueues; the section becomes drawable
only when `drainPendingUploadsLocked` reaches it, once per `pump`
(TerrainResidency.java:1282), capped at `UPLOAD_BYTES_PER_PUMP = 16 MiB`
(line 143). Vanilla promotes with an `AtomicReference` swap inside
`checkSectionMesh` (ip 80), which is instantaneous. So Meshelium is
structurally one frame behind vanilla on every newly built section. At 16
bytes per vertex and 4 vertices per quad, 16 MiB is about 262 thousand
quads per pump, which is 50 to 130 typical sections; at 1200 fps the budget
is never the binding constraint. Not material. Revisit only if
`stagingBacklogEntries` is ever seen climbing.

### M8 Eviction spends oldest first, which is not farthest first

`evictRetainedLocked` (TerrainResidency.java:1323 to 1381) and
`forceEvictRetainedLocked` (1398 to 1413) both iterate `retained.values()`
from the head, and insertion order is orphan-stamp order. That equals
distance order only for straight-line travel. For a player who wanders, a
position passed early and now adjacent to the grid carries an old stamp and
is evicted before a newer, farther copy: a hole ring hugging the seam with
intact terrain beyond it, which is the owner's symptom exactly.

**It is inert today.** `MesheliumConfig.retainTerrainMinutes` defaults to 0,
which `retainLimitMillis()` maps to no limit, so the age leg never runs. The
pressure leg is gated on `usedQuads > 85 percent of the arena ceiling` or
`regionStore.regionCount() > 90 percent of maxRegions()`. On the owner's
hardware the arena ceiling is around half of the largest device-local heap,
so region ids are the binding budget, not arena bytes:
`maxRegions(rd) = rd <= 32 ? 2048 : max(2048, roundUp256(2 * regionsTouched(rd)))`
(MesheliumScaling.java:40), high water 1843 at the 2048 floor, while a long
sparse retained trail of 8x4x8 regions is exactly what exhausts an id
budget.

So: not the primary, settled by one probe read, but a real latent defect
that will produce this exact ring the first time pressure eviction fires.

### M9 A real far plane at `depthFar` in the CPU cull

Verified, and it is a second, much wider persistent ring that wave 16 moved
but did not remove.

The two projections disagree on near/far order and only one is reversed.
`Camera.createProjectionMatrixForCulling()Lorg/joml/Matrix4f;` ip 65 to 80
calls `org/joml/Matrix4f.perspective:(FFFFZ)` with `0.05f` then
`getfield depthFar` in that order, the NORMAL order, while the render
projection swaps them for reversed Z. Both feed `Camera.update` ip 124
(`prepareCullFrustum`) and ip 160 to 175 (`setupPerspective`). So the cull
frustum's far plane is real, and `frustum.isVisible(regionAABB)` in
`TerrainDrawer` (lines 1582 occlusion, 1925 bfs, 2419 and 2491 translucent)
drops any region wholly beyond it.

`Camera.update` ip 0 to 46:
`depthFar = max(getEffectiveRenderDistance() * 16 * 4.0f, cloudRange * 16)`,
and wave 16's `CameraMixin` swapped the effective value for the raw option.
`cloudRange` defaults to 128, so the ceiling is `max(option * 64, 2048)`:
2048 blocks for any option at or below 32, 3072 at option 48, 6144 at option
96. Retention is unbounded in radius, so a long flight will eventually push
the horizon past it.

Not the owner's ring, which is at a couple of hundred blocks. Document it.

### M10 GateIndex truncation above option ~72

Translucent only. The CPU packs `gate = (posKey << 20) | gidx`
(TerrainDrawer.java:2445 and 2501) with `gidx = regionId * 256 + slot`. At
option 96 `maxRegions` reaches 7680, so `gidx` can reach about 1.97 million,
above `2^20`, and the low 20 bits wrap into the posKey field. The shader's
`pk != (GateIndex >> 20u)` check (terrain.mesh:157 to 169) fails OPEN, so
the cost is extra draws and, rarely, a wrongly gated water section. Not the
symptom. Fix the width while the file is open.

---

## 4. Where the lenses disagreed, and the adjudication

### 4.1 The square-versus-disc annulus: BOTH lenses were right, about different halves

The frontier lens said the wedge is never compiled, so there is nothing to
draw. The exposure lens said the wedge sections ARE live in Meshelium and only
their water is dropped. Those look contradictory. They are not: the annulus
holds two disjoint sets.

* Positions that entered the square and were **never inside the disc** while
  in it: UNCOMPILED, no Meshelium record at all, full opaque hole. This is M1's
  S2 and S3.
* Positions that **were inside the disc** and have since drifted into the
  corner while the camera moved: still live Meshelium residents, drawn opaque
  on the default path, but absent from `visibleSections`, so they lose their
  water (M3) and lose everything under bfsOnly (M2).

Both fixes are needed and they are different fixes. Conflating them would
produce a translucent patch that leaves the opaque holes untouched, or a
coverage measure that counts corner positions Meshelium has never held.

### 4.2 "The outermost buildable ring is permanently unbuilt": REFUTED

The lifecycle lens claimed `hasAllNeighbors` leaves a permanent unbuilt rim
one chunk inside the data edge. The exposure lens refuted it. The exposure
lens is right, and here is the arithmetic, from the one method both cited:

Client BFS reach uses pad 1, server tracking uses pad 2, same
`ChunkTrackingView.isWithinDistance` bytecode (ip 0 to 10 selects the pad).

* Axis extreme. Client-reachable at `|dx| = E` because `(E-1)^2 < E^2`. Its
  outward neighbour at `|dx| = E+1` is server-sent because
  `(E+1-2)^2 = (E-1)^2 <= E^2`. Satisfied.
* Diagonal extreme, E = 8. Client-reachable at (6,6) because `2*25 = 50 < 64`.
  Its outward diagonal neighbour (7,7) is server-sent because
  `2*5^2 = 50 <= 64`. Satisfied.

So `hasAllNeighbors` is satisfiable across the entire reachable set in steady
state. The unbuilt rim is TRANSIENT, present only while the padded ring is
still in flight, that is, only while moving. It belongs to S1, not to a
permanent defect. The lifecycle lens's OTHER claim in the same finding, the
square-versus-disc corners, is correct and is S2.

### 4.3 Eviction: both lenses were half right

The lifecycle lens said age eviction can erode the seam because age is not
distance for a wandering player. The exposure lens said eviction is
oldest-first therefore far-side-first, and is off by default, therefore
ruled out.

Synthesis, from the code: the ORDER claim is identical in both lenses and is
correct. The DEFAULT claim is the exposure lens's and is correct
(`retainTerrainMinutes = 0`). The INVERSION claim is the lifecycle lens's
and is also correct: age is monotone in distance only under straight-line
travel, and "i fly around" is not straight-line travel. Both legs of
pressure eviction share the same inversion and pressure eviction is NOT
gated by the config default.

Verdict: eviction is not the primary and one probe read settles it, but the
ordering is a latent defect and M8's fix should ship.

### 4.4 Mask aliasing: REFUTED, and it should stay refuted

The highest-priority pre-recon hypothesis was that a retained section
outside vanilla's window could alias onto an unrelated grid slot. The
culling lens killed it and the evidence holds: `regionSlotByKey` is a
`Long2IntOpenHashMap` with `defaultReturnValue(-1)` (TerrainDrawer.java:762
to 765) keyed on an absolute 21-bit-per-axis region key (2057 to 2059,
matching RegionStore.java:139 to 144), rebuilt from the SNAPSHOT's own
region list (2066 to 2073), so retained-only regions are included and a miss
skips rather than folding into region 0. The within-region bit is a POSITION
key unique across the region's 256 positions (RegionStore.java:146 to 149),
and `RegionStore.remove`'s swap-remove never touches a retained bit filed
under a different position (lines 236 to 260). Recorded here so nobody
re-runs it.

### 4.5 "The seam is a circle at the old fog wall"

The frontier lens asserted the seam is exactly the old fog wall. That is
true of the INNER boundary and false of the outer one. The fog wall is a
cylinder of radius `E*16`; the retained deposit ring is a SQUARE at
Chebyshev E+1. The shell between them is the four-lobed shape of section
1.4, not an annulus of constant thickness. This matters for the fix: a
constant-radius fog ramp will leave the diagonal lobes uncovered unless the
coverage measure is evaluated per direction or the ramp is conservative
enough to sit inside them.

---

## 5. Classification, and why conflating the classes breaks the fix

**(a) Defects Meshelium introduced.** M2, M3, M4, M5, M6, M8, M10. These are
bugs regardless of the owner's symptom and regardless of wave 16. They are
fixed in code, in `TerrainDrawer`, `SectionBuildTap` and `TerrainResidency`.
Each is small. None of them will close the owner's ring on its own if the
primary diagnosis holds, and none of them may be presented as the fix for
it.

**(b) Pre-existing gaps wave 16 exposed.** M1's S1, S2, S3, S5, plus M9.
Nothing in Meshelium is wrong. Vanilla has the identical holes and hides them
behind fog computed from the same int. **The only lever is presentation**,
because the data does not exist and cannot be made to exist: the server does
not send the corners (section 3, S2) and vanilla will not compile what the
BFS cannot reach. Writing lifecycle code for this class would be building a
cache for data that was never produced, which is the specific way this gets
fixed wrong.

**(c) Inherent consequences of presenting terrain we only partly hold.**
M1's S4, and S2/S3 in their permanent form. The retained set is a memory of
what vanilla compiled while we were watching: a looked-at corridor along the
flight path with occlusion-shaped bites out of it, never a disc. This class
cannot be fixed at all, only measured and either hidden or declared. The
honest framing to put in the options screen and the README is that the
horizon is *a memory of terrain once seen, from where you saw it*.

The distinction is operational, not philosophical. Class (a) is proven by
counters and draw-set deltas. Class (b) is proven by moving the fog and
watching a boundary move. Class (c) is proven only by the coverage measure
of W17-A, and until that exists it cannot be proven at all.

---

## 6. The wave-17 fix design

### W17-A Diagnosis, and the negative record. Ships FIRST, alone if necessary.

Three of the discriminating probes cannot be read in a real session today,
which is why this recon cannot name the primary with certainty and why the
owner's next flight would otherwise waste itself.

**A1. Put the latch on the draw-path stats line.** `TerrainDrawer.bfsOnlyFrames()`
and `occlusionError()` exist (lines 923 to 924, 933 to 934) but neither
appears in `recordPerf`'s format string (lines 2993 to 3017). Add
`bfsOnly=%d occErr=%s` to the existing line. One format edit. Without it,
M2 is undiagnosable in the field, and M2 is the one alternative that would
change the whole answer.

**A2. Put the loss counters on the residency stats line.**
`discardedBeforeUpload`, `decoderSkippedLayers` and `staleParks` are all in
`Counters` (TerrainResidency.java:513 to 533) but absent from
`maybeLogStatsLocked`'s `drops[...]` group (lines 1716 to 1737). Add them.
They are the probes for M6 and M4 respectively.

**A3. The negative record.** Meshelium today cannot distinguish "nothing was
ever there" from "we lost it": an air-only section and a lost section are
both simply no `Resident`. Add

```java
// TerrainResidency, next to `retained`
private static final LongOpenHashSet seenColumns = new LongOpenHashSet();
```

inserted with `ChunkPos.asLong(sx, sz)` in `drainPendingUploadsLocked`
beside `regionStore.addOrReplace` (line 1551), and cleared only in
`disposeAndReset`. 8 bytes per chunk column, about 232 KB for a full option
96 disc, one set operation per upload, nothing on the per-frame path.

Strong recommendation: store a per-column Y extent (two shorts, min and max
section Y) rather than a bare set, from the start. It costs 4 more bytes per
column and it is what makes S5 detectable and what stops the ramp of W17-C
from opening a wall over a column set that has no ground in the camera's Y
band. Retrofitting it later means re-deriving the measure and re-calibrating
the ramp.

**A4. The coverage oracle.** On top of A3, add

```java
public static int coverageHoles(int radiusChunks)   // camera-relative
```

returning the count of chunk columns within `radiusChunks` that are in
`seenColumns` and have NO drawable record at any Y, in neither `resident`
nor `retained`. That number is definitionally "we saw terrain here and we no
longer have it", which is the only rigorous definition of a hole this
project can state. Columns absent from `seenColumns` are legitimately absent
terrain and are excluded by construction. This is the enabling primitive for
every test in section 7.

### W17-B The class (a) defects

**B1. Translucent: stop sourcing the live half from `visibleSections`.**
In `drawTranslucentInner`, widen the retained pre-pass predicate at
TerrainDrawer.java:2413 from `d[o + 19] == 0` to
`d[o + 19] == 0 && visibleThisFrame(sx, sy, sz)`, so the far-to-near
pre-pass claims every slot-owning entry with a translucent prefix that
vanilla did not list. Keep the existing `visibleSections` loop (2473 to
2527) for the rest. The `transDrawnMark` machinery (2466 to 2467, 2484 to
2486) already guarantees no double blend, and the far-first ordering
argument is unchanged because the extra entries are, by construction,
outside the pad-1 disc and therefore farther than `E*16`.
Cost: one `LongOpenHashSet` of visible section keys per frame on the
occlusion path (a few thousand inserts), or reuse the mask the bfs path
already builds at 1885 to 1896.

**B2. bfsOnly: OR in a live-resident mask.** At TerrainDrawer.java:1933 to
1940, merge a third word sourced from the snapshot's own live entries, or,
simpler and strictly fail-open, set `maskSlot = NO_MASK_SLOT` for any region
whose merged popcount is less than `count`. The principle to write into the
comment: **Meshelium's record set is the authority for what exists;
`visibleSections` is a culling hint, and using it as a coverage contract is
what creates the gap.** Fail-open costs a few extra dispatched sections;
fail-closed costs holes.

**B3. Split the decode-miss signal from the empty-compile signal.**
`SectionBuildTap.onCompileReturn` line 81 must not call
`onSectionCompiledEmpty`. Add
`TerrainResidency.onSectionDecodeMiss(int sx, int sy, int sz)` that KEEPS
any retained copy (a stale copy is strictly better than a hole) and
increments a dedicated counter. Also count it toward the wave-8 drop
accounting: it is a section vanilla holds and Meshelium lost, which is
precisely the guard's definition, and the coverage story stays honest only
if it is counted there.

**B4. Widen M5's predicate from "chunk absent" to "chunk absent OR outside
the grid".** A release for a position vanilla can no longer own is
distance-class and must ORPHAN, not free. Both reads are public and safe
outside Meshelium's LOCK, so they go next to the existing probe in
`SectionBuildTap`, keeping the level-identity term:
`Minecraft.getInstance().levelRenderer.viewArea()`, then
`ViewArea.getCameraSectionPos()`, `ViewArea.getViewDistance()` and the
min/max section Y, compared with the same square test
`RotatingSectionStorage.containsSection` uses (ip 18 to 86:
`|sx - cx| <= radius`, `|sz - cz| <= radius`, `sy` within `[minY, maxY]`).
Count it separately as `orphanedOutsideGrid`. One extra branch on a path
that already makes a vanilla call. This subsumes part of M6's steady-flight
case as well.

**B5. Make the pending-upload discard retention-aware.** In
`onMeshReleased` lines 987 to 992, when the pending branch is taken AND
`inSlotReset()` AND `MesheliumConfig.retainTerrainEnabled()`, do not discard:
re-key the `PendingUpload` from mesh identity to `posPack(sx, sy, sz)` into
a small position-keyed pending-retain queue that the pump drains into
`retained`, stamping `orphanedAtMillis` at drain, bounded by the same
eviction ladder. This makes the retain-versus-free decision independent of
whether the pump happened to have run yet, which is the invariant wave 11
wanted and did not get.

**B6. Distance-aware eviction.** In `evictRetainedLocked` and
`forceEvictRetainedLocked`, skip any candidate within N sections of
`ViewArea.getCameraSectionPos()` and take the next, keeping the sweep
bounded by `EVICT_BUDGET_PER_PUMP = 256` and `FORCE_EVICT_BATCH = 64`. The
map stays insertion-ordered so the age sweep stays O(evicted). N = the
effective radius plus 2 is the natural choice: it protects exactly the seam.

**B7. GateIndex width.** Either assert `maxRegions() * 256 < (1 << 20)` at
pipeline creation next to the existing cap asserts (TerrainDrawer.java:2783
to 2803), or shift to `(posKey << 24) | gidx` and assert against `1 << 24`.
One line either way.

### W17-C The presentation decision

Section 8, in full.

### W17-D Documentation

Record in this file and in the options-screen tooltip: the horizon is a
memory of terrain once compiled, from where you were looking; the hard
ceiling at `max(option * 64, cloudRange * 16)` (M9), which below option 32
is set by the cloud range and not by the render distance at all; and the
vertical BFS cap of section 1.2, which is the reason a high-flying player
sees no ground.

---

## 7. How each fix gets PROVEN on the harness

House rules this project has learned the hard way, and which every leg below
obeys: no projection-model pixel windows, measure a boundary the feature
moves; no wall-clock-bound growth assertions; capture baselines before the
stimulus; a screenshot only proves the angle it was taken from; and a hole
test must be able to distinguish a hole from legitimately absent terrain.

**The last rule is the hard one, and W17-A3/A4 are a prerequisite for it,
not a nicety.** A sky pixel can mean three different things (no data was
ever sent, data was sent and never compiled, data was compiled and Meshelium
lost it) and no camera at any angle can tell them apart. The only way to
make the distinction is to ask Meshelium what it believes it holds and then
check whether it drew it. That is `coverageHoles`.

**P0, the coverage oracle, the enabling leg.** Assert
`TerrainResidency.coverageHoles(option)` is 0 at rest at the end of every
existing leg in `MesheliumTerrainDrawTest` and
`MesheliumTerrainResidencyTest`. Cost: one map walk. Any future regression
that loses a section Meshelium had now fails a test somewhere, which is not
true today. Before B3, B4 and B5 land, this leg is expected to be flaky in
exactly the ways those defects predict, and the flake rate should be
recorded as their baseline.

**P1, bfsOnly live-resident mask (B2).** Baseline
`TerrainDrawer.lastDrawnSections()` and
`TerrainResidency.counters().sectionsResident()` on the occlusion path at
rest, BEFORE the stimulus. Flip `meshelium.terrainDraw.bfsOnly`, wait for
`bfsOnlyFrames()` to rise (the harness already does exactly this at step (f)
of `assertMpRetainedHorizonPixels`), then assert the drawn-section count
does not fall by more than a frustum's worth. Today it falls by the corner
set. Write the leg to FAIL first and record the delta, so the fix has a
number to beat. No pixels involved.

**P2, translucent (B1).** Same shape on `lastTranslucentSections()`, camera
over water. Baseline stationary on the occlusion path, then step the camera
ONE CHUNK DIAGONALLY so the corner set changes membership, and assert the
translucent section count tracks the resident translucent count rather than
the `visibleSections` count. Diagonal is load bearing: on the compass axes
the disc and the square coincide (section 1.4) and an axis-aligned leg is
vacuous.

**P3, decode miss (B3).** The two signals must be provably different, so
prove it directly and without a client: for a position with a retained copy,
call `onSectionDecodeMiss` and assert `retainedSections()` does not fall
while the new counter rises; call `onSectionCompiledEmpty` for the same
position and assert it does fall. A single leg that exercises only one of
the two calls is exactly the leg that would have let this bug ship.

**P4, out-of-grid orphan (B4).** Extend the existing wave-16 leg, which
already injects `Options.setServerRenderDistance(RETAIN_RD_LOW)` and drives
a mass retain. After the injection, force an out-of-bracket release for a
position at Chebyshev `RETAIN_RD_LOW + 2` and assert `orphanedOutsideGrid`
rises while `retainedSections()` does not fall. Then assert the new grid
predicate in BOTH directions the way step (e2) already does for
`chunkAbsentClientSide`: a position at the camera must read in-grid, a
position 4096 chunks away must read out-of-grid. A predicate wedged to
"always in grid" would silently disable B4 while every other assertion
passed, which is the failure mode step (e2) exists to catch.

**P5, pending-upload retain (B5).** Make `UPLOAD_BYTES_PER_PUMP` a
property-overridable read (one line, matching the existing
`meshelium.test.arenaMiB` idiom), set it low enough to guarantee a backlog,
drive a `releaseAllBuffers` through the render-distance change the harness
already performs in `setRenderDistanceLikeTheUi`, and assert
`discardedBeforeUpload` stays flat while `orphanedSections` absorbs the
difference. Baseline both counters before the stimulus. Note this is a
counter-conservation assertion, not a growth-over-time assertion, so no
clock appears in it.

**P6, the ramp (W17-C).** Three claims, none of them a projection-model
window:

* *(a) the returned int, by equality against an independently computed
  expectation.* Under the wave-16 injection on a freshly stood-up world
  with nothing retained, `MesheliumRetentionHorizon.lastFogRenderDistance()`
  must equal the EFFECTIVE radius, not the option. After a scripted 512
  block out-and-back that populates a corridor, it must equal a value the
  leg computes itself from `coverageHoles`/`seenColumns`, exactly. Asserting
  equality against an independent computation, and not merely "greater than
  before", is what stops the ramp from being a number that happens to be
  bigger.
* *(b) the slew limit, in the feature's own units.* Sample
  `lastFogRenderDistance()` every tick across a scripted 90 degree heading
  change and assert no single-tick delta exceeds the configured step. This
  is a boundary the feature moves, measured in chunks, with no wall clock in
  the assertion.
* *(c) pixels, once, and only as corroboration.* Reuse the existing
  `assertRetainedSkylinePixels(A0, A1)` pair from the same pinned spectator
  camera and add a third shot A2 with the ramp forced to the raw option. The
  claim is an ORDERING of three measurements of the same scene: A2 has more
  sky than A0, which has less sky than A1. No constant encodes where the
  wall should be, which is precisely the failure mode the house rule is
  about.

**P7, regression tripwires.** Keep the wave-16 SP-parity assertion exactly
as written: with `option == effective` the widened counters must not move
over a 40 tick window. The ramp must not change that, because term 2 of the
predicate still short-circuits before any coverage read. Also keep
`assertGlDormant`'s check that both widened counters stay 0 on the OpenGL
run.

**Honest limit of the whole harness plan.** No leg here can reproduce M1's
persistent shapes (S2, S3, S4) in singleplayer against an integrated server
that follows the option. Those need `option > effective` AND sustained real
motion; the radius injection gives the first and the harness's teleports
give a poor imitation of the second. P0's coverage oracle is what carries
the proof burden, because it is a statement about Meshelium's own belief and
does not depend on reproducing the geometry.

---

## 8. The adaptive ramp, evaluated

**Proposal.** `MesheliumRetentionHorizon` returns
`clamp(measuredCoverageHorizon, effective, option)` instead of the raw
option.

**Why it is the right shape.** It converts an unfillable region into one we
can fill. Every class (b) and class (c) hole is invisible again if the fog
wall sits at the coverage boundary rather than at the option, with no
lifecycle change of any kind. It also restores vanilla's free soft edge:
`renderDistanceStart = blocks - clamp(blocks / 10, 4, 64)` gives 19 blocks
of ramp at rd 12, 51 at rd 32, and saturates at 64 above rd 48, so terrain
arriving at the frontier fades up out of the fog colour instead of popping,
which matters more than usual because Meshelium's `terrain.frag` pins
`ChunkVisibility` to 1.0 and has deleted vanilla's own per-section fade
(header note lines 19 to 22, main lines 173 to 174). And it caps the
vertical reveal for free, because `fog_cylindrical_distance` is
`max(|xz|, |y|)` and the same int drives both axes, which is the only cheap
answer to S5.

**The measure, which does not exist yet.** Built on W17-A3:

```text
coverageHorizon = the largest r, in chunks, such that for every annulus
  a in [effective+1 .. r], at least F of the chunk columns in a are in
  seenColumns AND their stored Y extent contains the camera's section Y.
  Walk outward from effective+1, stop at the first annulus that fails.
  F around 0.9.
```

Coverage-driven rather than extent-driven, which is what stops a single long
corridor from claiming a full disc, and what keeps the diagonal lobes of
section 4.5 from being opened over.

**Costs.**

* One ring walk every N frames, not per frame. At option 96 and effective 8
  that is at most 88 annuli, the outermost about 600 columns, so a few
  thousand hash lookups every N frames. N = 20 makes it noise. Memo the
  result and have both wrappers read the memo, so the fog site and the far
  plane site never disagree within a frame.
* 12 bytes per seen chunk column with the Y extent, about 350 KB for a full
  option 96 disc. Freed at `disposeAndReset`.
* One config field and one predicate term in
  `presentationRenderDistance`.

**Failure modes, and what each needs.**

1. *The ramp opens over a corridor and the player turns 90 degrees.*
   Coverage in the new heading is low and the horizon walks inward. With a
   slew limit that reads as fog rolling in over a second, a normal weather
   look. Without one it is a visible curtain slam every time the player
   turns. **The slew limit is not optional.** Recommend: raise at most one
   chunk per 500 ms, lower at most one chunk per 2000 ms, and require the
   coverage test to hold across two consecutive evaluations before moving.
   Asymmetric on purpose: opening late is invisible, closing late shows a
   hole.
2. *Coverage says yes but only at the wrong altitude.* Solved by storing the
   Y extent per column, which is why that is recommended from the start
   rather than added later.
3. *The ramp masks the class (a) defects.* It does not hide them, because a
   B3 or B4 hole sits well inside the coverage boundary, but it does make
   the pixel evidence for the widening conditional on coverage. Mitigated by
   P6(a), which asserts the returned int against an independently computed
   expectation rather than against the raw option.
4. *The server radius is not reported.* `Options.getEffectiveRenderDistance()`
   returns the option when `serverRenderDistance <= 0`, so the clamp's lower
   bound must be `effective`, never a raw server field. Using `effective`
   also makes the singleplayer identity case fall out for free, preserving
   the wave-16 SP-parity property that term 2 already guarantees.
5. *The ramp interacts with an unhealthy drawer.* No new risk: terms 1, 3, 4
   and 5 of the existing predicate still return `effective` before any
   coverage read is reached, and the coverage read must be placed AFTER them
   so a passive or error-latched drawer never pays for it.

**Do NOT ramp the far plane.** `Camera.update` writes `depthFar` once at ip
46 and feeds it to `createProjectionMatrixForCulling` at ip 124 and
`setupPerspective` at ip 175, and the cull projection is a NORMAL near/far
perspective (M9), so it carries a real far plane that the CPU region cull
enforces. Ramping it would pop whole 128x64x128 regions in and out of the
cull frustum as the measure moves. The far plane only ever widens what MAY
be drawn; it never reveals emptiness on its own. Leave
`farPlaneRenderDistance` on the raw option. This is the first time the two
wrappers' behaviour diverges, and it is exactly why wave 16 was right to
keep them separate.

**Recommendation: BUILD IT, DEFAULT ON, with a toggle for the wave-16
raw-option behaviour.**

The owner's directive was *"i wanted that for servers like this"*, which is
a wish to SEE the retained horizon, not a wish to see holes where there is
no horizon. The ramp shows him every block Meshelium actually holds and
nothing else, which is strictly more of what he asked for than a wall opened
over emptiness. Default ON because the alternative default is a known-bad
look that we now understand precisely and cannot fix any other way. The
toggle exists because raw-option is what wave 16 shipped and a playtest may
still prefer the further horizon with holes in it; name it for its meaning,
for example `retainHorizonFollowsCoverage`, default true, and say in the
tooltip that turning it off shows terrain further away at the cost of gaps
where nothing was ever loaded.

**Rejected alternatives, and why.**

* *Hysteresis-gate the widening on `LevelRenderer.hasRenderedAllSections()`.*
  Cheap and it does address S1, but it does nothing for S2, S3, S4 or S5,
  which are the persistent half, and it makes the horizon breathe with build
  queue depth rather than with coverage. Rejected as a substitute; harmless
  as an additional term if S1 proves visually dominant.
* *Accept and document.* Defensible for S1 alone. Not defensible for S2 and
  S3, which are permanent, which the player cannot fix by waiting, and which
  are the shapes he is most likely to read as a bug.
* *Widen the ViewArea grid to match the fog.* Would fill the shell with real
  live terrain, and it is the only fix that actually populates rather than
  hides. Rejected: the grid radius is `getEffectiveRenderDistance()` at
  `LevelRenderer.invalidateCompiledGeometry` ip 174, the tracker follows it
  at `LevelExtractor.extract` HEAD, and the server will not send the data
  regardless, so a wider grid buys empty slots, a larger `RotatingSectionStorage`
  and a wider BFS over nothing. It also breaks the wave-16 invariant that
  data follows the server.

---

## 9. What the owner should look for on the next server flight

Written for a player. The single most useful thing is number 1.

1. **Stop moving and stare at a hole for five seconds.** Does it fill in?
   * Fills in within a second or two: it is the loading edge. Expected, and
     the fix is the fog change, not a bug hunt.
   * Still empty after five seconds: it is one of the real bugs. Note where
     it is and carry on to 2.
2. **Do the holes sit at a fixed distance from you, or are they stuck to
   places in the world?** Fly sideways and watch one.
   * Stays at the same distance, moving with you: that is the expected
     shape.
   * Stuck to a world position, so you can fly straight at it and it stays
     put: that is a lost chunk and it is our bug. Screenshot it with F3 open
     so we get the coordinates.
3. **Are they only ahead of you, or all around?**
   * Only ahead while flying, sweeping around as you turn: loading edge.
   * All around, including behind you over ground you already flew across:
     lost data.
4. **Look at the shape.** Are they four bites in the diagonal directions
   (north-east, north-west, south-east, south-west) with the north, south,
   east and west directions looking fine? That is the expected pattern and
   it confirms the main diagnosis in one glance.
5. **Look at water at the edge.** Is the water SURFACE missing while the
   ground underneath it is still drawn? If yes, that is a separate bug we
   have already found, and it is worth saying so on its own.
6. **Fly straight along one compass direction for thirty seconds, then fly
   diagonally for thirty seconds.** Are the holes clearly worse on the
   diagonal? If yes, that is the expected pattern again.
7. **Are you flying very high?** More than a couple of hundred blocks above
   the ground. If the ground vanishes entirely from up there and comes back
   when you descend, that is a separate vertical limit and we want to know.
8. **Search the game log for the word `occlusion`.** If there is a line
   saying occlusion culling failed and it reverted to the BFS visibility
   feed, stop and tell us: that single line changes the diagnosis
   completely and everything above becomes secondary.
9. **Turn on `-Dmeshelium.debugStats=true` and send the two stat lines**
   (`meshelium residency:` and `meshelium draw-path:`) after a few minutes of
   flying. In the `retention[...]` group, if `evictAge` or `evictPressure`
   is anything other than 0, say so explicitly; those two would also change
   the answer.
10. **Did anything happen just before the holes appeared?** A render
    distance slider change, a dimension change, a portal, a death and
    respawn. Those are the moments where a different bug can fire.

---

## 10. UNVERIFIED, and the limits of this recon

1. **No JVM was run** (house rule). Every vanilla chain is static bytecode
   reading against the cited jar; every Meshelium chain is source reading.
   No frequency claim anywhere in this document is measured.
2. **Whether the owner's holes are transient or persistent is still
   unknown**, and it is the single fact that would most sharpen this
   design. Section 9 item 1 is the whole experiment.
3. **M5's frequency is genuinely uncertain.** The interleaving is proven,
   the rate is not. Under steady flight the repositioning column is the
   trailing edge, which is not where rebuild traffic concentrates, so the
   plain reposition case may be rare enough to be irrelevant next to M1.
   `orphanedOutsideGrid` (B4) is the counter that would settle it, and it
   does not exist yet.
4. **Whether the owner's session is on the occlusion path is unknown**, and
   nothing currently printed can tell him. That is W17-A1's whole purpose.
   Until it lands, M2 cannot be excluded and the primary diagnosis is
   conditional on it.
5. **The coverage measure of section 8 is a design, not a measurement.**
   The value of F, the annulus granularity and the slew rates are all
   guesses that the first real session must calibrate. Shipping the ramp
   with un-calibrated constants and no P6(b) leg would trade a visible hole
   for a visible breathing horizon.
6. **The exact seam radius on the owner's server is unknown** because his
   server's view distance was never recorded. Every worked number in this
   document uses E = 8 or E = 12 as an illustration. If his server runs a
   larger radius the lobes are proportionally further out and proportionally
   deeper in absolute blocks, but no conclusion changes.
7. **S3's geometry assumes an exactly diagonal heading.** Real flight
   wanders, and a heading change re-decomposes every position's `(t, u)`,
   so some scars self-heal. How much is unmeasured, and it is the difference
   between "two obvious empty lanes" and "occasional notches".
8. **M10's arithmetic rests on the lens's reading of `MesheliumScaling`**
   (`maxRegions` at line 40 and `regionsTouched` in the class javadoc). The
   formula was read; the option 96 worked example was not independently
   recomputed here.
9. **The extended frame-list ring depth (4 slots) remains argued, not
   measured**, exactly as the culling lens left it. It is live only above
   option 32, which IS the owner's configuration. The cheap tripwire the
   lens proposed (stamp each ring slot with its `frameSerial` and compare in
   the region raster, counting mismatches into the unused `occStats[3]`)
   converts the argument into a counter for almost nothing, and W17-A is
   the right wave to add it to.

---

## 11. Wave 17 as BUILT (appended by the implementation, 2026-08-10)

This section records what shipped against what section 6 designed, what was
rejected and why, and the exact new stats-line format. No code claim here
is a prediction: everything below is a source fact about the working tree.
Nothing here was run (house rule), so every RUNTIME claim remains
unverified, and the list at the end says which.

### 11.1 W17-A shipped in full

* **A1** — the draw-path line gained `path[bfsOnly=..,occlusion=..,occErr=..]`.
  `occErr` prints `null` when healthy. This is the probe that decides
  whether M2 is the answer, and it was unreadable in the field until now.
* **A2** — the residency line gained `drops[...,decodeMiss=..]`,
  `lost[discarded=..,staleParks=..,decoderSkipped=..]` and
  `coverage[seenCols=..]`.
* **A3** — `TerrainResidency.seenColumns`, a `Long2IntOpenHashMap` from
  packed chunk column to `(maxSectionY << 16) | (minSectionY & 0xFFFF)`,
  written at upload beside `resident.put`, cleared at `disposeAndReset`.
  The Y extent is stored from the start, as recommended.
* **A4** — `TerrainResidency.coverage(camSecX, camSecY, camSecZ, radius,
  yBandSections)` returns a `Coverage` record carrying total/seen/held/hole
  column counts plus per-annulus totals and covered counts.
  `coverageHoles(radius)` and `coverageHoles(cx, cz, radius)` are the thin
  wrappers, `seenColumnsSnapshot()` / `seenColumn(cx, cz)` the ledger view.

Three deliberate departures from the A3/A4 text:

1. **A second ledger, `heldColumns`.** The design said a hole is a column
   in `seenColumns` with no record in `resident` or `retained`, which as
   written is an O(residents) scan per query. A per-column held COUNT,
   incremented at upload and decremented at every departure, makes the
   query O(columns in the disc) with two hash lookups each. Same answer,
   bounded cost, and it is what makes the once-per-250ms ramp affordable.
2. **Euclidean annuli, not Chebyshev rings.** The thing being decided is
   the radius of a CYLINDER, and a Chebyshev ring at distance `a` is mostly
   OUTSIDE the circle of radius `a` (only about 4 of its `8a` columns are
   inside). Bucketing by `ceil(euclidean distance)` makes annulus `a`
   exactly "the columns the circle of radius `a` admits and the circle of
   `a-1` does not", which is the set the wall is being asked about.
3. **The ramp requires HELD, not merely SEEN.** Section 8's formula asks
   for columns "in seenColumns AND their stored Y extent contains the
   camera's section Y". A seen column we no longer hold is a HOLE by this
   document's own definition, so opening the wall over it would show
   exactly the thing the ramp exists to hide. The implementation requires
   `held > 0` as well.

### 11.2 W17-B: what shipped, what did not

| Fix | Shipped | Note |
|---|---|---|
| B1 translucent pre-pass | YES, narrowed | claims live residents outside the BFS REACH, not every unlisted section |
| B2 bfsOnly live mask | YES, narrowed | same predicate; NOT the blanket fail-open |
| B3 decode-miss split | YES | `onSectionDecodeMiss`, keeps retained copies, counts a DROP |
| B4 out-of-grid orphan | YES | predicate is "chunk absent OR outside the grid" |
| B5 pending-upload retain | NO | see 11.5 |
| B6 distance-aware eviction | NO | see 11.5 |
| B7 GateIndex width | NO | see 11.5 |

**B1 and B2 were narrowed on purpose.** The recon offered "OR in a live
resident mask" and "set `maskSlot = NO_MASK_SLOT` for any region whose
merged popcount is less than count". Either would make the bfs mask
all-ones for essentially every region and delete that path's culling
outright; the fallback would become "draw everything in the frustum". The
shipped predicate is `!GridSnapshot.withinBfsReach(sx, sy, sz)`, which is
exactly the M2/M3 set and loses NO vanilla culling, because vanilla's list
stays the sole authority everywhere vanilla can see.

For B1 the narrowing is also what keeps the far-first pre-pass honest, and
even then only approximately. Horizontally the two sets abut with about six
blocks of overlap (a claimed section is at least `E+1` chunks out, a listed
one can reach `E + sqrt(2)`), and the vertical gate is coarser still: a
section more than `E` sections directly overhead is claimed while a listed
section at `E + sqrt(2)` chunks horizontally is farther away. Both can
blend a nearer water surface before a farther one at the extreme frontier.
This is the exposure the wave-11 retained pre-pass already carried and it
is accepted on the same grounds.

**B4 and the 90 firings.** The coordinator measured `orphanedChunkAbsent`
firing 90 times during ordinary flight on an INTEGRATED server, which
refutes wave 16's "structurally 0 on vanilla servers" claim. Both facts are
consistent: while the view centre moves, out-of-bracket frees routinely
arrive for positions the `ClientChunkCache$Storage` window has already
left, and the probe answers "absent" honestly. What the number establishes
is FREQUENCY. M5's interleaving was proven but its rate was UNVERIFIED item
3; 90 firings per flight on the *narrow* predicate says this release path is
hot, so the residual case B4 closes (chunk still cached, position already
out of the grid) is not a theoretical race either. The two counters now
partition the same hot path: `orphanedChunkAbsent` keeps its exact wave-16
meaning and `orphanedOutsideGrid` counts only the frees wave 16 performed.
A harness bound written against the old counter still means what it meant.

**B3 counts a DROP, and that trips the coverage guard.** This is the
recommendation taken literally and it is worth being explicit about the
consequence: one decode miss takes Meshelium passive for the rest of the
world. That is the same policy `countEncodeFailure` has had since wave 8
for the analogous event, and it is the honest response to "a section
vanilla holds and Meshelium lost". It is structurally unreachable on a
vanilla world: a layer either decodes or is counted in
`decoderSkippedLayers`, and a decoded layer always yields at least one quad
(`VanillaMeshDecoder.decodeLayer` rejects `vertexCount <= 0`), so
`quads().isEmpty()` with a non-empty `renderedLayers` means every layer was
refused by the format gate. It does NOT latch `lastError`: that latch means
"Meshelium threw" and never clears for the session, while this is a handled,
counted, guard-tripping condition with its own WARN and its own
options-screen sentence.

### 11.3 W17-C: the ramp as built

`MesheliumCoverageRamp.horizon(effective, option)`, called from the FOG site
only, after every gate/config/health term of
`MesheliumRetentionHorizon.presentationRenderDistance`. Config
`retainHorizonFollowsCoverage`, default true, property
`meshelium.retainHorizonFollowsCoverage`, options-screen toggle "Fog Follows
Remembered Terrain".

```text
floor    = effective                 (never a raw server field)
ceiling  = option
current  = clamp(previous, floor, ceiling)          # both clamps immediate
on a discontinuity (call gap > 1000 ms, grid centre jump > 4 sections,
    no grid at all): current = floor, forget the slew state, resyncs++
every 250 ms:
    probe  = min(ceiling, current + 1)               # the slew cannot exceed this
    walk annuli a = floor+1 .. probe over Coverage(camSec, probe, viewDistance)
    allowed(a)   = max(4, columnsInAnnulus(a) * (100 - fractionKnob) / 100)
    raiseTarget  = the last a, walking outward without a gap, whose
                   uncovered(a) <= allowed(a)
    lowerTarget  = the last a whose uncovered(a) <= allowed(a) + 2
    target = raiseTarget if current < raiseTarget
             lowerTarget if current > lowerTarget
             current otherwise            # the dead band
    direction = sign(target - current)
    two consecutive evaluations must agree on direction before the wall
        STARTS moving
    then move by exactly 1, no sooner than 750 ms after the last move when
        raising (and re-confirm), 250 ms when lowering (and do NOT spend
        the confirmation, so a sustained retreat runs at one chunk per
        evaluation = 64 blocks/s)
return clamp(current, floor, ceiling)
```

**The acceptance test is ABSOLUTE, not proportional, and this is a wave-17
repair-pass correction to the first draft.** The draft required 90 percent
of every ring, `meshelium.tune.coverageFraction`. That is wrong in both
directions, and the review that caught it computed both:

* *Too generous outward.* A proportional bar scales with the ring, so at
  effective 8 and a wall at 16 it licensed roughly 60 missing 16x16 columns
  spread through the band, all of them INSIDE the wall. Only the outermost
  ring is in the fog.
* *Too strict inward, to the point of switching the feature off.* Ring
  `E+1` always contains exactly FOUR columns vanilla's own pad-1 disc can
  never reach, the axial ones, because there `i = max(0, |dx|-1) = E` and
  the disc test `i*i + j*j < E*E` is STRICT (`ChunkTrackingView
  .isWithinDistance` ip 45-77). Measured against that bytecode, ring `E+1`
  is 87.5 percent reachable at E = 4, 88.9 at E = 6, 92.9 at E = 8, 95.5 at
  E = 12 and 96.2 at E = 16. A 90 percent bar therefore made the wall
  structurally unable to leave the floor on any server with a view distance
  of 4 or 6, no matter how much terrain the player held.

So the shipped test is `uncovered(a) <= 4`. Four is derived, not chosen: it
is exactly the structural count above, so live coverage alone clears ring
`E+1` at every radius and no ring inside the wall may ever be missing more
than four columns. Ring `E+1` therefore clears with ZERO margin, which is
why `meshelium.tune.coverageSlackColumns` exists. `meshelium.tune.coverageFraction`
survives as a PROPORTIONAL widening of the same tolerance, defaulting to
100 so it contributes nothing until a coordinator asks for it.

**Consequence, stated plainly rather than discovered in a playtest:** for a
player flying into ground they have never seen, ring `E+2` and beyond
require RETENTION to supply almost the whole ring, and a straight-line
flight never supplies the forward half. The wall will therefore sit at
`E+1` in unexplored territory and open only over ground the player has
circled. That is the correct answer (there genuinely is nothing out there;
that absence is the reported bug), but it means the visible benefit of the
feature is concentrated around places the player has spent time.

The vertical term is vanilla's own BFS Y gate: a column counts only if its
recorded Y extent overlaps `[camSecY +/- ViewArea.getViewDistance()]`. That
is what caps the S5 reveal, and it is why the extent had to be in the
ledger from the first commit.

**The far plane is not ramped**, per the design and for the design's
reason: `Camera.createProjectionMatrixForCulling` is a NORMAL near/far
perspective while the render projection is reversed, so the cull frustum
carries a real far plane that the CPU region cull enforces against
128x64x128 boxes. `CameraMixin` and `farPlaneRenderDistance` are unchanged.
A far plane wider than the fog is harmless: it widens only what MAY be
drawn, and everything past the fog wall is fogged out anyway.

### 11.4 Proof, and where it departs from section 7

P0 (oracle), P1 (bfs drawn-set delta), P2 (translucent, diagonal step), P3
(decode miss vs empty compile), P4 (out-of-grid predicate, both
directions), P6(a) (equality against an independently computed
expectation), P6(b) (slew in chunks per tick) and P6(c) (three shots) all
shipped. P5 did not, because B5 did not.

Four departures, each because the leg as designed would have been wrong or
vacuous:

1. **P0 asserts a bar, not zero.** A column whose last section is
   legitimately recompiled to nothing keeps its ledger entry forever, which
   is what makes the ledger a negative record, while its held count
   honestly falls to zero. Without a second "known empty" ledger those are
   indistinguishable from a loss. The bar is 0.5 percent of seen columns
   with a floor of 16, the measured value is printed on every call, and the
   shapes this project actually loses terrain in are rings and lobes that
   move it by orders of magnitude.
2. **P6(c)'s ordering is asserted only where it is true.** The design
   predicted `sky(A2) > sky(A0) < sky(A1)`, reasoning that the raw-option
   wall opens over emptiness and emptiness reads as sky. That holds only if
   the extra band IS empty. In a scene the leg deliberately swept a full
   circle in, the band is full, so A2 shows MORE terrain and LESS sky than
   A0, which is the ramp costing the player nothing and is a pass.
   Asserting the predicted inequality would fail exactly when the feature
   behaved best. What ships: both widened walls must show less sky than
   retention-off; if the ramp reached the option, A0 and A2 must be the
   same picture; and `sky(A2) - sky(A0)` is PRINTED with its sign as the
   calibration number.
3. **The strong wave-16 skyline assert moved to A2.** A2 is the shot whose
   wall sits at the raw option, which is precisely wave 16's configuration,
   so the wave-16 claim keeps its original strength and cannot weaken as
   the ramp's calibration changes. A0 gets the same assert only when the
   ramp reached the option, where the two are the same scene.
4. **The MP leg now sweeps the camera in a full circle before the
   injection.** Compiles are frustum-gated, so a camera that has only faced
   east has a wedge-shaped record set, and a ramp that measures coverage
   all the way round would honestly refuse to open over it. This is an
   arrangement the wave-16 leg silently depended on and never stated.

The representative leg the design asked for is
`assertNormalWorldSeamSweep`, opt-in behind `meshelium.test.deepSeam`: a real
`WorldPresets.NORMAL` world at a fixed seed, a 360 degree coverage sweep, an
injected server radius of 6 under an option of 16, and 24 diagonal hops of
32 blocks with 8 ticks each. It asserts on the oracle and takes screenshots
only for the eye. The pre-existing opt-in flight sweep was EXTENDED rather
than replaced: it is now diagonal (an axis-aligned sweep is vacuous against
S3) and prints the oracle beside every frame.

### 11.5 Rejected, and why

* **B5 (retention-aware pending-upload discard).** Rejected for this wave
  on scope: it needs a second position-keyed pending queue with its own
  eviction participation, which is the largest single piece of new
  lifecycle machinery in the whole design, and its steady-flight window is
  "roughly one pump" against a 16 MiB budget that is never the binding
  constraint. `discardedBeforeUpload` is now PRINTED (A2), so the next real
  session measures the burst case instead of arguing about it. This is the
  one unfixed contributor to P0's non-zero bar.
* **B6 (distance-aware eviction).** Rejected as out of scope for a wave
  about the seam: the recon itself calls it inert today
  (`retainTerrainMinutes` defaults to 0 and the pressure legs need 85/90
  percent of budgets nothing in the harness approaches). `evictAge` and
  `evictPressure` are already on the residency line, so a real session that
  moves them is the trigger to build it.
* **B7 (GateIndex width).** Rejected: translucent-only, fails OPEN, costs
  extra draws rather than pixels, and needs an option above ~72 to reach.
  It touches the shader/CPU packing contract, which is a poor thing to
  change in a wave whose whole subject is elsewhere.
* **The recon's blanket fail-open for B2.** See 11.2.
* **A Chebyshev annulus.** See 11.1.
* **Ramping the far plane.** See 11.3, and the design already said not to.
* **Widening the ViewArea grid.** Unchanged from the design's own
  rejection: the server will not send the data.
* **The frame-list ring `frameSerial` tripwire (UNVERIFIED 9).** Not added.
  It is a culling-correctness probe with no bearing on the seam, and the
  wave was already large.

### 11.6 New UNVERIFIED items

1. **Nothing was run.** No gradle, no client, no gametest. Every runtime
   claim in this section is a source fact plus an argument.
2. **The tolerance is derived but still uncalibrated in one direction.**
   The four-column slack is exactly the structural count ring `E+1` needs
   (11.3), so the wall can always leave the floor; what is NOT measured is
   whether real streaming leaves ring `E+1` one or two columns short of
   that in practice, in which case the wall parks at the floor anyway and
   the knob to move is `meshelium.tune.coverageSlackColumns`. The ramp leg
   says exactly that in its failure text rather than passing quietly.
3. **Whether the harness's swept superflat clears every annulus out to the
   option is unmeasured.** If it does not, the ramp leg fails with a
   calibration message rather than a bug.
4. **The 250 ms evaluation interval's cost at a fully ramped option 96 is
   arithmetic, not measurement**: about 37k column lookups under Meshelium's
   LOCK, four times a second. The probe cap at `current + 1` means a
   session that never ramps pays 361 lookups instead, which is the common
   case, but the fully-ramped case has not been timed.
5. **B1's blend-order overlap band is derived, not observed.** The six
   blocks horizontally and the vertical case in 11.2 come from the disc
   arithmetic; nobody has looked at the pixels.
6. **`GridSnapshot`'s centre lags vanilla's BFS centre** by up to one
   section while the camera crosses a boundary (ViewArea's
   `centerSectionPos` versus `SectionPos.of(cameraPos)` read live in
   `runUpdates`). Both consumers fail open, so the cost is bounded by
   argument, not measured.

---

## 12. The wave-17 REPAIR PASS (appended 2026-08-11)

Three adversarial lenses reviewed the wave-17 implementation before any
client ran. All four untouchable invariants survived; the criterion, the
slew rates and the test suite did not. This section records what changed
and, where a finding was rejected, why. Still nothing has been run.

### 12.1 Production changes

1. **B1 and B2 are gated on the widening being active.** Both claims key on
   `GridSnapshot.withinBfsReach` alone, so they ran identically in
   singleplayer, where they provably buy zero pixels: a claimed section
   fails `isWithinDistance` only when
   `max(0,|dx|-1)^2 + max(0,|dz|-1)^2 >= vd^2`, whose nearest block is at
   least `vd*16` away, and `FogData.renderDistanceEnd` is `fogRd*16` and
   fully opaque there. With `fogRd == vd` every claimed section renders flat
   fog colour. Both sites now consult `TerrainDrawer.widerThanGrid`
   (`MesheliumRetentionHorizon.lastFogRenderDistance() > grid.viewDistance()`),
   which is false on every singleplayer frame and every floor-parked
   multiplayer frame, so the wave-16 draw set is byte-for-byte preserved
   there and the fix still fires in exactly the band it exists for.
2. **The ramp's acceptance test became absolute.** See 11.3, rewritten.
3. **The slew asymmetry was inverted** to match the javadoc's own rationale:
   750 ms out, 250 ms in with no confirmation spent on the way in, i.e. 64
   blocks per second inward, which outruns rocket elytra. The first draft
   closed at 8 blocks per second, slower than a sprint-jump.
4. **Discontinuities reset the wall instead of being slewed across.**
   `reset()` is reached only from `SectionRenderDispatcher.dispose()`, so a
   same-dimension teleport, a config toggle and any gap in the fog site's
   calls all left the wall parked at a radius earned somewhere else. The
   ramp now remembers its measurement's grid centre and snaps to the floor
   on a jump of more than 4 sections between evaluations, on a call gap over
   1000 ms, and whenever there is no grid. Counted as `rampResync` on the
   draw-path line.
5. **Value hysteresis**, two columns wide, so a ring hovering on the bar
   cannot drive a 16-block limit cycle.
6. **`reset()` clears `evaluations`/`raises`/`lowers`**, which their javadoc
   already claimed and the harness relies on.
7. **`Coverage` carries `annulusHoles[]`**, and `uncoveredInAnnulus` is the
   quantity the tolerance bounds.
8. **Stale per-frame counters.** `lastUnreachableMaskSections` is zeroed on
   the occlusion path (it is only ever written by the bfs path, so the
   "last frame" stats group printed a frozen figure on the DEFAULT path),
   and both translucent pre-pass counters are zeroed on the Meshelium-empty
   early return.
9. **The decode guard-trip sentence** points at the once-only WARN instead
   of at the residency error latch, which `onSectionDecodeMiss` deliberately
   never sets.

### 12.2 Harness changes

* **P1/P2 are positive assertions now**, in a scene that cannot be vacuous:
  a chunk-aligned stained-glass sheet is filled at chunk offset (-6,-6)
  (inside the pad-1 disc at radius 8, `50 < 64`), the camera is aimed at
  it, the ramp is forced off so the widening gate is certainly open, and
  the one-chunk diagonal step moves it to (-7,-7) (`72 >= 64`, outside the
  disc, still inside the square). The leg re-verifies all three geometric
  premises and then asserts `lastUnreachableMaskSections() > 0` and
  `lastUnreachableTranslucentSections() > 0`. Reverting either fix makes
  both exactly 0.
* **B4's CALL SITE is tested**, not only its predicate. A new leg forces
  `SectionBuildTap.outsideVanillaGrid` true through
  `meshelium.test.forceOutsideGrid`, digs four whole sections out to air so
  vanilla's empty-compile free at `CompileTask.doTask` ip 209 fires out of
  bracket on a slot owner, and asserts `orphanedOutsideGrid` rises while
  the retained set does not shrink and `orphanedChunkAbsent` stays put.
  Then it refills with stone so the ghosts supersede on the normal path.
* **B3's CALL SITE is tested** through
  `SectionBuildTap.armForcedDecodeMiss()`, a one-shot that routes the next
  decoded section down the empty-quads branch. Reverting that line to
  `onSectionCompiledEmpty` moves `retainedSuperseded` and the leg fails. A
  volatile flag rather than a system property, deliberately: this is read
  once per decoded section on every build worker, and
  `System.getProperty` is a synchronized Hashtable read.
* **P3 chooses its subject** (`TerrainResidency.retainedSectionPositions`
  filtered by `chunkAbsentClientSide`) instead of taking the first entry of
  an insertion-ordered map, most of which sits outside the client chunk
  cache after a render-distance drop, where the wave-16 guard correctly
  KEEPS the copy. Its before/after snapshots are now taken inside the same
  client task as the call.
* **P6(a)'s expectation is genuinely independent**: it walks the raw ledger
  (`seenColumn`) with its own floating-point bucketing rather than calling
  the same `coverage()` with a copy of the same loop, and three
  world-independent geometry constants pin the annulus definition itself
  (`coverage(0,0,0,2,BIG)` must report 1 / 4 / 8 columns, 13 total).
* **P6(b) gained a clock-free progress bound**: 1000 ramp calls inside one
  client task must leave the wall on the floor and must produce exactly one
  evaluation. The per-tick sample survives as the no-snap check.
* **P0's bar** dropped its floor of 16, whose two stated justifications were
  both false (B5 discards produce NOT-SEEN columns, not seen-and-unheld
  ones, and no leg removed a block until the B4 leg above). It is now the
  fractional scene term plus `discardedBeforeUpload`, a hard upper bound on
  what the one unfixed loss mechanism could have stranded.
* **The deep-seam leg re-verifies its three premises** (option, ViewArea
  radius, and that at least one ledgered column spans more than one section,
  which a superflat can never produce), and its hole bar's fractional term
  is no longer dead code.
* **The three ramp shots share one sky reference**, taken from the
  retention-off frame, the way `assertRetainedSkylinePixels` already did it.

### 12.3 Rejected findings, with the reason

* *"A player who joined and stood still has 0 percent coverage at ring
  E+1."* False, and it matters because it was the strongest form of the
  "the ramp can never move" claim. Ring `E+1` is measured against the pad-1
  disc, not against a circle of radius `E`: `ChunkTrackingView
  .isWithinDistance` subtracts one chunk per axis before comparing, so a
  stationary player reaches 87.5 to 96.2 percent of that ring depending on
  radius. The four columns it never reaches are the axial ones, and the
  shipped tolerance is exactly four.
* *"Return the wall one chunk short of the last passing ring"* (the
  annulus-index-bucketed-by-centre finding). Rejected: it costs the entire
  gain at ring `E+1`, which is the only ring live coverage can supply, so
  the wall would sit at the floor forever. The exposed sliver it describes
  is at most 11 blocks of a ring that sits inside the fog fade band by
  construction. Documented, not fixed.
* *"Move the coverage walk off the render thread."* Real, and named in the
  class javadoc as accepted rather than hidden. Not fixed in a repair pass:
  the probe cap keeps the common case at 361 lookups four times a second,
  and the converged option-96 case has never been timed, so this would be
  optimising against arithmetic.
