# Meshelium performance: measured, not promised

Every number Meshelium claims is on this page, together with the machine it
came from, the method that produced it, and the traps that method had to
survive. This is the companion to [`TECHNICAL.md`](TECHNICAL.md), which
explains how the renderer works and deliberately keeps no tables of its own. If
the two ever disagree about a figure, this page wins: this is where figures are
maintained.

It is written for anyone deciding whether to believe the headline, and for
anyone who wants to reproduce it on their own hardware. Read the rig and the
method first. Everything after them depends on those constraints, and the
largest constraint is that all of this came from a single graphics card.

**Contents**

1. [The rig every number came from](#the-rig-every-number-came-from)
2. [Method](#method)
3. [Reproducing these numbers](#reproducing-these-numbers)
4. [The release sweep](#the-release-sweep-100-measured-at-1920x1080)
5. [Resolution changes the answer](#resolution-changes-the-answer-and-this-page-ignored-it-for-months)
6. [Reading the curve, including the part that loses](#reading-the-curve-including-the-part-that-loses)
7. [GPU cost per pass](#gpu-cost-per-pass)
    - [Occlusion culling is not free (superseded)](#occlusion-culling-is-not-free-and-below-render-distance-48-it-loses)
    - [The numbers after the atomic fix](#the-numbers-after-the-atomic-fix)
    - [Is occlusion culling worth it now?](#is-occlusion-culling-worth-it-now-only-at-long-distances)
8. [Workgroup sweep](#workgroup-sweep)
9. [The CPU optimisation pass, and what it removed](#the-cpu-optimisation-pass-and-what-it-removed)
10. [Traps this page had to survive](#traps-this-page-had-to-survive)
11. [Cross vendor status](#cross-vendor-status)
12. [What this page does not cover](#what-this-page-does-not-cover)

## The rig every number came from

**AMD Radeon RX 9070 XT (RDNA4)**, driver 1.4.349 (Adrenalin 26.7.1, LLPC),
Windows 11, Minecraft 26.2 on its own Vulkan backend. ONE machine. No NVIDIA
and no Intel number appears anywhere on this page, because none has ever been
taken. See [Cross vendor status](#cross-vendor-status).

## Method

Every leg is a `-Pmeshelium.bench=plains-rd<N>` run: a real NORMAL preset
world, seed 4242, a fixed spectator camera at (0.5, 130, 0.5) facing yaw 45 and
pitch 25, clouds off, time and weather frozen, 120 warmup frames then 600
measured frames.

**The vanilla baseline is measured in the same session, from the same camera**,
by flipping Meshelium's kill switch live. Both legs therefore share a driver
state, a chunk set and a thermal state. No baseline on this page was taken on a
different day from the Meshelium leg it is compared against.

Two reading rules the numbers depend on:

- **CPU figures are whole frame means, reported with p95. GPU figures are per
  pass timestamp queries. The two are never summed**, because they are two
  different measurements of the same frame, not two halves of one.
- **CPU stage timings are not additive.** They are render thread wall time
  between nanoTime pairs, and the frustum rebuild nests inside the extract
  stage, so adding the rows of a stage table double counts.

One honest limitation, stated once and applying to everything below: **the
bench camera is static.** That is what makes the runs repeatable, and it also
means the frustum rebuild almost never fires, so any stage attribution here
understates real mouse look play by roughly the cost of that rebuild times how
often you actually turn. Anything rotation driven has to be judged from a play
session with the debug stats line, not from this page.

## Reproducing these numbers

```
./gradlew runClientGameTest -Pmeshelium.backend=vulkan -Pmeshelium.bench=plains-rd16
```

The scenes that need no other flag are `plains-rd8`, `plains-rd16`,
`plains-rd24` and `plains-rd32`. The two extended scenes need the render
distance armed at boot as well, because Meshelium widens the option range
before `options.txt` loads and an unwidened range would silently clamp the run
back to 32:

```
./gradlew runClientGameTest -Pmeshelium.backend=vulkan -Pmeshelium.bench=plains-rd64 -Pmeshelium.rd=64
```

(and the same shape for `plains-rd48` with `-Pmeshelium.rd=48`). Raw series
land in `build/run/clientGameTest/meshelium-bench-<scene>.json`. The harness
boots a real client on your real GPU, runs both legs in one session, and writes
the whole series rather than a summary, so you can check the distribution
instead of trusting a mean.

Diagnostic instrumentation is OFF for every published row. The CPU stage timers
and the per pass GPU timestamp queries cost about 0.06 ms per frame, which is
invisible at render distance 64 and material at render distance 8, and they are
off in normal play, so leaving them on would measure a game nobody plays. To
collect a stage breakdown of your own, arm it explicitly with
`-Pmeshelium.cpustages` (bench runs arm the stage timers automatically) and
read the `cpuStages` block written into the bench JSON for both legs.

The raw JSON behind the release sweep lives on the development machine at
`../misc/bench-results/meshelium-2026-08-11-release-final/`, outside this
repository. It is not distributed with the mod, so reproduce these rows rather
than trust them.

## The release sweep (1.0.0, measured at 1920x1080)

**The resolution belongs in the heading**, and until release day it was
missing from this whole page. See the resolution section for why that
mattered more than any other caveat here.

Raw JSON: `../misc/bench-results/release-1080p-2026-08-11/`.

| Render distance | Meshelium | Vanilla | Speedup |
|---|---|---|---|
| 8 | 2,155 fps | 2,053 fps | 1.05x |
| 12 (Minecraft's default) | 1,748 fps | 1,511 fps | 1.16x |
| 16 | 1,486 fps | 1,054 fps | 1.41x |
| 24 | 946 fps | 597 fps | 1.59x |
| 32 (vanilla's maximum) | **696 fps** | 372 fps | **1.87x** |
| 48 | **513 fps** | 172 fps | **2.98x** |
| 64 | **413 fps** | 99 fps | **4.15x** |

Same session pairs: identical world, identical camera, the kill switch
flipped between legs, occlusion culling at its shipped 1.0.0 default of
OFF, diagnostic instrumentation off. Rows 48 and 64 suppress the
clamp-back for the vanilla leg so vanilla renders the same distance with
its own renderer, which is what makes those ratios real rather than
comparisons against a refusal.

Meshelium is ahead at every distance measured. Earlier editions of this
page reported vanilla winning below render distance 16, which was an
artefact of two mistakes now fixed: occlusion culling defaulting on, and
measuring in a window nobody plays in.

## Resolution changes the answer, and this page ignored it for months

Every table published before 1.0.0 came from the harness window:
**854x480, 0.41 megapixels**. That is a fifth of 1080p, a ninth of 1440p,
a twentieth of 4K. Anything whose cost scales with pixels was measured at
a fraction of its real price.

It did not just shift numbers, it inverted a conclusion:

| | 854x480 | 1920x1080 |
|---|---|---|
| rd 32, occlusion on | 827 fps | 317 fps |
| rd 32, occlusion off | 905 fps | **697 fps** |
| rd 64, occlusion on | **556 fps** | 263 fps |
| rd 64, occlusion off | 341 fps | **413 fps** |

In the small window occlusion won at rd 64 by 63 percent. At 1080p it
loses by 57 percent. At rd 32 the shipped default was slower than vanilla
itself, 317 against 372. The owner found this by playing at 1440p and
turning the setting off. No benchmark on this page could have caught it,
because none of them ran at a resolution anyone plays at.

Two rules follow:

1. Every frame rate is quoted with its resolution. A number without one
   is an anecdote.
2. Anything whose cost is per pixel gets benchmarked at a real
   resolution. The harness now accepts `-Pmeshelium.res=1920x1080`.

## Reading the curve, including the part that loses

Meshelium trades a fixed per frame cost, the occlusion passes and the culling
dispatch, for a per section cost that is close to free. Vanilla is the mirror
image: almost no fixed cost, and a per section cost that grows with the square
of the render distance. The two curves therefore cross, and the crossing is at
render distance 16.

Below that, Meshelium loses, and the page says so. At render distance 8 it runs
1,239 fps against vanilla's 2,137. The scene is so light that vanilla's draw
submission is nearly free while Meshelium still pays for machinery built for
far heavier frames. Nobody loses a playable frame there, both numbers being
several times any monitor's refresh rate, but a curve that quietly started at
16 would be dishonest.

Above the crossing the advantage compounds, and it compounds hardest exactly
where vanilla cannot follow: 2.24x at vanilla's own maximum, 3.79x at 48, and
**5.99x at 64, where vanilla manages 93 fps and Meshelium manages 556**.

Noise, stated plainly: the sub 16 rows sit under one millisecond per frame and
move by up to twenty percent between runs, so treat their exact ratios as
indicative and the ordering (vanilla ahead below 16) as the finding. From 24
upward the frames are long enough that repeat runs land within a few percent.

## GPU cost per pass

### Occlusion culling is not free, and below render distance 48 it loses

> **FIXED 2026-08-12. Everything in this section describes the shipped
> 1.0.0 build and is no longer how the code behaves.** The cost was one
> line: every fragment of an occlusion box wrote the same word with an
> atomic, and same-address atomics serialise at the L2, so a box covering
> the screen cost a million serialised read-modify-writes on a single
> address. It was never the fill rate this section blames. Read-guarding
> that store removed about 97 percent of both passes and occlusion is now
> comfortably FASTER than not having it at every distance measured, not
> just above 48. See [the numbers after the
> fix](#the-numbers-after-the-atomic-fix) below and
> [`OCCLUSION-FILLRATE-DESIGN.md`](OCCLUSION-FILLRATE-DESIGN.md) stage 1a.
> The section is kept as written because the reasoning it contains is
> sound and only the premise was wrong, and because the default is still
> off until the re-enable gate is cleared.

Found by the owner in real play on 2026-08-11 ("i just turned off
occlusion culling and it seems to majorly increase the fps"), then
reproduced here. This page had measured how many sections occlusion
REMOVES and never once measured whether that saving beats what the
occlusion passes COST. Those are different questions.

Open plains, static camera, everything else at defaults:

| Render distance | Occlusion on | Occlusion off | Verdict |
|---|---|---|---|
| 16 | 1,139 fps | **1,597 fps** | occlusion costs 29 percent |
| 32 | 827 fps | **905 fps** | occlusion costs 9 percent |
| 48 | **700 fps** | 688 fps | break even |
| 64 | **556 fps** | 341 fps | occlusion gains 63 percent |

The shape is the same one that governs the whole mod: the two occlusion
passes are a fixed cost per frame, while what they save grows with the
number of sections in front of you. At 9,000 resident sections the saving
is enormous. At 900 it does not pay the rent.

Scene caveat, and it matters: this is OPEN PLAINS from a camera above the
ground, which is close to the worst case for occlusion because almost
nothing hides behind anything. Caves, mountains and forests should move
the crossover nearer, possibly much nearer. Anyone reading this table as
"occlusion is bad" has read it wrong; it is scene dependent, and the
default should be too.

Consequence: shipping occlusion ON at every distance is wrong for the
distances most people play at. The fix is to decide per scene rather than
per build, keyed on how many sections the pass would actually have to
work on, which is a number the drawer already counts.

What the occlusion machinery buys, at render distance 32: **3,254 sections
resident, 1,576 drawn.** Box raster occlusion plus the visibility mask removes
just over half the draw set in open plains, which is close to the worst case
for occlusion. Caves and mountains cull far harder.

What it costs, at the same render distance, as means:

| Pass | Time |
|---|---|
| Phase A (opaque, last visible set) | 0.362 ms |
| Region box raster | 0.249 ms |
| Section box raster | 0.178 ms |
| Phase B (newly visible) | 0.082 ms |
| Translucent | 0.140 ms |
| **Meshelium GPU total** | **1.01 ms** |

### The numbers after the atomic fix

Same rig, same seed, same camera, same session, 1920x1080, static camera,
occlusion armed ON in both legs. The only difference is three lines in
`shaders/occlusion/box.frag`: read the stamp word before writing it, and
skip the atomic if this frame already stamped it.

| Scene | Before | After |
|---|---|---|
| Ground level, rd 32 | 3.484 ms (287 fps) | **0.644 ms (1,553 fps)** |
| Open plains, rd 64 | 3.802 ms (263 fps) | **1.730 ms (578 fps)** |

That is a 5.4x reduction in the cost of the occlusion path, measured the
only way it can be trusted: the guarded and unguarded builds run back to
back in the same session on the same scene, changing nothing but the
shader. The 2.84 ms saved in the ground scene lands on the 2.97 ms the two
box passes were independently measured to cost, which is the same
statement twice: the atomic WAS the passes.

**What this does NOT say, and a correction to the first version of this
section.** It first claimed occlusion had become "69 percent faster than
not having it", comparing the fresh ON leg against a BFS leg measured in a
DIFFERENT session at 1.089 ms. Re-measured same-session, that same BFS leg
is 0.671 ms and 0.670 ms on two runs. The 1.089 ms was not reproducible and
should never have carried a conclusion. Making the occlusion path 5.4x
cheaper is a different question from whether occlusion beats not culling at
all, and the second question needs its baseline measured beside it.

### Is occlusion culling worth it now? Only at long distances

Every pair below is same-session, 1920x1080, static camera, with repeats
where the answer was close. `culled` is sections resident minus sections
drawn, which is what the feature buys; the delta is what it costs.

| Scene | Occlusion ON | BFS | Delta | Resident | Drawn | Culled |
|---|---|---|---|---|---|---|
| ground rd 32 | 0.662 ms | 0.671 ms | **-1.3%** | 2,271 | 382 | 1,889 |
| ground rd 32 (repeat) | 0.730 ms | 0.670 ms | **+9.0%** | 2,267 | 375 | 1,892 |
| plains rd 32 | 0.989 ms | 0.863 ms | **+14.6%** | 3,291 | 1,623 | 1,668 |
| plains rd 32 (repeat) | 0.959 ms | 0.860 ms | **+11.5%** | 3,298 | 1,631 | 1,667 |
| plains rd 64 | 1.748 ms | 2.053 ms | **-14.9%** | 9,455 | 3,287 | 6,168 |
| ground rd 32 @1440p | 0.771 ms | 0.800 ms | **-3.6%** | 2,268 | 382 | 1,886 |

Positive means occlusion is slower. **The two `ground-rd32` rows are not a
result in either direction and must not be read as one:** those two ON legs
are the identical configuration, and the 0.068 ms between them is larger
than any occlusion-versus-bfsOnly difference ever measured at that scene.
One of them contains a single 20.5 ms stall frame worth 0.033 ms of its own
mean. Ground level at render distance 32 is a coin flip.

What is real is `plains-rd32` losing on two independent pairs and
`plains-rd64` winning by 0.305 ms. The GPU timestamps agree with the frame
times in both cases, independently putting the rd 64 saving at 0.327 ms.
The per-pass ledger, warmup rows excluded, shows why:

| Scene | Occlusion adds | Draw time it removes | Net GPU | Net frame |
|---|---|---|---|---|
| ground rd 32 | 0.176 ms | 0.263 ms | -0.087 | -0.009 |
| plains rd 32 | 0.243 ms | 0.137 ms | **+0.106** | +0.099 |
| plains rd 64 | 0.455 ms | 0.782 ms | **-0.327** | -0.305 |

The tax is near fixed and grows with region count and pixels, not with how
much is actually hidden; the payoff grows with the draw. When the terrain
draw is 0.39 ms there is nothing to buy. When it is 1.71 ms there is.

**A caveat on the frame-time column that also constrains the benchmark
itself.** GPU savings only reach frame time when there is enough GPU work
to be the bottleneck. The ratio of frame delta to GPU delta is 0.91 and
0.93 in the two plains scenes, where the GPU pass total is 0.69 to 1.71 ms,
and it is anywhere between 0.10 and 0.71 at ground rd 32, where the GPU
total is 0.30 ms against a 0.66 ms frame. Below roughly half a millisecond
of GPU work this harness is CPU limited and a GPU win is simply invisible
to it. That is the real reason ground rd 32 refuses to resolve.

Before the fix this same shape cost 3x at rd 32 rather than 10 percent, so
the fix turned a catastrophe into a trade. That is a real improvement and
it is still not a reason to turn the feature on for everyone.

**The cull rate relative to bfsOnly has never been measured**:
`gpuSectionsDrawn` reads 0 in every bfsOnly leg because occlusion
statistics are only recorded when occlusion resources exist. No percentage
of the form "occlusion removes N percent of the draw set" in any Meshelium
document is a measured comparison between the two modes. The cull rates
quoted below are occlusion's own drawn-versus-resident ratio, which is a
different and weaker statement.

### The full curve, and the one number that decides it

Twenty more legs, 1920x1080, two repeats per cell, every render size
verified against GLFW, medians rather than means. Sorted by the GPU ledger:
`saved` is the terrain draw time occlusion removes, `tax` is what its own
passes cost, and `net` is the difference. Negative net means occlusion is a
GPU win.

| Scene | saved | tax | net | cull | frame delta |
|---|---|---|---|---|---|
| ground rd 64 | 0.608 ms | 0.233 ms | **-0.375** | 86% | **-31.5%** |
| plains rd 64 | 0.782 ms | 0.459 ms | **-0.323** | 65% | **-16.7%** |
| ground rd 64 (rep) | 0.530 ms | 0.245 ms | **-0.285** | 89% | **-19.0%** |
| ground rd 32 | 0.263 ms | 0.174 ms | -0.090 | 83% | -2.4% (no result) |
| plains rd 48 | 0.398 ms | 0.333 ms | -0.065 | 58% | -5.3% |
| ground rd 8 | 0.044 ms | 0.104 ms | +0.060 | 59% | +12.9% |
| plains rd 32 | 0.136 ms | 0.242 ms | +0.106 | 51% | +12.8% |
| plains rd 16 | 0.031 ms | 0.150 ms | +0.119 | 46% | +12.9% |
| plains rd 24 | 0.070 ms | 0.191 ms | +0.121 | 47% | +19.3% |

**`saved > tax` predicts the frame result in 17 of 18 measured cells.** The
single exception is a `ground-rd32` repeat, which is the cell already known
to be CPU limited and whose own twin at the identical configuration
predicts and measures a win. As a cost model that is as good as this
harness can show.

**The headline is `ground-rd64`: occlusion wins by 19 to 31 percent.** That
is eye level at long distance, which is both what a real player using this
mod actually runs and the case every previous rd 64 measurement missed,
because they all used the bird's eye camera. It culls 86 to 89 percent
there against 65 percent from above. Long render distance is this mod's
entire selling point, so this is the case that matters most, and it is
occlusion's best.

**And a section count cannot decide this, which kills the rule this project
carried since 1.0.0.** `ground-rd64` has about 4,000 resident sections and
wins by 31 percent; `plains-rd32` has about 3,300 and loses by 13. Resident
counts overlap completely between winners and losers (winners from 2,252,
losers up to 3,298). What separates them is how much is actually hidden,
which a count cannot see. Cull rate alone does not separate them either:
`ground-rd8` culls 59 percent and still loses, because 59 percent of almost
nothing is almost nothing.

The one quantity that does separate cleanly, and which is readable while
occlusion is OFF, is the bfsOnly terrain draw cost. Every winner is above
0.719 ms and every loser below 0.580 ms, excluding the CPU-limited
`ground-rd32` cells. **A threshold near 0.65 ms separates all thirteen
decidable cells.** That is the trigger Auto should use to decide when it is
worth probing, with the probe itself making the actual decision.

### Camera pose moves occlusion more than distance or resolution do

The 1.1 Auto default keys on render distance, and this is the measurement
that says why that is a compromise rather than a solution. Two repeats per
cell, occlusion ON against the BFS feed, negative meaning occlusion is
faster:

| Scene | 1920x1080 | 2560x1440 |
|---|---|---|
| eye level, rd 32 | **-6.5 / -17.8%** | **-5.6 / -7.2%** |
| elevated, rd 32 | +14.5 / +14.6% | +20.7 / +20.2% |
| eye level, rd 64 | **-20.9 / -27.7%** | **-1.3 / -17.4%** |
| elevated, rd 48 | -4.3 / -3.1% | +2.1 / +0.9% |

**At render distance 32 the same world swings about 25 points on camera
pose alone.** Eye level looking along the terrain wins; 56 blocks up
looking down loses, because from up there almost nothing is hidden behind
anything. That is the whole mechanism in one table: occlusion pays exactly
in proportion to how much geometry is occluded, and distance is only a
proxy for that.

The owner reported the same split independently from real play before this
was measured, which is the strongest confirmation available that it is not
a harness artifact: "high cinematic shots cause lower fps with occlusion
while low ground realistic survival gameplay gets higher".

**Resolution moves the crossover UP, not down.** The box-pass tax still
scales with pixels, 0.245 to 0.299 ms at rd 32 for 78 percent more pixels,
because the read guard removed the per-fragment atomic but every fragment
still runs and still performs the guard load. So the tax is sub-linear in
pixels rather than flat. An earlier claim on this page's sibling documents
that higher resolutions would see SMALLER occlusion cost after the atomic
fix was wrong, and was corrected by this measurement.

**Why the default is 48 and not 32.** Averaged over the cells above, the
elevated loss at rd 32 is about 17 percent and the eye-level gain about 9,
so a default of 32 only pays for a player who almost never leaves the
ground. 48 is a small win at eye level and a wash from above: it is the
value that makes nobody meaningfully slower. Players who know how they play
have a slider, and the tooltip tells them which direction to move it.

### Phase B is half the cost of occlusion and it draws nothing

Broken out of the tax column above, phase B, the pass that draws sections
which became visible this frame, is **17 to 52 percent of everything
occlusion costs**, and it drew **zero sections in every measured window**.
At `ground-rd64` it is 49 to 52 percent: half the price of the feature, for
nothing.

It is neither dead code nor free. It fires during world load and when the
camera reveals new terrain, which is why it exists. But
`TerrainDrawer.recordPhaseDraws` issues one mesh-shader dispatch per
dispatched region and `terrain.task` then rejects every section slot one at
a time on a stamp comparison, so at rd 64 it is 158 regions of task-shader
work to draw nothing at all.

Removing that cost, by predicating the pass on a freshly-stamped counter or
by compacting to an indirect dispatch over only the new sections, moves
every cell by its own phase B figure:

| Scene | net today | net without phase B |
|---|---|---|
| plains rd 64 | -0.323 | **-0.557** |
| ground rd 64 | -0.285 | **-0.413** |
| plains rd 48 | -0.065 | **-0.209** |
| plains rd 32 | +0.106 | **+0.024** (a wash, from a clear loss) |
| plains rd 24 | +0.121 | +0.065 |
| plains rd 16 | +0.119 | +0.084 |
| ground rd 8 | +0.060 | +0.042 |

So it roughly triples the rd 48 win, adds 45 to 72 percent to the rd 64
wins, and turns the rd 32 loss into a wash. It does **not** rescue render
distance 16 and 24, which still lose, so an Auto mode is still required
afterwards. This is the next thing to build, before Auto, because it
changes the numbers Auto would be calibrated against.

**Scene caveat, unchanged and important:** these are open plains and open
ground, which is close to the worst case for occlusion, because almost
nothing hides behind anything. Caves, mountains and dense forest should
move the crossover much nearer. A player in a ravine is not the player this
table describes.

Why one line mattered that much: `gl_PrimitiveID` identifies the box, not
the pixel, so every fragment a box produces targets the same 32-bit word.
Same-address atomics cannot run in parallel, they queue. A box near the
camera covers most of the screen, so it was issuing on the order of a
million serialised read-modify-writes against one address, every frame.
The guard makes all but the first few of them a cache read that fails a
compare.

Correctness: the stamp writes were always idempotent (every writer in a
frame writes the identical value), so skipping a redundant one cannot
change the result. Verified by pixel parity against the BFS reference and
by the phase-B counter, which stays silent, meaning no section ever lost
its stamp. The full argument, including why the now non-atomic read is
safe despite being a formal data race, is in `box.frag` and in the design
doc.

**The default is still OFF** and the settings row is still hidden. Turning
them back on is a separate change that has to clear its gate first: a
1440p leg, a moving-camera leg, `plains-rd32`, and the row returning as
Auto rather than the plain toggle that caused this.

## Workgroup sweep

Render distance 32. Run to run noise is about 0.15 ms, so treat any smaller
delta as noise rather than as a result.

| Knobs | Meshelium ms | Verdict |
|---|---|---|
| **32 quads per mesh workgroup, 32 sections per task workgroup (defaults)** | **1.66** | **keep** |
| 64 quads per mesh workgroup | 2.01 | REGRESSION. Bigger mesh workgroups hurt RDNA4 even though the device advertises a preferred 256 invocations: measurement beats caps |
| 64 sections per task workgroup | 1.80 | noise to slightly worse |
| 128 sections per task workgroup | 1.81 | noise to slightly worse |
| 64 quads and 128 sections | 1.63 | noise |
| front to back ordering off | 1.62 | noise. Early z ordering is close to free either way in an occlusion culled scene, so it stays on: it helps worst cases and costs nothing |

**The shipped defaults stand.** Both sizes are injected into the shaders as
host side macros, and the knobs remain in the build for other vendors, whose
optima may well differ.

## The CPU optimisation pass, and what it removed

Development wave 12 (2026-08-10) was a measure first pass over the CPU half of
the frame. A stage attribution run at render distance 64 convicted two stages,
and the two knobs its numbers justified are now shipped defaults.

| Stage, rd 64, on a 6.53 ms baseline frame | Cost | Fate |
|---|---|---|
| vanilla `prepareChunkRenders`, building the draw list Meshelium's kill switch cancels anyway | 3.540 ms (54%) | **Skipped by default.** A jar wide consumer census proved the build feeds only the `renderGroup` calls the kill switch cancels; `-Dmeshelium.tune.skipVanillaPrep=false` is the escape hatch, and the one frame first throw hole stays counted and bench asserted at zero |
| Meshelium translucent recording, one draw per 64 quad slice | 1.935 ms | **Multi workgroup recording, default on for AMD devices** (measured pixel identical on RDNA4 twice; every other vendor stays off until measured there), which takes it to 0.618 ms |
| vanilla extract | 0.453 ms | untouched, load bearing |
| Meshelium opaque plus residency pump plus visibility BFS | about 0.10 ms | already cheap |

Result with the shipped defaults:

| Scene | Before | After | Change |
|---|---|---|---|
| plains rd 32 | 1.66 ms (602 fps), 1.73x vanilla | **1.10 ms (907 fps), 2.48x vanilla** | +51% |
| plains rd 64 | 7.24 ms (138 fps) | **1.73 ms (578 fps), p95 2.05 ms** | **+319%** |

Those two rows are the historical record of what this pass bought, on that
day's build and that day's bench code. The release sweep at the top of the page
is the current figure for both scenes. They are not the same measurement and
should not be compared row against row.

Pixel parity was re-verified with the new defaults live: 40 of 41 comparison
shots showed zero real differences, and 60 of 61 differed only in the animated
portal class (16 pixels, all portal purple on both sides, because the knobs
changed frame pacing so more animation frames sit between the two shots).

One knob the numbers did **not** justify: `meshelium.tune.cachedCull` stays
default OFF. Its bench win is an upper bound taken from a perfectly still
camera, where the memoization hits every frame; in real play it only helps
stationary frames. To judge it on your own play rather than on this table, run
with `-Dmeshelium.tune.cachedCull=true -Dmeshelium.debugStats=true` and read
the `cachedCull=hits/misses` breadcrumb, then flip the config if hits dominate.

## Traps this page had to survive

Listed because they are the reason to trust the rest, and because they will
bite anyone benchmarking Minecraft.

- **The 30 fps trap.** Vanilla caps an unfocused window to 30 fps after one
  minute (the `inactivityFpsLimit` AFK default). The very first bench run
  measured both legs at exactly 33.4 ms, which was the cap and not the
  renderer: a wrong baseline that happened to look plausible, and it is written
  down here so the next reader distrusts convenient baselines. The bench now
  runs minimized only, with vsync off and the framerate uncapped.
- **Worldgen contention.** A NORMAL world that is still generating chunks
  steals CPU and paces the client. The bench waits for residency to go quiet,
  no uploads for two seconds, before it measures anything, with a worldgen
  sized budget.
- **A busy GPU.** Every number here was taken with nothing else running.
- **Comparing across sessions. This one is the most dangerous on the list
  because nothing about it looks wrong.** The identical `ground-rd32`
  bfsOnly leg measured 1.089 ms in one session and 0.671 and 0.670 ms in
  another. On 2026-08-12 an occlusion result was written up internally as
  "69 percent faster than not culling" purely because a fresh number was
  compared against that stale one; re-measured properly the same scene was a
  coin flip. The same shift shows in `plains-rd32` bfsOnly, 1.436 ms on
  11 Aug against 0.860 ms on 12 Aug, with the vanilla control in those legs
  barely moving. **Rule: a comparison is only valid if BOTH legs were
  measured in the same session, and an archived number is context, never a
  baseline.** The cause of the shift is still unexplained; same-session
  pairing works around it rather than solving it.
- **The noise floor is bigger than most of the effects being chased, and
  the first version of this entry understated it.** It claimed the harness
  repeats "to 0.1 percent" within a session, generalising from one lucky
  pair. Two `ground-rd32` occlusion-ON legs of the identical
  resolution-verified configuration measured 0.662 and 0.730 ms, which is
  10 percent, and that spread is LARGER than any occlusion-versus-bfsOnly
  difference ever measured at that scene. One of those two legs contains a
  single 20.5 ms stall frame that moved its own mean by 0.033 ms. So: three
  repeats per cell, report the median and the 5 percent trimmed mean beside
  the mean, and treat any difference smaller than the observed spread of the
  legs themselves as no result at all. Measure the spread before believing
  the delta.
- **The resolution the run asked for is not the resolution it got.** The
  854x480 default window is documented above as the single biggest
  methodological error this project made; the second layer of it lasted
  months longer. `-Pmeshelium.res` fixed the REQUEST side while the report
  stayed silent about the RESULT, so a leg that was silently clamped, by a
  window manager refusing a size larger than the desktop for instance, would
  have looked perfectly comparable. Every bench report now records the real
  framebuffer width, height and megapixels, and the gate runs archive the
  scene screenshot next to the JSON as independent evidence of the size.

## Cross vendor status

| Vendor | Status |
|---|---|
| AMD RDNA4 (RX 9070 XT) | **Measured.** Every number on this page came from this card |
| AMD RDNA2 and RDNA3, Steam Deck | Untested. Expected to work on the same driver stack, no numbers |
| NVIDIA Turing and newer | **UNMEASURED.** No such hardware on the developer's desk |
| Intel Arc and Xe | **UNMEASURED.** No such hardware on the developer's desk |

If a number is not on this page, it does not exist. Treat any performance claim
made anywhere else as absent, not implied.

Two shipped defaults are gated on exactly this gap. Multi workgroup translucent
recording (`meshelium.translucentMultiWG`) is pixel correct on RDNA4 by
experiment, so it defaults on for AMD devices and off for every other vendor
until someone measures it there. The mesh and task workgroup sizes stay
retunable macros for the same reason.

If you have NVIDIA or Intel hardware and a spare afternoon, running the bench
above is the single most valuable thing anyone could contribute to this
project.

## Fully enclosed scenes, where Meshelium loses

Added 2026-08-13 with the `cave-rdN` scenes. Every other scene on this page
looks at open terrain, and "faster at every distance we measured" was true
of exactly that. It is not true everywhere, and the boundary is worth
publishing rather than discovering.

`cave-rd64` pins the spectator camera underground at y=30 and carves a 9x7x9
chamber out of real generated stone after worldgen settles, so solid rock
surrounds it in every direction. Six sections get drawn. Both renderers are
doing essentially nothing, and what remains is fixed per-frame cost.

| leg | Meshelium mean | vanilla mean | vanilla / Meshelium |
|---|---|---|---|
| occlusion on (AUTO at rd 64) | 0.555 ms | 0.470 ms | **0.85** |
| occlusion off (`bfsOnly`) | 0.467 ms | 0.405 ms | **0.87** |

Vanilla is roughly 15 to 18 percent faster here. Read only the ratios: the
two rows are separate harness sessions and vanilla's own number moved 16
percent between them (0.470 against 0.405) for identical work, which is the
same-session rule this page keeps relearning. Within each run the ratio is
stable, and both runs agree.

Three things follow.

**The result is real but not practical.** Both renderers are above 1,800 FPS.
Nobody is affected by losing 0.085 ms at 1,800 FPS, and no monitor can show
it.

**Meshelium's advantage requires visible geometry.** Its win comes from
drawing a lot of terrain cheaply. Where there is nothing to draw, there is
nothing to win, and the fixed cost of the mesh-shader path is what is left
on the clock. Vanilla's own visibility graph already solves the sealed-room
case for free.

**Occlusion culling costs about 0.09 ms when there is nothing to occlude,**
and turning it off recovers roughly half the deficit without closing it. The
remainder is the fixed frame cost, not the occlusion passes. This does not
argue for changing the AUTO threshold: AUTO keys on render distance, the
scene here is the degenerate end of the curve, and at rd 64 in open terrain
occlusion still pays. It does argue that a future visibility-aware AUTO has
something real to key on.

## Spin the camera, or the bench measures a third of the world

Owner's observation, 2026-08-13, and it invalidates every memory number this
page took before it: Minecraft only keeps sections resident that it has had
reason to render, so a bench with a FIXED camera never forces the world behind
the camera to load. `meshelium.bench.spin` exists and every published scene
leaves it at 0.

Same scene, same distance, spin 0 against spin 2 degrees per tick:

| | static camera | spinning |
|---|---|---|
| resident sections | 9,464 | **26,990** |
| terrain arena | 529 MB | **1650 MB** |
| arena blocks | 1 (grown) | **1 grown + 3 appended** |
| total GPU | 2651 MB | 5723 MB |
| drops | 0 | 0 |

Nearly three times the terrain. Any memory conclusion drawn from a static
camera understates a real session by about that much, which is why the
harness never got near the 4 GB the owner reached in play while this page was
quoting 600 MB.

It is also the first test of the block machinery at scale, and it is clean:
two grow-and-copies capped at the 512 MB block size, then three appends that
copy nothing, zero drops, no guard trip and no render-distance backoff needed.
Before the block-size change the same climb took five copies rising to
1944 MB, with draw-path spikes of 180 ms and 383 ms in the owner's log.

Frame-rate rows on this page stay static-camera, because that is what makes
them repeatable and comparable with every earlier row. Memory rows should use
spin.

## Spec-minimum simulation, the closest thing to cross-vendor coverage here

Added 2026-08-13. NVIDIA and Intel are not on this desk, so the multi-buffer
work could only ever be verified against one vendor's real limits. The
`VK_LAYER_KHRONOS_profiles` layer closes part of that gap by forcing another
device's limits onto this card.

Run with `VP_LUNARG_minimum_requirements_1_2` and
`SIMULATE_PROPERTIES_BIT` (properties only, so mesh shaders stay available
and it is the LIMITS under test), the RX 9070 XT reports:

| limit | real | simulated |
|---|---|---|
| maxStorageBufferRange | 4095 MB | **128 MB** |
| maxPerStageDescriptorStorageBuffers | unlimited | **4** |
| maxDescriptorSetStorageBuffers | unlimited | **24** |
| maxMemoryAllocationSize | 2048 MB | 1024 MB |

Meshelium degraded exactly as designed: blocks clamped from the preferred
512 MB to **128 MB**, and the block count fell to **2**, driven by the
per-stage descriptor limit of 4 minus the 2 the mesh stage already uses. Total
addressable dropped from 8192 MB to 256 MB.

The result that matters: **opaque terrain was pixel-identical to vanilla**,
zero differing pixels above a delta of 4, rendering through two 128 MB blocks
on a device pretending to be the weakest thing the spec allows. The whole
clamping chain - probe, block size, block count, descriptor array, shader
switch - works at the bottom of the range as well as the top.

Two honest limits on what this proves. It simulates LIMITS, not driver
behaviour, so it says nothing about whether NVIDIA's or Intel's compilers
accept the module. And at high render distance the task stage declares 5
storage buffers where this profile allows 4, which is an inherited exposure
predating the split and would need addressing before claiming spec-minimum
support outright.

## What this page does not cover

**Visual precision.** The 16 byte packed vertex carries a known quantisation
trade that shows at extreme distance and is visible only through zoom mods. It
is a design decision rather than a measurement, so it is explained in
[`TECHNICAL.md`](TECHNICAL.md); in bench output, the rd 48 and rd 64
screenshots are the first place to look for it.

**Retained terrain.** The retained terrain machinery was retired from the user
interface at 1.0.0 and defaults off, so no default bench run exercises it any
more. Measuring it means arming `-Dmeshelium.retainTerrain=true` first, the way
the gametest harness leg does. The player facing answer for a server's view
distance is Bobby, and [`TECHNICAL.md`](TECHNICAL.md) explains why the render
layer cannot solve it.

**Anything rotation driven**, for the static camera reason given under
[Method](#method).

---

## Greedy meshing, measured (2026-08-14)

> **Every quad-count figure in this section and the next was corrected on
> 2026-08-15 and the originals were WRONG.** They came from a model of the
> sweep living a few hundred lines from the real one, and the two had
> silently diverged: the model extended runs without bound while the mesher
> clamps them to powers of two, so a run of 15 counted as one rectangle when
> the encoder can only express 8 + 4 + 2 + 1. A separate bug let the model
> merge randomly rotated block variants that the mesher must refuse. Both
> errors flatter the merge, and both are worst exactly where the merge is
> best, so the shader-lighting projection was inflated more than the baseline
> it was compared against. The probe now reports the real mesher's own output
> and says so when its model disagrees. The corrected table is
> [below](#the-corrected-quad-counts-2026-08-15); the original is kept here
> because a page that quietly edits its own numbers is not evidence of
> anything.

`GreedyMeshProbe` runs a real greedy rectangle merge over every decoded
section and throws the merge away. `-Dmeshelium.probe.greedy=true`. It sits
between `VanillaMeshDecoder` and `SectionMeshEncoder`, exactly where a real
merge pass would go, so it measures the thing that would be built rather than
a model of it.

Render distance 64, 10,000 sections, 9,147,614 quads (**superseded, see the
correction above**):

| | quads after | reduction | eligible |
|---|---|---|---|
| As the renderer stands | 8,688,507 | **5.0%** | 940,042 (10%) |
| With lighting in the shader | 5,903,928 | **35.5%** | 6,013,714 (66%) |

Seed 7 gives 5.5% for the first row, so the low number is not a property of
seed 4242's snow and ice.

**Greedy meshing on its own is not worth building.** Five percent of quads,
against an opaque pass that is 0.687 ms of a 1.842 ms frame, with fill rate
unchanged because the same pixels are covered either way.

**Why, and it is not what the design work predicted.** The reasoning had
settled on vanilla's four position-hashed rotation variants for grass, dirt,
sand and stone capping natural terrain near a third. That is not the binding
constraint. **5,876,130 quads, 64 percent of all terrain, are disqualified
because their four corners disagree**: vanilla bakes smooth lighting and
ambient occlusion per vertex, so most faces are bilinear ramps and two ramps
do not tile into one larger ramp. An adversarial review had argued AO was a
per-edge cost rather than a blocker. The measurement refutes that.

The merge algorithm is fine. It collapses what it is allowed to touch by 49
percent, and by 54 percent in the shader-lighting case. There is simply
almost nothing it is allowed to touch.

**The consequence.** Moving lighting and biome tint off the vertex and into
the shader is worth seven times what greedy meshing is worth, and greedy
meshing is only worth anything as its second half. It also cuts arena size by
the same 35.5 percent, roughly 390 MB of a 1093 MB working set at render
distance 120, which is the constraint that has actually produced bug reports.

**What these numbers do not claim.** Not 35.5 percent more frames. Merging
covers the same pixels with fewer primitives, so it takes work off vertex and
primitive processing and none off fill rate. Single digits to low teens
percent, plus the memory, is the honest range.

### Smooth Lighting off, measured the same way

`-Dmeshelium.bench.flatLighting=true` turns vanilla's Smooth Lighting off, so
vanilla itself takes `prepareQuadFlat` and gives every quad one colour and one
light coordinate instead of four corners. Same scene, same 10,000 sections:

| Smooth Lighting | quads after | reduction | corners disagree |
|---|---|---|---|
| On, the default | 8,688,507 | 5.0% | 5,876,130 |
| **Off** | **6,791,472** | **25.6%** | **0** |
| Lighting in the shader, predicted | 5,901,981 | 35.4% | n/a |

The zero is the point. It confirms the mechanism exactly: per-vertex ambient
occlusion is the entire reason the merge fails, and removing it removes the
whole 5.9 million quad disqualification.

**This reverses the conclusion above for one population.** Greedy meshing is
not worth building for a player with Smooth Lighting on. It is worth 25.6
percent for a player with it off, today, with no shader work, no light volume
and no AO reproduction.

The remaining gap to 35.4 percent is also explained: with flat lighting each
quad still carries its own colour and light, so two adjacent quads at
different light levels still refuse to merge. Striking colour and light from
the key removes that last barrier, and only shader-side lighting can do it.

One artifact to read past: "not a unit face" jumps from 329,118 to 1,134,128
between the two rows. That is not a change in the geometry. The strict pass
tests uniformity before unit-ness, so with Smooth Lighting on most non-unit
quads were already counted in the non-uniform bucket and never reached the
unit test.

### The corrected quad counts (2026-08-15)

Re-measured after the probe was made to clamp runs the way the encoder does,
and after the mesher gained a UV-orientation key. Both changes make the merge
look worse and both are right. Same scene as above: `plains-rd64`, 10,000
sections, seed 4242.

The probe now runs the **real `GreedyMesher`** over each section and reports
that, rather than a second implementation of the sweep. Its model is kept
alongside only for the breakdown the mesher cannot give, and the report line
prints the disagreement when the two differ.

| Smooth Lighting | Published 2026-08-14 | Actual | Model, for comparison |
|---|---|---|---|
| On, the default | 5.0% | **3.4%** | 4.3% |
| Off | 25.6% | **17.9%** | 21.2% |
| Lighting in the shader, predicted | 35.4% | **29.6%** | (this row is still the model) |

Three separate things were wrong:

- **No run clamp.** The model extended a run of 15 into one rectangle; the
  encoder can only express 8 + 4 + 2 + 1. Worst where the merge is best.
- **No UV-orientation key.** Vanilla hashes block position into a model
  rotation for grass, dirt, sand and stone, which rotates the sprite's UVs
  while leaving its atlas RECTANGLE identical. The rectangle was all the
  model compared, so it counted merges between differently rotated faces. The
  mesher now refuses them, which costs 0.9 points with Smooth Lighting on and
  3.3 points with it off. That refusal is also a **bug fix**, not a
  regression: before it, those merges were being made and drawn wrong.
- **The shader-lighting row still has neither correction fully applied.** It
  is clamped now but its key still has no orientation field, so 29.6% is an
  upper bound and the real figure is likely 3 to 4 points lower.

The old prediction that shader-side lighting was "worth seven times what
greedy meshing is worth" was arithmetic on two numbers that were both wrong.
Against a corrected 3.4% baseline the corrected ceiling is about 26 points of
headroom, not 30.

### What it is worth in FRAME TIME (2026-08-15)

Quads are not frames, and the mod is judged on frames. Measured with the
current mesher, 600 frames per run, `-Dmeshelium.greedyMeshing` flipped
between runs, at two working points: the owner's own (render distance 64 at
1440p) and the comparable published row (render distance 32 at 1080p).

**Render distance 64, 2560x1440:**

| Smooth Lighting | Greedy | Frame mean | Frame median | Opaque GPU pass | Translucent pass |
|---|---|---|---|---|---|
| On, the default | off | 2.791 ms | 2.734 ms | 1.155 ms | 0.579 ms |
| On, the default | on | 2.709 ms (-2.9%) | 2.637 ms (-3.5%) | 1.086 ms (-6.0%) | 0.567 ms |
| **Off** | off | 2.735 ms | 2.695 ms | 1.128 ms | 0.566 ms |
| **Off** | **on** | **2.503 ms (-8.5%)** | **2.481 ms (-7.9%)** | **0.926 ms (-17.9%)** | 0.568 ms |

**Render distance 32, 1920x1080:**

| Smooth Lighting | Greedy | Frame mean | Frame median | Opaque GPU pass | Translucent pass |
|---|---|---|---|---|---|
| On, the default | off | 1.280 ms | 1.226 ms | 0.776 ms | 0.197 ms |
| On, the default | on | 1.252 ms (-2.2%) | 1.205 ms (-1.7%) | 0.752 ms (-3.1%) | 0.197 ms |
| **Off** | off | 1.277 ms | 1.230 ms | 0.779 ms | 0.195 ms |
| **Off** | **on** | **1.159 ms (-9.2%)** | **1.109 ms (-9.8%)** | **0.647 ms (-16.9%)** | 0.197 ms |

The translucent pass is the control. The merge excludes translucent geometry
by construction, so that column should not move, and it does not: 0.195 to
0.197 ms across all four rd 32 runs, 0.566 to 0.579 ms across all four rd 64
runs. Drift large enough to fake an 18 percent move in the opaque pass would
have moved it too.

**The opaque pass tracks the quad count almost exactly.** 17.9 percent fewer
quads with Smooth Lighting off, 17.9 percent off the pass at rd 64 and 16.9
percent at rd 32. That is a stronger result than the earlier 0.785 ratio
suggested, and it means the pass is close to purely primitive-bound here.

**What it is worth to a player**, which is the only question that matters:

- **Smooth Lighting on, the default: 2 to 3 percent.** Real, repeatable,
  and small.
- **Smooth Lighting off: 8 to 10 percent.**

And the caveat that must travel with every one of these percentages: these
frames are 1.2 to 2.8 ms because the bench camera is static in an empty
plains world with no entities, no GUI and no particles. What the merge
actually removes is an absolute **0.08 ms (default) or 0.23 ms (Smooth
Lighting off) per frame at rd 64 / 1440p**. In a real 10 ms frame those are
0.8 and 2.3 percent, not 3 and 9.

#### One outlier, reported rather than dropped

The first rd 64 / 1440p flat-lighting run with the merge on recorded a single
220.9 ms frame, which dragged its mean to 2.912 ms while its median stayed at
2.475 ms. A repeat under identical conditions came back clean (mean 2.503 ms,
max 4.181 ms), so the table uses the repeat. The spike did not reproduce and
is not attributed to the merge, but a mean that a single frame can move by 17
percent is why the median column exists in these tables.

### The 2026-08-16 review session, in numbers

A model-driven audit and a performance scout ran over everything above, and
the session's measurements belong on this page like any others.

**Phase B's skip ceiling re-validated, then the skip lost anyway.** Skipping
phase B outright (the deliberately incorrect measurement switch) is worth
0.163 ms at rd 64 / 1440p, 7.3 percent of the frame, same-session pair. The
correct conditional-rendering skip was then built, collapsed phase B from
0.239 to 0.008 ms, and LOST on the frame: the driver services conditional
rendering with a periodic 8 to 11 ms stall every 16 to 21 frames, p99 3.6 to
10.6 ms. Default off; the whole story is in OCCLUSION-FILLRATE-DESIGN.md.

**The rotation tax is not the frustum walk.** One spin run with the stage
timers settled a standing suspicion: applyFrustum fires 26 times in 600
rotating frames at 0.517 ms each, an amortized 0.022 ms per frame. The
720-to-550 fps drop while turning lives in vanilla's extract and chunk
rebuild churn plus legitimate GPU work, so the planned decoupled-translucent
ordering fix is dead: it would have removed a fiftieth of the cost it was
aimed at. One bench run, one 200-line feature not built.

**The arena tail is real and persistent.** With debug stats on, an rd 64
session holds tail at 492 to 496 MiB with emptyTopBlocks 0/2 throughout:
half a gigabyte of committed VRAM inside the top 512 MiB block that nothing
has ever touched, recoverable only by a shrink-copy of the block's used
extent, not by releasing whole blocks (there are never empty ones). That is
the measured case for the arena trim, which is specced but deliberately not
built in the same session that measured it.

### The idle memory trim, measured (2026-08-17)

The tail measurement above became a feature the same night. After 30 seconds
without meaningful arena traffic (staged volume, not mere activity - a live
server random ticks blocks forever, so a policy that waited for perfect
silence would never fire outside a void superflat, which is how the first
draft failed its first real bench), the top block is shrink-copied to its
extent and the remainder goes back to the driver.

| rd 64 / 1440p session | committed | in use | occupancy |
|---|---|---|---|
| Before the trim | 1024 MiB | 528 MiB | 52% |
| After | **536 MiB** | 528 MiB | **98.5%** |

488 MiB returned, and the frame did not notice: median 2.236 ms against the
session's 2.217 to 2.256 spread, worst frame 3.441 ms - the cleanest tail of
the day, because the one copy the trim performs is bounded by the extent
(about 20 MiB here), not the block size. The superflat torture world trims
256 to 16 MiB. Regrowth through a trimmed arena is the ordinary growth
ladder and the torture leg drives a rebuild storm through it without a drop.

Default ON as the Advanced row "Idle Memory Trim"; off restores 1.2.0
behavior exactly.

### Why shader-side lighting was NOT built

The corrected numbers above were the input to a design study for moving
lighting off the vertex and into the fragment shader, which is what would let
faces with different light merge. Three independent adversarial reviews
refuted it, and the arithmetic is the reason:

- The prize is the **residue**: about 26 points of quad reduction between the
  3.4 percent baseline and the 29.6 percent ceiling, and the ceiling is
  itself an overestimate.
- The cost is per pixel and scales with resolution and overdraw, while the
  prize is per quad and does not. Break-even lands near **17 points at 1080p,
  31 at 1440p, and 69 at 4K**.
- So at 1080p it is marginal, and **at 1440p and above it is a net loss** for
  exactly the players with the biggest GPUs.

There is a second, subtler reason it cannot simply be ported. Vanilla
interpolates the **product** of colour and the lightmap sample, computed per
vertex. A shader that interpolates the light COORDINATE and samples once is a
different function, and it diverges visibly across a light gradient. Doing it
correctly means three lightmap fetches per merged-quad pixel, which triples
the texture cost the estimate above was built on.

The idea is not dead in general, but the per-pixel lattice version is, and it
was killed by arithmetic before anything was built.

### The affine merge, which was built (2026-08-15)

One idea survived the review, and it is free. A merged rectangle is one
four-vertex quad, so the hardware interpolates its corners linearly across
each triangle. That reproduces the original per-corner field exactly when the
field is **affine** over the merged lattice, `v(i,j) == v00 + i*du + j*dv`, in
exact integer arithmetic, on all five channels. Identical corners, which is
what the merge used to demand, is just the constant case of that.

An adversarial reviewer predicted this would collect very little, because
vanilla truncates when it scales a colour by a face's shade factor, so a
genuine ambient-occlusion ramp lands on unevenly spaced integers and is not
exactly affine. That prediction was half right. It collects **2.5 points**,
not the 20 the single-valued-lattice ceiling suggested, and what it collects
is almost entirely the face whose shading is constant ALONG the run and varies
only across it.

| plains-rd64, 10,000 sections | Smooth Lighting on | Smooth Lighting off |
|---|---|---|
| Before | 3.4% | 18.0% |
| **After** | **5.9%** | 18.0% |
| Single-valued lattice, needs per-pixel work | 25.3% | 18.0% |
| Full shader lighting | 29.5% | 29.5% |

Nothing changes with Smooth Lighting off, and that is the expected answer
rather than a disappointment: vanilla's flat path already gives every face one
colour, so every mergeable face was already constant.

The shipped mesher and `AffineMergeProbe`, two separate implementations, agree
to the quad at 8,652,076. That cross-check is the reason the number is
trusted, after a previous probe turned out to have drifted from the mesher it
was modelling.

**Frame time.** At `plains-rd32` / 1080p, where the translucent control holds
to 0.196 to 0.197 ms across all four runs, the opaque GPU pass moves from
**-3.1 percent to -6.5 percent** and the frame median from -1.7 to -4.7
percent. It tracks the quad count almost exactly, again.

At `plains-rd64` / 1440p an earlier edition of this section said it does not
show, and **that conclusion was wrong in exactly the way this page's own
same-session rule predicts**. The comparison was against the -6.0 percent
figure measured before the change, which came from a DIFFERENT session, and
the off-baselines had drifted 1.155 to 1.117 ms between those sessions, which
is three times the effect being sought. Re-measured 2026-08-16 with seven
runs pooled and MEDIANS used so the occasional one-frame spike cannot poison
a mean:

| rd 64, 1440p, Smooth Lighting on | Opaque pass median | n | sd |
|---|---|---|---|
| Greedy meshing off | 1.117 ms | 4 | 0.005 |
| Greedy meshing on | **1.050 ms (-5.9%)** | 3 | 0.006 |

The quad reduction at this distance is 5.9 percent and the pass reduction is
5.9 percent: **the merge tracks the quad count at rd 64 exactly as it does
everywhere else**, and the translucent control holds at 0.559 to 0.564 ms
across all seven runs. There is no anomaly, and the weaker justification the
earlier edition offered is withdrawn: the merge pays at both working points,
proportionally to the quads it removes.

#### What the merge costs the build threads

Every number above is the win. The cost lives somewhere no frame-time
benchmark can see it: the merge runs once per section build, on a ForkJoin
worker, off the frame path entirely. Slower section builds are slower pop-in
when a player flies or breaks blocks, so it is now reported in the debug stats
line as `greedyMerge[...]`.

At render distance 64 it is **268 microseconds per section**. Over the ~10,000
sections of a full load that is about 2.7 CPU-seconds, spread across the build
pool, so roughly a third of a second of extra wall time on a load that takes
tens of seconds. On a single block placement it is 268 microseconds on one
worker, which is nothing.

The first reading was **430** microseconds. The affine test was materialising
the whole (W+1)x(H+1) lattice and re-checking all of it at every step of the
sweep's probing, and that turned out to be unnecessary: the seed cell alone
pins the plane, so each cell can be tested against it exactly once. Removing
the lattice took it to 268 and made the code shorter. Equivalence was measured
rather than argued, since `AffineMergeProbe` still builds the lattice the old
way: over 10,000 sections the two agree to the quad, 8,661,402 both.

#### One more spike, and what the series says about it

Two of the rd 64 / 1440p runs with the merge on recorded single frames of
220.9 ms and 78.0 ms. Locating the second one in the raw series puts it at
frame 17 of 600, right after warmup, and **no Meshelium stage timer accounts
for it**: the largest stage anywhere near it is 1.36 ms. The vanilla leg of
the same run has its own 9.5 ms spike at frame 105. Repeats of both pairs came
back clean, with maxima of 4.18 and 4.27 ms. So the spikes are sporadic, they
are not inside any measured stage, and they are not attributed to the merge.
They are recorded here because a mean that one frame can move by 17 percent is
why every table above carries a median column.

### The 1.4.0 dev cycle, night one (2026-08-17)

Same rig, same session for every pair below. The harness gained a fix this
night that matters to every future number: 26.2 renamed all gamerules, the
old camelCase freeze commands had been failing silently since the toolchain
moved, and every earlier bench therefore ran with daylight advancing and
random ticks at 3. Same-session pairs stayed fair because both legs drifted
identically; the freeze works now.

#### The phase-B CPU skip, measured: attempt 3 wins

Attempt 2's post-mortem said the remaining route to the 8-to-12-percent
phase-B prize was a CPU-side decision. Built this night: when the camera,
frustum, scene matrices and raster extent are bit-identical, the residency
epoch has not moved, the commit backlog is empty, and the lagged readback
shows zero phase-B draws since the last input change, pass 4's recording is
elided entirely. `plains-rd64` / 2560x1440, 600 frames, occlusion armed both
legs, same build:

| | frame p50 | frame p99 | worst | phase B GPU p50 | skip rate |
|---|---|---|---|---|---|
| skip off | 2.200 ms | 3.362 ms | 4.367 ms | 0.238 ms | 0% |
| skip on | **1.957 ms (-11.0%)** | 3.566 ms | 4.076 ms | **0.000 ms** | 96.1% |

The tail gate attempt 2 failed by a factor of eight is passed here: p99
moves 6 percent, the worst frame improves. The win exceeds the 0.163 ms
GPU-only ceiling because the CPU also stops recording ~158 push-constant
and draw pairs plus a render pass per skipped frame. The quiet detector
stayed quiet through 2,965 read-back stats frames, which is the skip being
exact rather than suppressive: nothing it skipped was ever owed.

The skip-rate column is the honesty condition for a static-only feature: a
pinned camera is its best case. Spinning and mid-flight frames disarm it by
key mismatch, so the blended real-play win is the static share of play
times the number above. At render distances below the occlusion Auto
crossover the occlusion path is off and the skip does not exist.

#### The lightmap fetch group, killed for three microseconds

The rank-4 scout idea (dedup the mesh stage's four lightmap fetches for
uniform-light quads) got its kill-first run: `MESHELIUM_LIGHT_STUB` stubs
every fetch to white, so one parity-breaking A/B pair bounds the entire
cost group. At `plains-rd64` / 2560x1440 the stubbed opaque pass is 0.845
versus 0.844 ms unstubbed: the whole group is worth about **0.003 ms**
against a 0.05 ms kill line. The dedup is dead, and the premul-MVP idea
sequenced behind it dies of the same arithmetic. Sixteen texture units
reading a 16x16 texture out of L0 cost nothing to begin with.

#### The flat-water census: the merge's premise is real

The probe now measures the translucent layer the merge refuses to touch.
Over the new deep-ocean scene (seed 4242, camera resolved from the biome
source), final probe reports:

| scene | flat cells | -> rectangles | of translucent | of ALL quads | plane-pure sections |
|---|---|---|---|---|---|
| ocean-rd32 | 1,758,689 | 27,667 | **-95.6%** | -15.2% | 2,822 of 4,761 wet |
| ocean-rd64 | 2,275,067 | 61,480 | **-93.1%** | -14.0% | 3,677 of 7,117 wet |

99.95 percent of flat cells pass the uniform-corner test, so the merge
machinery that ships today reaches nearly all of it. The 50-percent kill
line is beaten by 43 points; the sort-free flat-water merge graduates from
hunch to funded project. Its frame-time multiplicand (the measured ocean
translucent pass) still needs the settled bench legs, which the gamerule
fix above unblocks: kelp and seagrass random-ticking at speed 3 kept ocean
worldgen churning past the settle budget, which is how the first four
ocean legs died and is this project's third demonstration that a live
world never stops ticking.

**...and the multiplicand, measured (the second batch).** With the
freezes working, all four ocean legs settled (deep-ocean camera at
x=1088 z=1088, resolved from the biome source and recorded in the knob
block). The ocean translucent pass: **0.119 ms** at rd 32 / 1080p,
0.132 ms at rd 32 / 1440p, **0.316 ms** at rd 64 / 1440p. The 1.78x
pixel step moves the pass 11 percent, so it is primitive-bound like
every other pass and the merge's ceiling really is the pass times the
93-percent reachable share. Verdict against the scout's own kill lines:
the rd 32 frame case is DEAD (0.119 is under the 0.3 ms line; a
sub-0.11 ms win cannot clear the noise floor), the rd 64 frame case is
borderline-credible (~0.25-0.29 ms ceiling, greedy-meshing-sized), and
the memory case stands on its own: roughly 2.2M merged-away quads at
rd 64 is ~140 MB of arena prefix plus the same again in the CPU-side
resident copy. Deferred, not killed: the mesher/resort work is days
deep, and this cycle has bigger confirmed wins in hand.

#### The second batch: the skip at rd 32, and what spinning actually measures

With occlusion FORCED at rd 32 / 1080p (AUTO leaves it off there), the
static pair reads mean 1.127 to **1.038 ms (-7.9%)**, skip rate 97.7% -
the design doc's 8-percent prediction, delivered. The spin pairs then
answered a different question than the one they were sent to ask. The
bench spin rotates the player per TICK, so at bench frame rates ~24 of
25 frames share one camera pose bit-for-bit, and the skip stayed 96-97
percent engaged while spinning: rd 32 spin p50 1.701 to 1.599 ms, rd 64
spin p50 7.545 to **6.972 ms**, tails no worse (the rd 64 spin-skip leg
recorded one sporadic 696 ms frame in the class already documented
above: no stage timer accounts for it, the off leg has its own 72 ms
spike, medians are the row). Real mouse-look applies deltas per FRAME,
not per tick, so a player actively turning disarms the skip far more
than the spin legs did; the honest statement is that the skip pays
during every stretch where the pose holds for ~6 frames, which is
standing, building, menus, AFK, and the gaps between mouse movements,
and costs a key-compare-and-a-lock on the frames where it cannot.

**Default: ON**, as of the night it was built (the multiWG rule:
measured winners are defaults, `-Dmeshelium.occlusion.phaseBCpuSkip=false`
is the escape hatch). What was NOT directly measured tonight is a
100-percent-disarm workload (per-frame pose change); the arithmetic
bound on that path is one raw-bits key compare plus one uncontended
lock per frame, and the ~10,000 disarm frames inside tonight's spin
legs moved no tail. If a future session builds a per-frame-look leg,
run it before trusting this paragraph further.

#### The aligned frame (night two, 2026-08-18): 100 percent attribution

The attribution wave (row boundary moved to render HEAD; new brackets
compileUpload / encoderSubmit / levelRender / renderFrame; per-frame
sectionCompiles) closed the frame completely. plains-rd64 / 1440p, same
session, gamerule freezes working:

| p50 ms | static | spin 3°/tick |
|---|---|---|
| frame | 1.966 | 6.807 |
| renderFrame (whole) | 1.914 | 6.644 |
| levelRender | 0.771 | 4.173 |
| encoderSubmit (GPU-pace wait) | **0.702** | 0.122 |
| extract | 0.284 | 1.833 |
| applyFrustum | absent | **0.637** |
| mesheliumOpaque | 0.072 | **2.831** |
| mesheliumTranslucent | 0.623 | 0.870 |
| compileUpload | 0.044 | 0.115 (p99 1.30) |
| sectionCompiles/leg | **0** | **16,321** (max 122/frame) |

Verdicts, each now same-session data rather than inference:

1. **Static frames are GPU-paced.** encoderSubmit's semaphore wait is
   0.70 ms of a 1.97 ms frame and tick+input is 0.05 ms; nothing is
   hidden, and static-frame CPU work is confirmed free (the pace
   absorbs it). Optimize static frames on the GPU side only.
2. **The draw-snapshot conviction stands with aligned rows:**
   mesheliumOpaque 0.072 to 2.831 ms p50 while turning - 40 percent of
   the turning frame, in our code, with the fix designed in
   DRAW-SNAPSHOT-INCREMENTAL.md. Night-three item one.
3. **GC is convicted for the monster class:** 176 pauses in one
   GC-logged spin leg (default G1, unpinned heap), p50 11 ms, eight
   over 50 ms, max 777 ms - and that leg's 872 ms worst frame matches.
   The snapshot rebuild's ~280 MB/s garbage stream is a prime feeder:
   the incremental snapshot attacks median and tail at once. A
   heap-pinning / collector recommendation for players waits until our
   own garbage is fixed first (do not tune the collector around a leak
   we own).
4. **The build storm is real and budget-shaped:** 23 compiles/frame
   average while turning, bursts of 66-122, zero static. The
   direction-independent budgeted scheduler (A2) is funded by its own
   kill test.
5. **applyFrustum is 0.637 ms per ROTATING frame** - the old 0.022 ms
   amortized figure was the tick-quantized spin rarely crossing 2°
   buckets. Real mouse-look crosses per frame; the async-visibility
   prize (A1+A3) is roughly extract's 1.8 ms while turning, bigger
   than previously recorded.

#### The 1440p curve, refitted against the shipped state (batch 3)

Twenty-eight legs, two reps per cell, 2560x1440, phase-B CPU skip at
its shipped default: its tax read **0.000 ms on every one of the
fourteen occlusion legs**, in every scene. The curve, medians, both
reps agreeing (bfsDraw = the bfsOnly leg's opaque + translucent;
saved/tax per the stage-1 method; frame delta positive = occlusion
slower):

| cell | bfsDraw | saved | tax | frame delta | verdict |
|---|---|---|---|---|---|
| plains-rd16 | 0.233 | 0.03 | 0.15 | +21% | no result (CPU-limited) |
| plains-rd24 | 0.457 | 0.08 | 0.18 | +14/15% | LOSS |
| plains-rd32 | 0.758 | 0.16 | 0.22 | +5/7% | LOSS |
| plains-rd48 | 1.434 | 0.45 | 0.26 | **-11/12%** | WIN |
| plains-rd64 | 2.230 | 0.94 | 0.28 | **-25/28%** | WIN |
| ground-rd32 | 0.502 | 0.33 | 0.14 | **-14/17%** | WIN |
| ground-rd64 | 0.742 | 0.54 | 0.15 | **-27/37%** | WIN |

Two headlines. First, at the flagship distance the whole shipped stack
(occlusion + the new skip) beats bfsOnly by a quarter at plains and by
a third at eye level, and ground-rd64 runs a 0.7 ms frame at rd 64.
Second, **the draw-cost Auto trigger is refuted at 1440p**: ground-rd64
wins 27-37 percent at 0.72-0.76 ms of bfsOnly draw while plains-rd32
loses at the same 0.76 ms. Identical signal, opposite verdicts - the
draw cost cannot see the cull fraction, only the prize pool. This is
the second single-number Auto key this project has fitted and then
refuted with more data (resident count died the same way at 1080p),
and the conclusion is now structural, not parametric: **no passive
scalar decides this; Auto must probe.** The design (draw-cost floor,
short armed window, same-session verdict from the always-live GPU
timers, latch and re-probe) is in OCCLUSION-FILLRATE-DESIGN.md's
status section, unbuilt, first candidate for night two.
