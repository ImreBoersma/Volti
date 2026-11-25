package nl.imreboersma.volti.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreListerTest {
    @Test
    fun keepsOnlyPdfMimeType() {
        val rows = listOf(
            DocumentRow("1", "Score.pdf", "application/pdf"),
            DocumentRow("2", "notes.pdf", "text/plain"),
            DocumentRow("3", "Scan without extension", "application/pdf"),
            DocumentRow("4", "Subfolder", "vnd.android.document/directory"),
            DocumentRow("5", "unknown", null),
        )
        assertEquals(listOf("3", "1"), pdfRows(rows).map { it.id })
    }

    @Test
    fun sortsByNameIgnoringCase() {
        val rows = listOf("bolero.pdf", "Adagio.pdf", "Canon.pdf")
            .mapIndexed { i, name -> DocumentRow("$i", name, "application/pdf") }
        assertEquals(listOf("Adagio.pdf", "bolero.pdf", "Canon.pdf"), pdfRows(rows).map { it.name })
    }
}
