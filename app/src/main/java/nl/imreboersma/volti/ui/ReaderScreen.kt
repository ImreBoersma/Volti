package nl.imreboersma.volti.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.imreboersma.volti.data.PixelSize
import nl.imreboersma.volti.data.PageArea
import androidx.compose.foundation.layout.BoxScope
import nl.imreboersma.volti.ui.theme.LocalReaderColors
import nl.imreboersma.volti.ui.theme.readerColorsOn
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions

/**
 * Where a page is on screen, for whatever is drawn over it (annotations): page [index], showing [area] of it as a
 * [bitmapWidth] x [bitmapHeight] bitmap fitted in the box, seen in [region]. Only a [Region.Whole] page takes input.
 */
data class PageOverlay(val index: Int, val area: PageArea, val bitmapWidth: Int, val bitmapHeight: Int, val region: Region)

/**
 * The reader. [overlay] is drawn over each page and [controls] over the reader, for what the reader itself doesn't
 * know about (annotations): it only says where the page is.
 */
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable (PageOverlay) -> Unit = {},
    controls: @Composable BoxScope.() -> Unit = {},
    viewModel: ReaderViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val paper by viewModel.paper.collectAsStateWithLifecycle()
    // Once the score's paper colour is known, the reader takes it around the page; until then, the theme's.
    val colors = paper?.let { readerColorsOn(Color(it)) } ?: LocalReaderColors.current
    // The reader holds the keyboard focus, for the pedal.
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    CompositionLocalProvider(LocalReaderColors provides colors) {
        Box(
            modifier = modifier
                .fillMaxSize()
                // Around the page: the score's paper colour, or until it's known the theme's surround.
                .background(LocalReaderColors.current.surround)
                .onSizeChanged { viewModel.render(PixelSize(it.width, it.height)) }
                // Left half turns back, right half turns forward.
                .pointerInput(viewModel) {
                    detectTapGestures { tap -> if (tap.x < size.width / 2) viewModel.previous() else viewModel.next() }
                }
                // Before any focused control: Space on a pedal turns the page, it doesn't press a toolbar button.
                .onPreviewKeyEvent { pageTurnKey(it, viewModel) }
                // On the node TalkBack focuses (the focusable one): the tap halves aren't reachable with TalkBack.
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Next page") { viewModel.next(); true },
                        CustomAccessibilityAction("Previous page") { viewModel.previous(); true },
                    )
                }
                .focusRequester(focus)
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                ReaderState.Loading -> Unit
                is ReaderState.Failed -> Text(
                    text = when (s.problem) {
                        ReaderState.Problem.PasswordProtected -> "This PDF is password-protected."
                        ReaderState.Problem.TooSlow ->
                            "This file takes too long to open. If other scores stay blank too, close and reopen Volti."
                        ReaderState.Problem.Unreadable -> "Can't open this file."
                    },
                    color = LocalReaderColors.current.message,
                )
                is ReaderState.Page -> PageView(s, viewModel, overlay)
            }
            controls()
            BackButton(onBack, Modifier.align(Alignment.TopStart))
        }
    }
}

/**
 * A page-turn pedal is a keyboard: forward on Page Down, right, down and space, back on Page Up, left and up. One
 * press is one turn: a pedal held down doesn't skip pages. Its repeats are still taken, so they don't move the focus.
 * Anything else is left alone: Esc goes Back (see `MainActivity`).
 */
private fun pageTurnKey(event: KeyEvent, viewModel: ReaderViewModel): Boolean {
    val forward = when (event.key) {
        Key.PageDown, Key.DirectionRight, Key.DirectionDown, Key.Spacebar -> true
        Key.PageUp, Key.DirectionLeft, Key.DirectionUp -> false
        else -> return false
    }
    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
        if (forward) viewModel.next() else viewModel.previous()
    }
    return true
}

/**
 * Back to the library, for when the back gesture is awkward on a stand. It shows for [SHOWN] when the score
 * opens and then gets out of the way: on a cropped score the clef and key signature sit right in that corner. A tap
 * in the corner brings it back. Either way the corner takes its own taps, so it doesn't turn the page back.
 */
