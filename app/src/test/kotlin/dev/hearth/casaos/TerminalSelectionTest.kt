package dev.hearth.casaos

import dev.hearth.casaos.ui.terminal.Cell
import dev.hearth.casaos.ui.terminal.TerminalSelection
import dev.hearth.casaos.ui.terminal.trimForClipboard
import dev.hearth.casaos.ui.terminal.wordAround
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSelectionTest {
    @Test fun `cells are ordered as they are read`() {
        assertTrue(Cell(79, 0) < Cell(0, 1))
        assertTrue(Cell(3, 2) < Cell(4, 2))
        assertTrue("scrollback comes before the screen", Cell(5, -1) < Cell(0, 0))
    }

    @Test fun `a selection reads the same whichever end was dragged`() {
        val forward = TerminalSelection(Cell(4, 1), Cell(2, 3))
        val backward = TerminalSelection(Cell(2, 3), Cell(4, 1))
        assertEquals(Cell(4, 1), backward.start)
        assertEquals(Cell(2, 3), backward.end)
        assertEquals(forward.start, backward.start)
        assertEquals(forward.end, backward.end)
    }

    @Test fun `each line of a selection covers the right columns`() {
        val s = TerminalSelection(Cell(4, 1), Cell(2, 3))
        assertNull(s.columnsOn(0, 80))
        assertEquals(4..79, s.columnsOn(1, 80))
        assertEquals(0..79, s.columnsOn(2, 80))
        assertEquals(0..2, s.columnsOn(3, 80))
        assertNull(s.columnsOn(4, 80))
        assertEquals(5..9, TerminalSelection(Cell(9, 0), Cell(5, 0)).columnsOn(0, 80))
    }

    @Test fun `a selection follows its text as new output scrolls it up`() {
        val s = TerminalSelection(Cell(4, 1), Cell(2, 3))
        assertEquals(s, s.scrolledUp(0, oldestRow = -100))
        assertEquals(TerminalSelection(Cell(4, -4), Cell(2, -2)), s.scrolledUp(5, oldestRow = -100))
    }

    @Test fun `a selection is cut short where its text leaves the scrollback, then dropped`() {
        val s = TerminalSelection(Cell(4, -9), Cell(2, -7))
        // The oldest line kept is -10: after two more lines the selection's first line is gone.
        assertEquals(TerminalSelection(Cell(0, -10), Cell(2, -9)), s.scrolledUp(2, oldestRow = -10))
        assertEquals(TerminalSelection(Cell(0, -10), Cell(2, -10)), s.scrolledUp(3, oldestRow = -10))
        assertNull(s.scrolledUp(4, oldestRow = -10))
        // Dragged upwards, so the anchor is the later end: it stays the anchor.
        val upward = TerminalSelection(Cell(2, -7), Cell(4, -9))
        assertEquals(TerminalSelection(Cell(2, -9), Cell(0, -10)), upward.scrolledUp(2, oldestRow = -10))
    }

    @Test fun `a long press picks the whole word, path or address`() {
        val line = "ls  /DATA/AppData  192.168.1.20:8096 "
        fun pick(col: Int) = wordAround(col, line.length) { line[it] == ' ' }?.let { line.substring(it.first, it.last + 1) }
        assertEquals("ls", pick(0))
        assertEquals("ls", pick(1))
        assertNull("blank space picks nothing", pick(2))
        assertEquals("/DATA/AppData", pick(4))
        assertEquals("/DATA/AppData", pick(16))
        assertEquals("192.168.1.20:8096", pick(25))
        assertNull(pick(line.length - 1))
        assertNull("outside the line", pick(line.length))
        assertNull(pick(-1))
        assertEquals("a word that fills the line", 0..3, wordAround(2, 4) { false })
    }

    @Test fun `copied text loses the blank lines below it`() {
        assertEquals("one\ntwo", trimForClipboard("one\ntwo\n\n\n"))
        assertEquals("", trimForClipboard("\n\n"))
    }

    @Test fun `more text than the clipboard takes keeps the newest whole lines`() {
        val text = (1..100).joinToString("\n") { "line $it" }
        val kept = trimForClipboard(text, maxChars = 30)
        assertTrue(kept.length <= 30)
        assertTrue(kept.endsWith("line 99\nline 100"))
        assertTrue("starts on a whole line", kept.startsWith("line "))
        assertEquals("one long line is cut rather than lost", 30, trimForClipboard("x".repeat(50), maxChars = 30).length)
    }
}
