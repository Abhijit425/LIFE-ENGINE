package com.abhijit.lifeengine.core;

/**
 * Seeded 2D simplex noise, periodic gradient noise (for tileable textures), 3D value noise
 * and fractal helpers. Pure Java — no Android dependencies, so it is unit-testable on the desktop.
 */
public final class Noise {
    private final short[] perm = new short[512];
    private static final float[] GX = {1, -1, 1, -1, 1, -1, 0, 0, 0.7071f, -0.7071f, 0.7071f, -0.7071f};
    private static final float[] GY = {0, 0, 1, -1, 0, 0, 1, -1, 0.7071f, 0.7071f, -0.7071f, -0.7071f};
    private static final float F2 = 0.36602540378f; // (sqrt(3)-1)/2
    private static final float G2 = 0.2113248654f;  // (3-sqrt(3))/6

    public Noise(long seed) {
        short[] p = new short[256];
        for (short i = 0; i < 256; i++) p[i] = i;
        Rng r = new Rng(seed);
        for (int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            short t = p[i]; p[i] = p[j]; p[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    private static int fastFloor(float x) { int xi = (int) x; return x < xi ? xi - 1 : xi; }

    /** Simplex noise, roughly in [-1, 1]. */
    public float simplex(float x, float y) {
        float s = (x + y) * F2;
        int i = fastFloor(x + s), j = fastFloor(y + s);
        float t = (i + j) * G2;
        float x0 = x - (i - t), y0 = y - (j - t);
        int i1 = x0 > y0 ? 1 : 0, j1 = 1 - i1;
        float x1 = x0 - i1 + G2, y1 = y0 - j1 + G2;
        float x2 = x0 - 1f + 2f * G2, y2 = y0 - 1f + 2f * G2;
        int ii = i & 255, jj = j & 255;
        float n = 0f;
        float t0 = 0.5f - x0 * x0 - y0 * y0;
        if (t0 > 0) { int g = perm[ii + perm[jj]] % 12; t0 *= t0; n += t0 * t0 * (GX[g] * x0 + GY[g] * y0); }
        float t1 = 0.5f - x1 * x1 - y1 * y1;
        if (t1 > 0) { int g = perm[ii + i1 + perm[jj + j1]] % 12; t1 *= t1; n += t1 * t1 * (GX[g] * x1 + GY[g] * y1); }
        float t2 = 0.5f - x2 * x2 - y2 * y2;
        if (t2 > 0) { int g = perm[ii + 1 + perm[jj + 1]] % 12; t2 *= t2; n += t2 * t2 * (GX[g] * x2 + GY[g] * y2); }
        return 70f * n;
    }

    public float fbm(float x, float y, int octaves) {
        float sum = 0, amp = 0.5f, f = 1, norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += amp * simplex(x * f + o * 17.3f, y * f - o * 9.1f);
            norm += amp; amp *= 0.5f; f *= 2.03f;
        }
        return sum / norm;
    }

    /** Ridged multifractal in [0,1] — sharp mountain crests. */
    public float ridged(float x, float y, int octaves) {
        float sum = 0, amp = 0.5f, f = 1, norm = 0, prev = 1;
        for (int o = 0; o < octaves; o++) {
            float n = 1f - Math.abs(simplex(x * f + o * 31.7f, y * f + o * 7.7f));
            n *= n;
            sum += n * amp * prev;
            prev = n;
            norm += amp; amp *= 0.5f; f *= 2.1f;
        }
        return sum / norm;
    }

    // ---------- periodic gradient noise (tileable) ----------
    private float grad(int ix, int iy, int period, float dx, float dy) {
        ix = Math.floorMod(ix, period); iy = Math.floorMod(iy, period);
        int h = perm[(perm[ix & 255] + iy) & 511] & 7;
        float a = h * 0.785398f;
        return (float) Math.cos(a) * dx + (float) Math.sin(a) * dy;
    }

    private static float fade(float t) { return t * t * t * (t * (t * 6 - 15) + 10); }

    /** Perlin noise tileable with integer period (in lattice units). Range ~[-1,1]. */
    public float periodic(float x, float y, int period) {
        int x0 = fastFloor(x), y0 = fastFloor(y);
        float fx = x - x0, fy = y - y0;
        float u = fade(fx), v = fade(fy);
        float n00 = grad(x0, y0, period, fx, fy);
        float n10 = grad(x0 + 1, y0, period, fx - 1, fy);
        float n01 = grad(x0, y0 + 1, period, fx, fy - 1);
        float n11 = grad(x0 + 1, y0 + 1, period, fx - 1, fy - 1);
        float a = n00 + u * (n10 - n00), b = n01 + u * (n11 - n01);
        return (a + v * (b - a)) * 1.414f;
    }

    public float periodicFbm(float x, float y, int period, int octaves) {
        float sum = 0, amp = 0.5f, norm = 0; int p = period; float f = 1;
        for (int o = 0; o < octaves; o++) {
            sum += amp * periodic(x * f, y * f, p);
            norm += amp; amp *= 0.5f; f *= 2; p *= 2;
        }
        return sum / norm;
    }

    // ---------- 3D value noise (rock displacement) ----------
    private float hash3(int x, int y, int z) {
        int h = perm[(perm[(perm[x & 255] + y) & 511] + z) & 511];
        return h / 127.5f - 1f;
    }

    public float value3(float x, float y, float z) {
        int x0 = fastFloor(x), y0 = fastFloor(y), z0 = fastFloor(z);
        float fx = fade(x - x0), fy = fade(y - y0), fz = fade(z - z0);
        float c000 = hash3(x0, y0, z0), c100 = hash3(x0 + 1, y0, z0);
        float c010 = hash3(x0, y0 + 1, z0), c110 = hash3(x0 + 1, y0 + 1, z0);
        float c001 = hash3(x0, y0, z0 + 1), c101 = hash3(x0 + 1, y0, z0 + 1);
        float c011 = hash3(x0, y0 + 1, z0 + 1), c111 = hash3(x0 + 1, y0 + 1, z0 + 1);
        float a = lerp(lerp(c000, c100, fx), lerp(c010, c110, fx), fy);
        float b = lerp(lerp(c001, c101, fx), lerp(c011, c111, fx), fy);
        return lerp(a, b, fz);
    }

    public float fbm3(float x, float y, float z, int octaves) {
        float sum = 0, amp = 0.5f, f = 1, norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += amp * value3(x * f, y * f, z * f);
            norm += amp; amp *= 0.5f; f *= 2.02f;
        }
        return sum / norm;
    }

    public static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    public static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
