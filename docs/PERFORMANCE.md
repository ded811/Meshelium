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
4. [The release sweep](#the-release-sweep)
5. [Resolution changes the answer](#resolution-changes-the-answer-and-this-page-ignored-it-for-months)
6. [Reading the curve, including the part that loses](#reading-the-curve-including-the-part-that-loses)
6. [GPU cost per pass](#gpu-cost-per-pass)
7. [Workgroup sweep](#workgroup-sweep)
8. [The CPU optimisation pass, and what it removed](#the-cpu-optimisation-pass-and-what-it-removed)
9. [Traps this page had to survive](#traps-this-page-had-to-survive)
10. [Cross vendor status](#cross-vendor-status)
11. [What this page does not cover](#what-this-page-does-not-cover)

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
