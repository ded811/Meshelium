/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium;


/**
 * Wave-10 buffer sizing, reworked in wave 13: every capacity that used to
 * be a wave-≤9 literal (2048 regions, 512 per-frame list slots, the
 * 256 MiB arena) derives from a per-world pinned render distance and is
 * read by every consumer through {@link #current()}.
 *
 * <h2>Wave-13 change: pin from the OPTION, not the config ceiling</h2>
 * Wave 10 pinned from {@code maxRenderDistanceConfigured()} — the config
 * CEILING — which forced the ceiling to double as an enable switch
 * (default 32) or every world would pay ceiling-sized buffers. Wave 13
 * pins from what the player actually asked for:
 * <pre>
 * pinnedRd(option) = option ≤ 32 ? 32 (the standard snapshot)
 *                  : min(config ceiling, nextMultipleOf8Above(option))
 * </pre>
 * — {@code nextMultipleOf8Above} is the STRICTLY-next lattice step
 * (33→40, 40→48, 48→56), a modest headroom band so a small mid-world
 * slider raise stays inside the pinned budget. A player at rd 12 under
 * the new default ceiling of 96 pins the exact wave-≤9 standard sizes;
 * raising the vanilla slider mid-world past the pinned budget keeps the
 * drawer working at pinned capacity (the wave-5/6 fail-opens; a true
 * budget overflow still ends in the honest guard-trip + clamp-back) and
 * {@code MesheliumExtendedRd} shows the once-per-world "rejoin to apply
 * fully" hint. Rejoining re-pins from the new option value.
 *
 * <h2>The formulas (docs/EXTENDED-RENDER-DISTANCE.md carries the table)</h2>
 * <pre>
 * regionsTouched(rd) = (ceil((2·rd+1)/8) + 1)² × 7
 *     — regions are 8×4×8 sections; the vanilla grid spans 2·rd+1 chunks
 *       per axis, +1 region for grid/region straddle; a 24-section
 *       overworld touches ceil(24/4)+1 = 7 Y-region rows (the same
 *       derivation RegionStore's wave-3b constant used: rd 32 → 700).
 * maxRegions(rd)   = max(rd ≤ 32 ? 2048 : max(2048, roundUp256(2 × regionsTouched(rd))),
 *                        farRegionDemand())
 *     — 2× headroom over the touchable grid (wave-3b shipped ~3× at
 *       rd 32; retention is grid-bounded, recon Q4.3, so 2× is already
 *       conservative). Overflow still drops-with-counter → coverage
 *       guard → passive + clamp-back; budgets cost Meshelium a world,
 *       never pixels.
 *       <b>pre13</b>: the second term is the far-field LOD ring, which
 *       every formula in this class used to be blind to — see
 *       {@link #farRegionDemand()} for the worked arithmetic, the VRAM
 *       price and the defect it ends. It is 0 whenever the far field is
 *       off or its radius is inside the standard near budget, so a
 *       default session pins byte-identical sizes to pre12.
 * dispatchCapacity = rd ≤ 32 ? 512 : maxRegions(rd)
 *     — per-frame mask/occlusion-list slots. Extended mode sizes the
 *       lists to the whole region budget, so the wave-5/6 fail-open
 *       overflow paths become structurally unreachable there; the lists
 *       then exceed the 16 KiB spec-min UBO range and move to Meshelium's
 *       own host-visible STORAGE ring ({@code MesheliumFrameLists}) with
 *       SSBO-variant pipelines.
 * arenaBytes       = 256 MiB, EVERY pin — the INITIAL size only (wave 14).
 *       Waves 10/13 predicted the resident set with a quadratic formula
 *       anchored on the rd-32 plains bench (~255 quads/section) — and the
 *       owner's first real overworld session proved real terrain runs
 *       several-fold denser, overflowing the 256 MiB standard pin on a
 *       16 GiB card with ~15 GiB free (droppedArenaFull → guard →
 *       "passive for this world"). Wave 14 stops predicting density: the
 *       arena starts modest and GROWS on demand (×1.5, grow-and-copy in
 *       the pump, {@code TerrainResidency}) up to a ceiling derived from
 *       the DEVICE ({@link #arenaCeilingBytes()}), so the only worlds
 *       that can trip the guard on arena bytes are worlds that genuinely
 *       exceed {@value #ARENA_CEILING_HEAP_PCT}% of the card. Density
 *       arithmetic + design: docs/VANILLA-SECTION-BUILD.md wave-14 note.
 * staging          = 32 MiB, unchanged — it bounds per-frame streaming
 *       RATE (ring turnover), not the resident set; a full ring already
 *       backlogs gracefully (wave 3b), and the harness's rd-48 leg
 *       watches for drops.
 * </pre>
 *
 * Values at the corners (section records = 8 KiB/region, stamps = 1 KiB
 * per region per buffer; arena = initial, elastic to the device ceiling):
 * <pre>
 * pinned 32:  2048 regions,  512 slots, arena starts 256 MiB (byte-identical wave ≤9 standup)
 * pinned 48:  2816 regions, 2816 slots, arena starts 256 MiB
 * pinned 64:  4608 regions, 4608 slots, arena starts 256 MiB
 * pinned 96:  9472 regions, 9472 slots, arena starts 256 MiB
 * pinned 120: 14336 regions, 14336 slots, arena starts 256 MiB (wave-15
 *             custom-cap hard max; 112 MiB section records)
 * </pre>
 * With a far-field ring the region column adds its own term (pre13; the
 * dispatch and arena columns are untouched by it):
 * <pre>
 * pinned 32, LOD off or ≤ 48:  2048 regions (unchanged)
 * pinned 32, LOD  64:          2304 regions (+2.5 MiB)  ← the shipped default
 * pinned 32, LOD  96:          3072 regions (+10 MiB)
 * pinned 32, LOD 120:          3840 regions (+17.5 MiB) ← the owner's settings
 * pinned 32, LOD 144 and up:   4096 regions (+20 MiB), capped by the
 *                              shader's 20-bit gate packing
 * </pre>
 *
 * <h2>Pinning discipline</h2>
 * {@link #pinForWorld(int)} runs at {@code MesheliumTerrainGpu.create()} —
 * the first GPU standup of a world, strictly before the arena attaches,
 * before {@code TerrainOcclusion}/{@code MesheliumFrameLists} exist and
 * while the fresh {@code RegionStore} is still empty — so every consumer
 * of one world sees ONE consistent snapshot. The caller passes the RAW
 * vanilla option value at standup, deliberately not
 * {@code getEffectiveRenderDistance()}: the effective value is
 * {@code min(option, server radius)}, and at the exact standup moment
 * the server-radius half can still be a stale login value (the radius
 * packet and the tickServer follow race the first frame), while the raw
 * option is the player's stated intent — and on small-radius multiplayer
 * servers the option is also the honest budget wish for wave-11
 * retention. {@link #current()} before any pin returns the STANDARD
 * snapshot (pure-CPU tests; nothing GPU exists yet by construction, and
 * with the wave-13 ceiling default of 96 a config-derived unpinned view
 * would claim 9,472-region record sizes no world has paid for).
 */
