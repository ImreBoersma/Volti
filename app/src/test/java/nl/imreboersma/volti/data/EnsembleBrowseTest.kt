package nl.imreboersma.volti.data

import nl.imreboersma.volti.db.EnsembleFile
import org.junit.Assert.assertEquals
import org.junit.Test

class EnsembleBrowseTest {
    private fun pdf(name: String, vararg folders: String) =
        EnsembleFile(1, "$name@${folders.joinToString("/")}", null, name, folders.toList(), "0".repeat(32), 1)

    private val files = listOf(
        pdf("Happy - F-hoorn 1.pdf"),
        pdf("Cassiopeia - F-hoorn 10.pdf"),
        pdf("Cassiopeia - F-hoorn 2.pdf"),
        pdf("Despacito - Hoorn 2.pdf", "Pop"),
        pdf("Mas que Nada - Hoorn 1-2.pdf", "Pop", "Latin"),
        pdf("Hymne.pdf", "Concert"),
    )

    private fun names(rows: List<EnsembleRow>) = rows.map {
        when (it) {
            is EnsembleRow.Folder -> it.name + "/"
            is EnsembleRow.Pdf -> it.file.name
        }
    }

    @Test
    fun theTopShowsFoldersFirstThenPdfsInNaturalOrder() {
        assertEquals(
            listOf("Concert/", "Pop/", "Cassiopeia - F-hoorn 2.pdf", "Cassiopeia - F-hoorn 10.pdf", "Happy - F-hoorn 1.pdf"),
            names(ensembleRows(files, emptyList())),
        )
    }

    @Test
    fun aSubfolderShowsItsOwnFoldersAndPdfs() {
        assertEquals(listOf("Latin/", "Despacito - Hoorn 2.pdf"), names(ensembleRows(files, listOf("Pop"))))
        assertEquals(listOf("Mas que Nada - Hoorn 1-2.pdf"), names(ensembleRows(files, listOf("Pop", "Latin"))))
        assertEquals(emptyList<String>(), names(ensembleRows(files, listOf("Gone"))))
    }

    @Test
    fun aFolderNamedLikeTheStartOfAnotherIsNotConfused() {
        val tricky = listOf(pdf("a.pdf", "Pop"), pdf("b.pdf", "Pop music"))
        assertEquals(listOf("a.pdf"), names(ensembleRows(tricky, listOf("Pop"))))
    }

    @Test
    fun aFolderThatIsGoneFallsBackToTheNearestOneThatIsThere() {
        assertEquals(listOf("Pop", "Latin"), existingPath(files, listOf("Pop", "Latin")))
        assertEquals(listOf("Pop"), existingPath(files, listOf("Pop", "Rock")))
        assertEquals(emptyList<String>(), existingPath(files, listOf("Jazz", "Swing")))
        assertEquals(emptyList<String>(), existingPath(emptyList(), listOf("Pop")))
    }

    @Test
    fun searchCoversTheWholeEnsembleAndItsFolders() {
        assertEquals(
            listOf("Despacito - Hoorn 2.pdf", "Mas que Nada - Hoorn 1-2.pdf"),
            searchEnsemble(files, "pop hoorn").map { it.name },
        )
        assertEquals(listOf("Mas que Nada - Hoorn 1-2.pdf"), searchEnsemble(files, "LATIN").map { it.name })
        assertEquals(listOf("Hymne.pdf"), searchEnsemble(files, "hymne").map { it.name })
        assertEquals(emptyList<String>(), searchEnsemble(files, "   ").map { it.name })
    }
}
