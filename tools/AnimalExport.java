import com.abhijit.lifeengine.core.*;
import com.abhijit.lifeengine.core.life.*;
import java.nio.*; import java.nio.file.*;
/** args: seed outDir simSeconds hour */
public class AnimalExport {
  public static void main(String[] a) throws Exception {
    long seed = Long.parseLong(a[0]); Path out = Paths.get(a[1]); float secs = Float.parseFloat(a[2]); float hour = Float.parseFloat(a[3]);
    World w = WorldGen.generate(seed, 1f);
    AnimalPoser poser = new AnimalPoser(n -> Files.readAllBytes(Paths.get("app/src/main/assets/animals", n + ".glb")));
    AnimalSystem sys = new AnimalSystem(w, seed, poser.modelLengths(), 1f);
    for (float t = 0; t < secs; t += 0.05f) sys.update(0.05f, hour);
    int J = poser.maxJoints;
    StringBuilder js = new StringBuilder("{\"maxJoints\":" + J + ",\"species\":[");
    for (int s = 0; s < Species.ALL.length; s++) {
      Species sp = Species.ALL[s];
      var m = poser.models[s];
      f32(out.resolve("an_" + s + "_v.f32"), m.vertices); i32(out.resolve("an_" + s + "_i.i32"), m.indices);
      int n = sys.count(s);
      float[] inst = new float[n * 8]; float[] bones = new float[n * J * 16];
      int k = 0; float cx = 0, cz = 0;
      for (AnimalSystem.Animal an : sys.animals) if (an.species == s) {
        inst[k*8]=an.x; inst[k*8+1]=an.y; inst[k*8+2]=an.z; inst[k*8+3]=an.yaw;
        inst[k*8+4]=sp.scale; inst[k*8+5]=k; inst[k*8+6]=an.pitch; inst[k*8+7]=(an.id * 0.618f) % 1f;
        poser.pose(an, bones, k * J * 16); cx += an.x; cz += an.z; k++;
      }
      f32(out.resolve("an_" + s + "_inst.f32"), inst); f32(out.resolve("an_" + s + "_bones.f32"), bones);
      AnimalSystem.Animal first = null; for (AnimalSystem.Animal an : sys.animals) if (an.species == s) { first = an; break; }
      js.append(s > 0 ? "," : "").append(String.format(java.util.Locale.US,
        "{\"id\":\"%s\",\"count\":%d,\"pattern\":%d,\"pc\":[%f,%f,%f],\"first\":[%f,%f,%f,%f,\"%s\"]}",
        sp.id, n, sp.pattern, sp.patternColour == null ? 0 : sp.patternColour[0], sp.patternColour == null ? 0 : sp.patternColour[1], sp.patternColour == null ? 0 : sp.patternColour[2],
        first.x, first.y, first.z, first.yaw, first.activity));
    }
    Files.write(out.resolve("animals.json"), js.append("]}").toString().getBytes());
    System.out.println(new String(Files.readAllBytes(out.resolve("animals.json"))));
  }
  static void f32(Path p, float[] d) throws Exception { ByteBuffer b = ByteBuffer.allocate(d.length*4).order(ByteOrder.LITTLE_ENDIAN); b.asFloatBuffer().put(d); Files.write(p, b.array()); }
  static void i32(Path p, int[] d) throws Exception { ByteBuffer b = ByteBuffer.allocate(d.length*4).order(ByteOrder.LITTLE_ENDIAN); b.asIntBuffer().put(d); Files.write(p, b.array()); }
}
