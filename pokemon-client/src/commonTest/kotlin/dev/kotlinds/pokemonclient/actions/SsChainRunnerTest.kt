package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BagItem
import dev.kotlinds.pokemonclient.state.BagPocket
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Chains of actions in battle and their limits ([ChainRunner]), with a scripted game. */
class SsChainRunnerTest {

    private val piloswine = MonId(0x033ef671, 0x76f3a6fb)

    private fun battler(ref: BattlerRef, species: Int, name: String, hp: Int, personality: Long? = null, mon: MonId? = null, slot: Int? = null) = BattlerState(
        ref = ref, mon = mon, species = Named(SpeciesId(species), name), nickname = null, level = 50, hp = hp, maxHp = 150,
        status = null, volatile = emptySet(), statStages = statMap(), types = emptyList(), moves = emptyList(), personality = personality,
        partySlot = slot,
    )

    private fun statMap() = emptyMap<dev.kotlinds.pokemonclient.state.BattleStat, Int>()

    private fun state(vararg battlers: BattlerState) = GameState(
        0, Screen.Battle(Awaiting.INPUT), null, emptyList(), null,
        BattleState(BattleKind.TRAINER, false, BattlerRef.PLAYER_LEFT, battlers.toList(), listOf("Leader Misty"), emptyList(), null), null,
    )

    private val me = battler(BattlerRef.PLAYER_LEFT, 221, "PILOSWINE", 120, mon = piloswine)
    private val golduck = battler(BattlerRef.FOE_LEFT, 55, "GOLDUCK", 100, personality = 1)
    private val quagsire = battler(BattlerRef.FOE_LEFT, 195, "QUAGSIRE", 140, personality = 2)

    private fun attack(n: Int) = GameAction.Attack(MoveRef("move:$n"))

    /** Runs [actions]; step i leaves the game in `after[i]`. */
    private suspend fun run(
        start: GameState,
        after: List<GameState>,
        vararg actions: GameAction,
        limits: ChainLimits? = null,
        idle: () -> Duration = { Duration.ZERO },
        time: TestTimeSource = TestTimeSource(),
        stepTime: Duration = Duration.ZERO,
        outcome: (index: Int) -> ActionOutcome = { ActionOutcome.Done() },
    ): Pair<ChainResult, List<Int>> {
        var current = start
        val executed = mutableListOf<Int>()
        val result = ChainRunner(
            observe = { current },
            execute = { _, index ->
                executed += index
                time += stepTime
                current = after[index]
                outcome(index)
            },
            limits = limits,
            idle = idle,
            timeSource = time,
        ).run(actions.toList())
        return result to executed
    }

    @Test
    fun theChainStopsWhenTheAiSwitchesItsPokemon() = runTest {
        // Misty's AI: GOLDUCK out, QUAGSIRE (Water Absorb, immune to Ground... and to the planned move) in.
        val (result, executed) = run(state(me, golduck), listOf(state(me, quagsire), state(me, quagsire)), attack(1), attack(2))
        assertEquals(listOf(0), executed)
        val stop = assertIs<ChainStop.FoeChanged>(result.stop)
        assertEquals("GOLDUCK" to "QUAGSIRE", stop.before to stop.now)
        assertEquals("FOE_CHANGED", stop.code)
        assertEquals(listOf("attack(move:2)"), result.skipped.map { it.key })
        assertNull(result.failed)
    }

    @Test
    fun aPokemonOfTheSameSpeciesSentAfterAKnockOutIsAnotherOne() = runTest {
        val electrode = battler(BattlerRef.FOE_LEFT, 101, "ELECTRODE", 100, personality = 7)
        val second = electrode.copy(personality = 8, hp = 150)
        val (result, executed) = run(state(me, electrode), listOf(state(me, second), state(me, second)), attack(1), attack(1))
        assertEquals(listOf(0), executed)
        assertIs<ChainStop.FoeChanged>(result.stop)
    }

