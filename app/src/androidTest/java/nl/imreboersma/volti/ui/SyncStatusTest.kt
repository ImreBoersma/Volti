package nl.imreboersma.volti.ui

import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.imreboersma.volti.drive.DriveProblem
import nl.imreboersma.volti.drive.ImportProgress
import nl.imreboersma.volti.drive.ImportStep
import nl.imreboersma.volti.drive.Rejection
import nl.imreboersma.volti.drive.SyncState
import nl.imreboersma.volti.setlist.Skip
import nl.imreboersma.volti.setlist.SkipReason
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** The sync line, import progress and Drive problems as the musician reads them, from strings.xml. */
@RunWith(AndroidJUnit4::class)
class SyncStatusTest {
    private val now = 10L * 24 * 60 * 60_000
    private fun before(minutes: Long) = now - minutes * 60_000

    private fun resources(locale: Locale): Resources {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration).resources
    }

    private val res = resources(Locale.ENGLISH)

    @Test
    fun howLongAgo() {
        assertEquals("Synced just now", synced(res, now - 30_000, now, sentence = false))
        assertEquals("Synced 1 min ago", synced(res, before(1), now, sentence = false))
        assertEquals("Synced 59 min ago", synced(res, before(59), now, sentence = false))
        assertEquals("Synced 1 hour ago", synced(res, before(60), now, sentence = false))
        assertEquals("Synced 23 hours ago", synced(res, before(23 * 60 + 59), now, sentence = false))
        assertEquals("Synced 2 days ago.", synced(res, before(48 * 60), now, sentence = true))
        assertEquals("Synced just now", synced(res, now + 5_000, now, sentence = false)) // A clock that moved back.
    }

    @Test
    fun whatTheSyncLineSays() {
        assertEquals("Not synced yet", syncText(res, SyncState.Idle, null, now))
        assertEquals("Synced 5 min ago", syncText(res, SyncState.Idle, before(5), now))
        assertEquals("Downloading 3 of 10…", syncText(res, SyncState.Downloading(2, 10, now = "x"), null, now))
        assertEquals("Up to date", syncText(res, SyncState.Done(stopped = false, failed = 0), now, now))
        assertEquals(
            "Up to date. 1 part couldn't be downloaded; it is tried again next time",
            syncText(res, SyncState.Done(stopped = false, failed = 1), now, now),
        )
        assertEquals(
            "Up to date. 2 parts couldn't be downloaded; they are tried again next time. Stopped at 5,000 entries: the deepest files are left out",
            syncText(res, SyncState.Done(stopped = true, failed = 2), now, now),
        )
    }

    @Test
    fun checkedButNotStartedSaysWhatIsToCome() {
        val planned = SyncState.Downloading(0, 1300, now = null, waiting = setOf("a"))
        assertEquals("1,300 to download", syncText(res, planned, null, 0))
    }

    @Test
    fun offlineItSaysHowOldTheCopyIs() {
        val offline = SyncState.Failed(DriveProblem.Unreachable)
        assertEquals("Couldn't reach Google Drive. Synced 2 hours ago.", syncText(res, offline, before(120), now))
        assertEquals("Couldn't reach Google Drive. Nothing synced yet.", syncText(res, offline, null, now))
    }

    @Test
    fun aLongImportSaysHowFarItIsWithThousandsSeparated() {
        assertEquals("Copying 12 of 1,300…", importProgressText(res, ImportProgress(ImportStep.Copying, 11, 1300)))
        assertEquals("Importing 1 of 3…", importProgressText(res, ImportProgress(ImportStep.Importing, 0, 3)))
        assertEquals("Copying…", importProgressText(res, ImportProgress(ImportStep.Copying, 0, 0)))
        assertEquals("Importing…", importProgressText(res, ImportProgress(ImportStep.Importing, 0, 0)))
    }

    @Test
    fun numbersAreWrittenAsTheLanguageWritesThem() {
        assertEquals("5,000", count(res, 5000))
        assertEquals("5.000", count(resources(Locale.forLanguageTag("nl")), 5000))
    }

    @Test
    fun eachDriveProblemHasOneWordingAndAnErrorCodeSaysWhatToDo() {
        assertEquals("Signed out of Google Drive.", syncProblemText(res, DriveProblem.SignedOut))
        assertEquals("Signed out of Google Drive.", fileProblemText(res, DriveProblem.SignedOut))
        val error = "Google Drive answered with an error (503). Try again later."
        assertEquals(error, syncProblemText(res, DriveProblem.DriveError(503)))
        assertEquals(error, fileProblemText(res, DriveProblem.DriveError(503)))
    }

    @Test
    fun aSkippedPieceSaysWhichThenWhy() {
        assertEquals("Farandole skipped. Downloading it took too long.", skippedText(res, Skip("Farandole", SkipReason.TooSlow)))
        assertEquals("A piece skipped. Google Drive answered with an error (503).", skippedText(res, Skip(null, SkipReason.DriveError, 503)))
    }

    @Test
    fun eachDeviceHasItsOwnPhrase() {
        val full = listOf("a.pdf" to Rejection.NoSpace)
        assertEquals("a.pdf wasn't imported: there isn't enough space on this tablet.", notImportedText(res, full, Device.Tablet))
        assertEquals("a.pdf wasn't imported: there isn't enough space on this device.", notImportedText(res, full, Device.Other))
    }
}
