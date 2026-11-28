package nl.imreboersma.volti.ui

import android.app.PendingIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.imreboersma.volti.R
import nl.imreboersma.volti.data.WALK_LIMIT
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.imreboersma.volti.data.EnsembleRow
import nl.imreboersma.volti.db.EnsembleFile
import nl.imreboersma.volti.drive.SyncState
import nl.imreboersma.volti.drive.DriveProblem
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
                AppBackButton(onBack, stringResource(R.string.back_to_library))
                Text(stringResource(R.string.add_an_ensemble), style = MaterialTheme.typography.headlineLarge)
            }
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text(stringResource(R.string.add_ensemble_link)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ensemble_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = instrument,
                    onValueChange = { instrument = it },
                    label = { Text(stringResource(R.string.instrument_example)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            error?.let { problem ->
                val text = when (problem) {
                    AddProblem.NotALink -> R.string.add_not_a_link
                    AddProblem.NoName -> R.string.add_no_name
                    AddProblem.NoInstrument -> R.string.add_no_instrument
                    AddProblem.AlreadyAdded -> R.string.add_already
                }
                Text(stringResource(text), color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { viewModel.add(link, name, instrument, onAdded) }) { Text(stringResource(R.string.add)) }
        }
    }
}

/**
 * One ensemble, browsed like the library: its Drive folders as folders, search across all of it. The files are the
 * ones on the tablet; the sync with Drive runs above them while this screen shows. [setlists] gets room above the
 * scores, or beside them on a wide screen; not while searching.
 */
@Composable
fun EnsembleScreen(
    onOpenScore: (uri: String, scoreKey: String) -> Unit,
    setlists: @Composable ColumnScope.() -> Unit = {},
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
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val reading by viewModel.reading.collectAsStateWithLifecycle()
    val res = LocalResources.current
    val device = device()
    val notImported by viewModel.notImported.collectAsStateWithLifecycle()
    val placing by viewModel.placing.collectAsStateWithLifecycle()
    var choosingImport by remember { mutableStateOf(false) }
        var searching by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<EnsembleFile?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) } // The file id.
    // Into the folder I'm in; from my parts or a search, into the ensemble's top.
    val into = if (view is EnsembleView.Folder) path else emptyList()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.import(uris, into)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let { viewModel.importFolder(it, into) }
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
                        query.isNotEmpty() -> stringResource(R.string.clear_the_search)
                        inFolder -> stringResource(R.string.up_a_folder)
                        else -> stringResource(R.string.back_to_library)
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(ensemble?.name.orEmpty(), style = MaterialTheme.typography.headlineLarge, maxLines = NAME_LINES, overflow = TextOverflow.Ellipsis)
                    Text(
                        (listOfNotNull(ensemble?.instrument) + if (view is EnsembleView.Folder) path else emptyList()).joinToString(" / "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = NAME_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    OutlinedButton(onClick = { choosingImport = true }, enabled = importing == null && !reading) { Text(stringResource(R.string.import_button)) }
                    DropdownMenu(expanded = choosingImport, onDismissRequest = { choosingImport = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.import_pdfs_menu)) }, onClick = {
                            choosingImport = false
                            pick.launch(arrayOf("application/pdf"))
                        })
                        // Its subfolders come along as folders.
                        DropdownMenuItem(text = { Text(stringResource(R.string.import_folder_menu)) }, onClick = {
                            choosingImport = false
                            pickFolder.launch(null)
                        })
                    }
                }
            }
            // Only imports: nothing to sync.
            if (ensemble?.folderId != null) SyncLine(
                // Signed out: still say how old the copy here is.
                text = syncText(
                    res,
                    if (needsConsent != null) SyncState.Failed(DriveProblem.SignedOut) else sync,
                    ensemble?.lastSynced,
                    System.currentTimeMillis(),
                ),
                consent = needsConsent,
                failed = sync is SyncState.Failed,
                onConsent = { consent.launch(IntentSenderRequest.Builder(it).build()) },
                onRetry = { viewModel.sync(force = true) },
            )
            // Everything about the scores: the search, the chips and the list. With the setlists beside or above it.
            // [top]: what goes first in the list and scrolls away with it (the setlists, on a narrow window).
                        val scores: @Composable ColumnScope.(top: (@Composable ColumnScope.() -> Unit)?) -> Unit = { top ->
                if (!files.isNullOrEmpty()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        placeholder = { Text(stringResource(R.string.search_ensemble)) },
                        singleLine = true,
                        trailingIcon = {
                            if (query.isNotEmpty()) TextButton(onClick = { viewModel.setQuery("") }) { Text(stringResource(R.string.clear)) }
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).onFocusChanged { searching = it.isFocused },
                    )
                }
                val shared = placing?.incoming.orEmpty()
                if (shared.isNotEmpty()) {
                    // Shared from another app: I go to the folder it belongs in, then say so.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        val where = if (into.isEmpty()) ensemble?.name.orEmpty() else into.joinToString(" / ")
                        Text(
                            pluralStringResource(R.plurals.shared_to_import, shared.size, count(res, shared.size), where),
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = NAME_LINES + 1, // The sentence around the folder's name.
                            overflow = TextOverflow.Ellipsis,
                        )
                        Button(onClick = { viewModel.place(into) }) { Text(stringResource(R.string.import_here)) }
                        TextButton(onClick = viewModel::dontPlace) { Text(stringResource(R.string.cancel)) }
                    }
                }
                val progress = importing?.let { importProgressText(res, it) } ?: stringResource(R.string.reading_folder).takeIf { reading }
                progress?.let { Text(it, modifier = Modifier.padding(bottom = 8.dp)) }
                message?.let {
                    val text = when (it) {
                        is EnsembleMessage.Open -> fileProblemText(res, it.problem)
                        EnsembleMessage.FolderUnreadable -> stringResource(R.string.file_unreadable_folder)
                        EnsembleMessage.FolderTooLarge -> stringResource(R.string.folder_too_large, count(res, WALK_LIMIT))
                        EnsembleMessage.NoPdfs -> stringResource(R.string.folder_no_pdfs)
                    }
                    Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (notImported.isNotEmpty()) {
                    Text(notImportedText(res, notImported, device), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                }
                pending?.let { ImportReplaces(it.replacing, onReplace = { viewModel.confirmImport(true) }, onKeep = { viewModel.confirmImport(false) }, onCancel = viewModel::cancelImport) }
                renaming?.let { fileId ->
                    // The file as it is now: a sync meanwhile may have renamed it in Drive. Only once the list is in: back
                    // after the process ended, it is still loading, and the dialog (with what I typed) must wait for it.
                    files?.let { list ->
                        val file = list.firstOrNull { it.fileId == fileId }
                        if (file == null) {
                            LaunchedEffect(fileId) { renaming = null } // Gone meanwhile.
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
                val anyMine = files.orEmpty().any { it.mine }
                if (anyMine && query.isBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        FilterChip(selected = !showAll, onClick = { viewModel.showAll(false) }, label = { Text(stringResource(R.string.your_parts)) })
                        FilterChip(selected = showAll, onClick = { viewModel.showAll(true) }, label = { Text(stringResource(R.string.all_parts)) })
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
                            pluralStringResource(R.plurals.suggestions, count, count(res, count), ensemble?.instrument.orEmpty()),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Button(onClick = { reviewing = suggestions.map { it.fileId } }) { Text(stringResource(R.string.review)) }
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
                        file, isNew(file), folders, download = downloadOf(file.fileId, sync, downloading), onOpen = open,
                        onMine = { viewModel.setMine(file, it) }, onRename = { renaming = file.fileId }, onDelete = { deleting = file },
                    )
                }
                // Pulled: sync now, also if it just did.
                PullToRefreshBox(
                    // Only while Google Drive is asked what changed: the downloads after that show on the sheets.
                    isRefreshing = sync is SyncState.Listing,
                    onRefresh = { viewModel.sync(force = true) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                when (val v = view) {
                    null -> Unit
                    is EnsembleView.Folder ->
                        if (v.rows.isEmpty()) {
                            // Before the first sync has brought anything in, or a folder without PDFs.
                                                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                                        top?.invoke(this)
                                                        Text(
                                                            if (ensemble?.folderId == null) {
                                                                stringResource(device.pick(R.string.no_pdfs_import_tablet, R.string.no_pdfs_import_phone, R.string.no_pdfs_import_device))
                                                            } else {
                                                                stringResource(R.string.no_parts_here)
                                                            },
                                                            modifier = Modifier.padding(vertical = 24.dp),
                                                        )
                                                    }
                                                } else {
                                                    RowList(v.rows, onOpenFolder = viewModel::open, top = top, file = { rowFor(it, null) })
                                                }
                    is EnsembleView.Found ->
                        if (v.files.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.nothing_found, query))
                            }
                        } else {
                            FoundList(v.files, file = { rowFor(it, it.folders.takeIf { f -> f.isNotEmpty() }?.joinToString(" / ")) })
                        }
                    is EnsembleView.Mine ->
                                        FoundList(v.files, top = top, file = { rowFor(it, it.folders.takeIf { f -> f.isNotEmpty() }?.joinToString(" / ")) })
                }
                }
            }
            // An expanded window (the tablet in landscape) by its width class, never by the device: the setlists in a
                        // narrow pane beside the scores. Else they are the top of the scores' list, and scroll away with it.
                        val window = LocalWindowInfo.current.containerSize
                        val expanded = with(LocalDensity.current) { window.width.toDp() } >= EXPANDED_WIDTH
                        if (expanded) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                // Not while searching: a search shows only what it found.
                                if (query.isBlank()) {
                                    Column(modifier = Modifier.width(SETLISTS_WIDTH).padding(end = 24.dp).verticalScroll(rememberScrollState())) {
                                        setlists()
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) { scores(null) }
                            }
                        } else {
                            // Not while searching, nor while the search field has the keyboard: room for what is being found.
                            Column(modifier = Modifier.fillMaxSize()) { scores(setlists.takeIf { query.isBlank() && !searching }) }
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
            consent != null -> Button(onClick = { onConsent(consent) }) { Text(stringResource(R.string.sign_in_to_drive)) }
            failed -> OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
        }
    }
}

