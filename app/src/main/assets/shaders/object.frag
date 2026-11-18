#include "common.glsl"
in vec3 vWorld;
in vec3 vNormal;
in vec3 vColor;
in vec4 vAux;
out vec4 fragColor;
uniform sampler2D uLeafAtlas;

float leafAlpha(out vec3 texCol) {
    vec4 t = texture(uLeafAtlas, vAux.xy);
    texCol = t.rgb;
    // keep foliage dense in lower mips, then sharpen the edge
    vec2 sz = vec2(textureSize(uLeafAtlas, 0));
    vec2 dx = dFdx(vAux.xy * sz), dy = dFdy(vAux.xy * sz);
    float mip = max(0.0, 0.5 * log2(max(dot(dx, dx), dot(dy, dy))));
    float a = t.a * (1.0 + mip * 0.28);
    return (a - 0.42) / max(fwidth(a), 1e-4) + 0.5;
}

void main() {
    float flag = vAux.w;
    vec3 texCol = vec3(1.0);
    if (flag > 0.5 && flag < 1.5) {
        if (leafAlpha(texCol) < 0.5) discard;
    }
#ifdef DEPTH_ONLY
    fragColor = vec4(1.0);
#else
#ifdef REFLECTION
    if (vWorld.y < uClipY) discard;
#endif
    vec3 wp = vWorld;
    vec3 N = normalize(vNormal);
    vec3 albedo = vColor;
    float rough = 0.8;
    bool foliage = flag > 0.5;
    if (flag > 0.5 && flag < 1.5) {
        albedo *= texCol * 1.9;
    } else if (flag > 1.5) {
        float n = texture(uNoise, wp.xz * 0.37 + wp.y * 0.21).b;
        albedo *= 0.75 + 0.5 * n;
    } else if (vAux.x < -0.5) {
        // rock: triplanar detail, lichen on top faces
        vec3 an = abs(N);
        vec3 tw = an / (an.x + an.y + an.z);
        float d = texture(uNoise, wp.yz * 0.29).b * tw.x + texture(uNoise, wp.xz * 0.29).b * tw.y + texture(uNoise, wp.xy * 0.29).b * tw.z;
        float d2 = texture(uNoise, wp.xz * 1.3).r * tw.y + texture(uNoise, wp.xy * 1.3).r * (tw.x + tw.z);
        albedo *= 0.55 + 0.75 * d * (0.8 + 0.4 * d2);
        float lichen = smoothstep(0.55, 0.8, N.y + texture(uNoise, wp.xz * 0.11).g * 0.5 - 0.2);
        albedo = mix(albedo, vec3(0.30, 0.29, 0.17), lichen * 0.5);
        rough = 0.7;
    } else {
        // bark streaks
        float n = texture(uNoise, vec2(vAux.x * 1.5, vAux.y * 9.0)).b;
        albedo *= 0.6 + 0.7 * n;
    }
    albedo *= mix(1.0, 0.65, uWetness);

    vec3 V = normalize(uCamPos - wp);
    vec3 L = lightDir();
    vec3 lc = lightColor();
    float ndl = dot(N, L);
    float diff = foliage ? max((ndl + 0.45) / 1.45, 0.0) : max(ndl, 0.0);
    float sh = shadowFactor(wp, max(ndl, 0.2)) * cloudShadow(wp);
    vec3 col = albedo * lc * diff * sh * 0.32;
    if (foliage) {
        float trans = pow(max(dot(-V, L), 0.0), 3.0);
        col += albedo * vec3(1.1, 1.25, 0.6) * lc * trans * sh * 0.45;
    }
    col += ggxSpec(N, V, L, mix(rough, 0.3, uWetness)) * lc * max(ndl, 0.0) * sh * (foliage ? 0.15 : 0.25);
    col += albedo * hemiAmbient(N) * (foliage ? 0.85 : 1.0);
    col = applyFog(col, wp);
    fragColor = finalColor(col);
#endif
}
