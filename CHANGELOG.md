# Changelog

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
