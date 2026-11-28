package nl.imreboersma.volti.annotate

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * My marks are stored as `androidx.ink`'s encoded inputs. Every version of ink Volti has shipped with left a fixture in
 * `assets/ink/`: the same stroke, encoded by that version. Each must still decode to that stroke, or an ink update
 * would lose every mark on the tablet. Before merging an ink bump (Renovate's too), run this; after it, add the new
 * version's fixture with [writeTheFixtureForThisVersion] and keep the old ones.
 */
@RunWith(AndroidJUnit4::class)
class InkFixtureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** A pen stroke as the reader stores one: page units, time, and the pen's pressure, tilt and orientation. */
    private val inputs = List(12) { i ->
        StrokeInput.create(
            x = 100f + i * 7.5f,
            y = 200f + (i % 4) * 3.25f,
            elapsedTimeMillis = i * 8L,
            toolType = InputToolType.STYLUS,
            pressure = 0.2f + i * 0.05f,
            tiltRadians = 0.5f,
            orientationRadians = 1.25f,
        )
    }

    @Test
    fun everyStoredVersionStillDecodes() {
        val assets = instrumentation.context.assets
        val fixtures = assets.list("ink").orEmpty().filter { it.endsWith(".bin") }
        assertTrue("no fixtures in assets/ink", fixtures.isNotEmpty())
        for (name in fixtures) {
            val decoded = assets.open("ink/$name").use { StrokeInputBatch.decode(it) }
            assertEquals(name, inputs.size, decoded.size)
            val input = StrokeInput()
            inputs.forEachIndexed { i, expected ->
                decoded.populate(i, input)
                assertEquals("$name x $i", expected.x, input.x, 0.01f)
                assertEquals("$name y $i", expected.y, input.y, 0.01f)
                // Ink keeps time compressed: a millisecond either way is its own precision, not a broken decode.
                assertEquals("$name time $i", expected.elapsedTimeMillis.toDouble(), input.elapsedTimeMillis.toDouble(), 1.0)
                assertEquals("$name pressure $i", expected.pressure, input.pressure, 0.01f)
                assertEquals("$name tilt $i", expected.tiltRadians, input.tiltRadians, 0.01f)
                assertEquals("$name orientation $i", expected.orientationRadians, input.orientationRadians, 0.01f)
            }
            // And drawn as the reader draws it.
            val stroke = Stroke(Brush.createWithColorIntArgb(StockBrushes.pressurePen(), PenColor.Black.argb, 3f, 0.1f), decoded)
            assertTrue("$name has a shape", stroke.shape.getRenderGroupCount() > 0)
        }
    }

    /**
     * Writes this ink version's fixture, only when asked:
     * `-Pandroid.testInstrumentationRunnerArguments.writeInkFixture=1`. Then
     * `adb shell run-as nl.imreboersma.volti.debug cat files/ink-fixture.bin > app/src/androidTest/assets/ink/stroke-<version>.bin`.
     */
    @Test
    fun writeTheFixtureForThisVersion() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("writeInkFixture") == "1")
        val batch = MutableStrokeInputBatch().apply { inputs.forEach { add(it) } }
        val bytes = ByteArrayOutputStream().also { batch.encode(it) }.toByteArray()
        File(instrumentation.targetContext.filesDir, "ink-fixture.bin").writeBytes(bytes)
    }
}
