package com.abhijit.lifeengine.core;

import java.util.Arrays;

/**
 * Procedural savanna generator.
 *
 * Layout (seed-varied): a ridged mountain range along the north edge with a basalt plateau
 * holding a highland pool; the pool spills over a cliff as a waterfall into a meandering river
 * that crosses the grassland to a large lake in the south-west. Granite kopjes, waterholes,
 * riverine forest, acacia woodland and scattered bush fill the plains.
 */
public final class WorldGen {
    private final World w;
    private final Noise n;
    private final Rng rng;
    private final float vegScale;

    private float plateauX, plateauZ, plateauR, plateauH;
    private float poolX, poolZ, poolR;
    private float[] dRiver, dLake, dHole, plateauMask;
    private float[] riverHalfW;

    public static World generate(long seed, float vegetationScale) {
        return new WorldGen(seed, vegetationScale).run();
    }

    private WorldGen(long seed, float vegScale) {
        this.w = new World(seed);
        this.n = new Noise(seed);
        this.rng = new Rng(seed ^ 0x5A5A5A5AL);
        this.vegScale = vegScale;
    }

    private static float wx(int i) { return -World.HALF + i * World.CELL; }

    private World run() {
        int N = World.RES * World.RES;
        dRiver = new float[N]; dLake = new float[N]; dHole = new float[N];
        plateauMask = new float[N]; riverHalfW = new float[N];
        Arrays.fill(dRiver, 1e9f); Arrays.fill(dHole, 1e9f);

        layoutFeatures();
        buildRiver();
        rasterizeRiverDistance();
        computeHeights();
        computeMasks();
        computeWaterIds();
        placeVegetation();
        return w;
    }

    // ------------------------------------------------------------------ features
    private void layoutFeatures() {
        plateauX = rng.range(-150, 350);
        plateauZ = -700 + rng.range(-30, 30);
        plateauR = 170;
        plateauH = 58;
        poolX = plateauX + rng.range(-25, 25);
        poolZ = plateauZ + 30;
        poolR = 38;
        w.waterLevels[1] = plateauH - 6f;

        w.lakeX = rng.range(-900, -600);
        w.lakeZ = rng.range(650, 850);
        w.lakeR = 270;

        // waterholes spread over the plains
        w.waterholes = new float[5 * 3];
        for (int k = 0; k < 5; k++) {
            float x, z;
            int tries = 0;
            do {
                x = rng.range(-1100, 1150);
                z = rng.range(-350, 1100);
                tries++;
            } while (tries < 50 && (dist(x, z, w.lakeX, w.lakeZ) < w.lakeR + 250 || tooClose(w.waterholes, k, x, z, 450)));
            w.waterholes[k * 3] = x; w.waterholes[k * 3 + 1] = z; w.waterholes[k * 3 + 2] = rng.range(24, 42);
        }
    }

    private static boolean tooClose(float[] arr, int count, float x, float z, float d) {
        for (int k = 0; k < count; k++) if (dist(x, z, arr[k * 3], arr[k * 3 + 1]) < d) return true;
        return false;
    }

