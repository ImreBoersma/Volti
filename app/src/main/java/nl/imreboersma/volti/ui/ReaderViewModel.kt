package nl.imreboersma.volti.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nl.imreboersma.volti.Reader
import nl.imreboersma.volti.data.PixelSize
import nl.imreboersma.volti.data.ScoreDocument
import nl.imreboersma.volti.data.openScore

sealed interface ReaderState {
    data object Loading : ReaderState
    data object Failed : ReaderState
    data class Page(val bitmap: Bitmap) : ReaderState
}

class ReaderViewModel @JvmOverloads constructor(
    app: Application,
    handle: SavedStateHandle,
    private val open: (Uri) -> ScoreDocument = { openScore(app.contentResolver, it) },
) : AndroidViewModel(app) {
    private val uri = handle.toRoute<Reader>().uri.toUri()

    /** The renderer is not thread-safe: every touch of [document] runs on this one thread. */
    private val pdfThread = Dispatchers.IO.limitedParallelism(1)
    private var document: ScoreDocument? = null
    private var renderJob: Job? = null
    private var lastSize: PixelSize? = null

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state

    /**
     * Renders page one at [size]. Called again on rotation; the file stays open.
     * Skips empty and unchanged sizes, and gives up for good once the file failed.
     */
    fun render(size: PixelSize) {
        if (size.width <= 0 || size.height <= 0 || size == lastSize || _state.value == ReaderState.Failed) return
        lastSize = size
        renderJob?.cancel()
        renderJob = viewModelScope.launch(pdfThread) {
            _state.value = try {
                val doc = document ?: open(uri).also {
                    Log.i("Volti", "Opened $uri, ${it.pageCount} pages")
                    document = it
                }
                ReaderState.Page(doc.renderPage(0, size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Untrusted input: whatever the parser throws means this file can't be shown.
                Log.w("Volti", "Can't open $uri", e)
                ReaderState.Failed
            }
        }
    }

    override fun onCleared() {
        // viewModelScope is already cancelled. Close on pdfThread, so it waits for a render in flight.
        CoroutineScope(pdfThread).launch {
            try {
                document?.close()
            } catch (e: Exception) {
                Log.w("Volti", "Closing $uri failed", e)
            }
            document = null
        }
    }
}
