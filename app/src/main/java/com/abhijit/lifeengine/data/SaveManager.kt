package com.abhijit.lifeengine.data

import android.content.Context
import com.abhijit.lifeengine.render.Quality
import com.abhijit.lifeengine.sim.CameraMode
import com.abhijit.lifeengine.sim.EngineState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SaveSlot(val slot: Int, val name: String, val savedAt: Long?, val seed: Long?, val day: Int?, val hour: Float?)

/** Local, offline world saves (JSON in app storage). Slot 0 is the autosave. */
class SaveManager(private val context: Context) {
    private val dir = File(context.filesDir, "saves").apply { mkdirs() }
    private fun file(slot: Int) = File(dir, "slot$slot.json")

    fun slots(): List<SaveSlot> = (0..3).map { s ->
        val f = file(s)
        val name = if (s == 0) "Autosave" else "Slot $s"
        if (!f.exists()) SaveSlot(s, name, null, null, null, null) else try {
            val j = JSONObject(f.readText())
            SaveSlot(s, name, j.optLong("savedAt"), j.optLong("seed"), j.optInt("day"), j.optDouble("hour").toFloat())
        } catch (e: Exception) { SaveSlot(s, name, null, null, null, null) }
    }

    fun save(slot: Int, st: EngineState, camera: FloatArray) {
        val j = JSONObject()
        j.put("version", 1)
        j.put("savedAt", System.currentTimeMillis())
        j.put("seed", st.seed)
        j.put("day", st.day)
        j.put("hour", st.hour.toDouble())
        j.put("speedIndex", st.speedIndex)
        j.put("climate", JSONObject().apply {
            put("cloudCover", st.cloudCover.toDouble()); put("windStrength", st.windStrength.toDouble())
            put("windDirDeg", st.windDirDeg.toDouble()); put("fogDensity", st.fogDensity.toDouble())
            put("rain", st.rain.toDouble()); put("temperatureC", st.temperatureC.toDouble()); put("humidity", st.humidity.toDouble())
        })
        j.put("cameraMode", st.cameraMode.name)
        j.put("camera", JSONArray().apply { camera.forEach { put(it.toDouble()) } })
        val tmp = File(dir, "slot$slot.tmp")
        tmp.writeText(j.toString(2))
        tmp.renameTo(file(slot))
    }

    /** Applies a save to the engine state. Returns false if the slot is empty or unreadable. */
    fun load(slot: Int, st: EngineState): Boolean {
        val f = file(slot)
        if (!f.exists()) return false
        return try {
            val j = JSONObject(f.readText())
            val seed = j.getLong("seed")
            if (seed != st.seed) { st.seed = seed; st.regenerateRequested = true }
            st.day = j.optInt("day", 1)
            st.hourOverride = j.optDouble("hour", 8.0).toFloat()
            st.speedIndex = j.optInt("speedIndex", 1)
            j.optJSONObject("climate")?.let { c ->
                st.cloudCover = c.optDouble("cloudCover", 0.35).toFloat()
                st.windStrength = c.optDouble("windStrength", 0.35).toFloat()
                st.windDirDeg = c.optDouble("windDirDeg", 35.0).toFloat()
                st.fogDensity = c.optDouble("fogDensity", 1.0).toFloat()
                st.rain = c.optDouble("rain", 0.0).toFloat()
                st.temperatureC = c.optDouble("temperatureC", 27.0).toFloat()
                st.humidity = c.optDouble("humidity", 0.45).toFloat()
            }
            st.cameraMode = runCatching { CameraMode.valueOf(j.optString("cameraMode", "ORBIT")) }.getOrDefault(CameraMode.ORBIT)
            if (!st.cameraMode.available) st.cameraMode = CameraMode.ORBIT
            j.optJSONArray("camera")?.let { a -> st.cameraSnapshot = FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            true
        } catch (e: Exception) { false }
    }

    fun delete(slot: Int) { file(slot).delete() }

    // ---- preferences
    private val prefs = context.getSharedPreferences("life_engine", Context.MODE_PRIVATE)

    fun savedQuality(): Quality? = prefs.getString("quality", null)?.let { n -> Quality.entries.firstOrNull { it.name == n } }
    fun saveQuality(q: Quality?) { prefs.edit().putString("quality", q?.name).apply() }
    fun cinematicEffects(): Boolean = prefs.getBoolean("cinematicFx", true)
    fun saveCinematicEffects(v: Boolean) { prefs.edit().putBoolean("cinematicFx", v).apply() }

    companion object {
        fun format(ts: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ts))
    }
}
