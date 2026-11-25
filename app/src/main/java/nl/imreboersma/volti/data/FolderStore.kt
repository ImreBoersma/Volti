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

/** The one folder the user granted through the system picker. */
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
     * Takes a read-only grant on [uri], stores it, then drops older grants.
     * If the grant cannot be taken, nothing changes.
     * Runs to the end even if the caller is cancelled, so the stored URI
     * never points at a released grant.
     */
    suspend fun setFolder(uri: Uri) = withContext(NonCancellable + Dispatchers.IO) {
        val resolver = appContext.contentResolver
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            return@withContext
        }
        store.edit { it[FOLDER_URI] = uri.toString() }
        resolver.persistedUriPermissions
            .filter { it.uri != uri }
            .forEach {
                resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }

    private fun canRead(uri: Uri): Boolean =
        appContext.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
}
