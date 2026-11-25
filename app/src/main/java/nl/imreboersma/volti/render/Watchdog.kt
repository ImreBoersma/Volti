package nl.imreboersma.volti.render

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs [onTimeout] on its own thread when a guarded call takes longer than [timeoutMillis]. */
class Watchdog(private val timeoutMillis: Long, private val onTimeout: () -> Unit) {
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "volti-watchdog").apply { isDaemon = true } }

    fun <T> guard(block: () -> T): T {
        val alarm = timer.schedule(onTimeout, timeoutMillis, TimeUnit.MILLISECONDS)
        try {
            return block()
        } finally {
            alarm.cancel(false)
        }
    }
}
