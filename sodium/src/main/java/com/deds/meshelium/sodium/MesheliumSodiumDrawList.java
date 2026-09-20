/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.vk.SodiumTerrainDrawer;

import com.mojang.blaze3d.buffers.GpuBuffer;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Arrays;
import java.util.Iterator;

/**
 * Turns Sodium's per-frame visible-section lists into the flat arrays
 * Meshelium's drawer takes.
 *
 * <p>This class is the whole Sodium-typed surface of the draw path, and it
 * is deliberately the only one: everything downstream — {@link
 * SodiumTerrainDrawer} and the pipeline and the shader — sees primitives
 * and Blaze3D types. That is what lets the drawing half live in
 * {@code common}, which compiles against vanilla alone.</p>
 *
 * <h2>How a section is located</h2>
 * <p>Sodium's visible list is a list of regions, each carrying a byte
 * array of local section indices. The index does not resolve to a
 * {@code RenderSection} through any public method, and should not: Sodium's
 * own hot loop reconstructs the section's position arithmetically instead,
 * from the region's chunk coordinates plus the index's packed local
 * coordinates, and that touches only immutable ints that are already in
 * hand. The geometry's address comes from the pass's
 * {@code SectionRenderDataStorage}: a base VERTEX offset into the region's
 * buffer plus one vertex count per facing.</p>
 *
 * <h2>Two paths: the walk and the cache (D-021)</h2>
 * <p>The walk reads every listed section's record every pass. Stage 0
 * (MEASUREMENTS.md 0k) measured it at 464 us per pass at aerial rd96 —
 * half the frame — and 90-100 us at eye level, and found the frame at
 * distance CPU-bound on exactly that. So by default each region's runs
 * are cached on the region ({@link MesheliumRegionRunCache}, reached
 * through {@link MesheliumRegionCacheHolder}) and replayed while the
 * region's mutation epoch, its listed byte sequence, the camera's facing
 * band and the face-cull option are unchanged. A hit costs one iterator
 * drain and a byte compare; the per-frame origin arithmetic and the ring
 * write are unchanged.
 *
 * <p>The walk is kept whole and reachable —
 * {@code -Dmeshelium.sodium.runCache=false}, or the bench's
 * {@code SodiumTerrainDrawer.setRunCacheEnabled(false)} — because it is
 * the parity control: the cache's output must equal the walk's run for
 * run, and the stand-down suite asserts that by counters at the same
 * pose. The cache also arms only when all five record-mutation hooks were
 * found by the mixin plugin ({@link MesheliumSodiumHooks}) and stands
 * down for the session if a pass ever draws geometry before the upload
 * hook has fired once — geometry cannot exist without an upload, so that
 * would mean the hook is not wired.
 *
 * <h2>Two traps this deals with</h2>
 * <ul>
 *   <li>The byte list is PASS-AGNOSTIC. One bit per section says "has
 *       geometry", not "has geometry in this pass", so drawing the solid
 *       pass hands you every leaves-only and glass-only section too.
 *       Sodium filters afterwards; here the zero total vertex count does
 *       the same job, because a section with nothing in this pass reads a
 *       zero-filled record.</li>
 *   <li>{@code sectionsWithGeometryIterator} returns NULL, not an empty
 *       iterator, when a region's count is zero.</li>
 * </ul>
 *
 * <h2>Clean room</h2>
 * <p>Sodium is PolyForm Shield. This file calls Sodium's public methods and
 * reads its public constants; it contains no Sodium code.</p>
 */
final class MesheliumSodiumDrawList {

    /** 0..255 in slot order, for the all-slots diagnostic; one per thread, reset per region. */
    private static final class AllSlots implements ByteIterator {
        private int next;

        void reset() {
            this.next = 0;
        }

        @Override
        public boolean hasNext() {
            return this.next < 256;
        }

        @Override
        public int nextByteAsInt() {
            return this.next++;
        }
    }

    private static final ThreadLocal<AllSlots> ALL_SLOTS = ThreadLocal.withInitial(AllSlots::new);

