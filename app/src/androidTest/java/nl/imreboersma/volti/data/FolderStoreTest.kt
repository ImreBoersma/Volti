package nl.imreboersma.volti.data

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FolderStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ungranted =
        Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AVoltiTestNotGranted")

    // A fresh directory per test, so a DataStore still shutting down never shares a file with the next test.
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var prefs: DataStore<Preferences>
    private lateinit var store: FolderStore

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        prefs = PreferenceDataStoreFactory.create(scope = scope) {
            File(tmp.root, "folder-store-test.preferences_pb")
        }
        store = FolderStore(context, prefs)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun noStoredFolderGivesNull() = runBlocking {
        assertNull(store.folder.first())
    }

    @Test
    fun storedFolderWithoutGrantGivesNull() = runBlocking {
        prefs.edit { it[FOLDER_URI] = ungranted.toString() }
        assertNull(store.folder.first())
    }

    @Test
    fun aForgottenFolderIsGone() = runBlocking {
        prefs.edit { it[FOLDER_URI] = ungranted.toString() }
        store.forget()
        assertNull(prefs.data.first()[FOLDER_URI])
        assertEquals(emptyList<Uri>(), context.contentResolver.persistedUriPermissions.map { it.uri })
    }
}
