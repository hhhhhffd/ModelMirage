#version 330

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;
in vec2 texCoord0;
in vec4 vertexColor;
out vec4 fragColor;

// Adapted from AstolfoVisuals' MRE msdf_font shader for the 26.2 GUI pipeline.
float median(vec3 value) {
    return max(min(value.r, value.g), min(max(value.r, value.g), value.b));
}

void main() {
    float distance = median(texture(Sampler0, texCoord0).rgb) - 0.5;
    float smoothing = max(fwidth(distance), 0.0001);
    float alpha = clamp(distance / smoothing + 0.5, 0.0, 1.0);
    if (alpha < 0.01) discard;
    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;
}
