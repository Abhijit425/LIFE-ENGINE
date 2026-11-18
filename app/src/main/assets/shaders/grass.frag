#include "common.glsl"
in vec3 vWorld;
in vec3 vNormal;
in vec3 vColor;
in vec4 vAux;
out vec4 fragColor;
void main() {
#ifdef REFLECTION
    if (vWorld.y < uClipY) discard;
#endif
    vec3 wp = vWorld;
    vec3 N = normalize(vNormal);
    if (!gl_FrontFacing) N = -N;
    N = normalize(N + vec3(0.0, 0.6, 0.0));
    vec3 V = normalize(uCamPos - wp);
    vec3 L = lightDir();
    vec3 lc = lightColor();
    float t = vAux.x; // 0 base .. 1 tip
    vec3 albedo = vColor * mix(1.0, 0.7, uWetness);
    float sh = shadowFactor(wp, 0.6) * cloudShadow(wp);
    float diff = max((dot(N, L) + 0.5) / 1.5, 0.0);
    vec3 col = albedo * lc * diff * sh * 0.32;
    col += albedo * vec3(1.2, 1.1, 0.6) * lc * pow(max(dot(-V, L), 0.0), 3.0) * sh * 0.5 * t;
    col += albedo * hemiAmbient(N) * mix(0.45, 1.0, t);
    col = applyFog(col, wp);
    fragColor = finalColor(col);
}
