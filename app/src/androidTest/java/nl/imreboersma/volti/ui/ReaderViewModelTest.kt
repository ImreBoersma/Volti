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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import nl.imreboersma.volti.data.PageArea
import nl.imreboersma.volti.data.PageScan
import nl.imreboersma.volti.data.PixelSize
import nl.imreboersma.volti.data.ReaderSettings
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
    private val cropped = AtomicInteger()
    private val closed = CountDownLatch(1)
    private val store = ViewModelStore()
    private val collector = CoroutineScope(Dispatchers.Default)
    private val size = PixelSize(1600, 2400)
    private lateinit var vm: ReaderViewModel

    /**
     * The stand-in UI's own record, independent of the ViewModel's: every state it saw, the bitmap on
     * screen, bitmaps in recorded frames not yet presented, and when each bitmap left the screen.
     * Guarded by [screenLock].
     */
    private val screenLock = Any()
    private val states = mutableListOf<ReaderState>()
    private var onScreen: List<Bitmap> = emptyList()
    private val notYetPresented = mutableListOf<Bitmap>()
    private val leftScreenAt = IdentityHashMap<Bitmap, Long>()

    private inner class FakeDocument(
        override val pageCount: Int = 1,
        private val renderMillis: Long = 0,
        private val broken: Set<Int> = emptySet(),
    ) : ScoreDocument {
        override fun scanPage(index: Int) = PageScan(PageArea(0.1f, 0.1f, 0.9f, 0.9f), 0xffeee2be.toInt())

        override fun renderPage(index: Int, max: PixelSize, reuse: Bitmap?, area: PageArea): Bitmap {
            if (area != PageArea.WHOLE) cropped.incrementAndGet()
            Thread.sleep(renderMillis)
            if (index in broken) {
                events += "broken $index"
                throw IllegalStateException("corrupt page")
            }
            if (reuse != null) {
                reused.incrementAndGet()
                synchronized(screenLock) {
                    val left = leftScreenAt[reuse]
                    if (onScreen.any { it === reuse } || notYetPresented.any { it === reuse } ||
                        (left != null && SystemClock.uptimeMillis() - left < REUSE_DELAY_MS)
                    ) {
                        val id = { b: Bitmap -> System.identityHashCode(b).toString(16) }
                        events += "drew into a bitmap on screen: ${id(reuse)} for page $index; on screen ${onScreen.map(id)}, " +
                            "pending ${notYetPresented.map(id)}, left ${left?.let { SystemClock.uptimeMillis() - it }} ms ago"
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

    /**
     * The stand-in UI. Once per [frameMillis] (a long one is a busy main thread) it records a frame with the
     * latest state and reports onDrawn; [presentMillis] later (a long one is a busy render thread) that frame
     * reaches the screen, and it reports onPresented, as ReaderScreen does.
     */
    private fun viewModel(
        openTimeoutMs: Long = 10_000,
        frameMillis: Long = 16,
        presentMillis: Long = 5,
        settings: ReaderSettings = ReaderSettings(cropMargins = false),
        standInUi: Boolean = true,
        open: () -> ScoreDocument,
    ): ReaderViewModel {
        // A content URI with an encoded slash, as the library passes it.
        val handle = SavedStateHandle(mapOf("uri" to "content://provider/tree/a%3AScores/document/a%3AScores%2FBolero.pdf"))
        val factory = viewModelFactory {
            initializer {
                ReaderViewModel(
                    app,
                    handle,
                    open = { events += "open $it"; open() },
                    openTimeoutMs = openTimeoutMs,
                    loadSettings = { settings },
                )
            }
        }
        vm = ViewModelProvider.create(store, factory)[ReaderViewModel::class]
        if (!standInUi) return vm
        // Recorded frames, in order, for the presenter: like a real render thread, it shows them in that order.
        val frames = Channel<Pair<List<Bitmap>, Long>>(Channel.UNLIMITED)
        collector.launch {
            var hadPage = false
            while (true) {
                delay(frameMillis.milliseconds)
                val state = vm.state.value
                val bitmaps = (state as? ReaderState.Page)?.bitmaps.orEmpty()
                synchronized(screenLock) { if (states.lastOrNull() != state) states += state }
                // As ReaderScreen: frames with pages report themselves; a frame without one only when the pages
                // just went away (PageView disposed). Loading frames before the first page report nothing.
                if (bitmaps.isEmpty() && !hadPage) continue
                hadPage = bitmaps.isNotEmpty()
                synchronized(screenLock) {
                    notYetPresented += bitmaps
                    vm.onDrawn(bitmaps)
                }
                frames.send(bitmaps to SystemClock.uptimeMillis())
            }
        }
        collector.launch {
            for ((bitmaps, recordedAt) in frames) {
                delay((recordedAt + presentMillis - SystemClock.uptimeMillis()).milliseconds)
                synchronized(screenLock) {
                    val now = SystemClock.uptimeMillis()
                    onScreen.filter { old -> bitmaps.none { it === old } }.forEach { leftScreenAt[it] = now }
                    onScreen = bitmaps
                    bitmaps.forEach { b -> notYetPresented.remove(notYetPresented.firstOrNull { it === b }) }
                    vm.onPresented(bitmaps)
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
        withTimeout(10.seconds) { vm.state.first(predicate) }
    }

    private fun awaitPage(index: Int) = awaitState { it is ReaderState.Page && it.index == index } as ReaderState.Page

    private fun awaitEvents(count: Int) = runBlocking {
        withTimeout(10.seconds) { while (events.size < count) delay(10.milliseconds) }
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
        awaitState { it is ReaderState.Failed }
        vm.render(PixelSize(2560, 1500))
        vm.next()
        Thread.sleep(100)

        assertEquals(1, events.count { it.startsWith("open") })
    }

    @Test
    fun ioExceptionGivesFailed() {
        viewModel { throw IOException("No pages") }
        vm.render(size)
        awaitState { it is ReaderState.Failed }
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

    @Test
    fun aFileThatHangsGivesUpAndStaysGivenUp() {
        val hang = CountDownLatch(1)
        viewModel(openTimeoutMs = 200) { hang.await(5, TimeUnit.SECONDS); FakeDocument() }

        vm.render(size)
        assertEquals(ReaderState.Failed(ReaderState.Problem.TooSlow), awaitState { it is ReaderState.Failed })

        hang.countDown() // The renderer finally returns.
        awaitEvents(2) // open, then page 0 is rendered after all
        Thread.sleep(100)
        assertEquals(ReaderState.Failed(ReaderState.Problem.TooSlow), vm.state.value)
    }

    @Test
    fun aBusyMainThreadNeverGetsItsPageDrawnOver() {
        // The UI draws only every 200 ms, while pages turn every 60 ms: a page stays on screen long
        // after the state moved on. This failed on the CI emulator when reuse only looked at the state.
        viewModel(frameMillis = 200) { FakeDocument(pageCount = 30) }

        vm.render(size)
        awaitPage(0)
        repeat(15) {
            onMain { vm.next() }
            Thread.sleep(60)
        }
        awaitPage(15)
        Thread.sleep(500)

        assertEquals(listOf<String>(), events.filter { it.startsWith("drew") })
    }

    @Test
    fun aBusyRenderThreadNeverGetsItsPageDrawnOver() {
        // Each frame reaches the screen 120 ms after it was recorded: the old page is still on screen after
        // the new one was drawn. Counting from onDrawn instead of onPresented would draw into it.
        viewModel(frameMillis = 30, presentMillis = 120) { FakeDocument(pageCount = 30) }

        vm.render(size)
        awaitPage(0)
        repeat(15) {
            onMain { vm.next() }
            Thread.sleep(60)
        }
        awaitPage(15)
        Thread.sleep(500)

        assertEquals(listOf<String>(), events.filter { it.startsWith("drew") })
    }

    /** Taps arrive on the main thread; so do turns in these tests. */
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    fun halfPageTurnsGoThroughTheTopHalfOfTheNextPage() {
        viewModel(settings = ReaderSettings(cropMargins = false, halfPageTurns = true)) { FakeDocument(pageCount = 3) }
        fun at() = (vm.state.value as ReaderState.Page).let { "${it.index}${if (it.half) "½" else ""}" }
        fun step(turn: () -> Unit, expected: String) {
            onMain(turn)
            awaitState { it is ReaderState.Page && "${it.index}${if (it.half) "½" else ""}" == expected }
        }

        vm.render(size)
        awaitPage(0)
        step(vm::next, "0½")
        val half = vm.state.value as ReaderState.Page
        assertTrue("the top half shows the next page", half.top != null && half.top !== half.bitmap)
        step(vm::next, "1")
        step(vm::next, "1½")
        step(vm::next, "2")
        onMain(vm::next) // The last page: nowhere further.
        Thread.sleep(100)
        assertEquals("2", at())
        step(vm::previous, "1½")
        step(vm::previous, "1")
        step(vm::previous, "0½")
        step(vm::previous, "0")
        onMain(vm::previous)
        Thread.sleep(100)
        assertEquals("0", at())
    }

    @Test
    fun halfPageTurnsNeverShowABlankHalfInABurst() {
        viewModel(settings = ReaderSettings(cropMargins = false, halfPageTurns = true)) {
            FakeDocument(pageCount = 12, renderMillis = 100)
        }

        vm.render(size)
        awaitPage(0)
        repeat(9) { onMain(vm::next) }
        awaitState { it is ReaderState.Page && it.index == 4 && it.half }
        repeat(5) { onMain(vm::previous) }
        awaitState { it is ReaderState.Page && it.index == 2 && !it.half }
        Thread.sleep(300)

        val seen = synchronized(screenLock) { states.dropWhile { it !is ReaderState.Page } }
        assertTrue(
            "a blank page or half: $seen",
            seen.all { it is ReaderState.Page && it.bitmap != null && (!it.half || it.top != null) },
        )
        assertEquals(listOf<String>(), events.filter { it.startsWith("drew") })
    }

    @Test
    fun theCropSettingReachesTheRenderer() {
        viewModel(settings = ReaderSettings(cropMargins = true)) { FakeDocument(pageCount = 3) }
        vm.render(size)
        awaitPage(0)
        awaitEvents(1 + 2)
        assertTrue(cropped.get() >= 2)
        assertEquals("the paper colour reaches the reader", 0xffeee2be.toInt(), vm.paper.value)
    }

    @Test
    fun rotatingDuringAHalfTurnKeepsBothHalves() {
        // A new size empties the window: both halves must be rendered again, not just the page underneath.
        viewModel(settings = ReaderSettings(cropMargins = false, halfPageTurns = true)) { FakeDocument(pageCount = 4) }

        vm.render(size)
        awaitPage(0)
        onMain(vm::next)
        awaitState { it is ReaderState.Page && it.half }
        vm.render(PixelSize(2560, 1500))
        awaitEvents(events.size + 1)
        val rotated = awaitState { it is ReaderState.Page && events.any { e -> e.endsWith("2560x1500") } } as ReaderState.Page
        Thread.sleep(200)

        val now = vm.state.value as ReaderState.Page
        assertTrue("still a half turn: $now", now.half && now.index == 0)
        assertTrue("the top half is there after the rotation: $now", now.top != null)
        assertTrue(rotated.bitmap != null)
    }

    @Test
    fun aLateFrameWithoutAPageDoesNotReleaseThePagesDrawnAfterIt() {
        // Found by aBusyRenderThreadNeverGetsItsPageDrawnOver, in about 3 of 10 runs: the loading frame (no page)
        // was reported presented after page 1 was drawn, and released page 1 with it. Here in a fixed order.
        viewModel(standInUi = false) { FakeDocument(pageCount = 6) }
        vm.render(size)
        val first = awaitPage(0).bitmap!!
        synchronized(screenLock) { notYetPresented += first } // Page 1 is in a frame on its way to the screen.
        onMain {
            vm.onDrawn(emptyList()) // The loading frame,
            vm.onDrawn(listOf(first)) // then page 1.
            vm.onPresented(emptyList()) // The loading frame reaches the screen late: page 1 is still coming.
        }
        repeat(3) {
            Thread.sleep(REUSE_DELAY_MS + 20)
            onMain(vm::next) // Page 1 falls out of the window; its bitmap must not be drawn into.
            awaitPage(it + 1)
        }
        awaitEvents(1 + 5)
        Thread.sleep(100)

        assertEquals(listOf<String>(), events.filter { it.startsWith("drew") })
    }
}
