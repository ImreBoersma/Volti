package nl.imreboersma.volti.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.imreboersma.volti.data.FolderStore
import nl.imreboersma.volti.data.LibraryEntry
import nl.imreboersma.volti.data.Subfolder
import nl.imreboersma.volti.data.folderName
import nl.imreboersma.volti.data.listFolder

sealed interface LibraryState {
    data object Loading : LibraryState
    data object NoFolder : LibraryState

    /**
     * Showing the folder at [path] inside the granted [folder], named [name]. [entries] is null when that
     * folder could not be read.
     */
    data class Ready(
        val folder: Uri,
        val name: String,
        val path: List<Subfolder>,
        val entries: List<LibraryEntry>?,
    ) : LibraryState
}

/**
 * The subfolders opened below one granted folder, outermost first. Tied to that folder: after "Change folder"
 * it no longer applies, so a tap on an old entry can't list an old folder id against the new grant.
 */
private data class OpenPath(val folder: String, val subfolders: List<Subfolder>) {
    /** Flat for SavedStateHandle: the folder, then id and name of each subfolder. */
    fun encode() = arrayListOf(folder) + subfolders.flatMap { listOf(it.documentId, it.name) }

    companion object {
        fun decode(saved: List<String>): OpenPath? = saved.firstOrNull()?.let { folder ->
            OpenPath(folder, saved.drop(1).chunked(2).filter { it.size == 2 }.map { (id, name) -> Subfolder(id, name) })
        }
    }
}

class LibraryViewModel(app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app) {
    private val store = FolderStore(app)

    /** In saved state, so the open subfolder survives process death along with the reader on top of it. */
    private val savedPath = handle.getStateFlow(PATH, arrayListOf<String>())

    /** The granted folder's display name, queried once per folder. Only touched in the state flow below. */
    private var cachedName: Pair<Uri, String>? = null

    val state: StateFlow<LibraryState> = combine(store.folder, savedPath) { folder, saved ->
        if (folder == null) return@combine LibraryState.NoFolder
        val path = OpenPath.decode(saved)?.takeIf { it.folder == folder.toString() }?.subfolders.orEmpty()
        val resolver = getApplication<Application>().contentResolver
        val name = cachedName?.takeIf { it.first == folder }?.second ?: try {
            folderName(resolver, folder)?.also { cachedName = folder to it }
        } catch (e: Exception) {
            Log.w("Volti", "Can't read the name of $folder", e)
            null
        } ?: "Library"
        val entries = try {
            if (path.isEmpty()) listFolder(resolver, folder) else listFolder(resolver, folder, path.last().documentId)
        } catch (e: Exception) {
            // Any provider failure, not just a lost grant. An uncaught one would crash on every start,
            // because the folder is stored, with no way to pick another.
            Log.w("Volti", "Can't list $folder", e)
            null
        }
        LibraryState.Ready(folder, name, path, entries)
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState.Loading)

    fun setFolder(uri: Uri) {
        viewModelScope.launch { store.setFolder(uri) }
    }

    /** Opens [subfolder] of the folder shown in [ready]. */
    fun open(ready: LibraryState.Ready, subfolder: Subfolder) {
        handle[PATH] = OpenPath(ready.folder.toString(), ready.path + subfolder).encode()
    }

    /** Goes up one folder. False at the top: there is nowhere further up inside the grant. */
    fun up(): Boolean {
        val path = OpenPath.decode(savedPath.value) ?: return false
        if (path.subfolders.isEmpty()) return false
        handle[PATH] = path.copy(subfolders = path.subfolders.dropLast(1)).encode()
        return true
    }

    private companion object {
        const val PATH = "path"
    }
}
