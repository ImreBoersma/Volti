package nl.imreboersma.volti.ui

import android.app.Application
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

    val ensembles: StateFlow<List<Ensemble>?> = VoltiDatabase.get(app).ensembles().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
    // Tells Android when the home screen is really there: logged as "Fully drawn" at cold start.
    ReportDrawnWhen { ensembles != null && local != null }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
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
            items(ensembles.orEmpty(), key = { it.id }) { ensemble ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenEnsemble(ensemble.id) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(ensemble.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(ensemble.instrument, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun Heading(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 32.dp, bottom = 4.dp),
)
