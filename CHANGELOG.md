# Changelog

## 1.5.2

**Fixed: black water at the screen edges when your field of view widens.**

Take a speed potion over the ocean and the water revealed at the edges
of the screen drew as a dark, waterless seafloor until you moved your
camera. Sprinting or dragging the FOV slider flickered the same way for
a moment. The cause turns out to be a vanilla quirk: the game only
refreshes its list of visible chunk sections when the camera rotates,
never when the field of view changes, so potion and sprint zoom could
leave that list stale forever. You only noticed with occlusion on
because Meshelium draws the seafloor the instant it appears, while the
water layer still came from the stale list. Meshelium now tells the
game to refresh the list the moment your field of view changes, which
heals the water layer, the fallback path, and vanilla's own drawing all
at once. Costs nothing while the view is steady.

## 1.5.1

**Fixed: Solid Leaves was solidifying things that are not leaves.**

With Solid Leaves Beyond turned up, grass block sides smeared green,
and flowers, kelp, tall grass and vines stretched into blobs. The
setting now decides by geometry: only full see-through faces with
nothing behind them, which is leaves and full glass blocks, build
solid. Thin details, plants and the grass-side overlay keep their
normal look at every distance. Solid Leaves ships off, so nothing
changed unless you had moved that slider.

## 1.5.0

**Turning your camera is much smoother now.**

Whenever new terrain streamed in, Meshelium rebuilt its entire drawing
list from scratch, almost every frame while you were moving or turning.
At render distance 64 that one habit was costing about 40 percent of
every turning frame, and the memory churn behind it fed the little
hitches you could feel while flying. It now updates only what actually
changed. Measured while spinning the camera at render distance 64, the
frame time halved. Standing still was never affected.

**New: Smart Leaves, on by default past 16 chunks.**

Trees are full of faces you can never see: every leaf block touching
another leaf block draws the hidden wall between them, twice. Past 16
chunks, newly built terrain now skips those buried faces while keeping
the see-through look of the leaves you can actually see. In forests
that is roughly half of all leaf geometry gone, and because leaves
dominate what a forest chunk costs to build, it also smooths the
stutter of new terrain loading in. Trees quietly regain their full
detail as you get close. The slider is in Advanced; set it to 0 if you
want vanilla-exact terrain everywhere.

**New in Advanced: Solid Leaves Beyond, off by default.**

The next step past Smart: beyond a distance you choose, leaves build
fully solid, the way Fast graphics draws them everywhere. Distant
woods get cheaper still, and solid canopies start hiding the terrain
behind them from the renderer, which see-through leaves cannot do.
The see-through look returns as you approach.

**Fixed: the Advanced screen could cut off rows at larger interface
sizes.** It scrolls now, and the Done button stays put at the bottom.

## 1.4.0

**Occlusion culling now costs almost nothing while you stand still.**

One of occlusion culling's GPU passes exists to catch terrain that
becomes visible mid-frame. On a frame where the camera has not moved a
bit and no terrain changed, it provably has nothing to catch, and
Meshelium now proves that on the CPU each frame and skips the pass
entirely. Measured at render distance 64, where occlusion culling is on
by default, standing still gets about 11 percent of the frame back, and
it held 96 percent of that while the test camera panned, because the
proof only needs the view to hold still for a few frames at a time.

The moment you move, edit a block, resize the window, or a chunk
arrives, the pass runs exactly as it always did. It cannot show a stale
picture: anything doubtful counts as movement, and the passes that
decide what is visible never stop running either way. A launch flag
restores the old always-run path.

**New in Advanced: two distance sliders that trade far-away detail for
frames. Both ship Off.**

**Cull Tiny Plants Beyond** stops drawing small plants like grass tufts
and flowers past a distance you choose. At 2560x1440 a one-block plant
is smaller than a single pixel beyond about 64 chunks, so there is a lot
of room to move this slider before anything visibly changes.

