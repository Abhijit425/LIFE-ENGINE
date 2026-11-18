#include "common.glsl"
in vec3 vWorld;
in vec2 vUv;
out vec4 fragColor;
void main() {
    // streaky falling water: noise stretched along the fall, scrolling downward and accelerating
    float speed = 0.55 + vUv.y * 1.1;
    float s1 = texture(uNoise, vec2(vUv.x * 3.0, vUv.y * 1.2 - uTime * speed * 0.6)).g;
    float s2 = texture(uNoise, vec2(vUv.x * 7.0 + 0.3, vUv.y * 2.5 - uTime * speed)).b;
    float streak = smoothstep(0.35, 0.75, s1 * 0.6 + s2 * 0.5);
    float edge = smoothstep(0.0, 0.18, vUv.x) * smoothstep(1.0, 0.82, vUv.x);
    float alpha = edge * mix(0.55, 0.95, streak) * smoothstep(0.0, 0.04, vUv.y);
    alpha *= mix(1.0, 0.75, smoothstep(0.85, 1.0, vUv.y));
    vec3 L = lightDir();
    vec3 lc = lightColor();
    vec3 base = vec3(0.55, 0.6, 0.58) * mix(0.7, 1.15, streak);
    vec3 col = base * (hemiAmbient(vec3(0.0, 0.3, 1.0)) * 1.2 + lc * (0.25 + 0.2 * max(L.z, 0.0)) * cloudShadow(vWorld));
    col = applyFog(col, vWorld);
    fragColor = vec4(finalColor(col).rgb, alpha);
}
