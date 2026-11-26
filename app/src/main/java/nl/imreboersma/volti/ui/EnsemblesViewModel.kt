package nl.imreboersma.volti.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.imreboersma.volti.EnsembleFiles
import nl.imreboersma.volti.data.EnsembleRow
import nl.imreboersma.volti.data.ensembleRows
import nl.imreboersma.volti.data.existingPath
import nl.imreboersma.volti.data.searchEnsemble
import nl.imreboersma.volti.data.looksLikeMyPart
import nl.imreboersma.volti.data.parseInstrument
import nl.imreboersma.volti.data.sortedByPlace
import kotlinx.coroutines.flow.combine
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.VoltiDatabase
import nl.imreboersma.volti.drive.parseFolderLink
import nl.imreboersma.volti.drive.SyncState
import nl.imreboersma.volti.drive.Syncer
import nl.imreboersma.volti.drive.Rejection
import kotlinx.coroutines.flow.map
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import nl.imreboersma.volti.db.EnsembleFile

class EnsemblesViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = VoltiDatabase.get(app).ensembles()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var adding: Job? = null

    /**
     * Adds the ensemble and calls [onAdded] with its id, or sets [error] and leaves the form as it is. Without a
     * [link], its files are only what I import.
     */
    fun add(link: String, name: String, instrument: String, onAdded: (Long) -> Unit) {
        if (adding?.isActive == true) return // A second tap on Add.
        val folder = link.takeIf { it.isNotBlank() }?.let(::parseFolderLink)
        _error.value = when {
            link.isNotBlank() && folder == null -> "That isn't a link to a Drive folder."
            name.isBlank() -> "Give the ensemble a name."
            instrument.isBlank() -> "Say what you play in it."
            else -> null
        }
        if (_error.value != null) return
        adding = viewModelScope.launch {
            try {
                val ensemble = Ensemble(
                    name = name.trim(),
                    instrument = instrument.trim(),
                    folderId = folder?.id,
                    resourceKey = folder?.resourceKey,
                )
                onAdded(dao.add(ensemble))
            } catch (_: SQLiteConstraintException) {
                _error.value = "That folder is already an ensemble."
            }
        }
    }
}

sealed interface EnsembleView {
    data class Folder(val rows: List<EnsembleRow>) : EnsembleView
    data class Found(val files: List<EnsembleFile>) : EnsembleView

    /** My parts, wherever they are in the folder. */
    data class Mine(val files: List<EnsembleFile>) : EnsembleView
}

private const val PATH = "path"
private const val QUERY = "query"
private const val ALL = "all"

/**
 * One ensemble: its files as on the tablet, browsed by folder or searched, and what is new since I last looked.
 * The sync itself is the [Syncer]'s; this screen asks for it while it shows.
 */
class EnsembleViewModel(app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app) {
    private val id = handle.toRoute<EnsembleFiles>().id
    private val db = VoltiDatabase.get(app)
    private val syncer = Syncer.get(app)

