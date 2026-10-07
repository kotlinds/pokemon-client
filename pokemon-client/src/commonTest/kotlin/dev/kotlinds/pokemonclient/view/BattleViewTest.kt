package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.data.MoveCategory
import dev.kotlinds.pokemonclient.games.hgss.HgssStatuses
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattleWeather
import dev.kotlinds.pokemonclient.state.VolatileStatus
import dev.kotlinds.pokemonclient.state.WeatherKind
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.MajorStatus
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.SpeciesId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** The battle as agents read it: move battle data and the toxic counter. */
class BattleViewTest {
    private fun battler(status: MajorStatus?, moves: List<KnownMove>) = BattlerState(
        BattlerRef.PLAYER_LEFT, null, Named(SpeciesId(181), "AMPHAROS"), null, 36, 50, 117, status,
        emptySet(), emptyMap(), listOf("Electric"), moves,
    )

    private fun view(b: BattlerState): JsonObject =
        (StateView.battle(BattleState(BattleKind.WILD, false, null, listOf(b), emptyList(), emptyList(), null))["battlers"] as JsonArray)[0] as JsonObject

    @Test
    fun movesShowPowerAccuracyCategoryAndPriority() {
        val moves = listOf(
            KnownMove(Named(MoveId(84), "ThunderShock"), 30, 30, "Electric", 40, 100, MoveCategory.SPECIAL, 0),
            KnownMove(Named(MoveId(98), "Quick Attack"), 30, 30, "Normal", 40, 100, MoveCategory.PHYSICAL, 1),
            KnownMove(Named(MoveId(86), "Thunder Wave"), 20, 20, "Electric", 0, 100, MoveCategory.STATUS, 0),
            KnownMove(Named(MoveId(129), "Swift"), 20, 20, "Normal", 60, 0, MoveCategory.SPECIAL, 0),
        )
        val shown = (view(battler(null, moves))["moves"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(
            listOf(
                "move:84 ThunderShock (Electric, special, power 40, accuracy 100%) 30/30",
                "move:98 Quick Attack (Normal, physical, power 40, accuracy 100%, priority +1) 30/30",
                "move:86 Thunder Wave (Electric, status, accuracy 100%) 20/20",
                "move:129 Swift (Normal, special, power 60, never misses) 20/20",
            ),
            shown,
        )
    }

    @Test
    fun theToxicCounterIsShownInBattle() {
        // STATUS_BAD_POISON (bit 7) with the counter in bits 8-11: two turns of Toxic damage so far.
        val status = HgssStatuses.major(0x80L or (2L shl 8))
        assertEquals(MajorStatus.BadlyPoisoned(2), status)
        assertEquals("badly poisoned(2, next 3/16 HP)", view(battler(status, emptyList()))["status"]?.jsonPrimitive?.content)
        assertEquals("badly poisoned", view(battler(MajorStatus.BadlyPoisoned(0), emptyList()))["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun weatherAndTheMovesOfEncoreAndDisableAreShown() {
        // Map randomizer run: Solar Beam fired at once against Erika while the state said nothing of the sunlight.
        val encored = VolatileStatus.Encored(Named(MoveId(55), "Water Gun"), 2)
        val disabled = VolatileStatus.Disabled(Named(MoveId(33), "Tackle"), 4)
        val b = battler(null, emptyList()).copy(volatile = setOf(encored, disabled))
        val sunny = StateView.battle(BattleState(BattleKind.WILD, false, null, listOf(b), emptyList(), emptyList(), null, weather = BattleWeather(WeatherKind.SUN, 3)))
        assertEquals("sun (3 turns left)", sunny["weather"]?.jsonPrimitive?.content)
        val volatile = (((sunny["battlers"] as JsonArray)[0] as JsonObject)["volatile"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(listOf("encored(move:55 Water Gun, 2 turns)", "disabled(move:33 Tackle, 4 turns)"), volatile)
        // Clear skies (or a reader that doesn't know): no line.
        val clear = StateView.battle(BattleState(BattleKind.WILD, false, null, listOf(b), emptyList(), emptyList(), null, weather = BattleWeather(WeatherKind.CLEAR, null)))
        assertEquals(null, clear["weather"])
    }
}
