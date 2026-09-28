package io.github.pyp6.core.pattern

import io.github.pyp6.core.P6

/** One of the P-6's 64 pattern slots: bank 1-4, number 1-16. */
data class PatternSlot(val bank: Int, val number: Int) : Comparable<PatternSlot> {
    init {
        require(bank in 1..PatternSlot.BANKS && number in 1..PatternSlot.PER_BANK) { "no pattern slot $bank-$number" }
    }

    val fileName: String get() = "P6_PTN$bank-${number.toString().padStart(2, '0')}.PRM"
    val label: String get() = "$bank-${number.toString().padStart(2, '0')}"
    val index: Int get() = (bank - 1) * PER_BANK + (number - 1)

    override fun compareTo(other: PatternSlot): Int = index.compareTo(other.index)

    companion object {
        const val BANKS = 4
        const val PER_BANK = 16
        val ALL: List<PatternSlot> = (1..BANKS).flatMap { b -> (1..PER_BANK).map { PatternSlot(b, it) } }
        private val FILE_RE = Regex("""^P6_PTN(\d+)-(\d+)\.PRM$""", RegexOption.IGNORE_CASE)
        private val LABEL_RE = Regex("""^\s*(\d+)-(\d+)\s*$""")

        fun fromFileName(name: String): PatternSlot? {
            val m = FILE_RE.matchEntire(name) ?: return null
            return of(m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull())
        }

        fun fromLabel(label: String): PatternSlot? {
            val m = LABEL_RE.matchEntire(label) ?: return null
            return of(m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull())
        }

        private fun of(b: Int?, n: Int?): PatternSlot? =
            if (b != null && n != null && b in 1..BANKS && n in 1..PER_BANK) PatternSlot(b, n) else null

        fun fromIndex(i: Int): PatternSlot = ALL[i]
    }
}

/**
 * Pattern files number the sample pads 0-47: A1 is 0, A6 is 5, B1 is 6 ...
 * H6 is 47. Part 48 is the granular part. (Unconfirmed on hardware; if it
 * turns out otherwise only [PART_BASE] needs to change.)
 */
object Parts {
    const val PART_BASE = 0
    const val MAX_STEPS = 64
    val SAMPLE_PARTS = P6.BANKS.size * P6.PADS.size
    val GRANULAR = PART_BASE + SAMPLE_PARTS

    fun forPad(bank: Char, pad: Int): Int = PART_BASE + P6.bankIndex(bank) * P6.PADS.size + (pad - 1)

    fun padFor(part: Int): Pair<Char, Int>? {
        val i = part - PART_BASE
        return if (i in 0 until SAMPLE_PARTS) P6.BANKS[i / P6.PADS.size] to P6.PADS[i % P6.PADS.size] else null
    }

    fun padName(part: Int): String = padFor(part)?.let { "${it.first}${it.second}" } ?: "?"
}

/**
 * One pattern file, immutable. The text is kept verbatim (read as
 * ISO-8859-1 so every byte survives), and edits return new objects that only
 * rewrite the numbers that identify a pad. A pattern that is only moved is
 * written back byte for byte.
 */
class P6Pattern(val text: String) {
    val values: Map<String, String>
    val length: Int
    val tempo: Double
    val granularSource: Int
    val mutedParts: Set<Int>
    /** For each step (1-based index i at position i-1): the sample parts it plays. */
    val stepParts: List<List<Int>>
    val stepGranular: List<Int>
    val noteCount: Int
    val granularCount: Int
    val partCounts: Map<Int, Int>

