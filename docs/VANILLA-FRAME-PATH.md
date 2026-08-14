# The vanilla frame path — where a 26.2 Vulkan frame is recorded, and where Meshelium draws

Wave-2 recon, 2026-08-09. Method: `javap -p -c` against the real jar
(`attack-of-the-bteam-1.26.2/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/…`),
plus `unzip -l` / constant-pool `strings`. Extends `VANILLA-VULKAN-SEAM.md`
(Q4 there = this whole document). Nothing is remembered from tutorials;
every claim cites class.method + the bytecode that proves it. UNVERIFIED
items are marked and collected in the ledger at the end.

Status: **Q1–Q6 CLOSED** (2026-08-09). The wave-2 plan is §Q6; the standing
UNVERIFIED items are in the ledger at the end.

---

## Q1 — The frame skeleton on the Vulkan path

### 1.1 Who begins and ends a frame

`Minecraft.renderFrame(boolean)` (private; called from `Minecraft.runTick(boolean)`
at ip 457, `invokevirtual renderFrame:(Z)V`) is the whole frame, in this order
(all ips from `javap -c net.minecraft.client.Minecraft`, method `renderFrame`):

1. **Early-out** if `windowSurface.isAcquired()` already true (ip 1–10).
2. **"update window"**: `Window.updateFullscreenIfChanged()`; if
   `windowSurfaceNeedsReconfiguring || (surface.isSuboptimal() && !surfaceIsInvalid)`:
   `glfwGetFramebufferSize` → `GpuSurface$PresentMode.getSupportedVsyncMode(
   surface.supportedPresentModes(), options.enableVsync())` →
   `windowSurface.configure(new Configuration(w, h, presentMode))` (ip 64–160;
   `SurfaceException` caught → `surfaceIsInvalid = true`).
3. **Swapchain acquire**: `windowSurface.acquireNextTexture()` (ip 214), skipped
   when `surfaceIsInvalid || window.isMinimized()`; `SurfaceException` caught →
   invalid + needs reconfiguring (ip 220–249).
4. `gameRenderer.update(DeltaTracker)`, `pick(f)`, **"extract"**:
   `gameRenderer.extract(DeltaTracker, boolean)` (ip 441) — render-state
   extraction, no GPU commands.
5. `RenderSystem.executePendingTasks()` (ip 502).
6. **`gameRenderer.render(DeltaTracker, boolean)`** (ip 520) — every render pass
   of the frame is recorded (and interleaved-submitted, see 1.2) inside this call.
7. **"present"**: `windowSurface.blitFromTexture(RenderSystem.getDevice()
   .createCommandEncoder(), gameRenderer.mainRenderTarget().getColorTextureView())`
   (ip 622) — the frame renders OFFSCREEN into `MainTarget` and is blitted to the
   swapchain image at the end.
8. **`RenderSystem.getDevice().createCommandEncoder().submit()`** (ip 689) — the
   frame's final queue submission (see 1.2: "createCommandEncoder" returns a facade
   over the one true encoder, so this is `VulkanCommandEncoder.submit()`).
9. `windowSurface.present()` (ip 706).
10. `RenderSystem.getDynamicUniforms().reset()` (ip 723–726);
    `levelRenderer.endFrame()` (ip 733).

So: **acquire → extract → record passes → blit to swapchain → submit → present**,
all on the render thread, one `GpuSurface` owned by `Minecraft`
(`windowSurface` field, created `GpuDevice.createSurface(window.handle)`).

### 1.2 The encoder model — one encoder, one submission-in-progress, N command buffers

- `GpuDevice.createCommandEncoder()` bytecode: `new CommandEncoder(profiler,
  backend, backend.createCommandEncoder())` — a **fresh facade every call**.
  `VulkanDevice.createCommandEncoder()` bytecode is 2 instructions:
  `getfield commandEncoder; areturn` — the backend encoder is a **singleton per
  device**. All facades share the same `VulkanCommandEncoder` and its state.
  (This mirrors the seam doc's GpuDevice-wrapper finding: mod code reaches the
  singleton via `RenderSystem.getDevice().createCommandEncoder()` but the
  interesting state lives in `VulkanCommandEncoder`.)
- `VulkanCommandEncoder` fields (javap -p): `currentCommandBuffer
  (VkCommandBuffer)`, `currentRenderPass (VulkanRenderPass)`,
  `submissionBuilder (VulkanQueue$Submission)`, `commandPools
  (VulkanCommandPool[2])`, `submitSemaphore (long)` (a timeline semaphore),
  `currentSubmitIndex/completedSubmitIndex`, `MAX_SUBMITS_IN_FLIGHT = 2`
  (javap -constants).
- `commandBuffer()` (private): returns `currentCommandBuffer` if non-null; else
  (asserting `currentRenderPass == null`) calls
  `allocateAndBeginTransientCommandBuffer()` and registers the new buffer with
  `submissionBuilder.executeCommands(cb)` (bytecode ip 30–49). So **command
  buffers are per-segment, not per-frame and not per-pass**: one VkCommandBuffer
  runs until something ends it, and multiple render passes are recorded into the
  same buffer back-to-back.
- Segment enders (each calls `endCommandBuffer()` = `vkEndCommandBuffer` + null
  the field): `waitSemaphore(...)`, `signalSemaphore(...)`,
  **`execute(VkCommandBuffer)`** (public! ends the current buffer, then
  `submissionBuilder.executeCommands(yourCb)` — ip 18–27), and `submit()`.
- **`allocateAndBeginTransientCommandBuffer()` is public**: allocates from
  `commandPools[currentSubmitIndex % 2]` and calls `vkBeginCommandBuffer` with
  `flags(1)` = ONE_TIME_SUBMIT (bytecode ip 4–32). The pool is `reset()` when its
  index comes around again in `submit()` (ip 106–110), i.e. a buffer allocated
  this submit is valid until this submit's fence (timeline value) is waited,
  2 submits later.
- `submit()` bytecode order: `endCommandBuffer()` → `transientMemory.endSubmit()`
  → `signalSemaphore(submitSemaphore, currentSubmitIndex, 0x10000)`
  (`VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT`) → `submissionBuilder.close()` (this is
  the actual `vkQueueSubmit2` — `VulkanQueue$Submission.close()` buids one
  `VkSubmitInfo2` per stage with wait/signal `VkSemaphoreSubmitInfo`s) → new
  `graphicsQueue().beginSubmit()` → `currentSubmitIndex++` →
  `awaitSubmitCompletion(currentSubmitIndex - 2, 5_000_000_000L)` (5s timeout,
  else crash with checkpoint dump) → reset the now-free command pool → rotate
  destroy queue + checkpoint storage → `transientMemory.beginSubmit()`.
  **2 submits in flight, CPU throttled by timeline-semaphore wait.**
- `submit()` is NOT once-per-frame-only: anything may call
  `RenderSystem.getDevice().createCommandEncoder().submit()` mid-frame (the
  facade forwards); `Minecraft.renderFrame` guarantees one at frame end.

### 1.3 Render passes: dynamic rendering on the shared command buffer

`VulkanCommandEncoder.createRenderPass(RenderPassDescriptor)` bytecode:

- Collects color attachments (`VulkanGpuTextureView[]`) + optional depth
  attachment from the descriptor; derives `outputWidth/Height` from the first
  non-null attachment (ip 126–232).
- Builds `VkRenderingAttachmentInfo` per color attachment:
  `imageView(view.vkImageView())`, **`imageLayout(1)` =
  `VK_IMAGE_LAYOUT_GENERAL`**, `storeOp(0)` = STORE; `loadOp(1)` = CLEAR if the
  attachment's `clearValue` Optional is present (clear color via
  `VulkanUtils.putArgb`), else `loadOp(0)` = LOAD (ip 333–469). A null slot
  (from `withUnusedColorAttachment()`) gets `imageView(0)`, `loadOp(2)` =
  DONT_CARE, `storeOp(1)` = DONT_CARE.
- Depth: same shape — `imageLayout(1)` GENERAL, `storeOp(0)` STORE, `loadOp(1)`
  CLEAR iff `OptionalDouble` present (ip 558–678).
- `VkRenderingInfo`: `renderArea` from descriptor (assert non-null),
  `layerCount(1)`, `viewMask(0)` (ip 513–544).
- Then **`KHRDynamicRendering.vkCmdBeginRenderingKHR(commandBuffer(), info)`**
  (ip 688) — on the encoder's SHARED buffer, no new command buffer — and
  constructs `VulkanRenderPass(device, this, commandBuffer(), checkpointStorage,
  renderArea, outputWidth, outputHeight, hasDepth, label)`, stored in
  `currentRenderPass`. Only ONE pass can be open at a time (`commandBuffer()`
  throws `"Cannot start command buffer while inside RenderPass"`).
- `submitRenderPass()`: `vkCmdEndRenderingKHR(commandBuffer())` → clear
  `currentRenderPass` → **a full-pipeline sync2 memory barrier**
  (`vkCmdPipelineBarrier2KHR`, src/dst stage `0x10000` ALL_COMMANDS, src/dst
  access `0x18000` = MEMORY_READ|MEMORY_WRITE — `memoryBarrier` static, bytecode
  constants 65536/98304). **Invariant: after every vanilla pass ends, all memory
  is visible to everything.** Meshelium inherits this barrier for free after any
  pass it appends behind.

The facade (`systems.CommandEncoder.createRenderPass`) wraps the backend pass in
`systems.RenderPass` and flips `isInRenderPass`; `RenderPass.close()` →
`CommandEncoder.submitRenderPass()`. The three convenience `createRenderPass`
overloads build a `RenderPassDescriptor` internally — the descriptor path
(`RenderPassDescriptor.create(label).withColorAttachment(view, clear)
.withDepthAttachment(view, clear).withRenderArea(area)`) is public API Meshelium
can use for an own-pass.

### 1.4 Image layouts: everything lives in GENERAL

`VulkanGpuTexture` creation bytecode (`vmaCreateImage` then an immediate
`VkImageMemoryBarrier` with **`oldLayout(0)` UNDEFINED → `newLayout(1)`
GENERAL**, dstAccess `98304` = MEMORY_READ|MEMORY_WRITE, on
`textureInitCommandBuffer()`): every texture is transitioned to
`VK_IMAGE_LAYOUT_GENERAL` once at creation and **render passes attach it as
GENERAL** (1.3). No per-pass layout churn exists for offscreen targets. A
Meshelium-recorded pass over the same attachments needs no layout transitions.
(The only non-GENERAL traffic is the swapchain image inside
`VulkanGpuSurface.blitFromTexture` — not our problem; we never touch the
swapchain.)

### 1.5 Attachment formats in practice

