package nl.imreboersma.volti.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    /** Matching only looks at names; a Subfolder stands in for a score, as android.net.Uri can't be built here. */
    private fun score(name: String, vararg folders: String) =
        FoundEntry(Subfolder("entry", name), folders.mapIndexed { i, f -> Subfolder("id$i", f) })

    @Test
    fun ignoresCaseAndAccents() {
        assertEquals("handel - messiah", searchable("Händel - Messiah"))
        assertTrue(matches("HANDEL", score("Händel - Messiah.pdf")))
        assertTrue(matches("händel", score("Handel - Messiah.pdf")))
    }

    @Test
    fun everyWordMustAppearInTheNameOrAFolderOnTheWay() {
        val part = score("Viool 1.pdf", "Bach - Mass in B minor")
        assertTrue(matches("bach viool", part))
        assertTrue(matches("  viool   bach ", part))
        assertFalse(matches("bach cello", part))
    }

    @Test
    fun anEmptyQueryMatchesNothing() {
        assertFalse(matches("   ", score("Anything.pdf")))
    }

    @Test
    fun theSeparatorBetweenNamesMatchesNothing() {
        assertFalse(matches("/", score("Viool 1.pdf", "Bach")))
        assertFalse(matches("bach/viool", score("Viool 1.pdf", "Bach"))) // One word that only exists across names.
        assertTrue(matches("bach viool", score("Viool 1.pdf", "Bach"))) // Two words, each in a name.
    }
}
