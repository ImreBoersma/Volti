package nl.imreboersma.volti.annotate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EraserTest {
    private val line = listOf(Point(0f, 0f), Point(100f, 0f), Point(100f, 100f))

    @Test
    fun theEraserTouchesAStrokeAnywhereAlongIt() {
        assertTrue(touches(line, Point(50f, 5f), 10f)) // Beside the first segment.
        assertTrue(touches(line, Point(105f, 60f), 10f)) // Beside the second.
        assertFalse(touches(line, Point(50f, 50f), 10f)) // Inside the corner, away from both.
    }

    @Test
    fun beyondTheEndsIsMeasuredToTheEnd() {
        assertTrue(touches(line, Point(-8f, 0f), 10f))
        assertFalse(touches(line, Point(-15f, 0f), 10f))
    }

    @Test
    fun aDotIsAStrokeToo() {
        assertTrue(touches(listOf(Point(10f, 10f)), Point(12f, 10f), 5f))
        assertTrue(touches(listOf(Point(10f, 10f), Point(10f, 10f)), Point(12f, 10f), 5f))
    }
}
