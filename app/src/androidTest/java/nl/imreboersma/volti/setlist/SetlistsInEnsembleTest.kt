package nl.imreboersma.volti.setlist

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.imreboersma.volti.EnsembleFiles
import nl.imreboersma.volti.SetlistPieces
import nl.imreboersma.volti.VoltiApp
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.EnsembleFile
import nl.imreboersma.volti.db.VoltiDatabase
import nl.imreboersma.volti.ui.theme.VoltiTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** An ensemble's setlists in its own screen, the newest three first, gone while searching. */
@RunWith(AndroidJUnit4::class)
class SetlistsInEnsembleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val db = VoltiDatabase.get(InstrumentationRegistry.getInstrumentation().targetContext)
    private lateinit var nav: NavHostController

    @After
    fun clean() = runBlocking {
        db.ensembles().all().first().filter { it.name == "Setlists in ensemble" }.forEach { db.ensembles().remove(it) }
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theNewestThreeShowAboveTheScoresTheRestUnfoldAndSearchHidesThem() {
        val ensemble = runBlocking {
            val id = db.ensembles().add(Ensemble(name = "Setlists in ensemble", instrument = "Horn"))
            db.files().save(listOf(EnsembleFile(id, "f1", null, "Overture.pdf", emptyList(), "0".repeat(32), 1, imported = true)))
            (1..5).forEach { db.setlists().add(Setlist(ensembleId = id, name = "Concert $it", createdAt = it.toLong())) }
            id
        }
        compose.setContent {
            nav = rememberNavController()
            VoltiTheme { VoltiApp(nav) }
        }
        compose.runOnUiThread { nav.navigate(EnsembleFiles(ensemble)) }
        compose.waitUntil(10_000) { shows("Concert 5") }
        listOf("Concert 5", "Concert 4", "Concert 3", "Overture.pdf").forEach { assert(shows(it)) { "$it shows" } }
        assert(!shows("Concert 2")) { "only the newest three" }

        compose.onNodeWithText("All 5 setlists").performClick()
        compose.waitUntil(5_000) { shows("Concert 1") }

        compose.onNode(hasSetTextAction() and hasText("Search this ensemble")).performTextInput("overture")
        compose.waitUntil(5_000) { !shows("Concert 5") } // A search shows only what it found.
        assert(shows("Overture.pdf"))
    }

    @Test
    fun aNewSetlistStartsFromTheEnsemble() {
        val ensemble = runBlocking { db.ensembles().add(Ensemble(name = "Setlists in ensemble", instrument = "Horn")) }
        compose.setContent {
            nav = rememberNavController()
            VoltiTheme { VoltiApp(nav) }
        }
        compose.runOnUiThread { nav.navigate(EnsembleFiles(ensemble)) }
        compose.waitUntil(10_000) { shows("New setlist") }
        compose.onNodeWithText("New setlist").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Kerstconcert")
        compose.onNodeWithText("Make it").performClick()
        compose.waitUntil(10_000) { nav.currentBackStackEntry?.destination?.route?.contains("SetlistPieces") == true }
        val opened = runBlocking { db.setlists().forEnsemble(ensemble).first().single() }
        assertEquals("Kerstconcert", opened.name)
        assertEquals(opened.id, nav.currentBackStackEntry!!.toRoute<SetlistPieces>().setlistId)
    }

    @Test
    fun manySetlistsUnfoldedLeaveTheSearchAndTheScoresWithinReach() {
        val ensemble = runBlocking {
            val id = db.ensembles().add(Ensemble(name = "Setlists in ensemble", instrument = "Horn"))
            db.files().save(listOf(EnsembleFile(id, "f1", null, "Overture.pdf", emptyList(), "0".repeat(32), 1, imported = true)))
            (1..20).forEach { db.setlists().add(Setlist(ensembleId = id, name = "Concert $it", createdAt = it.toLong())) }
            id
        }
        compose.setContent {
            nav = rememberNavController()
            VoltiTheme { VoltiApp(nav) }
        }
        compose.runOnUiThread { nav.navigate(EnsembleFiles(ensemble)) }
        compose.waitUntil(10_000) { shows("All 20 setlists") }
        compose.onNodeWithText("All 20 setlists").performClick()
        compose.waitUntil(5_000) { shows("Concert 1") }
        // The search field stays on screen, and the score is in the list below the setlists (or beside them).
        compose.onNode(hasSetTextAction() and hasText("Search this ensemble")).assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Overture.pdf"))
        compose.onNodeWithText("Overture.pdf").assertIsDisplayed()
    }
}
