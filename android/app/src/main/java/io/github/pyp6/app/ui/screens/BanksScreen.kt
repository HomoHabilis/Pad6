package io.github.pyp6.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pyp6.app.ui.MainViewModel
import io.github.pyp6.app.ui.Nav
import io.github.pyp6.app.ui.Routes
import io.github.pyp6.app.ui.components.WavetableLabel
import io.github.pyp6.app.ui.components.CheckRow
import io.github.pyp6.app.ui.components.ConfirmDialog
import io.github.pyp6.app.ui.components.MiniWave
import io.github.pyp6.app.ui.components.MutedText
import io.github.pyp6.app.ui.components.ScreenScaffold
import io.github.pyp6.app.ui.components.Tag
import io.github.pyp6.app.ui.components.padSettingsLine
import io.github.pyp6.app.ui.components.rememberOverFraction
import io.github.pyp6.app.ui.components.rememberSummary
import io.github.pyp6.app.ui.formatBytes
import io.github.pyp6.app.ui.theme.LocalPyP6Colors
import io.github.pyp6.core.P6
import io.github.pyp6.core.model.PadAudio
import io.github.pyp6.core.model.PadRef
import io.github.pyp6.core.model.PadState
import io.github.pyp6.core.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BanksScreen(vm: MainViewModel, nav: Nav) {
    val project by vm.project.collectAsStateWithLifecycle()
    val bank by vm.currentBank.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var loadTarget by rememberSaveable { mutableStateOf(1) }
    var menu by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf<Boolean?>(null) }     // true = move, false = copy
    var clearing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.loadSamples(PadRef(bank, loadTarget), uris)
    }
    val bankState = project.bank(bank)
    val bytes by produceState(0L, bankState) { value = withContext(Dispatchers.IO) { PadAudio.bankBytes(bankState) } }
    val warnings by produceState(emptyList<String>(), bankState) {
        value = withContext(Dispatchers.IO) {
            P6.PADS.mapNotNull { p ->
                bankState.pad(p)?.let { st -> PadAudio.durationWarning(st, bankState.forceMono || st.mono)?.let { "Pad $p: $it" } }
            }
        }
    }

    ScreenScaffold(
        title = "PyP6",
        subtitle = "Roland P-6 files manager",
        topLevel = true,
        actions = {
            IconButton(onClick = vm::undo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(onClick = vm::redo, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Presets…") }, onClick = { menu = false; nav.to(Routes.PRESETS) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Copy bank $bank to…") }, onClick = { menu = false; transfer = false },
                        enabled = bankState.hasSamples)
                    DropdownMenuItem(text = { Text("Move bank $bank to…") }, onClick = { menu = false; transfer = true },
                        enabled = bankState.hasSamples)
                    DropdownMenuItem(text = { Text("Clear banks…") }, onClick = { menu = false; clearing = true })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; nav.to(Routes.SETTINGS) })
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item(span = { GridItemSpan(2) }) { BankChips(project, bank, vm::selectBank) }
            item(span = { GridItemSpan(2) }) {
                BankBar(
                    forceMono = bankState.forceMono,
                    onForceMono = { vm.setForceMono(bank, it) },
                    bytes = bytes,
                    limitBytes = settings.storageWarningMb * 1024L * 1024L,
                )
            }
            if (warnings.isNotEmpty()) item(span = { GridItemSpan(2) }) { WarningsCard(warnings) }
            items(P6.PADS, key = { "$bank$it" }) { pad ->
                val ref = PadRef(bank, pad)
                val st = bankState.pad(pad)
                if (st == null) EmptyPadCard(
                    pad,
                    onLoad = { loadTarget = pad; picker.launch(arrayOf("audio/*")) },
                    onChop = { nav.to(Routes.chop(ref)) },
                    onSynth = { nav.to(Routes.synth(ref)) },
                )
                else PadCard(
                    ref, st, bankState.forceMono,
                    playFraction = playback?.takeIf { it.id == vm.padPlaybackId(ref) }?.position,
                    onPlay = { vm.togglePad(ref) },
                    onOpen = { nav.to(Routes.pad(ref)) },
                )
            }
        }
    }

    transfer?.let { move ->
        BankPickerDialog(
            title = if (move) "Move bank $bank to" else "Copy bank $bank to",
            exclude = bank, project = project,
            note = if (move) "Bank $bank is emptied; its samples land in the bank you pick, replacing what is there."
            else "The bank you pick is replaced by a copy of bank $bank.",
            onDismiss = { transfer = null },
            onPick = { vm.transferBank(bank, it, move); transfer = null },
        )
    }
    if (clearing) ClearBanksDialog(project, bank, onDismiss = { clearing = false }, onClear = { vm.clearBanks(it); clearing = false })
}