**Cull Sub-Pixel Detail Beyond** goes further: past your chosen
distance, any face whose four corners land inside one pixel on your
screen is skipped entirely.

Both apply instantly while you watch, so the honest way to set them is
to drag until you see the picture change, then back off a step. Off
draws everything exactly as before; that is measured, not promised.
They are sliders rather than defaults because the frame-rate gain
depends on your world and your screen, and you are a better judge of
your own horizon than we are.

## 1.3.0

**Fixed: chunks could flash invisible for a split second while moving.**

Easiest to see over oceans, where the missing chunk showed the dark water
underneath and read as a black square blinking in and out. It could happen
anywhere terrain was rebuilding: for one frame, a rebuilding chunk had no
copy anywhere, because ours was still queued for upload and Minecraft's had
already been freed by the duplicate-memory saver. Over land the hole showed
whatever terrain was behind it, which is why it went unseen for a full
version. It settled down when you stood still and got worse the faster you
flew.

The old copy of a rebuilding chunk now stays on screen until its replacement
has actually landed on the graphics card. In a one-minute test flight that
handover fired about 3,800 times, and every one of them was a black flash
that did not happen. A new stress test rebuilds terrain in place and fails
the build if the gap ever comes back, and it was written the honest way: we
broke the fix on purpose and watched the test catch it before trusting
either.

**New: Idle Memory Trim, on by default.**

Meshelium grows its terrain memory in big steps so that growing stays rare,
and until now it kept all the spare room it had ever taken. After half a
minute of no terrain streaming, it now hands the unused part back to your
graphics card. Measured at render distance 64, that returned 488 MB and left
terrain memory 98 percent full instead of 52 percent, with no measurable
cost to frame times. Flying somewhere new simply takes the memory again.
Combined with 1.2.0's duplicate-memory saver, terrain that used to cost
around 1.7 GB of graphics memory now sits at about 540 MB.

The switch is in Advanced. Off restores the old keep-everything behaviour
exactly.

**New: Greedy Meshing, in Advanced. Off while it proves itself.**

Where several neighbouring block faces would look identical on screen, this
merges them into one bigger face so there is less geometry to draw. The
picture does not change, and a built-in checker we run in testing verifies
the merged result covers exactly the same ground as the originals.

How much it helps depends on your Smooth Lighting setting. With Smooth
Lighting off it removes about one face in six and was measured at 8 to 10
percent more frames. With it on, Minecraft shades every corner separately,
so far fewer faces match and the gain is 2 to 5 percent. Flipping the
setting reloads the terrain, so chunks rebuild for a few seconds and you can
compare live.

**NVIDIA and Intel now get the water-drawing speedup AMD already had.**

Transparent terrain like water has to blend back to front, so Meshelium
drew it in many small batches to keep the order safe, except on AMD cards,
where a one-draw-per-section fast path had been verified pixel-identical
and measured at about 1.3 ms per frame at render distance 64. The caution
existed because we believed the ordering rule the fast path relies on was
unclear in the Vulkan specification. We finally read the text closely: the
specification guarantees it outright, so the fast path is now on for every
card. A launch flag can force the old path in the unlikely event a driver
does not honour its own specification. The log line that still called this
an experiment is gone too.

**For NVIDIA and Intel testers.**

Two things in this release exist for cards we do not own. The graphics
card's mesh output limits are now measured at startup and respected rather
than assumed, so cards that only offer the specification minimum are safe by
construction. And there is an experimental GPU-side skip for one of the
occlusion passes behind a system property; it works, but on our AMD card the
driver charges more for the mechanism than the skip saves, so it ships off.
If you run this on NVIDIA or Intel and want to help, the properties are
documented in the source.

## 1.2.0

**Minecraft's duplicate copy of the terrain is now freed, by default.**

