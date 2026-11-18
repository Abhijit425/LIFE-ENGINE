#include "common.glsl"
// GPU-placed grass: each instance is one tuft on a camera-centred grid. Height and density come
// from the world textures, so no per-frame CPU work is needed.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
layout(location = 3) in vec4 aAux;
uniform sampler2D uHeight;
uniform sampler2D uFields;   // moisture, grass, sand, mud
uniform vec2 uWorld;         // size, half
uniform float uSpacing;
uniform int uBlock;          // cells per block side
uniform vec2 uBlockCell;     // grid cell index of this block's corner
uniform vec3 uFade;          // inner fade start, inner fade end (0,0 = none), outer radius
out vec3 vWorld;
out vec3 vNormal;
out vec3 vColor;
out vec4 vAux;

void main() {
    int ix = gl_InstanceID % uBlock, iz = gl_InstanceID / uBlock;
    vec2 cell = uBlockCell + vec2(float(ix), float(iz));
    float h1 = hash12(cell), h2 = hash12(cell + 17.3), h3 = hash12(cell * 1.7 + 3.1);
    vec2 pos = (cell + vec2(h1, h2)) * uSpacing;
    vec2 uv = (pos + uWorld.y) / uWorld.x;
    vec4 f = texture(uFields, uv);
    float ground = texture(uHeight, uv).r;
    float dist = length(pos - uCamPos.xz);
    float fade = (1.0 - smoothstep(uFade.z * 0.7, uFade.z, dist)) * (uFade.y > 0.0 ? smoothstep(uFade.x, uFade.y, dist) : 1.0);
    float keep = step(h3, f.g * 1.15);
    float sc = (0.65 + 0.7 * h1) * fade * keep * smoothstep(0.05, 0.35, f.g);
    if (sc < 0.02 || ground < 0.2) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        vWorld = vec3(0.0); vNormal = vec3(0.0, 1.0, 0.0); vColor = vec3(0.0); vAux = vec4(0.0);
        return;
    }
    float r = h2 * 6.2832;
    float c = cos(r), s = sin(r);
    vec3 p = aPos * vec3(1.0, sc, 1.0) * (0.8 + 0.4 * h3);
    p = vec3(c * p.x - s * p.z, p.y, s * p.x + c * p.z);
    vec3 n = vec3(c * aNormal.x - s * aNormal.z, aNormal.y, s * aNormal.x + c * aNormal.z);
    float w = aAux.z;
    float wave = sin(uTime * 2.1 + pos.x * 0.17 + pos.y * 0.11) * 0.5 + 0.6;
    float gust = 0.5 + 0.5 * sin(uTime * 0.6 + pos.x * 0.013 - pos.y * 0.017);
    p.xz += uWind.xy * w * (0.12 + uWind.z * 0.55) * (wave * 0.6 + gust * 0.6);
    vec3 wp = vec3(pos.x, ground, pos.y) + p;

    float moist = f.r;
    vec3 tintDry = vec3(1.15, 0.92, 0.55), tintGreen = vec3(0.55, 0.85, 0.32);
    vColor = aColor * mix(tintDry, tintGreen, smoothstep(0.42, 0.85, moist)) * (0.8 + 0.4 * h3);
    vWorld = wp;
    vNormal = n;
    vAux = aAux;
    gl_Position = uViewProj * vec4(wp, 1.0);
}
