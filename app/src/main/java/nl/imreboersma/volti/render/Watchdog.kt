package nl.imreboersma.volti.render

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Watches guarded calls from its own thread, so it keeps running while a call is stuck in native code.
 * [onTrip] runs when a call takes longer than [timeoutMillis], or when [memoryUsed] grows by more than
 * [memoryGrowthLimitBytes] during a call (checked every [memoryCheckMillis]). Growth, not the total: a
 * long-lived process creeps up between calls (freed memory returns lazily, open documents stay), and a
 * normal page on top of that must not trip it.
 */
class Watchdog(
    private val timeoutMillis: Long,
    private val memoryGrowthLimitBytes: Long = Long.MAX_VALUE,
    private val memoryUsed: () -> Long = { 0 },
    private val memoryCheckMillis: Long = 50,
    private val onTrip: (reason: String) -> Unit,
) {
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "volti-watchdog").apply { isDaemon = true } }

    /** The call being watched. A check that was already running when its call ended sees a newer id and does nothing. */
    private val current = AtomicLong()

    fun <T> guard(block: () -> T): T {
        val call = current.incrementAndGet()
        val before = memoryUsed()
        fun trip(reason: String) {
            if (current.get() == call) onTrip(reason)
        }
        val alarm = timer.schedule({ trip("took longer than $timeoutMillis ms") }, timeoutMillis, TimeUnit.MILLISECONDS)
        val memory = timer.scheduleWithFixedDelay({
            val grown = memoryUsed() - before
            if (grown > memoryGrowthLimitBytes) trip("grew ${grown shr 20} MB in one call, limit ${memoryGrowthLimitBytes shr 20} MB")
        }, memoryCheckMillis, memoryCheckMillis, TimeUnit.MILLISECONDS)
        try {
            return block()
        } finally {
            current.incrementAndGet()
            alarm.cancel(false)
            memory.cancel(false)
        }
    }
}
