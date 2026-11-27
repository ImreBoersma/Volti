package nl.imreboersma.volti.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnsembleFileDaoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, VoltiDatabase::class.java).build()
    private val files = db.files()

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), VoltiDatabase::class.java)

    @After
    fun close() = db.close()

    private val v1 = "1".repeat(32)
    private val v2 = "2".repeat(32)

    private suspend fun ensemble() = db.ensembles().add(Ensemble(name = "Harmonie", instrument = "Horn", folderId = "folderAAAAAA"))

    private fun file(ensembleId: Long, id: String, local: String? = null, seen: String? = null, removed: Boolean = false) =
        EnsembleFile(ensembleId, id, null, "$id.pdf", listOf("Hoorn", "Oud"), v1, 100, local, seen, removed)

    @Test
    fun replaceDropsTheRowsTheUpdateLeavesOut() = runBlocking {
        val id = ensemble()
        files.save(listOf(file(id, "a"), file(id, "b")))
        files.replace(id) { old -> old.filter { it.fileId == "a" } }
        val shown = files.shown(id).first()
        assertEquals(listOf("a"), shown.map { it.fileId })
        assertEquals(listOf("Hoorn", "Oud"), shown.single().folders)
    }

    @Test
    fun openingRecordsTheVersionISaw() = runBlocking {
        val id = ensemble()
        files.save(listOf(file(id, "a", local = v1)))
        files.opened(id, "a", v1)
        assertEquals(v1, files.all(id).single().seenMd5)
    }

    @Test
    fun aDownloadCountsOnlyForTheVersionItWasFor() = runBlocking {
        val id = ensemble()
        files.save(listOf(file(id, "a")))
        files.downloaded(id, "a", v2) // A version Drive has since replaced: ignored.
        assertEquals(null, files.all(id).single().localMd5)
        files.downloaded(id, "a", v1)
        assertEquals(v1, files.all(id).single().localMd5)
    }

    @Test
    fun versionsInUseIncludeTheOnesISawAndRemovedFiles() = runBlocking {
        val id = ensemble()
        files.save(listOf(file(id, "a", local = v2, seen = v1), file(id, "b", local = v1, removed = true)))
        assertEquals(setOf(v1, v2), files.versionsInUse().toSet())
    }

    @Test
    fun myNameFollowsTheFileIntoAnotherEnsembleAndBackAfterRemoval() = runBlocking {
        val first = ensemble()
        files.replace(first) { listOf(file(first, "x")) }
        files.rename("x", "Bizet - Farandole - F Horn 1")
        // The same Drive file, synced into a second ensemble (a subfolder added on its own): it has my name there too.
        val second = db.ensembles().add(Ensemble(name = "Concert", instrument = "Horn", folderId = "folderBBBBBB"))
        files.replace(second) { listOf(file(second, "x")) }
        assertEquals("Bizet - Farandole - F Horn 1", files.all(second).single().displayName)
        // The first ensemble removed and added again: its first sync brings the name back.
        db.ensembles().remove(db.ensembles().get(first)!!)
        val again = ensemble()
        files.replace(again) { listOf(file(again, "x")) }
        assertEquals("Bizet - Farandole - F Horn 1", files.all(again).single().displayName)
        // Back to the file name: everywhere.
        files.rename("x", null)
        files.replace(again) { it }
        assertEquals(listOf(null, null), (files.all(second) + files.all(again)).map { it.displayName })
    }

    @Test
    fun removingAnEnsembleRemovesItsFiles() = runBlocking {
        val id = ensemble()
        files.save(listOf(file(id, "a", local = v1)))
        db.ensembles().remove(db.ensembles().get(id)!!)
        assertTrue(files.versionsInUse().isEmpty())
    }

    @Test
    fun newFilesAreTheOnesAddedSinceILastLooked() = runBlocking {
        val id = ensemble()
        files.save(listOf(file(id, "baseline"), file(id, "early").copy(addedAt = 100), file(id, "late").copy(addedAt = 300)))
        assertEquals(2, db.ensembles().newFiles().first().single().count) // Never looked: all added ones.
        db.ensembles().looked(id, 200)
        assertEquals(1, db.ensembles().newFiles().first().single().count)
        db.ensembles().looked(id, 400)
        assertTrue(db.ensembles().newFiles().first().isEmpty())
    }

    @Test
    fun markingMineAlsoDecides() = runBlocking {
        val id = ensemble()
        files.save(listOf(file(id, "a"), file(id, "b"), file(id, "c")))
        files.setMine(id, listOf("a", "b"), true)
        files.setMine(id, listOf("c"), false)
        val all = files.all(id).associateBy { it.fileId }
        assertEquals(listOf(true, true, false), listOf("a", "b", "c").map { all.getValue(it).mine })
        assertTrue(all.values.all { it.decided })
    }

    @Test
    fun onlyMyPartsAndWhatIOpenedStayOnTheDevice() = runBlocking {
        val id = ensemble()
        files.save(
            listOf(
                file(id, "mine", local = v1).copy(mine = true),
                file(id, "opened", local = v1, seen = v1),
                file(id, "other", local = v1),
            ),
        )
        files.forgetUnused(id)
        val local = files.all(id).associate { it.fileId to it.localMd5 }
        assertEquals(mapOf("mine" to v1, "opened" to v1, "other" to null), local)
    }

    @Test
    fun strokesAreKeptPerScore() = runBlocking {
        val strokes = db.annotations()
        fun stroke(id: String, score: String) = nl.imreboersma.volti.annotate.AnnotationStroke(id, score, 0, 0, 3f, byteArrayOf(1, 2))
        strokes.add(listOf(stroke("a", "drive:x"), stroke("b", "drive:x"), stroke("c", "local:y")))
        assertEquals(setOf("a", "b"), strokes.forScore("drive:x").map { it.id }.toSet())
        strokes.remove(listOf("a"))
        assertEquals(listOf("b"), strokes.forScore("drive:x").map { it.id })
    }

    @Test
    fun symbolsAreKeptPerScore() = runBlocking {
        val marks = db.annotations()
        fun symbol(id: String, score: String) = nl.imreboersma.volti.annotate.AnnotationSymbol(id, score, 2, "Fermata", 10f, 20f, 0)
        marks.addSymbols(listOf(symbol("a", "drive:x"), symbol("b", "local:y")))
        assertEquals(listOf("a"), marks.symbolsForScore("drive:x").map { it.id })
        marks.removeSymbols(listOf("a"))
        assertTrue(marks.symbolsForScore("drive:x").isEmpty())
    }

    @Test
    fun openingANewerVersionKeepsTheOneISawUntilChecked() = runBlocking {
        val id = ensemble()
        files.save(listOf(file(id, "a", local = v1)))
        files.opened(id, "a", v1)
        assertEquals(null, files.previousOf("a")) // The first time there is nothing before it.
        files.opened(id, "a", v2)
        assertEquals(v1, files.previousOf("a"))
        files.opened(id, "a", v2) // Opening it again keeps what there is to compare.
        assertEquals(v1, files.previousOf("a"))
        assertTrue(v1 in files.versionsInUse())
        files.checked("a")
        assertEquals(null, files.previousOf("a"))
    }

    @Test
    fun checkedInOneEnsembleStaysCheckedInTheOther() = runBlocking {
        val a = ensemble()
        val b = db.ensembles().add(Ensemble(name = "Other", instrument = "Horn", folderId = "folderBBBBBB"))
        files.save(listOf(file(a, "x", local = v1), file(b, "x", local = v1)))
        files.opened(a, "x", v1)
        files.opened(b, "x", v1)
        files.opened(a, "x", v2) // A change, seen in the first ensemble,
        assertEquals(v1, files.previousOf("x"))
        files.checked("x") // checked there,
        files.opened(b, "x", v2) // and the same version in the second is no change any more.
        assertEquals(null, files.previousOf("x"))
    }

    @Test
    fun ensemblesSurviveTheMigrations() {
        val name = "migration-test"
        migrations.createDatabase(name, 1).use {
            it.execSQL("INSERT INTO ensemble (name, instrument, folderId) VALUES ('Harmonie', 'Horn', 'folderAAAAAA')")
        }
        migrations.runMigrationsAndValidate(name, 10, true).close()
        val migrated = Room.databaseBuilder(context, VoltiDatabase::class.java, name).build()
        try {
            val all = runBlocking { migrated.ensembles().all().first() }
            assertEquals(listOf("Harmonie"), all.map { it.name })
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun marksOnADriveFileKeepTheirFileAfterVersion9() {
        val name = "migration-keys"
        migrations.createDatabase(name, 8).use {
            it.execSQL("INSERT INTO annotation_symbol (id, scoreKey, page, symbol, x, y, color) VALUES ('s', 'drive:abc', 0, 'Fermata', 1, 1, 0)")
            it.execSQL("INSERT INTO annotation_symbol (id, scoreKey, page, symbol, x, y, color) VALUES ('l', 'local:123', 0, 'Fermata', 1, 1, 0)")
        }
        migrations.runMigrationsAndValidate(name, 10, true).use {
            it.query("SELECT id, scoreKey FROM annotation_symbol ORDER BY id").use { c ->
                val keys = buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
                assertEquals(mapOf("l" to "local:123", "s" to fileKey("abc")), keys)
            }
        }
        context.deleteDatabase(name)
    }
}
