package nl.imreboersma.volti.ui

import nl.imreboersma.volti.drive.SyncState
import nl.imreboersma.volti.drive.DriveProblem
import nl.imreboersma.volti.drive.ImportProgress
import nl.imreboersma.volti.drive.ImportStep
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
        val offline = SyncState.Failed(DriveProblem.Unreachable)
        assertEquals("Couldn't reach Google Drive. Synced 2 hours ago.", syncText(offline, before(120), now))
        assertEquals("Couldn't reach Google Drive. Nothing synced yet.", syncText(offline, null, now))
    }

    @Test
    fun aLongImportSaysHowFarItIsWithThousandsSeparated() {
        assertEquals("Copying 12 of 1,300…", importProgressText(ImportProgress(ImportStep.Copying, 11, 1300)))
        assertEquals("Importing 1 of 3…", importProgressText(ImportProgress(ImportStep.Importing, 0, 3)))
    }

    @Test
    fun eachDriveProblemHasOneWordingAndAnErrorCodeSaysWhatToDo() {
        assertEquals(SIGNED_OUT, syncProblemText(DriveProblem.SignedOut))
        assertEquals(SIGNED_OUT, fileProblemText(DriveProblem.SignedOut))
        val error = "Google Drive answered with an error (503). Try again later."
        assertEquals(error, syncProblemText(DriveProblem.DriveError(503)))
        assertEquals(error, fileProblemText(DriveProblem.DriveError(503)))
    }
}
