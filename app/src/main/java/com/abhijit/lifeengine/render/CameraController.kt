package com.abhijit.lifeengine.render

import com.abhijit.lifeengine.core.World
import com.abhijit.lifeengine.core.life.AnimalSystem
import com.abhijit.lifeengine.sim.CameraMode
import com.abhijit.lifeengine.sim.EngineState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Touch-driven camera with five modes. Input arrives on the UI thread and is accumulated under a lock;
 * update() runs on the GL thread once per frame and produces eye/target.
 */
class CameraController(private val state: EngineState) {
    // accumulated input (UI thread)
    private val lock = Any()
    private var dragX = 0f; private var dragY = 0f
    private var panX = 0f; private var panY = 0f
    private var pinch = 1f
    private var tapX = -1f; private var tapY = -1f
    @Volatile var interacting = false

    // orbit state (desired + smoothed)
    private var tx = 0f; private var ty = 0f; private var tz = 0f
    private var yaw = 1.1f; private var pitch = 0.42f; private var dist = 420f
    private var sTx = 0f; private var sTy = 0f; private var sTz = 0f
    private var sYaw = yaw; private var sPitch = pitch; private var sDist = dist

    // fly state (free / wildlife)
    private var fx = 0f; private var fy = 30f; private var fz = 0f
    private var fYaw = 0f; private var fPitch = -0.1f

    val eye = FloatArray(3)
    val target = FloatArray(3)
    var fov = 55f
        private set

    private var lastMode = CameraMode.ORBIT
    @Volatile var animals: AnimalSystem? = null
    var animalPicker: ((Float, Float) -> Int)? = null
    private var followDist = 14f
    private var lastUserInput = 0f
    private var clock = 0f
    private var world: World? = null
    val cinematic = CinematicDirector()

    fun attach(w: World) {
        world = w
        if (tx == 0f && tz == 0f) {
            // start looking at the river with the plateau falls in the background
            val r = w.riverPolyline
            val k = (r.size / 2 / 5) * 2
            tx = r[k]; tz = r[k + 1]
            yaw = atan2((tz - w.fallBottomZ), (tx - w.fallX)).toFloat()
            sTx = tx; sTz = tz; sYaw = yaw
            ty = w.heightAt(tx, tz); sTy = ty
        }
    }

    // ------------------------------------------------------------ input (UI thread)
    fun onDrag(dx: Float, dy: Float) = synchronized(lock) { dragX += dx; dragY += dy }
    fun onPan(dx: Float, dy: Float) = synchronized(lock) { panX += dx; panY += dy }
    fun onPinch(scale: Float) = synchronized(lock) { pinch *= scale }
    fun onDoubleTap(x: Float, y: Float) = synchronized(lock) { tapX = x; tapY = y }

    fun snapshot(): FloatArray = floatArrayOf(tx, ty, tz, yaw, pitch, dist)
    fun restore(s: FloatArray) { if (s.size >= 6) { tx = s[0]; ty = s[1]; tz = s[2]; yaw = s[3]; pitch = s[4]; dist = s[5] } }

    fun focus(x: Float, z: Float, d: Float, p: Float, yw: Float? = null) {
        tx = x; tz = z; dist = d; pitch = p
        if (yw != null) yaw = yw
        state.cameraMode = CameraMode.ORBIT
    }

