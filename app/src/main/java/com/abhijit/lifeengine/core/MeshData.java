package com.abhijit.lifeengine.core;

import java.util.Arrays;

/**
 * Growable interleaved vertex/index buffer.
 * Standard object vertex layout (STRIDE = 13 floats):
 *   position(3) normal(3) albedo-linear(3) aux(4) where aux = (u, v, windWeight, materialFlag)
 *   materialFlag: 0 = opaque bark/rock/grass, 1 = alpha-tested foliage card, 2 = solid foliage (LOD blob)
 */
public final class MeshData {
    public static final int STRIDE = 13;
    public float[] v = new float[1024 * STRIDE];
    public int[] idx = new int[2048];
    public int vCount, iCount;
    public float radius; // bounding radius around origin
    public float height;

    public int vertex(float px, float py, float pz, float nx, float ny, float nz,
                      float r, float g, float b, float u, float vv, float wind, float flag) {
        if ((vCount + 1) * STRIDE > v.length) v = Arrays.copyOf(v, v.length * 2);
        int o = vCount * STRIDE;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-6f) { nx = 0; ny = 1; nz = 0; l = 1; }
        v[o] = px; v[o + 1] = py; v[o + 2] = pz;
        v[o + 3] = nx / l; v[o + 4] = ny / l; v[o + 5] = nz / l;
        v[o + 6] = r; v[o + 7] = g; v[o + 8] = b;
        v[o + 9] = u; v[o + 10] = vv; v[o + 11] = wind; v[o + 12] = flag;
        float d = (float) Math.sqrt(px * px + py * py + pz * pz);
        if (d > radius) radius = d;
        if (py > height) height = py;
        return vCount++;
    }

    public void tri(int a, int b, int c) {
        if (iCount + 3 > idx.length) idx = Arrays.copyOf(idx, idx.length * 2);
        idx[iCount++] = a; idx[iCount++] = b; idx[iCount++] = c;
    }

    public void quad(int a, int b, int c, int d) { tri(a, b, c); tri(a, c, d); }

    public float[] vertices() { return Arrays.copyOf(v, vCount * STRIDE); }

    public int[] indices() { return Arrays.copyOf(idx, iCount); }
}
