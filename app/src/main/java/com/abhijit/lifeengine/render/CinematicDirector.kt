package com.abhijit.lifeengine.render

import com.abhijit.lifeengine.core.World
import com.abhijit.lifeengine.sim.EngineState
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Scripted fly-throughs of the world's landmarks. Each shot is a Catmull-Rom spline for the eye and
 * one for the look-at point, with an optional time of day (only when cinematic effects are enabled).
 */
class CinematicDirector {
    private class Shot(val caption: String, val hour: Float, val duration: Float,
                       val eyes: List<FloatArray>, val looks: List<FloatArray>, val fov: Float = 50f)

    private var shots: List<Shot> = emptyList()
    private var index = 0
    private var t = 0f
    private var built: World? = null
    var fov = 50f
        private set

    fun start(state: EngineState) { index = 0; t = 0f; applyShot(state) }

    private fun applyShot(state: EngineState) {
        val s = shots.getOrNull(index) ?: return
        state.cinematicCaption = s.caption
        if (state.cinematicEffects && s.hour >= 0f) state.hourOverride = s.hour
    }

    private fun build(w: World) {
        built = w
        fun p(x: Float, yAboveGround: Float, z: Float) = floatArrayOf(x, max(w.heightAt(x, z), 0f) + yAboveGround, z)
        val r = w.riverPolyline
        val n = r.size / 2
        fun rv(i: Int) = floatArrayOf(r[2 * i.coerceIn(0, n - 1)], 0f, r[2 * i.coerceIn(0, n - 1) + 1])
        val list = ArrayList<Shot>()

        // 1. Sunrise over the grasslands: low glide eastwards across the plains
        val sx = -300f; val sz = 250f
        list += Shot("Sunrise over the grasslands", 6.4f, 22f,
            listOf(p(sx - 200, 6f, sz), p(sx - 60, 5f, sz + 20), p(sx + 80, 6f, sz + 10), p(sx + 220, 9f, sz - 20)),
            listOf(p(sx + 400, 20f, sz - 40), p(sx + 600, 30f, sz - 60), p(sx + 800, 40f, sz - 60), p(sx + 1000, 60f, sz - 80)), 52f)

        // 2. The highland falls: dolly up the river towards the waterfall
        val fx = w.fallX; val fz = w.fallBottomZ
        list += Shot("The highland falls", 8.8f, 24f,
            listOf(p(fx - 120, 25f, fz + 420), p(fx - 60, 20f, fz + 260), p(fx - 20, 18f, fz + 150), p(fx + 30, 30f, fz + 90)),
            listOf(floatArrayOf(fx, 35f, fz - 20), floatArrayOf(fx, 40f, fz - 20), floatArrayOf(fx, 42f, fz - 15), floatArrayOf(fx, 48f, fz - 10)), 48f)

        // 3. River of life: follow the river downstream at treetop height
        val a = n / 4; val b = n / 2
        list += Shot("River of life", 11.5f, 26f,
            listOf(rv(a).also { it[1] = 32f }, rv(a + (b - a) / 3).also { it[1] = 28f }, rv(a + 2 * (b - a) / 3).also { it[1] = 30f }, rv(b).also { it[1] = 36f }),
            listOf(rv(a + 12), rv(a + (b - a) / 3 + 12), rv(a + 2 * (b - a) / 3 + 12), rv(b + 14)), 55f)

        // 4. Kopje lookout: orbit around a granite outcrop
        if (w.kopjes.size >= 3) {
            val kx = w.kopjes[0]; val kz = w.kopjes[1]; val kr = w.kopjes[2] * 3.2f
            val eyes = (0..3).map { i -> val an = i * 0.7f; p(kx + cos(an) * kr, 40f, kz + sin(an) * kr) }
            list += Shot("Granite kopje", 15.2f, 22f, eyes, List(4) { p(kx, 12f, kz) }, 50f)
        }

        // 5. Waterhole at dusk: slow low orbit
        if (w.waterholes.size >= 3) {
            val hx = w.waterholes[0]; val hz = w.waterholes[1]; val hr = w.waterholes[2] * 3.5f
            val eyes = (0..3).map { i -> val an = 2f + i * 0.45f; p(hx + cos(an) * hr, 7f, hz + sin(an) * hr) }
            list += Shot("Waterhole at dusk", 17.7f, 22f, eyes, List(4) { floatArrayOf(hx, 1f, hz) }, 50f)
        }

        // 6. Lake at sunset: rising pull-back
        val lx = w.lakeX; val lz = w.lakeZ
        list += Shot("Lake at sunset", 18.15f, 26f,
            listOf(p(lx + 300, 12f, lz - 320), p(lx + 380, 60f, lz - 420), p(lx + 460, 140f, lz - 520), p(lx + 520, 240f, lz - 600)),
            listOf(floatArrayOf(lx - 200, 0f, lz + 100), floatArrayOf(lx - 150, 0f, lz + 60), floatArrayOf(lx - 120, 0f, lz + 40), floatArrayOf(lx - 100, 0f, lz)), 55f)

        // 7. Night falls: high slow pan under the stars
        list += Shot("Night over the savanna", 21.5f, 24f,
            listOf(p(-400f, 160f, 600f), p(-200f, 180f, 500f), p(0f, 200f, 400f), p(200f, 210f, 300f)),
            listOf(floatArrayOf(200f, 600f, -1500f), floatArrayOf(400f, 650f, -1500f), floatArrayOf(600f, 700f, -1400f), floatArrayOf(800f, 700f, -1300f)), 60f)
        shots = list
    }

    fun update(dt: Float, w: World, state: EngineState, eye: FloatArray, target: FloatArray) {
        if (built !== w) { build(w); start(state) }
        if (shots.isEmpty()) return
        t += dt
        var s = shots[index]
        if (t > s.duration) {
            t = 0f
            index = (index + 1) % shots.size
            applyShot(state)
            s = shots[index]
        }
        var u = (t / s.duration).coerceIn(0f, 1f)
        if (state.cinematicEffects) u = u * u * (3f - 2f * u) * 0.35f + u * 0.65f // gentle ease
        spline(s.eyes, u, eye)
        spline(s.looks, u, target)
        val g = max(w.heightAt(eye[0], eye[2]), w.waterLevelAt(eye[0], eye[2])) + 2f
        if (eye[1] < g) eye[1] = g
        fov = s.fov
    }

    /** Uniform Catmull-Rom through 4 control points (endpoints duplicated). */
    private fun spline(pts: List<FloatArray>, u: Float, out: FloatArray) {
        val segs = pts.size - 1
        val f = u * segs
        val i = f.toInt().coerceIn(0, segs - 1)
        val t = f - i
        val p0 = pts[(i - 1).coerceAtLeast(0)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[(i + 2).coerceAtMost(segs)]
        val t2 = t * t; val t3 = t2 * t
        for (c in 0..2) {
            out[c] = 0.5f * ((2f * p1[c]) + (-p0[c] + p2[c]) * t + (2f * p0[c] - 5f * p1[c] + 4f * p2[c] - p3[c]) * t2 +
                    (-p0[c] + 3f * p1[c] - 3f * p2[c] + p3[c]) * t3)
        }
    }
}
