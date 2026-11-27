package nl.imreboersma.volti

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import nl.imreboersma.volti.drive.Incoming
import nl.imreboersma.volti.drive.Rejection
import nl.imreboersma.volti.drive.Syncer
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.withResumed
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import nl.imreboersma.volti.ui.EnsembleScreen
import nl.imreboersma.volti.annotate.AnnotationControls
import nl.imreboersma.volti.annotate.AnnotationViewModel
import nl.imreboersma.volti.annotate.PageAnnotations
import nl.imreboersma.volti.setlist.SetlistLine
import nl.imreboersma.volti.setlist.SetlistPlayerViewModel
import nl.imreboersma.volti.setlist.SetlistScreen
import nl.imreboersma.volti.setlist.SetlistsScreen
import nl.imreboersma.volti.ui.AddEnsembleScreen
import nl.imreboersma.volti.ui.HomeScreen
import nl.imreboersma.volti.ui.ImportIntoScreen
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
data class AddEnsemble(val importing: List<String> = emptyList())

/**
 * PDFs another app shared with Volti, copied in already ([incoming], each an encoded `Incoming`), waiting for me to
 * pick the ensemble they go into. [notReceived]: those that couldn't be copied, each `reason|name`. [dropped]: past
 * the most one share brings in.
 */
@Serializable
data class ImportInto(val incoming: List<String>, val notReceived: List<String> = emptyList(), val dropped: Int = 0)

@Serializable
object Settings

/** One ensemble's files; [id] is its row in the database. */
@Serializable
data class EnsembleFiles(val id: Long, val importing: List<String> = emptyList())

/** An ensemble's setlists. */
@Serializable
data class Setlists(val ensembleId: Long)

/** One setlist's pieces. */
@Serializable
data class SetlistPieces(val setlistId: Long)

/**
 * [uri] is the document URI of the score, as a string. [scoreKey] is what its annotations belong to when the opener
 * knows (`drive:<file id>` for an ensemble's score); without it, the file's content decides. [previous]: this is the
 * version before a change, opened to compare my marks against. [fromEnd]: open at the last page. [setlistId] and
 * [position]: the score is piece [position] of that setlist, so turning past its ends goes on to the next or previous
 * piece (the reader itself doesn't know; see `setlist/`). [skipped]: why the pieces before this one were passed over.
 */
@Serializable
data class Reader(
    val uri: String,
    val scoreKey: String? = null,
    val previous: Boolean = false,
    val fromEnd: Boolean = false,
    val setlistId: Long? = null,
    val position: Int = 0,
    val skipped: String? = null,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent system bars, also in the light theme: the default light scrim would put a whitish strip over
        // the cream. Icons still follow light or dark.
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        window.isNavigationBarContrastEnforced = false
        // Shared with Volti: only on the first start, a recreated activity has them in its back stack already, and
        // a start from Recents replays an old share whose files can't be read any more.
        if (savedInstanceState == null) share(intent)
        setContent {
            VoltiTheme {
                VoltiApp(shared = shared.receiveAsFlow())
            }
        }
    }

    private val shared = Channel<List<Uri>>(Channel.UNLIMITED)

    /** Shared while Volti is open: one Volti, so it comes here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        share(intent)
    }

    private fun share(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uris = sharedPdfs(intent).filter { fromAnotherApp(this, it) }
        if (uris.isNotEmpty()) shared.trySend(uris)
    }

    /** Esc does what Back does, on every screen: a keyboard user has no Back key. Only an Esc nothing else took. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_ESCAPE) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) onBackPressedDispatcher.onBackPressed()
        return true
    }
}

/**
 * Only files another app offers through its provider: never a path, and never one of Volti's own files, which Volti
 * could read but the other app couldn't.
 */
fun fromAnotherApp(context: Context, uri: Uri): Boolean = uri.scheme == ContentResolver.SCHEME_CONTENT &&
    context.packageManager.resolveContentProvider(uri.authority.orEmpty(), 0)?.packageName != context.packageName

