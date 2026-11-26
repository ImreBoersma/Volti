package nl.imreboersma.volti

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.imreboersma.volti.data.ReaderSettings
import nl.imreboersma.volti.data.ReaderSettingsStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** Turning pages without touching the screen: a pedal (a keyboard), TalkBack. And Back while annotating. */
@RunWith(AndroidJUnit4::class)
class ReaderInputTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var nav: NavHostController
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val settings = ReaderSettingsStore(instrumentation.targetContext)
    private lateinit var before: ReaderSettings

    @Before
    fun openAScore() {
        before = runBlocking { settings.settings.first() }
        runBlocking { settings.setHalfPageTurns(false) }
        val file = File(tmp.root, "score.pdf")
        val doc = PdfDocument()
        repeat(3) { i ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            page.canvas.drawRect(100f, 100f, 200f + 100f * i, 300f, Paint().apply { color = Color.BLACK })
            doc.finishPage(page)
        }
        file.outputStream().use(doc::writeTo)
        doc.close()
        compose.setContent {
            nav = rememberNavController()
            VoltiApp(nav)
        }
        compose.runOnUiThread { nav.navigate(Reader(Uri.fromFile(file).toString())) }
        waitForPage("Page 1 of 3")
        // Keys go to the window with the focus: on a slow emulator the page shows before the window has it.
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
    }

    @After
    fun restoreSettings() = runBlocking { settings.setHalfPageTurns(before.halfPageTurns) }

    private fun showing(description: String) = compose.onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description),
    ).fetchSemanticsNodes().isNotEmpty()

    private fun waitForPage(description: String) = compose.waitUntil(10_000) { showing(description) }

    private fun press(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun inReader() = nav.currentDestination?.hasRoute<Reader>() == true

    @Test
    fun pedalKeysTurnForwardAndBack() {
        press(KeyEvent.KEYCODE_PAGE_DOWN)
        waitForPage("Page 2 of 3")
        press(KeyEvent.KEYCODE_PAGE_UP)
        waitForPage("Page 1 of 3")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitForPage("Page 2 of 3")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitForPage("Page 1 of 3")
        press(KeyEvent.KEYCODE_SPACE)
        waitForPage("Page 2 of 3")
    }

    @Test
    fun aPedalHeldDownTurnsOnce() {
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, 0))
        repeat(3) { instrumentation.sendKeySync(KeyEvent(now, now + 500L * (it + 1), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, it + 1)) }
        instrumentation.sendKeySync(KeyEvent(now, now + 2000, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_DOWN, 0))
        waitForPage("Page 2 of 3")
        Thread.sleep(500)
        compose.waitForIdle()
        assertTrue("one turn, not four", showing("Page 2 of 3"))
    }

    /** The page actions, on the node TalkBack focuses: the reader's focusable root. */
    private fun pageAction(label: String) {
        val node = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNode()
        assertTrue("on the focusable node", SemanticsProperties.Focused in node.config)
        compose.runOnUiThread { node.config[SemanticsActions.CustomActions].first { it.label == label }.action() }
    }

    @Test
    fun talkBackTurnsPagesWithActions() {
        pageAction("Next page")
        waitForPage("Page 2 of 3")
        pageAction("Previous page")
        waitForPage("Page 1 of 3")
    }

    private fun focused(description: String) = compose.onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(description)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Focused, true),
    ).fetchSemanticsNodes().isNotEmpty()

    /** Tab until [description] has the focus. */
    private fun tabTo(description: String) {
        repeat(10) {
            if (focused(description)) return
            press(KeyEvent.KEYCODE_TAB)
        }
        assertTrue("$description focused", focused(description))
    }

    @Test
    fun pedalsStillTurnAfterAnnotateIsPressedWithTheKeyboard() {
        tabTo("Annotate")
        press(KeyEvent.KEYCODE_ENTER) // The focused button goes, and the focus with it.
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Pen, red").fetchSemanticsNodes().isNotEmpty() }
        press(KeyEvent.KEYCODE_PAGE_DOWN)
        waitForPage("Page 2 of 3")
    }

    @Test
    fun spaceTurnsThePageEvenWithAToolbarButtonFocused() {
        compose.onNodeWithContentDescription("Annotate").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Pen, red").fetchSemanticsNodes().isNotEmpty() }
        tabTo("Pen, red")
        press(KeyEvent.KEYCODE_SPACE)
        waitForPage("Page 2 of 3")
    }

    @Test
    fun backLeavesAnnotatingFirstThenTheScore() {
        compose.onNodeWithContentDescription("Annotate").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Pen, red").fetchSemanticsNodes().isNotEmpty() }

        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Pen, red").fetchSemanticsNodes().isEmpty() }
        assertTrue("still reading", inReader())

        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { !inReader() }
        assertFalse(inReader())
    }

    /**
     * A finger dragged in from the left edge, as the system's back gesture is made. Through the shell's input
     * command: events the test injects itself go to the app's window only, and the system's gesture never sees them.
     */
    private fun swipeFromTheLeftEdge() {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val y = metrics.heightPixels / 2
        instrumentation.uiAutomation.executeShellCommand("input touchscreen swipe 1 $y ${metrics.widthPixels / 2} $y 300").close()
        Thread.sleep(1500)
        compose.waitForIdle()
    }

    @Test
    fun anEdgeSwipeWhileAnnotatingDrawsInsteadOfGoingBack() {
        compose.onNodeWithContentDescription("Annotate").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Pen, red").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(500) // The score's key and stored strokes load before a finger can draw.
        // Twice: in the reader's immersive mode the first edge swipe only shows the system bars.
        swipeFromTheLeftEdge()
        swipeFromTheLeftEdge()
        assertTrue("still annotating", compose.onAllNodesWithContentDescription("Pen, red").fetchSemanticsNodes().isNotEmpty())
        assertTrue("still reading", inReader())
    }

    @Test
    fun anEdgeSwipeWhileReadingStillGoesBack() {
        // With three-button navigation an edge swipe is no Back: nothing to keep working.
        val res = instrumentation.targetContext.resources
        val mode = res.getIdentifier("config_navBarInteractionMode", "integer", "android").takeIf { it != 0 }?.let(res::getInteger)
        assumeTrue("needs gesture navigation, is in mode $mode", mode == 2)
        swipeFromTheLeftEdge() // Shows the system bars,
        swipeFromTheLeftEdge() // then goes Back.
        compose.waitUntil(5_000) { !inReader() }
    }

    @Test
    fun penAndSymbolTargetsAreAtLeast48dp() {
        compose.onNodeWithContentDescription("Annotate").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Pen, red").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Pen, red").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

        compose.onNodeWithText("Symbols").performClick()
        compose.waitForIdle()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .fetchSemanticsNodes()
            .forEach { assertTrue("${it.config} is ${it.size}", it.size.width >= 48 * it.layoutInfo.density.density - 0.5f) }
    }
}
