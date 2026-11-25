package nl.imreboersma.volti.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.imreboersma.volti.data.FolderStore
import nl.imreboersma.volti.data.FoundEntry
import nl.imreboersma.volti.data.NaturalOrder
import nl.imreboersma.volti.data.ReaderSettings
import nl.imreboersma.volti.data.ReaderSettingsStore
import nl.imreboersma.volti.data.LibraryEntry
import nl.imreboersma.volti.data.Subfolder
import nl.imreboersma.volti.data.folderName
import nl.imreboersma.volti.data.listFolder
import nl.imreboersma.volti.data.WALK_LIMIT
import nl.imreboersma.volti.data.matchesWords
import nl.imreboersma.volti.data.searchWords
import nl.imreboersma.volti.data.searchableNames
import nl.imreboersma.volti.data.walkFolder

/** What a search for [query] found below the folder on screen. [truncated]: the walk hit its limit. */
data class SearchResults(val query: String, val results: List<FoundEntry>, val truncated: Boolean = false)

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
    private val readerSettings = ReaderSettingsStore(app)

    /** How scores open: the reader reads these when it opens one. */
    val settings: StateFlow<ReaderSettings> = readerSettings.settings
        .stateIn(viewModelScope, SharingStarted.Lazily, ReaderSettings())

    fun setCropMargins(on: Boolean) {
        viewModelScope.launch { readerSettings.setCropMargins(on) }
    }

    fun setHalfPageTurns(on: Boolean) {
        viewModelScope.launch { readerSettings.setHalfPageTurns(on) }
    }

    /** In saved state, so the open subfolder survives process death along with the reader on top of it. */
    private val savedPath = handle.getStateFlow(PATH, arrayListOf<String>())

    /** The granted folder's display name, queried once per folder. Only touched in the state flow below. */
    private var cachedName: Pair<Uri, String>? = null

    /** Bumped by [refresh]: lists the folder again. */
    private val generation = MutableStateFlow(0)

    /** Bumped only by a pull: the search index is walked again. Coming back to the app keeps it. */
    private val indexGeneration = MutableStateFlow(0)

    /** The library screen was stopped (another app, or the reader on top) since it last started. */
    private var stoppedSinceStart = false

    /** True while a refresh the user pulled for is running: drives the pull indicator. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    val state: StateFlow<LibraryState> = combine(store.folder, savedPath, generation) { folder, saved, _ ->
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
        LibraryState.Ready(folder, name, path, entries).also { _refreshing.value = false }
    }
        .flowOn(Dispatchers.IO)
        // Lazily, not WhileSubscribed: returning to the app refreshes explicitly (see LibraryScreen), and an
        // idle flow costs nothing.
        .stateIn(viewModelScope, SharingStarted.Lazily, LibraryState.Loading)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /**
     * An entry with what search needs precomputed: its names made searchable, once. The index is sorted when
     * it is built, so filtering keeps the order and a keystroke only filters.
     */
    private class Indexed(val found: FoundEntry, val names: List<String>)

    /** The index of the folder searched last: everything below it, walked once, filtered per keystroke. */
    private var index: Pair<IndexKey, List<Indexed>>? = null
    private val indexLock = Mutex()

    private data class IndexKey(val folder: Uri, val path: List<Subfolder>, val generation: Int)

    /**
     * The index below the folder in [state], walking it if it isn't there yet. One walk at a time; a walk whose
     * caller was cancelled (a newer folder, query or pull) stops between folders and is not kept.
     */
    private suspend fun indexFor(state: LibraryState.Ready, generation: Int): List<Indexed> = indexLock.withLock {
        val key = IndexKey(state.folder, state.path, generation)
        index?.takeIf { it.first == key }?.second ?: withContext(Dispatchers.IO) {
            val started = SystemClock.uptimeMillis()
            val resolver = getApplication<Application>().contentResolver
            val job = coroutineContext[Job]
            val top = state.path.lastOrNull()?.documentId ?: DocumentsContract.getTreeDocumentId(state.folder)
            val found = walkFolder(resolver, state.folder, top, stopped = { job?.isActive == false })
            ensureActive() // Stopped part-way: don't keep a half index.
            val order = NaturalOrder()
            found.map { Indexed(it, searchableNames(it)) }
                .sortedWith(compareBy(order) { (it.found.parents.map(Subfolder::name) + it.found.entry.name).joinToString("/") })
                .also {
                    index = key to it
                    Log.d("Volti", "Search index: ${it.size} entries in ${SystemClock.uptimeMillis() - started} ms")
                }
        }
    }

    /** The search field got focus: walk the folder now, so the first letter typed doesn't wait for it. */
    fun prepareSearch() {
        val ready = state.value as? LibraryState.Ready ?: return
        if (ready.entries == null) return
        viewModelScope.launch { indexFor(ready, indexGeneration.value) }
    }

    /**
     * Search results for [query] below the folder on screen; null when not searching. mapLatest: a newer folder,
     * query or pull cancels the work for the older one, a walk included.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val search: StateFlow<SearchResults?> = combine(state, _query, indexGeneration) { state, query, generation ->
        Triple(state, query, generation)
    }.mapLatest { (state, query, generation) ->
        if (query.isBlank() || state !is LibraryState.Ready || state.entries == null) return@mapLatest null
        val entries = indexFor(state, generation)
        val words = searchWords(query)
        SearchResults(
            query,
            entries.filter { matchesWords(words, it.names) }.map { it.found },
            truncated = entries.size >= WALK_LIMIT,
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    fun setQuery(text: String) {
        _query.value = text
    }

    /** Lists the folder again. [pulled]: the user asked, so show the indicator and walk the search index again. */
    fun refresh(pulled: Boolean) {
        Log.d("Volti", "Library refresh, pulled=$pulled")
        if (pulled) {
            _refreshing.value = true
            indexGeneration.value++
        }
        generation.value++
    }

    /**
     * The library screen's lifecycle. Kept here, not in the screen: navigation drops the screen's state while the
     * reader is on top, and coming back from the reader must still refresh.
     */
    fun libraryStopped() {
        stoppedSinceStart = true
    }

    fun libraryStarted() {
        if (stoppedSinceStart) refresh(pulled = false)
        stoppedSinceStart = false
    }

    fun setFolder(uri: Uri) {
        viewModelScope.launch { store.setFolder(uri) }
    }

    /** Opens [subfolder] of the folder shown in [ready]. */
    fun open(ready: LibraryState.Ready, subfolder: Subfolder) {
        handle[PATH] = OpenPath(ready.folder.toString(), ready.path + subfolder).encode()
    }

    /** Opens a folder found by search, below the folder shown in [ready], and ends the search. */
    fun open(ready: LibraryState.Ready, found: FoundEntry) {
        val subfolder = found.entry as? Subfolder ?: return
        handle[PATH] = OpenPath(ready.folder.toString(), ready.path + found.parents + subfolder).encode()
        _query.value = ""
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
