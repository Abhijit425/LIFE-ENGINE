#include "common.glsl"
in vec2 vNdc;
out vec4 fragColor;
uniform mat4 uInvViewProj;

float cloudDensity(vec2 uv) {
    float n = texture(uNoise, uv).g * 0.62 + texture(uNoise, uv * 2.7 + 0.31).r * 0.28 + texture(uNoise, uv * 7.9).b * 0.10;
    return smoothstep(1.0 - uClouds.x, 1.0 - uClouds.x + 0.28, n);
}

void main() {
    vec4 p = uInvViewProj * vec4(vNdc, 1.0, 1.0);
    vec3 dir = normalize(p.xyz / p.w - uCamPos);
    vec3 col = skyRadiance(dir);
    float night = 1.0 - smoothstep(-0.16, 0.02, uSunDir.y);

    // stars (stereographic grid hash), fade with horizon haze and clouds later
    if (dir.y > 0.0 && night > 0.0) {
        vec2 sp = dir.xz / (1.0 + dir.y) * 260.0;
        vec2 cell = floor(sp);
        float h = hash12(cell);
        if (h > 0.985) {
            vec2 off = vec2(hash12(cell + 7.1), hash12(cell + 3.7));
            float d = length(fract(sp) - off);
            float tw = 0.65 + 0.35 * sin(uTime * (2.0 + h * 6.0) + h * 40.0);
            col += vec3(0.85, 0.9, 1.0) * smoothstep(0.09, 0.0, d) * (h - 0.985) * 70.0 * tw * night * smoothstep(0.0, 0.25, dir.y) * 0.02;
        }
        // faint milky band
        float band = exp(-pow(dot(dir, normalize(vec3(0.3, 0.2, 1.0))) * 3.5, 2.0));
        col += vec3(0.010, 0.010, 0.014) * band * texture(uNoise, sp * 0.003).r * night;
    }

    // sun disc + moon disc
    float cs = dot(dir, uSunDir);
    col += uSunColor * smoothstep(0.99985, 0.99995, cs) * 60.0;
    float cm = dot(dir, uMoonDir);
    float moon = smoothstep(0.99955, 0.99965, cm);
    if (moon > 0.0) {
        float crater = texture(uNoise, (dir.xz - uMoonDir.xz) * 30.0).r;
        col += vec3(0.9, 0.92, 1.0) * moon * (0.6 + 0.4 * crater) * 0.5 * night;
    }

    // cloud layer
    if (dir.y > 0.0) {
        float t = (uClouds.y - uCamPos.y) / dir.y;
        vec2 cp = uCamPos.xz + dir.xz * t;
        vec2 uv = cp / 5200.0 + uClouds.zw;
        float d = cloudDensity(uv);
        if (d > 0.001) {
            vec3 L = lightDir();
            float d2 = cloudDensity(uv + L.xz * 0.012);
            float lit = exp(-d2 * 2.2);
            vec3 lc = lightColor();
            float fwd = pow(max(dot(dir, L), 0.0), 5.0);
            vec3 ambC = uAmbSky * 1.15 + vec3(0.02);
            vec3 cloudCol = ambC * (1.0 - 0.45 * d) + lc * (0.11 * lit + 0.35 * fwd * (1.0 - d));
            cloudCol *= mix(1.0, 0.35, uClouds.x * uClouds.x * d);  // storm decks darken
            float fade = smoothstep(0.0, 0.10, dir.y) * exp(-t / 70000.0);
            // aerial perspective towards horizon
            cloudCol = mix(skyRadiance(normalize(vec3(dir.x, 0.05, dir.z))), cloudCol, exp(-t / 45000.0));
            col = mix(col, cloudCol, clamp(d * 1.25, 0.0, 1.0) * fade);
        }
    }
    fragColor = finalColor(col);
}
