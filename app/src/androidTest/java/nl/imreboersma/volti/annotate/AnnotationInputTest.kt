package nl.imreboersma.volti.annotate

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.imreboersma.volti.Reader
import nl.imreboersma.volti.VoltiApp
import nl.imreboersma.volti.db.VoltiDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** A pen writing with a hand resting on the glass, as on a stand: the pen draws, the hand turns nothing. */
@RunWith(AndroidJUnit4::class)
class AnnotationInputTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var nav: NavHostController
    private lateinit var scoreKey: String
    private val db = VoltiDatabase.get(InstrumentationRegistry.getInstrumentation().targetContext)

    @Before
    fun openAScore() {
        val file = File(tmp.root, "score.pdf")
        val doc = PdfDocument()
        repeat(2) { i ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            page.canvas.drawRect(100f, 100f, 200f + 100f * i, 300f, Paint().apply { color = Color.BLACK })
            doc.finishPage(page)
        }
        file.outputStream().use(doc::writeTo)
        doc.close()
        scoreKey = "local:" + MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        compose.setContent {
            nav = rememberNavController()
            VoltiApp(nav)
        }
        compose.runOnUiThread { nav.navigate(Reader(Uri.fromFile(file).toString())) }
        compose.waitUntil(10_000) { showing("Page 1 of 2") }
        Thread.sleep(500) // The score's key and stored strokes load before the pen can write.
    }

    @After
    fun removeTheStrokes() = db.openHelper.writableDatabase.execSQL("DELETE FROM annotation_stroke WHERE scoreKey = ?", arrayOf(scoreKey))

    private fun showing(description: String) = compose.onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description),
    ).fetchSemanticsNodes().isNotEmpty()

    private fun strokes(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM annotation_stroke WHERE scoreKey = ?", arrayOf(scoreKey))
            .use { it.moveToFirst(); it.getInt(0) }

    /** A touch on screen: pointer [id] with [tool] at [x], [y]. */
    private class Touch(val id: Int, val tool: Int, var x: Float, var y: Float)

    private val downTime = SystemClock.uptimeMillis()

    private fun send(action: Int, vararg touches: Touch) {
        val properties = touches.map { MotionEvent.PointerProperties().apply { id = it.id; toolType = it.tool } }.toTypedArray()
        val coords = touches.map { MotionEvent.PointerCoords().apply { x = it.x; y = it.y; pressure = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, touches.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS, 0,
        )
        InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    private fun pointer(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /** Somewhere in the middle of the screen, away from the corner buttons. */
    private fun spot(fx: Float, fy: Float): Pair<Float, Float> {
        val view = compose.activity.window.decorView
        return view.width * fx to view.height * fy
    }

    private fun pen(): Touch = spot(0.3f, 0.4f).let { (x, y) -> Touch(0, MotionEvent.TOOL_TYPE_STYLUS, x, y) }
    private fun palm(id: Int): Touch = spot(0.75f, 0.7f).let { (x, y) -> Touch(id, MotionEvent.TOOL_TYPE_FINGER, x, y) }

    private fun finished() {
        compose.waitForIdle()
        compose.waitUntil(5_000) { strokes() > 0 }
    }

    @Test
    fun aHandThatLandsAfterThePenAndLiftsLast() {
        val pen = pen()
        send(MotionEvent.ACTION_DOWN, pen)
        pen.x += 40f
        send(MotionEvent.ACTION_MOVE, pen)
        val palm = palm(1)
        send(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), pen, palm)
        pen.x += 40f
        send(MotionEvent.ACTION_MOVE, pen, palm)
        send(pointer(MotionEvent.ACTION_POINTER_UP, 0), pen, palm) // The pen lifts first,
        palm.y += 5f
        send(MotionEvent.ACTION_MOVE, palm) // the hand moves on its own (this crashed before),
        send(MotionEvent.ACTION_UP, palm) // and lifts.
        finished()
        assertEquals(1, strokes())
        assertEquals("the hand didn't turn the page", "Page 1 of 2", shown())
    }

    private fun shown() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        .fetchSemanticsNodes().joinToString { it.config[SemanticsProperties.StateDescription] }

    private fun symbols(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM annotation_symbol WHERE scoreKey = ?", arrayOf(scoreKey))
            .use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun aTapBetweenToolbarButtonsPlacesNothing() {
        // The toolbar out, a symbol picked: then a tap that just misses a button.
        while (compose.onAllNodesWithText("Done").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithContentDescription("Annotate").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithText("Symbols").performClick()
        compose.waitForIdle()
        if (compose.onAllNodesWithContentDescription("Fermata").fetchSemanticsNodes().isEmpty()) {
            throw AssertionError(compose.onRoot().printToString())
        }
        compose.onNodeWithContentDescription("Fermata").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Eraser").performTouchInput { click(Offset(-4.dp.toPx(), centerY)) } // In the gap before it.
        compose.waitForIdle()
        Thread.sleep(300)
        assertEquals(0, symbols())
        assertEquals("Page 1 of 2", shown())
    }

    @Test
    fun thePenAlone() {
        val pen = pen()
        send(MotionEvent.ACTION_DOWN, pen)
        pen.x += 40f
        send(MotionEvent.ACTION_MOVE, pen)
        pen.x += 40f
        send(MotionEvent.ACTION_MOVE, pen)
        send(MotionEvent.ACTION_UP, pen)
        finished()
        assertEquals(1, strokes())
        assertEquals("the pen didn't turn the page", "Page 1 of 2", shown())
    }

    @Test
    fun aHandThatIsAlreadyDownWhenThePenStarts() {
        val palm = palm(0)
        send(MotionEvent.ACTION_DOWN, palm) // The hand rests first,
        val pen = pen().let { Touch(1, it.tool, it.x, it.y) }
        send(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), palm, pen) // then the pen writes.
        pen.x += 40f
        send(MotionEvent.ACTION_MOVE, palm, pen)
        pen.x += 40f
        send(MotionEvent.ACTION_MOVE, palm, pen)
        send(pointer(MotionEvent.ACTION_POINTER_UP, 1), palm, pen)
        send(MotionEvent.ACTION_UP, palm)
        finished()
        assertEquals(1, strokes())
        assertEquals("the hand didn't turn the page", true, showing("Page 1 of 2"))
    }
}
