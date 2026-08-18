# Speedup candidates, researched 2026-08-17 (night one of 1.4.0)

## OWNER DECISIONS, 2026-08-18 (these override anything below)

1. **Mission restated:** mesh shaders were the origin; the product is
   frame rate and smoothness - no big stutters. Accuracy is core value.
2. **Track 1 (smoothness): APPROVED for implementation** once current
   work lands. The incremental draw snapshot goes first.
3. **Track 2 (LOD): NOT YET.** The owner dislikes how Distant Horizons
   looks; accuracy is a big part of this mod's value. APPROVED from the
   ladder: D1 stop-drawing-sub-pixel-plants. The vision when LOD does
   come: a settings submenu of per-feature DISTANCE SLIDERS ("after so
   many chunks stop doing plants, after so many chunks relax merge
   rules, after so many chunks half scale") - half-scale undecided.
   Voxy-style far field, possibly with its own worldgen, and the
   owner's surface-mesh idea (beyond some distance keep ONLY the
   computed exposed-surface mesh of a chunk and store nothing else in
   VRAM) are parked for design sessions WITH the owner - "tons of
   human input" requested. Do not build any of that autonomously.
4. **Track 3 (GPU slices): APPROVED**, same slider treatment.
5. **The five-gap ecosystem list is the funded backlog** (visible-only
   texture animation, arena defrag, flat-water resort avoidance,
   mesh-time face culling, sub-pixel geometry culling).
6. **Uncertainty rule:** any change not 100 percent certain (visual or
   behavioral - sub-pixel culling is the named example) ships as a
   SETTINGS ROW, default off, for the owner to play with.
7. **Sodium clean-room rule:** concepts may be studied; never copy
   their code; never credit or allude to Sodium in anything shipped
   (code, comments, changelog, store pages). Their license is strict.
   Internal research docs like this one may keep factual references.
8. **The vanilla-Vulkan AMD/Intel regression (barriers + layouts) is a
   priority investigation** - "could be a headline win for exactly the
   cards we run on."

Four-way research sweep: repo evidence audit, Minecraft LOD state of the
art, mesh-shader-era GPU techniques, and the perf-mod ecosystem. This
file is the synthesis; every candidate carries the cheapest kill test
that prices it before real work, per house discipline. Nothing here is
built. Sources and file:line citations live in the session reports; the
load-bearing ones are repeated inline.

## The frame, and where the room actually is

At plains-rd64 / 1440p static with everything shipped tonight: frame p50
1.96 ms = opaque 0.85 + occlusion rasters 0.29 + phase B 0.00 (CPU skip)
+ translucent 0.44, remainder CPU and present. Eye level at the same
distance: 0.7 ms frames. The GPU is NOT where real play hurts anymore.
Real-play pain, in order:

1. **Rotation churn and build storms** (the owner feels this daily):
   ~25 percent fps loss while turning, attributed by measurement to
   vanilla's extract + chunk rebuild churn, NOT our frustum walk
   (0.022 ms amortized, already exonerated). Vanilla only schedules
   rebuilds for dirty sections inside the frustum, so every turn dumps a
   fresh batch into the build queue.
2. **Scale costs at rd 96-120**: arena ~1.1 GB at rd120, owner measured
   4 GB total VRAM in real play, build storms triple when the world is
   3.5x the columns of rd64. No frame bench exists past rd64.
3. The remaining GPU slices, each small and known.

So the roadmap splits three ways: SMOOTHNESS (CPU), SCALE (LOD +
memory), RAW FPS (GPU slices).

## Track 1: smoothness - own the visibility bookkeeping (ecosystem-corroborated)

Sodium 0.7 (merged 2026-05) moved occlusion BFS and render-list
generation off the render thread with one-frame staleness and a
sync-on-teleport fallback, plus frame-rate-independent rebuild
budgeting. That is exactly the shape our own measurements point at, and
Nvidium ran its BFS async from the start (the one Nvidium trick never
ported here).

- **A1. Off-thread render-list generation**: our own connectivity BFS
  from our section records + vanilla's VisibilitySet, one frame stale,
  feeding the bfs fallback masks, sealed-room gating, and translucent
  cross-section order. Effort L, parity risk in translucent ordering.
- **A2. Direction-independent rebuild scheduling**: budgeted background
  drain of dirty sections regardless of view direction - we draw
  all-around terrain anyway (retention). Turns turn-storms into steady
  baseline. Effort M.
