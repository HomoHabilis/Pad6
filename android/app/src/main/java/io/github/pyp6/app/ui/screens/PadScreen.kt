package io.github.pyp6.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pyp6.app.ui.MainViewModel
import io.github.pyp6.app.ui.Nav
import io.github.pyp6.app.ui.Routes
import io.github.pyp6.app.ui.components.WavetableLabel
import io.github.pyp6.app.ui.components.CheckRow
import io.github.pyp6.app.ui.components.MiniWave
import io.github.pyp6.app.ui.components.MutedText
import io.github.pyp6.app.ui.components.PitchControl
import io.github.pyp6.app.ui.components.RateSelector
import io.github.pyp6.app.ui.components.ScreenScaffold
import io.github.pyp6.app.ui.components.Section
import io.github.pyp6.app.ui.components.SwitchRow
import io.github.pyp6.app.ui.components.FamilyGrid
import io.github.pyp6.app.ui.components.rememberOverFraction
import io.github.pyp6.app.ui.components.rememberSummary
import io.github.pyp6.app.ui.formatBytes
import io.github.pyp6.app.ui.theme.LocalPyP6Colors
import io.github.pyp6.core.P6
import io.github.pyp6.core.model.PadAudio
import io.github.pyp6.core.model.PadRef
import io.github.pyp6.core.prm.Prm
import io.github.pyp6.core.wavetable.Wavetable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** One pad: its sample, how it goes to the P-6, and everything that can be done with it. */
@Composable
fun PadScreen(vm: MainViewModel, nav: Nav, ref: PadRef) {
    val project by vm.project.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val st = project.pad(ref)
    val bankMono = project.bank(ref.bank).forceMono
    val scope = rememberCoroutineScope()
    var swapping by remember { mutableStateOf(false) }
    var ejecting by remember { mutableStateOf<List<String>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.loadSamples(ref, uris)
    }
    val playing = playback?.takeIf { it.id == vm.padPlaybackId(ref) }

    ScreenScaffold(
        title = "Bank ${ref.bank} · Pad ${ref.pad}",
        subtitle = st?.name ?: "Empty",
        onBack = { nav.back() },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (st == null) {
                Section("Empty pad") {
                    MutedText("Load a WAV, MP3, FLAC, M4A or OGG file. Several files fill this pad and the ones after it. " +
                        "Or build a multisample with Chop, or a wavetable with Synth.")
                    Button(onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Load sample")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { nav.to(Routes.chop(ref)) }, Modifier.weight(1f)) {
                            Icon(Icons.Default.ContentCut, null); Spacer(Modifier.width(6.dp)); Text("Chop")
                        }
                        OutlinedButton(onClick = { nav.to(Routes.synth(ref)) }, Modifier.weight(1f)) {
                            Icon(Icons.Default.Waves, null); Spacer(Modifier.width(6.dp)); Text("Synth")
                        }
                    }
                }
                return@Column
            }
            val summary by rememberSummary(st.filepath)
            val over by rememberOverFraction(st, bankMono)
            val warning by produceState<String?>(null, st, bankMono) {
                value = withContext(Dispatchers.IO) { PadAudio.durationWarning(st, bankMono || st.mono) }
            }
            val exportBytes by produceState(0L, st, bankMono) {
                value = withContext(Dispatchers.IO) { PadAudio.estimatedExportBytes(st, bankMono || st.mono) }
            }

            // --- waveform and play
            Section(null) {
                Box(Modifier.fillMaxWidth().height(96.dp).clickable(enabled = !st.isWavetable) { nav.to(Routes.editor(ref)) }) {
                    if (st.isWavetable) WavetableLabel(Modifier.fillMaxSize())
                    else MiniWave(summary?.envelope, Modifier.fillMaxSize(), overFraction = over, playFraction = playing?.position)
                }
                summary?.let { s ->
                    MutedText(String.format(Locale.ROOT, "%s · %d Hz · %s · %.2f s · %s on the P-6",
                        if (s.channels == 1) "Mono" else "Stereo", s.rate, formatBytes(s.bytes), s.durationSeconds, formatBytes(exportBytes)))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.togglePad(ref) }, modifier = Modifier.weight(1f)) {
                        Icon(if (playing != null) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp)); Text(if (playing != null) "Stop" else "Play")
                    }
                    FilledTonalButton(onClick = { nav.to(Routes.editor(ref)) }, enabled = !st.isWavetable, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Edit")
                    }
                }
                if (warning != null) Text(warning!!, color = LocalPyP6Colors.current.over, style = MaterialTheme.typography.bodySmall)
            }

            if (st.isWavetable) WavetableCard(vm, ref, st.wt_patch, st.wt_poly, st.wavetable!!) { nav.to(Routes.synth(ref)) }
            else {
                // --- how it goes to the device
                Section("Sound on the P-6") {
                    RateSelector(st.target_rate, { vm.setRate(ref, it) })
                    PitchControl(st.pitch_cents, { vm.setPitch(ref, it) })
                    SwitchRow("Mono", st.mono || bankMono, { vm.setMono(ref, it) }, enabled = !bankMono,
                        supporting = if (bankMono) "Force mono is on for bank ${ref.bank}" else "Mix this pad to mono on export")
                    val prm = PadAudio.prmStatus(st)
                    if (prm != PadAudio.PrmStatus.NONE) MutedText(
                        if (prm == PadAudio.PrmStatus.KEPT) "P-6 settings (.PRM) from the device go along with this sample."
                        else "The .PRM settings are left out: a changed rate or pitch no longer matches their frame positions."
                    )
                }
            }

            // --- other actions
            Section("Pad") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, Modifier.weight(1f)) {
                        Text("Replace")
                    }
                    OutlinedButton(onClick = { swapping = true }, Modifier.weight(1f)) {
                        Text("Swap with\u2026")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.to(Routes.chop(ref)) }, Modifier.weight(1f)) {
                        Text("Chop")
                    }
                    if (!st.isWavetable) OutlinedButton(onClick = { nav.to(Routes.synth(ref)) }, Modifier.weight(1f)) {
                        Text("Synth")
                    }
                }
                OutlinedButton(
                    onClick = {
                        scope.launch { ejecting = if (device.reachable) vm.deviceFilesFor(ref) else emptyList() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Default.Eject, null); Spacer(Modifier.width(6.dp)); Text("Eject (clear pad)") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (swapping) PadPickerDialog(
        title = "Swap ${ref.label} with",
        exclude = ref,
        project = project,
        onDismiss = { swapping = false },
        onPick = { vm.swapPads(ref, it); swapping = false },
    )
    ejecting?.let { files ->
        var alsoDevice by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { ejecting = null },
            title = { Text("Clear ${ref.label}?") },
            text = {
                Column {
                    Text("The pad is emptied in the app. Undo brings it back.")
                    if (files.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("The P-6 already holds ${files.joinToString(", ")} for this pad.")
                        CheckRow("Also delete from the P-6", alsoDevice, { alsoDevice = it })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.ejectPad(ref, alsoDevice); ejecting = null; nav.back() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { ejecting = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WavetableCard(
    vm: MainViewModel, ref: PadRef, patch: String, poly: Boolean,
    wt: io.github.pyp6.core.model.WavetableState, onRebuild: () -> Unit,
) {
    Section("Wavetable") {
        Wavetable.summary(wt.config.register, wt.config.note, wt.config.up, wt.meta).forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        FamilyGrid(Wavetable.zones(wt.meta), Modifier.fillMaxWidth())
        MutedText("On the P-6: set SIZE to 1, then turn START to step through the ${Wavetable.SEGMENTS} waveforms.")
        var open by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            OutlinedTextField(
                value = patch, onValueChange = {}, readOnly = true, label = { Text("Init patch") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Prm.TEMPLATES.keys.forEach { t ->
                    DropdownMenuItem(text = { Text(t) }, onClick = { vm.setWavetableVoice(ref, patch = t); open = false })
                }
            }
        }
        SwitchRow("Poly", poly, { vm.setWavetableVoice(ref, poly = it) }, supporting = "Play chords on this pad")
        FilledTonalButton(onClick = onRebuild, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.GraphicEq, null); Spacer(Modifier.width(6.dp)); Text("Rebuild in Synth")
        }
    }
}

/** Pick any pad of any bank. */
@Composable
fun PadPickerDialog(
    title: String, exclude: PadRef?, project: io.github.pyp6.core.model.Project,
    onDismiss: () -> Unit, onPick: (PadRef) -> Unit,
) {
    var bank by remember { mutableStateOf(exclude?.bank ?: 'A') }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    P6.BANKS.forEach { b ->
                        TextButton(onClick = { bank = b }, modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text(b.toString(), color = if (b == bank) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = if (b == bank) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                P6.PADS.forEach { p ->
                    val r = PadRef(bank, p)
                    val s = project.pad(r)
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = r != exclude) { onPick(r) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${r.label}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(40.dp))
                        Text(if (r == exclude) "(this pad)" else s?.name ?: "empty", maxLines = 1,
                            color = if (s == null || r == exclude) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