public final class MesheliumScaling {

    /**
     * One world's pinned sizes. {@code maxRd} is the PINNED render
     * distance. {@code arenaBytes} is the arena's INITIAL size since wave
     * 14 (the arena is elastic; records/lists stay pinned) — kept in the
     * snapshot so the standup log and the tests read one source.
     */
    public record Snapshot(int maxRd, boolean extended, int maxRegions,
            int dispatchCapacity, long arenaBytes) {
    }

    /** Wave-≤9 literals — the extended==false snapshot reproduces exactly these. */
    public static final int STANDARD_MAX_REGIONS = 2048;
    public static final int STANDARD_DISPATCH_CAPACITY = 512;
    public static final long STANDARD_ARENA_BYTES = 256L << 20;

    // ------------------------------------------------------------------
    // Wave-14: the arena ceiling comes from the DEVICE, not a formula
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Arena block geometry (multi-buffer)
    // ------------------------------------------------------------------

    /**
     * Preferred size of ONE arena block, before the device clamps it down.
     *
     * <p>512 MiB, a power of two so the shader decodes a quad address into
     * (block, local) with a shift and a mask.
     *
     * <p>It was 2 GiB, the largest a single binding could hold, and the
     * owner's rd-120 session showed why bigger is worse. Growth past the
     * first block is all-or-nothing: with 2 GiB blocks the arena stalled
     * dead at 4096 MiB because only 1824 MiB were free and a whole block
     * would not fit, while the same headroom holds THREE 512 MiB blocks.
     * Smaller blocks also bound the grow-and-copy: only block 0 is ever
     * copied, so the worst copy drops from ~2 GiB to 512 MiB, and past that
     * growth is free.
     *
     * <p>The cost is more blocks, so more switch arms in the shader and
     * more descriptors per push. Measured, not assumed: the GPU opaque pass
     * is 0.687 ms with one arm and 0.689 ms with four, and 16 arms is a
     * jump table on the same workgroup-uniform value.</p>
     */
    public static final long ARENA_BLOCK_PREFERRED_BYTES = 512L << 20;

    /**
     * Hard cap on block count.
     *
     * <p>16 blocks of 2^25 quads is 2^29 absolute quads, so a quad address
     * shifted left by 2 stays inside 2^31 and can never collide with
     * {@code TerrainArena.ALLOC_FAILED} (0xFFFFFFFF). Chosen for
     * small-block devices rather than for this desk: where blocks clamp to
     * 512 MiB it takes more of them to reach the same ceiling. The
     * descriptor limits below clamp N far under this on any device that
     * cannot afford it.</p>
     */
    public static final int ARENA_MAX_BLOCKS = 16;

    /**
     * Size of one arena block on this device, or the preferred size when
     * nothing has been probed.
     *
     * <p>Clamped by THREE separate device limits, not one, because each
     * bounds a different thing and any of them can be the smallest:
     * {@code maxStorageBufferRange} is how much of a buffer a shader may
     * READ, {@code maxMemoryAllocationSize} is how large one allocation may
     * BE, and they are unrelated numbers. The last of those has no VUID
     * anywhere, so exceeding it is invisible to the validation layer and
     * comes back as an opaque VkResult - exactly the "fits but cannot be
     * reached" class this whole change exists to remove, displaced one
     * limit sideways.</p>
     */
    public static long arenaBlockBytes() {
        // TEST/TUNE knob. Forcing a small block is the only way to exercise
        // the multi-block paths on hardware whose real block is 2 GiB: a
        // harness arena is 256 MiB, so without this the split never engages
        // and every run would be validating block 0 alone.
        long forcedMiB = Long.getLong("meshelium.tune.arenaBlockMiB", 0L);
        if (forcedMiB > 0) {
            long forced = forcedMiB << 20;
            return Math.max(com.deds.meshelium.terrain.TerrainVertexCodec.QUAD_STRIDE,
                    Long.highestOneBit(forced));
        }
        MesheliumVulkanState.ArenaLimits limits = MesheliumVulkanState.arenaLimits();
        long cap = ARENA_BLOCK_PREFERRED_BYTES;
        if (limits.maxStorageBufferRange() > 0) {
            cap = Math.min(cap, limits.maxStorageBufferRange());
        }
        if (limits.maxMemoryAllocationSize() > 0) {
            cap = Math.min(cap, limits.maxMemoryAllocationSize());
        }
        // Power of two, so the shader's decode stays a shift and a mask.
        long block = Long.highestOneBit(cap);
        return Math.max(com.deds.meshelium.terrain.TerrainVertexCodec.QUAD_STRIDE, block);
    }

