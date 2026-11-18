package com.abhijit.lifeengine.core;

/** Procedural tileable textures (RGBA8, row-major). Generated once at startup. */
public final class TextureGen {
    private TextureGen() {}

    /** 4 independent tileable fBm channels at different frequencies. Used for terrain detail, clouds, rock. */
    public static byte[] noiseRGBA(int size, long seed) {
        Noise a = new Noise(seed), b = new Noise(seed + 1), c = new Noise(seed + 2), d = new Noise(seed + 3);
        byte[] out = new byte[size * size * 4];
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                float u = x / (float) size, v = y / (float) size;
                float r = a.periodicFbm(u * 4, v * 4, 4, 6);
                float g = b.periodicFbm(u * 8, v * 8, 8, 5);
                float bb = c.periodicFbm(u * 16, v * 16, 16, 4);
                // worley-like cellular in alpha (for cracked earth / pebbles)
                float wv = cellular(u, v, 12, d);
                int o = (y * size + x) * 4;
                out[o] = b8(r * 0.5f + 0.5f); out[o + 1] = b8(g * 0.5f + 0.5f);
                out[o + 2] = b8(bb * 0.5f + 0.5f); out[o + 3] = b8(wv);
            }
        return out;
    }

    private static float cellular(float u, float v, int cells, Noise n) {
        float px = u * cells, py = v * cells;
        int cx = (int) Math.floor(px), cy = (int) Math.floor(py);
        float best = 9;
        for (int j = -1; j <= 1; j++)
            for (int i = -1; i <= 1; i++) {
                int gx = cx + i, gy = cy + j;
                int wx = Math.floorMod(gx, cells), wy = Math.floorMod(gy, cells);
                float fx = gx + Rng.hash2(wx, wy, 11), fy = gy + Rng.hash2(wx, wy, 23);
                float dx = px - fx, dy = py - fy;
                best = Math.min(best, dx * dx + dy * dy);
            }
        return Noise.clamp((float) Math.sqrt(best), 0, 1);
    }

    /** Tileable water normal map: RGB = normal*0.5+0.5 (z up), A = height. */
    public static byte[] waterNormal(int size, long seed) {
        Noise n = new Noise(seed);
        float[] h = new float[size * size];
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                float u = x / (float) size, v = y / (float) size;
                float s = n.periodicFbm(u * 8, v * 8, 8, 5);
                float ridges = 1f - Math.abs(n.periodic(u * 6 + 0.3f, v * 6, 6));
                h[y * size + x] = s * 0.7f + ridges * 0.35f;
            }
        byte[] out = new byte[size * size * 4];
        float strength = 2.2f;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                float hl = h[y * size + (x + size - 1) % size], hr = h[y * size + (x + 1) % size];
                float hd = h[((y + size - 1) % size) * size + x], hu = h[((y + 1) % size) * size + x];
                float nx = (hl - hr) * strength, ny = (hd - hu) * strength, nz = 1f;
                float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                int o = (y * size + x) * 4;
                out[o] = b8(nx / l * 0.5f + 0.5f); out[o + 1] = b8(ny / l * 0.5f + 0.5f);
                out[o + 2] = b8(nz / l * 0.5f + 0.5f); out[o + 3] = b8(h[y * size + x] * 0.5f + 0.5f);
            }
        return out;
    }

    // ------------------------------------------------------------------ foliage atlas
    private static float[] rgba;
    private static int S;

    /** 4 quadrants of foliage sprites. RGB = colour multiplier (around 0.5–1), A = coverage. */
    public static byte[] leafAtlas(int size, long seed) {
        S = size;
        rgba = new float[size * size * 4];
        Rng r = new Rng(seed);
        int q = size / 2;
        // 0: acacia — twigs with clusters of tiny bipinnate leaflets
        for (int t = 0; t < 9; t++) {
            float x0 = r.range(0.15f, 0.85f) * q, y0 = r.range(0.15f, 0.85f) * q;
            float ang = r.range(0, 6.283f), len = r.range(0.25f, 0.45f) * q;
            float x1 = x0 + (float) Math.cos(ang) * len, y1 = y0 + (float) Math.sin(ang) * len;
            line(0, 0, q, x0, y0, x1, y1, 1.2f, 0.35f, 0.3f, 0.25f);
            for (int k = 0; k < 26; k++) {
                float t2 = r.nextFloat();
                float px = x0 + (x1 - x0) * t2 + r.range(-7, 7), py = y0 + (y1 - y0) * t2 + r.range(-7, 7);
                float sh = r.range(0.75f, 1.0f);
                ellipse(0, 0, q, px, py, r.range(2.0f, 3.4f), r.range(1.1f, 1.8f), r.range(0, 3.14f), 0.62f * sh, 0.78f * sh, 0.42f * sh);
            }
        }
        // 1: broad leaves (fig / fever tree)
        for (int k = 0; k < 70; k++) {
            float px = r.range(0.1f, 0.9f) * q, py = r.range(0.1f, 0.9f) * q;
            float sh = r.range(0.65f, 1.0f);
            float a = r.range(0, 3.14f);
            ellipse(q, 0, q, px, py, r.range(9, 15), r.range(4, 7), a, 0.55f * sh, 0.85f * sh, 0.45f * sh);
            line(q, 0, q, px - (float) Math.cos(a) * 9, py - (float) Math.sin(a) * 9, px + (float) Math.cos(a) * 9, py + (float) Math.sin(a) * 9, 0.6f, 0.75f * sh, 0.9f * sh, 0.55f * sh);
        }
        // 2: palm leaflets — rachis down the centre, leaflets angled outwards (u: 0.5..1 across, v along)
        for (int k = 0; k < 60; k++) {
            float t = k / 60f;
            float py = t * q, cx = q * 0.5f;
            float sh = r.range(0.8f, 1.0f);
            float len = q * 0.46f * (float) Math.sin(Math.PI * (0.1f + 0.9f * t));
            line(0, q, q, cx, py, cx - len, py + len * 0.35f, 1.3f, 0.55f * sh, 0.8f * sh, 0.45f * sh);
            line(0, q, q, cx, py, cx + len, py + len * 0.35f, 1.3f, 0.55f * sh, 0.8f * sh, 0.45f * sh);
        }
        line(0, q, q, q * 0.5f, 0, q * 0.5f, q, 2.0f, 0.7f, 0.65f, 0.4f);
        // 3: bush leaves — small rounded
        for (int k = 0; k < 160; k++) {
            float px = r.range(0.08f, 0.92f) * q, py = r.range(0.08f, 0.92f) * q;
            float sh = r.range(0.6f, 1.0f);
            ellipse(q, q, q, px, py, r.range(3.5f, 6f), r.range(2.5f, 4f), r.range(0, 3.14f), 0.55f * sh, 0.75f * sh, 0.45f * sh);
        }
        byte[] out = new byte[size * size * 4];
        for (int k = 0; k < out.length; k++) out[k] = b8(rgba[k]);
        rgba = null;
        return out;
    }

    private static void plot(int ox, int oy, int q, int x, int y, float r, float g, float b, float a) {
        if (x < 1 || y < 1 || x >= q - 1 || y >= q - 1) return;
        int o = ((oy + y) * S + ox + x) * 4;
        float old = rgba[o + 3];
        float na = Math.max(old, a);
        float k = a >= old ? 1f : a;
        rgba[o] = rgba[o] * (1 - k) + r * k; rgba[o + 1] = rgba[o + 1] * (1 - k) + g * k; rgba[o + 2] = rgba[o + 2] * (1 - k) + b * k;
        rgba[o + 3] = na;
    }

    private static void ellipse(int ox, int oy, int q, float cx, float cy, float rx, float ry, float ang, float r, float g, float b) {
        float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
        int R = (int) Math.ceil(Math.max(rx, ry)) + 1;
        for (int y = (int) cy - R; y <= cy + R; y++)
            for (int x = (int) cx - R; x <= cx + R; x++) {
                float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                float u = (dx * c + dy * s) / rx, v = (-dx * s + dy * c) / ry;
                float d = u * u + v * v;
                if (d < 1.15f) {
                    float a = Noise.clamp((1.15f - d) * 4f, 0, 1);
                    float shade = 0.85f + 0.15f * (1 - d) + 0.08f * u;
                    plot(ox, oy, q, x, y, r * shade, g * shade, b * shade, a);
                }
            }
    }

    private static void line(int ox, int oy, int q, float x0, float y0, float x1, float y1, float w, float r, float g, float b) {
        float len = (float) Math.hypot(x1 - x0, y1 - y0);
        int steps = (int) (len * 2) + 1;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float px = x0 + (x1 - x0) * t, py = y0 + (y1 - y0) * t;
            int R = (int) Math.ceil(w) + 1;
            for (int y = (int) py - R; y <= py + R; y++)
                for (int x = (int) px - R; x <= px + R; x++) {
                    float d = (float) Math.hypot(x + 0.5f - px, y + 0.5f - py);
                    if (d < w) plot(ox, oy, q, x, y, r, g, b, Noise.clamp((w - d) * 1.5f, 0, 1));
                }
        }
    }

    private static byte b8(float v) { return (byte) Math.max(0, Math.min(255, Math.round(v * 255f))); }

    /** Pack world fields into an RGBA8 map: R moisture, G grass density, B sand, A mud. */
    public static byte[] worldFields(World w) {
        int n = World.RES * World.RES;
        byte[] out = new byte[n * 4];
        for (int k = 0; k < n; k++) {
            out[k * 4] = b8(w.moisture[k]); out[k * 4 + 1] = b8(w.grass[k]);
            out[k * 4 + 2] = b8(w.sand[k]); out[k * 4 + 3] = b8(w.mud[k]);
        }
        return out;
    }

    /** Heights as IEEE half floats (R16F). */
    public static short[] heightHalf(World w) {
        short[] out = new short[w.height.length];
        for (int k = 0; k < out.length; k++) out[k] = World.floatToHalf(w.height[k]);
        return out;
    }
}
