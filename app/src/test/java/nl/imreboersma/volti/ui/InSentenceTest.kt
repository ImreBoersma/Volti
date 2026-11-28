package nl.imreboersma.volti.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class InSentenceTest {
    @Test
    fun aNameInASentenceIsOneLineAndCutOff() {
        assertEquals("Bolero - Hoorn 1.pdf", inSentence("Bolero\n- Hoorn  1.pdf"))
        val long = inSentence("lang ".repeat(400))
        assertEquals(NAME_CHARS, long.length)
        assertEquals('…', long.last())
    }
}
