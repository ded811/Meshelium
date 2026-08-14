<!-- Provenance: 17-agent design workflow, 2026-08-13. Four recon agents over
vanilla-s upload path, CPU-side retention, Meshelium-s seam and the memory
accounting; three competing designs; nine adversarial reviewers; one synthesis.
Two of the three designs were killed at the bytecode by the reviewers.

VERIFIED INDEPENDENTLY BY THE COORDINATOR before landing this file:
  - vanilla-s terrain vertex is 28 bytes. VanillaMeshDecoder.java:72 declares
    BLOCK_STRIDE = 28 and :134 REJECTS the layer at runtime if vanilla-s format
    size differs, so it is an enforced invariant, not a derivation.
  - UberGpuBuffer.uploadStagedAllocations closes at most ONE heap per call and
    only when its TlsfAllocator.isCompletelyFree() (javap ip 629, 646, 658).
    Suppression therefore frees nothing in an already-loaded world.
-->

All load-bearing claims verified against the 26.2 jar and the repo. Writing the plan of record.

# PLAN OF RECORD — Suppressing Vanilla's Duplicate Terrain Copy

**Status:** APPROVED IN PRINCIPLE, BLOCKED ON ONE MEASUREMENT. Date 2026-08-13.
**Verification basis:** every vanilla claim below was javap'd by me today against `minecraft-merged-043a8b3edf-26.2.jar`; every repo claim carries a file:line I read today. Items I could not verify are labelled **UNVERIFIED** inline.

---

## 1. IS THE PREMISE TRUE?

**Directionally yes. Numerically no — the brief's framing is wrong and must not survive into the ticket.**

### What holds

Vanilla's terrain vertex is **28 bytes**, verified from the constants, not from a summary:

```
javap -p -c -constants ... com.mojang.blaze3d.vertex.DefaultVertexFormat
  static{} ip 0-27:  POSITION_FORMAT = RGB32_FLOAT ; COLOR_FORMAT = RGBA8_UNORM
                     UV0_FORMAT = RG32_FLOAT      ; UV2_FORMAT   = RG16_SINT
  static{} ip 43-81: BLOCK = builder().addAttribute("Position",POSITION)
                              .addAttribute("Color",COLOR).addAttribute("UV0",UV0)
                              .addAttribute("UV2",UV2).build()
```

12 + 4 + 8 + 4 = 28, four attributes, no Normal, no UV1. This is independently corroborated by shipping Meshelium code that would break if it were wrong: `VanillaMeshDecoder.java:72` declares `BLOCK_STRIDE = 28` and `:134` **rejects the layer at runtime** if `drawState.format().getVertexSize() != BLOCK_STRIDE`. Meshelium is decoding vanilla's terrain successfully today, so 28 is not a derivation — it is an enforced runtime invariant.

Against Meshelium's `TerrainVertexCodec.VERTEX_STRIDE = 16` / `QUAD_STRIDE = 64`, vanilla's copy of identical geometry is **112 B/quad versus 64 B/quad — 1.75×, larger than the entire arena.**

At the owner's recorded failure point, `61,968,382 × 112 = 6,940,458,784 B = 6,619 MiB = **6.46 GiB of vanilla vertex payload***. Heaps are whole 128 MiB blocks (`SectionRenderDispatcher.lambda$new$0` ip 19 `ldc 134217728` vertex, ip 43 `ldc 33554432` index) with a **separate `nodes` list per layer** (`private final List<Pair<TlsfAllocator, UberGpuBufferHeap>> nodes`), so three tails round up independently: ≥52 heaps, ≤55 with tails, plus unmeasured TLSF fragmentation. **Committed: 6.6–7.5 GiB, of which only the low end is derivable.**

### What does not hold, and must be struck

**(a) The "exact anchor" cross-check is a tautology.** All three designs lean on "61,968,382 × 64 = 3782 MiB matches the printed arena figure, so the count is trustworthy." I read the printer:

```java
// TerrainResidency.java:1580
arenaUsed = (arena.liveQuads() - 1) * 4L * TerrainVertexCodec.VERTEX_STRIDE;
```

The printed figure **is defined as** quads × 64. The identity cannot fail for any quad count. It verifies nothing and must be deleted from the doc. The premise survives on other evidence: `MesheliumScaling.java:371-375` preserves the owner's session verbatim in source — *"capacity 4096, headroom 1824, block 2048 / floored -> ceiling 4096, used 3782 = 92%"* — which is a genuine recorded datapoint reproducing the count to within MiB rounding.

**(b) "~10 GB is vanilla" is refuted.** The floor is 6.46 GiB of payload; the ledger ceiling cannot accommodate 10 GB alongside atlases, render targets and the swapchain. Roughly **2–3 GiB of the owner's VRAM is neither Meshelium's nor vanilla's terrain and has never been measured by anyone.** Suppression is ~70% of the problem, not the whole of it. Schedule the residual as its own investigation; do not let it hide inside this one.

**(c) The 13.9 → 6.9 GB delta is unusable, and unnecessary.** Two designs derive ~7.55 GiB from it via an invented area ratio `V32 ≈ V120 × (65/241)²` and a residual "everything else" term, then cite the 17% agreement with the payload figure as corroboration. That is a fitted parameter matching a fitted parameter. **Strike derivation 2 entirely.** The payload floor uses neither VRAM reading, so the session-continuity question comes off the critical path.

**(d) Meshelium's set is a strict subset, not an equal set.** `VanillaMeshDecoder.java:102-117,162-172` emits exactly one quad per vanilla quad, but `:131-138` skips whole malformed layers, and the arena was at 92% *actively refusing a 2048 MiB growth* at the sampled instant. 6.46 GiB is a **floor** on vanilla, not an estimate.

### The premise failure that matters more than the magnitude

