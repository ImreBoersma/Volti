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

    @Test
    fun myNameIsFoundSortedByAndShownTheFileNameToo() {
        val renamed = pdf("Farandole-Hoorn_in_F.pdf").copy(displayName = "Bizet - Farandole - F Horn 1")
        val other = pdf("Arlésienne.pdf")
        assertEquals(listOf(renamed), searchEnsemble(listOf(renamed, other), "bizet")) // By my name,
        assertEquals(listOf(renamed), searchEnsemble(listOf(renamed, other), "farandole hoorn")) // and by its file name.
        // Sorted by the name it shows: "Arlésienne.pdf" before "Bizet - …", though "Farandole-…" would come after.
        assertEquals(listOf(other, renamed), sortedByPlace(listOf(renamed, other)))
        assertEquals("Bizet - Farandole - F Horn 1", renamed.shownName)
        assertEquals("Arlésienne.pdf", other.shownName)
    }

    @Test
    fun aFileNameIsCleanedToStartANameFrom() {
        assertEquals("Farandole - Hoorn in F", cleanName("Farandole-Hoorn_in_F.pdf"))
        assertEquals("Er is nog zomer - Hoorn in F", cleanName("Er is nog zomer-Hoorn_in_F.pdf"))
        assertEquals("Saint-Saëns - Danse macabre", cleanName("Saint-Saëns - Danse macabre.PDF")) // A spaced dash: hyphens stay.
        assertEquals("Hymne", cleanName("  Hymne__.pdf"))
        assertEquals("Trompet 1-2", cleanName("Trompet_1-2.pdf")) // A range keeps its dash,
        assertEquals("Kerst 2024-12 - Hoorn", cleanName("Kerst_2024-12-Hoorn.Pdf")) // so does a date; any case of .pdf goes.
    }

    @Test
    fun savingTheStartUnchangedKeepsFollowingTheFileName() {
        val file = pdf("Farandole-Hoorn_in_F.pdf")
        assertEquals(null, nl.imreboersma.volti.ui.chosenName(file, "Farandole - Hoorn in F")) // The cleaned start,
        assertEquals(null, nl.imreboersma.volti.ui.chosenName(file, "Farandole-Hoorn_in_F.pdf")) // the file name,
        assertEquals(null, nl.imreboersma.volti.ui.chosenName(file, "   ")) // or nothing: no name of my own.
        assertEquals("Bizet - Farandole", nl.imreboersma.volti.ui.chosenName(file, " Bizet - Farandole "))
        // Once I have a name, saving the cleaned one is a choice of mine.
        val named = file.copy(displayName = "Bizet - Farandole")
        assertEquals("Farandole - Hoorn in F", nl.imreboersma.volti.ui.chosenName(named, "Farandole - Hoorn in F"))
    }

    @Test
    fun everyEnsembleIsSearchedWithMyPartsFirstAndItsNameAsAFolder() {
        val harmonie = files.map { PartIn(it, "Harmonie") }
        val bigBand = PartIn(pdf("Despacito - Trompet 1.pdf").copy(ensembleId = 2, mine = true), "Big Band")
        val all = harmonie + bigBand
        // Mine first, then by name.
        assertEquals(
            listOf("Despacito - Trompet 1.pdf", "Despacito - Hoorn 2.pdf"),
            searchEverywhere(all, "despacito").map { it.file.name },
        )
        // The ensemble's name narrows it down like a folder.
        assertEquals(listOf("Despacito - Trompet 1.pdf"), searchEverywhere(all, "big despacito").map { it.file.name })
        assertEquals(listOf("Hymne.pdf"), searchEverywhere(all, "harmonie concert").map { it.file.name })
        assertEquals(emptyList<PartIn>(), searchEverywhere(all, "   "))
    }

    @Test
    fun myPartsFromEveryEnsembleByTheNameTheyShow() {
        val a = PartIn(pdf("Zomer.pdf").copy(mine = true, displayName = "Avond"), "Harmonie")
        val b = PartIn(pdf("Bolero.pdf").copy(ensembleId = 2, mine = true), "Big Band")
        val notMine = PartIn(pdf("Alles.pdf"), "Harmonie")
        assertEquals(listOf(a, b), myParts(listOf(b, notMine, a)))
    }

    @Test
    fun aPartKeepsItsPlaceAsTheScreenSaysItIsMine() {
        val unticked = PartIn(pdf("Bolero.pdf"), "Harmonie") // Was mine when the screen opened.
        val other = PartIn(pdf("Alles.pdf"), "Harmonie")
        val wasMine = { file: EnsembleFile -> file == unticked.file }
        assertEquals(listOf(unticked), myParts(listOf(other, unticked), wasMine))
        assertEquals(listOf(unticked, other), searchEverywhere(listOf(other, unticked), "pdf", wasMine))
    }
}
