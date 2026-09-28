package io.github.pyp6.core.wavetable

import io.github.pyp6.core.dsp.Fft
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A waveform family the user drew, imported, or that ships in a collection:
 * either a morph from shape [a] to shape [b] ("draw"), or up to 255 shapes
 * played one per table step ("multi"). The same dict shape the desktop app
 * stores in waveforms.json, presets and .p6wf packs.
 */
@Serializable
data class WaveEntry(
    val kind: String = "draw",
    val name: String,
    val group: String? = null,
    val a: List<Double>? = null,
    val b: List<Double>? = null,
    val shapes: List<List<Double>>? = null,
    val labels: List<String>? = null,
) {
    val isMulti: Boolean get() = kind == "multi" && !shapes.isNullOrEmpty()
    val isUsable: Boolean get() = !a.isNullOrEmpty() || isMulti
}

/** Register: base MIDI note, cycles per segment, default upward range. */
data class WtRegister(val name: String, val baseMidi: Int, val cycles: Int, val defaultUp: Int)

/** What wt_build records about a table (the pad keeps it as "meta"). */
@Serializable
data class WtMeta(
    val L: Int,
    val cycles: Int,
    val f_real: Double,
    val cents: Double,
    val h: Int,
    val h_max: Int,
    val total_frames: Int,
    val seconds: Double,
    val step_ms: Double,
    val top_hz: Double,
    val counts: List<Int>,
    val families: List<String>,
)

class WtBuildResult(val pcm: ShortArray, val rows: List<WtRow>, val meta: WtMeta)

data class WtRow(val step: Int, val family: String, val morph: Double, val description: String, val frame: Int)

object Wavetable {
    const val SR = 44100
    const val SEGMENTS = 255
    const val MAX_SECONDS = 5.9
    val MAX_SEG_FRAMES = (MAX_SECONDS * SR).toInt() / SEGMENTS   // 1020
    const val PEAK = 0.9
    const val PREVIEW_SECONDS = 5.0
    /** The P-6 transposes a sample at most three octaves above its own pitch. */
    const val DEVICE_MAX_UP = 36
    const val DRAW_POINTS = 512
    const val MAX_SELECTED = 16
    const val MULTI_MAX = SEGMENTS
    const val CYCLE_SANE_MAX = 8192
    const val DEFAULT_USER_GROUP = "My Waveforms"
    const val AUDITION_REPEATS = 2

    val REGISTERS = listOf(
        WtRegister("Bass", 36, 1, 0),
        WtRegister("Mid", 48, 2, 12),
        WtRegister("Lead", 48, 2, 24),
    )

    fun register(name: String): WtRegister = REGISTERS.firstOrNull { it.name == name } ?: REGISTERS[0]

    private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun midiToHz(m: Int, a4: Double = 440.0) = a4 * 2.0.pow((m - 69) / 12.0)
    fun midiToName(m: Int) = "${NOTE_NAMES[Math.floorMod(m, 12)]}${Math.floorDiv(m, 12) - 1}"
    fun nameToMidi(name: String): Int? = (0 until 128).firstOrNull { midiToName(it) == name }

    /** Notes offered for a register: six semitones either side of its base. */
    fun notesFor(register: WtRegister): List<String> = (-6..6).map { midiToName(register.baseMidi + it) }

    /** (segment length, real frequency, tuning error in cents). */
    fun tuningInfo(midi: Int, cycles: Int, a4: Double = 440.0): Triple<Int, Double, Double> {
        val ideal = midiToHz(midi, a4)
        val L = Math.rint(cycles * SR / ideal).toInt()
        val real = SR.toDouble() * cycles / L
        return Triple(L, real, 1200.0 * log2(real / ideal))
    }

    fun harmonicsFor(L: Int, cycles: Int, upSemitones: Int): Pair<Int, Int> {
        val hMax = L / (2 * cycles)
        return max(4, min(hMax, (hMax / 2.0.pow(upSemitones / 12.0)).toInt())) to hMax
    }

