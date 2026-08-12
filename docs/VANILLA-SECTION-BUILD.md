# The vanilla section BUILD pipeline — where 26.2 meshes terrain on the CPU, and where Meshelium taps it

Wave-3 recon, 2026-08-09. Method: `javap -p -c` / `javap -v` (BootstrapMethods)
against the real jar
(`attack-of-the-bteam-1.26.2/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/…`),
plus `jar -tf` census and binary grep over extracted classes. Companion of
`VANILLA-FRAME-PATH.md` (the DRAW side: `ChunkSectionsToRender.renderGroup` →
`drawMultipleIndexed` over `SectionUberBuffers` slices); this document maps
the BUILD side — how those uber buffers get FILLED — and picks the seam where
Meshelium's 16-byte encoder runs alongside vanilla (dual-pipeline phase, waves
3–4). Every claim cites class.method + bytecode ips; UNVERIFIED items are
marked inline and collected in the ledger at the end.

Status: **Q1–Q5 CLOSED** (2026-08-09).

---

## Q1 — The build lineage: dirty section → filled uber buffer

### 1.1 Cast of classes (javap-verified, all in the 26.2 jar)

`net/minecraft/client/renderer/chunk/`: `SectionRenderDispatcher`
(+ inner `RenderSection`, `RenderSection$SectionTask`,
`RenderSection$SectionTask$SectionTaskResult{SUCCESSFUL,CANCELLED}`,
`RenderSection$CompileTask`, `RenderSection$ResortTransparencyTask`,
`SectionUberBuffers`, `RenderSectionBufferSlice`), `SectionCompiler`
(+ `$Results`), `CompiledSectionMesh` (+ `$1` = `UNCOMPILED`, `$2` = `EMPTY`),
`SectionMesh` (+ `$SectionDraw`), `SectionTaskDynamicQueue`,
`RenderRegionCache`, `RenderSectionRegion`, `SectionCopy`,
`TranslucencyPointOfView`, `VisGraph`, `VisibilitySet`, `ChunkSectionLayer`.
Elsewhere: `renderer/ViewArea`, `renderer/SectionBufferBuilderPack/Pool`,
`renderer/RenderBuffers`, `renderer/SectionOcclusionGraph`,
`renderer/extract/LevelExtractor`, `client/RotatingSectionStorage`
(+ `$Value`), `client/SectionUpdateTracker` (+ `$SectionDirtyState`),
`renderer/state/level/SectionUpdateRenderState`,
`blaze3d/vertex/{BufferBuilder, ByteBufferBuilder, MeshData, VertexConsumer,
QuadInstance, UberGpuBuffer, StagingBuffer, TlsfAllocator, StagedVertexBuffer}`.

Key shapes (javap -p):

- **`SectionRenderDispatcher`** fields: `SectionTaskDynamicQueue queue`,
  `SectionBufferBuilderPack fixedBuffers`, `SectionBufferBuilderPool
  bufferPool`, `volatile boolean closed`, `TracingExecutor executor`,
  `Consumer<RenderSection> onSectionMeshUpdate`, `AtomicReference<Vec3>
  cameraPosition`, `volatile SectionCompiler sectionCompiler`,
  `StagingBuffer stagingBuffer`, `Map<ChunkSectionLayer, SectionUberBuffers>
  chunkUberBuffers`, `ReentrantLock copyLock`.
- **`RenderSection implements RotatingSectionStorage$Value`** fields:
  `public final int index` (slot index in the rotating grid), `public final
  AtomicReference<SectionMesh> sectionMesh` (init `CompiledSectionMesh
  .UNCOMPILED`, ctor ip 14–25), `private volatile long sectionNode`
  (`SectionPos.asLong`), `renderOrigin` (MutableBlockPos), `lastCompileTask`,
  `lastResortTransparencyTask`, `bb` (AABB), `uploadedTime`/`fadeDuration`/
  `wasPreviouslyEmpty`.
- **Task types — exactly two**: `CompileTask` (full rebuild; holds the
  `RenderSectionRegion` block-data snapshot) and `ResortTransparencyTask`
  (sort-only; holds the live `CompiledSectionMesh`). Both extend
  `SectionTask{AtomicBoolean isCancelled, isCompleted; boolean isRecompile;
  doTask(SectionBufferBuilderPack) → SectionTaskResult}`.
- **`SectionUberBuffers`** = record `{UberGpuBuffer<SectionMesh> vertexBuffer,
  UberGpuBuffer<SectionMesh> indexBuffer}` — the generic key type is
  `SectionMesh`: **allocations inside the uber heaps are keyed by
  `CompiledSectionMesh` OBJECT IDENTITY** (`UberGpuBuffer.getAllocation(T)`,
  `removeAllocation(T)`, field `Map<T, TlsfAllocator$Allocation>
  allocationMap` — plain `HashMap(256)`, ctor ip 41–49).

### 1.2 Construction & wiring (LevelRenderer.invalidateCompiledGeometry bytecode)

`LevelRenderer.invalidateCompiledGeometry(ClientLevel, Options, Camera,
BlockColors)`:

1. `new SectionCompiler(options.ambientOcclusion(), options.cutoutLeaves(),
   modelManager.getBlockStateModelSet(), modelManager.getFluidStateModelSet(),
   blockColors)` (ip 0–49). On subsequent calls just
   `dispatcher.setCompiler(new one)` (ip 95–101) — the compiler is swap-in
   (`volatile` field), the dispatcher survives resource reloads.
