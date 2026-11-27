package nl.imreboersma.volti.ui

import nl.imreboersma.volti.drive.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStateTest {
    private val sync = SyncState.Downloading(done = 1, total = 3, now = "b", waiting = setOf("c"))

    @Test
    fun theSheetDownloadingNowAndTheOnesStillToComeShowIt() {
        assertEquals(Download.Now, downloadOf("b", sync, setOf("b")))
        assertEquals(Download.Waiting, downloadOf("c", sync, emptySet()))
        assertEquals(null, downloadOf("a", sync, emptySet())) // Done already, or never in this sync.
    }

    @Test
    fun aSheetDownloadingForAnyReasonIsDownloadingNowWhateverTheSyncDoes() {
        assertEquals(Download.Now, downloadOf("c", sync, setOf("c")))
        assertEquals(Download.Now, downloadOf("x", SyncState.Listing, setOf("x")))
        assertEquals(null, downloadOf("x", SyncState.Listing, emptySet()))
    }

    @Test
    fun checkedButNotStartedSaysWhatIsToCome() {
        val planned = SyncState.Downloading(0, 1300, now = null, waiting = setOf("a"))
        assertEquals("1,300 to download", syncText(planned, null, 0))
        assertEquals("Downloading 2 of 3…", syncText(sync, null, 0))
    }
}
