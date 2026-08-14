/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium;

/**
 * How much graphics memory is ACTUALLY free right now, as opposed to how
 * large the card is.
 *
 * <h2>What this is not for</h2>
 * <p>This is not a frugality mechanism, and it must never make Meshelium
 * use less memory on a machine that has memory to spare. Spending memory to
 * draw more terrain faster is the entire point of the mod; a player who
 * sets render distance 120 is explicitly asking for that trade, and an
 * owner-measured Radeon 780M laptop with 32 GiB ran high distances happily.
 * The static ceiling is a CAP, not a reservation: the arena grows on demand
 * and stops where terrain stops needing it.</p>
 *
 * <h2>What it is for</h2>
 * <p>It is for the case where demand genuinely exceeds what the machine
 * has, which is the one case the code handled catastrophically. Vanilla's
 * {@code VulkanUtils.crashIfFailure} turns every negative VkResult except
 * device-loss into a bare {@code IllegalStateException} - no out-of-memory
 * branch, no retry, no degradation - and vanilla still uploads its own full
 * copy of the terrain, because Meshelium's kill switch cancels its DRAWS
 * and not its UPLOADS. So if Meshelium takes the last of the card, the
 * thing that dies is usually vanilla, and the crash report names a vanilla
 * texture upload. This guard exists so that never happens.</p>
 *
 * <h2>Why a budget rather than a fraction</h2>
 * <p>A heap SIZE is a hardware fact. A heap BUDGET is what is available
 * after vanilla's textures, the compositor, the browser on the second
 * monitor, and whatever else is resident. Sizing from the former is
 * guessing; {@code VK_EXT_memory_budget} reports the latter. Where the
 * budget is readable it should be the bound, which is also the argument for
 * NOT tightening the static percentage: the real number is better than a
 * guess in both directions.</p>
 *
 * <p>Every value here is 0 when unknown, and unknown must be treated as
 * "no information" - never as "no headroom" (which would pin the render
 * distance at its floor forever) and never as "unlimited" (which is the
 * crash this exists to prevent). Callers fall back to the static ceiling,
 * which is exactly today's behaviour.</p>
 */
public final class MesheliumVramState {

    /**
     * Headroom kept clear for everyone else, on a discrete card.
     *
     * <p>Not a Meshelium budget: it is the room vanilla needs for the
     * textures, swapchain images and vertex buffers it will allocate after
     * Meshelium has finished growing. Meshelium going passive is a bad
     * afternoon; vanilla failing to allocate is a crash.</p>
     */
    public static final long RESERVE_DISCRETE_BYTES = 512L << 20;

    /**
     * The same on an integrated GPU, where the heap is shared system
     * memory. Larger because the competition is not just the rest of the
     * renderer, it is the JVM heap, the OS and every other process, and
     * because running out there means swapping rather than a clean refusal.
     */
    public static final long RESERVE_INTEGRATED_BYTES = 1024L << 20;

    private static volatile long budgetBytes;
    private static volatile long usageBytes;
    private static volatile long lastSampleNanos;

    private MesheliumVramState() {
    }

    /** Driver-reported budget for the heap the arena lives in; 0 = unknown. */
    public static long budgetBytes() {
        return budgetBytes;
    }

    /** Driver-reported usage of that heap; 0 = unknown. */
    public static long usageBytes() {
        return usageBytes;
    }

    /** True when a real budget has been sampled at least once. */
    public static boolean known() {
        return budgetBytes > 0;
    }

    /**
     * Bytes Meshelium may still take without eating the reserve, or
     * {@link Long#MAX_VALUE} when the budget is unknown - unknown means fall
     * back to the static ceiling and change nothing, NOT refuse everything.
     */
    public static long headroomBytes() {
        long budget = budgetBytes;
        if (budget <= 0) {
            return Long.MAX_VALUE;
        }
        long reserve = MesheliumVulkanState.integratedGpu()
                ? RESERVE_INTEGRATED_BYTES : RESERVE_DISCRETE_BYTES;
        return Math.max(0L, budget - usageBytes - reserve);
    }

    /**
     * Heap pressure as a percentage, on the same 0-100 scale the arena's own
     * pressure uses so both can feed one backoff. 0 when unknown, which
     * reads as "no pressure" and leaves the arena half in charge.
     */
    public static long pressurePct() {
        long budget = budgetBytes;
        if (budget <= 0) {
            return 0L;
        }
        return Math.min(100L, usageBytes * 100L / budget);
    }

    /**
     * Record a fresh sample. Called from the render thread on a timer, not
     * per frame: it is a driver round trip, and the quantity it measures
     * moves on the scale of seconds.
     */
    public static void sample(long budget, long usage, long nowNanos) {
        budgetBytes = budget;
        usageBytes = usage;
        lastSampleNanos = nowNanos;
        recent[recentIndex] = headroomBytes();
        recentIndex = (recentIndex + 1) % recent.length;
        if (recentFilled < recent.length) {
            recentFilled++;
        }
    }

    /**
     * Headroom that has been low for SEVERAL consecutive samples, taken as
     * the MAXIMUM of the recent window rather than the latest reading.
     *
     * <p>Deliberately optimistic, because this number can only ever shrink
     * what Meshelium is allowed to do. A single dip - someone opens a
     * browser, a shader cache warms, another game's leftovers have not been
     * reclaimed - must not cost the player render distance, and under the
     * no-restore rule they would not get it back automatically. Taking the
     * maximum means pressure has to be sustained across the whole window
     * before it counts, while a genuine shortage still gets through within
     * a few seconds.</p>
     */
    public static long sustainedHeadroomBytes() {
        if (budgetBytes <= 0 || recentFilled == 0) {
            return Long.MAX_VALUE;
        }
        long best = 0L;
        for (int i = 0; i < recentFilled; i++) {
            best = Math.max(best, recent[i]);
        }
        return best;
    }

    /** Rolling window of headroom samples; see sustainedHeadroomBytes. */
    private static final long[] recent = new long[3];
    private static int recentIndex;
    private static int recentFilled;

    public static long lastSampleNanos() {
        return lastSampleNanos;
    }

    /** Forget everything; a new device means new numbers. */
    public static void reset() {
        budgetBytes = 0L;
        usageBytes = 0L;
        lastSampleNanos = 0L;
        recentIndex = 0;
        recentFilled = 0;
        java.util.Arrays.fill(recent, 0L);
    }
}
