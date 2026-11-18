package com.abhijit.lifeengine.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.abhijit.lifeengine.BuildConfig
import com.abhijit.lifeengine.data.SaveManager
import com.abhijit.lifeengine.render.Quality
import com.abhijit.lifeengine.render.WorldView
import com.abhijit.lifeengine.sim.CameraMode
import com.abhijit.lifeengine.sim.EngineState
import com.abhijit.lifeengine.sim.TIME_SPEEDS
import com.abhijit.lifeengine.core.life.Species
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.random.Random

enum class Section(val label: String, val icon: ImageVector) {
    EXPLORER("World", Icons.Filled.Public),
    WILDLIFE("Wildlife", Icons.Filled.Pets),
    ECOSYSTEM("Ecosystem", Icons.Filled.Park),
    CLIMATE("Climate", Icons.Filled.WbSunny),
    EVOLUTION("Evolution", Icons.Filled.AccountTree),
    EXPERIMENTS("Lab", Icons.Filled.Science),
    ANALYTICS("Analytics", Icons.Filled.Insights),
    SETTINGS("Settings", Icons.Filled.Settings),
}

private class Telemetry {
    val sunElev = mutableStateListOf<Float>()
    val light = mutableStateListOf<Float>()
    val fps = mutableStateListOf<Float>()
    val cloud = mutableStateListOf<Float>()
    fun push(list: MutableList<Float>, v: Float) { list.add(v); if (list.size > 240) list.removeAt(0) }
}

