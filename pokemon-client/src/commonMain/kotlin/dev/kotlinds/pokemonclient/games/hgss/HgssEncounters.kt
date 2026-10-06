package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.games.gen4.Gen4Encounters
import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.world.EncounterCondition
import dev.kotlinds.pokemonclient.world.EncounterConditions
import dev.kotlinds.pokemonclient.world.EncounterMethod
import dev.kotlinds.pokemonclient.world.TimeOfDay
import dev.kotlinds.pokemonclient.world.WildEncounters

/**
 * The wild encounter table of a zone (`EncounterData`, include/wild_encounter.h, 0xC4 bytes per member of
 * `fielddata/encountdata/[gs]_enc_data.narc`, indexed by the map header's `wildEncounterBank`): the step encounter
 * roll's rates and levels, and the species of every slot (what `lookup encounters` tells, [HgssEncounters.wild]).
 */
data class HgssEncounterTable(
    /** `encounterRate_walking` (0..100): the land rate of the zone (tall grass, cave floors). */
    val landRate: Int,
    /** `encounterRate_surfing` (0..100). */
    val surfRate: Int,
    /** `landSlots.levels`: the level of each of the 12 land slots (shared by the times of day). */
    val landLevels: List<Int>,
    /** `surfSlots`: the level range (min to max) of each of the 5 surfing slots. */
    val surfLevels: List<IntRange>,
    /** `encounterRate_rockSmash`, `encounterRate_oldRod`, `_goodRod`, `_superRod`. */
    val rockSmashRate: Int = 0,
    val oldRodRate: Int = 0,
    val goodRodRate: Int = 0,
    val superRodRate: Int = 0,
    /** `landSlots.species_morn` / `_day` / `_nite`: the species of the 12 land slots at each time of day. */
    val landMorning: List<Int> = emptyList(),
    val landDay: List<Int> = emptyList(),
    val landNight: List<Int> = emptyList(),
    /** `surfSlots` / `rockSmashSlots` / `oldRodSlots` / `goodRodSlots` / `superRodSlots`: species and levels. */
    val surf: List<Slot> = emptyList(),
    val rockSmash: List<Slot> = emptyList(),
    val oldRod: List<Slot> = emptyList(),
    val goodRod: List<Slot> = emptyList(),
    val superRod: List<Slot> = emptyList(),
    /** `hoennSoundsSpecies` / `sinnohSoundsSpecies`: the radio's two species each (0: none). */
    val hoennSound: List<Int> = emptyList(),
    val sinnohSound: List<Int> = emptyList(),
    /** `landSwarm`, `surfSwarm`, `nightFish`, `fishSwarm` (0: none). */
    val landSwarm: Int = 0,
    val surfSwarm: Int = 0,
    val nightFish: Int = 0,
    val fishSwarm: Int = 0,
) {
    /** One `EncounterDataSlot`: a species and its level range. */
    data class Slot(val species: Int, val levels: IntRange)

    companion object {
        /** `sizeof(EncounterData)`. */
        const val SIZE = 0xC4

        private const val LAND_LEVELS = 0x08
        private const val LAND_MORNING = 0x14
        private const val LAND_DAY = 0x2C
        private const val LAND_NIGHT = 0x44
        private const val HOENN_SOUND = 0x5C
        private const val SINNOH_SOUND = 0x60
        private const val SURF_SLOTS = 0x64
        private const val ROCK_SMASH_SLOTS = 0x78
        private const val OLD_ROD_SLOTS = 0x80
        private const val GOOD_ROD_SLOTS = 0x94
        private const val SUPER_ROD_SLOTS = 0xA8
        private const val SWARMS = 0xBC

        /** Decodes one member of the encounter NARC, or null when it is too short. */
        fun parse(bytes: ByteArray): HgssEncounterTable? {
            if (bytes.size < SIZE) return null
            fun u8(o: Int) = Gen4RomBytes.u8(bytes, o)
            fun u16(o: Int) = Gen4RomBytes.u16(bytes, o)
            val land = HgssEncounters.LAND_SLOT_CHANCES.size
            // EncounterDataSlot: u8 level_min, u8 level_max, u16 species (the roll swaps the levels when reversed).
            fun slots(at: Int, count: Int) = List(count) { i ->
                val a = u8(at + 4 * i)
                val b = u8(at + 4 * i + 1)
                Slot(u16(at + 4 * i + 2), minOf(a, b)..maxOf(a, b))
            }
            val surf = slots(SURF_SLOTS, HgssEncounters.SURF_SLOT_CHANCES.size)
            return HgssEncounterTable(
                landRate = u8(0),
                surfRate = u8(1),
                landLevels = List(land) { u8(LAND_LEVELS + it) },
                surfLevels = surf.map { it.levels },
                rockSmashRate = u8(2),
                oldRodRate = u8(3),
                goodRodRate = u8(4),
                superRodRate = u8(5),
                landMorning = List(land) { u16(LAND_MORNING + 2 * it) },
                landDay = List(land) { u16(LAND_DAY + 2 * it) },
                landNight = List(land) { u16(LAND_NIGHT + 2 * it) },
                surf = surf,
                rockSmash = slots(ROCK_SMASH_SLOTS, HgssEncounters.ROCK_SMASH_SLOT_CHANCES.size),
                oldRod = slots(OLD_ROD_SLOTS, HgssEncounters.FISHING_SLOT_CHANCES.size),
                goodRod = slots(GOOD_ROD_SLOTS, HgssEncounters.FISHING_SLOT_CHANCES.size),
                superRod = slots(SUPER_ROD_SLOTS, HgssEncounters.FISHING_SLOT_CHANCES.size),
                hoennSound = List(2) { u16(HOENN_SOUND + 2 * it) },
                sinnohSound = List(2) { u16(SINNOH_SOUND + 2 * it) },
                landSwarm = u16(SWARMS),
                surfSwarm = u16(SWARMS + 2),
                nightFish = u16(SWARMS + 4),
                fishSwarm = u16(SWARMS + 6),
            )
        }
    }
}

