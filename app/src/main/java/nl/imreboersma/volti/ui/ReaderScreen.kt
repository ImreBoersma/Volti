package nl.imreboersma.volti.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.imreboersma.volti.data.PixelSize

@Composable
fun ReaderScreen(
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .onSizeChanged { viewModel.render(PixelSize(it.width, it.height)) }
            // Left half turns back, right half turns forward.
            .pointerInput(viewModel) {
                detectTapGestures { tap -> if (tap.x < size.width / 2) viewModel.previous() else viewModel.next() }
            },
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
                color = Color.Black,
            )
            is ReaderState.Page -> PageView(s, viewModel)
        }
    }
}

/**
 * The page on screen. In a half turn, the top half already shows the next page's top half, over this page,
 * with a line between them; the bottom half still shows the end of this page.
 */
@Composable
private fun PageView(page: ReaderState.Page, viewModel: ReaderViewModel) {
    val view = LocalView.current
    // Once no page is drawn any more (an error message, leaving the reader), say so when that frame is on
    // screen, so the last bitmaps can be reused.
    DisposableEffect(view) {
        onDispose {
            viewModel.onDrawn(emptyList())
            view.viewTreeObserver.registerFrameCommitCallback { viewModel.onPresented(emptyList()) }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
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
        PageImage(page.bitmap, page.index, if (page.half) Region.BottomHalf else Region.Whole)
        if (page.half) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(bottom = size.height / 2) {
                            drawRect(Color.White) // Covers this page's top half, also beside a narrower next page.
                            this@drawWithContent.drawContent()
                        }
                        drawLine(Color.Gray, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 3f)
                    },
            ) {
                PageImage(page.top, page.index + 1, Region.TopHalf)
            }
        }
    }
}

/** Where on screen a page is seen: in a half turn, each page only shows in one half. */
private enum class Region { Whole, TopHalf, BottomHalf }

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
                Text(text = "Can't show page ${index + 1}.", color = Color.Black)
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
