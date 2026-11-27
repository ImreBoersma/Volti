package nl.imreboersma.volti.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
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
import nl.imreboersma.volti.setlist.Setlist
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import nl.imreboersma.volti.ui.theme.VoltiTheme

/** My own name for a file, given in its ensemble: shown there and in its setlists, and given back. */
@RunWith(AndroidJUnit4::class)
class DisplayNameTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val db = VoltiDatabase.get(InstrumentationRegistry.getInstrumentation().targetContext)
    private lateinit var nav: NavHostController

    @After
    fun clean() = runBlocking {
        db.ensembles().all().first().filter { it.name == "Name test" }.forEach { db.ensembles().remove(it) }
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) = compose.waitUntil(10_000) { shows(text) }

    /** The dialog's field, by what it holds: the ensemble's search field is editable too. */
    private fun nameField(holding: String) = compose.onNode(hasSetTextAction() and hasText(holding))

    @Test
    fun aNameIGiveShowsInTheEnsembleAndTheSetlistAndCanGoBack() {
        val fileId = "f" + UUID.randomUUID()
        val (ensemble, setlist) = runBlocking {
            val ensemble = db.ensembles().add(Ensemble(name = "Name test", instrument = "Horn", folderId = "f" + UUID.randomUUID().toString().take(12)))
            db.files().save(listOf(EnsembleFile(ensemble, fileId, null, "Farandole-Hoorn_in_F.pdf", emptyList(), "0".repeat(32), 1)))
            ensemble to db.setlists().add(Setlist(ensembleId = ensemble, name = "Concert", createdAt = 1)).also {
                db.setlists().setPieces(it, listOf(fileId))
            }
        }
        compose.setContent {
            nav = rememberNavController()
            VoltiTheme { VoltiApp(nav) } // As MainActivity shows it.
        }
        compose.runOnUiThread { nav.navigate(EnsembleFiles(ensemble)) }
        waitFor("Farandole-Hoorn_in_F.pdf")

        compose.onNodeWithContentDescription("More for Farandole-Hoorn_in_F.pdf").performClick()
        compose.onNodeWithText("Rename").performClick()
        waitFor("Farandole - Hoorn in F") // Starts from the cleaned file name.
        nameField(holding = "Farandole - Hoorn in F").performTextReplacement("Bizet - Farandole - F Horn 1")
        compose.onNodeWithText("Save").performClick()
        waitFor("Bizet - Farandole - F Horn 1")
        assertEquals("Bizet - Farandole - F Horn 1", runBlocking { db.files().all(ensemble).single().displayName })

        compose.runOnUiThread { nav.navigate(SetlistPieces(setlist)) }
        waitFor("Bizet - Farandole - F Horn 1") // The setlist shows it by my name too.

        compose.onNodeWithContentDescription("More for Bizet - Farandole - F Horn 1").performClick()
        compose.onAllNodesWithText("Rename").onLast().performClick() // The menu's, over the setlist's own Rename.
        compose.onNodeWithText("Use the file name").performClick()
        waitFor("Farandole-Hoorn_in_F.pdf")
        assertEquals(null, runBlocking { db.files().all(ensemble).single().displayName })
    }
}
