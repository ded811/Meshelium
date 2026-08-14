<!-- Provenance: produced 2026-08-13 by a 19-agent design workflow: five
recon agents over the allocator, the Vulkan side, the shaders, the VRAM
probes and the vanilla seam; three competing multi-buffer designs; a VRAM
guard design; nine adversarial reviewers (three lenses per design); one
synthesis. Shader prototypes were really compiled with glslc and validated
with spirv-val - the artifacts are cited inline.

TWO CAVEATS BEFORE YOU TRUST A LINE OF THIS.

1. The agents read MesheliumExtendedRd.java WHILE it was being changed. The
   automatic render-distance restore they may reason about was deleted in
   13d7c2e; the backoff now only ever lowers. Anything here about restore
   hysteresis is stale.
2. Section 1's headline bug in MesheliumScaling.addressable() was REAL and is
   already fixed, in 7158a29 (default path) and c4e2501 (the two property
   overrides, which return early and the first fix missed). Section 3.7's
   proposed restructure of arenaCeilingBytes() has NOT been done; only the
   floor-versus-clamp inversion was.

Everything else is a proposal awaiting the owner's go-ahead. -->

I verified the load-bearing claims against the repo and the on-disk artifacts before synthesizing. Findings that changed the call: `doc_form.mesh:79-84` really does carry the grouped `#if > 4` guard, and the only `.spv` files beside it are `docform_{1,2,3,4,8}.spv` — no 5/6/7, which is the fatal bug reproduced on disk; `log_translucent_4.txt` is a compile-failure log for a configuration reported as OK; `MeshShaderDeviceSupport.java:304-310` already makes a chained `vkGetPhysicalDeviceProperties2` call, so the extra probes are cheaper than two reviewers thought.

---

# MESHELIUM PLAN OF RECORD — Multi-Buffer Terrain Arena + VRAM Guard

*Synthesis of three designs, nine adversarial reviews, and one VRAM-guard design. Every repo claim below carries a file:line I read in this session. Vulkan claims are marked SPEC-CITED (VUID nameable from `validusage.json` 1.4.357, commit `d184375`), PROFILE-SOURCED (value comes from a LunarG profile JSON, which is LunarG's encoding of the spec's Required Limits table — the spec table itself is not on this machine), or UNVERIFIED.*

---

## 1. RECOMMENDATION

**Build the constant-index descriptor-array split. Take Design 3 ("Striped Arena") as the base and fold Design 1's descriptor-write fixes into it. Ship it in four phases, and do not raise the effective VRAM ceiling in the same phase as the split.**

The single strongest reason: the constant-index switch is the only mechanism in the whole package that survived all nine reviews un-refuted, and it was independently reproduced by six reviewers from disassembly — the module declares exactly one capability (`MeshShadingEXT`), every `OpAccessChain` into the block array carries a literal `%int_N` first index, `spirv-val --target-env vulkan1.2` passes, and one reviewer additionally proved the property survives `glslc -O`, `-Os` and `spirv-opt -O`. That means it satisfies **VUID-StandaloneSpirv-StorageBufferArrayDynamicIndexing-10129** (SPEC-CITED) with **no device feature, no extension, no VMA flag, no descriptor pool, and no change to anything vanilla owns** — and it *deletes* rather than answers the unresolved "what is an invocation group for a mesh shader" question. Against a codebase that has already shipped one shader silently invalid on two of three vendors, paying one `OpSwitch` per quad to make a spec question that nobody on this desk can answer from primary sources simply not exist is the correct trade. Everything else in the package is plumbing, and plumbing is testable.

Secondary, and nearly as decisive: Phase 1 of this design is a **standalone bug fix that should ship regardless of whether the split ever lands**. `MesheliumScaling.java:218-219` is `long clamped = Math.min(bytes, limit >> 20 << 20); return Math.max(ARENA_CEILING_FLOOR_BYTES, clamped);` with `ARENA_CEILING_FLOOR_BYTES = 256L << 20` (`:126`), and `arenaInitialBytes()` (`:230-239`) then returns `min(256 MiB, ceiling) = 256 MiB`. On a device reporting the 128 MiB required minimum for `maxStorageBufferRange` (PROFILE-SOURCED, `VP_LUNARG_minimum_requirements.json`), the floor is applied *after* the clamp and defeats it: the mod allocates a 256 MiB arena and binds it at offset 0 with `VK_WHOLE_SIZE` (`TerrainDrawer.java:2670` translucent, `:2889` opaque). That is not merely the wave-14 invisible-terrain failure reconstructed — it is an outright violation of **VUID-VkWriteDescriptorSet-descriptorType-00333** (SPEC-CITED), which the validation layer would flag. The adjacent comment at `:215-217` claiming such a device "will simply go passive early rather than silently losing terrain" is false and should be deleted with the bug.

---

## 2. WHY NOT THE OTHERS

**Design 2, PointerArena (buffer device address) — rejected.** Its own performance section concedes there is no measured win, and its data-correctness reviewer landed the killing blow: 64 sequential 256 MiB device allocations from one heap will in practice sit at adjacent virtual addresses, so the "loud failure" that was supposed to compensate for giving up every bounds check is not reliable — an overrun off block *k* reads block *k+1*'s live, mapped, plausibly-decodable geometry. Wave 14 lost terrain; BDA can draw lies, and that inversion cannot be edited away because it is what BDA *is*. Its headline sentence ("`maxStorageBufferRange` stops applying because there is no descriptor") is refuted by its own §8, which keeps pushing block 0 on binding 0 at `VK_WHOLE_SIZE`. And its integration reviewer refuted the "must own raw memory" pillar outright: `VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT = 32` is javap-verified in `lwjgl-vma-3.4.1` and a Meshelium-owned `VmaAllocator` on vanilla's existing `VkDevice` gets device addresses without touching vanilla's allocator — so the most dangerous element of the design was also unnecessary. Its one genuine advantage (no uniformity question) is available for free from the constant-index switch. **Do not build it.** Keep the analysis: if the descriptor route ever fails on real NVIDIA/Intel hardware, this document is the fallback, and its `-Dmeshelium.tune.arenaBda=false` bisect discipline is the right shape.

**Design 1, Strided Block Arena — rejected as the base, harvested for parts.** Two of its three reviewers independently reproduced a fatal compile failure, and I confirmed the artifact on disk: `doc_form.mesh:79-84` puts cases 4–7 under one `#if MESHELIUM_ARENA_BLOCKS > 4`, so at N=5 the shader contains `case 6u: terrainBlocks[6]` against an array declared `terrainBlocks[5]`. The `.spv` files sitting next to it are `docform_1/2/3/4/8` — **N=5, 6 and 7 produced no output**. N=6 is the 24 GiB card (RTX 4090 / RX 7900 XTX) under the design's own N formula, i.e. the flagship configuration the change exists to serve, and the presented compile matrix {1, 4, 8} is precisely the subset that hides it. The design also asserted "N ≤ 8 is safe on any conformant device" from `maxPushDescriptors` while never naming `maxPerStageDescriptorStorageBuffers`, whose required minimum is **4** (PROFILE-SOURCED) — the identical unprobed-limit failure shape as the shipped `geometryShader` bug. Its evidence trail is also not reproducible: `log_translucent_4.txt` on disk is a **failure** log (`'max_vertices' : too large`, `missing #endif`, `SPIR-V is not generated`) for the configuration the document reports as OK, `log_taskcull_4.txt` contains only the filename rather than the quoted `== OK` line, and the compiled `split.mesh` uses a per-element `meshelium_vertex(blk, elem)` function with macro `MESHELIUM_ARENA_SHIFT`, not the per-quad `MESHELIUM_ARENA_BLOCK_SHIFT` form printed in the document. **Harvested: the `descriptorCount(info.remaining())` fix, the `byteOffset` → `byteOffsetInBlock` rename, the dynamic-index negative control as a permanent fixture, and the transitional-ceiling PR discipline.**

