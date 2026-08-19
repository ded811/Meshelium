# Exposed-Surface-Shell Census of Real 26.2 Worldgen

Sizing study for the Meshelium 1.6.0 far-field cache. Premise under test: a chunk
column's exposed surfaces can be stored palette-packed in ~1-2 KB/chunk, i.e.
~1-2 GB at 512-chunk radius (1,050,625 chunks).

**Verdict up front: REFUTED for the full 3D shell (p50 4.2 KB, p95 8.5 KB zlib'd —
4.2 GB median at 512 radius). CONFIRMED for a surface-band shell (top-8 cut):
p50 0.92 KB, p95 1.97 KB → ~1.0 GB median at 512 radius. 74.3% of all exposed
faces are cave interior below (heightmap top − 8).**

## Data provenance — read this first

**The specified data source was unusable and was replaced.** Every save under
`meshelium-private\build\run\clientGameTest\saves\New World*` is **superflat**:
an exhaustive scan of the largest ("New World (3)", 16 region files, 10,201 full
chunks) found every single full chunk topping out in section −4 (surface y≈−60,
bedrock/dirt/grass + glass/nether-portal test structures; sparse scans of all 15
other saves agree). These are the gametest harness worlds — no ocean or forest
worldgen exists in any of them. The census instead ran on
`C:\Users\mrszi\AppData\Roaming\.minecraft\saves\New World (13)` — a genuine
**26.2** world (level.dat `Version.Name = "26.2"`, DataVersion 4903, modern
`dimensions/minecraft/overworld/region` layout), 8,281 present chunks of which
**4,761 are `minecraft:full`** with real worldgen (surfaces y≈48–175, aquifers,
dripstone/lush/sulfur cave biomes, ores). This is the only real-worldgen 26.2
world on the machine.

**Biome coverage caveat (loud):** the world contains plains/meadow, forest,
taiga (incl. old-growth), dark forest, river, beach, **cold_ocean** (98 full
chunks; 46 sampled), and the underground biomes (dripstone_caves in 2,710 of
4,761 full-chunk palettes, lush_caves, sulfur_caves, deep_dark). It has **no
deep ocean, warm ocean, desert interior, jungle, swamp, badlands, snowy, or
mountain-peak biomes**. Ocean numbers below are real measured cold-ocean shelf
chunks, not extrapolated forests — but they may under-represent deep oceans.

## Methodology

Custom stdlib-only Python 3.11 (`nbt.py`, `census.py`, `report.py` beside this
file): anvil sector table → per-chunk zlib payload (all chunks compression
byte 2) → own NBT parser → modern `sections[].block_states {palette, data}`
long-unpacking with `bits = max(4, ceil(log2(|palette|)))`, y ∈ [−64, 320).
Chunks with `Status != minecraft:full` skipped. Each block-state name is
classified AIR-LIKE (air/cave_air/void_air), TRANSPARENT-RENDERED (water,
leaves, glass, ice, plants/cutouts, partials — 178-name observed global
palette, full class table in `nw13.json.classes.txt`; lava counted opaque), or
OPAQUE. A face is EXPOSED iff its neighbor is air-like, or (owner opaque)
neighbor is transparent-rendered. Faces are counted exactly in all 6 directions
using 98,304-bit occupancy bitboards per chunk (whole-int shifts +
`int.bit_count()`), with **real neighbor-chunk boundary planes** — only chunks
whose 4 lateral neighbors are also full were eligible (4,489 of 4,761). Above
world top = air; below bedrock = void (no face). Engine validated against a
superflat world: exactly 256 faces/chunk, all top faces, 100% heightmap
agreement — as geometry dictates.

Sampling: 300 chunks (target ≥200) stratified so every biome group present in
the world is represented (ocean group force-included), stride-spread within
groups for spatial diversity. Per chunk we record exposed faces, heightmap-top-
only faces, shell cells (positions owning ≥1 exposed face), shell palette, and
size models: **H** (heightfield, 256×4 B + palette), **S** (4 B/shell-cell =
packed pos 2 B + palette idx 1 B + face mask 1 B, plus palette table; both
global-id and per-chunk-name variants), **S+zlib** (the actual serialized S
stream, zlib level 6 — the number that hits disk), and the same restricted to
the surface band (cells at y ≥ column-top − 8). Sanity: computed topmost-non-air
vs stored WORLD_SURFACE heightmap agreed on **76,800 / 76,800 columns
(100.00%)**; 0 sampled chunks failed to parse; 0 chunks had shell y-span > 255
(2 B packed positions are safe); max per-chunk shell palette 44 (1 B local
index safe); global palette 178 names (2 B global ids safe).

## Census results (300 chunks, real 26.2 worldgen)

Per-class sample sizes: forest 89, plains 81, ocean 46 (cold_ocean), sand 45
(beach), river 26, cave-biome-surface 13. Classes are the dominant biome of the
surface section; dominant top blocks sampled: grass_block 116, water 108,
sand 32, leaves 42.

### Per-chunk shell statistics (overall)

| metric | avg | p50 | p95 | min | max |
|---|---:|---:|---:|---:|---:|
| exposed faces total (a) | 2,986 | 2,830 | 5,744 | 415 | 9,227 |
| heightmap-top-only faces (b) | 256 | 256 | 256 | 256 | 256 |
| cave/overhang/side premium (a−b) | 2,730 | 2,574 | 5,488 | — | — |
| shell cells (d) | 1,845 | 1,754 | 3,382 | 343 | 5,388 |
| shell cells in surface band (top−8) | 443 | 414 | 745 | 256 | 1,006 |
| shell palette size (c, names) | 18.8 | 18 | 30 | 3 | 44 |