    /**
     * How many blocks this device can afford, from the descriptor limits.
     *
     * <p>Resolved from the FULL default-policy ceiling rather than the live
     * one: pipelines are built once and cached for the device's lifetime,
     * so the element count baked into the descriptor layout cannot depend
     * on a value an operator can flip at runtime.</p>
     *
     * <p>The three subtractions are the bindings the terrain pipelines
     * already declare alongside the arena. Clamped here, at probe time,
     * rather than checked at pipeline build: a throw there is swallowed
     * into "broken, go passive", which is safe but happens after the
     * buffers are committed and gives the player a mystery instead of a
     * smaller N.</p>
     */
    public static int arenaBlockCount() {
        // TUNE knob: pin the declared block count. Exists to A/B the shader
        // switch (N=1 compiles a single arm, i.e. the pre-split shape) and
        // to force a small N on hardware that cannot be tested here.
        long forced = Long.getLong("meshelium.tune.arenaBlocks", 0L);
        if (forced > 0) {
            return (int) Math.min(ARENA_MAX_BLOCKS, forced);
        }
        long block = arenaBlockBytes();
        if (block <= 0) {
            return 1;
        }
        // Default policy only: arenaCeilingBytes() now caps itself to
        // blockBytes * blockCount, so reading it here would recurse.
        long ceiling = defaultPolicyCeilingBytes();
        int n = (int) Math.min(ARENA_MAX_BLOCKS, (ceiling + block - 1) / block);
        MesheliumVulkanState.ArenaLimits limits = MesheliumVulkanState.arenaLimits();
        // A zero is NOT REPORTED, never "no capacity": a driver that ignores
        // a chained struct leaves it zeroed, and reading that as a limit
        // would pin every device to one block for no reason.
        long capped = n;
        if (limits.maxPushDescriptors() > 0) {
            capped = Math.min(capped, limits.maxPushDescriptors() - TASK_NON_ARENA_BINDINGS);
        }
        if (limits.maxPerStageDescriptorStorageBuffers() > 0) {
            capped = Math.min(capped,
                    limits.maxPerStageDescriptorStorageBuffers() - MESH_STAGE_OTHER_STORAGE);
        }
        if (limits.maxDescriptorSetStorageBuffers() > 0) {
            capped = Math.min(capped,
                    limits.maxDescriptorSetStorageBuffers() - TASK_SET_OTHER_STORAGE);
        }
        return (int) Math.max(1L, capped);
    }

    /** Bindings the task variant declares besides the arena element(s). */
    private static final int TASK_NON_ARENA_BINDINGS = 11;
    /**
     * Storage buffers the MESH stage declares besides the arena.
     *
     * <p>This clamp guards the MESH stage ONLY. The task stage has its own
     * per-stage count, which no value of N can influence because the arena
     * never appears there, and it is guarded separately in
     * {@code TerrainDrawer.taskStageHasRoom()}. Do not assume this line
     * covers both.</p>
     */
    private static final int MESH_STAGE_OTHER_STORAGE = 2;
    /** Storage buffers the task SET declares besides the arena. */
    private static final int TASK_SET_OTHER_STORAGE = 5;

    /**
     * The ceiling the default policy would choose, ignoring the live
     * overrides. {@link #arenaBlockCount} needs this rather than
     * {@link #arenaCeilingBytes} because N is frozen for the device's
     * lifetime and must not move when an operator flips a property.
     */
    private static long defaultPolicyCeilingBytes() {
        long heap = MesheliumVulkanState.deviceLocalHeapBytes();
        if (heap <= 0) {
            return ARENA_CEILING_FALLBACK_BYTES;
        }
        long fraction = (heap / 100L * ARENA_CEILING_HEAP_PCT) >> 20 << 20;
        return Math.max(ARENA_CEILING_FLOOR_BYTES, fraction);
    }

    /** Default ceiling = this % of the largest DEVICE_LOCAL heap. */
    public static final int ARENA_CEILING_HEAP_PCT = 50;
    /** Absolute ceiling floor — a tiny reported heap still gets a workable arena. */
    public static final long ARENA_CEILING_FLOOR_BYTES = 256L << 20;
    /**
     * Ceiling when no heap was ever probed (a Vulkan world standing up
     * before the device hook recorded — structurally impossible today,
     * kept as a defensive constant): the wave-13 cap, the last honest
     * number this project shipped without a probe.
     */
    public static final long ARENA_CEILING_FALLBACK_BYTES = 1024L << 20;

