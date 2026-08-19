# Far Field: the 1.6.0 surface-mesh cache (DESIGN DRAFT for owner sessions)

Status: DRAFT. This document is the agenda for the design sessions the
owner asked for ("tons of human input"). Nothing here is committed
design until the owner has walked the OPEN QUESTIONS list at the
bottom. Premise measurements land in place as they finish; anything
marked CENSUS-PENDING is being measured right now, not assumed.

## 1. The mandate (owner, 2026-08-19, recorded in SPEEDUP-CANDIDATES-2026-08.md)

Beyond render distance, chunks never load as chunks. Meshelium keeps
its OWN store of exposed surfaces only, and draws them through 3-4
player-configurable levels, each with a distance slider and per-feature
toggles, all configurable LIVE. Target 512 chunks. Accuracy explicitly
does NOT matter at these distances (owner). Hard requirements, verbatim
from the decision blocks:

- **Master OFF switch. Off means zero cost**: no storage, no background
  work, no memory, exactly today's behavior.
- Every level defaults OFF (uncertainty rule) until the owner tunes
  look-to-frames in person.
- **Two radii**: the LOD RENDER radius (how far cached terrain draws,
  up to 512) and the GENERATION radius (how far background fill works,
  e.g. 120). Beyond the generation radius only visited terrain ever
  appears.
- **Source modes** (player setting): visited-only (cache fills only
  from chunks the player was actually sent; nothing invented) vs
  background-generate (seed-driven fill).
- Per-world/per-server cache folder, size readout in the UI, a
  clear-cache button, and a seed input field for multiplayer.
- Owner also floated: cache "only a certain block offset from the
  surface" (a cave-interior cutoff), a texture simplification slider
  down to flat color, half-scale (2x2x2) remesh, flat/sheet oceans,
  solid leaves at range, 2x2 shading rate, a column-heightfield
  horizon tier.

Engineering rules that bind this feature specifically:

- **Sliders act in BOTH directions** (the ground-cover lesson): raising
  a slider must visibly rebuild/reveal, not only apply to future
  builds. Every level slider needs promote AND demote paths from day
  one.
- Voxy is ARR: behavior may be observed, no code, no formats. Distant
  Horizons is LGPL-3.0: study freely, write our own. Sodium: clean-room
  rule as always, no references anywhere shipped.

## 2. Shape of the system

Three planes, deliberately separable so the master switch can amputate
all of them:

```text
   [SOURCES]              [STORE]                 [RENDERER]
   visited chunks   -->   shell cache        -->  far-field levels
   (retention-time        (disk, per-world,       (own draw path in
    extraction)            palette-packed          the armed drawer,
   background worldgen     shell records)          scale-tagged codec)
    (seed, radius-capped)
```

- **Extraction is free-riding**: we already walk every section's block
  content at build time (SectionBuildTap) and we already know when
  vanilla is about to unload a chunk (retention). Visited-only mode
  extracts the shell AT RETENTION TIME, while the ClientLevel chunk
  still exists — the same trick the mip-section plan (D3) uses. No
  extra chunk loads, ever.
- **The store is not a world format.** It stores exposed surfaces only:
  for each chunk column, the set of block faces that touch air or
  fluid, palette-packed. It cannot reconstruct block data and never
  pretends to; it exists only to be meshed.
- **The renderer is our existing drawer.** The far field draws through
  the same 16-byte quad codec with the scale tag in the section
  record's free header bits (26-31), the same culling, the same
  occlusion. Far records are just more regions, at coarser scale, with
  fewer features. No second renderer.

## 3. The store

### 3.1 Record content (per chunk column) — CENSUS COMPLETE 2026-08-18

Measured on real 26.2 worldgen (300 stratified chunks from a genuine
26.2 save; parser validated at 100.00% agreement with the chunks' own
stored heightmaps plus a superflat oracle run; full method and tables
in SHELL-CENSUS-2026-08.md, scripts + per-chunk rows archived at
`../misc/shell-census-2026-08/`). Note for posterity: every
gametest save turned out to be superflat — the known harness blind
spot — so the census ran on a real world save. Ocean numbers are real
measured cold-ocean shelf chunks; deep/warm ocean, desert, jungle,
swamp, and peaks were absent from the sample world.

