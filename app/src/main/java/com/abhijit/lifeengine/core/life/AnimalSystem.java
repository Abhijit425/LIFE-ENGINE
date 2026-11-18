package com.abhijit.lifeengine.core.life;

import com.abhijit.lifeengine.core.Noise;
import com.abhijit.lifeengine.core.Rng;
import com.abhijit.lifeengine.core.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2 "ambient" behaviour: herds and prides that graze, wander, walk to water to drink, rest and
 * occasionally gallop, following the terrain. Predator-prey interaction, needs (hunger/thirst/energy),
 * reproduction and population dynamics are Phase 3 and will replace the timers here.
 */
public final class AnimalSystem {
    // clip ids (resolved to names via Species)
    public static final int C_IDLE = 0, C_IDLE2 = 1, C_EAT = 2, C_WALK = 3, C_RUN = 4, C_REST = 5;
    // herd modes
    static final int H_GRAZE = 0, H_WANDER = 1, H_DRINK = 2, H_REST = 3, H_GALLOP = 4;

    public static final class Animal {
        public final int id, species, herd;
        public float x, y, z, yaw, pitch, speed;
        float ox, oz, offsetTimer, idleTimer;
        public int clip = C_IDLE, prevClip = C_IDLE;
        public float clipTime, prevClipTime, fade = 1f, clipRate = 1f, prevClipRate = 1f;
        public boolean moving;
        public float bodyLength;
        public String activity = "Idle";

        Animal(int id, int species, int herd) { this.id = id; this.species = species; this.herd = herd; }
    }

    static final class Herd {
        final int species;
        float tx, tz;
        int mode = H_GRAZE;
        float timer, spread;
        float thirst; // seconds since last drink (simple timer, Phase 3 replaces with physiology)
        final List<Animal> members = new ArrayList<>();
        Herd(int species) { this.species = species; }
    }

    public final List<Animal> animals = new ArrayList<>();
    private final List<Herd> herds = new ArrayList<>();
    private final float[] shore;   // x,z pairs of drinkable shoreline points
    private final World w;
    private final Rng rng;
    private final float[] bodyLen;  // per species, metres

    /**
     * @param modelLengths model-space body length per species (from the loaded GLB), multiplied by scale here
     * @param density 0..1 fraction of the full population to spawn (quality preset)
     */
    public AnimalSystem(World world, long seed, float[] modelLengths, float density) {
        this.w = world;
        this.rng = new Rng(seed * 31 + 7);
        this.shore = findShore(world);
        bodyLen = new float[Species.ALL.length];
        for (int s = 0; s < Species.ALL.length; s++) bodyLen[s] = modelLengths[s] * Species.ALL[s].scale;
        spawn(Math.max(0.15f, Math.min(1f, density)));
    }

    // ------------------------------------------------------------------ spawning
    private void spawn(float density) {
        int id = 0;
        for (int s = 0; s < Species.ALL.length; s++) {
            Species sp = Species.ALL[s];
            int groups = Math.max(1, Math.round(sp.groups * (0.5f + 0.5f * density)));
            for (int g = 0; g < groups; g++) {
                Herd h = new Herd(s);
                float[] p = randomSite(sp.diet == Species.CARNIVORE ? 0.0f : 0.25f);
                h.tx = p[0]; h.tz = p[1];
                int n = sp.herdMin + rng.nextInt(sp.herdMax - sp.herdMin + 1);
                n = Math.max(1, Math.round(n * (0.4f + 0.6f * density)));
                h.spread = bodyLen[s] * (1.6f + 0.35f * (float) Math.sqrt(n));
                h.thirst = rng.range(0, 400);
                h.timer = rng.range(5, 60);
                h.mode = sp.nocturnal ? H_REST : H_GRAZE;
                for (int k = 0; k < n; k++) {
                    Animal a = new Animal(id++, s, herds.size());
                    a.bodyLength = bodyLen[s];
                    newOffset(a, h);
                    a.x = h.tx + a.ox; a.z = h.tz + a.oz;
                    if (!walkable(a.x, a.z)) { a.x = h.tx; a.z = h.tz; }
                    a.y = w.heightAt(a.x, a.z);
                    a.yaw = rng.range(0, 6.283f);
                    a.clipTime = rng.range(0, 6);
                    a.offsetTimer = rng.range(5, 30);
                    h.members.add(a);
                    animals.add(a);
                }
                herds.add(h);
            }
        }
    }