/** A folder's rows, styled as the library's: folders in bold with a trailing slash, the way a path reads. */
@Composable
private fun RowList(
    rows: List<EnsembleRow>,
    onOpenFolder: (String) -> Unit,
    top: (@Composable ColumnScope.() -> Unit)?,
    file: @Composable (EnsembleFile) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        top?.let { item(key = "top") { Column(content = it) } }
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
                    maxLines = NAME_LINES,
                    overflow = TextOverflow.Ellipsis,
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
private fun FoundList(files: List<EnsembleFile>, top: (@Composable ColumnScope.() -> Unit)? = null, file: @Composable (EnsembleFile) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        top?.let { item(key = "top") { Column(content = it) } }
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
internal fun FileRow(
    file: EnsembleFile,
    new: Boolean,
    folders: String?,
    download: Download?,
    onOpen: (EnsembleFile) -> Unit,
    onMine: (Boolean) -> Unit,
    onRename: () -> Unit,
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
                file.shownName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (downloaded) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = NAME_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            folders?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = NAME_LINES, overflow = TextOverflow.Ellipsis) }
        }
        when {
            download == Download.Now -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.downloading), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            }
            // Quiet: it comes later in this sync. Only for one not here yet: one that is opens already.
            !downloaded && download == Download.Waiting ->
                Icon(DownloadArrow, contentDescription = stringResource(R.string.waiting_to_download), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            !downloaded -> Text(stringResource(device().pick(R.string.not_on_this_tablet, R.string.not_on_this_phone, R.string.not_on_this_device)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            file.removed -> Text(stringResource(R.string.removed_from_drive), color = MaterialTheme.colorScheme.onSurfaceVariant)
            file.changed -> Text(stringResource(R.string.changed), color = MaterialTheme.colorScheme.secondary)
            new -> Text(stringResource(R.string.new_label), color = MaterialTheme.colorScheme.secondary)
        }
        FilterChip(
            selected = file.mine,
            onClick = { onMine(!file.mine) },
            label = { Text(stringResource(R.string.your_part)) },
            modifier = Modifier.padding(start = 12.dp),
        )
        // A Drive file comes and goes with the folder; only what I imported is mine to delete.
        MoreMenu(
            stringResource(R.string.more_for, file.shownName),
            listOfNotNull(stringResource(R.string.rename) to onRename, (stringResource(R.string.delete) to onDelete).takeIf { file.imported }),
        )
    }
}