    /**
     * The terrain arena's growth ceiling in bytes, resolved per call (the
     * growth path asks at most once per pump — property flips are live,
     * the harness's requirement). Resolution order:
     * <ol>
     *   <li>{@code meshelium.test.arenaMiB} — the wave-8 torture override:
     *       initial AND ceiling both collapse to it, so the guard leg
     *       still deterministically forces "growth exhausted" (the wave-14
     *       trip condition) exactly where it used to force "arena
     *       full".</li>
     *   <li>{@code meshelium.tune.arenaCeilingMiB} — operator override
     *       (coordinator/power user; also the guard-leg knob when a
     *       nonstandard initial is in play).</li>
     *   <li>max({@value #ARENA_CEILING_HEAP_PCT}% of the largest
     *       DEVICE_LOCAL heap, 256 MiB) — the wave-14 default. The heap
     *       SIZE is a static hardware fact
     *       ({@code vkGetPhysicalDeviceMemoryProperties}, core 1.0),
     *       unlike the {@code vmaGetHeapBudgets} usage estimates wave 10
     *       rightly rejected (vanilla's allocator lacks
     *       VK_EXT_memory_budget — that rejection stands; this is a
     *       different query). Integrated GPUs report their shared
     *       system-memory heap: the fraction then bounds Meshelium's share
     *       of SYSTEM memory — documented caveat, and the reason the
     *       default is a fraction rather than "heap minus slack".</li>
     * </ol>
     */
    public static long arenaCeilingBytes() {
        // The overrides are CLAMPED but NOT floored. Clamped because an
        // operator typing a number bigger than the device can address
        // deserves a smaller arena, not invisible terrain; not floored
        // because forcing an arena below 256 MiB is the entire purpose of
        // the torture knob, and applying the floor here would silently
        // round 192 MiB up to 256 and make the guard legs untestable.
        long testMiB = Long.getLong("meshelium.test.arenaMiB", 0L);
        if (testMiB > 0) {
            return clampToAddressable(testMiB << 20);
        }
        long overrideMiB = Long.getLong("meshelium.tune.arenaCeilingMiB", 0L);
        if (overrideMiB > 0) {
            return clampToAddressable(overrideMiB << 20);
        }
        long heap = MesheliumVulkanState.deviceLocalHeapBytes();
        if (heap <= 0) {
            return capToBlocks(ARENA_CEILING_FALLBACK_BYTES);
        }
        // Whole MiB — growth fills/copies then stay trivially 4-aligned.
        long fraction = (heap / 100L * ARENA_CEILING_HEAP_PCT) >> 20 << 20;
        return capToBlocks(Math.max(ARENA_CEILING_FLOOR_BYTES, fraction));
    }

    /**
     * The ceiling that is actually REACHABLE right now: the static policy
     * ceiling, or how far the arena could still grow on this machine,
     * whichever is smaller.
     *
     * <p>This is the whole VRAM guard, expressed as one number instead of a
     * second state machine. Every consumer of the ceiling - the 85%
     * eviction high-water and the 92% render-distance backoff - already
     * divides by it, so shrinking it when the card is genuinely short makes
     * those existing valves fire EARLIER, before terrain starts dropping.
     * With memory to spare it equals the static ceiling exactly and nothing
     * behaves differently, which is the point: this must never cost a
     * player who has the memory.</p>
     *
     * <p>WHY IT HAS TO WORK THIS WAY ROUND. Simply refusing to grow is not
     * the gentle option it sounds like. A refused growth drops the section,
     * which trips the coverage guard, which puts Meshelium passive for the
     * whole world AND clamps the render distance to 32. Stepping the
     * distance down by 8 first is strictly kinder than the cliff it
     * prevents, and the player can drag it straight back up in Video
     * Settings.</p>
     *
     * <p>Uses the SUSTAINED headroom, not the latest sample, so a momentary
     * dip cannot cost render distance under the no-restore rule.</p>
     *
     * @param currentCapacityBytes what the arena already holds
     */
    public static long effectiveCeilingBytes(long currentCapacityBytes) {
        long staticCeiling = arenaCeilingBytes();
        long headroom = MesheliumVramState.sustainedHeadroomBytes();
        if (headroom == Long.MAX_VALUE) {
            return staticCeiling; // budget unknown: behave exactly as before
        }
        // FLOOR THE HEADROOM TO WHOLE BLOCKS. Growth past the first block is
        // block-granular: the arena cannot take 1824 MiB of headroom and
        // grow by 1824 MiB, it can only append a whole block or nothing.
        // Counting the remainder as reachable is what let the owner's
        // 2026-08-13 session walk into the cliff. At the moment it failed:
        //
        //   capacity 4096, headroom 1824, block 2048
        //   raw     -> ceiling 5920, used 3782 = 64%  ... 92% trip silent
        //   floored -> ceiling 4096, used 3782 = 92%  ... trip fires
        //
        // The refusal to allocate was correct; what was missing was the
        // render-distance step that should have happened BEFORE it, and it
        // was missing because the ceiling claimed room that no allocation
        // could ever occupy.
        long block = arenaBlockBytes();
        long reachable = currentCapacityBytes;
        if (currentCapacityBytes < block) {
            // Still inside the first block, which grows continuously rather
            // than in whole-block steps, so every byte of headroom is real.
            reachable += headroom;
        } else if (block > 0) {
            reachable += headroom / block * block;
        }
        // Never below what is already committed, or the pressure percentage
        // would exceed 100 and the backoff would step on every tick.
        return Math.max(currentCapacityBytes, Math.min(staticCeiling, reachable));
    }

    /**
     * Cap a ceiling to what the BLOCKS can actually deliver.
     *
     * <p>This is what replaces the old single-binding clamp, and it is the
     * step that finally raises the 4 GiB wall. maxStorageBufferRange bounds
     * one BINDING, not the arena: with the terrain data split across N
     * separately bound blocks the reachable total is blockBytes * N, which
     * on the dev card is 4 x 2048 MiB = 8192 MiB against the 4095 MiB a
     * single buffer could ever have offered.</p>
     *
     * <p>The per-block guarantee has not gone anywhere, it has moved:
     * {@link #arenaBlockBytes()} clamps each block to both
     * maxStorageBufferRange and maxMemoryAllocationSize, so every
     * individual binding is still readable end to end. What changed is
     * only that the TOTAL is no longer held to one binding's reach.</p>
     */
    private static long capToBlocks(long bytes) {
        long block = arenaBlockBytes();
        int blocks = arenaBlockCount();
        if (block <= 0 || blocks <= 0) {
            return addressable(bytes); // not probed: the old single-buffer rule
        }
        long total = block * (long) blocks;
        return Math.max(Math.min(ARENA_CEILING_FLOOR_BYTES, total), Math.min(bytes, total));
    }

