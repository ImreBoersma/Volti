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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import nl.imreboersma.volti.data.ReaderSettings
import nl.imreboersma.volti.data.ReaderSettingsStore
import nl.imreboersma.volti.data.folderName
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.VoltiDatabase

/** The local folder as the home screen shows it. */
sealed interface LocalFolder {
    data object None : LocalFolder
    data class Chosen(val name: String) : LocalFolder
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val readerSettings = ReaderSettingsStore(app)
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

    val localFolder: StateFlow<LocalFolder?> = FolderStore(app).folder
        .map { uri ->
            if (uri == null) {
                LocalFolder.None
            } else {
                // A folder that can't be read any more (deleted, card removed) still shows, by a plain name: the
                // library says what's wrong when it's opened. Providers throw all sorts for that; none may stop the app.
                val name = try {
                    withContext(Dispatchers.IO) { folderName(app.contentResolver, uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Volti", "Can't read the folder's name", e)
                    null
                }
                LocalFolder.Chosen(name ?: "Folder")
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<ReaderSettings> = readerSettings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderSettings())

    fun setCropMargins(on: Boolean) {
        viewModelScope.launch { readerSettings.setCropMargins(on) }
    }

    fun setHalfPageTurns(on: Boolean) {
        viewModelScope.launch { readerSettings.setHalfPageTurns(on) }
    }
}

/**
 * Where the app opens: my ensembles, then what is on the tablet itself, and how scores open. Back here leaves the
 * app, so there is no back button.
 */
@Composable
fun HomeScreen(
    onOpenEnsemble: (Long) -> Unit,
    onAddEnsemble: () -> Unit,
    onOpenLocal: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val ensembles by viewModel.ensembles.collectAsStateWithLifecycle()
    val local by viewModel.localFolder.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
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
    val syncing = syncStates.values.any { it is SyncState.Listing || it is SyncState.Downloading }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        PullToRefreshBox(
            isRefreshing = syncing,
            onRefresh = { viewModel.syncAll(force = true) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            item { Text("Library", style = MaterialTheme.typography.headlineLarge) }
            item {
                // How scores open. Read when a score opens, so a change applies to the next one.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilterChip(
                        selected = settings.cropMargins,
                        onClick = { viewModel.setCropMargins(!settings.cropMargins) },
                        label = { Text("Crop margins") },
                    )
                    FilterChip(
                        selected = settings.halfPageTurns,
                        onClick = { viewModel.setHalfPageTurns(!settings.halfPageTurns) },
                        label = { Text("Half-page turns") },
                    )
                }
            }
            item { Heading("Ensembles") }
            needsConsent?.let { intent ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Volti needs read-only access to Google Drive to sync.", modifier = Modifier.weight(1f, fill = false))
                        Button(onClick = { consent.launch(IntentSenderRequest.Builder(intent).build()) }) {
                            Text("Sign in to Google Drive")
                        }
                    }
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
                            syncText(syncStates[ensemble.id] ?: SyncState.Idle, ensemble.lastSynced, System.currentTimeMillis()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (new > 0) Text("$new new", color = MaterialTheme.colorScheme.secondary)
                }
                HorizontalDivider()
            }
            item {
                OutlinedButton(onClick = onAddEnsemble, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Add an ensemble")
                }
            }
            item { Heading("On this tablet") }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenLocal)
                        .padding(vertical = 12.dp),
                ) {
                    when (val folder = local) {
                        is LocalFolder.Chosen -> {
                            Text(folder.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("A folder on the tablet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LocalFolder.None, null -> Text("Choose a folder on the tablet", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                HorizontalDivider()
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
