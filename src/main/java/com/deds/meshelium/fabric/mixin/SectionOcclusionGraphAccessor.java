/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import net.minecraft.client.renderer.SectionOcclusionGraph;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 1.5.2 accessor: {@code SectionOcclusionGraph} keeps the flag that is the
 * ONLY on-demand trigger of vanilla's {@code visibleSections} rebuild in
 * {@code private final AtomicBoolean needsFrustumUpdate} (javap-verified).
 * {@code LevelExtractor.extract} consumes it (ip 256,
 * {@code consumeFrustumUpdate()} = compareAndSet(true, false)) and runs
 * {@code applyFrustum} — the list rebuild — only then or on a 2-degree
 * camera-rotation bucket crossing (ip 262-279); NO term involves the
 * projection. Vanilla itself sets the flag in exactly two places (bytecode
 * census): the tail of the ASYNC full-graph rebuild
 * ({@code lambda$scheduleFullUpdate$0} ip 91-96) and chunk-churn
 * propagation ({@code lambda$runPartialUpdate$2} ip 11-16).
 *
 * <p>The FOV-reveal heal ({@code TerrainDrawer.healFrustumOnProjectionChange})
 * sets this flag when the render projection changed, so the NEXT extract
 * rebuilds the list through vanilla's own path with the already-widened
 * cull frustum — byte-for-byte what a rotation crossing would have done.
 * Getter only (the field is final; mutation goes through
 * {@code AtomicBoolean.set}); a synthetic accessor on a backend-neutral
 * class, zero behaviour unless called — and only the armed drawer calls
 * it, so GL dormancy is untouched.</p>
 */
@Mixin(SectionOcclusionGraph.class)
public interface SectionOcclusionGraphAccessor {

    @Accessor("needsFrustumUpdate")
    AtomicBoolean meshelium$needsFrustumUpdate();
}