- The frame is rendered into `GameRenderer.mainRenderTarget()` — a
  `com.mojang.blaze3d.pipeline.MainTarget`, whose ctor calls
  `RenderTarget.<init>(name, useDepth, **GpuFormat.RGBA8_UNORM**)` (bytecode:
  `getstatic GpuFormat.RGBA8_UNORM` before `invokespecial RenderTarget.<init>`).
  `RenderTarget` creates its depth texture with **`GpuFormat.D32_FLOAT`**
  (bytecode: `getstatic GpuFormat.D32_FLOAT` → `GpuDevice.createTexture`) and
  its color texture with the stored `format` field. **So the terrain pass
  Meshelium joins is `VK_FORMAT_R8G8B8A8_UNORM` color + `VK_FORMAT_D32_SFLOAT`
  depth** — via `VulkanConst.toVk(GpuFormat)` whose tableswitch (ordinal-indexed,
  all 56 cases read out and paired against the enum declaration order) maps
  `RGBA8_UNORM`(#7) → 37 = `VK_FORMAT_R8G8B8A8_UNORM` and `D32_FLOAT`(#52) →
  126 = `VK_FORMAT_D32_SFLOAT`.
- **Runtime discovery (do this instead of hardcoding):** any
  `GpuTextureView.texture().getFormat()` returns the `GpuFormat`;
  `VulkanConst.toVk(GpuFormat)` is `public static` — call it directly from mod
  code for `VkPipelineRenderingCreateInfo`. For wave 2, read the formats off the
  actual attachments of the pass we join (or `mainRenderTarget()`'s two views).
- Swapchain (for completeness; Meshelium never renders to it):
  `VulkanGpuSurface.pickSwapchainSurfaceFormat` iterates surface formats and
  takes the first with `colorSpace == 0` (SRGB_NONLINEAR) and `format == 37
  (R8G8B8A8_UNORM) || format == 44 (B8G8R8A8_UNORM)`, else throws "Could not
  find compatible swapchain format". Stored in `swapchainImageFormat` (private).
  `configure()` (re)creates the swapchain; `acquireNextTexture` /
  `blitFromTexture` / `present` manage acquire/present semaphore arrays.
- Sample count: `VulkanGpuTexture` creation and `VkRenderingInfo` carry no MSAA
  anywhere on this path (no `samples(...)` call sites in
  `VulkanCommandEncoder.createRenderPass`); pipelines are single-sample —
  confirmed from `VulkanRenderPipeline` in Q4.

### 1.6 What Q1 means for Meshelium

- The **frame seam** is `GameRenderer.render` (everything GPU happens inside)
  and the **submission seam** is `VulkanCommandEncoder` (public `execute()`,
  public `allocateAndBeginTransientCommandBuffer()`, singleton, reachable
  without mixins as `(VulkanCommandEncoder) something` — but note
  `RenderSystem.getDevice().createCommandEncoder().backend()` is `protected`;
  either an accessor mixin on `CommandEncoder` or a `VulkanDevice` handle
  captured at device-create time (wave 1's `MesheliumVulkanState` already sits at
  that seam) gives us the `VulkanDevice`, and
  `VulkanDevice.createCommandEncoder()` is public → the singleton encoder.
- Vanilla hands us: transient command buffers from its own pools (correct
  lifetime), ordered submission stages, an all-memory barrier after every pass,
  GENERAL image layouts, and public format mapping. We do NOT need our own
  VkCommandPool, fences, or layout tracking for wave 2.

---

## Q2 — Terrain draw recording

### 2.1 Census: the section-rendering lineage in 26.2

`net/minecraft/client/renderer/chunk/` (26 classes, full `unzip -l` census):
`SectionRenderDispatcher` (+ `RenderSection`, `RenderSection$SectionTask`/
`CompileTask`/`ResortTransparencyTask`, `RenderSectionBufferSlice`,
`SectionUberBuffers`), `SectionCompiler(+Results)`, `SectionMesh
(+SectionDraw)`, `CompiledSectionMesh`, `ChunkSectionLayer`,
`ChunkSectionLayerGroup`, `ChunkSectionsToRender`, `RenderSectionRegion`,
`RenderRegionCache`, `SectionCopy`, `TranslucencyPointOfView`,
`SectionTaskDynamicQueue`, `VisGraph`, `VisibilitySet`. Siblings in
`renderer/`: `SectionOcclusionGraph`, `Octree`, `ViewArea`,
`SectionBufferBuilderPack/Pool`, `LevelRenderer`, `DynamicUniforms`,
`RenderPipelines`, `BindGroupLayouts`; `renderer/culling/Frustum`.

Layer/group model (javap):

- **`ChunkSectionLayer` enum = SOLID, CUTOUT, TRANSLUCENT** (three, not
  1.21's five — tripwire is gone). Each carries `pipeline()
  → RenderPipeline`, `bufferSize()`, `translucent()`, `vertexFormat()`.
- **`ChunkSectionLayerGroup` enum = OPAQUE, TRANSLUCENT** with `layers()`
  (OPAQUE = [SOLID, CUTOUT] — inferred from renderGroup's per-layer loop and
  main-pass profiler names; enum ctor args UNVERIFIED-exact but the loop
  `group.layers()` is what matters). `outputTarget()` bytecode: ordinal 1
  (TRANSLUCENT) → `Minecraft.levelRenderer.translucentTarget()`, default →
  `gameRenderer.mainRenderTarget()`, null-falls-back to main. So **the
  translucent group renders into a SEPARATE target when the fabulous
  transparency chain is active** (created as frame-graph internal
  "translucent", same RGBA8 + depth descriptor), composited later by the
  `PostChain`.

Geometry storage: `SectionRenderDispatcher` holds `Map<ChunkSectionLayer,
SectionUberBuffers>` where `SectionUberBuffers = {UberGpuBuffer vertexBuffer,
UberGpuBuffer indexBuffer}` — **one uber vertex buffer + one uber index buffer
per layer**, sections sub-allocated inside (`getRenderSectionSlice(SectionMesh,
ChunkSectionLayer) → RenderSectionBufferSlice{vertexBuffer, vertexBufferOffset,
indexBuffer, indexBufferOffset}`). Uploads flow through a `StagingBuffer` and
`uploadTerrainBuffersToGpu()`, called from `LevelRenderer.render` AFTER the
frame graph executes (ip 647–651), under `lock()`/`unlock()`.

### 2.2 The frame, from `LevelRenderer.render`

`GameRenderer` (bytecode around ip 1457–1501 of its render path): builds the
projection (`ProjectionMatrixBuffer.getBuffer(Matrix4f) → GpuBufferSlice`,
`RenderSystem.setProjectionMatrix(slice, type)`), computes fog
(`fogRenderer.updateBuffer(fogData)`, `fogRenderer.getBuffer(FogMode.WORLD)` →
`GpuBufferSlice`), then calls `LevelRenderer.render(GraphicsResourceAllocator,
DeltaTracker, boolean, CameraRenderState, Matrix4fc modelView, GpuBufferSlice
fogBuffer, Vector4f clearColor, boolean renderSky)`.

`LevelRenderer.render` (bytecode, ips cited inline): `repositionCamera` →
`submitFeatures` (entities/block-entities → `SubmitNodeStorage`, CPU only) →
`featureRenderDispatcher.prepareFrame` → **builds a `FrameGraphBuilder`**
(ip 105): imports `main` (= `gameRenderer.mainRenderTarget()`), creates
internals `translucent`, `item_entity`, `particles`, `weather`, `clouds`
(RGBA8_UNORM + depth descriptor, ip 160–300, only when
`getTransparencyChain() != null`), imports `entity_outline`. Pass order added:
**"clear" → addSkyPass (opt) → `prepareChunkRenders(levelRenderState
.cameraRenderState.viewRotationMatrix)` (ip 367, on the spot) → addMainPass →
entity-outline PostChain → addCloudsPass → addWeatherPass → transparency
PostChain → addAlwaysOnTopPass → `FrameGraphBuilder.execute`** (ip 561–572).
After execute: `compileSections` → `sectionRenderDispatcher
.uploadTerrainBuffersToGpu()` → `sectionOcclusionGraph.update(cameraRenderState,
fov, chunkLoadingRenderState)` (ip 693–713) — **occlusion/BFS updates AFTER
rendering; `visibleSections` used by this frame was computed last frame.**

### 2.3 The main pass body (`LevelRenderer.lambda$addMainPass$0`)

Bytecode order (this synthetic private method is the mixin surface):

1. `RenderSystem.setShaderFog(fogBufferSlice)` (ip 1).
2. (Re)create `chunkLayerSampler` if needed (CLAMP_TO_EDGE/LINEAR, anisotropy
   per options, ip 18–82).
3. Profiler **"solidTerrain"**: `chunkSectionsToRender.renderGroup(
   ChunkSectionLayerGroup.OPAQUE, chunkLayerSampler)` (ip 94–103).
4. `Lighting.setupFor(LEVEL)`; optional entity-outline clear.
5. **"renderSolidFeatures"**: `preparedFrame.executeSolid()` (entities etc.).
6. `RenderTarget.copyDepthFrom(main)` into itemEntity/particles/weather
   targets (ip 186–270).
7. **"renderTranslucentFeatures"**: `executeTranslucent()`, `executeOutline()`.
8. Profiler **"translucentTerrain"**: `renderGroup(TRANSLUCENT,
   chunkLayerSampler)` (ip 304–313).
9. `executeTranslucentAfterTerrain()`.

### 2.4 How terrain draws are recorded (`ChunkSectionsToRender.renderGroup`)

Bytecode, complete:

- Shared index buffer: `RenderSystem.getSequentialBuffer(PrimitiveTopology
  .QUADS).getBuffer(maxIndicesRequired)` (the auto-grown quad→triangle index
  buffer; null if no draws).
- **Opens its own RenderPass**: `RenderSystem.getDevice().createCommandEncoder()
  .createRenderPass(label, group.outputTarget().getColorTextureView(),
  Optional.empty(), outputTarget.getDepthTextureView(), OptionalDouble.empty())`
  — **no clear values → loadOp LOAD on both attachments** (Q1.3). One pass per
  group: the OPAQUE pass contains both SOLID and CUTOUT draws.
- `RenderSystem.bindDefaultUniforms(pass)` — bytecode: `setUniform("Projection",
  getProjectionMatrixBuffer())`, `setUniform("Fog", getShaderFog())`,
  `setUniform("Globals", getGlobalSettingsUniform())`, `setUniform("Lighting",
  getShaderLights())`.
- `bindTexture("Sampler0", blockAtlasView, sampler)`, `bindTexture("Sampler2",
  gameRenderer.lightmap(), clamped-linear)`.
- Per layer in `group.layers()`: `pass.setPipeline(layer.pipeline())` (or
  `RenderPipelines.WIREFRAME` on debug hotkey), then per draw-group (an
  `Int2ObjectOpenHashMap<List<RenderPass$Draw>>` bucketed by
  vertex/index-buffer identity hash so buffers rebind once): TRANSLUCENT lists
  are `reversed()` (visibleSections is sorted near→far, so translucent draws
  far→near), then **the single draw verb: `pass.drawMultipleIndexed(draws,
  sharedIndexBuffer, indexType, List.of("ChunkSection"), chunkSectionInfos)`**.
  No other draw verb is used for terrain.
- Pass closed (`RenderPass.close()` → `submitRenderPass()` → end rendering +
  full barrier).

### 2.5 Per-section data & the matrix/fog/frustum plumbing

`LevelRenderer.prepareChunkRenders(Matrix4fc viewRotationMatrix)` bytecode:

- Iterates `visibleSections` (`ObjectArrayList<RenderSection>`, populated by
  `SectionOcclusionGraph`'s BFS the previous frame), takes
  `section.getSectionMesh()`, `section.getRenderOrigin()` (BlockPos), and per
  layer `mesh.getSectionDraw(layer)` + `dispatcher.getRenderSectionSlice(...)`.
- Per visible section it registers ONE `DynamicUniforms$ChunkSectionInfo(new
  Matrix4f(viewRotationMatrix), originX, originY, originZ,
  section.getVisibility(millis) /*fade-in*/, atlasW, atlasH)` (ip 261–304) —
  shared by all its layers' draws.
- Builds `RenderPass$Draw(slot=0, vertexBuffer, indexBuffer|null,
  indexType|null, firstIndex, indexCount, baseVertex, uploaderBiConsumer)`;
  `firstIndex = indexBufferOffset/indexType.bytes`, `baseVertex =
  vertexBufferOffset/vertexFormat.getVertexSize()`. The BiConsumer is
  `lambda$prepareChunkRenders$1`: `uploader.upload("ChunkSection",
  chunkSectionInfos[i])` — the per-draw dynamic uniform.
- After the loop: `RenderSystem.getDynamicUniforms().writeChunkSections(infos)`
  → `GpuBufferSlice[]` (one UBO slice per section out of a per-frame dynamic
  uniform ring; `DynamicUniforms.reset()` runs at end of frame in
  `Minecraft.renderFrame` ip 723).

**Where a mod reads the per-frame view state** — all public:

| Datum | Source |
|---|---|
| camera pos / block pos | `CameraRenderState.pos` / `.blockPos` (public fields; state object at `levelRenderState.cameraRenderState`, also passed to `LevelRenderer.render`) |
| view rotation matrix (world→view, camera-relative) | `CameraRenderState.viewRotationMatrix` (public `Matrix4f`) |
| projection matrix (CPU copy) | `CameraRenderState.projectionMatrix` (public `Matrix4f`) |
| projection UBO slice (GPU) | `RenderSystem.getProjectionMatrixBuffer()` → `GpuBufferSlice` |
| frustum | `CameraRenderState.cullFrustum` (public `net.minecraft.client.renderer.culling.Frustum`) |
| fog values (CPU) | `CameraRenderState.fogData` (`renderer/fog/FogData`) |
| fog UBO slice (GPU) | `RenderSystem.getShaderFog()` (set per-pass; the main pass sets it first thing) |
| depthFar | `CameraRenderState.depthFar` (public float) |
| per-section model-view + origin | vanilla uploads per-draw "ChunkSection" UBO from `DynamicUniforms$ChunkSectionInfo{modelView, x, y, z, visibility, atlasW, atlasH}` — Meshelium builds its own equivalent |

### 2.6 Injection points

**(a) Wave 2 — one extra draw right after opaque terrain.** Target
`LevelRenderer.lambda$addMainPass$0` (synthetic private instance method — a
plain `@Inject` target; exact descriptor in Q6's shopping list), `@Inject(at =
@At(value = "INVOKE", target = renderGroup, ordinal = 0, shift = AFTER))`.
At that point: the OPAQUE pass has been recorded AND closed (renderGroup closes
its pass), the encoder is between passes, fog/projection slices are bound-able,
and depth still holds only terrain. Meshelium opens its own pass over the same
attachments (Q3). Fallback target if lambda names get remapped weirdly:
`@Inject` at `ChunkSectionsToRender.renderGroup` TAIL, filtered to
`group == OPAQUE` — same recording position, but inside the class that owns
the draws.

**(b) Waves 4+ — full replacement of section draws.** Cleanest gate:
`@Inject(at = @At("HEAD"), cancellable = true)` on
**`ChunkSectionsToRender.renderGroup(ChunkSectionLayerGroup, GpuSampler)`** —
cancel when Meshelium owns terrain (per-group: cancel OPAQUE in wave 4, also
TRANSLUCENT in wave 7). Because the vanilla RenderPass is created INSIDE
renderGroup, cancelling skips pass creation, uniform binds and all draws in
one cut, and touches nothing else (features/entities/particles use their own
passes). The empty-render-list alternative (emptying `visibleSections`) is
WRONG here: `prepareChunkRenders`, `scheduleTranslucentSectionResort` and
block-entity collection all read it. Keep vanilla's `visibleSections`/BFS
alive (Meshelium wave 3+ reuses `SectionOcclusionGraph`'s output as its
section-liveness feed; SPEC decision 2 already leans on it), only the DRAWS
are cancelled.
Section-geometry interception for wave 3 happens upstream at
`SectionRenderDispatcher$RenderSection$CompileTask`/`SectionCompiler.compile`
results (meshes as `MeshData` per layer before upload) — mapped, not designed,
here.

## Q3 — Raw command access mid-pass

### 3.1 Where the live VkCommandBuffer lives

- `VulkanRenderPass` holds `private final org.lwjgl.vulkan.VkCommandBuffer
  commandBuffer` (set in the ctor from the encoder's shared buffer; private
  accessor `commandBuffer()`). **One accessor mixin on this field/method gives
  raw command access inside any pass, including passes Meshelium creates through
  the public API.**
- Equivalently, `VulkanCommandEncoder.currentCommandBuffer` (private) is the
  same object between `createRenderPass` and `submitRenderPass`.
- Outside a pass: `VulkanCommandEncoder.commandBuffer()` is private, but
  **`allocateAndBeginTransientCommandBuffer()` and `execute(VkCommandBuffer)`
  are public** (Q1.2) — Meshelium can record a fully private command buffer and
  splice it into the frame's submission in order, without touching vanilla's.

### 3.2 What state vanilla tracks (and what an interloper would owe back)

`VulkanRenderPass` per-pass state, all CPU-side (bytecode-proven):

- **Viewport/scissor**: set ONCE in the pass ctor —
  `vkCmdSetViewport(cb, 0, {0, 0, outputW, outputH, minDepth 0, maxDepth 1})` +
  `vkCmdSetScissor(renderArea)`. `enableScissor/disableScissor` re-issue
  scissor only. These are the ONLY dynamic states (see Q4).
- **Pipeline**: `setPipeline` → `device.getOrCompilePipeline(info)` →
  `vkCmdBindPipeline(cb, GRAPHICS(0), hasDepth ? withDepthPipeline :
  withoutDepthPipeline)` and sets `anyDescriptorDirty = true`. Vanilla does
  NOT track "currently bound VkPipeline" beyond its `pipeline` field — if
  foreign code rebinds the pipeline mid-pass, vanilla's subsequent draws in the
  SAME pass would run on the wrong pipeline until the next `setPipeline` call.
- **Descriptors**: `uniforms` HashMap (name → GpuBufferSlice) + `textures`
  HashMap (name → view+sampler) + `anyDescriptorDirty`. Flushed lazily by
  `pushDescriptors()` at every draw IF dirty: builds one `VkWriteDescriptorSet`
  per `VulkanBindGroupLayout.entries()` element (dstBinding = list index) and
  calls **`KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, GRAPHICS,
  pipeline.pipelineLayout(), set 0, writes)`** — vanilla uses PUSH descriptors
  exclusively; there are no descriptor pools/sets to collide with.
- **Vertex/index buffers**: bound per draw-group inside `drawMultipleIndexed`
  (which per Draw calls `setIndexBuffer`/`setVertexBuffer` → `vkCmdBindIndexBuffer`
  / `vkCmdBindVertexBuffers`) — self-repairing across foreign binds.

**Piggyback-in-pass cost:** after Meshelium's raw `vkCmdBindPipeline` +
`vkCmdPushDescriptorSetKHR`/`vkCmdBindDescriptorSets` inside a VANILLA pass,
vanilla would keep drawing without rebinding pipeline or re-pushing
descriptors (dirty flag is false) → corrupted draws, unless Meshelium re-binds
vanilla's pipeline handle and force-sets `anyDescriptorDirty` via accessors.
Doable, but it couples us to two private fields and vanilla's lazy-flush
timing.

### 3.3 The cleaner seam — own pass, same attachments (RECOMMENDED for wave 2)

Vanilla's OPAQUE terrain pass is created and CLOSED entirely inside
`ChunkSectionsToRender.renderGroup` (Q2.4). At our injection point (after that
call), no pass is open, and `submitRenderPass` has already emitted the
all-memory barrier. Meshelium then:

1. Creates its own pass through the PUBLIC abstraction:
   `RenderSystem.getDevice().createCommandEncoder().createRenderPass(() ->
   "meshelium terrain", mainTarget.getColorTextureView(), Optional.empty(),
   mainTarget.getDepthTextureView(), OptionalDouble.empty())` — loadOp LOAD on
   both attachments (Q1.3), GENERAL layouts (Q1.4), viewport/scissor set by
   the ctor, `vkCmdBeginRenderingKHR` issued by vanilla with the right
   attachment shapes. Nothing to save or restore: the pass is ours.
2. Reaches the raw buffer via the `VulkanRenderPass.commandBuffer` accessor
   mixin (the backend object comes from `CommandEncoder.backend()` — protected,
   one more trivial accessor — or by capturing `VulkanDevice` at wave 1's
   device seam and calling its public `createCommandEncoder()`).
3. Records `vkCmdBindPipeline(cb, GRAPHICS, meshPipeline)` +
   `vkCmdPushDescriptorSetKHR(cb, GRAPHICS, mesheliumLayout, 0, writes)` (push
   descriptors are available: `VK_KHR_push_descriptor` is in vanilla's device
   extension strings — VulkanBackend constant pool) + **`EXTMeshShader
   .vkCmdDrawMeshTasksEXT(cb, gx, gy, gz)`**. It must NOT call vanilla's
   `setPipeline` (two-stage only) — raw binds inside an otherwise-vanilla pass
   object are fine because vanilla records no draws into this pass.
4. `pass.close()` → vanilla ends rendering + emits the full barrier.

Weighing the three options:

| Option | Pros | Cons |
|---|---|---|
| piggyback inside vanilla's terrain pass | zero extra begin/end rendering | must restore pipeline + force descriptor dirty; couples to private lazy-flush; fragile |
| **own `RenderPass` via public API, same attachments (CHOSEN)** | no state restore at all; vanilla does begin/end + barrier + viewport/scissor; ~1 accessor mixin | one extra vkCmdBeginRendering/vkCmdEndRendering pair per frame (trivial) |
| fully own command buffer + `execute()` | total isolation; good for waves 4+ multi-pass recording | we own beginRendering/attachment info + barriers; splits vanilla's current command buffer (harmless but noisier) |

Wave 2 takes the middle path. Waves 4+ can graduate to
`allocateAndBeginTransientCommandBuffer()` + own `vkCmdBeginRenderingKHR` +
`execute(cb)` when Meshelium records multiple passes (occlusion, terrain,
translucency) back-to-back — that path is also bytecode-supported today
(both methods public, pool lifetime = 2 submits, Q1.2).

## Q4 — Pipeline story for mesh stages

All from `VulkanRenderPipeline.compile(VulkanDevice, VulkanBindGroupLayout,
RenderPipeline, long vertModule, long fragModule)` bytecode +
`VulkanBindGroupLayout.create` bytecode.

### 4.1 Vanilla's layout conventions

- **Descriptor set layout** (`VulkanBindGroupLayout.create`): one
  `VkDescriptorSetLayoutBinding` per entry, `binding = i` (list order),
  `descriptorCount = 1`, type by entry kind — ordinal 0 → `6`
  (UNIFORM_BUFFER), 1 → `1` (COMBINED_IMAGE_SAMPLER), 2 → `4`
  (UNIFORM_TEXEL_BUFFER) — and **`stageFlags = 17` = VERTEX|FRAGMENT
  (0x1|0x10)**. CreateInfo `flags = 1` =
  `VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR`.
  **Consequence: vanilla's set layouts can never serve mesh/task stages**
  (stage flags are wrong) — Meshelium MUST create its own
  `VkDescriptorSetLayout` with `TASK_EXT|MESH_EXT|FRAGMENT` stage bits. That
  is fully independent (`vkCreateDescriptorSetLayout` on the same VkDevice;
  no shared registry exists to conflict with).
- **Pipeline layout**: `VkPipelineLayoutCreateInfo{pSetLayouts = [one set
  layout], no push constants}` → `vkCreatePipelineLayout`. Set 0 is the only
  set. Meshelium's own pipeline layout is likewise free-standing.
- Device extensions available for our layouts (string constants in
  `VulkanBackend.class`): `VK_KHR_push_descriptor`, `VK_KHR_dynamic_rendering`,
  `VK_KHR_synchronization2`, `VK_KHR_swapchain`, optional `VK_EXT_multi_draw`,
  `VK_EXT_vertex_attribute_divisor`, `VK_AMD_buffer_marker`,
  `VK_KHR_portability_subset`. (Which are REQUIRED vs optional is in the seam
  doc Q2; push_descriptor must be required-tier since every draw depends on
  it.)

### 4.2 What a pipeline must look like to live in vanilla's pass

From `compile` (every op cited is in the bytecode dump):

- **Dynamic state: exactly `{VK_DYNAMIC_STATE_SCISSOR(1),
  VK_DYNAMIC_STATE_VIEWPORT(0)}`** (`stack.ints(1, 0)` →
  `pDynamicStates`). The pass sets both at creation (Q3.2), so a Meshelium
  pipeline that declares the same two dynamic states inherits correct
  viewport/scissor with zero calls. Declare both, or set your own.
- **`VkPipelineRenderingCreateInfoKHR`** chained via `pNext` on
  `VkGraphicsPipelineCreateInfo`: `pColorAttachmentFormats` = per color target
  `VulkanConst.toVk(state.format())`; **`depthAttachmentFormat = 126`
  (`bipush 126` = `VK_FORMAT_D32_SFLOAT`) — hardcoded**, matching
  `RenderTarget`'s D32_FLOAT depth. Two variants are built:
  `withDepthPipeline` (format 126) always; `withoutDepthPipeline`
  (`depthAttachmentFormat(0)`) ONLY when the `RenderPipeline` has no
  depth-stencil state; `setPipeline` picks by the pass's `hasDepth`.
  For Meshelium wave 2: one pipeline, color `VK_FORMAT_R8G8B8A8_UNORM` (37) +
  depth `VK_FORMAT_D32_SFLOAT` (126), read at runtime per Q1.5.
- **Multisample: `rasterizationSamples(1)`, `sampleShadingEnable(false)`** —
  single-sample everywhere.
- Rasterization: `frontFace(1)` = **CLOCKWISE**, `cullMode` = `isCull() ? 2
  (BACK) : 0`, polygon mode from pipeline, `lineWidth(1.0)`; depth bias from
  DepthStencilState when nonzero.
- Depth-stencil: `depthTestEnable(true)` iff state present, write flag +
  compare op from state. Blend: per-target `colorWriteMask` +
  `applyBlendInformation(BlendFunction)`; viewport state
  `{viewportCount 1, scissorCount 1}`.
- Stages: two `VkPipelineShaderStageCreateInfo`, entry point `"main"`, stages
  1 (VERTEX) and 16 (FRAGMENT); `vkCreateGraphicsPipelines` with null pipeline
  cache. Meshelium's raw pipeline swaps these for TASK_EXT (0x40) / MESH_EXT
  (0x80) / FRAGMENT stages and omits
  `pVertexInputState`/`pInputAssemblyState` (mesh pipelines have none) —
  everything else (rendering info, dynamic state, multisample, blend shape)
  copies vanilla's conventions above.

## Q5 — shaderc from mod code

### 5.1 The artifact, the constants, the natives

- **Classpath artifact: `org.lwjgl:lwjgl-shaderc:3.4.1`** (gradle cache:
  `~/.gradle/caches/modules-2/files-2.1/org.lwjgl/lwjgl-shaderc/3.4.1/…/lwjgl-shaderc-3.4.1.jar`,
  alongside `lwjgl-spvc`, `lwjgl-vulkan`, `lwjgl-vma`, all 3.4.1 — 26.2's
  LWJGL generation; 3.3.2/3.3.3 in the cache belong to older MC versions of
  other projects).
- **`org.lwjgl.util.shaderc.Shaderc` HAS the mesh stages** (javap -constants
  on the actual class): `shaderc_task_shader = 26`, `shaderc_mesh_shader = 27`,
  `shaderc_glsl_task_shader = 26`, `shaderc_glsl_mesh_shader = 27`,
  `shaderc_glsl_default_task_shader = 28`, `shaderc_glsl_default_mesh_shader
  = 29`.
- **Dev runtime natives: PRESENT** — gradle resolved
  `lwjgl-shaderc-3.4.1-natives-windows.jar` (contains
  `windows/x64/org/lwjgl/shaderc/shaderc.dll`, 6.2 MB) plus windows-x86 and
  windows-arm64 classifiers into the cache for this workspace; LWJGL 3.4
  extracts natives from classpath jars at runtime (the empty
  `.gradle/loom-cache/natives/26.2/` dir is normal — nothing pre-extracts).
- **Production natives: VERIFIED on this machine.** The vanilla launcher's
  `%APPDATA%/.minecraft/versions/26.2/26.2.json` lists `lwjgl-shaderc:3.4.1`
  with natives classifiers for linux, macos, macos-arm64, windows,
  windows-arm64, windows-x86, and
  `.minecraft/libraries/org/lwjgl/lwjgl-shaderc/3.4.1/` exists on disk. The
  game itself compiles all Vulkan shaders through shaderc at runtime
  (GlslCompiler below), so the natives are load-bearing in production, not
  optional.
- Remaining UNVERIFIED (carried from seam Q5): whether the shipped native
  `shaderc.dll`'s compiler accepts kinds 26/27 with `GL_EXT_mesh_shader`
  sources (the Java constants prove the BINDING, not the native build's
  feature set — near-certain for a 2026 build, but the caps probe's trial
  task/mesh compile settles it on real hardware).

### 5.2 What GlslCompiler does (and what Meshelium needs of it)

`GlslCompiler.<init>` bytecode: `shaderc_compiler_initialize()`;
`shaderc_compile_options_initialize()`;
`shaderc_compile_options_set_target_env(opts, 0 /*vulkan*/, 4202496
/*=0x402000 = env_version_vulkan_1_2*/)`;
`set_auto_bind_uniforms(true)`; `set_auto_map_locations(true)`;
`set_generate_debug_info()`; `set_optimization_level(0)`; global defines
`gl_VertexID→gl_VertexIndex`, `gl_InstanceID→gl_InstanceIndex`.

`createIntermediary(String name, String source, ShaderType)` bytecode:
`GlslPreprocessor.injectDefines(source, globalDefines)` → kind = (type ==
FRAGMENT ? 1 : 0) → `shaderc_compile_into_spv(compiler, sourceUtf8, kind,
nameUtf8, "main", opts)` → status != 0 throws `ShaderCompileException(
shaderc_result_get_error_message)` → copies `shaderc_result_get_bytes` →
`IntermediaryShaderModule.createFromSpirv(name, spirv)` (SPIRV-Cross
reflection for its two-stage pipeline plumbing) → frees everything.

**Meshelium's need: none of the preprocessor.** For single-file GLSL with
explicit `layout(set=0, binding=N)` qualifiers, the whole recipe is:
compiler + options with `set_target_env(vulkan, env_version_vulkan_1_2)`,
`shaderc_compile_into_spv(..., shaderc_mesh_shader /*27*/ or
shaderc_task_shader /*26*/ or shaderc_fragment_shader, ..., "main", opts)`,
check status, `vkCreateShaderModule`. Vanilla's auto-bind/auto-map options
exist to serve unannotated GL-dialect shaders — explicit bindings make them
unnecessary. `#moj_import` is vanilla's include system
(`blaze3d/preprocessor/`); Meshelium's shaders don't use it. (Design note, not
bytecode: mesh shaders want SPIR-V ≥1.4; target env vulkan_1_2 implies
SPIR-V 1.5, so no extra `set_target_spirv` call is needed.)

## Q6 — Wave-2 "hello meshlet" plan

**Goal restated:** one visible mesh-shader-drawn triangle in a screenshot on
the Vulkan backend, recorded through/alongside vanilla's encoder.

### The plan

1. **Injection point:** `LevelRenderer.lambda$addMainPass$0`, `@At(value =
   "INVOKE", target = ChunkSectionsToRender.renderGroup, ordinal = 0,
   shift = AFTER)` — right after the OPAQUE terrain pass closes (Q2.6a).
   Guarded by the wave-1 gate (Vulkan + mesh shaders + not force-disabled).
2. **Pass:** open our own `RenderPass` over
   `gameRenderer.mainRenderTarget()`'s color+depth views via the public
   encoder API (no clears → LOAD), grab the raw `VkCommandBuffer` through the
   `VulkanRenderPass.commandBuffer` accessor, record
   `vkCmdBindPipeline` + (for world-space) `vkCmdPushDescriptorSetKHR` +
   `vkCmdDrawMeshTasksEXT(cb, 1, 1, 1)`, close the pass (Q3.3). Vanilla
   supplies begin/end rendering, viewport/scissor, layouts, and the
   after-pass barrier.
3. **Pipeline creation timing: lazily on first injection hit**, cached for
   device lifetime. NOT at device-create — `mainRenderTarget()`'s textures
   don't exist yet during device creation (RenderTarget allocates on resize),
   and the injection point is the first moment formats can be read off the
   real attachments (`view.texture().getFormat()` → `VulkanConst.toVk`,
   Q1.5). Compile failures flip the mod's gate off with a log, never crash
   the frame.
4. **Shaders:** `assets/meshelium/shaders/hello.mesh` + `hello.frag`
   (mesh+fragment ONLY — `VK_EXT_mesh_shader` permits pipelines without a
   task stage; the task stage joins in wave 5 for culling). Loaded as mod
   resources, compiled via shaderc kinds `shaderc_mesh_shader = 27` and
   `shaderc_fragment_shader = 1` (javap -constants, same class as 5.1).
   `#version 460` + `#extension GL_EXT_mesh_shader : require`.
5. **NDC first, world-space second — do both inside wave 2.**
   - *Step A (NDC):* mesh shader emits one hardcoded clip-space triangle;
     depth test/write disabled in our pipeline; ZERO descriptors (an empty
     descriptor set layout list in the pipeline layout is legal). Proves:
     compile → module → pipeline → pass → draw → screenshot.
   - *Step B (world-space):* one 64-byte UBO holding `MVP =
     cameraRenderState.projectionMatrix * cameraRenderState.viewRotationMatrix`
     (both public CPU-side `Matrix4f`s — Q2.5), triangle vertices supplied
     camera-relative (CPU subtracts `cameraRenderState.pos`). The UBO rides
     `CommandEncoder.transientMemory()` (public; per-submit lifetime, exactly
     one frame — Q1.2/vulkan-sigs) — no persistent buffer management at all.
     **Matrix-plumbing cost quantified: ~64 bytes/frame + one
     `transientMemory().uploadGpu(...)` call + one push-descriptor write.**
     Vanilla's own terrain math for reference (verbatim,
     `assets/minecraft/shaders/core/terrain.vsh` + include UBOs
     `chunksection.glsl`/`globals.glsl`/`projection.glsl`):
     `pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
     gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0)` with std140 UBOs
     `ChunkSection{mat4 ModelViewMat; float ChunkVisibility; ivec2
     TextureSize; ivec3 ChunkPosition}`, `Globals{ivec3 CameraBlockPos; vec3
     CameraOffset; …}`, `Projection{mat4 ProjMat}` — wave 4 will reuse this
     split-integer scheme; wave 2's single CPU-composed MVP is enough for a
     triangle.
   Recommendation: land Step A as the wave-2 "done" gate (screenshot), keep
   Step B in the same wave because it de-risks wave 3/4's only real unknown
   (matrix plumbing) for one UBO's worth of work.
6. **Cleanup:** pipeline/layout/shader modules are device-lifetime objects —
   destroy on client shutdown (and on `clearPipelineCache`-style resource
   reload only if wave 8 finds it fires; not a wave-2 concern). Resize needs
   NOTHING (viewport/scissor are dynamic, formats are resolution-independent,
   the per-frame UBO is transient). World unload needs NOTHING (the injection
   only fires inside the main pass, which only exists in-world; no per-world
   GPU state exists in wave 2). Per-frame buffers via `transientMemory()`
   free themselves; anything else goes through the public
   `VulkanCommandEncoder.queueForDestroy(Destroyable)` (deferred-safe).

### Mixin shopping list (wave 2)

| # | Target | Kind | Purpose |
|---|---|---|---|
| 1 | `LevelRenderer.lambda$addMainPass$0(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lcom/mojang/blaze3d/resource/ResourceHandle;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;)V` | `@Inject` at `@At("INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V", ordinal = 0, shift = AFTER)` | record the hello-meshlet pass after opaque terrain |
| 2 | `VulkanRenderPass.commandBuffer()` (private, `()Lorg/lwjgl/vulkan/VkCommandBuffer;`) or the final field of the same name | `@Invoker`/`@Accessor` | raw VkCommandBuffer of OUR pass |
| 3 | `CommandEncoder.backend()` (protected, `()Lcom/mojang/blaze3d/systems/CommandEncoderBackend;`) | `@Invoker` — OR skip: wave 1's `MesheliumVulkanState` already holds the `VulkanDevice`, whose public `createCommandEncoder()` returns the singleton `VulkanCommandEncoder` | reach the Vulkan encoder/pass backends behind the facades |
| 4 | *(waves 4+, listed for planning)* `ChunkSectionsToRender.renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V` | `@Inject` HEAD, cancellable | cancel vanilla terrain draws per group when Meshelium owns terrain |

Fallback for row 1 (if the synthetic lambda name proves unstable in the dev
remap): `@Inject` at TAIL of `ChunkSectionsToRender.renderGroup`, filtered to
`group == ChunkSectionLayerGroup.OPAQUE` — same recording position from
inside the callee.

## Wave-2 implementation notes (deviations from Q6, 2026-08-09)

Wave 2 shipped per the Q6 plan — injection point, own-pass flow, lazy
pipeline timing, transient-memory UBO, push descriptors all as written.
Four deviations, each with its evidence:

1. **26.2 is REVERSED-Z — Q6's depth assumptions were wrong.** The frame
   graph clears depth to **0.0** (`dconst_0` at both
   `clearColorAndDepthTextures` call sites in `LevelRenderer`, bytecode) and
   vanilla's depth-tested pipelines use
   **`CompareOp.GREATER_THAN_OR_EQUAL`** (14 `getstatic` sites in
   `RenderPipelines`, vs 2 EQUAL and zero LESS-family). `VulkanConst.toVk`
   maps it to `VK_COMPARE_OP_GREATER_OR_EQUAL` (6). So the hello pipelines
   use depth test ENABLED, compare **GREATER_OR_EQUAL**, write OFF — not
   the LESS_OR_EQUAL a standard-Z frame would want, and not Q6 step A's
   "depth test disabled" either (test-on-write-off gives the intended
   "visible against sky, occluded by near terrain, vanilla depth
   untouched": sky sits at depth 0.0, the NDC triangle at 0.5 wins there
   and loses to near terrain). Wave 4+ parity work MUST inherit GEQUAL +
   reversed-Z projection conventions.
2. **Backend reachability: pass-side accessors instead of shopping-list
   row 3.** Rather than an invoker for the protected
   `CommandEncoder.backend()`, wave 2 reads `systems.RenderPass`'s
   `private final RenderPassBackend backend` field (javap-verified) via a
   `RenderPassAccessor` mixin, and takes `VulkanRenderPass`'s private
   `commandBuffer` AND `device` fields via `VulkanRenderPassAccessor` —
   one hop shorter, and the `device` accessor hands the lazy pipeline
   build its `VkDevice` at exactly the seam that already has the pass.
   Row 3's device-capture alternative remains open for waves 4+.
3. **The frag stage passes through a per-vertex colour instead of
   hardcoding magenta.** Both mesh shaders write `location = 0` vec4
   (magenta from `hello.mesh`, yellow from `hello_world.mesh`) and one
   shared `hello.frag` outputs it — so the NDC and world-anchored
   triangles are distinguishable in a single screenshot. The NDC
   triangle, the wave's acceptance, is still solid loud magenta.
4. **`TransientMemory.uploadGpu` parameter semantics pinned.** The 3-arg
   default `uploadGpu(ByteBuffer, long, int)` forwards to the 5-arg
   abstract as `(data, alignment, usageFlags, data.remaining(), 1)`
   (interface default-method bytecode); wave 2 passes alignment 256 (the
   spec's max `minUniformBufferOffsetAlignment` — always sufficient, no
   limits query) and `GpuBuffer.USAGE_UNIFORM` (128, javap -constants).
   The returned slice's buffer is a `VulkanTransientMemory$TransientGpuBuffer
   extends VulkanGpuBuffer`, so public `vkBuffer()` feeds the
   `VkDescriptorBufferInfo` directly.

One addition, not a deviation: LWJGL 3.4.1's
`VkGraphicsPipelineCreateInfo.validate` bytecode only validates
`pVertexInputState`/`pDynamicState` when non-NULL (`memGetAddress == 0 →
skip`), so a mesh pipeline may leave `pVertexInputState` and
`pInputAssemblyState` NULL through the Java binding — no struct-level
requiredness stands in the way (the Vulkan spec ignores both members when
a mesh stage is present).

## Wave-4 implementation notes (2026-08-09) — the opaque-terrain replacement

Wave 4 shipped per Q2.6(b): `@Inject(HEAD, cancellable = true)` on
`ChunkSectionsToRender.renderGroup(ChunkSectionLayerGroup, GpuSampler)`
(`ChunkSectionsToRenderMixin`), cancelled ONLY when wave-1 gate =
VULKAN_MESH_SHADERS ∧ `meshelium.terrainDraw` (a system property RE-READ on
every call, so the harness flips it live for the vanilla-twin shot) ∧
group == OPAQUE ∧ `TerrainDrawer.drawOpaque` succeeded. TRANSLUCENT stays
vanilla until wave 7 — the mixed frame is correct by construction: our
opaque pass writes depth (GEQUAL, write ON — reversed-Z per the wave-2
notes) and vanilla's translucent group tests against exactly that buffer.
Drawer failure latches off and leaves renderGroup uncancelled — vanilla's
dual-phase buffers still build/upload every frame, so terrain never
disappears on error. Notes where reality refined the plan:

1. **The parity source is vanilla's own shader text, not Nvidium's.** The
   wave-4 brief suggested porting Nvidium's fog.glsl (smoothstep,
   spherical/cylindrical pick) — but 26.2's real terrain fragment path,
   dumped verbatim from the jar (`assets/minecraft/shaders/core/terrain.
   {vsh,fsh}` + `include/{fog,globals,sample_lightmap,chunksection,
   projection}.glsl`), uses **linear** fog =
   `max(linear(spherical, envStart, envEnd), linear(cylindrical, rdStart,
   rdEnd))` mixed by `fogValue * FogColor.a`, and samples the atlas through
   `sampleNearest`/`sampleRGSS` (texel-center correction + optional rotated
   grid supersampling — `Globals.UseRgss`), not a plain `texture()`.
   `terrain.frag` therefore ports VANILLA's fsh + fog.glsl verbatim
   (`sampleNearest`, `sampleRGSS`, `apply_fog`); Nvidium contributes the
   mesh-side quad expansion only. Pixel parity is the acceptance bar and
   vanilla's text is the parity source of truth.
2. **Vanilla's own GPU slices are bound directly** into Meshelium's
   descriptor set (stage flags MESH/FRAGMENT as needed — vanilla's layouts
   can't serve mesh stages, Q4.1, so the DSL is ours): `RenderSystem
   .getProjectionMatrixBuffer()` (the exact ProjMat bytes vanilla's vsh
   reads), `.getShaderFog()` (set first thing in the main pass, Q2.3, so
   current at renderGroup time), `.getGlobalSettingsUniform()` (UseRgss);
   the **atlas sampler is the very `GpuSampler` vanilla passed into the
   cancelled renderGroup call** (captured mixin argument), the atlas view
   is `ChunkSectionsToRender.textureView()` (public record accessor), the
   lightmap is `gameRenderer.lightmap()` + `RenderSystem.getSamplerCache()
   .getClampToEdge(LINEAR)` — the same call renderGroup makes (bytecode ip
   145–151). Meshelium adds only an 80-byte scene UBO (viewRotationMatrix +
   atlas dims) from transient memory and 20 push-constant bytes per draw.
   No BUFFER_DEVICE_ADDRESS anywhere: the arena binds as a std430 SSBO
   (the wave-3b constraint).
3. **Vertex math mirrors terrain.vsh operation-for-operation**:
   `ProjMat * (ModelViewMat * vec4(decodedLocal + originRelCamera, 1.0))`
   with `originRelCamera = sectionOrigin − camera.pos` computed in doubles
   on the CPU — which sidesteps ledger item 5 entirely (the
   `CameraOffset` formula is now also pinned by the verbatim vsh dump:
   `pos = Position + (ChunkPosition − CameraBlockPos) + CameraOffset`).
   Per-vertex lightmap multiply and per-vertex fog distances are computed
   in the MESH stage exactly where vanilla's vertex stage computes them,
   then interpolated identically.
4. **The lightmap "+8 centring" assumption is VERIFIED**: vanilla's
   `sample_lightmap` = `texture(lm, clamp((uv2/256) + 0.5/16, 0.5/16,
   15.5/16))`; Meshelium stores `coord + 8` clamped [8,248], so
   `vec2(stored)/256` reproduces vanilla's clamped UV bit-for-bit
   (discharges VANILLA-SECTION-BUILD.md ledger 8).
5. **SetMeshOutputsEXT restructure came out simpler than the study's
   worst case** (arch §4/§10 row 2): wave 4 has no per-quad culling, so
   the counts are uniform by construction — derived from push constants +
   `gl_WorkGroupID` only — one `SetMeshOutputsEXT(wgQuads*4, wgQuads*2)`,
   then threads over the count return without writing. No subgroup ops at
   all this wave.
6. **Workgroup shape: 32 quads = 128 vertices / 64 primitives per
   workgroup**, one thread per quad, within every VK_EXT_mesh_shader
   spec minimum (maxMeshWorkGroupInvocations ≥ 128, maxMeshOutput* ≥ 256)
   and re-asserted at pipeline creation against the REAL device's wave-1
   caps (`MesheliumVulkanState.caps()`, values logged alongside the choice).
   The size is the host-injected shaderc macro **`MESHELIUM_WG_SIZE`** —
   deliberately a macro, not a SPIR-V specialization constant: shaders
   compile at runtime anyway, so a macro gives wave 9 identical
   per-vendor tunability without betting on `LocalSizeId` support in the
   shipped shaderc/driver pair. (Raising it above 32 must also raise the
   derived `max_vertices/max_primitives` in terrain.mesh — they are
   `4*/2*` the macro.)
7. **Draw-list strategy**: `TerrainResidency.drawSnapshot(epoch)` — an
   additive, epoch-cached flat-int view of the resident store (rebuilt
   only when the resident set changes); per frame the drawer
   frustum-culls each section's full 16³ AABB against
   `CameraRenderState.cullFrustum.isVisible(AABB)` (bytecode: world-space
   AABB, camera subtracted internally) and gates the 7 facing buckets by
   the `QuadFacing.visibleFrom` camera-side signs, merging adjacent
   visible buckets into contiguous quad runs (zero-count buckets never
   break a run; the translucent prefix is excluded because bucket 0
   starts after it). One `vkCmdPushConstants` + `vkCmdDrawMeshTasksEXT
   (ceil(quads/32),1,1)` per run; SOLID and CUTOUT share one pipeline —
   the wave-3 stream interleaves them inside each facing bucket, and the
   per-primitive material bits reproduce vanilla's per-pipeline
   ALPHA_CUTOUT thresholds (0 → none, 0.5 → CUTOUT_TERRAIN's define).
   Draw order is arbitrary (depth-tested opaque); front-to-back sorting
   is a wave-5 perf item, not a correctness one.
8. **Cull state proven, not assumed**: `RenderPipeline$Builder.build()`
   defaults cull to TRUE (`iconst_1` + `Optional.orElse`, bytecode) and
   the SOLID/CUTOUT terrain builders never call `withCull` — so the
   pipeline culls BACK with frontFace CLOCKWISE (Q4.2), and the mesh
   shader emits vanilla's exact `{0,1,2, 2,3,0}` quad split so the
   winding matches vanilla's raster.
9. **Known parity deviations, all deliberate and bounded** (the parity
   harness quiesces before its screenshots, which retires the first two):
   (a) `ChunkVisibility` (per-section fade-in) ≡ 1.0 — a freshly built
   section skips its sub-second fade; (b) sections uploaded this frame
   first draw next frame — the same one-frame latency vanilla itself has
   (Q1.6); (c) **no BFS occlusion feed yet**: Meshelium draws every
   frustum-visible RESIDENT section, including ones vanilla's
   `SectionOcclusionGraph` would skip — occluded sections resolve
   identically through the depth test, but a section RETAINED after its
   chunk unloaded (recon Q4.3) would still be drawn until its slot
   repositions; wave 5 adopts the BFS/visibility feed; (d) vertex alpha
   is not stored (16-byte format drops it) — opaque terrain vertex alpha
   is assumed 255, so `color.a` = texture alpha alone; (e) UV quantization
   error ≤ 1/65536 ≈ 0.016 texel on a 1024² atlas; (f) the harness's two
   shots are frames apart, so animated atlas sprites (water/lava) differ
   locally between them — a compare-time note, not a renderer diff.

## Wave-5 implementation notes (2026-08-09) — GPU culling via task shaders

Wave 5 moved the wave-4 per-section CPU cull onto the task stage, adopted
vanilla's BFS visibility as a per-region bitmask feed, and turned the draw
loop into per-region dispatches. Same gates (`VULKAN_MESH_SHADERS` ∧
`meshelium.terrainDraw`), same pass position, same mesh/fragment pixel math —
wave 4's parity ports forward untouched; only WHO decides what draws moved.

1. **Dispatch shape.** One `vkCmdDrawMeshTasksEXT(ceil(count/32), 1, 1)`
   per CPU-frustum-visible region, where `count` is the region's DENSE
   compacted-slot count from the CPU store (tighter than Nvidium's GPU-side
   `lastIdx+1` — the GPU record only carries the highest occupied POSITION
   index, but our CPU knows the slot count and slots are dense; trailing
   slots stay zeroed either way, so the task shader's `header.w == 0` check
   is a second line of defense, not the primary). Task workgroup =
   **`MESHELIUM_TASK_WG_SIZE` = 32 sections, one invocation each** (a small
   per-section loop was rejected: it serializes record loads without
   saving anything at this size). Justification against the 9070 XT caps
   (wave-1 probe): 32 ≤ maxTaskWorkGroupInvocations 1024; the
   device-preferred 1024 invocations would allow a whole-region 256-wide
   workgroup on THIS card, but 256 > the spec-guaranteed 128 invocations
   and its payload (256 × 80 B = 20 KiB) breaks the spec-minimum
   `maxTaskPayloadSize` 16384 — so 32 is the portable default and the
   macro is the wave-9 per-vendor knob. Push constants (TASK|MESH, 20 of
   32 declared bytes): `vec3 OriginRelCamera` (region origin − camera,
   CPU doubles), `uint RegionIndex` (Meshelium region id = index into the
   section-records buffer), `uint MaskSlot` (visibility-UBO slice, or
   0xFFFFFFFF = no mask).

2. **Task payload, verbatim** (`terrain.task` = writer, `terrain.mesh`
   reader; both declare it identically):

   > `taskPayloadSharedEXT { MesheliumSectionTask sections[32]; }` where
   > `MesheliumSectionTask` = `uint groupEnd` (AFTER the thread-0 prefix
   > pass: cumulative mesh-workgroup end over the task workgroup's
   > sections); `uint posAndCount` (bits 0-7 posKey within the region —
   > y at 6-7, z at 3-5, x at 0-2, RegionStore.posKey's packing; bits
   > 8-31 surviving quad count); `uvec4 binIa`, `binIb` (cumulative
   > surviving-quad END index of up to 8 dense-packed facing bins; unused
   > bins hold 0); `uvec4 binVa`, `binVb` (ABSOLUTE terrain-arena quad
   > index of each bin's start = header.w + cumulative bucket offset).
   > 72 B/section tight, budgeted 80 (16-byte struct rounding unproven),
   > × 32 sections ≤ 2560 B — vs spec-min maxTaskPayloadSize 16384,
   > asserted against the REAL device's cap at pipeline creation (the
   > probe logs maxTaskPayloadSize since this wave). Mesh workgroup g
   > finds its section by binary-searching groupEnd (5 steps over 32),
   > its quad by the linear 8-bin walk — Nvidium's mesh.glsl:46-69
   > consumption pattern, which is WHY the payload is per-section bins
   > and not per-mesh-group {base,count} entries: renderRanges carries
   > u16 quad counts per bucket, so one section can legally demand
   > thousands of mesh groups; any fixed per-group array needs a drop
   > path, and dropped quads are a parity violation. wgQuads stays
   > uniform (payload + gl_WorkGroupID only) so the wave-4
   > SetMeshOutputsEXT contract is unchanged.

3. **EXT restructure of the task stage** (arch §4/§10 row 2 discharged
   for the task side): no early returns anywhere — every invocation
   reaches `barrier(); [thread-0 prefix sum]; barrier();
   EmitMeshTasksEXT(total,1,1)` with a workgroup-uniform argument.
   Culled/empty sections contribute `groups = 0` and a flat groupEnd,
   which the mesh-side binary search can never select. No subgroup ops:
   the only cross-invocation step is a 32-element serial prefix by
   invocation 0 — zero portability assumptions, revisit in wave 9.

4. **Visibility feed — the tap.** `LevelRenderer.visibleSections()`
   (public, javap-verified: `ObjectArrayList<RenderSection>`) read in
   `drawOpaque` on the render thread. Timing proof of same-frame
   equality: the list this frame's draws use was produced by LAST frame's
   `sectionOcclusionGraph.update` (render ip 693-713, AFTER rendering),
   `prepareChunkRenders` consumed it earlier inside THIS
   `LevelRenderer.render` call, and nothing mutates it between that and
   the main-pass lambda where the kill switch fires — so the mask is
   built from EXACTLY the list vanilla would have drawn. Per section:
   `getSectionNode()` → `SectionPos.x/y/z` → region key + posKey →
   1 bit. Per-frame CPU cost: one hash lookup + one OR per visible
   section (~thousands), one `Arrays.fill` over 8×liveRegions ints, and
   32 B per dispatched region copied into the upload buffer —
   microseconds, measured by the new breadcrumb. **Descriptor:** the
   masks travel as a per-frame **UBO** slice (binding 8, TASK stage,
   `uvec4[1024]` = 16 KiB = spec-min `maxUniformBufferRange`) out of
   `encoder.transientMemory()` — vanilla's `bufferUsageToVk` cannot mint
   STORAGE usage (wave-3b finding), and 16 KiB/frame through transient
   memory is noise. Regions beyond the 512-slot capacity dispatch with
   `MaskSlot = 0xFFFFFFFF`: the task stage then treats every section as
   visible — **overflow fails open** (draws more, never less), counted in
   `maskOverflowRegions`. Binding 7 (TASK, SSBO) is the wave-3b
   section-records buffer, whose VkBuffer handle now rides the
   residency snapshot next to the arena handle (same lock, same
   no-mixed-eras rule). Wave 6 replaces this feed as the CULLING source
   with the GPU-rasterised occlusion scheme (Nvidium's own architecture);
   the feed stays as the correctness fallback.

5. **What the CPU still culls:** whole regions (128×64×128 AABB against
   `cam.cullFrustum.isVisible` — a few hundred tests), plus regions whose
   mask came out all-zero are skipped without a draw. Front-to-back
   region ordering (Nvidium's overdraw sort) remains future perf work —
   correctness never depended on it.

6. **Frustum + distance parity argument.** The task stage tests the
   section's full 16³ box (wave-4 precedent), inflated 0.5 blocks,
   against the six Gribb-Hartmann planes of ProjMat·ModelViewMat — the
   RENDER matrices. The construction and test replicate vanilla's
   machinery exactly (disassembly of `Frustum.calculateFrustum` →
   `matrix = projection * modelView` → JOML `FrustumIntersection.set`,
   whose plane formulas and p-vertex `dot(n,p) >= -w` test were read
   from the class file; JOML's normalization is a positive scale,
   dropped as test-invariant; `isVisible` = "not fully outside any
   plane" = our keep condition) — but the PROJECTION input deliberately
   differs: vanilla's cull frustum is built from
   `Camera.createProjectionMatrixForCulling()` (bytecode: fov widened to
   max(current fov, options fov), near 0.05, far `depthFar`) — i.e.
   vanilla culls LOOSER than it renders. Meshelium culls with the render
   projection instead, and pixel-safety rests on the clip-volume
   argument, not frustum equality: every rendered pixel's geometry is
   inside the render clip volume, and a box fully outside one render
   plane has all its geometry outside that plane — no pixels. Distance:
   26.2 applies NO draw-time distance test beyond its cull frustum's far
   plane (`prepareChunkRenders` iterates `visibleSections` with none;
   `depthFar` enters only through the culling projection), and the BFS
   bounds its walk at renderDistance·16 — so our far plane + the mask
   subsume vanilla's distance behaviour; a separate render-distance
   sphere would either duplicate the mask or over-cull frustum corners
   (a point on the far plane sits at distance depthFar/cos θ > depthFar).
   The 0.5-block inflation makes CPU-vs-GPU float rounding strictly
   over-inclusive; over-inclusion is depth-tested away, exactly as in
   wave 4.

7. **The cpuCull escape hatch.** `meshelium.terrainDraw.cpuCull` (re-read
   every call, like the main property) forces the wave-4 CPU-culled
   per-section path: same mesh/frag shaders compiled with
   `MESHELIUM_TASK_CULL=0` (push constants carry {FirstQuad, QuadCount}
   again), own pipeline + layout, cached separately. It also engages
   automatically if the section-records handle is 0 (defensive; cannot
   happen while the arena handle is live). The harness asserts the hatch
   renders and that the task path resumes when it closes.

8. **Stats.** Drawer counters grew `regionsDispatched`,
   `sectionsVisibleIn` (mask popcount over dispatched regions; overflow
   regions count their resident sections), `dispatchSignature` (order-
   independent region-id set hash — the camera-turn assertion),
   `taskCullFrames`/`cpuCullFrames`, `maskOverflowRegions`, and a
   once-per-5 s breadcrumb of CPU-side draw-path micros (avg/max) —
   DEBUG normally, INFO under `-Dmeshelium.debugStats`. **Honest GPU
   timing waits for wave 9's timestamp queries**; a CPU clock around
   command recording proves nothing about GPU execution.

9. **Known bounds + windows, all deliberate:** (a) GPU section records
   can lag the CPU region snapshot when the staging ring is full
   (`RegionStore.commitDirty` requeues) — a just-built section then
   draws a frame or two late under heavy streaming, never stale (arena
   frees stay fence-parked); the parity harness quiesces, closing the
   window for the A/B. (b) `EmitMeshTasksEXT` X is bounded by
   maxMeshWorkGroupCount[0] (spec-min 65535): one task workgroup would
   need > 2000 surviving quads per section ON AVERAGE across 32 sections
   to reach it — accepted like Nvidium's own u16 bucket bounds.
   (c) Section origins now reconstruct as float(regionOrigin−cam) +
   exact 16-multiples instead of wave 4's per-section float(origin−cam):
   ≤ 1 ulp (~1e-4 blocks) difference — beneath the perceptual compare,
   and vanilla's own origin math differs from both by more.

## Wave-6 implementation notes (2026-08-09) — GPU occlusion culling

Wave 6 made Nvidium's two-level box-raster occlusion (architecture §5) the
default visibility source, replacing the wave-5 BFS mask feed as the
CULLING source (the feed survives verbatim behind
`meshelium.terrainDraw.bfsOnly` — a total wave-5 revert, re-read every
call). Same gates, same pass position (wave 4's), same mesh/fragment pixel
math; representative-fragment-test dropped per the coordinator's decision
(§10 row 6); compute-HiZ NOT built (see the upgrade note at the end).

### 1. Pass layout and barrier count

Occlusion frames record FOUR Meshelium passes plus one transfer CB, all
inside the cancelled-renderGroup window (drawOpaque), all over the same
color+depth attachments, every pass end inheriting vanilla's free
ALL_COMMANDS MEMORY_READ|MEMORY_WRITE barrier (Q1.3). **Meshelium adds no
barrier of its own between passes**; the only Meshelium-issued barriers are
inside the stats transfer CB (the MesheliumTerrainGpu transfer-CB
convention):

```text
pass 1  "meshelium terrain phase A"     terrain pipeline, VisMode 1
        draws sections stamped visible LAST frame → primes depth
        └─ vanilla pass-end barrier: A depth → box depth tests
pass 2  "meshelium occlusion regions"   region_raster.mesh + box.frag
        occupancy-AABB box per dispatched region (≤512), depth GEQUAL,
        write OFF, colorWriteMask 0, cull NONE; survivors atomicExchange
        regionStamps[dispatchSlot] = FrameStamp
        └─ barrier: regionStamps → section-raster task reads
pass 3  "meshelium occlusion sections"  section_raster.task/.mesh + box.frag
        task per region: gate on regionStamps[slot]==FrameStamp, emit
        <count> mesh children; mesh per compacted slot: tight
        geomMin/geomSize box; survivors stamp curStamps[regionId*256+slot]
        └─ barrier: curStamps → phase-B task reads
pass 4  "meshelium terrain phase B"     terrain pipeline, VisMode 2
        draws sections stamped THIS frame that phase A did not draw
        └─ barrier: opaque color+depth complete for vanilla's features
+ transient CB: copy stats → host ring slot, barrier, zero stats, barrier
```

Cost vs wave 5 (1 pass): +3 begin/end-rendering pairs, +3 inherited
pass-end barriers, +1 transfer CB carrying 2 full barriers, per occlusion
frame. bfsOnly frames record wave 5's single pass (+ the stats CB when
occlusion resources exist, so the GPU counter stays comparable across the
A/B). Push-descriptor state does not survive the foreign pipeline layouts
of passes 2/3, so phase B re-pushes the terrain set (Q3.2).

The rasters run in their own passes (not merged into pass 1) because the
region raster's FRAGMENT writes must be visible to the section raster's
TASK stage, and task-stage reads of fragment writes are not ordered inside
a pass (rasterization order covers only per-sample fixed-function ops) —
the pass split buys the dependency from vanilla's barrier for free.
Color stays ATTACHED with `colorWriteMask = 0` rather than a depth-only
pass: the encoder's zero-color-attachment path is bytecode-UNVERIFIED
(output sizing reads "first non-null attachment"), while LOAD+STORE with
mask 0 is provably bit-identical to not attaching and keeps every
pipeline on vanilla's attachment shape (Q4.2).

### 2. Visibility layout, verbatim

> **Section stamps** — two DEVICE_LOCAL SSBOs (`sectionStampsA/B`,
> maxRegions×256×4 B = 2 MiB each @ 2048 regions), ping-ponged by frame
> parity: cur = (stamp&1)==0 ? A : B, prev = the other. One uint per
> global section slot, index = regionId*256 + compactedSlot. Written ONLY
> by the section raster (fragment on depth-pass; mesh thread 0 on
> camera-inside-box), always `atomicExchange(buf[idx], FrameStamp)` — all
> writers of a frame write the identical 32-bit value. Consumed by
> terrain.task: phase A visible ⇔ `prev[idx] == FrameStamp-1`; phase B
> visible ⇔ `cur[idx] == FrameStamp && prev[idx] != FrameStamp-1`.
> Equivalence to Nvidium's byte (study §5): bit 0 ⇔ cur==stamp, bit 1 ⇔
> prev==stamp-1 — the only two bits Nvidium's shaders ever read
> (terrain/task.glsl:20-22 bit 0; temporal_task.glsl:23-26 bits 0-1); the
> 8-frame shift history is unused surplus in the original and is
> reproducible here as `stamp - lastMark <= H` if a future wave wants a
> wider phase-A window (one macro, no format change).
>
> **Region stamps** — one DEVICE_LOCAL SSBO, 512×4 B, indexed by DISPATCH
> SLOT (this frame's list position — Nvidium's frustum-list-slot
> indexing). Same stamp discipline; never reset (stale slots hold an old
> stamp and compare unequal).
>
> **Occlusion region list** — per-frame 16 KiB transient-memory UBO
> (spec-min maxUniformBufferRange), 512 × 32 B std140 entries:
> `vec4 origin` (region min corner, blocks, camera-relative; w unused) +
> `uvec4 meta` (x = regionId, y = compacted section count, z/w = occupancy
> AABB min/max packed x|y<<8|z<<16 in section-local units — the CPU-side
> equivalent of Nvidium's region-record metadata scan,
> RegionManager.java:95-126, carried in DrawSnapshot.REGION_STRIDE=7).
>
> **Stats** — DEVICE_LOCAL 4×u32 (`[VisMode] += 1` per task-surviving
> section: [0] mask mode, [1] phase A, [2] phase B, [3] spare), copied
> per stats frame into a HOST_VISIBLE|HOST_COHERENT 8-slot ring and
> zeroed; the CPU reads slot (statsFrames − 3) — the FREE_FRAME_LAG
> fence argument, now serving its originally-promised purpose as wave
> 3b's deferred download-stream consumer.

**Why stamps instead of the byte (the wave's one representation
redesign):** Nvidium's write protocol — mesh thread 0 stores
`(last<<1)|cameraIn`, fragment stores `(last<<1)|1` — is two non-atomic
same-address stores from different pipeline stages whose values differ in
bit 0: a data race under the Vulkan memory model, benign only where
NV-like store ordering holds. With stamps every writer of a frame writes
the SAME value through an atomic, so the outcome is defined on every
conformant device; the shift disappears (freshness is equality against
the current frame), which also deletes Nvidium's frustum-exit visibility
clears (RenderPipeline.java:215-221 — the study's known-bug fix) and the
slot-reuse hazard: a stale stamp can only make phase A draw whatever
CURRENT record occupies the slot (fence-parked geometry, correct pixels,
one wasted draw) — it can never suppress a draw or replay freed geometry,
because records and stamps are read in the same frame and phase B keys on
this frame's raster alone.

### 3. Camera-inside-box / near plane (deliverable's "read how")

Nvidium does NOT clamp boxes to the near plane. Standard clipping handles
straddling boxes; the one lost case — camera INSIDE a box, all walls
behind the camera or clipped, zero fragments despite trivial visibility —
is handled on the WRITE side: thread 0 force-marks visible when the
camera is inside the inflated box (region_raster/mesh.glsl:74-77,
section_raster/mesh.glsl:89-96). No NV-specific trick; ported verbatim
(camera-relative coordinates make the test "box contains the origin").
Margin check: boxes inflate 0.1 blocks and 26.2's near plane is 0.05
(Camera.createProjectionMatrixForCulling recon, wave-5 notes item 6) —
a camera close enough for near-clipping to eat a wall is already inside
the inflated box.

### 4. The guard enumeration (why occlusion cannot cull a visible section)

A section is NOT drawn this frame only if every one of these held:

1. **Region CPU frustum** — full 128×64×128 box vs the render frustum
   (wave-5 clip-volume argument; conservative superset of every section).
2. **Not stamped last frame AND not stamped this frame.** This frame's
   stamp needed the box to produce ONE depth-passing fragment. The box is
   a superset of the section's geometry (occupancy/geomMin-geomSize
   AABB plus 0.1 inflation); the projected silhouette of a closed watertight box
   covers every sample its contents cover, and at any covered sample the
   box's front face is ≥ (reversed-Z: nearer than or equal to) the
   geometry's depth. The raster tests against PHASE-A depth = real opaque
   terrain drawn THIS frame = a SUBSET of the final frame's opaque depth.
   So: box failed everywhere ⇒ geometry strictly behind phase-A terrain
   at every covered sample ⇒ geometry loses the final depth test at every
   sample ⇒ zero pixels. (Sub-pixel boxes cannot slip through: a geometry
   fragment at sample s implies box coverage at s.)
3. **Task frustum + facing gates** — wave-5's pixel-safe cuts, unchanged.
4. **Fail-open paths verified by construction**: region-raster miss ⇒
   sections unrastered but their REGION box (superset) failed depth ⇒
   same argument; occlusion-list overflow (>512 dispatched regions) ⇒
   phase A draws the region maskless; empty record (header.w==0) ⇒ no
   geometry exists; readback lag affects COUNTERS only, never draws.
   Camera-inside boxes are stamped without any fragment.

Residual windows, both shared with wave 5 (notes item 9a): GPU records
lag the CPU snapshot by ≤1 pump under staging pressure (a just-built
section appears a frame late — the raster then stamps it the frame its
record lands and phase B draws it the same frame); and vanilla-retained
sections after chunk unload draw until their record tombstones (wave-4
deviation (c)'s class, transient, closed by the harness quiesce).

### 5. Temporal behaviour — what "late" looks like

Phase B consumes THIS frame's raster output across the pass-3 barrier, so
a camera cut repaints in the SAME frame (the harness asserts phase-B > 0
within 2 stats frames of the dispatch-set change — the window absorbs
tick/frame skew only). Sections stay drawn for exactly one frame after
becoming occluded (phase A draws last frame's set — Nvidium-identical
decay: the raster stops stamping, the stale stamp ages out next frame) —
that is OVERDRAW, invisible by depth test. A section can appear one frame
late only through the record-lag window above, never through the
occlusion loop itself; the parity shots cannot show any of this because
the harness quiesces and holds the camera still, so A ∪ B is a fixed
point — steady state draws the same set every frame.

### 6. The HiZ upgrade path (wave 9+, documented not built)

The raster scheme's cost centers — 3 extra passes, per-box fragment
traffic, idempotent-store redundancy where rep-frag-test would have
helped — all live behind `TerrainOcclusion.recordRegionRaster` /
`recordSectionRaster`. A compute-HiZ replacement would: build a depth
pyramid from phase-A depth (one compute pass), test region+section AABBs
against it in one dispatch writing the same stamp buffers, and delete
passes 2-3 entirely (terrain.task's stamp consumption is agnostic to who
writes the stamps). Blocked in wave 6 by decision, not by architecture:
the box raster is Nvidium's proven design and the reference shaders
exist; HiZ needs new-code validation the parity harness would have to
re-earn.

### Wave-6 verification addendum (coordinator, 2026-08-09)

The occlusion-on vs bfs-only A/B measured EXACTLY ONE differing pixel
(delta 86 at (645,349) in the hidden-wall scene): a diagonal silhouette
edge where a wall face and the grass behind it meet at equal depth. Under
GEQUAL, equal-depth ties go to the LAST primitive drawn, and the temporal
phase-A/B split legitimately reorders draws relative to the single-pass
path — so isolated tie-break pixels on coplanar silhouette edges are an
expected, bounded artifact of the two-phase scheme (Nvidium's has the
same order-dependence). The discriminator between this and a real culling
hole: a hole shows BACKGROUND bleeding through; a tie-break shows the
other legitimate adjacent surface. The culling A/B criterion is therefore
"no structural diffs; isolated coplanar-edge tie pixels allowed", with
this pixel as the exhibit. Parity vs vanilla (shot 40/41) stays at
zero-real-diff, worst 2.

## Wave-7 implementation notes (2026-08-09) — the translucent replacement

Wave 7 extended the kill switch to the TRANSLUCENT group: `renderGroup`
HEAD now cancels BOTH groups (same triple gate, property re-read every
call), `TerrainDrawer.drawTranslucent` records Meshelium's blend pass at the
exact frame point vanilla's translucent draws held (Q2.3 item 8 — after
features/depth-copies; the mixin IS that call site), and translucent only
ever owns a frame whose OPAQUE group Meshelium also owned (frame-serial
coupling in the drawer), so every frame is wholly vanilla or wholly
Meshelium — the blend pass always tests depth its own opaque pass wrote.
Host-sorter doctrine throughout: vanilla's own sort is the ordering
authority; nothing in this wave invents an order.

### 1. Blend / depth state — verbatim, bytecode-cited (deliverable 1)

`RenderPipelines.<clinit>` ip 1328-1374: `TRANSLUCENT_TERRAIN` =
`TERRAIN_SNIPPET` + location `pipeline/translucent_terrain` +
`withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))` +
`withShaderDefine("ALPHA_CUTOUT", 0.1f)` — and NOTHING else: no
`withDepthWrite`, no `withCull`, no depth-test override.

> **Blend** — `BlendFunction.<clinit>` ip 60-79: TRANSLUCENT = color
> {src SRC_ALPHA, dst ONE_MINUS_SRC_ALPHA, op ADD}, alpha {src ONE, dst
> ONE_MINUS_SRC_ALPHA, op ADD} (the 4-factor ctor routes both equations
> through BlendOp.ADD). `ColorTargetState`'s 1-arg ctor pins
> `writeMask = 15` (bipush 15). `VulkanRenderPipeline.applyBlendInformation`
> sets `blendEnable(true)` + the six fields via `VulkanConst.toVk`; the
> `VulkanConst$1` $SwitchMap assignments read out: SRC_ALPHA → case 12 →
> **6** (VK_BLEND_FACTOR_SRC_ALPHA), ONE_MINUS_SRC_ALPHA → case 10 → **7**,
> ONE → case 7 → **1**, ADD → case 1 → **0** (VK_BLEND_OP_ADD).
>
> **Depth-write verdict: ON.** TERRAIN_SNIPPET inherits
> GENERIC_BLOCKS_SNIPPET's `DepthStencilState.DEFAULT` =
> `(CompareOp.GREATER_THAN_OR_EQUAL, writeDepth = true)`
> (`DepthStencilState.<clinit>` — iconst_1 into the 2-arg ctor), and the
> translucent builder never overrides it — 26.2 translucent terrain KEEPS
> depth writes, matching Nvidium's own observation (§6 study note, now
> verified as the study demanded). Cull: builder default TRUE (wave-4 note
> 8), never overridden → BACK, front face CW.

Meshelium's `TerrainDrawPipeline.createTranslucent` reproduces exactly this
(blend branch carries the citations); everything else (dynamic states,
single-sample, formats-from-attachments) is the wave-4 convention set.

### 2. Section order — vanilla's, reproduced CPU-side (deliverable 2)

`renderGroup` bytecode (complete dump re-read this wave): per layer the
draw lists live in an `Int2ObjectOpenHashMap<List<Draw>>` keyed by
buffer-identity hash; every non-empty TRANSLUCENT list is
`List.reversed()` (ip 262-277) before `drawMultipleIndexed`; the MAP is
iterated in HASH order (`.values().iterator()`, ip 218-230). The list
order inside each bucket is `prepareChunkRenders`' iteration order =
`visibleSections`, which is filled by
`SectionOcclusionGraph.addSectionsInFrustum` →
`Octree.visitNodes(visitor, frustum, 32)` — the camera-sorted octree walk
(`Octree$Branch` holds a camera-derived `AxisSorting`; the visitor lambda
appends in visit order, nearby list gated by `isClose(..., 32)`), i.e.
front-to-back. **So vanilla's translucent section order = visibleSections
REVERSED, per draw-group bucket, buckets in hash order.** Meshelium draws
`visibleSections` reversed over its ONE buffer — the single-bucket case
exactly; when vanilla's uber heaps split (>128 MiB of a layer), vanilla's
own cross-bucket order degrades to hash order while Meshelium's stays the
globally reversed list. Deviation direction: Meshelium is the order vanilla
itself produces whenever one heap suffices (every harness scene), and
strictly no worse sorted beyond that — documented, not observable in the
A/B.

Intra-section: the arena prefix IS vanilla's sorted order (wave 3b build
sort; wave-7 resorts below), drawn front-of-buffer-first in ≤64-quad
slices, one workgroup per slice, slices in prefix order. Draw-to-draw
ordering is core rasterization API order; within a workgroup, output
primitive indices ascend with the prefix slot. **The multi-workgroup
dispatch was deliberately NOT used for translucency**: VK_EXT_mesh_shader's
inter-workgroup primitive-ordering guarantee is the study's UNVERIFIED
item (§6) — the single-WG shape needs no such guarantee. Cost: an
ocean-surface section (256 top quads) records 4 draws instead of 1;
unmeasured by rule (correctness first), the multi-WG upgrade is parked
with wave 9 pending a spec-text/hardware verification of the ordering
guarantee.

### 3. Resort plumbing (deliverable 3)

- **Trigger recon** (`LevelRenderer.scheduleTranslucentSectionResort`
  bytecode): fires from `compileSections`' tail; when the camera BLOCK
  position changed, EVERY `nearbyVisibleSections` member (≤32 blocks, the
  Octree isClose radius) is force-scheduled (`forced = blockChanged &&
  (pov.isAxisAligned() || isNearby)` — scheduleResort ip 22-44), plus a
  rotating `max(15, visibleSections/8)` slice of all visibleSections per
  frame. `ResortTransparencyTask.doTask` then really rebuilds only when
  `mesh.isDifferentPointOfView(pov) || pov.isAxisAligned()` —
  `TranslucencyPointOfView` = per-axis `clamp(cameraSectionCoord −
  sectionCoord, −1, 1)` (getCoordinate bytecode), so **the sort threshold
  is the 16-block section grid**: crossing a section boundary flips a
  section's POV; sections sharing an axis band (a 0 component — near
  terrain almost always) resort on every camera block change.
- **The tap** — shopping-list row 7, finally used, with the filter
  INVERTED from its original fallback purpose: `@Inject(HEAD)` on
  `RenderSection.addSectionBuffersToUberBuffer(ChunkSectionLayer,
  CompiledSectionMesh, ByteBuffer, ByteBuffer)Z`, accepting ONLY
  `layer == TRANSLUCENT && vertexBuffer == null && indexBuffer != null` —
  the resort hand-off is the only such caller (`ResortTransparencyTask
  .doTask` ip 167-187), and the compile tap is structurally blind to it
  (section-build Q4.2). Dedupe is CONTENT-based: doTask's spin loop calls
  `result.byteBuffer()` FRESH each retry (ip 179-184), so identity can't
  be trusted; after the first application the decoded order equals the
  stored order and re-fires count as `resortsNoop`.
- **The permutation** — vanilla's TRANSLUCENT vertex buffer never
  reorders; only the index buffer does. Meshelium instead stores the prefix
  physically in draw order, so a resort = a byte permutation:
  `TranslucentPrefix.permute` (pure CPU, `terrain/`, unit-pinned in the
  data test) over a per-section CPU prefix copy + applied-order array
  (seeded on the build thread at enqueue from the decoder's applied
  build-time order; ~64 B per resident translucent quad of CPU memory —
  the price of never reading back). The new order is decoded from the raw
  index bytes with the index width INFERRED from the byte count
  (`remaining == n*6*2` or `*4` — no DrawState travels with a resort);
  any anomaly counts `resortsMalformed` and keeps the current order
  (stale order, never corrupt bytes).
- **The re-upload** — permuted prefixes stage through the pump as LATE
  arena copies (`stageArenaCopyLate`): the pump's transfer CB is now
  `fills → barrier → copies → [barrier → late copies] → barrier`, so a
  resorted prefix overwriting bytes a same-frame fresh upload just wrote
  is WAW-ordered (the resorted-while-pending path). In-place overwrites
  vs PRIOR frames' draws are ordered by vanilla's pass-end ALL_COMMANDS
  barriers (Q1.3) — the pump records after the frame graph executed.
  Latency matches vanilla's: both sides' new order first draws the frame
  after the resort's upload was recorded. Counters: `resortsApplied`
  (permutations), `resortBytes` (staged), `resortsNoop`,
  `resortsUnknownMesh` (mesh never resident — dropped/budget/raced),
  `resortsMalformed`. **Resorts never re-encode** — structurally (the
  task never calls compile) and harness-pinned (`resortsApplied` advances
  while `encodedSections` is flat).

### 4. Visibility + the occlusion gate

BFS membership (iterating `visibleSections` IS the gate) + the CPU
section frustum, exactly the opaque sources. On occlusion frames the
drawer carries {curStamps handle, frame stamp, rastered-region set} from
`drawOcclusionCulled` to the translucent pass (same frame, recorded after
pass 3's stamps behind vanilla's pass-end barriers) and pushes a
per-section `GateIndex = posKey<<20 | regionId*256+slot`; the MESH stage
verifies the record's identity (posKey recompute) before consulting
`curStamps[idx] == FrameStamp`, and EVERY mismatch — sentinel, record
lag, slot compaction, region past the occlusion list cap — fails OPEN.
The skip case is pixel-safe by the wave-6 guard argument extended to
blending: a box that depth-failed everywhere against phase-A depth (a
subset of the final opaque depth) has all its geometry losing the final
depth test — zero blended fragments removed. The gate verdict is computed
by invocation 0 into shared memory and barriered, making the
SetMeshOutputsEXT argument provably workgroup-uniform. Slotless residents
(promotion-lag window) are excluded from the translucent map so a section
can never double-blend; the slot owner is what the GPU records already
point at, keeping opaque and translucent consistent (wave-3b note 9's
window, now with a translucent-specific rule).

### 5. Known deviations, each deliberate

(a) cross-heap section order (note 2 above) — single-bucket exact,
better-sorted beyond; (b) vertex alpha is not stored (wave-4 deviation
(d) extended to blending): vanilla's own translucent terrain content
carries vertex alpha 255 (water tint is RGB, glass/portal untinted —
alpha comes from the TEXTURE), so `out.a = tex.a × 1.0` matches; modded
blocks emitting vertex alpha < 255 on the TRANSLUCENT layer would shade
differently — the 60/61 pair is the detector; (c) `ChunkVisibility`
fade ≡ 1.0 (wave-4 deviation (a), quiesced away); (d) fabulous graphics
**RESOLVED 2026-08-13, no deviation** — see the addendum below.

### Wave-16: improved transparency ("fabulous") is tested and correct

Deviation (d) said the separate-translucent-target path had never
executed. It has now, and it works: shots `62_meshelium_translucent
_fabulous` / `63_vanilla_translucent_fabulous_reference` in
`MesheliumTerrainDrawTest.assertFabulousTransparency`.

Naming first, because the old note is written against a version that no
longer exists: **26.2 has no `GraphicsStatus`**. `FABULOUS` survives only
as a `GraphicsPreset`, and the preset is a bundle. The single toggle that
actually moves terrain to another render target is
`Options.improvedTransparency()`. The chain, all javap-verified:
`GameRenderer` copies the option per frame into
`OptionsRenderState.improvedTransparency`; `GameRenderState
.useShaderTransparency()` is `!isPanoramicMode && improvedTransparency`;
that alone gates `LevelRenderer.getTransparencyChain()`; and a non-null
chain is what makes the frame graph `createInternal("translucent", …)`
so `translucentTarget()` returns non-null. No restart is required.

**The trap, which cost a run.** The obvious way to assert the path is
live — wait for `LevelRenderer.translucentTarget() != null` — can never
succeed. `LevelTargetBundle.clear()` runs at the end of every frame
(`renderLevel`, offset 586), so from a test thread between frames that
getter reads null on *every* graphics setting. The first version of the
test timed out there and looked exactly like the feature failing. The
drawer therefore counts it from inside the frame, at the point it picks
its target: `TerrainDrawer.translucentSeparateTargetFrames()`. The test
waits on that counter, so shot 62 cannot be a default-path frame
mislabelled as fabulous.

**Verdict, RX 9070 XT, 854×480.** Meshelium agrees with vanilla on the
fabulous path exactly as well as it does on the default one:

| threshold | 62 vs 63 (fabulous) | 60 vs 61 (default) |
|---|---|---|
| any diff | 3,572 (0.87%) | 111,555 (27.2%) |
| >4 | 657 | 647 |
| >8 | 359 | 327 |
| >16 | 51 | 53 |
| >32 | 5 | 5 |

Read the columns, not the first row. The substantive profiles are the
same to within noise; 49 of the 51 pixels above 16 sit in the nether
portal, the animated-sprite class wave 7 already documented as benign.
The first row differs only because the two paths dither differently at
±1–2, which is why "any diff" is a useless threshold for grading a
compositing change and the 27% must not be read as a regression.

No gate was added, because there is nothing to gate.

### Wave-7 verification addendum (coordinator, 2026-08-09)

Translucent A/B (shots 60/61): 9 differing pixels, all inside the nether
portal's face, all portal-purple on both sides — the portal sprite is
ANIMATED and the two shots sit a few animation frames apart, the exact
benign diff class the wave-4 protocol documented for water/lava. The
discriminator for translucency: a real ORDER error re-blends the whole
surface (uniform color shift across the face); an animation diff moves
sparkle detail within it. Water and stained glass compared clean; opaque
parity unchanged (zero real, worst 2). Verdict: PASS.

## Wave-8 implementation notes (2026-08-09) — robustness + the real config

### 1. The config graduation (deliverable 1)

`MesheliumConfig` grew `enableTerrainRendering` (**default TRUE** — this is
the wave the mod turns on), `enableOcclusionCulling` (true), `debugStats`
(false). Precedence, resolver-per-setting in `MesheliumConfig` (the full
matrix lives in its class javadoc): a system property that is PRESENT
overrides the config field with its parsed value — the dev/harness flags
keep their exact wave-4/5/6 semantics, including the live-flip protocol
the parity screenshots depend on — an ABSENT property lets config rule.
`meshelium.terrainDraw.cpuCull` stays property-only (dev hatch). Every
consumer re-reads per call — the kill-switch mixin and frame-state hook
call `MesheliumConfig.terrainRenderingEnabled()` per renderGroup/render, the
occlusion branch calls `occlusionCullingEnabled()` per frame, both stat
emitters call `debugStatsEnabled()` per emission — so config toggles land
next frame with no restart, exactly like the properties always did.
Consequence for the harness: a Vulkan run with NO `-Pmeshelium.terrain`
now draws terrain through Meshelium by default (boot-smoke shot
00 becomes a Meshelium frame on Vulkan; pixel parity is the wave-4/7
guarantee and the GL run is untouched at the gate).

### 2. The coverage guard (deliverable 3 — the VRAM-pressure answer)

Not an eviction system: a section Meshelium DROPS (arena-full, oversize,
region-budget, encode failure) is a section vanilla still has — once
Meshelium owns the draw that difference is a hole in the world. New rule:
`TerrainResidency.dropsThisWorld()` (the four drop counters minus a
baseline captured in `disposeAndReset`, i.e. per-world drops — the
counters themselves stay lifetime diagnostics) nonzero ⇒ `drawOpaque`
returns false BEFORE any recording, the kill switch stops cancelling,
vanilla draws every group, one WARN with all four counts (once per
tripped world; the flag re-arms at dispose). Passive is NOT an error:
`lastError()` stays null, residency keeps mirroring (so the next world is
judged on fresh evidence).

Flip-flop analysis (the report's must-be-none): the guard is read exactly
once per frame at drawOpaque HEAD; translucent never re-reads it — it
keys on `opaqueOwnedSerial`, so every frame is wholly Meshelium's or wholly
vanilla's. Drops land in the pump (render thread, before the frame graph
runs the main pass) or on build workers (`droppedEncoding`); a worker
drop between the two groups of frame N flips only frame N+1, and the
dropped section's mesh had not been promoted by vanilla for frame N
either, so frame N has no hole. Within a world the input is monotonic
(counters only grow; the baseline moves only at dispose) ⇒ passive can
never flap back to active mid-world. Test hook:
`-Dmeshelium.test.arenaMiB=1` shrinks the arena at world standup
(`MesheliumTerrainGpu.arenaBytes()`, WARN-logged) so the torture test can
force the trip deterministically.

### 3. The destroy sweep (deliverable 4) — every wave-8 marker discharged

The safe point, bytecode-cited: `VulkanDevice.close()` runs
`commandEncoder.destroy()` at ip 13 — whose own body does the final
submit, `graphicsQueue.waitIdle()` (VulkanCommandEncoder.destroy ip 21)
and BOTH destroy-queue drains — then `clearPipelineCache()` (ip 17),
`vmaDestroyAllocator` (ip 24), `vkDestroyDevice` (ip 32).
`VulkanDeviceMixin` injects AFTER the encoder destroy: queue provably
idle, deferred per-world destroys already ran, VkDevice still valid —
the exact window where vanilla destroys ITS pipelines. Ownership table:

| Object | Owner / destruction |
|---|---|
| Hello pipelines (2) + layouts (2) + set layout (1) | `HelloMeshletPipeline.destroy` ← `HelloMeshletRenderer.destroyDeviceObjects` ← device close |
| Terrain pipelines ×3 (task/cpu/translucent) + layout + set layout each | `TerrainDrawPipeline.destroy` ← `TerrainDrawer.destroyDeviceObjects` ← device close |
| Occlusion static pipelines ×2 + layouts + set layouts | `TerrainOcclusion.destroyPipelines` ← same |
| Shader modules (all waves) | already destroyed at pipeline creation (no change) |
| Arena/records/staging/stamps/stats (per-world) | unchanged: dispatcher dispose → `queueForDestroy`; NEW defensive `destroyNow()` at device close if a dispose hook never ran (WARN) |

`Minecraft.close()` ordering (javap): `levelRenderer.close()` at ip 63
(→ dispose → per-world teardown) long before
`RenderSystem.shutdownRenderer()` at ip 128 (→ device close) — so the
defensive path should never fire in a clean shutdown. Resource reload
(F3+T) needs NOTHING destroyed: the only client caller of
`GpuDevice.clearPipelineCache` is `ShaderManager` (jar caller census) and
it clears vanilla's OWN pipeline cache; Meshelium's raw handles compile
their shaders from the mod jar, not resource packs — the torture test
pins drawing resuming after reload.

### 4. Device loss (deliverable 4's last leg)

`com.mojang.blaze3d.GpuDeviceLossException extends RuntimeException`
(javap; thrown by `VulkanUtils` on VK_ERROR_DEVICE_LOST with checkpoint
formatting). NO client-side catcher exists anywhere in
`net/minecraft/client` (binary grep over the extracted jar) — vanilla
crashes on its next GPU call. Meshelium now catches it DISTINCTLY (before
the generic Throwable containment) at drawOpaque, drawTranslucent and
the pump; the occlusion branch rethrows it past its own occlusion-only
latch. Effect: latch → passive → vanilla's own next call reports the
loss exactly as an unmodded client would. Never harder than vanilla.

### 5. Options screen + routes (deliverable 2)

`MesheliumOptionsScreen`: the popup's widget skeleton + vanilla
`CycleButton.onOffBuilder(boolean).create(Component, OnValueChange)`
(javap: 2-arg create delegates to (0,0,150,20)). Rows overridden by a dev
property render inactive with an explanatory line (an inert toggle would
lie). Routes: ModMenu entrypoint key `"modmenu"`, interface
`com.terraformersmc.modmenu.api.ModMenuApi#getModConfigScreenFactory` →
`ConfigScreenFactory.create(Screen)` — all javap'd from the owner's
`test-mods/modmenu-20.0.1.jar` (compileOnly file dep, remap-free in the
unobfuscated era, never on the runtime classpath); and `/meshelium` via
fabric-command-api-v2 (`ClientCommandRegistrationCallback.EVENT` +
`ClientCommands.literal`, javap'd from fabric-api 0.155.2), the screen
opened through `Minecraft.schedule` (always enqueues — javap
`BlockableEventLoop.schedule` vs inline-running `execute`) so the chat
screen's own close cannot stomp it.

### 6. Torture harness (deliverable 4's test half) — and the honest skips

`MesheliumLifecycleTortureTest`, Vulkan+terrain run only: options-screen
smoke (shot 70) → config-graduation live toggles (property CLEARED —
the config path is exercised for real) → `reloadResourcePacks()` (javap:
`CompletableFuture<Void>`) with drawing + zero latches after → 3 fast
world hops each observing the note-11 dispose
(`lastDisposeSnapshot` progression) then drawing again → the coverage
guard leg (shot 80; WARN once; counters frozen; clean world re-arms).
SKIPPED, honestly: window resize — fabric client-gametest 5.1.1 has no
resize API (`ClientGameTestContext` javap'd end to end); device loss —
unforceable on healthy hardware. Both stay on the UNVERIFIED ledger.

## Wave-9 implementation notes (2026-08-09) — measurement + tuning

Wave 9 adds instrumentation and knobs, no new rendering behaviour: every
knob defaults to the wave-4/5/7-verified values, and the two things that
run by default (GPU timestamps, front-to-back ordering) are pixel-neutral
by argument (below) with shots 40/41 + 60/61 as the empirical backstop.

1. **GPU timing rides vanilla's own query plumbing** (deliverable Q1
   answered by javap, all bytecode-cited in `MesheliumGpuTimers`):
   `GpuDevice.createTimestampQueryPool(int)` → `VulkanQueryPool`
   (`vkCreateQueryPool` queryType TIMESTAMP + a host `vkResetQueryPool`
   at creation — so VK 1.2 hostQueryReset is enabled on vanilla's
   device); `CommandEncoder.writeTimestamp(pool, i)` (public facade) =
   host `vkResetQueryPool(pool, i, 1)` +
   **`KHRSynchronization2.vkCmdWriteTimestamp2KHR(cb, 0x10000
   ALL_COMMANDS, pool, i)`** — the sync2 LWJGL name, verified;
   `GpuQueryPool.getValues` = `vkGetQueryPoolResults` 64_BIT |
   WITH_AVAILABILITY, no WAIT (never stalls; unready = absent);
   `DeviceInfo.timestampPeriod()` converts ticks → ns. Vanilla's
   `TracyGpuProfiler` uses exactly this stack (1024-query rotation,
   reset-per-write), which is the strongest available evidence the
   driver path is production-safe. Meshelium writes 5–7 timestamps per
   drawn frame BETWEEN its passes (never inside one); with vanilla's
   pass-end ALL_COMMANDS barriers, adjacent stamps bracket whole passes
   — durations include scheduling bubbles, reported as such. Readback:
   8-frame slot ring, read at frame−3 (the FREE_FRAME_LAG argument, plus
   availability as the belt). CPU draw-path micros stay a separate log
   line; **CPU and GPU times are never summed anywhere.**
2. **Benchmark mode** (`-Pmeshelium.bench=<scene>`): build.gradle swaps the
   gametest entrypoint list to `MesheliumBenchmarkTest` alone (template
   expansion in `processGametestResources`). The bench builds a NORMAL
   noise world at seed 4242 (the sibling repo's worldgen-proof pattern),
   pins a SPECTATOR camera at `0.5 130 0.5 yaw 45 pitch 25`, freezes the
   world per the parity protocol, quiesces, then measures 120 warmup +
   600 frames of whole-frame CPU time (deltas at `LevelRenderer.render`
   HEAD — the wave-4 mixin, hook live regardless of the terrain gate) +
   the GPU pass series, flips `meshelium.terrainDraw` OFF live (the 40/41
   protocol) and measures the vanilla baseline in the same session/world/
   camera. Raw series + mean/median/p95/p99 land in
   `build/run/clientGameTest/meshelium-bench-<scene>.json`.
3. **Knobs** (all resolved in `TerrainDrawer`, defaults unchanged):
   `meshelium.tune.meshWorkgroupQuads` (32; ≤64 — the one-quad-per-thread
   shape hits the 256-output-vertex spec cap at 64; a 256-invocation
   shape needs multi-quad-per-thread emission, NOT implemented, sweep
   capped and documented), `meshelium.tune.taskWorkgroupSections` (32;
   ≤128 — 128×80 B = 10 240 B payload ≤ spec-min 16 384; 256 would need
   20 480 and is rejected by the extended pipeline-creation assert),
   `meshelium.tune.frontToBack` (ON — sorts the per-region dispatch list
   near→far before recording; safety: opaque GEQUAL/write-ON/blend-OFF
   output is a function of the fragment SET except exact-equal-depth
   ties, the class the wave-6 addendum already bounded to isolated
   coplanar silhouette pixels and which well-formed section meshes do
   not produce), `meshelium.translucentMultiWG` (OFF — ledger 17's
   experiment: multi-WG translucent slices; the shader now derives each
   workgroup's slice from `gl_WorkGroupID.x`, bit-identical for 1-WG
   dispatches since base is the constant 0).
4. **What wave 9 deliberately did NOT build:** compute-HiZ (wave-6 note
   §6's documented upgrade), subgroup prefix sums in the task stage,
   256-invocation mesh shapes, and any Nvidium-constant copying —
   Nvidium ships 16 quads/mesh-WG and 1-invocation task WGs (NV-tuned;
   reference shaders read directly), which is comparison context for the
   sweep, not a target.

### Wave-9 verification addendum (coordinator, 2026-08-09)

**Ledger 28 EXPERIMENT RESULT (RDNA4):** the translucent parity scene with
`meshelium.translucentMultiWG=true` measured the identical diff signature as
the single-WG default (7 px inside the animated portal face, worst 59 —
the benign animation class) — multi-workgroup translucent slices blend in
dispatch order ON RDNA4 (driver 26.7.1/LLPC). The knob stays default-OFF:
one vendor's practice is not the spec, and NVIDIA/Intel are unmeasured.

**Also settled: the recurring "~1s BUILD FAILED lock race" was never the
client.** `deleteGameTestRunDir` names the blocker when asked: a process
with its WORKING DIRECTORY inside the run dir — which was the
coordinator's own persistent shell, left sitting in `screenshots/` by
`cd`-based pixel-compare commands. Compare from the project root with
absolute/relative paths instead; the "transient" never recurs.

Regression gate with wave-9 defaults (front-to-back ON, GPU timers ON):
40/41 = zero real diffs; 60/61 = the animated-portal class only. GL
dormancy green. Timing benches pend a GPU-idle window (the owner games on
this machine — benches only run announced).

## Wave-12 implementation notes (2026-08-10) — CPU attribution + candidates

Wave 12 is the measure-first CPU wave: the rd-64 row (PERFORMANCE.md) shows
7.24 ms frames with 3.0 ms on the GPU, so ~4 ms of CPU is unattributed.
This wave ships (1) per-stage attribution and (2) candidates EACH behind
its own property, defaults OFF/byte-identical — the coordinator's bench
A/Bs decide winners; default flips are coordinator commits, not this
wave's. All names re-verified by javap/census against the real jar.

### 1. Where the render-thread CPU actually goes (the recon that shaped the stages)

- **`LevelExtractor.extract(DeltaTracker, Camera, float)`** (called from
  `GameRenderer.extract` ip 103, itself from `Minecraft.renderFrame` ip
  441): per frame it scans ALL of `visibleSections` for dirty states
  (ip 484–581), builds `RenderRegionCache` snapshots per dirty section,
  and extracts entities/block entities/particles.
- **The `visibleSections` REBUILD is NOT per-frame.** `extract` ip 256–295:
  `applyFrustum(Frustum)` (→ `clearVisibleSections()` +
  `SectionOcclusionGraph.addSectionsInFrustum` — jar census: applyFrustum
  is addSectionsInFrustum's ONLY caller) runs only when
  `consumeFrustumUpdate()` OR the camera rotation crossed a 2° bucket
  (`floor(rot/2)` vs `prevCamRotX/Y`; skipped entirely under a captured
  debug frustum, ip 218–222). Consequence for measurement: the bench's
  static camera almost never rebuilds the list; real mouse-look rebuilds
  it most frames — the per-frame `applyFrustumRuns` series exists exactly
  to keep those two worlds separate.
- **`SectionOcclusionGraph.update(CameraRenderState, int,
  ChunkLoadingRenderState)`** (LevelRenderer.render ip 713, after the
  frame graph): folds chunk-load/empty-section deltas, then either
  schedules a FULL rebuild asynchronously (`CompletableFuture.runAsync`
  on `Util.backgroundExecutor()` — bytecode) or runs the partial BFS
  propagation on the render thread (`runPartialUpdate` → `runUpdates`).
  The bracket therefore measures the render-thread share only, honestly.
- **`LevelRenderer.prepareChunkRenders(Matrix4fc)`** is per-frame and
  UNCONDITIONAL: for every `visibleSections` entry it does per-layer
  `getSectionDraw` + `getRenderSectionSlice` lookups under
  `dispatcher.lock()`, allocates `new Matrix4f` + a
  `DynamicUniforms$ChunkSectionInfo` per section and a `RenderPass$Draw`
  per non-empty layer, then `writeChunkSections` uploads one UBO slice
  per visible section into the dynamic-uniform ring. At rd 64 that is
  ~9,459 sections of allocation + ~1 MB of UBO traffic per frame —
  the leading suspect the stage table exists to convict or acquit.

### 2. `MesheliumCpuStages` — the attribution (deliverable 1)

Seven render-thread `System.nanoTime()` brackets: `extract`,
`applyFrustum` (nested inside extract — never summed), 
`occlusionGraphUpdate`, `prepareChunkRenders` (via `LevelRendererMixin`),
`mesheliumOpaque`/`mesheliumTranslucent` (the drawer's existing recording
clock, now per-frame series), `residencyPump` (at the pump hook). Frame
rows commit at the next `extract` HEAD; bench runs capture per-frame
series into the JSON (`cpuStages` block, both legs — the vanilla
baseline's own breakdown is half the story) plus `applyFrustumRuns` and
`visibleSections.size()` context series; a 5 s means line follows the
debug-stats convention. Gate: `MesheliumCpuStages.ARMED` is a static final
resolved from `meshelium.cpustages` ?? bench-armed — JIT-dead `if (false)`
on every normal run, zero per-frame allocation when armed (fixed arrays).
Two new instrumentation-only mixins (`LevelExtractorMixin`,
`SectionOcclusionGraphMixin`) target backend-neutral classes and call the
pure-JDK recorder — GL dormancy untouched.

### 3. `meshelium.tune.skipVanillaPrep` (deliverable 2, default OFF) — the consumer census

The kill switch cancels `renderGroup` AT ITS HEAD — but
`prepareChunkRenders` has already built the frame's draw lists and
uploaded the per-section UBO slices before the frame graph even executes
(render ip 367 vs 561). The candidate skips that dead work when Meshelium
will own the frame. **Census (extracted-jar grep over every class's
constant pool, `net/minecraft/client` + `com/mojang`):**

| Consumer question | Answer (evidence) |
|---|---|
| Who references `ChunkSectionsToRender` at all? | ONLY `LevelRenderer` and the record itself (class-file census) |
| Who calls `prepareChunkRenders`? | ONLY `LevelRenderer.render` ip 367 (census + bytecode) |
| Where does the record flow? | `render` ip 387 `addMainPass(...)` → captured into the main-pass lambda (invokedynamic ip 258) → consumed at exactly TWO `renderGroup` invokes (lambda ips 103/313) — the calls the wave-4/7 kill switch cancels |
| Who reads the record's accessors (`textureView/drawGroupsPerLayer/maxIndicesRequired/chunkSectionInfos`)? | Only `renderGroup` itself — plus MESHELIUM's own `sections.textureView()` in `drawOpaque`/`drawTranslucent` (the captured mixin argument) |
| Who consumes `DynamicUniforms$ChunkSectionInfo`/`writeChunkSections`? | Only `DynamicUniforms` (owner) and `prepareChunkRenders` (census); the returned slices ride ONLY inside the record's draws |
| Features / depth-copies / addMainPass lambda chain? | The lambda's other work (`executeSolid/Translucent`, `copyDepthFrom`, outline) never touches the record (bytecode read of `lambda$addMainPass$0`) |

So on a Meshelium-owned frame the ENTIRE work product is dead except
`textureView()`. The skip (LevelRendererMixin, HEAD-cancellable on
`prepareChunkRenders`) returns a minimal record: the REAL atlas view
(vanilla's exact lookup, ip 80–89: `textureManager.getTexture(
TextureAtlas.LOCATION_BLOCKS).getTextureView()`), one empty
`Int2ObjectOpenHashMap` per layer (renderGroup `.get(layer).values()`
needs non-null maps — bytecode ip 204–223), `maxIndicesRequired = 0`
(renderGroup then passes a NULL shared index buffer — ip 7–43) and an
empty slice array. Precedent: vanilla itself builds this exact shape when
`sectionRenderDispatcher == null` (prepareChunkRenders ip 110–114).
Side-effect audit of the skipped body: `getSectionMesh`/`getRenderOrigin`
/`getSectionDraw`/`getRenderSectionSlice` are getters;
`RenderSection.getVisibility(long)` is PURE (bytecode: reads
uploadedTime/fadeDuration, no stores); `lock()/unlock()` guards only the
skipped iteration; `writeChunkSections` only advances the per-frame ring
vanilla resets each frame — skipping frees ring capacity, never starves it.

**The gate** is predictive: property ∧ wave-1 gate ∧ config ∧
`TerrainDrawer.wouldOwnFrame()` (no latch, coverage guard clean via a
side-effect-free `dropsThisWorld()` read, camera captured, OPAQUE target +
lightmap views present; the TRANSLUCENT target null-falls-back to main —
Q2.1 — so it cannot independently refuse). Every deterministic refusal
path of both draw calls is mirrored; the residual is a drawer THROW
latching mid-frame: that one frame renders without terrain, then the
latch makes the prediction false and vanilla is whole. Counted
(`prepSkipHoleFrames`, WARN once); the bench asserts 0 on skip legs.
Mid-frame property/config flips cannot split prediction from kill switch
(both on the render thread inside one `render` call; toggles land between
frames on that thread).

### 4. `meshelium.tune.cachedCull` (deliverable 3, default OFF) — and the honesty verdict

What the drawer's occlusion path (the DEFAULT) spends per frame at rd 64:
a ~1,700-iteration region loop (`new AABB` + `Frustum.isVisible` each —
vanilla's `cubeInFrustum(double×6)` is private, so the allocation is not
avoidable through public API), occlusion-list writes, the front-to-back
sort, then 2×dispatched push-constant/draw records. The candidate
memoizes the build EXACTLY: key = snapshot epoch + camera-position raw
bits + the cull frustum's camera raw bits + its private matrix's 16 raw
float bits (`FrustumAccessor`) + capacity/extLists/frontToBack. Identical
key ⇒ the rebuild is a pure function replay ⇒ a hit records bitwise the
same commands (pixel-neutral by construction; ANY delta misses). Ext-mode
ring rotation is preserved by building into a persistent shadow and
memcpy-ing the prefix into each frame's slot; knob OFF keeps the wave-10
write targets byte-identical.

**Honest verdict, stated as the brief demanded:** the memo hits only on
BIT-identical camera frames — 100% of the bench's pinned camera, but in
real play only the stationary slice (standing still, chat/inventory
open; any mouse-look or movement delta misses). The bench A/B therefore
measures the UPPER BOUND (the region-loop + sort share), and
`cachedCullHit/MissFrames` from a real play session are the evidence any
default flip must cite. The brief's alternative — incremental mask
building — was REJECTED from the code itself: per-region masks exist
ONLY on the `bfsOnly` fallback (see §5), so optimizing them cannot help
real play on the shipped occlusion path; and vanilla's list, contrary to
the brief's premise, is not rebuilt per frame at all (§1) — the honest
scaling lever for real play is whatever the stage table convicts, which
is exactly why attribution ships first.

### 5. Mask-path elision on the occlusion path (deliverable 4) — FINDING: already elided

`drawOcclusionCulled` never touches `visibleSections`, `regionMasks` or
the 16 KiB mask upload: its occlusion region list is built from the
snapshot's `regionData` alone, and binding 8 receives the occ list slice
as a type-correct dummy the stamp modes never read (code + the wave-6
notes' binding table). The BFS mask build + upload exists ONLY in
`drawTaskCulled` — the `bfsOnly` fallback — where it IS the correctness
feed and must stay. The `meshelium.tune.noMaskOnOcclusion` candidate is
therefore DROPPED as already-shipped behaviour; no knob, nothing to
measure.

### 6. Wave-12 ledger additions

30. **Stage-bracket overhead is argued, not measured**: ARMED-off is
    JIT-dead by the static-final argument (the BenchRecorder precedent);
    ARMED-on adds ≤7 nanoTime pairs + array stores per frame. If the
    armed bench were somehow slower than an unarmed run by more than
    noise, the stage data itself would be suspect — a
    `-Pmeshelium.cpustages=false` bench leg is the on-demand A/B.
31. **skipVanillaPrep's one-frame hole** (first drawer throw after a
    skip) is deterministic-zero by the wouldOwnFrame mirror but cannot be
    proven against unknown future throws — `prepSkipHoleFrames` is the
    standing detector and the bench asserts it 0. Knob default OFF.
32. **cachedCull vs debug frustum capture**: under F3 frustum capture,
    extract skips applyFrustum and the CAPTURED frustum feeds
    `cam.cullFrustum`; the memo keys on the frustum's own matrix+camera
    bits, so a capture toggle changes the key and misses — argued, not
    exercised (debug-only feature, knob default OFF).

## UNVERIFIED ledger

1. `ChunkSectionLayerGroup.OPAQUE.layers()` = `[SOLID, CUTOUT]` — inferred
   from the renderGroup loop + "solidTerrain" profiler scope + the enum's
   ctor varargs; the `$values` initializer args were not read.
2. `FrameGraphBuilder.execute` runs passes in insertion order — assumed
   (toposort over readsAndWrites deps); irrelevant to our injection point,
   which sits INSIDE the main pass lambda.
3. Shipped native `shaderc.dll` accepts task/mesh kinds (binding constants
   verified; native compiler feature set pending the caps-probe trial
   compile — seam doc Q5 carry-over).
4. `VK_KHR_push_descriptor` sits in `REQUIRED_DEVICE_EXTENSIONS` (its string
   is in `VulkanBackend`'s pool and every vanilla draw depends on it, so it
   is required-in-practice; the Set membership itself was not read out).
5. `Globals.CameraOffset` exact sign/semantics — terrain.vsh quoted verbatim,
   not re-derived. Read `globals` extraction code before wave 4 parity work.
   *Wave-4 addendum:* moot for Meshelium's path — the drawer computes
   `originRelCamera = sectionOrigin − camera.pos` in doubles and never
   consumes CameraBlockPos/CameraOffset (wave-4 notes item 3); the vsh
   formula itself is now on record from the jar dump. The Java-side
   extraction (who writes CameraOffset, with what rounding) remains unread.
6. Synthetic-lambda mixin target stability (`lambda$addMainPass$0`) across
   loom remaps — fallback documented in the shopping list.
7. The `Int2ObjectOpenHashMap` draw-group key (hash of buffer identities,
   base 173/31 — bytecode read) groups draws by vertex/index-buffer combo so
   buffers rebind once per group — the MECHANISM is proven, the INTENT is
   interpretation.
8. *(wave 5)* Task-payload struct size: 72 B/section tight-packed is the
   GLSL declaration's arithmetic; whether the compiler rounds
   `MesheliumSectionTask` to 16-byte multiples (→ 80) is unverified — the
   Java budget uses 80 and the runtime cap assertion is against that
   ceiling, so a rounding surprise cannot underflow, only waste headroom.
9. *(wave 5)* "Nothing mutates `visibleSections` between
   `prepareChunkRenders` and the main-pass lambda" rests on the read
   render-order bytecode (`sectionOcclusionGraph.update` at ip 693-713,
   after the frame graph) — an exhaustive search for OTHER writers of the
   list inside the frame window was not done. A violated assumption would
   shift the feed by at most the delta between two same-frame reads, and
   only ever toward vanilla's own newer list.
10. *(wave 5)* NVIDIA/Intel task-stage preferences (e.g. NVIDIA's small
    preferred task workgroups) are hearsay until run on real silicon —
    MESHELIUM_TASK_WG_SIZE=32 is asserted only against spec minima and the
    9070 XT's probe output; cross-vendor tuning is wave 9 with hardware
    in hand.
11. *(wave 5)* `CameraRenderState.projectionMatrix` == the bytes of the
    bound `Projection` slice (`RenderSystem.getProjectionMatrixBuffer()`)
    — assumed from the Q2.5 table's "projection matrix (CPU copy)" row,
    not re-proven byte-for-byte. The task stage's frustum planes are
    extracted from the CPU copy while the mesh stage transforms with the
    GPU slice; a divergence would make the plane test cull against the
    wrong volume — the A/B pair is the detector, and the 0.5-block box
    inflation absorbs rounding-scale differences only.
12. *(wave 6)* The encoder's ZERO-color-attachment RenderPass path
    (output sizing "first non-null attachment", Q1.3) was never bytecode-
    traced for the depth-only case — wave 6 sidestepped it (color
    attached, colorWriteMask 0); a future depth-only raster pass must
    verify it first.
13. *(wave 6)* "A closed watertight box's projected silhouette covers
    every sample its contents cover" relies on standard fill rules +
    shared-edge watertight rasterization; universally expected, not
    device-verified. A violation would read as a distant 1-section hole
    in motion — the hidden-wall shots and 40/41 are the detectors.
14. *(wave 6)* Shaderc/driver acceptance of the occlusion shader set
    (atomicExchange from mesh AND fragment stages, per-primitive
    gl_PrimitiveID→fragment plumbing on RDNA4) pends the coordinator's
    run — same class of pending as every wave's shaders; failure latches
    `occlusionError` and the drawer keeps rendering on the BFS feed.
15. *(wave 6)* The stats readback ring rests on the same
    2-submits-in-flight throttle argument as FREE_FRAME_LAG (Q1.2) —
    proven for buffer FREES in 3b, extended here to host READS of
    transfer writes; HOST_COHERENT memory means no invalidate, but the
    claim "slot (statsFrames−3) is always complete" is analysis, not yet
    an observed-on-hardware fact. A violated assumption skews COUNTERS
    only (draws never depend on the readback).
16. *(wave 6)* `fill`/`tp` command behaviour in the singleplayer gametest
    context (permission level, relative-coordinate anchoring to @p) is
    patterned on the residency test's tp usage; the 65×41×3 fill volume
    (7995 blocks < 32768 limit) was checked, its interaction with unloaded
    chunks was not — the harness run settles it.
17. *(wave 7)* **VK_EXT_mesh_shader inter-workgroup primitive ordering**
    remains UNVERIFIED (carried from the study §6) — wave 7 SIDESTEPPED
    it (one workgroup per translucent draw; API order between draws), it
    did not resolve it. The wave-9 multi-WG translucent upgrade blocks on
    reading the spec text and/or testing on real silicon.
18. *(wave 7)* "Vanilla translucent terrain content never carries vertex
    alpha < 255" — asserted from the tint plumbing (biome water color is
    RGB; glass/portal quads are untinted; QuadInstance colors carry the
    lighter's brightness in RGB), not from an exhaustive dump of every
    vanilla block. The 16-byte format cannot store vertex alpha, so a
    counter-example would blend brighter in Meshelium's shot — the 60/61
    pair is the detector, and modded content is out of parity scope.
19. *(wave 7)* `visibleSections` is front-to-back — the Octree's
    camera-derived `AxisSorting` child ordering was read as fields +
    call shape, not decompiled line-by-line; the wave-4 recon asserted
    the same claim independently (Q2.4). A violated assumption would
    make VANILLA's own translucent order wrong too (renderGroup only
    reverses this list), so the A/B pair cannot distinguish it — it
    would detect only a Meshelium-vs-vanilla DIFFERENCE, which reversal
    of the same list precludes.
20. *(wave 7)* Shaderc/driver acceptance of the translucent mesh variant
    (shared-memory + barrier before SetMeshOutputsEXT, per-thread output
    loop, blend pipeline) pends the coordinator's run on the 9070 XT —
    same class of pending as every wave's shaders; a failure latches the
    drawer's error and vanilla resumes for BOTH groups.
21. *(wave 7)* The `nether_portal[axis=x]` setblock survival inside its
    obsidian ring under fill's neighbor updates is patterned on vanilla
    frame rules, not yet observed in the harness context; if the portal
    blocks pop, the scene silently loses one translucent family (water +
    stained glass still cover the pair) — check shot 60 for the portal.
22. *(wave 8)* **Window resize** — no harness API exists
    (ClientGameTestContext 5.1.1 javap'd: no resize/setWindowSize), so
    resize survives on the wave-2 static argument only (dynamic
    viewport/scissor, resolution-independent formats, transient per-frame
    UBOs; pipelines key on attachment FORMATS, which a resize preserves).
    A manual in-game resize by the owner is the honest check.
23. *(wave 8)* **Device-loss handling** is code-reviewed, never executed:
    VK_ERROR_DEVICE_LOST cannot be forced on healthy hardware from a
    gametest. The catch→latch→passive path shares its skeleton with the
    proven wave-2 containment; the claim "vanilla reports the loss
    itself" rests on the jar census (no client-side catcher of
    GpuDeviceLossException anywhere).
24. *(wave 8)* **ModMenu integration** compiles against the real
    modmenu-20.0.1 jar and follows its own fabric.mod.json's entrypoint
    key, but no dev run carries ModMenu (compileOnly) — the config button
    appearing in the owner's live instance is the acceptance, not any
    harness leg.
25. *(wave 8)* The **device-close sweep** runs on clean shutdown of the
    harness client after the last test; its evidence is the "device-
    lifetime objects destroyed" INFO line + no validation complaints in a
    `--vulkanValidation` run — asserted by the coordinator's log read,
    not by a gametest (the client is already past test execution when it
    fires).
26. *(wave 9)* **Timestamp-slot reuse safety** rests on the
    FREE_FRAME_LAG argument extended to HOST query resets: the per-write
    `vkResetQueryPool` (inside vanilla's `writeTimestamp`) touches a
    query index last written 8 frames ago, and the 2-submits-in-flight
    throttle proves that submission completed. Analysis, not yet an
    observed fact — same class as ledger 15; vanilla's own Tracy
    profiler ships the identical pattern. A violation corrupts TIMING
    NUMBERS only (draws never depend on the pool).
27. *(wave 9)* **timestampPeriod/timestampValidBits on RDNA4**:
    `DeviceInfo.timestampPeriod()` is trusted for ticks→ns; validBits is
    not queried (the vanilla abstraction doesn't expose it) — deltas are
    sanity-bounded instead (negative or >4 s ⇒ frame discarded +
    counted in `framesAnomalousCount`). Wrong-period evidence would be
    GPU sums wildly exceeding the CPU frame time in the bench JSON.
28. *(wave 9)* **VK_EXT_mesh_shader inter-workgroup primitive ordering**
    (= ledger 17, now with a test protocol): the coordinator runs the
    translucent parity scene with `-Pmeshelium.translucentMultiWG=true`;
    pixel-identical 60/61 ⇒ RDNA4 orders inter-WG primitives by
    dispatch order IN PRACTICE (a hardware finding, not a spec
    guarantee — the property stays default-OFF either way). Record the
    outcome here.
29. *(wave 9)* **Front-to-back opaque ordering is pixel-neutral** by the
    order-independence argument (TerrainDrawer.PROPERTY_FRONT_TO_BACK
    javadoc): exact only up to GEQUAL equal-depth ties between distinct
    coplanar overlapping opaque quads — a geometry class vanilla's
    section meshes are argued (not exhaustively proven) never to emit.
    Default ON; shots 40/41 on every run are the standing detector, and
    a `-Pmeshelium.frontToBack=false` A/B isolates it on demand.
