package nl.imreboersma.volti.annotate

import android.database.sqlite.SQLiteFullException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Why marks can't be saved: the device is full, or the database can't be written. */
enum class MarkProblem { Full, Unwritable }

/**
 * Where marks are written to the database: one after another, in the order they were made (a stroke and its undo
 * never land the wrong way round), in a scope that outlives the reader. Leaving the reader right after the pen
 * lifts doesn't cancel the write; only the process ending can still lose what is queued, a few milliseconds' worth.
 */
object MarkWrites {
    /** A write, or (not [saves]) only a mark in the queue that [flush] waits for. */
    private class Item(val saves: Boolean, val block: suspend () -> Unit)

    private val queue = Channel<Item>(Channel.UNLIMITED)

    private val _problem = MutableStateFlow<MarkProblem?>(null)

    /** Why the last mark couldn't be saved; null once one is saved again. The reader says it, as long as it holds. */
    val problem: StateFlow<MarkProblem?> = _problem.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (item in queue) {
                try {
                    item.block()
                    // Waiting isn't saving: only a mark saved again ends the problem.
                    if (item.saves) _problem.value = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // This mark isn't kept: said in the reader, not only here. The next ones are tried still.
                    Log.w("Volti", "Can't save a mark", e)
                    _problem.value = if (e is SQLiteFullException) MarkProblem.Full else MarkProblem.Unwritable
                }
            }
        }
    }

    /**
     * A reader opened: what was said about an earlier failure is left behind. If the device is still full, the next
     * mark says so again at once.
     */
    fun forgetProblem() {
        _problem.value = null
    }

    /** After every write asked for before it. */
    fun write(block: suspend () -> Unit) {
        queue.trySend(Item(saves = true, block))
    }

    /** Waits until the writes asked for so far are done: for tests, and whoever must read them back at once. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        queue.send(Item(saves = false) { done.complete(Unit) })
        done.await()
    }
}
