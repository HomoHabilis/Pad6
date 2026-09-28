package io.github.pyp6.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.pyp6.core.P6
import java.util.Locale

/** A titled card, the phone equivalent of the desktop app's rounded panels. */
@Composable
fun Section(
    title: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    trailing()
                }
            }
            content()
        }
    }
}

/** A row with a label (and optional explanation) and a switch; the whole row toggles. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, trailing: String? = null) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun rateLabel(rate: Int): String = when (rate) {
    44100 -> "44.1k"
    22050 -> "22.05k"
    14700 -> "14.7k"
    11025 -> "11.025k"
    else -> "${rate / 1000.0}k"
}

/** Short enough for a quarter of a small phone's width at large text. */
private fun rateShort(rate: Int): String = when (rate) {
    44100 -> "44.1"
    22050 -> "22"
    14700 -> "14.7"
    11025 -> "11"
    else -> "${rate / 1000}"
}

/** The four rates the P-6 accepts, as a segmented control. */
@Composable
fun RateSelector(rate: Int, onChange: (Int) -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Text("Sample rate: ${String.format(Locale.ROOT, "%,d", rate)} Hz", style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        P6.TARGET_RATES.forEachIndexed { i, r ->
            SegmentedButton(colors = segColors(), icon = {}, 
                selected = r == rate,
                onClick = { onChange(r) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, P6.TARGET_RATES.size),
                label = { Text(rateShort(r), maxLines = 1, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

/** Pitch in cents: -/+ 100 steps, tap the value to type one, Reset. */
@Composable
fun PitchControl(cents: Int, onChange: (Int) -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Pitch", style = MaterialTheme.typography.bodyLarge, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = { onChange((cents - P6.PITCH_STEP_CENTS).coerceAtLeast(P6.PITCH_MIN_CENTS)) },
            enabled = enabled && cents > P6.PITCH_MIN_CENTS) { Icon(Icons.Default.Remove, "Pitch down 100 cents") }
        TextButton(onClick = { editing = true }, enabled = enabled, modifier = Modifier.width(88.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
            Text(String.format(Locale.ROOT, "%+d c", cents), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
        FilledTonalIconButton(onClick = { onChange((cents + P6.PITCH_STEP_CENTS).coerceAtMost(P6.PITCH_MAX_CENTS)) },
            enabled = enabled && cents < P6.PITCH_MAX_CENTS) { Icon(Icons.Default.Add, "Pitch up 100 cents") }
        IconButton(onClick = { onChange(0) }, enabled = enabled && cents != 0) { Icon(Icons.Default.RestartAlt, "Reset pitch") }
    }
    if (editing) NumberDialog(
        title = "Pitch in cents",
        initial = cents.toString(),
        hint = "${P6.PITCH_MIN_CENTS} to +${P6.PITCH_MAX_CENTS}; 100 cents = 1 semitone",
        onDismiss = { editing = false },
        onValue = { v -> v.toDoubleOrNull()?.let { onChange(it.toInt().coerceIn(P6.PITCH_MIN_CENTS, P6.PITCH_MAX_CENTS)) }; editing = false },
    )
}

@Composable
fun NumberDialog(title: String, initial: String, hint: String?, onDismiss: () -> Unit, onValue: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                supportingText = hint?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = { TextButton(onClick = { onValue(text.replace(',', '.').trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, label: String, confirm: String, onDismiss: () -> Unit, onValue: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text(label) }) },
        confirmButton = { TextButton(onClick = { onValue(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A small rounded tag: MONO, PRM, WT … */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.18f), contentColor = color, shape = MaterialTheme.shapes.extraSmall, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

/** A numbered step, for the "on the P-6 itself" instructions. */
@Composable
fun Steps(steps: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, s ->
            Row {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    contentColor = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(s, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Segmented buttons with a clearly filled selected segment (the default tint is too faint on the dark palettes). */
@Composable
fun segColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primary,
    activeContentColor = MaterialTheme.colorScheme.onPrimary,
    activeBorderColor = MaterialTheme.colorScheme.primary,
)
