package nl.imreboersma.volti.annotate

import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.withMatrix
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.imreboersma.volti.ui.CornerButton
import nl.imreboersma.volti.ui.PageOverlay
import nl.imreboersma.volti.ui.Region
import nl.imreboersma.volti.ui.theme.LocalReaderColors

private fun PageGeometry.pageToScreen() = Matrix().apply { setValues(pageToScreenValues() + floatArrayOf(0f, 0f, 1f)) }

/**
 * A page's annotations, over the page as the reader shows it. A whole page also takes input: a pen draws (its eraser
 * end, or its button held, erases), and while annotating a finger does too; any other touch goes on to the reader,
 * which turns the page. In a half turn, annotations show but don't take input.
 */
@Composable
fun PageAnnotations(page: PageOverlay, viewModel: AnnotationViewModel) {
    val strokes by viewModel.strokes.collectAsStateWithLifecycle()
    var box by remember { mutableStateOf(IntSize.Zero) }
    val geometry = remember(page, box) {
        if (box == IntSize.Zero) null
        else PageGeometry(page.area, page.bitmapWidth, page.bitmapHeight, box.width.toFloat(), box.height.toFloat())
    }
    val renderer = remember { CanvasStrokeRenderer.create() }
    Box(modifier = Modifier.fillMaxSize().onSizeChanged { box = it }) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val toScreen = geometry?.pageToScreen() ?: return@Canvas
            drawIntoCanvas { canvas ->
                // The renderer draws in the canvas's own coordinates; the transform only tells it the resolution.
                canvas.nativeCanvas.withMatrix(toScreen) {
                    strokes[page.index]?.forEach { renderer.draw(this, it.stroke, toScreen) }
                }
            }
        }
        // Input only once the score's identity and its stored strokes are here: a stroke before then would be lost.
        val ready by viewModel.ready.collectAsStateWithLifecycle()
        if (page.region == Region.Whole && geometry != null && ready) InkInput(page.index, geometry, viewModel)
    }
}

/**
 * Takes the pen (and, while annotating, a finger) and draws as it moves, low-latency; finished strokes go to
 * [viewModel]. Only the pointer that draws is taken: a hand resting on the glass, or any finger while not annotating,
 * is left alone, and the reader turns the page on a tap. While the pen draws, a touch beside it is no tap.
 */
