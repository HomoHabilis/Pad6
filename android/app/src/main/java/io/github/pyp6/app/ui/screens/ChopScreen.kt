package io.github.pyp6.app.ui.screens

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pyp6.app.ui.MainViewModel
import io.github.pyp6.app.ui.Nav
import io.github.pyp6.app.ui.components.EditWave
import io.github.pyp6.app.ui.components.MutedText
import io.github.pyp6.app.ui.components.RateSelector
import io.github.pyp6.app.ui.components.ScreenScaffold
import io.github.pyp6.app.ui.components.Section
import io.github.pyp6.app.ui.components.SwitchRow
import io.github.pyp6.app.ui.theme.LocalPyP6Colors
import io.github.pyp6.core.P6
import io.github.pyp6.core.dsp.ChopNormalize
import io.github.pyp6.core.model.PadRef
import java.util.Locale

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChopScreen(vm: MainViewModel, nav: Nav, ref: PadRef) {
    val app = LocalContext.current.applicationContext as Application
    val ch: ChopViewModel = viewModel(key = "chop-${ref.label}") { ChopViewModel(app, ref) }
    val u by ch.ui.collectAsStateWithLifecycle()
    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { ch.addFiles(it, vm) }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u2 -> ch.openSource(u2, vm) } }

    ScreenScaffold(
        title = "Chop → ${ref.label}",
        subtitle = "${u.entries.size} in the order · ${u.slices} slices",
        onBack = { nav.back() },
        actions = { TextButton(onClick = { ch.build(vm) { nav.back() } }, enabled = u.entries.isNotEmpty() && u.working == null) { Text("Build") } },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MutedText("Combines several short samples into one file for the P-6's Chop function (Sample Edit, Voice). " +
                "Each sample gets one slot of the slice grid; the order here is the slice order on the device. " +
                "Inspired by p6-wave-slice by Warren Blackwell.")
            u.working?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            Section("Add samples") {
                FilledTonalButton(onClick = { filesPicker.launch(arrayOf("audio/*")) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.LibraryAdd, null); Spacer(Modifier.width(6.dp)); Text("Add whole files")
                }
                OutlinedButton(onClick = { sourcePicker.launch(arrayOf("audio/*")) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ContentCut, null); Spacer(Modifier.width(6.dp)); Text("Cut regions or hits from a file")
                }
            }

            u.source?.let { s -> SourceSection(ch, vm, s, u) }

            Section("Chop order", trailing = {
                if (u.entries.isNotEmpty()) TextButton(onClick = ch::clearEntries) { Text("Clear") }
            }) {
                if (u.entries.isEmpty()) MutedText("Nothing yet. Add whole files, or open a file and add regions or detected hits.")
                u.entries.forEachIndexed { i, e ->
                    val over = e.seconds > u.slotSeconds
                    val unused = i >= u.slices
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(26.dp),
                            color = if (unused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).clickable { ch.previewEntry(e, vm) }) {
                            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                                color = if (unused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            Text(String.format(Locale.ROOT, "%.3f s%s", e.seconds,
                                when { unused -> " · beyond the slice count, not used"; over -> " · longer than the slot, will be cut"; else -> "" }),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (over && !unused) LocalPyP6Colors.current.over else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { ch.move(i, -1) }, enabled = i > 0, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                        IconButton(onClick = { ch.move(i, 1) }, enabled = i < u.entries.size - 1, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                        IconButton(onClick = { ch.remove(i) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Delete, "Remove") }
                    }
                    if (i < u.entries.size - 1) HorizontalDivider()
                }
            }

            Section("Output") {
                Text("Slices", style = MaterialTheme.typography.bodyLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    P6.SLICE_COUNTS.forEach { n -> FilterChip(selected = u.slices == n, onClick = { ch.setSlices(n) }, label = { Text("$n") }) }
                }
                RateSelector(u.rate, ch::setRate)
                SwitchRow("Stereo", u.stereo, ch::setStereo, supporting = "Off mixes every sample to mono, which doubles the time available")
                Text("Normalize", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ChopNormalize.entries.forEachIndexed { i, n ->
                        SegmentedButton(colors = io.github.pyp6.app.ui.components.segColors(), icon = {}, selected = u.normalize == n, onClick = { ch.setNormalize(n) },
                            shape = SegmentedButtonDefaults.itemShape(i, ChopNormalize.entries.size), label = { Text(n.label, maxLines = 1) })
                    }
                }
                MutedText(String.format(Locale.ROOT, "%d slices at %s %s: %.0f ms each, %.2f s in total. Leading silence is trimmed; " +
                    "shorter samples are padded, longer ones cut at a zero crossing.",
                    u.slices, io.github.pyp6.app.ui.components.rateLabel(u.rate), if (u.stereo) "stereo" else "mono",
                    u.slotSeconds * 1000, u.totalSeconds))
                Button(onClick = { ch.build(vm) { nav.back() } }, enabled = u.entries.isNotEmpty() && u.working == null,
                    modifier = Modifier.fillMaxWidth()) { Text("Build multisample onto ${ref.label}") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SourceSection(ch: ChopViewModel, vm: MainViewModel, s: ChopSource, u: ChopUi) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    val pc = LocalPyP6Colors.current
    Section(s.name, trailing = { IconButton(onClick = ch::closeSource) { Icon(Icons.Default.Close, "Close file") } }) {
        val frames = s.audio.frames.toDouble()
        val highlight = s.selectedPiece?.let { i -> val (a, b) = s.piece(i); a / frames to b / frames }
        val playFrac = playback?.takeIf { it.id == "chop:source" }?.let { p ->
            val (a, b) = s.selectedPiece?.let { s.piece(it) } ?: ((s.selStart * frames).toInt() to (s.selEnd * frames).toInt())
            (a + p.position * (b - a)) / frames
        }
        Box(Modifier.fillMaxWidth().height(160.dp)) {
            EditWave(
                s.mono, Modifier.fillMaxSize(), selStart = s.selStart, selEnd = s.selEnd,
                onSelection = ch::setSourceSelection, cuts = s.cuts.map { it / frames },
                highlight = highlight, playFraction = playFrac, onTap = ch::tapSource,
            )
        }
        val marked = (s.selEnd - s.selStart) * s.audio.durationSeconds
        val slot = u.slotSeconds
        Text(String.format(Locale.ROOT, "Marked %.3f s (slot holds %.3f s)", marked, slot), style = MaterialTheme.typography.bodySmall,
            color = if (marked > slot) pc.over else MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ch.previewSource(vm) }, Modifier.weight(1f)) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(if (s.selectedPiece != null) "Piece" else "Marked")
            }
            FilledTonalButton(onClick = { ch.addMarked(vm) }, Modifier.weight(1f)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add marked")
            }
        }
        HorizontalDivider()
        Text("Detect hits", style = MaterialTheme.typography.bodyLarge)
        MutedText("Finds the strikes in a rhythm loop (inside the marked part) and cuts before each one. Tap a piece to pick it.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Fewer", style = MaterialTheme.typography.bodySmall)
            Slider(value = s.sensitivity.toFloat(), onValueChange = { ch.detectHits(it.toDouble()) }, valueRange = 0.2f..4f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            Text("More", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { ch.detectHits(s.sensitivity) }, Modifier.weight(1f)) { Text("Detect") }
            OutlinedButton(onClick = ch::clearHits, enabled = s.cuts.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Clear lines") }
        }
        if (s.pieceCount > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ch.addAllPieces(vm) }, Modifier.weight(1f)) { Text("Add ${s.pieceCount} pieces") }
                OutlinedButton(onClick = { ch.addSelectedPiece(vm) }, enabled = s.selectedPiece != null, modifier = Modifier.weight(1f)) { Text("Add piece") }
            }
            OutlinedButton(onClick = ch::mergeSelected, enabled = s.selectedPiece != null && s.selectedPiece < s.pieceCount - 1,
                modifier = Modifier.fillMaxWidth()) { Text("Merge piece with the next (delete line)") }
        }
    }
}
