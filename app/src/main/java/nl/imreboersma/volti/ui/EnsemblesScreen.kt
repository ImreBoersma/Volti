package nl.imreboersma.volti.ui

import android.app.PendingIntent
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.imreboersma.volti.data.EnsembleRow
import nl.imreboersma.volti.db.EnsembleFile

/** A form to add an ensemble from a shared folder's link. */
@Composable
fun AddEnsembleScreen(onAdded: (Long) -> Unit, onBack: () -> Unit, viewModel: EnsemblesViewModel = viewModel()) {
    val error by viewModel.error.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var instrument by rememberSaveable { mutableStateOf("") }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(onBack, "Back to the library")
                Text("Add an ensemble", style = MaterialTheme.typography.headlineLarge)
            }
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Link to the shared Drive folder") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
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
            Button(onClick = { viewModel.add(link, name, instrument, onAdded) }) { Text("Add") }
        }
    }
}

/**
 * One ensemble, browsed like the library: its Drive folders as folders, search across all of it. The files are the
 * ones on the tablet; the sync with Drive runs above them while this screen shows.
 */
@Composable
fun EnsembleScreen(
    onOpenScore: (String) -> Unit,
    onRemoved: () -> Unit,
    onBack: () -> Unit,
    viewModel: EnsembleViewModel = viewModel(),
) {
    val ensemble by viewModel.ensemble.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val path by viewModel.path.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val view by viewModel.rows.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.consented(it.data)
    }
    // Syncs while this screen shows, and only then. The consent screen also stops it; coming back syncs again.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.load() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pause() }

    // Back clears the search first, then goes up a folder, then back to the library: the button does the same.
    // Only while this is the active screen: during the crossfade into the reader, back must close the score.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    BackHandler(enabled = active && path.isNotEmpty()) { viewModel.up() }
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
                            path.isNotEmpty() -> viewModel.up()
                            else -> onBack()
                        }
                    },
                    description = when {
                        query.isNotEmpty() -> "Clear the search"
                        path.isNotEmpty() -> "Up a folder"
                        else -> "Back to the library"
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(ensemble?.name.orEmpty(), style = MaterialTheme.typography.headlineLarge)
                    Text(
                        (listOfNotNull(ensemble?.instrument) + path).joinToString(" / "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            if (!files.isNullOrEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("Search this ensemble") },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) TextButton(onClick = { viewModel.setQuery("") }) { Text("Clear") }
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
            val open = { file: EnsembleFile -> viewModel.open(file, onOpenScore) }
            when (val v = view) {
                null -> Unit
                is EnsembleView.Folder ->
                    if (v.rows.isEmpty()) {
                        // Before the first sync has brought anything in, or a folder without PDFs.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No PDFs here yet.") }
                    } else {
                        RowList(v.rows, onOpenFolder = viewModel::open, onOpen = open)
                    }
                is EnsembleView.Found ->
                    if (v.files.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Nothing found for \"$query\".")
                        }
                    } else {
                        FoundList(v.files, onOpen = open)
                    }
            }
        }
    }
}

@Composable
private fun SyncLine(
    sync: SyncState,
    count: Int,
    onConsent: (PendingIntent) -> Unit,
    onRetry: () -> Unit,
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

/** A folder's rows, styled as the library's: folders in bold with a trailing slash, the way a path reads. */
@Composable
private fun RowList(rows: List<EnsembleRow>, onOpenFolder: (String) -> Unit, onOpen: (EnsembleFile) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows, key = { row ->
            when (row) {
                is EnsembleRow.Folder -> "folder:" + row.name
                is EnsembleRow.Pdf -> row.file.fileId
            }
        }) { row ->
            when (row) {
                is EnsembleRow.Folder -> Text(
                    text = row.name + "/",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFolder(row.name) }
                        .padding(vertical = 16.dp),
                )
                is EnsembleRow.Pdf -> FileRow(row.file, folders = null, onOpen = onOpen)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun FoundList(files: List<EnsembleFile>, onOpen: (EnsembleFile) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(files, key = { it.fileId }) { file ->
            FileRow(file, folders = file.folders.takeIf { it.isNotEmpty() }?.joinToString(" / "), onOpen = onOpen)
            HorizontalDivider()
        }
    }
}

/** A PDF: openable once it is on the tablet, with what changed about it. */
@Composable
private fun FileRow(file: EnsembleFile, folders: String?, onOpen: (EnsembleFile) -> Unit) {
    val downloaded = file.localMd5 != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = downloaded) { onOpen(file) }
            .padding(vertical = if (folders == null) 16.dp else 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (downloaded) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            folders?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        when {
            !downloaded -> Text("Not downloaded yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            file.removed -> Text("Removed in Drive", color = MaterialTheme.colorScheme.onSurfaceVariant)
            file.changed -> Text("Changed", color = MaterialTheme.colorScheme.secondary)
        }
    }
}
