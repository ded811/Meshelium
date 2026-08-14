/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium;

import com.deds.meshelium.fabric.mixin.SectionUberBuffersAccessor;
import com.deds.meshelium.fabric.mixin.UberGpuBufferAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

import java.util.Map;

/**
 * How much GPU memory VANILLA's terrain copy is holding, measured rather
 * than derived.
 *
 * <h2>Why this exists</h2>
 * <p>Meshelium's kill switch cancels vanilla's terrain DRAWS but not its
 * UPLOADS, so vanilla keeps a complete second copy of the world that
 * nothing ever renders. Three separate derivations put that copy at
 * somewhere between 6.5 and 10 GiB at render distance 120, which is a
 * range wide enough to be useless for deciding whether to do anything
 * about it.</p>
 *
 * <p>Every one of those derivations was arithmetic on top of an
 * assumption. This is not: vanilla's terrain lives in a growable list of
 * FIXED-SIZE heaps per layer ({@code UberGpuBuffer.nodes}, 128 MiB vertex
 * and 32 MiB index heaps per {@code SectionRenderDispatcher.lambda$new$0}),
 * so {@code nodes.size() * heapSize} is the committed byte count including
 * fragmentation. A list length times a constant is a measurement.</p>
 *
 * <h2>What it is NOT</h2>
 * <p>Read-only. It allocates nothing, changes no behaviour, and touches no
 * GPU state. It exists to answer one question - is vanilla's copy really
 * where the memory went - before anyone writes the far riskier code that
 * would suppress it.</p>
 */
public final class VanillaTerrainCensus {

    private VanillaTerrainCensus() {
    }

    /** Committed bytes across every terrain layer, or -1 when unavailable. */
    public static long committedBytes() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.levelRenderer == null) {
            return -1L;
        }
        SectionRenderDispatcher dispatcher = minecraft.levelRenderer.sectionRenderDispatcher();
        if (dispatcher == null) {
            return -1L;
        }
        Map<?, ?> layers = ((SectionRenderDispatcherAccessorBridge) (Object) dispatcher)
                .meshelium$chunkUberBuffers();
        if (layers == null) {
            return -1L;
        }
        long total = 0L;
        for (Object buffers : layers.values()) {
            if (!(buffers instanceof SectionUberBuffersAccessor pair)) {
                continue;
            }
            total += bytesOf(pair.meshelium$vertexBuffer());
            total += bytesOf(pair.meshelium$indexBuffer());
        }
        return total;
    }

    private static long bytesOf(Object uberBuffer) {
        if (!(uberBuffer instanceof UberGpuBufferAccessor accessor)) {
            return 0L;
        }
        var nodes = accessor.meshelium$nodes();
        return nodes == null ? 0L : (long) nodes.size() * accessor.meshelium$heapSize();
    }

    /** Marker so the dispatcher accessor can live beside the other mixins. */
    public interface SectionRenderDispatcherAccessorBridge {
        Map<?, ?> meshelium$chunkUberBuffers();
    }
}
