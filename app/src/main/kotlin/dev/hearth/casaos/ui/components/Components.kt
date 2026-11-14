package dev.hearth.casaos.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.hearth.casaos.ui.theme.LocalUiStyle
import dev.hearth.casaos.ui.theme.UiStyle
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import kotlin.math.max
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/** How wide a column of cards, rows or form fields gets before it looks stretched. Wider screens centre it. */
val ReadableWidth = 720.dp

/**
 * Centres the content in a column no wider than [max]. Put it after `verticalScroll` so the margins
 * either side still scroll, and before the side padding so that comes out of [max].
 */
fun Modifier.readableWidth(max: Dp = ReadableWidth): Modifier = layout { measurable, constraints ->
    val cap = max.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = minOf(constraints.minWidth, cap), maxWidth = minOf(constraints.maxWidth, cap)),
    )
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
    layout(width, placeable.height) { placeable.placeRelative((width - placeable.width) / 2, 0) }
}

/**
 * The same for lazy lists, which take their side padding as content padding: [content] gets the padding
 * that keeps rows within [max], and at least [min] from the edges.
 */
@Composable
fun ReadableGutter(min: Dp, max: Dp = ReadableWidth, content: @Composable (side: Dp) -> Unit) {
    BoxWithConstraints { content(((maxWidth - max) / 2).coerceAtLeast(0.dp) + min) }
}

/**
 * Keeps [content] out from under a camera cutout at the side of the screen, as on a phone turned on its
 * side. The strip beside the cutout is filled with the background colour.
 */
@Composable
fun ClearOfCutout(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
    ) { content() }
}

/** Corner radius of cards, and of the outer corners of a grouped list. */
val CardRadius = 24.dp

/** Corner radius where two rows of a grouped list meet. */
private val InnerRadius = 6.dp

/** The line between rows of a grouped list in One UI, and how far it stops short of the card's sides. */
private val Hairline = 1.dp
private val HairlineInset = 20.dp

/** True in the One UI style, where the rows of a grouped list join into one card with lines between them. */
private val joinedRows: Boolean
    @Composable @ReadOnlyComposable get() = LocalUiStyle.current == UiStyle.OneUi

/** The gap between rows of a grouped list in a lazy list: 2dp on Android, none in One UI, where rows touch. */
val GroupGap: Dp
    @Composable @ReadOnlyComposable get() = if (joinedRows) 0.dp else 2.dp

/** Padding inside a card that shows a block of content (charts, forms, choices). */
val CardPadding = 20.dp

/**
 * The shape of row [index] of [count] in a grouped list: round outer corners at the ends, tight ones
 * where rows meet, like Android's own Settings. Used where rows can't share a parent, as in lazy lists.
 * In One UI the rows meet squarely, and a [HearthCard] or [ListRow] given this shape draws the line above itself.
 */
@Composable
@ReadOnlyComposable
fun groupShape(index: Int, count: Int): Shape {
    if (joinedRows) return JoinedShape(first = index == 0, last = index == count - 1)
    val top = if (index == 0) CardRadius else InnerRadius
    val bottom = if (index == count - 1) CardRadius else InnerRadius
    return RoundedCornerShape(top, top, bottom, bottom)
}

/** A row of a One UI group in a lazy list. It also tells the row whether there is a row above to draw a line against. */
private data class JoinedShape(val first: Boolean, val last: Boolean) : Shape {
    private val shape = RoundedCornerShape(
        topStart = if (first) CardRadius else 0.dp,
        topEnd = if (first) CardRadius else 0.dp,
        bottomEnd = if (last) CardRadius else 0.dp,
        bottomStart = if (last) CardRadius else 0.dp,
    )

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        shape.createOutline(size, layoutDirection, density)
}

/** The line along the top of a row that has [shape] and a row above it. It's drawn over the row, so it goes inside the row's background. */
private fun Modifier.joinedRowLine(shape: Shape, color: Color): Modifier =
    if (shape is JoinedShape && !shape.first) {
        drawWithContent {
            drawContent()
            val y = Hairline.toPx() / 2
            drawLine(color, Offset(HairlineInset.toPx(), y), Offset(size.width - HairlineInset.toPx(), y), Hairline.toPx())
        }
    } else this

