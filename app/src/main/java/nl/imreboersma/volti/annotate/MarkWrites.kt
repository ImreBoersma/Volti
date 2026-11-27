package nl.imreboersma.volti.annotate

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Where marks are written to the database: one after another, in the order they were made (a stroke and its undo
 * never land the wrong way round), in a scope that outlives the reader. Leaving the reader right after the pen
 * lifts doesn't cancel the write; only the process ending can still lose what is queued, a few milliseconds' worth.
 */
object MarkWrites {
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (write in queue) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A full disk, say: this mark isn't kept, the next ones still are.
                    Log.w("Volti", "Can't save a mark", e)
                }
            }
        }
    }

    /** After every write asked for before it. */
    fun write(block: suspend () -> Unit) {
        queue.trySend(block)
    }

    /** Waits until the writes asked for so far are done: for tests, and whoever must read them back at once. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        queue.send { done.complete(Unit) }
        done.await()
    }
}