/** Some picked PDFs have the name of an imported file in that folder: new versions of them, or next to them? */
@Composable
private fun ImportReplaces(names: List<String>, onReplace: () -> Unit, onKeep: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (names.size == 1) {
                    stringResource(R.string.replaces_one, names[0])
                } else {
                    pluralStringResource(R.plurals.replaces_many, names.size, count(LocalResources.current, names.size))
                },
                maxLines = NAME_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = { Text(stringResource(R.string.replaces_text)) },
        confirmButton = { TextButton(onClick = onReplace) { Text(stringResource(R.string.replace)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = onKeep) { Text(stringResource(R.string.keep_both)) }
            }
        },
    )
}

@Composable
internal fun DeleteImported(file: EnsembleFile, onDelete: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.delete_title, file.shownName), maxLines = NAME_LINES, overflow = TextOverflow.Ellipsis) },
        text = { Text(stringResource(R.string.delete_imported_text)) },
        confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The suggested parts, all ticked: I untick what isn't mine. What I leave unticked isn't suggested again. */
@Composable
private fun ReviewSuggestions(suggestions: List<EnsembleFile>, onDone: (Set<String>) -> Unit, onCancel: () -> Unit) {
    var ticked by rememberSaveable { mutableStateOf(suggestions.map { it.fileId }.toSet()) }
    val chosen = ticked intersect suggestions.map { it.fileId }.toSet() // A file that went meanwhile can't be chosen.
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.review_title)) },
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
                            Text(file.shownName, maxLines = NAME_LINES, overflow = TextOverflow.Ellipsis)
                            if (file.folders.isNotEmpty()) Text(file.folders.joinToString(" / "), style = MaterialTheme.typography.bodySmall, maxLines = NAME_LINES, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(chosen) }) {
                Text(pluralStringResource(R.plurals.review_confirm, chosen.size, count(LocalResources.current, chosen.size)))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.not_now)) } },
    )
}

/** A sheet's place in what is being downloaded. */
enum class Download { Now, Waiting }

/** [fileId]'s download: now (whatever for: [downloading] holds them all), waiting its turn in the sync, or none. */
fun downloadOf(fileId: String, sync: SyncState, downloading: Set<String>): Download? = when {
    fileId in downloading -> Download.Now
    sync is SyncState.Downloading && fileId in sync.waiting -> Download.Waiting
    else -> null
}

/** Material's "download" arrow: no icon library for it, as for the back arrow. */
private val DownloadArrow: ImageVector = ImageVector.Builder("Download", 20.dp, 20.dp, 24f, 24f)
    .path(fill = SolidColor(Color.Black)) {
        moveTo(19f, 9f)
        horizontalLineToRelative(-4f)
        verticalLineTo(3f)
        horizontalLineTo(9f)
        verticalLineToRelative(6f)
        horizontalLineTo(5f)
        lineToRelative(7f, 7f)
        close()
        moveTo(5f, 18f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(14f)
        verticalLineToRelative(-2f)
        close()
    }
    .build()

/** The expanded window width class starts here: the setlists get a pane of their own (the tablet in landscape). */
private val EXPANDED_WIDTH = 840.dp

private val SETLISTS_WIDTH = 320.dp
