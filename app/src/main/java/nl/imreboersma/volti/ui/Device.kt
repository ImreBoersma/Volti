package nl.imreboersma.volti.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * What to call the device in the app's text: "tablet" or "phone" by the screen's narrow side (600 dp is where Android
 * draws that line), "device" for anything else (a TV, a car, a watch).
 */
@Composable
fun deviceName(): String {
    val configuration = LocalConfiguration.current
    if (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK != Configuration.UI_MODE_TYPE_NORMAL) return "device"
    return if (configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"
}