    @Test
    fun aTrainersPokemonOfTheSameSpeciesAndLevelIsToldApartByItsPartySlot() = runTest {
        // A trainer's Pokémon get their personality from the trainer, species and level: two Doduo Lv50 share it.
        val doduo = battler(BattlerRef.FOE_LEFT, 84, "DODUO", 30, personality = 0x5a00, slot = 0)
        val next = doduo.copy(hp = 150, partySlot = 1)
        val (result, executed) = run(state(me, doduo), listOf(state(me, next), state(me, next)), attack(1), attack(1))
        assertEquals(listOf(0), executed)
        val stop = assertIs<ChainStop.FoeChanged>(result.stop)
        assertEquals("DODUO" to "DODUO", stop.before to stop.now)
    }

    @Test
    fun theSamePokemonInTheSameSlotIsNoChange() = runTest {
        val doduo = battler(BattlerRef.FOE_LEFT, 84, "DODUO", 150, personality = 0x5a00, slot = 0)
        val (result, executed) = run(state(me, doduo), listOf(state(me, doduo.copy(hp = 90)), state(me, doduo.copy(hp = 20))), attack(1), attack(1))
        assertEquals(listOf(0, 1), executed)
        assertNull(result.stop)
    }

    @Test
    fun aStepRefusedBecauseTheSwitchPromptCameUpMeanwhileIsGivenBackWithTheReason() = runTest {
        // The race: checked while the K.O. was still playing (the old Doduo standing), the attack then found the
        // "Will you switch?" prompt with the next Doduo already loaded: not a failure, a foe change.
        val doduo = battler(BattlerRef.FOE_LEFT, 84, "DODUO", 30, personality = 0x5a00, slot = 0)
        val prompt = state(me, doduo.copy(hp = 150, partySlot = 1))
        val refused = ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "attack isn't possible on this screen"))
        val (result, executed) = run(state(me, doduo), listOf(state(me, doduo), prompt, prompt), attack(1), attack(1), attack(2)) { index ->
            if (index == 1) refused else ActionOutcome.Done()
        }
        assertEquals(listOf(0, 1), executed)
        assertNull(result.failed)
        assertEquals("FOE_CHANGED", result.stop?.code)
        assertEquals(listOf("attack(move:1)", "attack(move:2)"), result.skipped.map { it.key })
        assertEquals(listOf("attack(move:1)"), result.performed)
    }

    @Test
    fun aStepRefusedWithoutAnyBattleChangeIsStillAFailure() = runTest {
        val refused = ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PP, "no PP"))
        val (result, _) = run(state(me, golduck), listOf(state(me, golduck), state(me, golduck)), attack(1), attack(1)) { index ->
            if (index == 1) refused else ActionOutcome.Done()
        }
        assertEquals(UnavailableReason.NO_PP, (result.failed?.second as? ActionError.Unavailable)?.reason)
        assertNull(result.stop)
    }

    @Test
    fun theChainStopsWhenTheFoeFainted() = runTest {
        val (result, executed) = run(state(me, golduck), listOf(state(me, golduck.copy(hp = 0)), state(me, quagsire)), attack(1), attack(1))
        assertEquals(listOf(0), executed)
        assertEquals("FOE_FAINTED", result.stop?.code)
    }

    @Test
    fun theBattleStepsLeftAreDroppedWhenTheLastFoeFaintedAndTheTrainerBattleIsWon() = runTest {
        // The K.O. of Misty's last Pokémon ends the battle during the step: the attacks left have nothing to act on.
        val after = state(me, golduck).copy(screen = Screen.Overworld(null, Awaiting.INPUT), battle = null)
        val (result, executed) = run(state(me, golduck), listOf(after, after), attack(1), attack(1), attack(2))
        assertEquals(listOf(0), executed)
        assertNull(result.failed)
        assertNull(result.stop)
        assertEquals(BattleEnd.WON, result.droppedBecause?.outcome)
        assertEquals("BATTLE_WON", result.droppedBecause?.code)
        assertEquals(listOf("attack(move:1)", "attack(move:2)"), result.dropped.map { it.key })
        assertEquals(emptyList(), result.skipped)
        assertEquals(listOf("attack(move:1)"), result.performed)
    }

    private val wild = state(me, golduck).let { it.copy(battle = it.battle!!.copy(kind = BattleKind.WILD, trainers = emptyList())) }
    private val bag = listOf(
        BagPocket("items", listOf(BagItem(Named(ItemId(76), "Super Repel"), 3))),
        BagPocket("medicine", listOf(BagItem(Named(ItemId(17), "Potion"), 2))),
        BagPocket("battle_items", listOf(BagItem(Named(ItemId(57), "X Attack"), 1))),
    )
    private val field = wild.copy(screen = Screen.Overworld(null, Awaiting.INPUT), battle = null, bag = bag)

    @Test
    fun aWildBattleOverDropsTheBattleStepsWithoutClaimingAWin() = runTest {
        val (result, _) = run(wild, listOf(field, field), attack(1), GameAction.ThrowBall(ItemRef("item:4")))
        assertEquals("BATTLE_OVER", result.droppedBecause?.code)
        assertEquals(1, result.dropped.size)
    }

    @Test
    fun onceTheWildFoeIsKnockedOutTheFieldStepsAfterTheDroppedAttacksGoOn() = runTest {
        // The agent's chain: the wild foe falls to the first attack, the trainer behind is still talked to.
        val talk = GameAction.Interact("npc:3")
        val (result, executed) = run(wild, List(3) { field }, attack(1), attack(1), talk)
        assertEquals(listOf(0, 2), executed)
        assertEquals(listOf("attack(move:1)", talk.key), result.performed)
        assertEquals(listOf("attack(move:1)"), result.dropped.map { it.key })
        assertEquals("BATTLE_OVER", result.droppedBecause?.code)
        assertNull(result.stop)
        assertEquals(emptyList(), result.skipped)
    }

    @Test
    fun aBattleOnlyItemIsDroppedButAFieldItemIsUsedAfterTheBattle() = runTest {
        val xAttack = GameAction.UseItem(ItemRef("X Attack"))
        val repel = GameAction.UseItem(ItemRef("Super Repel"))
        val potion = GameAction.UseItem(ItemRef("item:17"), target = piloswine)
        val (result, executed) = run(wild, List(4) { field }, attack(1), xAttack, repel, potion)
        assertEquals(listOf(0, 2, 3), executed)
        assertEquals(listOf(xAttack.key), result.dropped.map { it.key })
    }

    @Test
    fun theBattleStepDecisionReadsThePocketsByIdNotByName() {
        assertTrue(BattleWatch.isBattleStep(GameAction.UseItem(ItemRef("item:57")), field))
        assertFalse(BattleWatch.isBattleStep(GameAction.UseItem(ItemRef("item:76")), field))
        assertFalse(BattleWatch.isBattleStep(GameAction.LearnMove(null), field))
        assertTrue(BattleWatch.isBattleStep(GameAction.KeepBattling, field))
    }

    @Test
    fun aFailedEscapeStopsTheChainWithItsOwnCode() = runTest {
        // "Can't escape!": the run was done (the turn is used) but the Super Repel after it was meant for the field.
        val repel = GameAction.UseItem(ItemRef("Super Repel"))
        val (result, executed) = run(wild, listOf(wild, wild), GameAction.Run, repel) { index ->
            if (index == 0) ActionOutcome.Done("couldn't escape: the battle goes on", stopsChain = ChainStop.EscapeFailed("GOLDUCK")) else ActionOutcome.Done()
        }
        assertEquals(listOf(0), executed)
        assertNull(result.failed)
        assertEquals(listOf("run"), result.performed)
        val stop = assertIs<ChainStop.EscapeFailed>(result.stop)
        assertEquals("ESCAPE_FAILED", stop.code)
        assertEquals(listOf(repel.key), result.skipped.map { it.key })
    }

    @Test
    fun aSuccessfulEscapeLetsTheFieldStepsGoOn() = runTest {
        val repel = GameAction.UseItem(ItemRef("Super Repel"))
        val (result, executed) = run(wild, listOf(field, field), GameAction.Run, repel) { ActionOutcome.Done() }
        assertEquals(listOf(0, 1), executed)
        assertNull(result.stop)
    }

    @Test
    fun stepsMeantForAfterTheBattleGoOnOnceItIsOver() = runTest {
        val after = state(me, golduck).copy(screen = Screen.Overworld(null, Awaiting.INPUT), battle = null)
        val (result, executed) = run(state(me, golduck), listOf(after, after), attack(1), GameAction.Wait())
        assertEquals(listOf(0, 1), executed)
        assertNull(result.stop)
    }

    @Test
    fun theChainStopsWhenOurPokemonFainted() = runTest {
        val (result, executed) = run(state(me, golduck), listOf(state(me.copy(hp = 0), golduck), state(me, golduck)), attack(1), attack(1))
        assertEquals(listOf(0), executed)
        val stop = assertIs<ChainStop.OwnFainted>(result.stop)
        assertEquals(piloswine, stop.mon)
    }

    @Test
    fun theSameFoeLosingHpLetsTheChainGoOn() = runTest {
        val (result, executed) = run(state(me, golduck), listOf(state(me, golduck.copy(hp = 40)), state(me, golduck.copy(hp = 10))), attack(1), attack(1))
        assertEquals(listOf(0, 1), executed)
        assertNull(result.stop)
        assertEquals(2, result.performed.size)
    }

    @Test
    fun ourOwnSwitchIsNoReasonToStop() = runTest {
        val gyarados = battler(BattlerRef.PLAYER_LEFT, 130, "GYARADOS", 150, mon = MonId(0x49c199cc, 0x76f3a6fb))
        val (result, executed) = run(state(me, golduck), listOf(state(gyarados, golduck), state(gyarados, golduck)), GameAction.Switch(gyarados.mon!!), attack(1))
        assertEquals(listOf(0, 1), executed)
        assertNull(result.stop)
    }

    @Test
    fun aChainGoesOnPastTheOldBudgetWhileTheGameProgresses() = runTest {
        // Three 20 s steps (60 s in all): the old 35 s budget would have stopped it; the game kept progressing.
        val limits = ChainLimits(idle = 20.seconds, total = 90.seconds)
        val field = state(me, golduck).copy(battle = null)
        val (result, executed) = run(field, List(3) { field }, GameAction.Wait(), GameAction.Wait(), GameAction.Wait(), limits = limits, idle = { 2.seconds }, stepTime = 20.seconds)
        assertEquals(listOf(0, 1, 2), executed)
        assertNull(result.stop)
    }

    @Test
    fun aChainStopsWhenNothingProgresses() = runTest {
        val limits = ChainLimits(idle = 20.seconds, total = 90.seconds)
        val field = state(me, golduck).copy(battle = null)
        val (result, executed) = run(field, List(3) { field }, GameAction.Wait(), GameAction.Wait(), GameAction.Wait(), limits = limits, idle = { 25.seconds }, stepTime = 25.seconds)
        assertEquals(listOf(0), executed)
        val stop = assertIs<ChainStop.Idle>(result.stop)
        assertEquals("IDLE", stop.code)
        assertEquals(2, result.skipped.size)
    }

    @Test
    fun theSafetyCapStopsEvenAProgressingChain() = runTest {
        val limits = ChainLimits(idle = 20.seconds, total = 90.seconds)
        val field = state(me, golduck).copy(battle = null)
        val (result, executed) = run(field, List(4) { field }, *Array(4) { GameAction.Wait() }, limits = limits, idle = { 1.seconds }, stepTime = 50.seconds)
        assertEquals(listOf(0, 1), executed)
        assertEquals("TIME_CAP", result.stop?.code)
    }
}