**Design 3 was not sunk — it was wounded, and the wounds are repairable.** Its fatal was that the invariant the entire split rests on (`physicalBytes[k] <= blockBytes`, so every local address is `< 2^blockShift`) is stated nowhere and enforced nowhere, while `arenaInitialBytes()` (`MesheliumScaling.java:230-239`) is clamped only to the *ceiling* and is operator-settable via `meshelium.tune.arenaInitialMiB` / `meshelium.test.arenaMiB`. Once `addressable()` stops clamping, a large card's ceiling far exceeds `blockBytes` and block 0 gets a `SegmentedManager` wider than its address stride — wrong upload offset, wrong free target, and a shader read from a different live buffer. That is one added guard clause, not a redesign. Its sizing model (§0) is separately refuted and must be deleted: `docs/VANILLA-SECTION-BUILD.md:873-878` records the wave-9 plains rd-32 calibration as "51 MiB live ≈ 835k quads over ~3,274 resident sections ≈ 255 quads/section" — 16.3 KiB/section, not the 55.1 KiB/section the design anchors on, and 55.1 KiB/section is 882 quads/section, which is the top of the *real-terrain* range given two lines later. The model uses a real-terrain byte density as its plains baseline and then multiplies by the real-vs-plains section-count ratio on top. **Delete the extrapolation table.** The motivation stands on two verified facts without it.

---

## 3. THE DESIGN

### 3.0 What the motivation actually is (post-refutation)

Three verified reasons, no extrapolation:

1. **`maxStorageBufferRange` is not reliably ≥ 2 GiB on the desktop fleet.** `VP_LUNARG_desktop_baseline.json` reports `1073741820` (1 GiB − 4) for the 2022/2023/2024 blocks and `134217728` (128 MiB) for 2026 (PROFILE-SOURCED, reproduced by three reviewers). The repo's own comment at `MeshShaderDeviceSupport.java:285` — "desktop drivers report 0xFFFFFFFF" — is a fact about the RX 9070 XT, not about the fleet.
2. **There is a live spec violation today** (§1 above, `MesheliumScaling.java:218-219`).
3. **The owner's own field incident.** `MesheliumScaling.java:186-187` and `:194-197` record that a real 16 GiB card grew the arena to 4,374 MiB with terrain invisible at rd 96 and 120. Caveat to carry honestly: 4,374 MiB is the arena *allocation* after a 1.5× geometric step (`TerrainResidency.java:1457`), not live geometry — but allocation is exactly what gets bound at `VK_WHOLE_SIZE`, so the addressability wall was genuinely reached.

Everything else — copy-free growth, a bounded transient peak, and the only route to arena *shrink* — is a real benefit but is not the argument.

### 3.1 Address space and block geometry

```
virtualQuad = (block << SHIFT) | local          SHIFT = log2(blockBytes / QUAD_STRIDE)
```

`TerrainVertexCodec.java:92,94` give `VERTEX_STRIDE = 16` and `QUAD_STRIDE = 4 * VERTEX_STRIDE = 64`, so any power-of-two byte block is an exact power-of-two quad count and `SHIFT = log2(blockBytes) - 6`. The 32-bit absolute quad address is **unchanged**: `SectionRecord`'s `header.w` keeps its exact meaning, the task payload is unchanged, and quad 0 of block 0 remains the reserved sentinel (`TerrainArena.java:85-89` asserts `allocQuads(1) == 0` at construction and still does).

This directly contradicts the javadoc at `MesheliumTerrainGpu.java:336-346`, which dismisses multi-block as "a record-format and shader change". The premise is wrong; rewrite that javadoc as part of this work.

**Block size — four clamps, not one.** Design 3 argued 2 GiB is "forced by arithmetic" because `maxStorageBufferRange` is a uint32. Two reviewers refuted that: `maxStorageBufferRange` bounds a *binding range*, not a *buffer size*.

```java
static long computeArenaBlockBytes(long maxStorageBufferRange,
                                   long maxBufferSize,          // 0 = not reported
                                   long maxMemoryAllocationSize) {
    long cap = ARENA_BLOCK_PREFERRED_BYTES;                     // 2 GiB
    if (maxStorageBufferRange > 0) cap = Math.min(cap, maxStorageBufferRange);
    if (maxBufferSize > 0)          cap = Math.min(cap, maxBufferSize);
    if (maxMemoryAllocationSize > 0) cap = Math.min(cap, maxMemoryAllocationSize);
    return Math.max(QUAD_STRIDE, Long.highestOneBit(cap));
}
```

- `maxBufferSize` (maintenance4 / core 1.3): **VUID-VkBufferCreateInfo-size-06409** (SPEC-CITED) — "size must be less than or equal to `VkPhysicalDeviceMaintenance4Properties::maxBufferSize`". Required minimum 1 GiB (PROFILE-SOURCED), and LunarG's desktop-baseline 2026 reports exactly `2147483648` — zero margin against the preferred block.
- `maxMemoryAllocationSize` (`VkPhysicalDeviceVulkan11Properties` / Maintenance3): required minimum 1 GiB (PROFILE-SOURCED), and desktop-baseline 2023 reports `1610612736` (1.5 GiB), **below the preferred 2 GiB block**. One reviewer's grep found **zero VUIDs** mention this limit, so exceeding it is invisible to the validation layer and arrives as an opaque `VkResult` — precisely the "fits in memory is not the same as addressable" class this whole change exists to remove, displaced one limit sideways.

**Own the `highestOneBit` cost in the doc.** On a device reporting `1073741820`, blocks are 512 MiB and N=8 totals 4 GiB — no better than today's clamp. The power-of-two decode is what keeps the shader a shift-and-mask; that is the trade, and it should be written down rather than discovered.

**`ARENA_MAX_BLOCKS = 16.`** 16 × 2^25 quads = 2^29 absolute quads; `quad << 2` = 2^31, inside uint, and comfortably inside a signed host int, so `ALLOC_FAILED = 0xFFFFFFFF` (`TerrainArena.java:49`) can never collide. 16 is chosen over 8 not for the dev card (which needs 4) but for small-block devices, where 512 MiB blocks need more of them to reach the same ceiling. The descriptor probes below clamp N far below 16 on any device that cannot afford it.

### 3.2 N is frozen at device creation, clamped by four probed limits

Pipelines are created once per variant and cached in statics for the device's lifetime, so `descriptorCount(N)` and `MESHELIUM_ARENA_BLOCKS` are baked at first draw. N must therefore be resolved **once, at device creation, from the full default-policy ceiling** — never from a live-flippable ceiling.

```java
int n = (int) ceilDiv(defaultPolicyCeilingBytes, blockBytes);
n = Math.min(n, maxPushDescriptors - 11);                    // VUID-...-flags-00281
n = Math.min(n, maxPerStageDescriptorStorageBuffers - 2);    // VUID-...-descriptorType-03018
n = Math.min(n, maxDescriptorSetStorageBuffers - 5);         // VUID-...-descriptorType-03031
n = Math.max(1, Math.min(n, ARENA_MAX_BLOCKS));
```

Derivations, all against layout counts I read in `TerrainDrawPipeline.java`:

| Limit | VUID | Required min | What consumes it |
|---|---|---|---|
| `maxPushDescriptors` | `VkDescriptorSetLayoutCreateInfo-flags-00281` | **32 is a Vulkan 1.4 guarantee, not 1.2 — do not rely on it** | task variant declares 12 bindings (`:322`); binding 0 becomes N elements → `11 + N` |
| `maxPerStageDescriptorStorageBuffers` | `VkPipelineLayoutCreateInfo-descriptorType-03018` | **4** (PROFILE-SOURCED) | MESH stage: binding 0 (`:325`) is N; translucent adds bindings 7 and 8 on `meshStage` (`:348`, `:349`) → `N + 2` |
| `maxDescriptorSetStorageBuffers` | `VkPipelineLayoutCreateInfo-descriptorType-03031` | **24** (PROFILE-SOURCED) | task set: binding 0 (N) + 7, 9, 10, 11 (`:333`, `:342`, `:343`, `:344`) + binding 8 when `extendedLists` (`:338`) → `N + 5` |
| `maxStorageBufferRange` | `VkWriteDescriptorSet-descriptorType-00333` | 128 MiB (PROFILE-SOURCED) | bounds `blockBytes`, already probed at `MeshShaderDeviceSupport.java:290-296` |

