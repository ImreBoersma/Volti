package nl.imreboersma.volti.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.imreboersma.volti.EnsembleFiles
import nl.imreboersma.volti.VoltiApp
import nl.imreboersma.volti.db.Ensemble
import nl.imreboersma.volti.db.EnsembleFile
import nl.imreboersma.volti.db.VoltiDatabase
import nl.imreboersma.volti.ui.theme.VoltiTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** A name from Drive or another app with line breaks or thousands of characters keeps its row to two lines. */
@RunWith(AndroidJUnit4::class)
class NameLinesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val db = VoltiDatabase.get(InstrumentationRegistry.getInstrumentation().targetContext)
    private lateinit var nav: NavHostController

    @After
    fun clean() = runBlocking {
        db.ensembles().all().first().filter { it.name == "Lines test" }.forEach { db.ensembles().remove(it) }
    }

    @Test
    fun aLongNameWithLineBreaksTakesTwoLines() {
        val name = "Farandole\nHoorn\nin\nF " + "lang ".repeat(400) + ".pdf"
        val ensemble = runBlocking {
            val id = db.ensembles().add(Ensemble(name = "Lines test", instrument = "Horn", folderId = null))
            db.files().save(listOf(EnsembleFile(id, "f" + UUID.randomUUID(), null, name, emptyList(), "0".repeat(32), 1, imported = true)))
            id
        }
        compose.setContent {
            nav = rememberNavController()
            VoltiTheme { VoltiApp(nav) }
        }
        compose.runOnUiThread { nav.navigate(EnsembleFiles(ensemble)) }
        val row = compose.onNode(hasText("Farandole", substring = true))
        compose.waitUntil(10_000) { runCatching { row.fetchSemanticsNode() }.isSuccess }

        val layouts = mutableListOf<TextLayoutResult>()
        row.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        assertTrue("laid out", layouts.isNotEmpty())
        assertTrue("at most two lines, was ${layouts[0].lineCount}", layouts[0].lineCount <= NAME_LINES)
        assertTrue("cut off with an ellipsis", layouts[0].isLineEllipsized(layouts[0].lineCount - 1))
    }
}
