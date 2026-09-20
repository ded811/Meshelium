/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium;

/**
 * Did the five record-mutation hooks land, and has the run cache been
 * stood down.
 *
 * <h2>Why this exists</h2>
 * <p>The per-region run cache (D-021, MEASUREMENTS.md 0k) is only correct
 * while every path that rewrites a section's geometry record passes
 * through a hooked site. The hooks are {@code @Inject}s with
 * {@code require = 0}, because a Sodium update that renames one of the
 * five targets must cost a slower frame, not a crash — and Mixin's own
 * behaviour for a missing target under {@code required: false} is to log
 * a warning and carry on with the injection silently absent. Nothing
 * downstream would know. So {@code MesheliumSodiumMixinPlugin.preApply}
 * checks each target by name and descriptor in the class node before the
 * mixin is applied and records the answer here; the draw list arms the
 * cache only when all five are present.
 *
 * <h2>Why it is a separate class with no imports</h2>
 * <p>The plugin writes here from inside class transformation, while
 * Sodium's {@code RenderRegion} is being loaded. Anything this class
 * pulled in would load at that moment too — and loading a Blaze3D or
 * Minecraft class before its own mixins have applied is a session-ending
 * fault that shows up as somebody else's mixin failing. {@code
 * SodiumTerrainDrawer}, where the counters live, names half of Blaze3D in
 * its signatures; the plugin must never touch it. This class names nothing
 * but {@code java.lang}.
 *
 * <h2>Why the strings are here, once</h2>
 * <p>The injector annotations and the plugin's check must agree on the
 * exact name and descriptor of each target, or a hook could be present
 * and unchecked (or checked and absent). Both read the constants below;
 * the descriptors were confirmed by javap against Sodium 0.9.2-beta.1.
 *
 * <h2>Clean room</h2>
 * <p>Sodium is PolyForm Shield. This file names five of Sodium's methods
 * by their JVM descriptors — the minimum needed to interoperate — and
 * contains no Sodium code.
 */
public final class MesheliumSodiumHooks {

    /** Internal (slashed) names, as {@code ClassNode.name} reports them. */
    public static final String RENDER_REGION =
            "net/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion";

    public static final String RENDER_REGION_MANAGER =
            "net/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager";

    /**
     * H1: the private per-region {@code uploadResults} overload — the sole
     * caller of {@code SectionRenderDataStorage.setVertexData} and of
     * {@code removeVertexData}. The descriptor is mandatory: the class has
     * a public overload of the same name.
     */
    public static final String UPLOAD_NAME = "uploadResults";

    public static final String UPLOAD_DESC =
            "(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"
                    + "Ljava/util/Collection;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V";

    public static final String UPLOAD_TARGET = UPLOAD_NAME + UPLOAD_DESC;

    /** H2: a section leaving the region clears its record. */
    public static final String REMOVE_SECTION_NAME = "removeSection";

    public static final String REMOVE_SECTION_DESC =
            "(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;)V";

    public static final String REMOVE_SECTION_TARGET = REMOVE_SECTION_NAME + REMOVE_SECTION_DESC;

    /** H3: the geometry buffer was replaced; every base vertex is re-derived. */
    public static final String BUFFER_CHANGE_NAME = "onGeometryBufferChange";

    public static final String BUFFER_CHANGE_DESC = "()V";

    public static final String BUFFER_CHANGE_TARGET = BUFFER_CHANGE_NAME + BUFFER_CHANGE_DESC;

    /**
     * H4: a defragmentation moved one section's segment inside the same
     * buffer — the only signal for that move, which changes a base vertex
     * without changing the buffer.
     */
    public static final String SEGMENT_CHANGE_NAME = "onGeometrySegmentChange";

    public static final String SEGMENT_CHANGE_DESC = "(I)V";

    public static final String SEGMENT_CHANGE_TARGET = SEGMENT_CHANGE_NAME + SEGMENT_CHANGE_DESC;

    /** H5: the region is going away and its record heap with it. */
    public static final String DELETE_NAME = "delete";

