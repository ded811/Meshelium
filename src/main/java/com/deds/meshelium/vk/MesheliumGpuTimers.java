/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.fabric.MesheliumClient;
import com.deds.meshelium.terrain.host.TerrainResidency;

import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanQueryPool;

import java.util.Arrays;
import java.util.OptionalLong;

/**
 * Wave-9 GPU timing — per-pass timestamps for Meshelium's terrain frame,
 * built ENTIRELY on vanilla's own query plumbing (no raw Vulkan calls in
 * this class):
 *
 * <ul>
 *   <li><b>Pool:</b> {@code GpuDevice.createTimestampQueryPool(int)} →
 *       {@code VulkanQueryPool} — bytecode: {@code vkCreateQueryPool} with
 *       {@code queryType(2)} = TIMESTAMP + a host {@code vkResetQueryPool}
 *       over the whole pool at creation (VK 1.2 hostQueryReset, which
 *       vanilla's device therefore has enabled).</li>
 *   <li><b>Write:</b> {@code CommandEncoder.writeTimestamp(pool, index)}
 *       (public facade → {@code VulkanCommandEncoder.writeTimestamp},
 *       bytecode: host {@code vkResetQueryPool(pool, index, 1)} then
 *       {@code KHRSynchronization2.vkCmdWriteTimestamp2KHR} on the shared
 *       command buffer with stage constant 0x10000 = ALL_COMMANDS) — the
 *       sync2 LWJGL name the deliverable asked to verify, bytecode-verified
 *       AND already used by vanilla's own {@code TracyGpuProfiler} with the
 *       same reset-per-write discipline. Meshelium writes only BETWEEN passes
 *       (never inside one), where the encoder's shared command buffer is
 *       legal to touch and every vanilla pass-end has already emitted its
 *       full ALL_COMMANDS barrier — so consecutive timestamps bracket
 *       whole passes.</li>
 *   <li><b>Read:</b> {@code GpuQueryPool.getValues(first, count)} —
 *       bytecode: {@code vkGetQueryPoolResults} with stride 16, flags 5 =
 *       64_BIT | WITH_AVAILABILITY, NO WAIT bit: never stalls, returns
 *       {@code OptionalLong.empty()} for queries still in flight. Ticks →
 *       nanoseconds via {@code DeviceInfo.timestampPeriod()} (public).</li>
 * </ul>
 *
 * <h2>Ring + staleness (the wave-6 readback pattern)</h2>
 * {@value #RING_FRAMES} frame slots × {@value #QUERIES_PER_FRAME} query
 * indices; frame N writes slot N&nbsp;%&nbsp;{@value #RING_FRAMES}, frame N
 * reads frame N&nbsp;−&nbsp;{@link TerrainResidency#FREE_FRAME_LAG} — the
 * same 2-submits-in-flight throttle argument as the occlusion stats ring
 * (frame-path Q1.2), with WITH_AVAILABILITY as the belt: an unready slot
 * reads as absent and is counted, never waited on. Reusing a slot 8 frames
 * later also keeps the per-write HOST reset legal (the reset may not touch
 * a query a pending command buffer still writes; 8&nbsp;&gt;&gt;&nbsp;2
 * submits in flight — vanilla's Tracy profiler makes the identical
 * assumption with its 1024-query rotation).
 *
 * <h2>Frame layout (query index = slot×8 + point)</h2>
 * <pre>
 * point 0  opaque begin        (after transient uploads, before pass 1)
 * point 1  after phase A       (bfs/cpu mode: after THE single opaque pass)
 * point 2  after region raster (occlusion mode only)
 * point 3  after section raster(occlusion mode only)
 * point 4  after phase B       (occlusion mode only)
 * point 5  translucent begin
 * point 6  after translucent
 * </pre>
 * Reported durations are DIFFERENCES of adjacent points, each valid only
 * when both ends were written this frame (a per-frame point mask travels
 * with the slot): phaseA=1−0, regionRaster=2−1, sectionRaster=3−2,
 * phaseB=4−3, translucent=6−5. In bfs/cpu mode "phaseA" is honestly the
 * whole single opaque pass and the other opaque slots read −1 (absent).
 *
 * <h2>Honesty rules</h2>
 * <ul>
 *   <li><b>Never sum CPU and GPU.</b> The drawer's CPU draw-path micros
 *       (recordPerf) measure command RECORDING on the CPU; these measure
 *       GPU EXECUTION between full barriers. They are reported on separate
 *       lines and never combined.</li>
 *   <li>With stage ALL_COMMANDS and vanilla's pass-end barriers, a pass
 *       duration includes any GPU idle bubble between the bracketing
 *       barriers — that is the honest cost of the pass AS SCHEDULED, not a
 *       shader-only figure.</li>
 *   <li>Anomalous deltas (negative, or &gt; {@value #SANE_NANOS} ns —
 *       timestampValidBits wrap, device weirdness) discard the frame's
 *       affected duration and count {@link #framesAnomalous}.</li>
 * </ul>
 *
 * <h2>Safety</h2>
 * Pixel-neutral by construction: timestamp writes touch no attachment, no
 * descriptor, no pipeline state, and are recorded outside every pass. Any
 * failure latches the timers OFF ({@link #failure()}), logged once — the
 * drawer never sees the throwable (except {@link GpuDeviceLossException},
 * which is rethrown so wave 8's device-loss handling stays authoritative).
 * Kill switch: {@code -Dmeshelium.gpuTimers=false} (re-read every frame).
 * The pool is a device-lifetime object, destroyed at device close via
 * {@link TerrainDrawer#destroyDeviceObjects} → {@link #destroyDeviceObjects()}
 * (direct {@code VulkanQueryPool.destroy()} — the deferred
 * {@code close()} path targets a destroy queue that has already drained by
 * then, bytecode-cited on the teardown mixin).
 */
