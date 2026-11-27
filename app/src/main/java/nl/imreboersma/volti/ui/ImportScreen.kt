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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.imreboersma.volti.MAX_SHARED
import nl.imreboersma.volti.R
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
    val res = LocalResources.current
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxSize().padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(onCancel, stringResource(R.string.dont_import))
                Text(
                    if (count == 0) {
                        stringResource(R.string.nothing_to_import)
                    } else {
                        pluralStringResource(R.plurals.import_pdfs, count, count(res, count))
                    },
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
            if (notReceived.isNotEmpty()) {
                Text(notImportedText(res, notReceived, device()), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
            }
            if (dropped > 0) {
                Text(
                    pluralStringResource(R.plurals.share_dropped, dropped, count(res, MAX_SHARED), count(res, dropped)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            if (count == 0) return@Column // Nothing came in: only why.
            Text(stringResource(R.string.into_which_ensemble), modifier = Modifier.padding(vertical = 16.dp))
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(ensembles.orEmpty(), key = { it.id }) { ensemble ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = stringResource(R.string.import_into, ensemble.name)) { onChoose(ensemble.id) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(ensemble.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(ensemble.instrument, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
            OutlinedButton(onClick = onNewEnsemble, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.new_ensemble)) }
        }
    }
}
