package nl.imreboersma.volti.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** Cream paper with ink, the Bauhaus blue for actions, the red for what is switched on. */
internal val LightColors = lightColorScheme(
    primary = BauhausBlue,
    onPrimary = CreamLight,
    primaryContainer = BauhausBlue,
    onPrimaryContainer = CreamLight,
    secondary = BauhausRed,
    onSecondary = CreamLight,
    secondaryContainer = BauhausRed,
    onSecondaryContainer = CreamLight,
    tertiary = BauhausRedShadow,
    onTertiary = CreamLight,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = CreamDim,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = CreamLight,
    surfaceContainerLow = CreamLight,
    surfaceContainer = CreamLight,
    surfaceContainerHigh = CreamDim,
    surfaceContainerHighest = CreamDim,
    outline = InkMuted,
    outlineVariant = CreamLine,
)

/** The icon itself: near-black, cream, and lighter blue and red so they read on the dark. */
internal val DarkColors = darkColorScheme(
    primary = BlueLight,
    onPrimary = Ink,
    primaryContainer = BauhausBlue,
    onPrimaryContainer = Cream,
    secondary = RedLight,
    onSecondary = Ink,
    secondaryContainer = BauhausRedShadow,
    onSecondaryContainer = Cream,
    tertiary = Cream,
    onTertiary = Ink,
    background = Ink,
    onBackground = Cream,
    surface = Ink,
    onSurface = Cream,
    surfaceVariant = InkHigh,
    onSurfaceVariant = CreamMuted,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = InkRaised,
    surfaceContainer = InkRaised,
    surfaceContainerHigh = InkHigh,
    surfaceContainerHighest = InkHigh,
    outline = CreamMuted,
    outlineVariant = InkLine,
)

/** The widest a form or settings column gets: on a wide window, fields and rows don't stretch across it. */
val MaxContentWidth = 600.dp

/**
 * The reader's own colours. The page is white paper in both themes, so what touches it is fixed rather than
 * taken from the scheme: around the page, white in the light theme (cream would frame each page as a white box)
 * and ink in the dark one; the half-turn line is drawn across paper, so dark in both.
 */
data class ReaderColors(val surround: Color, val line: Color, val message: Color)

internal val LightReader = ReaderColors(surround = Paper, line = InkMuted, message = Ink)
internal val DarkReader = ReaderColors(surround = Ink, line = InkMuted, message = Cream)

val LocalReaderColors = staticCompositionLocalOf { LightReader }

/**
 * The reader's colours around a score whose paper is [paper]: the paper's own colour, so page and surround are one,
 * with ink or cream on it, whichever contrasts more with that paper, whatever the theme.
 */
fun readerColorsOn(paper: Color): ReaderColors {
    fun contrast(a: Color) = (maxOf(a.luminance(), paper.luminance()) + 0.05f) / (minOf(a.luminance(), paper.luminance()) + 0.05f)
    // The line too: on mid-grey paper a muted line all but disappears.
    val ink = if (contrast(Ink) >= contrast(Cream)) Ink else Cream
    return ReaderColors(surround = paper, line = ink, message = ink)
}

/**
 * The icon's colours, light or dark with the system. No dynamic colour: the wallpaper's colours would replace
 * the palette that makes the app recognisable.
 */
@Composable
fun VoltiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalReaderColors provides if (darkTheme) DarkReader else LightReader) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Typography,
            content = content,
        )
    }
}
