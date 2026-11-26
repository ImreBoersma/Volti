package nl.imreboersma.volti.setlist

import org.junit.Assert.assertEquals
import org.junit.Test

class MovedTest {
    private val pieces = listOf("a", "b", "c", "d")

    @Test
    fun aPieceMovesAndTheRestKeepTheirOrder() {
        assertEquals(listOf("b", "c", "a", "d"), pieces.moved(0, 2))
        assertEquals(listOf("d", "a", "b", "c"), pieces.moved(3, 0))
        assertEquals(listOf("a", "c", "b", "d"), pieces.moved(1, 2))
    }

    @Test
    fun nowhereToGoChangesNothing() {
        assertEquals(pieces, pieces.moved(0, 0))
        assertEquals(pieces, pieces.moved(0, -1))
        assertEquals(pieces, pieces.moved(4, 0))
    }
}
