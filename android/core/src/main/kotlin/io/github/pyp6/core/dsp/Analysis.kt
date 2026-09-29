package io.github.pyp6.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where the strikes are, and what tempo a loop runs at. Ports of
 * detect_onsets, detect_hits, detect_bpm and bpm_octave_alternative from
 * the desktop app, constants and gates included - those were measured on
 * real loop libraries and are kept exactly.
 */
object Analysis {
    const val ONSET_FRAME = 1024
    const val ONSET_HOP = 512

    const val HIT_HOP = 256
    const val HIT_WINDOW = 1024
    const val HIT_MIN_GAP_MS = 35.0
    const val HIT_BACKTRACK_MS = 25.0

    const val BPM_MIN = 60.0
    const val BPM_MAX = 200.0

    /** Spectral flux between consecutive windows: flux[i] = rise from window i to i+1. */
    private fun spectralFlux(x: DoubleArray, frame: Int, hop: Int): DoubleArray {
        val count = 1 + (x.size - frame) / hop
        if (count < 2) return DoubleArray(0)
        val win = hanning(frame)
        var prev: DoubleArray? = null
        val flux = DoubleArray(count - 1)
        val buf = DoubleArray(frame)
        for (f in 0 until count) {
            val off = f * hop
            for (k in 0 until frame) buf[k] = x[off + k] * win[k]
            val mag = Fft.rfftMagnitude(buf)
            if (prev != null) {
                var s = 0.0
                for (k in mag.indices) {
                    val d = mag[k] - prev[k]
                    if (d > 0) s += d
                }
                flux[f - 1] = s
            }
            prev = mag
        }
        return flux
    }

    private fun toMonoDouble(samples: FloatArray): DoubleArray = DoubleArray(samples.size) { samples[it].toDouble() }

    private fun mean(a: DoubleArray): Double = if (a.isEmpty()) 0.0 else a.sum() / a.size
    private fun std(a: DoubleArray): Double {
        if (a.isEmpty()) return 0.0
        val m = mean(a)
        var s = 0.0
        for (v in a) s += (v - m) * (v - m)
        return sqrt(s / a.size)
    }

    /**
     * Attack positions (sample indices) via spectral flux, used only to place
     * the phase vocoder's phase resets. [sensitivity]: higher = fewer hits.
     */
    fun detectOnsets(
        mono: FloatArray, rate: Int, sensitivity: Double = 1.8, minGapS: Double = 0.05,
        frame: Int = ONSET_FRAME, hop: Int = ONSET_HOP,
    ): List<Int> {
        val n = mono.size
        if (n < frame * 2) return emptyList()
        val nFrames = 1 + (n - frame) / hop
        if (nFrames < 3) return emptyList()
        val flux = spectralFlux(toMonoDouble(mono), frame, hop)
        if (flux.isEmpty()) return emptyList()
        val top = flux.max()
        if (top <= 0) return emptyList()
        for (i in flux.indices) flux[i] = flux[i] / (top + 1e-12)
        val thresh = mean(flux) + sensitivity * std(flux)
        val out = ArrayList<Int>()
        val minGap = max(1, (minGapS * rate / hop).toInt())
        var last = -minGap
        for (i in flux.indices) {
            if (flux[i] > thresh && (i - last) >= minGap) {
                out.add(i * hop + frame)
                last = i
            }
        }
        return out
    }

