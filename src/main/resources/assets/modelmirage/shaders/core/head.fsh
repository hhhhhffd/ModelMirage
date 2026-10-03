#version 330
#moj_import <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;
in vec2 localPosition;
flat in vec2 halfSize;
flat in float radius;
flat in float border;
in vec4 vertexColor;
out vec4 fragColor;
void main() {
    vec2 q = abs(localPosition) - halfSize + radius;
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    float alpha = 1.0 - smoothstep(-fwidth(d), fwidth(d), d);
    if (alpha < 0.001) discard;
    vec2 uv = (localPosition / halfSize + 1.0) * 0.5;
    vec4 face;
    if (border < 0.5) {
        face = texture(Sampler0, vec2(0.125) + uv * 0.125);
        vec4 hat = texture(Sampler0, vec2(0.625, 0.125) + uv * 0.125);
        face.rgb = mix(face.rgb, hat.rgb, hat.a);
    } else {
        face = texture(Sampler0, uv);
    }
    fragColor = face * vertexColor * ColorModulator;
    fragColor.a *= alpha;
}