    /**
     * Clamp an arena size to what a shader can actually READ.
     *
     * <p>FITTING IN MEMORY IS NOT THE SAME AS BEING ADDRESSABLE, and this
     * cost a player their terrain. The arena is one storage buffer, and a
     * shader can only reach {@code maxStorageBufferRange} bytes of it,
     * which is 4 GiB minus one on essentially all desktop hardware. Sizing
     * the ceiling purely from the heap let a 16 GiB card grow the arena to
     * 4,374 MiB. Everything past the 4 GiB line still existed, was still
     * uploaded, and was still counted resident: it simply could not be
     * fetched. Reads there return zero under robust buffer access, a zeroed
     * section record reads {@code header.w == 0}, which is exactly the
     * tombstone the task shader uses for "empty slot", so the section was
     * silently skipped.</p>
     *
     * <p>The symptom was terrain turning invisible at render distance 96
     * and 120, spreading as more chunks loaded, spreading again when blocks
     * were edited because a rebuild reallocates upward, surviving the
     * occlusion toggle because it was never occlusion, and clearing only on
     * restart. Every drop counter read zero throughout and the coverage
     * guard never fired, correctly: nothing was dropped. That is the nasty
     * part. The guard defends the "does not fit" failure and this was a
     * "fits but cannot be reached" failure, which had no defence at all.</p>
     *
     * <p>Clamped here rather than at the growth site so the ceiling is
     * honest everywhere it is read: the status screen, the log line, and
     * the guard's own trip condition. Once the ceiling is reachable, an
     * overflowing world takes the EXISTING path, dropping sections and
     * tripping the guard into passive with a named reason, which is a bad
     * outcome a player can understand instead of a mystery.</p>
     */
    private static long addressable(long bytes) {
        return addressableFor(bytes, MesheliumVulkanState.maxStorageBufferRangeBytes());
    }

    private static long clampToAddressable(long bytes) {
        return clampToAddressableFor(bytes, MesheliumVulkanState.maxStorageBufferRangeBytes());
    }

    /**
     * The clamp WITHOUT the floor, for the two property overrides.
     *
     * <p>They need the clamp for the same reason the default path does: a
     * number larger than {@code maxStorageBufferRange} produces terrain the
     * shader cannot read, and an operator override is no safer than a
     * computed one. They must NOT get the floor, because forcing an arena
     * below 256 MiB is exactly what {@code meshelium.test.arenaMiB} is for -
     * the pressure-backoff and coverage-guard legs are driven at 192 and
     * 352 MiB, and flooring those to 256 would quietly disarm the tests
     * that prove the safety valves work.
     */
    public static long clampToAddressableFor(long bytes, long limit) {
        if (limit <= 0) {
            return bytes;
        }
        long limitAligned = limit >> 20 << 20;
        return limitAligned <= 0 ? Math.min(bytes, limit) : Math.min(bytes, limitAligned);
    }

    /**
     * {@link #addressable} with the device limit passed in, so the
     * arithmetic can be exercised against limits no GPU on this desk
     * reports. Every value that matters here is one this hardware cannot
     * produce: the bug it exists to catch is invisible at 4095 MiB and only
     * appears below 256 MiB.
     *
     * @param bytes the size being requested
     * @param limit {@code maxStorageBufferRange}, or 0 when never probed
     * @return a size the shader can actually reach, never above {@code limit}
     */
    public static long addressableFor(long bytes, long limit) {
        if (limit <= 0) {
            return bytes;
        }
        // THE FLOOR MUST NEVER OUTRANK THE LIMIT. This originally read
        // `Math.max(ARENA_CEILING_FLOOR_BYTES, clamped)`, which applied the
        // floor AFTER the clamp and therefore undid it: a device reporting
        // maxStorageBufferRange below 256 MiB got a 256 MiB ceiling, an
        // arena larger than the shader can address, and the wave-14
        // invisible-terrain failure rebuilt exactly. The comment that used
        // to sit here claimed such a device would "simply go passive early
        // rather than silently losing terrain", which was the opposite of
        // what the code did. Vulkan's required minimum for
        // maxStorageBufferRange is 128 MiB, i.e. BELOW the floor, so this
        // was reachable on a conformant device and not merely in theory.
        // No desktop GPU reports anything near it — the dev card reports
        // 4095 MiB — which is precisely why it would have shipped.
        long limitAligned = limit >> 20 << 20; // whole MiB, always <= limit
        if (limitAligned <= 0) {
            // Cannot address even one MiB of a storage buffer. Hand back the
            // raw limit rather than zero: the residency guard then reports a
            // world that does not fit, out loud, instead of this returning a
            // zero-byte arena for something downstream to divide by.
            return limit;
        }
        return Math.max(Math.min(ARENA_CEILING_FLOOR_BYTES, limitAligned),
                Math.min(bytes, limitAligned));
    }

    /**
     * The arena's INITIAL allocation in bytes: {@code meshelium.test.arenaMiB}
     * (torture: tiny initial AND ceiling) ?? {@code meshelium.tune.arenaInitialMiB}
     * (the growth-leg knob: tiny initial, normal ceiling) ?? the pinned
     * snapshot's 256 MiB — always clamped to the ceiling (an iGPU whose
     * ceiling resolves below 256 MiB starts at the ceiling and simply
     * never grows).
     */
    public static long arenaInitialBytes() {
        long ceiling = arenaCeilingBytes();
        long testMiB = Long.getLong("meshelium.test.arenaMiB", 0L);
        if (testMiB > 0) {
            return testMiB << 20; // == ceiling by construction above
        }
        long initialMiB = Long.getLong("meshelium.tune.arenaInitialMiB", 0L);
        long initial = initialMiB > 0 ? initialMiB << 20 : current().arenaBytes();
        return Math.min(initial, ceiling);
    }

