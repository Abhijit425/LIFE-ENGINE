package com.abhijit.lifeengine.core.gltf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Loads a binary glTF 2.0 (.glb) file with one skinned mesh: geometry of all primitives (merged),
 * material base colours, node hierarchy, skin and TRS animation clips (LINEAR / STEP; CUBICSPLINE uses
 * the spline's value keys, which is exact at keys).
 */
public final class GlbLoader {
    private GlbLoader() {}

    /** Optional per-material colour override: return null to keep the file's base colour. */
    public interface Palette { float[] colour(String materialName, float[] fileColour); }

    public static SkinnedModel load(byte[] glb, Palette palette) {
        ByteBuffer bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.getInt(0) != 0x46546C67) throw new IllegalArgumentException("not a GLB file");
        int jsonLen = bb.getInt(12);
        String jsonText = new String(glb, 20, jsonLen, StandardCharsets.UTF_8);
        int binStart = 20 + jsonLen + 8;
        Map<String, Object> root = Json.obj(Json.parse(jsonText));
        Ctx c = new Ctx(root, bb, binStart);

        SkinnedModel m = new SkinnedModel();
        List<Object> nodes = Json.arr(root.get("nodes"));
        int n = nodes.size();
        m.nodeCount = n;
        m.parent = new int[n];
        Arrays.fill(m.parent, -1);
        m.restT = new float[n * 3]; m.restR = new float[n * 4]; m.restS = new float[n * 3];
        m.nodeNames = new String[n];
        int meshNode = -1;
        for (int k = 0; k < n; k++) {
            Map<String, Object> nd = Json.obj(nodes.get(k));
            m.nodeNames[k] = Json.str(nd, "name", "node" + k);
            for (Object ch : Json.arr(nd.get("children"))) m.parent[((Double) ch).intValue()] = k;
            float[] t = vec(nd.get("translation"), new float[]{0, 0, 0});
            float[] r = vec(nd.get("rotation"), new float[]{0, 0, 0, 1});
            float[] s = vec(nd.get("scale"), new float[]{1, 1, 1});
            if (nd.containsKey("matrix")) decompose(vec(nd.get("matrix"), null), t, r, s);
            System.arraycopy(t, 0, m.restT, k * 3, 3);
            System.arraycopy(r, 0, m.restR, k * 4, 4);
            System.arraycopy(s, 0, m.restS, k * 3, 3);
            if (nd.containsKey("mesh") && nd.containsKey("skin") && meshNode < 0) meshNode = k;
        }
        if (meshNode < 0) throw new IllegalArgumentException("no skinned mesh node");
        m.order = topoOrder(m.parent);

        // skin
        Map<String, Object> meshNodeObj = Json.obj(nodes.get(meshNode));
        Map<String, Object> skin = Json.obj(Json.arr(root.get("skins")).get(Json.i(meshNodeObj, "skin", 0)));
        List<Object> joints = Json.arr(skin.get("joints"));
        m.jointNode = new int[joints.size()];
        for (int j = 0; j < joints.size(); j++) m.jointNode[j] = ((Double) joints.get(j)).intValue();
        m.inverseBind = c.floats(Json.i(skin, "inverseBindMatrices", -1));

        // materials
        List<Object> mats = Json.arr(root.get("materials"));
        float[][] matColour = new float[mats.size()][];
        for (int k = 0; k < mats.size(); k++) {
            Map<String, Object> mt = Json.obj(mats.get(k));
            String name = Json.str(mt, "name", "mat" + k);
            m.materialNames.add(name);
            float[] col = {0.8f, 0.8f, 0.8f};
            Object pbr = mt.get("pbrMetallicRoughness");
            if (pbr != null) {
                Object bc = Json.obj(pbr).get("baseColorFactor");
                if (bc != null) { float[] v = vec(bc, null); col = new float[]{v[0], v[1], v[2]}; }
            }
            if (palette != null) { float[] o = palette.colour(name, col); if (o != null) col = o; }
            matColour[k] = col;
        }

        // geometry: merge all primitives of the mesh
        Map<String, Object> mesh = Json.obj(Json.arr(root.get("meshes")).get(Json.i(meshNodeObj, "mesh", 0)));
        List<Object> prims = Json.arr(mesh.get("primitives"));
        int totalV = 0, totalI = 0;
        for (Object po : prims) {
            Map<String, Object> p = Json.obj(po);
            Map<String, Object> at = Json.obj(p.get("attributes"));
            totalV += c.count(Json.i(at, "POSITION", -1));
            totalI += c.count(Json.i(p, "indices", -1));
        }
        float[] v = new float[totalV * SkinnedModel.STRIDE];
        int[] idx = new int[totalI];
        int vBase = 0, iBase = 0;
        float[] mn = {1e9f, 1e9f, 1e9f}, mx = {-1e9f, -1e9f, -1e9f};
        for (Object po : prims) {
            Map<String, Object> p = Json.obj(po);
            Map<String, Object> at = Json.obj(p.get("attributes"));
            float[] pos = c.floats(Json.i(at, "POSITION", -1));
            float[] nrm = c.floats(Json.i(at, "NORMAL", -1));
            float[] jnt = c.floats(Json.i(at, "JOINTS_0", -1));
            float[] wgt = c.floats(Json.i(at, "WEIGHTS_0", -1));
            int mi = Json.i(p, "material", -1);
            float[] col = mi >= 0 ? matColour[mi] : new float[]{0.8f, 0.8f, 0.8f};
            float coat = mi >= 0 ? coatClass(m.materialNames.get(mi)) : 0;
            int cnt = pos.length / 3;
            for (int k = 0; k < cnt; k++) {
                int o = (vBase + k) * SkinnedModel.STRIDE;
                for (int a = 0; a < 3; a++) {
                    v[o + a] = pos[k * 3 + a];
                    mn[a] = Math.min(mn[a], pos[k * 3 + a]); mx[a] = Math.max(mx[a], pos[k * 3 + a]);
                    v[o + 3 + a] = nrm != null ? nrm[k * 3 + a] : (a == 1 ? 1 : 0);
                    v[o + 6 + a] = col[a];
                }
                float ws = 0;
                for (int a = 0; a < 4; a++) {
                    v[o + 9 + a] = jnt != null ? jnt[k * 4 + a] : 0;
                    float w = wgt != null ? wgt[k * 4 + a] : (a == 0 ? 1 : 0);
                    v[o + 13 + a] = w; ws += w;
                }
                if (ws > 1e-6f) for (int a = 0; a < 4; a++) v[o + 13 + a] /= ws;
                v[o + 17] = coat;
            }
            int ia = Json.i(p, "indices", -1);
            if (ia >= 0) {
                float[] ind = c.floats(ia);
                for (int k = 0; k < ind.length; k++) idx[iBase + k] = vBase + (int) ind[k];
                iBase += ind.length;
            }
            vBase += cnt;
        }
        m.vertices = v;
        m.indices = Arrays.copyOf(idx, iBase);
        m.vertexCount = vBase;
        m.boundsMin = mn; m.boundsMax = mx;

        // animations
        for (Object ao : Json.arr(root.get("animations"))) {
            Map<String, Object> a = Json.obj(ao);
            SkinnedModel.Clip clip = new SkinnedModel.Clip(Json.str(a, "name", "clip" + m.clips.size()));
            List<Object> samplers = Json.arr(a.get("samplers"));
            List<Object> chans = Json.arr(a.get("channels"));
            int nc = chans.size();
            clip.node = new int[nc]; clip.path = new int[nc]; clip.times = new float[nc][]; clip.values = new float[nc][];
            int used = 0;
            for (Object cho : chans) {
                Map<String, Object> ch = Json.obj(cho);
                Map<String, Object> tg = Json.obj(ch.get("target"));
                String path = Json.str(tg, "path", "");
                int pathId = path.equals("translation") ? 0 : path.equals("rotation") ? 1 : path.equals("scale") ? 2 : -1;
                if (pathId < 0 || !tg.containsKey("node")) continue;
                Map<String, Object> smp = Json.obj(samplers.get(Json.i(ch, "sampler", 0)));
                float[] times = c.floats(Json.i(smp, "input", -1));
                float[] vals = c.floats(Json.i(smp, "output", -1));
                if ("CUBICSPLINE".equals(Json.str(smp, "interpolation", "LINEAR"))) vals = cubicValues(vals, pathId == 1 ? 4 : 3);
                clip.node[used] = Json.i(tg, "node", 0);
                clip.path[used] = pathId;
                clip.times[used] = times;
                clip.values[used] = vals;
                if (times.length > 0) clip.duration = Math.max(clip.duration, times[times.length - 1]);
                used++;
            }
            if (used < nc) {
                clip.node = Arrays.copyOf(clip.node, used); clip.path = Arrays.copyOf(clip.path, used);
                clip.times = Arrays.copyOf(clip.times, used); clip.values = Arrays.copyOf(clip.values, used);
            }
            m.clips.add(clip);
        }
        return m;
    }

    private static float coatClass(String name) {
        switch (name) {
            case "Main": return 1;
            case "Main_Light": return 2;
            case "Main_Dark": return 3;
            default: return 0;
        }
    }

    private static float[] cubicValues(float[] v, int comps) {
        int keys = v.length / (comps * 3);
        float[] out = new float[keys * comps];
        for (int k = 0; k < keys; k++) System.arraycopy(v, (k * 3 + 1) * comps, out, k * comps, comps);
        return out;
    }

    private static float[] vec(Object o, float[] def) {
        if (o == null) return def;
        List<Object> l = Json.arr(o);
        float[] r = new float[l.size()];
        for (int k = 0; k < r.length; k++) r[k] = Json.f(l.get(k));
        return r;
    }

    private static int[] topoOrder(int[] parent) {
        int n = parent.length;
        int[] order = new int[n];
        boolean[] done = new boolean[n];
        int o = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int k = 0; k < n; k++) if (parent[k] < 0) q.add(k);
        while (!q.isEmpty()) {
            int k = q.poll();
            if (done[k]) continue;
            done[k] = true;
            order[o++] = k;
            for (int c = 0; c < n; c++) if (parent[c] == k && !done[c]) q.add(c);
        }
        for (int k = 0; k < n; k++) if (!done[k]) order[o++] = k;
        return order;
    }

    /** Decomposes a column-major TRS matrix (no shear). */
    private static void decompose(float[] mt, float[] t, float[] r, float[] s) {
        t[0] = mt[12]; t[1] = mt[13]; t[2] = mt[14];
        s[0] = (float) Math.sqrt(mt[0] * mt[0] + mt[1] * mt[1] + mt[2] * mt[2]);
        s[1] = (float) Math.sqrt(mt[4] * mt[4] + mt[5] * mt[5] + mt[6] * mt[6]);
        s[2] = (float) Math.sqrt(mt[8] * mt[8] + mt[9] * mt[9] + mt[10] * mt[10]);
        float m00 = mt[0] / s[0], m10 = mt[1] / s[0], m20 = mt[2] / s[0];
        float m01 = mt[4] / s[1], m11 = mt[5] / s[1], m21 = mt[6] / s[1];
        float m02 = mt[8] / s[2], m12 = mt[9] / s[2], m22 = mt[10] / s[2];
        float tr = m00 + m11 + m22;
        if (tr > 0) {
            float q = (float) Math.sqrt(tr + 1) * 2;
            r[3] = 0.25f * q; r[0] = (m21 - m12) / q; r[1] = (m02 - m20) / q; r[2] = (m10 - m01) / q;
        } else if (m00 > m11 && m00 > m22) {
            float q = (float) Math.sqrt(1 + m00 - m11 - m22) * 2;
            r[3] = (m21 - m12) / q; r[0] = 0.25f * q; r[1] = (m01 + m10) / q; r[2] = (m02 + m20) / q;
        } else if (m11 > m22) {
            float q = (float) Math.sqrt(1 + m11 - m00 - m22) * 2;
            r[3] = (m02 - m20) / q; r[0] = (m01 + m10) / q; r[1] = 0.25f * q; r[2] = (m12 + m21) / q;
        } else {
            float q = (float) Math.sqrt(1 + m22 - m00 - m11) * 2;
            r[3] = (m10 - m01) / q; r[0] = (m02 + m20) / q; r[1] = (m12 + m21) / q; r[2] = 0.25f * q;
        }
    }

    /** Accessor reader. */
    private static final class Ctx {
        final List<Object> accessors, views;
        final ByteBuffer bin;
        final int binStart;

        Ctx(Map<String, Object> root, ByteBuffer bin, int binStart) {
            accessors = Json.arr(root.get("accessors"));
            views = Json.arr(root.get("bufferViews"));
            this.bin = bin; this.binStart = binStart;
        }

        int count(int a) { return a < 0 ? 0 : Json.i(Json.obj(accessors.get(a)), "count", 0); }

        float[] floats(int a) {
            if (a < 0) return null;
            Map<String, Object> acc = Json.obj(accessors.get(a));
            int count = Json.i(acc, "count", 0);
            String type = Json.str(acc, "type", "SCALAR");
            int comps = type.equals("SCALAR") ? 1 : type.equals("VEC2") ? 2 : type.equals("VEC3") ? 3 : type.equals("VEC4") ? 4 : type.equals("MAT4") ? 16 : 1;
            int ct = Json.i(acc, "componentType", 5126);
            boolean norm = Boolean.TRUE.equals(acc.get("normalized"));
            int size = ct == 5126 || ct == 5125 ? 4 : ct == 5123 || ct == 5122 ? 2 : 1;
            float[] out = new float[count * comps];
            if (!acc.containsKey("bufferView")) return out; // all zeros
            Map<String, Object> view = Json.obj(views.get(Json.i(acc, "bufferView", 0)));
            int stride = Json.i(view, "byteStride", 0);
            if (stride == 0) stride = comps * size;
            int base = binStart + Json.i(view, "byteOffset", 0) + Json.i(acc, "byteOffset", 0);
            for (int e = 0; e < count; e++) {
                int p = base + e * stride;
                for (int k = 0; k < comps; k++) {
                    int q = p + k * size;
                    float val;
                    switch (ct) {
                        case 5126: val = bin.getFloat(q); break;
                        case 5125: val = bin.getInt(q) & 0xFFFFFFFFL; break;
                        case 5123: val = bin.getShort(q) & 0xFFFF; if (norm) val /= 65535f; break;
                        case 5122: val = bin.getShort(q); if (norm) val = Math.max(val / 32767f, -1f); break;
                        case 5121: val = bin.get(q) & 0xFF; if (norm) val /= 255f; break;
                        case 5120: val = bin.get(q); if (norm) val = Math.max(val / 127f, -1f); break;
                        default: val = 0;
                    }
                    out[e * comps + k] = val;
                }
            }
            return out;
        }
    }
}
