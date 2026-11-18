import com.abhijit.lifeengine.core.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Desktop check: generates a world, prints stats and writes a shaded top-down map PNG. */
public class WorldMap {
    public static void main(String[] a) throws Exception {
        long seed = a.length > 0 ? Long.parseLong(a[0]) : 2026L;
        long t0 = System.nanoTime();
        World w = WorldGen.generate(seed, 1.0f);
        System.out.printf("generate: %.0f ms%n", (System.nanoTime() - t0) / 1e6);
        float mn = 1e9f, mx = -1e9f;
        int water = 0, pool = 0;
        for (int k = 0; k < w.height.length; k++) {
            mn = Math.min(mn, w.height[k]); mx = Math.max(mx, w.height[k]);
            if (w.waterId[k] == 0) water++; if (w.waterId[k] == 1) pool++;
            if (Float.isNaN(w.height[k])) throw new RuntimeException("NaN height at " + k);
        }
        System.out.printf("height %.1f..%.1f  waterCells=%d poolCells=%d river pts=%d kopjes=%d%n",
                mn, mx, water, pool, w.riverPolyline.length / 2, w.kopjes.length / 3);
        for (int t = 0; t < World.T_COUNT; t++) System.out.print("type" + t + "=" + w.instanceCount[t] + " ");
        System.out.println();
        int R = World.RES;
        BufferedImage img = new BufferedImage(R, R, BufferedImage.TYPE_INT_RGB);
        float[] nrm = new float[3];
        for (int j = 0; j < R; j++) for (int i = 0; i < R; i++) {
            int id = j * R + i;
            float h = w.height[id];
            w.normalAtCell(i, j, nrm);
            float light = Math.max(0.15f, 0.6f * nrm[0] * -0.5f + nrm[1] * 0.8f + nrm[2] * -0.3f);
            float r, g, b;
            float m = w.moisture[id];
            r = 0.75f - 0.35f * m; g = 0.62f - 0.05f * m; b = 0.32f - 0.1f * m;
            float rock = Noise.smoothstep(0.25f, 0.45f, 1 - nrm[1]) + Noise.smoothstep(90, 160, h);
            rock = Math.min(1, rock);
            r = Noise.lerp(r, 0.5f, rock); g = Noise.lerp(g, 0.47f, rock); b = Noise.lerp(b, 0.44f, rock);
            r = Noise.lerp(r, 0.86f, w.sand[id]); g = Noise.lerp(g, 0.78f, w.sand[id]); b = Noise.lerp(b, 0.58f, w.sand[id]);
            r = Noise.lerp(r, 0.35f, w.mud[id]); g = Noise.lerp(g, 0.27f, w.mud[id]); b = Noise.lerp(b, 0.2f, w.mud[id]);
            r *= light; g *= light; b *= light;
            if (w.waterId[id] >= 0 && h < w.waterLevels[w.waterId[id]]) {
                float d = Math.min(1, (w.waterLevels[w.waterId[id]] - h) / 6f);
                r = Noise.lerp(0.2f, 0.05f, d); g = Noise.lerp(0.45f, 0.2f, d); b = Noise.lerp(0.5f, 0.35f, d);
            }
            img.setRGB(i, j, rgb(r, g, b));
        }
        int[] colors = {0x1f6f2a, 0x0f4a1c, 0x5a3a12, 0x6f8f3a, 0x777777, 0x777777, 0x777777, 0x777777, 0x2f8f3f};
        for (int t = 0; t < World.T_COUNT; t++) {
            float[] in = w.instances[t];
            for (int k = 0; k < w.instanceCount[t]; k++) {
                int i = (int) ((in[k * 6] + World.HALF) / World.CELL), j = (int) ((in[k * 6 + 2] + World.HALF) / World.CELL);
                if (i >= 0 && j >= 0 && i < R && j < R && t != World.T_BUSH && t < World.T_ROCK0 || t == World.T_PALM || t == World.T_BAOBAB)
                    if (i >= 0 && j >= 0 && i < R && j < R) img.setRGB(i, j, colors[t]);
            }
        }
        File out = new File(a.length > 1 ? a[1] : "worldmap.png");
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out.getAbsolutePath());
    }

    static int rgb(float r, float g, float b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }
    static int clamp(float v) { return Math.max(0, Math.min(255, (int) (v * 255))); }
}
