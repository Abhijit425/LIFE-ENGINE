package com.abhijit.lifeengine.core;

import java.util.HashMap;

/**
 * Procedural environment models (Phase 1). Every model has a detailed LOD0 (alpha-tested foliage cards)
 * and a light LOD1 (solid canopy shells) used at distance. Albedo colours are linear-space.
 * Leaf atlas quadrants: 0 acacia leaflets, 1 broad leaves, 2 palm leaflets, 3 bush leaves.
 */
public final class ModelFactory {
    private ModelFactory() {}

    // ------------------------------------------------------------------ primitives
    /** Tapered tube between a and b. Wind weight interpolates wa→wb. */
    static void tube(MeshData m, float ax, float ay, float az, float bx, float by, float bz,
                     float r0, float r1, int segs, int rings, float[] col, float wa, float wb, Rng rng) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= len; dy /= len; dz /= len;
        // orthonormal basis
        float ux = 0, uy = 1, uz = 0;
        if (Math.abs(dy) > 0.9f) { ux = 1; uy = 0; }
        float tx = uy * dz - uz * dy, ty = uz * dx - ux * dz, tz = ux * dy - uy * dx;
        float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz); tx /= tl; ty /= tl; tz /= tl;
        float sx = dy * tz - dz * ty, sy = dz * tx - dx * tz, sz = dx * ty - dy * tx;
        int base = m.vCount;
        for (int r = 0; r <= rings; r++) {
            float t = r / (float) rings;
            float rad = r0 + (r1 - r0) * t;
            float cx = ax + (bx - ax) * t, cy = ay + (by - ay) * t, cz = az + (bz - az) * t;
            float w = wa + (wb - wa) * t;
            for (int s = 0; s <= segs; s++) {
                float a = (float) (s / (double) segs * Math.PI * 2);
                float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                float nx = tx * ca + sx * sa, ny = ty * ca + sy * sa, nz = tz * ca + sz * sa;
                float bump = 1f + 0.08f * (rng.nextFloat() - 0.5f);
                float shade = 0.85f + 0.3f * rng.nextFloat();
                m.vertex(cx + nx * rad * bump, cy + ny * rad * bump, cz + nz * rad * bump, nx, ny, nz,
                        col[0] * shade, col[1] * shade, col[2] * shade, s / (float) segs, t, w, 0);
            }
        }
        for (int r = 0; r < rings; r++)
            for (int s = 0; s < segs; s++) {
                int a = base + r * (segs + 1) + s, b = a + segs + 1;
                m.quad(a, a + 1, b + 1, b);
            }
    }

    /** One alpha-tested foliage card centred at (cx,cy,cz). Normal is bent towards (ox,oy,oz) outward. */
    static void card(MeshData m, float cx, float cy, float cz, float size, int quad, float ox, float oy, float oz,
                     float[] col, float wind, Rng rng, float upBias) {
        // random card orientation, biased to face outward/up
        float nx = rng.range(-1, 1) + ox * 1.2f, ny = rng.range(-1, 1) + oy * 1.2f + upBias, nz = rng.range(-1, 1) + oz * 1.2f;
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz); nx /= nl; ny /= nl; nz /= nl;
        float ux = 0, uy = 1, uz = 0;
        if (Math.abs(ny) > 0.9f) { ux = 1; uy = 0; }
        float tx = uy * nz - uz * ny, ty = uz * nx - ux * nz, tz = ux * ny - uy * nx;
        float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz); tx /= tl; ty /= tl; tz /= tl;
        float bx = ny * tz - nz * ty, by = nz * tx - nx * tz, bz = nx * ty - ny * tx;
        float rot = rng.range(0, 6.283f), c = (float) Math.cos(rot), s = (float) Math.sin(rot);
        float t2x = tx * c + bx * s, t2y = ty * c + by * s, t2z = tz * c + bz * s;
        float b2x = bx * c - tx * s, b2y = by * c - ty * s, b2z = bz * c - tz * s;
        float h = size * 0.5f;
        float u0 = (quad % 2) * 0.5f + 0.01f, v0 = (quad / 2) * 0.5f + 0.01f, du = 0.48f;
        // shading normal: mostly the canopy-outward direction (soft, volumetric look)
        float snx = ox * 0.75f + nx * 0.25f, sny = oy * 0.75f + ny * 0.25f, snz = oz * 0.75f + nz * 0.25f;
        float shade = 0.8f + 0.4f * rng.nextFloat();
        float r = col[0] * shade, g = col[1] * shade, b = col[2] * shade;
        int a0 = m.vertex(cx - t2x * h - b2x * h, cy - t2y * h - b2y * h, cz - t2z * h - b2z * h, snx, sny, snz, r, g, b, u0, v0 + du, wind, 1);
        int a1 = m.vertex(cx + t2x * h - b2x * h, cy + t2y * h - b2y * h, cz + t2z * h - b2z * h, snx, sny, snz, r, g, b, u0 + du, v0 + du, wind, 1);
        int a2 = m.vertex(cx + t2x * h + b2x * h, cy + t2y * h + b2y * h, cz + t2z * h + b2z * h, snx, sny, snz, r, g, b, u0 + du, v0, wind, 1);
        int a3 = m.vertex(cx - t2x * h + b2x * h, cy - t2y * h + b2y * h, cz - t2z * h + b2z * h, snx, sny, snz, r, g, b, u0, v0, wind, 1);
        m.quad(a0, a1, a2, a3);
    }

    // icosphere cache ------------------------------------------------------------
    private static float[][] icoVerts;
    private static int[] icoTris;
    private static int icoLevel = -1;

    private static synchronized void ico(int level) {
        if (icoLevel == level) return;
        float t = (float) ((1 + Math.sqrt(5)) / 2);
        java.util.ArrayList<float[]> vs = new java.util.ArrayList<>();
        float[][] base = {{-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0}, {0, -1, t}, {0, 1, t}, {0, -1, -t}, {0, 1, -t},
                {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}};
        for (float[] b : base) vs.add(norm(b));
        int[] f = {0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11, 1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1, 8,
                3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9, 4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1};
        for (int l = 0; l < level; l++) {
            HashMap<Long, Integer> mid = new HashMap<>();
            int[] nf = new int[f.length * 4];
            int o = 0;
            for (int k = 0; k < f.length; k += 3) {
                int a = f[k], b = f[k + 1], c = f[k + 2];
                int ab = midpoint(vs, mid, a, b), bc = midpoint(vs, mid, b, c), ca = midpoint(vs, mid, c, a);
                int[] add = {a, ab, ca, b, bc, ab, c, ca, bc, ab, bc, ca};
                System.arraycopy(add, 0, nf, o, 12); o += 12;
            }
            f = nf;
        }
        icoVerts = vs.toArray(new float[0][]);
        icoTris = f;
        icoLevel = level;
    }

    private static int midpoint(java.util.ArrayList<float[]> vs, HashMap<Long, Integer> mid, int a, int b) {
        long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
        Integer e = mid.get(key);
        if (e != null) return e;
        float[] p = vs.get(a), q = vs.get(b);
        vs.add(norm(new float[]{(p[0] + q[0]) / 2, (p[1] + q[1]) / 2, (p[2] + q[2]) / 2}));
        mid.put(key, vs.size() - 1);
        return vs.size() - 1;
    }

    private static float[] norm(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new float[]{v[0] / l, v[1] / l, v[2] / l};
    }

    interface Displace { float r(float x, float y, float z); }

    /** Displaced ellipsoid with smooth recomputed normals. */
    static void blob(MeshData m, int level, float cx, float cy, float cz, float rx, float ry, float rz,
                     Displace d, float[] col, float colVar, float windBase, float windTop, float flag, Rng rng, float flattenBelow) {
        ico(level);
        int base = m.vCount;
        int n = icoVerts.length;
        float[] pos = new float[n * 3];
        for (int k = 0; k < n; k++) {
            float[] p = icoVerts[k];
            float s = d == null ? 1f : d.r(p[0], p[1], p[2]);
            float y = p[1] * ry * s;
            if (y < flattenBelow * ry) y = flattenBelow * ry + (y - flattenBelow * ry) * 0.15f;
            pos[k * 3] = cx + p[0] * rx * s; pos[k * 3 + 1] = cy + y; pos[k * 3 + 2] = cz + p[2] * rz * s;
        }
        float[] nrm = new float[n * 3];
        for (int k = 0; k < icoTris.length; k += 3) {
            int a = icoTris[k], b = icoTris[k + 1], c = icoTris[k + 2];
            float e1x = pos[b * 3] - pos[a * 3], e1y = pos[b * 3 + 1] - pos[a * 3 + 1], e1z = pos[b * 3 + 2] - pos[a * 3 + 2];
            float e2x = pos[c * 3] - pos[a * 3], e2y = pos[c * 3 + 1] - pos[a * 3 + 1], e2z = pos[c * 3 + 2] - pos[a * 3 + 2];
            float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
            for (int v : new int[]{a, b, c}) { nrm[v * 3] += nx; nrm[v * 3 + 1] += ny; nrm[v * 3 + 2] += nz; }
        }
        float minY = cy - ry, maxY = cy + ry;
        for (int k = 0; k < n; k++) {
            float t = (pos[k * 3 + 1] - minY) / (maxY - minY);
            float shade = 1f + colVar * (rng.nextFloat() - 0.5f);
            float ao = 0.55f + 0.45f * Noise.clamp(t, 0, 1);
            m.vertex(pos[k * 3], pos[k * 3 + 1], pos[k * 3 + 2], nrm[k * 3], nrm[k * 3 + 1], nrm[k * 3 + 2],
                    col[0] * shade * ao, col[1] * shade * ao, col[2] * shade * ao, -1, -1,
                    windBase + (windTop - windBase) * Noise.clamp(t, 0, 1), flag);
        }
        for (int k = 0; k < icoTris.length; k += 3) m.tri(base + icoTris[k], base + icoTris[k + 1], base + icoTris[k + 2]);
    }

    // ------------------------------------------------------------------ models
    static final float[] BARK_ACACIA = {0.085f, 0.06f, 0.045f};
    static final float[] LEAF_ACACIA = {0.11f, 0.16f, 0.045f};
    static final float[] BARK_FEVER = {0.20f, 0.20f, 0.10f};
    static final float[] LEAF_FIG = {0.075f, 0.10f, 0.04f};
    static final float[] BARK_BAOBAB = {0.28f, 0.22f, 0.19f};
    static final float[] LEAF_PALM = {0.12f, 0.17f, 0.06f};
    static final float[] BARK_PALM = {0.16f, 0.12f, 0.08f};
    static final float[] LEAF_BUSH = {0.07f, 0.1f, 0.035f};

    public static MeshData acacia(int lod, long seed) {
        Rng rng = new Rng(seed);
        MeshData m = new MeshData();
        float trunkH = 2.4f, canopyY = 6.4f, R = 5.6f;
        tube(m, 0, -0.5f, 0, 0.15f, trunkH, 0.1f, 0.34f, 0.26f, lod == 0 ? 9 : 6, 3, BARK_ACACIA, 0, 0.05f, rng);
        int branches = lod == 0 ? 4 : 3;
        float[] tips = new float[branches * 3];
        for (int b = 0; b < branches; b++) {
            float a = b * 6.283f / branches + rng.range(-0.4f, 0.4f);
            float r = rng.range(2.2f, 3.4f);
            float tx = (float) Math.cos(a) * r, tz = (float) Math.sin(a) * r, ty = canopyY - rng.range(0.4f, 0.9f);
            tube(m, 0.15f, trunkH - 0.2f, 0.1f, tx, ty, tz, 0.2f, 0.08f, lod == 0 ? 6 : 4, 2, BARK_ACACIA, 0.05f, 0.35f, rng);
            tips[b * 3] = tx; tips[b * 3 + 1] = ty; tips[b * 3 + 2] = tz;
            if (lod == 0) {
                for (int s = 0; s < 2; s++) {
                    float a2 = a + rng.range(-1.1f, 1.1f), r2 = R * rng.range(0.55f, 0.9f);
                    tube(m, tx, ty, tz, (float) Math.cos(a2) * r2, canopyY - 0.2f, (float) Math.sin(a2) * r2, 0.07f, 0.03f, 4, 1, BARK_ACACIA, 0.35f, 0.6f, rng);
                }
            }
        }
        if (lod == 0) {
            for (int k = 0; k < 300; k++) {
                float a = rng.range(0, 6.283f), rr = (float) Math.sqrt(rng.nextFloat()) * R;
                float edge = rr / R;
                float x = (float) Math.cos(a) * rr * 1.05f, z = (float) Math.sin(a) * rr * 0.95f;
                float y = canopyY + rng.range(-0.45f, 0.55f) * (1.1f - 0.6f * edge * edge) - 0.35f * edge * edge;
                float ox = x * 0.35f, oy = 1.6f, oz = z * 0.35f;
                card(m, x, y, z, rng.range(1.5f, 2.2f), 0, ox, oy, oz, LEAF_ACACIA, 0.6f + 0.4f * edge, rng, 0.8f);
            }
            // thin underside shell so the flat crown reads solid from below
            blob(m, 2, 0, canopyY - 0.1f, 0, R * 0.85f, 0.35f, R * 0.8f, null, scale(LEAF_ACACIA, 0.55f), 0.2f, 0.5f, 0.7f, 2, rng, -2f);
        } else {
            Noise nz = new Noise(seed);
            blob(m, 2, 0, canopyY, 0, R, 0.75f, R * 0.92f, (x, y, z) -> 1f + 0.18f * nz.fbm3(x * 2.1f, y * 2.1f, z * 2.1f, 3),
                    LEAF_ACACIA, 0.25f, 0.5f, 0.8f, 2, rng, -2f);
        }
        return m;
    }

    public static MeshData riverine(int lod, long seed) {
        Rng rng = new Rng(seed);
        MeshData m = new MeshData();
        float H = 6.5f;
        tube(m, 0, -0.6f, 0, 0.3f, H, -0.2f, 0.55f, 0.35f, lod == 0 ? 10 : 6, 4, BARK_FEVER, 0, 0.1f, rng);
        int clusters = 6;
        Noise nz = new Noise(seed + 7);
        for (int c = 0; c < clusters; c++) {
            float a = c * 6.283f / (clusters - 1) + rng.range(-0.3f, 0.3f);
            float d = c == 0 ? 0 : rng.range(2.8f, 4.6f);
            float cx = (float) Math.cos(a) * d, cz = (float) Math.sin(a) * d;
            float cy = c == 0 ? H + 5.2f : H + rng.range(2.2f, 4.2f);
            float cr = c == 0 ? 4.2f : rng.range(3.0f, 3.9f);
            tube(m, 0.3f, H - 0.4f, -0.2f, cx * 0.8f, cy - cr * 0.3f, cz * 0.8f, 0.28f, 0.1f, lod == 0 ? 6 : 4, 2, BARK_FEVER, 0.1f, 0.4f, rng);
            float[] inner = scale(LEAF_FIG, 0.5f);
            if (lod == 0) {
                blob(m, 1, cx, cy, cz, cr * 0.5f, cr * 0.42f, cr * 0.5f, null, inner, 0.2f, 0.45f, 0.7f, 2, rng, -2f);
                int cards = c == 0 ? 120 : 85;
                for (int k = 0; k < cards; k++) {
                    float px = rng.range(-1, 1), py = rng.range(-0.5f, 1), pz = rng.range(-1, 1);
                    float l = (float) Math.sqrt(px * px + py * py + pz * pz) + 1e-4f;
                    float rr = cr * (float) Math.sqrt(rng.range(0.25f, 1.0f));
                    float x = cx + px / l * rr, y = cy + py / l * rr * 0.8f, z = cz + pz / l * rr;
                    card(m, x, y, z, rng.range(1.8f, 2.8f), 1, px / l, py / l + 0.3f, pz / l, LEAF_FIG, 0.75f, rng, 0.2f);
                }
            } else {
                final float fcx = cx, fcz = cz;
                blob(m, 1, cx, cy, cz, cr, cr * 0.85f, cr, (x, y, z) -> 1f + 0.25f * nz.fbm3(x * 2.3f + fcx, y * 2.3f, z * 2.3f + fcz, 3),
                        LEAF_FIG, 0.3f, 0.45f, 0.8f, 2, rng, -2f);
            }
        }
        return m;
    }

    public static MeshData baobab(int lod, long seed) {
        Rng rng = new Rng(seed);
        MeshData m = new MeshData();
        // bottle trunk as stacked tapered tubes
        float[] ys = {-0.8f, 2.5f, 6f, 9.5f, 11f};
        float[] rs = {2.5f, 2.7f, 2.2f, 1.35f, 0.9f};
        for (int k = 0; k < ys.length - 1; k++)
            tube(m, 0, ys[k], 0, 0, ys[k + 1], 0, rs[k], rs[k + 1], lod == 0 ? 16 : 9, 2, BARK_BAOBAB, 0, 0.02f, rng);
        int br = 7;
        for (int b = 0; b < br; b++) {
            float a = b * 6.283f / br + rng.range(-0.3f, 0.3f);
            float sx = (float) Math.cos(a) * 0.6f, sz = (float) Math.sin(a) * 0.6f;
            float ex = (float) Math.cos(a) * rng.range(3.5f, 5.5f), ez = (float) Math.sin(a) * rng.range(3.5f, 5.5f);
            float ey = rng.range(12.5f, 15f);
            tube(m, sx, 10.5f, sz, ex, ey, ez, 0.55f, 0.15f, lod == 0 ? 7 : 4, 2, BARK_BAOBAB, 0.02f, 0.25f, rng);
            if (lod == 0) {
                for (int s = 0; s < 2; s++) {
                    float fx = ex + rng.range(-1.6f, 1.6f), fz = ez + rng.range(-1.6f, 1.6f), fy = ey + rng.range(0.6f, 1.8f);
                    tube(m, ex, ey, ez, fx, fy, fz, 0.14f, 0.05f, 4, 1, BARK_BAOBAB, 0.25f, 0.5f, rng);
                    for (int c = 0; c < 6; c++)
                        card(m, fx + rng.range(-0.8f, 0.8f), fy + rng.range(-0.3f, 0.6f), fz + rng.range(-0.8f, 0.8f), rng.range(1.0f, 1.6f), 1,
                                fx * 0.2f, 1, fz * 0.2f, scale(LEAF_FIG, 1.4f), 0.7f, rng, 0.5f);
                }
            } else {
                blob(m, 1, ex, ey + 0.8f, ez, 1.6f, 1.0f, 1.6f, null, scale(LEAF_FIG, 1.2f), 0.3f, 0.4f, 0.6f, 2, rng, -2f);
            }
        }
        return m;
    }

    public static MeshData palm(int lod, long seed) {
        Rng rng = new Rng(seed);
        MeshData m = new MeshData();
        float H = 10.5f;
        int seg = lod == 0 ? 6 : 3;
        float px = 0, py = -0.4f, pz = 0, lean = rng.range(0.6f, 1.6f);
        for (int s = 1; s <= seg; s++) {
            float t = s / (float) seg;
            float nx = lean * t * t, ny = H * t, nz = 0.3f * t;
            tube(m, px, py, pz, nx, ny, nz, 0.3f - 0.06f * (s - 1) / seg, 0.3f - 0.06f * s / seg, lod == 0 ? 8 : 5, 1, BARK_PALM, 0.15f * (s - 1) / seg, 0.15f * t, rng);
            px = nx; py = ny; pz = nz;
        }
        int fronds = lod == 0 ? 14 : 9;
        for (int f = 0; f < fronds; f++) {
            float a = f * 6.283f / fronds + rng.range(-0.2f, 0.2f);
            float dx = (float) Math.cos(a), dz = (float) Math.sin(a);
            float rise = rng.range(0.2f, 0.9f), L = rng.range(3.8f, 5.0f);
            int n = lod == 0 ? 6 : 3;
            int prevL = -1, prevR = -1, prevC = -1;
            for (int i = 0; i <= n; i++) {
                float t = i / (float) n;
                float x = px + dx * L * t, z = pz + dz * L * t;
                float y = py + rise * L * t - 2.4f * t * t * L / 4.5f;
                float w = 0.85f * (float) Math.sin(Math.PI * (0.15f + 0.85f * t)) + 0.08f;
                float sxp = -dz * w, szp = dx * w;
                float fold = 0.25f * w;
                float u = 0.51f, v0 = 0.51f + 0.48f * t;
                int c = m.vertex(x, y + fold, z, 0, 1, 0, LEAF_PALM[0], LEAF_PALM[1], LEAF_PALM[2], 0.75f, v0, 0.4f + 0.6f * t, 1);
                int l = m.vertex(x + sxp, y - fold * 0.3f, z + szp, -dz * 0.4f, 1, dx * 0.4f, LEAF_PALM[0], LEAF_PALM[1], LEAF_PALM[2], u, v0, 0.4f + 0.6f * t, 1);
                int r = m.vertex(x - sxp, y - fold * 0.3f, z - szp, dz * 0.4f, 1, -dx * 0.4f, LEAF_PALM[0], LEAF_PALM[1], LEAF_PALM[2], 0.99f, v0, 0.4f + 0.6f * t, 1);
                if (i > 0) { m.quad(prevL, l, c, prevC); m.quad(prevC, c, r, prevR); }
                prevL = l; prevR = r; prevC = c;
            }
        }
        return m;
    }

    public static MeshData bush(int lod, long seed) {
        Rng rng = new Rng(seed);
        MeshData m = new MeshData();
        Noise nz = new Noise(seed);
        int lumps = 3;
        for (int b = 0; b < lumps; b++) {
            float a = rng.range(0, 6.283f), d = b == 0 ? 0 : rng.range(0.5f, 0.9f);
            float cx = (float) Math.cos(a) * d, cz = (float) Math.sin(a) * d, r = rng.range(0.8f, 1.15f);
            float cy = r * 0.55f;
            if (lod == 0) {
                blob(m, 1, cx, cy, cz, r * 0.8f, r * 0.62f, r * 0.8f, null, scale(LEAF_BUSH, 0.6f), 0.25f, 0.2f, 0.45f, 2, rng, -0.5f);
                for (int k = 0; k < 28; k++) {
                    float px = rng.range(-1, 1), py = rng.range(-0.2f, 1), pz = rng.range(-1, 1);
                    float l = (float) Math.sqrt(px * px + py * py + pz * pz) + 1e-4f;
                    card(m, cx + px / l * r, cy + py / l * r * 0.75f, cz + pz / l * r, rng.range(0.6f, 0.95f), 3,
                            px / l, py / l + 0.4f, pz / l, LEAF_BUSH, 0.5f, rng, 0.3f);
                }
            } else {
                final float o = b * 3.1f;
                blob(m, 1, cx, cy, cz, r, r * 0.75f, r, (x, y, z) -> 1f + 0.25f * nz.fbm3(x * 2.5f + o, y * 2.5f, z * 2.5f, 2),
                        LEAF_BUSH, 0.3f, 0.2f, 0.5f, 2, rng, -0.5f);
            }
        }
        return m;
    }

    public static MeshData rock(int variant, int lod, long seed) {
        Rng rng = new Rng(seed + variant * 101);
        MeshData m = new MeshData();
        Noise nz = new Noise(seed + variant * 31);
        float sx = 1, sy = 0.7f, sz = 0.9f;
        float amp = 0.32f;
        switch (variant) {
            case 1: sx = 1.1f; sy = 0.85f; sz = 0.8f; amp = 0.45f; break;   // angular
            case 2: sx = 1.3f; sy = 0.38f; sz = 1.1f; amp = 0.25f; break;   // slab
            case 3: sx = 0.7f; sy = 1.1f; sz = 0.75f; amp = 0.35f; break;   // tall
        }
        final float fa = amp;
        final int fv = variant;
        Displace d = (x, y, z) -> {
            float r = 1f + fa * nz.fbm3(x * 1.4f + 3, y * 1.4f, z * 1.4f, 4) + 0.07f * nz.fbm3(x * 6f, y * 6f, z * 6f, 2);
            if (fv == 1) r = Math.round(r * 5f) / 5f * 0.7f + r * 0.3f; // faceted
            return r;
        };
        float[] col = {0.30f, 0.26f, 0.23f};
        blob(m, lod == 0 ? 3 : 2, 0, 0, 0, sx, sy, sz, d, col, 0.25f, 0, 0, 0, rng, -0.35f);
        return m;
    }

    /** Savanna grass tuft: curved blades. Height ~0.9 m. */
    public static MeshData grassTuft(long seed, int blades) {
        Rng rng = new Rng(seed);
        MeshData m = new MeshData();
        for (int b = 0; b < blades; b++) {
            float a = rng.range(0, 6.283f), r = rng.range(0f, 0.28f);
            float bx = (float) Math.cos(a) * r, bz = (float) Math.sin(a) * r;
            float dir = rng.range(0, 6.283f), dx = (float) Math.cos(dir), dz = (float) Math.sin(dir);
            float H = rng.range(0.35f, 0.8f), lean = rng.range(0.12f, 0.4f), w = rng.range(0.03f, 0.05f);
            float px = -dz, pz = dx; // width direction
            int segs = 4;
            int prevA = -1, prevB = -1;
            float tint = rng.range(0.8f, 1.2f);
            for (int s = 0; s <= segs; s++) {
                float t = s / (float) segs;
                float x = bx + dx * lean * t * t, y = H * t * (1 - 0.15f * t * lean), z = bz + dz * lean * t * t;
                float ww = w * (1 - t * 0.92f);
                float nx = dx * 0.5f, ny = 0.8f, nz2 = dz * 0.5f;
                float cr = (0.10f + 0.30f * t) * tint, cg = (0.085f + 0.24f * t) * tint, cb = (0.04f + 0.09f * t) * tint;
                int va = m.vertex(x + px * ww, y, z + pz * ww, nx, ny, nz2, cr, cg, cb, t, 0, t * t, 0);
                int vb = m.vertex(x - px * ww, y, z - pz * ww, nx, ny, nz2, cr, cg, cb, t, 1, t * t, 0);
                if (s > 0) m.quad(prevA, prevB, vb, va);
                prevA = va; prevB = vb;
            }
        }
        return m;
    }

    /** Distant grass: fewer, wider and taller blades covering a larger cell. */
    public static MeshData grassTuftFar(long seed) {
        Rng rng = new Rng(seed);
        MeshData m = new MeshData();
        for (int b = 0; b < 7; b++) {
            float a = rng.range(0, 6.283f), r = rng.range(0f, 0.7f);
            float bx = (float) Math.cos(a) * r, bz = (float) Math.sin(a) * r;
            float dir = rng.range(0, 6.283f), dx = (float) Math.cos(dir), dz = (float) Math.sin(dir);
            float H = rng.range(0.45f, 0.8f), lean = rng.range(0.15f, 0.4f), w = rng.range(0.09f, 0.14f);
            float px = -dz, pz = dx;
            int prevA = -1, prevB = -1;
            for (int s = 0; s <= 2; s++) {
                float t = s / 2f;
                float x = bx + dx * lean * t * t, y = H * t, z = bz + dz * lean * t * t;
                float ww = w * (1 - t * 0.85f);
                float cr = 0.10f + 0.30f * t, cg = 0.085f + 0.24f * t, cb = 0.04f + 0.09f * t;
                int va = m.vertex(x + px * ww, y, z + pz * ww, dx * 0.5f, 0.8f, dz * 0.5f, cr, cg, cb, t, 0, t * t, 0);
                int vb = m.vertex(x - px * ww, y, z - pz * ww, dx * 0.5f, 0.8f, dz * 0.5f, cr, cg, cb, t, 1, t * t, 0);
                if (s > 0) m.quad(prevA, prevB, vb, va);
                prevA = va; prevB = vb;
            }
        }
        return m;
    }

    static float[] scale(float[] c, float s) { return new float[]{c[0] * s, c[1] * s, c[2] * s}; }
}
