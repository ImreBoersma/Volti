package nl.imreboersma.volti

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Esc on an external keyboard does what Back does. In the app's own activity: that is where Esc is handled. */
@RunWith(AndroidJUnit4::class)
class EscapeKeyTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun showing(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun escFromTheSettingsGoesHome() {
        compose.onNodeWithText("Settings").performClick()
        compose.waitUntil(5_000) { showing("Crop margins") }
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() } // Keys go to the window with the focus.

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE)

        compose.waitUntil(5_000) { !showing("Crop margins") && showing("Library") }
    }
}
