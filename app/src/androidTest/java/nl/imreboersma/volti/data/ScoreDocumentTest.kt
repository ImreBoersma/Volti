package nl.imreboersma.volti.data

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class ScoreDocumentTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    @get:Rule
    val tmp = TemporaryFolder()

    /** A 2-page A4 PDF whose first page has a black block over its left half. */
    private fun writePdf(): File {
        val file = File(tmp.root, "score.pdf")
        val pdf = PdfDocument()
        repeat(2) { i ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            if (i == 0) page.canvas.drawRect(0f, 0f, 297f, 842f, Paint().apply { color = Color.BLACK })
            pdf.finishPage(page)
        }
        file.outputStream().use(pdf::writeTo)
        pdf.close()
        return file
    }

    @Test
    fun rendersPageOneOnWhiteAtFittedSize() {
        openScore(resolver, Uri.fromFile(writePdf())).use { doc ->
            assertEquals(2, doc.pageCount)

            val bitmap = doc.renderPage(0, PixelSize(600, 600))

            assertEquals(PixelSize(424, 600), PixelSize(bitmap.width, bitmap.height))
            assertEquals(Color.BLACK, bitmap.getPixel(100, 300))
            assertEquals(Color.WHITE, bitmap.getPixel(320, 300))
        }
    }

    @Test
    fun drawsIntoAReusedBitmapOfTheRightSize() {
        openScore(resolver, Uri.fromFile(writePdf())).use { doc ->
            val first = doc.renderPage(0, PixelSize(600, 600))

            val second = doc.renderPage(1, PixelSize(600, 600), reuse = first)

            assertSame(first, second)
            assertEquals(Color.WHITE, second.getPixel(100, 300)) // Page 1's black block is gone.
            assertNotSame(first, doc.renderPage(1, PixelSize(300, 300), reuse = first))
        }
    }

    @Test
    fun usesThePlayUpdatedRendererOnAndroid14() {
        assumeTrue(Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assumeTrue(SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13)
        openScore(resolver, Uri.fromFile(writePdf())).use { doc ->
            assertEquals("PreVDocument", doc::class.simpleName)
        }
    }

    @Test(expected = IOException::class)
    fun notAPdfThrowsIOException() {
        val file = File(tmp.root, "fake.pdf").apply { writeText("hello") }
        openScore(resolver, Uri.fromFile(file))
    }

    @Test(expected = IOException::class)
    fun pdfWithoutPagesThrowsIOException() {
        val file = File(tmp.root, "empty.pdf")
        val objects = listOf("<</Type/Catalog/Pages 2 0 R>>", "<</Type/Pages/Kids[]/Count 0>>")
        val body = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, o -> body.length.also { body.append("${i + 1} 0 obj\n$o\nendobj\n") } }
        val xref = body.length
        body.append("xref\n0 3\n0000000000 65535 f \n")
        offsets.forEach { body.append("%010d 00000 n \n".format(it)) }
        body.append("trailer<</Size 3/Root 1 0 R>>\nstartxref\n$xref\n%%EOF\n")
        file.writeText(body.toString())
        openScore(resolver, Uri.fromFile(file))
    }
}
