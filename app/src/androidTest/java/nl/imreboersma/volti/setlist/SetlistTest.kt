package nl.imreboersma.volti.setlist

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.imreboersma.volti.SetlistPieces
import nl.imreboersma.volti.VoltiApp
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.EnsembleFile
import nl.imreboersma.volti.db.VoltiDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SetlistTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun file(ensembleId: Long, id: String) =
        EnsembleFile(ensembleId, id, null, "$id.pdf", emptyList(), "0".repeat(32), 1, mine = true)

    @Test
    fun piecesKeepTheirOrderAndAGoneFileShowsAsGone() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, VoltiDatabase::class.java).build()
        try {
            val ensemble = db.ensembles().add(Ensemble(name = "E", instrument = "Horn", folderId = "folderAAAAAA"))
            db.files().save(listOf(file(ensemble, "a"), file(ensemble, "b")))
            val setlist = db.setlists().add(Setlist(ensembleId = ensemble, name = "Concert", createdAt = 1))
            db.setlists().setPieces(setlist, listOf("b", "gone", "a"))
            val pieces = db.setlists().pieces(setlist).first()
            assertEquals(listOf("b", "gone", "a"), pieces.map { it.fileId })
            assertEquals("b.pdf", pieces[0].file?.name)
            assertNull(pieces[1].file)
            db.ensembles().remove(db.ensembles().get(ensemble)!!) // The ensemble's setlists go with it.
            assertEquals(emptyList<Setlist>(), db.setlists().forEnsemble(ensemble).first())
        } finally {
            db.close()
        }
    }

    @Test
    fun reorderingOnlyReordersAndQuickChangesDontUndoEachOther() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, VoltiDatabase::class.java).build()
        try {
            val ensemble = db.ensembles().add(Ensemble(name = "E", instrument = "Horn", folderId = "folderAAAAAA"))
            val setlist = db.setlists().add(Setlist(ensembleId = ensemble, name = "Concert", createdAt = 1))
            val dao = db.setlists()
            dao.addPieces(setlist, listOf("a", "b"))
            dao.addPieces(setlist, listOf("c"))
            dao.removePiece(setlist, "a")
            dao.removePiece(setlist, "b") // Right after: from what is stored, so "a" stays gone.
            assertEquals(listOf("c"), dao.fileIds(setlist))
            dao.addPieces(setlist, listOf("d", "e"))
            dao.reorder(setlist, listOf("e", "a", "c")) // An old order: "a" isn't brought back, "d" isn't dropped.
            assertEquals(listOf("e", "c", "d"), dao.fileIds(setlist))
        } finally {
            db.close()
        }
    }

    private lateinit var nav: NavHostController

    @After
    fun clean() = runBlocking {
        val db = VoltiDatabase.get(context)
        db.ensembles().all().first().filter { it.name == "Setlist test" }.forEach { db.ensembles().remove(it) }
    }

    @Test
    fun aDragAfterAddingInTwoRoundsKeepsEveryPiece() {
        val db = VoltiDatabase.get(context)
        val setlist = runBlocking {
            val ensemble = db.ensembles().add(Ensemble(name = "Setlist test", instrument = "Horn", folderId = "f" + UUID.randomUUID().toString().take(12)))
            db.files().save(listOf("one", "two", "three").map { file(ensemble, it) })
            db.setlists().add(Setlist(ensembleId = ensemble, name = "Concert", createdAt = 1)).also { db.setlists().addPieces(it, listOf("one")) }
        }
        compose.setContent {
            nav = rememberNavController()
            VoltiApp(nav)
        }
        compose.runOnUiThread { nav.navigate(SetlistPieces(setlist)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("one.pdf").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { db.setlists().addPieces(setlist, listOf("two", "three")) } // A second round, while the screen shows.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("three.pdf").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("Drag to move")[0].performTouchInput {
            swipe(center, center.copy(y = center.y + 80.dp.toPx()), durationMillis = 600)
        }
        compose.waitForIdle()
        val order = runBlocking {
            var stored = emptyList<String>()
            repeat(50) {
                stored = db.setlists().fileIds(setlist)
                if (stored.firstOrNull() == "two") return@runBlocking stored
                kotlinx.coroutines.delay(20)
            }
            stored
        }
        assertEquals(listOf("two", "one", "three"), order)
    }

    @Test
    fun draggingAPieceDownTwoPlacesStoresTheNewOrder() {
        val db = VoltiDatabase.get(context)
        val setlist = runBlocking {
            val ensemble = db.ensembles().add(Ensemble(name = "Setlist test", instrument = "Horn", folderId = "f" + UUID.randomUUID().toString().take(12)))
            db.files().save(listOf("one", "two", "three", "four").map { file(ensemble, it) })
            db.setlists().add(Setlist(ensembleId = ensemble, name = "Concert", createdAt = 1)).also {
                db.setlists().setPieces(it, listOf("one", "two", "three", "four"))
            }
        }
        compose.setContent {
            nav = rememberNavController()
            VoltiApp(nav)
        }
        compose.runOnUiThread { nav.navigate(SetlistPieces(setlist)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("one.pdf").fetchSemanticsNodes().isNotEmpty() }
        // The first piece's handle, dragged down a little over two rows (a row is 64 dp).
        compose.onAllNodesWithContentDescription("Drag to move")[0].performTouchInput {
            swipe(center, center.copy(y = center.y + 140.dp.toPx()), durationMillis = 600)
        }
        compose.waitForIdle()
        val order = runBlocking {
            var stored = emptyList<String>()
            repeat(50) {
                stored = db.setlists().pieces(setlist).first().map { it.fileId }
                if (stored.first() != "one") return@runBlocking stored
                kotlinx.coroutines.delay(20)
            }
            stored
        }
        assertEquals(listOf("two", "three", "one", "four"), order)
    }
}