/**
 * The chance that one step encounter check starts a wild battle in HeartGold / SoulSilver, with the game's rules
 * (src/field/encounter_check.c, `FieldSystem_PerformLandOrSurfEncounterCheck`):
 * 1. the zone's rate ([HgssEncounterTable.landRate] / [HgssEncounterTable.surfRate]; 0: never), times 2/3 when the
 *    first Pokémon of the party holds a Cleanse Tag or Pure Incense (`ApplyLeadMonHeldItemEffectToEncounterRate`);
 * 2. two rolls must pass (`FieldSystem_EncounterRateRoll`): one against a movement rate (40, minus 20 unless running,
 *    cycling or surfing, plus 30 on the bike), then one against the zone's rate;
 * 3. with a Repel at work, a wild Pokémon of a lower level than the first Pokémon able to fight doesn't appear
 *    (`EncounterGen_DoesRepelSuppressEncounter`): only the slots (`EncounterSlot_WildMonSlotRoll_Land` / `_Surfing`
 *    chances) and levels (uniform between min and max) at least that level count.
 *
 * Left out: the lead's abilities (Stench, Illuminate, Intimidate... read as names, not ids, by the library), the
 * Black / White Flutes, very tall grass (+40, the library doesn't tell it from tall grass), the Pokégear radio's march
 * and lullaby (±25), and the boost of turning back and forth in place (the routes don't do it).
 */
object HgssEncounters {

    /** `EncounterSlot_WildMonSlotRoll_Land`: percent chance of each of the 12 land slots (the Gen 4 engine's). */
    val LAND_SLOT_CHANCES = Gen4Encounters.LAND_SLOT_CHANCES

    /** `EncounterSlot_WildMonSlotRoll_Surfing`: percent chance of each of the 5 surfing slots (the Gen 4 engine's). */
    val SURF_SLOT_CHANCES = Gen4Encounters.SURF_SLOT_CHANCES

    /** `EncounterSlot_WildMonSlotRoll_RockSmash`: percent chance of each of the 2 rock slots. */
    val ROCK_SMASH_SLOT_CHANCES = listOf(80, 20)

    /** `EncounterSlot_WildMonSlotRoll_Fishing`: percent chance of each of the 5 slots of a rod. */
    val FISHING_SLOT_CHANCES = listOf(40, 30, 15, 10, 5)

