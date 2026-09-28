package io.github.pyp6.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pyp6.app.ui.MainViewModel
import io.github.pyp6.app.ui.Nav
import io.github.pyp6.app.ui.components.CheckRow
import io.github.pyp6.app.ui.components.ConfirmDialog
import io.github.pyp6.app.ui.components.MutedText
import io.github.pyp6.app.ui.components.ScreenScaffold
import io.github.pyp6.app.ui.components.Section
import io.github.pyp6.app.ui.components.SwitchRow
import io.github.pyp6.core.P6
import io.github.pyp6.core.io.VNode
import io.github.pyp6.core.pattern.PatternSlot
import io.github.pyp6.core.preset.Presets

/**
 * Presets: banks (samples with their settings and .PRM) and pattern banks,
 * in the same folder layout as the desktop app, so a preset can go back and
 * forth between the phone and a computer.
 */
@Composable
fun PresetsScreen(vm: MainViewModel, nav: Nav) {
    val project by vm.project.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var presets by remember { mutableStateOf<List<Pair<VNode, Presets.Manifest>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload, settings.presetsTreeUri) { presets = vm.listPresets() }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.setPresetsFolder(it); reload++ }
    }
    var name by remember { mutableStateOf("") }
    var saveBanks by remember(project) { mutableStateOf(P6.BANKS.filter { project.bank(it).hasSamples }.toSet()) }
    val loadedPatternBanks = project.patterns.keys.map { it.bank }.toSet()
    var savePatternBanks by remember(project.patterns) { mutableStateOf(loadedPatternBanks) }
    var loading by remember { mutableStateOf<Pair<VNode, Presets.Manifest>?>(null) }
    var deleting by remember { mutableStateOf<VNode?>(null) }
    var confirmOverwrite by remember { mutableStateOf(false) }

    fun doSave() = vm.savePreset(name, saveBanks.sorted(), savePatternBanks.sorted()) { reload++ }

    ScreenScaffold(title = "Presets", subtitle = vm.presetsLocationLabel, onBack = { nav.back() }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Where presets live") {
                MutedText(if (settings.presetsTreeUri == null)
                    "Inside the app. Choose a folder (e.g. Documents, or a cloud folder) to reach them from a computer too."
                else "In the folder you chose. Presets saved by the desktop app there show up below.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FolderOpen, null); Text(" Choose folder")
                    }
                    if (settings.presetsTreeUri != null) TextButton(onClick = { vm.setPresetsFolder(null); reload++ }, modifier = Modifier.weight(1f)) {
                        Text("Use app storage")
                    }
                }
            }

            Section("Save a preset") {
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth())
                Text("Banks", style = MaterialTheme.typography.labelLarge)
                BankChecks(saveBanks, enabled = { project.bank(it).hasSamples }) { b, on -> saveBanks = if (on) saveBanks + b else saveBanks - b }
                Text("Pattern banks", style = MaterialTheme.typography.labelLarge)
                if (loadedPatternBanks.isEmpty()) MutedText("No patterns loaded.")
                else (1..PatternSlot.BANKS).forEach { b ->
                    CheckRow("Patterns $b (${b}-01 … ${b}-16)", b in savePatternBanks, { on -> savePatternBanks = if (on) savePatternBanks + b else savePatternBanks - b },
                        enabled = b in loadedPatternBanks)
                }
                MutedText("Saving over an existing preset replaces only the banks ticked here.")
                Button(
                    onClick = { if (presets?.any { it.first.name == name.trim() } == true) confirmOverwrite = true else doSave() },
                    enabled = name.isNotBlank() && (saveBanks.isNotEmpty() || savePatternBanks.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save") }
            }

            Text("Saved presets", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            val list = presets
            when {
                list == null -> MutedText("Reading …")
                list.isEmpty() -> MutedText("None yet.")
                else -> {
                    val recent = settings.recentPresets
                    list.sortedBy { (d, _) -> recent.indexOf(d.name).let { if (it < 0) Int.MAX_VALUE else it } }.forEach { (dir, m) ->
                        Card(onClick = { loading = dir to m }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(dir.name, style = MaterialTheme.typography.titleSmall)
                                    MutedText("Banks " + m.bankLetters.joinToString(", ").ifEmpty { "-" } +
                                        if (m.patternBanks.isNotEmpty()) " · patterns " + m.patternBanks.sorted().joinToString(", ") else "")
                                }
                                IconButton(onClick = { deleting = dir }) { Icon(Icons.Default.Delete, "Delete ${dir.name}") }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmOverwrite) ConfirmDialog("Update “${name.trim()}”?",
        "The ticked banks are replaced in that preset; the others it holds stay as they are.", "Save",
        onDismiss = { confirmOverwrite = false }, onConfirm = ::doSave)
    deleting?.let { d ->
        ConfirmDialog("Delete “${d.name}”?", "The preset folder and its samples are deleted.", "Delete", destructive = true,
            onDismiss = { deleting = null }, onConfirm = { vm.deletePreset(d) { reload++ } })
    }
    loading?.let { (dir, m) -> LoadDialog(vm, dir, m, onDismiss = { loading = null }) { nav.back() } }
}

@Composable
private fun BankChecks(selected: Set<Char>, enabled: (Char) -> Boolean, onChange: (Char, Boolean) -> Unit) {
    Column {
        P6.BANKS.chunked(4).forEach { row ->
            Row {
                row.forEach { b ->
                    CheckRow("$b", b in selected, { onChange(b, it) }, enabled = enabled(b), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LoadDialog(vm: MainViewModel, dir: VNode, m: Presets.Manifest, onDismiss: () -> Unit, onLoaded: () -> Unit) {
    val current by vm.currentBank.collectAsStateWithLifecycle()
    val project by vm.project.collectAsStateWithLifecycle()
    var banks by remember { mutableStateOf(m.bankLetters.toSet()) }
    var patternBanks by remember { mutableStateOf(m.patternBanks) }
    var intoCurrent by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Load “${dir.name}”") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                m.bankLetters.forEach { b ->
                    CheckRow("Bank $b", b in banks, { on -> banks = if (on) banks + b else banks - b },
                        trailing = "${m.padsIn(b)} pads" + if (project.bank(b).hasSamples) " · replaces" else "")
                }
                m.patternBanks.sorted().forEach { b ->
                    CheckRow("Patterns $b", b in patternBanks, { on -> patternBanks = if (on) patternBanks + b else patternBanks - b })
                }
                if (banks.size == 1) {
                    SwitchRow("Load into another bank", intoCurrent, { intoCurrent = it },
                        supporting = "Put bank ${banks.first()} into the bank chosen below")
                    if (intoCurrent) BankDropdown("Into", target, project) { target = it }
                }
                MutedText("Loading replaces the chosen banks. Undo brings them back.", Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.loadPreset(dir, m, banks.sorted(), patternBanks.sorted(), if (intoCurrent && banks.size == 1) target else null) { onLoaded() }
                onDismiss()
            }, enabled = banks.isNotEmpty() || patternBanks.isNotEmpty()) { Text("Load") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

