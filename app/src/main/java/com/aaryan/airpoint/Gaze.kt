package com.aaryan.airpoint

import android.content.SharedPreferences
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Experimental gaze estimate, WebGazer style: where the iris sits inside the eye, plus how your head is turned and where it is in the frame,
 * mapped to screen coordinates by a ridge regression that you train in a short calibration.
 */
object Gaze {
    class Model(val mean: FloatArray, val std: FloatArray, val w: Array<FloatArray>)   // w[0] = x weights, w[1] = y weights, last entry is the bias

    /** [iris x in eye, iris y in eye, head yaw, head pitch, face x, face y], or null if the iris landmarks aren't there. */
    fun raw(lm: List<NormalizedLandmark>): FloatArray? {
        if (lm.size < 478) return null
        fun eye(c1: Int, c2: Int, iris: Int, up: Int, lo: Int): FloatArray? {
            val x1 = minOf(lm[c1].x(), lm[c2].x()); val w = maxOf(lm[c1].x(), lm[c2].x()) - x1
            if (w < 1e-4f) return null
            return floatArrayOf((lm[iris].x() - x1) / w, (lm[iris].y() - (lm[up].y() + lm[lo].y()) / 2f) / w)
        }
        val r = eye(33, 133, 468, 159, 145) ?: return null
        val l = eye(362, 263, 473, 386, 374) ?: return null
        val fw = maxOf(abs(lm[454].x() - lm[234].x()), 1e-4f); val fh = maxOf(abs(lm[152].y() - lm[10].y()), 1e-4f)
        return floatArrayOf((r[0] + l[0]) / 2f, (r[1] + l[1]) / 2f,
            (lm[1].x() - (lm[234].x() + lm[454].x()) / 2f) / fw, (lm[1].y() - (lm[10].y() + lm[152].y()) / 2f) / fh, lm[1].x(), lm[1].y())
    }

    fun expand(f: FloatArray) = floatArrayOf(f[0], f[1], f[2], f[3], f[4], f[5], f[0] * f[0], f[1] * f[1], f[0] * f[1])

    /** Returns the model and its typical error in pixels, or null if there wasn't enough good data. */
    fun fit(samples: List<Pair<FloatArray, FloatArray>>): Pair<Model, Float>? {
        val n = samples.size; if (n < 30) return null
        val d = samples[0].first.size; val m = d + 1
        val mean = FloatArray(d); val std = FloatArray(d)
        for (s in samples) for (j in 0 until d) mean[j] += s.first[j] / n
        for (s in samples) for (j in 0 until d) { val v = s.first[j] - mean[j]; std[j] += v * v / n }
        for (j in 0 until d) std[j] = maxOf(sqrt(std[j]), 1e-4f)
        val a = Array(m) { DoubleArray(m) }; val by = Array(m) { DoubleArray(2) }
        for (s in samples) {
            val x = DoubleArray(m) { if (it < d) ((s.first[it] - mean[it]) / std[it]).toDouble() else 1.0 }
            for (i in 0 until m) { for (j in 0 until m) a[i][j] += x[i] * x[j]; by[i][0] += x[i] * s.second[0]; by[i][1] += x[i] * s.second[1] }
        }
        for (i in 0 until d) a[i][i] += 2.0      // ridge: keeps the fit from chasing noise (the bias is not penalised)
        val aug = Array(m) { i -> DoubleArray(m + 2) { j -> if (j < m) a[i][j] else by[i][j - m] } }
        for (c in 0 until m) {
            var p = c; for (r in c + 1 until m) if (abs(aug[r][c]) > abs(aug[p][c])) p = r
            val t = aug[c]; aug[c] = aug[p]; aug[p] = t
            val pv = aug[c][c]; if (abs(pv) < 1e-9) return null
            for (j in c until m + 2) aug[c][j] /= pv
            for (r in 0 until m) if (r != c) { val f = aug[r][c]; if (f != 0.0) for (j in c until m + 2) aug[r][j] -= f * aug[c][j] }
        }
        val model = Model(mean, std, Array(2) { k -> FloatArray(m) { i -> aug[i][m + k].toFloat() } })
        var se = 0.0
        for (s in samples) { val p = predict(model, s.first); val ex = p[0] - s.second[0]; val ey = p[1] - s.second[1]; se += ex * ex + ey * ey }
        return model to sqrt(se / n).toFloat()
    }

    fun predict(m: Model, f: FloatArray): FloatArray {
        val d = m.mean.size; var x = 0f; var y = 0f
        for (i in 0 until d) { val v = (f[i] - m.mean[i]) / m.std[i]; x += m.w[0][i] * v; y += m.w[1][i] * v }
        return floatArrayOf(x + m.w[0][d], y + m.w[1][d])
    }

    fun save(p: SharedPreferences, m: Model) {
        p.edit().putString("gaze", listOf(m.mean, m.std, m.w[0], m.w[1]).joinToString("|") { it.joinToString(",") }).apply()
    }
    fun load(p: SharedPreferences): Model? = try {
        val parts = p.getString("gaze", null)?.split("|")?.map { s -> s.split(",").map { it.toFloat() }.toFloatArray() }
        if (parts == null || parts.size != 4) null else Model(parts[0], parts[1], arrayOf(parts[2], parts[3]))
    } catch (e: Exception) { null }
}
