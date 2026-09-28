package io.github.pyp6.app.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pyp6.app.ui.MainViewModel
import io.github.pyp6.app.ui.Nav
import io.github.pyp6.app.ui.components.EditWave
import io.github.pyp6.app.ui.components.MutedText
import io.github.pyp6.app.ui.components.PitchControl
import io.github.pyp6.app.ui.components.RateSelector
import io.github.pyp6.app.ui.components.ScreenScaffold
import io.github.pyp6.app.ui.components.Section
import io.github.pyp6.app.ui.components.SwitchRow
import io.github.pyp6.app.ui.theme.LocalPyP6Colors
import io.github.pyp6.core.dsp.StretchMethod
import io.github.pyp6.core.model.PadRef
import java.util.Locale
import kotlin.math.ln
import kotlin.math.exp

@Composable
fun EditorScreen(vm: MainViewModel, nav: Nav, ref: PadRef) {
    val app = LocalContext.current.applicationContext as Application
    val ed: EditorViewModel = viewModel(key = "editor-${ref.label}") { EditorViewModel(app, ref) }
    val u by ed.ui.collectAsStateWithLifecycle()
    val project by vm.project.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val st = project.pad(ref)
    val bankMono = project.bank(ref.bank).forceMono
    var loop by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val playing = playback?.takeIf { it.id == ed.playbackId }

    LaunchedEffect(st?.pitch_cents) { ed.onPitchChanged() }

    ScreenScaffold(
        title = "Edit ${ref.label}",
        subtitle = u.name,
        onBack = { nav.back() },
        actions = {
            TextButton(onClick = { ed.apply(vm) { nav.back() } }, enabled = u.changed && !u.stretching) { Text("Apply") }
        },
    ) { padding ->
        if (u.loading || st == null || u.error != null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (u.error != null || st == null) Text(u.error ?: "This pad is empty.") else CircularProgressIndicator()
            }
            return@ScreenScaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Playhead position: preview plays the selection only, so map it back.
            val playFrac = playing?.position?.let { u.selStart + it * (u.selEnd - u.selStart) }
            Box(Modifier.fillMaxWidth().height(200.dp)) {
                EditWave(
                    mono = u.mono, modifier = Modifier.fillMaxSize(),
                    viewStart = u.viewStart, viewEnd = u.viewEnd,
                    selStart = u.selStart, selEnd = u.selEnd,
                    selectionEnabled = !u.isChop,
                    onSelection = ed::setSelection,
                    overFraction = ed.overFraction(),
                    playFraction = playFrac,
                )
                if (u.stretching) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ZoomIn, "Zoom", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = ln(zoom) / ln(64f), onValueChange = { v -> zoom = exp(v * ln(64f)); ed.setZoom(zoom.toDouble()) },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                IconButton(onClick = { ed.pan(-0.5) }, enabled = u.viewStart > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Scroll left") }
                IconButton(onClick = { ed.pan(0.5) }, enabled = u.viewEnd < 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Scroll right") }
                IconButton(onClick = ed::resetSelection, enabled = u.trimmed) { Icon(Icons.Default.SelectAll, "Select all") }
            }
            val selSeconds = (u.selEnd - u.selStart) * u.seconds
            MutedText(
                if (u.isChop) "Chop multisample: trimming and fades would move the fixed slice boundaries, so only Normalize is available."
                else String.format(Locale.ROOT, "Marked %.3f s of %.3f s · drag the green and red brackets, or drag across the waveform.",
                    selSeconds, u.seconds)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { ed.togglePlay(loop, vm) }, modifier = Modifier.weight(1f)) {
                    Icon(if (playing != null) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(6.dp)); Text(if (playing != null) "Stop" else "Play")
                }
                FilledTonalIconToggleButton(checked = loop, onCheckedChange = { loop = it }) { Icon(Icons.Default.Repeat, "Loop") }
            }

            Section("Level") {
                SwitchRow("Normalize", u.normalize, ed::setNormalize, supporting = "Lift the loudest peak to just under full scale")
                FadeSlider("Fade in", u.fadeIn, !u.isChop, ed::setFadeIn)
                FadeSlider("Fade out", u.fadeOut, !u.isChop, ed::setFadeOut)
            }

            if (!u.isChop) LengthSection(ed, u)

            Section("Pad settings") {
                MutedText("These are the pad's own settings: the preview and the orange length limit follow them.")
                RateSelector(st.target_rate, { vm.setRate(ref, it) })
                PitchControl(st.pitch_cents, { vm.setPitch(ref, it) })
                SwitchRow("Mono", st.mono || bankMono, { vm.setMono(ref, it) }, enabled = !bankMono)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FadeSlider(label: String, value: Double, enabled: Boolean, onChange: (Double) -> Unit) {
    val idx = FADE_STEPS.indices.minBy { kotlin.math.abs(FADE_STEPS[it] - value) }
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (value == 0.0) "off" else String.format(Locale.ROOT, "%.2f s", value), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = idx.toFloat(), onValueChange = { onChange(FADE_STEPS[it.toInt().coerceIn(0, FADE_STEPS.size - 1)]) },
            valueRange = 0f..(FADE_STEPS.size - 1).toFloat(), steps = FADE_STEPS.size - 2, enabled = enabled,
        )
    }
}

@Composable
private fun LengthSection(ed: EditorViewModel, u: EditorUi) {
    val focus = LocalFocusManager.current
    Section("Length without pitch change") {
        val bpmMode = u.showBpm && u.sourceBpm != null
        val shown = if (bpmMode) ed.currentBpm()?.let { String.format(Locale.ROOT, "%.1f", it) } ?: ""
        else String.format(Locale.ROOT, "%.2f", ed.heardSeconds())
        var text by remember(shown) { mutableStateOf(shown) }
        val limit = ed.fitLimitSeconds()
        val stretched = kotlin.math.abs(u.stretchFactor - 1) > 1e-4
        if (u.sourceBpm != null) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(colors = io.github.pyp6.app.ui.components.segColors(), icon = {}, selected = !bpmMode, onClick = { ed.setShowBpm(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Length (s)") }
                SegmentedButton(colors = io.github.pyp6.app.ui.components.segColors(), icon = {}, selected = bpmMode, onClick = { ed.setShowBpm(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Tempo (BPM)") }
            }
        } else MutedText("No steady tempo found in this sample, so the length is set in seconds.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text(if (bpmMode) "BPM" else "Seconds") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    text.replace(',', '.').toDoubleOrNull()?.let(ed::commitLength); focus.clearFocus()
                }),
                isError = limit != null,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { ed.nudge(-0.05) }) { Text("−5%") }
            OutlinedButton(onClick = { ed.nudge(0.05) }) { Text("+5%") }
        }
        if (limit != null) Text(String.format(Locale.ROOT, "Longer than the %.2f s the pad keeps at its rate - the orange part would be cut.", limit),
            color = LocalPyP6Colors.current.over, style = MaterialTheme.typography.bodySmall)
        else if (stretched) MutedText(String.format(Locale.ROOT, "Playing at %.2f× its recorded length.", u.stretchFactor))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = ed::fit, enabled = limit != null, modifier = Modifier.weight(1f)) { Text("Fit") }
            OutlinedButton(onClick = ed::resetLength, enabled = stretched, modifier = Modifier.weight(1f)) { Text("Reset") }
            if (u.octaveAlt != null && u.sourceBpm != null) OutlinedButton(onClick = ed::swapOctave, modifier = Modifier.weight(1f)) {
                Text(if (u.octaveAlt < u.sourceBpm) "½×" else "2×")
            }
        }
        if (u.octaveAlt != null) MutedText(String.format(Locale.ROOT,
            "Could also be read as %.1f BPM. Half against double has no single right answer; the ½×/2× button only changes the number.", u.octaveAlt))
        Text("Method", style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            StretchMethod.entries.forEachIndexed { i, m ->
                SegmentedButton(colors = io.github.pyp6.app.ui.components.segColors(), icon = {}, selected = u.method == m, onClick = { ed.setMethod(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, StretchMethod.entries.size),
                    label = { Text(m.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) })
            }
        }
        MutedText(when (u.method) {
            StretchMethod.AUTO -> "Move pieces when shortening a loop with clear strikes, stretch the spectrum otherwise."
            StretchMethod.SLICES -> "Cuts at the strikes and slides them together: attacks stay intact. Only shortens."
            StretchMethod.VOCODER -> "Rebuilds the sound at the new length: works both ways and on sustained sounds; softens attacks."
        })
        SwitchRow("Keep length while pitching", u.keepLength, ed::setKeepLength,
            supporting = "Pitch is vari-speed; this re-stretches so the length or tempo above stays put")
    }
}
