package nl.imreboersma.volti.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Material's "arrow back", the one glyph needed: no icon library for it. */
private val ArrowBack: ImageVector = ImageVector.Builder("ArrowBack", 24.dp, 24.dp, 24f, 24f, autoMirror = true)
    .path(fill = SolidColor(Color.Black)) {
        moveTo(20f, 11f)
        horizontalLineTo(7.83f)
        lineToRelative(5.59f, -5.59f)
        lineTo(12f, 4f)
        lineToRelative(-8f, 8f)
        lineToRelative(8f, 8f)
        lineToRelative(1.41f, -1.41f)
        lineTo(7.83f, 13f)
        horizontalLineTo(20f)
        close()
    }
    .build()

/**
 * The app's back button, before a screen's title: always there, unlike the reader's, which hides itself so it
 * doesn't cover the music.
 */
@Composable
fun AppBackButton(onBack: () -> Unit, description: String, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier) {
        Icon(ArrowBack, contentDescription = description)
    }
}
