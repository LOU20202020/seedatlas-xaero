#version 330
#extension GL_ARB_separate_shader_objects : require

// Builds Xaero's zoomed-out textures like its own branch shader, but averages only explored texels:
// unexplored ones (all zero) used to darken the edges of explored terrain in every coarser level.

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 size = textureSize(Sampler0, 0);
    vec2 centred = texCoord0 * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(centred));
    vec2 f = centred - vec2(base);
    vec4 sum = vec4(0.0);
    float weight = 0.0;
    for (int dy = 0; dy < 2; ++dy) {
        for (int dx = 0; dx < 2; ++dx) {
            vec4 t = texelFetch(Sampler0, clamp(base + ivec2(dx, dy), ivec2(0), size - 1), 0);
            float w = (dx == 0 ? 1.0 - f.x : f.x) * (dy == 0 ? 1.0 - f.y : f.y);
            if (t != vec4(0.0) && w > 0.0) {
                sum += t * w;
                weight += w;
            }
        }
    }
    // Unexplored stays exactly zero, which the map shader discards.
    fragColor = weight > 0.0 ? sum / weight : vec4(0.0);
}
