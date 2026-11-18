#include "common.glsl"
// Instanced static model (trees, palms, bushes, rocks).
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
layout(location = 3) in vec4 aAux;      // u, v, wind weight, material flag
layout(location = 4) in vec4 iPosRot;   // x, y, z, rotationY
layout(location = 5) in vec2 iScaleTint;
out vec3 vWorld;
out vec3 vNormal;
out vec3 vColor;
out vec4 vAux;

void main() {
    float c = cos(iPosRot.w), s = sin(iPosRot.w);
    float sc = iScaleTint.x;
    vec3 p = aPos * sc;
    p = vec3(c * p.x - s * p.z, p.y, s * p.x + c * p.z);
    vec3 n = vec3(c * aNormal.x - s * aNormal.z, aNormal.y, s * aNormal.x + c * aNormal.z);

    // wind: slow sway of the whole crown + gusts + fast leaf flutter
    float w = aAux.z;
    float phase = dot(iPosRot.xz, vec2(0.031, 0.047));
    float gust = 0.55 + 0.45 * sin(uTime * 0.7 + phase * 3.0) * sin(uTime * 0.31 + phase);
    float sway = uWind.z * w * (0.35 * sin(uTime * 1.5 + phase * 7.0) + 0.65 * gust);
    p.xz += uWind.xy * sway * 0.22 * sc * (0.6 + 0.4 * p.y / (6.0 * sc + 1.0));
    if (aAux.w > 0.5 && aAux.w < 1.5) {
        float fl = sin(uTime * (7.0 + uWind.z * 6.0) + dot(aPos, vec3(3.1, 5.3, 2.7)));
        p += n * fl * 0.06 * w * (0.3 + uWind.z);
    }

    vec3 wp = iPosRot.xyz + p;
    vWorld = wp;
    vNormal = n;
    // per-instance variation: drier / greener and brightness
    float t = iScaleTint.y;
    vec3 col = aColor;
    if (aAux.w > 0.5) col = mix(col, col * vec3(1.45, 1.15, 0.55), smoothstep(0.4, 1.0, t) * 0.8);
    vColor = col * (0.85 + 0.3 * fract(t * 7.31));
    vAux = aAux;
    gl_Position = uViewProj * vec4(wp, 1.0);
}
