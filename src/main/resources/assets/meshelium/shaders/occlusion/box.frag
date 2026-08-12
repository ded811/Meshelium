#version 460
// Meshelium — LGPL-3.0-only.
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
//    NV-only even in Vulkan. Every surviving fragment stores — the stores
//    are idempotent (all fragments of a frame write the IDENTICAL 32-bit
//    FrameStamp), so correctness is unaffected; the cost is redundant
//    stores, accepted per the study's verdict.
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
//    atomics make the outcome defined on every conformant device.
//  - Nvidium's fragment writes the whole history byte (carried through
//    gl_PrimitiveID's low bits). Meshelium's visibility is frame-stamped
//    (see TerrainOcclusion javadoc): gl_PrimitiveID carries ONLY the
//    target index — region dispatch slot for the region raster, global
//    section index (regionId*256+slot) for the section raster — and the
//    value written is this frame's stamp.
//
// Bound with colorWriteMask = 0 (no color output declared, none written)
// and depthWriteEnable = false: the ONLY side effect is the stamp store.

layout(early_fragment_tests) in;

// Region pipeline binds the region-stamp buffer here; section pipeline
// binds the CURRENT section-stamp buffer. Same binding index by design so
// this one module serves both pipelines.
layout(set = 0, binding = 3, std430) buffer StampOut {
    uint stampOut[];
};

layout(push_constant) uniform OccPush {
    uint FrameStamp;
};

void main() {
    atomicExchange(stampOut[uint(gl_PrimitiveID)], FrameStamp);
}
