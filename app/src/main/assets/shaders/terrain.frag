#include "common.glsl"
in vec3 vWorld;
in vec3 vNormal;
in vec4 vSplat;
out vec4 fragColor;

#ifdef DEPTH_ONLY
void main() { fragColor = vec4(1.0); }
#else
void main() {
#ifdef REFLECTION
    if (vWorld.y < uClipY) discard;
#endif
    vec3 wp = vWorld;
    float moist = vSplat.x, sandM = vSplat.y, mudM = vSplat.z, grassM = vSplat.w;

    vec4 n1 = texture(uNoise, wp.xz * (1.0 / 41.0));
    vec4 n2 = texture(uNoise, wp.xz * (1.0 / 7.3));
    vec4 n3 = texture(uNoise, wp.xz * (1.0 / 1.7));
    float macro = texture(uNoise, wp.xz * (1.0 / 613.0)).r;

    // detail bump from fine noise (finite differences)
    const float e = 0.25;
    float hb = n3.b;
    float hx = texture(uNoise, (wp.xz + vec2(e, 0.0)) * (1.0 / 1.7)).b;
    float hz = texture(uNoise, (wp.xz + vec2(0.0, e)) * (1.0 / 1.7)).b;
    vec3 N = normalize(vNormal);

    float slope = 1.0 - N.y;
    float rockM = smoothstep(0.22, 0.38, slope + (n1.r - 0.5) * 0.22);
    rockM = max(rockM, smoothstep(170.0, 280.0, wp.y + n1.r * 60.0) * 0.7);

    // --- layer albedos (linear) ---
    vec3 dryGrass = vec3(0.40, 0.31, 0.15) * (0.78 + 0.44 * n2.r);
    vec3 greenGrass = vec3(0.10, 0.15, 0.045) * (0.8 + 0.4 * n2.g);
    float greenAmt = smoothstep(0.40, 0.85, moist + (macro - 0.5) * 0.35);
    vec3 grassCol = mix(dryGrass, greenGrass, greenAmt);
    grassCol *= 0.78 + 0.42 * n3.g;                       // blade streaks

    vec3 soil = vec3(0.27, 0.15, 0.085) * (0.8 + 0.4 * n2.b);
    soil *= mix(0.72, 1.0, smoothstep(0.04, 0.22, n2.a)); // cracked earth / pebbles
    soil = mix(soil, vec3(0.33, 0.25, 0.16), smoothstep(0.55, 0.8, n1.g) * 0.5);

    float grassAmt = clamp(grassM * 1.35 - (1.0 - n2.r) * 0.45 - (1.0 - n1.g) * 0.25 + 0.15, 0.0, 1.0);
    vec3 albedo = mix(soil, grassCol, grassAmt);

    vec3 sandCol = vec3(0.56, 0.46, 0.31) * (0.88 + 0.24 * n3.r);
    albedo = mix(albedo, sandCol, smoothstep(0.2, 0.7, sandM + (n2.g - 0.5) * 0.3));
    vec3 mudCol = vec3(0.11, 0.075, 0.05) * (0.85 + 0.3 * n2.r);
    float mudAmt = smoothstep(0.2, 0.7, mudM + (n2.b - 0.5) * 0.4);
    albedo = mix(albedo, mudCol, mudAmt);

    vec4 nr = texture(uNoise, vec2(wp.x + wp.z, wp.y * 2.0) * (1.0 / 23.0));   // vertical projection for cliffs
    vec3 rockCol = vec3(0.26, 0.23, 0.20) * (0.65 + 0.6 * mix(n2.r, nr.r, slope));
    rockCol *= 0.93 + 0.08 * sin(wp.y * 0.6 + nr.g * 9.0);                      // faint strata
    rockCol = mix(rockCol, vec3(0.34, 0.27, 0.19), smoothstep(0.45, 0.75, n1.b) * 0.5); // iron staining
    rockCol *= mix(0.6, 1.0, smoothstep(0.1, 0.35, nr.a));                     // crevices
    albedo = mix(albedo, rockCol, rockM);

    float bumpAmt = mix(0.35, 1.4, rockM) * (1.0 - grassAmt * 0.6);
    N = normalize(N + vec3(hb - hx, 0.0, hb - hz) * bumpAmt);

    // --- wetness: shorelines, mud and rain ---
    float shore = 1.0 - smoothstep(0.0, 1.4, wp.y - 0.15);
    float wet = clamp(max(max(shore, mudAmt * 0.8), uWetness * (1.0 - rockM * 0.3)), 0.0, 1.0);
    albedo *= mix(1.0, 0.55, wet);
    float rough = mix(mix(0.92, 0.7, rockM), 0.18, wet);

    // underwater: absorption tint + animated caustics
    float under = clamp(-wp.y, 0.0, 12.0);
    if (wp.y < 0.0) {
        vec2 cuv = wp.xz * 0.09;
        float c1 = texture(uNoise, cuv + vec2(uTime * 0.03, uTime * 0.021)).b;
        float c2 = texture(uNoise, cuv * 1.3 - vec2(uTime * 0.025, -uTime * 0.017)).b;
        float caust = pow(1.0 - abs(c1 - c2), 8.0);
        albedo = albedo * exp(-under * vec3(0.35, 0.14, 0.16)) * (1.0 + caust * 1.5 * exp(-under * 0.4));
    }

    vec3 V = normalize(uCamPos - wp);
    vec3 L = lightDir();
    float ndl = max(dot(N, L), 0.0);
    float sh = shadowFactor(wp, ndl) * cloudShadow(wp);
    vec3 lc = lightColor();
    vec3 direct = albedo / PI * lc * ndl * sh * PI * 0.32;
    vec3 spec = ggxSpec(N, V, L, rough) * lc * ndl * sh * mix(0.08, 0.6, wet);
    float ao = mix(1.0, 0.8, grassAmt) * mix(0.7, 1.0, smoothstep(0.02, 0.2, n2.a));
    vec3 amb = albedo * hemiAmbient(N) * ao;
    // sky reflection on wet ground
    vec3 R = reflect(-V, N);
    float F = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
    vec3 env = skyRadiance(normalize(vec3(R.x, max(R.y, 0.05), R.z))) * F * wet * 0.6;

    vec3 col = direct + spec + amb + env;
    col = applyFog(col, wp);
    fragColor = finalColor(col);
}
#endif
