<p align="center">
  <img src="icon.png" align="center" width="180" alt="Meshelium">
</p>

<h1 align="center">Meshelium</h1>

<p align="center"><b>See way further in Minecraft, and get more frames doing it.</b></p>

<p align="center">
  <a href="https://modrinth.com/mod/meshelium"><img src="https://img.shields.io/badge/Download-Modrinth-00AF5C?style=for-the-badge&logo=modrinth&logoColor=white" alt="Download Meshelium on Modrinth"></a>
  <a href="https://github.com/ded811/Meshelium"><img src="https://img.shields.io/badge/Source-GitHub-181717?style=for-the-badge&logo=github&logoColor=white" alt="Meshelium on GitHub"></a>
</p>

<p align="center">
  <a href="https://modrinth.com/mod/fabric-api"><img src="https://img.shields.io/badge/also%20install-Fabric%20API-1976D2?style=flat-square" alt="Fabric API on Modrinth"></a>
  <a href="https://modrinth.com/mod/bobby"><img src="https://img.shields.io/badge/for%20multiplayer%2C%20also%20install-Bobby-7E57C2?style=flat-square" alt="Bobby on Modrinth"></a>
  <img src="https://img.shields.io/badge/Minecraft-26.2-brightgreen?style=flat-square" alt="Minecraft 26.2">
</p>

Frames per second at 1920x1080, same computer, same world, looking the same way, at default settings. One run with Meshelium, one without.

![Bar chart showing how many times more frames per second Meshelium gives, rising from 1.5 times at render distance 12 to 5.3 times at render distance 64](docs/fps-chart.png)

**The further you look, the bigger Meshelium wins.** Here are the actual frame rates behind that graph, in frames per second, so higher is better:

| Render distance | Meshelium FPS | Minecraft FPS | Difference |
|---|---|---|---|
| 12, Minecraft's default | 2,437 | 1,621 | 1.5x |
| 16 | 2,126 | 1,115 | 1.9x |
| 24 | 1,709 | 641 | 2.7x |
| 32, as far as Minecraft goes | **1,206** | 393 | **3.1x** |
| 48 | **761** | 205 | **3.7x** |
| 64 | **607** | 114 | **5.3x** |

Minecraft's own slider stops at 32. Meshelium unlocks 48 and 64, and we measured Minecraft out there too so the comparison stays fair.

Both columns are Minecraft's Vulkan renderer, the one Meshelium runs on, so the only thing that changes between them is Meshelium itself.

These are our numbers on our computer: one graphics card, one world, one spot. Yours will differ. What should hold everywhere is the shape, that the further you can see, the more Meshelium is doing for you.

## What you need

