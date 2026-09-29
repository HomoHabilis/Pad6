package io.github.pyp6.core.dsp

import io.github.pyp6.core.audio.Audio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Band-limited sample rate conversion (windowed sinc, Kaiser window).
 *
 * The desktop app resamples through pydub/audioop, which interpolates
 * without an anti-aliasing filter. Going down from 44.1 kHz to 11 kHz that
 * folds everything above 5.5 kHz back into the audible range, so this uses
 * a proper low-pass instead: what reaches the P-6 is cleaner, and the same
 * length and pitch.
 */
object Resampler {
    private const val ZERO_CROSSINGS = 24
    private const val TABLE_RES = 512
    private const val KAISER_BETA = 8.6

    /** sinc(x) * kaiser(x), sampled at 1/TABLE_RES steps over 0..ZERO_CROSSINGS. */
    private val table: FloatArray by lazy {
        val size = ZERO_CROSSINGS * TABLE_RES + 2
        val i0Beta = besselI0(KAISER_BETA)
        FloatArray(size) { i ->
            val x = i.toDouble() / TABLE_RES
            if (x >= ZERO_CROSSINGS) 0f else {
                val s = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val r = x / ZERO_CROSSINGS
                val w = besselI0(KAISER_BETA * sqrt(1 - r * r)) / i0Beta
                (s * w).toFloat()
            }
        }
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4
        var k = 1
        while (k < 200) {
            term *= q / (k.toDouble() * k)
            sum += term
            if (term < sum * 1e-12) break
            k++
        }
        return sum
    }

    private fun kernel(x: Double): Float {
        val ax = abs(x) * TABLE_RES
        val i = ax.toInt()
        if (i >= table.size - 1) return 0f
        val f = (ax - i).toFloat()
        return table[i] * (1 - f) + table[i + 1] * f
    }

    fun outputFrames(frames: Int, fromRate: Double, toRate: Int): Int =
        (frames * toRate / fromRate).roundToInt()

    /**
     * Resamples [samples] recorded at [fromRate] to [toRate]. [fromRate] may
     * be fractional: a vari-speed pitch shift relabels the source rate.
     */
    fun resample(samples: FloatArray, fromRate: Double, toRate: Int): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        if (abs(fromRate - toRate) < 1e-9) return samples.copyOf()
        val outN = outputFrames(samples.size, fromRate, toRate)
        val out = FloatArray(outN)
        val step = fromRate / toRate               // input samples per output sample
        val cutoff = min(1.0, toRate / fromRate)   // low-pass relative to input Nyquist
        val halfWidth = ZERO_CROSSINGS / cutoff    // in input samples
        val n = samples.size
        for (i in 0 until outN) {
            val t = i * step
            val lo = maxOf(0, floor(t - halfWidth).toInt() + 1)
            val hi = min(n - 1, floor(t + halfWidth).toInt())
            var acc = 0.0
            var norm = 0.0
            for (j in lo..hi) {
                val k = kernel((t - j) * cutoff)
                acc += samples[j] * k
                norm += k
            }
            // Normalising by the kernel sum keeps DC exact near the edges
            // where part of the kernel falls outside the signal.
            out[i] = if (norm > 1e-9) (acc / norm).toFloat() else 0f
        }
        return out
    }

    fun resample(audio: Audio, toRate: Int, fromRate: Double = audio.rate.toDouble()): Audio {
        if (abs(fromRate - toRate) < 1e-9) return audio.withRate(toRate)
        return Audio(toRate, audio.channels.map { resample(it, fromRate, toRate) })
    }
}