    private static final Snapshot STANDARD = new Snapshot(
            MesheliumConfig.MIN_MAX_RENDER_DISTANCE, false,
            STANDARD_MAX_REGIONS, STANDARD_DISPATCH_CAPACITY, STANDARD_ARENA_BYTES);

    private static volatile Snapshot current;

    private MesheliumScaling() {
    }

    /** The pinned world snapshot; the STANDARD snapshot before the first pin. */
    public static Snapshot current() {
        Snapshot pinned = current;
        return pinned != null ? pinned : STANDARD;
    }

    /**
     * The pinned snapshot, or null before the first world standup —
     * wave-13's mid-world-raise hint keys on this (identity per pin):
     * a hint may only fire against a REAL world budget, never the
     * unpinned fallback.
     */
    public static Snapshot pinned() {
        return current;
    }

    /**
     * Pin (or re-pin) at world standup — render thread, called by
     * {@code MesheliumTerrainGpu.create()} with the raw vanilla option
     * value at that moment. Logs the derivation once per pin so every
     * scaled buffer's standup line has it next to it.
     */
    public static Snapshot pinForWorld(int optionRd) {
        Snapshot snapshot = compute(pinnedRd(optionRd));
        current = snapshot;
        if (snapshot.extended()) {
            MesheliumLog.LOGGER.info(
                    "Meshelium extended render distance pinned for this world: option {} chunks "
                            + "-> pinned {} (next-8 headroom, ceiling {}) -> maxRegions={} "
                            + "({} MiB section records), dispatchCapacity={} (SSBO frame "
                            + "lists), arena starts at {} MiB and grows on demand to the "
                            + "{} MiB device ceiling (wave 14; raising the slider past the "
                            + "pinned budget mid-world grows the records live, wave 15)",
                    optionRd, snapshot.maxRd(), MesheliumConfig.maxRenderDistanceConfigured(),
                    snapshot.maxRegions(), (snapshot.maxRegions() * 8192L) >> 20,
                    snapshot.dispatchCapacity(), snapshot.arenaBytes() >> 20,
                    arenaCeilingBytes() >> 20);
        } else if (snapshot.maxRegions() > STANDARD_MAX_REGIONS) {
            MesheliumLog.LOGGER.info(
                    "Meshelium scaling pinned for this world: option {} chunks -> the standard "
                            + "wave-<=9 near sizes with a FAR-FIELD region budget of {} ids "
                            + "({} MiB section records, {} MiB more than the standard 2048) "
                            + "because the LOD ring spans more region columns than the render "
                            + "distance does (pre13); {} dispatch slots, arena starts at 256 "
                            + "MiB and grows on demand to the {} MiB device ceiling",
                    optionRd, snapshot.maxRegions(),
                    (snapshot.maxRegions() * 8192L) >> 20,
                    ((snapshot.maxRegions() - STANDARD_MAX_REGIONS)
                            * (long) FAR_REGION_ROW_COST_KIB * 1024L) >> 20,
                    snapshot.dispatchCapacity(), arenaCeilingBytes() >> 20);
        } else {
            MesheliumLog.LOGGER.info(
                    "Meshelium scaling pinned for this world: option {} chunks -> the standard "
                            + "wave-<=9 sizes (2048 regions, 512 dispatch slots, arena starts "
                            + "at 256 MiB and grows on demand to the {} MiB device ceiling — "
                            + "wave 14; extended record sizing pins only when the option "
                            + "exceeds 32 at world standup)",
                    optionRd, arenaCeilingBytes() >> 20);
        }
        return snapshot;
    }

    /**
     * Wave-15: the snapshot a LIVE mid-world raise to {@code optionRd}
     * would need — pure (no state change); the pump compares it against
     * {@link #current()} to size the record grow. Same formula as
     * {@link #pinForWorld}.
     */
    public static Snapshot computeForOption(int optionRd) {
        return compute(pinnedRd(optionRd));
    }

    /**
     * Wave-15: swap the pinned snapshot mid-world after the GPU record
     * buffers grew to the new sizes (render thread, under the residency
     * LOCK, called by {@code TerrainResidency}'s pump ONLY — the ordering
     * is load-bearing: every consumer that live-reads {@link #current()}
     * for a BUDGET ({@code RegionStore.maxRegions()},
     * {@code TerrainOcclusion.listCapacity()}) must never see the grown
     * budget before the grown buffers exist). A fresh snapshot identity
     * on purpose: the rejoin-hint keying and the harness's grow probes
     * both key on it.
     */
    public static void growPinned(Snapshot grown) {
        current = grown;
        MesheliumLog.LOGGER.info(
                "Meshelium scaling grown mid-world: pinned {} chunks -> maxRegions={} "
                        + "({} MiB section records), dispatchCapacity={} (live render-distance "
                        + "raise, wave 15; no rejoin needed)",
                grown.maxRd(), grown.maxRegions(), (grown.maxRegions() * 8192L) >> 20,
                grown.dispatchCapacity());
    }

    /**
     * The wave-13 pin formula: standard at option ≤ 32; otherwise the
     * strictly-next multiple of 8 above the option (a modest headroom
     * band: 33→40, 40→48, 48→56), capped by the config ceiling.
     */
    static int pinnedRd(int optionRd) {
        if (optionRd <= MesheliumConfig.MIN_MAX_RENDER_DISTANCE) {
            return MesheliumConfig.MIN_MAX_RENDER_DISTANCE;
        }
        int next8 = (optionRd / 8 + 1) * 8;
        return Math.min(MesheliumConfig.maxRenderDistanceConfigured(), next8);
    }