- **Minecraft 26.2** with the **Fabric** loader
- [**Fabric API**](https://modrinth.com/mod/fabric-api), the helper mod almost every mod wants. Put it in your mods folder too
- Windows or Linux. Sorry, no Mac
- A graphics card that supports mesh shaders, which means **AMD** RX 6000 or newer, **NVIDIA** GTX 16xx or newer, or **Intel** Arc. Newer laptop and handheld chips count too, including the Steam Deck
- If your card is older than that, Meshelium switches itself off, tells you why, and your game keeps working normally

Only you need this mod. Your friends do not, and neither does your server.

## Do this or nothing will happen

Minecraft starts up in the old drawing mode, called OpenGL. Meshelium only works in the new one, called Vulkan. Switching takes ten seconds, and you only do it once.

1. Open **Options**
2. Go to **Video Settings**
3. Find **Graphics API**
4. Choose **Prefer Vulkan**
5. **Close Minecraft and start it again.** It only changes while the game is loading

Skip this and it looks like the mod did nothing at all. If that happens, Meshelium puts a message on your screen with a button that does the whole thing for you.

## Playing online?

**Add [Bobby](https://modrinth.com/mod/bobby) to your own mods folder, next to Meshelium.**

A server only sends you the land close to you, so a huge render distance has nothing out there to draw. Bobby remembers the places the server already showed you and puts them back when you walk away. Bobby remembers the world, Meshelium draws it.

Nothing gets installed on the server. Bobby runs in your game, just like this mod, and the server never knows either one is there.

## The settings

Everything is in **Options > Video Settings > Meshelium Settings...**, and every one of them defaults to the right answer. You should not need to touch any of this.

**Meshelium Rendering** turns the whole thing on and off. Turning it off puts Minecraft back in charge and pulls your render distance down to 32, because that is as far as Minecraft can draw on its own. Turning it back on gives your distance back.

**Distance Cap** is how far the slider in Video Settings is allowed to go. Raising it does not change what you see until you also move the actual render distance slider.

**Occlusion Culling** skips terrain hidden behind other terrain. Auto switches it on past the distance where it usually starts paying off. See the note below if your graphics are modest.

**Distance Fog** is new in 1.2.0 and defaults to **Off**, which is a change worth explaining. Minecraft fades distant terrain to fog at a fixed 1024 blocks, and that number does not care how far you can see. In vanilla it is invisible, because vanilla stops at 32 chunks and the fog sits far past the horizon. At 120 chunks it eats the outer 56: the game loads them, builds them, draws them, and then paints them flat grey. Off drops that haze and keeps only the short fade right at the edge, which is the part that hides chunks appearing, so the horizon still softens rather than ending in a wall. Match View Distance keeps a haze but moves it out with your view, and never makes fog thicker than Minecraft would, so below 64 chunks it changes nothing.

Behind **Advanced** are three things you will probably never want. **Duplicate Terrain Memory** should stay on Freed; it stops Minecraft holding a second copy of the world that nothing draws, worth gigabytes past 64 chunks. Set it to Kept only if another mod needs Minecraft's own terrain buffers. **Debug Stat Logging** writes numbers to the log and changes nothing you can see. **Backend Popup** re-arms the first-run Vulkan prompt.

## Performance may vary

These numbers come from one computer with an AMD Radeon RX 9070 XT at 1920x1080, so yours will land somewhere else. At short render distances the difference is small, and the mod earns its place when you push the slider out.

**On weaker graphics, turn Occlusion Culling on sooner.** Auto leaves it off below render distance 48, which is where it starts paying on the desktop card above. On a laptop with integrated graphics (Radeon 780M) it pays much earlier and much harder: at render distance 32 it took 130 FPS to 300-400 standing on the ground, and 120 to about 165 flying. Auto would have left that switched off. If your graphics are on the modest side, set "Auto turns on at" to 32 and see what happens.

We do not yet know whether that is about integrated graphics specifically or about weaker graphics in general, so Auto still uses one number for everybody rather than guessing at your hardware. Testing on more cards will settle it.

## Thanks

### Nvidium, by MCRcortex

[Nvidium](https://github.com/MCRcortex/nvidium) is why this mod was worth attempting. MCRcortex pioneered mesh-shader terrain in Minecraft and proved you really could see for miles without the game falling over. Believing that was possible was the hard part.

Meshelium is its own mod, not a port of theirs. It targets `VK_EXT_mesh_shader` rather than the NVIDIA dialect, runs on Minecraft's Vulkan backend, and the architecture, the memory model, the culling and everything above the shaders are ours. Some shader logic is derived from Nvidium and says so in the header of each file it applies to, which is why Meshelium carries the same LGPL-3.0 licence.

MCRcortex has no involvement in Meshelium, has not endorsed it, and is not responsible for anything it does. Any bug you find here is ours. Go and star their project anyway.

### And

- **Bobby** by **Johni0702**, the perfect partner for playing online
- The **Fabric** team, for the loader and Fabric API
- Meshelium is by **Ded811**. Copyright (C) 2026
- License: **LGPL-3.0-only**, the same license Nvidium uses

Want the deep version? It is in [TECHNICAL.md](docs/TECHNICAL.md) and [PERFORMANCE.md](docs/PERFORMANCE.md).
