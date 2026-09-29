package io.github.pyp6.core.audio

import kotlin.math.abs
import kotlin.math.max

/**
 * Audio in memory: one float array per channel, samples in -1..1.
 *
 * Planar rather than interleaved because nearly every processing step here
 * works on one channel at a time or on the mono mix.
 */
class Audio(val rate: Int, val channels: List<FloatArray>) {
    init {
        require(channels.isNotEmpty()) { "audio needs at least one channel" }
        require(channels.all { it.size == channels[0].size }) { "channels differ in length" }
    }

    val frames: Int get() = channels[0].size
    val channelCount: Int get() = channels.size
    val durationSeconds: Double get() = if (rate > 0) frames.toDouble() / rate else 0.0
    val isMono: Boolean get() = channels.size == 1

    /** Mean of all channels, as numpy's data.mean(axis=1) gives it. */
    fun mono(): FloatArray {
        if (channels.size == 1) return channels[0]
        val out = FloatArray(frames)
        val n = channels.size
        for (c in channels) for (i in 0 until frames) out[i] += c[i]
        for (i in 0 until frames) out[i] /= n
        return out
    }

    fun toMono(): Audio = if (isMono) this else Audio(rate, listOf(mono()))

    fun slice(from: Int, to: Int): Audio {
        val a = from.coerceIn(0, frames)
        val b = to.coerceIn(a, frames)
        return Audio(rate, channels.map { it.copyOfRange(a, b) })
    }

    fun copy(): Audio = Audio(rate, channels.map { it.copyOf() })

    fun withRate(newRate: Int): Audio = Audio(newRate, channels)

    fun peak(): Float {
        var p = 0f
        for (c in channels) for (v in c) { val a = abs(v); if (a > p) p = a }
        return p
    }

    fun scaled(gain: Float): Audio = Audio(rate, channels.map { c -> FloatArray(c.size) { c[it] * gain } })

    /** Interleaved copy, for audio output. */
    fun interleaved(): FloatArray {
        val n = channels.size
        val out = FloatArray(frames * n)
        for (ch in 0 until n) {
            val c = channels[ch]
            for (i in 0 until frames) out[i * n + ch] = c[i]
        }
        return out
    }

    /** Stereo copy: mono is doubled, anything wider keeps its first two. */
    fun toStereo(): Audio = when (channels.size) {
        1 -> Audio(rate, listOf(channels[0], channels[0].copyOf()))
        2 -> this
        else -> Audio(rate, channels.subList(0, 2))
    }

    fun withChannels(count: Int): Audio = when {
        count == channelCount -> this
        count == 1 -> toMono()
        else -> {
            val m = if (isMono) channels[0] else mono()
            Audio(rate, List(count) { if (it == 0) m else m.copyOf() })
        }
    }

    companion object {
        fun silence(rate: Int, channels: Int, frames: Int): Audio =
            Audio(rate, List(max(1, channels)) { FloatArray(max(0, frames)) })

        fun concat(parts: List<Audio>, rate: Int, channels: Int): Audio {
            val total = parts.sumOf { it.frames }
            val out = List(channels) { FloatArray(total) }
            var at = 0
            for (p in parts) {
                val src = p.withChannels(channels)
                for (ch in 0 until channels) src.channels[ch].copyInto(out[ch], at)
                at += p.frames
            }
            return Audio(rate, out)
        }
    }
}
