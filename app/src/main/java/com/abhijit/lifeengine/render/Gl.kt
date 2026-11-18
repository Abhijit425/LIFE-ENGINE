package com.abhijit.lifeengine.render

import android.content.res.AssetManager
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer

private const val TAG = "LifeEngineGL"

object Gl {
    fun floats(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    fun ints(a: IntArray): IntBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply { put(a); position(0) }

    fun shorts(a: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(a); position(0) }

    fun bytes(a: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(a.size).order(ByteOrder.nativeOrder()).apply { put(a); position(0) }

    fun genBuffer(): Int { val b = IntArray(1); GLES30.glGenBuffers(1, b, 0); return b[0] }
    fun genTexture(): Int { val b = IntArray(1); GLES30.glGenTextures(1, b, 0); return b[0] }
    fun genVao(): Int { val b = IntArray(1); GLES30.glGenVertexArrays(1, b, 0); return b[0] }
    fun genFbo(): Int { val b = IntArray(1); GLES30.glGenFramebuffers(1, b, 0); return b[0] }
    fun genRbo(): Int { val b = IntArray(1); GLES30.glGenRenderbuffers(1, b, 0); return b[0] }

    fun arrayBuffer(data: FloatArray, usage: Int = GLES30.GL_STATIC_DRAW): Int {
        val id = genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, floats(data), usage)
        return id
    }

    fun indexBuffer(data: IntArray): Int {
        val id = genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, id)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, data.size * 4, ints(data), GLES30.GL_STATIC_DRAW)
        return id
    }

    /** Float attribute pointer helper. sizes = component counts in order, stride in floats. */
    fun attribs(firstLocation: Int, strideFloats: Int, vararg sizes: Int, divisor: Int = 0, baseOffsetBytes: Int = 0) {
        var off = baseOffsetBytes
        var loc = firstLocation
        for (s in sizes) {
            GLES30.glEnableVertexAttribArray(loc)
            GLES30.glVertexAttribPointer(loc, s, GLES30.GL_FLOAT, false, strideFloats * 4, off)
            GLES30.glVertexAttribDivisor(loc, divisor)
            off += s * 4
            loc++
        }
    }

    fun texture2D(w: Int, h: Int, internal: Int, format: Int, type: Int, data: java.nio.Buffer?,
                  mipmaps: Boolean, repeat: Boolean, anisotropy: Float = 1f): Int {
        val id = genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, data)
        val wrap = if (repeat) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        if (mipmaps) {
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            if (anisotropy > 1f) GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, 0x84FE /* MAX_ANISOTROPY_EXT */, anisotropy)
        } else {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        }
        return id
    }

    fun check(where: String) {
        var e = GLES30.glGetError()
        while (e != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "GL error 0x${Integer.toHexString(e)} at $where")
            e = GLES30.glGetError()
        }
    }
}

/** GLSL ES 3.00 program built from asset files with `#include "common.glsl"` and #define injection. */
class ShaderProgram(assets: AssetManager, vert: String, frag: String, defines: List<String>) {
    val id: Int
    private val locations = HashMap<String, Int>()

    init {
        val common = read(assets, "shaders/common.glsl")
        val header = buildString {
            append("#version 300 es\nprecision highp float;\nprecision highp int;\n")
            append("precision highp sampler2D;\nprecision highp sampler2DShadow;\n")
            defines.forEach { append("#define ").append(it).append(" 1\n") }
        }
        fun src(name: String) = header + read(assets, "shaders/$name").replace("#include \"common.glsl\"", common)
        val vs = compile(GLES30.GL_VERTEX_SHADER, src(vert), vert)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, src(frag), frag)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            throw RuntimeException("Link failed $vert/$frag $defines: $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
    }

    private fun read(assets: AssetManager, path: String) = assets.open(path).bufferedReader().use { it.readText() }

    private fun compile(type: Int, src: String, name: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            throw RuntimeException("Compile failed $name: $log")
        }
        return s
    }

    fun use() = GLES30.glUseProgram(id)

    fun loc(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun f(name: String, v: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform1f(l, v) }
    fun i(name: String, v: Int) { val l = loc(name); if (l >= 0) GLES30.glUniform1i(l, v) }
    fun v2(name: String, x: Float, y: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform2f(l, x, y) }
    fun v3(name: String, v: FloatArray) { val l = loc(name); if (l >= 0) GLES30.glUniform3f(l, v[0], v[1], v[2]) }
    fun v3(name: String, x: Float, y: Float, z: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform3f(l, x, y, z) }
    fun v4(name: String, x: Float, y: Float, z: Float, w: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform4f(l, x, y, z, w) }
    fun m4(name: String, m: FloatArray) { val l = loc(name); if (l >= 0) GLES30.glUniformMatrix4fv(l, 1, false, m, 0) }
}
