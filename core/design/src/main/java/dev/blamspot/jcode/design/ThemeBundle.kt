package dev.blamspot.jcode.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * A selectable look: a Material color scheme for dark and light, matching semantic colors, and an
 * optional UI font. Built-in bundles are defined below; the same shape is what a YAML/asset bundle
 * extension would deserialize into, so disk-loaded bundles can be added later without touching the UI.
 */
@Immutable
data class ThemeBundle(
    val id: String,
    val name: String,
    val description: String,
    val author: String = "JCode",
    val dark: ColorScheme,
    val light: ColorScheme,
    val darkSemantic: JCodeSemanticColors,
    val lightSemantic: JCodeSemanticColors,
    val darkSyntax: SyntaxPalette = DefaultDarkSyntax,
    val lightSyntax: SyntaxPalette = DefaultLightSyntax,
    val darkAnsi: AnsiPalette = DefaultDarkAnsi,
    val lightAnsi: AnsiPalette = DefaultLightAnsi,
) {
    fun colorScheme(darkTheme: Boolean): ColorScheme = if (darkTheme) dark else light
    fun semanticColors(darkTheme: Boolean): JCodeSemanticColors = if (darkTheme) darkSemantic else lightSemantic
    fun syntaxPalette(darkTheme: Boolean): SyntaxPalette = if (darkTheme) darkSyntax else lightSyntax
    fun ansiPalette(darkTheme: Boolean): AnsiPalette = if (darkTheme) darkAnsi else lightAnsi
}

object ThemeBundleRegistry {
    val builtIns: List<ThemeBundle> = listOf(
        terminalBundle,
        catppuccinBundle,
        pierreDarkBundle,
        nightOwlBundle,
    )

    val default: ThemeBundle get() = terminalBundle

    fun byId(id: String?): ThemeBundle = builtIns.firstOrNull { it.id == id } ?: default
}

// --- Built-in bundles ------------------------------------------------------------------------

// Terminal — QCode's default. A flat near-black/near-white canvas with the sixteen ANSI colors
// doing all the talking: the editor, the terminal grid and the UI chrome share one palette, so a
// program's output and its source read as the same system. No gradients, no bitmaps.
private val terminalNormalDark = listOf(
    Color(0xFF1A1A1A), Color(0xFFE06C75), Color(0xFF98C379), Color(0xFFE5C07B),
    Color(0xFF61AFEF), Color(0xFFC678DD), Color(0xFF56B6C2), Color(0xFFDCDFE4),
)
private val terminalBrightDark = listOf(
    Color(0xFF5C6370), Color(0xFFFF7B86), Color(0xFFB5E890), Color(0xFFFFD68A),
    Color(0xFF8CC8FF), Color(0xFFE2A6FF), Color(0xFF7FD6E0), Color(0xFFFFFFFF),
)
private val terminalNormalLight = listOf(
    Color(0xFF383A42), Color(0xFFE45649), Color(0xFF50A14F), Color(0xFFC18401),
    Color(0xFF4078F2), Color(0xFFA626A4), Color(0xFF0184BC), Color(0xFFA0A1A7),
)
private val terminalBrightLight = listOf(
    Color(0xFF4F525E), Color(0xFFE06C75), Color(0xFF98C379), Color(0xFFE5C07B),
    Color(0xFF61AFEF), Color(0xFFC678DD), Color(0xFF56B6C2), Color(0xFFFFFFFF),
)

