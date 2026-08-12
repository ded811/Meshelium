# Nvidium Architecture Reference

**Purpose of this document:** the consolidated, citable map of Nvidium's actual source, produced from nine parallel subsystem readings of the reference clone at `misc/reference/nvidium` (plus the Alphadium community fork at `misc/reference/alphadium`). It is the factual substrate for the Meshelium SPEC — the cross-vendor Vulkan mesh-shader port. Every claim below carries a `path:line` citation into the reference tree. Items the readers could not confirm are kept marked **UNVERIFIED**. This document records and explains; design decisions belong to the SPEC.

---

## 0. Executive summary

Nvidium is a client-only Fabric mod with **zero entrypoints** (`fabric.mod.json:18`) that bolts a fully GPU-driven terrain renderer onto Sodium 0.5.x via 16 mixins (11 into Sodium internals, hard-pinned to `=0.5.9`/`=0.5.11`, `fabric.mod.json:25`). Its defining trait is a pointer ABI: every device buffer is made resident and exposed as a raw 64-bit GPU address (NV_shader_buffer_load), and one scene UBO carries ~12 such pointers — there is no binding/descriptor machinery anywhere (`RenderDevice.java` is 35 lines). Geometry is a flat pool of 16-byte packed vertices, 4 per quad, in an 80 GB sparse virtual arena; there are **no classic meshlets**.

Frame flow: each frame the CPU frustum-culls 8×4×8-section regions, uploads a front-to-back-sorted u16 region list plus the scene UBO, then the GPU runs six phases (`RenderPipeline.java:336-410`): (1) draw **last frame's** GPU-written solid indirect commands — which also primes the depth buffer; (2) rasterize region AABBs against that depth with writes disabled, marking `regionVisibility` bytes; (3) rasterize per-section AABBs, writing `sectionVisibility` bytes **and next frame's indirect command buffers**; (4) a temporal pass immediately re-draws sections that just became visible, hiding the pipeline's designed one-frame latency; (5) a query raster + async readback feeds the CPU's VRAM-eviction heuristic; (6) a compute sort maintains translucent section order. The translucent draw runs later in the frame off a reversed (back-to-front) command buffer.

The three hardest port problems:
1. **The indirect command format.** NV's 8-byte `{taskCount, firstTask}` with `firstTask = regionId<<8` is what makes `gl_WorkGroupID.x` a *global* section index in every consuming task shader. `VkDrawMeshTasksIndirectCommandEXT` is `{x,y,z}` with no firstTask — a redesign that crosses the occlusion, terrain, and translucency subsystems simultaneously.
2. **The NV mesh-shader dialect.** Mesh shaders write `gl_PrimitiveCountNV` *after* per-thread culling + subgroup-scan compaction, with early-returning overflow threads, assuming workgroup==subgroup. EXT's `SetMeshOutputsEXT` demands counts up-front in uniform control flow — every mesh shader needs structural restructuring, not renaming.
3. **The host boundary.** Nvidium consumes Sodium's entire CPU side (mesher + pluggable vertex encoder, facing buckets, section lifecycle, BFS culler, options GUI, shader preprocessor) and none of its GPU side. The Alphadium fork proves the GPU core survives host churn byte-identical while 100% of maintenance lands at this boundary — which for Meshelium on 26.2 must be rebuilt or re-hosted wholesale.

---

## 1. Top-level orchestration (init, capability gating, frame flow, lifecycle)

**Purpose.** Nvidium replaces Sodium's chunk-terrain draw path: the CPU only frustum-culls 8×4×8-section regions and uploads a scene UBO; NV mesh/task shaders do per-region and per-section occlusion culling on the GPU and write their own indirect draw-command buffers, consumed by the *next* frame's terrain draw. Everything else (meshing, block entities, matrices, fog state) still comes from Sodium/vanilla. There is no mod entrypoint at all — the entire mod is mixin-driven.

### Capability gate and global state

- `Nvidium.java:29-52` `checkSystemIsCapable()`: ALL six extensions REQUIRED in a single &&-conjunction, no optional tier: `GL_NV_mesh_shader`, `GL_NV_uniform_buffer_unified_memory`, `GL_NV_vertex_buffer_unified_memory`, `GL_NV_representative_fragment_test`, `GL_ARB_sparse_buffer`, `GL_NV_bindless_multi_draw_indirect` (lines 31-36); `IS_COMPATIBLE=result`, `IS_ENABLED=IS_COMPATIBLE` (line 51).
- `Nvidium.java:43-46`: on Linux, even when compatible, `SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER` is forced false ("driver inconsistencies") → the terrain arena uses a non-sparse fallback buffer at higher VRAM cost.
- `Nvidium.java:14-18` global flags: `IS_COMPATIBLE`, `IS_ENABLED`, `SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER`, `FORCE_DISABLE` (config-GUI toggle, `ConfigGuiBuilder.java:27`).
- Config loaded statically via `NvidiumConfig.loadOrCreate()` from `config/nvidium-config.json` (`NvidiumConfig.java:60-64`); key fields: `enable_temporal_coherence`, `automatic_memory`, `async_bfs=true` default, `region_keep_distance=32`, `render_fog`, `translucency_sorting_level` (`NvidiumConfig.java:17-29`).
- Capability check fires exactly once: `MixinWindow.java:15-18` injects immediately AFTER `GL.createCapabilities()` in vanilla `Window.<init>` — before any world exists. The Vulkan-port equivalent is a device-feature query at device selection.
- The enable decision is TWO-stage: `IS_COMPATIBLE` fixed at window creation; `IS_ENABLED` recomputed at every `RenderSectionManager` construction as `!FORCE_DISABLE && IS_COMPATIBLE && IrisCheck.checkIrisShouldDisable()` (`MixinRenderSectionManager.java:46-49`). Toggling never happens mid-world — only across world-renderer rebuilds (world change, render-distance change, F3+A). **Keep this invariant: no live pipeline swap.**

### Per-world renderer facade (`NvidiumWorldRenderer.java`)

- Ctor (`:45-59`): frames-in-flight = Sodium `advanced.cpuRenderAheadLimit+1` (line 46); 32 MB `UploadingBufferStream` (line 48); 8 MB `DownloadTaskStream` sized by frames (line 50); `SectionManager` gets `max_geometry_memory` MB and `NvidiumCompactChunkVertex.STRIDE` (line 54); `RenderPipeline` built over device+streams+sectionManager (line 55).
- `renderFrame` (`:80-91`): delegates to pipeline, then evicts regions in a loop while terrain-arena used MB > (budget−100) via `renderPipeline.removeARegion()`; resamples VRAM budget every 60 s (lines 87-90), only on the sparse path.
- `update_allowed_memory` (`:122-130`): automatic mode queries `GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX` (NVX extension, line 124), subtracts 1 GB, clamps to min 2048 MB; else fixed config value.
- `delete()` (`:65-74`): full GPU-resource teardown (uploadStream, downloadStream, renderPipeline, asyncChunkTracker, sectionManager) on world-renderer destroy.
- Async-BFS tracker surface (`:132-165`): `update(camera,viewport,frame,spectator)`, `getAsyncFrameId`, `getSectionsWithEntities`, `getAnimatedSpriteSet` — all null-guarded, tracker exists only when `config.async_bfs`.
- `setTransformation`/`setOrigin` (`:167-173`) forward to pipeline — the api0 per-region transform feature.

### Frame orchestrator (`RenderPipeline.java`)

Buffers created in ctor (`:104-116`), all device-only mapped (GPU-address addressable): `sceneUniform` = SCENE_SIZE + maxRegions*2 (region index list appended at tail), `regionVisibility` (1 B/region), `sectionVisibility` (256 B/region = 1 B/section), `terrainCommandBuffer` (8 B/region), `translucencyCommandBuffer` (8 B/region), `regionSortingList` (2 B/region), `transformationArray` (64 B × MAX_TRANSFORMATION_COUNT), `originOffsetArray` (8 B each), `statisticsBuffer` (16 B). `SCENE_SIZE = alignUp(...,2)` formula at `:63`; layout written at `:245-302`.

`renderFrame` order:
- (a) early-out if 0 regions (`:164`);
- (b) CPU loop over all regions: distance eviction beyond `region_keep_distance+4` (`:198-201`); frustum test `rm.isRegionVisible` (`:203`); visible regions inserted into an `IntAVLTreeSet` keyed `(distance<<16)|regionId` for front-to-back order (`:206`); camera-axis regions queued for translucency sort (`:210-212`); regions leaving the frustum get their 256-byte `sectionVisibility` slice zeroed via `nglClearNamedBufferSubData` when temporal coherence is on (`:215-221`);
- (c) region-id list uploaded to `sceneUniform+SCENE_SIZE` (`:228-234`); (d) scene UBO write (`:242-302`); (e) `regionsToSort` upload (`:308-317`); (f) `sectionManager.commitChanges` + `uploadStream.commit` + `TickableManager.TickAll` (`:319-322`).

GPU phases within `renderFrame`: enable 4 NV unified-memory client states + `glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, sceneUniform addr)` (`:329-334`); **PHASE 1** `terrainRasterizer.raster(prevRegionCount, terrainCommandBuffer addr)` — draws solid terrain using LAST frame's GPU-written commands, then `GL_FRAMEBUFFER_BARRIER_BIT` (`:336-340`); **PHASE 2** `regionRasterizer.raster(visibleRegions)` with depth LEQUAL, depthMask=false, colorMask=false, `GL_REPRESENTATIVE_FRAGMENT_TEST_NV` enabled (`:342-364`); **PHASE 3** `sectionRasterizer.raster(visibleRegions)` — writes sectionVisibility + both command buffers for next frame (`:375-381`), then `prevRegionCount=visibleRegions` (`:383`); **PHASE 4** `temporalRasterizer.raster` (only `enable_temporal_coherence`) after `GL_COMMAND_BARRIER_BIT` — draws sections that just became visible, hiding the 1-frame latency (`:386-389`); **PHASE 5** `regionVisibilityTracking.computeVisibility` — rep-frag-test query draw + async readback of regionVisibility for the eviction heuristic (`:392-402`); **PHASE 6** `regionSectionSorter.dispatch(regionSortSize)` compute sort of translucent order (`:406-410`); restore state (`:412-417`).

`renderTranslucent()` (`:443-489`) runs later from Sodium's TRANSLUCENT pass: rebinds unified state + scene UBO (`:446-451`), blend SRC_ALPHA/ONE_MINUS_SRC_ALPHA (`:456-457`), `translucencyTerrainRasterizer.raster(prevRegionCount, translucencyCommandBuffer addr)` (`:458`) — comment at `:443-444`: translucency "hijacks the unassigned indirect command dispatch"; statistics download + zero-fill at `:472-488`.

`reloadShaders()` (`:535-550`) deletes/reconstructs all six phase objects; `compiledForFog` re-latched from `config.render_fog` (`:536`) — **the fog toggle changes the UBO layout** (inverse-MVP present only when fog compiled, `:251-257`). `removeARegion()` (`:434-436`) picks the victim via `RegionVisibilityTracker.findMostLikelyLeastSeenRegion` (`RegionVisibilityTracker.java:68-81`: only regions with frustum count>200 eligible; least-recently-visible wins). Known-bug comment `:160-161`: regions leaving the frustum must have visibility cleared or stale "visible last frame" state causes artifacts — the port must replicate this clear.

### Phase dispatchers (`renderers/`)

- `PrimaryTerrainRasterizer.java:23-26,44-58`: task+mesh+frag (`terrain/task.glsl`, `terrain/mesh.glsl`, `terrain/frag.frag`); binds block atlas (`textures/atlas/blocks.png` via TextureManager, `:47`) and lightmap (via `LightMapAccessor` mixin, `:48`); draws via `glBufferAddressRangeNV(GL_DRAW_INDIRECT_ADDRESS_NV, commandAddr, regionCount*8)` + `glMultiDrawMeshTasksIndirectNV(0, regionCount, 0)`.
- `RegionRasterizer.java:12-21`: mesh+frag only (`occlusion/region_raster`), non-indirect `glDrawMeshTasksNV(0, regionCount)`.
- `SectionRasterizer.java:11-21`: task+mesh+frag (`occlusion/section_raster`), `glDrawMeshTasksNV(0, regionCount)`; its task shader writes the translucency command buffer in reverse order (comment `RenderPipeline.java:204-206`).
- `TemporalTerrainRasterizer.java:23-26,39-54`: same terrain mesh/frag with `terrain/temporal_task.glsl`; indirect off terrainCommandBuffer.
- `TranslucentTerrainRasterizer.java:26-30,51-64`: `terrain/translucent/{task,mesh}.glsl` + frag with `TRANSLUCENT_PASS`; indirect off translucencyCommandBuffer.
- `SortRegionSectionPhase.java:22-31`: compute `sorting/region_section_sorter.comp`, `glDispatchCompute(sortingRegionCount,1,1)`.
- `RegionVisibilityTracker.java:19-56`: mesh+frag query shader (`occlusion/queries/region`); after draw + SSBO barrier, `DownloadTaskStream` async-reads regionCount bytes; frustum[]/visible[] counters feed `findMostLikelyLeastSeenRegion` (`:68-81`).

### Data formats (verbatim)

**Scene uniform** (bound whole-frame at UBO binding 0 via GPU address; region index list appended at buffer tail) — `RenderPipeline.java:63` (SCENE_SIZE), `:242-302` (writes), `:228-234` (tail region list), `:334/:451` (binding):

> offset 0: mat4 MVP = projection*modelView*translate(-subchunkDelta) (64 B); [only if compiledForFog] +64: mat4 inverse(projection*modelView) (64 B — UBO LAYOUT CHANGES with render_fog config, RenderPipeline.java:251-257); then ivec4 cameraChunkPos (16 B); vec4 negated subchunk offset (16 B); vec4 fogColor (16 B); then 12 x u64 GPU addresses in order: regionIndexList (sceneUniform+SCENE_SIZE), regionBuffer, sectionBuffer, regionVisibility, sectionVisibility, terrainCommandBuffer, translucencyCommandBuffer, regionSortingList, terrainArena geometry, transformationArray, originOffsetArray, statisticsBuffer (96 B); then f32 screenWidth/2, f32 screenHeight/2, f32 fogStart, f32 fogEnd, i32 fogShapeId (20 B); u16 visibleRegionCount; u8 frameId (wrapping byte counter). SCENE_SIZE = alignUp(64+16+16+4+16+16+64+12+3+4+8+8+64, 2) = 296 with fog. Tail: visibleRegions x u16 region ids, sorted ascending by (distance<<16)|regionId for front-to-back.

