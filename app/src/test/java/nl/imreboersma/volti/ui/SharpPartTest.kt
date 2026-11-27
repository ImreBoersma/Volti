package nl.imreboersma.volti.ui

import nl.imreboersma.volti.data.PageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharpPartTest {
    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 0.001f)

    @Test
    fun theWholePageNeedsNothingSharp() {
        assertNull(sharpPart(Enlarged.NONE, PageArea.WHOLE, 1000, 1600, 1000f, 1600f))
    }

    @Test
    fun twiceEnlargedAtTheTopLeftIsThePagesTopLeftQuarterOnTheWholeScreen() {
        val part = sharpPart(Enlarged(2f, 0f, 0f), PageArea.WHOLE, 1000, 1600, 1000f, 1600f)!!
        near(0f, part.area.left)
        near(0.5f, part.area.right)
        near(0.5f, part.area.bottom)
        assertEquals(1000 to 1600, part.width to part.height) // Rendered at the screen's size, so as sharp as it.
        near(0f, part.left)
    }

    @Test
    fun aCroppedPageGivesItsPartOfTheWholePage() {
        // The crop shows 0.1..0.9 of the page across; twice enlarged, moved to its right half.
        val part = sharpPart(Enlarged(2f, -1000f, 0f), PageArea(0.1f, 0f, 0.9f, 1f), 800, 1600, 1000f, 1600f)!!
        // The bitmap is 800 wide in a 1000 box: it starts at 100. The box's right half (500..1000) shows 500..900 of it.
        near(0.1f + 0.8f * (400f / 800f), part.area.left)
        near(0.9f, part.area.right)
        near(0f, part.left) // On screen from the left edge,
        assertEquals(800, part.width) // up to where the page ends: beside it is no music.
    }
}