Minecraft kept a complete second copy of the world in graphics memory even
though Meshelium was the one drawing it. Nothing ever read that copy. It was
measured at 1.3 to 2.6 times the size of Meshelium's own terrain memory: on
a spinning test at 64 chunks it was 3264 MB, and freeing it took total
graphics memory for that scene from 5723 MB to 1883 MB. At 120 chunks the
owner went from hitting a 15 GB wall to a comfortable 8.7 GB with everything
loaded.

The two renderers now hand over one at a time rather than overlapping, so
switching Meshelium on or off reloads the terrain instead of briefly holding
both copies at once. You will see chunks rebuild for a few seconds when you
flip it. That is the trade, and it is what keeps an 8 GB card from being
asked to hold two worlds.

The setting is **Duplicate Terrain Memory** on the new Advanced screen, and
it reads Freed or Kept rather than On or Off, because "Free Duplicate Terrain
Memory: OFF" is not a sentence anyone can parse. Choose Kept only if another
mod needs Minecraft's own terrain buffers.

**Fixed: a graphics memory leak on every world exit.**

If your terrain memory had grown past its first block, which it does at any
long render distance, every block after the first was never handed back when
you left the world. A session at 120 chunks leaked most of its terrain memory
each time you quit to the menu, and it accumulated until you restarted the
game. One line, three call sites, and the method that caused it is gone.

**Fog now keeps up with how far you can see.**

Minecraft fades distant terrain to fog at a fixed 1024 blocks, and that
number ignores your render distance entirely. It was never a problem in
vanilla, where the maximum is 32 chunks and the fog sits far beyond the
horizon. Past 64 chunks it starts eating the world: at 120 chunks the
furthest 56 chunks are loaded, meshed, culled, rasterised, and then painted
flat fog colour.

**Distance Fog** in the settings has three choices, and it now ships on
**Off**, which turned out to look the best of the three at long range. Off
removes the distance haze and keeps only the short fade right at the edge,
which is the part that hides chunks appearing, so the horizon still softens
instead of ending in a wall. Match View Distance keeps a haze but moves it
out with your render distance, and never makes fog thicker than Minecraft
would, so below 64 chunks it changes nothing at all; a slider sets where it
finishes as a share of your view, labelled in blocks as well as percent.
Minecraft Default leaves everything alone.

**Your render distance comes back when you switch Meshelium on again.**

Turning Meshelium off pulls the distance down to 32, because Minecraft cannot
draw further than that on its own. It now remembers what you had and puts it
back when you turn Meshelium on. It will not overwrite a distance you chose
yourself while it was off, and it still never restores after a memory backoff,
which is a different situation and a deliberate one.

**A settings screen you can read.**

The rows that almost nobody should touch moved behind an **Advanced** button:
duplicate terrain memory, debug logging, and the backend prompt. The main
screen keeps what you actually reach for.

**Problems now say so in chat.**

Errors went to a toast in the corner that fades in a few seconds, and the
most useful ones had no player-facing surface at all. They are mirrored into
chat now, where they persist and can be screenshotted into a bug report.

**Fixed: terrain turning invisible at very high render distances.**

At render distance 96 and above, chunks could go see-through once enough
world had loaded, and placing a block could make more of them vanish. It
looked like the mod losing your terrain. It was not: the terrain was there
the whole time, in memory, uploaded and intact. The graphics card just
could not reach it.

Meshelium keeps all terrain in one big block of graphics memory, and it
sized that block from how much memory your card has. There is a second,
smaller limit that matters more, which is how much of a single block a
shader is allowed to read at once. On most cards that is 4 GB no matter how
much memory you have. Past that line, reads come back empty, and an empty
read looks exactly like an empty chunk, so those chunks were quietly
skipped.

The limit is now measured at startup and respected. If a world genuinely
does not fit, Meshelium goes passive and tells you, which is the behaviour
it always should have had. Nothing quietly disappears.

**Running out of room now pulls the render distance in instead of
breaking.**

Respecting the limit is not much use if reaching it still ruins the
session. When terrain memory passes 92 percent, Meshelium now lowers the
render distance a step and tells you it did. The far chunks are released
through Minecraft's own path, so the game knows to rebuild them when you
go back. Hitting the ceiling should feel like the render distance being
limited, because that is what it is.

