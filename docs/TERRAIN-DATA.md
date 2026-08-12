# Terrain data layouts — verified from source (wave 3a)

**What this is:** the byte layouts the wave-3a data layer
(`com.deds.meshelium.terrain`) implements, each RE-DERIVED from the original
Nvidium Java writers and GLSL readers in `misc/reference/nvidium` (Alphadium
cross-checked), per the architecture study's own warning not to trust its
summarized offsets. Every layout here is also pinned bit-for-bit by
`MesheliumTerrainDataTest` — the test's longhand expected byte arrays are the
executable twin of this document.

**Verdict on the study (NVIDIUM-ARCHITECTURE.md):** every §2/§4 "verbatim"
layout this wave re-derived was CORRECT against source. Discrepancies found
are listed in "Corrections and clarifications" below — they are stale
comments in *Nvidium's own source*, not study errors, plus two study items
now resolved.

---

## 1. Packed terrain vertex — 16 bytes, 4 per quad (64 B/quad)

Implemented by `TerrainVertexCodec`. Authorities:
`sodiumCompat/NvidiumCompactChunkVertex.java:21-96` (encoder),
`shaders/terrain/vertex_format.glsl:1-44` (decoder). Both read; they agree.

| int | bits | field | encoding |
|---|---|---|---|
| i0 | 0-15 | posX | `(int)((x + 8) * 2048)`, truncating cast |
| i0 | 16-31 | posY | same |
| i1 | 0-15 | posZ | same |
| i1 | 16-23 | material | bits 16-17 = alpha-cutoff index into `{0.0, 0.1, 0.5}`; bit 18 = mip flag; bits 19-23 unused |
| i1 | 24-31 | blockLight | raw 0-255 clamped to [8, 248] |
| i2 | 0-7 / 8-15 / 16-23 | R / G / B | premultiplied by the input alpha (AO brightness); alpha byte NOT stored |
| i2 | 24-31 | skyLight | clamped [8, 248] |
| i3 | 0-15 | U | `round(u * 32768) & 0xFFFF` |
| i3 | 16-31 | V | same |

- Position domain [-8, +24) blocks around the section origin, 1/2048-block
  steps. GLSL dequant: `p * (32/65536) - 8` (vertex_format.glsl:6-13).
- UV scale 32768 = 2^15: the 16-bit field holds [0,1] exactly (1.0 → 0x8000)
  and wraps only above ~2.0. The value is host-injected into shaders as
  `TEXTURE_MAX_SCALE` (ShaderLoader.java:36).
- Light dequant: `uvec2(v.y>>24, v.z>>24) / 256.0` (vertex_format.glsl:41-44).

**Named Sodium-constant assumptions** (see `TerrainVertexCodec` Javadoc):
the [-8,24) mesh space, TEXTURE_MAX_VALUE=32768, the [8,248] light clamp,
and the colour-premultiply rounding (Sodium's ColorU8 source is not in the
reference tree — Meshelium uses round-half-up; ±1 LSB risk, pinned by test).

**Meshelium deviation (deliberate, pinned):** out-of-range positions CLAMP to
[0, 65535]. Nvidium neither masks nor clamps — a position of exactly +24
would emit 65536 and corrupt the adjacent bit-field.

## 2. Section metadata record — 32 bytes

Implemented by `SectionRecord`. Authority: `managers/SectionManager.java:102-122`
(writer); readers `terrain/task.glsl:39-51`, `occlusion/section_raster/mesh.glsl:54-73`.

```
i0 header.x  geomMinX:4 | geomSizeX:4 | chunkX:24 (signed, via <<8 / GPU >>8)
i1 header.y  geomMinY:4 | geomSizeY:4 | chunkY:9 (signed, GPU sign-extends)
             | bit17 hide | bits18-25 compactedSectionRefId | bits26-31 free
i2 header.z  geomMinZ:4 | geomSizeZ:4 | chunkZ:24 (signed)
i3 header.w  terrainAddress (QUAD units; byte offset = addr * 64)
i4 count[POS_X]      :16 | count[POS_Y]  :16      (renderRanges.x)
i5 count[POS_Z]      :16 | count[NEG_X]  :16      (renderRanges.y)
i6 count[NEG_Y]      :16 | count[NEG_Z]  :16      (renderRanges.z)
i7 count[UNASSIGNED] :16 | translucentQuadCount:16 (renderRanges.w)
```

- geomMin/geomSize: 4-bit block-granularity AABB of actual geometry;
  size = `clamp(max − min − 1, 0, 15)` — the GPU adds the 1 back
  (section_raster/mesh.glsl:68-69). min clamps [0,15], max [0,16]
  (SodiumResultCompatibility.java:26-48).
