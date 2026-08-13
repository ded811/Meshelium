/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import net.minecraft.server.level.ChunkTaskPriorityQueue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Wave-10 hotfix accessor, added after the first rd-48 run crashed the
 * chunk workers with {@code ArrayIndexOutOfBoundsException: Index 50 out
 * of bounds for length 46} in {@code resortChunkTasks}.
 *
 * <p>The recon widened the two obvious server-side 32s (ChunkMap's
 * setServerViewDistance clamp, DistanceManager's PlayerTicketTracker
 * radius) but missed the third: this queue sizes a fixed priority ladder
 * from {@code PRIORITY_LEVEL_COUNT = ChunkLevel.MAX_LEVEL + 2} (= 46,
 * bytecode: clinit {@code getstatic MAX_LEVEL; iconst_2; iadd}) and
 * indexes it directly by ticket level — a tracker widened by +N produces
 * levels up to {@code 46 + N - 2} and walks off the end.
 *
 * <p>The field is {@code static final int} but INITIALISED IN CLINIT from
 * a computation, so javac cannot constant-fold it into the two readers
 * (this class + ChunkMap — census 2026-08-10), and a {@link Mutable}
 * accessor write at boot (before any server, same timeframe as the
 * Options-range widening) reaches both. <b>Wave 13: widened
 * UNCONDITIONALLY by +64</b> (the 96-chunk ceiling's worst case) — the
 * wave-10 boot-config gate left the ladder narrow when the ceiling was
 * raised mid-session and a world rejoined (the live-read tracker cap then
 * produced levels past 46 — the same AIOOBE, reachable in production).
 * Cost of unconditional: 64 empty per-rung maps per queue (ctor bytecode:
 * {@code IntStream.range(0, PRIORITY_LEVEL_COUNT)} → one
 * {@code Long2ObjectLinkedOpenHashMap} each); ChunkMap's other read is
 * {@code min(queueLevel, COUNT−1)} — a clamp into the ladder, coherent
 * under widening (both javap-cited in MesheliumExtendedRd).</p>
 */
@Mixin(ChunkTaskPriorityQueue.class)
public interface ChunkTaskPriorityQueueAccessor {

    @SuppressWarnings("unused")
    @Accessor("PRIORITY_LEVEL_COUNT")
    static int meshelium$priorityLevelCount() {
        throw new AssertionError("mixin accessor not applied");
    }

    @SuppressWarnings("unused")
    @Mutable
    @Accessor("PRIORITY_LEVEL_COUNT")
    static void meshelium$setPriorityLevelCount(int value) {
        throw new AssertionError("mixin accessor not applied");
    }
}
