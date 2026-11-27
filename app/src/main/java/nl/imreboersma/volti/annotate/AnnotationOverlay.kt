package nl.imreboersma.volti.annotate

import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.imreboersma.volti.R
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
import nl.imreboersma.volti.ui.device

private fun PageGeometry.pageToScreen() = Matrix().apply { setValues(pageToScreenValues() + floatArrayOf(0f, 0f, 1f)) }

/**
 * A page's annotations, over the page as the reader shows it. A whole page also takes input: a pen draws (its eraser
 * end, or its button held, erases), and while annotating a finger does too; any other touch goes on to the reader,
 * which turns the page. In a half turn, annotations show but don't take input.
 */
@Composable
fun PageAnnotations(page: PageOverlay, viewModel: AnnotationViewModel, zoom: PageZoom) {
    val strokes by viewModel.strokes.collectAsStateWithLifecycle()
    val symbols by viewModel.symbols.collectAsStateWithLifecycle()
    val annotating by viewModel.annotating.collectAsStateWithLifecycle()
    // The whole page again when I stop annotating, and on a turn (also a half one): a new page starts whole.
    LaunchedEffect(annotating) { if (!annotating) zoom.reset() }
    LaunchedEffect(page.index, page.region) {
        zoom.reset()
        zoom.allow(whole = page.region == Region.Whole)
    }
    var box by remember { mutableStateOf(IntSize.Zero) }
    // Enlarged as the reader shows the page image (MainActivity gives it the same zoom): marks drawn and input read
    // through the same transform.
    val geometry = remember(page, box, zoom.scale, zoom.offset) {
        if (box == IntSize.Zero) null
        else PageGeometry(
            page.area, page.bitmapWidth, page.bitmapHeight, box.width.toFloat(), box.height.toFloat(),
            zoom.scale, zoom.offset.x, zoom.offset.y,
        )
    }
    val renderer = remember { CanvasStrokeRenderer.create() }
    val zoomedIn = stringResource(R.string.zoomed_in)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .semantics { if (zoom.zoomed) stateDescription = zoomedIn },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val toScreen = geometry?.pageToScreen() ?: return@Canvas
            drawIntoCanvas { canvas ->
                // The renderer draws in the canvas's own coordinates; the transform only tells it the resolution.
                canvas.nativeCanvas.withMatrix(toScreen) {
                    strokes[page.index]?.forEach { renderer.draw(this, it.stroke, toScreen) }
                }
            }
            symbols[page.index]?.forEach { mark ->
                val symbol = Symbol.named(mark.symbol) ?: return@forEach
                val at = geometry.toScreen(Point(mark.x, mark.y))
                drawSymbol(symbol, Offset(at.x, at.y), SYMBOL_SIZE * geometry.scale, Color(mark.color))
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
            // While annotating, a stroke from the very edge of the screen draws instead of going Back. Done leaves.
            .then(if (annotating) Modifier.systemGestureExclusion() else Modifier)
            .pointerInput(viewModel) {
                val batch = MutableStrokeInputBatch()
                awaitPointerEventScope {
                    var active: PointerId? = null
                    var stroke: InProgressStrokeId? = null
                    var erasing = false
                    var started = 0L
                    // A symbol goes where the touch lifts, not where it lands: a second finger after it is a zoom.
                    var stamping: Pair<Point, Symbol>? = null
                    var byFinger = false
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
                            byFinger = down.type == PointerType.Touch
                            claimed = true
                            event.changes.forEach { it.consume() } // A hand already down is no tap any more.
                            started = down.uptimeMillis
                            // The pen's eraser end, its button held, or the eraser picked in the toolbar.
                            erasing = down.type == PointerType.Eraser || event.buttons.isSecondaryPressed ||
                                viewModel.tool.value == Tool.Eraser
                            val stamp = viewModel.tool.value as? Tool.Stamp
                            if (erasing) {
                                viewModel.eraseStart()
                                viewModel.eraseAt(index, pagePoint(down.position))
                            } else if (stamp != null) {
                                stamping = pagePoint(down.position) to stamp.symbol // Placed as it lifts; moving does nothing.
                            } else {
                                stroke = ink.startStroke(input(down.position, down.uptimeMillis, down), viewModel.brush(), g.pageToScreen())
                                    .also { startedOn[it] = index }
                            }
                            down.consume()
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == id }
                        if (byFinger && event.changes.count { it.type == PointerType.Touch && it.pressed } >= 2) {
                            // A second finger: the reader zooms, and this finger's stroke or symbol was no mark.
                            if (erasing) viewModel.eraseEnd() else stroke?.let(ink::cancelStroke)
                            active = null
                            stroke = null
                            stamping = null
                            erasing = false
                            continue
                        }
                        if (change == null || !change.pressed) {
                            // Lifted (or gone): the stroke ends here, whatever other pointers still touch.
                            if (change != null) stamping?.let { (at, symbol) -> viewModel.place(index, at, symbol) }
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
                            stamping = null
                            erasing = false
                            continue
                        }
                        if (erasing) {
                            (change.historical.map { it.position } + change.position).forEach { viewModel.eraseAt(index, pagePoint(it)) }
                        } else {
                            stroke?.let { s ->
                                batch.clear()
                                // An event can repeat its last historical point as the current one; ink refuses the
                                // same point twice, and the pen would crash the reader mid-stroke.
                                (change.historical.map { it.position to it.uptimeMillis } + (change.position to change.uptimeMillis))
                                    .distinct()
                                    .forEach { (position, time) -> batch.add(input(position, time, change)) }
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
fun BoxScope.AnnotationControls(viewModel: AnnotationViewModel, onOpenPrevious: (uri: String) -> Unit) {
    val annotating by viewModel.annotating.collectAsStateWithLifecycle()
    val change by viewModel.change.collectAsStateWithLifecycle()
    val notSaved by MarkWrites.problem.collectAsStateWithLifecycle()
    if (!annotating) change?.let { ChangeNotice(it, onOpenPrevious, viewModel::marksChecked, viewModel::later) }
    // On the previous version, nothing to annotate: it is there to look at.
    if (!annotating && change != ScoreChange.ShowingPrevious) {
        CornerButton(stringResource(R.string.annotate), stringResource(R.string.show_annotate_button), viewModel::start, Modifier.align(Alignment.TopEnd)) { ink ->
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
    // At the bottom: the top has the back and annotate buttons, and on phones the camera. Moved to the top (below
    // those) to reach the last staff.
    var atTop by rememberSaveable { mutableStateOf(false) }
    // A row of choices is out only while choosing, and one at a time: it would cover more music than it needs to.
    var open by rememberSaveable { mutableStateOf(Choosing.Nothing) }
    val choosing = open == Choosing.Symbol
    val choosingColor = open == Choosing.Colour
    // Back (and Esc) does what Done does: it leaves annotating, not the score. Only while the reader is the resumed
    // screen: a second Back during the crossfade out of it must still reach the screen before.
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    BackHandler(enabled = resumed) {
        viewModel.done()
        open = Choosing.Nothing
    }
    val shape = RoundedCornerShape(24.dp)
    // A touch in a gap between a panel's buttons must not reach the page: having touch handling at all keeps it from
    // the page underneath, and taking the release that no button took keeps it from being a page turn. Down and move
    // are the buttons' own: taking those would cancel their clicks.
    fun Modifier.panel() = clip(shape)
        .background(colors.surround)
        .border(1.5.dp, colors.message, shape)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent().changes.forEach { if (it.previousPressed && !it.pressed && !it.isConsumed) it.consume() }
                }
            }
        }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter)
            .windowInsetsPadding(if (atTop) WindowInsets.statusBars else WindowInsets.navigationBars)
            .padding(horizontal = 12.dp)
            .padding(top = if (atTop) 72.dp else 0.dp, bottom = if (atTop) 0.dp else 12.dp),
    ) {
        // Marks that aren't kept, said where they are made: it moves with the toolbar, up or down.
        notSaved?.let { MarksNotSaved(it, Modifier.panel().padding(horizontal = 16.dp, vertical = 8.dp)) }
        val stamp = tool as? Tool.Stamp
        if (stamp != null && choosing) {
            // The symbols on paper, so a black one shows on a dark surround too. One row, scrolling: low.
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.panel().horizontalScroll(rememberScrollState()).padding(8.dp),
            ) {
                Symbol.entries.forEach { symbol ->
                    val picked = stamp.symbol == symbol
                    val label = stringResource(symbol.label)
                    Canvas(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(if (picked) 3.dp else 1.dp, colors.message, CircleShape)
                            .clickable(role = Role.RadioButton) {
                                viewModel.pick(Tool.Stamp(symbol))
                                open = Choosing.Nothing
                            }
                            .semantics {
                                contentDescription = label
                                selected = picked
                            },
                    ) { drawSymbol(symbol, center, size.minDimension * 0.55f, Color(color.argb)) }
                }
            }
        }
        if (choosingColor) {
            // A colour goes for the symbols too; picked while stamping, it stays with the symbol.
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.panel().selectableGroup().horizontalScroll(rememberScrollState()).padding(8.dp),
            ) {
                PenColor.entries.forEach { pen ->
                    val picked = color == pen
                    val label = stringResource(pen.label)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.RadioButton) {
                                viewModel.pickColor(pen)
                                open = Choosing.Nothing
                            }
                            .semantics {
                                contentDescription = label
                                selected = picked
                            },
                    ) { Swatch(pen, picked) }
                }
            }
        }
        // Onto a second line where it doesn't fit, as on a phone.
        FlowRow(
            itemVerticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier.panel().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            // The pen's colour; its six choices open above, as the symbols do: few things at a time on the music.
            val penColour = stringResource(R.string.pen_colour, stringResource(color.label))
            val colours = stringResource(if (choosingColor) R.string.colours_shown else R.string.colours_hidden)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.choose_pen_colour)) {
                        open = if (choosingColor) Choosing.Nothing else Choosing.Colour
                    }
                    .semantics {
                        contentDescription = penColour
                        stateDescription = colours
                    },
            ) { Swatch(color, picked = tool !is Tool.Eraser) }
            FilterChip(
                selected = stamp != null,
                onClick = {
                    when {
                        stamp == null -> {
                            viewModel.pick(Tool.Stamp(Symbol.Breath))
                            open = Choosing.Symbol
                        }
                        choosing -> {
                            viewModel.pick(Tool.Pen)
                            open = Choosing.Nothing
                        }
                        else -> open = Choosing.Symbol // Change the symbol.
                    }
                },
                // Once one is picked, it says which.
                label = { Text(stringResource(if (stamp == null || choosing) R.string.symbols else stamp.symbol.label)) },
            )
            FilterChip(
                selected = tool == Tool.Eraser,
                onClick = {
                    viewModel.pick(Tool.Eraser)
                    open = Choosing.Nothing
                },
                label = { Text(stringResource(R.string.eraser)) },
            )
            TextButton(onClick = viewModel::undo, enabled = canUndo) { Text(stringResource(R.string.undo), color = colors.message) }
            TextButton(onClick = { atTop = !atTop }) { Text(stringResource(if (atTop) R.string.move_down else R.string.move_up), color = colors.message) }
            TextButton(onClick = {
                viewModel.done()
                open = Choosing.Nothing
            }) { Text(stringResource(R.string.done), color = colors.message) }
        }
    }
}

