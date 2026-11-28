package nl.imreboersma.volti.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.imreboersma.volti.R
import nl.imreboersma.volti.data.PartIn
import nl.imreboersma.volti.data.myParts
import nl.imreboersma.volti.data.searchEverywhere
import nl.imreboersma.volti.db.EnsembleFile
import nl.imreboersma.volti.db.VoltiDatabase
import nl.imreboersma.volti.drive.DriveProblem
import nl.imreboersma.volti.drive.SyncState
import nl.imreboersma.volti.drive.Syncer

private const val QUERY = "query"

/** My parts from every ensemble, and a search through all of them. */
class PartsViewModel(app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app) {
    private val db = VoltiDatabase.get(app)
    private val syncer = Syncer.get(app)

    val query: StateFlow<String> = handle.getStateFlow(QUERY, "")

    /**
     * By ensemble and file id, whether a part I ticked or unticked on this visit was mine before: it keeps its place
     * until I leave, so a slip of the finger is undone where it happened.
     */
    private val asShown = MutableStateFlow<Map<Pair<Long, String>, Boolean>>(emptyMap())

    /** While searching, what it found in every ensemble; else my parts. Null while loading. */
    val rows: StateFlow<List<PartIn>?> = combine(db.ensembles().all(), db.files().everywhere(), query, asShown) { ensembles, files, query, shown ->
        val names = ensembles.associate { it.id to it.name }
        val parts = files.mapNotNull { file -> names[file.ensembleId]?.let { PartIn(file, it) } }
        val mine = { file: EnsembleFile -> shown[file.ensembleId to file.fileId] ?: file.mine }
        if (query.isNotBlank()) searchEverywhere(parts, query, mine) else myParts(parts, mine)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val syncStates: StateFlow<Map<Long, SyncState>> = syncer.states

    /** Google wants me to pick an account or agree first, to download a part I tapped. */
    val consent: StateFlow<PendingIntent?> = syncer.consent

    /** The files downloading right now, whatever for. */
    val downloading: StateFlow<Set<String>> = syncer.downloading

    private val _problem = MutableStateFlow<DriveProblem?>(null)

    /** Why the last part I tapped didn't open. */
    val problem: StateFlow<DriveProblem?> = _problem.asStateFlow()

    private val opener = PartOpener(viewModelScope, db, syncer) { _problem.value = it }

    fun setQuery(query: String) {
        handle[QUERY] = query
    }

    fun open(file: EnsembleFile, onOpen: (uri: String, scoreKey: String) -> Unit) = opener.open(file, onOpen)

    /** Marked mine: downloaded now. Unmarked: off my parts, and off the device at the next sync if I never opened it. */
    fun setMine(file: EnsembleFile, mine: Boolean) {
        asShown.update { it + ((file.ensembleId to file.fileId) to (it[file.ensembleId to file.fileId] ?: file.mine)) }
        viewModelScope.launch {
            db.files().setMine(file.ensembleId, listOf(file.fileId), mine)
            if (mine) syncer.fetchMine(file.ensembleId, file.fileId)
        }
    }

    fun rename(file: EnsembleFile, name: String?) {
        viewModelScope.launch { db.files().rename(file.fileId, name) }
    }

    fun delete(file: EnsembleFile) {
        viewModelScope.launch { syncer.delete(file) }
    }

    /**
     * The screen went away: a download to open a part stops, and the parts I ticked or unticked take their places.
     * No sync of its own: that is the library's and an ensemble's (plan.md, Battery).
     */
    fun left() {
        opener.stop()
        asShown.value = emptyMap()
    }

    fun consented(data: Intent?) = syncer.consented(data)
}

/**
 * My parts from every ensemble, by name, each with its ensemble; the search above them goes through every part in
 * every ensemble. [search]: opened to search, so the keyboard is up at once.
 */
@Composable
fun PartsScreen(
    search: Boolean,
    onOpenScore: (uri: String, scoreKey: String) -> Unit,
    onBack: () -> Unit,
    viewModel: PartsViewModel = viewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val syncStates by viewModel.syncStates.collectAsStateWithLifecycle()
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val res = LocalResources.current
    var deleting by remember { mutableStateOf<EnsembleFile?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) } // Ensemble id and file id.
    val needsConsent by viewModel.consent.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.consented(it.data)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.left() }

    // The keyboard only the first time: back from a part, the results are what I want to see.
    val focus = remember { FocusRequester() }
    var focused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (search && !focused) focus.requestFocus()
        focused = true
    }

    // Back clears the search first, as in an ensemble. Only while this is the active screen.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    BackHandler(enabled = active && query.isNotEmpty()) { viewModel.setQuery("") }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(
                    onBack = {
                        when {
                            !active -> Unit
                            query.isNotEmpty() -> viewModel.setQuery("")
                            else -> onBack()
                        }
                    },
                    description = stringResource(if (query.isNotEmpty()) R.string.clear_the_search else R.string.back_to_library),
                )
                Text(stringResource(R.string.your_parts), style = MaterialTheme.typography.headlineLarge)
            }
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text(stringResource(R.string.search_every_ensemble)) },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) TextButton(onClick = { viewModel.setQuery("") }) { Text(stringResource(R.string.clear)) }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusRequester(focus),
            )
            problem?.let { Text(fileProblemText(res, it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
            // A part that isn't here needs Google Drive: the way to sign in, where it was asked for.
            needsConsent?.let { intent ->
                Button(onClick = { consent.launch(IntentSenderRequest.Builder(intent).build()) }, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(stringResource(R.string.sign_in_to_drive))
                }
            }
            renaming?.let { key ->
                // The file as it is now; once the list is in (back after the process ended, it is still loading).
                rows?.let { list ->
                    val file = list.firstOrNull { "${it.file.ensembleId}:${it.file.fileId}" == key }?.file
                    if (file == null) {
                        LaunchedEffect(key) { renaming = null } // Gone meanwhile, or no longer found.
                    } else {
                        RenameFile(file, onSave = { name ->
                            viewModel.rename(file, name)
                            renaming = null
                        }, onCancel = { renaming = null })
                    }
                }
            }
            deleting?.let { file ->
                DeleteImported(file, onDelete = {
                    viewModel.delete(file)
                    deleting = null
                }, onCancel = { deleting = null })
            }
            val list = rows ?: return@Column
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (query.isNotBlank()) stringResource(R.string.nothing_found, query) else stringResource(R.string.no_parts_yours))
                }
                return@Column
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // The same part can be in two ensembles.
                items(list, key = { "${it.file.ensembleId}:${it.file.fileId}" }) { part ->
                    val file = part.file
                    FileRow(
                        file,
                        new = false,
                        folders = (listOf(part.ensemble) + file.folders).joinToString(" / "),
                        download = downloadOf(file.fileId, syncStates[file.ensembleId] ?: SyncState.Idle, downloading),
                        onOpen = { viewModel.open(it, onOpenScore) },
                        onMine = { viewModel.setMine(file, it) },
                        onRename = { renaming = "${file.ensembleId}:${file.fileId}" },
                        onDelete = { deleting = file },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
