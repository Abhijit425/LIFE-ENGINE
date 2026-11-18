package com.abhijit.lifeengine.core;

/**
 * Builds chunked terrain meshes for one LOD level (vertex step 1, 2, 4 or 8 grid cells).
 * Each chunk owns a vertex range and an index range in one shared buffer pair, with downward
 * skirts to hide cracks between chunks of different LOD.
 * Vertex layout (TSTRIDE = 10): position(3) normal(3) moisture sand mud grass
 */
public final class TerrainMesher {
    public static final int TSTRIDE = 10;
    public static final int CHUNK_CELLS = 48;
    public static final int CHUNKS = (World.RES - 1) / CHUNK_CELLS; // 16

    public static final class Lod {
        public int step;
        public float[] vertices;
        public int[] indices;
        public int[] chunkIndexOffset = new int[CHUNKS * CHUNKS];
        public int[] chunkIndexCount = new int[CHUNKS * CHUNKS];
        /** min x,y,z, max x,y,z per chunk */
        public float[] chunkBounds = new float[CHUNKS * CHUNKS * 6];
    }

    public static Lod build(World w, int step) {
        Lod lod = new Lod();
        lod.step = step;
        int side = CHUNK_CELLS / step + 1;
        int vertsPerChunk = side * side + 4 * side;
        int idxPerChunk = (side - 1) * (side - 1) * 6 + 4 * (side - 1) * 6;
        int nChunks = CHUNKS * CHUNKS;
        float[] v = new float[nChunks * vertsPerChunk * TSTRIDE];
        int[] ix = new int[nChunks * idxPerChunk];
        float[] nrm = new float[3];
        int vc = 0, ic = 0;
        float skirt = 3f + step * 3f;
        for (int cj = 0; cj < CHUNKS; cj++) {
            for (int ci = 0; ci < CHUNKS; ci++) {
                int c = cj * CHUNKS + ci;
                int base = vc;
                float minY = 1e9f, maxY = -1e9f;
                for (int y = 0; y < side; y++) {
                    for (int x = 0; x < side; x++) {
                        int gi = ci * CHUNK_CELLS + x * step, gj = cj * CHUNK_CELLS + y * step;
                        int id = World.idx(gi, gj);
                        float h = w.height[id];
                        minY = Math.min(minY, h); maxY = Math.max(maxY, h);
                        w.normalAtCell(gi, gj, nrm);
                        put(v, vc++, -World.HALF + gi * World.CELL, h, -World.HALF + gj * World.CELL, nrm, w, id);
                    }
                }
                for (int y = 0; y < side - 1; y++) {
                    for (int x = 0; x < side - 1; x++) {
                        int a = base + y * side + x, b = a + 1, d = a + side, e = d + 1;
                        ix[ic++] = a; ix[ic++] = d; ix[ic++] = b;
                        ix[ic++] = b; ix[ic++] = d; ix[ic++] = e;
                    }
                }
                // skirts: 4 edges, copy edge vertex lowered
                int[][] edges = new int[4][side];
                for (int k = 0; k < side; k++) {
                    edges[0][k] = base + k;                         // north (y=0)
                    edges[1][k] = base + (side - 1) * side + k;     // south
                    edges[2][k] = base + k * side;                  // west
                    edges[3][k] = base + k * side + side - 1;       // east
                }
                for (int e = 0; e < 4; e++) {
                    int sBase = vc;
                    for (int k = 0; k < side; k++) {
                        int src = edges[e][k] * TSTRIDE;
                        System.arraycopy(v, src, v, vc * TSTRIDE, TSTRIDE);
                        v[vc * TSTRIDE + 1] -= skirt;
                        vc++;
                    }
                    for (int k = 0; k < side - 1; k++) {
                        int a = edges[e][k], b = edges[e][k + 1], sa = sBase + k, sb = sBase + k + 1;
                        // both windings emitted? No: choose winding per edge so skirts face outward.
                        if (e == 0 || e == 3) {
                            ix[ic++] = a; ix[ic++] = b; ix[ic++] = sa;
                            ix[ic++] = b; ix[ic++] = sb; ix[ic++] = sa;
                        } else {
                            ix[ic++] = a; ix[ic++] = sa; ix[ic++] = b;
                            ix[ic++] = b; ix[ic++] = sa; ix[ic++] = sb;
                        }
                    }
                }
                lod.chunkIndexOffset[c] = ic - idxPerChunk;
                lod.chunkIndexCount[c] = idxPerChunk;
                float x0 = -World.HALF + ci * CHUNK_CELLS * World.CELL, z0 = -World.HALF + cj * CHUNK_CELLS * World.CELL;
                float s = CHUNK_CELLS * World.CELL;
                lod.chunkBounds[c * 6] = x0; lod.chunkBounds[c * 6 + 1] = minY - skirt; lod.chunkBounds[c * 6 + 2] = z0;
                lod.chunkBounds[c * 6 + 3] = x0 + s; lod.chunkBounds[c * 6 + 4] = maxY; lod.chunkBounds[c * 6 + 5] = z0 + s;
            }
        }
        lod.vertices = v;
        lod.indices = ix;
        return lod;
    }

