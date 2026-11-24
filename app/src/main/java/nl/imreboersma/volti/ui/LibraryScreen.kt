package nl.imreboersma.volti.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import nl.imreboersma.volti.ui.theme.VoltiTheme

@Composable
fun LibraryScreen(
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
            Button(
                onClick = onNavigateToReader,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(text = "Open Reader")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LibraryScreenPreview() {
    VoltiTheme {
        LibraryScreen(onNavigateToReader = {})
    }
}
