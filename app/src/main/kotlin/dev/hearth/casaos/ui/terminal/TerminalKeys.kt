package dev.hearth.casaos.ui.terminal

/** Turns typed characters into what a terminal sends. Kept free of Android types so it can be unit tested. */
object TerminalKeys {
    const val ESC = "\u001b"
    const val DEL = "\u007f"

    /**
     * Keyboards send "\n" for Enter but shells expect a carriage return. With [ctrl] held, letters and a few
     * symbols become control codes (Ctrl+C is 0x03); [alt] prefixes each character with Escape.
     */
    fun encode(text: String, ctrl: Boolean = false, alt: Boolean = false): String {
        val out = StringBuilder(text.length * 2)
        for (c in text) {
            var ch = if (c == '\n') '\r' else c
            if (ctrl) controlCode(ch)?.let { ch = it }
            if (alt) out.append(ESC)
            out.append(ch)
        }
        return out.toString()
    }

    fun controlCode(c: Char): Char? = when (c) {
        in 'a'..'z' -> (c - 'a' + 1).toChar()
        in 'A'..'Z' -> (c - 'A' + 1).toChar()
        ' ', '@', '2' -> 0.toChar()
        '[', '3' -> 27.toChar()
        '\\', '4' -> 28.toChar()
        ']', '5' -> 29.toChar()
        '^', '6' -> 30.toChar()
        '_', '-', '7' -> 31.toChar()
        '?', '8' -> 127.toChar()
        else -> null
    }
}
