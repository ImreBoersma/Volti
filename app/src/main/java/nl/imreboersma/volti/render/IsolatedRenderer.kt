package nl.imreboersma.volti.render

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.IBinder
import android.os.RemoteException
import android.os.SharedMemory
import android.util.Log
import androidx.core.graphics.createBitmap
import nl.imreboersma.volti.data.PageArea
import nl.imreboersma.volti.data.PasswordProtectedException
import nl.imreboersma.volti.data.PixelSize
import nl.imreboersma.volti.data.ScoreDocument
import nl.imreboersma.volti.data.checkArea
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Opens [uri] in the isolated PdfRenderService. The app opens the file (it holds the folder grant) and hands
 * the service only the descriptor. Blocking: call off the main thread, which delivers the connection.
 */
fun openIsolated(context: Context, uri: Uri): ScoreDocument = RemoteDocument(context.applicationContext, uri)

/**
 * What came back from the renderer must fit what was asked for. The renderer parses untrusted files and may
 * be compromised: an absurd size would make the app allocate itself to death.
 */
internal fun checkRenderedSize(size: IntArray?, max: PixelSize, sharedBytes: Int): PixelSize {
    if (size == null || size.size != 2) throw IOException("Renderer returned no size")
    val (width, height) = size
    if (width !in 1..max.width || height !in 1..max.height || width.toLong() * height * 4 > sharedBytes) {
        throw IOException("Renderer returned an impossible size: $width x $height for $max")
    }
    return PixelSize(width, height)
}

/** Turns the service's failures into the exceptions the reader already understands. RemoteException passes. */
private fun <T> mapped(block: () -> T): T = try {
    block()
} catch (e: SecurityException) {
    throw PasswordProtectedException(e)
} catch (e: IllegalStateException) {
    throw IOException(e.message, e)
}

/**
 * A document open in the renderer. A renderer that died before a call (an earlier page crashed or hung it, or
 * Android reclaimed the idle process) is replaced first: the document is opened again in a fresh one. A
 * renderer that dies during a call was killed by that page: the call throws and the reader marks only that
 * page as broken, without trying it again.
 */
private class RemoteDocument(private val context: Context, private val uri: Uri) : ScoreDocument {
    private lateinit var service: IPdfRenderer
    private var id = 0
    override val pageCount: Int

    /** The pixels come back through this, sized for the view. Used by one render at a time. */
    private var pixels: SharedMemory? = null

    init {
        pageCount = try {
            openInFreshService()
        } catch (e: RemoteException) {
            throw IOException("PDF renderer stopped", e)
        }
    }

    /** Opens the document in the current renderer, starting one if needed. Returns the page count. */
    private fun openInFreshService(): Int {
        service = RendererConnection.get(context)
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
        return fd.use {
            mapped {
                id = service.open(fd)
                service.pageCount(id)
            }
        }
    }

    /** Runs [call] in a live renderer, reopening the document first if the last one died. */
    private fun <T> surviving(call: () -> T): T {
        try {
            if (!service.asBinder().isBinderAlive) {
                Log.w("Volti", "PDF renderer had stopped; reopening $uri")
                openInFreshService()
            }
            return mapped(call)
        } catch (e: RemoteException) {
            throw IOException("PDF renderer stopped", e)
        }
    }

    override fun contentArea(index: Int): PageArea = checkArea(surviving { service.contentArea(id, index) })

    override fun renderPage(index: Int, max: PixelSize, reuse: Bitmap?, area: PageArea): Bitmap {
        val bytes = max.width * max.height * 4
        val shared = pixels?.takeIf { it.size >= bytes } ?: SharedMemory.create("volti-page", bytes).also {
            pixels?.close()
            pixels = it
        }
        val size = surviving {
            service.renderPage(id, index, max.width, max.height, area.left, area.top, area.right, area.bottom, shared)
        }
        val (width, height) = checkRenderedSize(size, max, shared.size)
        val bitmap = reuse?.takeIf { it.isMutable && it.width == width && it.height == height }
            ?: createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val buffer = shared.mapReadOnly()
        try {
            bitmap.copyPixelsFromBuffer(buffer)
        } finally {
            SharedMemory.unmap(buffer)
        }
        return bitmap
    }

    override fun close() {
        try {
            service.close(id)
        } catch (_: RemoteException) {
            // The renderer is gone, and the document with it.
        } catch (_: IllegalStateException) {
        }
        pixels?.close()
        pixels = null
    }
}

/**
 * One binding for the whole app. Holds only the application context, which lives as long as the process:
 * not a leak.
 *
 * After the service crashes, the system restarts it with a backoff that grew to 19 s on the tablet under
 * the memory pressure a crash causes. Binding again doesn't help while a restart is pending; unbinding
 * first drops the service record and its pending restart, so the next bind starts a fresh process at once.
 * A dead binder is the signal, not onServiceDisconnected: that arrives later, posted to the main thread.
 */
@SuppressLint("StaticFieldLeak")
private object RendererConnection : ServiceConnection {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var service: IPdfRenderer? = null
    private var bound = false
    private var app: Context? = null

    fun get(context: Context): IPdfRenderer = lock.withLock {
        app = context.applicationContext
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var current = service
        while (current == null || !current.asBinder().isBinderAlive) {
            if (current != null || !bound) rebind() // A dead binder, or not bound at all.
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0) throw IOException("PDF renderer did not start")
            changed.await(left, TimeUnit.MILLISECONDS)
            current = service
        }
        current
    }

    /** Under [lock]. */
    private fun rebind() {
        val context = app!!
        if (bound) context.unbindService(this)
        service = null
        bound = context.bindService(Intent(context, PdfRenderService::class.java), this, Context.BIND_AUTO_CREATE)
        if (!bound) throw IOException("Can't start the PDF renderer")
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) = lock.withLock {
        service = IPdfRenderer.Stub.asInterface(binder)
        changed.signalAll()
    }

    override fun onServiceDisconnected(name: ComponentName) = lock.withLock {
        // The binder is already dead, and get() rebinds on that. Wake anyone waiting so they do.
        changed.signalAll()
    }

    override fun onBindingDied(name: ComponentName) = lock.withLock {
        // The system gave up on this binding: drop it, so get() binds afresh.
        app?.unbindService(this)
        bound = false
        service = null
        changed.signalAll()
    }
}
