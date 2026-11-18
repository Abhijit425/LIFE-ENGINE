package com.abhijit.lifeengine.core.gltf;

/**
 * Samples clips into local TRS poses, cross-fades poses and produces joint (skinning) matrices.
 * All matrices are column-major 4x4, matching OpenGL.
 */
public final class Animator {
    private final SkinnedModel m;
    private final float[] global;     // 16 per node
    private final float[] local = new float[16];

    public Animator(SkinnedModel model) {
        this.m = model;
        this.global = new float[model.nodeCount * 16];
    }

    public static final class Pose {
        public final float[] t, r, s;
        public Pose(SkinnedModel m) { t = new float[m.nodeCount * 3]; r = new float[m.nodeCount * 4]; s = new float[m.nodeCount * 3]; }
    }

    public Pose newPose() { return new Pose(m); }

    /** Rest pose then overwrite animated channels at time t (looped when loop = true). */
    public void sample(SkinnedModel.Clip clip, float time, boolean loop, Pose out) {
        System.arraycopy(m.restT, 0, out.t, 0, out.t.length);
        System.arraycopy(m.restR, 0, out.r, 0, out.r.length);
        System.arraycopy(m.restS, 0, out.s, 0, out.s.length);
        if (clip == null || clip.duration <= 0) return;
        float t = loop ? (float) (time - Math.floor(time / clip.duration) * clip.duration) : Math.min(Math.max(time, 0), clip.duration);
        for (int c = 0; c < clip.node.length; c++) {
            float[] times = clip.times[c], vals = clip.values[c];
            int node = clip.node[c], path = clip.path[c];
            int comps = path == 1 ? 4 : 3;
            int n = times.length;
            int k1;
            if (t <= times[0]) k1 = 0;
            else if (t >= times[n - 1]) k1 = n - 1;
            else {
                int lo = 0, hi = n - 1;
                while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (times[mid] <= t) lo = mid; else hi = mid; }
                k1 = hi;
                float a = (t - times[lo]) / (times[hi] - times[lo]);
                write(out, node, path, vals, lo, hi, a, comps);
                continue;
            }
            write(out, node, path, vals, k1, k1, 0, comps);
        }
    }

    private static void write(Pose out, int node, int path, float[] v, int i0, int i1, float a, int comps) {
        float[] dst = path == 0 ? out.t : path == 1 ? out.r : out.s;
        int o = node * comps;
        if (path == 1) {
            float x0 = v[i0 * 4], y0 = v[i0 * 4 + 1], z0 = v[i0 * 4 + 2], w0 = v[i0 * 4 + 3];
            float x1 = v[i1 * 4], y1 = v[i1 * 4 + 1], z1 = v[i1 * 4 + 2], w1 = v[i1 * 4 + 3];
            nlerp(x0, y0, z0, w0, x1, y1, z1, w1, a, dst, o);
        } else {
            for (int k = 0; k < 3; k++) dst[o + k] = v[i0 * 3 + k] + (v[i1 * 3 + k] - v[i0 * 3 + k]) * a;
        }
    }

    private static void nlerp(float x0, float y0, float z0, float w0, float x1, float y1, float z1, float w1, float a, float[] d, int o) {
        if (x0 * x1 + y0 * y1 + z0 * z1 + w0 * w1 < 0) { x1 = -x1; y1 = -y1; z1 = -z1; w1 = -w1; }
        float x = x0 + (x1 - x0) * a, y = y0 + (y1 - y0) * a, z = z0 + (z1 - z0) * a, w = w0 + (w1 - w0) * a;
        float l = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        if (l < 1e-8f) { d[o] = 0; d[o + 1] = 0; d[o + 2] = 0; d[o + 3] = 1; return; }
        d[o] = x / l; d[o + 1] = y / l; d[o + 2] = z / l; d[o + 3] = w / l;
    }

    /** out = a*(1-w) + b*w (in place into out, which may alias a). */
    public static void blend(Pose a, Pose b, float w, Pose out) {
        for (int k = 0; k < a.t.length; k++) { out.t[k] = a.t[k] + (b.t[k] - a.t[k]) * w; out.s[k] = a.s[k] + (b.s[k] - a.s[k]) * w; }
        for (int k = 0; k < a.r.length; k += 4)
            nlerp(a.r[k], a.r[k + 1], a.r[k + 2], a.r[k + 3], b.r[k], b.r[k + 1], b.r[k + 2], b.r[k + 3], w, out.r, k);
    }

    /** Adds a pitch (radians, about the node's local X axis) to a named node — used for head-down drinking. */
    public void addPitch(Pose p, int node, float radians) {
        if (node < 0) return;
        float h = radians * 0.5f, qx = (float) Math.sin(h), qw = (float) Math.cos(h);
        int o = node * 4;
        float x = p.r[o], y = p.r[o + 1], z = p.r[o + 2], w = p.r[o + 3];
        // p = p * q
        p.r[o] = w * qx + x * qw;
        p.r[o + 1] = y * qw + z * qx;
        p.r[o + 2] = z * qw - y * qx;
        p.r[o + 3] = w * qw - x * qx;
    }

    /** Writes jointCount matrices (16 floats each) = global(joint) * inverseBind into out at offset. */
    public void jointMatrices(Pose p, float[] out, int offset) {
        for (int oi = 0; oi < m.order.length; oi++) {
            int n = m.order[oi];
            trs(p, n, local);
            int par = m.parent[n];
            if (par < 0) System.arraycopy(local, 0, global, n * 16, 16);
            else mul(global, par * 16, local, 0, global, n * 16);
        }
        for (int j = 0; j < m.jointNode.length; j++)
            mul(global, m.jointNode[j] * 16, m.inverseBind, j * 16, out, offset + j * 16);
    }

    /** Global (model-space) transform of a node after jointMatrices() — e.g. head position for cameras. */
    public void nodeGlobal(int node, float[] out16) { System.arraycopy(global, node * 16, out16, 0, 16); }

    private static void trs(Pose p, int n, float[] d) {
        float x = p.r[n * 4], y = p.r[n * 4 + 1], z = p.r[n * 4 + 2], w = p.r[n * 4 + 3];
        float sx = p.s[n * 3], sy = p.s[n * 3 + 1], sz = p.s[n * 3 + 2];
        d[0] = (1 - 2 * (y * y + z * z)) * sx; d[1] = (2 * (x * y + z * w)) * sx; d[2] = (2 * (x * z - y * w)) * sx; d[3] = 0;
        d[4] = (2 * (x * y - z * w)) * sy; d[5] = (1 - 2 * (x * x + z * z)) * sy; d[6] = (2 * (y * z + x * w)) * sy; d[7] = 0;
        d[8] = (2 * (x * z + y * w)) * sz; d[9] = (2 * (y * z - x * w)) * sz; d[10] = (1 - 2 * (x * x + y * y)) * sz; d[11] = 0;
        d[12] = p.t[n * 3]; d[13] = p.t[n * 3 + 1]; d[14] = p.t[n * 3 + 2]; d[15] = 1;
    }

    /** r = a * b (column-major). r must not alias a or b ranges. */
    static void mul(float[] a, int ao, float[] b, int bo, float[] r, int ro) {
        for (int c = 0; c < 4; c++) {
            float b0 = b[bo + c * 4], b1 = b[bo + c * 4 + 1], b2 = b[bo + c * 4 + 2], b3 = b[bo + c * 4 + 3];
            for (int row = 0; row < 4; row++)
                r[ro + c * 4 + row] = a[ao + row] * b0 + a[ao + 4 + row] * b1 + a[ao + 8 + row] * b2 + a[ao + 12 + row] * b3;
        }
    }
}