- **A3. Extract slimming** under the skipVanillaPrep census protocol
  (ceiling 0.45 ms static, more while rotating).

**KILL TEST for the whole track (one spin bench, cpustages armed): sum
extract + occlusionGraphUpdate + applyFrustum during rotation, and count
rebuild tasks per second turning vs static. Under ~0.3 ms/frame and no
rebuild spike, the track dies** (the decoupled-translucent fix died
exactly this way; respect the precedent).

## Track 2: scale - the LOD ladder (owner green-lit geometry LOD)

Key structural gift: blocky terrain skips Nanite's hard parts. Coarse
and fine partitions of the same axis-aligned plane cannot crack, so
section-granularity LOD needs none of the DAG/grouping machinery. And
unlike Distant Horizons, our far field is already full-fidelity resident
meshes (retention), so LOD here is COMPRESSION of perfect data, not
reconstruction - no DH-style lighting/color seam is forced. DH is
LGPL-3.0 (study freely); Voxy is ARR (observe behavior only, no code).

Cheapest first:

- **D1. Bucket-drop / sub-pixel cull** (merges with the GPU report's
  top pick): the UNASSIGNED facing bucket (grass/flower crosses, no
  merge plane, ~sub-pixel beyond ~40-80 chunks at 1440p) is emitted
  unconditionally today. A distance gate on that bucket in terrain.task
  is a ~5-line change, zero memory, zero rebuild. KILL TEST: gate at 32
  chunks, one plains-rd64 bench: quad delta + opaqueA + screenshots.
  Under 10 percent of drawn quads, dead.
- **D2. Distance-relaxed greedy merge**: beyond a ring, merge on
  coplanar + same sprite + facing only (area-weighted light instead of
  the exact affine gate). The parity constraint is what starves the
  shipped merge (5.9 percent at rd64); relaxed, open terrain should
  approach O(perimeter). NEGATIVE cost: fewer quads = less arena,
  faster builds. Distant AO gradients flatten slightly. KILL TEST: flag
  forcing the relaxed predicate globally, one bench: merge percent
  (target >= 40), build us/section, screenshots at 32+ chunks. Under 25
  percent, the cheap-LOD-via-merging family dies.
- **D3. Mip-sections**: 2x2x2 section groups downsampled to half scale
  (cell solid if >= N of 8 children), remeshed by the same GreedyMesher,
  SAME 16-byte codec with 1 unit = 2 blocks - a 2-bit scale tag fits in
  the section record's free header bits (26-31), and the mesh shader
  multiplies by 1<<scale. Recurses to quarter scale for rd 96-120.
  Culling/occlusion/buckets work unchanged. Build at retention time
  while the ClientLevel chunk still exists (we keep no block data).
  Applied beyond 48 chunks at rd120: ~63 percent total quad/VRAM cut.
  KILL TEST (CPU-only, no shader work): downsample+remesh one captured
  plains region offline; kill if LOD/full quad ratio < 2.5x or > 2
  ms/group.
