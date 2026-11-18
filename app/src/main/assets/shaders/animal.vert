#include "common.glsl"
// GPU-skinned, instanced animals. Joint matrices for every instance live in an RGBA32F texture:
// row = instance's bone row, 4 texels (columns of the matrix) per joint.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
layout(location = 3) in vec4 aJoints;
layout(location = 4) in vec4 aWeights;
layout(location = 5) in float aCoat;
layout(location = 6) in vec4 iPosYaw;     // world x, y, z, yaw (forward = (sin, cos))
layout(location = 7) in vec4 iParams;     // scale, bone row, pitch, variation
uniform sampler2D uBones;
out vec3 vWorld;
out vec3 vNormal;
out vec3 vColor;
out vec3 vBind;
out float vCoat;
out float vVar;

mat4 bone(float j, int row) {
    int x = int(j + 0.5) * 4;
    return mat4(texelFetch(uBones, ivec2(x, row), 0), texelFetch(uBones, ivec2(x + 1, row), 0),
                texelFetch(uBones, ivec2(x + 2, row), 0), texelFetch(uBones, ivec2(x + 3, row), 0));
}

void main() {
    int row = int(iParams.y + 0.5);
    mat4 skin = bone(aJoints.x, row) * aWeights.x + bone(aJoints.y, row) * aWeights.y
              + bone(aJoints.z, row) * aWeights.z + bone(aJoints.w, row) * aWeights.w;
    vec3 p = (skin * vec4(aPos, 1.0)).xyz * iParams.x;
    vec3 n = mat3(skin) * aNormal;
    // body pitch (about local X), then heading (about Y)
    float cp = cos(iParams.z), sp = sin(iParams.z);
    p = vec3(p.x, cp * p.y - sp * p.z, sp * p.y + cp * p.z);
    n = vec3(n.x, cp * n.y - sp * n.z, sp * n.y + cp * n.z);
    float c = cos(iPosYaw.w), s = sin(iPosYaw.w);
    p = vec3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z);
    n = vec3(c * n.x + s * n.z, n.y, -s * n.x + c * n.z);
    vec3 wp = iPosYaw.xyz + p;
    vWorld = wp;
    vNormal = n;
    vColor = aColor;
    vBind = aPos;
    vCoat = aCoat;
    vVar = iParams.w;
    gl_Position = uViewProj * vec4(wp, 1.0);
}
