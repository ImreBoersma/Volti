package nl.imreboersma.volti.drive

import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import nl.imreboersma.volti.fromAnotherApp
import nl.imreboersma.volti.sharedPdfs
import kotlinx.coroutines.flow.flowOf
import nl.imreboersma.volti.annotate.AnnotationSymbol
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.VoltiDatabase
import nl.imreboersma.volti.db.localKey
import nl.imreboersma.volti.setlist.Setlist
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import nl.imreboersma.volti.ui.theme.VoltiTheme

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

    /** Copied in as from another app, then taken in: what wasn't, with why. */
    private fun import(ensemble: Long, files: List<Pair<String, ByteArray>>, replace: Boolean = false) = runBlocking {
        val uris = files.map { (name, bytes) -> Uri.fromFile(java.io.File(context.cacheDir, name).apply { writeBytes(bytes) }) }
        val received = syncer.receive(uris).await()
        received.problems + syncer.import(ensemble, emptyList(), received, replace).await()
    }

    private fun import(ensemble: Long, name: String, bytes: ByteArray, replace: Boolean = false) =
        import(ensemble, listOf(name to bytes), replace)

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
            VoltiTheme { VoltiApp(nav) } // As MainActivity shows it.
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
    fun aSharedPdfGoesIntoTheEnsembleAndFolderIPick() {
        val ensemble = ensemble()
        import(ensemble, "Already.pdf", pdf(pages = 1)) // So the ensemble has a folder to go into: none, the top.
        val shared = Uri.parse("content://nl.imreboersma.volti.test.sharing/Shared%20part.pdf") // As Gmail would.
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            VoltiTheme { VoltiApp(nav, shared = flowOf(listOf(shared))) } // As MainActivity shows it.
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Into which ensemble?").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Import test").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Import here").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1 PDF to import into Import test").assertExists()
        compose.onNodeWithText("Import here").performClick()
        compose.waitUntil(10_000) { files(ensemble).size == 2 }
        assertEquals(listOf("Already.pdf", "Shared part.pdf"), files(ensemble).map { it.name }.sorted())
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Import here").fetchSemanticsNodes().isEmpty() }
        compose.runOnUiThread { nav.popBackStack() } // Back goes to the library, not to the question.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Add an ensemble").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun onlyAnotherAppsFilesAreTakenFromAShare() {
        assertTrue(fromAnotherApp(context, Uri.parse("content://nl.imreboersma.volti.test.sharing/a.pdf")))
        // A path, or Volti's own provider: files Volti could read but the sender couldn't.
        assertFalse(fromAnotherApp(context, Uri.fromFile(java.io.File(context.filesDir, "drive/x.pdf"))))
        assertFalse(fromAnotherApp(context, Uri.parse("content://${context.packageName}.androidx-startup/x.pdf")))
    }

    @Test
    fun anOpenWithOrAShareHandsOverItsPdfs() {
        val a = Uri.parse("content://x/a.pdf")
        val b = Uri.parse("content://x/b.pdf")
        assertEquals(listOf(a), sharedPdfs(Intent(Intent.ACTION_VIEW, a)))
        assertEquals(listOf(a), sharedPdfs(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, a)))
        assertEquals(listOf(a, b), sharedPdfs(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))))
        assertEquals(emptyList<Uri>(), sharedPdfs(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun aFolderComesWithItsSubfoldersAndTheMarksMadeOnItsPdfs() {
        val ensemble = ensemble()
        val inFolder = pdf(pages = 1)
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(inFolder).joinToString("") { "%02x".format(it) }
        // Marked before, when Volti read the folder in place.
        val mark = "old-" + java.util.UUID.randomUUID()
        runBlocking { db.annotations().addSymbols(listOf(AnnotationSymbol(mark, localKey(md5), 0, "Fermata", 1f, 1f, 0))) }
        val uris = listOf("Overture.pdf" to inFolder, "Suite.pdf" to pdf(pages = 2)).map { (name, bytes) ->
            Uri.fromFile(java.io.File(context.cacheDir, name).apply { writeBytes(bytes) })
        }
        runBlocking {
            val received = syncer.receive(uris, listOf(listOf("Concert"), listOf("Concert", "Encores"))).await()
            syncer.import(ensemble, listOf("Season"), received, replace = false).await()
            assertTrue("the batch goes once imported", syncer.received(received.batch) == null)
        }
        val byName = files(ensemble).associateBy { it.name }
        assertEquals(listOf("Season", "Concert"), byName.getValue("Overture.pdf").folders)
        assertEquals(listOf("Season", "Concert", "Encores"), byName.getValue("Suite.pdf").folders)
        val overture = byName.getValue("Overture.pdf")
        assertEquals(listOf(mark), runBlocking { db.annotations().symbolsForScore(overture.scoreKey) }.map { it.id })
    }

    @Test
    fun aBatchThatCantBeReadIsGone() = runBlocking {
        val received = syncer.receive(emptyList()).await()
        val file = java.io.File(context.filesDir, "drive-incoming/${received.batch}")
        assertTrue(file.isFile)
        file.writeText("{ half a batch") // A process that ended mid-write, before batches were written whole.
        assertEquals(null, syncer.received(received.batch))
    }

    @Test
    fun replacedTheMarksMadeOnThatPdfInPlaceComeAlong() {
        val ensemble = ensemble()
        import(ensemble, "Hoorn 2.pdf", pdf(pages = 1))
        val again = pdf(pages = 2)
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(again).joinToString("") { "%02x".format(it) }
        val mark = "inPlace-" + java.util.UUID.randomUUID()
        runBlocking { db.annotations().addSymbols(listOf(AnnotationSymbol(mark, localKey(md5), 0, "Fermata", 1f, 1f, 0))) }
        import(ensemble, "Hoorn 2.pdf", again, replace = true)
        val file = files(ensemble).single()
        assertEquals(listOf(mark), runBlocking { db.annotations().symbolsForScore(file.scoreKey) }.map { it.id })
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
        val problems = import(ensemble, listOf("Notes.txt" to "not a score".toByteArray(), "Good.pdf" to pdf(pages = 1)))
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
