# Meshelium

**See further. Get more frames. Powered by mesh shaders.**

Meshelium draws Minecraft's terrain with your graphics card's mesh shaders
instead of the way the game normally does it. You get more frames per second,
and you can push the render distance far past the slider's usual limit.

## How much faster?

Measured at 1920x1080 on an AMD Radeon RX 9070 XT, same world, same view, at
default settings. One run with the mod and one without:

| Render distance | Meshelium | Minecraft |
|---|---|---|
| 12, Minecraft's default | 2,437 FPS | 1,621 FPS |
| 16 | 2,126 FPS | 1,115 FPS |
| 24 | 1,709 FPS | 641 FPS |
| 32, as far as Minecraft goes | **1,206 FPS** | 393 FPS |
| 48 | **761 FPS** | 205 FPS |
| 64 | **607 FPS** | 114 FPS |

The further you look, the bigger the difference. At render distance 64,
Meshelium runs more than five times faster than Minecraft's own renderer.

Both columns are Minecraft's Vulkan renderer, the one Meshelium runs on, so the
only thing that changes between them is Meshelium itself.

## What you need

- **Minecraft 26.2** with **Fabric** and [Fabric API](https://modrinth.com/mod/fabric-api)
- Windows or Linux
- A graphics card with mesh shaders: **AMD** RX 6000 or newer, **NVIDIA** GTX 16xx
  or newer, **Intel** Arc. Newer laptop and handheld chips count too
- If your card is older, Meshelium turns itself off and tells you why. Your game
  keeps working normally

Client side only. Your friends do not need it, and neither does your server.

## Do this or nothing will happen

Minecraft starts in the old drawing mode, OpenGL. Meshelium only works in the
new one, Vulkan.

1. Open **Options**
2. Go to **Video Settings**
3. Find **Graphics API**
4. Choose **Prefer Vulkan**
5. **Restart Minecraft.** It only changes while the game is loading

Skip this and it looks like the mod did nothing. If that happens, Meshelium puts
a message on screen with a button that does it for you.

## Settings

There is a **Meshelium Settings** button at the top of Video Settings. Everything
in it applies instantly, and there is a reset button if you want to start over.

The one worth knowing about is **Occlusion Culling**. Before drawing the world,
Meshelium can ask your graphics card which parts are hidden behind other parts
and skip those. Asking costs a little every frame, and it only saves time when
plenty really is hidden, so it helps some players and hurts others. It is a
clear win far underground or looking across hilly land, and a small loss on flat
open ground or looking down from high up, where almost nothing is behind
anything.

Left on **Auto** it switches itself on once your render distance is far enough
that it usually pays. If you mostly play on the ground or in caves, try lowering
the distance it turns on at. If you spend a lot of time flying, leave it alone or
raise it. Nothing breaks either way, so it is safe to try both and watch your
frame counter.

## Playing online?

[Bobby](https://modrinth.com/mod/bobby) is recommended. A
server only sends you the land close by, so a huge render distance has nothing
out there to draw. Bobby remembers the places the server already showed you.
Bobby remembers the world, Meshelium draws it.

## Performance may vary

These numbers are from one computer. Yours will land somewhere else. Meshelium
helps at every distance we measured, and it earns its place when you push the
slider out.

## Thanks

Meshelium exists because of [Nvidium](https://modrinth.com/mod/nvidium) by
**MCRcortex**, who worked out how to hand Minecraft's terrain to the graphics
card and proved you really could see for miles. Nvidium is NVIDIA only, so
Meshelium rebuilds the idea for AMD and Intel as well. Go and star their project.
