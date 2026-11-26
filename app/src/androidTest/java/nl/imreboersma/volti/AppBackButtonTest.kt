package nl.imreboersma.volti

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
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
    fun theLibraryTopLevelHasNoBackButton() {
        assertTrue(compose.onAllNodesWithContentDescription("Up a folder").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription("Clear the search").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun backFromTheEnsemblesGoesToTheLibrary() {
        compose.runOnUiThread { nav.navigate(Ensembles) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back to the library").performClick()
        compose.waitForIdle()
        assertTrue(on { it.hasRoute<Library>() })
    }

    @Test
    fun aDoubleTapGoesBackOnce() {
        compose.runOnUiThread { nav.navigate(Ensembles) }
        compose.waitForIdle()
        val button = compose.onNodeWithContentDescription("Back to the library")
        compose.mainClock.autoAdvance = false // Hold the crossfade, so the button is still there for the second tap.
        button.performClick()
        compose.mainClock.advanceTimeBy(100)
        button.performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue("the library is still there", on { it.hasRoute<Library>() })
    }
}
