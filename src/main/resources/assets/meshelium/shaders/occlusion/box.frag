// Copyright (C) 2026 Ded811
// SPDX-License-Identifier: LGPL-3.0-only
#version 460
//
// Wave-6 SHARED occlusion fragment shader (EXT dialect) — the visibility
// writer both box-raster pipelines use. Derived from Nvidium by MCRcortex
// (LGPL-3.0):
//   misc/reference/nvidium/.../shaders/occlusion/region_raster/fragment.frag
//     (early_fragment_tests + the one-line visibility store, :10,20-22)
//   misc/reference/nvidium/.../shaders/occlusion/section_raster/fragment.glsl
//     (same shape; gl_PrimitiveID carries the target index, :20-22)
//
// PORT NOTES (docs/NVIDIUM-ARCHITECTURE.md §5/§10 row 6):
//  - NO representative-fragment-test: NV_representative_fragment_test is
//    NV-only even in Vulkan. Every surviving fragment THAT FINDS THE WORD
//    UNSTAMPED stores — the stores are idempotent (all fragments of a
//    frame write the IDENTICAL 32-bit FrameStamp), so correctness never
//    depended on how many of them run, only the cost did. Letting every
//    fragment store unconditionally is what the read guard in main()
//    exists to avoid, and it was the whole cost of the feature.
//  - layout(early_fragment_tests) is LOAD-BEARING, not an optimization
//    hint here: a fragment shader with side effects (SSBO stores) would
//    otherwise be allowed to run before/without the depth test, and every
//    box fragment would mark its section visible — occlusion would
//    silently degrade to "everything visible". With it, only fragments
//    that PASS the depth test (GEQUAL vs the live phase-A terrain depth,
//    reversed-Z) reach the store.
//  - The store is atomicExchange, not a plain store: the section-raster
//    MESH stage also writes the same word (the camera-inside-box force),
//    and Nvidium's plain-store pair is formally a data race under the
//    Vulkan memory model (benign on NV, unproven elsewhere). All writers
//    of a frame exchange the SAME value, so ordering is irrelevant and
//    atomics make the outcome defined on every conformant device. The
//    guard READ added in stage 1a is NOT atomic and does reintroduce a
//    race; main() carries the argument for why that one is safe, and it
//    is a different argument from this one.
//  - Nvidium's fragment writes the whole history byte (carried through
//    gl_PrimitiveID's low bits). Meshelium's visibility is frame-stamped
//    (see TerrainOcclusion javadoc): gl_PrimitiveID carries ONLY the
//    target index — region dispatch slot for the region raster, global
//    section index (regionId*256+slot) for the section raster — and the
//    value written is this frame's stamp.
//
// Bound with colorWriteMask = 0 (no color output declared, none written)
// and depthWriteEnable = false: the ONLY side effect is the stamp store.

// The box index arrives as a PER-PRIMITIVE mesh output, NOT gl_PrimitiveID.
//
// Reading gl_PrimitiveID in a fragment shader makes glslang declare SPIR-V
// Capability Geometry, which the spec then requires
// VkPhysicalDeviceFeatures::geometryShader for
// (VUID-VkShaderModuleCreateInfo-pCode-08740). Vanilla does not enable that
// feature. AMD's driver tolerated it silently, so this shipped and worked
// on this desk for six waves; the validation layer refuses it outright, and
// so may a stricter driver. The failure mode is quiet and total:
// vkCreateShaderModule fails, occlusionError latches, and the whole feature
// reverts to the BFS feed with no visible sign except missing frames. That
// would have cost NVIDIA and Intel users the entire occlusion win while
// looking, from here, like occlusion simply not helping on their hardware.
//
// perprimitiveEXT carries the same value and declares MeshShadingEXT, which
// IS enabled because the whole renderer depends on it. Requires the
// extension in the FRAGMENT stage too, hence the line below.
#extension GL_EXT_mesh_shader : require

layout(early_fragment_tests) in;

