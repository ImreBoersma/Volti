package nl.imreboersma.volti

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import nl.imreboersma.volti.ui.LibraryScreen
import nl.imreboersma.volti.ui.ReaderScreen
import nl.imreboersma.volti.ui.theme.VoltiTheme

@Serializable
object Library

/** [uri] is the document URI of the score, as a string. */
@Serializable
data class Reader(val uri: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoltiTheme {
                VoltiApp()
            }
        }
    }
}

@Composable
fun VoltiApp() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Library,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable<Library> {
            LibraryScreen(
                onOpenScore = { score -> navController.navigate(Reader(score.uri.toString())) },
            )
        }
        composable<Reader> {
            ReaderScreen()
        }
    }
}
