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
import org.junit.Assert.assertTrue
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

    @Test
    fun croppingMarginsMakesTheMusicBigger() {
        // An A4 page with wide margins: content only in the middle 60% each way.
        val file = File(tmp.root, "margins.pdf")
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        page.canvas.drawRect(119f, 168f, 476f, 674f, Paint().apply { color = Color.BLACK })
        pdf.finishPage(page)
        file.outputStream().use(pdf::writeTo)
        pdf.close()

        openScore(resolver, Uri.fromFile(file)).use { doc ->
            fun blackShare(bitmap: android.graphics.Bitmap): Double {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                return pixels.count { it == Color.BLACK } / (600.0 * 600.0) // Share of the view, not of the bitmap.
            }
            val whole = blackShare(doc.renderPage(0, PixelSize(600, 600)))
            val cropped = blackShare(doc.renderPage(0, PixelSize(600, 600), area = doc.scanPage(0).area))
            // The cap is 15% a side, so the content can grow by up to about 1.4x in each direction.
            assertTrue("whole $whole, cropped $cropped", cropped > whole * 1.5)
        }
    }

    /** One page with a black block in the middle 60%, optionally turned with /Rotate. Hand-written: PdfDocument can't rotate. */
    private fun marginsPdf(rotate: Int = 0): File {
        val content = "0 g 119 168 357 506 re f".toByteArray()
        val objects = listOf(
            "<</Type/Catalog/Pages 2 0 R>>",
            "<</Type/Pages/Kids[3 0 R]/Count 1>>",
            "<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Rotate $rotate/Contents 4 0 R>>",
            "<</Length ${content.size}>>stream\n${String(content)}\nendstream",
        )
        val body = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, o -> body.length.also { body.append("${i + 1} 0 obj\n$o\nendobj\n") } }
        val xref = body.length
        body.append("xref\n0 5\n0000000000 65535 f \n")
        offsets.forEach { body.append("%010d 00000 n \n".format(it)) }
        body.append("trailer<</Size 5/Root 1 0 R>>\nstartxref\n$xref\n%%EOF\n")
        return File(tmp.root, "margins-$rotate.pdf").apply { writeText(body.toString()) }
    }

    private fun blackShareOfView(bitmap: android.graphics.Bitmap): Double {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { it == Color.BLACK } / (600.0 * 600.0)
    }

    @Test
    fun croppingWorksOnARotatedPage() {
        openScore(resolver, Uri.fromFile(marginsPdf(rotate = 90))).use { doc ->
            val whole = doc.renderPage(0, PixelSize(600, 600))
            val cropped = doc.renderPage(0, PixelSize(600, 600), area = doc.scanPage(0).area)
            assertTrue("a rotated A4 page is landscape: ${whole.width}x${whole.height}", whole.width > whole.height)
            assertTrue("whole ${blackShareOfView(whole)}, cropped ${blackShareOfView(cropped)}", blackShareOfView(cropped) > blackShareOfView(whole) * 1.5)
        }
    }

    @Test
    fun croppingWorksThroughTheIsolatedRenderer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        nl.imreboersma.volti.render.openIsolated(context, Uri.fromFile(marginsPdf())).use { doc ->
            val whole = blackShareOfView(doc.renderPage(0, PixelSize(600, 600)))
            val cropped = blackShareOfView(doc.renderPage(0, PixelSize(600, 600), area = doc.scanPage(0).area))
            assertTrue("whole $whole, cropped $cropped", cropped > whole * 1.5)
        }
    }

    @Test
    fun theIsolatedRendererReadsAYellowedPagesPaperColour() {
        // A page filled with yellowed paper and a black block, through the isolated renderer like the app.
        val file = File(tmp.root, "yellowed.pdf")
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        page.canvas.drawColor(0xffeee2be.toInt())
        page.canvas.drawRect(119f, 168f, 476f, 300f, Paint().apply { color = Color.BLACK })
        pdf.finishPage(page)
        file.outputStream().use(pdf::writeTo)
        pdf.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        nl.imreboersma.volti.render.openIsolated(context, Uri.fromFile(file)).use { doc ->
            val paper = doc.scanPage(0).paper
            for ((shift, expected) in listOf(16 to 0xee, 8 to 0xe2, 0 to 0xbe)) {
                assertTrue("paper #%06X".format(paper and 0xffffff), kotlin.math.abs((paper shr shift and 0xff) - expected) <= 3)
            }
        }
    }
}