    /**
     * Replays a drained byte sequence to the walk on a cache miss, so the
     * miss path and the parity control run the SAME per-section code over
     * the same bytes. Sodium's iterator was consumed to build the key.
     */
    private static final class Sequence implements ByteIterator {
        private byte[] data;
        private int next;
        private int end;

        void reset(byte[] data, int count) {
            this.data = data;
            this.next = 0;
            this.end = count;
        }

        @Override
        public boolean hasNext() {
            return this.next < this.end;
        }

        @Override
        public int nextByteAsInt() {
            return this.data[this.next++] & 0xFF;
        }
    }

    /**
     * Per-facing runs live inside one contiguous allocation, so a section
     * is one draw. Kept as a named constant because the assumption is
     * load-bearing: it holds because opaque meshes are built in the
     * default shape, where the seven runs are written in facing order into
     * a single {@code BufferSegment}.
     */
    private static final int FACINGS = ModelQuadFacing.COUNT;

    /**
     * {@code -Dmeshelium.sodium.diag.allSlots=true}: enumerate every one of
     * a listed region's 256 section slots instead of Sodium's
     * sections-with-geometry set, so every section that HAS geometry in a
     * BFS-listed region is drawn whether or not the BFS found it visible.
     * Diagnostic only (GPU-VISIBILITY-DESIGN.md stage 0, P0.3): it prices a
     * frustum-only GPU draw — the upper bound a GPU-resident path would pay
     * before occlusion claws it back. Empty slots read a zero-filled record
     * and emit nothing, which is the same property the geometry set relies
     * on. Disarms the run cache: its key is the geometry set.
     */
    private static final boolean DIAG_ALL_SLOTS = Boolean.getBoolean("meshelium.sodium.diag.allSlots");

    /**
     * Set to {@code false} to draw every facing of every section, the way
     * this path did before 2026-09-05. Kept as an A/B lever and as the
     * immediate answer if culling ever leaves a hole.
     */
    static final String PROPERTY_FACE_CULL = "meshelium.sodium.facecull";

    /** Shared with the GPU-visibility loop, so both paths read one lever. */
    static final boolean FACE_CULL =
            !"false".equalsIgnoreCase(System.getProperty(PROPERTY_FACE_CULL));

    /** Logged once per session, from whichever draw list trips it. */
    private static boolean uploadGuardReported;

    /** Quads emitted and quads skipped in the most recent build, for the bench. */
    private long quadsKept;
    private long quadsCulled;

    /**
     * Sections with at least one kept run in the most recent build: the
     * CPU twin of the GPU stats' VisMode-0 survivor count, which is what
     * the stage-2 parity leg compares (contract section 9).
     */
    private int sectionsEmitted;

    private GpuBuffer[] buffers = new GpuBuffer[64];
    private int bufferCount;

    private int[] meta = new int[256 * SodiumTerrainDrawer.META_STRIDE];
    private float[] origins = new float[256 * 3];
    private int drawCount;
    private int regionCount;

    /** One region's listed sections, drained from Sodium's iterator; 256 is every slot. */
    private final byte[] sequence = new byte[256];

    private final Sequence replay = new Sequence();

    /**
     * Passes seen by this list, interned to a small index. The cache holds
     * the index and never the pass, so a region's cache references no
     * Sodium object; the table is per draw list, which is per
     * {@code RenderSectionManager}, the same lifetime as the regions.
     */
    private TerrainRenderPass[] passes = new TerrainRenderPass[4];
    private int passCount;

    /** The entry being rebuilt on a miss; null on the walk. {@link #push} appends to it. */
    private MesheliumRegionRunCache.Entry recording;

    private boolean cacheArmed;
    private int regionsHit;
    private int regionsRebuilt;

    /**
     * The frustum lever ({@link SodiumTerrainDrawer#PROPERTY_FRUSTUM}):
     * whether every pushed run is tested against the six planes here, and
     * whether a failing run is left out of the pass arrays (cpu mode) or
     * only counted (count mode, or the diagnostic under gpu mode).
     */
    private final boolean frustumTest = SodiumTerrainDrawer.frustumCpuTestWanted();
    private final boolean frustumDrop = SodiumTerrainDrawer.FRUSTUM_MODE == SodiumTerrainDrawer.FRUSTUM_CPU;

