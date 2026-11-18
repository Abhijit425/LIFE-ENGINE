#include "common.glsl"
layout(location = 0) in vec4 aPos;   // x, level, z, bodyId
out vec3 vWorld;
out float vBody;
void main() {
    vec3 p = aPos.xyz;
    // gentle swell on the lake (amplitude scales with wind)
    float sw = uWind.z * 0.12;
    p.y += sw * (sin(p.x * 0.05 + uTime * 1.1) * cos(p.z * 0.043 - uTime * 0.8));
    vWorld = p;
    vBody = aPos.w;
    gl_Position = uViewProj * vec4(p, 1.0);
}
