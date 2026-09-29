package io.github.pyp6.core.model

import io.github.pyp6.core.P6
import io.github.pyp6.core.pattern.P6Pattern
import io.github.pyp6.core.pattern.Parts
import io.github.pyp6.core.pattern.PatternSlot
import io.github.pyp6.core.wavetable.WaveEntry
import io.github.pyp6.core.wavetable.WtMeta
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a wavetable pad was built, so Synth can reopen it as it was. */
@Serializable
data class WtConfig(
    val mode: String = "Simple",
    val simple_set: String = "Basic",
    val register: String = "Bass",
    val note: String = "C2",
    val up: Int = 0,
    val families: List<String> = emptyList(),
    val custom: List<WaveEntry> = emptyList(),
    val save_map: Boolean = false,
)

@Serializable
data class WavetableState(val config: WtConfig, val meta: WtMeta)

/**
 * One pad. Field names follow the desktop app's pad state, which is also
 * what preset.json stores, so presets move between the two unchanged.
 * [filepath] is an absolute path to a file the app owns.
 */
@Serializable
data class PadState(
    val filepath: String,
    val target_rate: Int = 44100,
    val pitch_cents: Int = 0,
    val mono: Boolean = false,
    val from_sync: Boolean = false,
    val display_name: String? = null,
    val wavetable: WavetableState? = null,
    val wt_patch: String = "Init",
    val wt_poly: Boolean = false,
) {
    val name: String get() = display_name ?: java.io.File(filepath).name
    val isWavetable: Boolean get() = wavetable != null
}

@Serializable
data class BankState(
    val pads: List<PadState?> = List(P6.PADS.size) { null },
    val forceMono: Boolean = false,
) {
    fun pad(pad: Int): PadState? = pads[pad - 1]
    fun withPad(pad: Int, state: PadState?): BankState = copy(pads = pads.toMutableList().also { it[pad - 1] = state })
    val hasSamples: Boolean get() = pads.any { it != null }
}

/** A pad address. */
@Serializable
data class PadRef(val bank: Char, val pad: Int) {
    val label: String get() = "$bank$pad"
    val part: Int get() = Parts.forPad(bank, pad)
}

/**
 * Everything the user is working on, immutable: an undo step is just the
 * previous Project. Patterns are immutable too, so snapshots share them.
 */
data class Project(
    val banks: Map<Char, BankState> = P6.BANKS.associateWith { BankState() },
    val patterns: Map<PatternSlot, P6Pattern> = emptyMap(),
    /** Where the patterns came from (display only). */
    val patternSource: String? = null,
    /** Patterns changed since they were loaded or saved. */
    val patternsDirty: Boolean = false,
) {
    fun bank(b: Char): BankState = banks[b] ?: BankState()
    fun pad(ref: PadRef): PadState? = bank(ref.bank).pad(ref.pad)
    fun pad(b: Char, p: Int): PadState? = bank(b).pad(p)

    fun withPad(ref: PadRef, state: PadState?): Project =
        copy(banks = banks + (ref.bank to bank(ref.bank).withPad(ref.pad, state)))

    fun withBank(b: Char, state: BankState): Project = copy(banks = banks + (b to state))

    fun effectiveMono(ref: PadRef): Boolean = bank(ref.bank).forceMono || (pad(ref)?.mono ?: false)

    fun allPads(): List<Pair<PadRef, PadState>> =
        P6.BANKS.flatMap { b -> P6.PADS.mapNotNull { p -> pad(b, p)?.let { PadRef(b, p) to it } } }

    /** Every file a pad points at - what the app's sample folder must keep. */
    fun referencedFiles(): Set<String> = allPads().map { it.second.filepath }.toSet()

    /** Applies a pad mapping to the patterns when [sync] is on. Returns the project and the patterns changed. */
    fun syncPatterns(pairs: List<Pair<PadRef, PadRef>>, swap: Boolean, sync: Boolean): Pair<Project, Int> {
        if (!sync || patterns.isEmpty()) return this to 0
        val mapping = HashMap<Int, Int>()
        for ((a, b) in pairs) {
            mapping[a.part] = b.part
            if (swap) mapping[b.part] = a.part
        }
        val (np, changed) = P6Pattern.remapAll(patterns, mapping)
        return copy(patterns = np, patternsDirty = patternsDirty || changed > 0) to changed
    }

    /** Swaps two pads, anywhere; settings travel with them. */
    fun swapPads(a: PadRef, b: PadRef, sync: Boolean): Pair<Project, Int> {
        if (a == b) return this to 0
        val pa = pad(a)
        val pb = pad(b)
        return withPad(a, pb).withPad(b, pa).syncPatterns(listOf(a to b), swap = true, sync = sync)
    }

    /** Swaps two whole banks, Force Mono included. */
    fun swapBanks(a: Char, b: Char, sync: Boolean): Pair<Project, Int> {
        if (a == b) return this to 0
        val ba = bank(a)
        val bb = bank(b)
        return withBank(a, bb).withBank(b, ba)
            .syncPatterns(P6.PADS.map { PadRef(a, it) to PadRef(b, it) }, swap = true, sync = sync)
    }

    fun clearBanks(which: Collection<Char>): Project {
        var p = this
        for (b in which) p = p.withBank(b, BankState())
        return p
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            prettyPrint = false
        }
    }
}
