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

    @Test
    fun firesWhenMemoryGoesOverTheLimitDuringACall() {
        val used = java.util.concurrent.atomic.AtomicLong(10)
        val reasons = java.util.Collections.synchronizedList(mutableListOf<String>())
        val watchdog = Watchdog(timeoutMillis = 10_000, memoryGrowthLimitBytes = 100, memoryUsed = used::get, memoryCheckMillis = 10) {
            reasons += it
        }

        watchdog.guard {
            Thread.sleep(50)
            used.set(500) // A runaway render.
            Thread.sleep(100)
        }

        assertTrue("watchdog did not see the memory: $reasons", reasons.any { "limit" in it })
    }

    @Test
    fun memoryIsNotWatchedOutsideCalls() {
        val reasons = java.util.Collections.synchronizedList(mutableListOf<String>())
        val watchdog = Watchdog(timeoutMillis = 10_000, memoryGrowthLimitBytes = 100, memoryUsed = { 500 }, memoryCheckMillis = 10) {
            reasons += it
        }

        watchdog.guard { }
        Thread.sleep(100)

        assertEquals(emptyList<String>(), reasons.toList())
    }

    @Test
    fun aHighStartingPointIsNotGrowth() {
        // Between calls the process creeps up; a normal call on top of that must not trip.
        val used = java.util.concurrent.atomic.AtomicLong(1_000)
        val reasons = java.util.Collections.synchronizedList(mutableListOf<String>())
        val watchdog = Watchdog(timeoutMillis = 10_000, memoryGrowthLimitBytes = 100, memoryUsed = used::get, memoryCheckMillis = 10) {
            reasons += it
        }

        watchdog.guard {
            used.set(1_050) // Grew 50: fine, even though the total is far above 100.
            Thread.sleep(80)
        }

        assertEquals(emptyList<String>(), reasons.toList())
    }

    @Test
    fun aCheckStillRunningWhenTheCallEndsDoesNothing() {
        // The memory reading is slow; by the time it sees a high value, the call is over.
        val reasons = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reading = CountDownLatch(1)
        var calls = 0
        val watchdog = Watchdog(timeoutMillis = 10_000, memoryGrowthLimitBytes = 100, memoryCheckMillis = 5, memoryUsed = {
            if (calls++ == 0) 0 else { reading.countDown(); Thread.sleep(100); 1_000 }
        }) { reasons += it }

        watchdog.guard { reading.await(2, TimeUnit.SECONDS) }
        Thread.sleep(200)

        assertEquals(emptyList<String>(), reasons.toList())
    }
}
