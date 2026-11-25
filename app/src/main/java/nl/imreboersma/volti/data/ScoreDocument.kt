package nl.imreboersma.volti.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.net.Uri
import android.os.Build
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

/** An open PDF. Not thread-safe, and only one page may be open at a time: use from one thread. */
interface ScoreDocument : Closeable {
    val pageCount: Int

    /** Renders page [index] on white, as large as fits inside [max] with the page's aspect ratio. */
    fun renderPage(index: Int, max: PixelSize): Bitmap
}

/**
 * Opens [uri] read-only. On Android 14 this uses [PdfRendererPreV] when available: it lives in the
 * MediaProvider module, which Google Play keeps patched. The platform [PdfRenderer] on Android 14
 * uses the system PDFium, which only firmware updates patch. From Android 15 the platform class
 * comes from that same module.
 */
fun openScore(resolver: ContentResolver, uri: Uri): ScoreDocument {
    val fd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
    val doc = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
        ) {
            PreVDocument(PdfRendererPreV(fd))
        } else {
            PlatformDocument(PdfRenderer(fd))
        }
    } catch (e: Exception) {
        fd.close()
        throw e
    }
    if (doc.pageCount == 0) {
        doc.close()
        throw IOException("No pages: $uri")
    }
    return doc
}

private fun blankBitmap(size: PixelSize): Bitmap =
    createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

private class PlatformDocument(private val renderer: PdfRenderer) : ScoreDocument {
    override val pageCount get() = renderer.pageCount

    override fun renderPage(index: Int, max: PixelSize): Bitmap = renderer.openPage(index).use { page ->
        blankBitmap(fitInside(page.width, page.height, max)).also {
            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    override fun close() = renderer.close()
}

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
private class PreVDocument(private val renderer: PdfRendererPreV) : ScoreDocument {
    override val pageCount get() = renderer.pageCount

    override fun renderPage(index: Int, max: PixelSize): Bitmap = renderer.openPage(index).use { page ->
        blankBitmap(fitInside(page.width, page.height, max)).also {
            page.render(it, null, null, RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build())
        }
    }

    override fun close() = renderer.close()
}
