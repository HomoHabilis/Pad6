package io.github.pyp6.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.pyp6.app.PyP6Application
import io.github.pyp6.core.audio.Audio
import io.github.pyp6.core.audio.Wav
import io.github.pyp6.core.dsp.Analysis
import io.github.pyp6.core.dsp.Edit
import io.github.pyp6.core.dsp.Stretch
import io.github.pyp6.core.dsp.StretchMethod
import io.github.pyp6.core.model.PadAudio
import io.github.pyp6.core.model.PadRef
import io.github.pyp6.core.model.PadState
import io.github.pyp6.core.P6
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

val FADE_STEPS = listOf(0.0, 0.01, 0.02, 0.05, 0.1, 0.15, 0.2, 0.3, 0.5, 0.7, 1.0)

data class EditorUi(
    val loading: Boolean = true,
    val error: String? = null,
    val name: String = "",
    val rate: Int = 44100,
    val channels: Int = 1,
    /** The audio being edited (after any length change), mixed to mono for drawing. */
    val mono: FloatArray = FloatArray(0),
    val frames: Int = 0,
    val selStart: Double = 0.0,
    val selEnd: Double = 1.0,
    val viewStart: Double = 0.0,
    val viewEnd: Double = 1.0,
    val normalize: Boolean = false,
    val fadeIn: Double = 0.0,
    val fadeOut: Double = 0.0,
    val stretchFactor: Double = 1.0,
    val stretching: Boolean = false,
    val method: StretchMethod = StretchMethod.AUTO,
    val sourceBpm: Double? = null,
    val octaveAlt: Double? = null,
    val sourceSeconds: Double = 0.0,
    val showBpm: Boolean = false,
    val keepLength: Boolean = false,
    val isChop: Boolean = false,
) {
    val seconds: Double get() = if (rate > 0) frames.toDouble() / rate else 0.0
    val trimmed: Boolean get() = selStart > 0.001 || selEnd < 0.999
    val changed: Boolean
        get() = (!isChop && (trimmed || fadeIn > 0 || fadeOut > 0 || kotlin.math.abs(stretchFactor - 1) > 1e-4)) || normalize
}

/**
 * The waveform editor (the desktop app's PadWaveformViewDialog): trim, fade,
 * normalize and length/tempo changes without pitch shift, previewed with the
 * pad's own rate/pitch/mono and applied as a new file.
 */
class EditorViewModel(app: Application, val ref: PadRef) : AndroidViewModel(app) {
    private val c = (app as PyP6Application).container
    private val _ui = MutableStateFlow(EditorUi(method = c.settings.value.stretchMethod))
    val ui: StateFlow<EditorUi> = _ui.asStateFlow()

    private var source: Audio? = null          // untouched file
    private var current: Audio? = null         // after length change
    private var target: Pair<String, Double>? = null      // ("length", s) or ("bpm", v) the user pinned
    private var stretchJob: Job? = null

    val playbackId = "editor:${ref.label}"

