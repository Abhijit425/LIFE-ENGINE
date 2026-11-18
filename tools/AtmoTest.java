import com.abhijit.lifeengine.core.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
public class AtmoTest {
  public static void main(String[] a) throws Exception {
    float[] elevs = {60, 20, 5, 1, -3, -12};
    int W = Atmosphere.LUT_W, H = Atmosphere.LUT_H;
    BufferedImage img = new BufferedImage(W, H * elevs.length, BufferedImage.TYPE_INT_RGB);
    Atmosphere at = new Atmosphere();
    for (int k = 0; k < elevs.length; k++) {
      double e = Math.toRadians(elevs[k]);
      float[] sun = {(float) Math.cos(e), (float) Math.sin(e), 0};
      float[] moon = {-sun[0], -sun[1], 0.2f};
      long t0 = System.nanoTime();
      at.update(sun, moon, 1.6f, 0f);
      System.out.printf("elev %5.1f: %.1fms sun=(%.2f %.2f %.2f) amb=(%.3f %.3f %.3f) exp=%.2f%n", elevs[k], (System.nanoTime()-t0)/1e6,
        at.sunColor[0], at.sunColor[1], at.sunColor[2], at.ambientSky[0], at.ambientSky[1], at.ambientSky[2], at.exposure);
      for (int j = 0; j < H; j++) for (int i = 0; i < W; i++) {
        int o = (j * W + i) * 3; int rgb = 0;
        for (int c = 0; c < 3; c++) { float v = at.lut[o + c] * at.exposure; v = v/(1+v); v = (float)Math.pow(v, 1/2.2); rgb = (rgb << 8) | Math.min(255, (int)(v*255)); }
        img.setRGB(i, k * H + (H - 1 - j), rgb);
      }
    }
    ImageIO.write(img, "png", new File(a[0]));
  }
}
