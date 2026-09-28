package io.github.pyp6.app.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.pyp6.app.PyP6Application
import io.github.pyp6.app.ui.MainViewModel
import io.github.pyp6.core.P6
import io.github.pyp6.core.audio.Audio
import io.github.pyp6.core.audio.Wav
import io.github.pyp6.core.dsp.Analysis
import io.github.pyp6.core.dsp.Chop
import io.github.pyp6.core.dsp.ChopNormalize
import io.github.pyp6.core.dsp.Edit
import io.github.pyp6.core.model.PadRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One entry of the chop order: a sample (or a piece of one) held in memory. */
data class ChopEntry(val id: String, val name: String, val audio: Audio) {
    val seconds: Double get() = audio.durationSeconds
}

/** The file being cut into regions or pieces. */
data class ChopSource(
    val name: String,
    val audio: Audio,
    val mono: FloatArray,
    val selStart: Double = 0.0,
    val selEnd: Double = 1.0,
    /** Cut positions in frames (Detect Hits). */
    val cuts: List<Int> = emptyList(),
    val sensitivity: Double = 1.0,
    val selectedPiece: Int? = null,
) {
    val edges: List<Int> get() = (listOf(0) + cuts.filter { it > 0 && it < audio.frames } + listOf(audio.frames)).distinct().sorted()
    val pieceCount: Int get() = if (cuts.isEmpty()) 0 else edges.size - 1
    fun piece(i: Int): Pair<Int, Int> = edges[i] to edges[i + 1]
}

data class ChopUi(
    val entries: List<ChopEntry> = emptyList(),
    val source: ChopSource? = null,
    val slices: Int = 16,
    val rate: Int = 44100,
    val stereo: Boolean = false,
    val normalize: ChopNormalize = ChopNormalize.OFF,
    val working: String? = null,
) {
    val channels: Int get() = if (stereo) 2 else 1
    val slotSeconds: Double get() = Chop.slotSeconds(rate, channels, slices) ?: 0.0
    val totalSeconds: Double get() = P6.maxSeconds(rate, channels) ?: 0.0
}

/**
 * Chop: several samples (or pieces of one) into one multisample for the
 * P-6's own Chop function. Port of the desktop ChopDialog.
 */
class ChopViewModel(app: Application, val ref: PadRef) : AndroidViewModel(app) {
    private val c = (app as PyP6Application).container
    private val _ui = MutableStateFlow(ChopUi(slices = c.settings.value.defaultSlices.takeIf { it in P6.SLICE_COUNTS } ?: 16))
    val ui: StateFlow<ChopUi> = _ui.asStateFlow()
    private var hitJob: Job? = null

    private fun busy(label: String?) { _ui.value = _ui.value.copy(working = label) }

    private fun add(entries: List<ChopEntry>, vm: MainViewModel) {
        val cap = P6.SLICE_COUNTS.max()
        val room = cap - _ui.value.entries.size
        if (room <= 0) { vm.message("The chop order already holds $cap samples, the most a multisample can carry.", true); return }
        val take = entries.take(room)
        _ui.value = _ui.value.copy(entries = _ui.value.entries + take)
        if (entries.size > room) vm.message("Only the first $room fitted in the chop order.")
    }