- All-zero record = empty slot. chunkY budget ±256 sections — 26.2's world
  (sections −4..19) fits with room.
- Alphadium appends 16 bytes (SECTION_SIZE 32 → 48, translucencyDataIdx at
  +32) for its SODIUM translucency level; the first 32 bytes are
  bit-identical. Meshelium ships the 32-byte record; if wave 7 adopts
  host-sorter data, grow the record the same way rather than repacking.
- *(wave 5)* Meshelium's first GPU reader is live: `terrain.task` reads both
  ivec4s as `sectionRecords[(regionId*256 + slot)*2 + 0/1]` — header.w==0
  as the emptiness gate, chunk coords for posKey/facing gates, renderRanges
  for the bin walk. The trailing-slots-zeroed and quad-0-reserved
  invariants documented above are now load-bearing on the GPU.

## 3. Region metadata record — 16 bytes (2 × u64)

Implemented by `RegionRecord`. Authority: `managers/RegionManager.java:95-126`
(writer), `:66-79` (tombstone); readers `occlusion/scene.glsl:19-37`,
`occlusion/region_raster/mesh.glsl:42-58`.

```
A bits 62-63 sizeY (0-3)   = maxY−minY of OCCUPIED section positions
  bits 59-61 sizeX (0-7)
  bits 56-58 sizeZ (0-7)
  bits 48-55 lastIdx (0-255) — HIGHEST OCCUPIED POSITION INDEX, not a count
  bits 24-47 absolute min section X = rx*8+minX, masked 24-bit (GPU sign-extends)
  bits  0-23 absolute min section Y = ry*4+minY, masked 24-bit
B bits 40-63 absolute min section Z = rz*8+minZ, masked 24-bit
  bits 30-39 transformationId (10 bits, max 1024)
  bits  0-29 written 0
```

- Tombstone = all 16 bytes 0xFF (`memSet(-1)`); GPU checks
  `data.a == uint64_t(-1)`. Exact value preserved.
- `lastIdx` invariants to preserve when wave 3b builds the region mirror:
  compacted ids ≤ lastIdx, trailing empty 32-byte slots zeroed.

## 4. Facing-bucket geometry stream + offsets[8]

Implemented by `QuadFacing`, `QuadFacingBuckets`, `SectionMeshEncoder`.
Authorities: `sodiumCompat/SodiumResultCompatibility.java:89-225` (stream
builder), `terrain/task_common.glsl:30-87` (the GPU walk — LOAD-BEARING for
face culling), `terrain/task.glsl:40-44` (relChunk sign convention).
Alphadium's task_common.glsl:45-84 keeps the identical order (it only adds a
flag that zeroes relChunkPos to disable face culling).

Stream per section: `[translucent quads][POS_X][POS_Y][POS_Z][NEG_X][NEG_Y]
[NEG_Z][UNASSIGNED]`, 64 B/quad. `offsets[0..6]` = per-bucket quad COUNTS
(deltas); `offsets[7]` = translucent count = absolute quad index where
bucket 0 starts. Camera-side gates (relChunk = sectionChunk − camChunk):
bucket 0 drawn iff relChunk.x≤0, 1 iff y≤0, 2 iff z≤0, 3 iff x≥0, 4 iff
y≥0, 5 iff z≥0, 6 always.

The study's §2 UNVERIFIED item ("exact Sodium ModelQuadFacing ordinal→
direction mapping") is MOOT for Meshelium: our encoder defines the buckets;
the enum ordinal + the GLSL gates above are the entire contract, pinned by
`bucketOrderPin()` in the gametest.

Translucent-prefix ORDER is the caller's job (back-to-front against a
camera snapshot — Nvidium sorts at build time,
SodiumResultCompatibility.java:89-164); wave 3b owns it. *Discharged
2026-08-09:* the wave-3b tap emits the prefix in the order of vanilla's
own build-time distance-sorted index buffer (built by `MeshData.sortQuads`
against the camera snapshot the compile task itself took) — same snapshot
semantics, no separately captured camera; on-screen correctness validated
in wave 7 (`VanillaMeshDecoder.sortedQuadOrder`,
VANILLA-SECTION-BUILD.md wave-3b note 6). 0-quad sections
are deleted, never encoded (SectionManager.java:58-61).

