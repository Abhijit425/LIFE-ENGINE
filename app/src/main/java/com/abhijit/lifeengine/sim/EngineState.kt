package com.abhijit.lifeengine.sim

import com.abhijit.lifeengine.render.Quality

enum class CameraMode(val label: String, val available: Boolean = true) {
    ORBIT("Explore"), FREE("Free"), WILDLIFE("Wildlife"), AERIAL("Aerial"), CINEMATIC("Cinematic"),
    FOLLOW("Follow animal")
}

/** Simulation speeds. 1x = one simulated minute per real second (a full day in 24 minutes). */
val TIME_SPEEDS = floatArrayOf(0f, 1f, 2f, 5f, 10f, 100f, 1000f, 10000f)

/**
 * State shared between the Compose UI thread and the GL thread. Plain @Volatile fields: the UI writes
 * settings, the renderer reads them every frame and writes back stats.
 */
class EngineState {
    // world
    @Volatile var seed: Long = 2026L
    @Volatile var regenerateRequested = false

    // time
    @Volatile var hour: Float = 7.0f          // 0..24
    @Volatile var day: Int = 1
    @Volatile var speedIndex: Int = 1          // index into TIME_SPEEDS
    @Volatile var hourOverride: Float = -1f    // UI slider sets this, renderer consumes

    // climate (visual in Phase 1; the climate engine in Phase 4 drives these)
    @Volatile var cloudCover = 0.35f
    @Volatile var windStrength = 0.35f
    @Volatile var windDirDeg = 35f
    @Volatile var fogDensity = 1.0f            // multiplier
    @Volatile var rain = 0f                    // wetness 0..1
    @Volatile var temperatureC = 27f
    @Volatile var humidity = 0.45f

    // rendering
    @Volatile var quality: Quality = Quality.HIGH
    @Volatile var qualityAuto = true
    @Volatile var detectedQuality: Quality? = null
    @Volatile var cinematicEffects = true
    @Volatile var showStats = true

    // camera
    @Volatile var cameraMode: CameraMode = CameraMode.ORBIT
    @Volatile var cameraSnapshot: FloatArray? = null   // restored from saves: tx, ty, tz, yaw, pitch, dist
    @Volatile var focusRequest: String? = null         // "waterfall", "lake", "river", "kopje", "waterhole"

    // wildlife
    @Volatile var followAnimalId: Int = -1
    @Volatile var followSpeciesRequest: String? = null
    @Volatile var followInfo: String = ""
    @Volatile var followNote: String = ""
    @Volatile var animalCounts: IntArray? = null

    // renderer -> UI
    @Volatile var loading = true
    @Volatile var loadingProgress = 0f
    @Volatile var loadingText = "Starting"
    @Volatile var fps = 0f
    @Volatile var frameMs = 0f
    @Volatile var drawCalls = 0
    @Volatile var triangles = 0
    @Volatile var gpuName = ""
    @Volatile var sunElevationDeg = 0f
    @Volatile var lightLevel = 0f
    @Volatile var cinematicCaption = ""
    @Volatile var errorText: String? = null
    @Volatile var worldStats: WorldStats? = null
    @Volatile var thermalThrottled = false

    val timeSpeed: Float get() = TIME_SPEEDS[speedIndex.coerceIn(0, TIME_SPEEDS.size - 1)]
}

data class WorldStats(
    val seed: Long,
    val areaKm2: Float,
    val waterPercent: Float,
    val grassPercent: Float,
    val treeCounts: Map<String, Int>,
    val waterholes: Int,
    val kopjes: Int,
    val riverLengthKm: Float,
    val highestPeakM: Float,
    val generationMs: Long,
)
