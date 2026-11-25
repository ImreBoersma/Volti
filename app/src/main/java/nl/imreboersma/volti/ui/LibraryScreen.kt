package nl.imreboersma.volti.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nl.imreboersma.volti.data.FolderStore
import nl.imreboersma.volti.ui.theme.VoltiTheme

/** Folder picker that hides cloud-backed roots. */
private class OpenLocalDocumentTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

/** Null until DataStore has answered, so the picker button does not flash on start. */
private class StoredFolder(val uri: Uri?)

@Composable
fun LibraryScreen(
    onNavigateToReader: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { FolderStore(context) }
    val stored by remember { store.folder.map(::StoredFolder) }
        .collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(OpenLocalDocumentTree()) { uri ->
        if (uri != null) scope.launch { store.setFolder(uri) }
    }

    LibraryContent(
        loading = stored == null,
        folder = stored?.uri,
        onPickFolder = { picker.launch(stored?.uri) },
        onNavigateToReader = onNavigateToReader,
        modifier = modifier,
    )
}

@Composable
private fun LibraryContent(
    loading: Boolean,
    folder: Uri?,
    onPickFolder: () -> Unit,
    onNavigateToReader: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Library",
                style = MaterialTheme.typography.headlineLarge,
            )
            if (loading) return@Column
            if (folder == null) {
                Button(
                    onClick = onPickFolder,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(text = "Choose folder")
                }
            } else {
                Text(
                    text = DocumentsContract.getTreeDocumentId(folder),
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedButton(
                    onClick = onPickFolder,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(text = "Change folder")
                }
                Button(
                    onClick = onNavigateToReader,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(text = "Open Reader")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LibraryScreenPreview() {
    VoltiTheme {
        LibraryContent(
            loading = false,
            folder = null,
            onPickFolder = {},
            onNavigateToReader = {},
        )
    }
}