**Indirect terrain / translucency command buffers** (GPU-written by section_raster task shader, GPU-consumed next frame) — `RenderPipeline.java:107-108` (alloc), `PrimaryTerrainRasterizer.java:55-56` (consume):

> One 8-byte NV_mesh_shader indirect command per region: {uint32 taskCount, uint32 firstTask} consumed by glMultiDrawMeshTasksIndirectNV with stride 0 (tightly packed). Draw count = prevRegionCount (CPU-side, last frame's visible count). Translucency buffer written in reverse region order by the section task shader (comment RenderPipeline.java:204-206) for back-to-front blending. EXT mesh shader indirect command is {uint32 x,y,z} 12 B — port must re-stride.

**Visibility buffers** — `RenderPipeline.java:105-106` (alloc), `:218` (clear slice), `RegionVisibilityTracker.java:44-55` (readback):

> regionVisibility: 1 byte per region (maxRegions total), 1=visible, written by region_raster/query fragment shaders, read back asynchronously to CPU. sectionVisibility: 256 bytes per region (1 byte per section, region = 256 sections), indexed regionId<<8; a region's slice is zeroed with glClearNamedBufferSubData when it leaves the frustum (temporal coherence bookkeeping).

**Packed region origin** (api0 transform feature) — `RenderPipeline.java:143-154`:

> u64: bits 0-24 x (25 bits, masked 0x1ffffff), bits 25-49 z (25 bits), bits 50-63 y (14 bits, masked 0x3fff); one per transformation id, MAX_TRANSFORMATION_COUNT entries; transformationArray holds column-major mat4 (64 B) per id, initialized to identity (RenderPipeline.java:120-128).

**Statistics buffer** — `RenderPipeline.java:116` (alloc 16 B), `:472-488` (download+reset):

> 4 x u32: [0]=regionCount, [4]=sectionCount, [8]=quadCount (GPU-atomically incremented by shaders when statistics_level high enough); zeroed each frame via a 16-byte upload-stream write because 'nvidia not following spec' broke glClearNamedBufferSubData for this case (comment RenderPipeline.java:484).

**Region sorting list** — `RenderPipeline.java:109` (alloc maxRegions*2), `:308-317` (upload), `SortRegionSectionPhase.java:29-32` (consume):

> regionSortSize x u16 region ids, uploaded when regions cross a camera axis or a region-sort is enqueued via API; consumed by region_section_sorter.comp with one workgroup per region (glDispatchCompute(regionSortSize,1,1)).

### Port notes

- Capability gating is all-or-nothing; the only degraded mode is the Linux non-sparse fallback. Meshelium's hard requirements reduce to VK_EXT_mesh_shader + bufferDeviceAddress; sparse and memory-budget both have precedented fallbacks inside Nvidium's own design.
- The pipeline is one frame latent by design: frame N's PHASE 1 consumes commands written by frame N−1's PHASE 3; `prevRegionCount` carries the count across frames (`:336-340,383`). `renderTranslucent` also draws with `prevRegionCount` (`:458`). Any Vulkan port must preserve this cross-frame buffer dependency with indirect-read barriers.
- Occlusion is raster-based, not query-based. The rep-frag test is purely a perf hint (note at `:342`: requires depthMask=false) — a cross-vendor port can drop it (slower) or REDESIGN to Hi-Z compute culling.
- No resize handling exists anywhere: no framebuffer-sized resources are owned; screen half-dimensions are read fresh each frame (`:183-184, 289-291`). Lifecycle events are exactly: window init (capability check), RenderSectionManager init/destroy (attach/detach), and options-GUI-driven `reloadShaders` (keeps all buffers, `:535-550`).
- Fog config changes the UBO layout and requires shader reload — a port should use a specialization constant or always include the matrix.
- Overdraw ordering is CPU-guaranteed: front-to-back region upload keyed `(distance<<16)|id` (`:206`); translucency relies on the same sorted list reversed. Keep the 16-bit id packing limit (max 65536 regions; regionMap is `short[]`).
- **UNVERIFIED:** the exact semantics of "hijacks the unassigned indirect command dispatch" for translucency (comments `RenderPipeline.java:443-444`, `TranslucentTerrainRasterizer.java:49-50`, dead `+8*6` offset comment at `:62`) — resolved in practice by the shader reading (§6), but the stale comments should not be trusted.
- **UNVERIFIED:** SCENE_SIZE arithmetic (`:63`) sums to 295 → alignUp=296 and field writes at `:245-302` total 295 bytes with fog — consistent, but not verified against the GLSL-side struct declaration.

---

## 2. Terrain data managers (`me.cortex.nvidium.managers`)

**Purpose.** The CPU side of the GPU-driven renderer: owns every persistent GPU data structure the task/mesh shaders consume. `SectionManager` ingests Sodium chunk-build results, stores repacked quads in a giant arena, writes a 32-byte metadata record per section. `RegionManager` groups sections into 8×4×8 regions (16-byte packed record each), handles id allocation/compaction, batches whole-region uploads. `RegionVisibilityTracker` runs a GPU occlusion query + async readback to pick least-seen regions for eviction. `AsyncOcclusionTracker` runs Sodium's BFS on a background thread purely to schedule rebuilds. The CPU's only per-frame work is uploading a sorted list of frustum-visible region ids.

### SectionManager (`managers/SectionManager.java`)

- `SECTION_SIZE = 32` bytes per GPU section record (`:24`); `maxRegions` hardcoded 50_000; RegionManager created with `maxSections = maxRegions*200` = 10,000,000 (`:40,46`).
- Two CPU maps: `section2id` (packed ChunkSectionPos long → section handle) and `section2terrain` (→ quad address), default −1 (`:29-30,48-49`).
- Upload flow (`uploadChunkBuildResult`, `:52-123`): 0-quad result deletes the section (`:58-61`); terrain allocation reused only when quad count is EXACTLY equal (`canReuse`, `:66-71`; `BufferArena.java:87-89`), else free+realloc; `allocQuads` returning `SegmentedManager.SIZE_LIMIT` (−1) logs "Terrain arena critically out of memory" and deletes the section (`:77-85`); geometry memCopy'd straight into the mapped staging pointer from `terrainAreana.upload` (`:89-90`).
- Section id allocated lazily via `regionManager.allocateSection` on first upload (`:96-99`). 32-byte metadata written through the pointer from `regionManager.setSectionData`: 16-byte header Vector4i (`:110-114`) + 4 ints of packed geometry offsets from `output.offsets()[0..7]` (`:118-122`).
- Hide-bit API (`setHideBit`, `:125-146`): `hiddenSectionKeys` persists across section reloads; bit 17 of header.y patched in place, marking region dirty (`:143-145`).
- `deleteSection` (`:152-162`); `removeRegionById` iterates the full 8×4×8 (`:177-190`); `commitChanges` delegates to regionManager (`:169-171`).

### RegionManager (`managers/RegionManager.java`)

- Region = 8×4×8 sections (comment `:18`; shifts >>3,>>2,>>3 at `:217`); `META_SIZE = 16` B/region (`:24`); `TOTAL_SECTION_META_SIZE = 32*256 = 8192` B/region (`:26`).
- `regionBuffer` = maxRegions*16 B; `sectionBuffer` = maxSections*32 B (`:45-46`) — 800 KB + 320 MB of GPU VA, device-only mapped (resident with GPU address).
- Region ids from `IdProvider` (lowest-free-id reuse, tail shrink — `IdProvider.java:10-24`); `regionMap`: regionKey long → id (`:34-35, 218`).
- `allocateSection`: `posKey = (y&3)<<6 | (x&7) | (z&7)<<3`; `sectionId = region.count++`; maintains `pos2id`/`id2pos[256]`; returns `posKey | regionId<<8` (`:216-241`).
- `setSectionData` translates posKey → compacted id via `pos2id`, returns pointer into the region's CPU-side 8 KB malloc'd mirror (`nmemAlloc 8*4*8*32`, `:354`), marks region dirty (`:140-149`).
- `removeSection` does swap-remove compaction: zeroes the slot, moves the last compacted id's 32-byte record into the hole, and REWRITES bits 18-25 of the moved record's header.y to the new compacted id (`:151-198`, patch at `:191-195`; FIXME at `:187-189`).
- Region freed when count hits 0 (`:202-209`); `commitChanges` then memSets its 16-byte record to −1 and zeroes its 8 KB section block on GPU unless a new region took the id (`:66-79`).
- `commitChanges` drains dirtyRegions: per dirty region uploads full 16-byte metadata (rebuilt by `setRegionMetadata` from live pos2id occupancy: min/max AABB + lastIdx, `:95-126`) plus the FULL 8 KB section block (`:82-88`), then fires `regionUploadCallback(regionId)` → translucency re-sort (`:90`).
- `MAX_TRANSFORMATION_COUNT = 1024` (10 bits) per-region transform ids; ids remembered per region key even before/after the region exists (`:20-21, 222-223, 317-334`).
- Per-frame CPU cull helpers: `isRegionVisible` (frustum, full 128×64×128 AABB, `:267-274`), `distance` (`:276-284`), `withinSquare` (`:286-291`), `isRegionInACameraAxis` (`:293-300`).

### RegionVisibilityTracker / AsyncOcclusionTracker

- `RegionVisibilityTracker` owns its own mesh+frag shader `occlusion/queries/region` (`:19-22`), `glDrawMeshTasksNV(0, regionCount)` + SSBO barrier (`:42-43`); reuses the frame's regionVisibility buffer as query output ("kind of evil", comment `:38`); downloads regionCount bytes via `DownloadTaskStream` with a callback firing frames later (`:44-55`); `frustum[]`/`visible[]` per region (`:45-53`); `findMostLikelyLeastSeenRegion` requires `frustum[i] > 200` samples, picks oldest `visible[i]` (`:68-81`); `resetRegion(id)` zeroes counters (`:63-66`).
- `AsyncOcclusionTracker`: dedicated MAX_PRIORITY "Cull thread" driven by a semaphore capped at 5 frames ahead (`:49-54, 137-139`); runs `occlusionCuller.findVisible(visitor, viewport, renderDistance*16, useOcclusionCulling, frame)` per permit (`:104-111`); visitor enqueues rebuilds once via injected `isSeen`/`isSubmittedRebuild` flags (`:74-81`), collects block-entity sections within 33 chunks (`:90-92`), animated sprites within 33 chunks (`:94-100`); results handed over via AtomicReference; `update()` drains into per-ChunkUpdateType queues respecting `getMaximumQueueSize` (`:132-158`). **This thread produces NO GPU-visibility data** — it only schedules rebuilds/block-entities/sprites. Pure CPU, fully portable.

### Data formats (verbatim)

**GPU Section metadata record** (SECTION_SIZE = 32 bytes; sectionBuffer indexed by regionId*256 + compactedSectionId) — written `SectionManager.java:102-122` via `RegionManager.setSectionData` (`RegionManager.java:140-149`), uploaded whole-region in `commitChanges` (`:85-88`); read `terrain/task.glsl:39-51`, `terrain/translucent/task.glsl:40-67`, `occlusion/section_raster/mesh.glsl:55-60`:

> Bytes 0-15 'header' (ivec4), written as Vector4i at SectionManager.java:110-114: header.x = chunkX<<8 | geomSizeX<<4 | geomMinX; header.y = (chunkY&0x1FF)<<8 | geomSizeY<<4 | geomMinY | hideBit<<17 | compactedSectionRefId<<18 (bits 18-25; comment at SectionManager.java:108-109 says bits 18->26 hold section id for translucency sorting, 26->32 free); header.z = chunkZ<<8 | geomSizeZ<<4 | geomMinZ; header.w = terrainAddress (quad-granularity offset into terrain arena). chunkY is 9-bit signed (sign-extended on GPU, terrain/task.glsl:41-43). geomMin/geomSize are the 4-bit block-granularity AABB of actual geometry from RepackagedSectionOutput.min/size. Bytes 16-31 'renderRanges' (ivec4), written at SectionManager.java:118-122: int i = offsets[2i] | offsets[2i+1]<<16, i.e. x = cnt[facing0] | cnt[facing1]<<16, y = cnt[facing2] | cnt[facing3]<<16, z = cnt[facing4] | cnt[facing5]<<16, w = cnt[UNASSIGNED] | translucentQuadCount<<16. GLSL mirror struct: scene.glsl:4-12. All-zero record = empty slot (sectionEmpty masks header.y &= ~(0x1FF<<17) first, scene.glsl:39-42).

**GPU Region metadata record** (META_SIZE = 16 bytes = 2× uint64; regionBuffer indexed by regionId) — built `RegionManager.java:95-126`, written `:82-83`; read `occlusion/region_raster/mesh.glsl:42-58`, `occlusion/section_raster/task.glsl:50-59`, `terrain/task.glsl:45`:

> uint64 A: bits 62-63 = sizeY (maxY-minY of occupied section positions, 0-3); bits 59-61 = sizeX (0-7); bits 56-58 = sizeZ (0-7); bits 48-55 = lastIdx (highest occupied packed position index 0-255, GPU calls it 'count' and iterates 0..lastIdx inclusive); bits 24-47 = absolute min section X, 24-bit ( = rx*8+minX, masked &0xFFFFFF); bits 0-23 = absolute min section Y, 24-bit ( = ry*4+minY). uint64 B: bits 40-63 = absolute min section Z, 24-bit ( = rz*8+minZ); bits 30-39 = transformationId (10 bits, MAX_TRANSFORMATION_SIZE_BITS=10 -> max 1024, RegionManager.java:20-21,123). A == all-ones (memSet -1, RegionManager.java:72-73) marks a deleted region slot; GPU checks data.a == uint64_t(-1) (occlusion/region_raster/mesh.glsl:46). GLSL decoders: unpackRegionSize/unpackRegionTransformId/unpackRegionPosition/unpackRegionCount at scene.glsl:19-37 (positions sign-extended from 24 bits).

**CPU section handle** — `RegionManager.java:216-241`; consumed `SectionManager.java:96-102,140-145,152-161`:

> handle = posKey | (regionId << 8), where posKey = (sectionY&3)<<6 | (sectionZ&7)<<3 | (sectionX&7) (RegionManager.java:227,240). IMPORTANT: the low 8 bits are the POSITION within the 8x4x8 region, not the GPU slot; setSectionData/getSectionRefId translate posKey -> compacted id via region.pos2id (RegionManager.java:128-135,140-149). GPU-side global section index = regionId<<8 | compactedId.

**RepackagedSectionOutput offsets[8]** (quad-count table feeding renderRanges) — `SodiumResultCompatibility.java:63-225`; `RepackagedSectionOutput.java:7-11`; consumed `terrain/task_common.glsl:30-87` and `terrain/translucent/task.glsl:67`:

> Geometry buffer layout per section: translucent quads first (pre-sorted back-to-front on build thread, SodiumResultCompatibility.java:89-164), then for each of Sodium's 7 ModelQuadFacing buckets (i=0..6) solid+cutout quads. offsets[i] (i=0..6) = quad COUNT in facing bucket i (delta, not absolute; SodiumResultCompatibility.java:219); offsets[7] = translucent quad count = absolute start of the facing buckets (SodiumResultCompatibility.java:166). GPU walks them cumulatively: fr starts at (ranges.w>>16)&0xFFFF and adds each 16-bit count, gating each facing bucket by camera-relative chunk sign (task_common.glsl:30-87: bucket0 drawn if relChunk.x<=0, bucket1 if y<=0, bucket2 if z<=0, bucket3 if x>=0, bucket4 if y>=0, bucket5 if z>=0, bucket6 always).

**Terrain quad geometry (NvidiumCompactChunkVertex)** — `NvidiumCompactChunkVertex.java`; uploaded via `SectionManager.java:89-90`:

> 16 bytes/vertex, 4 vertices/quad = 64 bytes/quad (STRIDE=16, NvidiumCompactChunkVertex.java:18; BufferArena converts quad address to bytes via addr*4*vertexFormatSize, BufferArena.java:41,50,55). Vertex: int0 = posX16 | posY16<<16; int1 = posZ16 | materialBits8<<16 | blockLight8<<24; int2 = colorRGB24 (pre-multiplied by alpha-brightness, alpha zeroed) | skyLight8<<24; int3 = u16 | v16<<16. Position quantization: 16-bit over [-8,24) block range (POSITION_MAX_VALUE=65536, MODEL_ORIGIN=8, MODEL_RANGE=32); UV: 15-bit (TEXTURE_MAX_VALUE=32768) (NvidiumCompactChunkVertex.java:21-28,53-96). Terrain arena quad address 0 is reserved (BufferArena.java:30-31).

**terrainCommandBuffer / translucencyCommandBuffer entry** — `occlusion/section_raster/task.glsl:33-66`; pointed to from SceneData (`scene.glsl:64-66`):

> uvec2 { taskCount = lastIdx+1, firstTask = regionId<<8 } written per visible region by the section-raster task shader (occlusion/section_raster/task.glsl:63-66; zeroed uvec2(0) when region invisible, lines 38-42). This is the GL_NV_mesh_shader DrawMeshTasksIndirectCommandNV {count, first} layout; translucency buffer written in reverse region order (transCmdIdx = regionCount-1-workGroupID, line 35) for back-to-front region draw.

**Region key and geometry** — `RegionManager.java:18,216-217,267-274`; `SectionManager.java:177-190`:

> Region = 8x4x8 sections = 128x64x128 blocks (comment RegionManager.java:18). regionKey = ChunkSectionPos.asLong(sectionX>>3, sectionY>>2, sectionZ>>3) (RegionManager.java:217). Frustum AABB test uses full region extent: center (rx<<7)+64, (ry<<6)+32, (rz<<7)+64 with half-extents 64/32/64 blocks (RegionManager.java:267-274).

**Per-frame visible-region index list** — `RenderPipeline.java:186-240`; consumed `occlusion/region_raster/mesh.glsl:42`, `occlusion/section_raster/task.glsl:50`:

> uint16[visibleRegions], region ids sorted ascending by (manhattan-ish distance<<16 | regionId) for front-to-back overdraw order; uploaded each frame to sceneUniform+SCENE_SIZE and pointed to by the regionIndicies GPU pointer in SceneData (RenderPipeline.java:195-235,264; scene.glsl:58, regionCount uint16 at scene.glsl:89).

### Port notes

- Hierarchy: section (16³ blocks) → region of 8×4×8 = 256 sections. CPU handle = `regionId<<8 | positionKey`; GPU slot = `regionId<<8 | compactedId`; `pos2id`/`id2pos` translate. Regions exist only while ≥1 built section; ids and GPU slots recycled immediately on emptying.
- Update flow: build worker repackages → render thread `uploadChunkBuildResult` copies quads into staging + writes 32-byte record into the region's CPU mirror → `commitChanges` once per frame uploads each dirty region as ONE 16-byte record + ONE 8 KB block (whole-region batching deliberate, comment `RegionManager.java:352-353`) → `uploadStream.commit()` executes staging→device copies (`RenderPipeline.java:319-320`).
- The GPU "count" field is NOT the section count: it is `lastIdx` = highest occupied POSITION index. The section-raster task dispatches `lastIdx+1` mesh workgroups over compacted slots and relies on (a) compacted ids ≤ lastIdx and (b) trailing empty slots being zeroed so `sectionEmpty()` skips them. Preserve both invariants or switch the field to a real count.
- **Buffer sizing inconsistency to fix in the port:** sectionBuffer = maxSections*32 = 320 MB (maxSections = maxRegions*200, `SectionManager.java:46`) but addressing is `region.id * 8192` bytes, so only 39,062 region ids fit while maxRegions = 50,000. Nothing guards overflow — size sectionBuffer as maxRegions*8192 (409.6 MB) or virtually allocate.
- Deleted-region tombstones: region record −1 (`a == ~0`) and its 8 KB block zeroed, ONLY if a new region hasn't claimed the id (`RegionManager.java:66-79`); shaders check `data.a == uint64_t(-1)`. Keep the exact tombstone values.
- Swap-remove compaction rewrites the moved section's self-referential ref id (bits 18-25) in the CPU mirror; the translucent task shader depends on that ref id for sorted-order indirection. Unresolved FIXME at `RegionManager.java:187-189`.
- Coordinate budgets baked in: chunkY 9-bit signed (±256 sections), region min coords 24-bit signed section coords, region-local geometry AABB 4 bits each. MC 26.2 world heights fit, but re-check before reusing bit budgets.
- Eviction: (1) `region_keep_distance` square check (`RenderPipeline.java:198-201`); (2) memory pressure via the query readback ranking, >200 frustum samples required. The readback is a regionCount-byte async DMA — trivially a Vulkan buffer copy + fence-delayed callback.
- Hide-bit (header.y bit 17) is honored in the section occlusion raster (`section_raster/mesh.glsl:60`), preventing the visibility byte from ever being set. Tracked CPU-side in `hiddenSectionKeys` so it survives rebuilds.
- `commitChanges` MUST run before the frame's culling/draw passes and before `uploadStream.commit` (`RenderPipeline.java:319-320`); preserve ordering in the Vulkan frame graph (staging copies → barrier → task/mesh passes).
- **UNVERIFIED:** the exact Sodium ModelQuadFacing ordinal→direction mapping behind offsets[0..6] (deduced POS_X,POS_Y,POS_Z,NEG_X,NEG_Y,NEG_Z,UNASSIGNED from camera-side gating signs in `task_common.glsl:39-78`; Sodium enum source not in this repo).
- **UNVERIFIED:** whether `createDeviceOnlyMappedBuffer` zero-initializes the region/section buffers at creation (likely never matters given the full-block-write discipline, but a Vulkan port should explicitly zero-init).

---

## 3. GL abstraction + memory management (`gl/`, `util/`)

**Purpose.** A deliberately minimal GPU abstraction: buffer wrappers whose defining trait is that every device buffer exposes a raw 64-bit GPU virtual address instead of bind points, plus a persistent-mapped staging system (`UploadingBufferStream`/`DownloadTaskStream`) that moves chunk geometry to an 80 GB sparse virtual arena with fence-based per-frame retirement. It exists so the rest of the mod can treat GPU memory as a pointer-addressable heap. The design maps unusually well to Vulkan 1.2+ (buffer_device_address, timeline semaphores); sparse residency and the fenceless readback are the two redesign spots.

### Key mechanics

- `RenderDevice.java` — the entire device abstraction is a 35-line factory + 4 raw GL passthroughs (`flush`=glFlushMappedNamedBufferRange, `barrier`=glMemoryBarrier, `copyBuffer`=glCopyNamedBufferSubData; `:11-34`). No command-buffer or state abstraction anywhere. The catch for Vulkan: these calls execute immediately in the implicit GL stream; Vulkan needs them recorded into the frame command buffer.
- `PersistentClientMappedBuffer.java` — `glNamedBufferStorage(id, size, PERSISTENT|CLIENT_STORAGE|WRITE)` (`:19`); mapped once forever with `UNSYNCHRONIZED|FLUSH_EXPLICIT|WRITE` returning raw pointer `addr` (`:20`). No `GL_MAP_READ_BIT`, not COHERENT — yet **DownloadTaskStream READS through this write-only mapping** (`DownloadTaskStream.java:48`), technically out-of-spec GL that the NV driver tolerates.
- `DeviceOnlyMappedBuffer.java` — `glNamedBufferStorage(id, size, 0)`; NV_shader_buffer_load: `glGetNamedBufferParameterui64vNV(GL_BUFFER_GPU_ADDRESS_NV)` then `glMakeNamedBufferResidentNV(GL_READ_WRITE)`; addr==0 is fatal (`:18-25`); `delete()` must un-resident first (`:29-33`). Vulkan equivalent is exact and cross-vendor: DEVICE_LOCAL + SHADER_DEVICE_ADDRESS, residency implicit.
- `PersistentSparseAddressableBuffer.java` — `glNamedBufferStorage(id, size, GL_SPARSE_STORAGE_BIT_ARB)` (`:37`); PAGE_SIZE = 1<<20, chosen because the NV driver fragments with smaller pages (`:26-29`) — driver folklore, not spec. Page commit/decommit via `glBufferPageCommitmentARB` through a legacy GL_ARRAY_BUFFER bind (`:47-50`), refcounted per page in an Int2IntOpenHashMap (`:52-71`). **Latent bug, do not copy:** constructor aligns `this.size = alignUp(size, PAGE_SIZE)` but passes the UNALIGNED size to glNamedBufferStorage (`:36-37`); benign only because the sole caller passes page-aligned 80000000000L.
- `GlFence.java` — glFenceSync at construction (`:10`); `signaled()` polls glClientWaitSync(timeout=0), caches (`:13-24`). Vulkan: timeline semaphore is the cleaner match (one fence per frame FIFO).
- `TrackedObject.java` — Cleaner-based leak detector (`:35-56`), double-free guard (`:13-19`). 100% portable Java.
- `Shader.java` — source-string → compiled+linked GL program; `IShaderProcessor.process(type, source)` hook for #define injection (`:45-48`); monolithic glUseProgram binding (`:25-27`). Vulkan REDESIGN: GLSL→SPIR-V + VkPipeline (or shader objects); keep the IShaderProcessor text pass.
- `ShaderType.java:11-15` — MESH=GL_MESH_SHADER_NV, TASK=GL_TASK_SHADER_NV; direct enum swap to VK_SHADER_STAGE_MESH/TASK_BIT_EXT.
- `DepthOnlyFrameBuffer.java:19-37` — the ONLY file in images/: D32F single-mip depth FBO, bound via GlStateManager to keep vanilla's state cache honest. Vulkan: dynamic rendering with a depth-only attachment.
- `UploadingBufferStream.java` — THE upload path (32,000,000 B instance): one PersistentClientMappedBuffer doubles as ring storage with a SegmentedManager arena inside it (`:28-40`). `upload(target, destOffset, size)` tries `expand()` of the current segment first (contiguity), else fresh alloc; returns `uploadBuffer.addr + addr` — callers write vertex data straight through the client pointer (`:56-85`). Overflow: commit + up to 10× `glFinish()`+tick before throwing (`:58-70`). `commit()`: flush per dirty segment, `GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT`, one glCopyNamedBufferSubData per queued upload, `GL_BUFFER_UPDATE_BARRIER_BIT` (`:89-109`). `tick()`: push `UploadFrame(new GlFence(), allocations)`, pop FIFO while head fence signaled (`:111-128`).
- `DownloadTaskStream.java` — 8,000,000 B readback ring; per-frame callback lists sized `frames = cpuRenderAheadLimit+1` (`:27-37`). `download()` copies immediately, queues callback on the CURRENT frame slot (`:39-43`); `tick()` advances `cidx` and fires that slot's callbacks (`:45-52`). Synchronization is purely "GPU must be ≤ frames−1 behind": **NO fence, NO barrier/flush on the readback path**. Also **no overflow handling**: alloc() returning −1 is never checked (`:40-41`) — a full buffer would copy to offset −1; Meshelium must add the check.
- `SegmentedManager.java` — core free-list allocator: FREE RB-tree keyed `(size<<34)|addr` (best-fit), TAKEN keyed `(addr<<30)|size` (merge-on-free); tail growth capped by sizeLimit → SIZE_LIMIT=−1 (`:34-113`); in-place `expand()` (`:118-157`); fuzz harness in main() (`:208-231`). Note: line-14 comment claims 2^39 addresses but masks give 2^34. Fully portable — reuse verbatim.
- `BufferArena.java` — quad-granularity suballocator: address unit = 1 quad = 64 bytes; sparse path = 80 GB virtual buffer + 1 MiB page commitment around each allocation; fallback = fixed device-only buffer with limit `memory/(4*stride)` quads (`:24-29`). TODO at `:8-9`: page decommit should be deferred to end-of-frame (commitment is expensive) — also a use-after-free hazard under Vulkan; defer decommit behind the frame fence. Stats (`getAllocatedMB`/`getFragmentation`) feed F3 + the eviction loop.
- `TickableManager.java:25` — `TickAll()` "should be called at the very end of the frame", invoked at `RenderPipeline.java:322`.
- `IdProvider.java:10-28` — dense id allocator with tail compaction; portable verbatim.

### Data formats (verbatim)

**SegmentedManager packed-long slot encoding** — `SegmentedManager.java:14-19`:

> ADDR_BITS=34, SIZE_BITS=30 (SegmentedManager.java:14-15). FREE set entries: (size<<34)|addr, ordered size-then-address for best-fit search (line 18, alloc at 37). TAKEN set entries: (addr<<30)|size, ordered address-then-size for neighbor merging (line 19, free at 64). Sentinel SIZE_LIMIT=-1 returned when alloc exceeds sizeLimit (lines 12, 42-44). Max allocation size 2^30 units, max address 2^34 units (the line-14 comment claims 2^39 addresses but the masks give 2^34).

**BufferArena quad addressing** — `BufferArena.java:41-55`:

> Arena allocates in quad units; byte offset = Integer.toUnsignedLong(quadAddr) * 4 * vertexFormatSize (BufferArena.java:41,50,55). vertexFormatSize = NvidiumCompactChunkVertex.STRIDE passed by SectionManager (NvidiumWorldRenderer.java:54, SectionManager.java:45). Quad index 0 is reserved at construction (BufferArena.java:31).

**UploadingBufferStream frame record** — `UploadingBufferStream.java:137-138`:

> UploadFrame = (GlFence fence, LongArrayList allocations) — one per frame, queued in a Deque, popped FIFO when fence signals (lines 114, 118-128). UploadData = (Buffer target, long uploadOffset, long targetOffset, long size) — one per staged copy, drained by commit() (lines 83, 100-103).

**Sparse buffer page table** — `PersistentSparseAddressableBuffer.java:29,52`:

> PAGE_SIZE = 1<<20 (1 MiB) (line 29). Residency tracked in Int2IntOpenHashMap allocationCount: page index -> refcount (line 52); commit on 0->1 (lines 54-59), decommit on 1->0 (lines 61-71). Byte range -> page range: pstart=addr/PAGE_SIZE, pend=(addr+size+PAGE_SIZE-1)/PAGE_SIZE (lines 77-87).

### Port notes

- **ARCHITECTURAL KEYSTONE:** the gl layer has no binding abstraction because the entire ABI is 64-bit GPU pointers — RenderPipeline builds the scene UBO by memPutLong-ing `getDeviceAddress()` of every buffer (`RenderPipeline.java:264-286`). VK_KHR_buffer_device_address preserves this 1:1: one small uniform/push block of VkDeviceAddress replaces the whole NV unified-memory dance.
- Upload sync contract to preserve: `upload()` returns a client pointer the caller writes into AFTER the call; the segment is not reusable until the flushing frame's fence signals. Keep "pointer valid until end of frame, GPU copy ordered before this frame's draws".
- GL executed commit() copies inline between arbitrary Sodium/vanilla GL work; Vulkan requires the copies recorded into a command buffer submitted before the consuming draws — record at TickAll/commit points or a dedicated transfer submission with semaphore.
- The sparse-vs-fallback split is runtime-selected and the fallback is battle-tested (all Linux users run it). Recommendation from the reader: ship fallback-style first, sparse as optional upgrade.
- Concrete sizes: upload staging 32,000,000 B; download staging 8,000,000 B; ring depth = cpuRenderAheadLimit+1; sparse virtual 80 GB, page 1 MiB; depth target D32F single mip; eviction threshold `max_geometry_memory−100` MB.
- `TrackedObject`, `SegmentedManager`, `IdProvider`, `TickableManager` are pure Java — reuse unchanged as Meshelium's foundation.
- **UNVERIFIED:** whether the NV driver coalesces per-segment flushes (`UploadingBufferStream.java:92-96`) or whether UNSYNCHRONIZED has effect beyond map time — irrelevant for Vulkan (choose HOST_COHERENT) but the flush-per-segment pattern shapes commit().
- **UNVERIFIED:** exact GPU-side consumers of DownloadTaskStream beyond the mechanism (frame-ring delayed callbacks, no fence) — the consumers are pinned down in §1/§5 (regionVisibility readback, statistics).

---

## 4. Terrain shaders (`shaders/terrain/`: task/mesh/fragment, opaque + temporal + translucent)

**Purpose.** Task shaders cull at section granularity (occlusion visibility byte + per-face-direction quad-range selection) and launch mesh workgroups sized to the surviving quad count; mesh shaders expand 16-byte packed quad vertices from a flat pool into triangles with sub-pixel triangle culling and subgroup compaction; the fragment shader reconstructs uv/colour/light by re-fetching raw vertices via `gl_PrimitiveID` + NV barycentrics, keeping the raster interface nearly empty. A temporal task variant re-renders only newly-visible sections; a translucent variant adds back-to-front section indirection plus an incremental in-buffer quad depth sort. This subsystem is the heart of the port: two pieces (SetMeshOutputsEXT ordering, indirect command format without firstTask) force genuine redesign.

### Key mechanics

- `terrain/task.glsl`: `local_size_x=1`, one invocation per section; `sectionId = gl_WorkGroupID.x` = firstTask + local index under MDI (`:18,27`). Early-out on `sectionVisibility[sectionId]` bit 0 (`:20-22,29-33`). Decodes chunk coords from header.xyz>>8 with 9-bit signed y (`:39-43`); reads regionData ONLY for transformationId (`:45`); `baseOffset = header.w` (`:49`); calls `populateTasks` (`:51`); optional statistics atomicAdd via raw pointer (`:35-37,54-56`).
- `terrain/task_common.glsl`: `MESH_WORKLOAD_PER_INVOCATION = 16`; `gl_TaskCountNV = ceil(quadCount/16)` (`:1,86`). Payload (88 B): origin, baseOffset, quadCount, transformationId + 4× uvec4 bins (binIa/binIb cumulative indices, binVa/binVb buffer offsets) (`:3-14`). Face culling: 6 directional groups included only when the camera is on the visible side; 7th "unconditional" group always emitted (`:30-78`); opaque groups start at `fr = (ranges.w>>16)&0xFFFF` — after the translucent quads (`:39`).
- `terrain/temporal_task.glsl`: predicate `(sectionVisibility & 3) == 1` — visible this frame AND not last frame (`:23-26`); otherwise byte-identical flow, same mesh/frag program.
- `terrain/mesh.glsl`: `local_size_x=16`, `max_vertices=64, max_primitives=32` (`:21-22`). `getOffset()`: 8-way linear search of payload bins maps invocation → quad id; `uint(-1)` past quadCount → thread returns without emitting (`:46-69,106-111`). Loads 4 vertices from `terrainData[(id<<2)+0..3]`, transforms by transformationArray then MVP (`:112-124`). **Degenerate-triangle cull:** pixel coords = `((clip.xy/w)+1)*screenSize` (screenSize = framebuffer/2); triangle kept only if `round(min) != round(max)` on both axes (`:129-149`). **Compaction via subgroup ops:** subgroupExclusiveAdd for triangle/vertex slots, subgroupMax for totals; subgroupElect thread writes gl_PrimitiveCountNV; thread 0 pre-zeroes it (`:102-104,157-161,202-204`) — **ASSUMES the 16-thread workgroup occupies exactly one subgroup**. Per-primitive `gl_PrimitiveID = (quadId<<4)|(tri<<3)|(lodBias<<2)|alphaCutoff2` (`:173-198`). Only extra vertex output is optional fogLerp (`:24-28,89-95`).
- `terrain/vertex_format.glsl`: MODEL_SCALE = 32/65536, MODEL_ORIGIN = 8 (`:1-2`); UV scale 1/TEXTURE_MAX_SCALE (host-injected define = 32768, `ShaderLoader.java:36`); material bits at v.y>>16: bits 0-1 alpha-cutoff index into `float[](0.0, 0.1, 0.5)`, bit 2 mip flag (`:25-39`); lightmap UV = `uvec2(v.y>>24, v.z>>24)/256.0` (`:41-44`).
- `terrain/fog.glsl`: cylindrical = `max(length(xz), abs(y))` else spherical; `computeFogLerp = smoothstep(fogStart, fogEnd, dist)`; fragment applies `mix(colour, fogColour.rgb, fogLerp)` (`frag.frag:68`).
- `terrain/frag.frag`: `GL_NV_fragment_shader_barycentric` — `gl_BaryCoordNV` weights attributes fetched straight from terrainData; the opaque pass carries NO uv/colour interpolants at all (`:11,53,87`). Decodes gl_PrimitiveID: quadId = id>>4, bit 3 selects vertex triple (0,1,2) or (2,3,0) (`:74-80`). Mip control: `texture(tex_diffuse, uv, ((gl_PrimitiveID>>2)&1) * -8.0)`; alpha discard vs cutoff bits (`:88-89`). TRANSLUCENT_PASS path uses interpolated uv/v_colour instead (`:83-85`). Lightmap binding 1, atlas binding 0 — classic bound samplers, not bindless (`:34-38`).
- Java authority for the vertex format: `NvidiumCompactChunkVertex.java:16-96` (STRIDE=16; light clamped 8..248; colour pre-multiplied by alpha, alpha zeroed).
- `ShaderLoader.java:19-39`: injected defines DEBUG, STATISTICS_* (cumulative), TRANSLUCENCY_SORTING_* (cumulative), RENDER_FOG, TEXTURE_MAX_SCALE=32768; `#import <nvidium:...>` is Sodium ShaderParser preprocessing, not a GL extension.

### Data formats (verbatim)

**Packed terrain vertex** (Vertex = uvec4, 16 bytes; 4 consecutive vertices = 1 quad) — GLSL decode `terrain/vertex_format.glsl:1-44` + `occlusion/scene.glsl:1`; Java encode `NvidiumCompactChunkVertex.java:53-96`; quad fetch `terrain/mesh.glsl:115-118`:

> v.x: bits 0-15 = posX u16, bits 16-31 = posY u16. v.y: bits 0-15 = posZ u16; bits 16-23 = material bits (bit16-17 = alphaCutoff index into float[]{0.0, 0.1, 0.5}, bit18 = hasMipping flag); bits 24-31 = block light. v.z: bits 0-7/8-15/16-23 = R/G/B vertex colour (pre-multiplied by vertex alpha on CPU, NvidiumCompactChunkVertex.java:82-90); bits 24-31 = sky light. v.w: bits 0-15 = U, bits 16-31 = V, both quantized as round(uv * 32768) (TEXTURE_MAX_VALUE=32768, NvidiumCompactChunkVertex.java:22,93-96). Position dequantize: pos = packed * (32.0/65536.0) - 8.0 => range [-8, +24) blocks around section origin at 1/2048-block precision. Light dequantize: uvec2(v.y>>24, v.z>>24)/256.0, values CPU-clamped to 8..248.

**Opaque/temporal task→mesh payload** (taskNV out Task, 88 bytes) — `terrain/task_common.glsl:3-14` (writer); `terrain/mesh.glsl:30-41` (reader):

> vec3 origin (12B, section origin in camera-relative chunk space * 16); uint baseOffset (4B, header.w); uint quadCount (4B); uint transformationId (4B, index into transformationArray); uvec4 binIa, binIb (32B, cumulative end-index of up to 8 visible face-group bins); uvec4 binVa, binVb (32B, quad-buffer start offset of each bin). Mesh shader linearly searches the 8 bins to map gl_GlobalInvocationID.x -> quad id (mesh.glsl:46-69).

**Translucent task→mesh payload** (25-26 bytes) — `terrain/translucent/task.glsl:22-28` (writer); `terrain/translucent/mesh.glsl:28-34` (reader):

> vec4 originAndBaseData (xyz = section origin, w = uintBitsToFloat(baseQuadOffset - jiggle)); uint quadCount (incl. jiggle); optional uint8_t jiggle (0 or 1, = frameId&1 when quadCount>=2, translucent/task.glsl:69) under TRANSLUCENCY_SORTING_QUADS.

**gl_PrimitiveID per-primitive encoding** (mesh → fragment) — written `terrain/mesh.glsl:173-198` and `translucent/mesh.glsl:187-188`; decoded `terrain/frag.frag:75-89`:

> bits 0-1 = alphaCutoff index (0/1/2 -> 0.0/0.1/0.5); bit 2 = lodBias flag (1 = no mipping -> sampled with lod bias -8.0, frag.frag:88); bit 3 = triangle index within quad (0 = verts 0,1,2; 1 = verts 2,3,0); bits 4-31 = quadId (frag re-fetches the 3 raw vertices from terrainData[(quadId<<2)+i] and interpolates with gl_BaryCoordNV). Translucent pass writes only quadId<<4 | tri<<3 (attributes come as interpolants instead).

**SceneData UBO (GLSL view)** — `occlusion/scene.glsl:45-92`; filled by `RenderPipeline.java:242-293`:

> mat4 MVP; [mat4 MVPInv if RENDER_FOG]; ivec4 chunkPosition; vec4 subchunkOffset; vec4 fogColour; then 10+ raw 64-bit device pointers (regionIndicies uint16*, regionData Region*, sectionData Section*, regionVisibility/sectionVisibility uint8*, terrain+translucency command buffers uvec2*, sortingRegionList, terrainData Vertex* (non-readonly: translucent mesh writes it), transformationArray mat4*, originArray uint64*, statistics_buffer uint32*); vec2 screenSize = framebuffer dims / 2 (RenderPipeline.java:289-291, so (ndc+1)*screenSize = pixel coords); float fogStart, fogEnd; bool isCylindricalFog; uint16_t regionCount; uint8_t frameId.

### Port notes

- There are **no classic meshlets**: a flat pool of 16-byte vertices, 4 per quad. Task shader = per-section cull; each mesh workgroup processes a fixed budget of quads (16 opaque / 32 translucent) located via the payload's bin table. Opaque quads per section pre-sorted CPU-side into `[translucent][X-][Y-][Z-][X+][Y+][Z+][unconditional]` so back-facing groups are skipped wholesale.
- The opaque pipeline deliberately starves the raster interface: only gl_Position (+ optional fogLerp) crosses it. Keep this only if VK_KHR_fragment_shader_barycentric is available on all targets; otherwise adopt the translucent path's interpolant style for both (costs ~24 B/vertex extra mesh output).
- **SetMeshOutputsEXT restructuring is the single biggest shader rewrite:** EXT requires counts up-front in uniform control flow; NV code computes them via subgroup scan after culling with early returns. Restructure to: cull verdicts → subgroup reductions → uniform SetMeshOutputsEXT → compacted writes.
- **EXT dispatch is 3D with no firstTask:** the uvec2 {count, first} commands must become {x=count,1,1} (12 B stride) plus a per-draw base-section lookup indexed by gl_DrawID in the task shader. This format change crosses subsystem boundaries (written by occlusion, consumed here).
- The pixel-coverage cull depends on screenSize = framebuffer/2; it culls sub-pixel triangles at any distance — cheap, worth keeping.
- gl_PrimitiveID budget: quadId occupies bits 4..31 → ~2^28 quads globally; EXT also has 32-bit per-primitive PrimitiveID.
- NV tuning (16-thread task-less workgroups, per-quad threads) may need vendor-specific sizing: make MESH_WORKLOAD_PER_INVOCATION and local_size spec-constants (AMD prefers wave-sized workgroups; check maxPreferredMeshWorkGroupInvocations).
- The translucency quad sort is optional (config-gated); a port can ship without it, falling back to section-level back-to-front from the reversed command buffer.
- Fog is per-vertex in mesh shaders, applied in frag; screen-space reconstruction via MVPInv exists but is commented out (`frag.frag:61-67`). MVPInv only exists in the UBO when RENDER_FOG — keep or pad out.
- **UNVERIFIED:** exact std140 offsets of the pointer-heavy tail of SceneData — the Java writer packs pointers as 8-byte longs relying on NV treating pointer members as 8-byte-aligned scalars; a Vulkan port should redefine this block explicitly (uint64_t/buffer_reference members) rather than replicate byte offsets.
- **UNVERIFIED:** whether vanilla MC 26.2's Vulkan backend exposes lightmap + block-atlas handles equivalent to the LightMapAccessor mixin — the shaders need both as combined image samplers (bindings 0/1).

---

## 5. Occlusion culling (GPU two-level box raster + CPU async BFS)

**Purpose.** A GPU-resident visibility pipeline: re-draw last frame's visible terrain first (priming depth), then rasterize region-level (8×4×8-chunk) bounding boxes and per-section boxes against that depth with all writes disabled; fragment shaders that survive the depth test store 1-byte visibility flags. Those flags gate next frame's terrain task shaders and are read back asynchronously to drive VRAM eviction. The separate CPU thread (AsyncOcclusionTracker) runs Sodium's BFS purely for rebuild scheduling / block entities / sprites — it never feeds the GPU path.

### Key mechanics

- **Pass 1 — region raster** (`occlusion/region_raster/mesh.glsl`): one workgroup per visible region draws its AABB, `local_size_x=8`, 8 verts/12 tris (`:16-17`, cube tables `:19-24`). Region fetched via `regionData[regionIndicies[gl_WorkGroupID.x]]` (`:42`). Box inflated by ADD_SIZE = 0.1 blocks (`:13,57-58`). Writes `gl_PrimitiveID = visibilityIndex` (the frustum-list slot) on every primitive (`:30`). Deleted slot (`data.a==-1`) → visibility 0, 0 primitives (`:46-50`). Thread 0 pre-writes `regionVisibility[i] = cameraInRegion ? 1 : 0` so a box surrounding the camera still passes (`:74-77`). Fragment (`fragment.frag`): `layout(early_fragment_tests)`; body is exactly `regionVisibility[gl_PrimitiveID] = uint8_t(1)` — idempotent, so representative-fragment-test is purely an optimization (`:10,20-22`).
- **Pass 2 — section raster** (`occlusion/section_raster/`): task shader gates on regionVisibility and **writes the next-frame indirect command buffers**: invisible region → uvec2(0) into both buffers, gl_TaskCountNV=0 (`task.glsl:38-43`); visible → `gl_TaskCountNV = sectionCount`, `terrainCommandBuffer[cmdIdx] = uvec2(count, visOutBase)` front-to-back and `translucencyCommandBuffer[regionCount-1-cmdIdx]` = same, reversed (`:55-66`). `_visOutBase = regionIndex<<8` passed via taskNV payload (`:19-25,55-56`). Mesh shader: one workgroup per section, reads `lastData = sectionVisibility[visibilityIndex]` BEFORE overwriting (`mesh.glsl:48-50`); empty header or hide bit (bit 17) → 0 (`:60-66`); tight AABB from header ±0.1 blocks (`:68-69`); per-primitive payload `gl_PrimitiveID = (visibilityIndex<<8) | ((lastData<<1)&0xff) | 1` — carries BOTH target byte index and new history value (`:83`); thread 0 pre-writes `sectionVisibility[idx] = (lastData<<1) | (cameraInsideSection?1:0)` — **history shifts even when occluded** (`:96`). Fragment stores `sectionVisibility[gl_PrimitiveID>>8] = uint8_t(gl_PrimitiveID)` (`fragment.glsl:20-22`).
- **Pass 4 — query raster** (`occlusion/queries/region/`): re-draws region boxes for CPU readback; unconditionally pre-writes 0 (`mesh.glsl:60-62`); the regionVisibility buffer is REUSED as query storage after section_raster consumed it (`RegionVisibilityTracker.java:38` "kind of evil").
- The GLSL/state contract, buffer sizes, phase ordering and barriers are as described in §1 (`RenderPipeline.java:104-111, 196-234, 336-402`). No GL occlusion queries and no conditional render anywhere — grep for ConditionalRender/glBeginQuery over src/main/java returns nothing.
- Consumers: `terrain/task.glsl:20-22` (`bit 0`), `temporal_task.glsl:23-26` (`(vis&3)==1`), `translucent/task.glsl:30-33` (bit 0 on redirected id).

### Data formats (verbatim)

**regionVisibility buffer** — `RenderPipeline.java:105`; written `region_raster/mesh.glsl:47,76` + `fragment.frag:21`; read `section_raster/task.glsl:38`:

> 1 byte per FRUSTUM-LIST slot (index = position in this frame's sorted visible-region list, NOT the region id), maxRegions=50000 bytes total. 0 = occluded, 1 = visible (fragment survived depth or camera inside box). Reused as the query-readback buffer by queries/region pass afterwards.

**sectionVisibility buffer** — `RenderPipeline.java:106`; written `section_raster/mesh.glsl:96` + `fragment.glsl:21`; read `terrain/task.glsl:21`, `temporal_task.glsl:24`, `translucent/task.glsl:32`:

> 1 byte per GLOBAL section slot (regionIndex<<8 | sectionIdx), maxRegions*256 bytes. Bit 0 = visible this frame; each frame the byte shifts left one, giving 8 frames of visibility history. Cleared per-region (256 bytes) when a region leaves the frustum (RenderPipeline.java:218).

**terrain/translucency command buffers** — `RenderPipeline.java:107-108`; written `section_raster/task.glsl:39-40,63-66`; consumed `PrimaryTerrainRasterizer.java:55-56`:

> 8 bytes per visible region: uvec2 { taskCount = sectionCount(region), firstTask = regionIndex<<8 } — the NV DrawMeshTasksIndirectCommandNV {count, first} layout. Terrain buffer in near-to-far order, translucency buffer mirrored far-to-near. Occluded/empty regions hold uvec2(0).

(SceneData UBO, Region/Section records, origin-offset entry: identical to the verbatim layouts in §1/§2/§4.)

### Port notes

- The core is a **GPU-driven temporal occlusion loop with ONE FRAME of latency**, plus a temporal catch-up pass for 0→1 transitions. Portable; keep it.
- What gets rasterized: only AABBs — one region box per CPU-frustum-visible region, then one tight per-section box inside GPU-visible regions. Real terrain is never re-rasterized for occlusion; the normal terrain draw's depth is the occluder set. Camera-inside boxes forced visible on the write side.
- Mechanical translation survives: scene UBO + pointers (→ BDA), both box-raster passes and meshlet layout, the 8-frame history byte, temporal pass, GPU-written command buffers, dual command ordering, CPU frustum + distance sort, async BFS, eviction readback. **REDESIGN needed for exactly two things:** (1) `{count, firstTask}` → gl_DrawID-indexed base lookup; (2) representative fragment test → accept redundant idempotent stores (correct, slower) or move to compute Hi-Z depth-pyramid testing (also removes the raster-state juggling).
- Vulkan hazards: `GL_SHADER_STORAGE_BARRIER_BIT` between region raster → section raster → temporal (`RenderPipeline.java:364,381`) = fragment-write → task-read buffer barriers; `GL_COMMAND_BARRIER_BIT` (`:387`) = dstAccess INDIRECT_COMMAND_READ. Note the visibility write side is the FRAGMENT stage (device-scope buffer stores from frag shaders) — fine on desktop AMD/Intel, poor on some tile-based mobile.
- Indexing subtlety: regionVisibility is indexed by frustum-list position; sectionVisibility by persistent global section slot. `section_raster/task.glsl:38` bridges the two. sectionVisibility must persist across frames and be zeroed when a region leaves the frustum or is reallocated.
- Depth contract: occlusion passes run LEQUAL/depthMask=false against the depth written by the primary terrain pass earlier in the same renderFrame. The 26.2 backend must expose that depth attachment between the two draws (or split passes with a depth dependency).
- Buffer budget at maxRegions=50000: regionVisibility 50 KB, sectionVisibility 12.8 MB, each command buffer 400 KB, scene UBO ~0.4 KB + 100 KB index list.
- **UNVERIFIED:** the NV indirect struct field order {count, first} is inferred from the shader writing `uvec2(count, visOutBase)` + consumers using gl_WorkGroupID.x as a pre-offset index; not confirmed against the NV_mesh_shader spec text from this repo.
- **UNVERIFIED:** exact std140 packing of the trailing SceneData scalars — SCENE_SIZE is a hand-computed constant; the port should re-derive the layout, not copy it.

---

## 6. Translucency + sorting

**Purpose.** Correct back-to-front alpha blending on a pipeline where the CPU never knows draw order. Each section's translucent quads sit contiguously at the START of its geometry allocation (pre-sorted far-to-near on the CPU at build time); ordering is then maintained at three GPU granularities: regions drawn far-to-near via the reversed command buffer, sections within a region reordered by a 256-element bitonic compute sort writing an 8-bit redirect into section headers, and individual quads incrementally bubble-sorted by the translucent mesh shader itself, which **physically swaps 64-byte quads in VRAM**, one comparator step per frame (the "jiggle" turns this into an odd-even transposition sort over time).

### Key mechanics

- **Layer 1 — CPU build-time sort** (`SodiumResultCompatibility.java:89-164`): translucent quads from all 7 facings merged, sorted by squared centroid distance to camera (camera projected/clamped to ≤32 blocks from section, `:75-87`), radix-sorted ascending then written reversed → far-to-near at offset 0. Camera pos read from the render thread's Camera object ON THE MESHING WORKER THREAD (`:69`) — benign race; port should snapshot at task creation. Mutates Sodium's translucent vertex data in place (flags byte, `:67,112-113`).
- **Layer 2 — region order**: `section_raster/task.glsl:34-35` writes translucency commands at `transCmdIdx = (regionCount - gl_WorkGroupID.x) - 1` — the CPU near-to-far list reversed. Every section slot gets a task workgroup, even non-translucent ones (TODO acknowledges the waste, `:64-65`).
- **Layer 3 — section order**: `sorting/region_section_sorter.comp` — SORTING_NETWORK_SIZE 256, local_size_x=128, one workgroup per `sortingRegionList` entry (`:3-4,30`). Key = Manhattan distance in chunk coords from camera chunk; empty sections keyed −999999999 (`:11-24`). Full 8-stage bitonic network (`:65-108`). `update()` writes the rank-r section id into bits 18-25 of `sectionData[(regionId<<8)|r].header.y` (`:43-55`) — the redirect consumed by `translucent/task.glsl:37-48` (`sectionId = (sectionId & ~0xFF) | ((header.y>>18)&0xFF)`). Dead code: region-visibility early-exit commented out because regionId ≠ visibility index (`:32-35`). Sort triggers: regions whose 128/64/128 slab contains the camera on any axis, every frame (`RenderPipeline.java:210-212`, `RegionManager.java:293-300`); plus every region whose metadata was re-uploaded (`RegionManager.java:90` → `enqueueRegionSort`); config NONE clears the queue (`RenderPipeline.java:304-306`). Dispatch at END of opaque renderFrame, bracketed by SSBO barriers (`:406-410`).
- **Layer 4 — quad order** (`translucent/mesh.glsl`): `local_size_x=32`, max_vertices=128/max_primitives=64, 1 thread = 1 quad (`:24-25,51-60`). Per-quad depth = |sum of 4 camera-relative verts|²/16 into `shared depthBuffers[32]` (`:82-84,119-122`); 16 threads compare disjoint adjacent pairs and `swapQuads()` rewrites all 8 vertices of both quads back into the global terrainData buffer (`:88-145`; `scene.glsl:70-71` — terrainData is non-readonly specifically for this). Sort runs AFTER vertex emission + barrier, so the current frame draws pre-swap order (`:176-179`). Jiggle slots render degenerate verts (w=−1), sentinel depth −9999 (`:124-127,162-175`). Lightmap sampled IN the mesh shader, baked into v_colour (`:44-49,75-80`) — the translucent fragment path needs zero exotic features. `gl_PrimitiveCountNV = min(remaining*2, 64)` (`:190-193`).
- `translucent/task.glsl`: MESH_WORKLOAD_PER_INVOCATION=32 (`:16`); `quadCount = (renderRanges.w>>16)&0xFFFF` (`:67`); jiggle = `min(quadCount>>1, frameId&1)` shifts the window by one on odd frames (`:68-72`); no transformationId — translucent skips the region transform (`mesh.glsl:66`).
- `sorting/sorting_network.glsl:1-53`: reusable shared-memory bitonic network (float keys + u8 payload), descending, N/2 threads sort N; portable GLSL — no NV intrinsics beyond uint8_t.
- Dispatch: `TranslucentTerrainRasterizer.java:51-64` — atlas + lightmap samplers, `glBufferAddressRangeNV(GL_DRAW_INDIRECT_ADDRESS_NV, commandAddr, regionCount*8)` + `glMultiDrawMeshTasksIndirectNV(0, regionCount, 0)`. Stale comment about "+8*6 offset to the unassigned dispatch" — actual code binds the dedicated translucencyCommandBuffer, no offset (`:62-63`). This resolves §1's UNVERIFIED "hijack" comment: it is historical.
- Blend/depth state: blendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA); depth TEST on and depth WRITES **left enabled** (mask restored true at `RenderPipeline.java:377`, never disabled in renderTranslucent) — verify against 26.2's vanilla translucent pass state.

### Data formats (verbatim)

**CPU translucent sort key** — `SodiumResultCompatibility.java:124-159`:

> packed int64: bits 63..32 = (int)(distSq*4096) where distSq = squared distance from quad centroid to camera pos clamped/projected to <=32 blocks from section origin (lines 75-87,131-137); bits 31..3 = quad index within facing range; bits 2..0 = facing index 0-6. LongArrays.radixSort ascending (line 155), then written REVERSED into output (line 159: dst index = (len-1)-i) => farthest quad at offset 0.

**Sorting network shared storage** — `sorting/sorting_network.glsl:1-6`; `region_section_sorter.comp:3-4`:

> shared float threadBufferFloat[SORTING_NETWORK_SIZE=256] (keys) + shared uint8_t threadBufferIndex[256] (payload ids); 128 threads (local_size_x = SIZE>>1), bitonic network of localSortA/localSortB stages sorting DESCENDING (swap when a<b, sorting_network.glsl:14,33); key = Manhattan distance in chunks from camera chunk, empty sections keyed -999999999 so they sink to the end (region_section_sorter.comp:11-24).

(Translucency command buffer, translucent task payload, section metadata redirect bits: verbatim layouts already given in §1/§2/§4/§5.)

### Port notes

- **What breaks without each layer:** without (1), quads inside a section blend arbitrarily the moment it loads; without (2), whole 128×64×128 regions composite wrongly at distance; without (3), sections inside a region blend in slot-allocation order; without (4), the build-time order goes stale as the camera moves (classic "glass pops through water when you strafe") until a rebuild.
- Correctness relies on primitive submission order across the multi-draw and across task workgroups. GL guarantees this; Vulkan guarantees primitive order by draw/primitive index for mesh shading — but only within one `vkCmdDrawMeshTasksIndirectEXT`. **UNVERIFIED:** exact VK_EXT_mesh_shader spec language on inter-workgroup primitive ordering — verify before relying on it.
- **The quad swap mutates shared VRAM while other workgroups render from it.** Within a workgroup, reads happen before the barrier; other workgroups of the same section (and the fragment shader's terrainData refetch) can observe half-swapped quads. Nvidium ships this as an accepted race. Strong recommendation: REDESIGN to a compute pass sorting per-section quad INDICES (sorting_network.glsl is already portable), mesh shader reads through the index — removes mutation, race, and jiggle in one move at the cost of one indirection per fetch.
- Translucent reuses the opaque frame's culling products (sectionVisibility + command buffer written this frame, drawn with prevRegionCount); keep opaque and translucent inside one frame graph with a shader-storage → indirect barrier.
- SECTIONS level = Manhattan whole-chunk distance (coarse but stable); QUADS = squared Euclidean centroid. Default config = QUADS. Defines cumulative: QUADS implies SECTIONS (`ShaderLoader.java:28-30`).
- Sodium 0.6+ (and 26.2 vanilla) has its own translucency sorting — Alphadium's SODIUM level (see §9) consumes the host sorter's index data instead of layers 1+4. The repackaging step stays regardless (Nvidium needs quads contiguous translucent-first with counts in renderRanges).
- **UNVERIFIED:** whether the jiggle single-step sort converges fast enough at QUADS level for large translucent volumes (oceans: 1 comparator pass over 32-quad windows per frame). No profiling data in the repo; a full per-section bitonic compute sort per frame is affordable on modern GPUs if artifacts persist.

---

## 7. Host integration: the mixin layer + sodiumCompat bridge

**Purpose.** Nvidium is not a standalone renderer: it is a GPU-side replacement bolted onto Sodium's CPU-side infrastructure. The mixin layer (a) swaps Nvidium's 16-byte vertex format into Sodium's meshing workers, (b) repackages each finished mesh on the worker thread, (c) steals the mesh-upload path and the terrain draw call so Sodium's GPU buffers and render lists are never used, and (d) optionally replaces the synchronous BFS with an async thread reusing Sodium's OcclusionCuller purely for rebuild scheduling / block entities / sprites. Everything Sodium does on the CPU is consumed as-is; everything Sodium does on the GPU is discarded.

### The 16 mixins (nvidium.mixins.json:5-22 — 5 minecraft.*, 11 sodium.*)

- **MixinRenderSectionManager** (targets `me.jellysquid.mods.sodium...RenderSectionManager`, remap=false, `:37`): the central takeover. `<init>` TAIL recomputes IS_ENABLED, constructs NvidiumWorldRenderer (+AsyncOcclusionTracker when async_bfs), injects into RenderRegionManager via INvidiumWorldRendererSetter (`:51-60`); @ModifyArg swaps the ChunkBuilder vertex format to NvidiumCompactChunkVertex (`:62-69`); `renderLayer` HEAD cancelled ALWAYS when enabled — SOLID → renderFrame, TRANSLUCENT → renderTranslucent, CUTOUT nothing (folded into solid), wrapped in pass.startDrawing/endDrawing (`:98-110`); destroy TAIL detaches + renderer.delete() (`:72-81`); Viewport captured from update() HEAD (`:93-96`); async_bfs hooks — createTerrainRenderList cancelled (`:127-132`), isSectionVisible = `|lastVisibleFrame − asyncFrameId| <= 1` (`:143-156`), tickVisibleRenders → tracker's animated sprite set via SpriteUtil (`:158-170`), scheduleRebuild fast-requeue with isSubmittedRebuild dedup (`:172-181`), submitRebuildTasks resets the flag (`:134-141`), getDebugStrings/getVisibleChunkCount replaced (`:112-120, 183-188`); **onSectionRemoved deletes GPU geometry ONLY when region_keep_distance == 32** — larger keep-distances intentionally retain geometry after Sodium discards the section (`:83-91`).
- **MixinRenderRegionManager** (`:27-34`): @Redirect on uploadMeshes — every ChunkBuildOutput goes to `renderer.uploadBuildResult` (→ SectionManager); Sodium's upload path bypassed entirely.
- **MixinChunkBuilderMeshingTask** (`:17-25`): TAIL of `execute` calls `SodiumResultCompatibility.repackage(result)` on the chunk-build worker thread ("saving alot of 1% lows"), stores via IRepackagedResult.
- **MixinSodiumWorldRenderer** (`:33-53`): setupTerrain hook calls `renderer.update(camera, viewport, frame, spectator)` each frame; renderBlockEntities cancelled when async_bfs, re-implemented over the tracker's section list calling Sodium's own static renderBlockEntity.
- **MixinChunkBuilder** (`:13-20`): scheduling budget ×3 when async_bfs. **MixinChunkJobQueue** (`:10-13`): availableProcessors → MAX_VALUE>>1 in shutdown. **MixinSodiumOptionsGUI** (`:27-43`): injects the config page + calls `reloadShaders()` on REQUIRES_SHADER_RELOAD via a live accessor chain. **MixinOptionFlag** (`:14-29`): grows Sodium's OptionFlag enum via $VALUES mutation.
- **MixinWindow** (`:15-18`): capability check after GL.createCapabilities. **MixinGameRenderer** (`:12-18`): far plane forced 16*512 = 8192 blocks. **MixinBackgroundRenderer** (`:12-19`): fog 192.0F constant → 9999999. **MixinWorldRenderer** (`:12-28`): Math.max redirect (require=0) + getViewDistance → region_keep_distance*16 (9999999 at 256). **LightMapAccessor** (`:8-11`): lightmap texture GL id.
- **MixinRenderSection** (`:40-61`): injects two volatile booleans isSeen/isSubmittedRebuild (IRenderSectionExtension).

### The repackager (`sodiumCompat/SodiumResultCompatibility.java`)

Reads `result.meshes.get(pass)` for TRANSLUCENT/SOLID/CUTOUT and each mesh's `getVertexData()` (NativeBuffer) + `getVertexRanges()[facing 0..6]` — the COMPLETE set of geometry data taken from Sodium (`:90-103, 169-218`). formatSize hardcoded 16 (`:19-20,49`). Output: translucent first (sorted, reversed), then per facing solid then cutout; `outOffsets[7]`=translucent count, `[0..6]`=solid+cutout counts (`:89-166,173-219`). Rewrites the material byte at vertex offset 6 (solid/translucent get 0b100; cutout remapped; 0.5 alpha cut → 0.1 under Iris, `:112-113,183-186,202-211`). Computes the section AABB while walking vertices: `decodePosition(short) = u16/2048f - 8.0`; min clamped 0..15, max 0..16, size = max−min−1 (`:228-247, 26-48`). The repackager and NvidiumCompactChunkVertex are one unit — byte-6 offsets hardcoded in three places.

### What crosses the boundary each frame (complete host-consumption list)

1. Pluggable per-vertex encoder in the mesher (ChunkVertexType swap) — `MixinRenderSectionManager.java:62-69`.
2. Per-facing quad bucketing (7 ModelQuadFacing buckets per pass) — load-bearing for task-shader face culling.
3. Worker-thread post-mesh hook + build-output attach point with delete() lifecycle.
4. Interceptable mesh-upload point per built section.
5. Renderer create/destroy + section-removal lifecycle events.
6. Terrain draw entry point with pass identity, projection+modelView matrices, camera xyz, pass state bracketing; Viewport (frustum + camera chunk) from update().
7. RenderSection state for the BFS: flags (HAS_BLOCK_GEOMETRY/HAS_BLOCK_ENTITIES/HAS_ANIMATED_SPRITES), pendingUpdate + cancellation token, culledBlockEntities, animatedSprites, lastVisibleFrame, isDisposed + 2 injected booleans.
8. Sodium's OcclusionCuller (graph BFS) used unmodified on Nvidium's thread — output drives ONLY rebuilds/block-entities/sprites, never terrain draws.
9. Rebuild queues (rebuildLists per ChunkUpdateType, queue-size caps, scheduling budget).
10. Sodium utilities: NativeBuffer, ShaderParser/ShaderConstants `#import` preprocessor, SpriteUtil, options (cpuRenderAheadLimit, animateOnlyVisibleTextures, useFogOcclusion), options-GUI pages + OptionFlag.
11. Vanilla per-frame state: RenderSystem fog color/start/end/shape; lightmap texture + block atlas `textures/atlas/blocks.png`; framebuffer size; Camera position (including a worker-thread read); MinecraftClient.chunkCullingEnabled.
12. Vanilla far-plane/fog override points (GameRenderer, BackgroundRenderer, WorldRenderer) for >vanilla render distance.
13. Feedback Nvidium provides TO the host: fills rebuildLists, answers isSectionVisible, supplies block-entity section list, marks animated sprites, F3 debug strings + visible-chunk count.
14. Iris API (soft dep): `isShaderPackInUse()` disables Nvidium at renderer construction (`IrisCheck.java:13-15`).

### Data formats (verbatim)

**RepackagedSectionOutput** (worker→render thread handoff) — `RepackagedSectionOutput.java:7-11`, `SodiumResultCompatibility.java:49, 89-225`:

> quads:int (=bytes/16/4); geometry:NativeBuffer — quad-ordered: [translucent quads, camera-sorted back-to-front then stored reversed] ++ for facing i in 0..6: [solid quads of facing i] ++ [cutout quads of facing i]; offsets:short[8] where [7]=translucent quad count and [0..6]=solid+cutout quad count per facing (relative counts, not absolute offsets); min:Vector3i (0..15), size:Vector3i (0..15, = max-min-1 clamped).

**NvidiumCompactChunkVertex (host-integration view)** — `NvidiumCompactChunkVertex.java:52-96`:

> 16 bytes/vertex, 4 vertices/quad. int0: x:u16 | y:u16 (pos = raw/2048.0 - 8.0, range [-8,24), 1/2048-block steps). int1: z:u16 | materialBits:u8 (byte offset 6) | blockLight:u8 (byte 7, clamped 8..248). int2: R:u8 G:u8 B:u8 (premultiplied by Sodium's AO alpha) | skyLight:u8 (byte 11, clamped 8..248). int3: u:u16 | v:u16 (uv = round(f*32768)). Post-repackage the material byte becomes: bit2=mip enable, bits0-1=alpha-cut level (0b100 solid/translucent; cutout remapped, 0.5-cut -> 0.1-cut under Iris).

### Port notes

- Frame takeover point is renderLayer, not setupTerrain: Sodium still runs its full update/build scheduling every frame (even its sync BFS when async_bfs=off — the render lists are simply never drawn).
- Terrain visibility is never fed back to Sodium: isSectionVisible answers from the async BFS, not GPU occlusion. **A port still needs a CPU-side traversal even with fully GPU-driven terrain** (block entities, sprites, rebuild scheduling).
- Section retention beyond the host's unload radius (keep_distance > 32) + the far-plane/fog mixins = the "infinite render distance" feature; the port needs the same decoupling of GPU residency from game section lifecycle.
- Thread-safety warts to fix: worker-thread camera read; in-place mutation of host vertex data.
- All five vanilla mixins target 1.21-era class shapes; none apply to the 26.2 Vulkan backend as-is — equivalents needed for fog params, lightmap handle, atlas handle, far-plane overrides, post-init capability hook.
- MixinOptionFlag's enum mutation and the ordinal-based GUI injection are brittle; use a first-class options API in the port.
- Iris coexistence pattern worth keeping: enable re-evaluated at every level (re)load, so toggling a shaderpack cleanly swaps renderers without restart.
- **UNVERIFIED:** purpose of the MixinChunkJobQueue redirect (presumably forces shutdown to drain all queued jobs). **UNVERIFIED:** which Math.max call MixinWorldRenderer's require=0 redirect targets. **UNVERIFIED:** exact semantics of Sodium 0.5.x `Material.bits()` (bit0=mip?, bits1-2=alpha-cutoff ordinal inferred from shift/mask only).

---

## 8. Config + public API (api0) + packaging

**Purpose.** A 9-field JSON config wired into Sodium's video-settings GUI as an extra page; several options become GLSL #defines at shader-compile time; the whole renderer gated behind the NV capability check + Iris detection. `api0` is a minimal public API for other mods (hide sections, attach transforms to regions — the Immersive Portals use case). Packaging: client-only Fabric mod, no entrypoints, LGPL-3.0.

### Facts

- Config fields + defaults (`NvidiumConfig.java:17-29`): `extra_rd=100` (**DEAD — never read**), `enable_temporal_coherence=true`, `max_geometry_memory=2048` MB, `automatic_memory=true`, `async_bfs=true`, `region_keep_distance=32`, `render_fog=true`, `translucency_sorting_level=QUADS`, `statistics_level=NONE`. Gson LOWER_CASE_WITH_UNDERSCORES, pretty-print (`:32-36`); save non-atomic with in-code TODO (`:51-58`); path `<configDir>/nvidium-config.json` (`:60-64`).
- GUI (`ConfigGuiBuilder.java`): "Disable nvidium" tickbox binds FORCE_DISABLE, deliberately NOT saved (`:22-30`); dummy row "disabled due to shaders being loaded" when Iris active (`:32-43`); region_keep_distance slider 32-256 (32='Vanilla', 256='Keep All', `:48`); max memory slider 2048-32768 step 512, enabled only when automatic off (`:86`), needs renderer reload ONLY when sparse unsupported (`:88`); translucency/render_fog/async_bfs = REQUIRES_RENDERER_RELOAD (`:70,97,117`); statistics_level = custom REQUIRES_SHADER_RELOAD (`:138`); page appended only when IS_COMPATIBLE (`:142-144`).
- Reload tiers to preserve: **live** (max memory on sparse) / **shader-only** (statistics) / **full renderer** (most). Applied via `MixinSodiumOptionsGUI.java:32-43` → `reloadShaders()`.
- `api0/NvidiumAPI.java`: ctor takes a modName that is stored but never used (`:11-13`); `hideSection`/`showSection(x,y,z)` (`:21-43`); `setRegionTransformId(id,x,y,z)` (`:52-59`); `setTransformation(id, Matrix4fc)` + `setOrigin(id, chunkX,chunkY,chunkZ)` (`:66-89`) — GPU-side moving/portal terrain. Every method silently no-ops when IS_ENABLED is false. Only internal use is commented-out debug code; the intended consumer is external (commented-out immersiveportals dep, `build.gradle:75`).
- Shader define plumbing (`ShaderLoader.java:17-40`): cumulative STATISTICS_*/TRANSLUCENCY_SORTING_* by enum ordinal; RENDER_FOG; DEBUG only when `-Dnvidium.isDebug=TRUE` (literal "TRUE" — lowercase silently does nothing, `Nvidium.java:16`); TEXTURE_MAX_SCALE=32768.
- Packaging: `fabric.mod.json` — id nvidium, LGPL-3.0, environment client, `entrypoints {}` (`:3,15,17-18`); depends fabricloader >=0.15, minecraft >=1.21, sodium exactly [=0.5.9, =0.5.11] (`:22-26`). Build: loom 1.7-SNAPSHOT, Java 21, yarn 1.21+build.2, 5 fabric-api split modules, sodium from Modrinth `mc1.21-0.5.11`, iris compileOnly (`build.gradle:19,57-76`). processResources expands version/commit/buildtime; `Nvidium.MOD_VERSION` depends on the "commit" custom value at class-init (`Nvidium.java:22-27`) — a jar built without expansion breaks at class-init; make version stamping optional-safe in Meshelium.
- README (`:5-8`): "requires sodium and an nvidia gtx 1600 series or newer (turing+)". De-facto known issues live in code: Linux sparse fallback, Iris auto-disable, non-atomic config save, max-memory slider refresh quirk (`en_us.json:6`).

### Data formats (verbatim)

**nvidium-config.json** — `NvidiumConfig.java:60-64`, Gson config `:32-36`:

> JSON object: extra_rd:int (default 100, DEAD — never read), enable_temporal_coherence:bool (true), max_geometry_memory:int MB (2048), automatic_memory:bool (true), async_bfs:bool (true), region_keep_distance:int chunks (32), render_fog:bool (true), translucency_sorting_level:enum string NONE|SECTIONS|QUADS (QUADS), statistics_level:enum string NONE|FRUSTUM|REGIONS|SECTIONS|QUADS (NONE).

**Shader compile-time defines** — `ShaderLoader.java:17-40`:

> Cumulative defines: STATISTICS_<LEVEL> for every level 1..statistics_level.ordinal(); TRANSLUCENCY_SORTING_<LEVEL> for 1..translucency_sorting_level.ordinal(); RENDER_FOG if render_fog; DEBUG if -Dnvidium.isDebug=TRUE; TEXTURE_MAX_SCALE=<TEXTURE_MAX_VALUE>. Injected through Sodium's ShaderConstants/ShaderParser '#import <ns:path>' mechanism.

### Port notes

- Do not port `extra_rd`. Keep the three-tier apply model (live / shader-only / full renderer). Keep api0's contract (hide bits + transform slots, silent no-op when disabled) **if** drop-in compatibility with api0 consumers is wanted.
- **UNVERIFIED:** whether any published third-party mod actually consumes api0 (no consumer in this repo; the Immersive Portals dep is commented out) — check Modrinth/GitHub before committing to api0 ABI compatibility.

---

## 9. Alphadium delta (community maintenance churn analysis)

**Purpose of this reading.** Alphadium (community fork by R7CE4/drouarb; MC 1.21 → 1.21.11, Sodium 0.5.11 → 0.8.6, LGPL-3.0) is the best available predictor of the host-boundary churn Meshelium will face at 26.2.

### Headline finding

**The entire NVIDIA GL memory core is untouched** — `gl/`, `util/BufferArena`, `UploadingBufferStream`, `DownloadTaskStream`, sparse buffers are ZERO-diff modulo package rename. 100% of the churn is (a) the Sodium/vanilla mixin boundary and (b) shader-side quality/perf work. The required NV extension set is identical (`Alphadium.java:29-36`).

### Churn classes

1. **Sodium package + API rename (0.5 → 0.8):** `me.jellysquid.mods.sodium` → `net.caffeinemc.mods.sodium`; SodiumOptionsGUI mixin replaced by official `sodium:config_api_user` ConfigEntryPoint (`fabric.mod.json:21-25`); `getVertexRanges()` → `getVertexSegments()` (layout change: interleaved (count,facing) pairs); ChunkUpdateType enum → int flags + TaskQueueType; `uploadMeshes` → `uploadResults(BuilderTaskOutput)`; ChunkBuildOutput split into ChunkBuildOutput + ChunkSortOutput; both projects pin exact Sodium versions (`=0.8.6`).
2. **Vanilla Blaze3D GpuDevice refactor (1.21.5+)** — the most relevant precedent since 26.2's Vulkan backend is that refactor's endpoint: capability hook moved Window → GlDevice ctor (`MixinGlDevice.java:13-16`); `pass.startDrawing/endDrawing` died — manual FBO bind via `GlTexture.getFbo(dsa, depthTex)` + Sodium's `GlCommandEncoderAccessor.sodium$applyPipelineState` (`MixinRenderSectionManager.java:107-126`); GpuSampler/GpuTextureView instead of raw texture ids; lightmap via `lightTexture().getTextureView()`. **A raw-API renderer coexisting with the vanilla abstraction requires deliberate state-handoff shims at every draw boundary.**
3. **Vanilla fog rewrite:** `RenderSystem.getShaderFog*` deleted; fog = FogParameters (environmental + render range pairs; fog-shape enum gone) + vanilla FogRenderer writing a fog UBO. Scene UBO switched to `fogColour, environmentFog vec2, renderFog vec2`; fog code uses `<sodium:include/fog.glsl>` `_linearFog`. Overrides moved to `MixinFogRenderer.java:11-30` (ModifyVariable on setupFog + ModifyArg on updateBuffer, skyEnd clamped [32,512]).

### Behavioural fixes the fork absorbed (inherit these in Meshelium's design)

(1) global block entities dropped by the async-BFS path — must iterate `getSectionsWithGlobalEntities()` too; (2) scheduleSort/upgradePendingUpdate double-enqueue race → NPE in translucency sorting — dedupe queue inserts (`MixinRenderSectionManager.java:198-215`); (3) `prevRegionCount` stale when zero regions visible; (4) unconditional degenerate-triangle culling caused pixel holes → now optional + 0.01 rounding bias; (5) atlas bleeding → texCoordShrink/texelSize uniforms from sub-texel precision bits + UV clamp with half-texel shift; (6) region_keep_distance must respect effective render distance; sentinel 256 → 257; (7) **Iris hijacks the chunk vertex format unless mixin priority > Iris's** (priority=1500, `MixinRenderSectionManager.java:46`).

### Architecture evolution worth copying (more Vulkan-shaped than the original)

- **Command-buffer building moved out of the section-raster task shader into a dedicated compute pass** (`command_buffer_builder.comp`): 32-thread workgroup per region, **KHR subgroup ops only** (GL_KHR_shader_subgroup_basic/arithmetic — already cross-vendor, `:6-7`), compacts visible section ids with prefix sums and emits exact task counts per pass; terrain/translucent/temporal get **separate command buffers**; task shaders index a compacted `u8vec3 sectionIndices` buffer instead of launching all 256 sections and early-exiting. Maps cleanly to a Vulkan compute pre-pass + `vkCmdDrawMeshTasksIndirect(Count)EXT`.
- **SODIUM translucency sorting level (new default):** reuse Sodium's CPU sorter output (ChunkSortOutput index buffers, 6 ints/quad compressed to 1 uint/quad stored in the terrain arena; SECTION_SIZE grew 32 → 48 with a translucencyDataIdx at offset +32; integrity check totalQuads*6*4 == indexBuffer length guards a quad-count race, `SectionManager.java:66-135`). Consumed in `translucent/mesh.glsl:164-173`.
- **use_sodium_vertex_format option:** whole pipeline can run on Sodium's stock 20-byte CompactChunkVertex (3×20-bit positions hi/lo split, scale 32/2^20) — implement the host's stock format first, add the compact one as an optimization.
- **NV barycentric made optional** (`use_nv_fragment_shader_barycentric`, default true) with a conventional interpolant fallback — proving the renderer works without it.
- Mesh shader rewrite (portable perf work): 16 → 32 threads, 2 threads/quad exchanging shared corners via `subgroupShuffleXor`; task payload halved (binStarts/binOffsets 2×uvec4). New `sampleNearest` texel-center correction + `sampleRGSS` 4-tap supersampling honoring vanilla's RGSS filtering option. GPUTiming: pooled GL timestamp queries, 100-sample rolling averages per phase.

### Dropped by the fork

`extra_rd`; the MixinChunkBuilder 3× budget hack; custom fog.glsl; per-vertex flag rewriting + Iris alpha-cutoff remap in the repackager (Sodium 0.8's material byte arrives correct); the MixinWorldRenderer Math.max/viewDistance redirects.

### Data formats added/changed (verbatim)

**Scene uniform — Alphadium redesign** — `alphadium RenderPipeline.java:67-97`:

> mat4 MVP; mat4 MVPInv; ivec4 chunkPosition; vec4 subchunkOffset; then 15 x 8-byte GPU pointers: uint16_t* regionIndicies, Region* regionData, Section* sectionData, uint8_t* regionVisibility, uint8_t* sectionVisibility, u8vec3* sectionIndices, uvec2* terrainCommandBuffer, uvec2* translucencyCommandBuffer, uvec2* temporalCommandBuffer, uint16_t* sortingRegionList, Vertex* terrainData, uint* translucencyIndexData, mat4* transformationArray, uint64_t* originArray, uint32_t* statistics_buffer; vec2 screenSize; vec4 fogColour; vec2 environmentFog; vec2 renderFog; vec2 texCoordShrink; vec2 texelSize; uint flags (bit0=useBlockFaceCulling, bit1=useRGSS — scene.glsl:127-134); uint16_t regionCount; uint8_t frameId.

**sectionIndices compaction buffer** — `alphadium RenderPipeline.java:144` (maxRegions*256*3 B); written `command_buffer_builder.comp:61-73`:

> u8vec3 per section slot: .x = compacted visible-section id (terrain pass), .y = compacted translucent-section id (after mirror redirection via header.y>>18 & 0xFF), .z = compacted newly-visible id (temporal pass). Task shaders index it as sectionIndices[gl_WorkGroupID.x] + (gl_WorkGroupID.x & 0xFFFFFF00).

**Sodium CompactChunkVertex (20 B, optional input)** — `alphadium shaders/terrain/vertex_format/sodium_vertex_format.glsl:1-33`:

> uint hi + uint lo: 3x20-bit positions interleaved as (hi 10 high bits)<<10 | (lo 10 low bits) per axis, scale 32/2^20, offset -8; uint color; u16 u, v (15-bit + sign bias bit used with texCoordShrink); u8 blockLight, skyLight, material, section.

### Loose ends

- `AlphadiumOptionFlags.REQUIRES_SHADER_RELOAD` is registered and set but the applyChanges handler was dropped — nothing consumes it; changing statistics level likely doesn't hot-reload shaders. **UNVERIFIED:** whether Sodium 0.8's config system tolerates the foreign flag.
- **UNVERIFIED:** no .git history/changelogs in either tree — fix ordering inferred from code comments, not commits. **UNVERIFIED:** `alphadium/bin/main` (IDE output mirror) not analyzed separately.

---

## 10. The NV-only inventory

Consolidated across all nine readings. "REDESIGN" = structural change, not a rename.

| # | Mechanism | Where it lives | Cross-vendor replacement |
|---|-----------|----------------|--------------------------|
| 1 | `GL_NV_mesh_shader` dispatch: `glDrawMeshTasksNV`, `glMultiDrawMeshTasksIndirectNV` | Nvidium.java:31; RegionRasterizer.java:20; SectionRasterizer.java:20; RegionVisibilityTracker.java:42; Primary/Temporal/TranslucentTerrainRasterizer.java:55-64 | `vkCmdDrawMeshTasksEXT` / `vkCmdDrawMeshTasksIndirectEXT` (VK_EXT_mesh_shader) |
| 2 | NV GLSL mesh dialect: `taskNV` payload blocks, `gl_TaskCountNV`, `gl_MeshVerticesNV`, `gl_PrimitiveIndicesNV`, `gl_PrimitiveCountNV` written *after* compaction with early-returning threads | all task/mesh shaders (terrain/, occlusion/, queries/) — e.g. task_common.glsl:3-14,86; mesh.glsl:102-104,157-204 | **REDESIGN**: `taskPayloadSharedEXT` + `EmitMeshTasksEXT(x,y,z)`; `SetMeshOutputsEXT` must be called once, uniformly, BEFORE output writes — restructure to cull → reduce → SetMeshOutputs → compacted writes; `gl_PrimitiveTriangleIndicesEXT` |
| 3 | 8-byte indirect command `{taskCount, firstTask}` with `firstTask=regionId<<8` making `gl_WorkGroupID.x` a global section index | occlusion/section_raster/task.glsl:63-66; terrain/task.glsl:27; scene.glsl:64-66; RenderPipeline.java:44-45 | **REDESIGN**: `VkDrawMeshTasksIndirectCommandEXT` is `{x,y,z}` 12 B, no firstTask — gl_DrawID-indexed per-draw base buffer, 2D dispatch, or per-region push; crosses occlusion/terrain/translucency subsystems |
| 4 | `GL_NV_uniform_buffer_unified_memory` + `GL_NV_vertex_buffer_unified_memory`: client states + `glBufferAddressRangeNV` binding buffers by raw address | RenderPipeline.java:329-334, 412-415, 446-451, 464-467; Nvidium.java:32-33 | VK_KHR_buffer_device_address (core 1.2); UBO bound by descriptor; no client-state equivalent needed |
| 5 | `GL_NV_shader_buffer_load`: `glMakeNamedBufferResidentNV` / `GL_BUFFER_GPU_ADDRESS_NV`; raw pointers inside a UBO; pointer arithmetic + atomics in GLSL | DeviceOnlyMappedBuffer.java:19-25,31; PersistentSparseAddressableBuffer.java:39-44; scene.glsl:45-92; RegionManager.java:302-308 | `vkGetBufferDeviceAddress` + GLSL `GL_EXT_buffer_reference(2)` — 1:1 pattern; residency implicit in Vulkan |
| 6 | `GL_NV_representative_fragment_test` around occlusion/query rasters | RenderPipeline.java:354, 376, 395-399; Nvidium.java:34 | Drop it — stores are idempotent, correctness unaffected (early_fragment_tests already required); or **REDESIGN** to compute Hi-Z depth-pyramid culling (VK_NV_representative_fragment_test is NV-only in Vulkan too) |
| 7 | `GL_NV_bindless_multi_draw_indirect` (`GL_DRAW_INDIRECT_ADDRESS_NV`) | Nvidium.java:36; RenderPipeline.java:44-45; PrimaryTerrainRasterizer.java:55-56 | Not needed — `vkCmdDrawMeshTasksIndirectEXT(buffer, offset, drawCount, stride)` is inherently bindless; drawCount is CPU-known |
| 8 | `GL_ARB_sparse_buffer` 80 GB virtual arena, 1 MiB pages (NV-driver-tuned) combined with NV residency | BufferArena.java:24-31; PersistentSparseAddressableBuffer.java:26-50; Nvidium.java:35,43-46 | VK sparse binding (`sparseResidencyBuffer` + `vkQueueBindSparse`) — optional, queue-based; pragmatic primary path = generalize Nvidium's own non-sparse fallback (fixed/growable device-address pool, VMA) |
| 9 | `GL_NVX_gpu_memory_info` VRAM budget query | NvidiumWorldRenderer.java:27, 124 | VK_EXT_memory_budget (vmaGetHeapBudgets / VkPhysicalDeviceMemoryBudgetPropertiesEXT) |
| 10 | `GL_NV_fragment_shader_barycentric` (`gl_BaryCoordNV` vertex pulling in frag) | frag.frag:11, 53, 87 | VK_KHR_fragment_shader_barycentric (cross-vendor NV/AMD RDNA/Intel Arc); fallback = interpolants like the translucent path (Alphadium ships the fallback) |
| 11 | `GL_NV_gpu_shader5`: uint8_t/int16_t/uint64_t/float16_t, C-style casts, `#pragma optionNV(unroll all)` | every shader header; scene.glsl:58-91; vertex_format.glsl:30,34 | GL_EXT_shader_explicit_arithmetic_types_int8/16/64/float16 (+ 8/16-bit storage features); constructor casts; `[[unroll]]` |
| 12 | `GL_NV_bindless_texture` declared in every stage (never actually used — samplers are binding 0/1) | task.glsl:8; mesh.glsl:8; frag.frag:6; occlusion shaders | Delete; plain combined-image-sampler descriptors |
| 13 | Implicit workgroup==subgroup assumption (subgroupExclusiveAdd/Max/Elect for cross-workgroup compaction, no shared-memory fallback) | terrain/mesh.glsl:21, 157-161, 202-204 | VK_EXT_subgroup_size_control (requiredSubgroupSize/full-subgroups) sized to gl_SubgroupSize, or shared-memory prefix sums; tune workload per vendor |
| 14 | Fenceless GPU→CPU readback through a WRITE-only, non-coherent persistent mapping (NV driver leniency) | DownloadTaskStream.java:39-52; PersistentClientMappedBuffer.java:19-20 | **REDESIGN**: HOST_VISIBLE\|HOST_CACHED read-mapped buffer; TRANSFER_WRITE→HOST_READ barrier; fence/timeline-gated callback delivery; invalidate if non-coherent |
| 15 | Translucent mesh shader mutating the live vertex pool (`swapQuads` — intentionally racy cross-workgroup writes) | translucent/mesh.glsl:89-145; scene.glsl:70-71 | **REDESIGN**: compute-pass index sort (reuse sorting_network.glsl) or host sorter data (Alphadium SODIUM level); UB under the Vulkan memory model as-is |
| 16 | GL implicit-command-stream mechanisms: DSA copies (`glCopyNamedBufferSubData`, `nglClearNamedBufferSubData`), global `glMemoryBarrier`, `glFenceSync`/`glClientWaitSync`, `glFinish` fallback, GL FBO via GlStateManager | RenderDevice.java:15-26; UploadingBufferStream.java:89-109, 63; GlFence.java:10-30; RenderPipeline.java:130, 218; DepthOnlyFrameBuffer.java:19-37 | Standard Vulkan: `vkCmdCopyBuffer`/`vkCmdFillBuffer` + `vkCmdPipelineBarrier2` recorded in the frame command buffer; timeline semaphores; `vkWaitSemaphores` on oldest value (never vkDeviceWaitIdle mid-frame); dynamic rendering for the depth-only target |
| 17 | GL program objects from GLSL strings, `glUseProgram`, `#import <nvidium:...>` via Sodium's ShaderParser | Shader.java:25-27, 50-103; ShaderLoader.java:39 | **REDESIGN** (structural, not conceptual): GLSL→SPIR-V (shaderc/glslang) + VkPipeline or VK_EXT_shader_object; keep IShaderProcessor as a pre-compile text pass; own include resolution |
| 18 | `GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV` — imported, used only in commented-out code | RenderPipeline.java:38, 363, 380 (commented) | n/a — live barriers are standard bits; map each site to vkCmdPipelineBarrier2 |

---

## 11. Host integration verdict

### What the renderer actually consumes from its host

The evidence (§7, confirmed by §9) is unambiguous: **Nvidium consumes Sodium's entire CPU side and none of Sodium's GPU side.**

Consumed: meshing workers + task scheduling with a pluggable per-vertex encoder (geometry is emitted directly in Nvidium's format by Sodium's own mesher); per-facing quad bucketing (7 ModelQuadFacing ranges per pass — load-bearing for GPU face culling); section lifecycle, flags, pending-update state, rebuild queues; OcclusionCuller graph-BFS (for rebuild scheduling, block entities, sprites only); options GUI + option storage; GLSL `#import` preprocessor; NativeBuffer; per-frame camera/matrices/viewport/fog/framebuffer state; vanilla atlas + lightmap textures; far-plane/fog override points.

Rebuilt by Nvidium: the upload path, region/section GPU storage, render-list generation (GPU-driven), all terrain draw calls, translucency sorting, fog application.

Feedback returned to the host: rebuild scheduling, `isSectionVisible` answers, block-entity section lists, animated-sprite activation, debug strings.

### The trade-off (evidence only — the decision belongs to the SPEC)

**Option A — Meshelium as a Sodium addon (Nvidium's shape).**
- Keeps ~16 small mixins; inherits mesher, facing buckets, section store, BFS culler, rebuild queues, config UI, shader preprocessing for free.
- Cost: an exact-version pin is structurally required — both Nvidium (`=0.5.9/=0.5.11`) and Alphadium (`=0.8.6`) pin exactly, because all sodium mixins are remap=false against internals. Sodium 0.5→0.8 renamed the root package, replaced the GUI-mixin surface with an official config API, changed the mesh-parts layout (`getVertexRanges`→`getVertexSegments`), split build outputs, and reworked update queues — Alphadium absorbed all of it at the boundary while the GPU core stayed byte-identical.
- Upside evidence: newer Sodium gives correct translucency sorting data for free (Alphadium's SODIUM level is its default), plus a stock vertex format option that eases mod compat.
- Iris interplay must be managed (auto-disable pattern + mixin priority 1500 to keep the vertex format).

**Option B — Meshelium standalone on vanilla 26.2's section pipeline.**
- Removes the entire Sodium coupling class — no version pin, no remap=false mixins into a moving target.
- Cost, enumerated from the consumption list: Meshelium must recreate (or hook vanilla equivalents of) a facing-bucketed quad mesher with pluggable 16-byte encoding, a section store with flags + rebuild queues + a BFS occlusion graph (vanilla's SectionOcclusionGraph is the candidate base for the async tracker), the per-frame draw hooks with pass identity/matrices/camera, fog parameter sourcing, atlas/lightmap handles, far-plane overrides, and its own options UI + shader preprocessing — "roughly the majority of Sodium's chunk subsystem" (§7 verdict note).
- The Alphadium precedent cuts both ways: it proves the GPU core is host-agnostic (portable to any host), but also that the host boundary is where 100% of ongoing maintenance lands — a vanilla host trades Sodium-version churn for vanilla-version churn, on a backend (26.2 Vulkan) that is the endpoint of the GpuDevice refactor that already forced Alphadium into manual state-handoff shims at every draw boundary.
- A CPU-side section traversal is required in EITHER option (block entities, sprites, rebuild scheduling are never GPU-driven).

The SPEC must weigh: pin-and-track a host that already solved the CPU side vs. own the CPU side and track vanilla. This document supplies the inventory of exactly what must be rebuilt or bridged in each case; it does not choose.

---

## 12. Open questions for the SPEC

1. **Host choice:** Sodium addon vs standalone on vanilla 26.2's section pipeline (§11) — the load-bearing decision everything else keys off.
2. **Indirect command redesign:** recover the `regionId<<8` base via a gl_DrawID-indexed per-draw buffer, 2D dispatch groups, or one draw per region with a push constant? (Affects occlusion writer, terrain/temporal/translucent consumers simultaneously.) Consider adopting Alphadium's compute command-buffer-builder (already KHR-subgroup-only, separate buffers per pass) as the baseline rather than porting the NV task-shader writer.
3. **Occlusion mechanism:** keep box rasterization with idempotent fragment stores (drop rep-frag-test, correct but more stores), or REDESIGN to compute Hi-Z depth-pyramid culling? The depth-attachment contract with the 26.2 backend (occlusion passes reading the frame's terrain depth mid-frame) must be answerable either way.
4. **Mesh-shader restructuring plan:** SetMeshOutputsEXT-compatible control flow, subgroup-size control vs shared-memory prefix sums, and per-vendor MESH_WORKLOAD/local_size spec constants (16/32 NV-tuned today).
5. **Translucency quad sorting:** port the racy in-place swap (accepting a formal data race), move to a compute index sort, or consume the host sorter's index data (Alphadium SODIUM level)? Also: verify VK_EXT_mesh_shader's inter-workgroup primitive ordering guarantee before relying on submission-order blending (§6 UNVERIFIED).
6. **Terrain arena backing:** non-sparse fallback-first (Linux-precedented) with Vulkan sparse binding as optional upgrade, or sparse-first? Page decommit must move behind the frame fence either way.
7. **Scene UBO:** re-derive the layout explicitly with buffer_reference members (do NOT copy SCENE_SIZE byte offsets — §4/§5 UNVERIFIED std140 packing); always include MVPInv to kill the fog-dependent dual layout?
8. **Vertex format strategy:** host/stock format first with the 16-byte compact format as an optimization (Alphadium's `use_sodium_vertex_format` lever), or compact-only like Nvidium?
9. **Fragment barycentric policy:** require VK_KHR_fragment_shader_barycentric, or ship the interpolant fallback as baseline (Alphadium proves both paths)?
10. **Capability gate + degraded modes:** hard requirements = VK_EXT_mesh_shader + bufferDeviceAddress; which of sparse/memory-budget/barycentric are optional tiers, and does the two-stage enable (compat at device creation, enable per world-renderer rebuild, no live swap) carry over as-is?
11. **26.2 host hook inventory:** where exactly does the vanilla Vulkan backend expose lightmap/atlas image handles, fog parameters, far-plane overrides, section-build interception, and the SOLID/TRANSLUCENT draw entry points? (All five of Nvidium's vanilla mixins target 1.21-era shapes; none apply.)
12. **Frame-graph placement of streaming:** staging copies recorded into the frame command buffer at commit points vs a dedicated transfer submission; readback callbacks gated on frame fences (fixes §3's fenceless-readback and missing overflow check).
13. **Known-bug fixes to adopt at design time:** sectionBuffer sizing mismatch (39k vs 50k regions, §2), DownloadTaskStream overflow check, deferred page decommit, worker-thread camera snapshot, plus Alphadium's seven behavioural fixes (§9).
14. **api0 compatibility:** reproduce hide-bits + transform slots (and the silent no-op contract), or drop it? First check whether any published mod consumes api0 (§8 UNVERIFIED).
15. **Sorting convergence:** does one odd-even exchange step per frame suffice at QUADS level for large translucent volumes, or should the port budget a full per-section bitonic compute sort per frame (§6 UNVERIFIED — no profiling data exists)?
16. **Statistics/debug surface:** the statistics SSBO pointer slot must exist in the UBO layout (or shaders compiled without it); decide the F3/timing story (Alphadium's GPUTiming timestamp-query pattern is a ready template).
