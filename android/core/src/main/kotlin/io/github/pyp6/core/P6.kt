package io.github.pyp6.core

/**
 * Facts about the Roland AIRA P-6 that the whole app shares.
 *
 * Mirrors the constants block of the desktop script
 * (PyP6-Roland-P6-Sample-Manager.py): banks A-H, six pads each, the four
 * sample rates the device accepts and how long a sample may be at each.
 */
object P6 {
    val BANKS: List<Char> = ('A'..'H').toList()
    val PADS: List<Int> = (1..6).toList()
    val TARGET_RATES: List<Int> = listOf(44100, 22050, 14700, 11025)
    val SLICE_COUNTS: List<Int> = listOf(1, 2, 4, 8, 16, 24, 32, 48, 64)

    const val PITCH_MIN_CENTS = -1200
    const val PITCH_MAX_CENTS = 1200
    const val PITCH_STEP_CENTS = 100

    /** Soft limit per upload: the desktop app asks before copying more. */
    const val MAX_UPLOAD_BYTES: Long = 10L * 1024 * 1024

    const val MAX_UNDO_STEPS = 25

    private val MAX_SECONDS: Map<Pair<Int, Int>, Double> = mapOf(
        (44100 to 1) to 5.9, (44100 to 2) to 2.95,
        (22050 to 1) to 11.8, (22050 to 2) to 5.9,
        (14700 to 1) to 17.8, (14700 to 2) to 8.9,
        (11025 to 1) to 23.7, (11025 to 2) to 11.85,
    )

    /** The longest sample the P-6 keeps at [rate] Hz with [channels] channels. */
    fun maxSeconds(rate: Int, channels: Int): Double? =
        MAX_SECONDS[rate to (if (channels <= 1) 1 else 2)]

    fun bankIndex(bank: Char): Int = BANKS.indexOf(bank)

    /** PHRASE = bank index * 6 + (pad - 1). Bank A, pad 1 -> 0. */
    fun phraseNumber(bank: Char, pad: Int): Int = bankIndex(bank) * PADS.size + (pad - 1)

    /**
     * The lowest offered rate that still holds everything the file has; the
     * top rate for anything above it (the desktop app's rule on load).
     */
    fun defaultRateFor(sourceRate: Int): Int =
        TARGET_RATES.filter { it >= sourceRate }.minOrNull() ?: TARGET_RATES.max()
}
