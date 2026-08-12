#version 460

// Meshelium wave 2 — shared fragment stage for both hello meshlet variants.
// Writes the loud solid colour the mesh stage chose (magenta for the NDC
// triangle, yellow for the world-space one) — one frag file serves both
// pipelines.

layout(location = 0) in vec4 mesheliumColor;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = mesheliumColor;
}
