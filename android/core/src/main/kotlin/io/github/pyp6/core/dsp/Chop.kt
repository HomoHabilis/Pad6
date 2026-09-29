package io.github.pyp6.core.dsp

import io.github.pyp6.core.P6
import io.github.pyp6.core.audio.Audio
import kotlin.math.floor
import kotlin.math.max

enum class ChopNormalize(val label: String) {
    OFF("Off"),
    PER_SAMPLE("Per sample"),
    WHOLE("Whole file"),
}

/**
 * Builds a multisample for the P-6's Chop function (Sample Edit, Voice):
 * N sources, each trimmed of leading silence and cut or padded to one slot
 * of an exact grid, laid end to end. Port of build_chop_file.
 *
 * The grid is kept in frames rather than milliseconds - the device counts
 * frames, so every slot boundary lands exactly where its fixed grid expects
 * and the total is exactly the rate's length limit.
 */
object Chop {
    fun slotSeconds(rate: Int, channels: Int, slices: Int): Double? =
        P6.maxSeconds(rate, channels)?.let { it / slices }

    fun build(
        sources: List<Audio>,
        rate: Int,
        channels: Int,
        slices: Int,
        normalize: ChopNormalize = ChopNormalize.OFF,
    ): Audio {
        val limit = P6.maxSeconds(rate, channels)
            ?: throw IllegalArgumentException("No duration limit defined for ${rate}Hz/${channels}ch.")
        val totalFrames = floor(limit * rate).toInt()
        val bounds = IntArray(slices + 1) { Math.rint(it.toDouble() * totalFrames / slices).toInt() }
        val used = sources.take(slices)
        val parts = ArrayList<Audio>()
        for ((idx, src) in used.withIndex()) {
            val slot = bounds[idx + 1] - bounds[idx]
            var a = Resampler.resample(src, rate).withChannels(channels)
            val lead = Edit.leadingSilenceFrames(a)
            a = a.slice(lead, a.frames)
            if (a.frames > slot) {
                val cut = Edit.snapBackwardToZero(a, slot)
                a = a.slice(0, cut)
                if (a.frames > rate * 4 / 1000) a = Edit.fadeOut(a, 2.0)
            }
            if (normalize == ChopNormalize.PER_SAMPLE) a = Edit.pydubNormalize(a)
            if (a.frames < slot) {
                val tail = Edit.snapBackwardToZero(a, a.frames)
                a = a.slice(0, tail)
                a = Audio.concat(listOf(a, Audio.silence(rate, channels, slot - a.frames)), rate, channels)
            }
            parts.add(a)
        }
        if (used.size < slices) {
            val remaining = totalFrames - bounds[used.size]
            if (remaining > 0) parts.add(Audio.silence(rate, channels, remaining))
        }
        var combined = Audio.concat(parts, rate, channels)
        if (normalize == ChopNormalize.WHOLE) combined = Edit.pydubNormalize(combined)
        if (combined.frames > totalFrames) combined = combined.slice(0, totalFrames)
        return combined
    }

    /** Pieces of [audio] between consecutive cut positions (the Detect Hits result). */
    fun piecesAt(audio: Audio, cuts: List<Int>): List<Audio> {
        val edges = (cuts.filter { it in 0 until audio.frames } + audio.frames).toSortedSet().toList()
        val start = if (edges.first() == 0) edges else listOf(0) + edges
        val out = ArrayList<Audio>()
        for (i in 0 until start.size - 1) {
            val seg = audio.slice(start[i], start[i + 1])
            if (seg.frames >= 16) out.add(Edit.microFade(seg, 2.0))
        }
        return out
    }

    @Suppress("unused")
    private fun clampSlices(n: Int) = max(1, n)
}