    fun playableRange(L: Int, cycles: Int, upSemitones: Int): Int {
        val (h, hMax) = harmonicsFor(L, cycles, upSemitones)
        if (h >= hMax || h <= 0) return 0
        return floor(12.0 * log2(hMax / h.toDouble())).toInt()
    }

    fun splitSteps(nFamilies: Int, total: Int = SEGMENTS): List<Int> {
        if (nFamilies <= 0) return emptyList()
        val base = total / nFamilies
        val rem = total % nFamilies
        return (0 until nFamilies).map { base + if (it < rem) 1 else 0 }
    }

    /** Aliases for families renamed when the collections became folders. */
    private val ALIASES: Map<String, String> = (1..2).flatMap { vol ->
        (1..16).map { k ->
            val kk = k.toString().padStart(2, '0')
            "Vintage Collection $vol - No $kk" to "VC$vol - No $kk"
        }
    }.toMap()

    fun canonical(name: String) = ALIASES[name] ?: name

    // ------------------------------------------------------------ catalogue

    class Catalogue(
        val families: LinkedHashMap<String, WtRender>,
        val groups: List<Pair<String, List<String>>>,
        val builtinMulti: Set<String>,
        val builtinEntries: Map<String, WaveEntry>,
    )

    val SIMPLE_FAMILIES = listOf(
        "Saw", "Pulse / PWM", "Triangle", "Sine",
        "Wave Folder", "Hard Sync", "FM", "Phase Dist.",
        "Staircase", "Vowel Formant", "Organ", "Piano",
        "Strings", "Brass", "Bell / Metal", "Noise Morph",
    )

    const val HOT_MW_BIG_NAME = "Hot MW 1 Big 255WF"

    private fun readInt16(path: String): DoubleArray {
        val bytes = Wavetable::class.java.getResourceAsStream(path)!!.use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return DoubleArray(bb.remaining()) { bb.get(it) / 32767.0 }
    }

    val catalogue: Catalogue by lazy {
        val fams = LinkedHashMap<String, WtRender>()
        BasicFamilies.ALL.forEach { (n, f) -> fams[n] = f }
        val groups = ArrayList<Pair<String, List<String>>>()
        groups.add("Basic" to BasicFamilies.ALL.map { it.first })
        val entries = HashMap<String, WaveEntry>()
        val multi = HashSet<String>()

        runCatching {
            val q = readInt16("/pyp6/vintage_wt.i16")
            val pts = 512
            for (vol in 0 until 2) {
                val names = ArrayList<String>()
                for (k in 0 until 16) {
                    val name = "VC${vol + 1} - No ${(k + 1).toString().padStart(2, '0')}"
                    val base = ((vol * 16 + k) * 2) * pts
                    val e = WaveEntry("draw", name, "Vintage Collection ${vol + 1}",
                        q.copyOfRange(base, base + pts).toList(), q.copyOfRange(base + pts, base + 2 * pts).toList())
                    fams[name] = drawnFamily(e); entries[name] = e; names.add(name)
                }
                groups.add("Vintage Collection ${vol + 1}" to names)
            }
        }
        runCatching {
            val q = readInt16("/pyp6/hot_mw_wt.i16")
            val pts = 512
            val count = 64
            for (col in 0 until 2) {
                val names = ArrayList<String>()
                if (col == 0) {
                    runCatching {
                        val big = readInt16("/pyp6/hot_mw_big.i16")
                        val labels = Wavetable::class.java.getResourceAsStream("/pyp6/hot_mw_big_labels.txt")!!
                            .use { String(it.readBytes(), Charsets.UTF_8) }.split("\n")
                        val shapes = (0 until 255).map { big.copyOfRange(it * pts, (it + 1) * pts).toList() }
                        val e = WaveEntry("multi", HOT_MW_BIG_NAME, "Hot Microwaves Collection 1", shapes = shapes, labels = labels)
                        fams[e.name] = multiFamily(e); entries[e.name] = e; names.add(e.name); multi.add(e.name)
                    }
                }
                for (k in 0 until count - 1) {
                    val name = "Hot MW ${col + 1} SWP ${(k + 1).toString().padStart(2, '0')}"
                    val a0 = (col * count + k) * pts
                    val b0 = (col * count + k + 1) * pts
                    val e = WaveEntry("draw", name, "Hot Microwaves Collection ${col + 1}",
                        q.copyOfRange(a0, a0 + pts).toList(), q.copyOfRange(b0, b0 + pts).toList())
                    fams[name] = drawnFamily(e); entries[name] = e; names.add(name)
                }
                groups.add("Hot Microwaves Collection ${col + 1}" to names)
            }
        }
        Catalogue(fams, groups, multi, entries)
    }

