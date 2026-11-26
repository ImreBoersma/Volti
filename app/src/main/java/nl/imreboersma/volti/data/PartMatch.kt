package nl.imreboersma.volti.data

/**
 * Instruments by the names they go by in Dutch and English parts, abbreviations included. A file's words are matched
 * whole, so "flugelhorn" is not a horn and "clarinetto" is not "clarinet" unless listed.
 */
private val FAMILIES: Map<String, Set<String>> = mapOf(
    "horn" to setOf("horn", "hoorn", "hn", "cor", "corno", "corni", "horns", "hoorns"),
    "clarinet" to setOf("clarinet", "klarinet", "cl", "clar", "clarinetto", "clarinets", "klarinetten"),
    "trumpet" to setOf("trumpet", "trompet", "tp", "tpt", "trp", "tromba", "trumpets", "trompetten"),
    "cornet" to setOf("cornet", "kornet", "cornets"),
    "flugelhorn" to setOf("flugelhorn", "bugel", "flh", "flugel"),
    "trombone" to setOf("trombone", "tb", "tbn", "trb", "trombones"),
    "euphonium" to setOf("euphonium", "euph", "euphoniums"),
    "tuba" to setOf("tuba", "tubas"),
    "flute" to setOf("flute", "fluit", "fl", "flauto", "flutes", "fluiten"),
    "piccolo" to setOf("piccolo", "picc", "pic"),
    "oboe" to setOf("oboe", "hobo", "ob", "oboes"),
    "bassoon" to setOf("bassoon", "fagot", "fg", "bsn", "fagotto"),
    "saxophone" to setOf("saxophone", "saxofoon", "sax", "saxes", "altsax", "tenorsax", "baritonsax", "sopraansax"),
    "percussion" to setOf("percussion", "slagwerk", "perc", "drums", "drumstel", "timpani", "pauken", "mallets"),
    "violin" to setOf("violin", "viool", "vl", "vln", "violino"),
    "viola" to setOf("viola", "altviool", "vla"),
    "cello" to setOf("cello", "violoncello", "vc"),
    "double bass" to setOf("contrabas", "kb", "cb"),
)

/** Voices within a family: when both the instrument and a file name one, they must be the same. */
private val VOICES: Map<String, Set<String>> = mapOf(
    "soprano" to setOf("soprano", "sopraan", "sopraansax"),
    "alto" to setOf("alto", "alt", "altsax"),
    "tenor" to setOf("tenor", "tenorsax"),
    "baritone" to setOf("baritone", "bariton", "bari", "baritonsax"),
    "bass" to setOf("bass", "bas", "basso"),
)

/** Words between an instrument and its part number that say nothing about the part: "Horn in F 1", "Hoorn F 1_2". */
private val FILLER = setOf("in", "f", "bb", "b", "eb", "es", "bes", "c", "d", "a", "g", "e", "part", "partij", "stem", "nr", "no")

/** Keys, as written next to an instrument: "Bb clarinet", "Es klarinet", "Hoorn in F". */
private val KEYS: Map<String, String> = mapOf("bb" to "bb", "bes" to "bb", "b" to "bb", "eb" to "eb", "es" to "eb", "f" to "f", "c" to "c")