    public static final String DELETE_DESC = "()V";

    public static final String DELETE_TARGET = DELETE_NAME + DELETE_DESC;

    public static final int HOOK_UPLOAD = 1;
    public static final int HOOK_REMOVE_SECTION = 1 << 1;
    public static final int HOOK_BUFFER_CHANGE = 1 << 2;
    public static final int HOOK_SEGMENT_CHANGE = 1 << 3;
    public static final int HOOK_DELETE = 1 << 4;
    public static final int HOOKS_ALL = HOOK_UPLOAD | HOOK_REMOVE_SECTION | HOOK_BUFFER_CHANGE
            | HOOK_SEGMENT_CHANGE | HOOK_DELETE;

    /**
     * Stage 2/3 (D-023): the private search-distance method the
     * GPU-visibility distance gate reaches through an {@code @Invoker}.
     * NOT part of {@link #HOOKS_ALL}: its absence widens the gate to the
     * render distance (contract section 6.3), it disarms nothing.
     */
    public static final String RENDER_SECTION_MANAGER =
            "net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager";

    public static final String SEARCH_DISTANCE_NAME = "getSearchDistance";

    public static final String SEARCH_DISTANCE_DESC =
            "(Lnet/caffeinemc/mods/sodium/client/util/FogParameters;)F";

    public static final String SEARCH_DISTANCE_TARGET = SEARCH_DISTANCE_NAME + SEARCH_DISTANCE_DESC;

    public static final int HOOK_SEARCH_DISTANCE = 1 << 5;

    /**
     * The per-region geometry bitmap Sodium already keeps, which the
     * mirror reads instead of re-deriving it from the byte iterator
     * (2026-09-17). NOT part of {@link #HOOKS_ALL}: its absence costs the
     * drain this replaces, it disarms nothing else.
     */
    public static final String CHUNK_RENDER_LIST =
            "net/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderList";

    public static final String GEOMETRY_MAP_NAME = "sectionsWithGeometryMap";

    /** {@code long[]}, four words, 256 slot bits (javap, the ctor's array store). */
    public static final String GEOMETRY_MAP_DESC = "[J";

    public static final String GEOMETRY_MAP_TARGET = GEOMETRY_MAP_NAME + " " + GEOMETRY_MAP_DESC;

    public static final int HOOK_GEOMETRY_MAP = 1 << 6;

    /**
     * Sodium's indirect-command ring context. Its constructor sizes the ring
     * with one literal, and growing that ring later corrupts the frame
     * (VKIndirectContextMixin explains how). NOT part of {@link #HOOKS_ALL}:
     * its absence restores Sodium's own size and the defect with it.
     */
    public static final String VK_INDIRECT_CONTEXT =
            "net/caffeinemc/mods/sodium/client/gpu/device/context/VKIndirectContext";

    /** {@code INITIAL_SIZE}, the literal the constructor passes (javap). */
    public static final int INDIRECT_RING_STOCK_BYTES = 512_000;

    /**
     * What we give it instead: 4 MiB, a budget of 209,715 commands a frame
     * against stock's 25,600. Sized from the largest count this project has
     * measured - 28,207 opaque runs in one rd96 frame - times the three
     * terrain passes that share the budget, times a safety factor of about
     * two for rd120. Costs 3x that in GPU memory (MappableRingBuffer's
     * BUFFER_COUNT is 3), so 12.6 MB against stock's 1.5 MB.
     */
    public static final int INDIRECT_RING_DEFAULT_BYTES = 4 * 1024 * 1024;

    /** {@code -Dmeshelium.sodium.indirectRingBytes=<n>}; 0 leaves Sodium's own size. */
    public static final String INDIRECT_RING_PROPERTY = "meshelium.sodium.indirectRingBytes";

