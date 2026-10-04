package io.github.pyp6.core.wavetable

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/**
 * Wavetables for the Arturia MiniFreak's Import (firmware 5.0+), the port of
 * the desktop's minifreak_export.py: mono 24-bit WAV of 512-point single
 * cycles, with the Serum-style `clm ` chunk that declares the frame size.
 *
 * Same split as the P-6 table - the frames are shared out evenly between the
 * families, each sweeping its morph across its share - but any total from 1
 * to [MAX_FRAMES], one cycle per frame and every harmonic a 512-point cycle
 * holds: the MiniFreak band limits on playback, so there is no register or
 * upward range. Nothing here touches a pad.
 */
object MiniFreak {
    const val FRAME = 512
    const val MAX_FRAMES = 512
    /**
     * minifreak-converter reports MiniFreak V crashing on tables longer than
     * this. Not published by Arturia, but the default for every export, and
     * going past it is allowed only after a warning.
     */
    const val SAFE_FRAMES = 189
    const val DEFAULT_FRAMES = SAFE_FRAMES
    /** The header rate. A wavetable has none really; 48 kHz is the MiniFreak's own. */
    const val SR = 48000
    /** Every harmonic short of Nyquist. */
    const val HARMONICS = FRAME / 2 - 1
    const val PEAK_DB = -0.3
    /** Pitch the formant families are voiced at when no other is given. */
    const val DEFAULT_NOTE = "C3"

    /** One family's frames: first..last, with what it sounds like at either end. */
    data class Row(val first: Int, val last: Int, val name: String, val startsAs: String, val endsAs: String)

    class Table(val frames: List<DoubleArray>, val rows: List<Row>)

    private fun multiEntry(item: Wavetable.Item): WaveEntry? = when (item) {
        is Wavetable.Item.Custom -> item.entry.takeIf { it.isMulti }
        is Wavetable.Item.Builtin -> Wavetable.catalogue.builtinEntries[Wavetable.canonical(item.name)]?.takeIf { it.isMulti }
    }

    /** [DEFAULT_FRAMES], except that a lone multi family with fewer shapes gets one frame per shape. */
    fun defaultFrames(selection: List<Wavetable.Item>): Int {
        if (selection.size == 1) multiEntry(selection[0])?.let { e ->
            return (e.shapes?.size ?: 0).coerceIn(1, DEFAULT_FRAMES)
        }
        return DEFAULT_FRAMES
    }

    fun build(selection: List<Wavetable.Item>, frames: Int? = null, note: String = DEFAULT_NOTE,
              peakDb: Double = PEAK_DB): Table {
        require(selection.isNotEmpty()) { "nothing selected" }
        val n = frames ?: defaultFrames(selection)
        require(n in 1..MAX_FRAMES) { "frames must be 1..$MAX_FRAMES, got $n" }
        require(n >= selection.size) { "${selection.size} families need at least as many frames, got $n" }
        val f0 = Wavetable.midiToHz(Wavetable.nameToMidi(note) ?: Wavetable.nameToMidi(DEFAULT_NOTE)!!)
        val s = WtSynth(FRAME, 1, HARMONICS)
        val peak = 10.0.pow(peakDb / 20.0)

        val counts = Wavetable.splitSteps(selection.size, n)
        val out = ArrayList<DoubleArray>(n)
        val rows = ArrayList<Row>()
        for ((item, count) in selection.zip(counts)) {
            val start = out.size
            var first = ""
            var last = ""
            val multi = multiEntry(item)
            if (multi != null) {
                // Picked directly: the family's own render indexes against the
                // P-6's 255 segments and would sample the wrong shapes here.
                val shapes = multi.shapes!!
                val labels = multi.labels ?: emptyList()
                for (j in 0 until count) {
                    val k = if (count == 1) 0 else Math.rint(j.toDouble() * (shapes.size - 1) / (count - 1)).toInt()
                    out.add(Wavetable.pointsToCycle(shapes[k], s))
                    val desc = labels.getOrNull(k) ?: "${k + 1}"
                    if (first.isEmpty()) first = desc
                    last = desc
                }
            } else {
                val fn = Wavetable.renderFor(item)
                for (j in 0 until count) {
                    val m = if (count == 1) 0.5 else j.toDouble() / (count - 1)
                    val (w, desc) = fn.render(s, m, f0)
                    out.add(s.bandLimit(w))
                    if (first.isEmpty()) first = desc
                    last = desc
                }
            }
            rows.add(Row(start, start + count - 1, item.name, first, last))
        }
        // Per frame, as the P-6 build does: scanning changes the timbre, not the level.
        for (w in out) {
            var pk = 0.0
            for (v in w) pk = maxOf(pk, abs(v))
            val g = if (pk > 1e-12) peak / pk else peak
            for (i in w.indices) w[i] *= g
        }
        return Table(out, rows)
    }

    /** 24-bit little-endian frames, as numpy's round-then-clip writes them. */
    private fun int24(frames: List<DoubleArray>): ByteArray {
        val bytes = ByteArray(frames.size * FRAME * 3)
        var p = 0
        for (w in frames) for (v in w) {
            val q = Math.rint(v * 8388608.0).coerceIn(-8388608.0, 8388607.0).toInt()
            bytes[p++] = q.toByte(); bytes[p++] = (q shr 8).toByte(); bytes[p++] = (q shr 16).toByte()
        }
        return bytes
    }

    /** The WAV the MiniFreak imports: mono, 24-bit PCM, with a `clm ` chunk declaring 512-point frames. */
    fun wav(table: Table): ByteArray {
        val pcm = int24(table.frames)
        var clm = "<!>$FRAME 00000000 wavetable Pad6".toByteArray(Charsets.US_ASCII)
        if (clm.size % 2 == 1) clm += 0
        fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(1).putInt(SR).putInt(SR * 3).putShort(3).putShort(24).array()
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray())
        body.write("fmt ".toByteArray()); body.write(le32(fmt.size)); body.write(fmt)
        body.write("clm ".toByteArray()); body.write(le32(clm.size)); body.write(clm)
        body.write("data".toByteArray()); body.write(le32(pcm.size)); body.write(pcm)
        if (pcm.size % 2 == 1) body.write(0)
        val b = body.toByteArray()
        return "RIFF".toByteArray() + le32(b.size) + b
    }

    /** Which frames hold which family - the MiniFreak shows a position, not a name. */
    fun map(table: Table, note: String): String {
        val n = table.frames.size
        val sb = StringBuilder()
        sb.append("Pad6 MiniFreak wavetable - $n frames x $FRAME points\n")
        sb.append("Formant reference pitch: $note\n\n")
        sb.append("frames      position  family                     starts as -> ends as\n")
        for (r in table.rows) {
            val pos = if (n > 1) r.first.toDouble() / (n - 1) * 100 else 0.0
            val span = if (r.startsAs == r.endsAs) r.startsAs else "${r.startsAs} -> ${r.endsAs}"
            sb.append(String.format(Locale.ROOT, "%3d-%-3d     %5.1f%%    %-26s %s\n", r.first, r.last, pos, r.name, span))
        }
        return sb.toString()
    }

    /** A short FAT/Windows-safe file name. */
    fun fileName(label: String): String {
        val cleaned = label.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]+"), "-")
            .replace(Regex("\\s+"), " ").trim(' ', '.', '-')
        return cleaned.ifEmpty { "Wavetable" }.take(40)
    }
}