The premise's arithmetic failed on **cell count**, not on bytes-per-cell:
4 B/cell held exactly, but a real 26.2 chunk owns ~1,750 shell cells (median),
not the ~350–500 a 1–2 KB budget implies. Post-1.18 cave density is the driver:
~1,400 of those cells are below the surface band.

### Size models per chunk (bytes)

| model | avg | p50 | p95 |
|---|---:|---:|---:|
| MODEL H (heightfield floor) | 1,033 | 1,033 | 1,041 |
| MODEL S accounting, global palette (4·cells + 2·pal) | 7,417 | 7,059 | 13,573 |
| MODEL S accounting, per-chunk name palette | 7,761 | 7,419 | 14,067 |
| MODEL S real stream (global ids + headers) | 7,468 | 7,110 | 13,624 |
| **MODEL S+zlib (full shell, hits disk)** | **4,505** | **4,256** | **8,710** |
| MODEL S stream, surface band only (top−8) | 1,834 | 1,720 | 3,045 |
| **MODEL S+zlib, surface band only** | **1,047** | **941** | **2,012** |

zlib(6) achieves ~1.66× on the 4 B/cell stream. MODEL H sits at ~1.0 KB and
holds the premise, but stores no overhangs, cave mouths, cliffs walls, or tree
undersides — it is the floor, not a shell.

### Per biome class (S+zlib bytes)

| class | n | full p50 | full p95 | surf p50 | surf p95 | faces avg | cave-face share |
|---|---:|---:|---:|---:|---:|---:|---:|
| forest | 89 | 5,000 | 9,797 | 1,620 | 2,348 | 3,736 | 65.8% |
| plains | 81 | 4,123 | 7,882 | 851 | 1,349 | 2,839 | 77.0% |
| ocean (cold) | 46 | 4,036 | 7,522 | 418 | 555 | 2,713 | 90.1% |
| sand (beach) | 45 | 3,044 | 5,355 | 634 | 1,382 | 2,109 | 77.0% |
| river | 26 | 4,172 | 6,600 | 1,074 | 1,727 | 2,813 | 72.2% |
| cave-biome surface | 13 | 4,904 | 7,499 | 1,091 | 1,511 | 3,115 | 76.5% |

Forest is the most expensive class (leaf canopies are shell-cell-dense on both
sides); beach the cheapest. Note the ocean caveat: "top" is the **water
surface**, so the top−8 cut keeps the water plane and drops the sea floor
(cave share 90.1% includes the floor). If the far field must render the sea
floor, cut per-column from OCEAN_FLOOR instead; expect ocean surf sizes nearer
~2× the 418 B shown.

## Extrapolation

MODEL S+zlib per-chunk × chunk counts (mean×N is the honest total for a sum;
p50/p95×N bound it):

| store | 512-radius disc, 1,050,625 chunks | radius-120 + visited ring, 232,325 chunks |
|---|---:|---:|
| FULL shell, mean | **4.41 GB** | 998 MB |
| FULL shell, p50×N | 4.16 GB | 943 MB |
| FULL shell, p95×N | 8.52 GB | 1.88 GB |
| SURF band, mean | **1.02 GB** | 232 MB |
| SURF band, p50×N | 0.92 GB | 208 MB |
| SURF band, p95×N | 1.97 GB | 446 MB |

RAM, if the render-resident form is MODEL S uncompressed (real stream bytes):
full shell 7.29 KB mean / 13.31 KB p95 per chunk → **7.3 GB** mean at the full
512 disc (infeasible resident; must stay compressed or partial), 1.62 GB at the
visited-ring count. Surface band: 1.83 KB mean → **1.84 GB** at 512 disc,
417 MB at visited-ring.

## Verdict on the 1–2 KB/chunk premise

- **FULL 3D shell: REFUTED.** Real number is p50 **4.26 KB**, p95 **8.71 KB**
  zlib'd (mean 4.51 KB) — 2–4× the premise. 512-radius disk is ~4.2–4.4 GB
  (p95-shaped worst case 8.5 GB), not 1–2 GB.
- **Surface-band shell (top−8 toggle): CONFIRMED.** p50 **0.94 KB**, p95
  **2.01 KB** (mean 1.05 KB) → 0.9–1.0 GB at 512 radius, 2.0 GB at p95×N.
  The owner's "only a certain block offset from the surface" idea is exactly
  the lever that makes the premise true.
- MODEL H heightfield floor: ~1.03 KB/chunk flat, but loses all 3D relief.

## Cave-interior share

**74.3%** of all exposed faces (665,195 / 895,797) sit below (heightmap top −
8) — per class 65.8% (forest) to 90.1% (cold ocean, incl. sea floor; see ocean
caveat above). A far-field store that drops the sub-surface band behind a
toggle sheds ~3/4 of its faces and ~77% of its zlib'd bytes (4,505 → 1,047 B
mean) while keeping everything a distant camera can actually see except cliffs
that open below the local surface line and cave mouths deeper than 8 blocks.

## Files

- `nbt.py` — anvil region + NBT parser (stdlib only)
- `census.py` — bitboard face census engine (this document's numbers)
- `report.py` — aggregation; `scan_worlds.py`, `scan_full.py`, `probe.py` — data audit
- `nw13.json` — full per-chunk rows (300 samples); `nw13.json.classes.txt` — block class table
- `smoke.json` — superflat validation run (exactly 256 faces/chunk, 100% heightmap agreement)
