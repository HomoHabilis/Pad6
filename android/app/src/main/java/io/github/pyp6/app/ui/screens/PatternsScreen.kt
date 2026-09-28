package io.github.pyp6.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pyp6.app.ui.MainViewModel
import io.github.pyp6.app.ui.Nav
import io.github.pyp6.app.ui.components.MutedText
import io.github.pyp6.app.ui.components.ScreenScaffold
import io.github.pyp6.app.ui.components.Section
import io.github.pyp6.app.ui.components.Steps
import io.github.pyp6.app.ui.components.SwitchRow
import io.github.pyp6.app.ui.components.Tag
import io.github.pyp6.app.ui.theme.LocalPyP6Colors
import io.github.pyp6.core.P6
import io.github.pyp6.core.device.P6Drive
import io.github.pyp6.core.model.PadRef
import io.github.pyp6.core.model.Project
import io.github.pyp6.core.pattern.P6Pattern
import io.github.pyp6.core.pattern.Parts
import io.github.pyp6.core.pattern.PatternRender
import io.github.pyp6.core.pattern.PatternSlot
import kotlinx.coroutines.launch
import java.util.Locale

private val BACKUP_STEPS = listOf(
    "Connect the P-6 to the phone over USB (USB-C cable or adapter) and turn the P-6 off.",
    "Hold [▶] (PLAY) and turn the power on.",
    "The P-6 writes its patterns to its BACKUP folder; the step buttons show the progress. Many patterns can take a few minutes.",
    "Tap “From the P-6” once the drive shows up here.",
)
private val RESTORE_STEPS = listOf(
    "Connect the P-6 over USB and turn it off.",
    "Hold [●] (REC) and turn the power on.",
    "Tap “To the P-6”: the patterns are copied into its RESTORE folder.",
    "Eject the drive (notification shade › USB drive › Eject), then press [KYBD] on the P-6. It can take around five minutes.",
)