- **D5. Two-stream sections** (only if D2's ring pops visibly): both
  quad streams resident, task shader picks per section by projected
  error - continuous pop-free LOD, costs +25-40 percent far-ring arena.
- **D6. Far water top-sheet**: beyond a ring draw only up-facing
  translucent quads (encoder orders up-facing first inside the
  translucent range - an ordering contract, not a format change). Pairs
  with the flat-water memory case. KILL TEST rides any ocean bench.
- **D4. Column heightmesh beyond retention** (the rd-512 play): 8-16
  B/column records, mesh workgroups SYNTHESIZE top+skirt quads from an
  SSBO (geometry amplification is what mesh shaders are for; none of
  DH's VBO management). Weeks of work. KILL TEST FIRST: screenshot
  arithmetic at rd120 - if fog/sky already eats the far field at our
  fov, the design is decoration.

Strategic flag: **Distant Horizons 3.1.0-b ships native Vulkan support
on 26.2's Blaze3D** - the LOD giant now runs on our exact backend.
Coexist (Meshelium near + DH far) or compete (D4) is now a real product
decision, and a compat test either way.

## Track 3: raw fps - GPU slices (each small, each cheap to price)

- **G1. Per-draw fragment shading rate** (VK_KHR_fragment_shading_rate,
  cross-vendor RDNA2+/Turing+/Arc): one draw = one region already, so
  2x2 shading beyond N chunks is per-draw dynamic state - no attachment
  image, no render-pass surgery. Maybe 0.15-0.2 ms of opaque +
  translucent. KILL TEST: force a global 2x2 rate, one bench = the
  ceiling; under 0.15 ms, drop. Half a day.
- **G2. Alternate-frame occlusion rasters**: raster boxes every 2nd
  frame under a camera-motion threshold; stamp staleness only ADDS
  draws (fail-open direction). ~0.145 ms average. Needs a stamp-epoch
  tweak for phase A's prev-frame test.
- **G3. Quad-record repack** 64 -> 40-48 B (origin+extent+shared
  material, lossless for rectangular quads, raw fallback class for
  fluid corners): frame-time prize ~nil (the 0.003 ms lightmap kill
  bounds the whole mesh-ALU family); fund ONLY as a VRAM play with a
  measured rd120 arena number.
- **Closed by structure, do not revisit**: meshlet cone culling (the
  facing-bucket walk IS exact 90-degree cone culling, tighter than any
  fitted cone); visibility buffer (our frag is atlas+lightmap - fails
  the expensive-shading precondition); async compute (single vanilla
  queue + full ALL_COMMANDS barriers after every pass; AMD already runs
  task shaders on a side HW queue under us); device-generated commands
  (NVIDIA's EXT path measured 90 ms vs 7.3 ms for the NV path on the
  same workload - disqualifying for a cross-vendor mod).

## Track 4: beyond terrain - OWNER-FLAGGED for later (2026-08-18)

The owner explicitly parked this as future scope: everything else on
screen. Entity rendering, block entities (chests, signs), particles,
clouds/sky/weather, and the sneaky one - only animating the texture
sprites actually visible (vanilla updates every animated texture every
tick, lava included, on or off screen). None of it is terrain, all of
it costs frame time, and the spin-frame attribution gap (~6 ms of a
7.5 ms rotating frame outside every Meshelium stage timer) likely
contains several of these.

Strategic frame agreed the same night: Meshelium owns terrain; prefer
COEXISTENCE with Sodium (their 0.9.x has early Vulkan support on 26.2)
over requiring them Nvidium-style - detect-and-yield first, then
coexistence testing when their Vulkan exits beta. These beyond-terrain
items are therefore candidates EITHER for "let Sodium do them" (if
coexistence works on the Vulkan backend) OR for Meshelium's own
expansion (if it does not, or where we can do better on our backend).
Decision inputs: the ecosystem inventory's Sodium-on-Vulkan findings
and the frame-gap attribution run. Do not start building any of these
before both exist.

EntityCulling (async entity raytrace culling), Lithium, Moonrise (has a
26.2 branch; integrated-server wins = fewer worldgen stalls pacing the
client), C2ME (parallel worldgen). ImmediatelyFast is a custom GL buffer
layer - unverified on the Vulkan backend, check before recommending.

## 2026-08-18 deep-dive addendum: Track 1 findings + the ecosystem delta

Six further reports (Track-1 deep dive x4, ecosystem inventory x2)
landed after the sections above. Corrections first, then the news.

**Corrections to this file:** Sodium's async graph culling shipped in
0.9.0, not "0.7" - and Sodium 0.9.0 (2026-06-16) runs EXPERIMENTALLY ON
the vanilla Vulkan backend, our exact seam. SPEC.md:115's standalone
premise ("no Sodium is known to run on it") was already false when the
1.4.0 cycle opened. Sodium-on-Vulkan and Meshelium are either/or on
terrain: detect-and-yield is now an obligation, not a nicety. Also on
this backend already: Distant Horizons 3.1.0-b and Vulkan PostFX
(post-processing) - the first frame-mates recording into our frame.

**The unattributed-frame premise was WRONG, and the correction found
the real lever.** Two bookkeeping errors (applyFrustum nests inside
extract; stage rows land 1-2 indices after their frame delta)
manufactured the "6 ms unattributed" claim. Corrected: spin frames are
~88 percent attributed, every 70-700 ms monster frame lands inside a
stage timer once shifted, and the static gap is GPU-pace WAIT in
vanilla's frame-end submit throttle, not hidden work (adding CPU inside
a pace-bound static frame measurably costs nothing). The real finding:
**mesheliumOpaque inflates 0.098 to 3.07 ms p50 while turning - OUR
code, ~2.9 ms of the 7.5 ms spin frame** - because every residency
epoch bump reallocates and refills the whole draw snapshot (2 MB, 25.5k
sections while spinning = ~280 MB/s of garbage, which is also the
signature behind the recurring 14-17 ms hitches) and rebuildRegionMap
rescans everything. An incremental snapshot / dirty-region-only rebuild
attacks the turn p50 AND the hitch tail at once. This is night two's
top smoothness item, ahead of anything async.

**Track 1 refinements (full designs in the session reports):**
- A1 async visibility: honest boundary established - by itself it
  removes NONE of vanilla's render-thread work (vanilla consumes its
  own list for rebuilds/BEs/resorts); it buys independence, and the
  sealed-room GPU pre-gate that could recover the enclosed-cave ~15
  percent. That pre-gate (K3) is testable TODAY with existing mask
  machinery, no async needed. Sodium's design answer worth copying
  when we build it: BFS trees are FRUSTUM-FREE, so rotation never
  invalidates anything; only position moves rebuild them.
