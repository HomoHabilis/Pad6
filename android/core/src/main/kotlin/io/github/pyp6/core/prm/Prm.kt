package io.github.pyp6.core.prm

import io.github.pyp6.core.P6

/**
 * The P-6's .PRM sidecar: plain text, "KEY\t= <int>\n", fixed order.
 *
 * PRM_DEFAULTS is the baseline observed across exports from the device,
 * TEMPLATES are dry voicings layered on top. Every template enables GATE and
 * LOOP, otherwise a single wavetable segment would not sustain.
 */
object Prm {
    val DEFAULTS: List<Pair<String, Int>> = listOf(
        "PHRASE" to 0, "GATE" to 1, "LOOP" to 1, "REVERSE" to 0,
        "START_POS" to 0, "SIZE" to 0, "LOOP_SIZE" to 0,
        "C.TUNE" to 0, "F.TUNE" to 0, "DETUNE" to 0,
        "LO-FI_SW" to 0, "LO-FI" to 70,
        "ENV_MODE" to 4,
        "PENV_MODE" to 1, "PENV_ATTACK" to 0, "PENV_DECAY" to 20,
        "PENV_SUSTAIN" to 255, "PENV_RELEASE" to 25, "PENV_TIME_KEYF" to 255,
        "PENV_VELO_SENS" to 0, "PENV_DEPTH" to 0,
        "TENV_MODE" to 1, "TENV_ATTACK" to 3, "TENV_DECAY" to 0,
        "TENV_SUSTAIN" to 255, "TENV_RELEASE" to 3, "TENV_TIME_KEYF" to 255,
        "TVF_TYPE" to 0, "TVF_CUTOFF" to 255, "TVF_RESO" to 0, "TVF_KEYF" to 255,
        "TVF_VELO_SENS" to 0, "TVF_ENV_DEPTH" to 0,
        "TVA_SW" to 1, "LEVEL" to 100,
        "PAN_MODE" to 0, "PAN" to 64, "OUTPUT_SEL" to 2,
        "SEND_DELAY" to 0, "SEND_REVERB" to 0,
        "TM_STR_MODE" to 0, "TM_STR_WINDOW" to 30, "TM_STR_SPEED" to 100,
        "MONO_POLY" to 0, "CHOP" to 1, "MUTE_GROUP" to 0,
    ) + (1..16).map { "PRM$it" to 0 }

    val TEMPLATES: Map<String, Map<String, Int>> = linkedMapOf(
        "Init" to mapOf("GATE" to 1, "LOOP" to 1, "TENV_SUSTAIN" to 255, "LEVEL" to 110),
        "Acid" to mapOf(
            "GATE" to 1, "LOOP" to 1, "TENV_ATTACK" to 23,
            "TENV_DECAY" to 26, "TENV_SUSTAIN" to 45, "TENV_RELEASE" to 108,
            "TVF_TYPE" to 1, "TVF_CUTOFF" to 137, "TVF_RESO" to 218,
            "TVF_ENV_DEPTH" to 27, "LEVEL" to 110,
        ),
        "Bass 1" to mapOf(
            "GATE" to 1, "LOOP" to 1, "TENV_ATTACK" to 0,
            "TENV_DECAY" to 52, "TENV_SUSTAIN" to 18, "TENV_RELEASE" to 71,
            "TVF_TYPE" to 1, "TVF_CUTOFF" to 3, "TVF_RESO" to 23,
            "TVF_ENV_DEPTH" to 43, "LEVEL" to 110,
        ),
        "Bass 2" to mapOf(
            "GATE" to 1, "LOOP" to 1, "TENV_ATTACK" to 0,
            "TENV_DECAY" to 85, "TENV_SUSTAIN" to 82, "TENV_RELEASE" to 109,
            "TVF_TYPE" to 1, "TVF_CUTOFF" to 0, "TVF_RESO" to 75,
            "TVF_ENV_DEPTH" to 19, "LEVEL" to 110,
        ),
        "Pad" to mapOf(
            "GATE" to 1, "LOOP" to 1, "DETUNE" to 10,
            "PENV_MODE" to 1, "PENV_ATTACK" to 1, "PENV_DECAY" to 225,
            "PENV_SUSTAIN" to 255, "PENV_RELEASE" to 25, "PENV_TIME_KEYF" to 255,
            "PENV_VELO_SENS" to 0, "PENV_DEPTH" to 0,
            "TENV_ATTACK" to 255, "TENV_DECAY" to 125, "TENV_RELEASE" to 255,
            "TVF_TYPE" to 1, "TVF_CUTOFF" to 112, "TVF_ENV_DEPTH" to 122,
            "LEVEL" to 110, "MONO_POLY" to 1,
        ),
        "Reso" to mapOf(
            "GATE" to 1, "LOOP" to 1, "TENV_SUSTAIN" to 73,
            "TENV_RELEASE" to 181, "TVF_TYPE" to 2, "TVF_CUTOFF" to 46,
            "TVF_RESO" to 158, "TVF_ENV_DEPTH" to 66, "LEVEL" to 110,
        ),
        "Reso 2" to mapOf(
            "GATE" to 1, "LOOP" to 1, "TENV_DECAY" to 17,
            "TENV_SUSTAIN" to 86, "TENV_RELEASE" to 74, "TVF_TYPE" to 2,
            "TVF_CUTOFF" to 102, "TVF_RESO" to 133, "TVF_ENV_DEPTH" to 22,
            "LEVEL" to 110,
        ),
    )

