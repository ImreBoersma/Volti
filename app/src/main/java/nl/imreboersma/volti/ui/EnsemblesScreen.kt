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
import androidx.compose.foundation.layout.widthIn
import nl.imreboersma.volti.ui.theme.MaxContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.runtime.remember
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
import nl.imreboersma.volti.drive.SyncState
import nl.imreboersma.volti.drive.MAX_PDF_BYTES
import nl.imreboersma.volti.drive.Rejection
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

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
                .widthIn(max = MaxContentWidth)
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
                label = { Text("Link to the shared Drive folder, if there is one") },
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
    onOpenScore: (uri: String, scoreKey: String) -> Unit,
    onOpenSetlists: (ensembleId: Long) -> Unit,
    onBack: () -> Unit,
    viewModel: EnsembleViewModel = viewModel(),
) {
    val ensemble by viewModel.ensemble.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val path by viewModel.path.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val view by viewModel.rows.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val needsConsent by viewModel.consent.collectAsStateWithLifecycle()
    val since by viewModel.since.collectAsStateWithLifecycle()
    val showAll by viewModel.showAll.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val fetching by viewModel.fetching.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val notImported by viewModel.notImported.collectAsStateWithLifecycle()
    val toPlace by viewModel.toPlace.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<EnsembleFile?>(null) }
    // Into the folder I'm in; from my parts or a search, into the ensemble's top.
    val into = if (view is EnsembleView.Folder) path else emptyList()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.import(uris, into)
    }
    // The suggestions as they were when I tapped Review: a sync may change the list meanwhile, and only what I saw is
    // decided. A suggestion that arrives during the review waits for the next one.
    var reviewing by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.consented(it.data)
    }
    // Syncs while this screen shows, and only then. The consent screen also stops it; coming back syncs again.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.sync() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.left() }

    // Back clears the search first, then goes up a folder, then back to the library: the button does the same.
    // Only while this is the active screen: during the crossfade into the reader, back must close the score.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    // Up a folder only while browsing folders: my parts and search results aren't in one.
    val inFolder = path.isNotEmpty() && view is EnsembleView.Folder
    BackHandler(enabled = active && inFolder) { viewModel.up() }
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
                            inFolder -> viewModel.up()
                            else -> onBack()
                        }
                    },
                    description = when {
                        query.isNotEmpty() -> "Clear the search"
                        inFolder -> "Up a folder"
                        else -> "Back to the library"
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(ensemble?.name.orEmpty(), style = MaterialTheme.typography.headlineLarge)
                    Text(
                        (listOfNotNull(ensemble?.instrument) + if (view is EnsembleView.Folder) path else emptyList()).joinToString(" / "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { pick.launch(arrayOf("application/pdf")) }, enabled = !importing) { Text("Import") }
                ensemble?.let {
                    OutlinedButton(onClick = { onOpenSetlists(it.id) }, modifier = Modifier.padding(start = 8.dp)) { Text("Setlists") }
                }
            }
            // Only imports: nothing to sync.
            if (ensemble?.folderId != null) SyncLine(
                // Signed out: still say how old the copy here is.
                text = syncText(
                    if (needsConsent != null) SyncState.Failed("Signed out of Google Drive.") else sync,
                    ensemble?.lastSynced,
                    System.currentTimeMillis(),
                ),
                consent = needsConsent,
                failed = sync is SyncState.Failed,
                onConsent = { consent.launch(IntentSenderRequest.Builder(it).build()) },
                onRetry = { viewModel.sync(force = true) },
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
            if (toPlace.isNotEmpty()) {
                // Shared from another app: I go to the folder it belongs in, then say so.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    val what = if (toPlace.size == 1) "1 PDF" else "${toPlace.size} PDFs"
                    val where = if (into.isEmpty()) ensemble?.name.orEmpty() else into.joinToString(" / ")
                    Text("$what to import into $where", modifier = Modifier.weight(1f, fill = false))
                    Button(onClick = { viewModel.place(into) }) { Text("Import here") }
                    TextButton(onClick = viewModel::dontPlace) { Text("Cancel") }
                }
            }
            if (importing) Text("Importing…", modifier = Modifier.padding(bottom = 8.dp))
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
            if (notImported.isNotEmpty()) {
                Text(notImportedText(notImported, deviceName()), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
            }
            pending?.let { ImportReplaces(it.replacing, onReplace = { viewModel.confirmImport(true) }, onKeep = { viewModel.confirmImport(false) }, onCancel = viewModel::cancelImport) }
            deleting?.let { file ->
                DeleteImported(file, onDelete = {
                    viewModel.delete(file)
                    deleting = null
                }, onCancel = { deleting = null })
            }
            val anyMine = files.orEmpty().any { it.mine }
            if (anyMine && query.isBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    FilterChip(selected = !showAll, onClick = { viewModel.showAll(false) }, label = { Text("My parts") })
                    FilterChip(selected = showAll, onClick = { viewModel.showAll(true) }, label = { Text("All files") })
                }
            }
            if (suggestions.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    val count = suggestions.size
                    Text(
                        (if (count == 1) "1 file looks" else "$count files look") + " like my part (${ensemble?.instrument.orEmpty()}).",
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Button(onClick = { reviewing = suggestions.map { it.fileId } }) { Text("Review") }
                }
            }
            reviewing?.let { ids ->
                val byId = files.orEmpty().associateBy { it.fileId }
                val shown = ids.mapNotNull { byId[it] }
                if (shown.isEmpty()) {
                    reviewing = null // Gone meanwhile: nothing left to decide.
                } else {
                    ReviewSuggestions(
                        shown,
                        onDone = { chosen ->
                            viewModel.decide(shown, chosen)
                            reviewing = null
                        },
                        onCancel = { reviewing = null },
                    )
                }
            }
            val open = { file: EnsembleFile -> viewModel.open(file, onOpenScore) }
            val isNew = { file: EnsembleFile -> file.addedAt != null && file.addedAt > since }
            val rowFor = @Composable { file: EnsembleFile, folders: String? ->
                FileRow(
                    file, isNew(file), folders, fetching = file.fileId == fetching, onOpen = open,
                    onMine = { viewModel.setMine(file, it) }, onDelete = { deleting = file },
                )
            }
            // Pulled: sync now, also if it just did.
            PullToRefreshBox(
                isRefreshing = sync is SyncState.Listing || sync is SyncState.Downloading,
                onRefresh = { viewModel.sync(force = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
            when (val v = view) {
                null -> Unit
                is EnsembleView.Folder ->
                    if (v.rows.isEmpty()) {
                        // Before the first sync has brought anything in, or a folder without PDFs.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (ensemble?.folderId == null) "No PDFs yet. Import them from this ${deviceName()}." else "No PDFs here yet.")
                        }
                    } else {
                        RowList(v.rows, onOpenFolder = viewModel::open, file = { rowFor(it, null) })
                    }
                is EnsembleView.Found ->
                    if (v.files.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Nothing found for \"$query\".")
                        }
                    } else {
                        FoundList(v.files, file = { rowFor(it, it.folders.takeIf { f -> f.isNotEmpty() }?.joinToString(" / ")) })
                    }
                is EnsembleView.Mine ->
                    FoundList(v.files, file = { rowFor(it, it.folders.takeIf { f -> f.isNotEmpty() }?.joinToString(" / ")) })
            }
            }
        }
    }
}

