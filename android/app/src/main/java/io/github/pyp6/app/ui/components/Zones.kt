package io.github.pyp6.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import io.github.pyp6.app.ui.theme.LocalPyP6Colors
import io.github.pyp6.core.wavetable.Wavetable

/**
 * What sits where on the P-6's START knob: one coloured band per family,
 * labelled where there is room, with an optional marker at [position]
 * (0..254).
 */
@Composable
fun ZoneStrip(zones: List<Triple<Int, Int, String>>, modifier: Modifier = Modifier, position: Int? = null) {
    val pc = LocalPyP6Colors.current
    val text = MaterialTheme.colorScheme.onSurface
    val colors = listOf(pc.wave, pc.good, pc.mono, pc.warn, pc.playing)
    Canvas(modifier.clip(RoundedCornerShape(6.dp))) {
        val total = Wavetable.SEGMENTS.toFloat()
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 11.dp.toPx()
            color = text.toArgb()
        }
        zones.forEachIndexed { i, (a, b, name) ->
            val x0 = a / total * size.width
            val x1 = (b + 1) / total * size.width
            drawRect(colors[i % colors.size].copy(alpha = 0.55f), Offset(x0, 0f), Size(x1 - x0, size.height))
            drawLine(Color.Black.copy(alpha = 0.35f), Offset(x1, 0f), Offset(x1, size.height), 1f)
            val w = paint.measureText(name)
            if (w + 6.dp.toPx() < x1 - x0) {
                drawContext.canvas.nativeCanvas.drawText(name, x0 + 3.dp.toPx(), size.height / 2 + paint.textSize / 3, paint)
            }
        }
        position?.let {
            val x = (it + 0.5f) / total * size.width
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
        }
    }
}

/** A single cycle (or a few stacked shapes) drawn as lines, for the morph display. */
@Composable
fun ShapeView(shapes: List<DoubleArray>, modifier: Modifier = Modifier, highlightLast: Boolean = false) {
    val pc = LocalPyP6Colors.current
    Canvas(modifier.clip(RoundedCornerShape(6.dp))) {
        drawRect(pc.waveBg)
        drawLine(pc.wave.copy(alpha = 0.3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        val n = shapes.size
        shapes.forEachIndexed { k, s ->
            if (s.isEmpty()) return@forEachIndexed
            val p = Path()
            for (i in s.indices) {
                val x = i / (s.size - 1f).coerceAtLeast(1f) * size.width
                val y = size.height / 2 - (s[i].toFloat() * size.height * 0.45f)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            val alpha = if (n == 1) 1f else 0.25f + 0.75f * k / (n - 1)
            val col = if (highlightLast && k == n - 1) pc.good else pc.wave
            drawPath(p, col.copy(alpha = alpha), style = Stroke(1.5.dp.toPx()))
        }
    }
}
