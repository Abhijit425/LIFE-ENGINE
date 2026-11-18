package com.abhijit.lifeengine.core.life;

import com.abhijit.lifeengine.core.gltf.Animator;
import com.abhijit.lifeengine.core.gltf.GlbLoader;
import com.abhijit.lifeengine.core.gltf.SkinnedModel;

/**
 * Loads one skinned model per species (with the species palette applied) and turns an animal's clip state
 * into joint matrices: sample current clip, cross-fade from the previous clip, write global*inverseBind.
 */
public final class AnimalPoser {
    public final SkinnedModel[] models = new SkinnedModel[Species.ALL.length];
    private final Animator[] animators = new Animator[Species.ALL.length];
    private final Animator.Pose[] poseA = new Animator.Pose[Species.ALL.length];
    private final Animator.Pose[] poseB = new Animator.Pose[Species.ALL.length];
    private final SkinnedModel.Clip[][] clips = new SkinnedModel.Clip[Species.ALL.length][6];
    public int maxJoints;

    public interface Source { byte[] read(String modelName) throws Exception; }

    public AnimalPoser(Source src) throws Exception {
        for (int s = 0; s < Species.ALL.length; s++) {
            final Species sp = Species.ALL[s];
            SkinnedModel m = GlbLoader.load(src.read(sp.model), (name, col) -> sp.colour(name));
            models[s] = m;
            animators[s] = new Animator(m);
            poseA[s] = animators[s].newPose();
            poseB[s] = animators[s].newPose();
            String[] names = {sp.clipIdle, sp.clipIdle2, sp.clipEat, sp.clipWalk, sp.clipRun, sp.clipRest};
            for (int c = 0; c < 6; c++) {
                clips[s][c] = m.clip(names[c]);
                if (clips[s][c] == null) clips[s][c] = m.clip("Idle");
            }
            maxJoints = Math.max(maxJoints, m.jointCount());
        }
    }

    /** Body length in model units (for behaviour spacing). */
    public float[] modelLengths() {
        float[] l = new float[models.length];
        for (int s = 0; s < l.length; s++) l[s] = models[s].boundsMax[2] - models[s].boundsMin[2];
        return l;
    }

    public void pose(AnimalSystem.Animal a, float[] out, int offset) {
        int s = a.species;
        Animator an = animators[s];
        an.sample(clips[s][a.clip], a.clipTime, true, poseA[s]);
        if (a.fade < 1f) {
            an.sample(clips[s][a.prevClip], a.prevClipTime, true, poseB[s]);
            float f = a.fade * a.fade * (3f - 2f * a.fade);
            Animator.blend(poseB[s], poseA[s], f, poseA[s]);
        }
        an.jointMatrices(poseA[s], out, offset);
    }
}
