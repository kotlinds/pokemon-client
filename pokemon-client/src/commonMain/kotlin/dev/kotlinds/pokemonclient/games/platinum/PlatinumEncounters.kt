package dev.kotlinds.pokemonclient.games.platinum

import dev.kotlinds.pokemonclient.games.gen4.Gen4Encounters
import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.world.EncounterCondition
import dev.kotlinds.pokemonclient.world.EncounterConditions
import dev.kotlinds.pokemonclient.world.EncounterMethod
import dev.kotlinds.pokemonclient.world.TimeOfDay
import dev.kotlinds.pokemonclient.world.WildEncounters

/**
 * The wild encounter table of a Platinum zone (`WildEncounters`, include/overlay006/wild_encounters.h, 0x1A8 bytes
 * per member of `fielddata/encountdata/pl_enc_data.narc`, indexed by the map header's `wildEncountersArchiveID`): the
 * Diamond / Pearl / Platinum format, where the 12 land slots hold the morning's species and a few slots are replaced
 * by the day's / night's, a swarm, the Poké Radar or a GBA cartridge.
 */
data class PlatinumEncounterTable(
    /** `grassEncounters.encounterRate` (0..100): the land rate (tall grass, cave floors). */
    val landRate: Int,
    /** `grassEncounters.encounters`: the 12 land slots (one level each), the morning's species. */
    val land: List<Slot>,
    /** `swarmEncounters`: the species of land slots 0-1 during the zone's swarm. */
    val swarm: List<Int> = emptyList(),
    /** `dayEncounters` / `nightEncounters`: the species of land slots 2-3 by day (and twilight) / by night. */
    val day: List<Int> = emptyList(),
    val night: List<Int> = emptyList(),
    /** `radarEncounters`: the species of land slots 4, 5, 10, 11 in a Poké Radar patch. */
    val radar: List<Int> = emptyList(),
    /** `dualSlot*Encounters`: the species of land slots 8-9 with each GBA game inserted (Ruby, Sapphire, Emerald, FireRed, LeafGreen). */
    val dualSlot: Map<EncounterCondition, List<Int>> = emptyMap(),
    /** `surfEncounters`, `oldRodEncounters`, `goodRodEncounters`, `superRodEncounters`: rate and 5 slots each. */
    val surf: Water = Water.NONE,
    val oldRod: Water = Water.NONE,
    val goodRod: Water = Water.NONE,
    val superRod: Water = Water.NONE,
) {
    /** One slot: a species and its level range (a land slot has one level). */
    data class Slot(val species: Int, val levels: IntRange)

    /** A `WaterEncounters`: its rate (0..100) and its 5 slots. */
    data class Water(val rate: Int, val slots: List<Slot>) {
        companion object {
            val NONE = Water(0, emptyList())
        }
    }

    companion object {
        /** `sizeof(WildEncounters)`. */
        const val SIZE = 0x1A8

        private const val LAND_SLOTS = 0x04
        private const val SWARM = 0x64
        private const val DAY = 0x6C
        private const val NIGHT = 0x74
        private const val RADAR = 0x7C
        private const val DUAL_SLOT = 0xA4
        private const val SURF = 0xCC
        private const val OLD_ROD = 0x124
        private const val GOOD_ROD = 0x150
        private const val SUPER_ROD = 0x17C

        /** The dual-slot tables in the struct's order (`dualSlotRubyEncounters` first). */
        private val DUAL_SLOT_ORDER = listOf(
            EncounterCondition.DUAL_SLOT_RUBY, EncounterCondition.DUAL_SLOT_SAPPHIRE, EncounterCondition.DUAL_SLOT_EMERALD,
            EncounterCondition.DUAL_SLOT_FIRERED, EncounterCondition.DUAL_SLOT_LEAFGREEN,
        )

        /** Decodes one member of the encounter NARC, or null when it is too short. */
        fun parse(bytes: ByteArray): PlatinumEncounterTable? {
            if (bytes.size < SIZE) return null
            fun u8(o: Int) = Gen4RomBytes.u8(bytes, o)
            // The species are `int`s; the game's species ids fit in their low 16 bits.
            fun species(o: Int) = Gen4RomBytes.u16(bytes, o)
            fun ints(at: Int, count: Int) = List(count) { species(at + 4 * it) }
            // WaterEncounter: s8 maxLevel, s8 minLevel, 2 bytes of padding, int species (the roll swaps reversed levels).
            fun water(at: Int) = Water(u8(at), List(Gen4Encounters.SURF_SLOT_CHANCES.size) { i ->
                val o = at + 4 + 8 * i
                val a = u8(o)
                val b = u8(o + 1)
                Slot(species(o + 4), minOf(a, b)..maxOf(a, b))
            })
            return PlatinumEncounterTable(
                landRate = u8(0),
                // GrassEncounter: s8 level, 3 bytes of padding, int species.
                land = List(Gen4Encounters.LAND_SLOT_CHANCES.size) { i -> u8(LAND_SLOTS + 8 * i).let { Slot(species(LAND_SLOTS + 8 * i + 4), it..it) } },
                swarm = ints(SWARM, 2),
                day = ints(DAY, 2),
                night = ints(NIGHT, 2),
                radar = ints(RADAR, 4),
                dualSlot = DUAL_SLOT_ORDER.withIndex().associate { (i, condition) -> condition to ints(DUAL_SLOT + 8 * i, 2) },
                surf = water(SURF),
                oldRod = water(OLD_ROD),
                goodRod = water(GOOD_ROD),
                superRod = water(SUPER_ROD),
            )
        }
    }
}

