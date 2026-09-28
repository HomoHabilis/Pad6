package io.github.pyp6.core.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** What a WAV header says, without reading the audio. */
data class WavInfo(
    val rate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val formatTag: Int,
    val frames: Int,
    /** Frame size a Serum-style `clm ` chunk declares, if any. */
    val clmFrameSize: Int? = null,
) {
    val durationSeconds: Double get() = if (rate > 0) frames.toDouble() / rate else 0.0
    val isPcm16: Boolean get() = formatTag == Wav.FORMAT_PCM && bitsPerSample == 16
}

class WavFormatException(message: String) : IOException(message)

/**
 * RIFF WAVE reading and writing.
 *
 * Reads what the desktop app reads through soundfile: integer PCM at 8, 16,
 * 24 and 32 bits, IEEE float at 32 and 64 bits, and the EXTENSIBLE wrapper
 * around either. Writes 16-bit PCM only, because that is the one format the
 * P-6 accepts.
 */
object Wav {
    const val FORMAT_PCM = 1
    const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    fun readInfo(file: File): WavInfo = file.inputStream().buffered().use { readInfo(it) }

    fun readInfo(input: InputStream): WavInfo = parse(input, readData = false).first

    fun read(file: File): Audio = file.inputStream().buffered().use { read(it) }

    fun read(input: InputStream): Audio = parse(input, readData = true).second!!