    private float[] randomSite(float minGrass) {
        for (int tries = 0; tries < 4000; tries++) {
            float x = rng.range(-World.HALF + 250, World.HALF - 250), z = rng.range(-World.HALF + 250, World.HALF - 250);
            if (!walkable(x, z) || w.heightAt(x, z) > 40f) continue;
            if (w.sample(w.grass, x, z) < minGrass) continue;
            return new float[]{x, z};
        }
        return new float[]{0, 300};
    }

    private void newOffset(Animal a, Herd h) {
        float ang = rng.range(0, 6.283f), r = (float) Math.sqrt(rng.nextFloat()) * h.spread;
        a.ox = (float) Math.cos(ang) * r; a.oz = (float) Math.sin(ang) * r;
    }

    // ------------------------------------------------------------------ world queries
    public boolean walkable(float x, float z) {
        if (Math.abs(x) > World.HALF - 120 || Math.abs(z) > World.HALF - 120) return false;
        float h = w.heightAt(x, z);
        if (h < w.waterLevelAt(x, z) + 0.12f) return false;
        if (h < 0.12f) return false;
        return w.slopeAt(x, z) < 0.55f;
    }

    private static float[] findShore(World w) {
        float[] tmp = new float[2 * 4000];
        int n = 0;
        for (int j = 4; j < World.RES - 4 && n < 4000; j += 3) {
            for (int i = 4; i < World.RES - 4 && n < 4000; i += 3) {
                int id = World.idx(i, j);
                float h = w.height[id];
                if (h < 0.15f || h > 0.9f) continue;
                boolean nearWater = false;
                for (int d = 1; d <= 3 && !nearWater; d++)
                    nearWater = w.waterId[World.idx(i + d, j)] == 0 && w.height[World.idx(i + d, j)] < -0.1f
                            || w.waterId[World.idx(i - d, j)] == 0 && w.height[World.idx(i - d, j)] < -0.1f
                            || w.waterId[World.idx(i, j + d)] == 0 && w.height[World.idx(i, j + d)] < -0.1f
                            || w.waterId[World.idx(i, j - d)] == 0 && w.height[World.idx(i, j - d)] < -0.1f;
                if (!nearWater) continue;
                float x = -World.HALF + i * World.CELL, z = -World.HALF + j * World.CELL;
                if (w.slopeAt(x, z) > 0.25f) continue;
                tmp[n * 2] = x; tmp[n * 2 + 1] = z; n++;
            }
        }
        float[] out = new float[n * 2];
        System.arraycopy(tmp, 0, out, 0, n * 2);
        return out;
    }

    private float[] nearestShore(float x, float z) {
        float best = 1e18f; float bx = x, bz = z;
        for (int k = 0; k < shore.length; k += 2) {
            float dx = shore[k] - x, dz = shore[k + 1] - z, d = dx * dx + dz * dz;
            if (d < best) { best = d; bx = shore[k]; bz = shore[k + 1]; }
        }
        return new float[]{bx, bz, (float) Math.sqrt(best)};
    }

    // ------------------------------------------------------------------ update
    /** @param dt simulated seconds since last update; hour = time of day (0..24) */
    public void update(float dt, float hour) {
        if (dt <= 0) return;
        boolean night = hour < 6.2f || hour > 18.6f;
        boolean dusk = (hour > 16.5f && hour < 19.5f) || (hour > 5f && hour < 7.5f);
        for (Herd h : herds) updateHerd(h, dt, night, dusk);
        for (Herd h : herds) for (Animal a : h.members) updateAnimal(a, h, dt);
    }

