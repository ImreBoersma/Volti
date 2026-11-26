package nl.imreboersma.volti.render

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import android.system.Os
import android.system.OsConstants
import android.util.Log
import nl.imreboersma.volti.BuildConfig
import nl.imreboersma.volti.data.PasswordProtectedException
import nl.imreboersma.volti.data.PixelSize
import nl.imreboersma.volti.data.ScoreDocument
import nl.imreboersma.volti.data.checkArea
import nl.imreboersma.volti.data.openPdf
import java.io.File
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
    private val watchdog = Watchdog(
        timeoutMillis = TimeUnit.SECONDS.toMillis(CALL_TIMEOUT_SECONDS),
        memoryGrowthLimitBytes = MEMORY_GROWTH_LIMIT_BYTES,
        memoryUsed = ::residentBytes,
    ) { reason ->
        Log.w("Volti", "PDF renderer $reason; stopping it")
        Process.killProcess(Process.myPid())
    }

    /** Runs [block] under the one lock the renderer needs, with this process killed if it takes too long. */
    private fun <T> guarded(block: () -> T): T = synchronized(this) {
        val result = try {
            watchdog.guard(block)
        } catch (_: PasswordProtectedException) {
            // Binder only carries a few exception types back to the app: SecurityException means a password.
            throw SecurityException("Password-protected PDF")
        } catch (e: Exception) {
            // Untrusted input: whatever the parser throws means the file can't be shown.
            throw IllegalStateException(e.toString())
        }
        // Debug only, for measuring: R8 strips Log.d from release, but not a call in its argument.
        if (BuildConfig.DEBUG) Log.d("Volti", "Renderer peak memory: ${peakResidentBytes() shr 20} MB")
        result
    }

    private fun document(id: Int) = documents[id] ?: throw IllegalStateException("No document $id")

    private val binder = object : IPdfRenderer.Stub() {
        override fun open(fd: ParcelFileDescriptor): Int = guarded {
            val id = nextId++
            documents[id] = openPdf(fd)
            id
        }

        override fun pageCount(doc: Int): Int = guarded { document(doc).pageCount }

        override fun scanPage(doc: Int, index: Int): FloatArray = guarded {
            document(doc).scanPage(index).let { (a, c) ->
                floatArrayOf(a.left, a.top, a.right, a.bottom, ((c shr 16) and 0xff).toFloat(), ((c shr 8) and 0xff).toFloat(), (c and 0xff).toFloat())
            }
        }

        override fun renderPage(
            doc: Int,
            index: Int,
            maxWidth: Int,
            maxHeight: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            pixels: SharedMemory,
        ): IntArray =
            // Closes this process's copy of the descriptor, whatever happens.
            pixels.use {
                guarded {
                    val area = checkArea(floatArrayOf(left, top, right, bottom))
                    val bitmap = document(doc).renderPage(index, PixelSize(maxWidth, maxHeight), area = area)
                    val buffer = pixels.mapReadWrite()
                    try {
                        bitmap.copyPixelsToBuffer(buffer)
                    } finally {
                        SharedMemory.unmap(buffer)
                    }
                    intArrayOf(bitmap.width, bitmap.height).also { bitmap.recycle() }
                }
            }

        override fun close(doc: Int) = guarded { documents.remove(doc)?.close(); Unit }
    }

    override fun onBind(intent: Intent): IBinder = binder

    companion object {
        /** Longer than any sane page render (about 100 ms for a 200 dpi scan), short enough to recover on stage. */
        const val CALL_TIMEOUT_SECONDS = 10L

        /**
         * A crafted PDF can make PDFium grow by 500 MB a second (form-bomb.pdf), squeezing the whole device: on
         * the CI emulator it took the system down. One normal render adds far less; see plan.md. Polled every
         * 50 ms, so a fast runaway overshoots by what it touches in that time.
         */
        const val MEMORY_GROWTH_LIMIT_BYTES = 300L shl 20

        private val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)

        /** Resident memory of this process, from /proc/self/statm: cheap enough to read every 50 ms. */
        fun residentBytes(): Long =
            File("/proc/self/statm").readText().split(' ')[1].toLong() * pageSize

        /** The most this process has had resident, from /proc/self/status (VmHWM). */
        fun peakResidentBytes(): Long =
            File("/proc/self/status").useLines { lines ->
                lines.first { it.startsWith("VmHWM:") }.filter(Char::isDigit).toLong() * 1024
            }
    }
}
