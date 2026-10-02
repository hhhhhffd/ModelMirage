#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
in vec3 Position;
in vec2 UV0;
in vec4 Color;
out vec2 localPosition;
flat out vec2 halfSize;
flat out float radius;
flat out float border;
out vec4 vertexColor;
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position.xy, 0.0, 1.0);
    localPosition = UV0;
    halfSize = abs(UV0);
    radius = floor(Position.z + 0.0001);
    border = fract(Position.z + 0.0001) * 1000.0 - 0.1;
    vertexColor = Color;
}
