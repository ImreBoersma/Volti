package nl.imreboersma.volti.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.imreboersma.volti.drive.SyncState
import nl.imreboersma.volti.drive.Syncer
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.imreboersma.volti.R
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.imreboersma.volti.data.FolderStore
import nl.imreboersma.volti.data.folderName
import nl.imreboersma.volti.data.pdfsIn
import nl.imreboersma.volti.drive.ImportProgress
import nl.imreboersma.volti.drive.ImportStep
import nl.imreboersma.volti.data.WALK_LIMIT
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.VoltiDatabase

/** The local folder as the home screen shows it. */
sealed interface LocalFolder {
    data object None : LocalFolder
    /** [name]: null when it can't be read. */
    data class Chosen(val name: String?) : LocalFolder
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val syncer = Syncer.get(app)
    private val dao = VoltiDatabase.get(app).ensembles()

    val ensembles: StateFlow<List<Ensemble>?> =
        dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Per ensemble id, how many files are new since I last looked. */
    val newFiles: StateFlow<Map<Long, Int>> = dao.newFiles().map { list -> list.associate { it.ensembleId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val syncStates: StateFlow<Map<Long, SyncState>> = syncer.states
    val consent: StateFlow<PendingIntent?> = syncer.consent

    /** The library shows: sync every ensemble, skipping those synced a minute ago unless [force]d (pulled). */
    fun syncAll(force: Boolean = false) {
        shown = true
        viewModelScope.launch {
            val ids = dao.all().first().map { it.id }
            if (shown) syncer.sync(ids, owner = this@HomeViewModel, force = force) // Not if I left meanwhile.
        }
    }

    private var shown = false

    fun left() {
        shown = false
        syncer.stop(owner = this)
    }

    fun consented(data: Intent?) = syncer.consented(data)

    private val folders = FolderStore(app)

    /**
     * The folder Volti read in place before milestone 6, by name, while it still holds it: offered once, to import.
     * [LocalFolder.None] once it is imported or let go.
     */
    val localFolder: StateFlow<LocalFolder?> = folders.folder
        .map { uri ->
            if (uri == null) {
                LocalFolder.None
            } else {
                // A folder that can't be read any more (deleted, card removed) still shows, by a plain name: the
                // import says what's wrong. Providers throw all sorts for that; none may stop the app.
                val name = try {
                    withContext(Dispatchers.IO) { folderName(app.contentResolver, uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Volti", "Can't read the folder's name", e)
                    null
                }
                LocalFolder.Chosen(name)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _copying = MutableStateFlow<ImportProgress?>(null)

    /** How far copying the old folder in is ("Copying 12 of 300…"; a total of 0 while still counting); null when it isn't. */
    val copying: StateFlow<ImportProgress?> = _copying.asStateFlow()

    private val _copied = MutableStateFlow<String?>(null)

    /**
     * The old folder, copied in as this batch: the screen goes on to pick its ensemble. A state, not a callback, so
     * it waits for the screen when I left it during the copy.
     */
    val copied: StateFlow<String?> = _copied.asStateFlow()

    private val _folderProblem = MutableStateFlow<OldFolderProblem?>(null)

    /** Why the old folder couldn't be copied in. The offer stays. */
    val folderProblem: StateFlow<OldFolderProblem?> = _folderProblem.asStateFlow()

    /**
     * Copies the old folder in, its subfolders as folders. It is let go only once its batch is imported: until then,
     * cancelled or failed, the offer stays.
     */
    fun importOldFolder() {
        if (_copying.value != null) return
        viewModelScope.launch {
            val tree = folders.folder.first() ?: return@launch
            _copying.value = ImportProgress(ImportStep.Copying, 0, 0)
            _folderProblem.value = null
            try {
                val found = withContext(Dispatchers.IO) { pdfsIn(getApplication<Application>().contentResolver, tree) }
                _folderProblem.value = when {
                    !found.complete -> OldFolderProblem.TooLarge
                    found.uris.isEmpty() -> OldFolderProblem.NoPdfs
                    else -> null
                }
                if (_folderProblem.value == null) _copied.value = syncer.receive(found.uris, found.folders, oldFolder = true) { done, total ->
                    _copying.value = ImportProgress(ImportStep.Copying, done, total)
                }.await().batch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Volti", "Can't read the old folder", e)
                _folderProblem.value = OldFolderProblem.Unreadable
            } finally {
                _copying.value = null
            }
        }
    }

    /** The screen went on with [copied]. */
    fun wentOn() {
        _copied.value = null
    }

    /** I don't want the old folder in Volti. */
    fun letGoOldFolder() {
        viewModelScope.launch { folders.forget() }
    }
}

/** Why the old folder couldn't be copied in: more than [WALK_LIMIT] entries, no PDFs, or it can't be read any more. */
enum class OldFolderProblem { TooLarge, NoPdfs, Unreadable }

/**
 * Where the app opens: my ensembles, then what is on the tablet itself; settings one tap away. Back here leaves the
 * app, so there is no back button.
 */
@Composable
fun HomeScreen(
    onOpenEnsemble: (Long) -> Unit,
    onAddEnsemble: () -> Unit,
    onImportOldFolder: (batch: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenParts: (search: Boolean) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val ensembles by viewModel.ensembles.collectAsStateWithLifecycle()
    val local by viewModel.localFolder.collectAsStateWithLifecycle()
    val copying by viewModel.copying.collectAsStateWithLifecycle()
    val copied by viewModel.copied.collectAsStateWithLifecycle()
    val folderProblem by viewModel.folderProblem.collectAsStateWithLifecycle()
    LaunchedEffect(copied) {
        copied?.let {
            onImportOldFolder(it)
            viewModel.wentOn()
        }
    }
    val newFiles by viewModel.newFiles.collectAsStateWithLifecycle()
    val syncStates by viewModel.syncStates.collectAsStateWithLifecycle()
    val needsConsent by viewModel.consent.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.consented(it.data)
    }
    // Tells Android when the home screen is really there: logged as "Fully drawn" at cold start.
    ReportDrawnWhen { ensembles != null && local != null }
    // Syncs every ensemble while the library shows, and only then.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.syncAll() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.left() }
    // Only while Google Drive is asked what changed: the downloads after that show on the ensembles, not here.
    val syncing = syncStates.values.any { it is SyncState.Listing }
    val res = LocalResources.current

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        PullToRefreshBox(
            isRefreshing = syncing,
            onRefresh = { viewModel.syncAll(force = true) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.library), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                    // Through every part in every ensemble: the search an ensemble has, for all of them.
                    // Only the icon: with two labelled buttons the title runs out of room on a phone, in Dutch, at large text.
                    IconButton(onClick = { onOpenParts(true) }, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Magnifier, contentDescription = stringResource(R.string.search))
                    }
                    OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings)) }
                }
            }
            item { Heading(stringResource(R.string.ensembles)) }
            needsConsent?.let { intent ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.drive_signed_out), modifier = Modifier.weight(1f, fill = false))
                        Button(onClick = { consent.launch(IntentSenderRequest.Builder(intent).build()) }) {
                            Text(stringResource(R.string.sign_in_to_drive))
                        }
                    }
                }
            }
            // My parts from every ensemble in one list, above the ensembles they come from.
            if (!ensembles.isNullOrEmpty()) {
                item(key = "parts") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenParts(false) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(stringResource(R.string.your_parts), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.from_every_ensemble), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
            items(ensembles.orEmpty(), key = { it.id }) { ensemble ->
                val new = newFiles[ensemble.id] ?: 0
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenEnsemble(ensemble.id) }
                        .padding(vertical = 12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ensemble.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(ensemble.instrument, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (ensemble.folderId == null) {
                                stringResource(R.string.imported_parts_only)
                            } else {
                                syncText(res, syncStates[ensemble.id] ?: SyncState.Idle, ensemble.lastSynced, System.currentTimeMillis())
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (new > 0) Text(pluralStringResource(R.plurals.new_parts, new, count(res, new)), color = MaterialTheme.colorScheme.secondary)
                }
                HorizontalDivider()
            }
            item {
                OutlinedButton(onClick = onAddEnsemble, modifier = Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.add_an_ensemble))
                }
            }
            // The folder Volti read in place before every PDF became an ensemble's: once, the offer to import it.
            (local as? LocalFolder.Chosen)?.let { folder ->
                item { Heading(stringResource(device().pick(R.string.on_this_tablet, R.string.on_this_phone, R.string.on_this_device))) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                        Text(folder.name ?: stringResource(R.string.folder), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.old_folder_text))
                        folderProblem?.let {
                            val text = when (it) {
                                OldFolderProblem.TooLarge -> stringResource(
                                    R.string.old_folder_too_large,
                                    count(res, WALK_LIMIT),
                                    stringResource(R.string.import_button),
                                    stringResource(R.string.import_folder_menu),
                                )
                                OldFolderProblem.NoPdfs -> stringResource(R.string.old_folder_no_pdfs)
                                OldFolderProblem.Unreadable -> stringResource(R.string.old_folder_unreadable)
                            }
                            Text(text, color = MaterialTheme.colorScheme.error)
                        }
                        val progress = copying
                        if (progress != null) {
                            Text(importProgressText(res, progress), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = viewModel::importOldFolder) { Text(stringResource(R.string.import_it)) }
                                TextButton(onClick = viewModel::letGoOldFolder) { Text(stringResource(R.string.remove)) }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun Heading(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 32.dp, bottom = 4.dp),
)

/** Material's "search" magnifier: no icon library for it, as for the back arrow. */
private val Magnifier: ImageVector = ImageVector.Builder("Search", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes(
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16" +
                "c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5" +
                "S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
        ),
        fill = SolidColor(Color.Black),
    )
    .build()
