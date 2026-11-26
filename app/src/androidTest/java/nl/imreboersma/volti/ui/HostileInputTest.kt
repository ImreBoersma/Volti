package nl.imreboersma.volti.ui

import android.app.Application
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRendererPreV
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
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
import kotlin.time.Duration.Companion.seconds
import nl.imreboersma.volti.data.PixelSize
import nl.imreboersma.volti.data.ReaderSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** The files in assets/hostile (made by make_hostile_pdfs.py), through the real ViewModel and renderer. */
@RunWith(AndroidJUnit4::class)
class HostileInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val store = ViewModelStore()
    private val size = PixelSize(1600, 2400)

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() = store.clear()

    private fun fixture(name: String): File = File(tmp.root, name).also { file ->
        instrumentation.context.assets.open("hostile/$name").use { input -> file.outputStream().use(input::copyTo) }
    }

    private fun open(name: String): ReaderViewModel {
        val handle = SavedStateHandle(mapOf("uri" to Uri.fromFile(fixture(name)).toString()))
        // The default reader settings, not whatever the device has: half-page turns would change what next() does.
        val factory = viewModelFactory { initializer { ReaderViewModel(app, handle, loadSettings = { ReaderSettings() }) } }
        return ViewModelProvider.create(store, factory)[ReaderViewModel::class].also { it.render(size) }
    }

    private fun ReaderViewModel.settled(): ReaderState = runBlocking {
        withTimeout(10.seconds) { state.first { it !is ReaderState.Loading } }
    }

    @Test
    fun passwordProtectedSaysSo() {
        assertEquals(ReaderState.Failed(ReaderState.Problem.PasswordProtected), open("password.pdf").settled())
    }

    @Test
    fun passwordFixtureIsReallyEncryptedNotJustBroken() {
        assumeTrue(Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assumeTrue(SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13)
        val fd = ParcelFileDescriptor.open(fixture("password.pdf"), ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRendererPreV(fd, LoadParams.Builder().setPassword("secret").build()).use {
            assertEquals(1, it.pageCount)
        }
    }

    @Test
    fun notAPdfCannotBeOpened() {
        assertEquals(ReaderState.Failed(), open("not-a-pdf.pdf").settled())
    }

    @Test
    fun truncatedCannotBeOpened() {
        assertEquals(ReaderState.Failed(), open("truncated.pdf").settled())
    }

    @Test
    fun zeroPagesCannotBeOpened() {
        assertEquals(ReaderState.Failed(), open("zero-pages.pdf").settled())
    }

    @Test
    fun zeroSizePageFallsBackToADefaultSize() {
        // PDFium replaces an empty media box with a default page size. fitInside's own guard is for other renderers.
        val bitmap = (open("zero-size-page.pdf").settled() as ReaderState.Page).bitmap
        assertNotNull(bitmap)
        assertTrue(bitmap!!.width <= size.width && bitmap.height <= size.height)
    }

    @Test
    fun hugePageStaysInsideTheView() {
        val bitmap = (open("huge-page.pdf").settled() as ReaderState.Page).bitmap
        assertNotNull(bitmap)
        assertTrue(bitmap!!.width <= size.width && bitmap.height <= size.height)
    }

    @Test
    fun brokenContentOnOnePageLeavesTheOthersReadable() {
        val vm = open("broken-page.pdf")
        assertNotNull((vm.settled() as ReaderState.Page).bitmap)
        vm.next()
        val second = runBlocking { withTimeout(10.seconds) { vm.state.first { it is ReaderState.Page && it.index == 1 } } }
        vm.next()
        val third = runBlocking { withTimeout(10.seconds) { vm.state.first { it is ReaderState.Page && it.index == 2 } } }
        // PDFium doesn't throw on a broken content stream: it draws what it can. So "Can't show page N."
        // is never reached with the real renderer; ReaderViewModelTest covers it with a fake.
        assertNotNull((second as ReaderState.Page).bitmap)
        assertNotNull((third as ReaderState.Page).bitmap)
    }

    @Test
    fun aFileThatCrashesPdfiumLeavesTheAppAndOtherScoresWorking() {
        // form-bomb.pdf opens fine; rendering its page makes PDFium abort. In the isolated renderer that kills
        // only the service process: the page shows as "Can't show page 1." and the test process lives on.
        val bomb = open("form-bomb.pdf").settled()
        assertEquals(ReaderState.Page(bitmap = null, index = 0, pageCount = 1), bomb)

        store.clear()
        val next = open("huge-page.pdf").settled()
        assertTrue("the next score did not open: $next", next is ReaderState.Page && next.bitmap != null)
    }

    @Test
    fun aPageThatCrashesPdfiumLeavesTheOtherPagesOfTheScoreReadable() {
        // Page 2 aborts PDFium, every time. The document is reopened in a fresh renderer after each crash.
        val vm = open("bomb-on-page-2.pdf")
        assertNotNull((vm.settled() as ReaderState.Page).bitmap)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.next() }
        val second = runBlocking { withTimeout(60.seconds) { vm.state.first { it is ReaderState.Page && it.index == 1 } } }
        assertEquals("page 2 should show as unreadable", null, (second as ReaderState.Page).bitmap)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.next() }
        val third = runBlocking { withTimeout(60.seconds) { vm.state.first { it is ReaderState.Page && it.index == 2 } } }
        assertNotNull("page 3 should render in a fresh renderer", (third as ReaderState.Page).bitmap)
    }
}
