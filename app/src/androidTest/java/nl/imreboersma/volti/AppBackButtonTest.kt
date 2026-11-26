package nl.imreboersma.volti

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppBackButtonTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController

    @Before
    fun setUp() {
        compose.setContent {
            nav = rememberNavController()
            VoltiApp(nav)
        }
        compose.waitForIdle()
    }

    private fun on(check: (androidx.navigation.NavDestination) -> Boolean) = nav.currentDestination?.let(check) == true

    @Test
    fun theHomeScreenHasNoBackButton() {
        assertTrue(compose.onAllNodesWithContentDescription("Back to the library").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun backFromTheFolderOnTheTabletGoesHome() {
        compose.runOnUiThread { nav.navigate(LocalLibrary) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back to the library").performClick()
        compose.waitForIdle()
        assertTrue(on { it.hasRoute<Home>() })
    }

    @Test
    fun backFromTheSettingsGoesHome() {
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        assertTrue(on { it.hasRoute<Settings>() })
        compose.onNodeWithContentDescription("Back to the library").performClick()
        compose.waitForIdle()
        assertTrue(on { it.hasRoute<Home>() })
    }

    @Test
    fun backFromAddingAnEnsembleGoesHome() {
        compose.runOnUiThread { nav.navigate(AddEnsemble) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back to the library").performClick()
        compose.waitForIdle()
        assertTrue(on { it.hasRoute<Home>() })
    }

    @Test
    fun aDoubleTapGoesBackOnce() {
        compose.runOnUiThread { nav.navigate(AddEnsemble) }
        compose.waitForIdle()
        val button = compose.onNodeWithContentDescription("Back to the library")
        compose.mainClock.autoAdvance = false // Hold the crossfade, so the button is still there for the second tap.
        button.performClick()
        compose.mainClock.advanceTimeBy(100)
        button.performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue("the library is still there", on { it.hasRoute<Home>() })
    }
}
