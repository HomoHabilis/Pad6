package io.github.pyp6.core.wavetable

import io.github.pyp6.core.dsp.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates waveforms of length [L] containing [R] cycles, from [h]
 * harmonics. Phase runs 0..R across the segment, so harmonic k lands on FFT
 * bin k*R and the result stays exactly periodic over the whole segment.
 */
class WtSynth(val L: Int, val R: Int, val h: Int) {
    /** Phase 0..R across the segment. */
    val t = DoubleArray(L) { it.toDouble() / L * R }
    /** Phase within the current cycle, 0..1. */
    val tf = DoubleArray(L) { t[it] % 1.0 }
    /** Harmonic numbers 1..h. */
    val k = DoubleArray(h) { (it + 1).toDouble() }
    val sinB: Array<DoubleArray> = Array(h) { kk -> DoubleArray(L) { sin(2 * PI * (kk + 1) * t[it]) } }
    val cosB: Array<DoubleArray> = Array(h) { kk -> DoubleArray(L) { cos(2 * PI * (kk + 1) * t[it]) } }

    private fun padTo(values: DoubleArray): DoubleArray {
        val a = DoubleArray(h)
        values.copyInto(a, 0, 0, minOf(values.size, h))
        return a
    }

    /** Sum of sines with amplitudes [amps] (and optional phases). */
    fun add(amps: DoubleArray, phases: DoubleArray? = null): DoubleArray {
        val a = padTo(amps)
        val out = DoubleArray(L)
        if (phases == null) {
            for (kk in 0 until h) {
                val ak = a[kk]
                if (ak == 0.0) continue
                val row = sinB[kk]
                for (i in 0 until L) out[i] += ak * row[i]
            }
            return out
        }
        val p = padTo(phases)
        for (kk in 0 until h) {
            val s = a[kk] * cos(p[kk])
            val c = a[kk] * sin(p[kk])
            if (s == 0.0 && c == 0.0) continue
            val rs = sinB[kk]
            val rc = cosB[kk]
            for (i in 0 until L) out[i] += s * rs[i] + c * rc[i]
        }
        return out
    }

    fun addSc(ampsSin: DoubleArray, ampsCos: DoubleArray): DoubleArray {
        val s = padTo(ampsSin)
        val c = padTo(ampsCos)
        val out = DoubleArray(L)
        for (kk in 0 until h) {
            val rs = sinB[kk]
            val rc = cosB[kk]
            val a = s[kk]
            val b = c[kk]
            for (i in 0 until L) out[i] += a * rs[i] + b * rc[i]
        }
        return out
    }

    /**
     * Removes DC, everything above harmonic h and the Nyquist bin (which
     * add_sc's cosines would otherwise leave on the Bass tables).
     */
    fun bandLimit(w: DoubleArray): DoubleArray {
        val (re, im) = Fft.rfft(w)
        re[0] = 0.0; im[0] = 0.0
        val cut = h * R
        if (cut + 1 < re.size) for (i in cut + 1 until re.size) { re[i] = 0.0; im[i] = 0.0 }
        re[re.size - 1] = 0.0; im[im.size - 1] = 0.0
        return Fft.irfft(re, im, L)
    }

    fun formant(f0: Double, centers: DoubleArray, gains: DoubleArray, bws: DoubleArray, tilt: Double = 1.0): DoubleArray {
        val out = DoubleArray(h)
        for (i in 0 until h) {
            val freq = k[i] * f0
            var env = 0.0
            for (j in centers.indices) {
                val z = (freq - centers[j]) / bws[j]
                env += gains[j] * kotlin.math.exp(-0.5 * z * z)
            }
            env += 0.02
            out[i] = (1.0 / Math.pow(k[i], tilt)) * env
        }
        return out
    }

    companion object {
        fun normalizePeak(w: DoubleArray): DoubleArray {
            var peak = 0.0
            for (v in w) peak = maxOf(peak, abs(v))
            if (peak > 1e-12) for (i in w.indices) w[i] /= peak
            return w
        }
    }
}
