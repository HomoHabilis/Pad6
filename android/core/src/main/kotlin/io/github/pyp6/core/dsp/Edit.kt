package io.github.pyp6.core.dsp

import io.github.pyp6.core.audio.Audio
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Non-destructive edits: every function returns new audio, the input is
 * never touched. Ports of trim_wav_file, normalize_wav_file,
 * apply_fade_envelope, apply_micro_fade and friends.
 */
object Edit {

    /**
     * Closest index to [target] (within +/- [radius]) where the mono mix
     * crosses zero; [target] itself when there is none.
     */
    fun findZeroCrossing(mono: FloatArray, target: Int, radius: Int): Int {
        val n = mono.size
        if (n < 2) return target
        val t = target.coerceIn(0, n - 1)
        val lo = max(0, t - radius)
        val hi = min(n - 2, t + radius)
        if (lo >= hi) return t
        var best = -1
        var bestDist = Int.MAX_VALUE
        for (i in lo..hi) {
            if (mono[i] == 0f || (mono[i] < 0) != (mono[i + 1] < 0)) {
                val idx = if (abs(mono[i]) <= abs(mono[i + 1])) i else i + 1
                val d = abs(idx - t)
                if (d < bestDist) {
                    best = idx; bestDist = d
                }
            }
        }
        return if (best >= 0) best else t
    }

    /** Linear fade of [fadeMs] at both ends, against clicks at hard cuts. */
    fun microFade(audio: Audio, fadeMs: Double = 2.0): Audio {
        val len = min((audio.rate * fadeMs / 1000).toInt(), audio.frames / 2)
        if (len <= 0) return audio
        val ramp = linspace(0.0, 1.0, len)
        return Audio(audio.rate, audio.channels.map { c ->
            val o = c.copyOf()
            for (i in 0 until len) {
                o[i] = (o[i] * ramp[i]).toFloat()
                o[o.size - len + i] = (o[o.size - len + i] * ramp[len - 1 - i]).toFloat()
            }
            o
        })
    }

    /**
     * Cuts [startFrac]..[endFrac], moving both cut points to a nearby zero
     * crossing (within [snapMs]) and finishing with a [fadeMs] safety fade.
     */
    fun trim(audio: Audio, startFrac: Double, endFrac: Double, fadeMs: Double = 0.5, snapMs: Double = 5.0): Audio {
        val n = audio.frames
        if (n == 0) return audio
        var start = (startFrac * n).toInt().coerceIn(0, n - 1)
        var end = max(start + 1, min((endFrac * n).toInt(), n))
        val radius = (audio.rate * snapMs / 1000).toInt()
        val mono = audio.mono()
        start = findZeroCrossing(mono, start, radius)
        end = findZeroCrossing(mono, end, radius)
        if (end <= start) end = min(n, start + 1)
        return microFade(audio.slice(start, end), fadeMs)
    }

    /** Peak-normalises to [targetPeak] (0.98 leaves room against rounding). */
    fun normalize(audio: Audio, targetPeak: Double = 0.98): Audio {
        val peak = audio.peak()
        if (peak <= 0f) return audio
        return audio.scaled((targetPeak / peak).toFloat())
    }

    /** Linear fade in/out, the one envelope preview, audition and file writing share. */
    fun fades(audio: Audio, fadeInS: Double, fadeOutS: Double): Audio {
        val n = audio.frames
        if (n == 0 || (fadeInS <= 0 && fadeOutS <= 0)) return audio
        val fin = min((fadeInS * audio.rate).toInt(), n)
        val fout = min((fadeOutS * audio.rate).toInt(), n)
        val rin = linspace(0.0, 1.0, fin)
        val rout = linspace(1.0, 0.0, fout)
        return Audio(audio.rate, audio.channels.map { c ->
            val o = c.copyOf()
            for (i in 0 until fin) o[i] = (o[i] * rin[i]).toFloat()
            for (i in 0 until fout) o[n - fout + i] = (o[n - fout + i] * rout[i]).toFloat()
            o
        })
    }

    /**
     * Speed multiplier of a vari-speed pitch shift of [cents]. Above 1 means
     * faster playback, a shorter sample, higher pitch.
     */
    fun pitchSpeedFactor(cents: Int): Double = 2.0.pow(cents / 1200.0)

