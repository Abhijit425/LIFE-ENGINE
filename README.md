# LIFE ENGINE — 3D ecosystem simulator for Android tablets

An offline, scientifically grounded savanna ecosystem simulator with a custom OpenGL ES 3.0 PBR renderer.
No accounts, no API keys, no network access needed at runtime.

**Download:** the latest debug APK is attached to the [`debug-latest` release](../../releases/tag/debug-latest),
rebuilt and smoke-tested on a tablet emulator by GitHub Actions on every push to `main`.

## Status: Phases 1–2 of 7 complete

| Phase | Scope | Status |
|---|---|---|
| 1 | Android app, 3D savanna world, rendering, cameras, time of day, UI shell, saves | **Done** |
| 2 | CC0 rigged glTF animals, GPU skinning, animation, ambient behaviour, follow camera | **Done** |
| 3 | Animal AI + ecological simulation (populations, food web, biomass, water) | Next |
| 4 | Climate engine + weather effects (rain, lightning, seasons, drought) | Planned |
| 5 | Evolution (heritable traits, mutation, selection, drift) | Planned |
| 6 | Analytics dashboard + experiment lab | Planned |
| 7 | Performance pass + release APK | Planned |

### What Phase 1 contains
- **World**: procedural 3.1 × 3.1 km savanna basin (seeded, deterministic): ridged mountain ranges, a basalt
  plateau with a highland pool and waterfall, a meandering river, a lake with beaches, 5 waterholes with mud rims,
  granite kopjes, scree, a far-horizon mountain ring. ~10,000 placed plants and rocks: umbrella acacias,
  riverine fig/fever-tree galleries, doum palms, baobabs, shrubs, 4 rock types.
- **Rendering** (OpenGL ES 3.0): physically based single-scattering atmosphere (Rayleigh + Mie) driving sky,
  sun/moon light, ambient and fog; GGX specular; 3×3 PCF shadow maps; planar water reflections with Fresnel,
  normal-mapped waves, depth absorption, shoreline foam and caustics; volumetric-style height fog with sun
  in-scattering; procedural clouds with cloud shadows; stars and moon at night; wind-animated trees and grass;
  alpha-sharpened foliage cards; ACES tone mapping.
- **Performance**: chunked terrain with 4 LOD levels + skirts, frustum culling, GPU instancing for vegetation with
  2 LODs, GPU-placed two-layer grass in culled blocks, render scale via hardware scaler, 4 graphics presets with
  automatic selection from the GPU, thermal throttling response.
- **Cameras**: Explore (orbit), Free (drone), Wildlife (ground level), Aerial, Cinematic (7 scripted shots).
- **UI**: landscape-first dark tablet UI, 8 sections, live telemetry charts, climate controls, saves.
- **Time**: pause → 10,000×; lighting is held steady above 100× (no strobing day/night).

### What Phase 2 adds
- **glTF 2.0 / GLB loader** (pure Java): skins, node hierarchy, TRS animation clips; GPU skinning with joint matrices in a
  float texture, instanced per species, in the main, shadow and reflection passes; clip cross-fading; distance-based pose rate.
- **~170 animals in 8 species**: plains zebra, impala, Thomson's gazelle, African buffalo, spotted hyena, lion, leopard,
  cheetah — with walk, gallop, eat/drink, idle and rest animations, terrain following and body pitch on slopes.
- **Ambient behaviour**: herds and prides graze, wander, walk to the shoreline to drink, rest (predators by day, herbivores
  at night) and occasionally gallop. Hunting, fleeing and needs come with Phase 3.
- **Follow-animal camera**: double-tap an animal or use Wildlife → Follow.

**Models are CC0 only** (Quaternius, *Ultimate Animated Animals*; see `app/src/main/assets/animals/ATTRIBUTION.md`).
No CC0 animated lion, leopard, cheetah or hyena exists, so those use the closest rig (wolf/fox) with species coats and are
labelled as stand-ins in the app; zebra uses the horse rig with a zebra coat. **Elephant, giraffe, ostrich and crocodile are
not in the world yet** because no verified CC0 animated model was found.

## Build
See [docs/BUILD.md](docs/BUILD.md). Architecture and model assumptions: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Preview renders
Rendered headlessly from the same GLSL ES shaders and world generator that ship in the APK (Ultra preset):

| | |
|---|---|
| ![Highland falls](docs/img/preview_falls.jpg) | ![Lake at sunset](docs/img/preview_lake.jpg) |
| ![Morning grassland](docs/img/preview_plains.jpg) | ![Basalt plateau](docs/img/preview_plateau.jpg) |
