import com.abhijit.lifeengine.core.*;
import java.nio.*; import java.nio.file.*;
/** args: sunx suny sunz moonx moony moonz turbidity overcast outPrefix */
public class AtmoDump {
  public static void main(String[] a) throws Exception {
    float[] f = new float[8]; for (int i = 0; i < 8; i++) f[i] = Float.parseFloat(a[i]);
    Atmosphere at = new Atmosphere();
    at.update(new float[]{f[0], f[1], f[2]}, new float[]{f[3], f[4], f[5]}, f[6], f[7]);
    ByteBuffer b = ByteBuffer.allocate(at.lut.length * 4).order(ByteOrder.LITTLE_ENDIAN); b.asFloatBuffer().put(at.lut);
    Files.write(Paths.get(a[8] + ".f32"), b.array());
    String js = String.format(java.util.Locale.US, "{\"sun\":[%f,%f,%f],\"moon\":[%f,%f,%f],\"ambSky\":[%f,%f,%f],\"ambGround\":[%f,%f,%f],\"exposure\":%f}",
      at.sunColor[0], at.sunColor[1], at.sunColor[2], at.moonColor[0], at.moonColor[1], at.moonColor[2],
      at.ambientSky[0], at.ambientSky[1], at.ambientSky[2], at.ambientGround[0], at.ambientGround[1], at.ambientGround[2], at.exposure);
    Files.write(Paths.get(a[8] + ".json"), js.getBytes());
  }
}
