package nl.imreboersma.volti.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nl.imreboersma.volti.data.PixelSize
import nl.imreboersma.volti.data.ScoreDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReaderViewModelTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val closed = CountDownLatch(1)

    private inner class FakeDocument(private val renderMillis: Long) : ScoreDocument {
        override val pageCount = 1

        override fun renderPage(index: Int, max: PixelSize): Bitmap {
            Thread.sleep(renderMillis)
            events += "render ${max.width}x${max.height}"
            return createBitmap(1, 1)
        }

        override fun close() {
            events += "close"
            closed.countDown()
        }
    }

    private val store = ViewModelStore()

    private fun viewModel(open: () -> ScoreDocument): ReaderViewModel {
        // A content URI with an encoded slash, as the library passes it.
        val handle = SavedStateHandle(mapOf("uri" to "content://provider/tree/a%3AScores/document/a%3AScores%2FBolero.pdf"))
        val factory = viewModelFactory {
            initializer {
                ReaderViewModel(app, handle) { events += "open $it"; open() }
            }
        }
        return ViewModelProvider.create(store, factory)[ReaderViewModel::class]
    }

    private fun ReaderViewModel.awaitState(predicate: (ReaderState) -> Boolean) = runBlocking {
        withTimeout(5_000) { state.first(predicate) }
    }

    private fun awaitEvents(count: Int) = runBlocking {
        withTimeout(5_000) { while (events.size < count) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun opensOnceAcrossResizesAndSkipsUnchangedOrEmptySizes() {
        val vm = viewModel { FakeDocument(renderMillis = 0) }

        vm.render(PixelSize(0, 1000))
        vm.render(PixelSize(1600, 2400))
        awaitEvents(2)
        vm.render(PixelSize(1600, 2400))
        Thread.sleep(200) // Room for a duplicate render, if one were started.
        assertEquals(2, events.size)
        vm.render(PixelSize(2560, 1500))
        awaitEvents(3)
        Thread.sleep(100)

        assertEquals(
            listOf(
                "open content://provider/tree/a%3AScores/document/a%3AScores%2FBolero.pdf",
                "render 1600x2400",
                "render 2560x1500",
            ),
            events.toList(),
        )
    }

    @Test
    fun closesAfterTheRenderInFlight() {
        val vm = viewModel { FakeDocument(renderMillis = 300) }

        vm.render(PixelSize(1600, 2400))
        Thread.sleep(50) // The render is now running on the PDF thread.
        store.clear()

        assertTrue("document was not closed", closed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("render 1600x2400", "close"), events.drop(1))
    }

    @Test
    fun anyExceptionGivesFailedAndFailedIsFinal() {
        val vm = viewModel { throw IllegalArgumentException("File descriptor not seekable") }

        vm.render(PixelSize(1600, 2400))
        vm.awaitState { it == ReaderState.Failed }
        vm.render(PixelSize(2560, 1500))
        Thread.sleep(100)

        assertEquals(1, events.count { it.startsWith("open") })
    }

    @Test
    fun ioExceptionGivesFailed() {
        val vm = viewModel { throw IOException("No pages") }
        vm.render(PixelSize(1600, 2400))
        vm.awaitState { it == ReaderState.Failed }
    }
}
