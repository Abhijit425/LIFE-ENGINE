package com.abhijit.lifeengine.core.gltf;

import java.util.ArrayList;
import java.util.List;

/**
 * A skinned glTF 2.0 model flattened for rendering.
 * Vertex layout (STRIDE = 18): position(3) normal(3) colour(3) joints(4) weights(4) coatClass(1)
 * coatClass: 0 = not coat (eyes, hooves, horns...), 1 = main coat, 2 = light coat (belly/muzzle), 3 = dark coat.
 */
public final class SkinnedModel {
    public static final int STRIDE = 18;

    public float[] vertices;
    public int[] indices;
    public int vertexCount;

    // node hierarchy (all nodes in the file)
    public int nodeCount;
    public int[] parent;          // -1 for roots
    public int[] order;           // parents before children
    public float[] restT, restR, restS;
    public String[] nodeNames;

    // skin
    public int[] jointNode;       // joint index -> node index
    public float[] inverseBind;   // 16 floats per joint, column-major

    public final List<Clip> clips = new ArrayList<>();
    public final List<String> materialNames = new ArrayList<>();
    public float[] boundsMin = new float[3], boundsMax = new float[3];

    public int jointCount() { return jointNode.length; }

    public Clip clip(String name) {
        for (Clip c : clips) if (c.name.equals(name)) return c;
        return null;
    }

    public static final class Clip {
        public final String name;
        public float duration;
        public int[] node;       // per channel
        public int[] path;       // 0 translation, 1 rotation, 2 scale
        public float[][] times;
        public float[][] values;

        public Clip(String name) { this.name = name; }
    }
}
