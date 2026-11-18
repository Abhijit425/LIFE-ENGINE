package com.abhijit.lifeengine.render

import kotlin.math.sqrt

/** View-frustum planes extracted from a column-major view-projection matrix. */
class Frustum {
    val p = FloatArray(24)

    fun set(m: FloatArray) {
        // row i of the matrix = (m[i], m[4+i], m[8+i], m[12+i])
        fun row(i: Int, s: Float, out: Int, j: Int) {
            p[out] = m[3] + s * m[j]; p[out + 1] = m[7] + s * m[4 + j]; p[out + 2] = m[11] + s * m[8 + j]; p[out + 3] = m[15] + s * m[12 + j]
            val l = sqrt(p[out] * p[out] + p[out + 1] * p[out + 1] + p[out + 2] * p[out + 2])
            for (k in 0..3) p[out + k] /= l
        }
        row(0, 1f, 0, 0); row(0, -1f, 4, 0)
        row(0, 1f, 8, 1); row(0, -1f, 12, 1)
        row(0, 1f, 16, 2); row(0, -1f, 20, 2)
    }

    fun aabb(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Boolean {
        var i = 0
        while (i < 24) {
            val a = p[i]; val b = p[i + 1]; val c = p[i + 2]; val d = p[i + 3]
            val x = if (a > 0) x1 else x0
            val y = if (b > 0) y1 else y0
            val z = if (c > 0) z1 else z0
            if (a * x + b * y + c * z + d < 0) return false
            i += 4
        }
        return true
    }
}