**The seam removes upload traffic, not committed heaps — and nothing in any of the three designs forces the committed heaps to drain.** Verified: `UberGpuBuffer.uploadStagedAllocations` has **no early return** when `stagedAllocations` is empty (ip 0-47 iterates an empty keySet, ip 61-75 opens a loop that immediately falls through, ip 534-545 clears both collections, ip 599-664 runs the reclaim tail unconditionally). The tail closes **one heap per call and only when its `TlsfAllocator.isCompletelyFree()`** (ip 629 `isCompletelyFree`, ip 646 `GpuBuffer.close`, ip 656-658 `iconst_1 / goto 664` — break).

So arming the seam in a settled rd-120 world with a stationary camera frees **approximately zero**: the existing allocations are live, no allocator is completely free, and vanilla only recompiles dirty sections. Every design's headline number is contingent on arming at a moment when the heaps are already empty — a precondition only Design 3 half-noticed and none of them made binding.

**Verdict: build for a ~6.5 GiB floor, not a 7–10 GiB prize, and treat "force the heaps to drain" as part of the mechanism rather than a footnote.**

---

## 2. RECOMMENDATION

**Build the late seam — a single cancelling `@Inject(HEAD)` on `SectionRenderDispatcher$RenderSection.addSectionBuffersToUberBuffer` — merged into the handler Meshelium already owns there, gated to fire only above vanilla's render distance, and shipped default-OFF for one release. But do not write a line of it until the fifteen-line read-only heap census has run on the owner's rig in one session, and do not ship it at all unless the ownership rework lands with it: on every demotion Meshelium must keep drawing its own holey or stale picture until vanilla is plausibly back, because the seam destroys the coverage guard's founding premise that vanilla is whole.** The two competing seams are both dead at the bytecode — the `@Redirect` wedges every non-translucent section at `UNCOMPILED` forever, and the compile-RETURN seam routes every opaque section down a branch that never re-checks cancellation and can therefore free a *newer* mesh out from under both renderers. The late seam is the only one of the three that leaves vanilla's own state machine and its cancellation checkpoint intact. The remaining cost is honest and bounded: on a hard mid-frame drawer throw the player sees an empty world for the duration of a rebuild nobody has yet timed, and that number — an F3+A stopwatch at rd 120, free, today — is the go/no-go the owner has to make, not me.

---

## 3. WHY NOT THE OTHERS

### Design 3 (Starve-and-Clamp, `@Redirect` on `addAllocation`) — FATAL, confirmed at the bytecode

The redirect fires vanilla's `UploadCallback` synchronously at ip 86. I read the full method:

```
addSectionBuffersToUberBuffer  (RenderSection)
    0: iconst_1 / istore 5           <- result starts TRUE
    3-10: copyLock.lock()
   13-15: getSectionDraw(layer)      <- read INSIDE the lock
   20-22: ifnull 145                 <- no draw => skip body, return true
   62-63: aload_3 / ifnull 92        <- vertexData != null ?
      86: addAllocation(vertex)  [callback = lambda$0 -> setVertexBufferUploaded + checkSectionMesh]
   92-94: aload 4 / ifnull 140       <- indexData != null ?
     131: addAllocation(index)
 140-142: setIndexBufferUploaded     <- indexData == null branch: NO checkSectionMesh
 145-160: if (!result && onRenderThread) uploadTerrainBuffersToGpu()   <- self-flush
 163-170: copyLock.unlock()
```

For every section with no translucent geometry — the overwhelming majority — the layer's last act is ip 140-142, which sets the index flag and **never calls `checkSectionMesh`**. Under the redirect, `checkSectionMesh` has already run at ip 86 with the index flag still false. And `checkSectionMesh` ANDs *both* flags for every layer with a non-null `SectionDraw` (ip 39-56) before installing at ip 78-86. Nothing re-checks: `CompileTask.doTask` has no `setSectionMesh` after the layer loop (ip 440 → `SUCCESSFUL`, verified). The section stays at `UNCOMPILED` forever, `facesCanSeeEachother()` returns false, occlusion propagation dies, `visibleSections` collapses to the camera shell — **and Meshelium starves with it**, because its build feed rides vanilla's `visibleSections`. Sections near water still install, so it presents as intermittent. Design 3's own recovery machinery (the render-distance clamp) is worth keeping and is folded in below; its seam is not.

### Design 2 (Empty-Results seam at `SectionCompiler.compile` RETURN) — FATAL, confirmed at the bytecode

Design 2's structural argument is that clearing SOLID+CUTOUT from `renderedLayers` routes the section down "vanilla's own empty-layer path, which it runs for every air section already." The path exists — but it is **not** semantically equivalent, and the difference is a correctness bug that reaches into Meshelium:

```
CompileTask.doTask
      1,29: isCancelled.get() -> CANCELLED        (early, before compile)
   159-164: new CompiledSectionMesh(...)
   166-176: renderedLayers.isEmpty() ? -> 179 : -> 250
   179-185: setSectionMesh(mesh)        <-- NO isCancelled READ ANYWHERE FROM ip 42 TO HERE
   200-209: copyLock.lock(); releaseSectionMesh(OLD); ...
   246-249: return SUCCESSFUL
      ---- non-empty path ----
   304-316: isCancelled.get()  -> ip 380 return CANCELLED      <-- the checkpoint
      410:  addSectionBuffersToUberBuffer(...)
```

Vanilla re-reads `isCancelled` **before every layer upload** (ip 310) on the normal path. The empty branch never reads it, which is harmless in vanilla because only genuinely-empty sections take it. Design 2 routes *every opaque section in the world* down that branch carrying real state. A worker whose task was cancelled by `createCompileTask`'s `cancelTasks()` — routine on any block break — resumes at ip 166, installs its stale mesh at ip 185 unconditionally, and at ip 209 `releaseSectionMesh` frees the **newer** mesh. That release is exactly what drives `RenderSectionMixin.java:98-114 → TerrainResidency.onMeshReleased`, so it inverts the ctor-before-release invariant Meshelium relies on and corrupts *both* renderers. The window spans a globally contended lock (`TerrainResidency` `synchronized (LOCK)`) and the superseding task is frequently `compileSync` on the render thread.

