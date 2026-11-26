package nl.imreboersma.volti.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnsembleDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        VoltiDatabase::class.java,
    ).build()
    private val dao = db.ensembles()

    @After
    fun close() = db.close()

    @Test
    fun ensemblesAreListedByName() = runBlocking {
        dao.add(Ensemble(name = "harmonie", instrument = "Bb clarinet 2", folderId = "folderAAAAAA"))
        dao.add(Ensemble(name = "Big band", instrument = "Alto sax", folderId = "folderBBBBBB", resourceKey = "0-k"))
        val all = dao.all().first()
        assertEquals(listOf("Big band", "harmonie"), all.map { it.name })
        assertEquals("0-k", all[0].resourceKey)
    }

    @Test
    fun aFolderIsOneEnsembleOnly() = runBlocking {
        dao.add(Ensemble(name = "Harmonie", instrument = "Clarinet", folderId = "folderAAAAAA"))
        try {
            dao.add(Ensemble(name = "Again", instrument = "Sax", folderId = "folderAAAAAA"))
            fail("added the same folder twice")
        } catch (_: SQLiteConstraintException) {
        }
        assertEquals(1, dao.all().first().size)
    }

    @Test
    fun removing() = runBlocking {
        val id = dao.add(Ensemble(name = "Harmonie", instrument = "Clarinet", folderId = "folderAAAAAA"))
        dao.remove(dao.get(id)!!)
        assertNull(dao.get(id))
    }
}
