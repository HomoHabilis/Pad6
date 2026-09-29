package io.github.pyp6.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pyp6.app.ui.MainViewModel
import io.github.pyp6.app.ui.Nav
import io.github.pyp6.app.ui.components.ConfirmDialog
import io.github.pyp6.app.ui.components.MutedText
import io.github.pyp6.app.ui.components.ScreenScaffold
import io.github.pyp6.app.ui.components.Section
import io.github.pyp6.app.ui.formatBytes
import io.github.pyp6.app.ui.theme.PALETTES
import io.github.pyp6.app.ui.theme.THEME_SYSTEM
import io.github.pyp6.core.wavetable.WaveLibrary
import io.github.pyp6.core.wavetable.Wavetable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, nav: Nav) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var refresh by remember { mutableLongStateOf(0L) }
    val storage by produceState(0L, refresh) { value = withContext(Dispatchers.IO) { vm.storageBytes() } }
    var confirmClean by remember { mutableStateOf(false) }
    val packImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importWavePack) }
    var exportGroup by remember { mutableStateOf<String?>(null) }
    val packExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val g = exportGroup
        if (uri != null && g != null) vm.exportWavePack(g, uri)
    }
    val groups = library.values.map { it.group ?: Wavetable.DEFAULT_USER_GROUP }.distinct().sorted()

    ScreenScaffold(title = "Settings", onBack = { nav.back() }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Theme") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeSwatch("System", Color(0xFF6750A4), Color(0xFF1C1B1F), settings.theme == THEME_SYSTEM) { vm.setTheme(THEME_SYSTEM) }
                    PALETTES.forEach { p ->
                        ThemeSwatch(p.label, Color(p.blue), Color(p.bgDark), settings.theme == p.key) { vm.setTheme(p.key) }
                    }
                }
                MutedText("The eleven palettes of the desktop app; System follows your wallpaper colours (Material You).")
            }

            Section("Uploads") {
                Text("Storage warning: ${settings.storageWarningMb} MB per bank", style = MaterialTheme.typography.bodyLarge)
                Slider(value = settings.storageWarningMb.toFloat(), onValueChange = { v -> vm.updateSettings { it.copy(storageWarningMb = v.toInt()) } },
                    valueRange = 1f..32f, steps = 30)
                MutedText("The P-6 manual gives a 10 MB soft limit per upload; the storage bar and Send warn above this.")
            }

            Section("App storage") {
                Text("Samples in the app: ${formatBytes(storage)}", style = MaterialTheme.typography.bodyLarge)
                MutedText("Every loaded, edited, chopped or synthesised sample is a file inside the app. Files no pad (and no undo step) uses any more can go.")
                OutlinedButton(onClick = { confirmClean = true }, modifier = Modifier.fillMaxWidth()) { Text("Remove unused files") }
            }

            Section("Your waveforms") {
                Text("${library.size} waveform famil${if (library.size == 1) "y" else "ies"} in ${groups.size} folder${if (groups.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyLarge)
                MutedText("Folders travel as .p6wf packs - the same files the desktop app keeps in ~/.pyp6/user_wave_families.")
                OutlinedButton(onClick = { packImport.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Import a .p6wf pack") }
                groups.forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g, modifier = Modifier.weight(1f))
                        TextButton(onClick = { exportGroup = g; packExport.launch(WaveLibrary.packFileName(g)) }) { Text("Export") }
                    }
                }
            }

            Section("About") {
                Text("Pad6 for Android ${vm.c.appVersion}", style = MaterialTheme.typography.bodyLarge)
                MutedText("Roland AIRA P-6 files manager. Chop is inspired by p6-wave-slice by Warren Blackwell. Not affiliated with Roland.")
                MutedText("Thanks to the original PyP6 project, which this one is built on.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/HomoHabilis/Roland-P6-files-manager"))) }) { Text("Project page") }
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/j0kerpack/Roland-P6-sample-manager"))) }) { Text("Original project") }
                }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/HomoHabilis/Roland-P6-files-manager/blob/main/LICENSE"))) }) {
                    Text("License and third-party notices")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmClean) ConfirmDialog("Remove unused files?",
        "Samples that no pad and no undo step point at are deleted. Presets are not affected.", "Remove",
        onDismiss = { confirmClean = false }, onConfirm = { vm.clearUnusedFiles(); refresh++ })
}

@Composable
private fun ThemeSwatch(label: String, accent: Color, bg: Color, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(accent)) }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
