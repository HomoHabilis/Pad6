package io.github.pyp6.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pyp6.app.ui.MainViewModel
import io.github.pyp6.app.ui.Nav
import io.github.pyp6.app.ui.components.MutedText
import io.github.pyp6.app.ui.components.ScreenScaffold
import io.github.pyp6.app.ui.components.Section
import io.github.pyp6.app.ui.components.SwitchRow
import io.github.pyp6.app.ui.theme.LocalPyP6Colors
import io.github.pyp6.core.audio.Audio
import io.github.pyp6.core.wavetable.WaveEntry
import io.github.pyp6.core.wavetable.Wavetable
import io.github.pyp6.core.wavetable.WtSynth
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val POINTS = Wavetable.DRAW_POINTS

private val PRESETS: List<Pair<String, (Double) -> Double>> = listOf(
    "Sine" to { t -> sin(2 * PI * t) },
    "Triangle" to { t -> 2.0 * abs(2.0 * ((t + 0.25) % 1.0) - 1.0) - 1.0 },
    "Saw" to { t -> 2.0 * t - 1.0 },
    "Square" to { t -> if (t < 0.5) 1.0 else -1.0 },
    "Flat" to { _ -> 0.0 },
)

private fun preset(i: Int) = DoubleArray(POINTS) { PRESETS[i].second(it.toDouble() / POINTS) }

/** What the table will actually hold: the drawing through the band limiter. */
private fun bandLimited(values: DoubleArray): DoubleArray {
    val s = WtSynth(POINTS, 1, 96)
    return Wavetable.pointsToCycle(values.toList(), s)
}

/**
 * Waveform Creator: draw two single cycles with a finger (or start from a
 * preset) and the family morphs from A to B. Saved into the waveform
 * library, from where Synth's Advanced mode adds it to a table.
 */
@Composable
fun DrawWaveScreen(vm: MainViewModel, nav: Nav) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    var lane by remember { mutableIntStateOf(0) }
    var a by remember { mutableStateOf(preset(0)) }
    var b by remember { mutableStateOf(preset(2)) }
    var name by remember { mutableStateOf(vm.uniqueWaveName("Custom")) }
    var folder by remember { mutableStateOf(Wavetable.DEFAULT_USER_GROUP) }
    var showResult by remember { mutableStateOf(true) }
    var version by remember { mutableIntStateOf(0) }
    val current = if (lane == 0) a else b
    val playing = playback?.id == "draw"

    fun setCurrent(v: DoubleArray) { if (lane == 0) a = v else b = v; version++ }

    ScreenScaffold(title = "Draw a waveform", subtitle = "Shape A morphs into shape B", onBack = { nav.back() },
        actions = {
            TextButton(onClick = {
                vm.putWaveEntries(listOf(WaveEntry("draw", vm.uniqueWaveName(name), folder.ifBlank { Wavetable.DEFAULT_USER_GROUP }, a.toList(), b.toList())))
                vm.message("Saved to “$folder” - add it in Synth › Advanced › Add families.")
                nav.back()
            }) { Text("Save") }
        }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Shape A", "Shape B").forEachIndexed { i, l ->
                    SegmentedButton(colors = io.github.pyp6.app.ui.components.segColors(), icon = {}, selected = lane == i, onClick = { lane = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                }
            }
            val result = remember(current, version) { if (showResult) bandLimited(current) else null }
            DrawCanvas(current, result, onChange = ::setCurrent, key = lane)
            MutedText("Draw with your finger. The thin line is what the table will hold after band limiting - where your line is sharper than a segment can carry, they differ.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PRESETS.forEachIndexed { i, (label, _) -> AssistChip(onClick = { setCurrent(preset(i)) }, label = { Text(label) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { setCurrent(smooth(current)) }, Modifier.weight(1f)) { Text("Smooth") }
                OutlinedButton(onClick = { if (lane == 0) { b = a.copyOf() } else { a = b.copyOf() }; version++ }, Modifier.weight(1f)) {
                    Text(if (lane == 0) "Copy to B" else "Copy to A")
                }
            }
            SwitchRow("Show what will sound", showResult, { showResult = it; version++ })
            Button(onClick = {
                if (playing) vm.stopPlayback()
                else {
                    val pts = bandLimited(current)
                    vm.play("draw", Audio(Wavetable.SR, listOf(Wavetable.cycleTone(pts.toList(), 130.81))))
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(6.dp)); Text(if (playing) "Stop" else "Hear this shape (C3)")
            }
            Section("Save as") {
                OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, singleLine = true, label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = folder, onValueChange = { folder = it.take(40) }, singleLine = true, label = { Text("Folder") },
                    modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun smooth(v: DoubleArray): DoubleArray {
    val n = v.size
    return DoubleArray(n) { i -> (v[(i - 1 + n) % n] + 2 * v[i] + v[(i + 1) % n]) / 4 }
}

@Composable
private fun DrawCanvas(values: DoubleArray, result: DoubleArray?, onChange: (DoubleArray) -> Unit, key: Int) {
    val pc = LocalPyP6Colors.current
    val live = remember(key) { mutableStateOf(values) }
    live.value = values
    var tick by remember { mutableIntStateOf(0) }
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(8.dp))
            .pointerInput(key) {
                var last: Pair<Int, Double>? = null
                fun apply(o: Offset) {
                    val idx = (o.x / size.width * POINTS).toInt().coerceIn(0, POINTS - 1)
                    val v = (1.0 - 2.0 * o.y / size.height).coerceIn(-1.0, 1.0)
                    val arr = live.value.copyOf()
                    val prev = last
                    if (prev == null) arr[idx] = v
                    else {
                        // Fill every point between two touch samples, so a fast stroke leaves no gaps.
                        val (pi, pv) = prev
                        val lo = min(pi, idx); val hi = max(pi, idx)
                        for (k in lo..hi) {
                            val t = if (hi == lo) 1.0 else (k - pi).toDouble() / (idx - pi)
                            arr[k] = pv + (v - pv) * t
                        }
                    }
                    last = idx to v
                    live.value = arr
                    tick++
                }
                detectDragGestures(
                    onDragStart = { last = null; apply(it) },
                    onDrag = { change, _ -> apply(change.position) },
                    onDragEnd = { last = null; onChange(live.value) },
                )
            }
            .pointerInput(key) { detectTapGestures { o ->
                val idx = (o.x / size.width * POINTS).toInt().coerceIn(0, POINTS - 1)
                val arr = live.value.copyOf(); arr[idx] = (1.0 - 2.0 * o.y / size.height).coerceIn(-1.0, 1.0); onChange(arr)
            } }
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        drawRect(pc.waveBg)
        for (g in 1..3) drawLine(pc.wave.copy(alpha = 0.15f), Offset(size.width * g / 4, 0f), Offset(size.width * g / 4, size.height), 1f)
        drawLine(pc.wave.copy(alpha = 0.35f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        fun path(v: DoubleArray): Path = Path().apply {
            for (i in v.indices) {
                val x = i / (v.size - 1f) * size.width
                val y = (size.height / 2 - v[i] * size.height / 2).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(path(live.value), pc.wave, style = Stroke(3.dp.toPx()))
        result?.let { drawPath(path(it), pc.good, style = Stroke(1.5.dp.toPx())) }
    }
}
