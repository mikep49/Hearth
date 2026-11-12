package dev.hearth.casaos.ui.terminal

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Receives keyboard input from [TerminalInputView]. */
interface TerminalInput {
    /** Printable text; [ctrl] and [alt] come from a hardware keyboard's modifier keys. */
    fun onText(text: String, ctrl: Boolean = false, alt: Boolean = false)

    /** Non-printing keys (Enter, arrows, F-keys…). Returns false if the key isn't one the terminal handles. */
    fun onKey(keyCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean): Boolean
}

/**
 * The terminal screen. Tap to bring up the keyboard, drag or fling to scroll back, pinch to change the text
 * size. A long press picks a word to copy, with handles to pick more; on an empty spot it offers to paste.
 * [inputView] receives the keyboard; the caller owns it so the extra-keys row can refocus it.
 */
@Composable
fun TerminalPane(
    viewModel: TerminalViewModel,
    inputView: TerminalInputView,
    /** Offered in the long-press menu; null while there's nothing to paste or nowhere to paste it. */
    onPaste: (() -> Unit)?,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val toolbar = LocalTextToolbar.current

    // The terminal stays dark in the light theme, like a code block: programs pick colors for a dark screen.
    val background = if (scheme.background.luminance() < 0.5f) scheme.surfaceContainerLowest else LightThemeBackground
    val accent = scheme.primary
    DisposableEffect(background, accent) {
        TerminalTheme.apply(background.toArgb(), cursor = accent.toArgb())
        viewModel.recolor()
        onDispose {}
    }

    val renderer = remember(viewModel.fontSp, density) { TerminalRenderer(with(density) { viewModel.fontSp.sp.toPx() }) }
    val pane = remember(viewModel) { PaneState(viewModel) }
    pane.renderer = renderer
    pane.inset = with(density) { TextInset.toPx() }
    pane.handleRadius = with(density) { HandleRadius.toPx() }
    pane.handleReach = with(density) { HandleReach.toPx() }
    pane.handleOverhang = with(density) { HandleOverhang.toPx() }
    val paste by rememberUpdatedState(onPaste)
    val copy by rememberUpdatedState(onCopy)

    // Debounced: pinching changes the size every frame, and each resize is a round trip to the server.
    LaunchedEffect(pane.size, renderer) {
        val size = pane.size
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        delay(80)
        val columns = floor((size.width - 2 * pane.inset) / renderer.cellWidth).toInt()
        val rows = floor((size.height - 2 * pane.inset) / renderer.cellHeight).toInt()
        viewModel.resize(columns, rows, renderer.cellWidth.roundToInt(), renderer.cellHeight.roundToInt())
    }

    // Holding a selection drag at the top or bottom edge scrolls more text into reach.
    LaunchedEffect(pane.dragging) {
        if (!pane.dragging) return@LaunchedEffect
        val edge = with(density) { 32.dp.toPx() }
        while (true) {
            delay(45)
            val at = pane.pointer ?: continue
            val step = when {
                at.y < edge -> -1
                at.y > pane.size.height - edge -> 1
                else -> 0
            }
            if (step != 0 && viewModel.scrollHistory(step)) pane.dragTo(at)
        }
    }

    // Copy, Paste and Select all in Android's own floating menu, as in any text field.
    val selection = viewModel.selection
    val menuRect = if (pane.dragging) null else pane.menuRect(selection, viewModel.topRow)
    val canPaste = paste != null
    DisposableEffect(menuRect, selection != null, canPaste) {
        if (menuRect == null) {
            toolbar.hide()
        } else {
            toolbar.showMenu(
                rect = menuRect,
                onCopyRequested = if (selection == null) null else {
                    {
                        viewModel.selectedText()?.let { copy(it) }
                        viewModel.clearSelection()
                    }
                },
                onPasteRequested = if (!canPaste) null else {
                    {
                        pane.menuAt = null
                        paste?.invoke()
                    }
                },
                onSelectAllRequested = {
                    pane.menuAt = null
                    viewModel.selectAll()
                },
            )
        }
        onDispose {}
    }
    DisposableEffect(toolbar) { onDispose { toolbar.hide() } }
    BackHandler(enabled = selection != null || pane.menuAt != null) { pane.dismiss() }

    val scroll = rememberScrollableState { delta ->
        pane.menuAt = null
        pane.scrollPending += delta
        val lines = (pane.scrollPending / pane.renderer.cellHeight).toInt()
        if (lines == 0) return@rememberScrollableState delta
        pane.scrollPending -= lines * pane.renderer.cellHeight
        // Dragging down reveals older output. At either end, stop taking the fling.
        if (viewModel.scrollBy(-lines)) delta else 0f.also { pane.scrollPending = 0f }
    }

    Box(modifier) {
        // Behind the canvas: it only needs focus to receive the keyboard, never touches.
        AndroidView(factory = { inputView }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .drawBehind {
                    viewModel.revision // redraw on new output
                    val emu = viewModel.emulator
                    drawRect(if (emu == null) background else Color(renderer.screenBackground(emu)))
                    if (emu == null) return@drawBehind
                    drawIntoCanvas {
                        val canvas = it.nativeCanvas
                        canvas.save()
                        canvas.translate(pane.inset, pane.inset)
                        renderer.draw(canvas, emu, viewModel.topRow, viewModel.selection, accent.copy(alpha = 0.38f).toArgb())
                        canvas.restore()
                    }
                    drawScrollThumb(emu, viewModel.topRow)
                },
        )
        // Not clipped like the text: a handle on the bottom line hangs below the terminal.
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { pane.size = it }
                .onGloballyPositioned { pane.origin = it.positionInRoot() }
                .semantics {
                    contentDescription = "Terminal. Double-tap to type, touch and hold to copy or paste."
                    onClick { inputView.showKeyboard(); true }
                }
                .scrollable(scroll, Orientation.Vertical)
                .pointerInput(pane) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val grabbed = pane.handleAt(down.position)
                        if (grabbed != null) {
                            down.consume()
                            pane.grab(grabbed, down.position)
                            dragSelection(pane, down.id)
                            return@awaitEachGesture
                        }
                        when (awaitIntent(down.id, down.position)) {
                            Intent.Tap -> if (!pane.dismiss()) inputView.showKeyboard()
                            Intent.LongPress -> {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (pane.pickWord(down.position)) {
                                    dragSelection(pane, down.id)
                                } else {
                                    pane.menuAt = down.position
                                    consumeUntilUp()
                                }
                            }
                            Intent.Pinch -> {
                                val startSp = viewModel.fontSp
                                var scale = 1f
                                do {
                                    val event = awaitPointerEvent()
                                    scale *= event.calculateZoom()
                                    viewModel.changeFontSize(startSp * scale)
                                    event.changes.forEach { it.consume() } // or the two fingers would scroll as well
                                } while (event.changes.any { it.pressed })
                            }
                            Intent.Other -> Unit // a scroll: the scrollable above has it
                        }
                    }
                }
                .drawBehind { pane.handles(viewModel.selection, viewModel.topRow).forEach { drawHandle(it, accent) } },
        )
        AnimatedVisibility(
            visible = viewModel.topRow != 0 && selection == null,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            SmallFloatingActionButton(
                onClick = viewModel::scrollToBottom,
                containerColor = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
            ) { Icon(Icons.Rounded.KeyboardArrowDown, "Jump to the latest output") }
        }
    }
}