    /** Six planes as (a, b, c, d): the Gribb-Hartmann rows of projection * modelView. */
    private final float[] planes = new float[24];
    private final Matrix4f mvpScratch = new Matrix4f();

    /** Runs pushed this build before any dropping; the two rejects are 0 unless the test ran. */
    private int runsPushed;
    private int runsFrustumRejected;
    private long quadsFrustumRejected;

    GpuBuffer[] buffers() {
        return buffers;
    }

    int bufferCount() {
        return bufferCount;
    }

    int[] meta() {
        return meta;
    }

    float[] origins() {
        return origins;
    }

    int drawCount() {
        return drawCount;
    }

    int regionCount() {
        return regionCount;
    }

    long quadsKept() {
        return quadsKept;
    }

    /**
     * Quads the camera-facing test removed. Reported next to
     * {@link #quadsKept()} because the ratio is the only honest measure of
     * whether facing culling is doing anything in a given scene — a frame
     * time can move for a dozen reasons, but this cannot.
     */
    long quadsCulled() {
        return quadsCulled;
    }

    /** Sections with at least one kept run in the most recent build. */
    int sectionsEmitted() {
        return sectionsEmitted;
    }

    /** True when the most recent build used the per-region cache. */
    boolean cacheArmed() {
        return cacheArmed;
    }

    /** Regions replayed from their cache in the most recent build. */
    int regionsHit() {
        return regionsHit;
    }

    /** Regions walked and re-cached in the most recent build. */
    int regionsRebuilt() {
        return regionsRebuilt;
    }

    /** Runs pushed in the most recent build, before the frustum lever dropped any. */
    int runsPushed() {
        return runsPushed;
    }

    /** Runs the CPU frustum test found outside the frustum (0 unless it ran). */
    int runsFrustumRejected() {
        return runsFrustumRejected;
    }

    /** Quads of the runs {@link #runsFrustumRejected()} counts. */
    long quadsFrustumRejected() {
        return quadsFrustumRejected;
    }

    /**
     * Walk Sodium's lists for one pass and record every section that has
     * geometry in it.
     *
     * <p>Nothing of Sodium's is retained past the call. Sodium's render
     * lists and their byte iterators are per-region objects that get reset
     * and refilled every frame, and the geometry record behind
     * {@code getDataPointer} is rewritten whenever an arena defragments —
     * so both are read here and turned into plain numbers immediately. What
     * the cache keeps is those plain numbers, keyed so that every rewrite
     * of the record invalidates them (see {@link MesheliumRegionRunCache}).</p>
     */
    void build(ChunkRenderListIterable renderLists, TerrainRenderPass pass, CameraTransform camera,
            Matrix4fc modelView, Matrix4fc projection) {
        this.bufferCount = 0;
        this.drawCount = 0;
        this.regionCount = 0;
        this.quadsKept = 0L;
        this.quadsCulled = 0L;
        this.sectionsEmitted = 0;
        this.regionsHit = 0;
        this.regionsRebuilt = 0;
        this.recording = null;
        this.runsPushed = 0;
        this.runsFrustumRejected = 0;
        this.quadsFrustumRejected = 0L;
        if (this.frustumTest) {
            setPlanes(modelView, projection);
        }

        // Honour Sodium's own switch. With block-face culling off, Sodium
        // draws every facing and so must we, or the two renderers disagree
        // about what the setting means.
        boolean faceCull = FACE_CULL;
        try {
            faceCull = faceCull && SodiumClientMod.options().performance.useBlockFaceCulling;
        } catch (Throwable ignored) {
            // Options not loaded yet, or moved in a future Sodium. Drawing
            // every facing is the answer that cannot be wrong.
            faceCull = false;
        }

        boolean translucent = pass.isTranslucent();
        this.cacheArmed = runCacheArmed();
        if (this.cacheArmed) {
            buildCached(renderLists, pass, camera, faceCull, translucent);
            if (this.drawCount > 0 && SodiumTerrainDrawer.hookUpload() == 0L) {
                // Geometry cannot exist in a record without setVertexData,
                // whose only caller is the hooked uploadResults. Drawing
                // anything before that hook has fired once means the hook
                // is not wired, and a cache with no invalidation would draw
                // last week's geometry. This build was all misses (the cache
                // was empty), so its output is the walk's; the next is too.
                MesheliumSodiumHooks.disarm("a pass drew " + this.drawCount
                        + " runs while the upload hook had never fired");
                if (!uploadGuardReported) {
                    uploadGuardReported = true;
                    MesheliumLog.LOGGER.warn(
                            "Meshelium's per-region run cache is standing down for this "
                                    + "session: {}. The enumeration walk draws instead "
                                    + "(reported once).", MesheliumSodiumHooks.disarmReason());
                }
            }
        } else {
            buildWalk(renderLists, pass, camera, faceCull, translucent);
        }
    }