    init {
        for ((name, t) in TEMPLATES) {
            require(t["GATE"] == 1 && t["LOOP"] == 1) { "PRM template $name must set GATE = 1 and LOOP = 1" }
        }
    }

    /** The .PRM text for one pad. [poly] overrides the template's MONO_POLY. */
    fun render(
        bank: Char, pad: Int, startFrame: Int, sizeFrames: Int, totalFrames: Int,
        template: String = "Init", poly: Boolean? = null, overrides: Map<String, Int> = emptyMap(),
    ): String {
        val values = LinkedHashMap<String, Int>()
        DEFAULTS.forEach { (k, v) -> values[k] = v }
        values.putAll(TEMPLATES[template] ?: TEMPLATES.getValue("Init"))
        values["PHRASE"] = P6.phraseNumber(bank, pad)
        values["START_POS"] = startFrame
        values["SIZE"] = sizeFrames
        // The device writes the full frame count here even for a one-segment loop.
        values["LOOP_SIZE"] = totalFrames
        if (poly != null) values["MONO_POLY"] = if (poly) 1 else 0
        values.putAll(overrides)
        return buildString { DEFAULTS.forEach { (k, _) -> append(k).append("\t= ").append(values[k]).append('\n') } }
    }

    /**
     * [text] with its PHRASE line pointed at [bank]/[pad]; every other byte is
     * passed through, line endings included. Null when there is no PHRASE line.
     */
    fun retargetPhrase(text: String, bank: Char, pad: Int): String? {
        val want = P6.phraseNumber(bank, pad)
        val out = StringBuilder()
        var found = false
        var i = 0
        while (i < text.length) {
            var j = text.indexOf('\n', i)
            j = if (j < 0) text.length else j + 1
            val line = text.substring(i, j)
            val body = line.trimEnd('\r', '\n')
            if (!found && body.trim().substringBefore('=').trim().uppercase() == "PHRASE") {
                val ending = line.substring(body.length)
                out.append("PHRASE\t= ").append(want).append(ending)
                found = true
            } else out.append(line)
            i = j
        }
        return if (found) out.toString() else null
    }

    private val VALUE_RE = Regex("""^([^\t=\r\n]+?)\s*=\s*(-?\d+)\s*$""", RegexOption.MULTILINE)

    /** {KEY: int} from a pad or pattern .PRM. */
    fun readValues(text: String): Map<String, Int> =
        VALUE_RE.findAll(text).associate { it.groupValues[1].trim() to it.groupValues[2].toInt() }
}
