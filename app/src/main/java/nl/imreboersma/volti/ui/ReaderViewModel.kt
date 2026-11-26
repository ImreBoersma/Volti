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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import nl.imreboersma.volti.Reader
import nl.imreboersma.volti.data.PageArea
import nl.imreboersma.volti.data.PasswordProtectedException
import nl.imreboersma.volti.data.PixelSize
import nl.imreboersma.volti.data.ReaderSettings
import nl.imreboersma.volti.data.ReaderSettingsStore
import nl.imreboersma.volti.data.ScoreDocument
import nl.imreboersma.volti.data.samplePages
import nl.imreboersma.volti.render.openIsolated

sealed interface ReaderState {
    data object Loading : ReaderState

    /** The file could not be opened. Final. */
    data class Failed(val problem: Problem = Problem.Unreadable) : ReaderState

    enum class Problem { Unreadable, PasswordProtected, TooSlow }

    /**
     * Page [index] is on screen; [bitmap] is null when that one page could not be rendered. In a [half] turn the
     * top half of the screen already shows the next page, [top] (null if that page could not be rendered), and
     * the bottom half still shows the end of this one.
     */
    data class Page(
        val bitmap: Bitmap?,
        val index: Int,
        val pageCount: Int,
        val half: Boolean = false,
        val top: Bitmap? = null,
        /** The part of each page the bitmaps show: the score's margin crop. */
        val area: PageArea = PageArea.WHOLE,
    ) : ReaderState {
        /** Everything this state puts on screen. */
        val bitmaps get() = listOfNotNull(bitmap, top)
    }
}

/**
 * A bitmap is not drawn into until this long after it was replaced, about three frames at 60 Hz:
 * replaced in the state, and replaced on screen as reported by [ReaderViewModel.onPresented].
 */
internal const val REUSE_DELAY_MS = 50L

