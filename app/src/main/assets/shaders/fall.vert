#include "common.glsl"
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
out vec3 vWorld;
out vec2 vUv;
void main() {
    vWorld = aPos;
    vUv = aUv;
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