Design 2 also needs bulk-dirty plumbing Meshelium does not have (`grep -rn "levelExtractor|allChanged|setSectionRangeDirty|setSectionDirty" src/main/java` → **zero hits**), and its arming gate on arena headroom bootstrap-deadlocks: the watermark is already tripped at 92% and the thing that would untrip it is the suppression it is blocking.

**The decisive point, which none of the nine reviews made: the late seam preserves vanilla's cancellation checkpoint and the early seam destroys it.** The late seam leaves `renderedLayers` populated, so the section stays on the non-empty path and ip 304-316 keeps guarding every layer. That is a correctness property, not a preference.

### Design 1 (Armed Upload Seam) — the survivor, with three of its own claims struck

Design 1's seam is right. Three of its supporting claims are false and are replaced below:

1. **"Class A — Meshelium can still draw."** False for the common case. `coverageGuardBlocks()` (`TerrainDrawer.java:1182-1202`) sets `coveragePassive = true` and `drawOpaque` does `opaqueOwnedSerial = -1; return notePrepOutcome(false)` (`:1386-1389`), so the kill switch never cancels and vanilla renders against buffers the seam left empty. `dropsThisWorld()` is monotonic per world and **one** dropped section trips it. Forcing retention on does not help — retained geometry is useless to a drawer that has gone passive, and it pins ~4 GiB during the exact event that says VRAM is exhausted.
2. **Recovery driver in the pump hook.** `MesheliumTerrainPump.afterVanillaTerrainUpload()`'s first statement is `if (broken) { return; }`, and `broken` is latched on device loss and on any throwable from `TerrainResidency.pump`. Its call site latches identically (`LevelRendererMixin.java:336-337`). Four of Design 1's own nine demote triggers permanently disable its recovery consumer.
3. **`@Shadow` of the synthetic `this$0`.** Zero `@Shadow` and zero `@Invoker` exist anywhere in `src/main/java` (7 `@Accessor` files, all interface accessors). A verified public route exists and is used below.

---

## 4. THE DESIGN

### 4.1 The seam

**Target:** `net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection#addSectionBuffersToUberBuffer` — descriptor verified exactly:

```
(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;
 Lnet/minecraft/client/renderer/chunk/CompiledSectionMesh;
 Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)Z
```

**Home:** `com.deds.meshelium.fabric.mixin.RenderSectionMixin`, **merged into the existing HEAD handler at `RenderSectionMixin.java:130-153`**, not added as a second one. A cancelling callback emits a cancellation check that short-circuits later HEAD handlers on the same method; whether Mixin orders them deterministically is **UNVERIFIED** (no mixin jar on disk, gradle forbidden). Merging makes the question moot. Order inside the merged handler: resort tap first, seam second.

**Reaching the lock without `@Shadow`.** Verified public: `Minecraft.levelRenderer` is `public final`, and `LevelRenderer.sectionRenderDispatcher()` is `public`. `SectionRenderDispatcher.lock()`/`unlock()` are public and are literally `copyLock.lock()/unlock()` — the same `ReentrantLock` vanilla takes at ip 3-10, and reentrant. Reach `checkSectionMesh` (private, verified) with an `@Invoker("checkSectionMesh")` interface, matching the repo's accessor-interface pattern. This is the repo's **first `@Invoker`** — flagged as new territory, though `@Invoker` on a private instance method is ordinary.

```java
// inside the merged handler, after the resort tap, before returning
if (meshelium$seamBroken || !VanillaUploadSeam.armed()) return;
try {
    LevelRenderer lr = Minecraft.getInstance().levelRenderer;
    SectionRenderDispatcher d = lr == null ? null : lr.sectionRenderDispatcher();
    if (d == null) return;                       // fall through uncancelled

    d.lock();                                    // == copyLock, reentrant
    try {
        // Read getSectionDraw INSIDE the lock, exactly where vanilla reads it
        // (ip 13-15, after the lock at ip 3-10). CompiledSectionMesh.close()
        // clears the maps; an unlocked check races releaseSectionMesh into an
        // NPE inside setVertexBufferUploaded.
        if (mesh.getSectionDraw(layer) == null) {           // mirrors ip 20-22
            cir.setReturnValue(Boolean.TRUE);
            return;
        }
        // EXACTLY vanilla's flag order and conditionals:
        //   ip 62-63  vertex flag only when vertexData != null
        //   ip 140-142 index flag unconditionally otherwise
        //   checkSectionMesh ONCE, after every flag is set
        if (vertexData != null) mesh.setVertexBufferUploaded(layer);
        mesh.setIndexBufferUploaded(layer);
        ((RenderSectionInvoker) this).meshelium$checkSectionMesh(mesh);
    } finally {
        d.unlock();
    }
    cir.setReturnValue(Boolean.TRUE);   // never false => the spin loop never spins
} catch (Throwable t) {
    meshelium$seamBroken = true;
    VanillaUploadSeam.demote("seam handler threw: " + t);
    LOGGER.error("Meshelium upload seam failed; vanilla uploads resume this session", t);
    // uncancelled fall-through: vanilla stages this section normally.
}
```

**Why the bookkeeping is complete and the fallthrough is safe.**

- The upload flags have exactly one consumer. All four accessors are `public` on `CompiledSectionMesh` and appear in only two class files jar-wide (`CompiledSectionMesh`, `RenderSection`); they are **not** on the `SectionMesh` interface, so no polymorphic consumer can exist.
- `checkSectionMesh` is **idempotent** — ip 63-75 is `if (!allUploaded) return; if (sectionMesh.get() == mesh) return;` before installing. Re-setting `AtomicBoolean` flags is idempotent too. So the error fallthrough (seam ran partially, then vanilla stages normally) is safe in both directions. *This is not stated in any of the three designs.*
- Zero heaps are created while armed. `addAllocation` ip 0-16 is `tryAppend / ifnonnull 17 / iconst_0 / ireturn` — **no `createBuffer`, no `allocate` anywhere in its body**. Every `TlsfAllocator.allocate` (ip 199, ip 354) and the single `new UberGpuBufferHeap` (ip 293) live inside `uploadStagedAllocations`' per-staged-entry loop (opens ip 61-75, exits ip 534). With `stagedAllocations` empty the body never executes.
- The per-frame flush stays a live no-op, so `LevelRendererMixin.java:321-334` (`@At INVOKE ... uploadTerrainBuffersToGpu, shift=AFTER`) keeps firing and the residency pump survives. **Never redirect away that call site.**
- Cancelling `uploadTerrainBuffersToGpu` itself is forbidden and the reason is bytecode-certain: it is the only caller of `StagingBuffer$Uploader.close()` → the only caller of `tryClearAndRotate()` → the only reset of `nextWriteOffset`. Cancel it and `tryAppend` returns null forever and `CompileTask.doTask` pegs every worker in `Thread.onSpinWait` (ip 415-429). Leave it alone.

