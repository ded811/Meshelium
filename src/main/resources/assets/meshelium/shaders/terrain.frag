// Copyright (C) 2026 Ded811
// SPDX-License-Identifier: LGPL-3.0-only
#version 460
//
// Wave-4 opaque terrain FRAGMENT shader. This is a port of VANILLA's
// terrain.fsh + fog.glsl (both dumped verbatim from the 26.2 jar — that is
// the pixel-parity bar), not of Nvidium's frag.frag: Nvidium's smoothstep
// fog and barycentric vertex re-fetch would both diverge from vanilla.
// Per SPEC decision 2 the interpolant style (Alphadium's baseline) is used
// instead of barycentric pulling.
//
// Deviations from vanilla terrain.fsh, each deliberate:
//  * ALPHA_CUTOUT is a per-primitive material decision (Nvidium's material
//    bits, docs/TERRAIN-DATA.md §1) instead of a pipeline #define — the
//    wave-3 geometry stream interleaves SOLID and CUTOUT quads inside each
//    facing bucket, so one pipeline draws both; index 0 = cutoff 0.0 makes
//    SOLID quads discard-free, index 2 = 0.5 reproduces CUTOUT_TERRAIN's
//    define, matching vanilla's per-pipeline thresholds exactly
//    (RenderPipelines bytecode: CUTOUT terrain 0.5, default 0.1).
//  * ChunkVisibility (per-section fade-in) is fixed at 1.0 — the mix() with
//    FogColor is the identity for every settled section; the sub-second
//    fade of a freshly built section is the only visual difference, and the
//    parity harness quiesces before its screenshots.
//  * Fog UBO/Globals UBO are vanilla's OWN GPU slices (RenderSystem
//    .getShaderFog()/.getGlobalSettingsUniform()), declared verbatim from
//    assets/minecraft/shaders/include/{fog,globals}.glsl — same bytes, same
//    std140 layout, same math ⇒ same pixels.

// Wave-5 layout (grown for the task stage's frustum planes + camera chunk;
// must match terrain.task/terrain.mesh and TerrainDrawer.uploadScene
// byte-for-byte — this stage still reads SceneMisc only).
layout(set = 0, binding = 1, std140) uniform MesheliumScene {
    mat4 ModelViewMat;
    vec4 SceneMisc;  // xy = block atlas size in texels (TextureSize)
    vec4 FrustumPlanes[6];
    ivec4 CameraChunk;
};

// Verbatim from assets/minecraft/shaders/include/fog.glsl (26.2 jar).
layout(set = 0, binding = 3, std140) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

// Verbatim from assets/minecraft/shaders/include/globals.glsl (26.2 jar);
// only UseRgss is read here, but the whole block keeps offsets identical.
layout(set = 0, binding = 4, std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};

layout(set = 0, binding = 5) uniform sampler2D Sampler0; // block atlas

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float sphericalVertexDistance;
layout(location = 3) in float cylindricalVertexDistance;
layout(location = 4) flat in uint materialBits;
// Sprite rectangle: xy = atlas min, zw = atlas max. One vec4 rather than
// two vec2s plus a repeat varying, because mesh-stage output memory is
// charged per LOCATION and the spec floor is 32768 B per workgroup; the
// repeat pair is re-derived from materialBits below instead of shipped.
layout(location = 5) flat in vec4 spriteRect;

layout(location = 0) out vec4 fragColor;

// Wrap a greedy-merged quad's coordinate back inside its sprite.
//
// The merged quad keeps ONE tile's UVs and carries a tile count, {1,2,4,8,16}
// per axis, unpacked from the material byte by the mesh stage and arriving
// here as a flat varying. The interpolated coordinate therefore sweeps the
// sprite once across a quad that should show it N times, and this multiplies
// it up and folds it back.
//
// The derivative is the subtle part and the reason this returns one rather
// than calling texture() directly. fract() is discontinuous at every tile
// boundary, so a hardware-derived gradient spikes there, the sampler picks
// the smallest mip, and the seams show as a sharp grid at distance. Taking
// the gradient from the UNWRAPPED coordinate and sampling with textureGrad
// keeps it continuous across the fold.
struct TiledUv {
    vec2 uv;
    vec2 dx;
    vec2 dy;
};

TiledUv meshelium_tileUv(vec2 uv, vec2 repeat) {
    vec2 uvMin = spriteRect.xy;
    vec2 span = max(spriteRect.zw - uvMin, vec2(1.0e-9));
    // Position within the sprite, scaled up to the tile count. Gradients
    // come from THIS, before the fold.
    vec2 scaled = (uv - uvMin) / span * repeat;
    TiledUv r;
    r.dx = dFdx(scaled) * span;
    r.dy = dFdy(scaled) * span;
    r.uv = uvMin + fract(scaled) * span;
    return r;
}

// ---- vanilla fog.glsl, verbatim ----

float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
    if (vertexDistance <= fogStart) {
        return 0.0;
    } else if (vertexDistance >= fogEnd) {
        return 1.0;
    }

    return (vertexDistance - fogStart) / (fogEnd - fogStart);
}

