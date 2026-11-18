package com.abhijit.lifeengine.core;

/** SplitMix64 — small, fast, deterministic across platforms (same seed = same world). */
public final class Rng {
    private long state;

    public Rng(long seed) { state = seed * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L; }

    public long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public int nextInt(int bound) { return (int) ((nextLong() >>> 33) % bound); }

    /** Uniform in [0,1). */
    public float nextFloat() { return (nextLong() >>> 40) / (float) (1L << 24); }

    public float range(float lo, float hi) { return lo + (hi - lo) * nextFloat(); }

    /** Stateless hash of 2 ints to [0,1) — used for per-cell deterministic jitter. */
    public static float hash2(int x, int y, int salt) {
        long h = x * 0x8DA6B343L + y * 0xD8163841L + salt * 0xCB1AB31FL;
        h ^= h >>> 13; h *= 0x5bd1e995L; h ^= h >>> 15;
        return ((h >>> 8) & 0xFFFFFF) / (float) 0x1000000;
    }
}