/** The P-6's 64 patterns: what each plays, where, and moving them. */
@Composable
fun PatternsScreen(vm: MainViewModel, nav: Nav) {
    val project by vm.project.collectAsStateWithLifecycle()
    val selected by vm.selectedPattern.collectAsStateWithLifecycle()
    val focusPad by vm.focusPad.collectAsStateWithLifecycle()
    val focus by vm.focus.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    var bank by remember { mutableIntStateOf(selected?.bank ?: 1) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val folderLoad = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::loadPatternsFromFolder) }
    var saveFolderPlan by remember { mutableStateOf<Pair<android.net.Uri, P6Drive.PatternSavePlan?>?>(null) }
    val scope = rememberCoroutineScope()
    val folderSave = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { scope.launch { saveFolderPlan = it to vm.patternSavePlan(false, it) } }
    }
    val pats = project.patterns
    LaunchedEffect(pats.isEmpty()) {
        if (selected == null) pats.entries.firstOrNull { !it.value.isEmpty }?.key?.let { vm.selectPattern(it); bank = it.bank }
    }

    // Patterns that play the focused pad, when the pad was picked last.
    val linked: Set<PatternSlot> = if (focus == "pad" && focusPad != null)
        pats.filterValues { focusPad!!.bank to focusPad!!.pad in it.usedPads() }.keys else emptySet()

    ScreenScaffold(
        title = "Patterns",
        subtitle = if (pats.isEmpty()) "None loaded" else "${pats.size} of 64 · ${project.patternSource ?: ""}" + if (project.patternsDirty) " · changed" else "",
        topLevel = true,
        actions = {
            IconButton(onClick = { loading = true }) { Icon(Icons.Default.Download, "Load patterns") }
            IconButton(onClick = { saving = true }, enabled = pats.isNotEmpty()) { Icon(Icons.Default.Upload, "Save patterns") }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pats.isEmpty()) Section("No patterns loaded") {
                MutedText("Load the P-6's 64 patterns from its BACKUP folder (or any folder of P6_PTN*.PRM files) to see which samples " +
                    "they play, hear them with the pads as they are now, and move or clear them.")
                Button(onClick = { loading = true }, modifier = Modifier.fillMaxWidth()) { Text("Load patterns") }
            }
            SwitchRow("Sync patterns with pad moves", settings.patternSync, vm::setPatternSync,
                supporting = "Swapping pads or banks rewrites the patterns so they keep playing the same samples")

            PatternGrid(vm, pats, bank, { bank = it }, selected, linked, preview?.slot, project)

            val sel = selected
            if (sel != null) {
                val p = pats[sel]
                PatternCard(vm, sel, p, project, playing = preview?.slot == sel,
                    step = preview?.takeIf { it.slot == sel }?.let { pv -> playback?.let { (it.position * pv.steps).toInt() } },
                    onMove = { moving = true })
            }
            PadMap(vm, project, pats[selected ?: PatternSlot(1, 1)]?.takeIf { selected != null && focus == "pattern" }, focusPad)
            Spacer(Modifier.height(16.dp))
        }
    }

    if (loading) AlertDialog(
        onDismissRequest = { loading = false },
        title = { Text("Load patterns") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("On the P-6 itself", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Steps(BACKUP_STEPS)
                Spacer(Modifier.height(10.dp))
                MutedText(if (device.hasBackup) "Found the BACKUP folder on ${device.rootName}." else if (device.reachable)
                    "The P-6 drive is connected but has no BACKUP folder yet." else "Waiting for the P-6 drive (grant access on the P-6 tab).")
            }
        },
        confirmButton = {
            TextButton(onClick = { loading = false; vm.loadPatternsFromDevice() }, enabled = device.hasBackup) { Text("From the P-6") }
        },
        dismissButton = {
            TextButton(onClick = { loading = false; folderLoad.launch(null) }) { Text("A folder…") }
        },
    )
    if (saving) {
        var plan by remember { mutableStateOf<P6Drive.PatternSavePlan?>(null) }
        LaunchedEffect(device.hasRestore) { plan = if (device.hasRestore) vm.patternSavePlan(true, null) else null }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save patterns") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("On the P-6 itself", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Steps(RESTORE_STEPS)
                    Spacer(Modifier.height(10.dp))
                    MutedText(if (device.hasRestore) "Found the RESTORE folder on ${device.rootName}." else "Waiting for the P-6 drive with its RESTORE folder.")
                    plan?.let { PlanText(it) }
                }
            },
            confirmButton = { TextButton(onClick = { saving = false; vm.savePatterns(true, null) }, enabled = device.hasRestore) { Text("To the P-6") } },
            dismissButton = { TextButton(onClick = { saving = false; folderSave.launch(null) }) { Text("A folder…") } },
        )
    }
    saveFolderPlan?.let { (uri, plan) ->
        AlertDialog(
            onDismissRequest = { saveFolderPlan = null },
            title = { Text("Save patterns to this folder?") },
            text = { Column { plan?.let { PlanText(it) } ?: Text("That folder cannot be read.") } },
            confirmButton = { TextButton(onClick = { saveFolderPlan = null; vm.savePatterns(false, uri) }, enabled = plan != null) { Text("Save") } },
            dismissButton = { TextButton(onClick = { saveFolderPlan = null }) { Text("Cancel") } },
        )
    }
    if (moving && selected != null) SlotPickerDialog(selected!!, pats, onDismiss = { moving = false }) { t -> vm.swapPatterns(selected!!, t); moving = false }
}

@Composable
private fun PlanText(plan: P6Drive.PatternSavePlan) {
    Column(Modifier.padding(top = 8.dp)) {
        Text("${plan.toWrite} pattern files will be written.")
        if (plan.overwrite > 0) MutedText("${plan.overwrite} existing pattern file(s) there are replaced.")
        if (plan.stale > 0) MutedText("${plan.stale} pattern file(s) there belong to slots that are now empty and are deleted.")
        if (plan.emptySlots > 0) MutedText("${plan.emptySlots} slot(s) have no pattern, so a restore leaves the P-6's own there.")
    }
}