    // ------------------------------------------------------------ per-frame update (GL thread)
    fun update(dt: Float, invViewProj: FloatArray?, viewW: Int, viewH: Int) {
        val w = world ?: return
        var dX = 0f; var dY = 0f; var pX = 0f; var pY = 0f; var pin = 1f; var tX = -1f; var tY = -1f
        synchronized(lock) {
            dX = dragX; dY = dragY; pX = panX; pY = panY; pin = pinch; tX = tapX; tY = tapY
            dragX = 0f; dragY = 0f; panX = 0f; panY = 0f; pinch = 1f; tapX = -1f
        }
        state.cameraSnapshot?.let { restore(it); state.cameraSnapshot = null }
        state.focusRequest?.let { handleFocus(it, w); state.focusRequest = null }

        clock += dt
        if (dX != 0f || dY != 0f || pX != 0f || pY != 0f || pin != 1f) lastUserInput = clock
        // double-tap on an animal (in orbit-style modes) starts following it
        if (tX >= 0 && invViewProj != null && (state.cameraMode == CameraMode.ORBIT || state.cameraMode == CameraMode.FOLLOW || state.cameraMode == CameraMode.AERIAL)) {
            val hit = pick(tX, tY, invViewProj, viewW, viewH, w)
            val id = if (hit != null) animalPicker?.invoke(hit[0], hit[2]) ?: -1 else -1
            if (id >= 0) { state.followAnimalId = id; state.cameraMode = CameraMode.FOLLOW; tX = -1f }
        }
        val mode = state.cameraMode
        if (mode != lastMode) onModeChange(lastMode, mode)
        lastMode = mode
        val k = 1f - exp(-dt * 9f)

        val followed = if (mode == CameraMode.FOLLOW) animals?.animals?.getOrNull(state.followAnimalId) else null
        when (mode) {
            CameraMode.FOLLOW -> if (followed != null) {
                yaw += dX * 0.0055f
                pitch = (pitch + dY * 0.004f).coerceIn(0.05f, 1.3f)
                followDist = (followDist / pin).coerceIn(followed.bodyLength * 1.5f, 160f)
                // trail behind the animal while it moves, unless the user is steering
                if (clock - lastUserInput > 3f && followed.speed > 0.3f) {
                    val behind = kotlin.math.atan2(-cos(followed.yaw), -sin(followed.yaw))
                    var diff = behind - yaw
                    while (diff > PI) diff -= (2 * PI).toFloat()
                    while (diff < -PI) diff += (2 * PI).toFloat()
                    yaw += diff * min(1f, dt * 0.8f)
                }
                tx = followed.x; tz = followed.z; ty = followed.y + followed.bodyLength * 0.3f
                dist = followDist
                val kf = 1f - exp(-dt * 6f)
                sTx += (tx - sTx) * kf; sTy += (ty - sTy) * kf; sTz += (tz - sTz) * kf
                sYaw += (yaw - sYaw) * kf; sPitch += (pitch - sPitch) * kf; sDist += (dist - sDist) * kf
                val cp = cos(sPitch)
                eye[0] = sTx + sDist * cp * cos(sYaw); eye[1] = sTy + sDist * sin(sPitch); eye[2] = sTz + sDist * cp * sin(sYaw)
                target[0] = sTx; target[1] = sTy; target[2] = sTz
                val ground = groundAt(w, eye[0], eye[2]) + 1.2f
                if (eye[1] < ground) eye[1] = ground
                fov = 50f
            } else {
                // nothing selected: follow the nearest animal to the current view target
                val k = animalPicker?.invoke(sTx, sTz) ?: -1
                if (k >= 0) state.followAnimalId = k else state.cameraMode = CameraMode.ORBIT
            }
            CameraMode.ORBIT, CameraMode.AERIAL -> {
                yaw += dX * 0.0055f
                pitch = (pitch + dY * 0.004f).coerceIn(if (mode == CameraMode.AERIAL) 0.5f else 0.04f, 1.45f)
                dist = (dist / pin).coerceIn(if (mode == CameraMode.AERIAL) 700f else 6f, 3200f)
                if (pX != 0f || pY != 0f) {
                    val s = sDist * 0.0016f
                    val cy = cos(sYaw); val sy = sin(sYaw)
                    // screen right = (-sin, cos), screen up/forward = (-cos, -sin)
                    // move opposite to the finger: -right * dx + forward * dy
                    tx += (-sy * pX - cy * pY) * s
                    tz += (cy * pX - sy * pY) * s
                }
                if (mode == CameraMode.AERIAL && !interacting) yaw += dt * 0.025f
                if (tX >= 0 && invViewProj != null) pick(tX, tY, invViewProj, viewW, viewH, w)?.let { tx = it[0]; tz = it[2] }
                tx = tx.coerceIn(-World.HALF + 20, World.HALF - 20)
                tz = tz.coerceIn(-World.HALF + 20, World.HALF - 20)
                ty = max(w.heightAt(tx, tz), w.waterLevelAt(tx, tz))
                sTx += (tx - sTx) * k; sTy += (ty - sTy) * k; sTz += (tz - sTz) * k
                sYaw += (yaw - sYaw) * k; sPitch += (pitch - sPitch) * k; sDist += (dist - sDist) * k
                val cp = cos(sPitch)
                eye[0] = sTx + sDist * cp * cos(sYaw)
                eye[1] = sTy + sDist * sin(sPitch)
                eye[2] = sTz + sDist * cp * sin(sYaw)
                target[0] = sTx; target[1] = sTy + min(sDist * 0.02f, 6f); target[2] = sTz
                val ground = groundAt(w, eye[0], eye[2]) + 2.5f
                if (eye[1] < ground) eye[1] = ground
                fov = 55f
            }
            CameraMode.FREE, CameraMode.WILDLIFE -> {
                val walk = mode == CameraMode.WILDLIFE
                fYaw += dX * 0.0035f
                fPitch = (fPitch - dY * 0.0035f).coerceIn(-1.35f, 1.2f)
                val speed = if (walk) 0.04f else 0.25f + max(0f, fy - groundAt(w, fx, fz)) * 0.004f
                val fwd = -pY * speed + (pin - 1f) * (if (walk) 60f else 260f)
                val strafe = pX * speed
                val cy = cos(fYaw); val sy = sin(fYaw)
                fx += cy * fwd - sy * strafe
                fz += sy * fwd + cy * strafe
                fx = fx.coerceIn(-World.HALF + 5, World.HALF - 5)
                fz = fz.coerceIn(-World.HALF + 5, World.HALF - 5)
                val g = groundAt(w, fx, fz)
                if (walk) fy += (g + 1.7f - fy) * k * 2f
                else fy = max(fy, g + 3f)
                if (!walk && tX >= 0) fy += 20f // double tap: climb
                eye[0] = fx; eye[1] = fy; eye[2] = fz
                val cpp = cos(fPitch)
                target[0] = fx + cy * cpp * 10f; target[1] = fy + sin(fPitch) * 10f; target[2] = fz + sy * cpp * 10f
                fov = if (walk) 50f else 60f
            }
            CameraMode.CINEMATIC -> {
                cinematic.update(dt, w, state, eye, target)
                fov = cinematic.fov
            }
        }
    }

