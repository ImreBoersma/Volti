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
    fun removingAnEnsembleRemovesItsFiles() = runBlocking {
        val id = ensemble()
        files.save(listOf(file(id, "a", local = v1)))
        db.ensembles().remove(db.ensembles().get(id)!!)
        assertTrue(files.versionsInUse().isEmpty())
    }

    @Test
    fun ensemblesSurviveTheMigrationToVersion2() {
        val name = "migration-test"
        migrations.createDatabase(name, 1).use {
            it.execSQL("INSERT INTO ensemble (name, instrument, folderId) VALUES ('Harmonie', 'Horn', 'folderAAAAAA')")
        }
        migrations.runMigrationsAndValidate(name, 2, true).close()
        val migrated = Room.databaseBuilder(context, VoltiDatabase::class.java, name).build()
        try {
            val all = runBlocking { migrated.ensembles().all().first() }
            assertEquals(listOf("Harmonie"), all.map { it.name })
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
