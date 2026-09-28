package io.github.pyp6.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.pyp6.app.ui.theme.LocalPyP6Colors
import io.github.pyp6.core.dsp.Edit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Draws a min/max envelope (pairs) across the given rectangle. */
fun DrawScope.drawEnvelope(env: FloatArray, color: Color, left: Float = 0f, width: Float = size.width, from: Float = 0f, to: Float = 1f) {
    val cols = env.size / 2
    if (cols == 0) return
    val h = size.height
    val mid = h / 2
    val w = max(1f, width)
    val px = w.toInt().coerceAtLeast(1)
    val a = (from * cols).toInt().coerceIn(0, cols)
    val b = (to * cols).toInt().coerceIn(a, cols)
    val span = max(1, b - a)
    val stroke = max(1f, w / px)
    for (x in 0 until px) {
        val c0 = a + (x.toLong() * span / px).toInt()
        val c1 = max(c0 + 1, a + ((x + 1).toLong() * span / px).toInt()).coerceAtMost(b)
        var mn = 0f
        var mx = 0f
        for (c in c0 until c1) {
            mn = min(mn, env[2 * c]); mx = max(mx, env[2 * c + 1])
        }
        val y0 = mid - mx * mid * 0.95f
        val y1 = mid - mn * mid * 0.95f
        drawLine(color, Offset(left + x + 0.5f, y0), Offset(left + x + 0.5f, max(y1, y0 + 1f)), stroke)
    }
}

/**
 * A pad's small waveform: orange beyond the length the P-6 keeps, a
 * playhead while it plays.
 */