- A2 rebuild scheduling: full design exists - budgeted consumption cap
  at the extract seam (never touching the player-edit sync path), a
  direction-independent drip that pre-builds off-screen dirty sections,
  and a ~100 ms coalescing window for tick-driven re-dirty trains.
  The rigorous safety argument: deferral costs bounded STALENESS never
  ABSENCE, because retention + the 1.3.0 handover fix keep the old copy
  drawable through any deferral - the property vanilla lacks.
- New smoothness kill tests, all S-effort, mostly one combined bench
  session: five new stage brackets (submit/present, compileSections
  window, acquire, levelRender, renderFrame) + a GC log leg convict or
  acquit GC for the monster frames; grow-log join; superflat-vs-normal
  server-contention A/B; pump-budget A/B; ocean resort-churn leg.

**The ecosystem delta - what they have that we genuinely lack**
(everything else audited: every Nvidium and VulkanMod mechanism is
shipped here, structurally superseded, or rejected with receipts):
1. Animated-texture visible-only ticking (Sodium): vanilla uploads
   every animated sprite every tick, lava included. Nobody owns this on
   26.2. Our stamp data is a better visibility source than their BFS.
   Kill test: one cpustage around vanilla's texture tick; under 0.1
   ms/frame it dies.
2. Incremental arena defragmentation (Sodium shipped the precedent
   2026-07): our trim never compacts; the mover primitive exists
   (grow-and-copy + fence parking). VRAM play for rd 96-120.
3. Resort-trigger avoidance (their GFNI work, concept only): classify
   plane-pure translucent sections and cancel vanilla's resort tasks
   for them - pairs with the flat-water census machinery.
4. Aggressive mesh-time face culling (MoreCulling concept, GPL - port
   the idea, zero code): voxel-shape overlap tests kill quads vanilla
   keeps; strict-subset rules are pixel-safe.
5. Sub-pixel triangle cull (Nvidium had it; we dropped it as a
   parity casualty): round(min)==round(max) extent test in the mesh
   stage, property-gated. Composes with the D1 bucket gate.
6. Precise barriers around our 4-pass chain (5 inherited ALL_COMMANDS
   full stalls; kill test is free - read the existing inter-pass
   timestamp bubbles first). Community context: vanilla's Vulkan
   backend measured +21-31 percent on NVIDIA but up to -58 percent on
   AMD/Intel vs GL in April snapshots; the barrier discipline and
   all-GENERAL layouts are the documented suspects, and both fixes are
   mod-scoped and legal.
7. Own mesher emitting our codec directly (kills the 28-byte-to-16-byte
   re-encode): funded ONLY if the spin bench convicts compile time;
   rides the same rewrite as D2's relaxed merge.
8. Sodium 0.9.2 carries a backport of a 26.3 fix for a KNOWN 26.2
   buffer-recycling perf bug (inventory item rendering) - tiny mixin,
   non-terrain, owner's call.

## Suggested night-two order

1. Probe-Auto (already designed in OCCLUSION-FILLRATE-DESIGN.md, its
   curve data landed tonight).
2. The one bench run that prices D1 + D2 together (two flags, same
   scene), plus screenshots for acceptability.
3. The one spin bench that prices Track 1 (cpustages + rebuild counts).
4. G1's half-day ceiling test.
5. Build whatever survived, in prize order.
