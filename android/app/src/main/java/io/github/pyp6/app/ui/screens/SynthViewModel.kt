package io.github.pyp6.app.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.pyp6.app.PyP6Application
import io.github.pyp6.app.ui.MainViewModel
import io.github.pyp6.core.audio.Audio
import io.github.pyp6.core.model.PadRef
import io.github.pyp6.core.model.WtConfig
import io.github.pyp6.core.wavetable.MiniFreak
import io.github.pyp6.core.wavetable.WaveEntry
import io.github.pyp6.core.wavetable.Wavetable
import io.github.pyp6.core.wavetable.WtBuildResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SynthUi(
    val mode: String = "Simple",
    val simpleSet: String = "Basic",
    val register: String = "Bass",
    val note: String = "C2",
    val up: Int = 0,
    /** Advanced mode's step order. */
    val selection: List<String> = emptyList(),
    val randomized: List<String> = emptyList(),
    val working: String? = null,
    /** The family last tapped, with the waveforms its morph steps through and how many steps it gets. */
    val morph: Morph? = null,
    val lastWarning: String? = null,
) {
    val simple: Boolean get() = mode == "Simple"
}

/** A family's morph as the Morph display shows it: up to [SHOWN] of its [steps] waveforms. */
data class Morph(val name: String, val shapes: List<DoubleArray>, val steps: Int) {
    companion object { const val SHOWN = 12 }
}

/** Cycle files read for import, waiting for the user to pick a mode. */
data class PendingCycles(val entries: List<WaveEntry>, val skipped: List<String>, val tables: List<String>)

/**
 * The wavetable builder: 255 single-cycle segments morphing through up to 16
 * families, with the START knob on the P-6 stepping through them.
 */
class SynthViewModel(app: Application, val ref: PadRef) : AndroidViewModel(app) {
    private val c = (app as PyP6Application).container
    private val _ui = MutableStateFlow(SynthUi())
    val ui: StateFlow<SynthUi> = _ui.asStateFlow()
    private val _pending = MutableStateFlow<PendingCycles?>(null)
    val pending: StateFlow<PendingCycles?> = _pending.asStateFlow()
    /** Entries restored from the pad's own config, which win over library entries of the same name. */
    private var padCustom: Map<String, WaveEntry> = emptyMap()

    val tableId = "synth:${ref.label}"

    private companion object {
        /** The desktop's cap: a morph this smooth sounds the same with 64 steps as with 255. */
        const val PREVIEW_STEP_CAP = 64
    }

    init {
        val cfg = c.repository.value.pad(ref)?.wavetable?.config
        if (cfg != null) {
            padCustom = cfg.custom.associateBy { it.name }
            val known = cfg.families.map { Wavetable.canonical(it) }
            _ui.value = SynthUi(
                mode = cfg.mode, simpleSet = cfg.simple_set, register = cfg.register, note = cfg.note,
                up = cfg.up.coerceIn(0, Wavetable.DEVICE_MAX_UP), selection = known.take(Wavetable.MAX_SELECTED),
            )
        }
        // Warm the catalogue (decodes the collections) off the main thread.
        viewModelScope.launch(Dispatchers.Default) { Wavetable.catalogue }
    }

    fun setMode(m: String) { _ui.value = _ui.value.copy(mode = m) }

    fun setSimpleSet(s: String) {
        _ui.value = _ui.value.copy(simpleSet = s, randomized = if (s == Wavetable.RANDOMIZE) Wavetable.randomFamilies() else _ui.value.randomized)
    }

    fun setRegister(r: String) {
        val reg = Wavetable.register(r)
        _ui.value = _ui.value.copy(register = r, note = Wavetable.midiToName(reg.baseMidi), up = reg.defaultUp)
    }

    fun setNote(n: String) { _ui.value = _ui.value.copy(note = n) }
    fun setUp(v: Int) { _ui.value = _ui.value.copy(up = v.coerceIn(0, Wavetable.DEVICE_MAX_UP)) }

