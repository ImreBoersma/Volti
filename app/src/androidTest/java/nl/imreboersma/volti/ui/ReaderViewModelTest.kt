package nl.imreboersma.volti.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nl.imreboersma.volti.data.PixelSize
import nl.imreboersma.volti.data.ScoreDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ReaderViewModelTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val reused = AtomicInteger()
    private val closed = CountDownLatch(1)
    private val store = ViewModelStore()
    private val collector = CoroutineScope(Dispatchers.Default)
    private val size = PixelSize(1600, 2400)
    private lateinit var vm: ReaderViewModel

    /** Every state the "UI" saw, and which bitmap it showed when. Guarded by [screenLock]. */
    private val screenLock = Any()
    private val states = mutableListOf<ReaderState>()
    private var onScreen: Bitmap? = null
    private val leftScreenAt = IdentityHashMap<Bitmap, Long>()

    private inner class FakeDocument(
        override val pageCount: Int = 1,
        private val renderMillis: Long = 0,
        private val broken: Set<Int> = emptySet(),
    ) : ScoreDocument {
        override fun renderPage(index: Int, max: PixelSize, reuse: Bitmap?): Bitmap {
            Thread.sleep(renderMillis)
            if (index in broken) {
                events += "broken $index"
                throw IllegalStateException("corrupt page")
            }
            if (reuse != null) {
                reused.incrementAndGet()
                synchronized(screenLock) {
                    val left = leftScreenAt[reuse]
                    if (reuse === onScreen || (left != null && SystemClock.uptimeMillis() - left < REUSE_DELAY_MS)) {
                        events += "drew into a bitmap on screen"
                    }
                }
            }
            events += "render $index ${max.width}x${max.height}"
            return reuse ?: createBitmap(1, 1)
        }

        override fun close() {
            events += "close"
            closed.countDown()
        }
    }

    private fun viewModel(open: () -> ScoreDocument): ReaderViewModel {
        // A content URI with an encoded slash, as the library passes it.
        val handle = SavedStateHandle(mapOf("uri" to "content://provider/tree/a%3AScores/document/a%3AScores%2FBolero.pdf"))
        val factory = viewModelFactory {
            initializer { ReaderViewModel(app, handle) { events += "open $it"; open() } }
        }
        vm = ViewModelProvider.create(store, factory)[ReaderViewModel::class]
        collector.launch {
            vm.state.collect { state ->
                synchronized(screenLock) {
                    states += state
                    val bitmap = (state as? ReaderState.Page)?.bitmap
                    if (bitmap !== onScreen) {
                        onScreen?.let { leftScreenAt[it] = SystemClock.uptimeMillis() }
                        onScreen = bitmap
                    }
                }
            }
        }
        return vm
    }

    @After
    fun tearDown() {
        collector.cancel()
        store.clear()
    }

    private fun awaitState(predicate: (ReaderState) -> Boolean) = runBlocking {
        withTimeout(10_000) { vm.state.first(predicate) }
    }

    private fun awaitPage(index: Int) = awaitState { it is ReaderState.Page && it.index == index } as ReaderState.Page

    private fun awaitEvents(count: Int) = runBlocking {
        withTimeout(10_000) { while (events.size < count) delay(10) }
    }

    private fun renders() = events.filter { it.startsWith("render") }

    @Test
    fun opensOnceAcrossResizesAndSkipsUnchangedOrEmptySizes() {
        viewModel { FakeDocument() }

        vm.render(PixelSize(0, 1000))
        vm.render(size)
        awaitEvents(2)
        vm.render(size)
        Thread.sleep(200) // Room for a duplicate render, if one were started.
        assertEquals(2, events.size)
        vm.render(PixelSize(2560, 1500))
        awaitEvents(3)
        Thread.sleep(100)

        assertEquals(
            listOf(
                "open content://provider/tree/a%3AScores/document/a%3AScores%2FBolero.pdf",
                "render 0 1600x2400",
                "render 0 2560x1500",
            ),
            events.toList(),
        )
    }

    @Test
    fun closesAfterTheRenderInFlight() {
        viewModel { FakeDocument(renderMillis = 300) }

        vm.render(size)
        Thread.sleep(50) // The render is now running on the PDF thread.
        store.clear()

        assertTrue("document was not closed", closed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("render 0 1600x2400", "close"), events.drop(1))
    }

    @Test
    fun openFailureIsFinal() {
        viewModel { throw IllegalArgumentException("File descriptor not seekable") }

        vm.render(size)
        awaitState { it == ReaderState.Failed }
        vm.render(PixelSize(2560, 1500))
        vm.next()
        Thread.sleep(100)

        assertEquals(1, events.count { it.startsWith("open") })
    }

    @Test
    fun ioExceptionGivesFailed() {
        viewModel { throw IOException("No pages") }
        vm.render(size)
        awaitState { it == ReaderState.Failed }
    }

    @Test
    fun nextPageIsRenderedBeforeTheTurn() {
        viewModel { FakeDocument(pageCount = 5) }

        vm.render(size)
        awaitPage(0)
        awaitEvents(3) // open, page 0, page 1
        vm.next()
        awaitPage(1)
        awaitEvents(4)
        Thread.sleep(100)

        // Page 1 was ready before the turn; the turn only renders the new neighbour, page 2.
        assertEquals(listOf("render 0", "render 1", "render 2"), renders().map { it.substringBeforeLast(' ') })
    }

    @Test
    fun turnsStopAtTheFirstAndLastPage() {
        viewModel { FakeDocument(pageCount = 2) }

        vm.render(size)
        awaitPage(0)
        vm.previous()
        Thread.sleep(100)
        assertEquals(0, (vm.state.value as ReaderState.Page).index)

        vm.next()
        awaitPage(1)
        vm.next()
        Thread.sleep(100)
        assertEquals(1, (vm.state.value as ReaderState.Page).index)
    }

    @Test
    fun reusesDroppedBitmaps() {
        viewModel { FakeDocument(pageCount = 8) }

        vm.render(size)
        awaitPage(0)
        for (page in 1..6) {
            Thread.sleep(REUSE_DELAY_MS + 20) // A reader's pace: older bitmaps are free again.
            vm.next()
            awaitPage(page)
        }
        awaitEvents(1 + 8) // open, then every page once
        Thread.sleep(100)

        assertEquals((0..7).map { "render $it 1600x2400" }, events.drop(1))
        assertTrue("no bitmap was reused", reused.get() > 0)
    }

    @Test
    fun burstsOfTapsNeverShowABlankPageOrGoBackwards() {
        viewModel { FakeDocument(pageCount = 12, renderMillis = 100) }

        vm.render(size)
        awaitPage(0)
        repeat(6) { vm.next() }
        awaitPage(6)
        repeat(4) { vm.previous() }
        awaitPage(2)
        Thread.sleep(300)

        val seen = synchronized(screenLock) { states.dropWhile { it !is ReaderState.Page } }
        assertTrue("a non-page state after the first page: $seen", seen.all { it is ReaderState.Page && it.bitmap != null })
        val indices = seen.map { (it as ReaderState.Page).index }
        val forward = indices.takeWhile { it < 6 } + 6
        assertEquals(forward.sorted(), forward)
        assertEquals(listOf<String>(), events.filter { it.startsWith("drew") })
    }

    @Test
    fun fastRendersNeverDrawIntoABitmapThatJustLeftTheScreen() {
        viewModel { FakeDocument(pageCount = 30, renderMillis = 0) }

        vm.render(size)
        awaitPage(0)
        repeat(20) { vm.next() }
        awaitPage(20)
        repeat(10) { vm.previous() }
        awaitPage(10)
        Thread.sleep(100)

        assertEquals(listOf<String>(), events.filter { it.startsWith("drew") })
    }

    @Test
    fun aBrokenPageIsShownAsUnreadableAndTheRestStaysReadable() {
        viewModel { FakeDocument(pageCount = 5, broken = setOf(2)) }

        vm.render(size)
        awaitPage(0)
        vm.next()
        awaitPage(1)
        Thread.sleep(100) // Page 2 fails while prefetching.
        assertTrue(vm.state.value is ReaderState.Page)

        vm.next()
        assertNull(awaitPage(2).bitmap)
        vm.next()
        assertTrue(awaitPage(3).bitmap != null)
        vm.previous()
        awaitPage(2)
        Thread.sleep(100)

        assertEquals("a broken page is tried once", 1, events.count { it.startsWith("broken") })
    }

    @Test
    fun aNewSizeStopsTheOldSizeBeforeItsNeighbours() {
        viewModel { FakeDocument(pageCount = 5, renderMillis = 100) }

        vm.render(size)
        Thread.sleep(50) // Page 0 at the first size is rendering.
        vm.render(PixelSize(2560, 1500))
        awaitEvents(1 + 3)
        Thread.sleep(300)

        assertEquals(
            listOf("render 0 1600x2400", "render 0 2560x1500", "render 1 2560x1500"),
            renders(),
        )
    }
}
