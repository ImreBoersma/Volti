package nl.imreboersma.volti.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ReaderSettings(val cropMargins: Boolean = true, val halfPageTurns: Boolean = false)

private val CROP_MARGINS = booleanPreferencesKey("crop_margins")
private val HALF_PAGE_TURNS = booleanPreferencesKey("half_page_turns")

/** How the reader shows a score. Stored next to the folder, in the same DataStore. */
class ReaderSettingsStore(context: Context) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<ReaderSettings> = store.data.map {
        ReaderSettings(
            cropMargins = it[CROP_MARGINS] ?: ReaderSettings().cropMargins,
            halfPageTurns = it[HALF_PAGE_TURNS] ?: ReaderSettings().halfPageTurns,
        )
    }

    suspend fun setCropMargins(on: Boolean) {
        store.edit { it[CROP_MARGINS] = on }
    }

    suspend fun setHalfPageTurns(on: Boolean) {
        store.edit { it[HALF_PAGE_TURNS] = on }
    }
}
