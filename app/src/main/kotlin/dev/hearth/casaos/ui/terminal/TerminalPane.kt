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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Receives keyboard input from [TerminalInputView]. */
interface TerminalInput {
    /** Printable text; [ctrl] and [alt] come from a hardware keyboard's modifier keys. */
    fun onText(text: String, ctrl: Boolean = false, alt: Boolean = false)

    /** Non-printing keys (Enter, arrows, F-keys…). Returns false if the key isn't one the terminal handles. */
    fun onKey(keyCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean): Boolean
}

/**
 * The terminal screen. Tap to bring up the keyboard, drag to scroll back, pinch to change the text size.
 * [inputView] receives the keyboard; the caller owns it so the extra-keys row can refocus it.
 */
@Composable
fun TerminalPane(viewModel: TerminalViewModel, inputView: TerminalInputView, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    var fontSp by rememberSaveable { mutableFloatStateOf(12f) }
    val renderer = remember(fontSp, density) { TerminalRenderer(with(density) { fontSp.sp.toPx() }) }
    // The gesture handler must not restart when the font changes, or every pinch step would cut the pinch short.
    val current by rememberUpdatedState(renderer)
    var size by remember { mutableStateOf(IntSize.Zero) }

    // Debounced: pinching changes the size every frame, and each resize is a round trip to the server.
    LaunchedEffect(size, renderer) {
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        delay(80)
        val columns = floor(size.width / renderer.cellWidth).toInt()
        val rows = (size.height / renderer.cellHeight).toInt()
        viewModel.resize(columns, rows, renderer.cellWidth.roundToInt(), renderer.cellHeight.roundToInt())
    }

    Box(modifier) {
        // Behind the canvas: it only needs focus to receive the keyboard, never touches.
        AndroidView(factory = { inputView }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size = it }
                .semantics { contentDescription = "Terminal. Double-tap to type." }
                .pointerInput(Unit) { detectTapGestures(onTap = { inputView.showKeyboard() }) }
                .pointerInput(Unit) {
                    var pending = 0f
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) {
                            // Half-sp steps keep a slow pinch from resizing the remote terminal constantly.
                            val next = ((fontSp * zoom) * 2).roundToInt() / 2f
                            fontSp = next.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
                        }
                        pending += pan.y
                        val lines = (pending / current.cellHeight).toInt()
                        if (lines != 0) {
                            pending -= lines * current.cellHeight
                            viewModel.scrollBy(-lines) // dragging down reveals older output
                        }
                    }
                }
                .drawBehind {
                    viewModel.revision // redraw on new output
                    val emu = viewModel.emulator ?: return@drawBehind
                    drawIntoCanvas { renderer.draw(it.nativeCanvas, emu, viewModel.topRow) }
                },
        )
    }
}

private const val MIN_FONT_SP = 8f
private const val MAX_FONT_SP = 24f

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

    fun draw(canvas: android.graphics.Canvas, emu: TerminalEmulator, topRow: Int) {
        val palette = emu.mColors.mCurrentColors
        val reverse = emu.isReverseVideo
        val screenBg = palette[if (reverse) TextStyle.COLOR_INDEX_FOREGROUND else TextStyle.COLOR_INDEX_BACKGROUND]
        canvas.drawColor(screenBg)
        val screen = emu.screen
        val cursorRow = if (emu.shouldCursorBeVisible()) emu.cursorRow else -1
        for (r in 0 until emu.mRows) {
            val line = topRow + r
            val row = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(line))
            val cursorCol = if (line == cursorRow) emu.cursorCol else -1
            drawRow(canvas, row, emu.mColumns, r * cellHeight, cursorCol, palette, reverse, screenBg)
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
