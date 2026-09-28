package io.github.pyp6.app.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import io.github.pyp6.core.audio.Audio
import io.github.pyp6.core.audio.Wav
import io.github.pyp6.core.model.SampleStore
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Brings audio into the app. WAV files (any bit depth, float included) are
 * read directly; everything else Android can decode - MP3, AAC/M4A, FLAC,
 * OGG, Opus - goes through MediaCodec. What comes out is always a 16-bit WAV
 * in the app's sample folder, the one format the P-6 accepts. This replaces
 * the desktop app's ffmpeg dependency.
 */
class AudioImporter(private val context: Context, private val store: SampleStore) {

    fun displayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "sample"
    }

    /** Decodes [uri] to memory. */
    fun decode(uri: Uri): Audio {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { s -> return Wav.read(s.buffered()) }
        }
        return decodeWithMediaCodec(uri)
    }

    /**
     * Imports [uri] as a new sample file. Returns the file and whether it had
     * to be converted. A 16-bit WAV is copied byte for byte.
     */
    fun import(uri: Uri, tag: String = "imp"): Pair<File, Boolean> {
        val name = displayName(uri)
        val isWav = runCatching {
            context.contentResolver.openInputStream(uri)?.use { Wav.readInfo(it.buffered()) }
        }.getOrNull()
        if (isWav != null && isWav.isPcm16) {
            val out = store.newFile(name, tag)
            context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
            return out to false
        }
        val audio = decode(uri)
        if (audio.frames == 0) throw IOException("$name contains no audio")
        return store.write(audio, name, tag) to true
    }

    private fun decodeWithMediaCodec(uri: Uri): Audio {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) throw IOException("no audio track found")
            ex.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val out = FloatArrayBuilder()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = ex.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        outIdx >= 0 -> {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val b = buf.slice().order(ByteOrder.nativeOrder())
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val fb = b.asFloatBuffer()
                                while (fb.hasRemaining()) out.add(fb.get())
                            } else {
                                val sb = b.asShortBuffer()
                                while (sb.hasRemaining()) out.add(sb.get() / 32768f)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
            val interleaved = out.toArray()
            val ch = channels.coerceAtLeast(1)
            val frames = interleaved.size / ch
            val planes = List(ch) { c -> FloatArray(frames) { interleaved[it * ch + c] } }
            return Audio(rate, planes)
        } finally {
            ex.release()
        }
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(1 shl 16)
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
