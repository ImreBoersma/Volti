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
import nl.imreboersma.volti.drive.DriveEntry
import nl.imreboersma.volti.drive.DriveException
import nl.imreboersma.volti.drive.DriveFolderLink
import nl.imreboersma.volti.drive.FOLDER_TYPE
import nl.imreboersma.volti.drive.parseFolderLink
import nl.imreboersma.volti.drive.walkDriveFolder
import java.io.IOException

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

sealed interface EnsembleState {
    data object Loading : EnsembleState
    data class NeedsConsent(val intent: PendingIntent) : EnsembleState
    data class Listed(val folderName: String, val pdfs: List<DriveEntry>, val folders: Int, val stopped: Boolean) :
        EnsembleState

    data class Failed(val message: String) : EnsembleState
}

/** One ensemble, listed live from Drive. Milestone 3, step 1: no local copy yet. */
class EnsembleViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val id = handle.toRoute<EnsembleFiles>().id
    private val dao = VoltiDatabase.get(app).ensembles()
    private val auth = DriveAuth(app)

    private val _ensemble = MutableStateFlow<Ensemble?>(null)
    val ensemble: StateFlow<Ensemble?> = _ensemble.asStateFlow()

    private val _state = MutableStateFlow<EnsembleState>(EnsembleState.Loading)
    val state: StateFlow<EnsembleState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = EnsembleState.Loading
        viewModelScope.launch {
            val ensemble = dao.get(id) ?: return@launch
            _ensemble.value = ensemble
            _state.value = guarded {
                when (val access = auth.authorize()) {
                    is DriveAccess.NeedsConsent -> EnsembleState.NeedsConsent(access.intent)
                    is DriveAccess.Granted -> list(ensemble, access.token, retry = true)
                }
            }
        }
    }

    /** Anything unexpected from Play services or Drive's answers ends as a message, not a crash. */
    private suspend fun guarded(block: suspend () -> EnsembleState): EnsembleState = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("Volti", "Listing the ensemble failed", e)
        EnsembleState.Failed("Couldn't reach Google Drive.")
    }

    /** Back from Google's consent screen. */
    fun consented(data: Intent?) {
        val ensemble = _ensemble.value ?: return
        val token = auth.tokenFrom(data)
        if (token == null) {
            _state.value = EnsembleState.Failed("Volti needs access to Google Drive to list this folder.")
            return
        }
        _state.value = EnsembleState.Loading
        viewModelScope.launch { _state.value = guarded { list(ensemble, token, retry = true) } }
    }

    private var removed = false

    /** Once: a second tap, or one during the crossfade out, must not go back a second screen. */
    fun remove(onRemoved: () -> Unit) {
        val ensemble = _ensemble.value ?: return
        if (removed) return
        removed = true
        viewModelScope.launch {
            dao.remove(ensemble)
            onRemoved()
        }
    }

    private suspend fun list(ensemble: Ensemble, token: String, retry: Boolean): EnsembleState {
        val folder = DriveFolderLink(ensemble.folderId, ensemble.resourceKey)
        val api = DriveApi(token)
        return try {
            val info = api.folderInfo(folder)
            if (info.mimeType != FOLDER_TYPE) return EnsembleState.Failed("The link is to a file, not a folder.")
            val listing = walkDriveFolder(folder) { f, page -> api.listPage(f, page) }
            val order = NaturalOrder()
            val pdfs = listing.pdfs.sortedWith(
                compareBy<DriveEntry, String>(order) { it.folders.joinToString("\u0000") }.thenBy(order) { it.file.name },
            )
            EnsembleState.Listed(info.name, pdfs, listing.folders, listing.stopped)
        } catch (e: DriveException) {
            when {
                e.status == 401 && retry -> {
                    // The token ran out: drop it and ask Play services for a fresh one, once.
                    auth.forget(token)
                    when (val access = auth.authorize()) {
                        is DriveAccess.NeedsConsent -> EnsembleState.NeedsConsent(access.intent)
                        is DriveAccess.Granted -> list(ensemble, access.token, retry = false)
                    }
                }
                e.status == 403 || e.status == 404 ->
                    EnsembleState.Failed("This Google account can't see that folder. Is it shared with you?")
                else -> EnsembleState.Failed("Google Drive answered with an error (${e.status}). Try again later.")
            }
        } catch (e: IOException) {
            Log.w("Volti", "Listing an ensemble failed", e) // Not the folder id: for a link-shared folder, that is the link.
            EnsembleState.Failed("No connection to Google Drive.")
        }
    }
}
