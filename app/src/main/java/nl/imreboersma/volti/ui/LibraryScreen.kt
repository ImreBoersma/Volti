package nl.imreboersma.volti.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.imreboersma.volti.data.LibraryEntry
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
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Tells Android when the library is really there, list included: logged as "Fully drawn" at cold start.
    ReportDrawnWhen { state != LibraryState.Loading }
    val picker = rememberLauncherForActivityResult(OpenLocalDocumentTree()) { uri ->
        if (uri != null) viewModel.setFolder(uri)
    }

    // Back goes up a folder; at the top it leaves the app as usual. Only while the library is the active
    // screen: during the crossfade into the reader, back must close the score, not go up a folder.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    BackHandler(
        enabled = lifecycle.isAtLeast(Lifecycle.State.RESUMED) && (state as? LibraryState.Ready)?.path?.isNotEmpty() == true,
    ) { viewModel.up() }

    LibraryContent(
        state = state,
        onPickFolder = { picker.launch((state as? LibraryState.Ready)?.folder) },
        onOpenFolder = { subfolder -> (state as? LibraryState.Ready)?.let { viewModel.open(it, subfolder) } },
        onOpenScore = onOpenScore,
        modifier = modifier,
    )
}

@Composable
private fun LibraryContent(
    state: LibraryState,
    onPickFolder: () -> Unit,
    onOpenFolder: (Subfolder) -> Unit,
    onOpenScore: (Score) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = (state as? LibraryState.Ready)?.name ?: "Library",
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    if (state is LibraryState.Ready && state.path.isNotEmpty()) {
                        Text(text = state.path.joinToString(" / ") { it.name })
                    }
                }
                if (state is LibraryState.Ready) {
                    OutlinedButton(onClick = onPickFolder) { Text(text = "Change folder") }
                }
            }
            when (state) {
                LibraryState.Loading -> Unit
                LibraryState.NoFolder -> Centered {
                    Button(onClick = onPickFolder) { Text(text = "Choose folder") }
                }
                is LibraryState.Ready -> when {
                    state.entries == null -> Centered { Text(text = "Can't read this folder.") }
                    state.entries.isEmpty() -> Centered { Text(text = "No PDFs or folders here.") }
                    else -> EntryList(state.entries, onOpenFolder, onOpenScore)
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
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
            onPickFolder = {},
            onOpenFolder = {},
            onOpenScore = {},
        )
    }
}