/**
 * Platinum's wild encounter roll (pokeplatinum src/overlay006/wild_encounters.c, `WildEncounters_TryWildEncounter`),
 * in the common model, with the Gen 4 rules both engines share ([Gen4Encounters]):
 * 1. the table's rate (land or surfing; 0: never), times 2/3 when the lead holds a Cleanse Tag or Pure Incense
 *    (`ModifyEncounterRateWithHeldItem`);
 * 2. two rolls must pass (`ShouldGetRandomEncounter`): one against a flat 40 (+30 on the bicycle: walking and running
 *    roll the same, unlike HeartGold / SoulSilver), then one against the rate;
 * 3. a Repel keeps away the wild Pokémon of a lower level than the first Pokémon able to fight.
 *
 * Left out: the grace period after a battle or a map change (`GracePeriodStepsUsed`: 5 % for the first checks), the
 * lead's abilities, the Flutes, very tall grass (+30, not told from tall grass), the weather, the roamers, the Great
 * Marsh's daily Pokémon and the Trophy Garden's (other files), Feebas's tiles.
 */
object PlatinumEncounters {

    /** `GetRodEncounterSlot`: the Old Rod's 5 slots' percent chances. */
    val OLD_ROD_SLOT_CHANCES = listOf(60, 30, 5, 4, 1)

    /** `GetRodEncounterSlot`: the Good and Super Rods' 5 slots' percent chances. */
    val GOOD_ROD_SLOT_CHANCES = listOf(40, 40, 15, 4, 1)

    /**
     * [table] in the common model ([WildEncounters]) for zone [zoneId], as the game builds its land slots: the
     * morning's species, slots 2-3 replaced by day (and twilight) and by night (`WildEncounters_ReplaceTimedEncounters`);
     * then the replacements under a condition: a swarm (slots 0-1), the Poké Radar (slots 4, 5, 10, 11), a GBA game in
     * the second slot (slots 8-9). Surfing and the three rods have one table each.
     */
    fun wild(zoneId: Int, table: PlatinumEncounterTable): WildEncounters {
        val builder = Gen4Encounters.GroupsBuilder()
        val landSpecies = table.land.map { it.species }
        val landLevels = table.land.map { it.levels }
        val land = Gen4Encounters.LAND_SLOT_CHANCES
        fun timed(species: List<Int>) = landSpecies.mapIndexed { i, s -> if (i == 2 || i == 3) species.getOrElse(i - 2) { s } else s }
        for ((time, species) in listOf(TimeOfDay.MORNING to landSpecies, TimeOfDay.DAY to timed(table.day), TimeOfDay.NIGHT to timed(table.night))) {
            builder.add(EncounterMethod.WALK, table.landRate, species, landLevels, land, time = time)
        }
        val waters = listOf(
            Triple(EncounterMethod.SURF, table.surf, Gen4Encounters.SURF_SLOT_CHANCES),
            Triple(EncounterMethod.OLD_ROD, table.oldRod, OLD_ROD_SLOT_CHANCES),
            Triple(EncounterMethod.GOOD_ROD, table.goodRod, GOOD_ROD_SLOT_CHANCES),
            Triple(EncounterMethod.SUPER_ROD, table.superRod, GOOD_ROD_SLOT_CHANCES),
        )
        for ((method, water, chances) in waters) builder.add(method, water.rate, water.slots.map { it.species }, water.slots.map { it.levels }, chances)
        if (table.landRate > 0) {
            fun replaced(slots: List<Int>, species: List<Int>, condition: EncounterCondition) =
                builder.add(EncounterMethod.WALK, null, Gen4Encounters.replacing(land.size, slots.zip(species).toMap()), landLevels, land, condition = condition)
            replaced(listOf(0, 1), table.swarm, EncounterCondition.SWARM)
            replaced(listOf(4, 5, 10, 11), table.radar, EncounterCondition.POKE_RADAR)
            for ((condition, species) in table.dualSlot) replaced(listOf(8, 9), species, condition)
        }
        return WildEncounters(zoneId, builder.result)
    }

    /** The chance (0..1) that one check on [table]'s land tiles ([water] false) or water starts a battle. */
    fun chance(table: PlatinumEncounterTable, water: Boolean, conditions: EncounterConditions): Double {
        val movement = if (water) MovementMode.SURF else conditions.landMovement
        val rate = if (water) table.surf.rate else table.landRate
        return Gen4Encounters.chance(rate, movementRate(movement), conditions) { level ->
            if (water) Gen4Encounters.shareNotRepelled(table.surf.slots.map { it.levels }, Gen4Encounters.SURF_SLOT_CHANCES, level)
            else Gen4Encounters.shareNotRepelled(table.land.map { it.levels }, Gen4Encounters.LAND_SLOT_CHANCES, level)
        }
    }

    /**
     * `ShouldGetRandomEncounter`'s first threshold (`flatEncounterRate`): 40 whatever the player does on foot or on
     * the water, plus 30 cycling (`PLAYER_STATE_CYCLING`).
     */
    fun movementRate(movement: MovementMode): Int = when (movement) {
        MovementMode.WALK, MovementMode.RUN, MovementMode.SURF -> 40
        MovementMode.BIKE -> 70
    }
}
