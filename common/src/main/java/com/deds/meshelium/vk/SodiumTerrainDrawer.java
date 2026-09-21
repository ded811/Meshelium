/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.vk;

import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumVulkanState;
import com.deds.meshelium.mixin.RenderPassAccessor;
import com.deds.meshelium.mixin.VulkanRenderPassAccessor;

import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;

import net.minecraft.client.Minecraft;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Stage 1 of the Sodium port: Meshelium's mesh shaders drawing SODIUM's
 * terrain.
 *
 * <h2>What this is, and why it is not the other thing</h2>
 * <p>The obvious way to put mesh shaders on top of Sodium is to tap
 * Sodium's chunk builder, decode its vertices, re-encode them into
 * Meshelium's format, and upload them into Meshelium's arena. That was the
 * plan until the recon found the shortcut: by the time Sodium calls its
 * {@code ChunkRenderer}, the finished vertices are already sitting in a
 * plain Blaze3D {@code GpuBuffer} that the renderer can reach through
 * public methods. On the Vulkan backend that buffer is a
 * {@code VulkanGpuBuffer} with a real {@code VkBuffer} behind it, which
 * Meshelium can bind as a storage buffer and read directly.</p>
 *
 * <p>So nothing is decoded, nothing is re-encoded, nothing is uploaded,
 * and there is no second copy of the world in video memory. Sodium builds
 * the chunks; Meshelium draws them where they lie. The format difference —
 * Sodium's 20-byte vertex against Meshelium's 16-byte one — is absorbed by
 * a different fetch function in the mesh shader, compiled in under
 * {@code MESHELIUM_SODIUM}; every line downstream of that fetch is the
 * same parity-proven code the standalone path runs.</p>
 *
 * <p>It also means the fallback is trivially safe. Meshelium touches none
 * of Sodium's state, so handing a frame back to Sodium's own renderer is
 * always correct — nothing has been consumed, moved or freed.</p>
 *
 * <h2>What v1 does not do</h2>
 * <ul>
 *   <li><b>Opaque only.</b> Translucent geometry needs back-to-front draw
 *       order and per-vertex alpha; Sodium's renderer keeps that pass.</li>
 *   <li><b>No per-facing culling.</b> Sodium narrows each section to the
 *       facings the camera can see and Meshelium draws the section's whole
 *       contiguous run instead. That is more geometry, not wrong geometry,
 *       and it keeps v1 to one draw per section. The facing runs are
 *       contiguous and in ordinal order for opaque passes, so tightening
 *       this later is a loop over the seven counts, not a redesign.</li>
 *   <li><b>No culling of its own.</b> Sodium has already frustum- and
 *       occlusion-culled the list it hands over; Meshelium's task stage is
 *       not compiled into this variant at all.</li>
 *   <li><b>One draw per section</b>, where Sodium's multidraw manages
 *       roughly one per region. This is the standalone renderer's cpuCull
 *       shape — the slow one, kept for A/B debugging — and it is the
 *       biggest thing between this and a fair comparison.</li>
 *   <li><b>No chunk fade-in.</b> Sodium fades a freshly built section in;
 *       the {@code u_SectionTimeInfo} argument that drives it is ignored
 *       here, so sections appear at once.</li>
 * </ul>
 *
 * <h2>Inputs</h2>
 * <p>Everything arrives as primitives and Blaze3D types. That is
 * deliberate: this class lives in {@code common}, which compiles against
 * vanilla only, and the Sodium-typed enumeration that produces these
 * arrays lives in the separate {@code sodium} source set. The seam is the
 * argument list.</p>
 *
 * <h2>Clean room</h2>
 * <p>Sodium is PolyForm Shield. Nothing here is copied from it or derived
 * from its source; the buffers arrive as arguments and the drawing code is
 * Meshelium's own, unchanged from the standalone path.</p>
 */
public final class SodiumTerrainDrawer {

    /** Ints of {@link #drawMeta} per draw: buffer index, base vertex, quads. */
    public static final int META_STRIDE = 3;

    /**
     * Diagnostic: split every section's draw into this many command
     * recordings, drawing exactly the same geometry.
     *
     * <h2>What it is for</h2>
     * <p>To find out whether the per-section draw call is worth removing,
     * BEFORE building the machinery that removes it.
     *
     * <p>The argument for batching was "2000 draws against Sodium's few
     * dozen". Recon then established that Sodium actually submits MORE
     * sub-draws than we do — one per contiguous run of visible facings,
     * one to four per section — and simply packs them into a single API
     * call per region, which it then caches across frames. So the gap is
     * host-side command recording, and its size is an open question rather
     * than an obvious one.
     *
     * <p>Rather than guess, measure the SLOPE. This knob multiplies the
     * recording work by k while holding the geometry, the workgroup count
     * and the GPU's job essentially fixed: each chunk still dispatches
     * ceil(quads_chunk / wgQuads) workgroups, and with k dividing evenly
     * the total is unchanged. If frame time is flat from k=1 to k=4, draw
     * recording is not what is costing us and batching would buy nothing.
     * If it scales, the per-draw cost is (T(4) - T(1)) / (3 x draws), and
     * removing ~all of them is worth about (T(4) - T(1)) / 3.
     *
     * <p>Five lines of code against a run table, a mapped storage ring, a
     * shader variant and a binary search. The project's standing rule is
     * to measure the premise before writing the big design — a 787-line
     * design document was once refuted in three minutes by the one-line
     * test it had itself flagged.
     *
     * <p>Never set outside the bench. It only ever makes things slower.
     */
    public static final String PROPERTY_K_SPLIT = "meshelium.sodium.ksplit";

    /**
     * {@code -Dmeshelium.sodium.solidNoDiscard=true} draws the SOLID pass
     * with a fragment stage whose alpha {@code discard} is compiled out.
     * OPT-IN, default off, after measurement (D-015).
     *
     * <p>The reasoning was sound: Sodium's SOLID pass declares that it
     * does not support fragment discard, its own SOLID program has none
     * and neither does vanilla's, and on SOLID geometry Meshelium's discard
     * can never fire (every quad has cutoff index 0) while its static
     * presence forbids early depth writes. The measurement disagreed. At
     * 1920x1080 the variant changed nothing; at 2560x1440 it made the pass
     * about 20% SLOWER and noisier, three interleaved pairs agreeing
     * (MEASUREMENTS.md 0i), on the dev RDNA4 card. Why is not known — the
     * plausible story is that the driver's late-Z path for a discarding
     * shader happens to be the better one for this workload — and an
     * unexplained loss is exactly what the uncertainty rule reserves the
     * default-off row for. The variant stays as a lever because NVIDIA and
     * Intel are not on this desk, and early-Z behaviour under discard is
     * where they differ most.
     */
    public static final String PROPERTY_SOLID_NO_DISCARD = "meshelium.sodium.solidNoDiscard";

    /**
     * {@code -Dmeshelium.sodium.frustum=off|count|cpu|gpu}: a per-run
     * frustum test on the list path (rung 1). OFF by default until it is
     * measured (the uncertainty rule): a lever, not a setting.
     *
     * <h2>Why it exists (2026-09-13)</h2>
     * <p>The rung-1 draw has no culling of its own: every run Sodium lists
     * is drawn, on the assumption that the list is frustum-tight. Turning
     * the camera at aerial rd64 measured 9,393 runs per pass against 5,568
     * at rest and a GPU pass of 0.675 ms against 0.370 (MEASUREMENTS.md
     * 0l), and the owner reports the same thing in play: the frame rate is
     * highest facing one way and dips while looking around. Whether that
     * is the direction or slack in the list while the view rotates is what
     * {@code count} measures; the two dropping modes price the fix on
     * each side of the bus.
     * <ul>
     *   <li>{@code count}: test every run on the CPU, count the failures,
     *       draw everything (the diagnostic; costs only the test).</li>
     *   <li>{@code cpu}: test on the CPU and leave the failing runs out of
     *       the pass arrays. The run cache is untouched (its key has no
     *       frustum in it), so a replayed region is tested per run per
     *       frame.</li>
     *   <li>{@code gpu}: the mesh stage tests its run's section box against
     *       the same six planes and emits nothing for a run outside them;
     *       the CPU submits every run as before. Compiled in
     *       ({@code MESHELIUM_SODIUM_MESH_FRUSTUM}), so per session like
     *       every pipeline knob.</li>
     * </ul>
     * <p>The box is the section's 16-block cube inflated by
     * {@link #FRUSTUM_SLACK} on every side; the planes are the render
     * matrices' Gribb-Hartmann rows in camera-relative space, the planes
     * the standalone task stage tests, with its p-vertex rule.
     */
    public static final String PROPERTY_FRUSTUM = "meshelium.sodium.frustum";

    /**
     * {@code -Dmeshelium.sodium.diag.emptyPasses=N}: measurement-only. An
     * owned frame with occlusion records N EMPTY render passes (begin,
     * end, no draw) between the section raster and phase B, inside the
     * frame's GPU timer bracket. The phase-B timer segment then grows by
     * N times the cost of a render pass that draws nothing, which is the
     * per-pass overhead (vanilla's pass-end full barrier drains the GPU)
     * that D-024's floor is suspected to be mostly made of. Never a
     * setting.
     */
    public static final int DIAG_EMPTY_PASSES = Integer.getInteger("meshelium.sodium.diag.emptyPasses", 0);

    public static final int FRUSTUM_OFF = 0;
    public static final int FRUSTUM_COUNT = 1;
    public static final int FRUSTUM_CPU = 2;
    public static final int FRUSTUM_GPU = 3;

    /** The lever's value for this session; see {@link #PROPERTY_FRUSTUM}. */
    public static final int FRUSTUM_MODE = parseFrustumMode(System.getProperty(PROPERTY_FRUSTUM, "off"));

    /**
     * {@code -Dmeshelium.sodium.diag.frustumCount=true}: run the CPU count
     * in every mode, so a {@code gpu} run can report what its mesh stage
     * rejected. Costs the CPU test; diagnostic only.
     */
    public static final boolean FRUSTUM_DIAG_COUNT =
            Boolean.getBoolean("meshelium.sodium.diag.frustumCount");

    /**
     * Blocks of slack on every side of a section's 16-block box before the
     * frustum test: Sodium's own pad (javap 0.9.2-beta.1: Viewport
     * .CHUNK_SECTION_MARGIN = 1.125, PADDED_RADIUS 9.125; the tree
     * traversal's leaf test is JOML testAab on centre +- 9.125). Equal to
     * Sodium's rather than the standalone's 0.5 so that nothing Sodium's
     * frustum-tight list carries is ever removed here.
     */
    public static final float FRUSTUM_SLACK = 1.125f;

