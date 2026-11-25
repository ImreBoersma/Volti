package nl.imreboersma.volti.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text on the stand must be readable: every text colour against its background at least 4.5:1 (WCAG AA). */
class ThemeContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun ColorScheme.textPairs() = mapOf(
        "onBackground on background" to (onBackground to background),
        "onSurface on surface" to (onSurface to surface),
        "onSurfaceVariant on surface" to (onSurfaceVariant to surface),
        "onSurface on surfaceContainer" to (onSurface to surfaceContainer),
        "primary on background (text buttons, links)" to (primary to background),
        "onPrimary on primary (buttons)" to (onPrimary to primary),
        "onPrimaryContainer on primaryContainer" to (onPrimaryContainer to primaryContainer),
        "onSecondaryContainer on secondaryContainer (selected chips)" to (onSecondaryContainer to secondaryContainer),
        "onSecondary on secondary" to (onSecondary to secondary),
    )

    private fun check(name: String, scheme: ColorScheme) {
        val low = scheme.textPairs().mapValues { (_, pair) -> contrast(pair.first, pair.second) }.filterValues { it < 4.5 }
        assertTrue("$name: below 4.5:1: ${low.mapValues { "%.2f".format(it.value) }}", low.isEmpty())
    }

    @Test
    fun lightThemeTextIsReadable() = check("light", LightColors)

    @Test
    fun darkThemeTextIsReadable() = check("dark", DarkColors)

    /** Borders and lines that show what is a control, or where a page ends: at least 3:1 (WCAG non-text). */
    private fun checkLines(name: String, scheme: ColorScheme, reader: ReaderColors) {
        val pairs = mapOf(
            "outline on background (button borders)" to (scheme.outline to scheme.background),
            "outlineVariant on background (unselected chips, dividers)" to (scheme.outlineVariant to scheme.background),
            "the half-turn line on paper" to (reader.line to Paper),
            "reader messages on the surround" to (reader.message to reader.surround),
        )
        val low = pairs.mapValues { (_, pair) -> contrast(pair.first, pair.second) }.filterValues { it < 3.0 }
        assertTrue("$name: below 3:1: ${low.mapValues { "%.2f".format(it.value) }}", low.isEmpty())
    }

    @Test
    fun lightThemeLinesAreVisible() = checkLines("light", LightColors, LightReader)

    @Test
    fun darkThemeLinesAreVisible() = checkLines("dark", DarkColors, DarkReader)

    @Test
    fun theIconsOwnBlueIsTooDarkForTextOnInk() {
        // Why the dark theme uses lighter blue and red: the icon's own are below 4.5:1 on its near-black.
        assertTrue(contrast(BauhausBlue, Ink) < 4.5)
        assertTrue(contrast(BauhausRed, Ink) < 4.5)
    }
}
