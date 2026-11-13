package dev.hearth.casaos.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The percentages the top of a [UsageChart] can stand for. Each halves to a figure that reads well on the middle line. */
private val ScaleSteps = intArrayOf(5, 10, 20, 50, 100)

/**
 * The percentage at the top of the graph for these readings (each 0..1): the smallest step that leaves
 * some room above the highest one. A quiet server gets a 0–5% graph, so a 1% reading shows as a line that
 * moves, not one lying flat along the bottom.
 */
internal fun chartScale(values: List<Float>): Int {
    val peak = (values.maxOrNull() ?: 0f) * 100
    return ScaleSteps.firstOrNull { peak <= it * 0.9f } ?: 100
}

/** The label on the line halfway up a graph that tops out at [scale] percent. */
internal fun halfScaleLabel(scale: Int): String = if (scale % 2 == 0) "${scale / 2}%" else "${scale / 2}.5%"

/**
 * The processor's headline figure. Below 10% it keeps one decimal, since rounding to a whole number
 * would hide most of what the graph beside it shows.
 */
internal fun formatCpuTotal(percent: Double): String {
    val tenths = (percent * 10).roundToInt()
    return if (tenths in 1..99 && tenths % 10 != 0) "${tenths / 10.0}%" else "${percent.roundToInt()}%"
}

/**
 * A filled line graph of [values] (each 0..1, oldest first) whose scale follows the readings: see
 * [chartScale]. The scale is written beside the graph, so a zoomed-in graph can't be mistaken for a busy
 * server. The graph is [slots] readings wide and fills from the right, so the time one step stands for
 * never changes.
 */
@Composable
internal fun UsageChart(
    values: List<Float>,
    slots: Int,
    color: Color,
    modifier: Modifier = Modifier,
    /** Also label the line halfway up, on graphs tall enough for three labels. */
    labelMiddle: Boolean = false,
) {
    val shown = remember(values, slots) { values.takeLast(slots) }
    val scale = chartScale(shown)
    // Glides to a new scale rather than jumping. Only read while drawing, so it never recomposes.
    val top = animateFloatAsState(scale / 100f, tween(500), label = "scale")
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val cardColor = MaterialTheme.colorScheme.surfaceContainer

    Spacer(
        modifier.drawWithCache {
            val topLabel = measurer.measure("$scale%", labelStyle)
            val middleLabel = if (labelMiddle) measurer.measure(halfScaleLabel(scale), labelStyle) else null
            val zeroLabel = measurer.measure("0", labelStyle)
            // As wide as the widest label there can be, so the graph keeps its width when the scale changes.
            val labelsWidth = measurer.measure("100%", labelStyle).size.width
            val plotWidth = size.width - labelsWidth - 8.dp.toPx()
            // Labels are centred on their lines, so the top and bottom lines sit half a label in.
            val topY = topLabel.size.height / 2f
            val bottomY = size.height - topY
            val middleY = (topY + bottomY) / 2

            val stepX = plotWidth / (slots - 1).coerceAtLeast(1)
            fun x(i: Int) = plotWidth - (shown.lastIndex - i) * stepX
            val ceiling = top.value
            fun y(v: Float) = bottomY - (v / ceiling).coerceIn(0f, 1f) * (bottomY - topY)

            val line = Path()
            val fill = Path()
            if (shown.size >= 2) {
                line.moveTo(x(0), y(shown[0]))
                for (i in 1..shown.lastIndex) {
                    // Smooth the polyline with midpoint cubic segments.
                    val mid = (x(i - 1) + x(i)) / 2
                    line.cubicTo(mid, y(shown[i - 1]), mid, y(shown[i]), x(i), y(shown[i]))
                }
                fill.addPath(line)
                fill.lineTo(plotWidth, bottomY)
                fill.lineTo(x(0), bottomY)
                fill.close()
            }
            val fillBrush = Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent), topY, bottomY)
            val lineStyle = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val gridWidth = 1.dp.toPx()
            val dashes = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            val latest = shown.lastOrNull()?.let { Offset(plotWidth, y(it)) }

            onDrawBehind {
                drawLine(gridColor, Offset(0f, topY), Offset(plotWidth, topY), gridWidth, pathEffect = dashes)
                drawLine(gridColor, Offset(0f, middleY), Offset(plotWidth, middleY), gridWidth, pathEffect = dashes)
                drawLine(gridColor, Offset(0f, bottomY), Offset(plotWidth, bottomY), gridWidth)
                fun label(text: TextLayoutResult, y: Float) =
                    drawText(text, topLeft = Offset(size.width - text.size.width, y - text.size.height / 2f))
                label(topLabel, topY)
                middleLabel?.let { label(it, middleY) }
                label(zeroLabel, bottomY)

                drawPath(fill, fillBrush)
                drawPath(line, color, style = lineStyle)
                // The newest reading, ringed in the card's colour so it stands clear of the line.
                latest?.let {
                    drawCircle(cardColor, 5.dp.toPx(), it)
                    drawCircle(color, 3.dp.toPx(), it)
                }
            }
        },
    )
}
