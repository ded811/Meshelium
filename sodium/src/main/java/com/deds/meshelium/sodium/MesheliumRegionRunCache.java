/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;

import java.util.Arrays;

/**
 * One region's cached draw runs, per opaque pass: what the enumeration
 * walk would have produced, kept until something that could change the
 * answer happens.
 *
 * <h2>Why (D-021, MEASUREMENTS.md 0k)</h2>
 * <p>Stage 0 measured the frame at distance as CPU-bound, and the CPU was
 * ours: the per-section walk over Sodium's lists — seven Unsafe reads and
 * a facing test per listed section, twice a frame — cost 464 us per pass
 * at aerial rd96, half the frame. The walk's output for a region is a
 * pure function of four things: the region's geometry records, the set
 * and order of its listed sections, the facing mask (a function of the
 * camera's integer position), and the pass. All four are cheap to
 * observe, and the first is the only one without a public change signal
 * — which is what the five hooks supply, as a per-region epoch. So the
 * output is cached on the region and replayed while the key holds.
 *
 * <h2>The key, exactly</h2>
 * <ul>
 *   <li><b>epoch</b> — the region's mutation counter, bumped at every
 *       hooked record write (upload, section removal, buffer change,
 *       segment move, delete). Nothing else writes bytes 4..47 of a
 *       record (javap, jar-wide).</li>
 *   <li><b>the byte sequence</b> — the region's sections-with-geometry
 *       list, in Sodium's BFS order, compared byte for byte. The order
 *       matters: it is the draw order, and matching on it makes the
 *       cached run table identical to the walk's, not merely a
 *       permutation of it. That is what lets the suite assert parity by
 *       counters alone.</li>
 *   <li><b>the camera band</b> — {@link #cameraKey(int)} per axis. Not the
 *       camera's section: Sodium's {@code getVisibleFaces} compares block
 *       coordinates with a three-block slack, so the mask flips INSIDE a
 *       section (at x ≡ 14 and x ≡ 3 mod 16). The band is the exact
 *       granularity; see the method.</li>
 *   <li><b>faceCull</b> — Sodium's block-face-culling option, read per
 *       build, so a runtime toggle misses.</li>
 * </ul>
 * <p>The pass is the entry's slot; the geometry buffer is not in the key
 * because it is interned per frame by the draw list, and a replaced
 * buffer bumps the epoch (H3) anyway.
 *
 * <h2>What a hit replays</h2>
 * <p>Runs as (firstVertex, quads, sectionX, sectionY, sectionZ). The
 * camera-relative origins are recomputed per frame from the section
 * coordinates — the ring write is unchanged by the cache — and the pass
 * totals (quads kept and culled) are carried so the bench's ratio stays
 * exact on a frame of hits.
 *
 * <h2>Safety under defragmentation</h2>
 * <p>{@code firstVertex} is a base vertex inside Sodium's arena, and the
 * arena moves segments. Every path that moves one passes through H3 (a
 * buffer replaced: every base vertex re-derived) or H4 (a same-buffer
 * move), both hooked at HEAD, both bumping the epoch before the frame's
 * first {@code render()}. A stale base vertex would draw WRONG geometry
 * rather than missing geometry, which is why the hook audit in the pin
 * phase and the suite's transition leg exist.
 *
 * <p>Holds ints and bytes only. No Sodium object is referenced from here,
 * so a deleted region's cache pins nothing.
 */
public final class MesheliumRegionRunCache {

    /** Ints per cached run. */
    static final int RUN_STRIDE = 5;

    static final int RUN_FIRST_VERTEX = 0;
    static final int RUN_QUADS = 1;
    static final int RUN_X = 2;
    static final int RUN_Y = 3;
    static final int RUN_Z = 4;

    /** One pass's cached runs and the key they were built against. */
    static final class Entry {
        private boolean valid;
        private int epoch;
        private boolean faceCull;
        private int camKeyX;
        private int camKeyY;
        private int camKeyZ;
        private int count;
        private final byte[] sequence = new byte[256];
        private int runCount;
        private int[] runs = new int[RUN_STRIDE * 32];
        private long quadsKept;
        private long quadsCulled;
        private int sectionsEmitted;

        boolean matches(int epoch, boolean faceCull, int camKeyX, int camKeyY, int camKeyZ,
                byte[] candidate, int candidateCount) {
            return this.valid
                    && this.epoch == epoch
                    && this.faceCull == faceCull
                    && this.camKeyX == camKeyX
                    && this.camKeyY == camKeyY
                    && this.camKeyZ == camKeyZ
                    && this.count == candidateCount
                    && Arrays.equals(this.sequence, 0, candidateCount,
                            candidate, 0, candidateCount);
        }

        /** Start a rebuild: the entry is invalid until {@link #finish}. */
        void begin(int epoch, boolean faceCull, int camKeyX, int camKeyY, int camKeyZ,
                byte[] candidate, int candidateCount) {
            this.valid = false;
            this.epoch = epoch;
            this.faceCull = faceCull;
            this.camKeyX = camKeyX;
            this.camKeyY = camKeyY;
            this.camKeyZ = camKeyZ;
            this.count = candidateCount;
            System.arraycopy(candidate, 0, this.sequence, 0, candidateCount);
            this.runCount = 0;
            this.quadsKept = 0L;
            this.quadsCulled = 0L;
            this.sectionsEmitted = 0;
        }

