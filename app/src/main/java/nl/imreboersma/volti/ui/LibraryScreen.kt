package nl.imreboersma.volti.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.imreboersma.volti.data.Score
import nl.imreboersma.volti.ui.theme.VoltiTheme

/** Folder picker that hides cloud-backed roots. */
private class OpenLocalDocumentTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

@Composable
fun LibraryScreen(
    onNavigateToReader: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(OpenLocalDocumentTree()) { uri ->
        if (uri != null) viewModel.setFolder(uri)
    }

    LibraryContent(
        state = state,
        onPickFolder = { picker.launch((state as? LibraryState.Ready)?.folder) },
        onOpenScore = { onNavigateToReader() },
        modifier = modifier,
    )
}

@Composable
private fun LibraryContent(
    state: LibraryState,
    onPickFolder: () -> Unit,
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
                Column {
                    Text(text = "Library", style = MaterialTheme.typography.headlineLarge)
                    if (state is LibraryState.Ready) {
                        Text(text = DocumentsContract.getTreeDocumentId(state.folder))
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
                    state.scores == null -> Centered { Text(text = "Can't read this folder.") }
                    state.scores.isEmpty() -> Centered { Text(text = "No PDFs in this folder.") }
                    else -> ScoreList(state.scores, onOpenScore)
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
private fun ScoreList(scores: List<Score>, onOpenScore: (Score) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
        items(scores, key = { it.uri.toString() }) { score ->
            Text(
                text = score.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenScore(score) }
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
            onOpenScore = {},
        )
    }
}