    /** What Simple mode can build from: (label, family names). */
    fun simpleSets(): List<Pair<String, List<String>>> {
        val c = catalogue
        val out = ArrayList<Pair<String, List<String>>>()
        out.add("Basic" to SIMPLE_FAMILIES)
        for ((label, names) in c.groups) {
            if (label == "Basic") continue
            val plain = names.filter { it !in c.builtinMulti }.take(MAX_SELECTED)
            if (plain.isNotEmpty()) out.add(label to plain)
        }
        for ((_, names) in c.groups) for (n in names) if (n in c.builtinMulti) out.add(n to listOf(n))
        return out
    }

    const val RANDOMIZE = "Randomize"

    fun randomFamilies(random: kotlin.random.Random = kotlin.random.Random.Default): List<String> {
        val pool = catalogue.groups.flatMap { it.second }.distinct().filter { it !in catalogue.builtinMulti }
        return pool.shuffled(random).take(min(MAX_SELECTED, pool.size))
    }

    private val BLURBS = mapOf(
        "Basic" to "The plain oscillator shapes - saw, square, pulse, triangle - and morphs between them.\n\n" +
            "Neutral and predictable. The set to start from when you want the pad to sound like an oscillator rather than a character.",
        "Vintage Collection 1" to "Fat analogue shapes in the Moog tradition: rounded saws, hollow pulses, slow sweeps between them.\n\n" +
            "Warm and thick, at home in basses and leads.",
        "Vintage Collection 2" to "More of the same tradition, further from the textbook: reedier pulses, harder edges, wider morphs.\n\n" +
            "Use it where Collection 1 sounds too polite.",
        "Hot Microwaves Collection 1" to "Digital wavetables after the Waldorf Microwave: metallic, restless, full of formant movement.\n\n" +
            "A continuous sweep - put several neighbours in the step order and the table runs through without a seam.",
        "Hot Microwaves Collection 2" to "The second Microwave sweep. Brighter and more vocal than the first, with sharper steps between shapes.\n\n" +
            "Also continuous, so neighbouring families join up.",
        RANDOMIZE to "Sixteen morphing families drawn at random from every collection.\n\n" +
            "Rolled fresh each time you pick it. Multi families are left out; each needs the whole table to itself.",
    )

    fun blurb(label: String, nFamilies: Int, multi: Boolean): String = when {
        multi -> "$MULTI_MAX waveforms in one family, one per step.\n\nNo morphing: START picks a waveform outright " +
            "instead of sweeping between two. It fills the table on its own, so nothing else goes in the step order."
        else -> BLURBS[label] ?: "$nFamilies families, built to be used together.\n\nPick the set and the first sixteen go into the step order."
    }

    // ---------------------------------------------------- drawn / multi

    /**
     * A drawn polyline as a band-limited segment for [s]: through the
     * harmonic domain, so it cannot alias, carries no DC, and loops seamlessly.
     */
    fun pointsToCycle(points: List<Double>, s: WtSynth): DoubleArray {
        val size = points.size
        if (size < 4) return DoubleArray(s.L)
        val (re, im) = Fft.rfft(points.toDoubleArray())
        val usable = minOf(s.h, size / 2, re.size - 1)
        if (usable < 1) return DoubleArray(s.L)
        val w = DoubleArray(s.L)
        for (k in 1..usable) {
            val r = re[k] / size
            val i = im[k] / size
            val amp = 2.0 * sqrt(r * r + i * i)
            val ph = atan2(i, r)
            val ca = amp * cos(ph)
            val sa = amp * sin(ph)
            val rc = s.cosB[k - 1]
            val rs = s.sinB[k - 1]
            for (j in 0 until s.L) w[j] += ca * rc[j] - sa * rs[j]
        }
        return WtSynth.normalizePeak(w)
    }