    fun isWav(file: File): Boolean = try {
        file.inputStream().use { s ->
            val h = ByteArray(12)
            s.read(h) == 12 && String(h, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(h, 8, 4, Charsets.US_ASCII) == "WAVE"
        }
    } catch (_: IOException) {
        false
    }

    private fun parse(input: InputStream, readData: Boolean): Pair<WavInfo, Audio?> {
        val head = input.readNBytesCompat(12)
        if (head.size < 12 || String(head, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(head, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) throw WavFormatException("not a RIFF WAVE file")

        var tag = -1
        var channels = 0
        var rate = 0
        var bits = 0
        var blockAlign = 0
        var clm: Int? = null
        while (true) {
            val hdr = input.readNBytesCompat(8)
            if (hdr.size < 8) throw WavFormatException("no data chunk found")
            val id = String(hdr, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            when (id) {
                "fmt " -> {
                    val fmt = input.readNBytesCompat(size.toInt())
                    val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    tag = b.getShort(0).toInt() and 0xFFFF
                    channels = b.getShort(2).toInt() and 0xFFFF
                    rate = b.getInt(4)
                    blockAlign = b.getShort(12).toInt() and 0xFFFF
                    bits = b.getShort(14).toInt() and 0xFFFF
                    if (tag == FORMAT_EXTENSIBLE && fmt.size >= 26) {
                        tag = b.getShort(24).toInt() and 0xFFFF
                    }
                    if (size and 1L == 1L) input.skipFully(1)
                }
                "clm " -> {
                    val body = String(input.readNBytesCompat(size.toInt()), Charsets.ISO_8859_1)
                    clm = parseClm(body)
                    if (size and 1L == 1L) input.skipFully(1)
                }
                "data" -> {
                    if (tag < 0) throw WavFormatException("data chunk before fmt chunk")
                    if (channels <= 0 || bits <= 0) throw WavFormatException("invalid fmt chunk")
                    val bytesPerFrame = if (blockAlign > 0) blockAlign else channels * ((bits + 7) / 8)
                    if (!readData) {
                        // A header that claims 0 or a huge size (streamed
                        // recordings) is reported as it stands; readers of the
                        // info only use it for display and limits.
                        val frames = (size / bytesPerFrame).toInt()
                        return WavInfo(rate, channels, bits, tag, frames, clm) to null
                    }
                    val raw = readDataBytes(input, size)
                    val frames = raw.size / bytesPerFrame
                    val audio = decode(raw, frames, channels, bits, tag, bytesPerFrame, rate)
                    return WavInfo(rate, channels, bits, tag, frames, clm) to audio
                }
                else -> input.skipFully(size + (size and 1L))
            }
        }
    }

    private fun readDataBytes(input: InputStream, size: Long): ByteArray {
        // Some writers leave 0 or 0xFFFFFFFF in a streamed file's header;
        // reading to the end of the stream recovers those.
        if (size == 0L || size >= 0xFFFFFFF0L || size > Int.MAX_VALUE) {
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            return out.toByteArray()
        }
        return input.readNBytesCompat(size.toInt())
    }

    private fun decode(
        raw: ByteArray, frames: Int, channels: Int, bits: Int, tag: Int,
        bytesPerFrame: Int, rate: Int,
    ): Audio {
        val out = List(channels) { FloatArray(frames) }
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val width = bits / 8
        for (i in 0 until frames) {
            val base = i * bytesPerFrame
            for (ch in 0 until channels) {
                val p = base + ch * width
                out[ch][i] = when {
                    tag == FORMAT_FLOAT && bits == 32 -> b.getFloat(p)
                    tag == FORMAT_FLOAT && bits == 64 -> b.getDouble(p).toFloat()
                    tag == FORMAT_PCM && bits == 16 -> b.getShort(p) / 32768f
                    tag == FORMAT_PCM && bits == 8 -> ((raw[p].toInt() and 0xFF) - 128) / 128f
                    tag == FORMAT_PCM && bits == 24 -> {
                        val v = (raw[p].toInt() and 0xFF) or ((raw[p + 1].toInt() and 0xFF) shl 8) or
                            (raw[p + 2].toInt() shl 16)
                        v / 8388608f
                    }
                    tag == FORMAT_PCM && bits == 32 -> (b.getInt(p) / 2147483648.0).toFloat()
                    else -> throw WavFormatException("unsupported WAV format (tag $tag, $bits-bit)")
                }
            }
        }
        return Audio(rate, out)
    }

    /**
     * `<!>2048 10000000 wavetable (www.xferrecords.com)` -> 2048. The number
     * ends at the first non-digit; reading every digit in the field would run
     * the frame size into the flags after it.
     */
    internal fun parseClm(body: String): Int? {
        val tail = body.substringAfterLast("<!>").trim()
        val digits = tail.takeWhile { it.isDigit() }
        val n = digits.toIntOrNull() ?: return null
        return if (n in 16..65536) n else null
    }

    /** Encodes [audio] as 16-bit PCM WAV bytes. */
    fun encodePcm16(audio: Audio): ByteArray {
        val ch = audio.channelCount
        val frames = audio.frames
        val dataBytes = frames * ch * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(FORMAT_PCM.toShort())
        buf.putShort(ch.toShort())
        buf.putInt(audio.rate)
        buf.putInt(audio.rate * ch * 2)
        buf.putShort((ch * 2).toShort())
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes)
        for (i in 0 until frames) {
            for (c in 0 until ch) buf.putShort(toPcm16(audio.channels[c][i]))
        }
        return buf.array()
    }

    fun writePcm16(audio: Audio, output: OutputStream) {
        output.write(encodePcm16(audio))
    }

    fun writePcm16(audio: Audio, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { writePcm16(audio, it) }
    }

    /** Writes already-quantised mono 16-bit samples (the wavetable path). */
    fun writePcm16Mono(samples: ShortArray, rate: Int, file: File) {
        val dataBytes = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII)); buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII)); buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16); buf.putShort(1); buf.putShort(1); buf.putInt(rate); buf.putInt(rate * 2)
        buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII)); buf.putInt(dataBytes)
        for (s in samples) buf.putShort(s)
        file.parentFile?.mkdirs()
        file.writeBytes(buf.array())
    }

    /** libsndfile's float -> PCM16 conversion: scale by 32767, round, clip. */
    fun toPcm16(v: Float): Short {
        val s = (v * 32767f).roundToInt()
        return s.coerceIn(-32768, 32767).toShort()
    }
}

internal fun InputStream.readNBytesCompat(n: Int): ByteArray {
    val out = ByteArray(n)
    var read = 0
    while (read < n) {
        val r = read(out, read, n - read)
        if (r < 0) break
        read += r
    }
    return if (read == n) out else out.copyOf(read)
}

internal fun InputStream.skipFully(n: Long) {
    var left = n
    while (left > 0) {
        val s = skip(left)
        if (s <= 0) {
            if (read() < 0) return
            left -= 1
        } else left -= s
    }
}
