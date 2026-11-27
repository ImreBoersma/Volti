package nl.imreboersma.volti.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    /** A file [name] in [folders], as an ensemble's search matches it. */
    private fun score(name: String, vararg folders: String) = (folders.toList() + name).map(::searchable)

    private fun matches(query: String, names: List<String>) = matchesWords(searchWords(query), names)

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