/**
 * The score changed under my marks: say so, below the top buttons, with the version before to compare against. I
 * check; Volti doesn't move marks to where it thinks they belong. On that previous version itself, say which it is.
 */
@Composable
private fun BoxScope.ChangeNotice(
    change: ScoreChange,
    onOpenPrevious: (String) -> Unit,
    onChecked: () -> Unit,
    onLater: () -> Unit,
) {
    val colors = LocalReaderColors.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 72.dp, start = 12.dp, end = 12.dp)
            .clip(shape)
            .background(colors.surround)
            .border(1.5.dp, colors.message, shape)
            // A tap on the text is no page turn (as on the toolbar: the release nobody took is taken here).
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { if (it.previousPressed && !it.pressed && !it.isConsumed) it.consume() }
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        when (change) {
            is ScoreChange.Changed -> {
                Text(stringResource(R.string.part_changed), color = colors.message)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onOpenPrevious(change.previousUri) }) { Text(stringResource(R.string.previous_version), color = colors.message) }
                    TextButton(onClick = onChecked) { Text(stringResource(R.string.marks_checked), color = colors.message) }
                    // Not now, mid-rehearsal: hidden for this opening, the previous version kept.
                    TextButton(onClick = onLater) { Text(stringResource(R.string.not_now), color = colors.message) }
                }
            }
            ScoreChange.ShowingPrevious ->
                Text(stringResource(R.string.previous_version_text), color = colors.message)
        }
    }
}

/** Marks can't be saved: what is drawn now is gone when the part closes. Until a mark is saved again. */
@Composable
private fun MarksNotSaved(problem: MarkProblem, modifier: Modifier) {
    Text(
        when (problem) {
            MarkProblem.Full -> stringResource(device().pick(R.string.marks_full_tablet, R.string.marks_full_phone, R.string.marks_full_device))
            MarkProblem.Unwritable -> stringResource(R.string.marks_unwritable)
        },
        color = LocalReaderColors.current.message,
        modifier = modifier,
    )
}

/** Which row of choices is out above the toolbar: at most one. */
private enum class Choosing { Nothing, Symbol, Colour }

/** A colour as a 36 dp circle in its 48 dp target; the picked one with a thick ring. */
@Composable
private fun Swatch(pen: PenColor, picked: Boolean) {
    val ring = LocalReaderColors.current.message
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            // On paper, as the symbols are: black and purple show on a dark reader too.
            .background(Color.White)
            .border(if (picked) 3.dp else 1.dp, ring, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(Color(pen.argb)),
    )
}