It only ever goes down. The slider in Video Settings shows the new number
and you can drag it back up whenever you want, but Meshelium will not do
it for you. An automatic restore was built and measured first, and it
turned out to be worth nothing: the distance it wanted to restore to was
by definition the one that had just failed to fit, so it walked straight
back into the same wall. Each change also rebuilds all your terrain, so a
restore that re-trips costs you two stutters and buys nothing.

Under heavy pressure it steps faster, because a world streaming in was
measured filling terrain memory from 78 to 176 MB in three seconds, and a
polite one-step-every-three-seconds could not keep up with that.

## 1.1.0

**Occlusion culling is back, and this time it pays for itself.**

It shipped disabled and hidden in 1.0.0 because it cost about thirty times
what it saved. The cause turned out to be a single line. Every fragment of
an occlusion box wrote to the same 32-bit word using an atomic, and
same-address atomics serialise, so a box covering the screen was issuing
around a million queued read-modify-writes against one address every frame.
Reading the word before writing it, and skipping the write when this frame
already stamped it, made the occlusion path **5.4 times cheaper**: 287 to
1,553 frames per second in the scene that exposed it.

The feature now has a **Auto / On / Off** setting. Auto switches it on at
render distance 48 and above, which is where measurement says it starts
paying, and the crossover is a slider if your world disagrees with ours.

**Faster at every distance we measured**, against Minecraft's own renderer
on the same Vulkan backend, at 1920x1080 on an RX 9070 XT:

| Render distance | 1.0.0 | 1.1.0 |
|---|---|---|
| 32 | 1.9x | **3.1x** |
| 48 | 3.0x | **3.7x** |
| 64 | 4.2x | **5.3x** |

Some of that is the occlusion fix and some is a corrected baseline. Before
1.1 the "plain Minecraft" figures were measured in a way that flattered us
slightly less than we thought; the numbers above compare like with like.

### Settings

- **Occlusion Culling: Auto / On / Off**, with a slider and a typed box for
  the distance Auto turns on at. It helps on some systems and hurts on
  others, and the tooltip says so.
- **Terrain Rendering** moved to the top and now reads Enabled or Disabled.
  It is the master switch, so it belongs first.
- The **Custom** buttons that opened a separate screen are gone. Both
  sliders now have a number box beside them. Type a value and press Enter.
- **Reset Meshelium Settings**, above Done. Two clicks, so a slip cannot
  wipe a tuned configuration.

### Fixed

- **Occlusion culling was never valid Vulkan**, and had been quietly
  tolerated by AMD drivers since it was written. Two separate violations:
  reading `gl_PrimitiveID` in a fragment shader demanded a device feature
  Minecraft does not enable, and `fragmentStoresAndAtomics` was never
  requested even though the whole technique is a fragment shader writing to
  a buffer. On a stricter driver both fail silently and occlusion falls back
  to the slower path with no visible sign. **This most likely means
  occlusion culling did not work at all on NVIDIA or Intel.** Both are
  fixed, and the renderer now passes the Vulkan validation layers with
  synchronization validation enabled, which also machine-checks the barrier
  reasoning behind the occlusion passes.

### Internal

- Benchmarks now report smoothness, not just averages: worst frame, p99
  against the median, hitch count, and the largest frame-to-frame jump.
- Benchmarks record the resolution they actually ran at, and keep
  third-party overlay layers out of the process, so a measurement no longer
  depends on what else is installed.
- A GPU-side skip for the phase B draw pass was built, measured and
  reverted. It made things slower and produced 63 ms stutters. The ceiling
  it was chasing is documented at 8 to 12 percent, so the next attempt
  starts from a number.

## 1.0.0

First release. Mesh-shader terrain rendering for any GPU with mesh shaders,
render distances up to 120 chunks, and a graceful fallback with an
explanation when it cannot run.
