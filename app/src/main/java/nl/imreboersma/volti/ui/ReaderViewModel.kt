package nl.imreboersma.volti.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nl.imreboersma.volti.Reader
import nl.imreboersma.volti.data.PixelSize
import nl.imreboersma.volti.data.ScoreDocument
import nl.imreboersma.volti.data.openScore

sealed interface ReaderState {
    data object Loading : ReaderState

    /** The file could not be opened. Final. */
    data object Failed : ReaderState

    /** Page [index] is on screen; [bitmap] is null when that one page could not be rendered. */
    data class Page(val bitmap: Bitmap?, val index: Int, val pageCount: Int) : ReaderState
}

/**
 * A bitmap that left the screen is not drawn into for this long, about three frames at 60 Hz,
 * so the UI has drawn its replacement first.
 */
internal const val REUSE_DELAY_MS = 50L

class ReaderViewModel @JvmOverloads constructor(
    app: Application,
    handle: SavedStateHandle,
    private val open: (Uri) -> ScoreDocument = { openScore(app.contentResolver, it) },
) : AndroidViewModel(app) {
    private val uri = handle.toRoute<Reader>().uri.toUri()

    /** The renderer is not thread-safe: everything below this line is only touched on this one thread. */
    private val pdfThread = Dispatchers.IO.limitedParallelism(1)
    private var document: ScoreDocument? = null
    private var size: PixelSize? = null
    private var current = 0

    /** Rendered pages around [current]: previous, current and next, at [size]. */
    private val window = mutableMapOf<Int, Bitmap>()

    /** Pages that threw while rendering. Not retried: a broken page stays broken. */
    private val brokenPages = mutableSetOf<Int>()

    /** The bitmap on screen, and bitmaps that left it, with the time they left. */
    private var onScreen: Bitmap? = null
    private val leftScreen = ArrayDeque<Pair<Bitmap, Long>>()

    /** Main thread only. */
    private var lastSize: PixelSize? = null
    private var resizeJob: Job? = null

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state

    /**
     * Renders the current page and its neighbours at [size]. Called again on rotation; the file stays open.
     * Skips empty and unchanged sizes, and gives up for good once the file failed.
     */
    fun render(size: PixelSize) {
        if (size.width <= 0 || size.height <= 0 || size == lastSize || _state.value == ReaderState.Failed) return
        lastSize = size
        resizeJob?.cancel()
        resizeJob = onPdfThread {
            val doc = document ?: try {
                open(uri).also {
                    Log.i("Volti", "Opened $uri, ${it.pageCount} pages")
                    document = it
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Untrusted input: whatever the parser throws means this file can't be shown.
                Log.w("Volti", "Can't open $uri", e)
                _state.value = ReaderState.Failed
                return@onPdfThread
            }
            this.size = size
            window.clear()
            showCurrent(doc, since = SystemClock.uptimeMillis())
        }
    }

    fun next() = turn(+1)

    fun previous() = turn(-1)

    private fun turn(delta: Int) {
        if (_state.value !is ReaderState.Page) return
        val tapped = SystemClock.uptimeMillis()
        onPdfThread {
            val doc = document ?: return@onPdfThread
            if (size == null || current + delta !in 0 until doc.pageCount) return@onPdfThread
            current += delta
            showCurrent(doc, since = tapped)
        }
    }

    /**
     * Shows [current], from the window if it is there, then fills the window around it.
     * Stops before each neighbour if a newer size has arrived.
     */
    private suspend fun showCurrent(doc: ScoreDocument, since: Long) {
        val ready = current in window || current in brokenPages
        val bitmap = window[current] ?: renderInto(doc, current)
        publish(ReaderState.Page(bitmap, current, doc.pageCount))
        Log.d("Volti", "Page ${current + 1}: ${if (ready) "ready" else "rendered"} ${SystemClock.uptimeMillis() - since} ms after the tap")
        // Next before previous: reading forward is the common case.
        for (i in listOf(current + 1, current - 1)) {
            currentCoroutineContext().ensureActive()
            if (i in 0 until doc.pageCount && i !in window) renderInto(doc, i)
        }
    }

    private fun publish(page: ReaderState.Page) {
        if (page.bitmap !== onScreen) {
            onScreen?.let { leftScreen.addLast(it to SystemClock.uptimeMillis()) }
            onScreen = page.bitmap
        }
        _state.value = page
    }

    /**
     * Renders [index], into the bitmap of a page that fell out of the window when that bitmap is safe to
     * draw into: not on screen, and off screen for at least [REUSE_DELAY_MS]. Returns null for a page
     * that throws; the rest of the score stays readable.
     */
    private fun renderInto(doc: ScoreDocument, index: Int): Bitmap? {
        if (index in brokenPages) return null
        val now = SystemClock.uptimeMillis()
        while (leftScreen.isNotEmpty() && now - leftScreen.first().second >= REUSE_DELAY_MS) leftScreen.removeFirst()
        val dropped = window.keys.filter { it !in current - 1..current + 1 }.map { window.remove(it)!! }
        val reuse = dropped.firstOrNull { bitmap -> bitmap !== onScreen && leftScreen.none { it.first === bitmap } }
        return try {
            doc.renderPage(index, size!!, reuse).also { window[index] = it }
        } catch (e: Exception) {
            Log.w("Volti", "Can't render page ${index + 1} of $uri", e)
            brokenPages += index
            null
        }
    }

    private fun onPdfThread(block: suspend () -> Unit): Job = viewModelScope.launch(pdfThread) {
        if (_state.value != ReaderState.Failed) block()
    }

    override fun onCleared() {
        // viewModelScope is already cancelled. Close on pdfThread, so it waits for a render in flight.
        CoroutineScope(pdfThread).launch {
            try {
                document?.close()
            } catch (e: Exception) {
                Log.w("Volti", "Closing $uri failed", e)
            }
            document = null
        }
    }
}
