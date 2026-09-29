package io.github.rmdodhia.gesturelauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.rmdodhia.gesturelauncher.core.GestureSample
import io.github.rmdodhia.gesturelauncher.core.SampleBuilder
import io.github.rmdodhia.gesturelauncher.core.TouchPoint

/**
 * Full-area touch capture. Collects all fingers until no finger has been down for [endTimeoutMs],
 * then reports a [GestureSample]. Multi-stroke shapes and tap sequences work because the gesture only
 * ends after the timeout, not on the first finger-up.
 */
@Composable
fun CaptureSurface(
    endTimeoutMs: Long,
    onGesture: (GestureSample) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    var ink by remember { mutableStateOf<List<List<TouchPoint>>>(emptyList()) }
    var lastInk by remember { mutableStateOf<List<List<TouchPoint>>>(emptyList()) }
    val currentOnGesture by rememberUpdatedState(onGesture)
    val currentTimeout by rememberUpdatedState(endTimeoutMs)
    val inkColor = MaterialTheme.colorScheme.primary

    Box(
        modifier
            // Best effort: Android honours at most 200dp per edge, so callers also inset from the edges.
            .systemGestureExclusion()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val first = awaitPointerEvent()
                        val firstDown = first.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                        val builder = SampleBuilder(firstDown.uptimeMillis)
                        feed(builder, first)
                        lastInk = emptyList()
                        ink = builder.strokes()
                        while (true) {
                            val ev = if (builder.hasActivePointers) {
                                awaitPointerEvent()
                            } else {
                                withTimeoutOrNull(currentTimeout) { awaitPointerEvent() } ?: break
                            }
                            feed(builder, ev)
                            ink = builder.strokes()
                        }
                        val sample = builder.build(density)
                        lastInk = ink
                        ink = emptyList()
                        currentOnGesture(sample)
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val width = 6.dp.toPx()
            drawInk(lastInk, inkColor.copy(alpha = 0.25f), width)
            drawInk(ink, inkColor, width)
        }
        if (hint != null && ink.isEmpty() && lastInk.isEmpty()) {
            Text(
                hint,
                modifier = Modifier.align(Alignment.Center),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun feed(builder: SampleBuilder, event: PointerEvent) {
    for (c in event.changes) {
        val id = c.id.value
        when {
            c.pressed && !c.previousPressed -> builder.down(id, c.position.x, c.position.y, c.uptimeMillis)
            c.pressed -> {
                c.historical.forEach { h -> builder.move(id, h.position.x, h.position.y, h.uptimeMillis) }
                builder.move(id, c.position.x, c.position.y, c.uptimeMillis)
            }
            c.previousPressed -> builder.up(id, c.position.x, c.position.y, c.uptimeMillis)
        }
        c.consume()
    }
}

fun DrawScope.drawInk(strokes: List<List<TouchPoint>>, color: Color, width: Float) {
    for (s in strokes) {
        if (s.isEmpty()) continue
        if (s.size == 1) {
            drawCircle(color, radius = width * 1.5f, center = Offset(s[0].x, s[0].y))
            continue
        }
        val path = Path().apply {
            moveTo(s[0].x, s[0].y)
            for (i in 1 until s.size) lineTo(s[i].x, s[i].y)
        }
        drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Small preview of a recorded sample, scaled to fit. */
@Composable
fun SampleThumbnail(sample: GestureSample, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val pts = sample.tracks.flatMap { it.points }
        if (pts.isEmpty()) return@Canvas
        val minX = pts.minOf { it.x }
        val minY = pts.minOf { it.y }
        val w = pts.maxOf { it.x } - minX
        val h = pts.maxOf { it.y } - minY
        val pad = 6.dp.toPx()
        val scale = minOf((size.width - 2 * pad) / w.coerceAtLeast(1f), (size.height - 2 * pad) / h.coerceAtLeast(1f))
            .coerceAtMost(1f)
        val offX = (size.width - w * scale) / 2
        val offY = (size.height - h * scale) / 2
        val strokes = sample.tracks.map { t ->
            t.points.map { TouchPoint((it.x - minX) * scale + offX, (it.y - minY) * scale + offY, it.t) }
        }
        drawInk(strokes, color, 2.dp.toPx())
    }
}
