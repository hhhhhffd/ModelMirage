#version 330
#moj_import <minecraft:dynamictransforms.glsl>
in vec2 localPosition;
flat in vec2 halfSize;
flat in float radius;
flat in float border;
in vec4 vertexColor;
out vec4 fragColor;
void main() {
    vec2 q = abs(localPosition) - halfSize + radius;
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    float aa = max(fwidth(d), 0.0001);
    float alpha = 1.0 - smoothstep(-aa, aa, d);
    if (border > 0.1) alpha *= 1.0 - smoothstep(border - aa, border + aa, -d);
    if (alpha < 0.001) discard;
    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;
}