    init {
        val vals = LinkedHashMap<String, String>()
        val smpl = HashMap<Int, List<Int>>()
        val grnl = HashMap<Int, Int>()
        for (line in splitLines(text)) {
            val m = LINE_RE.find(line) ?: continue
            val key = m.groupValues[1].trim()
            val value = m.groupValues[3]
            when {
                key.startsWith("STEP_NOTE_SMPL ") -> {
                    val parts = PART_RE.findAll(value).map { it.groupValues[2].toInt() }.filter { it >= 0 }.toList()
                    smpl[intOr(key.split(WS).last(), 0)!!] = parts
                }
                key.startsWith("STEP_NOTE_GRNL ") -> {
                    val notes = NOTE_RE.findAll(value).map { it.groupValues[2].toInt() }
                    grnl[intOr(key.split(WS).last(), 0)!!] = notes.count { it >= 0 }
                }
                !key.startsWith("STEP_") -> vals[key] = value.trim()
            }
        }
        values = vals
        length = intOr(vals["LENG"], 16)!!
        tempo = intOr(vals["TEMPO"], 0)!! / 100.0
        granularSource = intOr(vals["GRANU_PHRASE"], -1)!!
        mutedParts = LIST_RE.findAll(vals["PART_MUTE"] ?: "")
            .associate { it.groupValues[1] to it.groupValues[2] }
            .filter { (_, v) -> v != "0" && v != "" }
            .keys.map { it.toInt() }.toSet()
        val maxStep = (listOf(Parts.MAX_STEPS) + smpl.keys + grnl.keys).max()
        stepParts = (1..maxStep).map { smpl[it] ?: emptyList() }
        stepGranular = (1..maxStep).map { grnl[it] ?: 0 }
        noteCount = stepParts.sumOf { it.size }
        granularCount = stepGranular.sum()
        val counts = LinkedHashMap<Int, Int>()
        for (parts in stepParts) for (p in parts) counts[p] = (counts[p] ?: 0) + 1
        partCounts = counts
    }

    fun value(key: String): String? = values[key]

    val isEmpty: Boolean get() = noteCount == 0 && granularCount == 0

    /**
     * Every pad this pattern plays. The granular source counts only when the
     * pattern has granular notes - every file names one, used or not.
     */
    fun usedPads(): Set<Pair<Char, Int>> {
        val pads = LinkedHashSet<Pair<Char, Int>>()
        for (p in partCounts.keys) Parts.padFor(p)?.let { pads.add(it) }
        if (granularCount > 0) Parts.padFor(granularSource)?.let { pads.add(it) }
        return pads
    }

    /**
     * What the P-6's own clear does: every note, granular note and motion
     * step is replaced by the empty line the device writes; tempo, length,
     * shuffle, FX and the granular sound are kept.
     */
    fun cleared(): P6Pattern {
        val blank = blankSteps
        val out = StringBuilder()
        for (line in splitLinesKeepEnds(text)) {
            val body = line.trimEnd('\r', '\n')
            val ending = line.substring(body.length)
            val m = LINE_RE.find(body)
            if (m != null) {
                val key = m.groupValues[1].trim()
                val bv = blank[key]
                if (bv != null && m.groupValues[3] != bv) {
                    out.append(m.groupValues[1]).append(m.groupValues[2]).append(bv).append(ending)
                    continue
                }
            }
            out.append(line)
        }
        val t = out.toString()
        return if (t == text) this else P6Pattern(t)
    }

