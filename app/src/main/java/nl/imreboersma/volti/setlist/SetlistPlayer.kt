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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
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
import nl.imreboersma.volti.drive.DriveProblem
import nl.imreboersma.volti.R
import nl.imreboersma.volti.ReaderTypes
import nl.imreboersma.volti.ui.count
import nl.imreboersma.volti.ui.skippedText
import kotlinx.serialization.Serializable
import nl.imreboersma.volti.ui.theme.LocalReaderColors
import kotlin.time.Duration.Companion.seconds

/** How long reaching one piece may take: on a weak connection, better to skip it than to keep me waiting. */
private val REACH_TIMEOUT = 20.seconds

/** Why a setlist piece was passed over: what went wrong reaching it. Worded in the reader, by [skippedText]. */
enum class SkipReason { Unreachable, SignedOut, Gone, ImportGone, DriveError, NotDownloaded, TooSlow, NotOpened }

/** A piece passed over: its name (null when its file is gone), why, and Google Drive's answer for a [SkipReason.DriveError]. */
@Serializable
data class Skip(val name: String?, val why: SkipReason, val status: Int = 0)

private fun DriveProblem.skipReason(): Pair<SkipReason, Int> = when (this) {
    DriveProblem.Unreachable -> SkipReason.Unreachable to 0
    DriveProblem.SignedOut -> SkipReason.SignedOut to 0
    DriveProblem.FileGone -> SkipReason.Gone to 0
    DriveProblem.ImportGone -> SkipReason.ImportGone to 0
    is DriveProblem.DriveError -> SkipReason.DriveError to status
    DriveProblem.NotShared, DriveProblem.NotAFolder -> SkipReason.NotDownloaded to 0
}

/**
 * From piece [from] of setlist [setlistId], the first piece in direction [step] that can be opened: on the device, or
 * downloaded now. One that can't (gone from Drive, offline, too slow) is skipped, and the reader's route says why.
 * Recorded as opened, as from the ensemble.
 */
suspend fun findPiece(app: Application, setlistId: Long, from: Int, step: Int): Reached {
    val db = VoltiDatabase.get(app)
    val setlist = db.setlists().observe(setlistId).first() ?: return Reached(null, emptyList())
    val pieces = db.setlists().pieces(setlistId).first()
    val syncer = Syncer.get(app)
    val skipped = mutableListOf<Skip>()
    var position = from + step
    while (position in pieces.indices) {
        val piece = pieces[position]
        var problem: Pair<SkipReason, Int>? = SkipReason.Gone to 0
        if (piece.file != null) {
            problem = SkipReason.TooSlow to 0
            withTimeoutOrNull(REACH_TIMEOUT) {
                problem = syncer.fetch(setlist.ensembleId, piece.fileId, open = true)?.skipReason()
            }
        }
        val md5 = if (problem == null) db.files().all(setlist.ensembleId).firstOrNull { it.fileId == piece.fileId }?.seenMd5 else null
        if (md5 != null) {
            val uri = Uri.fromFile(syncer.fileFor(md5)).toString()
            val reader = Reader(uri, fileKey(piece.fileId), fromEnd = step < 0, setlistId = setlistId, position = position, skipped = skipped.toList())
            return Reached(reader, skipped)
        }
        val (why, status) = problem ?: (SkipReason.NotOpened to 0)
        skipped += Skip(piece.file?.shownName?.removeSuffix(".pdf"), why, status)
        position += step
    }
    return Reached(null, skipped)
}

/** The piece to open, null when the setlist ends; and why the pieces on the way were skipped, if any were. */
class Reached(val reader: Reader?, val skipped: List<Skip>)

/** What the setlist line says, worded by [SetlistLine]. One that [fades] is only where I am; the others stay until I turn a page. */
sealed interface Line {
    val fades: Boolean

    /** Piece [position] (from 0) of [size], called [name]; after the pieces [skipped] on the way to it. */
    data class Here(val position: Int, val size: Int, val name: String?, val skipped: List<Skip>) : Line {
        override val fades get() = skipped.isEmpty()
    }

    /** Going on to the next piece ([step] +1) or the previous one (-1). */
    data class Opening(val step: Int) : Line {
        override val fades get() = false
    }

    /** No piece left in direction [step], after the pieces [skipped] on the way. */
    data class End(val step: Int, val skipped: List<Skip>) : Line {
        override val fades get() = false
    }
}

/**
 * The setlist around the score in the reader: which piece this is, and going on to the next or the previous one
 * when I turn past this one's ends. Only for a reader opened from a setlist.
 */
class SetlistPlayerViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val route = handle.toRoute<Reader>(ReaderTypes)

    private val _line = MutableStateFlow<Line?>(null)

    /** Which piece this is, why pieces were skipped, that the next one is coming or that the setlist ends here. */
    val line: StateFlow<Line?> = _line.asStateFlow()

    private var going: Job? = null
    private var goingStep = 0

    init {
        val setlistId = route.setlistId
        if (setlistId != null) {
            viewModelScope.launch {
                val pieces = VoltiDatabase.get(app).setlists().pieces(setlistId).first()
                val name = pieces.getOrNull(route.position)?.file?.shownName?.removeSuffix(".pdf")
                _line.value = Line.Here(route.position, pieces.size, name, route.skipped)
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
            _line.value = Line.Opening(step)
            val reached = findPiece(getApplication(), setlistId, route.position, step)
            val next = reached.reader
            if (next == null) {
                _line.value = Line.End(step, reached.skipped)
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
    val res = LocalResources.current
    val text = when (shown) {
        is Line.Here -> {
            val here = if (shown.name == null) {
                stringResource(R.string.setlist_here_unnamed, count(res, shown.position + 1), count(res, shown.size))
            } else {
                stringResource(R.string.setlist_here, count(res, shown.position + 1), count(res, shown.size), shown.name)
            }
            (shown.skipped.map { skippedText(res, it) } + here).joinToString("\n")
        }
        is Line.Opening -> stringResource(if (shown.step > 0) R.string.opening_next_piece else R.string.opening_previous_piece)
        is Line.End -> {
            val end = stringResource(if (shown.step > 0) R.string.end_of_setlist else R.string.start_of_setlist)
            (shown.skipped.map { skippedText(res, it) } + end).joinToString("\n")
        }
    }
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