/** How many columns `GridCells.Adaptive(min)` makes in [width] with [spacing] between them. */
fun adaptiveColumns(width: Dp, min: Dp, spacing: Dp): Int = max(1, ((width + spacing) / (min + spacing)).toInt())

/** The app's base container: a tonal fill a step up from the background. */
@Composable
fun HearthCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(CardRadius),
    content: @Composable ColumnScope.() -> Unit,
) {
    val color = MaterialTheme.colorScheme.surfaceContainer
    val line = Modifier.joinedRowLine(shape, MaterialTheme.colorScheme.outlineVariant)
    if (onLongClick != null) {
        // Surface's own onClick has no long press. Clicking inside it keeps the ripple within the rounded corners.
        Surface(modifier = modifier, shape = shape, color = color) {
            Column(line.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick), content = content)
        }
    } else if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color) {
            Column(line, content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color) {
            Column(line, content = content)
        }
    }
}

/**
 * Rows grouped into one rounded block: 2dp apart on Android, joined with a line between them in One UI.
 * Each child is one row: use [ListRow], or a [HearthCard] with [GroupRowShape] for anything else.
 * Clipping the column rounds the outer corners of the first and last rows.
 */
@Composable
fun ListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val joined = joinedRows
    val card = MaterialTheme.colorScheme.surfaceContainer
    val line = MaterialTheme.colorScheme.outlineVariant
    // The lines aren't drawn one by one: the rows sit a hairline apart on a card-colored block with a
    // strip of the line color down its middle, which shows through the gaps.
    val lines = if (joined) {
        Modifier.drawBehind {
            drawRect(card)
            drawRect(line, Offset(HairlineInset.toPx(), 0f), Size(size.width - HairlineInset.toPx() * 2, size.height))
        }
    } else Modifier
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(CardRadius)).then(lines),
        verticalArrangement = Arrangement.spacedBy(if (joined) Hairline else GroupGap),
        content = content,
    )
}

/** The shape of a row inside a [ListGroup]; the group rounds the outer corners. */
val GroupRowShape: Shape
    @Composable @ReadOnlyComposable get() = if (joinedRows) RectangleShape else SegmentShape

private val SegmentShape: Shape = RoundedCornerShape(InnerRadius)

/** An icon on a tinted rounded tile, leading a row. */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    container: Color = tint.copy(alpha = 0.14f),
    size: Dp = 40.dp,
) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.55f), tint = tint)
    }
}

/**
 * One row of a list: a leading icon or tile, a title with an optional subtitle, and something trailing.
 * Rows in a [ListGroup] get the group's look; pass [shape] for rows that stand alone or sit in a lazy list.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    shape: Shape = GroupRowShape,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** Turns the whole row into a switch, for rows whose trailing content is a [Switch] with no click of its own. */
    toggle: Pair<Boolean, (Boolean) -> Unit>? = null,
    /** What a [toggle] row is to accessibility services: a switch, or a checkbox for a row in a list to pick from. */
    toggleRole: Role = Role.Switch,
) {
    val alpha = if (enabled) 1f else 0.45f
    val click = when {
        toggle != null -> Modifier.toggleable(toggle.first, enabled = enabled, role = toggleRole, onValueChange = toggle.second)
        onLongClick != null -> Modifier.combinedClickable(enabled = enabled, onClick = onClick ?: {}, onLongClick = onLongClick)
        onClick != null -> Modifier.clickable(enabled = enabled, onClick = onClick)
        else -> Modifier
    }
    Surface(modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.joinedRowLine(shape, MaterialTheme.colorScheme.outlineVariant).then(click).heightIn(min = 64.dp).padding(start = 16.dp, end = if (trailing != null) 12.dp else 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> Box(Modifier.alpha(alpha)) { leading() }
                icon != null -> IconTile(icon, Modifier.alpha(alpha), tint = iconTint)
            }
            Column(Modifier.weight(1f).padding(start = if (leading != null || icon != null) 16.dp else 0.dp, end = 8.dp).alpha(alpha)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing?.invoke()
        }
    }
}

