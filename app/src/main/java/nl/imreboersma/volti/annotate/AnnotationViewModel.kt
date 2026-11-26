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

enum class Tool { Pen, Eraser }

/** A stroke on a page: what is stored, the ink stroke drawn from it, and its points for the eraser. */
class Drawn(val row: AnnotationStroke, val stroke: Stroke, val points: List<Point>)

/**
 * The annotations of the score in the reader: its strokes per page, the pen, and undo. Strokes are kept here and
 * written to the database behind them, so a finished stroke shows at once. The score is the reader's route: a Drive
 * file id it was opened with, or else the file's content.
 */
class AnnotationViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val dao = VoltiDatabase.get(app).annotations()
    private var scoreKey: String? = null

    private val _strokes = MutableStateFlow<Map<Int, List<Drawn>>>(emptyMap())

    /** Per page index. */
    val strokes: StateFlow<Map<Int, List<Drawn>>> = _strokes.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /** The score's identity is known and its stored strokes are loaded: from now on a stroke can be kept. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _annotating = MutableStateFlow(false)

    /** The toolbar is out, and a finger draws instead of turning pages. A pen always draws. */
    val annotating: StateFlow<Boolean> = _annotating.asStateFlow()

    private val _tool = MutableStateFlow(Tool.Pen)
    val tool: StateFlow<Tool> = _tool.asStateFlow()

    private val _color = MutableStateFlow(PEN_COLORS.first())
    val color: StateFlow<Int> = _color.asStateFlow()

    /** What undo takes back: strokes added, or strokes erased in one go. */
    private sealed interface Change {
        val rows: List<AnnotationStroke>

        class Added(override val rows: List<AnnotationStroke>) : Change
        class Erased(override val rows: List<AnnotationStroke>) : Change
    }

    private val changes = ArrayDeque<Change>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    /** Erased by the eraser now down; one undo brings them all back. */
    private var erasing: MutableList<AnnotationStroke>? = null

    init {
        val route = handle.toRoute<Reader>()
        viewModelScope.launch {
            val key = route.scoreKey ?: localKey(route.uri) ?: return@launch
            scoreKey = key
            val rows = dao.forScore(key)
            val drawn = withContext(Dispatchers.Default) { rows.mapNotNull(::draw) }
            _strokes.value = drawn.groupBy { it.row.page }
            _ready.value = true
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

    fun start() {
        _annotating.value = true
    }

    fun done() {
        _annotating.value = false
        _tool.value = Tool.Pen
    }

    fun pick(color: Int) {
        _color.value = color
        _tool.value = Tool.Pen
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
        _strokes.update { it + (page to it[page].orEmpty() + Drawn(row, stroke, points(stroke.inputs))) }
        record(Change.Added(listOf(row)))
        viewModelScope.launch { dao.add(listOf(row)) }
    }

    fun eraseStart() {
        erasing = mutableListOf()
    }

    /** The eraser at [at] on [page], in page units: every stroke it touches goes. */
    fun eraseAt(page: Int, at: Point) {
        val hits = _strokes.value[page].orEmpty().filter { touches(it.points, at, ERASER_RADIUS) }
        if (hits.isEmpty()) return
        _strokes.update { it + (page to it[page].orEmpty() - hits.toSet()) }
        erasing?.addAll(hits.map { it.row })
        viewModelScope.launch { dao.remove(hits.map { it.row.id }) }
    }

    fun eraseEnd() {
        erasing?.takeIf { it.isNotEmpty() }?.let { record(Change.Erased(it)) }
        erasing = null
    }

    fun undo() {
        val change = changes.removeLastOrNull() ?: return
        _canUndo.value = changes.isNotEmpty()
        val rows = change.rows
        when (change) {
            is Change.Added -> {
                val ids = rows.map { it.id }.toSet()
                _strokes.update { pages -> pages.mapValues { (_, list) -> list.filter { it.row.id !in ids } } }
                viewModelScope.launch { dao.remove(ids.toList()) }
            }
            is Change.Erased -> {
                val back = rows.mapNotNull(::draw)
                _strokes.update { pages -> back.groupBy { it.row.page }.entries.fold(pages) { acc, (page, list) -> acc + (page to acc[page].orEmpty() + list) } }
                viewModelScope.launch { dao.add(rows) }
            }
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