    fun drawnFamily(entry: WaveEntry): WtRender {
        val pa = entry.a ?: emptyList()
        val pb = entry.b?.takeIf { it.isNotEmpty() } ?: pa
        val name = entry.name
        var key: Triple<Int, Int, Int>? = null
        var cached: Pair<DoubleArray, DoubleArray>? = null
        return WtRender { s, m, _ ->
            val kk = Triple(s.L, s.R, s.h)
            val c = synchronized(this) {
                if (key != kk || cached == null) {
                    cached = pointsToCycle(pa, s) to pointsToCycle(pb, s)
                    key = kk
                }
                cached!!
            }
            DoubleArray(s.L) { c.first[it] * (1.0 - m) + c.second[it] * m } to
                "$name ${String.format(java.util.Locale.ROOT, "%.2f", m)}"
        }
    }

    fun multiFamily(entry: WaveEntry): WtRender {
        val shapes = entry.shapes ?: emptyList()
        val labels = entry.labels ?: emptyList()
        val name = entry.name
        var key: Triple<Int, Int, Int>? = null
        var cycles: List<DoubleArray> = emptyList()
        return WtRender { s, m, _ ->
            val kk = Triple(s.L, s.R, s.h)
            val cs = synchronized(this) {
                if (key != kk) {
                    cycles = shapes.map { pointsToCycle(it, s) }
                    key = kk
                }
                cycles
            }
            val idx = Math.rint(m * (SEGMENTS - 1)).toInt()
            if (idx in cs.indices) {
                val label = if (idx < labels.size) labels[idx] else "${idx + 1}"
                cs[idx] to "$name [${idx + 1}] $label"
            } else DoubleArray(s.L) to "$name [${idx + 1}] silent"
        }
    }

    /** A step-order item: a built-in family name, or a user entry. */
    sealed class Item {
        abstract val name: String
        data class Builtin(override val name: String) : Item()
        data class Custom(val entry: WaveEntry) : Item() {
            override val name: String get() = entry.name
        }
    }

    fun renderFor(item: Item): WtRender = when (item) {
        is Item.Builtin -> catalogue.families[canonical(item.name)]
            ?: throw IllegalArgumentException("unknown waveform family ${item.name}")
        is Item.Custom -> if (item.entry.isMulti) multiFamily(item.entry) else drawnFamily(item.entry)
    }

    /** Builds the 255-segment table. Throws when the root note is too low. */
    fun build(selection: List<Item>, midi: Int, cycles: Int, upSemitones: Int, progress: ((Double) -> Unit)? = null): WtBuildResult {
        val (L, fReal, cents) = tuningInfo(midi, cycles)
        require(L <= MAX_SEG_FRAMES) {
            "Segment would be $L frames, maximum is $MAX_SEG_FRAMES ($MAX_SECONDS s / $SEGMENTS). Pick a higher root note."
        }
        val (h, hMax) = harmonicsFor(L, cycles, upSemitones)
        val s = WtSynth(L, cycles, h)
        val counts = splitSteps(selection.size)
        val pcm = ShortArray(L * counts.sum())
        val rows = ArrayList<WtRow>()
        var step = 0
        val totalSteps = max(1, counts.sum())
        for ((item, count) in selection.zip(counts)) {
            val fn = renderFor(item)
            for (j in 0 until count) {
                val m = if (count == 1) 0.5 else j.toDouble() / (count - 1)
                val (w0, desc) = fn.render(s, m, fReal)
                val w = WtSynth.normalizePeak(s.bandLimit(w0))
                for (i in 0 until L) {
                    val v = (w[i] * PEAK).coerceIn(-1.0, 1.0) * 32767.0
                    pcm[step * L + i] = v.toInt().toShort()      // astype("<i2") truncates
                }
                rows.add(WtRow(step, item.name, m, desc, step * L))
                step++
            }
            progress?.invoke(step.toDouble() / totalSteps)
        }
        val meta = WtMeta(
            L = L, cycles = cycles, f_real = fReal, cents = cents, h = h, h_max = hMax,
            total_frames = pcm.size, seconds = pcm.size.toDouble() / SR,
            step_ms = L.toDouble() / SR * 1000.0, top_hz = h * fReal,
            counts = counts, families = selection.map { it.name },
        )
        return WtBuildResult(pcm, rows, meta)
    }

