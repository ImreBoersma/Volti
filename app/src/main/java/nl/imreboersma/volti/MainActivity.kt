package nl.imreboersma.volti

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import nl.imreboersma.volti.ui.EnsembleScreen
import nl.imreboersma.volti.annotate.AnnotationControls
import nl.imreboersma.volti.annotate.AnnotationViewModel
import nl.imreboersma.volti.annotate.PageAnnotations
import nl.imreboersma.volti.setlist.SetlistScreen
import nl.imreboersma.volti.setlist.SetlistsScreen
import nl.imreboersma.volti.ui.AddEnsembleScreen
import nl.imreboersma.volti.ui.HomeScreen
import nl.imreboersma.volti.ui.SettingsScreen
import nl.imreboersma.volti.ui.LibraryScreen
import nl.imreboersma.volti.ui.ReaderScreen
import nl.imreboersma.volti.ui.ReaderState
import nl.imreboersma.volti.ui.ReaderViewModel
import nl.imreboersma.volti.ui.StageMode
import nl.imreboersma.volti.ui.theme.VoltiTheme

/** Where the app opens: the ensembles and the folder on the tablet. */
@Serializable
object Home

/** The folder on the tablet. */
@Serializable
object LocalLibrary

@Serializable
object AddEnsemble

@Serializable
object Settings

/** One ensemble's files; [id] is its row in the database. */
@Serializable
data class EnsembleFiles(val id: Long)

/** An ensemble's setlists. */
@Serializable
data class Setlists(val ensembleId: Long)

/** One setlist's pieces. */
@Serializable
data class SetlistPieces(val setlistId: Long)

/**
 * [uri] is the document URI of the score, as a string. [scoreKey] is what its annotations belong to when the opener
 * knows (`drive:<file id>` for an ensemble's score); without it, the file's content decides. [previous]: this is the
 * version before a change, opened to compare my marks against.
 */
@Serializable
data class Reader(val uri: String, val scoreKey: String? = null, val previous: Boolean = false)

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

    /** Esc does what Back does, on every screen: a keyboard user has no Back key. Only an Esc nothing else took. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_ESCAPE) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) onBackPressedDispatcher.onBackPressed()
        return true
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
        startDestination = Home,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable<Home> { entry ->
            // As for the reader: a second tap during a crossfade must not open a screen twice.
            fun go(route: Any) {
                if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navController.navigate(route)
            }
            HomeScreen(
                onOpenEnsemble = { id -> go(EnsembleFiles(id)) },
                onAddEnsemble = { go(AddEnsemble) },
                onOpenLocal = { go(LocalLibrary) },
                onOpenSettings = { go(Settings) },
            )
        }
        composable<Settings> {
            SettingsScreen(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable<LocalLibrary> { entry ->
            LibraryScreen(
                onOpenScore = { score ->
                    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        navController.navigate(Reader(score.uri.toString()))
                    }
                },
                onHome = dropUnlessResumed { navController.popBackStack() },
            )
        }
        composable<AddEnsemble> { entry ->
            AddEnsembleScreen(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onAdded = { id ->
                    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        // In place of the form: back from the new ensemble goes to the library, not to the form.
                        navController.navigate(EnsembleFiles(id)) { popUpTo<AddEnsemble> { inclusive = true } }
                    }
                },
            )
        }
        composable<EnsembleFiles> { entry ->
            EnsembleScreen(
                onOpenScore = { uri, scoreKey ->
                    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        navController.navigate(Reader(uri, scoreKey))
                    }
                },
                onOpenSetlists = { id ->
                    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navController.navigate(Setlists(id))
                },
                onBack = dropUnlessResumed { navController.popBackStack() },
            )
        }
        composable<Setlists> { entry ->
            SetlistsScreen(
                onOpen = { id ->
                    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navController.navigate(SetlistPieces(id))
                },
                onBack = dropUnlessResumed { navController.popBackStack() },
            )
        }
        composable<SetlistPieces> {
            SetlistScreen(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onDeleted = dropUnlessResumed { navController.popBackStack() },
            )
        }
        composable<Reader> { entry ->
            // Annotations go over the reader through its slots; the reader itself doesn't know about them.
            val annotations: AnnotationViewModel = viewModel()
            ReaderScreen(
                // Only while the reader is the resumed screen: a second tap during the crossfade would also pop the
                // library and leave nothing on screen.
                onBack = dropUnlessResumed { navController.popBackStack() },
                overlay = { page -> PageAnnotations(page, annotations) },
                controls = {
                    AnnotationControls(
                        annotations,
                        onOpenPrevious = { uri ->
                            if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                navController.navigate(entry.toRoute<Reader>().copy(uri = uri, previous = true))
                            }
                        },
                    )
                },
            )
        }
    }
}