    fun simpleFamilies(): List<String> {
        val u = _ui.value
        if (u.simpleSet == Wavetable.RANDOMIZE) return u.randomized.ifEmpty { Wavetable.randomFamilies().also { r -> _ui.value = u.copy(randomized = r) } }
        return Wavetable.simpleSets().firstOrNull { it.first == u.simpleSet }?.second ?: Wavetable.SIMPLE_FAMILIES
    }

    fun activeNames(): List<String> = if (_ui.value.simple) simpleFamilies() else _ui.value.selection

    fun item(name: String, library: Map<String, WaveEntry>): Wavetable.Item =
        padCustom[name]?.let { Wavetable.Item.Custom(it) }
            ?: library[name]?.let { Wavetable.Item.Custom(it) }
            ?: Wavetable.Item.Builtin(name)

    fun isMulti(name: String, library: Map<String, WaveEntry>): Boolean =
        name in Wavetable.catalogue.builtinMulti || (padCustom[name] ?: library[name])?.isMulti == true

    fun pitch(): Triple<Int, Int, Int> {
        val u = _ui.value
        val reg = Wavetable.register(u.register)
        val midi = Wavetable.nameToMidi(u.note) ?: reg.baseMidi
        return Triple(midi, reg.cycles, u.up)
    }

    // --- step order

    fun addFamily(name: String, vm: MainViewModel) {
        val u = _ui.value
        if (name in u.selection) { vm.message("$name is already in the step order."); return }
        if (u.selection.size >= Wavetable.MAX_SELECTED) { vm.message("The step order holds at most ${Wavetable.MAX_SELECTED} families.", true); return }
        val lib = vm.library.value
        val warn = when {
            isMulti(name, lib) && u.selection.isNotEmpty() ->
                "$name is a multi family: it needs the whole table to itself, and beside others only some of its waveforms reach the table."
            u.selection.any { isMulti(it, lib) } -> "The multi family already in the step order now shares the table and loses waveforms."
            else -> null
        }
        _ui.value = u.copy(selection = u.selection + name, lastWarning = warn)
        warn?.let { vm.message(it) }
    }

    fun removeAt(i: Int) { _ui.value = _ui.value.copy(selection = _ui.value.selection.filterIndexed { k, _ -> k != i }) }

    fun move(i: Int, d: Int) {
        val l = _ui.value.selection.toMutableList()
        val j = i + d
        if (i !in l.indices || j !in l.indices) return
        val t = l[i]; l[i] = l[j]; l[j] = t
        _ui.value = _ui.value.copy(selection = l)
    }

    fun clearSelection() { _ui.value = _ui.value.copy(selection = emptyList()) }

    /** In Advanced mode, starting from a Simple set is often what one wants. */
    fun copySimpleToAdvanced() { _ui.value = _ui.value.copy(selection = simpleFamilies().take(Wavetable.MAX_SELECTED), mode = "Advanced") }

    // --- listening

    private fun stepsFor(name: String): Int {
        val names = activeNames()
        val counts = Wavetable.splitSteps(names.size)
        val i = names.indexOf(name)
        return if (i >= 0) counts[i] else 16
    }

    fun familyId(name: String) = "synth:family:$name"

    /**
     * Shows a family's morph and plays its sweep at the configured pitch.
     * Tapping the family that is playing stops it instead.
     */
    fun previewFamily(name: String, vm: MainViewModel) {
        if (c.player.isPlaying(familyId(name))) { c.player.stop(); return }
        val lib = vm.library.value
        val it = item(name, lib)
        val (midi, cycles, up) = pitch()
        viewModelScope.launch {
            val res = withContext(Dispatchers.Default) {
                runCatching {
                    val steps = if (isMulti(name, lib)) Wavetable.SEGMENTS else stepsFor(name)
                    // Rendering and audio cost only; the display says how many steps the table really gets.
                    val audio = Wavetable.renderSweep(it, midi, cycles, up, minOf(steps, PREVIEW_STEP_CAP))
                    val shapes = Wavetable.morphShapes(it, midi, cycles, up, steps.coerceIn(2, Morph.SHOWN))
                    Triple(audio, shapes, steps)
                }.getOrNull()
            }
            if (res == null) { vm.message("$name cannot be rendered.", true); return@launch }
            _ui.value = _ui.value.copy(morph = Morph(name, res.second, res.third))
            vm.play(familyId(name), Audio(Wavetable.SR, listOf(res.first)))
        }
    }

