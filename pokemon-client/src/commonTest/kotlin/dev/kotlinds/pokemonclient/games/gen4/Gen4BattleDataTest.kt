package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.pokemonclient.data.BattleStatBoost
import dev.kotlinds.pokemonclient.data.EffortStat
import dev.kotlinds.pokemonclient.data.HpRestore
import dev.kotlinds.pokemonclient.data.ItemEffect
import dev.kotlinds.pokemonclient.data.PpRestore
import dev.kotlinds.pokemonclient.data.StatusCure
import dev.kotlinds.pokemonclient.state.BattleWeather
import dev.kotlinds.pokemonclient.state.WeatherKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Gen 4 battle weather word and item records, decoded the way both engines store them. */
class Gen4BattleDataTest {

    @Test
    fun weatherFromTheFieldConditionWord() {
        // FIELD_CONDITION_SUN (bit 4) from Sunny Day, 3 turns left; SUN_PERMANENT (bit 5) from Drought: no count.
        assertEquals(BattleWeather(WeatherKind.SUN, 3), Gen4BattleWeather.of(1L shl 4, 3))
        assertEquals(BattleWeather(WeatherKind.SUN, null), Gen4BattleWeather.of(1L shl 5, 0))
        assertEquals(BattleWeather(WeatherKind.RAIN, 5), Gen4BattleWeather.of(1L, 5))
        assertEquals(BattleWeather(WeatherKind.HAIL, 1), Gen4BattleWeather.of(1L shl 6, 1))
        assertEquals(BattleWeather(WeatherKind.FOG, null), Gen4BattleWeather.of(1L shl 15, 0))
        // Uproar / Gravity / Trick Room bits aren't weather.
        assertEquals(BattleWeather(WeatherKind.CLEAR, null), Gen4BattleWeather.of((3L shl 8) or (7L shl 12), 0))
        assertEquals("sun (3 turns left)", BattleWeather(WeatherKind.SUN, 3).describe())
        assertEquals("rain (until the battle ends)", BattleWeather(WeatherKind.RAIN, null).describe())
    }

    /** An item record used on a Pokémon (`partyUse`) with its `ItemPartyParam` bytes [param] (from offset 0x0E). */
    private fun record(vararg param: Pair<Int, Int>, partyUse: Boolean = true) = ByteArray(36).also { d ->
        if (partyUse) d[Gen4ItemData.PARTY_USE] = 1
        param.forEach { (i, v) -> d[Gen4ItemData.PARTY_PARAM + i] = v.toByte() }
    }

    @Test
    fun itemEffectsFromThePartyUseParameters() {
        // Potion: hp_restore (byte 5 bit 2), 20 HP.
        assertEquals(ItemEffect(hp = HpRestore.Points(20)), Gen4ItemData.effect(record(5 to 0x04, 13 to 20)))
        // Full Restore: every status, full HP.
        assertEquals(ItemEffect(hp = HpRestore.Full, cures = StatusCure.entries.toSet()), Gen4ItemData.effect(record(0 to 0x7F, 5 to 0x04, 13 to 255)))
        // Revive: revive (byte 1 bit 0), half the HP.
        assertEquals(ItemEffect(hp = HpRestore.Half, revives = true), Gen4ItemData.effect(record(1 to 0x01, 5 to 0x04, 13 to 254)))
        // Ether: 10 PP to one move; Max Elixir: all PP to every move.
        assertEquals(ItemEffect(pp = PpRestore(10, allMoves = false)), Gen4ItemData.effect(record(5 to 0x01, 14 to 10)))
        assertEquals(ItemEffect(pp = PpRestore(null, allMoves = true)), Gen4ItemData.effect(record(5 to 0x02, 14 to 127)))
        // X Attack: atk_stages (byte 1 high nibble) +1; Dire Hit: crit stages (byte 4 bits 4-5).
        assertEquals(ItemEffect(statStages = mapOf(BattleStatBoost.ATTACK to 1)), Gen4ItemData.effect(record(1 to 0x10)))
        assertEquals(ItemEffect(statStages = mapOf(BattleStatBoost.CRITICAL_HIT to 2)), Gen4ItemData.effect(record(4 to 0x20)))
        // HP Up: hp_ev_up (byte 5 bit 3), +10.
        assertEquals(ItemEffect(effortValues = mapOf(EffortStat.HP to 10)), Gen4ItemData.effect(record(5 to 0x08, 7 to 10)))
        // A Poké Ball: not used on a Pokémon (its unused bytes say "sleep, poison": item_data.csv), no effect.
        assertNull(Gen4ItemData.effect(record(0 to 0x03, partyUse = false)))
        assertNull(Gen4ItemData.effect(record()))
    }
}
