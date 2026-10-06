package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.world.EncounterCondition
import dev.kotlinds.pokemonclient.world.EncounterConditions
import dev.kotlinds.pokemonclient.world.EncounterGroup
import dev.kotlinds.pokemonclient.world.EncounterMethod
import dev.kotlinds.pokemonclient.world.EncounterSlot
import dev.kotlinds.pokemonclient.world.TimeOfDay

/**
 * What the wild encounter roll of the Gen 4 engine shares between Diamond / Pearl / Platinum
 * (pokeplatinum src/overlay006/wild_encounters.c) and HeartGold / SoulSilver (pokeheartgold src/field/encounter_check.c):
 * the land and surfing slot chances, the lead's held items that cut the rate, the Repel rule, and how a table's slots
 * become the common model's groups ([EncounterGroup]). Each game keeps its own table format and its own first roll
 * (the movement's: [dev.kotlinds.pokemonclient.games.hgss.HgssEncounters.movementRate],
 * [dev.kotlinds.pokemonclient.games.platinum.PlatinumEncounters.movementRate]).
 */
object Gen4Encounters {

    /** The 12 land slots' percent chances (Platinum `GetGroundEncounterSlot`, HGSS `EncounterSlot_WildMonSlotRoll_Land`). */
    val LAND_SLOT_CHANCES = listOf(20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1)

    /** The 5 surfing slots' percent chances (Platinum `GetWaterEncounterSlot`, HGSS `EncounterSlot_WildMonSlotRoll_Surfing`). */
    val SURF_SLOT_CHANCES = listOf(60, 30, 5, 4, 1)

    /** `ITEM_CLEANSE_TAG`, `ITEM_PURE_INCENSE` (the same ids in both decomps): rate × 2/3 when the lead holds one. */
    private val RATE_REDUCING_ITEMS = setOf(224, 320)

    /**
     * The chance (0..1) that one encounter check starts a battle: a first roll against [movementRate] (percent, the
     * game's own rule for the player's movement), then one against the table's [rate] (0..100; 0: never), cut to 2/3
     * when the lead holds a Cleanse Tag or Pure Incense; with a Repel ([EncounterConditions.repelLevel]), times the
     * share of the encounters it lets through ([notRepelled], given that level).
     */
    fun chance(rate: Int, movementRate: Int, conditions: EncounterConditions, notRepelled: (Int) -> Double): Double {
        if (rate <= 0) return 0.0
        var r = minOf(rate, 100)
        if (conditions.leadItem in RATE_REDUCING_ITEMS) r = r * 2 / 3
        val roll = minOf(100, movementRate) / 100.0 * r / 100.0
        val repel = conditions.repelLevel ?: return roll
        return roll * notRepelled(repel)
    }

    /**
     * The share (0..1) of a table's encounters a Repel lets through when the first Pokémon able to fight is level
     * [level]: the game repels the wild Pokémon of a lower level (Platinum `RepelPreventsEncounter`, HGSS
     * `EncounterGen_DoesRepelSuppressEncounter`). Each slot ([levels], [chances] in percent) counts by the share of its
     * levels (rolled uniformly between min and max) at least [level].
     */
    fun shareNotRepelled(levels: List<IntRange>, chances: List<Int>, level: Int): Double =
        levels.zip(chances).sumOf { (range, chance) ->
            val through = range.count { it >= level }
            chance / 100.0 * through / (range.last - range.first + 1)
        }

    /**
     * Builds the groups of one zone's table in the common model, the way both games list their slots: [add] one way
     * of meeting Pokémon at a time; a species in several slots of a group is one [EncounterSlot] (chances added,
     * levels joined), species 0 is an empty slot, and a group without a rate (`<= 0`) or without any species is left
     * out. A [EncounterGroup.condition]'s replacements have no rate (null): pass only the slots they take, 0 elsewhere
     * ([replacing]).
     */
    class GroupsBuilder {
        private val groups = mutableListOf<EncounterGroup>()

        /** The groups added so far. */
        val result: List<EncounterGroup> get() = groups.toList()

        fun add(method: EncounterMethod, rate: Int?, species: List<Int>, levels: List<IntRange>, chances: List<Int>, time: TimeOfDay? = null, condition: EncounterCondition? = null) {
            if ((rate ?: 1) <= 0) return
            val slots = species.indices.filter { species[it] != 0 }.groupBy { species[it] }.map { (s, at) ->
                EncounterSlot(SpeciesId(s), at.sumOf { chances[it] }, at.minOf { levels[it].first }..at.maxOf { levels[it].last })
            }
            if (slots.isNotEmpty()) groups += EncounterGroup(method, rate, slots, time, condition)
        }
    }

    /** [count] slots where only those of [at] (slot → species) hold a species: what a condition replaces. */
    fun replacing(count: Int, at: Map<Int, Int>): List<Int> = List(count) { at[it] ?: 0 }
}
