import com.abhijit.lifeengine.core.*;
import com.abhijit.lifeengine.core.gltf.*;
import com.abhijit.lifeengine.core.life.*;
import java.nio.file.*; import java.util.*;
public class LifeSim {
  public static void main(String[] a) throws Exception {
    World w = WorldGen.generate(2026, 1f);
    String dir = a[0];
    float[] lens = new float[Species.ALL.length];
    for (int s = 0; s < lens.length; s++) {
      SkinnedModel m = GlbLoader.load(Files.readAllBytes(Paths.get(dir, Species.ALL[s].model + ".glb")), null);
      lens[s] = m.boundsMax[2] - m.boundsMin[2];
    }
    long t0 = System.nanoTime();
    AnimalSystem sys = new AnimalSystem(w, 2026, lens, 1f);
    System.out.printf("spawn %.0f ms, animals=%d shorePoints=%d%n", (System.nanoTime()-t0)/1e6, sys.animals.size(), sys.shorePoints());
    for (int s = 0; s < Species.ALL.length; s++) System.out.print(Species.ALL[s].id + "=" + sys.count(s) + "(len " + String.format("%.1f", lens[s]*Species.ALL[s].scale) + "m) ");
    System.out.println();
    Map<String,Integer> acts = new TreeMap<>();
    float[] startX = new float[sys.animals.size()], startZ = new float[sys.animals.size()];
    for (int i = 0; i < startX.length; i++) { startX[i] = sys.animals.get(i).x; startZ[i] = sys.animals.get(i).z; }
    float hour = 9f; long u0 = System.nanoTime(); int steps = 0; int wet = 0, nan = 0, drinkNear = 0, drinkTotal = 0;
    for (float t = 0; t < 1800; t += 0.1f) {
      sys.update(0.1f, hour + t / 3600f); steps++;
      if (steps % 600 == 0) for (AnimalSystem.Animal an : sys.animals) {
        acts.merge(an.activity, 1, Integer::sum);
        if (Float.isNaN(an.x) || Float.isNaN(an.yaw)) nan++;
        if (an.y < w.waterLevelAt(an.x, an.z) - 0.05f) wet++;
        if (an.activity.equals("Drinking")) { drinkTotal++; float lvl = 0; if (an.y < 1.2f) drinkNear++; }
      }
    }
    double perStep = (System.nanoTime()-u0)/1e6/steps;
    double moved = 0; for (int i = 0; i < startX.length; i++) moved += Math.hypot(sys.animals.get(i).x - startX[i], sys.animals.get(i).z - startZ[i]);
    System.out.printf("update avg %.3f ms/step, mean displacement 30 min = %.0f m, NaN=%d inWater=%d drinking samples=%d (at shore %d)%n", perStep, moved/startX.length, nan, wet, drinkTotal, drinkNear);
    System.out.println("activity samples: " + acts);
  }
}
