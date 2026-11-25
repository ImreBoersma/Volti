package nl.imreboersma.volti.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WatchdogTest {
    @Test
    fun firesWhileACallHangs() {
        val fired = CountDownLatch(1)
        val watchdog = Watchdog(timeoutMillis = 50) { fired.countDown() }

        // The call is still running when the alarm goes off, as with a native render that never returns.
        watchdog.guard { assertTrue("watchdog did not fire", fired.await(2, TimeUnit.SECONDS)) }
    }

    @Test
    fun staysQuietForFastCallsAndAfterwards() {
        val fired = AtomicInteger()
        val watchdog = Watchdog(timeoutMillis = 50) { fired.incrementAndGet() }

        repeat(20) { watchdog.guard { Thread.sleep(5) } }
        Thread.sleep(150) // Well past the timeout of every call above.

        assertEquals(0, fired.get())
    }

    @Test
    fun aCallThatThrowsStillCancelsItsAlarm() {
        val fired = AtomicInteger()
        val watchdog = Watchdog(timeoutMillis = 50) { fired.incrementAndGet() }

        runCatching { watchdog.guard { throw IllegalStateException("broken page") } }
        Thread.sleep(150)

        assertEquals(0, fired.get())
    }
}