    /** A morph sweep through one family at the configured pitch, for auditioning. */
    fun renderSweep(item: Item, midi: Int, cycles: Int, upSemitones: Int, steps: Int, seconds: Double = PREVIEW_SECONDS): FloatArray {
        val (L, fReal, _) = tuningInfo(midi, cycles)
        val (h, _) = harmonicsFor(L, cycles, upSemitones)
        val lp = max(16, Math.rint(SR / fReal).toInt())
        val s = WtSynth(lp, 1, max(1, min(h, lp / 2)))
        val fn = renderFor(item)
        val st = max(1, steps)
        val tabs = Array(st) { j ->
            val m = if (st == 1) 0.5 else j.toDouble() / (st - 1)
            WtSynth.normalizePeak(s.bandLimit(fn.render(s, m, fReal).first))
        }
        val n = (seconds * SR).toInt()
        val out = FloatArray(n)
        val ramp = (0.015 * SR).toInt()
        for (t in 0 until n) {
            val v = if (st == 1) tabs[0][t % lp] else {
                val tp = t * ((st - 1).toDouble() / (n - 1))
                val i0 = floor(tp).toInt().coerceIn(0, st - 1)
                val i1 = (i0 + 1).coerceIn(0, st - 1)
                val fr = tp - i0
                tabs[i0][t % lp] * (1 - fr) + tabs[i1][t % lp] * fr
            }
            var env = 1.0
            if (t < ramp) env = t.toDouble() / (ramp - 1)
            if (t >= n - ramp) env = (n - 1 - t).toDouble() / (ramp - 1)
            out[t] = (v * env * PEAK).toFloat()
        }
        return out
    }

    /** The shapes a family moves through, for the morph display. */
    fun morphShapes(item: Item, midi: Int, cycles: Int, upSemitones: Int, count: Int, points: Int = 256): List<DoubleArray> {
        val (L, fReal, _) = tuningInfo(midi, cycles)
        val (h, _) = harmonicsFor(L, cycles, upSemitones)
        val lp = min(points, 1024)
        val s = WtSynth(lp, 1, max(1, min(h, lp / 2)))
        val fn = renderFor(item)
        return (0 until count).map { j ->
            val m = if (count == 1) 0.5 else j.toDouble() / (count - 1)
            WtSynth.normalizePeak(s.bandLimit(fn.render(s, m, fReal).first))
        }
    }

    /** Two short lines describing a wavetable pad. */
    fun summary(register: String, note: String, up: Int, meta: WtMeta): List<String> {
        val counts = meta.counts
        val per = when {
            counts.isEmpty() -> "-"
            counts.toSet().size == 1 -> counts[0].toString()
            else -> "${counts.min()}-${counts.max()}"
        }
        val dot = " · "
        val span = if (up > 0) "$dot+$up st" else ""
        val f = String.format(java.util.Locale.ROOT, "%.2f", meta.f_real)
        val n = meta.families.size
        return listOf(
            "$register$dot$note$dot$f Hz$span",
            "$n famil${if (n == 1) "y" else "ies"}$dot~$per steps",
        )
    }

    /** Zones: (first START position, last, family) for the zone strip. */
    fun zones(meta: WtMeta): List<Triple<Int, Int, String>> {
        val out = ArrayList<Triple<Int, Int, String>>()
        var start = 0
        for ((name, count) in meta.families.zip(meta.counts)) {
            if (count <= 0) continue
            out.add(Triple(start, start + count - 1, name))
            start += count
        }
        return out
    }