### 4.2 The state machine — `com.deds.meshelium.terrain.host.VanillaUploadSeam`

**Hosted on `ClientTickEvents.END_CLIENT_TICK`, not on the pump hook.** The repo already registers unconditional tick handlers (`MesheliumExtendedRd.java:295`, `MesheliumGate.java:82`); this is a third. The handler carries its own try/catch whose failure mode is `armed = false` + request rebuild, never `return`.

```java
armed()                      // volatile read, hot on build workers
demote(String reason)        // ANY thread. armed = false FIRST, unconditionally,
                             // BEFORE any `if (alreadyDemoted) return` guard.
                             // Use a CAS on the arm/demote pair, not two stores.
```

Arming predicate, re-evaluated **level-triggered every tick** (never edge-latched — a latch that fired in world A is never re-fired in world B):

```
MesheliumConfig.suppressVanillaUploadsEnabled()      // meshelium.tune.suppressVanillaUploads, DEFAULT OFF
 && MesheliumGate.state() == VULKAN_MESH_SHADERS
 && MesheliumConfig.terrainRenderingEnabled()
 && MesheliumPassive.reason() == null                // the single funnel, below
 && mc.level != null
 && mc.options.getEffectiveRenderDistance() > MesheliumExtendedRd.vanillaMaxRenderDistance()
 && !Boolean.getBoolean(MesheliumExtendedRd.PROPERTY_BENCH_NO_CLAMP)
 && VanillaUploadSeam.heapsAreDrainable()            // see 4.4
```

The render-distance term is the single most important line, and Design 3 was right about it: below vanilla's max there is no clamp to ride, no invalidate to recover through, and a prize of ~0.5 GiB. **Consequence that must be written down: `Options.getEffectiveRenderDistance()` is `serverRenderDistance > 0 ? min(option, server) : option`, so on any server with view distance ≤ 32 the seam can never arm. The 6.5 GiB is a singleplayer/Bobby/permissive-server number.** Use `getEffectiveRenderDistance()` here *and* teach `MesheliumExtendedRd` to key on the same quantity — today it clamps on the raw `options.renderDistance().get()` (`MesheliumExtendedRd.java:501`), and two different render-distance views driving arm and recovery is a latent drift.

Per-world reset must sit at a site no other component's latch can skip — **not** behind `MesheliumTerrainPump.onDispatcherDispose` (verified: a throw from `TerrainResidency.disposeAndReset()` at `MesheliumTerrainPump.java:75` jumps to the catch at `:94` and skips everything after; and `SectionRenderDispatcherMixin`'s own `disposeHookBroken` latch can skip it entirely).

### 4.3 One funnel, compiler-enforced

Nine hand-written demote sites with no compiler check is how a passive path added later becomes an empty world. Route every latch through one helper:

```java
MesheliumPassive.latch(Reason r)   // sets the per-site broken flag AND demotes
MesheliumPassive.reason()          // null == healthy; consumed by BOTH the seam
                                   // predicate AND MesheliumExtendedRd.drawerHealthy()
```

Make the per-site booleans private to that helper. `drawerHealthy()` today reads only `TerrainDrawer.lastError()` and `coveragePassive()` (`MesheliumExtendedRd.java:589-594`), so **the clamp never fires** for kill-switch-hook death, pump death, or frame-state-hook death. Those must go in, or the seam demotes at rd 120 with no clamp and vanilla rebuilds the full rd-120 world on top of Meshelium's un-freed arena — re-creating the exact 13.9 GB state this exists to eliminate.

Accessors the predicate needs and that **do not exist today**: `TerrainDrawer.isBroken()`, `TerrainDrawer.killSwitchBroken()`, `MesheliumTerrainPump.isBroken()`. Only `lastError()` (`:790`), `coveragePassive()` (`:1043`) and `deviceLost()` (`:1053`) exist. The published predicate does not currently compile.

Add a **watchdog** the explicit call sites cannot substitute for: armed AND `TerrainDrawer.wouldOwnFrame()` false for N consecutive frames (N = 3) → demote, with its own counter alongside `prepSkipHoleFrames`. `wouldOwnFrame()` (`TerrainDrawer.java:1116-1133`) deliberately models per-frame transients — null `cullFrustum`, missing lightmap, absent render target — and the seam's world-scale latch cannot see them. Without the watchdog those produce an unbounded run of blank frames that nothing observes.

Also bound `MesheliumTerrainPump.java:47-50`: `MesheliumTerrainGpu.create()` returning null retries forever with `broken` false and `lastError` null, so `drawerHealthy()` reports healthy. Give it a retry budget that latches.

### 4.4 Forcing the heaps to drain — the part that makes the saving real

Per §1, arming alone frees nothing. Two options, and I pick the first:

**Arm only when the heaps are already empty or emptying.** `heapsAreDrainable()` is true only in the window following a full invalidate — i.e. the seam arms *on* the extended-rd raise's own `allChanged` (a render-distance change is itself an invalidate: `LevelExtractor.extract` turns `getEffectiveRenderDistance() != lastViewDistance` into `allChanged()`), or at world standup. `ViewArea.releaseAllBuffers` has just freed every sub-allocation, every `TlsfAllocator` is completely free, and the tail loop drains ~52 heaps at one per `uploadTerrainBuffersToGpu` call ≈ one per frame ≈ ~1 s.

