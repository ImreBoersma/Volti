package nl.imreboersma.volti.annotate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun theEraserTouchesASymbolAnywhereOnIt() {
        val center = Point(100f, 100f)
        assertTrue(touchesSymbol(center, Point(100f, 100f), 0f))
        assertTrue(touchesSymbol(center, Point(100f + SYMBOL_SIZE / 2, 100f), 0f)) // Its edge.
        assertTrue(touchesSymbol(center, Point(100f + SYMBOL_SIZE / 2 + 9f, 100f), 10f)) // Just beside, with the eraser's width.
        assertFalse(touchesSymbol(center, Point(100f + SYMBOL_SIZE, 100f), 10f))
    }

    @Test
    fun aStoredSymbolIsReadByNameAndAnUnknownOneIsLeftOut() {
        assertEquals(Symbol.Fermata, Symbol.named("Fermata"))
        assertNull(Symbol.named("Coda")) // From a later version, say.
        assertTrue(Symbol.MF.dynamic)
        assertFalse(Symbol.Sharp.dynamic)
    }

    @Test
    fun aDotIsAStrokeToo() {
        assertTrue(touches(listOf(Point(10f, 10f)), Point(12f, 10f), 5f))
        assertTrue(touches(listOf(Point(10f, 10f), Point(10f, 10f)), Point(12f, 10f), 5f))
    }
}