@Composable
fun LifeEngineApp(state: EngineState, worldView: WorldView, saves: SaveManager) {
    LifeTheme {
        var section by remember { mutableStateOf(Section.EXPLORER) }
        var panelOpen by remember { mutableStateOf(false) }
        var tick by remember { mutableIntStateOf(0) }
        val telemetry = remember { Telemetry() }
        LaunchedEffect(Unit) {
            var n = 0
            while (true) {
                delay(250)
                tick++
                worldView.applyRenderScale(state.quality.renderScale)
                if (++n % 2 == 0 && !state.loading) {
                    telemetry.push(telemetry.sunElev, state.sunElevationDeg)
                    telemetry.push(telemetry.light, state.lightLevel)
                    telemetry.push(telemetry.fps, state.fps)
                    telemetry.push(telemetry.cloud, state.cloudCover * 100f)
                }
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { worldView }, modifier = Modifier.fillMaxSize())

            if (!state.loading || tick < 0) {
                Row(Modifier.fillMaxSize()) {
                    NavRail(section) { s ->
                        if (s == section) panelOpen = !panelOpen else { section = s; panelOpen = true }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        TopHud(state, tick, Modifier.align(Alignment.TopEnd).padding(16.dp))
                        CinematicCaption(state, tick, Modifier.align(Alignment.TopCenter).padding(top = 22.dp))
                        BottomBar(state, tick, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp, start = 12.dp, end = 12.dp))
                        SlidingPanel(panelOpen, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp, top = 70.dp, bottom = 110.dp)) {
                            GlassCard(Modifier.widthIn(max = 400.dp).fillMaxHeight()) {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    when (section) {
                                        Section.EXPLORER -> ExplorerPanel(state, tick)
                                        Section.WILDLIFE -> WildlifePanel(state, tick)
                                        Section.ECOSYSTEM -> EcosystemPanel(state, tick)
                                        Section.CLIMATE -> ClimatePanel(state)
                                        Section.EVOLUTION -> EvolutionPanel()
                                        Section.EXPERIMENTS -> ExperimentsPanel()
                                        Section.ANALYTICS -> AnalyticsPanel(state, telemetry, tick)
                                        Section.SETTINGS -> SettingsPanel(state, saves, worldView, tick)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.loading) LoadingOverlay(state, tick)
            state.errorText?.let { err ->
                GlassCard(Modifier.align(Alignment.Center).padding(32.dp)) {
                    Column {
                        Text("Something went wrong", color = Palette.Danger, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(err, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                        Chip("Dismiss", false) { state.errorText = null }
                    }
                }
            }
        }
    }
}

/** Declared outside any Row/Column so the scope-free AnimatedVisibility overload is used. */
@Composable
private fun SlidingPanel(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInHorizontally { it / 3 } + fadeIn(),
        exit = slideOutHorizontally { it / 3 } + fadeOut(),
    ) { content() }
}

// ------------------------------------------------------------------ chrome
@Composable
private fun NavRail(selected: Section, onSelect: (Section) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(92.dp).background(Brush.horizontalGradient(listOf(Color(0xE6080C0F), Color(0x99080C0F))))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("LIFE", color = Palette.Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
        Text("ENGINE", color = Palette.Text, fontSize = 10.sp, letterSpacing = 3.sp)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Section.entries.forEach { s ->
                val sel = s == selected
                Column(
                    Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (sel) Color(0x33F3A65A) else Color.Transparent)
                        .clickable { onSelect(s) }.padding(horizontal = 10.dp, vertical = 8.dp).width(64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(s.icon, contentDescription = s.label, tint = if (sel) Palette.Amber else Palette.Muted, modifier = Modifier.size(26.dp))
                    Text(s.label, color = if (sel) Palette.Text else Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

private fun clock(h: Float): String {
    val hh = h.toInt().coerceIn(0, 23); val mm = ((h - h.toInt()) * 60).toInt().coerceIn(0, 59)
    return String.format(Locale.US, "%02d:%02d", hh, mm)
}

@Composable
private fun TopHud(state: EngineState, tick: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.GlassLight).border(1.dp, Palette.Stroke, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Day ${state.day}  ${clock(state.hour)}", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(14.dp))
            Text(String.format(Locale.US, "%.0f°C  ☁ %d%%  ≋ %d%%", state.temperatureC, (state.cloudCover * 100).toInt(), (state.windStrength * 100).toInt()),
                color = Palette.Muted, fontSize = 13.sp)
        }
        if (state.showStats) {
            Text(
                String.format(Locale.US, "%.0f fps · %.1f ms · %s%s · %d draws · %.1fM tris", state.fps, state.frameMs, state.quality.label,
                    if (state.qualityAuto) " (auto)" else "", state.drawCalls, state.triangles / 1e6f),
                color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, end = 4.dp),
            )
            if (state.thermalThrottled) Text("Device is hot — quality reduced", color = Palette.Danger, fontSize = 11.sp)
        }
        if (state.cameraMode == CameraMode.FOLLOW && state.followInfo.isNotEmpty()) {
            Column(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Glass).border(1.dp, Palette.Stroke, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.End) {
                Text("Following", color = Palette.Amber, fontSize = 11.sp, letterSpacing = 1.sp)
                Text(state.followInfo, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (state.followNote.isNotEmpty()) Text("Stand-in model · ${state.followNote}", color = Palette.Muted, fontSize = 11.sp)
            }
        }
        if (state.timeSpeed > 100f) Text("Time-lapse: lighting held steady above 100×", color = Palette.Teal, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CinematicCaption(state: EngineState, tick: Int, modifier: Modifier) {
    val c = state.cinematicCaption
    if (state.cameraMode == CameraMode.CINEMATIC && c.isNotEmpty()) {
        Text(c.uppercase(), color = Color(0xEEFFFFFF), fontSize = 18.sp, letterSpacing = 6.sp, fontWeight = FontWeight.Light, modifier = modifier)
    }
}

@Composable
private fun BottomBar(state: EngineState, tick: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraMode.entries.forEach { m ->
                Chip(m.label, state.cameraMode == m, enabled = m.available) { state.cameraMode = m }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.clip(RoundedCornerShape(18.dp)).background(Palette.Glass).border(1.dp, Palette.Stroke, RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val paused = state.speedIndex == 0
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Palette.Amber).clickable { state.speedIndex = if (paused) 1 else 0 },
                contentAlignment = Alignment.Center,
            ) { Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, "Play/pause", tint = Color(0xFF1B1209)) }
            Spacer(Modifier.width(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 1 until TIME_SPEEDS.size) {
                    val v = TIME_SPEEDS[i]
                    Chip(if (v >= 1000f) "${(v / 1000).toInt()}k×" else "${v.toInt()}×", state.speedIndex == i) { state.speedIndex = i }
                }
            }
            Spacer(Modifier.width(12.dp))
            var dragging by remember { mutableStateOf(false) }
            var local by remember { mutableFloatStateOf(state.hour) }
            val shown = if (dragging) local else state.hour
            Text(clock(shown), color = Palette.Text, fontSize = 13.sp, modifier = Modifier.width(44.dp))
            Slider(
                value = shown, onValueChange = { dragging = true; local = it; state.hourOverride = it },
                onValueChangeFinished = { dragging = false }, valueRange = 0f..23.99f,
                modifier = Modifier.width(220.dp),
                colors = SliderDefaults.colors(thumbColor = Palette.Amber, activeTrackColor = Palette.Amber),
            )
        }
    }
}

@Composable
private fun LoadingOverlay(state: EngineState, tick: Int) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1A0F0A), Color(0xFF07090B)))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("LIFE ENGINE", color = Palette.Amber, fontSize = 44.sp, letterSpacing = 12.sp, fontWeight = FontWeight.Light)
            Text("Ecosystem simulator", color = Palette.Muted, fontSize = 15.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(36.dp))
            LinearProgressIndicator(
                progress = { state.loadingProgress }, modifier = Modifier.width(360.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = Palette.Amber, trackColor = Color(0x33FFFFFF),
            )
            Spacer(Modifier.height(12.dp))
            Text(state.loadingText, color = Palette.Text, fontSize = 14.sp)
            Text("World seed ${state.seed}", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ------------------------------------------------------------------ panels
@Composable
private fun ExplorerPanel(state: EngineState, tick: Int) {
    SectionTitle("World Explorer", "Savanna basin · 3.1 × 3.1 km")
    Text("Travel to", color = Palette.Muted, fontSize = 13.sp)
    FlowChips(listOf("overview" to "Overview", "waterfall" to "Highland falls", "river" to "River", "lake" to "Lake",
        "waterhole" to "Waterhole", "kopje" to "Kopje")) { state.focusRequest = it }
    Spacer(Modifier.height(12.dp))
    Text("Camera", color = Palette.Muted, fontSize = 13.sp)
    StatRow("Explore", "drag rotate · pinch zoom · 2-finger pan")
    StatRow("Free / Wildlife", "drag look · 2-finger move · pinch")
    StatRow("Any orbit mode", "double-tap ground to focus")
    Spacer(Modifier.height(10.dp))
    StatRow("Follow animal", "double-tap an animal, or Wildlife → Follow")
    PhaseNote("Phases 1–2: world, rendering, cameras, time of day, animated wildlife. Next: AI and ecology (Phase 3), climate engine (Phase 4), evolution (Phase 5) and the lab (Phase 6).")
}

@Composable
private fun FlowChips(items: List<Pair<String, String>>, onClick: (String) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { (k, l) -> Chip(l, false) { onClick(k) } } }
        }
    }
}

@Composable
private fun WildlifePanel(state: EngineState, tick: Int) {
    SectionTitle("Wildlife", "Live in this world")
    val counts = state.animalCounts
    if (counts == null) { Text("Animals are loading…", color = Palette.Muted); return }
    Text("${counts.sum()} animals · tap Follow, or double-tap an animal in the view", color = Palette.Muted, fontSize = 12.5.sp)
    Spacer(Modifier.height(6.dp))
    Species.ALL.forEachIndexed { i, sp ->
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp)).background(Palette.GlassLight).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sp.name, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Text(if (sp.diet == Species.CARNIVORE) "Carnivore" else if (sp.diet == Species.BROWSER) "Mixed feeder" else "Grazer",
                            color = if (sp.diet == Species.CARNIVORE) Palette.Amber else Palette.Teal, fontSize = 12.sp)
                    }
                    Text("${sp.latin} · ${counts.getOrElse(i) { 0 }} in world", color = Palette.Muted, fontSize = 12.sp)
                    if (sp.standIn != null) Text("Stand-in model: ${sp.standIn}", color = Palette.Muted, fontSize = 11.5.sp)
                }
                Chip("Follow", state.cameraMode == CameraMode.FOLLOW && state.followInfo.startsWith(sp.name)) {
                    state.followSpeciesRequest = sp.id
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Not in the world yet", color = Palette.Muted, fontSize = 13.sp)
    Species.MISSING.forEach { m ->
        Text("•  ${m[0]} (${m[1]})", color = Palette.Text, fontSize = 12.5.sp, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
    }
    PhaseNote("No verified CC0 animated model exists for these yet. They will be added when one is found, or from a licensed pack you provide.")
    PhaseNote("Phase 2: models, animation and ambient behaviour (grazing, walking, drinking at the shore, resting, galloping). Phase 3 adds hunting, fleeing, herding decisions, hunger, thirst and reproduction.")
    Text("Animal models: Ultimate Animated Animals by Quaternius (CC0 1.0). Coats are applied in the shader.",
        color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun EcosystemPanel(state: EngineState, tick: Int) {
    SectionTitle("Ecosystem", "What this world is made of")
    val s = state.worldStats
    if (s == null) { Text("Generating…", color = Palette.Muted); return }
    StatRow("Area", String.format(Locale.US, "%.1f km²", s.areaKm2))
    StatRow("Open water", String.format(Locale.US, "%.1f %%", s.waterPercent))
    StatRow("Grass cover (mean density)", String.format(Locale.US, "%.0f %%", s.grassPercent))
    StatRow("River length", String.format(Locale.US, "%.1f km", s.riverLengthKm))
    StatRow("Waterholes", "${s.waterholes}")
    StatRow("Granite kopjes", "${s.kopjes}")
    StatRow("Highest peak", String.format(Locale.US, "%.0f m", s.highestPeakM))
    Spacer(Modifier.height(8.dp))
    Text("Woody plants", color = Palette.Muted, fontSize = 13.sp)
    s.treeCounts.forEach { (k, v) -> StatRow(k, "%,d".format(v)) }
    Spacer(Modifier.height(8.dp))
    PhaseNote("Phase 3 turns this into a living system: grass biomass growth from rainfall, water availability, births, deaths, predation, migration and competition, with population-level models at high time speeds.")
}

@Composable
private fun ClimatePanel(state: EngineState) {
    SectionTitle("Climate", "Sky, wind and weather")
    var refresh by remember { mutableIntStateOf(0) }
    Text("Presets", color = Palette.Muted, fontSize = 13.sp)
    FlowChips(listOf("clear" to "Clear", "haze" to "Hazy", "overcast" to "Overcast", "storm" to "Storm", "dawn" to "Misty dawn", "night" to "Night")) { p ->
        when (p) {
            "clear" -> { state.cloudCover = 0.15f; state.windStrength = 0.25f; state.fogDensity = 0.7f; state.rain = 0f; state.humidity = 0.35f }
            "haze" -> { state.cloudCover = 0.3f; state.windStrength = 0.2f; state.fogDensity = 2.2f; state.rain = 0f; state.humidity = 0.6f }
            "overcast" -> { state.cloudCover = 0.85f; state.windStrength = 0.45f; state.fogDensity = 1.3f; state.rain = 0.1f; state.humidity = 0.75f }
            "storm" -> { state.cloudCover = 1f; state.windStrength = 0.95f; state.fogDensity = 1.8f; state.rain = 1f; state.humidity = 0.95f }
            "dawn" -> { state.hourOverride = 6.3f; state.fogDensity = 3.5f; state.cloudCover = 0.25f; state.windStrength = 0.1f; state.humidity = 0.8f }
            "night" -> { state.hourOverride = 22f; state.cloudCover = 0.15f }
        }
        refresh++
    }
    key(refresh) {
        LabeledSlider("Cloud cover", state.cloudCover, 0f..1f, { "${(it * 100).toInt()} %" }) { state.cloudCover = it }
        LabeledSlider("Wind strength", state.windStrength, 0f..1f, { "${(it * 100).toInt()} %" }) { state.windStrength = it }
        LabeledSlider("Wind direction", state.windDirDeg, 0f..359f, { "${it.toInt()}°" }) { state.windDirDeg = it }
        LabeledSlider("Haze / fog", state.fogDensity, 0.2f..5f, { String.format(Locale.US, "%.1f×", it) }) { state.fogDensity = it }
        LabeledSlider("Rain (ground wetness)", state.rain, 0f..1f, { "${(it * 100).toInt()} %" }) { state.rain = it }
        LabeledSlider("Humidity", state.humidity, 0f..1f, { "${(it * 100).toInt()} %" }) { state.humidity = it }
        LabeledSlider("Temperature", state.temperatureC, -5f..48f, { "${it.toInt()} °C" }) { state.temperatureC = it }
    }
    PhaseNote("These settings already drive sky scattering, clouds, cloud shadows, haze, wind in trees and grass, and wet surfaces. Phase 4 adds rain and lightning effects, seasons, drought and heatwaves, and makes plants and animals respond to them.")
}

@Composable
private fun key(k: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: (Float) -> String, onChange: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(value) }
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Palette.Text, fontSize = 13.sp)
            Text(fmt(v), color = Palette.Muted, fontSize = 13.sp)
        }
        Slider(value = v, onValueChange = { v = it; onChange(it) }, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Palette.Amber, activeTrackColor = Palette.Amber))
    }
}