@Composable
private fun SyncLine(
    text: String,
    consent: PendingIntent?,
    failed: Boolean,
    onConsent: (PendingIntent) -> Unit,
    onRetry: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(vertical = 16.dp),
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f, fill = false),
        )
        when {
            consent != null -> Button(onClick = { onConsent(consent) }) { Text("Sign in to Google Drive") }
            failed -> OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

/** A folder's rows, styled as the library's: folders in bold with a trailing slash, the way a path reads. */
@Composable
private fun RowList(rows: List<EnsembleRow>, onOpenFolder: (String) -> Unit, file: @Composable (EnsembleFile) -> Unit) {
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
                is EnsembleRow.Pdf -> file(row.file)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun FoundList(files: List<EnsembleFile>, file: @Composable (EnsembleFile) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(files, key = { it.fileId }) { f ->
            file(f)
            HorizontalDivider()
        }
    }
}

/**
 * A PDF, with what changed about it and whether it is mine. One that isn't on the device yet is downloaded when I
 * open it.
 */
@Composable
private fun FileRow(
    file: EnsembleFile,
    new: Boolean,
    folders: String?,
    fetching: Boolean,
    onOpen: (EnsembleFile) -> Unit,
    onMine: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val downloaded = file.localMd5 != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(file) }
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
            fetching -> Text("Downloading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            !downloaded -> Text("Not on this ${deviceName()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            file.removed -> Text("Removed in Drive", color = MaterialTheme.colorScheme.onSurfaceVariant)
            file.changed -> Text("Changed", color = MaterialTheme.colorScheme.secondary)
            new -> Text("New", color = MaterialTheme.colorScheme.secondary)
        }
        FilterChip(
            selected = file.mine,
            onClick = { onMine(!file.mine) },
            label = { Text("Mine") },
            modifier = Modifier.padding(start = 12.dp),
        )
        // A Drive file comes and goes with the folder; only what I imported is mine to delete.
        if (file.imported) TextButton(onClick = onDelete, modifier = Modifier.padding(start = 4.dp)) { Text("Delete") }
    }
}

