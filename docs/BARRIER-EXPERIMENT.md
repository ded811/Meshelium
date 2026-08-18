<!-- Assembled 2026-08-18 from the barrier recon session; verbatim agent deliverable. -->

# Barrier recon deliverable — vanilla Vulkan barrier map, Meshelium exposure, precise-barrier experiment design

All bytecode citations: `javap -c -p` over `C:\Users\mrszi\Documents\Projects\Attack Of the B-Team 1.26.2\attack-of-the-bteam-1.26.2\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-043a8b3edf\26.2\minecraft-merged-043a8b3edf-26.2.jar`. Repo citations are absolute under `C:\Users\mrszi\Documents\Projects\Attack Of the B-Team 1.26.2\meshelium-private\`. Web findings (task 4) were delivered to you directly by the research sub-agent (Nemez follow-up, kvark arXiv 2607.26506, RADV DCC refutation, RGP workflow) and are not restated here.

## 1. The vanilla barrier map (bytecode-definitive)

**1.1 The hammer itself.** `VulkanCommandEncoder.memoryBarrier(VkCommandBuffer, MemoryStack)` — public static:
- ip 0-2: `VkMemoryBarrier2.calloc(1, stack)` — exactly ONE global memory barrier, never buffer/image barriers
- ip 10-13: `srcStageMask(65536L)` = `VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT` (0x10000)
- ip 18-21: `srcAccessMask(98304L)` = `VK_ACCESS_2_MEMORY_READ_BIT` (0x8000) | `VK_ACCESS_2_MEMORY_WRITE_BIT` (0x10000)
- ip 26-29 / 34-37: dst stage/access identical (65536 / 98304)
- ip 42-51: `VkDependencyInfo` with `pMemoryBarriers` only, dependencyFlags left 0
- ip 57: `KHRSynchronization2.vkCmdPipelineBarrier2KHR`

The private instance overload just forwards `commandBuffer()` into the static (ip 1-5). Callers outside the encoder: `VulkanTransientMemory.endSubmit` (below) and Meshelium's own transfer CBs.

**1.2 `submitRenderPass()` — one full barrier per pass end, every pass.** ip 22 `vkCmdEndRenderingKHR` → ip 39 `endDebugGroup` → ip 62 checkpoint `END_RENDER_PASS` → ip 69 `currentRenderPass = null` → **ip 78 `memoryBarrier(stack)`**. No image barriers, no layout change, nothing else. This confirms `docs/VANILLA-FRAME-PATH.md:126-131` exactly.

**1.3 `submit()` — NO barrier at submit.** ip 1 `endCommandBuffer` (ends the shared CB) → ip 8 `transientMemory.endSubmit()` — which, per `VulkanTransientMemory.endSubmit` ip 111-127, appends one full static `memoryBarrier` at the TAIL of the transient upload CB iff `anyCommandRecorded`, then `vkEndCommandBuffer` (ip 165) → ip 23 `signalSemaphore(submitSemaphore, currentSubmitIndex, stageMask 65536)` → ip 30 `Submission.close()` → `VkSubmitInfo2` per SubmitStage → `vkQueueSubmit2KHR` (`VulkanQueue$Submission.close` ip 422) → ip 57-69 `awaitSubmitCompletion(currentSubmitIndex − 2, 5s)` — the 2-submits-in-flight throttle → pool/destroy/checkpoint rotate (ip 110-134). So the frame's synchronization is: full barrier after every pass + after every transfer op + at upload-CB tail, and timeline semaphores between submits — never anything scoped.

**1.4 One more full barrier after every out-of-pass op.** Same instance `memoryBarrier`, always AFTER the op (pre-op ordering rides the standing invariant): `clearColorTexture` ip 13, `clearColorAndDepthTextures` ip 25, `clearDepthTexture` ip 16, `writeToBuffer` ip 84, `copyToBuffer` ip 72, `writeToTexture` ip 148, `copyBufferToTexture` ip 167, `copyTextureToBuffer` ip 133, `copyTextureToTexture` ip 148.

**1.5 Layouts — GENERAL is enforced at THREE sites.**
- Creation: `VulkanGpuTexture.<init>` — `initialLayout(0)` UNDEFINED ip 103-104; after `vmaCreateImage` (ip 200), a legacy `VkImageMemoryBarrier` `oldLayout(0)`→`newLayout(1)` GENERAL ip 241-249, srcAccess 0 (ip 255), dstAccess 98304 (ip 262-264), via `vkCmdPipelineBarrier(cb, srcStage=1 TOP_OF_PIPE, dstStage=65536 ALL_COMMANDS, 0, …)` ip 359-367 — recorded on `textureInitCommandBuffer()`, which is literally `return commandBuffer()` (the shared frame CB). Never moved again.
- Attachment: `createRenderPass` — color `imageLayout(1)` ip 371-372, depth `imageLayout(1)` ip 591-592 (unused slot: view 0 + `imageLayout(0)` ip 481-482); `vkCmdBeginRenderingKHR` ip 688 on the shared CB.
- Descriptor: `VulkanRenderPass` bind-group update writes `VkDescriptorImageInfo.imageLayout(1)` (ip 592-593 in its dump).
- Only non-GENERAL traffic is the swapchain inside `VulkanGpuSurface.blitFromTexture`: UNDEFINED→TRANSFER_DST_OPTIMAL(7), blit, 7→PRESENT_SRC_KHR(1000001002), own sync2 barriers.

**Mod-legal consequence for the layout suspect:** a mod cannot re-layout vanilla's images safely — all three sites assume GENERAL, and compression metadata is a creation-time decision anyway (your RADV DCC findings). In-window GENERAL→optimal→GENERAL transitions are legal but buy nothing on images created for GENERAL life. Layouts are therefore a measure-and-report-upstream item (RGP + Mojira), not a mod fix. Barriers are the mod-actionable half.

**1.6 CB model facts the experiment leans on.** `commandBuffer()` lazily allocates and registers with the submission (ip 30-46); the shared CB stays current across `submitRenderPass` (only `execute`/`submit` end it). `execute(cb)` refuses inside a pass, ends the shared CB, appends yours in submission order (ip 18-27). `writeTimestamp` = host `vkResetQueryPool(…, idx, 1)` ip 18 + `vkCmdWriteTimestamp2KHR(stage 65536)` ip 25-30 on the shared CB.

**1.7 Frame context.** Vanilla's frame graph runs clear → sky → main → entity-outline → clouds → weather → transparency chain → always-on-top (FRAME-PATH:257-264) plus GUI passes plus per-op barriers plus the upload-CB tail — order tens of full stalls per frame. That is where the community's AMD/Intel regression lives; a mod can only fix its own window (the all-memory-visible invariant is load-bearing for every other consumer, FRAME-PATH:129-131).

## 2. Our exposure

Window: drawOpaque's cancelled-renderGroup window — 4 passes + stats CB (`src/main/java/com/deds/meshelium/vk/TerrainDrawer.java:1943-2045`, `TerrainOcclusion.java:123-142`).

| # | Barrier | Site | Verdict |
|---|---|---|---|
| 1 | pass-1 end (phase A) | vanilla, inherited | **convertible** |
| 2 | pass-2 end (region raster) | vanilla, inherited | **convertible** |
| 3 | pass-3 end (section raster) | vanilla, inherited | **convertible** |
| 4 | pass-4 end (phase B) | vanilla | **retained** — restores the invariant for vanilla's feature passes |
| 5 | stats CB WAR (copy→zero) | ours, `TerrainOcclusion.java:655` | **convertible** |
| 6 | stats CB final | ours, `TerrainOcclusion.java:665` | **convertible** |

Variants: phaseBCpuSkip frames (the static bench norm) run passes 1-3 → pass-3 end becomes the retained one, 4 convertible. bfsOnly: 1 pass, only the 2 stats barriers convertible. **Rule: the LAST Meshelium pass of the window always keeps the full barrier.** Event-driven extras (not steady state, phase 2): pump CB up to 3 full barriers per upload frame (`MesheliumTerrainGpu.java:710,719,722`), resize/init sites (315, 453, 486, 545, 634), occlusion standup fill (`TerrainOcclusion.java:348`) — all transfer-domain, trivially scopable later.

**Minimal scopes per boundary** (writers/readers exhaustively enumerated: stats written only by `terrain.task:330` atomicAdd, TASK stage, passes 1/4, read only by the stats copy; regionStamps written by `box.frag:135-147` FRAGMENT + `region_raster.mesh:154` MESH, read by `section_raster.task` TASK; curStamps written by `box.frag` FRAGMENT + `section_raster.mesh:160-165` MESH, read by phase-B TASK + the later translucent gate; predicate written pass 3, read via conditional rendering `TerrainDrawer.java:2024-2039`, zeroed at `TerrainOcclusion.java:663`; depth written passes 1/4, read-only GEQUAL passes 2/3; color written 1/4, mask-0 LOAD in 2/3):

- **A→regions:** src `EARLY_FRAGMENT_TESTS|LATE_FRAGMENT_TESTS|COLOR_ATTACHMENT_OUTPUT` / `DEPTH_STENCIL_ATTACHMENT_WRITE|COLOR_ATTACHMENT_WRITE` → dst same stages / `DEPTH_STENCIL_ATTACHMENT_READ|COLOR_ATTACHMENT_READ|COLOR_ATTACHMENT_WRITE`. Phase-A stats writes deliberately deferred — nothing reads them before the retained full barrier.
- **regions→sections:** src `FRAGMENT_SHADER|MESH_SHADER_EXT` (+attachment stages for chaining) / `SHADER_STORAGE_WRITE` → dst `TASK_SHADER_EXT` (+`EARLY|LATE`) / `SHADER_STORAGE_READ` (+`DEPTH_STENCIL_ATTACHMENT_READ`). Each boundary repeats the attachment scopes on both sides so pass-1 depth visibility chains transitively to passes 3/4 (sync2 dependency chaining).
- **sections→B:** src `MESH_SHADER_EXT|FRAGMENT_SHADER` (+attachment stages) / `SHADER_STORAGE_WRITE` → dst `TASK_SHADER_EXT|EARLY|LATE|COLOR_ATTACHMENT_OUTPUT` / `SHADER_STORAGE_READ|DEPTH_STENCIL_ATTACHMENT_READ|DEPTH_STENCIL_ATTACHMENT_WRITE|COLOR_ATTACHMENT_WRITE`, **plus `CONDITIONAL_RENDERING_BIT_EXT` / `CONDITIONAL_RENDERING_READ_BIT_EXT` when `phaseBPredicateActive()`**.
- **Stats WAR (#5):** src `ALL_TRANSFER`/`TRANSFER_READ` → dst `ALL_TRANSFER`/`TRANSFER_WRITE` (ALL_TRANSFER, not COPY|CLEAR, sidesteps the fill-buffer stage-classification argument — spec-unclear-must-cite discipline).
- **Stats final (#6):** src `ALL_TRANSFER`/`TRANSFER_WRITE` → dst `TASK|MESH|FRAGMENT` (+conditional when armed) / `SHADER_STORAGE_READ|SHADER_STORAGE_WRITE` (+`CONDITIONAL_RENDERING_READ`). Barriers are queue-scoped, so this covers next frame's submission.

All single `VkMemoryBarrier2` — no image barriers (layouts never move), no per-buffer barriers needed.

## 3. The experiment — two routes, recommendation: Route B

**Route B (recommended): keep vanilla pass machinery, intercept only the pass-end barrier.** Extend the existing `VulkanCommandEncoderMixin` (which already instruments `submit()`) with a `@WrapOperation` (MixinExtras is loader-bundled; plain `@Redirect` + one `@Invoker` also works) on the single `memoryBarrier(MemoryStack)` invocation inside `submitRenderPass` (ip 78). Handler: if a consumed-on-read render-thread flag is set → skip original; else call original. The drawer, under `-Dmeshelium.preciseBarriers=true` (re-read every call, bfsOnly-style, for live same-session A/B), sets the flag before `pass.close()` for every non-last Meshelium pass, then records the scoped `vkCmdPipelineBarrier2` on the captured cb (the shared CB is still current after `submitRenderPass` — verified §1.6). The stats-CB barriers are our own calls, swapped under the same property with no mixin. The two arms then differ by nothing but barrier scope — same CB, same begin/end, same viewport, same checkpoints, same marks. Cleanest attribution, smallest diff, fully property-reversible.

**Route A: fully own CB** (`allocateAndBeginTransientCommandBuffer()` + own `vkCmdBeginRenderingKHR` scopes + scoped barriers + `execute(cb)` — the FRAME-PATH:466-472 row-3 graduation). No encoder mixin at all, total isolation — but it owes attachment infos, vanilla's exact y-flipped viewport (`VulkanRenderPass` ctor ip 110-153) and scissor, and re-plumbed timers, and it introduces two confounds for the A/B (CB split around the window, our own begin path). Wrong tool for the measurement; right shipping shape later if B wins and we want isolation or to merge with multi-pass recording.

**Mark placement (separates the bubble, both arms identically).** Today every mark sits after the inherited barrier, so pass windows include their trailing bubble (`MesheliumGpuTimers.java:85-88`). Add E_k = timestamp AFTER `vkCmdEndRenderingKHR`, BEFORE the barrier — the wrap point IS that gap, in both arms. Then pass-k-as-scheduled = E_k − B_{k−1}; barrier-k bubble = B_k − E_k (B_k = existing after-barrier mark). Two more pairs inside the stats CB around barriers #5/#6, one post-stats mark on the encoder. QUERIES_PER_FRAME 8→16, same ring, same reset-per-write discipline. Caveat recorded: ALL_COMMANDS timestamps are drain-observing, so E_k includes the pass drain and B_k−E_k isolates barrier-added cost (flush/inv + extra serialization) — exactly the disputed quantity.

**Step 0 (the kill test, before any suppression code exists):** ship only the E_k marks as an instrumentation-only inject and measure the CURRENT full-barrier bubbles in one same-session run. If Σ bubbles ≤ ~20 µs, kill the experiment. If 50-150+ µs, build the suppression arm.

**Validation requirement:** vanilla's own switch is the client arg `--vulkanValidation` (Main ip 86 → `GameConfig$GameData.vulkanValidation` → Minecraft ip 1101-1121 → VulkanBackend ip 108-111 → VulkanInstance ip 75-93 enables `VK_LAYER_KHRONOS_validation`). Required for the precise arm: one clean plain-validation run AND one with synchronization validation (not on by default — force `VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION` via vkconfig on javaw) over bench + build-storm + camera-cut scenes, plus one with conditional rendering armed. Zero sync-val findings in our window is the gate. Never measure with layers on.

## 4. Risks

1. **Missed edge → intermittent frame-late stamps/flicker.** Mitigation: scopes derived from the exhaustive writer/reader enumeration above; sync-val; pixel harness incl. wall-heavy scene (superflat blind spot); existing dropped-stamp detectors (OCCLUSION-FILLRATE:456-480).
2. **Last-pass identity varies** (phaseBCpuSkip / bfsOnly / phaseBSkipUnsafe): suppress only when another Meshelium pass follows; unit-test the decision table.
3. **Flag leak to a vanilla pass end = global corruption.** Consumed-on-read, try/finally, dev-run assert on the pass label.
4. **Mixin fragility across MC patches** — audit ip 78 target per version bump (existing discipline).
5. **Driver priors are weak on this rig**: the conditional-rendering predicate was mechanically perfect and RDNA4 still charged ~9 ms of driver stalls. Scoped barriers could lower to the same cache ops as full ones on RADV/AMDVLK. Measure, never assume.
6. **Same-session A/B only** (documented 62% cross-session drift).
7. **Stats-CB bubbles are probably ~0 already** (everything drained by the retained barrier just ahead of the splice) — expect #5/#6 to measure ≈0; don't sell them.
8. **Predicate-arm scope omission** = wrong skip = invisible terrain for a frame; gate-tested.

## 5. Prize, honestly

Anatomy (`docs/SPEEDUP-CANDIDATES-2026-08.md:45-46`): 1.96 ms p50 at plains-rd64/1440p = opaque 0.85 + rasters 0.29 + phase B 0.00 (CPU-skipped) + translucent 0.44; GPU ≈1.6 ms. Static frames convert 4 barriers, moving frames 5. The scoped barrier keeps the true fragment→task serialization — the save is only the narrower cache flush/invalidate set and not draining unrelated domains. Prior-based range: **1-10 µs per converted barrier → ~5-50 µs/frame ≈ 0.3-3% of the GPU frame on RDNA4** (780M likely at the relative high end). This is NOT the community-regression headline — that lives in vanilla's tens of per-frame full stalls plus the layout story, which a mod cannot legally fix for vanilla's passes. Upgrades to the estimate: Step 0's measured E→B bubbles (direct, one session, this rig), an RGP capture reading per-barrier durations in our window (your workflow findings), and any published RDNA3/4 barrier microbenchmarks from the web track. Structural value regardless of the number: every future Meshelium pass (HiZ compute mips explicitly need scoped barriers per OCCLUSION-FILLRATE:597-607, LOD passes) stops inheriting a full stall by default.
