package nl.imreboersma.volti.annotate

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class PageZoomTest {
    private fun shown(zoom: PageZoom, page: Offset) = page * zoom.scale + zoom.offset

    @Test
    fun thePointUnderTheFingersStaysUnderThem() {
        val zoom = PageZoom()
        val fingers = Offset(300f, 500f)
        zoom.transform(fingers, Offset.Zero, 2f, 1000f, 1600f)
        assertEquals(2f, zoom.scale)
        assertEquals(fingers, shown(zoom, fingers)) // The page point at the fingers is still there.
    }

    @Test
    fun itGoesNoFurtherThanFourTimesNorSmallerThanTheWholePage() {
        val zoom = PageZoom()
        repeat(5) { zoom.transform(Offset(500f, 800f), Offset.Zero, 2f, 1000f, 1600f) }
        assertEquals(MAX_ZOOM, zoom.scale)
        repeat(5) { zoom.transform(Offset(500f, 800f), Offset.Zero, 0.5f, 1000f, 1600f) }
        assertEquals(1f, zoom.scale)
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test
    fun thePageAlwaysCoversTheScreen() {
        val zoom = PageZoom()
        zoom.transform(Offset(0f, 0f), Offset.Zero, 2f, 1000f, 1600f)
        zoom.transform(Offset(0f, 0f), Offset(5000f, 5000f), 1f, 1000f, 1600f) // Dragged far to the bottom right:
        assertEquals(Offset.Zero, zoom.offset) // its top-left corner stops at the screen's.
        zoom.transform(Offset(0f, 0f), Offset(-5000f, -5000f), 1f, 1000f, 1600f) // And the other way:
        assertEquals(Offset(-1000f, -1600f), zoom.offset) // its bottom-right corner stops at the screen's.
    }
}