@Composable
private fun EvolutionPanel() {
    SectionTitle("Evolution", "Phase 5")
    Text("Each animal will carry a genome of heritable traits:", color = Palette.Text, fontSize = 13.sp)
    listOf("Body size", "Speed", "Camouflage", "Fur thickness", "Temperature tolerance", "Feeding efficiency", "Reproductive success").forEach {
        Text("•  $it", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
    }
    Spacer(Modifier.height(8.dp))
    PhaseNote("Traits pass to offspring with mutation and drift; survival and mating success depend on the environment, so change comes from selection, not random re-skinning. Generation-by-generation trait charts will appear here.")
}

@Composable
private fun ExperimentsPanel() {
    SectionTitle("Experiments", "Phase 6")
    listOf(
        "Remove all predators and watch grazers overgraze",
        "Double rainfall for 50 years",
        "Introduce a drought every 7 years",
        "Raise temperature by 3 °C and track heat tolerance",
        "Reintroduce cheetahs after local extinction",
    ).forEach { Text("•  $it", color = Palette.Text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
    PhaseNote("The experiment lab will let you script interventions, run them at up to 10,000× using population-level models, and compare outcomes against a control world.")
}

@Composable
private fun AnalyticsPanel(state: EngineState, t: Telemetry, tick: Int) {
    SectionTitle("Analytics", "Live environment telemetry")
    ChartCard("Sun elevation (°)", t.sunElev, Palette.Amber, -90f, 90f)
    ChartCard("Light level", t.light, Color(0xFFFFE08A), 0f, null)
    ChartCard("Cloud cover (%)", t.cloud, Palette.Teal, 0f, 100f)
    ChartCard("Frame rate (fps)", t.fps, Color(0xFF9FD3FF), 0f, null)
    PhaseNote("Population, species diversity, predator/herbivore counts, plant biomass, water, births, deaths and extinction events are charted here once the ecology simulation arrives in Phase 3.")
}

@Composable
private fun ChartCard(title: String, data: List<Float>, color: Color, min: Float, max: Float?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Palette.GlassLight).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Palette.Text, fontSize = 13.sp)
            Text(data.lastOrNull()?.let { String.format(Locale.US, "%.1f", it) } ?: "–", color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Canvas(Modifier.fillMaxWidth().height(70.dp).padding(top = 6.dp)) {
            if (data.size < 2) return@Canvas
            val hi = max ?: (data.maxOrNull() ?: 1f).coerceAtLeast(min + 1e-3f)
            val lo = min
            val path = Path()
            data.forEachIndexed { i, v ->
                val x = i / 239f * size.width
                val y = size.height - ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawLine(Color(0x22FFFFFF), Offset(0f, size.height), Offset(size.width, size.height))
            drawPath(path, color, style = Stroke(width = 2.5f))
        }
    }
}

@Composable
private fun SettingsPanel(state: EngineState, saves: SaveManager, worldView: WorldView, tick: Int) {
    SectionTitle("Settings")
    Text("Graphics quality", color = Palette.Muted, fontSize = 13.sp)
    val detected = state.detectedQuality
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Auto" + (detected?.let { " (${it.label})" } ?: ""), state.qualityAuto) {
            state.qualityAuto = true; detected?.let { state.quality = it }; saves.saveQuality(null)
        }
        Quality.entries.forEach { q ->
            Chip(q.label, !state.qualityAuto && state.quality == q) { state.qualityAuto = false; state.quality = q; saves.saveQuality(q) }
        }
    }
    val q = state.quality
    StatRow("Render scale", "${(q.renderScale * 100).toInt()} %")
    StatRow("Shadows", if (q.shadowSize > 0) "${q.shadowSize}² PCF" else "Off")
    StatRow("Water reflections", if (q.reflections) "Planar, 1/${q.reflectionDivisor} res" else "Sky only")
    StatRow("Anti-aliasing", if (q.msaa > 0) "${q.msaa}× MSAA (applies after restart)" else "Off")
    StatRow("Vegetation", "${(q.vegetationKeep * 100).toInt()} %")
    StatRow("Frame cap", "${q.fpsCap} fps")
    Spacer(Modifier.height(10.dp))
    ToggleRow("Cinematic effects (timed lighting, easing)", state.cinematicEffects) { state.cinematicEffects = it; saves.saveCinematicEffects(it) }
    ToggleRow("Show performance stats", state.showStats) { state.showStats = it }

    Spacer(Modifier.height(14.dp))
    Text("World", color = Palette.Muted, fontSize = 13.sp)
    StatRow("Seed", "${state.seed}")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        Chip("New random world", false) { state.seed = Random.nextLong(1, 999_999); state.regenerateRequested = true; state.day = 1 }
        Chip("Default world", false) { state.seed = 2026L; state.regenerateRequested = true; state.day = 1 }
    }

    Spacer(Modifier.height(14.dp))
    Text("Saves (stored on this device)", color = Palette.Muted, fontSize = 13.sp)
    var refresh by remember { mutableIntStateOf(0) }
    val slots = remember(refresh) { saves.slots() }
    slots.forEach { s ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Palette.GlassLight).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(if (s.savedAt != null) "${SaveManager.format(s.savedAt)} · seed ${s.seed} · day ${s.day}" else "Empty",
                    color = Palette.Muted, fontSize = 11.5.sp)
            }
            if (s.slot != 0) Chip("Save", false) { saves.save(s.slot, state, worldView.camera.snapshot()); refresh++ }
            Spacer(Modifier.width(6.dp))
            Chip("Load", false, enabled = s.savedAt != null) { saves.load(s.slot, state) }
        }
    }

    Spacer(Modifier.height(14.dp))
    Text("About", color = Palette.Muted, fontSize = 13.sp)
    StatRow("Version", BuildConfig.VERSION_NAME)
    StatRow("Renderer", "OpenGL ES 3.0 (custom PBR engine)")
    StatRow("GPU", state.gpuName.take(32))
    StatRow("Offline", "No accounts, no API keys")
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(value) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = v, onCheckedChange = { v = it; onChange(it) },
            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF1B1209), checkedTrackColor = Palette.Amber))
    }
}