    /**
     * Frame positions to cut a rhythm loop at, one per strike. Running-median
     * threshold on the flux, then each peak is walked back to the quiet foot
     * of its attack so the slice keeps the crack of the drum.
     */
    fun detectHits(mono: FloatArray, rate: Int, sensitivity: Double = 1.0): List<Int> {
        if (mono.size < HIT_WINDOW * 2) return emptyList()
        var peak = 0.0
        for (v in mono) peak = max(peak, abs(v.toDouble()))
        if (peak < 1e-9) return emptyList()
        val x = DoubleArray(mono.size) { mono[it] / peak }
        val win = HIT_WINDOW
        val hop = HIT_HOP
        val count = 1 + (x.size - win) / hop
        val rawFlux = spectralFlux(x, win, hop)
        val flux = DoubleArray(count)
        for (i in 1 until count) flux[i] = rawFlux[i - 1]
        flux[0] = 0.0
        val fmax = flux.max()
        if (fmax > 0) for (i in flux.indices) flux[i] /= fmax

        val k = max(3, (0.35 * rate / hop).toInt()) or 1
        val half = k / 2
        val threshold = DoubleArray(count)
        val window = DoubleArray(k)
        for (i in 0 until count) {
            for (j in 0 until k) {
                val src = (i - half + j).coerceIn(0, count - 1)   // np.pad mode="edge"
                window[j] = flux[src]
            }
            window.sort()
            threshold[i] = window[k / 2] + sensitivity * 0.10
        }

        val gap = max(1, (HIT_MIN_GAP_MS / 1000.0 * rate / hop).toInt())
        val peaks = ArrayList<Int>()
        var last = -1_000_000_000
        for (i in 1 until flux.size - 1) {
            if (flux[i] > threshold[i] && flux[i] >= flux[i - 1] && flux[i] > flux[i + 1] && i - last > gap) {
                peaks.add(i); last = i
            }
        }

        val rmsWin = max(8, (0.001 * rate).toInt())
        val env = movingRms(x, rmsWin)
        val back = (HIT_BACKTRACK_MS / 1000.0 * rate).toInt()
        val cuts = ArrayList<Int>()
        var prev = -1
        for (p in peaks) {
            val centre = p * hop
            val lo = max(prev + 1, centre - win)
            val hi = min(x.size - 1, centre + hop + win / 2)
            if (hi - lo < rmsWin * 4) continue
            var rise = lo
            var best = -Double.MAX_VALUE
            for (i in lo until hi - 1) {
                val d = env[i + 1] - env[i]
                if (d > best) { best = d; rise = i }
            }
            val footLo = max(lo, rise - back)
            val len = rise + 1 - footLo
            var pos: Int
            if (len > 2) {
                var mn = Double.MAX_VALUE
                for (i in footLo..rise) mn = min(mn, env[i])
                val limit = max(env[rise] * 0.15, mn * 1.05)
                var lastQuiet = -1
                for (i in footLo..rise) if (env[i] <= limit) lastQuiet = i - footLo
                pos = footLo + (if (lastQuiet >= 0) lastQuiet else 0)
            } else {
                pos = rise
            }
            pos = pos.coerceIn(0, x.size - 1)
            if (cuts.isEmpty() || pos > cuts.last()) {
                cuts.add(pos); prev = pos
            }
        }

        // A loop that begins on a strike gives the detector nothing to compare
        // the first window against; if the file opens loud the cut is at 0.
        val headN = min(x.size, (0.015 * rate).toInt())
        var headPeak = 0.0
        for (i in 0 until headN) headPeak = max(headPeak, abs(x[i]))
        if (headN > 0 && headPeak > 0.15) {
            if (cuts.isNotEmpty() && cuts[0] < (0.05 * rate).toInt()) cuts[0] = 0
            else if (cuts.isEmpty() || cuts[0] > 0) cuts.add(0, 0)
        }
        return cuts
    }

    /** sqrt(np.convolve(x*x, ones(w)/w, mode="same")). */
    private fun movingRms(x: DoubleArray, w: Int): DoubleArray {
        val n = x.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + x[i] * x[i]
        val off = (w - 1) / 2
        return DoubleArray(n) { i ->
            val hiIdx = min(n - 1, i + off)
            val loIdx = max(0, i + off - (w - 1))
            val s = prefix[hiIdx + 1] - prefix[loIdx]
            sqrt(max(0.0, s / w))
        }
    }

    // ---------------------------------------------------------------- tempo

    private val LOOP_BEAT_WEIGHTS = linkedMapOf(
        4 to 1.0, 8 to 1.0, 16 to 1.0, 32 to 0.9, 64 to 0.7,
        2 to 0.55, 1 to 0.3,
        3 to 0.5, 6 to 0.5, 12 to 0.5, 24 to 0.5, 48 to 0.4,
    )
    private const val BPM_PRIOR_CENTRE = 125.0
    private const val BPM_PRIOR_WIDTH = 3.0
    private const val BPM_OCTAVE_CENTRE = 125.0
    private const val BPM_OCTAVE_WIDTH = 0.7
    private const val BPM_OCTAVE_TIE = 0.9
    private val BPM_SUBDIV_CREDIT = listOf(2 to 0.5, 4 to 0.25)
    private const val BPM_AC_STRONG = 0.25
    private const val BPM_AC_FLOOR = 0.12
    private const val BPM_WEAK_GRID_FIT = 0.50
    private const val BPM_GRID_PHASES = 12
    private const val BPM_MIN_ONSET_SPAN = 0.45
    private const val BPM_MIN_ONSETS = 4
    private const val BPM_MIN_GRID_FIT = 0.40
    private const val BPM_MIN_FLUX_CREST = 3.5

    private class Onsets(val times: DoubleArray, val weights: DoubleArray, val duration: Double)

    private fun log2(x: Double) = ln(x) / ln(2.0)

    private fun round1(x: Double) = Math.rint(x * 10) / 10
    private fun round2(x: Double) = Math.rint(x * 100) / 100

    private fun bpmFlux(x: DoubleArray, frame: Int, hop: Int): DoubleArray = spectralFlux(x, frame, hop)

