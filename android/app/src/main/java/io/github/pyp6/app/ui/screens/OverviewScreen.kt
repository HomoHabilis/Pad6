package io.github.pyp6.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pyp6.app.ui.MainViewModel
import io.github.pyp6.app.ui.Nav
import io.github.pyp6.app.ui.Routes
import io.github.pyp6.app.ui.components.WavetableLabel
import io.github.pyp6.app.ui.components.MiniWave
import io.github.pyp6.app.ui.components.MutedText
import io.github.pyp6.app.ui.components.ScreenScaffold
import io.github.pyp6.app.ui.components.rememberOverFraction
import io.github.pyp6.app.ui.components.rememberSummary
import io.github.pyp6.app.ui.theme.LocalPyP6Colors
import io.github.pyp6.core.P6
import io.github.pyp6.core.model.PadRef
import io.github.pyp6.core.model.Project
import kotlin.math.roundToInt

/** Something being dragged in the overview: a pad, or a whole bank. */
private sealed class Drag {
    data class PadDrag(val from: PadRef) : Drag()
    data class BankDrag(val from: Char) : Drag()
}

/**
 * All 48 pads at once. Tap plays, double-tap opens the pad; long-press and
 * drag moves a pad onto another pad (they swap, across banks too), or a
 * bank letter onto another bank (the banks swap, Force Mono included).
 */
@Composable
fun OverviewScreen(vm: MainViewModel, nav: Nav) {
    val project by vm.project.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val focus by vm.focusPad.collectAsStateWithLifecycle()
    // Live coordinates, read when hit-testing: cached rectangles go stale
    // when the list scrolls or a cell's content changes without moving it.
    val cells = remember { HashMap<PadRef, LayoutCoordinates>() }
    val rows = remember { HashMap<Char, LayoutCoordinates>() }
    var drag by remember { mutableStateOf<Drag?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }

    fun <K> Map<K, LayoutCoordinates>.at(pos: Offset): K? =
        entries.firstOrNull { (_, c) -> c.isAttached && c.boundsInRoot().contains(pos) }?.key
    val padTarget = (drag as? Drag.PadDrag)?.let { d -> cells.at(dragPos)?.takeIf { it != d.from } }
    val bankTarget = (drag as? Drag.BankDrag)?.let { d -> rows.at(dragPos)?.takeIf { it != d.from } }

    ScreenScaffold(
        title = "All banks",
        subtitle = "${project.allPads().size} of 48 pads loaded",
        topLevel = true,
        actions = {
            IconButton(onClick = vm::undo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(onClick = vm::redo, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).onGloballyPositioned { rootOffset = it.boundsInRoot().topLeft }) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = drag == null).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MutedText("Tap to play · double-tap to open · long-press and drag a pad onto another to swap them, or a bank letter onto another bank.")
                P6.BANKS.forEach { b ->
                    BankRow(
                        b, project, playback?.id, focus,
                        isBankTarget = bankTarget == b, isBankSource = (drag as? Drag.BankDrag)?.from == b,
                        padTarget = padTarget, padSource = (drag as? Drag.PadDrag)?.from,
                        vm = vm, onOpen = { nav.to(Routes.pad(it)) },
                        onCell = { r, rect -> cells[r] = rect },
                        onRow = { rect -> rows[b] = rect },
                        onDragStart = { d, pos -> drag = d; dragPos = pos },
                        onDrag = { dragPos = it },
                        onDragEnd = {
                            when (val d = drag) {
                                is Drag.PadDrag -> cells.at(dragPos)?.let { t ->
                                    if (t != d.from) vm.swapPads(d.from, t)
                                }
                                is Drag.BankDrag -> rows.at(dragPos)?.let { t ->
                                    if (t != d.from) vm.swapBanks(d.from, t)
                                }
                                null -> {}
                            }
                            drag = null
                        },
                    )
                }
            }
            drag?.let { d ->
                val density = LocalDensity.current
                val label = when (d) {
                    is Drag.PadDrag -> "${d.from.label} ${project.pad(d.from)?.name ?: ""}"
                    is Drag.BankDrag -> "Bank ${d.from}"
                }
                Surface(
                    shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary, shadowElevation = 8.dp,
                    modifier = Modifier.offset {
                        val p = dragPos - rootOffset
                        IntOffset((p.x - with(density) { 40.dp.toPx() }).roundToInt(), (p.y - with(density) { 44.dp.toPx() }).roundToInt())
                    },
                ) {
                    Text(label, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).width(120.dp))
                }
            }
        }
    }
}