public final class MesheliumGpuTimers {

    /** Kill switch, re-read every frame. Default ON (timers are pixel-neutral). */
    public static final String PROPERTY = "meshelium.gpuTimers";

    // Pass indices of the reported duration array.
    public static final int PASS_OPAQUE_A = 0;
    public static final int PASS_REGION_RASTER = 1;
    public static final int PASS_SECTION_RASTER = 2;
    public static final int PASS_PHASE_B = 3;
    public static final int PASS_TRANSLUCENT = 4;
    public static final int PASSES = 5;

    // Timestamp points (query index within a frame slot).
    static final int POINT_OPAQUE_BEGIN = 0;
    static final int POINT_AFTER_PHASE_A = 1;
    static final int POINT_AFTER_REGION_RASTER = 2;
    static final int POINT_AFTER_SECTION_RASTER = 3;
    static final int POINT_AFTER_PHASE_B = 4;
    static final int POINT_TRANSLUCENT_BEGIN = 5;
    static final int POINT_AFTER_TRANSLUCENT = 6;

    private static final int QUERIES_PER_FRAME = 8; // 7 used, 1 pad
    private static final int RING_FRAMES = 8;
    private static final int READ_LAG = TerrainResidency.FREE_FRAME_LAG;

    /** Delta sanity ceiling: 4 s in ns — anything above is a wrap/anomaly. */
    private static final long SANE_NANOS = 4_000_000_000L;

    // Render thread only.
    private static GpuQueryPool pool;
    private static double periodNs;
    private static boolean creationAttempted;
    private static long framesTimed;          // frames with point 0 written
    private static long armedSerial = -1;     // drawer frameSerial of the open frame
    private static final int[] ringMask = new int[RING_FRAMES];
    private static final long[] ringFrame = new long[RING_FRAMES];
    static {
        Arrays.fill(ringFrame, -1);
    }

    // Probes (volatile: gametest/bench threads read them).
    private static volatile String failure;
    private static volatile long framesReadCount;
    private static volatile long framesNotReady;
    private static volatile long framesAnomalous;
    /** Last read frame's per-pass nanos; −1 = pass absent that frame. */
    private static volatile long[] lastPassNanos = absentRow();

    // Bench capture (armed by MesheliumBenchmarkTest; render thread writes,
    // the gametest thread reads AFTER observing captureFilled — the
    // volatile write/read pair publishes the array contents).
    private static long[] captureRows = new long[0];
    private static volatile int captureFilled;
    private static volatile boolean capturing;