    // ---------------------------------------------------- cycle import

    /** One cycle resampled to [points] values in the frequency domain (no aliasing). */
    fun resampleCycle(values: DoubleArray, points: Int = DRAW_POINTS): DoubleArray {
        if (values.size == points) return values.copyOf()
        if (values.size < 2) return DoubleArray(points)
        val (re, im) = Fft.rfft(values)
        val keep = min(re.size, points / 2 + 1)
        val outRe = DoubleArray(points / 2 + 1)
        val outIm = DoubleArray(points / 2 + 1)
        for (i in 0 until keep) { outRe[i] = re[i]; outIm[i] = im[i] }
        val y = Fft.irfft(outRe, outIm, points)
        val scale = points / values.size.toDouble()
        for (i in y.indices) y[i] *= scale
        return y
    }

    private val FRAME_CANDIDATES = intArrayOf(64, 128, 256, 512, 1024, 2048, 4096)

    /** How many samples one cycle of a concatenated wavetable takes, or null. */
    fun detectFrameSize(data: DoubleArray, declared: Int? = null): Int? {
        val n = data.size
        if (declared != null && declared > 0 && n % declared == 0 && n / declared >= 2) return declared
        for (L in FRAME_CANDIDATES) {
            if (n % L != 0 || n / L < 2) continue
            val rows = n / L
            var rmsSum = 0.0
            var dcSum = 0.0
            var wrapSum = 0.0
            for (r in 0 until rows) {
                var sq = 0.0
                var sum = 0.0
                for (i in 0 until L) {
                    val v = data[r * L + i]
                    sq += v * v; sum += v
                }
                rmsSum += sqrt(sq / L)
                dcSum += abs(sum / L)
                wrapSum += abs(data[r * L] - data[r * L + L - 1])
            }
            val rms = rmsSum / rows + 1e-12
            val dc = dcSum / rows / rms
            val wrap = wrapSum / rows / rms
            if (dc < 0.05 && wrap < 0.35) return L
        }
        return null
    }

    private fun round4(v: Double) = Math.rint(v * 10000) / 10000

    /** A single-cycle file's samples as a normalised shape of [points] values. */
    fun cycleShape(data: DoubleArray, points: Int = DRAW_POINTS): List<Double> {
        require(data.size >= 4) { "file is too short to be a waveform cycle" }
        var peak = 0.0
        for (v in data) peak = max(peak, abs(v))
        require(peak >= 1e-9) { "file is silent" }
        val res = WtSynth.normalizePeak(resampleCycle(data, points))
        return res.map { round4(it) }
    }

    /** A concatenated wavetable cut into its frames, each a normalised shape. */
    fun splitTable(data: DoubleArray, declared: Int?, points: Int = DRAW_POINTS): Pair<List<List<Double>>, Int> {
        val L = detectFrameSize(data, declared) ?: throw IllegalArgumentException("no repeating cycle length could be found")
        val n = data.size / L
        val shapes = (0 until n).map { k ->
            WtSynth.normalizePeak(resampleCycle(data.copyOfRange(k * L, (k + 1) * L), points)).map { round4(it) }
        }
        return shapes to L
    }

    /**
     * DC-free, phase-aligned copy of one cycle, so the morph between two
     * shapes slides instead of cancelling halfway. [ref] refines it against
     * the previous cycle by circular correlation (within a quarter cycle).
     */
    fun alignPoints(values: List<Double>, ref: List<Double>? = null): List<Double> {
        var v = values.toDoubleArray()
        if (v.size < 4) return values
        val mean = v.average()
        for (i in v.indices) v[i] -= mean
        val (re, im) = Fft.rfft(v)
        val n = v.size
        if (sqrt(re[1] * re[1] + im[1] * im[1]) > 1e-12) {
            val shift = (atan2(im[1], re[1]) + PI / 2) / (2 * PI) * n
            for (k in re.indices) {
                val ang = 2 * PI * k * shift / n
                val c = cos(ang)
                val s = sin(ang)
                val r = re[k] * c - im[k] * s
                val i = re[k] * s + im[k] * c
                re[k] = r; im[k] = i
            }
            v = Fft.irfft(re, im, n)
        }
        if (ref != null && ref.size == n) {
            val (a1, a2) = Fft.rfft(v)
            val (b1, b2) = Fft.rfft(ref.toDoubleArray())
            val cr = DoubleArray(a1.size) { a1[it] * b1[it] + a2[it] * b2[it] }
            val ci = DoubleArray(a1.size) { a2[it] * b1[it] - a1[it] * b2[it] }
            val corr = Fft.irfft(cr, ci, n)
            val lim = n / 4
            val idx = (0..lim) + ((n - lim) until n)
            var best = idx.first()
            for (i in idx) if (corr[i] > corr[best]) best = i
            val rolled = DoubleArray(n) { v[(it + best) % n] }
            v = rolled
        }
        return WtSynth.normalizePeak(v).map { it }
    }