/** Padding between the terminal's rounded edge and its text. */
private val TextInset = 10.dp
private val HandleRadius = 9.dp

/** How far from a handle's middle a touch still grabs it. */
private val HandleReach = 24.dp

/**
 * How far a handle may hang outside the terminal's sides, into the margin its caller leaves around it. Past
 * that the handle turns to hang the other way, as Android's do at the edge of the screen.
 */
private val HandleOverhang = 8.dp
private val LightThemeBackground = Color(0xFF14171F)

private enum class Intent { Tap, LongPress, Pinch, Other }

/** Watches a finger that just came down until it's clear what it's doing. Holding still is a long press. */
private suspend fun AwaitPointerEventScope.awaitIntent(pointer: PointerId, start: Offset): Intent = try {
    withTimeout(viewConfiguration.longPressTimeoutMillis) { awaitMove(pointer, start) }
} catch (_: PointerEventTimeoutCancellationException) {
    Intent.LongPress
}

private suspend fun AwaitPointerEventScope.awaitMove(pointer: PointerId, start: Offset): Intent {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } > 1) return Intent.Pinch
        val change = event.changes.firstOrNull { it.id == pointer } ?: return Intent.Other
        if (change.changedToUp()) {
            change.consume()
            return Intent.Tap
        }
        if (change.isConsumed || (change.position - start).getDistance() > viewConfiguration.touchSlop) return Intent.Other
    }
}