    /**
     * A copy with every pad reference moved through [mapping] (old part ->
     * new part, applied all at once so a swap is two entries): notes, the
     * granular source, motion targets and the per-part lists.
     */
    fun remapParts(mapping: Map<Int, Int>): P6Pattern {
        val map = mapping.filter { (k, v) -> k != v }
        if (map.isEmpty()) return this
        fun move(n: Int) = map[n] ?: n
        val out = StringBuilder()
        var changed = false
        for (line in splitLinesKeepEnds(text)) {
            val body = line.trimEnd('\r', '\n')
            val ending = line.substring(body.length)
            val m = LINE_RE.find(body)
            if (m == null) {
                out.append(line); continue
            }
            val key = m.groupValues[1]
            val sep = m.groupValues[2]
            val value = m.groupValues[3]
            val k = key.trim()
            var newValue = value
            when {
                k.startsWith("STEP_NOTE_SMPL ") -> newValue = PART_RE.replace(value) {
                    it.groupValues[1] + move(it.groupValues[2].toInt())
                }
                k == "GRANU_PHRASE" || (k.startsWith("MOTION_PRM") && k.endsWith("_PART")) -> {
                    val num = intOr(value.trim(), null)
                    if (num != null && num in map) newValue = value.replaceFirst(num.toString(), map.getValue(num).toString())
                }
                k in PART_LISTS -> {
                    val entries = LIST_RE.findAll(value).map { it.groupValues[1] to it.groupValues[2] }.toList()
                    val old = entries.associate { it.first.toInt() to it.second }
                    val new = HashMap(old)
                    for ((src, dst) in map) if (src in old && dst in old) new[dst] = old.getValue(src)
                    if (new != old) {
                        val rebuilt = entries.joinToString(" ") { (i, _) -> "$i=${new[i.toInt()]}" }
                        newValue = rebuilt + value.substring(value.trimEnd().length)
                    }
                }
            }
            if (newValue != value) {
                changed = true
                out.append(key).append(sep).append(newValue).append(ending)
            } else out.append(line)
        }
        return if (changed) P6Pattern(out.toString()) else this
    }

    fun toBytes(): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    companion object {
        internal val LINE_RE = Regex("""^([^\t=]+?)(\s*=\s?)(.*)$""")
        internal val PART_RE = Regex("""(\bPART\d+=)(-?\d+)""")
        private val NOTE_RE = Regex("""\bNOTE(\d+)=(-?\d+)""")
        private val LIST_RE = Regex("""(\d+)=(-?\d+)""")
        private val WS = Regex("""\s+""")
        private val PART_LISTS = setOf("PART_MUTE", "PART_QUANTIZE", "RESERVED_PTN1")
        private val STEP_PREFIXES = listOf("STEP_NOTE_SMPL ", "STEP_NOTE_GRNL ", "STEP_MOTION ")

        fun fromBytes(bytes: ByteArray): P6Pattern = P6Pattern(String(bytes, Charsets.ISO_8859_1))

        /** The whole cleared pattern file the device writes (a real P6_PTN1-07.PRM). */
        val blankText: String by lazy {
            val stream = P6Pattern::class.java.getResourceAsStream("/pyp6/blank_pattern.prm")
                ?: error("blank pattern resource missing")
            String(stream.use { it.readBytes() }, Charsets.ISO_8859_1)
        }

        fun blank(): P6Pattern = P6Pattern(blankText)

        private val blankSteps: Map<String, String> by lazy {
            val out = HashMap<String, String>()
            for (line in splitLines(blankText)) {
                val m = LINE_RE.find(line) ?: continue
                val k = m.groupValues[1].trim()
                if (STEP_PREFIXES.any { k.startsWith(it) }) out[k] = m.groupValues[3]
            }
            out
        }

        internal fun intOr(text: String?, default: Int?): Int? = text?.trim()?.toIntOrNull() ?: default

        /** Python's str.splitlines() for the line breaks these files can contain. */
        internal fun splitLines(text: String): List<String> =
            splitLinesKeepEnds(text).map { it.trimEnd('\r', '\n') }

        internal fun splitLinesKeepEnds(text: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            val n = text.length
            while (i < n) {
                var j = i
                while (j < n && text[j] != '\n' && text[j] != '\r') j++
                if (j < n) {
                    if (text[j] == '\r' && j + 1 < n && text[j + 1] == '\n') j += 2 else j += 1
                }
                out.add(text.substring(i, j))
                i = j
            }
            return out
        }

        /** Applies a pad mapping to every pattern; returns the new map and how many changed. */
        fun remapAll(patterns: Map<PatternSlot, P6Pattern>, mapping: Map<Int, Int>): Pair<Map<PatternSlot, P6Pattern>, Int> {
            var changed = 0
            val out = LinkedHashMap<PatternSlot, P6Pattern>()
            for ((slot, pat) in patterns) {
                val n = pat.remapParts(mapping)
                if (n !== pat) changed++
                out[slot] = n
            }
            return out to changed
        }
    }
}