float total_fog_value(float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd) {
    return max(linear_fog_value(sphericalVertexDistance, environmentalStart, environmantalEnd), linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd));
}

vec4 apply_fog(vec4 inColor, float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd, vec4 fogColor) {
    float fogValue = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, environmentalStart, environmantalEnd, renderDistanceStart, renderDistanceEnd);
    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
}

// ---- vanilla terrain.fsh atlas samplers, verbatim ----

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    // Convert our UV back up to texel coordinates and find out how far over we are from the center of each pixel
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5f;
    vec2 texelOffset = uvTexelCoords - texelCenter;

    // Move our offset closer to the texel center based on texel size on screen
    texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
    texelOffset = clamp(texelOffset, 0.0f, 1.0f);

    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(source, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

// Rotated Grid Super-Sampling.
//
// The gradient-taking overload exists for greedy-merged quads, whose folded
// UV is discontinuous at every tile boundary: RGSS derives its mip LEVEL and
// its nearest-blend factor from the derivative, so deriving that from the
// folded coordinate spikes at the fold and draws the tile grid in wrong-mip
// seams, exactly the artifact meshelium_tileUv exists to prevent (and the
// same mistake this file already avoids on the sampleNearest path). The
// vanilla-verbatim 3-argument form below is untouched and remains the only
// one the unmerged path calls, so pixel parity for vanilla geometry is
// unaffected.
vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv) {
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);

    float minPixelSize = min(pixelSize.x, pixelSize.y);

    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);

    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);

    float effectiveDerivative = sqrt(minDerivative * maxDerivative);

    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    float mipLevelLow = floor(mipLevelExact);
    float mipLevelHigh = mipLevelLow + 1.0;
    float mipBlend = fract(mipLevelExact);

    const vec2 offsets[4] = vec2[](
    vec2(0.125, 0.375),
    vec2(-0.125, -0.375),
    vec2(0.375, -0.125),
    vec2(-0.375, 0.125)
    );

    vec4 rgssColorLow = vec4(0.0);
    vec4 rgssColorHigh = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColorLow += textureLod(source, sampleUV, mipLevelLow);
        rgssColorHigh += textureLod(source, sampleUV, mipLevelHigh);
    }
    rgssColorLow *= 0.25;
    rgssColorHigh *= 0.25;

    vec4 rgssColor = mix(rgssColorLow, rgssColorHigh, mipBlend);

    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);

    return mix(nearestColor, rgssColor, blendFactor);
}

// Vanilla terrain.fsh's own form, verbatim: hardware derivatives.
vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    return sampleRGSS(source, uv, pixelSize, dFdx(uv), dFdy(uv));
}

// Material bits 0-1 → alpha cutoff (vertex_format.glsl:29-31 / wave-3b
// material mapping: SOLID=0 → 0.0, TRANSLUCENT=1 → 0.1, CUTOUT=2 → 0.5).
const float ALPHA_CUTOFFS[3] = float[](0.0, 0.1, 0.5);

void main() {
    vec2 TextureSize = SceneMisc.xy;
    vec2 pixelSize = 1.0f / TextureSize;
    // Unmerged quads take the vanilla-verbatim path untouched: the packed
    // pair is index 0, which is repeat (1,1), the branch is flat (the pair
    // rides in materialBits, a flat varying, so it is uniform across the
    // primitive), and pixel parity with vanilla is unaffected for every quad
    // the mesher did not touch.
    vec4 color;
    if ((materialBits & 0xF8u) != 0u) {
        // The tile counts ride in materialBits as log2(u) * 5 + log2(v)
        // (bits 3-7). Re-deriving them here costs a few integer ops on
        // merged-quad fragments only, and it bought back a whole output
        // location in the mesh stage, which is what keeps the 64-quad
        // translucent workgroup inside spec-minimum output memory.
        uint repeatPair = materialBits >> 3u;
        vec2 repeat = vec2(float(1u << (repeatPair / 5u)), float(1u << (repeatPair % 5u)));
        TiledUv t = meshelium_tileUv(texCoord0, repeat);
        vec2 texelScreenSize = sqrt(t.dx * t.dx + t.dy * t.dy);
        // Both samplers get the UNWRAPPED gradients. The first build of this
        // passed the folded uv to the 3-argument sampleRGSS, whose internal
        // dFdx reintroduced the seam this branch exists to avoid; found by
        // review, not by screenshot, because RGSS is a vanilla setting the
        // harness never turns on.
        color = (UseRgss == 1
                ? sampleRGSS(Sampler0, t.uv, pixelSize, t.dx, t.dy)
                : sampleNearest(Sampler0, t.uv, pixelSize, t.dx, t.dy, texelScreenSize)) * vertexColor;
    } else {
        color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, pixelSize) : sampleNearest(Sampler0, texCoord0, pixelSize)) * vertexColor;
    }
    // vanilla: color = mix(FogColor * vec4(1,1,1,color.a), color, ChunkVisibility);
    // ChunkVisibility == 1.0 here (fade-in deviation, see header) → identity.
    if (color.a < ALPHA_CUTOFFS[materialBits & 3u]) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