    // Debug-line pacing.
    private static long lastLogNanos;

    private MesheliumGpuTimers() {}

    // ------------------------------------------------------------------
    // Probes
    // ------------------------------------------------------------------

    /** Null = healthy (or never started); non-null = latched-off reason. */
    public static String failure() {
        return failure;
    }

    /** Frames whose lagged readback landed with at least phase A present. */
    public static long framesRead() {
        return framesReadCount;
    }

    /** Lagged slots that were not yet available (never waited on). */
    public static long framesNotReadyCount() {
        return framesNotReady;
    }

    /** Frames discarded for nonsense deltas (wrap/device anomaly). */
    public static long framesAnomalousCount() {
        return framesAnomalous;
    }

    /** True once the pool exists and no failure latched. */
    public static boolean live() {
        return pool != null && failure == null;
    }

    /**
     * Last read frame's per-pass GPU nanos, indexed by the {@code PASS_*}
     * constants; −1 = that pass did not run (bfs/cpu mode, no translucent
     * geometry, readback missing). GPU-only numbers — never sum with the
     * drawer's CPU draw-path micros.
     */
    public static long[] lastPassNanosSnapshot() {
        return lastPassNanos.clone();
    }

    /** Nanoseconds per timestamp tick reported by the device (0 = unknown). */
    public static double timestampPeriodNs() {
        return periodNs;
    }

    // ------------------------------------------------------------------
    // Bench capture
    // ------------------------------------------------------------------

    /**
     * Arm per-frame capture of the next {@code rows} read-back frames
     * (each row = {@link #PASSES} longs, −1 for absent passes). Client
     * thread only (the bench arms it via {@code runOnClient}).
     */
    public static void armCapture(int rows) {
        captureRows = new long[rows * PASSES];
        captureFilled = 0;
        capturing = true;
    }

    public static void disarmCapture() {
        capturing = false;
    }

    /** Rows captured so far (each row = {@link #PASSES} longs). */
    public static int captureFilled() {
        return captureFilled;
    }

    /** Copy of the filled capture rows, flat, row-major (rows × PASSES). */
    public static long[] captureSnapshot() {
        int rows = captureFilled;
        return Arrays.copyOf(captureRows, rows * PASSES);
    }

    // ------------------------------------------------------------------
    // Frame hooks (TerrainDrawer, render thread, between passes only)
    // ------------------------------------------------------------------

    /**
     * Start timing the opaque half of a drawer-owned frame: fold in the
     * lagged readback (frame − {@value TerrainResidency#FREE_FRAME_LAG}),
     * open the next ring slot and write point 0. Call AFTER the frame's
     * transient uploads, BEFORE the first pass opens. No-op (and disarms
     * the frame) when disabled, latched off, or the pool cannot exist.
     */
    public static void beginOpaque(CommandEncoder encoder, long frameSerial) {
        if (armedSerial >= 0) {
            // The previous armed frame never saw its endFrame (e.g. its
            // translucent hook was skipped) — close it by ADVANCING, never
            // by reusing the frame index: reusing would host-reset query
            // slots a still-pending submission writes (the ring-safety
            // argument in the class javadoc requires monotonic indices).
            armedSerial = -1;
            framesTimed++;
        }
        if (!enabled() || failure != null) {
            return;
        }
        try {
            if (pool == null && !createPool()) {
                return;
            }
            readLagged(framesTimed - READ_LAG);
            int slot = (int) (framesTimed % RING_FRAMES);
            ringMask[slot] = 0;
            ringFrame[slot] = framesTimed;
            armedSerial = frameSerial;
            write(encoder, slot, POINT_OPAQUE_BEGIN);
        } catch (GpuDeviceLossException t) {
            throw t; // wave-8 rule: device loss is the drawer's to latch
        } catch (Throwable t) {
            latch(t);
        }
    }

    /**
     * Write an after-pass point ({@code POINT_AFTER_*}) into the frame
     * opened by {@link #beginOpaque}. Call between passes only.
     */
    public static void mark(CommandEncoder encoder, int point) {
        if (armedSerial < 0 || failure != null) {
            return;
        }
        try {
            // Nothing to finalize eagerly — the point mask travels with
            // the slot; the lagged read decides what was present.
            write(encoder, (int) (framesTimed % RING_FRAMES), point);
        } catch (GpuDeviceLossException t) {
            throw t;
        } catch (Throwable t) {
            latch(t);
        }
    }