Two encodings measured, both palette-packed, 4 bytes per shell cell
(packed position + palette index + face mask), zlib level 6 on disk:

- **MODEL H (heightfield)**: 256 x (height + palette index + water
  depth) ≈ **1.03 KB/chunk flat**. Cannot represent overhangs, cave
  mouths, or cliff walls. This is the L3/L4 storage floor.
- **MODEL S (sparse shell), FULL 3D**: **p50 4.26 KB, p95 8.71 KB per
  chunk zlib'd.** The old ~1-2 KB/chunk premise is REFUTED for the
  full shell — not on bytes per cell (4 B held exactly) but on cell
  count: a real chunk owns ~1,754 shell cells at median, and post-1.18
  cave density is the driver (~1,400 of them are subsurface).
- **MODEL S, SURFACE BAND (cells at y >= column top - 8)**: **p50
  0.94 KB, p95 2.01 KB per chunk zlib'd.** The premise is CONFIRMED
  here. **74.3% of all exposed faces sit below (heightmap top - 8)**
  (65.8% forest to 90.1% cold ocean) — the owner's "only a certain
  block offset from the surface" idea is not a nice-to-have, it is
  the lever that makes the whole 512 target affordable, shedding ~3/4
  of faces and ~77% of disk bytes while keeping everything a distant
  camera can see except deep cave mouths and cliffs opening below the
  local surface line.

Ocean caveat that shapes the cut rule: with a WORLD_SURFACE-style cut,
"top" is the water plane, so the band keeps the surface and drops the
sea floor. The cut must be per-column from OCEAN_FLOOR under water
(ocean chunks then cost roughly 2x their measured 418 B surf figure —
still the cheapest biome class).

### 3.2 Cache keying and lifecycle

- Folder: `<gamedir>/meshelium/farfield/<world-key>/` where world-key
  is the singleplayer folder name, or for multiplayer the server
  address (sanitized) + dimension id. One folder per dimension.
- Region-file style grouping (e.g. 32x32 chunks per file) so the clear
  button and the size readout are cheap directory operations, and
  partial invalidation (a re-visited chunk that changed) is a
  region-local rewrite.
- Every record carries its source tier. **Truth ordering is
  server-sent > cached > seed-generated**: a visited extraction always
  overwrites a generated record; a generated record never overwrites a
  visited one.
- Size readout + clear button live on the far-field settings screen;
  readout is the folder's byte total, computed off-thread, cached.

### 3.3 The math that frames everything (census numbers, mean x N)

Disk, MODEL S+zlib, from the measured per-chunk means:

| store         | 512 radius (1.05M chunks)     | 120 radius + visited ring (232K) |
|---------------|-------------------------------|----------------------------------|
| FULL 3D shell | **4.4 GB** (p95 bound 8.5 GB) | ~1.0 GB (p95 bound 1.9 GB)       |
| SURFACE BAND  | **1.0 GB** (p95 bound 2.0 GB) | ~232 MB (p95 bound 446 MB)       |
| MODEL H floor | ~1.1 GB flat                  | ~240 MB flat                     |

(The "1-2 GB at 512" note in SPEEDUP-CANDIDATES is now pinned: true
for the surface band, 2-4x short for the full shell.)

RAM/VRAM is the harsher budget: the uncompressed sparse-shell stream
is 7.3 KB/chunk mean — **7.3 GB resident at the full 512 disc**,
infeasible; even the surface band is 1.8 GB resident. So the store
stays zlib'd at rest and the RENDER-resident form thins with distance
by design: L1 holds real meshed quads for a modest ring, L2 half-scale
cuts quads ~8x, and L3 is a heightfield strip that mesh shaders
synthesize from ~8-16 B/column SSBO records (D4) — 2-4 KB/chunk
resident, no stored quads at all. The level ladder is not a look
preference; it is what makes 512 fit in memory.