@Composable
private fun BankRow(
    bank: Char, project: Project, playingId: String?, focus: PadRef?,
    isBankTarget: Boolean, isBankSource: Boolean, padTarget: PadRef?, padSource: PadRef?,
    vm: MainViewModel, onOpen: (PadRef) -> Unit,
    onCell: (PadRef, LayoutCoordinates) -> Unit, onRow: (LayoutCoordinates) -> Unit,
    onDragStart: (Drag, Offset) -> Unit, onDrag: (Offset) -> Unit, onDragEnd: () -> Unit,
) {
    val pc = LocalPyP6Colors.current
    val bs = project.bank(bank)
    var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    Row(
        Modifier.fillMaxWidth().height(62.dp)
            .onGloballyPositioned { rowCoords = it; onRow(it) }
            .border(if (isBankTarget) BorderStroke(2.dp, pc.warn) else BorderStroke(0.dp, Color.Transparent), RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(30.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                .background(if (isBankSource) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                .pointerInput(bank) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { o -> rowCoords?.let { start(Drag.BankDrag(bank), it.localToRoot(o)) } },
                        onDrag = { change, _ -> rowCoords?.let { move(it.localToRoot(change.position)) } },
                        onDragEnd = { end() }, onDragCancel = { end() },
                    )
                }
                .semantics { contentDescription = "Bank $bank. Long-press and drag onto another bank to swap." },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(bank.toString(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                if (bs.forceMono) Text("M", color = pc.mono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        P6.PADS.forEach { p ->
            val ref = PadRef(bank, p)
            Cell(
                ref, project, Modifier.weight(1f).fillMaxHeight(),
                playing = playingId == vm.padPlaybackId(ref),
                focused = focus == ref,
                target = padTarget == ref, source = padSource == ref,
                onTap = { vm.focusPad(ref); if (project.pad(ref) != null) vm.togglePad(ref) },
                onDouble = { onOpen(ref) },
                onPlaced = { onCell(ref, it) },
                onDragStart = { pos -> if (project.pad(ref) != null) start(Drag.PadDrag(ref), pos) },
                onDrag = { move(it) }, onDragEnd = { end() },
            )
        }
    }
}

@Composable
private fun Cell(
    ref: PadRef, project: Project, modifier: Modifier,
    playing: Boolean, focused: Boolean, target: Boolean, source: Boolean,
    onTap: () -> Unit, onDouble: () -> Unit, onPlaced: (LayoutCoordinates) -> Unit,
    onDragStart: (Offset) -> Unit, onDrag: (Offset) -> Unit, onDragEnd: () -> Unit,
) {
    val pc = LocalPyP6Colors.current
    val st = project.pad(ref)
    val mono = st != null && (project.bank(ref.bank).forceMono || st.mono)
    val summary by rememberSummary(st?.filepath)
    val over by rememberOverFraction(st, project.bank(ref.bank).forceMono)
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val tap by rememberUpdatedState(onTap)
    val dbl by rememberUpdatedState(onDouble)
    val ds by rememberUpdatedState(onDragStart)
    val dm by rememberUpdatedState(onDrag)
    val de by rememberUpdatedState(onDragEnd)
    val border = when {
        target -> BorderStroke(2.dp, pc.warn)
        playing -> BorderStroke(2.dp, pc.playing)
        focused -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Box(
        modifier
            .onGloballyPositioned { coords = it; onPlaced(it) }
            .clip(RoundedCornerShape(6.dp))
            .background(if (source) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainer)
            .border(border, RoundedCornerShape(6.dp))
            .pointerInput(ref) { detectTapGestures(onTap = { tap() }, onDoubleTap = { dbl() }) }
            .pointerInput(ref) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { o -> coords?.let { ds(it.localToRoot(o)) } },
                    onDrag = { change, _ -> coords?.let { dm(it.localToRoot(change.position)) } },
                    onDragEnd = { de() }, onDragCancel = { de() },
                )
            }
            .semantics { contentDescription = "${ref.label}: ${st?.name ?: "empty"}" },
    ) {
        if (mono) Box(Modifier.width(3.dp).fillMaxHeight().background(pc.mono))
        Column(Modifier.fillMaxSize().padding(start = if (mono) 5.dp else 3.dp, end = 3.dp, top = 2.dp, bottom = 2.dp)) {
            Text(if (st == null) "${ref.pad}" else st.name.substringBeforeLast('.').ifEmpty { st.name }, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start,
                color = if (st == null) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface)
            if (st != null && st.isWavetable) WavetableLabel(Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp), text = "WT")
            else if (st != null) MiniWave(summary?.envelope, Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp), overFraction = over)
        }
    }
}

