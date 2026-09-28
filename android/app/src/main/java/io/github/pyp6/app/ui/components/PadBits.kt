package io.github.pyp6.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import io.github.pyp6.app.data.WaveCache
import io.github.pyp6.app.data.WaveSummary
import io.github.pyp6.core.model.PadAudio
import io.github.pyp6.core.model.PadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** The file summary for a pad, loaded off the main thread. */
@Composable
fun rememberSummary(path: String?): State<WaveSummary?> =
    produceState(initialValue = path?.let { WaveCache.peek(it) }, path) {
        value = path?.let { withContext(Dispatchers.IO) { WaveCache.get(it) } }
    }

/** Where the length limit cuts the pad's sample, or null when it fits. */
@Composable
fun rememberOverFraction(state: PadState?, forceMono: Boolean): State<Double?> =
    produceState<Double?>(null, state?.filepath, state?.target_rate, state?.pitch_cents, forceMono, state?.mono) {
        value = if (state == null || state.isWavetable) null else withContext(Dispatchers.IO) {
            PadAudio.truncateFraction(File(state.filepath), state.target_rate, state.pitch_cents, forceMono || state.mono)
        }
    }

fun padSettingsLine(state: PadState, summary: WaveSummary?): String {
    if (state.isWavetable) {
        val wt = state.wavetable!!
        return "${wt.config.register} · ${wt.config.note} · ${wt.meta.families.size} fam."
    }
    val parts = ArrayList<String>()
    parts.add(rateLabel(state.target_rate))
    if (state.pitch_cents != 0) parts.add(String.format(Locale.ROOT, "%+dc", state.pitch_cents))
    summary?.let {
        val heard = it.durationSeconds / io.github.pyp6.core.dsp.Edit.pitchSpeedFactor(state.pitch_cents)
        parts.add(String.format(Locale.ROOT, "%.2fs", heard))
    }
    return parts.joinToString(" · ")
}