private val terminalBundle = ThemeBundle(
    id = "terminal",
    name = "Terminal",
    description = "Flat ANSI palette shared by editor, terminal and chrome.",
    author = "QCode",
    dark = darkColorScheme(
        primary = Color(0xFF61AFEF),
        onPrimary = Color(0xFF1A1A1A),
        primaryContainer = Color(0xFF2C313A),
        onPrimaryContainer = Color(0xFFDCDFE4),
        secondary = Color(0xFFC678DD),
        onSecondary = Color(0xFF1A1A1A),
        tertiary = Color(0xFF56B6C2),
        onTertiary = Color(0xFF1A1A1A),
        background = Color(0xFF1A1A1A),
        onBackground = Color(0xFFDCDFE4),
        surface = Color(0xFF202020),
        onSurface = Color(0xFFDCDFE4),
        surfaceVariant = Color(0xFF2C313A),
        onSurfaceVariant = Color(0xFFABB2BF),
    ),
    light = lightColorScheme(
        primary = Color(0xFF4078F2),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD6E4FF),
        onPrimaryContainer = Color(0xFF1A1A1A),
        secondary = Color(0xFFA626A4),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF0184BC),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFFAFAFA),
        onBackground = Color(0xFF383A42),
        surface = Color(0xFFF0F0F0),
        onSurface = Color(0xFF383A42),
        surfaceVariant = Color(0xFFE5E5E6),
        onSurfaceVariant = Color(0xFF5C6370),
    ),
    darkSemantic = JCodeSemanticColors(
        success = Color(0xFF98C379), onSuccess = Color(0xFF1A1A1A),
        warning = Color(0xFFE5C07B), onWarning = Color(0xFF1A1A1A),
        info = Color(0xFF56B6C2), onInfo = Color(0xFF1A1A1A),
    ),
    lightSemantic = JCodeSemanticColors(
        success = Color(0xFF50A14F), onSuccess = Color(0xFFFFFFFF),
        warning = Color(0xFFC18401), onWarning = Color(0xFFFFFFFF),
        info = Color(0xFF0184BC), onInfo = Color(0xFFFFFFFF),
    ),
    darkSyntax = SyntaxPalette(
        keyword = Color(0xFFC678DD),
        type = Color(0xFF56B6C2),
        string = Color(0xFF98C379),
        comment = Color(0xFF5C6370),
        number = Color(0xFFE5C07B),
        function = Color(0xFF61AFEF),
        variable = Color(0xFFDCDFE4),
        constant = Color(0xFFD19A66),
        property = Color(0xFF56B6C2),
        operator = Color(0xFFABB2BF),
        annotation = Color(0xFFE5C07B),
    ),
    lightSyntax = SyntaxPalette(
        keyword = Color(0xFFA626A4),
        type = Color(0xFF0184BC),
        string = Color(0xFF50A14F),
        comment = Color(0xFFA0A1A7),
        number = Color(0xFFC18401),
        function = Color(0xFF4078F2),
        variable = Color(0xFF383A42),
        constant = Color(0xFF986801),
        property = Color(0xFF0184BC),
        operator = Color(0xFF5C6370),
        annotation = Color(0xFFC18401),
    ),
    darkAnsi = AnsiPalette(
        normal = terminalNormalDark,
        bright = terminalBrightDark,
        foreground = Color(0xFFDCDFE4),
        background = Color(0xFF1A1A1A),
        cursor = Color(0xFF61AFEF),
        selection = Color(0x6461AFEF),
        selectionHandle = Color(0xFF61AFEF),
    ),
    lightAnsi = AnsiPalette(
        normal = terminalNormalLight,
        bright = terminalBrightLight,
        foreground = Color(0xFF383A42),
        background = Color(0xFFFAFAFA),
        cursor = Color(0xFF4078F2),
        selection = Color(0x644078F2),
        selectionHandle = Color(0xFF4078F2),
    ),
)

// Catppuccin — Mocha (dark) / Latte (light).
private val catppuccinBundle = ThemeBundle(
    id = "catppuccin",
    name = "Catppuccin",
    description = "Soft pastel theme (Mocha / Latte).",
    dark = darkColorScheme(
        primary = Color(0xFF89B4FA),
        onPrimary = Color(0xFF1E1E2E),
        primaryContainer = Color(0xFF313244),
        onPrimaryContainer = Color(0xFFCDD6F4),
        secondary = Color(0xFFCBA6F7),
        onSecondary = Color(0xFF1E1E2E),
        tertiary = Color(0xFFA6E3A1),
        onTertiary = Color(0xFF1E1E2E),
        background = Color(0xFF1E1E2E),
        onBackground = Color(0xFFCDD6F4),
        surface = Color(0xFF181825),
        onSurface = Color(0xFFCDD6F4),
        surfaceVariant = Color(0xFF313244),
        onSurfaceVariant = Color(0xFFBAC2DE),
    ),
    light = lightColorScheme(
        primary = Color(0xFF1E66F5),
        onPrimary = Color(0xFFEFF1F5),
        primaryContainer = Color(0xFFCCD0DA),
        onPrimaryContainer = Color(0xFF4C4F69),
        secondary = Color(0xFF8839EF),
        onSecondary = Color(0xFFEFF1F5),
        tertiary = Color(0xFF40A02B),
        onTertiary = Color(0xFFEFF1F5),
        background = Color(0xFFEFF1F5),
        onBackground = Color(0xFF4C4F69),
        surface = Color(0xFFE6E9EF),
        onSurface = Color(0xFF4C4F69),
        surfaceVariant = Color(0xFFCCD0DA),
        onSurfaceVariant = Color(0xFF5C5F77),
    ),
    darkSemantic = JCodeSemanticColors(
        success = Color(0xFFA6E3A1), onSuccess = Color(0xFF1E1E2E),
        warning = Color(0xFFF9E2AF), onWarning = Color(0xFF1E1E2E),
        info = Color(0xFF89DCEB), onInfo = Color(0xFF1E1E2E),
    ),
    lightSemantic = JCodeSemanticColors(
        success = Color(0xFF40A02B), onSuccess = Color(0xFFEFF1F5),
        warning = Color(0xFFDF8E1D), onWarning = Color(0xFFEFF1F5),
        info = Color(0xFF209FB5), onInfo = Color(0xFFEFF1F5),
    ),
)

