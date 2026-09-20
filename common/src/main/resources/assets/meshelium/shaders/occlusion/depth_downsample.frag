// Copyright (C) 2026 Ded811
// SPDX-License-Identifier: LGPL-3.0-only
#version 460
//
// NEXT (c) half-resolution occlusion depth, pass D: reduce vanilla's main
// depth attachment to the half-size D32 the box rasters test against.
//
// REVERSED-Z. NEAR=1, FAR=0. REDUCE WITH MIN (FARTHEST).
//
// That line is the whole correctness story of this file and it is quoted
// from the design doc (docs/OCCLUSION-FILLRATE-DESIGN.md:229-252). A MAX
// here would take the NEAREST of the window, the half-res depth would be
// nearer than the terrain it stands for, boxes hidden behind nothing would
// fail the GEQUAL test and TERRAIN WOULD BE DELETED. The min is what makes
// the terrain side of the superset argument hold: the half-res depth is
// never nearer than any full-res sample it covers, so a box that passed
// against a full-res sample still passes here.
//
// THE WINDOW, and why it is not always 2x2. Vanilla's own pass constructor
// records the viewport as the INTEGER attachment size
// (VkViewport{0, 0, outputWidth, outputHeight, 0, 1},
// VulkanRenderPass.<init>), so for an ODD main width W the half-res scale
// is 2W/(W+1), not 2. Half pixel X's footprint in full-res pixels is then
// [2XW/(W+1), 2(X+1)W/(W+1)), a subset of [2X-1, 2X+2), whose full-res
// centres are {2X-1, 2X, 2X+1}: a 3-wide window on an odd axis covers
// them, and clamping handles X = 0 and the last column (a duplicated texel
// can never raise a min). Even axes fetch exactly {2X, 2X+1}. 1080p, 1440p
// and the 854x480 harness are all even, so the odd arm is exercised
// deliberately by one -Pmeshelium.res=1281x721 run.
//
// No early_fragment_tests: this stage WRITES gl_FragDepth, and the
// depth state is test ALWAYS + write ON, so the early test would be
// testing a value this shader has not produced yet.
//
// Sampled, not copied: the main depth carries VK_IMAGE_USAGE_SAMPLED_BIT
// (RenderTarget.createBuffers, usage 15) and its view is aspect DEPTH; a
// sampled read in GENERAL is legal (frame-path 1.4) and phase A's writes
// are ordered before it by vanilla's pass-end ALL_COMMANDS barrier
// (frame-path 1.3). copyTextureToTexture cannot resize and
// textureUsageToVk never emits STORAGE, so those two routes are closed.

layout(set = 0, binding = 0) uniform sampler2D MainDepth;

layout(push_constant) uniform DownPush {
    ivec2 SrcSize;
};

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 odd = ivec2(SrcSize.x & 1, SrcSize.y & 1);
    ivec2 base = 2 * p - odd;
    ivec2 n = ivec2(2) + odd;
    ivec2 hi = SrcSize - ivec2(1);
    // Seed at the NEAR end of reversed-Z so the first texel always wins.
    float z = 1.0;
    for (int y = 0; y < n.y; ++y) {
        for (int x = 0; x < n.x; ++x) {
            ivec2 c = clamp(base + ivec2(x, y), ivec2(0), hi);
            z = min(z, texelFetch(MainDepth, c, 0).r);
        }
    }
    gl_FragDepth = z;
}
