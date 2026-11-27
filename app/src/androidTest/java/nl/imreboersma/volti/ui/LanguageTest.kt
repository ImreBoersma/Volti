package nl.imreboersma.volti.ui

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.imreboersma.volti.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Language setting picks Volti's per-app language, and the screens show it at once. */
@RunWith(AndroidJUnit4::class)
class LanguageTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val locales = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(LocaleManager::class.java)

    private fun showing(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @After
    fun backToEnglish() {
        locales.applicationLocales = LocaleList.forLanguageTags("en") // As VoltiTestRunner has it.
    }

    @Test
    fun nederlandsInSettingsShowsVoltiInDutch() {
        compose.onNodeWithText("Settings").performClick()
        compose.waitUntil(5_000) { showing("Language") }
        assertEquals("English", appLanguage(compose.activity).let { compose.activity.getString(it.label) })
        compose.onNodeWithText("Language").performClick()
        compose.waitUntil(5_000) { showing("Nederlands") }
        compose.onNodeWithText("Nederlands").performClick()

        compose.waitUntil(10_000) { showing("Marges bijsnijden") }
        assertEquals("nl", locales.applicationLocales.toLanguageTags())
        // The row says which one is picked, in it.
        assertTrue("the Language row reads Dutch, with Nederlands picked", showing("Taal") && showing("Nederlands"))
    }
}