    val ensemble: StateFlow<Ensemble?> =
        db.ensembles().observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Everything in the ensemble, as on the tablet. */
    val files: StateFlow<List<EnsembleFile>?> =
        db.files().shown(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sync: StateFlow<SyncState> = syncer.states.map { it[id] ?: SyncState.Idle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncState.Idle)

    val consent: StateFlow<PendingIntent?> = syncer.consent

    private val _since = MutableStateFlow(Long.MAX_VALUE)

    /**
     * Files added after this are new. When I last left the ensemble, as it was when this visit began: it stays that
     * for the whole visit, reading included, so what was new stays marked until I leave.
     */
    val since: StateFlow<Long> = _since.asStateFlow()

    init {
        viewModelScope.launch { _since.value = db.ensembles().get(id)?.lookedAt ?: Long.MIN_VALUE }
    }

    /** The folder I'm in, from the ensemble's own folder down. Kept across process death, like the library's. */
    val path: StateFlow<List<String>> = handle.getStateFlow(PATH, emptyList<String>())

    val query: StateFlow<String> = handle.getStateFlow(QUERY, "")

    /** Everything, not just my parts. Only matters once I have marked some. */
    val showAll: StateFlow<Boolean> = handle.getStateFlow(ALL, false)

    /**
     * What the screen lists: while searching, what the search found in the whole ensemble; else my parts, once I have
     * some, unless I asked for everything; else the folder I'm in.
     */
    val rows: StateFlow<EnsembleView?> = combine(files, path, query, showAll) { files, path, query, all ->
        files?.let {
            val existing = existingPath(it, path)
            if (existing != path) handle[PATH] = ArrayList(existing) // The folder is gone: up to what's left.
            val mine = it.filter { file -> file.mine }
            when {
                query.isNotBlank() -> EnsembleView.Found(searchEnsemble(it, query))
                mine.isNotEmpty() && !all -> EnsembleView.Mine(sortedByPlace(mine))
                else -> EnsembleView.Folder(ensembleRows(it, existing))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Files that look like my part and that I haven't decided on: matched against what I play in the ensemble. */
    val suggestions: StateFlow<List<EnsembleFile>> = combine(files, ensemble) { files, ensemble ->
        if (files == null || ensemble == null) return@combine emptyList()
        val me = parseInstrument(ensemble.instrument)
        sortedByPlace(files.filter { !it.mine && !it.decided && !it.removed && looksLikeMyPart(me, it.name, it.folders) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun showAll(all: Boolean) {
        handle[ALL] = all
    }

    /** Marked mine: downloaded now. Unmarked and never opened: gone from the device at the next sync. */
    fun setMine(file: EnsembleFile, mine: Boolean) {
        viewModelScope.launch {
            db.files().setMine(id, listOf(file.fileId), mine)
            if (mine) syncer.fetchMine(id, file.fileId)
        }
    }

    /** The suggestions I went through: [chosen] are mine, the rest are not, and none is suggested again. */
    fun decide(reviewed: List<EnsembleFile>, chosen: Set<String>) {
        viewModelScope.launch {
            val (yes, no) = reviewed.map { it.fileId }.partition { it in chosen }
            if (yes.isNotEmpty()) db.files().setMine(id, yes, true)
            if (no.isNotEmpty()) db.files().setMine(id, no, false)
            yes.forEach { syncer.fetchMine(id, it) }
        }
    }

    fun open(folder: String) {
        handle[PATH] = ArrayList(path.value + folder)
    }

    fun up() {
        handle[PATH] = ArrayList(path.value.dropLast(1))
    }

    fun setQuery(query: String) {
        handle[QUERY] = query
    }

    private val _fetching = MutableStateFlow<String?>(null)

    /** The file being downloaded to open it. */
    val fetching: StateFlow<String?> = _fetching.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** Why the last file I tapped didn't open. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * Opens the newest version on the device, once it is recorded as the one I saw: from then on it is kept, also
     * while a later sync brings a newer one. One that isn't here is downloaded first. The sync has stopped by the
     * time the reader shows: none runs while reading.
     */
    fun open(file: EnsembleFile, onOpen: (uri: String, scoreKey: String) -> Unit) {
        if (_fetching.value != null) return // One at a time; a second tap waits.
        _message.value = null
        viewModelScope.launch {
            // Through the store's lock also when it is here, so a sync can't delete it between the tap and the reader.
            if (file.localMd5 == null) _fetching.value = file.fileId
            val problem = try {
                syncer.fetch(id, file.fileId, open = true)
            } finally {
                _fetching.value = null
            }
            val here = db.files().all(id).firstOrNull { it.fileId == file.fileId }?.seenMd5
            when {
                problem != null -> _message.value = problem
                here != null -> onOpen(Uri.fromFile(syncer.fileFor(here)).toString(), file.scoreKey)
            }
        }
    }

    /** PDFs picked to import into [folders], waiting for me to say whether [replacing] are new versions. */
    class PendingImport(val uris: List<Uri>, val names: List<String>, val folders: List<String>, val replacing: List<String>)

    private val _pending = MutableStateFlow<PendingImport?>(null)
    val pending: StateFlow<PendingImport?> = _pending.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** PDFs picked to import into [folders]; when some have the name of an imported file there, I'm asked first. */
    fun import(uris: List<Uri>, folders: List<String>) {
        if (uris.isEmpty() || _importing.value) return
        _message.value = null
        _notImported.value = emptyList()
        viewModelScope.launch {
            val names = withContext(Dispatchers.IO) { uris.map(::nameOf) }
            val replacing = syncer.alreadyImported(id, folders, names)
            val picked = PendingImport(uris, names, folders, replacing)
            if (replacing.isEmpty()) copy(picked, replace = false) else _pending.value = picked
        }
    }

    /** [replace]: the ones with a name already here are new versions; else they come in next to them. */
    fun confirmImport(replace: Boolean) {
        val picked = _pending.value ?: return
        _pending.value = null
        viewModelScope.launch { copy(picked, replace) }
    }

    fun cancelImport() {
        _pending.value = null
    }

    private val _notImported = MutableStateFlow<List<Pair<String, Rejection>>>(emptyList())

    /** What the last import couldn't take in, and why. */
    val notImported: StateFlow<List<Pair<String, Rejection>>> = _notImported.asStateFlow()

    private suspend fun copy(picked: PendingImport, replace: Boolean) {
        _importing.value = true
        _notImported.value = emptyList()
        val resolver = getApplication<Application>().contentResolver
        val sources = picked.names.zip(picked.uris) { name, uri ->
            name to { resolver.openInputStream(uri) ?: throw IOException("Can't read a picked PDF") }
        }
        // The import goes on when I leave the screen; only what it says about it is lost then.
        _notImported.value = try {
            syncer.import(id, picked.folders, sources, replace).await()
        } finally {
            _importing.value = false
        }
    }

    /** The name the other app gives it; the end of its address if it gives none, or can't say. */
    private fun nameOf(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        // Any app's provider answers this, and may throw instead.
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return name ?: uri.lastPathSegment ?: "Imported.pdf"
    }

    /** An imported file goes, from its setlists too, with its marks. */
    fun delete(file: EnsembleFile) {
        viewModelScope.launch { syncer.delete(file) }
    }

    /** The screen shows: sync, unless it did a minute ago. [force]: pulled, sync anyway. */
    fun sync(force: Boolean = false) = syncer.sync(listOf(id), owner = this, force = force)

    /** The screen went away: no sync without it, and what is here now has been looked at. */
    fun left() {
        syncer.stop(owner = this)
        syncer.looked(id, System.currentTimeMillis())
    }

    fun consented(data: Intent?) = syncer.consented(data)

}