**Wave-7 prefix-ordering discharge (2026-08-09):** the prefix is now
consumed as the DRAW order — `TerrainDrawer.drawTranslucent` renders it
front-of-buffer-first (shots 60/61 are the on-screen validation the two
notes above deferred here) — and it stays vanilla's order across RESORTS:
`ResortTransparencyTask`'s re-sorted index buffer is decoded at the row-7
tap and applied as a pure byte permutation (`TranslucentPrefix.permute`
over a per-section CPU prefix copy + applied-order array; the physical
prefix always equals "vanilla's newest sorted order, materialized").
Note on the §2 Alphadium remark ("grow the record to 48 B if wave 7
adopts host-sorter data"): wave 7 DID adopt the host sorter but as
prefix-order materialization, not GPU-side index indirection — the
32-byte record is unchanged, and no translucencyDataIdx field exists.

## 5. Terrain arena (non-sparse path) + buffer sizing

Implemented by `TerrainArena` over `SegmentedManager` (ported verbatim),
behind the one-method `ArenaBacking` seam wave 3b fills with a VkBuffer.
Authority: `util/BufferArena.java` (fallback branch, `:26-28`),
`util/SegmentedManager.java`.

- Address unit = 1 quad = 4 × stride bytes (64). Quad 0 RESERVED — it is the
  reason the GPU's practical empty-section check (header.w == 0) is sound.
- Allocator: best-fit free list, merge-on-free coalescing, tail
  growth/shrink, limit = `memoryBytes / (4*stride)` quads.
- **Design-time fixes (study Q13 + §3), all pinned by tests:**
  1. **Deferred free** — `free()` parks ranges on a pending list;
     `releasePending()` (to be called by the render loop after its frame
     fence) returns them to the allocator. Fixes the use-after-free hazard
     in Nvidium's immediate free (+ its TODO at BufferArena.java:8-9).
  2. **Section-buffer sizing** — Nvidium allocates maxSections(=maxRegions×
     200)×32 B = 320 MB but addresses regionId×8192 B: only 39,062 of
     50,000 region ids fit, unguarded. `SectionRecord.sectionBufferBytes
     (maxRegions)` sizes by the addressing (50k → 409.6 MB).
  3. **Stats leak** — BufferArena.java:35-39 inflates totalQuads before
     checking alloc failure; Meshelium counts only on success.
  4. Double-free throws immediately.

## Corrections and clarifications found while verifying

1. **Stale comments in Nvidium's `occlusion/scene.glsl:6-8`** (Section
   struct): the per-field comments label header.y as chunk-Z data and
   header.z as chunk-Y. The Java writer (SectionManager.java:110-113) and
   the actual GLSL decode (`ivec3(header.xyz)>>8` with 9-bit y handling,
   task.glsl:40-43) agree on x/y/z order — the comments are wrong, the
   study's §2 layout is right.
2. **`sectionEmpty()` precedence quirk** (scene.glsl:39-42): the code reads
   `header.y &= ~0x1FF<<17` — GLSL unary `~` binds tighter than `<<`, so it
   masks with `(~0x1FF)<<17` = keep-bits-26-31, NOT the intended
   clear-bits-17-25. The check still works in practice because a live
   section's header.w (terrain address) is never 0 — quad 0 is reserved.
   The study transcribed the intent, not the actual precedence. Wave 4 must
   port the INTENT (clear hide+refId bits, or just test header.w != 0), and
   must keep the quad-0 reservation either way.
3. **Study Q13 confirmed against source** — SectionManager.java:40,46
   (maxRegions 50 000, maxSections ×200) vs RegionManager.java:85-88
   (regionId×8192 addressing). Fixed via sizing helper (above).
4. **BufferArena stats-inflation bug** (not in the study): totalQuads
   grows before the SIZE_LIMIT check (BufferArena.java:35-39). Every failed
   alloc permanently skews getUsedMB/getFragmentation — the eviction
   heuristic's inputs. Fixed in `TerrainArena`.
5. **UV field width**: the study calls UV "15-bit"; the stored field is 16
   bits wide — 32768 (=2^15) is the SCALE, so 1.0 lands exactly on 0x8000.
   [0,1] inputs never wrap.
6. **Study §9 confirmed**: Nvidium's and Alphadium's SegmentedManager /
   IdProvider / BufferArena are byte-identical modulo package rename
   (diffed directly) — the ports serve both lineages.

## Still open (NOT resolved by this wave)

- **SceneData std140 packing** (study §4/§5 UNVERIFIED): the scene UBO is a
  wave-3b/4 artifact; per study Q7 it will be re-derived with explicit
  buffer_reference members, never copied from SCENE_SIZE arithmetic. Nothing
  in 3a depends on it.
- **VK_EXT_mesh_shader inter-workgroup primitive ordering** (study §6) —
  *wave-7 update:* SIDESTEPPED, not resolved — translucent draws use one
  workgroup each (API order between draws), so nothing depends on the
  guarantee; it re-opens only for wave 9's multi-WG translucent upgrade
  (VANILLA-FRAME-PATH.md ledger 17).
- Colour-premultiply exact rounding vs Sodium's ColorU8 (±1 LSB) — verify
  if pixel-parity harness in wave 4 flags colour diffs.
