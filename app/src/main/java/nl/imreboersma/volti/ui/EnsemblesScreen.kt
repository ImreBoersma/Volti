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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** My ensembles, and a form to add one from a shared folder's link. */
@Composable
fun EnsemblesScreen(onOpen: (Long) -> Unit, viewModel: EnsemblesViewModel = viewModel()) {
    val ensembles by viewModel.ensembles.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var instrument by rememberSaveable { mutableStateOf("") }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Ensembles", style = MaterialTheme.typography.headlineLarge) }
            items(ensembles.orEmpty(), key = { it.id }) { ensemble ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(ensemble.id) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(ensemble.name, style = MaterialTheme.typography.titleMedium)
                    Text(ensemble.instrument, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** One ensemble's PDFs, straight from Drive. */
@Composable
fun EnsembleScreen(onRemoved: () -> Unit, viewModel: EnsembleViewModel = viewModel()) {
    val ensemble by viewModel.ensemble.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.consented(it.data)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(ensemble?.name.orEmpty(), style = MaterialTheme.typography.headlineLarge)
                    Text(ensemble?.instrument.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { viewModel.remove(onRemoved) }) { Text("Remove") }
            }
            when (val s = state) {
                EnsembleState.Loading -> Message("Listing the folder in Google Drive…")
                is EnsembleState.NeedsConsent -> Middle {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Volti needs read-only access to Google Drive to list this folder.")
                        Button(
                            onClick = { consent.launch(IntentSenderRequest.Builder(s.intent).build()) },
                            modifier = Modifier.padding(top = 16.dp),
                        ) { Text("Sign in to Google Drive") }
                    }
                }
                is EnsembleState.Failed -> Middle {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.message)
                        Button(onClick = viewModel::load, modifier = Modifier.padding(top = 16.dp)) { Text("Try again") }
                    }
                }
                is EnsembleState.Listed -> Listing(s)
            }
        }
    }
}

@Composable
private fun Listing(state: EnsembleState.Listed) {
    val groups = state.pdfs.groupBy { it.folders }
    Text(
        text = buildString {
            append("${state.folderName}: ${state.pdfs.size} PDFs")
            if (state.folders > 0) append(" in ${state.folders + 1} folders")
            if (state.stopped) append(". Stopped at 5,000 entries: the deepest files are left out")
        },
        modifier = Modifier.padding(vertical = 16.dp),
    )
    LazyColumn {
        groups.forEach { (folders, entries) ->
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
            items(entries, key = { it.file.id + "@" + folders.joinToString("\u0000") }) { entry ->
                Text(entry.file.name, modifier = Modifier.padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun Middle(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

@Composable
private fun Message(text: String) = Middle { Text(text) }
