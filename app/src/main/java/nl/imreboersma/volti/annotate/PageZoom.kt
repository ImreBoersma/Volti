package nl.imreboersma.volti.annotate

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged

/** The most the page is enlarged while annotating: a note head about the size of the pen's tip. */
const val MAX_ZOOM = 4f

/**
 * How much the page is enlarged, and where: a point at x on the page shows at x * [scale] + [offset]. At 1, the whole
 * page, as the reader shows it. Kept so the page always covers the screen: no empty space beside it when zoomed in.
 */
@Stable
class PageZoom {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    val zoomed get() = scale > 1f

    /**
     * Only a whole page zooms: in a half turn, two pages share the screen and neither takes marks. Set by the page
     * shown; off, it shows unzoomed.
     */
    var allowed by mutableStateOf(true)
        private set

    fun allow(whole: Boolean) {
        allowed = whole
        if (!whole) reset()
    }

    /**
     * Two fingers moved: the page is enlarged by [zoom] around [centroid] (on screen) and moved by [pan], in a box of
     * [width] x [height]. The point under the fingers stays under them.
     */
    fun transform(centroid: Offset, pan: Offset, zoom: Float, width: Float, height: Float) {
        if (!allowed) return
        val next = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        val moved = centroid - (centroid - offset) * (next / scale) + pan
        scale = next
        offset = Offset(moved.x.coerceIn(width - width * next, 0f), moved.y.coerceIn(height - height * next, 0f))
    }

    /** The box the page shows in, as last laid out: what [zoomIn] zooms around the middle of. */
    private var area = Size.Zero

    fun measured(width: Float, height: Float) {
        area = Size(width, height)
    }

    /**
     * Zoom in without fingers (TalkBack, a keyboard, a switch): twice, then [MAX_ZOOM] times, around the middle of what
     * shows. Moving a zoomed page stays a gesture.
     */
    fun zoomIn() {
        val next = if (scale < 2f) 2f else MAX_ZOOM
        transform(Offset(area.width / 2, area.height / 2), Offset.Zero, next / scale, area.width, area.height)
    }

    /** Back to the whole page. */
    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

/**
 * While [enabled] (annotating), two fingers enlarge and move the page: taken before the page and its marks see them,
 * so they neither draw nor turn a page. Not with a pen on the glass: a resting hand mustn't move the music under it.
 */
fun Modifier.zoomToAnnotate(zoom: PageZoom, enabled: Boolean) = onSizeChanged {
    zoom.measured(it.width.toFloat(), it.height.toFloat())
}.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        var penDown = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            penDown = penDown || event.changes.any { it.type == PointerType.Stylus || it.type == PointerType.Eraser }
            val fingers = event.changes.filter { it.type == PointerType.Touch && it.pressed && it.previousPressed }
            if (!penDown && fingers.size >= 2) {
                val (centroid, before) = fingers.centroids()
                val spread = fingers.spread(centroid) { it.position }
                val spreadBefore = fingers.spread(before) { it.previousPosition }
                val change = if (spreadBefore > 0f) spread / spreadBefore else 1f
                zoom.transform(centroid, centroid - before, change, size.width.toFloat(), size.height.toFloat())
                event.changes.forEach { if (it.type == PointerType.Touch) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** Where the fingers' middle is now, and was before this event. */
private fun List<PointerInputChange>.centroids() =
    Offset(sumOf { it.position.x.toDouble() }.toFloat() / size, sumOf { it.position.y.toDouble() }.toFloat() / size) to
        Offset(sumOf { it.previousPosition.x.toDouble() }.toFloat() / size, sumOf { it.previousPosition.y.toDouble() }.toFloat() / size)

/** How far apart the fingers are: their mean distance from [middle]. */
private fun List<PointerInputChange>.spread(middle: Offset, at: (PointerInputChange) -> Offset) =
    sumOf { (at(it) - middle).getDistance().toDouble() }.toFloat() / size
