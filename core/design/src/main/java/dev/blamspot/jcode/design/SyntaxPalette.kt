package dev.blamspot.jcode.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Token colors for syntax highlighting, expressed as a theme value rather than a hardcoded pair of
 * dark/light objects. Every bundle carries one of each; the default pair keeps JCode's existing
 * Catppuccin token colors for bundles that do not override them.
 */
@Immutable
data class SyntaxPalette(
    val keyword: Color,
    val type: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val function: Color,
    val variable: Color,
    val constant: Color,
    val property: Color,
    val operator: Color,
    val annotation: Color,
)

/**
 * The sixteen ANSI colors a terminal session draws from, plus the foreground/background/cursor and
 * selection tints. Kept as plain colors so a bundle is an immutable value object — no paints, no
 * bitmaps, nothing allocated per frame.
 */
@Immutable
data class AnsiPalette(
    val normal: List<Color>,
    val bright: List<Color>,
    val foreground: Color,
    val background: Color,
    val cursor: Color,
    val selection: Color,
    val selectionHandle: Color,
) {
    init {
        require(normal.size == 8) { "normal ANSI palette must have 8 colors" }
        require(bright.size == 8) { "bright ANSI palette must have 8 colors" }
    }

    /** The 16 study colors in ANSI order (normal 0-7, then bright 8-15), packed as ARGB ints. */
    fun toArgbArray(): IntArray {
        val out = IntArray(16)
        normal.forEachIndexed { index, color -> out[index] = color.toArgb() }
        bright.forEachIndexed { index, color -> out[8 + index] = color.toArgb() }
        return out
    }
}

// Defaults match the token colors the editor has always used (Catppuccin Mocha / Latte) and the
// xterm colors the terminal has always used, so bundles that do not opt in look exactly as before.
val DefaultDarkSyntax = SyntaxPalette(
    keyword = Color(0xFFCBA6F7),
    type = Color(0xFF89DCEB),
    string = Color(0xFFA6E3A1),
    comment = Color(0xFF7F849C),
    number = Color(0xFFFAB387),
    function = Color(0xFF89B4FA),
    variable = Color(0xFFB4BEFE),
    constant = Color(0xFFEBA0AC),
    property = Color(0xFF94E2D5),
    operator = Color(0xFFBAC2DE),
    annotation = Color(0xFFF9E2AF),
)

val DefaultLightSyntax = SyntaxPalette(
    keyword = Color(0xFF8839EF),
    type = Color(0xFF179299),
    string = Color(0xFF40A02B),
    comment = Color(0xFF8C8FA1),
    number = Color(0xFFFE640B),
    function = Color(0xFF1E66F5),
    variable = Color(0xFF7287FD),
    constant = Color(0xFFE64553),
    property = Color(0xFF04A5E5),
    operator = Color(0xFF6C6F85),
    annotation = Color(0xFFDF8E1D),
)

val DefaultDarkAnsi = AnsiPalette(
    normal = listOf(
        Color(0xFF000000), Color(0xFFCD0000), Color(0xFF00CD00), Color(0xFFCDCD00),
        Color(0xFF0000EE), Color(0xFFCD00CD), Color(0xFF00CDCD), Color(0xFFE5E5E5),
    ),
    bright = listOf(
        Color(0xFF7F7F7F), Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFFFFFF00),
        Color(0xFF5C5CFF), Color(0xFFFF00FF), Color(0xFF00FFFF), Color(0xFFFFFFFF),
    ),
    foreground = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    cursor = Color(0xFFFFFFFF),
    selection = Color(0x643B82F6),
    selectionHandle = Color(0xFF3B82F6),
)

val DefaultLightAnsi = AnsiPalette(
    normal = listOf(
        Color(0xFF000000), Color(0xFFCD0000), Color(0xFF00CD00), Color(0xFFCDCD00),
        Color(0xFF0000EE), Color(0xFFCD00CD), Color(0xFF00CDCD), Color(0xFFE5E5E5),
    ),
    bright = listOf(
        Color(0xFF7F7F7F), Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFFFFFF00),
        Color(0xFF5C5CFF), Color(0xFFFF00FF), Color(0xFF00FFFF), Color(0xFFFFFFFF),
    ),
    foreground = Color(0xFF000000),
    background = Color(0xFFFFFFFF),
    cursor = Color(0xFF000000),
    selection = Color(0x643B82F6),
    selectionHandle = Color(0xFF3B82F6),
)

val LocalSyntaxPalette = compositionLocalOf { DefaultDarkSyntax }
val LocalAnsiPalette = compositionLocalOf { DefaultDarkAnsi }
