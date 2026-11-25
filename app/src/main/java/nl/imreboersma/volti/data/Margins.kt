package nl.imreboersma.volti.data

/** A part of a page, in fractions of its width and height: 0 is the top or left edge, 1 the bottom or right. */
data class PageArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** The smallest area holding both. */
    fun union(other: PageArea) =
        PageArea(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))

    companion object {
        val WHOLE = PageArea(0f, 0f, 1f, 1f)
    }
}

/**
 * The area of a page with music on it, from a small render of the whole page ([pixels], ARGB, [width] wide).
 * A pixel is dark when it is at least [contrast] (luminance, 0-255) darker than the paper, taken as the 90th
 * percentile of the page's brightness (paper on any page that isn't 90% ink): relative, because thin staff lines
 * shrunk for detection end up light grey, and scanned paper is rarely white.
 * A row or column counts as content when at least [minDarkShare] of its pixels are dark, so dust and specks
 * don't count. [padding] is added around the content, and no more than [maxCropPerSide] is cut from any side:
 * a title page with one line doesn't zoom up to fill the stand.
 */
fun contentArea(
    pixels: IntArray,
    width: Int,
    height: Int,
    contrast: Int = 30,
    minDarkShare: Float = 0.004f,
    padding: Float = 0.02f,
    maxCropPerSide: Float = 0.15f,
): PageArea {
    val luminance = IntArray(pixels.size) { i ->
        val c = pixels[i]
        (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
    }
    val histogram = IntArray(256).also { h -> luminance.forEach { h[it]++ } }
    var seen = 0
    val paper = histogram.indices.first { seen += histogram[it]; seen * 10 >= luminance.size * 9 }
    val darkBelow = paper - contrast
    val rows = IntArray(height)
    val columns = IntArray(width)
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (luminance[y * width + x] < darkBelow) {
                rows[y]++
                columns[x]++
            }
        }
    }
    val rowMin = maxOf(1, (width * minDarkShare).toInt())
    val columnMin = maxOf(1, (height * minDarkShare).toInt())
    val top = rows.indexOfFirst { it >= rowMin }
    if (top < 0) return PageArea.WHOLE // Nothing on it: show the page as it is.
    val bottom = rows.indexOfLast { it >= rowMin } + 1
    val left = columns.indexOfFirst { it >= columnMin }.coerceAtLeast(0)
    val right = columns.indexOfLast { it >= columnMin }.let { if (it < 0) width else it + 1 }
    return PageArea(
        left = (left.toFloat() / width - padding).coerceIn(0f, maxCropPerSide),
        top = (top.toFloat() / height - padding).coerceIn(0f, maxCropPerSide),
        right = (right.toFloat() / width + padding).coerceIn(1f - maxCropPerSide, 1f),
        bottom = (bottom.toFloat() / height + padding).coerceIn(1f - maxCropPerSide, 1f),
    )
}

/**
 * Up to [count] pages spread over a score of [pageCount]: first, last and evenly in between. Their content areas
 * together give one crop for the whole score.
 */
fun samplePages(pageCount: Int, count: Int = 6): List<Int> =
    if (pageCount <= count) (0 until pageCount).toList()
    else (0 until count).map { it * (pageCount - 1) / (count - 1) }.distinct()

/**
 * An area that came over binder, from or to the renderer: a compromised side must not get a page area that is
 * inside out, empty or off the page. Anything odd becomes the whole page.
 */
fun checkArea(values: FloatArray?): PageArea {
    if (values == null || values.size != 4 || values.any { it.isNaN() }) return PageArea.WHOLE
    val (left, top, right, bottom) = values
    val fits = left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f
    return if (fits && right - left >= 0.1f && bottom - top >= 0.1f) PageArea(left, top, right, bottom) else PageArea.WHOLE
}

/** What a small render of a page shows: where the music is, and the colour of its paper (RGB, opaque). */
data class PageScan(val area: PageArea, val paper: Int)

/**
 * The colour of a page's paper, from a small render of it ([pixels], ARGB): the average of the pixels as bright
 * as the paper, its 90th percentile brightness, give or take [tolerance]. A yellowed scan gives its yellow.
 */
fun paperColor(pixels: IntArray, tolerance: Int = 6): Int {
    fun luminance(c: Int) = (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
    val histogram = IntArray(256).also { h -> pixels.forEach { h[luminance(it)]++ } }
    var seen = 0
    val paper = histogram.indices.first { seen += histogram[it]; seen * 10 >= pixels.size * 9 }
    var r = 0L; var g = 0L; var b = 0L; var n = 0L
    for (c in pixels) {
        if (kotlin.math.abs(luminance(c) - paper) <= tolerance) {
            r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff; n++
        }
    }
    return (0xff shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
}

/**
 * A page scan that came over binder from the renderer: {left, top, right, bottom, red, green, blue}. The area is
 * checked as [checkArea]; the colour clamped to 0-255, white if it isn't there.
 */
fun checkScan(values: FloatArray?): PageScan {
    val area = checkArea(values?.copyOfRange(0, minOf(4, values.size)))
    val rgb = values?.drop(4)?.takeIf { it.size == 3 && it.none(Float::isNaN) }?.map { it.toInt().coerceIn(0, 255) }
    val paper = if (rgb == null) 0xffffffff.toInt() else (0xff shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    return PageScan(area, paper)
}
