package nl.imreboersma.volti.annotate

import nl.imreboersma.volti.data.PageArea

/** Annotations live in page units: the page is [PAGE_WIDTH] wide, and as high as its shape makes it. */
const val PAGE_WIDTH = 1000f

/** A point, in screen pixels or page units. */
data class Point(val x: Float, val y: Float)

/**
 * Where a page sits on screen: [area] of the page (its margin crop) is shown as a bitmap of [bitmapWidth] x
 * [bitmapHeight] pixels, fitted and centred in a box of [boxWidth] x [boxHeight]. The one place that turns screen
 * pixels into page units and back, so a crop change or a half turn never moves an annotation.
 */
data class PageGeometry(
    val area: PageArea,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val boxWidth: Float,
    val boxHeight: Float,
) {
    /** On screen, the bitmap is drawn this much larger than its pixels (Fit), starting at [left], [top]. */
    private val fit = minOf(boxWidth / bitmapWidth, boxHeight / bitmapHeight)
    private val left = (boxWidth - bitmapWidth * fit) / 2
    private val top = (boxHeight - bitmapHeight * fit) / 2

    /** Screen pixels per page unit: the bitmap shows the area's width of the page across its own width. */
    val scale: Float = bitmapWidth * fit / ((area.right - area.left) * PAGE_WIDTH)

    /** The page's top-left corner on screen; it may be off screen when the margins are cropped. */
    private val originX = left - area.left * PAGE_WIDTH * scale
    private val originY = top - area.top * pageHeight * scale

    /** The page's height in page units, from the shape of the bitmap and of the area it shows. */
    val pageHeight: Float
        get() = PAGE_WIDTH * (bitmapHeight / (area.bottom - area.top)) / (bitmapWidth / (area.right - area.left))

    fun toPage(screen: Point) = Point((screen.x - originX) / scale, (screen.y - originY) / scale)

    fun toScreen(page: Point) = Point(originX + page.x * scale, originY + page.y * scale)

    /** The same, as the six numbers of an affine matrix (scale x, skew x, translate x, skew y, scale y, translate y). */
    fun pageToScreenValues() = floatArrayOf(scale, 0f, originX, 0f, scale, originY)
}
