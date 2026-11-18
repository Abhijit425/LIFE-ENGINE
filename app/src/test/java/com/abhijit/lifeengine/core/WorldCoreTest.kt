package com.abhijit.lifeengine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class WorldCoreTest {
    companion object {
        lateinit var world: World

        @JvmStatic
        @BeforeClass
        fun setUp() { world = WorldGen.generate(2026L, 1f) }
    }

    @Test
    fun generationIsDeterministic() {
        val other = WorldGen.generate(2026L, 1f)
        var diff = 0
        for (i in world.height.indices step 997) if (world.height[i] != other.height[i]) diff++
        assertEquals(0, diff)
        assertEquals(world.instanceCount.toList(), other.instanceCount.toList())
    }

    @Test
    fun heightsAreFiniteAndPlausible() {
        var min = Float.MAX_VALUE; var max = -Float.MAX_VALUE
        for (h in world.height) { assertTrue(h.isFinite()); min = minOf(min, h); max = maxOf(max, h) }
        assertTrue("river/lake beds go below water", min < -1f)
        assertTrue("mountains exist", max > 150f && max < 900f)
    }

    @Test
    fun hasWaterRiverWaterfallAndVegetation() {
        assertTrue(world.waterId.count { it.toInt() == 0 } > 5000)
        assertTrue(world.waterId.count { it.toInt() == 1 } > 50)
        assertTrue(world.riverPolyline.size > 40)
        assertTrue(world.fallTopY > world.fallBottomY + 30f)
        for (t in 0 until World.T_COUNT) assertTrue("type $t placed", world.instanceCount[t] > 0)
        assertTrue(world.waterholes.size / 3 >= 3)
    }

    @Test
    fun terrainLodMeshesAreValid() {
        for (step in intArrayOf(2, 8)) {
            val lod = TerrainMesher.build(world, step)
            val verts = lod.vertices.size / TerrainMesher.TSTRIDE
            assertTrue(lod.indices.all { it in 0 until verts })
            for (c in lod.chunkIndexOffset.indices) assertTrue(lod.chunkIndexOffset[c] + lod.chunkIndexCount[c] <= lod.indices.size)
        }
    }

    @Test
    fun modelsAreValid() {
        val meshes = listOf(ModelFactory.acacia(0, 1), ModelFactory.acacia(1, 1), ModelFactory.riverine(0, 2), ModelFactory.baobab(0, 3),
            ModelFactory.palm(0, 4), ModelFactory.bush(0, 5), ModelFactory.rock(1, 0, 6), ModelFactory.grassTuft(7, 12))
        for (m in meshes) {
            assertTrue(m.iCount > 0 && m.iCount % 3 == 0)
            val idx = m.indices()
            assertTrue(idx.all { it in 0 until m.vCount })
            assertTrue(m.vertices().all { it.isFinite() })
        }
    }

    @Test
    fun halfFloatRoundTrip() {
        for (v in floatArrayOf(0f, 1f, -2.5f, 46f, 411.25f, -10f)) {
            val h = World.floatToHalf(v).toInt() and 0xFFFF
            val sign = if (h and 0x8000 != 0) -1f else 1f
            val exp = (h shr 10) and 0x1F
            val man = h and 0x3FF
            val back = sign * (1f + man / 1024f) * Math.pow(2.0, (exp - 15).toDouble()).toFloat()
            if (v != 0f) assertEquals(v, back, kotlin.math.abs(v) * 0.002f)
        }
    }

    @Test
    fun atmosphereDayBrighterThanNight() {
        val a = Atmosphere()
        a.update(floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f), 1.6f, 0f)
        val day = a.sunColor[1] + a.ambientSky[1]
        val b = Atmosphere()
        b.update(floatArrayOf(0f, -0.5f, 0.866f), floatArrayOf(0f, 0.5f, -0.866f), 1.6f, 0f)
        val night = b.sunColor[1] + b.ambientSky[1]
        assertTrue(day > night * 20f)
        assertTrue(a.sunColor[2] < a.sunColor[0] * 1.2f)
    }
}