Two corrections both design documents got wrong and two reviewers caught: **the 32 for `maxPushDescriptors` sits under `VkPhysicalDeviceVulkan14Properties` in `VP_LUNARG_minimum_requirements.json` and `VP_KHR_roadmap.json`** — it is a Vulkan 1.4 core requirement, and vanilla creates a 1.2-era device that gets push descriptors from the `VK_KHR_push_descriptor` extension, which mandates no minimum. Treat an unprobed or zero value as N=1 and log it. And **`maxPushDescriptors` is not a member of `VkPhysicalDeviceLimits`** — it needs `vkGetPhysicalDeviceProperties2` with `VkPhysicalDevicePushDescriptorPropertiesKHR` chained.

That last point is cheaper than two reviewers assumed. `MeshShaderDeviceSupport.java:304-310` **already** does exactly this pattern:

```java
VkPhysicalDeviceProperties2 properties2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
long address = MESH_SHADER_PROPERTIES_STRUCT.findOrCreateStructInPNextChain(properties2, stack);
VK11.vkGetPhysicalDeviceProperties2(device, properties2);
```

So `VkPhysicalDevicePushDescriptorPropertiesKHR` and `VkPhysicalDeviceVulkan11Properties` chain into the call that is already being made, and `maxPerStageDescriptorStorageBuffers` / `maxDescriptorSetStorageBuffers` come free from the `properties.limits()` already fetched at `:290-296`.

**Clamp at probe time; never throw at pipeline build.** Design 1 proposed throwing in `TerrainDrawPipeline.build`. That throw is swallowed by the drawer's `catch (Throwable)` → `broken = true` → passive, which is safe but happens *after* the block buffers are committed and gives the player a mystery instead of a smaller N. Fold N into `MesheliumVulkanState.recordDeviceCreation` (`:113-123`, whose signature widens) and keep the pipeline-side check as an assert that can never fire.

**Log the pre-existing exposure.** The TASK stage already declares 4 storage buffers today, 5 on the extended-lists path — already above the guaranteed minimum of 4, before any split. That is inherited, not caused, and it deserves its own log line now.

### 3.3 The GLSL

Replaces `terrain.mesh:65-67` (the sole arena declaration; `:216` `uvec4 v = terrainData[(quad << 2) + i];` inside the fixed 4-iteration loop at `:215` is the sole read; `:213` is `meshelium_emitQuad`, called from `:260` and `:324`).

```glsl
#if MESHELIUM_ARENA_BLOCKS > 1
// Wave-16 SPLIT ARENA. maxStorageBufferRange bounds ONE BINDING, not the
// arena, so N bindings get N times the reach. Block k is descriptor
// element k; the address decodes (block, local) by shift and mask.
layout(set = 0, binding = 0, std430) readonly buffer TerrainData {
    uvec4 data[];
} terrainBlocks[MESHELIUM_ARENA_BLOCKS];

// ===================================================================
// EVERY ARM'S DESCRIPTOR INDEX IS A LITERAL. THIS IS LOAD-BEARING.
//
// VUID-StandaloneSpirv-StorageBufferArrayDynamicIndexing-10129: "If the
// StorageBufferArrayDynamicIndexing capability is not declared, and an
// instruction accesses memory through a storage buffer, the storage
// buffer through which that memory is accessed must be determined by
// constant integral expressions." Literal arms satisfy that with NO
// device feature -- vanilla enables nine features and that is not one
// of them (javap-verified).
//
// DO NOT "simplify" this to terrainBlocks[blk]. That form COMPILES
// CLEAN, emits NO StorageBufferArrayDynamicIndexing capability, and
// PASSES spirv-val -- and is spec-invalid on vanilla's device anyway.
// The proof artifact is scratchpad/shadertest/dyn_N4.spv; it is a
// permanent regression fixture, not a curiosity.
//
// VUID-RuntimeSpirv-StorageBufferArrayNonUniformIndexing-10136 and
// VUID-RuntimeSpirv-subgroupSize-10143 DO apply to these accesses --
// their antecedent is "an instruction accesses memory through a storage
// buffer", not "the index is non-constant". They are satisfied
// TRIVIALLY, because a constant is dynamically uniform at any scope.
// That is the stronger statement and no future spec clarification can
// undo it. Do not write "they have no instruction to apply to".
// ===================================================================
uvec4 meshelium_vertex(uint blk, uint elem) {
    switch (blk) {
        case 0u: return terrainBlocks[0].data[elem];
        case 1u: return terrainBlocks[1].data[elem];
#if MESHELIUM_ARENA_BLOCKS > 2
        case 2u: return terrainBlocks[2].data[elem];
#endif
#if MESHELIUM_ARENA_BLOCKS > 3
        case 3u: return terrainBlocks[3].data[elem];
#endif
        /* ... ONE #if PER ARM, through case 15u ... */
        // Out-of-range BLOCK index. Returns the tombstone shape WITHOUT
        // touching memory. It must NOT fall through to block 0: an
        // unguarded descriptor index is not covered by robust buffer
        // access, robustBufferAccess is NOT enabled on vanilla's device,
        // and folding a bad index onto block 0 renders ANOTHER SECTION'S
        // LIVE GEOMETRY -- plausible wrong pixels, strictly harder to
        // diagnose than wave-14's invisible terrain.
        default: return uvec4(0u);
    }
}
#else
layout(set = 0, binding = 0, std430) readonly buffer TerrainData {
    uvec4 terrainData[];
};
#endif
```

Fetch site, replacing the body of `meshelium_emitQuad` at `terrain.mesh:213-216`:

```glsl
void meshelium_emitQuad(uint slot, uint quad, vec3 origin) {
    uint vertBase = slot * 4u;
#if MESHELIUM_ARENA_BLOCKS > 1
    // Decomposed ONCE per quad: loop-invariant, and workgroup-uniform in
    // the task variant. A section's allocation can never cross a block
    // boundary (host invariant, 3.4), so blk is constant across the quad
    // AND across terrain.task's whole `base + fr` bucket walk.
    // MASK BEFORE SHIFT: elem is bounded by blockQuads<<2, so the uint
    // overflow that caps the flat design at 2^30 quads is gone.
    uint blk  = quad >> uint(MESHELIUM_ARENA_SHIFT);
    uint elem = (quad & ((1u << uint(MESHELIUM_ARENA_SHIFT)) - 1u)) << 2u;
#endif
    for (uint i = 0u; i < 4u; ++i) {
#if MESHELIUM_ARENA_BLOCKS > 1
        uvec4 v = meshelium_vertex(blk, elem + i);
#else
        uvec4 v = terrainData[(quad << 2) + i];
#endif
        /* ... wave-4 decode body verbatim, unchanged ... */
```

Three things to note. **(a)** `case 0u` is explicit and `default` is separate — Design 3's form merged them (`default: return terrainBlocks[0].data[elem];`), which is exactly the block-0 fold-down that three reviewers independently condemned. **(b)** The `#if` placement is what makes N=1 emit byte-identical SPIR-V (two reviewers reproduced the same md5s: task `936a1a4fecc00ba2bb16ffd80e023b23`, cpuCull `1e21dcf28a3cc06e0d49f3780fba7d57`, translucent `3bcd887efe5e41bf34d8fcc99f817036`). **(c)** `terrain.task`, `terrain.frag` and all four occlusion shaders need **zero** GLSL changes — verified by grep: `terrainData` appears only at `terrain.mesh:8` (comment), `:65-66` (declaration) and `:216` (the read), and the occlusion pipelines carry their own layouts and never bind binding 0.

Under a `MESHELIUM_ARENA_DEBUG` macro, make the `default:` arm emit a marker (degenerate/NaN position, or a magenta vertex) instead of zeros, so an out-of-range block index is *visible* in a debug build rather than merely absent.

### 3.4 Host allocator: one `SegmentedManager` per block, `SegmentedManager` untouched

`SegmentedManager.java:61-87` (`alloc`) has exactly two branches and both are confined to `[0, sizeLimit)`: the tail branch tests `if (totalSize+size>sizeLimit) return SIZE_LIMIT;` at `:69-71` **before** bumping `totalSize` at `:72`, and the best-fit branch can only return an address previously carved out of `totalSize`. `setLimit` at `:198-200` is a plain field assignment. So a per-block manager limited to that block's *buffer* size makes cross-block straddling **unrepresentable**, requires zero changes to the byte-faithful Nvidium port (`:5-16`), and dodges the free-coalescing and tail-shrink questions entirely.