    /**
     * [table] in the common model ([WildEncounters]) for zone [zoneId], as the game builds its slots
     * (src/field/encounter_check.c): the land slots of each time of day (`EncSlotArray_Init_Land`: morning 4:00-9:59,
     * day 10:00-19:59, night 20:00-3:59 by `GF_RTC_GetTimeOfDayByHour`), surfing, Rock Smash and the three rods; then
     * what replaces some slots under a condition: a swarm (`EncSlots_Update_LandSwarm`: land slots 0-1; surfing slot
     * 0; the rods' slots of `EncSlots_Update_FishingSwarm`), the radio's Hoenn / Sinnoh Sound (land slots 2-3 and
     * 4-5), the night's fish (`EncSlotArray_Update_NightFishing`: Good Rod slot 3, Super Rod slot 1). A species in
     * several slots of a group is one slot (chances added, levels joined). Groups without a rate or a species are
     * left out.
     */
    fun wild(zoneId: Int, table: HgssEncounterTable): WildEncounters {
        val builder = Gen4Encounters.GroupsBuilder()
        val landLevels = table.landLevels.map { it..it }
        for ((time, species) in listOf(TimeOfDay.MORNING to table.landMorning, TimeOfDay.DAY to table.landDay, TimeOfDay.NIGHT to table.landNight)) {
            builder.add(EncounterMethod.WALK, table.landRate, species, landLevels, LAND_SLOT_CHANCES, time = time)
        }
        val rods = listOf(
            Triple(EncounterMethod.OLD_ROD, table.oldRodRate, table.oldRod),
            Triple(EncounterMethod.GOOD_ROD, table.goodRodRate, table.goodRod),
            Triple(EncounterMethod.SUPER_ROD, table.superRodRate, table.superRod),
        )
        builder.add(EncounterMethod.SURF, table.surfRate, table.surf.map { it.species }, table.surf.map { it.levels }, SURF_SLOT_CHANCES)
        builder.add(EncounterMethod.ROCK_SMASH, table.rockSmashRate, table.rockSmash.map { it.species }, table.rockSmash.map { it.levels }, ROCK_SMASH_SLOT_CHANCES)
        for ((method, rate, slots) in rods) builder.add(method, rate, slots.map { it.species }, slots.map { it.levels }, FISHING_SLOT_CHANCES)
        // Replacements: only the slots they take (species 0 elsewhere), and only where the usual table exists.
        if (table.landRate > 0) {
            builder.add(EncounterMethod.WALK, null, Gen4Encounters.replacing(12, mapOf(0 to table.landSwarm, 1 to table.landSwarm)), landLevels, LAND_SLOT_CHANCES, condition = EncounterCondition.SWARM)
            val (h0, h1) = table.hoennSound
            builder.add(EncounterMethod.WALK, null, Gen4Encounters.replacing(12, mapOf(2 to h0, 3 to h0, 4 to h1, 5 to h1)), landLevels, LAND_SLOT_CHANCES, condition = EncounterCondition.HOENN_SOUND)
            val (s0, s1) = table.sinnohSound
            builder.add(EncounterMethod.WALK, null, Gen4Encounters.replacing(12, mapOf(2 to s0, 3 to s0, 4 to s1, 5 to s1)), landLevels, LAND_SLOT_CHANCES, condition = EncounterCondition.SINNOH_SOUND)
        }
        if (table.surfRate > 0) builder.add(EncounterMethod.SURF, null, Gen4Encounters.replacing(5, mapOf(0 to table.surfSwarm)), table.surf.map { it.levels }, SURF_SLOT_CHANCES, condition = EncounterCondition.SWARM)
        val swarmSlots = mapOf(EncounterMethod.OLD_ROD to listOf(2), EncounterMethod.GOOD_ROD to listOf(0, 2, 3), EncounterMethod.SUPER_ROD to listOf(0, 1, 2, 3, 4))
        val nightSlot = mapOf(EncounterMethod.GOOD_ROD to 3, EncounterMethod.SUPER_ROD to 1)
        for ((method, rate, slots) in rods) {
            if (rate <= 0) continue
            builder.add(method, null, Gen4Encounters.replacing(5, swarmSlots.getValue(method).associateWith { table.fishSwarm }), slots.map { it.levels }, FISHING_SLOT_CHANCES, condition = EncounterCondition.SWARM)
            nightSlot[method]?.let { builder.add(method, null, Gen4Encounters.replacing(5, mapOf(it to table.nightFish)), slots.map { s -> s.levels }, FISHING_SLOT_CHANCES, condition = EncounterCondition.NIGHT) }
        }
        return WildEncounters(zoneId, builder.result)
    }

    /** The chance (0..1) that one check on [table]'s land tiles ([water] false) or water starts a battle. */
    fun chance(table: HgssEncounterTable, water: Boolean, conditions: EncounterConditions): Double {
        val movement = if (water) MovementMode.SURF else conditions.landMovement
        return Gen4Encounters.chance(if (water) table.surfRate else table.landRate, movementRate(movement), conditions) { shareNotRepelled(table, water, it) }
    }

    /**
     * `FieldSystem_EncounterRateRoll`'s first threshold: 40, minus 20 unless the player runs, cycles or surfs
     * (`sub_0205DE98`: the player's last movement is a run, `MOVEMENT_ACTION` 88-91), plus 30 on the bike.
     */
    fun movementRate(movement: MovementMode): Int = when (movement) {
        MovementMode.WALK -> 20
        MovementMode.RUN, MovementMode.SURF -> 40
        MovementMode.BIKE -> 70
    }

    /** The share (0..1) of [table]'s encounters a Repel lets through when the first fit Pokémon is level [level]. */
    fun shareNotRepelled(table: HgssEncounterTable, water: Boolean, level: Int): Double =
        if (water) Gen4Encounters.shareNotRepelled(table.surfLevels, SURF_SLOT_CHANCES, level)
        else Gen4Encounters.shareNotRepelled(table.landLevels.map { it..it }, LAND_SLOT_CHANCES, level)
}
