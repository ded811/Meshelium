# One source tree, several Minecraft versions

Meshelium builds for more than one Minecraft version from ONE set of
sources. This directory is where the differences live, and this file is the
whole manual.

## The rule

The shared trees (`common/`, `sodium/`, `fabric/src`, `neoforge/src`) are
written for exactly one Minecraft version, the **canonical** one, named by
`mc_canonical` in `gradle.properties`. That is the version the IDE sees, the
version a plain `./gradlew build` produces, and the version every new line
of code is written against.

Every other version is **derived** from the canonical sources at build time,
by two mechanisms and nothing else:

1. **A rename table.** `versions/mc<version>/renames.txt` lists fully
   qualified class names that merely moved or were renamed between the
   canonical version and this one, one `canonical-name this-version-name`
   pair per line. The build copies each shared `.java` file with every
   pair applied, in both `a.b.C` (imports, code) and `a/b/C` (mixin
   descriptor strings) form. Only whole names match: `GpuBuffer` never
   touches `GpuBufferSlice`. Resources are never filtered.
2. **An overlay.** Any file under `versions/mc<version>/<same path as in the
   tree>` REPLACES the file at that path in the derived copy, and a file
   with no original is simply added. Overlay files are written in their own
   version's vocabulary and are not passed through the rename table. This
   is for the handful of files whose SHAPE differs - a mixin whose target
   method changed signature, an adapter for an interface that gained a
   method - and it should stay a handful: the derived copy of a 500-line
   mixin is a 500-line file to keep in sync. Prefer moving the shared logic
   into a helper the shared tree owns, and overlaying only the thin part
   that names the version-specific seam.

So:

```
./gradlew build                 # the canonical version (gradle.properties: mc=)
./gradlew build -Pmc=26.2       # another target, derived
./gradlew runClientGameTest -Pmc=26.2 -Pmeshelium.backend=vulkan ...
```

The derived sources land under `<loader>/build/versioned/mc<version>/` and
that is what javac compiles. **Never edit them**; they are overwritten on
every build. Edit the shared tree or the overlay.

## What a version needs

- `versions/mc<version>.properties` - the toolchain for that version:
  `minecraft_version`, `loader_version`, `fabric_api_version`,
  `neoforge_version`, `sodium_base_version`, and optionally
  `sodium_also_matching` (Sodium versions proven identical to the pinned
  one, accepted without a warning). The root build derives the Modrinth
  coordinates and the reported Sodium version string from these; there is
  no second place to keep them in step.
- `versions/mc<version>/renames.txt` - may be absent (no renames).
- `versions/mc<version>/...` - the overlay, may be empty.

The canonical version has an overlay too, compiled as it is beside the
shared tree (no copy), holding the files that exist ONLY on the canonical
version: a mixin whose target has a 26.3-only shape, an adapter for an API
the older version lacks. A file that must be ABSENT on an older version is
therefore never in the shared tree - it is in the canonical overlay, and
the older version's overlay either has its own copy or nothing. So the
shared tree is exactly what every version compiles unchanged.
`checkVersionedSources` (part of `check`) refuses a canonical-overlay file
that shadows a shared one (a duplicate class), an overlay file for a derived
target that still uses a canonical name listed in the rename table, and a
rename line that does not parse.

## Telling the version apart at runtime

`MesheliumBuild.MINECRAFT_TARGET` is the version this jar was built for,
read from `meshelium-build.properties`, which the build expands into the
jar. It is in the gate's log line at startup and on the settings screen.
Prefer compile-time selection (overlay) over runtime branching on it: a
runtime branch compiles both halves against one version's classes, which
is exactly the thing this layout exists to avoid.

## Run directories

The canonical version runs in `<loader>/run`; every other target in
`<loader>/run-mc<version>`, so a world saved by one game version is never
opened, and upgraded, by another.

## Adding the next Minecraft version

1. Write `versions/mc<new>.properties`.
2. Point `mc_canonical` (and the `mc` default) in `gradle.properties` at
   it, and move the shared tree to the new vocabulary: apply the new
   version's renames FORWARD to the shared sources, and write the REVERSE
   pairs into every older version's `renames.txt`.
3. Files whose shape changed move out of the shared tree: the new shape
   into the new version's overlay, the old shape into each older version's
   overlay. Keep the shape-specific file thin and the logic in a shared
   helper (`TerrainKillSwitch` and `MesheliumChunkRendererBase` are the
   pattern), so the per-version copies are signatures, not behaviour.
4. Build every target and run each one's suite. `docs/unreleased/
   MC26.3-RECON.md` (an internal recon note, not published) is the worked
   example of how 26.3 was sized: compile
   against the new jars, javap-diff every mixin seam, then decide what is
   a rename and what is a shape change.
