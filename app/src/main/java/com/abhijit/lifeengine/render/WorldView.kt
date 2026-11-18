package com.abhijit.lifeengine.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.abhijit.lifeengine.sim.EngineState
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * Full-screen 3D viewport. Gestures:
 *  one finger drag = rotate / look, pinch = zoom (or move in free modes),
 *  two finger drag = pan (or move), double tap = focus on the tapped ground point.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class WorldView(context: Context, private val state: EngineState) : GLSurfaceView(context) {
    val camera = CameraController(state)
    val renderer = WorldRenderer(context, state, camera)
    private var appliedScale = -1f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { camera.onPinch(d.scaleFactor); return true }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val sx = e.x * renderScaleX(); val sy = e.y * renderScaleY()
            camera.onDoubleTap(sx, sy); return true
        }
    })
    private var lastX = 0f; private var lastY = 0f; private var lastCount = 0

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MsaaConfigChooser(state.quality.msaa))
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private fun renderScaleX(): Float = if (width > 0 && appliedW > 0) appliedW.toFloat() / width else 1f
    private fun renderScaleY(): Float = if (height > 0 && appliedH > 0) appliedH.toFloat() / height else 1f
    private var appliedW = 0; private var appliedH = 0

    /** Lower internal resolution via the hardware scaler (cheap, big GPU savings on high-DPI tablets). */
    fun applyRenderScale(scale: Float) {
        if (width == 0 || height == 0 || scale == appliedScale) return
        appliedScale = scale
        appliedW = (width * scale).toInt().coerceAtLeast(320)
        appliedH = (height * scale).toInt().coerceAtLeast(200)
        holder.setFixedSize(appliedW, appliedH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw != 0 && (w != oldw || h != oldh)) {
            appliedScale = -1f
            holder.setSizeFromLayout()
        }
        post { applyRenderScale(state.quality.renderScale) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)
        val count = e.pointerCount
        var cx = 0f; var cy = 0f
        for (i in 0 until count) { cx += e.getX(i); cy += e.getY(i) }
        cx /= count; cy /= count
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastX = cx; lastY = cy; lastCount = count
                camera.interacting = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (count != lastCount) { lastX = cx; lastY = cy; lastCount = count }
                val dx = cx - lastX; val dy = cy - lastY
                lastX = cx; lastY = cy
                if (count == 1) camera.onDrag(dx, dy)
                else if (!scaleDetector.isInProgress || count >= 2) camera.onPan(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> camera.interacting = false
        }
        return true
    }

    fun shutdown() = renderer.release()
}

/** Prefer RGB888 + 24-bit depth with the requested MSAA; fall back gracefully on drivers without it. */
class MsaaConfigChooser(private val samples: Int) : GLSurfaceView.EGLConfigChooser {
    private val EGL_OPENGL_ES3_BIT = 0x40

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val tries = mutableListOf<IntArray>()
        if (samples > 0) tries += attribs(24, samples)
        if (samples > 2) tries += attribs(24, 2)
        tries += attribs(24, 0)
        tries += attribs(16, 0)
        for (a in tries) {
            val num = IntArray(1)
            if (egl.eglChooseConfig(display, a, null, 0, num) && num[0] > 0) {
                val configs = arrayOfNulls<EGLConfig>(num[0])
                egl.eglChooseConfig(display, a, configs, num[0], num)
                configs.firstOrNull()?.let { return it }
            }
        }
        throw IllegalStateException("No suitable OpenGL ES 3 config")
    }

    private fun attribs(depth: Int, msaa: Int): IntArray {
        val base = mutableListOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        )
        if (msaa > 0) { base += listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, msaa) }
        base += EGL10.EGL_NONE
        return base.toIntArray()
    }
}
