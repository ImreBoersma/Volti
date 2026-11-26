package nl.imreboersma.volti.setlist

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nl.imreboersma.volti.Reader
import nl.imreboersma.volti.db.VoltiDatabase
import nl.imreboersma.volti.db.fileKey
import nl.imreboersma.volti.drive.Syncer
import nl.imreboersma.volti.ui.theme.LocalReaderColors
import kotlin.time.Duration.Companion.seconds

/** How long reaching one piece may take: on a weak connection, better to skip it than to keep me waiting. */
private val REACH_TIMEOUT = 20.seconds

/**
 * From piece [from] of setlist [setlistId], the first piece in direction [step] that can be opened: on the device, or
 * downloaded now. One that can't (gone from Drive, offline, too slow) is skipped, and the reader's route says why.
 * Recorded as opened, as from the ensemble.
 */
suspend fun findPiece(app: Application, setlistId: Long, from: Int, step: Int): Reached {
    val db = VoltiDatabase.get(app)
    val setlist = db.setlists().observe(setlistId).first() ?: return Reached(null, null)
    val pieces = db.setlists().pieces(setlistId).first()
    val syncer = Syncer.get(app)
    val skipped = mutableListOf<String>()
    var position = from + step
    while (position in pieces.indices) {
        val piece = pieces[position]
        var problem: String? = "It isn't in Google Drive any more."
        if (piece.file != null) {
            problem = "Downloading it took too long."
            withTimeoutOrNull(REACH_TIMEOUT) { problem = syncer.fetch(setlist.ensembleId, piece.fileId, open = true) }
        }
        val md5 = if (problem == null) db.files().all(setlist.ensembleId).firstOrNull { it.fileId == piece.fileId }?.seenMd5 else null
        if (md5 != null) {
            val uri = Uri.fromFile(syncer.fileFor(md5)).toString()
            val why = skipped.joinToString("\n").ifEmpty { null }
            return Reached(Reader(uri, fileKey(piece.fileId), fromEnd = step < 0, setlistId = setlistId, position = position, skipped = why), why)
        }
        skipped += "${piece.file?.name?.removeSuffix(".pdf") ?: "A piece"} skipped. ${problem ?: "It couldn't be opened."}"
        position += step
    }
    return Reached(null, skipped.joinToString("\n").ifEmpty { null })
}

/** The piece to open, null when the setlist ends; and why the pieces on the way were skipped, if any were. */
class Reached(val reader: Reader?, val skipped: String?)

/** What the setlist line says; one that [fades] is only where I am, the others stay until I turn a page. */
class Line(val text: String, val fades: Boolean)

/**
 * The setlist around the score in the reader: which piece this is, and going on to the next or the previous one
 * when I turn past this one's ends. Only for a reader opened from a setlist.
 */
class SetlistPlayerViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val route = handle.toRoute<Reader>()

    private val _line = MutableStateFlow<Line?>(null)

    /** "2 / 9 · Cassiopeia", why pieces were skipped, that the next one is coming or that the setlist ends here. */
    val line: StateFlow<Line?> = _line.asStateFlow()

    private var going: Job? = null
    private var goingStep = 0

    init {
        val setlistId = route.setlistId
        if (setlistId != null) {
            viewModelScope.launch {
                val pieces = VoltiDatabase.get(app).setlists().pieces(setlistId).first()
                val name = pieces.getOrNull(route.position)?.file?.name?.removeSuffix(".pdf")
                val here = "${route.position + 1} / ${pieces.size}" + (name?.let { " · $it" } ?: "")
                _line.value = Line(listOfNotNull(route.skipped, here).joinToString("\n"), fades = route.skipped == null)
            }
        }
    }

    /** On to the piece [step] away (+1 next, -1 previous), in place of this one; or say the setlist ends here. */
    fun go(step: Int, onOpen: suspend (Reader) -> Unit) {
        val setlistId = route.setlistId ?: return
        if (going?.isActive == true) {
            if (step == goingStep) return // A second turn while the piece is being found.
            going?.cancel() // The other way: stay in this piece after all.
        }
        goingStep = step
        going = viewModelScope.launch {
            _line.value = Line(if (step > 0) "Opening the next piece…" else "Opening the previous piece…", fades = false)
            val reached = findPiece(getApplication(), setlistId, route.position, step)
            val next = reached.reader
            if (next == null) {
                val end = if (step > 0) "End of the setlist" else "Start of the setlist"
                _line.value = Line(listOfNotNull(reached.skipped, end).joinToString("\n"), fades = false)
            } else {
                onOpen(next)
            }
        }
    }

    /** I turned a page in this piece: whatever was said is past, and a piece still being found isn't wanted any more. */
    fun turned() {
        going?.cancel()
        if (_line.value?.fades == false) _line.value = null
    }

    fun shown() {
        _line.value = null
    }
}

/** The setlist line, at the bottom: which piece this is for a few seconds; a message until I turn a page. */
@Composable
fun BoxScope.SetlistLine(viewModel: SetlistPlayerViewModel) {
    val line by viewModel.line.collectAsStateWithLifecycle()
    val shown = line ?: return
    val text = shown.text
    if (shown.fades) {
        LaunchedEffect(shown) {
            delay(4.seconds)
            viewModel.shown()
        }
    }
    val colors = LocalReaderColors.current
    val shape = RoundedCornerShape(16.dp)
    Text(
        text,
        color = colors.message,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
            .clip(shape)
            .background(colors.surround)
            .border(1.dp, colors.message, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
