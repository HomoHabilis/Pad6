package io.github.pyp6.app.ui.screens

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pyp6.app.ui.MainViewModel
import io.github.pyp6.app.ui.Nav
import io.github.pyp6.app.ui.Routes
import io.github.pyp6.app.ui.components.MutedText
import io.github.pyp6.app.ui.components.ScreenScaffold
import io.github.pyp6.app.ui.components.Section
import io.github.pyp6.app.ui.components.MorphView
import io.github.pyp6.app.ui.components.FamilyGrid
import io.github.pyp6.core.model.PadRef
import io.github.pyp6.core.wavetable.WaveEntry
import io.github.pyp6.core.wavetable.Wavetable
import java.util.Locale

@Composable
fun SynthScreen(vm: MainViewModel, nav: Nav, ref: PadRef) {
    val app = LocalContext.current.applicationContext as Application
    val sy: SynthViewModel = viewModel(key = "synth-${ref.label}") { SynthViewModel(app, ref) }
    val u by sy.ui.collectAsStateWithLifecycle()
    val pending by sy.pending.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val cyclePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { sy.readCycles(it, vm) }
    val names = sy.activeNames()

    ScreenScaffold(
        title = "Synth → ${ref.label}",
        subtitle = "Wavetable: ${Wavetable.SEGMENTS} waveforms on the START knob",
        onBack = { nav.back() },
        actions = { TextButton(onClick = { sy.build(vm) { nav.back() } }, enabled = u.working == null && names.isNotEmpty()) { Text("Build") } },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Simple", "Advanced").forEachIndexed { i, m ->
                    SegmentedButton(colors = io.github.pyp6.app.ui.components.segColors(), icon = {}, selected = u.mode == m, onClick = { sy.setMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(m) }
                }
            }
            u.working?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            if (u.simple) SimpleSets(sy, u)
            else StepOrder(sy, vm, u, library, onAdd = { adding = true }, onDraw = { nav.to(Routes.DRAW) },
                onImport = { cyclePicker.launch(arrayOf("audio/*")) })

            PitchSection(sy, u)

            val morph = u.morph
            val familyPlaying = morph?.takeIf { playback?.id == sy.familyId(it.name) }
            Section("Table") {
                val counts = Wavetable.splitSteps(names.size)
                var start = 0
                val zones = names.zip(counts).map { (n, cnt) -> Triple(start, start + cnt - 1, n).also { start += cnt } }
                FamilyGrid(zones, Modifier.fillMaxWidth(), selected = morph?.name, playing = familyPlaying?.name,
                    onTap = { sy.previewFamily(it, vm) })
                MutedText("${names.size} famil${if (names.size == 1) "y" else "ies"}, " +
                    (if (counts.isEmpty()) "-" else if (counts.toSet().size == 1) "${counts[0]}" else "${counts.min()}-${counts.max()}") +
                    " steps each, on START from 0 to 254. Tap a family to see and hear its morph. On the P-6: set SIZE to 1, then turn START.")
                val tablePlaying = playback?.id == sy.tableId
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { sy.previewTable(vm) }, enabled = names.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Icon(if (tablePlaying) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp)); Text(if (tablePlaying) "Stop" else "Hear the table")
                    }
                    Button(onClick = { sy.build(vm) { nav.back() } }, enabled = u.working == null && names.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Text("Build")
                    }
                }
            }

            Section("Morph", trailing = {
                morph?.let { Text("${it.name} \u00b7 ${it.steps} steps", style = MaterialTheme.typography.labelLarge) }
            }) {
                MorphView(morph?.shapes ?: emptyList(), Modifier.fillMaxWidth().height(150.dp),
                    highlight = familyPlaying?.let { playback?.position }, placeholder = "Tap a family to see its morph")
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (adding) FamilyPicker(sy, vm, library, u, onDismiss = { adding = false })
    pending?.let { p -> CycleModeDialog(p, sy, vm) }
}

