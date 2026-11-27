package nl.imreboersma.volti.ui

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * What to call the device in the app's text. Each has its own phrase in strings.xml, not a word put into a sentence:
 * Dutch says "deze tablet" and "deze telefoon", but "dit apparaat".
 */
enum class Device {
    Tablet, Phone, Other;

    /** The string for this device, out of one per device. */
    @StringRes
    fun pick(@StringRes tablet: Int, @StringRes phone: Int, @StringRes other: Int) = when (this) {
        Tablet -> tablet
        Phone -> phone
        Other -> other
    }
}

/**
 * "Tablet" or "phone" by the screen's narrow side (600 dp is where Android draws that line), "device" for anything
 * else (a TV, a car, a watch).
 */
@Composable
fun device(): Device {
    val configuration = LocalConfiguration.current
    if (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK != Configuration.UI_MODE_TYPE_NORMAL) return Device.Other
    return if (configuration.smallestScreenWidthDp >= 600) Device.Tablet else Device.Phone
}