    private fun buildNow(lib: Map<String, WaveEntry>): WtBuildResult {
        val (midi, cycles, up) = pitch()
        return Wavetable.build(activeNames().map { item(it, lib) }, midi, cycles, up)
    }

    /** Plays the whole table as the pad would (each segment twice, so it is audible). */
    fun previewTable(vm: MainViewModel) {
        if (c.player.isPlaying(tableId)) { c.player.stop(); return }
        val lib = vm.library.value
        viewModelScope.launch {
            _ui.value = _ui.value.copy(working = "Building …")
            val r = withContext(Dispatchers.Default) { runCatching { buildNow(lib) } }
            _ui.value = _ui.value.copy(working = null)
            r.onFailure { vm.message(it.message ?: "Could not build.", true) }
            r.onSuccess { res ->
                val L = res.meta.L
                val out = FloatArray(res.pcm.size * Wavetable.AUDITION_REPEATS)
                var at = 0
                for (seg in 0 until Wavetable.SEGMENTS) repeat(Wavetable.AUDITION_REPEATS) {
                    for (i in 0 until L) out[at++] = res.pcm[seg * L + i] / 32768f
                }
                vm.play(tableId, Audio(Wavetable.SR, listOf(out)))
            }
        }
    }

    fun build(vm: MainViewModel, onDone: () -> Unit) {
        val names = activeNames()
        if (names.isEmpty()) { vm.message("Choose at least one waveform family.", true); return }
        val lib = vm.library.value
        viewModelScope.launch {
            _ui.value = _ui.value.copy(working = "Building wavetable …")
            val r = withContext(Dispatchers.Default) { runCatching { buildNow(lib) } }
            _ui.value = _ui.value.copy(working = null)
            r.onFailure { vm.message(it.message ?: "Could not build the wavetable.", true) }
            r.onSuccess { res ->
                val u = _ui.value
                val custom = names.mapNotNull { n -> (padCustom[n] ?: lib[n]) }
                val cfg = WtConfig(
                    mode = u.mode, simple_set = u.simpleSet, register = u.register, note = u.note, up = u.up,
                    families = names, custom = custom,
                )
                withContext(Dispatchers.IO) { vm.applyWavetable(ref, res, cfg) }
                onDone()
            }
        }
    }

    // --- MiniFreak export

    /** The selection as the export sees it, resolved the same way a build resolves it. */
    private fun items(lib: Map<String, WaveEntry>) = activeNames().map { item(it, lib) }

    /** What the export dialog starts at: 189, or one frame per shape for a lone smaller multi family. */
    fun miniFreakDefaultFrames(lib: Map<String, WaveEntry>): Int =
        maxOf(activeNames().size, MiniFreak.defaultFrames(items(lib)))

    /** Suggested file name: the set in Simple mode, the family or "Custom Selection" in Advanced. */
    fun miniFreakFileName(): String {
        val u = _ui.value
        val label = if (u.simple) u.simpleSet else u.selection.singleOrNull() ?: "Custom Selection"
        return MiniFreak.fileName(label) + ".wav"
    }

