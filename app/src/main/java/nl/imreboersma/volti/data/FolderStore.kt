package nl.imreboersma.volti.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal val Context.dataStore by preferencesDataStore(name = "settings")
internal val FOLDER_URI = stringPreferencesKey("folder_uri")

/** The one folder granted through the system picker, from when Volti read a folder in place (before milestone 6). */
class FolderStore(
    context: Context,
    private val store: DataStore<Preferences> = context.applicationContext.dataStore,
) {
    private val appContext = context.applicationContext

    /** The stored folder, or null if none is stored or its read grant is gone. */
    val folder: Flow<Uri?> = store.data
        .map { prefs -> prefs[FOLDER_URI]?.let(Uri::parse)?.takeIf(::canRead) }
        .flowOn(Dispatchers.IO)

    /**
     * Lets the folder go: its grant and the stored address. Since milestone 6 a folder is imported, not read in
     * place; this one was granted before that.
     */
    suspend fun forget() = withContext(NonCancellable + Dispatchers.IO) {
        val resolver = appContext.contentResolver
        resolver.persistedUriPermissions.forEach {
            resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        store.edit { it.remove(FOLDER_URI) }
    }

    private fun canRead(uri: Uri): Boolean =
        appContext.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
}