/** Moves the selection with the finger until it lifts. */
private suspend fun AwaitPointerEventScope.dragSelection(pane: PaneState, pointer: PointerId) {
    pane.dragging = true
    try {
        drag(pointer) { change ->
            change.consume()
            pane.dragTo(change.position)
        }
    } finally {
        pane.dragging = false
        pane.pointer = null
    }
}

private suspend fun AwaitPointerEventScope.consumeUntilUp() {
    do {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

/** One of the two drops that hang below the ends of a selection. [tip] is the corner that touches the text. */
private class Handle(val atStart: Boolean, val tip: Offset, val bodyLeft: Boolean, val radius: Float) {
    val center: Offset get() = Offset(tip.x + if (bodyLeft) -radius else radius, tip.y + radius)
}

private fun DrawScope.drawHandle(handle: Handle, color: Color) {
    val r = handle.radius
    drawCircle(color, r, handle.center)
    // The quarter of the circle's square nearest the text is filled in, making the drop's point.
    drawRect(color, Offset(if (handle.bodyLeft) handle.tip.x - r else handle.tip.x, handle.tip.y), Size(r, r))
}

/** A thin bar at the right edge showing how far back the view is scrolled. Hidden on the live screen. */
private fun DrawScope.drawScrollThumb(emu: TerminalEmulator, topRow: Int) {
    val history = emu.screen.activeTranscriptRows
    if (topRow == 0 || history == 0) return
    val margin = 8.dp.toPx()
    val track = size.height - 2 * margin
    val length = max(track * emu.mRows / (emu.mRows + history), 24.dp.toPx()).coerceAtMost(track)
    val y = margin + (track - length) * (history + topRow) / history
    drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(size.width - 6.dp.toPx(), y), Size(3.dp.toPx(), length), CornerRadius(1.5.dp.toPx()))
}

/** What the gestures need to know about the pane: where its cells are, and what a drag in progress is moving. */
private class PaneState(private val viewModel: TerminalViewModel) {
    lateinit var renderer: TerminalRenderer
    var inset = 0f
    var handleRadius = 0f
    var handleReach = 0f
    var handleOverhang = 0f
    var size by mutableStateOf(IntSize.Zero)
    var origin by mutableStateOf(Offset.Zero)

    /** Where a long press on an empty spot asked for the menu. */
    var menuAt by mutableStateOf<Offset?>(null)
    var dragging by mutableStateOf(false)

    /** Where the dragging finger is, for scrolling at the edges. */
    var pointer: Offset? = null
    var scrollPending = 0f

    // What a drag is moving: one handle (the other end stays put), or outwards from the word a long press picked.
    private var anchor: Cell? = null
    private var word: TerminalSelection? = null
    private var grabOffset = Offset.Zero
    private var grabbedStart = false

    private fun cellAt(position: Offset) = Cell(
        floor((position.x - inset) / renderer.cellWidth).toInt(),
        floor((position.y - inset) / renderer.cellHeight).toInt() + viewModel.topRow,
    )

    /** Clears the selection and the menu. Returns false if there was neither. */
    fun dismiss(): Boolean {
        val had = viewModel.selection != null || menuAt != null
        viewModel.clearSelection()
        menuAt = null
        return had
    }

    fun pickWord(position: Offset): Boolean {
        menuAt = null
        if (!viewModel.selectWordAt(cellAt(position))) {
            viewModel.clearSelection()
            return false
        }
        word = viewModel.selection
        anchor = null
        pointer = position
        return true
    }

    fun grab(handle: Handle, position: Offset) {
        val picked = viewModel.selection ?: return
        anchor = if (handle.atStart) picked.end else picked.start
        word = null
        grabbedStart = handle.atStart
        grabOffset = position - handle.tip
        pointer = position
    }

    fun dragTo(position: Offset) {
        pointer = position
        val fixed = anchor
        val picked = word
        if (fixed != null) {
            // The handle hangs below its line and to one side of its cell: aim at the cell's middle.
            val tip = position - grabOffset
            val half = renderer.cellWidth / 2
            viewModel.select(fixed, cellAt(Offset(tip.x + if (grabbedStart) half else -half, tip.y - renderer.cellHeight / 2)))
        } else if (picked != null) {
            val at = cellAt(position)
            when {
                at < picked.start -> viewModel.select(picked.end, at)
                at > picked.end -> viewModel.select(picked.start, at)
                else -> viewModel.select(picked.start, picked.end)
            }
        }
    }

    fun handles(selection: TerminalSelection?, topRow: Int): List<Handle> {
        val emu = viewModel.emulator
        if (selection == null || emu == null) return emptyList()
        val cw = renderer.cellWidth
        val ch = renderer.cellHeight
        val reach = 2 * handleRadius
        fun visible(cell: Cell) = cell.row - topRow in 0 until emu.mRows
        val out = ArrayList<Handle>(2)
        val start = selection.start
        if (visible(start)) {
            val tip = Offset(inset + start.col * cw, inset + (start.row - topRow + 1) * ch)
            out += Handle(atStart = true, tip, bodyLeft = tip.x - reach >= -handleOverhang, handleRadius)
        }
        val end = selection.end
        if (visible(end)) {
            val tip = Offset(inset + (end.col + 1) * cw, inset + (end.row - topRow + 1) * ch)
            out += Handle(atStart = false, tip, bodyLeft = tip.x + reach > size.width + handleOverhang, handleRadius)
        }
        return out
    }

    fun handleAt(position: Offset): Handle? {
        return handles(viewModel.selection, viewModel.topRow)
            .map { it to (it.center - position).getDistance() }
            .filter { it.second <= handleReach }
            .minByOrNull { it.second }
            ?.first
    }

    /** Where the floating menu should point, in the window's coordinates. Null when it shouldn't show. */
    fun menuRect(selection: TerminalSelection?, topRow: Int): Rect? {
        val emu = viewModel.emulator ?: return null
        if (selection == null) {
            val at = menuAt ?: return null
            return Rect(at.x, at.y - renderer.cellHeight, at.x, at.y).translate(origin)
        }
        val first = max(selection.start.row - topRow, 0)
        val last = minOf(selection.end.row - topRow, emu.mRows - 1)
        if (first > last) return null // scrolled out of view
        val oneLine = selection.start.row == selection.end.row
        val left = if (oneLine) inset + selection.start.col * renderer.cellWidth else inset
        val right = if (oneLine) inset + (selection.end.col + 1) * renderer.cellWidth else size.width - inset
        // Room for the handles, so the menu doesn't sit on them when it has to go below.
        return Rect(left, inset + first * renderer.cellHeight, right, inset + (last + 1) * renderer.cellHeight + 2.5f * handleRadius)
            .translate(origin)
    }
}

/**
 * The terminal's colors. Text and background follow the app's theme; the sixteen ANSI colors are the app's own
 * chart colors, softer than xterm's (whose pure blue is unreadable on a dark screen).
 */
internal object TerminalTheme {
    private const val FOREGROUND = 0xFFE6E8EE.toInt()
    private val ansi = longArrayOf(
        0xFF232937, 0xFFFF6B6B, 0xFF4ADE80, 0xFFFBBF24, 0xFF7AA2FF, 0xFFB794F6, 0xFF4CD9C0, 0xFFC9CED9,
        0xFF6B7386, 0xFFFF8E8E, 0xFF7CEBA3, 0xFFFFD666, 0xFFA3BFFF, 0xFFD0B8FF, 0xFF85E8D6, 0xFFFFFFFF,
    )

    /** Sets the colors new terminals start with, and go back to on a reset. */
    fun apply(background: Int, cursor: Int) {
        val defaults = TerminalColors.COLOR_SCHEME.mDefaultColors
        ansi.forEachIndexed { i, color -> defaults[i] = color.toInt() }
        defaults[TextStyle.COLOR_INDEX_FOREGROUND] = FOREGROUND
        defaults[TextStyle.COLOR_INDEX_BACKGROUND] = background
        defaults[TextStyle.COLOR_INDEX_CURSOR] = cursor
    }
}

/** Draws the emulator's screen with a monospace font, one cell per column. Mirrors Termux's renderer. */
internal class TerminalRenderer(textSizePx: Float) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = textSizePx
    }
    private val fill = Paint()
    val cellWidth: Float = text.measureText("M")
    val cellHeight: Float = ceil(text.fontSpacing)
    private val baseline: Float = ceil(-text.ascent())

    /** What fills the screen behind the text: the background, or the text color while a program reverses the two. */
    fun screenBackground(emu: TerminalEmulator): Int =
        emu.mColors.mCurrentColors[if (emu.isReverseVideo) TextStyle.COLOR_INDEX_FOREGROUND else TextStyle.COLOR_INDEX_BACKGROUND]

    /** Draws the rows from [topRow] down, with [selection] tinted in [selectionColor]. The caller fills the background. */
    fun draw(canvas: android.graphics.Canvas, emu: TerminalEmulator, topRow: Int, selection: TerminalSelection?, selectionColor: Int) {
        val palette = emu.mColors.mCurrentColors
        val reverse = emu.isReverseVideo
        val screenBg = screenBackground(emu)
        val screen = emu.screen
        val cursorRow = if (emu.shouldCursorBeVisible()) emu.cursorRow else -1
        for (r in 0 until emu.mRows) {
            val line = topRow + r
            val row = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(line))
            val cursorCol = if (line == cursorRow) emu.cursorCol else -1
            val y = r * cellHeight
            drawRow(canvas, row, emu.mColumns, y, cursorCol, palette, reverse, screenBg)
            val picked = selection?.columnsOn(line, emu.mColumns) ?: continue
            fill.color = selectionColor
            canvas.drawRect(picked.first * cellWidth, y, (picked.last + 1) * cellWidth, y + cellHeight, fill)
        }
    }

    private fun drawRow(
        canvas: android.graphics.Canvas,
        row: TerminalRow,
        columns: Int,
        y: Float,
        cursorCol: Int,
        palette: IntArray,
        reverse: Boolean,
        screenBg: Int,
    ) {
        val chars = row.mText
        val used = row.spaceUsed
        var col = 0
        var idx = 0
        var runCol = 0
        var runIdx = 0
        var runStyle = 0L
        var runCursor = false
        var runWide = false
        var started = false
        while (col < columns && idx < used) {
            val c = chars[idx]
            val surrogate = Character.isHighSurrogate(c) && idx + 1 < used
            val codePoint = if (surrogate) Character.toCodePoint(c, chars[idx + 1]) else c.code
            val width = WcWidth.width(codePoint).coerceAtLeast(1)
            val style = row.getStyle(col)
            val cursor = col == cursorCol
            // Wide characters get a run of their own so each lands exactly on its two cells.
            if (!started || style != runStyle || cursor != runCursor || width != 1 || runWide) {
                if (started) drawRun(canvas, chars, runIdx, idx, runCol, col, y, runStyle, runCursor, palette, reverse, screenBg)
                runCol = col
                runIdx = idx
                runStyle = style
                runCursor = cursor
                runWide = width != 1
                started = true
            }
            col += width
            idx += if (surrogate) 2 else 1
            // Combining marks are stored after their base character and share its cell.
            while (idx < used && WcWidth.width(chars, idx) <= 0) idx += if (Character.isHighSurrogate(chars[idx])) 2 else 1
        }
        if (started) drawRun(canvas, chars, runIdx, idx, runCol, col, y, runStyle, runCursor, palette, reverse, screenBg)
    }

    private fun drawRun(
        canvas: android.graphics.Canvas,
        chars: CharArray,
        startIdx: Int,
        endIdx: Int,
        startCol: Int,
        endCol: Int,
        y: Float,
        style: Long,
        cursor: Boolean,
        palette: IntArray,
        reverse: Boolean,
        screenBg: Int,
    ) {
        val effect = TextStyle.decodeEffect(style)
        val bold = effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0
        var fg = TextStyle.decodeForeColor(style)
        var bg = TextStyle.decodeBackColor(style)
        // Values with a full alpha byte are 24-bit colors; anything else indexes the palette.
        if (fg and OPAQUE != OPAQUE) {
            if (bold && fg in 0..7) fg += 8 // bold shows the bright variant of the basic colors
            fg = palette[fg]
        }
        if (bg and OPAQUE != OPAQUE) bg = palette[bg]
        if ((effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0) xor reverse) {
            val t = fg; fg = bg; bg = t
        }
        if (cursor) {
            bg = palette[TextStyle.COLOR_INDEX_CURSOR]
            fg = palette[TextStyle.COLOR_INDEX_BACKGROUND]
        }
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_DIM != 0) fg = dim(fg)

        val left = startCol * cellWidth
        val right = endCol * cellWidth
        if (bg != screenBg) {
            fill.color = bg
            canvas.drawRect(left, y, right, y + cellHeight, fill)
        }
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE != 0) return
        val count = endIdx - startIdx
        if (count <= 0) return

        text.color = fg
        text.isFakeBoldText = bold
        text.isUnderlineText = effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0
        text.isStrikeThruText = effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0
        text.textSkewX = if (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0) -0.25f else 0f

        // Glyphs outside the font (box drawing, emoji) can be wider than a cell: squeeze them so columns stay aligned.
        val measured = text.measureText(chars, startIdx, count)
        val expected = right - left
        val squeeze = measured > 0f && abs(measured - expected) > 0.5f
        if (squeeze) {
            canvas.save()
            canvas.scale(expected / measured, 1f, left, 0f)
        }
        canvas.drawText(chars, startIdx, count, left, y + baseline, text)
        if (squeeze) canvas.restore()
    }

    private fun dim(color: Int): Int {
        val r = (color shr 16 and 0xff) * 2 / 3
        val g = (color shr 8 and 0xff) * 2 / 3
        val b = (color and 0xff) * 2 / 3
        return OPAQUE or (r shl 16) or (g shl 8) or b
    }

    private companion object {
        const val OPAQUE = 0xff000000.toInt()
    }
}

/**
 * An invisible view that the soft keyboard types into. It reports itself as a password field so keyboards
 * don't autocorrect or hold back words, and clears its buffer after every commit.
 */
class TerminalInputView(context: Context) : View(context) {
    var input: TerminalInput? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flush()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flush()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // Some keyboards (e.g. Samsung's with spell check) delete through this instead of sending Backspace.
                repeat(beforeLength) { input?.onKey(KeyEvent.KEYCODE_DEL, ctrl = false, alt = false, shift = false) }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            private fun flush() {
                val e = editable ?: return
                if (e.isEmpty()) return
                input?.onText(e.toString())
                e.clear()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handler = input ?: return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        if (handler.onKey(keyCode, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed)) return true
        // Printable key from a hardware keyboard: take the character without Ctrl/Alt applied, then add them ourselves.
        val unicode = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (unicode == 0 || unicode and KeyCharacterMapCombining != 0) return super.onKeyDown(keyCode, event)
        handler.onText(String(Character.toChars(unicode)), event.isCtrlPressed, event.isAltPressed)
        return true
    }

    private companion object {
        const val KeyCharacterMapCombining = android.view.KeyCharacterMap.COMBINING_ACCENT
    }
}
