#version 330

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;
in vec2 texCoord0;
in vec4 vertexColor;
out vec4 fragColor;

// MSDF text shader for the 26.2 GUI pipeline; see THIRD_PARTY_NOTICES.md.
float median(vec3 value) {
    return max(min(value.r, value.g), min(max(value.r, value.g), value.b));
}

// distanceRange of every atlas in assets/modelmirage/ui/fonts.
const float DISTANCE_RANGE = 10.0;

void main() {
    float distance = median(texture(Sampler0, texCoord0).rgb) - 0.5;
    // Distance range in screen pixels; keeping it at least 1 stops thin strokes of small text from vanishing.
    vec2 unitRange = vec2(DISTANCE_RANGE) / vec2(textureSize(Sampler0, 0));
    float screenPxRange = max(0.5 * dot(unitRange, 1.0 / fwidth(texCoord0)), 1.0);
    float alpha = clamp(screenPxRange * distance + 0.5, 0.0, 1.0);
    if (alpha < 0.01) discard;
    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;
}