@Composable
private fun InkInput(pageIndex: Int, geometry: PageGeometry, viewModel: AnnotationViewModel) {
    val annotating by viewModel.annotating.collectAsStateWithLifecycle()
    val current by rememberUpdatedState(Triple(pageIndex, geometry, annotating))
    var view by remember { mutableStateOf<InProgressStrokesView?>(null) }
    // The page each stroke was started on: ink reports a stroke finished a frame or more later, maybe after a turn.
    val startedOn = remember { mutableMapOf<InProgressStrokeId, Int>() }

    AndroidView(
        factory = { context ->
            InProgressStrokesView(context).apply {
                addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
                    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                        // Into the drawn strokes in the same frame it leaves this view: no flicker.
                        strokes.forEach { (id, stroke) -> startedOn.remove(id)?.let { viewModel.finished(it, stroke) } }
                        removeFinishedStrokes(strokes.keys)
                    }
                })
                // Its drawing surface set up now, not at the first stroke: a surface takes a moment to be created, and
                // a stroke started before then is never drawn, so it never finishes either.
                eagerInit()
            }.also { view = it }
        },
        modifier = Modifier.fillMaxSize(),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(viewModel) {
                val batch = MutableStrokeInputBatch()
                awaitPointerEventScope {
                    var active: PointerId? = null
                    var stroke: InProgressStrokeId? = null
                    var erasing = false
                    var started = 0L
                    // Once the pen is down, the whole gesture is the pen's: nothing in it may turn the page, also a hand
                    // that is still on the glass after the pen lifts. Until every pointer is up.
                    var claimed = false
                    // Pointers seen going down since: when the pen lifts, the system may hand a hand still on the glass
                    // back as a new pointer that never went down. That is the pen's gesture still, not a tap.
                    val fresh = mutableSetOf<PointerId>()
                    while (true) {
                        val event = awaitPointerEvent()
                        if (claimed) {
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) {
                                claimed = false
                                fresh.clear()
                            }
                        } else {
                            event.changes.forEach { change ->
                                when {
                                    // A real touch-down comes as a Press; the handed-back pointer starts in a Move.
                                    change.changedToDownIgnoreConsumed() && event.type == PointerEventType.Press -> fresh += change.id
                                    change.id !in fresh -> change.consume()
                                }
                                if (!change.pressed) fresh -= change.id
                            }
                        }
                        val (index, g, fingerDraws) = current
                        val ink = view ?: continue
                        fun input(position: Offset, time: Long, change: PointerInputChange) = StrokeInput().apply {
                            val page = g.toPage(Point(position.x, position.y))
                            update(
                                page.x,
                                page.y,
                                (time - started).coerceAtLeast(0),
                                if (change.type == PointerType.Touch) InputToolType.TOUCH else InputToolType.STYLUS,
                                pressure = change.pressure.coerceIn(0f, 1f),
                            )
                        }
                        fun pagePoint(position: Offset) = g.toPage(Point(position.x, position.y))

                        val id = active
                        if (id == null) {
                            val down = event.changes.firstOrNull { change ->
                                change.changedToDown() &&
                                    (change.type == PointerType.Stylus || change.type == PointerType.Eraser || fingerDraws)
                            } ?: continue // Not ours: the reader turns the page.
                            active = down.id
                            claimed = true
                            event.changes.forEach { it.consume() } // A hand already down is no tap any more.
                            started = down.uptimeMillis
                            // The pen's eraser end, its button held, or the eraser picked in the toolbar.
                            erasing = down.type == PointerType.Eraser || event.buttons.isSecondaryPressed ||
                                viewModel.tool.value == Tool.Eraser
                            if (erasing) {
                                viewModel.eraseStart()
                                viewModel.eraseAt(index, pagePoint(down.position))
                            } else {
                                stroke = ink.startStroke(input(down.position, down.uptimeMillis, down), viewModel.brush(), g.pageToScreen())
                                    .also { startedOn[it] = index }
                            }
                            down.consume()
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == id }
                        if (change == null || !change.pressed) {
                            // Lifted (or gone): the stroke ends here, whatever other pointers still touch.
                            if (erasing) {
                                viewModel.eraseEnd()
                            } else {
                                stroke?.let { s ->
                                    if (change != null) ink.finishStroke(input(change.position, change.uptimeMillis, change), s)
                                    else ink.cancelStroke(s)
                                }
                            }
                            change?.consume()
                            active = null
                            stroke = null
                            erasing = false
                            continue
                        }
                        if (erasing) {
                            (change.historical.map { it.position } + change.position).forEach { viewModel.eraseAt(index, pagePoint(it)) }
                        } else {
                            stroke?.let { s ->
                                batch.clear()
                                change.historical.forEach { batch.add(input(it.position, it.uptimeMillis, change)) }
                                batch.add(input(change.position, change.uptimeMillis, change))
                                ink.addToStroke(batch, s)
                            }
                        }
                        change.consume()
                    }
                }
            },
    )
}

/**
 * The reader's annotation controls: a corner button to start, then a toolbar with the pen's colours, the eraser,
 * undo and done. While the toolbar is out, a finger draws.
 */
@Composable
fun BoxScope.AnnotationControls(viewModel: AnnotationViewModel) {
    val annotating by viewModel.annotating.collectAsStateWithLifecycle()
    if (!annotating) {
        CornerButton("Annotate", "Show the annotate button", viewModel::start, Modifier.align(Alignment.TopEnd)) { ink ->
            // A pencil, at an angle.
            val s = 2.5.dp.toPx()
            val c = size.width / 2
            val d = 9.dp.toPx()
            drawLine(ink, Offset(c - d, c + d), Offset(c + d, c - d), s * 2, StrokeCap.Round)
            drawLine(ink, Offset(c - d - 3.dp.toPx(), c + d + 3.dp.toPx()), Offset(c - d, c + d), s, StrokeCap.Round)
        }
        return
    }
    val colors = LocalReaderColors.current
    val tool by viewModel.tool.collectAsStateWithLifecycle()
    val color by viewModel.color.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surround)
            .border(1.5.dp, colors.message, RoundedCornerShape(24.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        PEN_COLORS.forEach { pen ->
            val picked = tool == Tool.Pen && color == pen
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(if (picked) 3.dp else 1.dp, colors.message, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Color(pen))
                    .clickable(role = Role.RadioButton) { viewModel.pick(pen) }
                    .semantics {
                        contentDescription = "Pen, ${colorName(pen)}"
                        selected = picked
                    },
            )
        }
        FilterChip(
            selected = tool == Tool.Eraser,
            onClick = { viewModel.pick(Tool.Eraser) },
            label = { Text("Eraser") },
        )
        TextButton(onClick = viewModel::undo, enabled = canUndo) { Text("Undo", color = colors.message) }
        TextButton(onClick = viewModel::done) { Text("Done", color = colors.message) }
    }
}

private fun colorName(color: Int) = when (color) {
    PEN_COLORS[1] -> "red"
    PEN_COLORS[2] -> "blue"
    else -> "black"
}