    private static float dist(float ax, float az, float bx, float bz) {
        float dx = ax - bx, dz = az - bz;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    private float plateauDist(float x, float z) {
        return dist(x, z, plateauX, plateauZ) + 34f * n.fbm(x / 140f, z / 140f, 3) + 10f * n.simplex(x / 35f, z / 35f);
    }

    /** Actual (noisy) cliff edge south of the pool along x = fallX. */
    private float cliffEdgeZ() {
        float z = poolZ;
        while (z < poolZ + 600f && plateauDist(plateauX, z) < plateauR) z += 1f;
        return z;
    }

    private void buildRiver() {
        float edge = cliffEdgeZ();
        w.fallX = plateauX;
        w.fallTopZ = edge - 4f;
        w.fallTopY = w.waterLevels[1];
        w.fallBottomZ = edge + 10f;
        w.fallBottomY = 0f;
        w.fallWidth = 9f;

        float x = plateauX, z = edge + 16f;
        float heading = (float) Math.atan2(w.lakeZ - z, w.lakeX - x);
        float[] pts = new float[2 * 600];
        int c = 0;
        pts[c++] = x; pts[c++] = z;
        float step = 12f;
        for (int s = 0; s < 590; s++) {
            float toLake = (float) Math.atan2(w.lakeZ - z, w.lakeX - x);
            float diff = toLake - heading;
            while (diff > Math.PI) diff -= 2 * Math.PI;
            while (diff < -Math.PI) diff += 2 * Math.PI;
            float meander = 1.1f * n.simplex(s * 0.05f, 3.3f) + 0.45f * n.simplex(s * 0.14f, 8.1f);
            // first stretch runs mostly south away from the cliff
            float pull = s < 25 ? 0.02f : 0.09f;
            heading += diff * pull + meander * 0.16f;
            if (s < 25) heading = Noise.lerp(heading, (float) (Math.PI / 2), 0.3f);
            x += (float) Math.cos(heading) * step;
            z += (float) Math.sin(heading) * step;
            pts[c++] = x; pts[c++] = z;
            if (dist(x, z, w.lakeX, w.lakeZ) < w.lakeR * 0.55f) break;
            if (Math.abs(x) > World.HALF - 60 || Math.abs(z) > World.HALF - 60) break;
        }
        w.riverPolyline = Arrays.copyOf(pts, c);

        // kopjes placed away from the river and lake
        w.kopjes = new float[7 * 3];
        int placed = 0, tries = 0;
        while (placed < 7 && tries < 400) {
            tries++;
            float kx = rng.range(-1250, 1250), kz = rng.range(-450, 1250);
            if (dist(kx, kz, w.lakeX, w.lakeZ) < w.lakeR + 160) continue;
            if (distToRiver(kx, kz) < 160) continue;
            if (tooClose(w.waterholes, 5, kx, kz, 140)) continue;
            if (tooClose(w.kopjes, placed, kx, kz, 380)) continue;
            w.kopjes[placed * 3] = kx; w.kopjes[placed * 3 + 1] = kz; w.kopjes[placed * 3 + 2] = rng.range(32, 60);
            placed++;
        }
        if (placed < 7) w.kopjes = Arrays.copyOf(w.kopjes, placed * 3);
    }

    private float distToRiver(float x, float z) {
        float best = 1e9f;
        float[] p = w.riverPolyline;
        for (int k = 0; k + 3 < p.length; k += 2) best = Math.min(best, segDist(x, z, p[k], p[k + 1], p[k + 2], p[k + 3]));
        return best;
    }

    private static float segDist(float px, float pz, float ax, float az, float bx, float bz) {
        float vx = bx - ax, vz = bz - az;
        float t = ((px - ax) * vx + (pz - az) * vz) / (vx * vx + vz * vz + 1e-6f);
        t = Noise.clamp(t, 0, 1);
        float dx = px - (ax + vx * t), dz = pz - (az + vz * t);
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    private void rasterizeRiverDistance() {
        float[] p = w.riverPolyline;
        int segs = p.length / 2 - 1;
        float R = 420f;
        for (int k = 0; k < segs; k++) {
            float ax = p[k * 2], az = p[k * 2 + 1], bx = p[k * 2 + 2], bz = p[k * 2 + 3];
            float t = k / (float) Math.max(1, segs - 1);
            float halfW = 7f + 9f * t; // widens downstream
            int i0 = Math.max(0, (int) ((Math.min(ax, bx) - R + World.HALF) / World.CELL));
            int i1 = Math.min(World.RES - 1, (int) ((Math.max(ax, bx) + R + World.HALF) / World.CELL) + 1);
            int j0 = Math.max(0, (int) ((Math.min(az, bz) - R + World.HALF) / World.CELL));
            int j1 = Math.min(World.RES - 1, (int) ((Math.max(az, bz) + R + World.HALF) / World.CELL) + 1);
            for (int j = j0; j <= j1; j++) {
                float z = wx(j);
                for (int i = i0; i <= i1; i++) {
                    float d = segDist(wx(i), z, ax, az, bx, bz);
                    int id = World.idx(i, j);
                    if (d < dRiver[id]) { dRiver[id] = d; riverHalfW[id] = halfW; }
                }
            }
        }
    }

    // ------------------------------------------------------------------ heights
    private void computeHeights() {
        float[] H = w.height;
        float edge = cliffEdgeZ();
        for (int j = 0; j < World.RES; j++) {
            float z = wx(j);
            for (int i = 0; i < World.RES; i++) {
                float x = wx(i);
                int id = World.idx(i, j);

                // rolling plains
                float h = 4.5f + 4.0f * n.fbm(x / 520f, z / 520f, 4) + 1.2f * n.fbm(x / 95f, z / 95f, 3);

                // northern mountains: ridged noise behind a noisy front line
                float front = -640f + 120f * n.simplex(x / 600f, 4.2f) + 40f * n.simplex(x / 150f, 1.7f);
                float mMask = Noise.smoothstep(front, front - 520f, z);
                // domain-warped ridges give broad massifs with eroded spurs instead of needles
                float wx = x + 260f * n.fbm(x / 900f + 5f, z / 900f, 3), wz = z + 260f * n.fbm(x / 900f, z / 900f - 5f, 3);
                float ridge = n.ridged(wx / 1100f + 3.1f, wz / 1100f - 1.4f, 6);
                float massif = 0.5f + 0.5f * n.fbm(x / 1400f + 2f, z / 1400f, 3);
                float mountains = mMask * (30f + 250f * (float) Math.pow(ridge, 1.6f) * (0.55f + 0.6f * massif) + 18f * n.fbm(x / 160f, z / 160f, 4));
                // foothills
                mountains += Noise.smoothstep(front + 260f, front - 120f, z) * 18f * (0.5f + 0.5f * n.fbm(x / 210f, z / 210f, 3));
                // edge mountains on east/west/south borders to frame the basin
                float border = Math.max(Math.abs(x), Math.abs(z)) - (World.HALF - 260f);
                float bMask = Noise.smoothstep(0f, 230f, border + 60f * n.simplex(x / 300f, z / 300f));
                mountains += bMask * (25f + 150f * (float) Math.pow(n.ridged(x / 820f + 9f, z / 820f, 5), 1.5f) + 12f * n.fbm(x / 150f, z / 150f, 3));
                h += mountains;

                // plateau with a steep south-facing cliff
                float pd = plateauDist(x, z);
                float pm = Noise.smoothstep(plateauR + 6f, plateauR - 3f, pd);
                // scree / talus apron at the foot of the cliff
                h += 9f * Noise.smoothstep(plateauR + 45f, plateauR + 4f, pd) * (1f - pm) * (0.5f + 0.5f * n.fbm(x / 22f, z / 22f, 3));
                plateauMask[id] = pm;
                float ph = plateauH + 4f * n.fbm(x / 60f, z / 60f, 3) + 8f * Noise.smoothstep(plateauR * 0.4f, plateauR, pd) * (0.5f + 0.5f * n.fbm(x / 45f, z / 45f, 2));
                if (pm > 0) h = Math.max(h, Noise.lerp(h, ph, pm));
                // highland pool bowl
                float qd = dist(x, z, poolX, poolZ) + 6f * n.simplex(x / 40f, z / 40f);
                if (pm > 0.5f && qd < poolR + 25f) {
                    float bowl = (w.waterLevels[1] - 5f) + 9f * Noise.smoothstep(poolR * 0.3f, poolR + 22f, qd);
                    h = Math.min(h, bowl);
                }
                // spill channel from pool to cliff lip
                if (pm > 0.05f) {
                    float sd = segDist(x, z, poolX, poolZ, w.fallX, w.fallTopZ + 4f);
                    if (sd < 16f) {
                        float bed = w.waterLevels[1] - 1.6f + 8f * Noise.smoothstep(4f, 16f, sd);
                        h = Math.min(h, Noise.lerp(h, bed, pm));
                    }
                }

                // kopjes: granite domes
                for (int k = 0; k + 2 < w.kopjes.length; k += 3) {
                    float kd = dist(x, z, w.kopjes[k], w.kopjes[k + 1]);
                    float kr = w.kopjes[k + 2];
                    if (kd < kr * 1.6f) {
                        float dome = 1f - kd / (kr * 1.6f);
                        dome = dome * dome * (3 - 2 * dome);
                        h += dome * (kr * 0.42f) * (0.85f + 0.3f * n.fbm(x / 18f, z / 18f, 3));
                    }
                }

                // river channel
                float dr = dRiver[id];
                if (dr < 400f) {
                    float hw = riverHalfW[id];
                    float bankW = 24f + 10f * n.simplex(x / 70f, z / 70f);
                    // flood plain: the land relaxes towards the river level
                    float keep = 1f - Noise.smoothstep(0.05f, 0.5f, pm);   // never erode the plateau
                    float flood = Noise.smoothstep(hw + bankW + 220f, hw + bankW, dr) * (1f - mMask) * keep;
                    float plainH = 1.6f + 0.012f * dr + 1.4f * n.fbm(x / 80f, z / 80f, 2);
                    h = Noise.lerp(h, Math.min(h, plainH), flood);
                    // channel
                    float t = Noise.smoothstep(hw * 0.55f, hw + bankW, dr);
                    float bed = -2.6f - 0.9f * Noise.smoothstep(hw, 0f, dr);
                    h = Noise.lerp(bed, h, Noise.lerp(1f, t, keep));
                }
                // plunge pool at the waterfall foot
                float fd = dist(x, z, w.fallX, w.fallBottomZ + 6f);
                if (fd < 40f) h = Math.min(h, Noise.lerp(-3.5f, h, Noise.smoothstep(10f, 40f, fd)));

                // lake
                float ang = (float) Math.atan2(z - w.lakeZ, x - w.lakeX);
                float lr = w.lakeR * (1f + 0.16f * n.simplex((float) Math.cos(ang) * 0.8f + 7f, (float) Math.sin(ang) * 0.8f) + 0.04f * n.simplex(x / 60f, z / 60f));
                float ld = dist(x, z, w.lakeX, w.lakeZ);
                dLake[id] = ld - lr;
                if (ld < lr + 90f) {
                    float depth = -9f * Noise.smoothstep(lr, lr * 0.4f, ld) - 1.0f;
                    float t = Noise.smoothstep(lr - 10f, lr + 90f, ld);
                    h = Noise.lerp(depth, Math.min(h, 0.6f + (ld - lr) * 0.04f + 1.5f * t), t);
                }

                // waterholes
                for (int k = 0; k < w.waterholes.length; k += 3) {
                    float hd = dist(x, z, w.waterholes[k], w.waterholes[k + 1]) + 5f * n.simplex(x / 25f, z / 25f);
                    float hr = w.waterholes[k + 2];
                    dHole[id] = Math.min(dHole[id], hd - hr);
                    if (hd < hr + 50f) {
                        float t = Noise.smoothstep(hr * 0.6f, hr + 50f, hd);
                        h = Noise.lerp(-1.8f, Math.min(h, 0.8f + 2.5f * t), t * t * 0.6f + t * 0.4f);
                    }
                }
                H[id] = h;
            }
        }
    }

    // ------------------------------------------------------------------ masks
    private void computeMasks() {
        float[] nrm = new float[3];
        for (int j = 0; j < World.RES; j++) {
            float z = wx(j);
            for (int i = 0; i < World.RES; i++) {
                float x = wx(i);
                int id = World.idx(i, j);
                float h = w.height[id];
                w.normalAtCell(i, j, nrm);
                float slope = 1f - nrm[1];

                float m = 0.9f * (float) Math.exp(-dRiver[id] / 120f)
                        + (float) Math.exp(-Math.max(0, dLake[id]) / 160f)
                        + 0.75f * (float) Math.exp(-Math.max(0, dHole[id]) / 70f)
                        + 0.18f * n.fbm(x / 300f, z / 300f, 3) + 0.12f
                        + 0.25f * Noise.smoothstep(25f, 120f, h);
                if (plateauMask[id] > 0.5f) m += 0.35f * (float) Math.exp(-dist(x, z, poolX, poolZ) / 90f);
                w.moisture[id] = Noise.clamp(m, 0, 1);

                float s = 0;
                if (dLake[id] < 35f && h < 2.8f) s = Noise.smoothstep(35f, 5f, dLake[id]);
                float bars = n.fbm(x / 35f, z / 35f, 2);
                if (dRiver[id] < riverHalfW[id] + 14f && h < 1.6f) s = Math.max(s, Noise.smoothstep(0.0f, 0.35f, bars) * 0.9f);
                s *= Noise.smoothstep(0.35f, 0.15f, slope);
                w.sand[id] = s;

                float md = 0;
                if (dHole[id] < 22f) md = Noise.smoothstep(22f, 2f, dHole[id]);
                if (dRiver[id] < riverHalfW[id] + 10f && h < 1.2f) md = Math.max(md, 0.6f * (1 - s));
                if (dist(x, z, w.fallX, w.fallBottomZ) < 55f && h < 2f) md = Math.max(md, 0.5f);
                w.mud[id] = md;

                float g = Noise.smoothstep(-0.2f, 1.0f, h) * (1 - s) * (1 - 0.85f * md)
                        * Noise.smoothstep(0.32f, 0.12f, slope)
                        * (0.55f + 0.45f * Noise.smoothstep(-0.4f, 0.4f, n.fbm(x / 60f, z / 60f, 3)))
                        * Noise.smoothstep(170f, 60f, h);
                w.grass[id] = Noise.clamp(g, 0, 1);
            }
        }
    }

    private void computeWaterIds() {
        for (int j = 0; j < World.RES; j++) {
            for (int i = 0; i < World.RES; i++) {
                int id = World.idx(i, j);
                float h = w.height[id];
                byte wid = -1;
                if (plateauMask[id] > 0.6f) {
                    float x = wx(i), z = wx(j);
                    boolean nearPool = dist(x, z, poolX, poolZ) < poolR + 30f
                            || segDist(x, z, poolX, poolZ, w.fallX, w.fallTopZ + 4f) < 12f;
                    if (nearPool && h < w.waterLevels[1] + 0.8f) wid = 1;
                } else if (h < w.waterLevels[0] + 0.8f) {
                    wid = 0;
                }
                w.waterId[id] = wid;
            }
        }
    }

    // ------------------------------------------------------------------ vegetation
    private float[] buf;
    private int bufCount;

    private void begin(int cap) { buf = new float[cap * World.INST_STRIDE]; bufCount = 0; }

    private boolean add(float x, float z, float rot, float scale, float tint, float sink) {
        if (bufCount * World.INST_STRIDE >= buf.length) return false;
        int o = bufCount * World.INST_STRIDE;
        buf[o] = x; buf[o + 1] = w.heightAt(x, z) - sink; buf[o + 2] = z;
        buf[o + 3] = rot; buf[o + 4] = scale; buf[o + 5] = tint;
        bufCount++;
        return true;
    }

    private void end(int type) {
        w.instances[type] = Arrays.copyOf(buf, bufCount * World.INST_STRIDE);
        w.instanceCount[type] = bufCount;
    }

    private boolean dry(float x, float z, float margin) {
        return w.heightAt(x, z) > w.waterLevelAt(x, z) + margin && w.heightAt(x, z) > margin;
    }

    private boolean onPlateauPoolArea(float x, float z) {
        return dist(x, z, poolX, poolZ) < poolR + 8;
    }

    private void placeVegetation() {
        float lim = World.HALF - 40f;
        int i;

        // Acacia woodland on the plains — clustered by low-frequency noise
        int nAcacia = (int) (1400 * vegScale);
        begin(nAcacia);
        for (i = 0; i < nAcacia * 30 && bufCount < nAcacia; i++) {
            float x = rng.range(-lim, lim), z = rng.range(-lim, lim);
            float h = w.heightAt(x, z);
            if (h < 1.2f || h > 90f || w.slopeAt(x, z) > 0.35f || !dry(x, z, 0.8f)) continue;
            float m = w.sample(w.moisture, x, z);
            float cluster = 0.5f + 0.5f * n.fbm(x / 240f, z / 240f, 3);
            float p = cluster * cluster * (0.35f + 0.65f * Noise.smoothstep(0.1f, 0.5f, m));
            if (w.sample(w.sand, x, z) > 0.3f || w.sample(w.mud, x, z) > 0.4f) continue;
            if (rng.nextFloat() > p) continue;
            add(x, z, rng.range(0, 6.283f), rng.range(0.75f, 1.3f), rng.nextFloat(), 0.15f);
        }
        end(World.T_ACACIA);

        // Riverine forest: dense galleries along the river, lake shore and plateau pool
        int nRiv = (int) (1700 * vegScale);
        begin(nRiv);
        for (i = 0; i < nRiv * 40 && bufCount < nRiv; i++) {
            float x = rng.range(-lim, lim), z = rng.range(-lim, lim);
            int gi = Math.round((x + World.HALF) / World.CELL), gj = Math.round((z + World.HALF) / World.CELL);
            int id = World.idx(gi, gj);
            float near = (float) Math.max(Math.exp(-Math.max(0, dRiver[id] - riverHalfW[id] - 14f) / 45f),
                    Math.exp(-Math.max(0, dLake[id] - 30f) / 50f));
            float forest = Noise.smoothstep(0.25f, 0.6f, n.fbm(x / 180f + 9f, z / 180f, 3));
            float foothill = Noise.smoothstep(0.15f, 0.5f, n.fbm(x / 200f - 4f, z / 200f + 2f, 3)) * Noise.smoothstep(6f, 30f, w.height[id]) * Noise.smoothstep(140f, 60f, w.height[id]);
            float p = Math.max(near * 0.95f, Math.max(forest * 0.12f, foothill * 0.5f));
            if (plateauMask[id] > 0.5f) p = Math.max(p, 0.7f * (float) Math.exp(-Math.max(0, dist(x, z, poolX, poolZ) - poolR) / 40f));
            if (rng.nextFloat() > p) continue;
            if (!dry(x, z, 0.9f) || w.slopeAt(x, z) > 0.45f || onPlateauPoolArea(x, z)) continue;
            if (w.sample(w.sand, x, z) > 0.4f) continue;
            add(x, z, rng.range(0, 6.283f), rng.range(0.7f, 1.25f), rng.nextFloat(), 0.2f);
        }
        end(World.T_RIVERINE);

        // Doum palms on the lake shore and river mouth
        int nPalm = (int) (160 * vegScale) + 20;
        begin(nPalm);
        for (i = 0; i < nPalm * 300 && bufCount < nPalm; i++) {
            float x = rng.range(w.lakeX - w.lakeR - 200, w.lakeX + w.lakeR + 200), z = rng.range(w.lakeZ - w.lakeR - 200, w.lakeZ + w.lakeR + 200);
            float d = w.sample(dLake, x, z);
            if (d < 12f || d > 90f || !dry(x, z, 0.8f)) continue;
            add(x, z, rng.range(0, 6.283f), rng.range(0.8f, 1.2f), rng.nextFloat(), 0.1f);
        }
        end(World.T_PALM);

        // Baobabs — rare giants
        int nBao = Math.max(8, (int) (28 * vegScale));
        begin(nBao);
        for (i = 0; i < 6000 && bufCount < nBao; i++) {
            float x = rng.range(-lim, lim), z = rng.range(-lim, lim);
            float h = w.heightAt(x, z);
            if (h < 2.5f || h > 45f || w.slopeAt(x, z) > 0.18f || !dry(x, z, 1.5f)) continue;
            if (w.sample(w.moisture, x, z) > 0.55f) continue;
            if (tooCloseInst(x, z, 220f)) continue;
            add(x, z, rng.range(0, 6.283f), rng.range(0.85f, 1.25f), rng.nextFloat(), 0.4f);
        }
        end(World.T_BAOBAB);

        // Bushes everywhere except wet/steep
        int nBush = (int) (5200 * vegScale);
        begin(nBush);
        for (i = 0; i < nBush * 12 && bufCount < nBush; i++) {
            float x = rng.range(-lim, lim), z = rng.range(-lim, lim);
            float h = w.heightAt(x, z);
            if (h < 0.9f || h > 140f || w.slopeAt(x, z) > 0.5f || !dry(x, z, 0.6f)) continue;
            if (w.sample(w.sand, x, z) > 0.5f) continue;
            float p = 0.25f + 0.6f * w.sample(w.moisture, x, z);
            if (rng.nextFloat() > p) continue;
            add(x, z, rng.range(0, 6.283f), rng.range(0.6f, 1.5f), rng.nextFloat(), 0.1f);
        }
        end(World.T_BUSH);

        // Rocks: kopje boulders, mountain scree, scattered stones
        int nRock = (int) (2400 * vegScale) + 200;
        int perVariant = nRock / 4 + 1;
        float[][] tmp = new float[4][perVariant * World.INST_STRIDE];
        int[] cnt = new int[4];
        for (i = 0; i < nRock * 20; i++) {
            int v = rng.nextInt(4);
            if (cnt[v] >= perVariant) { if (cnt[0] + cnt[1] + cnt[2] + cnt[3] >= perVariant * 4) break; continue; }
            float x, z, scale;
            float r = rng.nextFloat();
            if (r < 0.4f && w.kopjes.length > 0) {
                int k = rng.nextInt(w.kopjes.length / 3) * 3;
                float a = rng.range(0, 6.283f), rr = (float) Math.sqrt(rng.nextFloat()) * w.kopjes[k + 2] * 1.25f;
                x = w.kopjes[k] + (float) Math.cos(a) * rr; z = w.kopjes[k + 1] + (float) Math.sin(a) * rr;
                scale = rng.range(2.5f, 9f) * (1.2f - rr / (w.kopjes[k + 2] * 1.4f));
            } else if (r < 0.7f) {
                x = rng.range(-lim, lim); z = rng.range(-lim, lim);
                float s = w.slopeAt(x, z), h = w.heightAt(x, z);
                if (h < 20f || s < 0.25f) continue;
                scale = rng.range(2f, 7f);
            } else {
                x = rng.range(-lim, lim); z = rng.range(-lim, lim);
                scale = rng.range(0.4f, 1.8f);
            }
            if (!dry(x, z, -0.5f)) continue;
            int o = cnt[v] * World.INST_STRIDE;
            tmp[v][o] = x; tmp[v][o + 1] = w.heightAt(x, z) - scale * 0.25f; tmp[v][o + 2] = z;
            tmp[v][o + 3] = rng.range(0, 6.283f); tmp[v][o + 4] = Math.max(0.3f, scale); tmp[v][o + 5] = rng.nextFloat();
            cnt[v]++;
        }
        for (int v = 0; v < 4; v++) {
            w.instances[World.T_ROCK0 + v] = Arrays.copyOf(tmp[v], cnt[v] * World.INST_STRIDE);
            w.instanceCount[World.T_ROCK0 + v] = cnt[v];
        }
    }

    private boolean tooCloseInst(float x, float z, float d) {
        for (int k = 0; k < bufCount; k++) {
            if (dist(x, z, buf[k * World.INST_STRIDE], buf[k * World.INST_STRIDE + 2]) < d) return true;
        }
        return false;
    }
}
