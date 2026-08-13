/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.terrain.host;

import com.deds.meshelium.terrain.EncodedSectionMesh;
import com.deds.meshelium.terrain.SectionMeshEncoder;

import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;

/**
 * The two build-thread ends of the wave-3b tap (section-build doc Q3.1):
 * re-encode at {@code SectionCompiler.compile} RETURN, re-key to the
 * {@code CompiledSectionMesh} at its ctor TAIL. Both hooks run on the SAME
 * thread back to back ({@code CompileTask.doTask} bytecode: compile at
 * ip 100, the ctor at ip 153-161, no thread hop; the compileSync path is
 * the same method inlined on the render thread) — so the park between them
 * is a {@link ThreadLocal}, not a shared map: no contention, no cross-talk
 * when two overlapping doTasks build the SAME section on two workers (a
 * cancelled task can still be mid-flight while its replacement runs), and
 * a park orphaned by an exception is simply overwritten by the thread's
 * next compile. The {@code Results} identity check makes a stale park
 * detectable instead of mis-keyed. This implements the recon's
 * "park keyed by the Results object" (Q3.1) with the keying collapsed
 * onto the thread.
 *
 * <p><b>Resorts never arrive here by construction:</b>
 * {@code ResortTransparencyTask.doTask} calls neither
 * {@code SectionCompiler.compile} nor the {@code CompiledSectionMesh}
 * constructor (bytecode-verified: it only reads the LIVE mesh and hands
 * new index bytes to {@code addSectionBuffersToUberBuffer}) — so a resort
 * cannot trigger a re-encode even in principle.</p>
 *
 * <p>Callers (the mixins) gate on {@code MesheliumGate} BEFORE touching this
 * class, so it never loads on the OpenGL path's hot loop; this class in
 * turn imports no LWJGL.</p>
 */
public final class SectionBuildTap {

    private record Parked(SectionCompiler.Results results, int sx, int sy, int sz,
            EncodedSectionMesh encoded, int[] translucentOrder) {}

    private static final ThreadLocal<Parked> PARKED = new ThreadLocal<>();

    private SectionBuildTap() {}

    /**
     * {@code SectionCompiler.compile} RETURN, on the build thread: decode
     * the 28-byte BLOCK vertices of every rendered layer, derive facings,
     * order the translucent prefix by vanilla's own build-time distance
     * sort, encode to the 16-byte format, and park the result for the
     * ctor-TAIL re-key. Empty results (vanilla never uploads those either,
     * doTask ip 166-249) and 0-quad decodes park nothing.
     */
    public static void onCompileReturn(SectionPos pos, SectionCompiler.Results results) {
        PARKED.remove(); // drop any orphan from an earlier failed build
        if (results == null || results.renderedLayers.isEmpty()) {
            // Wave-11: an EMPTY compile is still a statement about the
            // world — the section has no geometry NOW — and it never
            // reaches the upload path (vanilla's empty short-circuit,
            // doTask ip 166-249), so the slot-steal supersede can never
            // fire for it. Signal the position so a RETAINED copy there
            // (rd shrink followed by a dig-out, the ghost-terrain edge)
            // dies with the world edit. No-op when nothing is retained.
            if (results != null) {
                TerrainResidency.onSectionCompiledEmpty(pos.x(), pos.y(), pos.z());
            }
            return;
        }
        try {
            VanillaMeshDecoder.DecodedSection decoded =
                    VanillaMeshDecoder.decode(results.renderedLayers);
            if (decoded.skippedLayers() > 0) {
                TerrainResidency.countDecoderSkips(decoded.skippedLayers());
            }
            if (decoded.quads().isEmpty()) {
                TerrainResidency.onSectionCompiledEmpty(pos.x(), pos.y(), pos.z()); // wave-11
                return;
            }
            EncodedSectionMesh encoded = SectionMeshEncoder.encode(decoded.quads());
            PARKED.set(new Parked(results, pos.x(), pos.y(), pos.z(), encoded,
                    decoded.translucentOrder()));
        } catch (Throwable t) {
            // e.g. a modded section beyond the u16-per-bucket quad budget —
            // drop THIS section, never the frame or the worker.
            TerrainResidency.countEncodeFailure(t);
        }
    }

    /**
     * {@code CompiledSectionMesh.<init>} TAIL, same thread: re-key the
     * parked encoding from the Results object to the mesh identity —
     * exactly the key vanilla's uber-buffer allocationMap uses (Q3.1), so
     * store and vanilla share lifetime semantics by construction.
     */
    public static void onMeshConstructed(Object mesh, SectionCompiler.Results results) {
        Parked parked = PARKED.get();
        if (parked == null) {
            return; // empty section, 0-quad decode, or tap disabled mid-build
        }
        PARKED.remove();
        if (parked.results() != results) {
            TerrainResidency.countStalePark();
            return;
        }
        TerrainResidency.enqueueUpload(mesh, parked.sx(), parked.sy(), parked.sz(),
                parked.encoded(), parked.translucentOrder());
    }
}