2. First time: `new SectionRenderDispatcher(Util.backgroundExecutor(),
   renderBuffers, compiler, sectionOcclusionGraph::schedulePropagationFrom)`
   (ip 58–89; the Consumer's bootstrap MethodHandle is
   `REF_invokeVirtual SectionOcclusionGraph.schedulePropagationFrom:
   (LSectionRenderDispatcher$RenderSection;)V` — javap -v constant #1470).
   **`onSectionMeshUpdate` = feed the BFS graph; every mesh swap notifies the
   occlusion graph.**
3. `viewArea.releaseAllBuffers()` (if any) → `dispatcher.clearCompileQueue()`
   → `new ViewArea(dispatcher, level.getMinY(), getMaxY(), getMinSectionY(),
   getMaxSectionY(), options.getEffectiveRenderDistance(),
   sectionOcclusionGraph)` → `sectionOcclusionGraph.waitAndReset(viewArea)` →
   `clearVisibleSections()` → `viewArea.repositionCamera(cameraSectionPos)`
   (ip 127–220).

`SectionRenderDispatcher.<init>` bytecode: `queue = new
SectionTaskDynamicQueue()`; `stagingBuffer = StagingBuffer.create("Chunk",
RenderSystem.getDevice(), 102_760_448)` (98 MiB; `StagingBuffer.create`
returns `$PersistentlyMapped` when `deviceInfo.hintsAndWorkarounds()
.writeToBufferIsSlow() && features.persistentMapping()`, else `$Cpu` —
bytecode ip 0–44); `chunkUberBuffers = Util.makeEnumMap(ChunkSectionLayer,
layer → new SectionUberBuffers(
  new UberGpuBuffer(layer.label(), usage=32, heapSize=134_217_728 /*128 MiB*/,
                    alignSize=layer.pipeline().getVertexFormatBinding(0)
                    .getVertexSize(), stagingBuffer),
  new UberGpuBuffer(layer.label(), usage=64, heapSize=33_554_432 /*32 MiB*/,
                    alignSize=8, stagingBuffer)))` (`lambda$new$0`, ip 0–66).
So: **one shared 98 MiB staging ring + per layer one 128 MiB-heap vertex uber
buffer and one 32 MiB-heap index uber buffer** (heaps are created lazily and
more heaps are appended when full — 1.5).

### 1.3 The worker pool — executor, thread count, gating

- Executor: **`Util.backgroundExecutor()`** — the game-wide shared
  `TracingExecutor` over a `ForkJoinPool` with parallelism
  `Mth.clamp(availableProcessors − 1, 1, getMaxThreads())`, where
  `getMaxThreads()` reads sysprop `max.bg.threads` (1..255, default 255)
  (`Util.makeExecutor` ip 0–45, `maxAllowedExecutorThreads` ip 0–15). **There
  is no dedicated meshing pool in 26.2** — section builds share the
  background FJP with everything else.
- Concurrency gate: `SectionBufferBuilderPool.allocate(n)` with `n =
  Runtime.availableProcessors()` (from `new RenderBuffers(nproc)` in
  `GameRenderer`, ip 201–213) clamped to `max(1, min(n, (int)(maxMemory×0.3)
  / SectionBufferBuilderPack.TOTAL_BUFFERS_SIZE))` (allocate ip 0–30;
  OutOfMemory fallback keeps 2/3, ip 72–118). `TOTAL_BUFFERS_SIZE` = sum of
  `ChunkSectionLayer.bufferSize()` = 4 MiB (SOLID) + 4 MiB (CUTOUT) + 768 KiB
  (TRANSLUCENT) = **9,175,040 B per pack** (ChunkSectionLayer `<clinit>`:
  4194304/4194304/786432).
- Scheduling: `schedule(task)` = `queue.add(task)` +
  `executor.execute(this::runTask)` (ip 8–26). `runTask` polls ONE task,
  `bufferPool.acquire()` (returns null when exhausted →
  `Objects.requireNonNull` NPE → catch → task re-queued, ip 121–130),
  `task.doTask(pack)`, `clearAll()` on success / `discardAll()` on cancel,
  `release(pack)`, then **re-submits itself** (`executor.execute(runTask)`,
  ip 105–117). Effective build parallelism = min(free packs, FJP threads).
- `cameraPosition` (AtomicReference, set from `setCameraPosition`) feeds both
  queue priority and vertex sorting on workers — a deliberate cross-thread
  snapshot, no lock.

### 1.4 Task selection (`SectionTaskDynamicQueue.poll(Vec3)` bytecode)

Synchronized linear scan: drops cancelled tasks; tracks the nearest
non-recompile task and the nearest recompile task by
`getRenderOrigin().distToCenterSqr(camera)`. A recompile (`isRecompile` —
true for every `ResortTransparencyTask` (super ctor arg `true`) and for
`CompileTask`s whose section already has a mesh, `createCompileTask` ip 4–31)
wins only while `recompileQuota > 0 && recompileDist < initialDist`; quota
starts/resets at `MAX_RECOMPILE_QUOTA = 2` and decrements per recompile
polled — i.e. **initial compiles are favored 1:2 against rebuilds/resorts,
nearest-first**.

### 1.5 The full flow: block change → pixels

1. **Dirty marking (main/game thread):** `LevelExtractor.setBlockDirty/
   setSectionDirty/setSectionDirtyWithNeighbors/setSectionRangeDirty` →
   `SectionUpdateTracker.setDirty(x,y,z, playerChanged)` — a
   `RotatingSectionStorage<SectionDirtyState>` of `{boolean isDirty,
   isDirtyFromPlayer}` per section slot.
2. **Extraction (render thread, inside `gameRenderer.extract` — frame-path
   Q1):** `LevelExtractor.extract` ip 432–584: `new RenderRegionCache()`;
   for each section in **`levelRenderer.visibleSections()`** (last frame's
   BFS output — invisible sections are never scheduled) with
   `dirtyState.isDirty()`: if mesh == `UNCOMPILED` it additionally requires
   `sectionUpdateTracker.hasAllNeighbors(level, node)` (all 4 horizontal +
   4 diagonal chunk neighbors FULL + lit, `hasAllNeighbors` ip 0–125); then
   `levelRenderState.sectionUpdateRenderStates.add(new
   SectionUpdateRenderState(sectionNode, isDirtyFromPlayer,
   regionCache.createRegion(level, sectionNode)))` + `setNotDirty()`.
   **The block-data snapshot (`RenderSectionRegion` = 3×3×3
   `SectionCopy` palette copies + level/light refs) is taken here, on the
   render thread — workers never touch live chunk data.**
3. **Scheduling (render thread, `LevelRenderer.compileSections`, called from
   `LevelRenderer.render` ip 602–613 AFTER `FrameGraphBuilder.execute`):**
   per `SectionUpdateRenderState`: fade bookkeeping, then
   `renderSection.compileSync(region)` if (`prioritizeChunkUpdates` says so
   AND (within √768 ≈ 27.7 blocks OR playerChanged)) else
   `compileAsync(region)` (ip 108–256). `compileSync` runs
   `doTask(dispatcher.fixedBuffers)` INLINE ON THE RENDER THREAD (bytecode:
   direct call, no queue). Then `scheduleTranslucentSectionResort(cameraPos)`
   (ip 262–276; see Q4.2).
4. **Compile (worker or render thread, `CompileTask.doTask`):**
   cancelled? → CANCELLED. Else `SectionPos.of(sectionNode)` → snapshot
   `cameraPosition` → profiler zone `"Compile Section"` →
   **`SectionCompiler.compile(sectionPos, region,
   createVertexSorting(pos, cam), pack)` → `SectionCompiler$Results`**
   (ip 72–103) → `TranslucencyPointOfView.of(cam, node)` → **`new
   CompiledSectionMesh(pov, results)`** (ip 145–164). If
   `results.renderedLayers.isEmpty()`: immediately
   `setSectionMesh(compiled)` + release old under `copyLock` → SUCCESSFUL
   (ip 166–249; empty sections never touch GPU buffers). Else per
   `(layer, MeshData)` in `results.renderedLayers`: spin-loop
   `addSectionBuffersToUberBuffer(layer, compiled, meshData.vertexBuffer(),
   meshData.indexBuffer())` until it returns true (on failure:
   `Thread.onSpinWait()` if off-thread — the worker BLOCKS until the render
   thread drains staging; cancellation re-checked each spin, ip 301–429) —
   then **`meshData.close()`** (ip 432: the CPU ByteBuffers die immediately
   after the staging memcpy).
5. **Staging (same thread, `RenderSection.addSectionBuffersToUberBuffer`,
   ip 0–191):** takes `dispatcher.copyLock`; skips layers with no
   `SectionDraw`; `uberBuffers.vertexBuffer.addAllocation(compiledMesh,
   this::vertexBufferUploadCallback, vertexBuf)` and likewise index with
   `indexBufferUploadCallback(…, isResortOnly = vertexBuf == null)`.
   `UberGpuBuffer.addAllocation` = `stagingBuffer.tryAppend(buf)` →
   `MemoryUtil.memCopy` into the staging ring at `nextWriteOffset` (returns
   null → **false = staging full**, tryAppend ip 44–56/57–97) + a
   `StagedAllocationEntry{BufferHandle, callback}` put into
   `stagedAllocations` keyed by the mesh (replacing/closing a previous
   staged entry for the same key). A null indexBuffer (SOLID/CUTOUT —
   sequential quad indices) short-circuits to
   `compiled.setIndexBufferUploaded(layer)` (ip 140–144). If false on the
   RENDER thread, it drains inline via `uploadTerrainBuffersToGpu()`
   (ip 150–160, reentrant under copyLock).
6. **GPU upload (render thread only):** `LevelRenderer.render` ip 630–658:
   `dispatcher.lock(); uploadTerrainBuffersToGpu(); unlock()` — right after
   `compileSections`, after the frame graph executed.
   `uploadTerrainBuffersToGpu` = `stagingBuffer.startUploading(
   device.createCommandEncoder())` → per layer
   `UberGpuBuffer.uploadStagedAllocations(device, uploader)`: frees the OLD
   allocation of every staged key, TLSF-allocates
   (`TlsfAllocator.allocate(size, alignSize)`) in existing heaps or creates
   a new `UberGpuBufferHeap` (128 MiB vertex / 32 MiB index `GpuBuffer`) —
   then `uploader.copyTo(handle, gpuBuffer, offsetFromHeap)` (records
   `StagingBuffer.copyTo(encoder, …)` — a GPU copy command on the shared
   encoder), `allocationMap.put(key, allocation)`, and **fires the
   `UploadCallback` synchronously** (ip 419–491). Fully-free heaps are
   closed (ip 588–661).
   Loop oddity: the dispatcher breaks out of the per-layer upload loop when
   a layer's VERTEX upload returns true (= heap created or destroyed;
   `uploadTerrainBuffersToGpu` ip 51–79). Mechanism proven; intent
   UNVERIFIED (remaining layers are picked up by the next frame's call).
7. **Promotion (render thread, via the callbacks):**
   `vertexBufferUploadCallback` → `compiled.setVertexBufferUploaded(layer)` →
   `checkSectionMesh(compiled)`; same for index (skipped when resort-only).
   `checkSectionMesh` (ip 0–89): when EVERY layer with a `SectionDraw` has
   both flags set AND `sectionMesh.get() != compiled`:
   `setSectionMesh(compiled)` (AtomicReference.getAndSet + fire
   `onSectionMeshUpdate` → `SectionOcclusionGraph.schedulePropagationFrom` +
   stamp `uploadedTime` for fade-in) then `releaseSectionMesh(oldMesh)`.
   **The DRAW side picks the new mesh up next frame**:
   `prepareChunkRenders` reads `section.getSectionMesh()` +
   `dispatcher.getRenderSectionSlice(mesh, layer)` (frame-path Q2.5), which
   resolves `allocationMap.get(mesh)` → `{gpuBuffer, offsetFromHeap}`.

### 1.6 Synchronization inventory (build ↔ render thread)

| Mechanism | Guards | Proof |
|---|---|---|
| `dispatcher.copyLock` (ReentrantLock) | staging appends (`addSectionBuffersToUberBuffer`), mesh frees (`releaseSectionMesh` — via `reset()` ip 22–43, via doTask ip 197–222/331–356, via render-thread upload window), `uploadTerrainBuffersToGpu` (LevelRenderer takes `lock()` around it), `dispose()` | bytecode of each |
| `sectionMesh` AtomicReference | mesh visibility swap; draw side reads it lock-free | `setSectionMesh` getAndSet |
| per-layer `AtomicBoolean` upload flags | promotion only after all GPU copies are RECORDED | `checkSectionMesh` |
| worker spin-wait (`Thread.onSpinWait`) | staging-full back-pressure; drained only by the render thread's upload | doTask ip 415–429 |
| one-frame latency by construction | uploads are recorded AFTER the frame's terrain draws (render ip 613 vs frame-graph execute ip 572), and promotion happens during upload recording — a new mesh is first DRAWN one frame after its copies were recorded | LevelRenderer.render order |
| task cancel CAS | `CompileTask.cancel` compareAndSet; `cancelTasks()` from `createCompileTask`/`reset` | bytecode |

---

## Q2 — The quad stream: what geometry exists where, in what format

### 2.1 Emission (`SectionCompiler.compile` bytecode, complete flow)

Signature: `compile(SectionPos, RenderSectionRegion, VertexSorting,
SectionBufferBuilderPack) → SectionCompiler$Results`. Per call: `new
Results()`; `new VisGraph()`; `BlockModelLighter.enableCaching()`; `new
ModelBlockRenderer(ambientOcclusion, true, blockColors)`; `new
FluidRenderer(fluidModelSet)`; a lazy `EnumMap<ChunkSectionLayer,
BufferBuilder>` (ip 71–80). Then for all 4096 `BlockPos.betweenClosed(origin,
origin+15³)` (ip 118+):

- `region.getBlockState(pos)` (the SNAPSHOT, not the live level); air →
  skip; solid → `visGraph.setOpaque(pos)`; block entity →
  `results.blockEntities.add` (`handleBlockEntity`).
- Fluids: `fluidRenderer.tesselate(region, pos, FluidRenderer$Output,
  state, fluidState)` (ip 232–243) where the Output (`lambda$compile$2`)
  returns the per-layer `BufferBuilder` as a raw `VertexConsumer` — **fluid
  geometry is emitted vertex-by-vertex, NOT through the quad object path.**
- Block models: `modelBlockRenderer.tesselateBlock(BlockQuadOutput,
  x, y, z, region, pos, state, blockModelSet.get(state), state.getSeed(pos))`
  with **`x/y/z = SectionPos.sectionRelative(pos.getX/Y/Z()) as float`**
  (ip 278–304) — **geometry is SECTION-LOCAL, [0,16) space** (block models
  can overhang slightly outside).
- The `BlockQuadOutput` sink (a functional interface: `put(float, float,
  float, BakedQuad, QuadInstance)`): `lambda$compile$0` routes by
  `quad.materialInfo().layer()` (a `ChunkSectionLayer` on the baked quad!),
  `lambda$compile$1` forces SOLID (used when
  `ModelBlockRenderer.forceOpaque(cutoutLeaves, state)`); both call
  **`BufferBuilder.putBlockBakedQuad(x, y, z, quad, quadInstance)`**.
- `getOrBeginLayer`: `new BufferBuilder(pack.buffer(layer),
  PrimitiveTopology.QUADS, layer.vertexFormat())` on first use — **layers
  that receive no geometry never allocate a builder; `renderedLayers` only
  contains non-empty layers.**

`putBlockBakedQuad` is a **default method on `VertexConsumer`** (NOT declared
on BufferBuilder — super-type rule!). Bytecode: `normal =
quad.direction().getUnitVec3f()`; `emission =
quad.materialInfo().lightEmission()`; for i in 0..3:
`addVertex(quad.position(i).x + x, …y…, …z…, quadInstance.getColor(i),
UVPair.unpackU/V(quad.packedUV(i)), quadInstance.overlayCoords(),
quadInstance.getLightCoordsWithEmission(i, emission), normal.x, normal.y,
normal.z)` — the 11-arg fast path.

Where the per-quad data lives at emission time (all javap-verified records):

- **`BakedQuad`** (record): `Vector3fc position0..3`, `long packedUV0..3`,
  **`Direction direction`** (explicit facing!), `MaterialInfo materialInfo`
  (carries `layer()` + `lightEmission()`).
- **`QuadInstance`**: `int color0..3` (per-vertex tint), `int
  lightCoords0..3` (per-vertex packed lightmap), `int overlayCoords`.

### 2.2 The terrain vertex format — 28 bytes, and NO normal

`ChunkSectionLayer.vertexFormat()` = `pipeline().getVertexFormatBinding(0)`;
the layer pipelines are `RenderPipelines.SOLID_TERRAIN / CUTOUT_TERRAIN /
TRANSLUCENT_TERRAIN` (ChunkSectionLayer `<clinit>`). The format in play is
**`DefaultVertexFormat.BLOCK`** — proven two independent ways: (1)
`BufferBuilder.<init>` sets `blockFormat = (format ==
DefaultVertexFormat.BLOCK)` (ip 138–151) and terrain writes take the
blockFormat fast path below; (2) `DefaultVertexFormat.<clinit>` builds BLOCK
as exactly the four attributes the fast path writes.

`DefaultVertexFormat.BLOCK` (clinit ip 42–96) — offsets from
`BufferBuilder.addVertex(FFFIFFIIFFF)` blockFormat path (ip 0–65):

| offset | attribute | GpuFormat | bytes | written as |
|---|---|---|---|---|
| 0 | `Position` | `RGB32_FLOAT` | 12 | `putVec3f(x,y,z)` — section-local floats |
| 12 | `Color` | `RGBA8_UNORM` | 4 | `putRgba` = `ARGB.toABGR(int)` → memPutInt (bytes R,G,B,A in memory on LE) |
| 16 | `UV0` | `RG32_FLOAT` | 8 | two memPutFloat (u, v — atlas UVs) |
| 24 | `UV2` | `RG16_SINT` | 4 | `putPackedUv(light)` = packed lightmap int as 2× int16 (block, sky) |

**Stride = 28 bytes.** The 11-arg addVertex's `overlay` (arg 7) and
`normal x/y/z` (args 9–11) are **DROPPED on the blockFormat path** (compare
the entityFormat path, ip 68–159, which writes UV1 + Normal at 24/28/32).
**There is no Normal element in 26.2 terrain vertices — facing is not
recoverable from an explicit vertex field.** (1.21's 32-byte BLOCK with
NORMAL+padding is gone.)

### 2.3 The one-CPU-container moment: `SectionCompiler$Results`

After the block loop, per non-empty layer: `BufferBuilder.build()` →
**`MeshData`** (compile ip 376–485). `Results` (all `public` fields):

```
public final List<BlockEntity>                 blockEntities;
public final Map<ChunkSectionLayer, MeshData>  renderedLayers;   // EnumMap
public VisibilitySet                           visibilitySet;    // VisGraph.resolve()
public MeshData$SortState                      transparencyState; // TRANSLUCENT only
```

`MeshData` = `{ByteBufferBuilder$Result vertexBuffer /*final*/,
ByteBufferBuilder$Result indexBuffer /*null unless sorted*/, DrawState
drawState}` with `DrawState{VertexFormat format, int vertexCount, int
indexCount, PrimitiveTopology primitiveTopology /*QUADS*/, IndexType
indexType /*least(vertexCount): SHORT below 65536 vertices else INT*/}`.
`vertexBuffer()`/`indexBuffer()` expose plain readable `ByteBuffer`s.

For TRANSLUCENT only (compile ip 444–470): `meshData.sortQuads(
pack.buffer(TRANSLUCENT), vertexSorting)` — computes per-quad centroids
(`decodeQuadCentroids`: midpoint of vertex 0 and vertex 2 positions, quads =
vertexCount/4) into a `CompactVectorArray`, builds
`SortState{centroids, indexType}`, and **sets `meshData.indexBuffer` to a
freshly built distance-sorted index buffer** (sortQuads ip 75–83). SOLID and
CUTOUT keep `indexBuffer == null` and are drawn with the shared sequential
quad→triangle index buffer (`RenderSystem.getSequentialBuffer(QUADS)`,
frame-path Q2.4); the `hasCustomIndexBuffer` flag on `SectionMesh$SectionDraw
{int indexCount, IndexType indexType, boolean hasCustomIndexBuffer}` records
exactly this distinction (`CompiledSectionMesh.lambda$new$2`).

**So: the complete section geometry exists in ONE readable CPU container per
layer — `Results.renderedLayers.get(layer)` — at `SectionCompiler.compile`
return, on the build thread, with:** positions (section-local floats,
quad-ordered: 4 consecutive 28-byte vertices per quad), UV0 atlas floats,
RGBA8 color (tint×AO premultiplied by the lighter), UV2 lightmap shorts, and
(TRANSLUCENT) camera-sorted indices + re-sortable centroids. Lifetime: until
`MeshData.close()` in `CompileTask.doTask` ip 432 — immediately after the
staging memcpy of that layer.

### 2.4 Facing: recoverable? bucketed?

- **Vanilla does NOT bucket quads per facing anywhere.** One `BufferBuilder`
  per layer; emission order = block-iteration order interleaved across
  facings; `Results`/`MeshData`/uber buffers carry no facing metadata
  (census of every field above). Nvidium's §2/§7 pre-condition (Sodium's 7
  `ModelQuadFacing` buckets per pass) **does not exist in 26.2 — Meshelium
  must bucket during re-encode.**
