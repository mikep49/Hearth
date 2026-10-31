package dev.hearth.casaos.data

/** How far back the logs page reaches. Bigger means more to download on every refresh. */
enum class LogHistory(val lines: Int, val label: String) {
    Short(500, "Last 500 lines"),
    Medium(2_000, "Last 2,000 lines"),
    Long(10_000, "Last 10,000 lines"),
}

// Terminal color and cursor codes, which many containers print. They show up as junk like "[0;32m" otherwise.
private val ansi = Regex("""\u001B(?:\[[0-?]*[ -/]*[@-~]|][^\u0007\u001B]*(?:\u0007|\u001B\\)|[@-Z\\-_])""")

/** Splits a log into lines without terminal codes, carriage returns or the empty line after the last newline. */
fun logLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.replace(ansi, "").split('\n').map { it.removeSuffix("\r") }
    return if (lines.last().isEmpty()) lines.dropLast(1) else lines
}

/** The lines containing [query], ignoring case; all of them when it's blank. */
fun filterLogLines(lines: List<String>, query: String): List<String> {
    val q = query.trim()
    return if (q.isEmpty()) lines else lines.filter { it.contains(q, ignoreCase = true) }
}

/** Where [query] appears in [line], ignoring case, as start..end-exclusive pairs. */
fun matchRanges(line: String, query: String): List<IntRange> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val out = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val i = line.indexOf(q, from, ignoreCase = true)
        if (i < 0) break
        out += i until i + q.length
        from = i + q.length
    }
    return out
}

/**
 * The newest lines that fit in [maxChars], joined with newlines, and how many made it. The clipboard goes
 * through a size-limited channel, so a very long log is cut from the top rather than failing to copy.
 */
fun newestLinesWithin(lines: List<String>, maxChars: Int): Pair<String, Int> {
    var total = 0
    var start = lines.size
    while (start > 0) {
        val next = total + lines[start - 1].length + 1
        if (next > maxChars) break
        total = next
        start--
    }
    val kept = lines.subList(start, lines.size)
    return kept.joinToString("\n") to kept.size
}
