package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.BattleOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The battle's outcome known before it leaves the screen ([dev.kotlinds.pokemonclient.state.BattleState.outcome]), on
 * real HeartGold snapshots: Red on Mt. Silver, the player's last Pokémon (Typhlosion, 18 HP) hit by Blastoise's Flash
 * Cannon. The game only sets its end flag once the faint is processed; the end rule computed from the HP left decides
 * as soon as the HP is 0.
 */
class HgssBattleOutcomeFixtureTest {

    private fun info(name: String) = HgssReader(HgssFixtures.load(name), HgssVersion.HEARTGOLD_US).read()!!.battle!!

    private fun outcome(name: String) = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).battle!!.outcome

    @Test
    fun undecidedWhileTheLastPokemonStillHasHp() {
        assertNull(outcome("bt_red_last_hit_pending"))
        assertEquals(emptySet(), info("bt_red_last_hit_pending").sidesOut)
    }

    @Test
    fun lostAsSoonAsTheLastPokemonIsAtZeroHpBeforeTheGameSetsItsFlag() {
        val info = info("bt_red_lost_hp_zero")
        assertEquals(0, info.outcomeFlag)
        assertEquals(setOf(0), info.sidesOut)
        assertEquals(BattleOutcome.LOST, outcome("bt_red_lost_hp_zero"))
    }

    @Test
    fun theGamesOwnFlagOnceItProcessedTheFaint() {
        // "ACE is out of usable Pokémon!": battleOutcomeFlag (BattleSystem + 0x2420) = BATTLE_OUTCOME_LOSE.
        val info = info("bt_red_lost_flag_set")
        assertEquals(2, info.outcomeFlag)
        assertEquals(BattleOutcome.LOST, outcome("bt_red_lost_flag_set"))
    }
}