- Facing IS explicit at emission (`BakedQuad.direction`), but the only sinks
  are private synthetic lambdas + a `VertexConsumer` default method, and
  **fluid geometry never passes through `putBlockBakedQuad`** (2.1) — a
  quad-object tap misses fluids entirely.
- **Recovery at the MeshData level: derive from geometry.** Vertices are
  quad-grouped (4 per quad, topology QUADS). Facing = normalize(cross(v1−v0,
  v2−v0)) snapped to the dominant axis when axis-aligned (the overwhelming
  case for terrain), else Nvidium's UNASSIGNED bucket (drawn always,
  `task_common.glsl` gating — architecture §2). This covers block AND fluid
  quads uniformly, costs 1 cross product per quad during re-encode on the
  worker, and reproduces exactly the 7-bucket scheme
  (`offsets[0..6]` + translucent) Meshelium's task shader needs. Winding note:
  vanilla terrain pipelines cull BACK with frontFace CLOCKWISE (frame-path
  Q4.2), so the sign convention can be validated in wave 4's parity shot.

---

## Q3 — The tap point for the dual pipeline

### 3.1 Option (a) — intercept the finished CPU buffers (CHOSEN, two-stage)

**Primary tap: `@Inject(method = "compile", at = @At("RETURN"))` on
`SectionCompiler`.** Public method, stable descriptor, returns `Results`
with public fields; runs ONCE per section build, on the build thread (FJP
worker, or render thread for the compileSync path), BEFORE any cancellation
or staging (doTask calls it at ip 100 and only then constructs the mesh).
Available right there: `SectionPos` (arg 0 — section identity),
`RenderSectionRegion` (arg 1 — the snapshot, if the encoder ever wants block
context), per-layer `MeshData` (positions/UV/color/light per 2.3),
`transparencyState`, `visibilitySet`. Meshelium re-encodes synchronously into
its 16-byte format (64 B/quad), buckets by derived facing (2.4), computes
the geometry AABB while walking vertices (Nvidium repackager parity,
architecture §7), and parks the result keyed by the `Results` object.

