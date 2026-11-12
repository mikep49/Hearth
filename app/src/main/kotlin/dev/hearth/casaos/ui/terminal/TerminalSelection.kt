package dev.hearth.casaos.ui.terminal

/**
 * One character cell of the terminal. Row 0 is the top line of the live screen and older lines, up in the
 * scrollback, are negative, so a cell keeps naming the same text while the view scrolls.
 */
data class Cell(val col: Int, val row: Int) : Comparable<Cell> {
    override fun compareTo(other: Cell): Int = if (row != other.row) row.compareTo(other.row) else col.compareTo(other.col)
}

/**
 * The text picked out for copying: every cell between [anchor] and [focus], both included, in reading order.
 * [anchor] stays put while a drag moves [focus], which may end up on either side of it.
 */
data class TerminalSelection(val anchor: Cell, val focus: Cell) {
    val start: Cell get() = minOf(anchor, focus)
    val end: Cell get() = maxOf(anchor, focus)

    /** The columns selected on [row] of a terminal [columns] wide, or null if nothing on that row is. */
    fun columnsOn(row: Int, columns: Int): IntRange? {
        val s = start
        val e = end
        if (row < s.row || row > e.row) return null
        return (if (row == s.row) s.col else 0)..(if (row == e.row) e.col else columns - 1)
    }

    /**
     * The same text after new output pushed everything up by [lines]. Lines above [oldestRow] have left the
     * scrollback: the selection is cut short there, and is null once all of it has gone.
     */
    fun scrolledUp(lines: Int, oldestRow: Int): TerminalSelection? {
        if (lines == 0) return this
        if (end.row - lines < oldestRow) return null
        fun Cell.moved() = if (row - lines < oldestRow) Cell(0, oldestRow) else Cell(col, row - lines)
        return TerminalSelection(anchor.moved(), focus.moved())
    }
}

/**
 * The run of cells without a blank around [col] on a line [columns] wide: a word, path, address or hash,
 * which is what a long press picks. Null when [col] is blank.
 */
fun wordAround(col: Int, columns: Int, isBlank: (Int) -> Boolean): IntRange? {
    if (col !in 0 until columns || isBlank(col)) return null
    var first = col
    while (first > 0 && !isBlank(first - 1)) first--
    var last = col
    while (last < columns - 1 && !isBlank(last + 1)) last++
    return first..last
}

/** The clipboard takes about a megabyte; a full scrollback of wide lines can be more. Keeps the newest text. */
fun trimForClipboard(text: String, maxChars: Int = 300_000): String {
    val trimmed = text.trimEnd('\n', ' ')
    if (trimmed.length <= maxChars) return trimmed
    val cut = trimmed.substring(trimmed.length - maxChars)
    // Start on a whole line rather than partway through one.
    return cut.substringAfter('\n', cut)
}