This deliberately **replaces Design 1's 120-frame warm-up**, which is incompatible with it: a 60-or-120-frame delay guarantees vanilla commits fresh near-field heaps (staging permits ~49 MiB/frame) that are then pinned indefinitely under a stationary camera, producing a permanent path-dependent partial vanilla copy — the exact failure Design 3 rejects its own partial options for. Arming on the invalidate frame also removes the arm-edge poisoning hazard (`armed` flipping between ip 86 and ip 131 of one section, publishing a half-staged mesh).

If arming at standup proves impractical, the fallback is to force one `allChanged()` at the arm transition — but on the render thread **outside** `copyLock`, per §5.

**Also strand-bounded, and it must be measured not asserted:** whatever vanilla committed before the arm is a floor of 3 layers × (≥1 × 128 MiB vertex + ≥1 × 32 MiB index) = **≥480 MiB stranded**, freed only when an allocator is completely free. Do not repeat the designs' "a few hundred MiB" as a ceiling; the census in §6 measures it directly.

### 4.5 Comments and contracts the seam falsifies — same commit

- `ChunkSectionsToRenderMixin.java:54-57`: *"the drawer returning false ... leaves vanilla uncancelled — terrain keeps rendering from vanilla's still-live dual-pipeline buffers."* False the moment the seam arms.
- `TerrainDrawer` coverage-guard log: *"vanilla draws everything until a world load with clean counters."* False.
- `LevelRendererMixin.java:207-209`: still says `skipVanillaPrep` is *"DEFAULT OFF"* while `MesheliumConfig.skipVanillaPrepEnabled()` returns `true` when the property is absent (`:635-637`). Stale today, dangerous under the seam.
- `docs/VANILLA-FRAME-PATH.md` §2.1: each `UberGpuBuffer` is not one buffer but a growable `List<Pair<TlsfAllocator, UberGpuBufferHeap>>` of fixed-size heaps. That list is where the gigabytes live.
- `TerrainDrawer.java:1464-1479` — the two "vanilla has nothing to draw either, so owning nothing costs nothing" rules — become false. If any section has been suppressed this world, an empty or absent snapshot must **refuse** the frame and request the rebuild.

---

## 5. THE RECOVERY PATH

**This section decides whether it ships.** The governing change is not a mechanism, it is an ownership rule.

### 5.1 The rule

> **While `suppressedThisWorld` is true, Meshelium keeps owning the terrain frame from the moment of demotion until vanilla is plausibly back. The coverage guard and the terrain-off path do not hand the frame to vanilla during that window.**

The coverage guard was invented to avoid showing a holey world. Its premise — that vanilla is whole — is exactly what the seam destroys. A holey Meshelium picture is strictly better than an empty vanilla one. Concretely: gate `coverageGuardBlocks()` and the `terrainRenderingEnabled` early-return on `VanillaUploadSeam.vanillaHasGeometry()`, false until the rebuild has run K frames with `LevelRenderer.hasRenderedAllSections()` true for M consecutive frames. `hasRenderedAllSections()` is public but is only `dispatcher.isQueueEmpty()` — it is true early and it flickers, so it is one weak term plus a frame floor, never the sole signal.

**If the owner rejects this rule, the seam does not ship.** Retention alone cannot substitute: `TerrainResidency.java:964` gating on `retainTerrain` (default `false`, `MesheliumConfig.java:362`) keeps Meshelium's *geometry* but does not make Meshelium *draw*, and it pins ~4 GiB during the exact event that says VRAM is exhausted.

### 5.2 Recovery primitive: dirty, not sledgehammer

Verified, and it changes the choice:

- `LevelExtractor.setSectionRangeDirty(int,int,int,int,int,int)` is **public**, a triple loop into `SectionUpdateTracker.setDirty`, null-safe out of grid. Non-destructive: the ViewArea survives, no allocation is freed, the occlusion graph is untouched, and **Meshelium's own copies are not released**.
- Under the late seam a suppressed section still holds a real `CompiledSectionMesh` with a real `visibilitySet` — the ctor copies `visibilitySet`, `blockEntities` and `transparencyState` straight off `Results`, and only the `draws` map comes from `renderedLayers`, which the seam never touches. So the occlusion graph never collapses. **Design 2 claimed this property as its exclusive structural advantage; it is equally true of the late seam.**
- **The limit, verified today:** `LevelExtractor.extract` iterates `levelRenderer.visibleSections()` at ip 445 and reads `getDirtyState / isDirty / hasAllNeighbors / setNotDirty` at ip 476-581 *inside that loop*. Dirty state is consumed **only over the frustum-visible set**. The bit is sticky for everything else, so the refill is per-viewing-direction and a completion detector can never honestly reach zero. Use a deadline as the primary exit, not the fallback.

That limit is tolerable precisely because of the §5.1 rule: the player is looking at Meshelium's picture, not vanilla's partial one.

- `allChanged()` stays as the fallback for the Class-B case, and it arrives on its own via the clamp. **Never call it from inside `copyLock`.** Verified cost: `allChanged` ip 31-47 constructs `new SectionUpdateTracker(level, getEffectiveRenderDistance())`, whose `RotatingSectionStorage` eagerly fills the grid — at rd 120 that is 241 × 241 × 24 = **1,393,944 slots** allocated on the render thread. The pump hook sits inside `LevelRenderer.render`'s lock/unlock window, so doing it there blocks every build worker in `addSectionBuffersToUberBuffer` (which takes `copyLock` at ip 3-10) for the duration. Set a `pendingRebuild` flag; consume it on the client tick, outside the lock, with its own try/catch, and clear the flag **after** the call returns normally so a throw retries rather than losing the recovery forever.

### 5.3 Class A — Meshelium can still draw (coverage guard trip, terrain toggle, arena pressure, extended-rd step-down)