    /**
     * Write the translucent-begin point iff this frame is the one
     * {@link #beginOpaque} armed (the wave-7 opaque/translucent coupling
     * means a Meshelium translucent pass always follows a Meshelium opaque
     * pass in the SAME frame serial).
     */
    public static void beginTranslucent(CommandEncoder encoder, long frameSerial) {
        if (armedSerial != frameSerial || failure != null) {
            return;
        }
        try {
            write(encoder, (int) (framesTimed % RING_FRAMES), POINT_TRANSLUCENT_BEGIN);
        } catch (GpuDeviceLossException t) {
            throw t;
        } catch (Throwable t) {
            latch(t);
        }
    }

    /** Translucent-end twin of {@link #beginTranslucent}. */
    public static void endTranslucent(CommandEncoder encoder, long frameSerial) {
        if (armedSerial != frameSerial || failure != null) {
            return;
        }
        try {
            write(encoder, (int) (framesTimed % RING_FRAMES), POINT_AFTER_TRANSLUCENT);
        } catch (GpuDeviceLossException t) {
            throw t;
        } catch (Throwable t) {
            latch(t);
        }
    }

    /**
     * Close the frame's timing (advance the ring). Called once per armed
     * frame from the drawer AFTER its last possible Meshelium pass of the
     * frame (translucent) — i.e. at the NEXT {@link #beginOpaque} a frame
     * is implicitly closed; this explicit call exists so frames without a
     * translucent pass still advance. Idempotent per serial.
     */
    public static void endFrame(long frameSerial) {
        if (armedSerial != frameSerial) {
            return;
        }
        armedSerial = -1;
        framesTimed++;
    }

    /**
     * Wave-8 destroy sweep: direct {@code VulkanQueryPool.destroy()} —
     * only legal after vanilla's encoder destroy (queue idle, VkDevice
     * still valid), which is exactly when {@link TerrainDrawer#destroyDeviceObjects}
     * runs. {@code close()} is NOT used here: it queues on the deferred
     * destroy rotation, which has already drained at that point.
     */
    public static void destroyDeviceObjects() {
        if (pool instanceof VulkanQueryPool vulkanPool) {
            vulkanPool.destroy();
        }
        pool = null;
        creationAttempted = false;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static boolean enabled() {
        String p = System.getProperty(PROPERTY);
        return p == null || Boolean.parseBoolean(p);
    }

    private static boolean createPool() {
        if (creationAttempted) {
            return false;
        }
        creationAttempted = true;
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            creationAttempted = false; // facade not up yet; retry next frame
            return false;
        }
        float period = device.getDeviceInfo().timestampPeriod();
        if (!(period > 0f)) {
            failure = "device reports timestampPeriod=" + period + " (timestamps unusable)";
            MesheliumClient.LOGGER.info(
                    "Meshelium GPU timers unavailable: {} — per-pass GPU times stay absent "
                            + "(CPU draw-path micros are unaffected)", failure);
            return false;
        }
        pool = device.createTimestampQueryPool(RING_FRAMES * QUERIES_PER_FRAME);
        periodNs = period;
        MesheliumClient.LOGGER.info(
                "Meshelium GPU timers up: {}-query timestamp pool through vanilla's own plumbing "
                        + "(VulkanQueryPool + CommandEncoder.writeTimestamp = host vkResetQueryPool "
                        + "per write + vkCmdWriteTimestamp2KHR ALL_COMMANDS, sync2), "
                        + "timestampPeriod {} ns/tick, readback lag {} frames",
                RING_FRAMES * QUERIES_PER_FRAME, period, READ_LAG);
        return true;
    }

    private static void write(CommandEncoder encoder, int slot, int point) {
        encoder.writeTimestamp(pool, slot * QUERIES_PER_FRAME + point);
        ringMask[slot] |= 1 << point;
    }