/** Part numbers in Roman numerals, as orchestral sets write them: "Corno II". */
private val ROMAN = mapOf("i" to 1, "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6, "vii" to 7, "viii" to 8)

/** What follows a number in front of an instrument: "1e hoorn", "2nd horn", "3rd", "4th", "1ste", "2de". */
private val ORDINAL = setOf("e", "ste", "de", "st", "nd", "rd", "th")

/** Words, made searchable, with letters and digits apart: "hoorn13" is "hoorn", "13"; "1-2" is "1", "-", "2". */
internal fun partWords(text: String): List<String> =
    Regex("[\\p{L}]+|[0-9]+|-").findAll(searchable(text)).map { it.value }.toList()

private fun familyOf(word: String) = FAMILIES.entries.firstOrNull { word in it.value }?.key
private fun voiceOf(word: String) = VOICES.entries.firstOrNull { word in it.value }?.key

/**
 * The words that say something about the instrument at [at]: itself, the word before it, and the word after it
 * (past an "in"). Dashes don't count as words here: "F-hoorn" is "f", "hoorn". Keys and voices are read only from
 * these, so a title's "Alto" doesn't make a tenor part an alto one.
 */
private fun around(words: List<String>, at: Int): List<String> {
    val before = words.subList(0, at).lastOrNull { it != "-" }
    val after = words.drop(at + 1).filter { it != "-" }.let { rest -> if (rest.firstOrNull() == "in") rest.drop(1) else rest }.firstOrNull()
    return listOfNotNull(words[at], before, after)
}

/**
 * The part numbers of the instrument at [at]. After it: "1" is part 1; "13" is parts 1 and 3 (two-digit numbers of
 * parts 1-4 are combined parts, as in "hoorn13"); "1-3" is 1 to 3; "1 2" and "1_2" are 1 and 2; "II" is 2. Else in
 * front of it, as an ordinal: "2e hoorn", "1st horn". Empty: no number, a part for all.
 */
private fun partsOf(words: List<String>, at: Int): Set<Int> {
    val parts = mutableSetOf<Int>()
    var i = at + 1
    while (i < words.size && words[i] in FILLER) i++
    var previous: Int? = null
    var range = false
    while (i < words.size) {
        val word = words[i]
        val numbers = when {
            word == "-" -> {
                range = previous != null
                i++
                continue
            }
            word.all(Char::isDigit) -> {
                val digits = word.map { it.digitToInt() }
                if (word.length > 1 && digits.all { it in 1..4 }) digits else listOf(word.toInt())
            }
            word in ROMAN -> listOf(ROMAN.getValue(word))
            else -> break
        }
        if (range && previous != null) (previous..numbers.first()).forEach { parts += it }
        parts += numbers
        previous = numbers.last()
        range = false
        i++
    }
    if (parts.isEmpty() && at >= 2 && words[at - 1] in ORDINAL && words[at - 2].all(Char::isDigit)) {
        parts += words[at - 2].toInt()
    }
    return parts
}

/** What I play, read from what I typed: "F Horn 1" is horn in F, part 1; "Alto sax" is saxophone, alto, any part. */
data class Instrument(val family: String?, val voice: String?, val key: String?, val parts: Set<Int>, val words: List<String>)

fun parseInstrument(text: String): Instrument {
    val words = partWords(text)
    val at = words.indexOfFirst { familyOf(it) != null }
    val near = if (at >= 0) around(words, at) else words
    return Instrument(
        family = words.getOrNull(at)?.let(::familyOf),
        voice = near.firstNotNullOfOrNull(::voiceOf),
        key = if (at >= 0) near.firstNotNullOfOrNull { KEYS[it] } else null,
        parts = if (at >= 0) partsOf(words, at) else words.filter { it.all(Char::isDigit) }.map(String::toInt).toSet(),
        words = words.filter { w -> !w.all(Char::isDigit) && w != "-" && w !in FILLER },
    )
}

/**
 * Whether a file looks like my part: my instrument is named in its name or a folder on the way; next to it, no
 * other key than mine and no other voice than mine (none if I named none: a bass clarinet is not my clarinet); and
 * its part numbers, if it has any, include mine. A suggestion only: I decide.
 */
fun looksLikeMyPart(me: Instrument, name: String, folders: List<String>): Boolean {
    if (me.family == null && me.words.isEmpty()) return false
    // The file name first: its part number counts; a folder only says the instrument.
    for (text in listOf(name.removeSuffix(".pdf").removeSuffix(".PDF")) + folders.reversed()) {
        val words = partWords(text)
        val at = words.indexOfFirst { word ->
            if (me.family != null) familyOf(word) == me.family else word in me.words
        }
        if (at < 0) continue
        val near = around(words, at)
        val voice = near.firstNotNullOfOrNull(::voiceOf)
        if (voice != me.voice && voice != null) return false
        val key = near.firstNotNullOfOrNull { KEYS[it] }
        if (me.key != null && key != null && key != me.key) return false
        val parts = partsOf(words, at)
        return me.parts.isEmpty() || parts.isEmpty() || parts.any { it in me.parts }
    }
    return false
}
