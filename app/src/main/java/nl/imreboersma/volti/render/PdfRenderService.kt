package nl.imreboersma.volti.render

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import android.util.Log
import nl.imreboersma.volti.data.PasswordProtectedException
import nl.imreboersma.volti.data.PixelSize
import nl.imreboersma.volti.data.ScoreDocument
import nl.imreboersma.volti.data.openPdf
import java.util.concurrent.TimeUnit

/**
 * Errors reach the app as SecurityException (password) or IllegalStateException (anything else).
 *
 * Runs PDFium in an isolated process (see the manifest): no permissions, no folder grant, only the file
 * descriptors the app hands over. A native crash takes down this process only. A call that hangs is ended
 * by the watchdog killing this process, which also frees PDFium's process-wide lock.
 */
class PdfRenderService : Service() {
    private val documents = mutableMapOf<Int, ScoreDocument>()
    private var nextId = 1
    private val watchdog = Watchdog(TimeUnit.SECONDS.toMillis(CALL_TIMEOUT_SECONDS)) {
        Log.w("Volti", "PDF renderer stuck for $CALL_TIMEOUT_SECONDS s; restarting it")
        Process.killProcess(Process.myPid())
    }

    /** Runs [block] under the one lock the renderer needs, with this process killed if it takes too long. */
    private fun <T> guarded(block: () -> T): T = synchronized(this) {
        try {
            watchdog.guard(block)
        } catch (e: PasswordProtectedException) {
            // Binder only carries a few exception types back to the app: SecurityException means a password.
            throw SecurityException("Password-protected PDF")
        } catch (e: Exception) {
            // Untrusted input: whatever the parser throws means the file can't be shown.
            throw IllegalStateException(e.toString())
        }
    }

    private fun document(id: Int) = documents[id] ?: throw IllegalStateException("No document $id")

    private val binder = object : IPdfRenderer.Stub() {
        override fun open(fd: ParcelFileDescriptor): Int = guarded {
            val id = nextId++
            documents[id] = openPdf(fd)
            id
        }

        override fun pageCount(doc: Int): Int = guarded { document(doc).pageCount }

        override fun renderPage(doc: Int, index: Int, maxWidth: Int, maxHeight: Int, pixels: SharedMemory): IntArray =
            try {
                guarded {
                    val bitmap = document(doc).renderPage(index, PixelSize(maxWidth, maxHeight))
                    val buffer = pixels.mapReadWrite()
                    try {
                        bitmap.copyPixelsToBuffer(buffer)
                    } finally {
                        SharedMemory.unmap(buffer)
                    }
                    intArrayOf(bitmap.width, bitmap.height).also { bitmap.recycle() }
                }
            } finally {
                pixels.close() // This process's copy of the descriptor, whatever happened.
            }

        override fun close(doc: Int) = guarded { documents.remove(doc)?.close(); Unit }
    }

    override fun onBind(intent: Intent): IBinder = binder

    companion object {
        /** Longer than any sane page render (about 100 ms for a 200 dpi scan), short enough to recover on stage. */
        const val CALL_TIMEOUT_SECONDS = 10L
    }
}
