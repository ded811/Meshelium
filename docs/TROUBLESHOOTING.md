# Troubleshooting

Things that look like Meshelium problems and are not, with what to do
about them. The Modrinth page and the README stay short on purpose; this
is where the detail lives.

## Minecraft 26.3 closes without a word

**What it looks like.** The game window disappears at start-up, at the
title screen or the moment you open a menu. No crash report, nothing in
the log, no error. It may happen one launch in two, and it happens with
or without mods.

**What it is.** Minecraft 26.3's launch profile is the first to pass one
extra Java setting, and Mojang's launcher passes it to every 26.3 launch:

```
-XX:StackShadowPages=32
```

Launchers that build the command line from their own metadata leave it
out. MultiMC 0.7.0 does, and Prism Launcher 11.1 did until Prism's
launcher metadata started adding it in September 2026. Without it the
game can die on any computer. The reports upstream are
MultiMC/Launcher#5779 and PrismLauncher#6073, and it was reproduced for
Meshelium on a fresh instance with the mod removed.

**What to do.** Add `-XX:StackShadowPages=32` to the instance's Java
arguments and the game stops closing.

- MultiMC: Edit Instance > Settings > Java > tick "Java arguments" and
  paste `-XX:StackShadowPages=32`.
- Prism Launcher: restart it so it refreshes its metadata, which now adds
  the argument on its own; or paste the same text under Edit > Settings >
  Java > Java arguments.
- The official launcher already passes it.

**How you know Meshelium noticed.** When the setting is missing, Meshelium
writes a warning to the log at start-up and shows a toast on the title
screen naming the argument. When it is present, nothing is shown.

## The AMD Vulkan "graphics drivers" error on NeoForge 26.2

Covered on the Modrinth page under "NeoForge and the early loading
screen": NeoForge's early loading screen and the Vulkan renderer cannot
share a window, and Meshelium closes that screen before the game makes
its own. Nothing to do on your side. Minecraft 26.3 NeoForge ships with
the early screen off, so it cannot happen there.