    private fun onsetPeaks(flux: DoubleArray, fps: Double): Pair<DoubleArray, DoubleArray> {
        if (flux.size < 4) return DoubleArray(0) to DoubleArray(0)
        val top = flux.max()
        if (top <= 0) return DoubleArray(0) to DoubleArray(0)
        val f = DoubleArray(flux.size) { flux[it] / top }
        val thr = mean(f) + 0.8 * std(f)
        val times = ArrayList<Double>()
        val strengths = ArrayList<Double>()
        val guard = max(1, (0.04 * fps).toInt())
        var i = 1
        val limit = f.size - 1
        while (i < limit) {
            if (f[i] > thr && f[i] >= f[i - 1] && f[i] >= f[i + 1]) {
                times.add(i / fps); strengths.add(f[i]); i += guard
            } else i++
        }
        return times.toDoubleArray() to strengths.toDoubleArray()
    }

    private fun normalizedMono(samples: FloatArray): DoubleArray? {
        var peak = 0.0
        for (v in samples) peak = max(peak, abs(v.toDouble()))
        if (peak < 1e-9) return null
        return DoubleArray(samples.size) { samples[it] / peak }
    }

    private fun onsetsOf(samples: FloatArray, rate: Int, frame: Int = 1024, hop: Int = 256): Onsets? {
        val n = samples.size
        val duration = n / rate.toDouble()
        if (n < frame * 4 || duration < 0.5) return null
        val x = normalizedMono(samples) ?: return null
        val count = 1 + (n - frame) / hop
        if (count < 8) return null
        val flux = bpmFlux(x, frame, hop)
        if (flux.size < 8 || flux.max() <= 0) return null
        if (flux.max() / (mean(flux) + 1e-12) < BPM_MIN_FLUX_CREST) return null
        val (times, weights) = onsetPeaks(flux, rate / hop.toDouble())
        if (times.size < BPM_MIN_ONSETS) return null
        if ((times.last() - times.first()) / duration < BPM_MIN_ONSET_SPAN) return null
        return Onsets(times, weights, duration)
    }

    private fun gridFit(o: Onsets, bpm: Double): Double {
        val onsets = o.times
        val weights = o.weights
        if (onsets.isEmpty()) return 0.0
        val period = 60.0 / bpm
        val tol = min(0.09 * period, 0.045)
        val nLines = (o.duration / period).toInt()
        if (nLines < 2) return 0.0
        val totalW = weights.sum().let { if (it == 0.0) 1.0 else it }
        val peakW = weights.max().let { if (it == 0.0) 1.0 else it }
        var best = 0.0
        for (step in 0 until BPM_GRID_PHASES) {
            val offset = step * period / BPM_GRID_PHASES
            val gained = DoubleArray(onsets.size)
            val perLine = DoubleArray(nLines)
            for ((oi, t) in onsets.withIndex()) {
                // Nearest beat line and the loudest onset per line.
                var minDist = Double.MAX_VALUE
                for (l in 0 until nLines) {
                    val d = abs(t - (offset + l * period))
                    if (d < minDist) minDist = d
                    if (d <= tol && weights[oi] > perLine[l]) perLine[l] = weights[oi]
                }
                if (minDist <= tol) gained[oi] = 1.0
            }
            for ((div, credit) in BPM_SUBDIV_CREDIT) {
                val sub = period / div
                val nSub = nLines * div
                for ((oi, t) in onsets.withIndex()) {
                    // Nearest subdivision line; lines are evenly spaced, so the
                    // closest index can be computed rather than searched.
                    val idx = Math.rint((t - offset) / sub).toInt().coerceIn(0, nSub - 1)
                    var d = abs(t - (offset + idx * sub))
                    if (idx > 0) d = min(d, abs(t - (offset + (idx - 1) * sub)))
                    if (idx < nSub - 1) d = min(d, abs(t - (offset + (idx + 1) * sub)))
                    if (d <= tol) gained[oi] = max(gained[oi], credit)
                }
            }
            var recall = 0.0
            for (i in onsets.indices) recall += weights[i] * gained[i]
            recall /= totalW
            val precision = perLine.average() / peakW
            if (recall + precision <= 0) continue
            best = max(best, 2 * recall * precision / (recall + precision))
        }
        return best
    }

