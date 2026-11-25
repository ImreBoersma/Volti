package nl.imreboersma.volti.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FitInsideTest {
    private val portraitTablet = PixelSize(1600, 2440)

    @Test
    fun a4FitsTheWidthOfAPortraitTablet() {
        // A4 is 595 x 842 points; width is the limit.
        assertEquals(PixelSize(1600, 2264), fitInside(595, 842, portraitTablet))
    }

    @Test
    fun landscapePageFitsTheWidth() {
        assertEquals(PixelSize(1600, 1131), fitInside(842, 595, portraitTablet))
    }

    @Test
    fun tallNarrowPageFitsTheHeight() {
        assertEquals(PixelSize(244, 2440), fitInside(100, 1000, portraitTablet))
    }

    @Test
    fun smallPageScalesUp() {
        assertEquals(PixelSize(1600, 1600), fitInside(10, 10, portraitTablet))
    }

    @Test
    fun absurdPageStaysInsideTheView() {
        assertEquals(PixelSize(1600, 1), fitInside(1_000_000, 1, portraitTablet))
    }

    @Test
    fun emptyPageOrEmptyViewIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { fitInside(0, 0, portraitTablet) }
        assertThrows(IllegalArgumentException::class.java) { fitInside(595, 842, PixelSize(1600, 0)) }
    }
}
