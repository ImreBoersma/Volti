package nl.imreboersma.volti.data

import kotlin.math.sign
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** The same check as the unit test, with Android's ICU-backed Collator, which ignores zero-width and bidi marks. */
@RunWith(AndroidJUnit4::class)
class NaturalOrderDeviceTest {
    @Test
    fun consistentOnHostileNamesWithIcu() = checkNaturalOrderIsConsistent(NaturalOrder(), seed = 7)
}

/** Copy of the check in NaturalOrderFuzzTest (unit tests). Keep them the same. */
private fun checkNaturalOrderIsConsistent(order: NaturalOrder, seed: Long = 1) {
    val alphabet = listOf("0", "1", "5", "9", "07", "a", "B", "é", " ", ".", "５", "٣", "５", "٣", "９1", "​", "‎", "Ⅻ", "½", "-")
    val random = kotlin.random.Random(seed)
    fun name() = (1..random.nextInt(1, 6)).joinToString("") { alphabet.random(random) }
    repeat(200) {
        val names = List(64) { name() }
        names.sortedWith(order) // TimSort throws on an inconsistent comparator past 32 elements.
        // Every triple of a smaller set: random sampling misses the rare cycles.
        val few = names.take(24)
        for (a in few) for (b in few) {
            val ab = order.compare(a, b).sign
            check(order.compare(b, a).sign == -ab) { "not antisymmetric: '$a' '$b'" }
            if (ab > 0) continue
            for (c in few) {
                if (order.compare(b, c) <= 0) check(order.compare(a, c) <= 0) { "cycle: '$a' <= '$b' <= '$c' but '$a' > '$c'" }
            }
        }
    }
}