**Identity link: `@Inject(method = "<init>", at = @At("TAIL"))` on
`CompiledSectionMesh`** — the ctor receives the same `Results` instance
(doTask ip 153–164), so Meshelium re-keys its parked encoding from `Results` →
`CompiledSectionMesh` object identity. That is EXACTLY the key vanilla uses
for its own uber-buffer allocations (1.1), so Meshelium's store and vanilla's
allocations have identical lifetime semantics by construction. The
section↔mesh binding (`sectionNode`) is re-confirmed at swap time (Q3.4/Q4).

Why not tap `CompileTask.doTask` locals instead: synthetic inner-class
method, local-capture injection, and it would still need the Results→mesh
link — strictly worse.

**Fallback within (a): `@Inject(HEAD)` on
`RenderSection.addSectionBuffersToUberBuffer`** — one method that sees layer
+ `CompiledSectionMesh` + both ByteBuffers on every geometry hand-off.
Costs: fires per layer, RE-FIRES on every spin-retry when staging is full
(doTask ip 304–429 — dedupe by (mesh, layer) required), must skip
`vertexBuffer == null` (resort-only), and the vertex ByteBuffer there is the
same `MeshData` memory anyway. Keep as fallback if the primary descriptors
churn.

### 3.2 Option (b) — a parallel sink inside the mesher (REJECTED for wave 3)

There is no pluggable encoder seam in 26.2: `BufferBuilder`'s block path is
hardcoded (`blockFormat` flag), the format comes from the layer pipeline (so
swapping it would corrupt vanilla's own draws — unusable in a DUAL phase
regardless), and the quad sinks are two private synthetic lambdas
(`SectionCompiler.lambda$compile$0/1`) plus a raw `VertexConsumer` for
fluids. A per-quad tap = 2 `@Redirect`s on `putBlockBakedQuad` call sites +
a full `VertexConsumer` proxy for the fluid path, and it would run per-quad
JNI-free but per-call overhead on the hottest loop in the mesher. The ONLY
thing it buys over (a) is explicit `BakedQuad.direction` — which (a)
recovers with one cross product. Revisit only if wave-4 parity shots show
facing misclassification (ledger item 4).

### 3.3 Option (c) — independent re-mesh from `RenderSectionRegion` (REJECTED)

