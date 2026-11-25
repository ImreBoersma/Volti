package nl.imreboersma.volti.data

import java.text.Collator
import java.util.Locale
import kotlin.math.sign
import org.junit.Test

/** Random names with hostile characters: the order must stay consistent, or sorting a folder throws. */
class NaturalOrderFuzzTest {
    @Test
    fun consistentOnHostileNames() = checkNaturalOrderIsConsistent(NaturalOrder())

    @Test
    fun consistentWithDutchCollation() = checkNaturalOrderIsConsistent(NaturalOrder(Collator.getInstance(Locale("nl", "NL"))), seed = 3)

    @Test
    fun noCycleWithNonAsciiDigits() {
        // Found by fuzzing the first version: a fullwidth and an Arabic-Indic digit made these a cycle.
        val order = NaturalOrder(Collator.getInstance(Locale("nl", "NL")))
        val (a, b, c) = listOf("٣ ５", " ５91", "５a")
        val ab = order.compare(a, b)
        val bc = order.compare(b, c)
        if (ab <= 0 && bc <= 0) check(order.compare(a, c) <= 0) { "cycle: '$a' <= '$b' <= '$c' but '$a' > '$c'" }
        if (ab >= 0 && bc >= 0) check(order.compare(a, c) >= 0) { "cycle: '$a' >= '$b' >= '$c' but '$a' < '$c'" }
    }
}

/** NaturalOrderDeviceTest has a copy, run with Android's ICU Collator. Keep them the same. */
internal fun checkNaturalOrderIsConsistent(order: NaturalOrder, seed: Long = 1) {
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