@Composable
private fun SimpleSets(sy: SynthViewModel, u: SynthUi) {
    val sets = remember { Wavetable.simpleSets() + (Wavetable.RANDOMIZE to emptyList()) }
    Section("Waveform set") {
        sets.forEach { (label, fams) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = u.simpleSet == label, onClick = { sy.setSimpleSet(label) }, role = Role.RadioButton),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = u.simpleSet == label, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                MutedText(when {
                    label == Wavetable.RANDOMIZE -> "16 at random"
                    fams.size == 1 -> "${Wavetable.MULTI_MAX} waveforms"
                    else -> "${fams.size} families"
                })
            }
        }
        val fams = sy.simpleFamilies()
        val multi = fams.size == 1 && fams[0] in Wavetable.catalogue.builtinMulti
        HorizontalDivider()
        Text(u.simpleSet, style = MaterialTheme.typography.titleSmall)
        Text(Wavetable.blurb(u.simpleSet, fams.size, multi), style = MaterialTheme.typography.bodyMedium)
        if (u.simpleSet == Wavetable.RANDOMIZE) TextButton(onClick = { sy.setSimpleSet(Wavetable.RANDOMIZE) }) { Text("Roll again") }
        TextButton(onClick = sy::copySimpleToAdvanced) { Text("Edit this set in Advanced") }
    }
}

