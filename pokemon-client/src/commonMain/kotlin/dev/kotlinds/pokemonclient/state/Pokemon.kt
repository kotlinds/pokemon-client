package dev.kotlinds.pokemonclient.state

/** One Pokémon of the party (or of a PC box). */
data class PartyMon(
    val id: MonId,
    /** Position in the saved party order (0-5); in battle the screen order may differ, see [BattleState.partyOrder]. */
    val slot: Int,
    val species: Named<SpeciesId>,
    /** Nickname when it differs from the species name. */
    val nickname: String?,
    val level: Int,
    val hp: Int,
    val maxHp: Int,
    val status: MajorStatus?,
    val types: List<String>,
    val heldItem: Named<ItemId>?,
    val ability: String?,
    val moves: List<KnownMove>,
    val stats: Map<Stat, Int>,
    val exp: Long,
    /** Experience still needed to reach the next level (0 at level 100), null until the growth rates are read from the ROM. */
    val expToNextLevel: Long?,
    val isEgg: Boolean,
) {
    val fainted: Boolean get() = hp == 0 && !isEgg

    /** The name shown in game: the nickname if any, else the species (or "Egg"). */
    val displayName: String get() = if (isEgg) "Egg" else nickname ?: species.name
}

/**
 * A move a Pokémon knows, with its remaining PP. In battle, also its battle data (from the game's move table):
 * [power] (0: no fixed power), [accuracy] in percent (0: never misses), [category] and [priority] (0 for most moves).
 */
data class KnownMove(
    val move: Named<MoveId>,
    val pp: Int,
    val maxPp: Int,
    val type: String? = null,
    val power: Int? = null,
    val accuracy: Int? = null,
    val category: dev.kotlinds.pokemonclient.data.MoveCategory? = null,
    val priority: Int? = null,
)

/** The persistent ("major") status conditions, shown next to a Pokémon. */
sealed interface MajorStatus {
    data class Asleep(val turns: Int) : MajorStatus
    data object Poisoned : MajorStatus
    data class BadlyPoisoned(val counter: Int) : MajorStatus
    data object Burned : MajorStatus
    data object Frozen : MajorStatus
    data object Paralyzed : MajorStatus
}

/** The six stats. */
enum class Stat { HP, ATTACK, DEFENSE, SPEED, SP_ATTACK, SP_DEFENSE }

/**
 * The player's Pokédex as the save keeps it: the species seen and caught (by national id). What a player knows of the
 * wild Pokémon around: at the Pokédex knowledge level, `lookup encounters` only names the species already seen.
 */
data class PokedexState(val seen: SpeciesSet, val caught: SpeciesSet)

/**
 * An immutable set of species kept as a bitfield, the way the games' saves keep their Pokédex: species `n` (national
 * id, 1 to [count]) is bit `(n - 1) % 8` of byte `(n - 1) / 8` of [bits]. Built from one bulk read, no allocation per
 * species: cheap enough to be read on every state decode. A [Set] of [SpeciesId] for everything else.
 */
class SpeciesSet(bits: ByteArray, private val count: Int) : AbstractSet<SpeciesId>() {
    /** A copy of exactly [count] bits (those past it cleared): the set never changes once built, equal sets have equal bits. */
    private val bits: ByteArray = ByteArray((count + 7) / 8) { i ->
        val b = bits.getOrElse(i) { 0 }.toInt()
        val kept = count - 8 * i
        (if (kept >= 8) b else b and ((1 shl kept) - 1)).toByte()
    }

    override fun contains(element: SpeciesId): Boolean {
        val i = element.value - 1
        if (i < 0 || i >= count) return false
        return (bits[i / 8].toInt() shr (i % 8)) and 1 == 1
    }

    override val size: Int by lazy { (1..count).count { contains(SpeciesId(it)) } }

    override fun iterator(): Iterator<SpeciesId> = (1..count).asSequence().map(::SpeciesId).filter(::contains).iterator()

    override fun equals(other: Any?): Boolean =
        if (other is SpeciesSet) count == other.count && bits.contentEquals(other.bits) else super.equals(other)

    override fun hashCode(): Int = super.hashCode()

    companion object {
        /** The set of [species] (ids 1 to [count]). */
        fun of(count: Int, species: Iterable<SpeciesId>): SpeciesSet {
            val bits = ByteArray((count + 7) / 8)
            for (s in species) if (s.value in 1..count) bits[(s.value - 1) / 8] = (bits[(s.value - 1) / 8].toInt() or (1 shl ((s.value - 1) % 8))).toByte()
            return SpeciesSet(bits, count)
        }
    }
}
