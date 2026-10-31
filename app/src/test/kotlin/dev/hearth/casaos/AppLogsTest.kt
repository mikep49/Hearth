package dev.hearth.casaos

import dev.hearth.casaos.data.filterLogLines
import dev.hearth.casaos.data.logLines
import dev.hearth.casaos.data.matchRanges
import dev.hearth.casaos.data.newestLinesWithin
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogsTest {
    @Test fun `splits lines and drops the empty one after the last newline`() {
        assertEquals(listOf("one", "two", "", "three"), logLines("one\r\ntwo\n\nthree\n"))
        assertEquals(emptyList<String>(), logLines(""))
        assertEquals(listOf("no newline"), logLines("no newline"))
    }

    @Test fun `strips terminal color and title codes`() {
        val text = "\u001B[0;32mINFO\u001B[0m ready\n\u001B]0;title\u0007\u001B[2Kdone\n"
        assertEquals(listOf("INFO ready", "done"), logLines(text))
    }

    @Test fun `filter ignores case and a blank query keeps everything`() {
        val lines = listOf("Starting server", "ERROR: disk full", "error again", "ok")
        assertEquals(listOf("ERROR: disk full", "error again"), filterLogLines(lines, " Error "))
        assertEquals(lines, filterLogLines(lines, "  "))
    }

    @Test fun `finds every match without overlapping`() {
        assertEquals(listOf(0..1, 4..5), matchRanges("abxxAB", "ab"))
        assertEquals(listOf(0..1, 2..3), matchRanges("aaaa", "aa"))
        assertEquals(emptyList<IntRange>(), matchRanges("abc", ""))
    }

    @Test fun `keeps the newest lines that fit`() {
        val lines = listOf("aaaa", "bbbb", "cccc")
        assertEquals("aaaa\nbbbb\ncccc" to 3, newestLinesWithin(lines, 100))
        // Each line costs its length plus a newline.
        assertEquals("bbbb\ncccc" to 2, newestLinesWithin(lines, 10))
        assertEquals("" to 0, newestLinesWithin(lines, 3))
    }
}
