package nl.imreboersma.volti.ui

import nl.imreboersma.volti.drive.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatusTest {
    private val now = 10L * 24 * 60 * 60_000
    private fun before(minutes: Long) = now - minutes * 60_000

    @Test
    fun howLongAgo() {
        assertEquals("just now", ago(now - 30_000, now))
        assertEquals("1 min ago", ago(before(1), now))
        assertEquals("59 min ago", ago(before(59), now))
        assertEquals("1 hour ago", ago(before(60), now))
        assertEquals("23 hours ago", ago(before(23 * 60 + 59), now))
        assertEquals("2 days ago", ago(before(48 * 60), now))
        assertEquals("just now", ago(now + 5_000, now)) // A clock that moved back.
    }

    @Test
    fun whatTheSyncLineSays() {
        assertEquals("Not synced yet", syncText(SyncState.Idle, null, now))
        assertEquals("Synced 5 min ago", syncText(SyncState.Idle, before(5), now))
        assertEquals("Downloading 3 of 10…", syncText(SyncState.Downloading(2, 10), null, now))
        assertEquals("Up to date", syncText(SyncState.Done(stopped = false, failed = 0), now, now))
        assertEquals(
            "Up to date. 1 PDF couldn't be downloaded; it is tried again next time",
            syncText(SyncState.Done(stopped = false, failed = 1), now, now),
        )
    }

    @Test
    fun offlineItSaysHowOldTheCopyIs() {
        val offline = SyncState.Failed("No connection to Google Drive.")
        assertEquals("No connection to Google Drive. Synced 2 hours ago.", syncText(offline, before(120), now))
        assertEquals("No connection to Google Drive. Nothing synced yet.", syncText(offline, null, now))
    }
}
