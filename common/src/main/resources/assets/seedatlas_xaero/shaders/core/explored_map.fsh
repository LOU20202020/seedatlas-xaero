#version 330
#extension GL_ARB_separate_shader_objects : require

// Xaero's map shader, except that unexplored texels (all zero) never take part in filtering. Linear
// minification blended them in as black, and the result was drawn opaque: a dark seam around explored
// terrain on top of the seed layer.

uniform sampler2D Sampler0;

layout(std140) uniform BrightnessBlock {
    float Brightness;
};

layout(std140) uniform WithLightBlock {
    int WithLight;
};

layout(location = 0) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

vec4 texel(ivec2 position, ivec2 size) {
    return texelFetch(Sampler0, clamp(position, ivec2(0), size - 1), 0);
}

void main() {
    ivec2 size = textureSize(Sampler0, 0);
    vec2 position = texCoord0 * vec2(size);
    vec2 footprint = fwidth(position);
    vec4 color;
    if (max(footprint.x, footprint.y) <= 1.0) {
        // Magnified: nearest, like Xaero's sampler.
        color = texel(ivec2(floor(position)), size);
    } else {
        // Minified: bilinear over the explored texels only.
        vec2 centred = position - 0.5;
        ivec2 base = ivec2(floor(centred));
        vec2 f = centred - vec2(base);
        vec4 sum = vec4(0.0);
        float weight = 0.0;
        for (int dy = 0; dy < 2; ++dy) {
            for (int dx = 0; dx < 2; ++dx) {
                vec4 t = texel(base + ivec2(dx, dy), size);
                float w = (dx == 0 ? 1.0 - f.x : f.x) * (dy == 0 ? 1.0 - f.y : f.y);
                if (t != vec4(0.0) && w > 0.0) {
                    sum += t * w;
                    weight += w;
                }
            }
        }
        color = weight > 0.0 ? sum / weight : vec4(0.0);
    }
    if (color == vec4(0.0))
        discard;
    if (WithLight == 0) {
        fragColor = vec4(color.rgb * Brightness, 1);
        return;
    }
    float light = color.a;
    float litBrightness = max(light, Brightness);
    fragColor = vec4(color.rgb * litBrightness, 1);
}
