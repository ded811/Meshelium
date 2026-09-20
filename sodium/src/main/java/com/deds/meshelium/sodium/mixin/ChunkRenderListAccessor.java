/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the 256-bit geometry bitmap Sodium already keeps for a listed
 * region, so Meshelium stops rebuilding a copy of it every frame.
 *
 * <h2>The duplication this removes</h2>
 * <p>{@code MesheliumRegionMirror.buildFrame} needs, per listed region,
 * the set of section slots that hold opaque geometry. Until 2026-09-17 it
 * got that by draining {@code sectionsWithGeometryIterator} — an
 * allocation per region plus one interface-dispatched step per section —
 * and folding the bytes back into a bitmap. Sodium had already built that
 * exact bitmap on the way in.
 *
 * <p>The two are bit-for-bit the same set, and the bytecode says so
 * rather than the naming: {@code ChunkRenderList.add(int)} guards ONE
 * branch with {@code getSectionFlags(i) & 1} (javap, ip 39-45) and inside
 * it writes BOTH {@code sectionsWithGeometryMap[i >> 6] |= 1L << (i & 63)}
 * (ip 48-65) and {@code sectionsWithGeometry[count++] = (byte) i} (ip
 * 66-86). One branch, two writes: the map and the byte array cannot
 * disagree. {@code reset(int)} zeroes the map in lockstep with the count
 * (ip 8-15), and {@code prepareForRender} only compares it with the
 * previous frame's copy and arraycopies it aside — it never clears the
 * live one. So a read taken where the drain used to run sees the same
 * frame's bits.
 *
 * <p>The word widths differ and nothing else: Meshelium packs 8 ints,
 * Sodium 4 longs, both little-endian over the same 256 slot indices, so
 * {@code mask[2k] = (int) map[k]} and {@code mask[2k+1] = (int)(map[k] >>> 32)}
 * is the whole substitution.
 *
 * <h2>Why this is safe to be an accessor at all</h2>
 * <p>House rule from the seam review (2026-09-17): Mixin's
 * {@code defaultRequire: 1} is soft for a missing METHOD and FATAL for an
 * injection point that matches nothing, so any seam that is not a
 * whole-method HEAD/TAIL must be checked by the config plugin before it is
 * trusted. An {@code @Accessor} whose field has been renamed leaves this
 * interface unimplemented on the target and the call site would die with
 * an {@code AbstractMethodError} at the first frame — loud, but at the
 * worst possible moment.
 *
 * <p>So {@code MesheliumSodiumMixinPlugin.preApply} looks the field up by
 * name and descriptor in the class node and records the answer in
 * {@code MesheliumSodiumHooks}; the mirror calls this accessor only when
 * that check passed, and falls back to the drain when it did not. A Sodium
 * update that renames the field therefore costs the frame time this class
 * saves, and nothing else.
 *
 * <h2>Clean room</h2>
 * <p>Sodium is PolyForm Shield. This interface names one private field of
 * Sodium's and contains no Sodium code.
 */
@Mixin(ChunkRenderList.class)
public interface ChunkRenderListAccessor {

    /**
     * Sodium's own per-frame geometry bitmap for this region: four longs,
     * 256 bits, bit {@code i} set when local section index {@code i} holds
     * opaque geometry this frame.
     *
     * <p>The array is Sodium's live field, not a copy. It is read on the
     * render thread inside the same frame that built it and is never
     * retained.
     */
    @Accessor("sectionsWithGeometryMap")
    long[] meshelium$sectionsWithGeometryMap();
}
