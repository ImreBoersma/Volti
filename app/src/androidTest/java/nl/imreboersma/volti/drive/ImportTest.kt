package nl.imreboersma.volti.drive

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.imreboersma.volti.EnsembleFiles
import nl.imreboersma.volti.VoltiApp
import nl.imreboersma.volti.annotate.AnnotationSymbol
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.VoltiDatabase
import nl.imreboersma.volti.setlist.Setlist
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** A PDF from the device is a score like a Drive file: listed, opened, a new version, deleted with what hangs off it. */
@RunWith(AndroidJUnit4::class)
class ImportTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = VoltiDatabase.get(context)
    private val syncer = Syncer.get(context)

    private fun pdf(pages: Int): ByteArray {
        val doc = PdfDocument()
        repeat(pages) { i ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            page.canvas.drawRect(100f, 100f, 150f + 40f * i, 300f, Paint().apply { color = Color.BLACK })
            doc.finishPage(page)
        }
        return ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray().also { doc.close() }
    }

    private fun ensemble() = runBlocking { db.ensembles().add(Ensemble(name = "Import test", instrument = "Horn")) }

    private fun import(ensemble: Long, name: String, bytes: ByteArray, replace: Boolean = false) = runBlocking {
        syncer.import(ensemble, emptyList(), listOf(name to { bytes.inputStream() }), replace).await()
    }

    private fun files(ensemble: Long) = runBlocking { db.files().all(ensemble) }

    @After
    fun clean() = runBlocking {
        db.ensembles().all().first().filter { it.name == "Import test" }.forEach { db.ensembles().remove(it) }
    }

    @Test
    fun anImportedPdfIsListedAndOpens() {
        val ensemble = ensemble()
        assertEquals(emptyList<Pair<String, Rejection>>(), import(ensemble, "Quartet.pdf", pdf(pages = 2)))
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            VoltiApp(nav)
        }
        compose.runOnUiThread { nav.navigate(EnsembleFiles(ensemble)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Quartet.pdf").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Quartet.pdf").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Page 1 of 2"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theSameNameReplacedIsANewVersionUnderMyMarks() {
        val ensemble = ensemble()
        import(ensemble, "Part.pdf", pdf(pages = 1))
        val first = files(ensemble).single()
        runBlocking { db.files().opened(ensemble, first.fileId, first.localMd5!!) }

        import(ensemble, "Part.pdf", pdf(pages = 2), replace = true)
        val now = files(ensemble).single()
        assertEquals(first.fileId, now.fileId) // The same score, so its marks and setlist places stay,
        assertTrue(now.changed) // and I'm told it changed under them.

        import(ensemble, "Part.pdf", pdf(pages = 3), replace = false) // Keep both: one more, under a name of its own.
        assertEquals(listOf("Part (2).pdf", "Part.pdf"), files(ensemble).map { it.name }.sorted())
    }

    @Test
    fun whatIsNoPdfIsSaidAndTheRestComesIn() {
        val ensemble = ensemble()
        val problems = runBlocking {
            syncer.import(
                ensemble, emptyList(),
                listOf("Notes.txt" to { "not a score".byteInputStream() }, "Good.pdf" to { pdf(pages = 1).inputStream() }),
                replace = false,
            ).await()
        }
        assertEquals(listOf("Notes.txt" to Rejection.NotAPdf), problems)
        assertEquals(listOf("Good.pdf"), files(ensemble).map { it.name })
    }

    @Test
    fun aDeletedImportLeavesItsSetlistsAndTakesItsMarks() {
        val ensemble = ensemble()
        import(ensemble, "Gone.pdf", pdf(pages = 1))
        val file = files(ensemble).single()
        val setlist = runBlocking {
            db.setlists().add(Setlist(ensembleId = ensemble, name = "Concert", createdAt = 1)).also {
                db.setlists().setPieces(it, listOf(file.fileId))
            }
        }
        runBlocking { db.annotations().addSymbols(listOf(AnnotationSymbol("s", file.scoreKey, 0, "Fermata", 1f, 1f, 0))) }

        runBlocking { syncer.delete(file) }

        assertTrue(files(ensemble).isEmpty())
        assertTrue(runBlocking { db.setlists().fileIds(setlist) }.isEmpty())
        assertTrue(runBlocking { db.annotations().symbolsForScore(file.scoreKey) }.isEmpty())
        assertTrue("the PDF went with it", !syncer.fileFor(file.md5).exists())
    }
}
