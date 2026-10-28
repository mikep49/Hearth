package dev.hearth.casaos.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/** The app's base container: tonal fill with a hairline border so cards read as glass-like on dark. */
@Composable
fun HearthCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val color = MaterialTheme.colorScheme.surfaceContainer
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = border) {
            Column(content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = border) {
            Column(content = content)
        }
    }
}

/** Circular progress ring that animates to [fraction] (0..1). */
@Composable
fun RingGauge(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    stroke: Dp = 10.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val target = fraction.coerceIn(0f, 1f)
    val animated = remember { Animatable(0f) }
    LaunchedEffect(target) { animated.animateTo(target, tween(700, easing = FastOutSlowInEasing)) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    // drawWithCache keeps the stroke and gradient between frames (e.g. while scrolling); the animated
    // value is only read when drawing, so the animation never recomposes.
    val ring = Modifier.drawWithCache {
        val s = stroke.toPx()
        val topLeft = Offset(s / 2, s / 2)
        val arcSize = Size(this.size.width - s, this.size.height - s)
        val style = Stroke(s, cap = StrokeCap.Round)
        val brush = Brush.sweepGradient(listOf(color.copy(alpha = 0.55f), color), this.size.center)
        onDrawBehind {
            drawArc(track, 135f, 270f, false, topLeft, arcSize, style = style)
            val sweep = animated.value
            if (sweep > 0.001f) drawArc(brush, 135f, 270f * sweep, false, topLeft, arcSize, style = style)
        }
    }
    Box(modifier.size(size).then(ring), contentAlignment = Alignment.Center, content = content)
}

/** Filled line chart for a rolling window of samples. [max] pins the scale (e.g. 1f for percentages). */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    max: Float? = null,
) {
    // Paths are rebuilt only when the samples or size change, not on every frame the screen scrolls.
    Spacer(
        modifier.drawWithCache {
            if (values.size < 2) return@drawWithCache onDrawBehind {}
            val top = max ?: (values.max().coerceAtLeast(1f) * 1.15f)
            val stepX = size.width / (values.size - 1)
            fun y(v: Float) = size.height - (v / top).coerceIn(0f, 1f) * (size.height - 4.dp.toPx()) - 2.dp.toPx()

            val line = Path().apply {
                moveTo(0f, y(values[0]))
                for (i in 1 until values.size) {
                    // Smooth the polyline with midpoint cubic segments.
                    val x0 = (i - 1) * stepX
                    val x1 = i * stepX
                    val mid = (x0 + x1) / 2
                    cubicTo(mid, y(values[i - 1]), mid, y(values[i]), x1, y(values[i]))
                }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            val fillBrush = Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent))
            val lineStyle = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind {
                drawPath(fill, fillBrush)
                drawPath(line, color, style = lineStyle)
            }
        },
    )
}

/** Status dot; pulses while [pulse] is true (running / restarting). */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, pulse: Boolean = false) {
    var dot = modifier.size(8.dp)
    if (pulse) {
        val t = rememberInfiniteTransition(label = "pulse")
        val alpha = t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "alpha")
        // Pulse through the dot's own layer: each frame only changes a layer property, so neither the dot
        // nor the card around it is redrawn (that kept every visible app card repainting non-stop).
        dot = dot.graphicsLayer { this.alpha = alpha.value }
    }
    Spacer(dot.drawBehind { drawCircle(color) })
}

/** Remote icon with a lettered tile as placeholder/fallback. */
@Composable
fun AppIcon(url: String?, name: String, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    val shape = RoundedCornerShape(size * 0.28f)
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val letter = name.firstOrNull()?.uppercase() ?: "?"
    // The tile is a painter handed to Coil, so swapping it for the loaded image is a redraw, not a
    // recomposition: rows scrolling into view are composed once instead of twice.
    val tile = remember(letter, size, scheme, measurer) {
        LetterTilePainter(
            measurer.measure(
                letter,
                TextStyle(color = scheme.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp),
            ),
            Brush.linearGradient(listOf(scheme.primaryContainer, scheme.surfaceContainerHighest)),
        )
    }
    Box(modifier.size(size).clip(shape).background(scheme.surfaceContainerHigh)) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            placeholder = tile,
            error = tile,
            fallback = tile,
        )
    }
}

private class LetterTilePainter(private val letter: TextLayoutResult, private val brush: Brush) : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        drawRect(brush)
        drawText(
            letter,
            topLeft = Offset((size.width - letter.size.width) / 2f, (size.height - letter.size.height) / 2f),
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.2.sp,
    )
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            message,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action()
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}

fun formatRate(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/s"
