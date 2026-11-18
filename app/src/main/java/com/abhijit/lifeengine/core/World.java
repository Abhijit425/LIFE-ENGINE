package com.abhijit.lifeengine.core;

/**
 * Generated world data: heightfield, biome masks, water bodies and object placements.
 * Coordinates: x east, y up, z south. Origin at world centre. Units: metres.
 */
public final class World {
    public static final float SIZE = 3072f;
    public static final int RES = 769;              // samples per side (768 cells, 4 m spacing)
    public static final float CELL = SIZE / (RES - 1);
    public static final float HALF = SIZE * 0.5f;

    // Object type ids (instance lists)
    public static final int T_ACACIA = 0, T_RIVERINE = 1, T_BAOBAB = 2, T_BUSH = 3,
            T_ROCK0 = 4, T_ROCK1 = 5, T_ROCK2 = 6, T_ROCK3 = 7, T_PALM = 8, T_COUNT = 9;
    /** Floats per instance: x, y, z, rotationY, scale, tint. */
    public static final int INST_STRIDE = 6;

    public final long seed;
    public final float[] height = new float[RES * RES];
    public final float[] moisture = new float[RES * RES];
    public final float[] sand = new float[RES * RES];
    public final float[] mud = new float[RES * RES];
    public final float[] grass = new float[RES * RES];
    /** Water body id per cell: -1 none, 0 lowland water table, 1 highland pool. */
    public final byte[] waterId = new byte[RES * RES];
    public final float[] waterLevels = {0f, 46f};

    public float[] riverPolyline;     // x,z pairs
    public final float[][] instances = new float[T_COUNT][];
    public final int[] instanceCount = new int[T_COUNT];

    // Waterfall ribbon: top centre, bottom centre, width
    public float fallX, fallTopY, fallTopZ, fallBottomY, fallBottomZ, fallWidth;

    // Notable places for cameras / cinematics (x,z pairs)
    public float lakeX, lakeZ, lakeR;
    public float[] waterholes;        // x,z,r triples
    public float[] kopjes;            // x,z,r triples

    public World(long seed) { this.seed = seed; }

    public static int idx(int i, int j) { return j * RES + i; }

    public float heightAtCell(int i, int j) {
        i = i < 0 ? 0 : (i >= RES ? RES - 1 : i);
        j = j < 0 ? 0 : (j >= RES ? RES - 1 : j);
        return height[j * RES + i];
    }

    /** Bilinear height at world position. */
    public float heightAt(float x, float z) { return sample(height, x, z); }

    public float sample(float[] f, float x, float z) {
        float gx = (x + HALF) / CELL, gz = (z + HALF) / CELL;
        if (gx < 0) gx = 0; if (gz < 0) gz = 0;
        if (gx > RES - 1.001f) gx = RES - 1.001f; if (gz > RES - 1.001f) gz = RES - 1.001f;
        int i = (int) gx, j = (int) gz;
        float fx = gx - i, fz = gz - j;
        int k = j * RES + i;
        float a = f[k] + (f[k + 1] - f[k]) * fx;
        float b = f[k + RES] + (f[k + RES + 1] - f[k + RES]) * fx;
        return a + (b - a) * fz;
    }

    /** Surface normal (unit) at a cell via central differences. Writes into out[0..2]. */
    public void normalAtCell(int i, int j, float[] out) {
        float hl = heightAtCell(i - 1, j), hr = heightAtCell(i + 1, j);
        float hd = heightAtCell(i, j - 1), hu = heightAtCell(i, j + 1);
        float nx = hl - hr, ny = 2f * CELL, nz = hd - hu;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        out[0] = nx / l; out[1] = ny / l; out[2] = nz / l;
    }

    public float slopeAt(float x, float z) {
        float e = CELL;
        float dx = heightAt(x + e, z) - heightAt(x - e, z);
        float dz = heightAt(x, z + e) - heightAt(x, z - e);
        return (float) Math.sqrt(dx * dx + dz * dz) / (2 * e);
    }

    public float waterLevelAt(float x, float z) {
        int i = Math.round((x + HALF) / CELL), j = Math.round((z + HALF) / CELL);
        if (i < 0 || j < 0 || i >= RES || j >= RES) return -1e9f;
        int id = waterId[j * RES + i];
        return id < 0 ? -1e9f : waterLevels[id];
    }

    public static float toHalfBits(float f) { return f; }

    /** IEEE754 half-float encoding (for R16F height texture upload). */
    public static short floatToHalf(float f) {
        int bits = Float.floatToIntBits(f);
        int sign = (bits >>> 16) & 0x8000;
        int val = (bits & 0x7fffffff) + 0x1000;
        if (val >= 0x47800000) {
            if ((bits & 0x7fffffff) >= 0x47800000) {
                if (val < 0x7f800000) return (short) (sign | 0x7c00);
                return (short) (sign | 0x7c00 | ((bits & 0x007fffff) >>> 13));
            }
            return (short) (sign | 0x7bff);
        }
        if (val >= 0x38800000) return (short) (sign | ((val - 0x38000000) >>> 13));
        if (val < 0x33000000) return (short) sign;
        val = (bits & 0x7fffffff) >>> 23;
        return (short) (sign | ((((bits & 0x7fffff) | 0x800000) + (0x800000 >>> (val - 102))) >>> (126 - val)));
    }
}
