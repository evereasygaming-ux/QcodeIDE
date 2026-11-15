package dev.blamspot.jcode.core.term

/**
 * The session's visible screen as plain text.
 *
 * The floating console mirrors the live terminal while something else owns the screen — a Flow
 * render in the browser, a build in the run tab — so the user can watch progress without the
 * console sheet covering what they are looking at. It reads the parser's screen buffer (the same
 * rows the view paints), not the scrollback: a mirror of what is on screen is what is useful.
 *
 * Returns an empty string when the session is gone or its parser is closed.
 */
fun TerminalSessionManager.Session.visibleScreenText(): String {
    val parser = parser
    if (!parser.isOpen) return ""
    return runCatching {
        val buf = IntArray(parser.cols * VtParser.CELL_STRIDE)
        val out = StringBuilder()
        for (row in 0 until parser.rows) {
            val filled = parser.readRow(row, buf)
            val line = StringBuilder(minOf(parser.cols, filled))
            for (column in 0 until minOf(parser.cols, filled)) {
                val codePoint = buf[column * VtParser.CELL_STRIDE]
                line.append(if (codePoint == 0 || codePoint == ' '.code) ' ' else codePoint.toChar())
            }
            out.append(line.toString().trimEnd()).append('\n')
        }
        out.toString()
    }.getOrDefault("")
}