| t | What happens | What the player sees |
|---|---|---|
| +0 ms | `MesheliumPassive.latch()` on whatever thread noticed. `armed = false` (volatile) — next `addSectionBuffersToUberBuffer` on any worker stages normally. `vanillaHasGeometry() = false` pins ownership to Meshelium. | Nothing. |
| ≤ 50 ms | Client tick: `setSectionRangeDirty` over the loaded range; recovery-retention forced on so `releaseAllBuffers`-style frees retain instead of dropping Meshelium's copies. | Nothing. |
| next frames | Vanilla recompiles the frustum-visible set, nearest-first. Sections outside the frustum hold a sticky dirty bit. | **Meshelium's picture, unchanged.** |
| ≤ 1 tick | If the trigger routes through `drawerHealthy()`, `clampBack` writes rd 32 + save, which is itself an `allChanged`. Rebuild target ≈ 65² columns, not 241². | A render-distance change, which the player already knows. |
| K frames later | `hasRenderedAllSections()` true for M frames AND a frame floor elapsed → `vanillaHasGeometry() = true`, handover. | Handover, invisible. |

**Marginal visible cost over today: zero, if §5.1 lands.** Without §5.1 it is an immediate blank screen on the *most common* trigger, which is why §5.1 is the ship gate.

### 5.4 Class B — Meshelium cannot draw (a throw latching mid-frame, `GpuDeviceLossException`)

Nothing can be held. The player sees the world rebuild from empty. **This is irreducible and it is the price.** Three things bound it, and one commonly-cited fourth does not:

1. Arming only at standup / on an invalidate means the drawer's exotic standup paths have already run (the bench recorded 10 `prepSkipHoleFrames` exactly at world standup, `TerrainDrawer.java:1464-1475`).
2. The clamp fires on the same tick *once `drawerHealthy()` is taught the three missing latches*, so the rebuild target is rd 32, not rd 120.
3. On the most likely Class-B trigger — device loss — vanilla's next GPU call hits the same loss, so no design could have helped.
4. **The "near-field safety bubble" does not exist. Delete it from the argument.** `checkSectionMesh` ip 78-86 does `setSectionMesh(new)` then `releaseSectionMesh(old)`, and `releaseSectionMesh` calls `removeAllocation` on all six buffers. So the first recompile of any near-field section while armed — a block placed, a light update, a neighbour rebuild — frees that section's real vanilla allocation and installs nothing. The bubble erodes while standing still.

**Duration is UNMEASURED and it is the load-bearing unknown.** See §6.

### 5.5 Paths that need explicit handling

- **Resource reload.** `Minecraft.reloadResourcePacks → LevelExtractor.allChanged → LevelRenderer.invalidateCompiledGeometry` does **not** call `dispatcher.dispose()`, so any dispose-keyed per-world reset never runs and the seam stays armed across the reload while `releaseAllBuffers` frees Meshelium's copies too. Register the resource-reload listener the mod does not currently have (grep confirms none in `src/main`), or treat a `RenderSection.reset()` storm at `RenderSectionMixin.java:68-96` as a demote.
- **The benchmark.** `MesheliumBenchmarkTest`'s vanilla leg flips `meshelium.terrainDraw` off live in the same world. Excluding at *arm* time is too late — leg 1 runs armed, so by the time the harness flips, vanilla's buffers are empty and the vanilla leg measures an empty world with a rebuild inside the measured window. Fix at the harness: after flipping, **block** until vanilla's committed heap bytes (via the §6 census) are nonzero *and* `hasRenderedAllSections()` holds, before arming the recorder. Add a hard assertion that committed terrain VRAM is nonzero at the start of the vanilla leg. Without this every A/B number in the release is fiction, and nothing existing would catch it — `prepSkipHoleFrames` only counts Meshelium-owned frames.
- **`PROPERTY_BENCH_NO_CLAMP`** (`MesheliumExtendedRd.java:508-521`) deliberately disables the clamp so the bench can measure vanilla above 32. Refuse to arm whenever it is set.

---

## 6. WHAT MUST BE VERIFIED BEFORE SHIPPING

### The cheapest experiment that settles the premise — run this first, alone

**Three `@Accessor` interfaces and one log line. ~15 lines. Read-only. Zero GPU risk. No new behaviour.**

```java
@Accessor("chunkUberBuffers")  Map<ChunkSectionLayer, ?> getChunkUberBuffers();  // on SectionRenderDispatcher
@Accessor("nodes")             List<?> getNodes();                              // on UberGpuBuffer
@Accessor("heapSize")          int getHeapSize();                               // on UberGpuBuffer
```

All three targets verified private and present: `private final Map<ChunkSectionLayer, SectionUberBuffers> chunkUberBuffers`; `private final List<Pair<TlsfAllocator, UberGpuBufferHeap>> nodes`; `private final int heapSize`. `SectionUberBuffers` exposes `vertexBuffer` / `indexBuffer`. Sum `nodes.size() * heapSize` **per layer, per buffer** and print it beside the existing residency line (`TerrainResidency.java:1617-1647`).

That number **is** vanilla's committed terrain VRAM, including its fragmentation and its per-layer split. It settles in one session what nine reviewers could only bracket. Per the project's cheap-experiment rule, it is the go/no-go — and per the same-session rule, none of these numbers may come from an earlier run.

**Stop condition: if the census does not show ≥ 4 GiB in `chunkUberBuffers` at rd 120, do not build the seam. Go hunting in the 2–3 GiB residual instead.**

### What the harness can check (no owner, no rd 120)

- The seam handler's flag order and conditionals, asserted directly against the bytecode contract: `vertexData != null ⇒ vertex flag`, index flag unconditional, `checkSectionMesh` exactly once and last.
- **The test that would have caught Design 3's fatal:** with the seam armed, in a scene with *no water* (underground or superflat — a lakeside shot passes while the world is broken), teleport/walk and assert (a) `LevelRenderer.visibleSections().size()` keeps growing and (b) no `RenderSection` remains at `CompiledSectionMesh.UNCOMPILED` after its compile task completes.
- Cancellation safety: assert the section stays on `doTask`'s non-empty path, i.e. `renderedLayers` is never emptied by us.
- Demote ordering: `armed == false` after `demote()` even when `demotedThisWorld` was already set.
- Per-world reset survives a thrown `disposeAndReset()` and a latched dispose hook.
- Bench guard: vanilla's committed heap bytes nonzero at the start of the vanilla leg.
- The three prerequisite latches actually reach `wouldOwnFrame()` and `drawerHealthy()`.
- Per-layer quad counter added to the residency line (today `TerrainResidency.java:1617-1637` prints only a total), retiring the translucent-fraction guess permanently.