The snapshot is available (LevelExtractor builds it per dirty section,
1.5.2) and could be re-meshed on Meshelium's own thread — but that doubles the
most expensive CPU stage (model resolution + AO), duplicates
`BlockStateModel` traversal logic that 26.2 just reshaped (BlockQuadOutput
is new-era), and buys nothing (a) doesn't already deliver. Reject per brief;
only reconsider if wave 6+ needs geometry vanilla never builds (e.g.
keep-distance beyond vanilla's grid, architecture §7 port note).

### 3.4 Lifecycle: how frees reach Meshelium (same seam family)

Every per-mesh free in the game funnels through ONE private method:
**`RenderSection.releaseSectionMesh(SectionMesh)`** = `mesh.close()` + per
layer `uberBuffers.{vertex,index}.removeAllocation(mesh)` (bytecode ip
0–62). Its callers (complete set, javap of the inner class + LevelRenderer):

1. replacement after a successful rebuild — `checkSectionMesh` ip 84–86 and
   the empty-mesh path in `doTask` ip 209 (old mesh freed);
2. cancelled-mid-copy compile — `doTask` ip 343 (the NEW mesh freed);
3. slot eviction — `RenderSection.reset()` (cancel tasks + getAndSet
   UNCOMPILED + release under copyLock), called from `setSectionNode`
   (ip 0–4) which `RotatingSectionStorage.repositionCenter` invokes for
   every slot whose wrapped grid position changed (ip 131–171), and from
   `ViewArea.releaseAllBuffers()` (iterates all slots → `reset()`), which
   runs on renderer reload/level change;
4. wholesale teardown — `SectionRenderDispatcher.dispose()` (closes all uber
   buffers + staging under copyLock; LevelRenderer calls it with
   `viewArea.releaseAllBuffers()` right before, levelrenderer dump lines
   2020–2040).

So Meshelium needs exactly TWO free hooks: `releaseSectionMesh` HEAD (free the
16-byte copy for that mesh; runs on worker OR render thread, always under
`copyLock` — see 1.6) and `dispose()` HEAD (drop the whole store). Threading
rule: the hook only marks/unmaps in Meshelium's CPU-side store; VkBuffer
arena frees are deferred to the render thread via the wave-2 destroy queue
(`VulkanCommandEncoder.queueForDestroy`, frame-path Q6.6).

**Upload timing hook (wave-3 optional, wave-4 required):** Meshelium's own
staging→arena GPU copies should be recorded on the render thread next to
vanilla's — `@Inject(at = @At(value="INVOKE", target =
uploadTerrainBuffersToGpu, shift = AFTER))` in `LevelRenderer.render`
(inside the `lock()`/`unlock()` window, ip 630–658), reusing the shared
encoder exactly as vanilla's `StagingBuffer.copyTo` does.

---

## Q4 — Section identity & lifecycle keys

### 4.1 What identifies a section

| Key | Type | Stability | Use for Meshelium |
|---|---|---|---|
| `sectionNode` | `long` (`SectionPos.asLong`) | volatile on RenderSection; changes on grid reposition (reset first) | world-space identity; matches `SectionOcclusionGraph`/`ChunkLoadingRenderState` long-key world |
| `RenderSection.index` | `public final int` | slot index into the `RotatingSectionStorage` node array, stable for the ViewArea's lifetime (assigned in `ViewArea.lambda$new$0`) | flat-array section store, size = `viewArea.size()` = (2r+1)²·height — the vanilla analogue of Nvidium's compact section ids |
| `CompiledSectionMesh` object identity | reference | one per successful compile; THE key of vanilla's uber-buffer `allocationMap` | Meshelium's geometry-copy key (Q3.1); free on `releaseSectionMesh` |
| `RenderSection` object identity | reference | one per slot, reused across sections | carries mesh↔node binding at swap time |

The binding moment mesh→section is **`RenderSection.setSectionMesh`**
(getAndSet + `onSectionMeshUpdate` fire): an `@Inject(RETURN)` there sees
`this.sectionNode`, `this.index`, the new mesh (arg) and the old mesh
(return value) — the atomic point where Meshelium flips its own section
record from old copy to new copy (wave 4's draw handoff; in wave 3 it just
validates the store).

### 4.2 Resort-only tasks — and why they never reach the Meshelium tap

Flow: `LevelRenderer.compileSections` tail →
`scheduleTranslucentSectionResort(cameraPos)` (ip 0–178): every
`nearbyVisibleSections` member each time the camera BLOCK changes, plus a
rotating `max(15, visibleSections/8)`-per-frame slice of `visibleSections`;
`scheduleResort` filters `mesh.isDifferentPointOfView(pov)` (or forced when
crossing block + non-axis-aligned) && `hasTranslucentGeometry()` &&
`!transparencyResortingScheduled()` → `RenderSection.resortTransparency()`
→ `new ResortTransparencyTask(compiledMesh)` → `dispatcher.schedule`.
`ResortTransparencyTask.doTask`: re-checks pov, then
`sortState.buildSortedIndexBuffer(pack.buffer(TRANSLUCENT), sorting)` →
spin-loop `addSectionBuffersToUberBuffer(TRANSLUCENT, mesh, **null**,
indexBytes)` → `setTranslucencyPointOfView`. **It never calls
`SectionCompiler.compile` and never constructs a `CompiledSectionMesh` — the
primary tap (Q3.1) is structurally blind to resorts, as required.** (The
`vertexBuffer == null` marker is also how the fallback tap must filter, and
how `indexBufferUploadCallback(…, true)` skips re-promotion — RenderSection
ip 97–117.)

### 4.3 Chunk unload — the finding: there is NO per-chunk free path

Binary grep + javap: `LevelRenderer` has no `onChunkUnloaded`; nothing calls
`RenderSection.reset()` on chunk unload. Chunk load/unload flows ONLY into
the BFS: `LevelExtractor.extract` ip 324–417 copies
`ClientChunkCache.addedEmptySections/removedEmptySections/addedLoadedChunks/
removedLoadedChunks` into `ChunkLoadingRenderState` (+
`flipUpdateTrackingSets`), consumed by `SectionOcclusionGraph.update(camera,
fov, chunkLoadingRenderState)` (LevelRenderer.render ip 694–713 —
`updateLoadedChunks`/`updateEmptySections`). An unloaded chunk's sections
KEEP their `CompiledSectionMesh` and uber-buffer allocations; they merely
drop out of `visibleSections`, and the memory is reclaimed only when (3) or
(4) of Q3.4 eventually hits the slot. **Meshelium inherits this retention
policy for free by keying on mesh identity — its copies are freed exactly
when vanilla's are, never earlier, never leaked** (wave 8 RESOLVED the
memory-pressure question without eviction: a section that fails to fit is
dropped with a counter, and any per-world drop trips the coverage guard —
Meshelium goes passive and vanilla draws everything, so pressure can cost
Meshelium a world but never pixels; Nvidium's keep-distance decoupling stays
architecture §7 territory).

---

## Q5 — Wave-3 tap plan

### 5.1 Recommendation (one paragraph)

Tap the build pipeline at **`SectionCompiler.compile` RETURN** (worker-side,
once per build, full per-layer `MeshData` still alive) and re-encode there
into Meshelium's 16-byte vertices with facing derived per quad and bucketed
into the 7 Nvidium ranges; re-key the encoding to the `CompiledSectionMesh`
at its ctor TAIL; mirror lifetime via `releaseSectionMesh` HEAD +
`dispose()` HEAD; observe the visible-swap at `setSectionMesh` RETURN;
record Meshelium's GPU copies right after vanilla's
`uploadTerrainBuffersToGpu` inside the existing lock window. Vanilla keeps
rendering untouched; resorts never trigger re-encodes; every Meshelium copy
dies exactly when vanilla frees the mesh it mirrors.

### 5.2 Mixin shopping list (wave 3)

| # | Target | Kind | Purpose / notes |
|---|---|---|---|
| 1 | `net/minecraft/client/renderer/chunk/SectionCompiler.compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;` | `@Inject(at=@At("RETURN"))` | re-encode all layers from `Results.renderedLayers` (public field); runs on FJP worker or render thread (compileSync); must be allocation-lean and thread-safe |
| 2 | `net/minecraft/client/renderer/chunk/CompiledSectionMesh.<init>(Lnet/minecraft/client/renderer/chunk/TranslucencyPointOfView;Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;)V` | `@Inject(at=@At("TAIL"))` | re-key parked encoding: Results → mesh identity (vanilla's own allocation key) |
| 3 | `net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection.releaseSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)V` | `@Inject(at=@At("HEAD"))` | free Meshelium's copy for that mesh (worker OR render thread; always under `copyLock`); GPU-side frees deferred via wave-2 destroy queue |
| 4 | `net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection.setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;` | `@Inject(at=@At("RETURN"))` | the atomic swap moment: bind mesh↔`sectionNode`/`index` in Meshelium's section table (wave 4 flips draws here) |
| 5 | `net/minecraft/client/renderer/chunk/SectionRenderDispatcher.dispose()V` | `@Inject(at=@At("HEAD"))` | level/renderer teardown: drop the whole store |
| 6 | `net/minecraft/client/renderer/LevelRenderer.render(...)` at `@At(value="INVOKE", target="Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadTerrainBuffersToGpu()V", shift=AFTER)` | `@Inject` | render-thread point to record Meshelium staging→arena copies on the shared encoder (inside vanilla's `lock()`/`unlock()`); optional in wave 3, required by wave 4 |
| 7 | *(fallback for 1+2)* `SectionRenderDispatcher$RenderSection.addSectionBuffersToUberBuffer(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Lnet/minecraft/client/renderer/chunk/CompiledSectionMesh;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)Z` | `@Inject(at=@At("HEAD"))` | per-layer hand-off with mesh identity; MUST dedupe (spin-retry re-fires) and skip `vertexBuffer==null` (resort) |

No accessor mixins needed for wave 3: every field the taps read is `public`
(`Results.*`, `RenderSection.index`, `sectionMesh`) or arrives as a
parameter; `getSectionNode()`/`getRenderOrigin()` are public methods.

### 5.3 Threading rules

- **Build threads (FJP "background executor" workers, nproc−1 max, AND the
  render thread via compileSync):** run the re-encoder (tap 1+2). Pure CPU;
  no Vulkan calls, no vanilla-GL/VK objects; per-thread scratch buffers
  (thread-local, sized to the largest layer: bounded by pack buffer sizes —
  4 MiB vertex data ≈ 150k vertices → ≤37.5k quads → ≤2.4 MiB Meshelium
  scratch per thread).
- **Any thread under `copyLock`:** taps 3/5 (frees) and 4 (swap). Store must
  use its own lock or piggyback the fact that vanilla already serializes
  these under `copyLock`; do NOT take Meshelium locks that can be held while
  waiting on `copyLock` elsewhere (deadlock discipline: Meshelium lock is
  always innermost).
- **Render thread only:** tap 6 — encoder work (staging copies), arena
  growth, destroy-queue draining. Same one-frame-latency contract as
  vanilla: copies recorded this frame, first drawn next frame (1.6).
- Never block a worker on the render thread: if Meshelium's staging is full,
  drop-and-mark-dirty (re-encode next build) rather than spin — vanilla can
  spin because the render thread is guaranteed to drain ITS staging; Meshelium
  gets no such guarantee for its own ring in wave 3.

### 5.4 Dual-pipeline memory cost (until wave 4 cancels vanilla's terrain draws — and wave 4 only removes DRAWS; vanilla buffers persist until a later kill switch)

Vanilla side (unchanged, for reference — all from 1.2/1.3):
98 MiB staging + lazily-grown uber heaps (128 MiB vertex + 32 MiB index per
heap, per layer, first heap on first upload) + `min(nproc,
0.3·maxMemory/9,175,040)` × 8.75 MiB CPU pack buffers.

Meshelium adds:
- **GPU copy:** vanilla stores 28 B/vertex = **112 B/quad** (+ 0 index bytes
  for SOLID/CUTOUT, 6·2 B/quad translucent indices); Meshelium stores
  **64 B/quad** (16 B × 4) + 32 B/section metadata + 16 B/region records
  (architecture §2). Ratio ≈ **0.57× of vanilla's live vertex bytes**.
  Ballpark: if vanilla's vertex heaps hold V MiB of live section geometry,
  Meshelium's arena holds ≈ 0.57·V + ~2 MiB of metadata at rd 32 (25×25×24
  sections ≈ 15k × 32 B ≈ 0.5 MiB section records + region/section tables).
  Example: V = 384 MiB (three layers, heavy world) → Meshelium ≈ 220 MiB.
  Total transient peak also includes Meshelium's own staging ring (pick
  32 MiB, Nvidium's number, architecture §3).
- **CPU:** per-worker scratch ≤ 2.4 MiB × worker count; the keyed store maps
  (identity map mesh → arena handle) are negligible.
- This coexistence cost is the price of the dual phase by design; the wave-4
  "flip" only stops vanilla's renderGroup draws (frame-path Q2.6b) — vanilla
  still BUILDS and UPLOADS its buffers until a later wave decides to starve
  them, so budget for both through waves 3–7 unless SPEC says otherwise.

---

## Wave-3b implementation notes (2026-08-09) — only where reality deviated from the plan

Wave 3b shipped per Q5: taps at rows 1+2, frees at rows 3+5, pump at row 6,
re-encode on the build thread, GPU work render-thread-only, locks per 5.3.
The deviations, each with its evidence:

1. **The park between compile RETURN and the mesh ctor is a ThreadLocal,
   not a shared map.** `CompileTask.doTask` bytecode proves both hooks run
   on the SAME thread back to back (compile at ip 100, ctor at ip 153–161,
   no thread hop; compileSync is the same method on the render thread), so
   `SectionBuildTap` parks per-thread with a `Results`-identity check —
   Q3.1's "keyed by the Results object" with the key collapsed onto the
   thread. This also kills a hazard the map design had: two overlapping
   doTasks for the SAME section (a cancelled task still mid-flight beside
   its replacement) can never cross-contaminate. `Results.release()` needs
   no hook: its only call site in doTask (ip 321) is AFTER the ctor, so
   the park has always been re-keyed (or dropped) by then.
2. **Row 4 (`setSectionMesh` RETURN) is not implemented in 3b.** Nothing
   draws, so there is nothing to flip and the store validates itself via
   the counters; wave 4 adds it where it's load-bearing. Rows shipped:
   1, 2, 3, 5, 6. Row 7 (fallback) unused — the primary descriptors
   applied cleanly against the jar names (all javap-re-verified).
3. **Per-mesh GPU frees don't use the wave-2 destroy queue** (Q3.4's
   threading rule anticipated they might): arena ranges are CPU
   bookkeeping inside ONE shared VkBuffer, so a free is an epoch entry —
   `TerrainResidency` parks the address stamped with the pump frame and
   only moves it into `TerrainArena.free()` + `releasePending()` when
   `frameCounter − freeFrame ≥ 3`. Derivation against frame-path Q1.2:
   work last referenced in pump frame F is in a submission closed no later
   than frame F's end-of-frame `submit()`; with 2 submits in flight the
   CPU waits on that submission's timeline value while closing frame
   F+2's submit; the pump of frame F+3 therefore runs strictly after —
   2 in flight + 1 safety. The same constant retires the staging ring.
   `queueForDestroy` is used exactly once: whole-buffer teardown at
   `dispose()`.
4. **Backend reachability: `GpuDeviceAccessor` on `systems.GpuDevice`'s
   private `backend` field** (javap-verified) instead of frame-path row
   3's `CommandEncoder.backend()` invoker — the same pass-side-accessor
   choice wave 2 made, one hop from `RenderSystem.getDevice()` to
   `VulkanDevice.{vma(), createCommandEncoder()}`.
5. **No SHADER_DEVICE_ADDRESS in 3b — bytecode finding.** Vanilla creates
   its VMA allocator with NO flags (`VulkanBackend` bytecode: no
   `VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT`) and its
   `VkPhysicalDeviceVulkan12Features` chain enables only
   `timelineSemaphore` + `hostQueryReset` — BDA usage on this device as
   created would be invalid. The arena/region/section buffers are
   `TRANSFER_DST | STORAGE_BUFFER` (raw `vmaCreateBuffer` on vanilla's
   `vma()` handle, mirroring `VulkanGpuBuffer$Direct`'s recipe — raw
   because `VulkanConst.bufferUsageToVk` has no STORAGE mapping,
   bytecode). Wave 4 reads them as SSBOs, or grows the wave-1 device
   mixin by the `bufferDeviceAddress` feature if the pointer ABI wins.
6. **The translucent "camera snapshot" is vanilla's own sorted index
   buffer.** Instead of capturing a Vec3, the decoder walks the
   TRANSLUCENT `MeshData`'s distance-sorted index buffer (built by
   `sortQuads` against the exact camera snapshot doTask took) and emits
   the prefix in that order — first index of sorted group i, ÷4, is the
   source quad (2.3's `{0,1,2, 2,3,0}` layout). Ordering correctness is
   wave 7's on-screen validation, as planned.
7. **Material bits follow vanilla's pipeline defines, not Nvidium's
   Sodium mapping**: `pipeline/cutout_terrain` carries `ALPHA_CUTOUT =
   0.5f` and the translucent terrain pipeline `0.1f` (RenderPipelines
   bytecode) → SOLID = cutoff 0, CUTOUT = index 2, TRANSLUCENT = index 1,
   mip on for all (one mipped atlas sampler in 26.2). Nvidium had
   translucent at cutoff 0; wave 7 revisits with the translucent shader.
8. **Region layer**: range-granular dirty uploads (contiguous dirty-slot
   span + rebuilt 16-B record) instead of Nvidium's whole-8-KiB blocks;
   consequently a reclaimed region id gets its GPU block zero-filled and
   its record tombstoned via `vkCmdFillBuffer` BEFORE the copies (fills →
   full barrier → copies on the one transient command buffer) — Nvidium's
   full-block writes made that implicit. All three device buffers are
   zero-initialized at creation (§2 port note: fresh VkBuffer memory is
   undefined). Region-id budget: 2048 (32 KiB + 16 MiB buffers), ~3× the
   ≤ ~700 regions a rd-32 grid can touch (recon Q4.3's retention is
   bounded by the grid); overflow drops the section with a counter —
   and since wave 8 any per-world drop trips the coverage guard (Meshelium
   passive, vanilla draws everything — budgets can cost Meshelium a world,
   never pixels). *(Wave 10: the budget is no longer a literal — it
   scales with the configured max render distance, pinned per world
   standup; 2048 remains exact at the default 32. Formulas + table:
   docs/EXTENDED-RENDER-DISTANCE.md §5.)*
9. **Two-meshes-one-section window**: Meshelium can upload a NEW mesh
   before vanilla promotes it and releases the OLD one (promotion waits
   on vanilla's own staging). The region slot is stolen last-writer-wins;
   the old resident is marked slotless so its later free only returns its
   arena range. Vanilla's free order makes this window rare (promotion +
   release happen during `uploadTerrainBuffersToGpu`, BEFORE the pump in
   the same lock window) but not impossible when vanilla's staging lags a
   frame (ledger 1).
10. **Teardown nuance found while writing the leak test**: a
    compiled-but-never-promoted mesh at world close is NOT individually
    released by vanilla (nothing calls `releaseSectionMesh` for it; the
    wholesale uber-buffer close covers vanilla's side). The residency
    gametest quiesces the build pipeline before closing the world, and
    `dispose()` drops Meshelium's whole store either way — so nothing
    leaks, but the dispose-time "0 sections resident" assertion needs the
    quiesce to be exact.
11. **CORRECTION, taken in two steps after the first real runs
    (coordinator, 2026-08-09) — the teardown model was wrong TWICE, and
    the second wrong model was the coordinator's own.** The wave-3b brief
    assumed world close runs `releaseAllBuffers()` then `dispose()`; the
    first fix assumed `releaseAllBuffers()` alone. The bytecode-complete
    truth: **at an ordinary world close, NOTHING frees.**
    `LevelRenderer` has no `setLevel`-style teardown at all in 26.2;
    `releaseAllBuffers()` has exactly two callers —
    `invalidateCompiledGeometry(...)` (F3+A / options-class resets) and
    `resetLevelRenderData()` — and `resetLevelRenderData()` is called
    only from `LevelRenderer.close()` — and the run-log settled what
    "close" means in practice: **the LevelRenderer is per-level**, and the
    old one's `close()` runs when the NEXT level spins up (observed
    2026-08-09: "dropped with the dispatcher: 0 sections / 0 quads" +
    fresh "residency up" at each world transition), which is the only
    route to `dispose()`. The dispatcher, the ViewArea and
    EVERY compiled mesh survive at the title screen; they free lazily as
    the next world's sections reposition/rebuild into the same slots
    (Q4's retention finding — which this wave's own test then
    contradicted; the recon had it right all along). Consequences:
    (a) Meshelium, keyed on mesh identity, inherits the retention policy by
    construction — its copies are SUPPOSED to survive the title screen,
    and a store that drains at world close would be the actual bug;
    (b) the leak test asserts both halves of the real lifecycle:
    retention at title (counters still live), then `freedSections`
    advancing while a SECOND world builds (repositioning frees flowing
    through the row-3 hook); (c) arena bytes reclaim on the next world's
    first pumps (`releasePending()` is pump-driven and the pump only runs
    during level rendering); (d) the `dispose()` HEAD hook remains
    correct for shutdown and for F3+A-class resets.

## Wave-11 note (2026-08-10) — retention decouples Meshelium's lifetimes from Q3.4/Q4.3

Wave 11 (retained terrain, SPEC row 11) deliberately BREAKS the wave-3b
statement "Meshelium's copies are freed exactly when vanilla's are": with
`retainTerrain` on (the default), a DISTANCE-class free keeps the copy.
The discrimination rides this doc's own census, re-verified against the
jar on 2026-08-10:

- **`releaseSectionMesh` callers — complete set re-proven** (jar-wide
  string census over every class referencing the name: only
  `RenderSection.class` + `RenderSection$CompileTask.class`): `reset()`
  ip 30; `checkSectionMesh` ip 86 (after `setSectionMesh` ip 80);
  `CompileTask.doTask` ip 209 (empty-path, OLD mesh) and ip 343
  (cancelled-mid-copy, NEW mesh). The method is private and its nest
  holds no other call site.
- **`reset()` callers — complete set re-proven** (census over classes
  referencing both `RenderSection` and a `reset` name: LevelExtractor's
  hits are other resets — `LevelRenderState.reset`/`resetCamera`/
  `resetSampler` — not ours): `setSectionNode` ip 1 (grid reposition —
  and reset runs BEFORE the node field is overwritten, ip 4-6) and
  `ViewArea.releaseAllBuffers()` ip 28 (renderer reload / render-distance
  change / level swap). **`reset()` is therefore the exact
  slot-revocation signature**, and the new HEAD/RETURN bracket on
  `RenderSectionMixin` (a thread-local depth) classifies releases: inside
  the bracket = distance-class ⇒ RETAIN (orphan to a position-keyed map,
  keep slot/records/arena range); outside = replacement/cancel ⇒ the
  wave-3b free.
- **Rebuild ordering** (the "which lands first" question): the NEW mesh's
  ctor at doTask ip 161 precedes BOTH old-release sites (ip 209 same
  method; checkSectionMesh ip 86 runs at promotion, strictly later), so
  the tap always parks the successor before the predecessor dies. The
  old release then arrives OUTSIDE any reset ⇒ plain free; whichever of
  {old-release, Meshelium-upload-of-new} happens first, the wave-3b
  slot-steal machinery (note 9) keeps one slot owner — and a steal from a
  RETAINED owner frees the retained copy on the spot (supersede).
- **The empty-compile hole and its plug**: vanilla's empty path never
  reaches `addSectionBuffersToUberBuffer` (doTask ip 166-249) and Meshelium
  never enqueues empty results, so the slot-steal supersede cannot fire
  for a section that recompiles to NOTHING — a retained copy at that
  position would ghost. The build tap now signals
  `TerrainResidency.onSectionCompiledEmpty(pos)` from `onCompileReturn`'s
  empty/0-quad branches, which drops any retained copy there.
- **Q4.3 status**: the "no per-chunk free path" finding stands and is
  now load-bearing in the opposite direction — on multiplayer, chunk
  unload frees nothing, so retention's real trigger is slot REPOSITION
  as the player travels (plus rd drops via releaseAllBuffers). Note 11's
  per-level dispose is the retention boundary: retained copies die with
  the dispatcher (per-world/per-dimension by construction).
- **Known, accepted risk — resource reloads vs retained UVs**: an F3+T
  that RELAYOUTS the block atlas leaves retained copies sampling the old
  layout (their 15-bit UVs are baked; vanilla's reload path funnels
  through `invalidateCompiledGeometry` → releaseAllBuffers, which
  retention deliberately survives — dropping there would also kill the
  rd-change retention this wave exists for, since both events share that
  code path and are indistinguishable at the hook). In-range sections
  re-upload within the reload's rebuild storm (supersede); only
  beyond-range retained terrain can show swapped textures, until
  superseded/evicted. Same-pack reloads normally reproduce the layout;
  Nvidium avoided this only because Sodium destroyed its whole renderer
  on reload — at the price of never retaining across rd changes either.

## Wave-7 note (2026-08-09) — shopping-list row 7 is now in use

Row 7 (`addSectionBuffersToUberBuffer` HEAD) shipped in wave 7 — but as
the **resort tap**, not the compile fallback it was listed as, and with
its filter INVERTED: the hook accepts ONLY
`layer == TRANSLUCENT && vertexBuffer == null && indexBuffer != null`,
which is exactly the `ResortTransparencyTask.doTask` hand-off (ip
167-187) and nothing else. Q4.2's finding — the primary tap is
structurally blind to resorts — is the load-bearing reason this hook
exists: it is the ONE seam where vanilla's re-sorted translucent order
(the fresh distance-sorted index buffer) becomes visible to Meshelium.
Dedupe detail beyond Q3.1's warning: the spin-retry re-fires pass a
FRESH `result.byteBuffer()` view each iteration (doTask bytecode ip
179-184), so the dedupe is content-based (decoded order == applied
order), never identity-based. Consumption + permutation story:
VANILLA-FRAME-PATH.md wave-7 notes §3; the resort trigger recon
(`scheduleTranslucentSectionResort` + `TranslucencyPointOfView` =
per-axis `clamp(camSection − section, −1, 1)`, threshold = the 16-block
section grid) lives there too.

## Wave-14 note (2026-08-10) — the arena grows on demand

The first owner-hit bug of the project: the very first Vulkan-active real
session (RX 9070 XT, 16 GiB, ~15 GiB free) ended with the status header
reading "NOT RENDERING — GPU budget exceeded; passive for this world".
The mod turned itself off on a card with sixty times the memory it was
refusing to use. Root cause, cure, and the per-budget audit below.

### §14.1 — Diagnosis: which budget, with the arithmetic

Every path to passive is one of the four drop counters
(`dropsThisWorld() > 0` ⇒ `coverageGuardBlocks()`):

| Counter | Budget | Fixed at pin? | Can a real world at option ≤32 trip it? |
|---|---|---|---|
| `droppedArenaFull` | arena bytes (256 MiB standard; wave-13 formula min(1 GiB, 256·(2rd+1)²/65²) extended) | yes (waves 3b/10/13) | **YES — the culprit; arithmetic below** |
| `droppedRegionBudget` | region ids (2048 standard; max(2048, 2×touched) extended) | yes | no: live regions are grid-bounded at ⌈65/8+1⌉²×7 = **700 of 2048** (3× headroom); at rd 48/96 the pin is 2× the touchable grid (2,816/9,472 vs 1,372/4,732 touched). Retention hoarding force-evicts + REQUEUES (wave 11), never drops while anything retained exists |
| `droppedOversize` | one section's bytes vs the 32 MiB staging ring | yes (ring fixed) | no: a 16³ section is ≤4096 blocks × 6 faces = 24,576 quads = **1.5 MiB**, a 21× margin below the ring |
| `droppedEncoding` | none (exceptions: e.g. modded chunkY beyond the record's 9-bit budget) | — | not a size budget; a legitimate guard cause, unchanged |

Non-guard budgets (cannot flip passive, audited for completeness):
dispatch capacity (512 standard / maxRegions extended) overflows FAIL
OPEN (wave-5/6 mask sentinel + per-region occlusion fail-open); a full
staging ring backlogs and retries; stamps/frame-lists are sized by the
pinned maxRegions at standup. None of these moves a drop counter.

**The arena arithmetic.** One quad = 4 × 16 B vertices = 64 B; the
256 MiB standard pin holds 4,194,304 quads.

- The formula's calibration point (wave 9, plains bench, rd 32): 51 MiB
  live ≈ 835k quads over ~3,274 resident sections ≈ **255
  quads/section**, ~0.8 non-empty sections per column (4,225 columns).
- A real overworld at rd 32: hills/forests/post-1.18 cave networks give
  **2–5 non-empty sections per column** (surface relief + trees +
  exposed cave surfaces) at **400–900 quads/section** (foliage crosses,
  ore faces, water). Modest estimate: 10,000 sections × 500 quads =
  5.0M quads = **305 MiB — 1.2× the 256 MiB pin**. Forested mountains:
  15,000 × 700 = 10.5M quads = **640 MiB — 2.5× the pin**. Retention
  (default ON) reaches the high-water first, then the LIVE set alone
  exceeds capacity: force-evicts drain the horizon, the next live
  allocation fails with nothing retained left → `droppedArenaFull` →
  guard → passive. Exactly the owner's session.
- Extended pins fared no better: the rd-48 pin (571 MiB) and the 1 GiB
  cap meet real-world demands of 0.6–2 GiB (the same density argument
  over a 97×97 grid). The formula was never wrong about plains — it was
  calibrated on the one biome class that doesn't need it.

Conclusion: **stop predicting density.** No formula anchored on a bench
biome survives contact with real worldgen; the card knows how much
memory it has, and the arena can simply use it.

### §14.2 — Growth design: grow-and-copy, and why

An allocation failure in the pump's drain now walks a ladder: **grow →
evict retained (wave 11, requeue) → drop (wave 8, guard)**. The failed
upload is served by the grown arena in the same pump (the retry costs
one extra `allocQuads`); only exhausted-or-impossible growth falls
through, so a drop on arena bytes now MEANS "the ceiling itself is too
small", and the guard trip names it (§14.4).

**Mechanism — grow-and-copy** (`MesheliumTerrainGpu.growArena`): allocate
a bigger DEVICE_LOCAL buffer through vanilla's VMA, record on ONE
transient command buffer `vkCmdFillBuffer(new tail, 0)` +
`vkCmdCopyBuffer(old → new, [0, oldSize))` + full barrier, splice it via
`encoder.execute` (in submission order, so it lands strictly before the
same pump's endFrame copies — staged uploads then overwrite copied
bytes in a barrier-ordered WAW, never the reverse), swap the backing,
park the old buffer. The whole step is atomic: any Vk failure is caught
inside, the fresh buffer is destroyed, nothing observable changed, the
caller sees "cannot grow". Quad addresses and byte offsets are
IDENTICAL in the new buffer — no record, snapshot-format, or shader
change anywhere.

Alternatives rejected:

- **Multi-block paging** — the arena binds as ONE whole-buffer SSBO
  (push-descriptored per pass from the snapshot's opaque handle); paging
  turns every `terrainAddress` into a (block, offset) pair — a
  section-record format change, a shader change in all four consumers,
  and a second indirection on the hottest read path. Grow-and-copy costs
  none of that.
- **Sparse binding** — vanilla's device enables no sparse features and
  its VMA allocator is created plain (the same bytecode chain as the
  wave-3b BDA finding), so sparse residency is invalid on the device as
  created; Nvidium's own non-sparse fallback is the lineage this arena
  already ports.
- **Copy cost** honestly stated: geometric ×1.5 growth means total copy
  traffic ≤ ~2× the final size across a world's life (a 1 GiB resident
  set costs ~2 GiB of one-off device-local copies spread over ~5
  growths; tens of ms each on desktop bandwidth — one-frame hitches,
  logged per growth).

**Fence story** (the FREE_FRAME_LAG invariant, unchanged and reused):
the old buffer's last possible readers are draws recorded in the growth
frame and the growth copy itself; vanilla runs 2 submits in flight + 1
safety frame ⇒ the pump of frame F+3 may destroy a buffer parked at
frame F. `beginFrame` destroys expired parks and RE-ASSERTS the lag at
destroy time (`IllegalStateException` → error latch — a violation is a
use-after-free the `--vulkanValidation` leg would also catch);
dispatcher dispose sweeps still-parked buffers into vanilla's
deferred-destroy rotation; device-close destroys them directly
(post-waitIdle). Probe: `MesheliumTerrainGpu.arenaBuffersRetired()`.
The drawer never holds the handle across frames beyond its epoch-cached
snapshot: growth bumps `drawEpoch`, the next `drawOpaque` re-snapshots
(and the wave-12 cachedCull memo keys on the same epoch, so a replay of
commands referencing the old handle is structurally a miss).

**Growth policy** (`TerrainResidency.growArenaLocked`): target =
min(ceiling, max(1.5 × current, current + needed)), whole MiB — the
`needed` term guarantees the failing allocation fits after one step;
geometric otherwise. Initial size = 256 MiB at EVERY pin
(`meshelium.tune.arenaInitialMiB` overrides — good for iGPUs and the
growth test-leg; `meshelium.test.arenaMiB` pins initial AND ceiling for
the guard leg). Counters: `arenaGrowths`, `arenaGrowthFailures` in the
residency counters + the 5 s stats line.

### §14.3 — The ceiling comes from the device

`MesheliumScaling.arenaCeilingBytes()` = `meshelium.test.arenaMiB` ??
`meshelium.tune.arenaCeilingMiB` ?? **max(256 MiB, 50% of the largest
DEVICE_LOCAL heap)** (whole MiB), fallback 1 GiB if no probe ever ran
(defensive; unreachable today — the probe runs at every Vulkan device
creation). The probe (`MeshShaderDeviceSupport.queryDeviceLocalHeapBytes`,
`vkGetPhysicalDeviceMemoryProperties`, core 1.0, LWJGL names
javap-verified) takes the LARGEST device-local heap, not the sum —
discrete cards report the ~256 MiB host-visible BAR heap as a second
DEVICE_LOCAL heap. This is a heap's fixed SIZE, a static hardware fact —
NOT the `vmaGetHeapBudgets` usage estimate wave 10 rejected (vanilla's
allocator lacks VK_EXT_memory_budget; that rejection stands untouched).
On the 9070 XT: 16 GiB heap → ~8 GiB ceiling; the owner's world needed
well under 1 GiB.

**Integrated-GPU caveat**: UMA devices report their shared
system-memory heap as DEVICE_LOCAL, so 50% of "device memory" is 50% of
SYSTEM RAM. Accepted: VMA places the arena in that same shared memory
either way, the fraction bounds Meshelium's share of it, and the modest
256 MiB initial means small machines only ever pay for what their world
actually holds. The caps log line prints heap and ceiling at device
creation on every Vulkan boot (GL: no probe, no line — dormancy).

### §14.4 — Budgets table (wave-14 disposition of every budget)

| Budget | ≤13 size | Wave-14 | Rationale |
|---|---|---|---|
| arena bytes | fixed 256 MiB std / formula ≤1 GiB ext | **elastic: 256 MiB initial → device ceiling (~50% VRAM)** | the owner-hit trip point; §14.1–14.3 |
| region records (16 B/id) | 2048 std / 2×touched ext, pinned | unchanged, pinned | 32–148 KiB total; trip requires live regions > budget — grid-bounded to ≤50% of pin (§14.1 table) |
| section records (8 KiB/id) | follows maxRegions | unchanged, pinned | 16–74 MiB; same id budget as above — no independent trip path |
| section stamps (2×1 KiB/id) | follows maxRegions | unchanged | same argument |
| dispatch lists | 512 std / maxRegions ext | unchanged | overflow FAILS OPEN (never a drop); std-mode retained horizons can exceed 512 dispatched regions → mask-overflow fail-open = culling degradation only, counted (`maskOverflowRegions`) |
| staging ring | 32 MiB | unchanged | bounds streaming RATE; full ring backlogs, oversize margin 21× (§14.1) |

The "no budget that costs <32 MiB at maximum may flip the guard" rule
holds by audit rather than by raising sizes: the two record budgets
share one id pool whose live demand is grid-bounded at ≤50% of the pin
and whose retention overflow force-evicts-and-requeues; there is no
input a vanilla world can produce that trips them. (Raising standard
maxRegions 4× was considered and rejected: it costs 64 MiB of section
records — over the rule's own threshold — to defend against a
structurally unreachable trip.)

### §14.5 — Guard changes (honesty, not semantics)

- The guard's CONDITION is unchanged (any drop this world ⇒ passive, no
  flap-back, re-arm on a clean world load) — but an arena drop now
  requires growth exhausted-or-impossible AND retained empty, so
  reaching it means the world genuinely exceeds the ceiling.
- **The trip is named**: `TerrainResidency.guardTrip()` records
  kind + size at the FIRST drop of the world ("arena": capacity/ceiling
  MiB; "oversize": section/ring MiB; "region": ids; "encoding") —
  cleared with the drop baseline at dispose. The drawer's once-only WARN
  prints `guardTripDescription()`; the options-screen passive line shows
  the kind-specific string (4 new lang keys), e.g. "terrain memory
  4212 MiB reached its 8184 MiB ceiling; passive for this world".
- **Mid-world re-arm after successful growth: rejected.** A dropped
  section is one vanilla holds and Meshelium lost; nothing re-enqueues its
  mesh until vanilla itself rebuilds it, so "growth later succeeded"
  cannot prove coverage — a re-arm would draw holes. (It also could not
  flap: drops are monotonic — but the coverage argument is the real
  bar.) Fresh-world re-arm only, as in wave 8.

### §14.6 — Wave-14 UNVERIFIED / risks

1. **No JVM has run this code** (house rule). The growth leg, guard leg,
   ceiling probes and screenshots are the coordinator's to run; a
   `--vulkanValidation` leg is recommended once for the retire fence.
2. **Drop-beats-growth window**: none found by review — `allocQuads` is
   called exactly once per section per pump, growth is attempted
   synchronously at that site before any counter moves, and both run
   under the store lock. The one deliberate residual: a growth whose
   ceiling-clamped target still can't fit the section falls through to
   evict/drop in the SAME pump — correct (that IS exhaustion), listed
   here because it's the only path where a drop follows a successful
   growth.
3. **Use-after-free on the old arena**: bounded by the same
   FREE_FRAME_LAG argument as every other free; the explicit
   destroy-time assert + validation leg are the backstops. The known
   subtlety — the drawer's cached snapshot may hold the OLD handle for
   the growth frame itself — is safe because the old buffer is alive
   and byte-coherent until retirement (growth never writes it).
4. **iGPU shared-heap behavior** (§14.3): UNVERIFIED on real UMA
   silicon — no such hardware on this desk; the caveat is documented on
   the probe, the state, and the ceiling resolver.
5. **VMA allocation failure mid-growth** returns 0 (treated as
   exhausted) — a fragmented-VRAM card could refuse a 4 GiB buffer while
   the ceiling says 8 GiB; the guard trip then reports the CURRENT
   capacity against the ceiling, which is honest but may puzzle (the
   `arenaGrowthFailures` counter disambiguates in the stats line).

## UNVERIFIED ledger

1. **`uploadTerrainBuffersToGpu` early-break intent** — the per-layer upload
   loop breaks when a layer's vertex `uploadStagedAllocations` returns true
   (heap created/destroyed); mechanism bytecode-proven (ip 51–79), the WHY
   is interpretation (deferred work to next frame vs. bug). Wave-3 code must
   tolerate layers whose vanilla upload slips a frame.
2. **`putBlockBakedQuad` default-method dispatch** — SectionCompiler's
   lambdas `invokevirtual BufferBuilder.putBlockBakedQuad`, resolved to the
   `VertexConsumer` default method (BufferBuilder declares none — verified
   absent in javap of BufferBuilder and present in VertexConsumer). A
   BufferBuilder override cannot appear at runtime (final jar), but
   OTHER mods' mixins could add one; the chosen tap (compile RETURN) is
   immune either way.
3. **`MaterialInfo.layer()` covers every block quad** — routing proven from
   `lambda$compile$0` bytecode; whether any modded/BlockEntity-adjacent path
   can emit terrain quads outside `tesselateBlock`/`FluidRenderer.tesselate`
   inside SectionCompiler is not exhaustively proven (the compile loop shows
   only those two emitters — high confidence).
4. **Cross-product facing recovery correctness at the margins** — quad
   vertex order (winding sign, degenerate/zero-area quads from mods) is
   asserted from the QUADS topology + CW front-face convention, not from a
   runtime dump; wave-4's pixel-parity screenshot is the planned validation.
   Fallback if misclassification shows up: option (b)'s per-quad redirects.
5. **`StagingBuffer.tryAppend` thread-safety relies on `copyLock`** — the
   method itself is unsynchronized (bytecode has no monitors); every call
   site found sits under `addSectionBuffersToUberBuffer`'s copyLock, but
   "no other caller exists anywhere" rests on the census of references to
   `StagingBuffer.tryAppend` inside chunk code (`UberGpuBuffer.addAllocation`
   only), plus `StagedVertexBuffer` using its own separate StagingBuffer
   instance (not audited beyond its ctor in `RenderBuffers`).
6. **Pack-buffer 4 MiB bound as re-encode scratch bound** —
   `ByteBufferBuilder` GROWS beyond the initial `bufferSize` (vanilla
   sections can exceed 4 MiB per layer in pathological worlds);
   `MAX_VERTEX_COUNT = 16,777,215` (BufferBuilder `beginVertex` ip 8–26) is
   the true ceiling. Meshelium's scratch must grow, not assert, past 2.4 MiB.
7. **`RenderPipelines.SOLID_TERRAIN` vertex format == BLOCK** — proven
   indirectly (blockFormat fast path + BLOCK clinit); the RenderPipelines
   builder call chain itself was not dumped. One javap of `RenderPipelines`
   settles it if ever doubted; nothing in the tap depends on it (the tap
   reads `DrawState.format()` at runtime).
8. *(added by wave 3b)* **Lightmap +8 half-texel centring** — vanilla's
   UV2 shorts are texel coords 0..240 (`LightTexture.pack` = coord<<4);
   Meshelium stores `coord + 8`, landing exactly on Nvidium's [8,248] clamp
   range, on the NAMED ASSUMPTION that Sodium's encoder centred the same
   way (its source is not in the reference tree). ±1 texel of lightmap
   error at worst; wave-4 pixel parity validates
   (`VanillaMeshDecoder.readVertex`).
   **DISCHARGED by wave 4 (2026-08-09):** vanilla's own
   `include/sample_lightmap.glsl` (jar dump) samples at
   `clamp((uv2/256) + 0.5/16, 0.5/16, 15.5/16)` — the +8 IS vanilla's own
   half-texel shift, and the [8,248] clamp IS vanilla's clamp premultiplied
   into the stored byte: `vec2(stored)/256` reproduces the vanilla UV
   bit-for-bit for every legal input (VANILLA-FRAME-PATH.md wave-4 notes
   item 4).
9. *(added by wave 3b)* **The whole 3b pipeline is build-verified only on
   paper** — no gradle/client run has executed the new mixins yet (agents
   never run JVMs; the coordinator's two harness runs are the acceptance).
   Every descriptor was javap-re-verified against the jar, but "applies
   cleanly and the counters move" is a claim only
   `MesheliumTerrainResidencyTest` can make.
