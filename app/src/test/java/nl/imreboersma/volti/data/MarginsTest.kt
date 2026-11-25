package nl.imreboersma.volti.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MarginsTest {
    private val white = 0xffffffff.toInt()
    private val black = 0xff000000.toInt()

    private fun page(width: Int = 100, height: Int = 100, draw: (IntArray) -> Unit = {}) =
        IntArray(width * height) { white }.also(draw)

    private fun IntArray.block(x0: Int, y0: Int, x1: Int, y1: Int, width: Int = 100) {
        for (y in y0 until y1) for (x in x0 until x1) this[y * width + x] = black
    }

    private fun assertArea(expected: PageArea, actual: PageArea) {
        listOf(expected.left to actual.left, expected.top to actual.top, expected.right to actual.right, expected.bottom to actual.bottom)
            .forEach { (e, a) -> assertEquals("$expected vs $actual", e, a, 0.001f) }
    }

    @Test
    fun cropsToTheContentWithPadding() {
        val pixels = page { it.block(10, 12, 90, 88) }
        assertArea(PageArea(0.08f, 0.10f, 0.92f, 0.90f), contentArea(pixels, 100, 100))
    }

    @Test
    fun aBlankPageIsShownWhole() {
        assertEquals(PageArea.WHOLE, contentArea(page(), 100, 100))
    }

    @Test
    fun dustOnAScanIsNotContent() {
        val pixels = page {
            it.block(10, 12, 90, 88)
            it[2 * 100 + 2] = black // A speck in each corner, outside the cap: counted, they'd keep the whole page.
            it[97 * 100 + 97] = black
        }
        assertArea(PageArea(0.08f, 0.10f, 0.92f, 0.90f), contentArea(pixels, 100, 100, minDarkShare = 0.02f))
    }

    @Test
    fun neverCutsMoreThanTheCapFromASide() {
        // One short line in the middle: without the cap this would zoom far up.
        val pixels = page { it.block(45, 49, 55, 51) }
        assertArea(PageArea(0.15f, 0.15f, 0.85f, 0.85f), contentArea(pixels, 100, 100))
    }

    @Test
    fun contentToTheEdgeKeepsTheEdge() {
        val pixels = page { it.block(0, 0, 100, 100) }
        assertEquals(PageArea.WHOLE, contentArea(pixels, 100, 100))
    }

    @Test
    fun greyBelowTheThresholdIsNotContent() {
        val light = 0xffd0d0d0.toInt() // A light scan background.
        val pixels = IntArray(100 * 100) { light }.also { it.block(10, 12, 90, 88) }
        assertArea(PageArea(0.08f, 0.10f, 0.92f, 0.90f), contentArea(pixels, 100, 100))
    }

    @Test
    fun thinStaffLinesShrunkToLightGreyStillCount() {
        // A scan's 1-px staff lines, shrunk for detection, end up light grey (218) on an off-white page (250).
        // A fixed threshold for "dark" missed them all and showed the page whole, margins included.
        val paper = 0xfffafafa.toInt()
        val line = 0xffdadada.toInt()
        val pixels = IntArray(100 * 100) { paper }
        for (y in listOf(14, 30, 46, 62, 78, 86)) for (x in 10 until 90) pixels[y * 100 + x] = line
        assertArea(PageArea(0.08f, 0.12f, 0.92f, 0.89f), contentArea(pixels, 100, 100))
    }

    @Test
    fun scanNoiseIsNotContent() {
        // Speckle of +-12 around the paper colour all over the page: no content anywhere.
        val random = java.util.Random(1)
        val pixels = IntArray(100 * 100) {
            val v = 238 + random.nextInt(25) - 12
            (0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
        assertEquals(PageArea.WHOLE, contentArea(pixels, 100, 100))
    }

    @Test
    fun theWholeScoreIsSampledFirstLastAndBetween() {
        assertEquals(listOf(0, 1, 2), samplePages(3))
        assertEquals(listOf(0, 3, 7, 11, 15, 19), samplePages(20))
        assertEquals(listOf(0), samplePages(1))
    }

    @Test
    fun oneCropHoldsEverySampledPage() {
        val a = PageArea(0.10f, 0.12f, 0.88f, 0.85f)
        val b = PageArea(0.15f, 0.05f, 0.90f, 0.80f)
        assertEquals(PageArea(0.10f, 0.05f, 0.90f, 0.85f), a.union(b))
    }

    @Test
    fun anAreaFromTheOtherProcessIsChecked() {
        assertEquals(PageArea(0.1f, 0.1f, 0.9f, 0.9f), checkArea(floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f)))
        for (bad in listOf(
            null,
            floatArrayOf(0.1f, 0.1f, 0.9f),
            floatArrayOf(0.9f, 0.1f, 0.1f, 0.9f), // inside out
            floatArrayOf(0.5f, 0.5f, 0.52f, 0.9f), // a sliver: would zoom absurdly
            floatArrayOf(-1f, 0f, 1f, 1f),
            floatArrayOf(0f, 0f, Float.NaN, 1f),
        )) assertEquals(PageArea.WHOLE, checkArea(bad))
    }
}
