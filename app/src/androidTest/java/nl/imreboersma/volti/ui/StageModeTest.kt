package nl.imreboersma.volti.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageModeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun keepsScreenOn() =
        compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    private fun barsVisible(): Boolean {
        val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
        return insets.isVisible(WindowInsetsCompat.Type.statusBars()) ||
            insets.isVisible(WindowInsetsCompat.Type.navigationBars())
    }

    @Test
    fun onWhileShownAndRestoredAfterwards() {
        var reading by mutableStateOf(false)
        compose.setContent { if (reading) StageMode() }
        compose.waitForIdle()
        assertEquals(false to true, keepsScreenOn() to barsVisible())

        reading = true
        compose.waitUntil(2_000) { !barsVisible() }
        assertEquals(true, keepsScreenOn())

        reading = false
        compose.waitUntil(2_000) { barsVisible() }
        assertEquals(false, keepsScreenOn())
    }
}