    private static void put(float[] v, int i, float x, float y, float z, float[] n, World w, int id) {
        int o = i * TSTRIDE;
        v[o] = x; v[o + 1] = y; v[o + 2] = z;
        v[o + 3] = n[0]; v[o + 4] = n[1]; v[o + 5] = n[2];
        v[o + 6] = w.moisture[id]; v[o + 7] = w.sand[id]; v[o + 8] = w.mud[id]; v[o + 9] = w.grass[id];
    }

    /** Water surface mesh (stride 4: x, y, z, bodyId) on an 8 m grid over flagged cells. */
    public static float[][] waterMesh(World w, int step) {
        int cells = (World.RES - 1) / step;
        float[] v = new float[(cells + 1) * (cells + 1) * 4];
        int[] remap = new int[(cells + 1) * (cells + 1)];
        java.util.Arrays.fill(remap, -1);
        int[] ix = new int[cells * cells * 6];
        int vc = 0, ic = 0;
        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int body = -1;
                for (int sj = 0; sj <= step && body < 0; sj++)
                    for (int si = 0; si <= step && body < 0; si++) {
                        int gi = Math.min(World.RES - 1, i * step + si), gj = Math.min(World.RES - 1, j * step + sj);
                        body = w.waterId[World.idx(gi, gj)];
                    }
                if (body < 0) continue;
                int[] corners = new int[4];
                for (int k = 0; k < 4; k++) {
                    int ci = i + (k & 1), cj = j + (k >> 1);
                    int key = cj * (cells + 1) + ci;
                    if (remap[key] < 0 || v[remap[key] * 4 + 3] != body) {
                        remap[key] = vc;
                        v[vc * 4] = -World.HALF + ci * step * World.CELL;
                        v[vc * 4 + 1] = w.waterLevels[body];
                        v[vc * 4 + 2] = -World.HALF + cj * step * World.CELL;
                        v[vc * 4 + 3] = body;
                        vc++;
                        if (vc * 4 >= v.length) v = java.util.Arrays.copyOf(v, v.length * 2);
                    }
                    corners[k] = remap[key];
                }
                ix[ic++] = corners[0]; ix[ic++] = corners[2]; ix[ic++] = corners[1];
                ix[ic++] = corners[1]; ix[ic++] = corners[2]; ix[ic++] = corners[3];
            }
        }
        float[] vi = java.util.Arrays.copyOf(v, vc * 4);
        float[] ii = new float[ic];
        for (int k = 0; k < ic; k++) ii[k] = ix[k];
        return new float[][]{vi, ii};
    }

    /** Waterfall ribbon (stride 5: x, y, z, u, v). */
    public static float[][] waterfallMesh(World w) {
        int cols = 6, rows = 24;
        float[] v = new float[(cols + 1) * (rows + 1) * 5];
        int vc = 0;
        for (int r = 0; r <= rows; r++) {
            float t = r / (float) rows;
            // ballistic arc: water shoots a little forward then drops
            float z = w.fallTopZ + (w.fallBottomZ - w.fallTopZ) * (float) Math.sqrt(t);
            float y = w.fallTopY + (w.fallBottomY - w.fallTopY) * t * t * 0.15f + (w.fallBottomY - w.fallTopY) * t * 0.85f;
            float width = w.fallWidth * (1f + 0.6f * t);
            for (int c = 0; c <= cols; c++) {
                float s = c / (float) cols - 0.5f;
                v[vc * 5] = w.fallX + s * width;
                v[vc * 5 + 1] = y;
                v[vc * 5 + 2] = z + 0.6f * (float) Math.sin(s * 6f) * t;
                v[vc * 5 + 3] = c / (float) cols;
                v[vc * 5 + 4] = t;
                vc++;
            }
        }
        float[] ix = new float[cols * rows * 6];
        int ic = 0;
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                int a = r * (cols + 1) + c, b = a + 1, d = a + cols + 1, e = d + 1;
                ix[ic++] = a; ix[ic++] = d; ix[ic++] = b; ix[ic++] = b; ix[ic++] = d; ix[ic++] = e;
            }
        return new float[][]{v, ix};
    }

    /** Far-horizon ring (terrain vertex layout) so the world never ends at a visible edge. */
    public static float[][] horizonRing(World w) {
        Noise n = new Noise(w.seed + 99);
        int seg = 160, rings = 26;
        float r0 = World.HALF * 0.86f, r1 = 18000f;
        float[] v = new float[seg * (rings + 1) * TSTRIDE];
        float[] h = new float[seg * (rings + 1)];
        int vc = 0;
        for (int r = 0; r <= rings; r++) {
            float t = r / (float) rings;
            float rad = r0 * (float) Math.pow(r1 / r0, t);
            for (int s2 = 0; s2 < seg; s2++) {
                float a = (float) (s2 / (double) seg * Math.PI * 2);
                float x = (float) Math.cos(a) * rad, z = (float) Math.sin(a) * rad;
                float inside = Math.max(Math.abs(x), Math.abs(z)) < World.HALF - 8 ? 1f : 0f;
                float far = 30f + 330f * (float) Math.pow(n.ridged(x / 2600f, z / 2600f, 5), 1.7f) * Noise.smoothstep(r0, r0 * 1.8f, rad)
                        + 40f * n.fbm(x / 900f, z / 900f, 3);
                float edge = Math.max(Math.abs(x), Math.abs(z)) - World.HALF;
                float blend = Noise.smoothstep(0f, 600f, edge);
                float hy = inside > 0 ? w.heightAt(x, z) - 6f : Noise.lerp(w.heightAt(x, z), far, blend);
                h[vc] = hy;
                int o = vc * TSTRIDE;
                v[o] = x; v[o + 1] = hy; v[o + 2] = z;
                v[o + 6] = 0.25f; v[o + 7] = 0; v[o + 8] = 0; v[o + 9] = 0.55f;
                vc++;
            }
        }
        // normals from neighbours
        for (int r = 0; r <= rings; r++)
            for (int s2 = 0; s2 < seg; s2++) {
                int k = r * seg + s2;
                int kl = r * seg + (s2 + seg - 1) % seg, kr = r * seg + (s2 + 1) % seg;
                int kd = Math.max(0, r - 1) * seg + s2, ku = Math.min(rings, r + 1) * seg + s2;
                float ax = v[kr * TSTRIDE] - v[kl * TSTRIDE], ay = h[kr] - h[kl], az = v[kr * TSTRIDE + 2] - v[kl * TSTRIDE + 2];
                float bx = v[ku * TSTRIDE] - v[kd * TSTRIDE], by = h[ku] - h[kd], bz = v[ku * TSTRIDE + 2] - v[kd * TSTRIDE + 2];
                float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                if (ny < 0) { nx = -nx; ny = -ny; nz = -nz; }
                float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz) + 1e-6f;
                v[k * TSTRIDE + 3] = nx / l; v[k * TSTRIDE + 4] = ny / l; v[k * TSTRIDE + 5] = nz / l;
            }
        float[] ix = new float[seg * rings * 6];
        int ic = 0;
        for (int r = 0; r < rings; r++)
            for (int s2 = 0; s2 < seg; s2++) {
                int a = r * seg + s2, b = r * seg + (s2 + 1) % seg, c = a + seg, d = b + seg;
                ix[ic++] = a; ix[ic++] = c; ix[ic++] = b; ix[ic++] = b; ix[ic++] = c; ix[ic++] = d;
            }
        return new float[][]{v, ix};
    }
}