    private static Snapshot compute(int pinnedRd) {
        int far = farRegionDemand();
        if (pinnedRd <= MesheliumConfig.MIN_MAX_RENDER_DISTANCE) {
            if (far <= STANDARD_MAX_REGIONS) {
                return STANDARD;
            }
            // A standard NEAR pin that carries a far ring: the region id
            // budget grows and NOTHING else does. `extended` stays false
            // and dispatchCapacity stays at the wave-≤9 512, because both
            // describe the near field's own draw path — the far ring
            // overflows the per-frame mask/occlusion lists at these
            // settings either way and those paths fail open (culling
            // degrades, parity never; docs/FARFIELD-WAVES.md, "M4 AND M5
            // ANSWERED"). Sizing them to the far ring instead would move
            // the frame lists to the SSBO variant on a world the player
            // pinned as standard, which is a much bigger change than the
            // one the ring actually needs.
            return new Snapshot(MesheliumConfig.MIN_MAX_RENDER_DISTANCE, false,
                    far, STANDARD_DISPATCH_CAPACITY, STANDARD_ARENA_BYTES);
        }
        int maxRegions = Math.max(STANDARD_MAX_REGIONS, roundUp(2 * regionsTouched(pinnedRd), 256));
        maxRegions = Math.max(maxRegions, far);
        // Wave-14: the arena no longer scales with rd — every pin starts
        // at the standard 256 MiB and grows on demand (the wave-10/13
        // quadratic density prediction is retired; density arithmetic in
        // docs/VANILLA-SECTION-BUILD.md wave-14 note).
        return new Snapshot(pinnedRd, true, maxRegions, maxRegions, STANDARD_ARENA_BYTES);
    }

    /** (ceil((2·rd+1)/8)+1)² × 7 — see the class javadoc derivation. */
    static int regionsTouched(int rd) {
        int chunks = 2 * rd + 1;
        int horizontal = (chunks + 7) / 8 + 1;
        return horizontal * horizontal * 7;
    }

    // ------------------------------------------------------------------
    // pre13/N1: the far ring is the OTHER consumer of the region budget,
    // and until now no formula here knew it existed
    // ------------------------------------------------------------------

    /**
     * Y-region ROWS a far-field column band is assumed to occupy.
     *
     * <p>A region is 8×4×8 sections, so one Y row is 64 blocks. The far
     * field stores a SURFACE BAND (the heightmap down {@code bandOffset}
     * blocks, default 8), not a whole column, so the question is how far
     * the surface moves across one region column — 128×128 blocks. Flat
     * biomes sit inside a single row; ordinary relief straddles a row
     * boundary; mountains and deep ocean shelves reach a third. Three is
     * chosen against the failure ASYMMETRY rather than the median: too
     * high costs {@value #FAR_REGION_ROW_COST_KIB} KiB of VRAM per unused
     * region, too low leaves the outer ring unable to fill, which is the
     * defect this exists to end and has now been reported three times.
     * {@code -Dmeshelium.tune.farRegionYRows} overrides it.</p>
     */
    private static final int FAR_REGION_Y_ROWS_DEFAULT = 3;

    /**
     * VRAM one region id costs, in KiB: 8 KiB of section records
     * ({@code SectionRecord.BYTES_PER_REGION}, addressed as
     * {@code regionId * 8192}), 2 KiB of occlusion stamps
     * ({@code TerrainOcclusion}: {@code maxRegions * 256 * 4} bytes in
     * TWO buffers) and 16 B of region record. Quoted so the sizing
     * argument below carries its own price.
     */
    private static final int FAR_REGION_ROW_COST_KIB = 10;

    /**
     * The far fill guard's own percentage
     * ({@code TerrainResidency.FAR_REGION_GUARD_PCT}), duplicated here
     * because that constant is private to the host package — the same
     * duplication {@code farFillHasRoomLocked} already makes in the other
     * direction for {@code regionsTouched}. A budget that only just
     * covers the demand would leave the guard closed at exactly the
     * moment the ring finished filling, so the demand is divided up by
     * this before it becomes a budget.
     */
    private static final int FAR_GUARD_PCT = 85;

    /**
     * Hard ceiling on the far-derived region budget: <b>4096 ids, and it
     * is the SHADER's number, not a memory one.</b>
     *
     * <p>The translucent occlusion gate packs its identity check as
     * {@code GateIndex = posKey << 20 | (regionId * 256 + slot)}
     * (TerrainDrawer, and {@code terrain.mesh}'s
     * {@code meshelium_translucentGate} reads it back with
     * {@code GateIndex & 0xFFFFF} / {@code GateIndex >> 20}). Twenty bits
     * for {@code regionId * 256 + slot} is {@code regionId <= 4095}
     * exactly. Past that the id spills into the {@code posKey} field, the
     * identity compare mismatches and the gate <b>fails OPEN</b> - the
     * wave-6 parity rule, so the picture is right and the translucent
     * occlusion cull silently stops working.</p>
     *
     * <p>That is a real cost on the axis the owner values most, so the
     * far term stops here rather than buying ring coverage with frame
     * rate. It binds only above L1 ≈ 144; at the owner's 120 the derived
     * budget is 3840 and the cap is not reached.
     * {@code -Dmeshelium.tune.farRegionCap} moves it for anyone who
     * wants to trade the other way.</p>
     *
     * <p><b>Recorded, not fixed:</b> every EXTENDED pin from rd 56 up
     * already exceeds 4096 ids by the wave-13 formula (rd 64 pins 4608),
     * so those worlds have been running with a dead translucent gate
     * since wave 13 and nothing said so. Not touched here - it predates
     * this change and widening the packing is a shader-format change.</p>
     */
    private static final int FAR_REGION_CAP = 4096;