    private void updateHerd(Herd h, float dt, boolean night, boolean dusk) {
        Species sp = Species.ALL[h.species];
        h.timer -= dt;
        h.thirst += dt;
        boolean carn = sp.diet == Species.CARNIVORE;
        if (h.mode == H_DRINK || h.mode == H_WANDER || h.mode == H_GALLOP) {
            // arrive when most members are near the target
            int near = 0;
            for (Animal a : h.members) if (dist(a.x, a.z, h.tx + a.ox, h.tz + a.oz) < 3f) near++;
            if (near >= h.members.size() * 0.7f && h.timer > 30f) h.timer = h.mode == H_DRINK ? rng.range(25, 50) : 0;
        }
        if (h.timer > 0) return;
        // choose the next activity
        boolean active = carn ? (night || dusk) : !night;
        if (h.thirst > (carn ? 900f : 520f) && shore.length > 0) {
            float c[] = herdCentre(h);
            float[] s = nearestShore(c[0], c[1]);
            if (s[2] < 1600f) {
                h.mode = H_DRINK; h.tx = s[0]; h.tz = s[1]; h.thirst = 0; h.timer = 400f;
                // line up along the water's edge
                for (Animal a : h.members) { float ang = rng.range(0, 6.283f); float r = rng.range(0.3f, 1f) * h.spread * 0.7f; a.ox = (float) Math.cos(ang) * r; a.oz = (float) Math.sin(ang) * r; }
                return;
            }
        }
        float roll = rng.nextFloat();
        if (!active) {
            h.mode = H_REST; h.timer = rng.range(60, 240);
        } else if (carn) {
            if (roll < 0.45f) { h.mode = H_REST; h.timer = rng.range(60, 200); }
            else { pickWanderTarget(h, 120, 420); h.mode = H_WANDER; h.timer = 300f; }
        } else {
            if (roll < 0.72f) { h.mode = H_GRAZE; h.timer = rng.range(90, 260); }
            else if (roll < 0.95f) { pickWanderTarget(h, 50, 200); h.mode = H_WANDER; h.timer = 260f; }
            else { pickWanderTarget(h, 180, 380); h.mode = H_GALLOP; h.timer = 120f; }
        }
        for (Animal a : h.members) if (rng.nextFloat() < 0.5f) newOffset(a, h);
    }

    private float[] herdCentre(Herd h) {
        float x = 0, z = 0;
        for (Animal a : h.members) { x += a.x; z += a.z; }
        return new float[]{x / h.members.size(), z / h.members.size()};
    }

    private void pickWanderTarget(Herd h, float minD, float maxD) {
        float[] c = herdCentre(h);
        for (int t = 0; t < 40; t++) {
            float ang = rng.range(0, 6.283f), d = rng.range(minD, maxD);
            float x = c[0] + (float) Math.cos(ang) * d, z = c[1] + (float) Math.sin(ang) * d;
            if (!walkable(x, z) || w.heightAt(x, z) > 60f) continue;
            if (Species.ALL[h.species].diet != Species.CARNIVORE && w.sample(w.grass, x, z) < 0.15f && t < 30) continue;
            h.tx = x; h.tz = z;
            return;
        }
    }

