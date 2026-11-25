package nl.imreboersma.volti.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
            // The bitmap is rendered to fit this box, so Fit draws it 1:1. During a rotation,
            // Fit shrinks the old page until the new render lands, instead of clipping it.
            is ReaderState.Page -> if (s.bitmap == null) {
                Text(text = "Can't show page ${s.index + 1}.", color = Color.Black)
            } else {
                Image(
                    bitmap = s.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}
