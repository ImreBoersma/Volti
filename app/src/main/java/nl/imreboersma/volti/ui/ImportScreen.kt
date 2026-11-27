package nl.imreboersma.volti.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.imreboersma.volti.MAX_SHARED
import nl.imreboersma.volti.drive.Rejection
import nl.imreboersma.volti.ui.theme.MaxContentWidth

/**
 * [count] PDFs another app shared with Volti: which ensemble do they go into? In the ensemble I then pick the folder,
 * or a new ensemble is made for them. [notReceived] couldn't be copied in; [dropped] were past the most one share
 * brings in.
 */
@Composable
fun ImportIntoScreen(
    count: Int,
    notReceived: List<Pair<String, Rejection>>,
    dropped: Int,
    onChoose: (ensembleId: Long) -> Unit,
    onNewEnsemble: () -> Unit,
    onCancel: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val ensembles by viewModel.ensembles.collectAsStateWithLifecycle()
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxSize().padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(onCancel, "Don't import")
                Text(
                    when (count) {
                        0 -> "Nothing to import"
                        1 -> "Import a PDF"
                        else -> "Import $count PDFs"
                    },
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
            if (notReceived.isNotEmpty()) {
                Text(notImportedText(notReceived, deviceName()), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
            }
            if (dropped > 0) {
                Text(
                    "Only the first $MAX_SHARED PDFs of a share are imported; $dropped more weren't.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            if (count == 0) return@Column // Nothing came in: only why.
            Text("Into which ensemble?", modifier = Modifier.padding(vertical = 16.dp))
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(ensembles.orEmpty(), key = { it.id }) { ensemble ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = "Import into ${ensemble.name}") { onChoose(ensemble.id) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(ensemble.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(ensemble.instrument, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
            OutlinedButton(onClick = onNewEnsemble, modifier = Modifier.padding(top = 12.dp)) { Text("New ensemble") }
        }
    }
}
