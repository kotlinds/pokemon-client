package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.pokemonclient.state.BattleWeather
import dev.kotlinds.pokemonclient.state.WeatherKind

/**
 * The weather of a Gen 4 battle, from `BattleContext.fieldCondition` and `fieldConditionData.weatherTurns`
 * (include/battle/battle.h): both engines (HeartGold / SoulSilver, Platinum) keep it the same way
 * (`FIELD_CONDITION_*`, include/constants/battle.h).
 */
object Gen4BattleWeather {

    /**
     * Each weather's two bits: the move-set one (counted down in `weatherTurns`) and the PERMANENT one (an ability, or
     * the weather of the place the battle started in), in the bit order of the game.
     */
    private val WEATHERS = listOf(
        WeatherKind.RAIN to 0,
        WeatherKind.SANDSTORM to 2,
        WeatherKind.SUN to 4,
        WeatherKind.HAIL to 6,
    )

    /** `FIELD_CONDITION_FOG`: only from the place (never counted down). */
    private const val FOG_BIT = 15

    /** The weather [fieldCondition] holds, with its turns left from [weatherTurns] (a move's weather lasts 5 turns). */
    fun of(fieldCondition: Long, weatherTurns: Int): BattleWeather {
        for ((kind, bit) in WEATHERS) {
            if (fieldCondition shr (bit + 1) and 1L == 1L) return BattleWeather(kind, null)
            if (fieldCondition shr bit and 1L == 1L) return BattleWeather(kind, weatherTurns.takeIf { it in 0..MAX_TURNS })
        }
        if (fieldCondition shr FOG_BIT and 1L == 1L) return BattleWeather(WeatherKind.FOG, null)
        return BattleWeather(WeatherKind.CLEAR, null)
    }

    /** Weather set by a move lasts 5 turns, 8 with the matching rock (Damp Rock, Heat Rock...). */
    private const val MAX_TURNS = 8
}
