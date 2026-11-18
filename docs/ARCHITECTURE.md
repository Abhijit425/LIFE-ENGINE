# Architecture

## Rendering technology decision
**Custom OpenGL ES 3.0 renderer (Kotlin + GLSL ES 3.00), one rendering architecture, no mixing.**

| Option | Verdict |
|---|---|
| Google Filament | Excellent PBR + glTF, but custom terrain/water/sky need its offline material compiler (`matc`) per material, which slows iteration on exactly the shaders that define this app's look. Reconsidered for Phase 2 glTF skinning (see below). |
| Vulkan | Best performance ceiling, but 3–4× the code and driver variance on mid-range tablets; not justified for Phase 1. |
| Unity | Strong tooling, but requires the Unity editor/licensing and produces a 60–100 MB APK; cannot be built headless in this project's CI without a Unity licence. |
| **OpenGL ES 3.0** | Supported by every target tablet (OnePlus Pad, Galaxy Tab S-series), small APK, full control over terrain/water/atmosphere, shaders testable on desktop via Mesa. **Chosen.** |

Phase 2 will add a glTF 2.0 loader with GPU skinning to the same GL ES pipeline (no second renderer).

## Code layout
```
app/src/main/java/com/abhijit/lifeengine/
  core/      Pure Java, no Android deps — world generator, meshes, textures, atmosphere (unit-tested on JVM)
    Noise, Rng, World, WorldGen, TerrainMesher, ModelFactory, TextureGen, Atmosphere, MeshData
  render/    GL ES 3.0 renderer — WorldRenderer (passes), CameraController, CinematicDirector,
             WorldView (GLSurfaceView + gestures), GraphicsProfile (presets), Gl/ShaderProgram, Frustum
  sim/       EngineState (UI <-> GL shared state), camera modes, time speeds
  ui/        Jetpack Compose tablet UI
  data/      SaveManager (JSON saves, preferences)
app/src/main/assets/shaders/   GLSL ES 3.00: common, terrain, object (instanced), grass, water, sky, fall
tools/, preview/               desktop exporters + headless renderer using the same shaders
```

## Frame
1. Clock: `hour += dt × speed / 60` (1× = one simulated minute per second). Above 100× the lighting time is frozen
   to avoid strobing; the day counter keeps advancing.
2. Atmosphere: when the sun moves > 0.02 h or clouds change, a worker thread integrates single scattering into a
   96×48 lat-long radiance table (RGB16F) and derives sun colour (transmittance), ambient and auto-exposure.
3. Camera update (touch input accumulated on the UI thread).
4. Shadow pass: 1 orthographic map fitted around the view target, texel-snapped; terrain (≥ 8 m LOD) + vegetation LOD1.
5. Reflection pass (Ultra/High): mirrored view about the water table, clip plane by `discard`, reduced resolution.
6. Main pass: terrain chunks (LOD by distance, frustum-culled) → horizon ring → instanced vegetation → grass blocks →
   sky (depth = far, early-z) → water (alpha) → waterfall (alpha).

## World model assumptions (Phase 1)
- Single-scattering atmosphere, Earth radii 6360/6420 km, Rayleigh β = (5.8, 13.5, 33.1)·10⁻⁶ m⁻¹, H_R = 8 km,
  Mie β = 21·10⁻⁶ m⁻¹ × turbidity, H_M = 1.2 km, g = 0.76. Sky radiance gain ×3 for display.
- Sun path: equatorial site (≈ Serengeti), solar declination fixed (tilt 20° south); sunrise 06:00, sunset 18:00.
- Water: one lowland water table at 0 m (river, lake, waterholes) and a highland pool; water colour from
  exponential absorption with depth.
- Moisture field = f(distance to river, lake, waterholes, altitude) + noise; drives grass greenness,
  riverine forest placement and the dry/green grass blend. It is a static proxy until the Phase 3/4
  hydrology and rainfall model replaces it.
- Vegetation placement uses moisture, slope, altitude and clustering noise.

## Population-level simulation (Phase 3 plan)
At ≤ 10× individuals are simulated as agents. Above that, species are aggregated into stage-structured population
models (Lotka–Volterra-type predator–prey with logistic grass growth driven by rainfall, Holling type II functional
responses), stepped per simulated day; agents are re-sampled from population state when the speed drops.
