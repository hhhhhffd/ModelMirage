#version 330
#moj_import <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;
in vec2 localPosition;
flat in vec2 halfSize;
flat in float radius;
flat in float blur;
flat in float opacity;
flat in vec4 tint;
out vec4 fragColor;

// Liquid glass: blurred backdrop, lens refraction near the rim, tint, saturation and a specular edge.
const int TAPS = 28;

vec3 sampleBlur(vec2 uv, vec2 texel, float radiusPx) {
    if (radiusPx < 0.5) return texture(Sampler0, uv).rgb;
    vec3 sum = vec3(0.0);
    float weights = 0.0;
    for (int i = 0; i < TAPS; i++) {
        float f = (float(i) + 0.5) / float(TAPS);
        float r = sqrt(f) * radiusPx;
        float a = float(i) * 2.39996323;
        float w = 1.0 - f * 0.6;
        sum += texture(Sampler0, uv + vec2(cos(a), sin(a)) * r * texel).rgb * w;
        weights += w;
    }
    return sum / weights;
}

void main() {
    vec2 q = abs(localPosition) - halfSize + radius;
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    float aa = max(fwidth(d), 0.0001);
    float coverage = 1.0 - smoothstep(-aa, aa, d);
    if (coverage < 0.001) discard;

    // Pixels per GUI unit, so effects keep their physical size at any GUI scale.
    float unit = 1.0 / max(fwidth(localPosition.x), 0.0001);
    vec2 size = vec2(textureSize(Sampler0, 0));
    vec2 texel = 1.0 / size;
    vec2 uv = gl_FragCoord.xy * texel;

    // Outward normal of the rounded rectangle; GUI y points down, framebuffer y points up.
    vec2 n = q.x > 0.0 && q.y > 0.0 ? normalize(q) : (q.x > q.y ? vec2(1.0, 0.0) : vec2(0.0, 1.0));
    n *= sign(localPosition + 0.0001);
    float bevel = min(10.0, min(halfSize.x, halfSize.y) * 0.5);
    float rim = clamp(1.0 + d / bevel, 0.0, 1.0);
    float lens = rim * rim * rim;
    vec2 refract = vec2(n.x, -n.y) * lens * 7.0 * unit;

    vec3 sharp = texture(Sampler0, uv).rgb;
    vec3 color = sampleBlur(uv - refract * texel, texel, blur * unit + lens * 2.0);
    float luma = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(luma), color, 1.25);
    color = mix(color, tint.rgb, tint.a);

    // Light from the top-left: bright rim on lit edges, faint inner shade on the opposite side.
    float edge = 1.0 - smoothstep(0.0, 1.4 * aa, -d);
    float light = clamp(dot(n, normalize(vec2(-0.45, -1.0))), -1.0, 1.0);
    color += edge * (0.10 + 0.22 * max(light, 0.0));
    color += lens * 0.05 * max(light, 0.0);
    color -= lens * 0.06 * max(-light, 0.0);
    float sheen = 1.0 - (localPosition.y / max(halfSize.y, 0.0001) * 0.5 + 0.5);
    color += sheen * sheen * 0.035;

    color = mix(sharp, color, opacity);
    fragColor = vec4(color, coverage * ColorModulator.a);
}