// Pierre Dark — the default. A near-black canvas with a single bright-blue accent; monochrome
// surfaces keep the blue (and the magenta/green highlights) doing all the talking. Palette from The
// Pierre Computer Company's theme (github.com/pierrecomputer/theme).
private val pierreDarkBundle = ThemeBundle(
    id = "pierre-dark",
    name = "Pierre Dark",
    description = "Near-black canvas with a bright blue accent.",
    author = "The Pierre Computer Company",
    dark = darkColorScheme(
        primary = Color(0xFF009FFF),
        onPrimary = Color(0xFF0A0A0A),
        primaryContainer = Color(0xFF19283C),
        onPrimaryContainer = Color(0xFFDCEBFF),
        secondary = Color(0xFFE130AC),
        onSecondary = Color(0xFF0A0A0A),
        tertiary = Color(0xFF0DBE4E),
        onTertiary = Color(0xFF0A0A0A),
        background = Color(0xFF0A0A0A),
        onBackground = Color(0xFFFAFAFA),
        surface = Color(0xFF171717),
        onSurface = Color(0xFFFAFAFA),
        surfaceVariant = Color(0xFF262626),
        onSurfaceVariant = Color(0xFFA3A3A3),
    ),
    light = lightColorScheme(
        primary = Color(0xFF0072D6),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDFEBFF),
        onPrimaryContainer = Color(0xFF0A0A0A),
        secondary = Color(0xFFBD2E90),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF18A46C),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF0A0A0A),
        surface = Color(0xFFF5F5F5),
        onSurface = Color(0xFF0A0A0A),
        surfaceVariant = Color(0xFFE5E5E5),
        onSurfaceVariant = Color(0xFF525252),
    ),
    darkSemantic = JCodeSemanticColors(
        success = Color(0xFF0DBE4E), onSuccess = Color(0xFF0A0A0A),
        warning = Color(0xFFFFCA00), onWarning = Color(0xFF0A0A0A),
        info = Color(0xFF08C0EF), onInfo = Color(0xFF0A0A0A),
    ),
    lightSemantic = JCodeSemanticColors(
        success = Color(0xFF18A46C), onSuccess = Color(0xFFFFFFFF),
        warning = Color(0xFFD5A910), onWarning = Color(0xFF0A0A0A),
        info = Color(0xFF1CA1C7), onInfo = Color(0xFFFFFFFF),
    ),
)

// Night Owl — Sarah Drasner's deep-navy theme (#011627 canvas) with soft, glowing blue/purple/cyan
// accents tuned for low-light coding. Surfaces lift a touch off the canvas so drawers read as panels.
private val nightOwlBundle = ThemeBundle(
    id = "night-owl",
    name = "Night Owl",
    description = "Deep navy with soft, glowing accents.",
    author = "Sarah Drasner",
    dark = darkColorScheme(
        primary = Color(0xFF82AAFF),
        onPrimary = Color(0xFF011627),
        primaryContainer = Color(0xFF1D3B53),
        onPrimaryContainer = Color(0xFFD6DEEB),
        secondary = Color(0xFFC792EA),
        onSecondary = Color(0xFF011627),
        tertiary = Color(0xFF7FDBCA),
        onTertiary = Color(0xFF011627),
        // background is Night Owl's signature deep navy; surface lifts a touch so drawers/sheets and
        // the editor gutter separate from the canvas.
        background = Color(0xFF011627),
        onBackground = Color(0xFFD6DEEB),
        surface = Color(0xFF0B2942),
        onSurface = Color(0xFFD6DEEB),
        surfaceVariant = Color(0xFF1D3B53),
        onSurfaceVariant = Color(0xFF8BADC9),
    ),
    light = lightColorScheme(
        primary = Color(0xFF4876D6),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD3E1F8),
        onPrimaryContainer = Color(0xFF15294A),
        secondary = Color(0xFF994CC3),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF0C969B),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFFBFBFB),
        onBackground = Color(0xFF403F53),
        surface = Color(0xFFF0F0F0),
        onSurface = Color(0xFF403F53),
        surfaceVariant = Color(0xFFE3E4EC),
        onSurfaceVariant = Color(0xFF5F6673),
    ),
    darkSemantic = JCodeSemanticColors(
        success = Color(0xFF22DA6E), onSuccess = Color(0xFF011627),
        warning = Color(0xFFECC48D), onWarning = Color(0xFF011627),
        info = Color(0xFF21C7A8), onInfo = Color(0xFF011627),
    ),
    lightSemantic = JCodeSemanticColors(
        success = Color(0xFF08916A), onSuccess = Color(0xFFFFFFFF),
        warning = Color(0xFFDAAA01), onWarning = Color(0xFF403F53),
        info = Color(0xFF0C969B), onInfo = Color(0xFFFFFFFF),
    ),
)