// Written by region_raster.mesh (region dispatch slot) and by
// section_raster.mesh (global section index = regionId*256 + slot). One
// module serves both pipelines, so the location must match in both.
layout(location = 0) perprimitiveEXT flat in uint vBoxId;

// Region pipeline binds the region-stamp buffer here; section pipeline
// binds the CURRENT section-stamp buffer. Same binding index by design so
// this one module serves both pipelines.
layout(set = 0, binding = 3, std430) buffer StampOut {
    uint stampOut[];
};

#if MESHELIUM_MARK_NEW
// SECTION pipeline only: the newly-visible mark that lets conditional
// rendering skip phase B on frames with nothing to reveal. A section is
// newly visible when its stamp TRANSITIONS to FrameStamp (the exchange's
// return value identifies exactly the transitioning invocation) and it was
// not marked last frame. The region pipeline compiles without this: regions
// have no prev buffer, and a region transition does not imply a phase-B
// draw.
layout(set = 0, binding = 6, std430) readonly buffer PrevStamps {
    uint prevStamps[];
};
layout(set = 0, binding = 7, std430) buffer PhaseBPredicate {
    uint phaseBPredicate;
};
#endif

layout(push_constant) uniform OccPush {
    uint FrameStamp;
};

void main() {
    // Read-guarded store (docs/OCCLUSION-FILLRATE-DESIGN.md stage 1a).
    // Every fragment of a box targets the SAME word, so the unguarded
    // version paid one same-address atomic per COVERED PIXEL, and
    // same-address atomics serialise at the L2: a near box is a million
    // of them. This was the entire cost of occlusion culling — not the
    // fill rate the study first blamed.
    // Measured, RX 9070 XT, 1920x1080, ground-rd32: 287 → 1553 fps.
    //
    // The load is deliberately NOT atomic, and that IS formally a data
    // race with the exchange below and with the mesh stage's
    // camera-inside force write (Vulkan memory model: the two are "not
    // mutually-ordered atomic operations" and nothing location-orders
    // them). It is safe because the word is a MONOTONE ONE-WAY LATCH
    // within a frame — every writer of a frame writes the IDENTICAL
    // FrameStamp and nothing ever writes anything else — so:
    //   stale read       → guard fails → redundant exchange of the value
    //                      the word was going to get anyway (fail-open)
    //   reads FrameStamp → a writer already stored it and no writer can
    //                      undo it, so skipping is a no-op
    // The only unsafe outcome needs the load to return a value never
    // stored at this address, which an aligned 32-bit dword load cannot
    // do on real hardware. That is a hardware argument, not a spec one:
    // the spec declines to define a racy read's value at all.
    //
    // coherent/volatile are deliberately ABSENT. They would not remove
    // the race (they govern availability and visibility, not
    // happens-before), a non-coherent load can only ever be too OLD,
    // which is the fail-open direction, and hitting the per-CU cache
    // instead of the L2 is precisely the win. The only construct that
    // would make this formally race-free is an atomicLoad from
    // GL_KHR_memory_scope_semantics, which needs the vulkanMemoryModel
    // feature vanilla does not enable — and the atomicOr(x, 0u) idiom
    // for it is the per-fragment L2 round trip this exists to delete.
    uint id = vBoxId;
    if (stampOut[id] != FrameStamp) {
#if MESHELIUM_MARK_NEW
        // The return value makes the mark exact: of all fragments that pass
        // the stale-read guard, exactly one exchange returns the
        // pre-transition value. The guard read being stale only ADDS
        // exchanges that return FrameStamp and mark nothing - fail-open in
        // the direction of running phase B, never of skipping it.
        uint old = atomicExchange(stampOut[id], FrameStamp);
        if (old != FrameStamp && prevStamps[id] != FrameStamp - 1u) {
            phaseBPredicate = 1u;
        }
#else
        atomicExchange(stampOut[id], FrameStamp);
#endif
    }
}
