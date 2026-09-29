package io.github.pyp6.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pyp6.app.ui.theme.LocalPyP6Colors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What sits where on the P-6's START knob: one cell per family with its name
 * and START range, four to a row. The desktop draws this as one strip; at
 * phone width sixteen names cannot share one line, so here it wraps.
 *
 * [zones] are (first START position, last, family). [onTap] makes the cells
 * tappable (the Synth screen: see and hear a family); [selected] is outlined,
 * [playing] marked in the playing colour.
 */
@Composable
fun FamilyGrid(
    zones: List<Triple<Int, Int, String>>,
    modifier: Modifier = Modifier,
    selected: String? = null,
    playing: String? = null,
    columns: Int = 4,
    onTap: ((String) -> Unit)? = null,
) {
    val pc = LocalPyP6Colors.current
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        zones.withIndex().chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (i, z) ->
                    val (a, b, name) = z
                    // Alternating shades, as the desktop strip does, so
                    // neighbouring families stay apart without borders.
                    val bg = pc.wave.copy(alpha = if (i % 2 == 0) 0.38f else 0.22f)
                    val border = when (name) {
                        playing -> BorderStroke(2.dp, pc.warn)
                        selected -> BorderStroke(2.dp, scheme.primary)
                        else -> null
                    }
                    Column(
                        Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp)).background(bg)
                            .let { if (border != null) it.border(border, RoundedCornerShape(8.dp)) else it }
                            .let { if (onTap != null) it.clickable { onTap(name) } else it }
                            .semantics { contentDescription = "$name, START $a to $b" }
                            .padding(horizontal = 4.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(name, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, color = scheme.onSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Text("$a–$b", fontSize = 10.sp, color = scheme.onSurfaceVariant)
                    }
                }
                // Keep the last row's cells the same width as the others.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The waveforms a family steps through, drawn as a stack receding up and to
 * the right, front (first step) to back (last) - the desktop Synth window's
 * Morph display. Each curve is filled with the background before it is
 * stroked, so a nearer one hides the ones behind it. [highlight] (0..1)
 * marks the step sounding in the preview; null draws none.
 */
@Composable
fun MorphView(shapes: List<DoubleArray>, modifier: Modifier = Modifier, highlight: Double? = null, placeholder: String? = null) {
    val pc = LocalPyP6Colors.current
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(pc.waveBg), contentAlignment = Alignment.Center) {
        if (shapes.isEmpty()) {
            placeholder?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@Box
        }
        Canvas(Modifier.matchParentSize()) {
            val shown = shapes.size
            val margin = 6.dp.toPx()
            val usable = size.height - 2 * margin
            if (usable < 30f || size.width < 80f) return@Canvas
            val depthX = size.width * 0.22f
            val depthY = usable * 0.5f
            val amp = (usable - depthY) / 2f
            val baseY = size.height - margin - amp
            val frontW = size.width - depthX - 4.dp.toPx()
            fun path(idx: Int, closed: Boolean): Path {
                val vals = shapes[idx]
                val frac = idx.toFloat() / max(1, shown - 1)
                val ox = depthX * frac
                val oy = -depthY * frac
                val p = Path()
                val step = max(1, vals.size / 220)
                var first = true
                var i = 0
                while (i < vals.size) {
                    val x = ox + i.toFloat() / (vals.size - 1) * frontW
                    val y = baseY + oy - vals[i].toFloat() * amp
                    if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
                    i += step
                }
                if (closed) {
                    p.lineTo(ox + frontW, size.height)
                    p.lineTo(ox, size.height)
                    p.close()
                }
                return p
            }
            for (idx in shown - 1 downTo 0) {
                val far = idx.toFloat() / max(1, shown - 1)
                drawPath(path(idx, true), pc.waveBg)
                drawPath(path(idx, false), lerp(pc.wave, pc.waveBg, 0.62f * far),
                    style = Stroke(if (idx == 0) 2.dp.toPx() else 1.dp.toPx()))
            }
            highlight?.let { h ->
                val idx = (h.coerceIn(0.0, 1.0) * (shown - 1)).roundToInt()
                drawPath(path(idx, false), pc.warn, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

/** Stands in for the waveform of a wavetable pad, whose audio is 255 cycles in a row and says nothing drawn. */
@Composable
fun WavetableLabel(modifier: Modifier = Modifier, text: String = "WAVETABLE") {
    val pc = LocalPyP6Colors.current
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(pc.waveBg), contentAlignment = Alignment.Center) {
        Text(text, color = pc.wave, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, maxLines = 1)
    }
}
