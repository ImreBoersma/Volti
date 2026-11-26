package nl.imreboersma.volti

import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** Stage mode in the real app: on in the reader, off in the library, whatever the navigation timing. */
@RunWith(AndroidJUnit4::class)
class StageModeNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var nav: NavHostController
    private lateinit var scoreA: Reader
    private lateinit var scoreB: Reader

    private fun pdf(name: String): Reader {
        val file = File(tmp.root, name)
        val doc = PdfDocument()
        doc.finishPage(doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create()))
        file.outputStream().use(doc::writeTo)
        doc.close()
        return Reader(Uri.fromFile(file).toString())
    }

    @Before
    fun setUp() {
        scoreA = pdf("a.pdf")
        scoreB = pdf("b.pdf")
        compose.setContent {
            nav = rememberNavController()
            VoltiApp(nav)
        }
        compose.waitForIdle()
    }

    private fun screenOn() =
        compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    private fun onMain(block: () -> Unit) = compose.runOnUiThread(block)

    @Test
    fun onInTheReaderAndOffBackInTheLibrary() {
        assertEquals(false, screenOn())

        onMain { nav.navigate(scoreA) }
        compose.waitForIdle()
        assertEquals(true, screenOn())

        onMain { nav.popBackStack() }
        compose.waitForIdle()
        assertEquals(false, screenOn())
    }

    @Test
    fun reopeningDuringTheCrossfadeKeepsTheScreenOn() {
        compose.mainClock.autoAdvance = false
        onMain { nav.navigate(scoreA) }
        compose.mainClock.advanceTimeBy(1_000)

        onMain { nav.popBackStack() }
        compose.mainClock.advanceTimeBy(100) // Reader A is still fading out.
        onMain { nav.navigate(scoreB) }
        compose.mainClock.advanceTimeBy(1_000) // Reader A has now left the composition.

        assertEquals(true, screenOn())
    }

    @Test
    fun offWhenTheFileCannotBeOpened() {
        val fake = File(tmp.root, "fake.pdf").apply { writeText("hello") }
        onMain { nav.navigate(Reader(Uri.fromFile(fake).toString())) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Can't open this file.").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals(false, screenOn())
    }
}