    init {
        viewModelScope.launch {
            val st = c.repository.value.pad(ref)
            if (st == null) {
                _ui.value = _ui.value.copy(loading = false, error = "This pad is empty."); return@launch
            }
            try {
                val a = withContext(Dispatchers.IO) { Wav.read(File(st.filepath)) }
                source = a; current = a
                val mono = a.mono()
                _ui.value = _ui.value.copy(
                    loading = false, name = st.name, rate = a.rate, channels = a.channelCount,
                    mono = mono, frames = a.frames, sourceSeconds = a.durationSeconds,
                    isChop = File(st.filepath).name.lowercase().startsWith("chop_"),
                )
                val (bpm, alt) = withContext(Dispatchers.Default) {
                    val b = runCatching { Analysis.detectBpm(mono, a.rate) }.getOrNull()
                    b to b?.let { runCatching { Analysis.bpmOctaveAlternative(mono, a.rate, it) }.getOrNull() }
                }
                _ui.value = _ui.value.copy(sourceBpm = bpm, octaveAlt = alt)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = "Could not read the sample: ${e.message}")
            }
        }
    }

    private fun pad(): PadState? = c.repository.value.pad(ref)
    private fun padMono() = c.repository.value.effectiveMono(ref)

    fun setSelection(a: Double, b: Double) {
        _ui.value = _ui.value.copy(selStart = a.coerceIn(0.0, 1.0), selEnd = b.coerceIn(0.0, 1.0))
    }

    fun resetSelection() = setSelection(0.0, 1.0)

    /** Zoom 1x..64x, centred on the selection (or on what is in view). */
    fun setZoom(zoom: Double) {
        val u = _ui.value
        val span = (1.0 / zoom.coerceIn(1.0, 64.0)).coerceAtMost(1.0)
        val centre = if (u.trimmed) (u.selStart + u.selEnd) / 2 else (u.viewStart + u.viewEnd) / 2
        var vs = centre - span / 2
        vs = vs.coerceIn(0.0, 1.0 - span)
        _ui.value = u.copy(viewStart = vs, viewEnd = vs + span)
    }

    fun pan(delta: Double) {
        val u = _ui.value
        val span = u.viewEnd - u.viewStart
        val vs = (u.viewStart + delta * span).coerceIn(0.0, 1.0 - span)
        _ui.value = u.copy(viewStart = vs, viewEnd = vs + span)
    }

    fun setNormalize(on: Boolean) { _ui.value = _ui.value.copy(normalize = on) }
    fun setFadeIn(s: Double) { _ui.value = _ui.value.copy(fadeIn = s) }
    fun setFadeOut(s: Double) { _ui.value = _ui.value.copy(fadeOut = s) }
    fun setMethod(m: StretchMethod) {
        _ui.value = _ui.value.copy(method = m)
        c.settings.update { it.copy(stretchMethod = m) }
        if (kotlin.math.abs(_ui.value.stretchFactor - 1) > 1e-4) applyStretch(_ui.value.stretchFactor)
    }
    fun setShowBpm(on: Boolean) { _ui.value = _ui.value.copy(showBpm = on && _ui.value.sourceBpm != null) }

    private fun pitchFactor(): Double = Edit.pitchSpeedFactor(pad()?.pitch_cents ?: 0)

    /** Length as it sounds on the pad: after stretching and (vari-speed) pitch. */
    fun heardSeconds(): Double = _ui.value.seconds / pitchFactor()

    fun currentBpm(): Double? = _ui.value.sourceBpm?.let { it / max(_ui.value.stretchFactor, 1e-6) * pitchFactor() }

    /** The seconds the pad may hold at its settings, or null when it fits. */
    fun fitLimitSeconds(): Double? {
        val st = pad() ?: return null
        val u = _ui.value
        val ch = if (padMono() || u.channels == 1) 1 else 2
        val limit = P6.maxSeconds(st.target_rate, ch) ?: return null
        val heard = heardSeconds()
        return if (heard > limit) limit else null
    }

    /** Where the device's limit cuts the waveform, as a fraction of it. */
    fun overFraction(): Double? {
        val st = pad() ?: return null
        val u = _ui.value
        if (u.frames == 0) return null
        val ch = if (padMono() || u.channels == 1) 1 else 2
        val limit = P6.maxSeconds(st.target_rate, ch) ?: return null
        val limitOriginal = limit * pitchFactor()
        return if (limitOriginal >= u.seconds) null else limitOriginal / u.seconds
    }

    /** Renders the new length from the untouched source (never re-stretching a stretch). */
    fun applyStretch(factor: Double) {
        val src = source ?: return
        val f = factor.coerceIn(1.0 / Stretch.MAX_RATIO, Stretch.MAX_RATIO)
        c.player.stop()
        stretchJob?.cancel()
        if (kotlin.math.abs(f - 1.0) < 1e-4) {
            current = src
            _ui.value = _ui.value.copy(mono = src.mono(), frames = src.frames, stretchFactor = 1.0, stretching = false,
                viewStart = 0.0, viewEnd = 1.0, selStart = 0.0, selEnd = 1.0)
            return
        }
        _ui.value = _ui.value.copy(stretching = true)
        val method = _ui.value.method
        stretchJob = viewModelScope.launch {
            val out = withContext(Dispatchers.Default) { runCatching { Stretch.stretch(src, f, method) }.getOrNull() }
            if (out == null) {
                _ui.value = _ui.value.copy(stretching = false); return@launch
            }
            current = out
            _ui.value = _ui.value.copy(mono = out.mono(), frames = out.frames, stretchFactor = f, stretching = false,
                viewStart = 0.0, viewEnd = 1.0, selStart = 0.0, selEnd = 1.0)
        }
    }

    private fun pin(kind: String, value: Double) { target = kind to value }

    /** A typed length in seconds (as heard) or a tempo. */
    fun commitLength(value: Double) {
        if (value <= 0) return
        val u = _ui.value
        val pf = pitchFactor()
        val factor = if (u.showBpm && u.sourceBpm != null) {
            pin("bpm", value); u.sourceBpm * pf / value
        } else {
            pin("length", value); value * pf / max(u.sourceSeconds, 1e-6)
        }
        if (kotlin.math.abs(factor - u.stretchFactor) > 1e-4) applyStretch(factor)
    }

    /** +/- 5 %: longer/shorter in Length, faster/slower in BPM. */
    fun nudge(delta: Double) {
        val u = _ui.value
        val d = if (u.showBpm && u.sourceBpm != null) -delta else delta
        applyStretch(u.stretchFactor + d)
        rememberTarget()
    }

    fun fit() {
        val limit = fitLimitSeconds() ?: return
        val u = _ui.value
        applyStretch(u.stretchFactor * (limit * 0.998) / max(heardSeconds(), 1e-6))
        target = "length" to limit * 0.998
    }

    fun resetLength() {
        target = null
        applyStretch(1.0)
    }

    /** Reads the same audio as the other octave: only the number changes. */
    fun swapOctave() {
        val u = _ui.value
        val alt = u.octaveAlt ?: return
        val old = u.sourceBpm ?: return
        _ui.value = u.copy(sourceBpm = alt, octaveAlt = old)
    }

    private fun rememberTarget() {
        val u = _ui.value
        target = if (u.showBpm && u.sourceBpm != null) currentBpm()?.let { "bpm" to it } else "length" to heardSeconds()
    }

    /** Keep length while pitching: re-stretch so the pinned length/tempo survives a pitch change. */
    fun setKeepLength(on: Boolean) {
        _ui.value = _ui.value.copy(keepLength = on)
        if (on && target == null) rememberTarget()
        onPitchChanged()
    }

    fun onPitchChanged() {
        val u = _ui.value
        if (!u.keepLength) return
        val pf = pitchFactor()
        val t = target
        val want = when {
            t == null -> pf
            t.first == "bpm" && u.sourceBpm != null -> u.sourceBpm * pf / t.second
            else -> t.second * pf / max(u.sourceSeconds, 1e-6)
        }
        if (kotlin.math.abs(want - u.stretchFactor) > 1e-4) applyStretch(want)
    }

    /** The edits applied to the current audio (not yet the pad's rate/pitch). */
    private fun edited(forPreview: Boolean): Audio? {
        var a = current ?: return null
        val u = _ui.value
        if (!u.isChop && u.trimmed) a = if (forPreview) a.slice((u.selStart * a.frames).toInt(), (u.selEnd * a.frames).toInt())
        else Edit.trim(a, u.selStart, u.selEnd)
        if (u.normalize) a = Edit.normalize(a)
        if (!u.isChop && (u.fadeIn > 0 || u.fadeOut > 0)) a = Edit.fades(a, u.fadeIn, u.fadeOut)
        return a
    }

    /** Plays the edited sample as the pad will sound (rate, pitch, mono). */
    fun togglePlay(loop: Boolean, vm: io.github.pyp6.app.ui.MainViewModel) {
        if (c.player.isPlaying(playbackId)) { c.player.stop(); return }
        val st = pad() ?: return
        viewModelScope.launch {
            val audio = withContext(Dispatchers.Default) {
                runCatching {
                    var a = edited(true) ?: return@runCatching null
                    if (padMono() && a.channelCount > 1) a = a.toMono()
                    Edit.pitchAndResample(a, st.pitch_cents, st.target_rate)
                }.getOrNull()
            } ?: return@launch
            vm.play(playbackId, audio, loop)
        }
    }

    /** Writes the result as a new file and puts it on the pad (one undo step). */
    fun apply(vm: io.github.pyp6.app.ui.MainViewModel, onDone: () -> Unit) {
        val u = _ui.value
        if (!u.changed) { onDone(); return }
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val a = edited(false)!!
                    c.store.write(a, u.name, "edit")
                }.getOrNull()
            }
            if (file == null) { vm.message("Could not write the edited sample.", error = true); return@launch }
            val suffix = buildString {
                if (!u.isChop && u.trimmed) append(" (trim)")
                if (!u.isChop && kotlin.math.abs(u.stretchFactor - 1) > 1e-4) append(" (fit)")
                if (u.normalize) append(" (normalized)")
                if (!u.isChop && (u.fadeIn > 0 || u.fadeOut > 0)) append(" (fade)")
            }
            val base = u.name.replace(Regex("""( \((trim|fit|normalized|fade)\))+$"""), "")
            // A chopped sample keeps its "chop_" name so it is still recognised as one.
            val final = if (u.isChop) File(file.parentFile, "chop_" + file.name).also { file.renameTo(it) } else file
            vm.applyEditedFile(ref, final, base + suffix)
            onDone()
        }
    }

    override fun onCleared() {
        if (c.player.isPlaying(playbackId)) c.player.stop()
        super.onCleared()
    }
}