    /**
     * The cache may run only when every hook is wired and nothing has
     * stood it down. Read per build, not latched: {@code RenderRegion} can
     * load after the first pass in a world that has no regions yet, and
     * the bench flips the lever between legs.
     */
    private static boolean runCacheArmed() {
        return !DIAG_ALL_SLOTS
                && SodiumTerrainDrawer.runCacheEnabled()
                && MesheliumSodiumHooks.hooksComplete()
                && !MesheliumSodiumHooks.disarmed();
    }

    /** The parity control: the enumeration exactly as it ran before the cache. */
    private void buildWalk(ChunkRenderListIterable renderLists, TerrainRenderPass pass,
            CameraTransform camera, boolean faceCull, boolean translucent) {
        Iterator<ChunkRenderList> lists = renderLists.iterator(translucent);
        while (lists.hasNext()) {
            ChunkRenderList list = lists.next();
            RenderRegion region = list.getRegion();
            if (region == null) {
                continue;
            }
            // Region-level short circuit: null means no section in this
            // whole region has geometry for this pass.
            SectionRenderDataStorage storage = region.getStorage(pass);
            if (storage == null) {
                continue;
            }
            RenderRegion.DeviceResources resources = region.getResources();
            if (resources == null) {
                continue;
            }
            GpuBuffer geometry = resources.getGeometryBuffer();
            if (geometry == null) {
                continue;
            }
            ByteIterator sections = list.sectionsWithGeometryIterator(translucent);
            if (DIAG_ALL_SLOTS) {
                sections = ALL_SLOTS.get();
                ((AllSlots) sections).reset();
            }
            if (sections == null) {
                continue; // null, not empty — Sodium's own loop checks this
            }
            if (walkRegion(region, storage, geometry, sections, camera, faceCull)) {
                this.regionCount++;
            }
        }
    }

