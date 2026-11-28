package nl.imreboersma.volti.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.Job
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
import nl.imreboersma.volti.drive.Received
import nl.imreboersma.volti.drive.DriveProblem
import nl.imreboersma.volti.drive.ImportProgress
import nl.imreboersma.volti.data.pdfsIn
import nl.imreboersma.volti.data.WALK_LIMIT
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import android.net.Uri
import nl.imreboersma.volti.db.EnsembleFile

/** What is wrong with the form to add an ensemble: one at a time, worded by the screen. */
enum class AddProblem { NotALink, NoName, NoInstrument, AlreadyAdded }

class EnsemblesViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = VoltiDatabase.get(app).ensembles()

    private val _error = MutableStateFlow<AddProblem?>(null)
    val error: StateFlow<AddProblem?> = _error.asStateFlow()

    private var adding: Job? = null

    /**
     * Adds the ensemble and calls [onAdded] with its id, or sets [error] and leaves the form as it is. Without a
     * [link], its files are only what I import.
     */
    fun add(link: String, name: String, instrument: String, onAdded: (Long) -> Unit) {
        if (adding?.isActive == true) return // A second tap on Add.
        val folder = link.takeIf { it.isNotBlank() }?.let(::parseFolderLink)
        _error.value = when {
            link.isNotBlank() && folder == null -> AddProblem.NotALink
            name.isBlank() -> AddProblem.NoName
            instrument.isBlank() -> AddProblem.NoInstrument
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
                _error.value = AddProblem.AlreadyAdded
            }
        }
    }
}

/** What the ensemble's screen says above its list, worded there. */
sealed interface EnsembleMessage {
    /** The file I tapped couldn't be opened. */
    data class Open(val problem: DriveProblem) : EnsembleMessage

    /** The folder picked to import can't be read. */
    data object FolderUnreadable : EnsembleMessage

    /** The folder picked to import holds more than [WALK_LIMIT] files and folders. */
    data object FolderTooLarge : EnsembleMessage

    /** The folder picked to import holds no PDFs. */
    data object NoPdfs : EnsembleMessage
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
private const val TO_PLACE = "toPlace"

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

    /** The files downloading right now, whatever for: the sync, to open one, or because I marked it mine. */
    val downloading: StateFlow<Set<String>> = syncer.downloading

    private val _message = MutableStateFlow<EnsembleMessage?>(null)

    /** Why the last file I tapped didn't open, or what is wrong with the folder I picked to import. */
    val message: StateFlow<EnsembleMessage?> = _message.asStateFlow()

    private val opener = PartOpener(viewModelScope, db, syncer) { problem -> _message.value = problem?.let(EnsembleMessage::Open) }

    /** See [PartOpener.open]. */
    fun open(file: EnsembleFile, onOpen: (uri: String, scoreKey: String) -> Unit) = opener.open(file, onOpen)

    /**
     * PDFs another app shared, copied in already: the batch, to import into the folder I choose here. Kept until the
     * import starts or I let it go, also across process death.
     */
    private val toPlace: StateFlow<String?> = handle.getStateFlow(TO_PLACE, handle.toRoute<EnsembleFiles>().importing)

    /** What the shared batch holds, while it waits for me. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val placing: StateFlow<Received?> = toPlace.mapLatest { it?.let { batch -> syncer.received(batch) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The shared PDFs into [folders]. */
    fun place(folders: List<String>) {
        val received = placing.value ?: return
        viewModelScope.launch { ask(received, folders, shared = true) }
    }

    fun dontPlace() {
        placing.value?.let(syncer::discard)
        handle[TO_PLACE] = null
    }

    /** Copied-in PDFs for [into], waiting for me to say whether [replacing] are new versions. [shared]: the shared batch. */
    class PendingImport(val received: Received, val into: List<String>, val replacing: List<String>, val shared: Boolean)

    private val _pending = MutableStateFlow<PendingImport?>(null)
    val pending: StateFlow<PendingImport?> = _pending.asStateFlow()

    private val _reading = MutableStateFlow(false)

    /** A folder picked to import is being read, before its PDFs are copied in. */
    val reading: StateFlow<Boolean> = _reading.asStateFlow()

    /** How far an import into this ensemble is ("Copying 12 of 300…"), also one started before I left; null when none runs. */
    val importing: StateFlow<ImportProgress?> = syncer.importing.map { it[id] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _notImported = MutableStateFlow<List<Pair<String, Rejection>>>(emptyList())

    /** What the last import couldn't take in, and why. */
    val notImported: StateFlow<List<Pair<String, Rejection>>> = _notImported.asStateFlow()

    /** PDFs picked to import into [folders]: copied in at once, while Volti may read them. */
    fun import(uris: List<Uri>, folders: List<String>) {
        if (uris.isNotEmpty()) receiveThenAsk(folders) { syncer.receive(uris, into = id).await() }
    }

    /**
     * Every PDF in the folder [tree] and its subfolders, into [folders]: the folder itself and its subfolders become
     * folders here.
     */
    fun importFolder(tree: Uri, folders: List<String>) {
        receiveThenAsk(folders) {
            val resolver = getApplication<Application>().contentResolver
            _reading.value = true
            val found = try {
                withContext(Dispatchers.IO) { pdfsIn(resolver, tree) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Volti", "Can't read a folder to import", e)
                _message.value = EnsembleMessage.FolderUnreadable
                return@receiveThenAsk null
            } finally {
                _reading.value = false
            }
            _message.value = when {
                !found.complete -> EnsembleMessage.FolderTooLarge
                found.uris.isEmpty() -> EnsembleMessage.NoPdfs
                else -> null
            }
            syncer.receive(found.uris, found.folders, into = id).await()
        }
    }

    private fun receiveThenAsk(into: List<String>, receive: suspend () -> Received?) {
        _message.value = null
        _notImported.value = emptyList()
        viewModelScope.launch {
            val received = receive() ?: return@launch
            _notImported.value = received.problems
            if (received.incoming.isNotEmpty()) ask(received, into, shared = false)
        }
    }

    /** When some have the name of an imported file where they go, I'm asked first; else they go in. */
    private suspend fun ask(received: Received, into: List<String>, shared: Boolean) {
        val replacing = syncer.alreadyImported(id, into, received)
        if (replacing.isEmpty()) take(received, into, replace = false, shared) else _pending.value = PendingImport(received, into, replacing, shared)
    }

    /** [replace]: the ones with a name already here are new versions; else they come in next to them. */
    fun confirmImport(replace: Boolean) {
        val pending = _pending.value ?: return
        _pending.value = null
        viewModelScope.launch { take(pending.received, pending.into, replace, pending.shared) }
    }

    fun cancelImport() {
        val pending = _pending.value ?: return
        _pending.value = null
        // A share stays offered, to put elsewhere; picked files were mine to pick again.
        if (!pending.shared) syncer.discard(pending.received)
    }

    private suspend fun take(received: Received, into: List<String>, replace: Boolean, shared: Boolean) {
        if (shared) handle[TO_PLACE] = null
        // The import goes on when I leave the screen; only what it says about it is lost then.
        val problems = syncer.import(id, into, received, replace).await()
        _notImported.value += problems
    }

    /** My name for [file]; null: its file name again. */
    fun rename(file: EnsembleFile, name: String?) {
        viewModelScope.launch { db.files().rename(file.fileId, name) }
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
        opener.stop()
        syncer.looked(id, System.currentTimeMillis())
    }

    fun consented(data: Intent?) = syncer.consented(data)

}