@Composable
fun MiniWave(
    envelope: FloatArray?,
    modifier: Modifier = Modifier,
    overFraction: Double? = null,
    playFraction: Double? = null,
    color: Color = LocalPyP6Colors.current.wave,
) {
    val pc = LocalPyP6Colors.current
    Canvas(modifier.clip(RoundedCornerShape(6.dp))) {
        drawRect(pc.waveBg)
        drawLine(color.copy(alpha = 0.35f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        if (envelope != null) {
            val cut = overFraction?.toFloat()?.coerceIn(0f, 1f)
            if (cut == null) drawEnvelope(envelope, color)
            else {
                drawEnvelope(envelope, color, 0f, size.width * cut, 0f, cut)
                drawEnvelope(envelope, pc.over, size.width * cut, size.width * (1 - cut), cut, 1f)
            }
        }
        playFraction?.let {
            val x = (it.toFloat() * size.width).coerceIn(0f, size.width)
            drawLine(pc.playing, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
        }
    }
}

/**
 * The editing waveform: [mono] drawn between [viewStart] and [viewEnd]
 * (fractions of the whole sample), a selection with two draggable handles,
 * optional cut lines, the length-limit shading and a playhead. Touch: drag
 * a handle to move it, drag elsewhere to mark a new region, tap to
 * [onTap] (e.g. play from there or pick a piece).
 */
@Composable
fun EditWave(
    mono: FloatArray,
    modifier: Modifier = Modifier,
    viewStart: Double = 0.0,
    viewEnd: Double = 1.0,
    selStart: Double = 0.0,
    selEnd: Double = 1.0,
    selectionEnabled: Boolean = true,
    onSelection: (Double, Double) -> Unit = { _, _ -> },
    cuts: List<Double> = emptyList(),
    highlight: Pair<Double, Double>? = null,
    overFraction: Double? = null,
    playFraction: Double? = null,
    onTap: (Double) -> Unit = {},
) {
    val pc = LocalPyP6Colors.current
    val density = LocalDensity.current
    val handleTouch = with(density) { 28.dp.toPx() }
    var widthPx by remember { mutableStateOf(1) }
    // One envelope column per pixel, computed for the width actually drawn.
    val envCache = remember(mono, viewStart, viewEnd) { HashMap<Int, FloatArray>() }
    fun envFor(w: Int): FloatArray = envCache.getOrPut(w) {
        val n = mono.size
        Edit.peakEnvelope(mono, w.coerceAtLeast(1), (viewStart * n).toInt(), (viewEnd * n).toInt())
    }
    val cur by rememberUpdatedState(Triple(selStart, selEnd, viewStart to viewEnd))
    val onSel by rememberUpdatedState(onSelection)
    val tap by rememberUpdatedState(onTap)
    val enabled by rememberUpdatedState(selectionEnabled)

    fun xToFrac(x: Float): Double {
        val (vs, ve) = cur.third
        return (vs + (x / widthPx.coerceAtLeast(1)) * (ve - vs)).coerceIn(0.0, 1.0)
    }

    fun fracToX(f: Double, width: Float, vs: Double, ve: Double) = ((f - vs) / (ve - vs) * width).toFloat()

    Box(modifier) {
        Canvas(
            Modifier.matchParentSize()
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { tap(xToFrac(it.x)) })
                }
                .pointerInput(Unit) {
                    var dragging = 0          // 1 = start handle, 2 = end handle, 3 = new region
                    var anchor = 0.0
                    detectDragGestures(
                        onDragStart = { o ->
                            if (!enabled) { dragging = 0; return@detectDragGestures }
                            val (s, e, v) = cur
                            val xs = fracToX(s, widthPx.toFloat(), v.first, v.second)
                            val xe = fracToX(e, widthPx.toFloat(), v.first, v.second)
                            dragging = when {
                                abs(o.x - xs) <= handleTouch && abs(o.x - xs) <= abs(o.x - xe) -> 1
                                abs(o.x - xe) <= handleTouch -> 2
                                else -> { anchor = xToFrac(o.x); 3 }
                            }
                        },
                        onDrag = { change, _ ->
                            val f = xToFrac(change.position.x)
                            val (s, e, _) = cur
                            val minGap = 0.002
                            when (dragging) {
                                1 -> onSel(min(f, e - minGap).coerceAtLeast(0.0), e)
                                2 -> onSel(s, max(f, s + minGap).coerceAtMost(1.0))
                                3 -> if (abs(f - anchor) > minGap) onSel(min(f, anchor), max(f, anchor))
                            }
                        },
                    )
                }
        ) {
            val vs = viewStart
            val ve = viewEnd
            drawRect(pc.waveBg)
            drawLine(pc.wave.copy(alpha = 0.35f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
            drawEnvelope(envFor(size.width.toInt()), pc.wave)
            overFraction?.let { of ->
                val x = fracToX(of, size.width, vs, ve).coerceIn(0f, size.width)
                if (x < size.width) drawRect(pc.over.copy(alpha = 0.30f), Offset(x, 0f), Size(size.width - x, size.height))
            }
            highlight?.let { (a, b) ->
                val xa = fracToX(a, size.width, vs, ve)
                val xb = fracToX(b, size.width, vs, ve)
                drawRect(pc.good.copy(alpha = 0.25f), Offset(xa, 0f), Size(xb - xa, size.height))
            }
            for (cf in cuts) {
                val x = fracToX(cf, size.width, vs, ve)
                if (x in 0f..size.width) drawLine(pc.good, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
            }
            if (selectionEnabled) {
                val xs = fracToX(selStart, size.width, vs, ve)
                val xe = fracToX(selEnd, size.width, vs, ve)
                val shade = Color.Black.copy(alpha = 0.45f)
                if (xs > 0) drawRect(shade, Offset(0f, 0f), Size(xs.coerceAtMost(size.width), size.height))
                if (xe < size.width) drawRect(shade, Offset(xe.coerceAtLeast(0f), 0f), Size(size.width - xe.coerceAtLeast(0f), size.height))
                drawHandle(xs, pc.good, left = true)
                drawHandle(xe, Color(0xFFDD5555), left = false)
            }
            playFraction?.let {
                val x = fracToX(it, size.width, vs, ve)
                if (x in 0f..size.width) drawLine(pc.playing, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
            }
        }
    }
}

/** A bracket marker, like the desktop app's green/red trim brackets, with a grab tab. */
private fun DrawScope.drawHandle(x: Float, color: Color, left: Boolean) {
    if (x < -2 || x > size.width + 2) return
    val w = 3.dp.toPx()
    val arm = 10.dp.toPx()
    drawRect(color, Offset(x - w / 2, 0f), Size(w, size.height))
    drawRect(color, Offset(if (left) x else x - arm, 0f), Size(arm, w))
    drawRect(color, Offset(if (left) x else x - arm, size.height - w), Size(arm, w))
    val tab = Path().apply {
        val cy = size.height / 2
        val r = 9.dp.toPx()
        addRoundRect(androidx.compose.ui.geometry.RoundRect(
            left = if (left) x else x - 2 * r, top = cy - r * 1.4f,
            right = if (left) x + 2 * r else x, bottom = cy + r * 1.4f, cornerRadius = CornerRadius(r / 2)))
    }
    drawPath(tab, color.copy(alpha = 0.9f))
    drawPath(tab, Color.Black.copy(alpha = 0.3f), style = Stroke(1f))
}
