#include "common.glsl"
in vec3 vWorld;
in float vBody;
out vec4 fragColor;

uniform sampler2D uWaterNormal;
uniform sampler2D uHeight;      // R16F terrain height
uniform vec2 uWorld;            // size, half size
uniform sampler2D uReflection;  // planar reflection (exposure-scaled linear)
uniform float uUseReflection;
uniform vec2 uViewport;
uniform vec3 uFallFoot;         // waterfall plunge point

void main() {
    vec3 wp = vWorld;
    float terrainH = texture(uHeight, (wp.xz + uWorld.y) / uWorld.x).r;
    float depth = max(wp.y - terrainH, 0.0);

    // two scrolling normal maps, flow follows the wind
    vec2 wdir = uWind.xy;
    float calm = 0.25 + uWind.z;
    vec2 uv = wp.xz / 19.0;
    vec3 a = texture(uWaterNormal, uv + wdir * uTime * 0.018).xyz * 2.0 - 1.0;
    vec3 b = texture(uWaterNormal, uv * 0.41 - vec2(wdir.y, -wdir.x) * uTime * 0.011).xyz * 2.0 - 1.0;
    vec3 c = texture(uWaterNormal, uv * 3.1 + wdir * uTime * 0.05).xyz * 2.0 - 1.0;
    vec2 slope = (a.xy + b.xy * 0.8 + c.xy * 0.35) * calm * 0.55;
    // rain ripples
    slope += (texture(uWaterNormal, uv * 6.0 + vec2(uTime * 0.4, 0.0)).xy * 2.0 - 1.0) * uWetness * 0.4;
    vec3 N = normalize(vec3(slope.x, 1.0, slope.y));

    vec3 V = normalize(uCamPos - wp);
    vec3 L = lightDir();
    vec3 lc = lightColor();
    float sh = shadowFactor(wp, max(L.y, 0.0)) * cloudShadow(wp);

    float NdV = max(dot(N, V), 0.0);
    float F = 0.02 + 0.98 * pow(1.0 - NdV, 5.0);

    vec3 R = reflect(-V, N);
    vec3 refl = skyRadiance(normalize(vec3(R.x, max(R.y, 0.01), R.z)));
    if (uUseReflection > 0.5) {
        vec2 suv = gl_FragCoord.xy / uViewport + N.xz * 0.045;
        vec4 pr = texture(uReflection, suv);
        refl = mix(refl, pr.rgb * 2.0 / max(uExposure, 1e-4), pr.a);
    }

    // sun glitter
    vec3 spec = ggxSpec(N, V, L, 0.07) * lc * max(dot(N, L), 0.0) * sh;

    // water body: murky savanna green-brown; shallow water is transparent
    vec3 scatterCol = vec3(0.020, 0.032, 0.018);
    vec3 body = scatterCol * (hemiAmbient(vec3(0.0, 1.0, 0.0)) * 1.4 + lc * max(L.y, 0.0) * 0.35 * sh);
    float opacity = 1.0 - exp(-depth * 0.6);

    // foam: shoreline and waterfall plunge pool
    float fn = texture(uNoise, wp.xz / 6.0 + vec2(uTime * 0.02, uTime * 0.015)).g;
    float shoreFoam = (1.0 - smoothstep(0.0, 0.35, depth)) * smoothstep(0.62, 0.85, fn + 0.18 * sin(uTime * 1.3 + depth * 14.0 + fn * 6.0));
    float fallD = length(wp.xz - uFallFoot.xz);
    float fn2 = texture(uNoise, wp.xz / 2.3 - vec2(uTime * 0.11, uTime * 0.07)).b;
    float fallFoam = (1.0 - smoothstep(2.0, 26.0, fallD + fn2 * 8.0)) * smoothstep(0.35, 0.7, fn * 0.5 + fn2 * 0.6);
    float foam = clamp(shoreFoam * 0.35 + fallFoam * 0.85, 0.0, 1.0);
    vec3 foamCol = vec3(0.8) * (hemiAmbient(vec3(0, 1, 0)) + lc * max(L.y, 0.0) * 0.3 * sh);

    vec3 col = body * opacity * (1.0 - F) + refl * F + spec;
    float alpha = clamp(1.0 - (1.0 - opacity) * (1.0 - F), 0.0, 1.0);
    alpha = max(alpha, clamp(dot(spec, vec3(0.3)) * 0.5, 0.0, 1.0));
    col /= max(alpha, 1e-3);
    col = mix(col, foamCol, foam);
    alpha = max(alpha, foam);
    // soft edge where depth -> 0
    alpha *= smoothstep(0.0, 0.08, depth);

    col = applyFog(col, wp);
    fragColor = vec4(finalColor(col).rgb, alpha);
}
