package nl.imreboersma.volti.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreListerTest {
    private val dir = "vnd.android.document/directory"

    @Test
    fun keepsFoldersAndPdfsByMimeType() {
        val rows = listOf(
            DocumentRow("1", "Score.pdf", "application/pdf"),
            DocumentRow("2", "notes.pdf", "text/plain"),
            DocumentRow("3", "Scan without extension", "application/pdf"),
            DocumentRow("4", "Bach", dir),
            DocumentRow("5", "unknown", null),
        )
        assertEquals(listOf("4", "3", "1"), libraryRows(rows).map { it.id })
    }

    @Test
    fun foldersComeBeforeScores() {
        val rows = listOf(
            DocumentRow("1", "Adagio.pdf", "application/pdf"),
            DocumentRow("2", "Zelenka", dir),
        )
        assertEquals(listOf("Zelenka", "Adagio.pdf"), libraryRows(rows).map { it.name })
    }

    @Test
    fun sortsByNameIgnoringCase() {
        val rows = listOf("bolero.pdf", "Adagio.pdf", "Canon.pdf")
            .mapIndexed { i, name -> DocumentRow("$i", name, "application/pdf") }
        assertEquals(listOf("Adagio.pdf", "bolero.pdf", "Canon.pdf"), libraryRows(rows).map { it.name })
    }

    @Test
    fun numbersSortByValue() {
        val names = listOf("Mars 10.pdf", "Mars 2.pdf", "Mars 1.pdf", "Violin 10", "Violin 9", "Violin 1")
        assertEquals(
            listOf("Mars 1.pdf", "Mars 2.pdf", "Mars 10.pdf", "Violin 1", "Violin 9", "Violin 10"),
            names.sortedWith(NaturalOrder()),
        )
    }

    @Test
    fun naturalOrderHandlesEdges() {
        val order = NaturalOrder()
        assertTrue("leading zeros only break ties", order.compare("Part 7", "Part 07") < 0)
        assertTrue("a prefix comes first", order.compare("Op. 2", "Op. 2 no. 1") < 0)
        assertTrue("no overflow", order.compare("Track 99999999999999999999", "Track 100000000000000000000") < 0)
        assertEquals(0, order.compare("Mars 2", "Mars 2"))
    }
}