@Composable
private fun StepOrder(
    sy: SynthViewModel, vm: MainViewModel, u: SynthUi, library: Map<String, WaveEntry>,
    onAdd: () -> Unit, onDraw: () -> Unit, onImport: () -> Unit,
) {
    Section("Step order (${u.selection.size}/${Wavetable.MAX_SELECTED})", trailing = {
        if (u.selection.isNotEmpty()) TextButton(onClick = sy::clearSelection) { Text("Clear") }
    }) {
        if (u.selection.isEmpty()) MutedText("Pick up to 16 families. The table morphs through them in this order; each gets an equal share of the 255 steps.")
        val counts = Wavetable.splitSteps(u.selection.size)
        u.selection.forEachIndexed { i, name ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
                Column(Modifier.weight(1f).clickable { sy.previewFamily(name, vm) }) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MutedText("${counts[i]} steps" + if (sy.isMulti(name, library)) " · multi" else "")
                }
                IconButton(onClick = { sy.previewFamily(name, vm) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.PlayArrow, "Hear $name") }
                IconButton(onClick = { sy.move(i, -1) }, enabled = i > 0, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                IconButton(onClick = { sy.move(i, 1) }, enabled = i < u.selection.size - 1, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                IconButton(onClick = { sy.removeAt(i) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Close, "Remove") }
            }
        }
        u.lastWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
        Button(onClick = onAdd, enabled = u.selection.size < Wavetable.MAX_SELECTED, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add families")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDraw, Modifier.weight(1f)) { Icon(Icons.Default.Brush, null); Spacer(Modifier.width(6.dp)); Text("Draw") }
            OutlinedButton(onClick = onImport, Modifier.weight(1f)) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Import cycles") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PitchSection(sy: SynthViewModel, u: SynthUi) {
    Section("Pitch") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Wavetable.REGISTERS.forEachIndexed { i, r ->
                SegmentedButton(colors = io.github.pyp6.app.ui.components.segColors(), icon = {}, selected = u.register == r.name, onClick = { sy.setRegister(r.name) },
                    shape = SegmentedButtonDefaults.itemShape(i, Wavetable.REGISTERS.size)) { Text(r.name) }
            }
        }
        val reg = Wavetable.register(u.register)
        var open by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            OutlinedTextField(
                value = u.note, onValueChange = {}, readOnly = true, label = { Text("Root note") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Wavetable.notesFor(reg).forEach { n -> DropdownMenuItem(text = { Text(n) }, onClick = { sy.setNote(n); open = false }) }
            }
        }
        val (midi, cycles, up) = sy.pitch()
        val (L, fReal, cents) = Wavetable.tuningInfo(midi, cycles)
        val tooLow = L > Wavetable.MAX_SEG_FRAMES
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Plays cleanly up to", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("+${u.up} st", style = MaterialTheme.typography.bodyLarge)
        }
        Slider(value = u.up.toFloat(), onValueChange = { sy.setUp(it.toInt()) }, valueRange = 0f..Wavetable.DEVICE_MAX_UP.toFloat(),
            steps = Wavetable.DEVICE_MAX_UP - 1)
        if (tooLow) Text("Segment would be $L frames, maximum is ${Wavetable.MAX_SEG_FRAMES}. Pick a higher root note.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        else {
            val (h, _) = Wavetable.harmonicsFor(L, cycles, up)
            val range = Wavetable.playableRange(L, cycles, up)
            MutedText(String.format(Locale.ROOT, "%.2f Hz (%+.1f cents) · %d frames per segment · %d harmonics · alias-free %d semitones up. " +
                "More range means fewer harmonics: a darker sound.", fReal, cents, L, h, range))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FamilyPicker(sy: SynthViewModel, vm: MainViewModel, library: Map<String, WaveEntry>, u: SynthUi, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val groups = remember(library) {
        val builtin = Wavetable.catalogue.groups
        val user = library.values.groupBy { it.group ?: Wavetable.DEFAULT_USER_GROUP }.toSortedMap()
            .map { (g, es) -> "★ $g" to es.map { it.name } }
        user + builtin
    }
    var open by remember { mutableStateOf(setOf<String>()) }
    var deleting by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Text("Add families", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        MutedText("Tap a name to hear it, + to add it to the step order. ★ folders are your own waveforms (long-press to delete).",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            groups.forEach { (g, fams) ->
                item(key = "g:$g") {
                    Row(
                        Modifier.fillMaxWidth().clickable { open = if (g in open) open - g else open + g }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(g, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        MutedText("${fams.size}")
                        Icon(if (g in open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    }
                }
                if (g in open) items(fams, key = { "$g/$it" }) { name ->
                    val inOrder = name in u.selection
                    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).combinedClickableCompat(
                                onClick = { sy.previewFamily(name, vm) },
                                onLongClick = { if (name in library) deleting = name },
                            ).padding(vertical = 12.dp),
                            color = if (inOrder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        IconButton(onClick = { sy.addFamily(name, vm) }, enabled = !inOrder && u.selection.size < Wavetable.MAX_SELECTED) {
                            Icon(Icons.Default.Add, "Add $name")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “$name”?") },
            text = { Text("It is removed from your waveform library. Wavetables already built keep it.") },
            confirmButton = { TextButton(onClick = { vm.deleteWaveEntries(listOf(name)); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CycleModeDialog(p: PendingCycles, sy: SynthViewModel, vm: MainViewModel) {
    var mode by remember { mutableStateOf(Wavetable.CycleMode.Chain) }
    var group by remember { mutableStateOf(Wavetable.DEFAULT_USER_GROUP) }
    AlertDialog(
        onDismissRequest = sy::cancelCycles,
        title = { Text("${p.entries.size} waveform${if (p.entries.size != 1) "s" else ""} read") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                p.tables.forEach { MutedText(it) }
                if (p.skipped.isNotEmpty()) MutedText("Skipped: " + p.skipped.joinToString("; "))
                Spacer(Modifier.height(8.dp))
                Wavetable.CycleMode.entries.forEach { m ->
                    Row(Modifier.fillMaxWidth().selectable(mode == m, onClick = { mode = m }, role = Role.RadioButton).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == m, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(m.name)
                            MutedText(when (m) {
                                Wavetable.CycleMode.Chain -> "1→2, 2→3 … one unbroken sweep through every shape"
                                Wavetable.CycleMode.Pairs -> "1→2, 3→4 … each shape used once"
                                Wavetable.CycleMode.Multi -> "Everything in one family, up to 255; START picks a waveform"
                            })
                        }
                    }
                }
                OutlinedTextField(value = group, onValueChange = { group = it }, singleLine = true, label = { Text("Folder") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { sy.commitCycles(mode, group, vm) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = sy::cancelCycles) { Text("Cancel") } },
    )
}

/** combinedClickable without the experimental opt-in noise at every call site. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))
