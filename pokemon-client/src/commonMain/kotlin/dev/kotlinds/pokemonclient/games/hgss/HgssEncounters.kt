package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.world.EncounterConditions

/**
 * The wild encounter table of a zone (`EncounterData`, include/wild_encounter.h, 0xC4 bytes per member of
 * `fielddata/encountdata/[gs]_enc_data.narc`, indexed by the map header's `wildEncounterBank`): only what the step
 * encounter roll needs.
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
) {
    companion object {
        /** `sizeof(EncounterData)`. */
        const val SIZE = 0xC4

        private const val LAND_LEVELS = 0x08
        private const val SURF_SLOTS = 0x64

        /** Decodes one member of the encounter NARC, or null when it is too short. */
        fun parse(bytes: ByteArray): HgssEncounterTable? {
            if (bytes.size < SIZE) return null
            fun u8(o: Int) = Gen4RomBytes.u8(bytes, o)
            return HgssEncounterTable(
                landRate = u8(0),
                surfRate = u8(1),
                landLevels = List(HgssEncounters.LAND_SLOT_CHANCES.size) { u8(LAND_LEVELS + it) },
                // EncounterDataSlot: u8 level_min, u8 level_max, u16 species (the roll swaps them when reversed).
                surfLevels = List(HgssEncounters.SURF_SLOT_CHANCES.size) { i ->
                    val a = u8(SURF_SLOTS + 4 * i)
                    val b = u8(SURF_SLOTS + 4 * i + 1)
                    minOf(a, b)..maxOf(a, b)
                },
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

    /** `EncounterSlot_WildMonSlotRoll_Land`: percent chance of each of the 12 land slots. */
    val LAND_SLOT_CHANCES = listOf(20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1)

    /** `EncounterSlot_WildMonSlotRoll_Surfing`: percent chance of each of the 5 surfing slots. */
    val SURF_SLOT_CHANCES = listOf(60, 30, 5, 4, 1)

    /** `ITEM_CLEANSE_TAG`, `ITEM_PURE_INCENSE` (include/constants/items.h): rate × 2/3 when the lead holds one. */
    private val RATE_REDUCING_ITEMS = setOf(224, 320)

    /** The chance (0..1) that one check on [table]'s land tiles ([water] false) or water starts a battle. */
    fun chance(table: HgssEncounterTable, water: Boolean, conditions: EncounterConditions): Double {
        var rate = if (water) table.surfRate else table.landRate
        if (rate <= 0) return 0.0
        rate = minOf(rate, 100)
        if (conditions.leadItem in RATE_REDUCING_ITEMS) rate = rate * 2 / 3
        val movement = if (water) MovementMode.SURF else conditions.landMovement
        val roll = minOf(100, movementRate(movement)) / 100.0 * rate / 100.0
        val repel = conditions.repelLevel ?: return roll
        return roll * shareNotRepelled(table, water, repel)
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
        if (water) {
            table.surfLevels.zip(SURF_SLOT_CHANCES).sumOf { (levels, chance) ->
                val through = levels.count { it >= level }
                chance / 100.0 * through / (levels.last - levels.first + 1)
            }
        } else {
            table.landLevels.zip(LAND_SLOT_CHANCES).sumOf { (slotLevel, chance) -> if (slotLevel >= level) chance / 100.0 else 0.0 }
        }
}