    /**
     * The cached enumeration: the same region-level checks as the walk,
     * then a key compare per region and either a replay or a rebuild.
     */
    private void buildCached(ChunkRenderListIterable renderLists, TerrainRenderPass pass,
            CameraTransform camera, boolean faceCull, boolean translucent) {
        int passIndex = passIndex(pass);
        // The facing mask depends on the camera only through these three
        // ints; when culling is off it does not depend on the camera at
        // all, and keying on it would only cost hits while turning.
        int camKeyX = faceCull ? MesheliumRegionRunCache.cameraKey(camera.intX) : 0;
        int camKeyY = faceCull ? MesheliumRegionRunCache.cameraKey(camera.intY) : 0;
        int camKeyZ = faceCull ? MesheliumRegionRunCache.cameraKey(camera.intZ) : 0;
        byte[] seq = this.sequence;

        Iterator<ChunkRenderList> lists = renderLists.iterator(translucent);
        while (lists.hasNext()) {
            ChunkRenderList list = lists.next();
            RenderRegion region = list.getRegion();
            if (region == null) {
                continue;
            }
            SectionRenderDataStorage storage = region.getStorage(pass);
            if (storage == null) {
                continue;
            }
            RenderRegion.DeviceResources resources = region.getResources();
            if (resources == null) {
                continue;
            }
            GpuBuffer geometry = resources.getGeometryBuffer();
            if (geometry == null) {
                continue;
            }
            ByteIterator sections = list.sectionsWithGeometryIterator(translucent);
            if (sections == null) {
                continue;
            }
            // Drain the iterator into the candidate key. Sodium's list is
            // refilled per frame, so the bytes are read now and the
            // iterator is not touched again; a local index is 8 bits, so
            // more than 256 of them is a broken invariant, not a big region.
            int n = 0;
            while (sections.hasNext()) {
                if (n == seq.length) {
                    throw new IllegalStateException(
                            "Sodium listed more than 256 sections for one region");
                }
                seq[n++] = (byte) sections.nextByteAsInt();
            }
            if (!(region instanceof MesheliumRegionCacheHolder holder)) {
                // The field mixin did not apply though the injectors did.
                // Not a state this build can cache in; walk this region and
                // every later build.
                MesheliumSodiumHooks.disarm(
                        "RenderRegion does not carry Meshelium's cache field");
                this.replay.reset(seq, n);
                if (walkRegion(region, storage, geometry, this.replay, camera, faceCull)) {
                    this.regionCount++;
                }
                this.regionsRebuilt++;
                continue;
            }
            MesheliumRegionRunCache cache = holder.meshelium$runCache();
            if (cache == null) {
                cache = new MesheliumRegionRunCache();
                holder.meshelium$setRunCache(cache);
            }
            MesheliumRegionRunCache.Entry entry = cache.entry(passIndex);
            int epoch = holder.meshelium$epoch();
            if (entry.matches(epoch, faceCull, camKeyX, camKeyY, camKeyZ, seq, n)) {
                if (replayEntry(entry, geometry, camera)) {
                    this.regionCount++;
                }
                this.regionsHit++;
            } else {
                entry.begin(epoch, faceCull, camKeyX, camKeyY, camKeyZ, seq, n);
                long keptBefore = this.quadsKept;
                long culledBefore = this.quadsCulled;
                int sectionsBefore = this.sectionsEmitted;
                this.recording = entry;
                this.replay.reset(seq, n);
                boolean drew = walkRegion(region, storage, geometry, this.replay, camera,
                        faceCull);
                this.recording = null;
                entry.finish(this.quadsKept - keptBefore, this.quadsCulled - culledBefore,
                        this.sectionsEmitted - sectionsBefore);
                if (drew) {
                    this.regionCount++;
                }
                this.regionsRebuilt++;
            }
        }
    }

    /**
     * A cache hit: the region's runs, with this frame's camera-relative
     * origins. The pass totals are carried too, whether or not any run
     * survived culling, because a region whose every quad was culled still
     * contributed to {@code quadsCulled} on the walk.
     *
     * @return true if anything was emitted
     */
    private boolean replayEntry(MesheliumRegionRunCache.Entry entry, GpuBuffer geometry,
            CameraTransform camera) {
        this.quadsKept += entry.quadsKept();
        this.quadsCulled += entry.quadsCulled();
        this.sectionsEmitted += entry.sectionsEmitted();
        int runs = entry.runCount();
        if (runs == 0) {
            return false;
        }
        // Interned here, per region per frame, exactly where the walk
        // interns it: the index depends on list order, which is per frame.
        int bufferIndex = intern(geometry);
        int[] r = entry.runs();
        for (int i = 0; i < runs; i++) {
            int base = i * MesheliumRegionRunCache.RUN_STRIDE;
            int sectionX = r[base + MesheliumRegionRunCache.RUN_X];
            int sectionY = r[base + MesheliumRegionRunCache.RUN_Y];
            int sectionZ = r[base + MesheliumRegionRunCache.RUN_Z];
            // The mirror's arithmetic with no region in scope: the region
            // origin (RenderRegion's 3/2/3-bit chunk shifts) minus the
            // camera in double, cast once, plus 16*local in float.
            // Bit-identical to terrain.mesh's float(regionOrigin - camera)
            // + 16*l (Java's float add is IEEE RNE, Vulkan's FAdd is
            // correctly rounded, 16*l is exact), so VisMode 0 and rung 1
            // agree in vertex bits, not just in sets (second stage-2/3
            // review, 2026-09-07).
            float ox = (float) ((sectionX & ~7) * 16.0 - camera.x) + 16.0f * (sectionX & 7);
            float oy = (float) ((sectionY & ~3) * 16.0 - camera.y) + 16.0f * (sectionY & 3);
            float oz = (float) ((sectionZ & ~7) * 16.0 - camera.z) + 16.0f * (sectionZ & 7);
            push(bufferIndex, r[base + MesheliumRegionRunCache.RUN_FIRST_VERTEX],
                    r[base + MesheliumRegionRunCache.RUN_QUADS],
                    sectionX, sectionY, sectionZ, ox, oy, oz);
        }
        return true;
    }