### What needs the owner at rd 120 (same session, all of it)

1. **The heap census**, stepping the slider 32 → 64 → 96 → 120 → 96 → 64 → 32, capturing the residency line at each step. The way *down* is the cleanest leg: the arena never shrinks (`TerrainArena.memoryBytes` only increases; `gpu.destroy()` is reached only from `MesheliumTerrainPump.onDispatcherDispose`), so the entire fall is attributable to vanilla.
2. **Time F3+A at rd 120 with a stopwatch.** `KeyboardHandler → minecraft.levelExtractor.allChanged()` is the identical operation to the Class-B recovery, and strictly worse than the Class-A `setSectionRangeDirty` path. **That number is the empty-world duration the player pays on a hard drawer throw. If it is tens of seconds, the seam should not ship at any saving.** This is the product decision, and it is the owner's, not mine.
3. **Confirm whether the 13.9 GB and 6.9 GB readings came from one continuous session.** Now low-stakes — derivation 2 is struck either way — but it should be recorded rather than left open.

---

## 7. SEQUENCING AND OPEN QUESTIONS

### Sequencing — four commits, and the seam is last

**Commit 1 — Measure.** The three `@Accessor` interfaces, the per-layer committed-VRAM line, and a per-layer quad counter on the residency line. Read-only. Ship it regardless of what follows; it is permanent observability the mod has never had. Then the owner runs one session and the F3+A stopwatch. **Gate: ≥ 4 GiB in `chunkUberBuffers` at rd 120.**

**Commit 2 — Prerequisites, shippable on their own merit.** `MesheliumPassive` as the single funnel. `ChunkSectionsToRenderMixin` reports `drawHookBroken` to `TerrainDrawer` before returning; `wouldOwnFrame()` consults it. `MesheliumTerrainPump.broken` latches the drawer passive. `drawerHealthy()` learns the three missing latches. `MesheliumTerrainGpu.create()` retry budget. Fix the four stale comments in §4.5. **All of these are live bugs today** — under the already-default prep skip, a dead kill-switch hook is a permanent, uncounted empty world *right now*, with no seam involved. Land them separately so the seam is not carrying their risk.

**Commit 3 — The ownership rework.** `vanillaHasGeometry()`, the handover gate on `coverageGuardBlocks()` and the terrain-off path, the recovery-retention term at `TerrainResidency.java:964`, the `LevelExtractor` plumbing (`setSectionRangeDirty` + deferred `allChanged`, both outside `copyLock`), the watchdog, the bench block-until-vanilla-is-back. This commit is inert without the seam and can be soaked on its own.

**Commit 4 — The seam.** Merged handler, `@Invoker`, `VanillaUploadSeam` on the client tick, `meshelium.tune.suppressVanillaUploads` **default OFF for one release**. Turn it on only after a session where `prepSkipHoleFrames` is 0, a deliberate coverage-guard trip recovers visibly with Meshelium still drawing, and a deliberately induced drawer throw is timed end to end so the empty-world duration is a number the owner has *seen*.

### Open questions

1. **UNVERIFIED — recovery duration at rd 120.** The load-bearing unknown. Free to measure (F3+A), not yet measured. Nothing ships on an estimate here.
2. **UNVERIFIED — the ~2–3 GiB residual.** Neither Meshelium's nor vanilla's terrain. Nobody has measured atlases, render targets at the owner's resolution, or the swapchain. Schedule it; do not let it hide inside this project's success.
3. **UNVERIFIED — Mixin's handling of a cancelling HEAD callback's effect on sibling HEAD callbacks.** No mixin jar on disk, gradle forbidden. Merging the handlers makes it moot, which is why merging is mandatory rather than stylistic.
4. **UNVERIFIED — `@Invoker` on a private instance method in this toolchain.** Standard Mixin, but this repo has zero precedent (0 `@Shadow`, 0 `@Invoker`, 7 `@Accessor`). First compile of commit 4 is the check.
5. **UNVERIFIED — the translucent quad fraction.** Retired by commit 1's per-layer counter. Until then, "TRANSLUCENT is ~0.5 GiB" is an assumption; an ocean-heavy world could be 3× that.
6. **UNVERIFIED — the arming-window strand.** Asserted as "a few hundred MiB" by two designs with no calculation. Floor is ≥ 480 MiB. Commit 1's census measures it directly; if it exceeds ~500 MiB, arming must move strictly onto the invalidate frame.
7. **Open policy question for the owner.** The seam makes this release's headline fix — *"passive degrades to slow, not to empty"* — conditional. With §5.1 it holds for every predictable passive path. It does not hold for a mid-session drawer throw or a device loss. That is a product decision, and it should be made with the F3+A number in hand.
8. **Scope caveat to record now, not discover later.** The seam can never arm on a server with view distance ≤ 32. The ~6.5 GiB is a singleplayer / Bobby / permissive-server figure.
---

## 8. POST-SHIP: THE BLANK GROUND, AND THE TOGGLE THAT DID NOTHING

Two defects the owner found in the first shipped build (2026-08-13). Both
came from the same place: the setting was treated as a boot-time constant.

### 8a. The handover fired before the rebuild (the blank ground)

Section 5 specifies the handover as "vanilla looks complete for N frames".
The signal used for "looks complete" is
`LevelRenderer.hasRenderedAllSections()`, which is only
`dispatcher.isQueueEmpty()`. **A settled world's queue is already empty**,
so that predicate read true from the very first frame after demotion, the
20-frame floor elapsed while nothing whatsoever was happening, and
Meshelium released the frame to buffers that had never been refilled. The
owner turned the setting off, turned Meshelium off, and got clear ground.

