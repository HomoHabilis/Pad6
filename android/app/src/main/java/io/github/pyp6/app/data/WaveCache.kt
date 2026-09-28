package io.github.pyp6.app.data

import android.util.LruCache
import io.github.pyp6.core.audio.Wav
import io.github.pyp6.core.dsp.Edit
import java.io.File

/** What a pad card shows about its file: length, format and a small waveform. */
data class WaveSummary(
    val frames: Int,
    val rate: Int,
    val channels: Int,
    val durationSeconds: Double,
    /** min/max pairs, [COLUMNS] of them. */
    val envelope: FloatArray,
    val bytes: Long,
) {
    companion object {
        const val COLUMNS = 300
    }
}

/**
 * Summaries per file, computed once. Files are never changed in place (an
 * edit is a new file), so the path alone is a safe key.
 */
object WaveCache {
    private val cache = LruCache<String, WaveSummary>(120)

    fun peek(path: String): WaveSummary? = cache.get(path)

    fun get(path: String): WaveSummary? {
        cache.get(path)?.let { return it }
        val f = File(path)
        val s = runCatching {
            val a = Wav.read(f)
            val env = Edit.peakEnvelope(a.mono(), WaveSummary.COLUMNS)
            WaveSummary(a.frames, a.rate, a.channelCount, a.durationSeconds, env, f.length())
        }.getOrNull() ?: return null
        cache.put(path, s)
        return s
    }
}
