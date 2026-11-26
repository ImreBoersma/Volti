package nl.imreboersma.volti

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import nl.imreboersma.volti.ui.LibraryScreen
import nl.imreboersma.volti.ui.ReaderScreen
import nl.imreboersma.volti.ui.ReaderState
import nl.imreboersma.volti.ui.ReaderViewModel
import nl.imreboersma.volti.ui.StageMode
import nl.imreboersma.volti.ui.theme.VoltiTheme

@Serializable
object Library

/** [uri] is the document URI of the score, as a string. */
@Serializable
data class Reader(val uri: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent system bars, also in the light theme: the default light scrim would put a whitish strip over
        // the cream. Icons still follow light or dark.
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        window.isNavigationBarContrastEnforced = false
        setContent {
            VoltiTheme {
                VoltiApp()
            }
        }
    }
}

@Composable
fun VoltiApp(navController: NavHostController = rememberNavController()) {
    // Stage mode follows the current destination, not the reader screen's own lifetime: the outgoing
    // screen lives on through the 700 ms crossfade, and its clean-up would switch off the next reader.
    val entry by navController.currentBackStackEntryAsState()
    if (entry?.destination?.hasRoute<Reader>() == true) {
        // The same ViewModel instance ReaderScreen uses: scoped to this back stack entry.
        val reader = viewModel<ReaderViewModel>(viewModelStoreOwner = entry!!)
        val state by reader.state.collectAsStateWithLifecycle()
        // A file that can't be opened doesn't need the screen on.
        if (state !is ReaderState.Failed) StageMode()
    }
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
            // Only while the reader is the resumed screen: a second tap during the crossfade would also pop the
            // library and leave nothing on screen.
            ReaderScreen(onBack = dropUnlessResumed { navController.popBackStack() })
        }
    }
}