    /** Estimated tempo, or null when the sample has no clear pulse. */
    fun detectBpm(samples: FloatArray, rate: Int, frame: Int = 1024, hop: Int = 256): Double? {
        val o = onsetsOf(samples, rate, frame, hop) ?: return null
        val x = normalizedMono(samples) ?: return null
        val flux = bpmFlux(x, frame, hop)
        val fps = rate / hop.toDouble()
        val m = mean(flux)
        for (i in flux.indices) flux[i] -= m
        val ac = autocorrelation(flux)
        if (ac.size < 4 || ac[0] <= 0) return null
        val a0 = ac[0]
        for (i in ac.indices) ac[i] /= a0
        val lo = max(1, (fps * 60.0 / BPM_MAX).roundToInt())
        val hi = min(ac.size - 1, (fps * 60.0 / BPM_MIN).roundToInt())
        if (hi <= lo) return null
        val band = ac.copyOfRange(lo, hi + 1)
        val acPeak = if (band.isEmpty()) 0.0 else band.max()
        if (acPeak < BPM_AC_FLOOR) return null
        val weakPulse = acPeak < BPM_AC_STRONG

        fun acAt(lag: Double): Double {
            if (lag < 1 || lag >= ac.size) return 0.0
            val a = max(1, floor(lag - 1).toInt())
            val b = min(ac.size - 1, ceil(lag + 1).toInt())
            var mx = -Double.MAX_VALUE
            for (i in a..b) mx = max(mx, ac[i])
            return mx
        }

        val candidates = LinkedHashMap<Double, Double?>()
        for ((beats, w) in LOOP_BEAT_WEIGHTS) {
            val bpm = beats * 60.0 / o.duration
            if (bpm in BPM_MIN..BPM_MAX) candidates[round2(bpm)] = w
        }
        val order = band.indices.sortedByDescending { band[it] }.take(8)
        for (i in order) {
            val bpm = 60.0 * fps / (lo + i)
            if (candidates.keys.none { abs(it - bpm) / bpm < 0.02 }) candidates[round2(bpm)] = null
        }
        if (candidates.isEmpty()) return null

        var bestBpm: Double? = null
        var bestScore = -1.0
        var bestFit = 0.0
        for ((bpm, lengthWeight) in candidates) {
            val strength = acAt(fps * 60.0 / bpm)
            val fit = gridFit(o, bpm)
            val z = log2(bpm / BPM_PRIOR_CENTRE) / BPM_PRIOR_WIDTH
            val prior = exp(-0.5 * z * z)
            val bonus = if (lengthWeight != null) 1.35 * lengthWeight else 1.0
            val score = (0.65 * fit + 0.35 * strength) * prior * bonus
            if (score > bestScore) {
                bestBpm = bpm; bestScore = score; bestFit = fit
            }
        }
        var best = bestBpm ?: return null
        if (weakPulse) {
            if (bestFit < BPM_WEAK_GRID_FIT || candidates[best] == null) return null
        } else if (bestFit < BPM_MIN_GRID_FIT) return null

        fun octavePrior(b: Double): Double {
            val z = log2(b / BPM_OCTAVE_CENTRE) / BPM_OCTAVE_WIDTH
            return exp(-0.5 * z * z)
        }
        for (alt in listOf(best * 2.0, best / 2.0)) {
            if (alt !in BPM_MIN..BPM_MAX) continue
            val altFit = gridFit(o, alt)
            if (altFit < BPM_MIN_GRID_FIT) continue
            if (altFit < BPM_OCTAVE_TIE * bestFit) continue
            if (altFit * octavePrior(alt) > bestFit * octavePrior(best)) {
                best = alt; bestFit = altFit
            }
        }

        val beats = o.duration * best / 60.0
        val nearest = Math.rint(beats)
        if (nearest >= 1 && abs(beats - nearest) < 0.05) {
            val snapped = nearest * 60.0 / o.duration
            if (snapped in BPM_MIN..BPM_MAX) best = snapped
        }
        return round1(best)
    }

    /**
     * The other octave of [bpm] (half or double) when that reading fits the
     * onsets as well as the detector's own bar requires; null otherwise.
     */
    fun bpmOctaveAlternative(samples: FloatArray, rate: Int, bpm: Double?): Double? {
        if (bpm == null || bpm == 0.0) return null
        val o = onsetsOf(samples, rate) ?: return null
        for (alt in listOf(bpm / 2.0, bpm * 2.0)) {
            if (alt !in BPM_MIN..BPM_MAX) continue
            if (gridFit(o, alt) >= BPM_MIN_GRID_FIT) return round1(alt)
        }
        return null
    }

    /** ac[lag] = sum f[i] f[i+lag], via FFT (np.correlate(f, f, "full")[n-1:]). */
    private fun autocorrelation(f: DoubleArray): DoubleArray {
        val n = f.size
        val m = Fft.nextPowerOfTwo(2 * n)
        val re = DoubleArray(m)
        val im = DoubleArray(m)
        f.copyInto(re)
        Fft.transform(re, im, false)
        for (i in 0 until m) {
            re[i] = re[i] * re[i] + im[i] * im[i]
            im[i] = 0.0
        }
        Fft.transform(re, im, true)
        return DoubleArray(n) { re[it] / m }
    }

    @Suppress("unused")
    private val TWO_PI = 2 * PI
}
