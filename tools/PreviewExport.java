import com.abhijit.lifeengine.core.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Dumps the generated world + meshes + textures as raw little-endian buffers for the GL preview harness. */
public class PreviewExport {
    static Path dir;

    public static void main(String[] a) throws Exception {
        long seed = Long.parseLong(a[0]);
        dir = Paths.get(a[1]);
        Files.createDirectories(dir);
        long t0 = System.nanoTime();
        World w = WorldGen.generate(seed, 1f);
        log("world", t0);
        StringBuilder js = new StringBuilder("{");
        for (int step : new int[]{1, 2, 4, 8}) {
            t0 = System.nanoTime();
            TerrainMesher.Lod l = TerrainMesher.build(w, step);
            f32("terrain" + step + "_v", l.vertices); i32("terrain" + step + "_i", l.indices);
            i32("terrain" + step + "_co", l.chunkIndexOffset); i32("terrain" + step + "_cc", l.chunkIndexCount);
            f32("terrain" + step + "_cb", l.chunkBounds);
            log("terrain step " + step + " verts=" + l.vertices.length / 10 + " tris=" + l.indices.length / 3, t0);
        }
        float[][] water = TerrainMesher.waterMesh(w, 2);
        f32("water_v", water[0]); f32("water_i", water[1]);
        float[][] fall = TerrainMesher.waterfallMesh(w);
        f32("fall_v", fall[0]); f32("fall_i", fall[1]);
        float[][] ring = TerrainMesher.horizonRing(w);
        f32("ring_v", ring[0]); f32("ring_i", ring[1]);
        f32("height", w.height);
        u8("fields", TextureGen.worldFields(w));
        t0 = System.nanoTime();
        u8("tex_noise", TextureGen.noiseRGBA(256, 11));
        u8("tex_water", TextureGen.waterNormal(256, 12));
        u8("tex_leaf", TextureGen.leafAtlas(512, 13));
        log("textures", t0);
        String[] names = {"acacia", "riverine", "baobab", "bush", "rock0", "rock1", "rock2", "rock3", "palm"};
        for (int t = 0; t < World.T_COUNT; t++) {
            for (int lod = 0; lod < 2; lod++) {
                MeshData m;
                switch (t) {
                    case World.T_ACACIA: m = ModelFactory.acacia(lod, 100); break;
                    case World.T_RIVERINE: m = ModelFactory.riverine(lod, 200); break;
                    case World.T_BAOBAB: m = ModelFactory.baobab(lod, 300); break;
                    case World.T_BUSH: m = ModelFactory.bush(lod, 400); break;
                    case World.T_PALM: m = ModelFactory.palm(lod, 500); break;
                    default: m = ModelFactory.rock(t - World.T_ROCK0, lod, 600); break;
                }
                f32("mesh_" + names[t] + lod + "_v", m.vertices()); i32("mesh_" + names[t] + lod + "_i", m.indices());
                System.out.println(names[t] + " lod" + lod + " verts=" + m.vCount + " tris=" + m.iCount / 3 + " r=" + m.radius);
            }
            f32("inst_" + names[t], w.instances[t]);
        }
        MeshData g = ModelFactory.grassTuft(7, 12);
        f32("mesh_grass_v", g.vertices()); i32("mesh_grass_i", g.indices());
        MeshData gf = ModelFactory.grassTuftFar(8);
        f32("mesh_grassfar_v", gf.vertices()); i32("mesh_grassfar_i", gf.indices());
        js.append("\"seed\":").append(seed)
          .append(",\"fall\":[").append(w.fallX).append(',').append(w.fallTopY).append(',').append(w.fallTopZ).append(',').append(w.fallBottomZ).append(']')
          .append(",\"lake\":[").append(w.lakeX).append(',').append(w.lakeZ).append(',').append(w.lakeR).append(']')
          .append(",\"holes\":").append(arr(w.waterholes)).append(",\"kopjes\":").append(arr(w.kopjes))
          .append(",\"river\":").append(arr(w.riverPolyline)).append('}');
        Files.write(dir.resolve("world.json"), js.toString().getBytes());
    }

    static String arr(float[] f) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < f.length; i++) { if (i > 0) s.append(','); s.append(f[i]); }
        return s.append(']').toString();
    }

    static void log(String s, long t0) { System.out.printf("%s: %.0f ms%n", s, (System.nanoTime() - t0) / 1e6); }

    static void f32(String n, float[] d) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(d.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.asFloatBuffer().put(d);
        Files.write(dir.resolve(n + ".f32"), b.array());
    }

    static void i32(String n, int[] d) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(d.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.asIntBuffer().put(d);
        Files.write(dir.resolve(n + ".i32"), b.array());
    }

    static void u8(String n, byte[] d) throws IOException { Files.write(dir.resolve(n + ".u8"), d); }
}
