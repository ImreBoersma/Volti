package nl.imreboersma.volti.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartMatchTest {
    private val horn1 = parseInstrument("F Horn 1")

    private fun mine(name: String, me: Instrument = horn1, folders: List<String> = emptyList()) =
        looksLikeMyPart(me, name, folders)

    @Test
    fun readsWhatIPlay() {
        assertEquals(Instrument("horn", null, "f", setOf(1), listOf("horn")), horn1)
        assertEquals("bb", parseInstrument("Bb clarinet 2").key)
        assertEquals("clarinet", parseInstrument("Bb clarinet 2").family)
        assertEquals(setOf(2), parseInstrument("Bb clarinet 2").parts)
        assertEquals("alto", parseInstrument("Alto sax").voice)
        assertEquals(emptySet<Int>(), parseInstrument("Alto sax").parts)
    }

    /** The horn folder of Harmonieorkest Brummen, as it was on 2026-11-26. */
    @Test
    fun theBrummenHornFolderForHorn1() {
        val yes = listOf(
            "A Collection of Chorales - F-hoorn 1.pdf",
            "Cassiopeia - F-hoorn 1.pdf",
            "GulliversTravels - hoorn13.pdf", // Horns 1 and 3.
            "Happy - F-hoorn 1.pdf",
            "Inspecteur Gadget French Horn 1 in F.pdf",
            "Invocation-hoorn12.pdf",
            "Mas que Nada - F-hoorn 1-2.pdf",
            "Sint-medley Rock & Roll - Hoorn in F.pdf", // One part for all horns.
            "Spirit of Independence_Hoorn F 1_2.pdf",
            "The Wind in the Willows - F-hoorn 1-3.pdf",
        )
        val no = listOf(
            "A Collection of Chorales - F-hoorn 2.pdf",
            "Cassiopeia - F-hoorn 3.pdf",
            "Despacito-euphonium.pdf",
            "Despacito-hoorn2.pdf",
            "GulliversTravels - hoorn24.pdf",
            "Inspecteur Gadget French Horn 2 in F.pdf",
            "Invocation-hoorn34.pdf",
            "Mas que Nada - F-hoorn 3-4.pdf",
            "Sinterklaas wie kent hem niet - F-hoorn 2-3.pdf",
        )
        yes.forEach { assertTrue(it, mine(it)) }
        no.forEach { assertFalse(it, mine(it)) }
    }

    @Test
    fun wordsAreMatchedWhole() {
        assertFalse(mine("Concerto - Flugelhorn 1.pdf"))
        assertFalse(mine("Hornpipe - Trumpet 1.pdf"))
        assertTrue(mine("March - Hn. 1.pdf"))
    }

    @Test
    fun numbersInTheTitleDontCount() {
        assertTrue(mine("1812 Overture - Horn in F 1.pdf"))
        assertFalse(mine("Symphony 1 - Horn 2.pdf"))
    }

    @Test
    fun aFolderCanSayTheInstrument() {
        assertTrue(mine("Despacito.pdf", folders = listOf("Hoorn")))
        assertFalse(mine("Despacito.pdf", folders = listOf("Klarinet")))
    }

    @Test
    fun voicesMustAgreeWhenBothSayOne() {
        val alto = parseInstrument("Alto sax")
        assertTrue(mine("Pennsylvania 6-5000 - Alto Sax 1.pdf", alto))
        assertTrue(mine("Pennsylvania 6-5000 - Altsax.pdf", alto))
        assertFalse(mine("Pennsylvania 6-5000 - Tenor Sax.pdf", alto))
        assertTrue(mine("Pennsylvania 6-5000 - Saxofoon.pdf", alto))
    }

    @Test
    fun romanNumeralsAndOrdinalsArePartNumbers() {
        assertTrue(mine("Symphonie 5 - Corno I in F.pdf"))
        assertFalse(mine("Symphonie 5 - Corno II in F.pdf"))
        assertFalse(mine("Symphonie 5 - Horn III.pdf"))
        assertTrue(mine("Wilhelmus - 1e Hoorn.pdf"))
        assertFalse(mine("Wilhelmus - 2e Hoorn.pdf"))
        assertFalse(mine("Wilhelmus - 2nd Horn.pdf"))
    }

    @Test
    fun anotherKeyIsAnotherInstrument() {
        assertFalse(mine("Mars - Es hoorn 1.pdf")) // A fanfare's alto horn, not mine.
        assertTrue(mine("Mars - Hoorn in F 1.pdf"))
        val clarinet = parseInstrument("Bb clarinet 1")
        assertTrue(mine("Mars - Bb Klarinet 1.pdf", clarinet))
        assertTrue(mine("Mars - Klarinet 1.pdf", clarinet))
        assertFalse(mine("Mars - Es Klarinet 1.pdf", clarinet))
    }

    @Test
    fun aVoiceIDidntNameIsAnotherInstrument() {
        val clarinet = parseInstrument("Bb clarinet 1")
        assertFalse(mine("Mars - Bas Klarinet.pdf", clarinet))
        assertFalse(mine("Mars - Alto Clarinet.pdf", clarinet))
    }

    @Test
    fun theVoiceIsReadNextToTheInstrumentNotInTheTitle() {
        val tenor = parseInstrument("Tenor sax")
        assertTrue(mine("Alto Blues - Tenor Sax.pdf", tenor))
        assertFalse(mine("Tenor Madness - Alto Sax.pdf", tenor))
    }

    @Test
    fun anInstrumentVoltiDoesntKnowMatchesByItsWords() {
        val me = parseInstrument("Doedelzak 1")
        assertTrue(mine("Amazing Grace - Doedelzak.pdf", me))
        assertFalse(mine("Amazing Grace - Fluit.pdf", me))
    }
}
