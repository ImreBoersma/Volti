package nl.imreboersma.volti.render

import nl.imreboersma.volti.data.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class CheckRenderedSizeTest {
    private val max = PixelSize(1600, 2400)
    private val shared = 1600 * 2400 * 4

    @Test
    fun aSizeThatFitsPasses() {
        assertEquals(PixelSize(1600, 2264), checkRenderedSize(intArrayOf(1600, 2264), max, shared))
    }

    @Test
    fun aCompromisedRendererCannotMakeTheAppAllocateMore() {
        for (bad in listOf(intArrayOf(40_000, 40_000), intArrayOf(1601, 10), intArrayOf(0, 10), intArrayOf(-1, 10))) {
            assertThrows(IOException::class.java) { checkRenderedSize(bad, max, shared) }
        }
        assertThrows(IOException::class.java) { checkRenderedSize(intArrayOf(1600, 2400), max, shared - 1) }
        assertThrows(IOException::class.java) { checkRenderedSize(null, max, shared) }
        assertThrows(IOException::class.java) { checkRenderedSize(intArrayOf(1600), max, shared) }
    }
}