@Composable
private fun PatternGrid(
    vm: MainViewModel, pats: Map<PatternSlot, P6Pattern>, bank: Int, onBank: (Int) -> Unit,
    selected: PatternSlot?, linked: Set<PatternSlot>, playing: PatternSlot?, project: Project,
) {
    val tiles = remember { mutableStateMapOf<PatternSlot, Rect>() }
    var dragFrom by remember { mutableStateOf<PatternSlot?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    val target = dragFrom?.let { f -> tiles.entries.firstOrNull { it.value.contains(dragPos) }?.key?.takeIf { it != f } }
    Section("Pattern bank") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            (1..PatternSlot.BANKS).forEach { b ->
                val n = linked.count { it.bank == b }
                SegmentedButton(colors = io.github.pyp6.app.ui.components.segColors(), icon = {}, selected = bank == b, onClick = { onBank(b) }, shape = SegmentedButtonDefaults.itemShape(b - 1, PatternSlot.BANKS)) {
                    if (n > 0) BadgedBox(badge = { Badge { Text("$n") } }) { Text("$b") } else Text("$b")
                }
            }
        }
        for (row in 0 until 4) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0 until 4) {
                    val slot = PatternSlot(bank, row * 4 + col + 1)
                    PatternTile(
                        slot, pats[slot], Modifier.weight(1f),
                        selected = slot == selected, linked = slot in linked, playing = slot == playing,
                        target = slot == target, source = slot == dragFrom,
                        onTap = { vm.selectPattern(slot) },
                        onDouble = { vm.selectPattern(slot); if (pats[slot] != null) vm.togglePatternPreview(slot) },
                        onPlaced = { tiles[slot] = it },
                        onDragStart = { dragFrom = slot; dragPos = it },
                        onDrag = { dragPos = it },
                        onDragEnd = {
                            val f = dragFrom
                            val t = tiles.entries.firstOrNull { it.value.contains(dragPos) }?.key
                            if (f != null && t != null && t != f) vm.swapPatterns(f, t)
                            dragFrom = null
                        },
                    )
                }
            }
        }
        MutedText("Tap to select · double-tap to play · long-press and drag onto another slot to swap (an empty slot: move). " +
            "A dashed slot has no file, so a restore leaves the P-6's own pattern there.")
    }
}