    /** N shapes -> N-1 families morphing each into the next. */
    fun chainFamilies(entries: List<WaveEntry>): List<WaveEntry> {
        if (entries.size == 1) {
            val e = entries[0]
            return listOf(WaveEntry("draw", e.name, e.group, e.a, e.b))
        }
        return entries.zipWithNext { a, b -> WaveEntry("draw", "${a.name}>${b.name}".take(32), a.group, a.a, b.a) }
    }

    /** N shapes -> N/2 families, each shape used once. */
    fun pairFamilies(entries: List<WaveEntry>): List<WaveEntry> {
        val out = ArrayList<WaveEntry>()
        var i = 0
        while (i + 1 < entries.size) {
            val a = entries[i]
            val b = entries[i + 1]
            out.add(WaveEntry("draw", "${a.name}>${b.name}".take(32), a.group, a.a, b.a))
            i += 2
        }
        if (entries.size % 2 == 1) {
            val e = entries.last()
            out.add(WaveEntry("draw", e.name, e.group, e.a, e.b))
        }
        return out
    }

    /** Everything into ONE multi family, one shape per step. */
    fun multiFamilies(entries: List<WaveEntry>): List<WaveEntry> {
        if (entries.isEmpty()) return emptyList()
        val kept = entries.take(MULTI_MAX)
        val label = if (kept.size == 1) kept[0].name else "${kept.first().name}..${kept.last().name}"
        return listOf(WaveEntry("multi", "$label (${kept.size})".take(32), kept[0].group,
            shapes = kept.map { it.a ?: emptyList() }, labels = kept.map { it.name }))
    }

    enum class CycleMode { Chain, Pairs, Multi }

    fun familiesForMode(entries: List<WaveEntry>, mode: CycleMode) = when (mode) {
        CycleMode.Pairs -> pairFamilies(entries)
        CycleMode.Multi -> multiFamilies(entries)
        CycleMode.Chain -> chainFamilies(entries)
    }

    /** MG2 before MG10. */
    fun naturalKey(name: String): String =
        Regex("""\d+""").replace(name.lowercase()) { it.value.padStart(12, '0') }

    /** One cycle as a note to listen to: repeated at [hz] for [seconds], ends faded. */
    fun cycleTone(values: List<Double>, hz: Double, seconds: Double = 3.0): FloatArray {
        val period = max(4, Math.rint(SR / hz).toInt())
        val cycle = resampleCycle(values.toDoubleArray(), period)
        var pk = 0.0
        for (v in cycle) pk = max(pk, abs(v))
        if (pk > 1e-12) for (i in cycle.indices) cycle[i] = cycle[i] / pk * 0.85
        val reps = max(1, Math.rint(seconds * SR / period).toInt())
        val tone = FloatArray(reps * period) { cycle[it % period].toFloat() }
        val edge = min((0.01 * SR).toInt(), tone.size / 4)
        if (edge > 1) for (i in 0 until edge) {
            val r = i.toFloat() / (edge - 1)
            tone[i] *= r
            tone[tone.size - 1 - i] *= r
        }
        return tone
    }

    @Suppress("unused")
    private fun ln2(x: Double) = ln(x)
}