        void addRun(int firstVertex, int quads, int sectionX, int sectionY, int sectionZ) {
            if ((this.runCount + 1) * RUN_STRIDE > this.runs.length) {
                this.runs = Arrays.copyOf(this.runs, this.runs.length * 2);
            }
            int r = this.runCount * RUN_STRIDE;
            this.runs[r + RUN_FIRST_VERTEX] = firstVertex;
            this.runs[r + RUN_QUADS] = quads;
            this.runs[r + RUN_X] = sectionX;
            this.runs[r + RUN_Y] = sectionY;
            this.runs[r + RUN_Z] = sectionZ;
            this.runCount++;
        }

        void finish(long quadsKept, long quadsCulled, int sectionsEmitted) {
            this.quadsKept = quadsKept;
            this.quadsCulled = quadsCulled;
            this.sectionsEmitted = sectionsEmitted;
            this.valid = true;
        }

        /** Sections of this region with at least one kept run (the stage-2 parity twin). */
        int sectionsEmitted() {
            return sectionsEmitted;
        }

        int runCount() {
            return this.runCount;
        }

        /** The run array; read {@link #runCount()} × {@link #RUN_STRIDE} ints. */
        int[] runs() {
            return this.runs;
        }

        long quadsKept() {
            return this.quadsKept;
        }

        long quadsCulled() {
            return this.quadsCulled;
        }
    }

    /** Indexed by the draw list's pass index; two opaque passes today. */
    private Entry[] entries = new Entry[2];

    /** The entry for a pass, created on first use. */
    Entry entry(int passIndex) {
        if (passIndex >= this.entries.length) {
            this.entries = Arrays.copyOf(this.entries, Math.max(passIndex + 1,
                    this.entries.length * 2));
        }
        Entry e = this.entries[passIndex];
        if (e == null) {
            e = new Entry();
            this.entries[passIndex] = e;
        }
        return e;
    }

    /**
     * The facing mask's dependence on one camera axis, reduced to one int.
     *
     * <p>Sodium's {@code DefaultChunkRenderer.getVisibleFaces} (javap,
     * 0.9.2-beta.1, bytecode 0-149) sets a section's positive facing when
     * {@code cam > 16*s - 3} and its negative facing when
     * {@code cam < 16*s + 19}. Rearranged for integer {@code s}:
     * positive ⟺ {@code (cam + 2) >> 4 >= s}, negative ⟺
     * {@code (cam - 3) >> 4 <= s}. So every section's answer on this axis
     * is a function of {@code a = (cam + 2) >> 4} and {@code b = (cam - 3)
     * >> 4} alone, and {@code a - b} is 0 or 1 because the two operands
     * differ by 5. Packing {@code 2a + (a - b)} loses nothing.
     *
     * <p>The naive key — the camera's section, {@code cam >> 4} — is NOT
     * exact: at {@code cam = 13} and {@code cam = 14} the section is the
     * same and the positive facing of section 1 differs (13 > 13 is false,
     * 14 > 13 is true). {@link #cameraKeyLatticeFailure()} proves both
     * halves at runtime.
     */
    public static int cameraKey(int cam) {
        int a = (cam + 2) >> 4;
        int b = (cam - 3) >> 4;
        return (a << 1) + (a - b);
    }

    /**
     * Self-check of {@link #cameraKey(int)} against Sodium's own facing
     * test, on a lattice of camera and section coordinates that crosses
     * every band boundary in both signs.
     *
     * <p>Two claims: equal keys imply equal masks (the cache never replays
     * under a mask that changed), and the naive section key would have
     * failed the same lattice (the check can fail, so a green result means
     * something). Run by the stand-down suite rather than a unit test
     * because the sodium tree has no test source set and the method under
     * test is Sodium's.
     *
     * @return null when both claims hold, otherwise a description of the
     *         first counterexample
     */
    public static String cameraKeyLatticeFailure() {
        boolean naiveFailed = false;
        for (int cam = -40; cam <= 40; cam++) {
            for (int other = -40; other <= 40; other++) {
                boolean sameKey = cameraKey(cam) == cameraKey(other);
                boolean sameSection = (cam >> 4) == (other >> 4);
                for (int s = -4; s <= 4; s++) {
                    boolean sameMask = DefaultChunkRenderer.getVisibleFaces(cam, 0, 0, s, 0, 0)
                            == DefaultChunkRenderer.getVisibleFaces(other, 0, 0, s, 0, 0);
                    if (sameKey && !sameMask) {
                        return "cameraKey(" + cam + ") == cameraKey(" + other
                                + ") but getVisibleFaces differs for section " + s
                                + "; the run cache would replay under a stale facing mask";
                    }
                    if (sameSection && !sameMask) {
                        naiveFailed = true;
                    }
                }
            }
        }
        if (!naiveFailed) {
            return "the camera-section key passed the facing lattice, so the lattice "
                    + "cannot tell an exact key from a wrong one; the check is vacuous";
        }
        return null;
    }
}