    /**
     * The per-section enumeration of one region: shared by the walk (over
     * Sodium's iterator) and by a cache miss (over the drained bytes), so
     * the two can only differ in what they iterate.
     *
     * @return true if anything was emitted from this region
     */
    private boolean walkRegion(RenderRegion region, SectionRenderDataStorage storage,
            GpuBuffer geometry, ByteIterator sections, CameraTransform camera,
            boolean faceCull) {
        int bufferIndex = -1; // resolved lazily; a region can contribute nothing
        boolean drewFromRegion = false;
        // The region origin minus the camera in double, cast once; each
        // section adds 16*local in float below. Bit-identical to
        // terrain.mesh's float(regionOrigin - camera) + 16*l on the GPU
        // path (Java's float add is IEEE round-to-nearest-even, Vulkan's
        // FAdd is correctly rounded, and 16*l is exact), so VisMode 0 and
        // rung 1 agree in vertex bits, not only in sets; the per-section
        // double cast this replaces could differ by an ulp (second
        // stage-2/3 review, 2026-09-07).
        float rx = (float) (region.getOriginX() - camera.x);
        float ry = (float) (region.getOriginY() - camera.y);
        float rz = (float) (region.getOriginZ() - camera.z);

        while (sections.hasNext()) {
            int index = sections.nextByteAsInt();
            long record = storage.getDataPointer(index);
            if (record == 0L) {
                continue;
            }
            int sectionX = region.getChunkX() + LocalSectionIndex.unpackX(index);
            int sectionY = region.getChunkY() + LocalSectionIndex.unpackY(index);
            int sectionZ = region.getChunkZ() + LocalSectionIndex.unpackZ(index);

            // Which facings can the camera possibly see, and which does
            // this section actually have geometry for. Two DIFFERENT
            // masks, both in facing-ordinal space, and using only the
            // second (as the original plan said to) culls nothing at
            // all — getSliceMask carries no camera information.
            //
            // getVisibleFaces is Sodium's own public static method
            // rather than a reimplementation of it. Its answer feeds
            // directly into which vertices get drawn, so a copy that
            // drifted by one block of slack would put holes in the
            // world; calling it cannot drift.
            int visible = faceCull
                    ? DefaultChunkRenderer.getVisibleFaces(
                            camera.intX, camera.intY, camera.intZ,
                            sectionX, sectionY, sectionZ)
                    : ModelQuadFacing.ALL;
            int mask = visible & SectionRenderDataUnsafe.getSliceMask(record);
            if (mask == 0) {
                continue; // nothing of this section faces us in this pass
            }
            if (bufferIndex < 0) {
                bufferIndex = intern(geometry);
            }
            float ox = rx + 16.0f * LocalSectionIndex.unpackX(index);
            float oy = ry + 16.0f * LocalSectionIndex.unpackY(index);
            float oz = rz + 16.0f * LocalSectionIndex.unpackZ(index);
            if (emitRuns(record, bufferIndex, sectionX, sectionY, sectionZ, ox, oy, oz, mask)) {
                drewFromRegion = true;
                this.sectionsEmitted++;
            }
        }
        return drewFromRegion;
    }

