package nl.imreboersma.volti.annotate

import android.app.Application
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.imreboersma.volti.db.VoltiDatabase
import nl.imreboersma.volti.db.fileKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Stamps and strokes through the ViewModel: placing, erasing, undo, and what reaches the database. */
@RunWith(AndroidJUnit4::class)
class AnnotationViewModelTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val db = VoltiDatabase.get(app)
    private val key = "test:" + UUID.randomUUID()
    private lateinit var vm: AnnotationViewModel

    @Before
    fun open() {
        runBlocking {
            vm = withContext(Dispatchers.Main) { AnnotationViewModel(app, SavedStateHandle(mapOf("uri" to "file:///none.pdf", "scoreKey" to key))) }
            vm.ready.first { it }
        }
    }

    @After
    fun clean() = runBlocking {
        db.annotations().forScore(key).let { db.annotations().remove(it.map { s -> s.id }) }
        db.annotations().symbolsForScore(key).let { db.annotations().removeSymbols(it.map { s -> s.id }) }
    }

    private fun stroke(vararg points: Point): Stroke {
        val inputs = MutableStrokeInputBatch()
        points.forEachIndexed { i, p -> inputs.add(StrokeInput.create(p.x, p.y, i * 10L)) }
        return Stroke(Brush.createWithColorIntArgb(StockBrushes.pressurePen(), PEN_COLORS[0], 3f, 0.1f), inputs)
    }

    /** Until the writes behind the screen have landed. */
    private suspend fun stored(strokes: Int, symbols: Int) {
        repeat(100) {
            if (db.annotations().forScore(key).size == strokes && db.annotations().symbolsForScore(key).size == symbols) return
            kotlinx.coroutines.delay(20)
        }
        assertEquals(strokes, db.annotations().forScore(key).size)
        assertEquals(symbols, db.annotations().symbolsForScore(key).size)
    }

    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    @Test
    fun aStampIsPlacedAndUndone() = runBlocking {
        main { vm.place(0, Point(100f, 200f), Symbol.Fermata) }
        assertEquals(listOf("Fermata"), vm.symbols.value[0]?.map { it.symbol })
        stored(strokes = 0, symbols = 1)
        main { vm.undo() }
        assertTrue(vm.symbols.value[0].isNullOrEmpty())
        stored(strokes = 0, symbols = 0)
    }

    @Test
    fun oneEraseTakesStrokesAndStampsAndOneUndoBringsThemBack() = runBlocking {
        main {
            vm.finished(0, stroke(Point(90f, 200f), Point(110f, 200f)))
            vm.place(0, Point(100f, 210f), Symbol.MF)
            vm.place(1, Point(100f, 200f), Symbol.Breath) // Another page: untouched by an erase on page 0.
            vm.eraseStart()
            vm.eraseAt(0, Point(100f, 205f))
            vm.eraseEnd()
        }
        assertTrue(vm.strokes.value[0].isNullOrEmpty())
        assertTrue(vm.symbols.value[0].isNullOrEmpty())
        assertEquals(1, vm.symbols.value[1]?.size)
        stored(strokes = 0, symbols = 1)
        main { vm.undo() }
        assertEquals(1, vm.strokes.value[0]?.size)
        assertEquals(listOf("MF"), vm.symbols.value[0]?.map { it.symbol })
        stored(strokes = 1, symbols = 2)
    }

    /** A Drive score in an ensemble that I opened at version 1, then at version 2. */
    private suspend fun changedDriveScore(fileId: String): Long {
        val ensemble = db.ensembles().add(nl.imreboersma.volti.db.Ensemble(name = "Test", instrument = "Horn", folderId = "f" + UUID.randomUUID().toString().take(12)))
        val v1 = "1".repeat(32)
        val v2 = "2".repeat(32)
        db.files().save(listOf(nl.imreboersma.volti.db.EnsembleFile(ensemble, fileId, null, "a.pdf", emptyList(), v2, 1, localMd5 = v2)))
        db.files().opened(ensemble, fileId, v1)
        db.files().opened(ensemble, fileId, v2)
        return ensemble
    }

    private suspend fun openDrive(fileId: String): AnnotationViewModel {
        val vm = withContext(Dispatchers.Main) { AnnotationViewModel(app, SavedStateHandle(mapOf("uri" to "file:///none.pdf", "scoreKey" to fileKey(fileId)))) }
        vm.ready.first { it }
        repeat(50) { if (vm.change.value != null) return vm; kotlinx.coroutines.delay(20) }
        return vm
    }

    @Test
    fun aChangedScoreWithMyMarksSaysSo() = runBlocking {
        val fileId = "file" + UUID.randomUUID().toString().take(8)
        val ensemble = changedDriveScore(fileId)
        db.annotations().addSymbols(listOf(AnnotationSymbol("s-$fileId", fileKey(fileId), 0, "Fermata", 1f, 1f, 0)))
        try {
            val vm = openDrive(fileId)
            assertTrue(vm.change.value is ScoreChange.Changed)
            main { vm.marksChecked() }
            repeat(50) { if (db.files().previousOf(fileId) == null) return@repeat; kotlinx.coroutines.delay(20) }
            assertEquals(null, db.files().previousOf(fileId))
        } finally {
            db.annotations().removeSymbols(listOf("s-$fileId"))
            db.ensembles().remove(db.ensembles().get(ensemble)!!)
        }
    }

    @Test
    fun aChangedScoreWithoutMarksSaysNothingAndLetsThePreviousGo() = runBlocking {
        val fileId = "file" + UUID.randomUUID().toString().take(8)
        val ensemble = changedDriveScore(fileId)
        try {
            val vm = openDrive(fileId)
            assertEquals(null, vm.change.value)
            assertEquals(null, db.files().previousOf(fileId))
        } finally {
            db.ensembles().remove(db.ensembles().get(ensemble)!!)
        }
    }

    @Test
    fun thePreviousVersionIsToLookAtOnly() = runBlocking {
        val previous = withContext(Dispatchers.Main) {
            AnnotationViewModel(app, SavedStateHandle(mapOf("uri" to "file:///none.pdf", "scoreKey" to key, "previous" to true)))
        }
        repeat(50) { if (previous.change.value != null) return@repeat; kotlinx.coroutines.delay(20) }
        assertEquals(ScoreChange.ShowingPrevious, previous.change.value)
        assertEquals("no pen on the old layout", false, previous.ready.value)
    }
}