/** Per PDF that an import couldn't take in, why, in my terms. */
internal fun notImportedText(problems: List<Pair<String, Rejection>>, device: String) = problems.joinToString("\n") { (name, why) ->
    "$name wasn't imported: " + when (why) {
        Rejection.TooLarge -> "it's larger than ${MAX_PDF_BYTES / 1024 / 1024} MB."
        Rejection.NotAPdf, Rejection.WrongChecksum -> "it isn't a PDF."
        Rejection.NoSpace -> "there isn't enough space on this $device."
        Rejection.Unreadable -> "it couldn't be read."
    }
}

/** Some picked PDFs have the name of an imported file in that folder: new versions of them, or next to them? */
@Composable
private fun ImportReplaces(names: List<String>, onReplace: () -> Unit, onKeep: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (names.size == 1) "${names[0]} is here already" else "${names.size} of these are here already") },
        text = { Text("Replace: the new one takes its place, and my marks stay on it. Keep both: it comes in next to it, with (2) after its name.") },
        confirmButton = { TextButton(onClick = onReplace) { Text("Replace") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onKeep) { Text("Keep both") }
            }
        },
    )
}

@Composable
private fun DeleteImported(file: EnsembleFile, onDelete: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete ${file.name}?") },
        text = { Text("It goes from this ensemble and its setlists, with my marks on it.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** The suggested parts, all ticked: I untick what isn't mine. What I leave unticked isn't suggested again. */
@Composable
private fun ReviewSuggestions(suggestions: List<EnsembleFile>, onDone: (Set<String>) -> Unit, onCancel: () -> Unit) {
    var ticked by rememberSaveable { mutableStateOf(suggestions.map { it.fileId }.toSet()) }
    val chosen = ticked intersect suggestions.map { it.fileId }.toSet() // A file that went meanwhile can't be chosen.
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Are these my parts?") },
        text = {
            LazyColumn {
                items(suggestions, key = { it.fileId }) { file ->
                    val on = file.fileId in chosen
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = on, role = Role.Checkbox) {
                                ticked = if (it) ticked + file.fileId else ticked - file.fileId
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(file.name)
                            if (file.folders.isNotEmpty()) Text(file.folders.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(chosen) }) {
                Text(if (chosen.size == 1) "1 is mine" else "${chosen.size} are mine")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Not now") } },
    )
}
