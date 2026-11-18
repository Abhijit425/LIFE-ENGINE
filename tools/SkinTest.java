import com.abhijit.lifeengine.core.gltf.*;
import java.nio.file.*;
public class SkinTest {
  public static void main(String[] a) throws Exception {
    for (String f : a) {
      long t0 = System.nanoTime();
      SkinnedModel m = GlbLoader.load(Files.readAllBytes(Paths.get(f)), null);
      long t1 = System.nanoTime();
      Animator an = new Animator(m);
      Animator.Pose p = an.newPose();
      String[] clips = {"Idle", "Walk", "Gallop", "Eating"};
      StringBuilder sb = new StringBuilder();
      for (SkinnedModel.Clip c : m.clips) sb.append(c.name).append(String.format("(%.2f) ", c.duration));
      System.out.printf("%s: verts=%d tris=%d joints=%d nodes=%d load=%.0fms%n  clips: %s%n", Paths.get(f).getFileName(), m.vertexCount, m.indices.length/3, m.jointCount(), m.nodeCount, (t1-t0)/1e6, sb);
      float[] jm = new float[m.jointCount()*16];
      for (String cn : clips) {
        SkinnedModel.Clip c = m.clip(cn);
        if (c == null) { System.out.println("  missing " + cn); continue; }
        an.sample(c, c.duration*0.3f, true, p);
        an.jointMatrices(p, jm, 0);
        float[] mn={1e9f,1e9f,1e9f}, mx={-1e9f,-1e9f,-1e9f};
        double headZ=0, tailZ=0; int hn=0;
        for (int v=0; v<m.vertexCount; v++) {
          int o=v*SkinnedModel.STRIDE; float[] r=new float[3];
          for (int k=0;k<4;k++){ float w=m.vertices[o+13+k]; if(w==0)continue; int j=(int)m.vertices[o+9+k];
            for(int row=0;row<3;row++) r[row]+=w*(jm[j*16+row]*m.vertices[o]+jm[j*16+4+row]*m.vertices[o+1]+jm[j*16+8+row]*m.vertices[o+2]+jm[j*16+12+row]); }
          for(int k=0;k<3;k++){mn[k]=Math.min(mn[k],r[k]);mx[k]=Math.max(mx[k],r[k]);}
          if (r[1] > 0) { } 
        }
        System.out.printf("  %-7s bbox x[%.2f,%.2f] y[%.2f,%.2f] z[%.2f,%.2f]%n", cn, mn[0],mx[0],mn[1],mx[1],mn[2],mx[2]);
      }
      // where is the head?
      for (int n=0;n<m.nodeCount;n++) if (m.nodeNames[n].toLowerCase().contains("head") || m.nodeNames[n].toLowerCase().contains("neck")) {
        float[] g=new float[16]; an.sample(m.clip("Idle"),0,true,p); an.jointMatrices(p,jm,0); an.nodeGlobal(n,g);
        System.out.printf("  node %s at (%.2f,%.2f,%.2f)%n", m.nodeNames[n], g[12],g[13],g[14]);
      }
    }
  }
}
