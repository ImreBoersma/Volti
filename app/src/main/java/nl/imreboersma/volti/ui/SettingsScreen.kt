package nl.imreboersma.volti.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.imreboersma.volti.data.ReaderSettings
import nl.imreboersma.volti.data.ReaderSettingsStore
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.VoltiDatabase
import nl.imreboersma.volti.drive.Syncer

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val readerSettings = ReaderSettingsStore(app)
    private val dao = VoltiDatabase.get(app).ensembles()
    private val syncer = Syncer.get(app)

    val settings: StateFlow<ReaderSettings> = readerSettings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderSettings())

    val ensembles: StateFlow<List<Ensemble>?> =
        dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setCropMargins(on: Boolean) {
        viewModelScope.launch { readerSettings.setCropMargins(on) }
    }

    fun setHalfPageTurns(on: Boolean) {
        viewModelScope.launch { readerSettings.setHalfPageTurns(on) }
    }

    /** A new instrument changes what is suggested as my part; what I already decided stays. */
    fun rename(ensemble: Ensemble, name: String, instrument: String) {
        if (name.isBlank() || instrument.isBlank()) return
        viewModelScope.launch { dao.rename(ensemble.id, name.trim(), instrument.trim()) }
    }

    /** The ensemble and the PDFs only it needed go; Drive is not touched. */
    fun remove(ensemble: Ensemble) {
        viewModelScope.launch { syncer.remove(ensemble) }
    }
}

/** How scores open, and my ensembles: their names, what I play in each, and removing one. */
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ensembles by viewModel.ensembles.collectAsStateWithLifecycle()

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
                AppBackButton(onBack, "Back to the library")
                Text("Settings", style = MaterialTheme.typography.headlineLarge)
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { Section("Reading") }
                item {
                    SwitchRow(
                        title = "Crop margins",
                        detail = "Leave out the white around the music, the same for every page of a score, so the notes are bigger.",
                        checked = settings.cropMargins,
                        onChange = viewModel::setCropMargins,
                    )
                }
                item {
                    SwitchRow(
                        title = "Half-page turns",
                        detail = "Turn the top half of the page first, so the end of one page and the start of the next show together.",
                        checked = settings.halfPageTurns,
                        onChange = viewModel::setHalfPageTurns,
                    )
                }
                item { Section("Ensembles") }
                if (ensembles?.isEmpty() == true) {
                    item { Text("None yet. Add one from the library.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(ensembles.orEmpty(), key = { it.id }) { ensemble ->
                    EnsembleSettings(ensemble, onSave = viewModel::rename, onRemove = viewModel::remove)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) = Text(
    title,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
)

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null) // The whole row toggles.
    }
}

@Composable
private fun EnsembleSettings(ensemble: Ensemble, onSave: (Ensemble, String, String) -> Unit, onRemove: (Ensemble) -> Unit) {
    var name by rememberSaveable(ensemble.id, ensemble.name) { mutableStateOf(ensemble.name) }
    var instrument by rememberSaveable(ensemble.id, ensemble.instrument) { mutableStateOf(ensemble.instrument) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val changed = name.trim() != ensemble.name || instrument.trim() != ensemble.instrument

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Ensemble") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = instrument,
                onValueChange = { instrument = it },
                label = { Text("What I play") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(ensemble, name, instrument) },
                enabled = changed && name.isNotBlank() && instrument.isNotBlank(),
            ) { Text("Save") }
            OutlinedButton(onClick = { confirming = true }) { Text("Remove") }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove ${ensemble.name}?") },
            text = { Text("Its PDFs and which of them are mine are deleted from this ${deviceName()}. The folder in Google Drive stays as it is.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onRemove(ensemble)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
