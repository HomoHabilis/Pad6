package io.github.pyp6.core.dsp

import io.github.pyp6.core.audio.Audio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How the length of a sample is changed without moving its pitch. */
enum class StretchMethod(val key: String, val label: String) {
    AUTO("auto", "Auto"),
    SLICES("slices", "Move pieces"),
    VOCODER("vocoder", "Stretch spectrum");

    companion object {
        fun fromKey(key: String?): StretchMethod = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * Time stretching: a phase vocoder for anything, and "move the pieces"
 * for shortening drum loops. Ports of time_stretch, time_stretch_slices,
 * choose_stretch_method and stretch_wav_file.
 */
object Stretch {
    const val FFT = 2048
    const val HOP = 512
    const val MAX_RATIO = 4.0
    const val XFADE_MS = 6.0

    /**
     * [samples] (one channel) at [factor] times its length, same pitch.
     * [transients] are sample positions where the phase is reset to the
     * source, which keeps drum strikes sharp.
     */
    fun vocoderChannel(samples: FloatArray, factor: Double, transients: List<Int>): DoubleArray {
        val nfft = FFT
        val hop = HOP
        val x = samples
        val win = hanning(nfft)
        val bins = nfft / 2 + 1
        val expected = DoubleArray(bins) { 2 * PI * hop * it / nfft }
        val resetFrames = transients.map { Math.rint(it / hop.toDouble()).toInt() }.toHashSet()

        val sigLen = x.size + 2 * nfft
        val count = 1 + (sigLen - nfft) / hop
        if (count < 2) return DoubleArray(x.size) { x[it].toDouble() }
        val mag = Array(count) { DoubleArray(bins) }
        val phase = Array(count) { DoubleArray(bins) }
        val frame = DoubleArray(nfft)
        for (f in 0 until count) {
            val start = f * hop - nfft          // offset into x (signal is padded by nfft)
            for (k in 0 until nfft) {
                val si = start + k
                frame[k] = if (si in x.indices) x[si] * win[k] else 0.0
            }
            val (re, im) = Fft.rfft(frame)
            for (b in 0 until bins) {
                mag[f][b] = sqrt(re[b] * re[b] + im[b] * im[b])
                phase[f][b] = atan2(im[b], re[b])
            }
        }

        val step = 1.0 / factor
        val nPos = max(0, ceil((count - 1) / step).toInt())
        val outLen = nPos * hop + nfft
        val acc = DoubleArray(outLen)
        val norm = DoubleArray(outLen)
        val running = phase[0].copyOf()
        val specRe = DoubleArray(bins)
        val specIm = DoubleArray(bins)
        for (i in 0 until nPos) {
            val pos = i * step
            val lo = pos.toInt()
            val frac = pos - lo
            val hi = min(lo + 1, count - 1)
            if (lo in resetFrames) phase[lo].copyInto(running)
            for (b in 0 until bins) {
                val m = (1 - frac) * mag[lo][b] + frac * mag[hi][b]
                specRe[b] = m * cos(running[b])
                specIm[b] = m * sin(running[b])
            }
            val grain = Fft.irfft(specRe, specIm, nfft)
            val start = i * hop
            for (k in 0 until nfft) {
                acc[start + k] += grain[k] * win[k]
                norm[start + k] += win[k] * win[k]
            }
            // True frequency per bin from the phase advance lo -> lo+1.
            if (lo + 1 < count) {
                for (b in 0 until bins) {
                    var delta = phase[lo + 1][b] - phase[lo][b] - expected[b]
                    delta = floorMod(delta + PI, 2 * PI) - PI
                    running[b] += (expected[b] + delta)
                }
            }
        }
        val want = Math.rint(x.size * factor).toInt()
        val y = DoubleArray(want)
        for (i in 0 until want) {
            val src = nfft + i
            if (src < outLen) y[i] = acc[src] / max(norm[src], 1e-8)
        }
        return y
    }

    private fun floorMod(a: Double, m: Double): Double {
        val r = a % m
        return if (r < 0) r + m else r
    }

    fun vocoder(audio: Audio, factor: Double, transients: List<Int>): Audio {
        if (audio.frames <= FFT * 2 || abs(factor - 1.0) < 1e-4) return audio
        val outs = audio.channels.map { vocoderChannel(it, factor, transients) }
        var peakIn = 0.0
        for (c in audio.channels) for (v in c) peakIn = max(peakIn, abs(v.toDouble()))
        var peakOut = 0.0
        for (c in outs) for (v in c) peakOut = max(peakOut, abs(v))
        val gain = if (peakOut > 0 && peakIn > 0) peakIn / peakOut else 1.0
        return Audio(audio.rate, outs.map { c -> FloatArray(c.size) { (c[it] * gain).toFloat() } })
    }

    /**
     * Shortens by moving the pieces between strikes closer together; every
     * piece is copied unchanged, so the attacks survive exactly. Returns the
     * input unchanged when lengthening or when there is nothing to slice.
     */
    fun slices(audio: Audio, factor: Double, onsets: List<Int>): Audio {
        val n = audio.frames
        val cuts = onsets.filter { it in 1 until n }.toSortedSet().toList()
        if (cuts.isEmpty() || factor >= 1.0) return audio
        val rate = audio.rate
        val edges = listOf(0) + cuts + listOf(n)
        val outLen = Math.rint(n * factor).toInt()
        val bufLen = outLen + rate / 10
        val xf = max(1, (XFADE_MS / 1000.0 * rate).toInt())
        val ramp = linspace(0.0, 1.0, xf)
        val out = List(audio.channelCount) { DoubleArray(bufLen) }
        for (i in 0 until edges.size - 1) {
            val a = edges[i]
            val b = edges[i + 1]
            val dst = Math.rint(a * factor).toInt()
            val room = if (i + 1 < edges.size - 1) Math.rint(b * factor).toInt() - dst else bufLen - dst
            var len = min(b - a, max(1, room + xf))
            if (dst + len > bufLen) len = bufLen - dst
            if (len < 1) continue
            for (ch in 0 until audio.channelCount) {
                val src = audio.channels[ch]
                val o = out[ch]
                for (k in 0 until len) {
                    var v = src[a + k].toDouble()
                    if (len > xf) {
                        if (k >= len - xf) v *= ramp[xf - 1 - (k - (len - xf))]
                        if (dst > 0 && k < xf) v *= ramp[k]
                    }
                    o[dst + k] += v
                }
            }
        }
        var peakIn = 0.0
        for (c in audio.channels) for (v in c) peakIn = max(peakIn, abs(v.toDouble()))
        var peakOut = 0.0
        for (c in out) for (k in 0 until outLen) peakOut = max(peakOut, abs(c[k]))
        val gain = if (peakOut > peakIn && peakIn > 0) peakIn / peakOut else 1.0
        return Audio(rate, out.map { c -> FloatArray(outLen) { (c[it] * gain).toFloat() } })
    }

    fun chooseMethod(factor: Double, onsets: List<Int>, preference: StretchMethod): StretchMethod {
        if (preference == StretchMethod.SLICES || preference == StretchMethod.VOCODER) return preference
        return if (onsets.size >= 2 && factor < 1.0) StretchMethod.SLICES else StretchMethod.VOCODER
    }

    /** The whole stretch_wav_file pipeline, in memory. */
    fun stretch(audio: Audio, factor: Double, preference: StretchMethod = StretchMethod.AUTO): Audio {
        val f = factor.coerceIn(1.0 / MAX_RATIO, MAX_RATIO)
        val mono = audio.mono()
        val hits = runCatching { Analysis.detectHits(mono, audio.rate, 1.8) }.getOrDefault(emptyList())
        val chosen = chooseMethod(f, hits, preference)
        fun voc(): Audio {
            val onsets = runCatching { Analysis.detectOnsets(mono, audio.rate, 1.8) }.getOrDefault(emptyList())
            return vocoder(audio, f, onsets)
        }
        return if (chosen == StretchMethod.SLICES) {
            val out = slices(audio, f, hits)
            if (out.frames == audio.frames) voc() else out
        } else voc()
    }
}
