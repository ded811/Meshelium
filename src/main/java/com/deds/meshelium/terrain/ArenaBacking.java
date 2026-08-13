/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.terrain;

/**
 * The ONE seam between the CPU-side terrain arena and whatever actually
 * backs it. Wave 3a tests hand back a dummy handle; wave 3b implements this
 * with a VkBuffer (DEVICE_LOCAL + SHADER_DEVICE_ADDRESS — the cross-vendor
 * equivalent of Nvidium's resident device-only buffer,
 * NVIDIUM-ARCHITECTURE.md §3/§10 row 5).
 *
 * <p>Deliberately minimal — one method, called exactly once per arena, at
 * construction. No free/resize/map: the non-sparse arena owns one fixed
 * range for its whole life (BufferArena.java:27, the Linux-fallback path
 * Meshelium ships first per study Q6/§3 port notes).</p>
 */
@FunctionalInterface
public interface ArenaBacking {

    /**
     * Allocate the arena's single backing range.
     *
     * @param sizeBytes the arena's fixed capacity in bytes
     * @return an opaque handle the arena stores and republishes via
     *         {@link TerrainArena#backingHandle()} (wave 3b: the buffer's
     *         device address or a table index — the arena never interprets it)
     */
    long allocate(long sizeBytes);
}
