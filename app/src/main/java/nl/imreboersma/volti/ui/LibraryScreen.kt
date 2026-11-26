package nl.imreboersma.volti.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.lifecycle.compose.LifecycleEventEffect
import nl.imreboersma.volti.data.FoundEntry
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.imreboersma.volti.data.LibraryEntry
import nl.imreboersma.volti.data.ReaderSettings
import nl.imreboersma.volti.data.Score
import nl.imreboersma.volti.data.Subfolder
import nl.imreboersma.volti.ui.theme.VoltiTheme

/** Folder picker that hides cloud-backed roots. */
private class OpenLocalDocumentTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

@Composable
fun LibraryScreen(
    onOpenScore: (Score) -> Unit,
    onOpenEnsembles: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Tells Android when the library is really there, list included: logged as "Fully drawn" at cold start.
    ReportDrawnWhen { state != LibraryState.Loading }
    val picker = rememberLauncherForActivityResult(OpenLocalDocumentTree()) { uri ->
        if (uri != null) viewModel.setFolder(uri)
    }

    // Coming back to the library, from the reader or from another app, lists the folder again: files copied in
    // meanwhile show up. Not on first start, which lists anyway.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.libraryStopped() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.libraryStarted() }

    // Back clears a search first, then goes up a folder; at the top it leaves the app as usual. Only while the
    // library is the active screen: during the crossfade into the reader, back must close the score.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    BackHandler(enabled = active && (state as? LibraryState.Ready)?.path?.isNotEmpty() == true) { viewModel.up() }
    BackHandler(enabled = active && query.isNotEmpty()) { viewModel.setQuery("") }

    LibraryContent(
        state = state,
        query = query,
        search = search,
        refreshing = refreshing,
        onQuery = viewModel::setQuery,
        onSearchFocus = viewModel::prepareSearch,
        settings = settings,
        onCropMargins = viewModel::setCropMargins,
        onHalfPageTurns = viewModel::setHalfPageTurns,
        onRefresh = { viewModel.refresh(pulled = true) },
        onPickFolder = { picker.launch((state as? LibraryState.Ready)?.folder) },
        onOpenFolder = { subfolder -> (state as? LibraryState.Ready)?.let { viewModel.open(it, subfolder) } },
        onOpenFound = { found ->
            when (val entry = found.entry) {
                is Score -> onOpenScore(entry)
                is Subfolder -> (state as? LibraryState.Ready)?.let { viewModel.open(it, found) }
            }
        },
        onOpenScore = onOpenScore,
        onOpenEnsembles = onOpenEnsembles,
        // What the back gesture does here: clear the search, else up a folder. At the top there is no button: back
        // leaves the app.
        onBack = when {
            !active -> null
            query.isNotEmpty() -> fun() { viewModel.setQuery("") }
            (state as? LibraryState.Ready)?.path?.isNotEmpty() == true -> fun() { viewModel.up() }
            else -> null
        },
        modifier = modifier,
    )
}

