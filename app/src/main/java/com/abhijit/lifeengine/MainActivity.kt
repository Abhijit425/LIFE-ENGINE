package com.abhijit.lifeengine

import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.abhijit.lifeengine.data.SaveManager
import com.abhijit.lifeengine.render.Quality
import com.abhijit.lifeengine.render.WorldView
import com.abhijit.lifeengine.sim.EngineState
import com.abhijit.lifeengine.ui.LifeEngineApp

class MainActivity : ComponentActivity() {
    private val state = EngineState()
    private lateinit var saves: SaveManager
    private lateinit var worldView: WorldView
    private var thermalListener: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        saves = SaveManager(this)
        val manual = saves.savedQuality()
        if (manual != null) { state.quality = manual; state.qualityAuto = false } else state.quality = Quality.fromDevice(this)
        state.cinematicEffects = saves.cinematicEffects()
        // resume the autosave (same world seed, time and camera) if one exists
        saves.load(0, state)
        state.regenerateRequested = false
        // test/automation hook: `am start ... --es follow zebra` opens the follow camera on that species
        intent?.getStringExtra("follow")?.let { state.followSpeciesRequest = it }

        worldView = WorldView(this, state)
        setContent { LifeEngineApp(state, worldView, saves) }
        registerThermal()
    }

    private fun registerThermal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            val hot = status >= PowerManager.THERMAL_STATUS_SEVERE
            state.thermalThrottled = hot
            if (hot && state.qualityAuto) {
                state.quality = when (state.quality) {
                    Quality.ULTRA -> Quality.HIGH
                    Quality.HIGH -> Quality.MEDIUM
                    else -> Quality.BATTERY
                }
            }
        }
        pm.addThermalStatusListener(mainExecutor, l)
        thermalListener = l
    }

    override fun onResume() {
        super.onResume()
        worldView.onResume()
    }

    override fun onPause() {
        if (!state.loading) runCatching { saves.save(0, state, worldView.camera.snapshot()) }
        worldView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let {
                getSystemService(PowerManager::class.java)?.removeThermalStatusListener(it)
            }
        }
        worldView.shutdown()
        super.onDestroy()
    }
}