/** A row that's a switch, with the whole row as its touch target. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    ListRow(
        title, modifier, subtitle = subtitle, icon = icon, enabled = enabled,
        toggle = checked to onCheckedChange,
        trailing = { HearthSwitch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/**
 * The app's switch: Material's on Android. One UI's is plainer, with a white thumb that stays one size
 * and a track with no outline.
 */
@Composable
fun HearthSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (LocalUiStyle.current != UiStyle.OneUi) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
        return
    }
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        // Material shrinks the thumb when off unless it holds something, so it holds nothing.
        thumbContent = {},
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = on,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = off,
            uncheckedBorderColor = Color.Transparent,
            disabledCheckedThumbColor = Color.White.copy(alpha = 0.6f),
            disabledCheckedTrackColor = on.copy(alpha = 0.38f),
            disabledUncheckedThumbColor = Color.White.copy(alpha = 0.6f),
            disabledUncheckedTrackColor = off.copy(alpha = 0.12f),
            disabledUncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** The chevron that ends a row which opens another page. */
@Composable
fun Chevron() {
    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The title at the top of each tab, with a line under it and actions beside it, so every tab starts the
 * same way. [subtitle] is free-form so a tab can put a server switcher or a folder path there.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                    LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                ) { subtitle() }
            }
        }
        actions()
    }
}

/** A plain-text [ScreenHeader] subtitle. */
@Composable
fun HeaderSubtitle(text: String) {
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The back arrow at the start of a page's top bar. */
@Composable
fun BackButton(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
}

/** Top bar colours for pages: the page's own background, lifting to a tint once the content scrolls under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun hearthTopBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
)

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

/**
 * Filled line chart for a rolling window of samples. [max] pins the scale (e.g. 1f for percentages).
 * With [slots], the chart is that many samples wide and fills from the right, so the time one step
 * stands for doesn't change while the first samples come in.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    max: Float? = null,
    slots: Int? = null,
) {
    // Paths are rebuilt only when the samples or size change, not on every frame the screen scrolls.
    Spacer(
        modifier.drawWithCache {
            if (values.size < 2) return@drawWithCache onDrawBehind {}
            val top = max ?: (values.max().coerceAtLeast(1f) * 1.15f)
            val stepX = size.width / (maxOf(slots ?: 0, values.size) - 1)
            val startX = size.width - (values.size - 1) * stepX
            fun y(v: Float) = size.height - (v / top).coerceIn(0f, 1f) * (size.height - 4.dp.toPx()) - 2.dp.toPx()

            val line = Path().apply {
                moveTo(startX, y(values[0]))
                for (i in 1 until values.size) {
                    // Smooth the polyline with midpoint cubic segments.
                    val x0 = startX + (i - 1) * stepX
                    val x1 = startX + i * stepX
                    val mid = (x0 + x1) / 2
                    cubicTo(mid, y(values[i - 1]), mid, y(values[i]), x1, y(values[i]))
                }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(startX, size.height)
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
fun AppIcon(url: String?, name: String, modifier: Modifier = Modifier, size: Dp = 52.dp, grayscale: Boolean = false) {
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
            colorFilter = if (grayscale) GrayscaleFilter else null,
        )
    }
}

private val GrayscaleFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

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

/** The small caps title inside a card, e.g. PROCESSOR on Home. Groups of cards or rows use [SectionHeader]. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.2.sp,
    )
}

/** The title above a group of rows or cards, as in Android's Settings. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** Small print under a group of rows. */
@Composable
fun GroupFootnote(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            IconTile(
                icon,
                Modifier.padding(bottom = 20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                size = 72.dp,
            )
        }
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

/** A rounded search box with a clear button. The keyboard's search key just hides the keyboard: results filter as you type. */
@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, "Clear search") }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = RoundedCornerShape(28.dp),
        // A filled pill, outlined in the accent only while typing.
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedBorderColor = Color.Transparent,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier.fillMaxWidth(),
    )
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