**Justify this on port fidelity alone.** Design 1's argument — that a boundary check inside `alloc` would produce an infinite loop via the tail-shrink branch at `:129-133` — is a strawman: two reviewers pointed out the alternative actually proposed was align-up-and-publish-the-gap, which never calls `free()` and never reaches that branch. Delete the loop argument rather than shipping a document that misrepresents the rejected alternative. (The tail-shrink branch *is* exactly as described — `resized = true; totalSize -= (slot&SIZE_MSK); return;` without adding to `FREE` — but it is not the reason.)

```java
public final class TerrainArena {
    private final int  blockShift, blockMask, maxBlocks;
    private final long blockQuads, blockBytes;

    private final SegmentedManager[] segments;    // [maxBlocks], null past blockCount
    private final long[] handles;                 // VkBuffer per committed block
    private final long[] physicalBytes;
    private int blockCount;
    private long[] cachedHandles;                 // rebuilt on commit/grow/shrink

    private int blockOf(int addr) { return addr >>> blockShift; }
    private int localOf(int addr) { return addr & blockMask; }

    // ---- THE INVARIANT. Stated here because nowhere in either design
    // ---- was it stated, and violating it is silent wrong geometry.
    //
    //   physicalBytes[k] <= blockBytes                 (address stride bounds the buffer)
    //   segments[k].sizeLimit == physicalBytes[k]/64   (buffer bounds the allocator)
    //
    // The second half is the one the designs missed: a block's ADDRESS
    // STRIDE is fixed (2 GiB) while its BUFFER may be far smaller
    // (256 MiB at commit). SegmentedManager bounds the tail ONLY with
    // sizeLimit. Miss a setLimit and alloc returns locals past the end of
    // the real VkBuffer; the bytes are uploaded, counted resident, and
    // fetched from a descriptor that does not reach them -- the wave-14
    // ghost, once per block.
    private void assertBlockGeometry(int k, long bytes) {
        if (bytes <= 0 || bytes > blockBytes) {
            throw new IllegalArgumentException("arena block " + k + " is " + bytes
                    + " bytes; the address stride is " + blockBytes);
        }
    }

    public boolean appendBlock(long bytes, long handle) {
        if (blockCount >= maxBlocks) return false;
        assertBlockGeometry(blockCount, bytes);
        SegmentedManager m = new SegmentedManager();
        m.setLimit(bytes / (4L * vertexStride));          // NEVER the stride
        segments[blockCount] = m;
        handles[blockCount] = handle;
        physicalBytes[blockCount] = bytes;
        blockCount++;
        cachedHandles = null;
        return true;
    }

    /** Wave-14 grow-and-copy, now scoped to the LAST block. */
    public void growLastBlock(long newBytes, long newHandle) {
        int k = blockCount - 1;
        if (newBytes <= physicalBytes[k]) throw new IllegalArgumentException("not a growth");
        assertBlockGeometry(k, newBytes);
        physicalBytes[k] = newBytes;
        handles[k] = newHandle;
        segments[k].setLimit(newBytes / (4L * vertexStride));   // NEVER the stride
        cachedHandles = null;
    }

    public int allocQuads(int quadCount) {
        if (quadCount <= 0) throw new IllegalArgumentException(...);
        if (quadCount > blockQuads) return ALLOC_FAILED;         // fits no block
        for (int k = 0; k < blockCount; k++) {                   // lowest-block-first
            long local = segments[k].alloc(quadCount);
            if (local != SegmentedManager.SIZE_LIMIT) {
                liveQuads += quadCount; liveAllocations++;
                return (k << blockShift) | (int) local;
            }
        }
        return ALLOC_FAILED;
    }

    /** RENAMED from byteOffset(): the offset is now BLOCK-RELATIVE. */
    public long byteOffsetInBlock(int addr) { return (long) localOf(addr) * 4L * vertexStride; }
    public int  blockIndex(int addr)        { return blockOf(addr); }

    /** Defensive COPY. Never hand the drawer a reference to mutable state. */
    public long[] backingHandles() { return cachedHandles().clone(); }
}
```

Every remaining `segments.` call site must be converted, and the enumeration must be driven by **compile errors, not by eye** — Design 1's change list named `TerrainResidency.java:1262` and `:1354` and lost `releasePending()`, which today does `segments.free(pendingFreeList.getInt(i))` (`TerrainArena.java:135-141`) with an *absolute* address. The `byteOffset` → `byteOffsetInBlock` rename plus a widened `stageArenaCopy` signature makes every stale caller fail to compile. `canReuse()` (`:171-175`) and the dead `expand()` (`SegmentedManager.java:145-184`) should be converted or deleted; a dead-but-reachable `expand()` can reintroduce a cross-block range.

Two side benefits fall out and both are real: `TerrainArena.java:101` casts `segments.alloc` to int and `:120`, `:175`, `:185` pass that int into `SegmentedManager`'s long params, which mask with the 34-bit `ADDR_MSK` (`SegmentedManager.java:48`, `:90`, `:187`) and therefore preserve sign-extended bits for any address ≥ 2^31 — under per-block managers the value handed to `SegmentedManager` is a local `< 2^25`, so the hazard is structurally unreachable. Apply the `Integer.toUnsignedLong` fix anyway as defence. And the GPU-side `quad << 2` overflow at `terrain.mesh:216` recedes because the mask is applied before the shift.

**Two smaller allocator items.** `alloc` sets `resized = true` at `:67` *before* the `sizeLimit` test at `:69`, so the lowest-block-first loop leaves a stale `true` on every full block it probes, on every allocation. Nothing reads the field today (grep for `.resized` over `src/main/java` is empty), which is exactly why it should be removed rather than left for a future caller. And blocks *k*≥1 have no reserved allocation at local 0, which activates `free()`'s self-described "very dodgy" block-0 merge branch (`SegmentedManager.java:112-116`) in production for the first time — one reviewer traced it through four scenarios and found it correct, and `MesheliumTerrainDataTest`'s `segmentedManagerFuzz` already exercises a bare manager with no reserved 0, so this is covered. State it as a new production dependency rather than leaving it implicit.

### 3.5 Growth: grow the last block, then append

```java
private static boolean growArenaLocked(TerrainGpuHost gpu, int quadCount) {
    long ceiling = MesheliumScaling.arenaCeilingBytes();   // already block-quantized, see 3.7
    long current = arena.memoryBytes();
    if (current >= ceiling) return false;
    long needed     = (long) quadCount * 4L * TerrainVertexCodec.VERTEX_STRIDE;
    long blockBytes = arena.blockBytes();
    long last       = arena.lastBlockBytes();

    // (1) Grow the LAST block in place -- the wave-14 path verbatim
    //     (TerrainResidency.java:1456-1462), now bounded by ONE block.
    if (last < blockBytes) {
        long target = Math.max(last + (last >> 1), last + needed);
        target = (target + (1L << 20) - 1) >> 20 << 20;
        target = Math.min(Math.min(blockBytes, target), last + (ceiling - current));
        if (target > last) {
            if (gpu.affordableDeviceBytes() >= 0 && target > gpu.affordableDeviceBytes()) {
                arenaGrowthRefusedByBudget++;      // see section 5
            } else {
                long h = gpu.growArenaBlock(arena.blockCount() - 1, target);
                if (h != 0L) { arena.growLastBlock(target, h); arenaGrowths++; drawEpoch++; return true; }
                arenaGrowthFailures++;
            }
            // FALL THROUGH: appending needs FAR less memory than a 1.5x
            // copy target with old AND new simultaneously resident.
        }
    }
    // (2) APPEND. No vkCmdCopyBuffer, no old buffer held, no retirement.
    if (arena.blockCount() < arena.maxBlocks()) {
        long want = Math.max(MesheliumScaling.arenaInitialBytes(), needed);
        want = Math.min(want, Math.min(blockBytes, ceiling - current));   // never overshoot
        want = (want + (1L << 20) - 1) >> 20 << 20;
        if (want < needed) return false;                                  // honest refusal
        long h = gpu.appendArenaBlock(arena.blockCount(), want);
        if (h != 0L) { arena.appendBlock(want, h); arenaBlocksAppended++; drawEpoch++; return true; }
        arenaGrowthFailures++;
    }
    return false;
}
```

Note `want` is clamped to `ceiling - current` **before** the `max(needed, …)`, not after — Design 1's sketch used `max(needed, ceiling - current)` and would overshoot the ceiling by up to ~32 MiB whenever `needed > ceiling - current`, which then makes the guard message misreport.