@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) =
    CornerButton("Back to the library", "Show the back button", onBack, modifier) { ink ->
        val stroke = 2.5.dp.toPx()
        val arrow = Size(20.dp.toPx(), 20.dp.toPx())
        val left = (size.width - arrow.width) / 2
        val mid = size.height / 2
        drawLine(ink, Offset(left + arrow.width, mid), Offset(left, mid), stroke, StrokeCap.Round)
        drawLine(ink, Offset(left, mid), Offset(left + arrow.width * 0.45f, mid - arrow.height * 0.4f), stroke, StrokeCap.Round)
        drawLine(ink, Offset(left, mid), Offset(left + arrow.width * 0.45f, mid + arrow.height * 0.4f), stroke, StrokeCap.Round)
    }

/**
 * A round button in a corner of the reader, over the music: it shows for [SHOWN] and then gets out of the way; a tap
 * in its corner brings it back. [glyph] is drawn in the reader's ink (or cream, whichever reads on this paper), on a
 * disc of the paper itself with a ring, so it has the full contrast of the reader's text. Drawn: no icon library.
 */
@Composable
fun CornerButton(
    label: String,
    showLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: DrawScope.(ink: Color) -> Unit,
) {
    val colors = LocalReaderColors.current
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(shown) {
        if (shown) {
            delay(SHOWN)
            shown = false
        }
    }
    Box(
        modifier = modifier
            .padding(12.dp)
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                onClickLabel = if (shown) label else showLabel,
                role = Role.Button,
                onClick = { if (shown) onClick() else shown = true },
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (shown) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(colors.surround)
                drawCircle(colors.message, style = Stroke(1.5.dp.toPx()), radius = size.minDimension / 2 - 1.dp.toPx())
                glyph(colors.message)
            }
        }
    }
}

/** How long the back button shows after opening a score, or after a tap in its corner. */
private val SHOWN = 3.seconds

/**
 * The page on screen. In a half turn, the top half already shows the next page's top half, over this page,
 * with a line between them; the bottom half still shows the end of this page.
 */
@Composable
private fun PageView(page: ReaderState.Page, viewModel: ReaderViewModel, overlay: @Composable (PageOverlay) -> Unit) {
    val view = LocalView.current
    // Once no page is drawn any more (an error message, leaving the reader), say so when that frame is on
    // screen, so the last bitmaps can be reused.
    DisposableEffect(view) {
        onDispose {
            viewModel.onDrawn(emptyList())
            view.viewTreeObserver.registerFrameCommitCallback { viewModel.onPresented(emptyList()) }
        }
    }
    val background = LocalReaderColors.current.surround
    val line = LocalReaderColors.current.line
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Which page is showing, for accessibility services, and for tests.
            .semantics {
                stateDescription = "Page ${page.index + 1}${if (page.half) " and the top of ${page.index + 2}" else ""} of ${page.pageCount}"
            }
            // Tells the ViewModel what is really on screen, so it never draws into those bitmaps: drawn when this
            // frame is recorded, presented once the render thread has committed it. Runs when the page changes,
            // not every frame.
            .drawWithContent {
                drawContent()
                val shown = page.bitmaps
                viewModel.onDrawn(shown)
                view.viewTreeObserver.registerFrameCommitCallback { viewModel.onPresented(shown) }
            },
    ) {
        val region = if (page.half) Region.BottomHalf else Region.Whole
        PageImage(page.bitmap, page.index, region)
        page.bitmap?.let { overlay(PageOverlay(page.index, page.area, it.width, it.height, region)) }
        if (page.half) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(bottom = size.height / 2) {
                            drawRect(background) // Covers this page's top half, also beside a narrower next page.
                            this@drawWithContent.drawContent()
                        }
                        drawLine(line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 3f)
                    },
            ) {
                PageImage(page.top, page.index + 1, Region.TopHalf)
                page.top?.let { overlay(PageOverlay(page.index + 1, page.area, it.width, it.height, Region.TopHalf)) }
            }
        }
    }
}

/** Where on screen a page is seen: in a half turn, each page only shows in one half. */
enum class Region { Whole, TopHalf, BottomHalf }

/**
 * A rendered page, or a message if it couldn't be rendered, centred in the [region] where it is seen. The bitmap
 * is rendered to fit this box, so Fit draws it 1:1; during a rotation, Fit shrinks the old page until the new
 * render lands, instead of clipping it.
 */
@Composable
private fun PageImage(bitmap: Bitmap?, index: Int, region: Region) {
    if (bitmap == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(if (region == Region.Whole) 1f else 0.5f)
                    .align(if (region == Region.BottomHalf) Alignment.BottomCenter else Alignment.TopCenter),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Can't show page ${index + 1}.", color = LocalReaderColors.current.message)
            }
        }
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
