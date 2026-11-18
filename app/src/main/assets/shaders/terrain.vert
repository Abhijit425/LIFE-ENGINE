#include "common.glsl"
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec4 aSplat;   // moisture, sand, mud, grass
out vec3 vWorld;
out vec3 vNormal;
out vec4 vSplat;
void main() {
    vWorld = aPos;
    vNormal = aNormal;
    vSplat = aSplat;
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