Background generation rate budget: filling a 120-radius disc (58K
chunks) at even 50 chunks/sec is ~20 minutes of background work. That
is fine for an idle-time system but it must pace by staged volume (the
quiet-means-volume lesson): a live world never stops ticking, so the
pacer keys on frame headroom, not idleness.

## 4. Sources

### 4.1 Visited-only (mode 1, the honest mode)

Extract at retention/unload from real chunks the server sent. Nothing
invented, works on any server, no seed needed. Multiplayer reality:
the server's view distance caps how much ever becomes "visited", so
this mode grows the far field only where the player has flown.
(Prior art note: Bobby caches full server chunks client-side to fake a
bigger view distance — heavyweight, full block data. Our store is the
light version: surfaces only, drawn by our own renderer, no fake
server. If Bobby is present we coexist: it extends real chunks, we
draw beyond whatever the effective near field is.)

### 4.2 Background-generate (mode 2, singleplayer-first)

Seed-driven fill out to the generation radius. Two very different
sub-cases:

- **Singleplayer**: the integrated server owns the real ChunkGenerator.
  The clean design runs our own low-priority generation OFF the
  server's chunk pipeline (generate into a throwaway, extract the
  shell, discard — never insert into the server's chunk map, never
  write vanilla save data). This is the technically hard part of 1.6.0
  and needs its own recon doc against the 26.2 worldgen entry points
  before any code.
- **Multiplayer + seed input**: vanilla worldgen from a typed seed
  approximates a vanilla server well and a modded/datapack server not
  at all. Records land in the lowest truth tier and get overwritten by
  reality as the player visits. The UI must say plainly: "guessed from
  seed, corrected as you explore."

### 4.3 What background generation is NOT

No chunk is ever loaded into the client or server world for far-field
purposes. No entity spawns, no ticking, no lighting engine runs on our
account (shell shading uses sky-exposure + a flat ambient — accuracy
does not matter at these distances, per the mandate).

## 5. The level ladder (straw proposal for the sessions)

Levels are bands by distance from camera, each with its own slider
(chunks) and feature toggles, all live. Bands close-to-far; each level
defaults OFF.

- **L0 — today's near field.** Real chunks, full detail, the shipped
  renderer + the shipped sliders (Smart/Solid Leaves etc.). Untouched
  by this feature.
- **L1 — retained shell, full scale.** Beyond vanilla render distance:
  shell store meshed at 1:1 by the same GreedyMesher. Features: solid
  leaves forced, flat ocean top-sheet (D6), ground cover dropped.
  Look: near-identical silhouette, simplified innards.
- **L2 — half-scale (D3 mip-sections).** 2x2x2 downsample, remeshed,
  scale tag = 1. Features: texture simplification slider starts here
  (mip clamp), optional 2x2 shading rate (G1). ~8x volume per section,
  quads way down. Quarter-scale (tag 2) is the same machinery again if
  the sessions want an L2.5.
- **L3 — column heightfield horizon (D4).** 8-16 B/column SSBO, mesh
  workgroups synthesize top + skirt quads directly; no stored meshes at
  all. Flat-color or heavily mip-clamped. This is what makes 512
  affordable; weeks of work, and its kill test (fog/sky arithmetic at
  the target fov) runs BEFORE it is built.

Both-directions rule, concretely: each level boundary keeps demote AND
promote walkers (the leaf-tier walker generalizes — it already demotes;
its missing promote arm is the same fix the ground-cover revisit
needs). Moving any slider re-tiers affected resident sections within a
bounded number of pump cycles, both ways.

Fog: drawing to 512 means fog cannot end at the vanilla distance. Our
shaders already receive fog params through the scene UBO; far levels
get their own fog curve (push-out toward the far plane, blend to sky
color at the horizon). Vanilla's fog for the near field is untouched.
This needs an owner look-tuning session by construction.