    /**
     * Turn one section's seven facing runs into as few contiguous draws as
     * the kept set allows.
     *
     * <h2>The trap</h2>
     * <p>The vertex cursor advances over ALL seven runs, including the
     * culled ones. Only the emitted run is conditional. Skipping the
     * advance for a dropped facing shifts every later run's base vertex
     * and draws a different part of Sodium's arena — geometry that is
     * WRONG rather than missing, which is far harder to notice than a
     * hole and much harder to trace back here.
     *
     * <h2>Two smaller things, both deliberate</h2>
     * <p>An EMPTY facing is transparent to merging: it neither flushes the
     * pending run nor breaks it, so a section with gaps in its facing set
     * still comes out as one draw where the kept facings are adjacent in
     * the buffer. Sodium does the same.
     *
     * <p>The loop runs to {@code <= FACINGS}. That extra iteration is the
     * flush sentinel for a run still open at the end; without it a section
     * whose last facing is kept silently loses it.
     *
     * @return true if anything was emitted
     */
    private boolean emitRuns(long record, int bufferIndex,
            int sectionX, int sectionY, int sectionZ,
            float ox, float oy, float oz, int mask) {
        long facingList = SectionRenderDataUnsafe.getFacingList(record);
        long cursor = SectionRenderDataUnsafe.getBaseVertex(record);
        long runStart = 0L;
        long pending = 0L;
        boolean any = false;
        for (int i = 0; i <= FACINGS; i++) {
            if (i < FACINGS) {
                long count = SectionRenderDataUnsafe.getVertexCount(record, i);
                if (count == 0L) {
                    // Never read the facing byte for an unpopulated run:
                    // the backing array is zero-filled and empty slots are
                    // never written, so it would read as facing 0, POS_X.
                    continue;
                }
                // Byte i of the facing list is run i's facing ordinal. The
                // list is loaded as an unmasked 64-bit read whose top byte
                // is the isLocalIndex flag, which is why nothing here ever
                // indexes byte 7.
                int facing = (int) ((facingList >>> (i * 8)) & 0xFFL);
                if (((mask >>> facing) & 1) != 0) {
                    if (pending == 0L) {
                        runStart = cursor;
                    }
                    pending += count;
                    quadsKept += count >> 2;
                } else {
                    if (pending > 0L) {
                        push(bufferIndex, (int) runStart, (int) (pending >> 2),
                                sectionX, sectionY, sectionZ, ox, oy, oz);
                        pending = 0L;
                        any = true;
                    }
                    quadsCulled += count >> 2;
                }
                cursor += count; // ALWAYS. See the trap above.
            } else if (pending > 0L) {
                push(bufferIndex, (int) runStart, (int) (pending >> 2),
                        sectionX, sectionY, sectionZ, ox, oy, oz);
                any = true;
            }
        }
        return any;
    }

    /**
     * Index of this buffer in {@link #buffers}, adding it if new.
     *
     * <p>Linear because the count is small — Sodium's arenas are shared
     * between regions, so a full render distance resolves to a handful of
     * distinct buffers, and the loop below is what lets the drawer push
     * descriptors once per buffer instead of once per section.</p>
     */
    private int intern(GpuBuffer buffer) {
        for (int i = 0; i < bufferCount; i++) {
            if (buffers[i] == buffer) {
                return i;
            }
        }
        if (bufferCount == buffers.length) {
            buffers = Arrays.copyOf(buffers, buffers.length * 2);
        }
        buffers[bufferCount] = buffer;
        return bufferCount++;
    }

    /** Index of this pass in {@link #passes}, adding it if new; linear, two entries. */
    private int passIndex(TerrainRenderPass pass) {
        for (int i = 0; i < passCount; i++) {
            if (passes[i] == pass) {
                return i;
            }
        }
        if (passCount == passes.length) {
            passes = Arrays.copyOf(passes, passes.length * 2);
        }
        passes[passCount] = pass;
        return passCount++;
    }

