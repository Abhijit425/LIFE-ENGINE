// ---------------------------------------------------------------------------------------------
// LIFE ENGINE — shared lighting, atmosphere, fog and shadow code. Included by every shader.
// Lighting is computed in linear HDR and tone-mapped (ACES) at the end of each fragment shader.
// ---------------------------------------------------------------------------------------------
uniform mat4 uViewProj;
uniform vec3 uCamPos;
uniform vec3 uSunDir;        // towards sun
uniform vec3 uSunColor;      // HDR radiance (0 at night)
uniform vec3 uMoonDir;
uniform vec3 uMoonColor;
uniform vec3 uAmbSky;
uniform vec3 uAmbGround;
uniform float uTime;
uniform float uExposure;
uniform vec4 uFog;           // density, height falloff, sun glow strength, unused
uniform vec4 uWind;          // dir.x, dir.y, strength (0..1), gust phase
uniform vec4 uClouds;        // coverage 0..1, altitude, scroll x, scroll y
uniform float uWetness;      // 0 dry .. 1 soaked (rain)
uniform sampler2D uSkyLut;
uniform sampler2D uNoise;

const float PI = 3.14159265;

vec3 skyRadiance(vec3 d) {
    float u = atan(d.z, d.x) * (0.5 / PI) + 0.5;
    float s = sign(d.y) * sqrt(abs(d.y));
    return texture(uSkyLut, vec2(u, 0.5 + 0.5 * s)).rgb;
}

// Main light = sun by day, moon by night (they never both dominate)
vec3 lightDir() { return uSunColor.r + uSunColor.g > 0.02 ? uSunDir : uMoonDir; }
vec3 lightColor() { return uSunColor + uMoonColor; }

vec3 hemiAmbient(vec3 n) {
    return mix(uAmbGround, uAmbSky, clamp(n.y * 0.5 + 0.5, 0.0, 1.0));
}

// GGX specular (normalised), returns specular term to multiply by light radiance * NdotL
float ggxSpec(vec3 n, vec3 v, vec3 l, float rough) {
    vec3 h = normalize(v + l);
    float a = rough * rough;
    float a2 = a * a;
    float nh = max(dot(n, h), 0.0);
    float d = nh * nh * (a2 - 1.0) + 1.0;
    float D = a2 / (PI * d * d + 1e-5);
    float nv = max(dot(n, v), 1e-3), nl = max(dot(n, l), 1e-3);
    float k = (rough + 1.0) * (rough + 1.0) / 8.0;
    float G = (nv / (nv * (1.0 - k) + k)) * (nl / (nl * (1.0 - k) + k));
    float F = 0.04 + 0.96 * pow(1.0 - max(dot(v, h), 0.0), 5.0);
    return D * G * F / (4.0 * nv * nl);
}

// Cloud shadows: project the cloud layer down the light direction onto the surface.
float cloudShadow(vec3 wp) {
    vec3 l = lightDir();
    if (l.y < 0.02) return 1.0;
    vec2 p = wp.xz + l.xz / l.y * (uClouds.y - wp.y);
    vec2 uv = p / 5200.0 + uClouds.zw;
    float n = texture(uNoise, uv).g * 0.65 + texture(uNoise, uv * 2.7).r * 0.35;
    float cover = smoothstep(1.0 - uClouds.x, 1.0 - uClouds.x + 0.25, n);
    return 1.0 - cover * 0.75;
}

// Height + distance fog with sun in-scattering ("volumetric-style" aerial perspective)
vec3 applyFog(vec3 col, vec3 wp) {
    vec3 rd = wp - uCamPos;
    float dist = length(rd);
    rd /= dist;
    float a = uFog.x, b = uFog.y;
    float camH = max(uCamPos.y, -5.0);
    float k = rd.y * b;
    float integral = abs(k) > 1e-5 ? (1.0 - exp(-dist * k)) / k : dist;
    float fog = 1.0 - exp(-a * exp(-b * camH) * integral);
    vec3 horizonDir = normalize(vec3(rd.x, max(rd.y, 0.02), rd.z));
    vec3 fogCol = skyRadiance(horizonDir);
    float sunAmt = pow(max(dot(rd, uSunDir), 0.0), 8.0);
    fogCol += uSunColor * sunAmt * uFog.z * 0.03;
    return mix(col, fogCol, fog);
}

vec3 toneMap(vec3 c) {
    c *= uExposure;
    // ACES fitted (Narkowicz)
    c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14);
    return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
}

#ifdef SHADOWS
uniform highp sampler2DShadow uShadowMap;
uniform mat4 uLightVP;
uniform float uShadowTexel;
float shadowFactor(vec3 wp, float ndl) {
    vec4 lp = uLightVP * vec4(wp, 1.0);
    vec3 p = lp.xyz / lp.w * 0.5 + 0.5;
    if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return 1.0;
    float bias = 0.0012 + 0.003 * (1.0 - ndl);
    float s = 0.0;
    for (int y = -1; y <= 1; y++)
        for (int x = -1; x <= 1; x++)
            s += texture(uShadowMap, vec3(p.xy + vec2(float(x), float(y)) * uShadowTexel * 1.25, p.z - bias));
    s /= 9.0;
    // fade out at the shadow-map edge
    vec2 e = smoothstep(0.0, 0.08, p.xy) * smoothstep(1.0, 0.92, p.xy);
    return mix(1.0, s, e.x * e.y);
}
#else
float shadowFactor(vec3 wp, float ndl) { return 1.0; }
#endif

// Final colour output. Reflection pass stores exposure-scaled linear colour (decoded by the water shader).
#ifdef REFLECTION
uniform float uClipY;
vec4 finalColor(vec3 c) { return vec4(min(c * uExposure * 0.5, vec3(1.0)), 1.0); }
#else
vec4 finalColor(vec3 c) { return vec4(toneMap(c), 1.0); }
#endif

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
