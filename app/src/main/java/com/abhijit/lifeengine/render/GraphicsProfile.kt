package com.abhijit.lifeengine.render

import android.app.ActivityManager
import android.content.Context

/** Graphics presets. Values mirror preview/render.py so desktop previews match the device. */
enum class Quality(
    val label: String,
    val renderScale: Float,
    val msaa: Int,
    val shadowSize: Int,           // 0 = shadows off
    val reflections: Boolean,
    val reflectionDivisor: Int,
    val fullResTerrain: Boolean,   // build/use the 4 m LOD0 terrain
    val terrainLod: FloatArray,    // distance thresholds for steps 1|2|4 -> else 8
    val objectLodDist: Float,
    val objectMaxDist: Float,
    val vegetationKeep: Float,     // fraction of placed vegetation drawn
    val grassNear: FloatArray,     // spacing, inner, outer
    val grassFar: FloatArray,
    val fpsCap: Int,
) {
    ULTRA("Ultra", 1.0f, 4, 2048, true, 2, true, floatArrayOf(300f, 750f, 1500f), 320f, 3600f, 1.0f,
        floatArrayOf(0.75f, 0f, 48f), floatArrayOf(1.9f, 44f, 150f), 60),
    HIGH("High", 0.85f, 4, 2048, true, 3, true, floatArrayOf(220f, 600f, 1300f), 260f, 3000f, 0.85f,
        floatArrayOf(0.85f, 0f, 38f), floatArrayOf(2.1f, 34f, 115f), 60),
    MEDIUM("Medium", 0.75f, 2, 1024, false, 4, true, floatArrayOf(140f, 450f, 1100f), 200f, 2400f, 0.65f,
        floatArrayOf(1.0f, 0f, 28f), floatArrayOf(2.4f, 25f, 80f), 45),
    BATTERY("Battery Saver", 0.6f, 0, 0, false, 4, false, floatArrayOf(0f, 350f, 900f), 150f, 1600f, 0.4f,
        floatArrayOf(1.25f, 0f, 18f), floatArrayOf(2.8f, 16f, 45f), 30);

    companion object {
        /** Initial guess before the GL renderer string is known (RAM / cores). */
        fun fromDevice(ctx: Context): Quality {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.isLowRamDevice) return BATTERY
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val gb = mi.totalMem / (1024.0 * 1024 * 1024)
            val cores = Runtime.getRuntime().availableProcessors()
            return when {
                gb >= 7.5 && cores >= 8 -> HIGH
                gb >= 3.5 -> MEDIUM
                else -> BATTERY
            }
        }

        /** Refine using the GPU name (called on the GL thread). */
        fun fromGpu(renderer: String, fallback: Quality): Quality {
            val r = renderer.lowercase()
            fun adrenoGen(): Int? = Regex("adreno[^0-9]*([0-9])([0-9]{2})").find(r)?.groupValues?.get(1)?.toIntOrNull()
            return when {
                "swiftshader" in r || "emulator" in r || "llvmpipe" in r || "android emulator" in r -> BATTERY
                "immortalis" in r -> ULTRA
                "adreno" in r -> when (adrenoGen() ?: 0) {
                    in 7..9 -> ULTRA
                    6 -> HIGH
                    else -> MEDIUM
                }
                "mali-g7" in r || "mali-g9" in r -> HIGH
                "mali-g6" in r || "mali-g5" in r -> MEDIUM
                "mali" in r || "powervr" in r -> if (fallback == BATTERY) BATTERY else MEDIUM
                "xclipse" in r -> HIGH
                else -> fallback
            }
        }
    }
}