GPU side, `appendArenaBlock` is today's `growArena` (`MesheliumTerrainGpu.java:349-393`) minus the copy: `createDeviceLocal` with `TRANSFER_SRC | TRANSFER_DST | STORAGE_BUFFER`, one `vkCmdFillBuffer(cb, buf, 0, sizeBytes, 0)`, `VulkanCommandEncoder.memoryBarrier`, `encoder.execute`. **Keep the fill.** Fresh VMA memory is undefined and `robustBufferAccess` is not enabled, so the fill is the only thing making a lagging record's read return the `header.w == 0` tombstone rather than stale garbage. Crucially the append path **never touches `retiredBackings`** (`:385-386`) because nothing is replaced, so `FREE_FRAME_LAG` is not involved.

Copy routing: `arenaCopies` and `arenaLateCopies` become per-block `List<long[]>[]`, tuples become `{srcRingOffset, dstOffsetInBlock, size}`, and `endFrame` loops per block, preserving the wave-7 `memoryBarrier` between the normal and LATE batches. **No copy is ever split**: a section is one contiguous run (`TerrainArena.java:101` passes the full `quadCount` to a single `segments.alloc`), capped at 8 × 0xFFFF quads = 33,553,920 B by `QuadFacingBuckets.OFFSETS_LENGTH = 8` (`:37`) and `toU16`'s `value > 0xFFFF` throw (`:94-95`), and it cannot straddle.

**Transient peak.** Today `growArena` allocates the whole new buffer while the old one is live and parks the old for `FREE_FRAME_LAG = 3` (`TerrainResidency.java:136`), so a 2730 → 4095 MiB step needs ~6,825 MiB simultaneously. Under blocks the peak is bounded by `total + 1.5 × blockBytes`. State it honestly as *bounded*, not *eliminated*: step (1) is still grow-and-copy until the last block reaches `blockBytes`, so with 2 GiB blocks the worst single in-frame copy is still ~2 GiB and the worst transient is still ~+3 GiB. The elimination applies to the append path only.

### 3.6 Descriptors

```java
// TerrainDrawPipeline.java:502-507 gains a count overload; only binding 0 uses it.
private static void binding(VkDescriptorSetLayoutBinding b, int i, int type, int stages) {
    binding(b, i, type, stages, 1);
}
private static void binding(VkDescriptorSetLayoutBinding b, int i, int type, int stages, int count) {
    b.binding(i).descriptorType(type).descriptorCount(count).stageFlags(stages);
}
// :325 becomes:
binding(bindings.get(0), 0, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, meshStage, arenaBlocks);
```

**The trap that would rebuild the wave-14 ghost with a new cause**, and Design 1's single best catch — verified in the repo by all three of its reviewers and by me. `TerrainDrawer.java:2966-2973` is:

```java
write.sType$Default().dstBinding(binding).descriptorCount(1).descriptorType(type).pBufferInfo(info);
```

The literal `1` is there, and LWJGL's `npBufferInfo` setter writes the pointer and nothing else — it never touches `descriptorCount` (javap-verified: four instructions, `getstatic PBUFFERINFO / memAddressSafe / memPutAddress / return`). Leaving it with an N-entry `pBufferInfo` binds block 0 only; blocks 1..N−1 stay **unwritten**, and unwritten push-descriptor array elements are *undefined*, not stale. Fix:

```java
private static void bufferWrite(VkWriteDescriptorSet write, int binding, int type,
        VkDescriptorBufferInfo.Buffer info) {
    write.sType$Default()
            .dstBinding(binding)
            .dstArrayElement(0)                  // calloc already zeroes; kept as intent
            .descriptorCount(info.remaining())   // was the literal 1
            .descriptorType(type)
            .pBufferInfo(info);
}
```

Safe for every existing caller: `bufferInfo()` (`TerrainDrawer.java:2949-2954`) unconditionally does `VkDescriptorBufferInfo.calloc(1, stack)`.

**Uncommitted slots get a dedicated zero-filled sentinel buffer, NOT block 0.** Design 1 and Design 3 both proposed `handles[b] = arena.backingHandle(min(b, committed-1))`. Three reviewers condemned it and they are right: it means any ordering or decode bug reads **live geometry** from block 0 at a masked offset. Allocate one small (1 MiB) `vkCmdFillBuffer`-zeroed DEVICE_LOCAL buffer at standup and bind it into every uncommitted slot. Be honest about what this buys: because `robustBufferAccess` is not enabled, an out-of-bounds read of the sentinel is *undefined*, not zero — the gain is that undefined-from-a-tiny-buffer is diagnosable while another section's triangles are not, and that in-bounds reads (offset 0) are genuinely zero, which is the tombstone.

The sentinel is never dereferenced under the invariant that the allocator only issues addresses in committed blocks, and the invariant is now backed by a host-side assert at both `stageArenaCopy` sites:

```java
assert blockOf(addr) < arena.blockCount()
    && byteOffsetInBlock(addr) + geometryBytes <= arena.physicalBytes(blockOf(addr));
```

Push sites (`TerrainDrawer.java:2661-2695` translucent, `:2878-2945` opaque) take `long[] arenaVkBuffers`; the translucent dummies for bindings 7/8 at `:2691` and `:2694` become `arenaVkBuffers[0]`. Every element stays at offset 0 with `VK_WHOLE_SIZE`, so `minStorageBufferOffsetAlignment` stays out of play. `DrawSnapshot`'s `long arenaBackingHandle` (`TerrainResidency.java:653`) becomes `long[] arenaBackingHandles`, **cloned at capture under the same lock hold as the section data** — every other array in that record is freshly allocated per snapshot, and a reference to the arena's cached array would put mutable pump-thread state inside a record the drawer caches across frames.

**A stale snapshot is now safe, and this is the argument for the descriptor array over BDA that neither design made cleanly.** Under BDA a stale pointer table is missing a block entirely → null deref → device loss → crash. Under the descriptor array a stale handle array holds the sentinel in that slot → reads zeros → the section is invisible for one frame → `drawEpoch++` on commit (mirroring `TerrainResidency.java:1475`) republishes it. The failure degrades to the shape this codebase already understands.

Macros at `TerrainDrawPipeline.java:274-283` gain two entries, taking `Map.of` from 7 pairs to 9 — still inside `Map.of`'s 10-pair limit, so no `Map.ofEntries` change is needed (Design 1 was wrong about this, Design 3 right):

```java
"MESHELIUM_ARENA_BLOCKS", Integer.toString(MesheliumVulkanState.arenaBlockCount()),
"MESHELIUM_ARENA_SHIFT",  Integer.toString(MesheliumVulkanState.arenaBlockShift()),
```

### 3.7 Ceiling policy — the fix goes in `arenaCeilingBytes()`, not `addressable()`

This is the correction that matters most for the backpressure machinery, and it took two reviewers to assemble it.

Both property overrides in `arenaCeilingBytes()` **return early and never reach `addressable()`** — `meshelium.test.arenaMiB` at `MesheliumScaling.java:162-165` and `meshelium.tune.arenaCeilingMiB` at `:166-169`. So Design 1's promise that "the live `meshelium.tune.arenaCeilingMiB` flip still works, clamped to `N × blockBytes`" cannot happen where it puts the clamp. Uncapped, an override yields addresses whose block field exceeds N — which the `default:` arm now renders as nothing rather than as garbage, but which still silently loses terrain.

The ceiling must equal **exactly** what the block geometry can deliver, in both directions:

```java
public static long arenaCeilingBytes() {
    return quantize(rawCeilingBytes());
}

private static long rawCeilingBytes() {
    long testMiB = Long.getLong("meshelium.test.arenaMiB", 0L);
    if (testMiB > 0) return testMiB << 20;
    long overrideMiB = Long.getLong("meshelium.tune.arenaCeilingMiB", 0L);
    if (overrideMiB > 0) return overrideMiB << 20;
    long heap = MesheliumVulkanState.deviceLocalHeapBytes();
    if (heap <= 0) return ARENA_CEILING_FALLBACK_BYTES;
    return Math.max(ARENA_CEILING_FLOOR_BYTES, (heap / 100L * ARENA_CEILING_HEAP_PCT) >> 20 << 20);
}

/**
 * The ceiling must be BOTH reachable and achievable.
 *  - Above n*blockBytes: the allocator can never get there, so the 85%
 *    eviction high-water (TerrainResidency.java:1192-1195) and the 92%
 *    render-distance backoff (MesheliumExtendedRd.java:598-603) -- which
 *    BOTH divide by this number -- can never fire. Both safety valves
 *    permanently shut, and the world jumps from "fine" to drops-and-passive.
 *  - Below what one binding can read: the wave-14 bug and a
 *    VUID-VkWriteDescriptorSet-descriptorType-00333 violation.
 * NOTE the floor is applied BEFORE the clamp, not after. That inversion
 * at the old :218-219 is the live bug this replaces.
 */
private static long quantize(long bytes) {
    long block = MesheliumVulkanState.arenaBlockBytes();
    int  n     = MesheliumVulkanState.arenaBlockCount();
    if (block <= 0 || n <= 0) return bytes;            // not probed / OpenGL path
    long capped = Math.min(bytes, (long) n * block);
    capped = Math.min(capped, ARENA_CEILING_TRANSITIONAL_CAP_BYTES);   // Phase 2 only
    return Math.max(Math.min(block, ARENA_CEILING_FLOOR_BYTES), capped);
}

/** arenaInitialBytes() gains the third clamp both designs missed. */
public static long arenaInitialBytes() {
    long initial = /* ... as today, :230-238 ... */;
    return Math.min(Math.min(initial, arenaCeilingBytes()), MesheliumVulkanState.arenaBlockBytes());
}
```

`ARENA_CEILING_TRANSITIONAL_CAP_BYTES = 4095L << 20` is the discipline Design 1 got right and Design 3 did not: **N is frozen from the full policy ceiling (dev card: 8 GiB → N=4) but the effective growth ceiling stays at today's 4095 MiB until the VRAM guard lands.** That gives a Phase 2 that is behaviour-neutral on VRAM while still committing two blocks at 2 GiB stride, so every new code path — address decode, copy routing, descriptor writes, append growth, sentinel binding — is exercised where the blast radius is zero. Phase 3 deletes one line.

---

## 4. WHAT MUST BE VERIFIED ON REAL HARDWARE BEFORE SHIPPING

### A. Checkable offline, before any hardware run — do these first

1. **Sweep every N in 1..`ARENA_MAX_BLOCKS` × {taskCull, cpuCull, translucent} and check the exit code.** Non-negotiable, and pin it as a test. The evidence on disk is `docform_{1,2,3,4,8}.spv` with nothing for 5/6/7 — the exact bug a powers-of-two matrix cannot see. 48 builds, seconds.
2. **Assert the compiled module contains no non-constant `OpAccessChain` index into the block array**, as a test-time or startup check. The whole legality argument is "every descriptor index is an `OpConstant`" and nothing in the build enforces it today.
3. **Pin the shaderc optimization level explicitly.** `MesheliumShaderCompiler.java:106-107` sets only `set_target_env(vulkan, env_version_vulkan_1_2)` and never `set_optimization_level`, so it inherits shaderc's default of zero. One reviewer verified the constant-index property survives `-O`, `-Os` and `spirv-opt -O`, so this is belt-and-braces — but inheriting a default that the entire spec argument depends on is not a thing to leave implicit.
4. **Regenerate the whole evidence trail from the shader that will actually ship, and keep the logs.** The current trail does not support the documents: `log_translucent_4.txt` is a failure log for a configuration reported OK, `log_taskcull_4.txt` contains only a filename, and `split.mesh` uses a different function shape and a different macro name than the document prints. Keep `dyn_N4.spv`, the dynamic-index negative control, as a **permanent regression fixture** — it is the most valuable artifact in the entire package.
5. **Add a multi-block leg to `MesheliumTerrainDataTest`**, which today only exercises `TerrainArena` as one flat range. Assert, across a full churn: `blockOf(addr) == blockOf(addr + quadCount - 1)`; `(block, local)` round-trips; no returned address has a local exceeding that block's physical quads; free/releasePending across blocks; block 0 local 0 is still the reserved sentinel while block *k*>0 local 0 yields a **nonzero** virtual address (so `header.w == 0` remains a unique tombstone); and an address in an uncommitted block is rejected rather than silently decoded.

### B. Needs the RX 9070 XT plus the validation layer

6. **A `--vulkanValidation` boot with N ≥ 2 forced, on a world that actually commits more than one block, with zero validation errors.** This one boot is the only tool in the chain that checks the whole family: `VkLayer_khronos_validation.dll` was found to contain `StorageBufferArrayDynamicIndexing-10129`, `StorageBufferArrayNonUniformIndexing-10136`, `subgroupSize-10143`, `flags-00281` **and** `descriptorType-03018`. `spirv-val` checks none of them and glslang silently omits the capability. Offline compilation is necessary and provably not sufficient. The gate must cover `vkCreatePipelineLayout`, not just shader modules.
7. **Boot once under `VK_LAYER_KHRONOS_profiles` in simulate mode with `VP_LUNARG_minimum_requirements.json`.** This is the highest-value single experiment in the plan and it costs one restart. It forces `maxStorageBufferRange` to 128 MiB, `maxPerStageDescriptorStorageBuffers` to 4, `maxDescriptorSetStorageBuffers` to 24, `maxBufferSize` and `maxMemoryAllocationSize` to 1 GiB, and `maxPushDescriptors` to 32 — turning every limit assumption in this design into a validation error on an AMD card. It is the closest thing available to cross-vendor coverage, and it is also the only way to exercise the `addressable()` floor bug from §1 on this desk.
8. **Log the four probed limits from the real device** — `maxPushDescriptors`, `maxPerStageDescriptorStorageBuffers`, `maxDescriptorSetStorageBuffers`, `maxMemoryAllocationSize`/`maxBufferSize` — plus the resulting `blockBytes` and N, with a one-time WARN whenever N clamps below the ceiling-derived value. Nobody has ever measured any of these on this hardware.
9. **Real terrain past the old 4 GiB line at rd 96+ on the 16 GiB card, visually confirmed** — the exact scenario that produced the original incident. Phase 3 only.
10. **Same-session A/B frame timing** for the split versus N=1, identical world and camera path. Same-session only: the harness repeats to 0.1% within a session and drifted 62% between them.
11. **Confirm `robustBufferAccess`'s actual state and settle it** (see Open Questions). It is absent from vanilla's nine `REQUIRED_DEVICE_FEATURES` (javap-verified by three reviewers), yet `MesheliumScaling.java:189` builds the entire wave-14 incident explanation on "Reads there return zero under robust buffer access". Either probe-and-request it at the existing seam or delete that sentence — do not let the new design inherit the premise.

### C. Cannot be verified on this desk — NVIDIA and Intel

12. **The descriptor-array push-descriptor layout on NVIDIA and on Intel.** UNVERIFIED and unobtainable here. Everything above establishes that the module is spec-legal, not that three drivers agree. This project has already shipped a shader silently invalid on two of three vendors.
13. **Actual values of `maxPushDescriptors`, `maxPerStageDescriptorStorageBuffers` and `maxMemoryAllocationSize` on non-AMD parts.** UNVERIFIED. The probes make a low value degrade to a smaller N rather than fail, which is the whole point of clamping at probe time — but "degrades correctly" is itself untested off AMD.
14. **Whether a mesh-stage `descriptorCount > 1` binding inside a `VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR` layout is accepted by NVIDIA's and Intel's drivers.** UNVERIFIED. Item 7 is the best available proxy and it is not the same thing.

---

## 5. THE VRAM GUARD

**Recommendation: build it, and build it as a hard prerequisite of the ceiling raise rather than as a follow-up.** Seven of the nine reviewers, across all three designs, independently arrived at the same conclusion, and it is the one item on the whole list that can turn a working install into a crashing one. `VulkanUtils.crashIfFailure` was disassembled by five reviewers with identical results: any non-negative result returns, `VK_ERROR_DEVICE_LOST` (−4) becomes `GpuDeviceLossException`, and every other negative result including `VK_ERROR_OUT_OF_DEVICE_MEMORY` (−2) becomes a bare `IllegalStateException`. There is no OOM branch, no retry, no degradation. Vanilla still builds and uploads a full second copy of the terrain — the kill switch cancels its draws, not its uploads. So this guard is not protecting Meshelium; it is protecting vanilla, and if Meshelium eats the last of the card the crash report names a vanilla texture upload.

