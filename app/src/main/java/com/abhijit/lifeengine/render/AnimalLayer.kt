package com.abhijit.lifeengine.render

import android.opengl.GLES30
import com.abhijit.lifeengine.core.gltf.SkinnedModel
import com.abhijit.lifeengine.core.life.AnimalPoser
import com.abhijit.lifeengine.core.life.AnimalSystem
import com.abhijit.lifeengine.core.life.Species
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * GPU side of the animals: one VAO per species, one RGBA32F bone texture holding every animal's joint
 * matrices (row = animal index), per-species instance buffers rebuilt each pass.
 * Poses are recomputed with distance-based rate (near every frame, far every few frames).
 */
class AnimalLayer(val system: AnimalSystem, private val poser: AnimalPoser) {
    private val count = system.animals.size
    private val joints = poser.maxJoints
    private val bones = FloatArray(max(1, count) * joints * 16)
    private val bonesFb: FloatBuffer = ByteBuffer.allocateDirect(bones.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val lastPosedFrame = IntArray(count) { -1000 }
    private var frame = 0
    private var boneTex = 0
    private val vaos = IntArray(Species.ALL.length)
    private val instBufs = IntArray(Species.ALL.length)
    private val indexCounts = IntArray(Species.ALL.length)
    private val inst = FloatArray(max(1, count) * 8)
    private val instFb: FloatBuffer = ByteBuffer.allocateDirect(inst.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    var trianglesDrawn = 0
        private set

    /** Create GL objects (GL thread). Returns all buffer/vao/texture ids so the owner can delete them. */
    fun upload(track: (kind: Int, id: Int) -> Unit) {
        for (s in Species.ALL.indices) {
            val m: SkinnedModel = poser.models[s]
            val vao = Gl.genVao().also { track(0, it) }
            GLES30.glBindVertexArray(vao)
            Gl.arrayBuffer(m.vertices).also { track(1, it) }
            Gl.attribs(0, SkinnedModel.STRIDE, 3, 3, 3, 4, 4, 1)
            Gl.indexBuffer(m.indices).also { track(1, it) }
            val ib = Gl.genBuffer().also { track(1, it) }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ib)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * 4 * 16, null, GLES30.GL_DYNAMIC_DRAW)
            Gl.attribs(6, 8, 4, 4, divisor = 1)
            GLES30.glBindVertexArray(0)
            vaos[s] = vao; instBufs[s] = ib; indexCounts[s] = m.indices.size
        }
        boneTex = Gl.genTexture().also { track(2, it) }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, boneTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, joints * 4, max(1, count), 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        for (k in 0 until count) poser.pose(system.animals[k], bones, k * joints * 16)
        uploadBones()
    }

    /** Re-pose animals near the camera every frame and distant ones less often, then upload. */
    fun animate(eye: FloatArray, f: Frustum, maxDist: Float) {
        frame++
        for (k in 0 until count) {
            val a = system.animals[k]
            val dx = a.x - eye[0]; val dz = a.z - eye[2]
            val d = sqrt(dx * dx + dz * dz)
            if (d > maxDist) continue
            val period = if (d < 120f) 1 else if (d < 300f) 2 else if (d < 600f) 4 else 8
            if (frame - lastPosedFrame[k] < period) continue
            val r = a.bodyLength + 2f
            if (!f.aabb(a.x - r, a.y - 1f, a.z - r, a.x + r, a.y + r, a.z + r) && d > 60f) continue
            poser.pose(a, bones, k * joints * 16)
            lastPosedFrame[k] = frame
        }
        uploadBones()
    }

    private fun uploadBones() {
        if (count == 0) return
        bonesFb.position(0); bonesFb.put(bones); bonesFb.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, boneTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, joints * 4, count, GLES30.GL_RGBA, GLES30.GL_FLOAT, bonesFb)
    }

    /** Draw every species that has instances within range of [eye] and inside [f]. */
    fun draw(p: ShaderProgram, eye: FloatArray, f: Frustum, maxDist: Float): Int {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE8)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, boneTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        p.i("uBones", 8)
        var draws = 0
        trianglesDrawn = 0
        for (s in Species.ALL.indices) {
            val sp = Species.ALL[s]
            var n = 0
            for (k in 0 until count) {
                val a = system.animals[k]
                if (a.species != s) continue
                val dx = a.x - eye[0]; val dz = a.z - eye[2]
                if (dx * dx + dz * dz > maxDist * maxDist) continue
                val r = a.bodyLength + 2f
                if (!f.aabb(a.x - r, a.y - 1f, a.z - r, a.x + r, a.y + r, a.z + r)) continue
                val o = n * 8
                inst[o] = a.x; inst[o + 1] = a.y; inst[o + 2] = a.z; inst[o + 3] = a.yaw
                inst[o + 4] = sp.scale; inst[o + 5] = k.toFloat(); inst[o + 6] = a.pitch; inst[o + 7] = (a.id * 0.618f) % 1f
                n++
            }
            if (n == 0) continue
            p.i("uPattern", sp.pattern)
            val pc = sp.patternColour
            if (pc != null) p.v3("uPatternColor", pc) else p.v3("uPatternColor", 0f, 0f, 0f)
            p.f("uPatternScale", 1f)
            GLES30.glBindVertexArray(vaos[s])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instBufs[s])
            instFb.position(0); instFb.put(inst, 0, n * 8); instFb.position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 8 * 4, instFb, GLES30.GL_STREAM_DRAW)
            GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, indexCounts[s], GLES30.GL_UNSIGNED_INT, 0, n)
            draws++
            trianglesDrawn += indexCounts[s] / 3 * n
        }
        GLES30.glBindVertexArray(0)
        return draws
    }

    /** Nearest animal to a world point (optionally of one species), or -1. */
    fun nearest(x: Float, z: Float, species: Int = -1, within: Float = Float.MAX_VALUE): Int {
        var best = -1; var bd = within * within
        for (k in 0 until count) {
            val a = system.animals[k]
            if (species >= 0 && a.species != species) continue
            val dx = a.x - x; val dz = a.z - z; val d = dx * dx + dz * dz
            if (d < bd) { bd = d; best = k }
        }
        return best
    }
}
