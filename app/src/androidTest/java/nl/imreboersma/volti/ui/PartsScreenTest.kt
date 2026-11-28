package nl.imreboersma.volti.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.imreboersma.volti.VoltiApp
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.EnsembleFile
import nl.imreboersma.volti.db.VoltiDatabase
import nl.imreboersma.volti.ui.theme.VoltiTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** My parts from every ensemble, and the search through every part, from the library. */
@RunWith(AndroidJUnit4::class)
class PartsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val db = VoltiDatabase.get(InstrumentationRegistry.getInstrumentation().targetContext)

    /** In every name, so other tests' ensembles on the device don't match. */
    private val tag = "zq" + UUID.randomUUID().toString().take(6)
    private val hoorn = "Farandole $tag - Hoorn.pdf"
    private val bolero = "Bolero $tag - Hoorn.pdf"
    private val trompet = "Farandole $tag - Trompet.pdf"

    @Before
    fun seed() = runBlocking {
        // Only imports: no Drive folder, so the screens don't sync.
        val harmonie = db.ensembles().add(Ensemble(name = "Parts test Harmonie", instrument = "Horn", folderId = null))
        val bigBand = db.ensembles().add(Ensemble(name = "Parts test Big Band", instrument = "Trumpet", folderId = null))
        fun file(ensemble: Long, name: String, mine: Boolean, id: String = "f" + UUID.randomUUID()) =
            EnsembleFile(ensemble, id, null, name, emptyList(), "0".repeat(32), 1, mine = mine, imported = true)
        val shared = "f" + UUID.randomUUID()
        db.files().save(listOf(file(harmonie, hoorn, mine = true, id = shared), file(harmonie, bolero, mine = false)))
        // The same Drive file in a second ensemble: two rows, one key each.
        db.files().save(listOf(file(bigBand, trompet, mine = false), file(bigBand, hoorn, mine = false, id = shared)))
        compose.setContent { VoltiTheme { VoltiApp() } }
    }

    @After
    fun clean() = runBlocking {
        db.ensembles().all().first().filter { it.name.startsWith("Parts test") }.forEach { db.ensembles().remove(it) }
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) = compose.waitUntil(10_000) { shows(text) }

    private fun top(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().minOf { it.boundsInRoot.top }

    @Test
    fun yourPartsListsMineFromEveryEnsembleWithTheirEnsemble() {
        waitFor("From every ensemble")
        compose.onNodeWithText("Your parts").performClick()
        waitFor(hoorn)
        assertTrue(shows("Parts test Harmonie"))
        assertTrue(!shows(bolero) && !shows(trompet)) // Not mine.
        assertEquals(1, compose.onAllNodesWithText(hoorn).fetchSemanticsNodes().size) // Mine in one ensemble only.
    }

    @Test
    fun aPartIUntickStaysWhereItIsUntilILeave() {
        waitFor("From every ensemble")
        compose.onNodeWithText("Your parts").performClick()
        waitFor(hoorn)
        // Its row's "Your part": the one beside the only part of mine with this tag.
        val row = compose.onAllNodes(hasText("Your part") and hasClickAction())
        val index = (0 until row.fetchSemanticsNodes().size).first { i ->
            val chip = row[i].fetchSemanticsNode().boundsInRoot
            val name = compose.onAllNodesWithText(hoorn).fetchSemanticsNodes().single().boundsInRoot
            chip.top < name.bottom && chip.bottom > name.top
        }
        row[index].performClick()
        compose.waitUntil(10_000) { runBlocking { db.files().everywhere().first().none { it.name == hoorn && it.mine } } }
        compose.waitForIdle()
        assertTrue("Still listed", shows(hoorn))
        row[index].assertIsNotSelected()
    }

    @Test
    fun searchGoesThroughEveryEnsembleMineFirst() {
        waitFor("From every ensemble")
        compose.onNodeWithContentDescription("Search").performClick()
        val field = compose.onNode(hasSetTextAction())
        compose.waitUntil(10_000) { runCatching { field.assertIsFocused() }.isSuccess } // The keyboard is up at once.

        field.performTextReplacement("$tag farandole")
        waitFor(trompet)
        assertTrue(!shows(bolero))
        assertEquals(2, compose.onAllNodesWithText(hoorn).fetchSemanticsNodes().size) // In both ensembles.
        assertTrue("Mine first", top(hoorn) < top(trompet))

        // The ensemble's name counts as a folder on the way.
        field.performTextReplacement("$tag big band")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(hoorn).fetchSemanticsNodes().size == 1 } // Only Big Band's.
        assertTrue(shows(trompet) && !shows("Parts test Harmonie"))

        field.performTextReplacement("$tag nothing")
        waitFor("Nothing found for \"$tag nothing\".")

        // Back clears the search first, and shows my parts.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor(hoorn)
        assertTrue(shows("Your parts") && !shows(trompet))
    }
}
