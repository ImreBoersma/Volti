package nl.imreboersma.volti

import android.graphics.Color
import org.junit.After
import nl.imreboersma.volti.data.ReaderSettingsStore
import nl.imreboersma.volti.data.ReaderSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderBackButtonTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var nav: NavHostController

    private val settings = ReaderSettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)
    private lateinit var before: ReaderSettings

    @Before
    fun setUp() {
        // Whole-page turns for these tests, whatever is set on the device; put back afterwards.
        before = runBlocking { settings.settings.first() }
        runBlocking { settings.setHalfPageTurns(false) }
        compose.setContent {
            nav = rememberNavController()
            VoltiApp(nav)
        }
        compose.waitForIdle()
    }

    /** A score of [pages] pages, each with a black block: page 1 small, page 2 large, so they look different. */
    private fun score(pages: Int = 2): Reader {
        val file = File(tmp.root, "score.pdf")
        val doc = PdfDocument()
        repeat(pages) { i ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            page.canvas.drawRect(100f, 100f, 100f + 100f * (i + 1), 300f, Paint().apply { color = Color.BLACK })
            doc.finishPage(page)
        }
        file.outputStream().use(doc::writeTo)
        doc.close()
        return Reader(Uri.fromFile(file).toString())
    }

    @After
    fun restoreSettings() = runBlocking { settings.setHalfPageTurns(before.halfPageTurns) }

    private fun inLibrary() = nav.currentDestination?.hasRoute<Library>() == true

    private fun openReader() {
        compose.runOnUiThread { nav.navigate(score()) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Back to the library").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theButtonGoesBackToTheLibrary() {
        openReader()
        assertTrue(!inLibrary())

        compose.onNodeWithContentDescription("Back to the library").performClick()
        compose.waitForIdle()

        assertTrue("back in the library", inLibrary())
    }

    @Test
    fun aDoubleTapOnBackStillLeavesTheLibrary() {
        openReader()
        val button = compose.onNodeWithContentDescription("Back to the library")
        compose.mainClock.autoAdvance = false // Hold the 700 ms crossfade, so the reader's button is still there.
        button.performClick()
        compose.mainClock.advanceTimeBy(100)
        button.performClick() // During the crossfade: must not pop the library too.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertTrue("the library is still there", inLibrary())
    }

    private fun showing(description: String) = compose.onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description),
    ).fetchSemanticsNodes().isNotEmpty()

    private fun waitForPage(description: String) = compose.waitUntil(10_000) { showing(description) }

    @Test
    fun aTapOnTheLeftBesideTheButtonStillTurnsBack() {
        openReader()
        waitForPage("Page 1 of 2")
        compose.onRoot().performTouchInput { click(Offset(width * 0.75f, height * 0.5f)) }
        waitForPage("Page 2 of 2")
        compose.onRoot().performTouchInput { click(Offset(width * 0.25f, height * 0.5f)) }
        waitForPage("Page 1 of 2")
    }

    @Test
    fun theButtonHidesAndATapInItsCornerBringsItBackWithoutTurning() {
        openReader()
        waitForPage("Page 1 of 2")
        compose.onRoot().performTouchInput { click(Offset(width * 0.75f, height * 0.5f)) }
        waitForPage("Page 2 of 2")
        compose.mainClock.advanceTimeBy(3_500) // The button has hidden itself (on Compose's clock, not the wall's).

        compose.onNodeWithContentDescription("Back to the library").performClick() // Shows it again,
        compose.waitForIdle()
        assertTrue("still reading", !inLibrary())
        assertTrue("and the page didn't turn back", showing("Page 2 of 2"))

        compose.onNodeWithContentDescription("Back to the library").performClick() // and now goes back.
        compose.waitForIdle()
        assertTrue(inLibrary())
    }
}
