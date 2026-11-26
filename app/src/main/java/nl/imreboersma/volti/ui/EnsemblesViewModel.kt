package nl.imreboersma.volti.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.imreboersma.volti.EnsembleFiles
import nl.imreboersma.volti.data.NaturalOrder
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.VoltiDatabase
import nl.imreboersma.volti.drive.DriveAccess
import nl.imreboersma.volti.drive.DriveApi
import nl.imreboersma.volti.drive.DriveAuth
import nl.imreboersma.volti.drive.DriveException
import nl.imreboersma.volti.drive.DriveFolderLink
import nl.imreboersma.volti.drive.FOLDER_TYPE
import nl.imreboersma.volti.drive.parseFolderLink
import nl.imreboersma.volti.drive.walkDriveFolder
import java.io.IOException
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.imreboersma.volti.db.EnsembleFile
import nl.imreboersma.volti.drive.PdfStore
import nl.imreboersma.volti.drive.reconcile
import java.io.File

class EnsemblesViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = VoltiDatabase.get(app).ensembles()

    val ensembles: StateFlow<List<Ensemble>?> =
        dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var adding: Job? = null

    /** Adds the ensemble and calls [onAdded] with its id, or sets [error] and leaves the form as it is. */
    fun add(link: String, name: String, instrument: String, onAdded: (Long) -> Unit) {
        if (adding?.isActive == true) return // A second tap on Add.
        val folder = parseFolderLink(link)
        _error.value = when {
            folder == null -> "That isn't a link to a Drive folder."
            name.isBlank() -> "Give the ensemble a name."
            instrument.isBlank() -> "Say what you play in it."
            else -> null
        }
        if (folder == null || _error.value != null) return
        adding = viewModelScope.launch {
            try {
                val ensemble = Ensemble(
                    name = name.trim(),
                    instrument = instrument.trim(),
                    folderId = folder.id,
                    resourceKey = folder.resourceKey,
                )
                onAdded(dao.add(ensemble))
            } catch (_: SQLiteConstraintException) {
                _error.value = "That folder is already an ensemble."
            }
        }
    }
}

sealed interface SyncState {
    data object Idle : SyncState
    data object Listing : SyncState
    data class Downloading(val done: Int, val total: Int) : SyncState
    data class NeedsConsent(val intent: PendingIntent) : SyncState

    /** [stopped]: the folder was too big to list completely. [failed]: downloads that will be tried next time. */
    data class Done(val stopped: Boolean, val failed: Int) : SyncState
    data class Failed(val message: String) : SyncState
}

/** One sync, or clean-up, at a time across the app: they share the store, and clean-up deletes what isn't in use. */
private val storeLock = Mutex()

/**
 * One ensemble: its files as on the tablet, shown straight away, and a sync with Drive behind them. Milestone 3,
 * step 2. The sync lists the folder, updates the rows (see [reconcile]), downloads what is new or changed, and
 * deletes versions nothing needs any more.
 */
class EnsembleViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val id = handle.toRoute<EnsembleFiles>().id
    private val db = VoltiDatabase.get(app)
    private val auth = DriveAuth(app)
    private val store = PdfStore(File(app.filesDir, "drive"))

    private val _ensemble = MutableStateFlow<Ensemble?>(null)
    val ensemble: StateFlow<Ensemble?> = _ensemble.asStateFlow()

    /** By folder, then name, in the library's natural order. */
    val files: StateFlow<List<EnsembleFile>?> = db.files().shown(id)
        .map { files ->
            val order = NaturalOrder()
            files.sortedWith(
                compareBy<EnsembleFile, String>(order) { it.folders.joinToString("\u0000") }.thenBy(order) { it.name },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    private var syncing: Job? = null

    /**
     * Opens the newest version on the tablet, once it is recorded as the one I saw: from then on it is kept, also
     * while a later sync brings a newer one. The sync has stopped by then: none runs while the reader is open.
     */
    fun open(file: EnsembleFile, onOpen: (String) -> Unit) {
        val md5 = file.localMd5 ?: return
        viewModelScope.launch {
            db.files().opened(id, file.fileId, md5)
            onOpen(Uri.fromFile(store.fileFor(md5)).toString())
        }
    }

    /** The screen went away (to the reader, or out of the app): no sync without it. [load] picks up again. */
    fun pause() {
        if (syncing?.isActive != true) return
        syncing?.cancel()
        _sync.value = SyncState.Idle
    }

    /** Syncs when the screen shows, unless a sync is already running. */
    fun load() {
        if (syncing?.isActive == true) return
        syncing = viewModelScope.launch {
            val ensemble = db.ensembles().get(id) ?: return@launch
            _ensemble.value = ensemble
            _sync.value = guarded {
                when (val access = auth.authorize()) {
                    is DriveAccess.NeedsConsent -> SyncState.NeedsConsent(access.intent)
                    is DriveAccess.Granted -> sync(ensemble, access.token, retry = true)
                }
            }
        }
    }

    /** Back from Google's consent screen. */
    fun consented(data: Intent?) {
        val ensemble = _ensemble.value ?: return
        val token = auth.tokenFrom(data)
        if (token == null) {
            _sync.value = SyncState.Failed("Volti needs access to Google Drive to sync this folder.")
            return
        }
        if (syncing?.isActive == true) return
        syncing = viewModelScope.launch { _sync.value = guarded { sync(ensemble, token, retry = true) } }
    }

    private var removed = false

    /** Once: a second tap, or one during the crossfade out, must not go back a second screen. */
    fun remove(onRemoved: () -> Unit) {
        val ensemble = _ensemble.value ?: return
        if (removed) return
        removed = true
        syncing?.cancel()
        viewModelScope.launch {
            storeLock.withLock {
                db.ensembles().remove(ensemble) // Its files go with it.
                cleanUp()
            }
            onRemoved()
        }
    }

    /** Anything unexpected from Play services or Drive's answers ends as a message, not a crash. */
    private suspend fun guarded(block: suspend () -> SyncState): SyncState = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("Volti", "Syncing the ensemble failed", e)
        SyncState.Failed("Couldn't reach Google Drive.")
    }

    private suspend fun cleanUp() = withContext(Dispatchers.IO) { store.keepOnly(db.files().versionsInUse().toSet()) }

    private suspend fun sync(ensemble: Ensemble, token: String, retry: Boolean): SyncState {
        val folder = DriveFolderLink(ensemble.folderId, ensemble.resourceKey)
        val api = DriveApi(token)
        return try {
            _sync.value = SyncState.Listing
            if (api.folderInfo(folder).mimeType != FOLDER_TYPE) return SyncState.Failed("The link is to a file, not a folder.")
            val listing = walkDriveFolder(folder) { f, page -> api.listPage(f, page) }
            storeLock.withLock {
                val files = db.files()
                files.replace(id) { old -> reconcile(id, old, listing.pdfs, complete = !listing.stopped) }
                val todo = files.all(id).filter { !it.removed && it.localMd5 != it.md5 }
                var failed = 0
                todo.forEachIndexed { i, file ->
                    _sync.value = SyncState.Downloading(i, todo.size)
                    try {
                        if (!withContext(Dispatchers.IO) { store.has(file.md5) }) {
                            api.download(DriveFolderLink(file.fileId, file.resourceKey)) { store.store(file.md5, file.size, it) }
                        }
                        files.downloaded(id, file.fileId, file.md5)
                    } catch (e: DriveException) {
                        if (e.status == 401) throw e // The token ran out halfway: start over with a new one.
                        failed++
                    } catch (e: IOException) {
                        Log.w("Volti", "A download failed: ${e.message}") // Not the name: it may be private.
                        failed++
                    }
                }
                cleanUp()
                SyncState.Done(listing.stopped, failed)
            }
        } catch (e: DriveException) {
            when {
                e.status == 401 && retry -> {
                    // The token ran out: drop it and ask Play services for a fresh one, once.
                    auth.forget(token)
                    when (val access = auth.authorize()) {
                        is DriveAccess.NeedsConsent -> SyncState.NeedsConsent(access.intent)
                        is DriveAccess.Granted -> sync(ensemble, access.token, retry = false)
                    }
                }
                e.status == 403 || e.status == 404 ->
                    SyncState.Failed("This Google account can't see that folder. Is it shared with you?")
                else -> SyncState.Failed("Google Drive answered with an error (${e.status}). Try again later.")
            }
        } catch (e: IOException) {
            Log.w("Volti", "Syncing an ensemble failed", e) // Not the folder id: for a link-shared folder, that is the link.
            SyncState.Failed("No connection to Google Drive. What was synced before is still here.")
        }
    }
}