    private fun groundAt(w: World, x: Float, z: Float): Float = max(w.heightAt(x, z), w.waterLevelAt(x, z))

    private fun onModeChange(from: CameraMode, to: CameraMode) {
        when (to) {
            CameraMode.FREE, CameraMode.WILDLIFE -> {
                fx = eye[0]; fz = eye[2]; fy = eye[1]
                fYaw = atan2(target[2] - eye[2], target[0] - eye[0])
                fPitch = if (to == CameraMode.WILDLIFE) 0f else -0.25f
                if (to == CameraMode.WILDLIFE) {
                    // drop to the ground at the orbit target, looking along the current view direction
                    fx = sTx - cos(fYaw) * 25f; fz = sTz - sin(fYaw) * 25f
                    world?.let { fy = groundAt(it, fx, fz) + 1.7f }
                }
            }
            CameraMode.AERIAL -> { dist = max(dist, 1100f); pitch = max(pitch, 0.75f) }
            CameraMode.ORBIT -> if (from == CameraMode.AERIAL) { dist = min(dist, 450f); pitch = min(pitch, 0.5f) } else if (from == CameraMode.FOLLOW) { dist = max(sDist, 60f) }
            CameraMode.FOLLOW -> { followDist = 14f; pitch = 0.3f }
            CameraMode.CINEMATIC -> cinematic.start(state)
            else -> {}
        }
        if (from == CameraMode.CINEMATIC) {
            // continue orbiting from where the cinematic camera was
            tx = target[0]; tz = target[2]
            val dx = eye[0] - target[0]; val dz = eye[2] - target[2]; val dy = eye[1] - target[1]
            dist = sqrt(dx * dx + dy * dy + dz * dz).coerceIn(20f, 3000f)
            yaw = atan2(dz, dx); pitch = atan2(dy, sqrt(dx * dx + dz * dz)).coerceIn(0.05f, 1.4f)
            sTx = tx; sTz = tz; sYaw = yaw; sPitch = pitch; sDist = dist
            state.cinematicCaption = ""
        }
    }

    private fun handleFocus(what: String, w: World) {
        when (what) {
            "waterfall" -> focus(w.fallX, w.fallBottomZ + 40f, 260f, 0.22f, (PI / 2).toFloat())
            "lake" -> focus(w.lakeX, w.lakeZ, 900f, 0.45f)
            "river" -> { val r = w.riverPolyline; val k = (r.size / 4) * 2; focus(r[k], r[k + 1], 220f, 0.3f) }
            "waterhole" -> if (w.waterholes.size >= 3) focus(w.waterholes[0], w.waterholes[1], 160f, 0.35f)
            "kopje" -> if (w.kopjes.size >= 3) focus(w.kopjes[0], w.kopjes[1], 200f, 0.3f)
            "overview" -> focus(0f, 0f, 2600f, 0.95f)
        }
    }

    /** Ray-march the heightfield from a screen point. */
    private fun pick(sx: Float, sy: Float, inv: FloatArray, vw: Int, vh: Int, w: World): FloatArray? {
        val nx = sx / vw * 2f - 1f
        val ny = 1f - sy / vh * 2f
        val near = unproject(inv, nx, ny, -1f)
        val far = unproject(inv, nx, ny, 1f)
        var dx = far[0] - near[0]; var dy = far[1] - near[1]; var dz = far[2] - near[2]
        val l = sqrt(dx * dx + dy * dy + dz * dz); dx /= l; dy /= l; dz /= l
        var t = 0f
        var prevAbove = true
        while (t < 6000f) {
            val x = near[0] + dx * t; val y = near[1] + dy * t; val z = near[2] + dz * t
            if (abs(x) > World.HALF || abs(z) > World.HALF) { t += 8f; continue }
            val above = y > groundAt(w, x, z)
            if (!above && prevAbove) return floatArrayOf(x, y, z)
            prevAbove = above
            t += if (t < 300f) 1.5f else 6f
        }
        return null
    }

    private fun unproject(inv: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val v = floatArrayOf(x, y, z, 1f)
        val o = FloatArray(4)
        android.opengl.Matrix.multiplyMV(o, 0, inv, 0, v, 0)
        return floatArrayOf(o[0] / o[3], o[1] / o[3], o[2] / o[3])
    }
}
