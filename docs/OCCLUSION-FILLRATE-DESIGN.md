# Occlusion fill rate: the decision, and the plan to bring the feature back

> **Status: STAGE 1a BUILT AND SHIPPED 2026-08-12. The title of this page
> is wrong.** GPU occlusion culling shipped DISABLED and hidden in 1.0.0
> because the two box rasters cost about thirty times what the drawing they
> save costs. This page set out to decide how it comes back and assumed the
> cost was fill rate. It was not. It was one same-address atomic per
> covered pixel, and a three-line read guard removed about 97 percent of
> both passes: `ground-rd32` went from 3.484 ms to 0.644 ms, a 5.4x cheaper
> occlusion path.
>
> **The feature still does not get turned back on, and the first draft of
> this banner was wrong about why.** It said 0.644 ms was "well under the
> 1.089 ms the feature has to beat". That 1.089 ms bfsOnly figure was
> cross-session and does not reproduce: same-session it is 0.670 ms. So the
> real bar was always much tighter, and against it occlusion is 11 to 15
> percent SLOWER at `plains-rd32`, a wash at `ground-rd32`, and 15 percent
> faster at `plains-rd64`. Cheap passes are not a profitable feature. The
> conclusion is Auto keyed on scene weight, not a flipped default.
>
> Everything below is preserved as written, including the parts the
> measurement refuted, because the reasoning is why the refutation was
> cheap. Read [stage
> 1a](#stage-1-the-two-free-wins-about-seven-lines-behaviour-identical)
> first, then the addendum immediately below.

## The recommendation

> **ADDENDUM 2026-08-12, after stage 1a: candidate A is probably no longer
> worth building.** The recommendation below is sound reasoning against the
> budget as it was understood at the time, and that budget no longer
> exists. A's case was 0.07 to 0.17 ms modelled against **2.97 ms measured**.
> The read guard already took the real cost to an implied residual of about
> **0.083 ms**, which is *inside A's own modelled band*. A would now be
> several hundred lines of new compute shader, a depth pyramid, and the
> reversed-Z mip reduction that this document's own risk register calls the
> highest-probability terrain-deleting bug on the page, in exchange for
> approximately nothing. The remaining question for 1.2 is not "A or C" but
> "is there anything left worth doing at all", and the honest answer today
> is: measure at 1440p and 4K first, because a residual that small may not
> even scale. Stages 2, 3 and 4 are on hold pending that. Stage 5 (delete
> the region level) is unaffected, since it was always a simplification
> rather than a speedup.

**Build a hierarchical-Z depth pyramid and test boxes in a compute shader
(candidate A).** It is the only candidate that clears the budget, and it
clears it by an order of magnitude rather than a margin: it replaces
roughly ten million per-fragment depth tests per frame with roughly ten
thousand per-box texel fetches, so its cost stops scaling with how much
screen a near box covers, which is the entire disease. Modelled at 0.07 to
0.17 ms against a measured 2.97 ms today, in a scene where the maximum
conceivable prize is 0.61 ms. It also needs no Vulkan extension, needs no
new mixin, needs no copy of the depth buffer, and it *deletes* two render
passes instead of adding one. Three facts that used to be its main risk
are now settled from bytecode: vanilla's depth image already carries
`VK_IMAGE_USAGE_SAMPLED_BIT`, it lives permanently in
`VK_IMAGE_LAYOUT_GENERAL`, and vanilla's own pass-end barrier already
orders the depth writes against a compute read. See
[Evidence](#evidence-base).

**The runner up is candidate C, reduced-resolution rasterisation of the
existing box passes, and it lost for three reasons.** It is two to three
times more expensive than A (0.25 to 0.35 ms modelled, which is right at
the ground-scene break-even rather than clear of it). It is still
per-pixel work, so it degrades by 2.1x at 1440p and 4.0x at 4K, and 1440p
is exactly where the owner found this bug in real play. And it is the only
candidate with a *coverage* conservatism hole: a section thinner than one
low-resolution pixel produces no fragments at all and is wrongly declared
hidden, which deletes terrain. A has no equivalent hole, because a
screen-space AABB over eight projected corners over-covers the true
silhouette and therefore culls strictly less than today's raster does. C
stays in reserve as the no-compute fallback and nothing more.

**Candidates B and D are rejected outright** and the arithmetic is in
[Why the others lost](#why-the-others-lost). B does not reduce depth
testing at all (the Vulkan spec defines the depth test per sample
regardless of fragment size), needs a second device extension, and caps at
4x on AMD rather than 16x. D pays the entire current fill cost and then
adds thousands of CPU draw calls and a pipeline drain on top.

**One important correction to the brief before any of this is trusted —
and this paragraph turned out to be the most valuable one on the page.**
The brief states the cause is fill rate. That is the likely cause but it
has not been isolated, and there is a competing explanation that costs one
line to test and would change what stage 1 should contain. See
[The diagnosis is not finished](#the-diagnosis-is-not-finished-yet).
**The competing explanation was right.** Everything above this line
reasons correctly from a premise that a one-line experiment falsified in
under three minutes. Run the cheap disambiguating measurement before
building the expensive thing it justifies.

## The bar, in measured numbers

Occlusion is `false` by default (`MesheliumConfig.java:157`) and has no row
on the settings screen (`MesheliumOptionsScreen.java:359`, the comment
block that explains the omission). The bar it has to clear to get both
back is not a feeling, it is these three rows.

All 1920x1080, RX 9070 XT, driver 1.4.349 (Adrenalin 26.7.1, LLPC), seed
4242, 120 warmup and 600 measured frames, whole-frame CPU means. "bfsOnly"
is Meshelium with occlusion off, which is the thing occlusion has to beat.

| scene | camera | occlusion ON | **bfsOnly (the bar)** | vanilla | resident | drawn |
|---|---|---|---|---|---|---|
| `ground-rd32` | y 74, pitch 2 | 3.553 ms | **1.089 ms** | 1.87 to 1.91 ms | 2,257 | 383 |
| `plains-rd32` | y 130, pitch 25 | 3.158 ms | **1.435 ms** | 2.76 to 2.87 ms | 3,271 | 1,631 |
| `plains-rd64` | y 130, pitch 25 | 3.805 ms | **2.423 ms** | 10.0 to 10.8 ms | 9,413 | 3,279 |

Two things to read off that table before anything else:

1. **Occlusion ON is slower than vanilla in the ground scene** (3.553
   against 1.87), while bfsOnly is nearly twice as fast as vanilla. The
   feature is not underperforming, it is actively harmful, which is why
   flipping the default was a correctness-of-defaults fix and not a tuning
   preference.
2. **bfsOnly at rd 64 is 2.423 ms against vanilla's 10.0 ms.** The mod's
   headline does not depend on occlusion at all. This feature is a bonus,
   and it must be held to a bonus's standard: if it cannot clearly win, it
   stays off.

### What the budget actually is

Marginal cost of drawing one more section, from the only archived runs
with per-pass GPU timestamps (`misc/bench-results/meshelium-2026-08-10/`,
`opaqueA + phaseB` at rd 32 is 0.444 ms for 1,565 drawn sections) and
cross-checked against the brief's ground-scene figure (0.10 ms for 383):
**0.27 microseconds per section**. Occlusion pays if and only if

```
pass cost  <  sections removed  x  0.27 us
```

| scene | sections removed | draw saving | current pass cost (implied) | reduction needed to break even | to win by 2x |
|---|---|---|---|---|---|
| ground rd 32 | 1,874 | 0.506 ms | 2.97 ms | **5.9x** | **11.7x** |
| plains rd 32 | 1,640 | 0.443 ms | 2.17 ms | 4.9x | 9.8x |
| plains rd 64 | 6,134 | 1.656 ms | 3.04 ms | 1.8x | 3.7x |

"Current pass cost (implied)" is `(ON minus bfsOnly) + draw saving`, which
recovers the gross cost of the passes from the A/B legs. In the ground
scene that gives 2.97 ms, sitting right next to the brief's GPU-timestamp
sum of 1.99 + 1.10 = 3.09 ms. Two independent routes to the same figure,
so the brief's number is sound.

**The sentence that should govern the whole plan: in the ground scene the
passes cost 2.97 ms and the absolute maximum prize, culling every single
resident section, is 0.61 ms.** There is no tuning of the current design
that reaches profit there. It has to get about six times cheaper to stop
losing and about twelve times cheaper to be worth a player's frame.

## The diagnosis is not finished yet

The brief attributes the cost to fill rate. The geometry supports that:
summing screen coverage over 43 region boxes on a 128-block lattice, with
`cullMode = VK_CULL_MODE_NONE` (`TerrainOcclusion.java:710`) giving every
covered pixel exactly two fragments (one entry wall, one exit wall, exact
for a closed box), gives roughly 8 to 11 million fragments for the region
pass alone.

But 1.99 ms for 8 to 11 million fragments is 4 to 5.5 Gfrag/s, and in the
*same frame on the same GPU* the terrain pass shades at least 2 million
fully-textured, lightmapped, fogged fragments in 0.10 ms, which is roughly
20 Gfrag/s. **A depth-test-only, colour-masked pass with no output is
running four to five times slower per fragment than a complete terrain
shade.** Something per fragment in the box pass costs more than a full
terrain shade, and raw fill rate does not explain that.

There is exactly one per-fragment side effect in the pass. The whole body
of `occlusion/box.frag` is:

```glsl
atomicExchange(stampOut[uint(gl_PrimitiveID)], FrameStamp);
```

For the region pass `gl_PrimitiveID` is the dispatch slot, so **every
depth-passing fragment of one box atomically exchanges the same 32-bit
word**. A near box covering most of the screen issues on the order of a
million same-address atomics per frame, and same-address atomics serialise
at the L2. Sky makes it worse: reversed-Z clears depth to 0.0, so every box
fragment above the terrain horizon passes GEQUAL and reaches the atomic.

This is a **hypothesis, not a finding.** A competing explanation I cannot
rule out from bytecode: vanilla leaves every image permanently in
`VK_IMAGE_LAYOUT_GENERAL` (verified), and some drivers disable depth
metadata and hierarchical rejection for GENERAL-layout images, which would
make every box fragment pay a full-rate depth read. **Stage 0 below
discriminates between the two in one shader edit**, and the answer changes
how much stage 1 is worth. It does not change the recommendation: A and C
both reduce fragments and atomics together, so A wins either way.

## The reversed-Z rule, stated so it cannot be misread

**Minecraft 26.2 is reversed-Z. Near is 1.0, far is 0.0, the depth buffer
clears to 0.0, and every Meshelium pipeline compares GREATER_OR_EQUAL**
(`TerrainOcclusion.java:726`). Every depth reduction in this plan is
therefore the mirror image of the textbook formulation, and getting it
backwards is the single most likely way this ships a bug.

### The three rules

1. **Reduce the pyramid with `min`, never `max`.**
   Conservative means "never claim something is hidden when it might be
   visible". The safe representative of a block of depths is the
   **FARTHEST** surface in that block, because anything nearer than the
   farthest thing in the block might still have a gap to see through. In
   reversed-Z, farthest is the **SMALLEST** value. So the reduction
   operator is `min`.

2. **Take the box's depth with `max`.**
   The box is a candidate occludee, and the safe representative of it is
   its **NEAREST** point, because if even its nearest point is behind the
   scene then all of it is. In reversed-Z, nearest is the **LARGEST**
   value. So `zBoxNear = max` over the eight projected corner depths.

3. **The test.** Declare the box hidden if and only if
   `zBoxNear < pyramidMin` over its screen-space footprint.
   *Proof:* for every pixel p in the footprint,
   `boxDepth(p) <= zBoxNear < pyramidMin <= sceneDepth(p)`,
   so every fragment the box would have produced fails GEQUAL and the box
   genuinely contributes nothing. Anything else is a guess.

A one-line mnemonic for the shader header, in capitals, because this is
the line that will be misread at 2am:

```
REVERSED-Z. NEAR=1, FAR=0. REDUCE WITH MIN (FARTHEST). TEST hidden = boxMAX < pyramidMIN.
```

### What a wrong sign looks like on screen

**Terrain vanishes.** Not subtly: hills develop holes you can see the sky
or the void through, distant chunks blink out and pop back as the camera
moves a few blocks, and the horizon shreds while the geometry nearest the
camera looks perfectly fine. The failure is worst at distance and worst
against a sky background, because that is where the reduction has averaged
over the largest depth range. If a tester says "the world has holes in it
that move when I move", the sign is inverted, and the first thing to check
is whether the reduction is `max` instead of `min`.

**The automated detector** is a counter, not a screenshot, because a
screenshot only proves the angle it was taken from. **The version of this
paragraph written first was wrong and is corrected here**: it said
`gpuSectionsDrawn` falling below the bfsOnly count "is an over-cull, full
stop", when drawing fewer sections than bfsOnly is the feature *working* —
which is precisely what `assertHiddenWallOcclusion` asserts. Stage 1a
then showed the count is not reproducible run to run either.

The detector that works is `phaseBQuietStatsFrames`: a section that loses
its stamp drops out of phase A and reappears in phase B the very next
frame, so in a converged static scene phase B must stay silent. See
[the gate](#what-must-be-true-before-the-row-comes-back) condition 2 for
the full statement.

### Four conservatism traps, all of which over-cull

These all fail in the terrain-deleting direction, so all four need a test.

1. **Odd mip dimensions.** 1080 reduces to 540, 270, 135, then 68 with a
   row left over. A plain 2x2 `min` from a 135-row level into a 68-row
   level silently drops row 134. The parent then holds the min of a
   *subset*, which is greater than or equal to the true min, which makes
   `zBoxNear < pyramidMin` fire more often than it should. **Fix:** sample
   3x3 on any odd dimension, or pad to a power of two. This is the most
   likely single bug in the whole plan.
2. **Mip selection off by one.** Choose the level coarse enough that the
   box's screen rect spans at most two texels per axis, sample 2x2, and if
   the rect still does not fit, step one level coarser or fail open. Never
   assume it fits.
3. **Near-plane straddle.** If any corner has `w <= near`, the perspective
   divide is garbage. Bail out to visible. This subsumes the
   camera-inside-box case that `region_raster.mesh:149-156` handles
   specially today.
4. **Footprint clamping.** Clamp the sampled rect to the image bounds
   explicitly rather than relying on a sampler addressing mode, and derive
   the pixel grid from `depthView.texture().getWidth(0)` and
   `getHeight(0)`, not from the window size.

### Keep the existing fail-open discipline

Today the design fails open everywhere: `cullMode = NONE`, overflow regions
drawn maskless, idempotent atomic stores, and a latched error that reverts
to the BFS feed (`TerrainDrawer.java:1456`). **Every new path must default
to "visible" on every uncertain branch**, so that a bug costs frames rather
than pixels. A dropped frame is a bug report; a hole in the world is a
one-star review.

## The plan, in stages that each ship and measure alone

Cheapest and safest first. Each stage is independently shippable, has its
own measurement, and can be abandoned without stranding the next one.

### Answering the brief's question directly

The brief asks whether the near-box exemption plus back-face culling
recovers most of the cost on its own, because that decides what wave 1.1
contains. **The honest answer is no, not in the ground scene.**

The E-stack (stages 1 and 2 below) is modelled at 0.5 to 0.8 ms, which is
a 4x to 6x reduction against a required 5.9x to break even and 11.7x to be
worth shipping. It lands *on* the break-even line in the ground scene at
rd 32, not clear of it. It is genuinely profitable at rd 64 (0.5 to 0.9 ms
against a 1.656 ms prize, roughly a 2x return) and it is nearly free to
build, so it still goes first. But **wave 1.1 cannot be "ship the cheap
wins and flip the default back on".** The cheap wins make occlusion stop
being harmful; candidate A is what makes it worth having. Plan wave 1.1 as
"cheap wins, feature stays off, row stays hidden" and wave 1.2 as "Hi-Z
compute, then re-measure against the bar".

The one thing that could change that answer is stage 0.

---

### Stage 0. Isolate the bottleneck (one shader edit, no ship)

**Why first:** everything downstream is reasoning about a bottleneck nobody
has isolated, and this costs one line and one bench run.

**Do:** delete the `atomicExchange` from `box.frag` entirely and re-run the
ground scene with GPU timers armed. Terrain will be visibly wrong. That is
fine, the timestamp is the answer. Then, separately, re-run the unmodified
passes with the viewport scaled to quarter linear resolution, which
measures candidate C's ceiling for free.

**Read:** if `regionRaster` collapses from 1.99 ms toward 0.1 ms, the
atomic is the bottleneck and stage 1's guard is worth more than stages 2
and 3 combined. If it barely moves, the depth path is the cost, the brief's
fill-rate framing is right, and stage 1's geometry work is what matters.

**Ships:** nothing. Revert both edits.

---

### Stage 1. The two free wins (about seven lines, behaviour-identical)

Both of these are provably behaviour-neutral by construction, which is why
they go together and go first.

**1a. The idempotent atomic guard. SHIPPED 2026-08-12 — and it was not a
"free win", it was the entire cost of the feature.** Add a read before the
write:

```glsl
if (stampOut[uint(gl_PrimitiveID)] != FrameStamp) {
    atomicExchange(stampOut[uint(gl_PrimitiveID)], FrameStamp);
}
```

**Measured, RX 9070 XT, 1920x1080, static camera, occlusion ON both legs,
same session and same build except this shader:**

| scene | unguarded | guarded |
|---|---|---|
| `ground-rd32` | 3.484 ms (287 fps) | **0.644 ms (1,553 fps)** |
| `plains-rd64` | 3.802 ms (263 fps) | **1.730 ms (578 fps)** |

**The bfsOnly bars originally quoted in this table (1.089 ms and 2.423 ms)
were CROSS-SESSION and are withdrawn.** Re-measured same-session,
`ground-rd32` bfsOnly is 0.671 and 0.670 ms on two runs, not 1.089. The
5.4x above is unaffected because both of its legs ran back to back, but
every conclusion that compared against those bars was wrong and is
corrected in [the gate section](#what-must-be-true-before-the-row-comes-back)
and in [`PERFORMANCE.md`](PERFORMANCE.md). Same-session, occlusion is 11 to
15 percent SLOWER than bfsOnly at `plains-rd32`, a wash at `ground-rd32`,
and 15 percent faster at `plains-rd64`.

The ground-scene saving of 2.84 ms lands on the independently derived gross
box-pass cost of 2.97 ms: **the guard removed roughly 97 percent of both box
passes.** Subtracting the known 0.506 ms draw saving leaves an implied
residual pass cost near 0.083 ms, which is *inside* the 0.07 to 0.17 ms band
this document modelled for candidate A, the Hi-Z compute pyramid. See the
recommendation section, which that number changes.

**It DOES need a memory-model argument — the original text here was wrong.**
The claim was that idempotent stores make the read benign without further
justification. Under the Vulkan memory model a non-atomic load racing an
atomic store is a data race under a **must**, and this file's own header
(`box.frag:26-31`) says the atomic exists precisely because Nvidium's
plain-store pair was a race Meshelium "engineered out rather than assumed
benign". The guard buys part of that back and owes the argument:

The word is a **monotone one-way latch within a frame**. Every writer in a
frame writes the identical `FrameStamp` and no writer ever writes anything
else — verified exhaustively, the only three writers of any stamp word in
the tree are `box.frag`, `region_raster.mesh:154` and
`section_raster.mesh:139`, all `atomicExchange(..., FrameStamp)`. So per
address per frame the state space is one-directional, and:

- a stale read fails the guard and performs the redundant exchange of the
  value the word was going to receive anyway (fail-open), and
- a read of `FrameStamp` means a writer already stored it and nothing can
  undo it, so skipping is a no-op.

The only unsafe outcome needs the load to return a value never stored at
that address. For a naturally aligned 32-bit dword in std430 that is a
single memory transaction on every ISA in play. That is a **hardware**
argument, not a spec one: the spec declines to define a racy read's value
at all, and the shader comment says so.

`coherent` and `volatile` are deliberately absent. Neither removes the race
(they govern availability and visibility, not happens-before), a
non-coherent load can only ever be too OLD which is the fail-open
direction, and hitting the per-CU cache rather than the L2 is the entire
win. The only formally race-free construct is `atomicLoad` from
`GL_KHR_memory_scope_semantics`, which needs the `vulkanMemoryModel`
feature vanilla does not enable, and whose `atomicOr(x, 0u)` stand-in is
the per-fragment L2 round trip being deleted.

**Residual risk, UNVERIFIED:** on silicon where one CU's L2 atomic does not
refresh another CU's cache line, every fragment pays load *plus* atomic and
the win shrinks toward a small regression. That is NVIDIA and Intel, neither
of which is on this desk. It is a performance risk only, never a correctness
one, and the floor is roughly break-even because a box's first fragment pays
the atomic either way.

**1b. A box containing the camera emits no geometry.** The verdict is
*already decided on the write side*: `region_raster.mesh:149-156` and
`section_raster.mesh:135-141` have invocation 0 force-stamp the box visible
when the inflated box contains the origin. The shader then rasterises all
twelve triangles anyway, and a box containing the camera covers
approximately the entire screen. Hoist that containment test above
`SetMeshOutputsEXT`, and on the inside branch call `SetMeshOutputsEXT(0u,
0u)` and return, exactly as `section_raster.mesh:101-104` already does for
empty slots. The test reads only `lo` and `hi`, which every invocation
computes identically, so the branch is workgroup-uniform and EXT uniformity
is preserved.

**Behaviour change: none.** The stamp is written either way.

**Modelled saving:** 1b removes up to two full screens, 4.15 million
fragments, call it 0.9 ms. Caveat on that figure: exactly one *region* box
contains the camera, but whether any *section* box does depends on whether
the camera sits inside some section's tight geometry AABB, which at y 74 in
a forest is likely but not certain. Treat 0.9 ms as the optimistic end and
0.45 ms as the pessimistic end. 1a's saving is whatever stage 0 says it is.

**Verify — and the criterion written here originally was the WRONG one.**
It demanded `gpuSectionsDrawn` be "exactly 383 and 1,631 respectively, byte
for byte unchanged". In the event 1a moved it to 389, then 376, then 377 on
three runs of the *same* shader, and the unguarded control moved it to 372.
The count is not reproducible run to run, because it tracks
`sectionsResident`, which is still climbing during the measured window and
climbs FASTER on the guarded build (it renders 5.7x more frames in the same
wall time, and upload is throttled per frame). Across five runs:

| shader | sectionsResident | gpuSectionsDrawn |
|---|---|---|
| unguarded | 2,160 | 372 |
| guarded | 2,236 | 376 |
| unguarded | 2,257 | 383 |
| guarded | 2,260 | 377 |
| guarded | 2,261 | 389 |

Sorted by residency the two shaders interleave, so the shader is not the
ordering variable. Worse, the criterion was ambiguous in the one direction
that mattered: **a rising drawn count is also the signature of a dropped
stamp.** Drawn = phase A + phase B; a section that loses its stamp falls out
of phase A next frame, reappears in phase B, and by not priming the depth
buffer lets things behind it pass too, so the total goes UP while terrain
flickers.

**The criterion that actually works is the phase split.** In a converged
static scene phase B is 0 and the total is all phase A; a dropped stamp
produces a phase-B stream every frame. `MesheliumBenchmarkTest.counters()`
now exports `gpuPhaseASections`, `gpuPhaseBSections`, and
`phaseBQuietStatsFrames` (stats frames since phase B last drew anything) for
exactly this. Measured `ground-rd32`, 1920x1080, static:

| shader | drawn | phase A | phase B | phase-B quiet for |
|---|---|---|---|---|
| unguarded | 372 | 372 | 0 | 739 frames |
| guarded | 377 | 377 | 0 | 247 frames |

Phase B is silent on both, so no stamp is being lost. Also re-run pixel
parity shots 40/41 and 50/51 and the hidden-wall assertion in
`MesheliumTerrainDrawTest`.

**A static screenshot cannot prove this on its own.** If the race ever did
bite, the failure is one frame of a missing section that self-heals next
frame (the box rasters again against a less complete phase-A depth and gets
stamped), so it is a flicker, and the bench camera is static by
construction. The phase-B quiet counter is the detector that survives that.

**Ships:** yes, feature still off by default. Flipping the default and
restoring the settings row is a SEPARATE change with its own gate below.

---

### Stage 2. Emit three faces, and do not touch the cull mode

**Do not enable `VK_CULL_MODE_BACK_BIT`.** The twelve-triangle table in
`region_raster.mesh:114-121` was re-derived from Nvidium's PILUT tables and
the pipeline sets `frontFace(VK_FRONT_FACE_CLOCKWISE)`
(`TerrainOcclusion.java:711`), but the winding's actual handedness under
26.2's projection and viewport convention is **UNVERIFIED**. If it is
inverted, back-face culling culls the *front* faces, boxes only stamp where
their rear wall passes depth, and terrain over-culls massively. That is a
coin flip on precisely the failure mode this document is most worried
about, taken for no benefit.

**Instead, emit only the faces the camera is on the near side of.** In
camera-relative coordinates, per axis:

- if `lo > 0`, the camera is on the low side: emit the `lo` face;
- else if `hi < 0`, the camera is on the high side: emit the `hi` face;
- else the camera is inside that slab: **emit no face on that axis**, since
  both of its faces are back-facing and neither is on the silhouette.

For a convex box the resulting 0 to 3 faces tile the silhouette exactly,
with no gap and no overlap, so **every covered pixel receives exactly one
fragment instead of two: an exact 2x, with no dependence on winding at
all.** The face count depends only on `lo` and `hi`, so it is
workgroup-uniform; declare `SetMeshOutputsEXT(8u, 2u * faceCount)` and keep
the existing per-invocation vertex write (unreferenced vertices are simply
unused). The all-three-axes-straddled case is the camera-inside case, which
stage 1b has already returned from, so the two rules compose into one.

Primitive count halves as well as fragment count, which also halves the
per-primitive `gl_PrimitiveID` attribute work.

**Modelled saving:** exactly 2x on every remaining box.

**Verify:** same two scenes and resolution. `gpuSectionsDrawn` must again be
**exactly 383 and 1,631**: three-face emission changes coverage not at all,
so any change in the drawn count is a bug in the face selection, and a
*drop* is the terrain-deleting direction. This is the stage where the
over-cull assertion earns its keep.

**Ships:** yes, feature still off.

---

### Stage 3. Near-box distance exemption

Extend the stage-1b predicate from "contains the camera" to "within D
blocks of the camera": stamp visible, emit nothing. Compute D GPU-side from
the camera-relative origin already present in the `OccRegion` list, so
nothing changes CPU-side and the `cachedCull` memo stays bit-identical.

**Regions are nearly free to exempt**, and this is a structural point worth
stating plainly: `regionStamps` has exactly one consumer in the entire
codebase, `section_raster.task:68` (verified by grep across `src`).
Exempting a near region therefore does not draw one extra section; every
section inside it is still tested individually. The only cost is running
section boxes for a region that was almost certainly visible anyway.

**Sections cost a little and save a lot.** Sections within 48 blocks number
about 113, of which perhaps half survive the frustum test, so about 50
extra draws at 0.27 us is **13 microseconds**. What it removes: a 16-block
box at 48 blocks covers about 3.2 percent of the screen, which after stage
2 is 66,000 fragments each, so 50 of them is 3.3 million fragments. In a
ground-level forest most of those sections are visible anyway, so the true
added draw count is well below 50.

**Every part of this is fail-open**: it can only mark more things visible,
never fewer.

**Verify:** sweep D at 32, 48 and 64 blocks on `ground-rd32` at 1920x1080.
Counters: `gpuSectionsDrawn` must **rise** (383 plus a few tens) and must
never fall; `regionRaster` and `sectionRaster` must fall; whole-frame mean
is the arbiter. Pick the D that minimises frame mean, not the one that
minimises pass time.

**Ships:** yes. **Expected position after stage 3: 0.5 to 0.8 ms of pass
cost in the ground scene against a 0.506 ms prize. Break-even, not a win.
The default stays off and the row stays hidden.**

---

### Stage 4. Candidate A, the Hi-Z pyramid and compute test

This is the stage that actually wins. It replaces box passes 2 and 3
entirely.

**Shape, all of it verified reachable from bytecode:**

- **Read vanilla's depth directly, no copy.** `MainTarget` creates the
  depth attachment with usage `15`, whose bit 4 (`USAGE_TEXTURE_BINDING`)
  maps to `VK_IMAGE_USAGE_SAMPLED_BIT`. Reach it via
  `RenderTarget.getDepthTextureView()` cast to `VulkanGpuTextureView`, the
  same cast `TerrainDrawer.java:2937-2945` already performs for the atlas.
  Bind as `VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE` with
  `imageLayout = VK_IMAGE_LAYOUT_GENERAL`. No sampler object: declare
  `uniform texture2D` and use `texelFetch` under
  `GL_EXT_samplerless_texture_functions`. Linear filtering of D32_SFLOAT is
  not guaranteed anyway, and a min-reduction does not want it.
- **Put the pyramid in an SSBO, not an image.** This is a deliberate
  choice. `VulkanConst.textureUsageToVk` has no path that ever emits
  `VK_IMAGE_USAGE_STORAGE_BIT`, so a vanilla-created texture can never be a
  compute storage image, and the image route means raw `vmaCreateImage`
  plus one `vkCreateImageView` per mip. A device-local buffer through the
  existing `MesheliumVkBuffers.createDeviceLocal` is 2.8 MB, needs none of
  that, and trades hardware sampling for four scalar loads, which for a min
  reduction is no loss. Keep the image route in mind only if
  `samplerFilterMinmax` hardware reduction is ever wanted.
- **No layout transitions anywhere.** Vanilla transitions every image
  UNDEFINED to GENERAL once at creation and never moves it again.
- **No barrier for the depth read.** `VulkanCommandEncoder.submitRenderPass`
  issues a full `ALL_COMMANDS` / `MEMORY_READ|MEMORY_WRITE` barrier after
  every pass, so phase A's depth writes are already visible to compute.
  Barriers *are* needed between pyramid mip dispatches: either reuse the
  public `VulkanCommandEncoder.memoryBarrier(cb, stack)` hammer, as
  `MesheliumTerrainGpu.java:233` already does, or write a scoped
  `VkImageMemoryBarrier2`.
- **Dispatch outside the render pass.** `vkCmdDispatch` is illegal inside
  one. Use `allocateAndBeginTransientCommandBuffer()` then `execute(cb)`,
  the exact pattern at `MesheliumTerrainGpu.java:229-236`. Submission order
  within the queue submit is preserved.
- **Queue, extension and compiler are all already in place.** Vanilla
  selects a graphics family with `VK_QUEUE_COMPUTE_BIT` set, so there is no
  second queue and no ownership transfer. `VK_KHR_push_descriptor` is in
  vanilla's required set, so `vkCmdPushDescriptorSetKHR` with
  `VK_PIPELINE_BIND_POINT_COMPUTE` needs no descriptor pool.
  `MesheliumShaderCompiler` needs one new constant,
  `KIND_COMPUTE = Shaderc.shaderc_compute_shader`, and nothing else.
- **Work per frame.** Pyramid from 960x540 down to about 30x17 is roughly
  691,000 texels and 11 MB of traffic, modelled at 0.05 to 0.15 ms and
  scene-independent. Test is one thread per box: about 2,050 threads at
  ground rd 32, 9,571 at rd 64, each doing eight corner transforms and four
  texel loads. Under 0.02 ms.

**What it deletes:** two render passes, two attachment load/store pairs,
two inherited `ALL_COMMANDS` barriers, the raster-state juggling, and
optionally the whole region level (see stage 5). Passes go **down**, not
up. This is the upgrade `SPEC.md` wave 6 already scheduled in writing.

**What stays untouched:** ping-pong frame stamps, same-frame temporal
repaint, the task-stage cull, the coverage guard, the `cachedCull` memo,
the translucent gate at `TerrainDrawer.java:2364`, extended render
distance, and the cpuCull and bfsOnly fallbacks. The compute test writes
`curStamps` at the same point in the frame that pass 3 writes it today, so
it is a drop-in. A compute failure latches through the same `catch` at
`TerrainDrawer.java:1456-1478` and reverts to the BFS feed.

**One nice side effect:** with one thread per box there is exactly one
writer per address, so the same-address atomic contention disappears by
construction. Keep `atomicExchange` anyway; it now costs nothing and
preserves the "defined on every conformant device" argument.

**Verify, and this is the stage with the most to verify:**
- A CPU unit test on the reduction operator itself, asserting `min` and
  asserting the odd-dimension row is folded in. This is a pure function and
  there is no excuse for not testing it.
- All three scenes at 1920x1080, plus **one leg at 2560x1440**, because
  resolution is the exact axis that inverted this project's conclusion once
  already and there is no 1440p row in the archive.
- Counters: `gpuSectionsDrawn` must be **greater than or equal to** the
  stage-3 value in every scene (A over-covers the silhouette, so it culls
  slightly less than the raster does; a *lower* count means the reduction
  sign or the mip selection is wrong). `regionRaster` and `sectionRaster`
  disappear and are replaced by a new pyramid-plus-test timer. Whole-frame
  mean must beat the bar in the table above.
- Pixel parity shots 40/41 and 50/51, the hidden-wall gametest, and a
  camera-motion leg, since the static bench camera hides temporal popping
  by construction.

**Ships:** yes, and this is the stage where the default can flip if the
numbers clear the bar.

---

### Stage 5 (optional cleanup). Delete the region level

Once A is in, the two-level hierarchy exists only to save work that has
become free: in compute, testing 2,050 boxes instead of 2,000 costs
nothing. `regionStamps` has exactly one consumer
(`section_raster.task:68`), so dropping the region level deletes a
pipeline, a shader pair, a buffer and a pass. Do it as its own change with
its own measurement, not folded into stage 4.

## Why the others lost

### B. `VK_KHR_fragment_shading_rate`

Availability is genuinely fine: every real desktop GPU with
`VK_EXT_mesh_shader` also has FSR (the only mesh-shader devices lacking it
are llvmpipe, MoltenVK and two anomalous reports), the RX 9070 XT exposes
all three features, and the pipeline plumbing is one `pNext` struct with a
zero-code fallback because omitting the struct reproduces today's pipeline
exactly. None of that saves it.

**It does not reduce the depth work.** The Vulkan spec defines the depth
test per sample: "The depth test compares the depth value in the
depth/stencil attachment **at each sample's** framebuffer coordinates",
and under a coarse rate "each pixel's coverage consists of the coverage
samples with a pixel index matching that pixel, and each sample retains its
unique sample index". A 2x2 coarse fragment still carries four pixels'
worth of samples and still runs four depth tests. FSR removes shader
invocations, nothing else.

So B reduces exactly the thing that the free one-line atomic guard in
stage 1a already reduces, and only that. It also caps at `maxFragmentSize
[2,2]` on both AMD parts checked (RDNA2 and RDNA4), so 4x rather than the
16x the brief assumed, against 4x4 on NVIDIA and Intel. **Paying a second
device extension and a cross-vendor availability matrix to buy what one
line already buys, on the one axis A and C both attack anyway, is not a
trade worth making.** Rejected.

### C. Reduced-resolution rasterisation

Covered in the recommendation. Two additions for whoever picks it up if
stage 4 stalls:

- Its reduced depth buffer must hold the **farthest** depth of each source
  block, which is the **minimum** value in reversed-Z, same rule as A.
- Its coverage hole has a cheap fix: inflate the box in world space by
  `k * distance` in the mesh shader, with `k` the angular size of one
  low-resolution pixel, so an inflated box always covers at least one
  low-resolution pixel. Monotone conservative, a few lines, no extension.
  `VK_EXT_conservative_rasterization` would also work and is a third
  extension, so it should not be used.
- Its genuine advantage over A, worth remembering: the downsample can be a
  plain vertex-plus-fragment pipeline writing `gl_FragDepth`, so C needs no
  compute pipeline at all, and its low-resolution depth attachment can be
  created through vanilla's public `GpuDevice.createTexture` API.

### D. Core occlusion queries

Rejected with arithmetic, on three independent grounds.

1. **The fill cost does not change at all.** The boxes still rasterise
   every fragment; the query counts passing samples instead of a shader
   writing a stamp. D starts at 2.97 ms and adds to it.
2. **It reinstates the per-section CPU draw loop this mod exists to
   remove.** 2,050 draws and 4,100 query commands per frame at rd 32,
   9,600 and 19,200 at rd 64. Vanilla's own `prepareChunkRenders` was
   measured at 3.540 ms for about 9,500 sections and is skipped by default
   for being too expensive.
3. **Same-frame consumption needs a stall.** Phase B reads the verdict in
   the same frame, so getting results into a GPU-readable buffer requires
   `vkCmdCopyQueryPoolResults` with `VK_QUERY_RESULT_WAIT_BIT`, draining
   the pipeline. Without it you are back to next-frame consumption, which
   is the indirect-command shape Meshelium deliberately designed away.

Also worth noting since it is a footgun rather than a reason: vanilla's
`VulkanQueryPool` hardcodes `queryType = 2` (TIMESTAMP) with no parameter,
so D would need its own pool regardless.

## Risk register

**1. The reversed-Z sign, and the odd-dimension mip.** The highest-probability
bug in the plan, and it deletes terrain. Mitigated by the CPU unit test on
the reduction, the over-cull counter assertion at every stage, and the
capitalised shader header. *This is the one to be paranoid about.*

**2. The bottleneck is not what anyone thinks. CLOSED 2026-08-12, and this
risk fired.** The whole document reasoned about a cost nobody had isolated,
and the cost turned out to be the same-address atomic, not the fill rate in
the title. The consequences are exactly the ones predicted here: stages 2
and 3 are worth far less than modelled, stage 1a was worth more than every
other stage combined, and the *recommendation* changed too — see the
addendum there, because A no longer has 2.97 ms to collapse. Keeping this
risk in the register and making stage 0 free and first is the reason the
document survived being wrong.

**3. The depth buffer is not sampleable.** Verified false from bytecode:
usage `15` includes `USAGE_TEXTURE_BINDING`, at both creation sites
(`MainTarget.allocateDepthAttachment` and `RenderTarget.createBuffers`),
and vanilla itself binds a depth view as a sampler through
`PostPass$TargetInput`. **What it would force if it were true, stated
because the plan should be able to say why it is not doing this:** not a
mixin. A `@ModifyConstant` on the `bipush 15` inside two Mojang methods
would reallocate every render target in the game and collide with any other
mod doing the same. The real fallback would be
`vkCmdCopyImage` into a Meshelium-owned SAMPLED image, which is always
available because the same usage word also sets `USAGE_COPY_SRC`, at
16.59 MB of traffic per frame, modelled at 0.04 to 0.05 ms. Cheap against
2.97 ms, but between a third and a half of the entire terrain draw, paid
every frame, for nothing. The device-creation mixin could never have fixed
this, because the usage flags are a hardcoded constant inside image
creation and never reach `VulkanBackend.createDevice`.

**4. Driver behaviour on a sampled read of a GENERAL-layout depth image.**
Whether AMD RDNA4, NVIDIA and Intel keep depth metadata in a
shader-readable compressed state here, and what the read actually costs, is
**UNVERIFIED** driver behaviour. The structurally favourable fact is that
vanilla never transitions the image, so there is no transition-triggered
decompress to pay for. This is also risk 2's competing explanation, so
stage 0 partially probes it. Needs a real measurement.

**5. Every forward number is modelled.** The stage savings come from a cost
model fitted to four 854x480 data points and scaled by a pixel ratio, plus
screen-coverage geometry. The model reproduces the plains rd 32 A/B leg
within 3 percent, over-predicts rd 64 by 37 percent and under-predicts the
ground scene by 54 percent, the last being exactly the "ground camera makes
near boxes bigger" direction. Treat the ground-scene models as a **lower
bound on the saving**, and treat every "expected position after stage N"
line as a hypothesis with a bench run attached.

**6. One GPU.** Every number on this page came from one RX 9070 XT. The
fitted model's fixed term in particular is a near-camera fill term and
could sit anywhere else on different silicon. NVIDIA and Intel claims stay
UNVERIFIED per house rules.

**7. The static bench camera.** All three scenes are fixed spectator
cameras, which is what makes them repeatable and also means the frustum
rebuild almost never fires and temporal popping is invisible. Stage 4 needs
a moving-camera leg specifically because an occlusion bug that only shows
up in motion is exactly the bug a static bench cannot see.

**8. Scope creep in stage 4.** A is the largest single change and it
introduces the first compute pipeline in the codebase. Stage 5 exists so
that "delete the region level" does not get folded into it. Resist.

## What must be true before the row comes back

Occlusion is off by default and has no settings row. Both facts are
deliberate and both are reversed by the same evidence, not separately.

**NEVER GATE AGAINST AN ARCHIVED BASELINE. This gate originally said "all
against the archived bfsOnly legs" and that sentence caused a wrong
conclusion to be written into four files.** The archived `ground-rd32`
bfsOnly bar of 1.089 ms does not reproduce: measured same-session on
2026-08-12 it is 0.671 and 0.670 ms on two runs, 62 percent lower. A leg
that should be identical moved by 62 percent across sessions while moving
by 0.1 percent within one. Every gate comparison must therefore be a PAIR
measured in the same session, occlusion ON and bfsOnly back to back, on the
same world, same seed and same build. The bars below are results, not
targets to be reused later.

**The gate, restated: for each scene, run the ON/BFS pair in one session
and require `ON <= BFS` in every scene, not on average.**

**Measured 2026-08-12, 1920x1080, static, same-session pairs (positive
delta means occlusion is SLOWER, so positive is a FAIL):**

| scene | ON | BFS | delta | resident | verdict |
|---|---|---|---|---|---|
| `ground-rd32` | 0.662 ms | 0.671 ms | -1.3% | 2,271 | no result |
| `ground-rd32` repeat | 0.730 ms | 0.670 ms | +9.0% | 2,267 | no result |
| `plains-rd32` | 0.989 ms | 0.863 ms | +14.6% | 3,291 | **FAIL** |
| `plains-rd32` repeat | 0.959 ms | 0.860 ms | +11.5% | 3,298 | **FAIL** |
| `plains-rd64` | 1.748 ms | 2.053 ms | -14.9% | 9,455 | pass |
| `ground-rd32` @2560x1440 | 0.771 ms | 0.800 ms | -3.6% | 2,268 | no result |

**THE GATE FAILS.** `plains-rd32` loses by 11 to 15 percent on two
independent pairs. A plain default flip would make render distance 32,
which is what most players who move the slider at all actually use,
measurably slower: 1.0.0's mistake at one tenth the scale.

**`ground-rd32` is "no result", not a wash.** Those two ON legs are the
identical resolution-verified configuration and differ by 0.068 ms, which
is larger than any ON-versus-BFS difference ever measured at that scene, so
neither sign means anything. The mechanism is understood: at ground rd32
the GPU pass total is 0.30 ms against a 0.66 ms frame, and below roughly
half a millisecond of GPU work this harness is CPU limited, so a GPU win
cannot reach frame time. Frame-delta over GPU-delta is 0.91 and 0.93 in the
two plains scenes and between 0.10 and 0.71 here. **Any future gate leg
whose GPU pass total is under about 0.5 ms is measuring the CPU and must be
reported as no result rather than as a tie.**

**What the numbers say to build instead, and what NOT to build.** The
payoff tracks the size of the terrain draw: occlusion adds a near fixed tax
(0.176, 0.243, 0.455 ms at the three scenes, growing with region count and
pixels) and removes a share of the draw (0.263, 0.137, 0.782 ms). So the
signal Auto keys on must be the measured cost of the draw, not a section
count.

**The Auto rule this document proposed earlier, `sectionsResident x 0.27us`
against twice the pass cost, is WITHDRAWN.** Fitted against these
measurements it arms occlusion in the `plains-rd32` case that loses. The
0.27 microseconds per section constant is wrong because the sections
occlusion removes are the cheap ones that early depth rejection was already
killing, so a count times a constant systematically overestimates the
prize. Do not build Auto on a count times a constant.

**Also unbuildable from today's counters: any rule keyed on cull rate.**
`TerrainDrawer:1998` gates stats recording on occlusion resources existing,
so `gpuSectionsDrawn` is 0 in every bfsOnly leg. There is no way to ask
what BFS would have drawn while occlusion is on, and no occlusion counter
at all while it is off. The cull rate relative to BFS has never been
measured and must not be quoted.

**The signal that IS available in both modes is `opaqueA + translucent`
from the GPU timers**, which read 0.391 ms (loses), 0.580 ms (loses) and
1.707 ms (wins) at the three scenes. `MesheliumGpuTimers.enabled()` (:369)
returns true when the property is absent, so the timers are live in
shipping builds, with a non-blocking 3-frame-lag readback (`live()` :193,
`lastPassNanosSnapshot()` :203) and a self-latch if timestamps are unusable.
Auto must fall back to BFS whenever the timers are not live.

**But do not write Auto yet.** See the phase-B section: that change moves
every number Auto would be calibrated against.

### The curve, filled in (2026-08-12, 20 legs)

The gap the paragraph above complained about has since been measured. Two
repeats per cell, 1920x1080, render size verified against GLFW, medians.
`saved` is the draw time occlusion removes, `tax` is what its passes cost.

| scene | saved | tax | net | cull | frame |
|---|---|---|---|---|---|
| `ground-rd64` | 0.608 | 0.233 | **-0.375** | 86% | **-31.5%** |
| `plains-rd64` | 0.782 | 0.459 | **-0.323** | 65% | -16.7% |
| `ground-rd64` rep | 0.530 | 0.245 | **-0.285** | 89% | -19.0% |
| `ground-rd32` | 0.263 | 0.174 | -0.090 | 83% | -2.4% (no result) |
| `plains-rd48` | 0.398 | 0.333 | -0.065 | 58% | -5.3% |
| `ground-rd8` | 0.044 | 0.104 | +0.060 | 59% | +12.9% |
| `plains-rd32` | 0.136 | 0.242 | +0.106 | 51% | +12.8% |
| `plains-rd16` | 0.031 | 0.150 | +0.119 | 46% | +12.9% |
| `plains-rd24` | 0.070 | 0.191 | +0.121 | 47% | +19.3% |

**`saved > tax` predicts the frame outcome in 17 of 18 cells.** The one miss
is a `ground-rd32` repeat, the cell already known to be CPU limited, whose
own twin at the identical configuration both predicts and measures a win.

**`ground-rd64` is the result that matters and it was never measured
before.** Every prior rd64 number used the bird's eye camera. At eye level
occlusion culls 86 to 89 percent instead of 65 and wins by 19 to 31 percent.
Long render distance is this mod's entire reason to exist, so occlusion's
best case is also its most important one.

**Resident count is dead as a signal, conclusively.** `ground-rd64` has
about 4,000 resident sections and wins by 31 percent while `plains-rd32` has
about 3,300 and loses by 13. Winners run from 2,252 resident, losers up to
3,298: complete overlap. Cull rate alone fails too, because `ground-rd8`
culls 59 percent and still loses; 59 percent of almost nothing is nothing.

**The trigger that does work:** bfsOnly-mode terrain draw cost. Every winner
is above 0.719 ms, every loser below 0.580 ms, excluding the CPU-limited
`ground-rd32` cells. A threshold near **0.65 ms** separates all thirteen
decidable cells, and it is readable while occlusion is off, which is the
hard requirement. Use it to decide when probing is worthwhile; let the probe
decide the answer.

### Phase B: half the cost, zero sections drawn

Broken out of the tax column, phase B is **17 to 52 percent of everything
occlusion costs** and drew **zero sections in every measured window**. At
`ground-rd64` it is about half the price of the feature for no output.

Not dead code: it fires during world standup and when the camera reveals
terrain. Not free either: `recordPhaseDraws` (:1783-1817) issues one
`vkCmdDrawMeshTasksEXT` per dispatched region with phase A's group count,
and `terrain.task` (:254-258) then rejects each section slot one at a time
on a stamp compare. At rd64 that is 158 regions of task-shader work to draw
nothing.

Predicted effect of removing it, cell by cell:

| scene | net now | net without phase B |
|---|---|---|
| `plains-rd64` | -0.323 | **-0.557** |
| `ground-rd64` | -0.285 | **-0.413** |
| `plains-rd48` | -0.065 | **-0.209** |
| `plains-rd32` | +0.106 | **+0.024** (wash) |
| `plains-rd24` | +0.121 | +0.065 |
| `plains-rd16` | +0.119 | +0.084 |
| `ground-rd8` | +0.060 | +0.042 |

Roughly triples the rd48 win, adds 45 to 72 percent to the rd64 wins, and
turns the rd32 loss into a wash. It does NOT rescue rd16 and rd24, so Auto
is still needed afterwards, but it is worth building first because it moves
every constant Auto would be fitted to.

Two implementations, both needing a spike. Predicate the phase B draws on a
counter the section raster writes for freshly stamped sections, using
`VK_EXT_conditional_rendering`: correct by construction because the same
frame writes the predicate, but reachability through vanilla's backend is
**UNVERIFIED**. Or have the section raster append new section indices and
make phase B an indirect dispatch over just those: more work, no extension
dependency.

#### ATTEMPT 1: BUILT, MEASURED, REVERTED (2026-08-12)

The indirect route was built end to end and **reverted**. Patch preserved at
`misc/dead-ends/phaseb-indirect-dispatch-2026-08-12.patch`; read this before
attempting it again.

Design as built: a per-region `VkDrawMeshTasksIndirectCommandEXT` buffer,
zero-filled each occlusion frame, with every stamp writer raising its
region's `groupCountX` by `atomicMax`, and phase B swapping
`vkCmdDrawMeshTasksEXT` for `vkCmdDrawMeshTasksIndirectEXT`. No new
extension: indirect mesh draws are core to `VK_EXT_mesh_shader`. It compiled,
the suite stayed green, pixel parity held, and `assertCameraCutPhaseB`
passed.

**It made things worse on both axes.** Same-session A/B, occlusion armed in
both legs, two repeats, overlay layers disabled:

| scene | phase B indirect | phase B direct | |
|---|---|---|---|
| `ground-rd64` | 0.142 ms | 0.137 ms | +0.005 |
| `plains-rd32` | 0.093 ms | 0.082 ms | +0.010 |
| `plains-rd64` | 0.263 ms | 0.230 ms | +0.032 |

And the tail was far worse at the heaviest scene. `ground-rd64` worst frame
was 10.9 ms and 63.2 ms on the two indirect runs against 2.4 ms and 2.6 ms
direct, with a 62 ms frame-to-frame jump. Two of two indirect runs, zero of
two direct.

**Why it failed, and this is the lesson.** The mark had to sit OUTSIDE
`box.frag`'s stamp guard for correctness, because a section made visible
only by the mesh stage's camera-inside force write would otherwise go
unmarked and vanish. Correct, and fatal to the purpose: the mark then fires
for EVERY stamped section rather than only newly-visible ones, so almost
every visible region gets marked, nothing is ever skipped, and the indirect
fetch is pure added cost. The safety requirement and the performance
requirement pulled in opposite directions and the safe reading won.

A correct skip needs the mark to fire only on `curStamps` transitioning AND
`prevStamps[gidx] != FrameStamp - 1`, which means detecting the transition
via the `atomicExchange` RETURN value and binding `prevStamps` into the
section raster's fragment stage. That trades a fail-open mark for an exact
one on a path where a miss deletes terrain.

#### The ceiling, measured, so the next attempt starts from a number

Rather than guess, phase B was run with the pass recorded and **no draws at
all** (`meshelium.occlusion.phaseBSkipUnsafe`, a measurement-only switch,
deliberately incorrect: revealed terrain arrives a frame late). The gap is
the entire prize any correct skip could win:

| scene | phase B GPU | frame p50 | fps |
|---|---|---|---|
| `plains-rd32` normal | 0.082 ms | 1.094 ms | 914 |
| `plains-rd32` no draws | **0.001 ms** | **1.011 ms** | **989** |
| `plains-rd64` normal | 0.234 ms | 1.816 ms | 551 |
| `plains-rd64` no draws | **0.001 ms** | **1.599 ms** | **625** |

So the cost IS in the task dispatches and it does vanish: **8 percent at
rd32 and 12 percent at rd64 are genuinely available**, and at `plains-rd32`
that is roughly the whole margin by which occlusion currently loses. The
idea is sound; attempt 1 was not. Note the tail moves slightly the wrong way
when phase B is skipped outright (p99 +0.04 and +0.06 ms, worst frame +0.5
and +0.8 ms), which is phase B doing its actual job, so a correct skip must
be data-driven per frame rather than a blanket disable.

**This no longer supersedes stage 4 automatically.** It is worth 8 to 12
percent, it is not worth shipping terrain holes for, and the bigger lever is
still simply enabling occlusion where it already wins.

Both scenes, not one. The ground-level scene is the hard one and it is the
one that exposed the bug, so a win at `plains-rd32` alone would prove
nothing. `plains-rd64` should be reported alongside but is not the gate,
because rd 64 is where occlusion was always going to win.

**Scene caveat that cuts the other way, and it matters for Auto:** all
three scenes are open terrain, near the worst case for occlusion. A cave or
a mountain valley culls far harder for the same resident count, so a
threshold fitted only to these numbers will be too conservative
underground. Auto should key on something that moves with how much is
actually being culled, not on render distance alone.

**Three further conditions:**

1. **A 2560x1440 leg exists.** The 854x480 harness window is how this
   project got the answer wrong for months, and 1440p is where the owner
   found the bug in real play. A feature whose cost scales with pixels does
   not get re-enabled on 1080p evidence alone.
2. **The over-cull detector, stated correctly this time.** As originally
   written this condition was "`gpuSectionsDrawn` never falls below the
   bfsOnly drawn count", and that is not merely wrong, it is the exact
   opposite of what the shipped suite asserts:
   `MesheliumTerrainDrawTest.assertHiddenWallOcclusion` (:1172) *requires*
   occlusion to draw strictly FEWER sections than bfsOnly, because drawing
   fewer is the entire point of the feature. Stage 1a then showed the
   drawn count is not even reproducible run to run, since it tracks
   `sectionsResident` (see the table in stage 1a).

   The two detectors that actually work, both non-negotiable, on a moving
   camera as well as a static one:

   - **`phaseBQuietStatsFrames` stays large.** A dropped stamp puts its
     section into phase B the next frame, so a per-frame phase-B stream in
     a converged static scene is the signature. This is the sensitive one.
   - **Pixel parity against the bfsOnly reference** at the same camera,
     which is what shots 40/41 and 50/51 already do.

   `gpuSectionsDrawn` may be reported, but it must never be used as a
   pass/fail gate on its own: a rise is ambiguous (it is equally the
   signature of a grown visible set and of an over-cull one frame earlier)
   and a fall is the feature working.
3. **The row returns as Auto, not as a plain toggle.** Both
   `MesheliumConfig.java:154-156` and the settings-screen comment already
   call for this: arm occlusion when `sectionsResident x 0.27 us` exceeds
   twice the measured pass cost, which is a number the drawer already
   tracks. Deciding this per scene instead of per build is the proper fix,
   and shipping a bare toggle again would be shipping the same mistake with
   a better constant.

When those clear, update `MesheliumConfig.java:124-157`, delete the
no-occlusion-row comment block at `MesheliumOptionsScreen.java:359-374`,
and add the measured rows to [`PERFORMANCE.md`](PERFORMANCE.md) in the same
change, per house rule 6.

## Evidence base

**Jar**, all bytecode citations, read with `javap -p -c`:
`../attack-of-the-bteam-1.26.2/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2.jar`

Key bytecode findings, with offsets:

| finding | site | evidence |
|---|---|---|
| depth attachment is SAMPLED | `MainTarget.allocateDepthAttachment` ip 9, 11 | `bipush 15`, `GpuFormat.D32_FLOAT`; same shape at `RenderTarget.createBuffers` ip 81, 83 |
| usage bit decode | `GpuTexture` constants | 1 COPY_DST, 2 COPY_SRC, 4 TEXTURE_BINDING, 8 RENDER_ATTACHMENT; 15 = all four |
| bit 4 becomes SAMPLED | `VulkanConst.textureUsageToVk` ip 33-42 | `iconst_4; iand` then `iconst_4` = `VK_IMAGE_USAGE_SAMPLED_BIT` |
| no STORAGE path exists | `VulkanConst.textureUsageToVk` ip 2-63 | no branch emits 0x08 |
| format is `VK_FORMAT_D32_SFLOAT` | `VulkanConst.toVk` key 52 to ip 513 | `bipush 126`, neighbours 53/54/55/56 all self-consistent |
| everything lives in GENERAL | `VulkanGpuTexture.<init>` ip 241, 248 | UNDEFINED(0) to GENERAL(1), never moved again |
| depth attachment binds GENERAL | `VulkanCommandEncoder.createRenderPass` ip 592 | `imageLayout(1)` |
| free full barrier after each pass | `VulkanCommandEncoder.submitRenderPass` ip 22, 78 | `vkCmdEndRenderingKHR` then `memoryBarrier`, stages 65536 ALL_COMMANDS, access 98304 |
| no MSAA | `VulkanGpuTexture.<init>` ip 129 | `samples(1)` |
| no vanilla depth mip chain | both creation sites | `mipLevels = 1` (`iconst_1`) |
| dispatch seam | `VulkanCommandEncoder.execute` ip 0-30 | refuses inside a render pass, ends CB, appends to submission |
| graphics queue is compute-capable | `VulkanPhysicalDevice` ip 258-267 | `hasAllBits(queueFlags, 3)` |
| push descriptors guaranteed | `VulkanBackend.<clinit>` ip 22-40 | `VK_KHR_push_descriptor` in `REQUIRED_DEVICE_EXTENSIONS` |
| instance is Vulkan 1.2 | `VulkanInstance.<init>` ip 58 | `VK12.VK_API_VERSION_1_2` |
| vanilla query pool is timestamp-only | `VulkanQueryPool.<init>` ip 29-30 | `iconst_2` into `queryType`, no parameter |

**Registry:** `VK_NV_representative_fragment_test` is `author="NV"`,
`supported="vulkan"`, with **no** `promotedto`, `deprecatedby` or
`ratified` attribute (`vk.xml` line 24684, header version 359), last
modified 2018-09-13. In the Vulkan registry those absent attributes are
positive evidence of no successor. Hardware database corroborates: 187 of
187 reporting devices are NVIDIA.

**Bench archive:** `../misc/bench-results/ground-occlusion-2026-08-12/`
(`rd32-ON.json`, `rd32-OFF.json`),
`../misc/bench-results/occlusion-ab-1080p-2026-08-11/`,
`../misc/bench-results/meshelium-2026-08-10/` (the only archived runs with
per-pass GPU timestamps armed).

**Source sites an implementer needs:**

- `src/main/java/com/deds/meshelium/vk/TerrainOcclusion.java` (121-140
  barrier story, 697-791 pipeline build, 710 the cull-NONE line, 726 the
  GEQUAL line)
- `src/main/java/com/deds/meshelium/vk/TerrainDrawer.java` (1416-1481
  target and depth-view acquisition and the error latch, 1696-1755 the four
  passes, 2364 the translucent gate, 2937-2945 the existing GENERAL-layout
  image descriptor)
- `src/main/java/com/deds/meshelium/vk/MesheliumTerrainGpu.java` (227-237,
  the transient-CB plus `execute` precedent)
- `src/main/java/com/deds/meshelium/vk/MesheliumShaderCompiler.java` (add
  `KIND_COMPUTE`)
- `src/main/resources/assets/meshelium/shaders/occlusion/` (`box.frag`,
  `region_raster.mesh`, `section_raster.mesh`, `section_raster.task`)
- `src/main/java/com/deds/meshelium/MesheliumConfig.java:124-157` and
  `src/main/java/com/deds/meshelium/gui/MesheliumOptionsScreen.java:359-374`
  (the two places that revert when the bar is cleared)

## Unverified ledger

1. The brief's 1.99 / 1.10 / 0.10 ms split is **not in any archived bench
   artifact**: `ground-occlusion-2026-08-12` has timers disarmed
   (`timestampPeriodNs = 0.0`, `framesCaptured = 0`). The *sum* is attested
   in the settings-screen source comment written the same day ("about
   3.1 ms ... about 0.1 ms (GPU timestamps, ground level taiga,
   2026-08-12)") and is independently reproduced as 2.97 ms by the A/B
   arithmetic above. The split between the two passes rests on the source
   comment and the brief alone. Re-run the ground scene with timers armed
   at stage 0 and archive it.
2. The brief says "3,000-plus sections"; the archived ground run says
   `sectionsResident = 2257`. 3,000-plus is the `plains-rd32` figure
   (3,271). All ground-scene arithmetic here uses 2,257, which makes the
   prize *smaller* and the bar *harder*.
3. ~~Whether the same-address `atomicExchange` dominates is a hypothesis.~~
   **CLOSED 2026-08-12, and it was the answer.** Guarding the store took
   `ground-rd32` from 3.484 ms to 0.644 ms, removing about 97 percent of
   both box passes. The atomic was not *a* cost, it was essentially the
   whole cost, and the fill-rate framing this document is named after was
   wrong.
4. ~~Whether LLPC already collapses a wave of identical wave-uniform
   `atomicExchange` operations with an unused result.~~ **CLOSED
   2026-08-12: it does not.** If it had, the guard could not have produced
   a 5.4x speedup, because there would have been nothing left to remove.
5. Whether AMD's driver keeps depth metadata and hierarchical rejection
   enabled for a GENERAL-layout image. No vendor document consulted.
6. The winding handedness of `BOX_TRIS` under 26.2's projection and
   viewport convention. This is exactly why stage 2 is three-face emission
   and not `cullMode = BACK`.
7. `VK_FORMAT_D32_SFLOAT` plus SAMPLED is not *formally* guaranteed by the
   spec's mandatory-format table (it guarantees only that at least one of
   `X8_D24_UNORM_PACK32` and `D32_SFLOAT` supports it). Verified in
   practice: vanilla creates this exact image, so any device that would
   reject it cannot boot 26.2's Vulkan backend.
8. shaderc's shipped natives compiling a compute stage. The LWJGL kind
   constant is verified; the natives carry the same caveat
   `VANILLA-VULKAN-SEAM.md:150-153` already logs for mesh and task stages.
   Risk is low, compute is shaderc's oldest stage.
9. `maxFragmentSize` on RDNA4 is `[2,2]` per hardware-database report
   49215, which is driver 26.6.1 against the desk's 26.7.1. Relevant only
   to candidate B, which is rejected.
10. NVIDIA and Intel silicon: no first-party measurement exists for any
    claim on this page.
