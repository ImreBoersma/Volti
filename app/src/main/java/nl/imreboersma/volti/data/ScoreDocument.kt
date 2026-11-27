package nl.imreboersma.volti.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresExtension
import androidx.core.graphics.createBitmap
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.roundToInt

data class PixelSize(val width: Int, val height: Int)

/** The largest size with the page's aspect ratio that fits inside [max]. All sizes must be positive. */
internal fun fitInside(pageWidth: Int, pageHeight: Int, max: PixelSize): PixelSize {
    require(pageWidth > 0 && pageHeight > 0) { "Page has no size: $pageWidth x $pageHeight" }
    require(max.width > 0 && max.height > 0) { "No room to render: $max" }
    val scale = minOf(max.width.toFloat() / pageWidth, max.height.toFloat() / pageHeight)
    return PixelSize(
        (pageWidth * scale).roundToInt().coerceIn(1, max.width),
        (pageHeight * scale).roundToInt().coerceIn(1, max.height),
    )
}

/** The PDF needs a password. The renderers throw SecurityException for that, as does a lost folder grant. */
class PasswordProtectedException(cause: Throwable) : IOException("Password-protected PDF", cause)

/** An open PDF. Not thread-safe, and only one page may be open at a time: use from one thread. */
interface ScoreDocument : Closeable {
    val pageCount: Int

    /**
     * Renders [area] of page [index] on white, as large as fits inside [max] with that area's aspect ratio.
     * Draws into [reuse] instead of allocating when it has exactly the right size. A smaller area than the whole
     * page crops the margins, so the music fills more of the view.
     */
    fun renderPage(index: Int, max: PixelSize, reuse: Bitmap? = null, area: PageArea = PageArea.WHOLE): Bitmap

    /** Where the music is on page [index], and the colour of its paper, from one small render of the page. */
    fun scanPage(index: Int): PageScan
}

/**
 * Opens [uri] read-only, in this process. The app itself renders through the isolated
 * PdfRenderService instead; this is what that service runs, and what the renderer tests use.
 */
fun openScore(resolver: ContentResolver, uri: Uri): ScoreDocument =
    openPdf(resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString()))

/**
 * Opens [fd], taking it over. On Android 14 this uses [PdfRendererPreV] when available: it lives in the
 * MediaProvider module, which Google Play keeps patched. The platform [PdfRenderer] on Android 14
 * uses the system PDFium, which only firmware updates patch. From Android 15 the platform class
 * comes from that same module.
 */
fun openPdf(fd: ParcelFileDescriptor): ScoreDocument {
    val doc = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
        ) {
            PreVDocument(PdfRendererPreV(fd))
        } else {
            PlatformDocument(PdfRenderer(fd))
        }
    } catch (e: SecurityException) {
        fd.close()
        throw PasswordProtectedException(e)
    } catch (e: Exception) {
        fd.close()
        throw e
    }
    if (doc.pageCount == 0) {
        doc.close()
        throw IOException("No pages")
    }
    return doc
}

private fun blankBitmap(size: PixelSize, reuse: Bitmap?): Bitmap {
    val bitmap = reuse?.takeIf { it.isMutable && it.width == size.width && it.height == size.height }
        ?: createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    return bitmap.apply { eraseColor(Color.WHITE) }
}

/** One open page of either renderer, with what cropping needs. */
private interface OpenPage {
    val width: Int
    val height: Int

    /** Draws the page into [bitmap]; [transform] maps page points to bitmap pixels, null scales it to fit. */
    fun render(bitmap: Bitmap, transform: Matrix?)
}

/** The rendering both renderers share; they differ only in how a page is opened and drawn. */
private abstract class PdfiumDocument : ScoreDocument {
    protected abstract fun <T> withPage(index: Int, block: (OpenPage) -> T): T

    override fun scanPage(index: Int): PageScan = withPage(index, ::scan)

    override fun renderPage(index: Int, max: PixelSize, reuse: Bitmap?, area: PageArea): Bitmap = withPage(index) { page ->
        val cropWidth = page.width * (area.right - area.left)
        val cropHeight = page.height * (area.bottom - area.top)
        require(cropWidth > 0 && cropHeight > 0) { "Nothing of the page to render: $area" }
        require(max.width > 0 && max.height > 0) { "No room to render: $max" }
        // Fitted in exact numbers, at the scale of the side that limits it: a small area at a large size (zoomed
        // in) keeps its exact shape, so the render lines up with the page and the marks to the pixel.
        val scale = minOf(max.width / cropWidth, max.height / cropHeight)
        val size = PixelSize((cropWidth * scale).roundToInt().coerceIn(1, max.width), (cropHeight * scale).roundToInt().coerceIn(1, max.height))
        blankBitmap(size, reuse).also { bitmap ->
            page.render(bitmap, Matrix().apply {
                setScale(scale, scale)
                preTranslate(-page.width * area.left, -page.height * area.top)
            })
        }
    }

    /** A small render of the whole page, scanned for content and paper colour. */
    private fun scan(page: OpenPage): PageScan {
        val small = fitInside(page.width, page.height, PixelSize(DETECT_SIZE, DETECT_SIZE))
        val bitmap = blankBitmap(small, null)
        page.render(bitmap, null)
        val pixels = IntArray(small.width * small.height)
        bitmap.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        bitmap.recycle()
        return PageScan(contentArea(pixels, small.width, small.height), paperColor(pixels))
    }

    private companion object {
        /** Big enough that a thin staff line keeps some contrast, small enough to cost a few ms. */
        const val DETECT_SIZE = 400
    }
}

private class PlatformDocument(private val renderer: PdfRenderer) : PdfiumDocument() {
    override val pageCount get() = renderer.pageCount

    override fun <T> withPage(index: Int, block: (OpenPage) -> T): T = renderer.openPage(index).use { page ->
        block(object : OpenPage {
            override val width = page.width
            override val height = page.height
            override fun render(bitmap: Bitmap, transform: Matrix?) =
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        })
    }

    override fun close() = renderer.close()
}

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
private class PreVDocument(private val renderer: PdfRendererPreV) : PdfiumDocument() {
    override val pageCount get() = renderer.pageCount

    override fun <T> withPage(index: Int, block: (OpenPage) -> T): T = renderer.openPage(index).use { page ->
        block(object : OpenPage {
            override val width = page.width
            override val height = page.height
            override fun render(bitmap: Bitmap, transform: Matrix?) =
                page.render(bitmap, null, transform, RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build())
        })
    }

    override fun close() = renderer.close()
}