## 6. Master OFF (the zero-cost proof)

OFF is the default and must mean: the store never opens a file, the
extractor never runs (one branch in the retention path), no thread
exists, no allocation happens, the settings screen shows the switch and
nothing else active. The suite gets a leg asserting the zero-cost
claims it can see (no farfield folder created, no background thread
named, counters all zero after a full run with the switch off).

## 7. Phasing proposal (for the sessions, not decided)

1.6.0 is too big for one release if it means all four levels + both
source modes + seed worldgen. Straw phasing:

- **1.6.0**: store + visited-only extraction + L1 (full-scale shell
  beyond rd) + master switch + cache UI (folder, readout, clear). The
  far field becomes REAL and the storage format proves itself.
- **1.7.0**: L2 half-scale + texture simplification + shading rate;
  promote/demote walkers generalized.
- **1.8.0**: background-generate (singleplayer), generation radius,
  seed input for multiplayer; L3 horizon tier last, behind its kill
  test.

Counter-argument the owner may make: visited-only L1 alone shows little
on a fresh world (nothing cached yet). If first-boot wow matters more
than format soak, background-generate moves up. Session topic.

## 8. Kill tests and premises (cheap experiments first)

- **Shell size census** — DONE 2026-08-18 (sections 3.1/3.3). Full 3D
  shell refuted at ~4.3 KB/chunk; surface band confirmed at ~1 KB. The
  surface-band cut graduates from "toggle idea" to the store's default
  posture, pending the owner's call on question 4.
- **Fog/sky arithmetic** (before L3): at the owner's fov and a pushed
  fog curve, how many pixels does the 256-512 band actually cover?
  Screenshot arithmetic, no code.
- **D3 downsample bench** (before L2): offline downsample+remesh of one
  captured plains region; kill if LOD/full quad ratio < 2.5x or > 2
  ms/group (already specified in SPEEDUP-CANDIDATES).
- **Retention-time extraction cost** (before 1.6.0 code): measure the
  shell walk added to one section build; it must hide inside the
  existing build pass budget.

## 9. Open questions for the owner (the design-session agenda)

1. **Level count and band edges**: 3 or 4 levels? Straw defaults: L1
   at rd..96, L2 96..192, L3 192..512 — or should band edges be free
   sliders with no fixed count?
2. **First-boot experience**: is visited-only-first phasing acceptable
   (far field grows as you fly), or must 1.6.0 ship background-generate
   so a fresh world shows the horizon immediately?
3. **Staleness**: a cached shell from a visit last month is wrong where
   the world changed. Acceptable at range (accuracy waived), or do we
   want a max-age / re-extract-on-revisit policy row?
4. **Cave cutoff — now a data-backed recommendation**: caves are 74%
   of faces and 77% of bytes. Proposed default: surface band ON
   (OCEAN_FLOOR-based cut under water so sea floors survive), full
   shell available as the "off" position of that toggle for players
   with disk to burn. Owner sign-off needed on the default and on the
   8-block offset value (slider or fixed?).
5. **Disk cap**: hard cap slider with LRU eviction by region distance,
   or unbounded until the clear button? (512-radius multiplayer roaming
   can accumulate multiple GB.)
6. **Texture simplification floor**: mip-clamp only, or all the way to
   per-quad flat color at L3? (Flat color halves the L3 bandwidth story
   and looks like a painting; needs the owner's eyes.)
7. **Seed-mode honesty UX**: how loudly should guessed terrain be
   labeled? (Settings-screen note only, or a subtle first-time toast?)
8. **Where does the far field END vertically**: full -64..320 shell, or
   surface band only at L2+ (ties into question 4)?
9. **Multiplayer default**: should visited-only be forced-default on
   servers (no seed guessing unless the player types a seed), per the
   truth-tier principle?
10. **The 512 number itself**: is 512 chunks (8192 blocks, ~5x vanilla
    max) the real target, or a stretch goal the sliders merely allow?
    L3's existence hangs on this answer.
