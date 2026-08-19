# Meshelium

**See further. Get more frames. Powered by mesh shaders.**

Meshelium draws Minecraft's terrain with your graphics card's mesh shaders
instead of the way the game normally does it. You get more frames per second,
and you can push the render distance far past the slider's usual limit.

## How much faster?

Measured at 1920x1080 on an AMD Radeon RX 9070 XT, same world, same view. One
run with the mod and one without:

| Render distance | Minecraft | Meshelium | Difference |
|---|---|---|---|
| 12, Minecraft's default | 1,621 FPS | **2,400 FPS** | 1.48× (+48%) |
| 16 | 1,115 FPS | **2,126 FPS** | 1.91× (+91%) |
| 24 | 641 FPS | **1,709 FPS** | 2.67× (+167%) |
| 32, as far as Minecraft goes | 393 FPS | **1,206 FPS** | 3.07× (+207%) |
| 48 | 205 FPS | **761 FPS** | 3.71× (+271%) |
| 64 | 114 FPS | **607 FPS** | 5.32× (+432%) |

The further you look, the bigger the difference. At render distance 64,
Meshelium runs more than five times faster than Minecraft's own renderer.

Both columns are Minecraft's Vulkan renderer, the one Meshelium runs on, so the
only thing that changes between them is Meshelium itself. Minecraft's own slider
stops at 32, so the last two rows use a test build that lets it go further,
giving the plain renderer something to be compared against.

## Using less graphics memory

Meshelium keeps exactly one copy of the terrain in graphics memory, packed
tight, and hands back anything it is not using after half a minute of
standing still. At render distance 64 the terrain itself sits around 540 MB,
a full 64-chunk scene fits in under 2 GB, and even 120 chunks runs
comfortably around 8.7 GB with the whole world loaded.

On laptops and handhelds with integrated graphics this counts double, because
their graphics memory is your system RAM. Whatever the terrain does not take,
the rest of your game and your computer keep.

## What you need

- **Minecraft 26.2** with **Fabric** and [Fabric API](https://modrinth.com/mod/fabric-api)
- Windows or Linux. Sorry, no Mac: Macs don't do mesh shaders on Vulkan yet
- A graphics card with mesh shaders. Meshelium asks your driver for the feature
  rather than checking a list of models, so anything that reports it will work.
  In practice that means **AMD** RX 6000 or newer, **NVIDIA** RTX 20xx or newer,
  and **Intel** Arc, plus recent laptop and handheld chips. Keeping your
  graphics driver current matters as much as the card
- If the feature is missing, Meshelium turns itself off and tells you why. Your
  game keeps working normally

Client side only. Your friends do not need it, and neither does your server.

## Do this or nothing will happen

Minecraft starts in the old drawing mode, OpenGL. Meshelium only works in the
new one, Vulkan.

1. Open **Options**
2. Go to **Video Settings**
3. Find **Graphics API**
4. Choose **Prefer Vulkan (Experimental)**, which is Minecraft's own name for it
5. **Restart Minecraft.** It only changes while the game is loading

Skip this and it looks like the mod did nothing. If that happens, Meshelium puts
a message on screen with a button that does it for you.

## Settings

There is a **Meshelium Settings** button at the top of Video Settings. There is
also a reset button, which asks twice before it does anything.

**Distance Cap** is how you get past 32. It widens Minecraft's own render
distance slider, up to 120 chunks. Raising the cap changes nothing on its own;
you still move the normal slider afterwards.

**Distance Fog** ships **Off**, and that is a change from how Minecraft looks.
Minecraft fades distant terrain to fog at a fixed 1024 blocks no matter how far
you can see, which is invisible at short distances and covers most of the view
past 64 chunks. Off removes that haze and keeps the short fade at the very edge,
so the horizon still softens instead of ending in a wall. Match View Distance
keeps a haze but moves it out with your render distance. Minecraft Default puts
it back exactly as the game has it.

**Occlusion Culling** asks your graphics card which terrain is hidden behind
other terrain and skips it. Asking costs a little while you are moving and
almost nothing while you stand still, so it wins when plenty really is
hidden. Measured here, it pays when you are on the ground
looking out across a long view, and costs a little from a high camera looking
down, where almost nothing is behind anything. Left on **Auto** it switches
itself on at 48 chunks. If you mostly play at ground level, try lowering that.
If you spend a lot of time flying, leave it alone or raise it. Nothing breaks
either way, so it is safe to try both and watch your frame counter.

Behind **Advanced**: **Idle Memory Trim** is the give-back described above,
on by default; turn it off only if another mod misbehaves when graphics
memory shrinks. **Greedy Meshing** merges neighbouring block faces that look
identical so there is less to draw. It helps most with Smooth Lighting off,
where it removes about one face in six, and the picture stays exactly the
same; it ships off while it proves itself. **Duplicate Terrain Memory** is
what frees Minecraft's unused second copy of the world; leave it on Freed
unless another mod needs Minecraft's own terrain buffers. **Cull Tiny
Plants Beyond** and **Cull Sub-Pixel Detail Beyond** are two distance
sliders that skip drawing things too small to see at range, from grass
tufts down to any face smaller than one pixel on your screen. Both ship
Off and apply instantly, so drag until you notice the picture change and
back off a step. **Smart Leaves Beyond** skips the leaf faces buried
inside tree canopies past a distance, keeping the see-through look; it
ships on at 16 chunks because the buried faces cannot be seen from
outside, trees regain full detail as you approach, and 0 turns it off.
**Solid Leaves Beyond** goes further: past your chosen distance leaves
build fully solid, the way Fast graphics draws them; it ships Off.

Everything applies as soon as you change it, apart from the backend popup, which
waits for the next launch. Switching Meshelium off and on, changing Duplicate
Terrain Memory, or flipping Greedy Meshing reloads the terrain, so chunks
rebuild for a few seconds.

## Playing online?

[Bobby](https://modrinth.com/mod/bobby) is what makes long distances work on a
server. A server only sends you the land close by, so a huge render distance has
nothing out there to draw no matter how fast your graphics card is. Bobby
remembers the places the server already showed you and puts them back. Bobby
remembers the world, Meshelium draws it.

## Performance may vary

These numbers are from one computer. Yours will land somewhere else. Meshelium
helps at every distance measured here, and it earns its place when you push the
slider out.

## Thanks

[Nvidium](https://modrinth.com/mod/nvidium) by **MCRcortex** is why this was
worth attempting. They pioneered mesh-shader terrain in Minecraft and proved
you really could see for miles without the game falling over.

Meshelium is its own mod, not a port of theirs. It targets a different
extension, runs on Minecraft's Vulkan backend, and its architecture, memory
model and culling are ours. Some shader logic is derived from Nvidium and says
so in the header of each file it applies to, which is why Meshelium carries the
same LGPL-3.0 licence.

MCRcortex has no involvement in Meshelium, has not endorsed it, and is not
responsible for anything it does. Any bug you find here is ours. Go and star
their project anyway.