/** A1-H6 banks as eight equal keys; a dot marks banks that hold samples. */
@Composable
private fun BankChips(project: Project, bank: Char, onSelect: (Char) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        P6.BANKS.forEach { b ->
            val has = project.bank(b).hasSamples
            val selected = b == bank
            Surface(
                onClick = { onSelect(b) },
                shape = MaterialTheme.shapes.small,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f).height(48.dp)
                    .semantics { contentDescription = "Bank $b" + if (has) ", has samples" else "" },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(b.toString(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Box(Modifier.padding(top = 2.dp).size(5.dp).background(
                        if (has) (if (selected) MaterialTheme.colorScheme.onPrimary else LocalPyP6Colors.current.good) else Color.Transparent,
                        CircleShape))
                }
            }
        }
    }
}

@Composable
private fun BankBar(forceMono: Boolean, onForceMono: (Boolean) -> Unit, bytes: Long, limitBytes: Long) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val over = bytes > limitBytes
                Text("${formatBytes(bytes)} / ${limitBytes / 1048576} MB", style = MaterialTheme.typography.labelLarge,
                    color = if (over) LocalPyP6Colors.current.over else MaterialTheme.colorScheme.onSurface)
                LinearProgressIndicator(
                    progress = { (bytes.toFloat() / limitBytes).coerceIn(0f, 1f) },
                    color = if (over) LocalPyP6Colors.current.over else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp),
                )
            }
            Text("Force mono", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Switch(checked = forceMono, onCheckedChange = onForceMono)
        }
    }
}

@Composable
private fun WarningsCard(lines: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = LocalPyP6Colors.current.over.copy(alpha = 0.15f))) {
        Row(Modifier.padding(12.dp)) {
            Icon(Icons.Default.Warning, null, tint = LocalPyP6Colors.current.over)
            Spacer(Modifier.width(8.dp))
            Column { lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

/** An empty pad: load a file, or build a multisample (Chop) or a wavetable (Synth) into it. */
@Composable
private fun EmptyPadCard(pad: Int, onLoad: () -> Unit, onChop: () -> Unit, onSynth: () -> Unit) {
    OutlinedCard(
        onClick = onLoad,
        modifier = Modifier.fillMaxWidth().heightIn(min = 148.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 4.dp)) {
            Text("PAD $pad", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                Text("Load sample", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onChop, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) {
                    Icon(Icons.Default.ContentCut, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text("Chop", maxLines = 1)
                }
                TextButton(onClick = onSynth, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) {
                    Icon(Icons.Default.Waves, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text("Synth", maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun PadCard(
    ref: PadRef, st: PadState, bankMono: Boolean, playFraction: Double?,
    onPlay: () -> Unit, onOpen: () -> Unit,
) {
    val summary by rememberSummary(st.filepath)
    val over by rememberOverFraction(st, bankMono)
    val pc = LocalPyP6Colors.current
    val mono = bankMono || st.mono
    val prm = remember(st) { PadAudio.prmStatus(st) }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().heightIn(min = 148.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = if (playFraction != null) BorderStroke(2.dp, pc.playing) else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PAD ${ref.pad}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onPlay, modifier = Modifier.size(40.dp)) {
                    Icon(if (playFraction != null) Icons.Default.Stop else Icons.Default.PlayArrow,
                        if (playFraction != null) "Stop ${ref.label}" else "Play ${ref.label}",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(st.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 6.dp).heightIn(min = 36.dp))
            Spacer(Modifier.height(4.dp))
            if (st.isWavetable) WavetableLabel(Modifier.fillMaxWidth().height(34.dp).padding(end = 6.dp))
            else MiniWave(summary?.envelope, Modifier.fillMaxWidth().height(34.dp).padding(end = 6.dp),
                overFraction = over, playFraction = playFraction)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (st.isWavetable) Tag("WT", pc.good)
                if (mono && !st.isWavetable) Tag("M", pc.mono)
                when (prm) {
                    PadAudio.PrmStatus.KEPT -> if (!st.isWavetable) Tag("PRM", pc.good)
                    PadAudio.PrmStatus.DROPPED -> Tag("PRM", pc.over)
                    PadAudio.PrmStatus.NONE -> {}
                }
                MutedText(padSettingsLine(st, summary), maxLines = 1)
            }
        }
    }
}

@Composable
fun BankPickerDialog(title: String, exclude: Char?, project: Project, note: String?, onDismiss: () -> Unit, onPick: (Char) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (note != null) MutedText(note, Modifier.padding(bottom = 8.dp))
                P6.BANKS.filter { it != exclude }.forEach { b ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(b) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Bank $b", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (project.bank(b).hasSamples) MutedText("has samples")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ClearBanksDialog(project: Project, current: Char, onDismiss: () -> Unit, onClear: (Set<Char>) -> Unit) {
    var chosen by remember { mutableStateOf(setOf(current)) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear banks") },
        text = {
            Column {
                MutedText("Empties the pads in the app only - nothing on the P-6 or in your files is touched. Undo brings them back.",
                    Modifier.padding(bottom = 8.dp))
                P6.BANKS.forEach { b ->
                    CheckRow("Bank $b", b in chosen, { on -> chosen = if (on) chosen + b else chosen - b },
                        trailing = if (project.bank(b).hasSamples) "${project.bank(b).pads.count { it != null }} pads" else "empty")
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onClear(chosen) }, enabled = chosen.isNotEmpty()) { Text("Clear") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

