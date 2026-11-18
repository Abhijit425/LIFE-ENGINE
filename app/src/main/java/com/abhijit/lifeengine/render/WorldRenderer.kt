package com.abhijit.lifeengine.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.abhijit.lifeengine.core.Atmosphere
import com.abhijit.lifeengine.core.MeshData
import com.abhijit.lifeengine.core.ModelFactory
import com.abhijit.lifeengine.core.TerrainMesher
import com.abhijit.lifeengine.core.TextureGen
import com.abhijit.lifeengine.core.World
import com.abhijit.lifeengine.core.WorldGen
import com.abhijit.lifeengine.sim.EngineState
import com.abhijit.lifeengine.sim.WorldStats
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "LifeEngine"

/** CPU-side world package produced on a background thread, uploaded to the GPU on the GL thread. */
private class WorldPackage(
    val world: World,
    val terrain: Array<TerrainMesher.Lod?>,   // index by step: 1,2,4,8 -> 0..3
    val water: Array<FloatArray>,
    val fall: Array<FloatArray>,
    val ring: Array<FloatArray>,
    val models: Array<Array<MeshData>>,       // [type][lod]
    val grassNear: MeshData,
    val grassFar: MeshData,
    val noise: ByteArray,
    val waterNormal: ByteArray,
    val leaf: ByteArray,
    val fields: ByteArray,
    val heightHalf: ShortArray,
    val stats: WorldStats,
)

class WorldRenderer(private val context: Context, private val state: EngineState, val camera: CameraController) : GLSurfaceView.Renderer {
    private val assets = context.assets
    private val loader = Executors.newSingleThreadExecutor()
    private val atmoWorker = Executors.newSingleThreadExecutor()

    @Volatile private var pending: WorldPackage? = null
    private var lastPackage: WorldPackage? = null
    // GL objects owned by the current world (deleted on regeneration)
    private val ownedBuffers = ArrayList<Int>()
    private val ownedVaos = ArrayList<Int>()
    private val ownedTextures = ArrayList<Int>()
    @Volatile private var generating = false
    private var world: World? = null
    private var uploaded = false

    // programs
    private var pTerrain: ShaderProgram? = null
    private lateinit var pTerrainDepth: ShaderProgram
    private lateinit var pTerrainRefl: ShaderProgram
    private lateinit var pObj: ShaderProgram
    private lateinit var pObjDepth: ShaderProgram
    private lateinit var pObjRefl: ShaderProgram
    private lateinit var pGrass: ShaderProgram
    private lateinit var pSky: ShaderProgram
    private lateinit var pSkyRefl: ShaderProgram
    private lateinit var pWater: ShaderProgram
    private lateinit var pFall: ShaderProgram
    private var programsWithShadows = false

    // GPU resources
    private class TerrainGpu(val vao: Int, val step: Int, val offsets: IntArray, val counts: IntArray, val bounds: FloatArray)
    private class MeshGpu(val vao: Int, val indexCount: Int, val instBuf: Int, val radius: Float, val height: Float)
    private val terrainGpu = arrayOfNulls<TerrainGpu>(4)
    private var models: Array<Array<MeshGpu>> = emptyArray()
    private var grassNearGpu: MeshGpu? = null
    private var grassFarGpu: MeshGpu? = null
    private var waterVao = 0; private var waterCount = 0
    private var fallVao = 0; private var fallCount = 0
    private var ringVao = 0; private var ringCount = 0
    private var skyVao = 0
    private var texNoise = 0; private var texWater = 0; private var texLeaf = 0; private var texFields = 0; private var texHeight = 0
    private var texSky = 0
    private var shadowTex = 0; private var shadowFbo = 0; private var shadowSize = 0
    private var reflTex = 0; private var reflDepth = 0; private var reflFbo = 0; private var reflW = 0; private var reflH = 0
    private var anisotropy = 1f

    // instance buckets per type per chunk (filtered by quality)
    private var buckets: Array<Array<FloatArray>> = emptyArray()
    private var bucketQuality: Quality? = null
    private var scratch = FloatArray(6 * 4096)
    private var scratch2 = FloatArray(6 * 4096)

