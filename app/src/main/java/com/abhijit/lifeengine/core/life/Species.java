package com.abhijit.lifeengine.core.life;

import java.util.HashMap;
import java.util.Map;

/**
 * Species catalogue. Every model is a verified CC0 file from Quaternius' "Ultimate Animated Animals" pack.
 * Where no CC0 animated model of the real species exists yet, the closest rig is used as a clearly labelled
 * stand-in with a species-appropriate coat (see {@link #standIn}).
 */
public final class Species {
    public static final int PAT_NONE = 0, PAT_STRIPES = 1, PAT_HYENA = 2, PAT_ROSETTES = 3, PAT_CHEETAH = 4,
            PAT_GAZELLE = 5, PAT_MANE = 6;
    public static final int GRAZER = 0, BROWSER = 1, CARNIVORE = 2;

    public final String id, name, latin, model;
    public final float scale;                // model units -> metres
    public final Map<String, float[]> palette = new HashMap<>();
    public final int pattern;
    public final float[] patternColour;
    public final float walkSpeed, runSpeed;  // m/s
    public final int herdMin, herdMax, groups;
    public final int diet;
    public final boolean nocturnal;
    public final String standIn;             // null when the model is the real species
    public final String clipIdle, clipIdle2, clipEat, clipWalk, clipRun, clipRest;

    private Species(String id, String name, String latin, String model, float scale, int pattern, float[] patternColour,
                    float walk, float run, int herdMin, int herdMax, int groups, int diet, boolean nocturnal, String standIn) {
        this.id = id; this.name = name; this.latin = latin; this.model = model; this.scale = scale;
        this.pattern = pattern; this.patternColour = patternColour;
        this.walkSpeed = walk; this.runSpeed = run; this.herdMin = herdMin; this.herdMax = herdMax; this.groups = groups;
        this.diet = diet; this.nocturnal = nocturnal; this.standIn = standIn;
        boolean canine = model.equals("wolf") || model.equals("fox");
        clipIdle = "Idle"; clipIdle2 = "Idle_2"; clipEat = "Eating"; clipWalk = "Walk"; clipRun = "Gallop";
        clipRest = canine ? "Idle_2_HeadLow" : "Idle_Headlow";
    }

    private Species pal(String material, float r, float g, float b) { palette.put(material, new float[]{r, g, b}); return this; }

    public float[] colour(String material) { return palette.get(material); }

    public static final Species[] ALL = {
        new Species("zebra", "Plains zebra", "Equus quagga", "horse", 0.36f, PAT_STRIPES, new float[]{0.012f, 0.011f, 0.011f},
                1.5f, 9f, 8, 14, 3, GRAZER, false, "Horse rig with zebra coat")
                .pal("Main", 0.78f, 0.77f, 0.73f).pal("Main_Light", 0.80f, 0.79f, 0.75f).pal("Main_Dark", 0.02f, 0.02f, 0.02f)
                .pal("Hair", 0.02f, 0.02f, 0.02f).pal("Muzzle", 0.025f, 0.022f, 0.02f).pal("Hooves", 0.04f, 0.035f, 0.03f),
        new Species("impala", "Impala", "Aepyceros melampus", "deer", 0.33f, PAT_NONE, null,
                1.3f, 9f, 8, 14, 3, BROWSER, false, null)
                .pal("Main", 0.33f, 0.15f, 0.055f).pal("Main_Light", 0.72f, 0.67f, 0.60f).pal("Main_Dark", 0.14f, 0.06f, 0.025f),
        new Species("gazelle", "Thomson's gazelle", "Eudorcas thomsonii", "deer", 0.25f, PAT_GAZELLE, new float[]{0.03f, 0.018f, 0.01f},
                1.2f, 11f, 10, 18, 3, GRAZER, false, "Deer rig, gazelle coat")
                .pal("Main", 0.45f, 0.26f, 0.10f).pal("Main_Light", 0.78f, 0.75f, 0.70f).pal("Main_Dark", 0.10f, 0.05f, 0.02f),
        new Species("buffalo", "African buffalo", "Syncerus caffer", "bull", 0.38f, PAT_NONE, null,
                1.2f, 7f, 12, 20, 2, GRAZER, false, null)
                .pal("Main", 0.03f, 0.027f, 0.024f).pal("Main_Light", 0.07f, 0.062f, 0.055f).pal("Horns", 0.06f, 0.055f, 0.05f)
                .pal("Muzzle", 0.02f, 0.018f, 0.016f).pal("Hooves", 0.03f, 0.025f, 0.02f),
        new Species("hyena", "Spotted hyena", "Crocuta crocuta", "wolf", 0.31f, PAT_HYENA, new float[]{0.05f, 0.035f, 0.02f},
                1.4f, 8f, 4, 7, 2, CARNIVORE, true, "Wolf rig, spotted coat")
                .pal("Main", 0.34f, 0.27f, 0.16f).pal("Main_Light", 0.26f, 0.21f, 0.14f).pal("Nose", 0.02f, 0.02f, 0.02f),
        new Species("lion", "Lion", "Panthera leo", "wolf", 0.46f, PAT_MANE, new float[]{0.13f, 0.06f, 0.02f},
                1.2f, 9f, 3, 6, 2, CARNIVORE, true, "Wolf rig, lion coat")
                .pal("Main", 0.46f, 0.29f, 0.11f).pal("Main_Light", 0.58f, 0.45f, 0.27f).pal("Nose", 0.08f, 0.04f, 0.03f),
        new Species("leopard", "Leopard", "Panthera pardus", "fox", 0.40f, PAT_ROSETTES, new float[]{0.02f, 0.015f, 0.01f},
                1.0f, 8f, 1, 1, 3, CARNIVORE, true, "Fox rig, rosette coat")
                .pal("Main", 0.52f, 0.33f, 0.11f).pal("Main_Light", 0.72f, 0.64f, 0.48f).pal("Black", 0.03f, 0.025f, 0.02f)
                .pal("Grey", 0.72f, 0.64f, 0.48f),
        new Species("cheetah", "Cheetah", "Acinonyx jubatus", "fox", 0.38f, PAT_CHEETAH, new float[]{0.015f, 0.012f, 0.01f},
                1.1f, 14f, 1, 3, 2, CARNIVORE, false, "Fox rig, cheetah coat")
                .pal("Main", 0.58f, 0.40f, 0.15f).pal("Main_Light", 0.76f, 0.68f, 0.52f).pal("Black", 0.03f, 0.025f, 0.02f)
                .pal("Grey", 0.76f, 0.68f, 0.52f),
    };

    /** Species from the brief that have no verified CC0 animated model yet. */
    public static final String[][] MISSING = {
        {"African elephant", "Loxodonta africana"}, {"Giraffe", "Giraffa camelopardalis"},
        {"Common ostrich", "Struthio camelus"}, {"Nile crocodile", "Crocodylus niloticus"},
    };

    public static int index(String id) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i].id.equals(id)) return i;
        return -1;
    }
}
