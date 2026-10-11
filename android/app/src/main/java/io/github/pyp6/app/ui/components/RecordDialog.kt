package io.github.pyp6.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pyp6.app.ui.MainViewModel
import io.github.pyp6.app.ui.theme.LocalPyP6Colors
import io.github.pyp6.core.model.PadRef
import java.util.Locale
import kotlin.math.log10

/**
 * The Record action for a pad: returns what to call to record onto a pad.
 * It asks for the microphone permission the first time, then shows the
 * recording dialog; Stop puts the take on the pad.
 */
@Composable
fun rememberRecordAction(vm: MainViewModel): (PadRef) -> Unit {
    val context = LocalContext.current
    // "A1"-style, so an open dialog (and a running recording) survives rotation.
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) target = pending
        else vm.message("Recording needs microphone access: allow it in Android's app settings.", error = true)
        pending = null
    }
    target?.let { t ->
        RecordDialog(vm, PadRef(t[0], t.substring(1).toInt()), onClose = { target = null })
    }
    return { ref ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            target = ref.label
        } else {
            pending = ref.label
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
private fun RecordDialog(vm: MainViewModel, ref: PadRef, onClose: () -> Unit) {
    val rec by vm.recording.collectAsStateWithLifecycle()
    val project by vm.project.collectAsStateWithLifecycle()
    val existing = project.pad(ref)
    val recording = rec != null
    val pc = LocalPyP6Colors.current
    val red = Color(0xFFE53935)

    AlertDialog(
        onDismissRequest = { vm.cancelRecording(); onClose() },
        // A stray tap outside must not throw a take away.
        properties = DialogProperties(dismissOnClickOutside = !recording),
        title = { Text("Record into ${ref.label}") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val secs = rec?.seconds ?: 0.0
                Text(
                    String.format(Locale.ROOT, "%d:%04.1f", (secs / 60).toInt(), secs % 60),
                    style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace,
                    color = if (recording) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // In dB over the last 60 dB, like a mixer's meter: a quiet input still moves it.
                LinearProgressIndicator(
                    progress = { rec?.level?.let { ((20 * log10(it.coerceAtLeast(1e-6f)) + 60f) / 60f).coerceIn(0f, 1f) } ?: 0f },
                    color = if (rec?.clipped == true) pc.over else pc.good,
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledIconButton(
                    onClick = {
                        if (recording) { vm.stopRecording(ref); onClose() } else vm.startRecording()
                    },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = red, contentColor = Color.White),
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        if (recording) "Stop" else "Record", Modifier.size(36.dp))
                }
                when {
                    rec?.clipped == true -> Text("Clipping: move the sound further away or play it softer.",
                        color = pc.over, style = MaterialTheme.typography.bodySmall)
                    recording -> MutedText("Tap stop to put the take on ${ref.label}. Trim it afterwards in Edit.")
                    else -> MutedText("Recorded in mono at 44.1 kHz from the microphone, or a USB audio interface when one is plugged in." +
                        (existing?.let { "\nReplaces ${it.name} (Undo brings it back)." } ?: ""))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { vm.cancelRecording(); onClose() }) { Text("Cancel") } },
    )
}
