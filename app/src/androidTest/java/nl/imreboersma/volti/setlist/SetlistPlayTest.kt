package nl.imreboersma.volti.setlist

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.imreboersma.volti.Reader
import nl.imreboersma.volti.SetlistPieces
import nl.imreboersma.volti.VoltiApp
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.EnsembleFile
import nl.imreboersma.volti.db.VoltiDatabase
import nl.imreboersma.volti.drive.Syncer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import nl.imreboersma.volti.ui.theme.VoltiTheme

/** A setlist played like one book: past a piece's last page into the next, back into the previous one's last page. */
@RunWith(AndroidJUnit4::class)
class SetlistPlayTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = VoltiDatabase.get(context)
    private lateinit var nav: NavHostController

    /** A PDF of [pages] pages, stored on the device as an ensemble file is: by its md5. */
    private fun piece(ensembleId: Long, id: String, pages: Int): EnsembleFile {
        val doc = PdfDocument()
        repeat(pages) { i ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            page.canvas.drawRect(100f, 100f, 150f + 40f * i, 300f, Paint().apply { color = Color.BLACK })
            doc.finishPage(page)
        }
        val bytes = ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
        doc.close()
        val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        Syncer.get(context).fileFor(md5).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        return EnsembleFile(ensembleId, id, null, "$id.pdf", emptyList(), md5, bytes.size.toLong(), localMd5 = md5, mine = true)
    }

    @After
    fun clean() = runBlocking {
        db.ensembles().all().first().filter { it.name == "Setlist play test" }.forEach { db.ensembles().remove(it) }
    }

    private fun showing(description: String) = compose.onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description),
    ).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(description: String) = compose.waitUntil(10_000) { showing(description) }

    private fun reader() = nav.currentBackStackEntry?.takeIf { it.destination.route?.contains("Reader") == true }?.toRoute<Reader>()

    private fun turn(forward: Boolean) = compose.onRoot().performTouchInput { click(Offset(width * if (forward) 0.75f else 0.25f, height * 0.5f)) }

    @Test
    fun aSetlistPlaysOnFromPieceToPiece() {
        val setlist = runBlocking {
            val ensemble = db.ensembles().add(Ensemble(name = "Setlist play test", instrument = "Horn", folderId = "f" + UUID.randomUUID().toString().take(12)))
            db.files().save(listOf(piece(ensemble, "first", pages = 2), piece(ensemble, "second", pages = 3)))
            db.setlists().add(Setlist(ensembleId = ensemble, name = "Concert", createdAt = 1)).also {
                db.setlists().setPieces(it, listOf("first", "second"))
            }.also { db.files().rename("second", "Bizet - Farandole") } // The reader's line says my name.
        }
        // Whole-page turns, whatever is set on the device.
        runBlocking { nl.imreboersma.volti.data.ReaderSettingsStore(context).setHalfPageTurns(false) }
        compose.setContent {
            nav = rememberNavController()
            VoltiTheme { VoltiApp(nav) } // As MainActivity shows it.
        }
        compose.runOnUiThread { nav.navigate(SetlistPieces(setlist)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Play").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Play").performClick()

        waitFor("Page 1 of 2")
        assertEquals(0, reader()?.position)
        turn(forward = true)
        waitFor("Page 2 of 2")
        turn(forward = true) // Past the first piece's last page: the second piece, from its start.
        waitFor("Page 1 of 3")
        assertEquals(1, reader()?.position)
        compose.waitUntil(10_000) { saying("2 / 2 · Bizet - Farandole") }
        turn(forward = false) // Back before its first page: the first piece, at its last page.
        waitFor("Page 2 of 2")
        assertEquals(0, reader()?.position)

        compose.runOnUiThread { nav.popBackStack() } // Back goes to the setlist, not through the pieces played.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Play").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun saying(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aPieceThatIsGoneIsSkippedSayingWhyAndTheEndIsSaid() {
        val setlist = runBlocking {
            val ensemble = db.ensembles().add(Ensemble(name = "Setlist play test", instrument = "Horn", folderId = "f" + UUID.randomUUID().toString().take(12)))
            db.files().save(listOf(piece(ensemble, "only", pages = 2)))
            db.setlists().add(Setlist(ensembleId = ensemble, name = "Concert", createdAt = 1)).also {
                db.setlists().setPieces(it, listOf("gone", "only")) // "gone" left the ensemble.
            }
        }
        runBlocking { nl.imreboersma.volti.data.ReaderSettingsStore(context).setHalfPageTurns(false) }
        compose.setContent {
            nav = rememberNavController()
            VoltiTheme { VoltiApp(nav) } // As MainActivity shows it.
        }
        compose.runOnUiThread { nav.navigate(SetlistPieces(setlist)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Play").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Play").performClick()

        waitFor("Page 1 of 2")
        assertEquals(1, reader()?.position)
        compose.waitUntil(10_000) { saying("A piece skipped. It isn't in Google Drive any more.") }
        turn(forward = true)
        waitFor("Page 2 of 2")
        compose.waitUntil(10_000) { !saying("skipped") } // Said until I turned a page.
        turn(forward = true)
        compose.waitUntil(10_000) { saying("End of the setlist") }
        turn(forward = false)
        waitFor("Page 1 of 2")
        compose.waitUntil(10_000) { !saying("End of the setlist") }
    }
}
