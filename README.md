<p align="center">
  <img src="icon.png" align="center" width="180" alt="Meshelium">
</p>

<h1 align="center">Meshelium</h1>

<p align="center"><b>See way further in Minecraft, and get more frames doing it.</b></p>

<p align="center">
  <a href="https://modrinth.com/project/meshelium"><img src="https://img.shields.io/badge/Download-Modrinth-00AF5C?style=for-the-badge&logo=modrinth&logoColor=white" alt="Download Meshelium on Modrinth"></a>
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

## Performance may vary

These numbers come from one computer with an AMD Radeon RX 9070 XT at 1920x1080, so yours will land somewhere else. At short render distances the difference is small, and the mod earns its place when you push the slider out.

## Thanks

### Nvidium, by MCRcortex

Meshelium exists because of [Nvidium](https://github.com/MCRcortex/nvidium). MCRcortex worked out how to hand Minecraft's terrain to the graphics card and draw it all at once, and proved you really could see for miles without the game falling over. That was the hard part, and it was their idea.

Nvidium only runs on NVIDIA cards, so Meshelium rebuilds the same idea in a way that works on AMD and Intel too. The design we learned it from is theirs, and a lot of this mod is us following a path they cut first. Go and give their project a star.

### And

- **Bobby** by **Johni0702**, the perfect partner for playing online
- The **Fabric** team, for the loader and Fabric API
- Meshelium is by **Ded811**. Copyright (C) 2026
- License: **LGPL-3.0**, the same license Nvidium uses

Want the deep version? It is in [TECHNICAL.md](docs/TECHNICAL.md) and [PERFORMANCE.md](docs/PERFORMANCE.md).