/** The PDFs an Open with or a Share hands Volti; none for an ordinary start. */
fun sharedPdfs(intent: Intent): List<Uri> = when (intent.action) {
    Intent.ACTION_VIEW -> listOfNotNull(intent.data)
    Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
    Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    else -> emptyList()
}

/** The most PDFs one share brings in: a route carries them, and its saved state has a size limit. */
const val MAX_SHARED = 50

/** [shared]: PDFs shared with Volti, to import: copied in at once, then I'm asked which ensemble they go into. */
@Composable
fun VoltiApp(navController: NavHostController = rememberNavController(), shared: Flow<List<Uri>> = emptyFlow()) {
    val context = LocalContext.current
    LaunchedEffect(shared) {
        shared.collect { uris ->
            val received = Syncer.get(context).receive(uris.take(MAX_SHARED)).await()
            navController.navigate(
                ImportInto(
                    received.incoming.map { it.encode() },
                    received.problems.map { (name, why) -> "${why.name}|$name" },
                    dropped = (uris.size - MAX_SHARED).coerceAtLeast(0),
                ),
            )
        }
    }
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
                onAddEnsemble = { go(AddEnsemble()) },
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
                        val importing = entry.toRoute<AddEnsemble>().importing
                        navController.navigate(EnsembleFiles(id, importing)) { popUpTo<AddEnsemble> { inclusive = true } }
                    }
                },
            )
        }
        composable<ImportInto> { entry ->
            val route = entry.toRoute<ImportInto>()
            // In place of this question: back from the ensemble goes to the library.
            fun go(next: Any) {
                if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    navController.navigate(next) { popUpTo<ImportInto> { inclusive = true } }
                }
            }
            ImportIntoScreen(
                count = route.incoming.size,
                notReceived = route.notReceived.map { it.split('|', limit = 2).let { (why, name) -> name to Rejection.valueOf(why) } },
                dropped = route.dropped,
                onChoose = { id -> go(EnsembleFiles(id, route.incoming)) },
                onNewEnsemble = { go(AddEnsemble(route.incoming)) },
                onCancel = dropUnlessResumed {
                    Syncer.get(context).discard(route.incoming.map(Incoming::decode))
                    navController.popBackStack()
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
        composable<SetlistPieces> { entry ->
            SetlistScreen(
                onPlay = { reader -> entry.lifecycle.withResumed { navController.navigate(reader) } },
                onBack = dropUnlessResumed { navController.popBackStack() },
                onDeleted = dropUnlessResumed { navController.popBackStack() },
            )
        }
        composable<Reader> { entry ->
            // Annotations go over the reader through its slots; the reader itself doesn't know about them.
            val annotations: AnnotationViewModel = viewModel()
            // So does a setlist: past a piece's ends, the next or previous piece takes this one's place, so Back
            // still goes to the setlist.
            val inSetlist = entry.toRoute<Reader>().setlistId != null
            val setlist: SetlistPlayerViewModel? = if (inSetlist) viewModel() else null
            if (setlist != null) {
                // The reader's own ViewModel, for its turns: one within the piece is heard here, not by the reader.
                val reader: ReaderViewModel = viewModel()
                LaunchedEffect(reader, setlist) {
                    reader.state.filterIsInstance<ReaderState.Page>().map { it.index to it.half }.distinctUntilChanged()
                        .drop(1).collect { setlist.turned() }
                }
            }
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
                                // To look at only: not as a piece of a setlist, and from its start.
                                navController.navigate(
                                    entry.toRoute<Reader>().copy(uri = uri, previous = true, fromEnd = false, setlistId = null, skipped = null),
                                )
                            }
                        },
                    )
                    // Not over the annotation toolbar, which is at the bottom too.
                    val annotating by annotations.annotating.collectAsStateWithLifecycle()
                    if (!annotating) setlist?.let { SetlistLine(it) }
                },
                onEdge = setlist?.let { player ->
                    { step ->
                        // Found while the screen was off: opened once I'm back, not dropped.
                        player.go(step) { next -> entry.lifecycle.withResumed { navController.navigate(next) { popUpTo<Reader> { inclusive = true } } } }
                    }
                },
            )
        }
    }
}