    /** Fold frame {@code f}'s slot into the probes (and the bench rows). */
    private static void readLagged(long f) {
        if (f < 0) {
            return;
        }
        int slot = (int) (f % RING_FRAMES);
        if (ringFrame[slot] != f || (ringMask[slot] & (1 << POINT_OPAQUE_BEGIN)) == 0) {
            return; // slot was never written for this frame (drawer idle)
        }
        int mask = ringMask[slot];
        ringFrame[slot] = -1; // consume once
        OptionalLong[] values = pool.getValues(slot * QUERIES_PER_FRAME, QUERIES_PER_FRAME - 1);

        long[] row = absentRow();
        boolean anyAbsentReady = false;
        boolean anomalous = false;
        // duration i spans points pairs[i][0] → pairs[i][1].
        int[][] pairs = {
                {POINT_OPAQUE_BEGIN, POINT_AFTER_PHASE_A},
                {POINT_AFTER_PHASE_A, POINT_AFTER_REGION_RASTER},
                {POINT_AFTER_REGION_RASTER, POINT_AFTER_SECTION_RASTER},
                {POINT_AFTER_SECTION_RASTER, POINT_AFTER_PHASE_B},
                {POINT_TRANSLUCENT_BEGIN, POINT_AFTER_TRANSLUCENT}};
        for (int i = 0; i < PASSES; i++) {
            int a = pairs[i][0];
            int b = pairs[i][1];
            if ((mask & (1 << a)) == 0 || (mask & (1 << b)) == 0) {
                continue; // pass did not run this frame — stays −1, honest
            }
            if (values[a].isEmpty() || values[b].isEmpty()) {
                anyAbsentReady = true; // written but not yet available
                continue;
            }
            long ticks = values[b].getAsLong() - values[a].getAsLong();
            long nanos = (long) (ticks * periodNs);
            if (nanos < 0 || nanos > SANE_NANOS) {
                anomalous = true;
                continue;
            }
            row[i] = nanos;
        }
        if (anyAbsentReady) {
            framesNotReady++;
        }
        if (anomalous) {
            framesAnomalous++;
        }
        if (row[PASS_OPAQUE_A] >= 0) {
            framesReadCount++;
            lastPassNanos = row;
            if (capturing) {
                int filled = captureFilled;
                if ((filled + 1) * PASSES <= captureRows.length) {
                    System.arraycopy(row, 0, captureRows, filled * PASSES, PASSES);
                    captureFilled = filled + 1; // volatile publish AFTER the copy
                } else {
                    capturing = false;
                }
            }
            maybeLog(row);
        }
    }

    /**
     * The wave-9 debug line — GPU pass times, once per 5 s, INFO under
     * debugStats, DEBUG otherwise. Deliberately a SEPARATE line from the
     * drawer's CPU draw-path breadcrumb: GPU execution time and CPU
     * recording time must never be summed or interleaved into one figure.
     */
    private static void maybeLog(long[] row) {
        long now = System.nanoTime();
        if (lastLogNanos != 0 && now - lastLogNanos < 5_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        String line = String.format(
                "meshelium GPU pass times (frame-%d readback, us): opaqueA=%s regionRaster=%s "
                        + "sectionRaster=%s phaseB=%s translucent=%s "
                        + "(GPU timestamps between vanilla pass-end barriers; CPU draw-path "
                        + "micros are a separate line — never sum the two)",
                READ_LAG,
                us(row[PASS_OPAQUE_A]), us(row[PASS_REGION_RASTER]), us(row[PASS_SECTION_RASTER]),
                us(row[PASS_PHASE_B]), us(row[PASS_TRANSLUCENT]));
        if (MesheliumConfig.debugStatsEnabled()) {
            MesheliumClient.LOGGER.info(line);
        } else {
            MesheliumClient.LOGGER.debug(line);
        }
    }

    private static String us(long nanos) {
        return nanos < 0 ? "absent" : Long.toString(nanos / 1_000);
    }

    private static long[] absentRow() {
        long[] row = new long[PASSES];
        Arrays.fill(row, -1);
        return row;
    }

    private static void latch(Throwable t) {
        failure = t.toString();
        armedSerial = -1;
        MesheliumClient.LOGGER.error(
                "Meshelium GPU timers failed; per-pass GPU timing stays off for this session "
                        + "(terrain drawing is unaffected — first and only report)", t);
    }
}