    fun addFiles(uris: List<Uri>, vm: MainViewModel) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy("Reading files …")
            val out = ArrayList<ChopEntry>()
            val failed = ArrayList<String>()
            withContext(Dispatchers.IO) {
                // Natural order, so "Hit 2" comes before "Hit 10".
                val named = uris.map { it to c.importer.displayName(it) }
                    .sortedBy { io.github.pyp6.core.wavetable.Wavetable.naturalKey(it.second) }
                for ((u, name) in named) {
                    try {
                        out.add(ChopEntry(UUID.randomUUID().toString(), name, c.importer.decode(u)))
                    } catch (e: Exception) {
                        failed.add(name)
                    }
                }
            }
            busy(null)
            add(out, vm)
            if (failed.isNotEmpty()) vm.message("Could not read: ${failed.joinToString(", ")}", true)
        }
    }

    fun openSource(uri: Uri, vm: MainViewModel) {
        viewModelScope.launch {
            busy("Reading …")
            val src = withContext(Dispatchers.IO) {
                runCatching {
                    val name = c.importer.displayName(uri)
                    val a = c.importer.decode(uri)
                    ChopSource(name, a, a.mono())
                }.getOrNull()
            }
            busy(null)
            if (src == null) vm.message("That file cannot be read.", true)
            _ui.value = _ui.value.copy(source = src)
        }
    }

    fun closeSource() { _ui.value = _ui.value.copy(source = null) }

    fun setSourceSelection(a: Double, b: Double) {
        val s = _ui.value.source ?: return
        _ui.value = _ui.value.copy(source = s.copy(selStart = a, selEnd = b, selectedPiece = null))
    }

    /** Tapping the waveform selects the piece under the finger (when hits are marked). */
    fun tapSource(frac: Double) {
        val s = _ui.value.source ?: return
        if (s.pieceCount == 0) return
        val f = (frac * s.audio.frames).toInt()
        val i = (0 until s.pieceCount).firstOrNull { val (a, b) = s.piece(it); f in a until b } ?: return
        _ui.value = _ui.value.copy(source = s.copy(selectedPiece = if (s.selectedPiece == i) null else i))
    }

    fun detectHits(sensitivity: Double) {
        val s = _ui.value.source ?: return
        _ui.value = _ui.value.copy(source = s.copy(sensitivity = sensitivity))
        hitJob?.cancel()
        hitJob = viewModelScope.launch {
            delay(120)          // the slider re-runs this live; wait until it settles
            val cuts = withContext(Dispatchers.Default) {
                // Only inside the marked region, when there is one.
                val a = (s.selStart * s.audio.frames).toInt()
                val b = (s.selEnd * s.audio.frames).toInt()
                val region = s.mono.copyOfRange(a, b)
                Analysis.detectHits(region, s.audio.rate, sensitivity).map { it + a } +
                    if (a > 0) listOf(a) else emptyList()
            }
            val cur = _ui.value.source ?: return@launch
            val end = (cur.selEnd * cur.audio.frames).toInt()
            _ui.value = _ui.value.copy(source = cur.copy(cuts = (cuts + if (end < cur.audio.frames) listOf(end) else emptyList()).distinct().sorted(),
                selectedPiece = null))
        }
    }

    fun clearHits() {
        val s = _ui.value.source ?: return
        _ui.value = _ui.value.copy(source = s.copy(cuts = emptyList(), selectedPiece = null))
    }

    /** "Del Line": merges the selected piece with the next one. */
    fun mergeSelected() {
        val s = _ui.value.source ?: return
        val i = s.selectedPiece ?: return
        if (i + 1 >= s.pieceCount) return
        val cut = s.piece(i).second
        _ui.value = _ui.value.copy(source = s.copy(cuts = s.cuts.filter { it != cut }))
    }

    fun addMarked(vm: MainViewModel) {
        val s = _ui.value.source ?: return
        val a = Edit.trim(s.audio, s.selStart, s.selEnd)
        add(listOf(ChopEntry(UUID.randomUUID().toString(), if (s.selStart > 0.001 || s.selEnd < 0.999) "${s.name} (trim)" else s.name, a)), vm)
    }

    fun addSelectedPiece(vm: MainViewModel) {
        val s = _ui.value.source ?: return
        val i = s.selectedPiece ?: return
        val (a, b) = s.piece(i)
        add(listOf(ChopEntry(UUID.randomUUID().toString(), "${s.name} – hit ${i + 1}", Edit.microFade(s.audio.slice(a, b), 2.0))), vm)
    }

    fun addAllPieces(vm: MainViewModel) {
        val s = _ui.value.source ?: return
        val pieces = (0 until s.pieceCount).mapNotNull { i ->
            val (a, b) = s.piece(i)
            if (b - a < 16) null else ChopEntry(UUID.randomUUID().toString(), "${s.name} – hit ${i + 1}", Edit.microFade(s.audio.slice(a, b), 2.0))
        }
        add(pieces, vm)
        vm.message("${pieces.size} pieces added to the chop order.")
    }

    fun previewSource(vm: MainViewModel) {
        val s = _ui.value.source ?: return
        val (a, b) = s.selectedPiece?.let { s.piece(it) } ?: ((s.selStart * s.audio.frames).toInt() to (s.selEnd * s.audio.frames).toInt())
        vm.play("chop:source", s.audio.slice(a, b))
    }

    fun previewEntry(e: ChopEntry, vm: MainViewModel) = vm.play("chop:${e.id}", e.audio)

    fun move(index: Int, delta: Int) {
        val l = _ui.value.entries.toMutableList()
        val j = index + delta
        if (index !in l.indices || j !in l.indices) return
        val t = l[index]; l[index] = l[j]; l[j] = t
        _ui.value = _ui.value.copy(entries = l)
    }

    fun remove(index: Int) {
        _ui.value = _ui.value.copy(entries = _ui.value.entries.filterIndexed { i, _ -> i != index })
    }

    fun clearEntries() { _ui.value = _ui.value.copy(entries = emptyList()) }

    fun setSlices(n: Int) {
        _ui.value = _ui.value.copy(slices = n)
        c.settings.update { it.copy(defaultSlices = n) }
    }
    fun setRate(r: Int) { _ui.value = _ui.value.copy(rate = r) }
    fun setStereo(on: Boolean) { _ui.value = _ui.value.copy(stereo = on) }
    fun setNormalize(n: ChopNormalize) { _ui.value = _ui.value.copy(normalize = n) }

    fun build(vm: MainViewModel, onDone: () -> Unit) {
        val u = _ui.value
        if (u.entries.isEmpty()) { vm.message("Add at least one sample first.", true); return }
        viewModelScope.launch {
            busy("Building the multisample …")
            val file = withContext(Dispatchers.Default) {
                runCatching {
                    val out = Chop.build(u.entries.map { it.audio }, u.rate, u.channels, u.slices, u.normalize)
                    val f = File(c.store.root, "chop_${u.slices}slices_${u.rate}Hz_${if (u.stereo) "stereo" else "mono"}_" +
                        UUID.randomUUID().toString().take(8) + ".wav")
                    Wav.writePcm16(out, f)
                    f
                }.getOrNull()
            }
            busy(null)
            if (file == null) { vm.message("The chop sample could not be built.", true); return@launch }
            val used = minOf(u.entries.size, u.slices)
            vm.applyNewFile(ref, file, "Chop ${u.slices}× (${used} sample${if (used != 1) "s" else ""})")
            if (u.entries.size > u.slices) vm.message("Only the first ${u.slices} samples were used.")
            onDone()
        }
    }
}
