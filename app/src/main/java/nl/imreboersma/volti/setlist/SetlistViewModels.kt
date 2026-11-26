package nl.imreboersma.volti.setlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.imreboersma.volti.Setlists
import nl.imreboersma.volti.SetlistPieces
import nl.imreboersma.volti.data.sortedByPlace
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.EnsembleFile
import nl.imreboersma.volti.db.VoltiDatabase

/** An ensemble's setlists, and making a new one. */
class SetlistsViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val ensembleId = handle.toRoute<Setlists>().ensembleId
    private val db = VoltiDatabase.get(app)

    val ensemble: StateFlow<Ensemble?> =
        db.ensembles().observe(ensembleId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val setlists: StateFlow<List<Setlist>?> =
        db.setlists().forEnsemble(ensembleId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun create(name: String, onCreated: (Long) -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch {
            onCreated(db.setlists().add(Setlist(ensembleId = ensembleId, name = name.trim(), createdAt = System.currentTimeMillis())))
        }
    }
}

/** One setlist: its pieces in order, adding, removing, reordering, renaming. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetlistViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val setlistId = handle.toRoute<SetlistPieces>().setlistId
    private val dao = VoltiDatabase.get(app).setlists()
    private val files = VoltiDatabase.get(app).files()

    val setlist: StateFlow<Setlist?> = dao.observe(setlistId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pieces: StateFlow<List<SetlistPiece>?> = dao.pieces(setlistId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** What can be added: the ensemble's files not in the setlist yet, by place; my parts are marked as such. */
    val candidates: StateFlow<List<EnsembleFile>> = setlist.filterNotNull()
        .flatMapLatest { files.shown(it.ensembleId) }
        .combine(pieces) { all, pieces ->
            val inList = pieces.orEmpty().mapTo(HashSet()) { it.fileId }
            sortedByPlace(all.filter { it.fileId !in inList && !it.removed })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** At the end, in the order given. */
    fun add(fileIds: List<String>) {
        viewModelScope.launch { dao.addPieces(setlistId, fileIds) }
    }

    fun remove(fileId: String) {
        viewModelScope.launch { dao.removePiece(setlistId, fileId) }
    }

    /** The pieces in this order, as dragged into it. */
    fun order(fileIds: List<String>) {
        viewModelScope.launch { dao.reorder(setlistId, fileIds) }
    }

    fun rename(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { dao.rename(setlistId, name.trim()) }
    }

    private var deleted = false

    /** Once: a second tap, or one during the crossfade out, must not go back a second screen. */
    fun delete(onDeleted: () -> Unit) {
        if (deleted) return
        deleted = true
        viewModelScope.launch {
            dao.remove(setlistId)
            onDeleted()
        }
    }
}
