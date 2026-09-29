package io.github.pyp6.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pyp6.app.ui.MainViewModel
import io.github.pyp6.app.ui.Nav
import io.github.pyp6.app.ui.TopLevel
import io.github.pyp6.app.ui.components.CheckRow
import io.github.pyp6.app.ui.components.ConfirmDialog
import io.github.pyp6.app.ui.components.MutedText
import io.github.pyp6.app.ui.components.ScreenScaffold
import io.github.pyp6.app.ui.components.Section
import io.github.pyp6.app.ui.components.Steps
import io.github.pyp6.app.ui.components.SwitchRow
import io.github.pyp6.app.ui.formatBytes
import io.github.pyp6.app.ui.theme.LocalPyP6Colors
import io.github.pyp6.core.P6
import io.github.pyp6.core.model.PadAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything that talks to the P-6 itself. */
@Composable
fun DeviceScreen(vm: MainViewModel, nav: Nav) {
    val device by vm.device.collectAsStateWithLifecycle()
    val project by vm.project.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val currentBank by vm.currentBank.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::grantDevice) }
    val volumeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.data?.let(vm::grantDevice)
    }
    fun connect() {
        val intent = vm.deviceGrantIntent()
        if (intent != null) volumeLauncher.launch(intent) else treeLauncher.launch(null)
    }

    var sendBanks by remember(project) { mutableStateOf(P6.BANKS.filter { project.bank(it).hasSamples }.toSet()) }
    var includePrm by remember { mutableStateOf(settings.includePrm) }
    var importTarget by remember { mutableStateOf(currentBank) }
    var wipeCount by remember { mutableStateOf<Int?>(null) }
    var confirmSend by remember { mutableStateOf(false) }
    val folderImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let { u -> vm.importBank(importTarget, u) } }

    LaunchedEffect(Unit) { vm.refreshDevice() }

    ScreenScaffold(
        title = "P-6",
        subtitle = when {
            device.reachable -> "Connected · ${device.rootName}"
            device.treeUri != null -> "Not connected"
            else -> "No drive chosen yet"
        },
        topLevel = true,
        actions = { IconButton(onClick = { vm.refreshDevice() }) { Icon(Icons.Default.Refresh, "Check again") } },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionCard(device, onConnect = ::connect, onForget = vm::forgetDevice,
                onStorageSettings = { context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) })

            // --- Banks → P-6
            val bytes by produceState(emptyMap<Char, Long>(), project) {
                value = withContext(Dispatchers.IO) { P6.BANKS.associateWith { PadAudio.bankBytes(project.bank(it)) } }
            }
            Section("Send banks to the P-6") {
                Steps(listOf(
                    "Connect the P-6 over USB and turn it off.",
                    "Hold [●] (REC) and turn it on: it appears as a drive with an IMPORT folder.",
                    "Tick the banks and tap Send. Each pad is converted to its rate, pitch and mono setting.",
                    "Eject the drive, then press a pad key on the P-6 and wait until it is done.",
                ))
                P6.BANKS.chunked(2).forEach { pair ->
                    Row {
                        pair.forEach { b ->
                            val has = project.bank(b).hasSamples
                            CheckRow("$b", b in sendBanks, { on -> sendBanks = if (on) sendBanks + b else sendBanks - b }, enabled = has,
                                trailing = if (has) formatBytes(bytes[b] ?: 0L) else "empty", modifier = Modifier.weight(1f).padding(end = 12.dp))
                        }
                    }
                }
                val total = sendBanks.sumOf { bytes[it] ?: 0L }
                val limit = settings.storageWarningMb * 1024L * 1024L
                val over = sendBanks.filter { (bytes[it] ?: 0L) > limit }
                Text("Total ${formatBytes(total)}", style = MaterialTheme.typography.bodyMedium,
                    color = if (over.isNotEmpty()) LocalPyP6Colors.current.over else MaterialTheme.colorScheme.onSurface)
                if (over.isNotEmpty()) MutedText("Bank ${over.joinToString(", ")} exceed${if (over.size == 1) "s" else ""} the ${settings.storageWarningMb} MB per-upload limit.")
                SwitchRow("Include P-6 settings (.PRM)", includePrm, { includePrm = it },
                    supporting = "Send the pad settings imported from the device along, where they still match the audio")
                Button(onClick = { if (over.isNotEmpty()) confirmSend = true else vm.sendBanks(sendBanks.toList(), includePrm) },
                    enabled = device.hasImport && sendBanks.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Upload, null); Spacer(Modifier.width(8.dp)); Text("Send ${sendBanks.size} bank${if (sendBanks.size != 1) "s" else ""}")
                }
                if (!device.hasImport) MutedText(if (device.reachable) "The drive has no IMPORT folder: start the P-6 in storage mode (hold REC while switching on)."
                    else "Connect the P-6 first.")
            }

            // --- P-6 → bank
            Section("Import a bank from the P-6") {
                Steps(listOf(
                    "Connect the P-6 over USB and turn it off.",
                    "Hold the bank button [A/E]–[D/H] and turn it on. For banks E–H, also hold SAMPLING.",
                    "Wait for the step buttons to light up: the bank is in its EXPORT folder.",
                    "Pick the bank here to put it in, then tap Import. Each sample keeps its .PRM settings.",
                ))
                BankDropdown("Into bank", importTarget, project) { importTarget = it }
                if (project.bank(importTarget).hasSamples) MutedText("Bank $importTarget is replaced (Undo brings it back).")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.importBank(importTarget) }, enabled = device.hasExport, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Import")
                    }
                    OutlinedButton(onClick = { folderImport.launch(null) }, modifier = Modifier.weight(1f)) { Text("From a folder…") }
                }
                if (!device.hasExport && device.reachable) MutedText("No EXPORT folder on the drive yet.")
            }

            Section("Patterns") {
                MutedText("Backing up and restoring the P-6's 64 patterns works through its BACKUP and RESTORE folders.")
                OutlinedButton(onClick = { nav.top(TopLevel.PATTERNS) }, modifier = Modifier.fillMaxWidth()) { Text("Open Patterns") }
            }

            Section("Wipe IMPORT folder") {
                MutedText("Permanently deletes every sample already copied to the P-6's IMPORT folder, across all banks. Your pads in the app stay as they are.")
                OutlinedButton(
                    onClick = { scope.launch { wipeCount = vm.importFileCount() } },
                    enabled = device.hasImport, modifier = Modifier.fillMaxWidth(),
                ) { Text("Wipe…", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmSend) ConfirmDialog(
        "Over the size limit", "Some banks exceed the ${settings.storageWarningMb} MB limit per upload. Send anyway?", "Send",
        onDismiss = { confirmSend = false }, onConfirm = { vm.sendBanks(sendBanks.toList(), includePrm) },
    )
    wipeCount?.let { n ->
        if (n <= 0) {
            vm.message(if (n == 0) "The IMPORT folder is empty." else "The IMPORT folder cannot be read.")
            wipeCount = null
        } else ConfirmDialog(
            "Delete $n sample file${if (n != 1) "s" else ""} from the P-6?",
            "This removes them from the device's IMPORT folder and cannot be undone. Your pads in the app are not affected.",
            "Delete", destructive = true, onDismiss = { wipeCount = null }, onConfirm = vm::wipeImport,
        )
    }
}

@Composable
private fun ConnectionCard(
    device: io.github.pyp6.app.data.DeviceState, onConnect: () -> Unit, onForget: () -> Unit, onStorageSettings: () -> Unit,
) {
    val pc = LocalPyP6Colors.current
    Section("Connection") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (device.reachable) Icons.Default.CheckCircle else Icons.Default.UsbOff, null,
                tint = if (device.reachable) pc.good else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(when {
                    device.reachable -> "P-6 drive connected"
                    device.treeUri != null -> "P-6 drive not connected"
                    else -> "Not set up yet"
                }, style = MaterialTheme.typography.titleSmall)
                if (device.reachable) MutedText(
                    if (device.folders.isEmpty()) "${device.rootName}: none of IMPORT, EXPORT, BACKUP, RESTORE found - is this the P-6?"
                    else "${device.rootName}: " + device.folders.sorted().joinToString(", "))
                else if (device.usbVolumes.isNotEmpty()) MutedText("USB drive present: " + device.usbVolumes.joinToString { it.description } +
                    if (device.treeUri != null) ". If this is the P-6 and it is not picked up, connect it again." else "")
            }
        }
        if (device.treeUri == null) {
            MutedText("The P-6 in storage mode is a USB drive. Plug it into the phone (USB-C cable, or an OTG adapter), start it in " +
                "storage mode, then allow access to the drive once - the P-6 is found by itself from then on.")
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect the P-6 drive") }
            MutedText("In the picker, open the P-6 drive (from the menu if it is not shown) and tap “Use this folder” on its top level.")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onConnect, modifier = Modifier.weight(1f)) { Text("Choose again") }
                TextButton(onClick = onForget, modifier = Modifier.weight(1f)) { Text("Forget") }
            }
        }
        TextButton(onClick = onStorageSettings) { Text("Storage settings (eject the drive)") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankDropdown(label: String, value: Char, project: io.github.pyp6.core.model.Project, onChange: (Char) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = "Bank $value", onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            P6.BANKS.forEach { b ->
                DropdownMenuItem(text = { Text("Bank $b" + if (project.bank(b).hasSamples) "  (has samples)" else "") },
                    onClick = { onChange(b); open = false })
            }
        }
    }
}

