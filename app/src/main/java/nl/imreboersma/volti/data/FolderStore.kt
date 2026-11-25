package nl.imreboersma.volti.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")
private val FOLDER_URI = stringPreferencesKey("folder_uri")

/** The one folder the user granted through the system picker. */
class FolderStore(context: Context) {
    private val appContext = context.applicationContext

    val folder: Flow<Uri?> = appContext.dataStore.data.map { prefs ->
        prefs[FOLDER_URI]?.let(Uri::parse)
    }

    /** Takes a read-only grant on [uri], drops any older grants, and stores the URI. */
    suspend fun setFolder(uri: Uri) {
        val resolver = appContext.contentResolver
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        resolver.persistedUriPermissions
            .filter { it.uri != uri }
            .forEach {
                resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        appContext.dataStore.edit { it[FOLDER_URI] = uri.toString() }
    }
}
