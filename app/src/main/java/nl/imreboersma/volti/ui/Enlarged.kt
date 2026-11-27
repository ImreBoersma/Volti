package nl.imreboersma.volti.ui

import androidx.compose.runtime.Immutable
import nl.imreboersma.volti.data.PageArea
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The page shown enlarged: a point at x in the page's box shows at x * [scale] + [x], and likewise for y. Given to the
 * reader by whatever enlarges it (zooming in to annotate); the reader draws it, and renders what is on screen sharp.
 */
@Immutable
data class Enlarged(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    val isEnlarged get() = scale > 1f

    companion object {
        val NONE = Enlarged()
    }
}

/** Where a bitmap sits when fitted and centred in a box: its top-left corner and its size there. */
@Immutable
data class Fitted(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * A [bitmapWidth] x [bitmapHeight] bitmap fitted (ContentScale.Fit) and centred in a box of [boxWidth] x
 * [boxHeight], as the reader draws a page. The one place for this: the marks (PageGeometry) and the sharp part use
 * it, so they can't drift apart.
 */
fun fitted(bitmapWidth: Int, bitmapHeight: Int, boxWidth: Float, boxHeight: Float): Fitted {
    val fit = min(boxWidth / bitmapWidth, boxHeight / bitmapHeight)
    val width = bitmapWidth * fit
    val height = bitmapHeight * fit
    return Fitted((boxWidth - width) / 2, (boxHeight - height) / 2, width, height)
}

/** The part [area] of a page (fractions of the whole page) that shows on screen at [left], [top], [width] x [height]. */
@Immutable
data class SharpPart(val area: PageArea, val left: Float, val top: Float, val width: Int, val height: Int)

/**
 * What of the page is on screen when [enlarged]: the bitmap of [area] ([bitmapWidth] x [bitmapHeight]) sits fitted
 * and centred in a box of [boxWidth] x [boxHeight]. Null at the whole page, or with nothing of the page on screen.
 */
fun sharpPart(enlarged: Enlarged, area: PageArea, bitmapWidth: Int, bitmapHeight: Int, boxWidth: Float, boxHeight: Float): SharpPart? {
    if (!enlarged.isEnlarged) return null
    val (bitmapLeft, bitmapTop, shownWidth, shownHeight) = fitted(bitmapWidth, bitmapHeight, boxWidth, boxHeight)
    val s = enlarged.scale
    // The box's part on screen, in the box's own coordinates, cut to where the page is (not the space beside it).
    val left = max(-enlarged.x / s, bitmapLeft)
    val right = min((boxWidth - enlarged.x) / s, bitmapLeft + shownWidth)
    val top = max(-enlarged.y / s, bitmapTop)
    val bottom = min((boxHeight - enlarged.y) / s, bitmapTop + shownHeight)
    if (right <= left || bottom <= top) return null
    val across = area.right - area.left
    val down = area.bottom - area.top
    val part = PageArea(
        area.left + (left - bitmapLeft) / shownWidth * across,
        area.top + (top - bitmapTop) / shownHeight * down,
        area.left + (right - bitmapLeft) / shownWidth * across,
        area.top + (bottom - bitmapTop) / shownHeight * down,
    )
    return SharpPart(part, left * s + enlarged.x, top * s + enlarged.y, ((right - left) * s).roundToInt(), ((bottom - top) * s).roundToInt())
}
