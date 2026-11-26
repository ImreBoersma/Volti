package nl.imreboersma.volti.annotate

import android.app.Application
import android.util.Log
import androidx.core.net.toUri
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.imreboersma.volti.Reader
import nl.imreboersma.volti.db.VoltiDatabase
import nl.imreboersma.volti.drive.Syncer
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

/** The pen's colours: the reader's ink, and the icon's red and blue. */
val PEN_COLORS = listOf(0xFF0F0F0F.toInt(), 0xFFC83823.toInt(), 0xFF2568BA.toInt())

/** In page units, on a page 1000 wide: about half a millimetre on an A4 part. */
private const val PEN_SIZE = 3f

/** How close the eraser must come to a stroke, in page units. */
private const val ERASER_RADIUS = 12f

/** That the score changed under my marks, for the reader to say. */
sealed interface ScoreChange {
    /** A newer version than the one I made my marks on: [previousUri] is the one before, to compare. */
    data class Changed(val previousUri: String) : ScoreChange

    /** This is that previous version, open to compare. */
    data object ShowingPrevious : ScoreChange
}

/** What a pen (or, while annotating, a finger) does on the page. */
sealed interface Tool {
    data object Pen : Tool
    data object Eraser : Tool

    /** Each tap places [symbol]. */
    data class Stamp(val symbol: Symbol) : Tool
}

/** A stroke on a page: what is stored, the ink stroke drawn from it, and its points for the eraser. */
class Drawn(val row: AnnotationStroke, val stroke: Stroke, val points: List<Point>)

/**
 * The annotations of the score in the reader: its strokes and symbols per page, the tools, and undo. Strokes are kept here and
 * written to the database behind them, so a finished stroke shows at once. The score is the reader's route: a Drive
 * file id it was opened with, or else the file's content.
 */
class AnnotationViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val dao = VoltiDatabase.get(app).annotations()
    private val files = VoltiDatabase.get(app).files()
    private var scoreKey: String? = null

    private val _strokes = MutableStateFlow<Map<Int, List<Drawn>>>(emptyMap())

    /** Per page index. */
    val strokes: StateFlow<Map<Int, List<Drawn>>> = _strokes.asStateFlow()

    private val _symbols = MutableStateFlow<Map<Int, List<AnnotationSymbol>>>(emptyMap())

    /** Per page index. */
    val symbols: StateFlow<Map<Int, List<AnnotationSymbol>>> = _symbols.asStateFlow()

    private val _change = MutableStateFlow<ScoreChange?>(null)
    val change: StateFlow<ScoreChange?> = _change.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /** The score's identity is known and its stored strokes are loaded: from now on a stroke can be kept. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _annotating = MutableStateFlow(false)

    /** The toolbar is out, and a finger draws instead of turning pages. A pen always draws. */
    val annotating: StateFlow<Boolean> = _annotating.asStateFlow()

    private val _tool = MutableStateFlow<Tool>(Tool.Pen)
    val tool: StateFlow<Tool> = _tool.asStateFlow()

    private val _color = MutableStateFlow(PEN_COLORS.first())
    val color: StateFlow<Int> = _color.asStateFlow()

    /** What undo takes back: marks added, or marks erased in one go. */
    private class Change(val added: Boolean, val strokes: List<AnnotationStroke>, val symbols: List<AnnotationSymbol>)

    private val changes = ArrayDeque<Change>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    /** Erased by the eraser now down; one undo brings them all back. */
    private var erasing: Pair<MutableList<AnnotationStroke>, MutableList<AnnotationSymbol>>? = null

    init {
        val route = handle.toRoute<Reader>()
        viewModelScope.launch {
            val key = route.scoreKey ?: localKey(route.uri) ?: return@launch
            scoreKey = key
            val rows = dao.forScore(key)
            val drawn = withContext(Dispatchers.Default) { rows.mapNotNull(::draw) }
            _strokes.value = drawn.groupBy { it.row.page }
            _symbols.value = dao.symbolsForScore(key).filter { Symbol.named(it.symbol) != null }.groupBy { it.page }
            // The previous version is to look at only: the marks are the new version's, and one written on the old
            // layout would land on the new one in the wrong place.
            _ready.value = !route.previous
            _change.value = if (route.previous) ScoreChange.ShowingPrevious else changeOf(key)
        }
    }

    /** A local file's annotations belong to its content: a rename keeps them, an edited PDF starts clean. */
    private suspend fun localKey(uri: String): String? = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("MD5")
            getApplication<Application>().contentResolver.openInputStream(uri.toUri())?.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            } ?: return@withContext null
            "local:" + digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Volti", "Can't read the score for its annotations", e)
            null // The reader says what's wrong with the file; without a key it just has no annotations.
        }
    }

    /**
     * Whether a Drive score changed since I opened it last: only worth saying when I have marks on it. Without marks
     * there is nothing to check, and the previous version is let go at once.
     */
    private suspend fun changeOf(key: String): ScoreChange? {
        val fileId = key.removePrefix("drive:").takeIf { key.startsWith("drive:") } ?: return null
        val previous = files.previousOf(fileId) ?: return null
        if (_strokes.value.values.all { it.isEmpty() } && _symbols.value.values.all { it.isEmpty() }) {
            files.checked(fileId)
            return null
        }
        return ScoreChange.Changed(Uri.fromFile(Syncer.get(getApplication()).fileFor(previous)).toString())
    }

    /** Not now: the notice goes for this opening; it comes back next time, and the previous version stays. */
    fun later() {
        _change.value = null
    }

    /** My marks fit the new version: the previous one can go. */
    fun marksChecked() {
        val key = scoreKey ?: return
        _change.value = null
        viewModelScope.launch { files.checked(key.removePrefix("drive:")) }
    }

    fun start() {
        _annotating.value = true
    }

    fun done() {
        _annotating.value = false
        _tool.value = Tool.Pen
    }

    /** The colour of what comes next: the pen, or the symbol being stamped. From the eraser, back to the pen. */
    fun pickColor(color: Int) {
        _color.value = color
        if (_tool.value == Tool.Eraser) _tool.value = Tool.Pen
    }

    fun pick(tool: Tool) {
        _tool.value = tool
    }

    fun brush(): Brush = brush(_color.value, PEN_SIZE)

    /**
     * A stroke the pen finished on [page] (the page it was started on), in page units, with the brush it was drawn
     * with. Shown at once, stored behind.
     */
    fun finished(page: Int, stroke: Stroke) {
        val key = scoreKey ?: return // Not reached: input only starts once the key is known.
        val row = AnnotationStroke(UUID.randomUUID().toString(), key, page, stroke.brush.colorIntArgb, stroke.brush.size, encode(stroke))
        // The stroke ink handed over, as it is: not decoded again, in the frame that must not flicker.
        add(listOf(Drawn(row, stroke, points(stroke.inputs))), emptyList())
        record(Change(added = true, listOf(row), emptyList()))
    }

    /** [symbol] stamped on [page] at [at], in page units, in the pen's colour. */
    fun place(page: Int, at: Point, symbol: Symbol) {
        val key = scoreKey ?: return
        val row = AnnotationSymbol(UUID.randomUUID().toString(), key, page, symbol.name, at.x, at.y, _color.value)
        add(emptyList(), listOf(row))
        record(Change(added = true, emptyList(), listOf(row)))
    }

    fun eraseStart() {
        erasing = mutableListOf<AnnotationStroke>() to mutableListOf()
    }

    /** The eraser at [at] on [page], in page units: every stroke and symbol it touches goes. */
    fun eraseAt(page: Int, at: Point) {
        val strokes = _strokes.value[page].orEmpty().filter { touches(it.points, at, ERASER_RADIUS) }.map { it.row }
        val symbols = _symbols.value[page].orEmpty().filter { touchesSymbol(Point(it.x, it.y), at, ERASER_RADIUS) }
        if (strokes.isEmpty() && symbols.isEmpty()) return
        remove(strokes, symbols)
        erasing?.let { (s, y) ->
            s += strokes
            y += symbols
        }
    }

    fun eraseEnd() {
        erasing?.let { (strokes, symbols) ->
            if (strokes.isNotEmpty() || symbols.isNotEmpty()) record(Change(added = false, strokes, symbols))
        }
        erasing = null
    }

    fun undo() {
        val change = changes.removeLastOrNull() ?: return
        _canUndo.value = changes.isNotEmpty()
        // Erased strokes come back from their stored form; one that can't be read stays out, also of the database.
        if (change.added) remove(change.strokes, change.symbols) else add(change.strokes.mapNotNull(::draw), change.symbols)
    }

    /** Onto the pages at once, into the database behind. */
    private fun add(drawn: List<Drawn>, symbols: List<AnnotationSymbol>) {
        val strokes = drawn.map { it.row }
        _strokes.update { pages -> drawn.groupBy { it.row.page }.entries.fold(pages) { acc, (page, list) -> acc + (page to acc[page].orEmpty() + list) } }
        _symbols.update { pages -> symbols.groupBy { it.page }.entries.fold(pages) { acc, (page, list) -> acc + (page to acc[page].orEmpty() + list) } }
        viewModelScope.launch {
            if (strokes.isNotEmpty()) dao.add(strokes)
            if (symbols.isNotEmpty()) dao.addSymbols(symbols)
        }
    }

    private fun remove(strokes: List<AnnotationStroke>, symbols: List<AnnotationSymbol>) {
        val strokeIds = strokes.map { it.id }.toSet()
        val symbolIds = symbols.map { it.id }.toSet()
        _strokes.update { pages -> pages.mapValues { (_, list) -> list.filter { it.row.id !in strokeIds } } }
        _symbols.update { pages -> pages.mapValues { (_, list) -> list.filter { it.id !in symbolIds } } }
        viewModelScope.launch {
            if (strokeIds.isNotEmpty()) dao.remove(strokeIds.toList())
            if (symbolIds.isNotEmpty()) dao.removeSymbols(symbolIds.toList())
        }
    }

    private fun record(change: Change) {
        changes.addLast(change)
        _canUndo.value = true
    }

    private fun brush(color: Int, size: Float): Brush =
        Brush.createWithColorIntArgb(StockBrushes.pressurePen(), color, size, epsilon = 0.1f)

    private fun encode(stroke: Stroke): ByteArray = ByteArrayOutputStream().also { stroke.inputs.encode(it) }.toByteArray()

    /** A stored stroke drawn again; null if its data can't be read (it is then left out, not shown wrongly). */
    private fun draw(row: AnnotationStroke): Drawn? = try {
        val inputs = StrokeInputBatch.decode(ByteArrayInputStream(row.inputs))
        Drawn(row, Stroke(brush(row.color, row.size), inputs), points(inputs))
    } catch (e: Exception) {
        Log.w("Volti", "Can't read a stored stroke", e)
        null
    }

    private fun points(inputs: StrokeInputBatch): List<Point> {
        val input = StrokeInput()
        return (0 until inputs.size).map { i -> inputs.populate(i, input).let { Point(it.x, it.y) } }
    }
}