The frame floor was the wrong shape of defence. An empty queue only means
FINISHED after it has meant WORKING, so the state machine now requires
three things in order: the rebuild is issued, the queue is observed BUSY at
least once, and only then does the completion counter start. Regression
covered by `MesheliumLifecycleTortureTest.assertSeamHandover`, which drives
the machine with no GPU because none of this needed one.

Also: while `armed`, progress is ignored outright. A rebuild running under
an armed seam is being cancelled section by section, so its completion
means the heaps have DRAINED, never that vanilla has geometry.

### 8b. Both directions now apply mid-world

Section 4 argued the setting could only take effect at world standup,
because vanilla frees a heap only when its allocator is completely free and
a settled world has live allocations pinning every heap. That argument is
correct about heaps and wrong about the remedy. The recovery path in
section 5 already had to build the missing piece:
`LevelExtractor.allChanged()` drops every section, which releases every
allocation, which empties every heap. **The call that puts vanilla back is
the call that clears it out** - the same mechanism, run in the other
direction.

So `onSettingChanged()`:

- **on**, mid-world: arm, then request the rebuild. Vanilla's heaps drain
  over the next few frames and the seam stops them refilling. Refused when
  terrain rendering is off, because suppressing vanilla with Meshelium also
  off leaves nobody drawing; it arms when the master switch returns.
- **off**: `demote()`, which is what the row should always have done. The
  original wrote the config file and nothing else, so the seam stayed armed
  and kept suppressing on behalf of a setting the player had switched off.
  That is what stacked with 8a to produce the blank ground.

Tooltip moved from "applies the next time a world loads" to warning that
the terrain reloads and the memory takes a few seconds to return.

### 8c. Still open: the arena never shrinks

Unrelated to the seam, reported in the same session. Render distance down
or a teleport away frees nothing, and the peak is sticky for the session.
See `MULTIBUFFER-VRAM-PLAN.md` phase 4; note that `TerrainArena` allocates
from a ROTATING CURSOR (`TerrainArena.java`, the `allocCursor` scan), which
is right for burst locality and actively wrong for ever emptying a block.
Any shrink work starts there, not with compaction.

---

## 9. THE OWNERSHIP RULE WAS NEVER WIRED UP (2026-08-13, second pass)

Sections 4, 5 and 8 all reason about an ownership rule living in
`TerrainDrawer.enabled()`. A four-way audit of the swap paths found that
**`TerrainDrawer.enabled()` had zero callers anywhere in the repository.**
It was dead code from the day it was written. Both real consumers of the
master switch, the draw-cancel hook in `ChunkSectionsToRenderMixin` and the
frame-state capture in `LevelRendererMixin`, read
`MesheliumConfig.terrainRenderingEnabled()` straight out of the config and
never referenced the drawer.

So the guarantee this document has asserted since section 5 has never once
executed. Switching Meshelium off with the seam armed stopped Meshelium
drawing that same frame, never reached `demote()`, and left vanilla's
uploads cancelled forever. Nobody drew, permanently. Every fix in section 8
was correct and irrelevant, because the handover it repaired was never
started.

### 9a. What replaced it

The owner chose the other resolution, and it is the better one: **the swap
is SEQUENCED, not overlapped.** Dump one renderer, then load the other, so
the two copies are never resident together and an 8 GB card is never asked
to hold both. Meshelium stopping the instant the switch flips is therefore
correct behaviour, and the visible rebuild is accepted.

`TerrainDrawer.enabled()` is deleted rather than wired up. Demotion runs
from `MesheliumExtendedRd.driveVanillaUploadSeamRecovery` instead, on the
client tick, which executes unconditionally and also catches the harness
flipping the property.

### 9b. The doubling was a steady state, not a swap transient

The audit's other finding. `TerrainResidency` never read the master switch
at all, and neither did the section build tap or the residency pump. With
Meshelium switched off it kept encoding and uploading, holding a complete
arena copy of a world it was not drawing, for the whole session. The
"memory freed on switch-off" the owner observed was not Meshelium releasing
anything: it was the wave-10 clamp back to render distance 32, which makes
vanilla issue its OWN `allChanged()`, whose release storm empties both
sides. Switching back on has no matching clamp, hence no invalidation, hence
nothing freed. That asymmetry was the whole of defect (A).

Now: the build tap and the pump are gated, and the pump hands the arena back
to the driver once the switch is off.

### 9c. Property versus config, a distinction that is load-bearing

The heavyweight swap keys on `MesheliumConfig.terrainRenderingConfigured()`
(the config field alone), never on `terrainRenderingEnabled()` (property ??
config). `meshelium.terrainDraw` is flipped between frames by the benchmark
and by `MesheliumTerrainDrawTest` to photograph one scene both ways; making
each flip reload the world broke that test immediately. The property stays a
cheap draw-level toggle. For a player, with no property set, the two values
are identical.

### 9d. Two release rules that look wrong and are right

- **Keep pumping while draining.** The first version returned early from the
  pump when disabled, then waited for pending frees to retire. Those frees
  retire *through the pump*, so the wait could never end.
- **Release on a deadline, not on a perfect condition.** Waiting for an
  empty store is not reachable: a handful of sections survive the release
  storm every run. Holding them is pointless, since nothing Meshelium holds
  can be drawn while it is off and every route back on issues its own
  invalidation. Fence safety comes from `gpu.destroy()` deferring through
  vanilla's destroy rotation, not from the counter.

### 9e. Covered by

`MesheliumLifecycleTortureTest.assertRendererSwap` does the full round trip
in a live world with suppression armed, and asserts on
`VanillaTerrainCensus.committedBytes() > 0` after the handover. Screenshots
could not have caught this: the seam was cancelling uploads, so vanilla's
draw calls ran against empty buffers and produced a structurally valid,
completely empty frame. The census asks the only question that matters,
which is whether vanilla has anything to draw at all.