    /**
     * One draw into the pass arrays — and, while a cache miss is being
     * rebuilt, into the region's entry as well, with the section
     * coordinates the replay needs to recompute the origins.
     */
    private void push(int bufferIndex, int baseVertex, int quads,
            int sectionX, int sectionY, int sectionZ,
            float ox, float oy, float oz) {
        // The cache records every run, tested or not: its key carries no
        // frustum, so a replayed region is re-tested per run below.
        if (recording != null) {
            recording.addRun(baseVertex, quads, sectionX, sectionY, sectionZ);
        }
        this.runsPushed++;
        if (this.frustumTest && !inFrustum(ox, oy, oz)) {
            this.runsFrustumRejected++;
            this.quadsFrustumRejected += quads;
            if (this.frustumDrop) {
                return;
            }
        }
        if ((drawCount + 1) * SodiumTerrainDrawer.META_STRIDE > meta.length) {
            meta = Arrays.copyOf(meta, meta.length * 2);
            origins = Arrays.copyOf(origins, origins.length * 2);
        }
        int m = drawCount * SodiumTerrainDrawer.META_STRIDE;
        meta[m] = bufferIndex;
        meta[m + 1] = baseVertex;
        meta[m + 2] = quads;
        int o = drawCount * 3;
        origins[o] = ox;
        origins[o + 1] = oy;
        origins[o + 2] = oz;
        drawCount++;
    }

    /**
     * JOML {@code FrustumIntersection.testAab}'s p-vertex rule on the run's
     * section box, inflated by {@link SodiumTerrainDrawer#FRUSTUM_SLACK}:
     * the same test on the same rows as terrain.task's {@code inFrustum}
     * and the mesh stage's gpu-mode gate. {@code (ox, oy, oz)} is the
     * section origin relative to the camera, which is the space the rows
     * are in.
     */
    private boolean inFrustum(float ox, float oy, float oz) {
        float s = SodiumTerrainDrawer.FRUSTUM_SLACK;
        float lx = ox - s;
        float ly = oy - s;
        float lz = oz - s;
        float hx = ox + 16.0f + s;
        float hy = oy + 16.0f + s;
        float hz = oz + 16.0f + s;
        float[] p = this.planes;
        for (int i = 0; i < 24; i += 4) {
            float a = p[i];
            float b = p[i + 1];
            float c = p[i + 2];
            float px = a >= 0.0f ? hx : lx;
            float py = b >= 0.0f ? hy : ly;
            float pz = c >= 0.0f ? hz : lz;
            if (a * px + b * py + c * pz < -p[i + 3]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The six Gribb-Hartmann rows of projection * modelView, the formulas
     * {@code SodiumTerrainDrawer.putFrustumPlanes} uploads for the GPU
     * gates, so the CPU and GPU modes of the lever test one frustum.
     */
    private void setPlanes(Matrix4fc modelView, Matrix4fc projection) {
        Matrix4f m = this.mvpScratch.set(projection).mul(modelView);
        float[] p = this.planes;
        plane(p, 0, m.m03() + m.m00(), m.m13() + m.m10(), m.m23() + m.m20(), m.m33() + m.m30());
        plane(p, 4, m.m03() - m.m00(), m.m13() - m.m10(), m.m23() - m.m20(), m.m33() - m.m30());
        plane(p, 8, m.m03() + m.m01(), m.m13() + m.m11(), m.m23() + m.m21(), m.m33() + m.m31());
        plane(p, 12, m.m03() - m.m01(), m.m13() - m.m11(), m.m23() - m.m21(), m.m33() - m.m31());
        plane(p, 16, m.m03() + m.m02(), m.m13() + m.m12(), m.m23() + m.m22(), m.m33() + m.m32());
        plane(p, 20, m.m03() - m.m02(), m.m13() - m.m12(), m.m23() - m.m22(), m.m33() - m.m32());
    }

    private static void plane(float[] p, int i, float a, float b, float c, float d) {
        p[i] = a;
        p[i + 1] = b;
        p[i + 2] = c;
        p[i + 3] = d;
    }

    /** Drop the buffer references so a deleted region's buffer is not pinned. */
    void clearBufferRefs() {
        for (int i = 0; i < bufferCount; i++) {
            buffers[i] = null;
        }
        bufferCount = 0;
    }
}