    /**
     * Pitch by vari-speed, like a tape: the source is relabelled at
     * rate * factor and resampled to [toRate]. Length changes with the pitch.
     */
    fun pitchAndResample(audio: Audio, cents: Int, toRate: Int): Audio {
        val from = if (cents != 0) (audio.rate * pitchSpeedFactor(cents)).toInt().toDouble() else audio.rate.toDouble()
        return Resampler.resample(audio, toRate, from)
    }

    /** Peak value in dBFS, pydub's max_dBFS. */
    fun peakDbfs(audio: Audio): Double {
        val p = audio.peak()
        return if (p <= 0f) Double.NEGATIVE_INFINITY else 20 * kotlin.math.log10(p.toDouble())
    }

    /** pydub.effects.normalize: peak to -headroom dBFS (0.1 dB by default). */
    fun pydubNormalize(audio: Audio, headroomDb: Double = 0.1): Audio {
        val p = audio.peak()
        if (p <= 0f) return audio
        val target = 10.0.pow(-headroomDb / 20.0)
        return audio.scaled((target / p).toFloat())
    }

    /**
     * pydub's detect_leading_silence: the start of the first 10 ms chunk
     * whose RMS level reaches [thresholdDb] dBFS, in frames.
     */
    fun leadingSilenceFrames(audio: Audio, thresholdDb: Double = -50.0, chunkMs: Int = 10): Int {
        val chunk = max(1, audio.rate * chunkMs / 1000)
        val n = audio.frames
        var at = 0
        // pydub measures RMS of the interleaved samples against full scale.
        while (at < n) {
            val end = min(n, at + chunk)
            var sum = 0.0
            var count = 0
            for (c in audio.channels) for (i in at until end) {
                sum += c[i].toDouble() * c[i]; count++
            }
            val rms = kotlin.math.sqrt(sum / max(1, count))
            val db = if (rms <= 0) Double.NEGATIVE_INFINITY else 20 * kotlin.math.log10(rms)
            if (db >= thresholdDb) return at
            at += chunk
        }
        return n
    }

    /**
     * A zero crossing at or BEFORE [targetFrame], within [searchMs]; never
     * after, so a Chop slice can never grow past its slot.
     */
    fun snapBackwardToZero(audio: Audio, targetFrame: Int, searchMs: Double = 5.0): Int {
        if (targetFrame <= 1) return targetFrame
        val mono = audio.mono()
        val n = mono.size
        if (n < 2) return targetFrame
        val t = min(targetFrame, n - 1)
        val radius = (audio.rate * searchMs / 1000).toInt()
        val lo = max(0, t - radius)
        var i = t - 1
        while (i >= lo) {
            if (mono[i] == 0f || (mono[i] < 0) != (mono[i + 1] < 0)) {
                return if (abs(mono[i]) <= abs(mono[i + 1])) i else i + 1
            }
            i--
        }
        return targetFrame
    }

    /** Linear fade-out over the last [ms] milliseconds (pydub's fade_out). */
    fun fadeOut(audio: Audio, ms: Double): Audio {
        val len = min(audio.frames, (audio.rate * ms / 1000).toInt())
        if (len <= 0) return audio
        return Audio(audio.rate, audio.channels.map { c ->
            val o = c.copyOf()
            val n = o.size
            for (i in 0 until len) o[n - len + i] = o[n - len + i] * (1f - i.toFloat() / len)
            o
        })
    }

    /** min/max pairs per column, for drawing a waveform [columns] wide. */
    fun peakEnvelope(mono: FloatArray, columns: Int, from: Int = 0, to: Int = mono.size): FloatArray {
        val a = from.coerceIn(0, mono.size)
        val b = to.coerceIn(a, mono.size)
        val n = b - a
        val cols = min(columns, max(n, 0))
        if (cols < 1) return FloatArray(0)
        val env = FloatArray(cols * 2)
        for (i in 0 until cols) {
            val lo = a + (i.toLong() * n / cols).toInt()
            val hi = max(a + ((i + 1).toLong() * n / cols).toInt(), lo + 1)
            var mn = Float.MAX_VALUE
            var mx = -Float.MAX_VALUE
            for (j in lo until min(hi, b)) {
                val v = mono[j]
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            if (mn == Float.MAX_VALUE) { mn = 0f; mx = 0f }
            env[2 * i] = mn
            env[2 * i + 1] = mx
        }
        return env
    }
}
