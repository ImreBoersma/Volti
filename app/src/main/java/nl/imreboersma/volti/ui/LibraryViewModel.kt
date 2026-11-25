package nl.imreboersma.volti.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.imreboersma.volti.data.FolderStore
import nl.imreboersma.volti.data.Score
import nl.imreboersma.volti.data.listScores

sealed interface LibraryState {
    data object Loading : LibraryState
    data object NoFolder : LibraryState

    /** [scores] is null when the folder could not be read. */
    data class Ready(val folder: Uri, val scores: List<Score>?) : LibraryState
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val store = FolderStore(app)

    val state: StateFlow<LibraryState> = store.folder
        .map { folder ->
            if (folder == null) {
                LibraryState.NoFolder
            } else {
                val scores = try {
                    listScores(app.contentResolver, folder)
                } catch (e: Exception) {
                    // Any provider failure, not just a lost grant. An uncaught one would crash on every
                    // start, because the folder is stored, with no way to pick another.
                    Log.w("Volti", "Can't list $folder", e)
                    null
                }
                LibraryState.Ready(folder, scores)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState.Loading)

    fun setFolder(uri: Uri) {
        viewModelScope.launch { store.setFolder(uri) }
    }
}
