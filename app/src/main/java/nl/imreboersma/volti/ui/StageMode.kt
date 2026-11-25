package nl.imreboersma.volti.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * While this is in the composition: the screen stays on and the system bars are hidden.
 * A swipe from the edge shows the bars for a moment. Leaving restores both.
 */
@Composable
fun StageMode() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as Activity).window
        val bars = WindowCompat.getInsetsController(window, view)
        val behavior = bars.systemBarsBehavior
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            bars.show(WindowInsetsCompat.Type.systemBars())
            bars.systemBarsBehavior = behavior
        }
    }
}
