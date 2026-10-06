package dev.kotlinds.pokemonclient.state

import kotlin.jvm.JvmInline

/**
 * Stable identity of one Pokémon, the same in the party, the PC boxes and battles, before and after a trade.
 *
 * Gen 4: the personality value (PID, drawn when the Pokémon is generated) plus the original trainer's full id
 * (trainer id + secret id, 32 bits). Two identical un-nicknamed Pokémon still have different ids, which the
 * name alone can't tell apart.
 */
data class MonId(val personality: Long, val originalTrainerId: Long) {
    /** Wire form used in agent APIs, e.g. `mon:a3f1c2d4.0e2117b3`. */
    override fun toString() = "mon:${personality.toHex8()}.${originalTrainerId.toHex8()}"

    companion object {
        /** Parses the wire form of [toString]; null when malformed. */
        fun parse(value: String): MonId? {
            val parts = value.removePrefix("mon:").split('.')
            if (parts.size != 2) return null
            val pid = parts[0].toLongOrNull(16) ?: return null
            val ot = parts[1].toLongOrNull(16) ?: return null
            return MonId(pid, ot)
        }
    }
}

/** A species, by its national dex number in the game's data (0 = none). */
@JvmInline
value class SpeciesId(val value: Int)

/** A move, by its id in the game's move table (0 = none). */
@JvmInline
value class MoveId(val value: Int)

/** An item, by its id in the game's item table (0 = none). */
@JvmInline
value class ItemId(val value: Int)

/** An ability, by its id in the game's ability table (0 = none). */
@JvmInline
value class AbilityId(val value: Int)

/** Something with an id and the name the game shows for it (names come from the game's own text). */
data class Named<T>(val id: T, val name: String)

internal fun Long.toHex8() = (this and 0xFFFFFFFFL).toString(16).padStart(8, '0')

/**
 * The form names are compared in, wherever an agent names something (an item, a move, a species, a map, a fly
 * destination...): lowercase letters and digits only, accents folded, so "Poké Ball" = "poke ball" = "POKE-BALL" and
 * "Célestia" = "Celestia". The game may be in French, German, Spanish or Italian, and agents often type names without
 * their accents: every name lookup goes through this one function so they all accept the same spellings.
 *
 * Common Kotlin has no Unicode decomposition (java.text.Normalizer is JVM only), hence the table of [ACCENT_FOLDS]:
 * the accented letters of the Latin alphabets the games are released in, plus the ligatures (œ, æ, ß) folded to their
 * letters.
 */
fun normalizeName(value: String): String = buildString(value.length) {
    for (c in value.lowercase()) {
        val folded = ACCENT_FOLDS[c]
        when {
            folded != null -> append(folded)
            c.isLetterOrDigit() -> append(c)
        }
    }
}

/** Accented letters (lowercase) → their plain letters, for [normalizeName]. */
private val ACCENT_FOLDS: Map<Char, String> = buildMap {
    fun fold(letters: String, plain: String) = letters.forEach { put(it, plain) }
    fold("àáâãäåā", "a")
    fold("çćč", "c")
    fold("èéêëēė", "e")
    fold("ìíîïī", "i")
    fold("ñń", "n")
    fold("òóôõöøō", "o")
    fold("ùúûüū", "u")
    fold("ýÿ", "y")
    fold("œ", "oe")
    fold("æ", "ae")
    fold("ß", "ss")
}
