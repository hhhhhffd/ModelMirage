#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
in vec3 Position;
in vec2 UV0;
in vec4 Color;
out vec2 localPosition;
flat out vec2 halfSize;
flat out float radius;
flat out float blur;
flat out float opacity;
flat out vec4 tint;
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position.xy, 0.0, 1.0);
    localPosition = UV0;
    halfSize = abs(UV0);
    // Z packs: radius * 2 (8 bits) | blur px (6 bits) | opacity * 63 (6 bits).
    float meta = floor(Position.z + 0.5);
    radius = mod(meta, 256.0) * 0.5;
    blur = mod(floor(meta / 256.0), 64.0);
    opacity = floor(meta / 16384.0) / 63.0;
    tint = Color;
}