    private void updateAnimal(Animal a, Herd h, float dt) {
        Species sp = Species.ALL[a.species];
        a.offsetTimer -= dt;
        if (a.offsetTimer < 0 && (h.mode == H_GRAZE || h.mode == H_REST)) {
            a.offsetTimer = rng.range(15, 60);
            if (rng.nextFloat() < 0.6f) {
                // drift a few body lengths while grazing
                float ang = rng.range(0, 6.283f), r = a.bodyLength * rng.range(0.5f, 2.5f);
                a.ox += (float) Math.cos(ang) * r; a.oz += (float) Math.sin(ang) * r;
                float l = (float) Math.sqrt(a.ox * a.ox + a.oz * a.oz);
                if (l > h.spread * 1.3f) { a.ox *= h.spread / l; a.oz *= h.spread / l; }
            }
        }
        float gx = h.tx + a.ox, gz = h.tz + a.oz;
        float dx = gx - a.x, dz = gz - a.z;
        float d = (float) Math.sqrt(dx * dx + dz * dz);
        float arrive = Math.max(1.2f, a.bodyLength * 0.6f);
        float targetSpeed = 0;
        if (d > arrive) {
            float far = h.mode == H_GALLOP ? 1f : Noise.smoothstep(40f, 160f, d);
            targetSpeed = h.mode == H_GALLOP && d > 12f ? sp.runSpeed * 0.75f
                    : sp.walkSpeed * (1f + 0.8f * far) * Math.min(1f, d / (arrive * 3f) + 0.3f);
        }
        // separation from herd mates
        float sx = 0, sz = 0;
        for (Animal o : h.members) {
            if (o == a) continue;
            float ex = a.x - o.x, ez = a.z - o.z, e2 = ex * ex + ez * ez;
            float min = (a.bodyLength + o.bodyLength) * 0.55f;
            if (e2 < min * min && e2 > 1e-4f) { float e = (float) Math.sqrt(e2); sx += ex / e * (min - e); sz += ez / e * (min - e); }
        }
        // speed + heading
        a.speed += (targetSpeed - a.speed) * Math.min(1f, dt * 1.8f);
        if (a.speed < 0.05f && targetSpeed == 0) a.speed = 0;
        if (d > arrive * 0.5f || sx != 0 || sz != 0) {
            float desired = (float) Math.atan2(dx + sx * 2f, dz + sz * 2f); // yaw: forward = (sin, cos)
            float diff = wrap(desired - a.yaw);
            float turn = (a.speed > sp.walkSpeed * 1.5f ? 1.2f : 2.0f) * dt;
            if (a.speed > 0.05f || Math.abs(diff) > 0.6f) a.yaw = wrap(a.yaw + Math.max(-turn, Math.min(turn, diff)));
        }
        if (a.speed > 0.01f) {
            float nx = a.x + (float) Math.sin(a.yaw) * a.speed * dt + sx * dt;
            float nz = a.z + (float) Math.cos(a.yaw) * a.speed * dt + sz * dt;
            if (walkable(nx, nz)) { a.x = nx; a.z = nz; }
            else {
                a.yaw = wrap(a.yaw + (rng.nextFloat() < 0.5f ? 0.9f : -0.9f));
                a.speed *= 0.5f;
                if (h.mode == H_DRINK && d < 12f) { a.ox -= dx * 0.2f; a.oz -= dz * 0.2f; }
            }
        }
        // terrain following + body pitch along the slope
        a.y = w.heightAt(a.x, a.z);
        float half = a.bodyLength * 0.4f;
        float hf = w.heightAt(a.x + (float) Math.sin(a.yaw) * half, a.z + (float) Math.cos(a.yaw) * half);
        float hb = w.heightAt(a.x - (float) Math.sin(a.yaw) * half, a.z - (float) Math.cos(a.yaw) * half);
        float targetPitch = (float) Math.atan2(hb - hf, 2 * half);
        a.pitch += (targetPitch - a.pitch) * Math.min(1f, dt * 4f);
        a.y = (hf + hb) * 0.5f + 0.02f;
        a.moving = a.speed > 0.15f;

        // animation selection
        int want;
        float rate = 1f;
        float natWalk = a.bodyLength * 0.62f / 1.12f;   // stride ≈ 0.62 body lengths per walk cycle
        float natRun = a.bodyLength * 1.9f / 0.58f;     // gallop stride ≈ 1.9 body lengths per cycle
        if (a.moving) {
            if (a.speed > sp.walkSpeed * 1.9f) { want = C_RUN; rate = clamp(a.speed / natRun, 0.6f, 1.8f); a.activity = "Running"; }
            else { want = C_WALK; rate = clamp(a.speed / natWalk, 0.4f, 2.2f); a.activity = h.mode == H_DRINK ? "Walking to water" : "Walking"; }
        } else {
            a.idleTimer -= dt;
            switch (h.mode) {
                case H_DRINK: want = C_EAT; a.activity = "Drinking"; break;
                case H_REST: want = C_REST; rate = 0.7f; a.activity = "Resting"; break;
                case H_GRAZE:
                    if (sp.diet == Species.CARNIVORE) { want = C_IDLE; a.activity = "Watching"; }
                    else { want = (a.id + (int) (a.clipTime / 9f)) % 4 == 0 ? C_IDLE : C_EAT; a.activity = want == C_EAT ? (sp.diet == Species.BROWSER ? "Feeding" : "Grazing") : "Alert"; }
                    break;
                default: want = (a.id % 3 == 0) ? C_IDLE2 : C_IDLE; a.activity = "Idle";
            }
        }
        if (want != a.clip) {
            a.prevClip = a.clip; a.prevClipTime = a.clipTime; a.prevClipRate = a.clipRate;
            a.clip = want; a.clipTime = 0; a.fade = 0;
        }
        a.clipRate = rate;
        a.clipTime += dt * rate;
        a.prevClipTime += dt * a.prevClipRate;
        if (a.fade < 1f) a.fade = Math.min(1f, a.fade + dt / 0.35f);
    }

    public String clipName(Animal a, int clip) {
        Species sp = Species.ALL[a.species];
        switch (clip) {
            case C_IDLE2: return sp.clipIdle2;
            case C_EAT: return sp.clipEat;
            case C_WALK: return sp.clipWalk;
            case C_RUN: return sp.clipRun;
            case C_REST: return sp.clipRest;
            default: return sp.clipIdle;
        }
    }

    public int count(int species) {
        int n = 0;
        for (Animal a : animals) if (a.species == species) n++;
        return n;
    }

    public int shorePoints() { return shore.length / 2; }

    private static float dist(float ax, float az, float bx, float bz) { float dx = ax - bx, dz = az - bz; return (float) Math.sqrt(dx * dx + dz * dz); }
    private static float wrap(float a) { while (a > Math.PI) a -= 2 * Math.PI; while (a < -Math.PI) a += 2 * Math.PI; return a; }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