    // frame state
    private var width = 1; private var height = 1
    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16)
    private val invVp = FloatArray(16); private val lightVp = FloatArray(16)
    private val reflView = FloatArray(16); private val reflVp = FloatArray(16); private val invReflVp = FloatArray(16)
    private val mirror = FloatArray(16).also { Matrix.setIdentityM(it, 0); it[5] = -1f }
    private val frustum = Frustum()
    private val tmp = FloatArray(16); private val tmp2 = FloatArray(16)
    private var lastFrameNs = 0L
    private var time = 0f
    private var fpsAccum = 0f; private var fpsFrames = 0
    private var drawCalls = 0; private var tris = 0
    private var currentQuality: Quality? = null

    // environment
    private val sunDir = FloatArray(3); private val moonDir = FloatArray(3)
    private val sunColor = FloatArray(3); private val moonColor = FloatArray(3)
    private val ambSky = FloatArray(3); private val ambGround = FloatArray(3)
    private var exposure = 0.1f; private var targetExposure = 0.1f
    @Volatile private var atmoResult: Atmosphere? = null
    @Volatile private var atmoBusy = false
    private var atmoHour = -100f; private var atmoCloud = -1f
    private var cloudU = 0f; private var cloudV = 0f
    private var lightHour = 7f

    // ------------------------------------------------------------------ lifecycle
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // all GL objects are (re)created here — the EGL context may have been lost
        uploaded = false
        terrainGpu.fill(null)
        models = emptyArray()
        pTerrain = null
        texSky = 0; shadowTex = 0; shadowFbo = 0; shadowSize = 0; reflFbo = 0; reflW = 0
        val renderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "unknown"
        state.gpuName = renderer
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        if (ext.contains("GL_EXT_texture_filter_anisotropic")) {
            val a = FloatArray(1)
            GLES30.glGetFloatv(0x84FF /* MAX_TEXTURE_MAX_ANISOTROPY_EXT */, a, 0)
            anisotropy = min(8f, max(1f, a[0]))
        }
        if (state.detectedQuality == null) {
            val q = Quality.fromGpu(renderer, state.quality)
            state.detectedQuality = q
            if (state.qualityAuto) state.quality = q
        }
        ownedBuffers.clear(); ownedVaos.clear(); ownedTextures.clear() // old context objects are gone
        if (pending == null && lastPackage != null) pending = lastPackage
        if (world == null && pending == null) startGeneration(state.seed)
        GLES30.glClearColor(0.02f, 0.02f, 0.025f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(1, w); height = max(1, h)
        GLES30.glViewport(0, 0, width, height)
        reflW = 0 // force reflection target rebuild
    }

    fun release() {
        loader.shutdownNow()
        atmoWorker.shutdownNow()
    }

    // ------------------------------------------------------------------ world generation (background)
    private fun startGeneration(seed: Long) {
        if (generating) return
        generating = true
        state.loading = true
        state.loadingProgress = 0f
        val fullRes = state.quality.fullResTerrain
        loader.execute {
            try {
                val t0 = SystemClock.elapsedRealtime()
                state.loadingText = "Sculpting terrain, rivers and mountains"
                state.loadingProgress = 0.05f
                val w = WorldGen.generate(seed, 1f)
                state.loadingProgress = 0.45f
                state.loadingText = "Building terrain meshes"
                val lods = arrayOfNulls<TerrainMesher.Lod>(4)
                val steps = intArrayOf(1, 2, 4, 8)
                for (i in 0..3) {
                    if (steps[i] == 1 && !fullRes) continue
                    lods[i] = TerrainMesher.build(w, steps[i])
                    state.loadingProgress = 0.45f + 0.08f * (i + 1)
                }
                val water = TerrainMesher.waterMesh(w, 2)
                val fall = TerrainMesher.waterfallMesh(w)
                val ring = TerrainMesher.horizonRing(w)
                state.loadingText = "Growing acacias, figs, palms and baobabs"
                state.loadingProgress = 0.8f
                val models = Array(World.T_COUNT) { t ->
                    Array(2) { lod ->
                        when (t) {
                            World.T_ACACIA -> ModelFactory.acacia(lod, 100)
                            World.T_RIVERINE -> ModelFactory.riverine(lod, 200)
                            World.T_BAOBAB -> ModelFactory.baobab(lod, 300)
                            World.T_BUSH -> ModelFactory.bush(lod, 400)
                            World.T_PALM -> ModelFactory.palm(lod, 500)
                            else -> ModelFactory.rock(t - World.T_ROCK0, lod, 600)
                        }
                    }
                }
                state.loadingText = "Painting textures"
                state.loadingProgress = 0.88f
                val pkg = WorldPackage(
                    w, lods, water, fall, ring, models,
                    ModelFactory.grassTuft(7, 12), ModelFactory.grassTuftFar(8),
                    TextureGen.noiseRGBA(256, 11), TextureGen.waterNormal(256, 12), TextureGen.leafAtlas(512, 13),
                    TextureGen.worldFields(w), TextureGen.heightHalf(w),
                    computeStats(w, SystemClock.elapsedRealtime() - t0),
                )
                state.loadingText = "Uploading to GPU"
                state.loadingProgress = 0.95f
                pending = pkg
            } catch (t: Throwable) {
                Log.e(TAG, "world generation failed", t)
                state.errorText = "World generation failed: ${t.message}"
            } finally {
                generating = false
            }
        }
    }

    private fun computeStats(w: World, ms: Long): WorldStats {
        var water = 0; var grass = 0.0; var peak = -1e9f
        for (k in w.height.indices) {
            if (w.waterId[k] >= 0 && w.height[k] < w.waterLevels[w.waterId[k].toInt()]) water++
            grass += w.grass[k]
            peak = max(peak, w.height[k])
        }
        val n = w.height.size.toFloat()
        var len = 0f
        val r = w.riverPolyline
        var i = 0
        while (i + 3 < r.size) { len += hypot(r[i + 2] - r[i], r[i + 3] - r[i + 1]); i += 2 }
        val names = mapOf(World.T_ACACIA to "Umbrella acacias", World.T_RIVERINE to "Riverine fig & fever trees",
            World.T_PALM to "Doum palms", World.T_BAOBAB to "Baobabs", World.T_BUSH to "Shrubs")
        return WorldStats(
            seed = w.seed, areaKm2 = World.SIZE * World.SIZE / 1e6f, waterPercent = water / n * 100f,
            grassPercent = (grass / n * 100.0).toFloat(), treeCounts = names.entries.associate { it.value to w.instanceCount[it.key] },
            waterholes = w.waterholes.size / 3, kopjes = w.kopjes.size / 3, riverLengthKm = len / 1000f,
            highestPeakM = peak, generationMs = ms,
        )
    }

    // ------------------------------------------------------------------ GPU upload
    private fun buildPrograms(shadows: Boolean) {
        val sd = if (shadows) listOf("SHADOWS") else emptyList()
        pTerrain = ShaderProgram(assets, "terrain.vert", "terrain.frag", sd)
        pTerrainDepth = ShaderProgram(assets, "terrain.vert", "terrain.frag", listOf("DEPTH_ONLY"))
        pTerrainRefl = ShaderProgram(assets, "terrain.vert", "terrain.frag", listOf("REFLECTION"))
        pObj = ShaderProgram(assets, "object.vert", "object.frag", sd)
        pObjDepth = ShaderProgram(assets, "object.vert", "object.frag", listOf("DEPTH_ONLY"))
        pObjRefl = ShaderProgram(assets, "object.vert", "object.frag", listOf("REFLECTION"))
        pGrass = ShaderProgram(assets, "grass.vert", "grass.frag", sd)
        pSky = ShaderProgram(assets, "sky.vert", "sky.frag", emptyList())
        pSkyRefl = ShaderProgram(assets, "sky.vert", "sky.frag", listOf("REFLECTION"))
        pWater = ShaderProgram(assets, "water.vert", "water.frag", sd)
        pFall = ShaderProgram(assets, "fall.vert", "fall.frag", emptyList())
        for (p in listOf(pTerrain!!, pTerrainDepth, pTerrainRefl, pObj, pObjDepth, pObjRefl, pGrass, pSky, pSkyRefl, pWater, pFall)) {
            p.use()
            p.i("uSkyLut", 0); p.i("uNoise", 1); p.i("uShadowMap", 2); p.i("uWaterNormal", 3)
            p.i("uHeight", 4); p.i("uFields", 5); p.i("uLeafAtlas", 6); p.i("uReflection", 7)
        }
        programsWithShadows = shadows
        Gl.check("programs")
    }

    private fun newVao(): Int = Gl.genVao().also { ownedVaos += it }
    private fun abuf(d: FloatArray): Int = Gl.arrayBuffer(d).also { ownedBuffers += it }
    private fun ibuf(d: IntArray): Int = Gl.indexBuffer(d).also { ownedBuffers += it }
    private fun tex(t: Int): Int = t.also { ownedTextures += it }

    private fun upload(pkg: WorldPackage) {
        val t0 = SystemClock.elapsedRealtime()
        lastPackage = pkg
        // terrain
        for (i in 0..3) {
            val l = pkg.terrain[i] ?: continue
            val vao = newVao()
            GLES30.glBindVertexArray(vao)
            abuf(l.vertices)
            Gl.attribs(0, TerrainMesher.TSTRIDE, 3, 3, 4)
            ibuf(l.indices)
            GLES30.glBindVertexArray(0)
            terrainGpu[i] = TerrainGpu(vao, l.step, l.chunkIndexOffset, l.chunkIndexCount, l.chunkBounds)
        }
        // models (+ per model dynamic instance buffer)
        models = Array(pkg.models.size) { t -> Array(2) { lod -> meshGpu(pkg.models[t][lod], instanced = true) } }
        grassNearGpu = meshGpu(pkg.grassNear, instanced = false)
        grassFarGpu = meshGpu(pkg.grassFar, instanced = false)
        // water / waterfall / ring
        waterVao = newVao(); GLES30.glBindVertexArray(waterVao)
        abuf(pkg.water[0]); Gl.attribs(0, 4, 4)
        ibuf(IntArray(pkg.water[1].size) { pkg.water[1][it].toInt() }); waterCount = pkg.water[1].size
        fallVao = newVao(); GLES30.glBindVertexArray(fallVao)
        abuf(pkg.fall[0]); Gl.attribs(0, 5, 3, 2)
        ibuf(IntArray(pkg.fall[1].size) { pkg.fall[1][it].toInt() }); fallCount = pkg.fall[1].size
        ringVao = newVao(); GLES30.glBindVertexArray(ringVao)
        abuf(pkg.ring[0]); Gl.attribs(0, TerrainMesher.TSTRIDE, 3, 3, 4)
        ibuf(IntArray(pkg.ring[1].size) { pkg.ring[1][it].toInt() }); ringCount = pkg.ring[1].size
        skyVao = newVao()
        GLES30.glBindVertexArray(0)
        // textures
        texNoise = tex(Gl.texture2D(256, 256, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, Gl.bytes(pkg.noise), true, true, anisotropy))
        texWater = tex(Gl.texture2D(256, 256, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, Gl.bytes(pkg.waterNormal), true, true, anisotropy))
        texLeaf = tex(Gl.texture2D(512, 512, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, Gl.bytes(pkg.leaf), true, false, anisotropy))
        texFields = tex(Gl.texture2D(World.RES, World.RES, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, Gl.bytes(pkg.fields), false, false))
        texHeight = tex(Gl.texture2D(World.RES, World.RES, GLES30.GL_R16F, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, Gl.shorts(pkg.heightHalf), false, false))
        Gl.check("upload")
        world = pkg.world
        camera.attach(pkg.world)
        bucketQuality = null
        state.worldStats = pkg.stats
        uploaded = true
        state.loading = false
        state.loadingProgress = 1f
        Log.i(TAG, "upload took ${SystemClock.elapsedRealtime() - t0} ms")
    }

    private fun meshGpu(m: MeshData, instanced: Boolean): MeshGpu {
        val vao = newVao()
        GLES30.glBindVertexArray(vao)
        abuf(m.vertices())
        Gl.attribs(0, MeshData.STRIDE, 3, 3, 3, 4)
        ibuf(m.indices())
        var inst = 0
        if (instanced) {
            inst = Gl.genBuffer().also { ownedBuffers += it }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 6 * 4 * 64, null, GLES30.GL_DYNAMIC_DRAW)
            Gl.attribs(4, World.INST_STRIDE, 4, 2, divisor = 1)
        }
        GLES30.glBindVertexArray(0)
        return MeshGpu(vao, m.iCount, inst, m.radius, m.height)
    }

    private fun freeWorldGpu() {
        if (ownedBuffers.isNotEmpty()) GLES30.glDeleteBuffers(ownedBuffers.size, ownedBuffers.toIntArray(), 0)
        if (ownedVaos.isNotEmpty()) GLES30.glDeleteVertexArrays(ownedVaos.size, ownedVaos.toIntArray(), 0)
        if (ownedTextures.isNotEmpty()) GLES30.glDeleteTextures(ownedTextures.size, ownedTextures.toIntArray(), 0)
        ownedBuffers.clear(); ownedVaos.clear(); ownedTextures.clear()
        lastPackage = null
        terrainGpu.fill(null)
        models = emptyArray()
        uploaded = false
        world = null
    }

    /** Sort instances into terrain chunks, thinning by the quality's vegetation fraction. */
    private fun rebuildBuckets(w: World, q: Quality) {
        val n = TerrainMesher.CHUNKS
        val chunkSize = TerrainMesher.CHUNK_CELLS * World.CELL
        buckets = Array(World.T_COUNT) { t ->
            val lists = Array(n * n) { ArrayList<Int>() }
            val data = w.instances[t]
            val keepRocks = t >= World.T_ROCK0 && t <= World.T_ROCK3
            for (k in 0 until w.instanceCount[t]) {
                val keep = keepRocks || ((k * 2654435761L) and 0xFFFFL) / 65536f < q.vegetationKeep || t == World.T_BAOBAB
                if (!keep) continue
                val cx = ((data[k * 6] + World.HALF) / chunkSize).toInt().coerceIn(0, n - 1)
                val cz = ((data[k * 6 + 2] + World.HALF) / chunkSize).toInt().coerceIn(0, n - 1)
                lists[cz * n + cx].add(k)
            }
            Array(n * n) { c ->
                val l = lists[c]
                FloatArray(l.size * 6).also { out -> for ((j, k) in l.withIndex()) System.arraycopy(data, k * 6, out, j * 6, 6) }
            }
        }
        bucketQuality = q
    }

    // ------------------------------------------------------------------ frame
    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val realDt = if (lastFrameNs == 0L) 0.016f else ((now - lastFrameNs) / 1e9f).coerceAtLeast(0.0005f)
        val dt = realDt.coerceAtMost(0.1f)   // animation step; stats use the real frame time
        lastFrameNs = now
        time += dt

        if (state.regenerateRequested) {
            state.regenerateRequested = false
            freeWorldGpu()
            pending = null
            startGeneration(state.seed)
        }
        if (!uploaded) {
            pending?.let { p -> pending = null; try { upload(p) } catch (t: Throwable) { Log.e(TAG, "upload", t); state.errorText = "GPU upload failed: ${t.message}" } }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            return
        }
        val w = world ?: return
        val q = state.quality
        if (pTerrain == null || programsWithShadows != (q.shadowSize > 0)) {
            try { buildPrograms(q.shadowSize > 0) } catch (t: Throwable) {
                Log.e(TAG, "shader build", t); state.errorText = t.message; return
            }
        }
        if (bucketQuality != q) rebuildBuckets(w, q)
        currentQuality = q
        drawCalls = 0; tris = 0

        updateTime(dt)
        updateEnvironment(dt)
        camera.update(dt, invVp, width, height)

        // camera matrices
        val eye = camera.eye; val tgt = camera.target
        Matrix.perspectiveM(proj, 0, camera.fov, width.toFloat() / height, 1.0f, 24000f)
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], tgt[0], tgt[1], tgt[2], 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.invertM(invVp, 0, vp, 0)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)

        bindTextures()
        val lightUp = (sunColor[0] + sunColor[1] + moonColor[0] + moonColor[1]) > 0.004f
        if (q.shadowSize > 0 && lightUp) shadowPass(w, q) else Matrix.setIdentityM(lightVp, 0)
        val useRefl = q.reflections && eye[1] < 2200f
        if (useRefl) reflectionPass(w, q)

        // main pass
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        bindTextures()
        frustum.set(vp)
        drawTerrain(pTerrain!!, vp, eye, frustum, q, 1f, if (q.fullResTerrain) 1 else 2)
        drawRing(pTerrain!!, vp, eye)
        drawObjects(pObj, vp, eye, frustum, q.objectLodDist, q.objectMaxDist, false)
        drawGrass(eye, q)
        drawSky(pSky, vp, invVp, eye)
        // transparent
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        drawWater(eye, useRefl)
        pFall.use(); setCommon(pFall, vp, eye)
        GLES30.glBindVertexArray(fallVao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, fallCount, GLES30.GL_UNSIGNED_INT, 0); drawCalls++
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)

        // stats + frame cap
        fpsAccum += realDt; fpsFrames++
        if (fpsAccum >= 0.5f) {
            state.fps = fpsFrames / fpsAccum
            state.frameMs = fpsAccum / fpsFrames * 1000f
            fpsAccum = 0f; fpsFrames = 0
            state.drawCalls = drawCalls
            state.triangles = tris
        }
        if (q.fpsCap < 60) {
            val budgetNs = 1_000_000_000L / q.fpsCap
            val spent = System.nanoTime() - now
            if (spent < budgetNs) SystemClock.sleep((budgetNs - spent) / 1_000_000L)
        }
    }

    // ------------------------------------------------------------------ simulation clock + environment
    private fun updateTime(dt: Float) {
        val ov = state.hourOverride
        if (ov >= 0f) { state.hour = ov; state.hourOverride = -1f; lightHour = ov }
        val speed = state.timeSpeed
        var h = state.hour + dt * speed / 60f
        while (h >= 24f) { h -= 24f; state.day = state.day + 1 }
        state.hour = h
        // Above 100x the sun would strobe; hold the lighting at the last steady time instead (photosensitivity-safe).
        if (speed <= 100f) lightHour = h
    }

    private fun sunDirection(hour: Float, out: FloatArray) {
        val a = (hour - 6f) / 12f * PI.toFloat()
        val tilt = 20f * PI.toFloat() / 180f
        out[0] = cos(a); out[1] = sin(a) * cos(tilt); out[2] = sin(a) * sin(tilt)
    }

    private fun updateEnvironment(dt: Float) {
        sunDirection(lightHour, sunDir)
        moonDir[0] = -sunDir[0]; moonDir[1] = -sunDir[1] + 0.25f; moonDir[2] = -sunDir[2] + 0.1f
        val ml = sqrt(moonDir[0] * moonDir[0] + moonDir[1] * moonDir[1] + moonDir[2] * moonDir[2])
        for (i in 0..2) moonDir[i] /= ml
        state.sunElevationDeg = (Math.toDegrees(kotlin.math.asin(sunDir[1].toDouble()))).toFloat()

        atmoResult?.let { a ->
            atmoResult = null
            System.arraycopy(a.sunColor, 0, sunColor, 0, 3); System.arraycopy(a.moonColor, 0, moonColor, 0, 3)
            System.arraycopy(a.ambientSky, 0, ambSky, 0, 3); System.arraycopy(a.ambientGround, 0, ambGround, 0, 3)
            targetExposure = a.exposure
            uploadSkyLut(a.lut)
            state.lightLevel = 0.3f * sunColor[0] + 0.59f * sunColor[1] + 0.11f * sunColor[2] + 0.3f * ambSky[1]
        }
        exposure += (targetExposure - exposure) * (1f - exp(-dt * 2.5f))
        if (texSky == 0) exposure = targetExposure

        val cloud = state.cloudCover
        val needed = abs(lightHour - atmoHour) > 0.02f || abs(cloud - atmoCloud) > 0.01f || texSky == 0
        if (needed && !atmoBusy) {
            atmoBusy = true
            atmoHour = lightHour; atmoCloud = cloud
            val s = sunDir.copyOf(); val m = moonDir.copyOf()
            atmoWorker.execute {
                try {
                    val a = Atmosphere()
                    a.update(s, m, 1.4f + cloud * 1.2f + state.humidity * 0.6f, cloud * 0.6f + state.rain * 0.4f)
                    atmoResult = a
                } catch (t: Throwable) { Log.e(TAG, "atmosphere", t) } finally { atmoBusy = false }
            }
        }
        if (texSky == 0) {
            // first frame: compute synchronously so we never render without a sky
            val a = Atmosphere(); a.update(sunDir, moonDir, 1.6f, cloud * 0.6f); atmoResult = a
            updateEnvironment(0f)
        }
        val wind = state.windStrength
        val wd = Math.toRadians(state.windDirDeg.toDouble())
        val sp = (0.00015f + wind * 0.0005f) * dt * min(state.timeSpeed, 10f).coerceAtLeast(1f)
        cloudU += cos(wd).toFloat() * sp; cloudV += sin(wd).toFloat() * sp
    }

    private fun uploadSkyLut(lut: FloatArray) {
        val half = ShortArray(lut.size) { World.floatToHalf(lut[it]) }
        if (texSky == 0) {
            texSky = Gl.texture2D(Atmosphere.LUT_W, Atmosphere.LUT_H, GLES30.GL_RGB16F, GLES30.GL_RGB, GLES30.GL_HALF_FLOAT, Gl.shorts(half), false, true)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texSky)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, Atmosphere.LUT_W, Atmosphere.LUT_H, GLES30.GL_RGB, GLES30.GL_HALF_FLOAT, Gl.shorts(half))
        }
    }

    private fun bindTextures() {
        fun bind(unit: Int, tex: Int) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex) }
        bind(0, texSky); bind(1, texNoise); bind(2, shadowTex); bind(3, texWater)
        bind(4, texHeight); bind(5, texFields); bind(6, texLeaf); bind(7, reflTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun setCommon(p: ShaderProgram, m: FloatArray, eye: FloatArray) {
        p.m4("uViewProj", m)
        p.v3("uCamPos", eye)
        p.v3("uSunDir", sunDir); p.v3("uSunColor", sunColor)
        p.v3("uMoonDir", moonDir); p.v3("uMoonColor", moonColor)
        p.v3("uAmbSky", ambSky); p.v3("uAmbGround", ambGround)
        p.f("uTime", time)
        p.f("uExposure", exposure)
        val fog = 0.00024f * state.fogDensity * (1f + state.rain * 2.5f + state.humidity * 0.6f)
        p.v4("uFog", fog, 0.0020f, if (state.cinematicEffects) 1.0f else 0.6f, 0f)
        val wd = Math.toRadians(state.windDirDeg.toDouble())
        p.v4("uWind", cos(wd).toFloat(), sin(wd).toFloat(), state.windStrength, 0f)
        p.v4("uClouds", state.cloudCover, 1800f, cloudU, cloudV)
        p.f("uWetness", state.rain)
        p.m4("uLightVP", lightVp)
        p.f("uShadowTexel", if (shadowSize > 0) 1f / shadowSize else 0f)
        p.v2("uWorld", World.SIZE, World.HALF)
    }

    // ------------------------------------------------------------------ passes
    private fun ensureShadowTarget(size: Int) {
        if (shadowSize == size && shadowTex != 0) return
        if (shadowTex != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(shadowTex), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(shadowFbo), 0)
        }
        shadowTex = Gl.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, size, size, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        shadowFbo = Gl.genFbo()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, shadowTex, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        val st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (st != GLES30.GL_FRAMEBUFFER_COMPLETE) Log.e(TAG, "shadow FBO incomplete 0x${Integer.toHexString(st)}")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        shadowSize = size
    }

    private fun shadowPass(w: World, q: Quality) {
        ensureShadowTarget(q.shadowSize)
        val eye = camera.eye; val tgt = camera.target
        var fx = tgt[0] - eye[0]; var fy = tgt[1] - eye[1]; var fz = tgt[2] - eye[2]
        val dist = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-3f)
        fx /= dist; fy /= dist; fz /= dist
        val d = min(dist, 220f)
        val cx = eye[0] + fx * d; val cz = eye[2] + fz * d
        val cy = w.heightAt(cx, cz)
        val extent = (dist * 0.9f + 80f).coerceIn(140f, 520f)
        val l = if (sunColor[0] + sunColor[1] > 0.02f) sunDir else moonDir
        val up = if (abs(l[1]) > 0.99f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
        Matrix.setLookAtM(tmp, 0, cx + l[0] * 1500f, cy + l[1] * 1500f, cz + l[2] * 1500f, cx, cy, cz, up[0], up[1], up[2])
        // texel snapping against shimmering
        val texel = 2f * extent / shadowSize
        tmp[12] -= tmp[12] % texel; tmp[13] -= tmp[13] % texel
        Matrix.orthoM(tmp2, 0, -extent, extent, -extent, extent, 1f, 3200f)
        Matrix.multiplyMM(lightVp, 0, tmp2, 0, tmp, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glViewport(0, 0, shadowSize, shadowSize)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(2f, 4f)
        frustum.set(lightVp)
        val lightEye = floatArrayOf(cx, cy, cz)
        drawTerrain(pTerrainDepth, lightVp, lightEye, frustum, q, 1f, 2)
        drawObjects(pObjDepth, lightVp, lightEye, frustum, extent * 0.6f, extent * 1.6f, true)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
    }

    private fun ensureReflectionTarget(div: Int) {
        val rw = max(1, width / div); val rh = max(1, height / div)
        if (reflFbo != 0 && rw == reflW && rh == reflH) return
        if (reflFbo != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(reflTex), 0)
            GLES30.glDeleteRenderbuffers(1, intArrayOf(reflDepth), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(reflFbo), 0)
        }
        reflTex = Gl.texture2D(rw, rh, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null, false, false)
        reflDepth = Gl.genRbo()
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, reflDepth)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, rw, rh)
        reflFbo = Gl.genFbo()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, reflFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, reflTex, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, reflDepth)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        reflW = rw; reflH = rh
    }

    private fun reflectionPass(w: World, q: Quality) {
        ensureReflectionTarget(q.reflectionDivisor)
        val eye = camera.eye
        Matrix.multiplyMM(reflView, 0, view, 0, mirror, 0)
        Matrix.multiplyMM(reflVp, 0, proj, 0, reflView, 0)
        Matrix.invertM(invReflVp, 0, reflVp, 0)
        val reye = floatArrayOf(eye[0], -eye[1], eye[2])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE7); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, reflFbo)
        GLES30.glViewport(0, 0, reflW, reflH)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glClearColor(0.02f, 0.02f, 0.025f, 1f)
        frustum.set(reflVp)
        for (p in listOf(pTerrainRefl, pObjRefl, pSkyRefl)) { p.use(); p.f("uClipY", -0.3f) }
        drawTerrain(pTerrainRefl, reflVp, reye, frustum, q, 2f, 2)
        drawRing(pTerrainRefl, reflVp, reye)
        drawObjects(pObjRefl, reflVp, reye, frustum, 0f, 2000f, true)
        drawSky(pSkyRefl, reflVp, invReflVp, reye)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
    }

    private fun drawTerrain(p: ShaderProgram, m: FloatArray, eye: FloatArray, f: Frustum, q: Quality, lodBias: Float, minStep: Int) {
        p.use(); setCommon(p, m, eye)
        val th = q.terrainLod
        for (li in 0..3) {
            val t = terrainGpu[li] ?: continue
            GLES30.glBindVertexArray(t.vao)
            val b = t.bounds
            for (c in t.offsets.indices) {
                val o = c * 6
                // distance from eye to chunk box
                val dx = max(max(b[o] - eye[0], 0f), eye[0] - b[o + 3])
                val dy = max(max(b[o + 1] - eye[1], 0f), eye[1] - b[o + 4])
                val dz = max(max(b[o + 2] - eye[2], 0f), eye[2] - b[o + 5])
                val d = sqrt(dx * dx + dy * dy + dz * dz) * lodBias
                var want = if (d < th[0]) 1 else if (d < th[1]) 2 else if (d < th[2]) 4 else 8
                if (want < minStep) want = minStep
                if (want == 1 && terrainGpu[0] == null) want = 2
                if (want != t.step) continue
                if (!f.aabb(b[o], b[o + 1], b[o + 2], b[o + 3], b[o + 4], b[o + 5])) continue
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, t.counts[c], GLES30.GL_UNSIGNED_INT, t.offsets[c] * 4)
                drawCalls++; tris += t.counts[c] / 3
            }
        }
        GLES30.glBindVertexArray(0)
    }

    private fun drawRing(p: ShaderProgram, m: FloatArray, eye: FloatArray) {
        p.use(); setCommon(p, m, eye)
        GLES30.glBindVertexArray(ringVao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, ringCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
        drawCalls++; tris += ringCount / 3
    }

    private fun drawObjects(p: ShaderProgram, m: FloatArray, eye: FloatArray, f: Frustum, lodDist: Float, maxDist: Float, forceLod1: Boolean) {
        if (buckets.isEmpty()) return
        p.use(); setCommon(p, m, eye)
        val n = TerrainMesher.CHUNKS
        val cs = TerrainMesher.CHUNK_CELLS * World.CELL
        val t0 = terrainGpu.firstOrNull { it != null } ?: return
        for (type in 0 until World.T_COUNT) {
            val gpu = models.getOrNull(type) ?: continue
            var c0 = 0; var c1 = 0
            val small = type == World.T_BUSH || (type in World.T_ROCK0..World.T_ROCK3)
            val typeMax = if (small) maxDist * 0.45f else maxDist
            for (c in 0 until n * n) {
                val arr = buckets[type][c]
                if (arr.isEmpty()) continue
                val ox = -World.HALF + (c % n) * cs; val oz = -World.HALF + (c / n) * cs
                val b = t0.bounds
                val y0 = b[c * 6 + 1] - 2f; val y1 = b[c * 6 + 4] + 32f
                if (!f.aabb(ox - 20f, y0, oz - 20f, ox + cs + 20f, y1, oz + cs + 20f)) continue
                val dx = max(max(ox - eye[0], 0f), eye[0] - (ox + cs))
                val dz = max(max(oz - eye[2], 0f), eye[2] - (oz + cs))
                val d = hypot(dx, dz)
                if (d > typeMax) continue
                if (!forceLod1 && d < lodDist) {
                    if ((c0 + arr.size) > scratch.size) scratch = scratch.copyOf(max(scratch.size * 2, c0 + arr.size))
                    System.arraycopy(arr, 0, scratch, c0, arr.size); c0 += arr.size
                } else {
                    if ((c1 + arr.size) > scratch2.size) scratch2 = scratch2.copyOf(max(scratch2.size * 2, c1 + arr.size))
                    System.arraycopy(arr, 0, scratch2, c1, arr.size); c1 += arr.size
                }
            }
            if (c0 > 0) drawInstanced(gpu[0], scratch, c0)
            if (c1 > 0) drawInstanced(gpu[1], scratch2, c1)
        }
    }

    private fun drawInstanced(g: MeshGpu, data: FloatArray, floats: Int) {
        GLES30.glBindVertexArray(g.vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.instBuf)
        val fb = instanceBuffer(floats)
        fb.put(data, 0, floats); fb.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, floats * 4, fb, GLES30.GL_STREAM_DRAW)
        val count = floats / World.INST_STRIDE
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, g.indexCount, GLES30.GL_UNSIGNED_INT, 0, count)
        GLES30.glBindVertexArray(0)
        drawCalls++; tris += g.indexCount / 3 * count
    }

    private var instFb: java.nio.FloatBuffer? = null
    private fun instanceBuffer(floats: Int): java.nio.FloatBuffer {
        val cur = instFb
        if (cur != null && cur.capacity() >= floats) { cur.clear(); return cur }
        val nb = java.nio.ByteBuffer.allocateDirect(max(floats, 6 * 8192) * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        instFb = nb
        return nb
    }

    private fun drawGrass(eye: FloatArray, q: Quality) {
        val p = pGrass
        p.use(); setCommon(p, vp, eye)
        drawGrassLayer(p, grassNearGpu ?: return, q.grassNear, eye)
        drawGrassLayer(p, grassFarGpu ?: return, q.grassFar, eye)
    }

    private fun drawGrassLayer(p: ShaderProgram, g: MeshGpu, layer: FloatArray, eye: FloatArray) {
        val sp = layer[0]; val rIn = layer[1]; val rOut = layer[2]
        val block = 16
        val bs = block * sp
        p.f("uSpacing", sp); p.i("uBlock", block)
        p.v3("uFade", rIn * 0.85f, if (rIn > 0f) rIn else 0f, rOut)
        GLES30.glBindVertexArray(g.vao)
        val w = world ?: return
        val bx0 = floor((eye[0] - rOut) / bs).toInt(); val bx1 = floor((eye[0] + rOut) / bs).toInt()
        val bz0 = floor((eye[2] - rOut) / bs).toInt(); val bz1 = floor((eye[2] + rOut) / bs).toInt()
        // skip entirely when the camera is high above the ground
        if (eye[1] - w.heightAt(eye[0], eye[2]) > rOut) { GLES30.glBindVertexArray(0); return }
        for (bz in bz0..bz1) for (bx in bx0..bx1) {
            val x0 = bx * bs; val z0 = bz * bs
            val dx = max(max(x0 - eye[0], 0f), eye[0] - (x0 + bs)); val dz = max(max(z0 - eye[2], 0f), eye[2] - (z0 + bs))
            if (hypot(dx, dz) > rOut) continue
            val fx = max(abs(x0 - eye[0]), abs(x0 + bs - eye[0])); val fz = max(abs(z0 - eye[2]), abs(z0 + bs - eye[2]))
            if (rIn > 0f && hypot(fx, fz) < rIn * 0.85f) continue
            val cx = (x0 + bs / 2).coerceIn(-World.HALF, World.HALF); val cz = (z0 + bs / 2).coerceIn(-World.HALF, World.HALF)
            val cy = w.heightAt(cx, cz)
            if (!frustum.aabb(x0, cy - 30f, z0, x0 + bs, cy + 30f, z0 + bs)) continue
            p.v2("uBlockCell", (bx * block).toFloat(), (bz * block).toFloat())
            GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, g.indexCount, GLES30.GL_UNSIGNED_INT, 0, block * block)
            drawCalls++; tris += g.indexCount / 3 * block * block
        }
        GLES30.glBindVertexArray(0)
    }

    private fun drawSky(p: ShaderProgram, m: FloatArray, inv: FloatArray, eye: FloatArray) {
        p.use(); setCommon(p, m, eye)
        p.m4("uInvViewProj", inv)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(false)
        GLES30.glBindVertexArray(skyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        drawCalls++
    }

    private fun drawWater(eye: FloatArray, useRefl: Boolean) {
        val p = pWater
        val w = world ?: return
        p.use(); setCommon(p, vp, eye)
        p.f("uUseReflection", if (useRefl) 1f else 0f)
        p.v2("uViewport", width.toFloat(), height.toFloat())
        p.v3("uFallFoot", w.fallX, 0f, w.fallBottomZ)
        GLES30.glBindVertexArray(waterVao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, waterCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
        drawCalls++; tris += waterCount / 3
    }
}