    /**
     * Region ids the far ring needs, or 0 when there is no far ring.
     *
     * <h2>Why this method has to exist (pre13 N1, and it is the third
     * report of the same defect)</h2>
     * <p>Every formula above is a function of the NEAR render distance
     * and knows nothing about layer 1. At the owner's exact settings —
     * render distance 32, LOD 120 — that gives {@code maxRegions = 2048},
     * and the far fill guard is
     * {@code min(85% × 2048, 2048 − 700) = 1348} region ids against a
     * ring that needs about 2,400. <b>The ring therefore could not fill
     * past roughly radius 100 however fast extraction ran</b>, and no
     * amount of work on the extraction budget could have fixed the
     * owner's report on its own. Both halves verified in this session
     * against {@code TerrainResidency.farFillHasRoomLocked} and
     * {@link #regionsTouched}, not carried over from the note that first
     * suspected it.</p>
     *
     * <h2>The arithmetic</h2>
     * <pre>
     * square(l1)  = (ceil((2·l1+1)/8) + 1)²      // region columns, square
     * columns(l1) = ceil(square × 79/100)        // the ring is a DISC
     * R           = columns × FAR_REGION_Y_ROWS  // far ids wanted
     * N           = regionsTouched(32) = 700     // the near reserve
     * budget      = min(FAR_REGION_CAP,
     *                   roundUp256(max((R + N)·100/85, R + 2N)))
     * </pre>
     * <p><b>The disc factor is not a fudge</b>: the ring walk's own
     * membership test is {@code withinRing}, {@code dx² + dz² <= l1²}, so
     * a quarter of the enumerated square is never promoted. π/4 = 0.785,
     * rounded up to 0.79 because the boundary region columns are touched
     * partially and still cost a whole id.</p>
     *
     * <p><b>Both arms of the guard, because they bind at different
     * radii.</b> {@code farFillHasRoomLocked} is
     * {@code min(85% × max, max − N)}: the first arm needs
     * {@code max >= (R+N)/0.85} and the second {@code max >= R + 2N}. The
     * second is the tighter one at small L1 and was the one an earlier
     * draft of this method got wrong - it would have sized L1 120 at 3328
     * ids for a gate of 2628 against a demand of 2427 plus the near
     * field's live regions, i.e. shipped the same defect one radius
     * further out.</p>
     *
     * <table border="1">
     * <caption>rd 32, by LOD radius</caption>
     * <tr><th>L1</th><th>far ids wanted</th><th>budget</th>
     *     <th>extra VRAM</th><th>gate opens at</th></tr>
     * <tr><td>≤ 48</td><td>465</td><td>2048</td><td>0</td><td>1348</td></tr>
     * <tr><td>64 (default)</td><td>768</td><td>2304</td><td>2.5 MiB</td><td>1604</td></tr>
     * <tr><td>96</td><td>1605</td><td>3072</td><td>10 MiB</td><td>2372</td></tr>
     * <tr><td>120 (the owner)</td><td>2427</td><td>3840</td><td>17.5 MiB</td><td>3140</td></tr>
     * <tr><td>144+</td><td>3423+</td><td>4096 (capped)</td><td>20 MiB</td><td>3396</td></tr>
     * </table>
     *
     * <h2>The one number here that is an estimate</h2>
     * <p>{@link #FAR_REGION_Y_ROWS_DEFAULT}. Everything else is counted.
     * If it is short, the symptom is the OLD one - an outer band that
     * will not fill - and the evidence is {@code farAdmitNoBudget}
     * climbing while {@code farExtractSweepOwed} is at zero, i.e. the
     * store has the shells and the residency will not take them. The
     * lever is {@code -Dmeshelium.tune.farRegionYRows}.</p>
     *
     * <h2>Failure direction</h2>
     * <p>Under-sizing is graceful and visible: far admissions come back
     * {@code ADMIT_NO_BUDGET}, which is counted and, since pre13, RETRIED
     * without the player moving. It never trips the wave-8 coverage
     * guard — that is reachable only from a live vanilla upload finding
     * no id, and the guard's {@code max − N} arm reserves against exactly
     * that. Over-sizing costs VRAM and nothing else, because the CPU-side
     * {@code RegionStore.Region} objects are allocated lazily per region
     * actually touched.</p>
     */
    public static int farRegionDemand() {
        int l1;
        try {
            if (!com.deds.meshelium.farfield.FarFieldConfig.enabled()) {
                return 0;
            }
            l1 = com.deds.meshelium.farfield.FarFieldConfig.l1RadiusChunks();
        } catch (Throwable t) {
            // The pin happens at GPU standup and must never fail because
            // the far field's config could not be read. No far term is
            // the pre13 behaviour, which is a working world.
            return 0;
        }
        if (l1 <= MesheliumConfig.MIN_MAX_RENDER_DISTANCE) {
            return 0; // the ring is inside the standard near budget already
        }
        int rows = Integer.getInteger("meshelium.tune.farRegionYRows",
                FAR_REGION_Y_ROWS_DEFAULT);
        rows = Math.max(1, Math.min(7, rows)); // 7 Y rows is a whole overworld
        int chunks = 2 * l1 + 1;
        int square = (chunks + 7) / 8 + 1;
        long columns = ((long) square * square * 79 + 99) / 100;
        long far = columns * rows;
        long near = regionsTouched(MesheliumConfig.MIN_MAX_RENDER_DISTANCE);
        long budget = Math.max((far + near) * 100L / FAR_GUARD_PCT, far + 2L * near);
        int cap = Integer.getInteger("meshelium.tune.farRegionCap", FAR_REGION_CAP);
        return Math.min(Math.max(256, cap),
                roundUp((int) Math.min(budget, 1 << 20), 256));
    }

    private static int roundUp(int value, int multiple) {
        return (value + multiple - 1) / multiple * multiple;
    }
}
