#include "common.glsl"
in vec3 vWorld;
in vec3 vNormal;
in vec3 vColor;
in vec3 vBind;
in float vCoat;
in float vVar;
out vec4 fragColor;
uniform int uPattern;         // see Species.PAT_*
uniform vec3 uPatternColor;
uniform float uPatternScale;  // model units -> pattern frequency

// cellular distance (noise texture alpha), tri-planar in bind space so patterns stick to the skin
float cells(vec3 p, vec3 n) {
    vec3 w = abs(n); w /= (w.x + w.y + w.z);
    return texture(uNoise, p.zy).a * w.x + texture(uNoise, p.xz).a * w.y + texture(uNoise, p.xy).a * w.z;
}

vec3 coat(vec3 base) {
    if (vCoat < 0.5 || uPattern == 0) return base;
    vec3 b = vBind;
    vec3 n = normalize(vNormal);
    if (uPattern == 1) {                       // zebra stripes: vertical on body/neck, horizontal on legs
        float legs = 1.0 - smoothstep(1.7, 2.1, b.y);
        float u = mix(b.z + 0.22 * sin(b.y * 2.3) + 0.05 * sin(b.z * 9.0), b.y * 1.25, legs);
        // rump: stripes sweep diagonally
        u = mix(u, b.y * 0.9 - b.z * 0.6, smoothstep(-0.6, -1.4, b.z) * (1.0 - legs));
        float f = sin(u * 22.0 * uPatternScale + 1.3);
        float aa = fwidth(f) + 0.02;
        float stripe = smoothstep(-aa, aa, f - 0.15);
        return mix(base, uPatternColor, stripe);
    }
    if (uPattern == 2 || uPattern == 4) {      // hyena / cheetah spots
        float c = cells(b * (uPattern == 2 ? 0.55 : 0.9) * uPatternScale, n);
        float spot = 1.0 - smoothstep(uPattern == 2 ? 0.26 : 0.2, uPattern == 2 ? 0.34 : 0.27, c);
        if (vCoat > 1.5) spot *= 0.25;           // pale belly keeps few spots
        return mix(base, uPatternColor, spot);
    }
    if (uPattern == 3) {                       // leopard rosettes
        float c = cells(b * 0.75 * uPatternScale, n);
        float ring = smoothstep(0.15, 0.2, c) * (1.0 - smoothstep(0.27, 0.32, c));
        vec3 centre = base * vec3(0.82, 0.68, 0.5);
        vec3 col = mix(base, centre, 1.0 - smoothstep(0.12, 0.18, c));
        if (vCoat > 1.5) ring *= 0.4;
        return mix(col, uPatternColor, ring);
    }
    if (uPattern == 5) {                       // Thomson's gazelle: dark flank band, white belly
        float side = smoothstep(0.25, 0.45, abs(b.x));
        float band = smoothstep(1.85, 1.95, b.y) * (1.0 - smoothstep(2.15, 2.25, b.y)) * side
                   * smoothstep(-1.3, -0.9, b.z) * (1.0 - smoothstep(1.0, 1.4, b.z));
        return mix(base, uPatternColor, band * 0.9);
    }
    if (uPattern == 6) {                       // lion: darker mane over neck and shoulders
        float mane = smoothstep(0.8, 1.3, b.z) * smoothstep(1.35, 1.75, b.y) * (1.0 - smoothstep(2.3, 2.6, b.z));
        float shag = texture(uNoise, b.yz * 3.0).b;
        return mix(base, uPatternColor * (0.8 + 0.5 * shag), mane * 0.85);
    }
    return base;
}

void main() {
#ifdef DEPTH_ONLY
    fragColor = vec4(1.0);
#else
#ifdef REFLECTION
    if (vWorld.y < uClipY) discard;
#endif
    vec3 wp = vWorld;
    vec3 N = normalize(vNormal);
    vec3 albedo = coat(vColor) * (0.9 + 0.2 * vVar);
    // subtle fur/hide variation
    albedo *= 0.88 + 0.24 * texture(uNoise, vBind.xz * 0.9 + vBind.y * 0.3).b;
    albedo *= mix(1.0, 0.7, uWetness);

    vec3 V = normalize(uCamPos - wp);
    vec3 L = lightDir();
    vec3 lc = lightColor();
    float ndl = max(dot(N, L), 0.0);
    float sh = shadowFactor(wp, max(ndl, 0.25)) * cloudShadow(wp);
    vec3 col = albedo * lc * (ndl * 0.85 + 0.15 * max(dot(N, L) + 0.4, 0.0)) * sh * 0.32;
    col += ggxSpec(N, V, L, 0.65) * lc * ndl * sh * 0.12;
    // soft fur rim light
    float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0);
    col += albedo * (hemiAmbient(N) * 1.4 + lc * 0.05) * rim * 0.5;
    col += albedo * hemiAmbient(N);
    col = applyFog(col, wp);
    fragColor = finalColor(col);
#endif
}
