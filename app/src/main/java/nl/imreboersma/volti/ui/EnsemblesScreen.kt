package nl.imreboersma.volti.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.graphics.Color
import android.app.PendingIntent
import nl.imreboersma.volti.db.EnsembleFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** My ensembles, and a form to add one from a shared folder's link. */
@Composable
fun EnsemblesScreen(
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: EnsemblesViewModel = viewModel()
) {
    val ensembles by viewModel.ensembles.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var instrument by rememberSaveable { mutableStateOf("") }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(16.dp),
        ) {
            // Above the list, not in it: the back button stays when the list scrolls to the form.
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(onBack, "Back to the library")
                Text("Ensembles", style = MaterialTheme.typography.headlineLarge)
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ensembles.orEmpty(), key = { it.id }) { ensemble ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(ensemble.id) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(ensemble.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            ensemble.instrument,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                }
                item {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 24.dp),
                    ) {
                        Text("Add an ensemble", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            label = { Text("Link to the shared Drive folder") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                                label = { Text("What I play, e.g. Bb clarinet 2") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = {
                            viewModel.add(link, name, instrument) { id ->
                                link = ""
                                name = ""
                                instrument = ""
                                onOpen(id)
                            }
                        }) { Text("Add") }
                    }
                }
            }
        }
    }
}

/** One ensemble's PDFs as on the tablet, with the sync with Drive above them. */
@Composable
fun EnsembleScreen(
    onOpenScore: (String) -> Unit,
    onRemoved: () -> Unit,
    onBack: () -> Unit,
    viewModel: EnsembleViewModel = viewModel(),
) {
    val ensemble by viewModel.ensemble.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val consent =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            viewModel.consented(it.data)
        }
    // Syncs while this screen shows, and only then. The consent screen also stops it; coming back syncs again.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.load() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pause() }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(onBack, "Back to the ensembles")
                Column(modifier = Modifier.weight(1f)) {
                    Text(ensemble?.name.orEmpty(), style = MaterialTheme.typography.headlineLarge)
                    Text(
                        ensemble?.instrument.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { viewModel.remove(onRemoved) }) { Text("Remove") }
            }
            SyncLine(
                sync = sync,
                count = files?.size ?: 0,
                onConsent = { consent.launch(IntentSenderRequest.Builder(it).build()) },
                onRetry = viewModel::load,
            )
            files?.let { Files(it, onOpen = { file -> viewModel.open(file, onOpenScore) }) }
        }
    }
}

@Composable
private fun SyncLine(
    sync: SyncState,
    count: Int,
    onConsent: (PendingIntent) -> Unit,
    onRetry: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(vertical = 16.dp),
    ) {
        Text(
            text = when (sync) {
                SyncState.Idle, SyncState.Listing -> "Checking Google Drive…"
                is SyncState.Downloading -> "Downloading ${sync.done + 1} of ${sync.total}…"
                is SyncState.NeedsConsent -> "Volti needs read-only access to Google Drive to sync this folder."
                is SyncState.Failed -> sync.message
                is SyncState.Done -> buildString {
                    append(if (count == 1) "1 PDF" else "$count PDFs")
                    append(", up to date")
                    if (sync.failed > 0) append(". ${sync.failed} couldn't be downloaded; they are tried again next time")
                    if (sync.stopped) append(". Stopped at 5,000 entries: the deepest files are left out")
                }
            },
            modifier = Modifier.weight(1f, fill = false),
        )
        when (sync) {
            is SyncState.NeedsConsent -> Button(onClick = { onConsent(sync.intent) }) { Text("Sign in to Google Drive") }
            is SyncState.Failed -> OutlinedButton(onClick = onRetry) { Text("Try again") }
            else -> Unit
        }
    }
}

@Composable
private fun Files(files: List<EnsembleFile>, onOpen: (EnsembleFile) -> Unit) {
    LazyColumn {
        files.groupBy { it.folders }.forEach { (folders, entries) ->
            if (folders.isNotEmpty()) {
                // Joined with a character no name can hold: Drive names may contain "/".
                item(key = "folder:" + folders.joinToString("\u0000")) {
                    Text(
                        folders.joinToString(" / "),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
            }
            items(entries, key = { it.fileId }) { file ->
                val downloaded = file.localMd5 != null
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = downloaded) { onOpen(file) }
                        .padding(vertical = 10.dp),
                ) {
                    Text(
                        file.name,
                        color = if (downloaded) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        !downloaded -> Text(
                            "Not downloaded yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        file.removed -> Text(
                            "Removed in Drive",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        file.changed -> Text("Changed", color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}
