package io.github.pyp6.app.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.pyp6.core.audio.Audio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What is playing: an id chosen by the caller, and how far along it is (0..1). */
data class Playback(val id: String, val position: Double, val frames: Int, val rate: Int, val loop: Boolean)

/**
 * Plays one buffer at a time through a static AudioTrack. Static means the
 * whole buffer is handed over once and the hardware loops it itself, which
 * keeps pattern previews sample-accurate. Starting anything stops whatever
 * was playing, like the desktop app.
 */
class Player(private val scope: CoroutineScope) {
    private var track: AudioTrack? = null
    private var ticker: Job? = null
    private val _state = MutableStateFlow<Playback?>(null)
    val state: StateFlow<Playback?> = _state.asStateFlow()

    fun isPlaying(id: String) = _state.value?.id == id

    @Synchronized
    /** [loopStart]: where each repeat of a looped buffer begins (a lead-in before it plays once). */
    fun play(id: String, audio: Audio, loop: Boolean = false, startFrac: Double = 0.0, loopStart: Int = 0) {
        stop()
        if (audio.frames < 2) return
        val stereo = audio.channelCount >= 2
        val a = if (audio.channelCount > 2) audio.toStereo() else audio
        val data = a.interleaved()
        val channelMask = if (stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(a.rate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 4)
                .build()
        } catch (e: Exception) {
            return
        }
        t.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
        if (loop) t.setLoopPoints(loopStart.coerceIn(0, a.frames - 1), a.frames, -1)
        val start = (startFrac.coerceIn(0.0, 1.0) * a.frames).toInt().coerceIn(0, a.frames - 1)
        if (start > 0) t.playbackHeadPosition = start
        t.play()
        track = t
        _state.value = Playback(id, start.toDouble() / a.frames, a.frames, a.rate, loop)
        ticker = scope.launch {
            val frames = a.frames
            while (isActive) {
                val cur = track ?: break
                if (cur !== t) break
                val pos = runCatching { t.playbackHeadPosition }.getOrDefault(0)
                if (!loop && pos >= frames - 1) {
                    stop()
                    break
                }
                val frac = (pos % frames).toDouble() / frames
                _state.value = _state.value?.copy(position = frac)
                delay(30)
            }
        }
    }

    @Synchronized
    fun stop() {
        ticker?.cancel()
        ticker = null
        track?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            it.release()
        }
        track = null
        _state.value = null
    }

    fun toggle(id: String, audio: () -> Audio?, loop: Boolean = false) {
        if (isPlaying(id)) stop() else audio()?.let { play(id, it, loop) }
    }
}