class ReaderViewModel @JvmOverloads constructor(
    app: Application,
    handle: SavedStateHandle,
    private val open: (Uri) -> ScoreDocument = { openIsolated(app, it) },
    private val openTimeoutMs: Long = 10_000,
    private val loadSettings: suspend () -> ReaderSettings = { ReaderSettingsStore(app).settings.first() },
) : AndroidViewModel(app) {
    private val uri = handle.toRoute<Reader>().uri.toUri()

    /** The renderer is not thread-safe: everything below this line is only touched on this one thread. */
    private val pdfThread = Dispatchers.IO.limitedParallelism(1)
    private var document: ScoreDocument? = null
    private var size: PixelSize? = null
    private var settings: ReaderSettings? = null

    /**
     * One crop for the whole score, so the music keeps its size and place from page to page and the pages keep one
     * size, so their bitmaps can be reused. The union of a sample of pages, each detected in its own renderer call:
     * a page that crashes the renderer is skipped (and marked broken), not the score. Null until detected.
     */
    private var scoreArea: PageArea? = null
    private var current = 0

    /** Half-page turns: the top half of the screen already shows the next page. */
    private var half = false

    /**
     * Rendered pages around [current], at [size]: previous, current and next; with half-page turns also the one
     * after next, which a half turn shows next.
     */
    private val window = mutableMapOf<Int, Bitmap>()
    private val windowRange get() = current - 1..current + if (settings?.halfPageTurns == true) 2 else 1

    /** Pages that threw while rendering. Not retried: a broken page stays broken. */
    private val brokenPages = mutableSetOf<Int>()

    /** The bitmaps last published, and bitmaps they replaced, with the time they were replaced. */
    private var onScreen: List<Bitmap> = emptyList()
    private val leftScreen = ArrayDeque<Pair<Bitmap, Long>>()

    /**
     * What the UI really put on screen, from [onDrawn] and [onPresented]. The state alone is not enough:
     * a busy main thread or render thread can keep an older page on screen well after a newer one was
     * published. Written on the main thread, read on the PDF thread: guarded by [drawnLock].
     */
    private val drawnLock = Any()

    /**
     * Recorded frames that may still be on screen, oldest first, each with the bitmaps it draws (two in a half
     * turn): the one presented last and any drawn since. Frames reach the screen in recording order.
     */
    private val drawn = ArrayDeque<List<Bitmap>>()
    private val stoppedDrawing = ArrayDeque<Pair<Bitmap, Long>>()

    /** Main thread only. */
    private var lastSize: PixelSize? = null
    private var resizeJob: Job? = null

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state

    /** The colour of the score's paper (ARGB), for around the page. Null until the score has been looked at. */
    private val _paper = MutableStateFlow<Int?>(null)
    val paper: StateFlow<Int?> = _paper

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
            if (settings == null) settings = loadSettings()
            if (scoreArea == null) scanScore(doc)
            this.size = size
            window.clear()
            showCurrent(doc, since = SystemClock.uptimeMillis())
        }
    }

    /**
     * Looks at a sample of pages once, for the crop ([scoreArea], only with crop margins on) and the paper colour
     * ([paper], always): the median of the sampled pages, so one odd page doesn't decide it.
     */
    private fun scanScore(doc: ScoreDocument) {
        val started = SystemClock.uptimeMillis()
        // The crop needs the whole sample; the paper colour alone is fine from the first page (about 55 ms, not 330).
        val sample = if (settings?.cropMargins == true) samplePages(doc.pageCount) else listOf(0)
        val scans = sample.mapNotNull { index ->
            try {
                doc.scanPage(index)
            } catch (e: Exception) {
                Log.w("Volti", "Can't look at page ${index + 1} of $uri", e)
                brokenPages += index
                null
            }
        }
        scoreArea = if (settings?.cropMargins == true) scans.map { it.area }.reduceOrNull(PageArea::union) ?: PageArea.WHOLE else PageArea.WHOLE
        _paper.value = scans.map { it.paper }.sortedBy { (it shr 16 and 0xff) + (it shr 8 and 0xff) + (it and 0xff) }
            .getOrNull(scans.size / 2)
        Log.d("Volti", "Scanned ${scans.size} pages in ${SystemClock.uptimeMillis() - started} ms: $scoreArea, paper ${_paper.value?.let { "#%06X".format(it and 0xffffff) }}")
    }

    /**
     * Native rendering can't be interrupted, and the Play renderer runs every document under one
     * process-wide lock. A file that hangs it would leave a blank reader with the screen kept on.
     * After [openTimeoutMs] without a first page, give up on this file and say so.
     */
    private fun watchFirstPage() {
        viewModelScope.launch {
            delay(openTimeoutMs.milliseconds)
            if (_state.compareAndSet(ReaderState.Loading, ReaderState.Failed(ReaderState.Problem.TooSlow))) {
                Log.w("Volti", "Gave up on $uri after $openTimeoutMs ms")
            }
        }
    }

    /**
     * Called by the UI when it records a frame drawing [bitmaps]: from now on they may be on screen. Also for a
     * frame without a page (empty), so a late [onPresented] for it can't release the pages drawn after it.
     */
    fun onDrawn(bitmaps: List<Bitmap>) = synchronized(drawnLock) {
        if (!sameBitmaps(drawn.lastOrNull(), bitmaps)) drawn.addLast(bitmaps)
    }

    /**
     * Called by the UI once the frame drawing [bitmaps] (empty: no page) has been committed to the screen.
     * Bitmaps only in older frames have then left it; bitmaps from newer frames are still on their way.
     * An unknown frame releases nothing: holding on too long is safe, letting go too early is not.
     */
    fun onPresented(bitmaps: List<Bitmap>) = synchronized(drawnLock) {
        val now = SystemClock.uptimeMillis()
        val older = drawn.indexOfFirst { sameBitmaps(it, bitmaps) }.coerceAtLeast(0)
        repeat(older) {
            drawn.removeFirst().filter { gone -> bitmaps.none { it === gone } }.forEach { stoppedDrawing.addLast(it to now) }
        }
    }

    private fun sameBitmaps(a: List<Bitmap>?, b: List<Bitmap>) = a != null && a.size == b.size && a.indices.all { a[it] === b[it] }

    fun next() = turn(+1)

    fun previous() = turn(-1)

    private fun turn(delta: Int) {
        if (_state.value !is ReaderState.Page) return
        val tapped = SystemClock.uptimeMillis()
        onPdfThread {
            val doc = document ?: return@onPdfThread
            if (size == null) return@onPdfThread
            val moved = if (settings?.halfPageTurns != true) {
                (current + delta in 0 until doc.pageCount).also { if (it) current += delta }
            } else if (delta > 0) {
                // Forward: first the next page's top half over this one, then the next page whole.
                when {
                    half -> { current += 1; half = false; true }
                    current + 1 < doc.pageCount -> { half = true; true }
                    else -> false
                }
            } else {
                when {
                    half -> { half = false; true }
                    current > 0 -> { current -= 1; half = true; true }
                    else -> false
                }
            }
            if (moved) showCurrent(doc, since = tapped)
        }
    }

    /**
     * Shows [current], from the window if it is there, then fills the surrounding window.
     * Stops before each neighbour if a newer size has arrived.
     */
    private suspend fun showCurrent(doc: ScoreDocument, since: Long) {
        val shown = if (half) listOf(current, current + 1) else listOf(current)
        val ready = shown.all { it in window || it in brokenPages }
        val bitmap = window[current] ?: renderInto(doc, current)
        val top = if (half) window[current + 1] ?: renderInto(doc, current + 1) else null
        publish(ReaderState.Page(bitmap, current, doc.pageCount, half, top, scoreArea ?: PageArea.WHOLE))
        Log.d("Volti", "Page ${current + 1}${if (half) "½" else ""}: ${if (ready) "ready" else "rendered"} ${SystemClock.uptimeMillis() - since} ms after the tap")
        // Forward first: reading forward is the common case.
        for (i in (current + 1..windowRange.last) + (current - 1)) {
            currentCoroutineContext().ensureActive()
            if (i in 0 until doc.pageCount && i !in window) renderInto(doc, i)
        }
    }

    private fun publish(page: ReaderState.Page) {
        val now = SystemClock.uptimeMillis()
        onScreen.filter { old -> page.bitmaps.none { it === old } }.forEach { leftScreen.addLast(it to now) }
        onScreen = page.bitmaps
        // Not over a Failed: the time limit may have given up on this file while the render ran.
        _state.update { it as? ReaderState.Failed ?: page }
    }

    /**
     * Renders [index], into the bitmap of a page that fell out of the window when that bitmap is safe to
     * draw into. Returns null for a page that throws; the rest of the score stays readable.
     */
    private fun renderInto(doc: ScoreDocument, index: Int): Bitmap? {
        if (index in brokenPages) return null
        val dropped = window.keys.filter { it !in windowRange }.map { window.remove(it)!! }
        val reuse = if (dropped.isEmpty()) null else safeToDrawInto(dropped)
        return try {
            doc.renderPage(index, size!!, reuse, area = scoreArea ?: PageArea.WHOLE).also { window[index] = it }
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
                onScreen.none { it === bitmap } &&
                    leftScreen.none { it.first === bitmap } &&
                    drawn.none { frame -> frame.any { it === bitmap } } &&
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
