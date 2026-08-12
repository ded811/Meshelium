# Meshelium 1.0.0, the technical half

Meshelium is a cross vendor, GPU driven terrain renderer for Minecraft 26.2,
built on the game's own Vulkan backend and one extension,
`VK_EXT_mesh_shader`. This page is the design record: what happens per frame,
what it refuses to do and why, how it behaves when something goes wrong, and
what had to be redesigned rather than translated when Nvidium's NVIDIA only
architecture was taken cross vendor.

It is written for people who read renderers. None of it is required reading to
play; the [README](../README.md) covers that in two minutes.

Measurements are not on this page. [`PERFORMANCE.md`](PERFORMANCE.md) is the
authority on every number, method and caveat, and this file points there rather
than repeating its tables.

**Contents**

1. [Where Meshelium sits next to the mods you already run](#where-meshelium-sits-next-to-the-mods-you-already-run)
2. [Best with Bobby](#best-with-bobby)
3. [How it works, one frame at a time](#how-it-works-one-frame-at-a-time)
4. [The coverage guard: everything, or nothing](#the-coverage-guard-everything-or-nothing)
5. [Render distance past 32, and why the ceiling is 120](#render-distance-past-32-and-why-the-ceiling-is-120)
6. [Performance, and where the numbers live](#performance-and-where-the-numbers-live)
7. [Workgroup shape: measured, not assumed](#workgroup-shape-measured-not-assumed)
8. [A known precision trade, inherited deliberately](#a-known-precision-trade-inherited-deliberately)
9. [Hardware and platform support](#hardware-and-platform-support)
10. [Not a generic Nvidium port](#not-a-generic-nvidium-port)
11. [Settings and configuration](#settings-and-configuration)
12. [What it deliberately does not do](#what-it-deliberately-does-not-do)
13. [Building and testing](#building-and-testing)
14. [Where the real documentation lives](#where-the-real-documentation-lives)
15. [Credits](#credits)
16. [Licence](#licence)

## Where Meshelium sits next to the mods you already run

Short version: Meshelium changes **who issues the draw calls and who decides
what is visible**. That is a different layer from what most performance mods
touch, and a completely different layer from what distance mods touch.

| Mod | What it changes | Where the work moves | What it needs from your GPU |
|---|---|---|---|
| **Sodium** | Rewrites Minecraft's chunk renderer and mesher: better batching, better data layout, fewer state changes | Still CPU driven draws, per section, on OpenGL | Anything |
| **Nvidium** | Adds a GPU driven mesh shader renderer on top of Sodium | GPU issues and culls the terrain work | Six mandatory GL extensions, five of them NVIDIA only. NVIDIA only in practice. |
| **Distant Horizons** | Builds its own low detail model of the world and draws it past your loaded chunks | A second, simplified world drawn alongside the real one | Anything |
| **Bobby** | Caches the chunks a server sent you and replays them as real chunks later | The data layer. It draws nothing. | Nothing |
| **Meshelium** | Re-encodes vanilla's finished section meshes into a GPU arena, then draws and culls them with mesh shaders | GPU issues and culls the terrain work | `VK_EXT_mesh_shader`, on Minecraft's Vulkan backend. AMD, NVIDIA and Intel all ship it. |

**Versus Sodium.** Sodium makes the CPU side of terrain cheap. Meshelium takes
the CPU side largely as vanilla gives it and moves the drawing and the
visibility decision onto the GPU: one dispatch per visible region, and a task
shader that throws sections away, instead of walking a section list on the CPU.
That is why the gap widens as the scene gets heavier. Per section CPU work
scales with the section count and a GPU task stage mostly does not. The two
have never been run together and probably cannot be: Sodium is a GL era
renderer, and this project has found no Sodium build that runs on 26.2's Vulkan
backend, which is Meshelium's hard floor. Meshelium is **not** a Sodium addon,
has no Sodium code, no Sodium dependency and no Sodium version pin. It was
studied only because Nvidium is a Sodium addon.

**Versus Nvidium.** Nvidium is the reason this exists and it proved the whole
idea. It is also NVIDIA only by construction: six GL extensions checked in a
single conjunction with no optional tier, five of them NVIDIA only (the sixth
is `GL_ARB_sparse_buffer`), and `GL_NV_mesh_shader` alone settles the vendor
question, because OpenGL never got a cross vendor mesh shader path and never
will (GL froze at 4.6 in 2017). Meshelium needs exactly one extension,
`VK_EXT_mesh_shader`, on Mojang's own Vulkan device. See [Not a generic Nvidium port](#not-a-generic-nvidium-port) for what
had to be redesigned rather than translated.

**Versus Distant Horizons.** A different problem entirely. Distant Horizons
answers "what do I show where there is no chunk data" with its own low detail
representation. Meshelium answers "how fast can real, full detail vanilla
geometry be drawn" and has no level of detail system at all. What Meshelium
draws is vanilla's own section meshes, at full detail, to the last block.
Nobody here has installed or tested Distant Horizons alongside Meshelium, so
treat that combination as unknown, not blessed.

**Compatibility, reasoned rather than tested.** Meshelium is client only and
hooks vanilla's section compiler and vanilla's chunk draw path. The mechanical
rule is therefore: anything that replaces those same paths is a collision risk
by construction, and anything that leaves terrain rendering alone should be
fine. That is reasoning from the code, not a test matrix. There is no tested
compatibility list and this page will not pretend otherwise.

## Best with Bobby

**On a multiplayer server, install [Bobby](https://modrinth.com/mod/bobby)
alongside Meshelium.** The two mods solve different halves of one problem and
neither can do the other's half.

**The mechanism, because it is worth understanding once.** A server decides how
far it sends chunk **data**. Vanilla computes your effective render distance as
the smaller of your option and the server's view distance, and then uses that
same number **twice**: once to place the fog wall, and once to decide which
sections it is willing to build at all. Those are the same cylinder. Terrain
you were never sent is terrain vanilla never compiles, so there is nothing out
there for any renderer to draw, and pushing the fog back only reveals the
emptiness.

This project learned that the expensive way. Two separate waves widened the
presentation side (the fog wall, then the far plane) to expose terrain that had
been kept in GPU memory from earlier travel. On the owner's real server both
produced worse artifacts than they fixed, and both were reverted whole. The
finding is preserved in `docs/FRONTIER-HOLES-RECON.md` and
`docs/MP-RETENTION-RECON.md` in bytecode terms, and it is simple: **this is a
data problem, and it has to be fixed in the data layer.**

Bobby fixes it in the data layer. It caches the chunks the server does send you
to disk and serves them back as real chunks when you move out of range, so your
client genuinely holds a wide world. Meshelium then draws all of it with mesh
shaders at a render distance far past vanilla's 32.

**Bobby supplies the world. Meshelium draws it.** Credit where it is due: Bobby
is Johni0702's work, and it is the mod that makes a big render distance mean
something on a server.

Two pieces of honesty about this pairing:

- **It has never been tested here.** Bobby has not been installed, run, or read
  alongside Meshelium. There is no version pin, no compatibility evidence and
  no promised result. This is a recommendation built on understanding the
  problem, not on a screenshot. Check Bobby's own version list for 26.2
  support.
- **Something was retired to make room for it.** Meshelium used to ship a
  "retained terrain" feature that kept unloaded sections alive in GPU memory,
  with a toggle and a time limit on the settings screen. Both rows were removed
  at 1.0.0. Retained terrain sat behind that same opaque fog wall, so it spent
  graphics memory to show nothing in normal play. The machinery survives only as
  a developer surface (`retainTerrain` and `retainTerrainMinutes` in
  `config/meshelium.json`, both default off, no user interface, no settings
  screen row) because a future data layer wave might want it over cached
  chunks. It is not a feature, and Meshelium cannot show you terrain past a
  server's view distance on its own. Nothing can, from the render layer. That
  is the whole point of the paragraph above.

## How it works, one frame at a time

Vanilla still owns world data and still builds section meshes. Meshelium starts
after that and ends before the rest of the frame.

1. **Vanilla compiles a section.** Untouched. Meshelium taps the compile result
   rather than replacing the mesher, which is the decision that keeps it free
   of any third party renderer dependency.
2. **Re-encode.** The section's quads are packed into a **16 byte vertex**
   (vanilla's is 28) and sorted into 7 facing buckets, then uploaded into a
   device local **GPU arena** through Mojang's own VMA allocator. Two small
   fixed records describe each section (32 bytes) and each region (16 bytes).
3. **Record the frame on the CPU, cheaply.** One `vkCmdDrawMeshTasksEXT` per
   frustum visible region, carrying the region origin, the region record index
   and the mask slot as push constants. Meshelium writes no GPU side indirect
   command buffers anywhere.
4. **The task stage culls.** Per section frustum, distance and visibility
   culling runs on the task shader, which then emits mesh workgroups only for
   the sections that survived. The frustum test uses vanilla's exact machinery,
   the JOML `FrustumIntersection` formulas with the p-vertex rule, so it can
   only remove boxes that vanilla's own looser cull frustum would have found
   pixel free.
5. **Occlusion, two phases.** Phase A draws the set that was visible last
   frame, which primes the depth buffer. Then region bounding boxes and per
   section bounding boxes are rasterized against that depth with colour writes
   off, stamping what is visible. Phase B immediately redraws whatever just
   became visible, so a camera cut repaints in the **same** frame instead of
   popping in on the next one. Vanilla's own flood fill visibility graph stays
   wired up as a correctness fallback and can be selected at runtime.
6. **The mesh stage expands quads.** Packed vertices are fetched, quads become
   triangles, and the fragment shader is a port of **vanilla's own**
   `terrain.fsh` with its `fog.glsl` and `globals.glsl` includes, dumped
   verbatim from the 26.2 jar. Vanilla's `sample_lightmap.glsl` sampling is
   reproduced too, one stage earlier, in the mesh shader. Pixel parity with
   vanilla is the bar rather than stylistic freedom.
7. **Translucency uses vanilla's sort.** Inside a section, the build time
   sorted prefix vanilla already produced. Across sections, vanilla's camera
   sorted visible list reversed, which is exactly the list and the reversal
   vanilla's own render group applies. Meshelium invents no ordering of its
   own, and the blend, depth and cull state is vanilla's `TRANSLUCENT_TERRAIN`
   pipeline state value for value.
8. **Then, and only then, vanilla's terrain draws are cancelled** for that
   frame, opaque and translucent together, and only on frames Meshelium
   provably owns.

All of it rides Minecraft 26.2's **own Vulkan backend**: vanilla's device,
vanilla's queues, vanilla's command encoder, vanilla's memory allocator,
vanilla's timestamp query pool, and the shaderc natives that ship with the game
for turning GLSL into SPIR-V at runtime. Meshelium is not a second renderer
bolted alongside Minecraft. It adds exactly one Vulkan extension,
`VK_EXT_mesh_shader`, to the device Mojang was already creating, using Mojang's
own public helpers for optional extensions and never touching Mojang's required
sets.

**One detail that bit early and shapes every pipeline: Minecraft 26.2 uses a
reversed depth convention.** The depth buffer clears to 0.0 and vanilla compares
GEQUAL. That came out of the disassembly recon of vanilla's Vulkan backend, it
invalidated this port's own earlier depth assumptions, and every Meshelium
pipeline inherits it, including the occlusion box rasters, which are GEQUAL with
depth writes off and colour writes masked. Nvidium was written against
conventional depth.

## The coverage guard: everything, or nothing

This is the part worth stealing if you write renderers.

Meshelium keeps four drop counters: arena full, section too large, region
budget exhausted, encoding failed. If **any** of them moves even once during
the current world, Meshelium stops cancelling vanilla's draws. Vanilla then
draws the complete terrain set, Meshelium goes passive for the rest of that
world, and one warning names the exact budget that tripped.

The guard is monotonic inside a world: counters only ever grow and the baseline
only moves when the world is disposed, so it can never flicker back on mid
session. It is read once, at opaque draw time, and opaque and translucent are
coupled, so it cannot flip halfway through a frame. It re-arms only on a world
load whose counters stay clean.

The property that buys is absolute, and it is the thing a player actually cares
about: **Meshelium either draws everything or draws nothing. It never draws a
partial world.** No holes, no missing chunks, no "turn it off and on again".
This is deliberately different in kind from the usual answer to video memory
pressure, which is to evict live terrain and hope. Meshelium does not evict, it
resigns.

The same instinct runs through the rest of the failure handling. Any internal
error hands the frame back to vanilla and latches instead of throwing. Device
loss is caught distinctly and latched, and vanilla has no client side handler
for it, so Meshelium can never crash harder than vanilla would have. An
occlusion failure silently reverts to the fallback visibility feed and terrain
keeps drawing. And any extended render distance snaps back to vanilla's 32 the
moment Meshelium is not actually drawing, because leaving vanilla's renderer at
64 would be a slideshow that Meshelium caused.

## Render distance past 32, and why the ceiling is 120

Meshelium does not add its own render distance control. It **widens vanilla's**.
The Render Distance slider in Video Settings is the same slider, in the same
place, and it now runs up to Meshelium's cap. The cap defaults to **96**; the
Meshelium settings screen offers a 32 to 96 slider plus a Custom box that
reaches **120**. In singleplayer the integrated server follows the option, so
the chunks really do arrive.

Getting there needed root cause fixes vanilla never needed, all recorded in
`docs/EXTENDED-RENDER-DISTANCE.md`:

- Vanilla builds the option as a range of 2 to 32 (2 to 16 if the game has less
  than a gigabyte of heap), and that range is copied into a persistence codec
  captured when the option is constructed. Widening one without the other
  silently loses your saved value, so both are widened before the options file
  loads.
- The integrated server's view distance clamp and its player ticket tracker
  range both had to be widened, or the server simply refuses to send what the
  client asked for.
- The chunk task priority ladder is sized at boot and threw an array index
  crash past a certain distance. Widened unconditionally, on both backends.
- Raising the slider mid world used to need a rejoin. Now the GPU record
  buffers grow in place. The old "rejoin to apply fully" notice survives only
  as the fallback if that growth ever fails.

**Why 120 and not 128.** The client's requested view distance travels to the
server as a **signed byte** inside `ClientInformation`. 128 arrives as -128,
clamps to 2, and silently kills the entire server follow chain. 120 is the last
safe stop. It is a wire format limit, not a taste decision.

**Why values above 96 hide behind a Custom box.** Past 96 the per section
tracking and record cost curve gets steep enough that it should be an explicit
choice you made, not a slider you nudged.

**Why the widened range disappears when the mod is off.** On OpenGL, without
mesh shaders, or after the coverage guard trips, the render distance clamps back
to vanilla's 32 with a notice. Leaving vanilla to render 96 chunks would be a
slideshow that Meshelium caused.

## Performance, and where the numbers live

**Every measurement lives in `docs/PERFORMANCE.md`**, with its method, its
hardware, its noise floor and the traps it had to survive. This page does not
repeat those tables. The one line summary: on the single machine that has ever
run this mod, vanilla is faster below render distance 16, Meshelium is about
2.2x faster at 32 (vanilla's own maximum), and about 6x faster at 64. If you
never move the render distance slider, Meshelium is not the mod that will speed
you up. It earns its place the moment you push the distance out, which is the
whole reason it exists.

Three caveats belong here as well as there, because they change how the rest of
this page should be read:

- **One machine, one scene.** An AMD RX 9070 XT (RDNA4) on Windows 11, in one
  fixed camera plains world. **NVIDIA and Intel are UNMEASURED.** AMD RDNA2,
  RDNA3 and the Steam Deck are untested too, and merely expected to work on the
  shared driver stack. If a number is not in `docs/PERFORMANCE.md`, it does not
  exist: treat any claim not made there as absent, not implied.
- **The vanilla baseline is measured in the same session**, by flipping
  Meshelium's kill switch live, so both legs share a driver state, a chunk set
  and a thermal state. It is never a number from another day.
- **CPU and GPU figures are never summed.** They are two different measurements
  of the same frame: whole frame wall time, and per pass timestamp queries.

One measured result feeds straight back into the design, so it belongs here
too: at render distance 32 the box raster occlusion plus the visibility mask
removes roughly half the draw set in open plains, which is close to the worst
case for occlusion. Caves and mountains cull far harder.

## Workgroup shape: measured, not assumed

Meshelium dispatches **32 quads per mesh workgroup** (128 vertices, 64
primitives) and **32 sections per task workgroup**, injected into the shaders as
host side macros so they can be retuned per vendor without touching the GLSL.

Neither number came from the device's own advice. Both were swept on real
hardware, and the sweep overruled the hardware: RDNA4 advertises a preferred
256 invocations, and doubling the mesh workgroup toward it was a measurable
regression. Measurement beat the caps. The knobs stay in the shipped build
precisely because NVIDIA's and Intel's optima are unknown and may well differ.
The full sweep, including the rows that turned out to be noise, is in
`docs/PERFORMANCE.md`.

## A known precision trade, inherited deliberately

The 16 byte packed vertex quantises positions onto a fine lattice, and all
rendering is camera relative in 32 bit float. At extreme distance this shows up
as sub block misalignment that is **visible only through zoom mods**, and the
owner observed exactly the same thing in original Nvidium on NVIDIA hardware.

It is the price of vertices that are 16 bytes instead of vanilla's 28, it grows
with render distance, and it was taken on knowingly rather than discovered. It
is revisited only if it ever becomes visible without magnification. The
extended distance bench screenshots are the first place to look for it.

## Hardware and platform support

Meshelium needs a GPU that exposes **`VK_EXT_mesh_shader`** with **both** the
mesh shader and task shader feature bits. Without it the mod stays completely
dormant and vanilla renders as normal.

| Vendor | Hardware | Status |
|---|---|---|
| **AMD** | RX 9070 XT (RDNA4) | **Measured.** Every number on this page came from this card. |
| **AMD** | RDNA2 and RDNA3: RX 6000, RX 7000, Steam Deck, Ryzen 6000 and newer integrated graphics | Expected to work on the same driver stack. **Never run. No numbers.** |
| **NVIDIA** | Turing and newer: GTX 16xx, RTX 20xx and up | **UNMEASURED.** No such card on the developer's desk. |
| **Intel** | Arc, and Xe-LPG (Meteor Lake) and newer integrated graphics | **UNMEASURED.** No such GPU on the developer's desk. |

The family cutoffs above describe the **intended target**, based on which
silicon ships the extension. They are not a tested compatibility list, and only
the first row has ever been verified by running the mod.

**Operating systems: Windows and Linux.** macOS is out of scope permanently,
because MoltenVK does not translate mesh shaders, so there is nothing to build
on. That is an external fact about MoltenVK rather than something measured
here, and it is the reason macOS is a non goal rather than a to do item.

**Vulkan version:** vanilla requests Vulkan 1.2 and `VK_EXT_mesh_shader` needs
1.1 or newer, so the two fit together. Meshelium's pipelines use dynamic
rendering, matching vanilla.

## Not a generic Nvidium port

Nvidium, by **MCRcortex**, is why this project exists. It proved that Minecraft
terrain can be drawn GPU driven through mesh shaders at enormous view distances,
and Meshelium takes its renderer architecture, its data formats and, where
ported, its shader logic. That debt is spelled out file by file in
[Credits](#credits) and it is not a small one.

What Meshelium is **not** is Nvidium with the names changed. Nvidium is an
OpenGL Sodium addon standing on six mandatory GL extensions, five of them
NVIDIA only. Getting the same idea onto AMD and Intel hardware, on Minecraft's
own Vulkan backend, turned out to be mostly a series of "you cannot do that
here" problems, and each one needed an answer rather than a translation.

| Area | Nvidium | Meshelium |
|---|---|---|
| **GPU requirement** | six GL extensions, all mandatory, no optional tier, five of them NVIDIA only | **one** extension, `VK_EXT_mesh_shader`, which AMD, NVIDIA and Intel all ship |
| **Host** | Sodium addon on an OpenGL host, pinned to exact Sodium versions | **standalone on vanilla**, riding Minecraft's own Vulkan device, VMA allocator, queues and command encoder |
| **Depth** | conventional depth | 26.2 is reversed Z: GEQUAL and a 0.0 clear, inherited by every pipeline |
| **Visibility state** | one byte per section, shifted every frame, written by a cross stage same address race | **ping pong frame stamps**: one unsigned int per slot in two buffers picked by frame parity, every writer in a frame writing the identical value with an atomic exchange |
| **Indirect draws** | 8 byte commands carrying a `firstTask` field | no GPU written indirect buffers at all: **one CPU recorded dispatch per visible region**, with push constants |
| **Readback** | fenceless persistent mapping, no overflow check | **fenced ring** at a lag derived from vanilla's own submission timeline, plus the overflow check |
| **Translucency** | the mesh shader mutates the live vertex pool with deliberately racy writes | **vanilla's sorter is the authority** at both granularities, and geometry is never mutated |
| **Mesh dialect** | NV: primitive count written after culling and subgroup compaction, workgroup assumed equal to subgroup | EXT: `SetMeshOutputsEXT` once, up front, in uniform control flow, and no subgroup operations at all |
| **Occlusion speed hack** | `GL_NV_representative_fragment_test` | dropped, because the occlusion stores are idempotent so correctness does not depend on it |
| **Memory** | 80 GB sparse virtual arena, which Nvidium itself disables on Linux | **256 MiB elastic arena**, grow and copy on demand, ceiling derived from the device's largest device local heap |
| **VRAM pressure** | eviction heuristic fed by an async query raster | **no eviction**: hand the whole frame back to vanilla, so a partial world cannot happen |
| **Settings and view distance** | Sodium's options GUI and option storage | vanilla's Video Settings, and **vanilla's own render distance slider widened** |

Five of those are worth a paragraph.

**The visibility race, replaced rather than copied.** Nvidium tracks visibility
in one byte per section, shifted left each frame with the new bit injected by
the raster, and its two writers hit the same address from different shader
stages with different values. That is undefined behaviour under the Vulkan
memory model, and it works because one vendor's hardware happens to tolerate
it. Only bits 0 and 1 are ever read anyway. Meshelium stores a frame stamp per
section slot in two ping pong buffers chosen by frame parity: "visible this
frame" becomes equality with the current frame number, "visible last frame"
becomes equality with the previous one. Every writer in a frame writes the same
value, so write ordering stops mattering, atomic exchange is defined on every
conformant device, **no clearing pass ever runs**, and slot reuse, world changes
and a debug reload all become self invalidating. A stale stamp can add a depth
tested draw of a current record, but it can never suppress one and never replay
freed geometry. A whole class of artifact stops being possible instead of being
cleaned up after.

**The indirect command shape: sidestepped, not solved, and the code says so in
those words.** Nvidium's 8 byte indirect command carries a `firstTask` field,
which is what makes the workgroup id a global section index everywhere
downstream. Vulkan's mesh task indirect command has no such field, and
redesigning around that would have crossed the occlusion, terrain and
translucency subsystems at once. Meshelium does not write GPU side indirect
buffers at all: it records one dispatch per frustum visible region on the CPU
and consumes the occlusion stamps in the same frame instead of feeding next
frame's command buffer. Honest framing: the problem was removed, not answered.

**Fenced readback, where there was no fence.** Nvidium reads GPU results back
through a persistent mapping with no fence at all, relying on driver leniency,
and its download stream has no overflow check. Meshelium reads back a fixed
number of frames later, and that number is derived rather than guessed: vanilla
runs two submits in flight with a CPU side timeline wait, so by frame F+3 frame
F's last submission has provably completed. That constant gates the stats
readback, every deferred buffer free, and the retirement of grown buffers, with
a destroy time re-assert that latches an error if the lag was ever violated. The
staging ring also carries the overflow check the original never had: a full ring
returns a failure and the caller backlogs instead of writing at a bogus offset.

**An 80 GB sparse arena becomes a 256 MiB elastic one.** Nvidium's geometry
lives in an 80 GB sparse virtual allocation with pages tuned for one driver, and
Nvidium itself disables that path on Linux. Sparse binding and buffer device
address are both absent from vanilla's Vulkan device as created, so neither was
available here anyway. Meshelium starts at 256 MiB per world and grows on
demand: a new device local buffer, zero filled tail, full copy, barrier, backing
swap, at identical offsets so no record, snapshot or shader changes. The old
buffer is destroyed only after the fence lag above. The ceiling is derived from
your hardware, the larger of 256 MiB and half of the largest device local heap,
using the largest single heap rather than the sum, because discrete cards report
the small resizable BAR heap as a second device local heap. This exists because
a fixed 256 MiB arena tuned on a plains benchmark overflowed in a real overworld
on a 16 GiB card and put the mod passive for the world.

**The coverage guard has no Nvidium equivalent.** Nvidium answers video memory
pressure by evicting terrain to stay inside a budget. Meshelium answers it by
handing the whole frame back to vanilla the moment one section is dropped for
any reason. Different in kind, not in degree. See
[the coverage guard](#the-coverage-guard-everything-or-nothing).

None of this is a knock on Nvidium. Several of these differences exist only
because Nvidium proved the architecture works first, and a couple of them are
simply what you get when the target is three vendors and a formal memory model
instead of one very good driver.

## Settings and configuration

Reach the settings two ways: the **Meshelium Settings...** button in vanilla's
Video Settings, which is the primary route, or the `/meshelium` client command.
The Video Settings button is injected at the top of the options list, above the
Display section so it is visible without scrolling, and it is added on **both**
graphics backends on purpose, so an OpenGL session can still open the screen and
read why the mod is off. There is no ModMenu integration: the adapter that used
to provide a third route to this same screen was deleted at 1.0.0, because it
compiled against a jar on one developer's disk and was the only thing stopping a
fresh clone from building.

The first line of that screen answers the only question that matters: **ACTIVE**
with a live count of the chunk sections being drawn, **READY**, checking, or
**NOT RENDERING** with the exact reason (OpenGL is active, no mesh shader
support, terrain turned off, a renderer error, or the specific GPU budget that
went passive).

| Setting | Default | What it does |
|---|---|---|
| **Render distance cap** | 96 | How far vanilla's Render Distance slider is allowed to go. Slider from 32 to 96, plus a Custom box reaching 120. |
| **Occlusion culling** | on | GPU box raster occlusion. Off falls back to vanilla's visibility flood fill, which is the correctness fallback. |
| **Terrain rendering** | on | The master switch. Off hands terrain back to vanilla entirely. |
| **Debug stats** | off | Periodic residency and draw path lines in the log. |
| **Backend popup** | on | On/off. Re-arms the one time Vulkan notice if you dismissed it. |

Every row has a hover tooltip that says what it does and when it applies.
Every toggle except the backend popup applies on the next frame with no
restart; the popup row is about startup, so it lands at the next game start.
Closing the settings screen after a cap change hands you a freshly built Video
Settings screen so the
vanilla slider picks up its new bounds without backing out of the whole options
tree.

**The config file** is one plain JSON file at `config/meshelium.json`, with no
config library behind it. Player facing fields are `enableTerrainRendering`
(default true), `enableOcclusionCulling` (default true), `debugStats` (default
false), `maxRenderDistance` (default 96), plus three popup persistence flags.
Every resolver is re-read each frame, so a change made on the settings screen
takes effect on the next frame. Hand editing the file is a different matter:
the file itself is read once at startup, so those edits need a restart.
Developer and tuning knobs are deliberately system properties only and stay out
of the player config, so the settings screen stays short enough to read.

**The popup.** The backend gate decides once per session, on the first client
tick where the loading overlay is gone, the title screen is up and a GPU device
exists. It lands in exactly one honest state: OpenGL (dormant, plus one popup
with the Enable Vulkan button); Vulkan but no mesh shaders (dormant, plus one
honest notice); Vulkan with mesh shaders (it may run, no popup); or Vulkan was
requested but boot fell back to OpenGL, which gets its own message and
deliberately does **not** offer the button, because offering it would be a
broken promise. There is no nag loop: "don't show again" persists, and the
settings screen can re-arm it.

## What it deliberately does not do

- **No shader pack support.** Iris and shader packs are parked, described by the
  owner as "a far off pipe dream". The one concession made now is that the
  terrain path sits behind a clean interface so a compatibility layer has a seam
  later. Nothing has been tested with Iris and no claim is made either way.
- **No fallback renderer for GPUs without mesh shaders.** Hardware without
  `VK_EXT_mesh_shader` keeps vanilla rendering, permanently. That is a decision,
  not a gap waiting to be filled.
- **No macOS.**
- **No level of detail system, and no distant terrain generation.** See the
  Distant Horizons comparison above.
- **No terrain past what the server sent you.** That is a data problem and no
  renderer can solve it. See [Best with Bobby](#best-with-bobby).
- **No retained terrain.** Retired from the user interface at 1.0.0, machinery
  kept behind a developer config flag only.
- **No ModMenu integration.** Deleted at 1.0.0. Meshelium compiles against
  Minecraft, Fabric loader and Fabric API and nothing else.
- **No cross vendor verification.** Said more than once on this page on purpose.
- **New.** 1.0.0 means shipped, not battle tested. It works, it has been pixel
  compared against vanilla frame by frame, and it also had two whole waves
  reverted during development for making things worse on a real server. Treat
  it accordingly.

## Building and testing

```
./gradlew build                 # compile
./gradlew runClientGameTest     # boots a real client on the real GPU and screenshots
```

The client gametest harness is the entire test story. This is a client only mod,
so server side gametests would be structurally vacuous and are disabled. A
screenshot only proves the angle it was taken from, so performance claims come
from frame timing on real hardware instead; the bench scenes and the exact flags
that reproduce every published row are in `docs/PERFORMANCE.md`.

There is **no build time shader toolchain**. Minecraft already ships shaderc and
SPIRV-Cross natives for its own runtime GLSL compilation, and Meshelium drives
shaderc directly for the task and mesh stages, which vanilla's own compiler
cannot express.

If you have NVIDIA or Intel hardware and a spare afternoon, running that bench
is the single most valuable thing anyone could contribute to this project.

## Where the real documentation lives

The `docs/` folder is the evidence base, not marketing. Several of these
predate decisions that later reversed; the ones that do carry a status banner
at the top saying so, and they are kept because the reasoning is the evidence.

| File | What is in it |
|---|---|
| [`PERFORMANCE.md`](PERFORMANCE.md) | Every measured number, with method, hardware and the traps |
| [`SPEC.md`](SPEC.md) | The build plan, wave by wave, with the evidence for each row |
| [`EXTENDED-RENDER-DISTANCE.md`](EXTENDED-RENDER-DISTANCE.md) | Everything behind the widened slider, including the server half |
| [`VANILLA-VULKAN-SEAM.md`](VANILLA-VULKAN-SEAM.md) | Disassembly recon of 26.2's Vulkan backend, question by question |
| [`NVIDIUM-ARCHITECTURE.md`](NVIDIUM-ARCHITECTURE.md) | Source study of the original, including the inventory of every NVIDIA only piece and its cross vendor replacement |
| [`TERRAIN-DATA.md`](TERRAIN-DATA.md) | The byte layouts of the vertex, section and region records, each re-derived and pinned by a test |
| [`VANILLA-FRAME-PATH.md`](VANILLA-FRAME-PATH.md), [`VANILLA-SECTION-BUILD.md`](VANILLA-SECTION-BUILD.md) | Where vanilla's frame and section build actually go |
| [`MP-RETENTION-RECON.md`](MP-RETENTION-RECON.md), [`FRONTIER-HOLES-RECON.md`](FRONTIER-HOLES-RECON.md) | The two reverted attempts at the fog wall, kept as the evidence for why the answer is Bobby. Both carry REVERTED banners |

## Credits

### Nvidium, by MCRcortex

**[Nvidium](https://github.com/MCRcortex/nvidium) is the reason this project
exists**, and saying so is not a formality. MCRcortex proved that Minecraft
terrain could be drawn GPU driven through mesh shaders at view distances nobody
thought were reachable, years before there was a cross vendor way to do the same
thing, and then published the whole thing under the LGPL so other people could
learn from it. Meshelium inherits the entire shape of that idea: the renderer
architecture, the data formats, the occlusion approach, and where ported, the
shader logic itself. Everything on this page that is fast is fast because that
groundwork exists. If you have an NVIDIA card and you have not tried Nvidium, go
and try Nvidium.

What is literally derived, file by file. Each of these carries an LGPL header
pointing back into Nvidium; all but the staging ring name MCRcortex and cite
the exact source file:

| Meshelium file | What came from Nvidium |
|---|---|
| `terrain/SegmentedManager.java` | ported, algorithm identical |
| `terrain/IdProvider.java` | ported, algorithm identical, only the header added |
| `terrain/TerrainVertexCodec.java` | the 16 byte packed vertex, re-derived from Nvidium's compact chunk vertex plus its vertex format shader |
| `terrain/SectionRecord.java`, `terrain/RegionRecord.java` | byte exact record layouts |
| `terrain/TerrainArena.java` | the arena, from `BufferArena.java` |
| `terrain/QuadFacing*.java`, `terrain/SectionMeshEncoder.java` | the 7 bucket facing contract |
| `terrain/host/RegionStore.java` | region management, from `RegionManager.java` |
| `terrain/EncodedSectionMesh.java` | from `RepackagedSectionOutput.java` |
| `vk/TerrainOcclusion.java` and the occlusion shaders | the two level box raster architecture and phase order |
| `vk/VkStagingRing.java` | shaped after `UploadingBufferStream` |
| `shaders/terrain.mesh` | quad expansion, vertex fetch and triangle split |

Nothing here is clean room, nothing needs to be, and the licence permits exactly
this as long as it is done openly. So: openly.

### Alphadium

**Alphadium**, the community fork of Nvidium (author "Cortex", with contributors
**drouarb** and **R7CE4**), LGPL-3.0, which tracked the original across many
Minecraft and Sodium versions. Studying it contributed two things directly. Its
history proved that the GPU core survives host churn essentially byte identical
while all the maintenance lands at the host boundary, which is the evidence
behind Meshelium standing alone on vanilla. And Meshelium adopted two of its
decisions outright: host sorter first translucency, and a conventional
interpolant fragment path instead of vendor specific barycentric pulling, along
with its list of behavioural fixes.

### Mojang

Meshelium is cross vendor **because** Mojang shipped a Vulkan backend and left
clean seams in it. It rides that backend rather than standing up a parallel one:
vanilla's device creation seam (appending its one extension through Mojang's own
public utilities, mirroring Mojang's own optional extension pattern, and never
touching their required sets), vanilla's device, queues, command encoder and
Vulkan Memory Allocator, the shaderc and SPIRV-Cross natives that ship with the
game, vanilla's timestamp query pool for the GPU timings (the same stack its own
profiler uses), and vanilla's own terrain fragment shading ported verbatim
because pixel parity with vanilla is the bar. Vanilla's sorting, frustum math
and pipeline state are the authority everywhere Meshelium could have invented
its own.

### Bobby

**[Bobby](https://modrinth.com/mod/bobby)** by **Johni0702** is the recommended
companion on servers, and the reason Meshelium does not try to solve the chunk
data problem itself. Bobby already solves it at the layer where it is actually
solvable. See [Best with Bobby](#best-with-bobby), including the note that the
combination has not been tested here. **Distant Horizons** deserves a mention in
the same breath for attacking the same layer from a different angle.

### And

- **Fabric Loader, Fabric API and Fabric Loom.** The loader, the client tick
  lifecycle hook the backend gate rides on, the command API behind `/meshelium`,
  and the client gametest harness that is this mod's entire test story.
- **LWJGL**, for the Vulkan, shaderc and VMA bindings that ship with Minecraft.
- **shaderc**, driven directly for the task and mesh stages, because vanilla's
  own shader compiler knows only vertex and fragment.
- **JOML**, Minecraft's own math library, used to reproduce vanilla's frustum
  culling formulas exactly.
- **SpongePowered Mixin**, via Loom.
- **No optional mod integrations.** Meshelium compiles against nothing but
  Minecraft, Fabric loader and Fabric API, so a fresh clone builds.
- **Sodium** is **not** a dependency and never was, for the reasons in
  [Where Meshelium sits next to the mods you already run](#where-meshelium-sits-next-to-the-mods-you-already-run).

Not affiliated with Mojang, AMD, NVIDIA, or Intel.

## Licence

**LGPL-3.0-only.** The full GNU Lesser General Public License version 3 text is
in [`LICENSE`](../LICENSE) at the root of the repository.

The reason is simple and worth stating plainly: the design and, where ported,
the shader logic derive from Nvidium, which is LGPL-3.0. Two files are direct
ports with the algorithm unchanged, several more are byte exact format
re-derivations, and the occlusion architecture and the mesh shader's quad
expansion logic are Nvidium's. Every ported file keeps its attribution in the
header.
