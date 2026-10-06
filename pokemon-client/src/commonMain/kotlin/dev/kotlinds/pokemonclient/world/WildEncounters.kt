package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.state.SpeciesId

/**
 * The wild Pokémon of a zone, as its game's encounter tables say ([WorldSource.wildEncounters]): one [EncounterGroup]
 * per way of meeting them, with each species' chance and levels. Common to every game; each game decodes its own
 * tables into it (HeartGold / SoulSilver: `EncounterData`, games/hgss/HgssEncounters; Platinum: `WildEncounters`,
 * games/platinum/PlatinumEncounters; the shared Gen 4 rules: games/gen4/Gen4Encounters).
 */
data class WildEncounters(val zoneId: Int, val groups: List<EncounterGroup>)

/**
 * The Pokémon met one way ([method]), at [time] of day when the table depends on it (null: any time), or only under
 * [condition] (a swarm, the radio...): then [slots] are the species that replace some of the usual ones, with the
 * chance of the slots they take.
 */
data class EncounterGroup(
    val method: EncounterMethod,
    /**
     * The table's rate (0..100), what the game rolls a check against (with the movement's own roll: see
     * [WorldSource.encounterChance]); for rods, the bite rate. Null for a [condition]'s replacements.
     */
    val rate: Int?,
    val slots: List<EncounterSlot>,
    val time: TimeOfDay? = null,
    val condition: EncounterCondition? = null,
)

/** One species of a group: the share of the group's encounters it takes ([chance], percent) and its [levels]. */
data class EncounterSlot(val species: SpeciesId, val chance: Int, val levels: IntRange)

/** How the player meets the Pokémon of a group. */
enum class EncounterMethod {
    /** Walking in tall grass or on a cave floor (any land tile with encounters). */
    WALK,
    SURF,
    ROCK_SMASH,
    OLD_ROD,
    GOOD_ROD,
    SUPER_ROD,
}

/**
 * The time of day a table depends on (the games' clock: the land tables of HeartGold / SoulSilver and Platinum;
 * Platinum's twilight counts as [DAY], its late night as [NIGHT], as its tables do).
 */
enum class TimeOfDay { MORNING, DAY, NIGHT }

/** What makes a group's species replace some of the usual ones. */
enum class EncounterCondition {
    /** A swarm (mass outbreak) on this map, announced on the phone or the radio. */
    SWARM,

    /** The Pokégear radio playing the Hoenn Sound. */
    HOENN_SOUND,

    /** The Pokégear radio playing the Sinnoh Sound. */
    SINNOH_SOUND,

    /** At night (the rods' tables). */
    NIGHT,

    /** In a patch of grass shaken by the Poké Radar (Platinum). */
    POKE_RADAR,

    /** With Pokémon Ruby in the DS's GBA slot, once the National Pokédex is obtained (Platinum's dual-slot mode). */
    DUAL_SLOT_RUBY,

    /** With Pokémon Sapphire in the GBA slot (see [DUAL_SLOT_RUBY]). */
    DUAL_SLOT_SAPPHIRE,

    /** With Pokémon Emerald in the GBA slot (see [DUAL_SLOT_RUBY]). */
    DUAL_SLOT_EMERALD,

    /** With Pokémon FireRed in the GBA slot (see [DUAL_SLOT_RUBY]). */
    DUAL_SLOT_FIRERED,

    /** With Pokémon LeafGreen in the GBA slot (see [DUAL_SLOT_RUBY]). */
    DUAL_SLOT_LEAFGREEN,
}
