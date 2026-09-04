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
public interface ArenaBacking {

    /**
     * Allocate the arena's FIRST backing range (block 0).
     *
     * @param sizeBytes block 0's initial capacity in bytes
     * @return an opaque handle the arena stores and republishes via
     *         {@link TerrainArena#backingHandle()} (wave 3b: the buffer's
     *         device address or a table index — the arena never interprets it)
     */
    long allocate(long sizeBytes);

    /**
     * Allocate an ADDITIONAL block, beyond block 0.
     *
     * <p>This is how the arena grows once block 0 has reached the largest
     * buffer this device can address. Appending is not merely an
     * alternative to grow-and-copy, it is dramatically cheaper: growing
     * copies every live byte to a new allocation and holds both at once,
     * so at multi-gigabyte sizes it is a visible hitch exactly when the
     * player is flying into new terrain, and it doubles peak VRAM at the
     * worst possible moment. Appending copies nothing and leaves every
     * existing address exactly where it was.</p>
     *
     * @return the new block's opaque handle, or 0 when the device refused
     *         the allocation (the caller treats that as growth exhausted)
     */
    default long appendBlock(long sizeBytes) {
        return 0L;
    }
}