    /**
     * Writes the current selection as a MiniFreak wavetable to [uri]. Only the
     * file is written: the pad and its table stay as they are. The root note
     * only voices the formant families (Vowel, Piano, Strings, Brass).
     */
    fun exportMiniFreak(uri: Uri, frames: Int, vm: MainViewModel) {
        val lib = vm.library.value
        val note = _ui.value.note
        viewModelScope.launch {
            _ui.value = _ui.value.copy(working = "Rendering MiniFreak wavetable …")
            val r = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = MiniFreak.wav(MiniFreak.build(items(lib), frames, note))
                    withContext(Dispatchers.IO) {
                        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                    }
                }
            }
            _ui.value = _ui.value.copy(working = null)
            r.onFailure { vm.message(it.message ?: "Could not export the wavetable.", true) }
            r.onSuccess { vm.message("Exported $frames frames for the MiniFreak.") }
        }
    }

    // --- single-cycle import

    fun readCycles(uris: List<Uri>, vm: MainViewModel) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(working = "Reading cycles …")
            val result = withContext(Dispatchers.IO) {
                val named = uris.map { it to c.importer.displayName(it) }.sortedBy { Wavetable.naturalKey(it.second) }
                val entries = ArrayList<WaveEntry>()
                val skipped = ArrayList<String>()
                val tables = ArrayList<String>()
                var prev: List<Double>? = null
                for ((u, name) in named) {
                    val base = name.substringBeforeLast('.').take(24).ifEmpty { "Custom" }
                    try {
                        val a = c.importer.decode(u)
                        val data = a.mono().let { m -> DoubleArray(m.size) { m[it].toDouble() } }
                        val declared = runCatching {
                            getApplication<Application>().contentResolver.openInputStream(u)!!.use { io.github.pyp6.core.audio.Wav.readInfo(it.buffered()).clmFrameSize }
                        }.getOrNull()
                        if (data.size > Wavetable.CYCLE_SANE_MAX || (declared != null && data.size / declared >= 2)) {
                            val (shapes, size) = Wavetable.splitTable(data, declared)
                            val width = shapes.size.toString().length
                            shapes.forEachIndexed { k, s ->
                                val aligned = Wavetable.alignPoints(s, prev).map { v -> Math.rint(v * 10000) / 10000 }
                                prev = aligned
                                entries.add(WaveEntry("draw", "$base ${(k + 1).toString().padStart(width, '0')}".take(24), a = aligned, b = aligned))
                            }
                            tables.add("$name: ${shapes.size} waveforms of $size samples")
                        } else {
                            val s = Wavetable.cycleShape(data)
                            val aligned = Wavetable.alignPoints(s, prev).map { v -> Math.rint(v * 10000) / 10000 }
                            prev = aligned
                            entries.add(WaveEntry("draw", base, a = aligned, b = aligned))
                        }
                    } catch (e: Exception) {
                        skipped.add("$name: ${e.message}")
                    }
                }
                PendingCycles(entries, skipped, tables)
            }
            _ui.value = _ui.value.copy(working = null)
            if (result.entries.isEmpty()) vm.message("No usable cycles: ${result.skipped.joinToString("; ")}", true)
            else _pending.value = result
        }
    }

    fun cancelCycles() { _pending.value = null }

    /** Turns the read cycles into families (Chain / Pairs / Multi), stores them and adds them to the step order. */
    fun commitCycles(mode: Wavetable.CycleMode, group: String, vm: MainViewModel) {
        val p = _pending.value ?: return
        _pending.value = null
        val g = group.trim().ifEmpty { Wavetable.DEFAULT_USER_GROUP }
        val fams = Wavetable.familiesForMode(p.entries.map { it.copy(group = g) }, mode)
            .map { e -> e.copy(name = vm.uniqueWaveName(e.name), group = g) }
        vm.putWaveEntries(fams)
        val room = Wavetable.MAX_SELECTED - _ui.value.selection.size
        val add = fams.take(room.coerceAtLeast(0)).map { it.name }
        _ui.value = _ui.value.copy(selection = _ui.value.selection + add, mode = "Advanced")
        vm.message("${fams.size} famil${if (fams.size == 1) "y" else "ies"} added to “$g”" +
            if (add.size < fams.size) "; ${add.size} fitted in the step order." else ".")
    }
}
