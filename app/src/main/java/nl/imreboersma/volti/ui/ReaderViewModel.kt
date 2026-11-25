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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.imreboersma.volti.Reader
import nl.imreboersma.volti.data.PasswordProtectedException
import nl.imreboersma.volti.data.PixelSize
import nl.imreboersma.volti.data.ScoreDocument
import nl.imreboersma.volti.data.openScore

sealed interface ReaderState {
    data object Loading : ReaderState

    /** The file could not be opened. Final. */
    data class Failed(val problem: Problem = Problem.Unreadable) : ReaderState

    enum class Problem { Unreadable, PasswordProtected, TooSlow }

    /** Page [index] is on screen; [bitmap] is null when that one page could not be rendered. */
    data class Page(val bitmap: Bitmap?, val index: Int, val pageCount: Int) : ReaderState
}

/**
 * A bitmap is not drawn into until this long after it was replaced, about three frames at 60 Hz:
 * replaced in the state, and replaced on screen as reported by [ReaderViewModel.onPresented].
 */
internal const val REUSE_DELAY_MS = 50L

class ReaderViewModel @JvmOverloads constructor(
    app: Application,
    handle: SavedStateHandle,
    private val open: (Uri) -> ScoreDocument = { openScore(app.contentResolver, it) },
    private val openTimeoutMs: Long = 10_000,
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

    /** The bitmap last published, and bitmaps it replaced, with the time they were replaced. */
    private var onScreen: Bitmap? = null
    private val leftScreen = ArrayDeque<Pair<Bitmap, Long>>()

    /**
     * What the UI really put on screen, from [onDrawn] and [onPresented]. The state alone is not enough:
     * a busy main thread or render thread can keep an older page on screen well after a newer one was
     * published. Written on the main thread, read on the PDF thread: guarded by [drawnLock].
     */
    private val drawnLock = Any()

    /**
     * Bitmaps in recorded frames that may still be on screen, oldest first: the one presented last and any
     * drawn since. Frames reach the screen in recording order.
     */
    private val drawn = ArrayDeque<Bitmap>()
    private val stoppedDrawing = ArrayDeque<Pair<Bitmap, Long>>()

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
        if (size.width <= 0 || size.height <= 0 || size == lastSize || _state.value is ReaderState.Failed) return
        if (lastSize == null) watchFirstPage()
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
                val problem = if (e is PasswordProtectedException) ReaderState.Problem.PasswordProtected else ReaderState.Problem.Unreadable
                _state.value = ReaderState.Failed(problem)
                return@onPdfThread
            }
            this.size = size
            window.clear()
            showCurrent(doc, since = SystemClock.uptimeMillis())
        }
    }

    /**
     * Native rendering can't be interrupted, and the Play renderer runs every document under one
     * process-wide lock. A file that hangs it would leave a blank reader with the screen kept on.
     * After [openTimeoutMs] without a first page, give up on this file and say so.
     */
    private fun watchFirstPage() {
        viewModelScope.launch {
            delay(openTimeoutMs)
            if (_state.compareAndSet(ReaderState.Loading, ReaderState.Failed(ReaderState.Problem.TooSlow))) {
                Log.w("Volti", "Gave up on $uri after $openTimeoutMs ms")
            }
        }
    }

    /** Called by the UI when it records a frame with [bitmap]: from now on it may be on screen. */
    fun onDrawn(bitmap: Bitmap) = synchronized(drawnLock) {
        if (drawn.lastOrNull() !== bitmap) drawn.addLast(bitmap)
    }

    /**
     * Called by the UI once the frame showing [bitmap] (null: no page) has been committed to the screen.
     * Bitmaps from older frames have then left it; bitmaps from newer frames are still on their way.
     */
    fun onPresented(bitmap: Bitmap?) = synchronized(drawnLock) {
        val now = SystemClock.uptimeMillis()
        val older = if (bitmap == null) drawn.size else drawn.indexOfFirst { it === bitmap }.coerceAtLeast(0)
        repeat(older) { stoppedDrawing.addLast(drawn.removeFirst() to now) }
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
        // Not over a Failed: the time limit may have given up on this file while the render ran.
        _state.update { if (it is ReaderState.Failed) it else page }
    }

    /**
     * Renders [index], into the bitmap of a page that fell out of the window when that bitmap is safe to
     * draw into. Returns null for a page that throws; the rest of the score stays readable.
     */
    private fun renderInto(doc: ScoreDocument, index: Int): Bitmap? {
        if (index in brokenPages) return null
        val dropped = window.keys.filter { it !in current - 1..current + 1 }.map { window.remove(it)!! }
        val reuse = if (dropped.isEmpty()) null else safeToDrawInto(dropped)
        return try {
            doc.renderPage(index, size!!, reuse).also { window[index] = it }
        } catch (e: Exception) {
            Log.w("Volti", "Can't render page ${index + 1} of $uri", e)
            brokenPages += index
            null
        }
    }

    /**
     * The first of [candidates] that is not the published page and not possibly on screen, and was replaced
     * in both at least [REUSE_DELAY_MS] ago. Drawing into anything else could show a half-rendered page.
     */
    private fun safeToDrawInto(candidates: List<Bitmap>): Bitmap? {
        val now = SystemClock.uptimeMillis()
        while (leftScreen.isNotEmpty() && now - leftScreen.first().second >= REUSE_DELAY_MS) leftScreen.removeFirst()
        return synchronized(drawnLock) {
            while (stoppedDrawing.isNotEmpty() && now - stoppedDrawing.first().second >= REUSE_DELAY_MS) stoppedDrawing.removeFirst()
            candidates.firstOrNull { bitmap ->
                bitmap !== onScreen &&
                    leftScreen.none { it.first === bitmap } &&
                    drawn.none { it === bitmap } &&
                    stoppedDrawing.none { it.first === bitmap }
            }
        }
    }

    private fun onPdfThread(block: suspend () -> Unit): Job = viewModelScope.launch(pdfThread) {
        if (_state.value !is ReaderState.Failed) block()
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