The guard design is sound as written. Adopt it essentially unchanged, with these amendments drawn from the arena reviews:

**Amendment 1 — the effective ceiling must also respect the block geometry.** The guard computes `effectiveCeiling = min(staticCeiling, arenaCapacity + affordable)`. With the split it must be `min(staticCeiling, n * blockBytes, arenaCapacity + affordable)`. Otherwise the dead-backpressure bug from §3.7 reappears through the guard's own denominator: a ceiling above achievable capacity means neither the 85% eviction high-water (`TerrainResidency.java:1192-1195`) nor the 92% backoff (`MesheliumExtendedRd.java:598-603`) can ever fire, because both divide by it.

**Amendment 2 — put the clamp in `arenaCeilingBytes()`, not `addressable()`.** Same reason as §3.7: both property overrides return early at `MesheliumScaling.java:162-169`.

**Amendment 3 — the split makes the affordability test dramatically cheaper to pass.** Today the test is `newTotalSize <= affordable` against a grow-and-copy that holds old and new simultaneously (up to 4+ GiB). After the split the append path's test is `blockSize <= affordable` (256 MiB–2 GiB) with nothing else resident. Same one-line test, far more likely to succeed under pressure. The guard's own reuse section says this; it is correct and it is the strongest reason to land the guard and the split together rather than either alone.

**Amendment 4 — preserve the empty case when closing the ownership hole.** The guard's §9 sketch is `if (snap == null || snap.arenaBackingHandle() == 0L)`, but the real line at `TerrainDrawer.java:1438` is `if (snap == null || snap.sectionCount() == 0 || snap.arenaBackingHandle() == 0L)`. The `sectionCount() == 0` term must stay: *empty* (world streaming in) is safe for Meshelium to own because vanilla is empty too; *absent* (standup refused, or the pump latched broken) means vanilla is the only thing that can draw the world. Only the absent case may return false.

**Amendment 5 — scope the cause-code latch to the world.** `lastGrowthRefusedByBudget` must be cleared in `disposeAndReset` alongside the other per-world latches, or world N+1 inherits world N's explanation. Small, but it is the same class of bug that produced the wrong player-facing message in the first place.

**How it reuses the render-distance backoff, and the down-only ratchet.** Exactly one expression changes — the one that computes `pct` — and everything below it is untouched: `ARENA_BACKOFF_PCT = 92` (`MesheliumExtendedRd.java:193`), `ARENA_CRITICAL_PCT = 97` (`:233`), `BACKOFF_STEP = 8` (`:236`), `BACKOFF_COOLDOWN_TICKS = 60` (`:243`), the cooldown selection at `:609-611`, the `pct < ARENA_BACKOFF_PCT || rd <= vanillaMax` floor at `:615`, `options.renderDistance().set(target)` and `options.save()` at `:622`, the toast, and the placement inside the healthy-drawer branch.

`pct = max(arenaPct, vramPct)`, on the same 0–100 scale, so both consume the existing thresholds unchanged. **The absence of a restore stays.** I confirmed there are exactly two `renderDistance().set` calls in the file, `:619/:622` (`target = Math.max(vanillaMax, rd - BACKOFF_STEP)`) and the `clampBack` at `:677`, and both go downward only. Nothing in this plan adds a restore, and `MesheliumVramState` only ever contributes to `pct`, which only ever feeds a step down.

The consequence of the down-only ratchet is the guard's most important behavioural note and it must survive into the shipped doc: `arenaPct` self-quenches after a step (lowering rd makes vanilla reset the level and the arena empties), but `vramPct` does **not**, because the arena cannot shrink today (`TerrainArena.java:155-159` throws on any non-growth call). A genuine shortage will therefore keep stepping every cooldown until headroom recovers or rd hits 32 — eleven steps from 120, roughly 33 s at the polite cooldown or 5.5 s at the critical one, and the player never gets it back automatically. Hence the three-consecutive-sample confirmation gate before the vram half may drive a step: **hysteresis on entry, precisely because there is no exit any more.** The arena half needs no gate; it cannot spike downward.

**Do not let the ceiling raise ride on `ARENA_CEILING_HEAP_PCT = 50`** (`MesheliumScaling.java:124`). Half of a 16 GiB card is 8 GiB, and the guard's reserve (512 MiB discrete / 1024 MiB UMA) is an unmeasured guess. Either land the memory-budget probe first and let the reserve be the real bound, or drop the heap percentage well below 50 and make `meshelium.tune.arenaCeilingMiB` the opt-in. Do not do both changes in one release.

**The guard's single most important line** is the one mapping `heapBudget == 0` to UNKNOWN rather than to "no pressure" or "no headroom". A driver that ignores the chained struct returns zeros; treating that as headroom is catastrophic and treating it as zero headroom pins the render distance at 32 forever. UNKNOWN must fall back to the static ceiling and change nothing else.

**Ride-along fixes, all confirmed and all worth doing before the split rather than after:** the partial-standup leak at `MesheliumTerrainGpu.java:195-208` (no try/finally — a throw at the 32 MiB staging ring at `:208` strands the arena from `:195-199` plus both record buffers from `:202-207`, with no surviving reference, because the `MesheliumTerrainGpu` that owns their handles is only constructed at `:210-211`); typed `VK_ERROR_OUT_OF_DEVICE_MEMORY` in `MesheliumVkBuffers.check`; and the ownership hole above. N block buffers multiply the leak surface in exactly the pressure scenario the ceiling raise invites.

---

## 6. SEQUENCING

1.2.0 is already in testing, so nothing here goes into it.

**Phase 0 — hygiene, ships alone, no arena changes.** The partial-standup leak (`MesheliumTerrainGpu.java:195-208`, and the same shape in `TerrainOcclusion.create` and `MesheliumFrameLists.create`); typed OOM in `MesheliumVkBuffers.check`; the `TerrainDrawer.java:1438` ownership hole, preserving the `sectionCount() == 0` term. Half a day, zero arena risk, strictly beneficial under pressure, and it is the prerequisite for every later phase because it is where N block buffers would otherwise multiply a leak.

**Phase 1 — probes and the live bug, ships alone, byte-identical SPIR-V.** Fix the `Math.max(FLOOR, clamped)` inversion at `MesheliumScaling.java:218-219` and clamp `arenaInitialBytes()`; add the four limit probes to the existing seams (`:290-296` for the plain limits, `:304-310` for the chained ones); freeze `blockBytes` and N in `recordDeviceCreation` (`MesheliumVulkanState.java:113-123`); inject both macros at N=1; add the compile sweep and the constant-index assertion as tests. Verified by two reviewers to produce byte-identical SPIR-V by md5 at N=1, so the regression risk is nil. **Ship this unconditionally, whether or not the split ever lands** — it fixes a real spec violation and adds limit probes this codebase should have had before it ever pushed 12 push-descriptor elements.

**Phase 2 — the split, with `ARENA_CEILING_TRANSITIONAL_CAP_BYTES = 4095 MiB` holding the effective ceiling where it is today.** Block-aware `TerrainArena` with the two hard invariants and their asserts; the `byteOffsetInBlock` rename sweep driven by compile errors; the sentinel buffer; per-block copy lists; the descriptor array and `descriptorCount(info.remaining())`; the cloned handle array in `DrawSnapshot`; append-block growth with the grow-then-append ladder; the GLSL with per-arm guards and the separated `default`. 3–4 focused days. Behaviour-neutral on VRAM on every existing card, while committing two blocks at 2 GiB stride so every new path is exercised with zero blast radius. Gated on verification items 1–8.

**Phase 3 — the VRAM guard, and only then delete the transitional cap.** `VK_EXT_memory_budget` probe-and-append, `MesheliumVramState`, growth gating, honest cause codes, the `pct = max(arenaPct, vramPct)` swap with the 3-sample entry gate, and re-basing the 85% high-water on the same effective ceiling. 1–2 days plus a measurement session for the reserve. **The transitional cap comes out in this phase and not before.** Gated on verification items 9–11.

