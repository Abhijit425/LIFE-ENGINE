package com.abhijit.lifeengine.core

import com.abhijit.lifeengine.core.gltf.Animator
import com.abhijit.lifeengine.core.gltf.GlbLoader
import com.abhijit.lifeengine.core.life.AnimalPoser
import com.abhijit.lifeengine.core.life.AnimalSystem
import com.abhijit.lifeengine.core.life.Species
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnimalCoreTest {
    private fun read(name: String) = File("src/main/assets/animals/$name.glb").readBytes()

    @Test
    fun everySpeciesModelLoadsWithRequiredClips() {
        for (sp in Species.ALL) {
            val m = GlbLoader.load(read(sp.model), null)
            assertTrue(m.vertexCount > 1000)
            assertTrue("bone texture width", m.jointCount() in 1..64)
            for (c in listOf(sp.clipIdle, sp.clipWalk, sp.clipRun, sp.clipEat, sp.clipRest)) assertNotNull("${sp.id} $c", m.clip(c))
            assertTrue(m.indices.all { it in 0 until m.vertexCount })
            // weights normalised
            for (v in 0 until m.vertexCount) {
                val o = v * 18
                val s = m.vertices[o + 13] + m.vertices[o + 14] + m.vertices[o + 15] + m.vertices[o + 16]
                assertEquals(1f, s, 1e-3f)
            }
        }
    }

    @Test
    fun animationProducesFiniteMatricesAndMovesTheHead() {
        val m = GlbLoader.load(read("deer"), null)
        val an = Animator(m)
        val p = an.newPose()
        val out = FloatArray(m.jointCount() * 16)
        an.sample(m.clip("Idle"), 0.5f, true, p); an.jointMatrices(p, out, 0)
        assertTrue(out.all { it.isFinite() })
        val head = m.nodeNames.indexOf("Head")
        val g = FloatArray(16)
        an.nodeGlobal(head, g); val idleY = g[13]
        an.sample(m.clip("Eating"), 3f, true, p); an.jointMatrices(p, out, 0)
        an.nodeGlobal(head, g)
        assertTrue("head lowered while eating", g[13] < idleY - 0.5f)
    }

    @Test
    fun animalsStayOnLandAndBehave() {
        val w = WorldGen.generate(2026L, 1f)
        val poser = AnimalPoser { n -> read(n) }
        val sys = AnimalSystem(w, 2026L, poser.modelLengths(), 1f)
        assertTrue(sys.animals.size > 100)
        for (s in Species.ALL.indices) assertTrue(sys.count(s) > 0)
        val bones = FloatArray(poser.maxJoints * 16)
        var t = 0f
        while (t < 240f) { sys.update(0.1f, 10f); t += 0.1f }
        for (a in sys.animals) {
            assertTrue(a.x.isFinite() && a.z.isFinite() && a.yaw.isFinite())
            assertTrue("animal ${a.id} in water", a.y >= w.waterLevelAt(a.x, a.z) - 0.05f)
            poser.pose(a, bones, 0)
            assertTrue(bones.all { it.isFinite() })
        }
    }
}
