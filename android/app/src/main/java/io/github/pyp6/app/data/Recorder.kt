package io.github.pyp6.app.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.pyp6.core.audio.Audio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** A recording in progress: how long it is so far and the recent peak level (0..1). */
data class Recording(val seconds: Double, val level: Float, val clipped: Boolean)

/**
 * Records the microphone (or whatever input Android routes, e.g. a USB
 * interface) to memory as 44.1 kHz 16-bit mono - the P-6's own format, so
 * nothing is lost on export. The caller must hold RECORD_AUDIO.
 */
class Recorder(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<Recording?>(null)
    val state: StateFlow<Recording?> = _state.asStateFlow()

    private var record: AudioRecord? = null
    private var data = ShortArray(RATE * 10)
    private var size = 0

    val isRecording: Boolean get() = record != null

    /** Starts recording; returns an error text when the input could not be opened. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): String? {
        if (record != null) return null
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return "This device cannot record at $RATE Hz."
        val bufBytes = max(minBuf, RATE / 5 * 2)
        // UNPROCESSED skips noise suppression and AGC where the device offers it: better for samples.
        val r = listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC).firstNotNullOfOrNull { src ->
            runCatching {
                val a = AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
                if (a.state == AudioRecord.STATE_INITIALIZED) a else { a.release(); null }
            }.getOrNull()
        } ?: return "The microphone could not be opened."
        try {
            r.startRecording()
        } catch (e: Exception) {
            r.release()
            return "The microphone could not be started: ${e.message}"
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release()
            return "The microphone is in use by another app."
        }
        size = 0
        record = r
        _state.value = Recording(0.0, 0f, false)
        scope.launch(Dispatchers.IO) {
            // This loop owns [r]: it releases it once stop() has stopped it (which ends a blocked read).
            try {
                val chunk = ShortArray(RATE / 20)
                var clipped = false
                while (isActive) {
                    val n = r.read(chunk, 0, chunk.size)
                    if (n < 0) break
                    if (n == 0) { if (record !== r) break else continue }
                    var peak = 0
                    for (i in 0 until n) peak = max(peak, abs(chunk[i].toInt()))
                    if (peak >= 32767) clipped = true
                    synchronized(this@Recorder) {
                        if (record !== r) return@launch
                        if (size + n > data.size) data = data.copyOf(max(data.size * 2, size + n))
                        System.arraycopy(chunk, 0, data, size, n)
                        size += n
                        _state.value = Recording(size.toDouble() / RATE, peak / 32768f, clipped)
                    }
                    if (size >= MAX_SECONDS * RATE) break
                }
            } finally {
                runCatching { r.stop() }
                r.release()
            }
        }
        return null
    }

    /** Stops and returns what was recorded, or null when nothing was. */
    fun stop(): Audio? {
        val pcm: ShortArray
        val frames: Int
        synchronized(this) {
            val r = record ?: return null
            record = null
            runCatching { r.stop() }
            _state.value = null
            pcm = data
            frames = size
            data = ShortArray(RATE * 10)
            size = 0
        }
        // The first few milliseconds often hold the input settling; skip them.
        val skip = minOf(frames, RATE / 50)
        if (frames - skip <= 0) return null
        return Audio(RATE, listOf(FloatArray(frames - skip) { pcm[it + skip] / 32768f }))
    }

    /** Stops and throws the recording away. */
    fun cancel() {
        stop()
    }

    companion object {
        const val RATE = 44100
        /** A safety stop: far more than one P-6 bank can hold. */
        const val MAX_SECONDS = 10 * 60
    }
}