    private static int parseFrustumMode(String value) {
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "count" -> FRUSTUM_COUNT;
            case "cpu" -> FRUSTUM_CPU;
            case "gpu" -> FRUSTUM_GPU;
            default -> FRUSTUM_OFF;
        };
    }

    /** True when the list builder must run the CPU test (count, cpu, or the diagnostic). */
    public static boolean frustumCpuTestWanted() {
        return FRUSTUM_MODE == FRUSTUM_COUNT || FRUSTUM_MODE == FRUSTUM_CPU || FRUSTUM_DIAG_COUNT;
    }

    /** The lever's value by name, for the bench report. */
    public static String frustumModeName() {
        return switch (FRUSTUM_MODE) {
            case FRUSTUM_COUNT -> "count";
            case FRUSTUM_CPU -> "cpu";
            case FRUSTUM_GPU -> "gpu";
            default -> "off";
        };
    }

    /**
     * {@code -Dmeshelium.sodium.reverseOrder=true} records the batched
     * draw in the REVERSE of Sodium's render-list order: far-to-near
     * instead of near-to-far, buffer groups and the runs inside them.
     * Diagnostic only, never a setting: it exists to price draw order
     * before building strict front-to-back across buffer groups (the
     * review's top remaining GPU lever). If the worst-case order does not
     * move the pass, the best-case order cannot either.
     */
    public static final String PROPERTY_REVERSE_ORDER = "meshelium.sodium.reverseOrder";

    private static final boolean REVERSE_ORDER = Boolean.getBoolean(PROPERTY_REVERSE_ORDER);

    private static final int K_SPLIT = Math.max(1,
            Integer.getInteger(PROPERTY_K_SPLIT, 1));

    /**
     * Batched dispatch: one draw per geometry buffer instead of one per
     * section, with the mesh shader finding its run in a table.
     *
     * <p>On by default because it was measured, not guessed. At one draw
     * per section, ~28% of the frame was Vulkan command recording
     * (~66 ns each, ~4,270 a frame, ~0.28 ms of a 0.99 ms frame) — see
     * {@code docs/unreleased/sodium/MEASUREMENTS.md} section 0.
     *
     * <p>{@code -Dmeshelium.sodium.batch=false} restores the per-section
     * path. Kept rather than deleted for two reasons: it is the A/B lever
     * for measuring this change in a single session, and it is the
     * fallback if the run table ever proves wrong on hardware that is not
     * this desk.
     */
    public static final String PROPERTY_BATCH = "meshelium.sodium.batch";

    /**
     * Resolved once, at pipeline creation, because the answer depends on
     * the device and the device does not exist at class-init time.
     *
     * <h2>Why it is conditional and not simply on</h2>
     * <p>Every one of these was measured on the dev card, forest-rd64,
     * against a same-session Sodium leg of 1.09-1.12 ms:</p>
     * <pre>
     *   per-section direct draws        0.994 ms
     *   batched, search, host memory    1.486 ms   (PCIe per read)
     *   batched, search, BAR memory     1.053 ms
     *   batched, gl_DrawID indirect     0.974 ms   &lt;- best
     *   batched, group-map lookup       1.010 ms
     * </pre>
     *
     * <p>So batching only pays when the run can be resolved in ONE read.
     * With gl_DrawID it can. Without it, the two remaining shapes — search
     * or group map — both land at or behind the per-section path, and the
     * right answer is not to batch at all.
     *
     * <p>The reason the win is 2% rather than the 28% the draw-call
     * measurement suggested is worth remembering: batching does not delete
     * that work, it moves it. The CPU stops recording 2,135 commands and
     * the GPU starts fetching 2,135 indirect dispatches. What is actually
     * saved is the difference, plus a much better p95 (1.152 against
     * 1.259) because the CPU is no longer the frame's long pole.
     */
    /**
     * The three ways this path can turn runs into dispatches.
     *
     * <p>Resolved ONCE and read by both the pipeline (which compiles the
     * matching shader variant) and the recorder (which writes the matching
     * buffers). A first version let the two decide independently — the
     * pipeline read the device feature, the recorder read a property —
     * and with {@code -Dmeshelium.sodium.groupmap=true} they disagreed:
     * a gl_DrawID shader ran under direct draws, {@code gl_DrawID} read as
     * zero for every workgroup, and the "group-map" benchmark quietly
     * measured the wrong geometry. Caught by review, not by the picture,
     * because nobody screenshotted that A/B lever. Every shape now gets
     * its own screenshot in the Sodium test.
     */
    enum Shape {
        /** One direct draw per run. The fallback and the A/B floor. */
        PER_SECTION,
        /** One indirect multi-draw per buffer; gl_DrawID names the run. */
        DRAW_ID,
        /** One direct draw per buffer; a per-workgroup map names the run. */
        GROUP_MAP
    }

    /**
     * Opt into the group-map resolution instead of gl_DrawID.
     *
     * <p>Not the default, and the reason is worth keeping: an earlier
     * measurement that showed it winning (0.705 ms against 0.811) was of
     * the BROKEN mapping described on {@link Shape}, drawing a fraction of
     * the terrain at the wrong origins. It has to be re-measured on the
     * fixed path, with a screenshot, before it can be trusted to be
     * anything.
     */
    public static final String PROPERTY_GROUP_MAP = "meshelium.sodium.groupmap";

    private static Shape shapeResolved;

    static Shape shape() {
        Shape sh = shapeResolved;
        if (sh == null) {
            if ("false".equalsIgnoreCase(System.getProperty(PROPERTY_BATCH))) {
                sh = Shape.PER_SECTION;
            } else if (Boolean.getBoolean(PROPERTY_GROUP_MAP)) {
                sh = Shape.GROUP_MAP;
            } else if (MesheliumVulkanState.drawIndirectSupported()) {
                sh = Shape.DRAW_ID;
            } else {
                // Every device vanilla can create has multiDrawIndirect and
                // shaderDrawParameters, so this arm exists for symmetry
                // with a future backend rather than for any card on the
                // list. The group map needs nothing, so it is the fallback.
                sh = Shape.GROUP_MAP;
            }
            shapeResolved = sh;
        }
        return sh;
    }

    private static boolean batchedEnabled() {
        return shape() != Shape.PER_SECTION;
    }

    /**
     * Workgroups one draw may dispatch.
     *
     * <p>The Vulkan spec floor for {@code maxMeshWorkGroupCount[0]} is
     * 65535 and this codebase probes no workgroup-COUNT limit anywhere, so
     * the floor is what we honour. A batched draw at render distance 64
     * can genuinely approach it — roughly total quads / workgroup quads —
     * and exceeding it is undefined behaviour rather than an error return,
     * which is the failure mode this project has already been bitten by
     * once with an unenabled shader feature that AMD happened to tolerate.
     *
     * <p>Groups beyond the cap are split into further draws carrying a
     * {@code GroupBase} offset, so the cost of the cap is a handful of
     * extra commands rather than missing geometry.
     */
    private static final int MAX_GROUPS_PER_DRAW = 65535;

    private static TerrainDrawPipeline pipeline;

    /**
     * The same pipeline with the fragment {@code discard} compiled out, for
     * passes whose materials never need it. Two slots keyed on that one
     * bit, both created lazily and both destroyed with the device; see
     * {@link #PROPERTY_SOLID_NO_DISCARD} for why the bit exists.
     */
    private static TerrainDrawPipeline pipelineNoDiscard;

    private static MesheliumSodiumRunTable runTable;

    /** Once-only: the run table filled up and geometry was dropped. */
    private static boolean runTableOverflowReported;

    /** Once-only: the group map filled up and dispatches were clipped. */
    private static boolean groupMapOverflowReported;

    /**
     * Latched on the first failure. A renderer that throws every frame is
     * worse than one that never ran: Sodium's own renderer is still there,
     * still correct, and handing the pass back to it costs one virtual
     * call. The error is reported once.
     */
    private static volatile boolean broken;

    private static volatile String error;

    private static volatile long framesDrawn;

    private static volatile int lastSectionsDrawn;

    private static volatile int lastRegionsDrawn;

    private static volatile long totalSectionsDrawn;

    /**
     * The bench's live A/B switch.
     *
     * <p>Flipping this off makes {@link #drawOpaque} decline, which sends
     * the pass to Sodium's own renderer — so one session can measure
     * Meshelium-on-Sodium and Sodium-alone against the same world, the
     * same camera and the same driver state, seconds apart.
     *
     * <p>That matters more than it sounds. This project's harness repeats
     * to 0.1% inside a session and has drifted 62% BETWEEN sessions, so a
     * cross-session comparison of two renderers is barely a comparison at
     * all. Before this switch existed the only way to measure Sodium alone
     * was a separate run, and the two numbers were not the same
     * experiment.
     *
     * <p>Not a setting and not a shipped feature: nothing but the
     * benchmark writes it. The player-facing switch is
     * {@code -Dmeshelium.sodium.adapter=false}, which stops the mixin
     * applying at all.
     */
    private static volatile boolean enabled = true;

    /**
     * Draw commands recorded in the most recent pass.
     *
     * <p>Reported separately from the section count because with
     * {@link #PROPERTY_K_SPLIT} they differ, and the whole point of that
     * experiment is the ratio between this number and the frame time.
     */
    private static volatile int lastDrawCommands;

    /**
     * Run-table entries written in the most recent batched pass.
     *
     * <p>Reported next to the command count because together they are the
     * whole claim: the same geometry, described by the same number of
     * runs, recorded in far fewer commands. One without the other could be
     * a draw loop that quietly stopped drawing.
     */
    private static volatile int lastRunsWritten;

    /**
     * Nanoseconds the most recent pass spent BUILDING the run table
     * (grouping, and the writes into mapped memory), and separately
     * RECORDING commands (descriptor pushes, push constants, draws).
     *
     * <h2>Why these exist</h2>
     * <p>Batching cut draw commands from 2,135 to 3 and made the frame
     * SLOWER — 0.99 ms to 1.50 ms. Something the change added costs more
     * than the draws it removed, and the frame-time number alone cannot
     * say which half.
     *
     * <p>The Sodium draw path had no instrumentation at all, so the
     * project's own bench could see the regression and not its cause. Two
     * {@code nanoTime} calls a pass is nothing next to being able to
     * answer the question. They also stay useful afterwards: a future
     * change that quietly moves cost from one half to the other shows up
     * here and nowhere else.
     */
    private static volatile long lastQuadsKept;

    private static volatile long lastQuadsCulled;

    private static volatile long lastTableNanos;

    private static volatile long lastRecordNanos;

    /**
     * Nanos the last pass spent enumerating Sodium's render lists into the
     * draw list, BEFORE {@link #drawOpaque}. Review (2026-09-05) pointed
     * out that the two timers above cover only the ring write and the
     * command recording, so the one CPU cost a per-region cache could
     * remove was the one nobody measured. It could plausibly be 0.1-0.4
     * ms a frame at rd64; this number settles it.
     */
    private static volatile long lastBuildNanos;

    /**
     * The last 256 enumeration timings, because a single pass's sample
     * swung 75-137 us between identical runs (GC, JIT, a cache miss on
     * Sodium's records) and a per-region cache is not worth building on
     * one sample. The bench reads the mean of the window.
     */
    private static final long[] buildNanosRing = new long[256];

    private static long buildNanosCount; // long: an int would go negative after 2^31 passes and empty the mean

    /** Passes recorded with the no-discard fragment variant this session. */
    private static volatile long noDiscardPasses;

    /** Passes recorded with the discarding fragment variant this session. */
    private static volatile long discardPasses;

    private static boolean ringGuardReported;

    /**
     * {@code -Dmeshelium.sodium.runCache=false} restores the per-section
     * enumeration walk. Any other value, including unset, leaves D-021's
     * per-region run cache on.
     *
     * <p>The walk is the parity control: the cache must produce the same
     * run table, and the only way to prove that at a pose is to run both.
     * Stage 0 (MEASUREMENTS.md 0k) measured the walk at 464 us per pass
     * at aerial rd96 — half the frame — which is what the cache removes.
     */
    public static final String PROPERTY_RUN_CACHE = "meshelium.sodium.runCache";

    private static volatile boolean runCacheEnabled =
            !"false".equalsIgnoreCase(System.getProperty(PROPERTY_RUN_CACHE));

    /**
     * Whether the most recent build actually used the cache: the lever
     * on, all five hooks found by the mixin plugin, and nothing having
     * stood it down. Reported by the draw list; the lever alone cannot
     * say, because the draw list can decline the cache on its own.
     */
    private static volatile boolean runCacheArmed;

    private static volatile int lastRegionsHit;

    private static volatile int lastRegionsRebuilt;

    private static volatile long regionsHitTotal;

    private static volatile long regionsRebuiltTotal;

    /**
     * The five record-mutation hooks (D-021), counted.
     *
     * <p>Each is an {@code @Inject} with {@code require = 0} in the sodium
     * tree, so a Sodium update that moves a target produces a slower frame
     * rather than a crash — and, without these, nothing else. A safety rule
     * with zero callers reads exactly like one that works (memory: verify
     * the guarantee has callers), so the stand-down suite drives each
     * transition and asserts its counter moved, and the bench reports all
     * five so a run can be read for whether invalidation happened at all.
     */
    private static volatile long hookUpload;

    private static volatile long hookRemoveSection;

    private static volatile long hookBufferChange;

    private static volatile long hookSegmentChange;

    private static volatile long hookDelete;

    private SodiumTerrainDrawer() {
    }

    /** True once a failure has taken this path out of service for the session. */
    public static boolean broken() {
        return broken;
    }

    /** The first failure's message, or null. */
    public static String error() {
        return error;
    }

    /** Opaque passes Meshelium has drawn this session. */
    public static long framesDrawn() {
        return framesDrawn;
    }

    /**
     * Draws (contiguous runs) recorded in the most recent non-empty pass on
     * rung 1, listed sections on an owned frame. Per PASS, for the bench;
     * the options screen reads {@link #lastSectionsDrawnFrame()}.
     */
    public static int lastSectionsDrawn() {
        return lastSectionsDrawn;
    }

    /**
     * Sections Meshelium drew in the most recent FRAME, the unit the options
     * screen and {@code TerrainDrawer.lastDrawnSections()} use.
     *
     * <p>Owned frames (rung 0a/0b) count the listed masks handed to the GPU,
     * which is the GPU's input set before its own cull; every other rung is
     * the list path's SOLID + CUTOUT sum, which the adapter reports before
     * each drawOpaque ({@code MesheliumChunkRenderer.rung1}), so an empty
     * CUTOUT pass reads 0 there rather than a stale value. Keyed on the
     * owned rungs, not on "1": rung reads "2" after any opaque pass handed
     * back whole, and an empty CUTOUT pass does that on every frame.
     *
     * <p>Exists because the status line printed {@link #lastSectionsDrawn()},
     * a per-pass figure that flickers between the SOLID and CUTOUT values
     * and counts runs rather than sections (owner report 2026-09-08
     * (beta.8)). {@code lastSectionsDrawn()} stays per pass for the bench.
     */
    public static int lastSectionsDrawnFrame() {
        String r = rung;
        return r != null && r.startsWith("0") ? lastSectionsDrawn : sectionsEmittedFrame();
    }

    /**
     * Sections drawn this session.
     *
     * <p>The one counter a test should assert on. A per-pass figure can be
     * zeroed by a legitimately empty pass arriving after a busy one — a
     * superflat world has solid geometry and no cutout geometry, and the
     * cutout pass runs second — which reads exactly like a broken
     * enumeration.</p>
     */
    public static long totalSectionsDrawn() {
        return totalSectionsDrawn;
    }

    /** Regions visited in the most recent pass (observability, tests). */
    public static int lastRegionsDrawn() {
        return lastRegionsDrawn;
    }

    /** Vulkan draw commands recorded in the most recent pass. */
    public static int lastDrawCommands() {
        return lastDrawCommands;
    }

    /** Run-table entries written in the most recent batched pass. */
    public static int lastRunsWritten() {
        return lastRunsWritten;
    }

    /** Quads drawn in the most recent pass, after facing culling. */
    public static long lastQuadsKept() {
        return lastQuadsKept;
    }

    /** Quads the facing test removed in the most recent pass. */
    public static long lastQuadsCulled() {
        return lastQuadsCulled;
    }

    private static volatile int lastRunsPushed;
    private static volatile int lastRunsFrustumRejected;
    private static volatile long lastQuadsFrustumRejected;
    private static volatile long runsPushedTotal;
    private static volatile long runsFrustumRejectedTotal;

    /**
     * Reported by the draw-list builder after each build: runs it pushed
     * (before any dropping), runs the CPU frustum test found outside the
     * frustum (0 unless the test ran; see {@link #PROPERTY_FRUSTUM}), and
     * those runs' quads. Also feeds the bench's per-frame run series.
     */
    public static void reportFrustum(int pushed, int rejected, long quadsRejected) {
        lastRunsPushed = pushed;
        lastRunsFrustumRejected = rejected;
        lastQuadsFrustumRejected = quadsRejected;
        runsPushedTotal += pushed;
        runsFrustumRejectedTotal += rejected;
        com.deds.meshelium.MesheliumBenchRecorder.addRuns(pushed, rejected);
    }

    public static int lastRunsPushed() {
        return lastRunsPushed;
    }

    public static int lastRunsFrustumRejected() {
        return lastRunsFrustumRejected;
    }

    public static long lastQuadsFrustumRejected() {
        return lastQuadsFrustumRejected;
    }

    public static long runsPushedTotal() {
        return runsPushedTotal;
    }

    public static long runsFrustumRejectedTotal() {
        return runsFrustumRejectedTotal;
    }

    /** Reported by the draw-list builder, which is the only thing that knows. */
    public static void reportQuads(long kept, long culled, long buildNanos) {
        lastQuadsKept = kept;
        lastQuadsCulled = culled;
        lastBuildNanos = buildNanos;
        buildNanosRing[(int) (buildNanosCount++ & 255L)] = buildNanos;
    }

    /** Mean enumeration nanos over the last 256 passes (0 before the first). */
    public static double meanBuildNanos() {
        int n = (int) Math.min(buildNanosCount, (long) buildNanosRing.length);
        if (n == 0) {
            return 0.0;
        }
        long sum = 0L;
        for (int i = 0; i < n; i++) {
            sum += buildNanosRing[i];
        }
        return (double) sum / n;
    }

    /**
     * Take this path out of service because enumerating Sodium's lists
     * threw. That enumeration runs before {@link #drawOpaque} and outside
     * its fallback, and it is the likeliest thing to break when Sodium's
     * internals move — so it needs the same latch, or a Sodium update
     * turns into a crash instead of a slower frame.
     */
    public static void failEnumeration(Throwable t) {
        broken = true;
        error = t.toString();
        MesheliumLog.LOGGER.error(
                "Meshelium could not read Sodium's render lists; Sodium's own renderer "
                        + "keeps the opaque passes for the rest of this session (first and "
                        + "only report). Nothing of Sodium's was consumed or freed.", t);
    }

    /** Nanos the last pass spent grouping and writing the run table. */
    public static long lastTableNanos() {
        return lastTableNanos;
    }

    /** Nanos the last pass spent recording Vulkan commands. */
    public static long lastRecordNanos() {
        return lastRecordNanos;
    }

    /** Nanos the last pass spent enumerating Sodium's lists (before the draw). */
    public static long lastBuildNanos() {
        return lastBuildNanos;
    }

    /** Passes recorded without a fragment discard this session. */
    public static long noDiscardPasses() {
        return noDiscardPasses;
    }

    /** Passes recorded with the fragment discard this session. */
    public static long discardPasses() {
        return discardPasses;
    }

    /**
     * Vanilla's command encoder has just returned from {@code submit()}.
     * Resets the run table's per-submit advance counter; the table itself
     * is package-private, so the mixin reaches it through here.
     */
    public static void onEncoderSubmit() {
        MesheliumSodiumRunTable.onSubmit();
        SodiumFrameRing.onSubmit();
    }

    /** The k-split diagnostic multiplier in force this session (1 = off). */
    public static int kSplit() {
        return K_SPLIT;
    }

    /** True when one command covers a whole geometry buffer's runs. */
    public static boolean batched() {
        return shapeResolved != null && shapeResolved != Shape.PER_SECTION;
    }

    /** The resolved dispatch shape's name, for the bench report. */
    public static String shapeName() {
        return shapeResolved == null ? "unresolved" : shapeResolved.name();
    }

    /** False while the bench is measuring its Sodium-alone leg. */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * Benchmark only. Off sends every pass to Sodium's renderer; on
     * restores Meshelium's draw from the next pass.
     *
     * <p>Safe to flip mid-frame for the same reason the whole fallback is
     * safe: Meshelium owns nothing of Sodium's, so a pass that changes
     * hands between frames leaves no state behind on either side.
     */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** False when the walk is forced by {@link #PROPERTY_RUN_CACHE} or the suite. */
    public static boolean runCacheEnabled() {
        return runCacheEnabled;
    }

    /**
     * Test only. Off forces the enumeration walk from the next pass; on
     * restores the cache. Safe mid-session: the cache holds derived
     * numbers keyed on state it re-checks every build, so a pass that
     * changes hands leaves nothing stale on either side.
     */
    public static void setRunCacheEnabled(boolean value) {
        runCacheEnabled = value;
    }

    /** Reported by the draw-list builder after each build. */
    public static void reportRunCache(boolean armed, int regionsHit, int regionsRebuilt) {
        runCacheArmed = armed;
        lastRegionsHit = regionsHit;
        lastRegionsRebuilt = regionsRebuilt;
        regionsHitTotal += regionsHit;
        regionsRebuiltTotal += regionsRebuilt;
    }

    /** True when the most recent build used the per-region run cache. */
    public static boolean runCacheArmed() {
        return runCacheArmed;
    }

    /** Regions replayed from their cache in the most recent build. */
    public static int lastRegionsHit() {
        return lastRegionsHit;
    }

    /** Regions walked and re-cached in the most recent build. */
    public static int lastRegionsRebuilt() {
        return lastRegionsRebuilt;
    }

    public static long regionsHitTotal() {
        return regionsHitTotal;
    }

    public static long regionsRebuiltTotal() {
        return regionsRebuiltTotal;
    }

    /** H1: {@code RenderRegionManager.uploadResults(RenderRegion, ...)} entered. */
    public static void onHookUpload() {
        hookUpload++;
    }

    /** H2: {@code RenderRegion.removeSection} entered. */
    public static void onHookRemoveSection() {
        hookRemoveSection++;
    }

    /** H3: {@code RenderRegion.onGeometryBufferChange} entered. */
    public static void onHookBufferChange() {
        hookBufferChange++;
    }

    /** H4: {@code RenderRegion.onGeometrySegmentChange} entered. */
    public static void onHookSegmentChange() {
        hookSegmentChange++;
    }

    /** H5: {@code RenderRegion.delete} entered. */
    public static void onHookDelete() {
        hookDelete++;
    }

    public static long hookUpload() {
        return hookUpload;
    }

    public static long hookRemoveSection() {
        return hookRemoveSection;
    }

    public static long hookBufferChange() {
        return hookBufferChange;
    }

    public static long hookSegmentChange() {
        return hookSegmentChange;
    }

    public static long hookDelete() {
        return hookDelete;
    }

    /**
     * Draw one opaque terrain pass from Sodium's geometry.
     *
     * @param colorView      the pass's colour attachment (Sodium's own
     *                       {@code TerrainRenderPass.getTarget()})
     * @param depthView      the pass's depth attachment
     * @param atlasView      the block atlas
     * @param atlasSampler   the sampler Sodium was going to use for it
     * @param lightmapView   vanilla's lightmap texture
     * @param modelView      camera-relative view rotation (no translation)
     * @param projection     the projection matrix for this pass
     * @param geometryBuffers distinct region geometry buffers, indexed by
     *                       each draw's first meta int
     * @param bufferCount    live prefix of {@code geometryBuffers}
     * @param drawCount      draws in {@code drawMeta} / {@code drawOrigins}
     * @param drawMeta       {@link #META_STRIDE} ints per draw: buffer
     *                       index, base VERTEX index, quad count
     * @param drawOrigins    3 floats per draw: the section's minimum corner
     *                       relative to the camera
     * @return true when Meshelium recorded the pass; false means the caller
     *         must let Sodium's renderer draw it
     */
    public static boolean drawOpaque(GpuTextureView colorView, GpuTextureView depthView,
            GpuTextureView atlasView, GpuSampler atlasSampler, GpuTextureView lightmapView,
            Matrix4fc modelView, Matrix4fc projection,
            GpuBuffer[] geometryBuffers, int bufferCount,
            int drawCount, int[] drawMeta, float[] drawOrigins, int regionCount,
            boolean noDiscard) {
        if (broken || !enabled) {
            return false;
        }
        if (colorView == null || depthView == null || atlasView == null || lightmapView == null
                || atlasSampler == null || modelView == null || projection == null) {
            return false;
        }
        if (drawCount == 0) {
            // Hand an empty pass BACK to Sodium rather than owning it.
            //
            // Owning it would be correct whenever the emptiness is real —
            // Sodium would draw nothing either. The reason not to is the
            // case where it is not real: if this enumeration ever breaks
            // and finds nothing while Sodium's lists are full, owning the
            // pass paints an empty world, and an empty world is the exact
            // bug this whole effort started from. Declining instead turns
            // a total enumeration failure into a frame that merely fails
            // to be accelerated.
            //
            // The cost is one redundant pass on genuinely empty passes,
            // which iterate the same empty lists Meshelium just did.
            return false;
        }
        if (shape() != Shape.PER_SECTION && MesheliumSodiumRunTable.ringGuardTripped()) {
            // The run table's ring is safe for a bounded number of passes
            // per queue submit. Past that bound the next pass would rewrite
            // a slot the GPU may still be reading, so it goes to Sodium
            // instead. Review found vanilla itself has such a path (see
            // MesheliumSodiumRunTable.onSubmit); this is the fallback the
            // pass would have taken for any other failure.
            if (!ringGuardReported) {
                ringGuardReported = true;
                MesheliumLog.LOGGER.warn(
                        "Meshelium: more than {} terrain passes were recorded between two queue "
                                + "submits, which is more than the run table's ring can hold "
                                + "safely; the extra passes are drawn by Sodium until the next "
                                + "submit (reported once).",
                        MesheliumSodiumRunTable.safeAdvancesPerSubmit());
            }
            return false;
        }
        try {
            record(colorView, depthView, atlasView, atlasSampler, lightmapView,
                    modelView, projection, geometryBuffers, bufferCount,
                    drawCount, drawMeta, drawOrigins, noDiscard);
            lastSectionsDrawn = drawCount;
            lastRegionsDrawn = regionCount;
            totalSectionsDrawn += drawCount;
            framesDrawn++;
            gpuTimerUnit = "pass"; // rung 1 times a PASS; rung 0 times a FRAME
            return true;
        } catch (GpuDeviceLossException t) {
            // A lost device is nobody's bug to recover from here; let it
            // reach vanilla's own handling rather than swallowing it into
            // a silent fallback that would then fail identically.
            throw t;
        } catch (Throwable t) {
            broken = true;
            error = t.toString();
            MesheliumLog.LOGGER.error(
                    "Meshelium's mesh-shader draw of Sodium's terrain failed; Sodium's own "
                            + "renderer takes the opaque passes back for the rest of this session "
                            + "(first and only report). Nothing of Sodium's was consumed or freed, "
                            + "so the fallback is complete rather than partial.", t);
            return false;
        }
    }

    private static void record(GpuTextureView colorView, GpuTextureView depthView,
            GpuTextureView atlasView, GpuSampler atlasSampler, GpuTextureView lightmapView,
            Matrix4fc modelView, Matrix4fc projection,
            GpuBuffer[] geometryBuffers, int bufferCount,
            int drawCount, int[] drawMeta, float[] drawOrigins, boolean noDiscard) {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        // Transient uploads before the pass opens: the allocator may record
        // transfer commands of its own, and only one pass may be open.
        GpuBufferSlice sceneSlice = uploadScene(encoder, modelView, projection,
                atlasView.getWidth(0), atlasView.getHeight(0));
        GpuBufferSlice projectionSlice = uploadProjection(encoder, projection);

        // GPU timestamps bracket the pass. One timer "frame" per RECORDED
        // pass, because this path has no frame serial of its own and two
        // opaque passes land per real frame — so the bench's per-row GPU
        // figure is per PASS, and a per-frame number is roughly two rows.
        // Written outside the render pass on the encoder's shared command
        // buffer, which is the only place the timer is legal to touch.
        //
        // This existed nowhere on the Sodium path before, and its absence
        // is why a 0.5 ms regression once had to be diagnosed by
        // elimination: the CPU counters said "not us" and nothing said
        // where it was. Whether a frame is CPU- or GPU-bound is the first
        // question every optimisation here has to answer, and this is the
        // instrument that answers it.
        long timerSerial = ++timerPasses;
        MesheliumGpuTimers.beginOpaque(encoder, timerSerial);

        try (RenderPass pass = encoder.createRenderPass(() -> "meshelium sodium terrain opaque",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            VulkanRenderPass backendPass = (VulkanRenderPass) ((RenderPassAccessor) pass).meshelium$backend();
            VulkanRenderPassAccessor vkPass = (VulkanRenderPassAccessor) backendPass;
            VkCommandBuffer cb = vkPass.meshelium$commandBuffer();

            TerrainDrawPipeline p = pipelineFor(vkPass, colorView, depthView, noDiscard);
            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipeline());
            if (noDiscard) {
                noDiscardPasses++;
            } else {
                discardPasses++;
            }

            if (batchedEnabled() && runTable != null) {
                recordBatched(cb, p, sceneSlice, projectionSlice, atlasView, atlasSampler,
                        lightmapView, geometryBuffers, bufferCount, drawCount, drawMeta,
                        drawOrigins);
            } else {
                recordPerSection(cb, p, sceneSlice, projectionSlice, atlasView, atlasSampler,
                        lightmapView, geometryBuffers, bufferCount, drawCount, drawMeta,
                        drawOrigins);
            }
        }
        MesheliumGpuTimers.mark(encoder, MesheliumGpuTimers.POINT_AFTER_PHASE_A);
        MesheliumGpuTimers.endFrame(timerSerial);
    }

    /** Serial handed to the GPU timers: one per recorded pass. */
    private static long timerPasses;

    /** The original one-draw-per-section recorder; the A/B lever and the fallback. */
    private static void recordPerSection(VkCommandBuffer cb, TerrainDrawPipeline p,
            GpuBufferSlice sceneSlice, GpuBufferSlice projectionSlice, GpuTextureView atlasView,
            GpuSampler atlasSampler, GpuTextureView lightmapView,
            GpuBuffer[] geometryBuffers, int bufferCount,
            int drawCount, int[] drawMeta, float[] drawOrigins) {
        long recordStart = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer push = stack.calloc(TerrainDrawPipeline.PUSH_BYTES);
                int wg = p.workgroupQuads();
                // Descriptors are re-pushed only when the geometry buffer
                // changes. The draw list is built region by region and
                // regions commonly share a backing arena, so in practice
                // this is a handful of pushes per pass rather than one per
                // section.
                int boundBuffer = -1;
                int commands = 0;
                for (int d = 0; d < drawCount; d++) {
                    int bufferIndex = drawMeta[d * META_STRIDE];
                    if (bufferIndex < 0 || bufferIndex >= bufferCount) {
                        continue; // defensive; the builder never emits this
                    }
                    if (bufferIndex != boundBuffer) {
                        pushDescriptors(cb, p, geometryBuffers[bufferIndex], sceneSlice,
                                projectionSlice, atlasView, atlasSampler, lightmapView);
                        boundBuffer = bufferIndex;
                    }
                    push.putFloat(0, drawOrigins[d * 3]);
                    push.putFloat(4, drawOrigins[d * 3 + 1]);
                    push.putFloat(8, drawOrigins[d * 3 + 2]);
                    int firstVertex = drawMeta[d * META_STRIDE + 1];
                    int quads = drawMeta[d * META_STRIDE + 2];
                    // Chunked so the same geometry can be recorded as k
                    // commands instead of one. At k=1 this is one iteration
                    // writing exactly the values the single-draw form wrote,
                    // so the measured path is unchanged when the knob is off.
                    int perChunk = K_SPLIT == 1 ? quads : (quads + K_SPLIT - 1) / K_SPLIT;
                    for (int first = 0; first < quads; first += perChunk) {
                        int n = Math.min(perChunk, quads - first);
                        // A vertex index, so the quad offset is scaled by 4.
                        push.putInt(12, firstVertex + first * 4);   // FirstVertex
                        push.putInt(16, n);                          // QuadCount
                        VK10.vkCmdPushConstants(cb, p.pipelineLayout(),
                                EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT, 0, push);
                        EXTMeshShader.vkCmdDrawMeshTasksEXT(cb, (n + wg - 1) / wg, 1, 1);
                        commands++;
                    }
                }
                lastDrawCommands = commands;
        }
        lastTableNanos = 0L;
        lastRecordNanos = System.nanoTime() - recordStart;
    }

    /**
     * One draw per geometry buffer (split only at the workgroup cap),
     * with the per-section detail moved into a storage-buffer run table.
     *
     * <h2>Why the runs are grouped rather than sorted</h2>
     * <p>A draw can only reference one geometry buffer, because the buffer
     * is a descriptor and the descriptor is pushed per draw. Runs
     * therefore have to be contiguous per buffer in the table, and a
     * counting sort gets them there in two linear passes with no
     * allocation — the draw list arrives region by region and regions
     * commonly share a buffer, so it is nearly sorted already but not
     * reliably so.
     *
     * <h2>groupEnd is per GROUP, not per table</h2>
     * <p>The cumulative workgroup count restarts at each buffer group, so
     * {@code gl_WorkGroupID.x} indexes straight into it. That is what
     * keeps the shader's binary search free of a per-draw subtraction, and
     * it is why the split at {@link #MAX_GROUPS_PER_DRAW} needs an
     * explicit {@code GroupBase} push constant instead.
     */
    private static void recordBatched(VkCommandBuffer cb, TerrainDrawPipeline p,
            GpuBufferSlice sceneSlice, GpuBufferSlice projectionSlice, GpuTextureView atlasView,
            GpuSampler atlasSampler, GpuTextureView lightmapView,
            GpuBuffer[] geometryBuffers, int bufferCount,
            int drawCount, int[] drawMeta, float[] drawOrigins) {
        long tableStart = System.nanoTime();
        int wg = p.workgroupQuads();
        int capacity = MesheliumSodiumRunTable.capacity();

        // Pass 1: how many runs each buffer owns, so pass 2 can place them
        // without growing anything.
        int[] start = new int[bufferCount + 1];
        for (int d = 0; d < drawCount; d++) {
            int b = drawMeta[d * META_STRIDE];
            if (b >= 0 && b < bufferCount) {
                start[b + 1]++;
            }
        }
        for (int b = 0; b < bufferCount; b++) {
            start[b + 1] += start[b];
        }
        int totalRuns = start[bufferCount];
        if (totalRuns == 0) {
            lastDrawCommands = 0;
            return;
        }
        if (totalRuns > capacity) {
            // Truncating loses geometry, which is visible. Say so once and
            // draw what fits — a dropped section beats writing past the
            // slot, and growing the ring would need the fence tracking
            // this deliberately does not have.
            if (!runTableOverflowReported) {
                runTableOverflowReported = true;
                MesheliumLog.LOGGER.warn(
                        "Meshelium's Sodium run table overflowed: {} runs against a capacity of "
                                + "{}. Some terrain will not be drawn this frame and in any frame "
                                + "this busy. Raise RUNS_PER_SLOT.", totalRuns, capacity);
            }
        }

        boolean drawId = shape() == Shape.DRAW_ID;
        ByteBuffer table = runTable.advance();
        ByteBuffer indirect = drawId ? runTable.indirectView() : null;
        ByteBuffer groupMap = drawId ? null : runTable.groupMapView();
        long groupMapAddress = groupMap == null ? 0L : MemoryUtil.memAddress(groupMap);
        int groupCapacity = MesheliumSodiumRunTable.groupCapacity();
        // Cumulative workgroups per buffer group, and the cursor each
        // group's runs are written at.
        int[] cursor = new int[bufferCount];
        int[] groups = new int[bufferCount];
        System.arraycopy(start, 0, cursor, 0, bufferCount);
        // Where each buffer group's workgroups start in the ONE group map
        // every group shares this pass. The first version restarted the
        // index at zero for every buffer group, so with three buffers the
        // third group's map overwrote the first two and their workgroups
        // resolved to the wrong runs. The map is global; the push constant
        // GroupBase carries each dispatch's offset into it.
        int[] groupStart = new int[bufferCount + 1];
        if (!drawId) {
            for (int d = 0; d < drawCount; d++) {
                int b = drawMeta[d * META_STRIDE];
                if (b >= 0 && b < bufferCount) {
                    groupStart[b + 1] += (drawMeta[d * META_STRIDE + 2] + wg - 1) / wg;
                }
            }
            for (int b = 0; b < bufferCount; b++) {
                groupStart[b + 1] += groupStart[b];
            }
            if (groupStart[bufferCount] > groupCapacity && !groupMapOverflowReported) {
                groupMapOverflowReported = true;
                MesheliumLog.LOGGER.warn(
                        "Meshelium's Sodium group map overflowed: {} workgroups against a "
                                + "capacity of {}. Dispatches are clipped to the mapped range, so "
                                + "some terrain will not be drawn in frames this busy. Raise "
                                + "GROUPS_PER_SLOT.", groupStart[bufferCount], groupCapacity);
            }
        }
        int written = 0;
        for (int k = 0; k < drawCount; k++) {
            int d = REVERSE_ORDER ? drawCount - 1 - k : k; // diagnostic, see PROPERTY_REVERSE_ORDER
            int b = drawMeta[d * META_STRIDE];
            if (b < 0 || b >= bufferCount) {
                continue;
            }
            int slot = cursor[b]++;
            if (slot >= capacity) {
                continue; // overflow, already reported
            }
            int firstVertex = drawMeta[d * META_STRIDE + 1];
            int quads = drawMeta[d * META_STRIDE + 2];
            int runGroups = (quads + wg - 1) / wg;
            if (!drawId) {
                // One map entry per workgroup this run will dispatch, so
                // the shader reads its run instead of searching for it.
                // Indexed from the buffer group's GLOBAL start, and written
                // with raw stores over a hoisted bound: this runs tens of
                // thousands of times a pass, and bounds-checked
                // ByteBuffer.putInt was most of a 54 us cost. Sequential
                // within a group, which is what write-combined memory wants.
                int local = slot - start[b];
                int gBase = groupStart[b] + groups[b];
                int end = Math.min(gBase + runGroups, groupCapacity);
                int packedBase = local << MesheliumSodiumRunTable.GROUP_LOCAL_BITS;
                long addr = groupMapAddress + (long) gBase * 4L;
                for (int gi = gBase, i = 0; gi < end; gi++, i++) {
                    MemoryUtil.memPutInt(addr, packedBase | i);
                    addr += 4L;
                }
            }
            groups[b] += runGroups;
            int o = slot * MesheliumSodiumRunTable.RUN_BYTES;
            table.putInt(o, firstVertex);
            table.putInt(o + 4, quads);
            table.putInt(o + 8, groups[b]);   // cumulative WITHIN the group
            table.putInt(o + 12, 0);
            table.putFloat(o + 16, drawOrigins[d * 3]);
            table.putFloat(o + 20, drawOrigins[d * 3 + 1]);
            table.putFloat(o + 24, drawOrigins[d * 3 + 2]);
            table.putFloat(o + 28, 0.0f);
            if (drawId) {
                // VkDrawMeshTasksIndirectCommandEXT: groupCountX/Y/Z. One
                // command per run, at the run's own table index, so
                // gl_DrawID and the run index are the same number minus
                // the group's FirstRun.
                int io = slot * MesheliumSodiumRunTable.INDIRECT_BYTES;
                indirect.putInt(io, (quads + wg - 1) / wg);
                indirect.putInt(io + 4, 1);
                indirect.putInt(io + 8, 1);
            }
            written++;
        }
        lastRunsWritten = written;
        lastTableNanos = System.nanoTime() - tableStart;

        long recordStart = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.calloc(TerrainDrawPipeline.PUSH_BYTES);
            int commands = 0;
            for (int k = 0; k < bufferCount; k++) {
                int b = REVERSE_ORDER ? bufferCount - 1 - k : k; // diagnostic, see PROPERTY_REVERSE_ORDER
                int first = start[b];
                int end = Math.min(cursor[b], capacity);
                if (end <= first || groups[b] == 0) {
                    continue;
                }
                pushDescriptors(cb, p, geometryBuffers[b], sceneSlice, projectionSlice,
                        atlasView, atlasSampler, lightmapView);
                if (drawId) {
                    // ONE command for the whole group, and every run in it
                    // gets its own dispatch with its own gl_DrawID. No
                    // workgroup cap applies: the cap bounds a single
                    // dispatch, and each of these is one run's worth.
                    push.putInt(12, first);        // FirstRun
                    push.putInt(16, end - first);  // RunCount (unused, kept honest)
                    push.putInt(20, 0);            // GroupBase (unused here)
                    VK10.vkCmdPushConstants(cb, p.pipelineLayout(),
                            EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT, 0, push);
                    EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cb,
                            runTable.indirectBuffer(),
                            runTable.indirectOffset()
                                    + (long) first * MesheliumSodiumRunTable.INDIRECT_BYTES,
                            end - first, MesheliumSodiumRunTable.INDIRECT_BYTES);
                    commands++;
                    continue;
                }
                // One draw unless the group exceeds the workgroup cap.
                // Never dispatch a workgroup the map has no entry for: an
                // unmapped workgroup would resolve to run 0 and draw its
                // geometry a second time at the wrong place.
                int mapped = Math.max(0, Math.min(groups[b], groupCapacity - groupStart[b]));
                for (int base = 0; base < mapped; base += MAX_GROUPS_PER_DRAW) {
                    int n = Math.min(MAX_GROUPS_PER_DRAW, mapped - base);
                    push.putInt(12, first);                   // FirstRun
                    push.putInt(16, end - first);             // RunCount
                    push.putInt(20, groupStart[b] + base);    // GroupBase, global
                    VK10.vkCmdPushConstants(cb, p.pipelineLayout(),
                            EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT, 0, push);
                    EXTMeshShader.vkCmdDrawMeshTasksEXT(cb, n, 1, 1);
                    commands++;
                }
            }
            lastDrawCommands = commands;
        }
        lastRecordNanos = System.nanoTime() - recordStart;
    }

    private static TerrainDrawPipeline pipelineFor(VulkanRenderPassAccessor vkPass,
            GpuTextureView colorView, GpuTextureView depthView, boolean noDiscard) {
        TerrainDrawPipeline p = noDiscard ? pipelineNoDiscard : pipeline;
        if (p == null) {
            int vkColorFormat = VulkanConst.toVk(colorView.texture().getFormat());
            int vkDepthFormat = VulkanConst.toVk(depthView.texture().getFormat());
            int wg = TerrainDrawer.meshWorkgroupQuads();
            MesheliumVulkanState.MeshShaderCaps caps = MesheliumVulkanState.caps();
            if (caps == null) {
                throw new IllegalStateException(
                        "no mesh-shader caps: the Vulkan device was created without "
                                + "VK_EXT_mesh_shader, so this path should never have armed");
            }
            if (caps.maxMeshWorkGroupInvocations() < wg
                    || caps.maxMeshOutputVertices() < wg * 4
                    || caps.maxMeshOutputPrimitives() < wg * 2
                    || (caps.maxMeshOutputMemorySize() > 0
                            && caps.maxMeshOutputMemorySize()
                                    < wg * TerrainDrawer.MESH_OUTPUT_BYTES_PER_QUAD)) {
                throw new IllegalStateException("device mesh caps below the Sodium draw shape ("
                        + wg + " quads/workgroup): " + caps);
            }
            // The table has to exist before the pipeline that declares
            // its binding, because a batched pipeline with nothing to bind
            // at 9 is an invalid descriptor push, not a slow frame. If it
            // cannot be created, compile the per-section variant instead
            // and keep drawing.
            Shape sh = shape();
            if (sh != Shape.PER_SECTION && runTable == null) {
                runTable = MesheliumSodiumRunTable.create();
                if (runTable == null) {
                    sh = Shape.PER_SECTION;
                    shapeResolved = sh; // the recorder must agree with the shader
                }
            }
            p = TerrainDrawPipeline.createSodium(vkPass.meshelium$device().vkDevice(),
                    vkColorFormat, vkDepthFormat, wg,
                    sh != Shape.PER_SECTION, sh == Shape.DRAW_ID, noDiscard);
            if (noDiscard) {
                pipelineNoDiscard = p;
            } else {
                pipeline = p;
            }
            MesheliumLog.LOGGER.info(
                    "Meshelium's Sodium draw pipeline created (color format {}, depth format {}, "
                            + "{} quads/workgroup, shape={}, fragment={}). From here Meshelium's "
                            + "mesh shaders draw the opaque terrain out of Sodium's own region "
                            + "buffers, in Sodium's vertex format, with no copy and no re-encode.",
                    vkColorFormat, vkDepthFormat, wg, sh, noDiscard ? "no-discard" : "discard");
        }
        return p;
    }

    /**
     * The seven bindings of the Sodium variant. Identical to the standalone
     * cpu variant's set except binding 0, which is one foreign buffer
     * rather than the arena's block array.
     *
     * <p>Binding 2 is Meshelium's own upload of Sodium's projection matrix
     * rather than {@code RenderSystem.getProjectionMatrixBuffer()}. Sodium
     * hands its matrices to the renderer explicitly, and taking both from
     * the same source is the only way to be sure the model-view and the
     * projection describe the same frame.</p>
     */
    private static void pushDescriptors(VkCommandBuffer cb, TerrainDrawPipeline p,
            GpuBuffer geometry, GpuBufferSlice sceneSlice, GpuBufferSlice projectionSlice,
            GpuTextureView atlasView, GpuSampler atlasSampler, GpuTextureView lightmapView) {
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
        GpuSampler lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer geometryInfo = bufferInfo(stack,
                    ((VulkanGpuBuffer) geometry).vkBuffer(), 0, VK10.VK_WHOLE_SIZE);
            VkDescriptorBufferInfo.Buffer sceneInfo = bufferInfo(stack,
                    ((VulkanGpuBuffer) sceneSlice.buffer()).vkBuffer(),
                    sceneSlice.offset(), sceneSlice.length());
            VkDescriptorBufferInfo.Buffer projectionInfo = bufferInfo(stack,
                    ((VulkanGpuBuffer) projectionSlice.buffer()).vkBuffer(),
                    projectionSlice.offset(), projectionSlice.length());
            VkDescriptorBufferInfo.Buffer fogInfo = bufferInfo(stack,
                    ((VulkanGpuBuffer) fog.buffer()).vkBuffer(), fog.offset(), fog.length());
            VkDescriptorBufferInfo.Buffer globalsInfo = bufferInfo(stack,
                    ((VulkanGpuBuffer) globals).vkBuffer(), 0, VK10.VK_WHOLE_SIZE);
            VkDescriptorImageInfo.Buffer atlasInfo = imageInfo(stack, atlasView, atlasSampler);
            VkDescriptorImageInfo.Buffer lightmapInfo = imageInfo(stack, lightmapView, lightmapSampler);

            boolean batched = batchedEnabled() && runTable != null;
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(batched ? 9 : 7, stack);
            bufferWrite(writes.get(0), 0, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, geometryInfo);
            bufferWrite(writes.get(1), 1, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, sceneInfo);
            bufferWrite(writes.get(2), 2, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, projectionInfo);
            bufferWrite(writes.get(3), 3, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, fogInfo);
            bufferWrite(writes.get(4), 4, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, globalsInfo);
            imageWrite(writes.get(5), 5, atlasInfo);
            imageWrite(writes.get(6), 6, lightmapInfo);
            if (batched) {
                bufferWrite(writes.get(7), 9, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                        bufferInfo(stack, runTable.vkBuffer(), runTable.offset(),
                                runTable.range()));
                bufferWrite(writes.get(8), 10, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                        bufferInfo(stack, runTable.groupMapBuffer(), runTable.groupMapOffset(),
                                runTable.groupMapRange()));
            }

            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb,
                    VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipelineLayout(), 0, writes);
        }
    }

    /**
     * Meshelium's scene UBO, the subset this variant reads.
     *
     * <p>The block is declared identically to the standalone path so one
     * shader source serves both, but only four fields can change a pixel
     * here: the model-view matrix (mesh stage), the atlas size (fragment
     * stage), CullMisc (the sub-pixel cull plus the viewport it needs) and,
     * under the frustum lever's gpu mode, the six frustum planes (the
     * mesh stage's per-run gate). The camera chunk belongs to the task
     * stage, which this variant does not compile.</p>
     */
    private static GpuBufferSlice uploadScene(CommandEncoder encoder, Matrix4fc modelView,
            Matrix4fc projection, int atlasWidth, int atlasHeight) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer scene = stack.calloc(TerrainDrawer.SCENE_BYTES);
            modelView.get(scene); // 64 bytes at position 0, position unchanged
            scene.putFloat(64, (float) atlasWidth);
            scene.putFloat(68, (float) atlasHeight);
            // Written in every mode: six rows of one 4x4 product cost
            // nothing, and a gpu-mode variant reading zeros would emit
            // nothing at all.
            putFrustumPlanes(scene, modelView, projection);

            float subPixelDist = MesheliumConfig.subPixelCullChunks() * 16.0f;
            // The plant cull is a task-stage gate and this variant has no
            // task stage; write the disabled sentinel so the field can
            // never be read as an armed distance.
            scene.putFloat(192, TerrainDrawer.CULL_OFF_DIST2);
            scene.putFloat(196, subPixelDist <= 0.0f ? TerrainDrawer.CULL_OFF_DIST2
                    : subPixelDist * subPixelDist);
            com.mojang.blaze3d.pipeline.RenderTarget mainTarget =
                    Minecraft.getInstance().gameRenderer.mainRenderTarget();
            scene.putFloat(200, (float) mainTarget.width);
            scene.putFloat(204, (float) mainTarget.height);

            return encoder.transientMemory().uploadGpu(scene, 256, GpuBuffer.USAGE_UNIFORM);
        }
    }

    /** Sodium's projection matrix, in the layout vanilla's Projection UBO uses. */
    private static GpuBufferSlice uploadProjection(CommandEncoder encoder, Matrix4fc projection) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buf = stack.calloc(64);
            projection.get(buf);
            return encoder.transientMemory().uploadGpu(buf, 256, GpuBuffer.USAGE_UNIFORM);
        }
    }

    private static VkDescriptorBufferInfo.Buffer bufferInfo(MemoryStack stack, long vkBuffer,
            long offset, long range) {
        VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
        info.get(0).buffer(vkBuffer).offset(offset).range(range);
        return info;
    }

    private static VkDescriptorImageInfo.Buffer imageInfo(MemoryStack stack, GpuTextureView view,
            GpuSampler sampler) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0)
                .sampler(((VulkanGpuSampler) sampler).vkSampler())
                .imageView(((VulkanGpuTextureView) view).vkImageView())
                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        return info;
    }

    private static void bufferWrite(VkWriteDescriptorSet write, int binding, int type,
            VkDescriptorBufferInfo.Buffer info) {
        write.sType$Default()
                .dstBinding(binding)
                .descriptorCount(info.remaining())
                .descriptorType(type)
                .pBufferInfo(info);
    }

    private static void imageWrite(VkWriteDescriptorSet write, int binding,
            VkDescriptorImageInfo.Buffer info) {
        write.sType$Default()
                .dstBinding(binding)
                .descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .pImageInfo(info);
    }

    /**
     * Destroy the pipeline. Called from {@code TerrainDrawer}'s own
     * device-close hook, which runs after vanilla's queue idle.
     */
    public static void destroyDeviceObjects(org.lwjgl.vulkan.VkDevice device) {
        if (pipeline != null) {
            pipeline.destroy(device);
            pipeline = null;
        }
        if (pipelineNoDiscard != null) {
            pipelineNoDiscard.destroy(device);
            pipelineNoDiscard = null;
        }
        if (runTable != null) {
            runTable.destroyNow();
            runTable = null;
        }
        // Stage 2/3: the task-stage pipelines and any rung-0 instance state
        // still live at device close (a RenderSectionManager that was never
        // destroyed); direct destroys, the deferred queue is drained here.
        if (pipelineTask != null) {
            pipelineTask.destroy(device);
            pipelineTask = null;
        }
        if (pipelineTaskNoDiscard != null) {
            pipelineTaskNoDiscard.destroy(device);
            pipelineTaskNoDiscard = null;
        }
        if (!liveInstances.isEmpty()) {
            MesheliumLog.LOGGER.warn(
                    "Meshelium: {} Sodium GPU-visibility instance(s) still live at device close "
                            + "(their chunk renderer was never deleted?); destroying directly",
                    liveInstances.size());
            for (InstanceState s : new ArrayList<>(liveInstances)) {
                s.destroyNow();
            }
            liveInstances.clear();
        }
        activeState = null;
    }

    // ==================================================================
    // Stage 2/3 (D-023): rung 0, the GPU-visibility draw off the record
    // mirror. Everything above this line is rung 1 (the stage-1 list
    // path), unchanged: it is the per-frame fallback and the parity
    // control, and drawOpaque is still what every declined frame runs.
    // ==================================================================

    /**
     * {@code -Dmeshelium.sodium.gpuDraw=true} arms stage 2: the task stage
     * selects runs from the GPU-resident record mirror instead of the
     * CPU-built run table, and the per-frame CPU work drops to
     * O(listed regions) (GPU-VISIBILITY-DESIGN.md section 4, MEASUREMENTS.md 0k/0l).
     * DEFAULT ON since 2026-09-13 (D-025, MEASUREMENTS.md 0q-0s), from the
     * config row {@code MesheliumConfig.sodiumGpuVisibility}; the property
     * overrides the row either way. The GPU draw without occlusion (rung
     * 0b) is the parity checkpoint, never a default.
     */
    public static final String PROPERTY_GPU_DRAW = "meshelium.sodium.gpuDraw";

    /**
     * {@code -Dmeshelium.sodium.occlusion=true} arms stage 3 on top of the
     * GPU draw: the region and section box rasters with temporal history
     * (VisMode 1/2). Follows the GPU draw (on with it) unless set to
     * {@code false} explicitly; needs {@link #PROPERTY_GPU_DRAW}. 0n
     * measured the prize under the canopy, 0o priced the floor, 0r found
     * the floor was the dispatch and removed it (D-025).
     */
    public static final String PROPERTY_OCCLUSION = "meshelium.sodium.occlusion";

    /**
     * Default true: the listed set is Sodium's own graph-culled list
     * (parity-preserving in every VisMode). {@code false} walks
     * {@code getLoadedRegions()} under occlusion and frustum-tests the
     * region boxes instead: sections the BFS could not reach may then
     * draw ("draw more, never fewer", design section 5.5).
     */
    public static final String PROPERTY_GRAPH_REGIONS = "meshelium.sodium.graphRegions";

    /**
     * Where the per-region geometry bitmap comes from in the O(listed
     * regions) loop. Four values, default {@link #LIST_MAP_OFF}:
     *
     * <ul>
     *   <li>{@code off} - drain Sodium's byte iterator, the pre-2026-09-18
     *       behaviour, kept as the way back.</li>
     *   <li>{@code on} - read the bitmap Sodium already built.</li>
     *   <li>{@code verify} - read it AND drain, compare all 256 bits,
     *       and fall back for the session on any disagreement.</li>
     *   <li>{@code ab} - alternate by frame into two accumulators, which
     *       is how the difference gets measured in ONE session.</li>
     * </ul>
     *
     * <p>Default {@code on} since 2026-09-18, by the owner's decision. It
     * shipped off for one day under the uncertainty rule, which sends
     * anything he has not judged in person to a lever; he judged it on the
     * evidence: 0u for the numbers (the per-region loop halves, 52.31 ->
     * 26.21 us at rd64), leg 97_20 for the parity on a real world, and both
     * suites green with it on (50 and 29 shots, 1,016 regions compared, 0
     * mismatches, no fallback).
     *
     * <p>What makes it safe to default rather than merely fast: the two
     * bitmaps are the same bits by construction ({@code ChunkRenderList.add}
     * writes both inside one branch), the field is verified at class-load
     * before the accessor is ever called, every read cross-checks its
     * popcount against Sodium's own count, and any disagreement falls back
     * to the drain for the session with one warning. The failure mode is a
     * slightly slower loop, never a wrong set.
     */
    public static final String PROPERTY_LIST_MAP = "meshelium.sodium.listMap";

    public static final String LIST_MAP_OFF = "off";

    public static final String LIST_MAP_ON = "on";

    public static final String LIST_MAP_VERIFY = "verify";

    public static final String LIST_MAP_AB = "ab";

    /**
     * {@code fog} (default): D = Sodium's own {@code getSearchDistance(fog)}
     * through the mixin invoker; {@code render}: widen to
     * {@code renderDistance * 16}; {@code off}: no distance gate. VisMode 1/2
     * only. Contract section 6.3 explains why the lever exists: which cull
     * tree fed the lists on a given frame is recon-level, and the horizon
     * screenshot is the judge.
     */
    public static final String PROPERTY_DISTANCE_GATE = "meshelium.sodium.distanceGate";

    /** Absent = on: the phase-B CPU skip re-based on the mirror epoch (contract section 7.4). */
    public static final String PROPERTY_PHASE_B_CPU_SKIP = "meshelium.sodium.phaseBCpuSkip";

    /**
     * {@code -Dmeshelium.sodium.mergePhaseA=true}: on an owned frame,
     * record the CUTOUT phase-A draw in the SAME render pass as the
     * SOLID one, at the SOLID call, and leave the CUTOUT call to the
     * rasters and phase B. One render pass fewer per frame.
     *
     * <p>Why (2026-09-13). Vanilla ends every render pass with a full
     * ALL_COMMANDS memory barrier (VANILLA-FRAME-PATH.md), a GPU drain;
     * rung 0a records five passes a frame (1a, 1b, region raster, section
     * raster, phase B) against the list path's two, and D-024's floor is
     * suspected to be mostly those three extra drains. Both phase-A draws
     * are depth-tested opaque geometry drawn SOLID then CUTOUT into the
     * same attachments, so their order and result do not change; only
     * the pass boundary between them goes. Measured -1.9% (0r); default
     * on since D-025, {@code false} restores the separate pass.
     */
    public static final String PROPERTY_MERGE_PHASE_A = "meshelium.sodium.mergePhaseA";

    /**
     * {@code -Dmeshelium.sodium.occBoxesPerWG=N} (1..8, default 4 since
     * D-025; measured -8% of the raster, -1.4% of the frame, 0r): section
     * boxes per mesh workgroup in the Sodium host's section raster. One
     * 8-lane workgroup per box (the standalone's shape) leaves most of
     * every wave idle and prices the raster by launches: 0.169 ms a frame
     * at ~9k boxes while turning at aerial rd64 (2026-09-13). Lever until
     * measured; the pixels drawn are the same boxes in the same order
     * within a workgroup, only packed.
     */
    public static final String PROPERTY_OCC_BOXES_PER_WG = "meshelium.sodium.occBoxesPerWG";

    /**
     * {@code -Dmeshelium.sodium.diag.taskSkip=none|all|stamps}: measurement
     * only. Under a moving camera phase B costs 0.22 ms a frame while
     * drawing two sections (2026-09-13, aerial rd64 jitter), i.e. the
     * task stage costs about 11-15 ns per masked-section lane before it
     * draws anything. {@code all} makes every lane emit nothing before
     * any load (what the launches and payloads alone cost);
     * {@code stamps} keeps the row and rank work but reads no stamps
     * (what the two scattered 4-byte loads cost). Either one breaks the
     * picture on purpose; never a setting.
     */
    public static final String PROPERTY_DIAG_TASK_SKIP = "meshelium.sodium.diag.taskSkip";

    /**
     * {@code -Dmeshelium.sodium.oneDrawPerGroup=true}: one indirect task
     * draw per buffer group per phase-pass, instead of one per listed
     * region (a multi-draw of ~200 commands per group).
     *
     * <p>Why (2026-09-13, MEASUREMENTS.md 0r). With every task lane made
     * to emit nothing before its first load, phase A still cost 0.244 ms
     * and phase B 0.236 ms a frame at aerial rd64 under a moving camera:
     * the per-lane work is free and the cost is the dispatch. Rung 0a
     * records about 800 task-shader indirect draws a frame (regions x
     * phases x passes) at roughly 0.3 us each, while the two rasters,
     * which are single draws, are cheap. So the region's draw goes and the
     * task workgroup finds its region itself: a binary search over the
     * group's list entries on their cumulative workgroup count
     * (LIST_GROUP_END), eight dependent scalar loads per workgroup, a few
     * hundred workgroups a frame. Measured (0r): phase B 0.223 -> 0.049 ms,
     * the moving-camera frame -15% aerial / -23% canopy. Default on since
     * D-025; {@code false} restores the per-region multi-draw.
     */
    public static final String PROPERTY_ONE_DRAW = "meshelium.sodium.oneDrawPerGroup";

    private static final boolean ONE_DRAW =
            !"false".equalsIgnoreCase(System.getProperty(PROPERTY_ONE_DRAW));

    /** True when the lever above is on, for the bench report. */
    public static boolean oneDrawPerGroup() {
        return ONE_DRAW;
    }

    private static final int DIAG_TASK_SKIP_FLAGS = diagTaskSkipFlags(
            System.getProperty(PROPERTY_DIAG_TASK_SKIP, "none"));

    private static int diagTaskSkipFlags(String value) {
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "all" -> SodiumGpuVisibilityLayout.FLAG_DIAG_SKIP_ALL;
            case "stamps" -> SodiumGpuVisibilityLayout.FLAG_DIAG_SKIP_STAMPS;
            default -> 0;
        };
    }

    /** The diagnostic's name, for the bench report. */
    public static String diagTaskSkip() {
        return System.getProperty(PROPERTY_DIAG_TASK_SKIP, "none");
    }

    public static final int OCC_BOXES_PER_WG =
            Math.max(1, Math.min(8, Integer.getInteger(PROPERTY_OCC_BOXES_PER_WG, 4)));

    private static final boolean MERGE_PHASE_A =
            !"false".equalsIgnoreCase(System.getProperty(PROPERTY_MERGE_PHASE_A));

    /** True when the lever above is on, for the bench report. */
    public static boolean mergePhaseA() {
        return MERGE_PHASE_A;
    }

    /**
     * Diagnostic, default false: keep a CPU copy of every committed record
     * block, re-read Sodium's heap every owned frame and count differing
     * bytes; read one block back from the GPU once per session and compare
     * (I1's proof, design stage 1's "0 mismatches over 10k frames").
     */
    public static final String PROPERTY_MIRROR_AUDIT = "meshelium.sodium.mirrorAudit";

    private static volatile boolean gpuDrawEnabled = MesheliumConfig.sodiumGpuVisibilityEnabled();

    private static volatile boolean occlusionEnabled = occlusionConfigured(gpuDrawEnabled);

    /** The occlusion lever's effective value: on with the GPU draw unless the property says false. */
    private static boolean occlusionConfigured(boolean gpuDraw) {
        String v = System.getProperty(PROPERTY_OCCLUSION);
        return v != null ? Boolean.parseBoolean(v) : gpuDraw;
    }

    /**
     * Re-read the config row and the two properties (the options row's
     * write path, and the suite's restore after its lever legs). Safe at
     * any frame: rung selection reads these per pass and a frame that
     * changes hands leaves nothing stale on either side.
     */
    public static void applyConfiguredGpuVisibility() {
        boolean gpu = MesheliumConfig.sodiumGpuVisibilityEnabled();
        gpuDrawEnabled = gpu;
        occlusionEnabled = occlusionConfigured(gpu);
    }

    private static volatile boolean graphRegions =
            !"false".equalsIgnoreCase(System.getProperty(PROPERTY_GRAPH_REGIONS));

    private static volatile String listMapMode = normaliseListMap(System.getProperty(PROPERTY_LIST_MAP));

    /** Frames and loop time, split by which implementation drew them ({@code ab}). */
    private static volatile long listMapDrainNanos;

    private static volatile long listMapMapNanos;

    private static volatile int listMapDrainFrames;

    private static volatile int listMapMapFrames;

    /** Regions listed on the frames counted above, so the means are per-region comparable. */
    private static volatile long listMapDrainRegions;

    private static volatile long listMapMapRegions;

    /** {@code verify}: regions compared, and regions whose 256 bits disagreed. */
    private static volatile long listMapCompares;

    private static volatile long listMapMismatches;

    /** Why the map was stood down for this session, or null. Latched. */
    private static volatile String listMapDisarmReason;

    private static final boolean MIRROR_AUDIT = Boolean.getBoolean(PROPERTY_MIRROR_AUDIT);

    /** Test only: the next SOLID call declines to rung 1 once, for this reason. */
    private static volatile String forcedDeclineReason;

    /** Test only: {@code CAPACITY_INITIAL} for the NEXT instance; <= 0 = the default. */
    private static volatile int initialCapacityOverride = -1;

    private static volatile boolean gpuDrawBroken;

    private static volatile String gpuDrawError;

    private static volatile boolean occlusionBroken;

    private static volatile String occlusionError;

    /** The task-stage pipeline pair (discard / no-discard), device-lifetime like the others. */
    private static TerrainDrawPipeline pipelineTask;

    private static TerrainDrawPipeline pipelineTaskNoDiscard;

    /** Every instance state not yet retired, for the device-close sweep. */
    private static final List<InstanceState> liveInstances = new ArrayList<>();

    /** The instance that drew most recently: the static probes read it. */
    private static volatile InstanceState activeState;

    /**
     * Frame stamps are a session-wide monotonic: a fresh instance (or a
     * grown one, whose stamp buffers are recreated zero-filled) never
     * reads a leftover value as fresh. Starts above 1 so {@code FrameStamp-1}
     * can never equal the zero fill.
     */
    private static long stampSerial = 1L;

    private static final Matrix4f mvpScratch = new Matrix4f();

    // ---- rung-0 counters (section 10 of the contract; all read by the bench and the suite) ----

    private static volatile String rung = "1";

    private static volatile long framesOwned;

    private static volatile long framesAttempted;

    private static volatile long occlusionFrames;

    private static final Map<String, Long> frameDeclines = new LinkedHashMap<>();

    private static volatile long frameDeclinesTotal;

    private static volatile int commitRegionsPerFrame;

    private static volatile long commitRegionsTotal;

    private static volatile long commitBytesPerFrame;

    private static volatile long commitNanos;

    private static volatile long deadRows;

    private static volatile int midsLive;

    private static volatile int midsCapacity;

    private static volatile long midsReleased;

    private static volatile long growths;

    private static volatile int bufferKeys;

    private static volatile int listedRegions;

    /** Sections in this frame's listed masks (the GPU's input set); accounted into the pass counters by the owned draws. */
    private static volatile int listedSections;

    private static volatile int bufferGroups;

    private static volatile int drawCommandsPerFrame;

    private static volatile long loopNanos;

    private static volatile int gpuSectionsMask;

    private static volatile int gpuSectionsA;

    private static volatile int gpuSectionsB;

    private static volatile long gpuQuads;

    private static volatile long statsFramesRead;

    private static volatile int sectionsEmitted;

    private static final int[] sectionsEmittedByPass = new int[2];

    private static final long[] quadsKeptByPass = new long[2];

    private static volatile long midMissing;

    private static volatile long keyMismatch;

    private static volatile long mirrorAuditMismatches;

    private static volatile long mirrorGpuReadbackMismatches;

    private static volatile long mirrorAuditFrames;

    private static volatile String searchDistanceSource = "none";

    private static volatile float searchDistanceBlocks;

    private static volatile boolean lastFaceAll;

    private static volatile boolean phaseBCpuSkipArmed;

    private static volatile long phaseBCpuSkips;

    private static volatile long instancesRetired;

    private static volatile long occlusionRecreates;

    private static volatile String gpuTimerUnit = "none";

    private static volatile boolean gpuDrawLogged;

    // ---- levers and toggles ----

    public static boolean gpuDrawEnabled() {
        return gpuDrawEnabled;
    }

    /** Suite/bench toggle, like {@link #setRunCacheEnabled}. Safe mid-session: a frame is owned whole. */
    public static void setGpuDrawEnabled(boolean value) {
        gpuDrawEnabled = value;
    }

    public static boolean occlusionEnabled() {
        return occlusionEnabled;
    }

    /** Suite toggle. Stamps left behind by an off interlude are inert (equality self-invalidates). */
    public static void setOcclusionEnabled(boolean value) {
        occlusionEnabled = value;
    }

    public static boolean graphRegions() {
        return graphRegions;
    }

    /**
     * Only the four known values; anything unrecognised falls back to the
     * DEFAULT, which is {@link #LIST_MAP_ON} since 2026-09-18.
     *
     * <p>An unreadable or misspelled property therefore lands on the fast
     * path rather than the slow one, and {@code off} stays spelled out as
     * the way back to the drain.
     */
    private static String normaliseListMap(String raw) {
        if (raw == null) {
            return LIST_MAP_ON;
        }
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (LIST_MAP_ON.equals(value) || LIST_MAP_VERIFY.equals(value)
                || LIST_MAP_AB.equals(value) || LIST_MAP_OFF.equals(value)) {
            return value;
        }
        return LIST_MAP_ON;
    }

    public static String listMapMode() {
        return listMapMode;
    }

    /**
     * Clear the A/B accumulators without touching the mode.
     *
     * <p>The bench calls this when it ARMS a leg, and the reason is a
     * fault this instrument shipped with for exactly one run (2026-09-17):
     * accumulating from game start folds in every frame of world
     * generation and settle, where the world is nearly empty and the loop
     * is nearly free. The first rd64 pair read 891,937 loop calls in a
     * 9m27s run - about 2,000 a second, against the 850 fps a real rd64
     * forest frame allows - and a mean of 61.7 listed regions against 0r's
     * ~200 at that distance. Both sides were diluted equally so the
     * DIFFERENCE was still honest, but as a mean over a session rather
     * than over the pose anybody would quote. The window has to be the
     * measured window.
     */
    public static void resetListMapCounters() {
        listMapDrainNanos = 0L;
        listMapMapNanos = 0L;
        listMapDrainFrames = 0;
        listMapMapFrames = 0;
        listMapDrainRegions = 0L;
        listMapMapRegions = 0L;
        listMapCompares = 0L;
        listMapMismatches = 0L;
    }

    /** Test only: the listMap mode, flipped live, counters cleared with it. */
    public static void setListMapModeForTest(String mode) {
        listMapMode = normaliseListMap(mode);
        listMapDrainNanos = 0L;
        listMapMapNanos = 0L;
        listMapDrainFrames = 0;
        listMapMapFrames = 0;
        listMapDrainRegions = 0L;
        listMapMapRegions = 0L;
        listMapCompares = 0L;
        listMapMismatches = 0L;
    }

    /**
     * One buildFrame, attributed to the implementation that drew it.
     *
     * <p>Whole-frame loop time rather than a timer around the bitmap fill
     * itself: {@code System.nanoTime} costs ~20-25 ns on this platform and
     * the fill is a few microseconds across a couple of hundred regions,
     * so a per-region timer would measure itself. Alternating whole frames
     * and differencing the means keeps one nanoTime pair per frame, which
     * is the pair that was already there.
     */
    public static void reportListMapFrame(boolean usedMap, long nanos, int listedRegions) {
        if (usedMap) {
            listMapMapNanos += nanos;
            listMapMapFrames++;
            listMapMapRegions += listedRegions;
        } else {
            listMapDrainNanos += nanos;
            listMapDrainFrames++;
            listMapDrainRegions += listedRegions;
        }
    }

    /** One region compared under {@code verify}; {@code diff} words disagreed. */
    public static void reportListMapCompare(int diff) {
        listMapCompares++;
        if (diff != 0) {
            listMapMismatches++;
        }
    }

    /**
     * Stand the map down for the rest of the session; the first reason
     * wins, and it is said out loud once.
     */
    public static void disarmListMap(String reason) {
        if (listMapDisarmReason == null) {
            listMapDisarmReason = reason;
            MesheliumLog.LOGGER.warn("Meshelium is back to draining Sodium's section iterator for "
                    + "the per-region geometry bitmap: {}. Rendering is unaffected; the loop is "
                    + "slightly slower at distance.", reason);
        }
    }

    public static boolean listMapDisarmed() {
        return listMapDisarmReason != null;
    }

    public static String listMapDisarmReason() {
        return listMapDisarmReason;
    }

    public static long listMapCompares() {
        return listMapCompares;
    }

    public static long listMapMismatches() {
        return listMapMismatches;
    }

    public static int listMapDrainFrames() {
        return listMapDrainFrames;
    }

    public static int listMapMapFrames() {
        return listMapMapFrames;
    }

    /** Mean loop microseconds on the frames the drain drew, or 0 when it drew none. */
    public static double listMapDrainMicros() {
        int frames = listMapDrainFrames;
        return frames == 0 ? 0.0 : listMapDrainNanos / (frames * 1000.0);
    }

    /** Mean loop microseconds on the frames the map drew, or 0 when it drew none. */
    public static double listMapMapMicros() {
        int frames = listMapMapFrames;
        return frames == 0 ? 0.0 : listMapMapNanos / (frames * 1000.0);
    }

    /** Mean listed regions per frame on each side, so a mean gap can be read per region. */
    public static double listMapDrainRegionsMean() {
        int frames = listMapDrainFrames;
        return frames == 0 ? 0.0 : listMapDrainRegions / (double) frames;
    }

    public static double listMapMapRegionsMean() {
        int frames = listMapMapFrames;
        return frames == 0 ? 0.0 : listMapMapRegions / (double) frames;
    }

    /** Test only: the graphRegions lever, flipped live. */
    public static void setGraphRegionsForTest(boolean value) {
        graphRegions = value;
    }

    /** TEST only: the next SOLID call declines to rung 1 once (leg 97_05 / 97_16). */
    public static void forceDeclineNextFrame(String reason) {
        forcedDeclineReason = reason == null ? "forced" : reason;
    }

    /** Consumed by the renderer at the SOLID call; null when no decline is forced. */
    public static String takeForcedDecline() {
        String r = forcedDeclineReason;
        forcedDeclineReason = null;
        return r;
    }

    /**
     * TEST only: the mirror capacity of the NEXT instance (leg 97_07 pins
     * it below the live id count to force a growth). Values below
     * {@link SodiumGpuVisibilityLayout#CAPACITY_TEST_MIN} are rounded up to
     * it; 0 or negative restores the default.
     *
     * <p>The floor used to be {@code CAPACITY_ROUND} (64), which the
     * suite's world never overflows at a fixed camera: Sodium builds only
     * the sections its graph walk reaches, so a render-distance change to
     * 13 uploaded into fewer than 64 regions in 65 seconds and the growth
     * leg timed out (first stage-2/3 run). Growth is a property of the id
     * space, not of the world; the leg now pins below what it can count.
     */
    public static void setMirrorCapacityForTest(int capacity) {
        initialCapacityOverride = capacity <= 0 ? -1
                : Math.max(capacity, SodiumGpuVisibilityLayout.CAPACITY_TEST_MIN);
    }

    /** The mirror capacity a NEW instance starts at ({@code CAPACITY_INITIAL} unless a test lowered it). */
    public static int initialCapacity() {
        int o = initialCapacityOverride;
        return o > 0 ? o : SodiumGpuVisibilityLayout.CAPACITY_INITIAL;
    }

    public static boolean mirrorAuditEnabled() {
        return MIRROR_AUDIT;
    }

    /** The distance-gate lever, normalised: "fog", "render" or "off" (unknown values widen to "render"). */
    public static String distanceGateMode() {
        String v = System.getProperty(PROPERTY_DISTANCE_GATE);
        if (v == null || v.isEmpty() || "fog".equalsIgnoreCase(v)) {
            return "fog";
        }
        if ("off".equalsIgnoreCase(v)) {
            return "off";
        }
        return "render";
    }

    /** Absent = on, the multiWG rule; re-read every frame like the other draw-path properties. */
    public static boolean phaseBCpuSkipEnabled() {
        String v = System.getProperty(PROPERTY_PHASE_B_CPU_SKIP);
        return v == null || Boolean.parseBoolean(v);
    }

    // ---- latches ----

    public static boolean gpuDrawBroken() {
        return gpuDrawBroken;
    }

    public static String gpuDrawError() {
        return gpuDrawError;
    }

    public static boolean occlusionBroken() {
        return occlusionBroken;
    }

    public static String occlusionError() {
        return occlusionError;
    }

    /**
     * Take rung 0 out of service for the session: a throw in the commit,
     * the loop or the phase-A recording, a mirror over budget, or an
     * invariant breach that repeats. One log line; rung 1 draws from here.
     */
    public static void latchGpuDraw(Throwable t) {
        latchGpuDraw(t.toString(), t);
    }

    public static void latchGpuDraw(String reason) {
        latchGpuDraw(reason, null);
    }

    private static void latchGpuDraw(String reason, Throwable t) {
        if (gpuDrawBroken) {
            return;
        }
        gpuDrawBroken = true;
        gpuDrawError = reason;
        rung = "1";
        MesheliumLog.LOGGER.error(
                "Meshelium's GPU-visibility draw of Sodium's terrain is standing down for the rest "
                        + "of this session ({}); the stage-1 list path draws every opaque pass from "
                        + "the next frame (first and only report). Nothing of Sodium's was consumed "
                        + "or freed.", reason, t);
    }

    /** A throw in the rasters or phase B: rung 0b (VisMode 0) from the next frame. */
    public static void latchOcclusion(Throwable t) {
        if (occlusionBroken) {
            return;
        }
        occlusionBroken = true;
        occlusionError = t.toString();
        MesheliumLog.LOGGER.error(
                "Meshelium's box-raster occlusion on the Sodium host is standing down for the rest "
                        + "of this session; the GPU draw continues in BFS-parity mode (VisMode 0) "
                        + "from the next frame (first and only report).", t);
    }

    // ---- counters ----

    /** "0a" / "0b" / "1" / "2": the rung the most recent frame was drawn by. */
    public static String rung() {
        return rung;
    }

    public static void reportRung(String value) {
        rung = value;
    }

    public static long framesOwned() {
        return framesOwned;
    }

    public static long framesAttempted() {
        return framesAttempted;
    }

    /** Frames drawn by rung 0a (rasters + temporal history) this session. */
    public static long occlusionFrames() {
        return occlusionFrames;
    }

    /** A rung-0 attempt began (the SOLID call reached the commit). */
    public static void reportAttempt() {
        framesAttempted++;
    }

    /** A per-frame decline by reason; the first of each reason is logged. */
    public static void reportDecline(String reason) {
        boolean first;
        synchronized (frameDeclines) {
            Long n = frameDeclines.get(reason);
            first = n == null;
            frameDeclines.put(reason, first ? 1L : n + 1L);
        }
        frameDeclinesTotal++;
        rung = "1";
        if ("ringGuard".equals(reason)) {
            SodiumFrameRing.countGuardTrip();
        }
        if (first) {
            MesheliumLog.LOGGER.info(
                    "Meshelium: a Sodium frame declined the GPU-visibility rung ({}); the list path "
                            + "drew it whole. Reported once per reason; the bench carries the counts.",
                    reason);
        }
    }

    /** reason to count, a copy. */
    public static Map<String, Long> frameDeclines() {
        synchronized (frameDeclines) {
            return new LinkedHashMap<>(frameDeclines);
        }
    }

    public static long frameDeclinesTotal() {
        return frameDeclinesTotal;
    }

    public static long frameDeclines(String reason) {
        synchronized (frameDeclines) {
            Long n = frameDeclines.get(reason);
            return n == null ? 0L : n;
        }
    }

    /** Reported by the CPU-side mirror after each commit. */
    public static void reportCommit(int regions, long bytes, long nanos, long deadRowsTotal,
            int live, int capacity, long released, int keys) {
        commitRegionsPerFrame = regions;
        commitRegionsTotal += regions;
        commitBytesPerFrame = bytes;
        commitNanos = nanos;
        deadRows = deadRowsTotal;
        midsLive = live;
        midsCapacity = capacity;
        midsReleased = released;
        bufferKeys = keys;
    }

    /** Reported by the CPU-side per-frame loop. */
    public static void reportFrameLoop(int listed, int sections, int groups, long nanos, boolean faceAll,
            float searchDistance, String searchSource) {
        listedRegions = listed;
        listedSections = sections;
        bufferGroups = groups;
        loopNanos = nanos;
        lastFaceAll = faceAll;
        searchDistanceBlocks = searchDistance;
        searchDistanceSource = searchSource;
    }

    /** Reported by the loop when an invariant check fails (the frame declines). */
    public static void reportInvariant(long midMissingTotal, long keyMismatchTotal) {
        midMissing = midMissingTotal;
        keyMismatch = keyMismatchTotal;
    }

    public static void reportGrowth() {
        growths++;
    }

    /** Reported by the audit lever. */
    public static void reportAudit(long frames, long mismatches, long gpuMismatches) {
        mirrorAuditFrames = frames;
        mirrorAuditMismatches = mismatches;
        mirrorGpuReadbackMismatches = gpuMismatches;
    }

    /**
     * Rung 1's per-pass "sections with at least one kept run" and quads, the
     * CPU twin of the GPU stats for the parity leg. The GPU stats
     * accumulate over BOTH opaque passes of a frame, so the frame sums are
     * kept too.
     *
     * @param passIndex 0 = SOLID, 1 = CUTOUT
     */
    public static void reportSectionsEmitted(int passIndex, int sections, long quads) {
        sectionsEmitted = sections;
        if (passIndex >= 0 && passIndex < 2) {
            sectionsEmittedByPass[passIndex] = sections;
            quadsKeptByPass[passIndex] = quads;
        }
    }

    public static int commitRegionsPerFrame() {
        return commitRegionsPerFrame;
    }

    public static long commitRegionsTotal() {
        return commitRegionsTotal;
    }

    public static long commitBytesPerFrame() {
        return commitBytesPerFrame;
    }

    public static long commitNanos() {
        return commitNanos;
    }

    public static long deadRows() {
        return deadRows;
    }

    public static int midsLive() {
        return midsLive;
    }

    public static int midsCapacity() {
        return midsCapacity;
    }

    public static long midsReleased() {
        return midsReleased;
    }

    public static long growths() {
        return growths;
    }

    public static int bufferKeys() {
        return bufferKeys;
    }

    public static int listedRegions() {
        return listedRegions;
    }

    public static int bufferGroups() {
        return bufferGroups;
    }

    public static int drawCommandsPerFrame() {
        return drawCommandsPerFrame;
    }

    public static long loopNanos() {
        return loopNanos;
    }

    /** GPU stats, lagged {@link SodiumMirrorGpu#READBACK_LAG} owned frames: VisMode-0 survivors. */
    public static int gpuSectionsMask() {
        return gpuSectionsMask;
    }

    public static int gpuSectionsA() {
        return gpuSectionsA;
    }

    public static int gpuSectionsB() {
        return gpuSectionsB;
    }

    public static long gpuQuads() {
        return gpuQuads;
    }

    public static long statsFramesRead() {
        return statsFramesRead;
    }

    /** Stats CBs recorded by the active instance (the frame index of the next readback). */
    public static long statsFrames() {
        InstanceState s = activeState;
        return s == null ? 0L : s.statsFrames;
    }

    /** Rung 1: sections with at least one kept run in the most recent pass. */
    public static int sectionsEmitted() {
        return sectionsEmitted;
    }

    /** Rung 1: SOLID + CUTOUT sections of the most recent frame (the GPU stats' unit). */
    public static int sectionsEmittedFrame() {
        return sectionsEmittedByPass[0] + sectionsEmittedByPass[1];
    }

    /** Rung 1: SOLID + CUTOUT quads kept of the most recent frame. */
    public static long quadsKeptFrame() {
        return quadsKeptByPass[0] + quadsKeptByPass[1];
    }

    public static long midMissing() {
        return midMissing;
    }

    public static long keyMismatch() {
        return keyMismatch;
    }

    public static boolean mirrorAuditArmed() {
        return MIRROR_AUDIT;
    }

    public static long mirrorAuditMismatches() {
        return mirrorAuditMismatches;
    }

    public static long mirrorGpuReadbackMismatches() {
        return mirrorGpuReadbackMismatches;
    }

    public static long mirrorAuditFrames() {
        return mirrorAuditFrames;
    }

    /** "invoker" / "fallback" / "none". */
    public static String searchDistanceSource() {
        return searchDistanceSource;
    }

    public static float searchDistanceBlocks() {
        return searchDistanceBlocks;
    }

    public static boolean faceAll() {
        return lastFaceAll;
    }

    public static boolean phaseBCpuSkipArmed() {
        return phaseBCpuSkipArmed;
    }

    public static long phaseBCpuSkips() {
        return phaseBCpuSkips;
    }

    public static int instancesLive() {
        return liveInstances.size();
    }

    public static long instancesRetired() {
        return instancesRetired;
    }

    public static long occlusionRecreates() {
        return occlusionRecreates;
    }

    /** "frame" after a rung-0 frame, "pass" after a rung-1 pass. */
    public static String gpuTimerUnit() {
        return gpuTimerUnit;
    }

    public static long ringGuardTrips() {
        return SodiumFrameRing.guardTrips();
    }

    /**
     * Phase-B section count of stats frame {@code f} on the active
     * instance, or -1 when that readback has not landed or was overwritten
     * (ring of {@value InstanceState#HISTORY}). The camera-cut leg's probe.
     */
    public static int gpuPhaseBAt(long f) {
        InstanceState s = activeState;
        if (s == null || f < 0L) {
            return -1;
        }
        int i = (int) (f % InstanceState.HISTORY);
        return s.phaseBFrames[i] == f ? s.phaseBCounts[i] : -1;
    }

    /**
     * NEXT (c): the highest stats frame whose readback has been folded in
     * on the active instance (-1 = none). The standalone had
     * {@code TerrainDrawer.lastReadStatsFrame()} already; this host had no
     * such getter, which is why the superset leg could not NAME the frame
     * its sample belonged to. Both hosts now share one idiom.
     */
    public static long lastReadStatsFrame() {
        InstanceState s = activeState;
        return s == null ? -1L : s.lastReadStatsFrame;
    }

    /**
     * NEXT (c), test-only: the most recently folded visible set, or null
     * when {@code meshelium.occlusion.diag.stampsReadback} is absent or
     * nothing has been folded yet. The index space is
     * {@code mid * 256 + Sodium slot}, valid to compare between two samples
     * only while that mapping is unchanged - which 97_18 asserts by counter
     * (upload hook, mids released, commit total, recreates, growths,
     * instances live) rather than assuming.
     */
    public static VisibleSetSample debugVisibleSet() {
        InstanceState s = activeState;
        return s == null || s.occlusion == null ? null : s.occlusion.debugVisibleSet();
    }

    /**
     * Phase-A section count of stats frame {@code f}: the same ring, tag
     * and index space as {@link #gpuPhaseBAt}. The forced-decline leg's
     * probe: the first owned frame after a decline must draw the last
     * owned frame's set in phase A (second stage-2/3 review, 2026-09-07).
     */
    public static int gpuSectionsAAt(long f) {
        InstanceState s = activeState;
        if (s == null || f < 0L) {
            return -1;
        }
        int i = (int) (f % InstanceState.HISTORY);
        return s.phaseBFrames[i] == f ? s.phaseACounts[i] : -1;
    }

    // ------------------------------------------------------------------
    // The instance state
    // ------------------------------------------------------------------

    /**
     * Rung-0 frame state: one per {@code MesheliumChunkRenderer} instance,
     * which is one per Sodium {@code RenderSectionManager}. The tables live
     * on the instance rather than in statics because a render-distance
     * change builds a new manager: Sodium destroys every region (our delete
     * hooks fire) and then the old renderer, which retires this behind
     * vanilla's deferred-destroy rotation; the new instance starts empty.
     */
    public static final class InstanceState {

        static final int HISTORY = 64;

        SodiumMirrorGpu mirror;
        SodiumFrameRing ring;
        /** Null until occlusion arms (rung 0a); recreated at the new capacity on growth. */
        TerrainOcclusion occlusion;

        /** Frames that reached the draw (the stamp/ring serial). */
        long ownedFrames;
        /** Stats CBs recorded: the readback frame index. */
        long statsFrames;
        int frameStamp32;
        long timerSerial;

        boolean frameOwned;
        boolean frameHasOcclusion;
        boolean frameSkipPhaseB;
        /**
         * NEXT (c): this frame's half-resolution mode - 0 full-res, 1 the
         * bias comparator, 2 FLAT. Decided by {@code armHalfRes} before any
         * pass opens, and read by passes D/2/3 and the phase-B skip key.
         */
        int frameHalfMode;
        /** The phase-B skip key's copy of it (contract section 7.4). */
        int pbHalfMode;
        /** PROPERTY_MERGE_PHASE_A: the SOLID call already recorded CUTOUT's phase A. */
        boolean frameCutoutPhaseARecorded;
        int frameFlags;
        int drawCommands;

        // remembered at the SOLID call for the CUTOUT call
        GpuBufferSlice sceneSlice;
        GpuBufferSlice projectionSlice;
        TerrainOcclusion.ListSlice listSlice;
        long prevStamps;
        long curStamps;
        GpuBuffer[] groupBuffers;
        int[] groupKeys;
        int[] groupStart;
        int groupCount;
        int listedRegions;

        // phase-B CPU skip key (contract section 7.4)
        boolean pbKeyValid;
        long pbPosX;
        long pbPosY;
        long pbPosZ;
        final float[] pbModelView = new float[16];
        final float[] pbProjection = new float[16];
        final float[] pbScratch = new float[16];
        int pbExtentW;
        int pbExtentH;
        long pbCommitSerial;
        long pbSignature;
        long pbLastOwned;
        long pbInputChangeStatsFrame;
        long lastReadStatsFrame = -1L;
        long lastPhaseBStatsFrame = -1L;
        final long[] phaseBFrames = new long[HISTORY];
        final int[] phaseBCounts = new int[HISTORY];
        /** Phase A of the same stats frames, tagged by {@code phaseBFrames}. */
        final int[] phaseACounts = new int[HISTORY];

        InstanceState() {
            Arrays.fill(phaseBFrames, -1L);
        }

        public SodiumMirrorGpu mirror() {
            return mirror;
        }

        public SodiumFrameRing ring() {
            return ring;
        }

        public long ownedFrames() {
            return ownedFrames;
        }

        /** Stats CBs this instance recorded: the index the audit's lagged block readback is keyed on. */
        public long statsFrames() {
            return statsFrames;
        }

        public boolean frameOwned() {
            return frameOwned;
        }

        public boolean frameHasOcclusion() {
            return frameHasOcclusion;
        }

        /** Drop the per-frame references (I4: no GpuBuffer outlives the CUTOUT call). */
        void clearFrame() {
            frameOwned = false;
            frameCutoutPhaseARecorded = false;
            frameHalfMode = 0;
            sceneSlice = null;
            projectionSlice = null;
            listSlice = null;
            groupBuffers = null;
            groupKeys = null;
            groupStart = null;
            groupCount = 0;
        }

        void destroyNow() {
            if (occlusion != null) {
                occlusion.destroyNow();
                occlusion = null;
            }
            if (ring != null) {
                ring.destroyNow();
                ring = null;
            }
            if (mirror != null) {
                mirror.destroyNow();
                mirror = null;
            }
        }
    }

    /** Registers for the device-close sweep; the CPU side keeps the reference and calls {@link #retire}. */
    public static InstanceState newInstanceState() {
        InstanceState s = new InstanceState();
        liveInstances.add(s);
        return s;
    }

    /** Everything onto vanilla's deferred-destroy rotation; drops from the registry. */
    public static void retire(InstanceState s) {
        if (s == null) {
            return;
        }
        liveInstances.remove(s);
        if (activeState == s) {
            activeState = null;
        }
        s.clearFrame();
        if (s.occlusion != null) {
            s.occlusion.destroy();
            s.occlusion = null;
        }
        if (s.ring != null) {
            s.ring.destroy();
            s.ring = null;
        }
        if (s.mirror != null) {
            s.mirror.destroy();
            s.mirror = null;
        }
        instancesRetired++;
    }

    /**
     * Create the mirror and the ring on first use. False while the device
     * facade is not up (the caller declines "deviceNotUp" and retries next
     * frame); a throw propagates and the caller latches.
     */
    public static boolean ensureResources(InstanceState s) {
        if (s.mirror == null) {
            s.mirror = SodiumMirrorGpu.create(initialCapacity());
            if (s.mirror == null) {
                return false;
            }
        }
        if (s.ring == null) {
            s.ring = SodiumFrameRing.create(s.mirror.capacity());
            if (s.ring == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Create the occlusion state (stamps + region stamps at the mirror's
     * capacity, no stats buffer) on the first occlusion frame. False when
     * it cannot exist this frame; a throw latches occlusion and returns
     * false, so the frame draws in VisMode 0.
     */
    public static boolean prepareOcclusion(InstanceState s) {
        if (s.occlusion != null) {
            return true;
        }
        if (s.mirror == null) {
            return false;
        }
        try {
            s.occlusion = TerrainOcclusion.create(s.mirror.capacity(), s.mirror.capacity(), true);
            if (s.occlusion != null) {
                occlusionRecreates++;
            }
            return s.occlusion != null;
        } catch (GpuDeviceLossException t) {
            throw t;
        } catch (Throwable t) {
            latchOcclusion(t);
            return false;
        }
    }

    /**
     * Grow the mirror to {@code newCapacity} and recreate the ring and the
     * occlusion state at the new size. False when the growth was refused
     * for want of VRAM, in which case the GPU draw is latched off (rung 1,
     * one log line). The caller declines the growth frame either way.
     */
    public static boolean growInstance(InstanceState s, int newCapacity) {
        SodiumMirrorGpu grown = s.mirror.grow(newCapacity);
        if (grown == null) {
            latchGpuDraw("mirror over budget at " + newCapacity + " ids");
            return false;
        }
        s.mirror = grown;
        if (s.ring != null) {
            s.ring.destroy();
        }
        s.ring = SodiumFrameRing.create(newCapacity);
        if (s.occlusion != null) {
            s.occlusion.destroy();
            s.occlusion = TerrainOcclusion.create(newCapacity, newCapacity, true);
            occlusionRecreates++;
        }
        growths++;
        return true;
    }

    // ------------------------------------------------------------------
    // The two owned-frame entry points
    // ------------------------------------------------------------------

    /**
     * The SOLID call of a rung-0 frame, after the CPU loop filled the ring
     * slot and the commit CB was executed. Records phase 1a (SOLID
     * pipeline, VisMode 1 with occlusion, VisMode 0 without) as one
     * indirect draw per geometry-buffer group, and remembers what the
     * CUTOUT call needs.
     *
     * @param sceneTail   {fracX, fracY, fracZ, searchDistance (blocks)}
     * @param cameraBlock {intX, intY, intZ}: CameraTransform.intX/Y/Z
     *                    verbatim, the facing formula's exact inputs
     * @param frameSignature a 64-bit fold of this frame's mids and counts
     *                    in ring order (the phase-B skip key)
     * @return false = declined (state untouched, the caller falls to rung 1)
     */
    public static boolean drawSolidOwned(InstanceState s,
            GpuTextureView colorView, GpuTextureView depthView, GpuTextureView atlasView,
            GpuSampler atlasSampler, GpuTextureView lightmapView,
            Matrix4fc modelView, Matrix4fc projection,
            double camX, double camY, double camZ, float[] sceneTail, int[] cameraBlock,
            GpuBuffer[] groupBuffers, int[] groupKeys, int[] groupStart, int groupCount,
            int listedRegions, long frameSignature,
            boolean faceAll, boolean distanceGate, boolean occlusionThisFrame, boolean noDiscard) {
        if (colorView == null || depthView == null || atlasView == null || lightmapView == null
                || atlasSampler == null || modelView == null || projection == null
                || s.mirror == null || s.ring == null) {
            return false;
        }
        boolean occ = occlusionThisFrame && s.occlusion != null && !occlusionBroken;
        // NEXT (c): a DECLINED frame never reaches clearFrame (only the
        // CUTOUT call's finally does), so the half mode is reset here as
        // well - a stale mode would open passes 2/3 on attachments this
        // frame never armed.
        s.frameHalfMode = 0;
        if (occ) {
            // The raster pipelines are TerrainOcclusion's: a failure to
            // build them is the occlusion latch (rung 0b from here), not
            // the GPU-draw latch, and it is decided before any pass opens
            // so the frame's VisMode is settled before the first draw.
            try {
                s.occlusion.ensureSodiumPipelines(VulkanConst.toVk(colorView.texture().getFormat()),
                        VulkanConst.toVk(depthView.texture().getFormat()));
                // NEXT (c): the half-resolution arm, and it is the FIRST
                // encoder-touching statement of the owned frame on purpose.
                // It may create the half attachment pair, whose
                // UNDEFINED-to-GENERAL layout barrier must not land inside a
                // rendering instance - and nothing at the Java level would
                // refuse that (see OcclusionHalfResTarget). This block
                // precedes createCommandEncoder, pullStats, the transient
                // uploads, the skip decision and beginOpaque, and the
                // previous owned frame's passes all closed before
                // drawSolidOwned returned.
                //
                // TWO matrices, two jobs (NEXT (c1), 2026-09-16). This host
                // used to say "one matrix, one source"; that is no longer
                // true and the sentence was load-bearing, so it is
                // replaced rather than trimmed.
                //
                // `projection` is what uploadProjection uploads and the
                // rasters bind at binding 2, and it is vanilla's
                // bob-multiplied level projection bit for bit: Sodium's
                // GameRendererMixin wraps ProjectionMatrixBuffer.getBuffer
                // (Matrix4f) inside renderLevel and copies the ARGUMENT,
                // and renderLevel's only call to that overload is the one
                // at ip 297 with the bob-multiplied copy (javap on
                // sodium-fabric-0.9.2-beta.1+mc26.2.jar). So k and NearR
                // cannot be derived from it - the bob's 0.1-block
                // translate alone puts 0.143 in front of a 1.0e-5
                // tolerance and refused essentially every walking frame.
                //
                // They are derived from the PRE-BOB stash instead, which
                // this host has no other way to reach: it holds no
                // CameraRenderState at all. `modelView` is the second
                // parameter of Sodium's ChunkRenderMatrices, which
                // LevelRendererMixin.getRenderState builds from the very
                // Matrix4fc that LevelRenderer.render ip 356-367 passes to
                // prepareChunkRenders - i.e.
                // cameraRenderState.viewRotationMatrix, a pure rotation.
                long preBobSerial = MesheliumProjectionCapture.preBobSerial();
                s.frameHalfMode = s.occlusion.armHalfRes(colorView, depthView, projection,
                        MesheliumProjectionCapture.preBob(), modelView, preBobSerial,
                        VulkanConst.toVk(colorView.texture().getFormat()),
                        VulkanConst.toVk(depthView.texture().getFormat()), true, true);
            } catch (GpuDeviceLossException t) {
                throw t;
            } catch (Throwable t) {
                latchOcclusion(t);
                occ = false;
                s.frameHalfMode = 0;
            }
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        // The lagged stats before this frame records (the readback frame
        // is statsFrames - READBACK_LAG, complete by the 2-submits-in-flight
        // argument).
        pullStats(s);

        // The stamp: per owned frame, session-monotonic.
        long stamp = ++stampSerial;
        int frameStamp32 = (int) stamp;
        if (frameStamp32 == 0 || frameStamp32 == 1) {
            // A 32-bit wrap could make FrameStamp-1 equal the zero fill;
            // skip the two values the zero fill can pass for.
            stampSerial += 2L;
            frameStamp32 = (int) stampSerial;
        }
        long ownedFrame = s.ownedFrames + 1L;

        // Transient uploads before any pass opens.
        GpuBufferSlice sceneSlice = uploadSceneTask(encoder, modelView, projection,
                atlasView.getWidth(0), atlasView.getHeight(0), sceneTail, cameraBlock);
        GpuBufferSlice projectionSlice = uploadProjection(encoder, projection);

        int flags = SodiumGpuVisibilityLayout.FLAG_STATS
                | (faceAll ? SodiumGpuVisibilityLayout.FLAG_FACE_ALL : 0)
                | (occ && distanceGate ? SodiumGpuVisibilityLayout.FLAG_DIST_GATE : 0)
                | (ONE_DRAW ? SodiumGpuVisibilityLayout.FLAG_ONE_DRAW : 0)
                | DIAG_TASK_SKIP_FLAGS;
        int visMode = occ ? SodiumGpuVisibilityLayout.VIS_MODE_PHASE_A
                : SodiumGpuVisibilityLayout.VIS_MODE_BFS;
        long prevStamps = occ ? s.occlusion.prevStampsBuffer(stamp) : s.mirror.vkBuffer();
        long curStamps = occ ? s.occlusion.curStampsBuffer(stamp) : s.mirror.vkBuffer();

        // The phase-B skip decision needs the whole key before the passes
        // record; consumed at the CUTOUT call.
        boolean skipPhaseB = occ && phaseBSkipDecide(s, camX, camY, camZ, modelView, projection,
                depthView.texture().getWidth(0), depthView.texture().getHeight(0),
                s.mirror.commitSerial(), frameSignature, ownedFrame);

        s.sceneSlice = sceneSlice;
        s.projectionSlice = projectionSlice;
        s.listSlice = new TerrainOcclusion.ListSlice(s.ring.listBuffer(), s.ring.listOffset(),
                s.ring.listRange(), true);
        s.prevStamps = prevStamps;
        s.curStamps = curStamps;
        s.groupBuffers = groupBuffers;
        s.groupKeys = groupKeys;
        s.groupStart = groupStart;
        s.groupCount = groupCount;
        s.listedRegions = listedRegions;
        s.frameStamp32 = frameStamp32;
        s.frameFlags = flags;
        s.frameHasOcclusion = occ;
        s.frameSkipPhaseB = skipPhaseB;
        s.drawCommands = 0;

        // GPU timestamps bracket the FRAME on this rung (both passes and,
        // with occlusion, the rasters and phase B), unlike rung 1's per-pass
        // bracket: the report carries gpuTimerUnit so the two are never
        // read as the same figure.
        s.timerSerial = ++timerPasses;
        MesheliumGpuTimers.beginOpaque(encoder, s.timerSerial);

        try (RenderPass pass = encoder.createRenderPass(() -> "meshelium sodium terrain 1a",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            VulkanRenderPass backendPass = (VulkanRenderPass) ((RenderPassAccessor) pass).meshelium$backend();
            VulkanRenderPassAccessor vkPass = (VulkanRenderPassAccessor) backendPass;
            VkCommandBuffer cb = vkPass.meshelium$commandBuffer();
            TerrainDrawPipeline p = pipelineTaskFor(vkPass, colorView, depthView, noDiscard);
            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipeline());
            if (noDiscard) {
                noDiscardPasses++;
            } else {
                discardPasses++;
            }
            s.drawCommands += recordGroups(cb, p, s, visMode,
                    SodiumGpuVisibilityLayout.recordBaseUvec4(s.mirror.capacity(),
                            SodiumGpuVisibilityLayout.PASS_SOLID),
                    atlasView, atlasSampler, lightmapView);
            if (MERGE_PHASE_A) {
                // PROPERTY_MERGE_PHASE_A: pass 1b folded into 1a. The
                // CUTOUT pipeline (its fragment stage discards) over the
                // CUTOUT record table, same views, same stamps and
                // VisMode; the CUTOUT call then skips its own phase A.
                TerrainDrawPipeline cutoutPipeline = pipelineTaskFor(vkPass, colorView, depthView, false);
                VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, cutoutPipeline.pipeline());
                discardPasses++;
                s.drawCommands += recordGroups(cb, cutoutPipeline, s, visMode,
                        SodiumGpuVisibilityLayout.recordBaseUvec4(s.mirror.capacity(),
                                SodiumGpuVisibilityLayout.PASS_CUTOUT),
                        atlasView, atlasSampler, lightmapView);
                s.frameCutoutPhaseARecorded = true;
            }
        }
        // The end of pass 1a, written before this call returns: the
        // difference between PASS_1A and PASS_OPAQUE_A (marked at the
        // CUTOUT call) is what the host records between the two terrain
        // layers, which 0r's "phase A" figure could not separate.
        MesheliumGpuTimers.mark(encoder, MesheliumGpuTimers.POINT_AFTER_PASS_1A);
        s.frameOwned = true;
        s.ownedFrames = ownedFrame;
        framesOwned++;
        framesDrawn++;
        // The bench's readiness wait and its runsPerPass read the list
        // path's counters; an owned frame accounts the masked sections it
        // handed the GPU so rung 0 is measurable by the same harness (the
        // GPU's own counts arrive lagged in gpuSectionsMask/A/B). Found
        // when every GPU-rung bench cell timed out on totalSectionsDrawn.
        lastSectionsDrawn = listedSections;
        lastRegionsDrawn = listedRegions;
        totalSectionsDrawn += listedSections;
        if (occ) {
            occlusionFrames++;
        }
        rung = occ ? "0a" : "0b";
        gpuTimerUnit = "frame";
        // No list was built on this rung: the enumeration figure reads 0,
        // never rung 1's stale value (the 97_00 leg asserts exactly this).
        lastBuildNanos = 0L;
        activeState = s;
        if (!gpuDrawLogged) {
            gpuDrawLogged = true;
            MesheliumLog.LOGGER.info(
                    "Meshelium is drawing Sodium's opaque terrain from the GPU record mirror: the "
                            + "task stage selects runs per section ({}), one indirect draw per "
                            + "geometry buffer per phase per pass, CPU work O(regions).",
                    occ ? "box-raster occlusion with temporal history" : "BFS-parity mode");
        }
        return true;
    }

    /**
     * The CUTOUT call of an owned frame: phase 1b, then with occlusion the
     * region raster, the section raster and phase B (SOLID then CUTOUT
     * pipelines in ONE pass), then the stats CB and the timer close. The
     * rasters run after BOTH phase-A draws so cutout geometry (leaves, the
     * canopy) primes the occluder depth (design section 5.2, 0n).
     *
     * @return false when phase 1b could not be recorded (latched inside;
     *         the caller draws this pass by rung 1). A throw in the rasters
     *         or phase B latches occlusion, costs this frame its phase B
     *         (one frame of late reveal) and still returns true: CUTOUT was
     *         drawn.
     */
    public static boolean drawCutoutOwned(InstanceState s, GpuTextureView colorView,
            GpuTextureView depthView, GpuTextureView atlasView, GpuSampler atlasSampler,
            GpuTextureView lightmapView, boolean noDiscardSolid) {
        if (!s.frameOwned || s.mirror == null || s.ring == null) {
            return false;
        }
        try {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            boolean occ = s.frameHasOcclusion;
            int visMode = occ ? SodiumGpuVisibilityLayout.VIS_MODE_PHASE_A
                    : SodiumGpuVisibilityLayout.VIS_MODE_BFS;
            int recordBaseSolid = SodiumGpuVisibilityLayout.recordBaseUvec4(s.mirror.capacity(),
                    SodiumGpuVisibilityLayout.PASS_SOLID);
            int recordBaseCutout = SodiumGpuVisibilityLayout.recordBaseUvec4(s.mirror.capacity(),
                    SodiumGpuVisibilityLayout.PASS_CUTOUT);

            // ---- pass 1b: CUTOUT phase A (the discarding pipeline; CUTOUT
            // declares fragment discard) - unless the SOLID call already
            // recorded it in pass 1a (PROPERTY_MERGE_PHASE_A) ----
            try {
                if (!s.frameCutoutPhaseARecorded) {
                    try (RenderPass pass = encoder.createRenderPass(() -> "meshelium sodium terrain 1b",
                            colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
                        VulkanRenderPass backendPass = (VulkanRenderPass) ((RenderPassAccessor) pass).meshelium$backend();
                        VulkanRenderPassAccessor vkPass = (VulkanRenderPassAccessor) backendPass;
                        VkCommandBuffer cb = vkPass.meshelium$commandBuffer();
                        TerrainDrawPipeline p = pipelineTaskFor(vkPass, colorView, depthView, false);
                        VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipeline());
                        discardPasses++;
                        s.drawCommands += recordGroups(cb, p, s, visMode, recordBaseCutout,
                                atlasView, atlasSampler, lightmapView);
                    }
                }
            } catch (GpuDeviceLossException t) {
                throw t;
            } catch (Throwable t) {
                latchGpuDraw(t);
                MesheliumGpuTimers.endFrame(s.timerSerial);
                return false;
            }
            MesheliumGpuTimers.mark(encoder, MesheliumGpuTimers.POINT_AFTER_PHASE_A);
            framesDrawn++;
            // The bench's readiness wait and its runsPerPass read the list
            // path's counters; an owned frame accounts the masked sections it
            // handed the GPU so rung 0 is measurable by the same harness (the
            // GPU's own counts arrive lagged in gpuSectionsMask/A/B). Found
            // when every GPU-rung bench cell timed out on totalSectionsDrawn.
            lastSectionsDrawn = listedSections;
            lastRegionsDrawn = listedRegions;
            totalSectionsDrawn += listedSections;

            if (occ) {
                try {
                    // ---- NEXT (c): pass D, then passes 2-3 on the half-res
                    // views. The box rasters are pixel-bound (0s: 0.133 ms at
                    // 1080p against 0.211 at 1440p for the same scene), so a
                    // quarter-area depth target is the lever; this is where
                    // the frame either makes one or keeps the main views.
                    // Phase A (passes 1a/1b) and phase B (pass 4) stay on the
                    // MAIN views always: only the BOX rasters move. ----
                    final int hm = s.frameHalfMode;
                    GpuTextureView rc = colorView;
                    GpuTextureView rd = depthView;
                    if (hm != 0) {
                        rc = s.occlusion.halfColorView();
                        rd = s.occlusion.halfDepthView();
                        // Colour loadOp LOAD (never written, mask 0
                        // everywhere); depth CLEAR to 0.0 = reversed-Z FAR,
                        // so a half texel the downsample somehow never wrote
                        // passes every box - fail-open, a belt under the
                        // full-screen triangle's braces.
                        final GpuTextureView passColor = rc;
                        final GpuTextureView passDepth = rd;
                        try (RenderPass pass = encoder.createRenderPass(
                                () -> "meshelium occlusion depth downsample",
                                passColor, Optional.empty(), passDepth, OptionalDouble.of(0.0))) {
                            VulkanRenderPass backendPass =
                                    (VulkanRenderPass) ((RenderPassAccessor) pass).meshelium$backend();
                            VkCommandBuffer cb =
                                    ((VulkanRenderPassAccessor) backendPass).meshelium$commandBuffer();
                            s.occlusion.recordDepthDownsample(cb, depthView);
                        }
                        MesheliumGpuTimers.mark(encoder, MesheliumGpuTimers.POINT_AFTER_DOWNSAMPLE);
                    }
                    final GpuTextureView rasterColor = rc;
                    final GpuTextureView rasterDepth = rd;

                    // ---- pass 2: region boxes ----
                    try (RenderPass pass = encoder.createRenderPass(() -> "meshelium sodium occlusion regions",
                            rasterColor, Optional.empty(), rasterDepth, OptionalDouble.empty())) {
                        VulkanRenderPass backendPass = (VulkanRenderPass) ((RenderPassAccessor) pass).meshelium$backend();
                        VkCommandBuffer cb = ((VulkanRenderPassAccessor) backendPass).meshelium$commandBuffer();
                        s.occlusion.recordRegionRasterSodium(cb, s.listSlice, s.sceneSlice,
                                s.projectionSlice, s.listedRegions, s.frameStamp32, hm);
                    }
                    MesheliumGpuTimers.mark(encoder, MesheliumGpuTimers.POINT_AFTER_REGION_RASTER);

                    // ---- pass 3: section boxes (writes curStamps) ----
                    try (RenderPass pass = encoder.createRenderPass(() -> "meshelium sodium occlusion sections",
                            rasterColor, Optional.empty(), rasterDepth, OptionalDouble.empty())) {
                        VulkanRenderPass backendPass = (VulkanRenderPass) ((RenderPassAccessor) pass).meshelium$backend();
                        VkCommandBuffer cb = ((VulkanRenderPassAccessor) backendPass).meshelium$commandBuffer();
                        s.occlusion.recordSectionRasterSodium(cb, s.listSlice, s.sceneSlice,
                                s.projectionSlice, s.mirror.vkBuffer(), s.mirror.rowsOffset(),
                                s.mirror.rowsRange(), s.listedRegions, s.frameStamp32, s.curStamps,
                                hm);
                    }
                    MesheliumGpuTimers.mark(encoder, MesheliumGpuTimers.POINT_AFTER_SECTION_RASTER);

                    // Measurement only (DIAG_EMPTY_PASSES): N passes that
                    // draw nothing, so the phase-B segment prices a pass.
                    for (int i = 0; i < DIAG_EMPTY_PASSES; i++) {
                        try (RenderPass pass = encoder.createRenderPass(() -> "meshelium diag empty pass",
                                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
                            // Vanilla begins and ends the pass; nothing is recorded inside.
                            ((RenderPassAccessor) pass).meshelium$backend();
                        }
                    }

                    // ---- pass 4: phase B, SOLID then CUTOUT, one pass ----
                    // The CPU skip elides the recording only when the pass
                    // provably draws zero sections (contract section 7.4);
                    // passes 1-3, the stamps and the stats CB ran regardless.
                    if (!s.frameSkipPhaseB) {
                        try (RenderPass pass = encoder.createRenderPass(() -> "meshelium sodium terrain phase B",
                                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
                            VulkanRenderPass backendPass = (VulkanRenderPass) ((RenderPassAccessor) pass).meshelium$backend();
                            VulkanRenderPassAccessor vkPass = (VulkanRenderPassAccessor) backendPass;
                            VkCommandBuffer cb = vkPass.meshelium$commandBuffer();
                            TerrainDrawPipeline solid = pipelineTaskFor(vkPass, colorView, depthView, noDiscardSolid);
                            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, solid.pipeline());
                            // Push-descriptor state does not survive the
                            // foreign layouts of passes 2/3: recordGroups
                            // pushes per group anyway.
                            s.drawCommands += recordGroups(cb, solid, s,
                                    SodiumGpuVisibilityLayout.VIS_MODE_PHASE_B, recordBaseSolid,
                                    atlasView, atlasSampler, lightmapView);
                            TerrainDrawPipeline cutout = pipelineTaskFor(vkPass, colorView, depthView, false);
                            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, cutout.pipeline());
                            s.drawCommands += recordGroups(cb, cutout, s,
                                    SodiumGpuVisibilityLayout.VIS_MODE_PHASE_B, recordBaseCutout,
                                    atlasView, atlasSampler, lightmapView);
                        }
                    } else {
                        phaseBCpuSkips++;
                    }
                    MesheliumGpuTimers.mark(encoder, MesheliumGpuTimers.POINT_AFTER_PHASE_B);
                } catch (GpuDeviceLossException t) {
                    throw t;
                } catch (Throwable t) {
                    latchOcclusion(t);
                }
            }

            // ---- stats copy + zero (the transfer CB) ----
            s.mirror.recordStatsTransfer(s.statsFrames);
            // NEXT (c), test-only: the visible-set copy rides beside it. A
            // no-op unless -Dmeshelium.occlusion.diag.stampsReadback is set;
            // recorded here, after phase B, where the pass-end barrier has
            // already made the rasters' stamp writes visible and phase B has
            // only READ them.
            if (occ) {
                s.occlusion.recordStampsTransfer(s.statsFrames, s.curStamps, s.frameStamp32);
            }
            s.statsFrames++;
            MesheliumGpuTimers.endFrame(s.timerSerial);
            drawCommandsPerFrame = s.drawCommands;
            return true;
        } finally {
            s.clearFrame();
        }
    }

    /** One indirect draw per buffer group, binding 0 re-pushed per group. */
    private static int recordGroups(VkCommandBuffer cb, TerrainDrawPipeline p, InstanceState s,
            int visMode, int recordBase, GpuTextureView atlasView, GpuSampler atlasSampler,
            GpuTextureView lightmapView) {
        int commands = 0;
        int stages = EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT | EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.calloc(TerrainDrawPipeline.PUSH_BYTES);
            for (int g = 0; g < s.groupCount; g++) {
                int first = s.groupStart[g];
                int count = s.groupStart[g + 1] - first;
                if (count <= 0 || s.groupBuffers[g] == null) {
                    continue;
                }
                pushDescriptorsTask(cb, p, s, s.groupBuffers[g], atlasView, atlasSampler, lightmapView);
                push.putInt(SodiumGpuVisibilityLayout.PUSH_FIRST_SLOT, first);
                push.putInt(SodiumGpuVisibilityLayout.PUSH_GROUP_KEY, s.groupKeys[g]);
                push.putInt(SodiumGpuVisibilityLayout.PUSH_VIS_MODE, visMode);
                push.putInt(SodiumGpuVisibilityLayout.PUSH_FRAME_STAMP, s.frameStamp32);
                push.putInt(SodiumGpuVisibilityLayout.PUSH_FLAGS, s.frameFlags);
                push.putInt(SodiumGpuVisibilityLayout.PUSH_RECORD_BASE, recordBase);
                push.putInt(SodiumGpuVisibilityLayout.PUSH_REGION_COUNT, ONE_DRAW ? count : 0);
                push.putInt(SodiumGpuVisibilityLayout.PUSH_RESERVED1, 0);
                VK10.vkCmdPushConstants(cb, p.pipelineLayout(), stages, 0, push);
                if (ONE_DRAW) {
                    // PROPERTY_ONE_DRAW: the group's single command, every
                    // task workgroup of every listed region in it.
                    EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cb, s.ring.indirectBuffer(),
                            s.ring.indirectGroupOffset() + (long) g * SodiumGpuVisibilityLayout.INDIRECT_BYTES,
                            1, SodiumGpuVisibilityLayout.INDIRECT_BYTES);
                } else {
                    EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cb, s.ring.indirectBuffer(),
                            s.ring.indirectOffset() + (long) first * SodiumGpuVisibilityLayout.INDIRECT_BYTES,
                            count, SodiumGpuVisibilityLayout.INDIRECT_BYTES);
                }
                commands++;
            }
        }
        return commands;
    }

    /**
     * The eleven bindings of the task-stage variant (contract section 5.1):
     * the seven of the stage-1 variant plus the mirror (7), the list slot
     * (8) and the prev/cur stamps (9/10; the mirror as a type-correct dummy
     * when occlusion is off, never dynamically accessed in VisMode 0).
     */
    private static void pushDescriptorsTask(VkCommandBuffer cb, TerrainDrawPipeline p, InstanceState s,
            GpuBuffer geometry, GpuTextureView atlasView, GpuSampler atlasSampler,
            GpuTextureView lightmapView) {
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
        GpuSampler lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(11, stack);
            bufferWrite(writes.get(0), SodiumGpuVisibilityLayout.B_GEOMETRY,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, ((VulkanGpuBuffer) geometry).vkBuffer(), 0, VK10.VK_WHOLE_SIZE));
            bufferWrite(writes.get(1), SodiumGpuVisibilityLayout.B_SCENE,
                    VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    bufferInfo(stack, ((VulkanGpuBuffer) s.sceneSlice.buffer()).vkBuffer(),
                            s.sceneSlice.offset(), s.sceneSlice.length()));
            bufferWrite(writes.get(2), SodiumGpuVisibilityLayout.B_PROJECTION,
                    VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    bufferInfo(stack, ((VulkanGpuBuffer) s.projectionSlice.buffer()).vkBuffer(),
                            s.projectionSlice.offset(), s.projectionSlice.length()));
            bufferWrite(writes.get(3), SodiumGpuVisibilityLayout.B_FOG,
                    VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    bufferInfo(stack, ((VulkanGpuBuffer) fog.buffer()).vkBuffer(), fog.offset(), fog.length()));
            bufferWrite(writes.get(4), SodiumGpuVisibilityLayout.B_GLOBALS,
                    VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                    bufferInfo(stack, ((VulkanGpuBuffer) globals).vkBuffer(), 0, VK10.VK_WHOLE_SIZE));
            imageWrite(writes.get(5), SodiumGpuVisibilityLayout.B_ATLAS, imageInfo(stack, atlasView, atlasSampler));
            imageWrite(writes.get(6), SodiumGpuVisibilityLayout.B_LIGHTMAP,
                    imageInfo(stack, lightmapView, lightmapSampler));
            bufferWrite(writes.get(7), SodiumGpuVisibilityLayout.B_MIRROR,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, s.mirror.vkBuffer(), 0, VK10.VK_WHOLE_SIZE));
            bufferWrite(writes.get(8), SodiumGpuVisibilityLayout.B_LIST,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, s.ring.listBuffer(), s.ring.listOffset(), s.ring.listRange()));
            bufferWrite(writes.get(9), SodiumGpuVisibilityLayout.B_PREV_STAMPS,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, s.prevStamps, 0, VK10.VK_WHOLE_SIZE));
            bufferWrite(writes.get(10), SodiumGpuVisibilityLayout.B_CUR_STAMPS,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    bufferInfo(stack, s.curStamps, 0, VK10.VK_WHOLE_SIZE));
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb,
                    VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipelineLayout(), 0, writes);
        }
    }

    /** The task-stage pipeline pair, created lazily like {@link #pipelineFor}. */
    private static TerrainDrawPipeline pipelineTaskFor(VulkanRenderPassAccessor vkPass,
            GpuTextureView colorView, GpuTextureView depthView, boolean noDiscard) {
        TerrainDrawPipeline p = noDiscard ? pipelineTaskNoDiscard : pipelineTask;
        if (p == null) {
            int vkColorFormat = VulkanConst.toVk(colorView.texture().getFormat());
            int vkDepthFormat = VulkanConst.toVk(depthView.texture().getFormat());
            int wg = TerrainDrawer.meshWorkgroupQuads();
            int taskWg = TerrainDrawer.taskWorkgroupSections();
            MesheliumVulkanState.MeshShaderCaps caps = MesheliumVulkanState.caps();
            if (caps == null) {
                throw new IllegalStateException(
                        "no mesh-shader caps: the Vulkan device was created without "
                                + "VK_EXT_mesh_shader, so this path should never have armed");
            }
            if (caps.maxMeshWorkGroupInvocations() < wg
                    || caps.maxTaskWorkGroupInvocations() < taskWg
                    || caps.maxMeshOutputVertices() < wg * 4
                    || caps.maxMeshOutputPrimitives() < wg * 2
                    || (caps.maxMeshOutputMemorySize() > 0
                            && caps.maxMeshOutputMemorySize()
                                    < wg * TerrainDrawer.MESH_OUTPUT_BYTES_PER_QUAD)) {
                throw new IllegalStateException("device mesh caps below the Sodium task draw shape ("
                        + wg + " quads/workgroup, " + taskWg + " sections/task workgroup): " + caps);
            }
            p = TerrainDrawPipeline.createSodiumTask(vkPass.meshelium$device().vkDevice(),
                    vkColorFormat, vkDepthFormat, wg, taskWg, noDiscard);
            if (noDiscard) {
                pipelineTaskNoDiscard = p;
            } else {
                pipelineTask = p;
            }
            MesheliumLog.LOGGER.info(
                    "Meshelium's Sodium GPU-visibility pipeline created (color format {}, depth "
                            + "format {}, {} quads/workgroup, {} sections/task workgroup, fragment={}).",
                    vkColorFormat, vkDepthFormat, wg, taskWg, noDiscard ? "no-discard" : "discard");
        }
        return p;
    }

    /**
     * The 240-byte scene UBO of the task-stage variant: bytes 0-207 as the
     * stage-1 upload plus the six frustum planes (from Sodium's
     * projection * modelView, the standalone's JOML formulas), the camera
     * chunk, then {@code vec4 {fracX, fracY, fracZ, D}} at 208 and
     * {@code ivec4 {intX, intY, intZ, 0}} at 224 (contract section 4.3).
     * Rung 1 keeps writing 208 bytes, unchanged.
     */
    private static GpuBufferSlice uploadSceneTask(CommandEncoder encoder, Matrix4fc modelView,
            Matrix4fc projection, int atlasWidth, int atlasHeight, float[] tail, int[] block) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer scene = stack.calloc(SodiumGpuVisibilityLayout.SCENE_BYTES_SODIUM);
            modelView.get(scene); // 64 bytes at position 0, position unchanged
            scene.putFloat(64, (float) atlasWidth);
            scene.putFloat(68, (float) atlasHeight);

            putFrustumPlanes(scene, modelView, projection);

            scene.putInt(176, block[0] >> 4);
            scene.putInt(180, block[1] >> 4);
            scene.putInt(184, block[2] >> 4);

            float subPixelDist = MesheliumConfig.subPixelCullChunks() * 16.0f;
            scene.putFloat(192, TerrainDrawer.CULL_OFF_DIST2);
            scene.putFloat(196, subPixelDist <= 0.0f ? TerrainDrawer.CULL_OFF_DIST2
                    : subPixelDist * subPixelDist);
            com.mojang.blaze3d.pipeline.RenderTarget mainTarget =
                    Minecraft.getInstance().gameRenderer.mainRenderTarget();
            scene.putFloat(200, (float) mainTarget.width);
            scene.putFloat(204, (float) mainTarget.height);

            scene.putFloat(SodiumGpuVisibilityLayout.SCENE_CAMERA_FRAC, tail[0]);
            scene.putFloat(SodiumGpuVisibilityLayout.SCENE_CAMERA_FRAC + 4, tail[1]);
            scene.putFloat(SodiumGpuVisibilityLayout.SCENE_CAMERA_FRAC + 8, tail[2]);
            scene.putFloat(SodiumGpuVisibilityLayout.SCENE_CAMERA_FRAC + 12, tail[3]);
            scene.putInt(SodiumGpuVisibilityLayout.SCENE_CAMERA_BLOCK, block[0]);
            scene.putInt(SodiumGpuVisibilityLayout.SCENE_CAMERA_BLOCK + 4, block[1]);
            scene.putInt(SodiumGpuVisibilityLayout.SCENE_CAMERA_BLOCK + 8, block[2]);
            scene.putInt(SodiumGpuVisibilityLayout.SCENE_CAMERA_BLOCK + 12, 0);

            return encoder.transientMemory().uploadGpu(scene, 256, GpuBuffer.USAGE_UNIFORM);
        }
    }

    /**
     * The six Gribb-Hartmann rows of projection * modelView at bytes
     * 80..175 of the scene UBO (FrustumPlanes[6]): the standalone's JOML
     * formulas, camera-relative space, unnormalized (the p-vertex test is
     * scale-invariant). Shared by the rung-1 and the task-stage uploads;
     * the list builder's CPU test derives the same rows from the same
     * matrices (MesheliumSodiumDrawList.setPlanes).
     */
    private static void putFrustumPlanes(ByteBuffer scene, Matrix4fc modelView, Matrix4fc projection) {
        Matrix4f m = mvpScratch.set(projection).mul(modelView);
        putPlane(scene, 80, m.m03() + m.m00(), m.m13() + m.m10(), m.m23() + m.m20(), m.m33() + m.m30());
        putPlane(scene, 96, m.m03() - m.m00(), m.m13() - m.m10(), m.m23() - m.m20(), m.m33() - m.m30());
        putPlane(scene, 112, m.m03() + m.m01(), m.m13() + m.m11(), m.m23() + m.m21(), m.m33() + m.m31());
        putPlane(scene, 128, m.m03() - m.m01(), m.m13() - m.m11(), m.m23() - m.m21(), m.m33() - m.m31());
        putPlane(scene, 144, m.m03() + m.m02(), m.m13() + m.m12(), m.m23() + m.m22(), m.m33() + m.m32());
        putPlane(scene, 160, m.m03() - m.m02(), m.m13() - m.m12(), m.m23() - m.m22(), m.m33() - m.m32());
    }

    private static void putPlane(ByteBuffer dst, int offset, float a, float b, float c, float d) {
        dst.putFloat(offset, a);
        dst.putFloat(offset + 4, b);
        dst.putFloat(offset + 8, c);
        dst.putFloat(offset + 12, d);
    }

    /**
     * Fold the lagged stats slot into the counters. The slot read is
     * {@code statsFrames - READBACK_LAG}, complete by the 2-submits-in-flight
     * argument; each stats frame is folded once.
     */
    private static void pullStats(InstanceState s) {
        long readFrame = s.statsFrames - SodiumMirrorGpu.READBACK_LAG;
        if (readFrame < 0L || readFrame <= s.lastReadStatsFrame) {
            return;
        }
        int[] st = s.mirror.readStats(readFrame);
        if (st == null) {
            return;
        }
        gpuSectionsMask = st[0];
        gpuSectionsA = st[1];
        gpuSectionsB = st[2];
        gpuQuads = st[3] & 0xFFFFFFFFL;
        statsFramesRead++;
        if (st[2] > 0) {
            s.lastPhaseBStatsFrame = readFrame;
        }
        int i = (int) (readFrame % InstanceState.HISTORY);
        s.phaseBFrames[i] = readFrame;
        s.phaseBCounts[i] = st[2];
        s.phaseACounts[i] = st[1];
        // NEXT (c), test-only: fold the visible set of the SAME frame, on
        // THIS thread. A suite thread cannot read the ring slot safely - it
        // is retagged when frame f+8 records, and the leg reaches it through
        // a poll loop and a runOnClient round trip while the client renders
        // hundreds of owned frames a second. A no-op unless the readback
        // property armed the ring.
        if (s.occlusion != null) {
            s.occlusion.foldVisibleSet(readFrame);
        }
        s.lastReadStatsFrame = readFrame;
    }

    /**
     * The phase-B CPU skip, ported from {@code TerrainDrawer.phaseBCpuSkipDecide}
     * with the key re-based on this host's inputs (contract section 7.4):
     * the camera's raw bits, both matrices, the raster extent, the
     * mirror's commit serial (bumped by every commit that copied a byte),
     * the frame's list signature and a gap in owned frames. Arms only
     * after a lagged readback shows zero phase-B draws for a frame after
     * the last change (the {@code >= c + 2} rule). Every doubt reads as a
     * change: the safe direction.
     */
    private static boolean phaseBSkipDecide(InstanceState s, double camX, double camY, double camZ,
            Matrix4fc modelView, Matrix4fc projection, int extentW, int extentH,
            long commitSerial, long signature, long ownedFrame) {
        if (!phaseBCpuSkipEnabled()) {
            s.pbKeyValid = false;
            phaseBCpuSkipArmed = false;
            return false;
        }
        boolean changed = !s.pbKeyValid;
        long px = Double.doubleToRawLongBits(camX);
        long py = Double.doubleToRawLongBits(camY);
        long pz = Double.doubleToRawLongBits(camZ);
        if (px != s.pbPosX || py != s.pbPosY || pz != s.pbPosZ) {
            changed = true;
            s.pbPosX = px;
            s.pbPosY = py;
            s.pbPosZ = pz;
        }
        // |= not ||=: every compare must also refresh its stored key.
        changed |= matrixChanged(modelView, s.pbModelView, s.pbScratch);
        changed |= matrixChanged(projection, s.pbProjection, s.pbScratch);
        if (extentW != s.pbExtentW || extentH != s.pbExtentH) {
            changed = true;
            s.pbExtentW = extentW;
            s.pbExtentH = extentH;
        }
        // NEXT (c): the frame's RASTER VARIANT is part of the key, and it is
        // the arm's per-frame RESULT rather than the static lever - so a
        // lever flip, an arm flip, an arm-time latch and an unusable
        // projection each read as an input change on the frame it happens.
        // This is for EXACTNESS, not safety: the sections a flip adds were
        // culled the frame before, so a missed skip would only delay hidden
        // sections by one frame. (The arm runs before this decide - see
        // drawSolidOwned.)
        if (s.frameHalfMode != s.pbHalfMode) {
            changed = true;
            s.pbHalfMode = s.frameHalfMode;
        }
        if (commitSerial != s.pbCommitSerial) {
            changed = true;
            s.pbCommitSerial = commitSerial;
        }
        if (signature != s.pbSignature) {
            changed = true;
            s.pbSignature = signature;
        }
        if (ownedFrame != s.pbLastOwned + 1L) {
            changed = true;
        }
        s.pbLastOwned = ownedFrame;
        s.pbKeyValid = true;
        if (changed) {
            s.pbInputChangeStatsFrame = s.statsFrames; // the frame about to record
        }
        long c = s.pbInputChangeStatsFrame;
        boolean skip = s.lastReadStatsFrame >= c + 2L && s.lastPhaseBStatsFrame <= c;
        phaseBCpuSkipArmed = skip;
        return skip;
    }

    /** Raw-bits compare AND refresh of one stored matrix key: true iff moved. */
    private static boolean matrixChanged(Matrix4fc m, float[] prev, float[] scratch) {
        m.get(scratch);
        boolean changed = false;
        for (int i = 0; i < 16; i++) {
            if (Float.floatToRawIntBits(scratch[i]) != Float.floatToRawIntBits(prev[i])) {
                changed = true;
            }
            prev[i] = scratch[i];
        }
        return changed;
    }
}