**Phase 4 — last-block shrink, safe to defer indefinitely.** The block model is the only route to giving memory back, which is the honest gap in everything above. It needs two things that do not exist: a per-block live-quad counter driven from `SegmentedManager.free` at *release* time, not `TerrainArena.free` at *retire* time (they are up to `FREE_FRAME_LAG = 3` frames apart and `liveQuads` already diverges in that window), and the lowest-block-first allocation preference from §3.4 so the top block is the one that empties. Release must route through `retiredBackings` and bump `drawEpoch`, exactly like every other retirement — "destroy it and re-point its descriptor" with no fence is a use-after-free waiting for a command buffer submitted last frame. Until that exists, **make block release throw**.

**Safe to defer entirely:** BDA in any form; the region-affinity dynamic-index route; any change to `ARENA_CEILING_HEAP_PCT`; the total-Meshelium-VRAM sum (cheap and worth adding, but not blocking).

---

## 7. OPEN QUESTIONS

1. **Is `robustBufferAccess` enabled, and should it be?** UNVERIFIED in effect. It is absent from vanilla's nine required features (javap-verified independently by five reviewers), yet `MesheliumScaling.java:189` builds the entire wave-14 incident explanation on "Reads there return zero under robust buffer access". Enabling it at the existing probe-and-add seam would make the sentinel and tombstone stories *defined* rather than merely *undiagnosable-in-a-good-way* — but it is a device-level feature that would change behaviour for vanilla's shaders too, with an unmeasured cost. **Owner's call**, and it should be made before Phase 2 either way, because the alternative is deleting the sentence.

2. **Does `VK_EXT_memory_budget` need to be ENABLED on the logical device, or merely SUPPORTED by the physical device, for the chained struct to be filled?** UNVERIFIED. The deciding prose ("Extending Physical Device From Device Extensions") is not on this machine; `validusage.json` carries no VUID either way, and absence of a VUID is not proof. The guard covers both readings (gate on `hasDeviceExtension` **and** append the string), which costs one string in a set. **Settle with one `--vulkanValidation` boot** — this is coordinator work.

3. **What does `heapBudget` actually account for, and does it reflect other processes?** UNVERIFIED. The fields, struct, extension and query are all nameable from `vk.xml`, but the semantic sentence is not on this machine. The guard degrades safely either way — a budget tracking only this process still catches Meshelium-plus-vanilla exhaustion, the dominant case at rd 120 — but **do not write "accounts for other processes" into a design doc as a guarantee.**

4. **What is the right reserve?** 512 MiB discrete / 1024 MiB UMA is defended by arithmetic and measured by nobody. Too small and the guard authorises a growth that crashes vanilla; too large and Meshelium refuses growth it could have had. **One session with `heapBudget`/`heapUsage` logged at rd 32 and rd 120** would settle it, and would also settle whether the existing 50% heap fraction is generous, tight, or already wrong.

5. **What is the real bytes-per-section density?** The two numbers in play differ by 3.4×: `docs/VANILLA-SECTION-BUILD.md:873-878` records 255 quads/section (16.3 KiB) for plains rd 32, while both split designs anchor on 55.1 KiB/section, which is the same document's *real-terrain upper bound*. Either 178 MiB is arena *allocated* against 51 MiB *live* — plausible, and the model would then be comparing allocated bytes to an addressability limit, which is actually the right comparison but should say so — or the two measurements disagree. **One harness run reporting live arena bytes and resident section count** settles it. Until then the extrapolation table stays deleted and the design is justified on the three facts in §3.0.

6. **`ARENA_MAX_BLOCKS = 16` or 8?** I chose 16 to serve small-block devices (512 MiB blocks need more of them to reach the same ceiling), accepting 16 extra switch arms and a tighter set-total budget (N+5 = 21 against a required minimum of 24). 8 would give far more descriptor headroom and costs nothing on the dev card, which wants N=4. The probes clamp either way, so this is a question about which fleet segment gets served, not about safety. **Owner's call**, and reversible.

7. **Should `meshelium.test.arenaMiB` and `meshelium.tune.arenaInitialMiB` be re-homed onto `quadLimit`?** The existing torture legs (a 1 MiB arena that asserts growth happens) are what makes the block-geometry invariant violable by a shipped, green test. The `min(initial, ceiling, blockBytes)` clamp in §3.7 keeps them *safe*, but it also means a 1 MiB test arena now lives inside a full-stride block 0 rather than being a 1 MiB block. **Confirm the wave-8/wave-14 test legs still assert what they were written to assert** before Phase 2 merges; if not, re-home the knobs onto a logical `quadLimit` inside a full-size block 0.

8. **Is the pre-existing TASK-stage exposure acceptable?** The extended-lists path already declares 5 storage buffers on the TASK stage (`TerrainDrawPipeline.java:333, :338, :342, :343, :344`) against a required minimum of 4 (PROFILE-SOURCED). That is inherited, not caused by this change, and it multiplies under it. Phase 1's probe should report it; whether to *act* on it — by clamping the extended path off on low-limit devices — is a separate decision the probe data should inform.
---

## POST-SHIP: A LEAK, AN ALLOCATOR, AND TWO NUMBERS (2026-08-13)

### 1. Every appended block leaked at world teardown

`MesheliumTerrainGpu` destroyed the arena through
`VkArenaBacking.takeForDestroy()`, described as "block 0 only, for the legacy
single-buffer destroy paths". It called `takeAllForDestroy()` underneath,
which **empties the block list**, and then returned the first pair. Blocks 1
and up were unreferenced and never destroyed.

That is a real VRAM leak on every world exit for any arena that grew past one
block, which at long render distance is every session: a 16-block arena
leaked 15 blocks. All three call sites (`destroy`, `destroyNow`,
`releasePartialStandup`) wanted every block. `takeForDestroy` is deleted, so
there is one way to do this and it hands back all of them.

### 2. The allocation cursor was why memory never came back

Section 3.4 specified lowest-block-first. What shipped was a rotating cursor
parked on whichever block satisfied the last request, which is better for
burst locality and fatal for reclamation: blocks return to the driver only
when COMPLETELY empty, and a roaming cursor smears survivors across every
block, so after a render-distance drop or a teleport not one of them is
empty. Now a plain low-first scan, as specified. Cost is up to N-1 extra
red-black descents per allocation, each a fast reject, against a staging copy
in the same loop iteration.

This does not free anything by itself. It is the precondition that lets a
block ever become free.

### 3. Two numbers, so the next step is chosen rather than guessed

`Counters` gains `arenaExtentBytes`, `arenaBlocks` and `emptyTopBlocks`.
The point is separating two things that live-versus-committed conflates:

- **holes** = extent minus live. Free space below the high-water mark, the
  only thing a compactor could recover, payable in device-to-device copies.
- **tail** = committed minus extent. Already free, no copying needed.
- **empty top blocks** = whole blocks that could go straight back today.

Surfaced continuously in the residency stats line, and once per world at the
arena-pressure backoff, which is the only moment the shape of the arena
decides anything. Nothing acts on them yet, deliberately: a block-release
path is worth building only if `emptyTopBlocks` is non-trivial in a real
session, and a compactor only if `holes` is.

Note for whoever builds the release path: the scout found the shader side is
cheap. The descriptor array length N is the device-derived MAXIMUM frozen at
pipeline creation, not the committed count, the committed count already
varies at runtime, and unused slots are already padded with block 0's handle,
so a release needs no pipeline, layout or shader change. Only the TOP block
is releasable, because a quad address encodes its block in the high bits.

### 4. The handover could deadlock on a fast rebuild

Unrelated to the arena, found by the same test run. The seam required the
vanilla build queue to be OBSERVED busy before it would accept a rebuild as
finished. That signal is sampled once per client tick at 20 Hz, and a small
world rebuilds inside one tick, so the busy frame never appears and the seam
waits forever for evidence that has already passed. Meshelium then owns the
frame permanently, which after a coverage-guard trip means a holey picture
forever instead of handing back to a whole vanilla.

A long unbroken calm (100 frames) now stands in for the missing evidence.
This is not a return to the original bug, which counted calm before the
rebuild had been asked for at all.

### 5. The coverage guard reported nothing while suppressing

The guard's ownership-rule early return sat BEFORE its once-only warning, so
whenever the upload seam had suppressed anything the guard tripped silently:
no log, no counter, no options-screen reason, until a rebuild finished tens
of frames later. With duplicate freeing now on by default that is the common
path. The trip is a fact about the residency counters at that instant; who
draws the frame is a separate decision and does not get to decide whether the
player is told.