@Composable
private fun PatternTile(
    slot: PatternSlot, pat: P6Pattern?, modifier: Modifier,
    selected: Boolean, linked: Boolean, playing: Boolean, target: Boolean, source: Boolean,
    onTap: () -> Unit, onDouble: () -> Unit, onPlaced: (Rect) -> Unit,
    onDragStart: (Offset) -> Unit, onDrag: (Offset) -> Unit, onDragEnd: () -> Unit,
) {
    val pc = LocalPyP6Colors.current
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val tap by rememberUpdatedState(onTap)
    val dbl by rememberUpdatedState(onDouble)
    val ds by rememberUpdatedState(onDragStart)
    val dm by rememberUpdatedState(onDrag)
    val de by rememberUpdatedState(onDragEnd)
    val bg = when {
        source -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        linked -> pc.highlight
        pat == null -> Color.Transparent
        pat.isEmpty -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val outline = when {
        target -> pc.warn
        playing -> pc.playing
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier.heightIn(min = 60.dp)
            .onGloballyPositioned { coords = it; onPlaced(it.boundsInRoot()) }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .pointerInput(slot) { detectTapGestures(onTap = { tap() }, onDoubleTap = { dbl() }) }
            .pointerInput(slot) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { o -> coords?.let { ds(it.localToRoot(o)) } },
                    onDrag = { c, _ -> coords?.let { dm(it.localToRoot(c.position)) } },
                    onDragEnd = { de() }, onDragCancel = { de() },
                )
            }
            .semantics { contentDescription = "Pattern ${slot.label}: " + (pat?.let { if (it.isEmpty) "empty" else "${it.noteCount} notes" } ?: "no file") },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = if (selected || playing || target) 2.5.dp.toPx() else 1.dp.toPx()
            drawRoundRect(outline, style = Stroke(w, pathEffect = if (pat == null) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
            // A bar of the steps that hold notes.
            if (pat != null && !pat.isEmpty) {
                val n = pat.length.coerceAtLeast(1)
                val bw = (size.width - 12.dp.toPx()) / n
                for (i in 0 until n) {
                    if (pat.stepParts.getOrNull(i)?.isNotEmpty() == true || (pat.stepGranular.getOrNull(i) ?: 0) > 0) {
                        drawRect(pc.wave, Offset(6.dp.toPx() + i * bw, size.height - 9.dp.toPx()), Size(bw.coerceAtLeast(1f), 4.dp.toPx()))
                    }
                }
            }
        }
        Column(Modifier.padding(start = 6.dp, top = 4.dp, end = 4.dp, bottom = 14.dp)) {
            Text(slot.number.toString().padStart(2, '0'), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
                color = if (pat == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            if (pat != null) Text(if (pat.isEmpty) "empty" else "${pat.noteCount}", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (playing) Icon(Icons.Default.PlayArrow, null, tint = pc.playing, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatternCard(
    vm: MainViewModel, slot: PatternSlot, p: P6Pattern?, project: Project, playing: Boolean, step: Int?, onMove: () -> Unit,
) {
    val pc = LocalPyP6Colors.current
    Section("Pattern ${slot.label}") {
        if (p == null) {
            MutedText("No file for this slot. A restore leaves whatever the P-6 has here. Clear creates an empty pattern, which does overwrite it.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.clearPattern(slot) }, Modifier.weight(1f)) { Text("Create empty") }
                OutlinedButton(onClick = onMove, Modifier.weight(1f)) { Text("Move here…") }
            }
            return@Section
        }
        val scale = P6Pattern.intOr(p.value("SCALE"), 1)!!
        val scaleName = listOf("1/8", "1/16", "1/32", "1/8T", "1/16T", "1/32T").getOrElse(scale) { "?" }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoPair("Tempo", String.format(Locale.ROOT, "%.1f", if (p.tempo > 0) p.tempo else 120.0))
            InfoPair("Length", "${p.length}")
            InfoPair("Scale", scaleName)
            InfoPair("Shuffle", p.value("SHUFFLE") ?: "0")
            InfoPair("Level", p.value("LEVEL") ?: "-")
            InfoPair("Transpose", p.value("TRANSPOSE") ?: "0")
        }
        StepStrip(p, step)
        if (p.isEmpty) MutedText("No notes.")
        else {
            Text("Samples", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.partCounts.entries.sortedBy { it.key }.forEach { (part, n) ->
                    val where = Parts.padFor(part)
                    val empty = where == null || project.pad(where.first, where.second) == null
                    val muted = part in p.mutedParts
                    val name = where?.let { project.pad(it.first, it.second)?.name } ?: ""
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = (if (empty) MaterialTheme.colorScheme.error else pc.good).copy(alpha = 0.15f),
                        modifier = Modifier.clickable { where?.let { vm.focusPad(PadRef(it.first, it.second)) } },
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Parts.padName(part), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                Text(" ×$n", style = MaterialTheme.typography.labelMedium)
                                if (muted) { Spacer(Modifier.width(4.dp)); Tag("M", MaterialTheme.colorScheme.error) }
                            }
                            Text(if (empty) "empty pad" else name, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                modifier = Modifier.width(96.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (p.granularCount > 0) Tag("Granular ×${p.granularCount} (${Parts.padName(p.granularSource)})", pc.mono)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.togglePatternPreview(slot) }, enabled = !p.isEmpty, modifier = Modifier.weight(1f)) {
                Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(4.dp)); Text(if (playing) "Stop" else "Play")
            }
            FilledTonalButton(onClick = { vm.clearPattern(slot) }, enabled = !p.isEmpty, modifier = Modifier.weight(1f)) { Text("Clear") }
            OutlinedButton(onClick = onMove, modifier = Modifier.weight(1f)) { Text("Move…") }
        }
        MutedText("Plays in a loop with the samples on the pads right now - to recognise the pattern, not to reproduce the P-6: no filter, effects or knob motion.")
    }
}

@Composable
private fun InfoPair(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

/** One cell per step, filled where notes are, with the play cursor. */
@Composable
private fun StepStrip(p: P6Pattern, step: Int?) {
    val pc = LocalPyP6Colors.current
    val n = p.length.coerceIn(1, Parts.MAX_STEPS)
    val rows = (n + 15) / 16
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.fillMaxWidth().height((rows * 18).dp)) {
        val gap = 2.dp.toPx()
        val cw = (size.width - gap * 15) / 16
        val ch = 16.dp.toPx()
        for (i in 0 until n) {
            val r = i / 16
            val c = i % 16
            val has = p.stepParts.getOrNull(i)?.isNotEmpty() == true || (p.stepGranular.getOrNull(i) ?: 0) > 0
            val color = when {
                step == i -> pc.warn
                has -> pc.wave
                else -> empty
            }
            drawRoundRect(color, Offset(c * (cw + gap), r * (ch + gap)), Size(cw, ch), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        }
    }
}

/** The 8x6 pads: which ones the selected pattern plays; tap one to see the patterns that play it. */
@Composable
private fun PadMap(vm: MainViewModel, project: Project, pattern: P6Pattern?, focus: PadRef?) {
    val pc = LocalPyP6Colors.current
    Section("Pads") {
        MutedText(if (pattern != null) "Green: pads this pattern plays. Tap a pad to light up every pattern that plays it."
        else "Tap a pad to light up every pattern that plays it.")
        val used = pattern?.usedPads() ?: emptySet()
        P6.BANKS.forEach { b ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(b.toString(), fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                P6.PADS.forEach { p ->
                    val ref = PadRef(b, p)
                    val st = project.pad(ref)
                    val isUsed = (b to p) in used
                    Box(
                        Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(4.dp))
                            .background(when {
                                isUsed && st == null -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                isUsed -> pc.good.copy(alpha = 0.6f)
                                st != null -> MaterialTheme.colorScheme.surfaceContainerHighest
                                else -> Color.Transparent
                            })
                            .border(BorderStroke(if (focus == ref) 2.dp else 1.dp,
                                if (focus == ref) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(4.dp))
                            .clickable { vm.focusPad(ref) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$p", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotPickerDialog(from: PatternSlot, pats: Map<PatternSlot, P6Pattern>, onDismiss: () -> Unit, onPick: (PatternSlot) -> Unit) {
    var bank by remember { mutableIntStateOf(from.bank) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Swap ${from.label} with") },
        text = {
            Column {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    (1..4).forEach { b -> SegmentedButton(colors = io.github.pyp6.app.ui.components.segColors(), icon = {}, selected = bank == b, onClick = { bank = b }, shape = SegmentedButtonDefaults.itemShape(b - 1, 4)) { Text("$b") } }
                }
                Spacer(Modifier.height(8.dp))
                for (r in 0 until 4) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until 4) {
                        val s = PatternSlot(bank, r * 4 + c + 1)
                        OutlinedButton(onClick = { onPick(s) }, enabled = s != from, modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text(s.number.toString().padStart(2, '0'), fontWeight = if (pats[s] != null) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                MutedText("Bold slots hold a pattern; picking an empty one moves it.", Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