@Composable
private fun LibraryContent(
    state: LibraryState,
    query: String,
    search: SearchResults?,
    refreshing: Boolean,
    onQuery: (String) -> Unit,
    onSearchFocus: () -> Unit,
    settings: ReaderSettings,
    onCropMargins: (Boolean) -> Unit,
    onHalfPageTurns: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenFolder: (Subfolder) -> Unit,
    onOpenFound: (FoundEntry) -> Unit,
    onOpenScore: (Score) -> Unit,
    onOpenEnsembles: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding) // So imePadding below doesn't add the navigation bar twice.
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (onBack != null) {
                    AppBackButton(onBack, if (query.isNotEmpty()) "Clear the search" else "Up a folder")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = (state as? LibraryState.Ready)?.name ?: "Library",
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    if (state is LibraryState.Ready && state.path.isNotEmpty()) {
                        Text(text = state.path.joinToString(" / ") { it.name })
                    }
                }
                // Step 3 of milestone 3 puts the ensembles in the library itself; until then, a way in.
                OutlinedButton(onClick = onOpenEnsembles) { Text(text = "Ensembles") }
                if (state is LibraryState.Ready) {
                    OutlinedButton(onClick = onPickFolder, modifier = Modifier.padding(start = 8.dp)) {
                        Text(text = "Change folder")
                    }
                }
            }
            if (state is LibraryState.Ready) {
                // How scores open. Read when a score opens, so a change applies to the next one.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilterChip(
                        selected = settings.cropMargins,
                        onClick = { onCropMargins(!settings.cropMargins) },
                        label = { Text("Crop margins") },
                    )
                    FilterChip(
                        selected = settings.halfPageTurns,
                        onClick = { onHalfPageTurns(!settings.halfPageTurns) },
                        label = { Text("Half-page turns") },
                    )
                }
            }
            if (state is LibraryState.Ready && state.entries != null) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    placeholder = { Text("Search this folder and its subfolders") },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) TextButton(onClick = { onQuery("") }) { Text("Clear") }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .onFocusChanged { if (it.isFocused) onSearchFocus() },
                )
            }
            when (state) {
                LibraryState.Loading -> Unit
                LibraryState.NoFolder -> Centered {
                    Button(onClick = onPickFolder) { Text(text = "Choose folder") }
                }
                is LibraryState.Ready -> PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = onRefresh,
                    // Edge to edge, the keyboard draws over the app: keep the results above it.
                    modifier = Modifier.fillMaxSize().imePadding(),
                ) {
                    when {
                        query.isNotBlank() -> when {
                            search == null || search.query != query -> Unit // Indexing or catching up: keep it calm.
                            search.results.isEmpty() -> Centered { Text(text = "Nothing found for \"$query\".") }
                            else -> Column {
                                if (search.truncated) {
                                    Text(
                                        text = "This folder is too big to search completely: the deepest files are left out.",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                                FoundList(search.results, onOpenFound)
                            }
                        }
                        state.entries == null -> Centered { Text(text = "Can't read this folder.") }
                        state.entries.isEmpty() -> Centered { Text(text = "No PDFs or folders here.") }
                        else -> EntryList(state.entries, onOpenFolder, onOpenScore)
                    }
                }
            }
        }
    }
}

@Composable
private fun FoundList(found: List<FoundEntry>, onOpen: (FoundEntry) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        items(found, key = { f -> (f.entry as? Score)?.uri?.toString() ?: ("folder:" + (f.entry as Subfolder).documentId) }) { f ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(f) }
                    .padding(vertical = 12.dp),
            ) {
                Text(
                    text = if (f.entry is Subfolder) f.entry.name + "/" else f.entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (f.entry is Subfolder) FontWeight.SemiBold else null,
                )
                if (f.parents.isNotEmpty()) {
                    Text(
                        text = f.parents.joinToString(" / ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    // Scrollable, so pull-to-refresh also works on an empty or unreadable folder.
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun EntryList(entries: List<LibraryEntry>, onOpenFolder: (Subfolder) -> Unit, onOpenScore: (Score) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
        items(entries, key = { entry -> if (entry is Score) entry.uri.toString() else "folder:" + (entry as Subfolder).documentId }) { entry ->
            Text(
                // Folders in bold with a trailing slash, the way a path reads.
                text = if (entry is Subfolder) entry.name + "/" else entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (entry is Subfolder) FontWeight.SemiBold else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        when (entry) {
                            is Subfolder -> onOpenFolder(entry)
                            is Score -> onOpenScore(entry)
                        }
                    }
                    .padding(vertical = 16.dp),
            )
            HorizontalDivider()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LibraryScreenPreview() {
    VoltiTheme {
        LibraryContent(
            state = LibraryState.NoFolder,
            query = "",
            search = null,
            refreshing = false,
            onQuery = {},
            onSearchFocus = {},
            settings = ReaderSettings(),
            onCropMargins = {},
            onHalfPageTurns = {},
            onRefresh = {},
            onPickFolder = {},
            onOpenFolder = {},
            onOpenFound = {},
            onOpenScore = {},
            onOpenEnsembles = {},
            onBack = null,
        )
    }
}
