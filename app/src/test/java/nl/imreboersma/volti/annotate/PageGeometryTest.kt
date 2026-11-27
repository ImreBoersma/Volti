package nl.imreboersma.volti.annotate

import nl.imreboersma.volti.data.PageArea
import org.junit.Assert.assertEquals
import org.junit.Test

class PageGeometryTest {
    private fun near(expected: Point, actual: Point) {
        assertEquals(expected.x, actual.x, 0.5f)
        assertEquals(expected.y, actual.y, 0.5f)
    }

    /** An A4 page (1:1.414) rendered 707 x 1000 into a box of the same size. */
    private val whole = PageGeometry(PageArea.WHOLE, 707, 1000, 707f, 1000f)

    @Test
    fun aWholePageMapsItsCornersToTheBox() {
        assertEquals(1414f, whole.pageHeight, 1f)
        near(Point(0f, 0f), whole.toPage(Point(0f, 0f)))
        near(Point(1000f, 1414f), whole.toPage(Point(707f, 1000f)))
    }

    @Test
    fun aCroppedPageKeepsThePointOnThePage() {
        // The middle 80% of the same page, rendered larger: what was at the crop's corner is still there.
        val cropped = PageGeometry(PageArea(0.1f, 0.1f, 0.9f, 0.9f), 707, 1000, 707f, 1000f)
        assertEquals(1414f, cropped.pageHeight, 1f)
        near(Point(100f, 141.4f), cropped.toPage(Point(0f, 0f)))
        // A point on the page lands where the uncropped view had it, scaled about the crop.
        val onPage = Point(500f, 707f)
        near(Point(353.5f, 500f), cropped.toScreen(onPage))
        near(onPage, whole.toPage(Point(353.5f, 500f)))
    }

    @Test
    fun aLetterboxedPageIsCentred() {
        // The page in a wide box: Fit leaves room left and right.
        val wide = PageGeometry(PageArea.WHOLE, 707, 1000, 1200f, 1000f)
        near(Point(0f, 0f), wide.toPage(Point((1200f - 707f) / 2, 0f)))
    }

    @Test
    fun anOldBitmapDuringARotationIsScaledDown() {
        // A bitmap rendered for a taller box, shown in a smaller one until the new render lands.
        val shrunk = PageGeometry(PageArea.WHOLE, 707, 1000, 707f, 500f)
        near(Point(1000f, 1414f), shrunk.toPage(Point(707f / 2 + 353.5f / 2, 500f)))
    }

    @Test
    fun screenAndPageRoundTrip() {
        val g = PageGeometry(PageArea(0.05f, 0.12f, 0.97f, 0.9f), 1200, 1500, 1600f, 1500f)
        val p = Point(321f, 654f)
        near(p, g.toPage(g.toScreen(p)))
        val v = g.pageToScreenValues()
        near(g.toScreen(p), Point(v[0] * p.x + v[2], v[4] * p.y + v[5]))
    }

    @Test
    fun zoomedInThePagePointUnderThePenIsTheSame() {
        // Two times, the page moved so the box's middle (353.5, 500) shows the page's (353.5, 500) still.
        val zoomed = whole.copy(zoom = 2f, panX = -353.5f, panY = -500f)
        val pen = Point(353.5f, 500f)
        near(whole.toPage(pen), zoomed.toPage(pen))
        near(pen, zoomed.toScreen(zoomed.toPage(pen))) // And back.
        // A quarter of the box left of the middle is only an eighth of the page left of it: the page is larger.
        near(Point(500f - 125f, whole.toPage(pen).y), zoomed.toPage(Point(176.75f, 500f)))
        assertEquals(whole.scale * 2, zoomed.scale, 0.0001f)
    }
}