    /**
     * The ring size to use, given the literal Sodium asked for.
     *
     * <p>Only ever grows it: a value below Sodium's own would make the ring
     * grow SOONER, which is the failure this exists to prevent, so a smaller
     * request is refused rather than honoured. An unparseable value, or any
     * failure to read the property at all, leaves Sodium's own size.
     */
    public static int indirectRingBytes(int stock) {
        int want = INDIRECT_RING_DEFAULT_BYTES;
        try {
            String raw = System.getProperty(INDIRECT_RING_PROPERTY);
            if (raw != null) {
                want = Integer.parseInt(raw.trim());
            }
        } catch (Throwable ignored) {
            want = INDIRECT_RING_DEFAULT_BYTES;
        }
        if (want <= stock) {
            return stock;
        }
        return want;
    }

    /**
     * True when the plugin found the ring literal in the context's
     * constructor. The mixin is a {@code @ModifyConstant}, whose failure
     * class is fatal when unchecked, so the check is instruction-level.
     */
    public static boolean indirectRingFound() {
        return (hooksFound & HOOK_INDIRECT_RING) != 0;
    }

    public static final int HOOK_INDIRECT_RING = 1 << 7;

    /**
     * True when the plugin found {@code sectionsWithGeometryMap} on
     * {@code ChunkRenderList}. The mirror calls the accessor ONLY when
     * this is true: an {@code @Accessor} against a renamed field leaves
     * the interface unimplemented and the call site would throw
     * {@code AbstractMethodError} on the first drawn frame.
     */
    public static boolean geometryMapFound() {
        return (hooksFound & HOOK_GEOMETRY_MAP) != 0;
    }

    /** True when the plugin found {@code getSearchDistance(FogParameters)} on the manager. */
    public static boolean searchDistanceFound() {
        return (hooksFound & HOOK_SEARCH_DISTANCE) != 0;
    }

    /** Bits of {@code HOOK_*} whose target the plugin found in the class node. */
    private static volatile int hooksFound;

    /**
     * Why the cache was stood down at runtime, or null. Latched: once a
     * guard has fired the session runs the walk, because a cache that was
     * wrong once has no way to say when it became right again.
     */
    private static volatile String disarmReason;

    private MesheliumSodiumHooks() {
    }

    /** Called by the mixin plugin, once per target it verified. */
    public static void markFound(int bit) {
        hooksFound |= bit;
    }

    public static int hooksFound() {
        return hooksFound;
    }

    /** True when all five targets were present when their classes loaded. */
    public static boolean hooksComplete() {
        return (hooksFound & HOOKS_ALL) == HOOKS_ALL;
    }

    /** Stand the cache down for the rest of the session; the first reason wins. */
    public static void disarm(String reason) {
        // Said once, out loud: a session that silently walks every frame while
        // reporting itself armed is the failure this holder exists to prevent
        // (second review of stage 1). java.lang only, like the rest of this
        // class - it may be called during class transformation.
        if (disarmReason == null) {
            System.out.println("[Meshelium] Sodium run cache disarmed for this session: " + reason);
        }
        if (disarmReason == null) {
            disarmReason = reason;
        }
    }

    public static boolean disarmed() {
        return disarmReason != null;
    }

    public static String disarmReason() {
        return disarmReason;
    }

    /** Names of the hooks not yet found, for a log line; empty when complete. */
    public static String describeMissing() {
        int found = hooksFound;
        StringBuilder sb = new StringBuilder();
        if ((found & HOOK_UPLOAD) == 0) {
            sb.append("RenderRegionManager.").append(UPLOAD_TARGET).append(' ');
        }
        if ((found & HOOK_REMOVE_SECTION) == 0) {
            sb.append("RenderRegion.").append(REMOVE_SECTION_TARGET).append(' ');
        }
        if ((found & HOOK_BUFFER_CHANGE) == 0) {
            sb.append("RenderRegion.").append(BUFFER_CHANGE_TARGET).append(' ');
        }
        if ((found & HOOK_SEGMENT_CHANGE) == 0) {
            sb.append("RenderRegion.").append(SEGMENT_CHANGE_TARGET).append(' ');
        }
        if ((found & HOOK_DELETE) == 0) {
            sb.append("RenderRegion.").append(DELETE_TARGET).append(' ');
        }
        return sb.toString().trim();
    }
}
